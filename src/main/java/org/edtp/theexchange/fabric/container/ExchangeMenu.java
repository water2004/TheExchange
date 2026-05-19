package org.edtp.theexchange.fabric.container;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.api.RefreshableExchangeView;
import org.edtp.theexchange.model.ExchangeViewState;
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
public class ExchangeMenu extends AbstractContainerMenu implements RefreshableExchangeView {

    private final ExchangeContainer exchangeContainer;
    private final String serverName;
    private final boolean local;
    private volatile boolean online;
    private boolean refreshing;

    public ExchangeMenu(int containerId, Inventory playerInventory,
                         ExchangeViewState state) {
        super(MenuType.GENERIC_9x6, containerId);
        this.serverName = state.getServerName();
        this.local = state.isLocal();
        this.online = state.isOnline();
        this.exchangeContainer = new ExchangeContainer(serverName, local, online, 6);
        exchangeContainer.loadFromItems(state.getItems());

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
        applyCachedView();
    }

    private void applyCachedView() {
        if (refreshing) return;
        refreshing = true;
        try {
            ExchangeViewState state = local
                    ? TheExchangeCore.getInstance().getViewService().openLocalView(serverName)
                    : TheExchangeCore.getInstance().getViewService().openRemoteView(serverName);
            online = state.isOnline();
            exchangeContainer.clearContent();
            exchangeContainer.loadFromItems(state.getItems());
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
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (local) {
            handleLocalClick(slotIndex, buttonNum, containerInput, player);
            return;
        }
        if (!touchesExchangeSpace(slotIndex, buttonNum, containerInput)) {
            super.clicked(slotIndex, buttonNum, containerInput, player);
            return;
        }

        if (!online) {
            player.sendSystemMessage(Component.literal("目标服务器离线，仅可查看"));
            refreshFromCache();
            return;
        }

        switch (containerInput) {
            case QUICK_MOVE -> {
                quickMoveStack(player, slotIndex);
                broadcastChanges();
            }
            case PICKUP -> handleRemotePickup(slotIndex, buttonNum, player);
            case SWAP -> handleRemoteSwap(slotIndex, buttonNum, player);
            case QUICK_CRAFT, PICKUP_ALL, THROW, CLONE -> {
                player.sendSystemMessage(Component.literal("远程共享空间暂不支持该操作，请使用点击或 Shift 点击"));
                refreshFromCache();
            }
            default -> refreshFromCache();
        }
    }

    private void handleLocalClick(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        java.util.List<ItemStack> before = snapshotExchangeSlots();
        exchangeContainer.setSuppressPersistence(true);
        try {
            super.clicked(slotIndex, buttonNum, containerInput, player);
        } finally {
            exchangeContainer.setSuppressPersistence(false);
        }

        java.util.List<Integer> changed = new java.util.ArrayList<>();
        var core = TheExchangeCore.getInstance();
        var serializer = core.getApi().getItemSerializer();
        for (int i = 0; i < 54; i++) {
            ItemStack current = exchangeContainer.getItem(i);
            if (ItemStack.matches(before.get(i), current)
                    && ItemStack.isSameItemSameComponents(before.get(i), current)
                    && before.get(i).getCount() == current.getCount()) {
                continue;
            }
            NeutralItem neutral = current.isEmpty() ? null : serializer.serialize(current);
            if (core.getLocalItemStore().replaceSlotFromLocal(i, neutral, player.getUUID().toString())) {
                changed.add(i);
            }
        }
        if (!changed.isEmpty()) {
            core.getExchangeService().publishLocalInventoryUpdate(changed);
        }
    }

    private java.util.List<ItemStack> snapshotExchangeSlots() {
        java.util.List<ItemStack> snapshot = new java.util.ArrayList<>(54);
        for (int i = 0; i < 54; i++) {
            snapshot.add(exchangeContainer.getItem(i).copy());
        }
        return snapshot;
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
                if (takeRemoteSlotToPlayer(slotIndex, copy.getCount(), player)) {
                    refreshFromCache();
                } else {
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
                int targetSlot = findTargetSlot(copy);
                if (targetSlot < 0) {
                    player.sendSystemMessage(Component.literal("共享空间已满"));
                    return ItemStack.EMPTY;
                }
                int putCount = copy.getCount();
                if (putRemoteStack(targetSlot, copy, putCount, player)) {
                    sourceStack.shrink(copy.getCount());
                    refreshFromCache();
                } else {
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

    private void handleRemotePickup(int slotIndex, int buttonNum, Player player) {
        if (slotIndex < 0) {
            super.clicked(slotIndex, buttonNum, ContainerInput.PICKUP, player);
            return;
        }
        if (slotIndex < 54) {
            ItemStack carried = getCarried();
            if (carried.isEmpty()) {
                handleTakeClick(slotIndex, buttonNum, player);
            } else {
                int count = buttonNum == 1 ? 1 : carried.getCount();
                ItemStack toPut = carried.copyWithCount(count);
                if (putRemoteStack(slotIndex, toPut, count, player)) {
                    carried.shrink(count);
                    setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
                }
            }
        }
        refreshFromCache();
    }

    private void handleTakeClick(int slotIndex, int buttonNum, Player player) {
        ItemStack remoteStack = this.slots.get(slotIndex).getItem();
        if (remoteStack.isEmpty()) return;
        ItemStack carried = getCarried();
        if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, remoteStack)) {
            player.sendSystemMessage(Component.literal("请先清空鼠标上的物品"));
            return;
        }
        int space = carried.isEmpty() ? remoteStack.getMaxStackSize()
                : carried.getMaxStackSize() - carried.getCount();
        if (space <= 0) return;
        int requestCount = buttonNum == 1 && carried.isEmpty()
                ? (remoteStack.getCount() + 1) / 2
                : remoteStack.getCount();
        requestCount = Math.min(requestCount, space);
        NeutralItem taken = takeRemoteSlot(slotIndex, requestCount, player);
        if (taken == null) return;
        Object itemObj = TheExchangeCore.getInstance().getApi().getItemSerializer().deserialize(taken);
        if (!(itemObj instanceof ItemStack giveStack) || giveStack.isEmpty()) return;
        if (carried.isEmpty()) {
            setCarried(giveStack);
        } else {
            carried.grow(giveStack.getCount());
            setCarried(carried);
        }
    }

    private void handleRemoteSwap(int slotIndex, int buttonNum, Player player) {
        if (slotIndex < 0 || slotIndex >= 54 || buttonNum < 0 || buttonNum > 8) return;
        Slot hotbarSlot = this.slots.get(81 + buttonNum);
        if (hotbarSlot.hasItem()) {
            ItemStack hotbarStack = hotbarSlot.getItem();
            int targetSlot = findTargetSlot(hotbarStack);
            if (targetSlot >= 0) {
                ItemStack toPut = hotbarStack.copy();
                if (putRemoteStack(targetSlot, toPut, toPut.getCount(), player)) {
                    hotbarStack.shrink(toPut.getCount());
                    hotbarSlot.setChanged();
                }
            }
            refreshFromCache();
            return;
        }
        ItemStack remoteStack = this.slots.get(slotIndex).getItem();
        if (!remoteStack.isEmpty() && takeRemoteSlotToPlayerSlot(slotIndex, remoteStack.getCount(), hotbarSlot, player)) {
            refreshFromCache();
        }
    }

    private NeutralItem takeRemoteSlot(int slotIndex, int count, Player player) {
        NeutralItem expected = cachedItem(slotIndex);
        if (expected == null || expected.isEmpty()) {
            player.sendSystemMessage(Component.literal("物品已变化，请重试"));
            return null;
        }
        ExchangeService.TakeResult result = TheExchangeCore.getInstance().getExchangeService()
                .takeItem(serverName, slotIndex, expected.getItemId(), expected.getVersion(), count,
                        player.getUUID().toString(), player.getName().getString());
        if (!result.isSuccess()) {
            player.sendSystemMessage(Component.literal(
                    result.getFailReason() != null ? result.getFailReason() : "取出失败"));
            refreshFromCache();
            return null;
        }
        return result.getItemsToGive();
    }

    private boolean takeRemoteSlotToPlayer(int slotIndex, int count, Player player) {
        NeutralItem taken = takeRemoteSlot(slotIndex, count, player);
        if (taken == null) return false;
        Object itemObj = TheExchangeCore.getInstance().getApi().getItemSerializer().deserialize(taken);
        if (itemObj instanceof ItemStack giveStack && !giveStack.isEmpty()) {
            if (!player.getInventory().add(giveStack)) {
                player.drop(giveStack, false);
            }
            return true;
        }
        return false;
    }

    private boolean takeRemoteSlotToPlayerSlot(int slotIndex, int count, Slot targetSlot, Player player) {
        NeutralItem taken = takeRemoteSlot(slotIndex, count, player);
        if (taken == null) return false;
        Object itemObj = TheExchangeCore.getInstance().getApi().getItemSerializer().deserialize(taken);
        if (!(itemObj instanceof ItemStack giveStack) || giveStack.isEmpty()) return false;
        if (!targetSlot.mayPlace(giveStack) || targetSlot.hasItem()) {
            if (!player.getInventory().add(giveStack)) {
                player.drop(giveStack, false);
            }
        } else {
            targetSlot.set(giveStack);
        }
        return true;
    }

    private boolean putRemoteStack(int targetSlot, ItemStack sourceStack, int count, Player player) {
        if (count <= 0 || sourceStack.isEmpty()) return false;
        ItemStack toPut = sourceStack.copyWithCount(count);
        ExchangeService.PutResult result = TheExchangeCore.getInstance().getExchangeService()
                .putItem(serverName, targetSlot,
                        player.getUUID().toString(), player.getName().getString(), toPut);
        if (!result.isSuccess()) {
            player.sendSystemMessage(Component.literal(
                    result.getFailReason() != null ? result.getFailReason() : "放入失败"));
            refreshFromCache();
            return false;
        }
        return true;
    }

    private NeutralItem cachedItem(int slot) {
        var cache = TheExchangeCore.getInstance().getCacheManager().getCache(serverName);
        return cache != null ? cache.getItem(slot) : null;
    }

    private boolean touchesExchangeSpace(int slotIndex, int buttonNum, ContainerInput input) {
        if (slotIndex >= 0 && slotIndex < 54) return true;
        if (input == ContainerInput.QUICK_MOVE && slotIndex >= 54) return true;
        if (input == ContainerInput.SWAP && slotIndex >= 0 && slotIndex < 54 && buttonNum >= 0 && buttonNum < 9) return true;
        return input == ContainerInput.QUICK_CRAFT || input == ContainerInput.PICKUP_ALL;
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
        return Component.literal((local ? "[本服] " : (online ? "" : "[离线] "))
                + serverName + " 的共享空间");
    }
}
