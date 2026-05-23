package org.edtp.theexchange.fabric.container;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.api.RefreshableExchangeView;
import org.edtp.theexchange.model.ExchangeInteraction;
import org.edtp.theexchange.model.ExchangeInteractionResult;
import org.edtp.theexchange.model.ExchangeMutationResult;
import org.edtp.theexchange.model.ExchangeViewState;
import org.edtp.theexchange.model.MenuClickType;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.PlayerExchangeContext;

/**
 * Container menu for the exchange GUI.
 * 0-53:  Exchange space (ExchangeSlot, backed by ExchangeContainer)
 * 54-80: Player inventory
 * 81-89: Player hotbar
 *
 * Loader-facing menu: translates Minecraft clicks and ItemStacks into core
 * interaction requests, then applies the core result back to the player.
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
        this.exchangeContainer = new ExchangeContainer(online, 6);
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
        loadViewAsync();
    }

    @Override
    public void refreshFromMemory() {
        if (refreshing) return;
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            return;
        }
        core.openRemoteCachedViewAsync(serverName).whenComplete((state, error) ->
                core.getApi().runOnMainThread(() -> {
                    if (error == null && state != null && isViewingServer(state.getServerName())) {
                        applyViewState(state);
                    }
                }));
    }

    private void loadViewAsync() {
        if (refreshing) return;
        refreshing = true;
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            refreshing = false;
            return;
        }
        var future = local
                ? core.openLocalViewAsync(serverName)
                : core.openRemoteViewAsync(serverName);
        future.whenComplete((state, error) -> core.getApi().runOnMainThread(() -> {
            try {
                if (error != null || state == null || !isViewingServer(state.getServerName())) {
                    return;
                }
                applyViewState(state);
            } finally {
                refreshing = false;
            }
        }));
    }

    private void applyViewState(ExchangeViewState state) {
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
    }

    @Override
    public boolean stillValid(Player player) {
        return exchangeContainer.stillValid(player);
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ClickType clickType, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized() || core.getMenuInteractionService() == null) {
            player.displayClientMessage(Component.literal("Exchange 正在重载，请稍后再试"), false);
            return;
        }
        ExchangeInteractionResult decision = core.getMenuInteractionService()
                .decide(buildInteraction(slotIndex, buttonNum, clickType, player));

        switch (decision.getAction()) {
            case PASS_TO_LOADER -> {
                super.clicked(slotIndex, buttonNum, clickType, player);
                return;
            }
            case LOCAL_APPLY -> {
                handleLocalClick(slotIndex, buttonNum, clickType, player);
                return;
            }
            case REJECT -> {
                if (decision.getMessage() != null) {
                    player.displayClientMessage(Component.literal(decision.getMessage()), false);
                }
                return;
            }
            case REFRESH -> {
                if (decision.getMessage() != null) {
                    player.displayClientMessage(Component.literal(decision.getMessage()), false);
                }
                refreshFromCache();
                return;
            }
            case PUT_REMOTE -> {
                applyRemotePut(decision, slotIndex, buttonNum, clickType, player);
                return;
            }
            case TAKE_REMOTE -> {
                applyRemoteTake(decision, buttonNum, clickType, player);
                return;
            }
        }
    }

    private ExchangeInteraction buildInteraction(int slotIndex, int buttonNum,
                                                 ClickType clickType, Player player) {
        return new ExchangeInteraction(
                serverName,
                local,
                online,
                slotIndex,
                buttonNum,
                mapClickType(clickType),
                neutralFromSlot(slotIndex),
                neutralFromStack(getCarried()),
                neutralFromHotbar(buttonNum),
                snapshotNeutralItems(),
                new PlayerExchangeContext(player.getUUID().toString(), player.getName().getString()));
    }

    private MenuClickType mapClickType(ClickType input) {
        return switch (input) {
            case PICKUP -> MenuClickType.PICKUP;
            case QUICK_MOVE -> MenuClickType.QUICK_MOVE;
            case SWAP -> MenuClickType.SWAP;
            case QUICK_CRAFT -> MenuClickType.QUICK_CRAFT;
            case PICKUP_ALL -> MenuClickType.PICKUP_ALL;
            case THROW -> MenuClickType.THROW;
            case CLONE -> MenuClickType.CLONE;
        };
    }

    private NeutralItem neutralFromSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) return null;
        return neutralFromStack(this.slots.get(slotIndex).getItem());
    }

    private NeutralItem neutralFromHotbar(int button) {
        if (button < 0 || button > 8 || 81 + button >= this.slots.size()) return null;
        return neutralFromStack(this.slots.get(81 + button).getItem());
    }

    private NeutralItem neutralFromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return TheExchangeCore.getInstance().getApi().getItemSerializer().serialize(stack.copy());
    }

    private java.util.List<NeutralItem> snapshotNeutralItems() {
        java.util.List<NeutralItem> items = new java.util.ArrayList<>(54);
        for (int i = 0; i < 54; i++) {
            items.add(neutralFromStack(exchangeContainer.getItem(i)));
        }
        return items;
    }

    private void applyRemotePut(ExchangeInteractionResult decision, int slotIndex, int buttonNum,
                                ClickType clickType, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;

        ItemStack inFlight = removeSourceStack(decision.getCount(), slotIndex, buttonNum, clickType);
        if (inFlight.isEmpty()) {
            player.displayClientMessage(Component.literal("物品已变化，请重试"), false);
            refreshFromCache();
            return;
        }

        NeutralItem item = neutralFromStack(inFlight);
        core.putRemoteAsync(serverName, decision.getTargetSlot(), item, playerContext(player))
                .whenComplete((result, error) -> core.getApi().runOnMainThread(() -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        giveOrDrop(player, inFlight);
                        player.displayClientMessage(Component.literal(error != null
                                ? "放入失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "放入失败"), false);
                    }
                    refreshFromMemory();
                }));
    }

    private ItemStack removeSourceStack(int count, int slotIndex, int buttonNum,
                                        ClickType clickType) {
        if (count <= 0) return ItemStack.EMPTY;
        if (clickType == ClickType.PICKUP) {
            ItemStack carried = getCarried();
            if (carried.isEmpty() || carried.getCount() < count) return ItemStack.EMPTY;
            ItemStack removed = carried.copyWithCount(count);
            carried.shrink(count);
            setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
            broadcastChanges();
            return removed;
        }
        if (clickType == ClickType.SWAP && buttonNum >= 0 && buttonNum <= 8) {
            return removeFromSlot(81 + buttonNum, count);
        }
        return removeFromSlot(slotIndex, count);
    }

    private ItemStack removeFromSlot(int slotIndex, int count) {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) return ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        ItemStack source = slot.getItem();
        if (source.isEmpty() || source.getCount() < count) return ItemStack.EMPTY;
        ItemStack removed = source.copyWithCount(count);
        source.shrink(count);
        if (source.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        broadcastChanges();
        return removed;
    }

    private void giveOrDrop(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemStack remaining = stack.copy();
        if (!player.isRemoved()) {
            if (player.getInventory().add(remaining)) {
                return;
            }
        }
        dropAtPlayer(player, remaining);
    }

    private void applyRemoteTake(ExchangeInteractionResult decision, int buttonNum,
                                 ClickType clickType, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;
        core.takeRemoteAsync(serverName, decision.getTargetSlot(), decision.getCount(), playerContext(player))
                .whenComplete((result, error) -> core.getApi().runOnMainThread(() -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        player.displayClientMessage(Component.literal(error != null
                                ? "取出失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "取出失败"), false);
                        refreshFromMemory();
                        return;
                    }
                    applyTakenItem(result, buttonNum, clickType, player);
                    refreshFromMemory();
                }));
    }

    private void applyTakenItem(ExchangeMutationResult result, int buttonNum,
                                ClickType clickType, Player player) {
        if (result.getItem() == null || result.getItem().isEmpty() || result.getItem().isIncompatible()) {
            player.displayClientMessage(Component.literal("不兼容物品禁止操作"), false);
            return;
        }
        ExchangeAPI api = TheExchangeCore.getInstance().getApi();
        Object itemObj = api.getItemSerializer().deserialize(result.getItem());
        if (!(itemObj instanceof ItemStack giveStack) || giveStack.isEmpty()) {
            player.displayClientMessage(Component.literal("不兼容物品禁止操作"), false);
            return;
        }

        if (player.isRemoved() || player.containerMenu != this) {
            giveOrDrop(player, giveStack);
            return;
        }

        if (clickType == ClickType.PICKUP) {
            ItemStack carried = getCarried();
            if (carried.isEmpty()) {
                setCarried(giveStack);
            } else if (ItemStack.isSameItemSameComponents(carried, giveStack)) {
                carried.grow(giveStack.getCount());
                setCarried(carried);
            } else if (!player.getInventory().add(giveStack)) {
                player.drop(giveStack, false);
            }
        } else if (clickType == ClickType.SWAP && buttonNum >= 0 && buttonNum <= 8) {
            Slot hotbarSlot = this.slots.get(81 + buttonNum);
            if (!hotbarSlot.hasItem() && hotbarSlot.mayPlace(giveStack)) {
                hotbarSlot.set(giveStack);
            } else if (!player.getInventory().add(giveStack)) {
                player.drop(giveStack, false);
            }
        } else if (!player.getInventory().add(giveStack)) {
            dropAtPlayer(player, giveStack);
        }
    }

    private void handleLocalClick(int slotIndex, int buttonNum, ClickType clickType, Player player) {
        java.util.List<NeutralItem> before = snapshotNeutralItems();
        super.clicked(slotIndex, buttonNum, clickType, player);
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;
        java.util.List<NeutralItem> after = snapshotNeutralItems();
        core.applyLocalSnapshotAsync(before, after, playerContext(player))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        core.getApi().getLogger().error("Failed to persist local exchange snapshot", error);
                    }
                });
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) return ItemStack.EMPTY;
        if (local) {
            Slot slot = this.slots.get(slotIndex);
            if (!slot.hasItem()) return ItemStack.EMPTY;
            ItemStack sourceStack = slot.getItem();
            ItemStack copy = sourceStack.copy();
            boolean moved = slotIndex < 54
                    ? moveItemStackTo(sourceStack, 54, 90, true)
                    : moveItemStackTo(sourceStack, 0, 54, false);
            if (!moved) return ItemStack.EMPTY;
            if (sourceStack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            return copy;
        }
        ItemStack before = this.slots.get(slotIndex).getItem().copy();
        clicked(slotIndex, 0, ClickType.QUICK_MOVE, player);
        return before;
    }

    private PlayerExchangeContext playerContext(Player player) {
        return new PlayerExchangeContext(player.getUUID().toString(), player.getName().getString());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
    }

    public Component getTitle() {
        return Component.literal((local ? "[本服] " : (online ? "" : "[离线] "))
                + serverName + " 的共享空间");
    }

    private String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private void dropAtPlayer(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (!player.level().isClientSide()) {
            ItemEntity entity = new ItemEntity(player.level(),
                    player.getX(), player.getY(), player.getZ(), stack.copy());
            entity.setPickUpDelay(20);
            player.level().addFreshEntity(entity);
        }
    }
}
