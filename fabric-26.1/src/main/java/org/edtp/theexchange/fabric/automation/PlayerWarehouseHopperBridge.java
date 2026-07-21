package org.edtp.theexchange.fabric.automation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.fabric.block.AttachedEnderChestSign;
import org.edtp.theexchange.model.ExchangeMutationResult;
import org.edtp.theexchange.model.ExchangeViewState;
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.PlayerExchangeContext;
import org.edtp.theexchange.model.PlayerInventoryConnectionSpec;
import org.edtp.theexchange.service.WarehouseAutomationPlanner;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Async, non-blocking bridge between vanilla hopper ticks and a signed player warehouse. */
public final class PlayerWarehouseHopperBridge {
    private static final long AUTH_FAILURE_BACKOFF_MS = 30_000L;
    private static final java.util.Set<TransferKey> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<EndpointKey, Long> AUTH_RETRY_AFTER = new ConcurrentHashMap<>();

    private PlayerWarehouseHopperBridge() {
    }

    public static Optional<Boolean> push(Level level, BlockPos hopperPos, HopperBlockEntity hopper) {
        Direction facing = level.getBlockState(hopperPos).getValue(HopperBlock.FACING);
        BlockPos chestPos = hopperPos.relative(facing);
        Optional<PlayerInventoryConnectionSpec> endpoint = mappedEndpoint(level, chestPos);
        if (endpoint.isEmpty()) return Optional.empty();
        TheExchangeCore core = readyCore();
        if (core == null) return Optional.of(false);
        PlayerInventoryConnectionSpec connection = endpoint.orElseThrow();
        PlayerExchangeContext actor = automationActor(level, hopperPos);
        EndpointKey endpointKey = EndpointKey.of(actor, connection);
        Optional<InventoryAccess> session = core.findPlayerInventorySession(connection.serverName(), connection.playerName(), actor);
        if (session.isEmpty()) session = PlayerWarehouseAutomationSessions.find(
                core, PlayerWarehouseEndpointId.forBlock(level, chestPos), connection);
        if (session.isEmpty() && (connection.password().isEmpty() || authBackoffActive(endpointKey))) return Optional.of(false);
        TransferKey transferKey = new TransferKey(actor.uuid(), TransferDirection.PUSH);
        if (!IN_FLIGHT.add(transferKey)) return Optional.of(true);
        int sourceSlot = firstNonEmptySlot(hopper);
        if (sourceSlot < 0) { IN_FLIGHT.remove(transferKey); return Optional.of(false); }
        ItemStack reserved = hopper.removeItem(sourceSlot, 1);
        hopper.setChanged();
        NeutralItem item = core.getApi().getItemSerializer().serialize(reserved.copy());
        if (item == null || item.isEmpty() || item.isIncompatible()) {
            restore(level, hopperPos, reserved); IN_FLIGHT.remove(transferKey); return Optional.of(false);
        }
        access(core, connection, actor, endpointKey, session)
                .thenCompose(access -> open(core, connection, access).thenCompose(state -> {
                    var target = WarehouseAutomationPlanner.findPutSlot(state.getItems(), item,
                            core.getApi().getItemSerializer()::getMaxStackSize);
                    if (target.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("玩家仓库没有可放入的槽位"));
                    return core.putRemoteAsync(connection.serverName(), target.getAsInt(), item, actor, access);
                }))
                .whenComplete((result, error) -> core.getApi().runOnMainThread(() -> {
                    try {
                        if (error != null || result == null || !result.isSuccess()) restore(level, hopperPos, reserved);
                    } finally { IN_FLIGHT.remove(transferKey); }
                }));
        return Optional.of(true);
    }

    public static Optional<Boolean> pull(Level level, Hopper hopper) {
        BlockPos chestPos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
        Optional<PlayerInventoryConnectionSpec> endpoint = mappedEndpoint(level, chestPos);
        if (endpoint.isEmpty()) return Optional.empty();
        TheExchangeCore core = readyCore();
        if (core == null || !hasAnySpace(hopper)) return Optional.of(false);
        BlockPos hopperPos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        PlayerInventoryConnectionSpec connection = endpoint.orElseThrow();
        PlayerExchangeContext actor = automationActor(level, hopperPos, hopper);
        EndpointKey endpointKey = EndpointKey.of(actor, connection);
        Optional<InventoryAccess> session = core.findPlayerInventorySession(connection.serverName(), connection.playerName(), actor);
        if (session.isEmpty()) session = PlayerWarehouseAutomationSessions.find(
                core, PlayerWarehouseEndpointId.forBlock(level, chestPos), connection);
        if (session.isEmpty() && (connection.password().isEmpty() || authBackoffActive(endpointKey))) return Optional.of(false);
        TransferKey transferKey = new TransferKey(actor.uuid(), TransferDirection.PULL);
        if (!IN_FLIGHT.add(transferKey)) return Optional.of(true);
        List<ItemStack> destinationSnapshot = snapshot(hopper);
        access(core, connection, actor, endpointKey, session)
                .thenCompose(access -> open(core, connection, access)
                        .thenCompose(state -> takeFirstAcceptable(core, connection, actor, access, destinationSnapshot, state)))
                .whenComplete((result, error) -> core.getApi().runOnMainThread(() -> {
                    try {
                        if (error == null && result != null && result.isSuccess() && result.getItem() != null && !result.getItem().isIncompatible()) {
                            Object decoded = core.getApi().getItemSerializer().deserialize(result.getItem());
                            if (decoded instanceof ItemStack stack && !stack.isEmpty()) {
                                insertPulled(level, hopper, stack);
                            }
                        }
                    } finally { IN_FLIGHT.remove(transferKey); }
                }));
        return Optional.of(true);
    }

    private static CompletableFuture<ExchangeMutationResult> takeFirstAcceptable(TheExchangeCore core,
            PlayerInventoryConnectionSpec connection, PlayerExchangeContext actor,
            InventoryAccess access, List<ItemStack> destinationSnapshot, ExchangeViewState state) {
        var source = WarehouseAutomationPlanner.findTakeSlot(state.getItems(), item -> canAccept(core, destinationSnapshot, item));
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

    private static Optional<PlayerInventoryConnectionSpec> mappedEndpoint(Level level, BlockPos chestPos) {
        return level.getBlockState(chestPos).is(Blocks.ENDER_CHEST) ? AttachedEnderChestSign.find(level, chestPos) : Optional.empty();
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

    private static int firstNonEmptySlot(Container hopper) {
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) if (!hopper.getItem(slot).isEmpty()) return slot;
        return -1;
    }

    private static void restore(Level level, BlockPos hopperPos, ItemStack reserved) {
        if (reserved == null || reserved.isEmpty()) return;
        ItemStack remaining = reserved.copy();
        if (level.getBlockEntity(hopperPos) instanceof Container current) {
            remaining = HopperBlockEntity.addItem(null, current, remaining, null);
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

    private enum TransferDirection { PUSH, PULL }
    private record TransferKey(String actorUuid, TransferDirection direction) { }
    private record EndpointKey(String actorUuid, String target) {
        private static EndpointKey of(PlayerExchangeContext actor, PlayerInventoryConnectionSpec connection) {
            return new EndpointKey(actor.uuid(), connection.redacted().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
