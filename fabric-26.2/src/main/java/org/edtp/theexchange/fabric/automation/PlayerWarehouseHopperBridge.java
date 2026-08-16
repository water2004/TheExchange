package org.edtp.theexchange.fabric.automation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.ExchangeMutationResult;
import org.edtp.theexchange.model.ExchangeViewState;
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.PlayerExchangeContext;
import org.edtp.theexchange.model.PlayerInventoryConnectionSpec;
import org.edtp.theexchange.service.ExchangeService;
import org.edtp.theexchange.service.WarehouseAutomationFairness;
import org.edtp.theexchange.service.WarehouseAutomationGate;
import org.edtp.theexchange.service.ExchangeSlotPlanner;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Async, non-blocking bridge between vanilla hopper ticks and a signed player warehouse. */
public final class PlayerWarehouseHopperBridge {
    private static final long AUTH_FAILURE_BACKOFF_MS = 30_000L;
    private static final WarehouseAutomationGate<String> OPERATIONS = new WarehouseAutomationGate<>();
    private static final WarehouseAutomationFairness<String> FAIRNESS =
            new WarehouseAutomationFairness<>(ExchangeService.INVENTORY_SLOT_COUNT);
    private static final WarehouseAutomationFairness<String> SOURCE_FAIRNESS =
            new WarehouseAutomationFairness<>(5);
    private static final ConcurrentHashMap<EndpointKey, Long> AUTH_RETRY_AFTER = new ConcurrentHashMap<>();

    private PlayerWarehouseHopperBridge() {
    }

    public enum Decision {
        PASS,
        SUCCESS,
        BLOCKED
    }

    public static Decision push(Level level, BlockPos hopperPos, HopperBlockEntity hopper) {
        Direction facing = hopper.getBlockState().getValue(HopperBlock.FACING);
        long chestPosition = BlockPos.asLong(
                hopperPos.getX() + facing.getStepX(),
                hopperPos.getY() + facing.getStepY(),
                hopperPos.getZ() + facing.getStepZ());
        PlayerInventoryConnectionSpec connection = PlayerWarehouseEndpointCache.find(level, chestPosition);
        if (connection == null) return Decision.PASS;
        BlockPos chestPos = BlockPos.of(chestPosition);
        TheExchangeCore core = readyCore();
        if (core == null || !core.isPlayerInventoriesEnabled()) return Decision.BLOCKED;
        PlayerExchangeContext actor = automationActor(level, hopperPos);
        if (OPERATIONS.isBusy(actor.uuid())) return Decision.BLOCKED;
        EndpointKey endpointKey = EndpointKey.of(actor, connection);
        Optional<InventoryAccess> session = core.findPlayerInventorySession(connection.serverName(), connection.playerName(), actor);
        if (session.isEmpty()) session = PlayerWarehouseAutomationSessions.find(
                core, PlayerWarehouseEndpointId.forBlock(level, chestPos), connection);
        if (session.isEmpty() && (connection.password().isEmpty() || authBackoffActive(endpointKey))) return Decision.BLOCKED;
        if (FAIRNESS.shouldYield(actor.uuid(), WarehouseAutomationFairness.Direction.PUSH,
                () -> canStartPull(core, level, hopperPos, hopper, actor))) return Decision.BLOCKED;
        OptionalInt source = SOURCE_FAIRNESS.claimSlot(actor.uuid(),
                slot -> slot < hopper.getContainerSize() && !hopper.getItem(slot).isEmpty());
        if (source.isEmpty()) return Decision.BLOCKED;
        int sourceSlot = source.orElseThrow();
        Optional<WarehouseAutomationGate.Lease<String>> acquired = OPERATIONS.tryAcquire(actor.uuid());
        if (acquired.isEmpty()) return Decision.BLOCKED;
        WarehouseAutomationGate.Lease<String> lease = acquired.orElseThrow();
        FAIRNESS.recordStarted(actor.uuid(), WarehouseAutomationFairness.Direction.PUSH);
        ItemStack reserved = hopper.removeItem(sourceSlot, 1);
        hopper.setChanged();
        NeutralItem item;
        try {
            item = core.getApi().getItemSerializer().serialize(reserved.copy());
        } catch (RuntimeException error) {
            restore(level, hopperPos, sourceSlot, reserved);
            lease.close();
            return Decision.BLOCKED;
        }
        if (item == null || item.isEmpty() || item.isIncompatible()) {
            restore(level, hopperPos, sourceSlot, reserved); lease.close(); return Decision.BLOCKED;
        }
        int slotStart = FAIRNESS.claimSlotStart(actor.uuid());
        try {
            CompletableFuture<ExchangeMutationResult> operation = access(core, connection, actor, endpointKey, session)
                    .thenCompose(access -> open(core, connection, access).thenCompose(state -> {
                        var target = ExchangeSlotPlanner.findPutSlot(state.getItems(), item, slotStart);
                        if (target.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("玩家仓库没有可放入的槽位"));
                        return core.putRemoteAsync(connection.serverName(), target.getAsInt(), item, actor, access);
                    }));
            operation.whenComplete((result, error) -> settleOnMainThread(core, lease, () -> {
                        try {
                            if (error != null || result == null || !result.isSuccess()) {
                                restore(level, hopperPos, sourceSlot, reserved);
                            }
                            if (result != null) result.acknowledgeSettlement();
                        } finally { hopper.setChanged(); }
                    }));
        } catch (RuntimeException error) {
            restore(level, hopperPos, sourceSlot, reserved);
            lease.close();
            return Decision.BLOCKED;
        }
        return Decision.SUCCESS;
    }

    public static Decision pull(Level level, Hopper hopper) {
        BlockPos blockHopperPos = hopper instanceof HopperBlockEntity blockHopper
                ? blockHopper.getBlockPos() : null;
        int hopperX = blockHopperPos == null ? Mth.floor(hopper.getLevelX()) : blockHopperPos.getX();
        int hopperY = blockHopperPos == null ? Mth.floor(hopper.getLevelY()) : blockHopperPos.getY();
        int hopperZ = blockHopperPos == null ? Mth.floor(hopper.getLevelZ()) : blockHopperPos.getZ();
        long chestPosition = BlockPos.asLong(hopperX, hopperY + 1, hopperZ);
        PlayerInventoryConnectionSpec connection = PlayerWarehouseEndpointCache.find(level, chestPosition);
        if (connection == null) return Decision.PASS;
        BlockPos hopperPos = blockHopperPos == null
                ? new BlockPos(hopperX, hopperY, hopperZ) : blockHopperPos;
        BlockPos chestPos = BlockPos.of(chestPosition);
        TheExchangeCore core = readyCore();
        if (core == null || !core.isPlayerInventoriesEnabled() || !hasAnySpace(hopper)) {
            return Decision.BLOCKED;
        }
        PlayerExchangeContext actor = automationActor(level, hopperPos, hopper);
        if (OPERATIONS.isBusy(actor.uuid())) return Decision.BLOCKED;
        EndpointKey endpointKey = EndpointKey.of(actor, connection);
        Optional<InventoryAccess> session = core.findPlayerInventorySession(connection.serverName(), connection.playerName(), actor);
        if (session.isEmpty()) session = PlayerWarehouseAutomationSessions.find(
                core, PlayerWarehouseEndpointId.forBlock(level, chestPos), connection);
        if (session.isEmpty() && (connection.password().isEmpty() || authBackoffActive(endpointKey))) return Decision.BLOCKED;
        Optional<WarehouseAutomationGate.Lease<String>> acquired = OPERATIONS.tryAcquire(actor.uuid());
        if (acquired.isEmpty()) return Decision.BLOCKED;
        WarehouseAutomationGate.Lease<String> lease = acquired.orElseThrow();
        FAIRNESS.recordStarted(actor.uuid(), WarehouseAutomationFairness.Direction.PULL);
        int slotStart = FAIRNESS.claimSlotStart(actor.uuid());
        try {
            List<ItemStack> destinationSnapshot = snapshot(hopper);
            CompletableFuture<ExchangeMutationResult> operation = access(core, connection, actor, endpointKey, session)
                    .thenCompose(access -> open(core, connection, access)
                            .thenCompose(state -> takeFirstAcceptable(core, connection, actor, access,
                                    destinationSnapshot, state, slotStart)));
            operation.whenComplete((result, error) -> settleOnMainThread(core, lease, () -> {
                        boolean applied = result != null && !result.isSuccess();
                        if (error == null && result != null && result.isSuccess() && result.getItem() != null && !result.getItem().isIncompatible()) {
                            Object decoded = core.getApi().getItemSerializer().deserialize(result.getItem());
                            if (decoded instanceof ItemStack stack && !stack.isEmpty()) {
                                insertPulled(level, hopper, stack);
                                applied = true;
                            }
                        }
                        if (applied) result.acknowledgeSettlement();
                    }));
        } catch (RuntimeException error) {
            lease.close();
            return Decision.BLOCKED;
        }
        return Decision.SUCCESS;
    }

    private static CompletableFuture<ExchangeMutationResult> takeFirstAcceptable(TheExchangeCore core,
            PlayerInventoryConnectionSpec connection, PlayerExchangeContext actor,
            InventoryAccess access, List<ItemStack> destinationSnapshot, ExchangeViewState state,
            int slotStart) {
        var source = ExchangeSlotPlanner.findTakeSlot(state.getItems(),
                item -> canAccept(core, destinationSnapshot, item), slotStart);
        if (source.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("玩家仓库没有漏斗可接收的兼容物品"));
        return core.takeRemoteAsync(connection.serverName(), source.getAsInt(), 1, actor, access);
    }

    private static CompletableFuture<InventoryAccess> access(TheExchangeCore core,
            PlayerInventoryConnectionSpec connection, PlayerExchangeContext actor,
            EndpointKey endpointKey, Optional<InventoryAccess> existing) {
        if (existing.isPresent()) return CompletableFuture.completedFuture(existing.orElseThrow());
        return core.authenticatePlayerInventoryAsync(connection.serverName(), connection.playerName(),
                        connection.password().orElseThrow(), actor)
                .whenComplete((ignored, error) -> {
                    if (error == null) AUTH_RETRY_AFTER.remove(endpointKey);
                    else AUTH_RETRY_AFTER.put(endpointKey, System.currentTimeMillis() + AUTH_FAILURE_BACKOFF_MS);
                });
    }

    private static CompletableFuture<ExchangeViewState> open(TheExchangeCore core,
            PlayerInventoryConnectionSpec connection, InventoryAccess access) {
        String localName = core.getRuntimeConfig().getDisplayName();
        return "local".equalsIgnoreCase(connection.serverName()) || localName.equalsIgnoreCase(connection.serverName())
                ? core.openLocalViewAsync(localName, access) : core.openRemoteViewAsync(connection.serverName(), access);
    }

    private static boolean canStartPull(TheExchangeCore core, Level level, BlockPos hopperPos,
                                        Hopper hopper, PlayerExchangeContext actor) {
        if (!hasAnySpace(hopper)) return false;
        BlockPos chestPos = hopperPos.above();
        PlayerInventoryConnectionSpec connection = PlayerWarehouseEndpointCache.find(level, chestPos);
        if (connection == null) return false;
        EndpointKey endpointKey = EndpointKey.of(actor, connection);
        Optional<InventoryAccess> session = core.findPlayerInventorySession(
                connection.serverName(), connection.playerName(), actor);
        if (session.isEmpty()) session = PlayerWarehouseAutomationSessions.find(
                core, PlayerWarehouseEndpointId.forBlock(level, chestPos), connection);
        return session.isPresent()
                || (connection.password().isPresent() && !authBackoffActive(endpointKey));
    }

    private static boolean canAccept(TheExchangeCore core, List<ItemStack> destination, NeutralItem item) {
        if (item == null || item.isEmpty() || item.isIncompatible()) return false;
        Object decoded = core.getApi().getItemSerializer().deserialize(item);
        if (!(decoded instanceof ItemStack candidate) || candidate.isEmpty()) return false;
        for (ItemStack current : destination) {
            if (current.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(current, candidate)
                    && current.getCount() < current.getMaxStackSize()) return true;
        }
        return false;
    }

    private static List<ItemStack> snapshot(Container container) {
        java.util.ArrayList<ItemStack> result = new java.util.ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot).copy());
        return result;
    }

    private static void insertPulled(Level level, Hopper hopper, ItemStack stack) {
        boolean available = !(hopper instanceof HopperBlockEntity blockHopper)
                || level.getBlockEntity(blockHopper.getBlockPos()) == blockHopper;
        if (hopper instanceof Entity entity) available = entity.isAlive();
        ItemStack remaining = available ? HopperBlockEntity.addItem(null, hopper, stack.copy(), null) : stack.copy();
        if (!remaining.isEmpty()) Containers.dropItemStack(level, hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ(), remaining);
    }

    private static boolean hasAnySpace(Container hopper) {
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            ItemStack current = hopper.getItem(slot);
            if (current.isEmpty() || current.getCount() < current.getMaxStackSize()) return true;
        }
        return false;
    }

    private static void restore(Level level, BlockPos hopperPos, int sourceSlot, ItemStack reserved) {
        if (reserved == null || reserved.isEmpty()) return;
        ItemStack remaining = reserved.copy();
        if (level.getBlockEntity(hopperPos) instanceof Container current
                && sourceSlot >= 0 && sourceSlot < current.getContainerSize()) {
            ItemStack existing = current.getItem(sourceSlot);
            if (existing.isEmpty() && current.canPlaceItem(sourceSlot, remaining)
                    && remaining.getCount() <= remaining.getMaxStackSize()) {
                current.setItem(sourceSlot, remaining);
                current.setChanged();
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameComponents(existing, remaining)) {
                int capacity = Math.max(0, existing.getMaxStackSize() - existing.getCount());
                int restored = Math.min(capacity, remaining.getCount());
                if (restored > 0) {
                    existing.grow(restored);
                    remaining.shrink(restored);
                    current.setChanged();
                }
            }
        }
        if (!remaining.isEmpty()) Containers.dropItemStack(level, hopperPos.getX() + 0.5,
                hopperPos.getY() + 0.5, hopperPos.getZ() + 0.5, remaining);
    }

    private static PlayerExchangeContext automationActor(Level level, BlockPos hopperPos) {
        String endpoint = level.dimension().identifier() + ":" + hopperPos.toShortString();
        String uuid = UUID.nameUUIDFromBytes(("theexchange:hopper:" + endpoint).getBytes(StandardCharsets.UTF_8)).toString();
        return new PlayerExchangeContext(uuid, "Hopper " + hopperPos.toShortString());
    }

    private static PlayerExchangeContext automationActor(Level level, BlockPos hopperPos, Hopper hopper) {
        if (hopper instanceof Entity entity) return new PlayerExchangeContext(entity.getUUID().toString(), "Hopper Minecart");
        return automationActor(level, hopperPos);
    }

    private static boolean authBackoffActive(EndpointKey key) {
        Long retryAfter = AUTH_RETRY_AFTER.get(key);
        if (retryAfter == null) return false;
        if (retryAfter <= System.currentTimeMillis()) { AUTH_RETRY_AFTER.remove(key, retryAfter); return false; }
        return true;
    }

    private static TheExchangeCore readyCore() {
        TheExchangeCore core = TheExchangeCore.getInstance();
        return core != null && core.isInitialized() ? core : null;
    }

    private static void settleOnMainThread(TheExchangeCore core,
                                           WarehouseAutomationGate.Lease<String> lease,
                                           Runnable settlement) {
        try {
            core.getApi().runOnMainThread(() -> {
                try {
                    settlement.run();
                } finally {
                    lease.close();
                }
            });
        } catch (RuntimeException schedulingError) {
            lease.close();
            core.getApi().getLogger().error(
                    "[Exchange|Hopper] Failed to schedule async operation settlement",
                    schedulingError);
        }
    }

    public static void reset() {
        OPERATIONS.clear();
        FAIRNESS.clear();
        SOURCE_FAIRNESS.clear();
        AUTH_RETRY_AFTER.clear();
        PlayerWarehouseAutomationSessions.clear();
    }

    private record EndpointKey(String actorUuid, String target) {
        private static EndpointKey of(PlayerExchangeContext actor, PlayerInventoryConnectionSpec connection) {
            return new EndpointKey(actor.uuid(), connection.redacted().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
