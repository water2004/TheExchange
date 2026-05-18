package org.edtp.theexchange.fabric.container;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.messages.PutItemRequest;
import org.edtp.theexchange.network.protocol.messages.TakeItemRequest;
import org.edtp.theexchange.service.ExchangeService;

import java.util.UUID;

/**
 * Custom AbstractContainerMenu for the exchange GUI.
 * Supports both LOCAL (this server's own space) and REMOTE (other server) modes.
 * 0-53: Exchange space (ExchangeSlot)
 * 54-80: Player inventory (27 slots)
 * 81-89: Player hotbar (9 slots)
 */
public class ExchangeMenu extends AbstractContainerMenu {

    private final ExchangeContainer exchangeContainer;
    private final String serverName;
    private final boolean local;     // true = this server's own exchange space
    private final boolean online;
    private final ServerPlayer player;

    public ExchangeMenu(int containerId, Inventory playerInventory,
                         String serverName, boolean local, boolean online) {
        super(MenuType.GENERIC_9x6, containerId);
        this.serverName = serverName;
        this.local = local;
        this.online = online;
        this.player = (ServerPlayer) playerInventory.player;
        this.exchangeContainer = new ExchangeContainer(serverName, online, 6);

        // Load items
        if (local) {
            exchangeContainer.loadFromLocal();
        } else {
            exchangeContainer.loadFromCache();
        }

        // Exchange slots (6 rows × 9 cols = 54)
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = row * 9 + col;
                ExchangeSlot slot = new ExchangeSlot(exchangeContainer, slotIndex,
                        8 + col * 18, 18 + row * 18);
                // Local is always writable; remote requires online
                slot.setReadOnly(!local && !online);
                this.addSlot(slot);
            }
        }

        // Player inventory (3 rows × 9 cols)
        int invTop = 18 + 6 * 18 + 13;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, 9 + row * 9 + col,
                        8 + col * 18, invTop + row * 18));
            }
        }

        // Player hotbar
        int hotbarTop = invTop + 3 * 18 + 4;
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, hotbarTop));
        }
    }

    public boolean isLocal() { return local; }

    @Override
    public boolean stillValid(Player player) {
        return exchangeContainer.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = this.slots.get(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stackInSlot = slot.getItem();
        ItemStack copy = stackInSlot.copy();

        if (slotIndex < 54) {
            // === TAKE: exchange → player inventory ===
            if (!online && !local) {
                player.sendSystemMessage(Component.literal("目标服务器离线，仅可查看"));
                return ItemStack.EMPTY;
            }

            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stackInSlot.getItem()).toString();

            if (local) {
                // Direct local TAKE — no network
                return handleLocalTake(player, slot, slotIndex, itemId, copy);
            } else {
                // Remote TAKE
                return handleRemoteTake(player, slot, slotIndex, itemId, copy);
            }

        } else {
            // === PUT: player inventory → exchange ===
            if (!online && !local) {
                player.sendSystemMessage(Component.literal("目标服务器离线，仅可查看"));
                return ItemStack.EMPTY;
            }

            // Find target slot
            int targetSlot = findTargetSlot(copy);
            if (targetSlot < 0) {
                player.sendSystemMessage(Component.literal("共享空间已满"));
                return ItemStack.EMPTY;
            }

            if (local) {
                // Direct local PUT — no network
                return handleLocalPut(player, slot, targetSlot, copy);
            } else {
                // Remote PUT
                return handleRemotePut(player, slot, targetSlot, copy);
            }
        }
    }

    // ===== Local operations (this server is authoritative) =====

    private ItemStack handleLocalTake(Player player, Slot slot, int slotIndex,
                                       String itemId, ItemStack copy) {
        ExchangeService service = TheExchangeCore.getInstance().getExchangeService();
        String requestId = UUID.randomUUID().toString();

        // Get current version from local store
        var record = TheExchangeCore.getInstance().getLocalItemStore().getItem(slotIndex);
        int expectedVersion = record != null ? record.version() : 0;

        TakeItemRequest request = new TakeItemRequest(slotIndex, itemId,
                expectedVersion, copy.getCount(), requestId,
                player.getUUID().toString(), player.getName().getString());

        var response = service.handleRemoteTake(request);

        if (response.isSuccess()) {
            slot.set(ItemStack.EMPTY);
            if (response.getItemsToGive() != null) {
                Object itemObj = TheExchangeCore.getInstance().getApi()
                        .getItemSerializer().deserialize(response.getItemsToGive());
                if (itemObj instanceof ItemStack giveStack) {
                    if (!((ServerPlayer) player).getInventory().add(giveStack)) {
                        player.drop(giveStack, false);
                    }
                }
            }
            // Refresh local display
            refreshLocalSlots();
            return copy;
        } else {
            player.sendSystemMessage(Component.literal(
                    response.getFailReason() != null ? response.getFailReason() : "取出失败"));
            return ItemStack.EMPTY;
        }
    }

    private ItemStack handleLocalPut(Player player, Slot playerSlot, int targetSlot,
                                      ItemStack copy) {
        ExchangeService service = TheExchangeCore.getInstance().getExchangeService();
        String requestId = UUID.randomUUID().toString();

        // Serialize the item
        NeutralItem item = TheExchangeCore.getInstance().getApi()
                .getItemSerializer().serialize(copy);

        PutItemRequest request = new PutItemRequest(targetSlot, item, requestId,
                player.getUUID().toString(), player.getName().getString());

        var response = service.handleRemotePut(request);

        if (response.isSuccess()) {
            playerSlot.set(ItemStack.EMPTY);
            // Refresh local display
            refreshLocalSlots();
            return copy;
        } else {
            player.sendSystemMessage(Component.literal(
                    response.getFailReason() != null ? response.getFailReason() : "放入失败"));
            return ItemStack.EMPTY;
        }
    }

    // ===== Remote operations (network to target server) =====

    private ItemStack handleRemoteTake(Player player, Slot slot, int slotIndex,
                                        String itemId, ItemStack copy) {
        ExchangeService service = TheExchangeCore.getInstance().getExchangeService();

        var cache = TheExchangeCore.getInstance().getCacheManager().getCache(serverName);
        int version = 1;

        ExchangeService.TakeResult result = service.takeItem(serverName, slotIndex,
                itemId, version, copy.getCount(),
                player.getUUID().toString(), player.getName().getString());

        if (result.isSuccess()) {
            slot.set(ItemStack.EMPTY);
            if (result.getItemsToGive() != null) {
                Object itemObj = TheExchangeCore.getInstance().getApi()
                        .getItemSerializer().deserialize(result.getItemsToGive());
                if (itemObj instanceof ItemStack giveStack) {
                    if (!((ServerPlayer) player).getInventory().add(giveStack)) {
                        player.drop(giveStack, false);
                    }
                }
            }
            return copy;
        } else {
            player.sendSystemMessage(Component.literal(
                    result.getFailReason() != null ? result.getFailReason() : "取出失败"));
            return ItemStack.EMPTY;
        }
    }

    private ItemStack handleRemotePut(Player player, Slot playerSlot, int targetSlot,
                                       ItemStack copy) {
        ExchangeService service = TheExchangeCore.getInstance().getExchangeService();

        ExchangeService.PutResult result = service.putItem(serverName, targetSlot,
                player.getUUID().toString(), player.getName().getString(), copy);

        if (result.isSuccess()) {
            playerSlot.set(ItemStack.EMPTY);
            if (result.getCurrentItem() != null) {
                Object itemObj = TheExchangeCore.getInstance().getApi()
                        .getItemSerializer().deserialize(result.getCurrentItem());
                if (itemObj instanceof ItemStack showStack) {
                    this.slots.get(targetSlot).set(showStack);
                }
            }
            this.exchangeContainer.updateSlotFromCache(targetSlot);
            return copy;
        } else {
            player.sendSystemMessage(Component.literal(
                    result.getFailReason() != null ? result.getFailReason() : "放入失败"));
            return ItemStack.EMPTY;
        }
    }

    // ===== Helpers =====

    private int findTargetSlot(ItemStack stack) {
        // First pass: merge with existing same-type stacks
        for (int i = 0; i < 54; i++) {
            Slot es = this.slots.get(i);
            if (es.hasItem()
                    && es.getItem().getItem() == stack.getItem()
                    && es.getItem().getCount() + stack.getCount() <= es.getMaxStackSize()) {
                return i;
            }
        }
        // Second pass: first empty slot
        for (int i = 0; i < 54; i++) {
            if (!this.slots.get(i).hasItem()) {
                return i;
            }
        }
        return -1;
    }

    private void refreshLocalSlots() {
        var items = TheExchangeCore.getInstance().getLocalItemStore().getAllItems();
        var serializer = TheExchangeCore.getInstance().getApi().getItemSerializer();
        for (int i = 0; i < 54; i++) {
            this.slots.get(i).set(ItemStack.EMPTY);
        }
        for (int i = 0; i < items.size() && i < 54; i++) {
            NeutralItem item = items.get(i);
            if (item != null && !item.isEmpty()) {
                Object mcStack = serializer.deserialize(item);
                if (mcStack instanceof ItemStack stack) {
                    this.slots.get(i).set(stack);
                }
            }
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
    }

    public Component getTitle() {
        String prefix = local ? "[本服] " : (online ? "" : "[离线] ");
        return Component.literal(prefix + serverName + " 的共享空间");
    }
}
