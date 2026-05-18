package org.edtp.theexchange.fabric.container;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.service.ExchangeService;

/**
 * Custom AbstractContainerMenu for the exchange GUI.
 * 0-53: Remote exchange space (ExchangeSlot)
 * 54-80: Player inventory (27 slots)
 * 81-89: Player hotbar (9 slots)
 */
public class ExchangeMenu extends AbstractContainerMenu {

    private final ExchangeContainer exchangeContainer;
    private final String serverName;
    private final boolean online;
    private final ServerPlayer player;

    public ExchangeMenu(int containerId, Inventory playerInventory,
                         String serverName, boolean online) {
        super(MenuType.GENERIC_9x6, containerId);
        this.serverName = serverName;
        this.online = online;
        this.player = (ServerPlayer) playerInventory.player;
        this.exchangeContainer = new ExchangeContainer(serverName, online, 6);

        // Load cache
        exchangeContainer.loadFromCache();

        // Remote exchange slots (6 rows × 9 cols)
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = row * 9 + col;
                ExchangeSlot slot = new ExchangeSlot(exchangeContainer, slotIndex,
                        8 + col * 18, 18 + row * 18);
                slot.setReadOnly(!online);
                this.addSlot(slot);
            }
        }

        // Player inventory (3 rows × 9 cols)
        int invTop = 18 + 6 * 18 + 13; // = 139
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, 9 + row * 9 + col,
                        8 + col * 18, invTop + row * 18));
            }
        }

        // Player hotbar
        int hotbarTop = invTop + 3 * 18 + 4; // = 139 + 54 + 4 = 197
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, hotbarTop));
        }
    }

    public ExchangeContainer getExchangeContainer() {
        return exchangeContainer;
    }

    public String getServerName() {
        return serverName;
    }

    public boolean isOnline() {
        return online;
    }

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

        ExchangeService service = TheExchangeCore.getInstance().getExchangeService();

        if (slotIndex < 54) {
            // From exchange → player inventory = TAKE
            if (!online) {
                player.sendSystemMessage(Component.literal("目标服务器离线，仅可查看"));
                return ItemStack.EMPTY;
            }

            // Find the cached version info
            var cache = TheExchangeCore.getInstance().getCacheManager().getCache(serverName);
            int version = 1;
            if (cache != null) {
                var items = cache.getItems();
                if (slotIndex < items.size() && items.get(slotIndex) != null) {
                    // version tracked in cache separately
                    version = 1; // TODO: track per-slot version in cache
                }
            }

            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stackInSlot.getItem()).toString();

            ExchangeService.TakeResult result = service.takeItem(serverName, slotIndex,
                    itemId, version, copy.getCount(),
                    player.getUUID().toString(), player.getName().getString());

            if (result.isSuccess()) {
                slot.set(ItemStack.EMPTY);

                // Give item to player
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

        } else {
            // From player → exchange = PUT
            if (!online) {
                player.sendSystemMessage(Component.literal("目标服务器离线，仅可查看"));
                return ItemStack.EMPTY;
            }

            // Find first empty exchange slot
            int targetSlot = -1;
            for (int i = 0; i < 54; i++) {
                Slot es = this.slots.get(i);
                if (!es.hasItem()) {
                    targetSlot = i;
                    break;
                }
                // Merge with existing stack of same type
                if (es.getItem().getItem() == copy.getItem()
                        && es.getItem().getCount() + copy.getCount() <= es.getMaxStackSize()) {
                    targetSlot = i;
                    break;
                }
            }

            if (targetSlot < 0) {
                player.sendSystemMessage(Component.literal("共享空间已满"));
                return ItemStack.EMPTY;
            }

            ExchangeService.PutResult result = service.putItem(serverName, targetSlot,
                    player.getUUID().toString(), player.getName().getString(), copy);

            if (result.isSuccess()) {
                slot.set(ItemStack.EMPTY);
                // Update the exchange slot display
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
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Cleanup when GUI is closed
    }

    public Component getTitle() {
        String title = (online ? "" : "[离线] ") + serverName + " 的共享空间";
        return Component.literal(title);
    }
}
