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
import org.edtp.theexchange.service.ExchangeService;

/**
 * Container menu for the exchange GUI.
 * 0-53:  Exchange space (ExchangeSlot, backed by ExchangeContainer)
 * 54-80: Player inventory
 * 81-89: Player hotbar
 *
 * LOCAL mode: ExchangeContainer auto-persists to LocalItemStore on every write.
 *   No special handling needed — vanilla click/move logic triggers setItem/removeItem.
 *
 * REMOTE mode: shift+click triggers network PUT/TAKE via ExchangeService.
 */
public class ExchangeMenu extends AbstractContainerMenu {

    private final ExchangeContainer exchangeContainer;
    private final String serverName;
    private final boolean local;
    private final boolean online;
    private boolean refreshing;

    public ExchangeMenu(int containerId, Inventory playerInventory,
                         String serverName, boolean local, boolean online) {
        super(MenuType.GENERIC_9x6, containerId);
        this.serverName = serverName;
        this.local = local;
        this.online = online;
        this.exchangeContainer = new ExchangeContainer(serverName, local, online, 6);

        // Load initial data
        if (local) {
            exchangeContainer.loadFromLocal();
        } else {
            exchangeContainer.loadFromCache();
        }

        // Exchange slots (6 rows × 9 cols = 54)
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = row * 9 + col;
                this.addSlot(new ExchangeSlot(exchangeContainer, idx,
                        8 + col * 18, 18 + row * 18));
            }
        }

        // Player inventory (3 rows)
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

        // Set read-only state
        updateSlotReadOnly();
    }

    private void updateSlotReadOnly() {
        boolean readOnly = !local && !online;
        for (int i = 0; i < 54; i++) {
            Slot s = this.slots.get(i);
            if (s instanceof ExchangeSlot es) {
                es.setReadOnly(readOnly);
            }
        }
    }

    public boolean isViewingServer(String name) {
        return serverName.equalsIgnoreCase(name);
    }

    public void refreshFromCache() {
        if (refreshing) return;
        refreshing = true;
        try {
            exchangeContainer.clearContent();
            if (local) {
                exchangeContainer.loadFromLocal();
            } else {
                exchangeContainer.loadFromCache();
            }
            updateSlotReadOnly();
            for (int i = 0; i < 54; i++) {
                Slot slot = this.slots.get(i);
                if (slot instanceof ExchangeSlot) {
                    ItemStack stack = exchangeContainer.getItem(i);
                    slot.set(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
                }
            }
            broadcastChanges();
        } finally {
            refreshing = false;
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return exchangeContainer.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = this.slots.get(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack sourceStack = slot.getItem();
        ItemStack copy = sourceStack.copy();

        if (slotIndex < 54) {
            // === Exchange → Player inventory (TAKE) ===
            if (!online && !local) {
                player.sendSystemMessage(Component.literal("目标服务器离线，仅可查看"));
                return ItemStack.EMPTY;
            }

            if (local) {
                // Local: move items via vanilla, container persists automatically
                if (!moveItemStackTo(sourceStack, 54, 90, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Remote: network TAKE
                String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(copy.getItem()).toString();
                ExchangeService service = TheExchangeCore.getInstance().getExchangeService();
                ExchangeService.TakeResult result = service.takeItem(serverName, slotIndex,
                        itemId, 1, copy.getCount(),
                        player.getUUID().toString(), player.getName().getString());

                if (result.isSuccess()) {
                    if (result.getItemsToGive() != null) {
                        Object itemObj = TheExchangeCore.getInstance().getApi()
                                .getItemSerializer().deserialize(result.getItemsToGive());
                        if (itemObj instanceof ItemStack giveStack) {
                            if (!player.getInventory().add(giveStack)) {
                                player.drop(giveStack, false);
                            }
                        }
                    }
                    refreshFromCache();
                } else {
                    player.sendSystemMessage(Component.literal(
                            result.getFailReason() != null ? result.getFailReason() : "取出失败"));
                    return ItemStack.EMPTY;
                }
            }
        } else {
            // === Player inventory → Exchange (PUT) ===
            if (!online && !local) {
                player.sendSystemMessage(Component.literal("目标服务器离线，仅可查看"));
                return ItemStack.EMPTY;
            }

            if (local) {
                // Local: move items via vanilla, container persists automatically
                if (!moveItemStackTo(sourceStack, 0, 54, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Remote: network PUT
                int targetSlot = findTargetSlot(copy);
                if (targetSlot < 0) {
                    player.sendSystemMessage(Component.literal("共享空间已满"));
                    return ItemStack.EMPTY;
                }

                ExchangeService service = TheExchangeCore.getInstance().getExchangeService();
                ExchangeService.PutResult result = service.putItem(serverName, targetSlot,
                        player.getUUID().toString(), player.getName().getString(), copy);

                if (result.isSuccess()) {
                    refreshFromCache();
                } else {
                    player.sendSystemMessage(Component.literal(
                            result.getFailReason() != null ? result.getFailReason() : "放入失败"));
                    return ItemStack.EMPTY;
                }
            }
        }

        if (sourceStack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return copy;
    }

    private int findTargetSlot(ItemStack stack) {
        for (int i = 0; i < 54; i++) {
            Slot es = this.slots.get(i);
            if (es.hasItem()
                    && es.getItem().getItem() == stack.getItem()
                    && es.getItem().getCount() + stack.getCount() <= es.getMaxStackSize()) {
                return i;
            }
        }
        for (int i = 0; i < 54; i++) {
            if (!this.slots.get(i).hasItem()) return i;
        }
        return -1;
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
