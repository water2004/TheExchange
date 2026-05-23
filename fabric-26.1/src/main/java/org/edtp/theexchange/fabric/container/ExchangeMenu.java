package org.edtp.theexchange.fabric.container;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
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

    private enum SourceKind {
        CARRIED,
        SLOT
    }

    private record SourceStack(ItemStack stack, SourceKind kind, int slotIndex) {
        private boolean isEmpty() {
            return stack == null || stack.isEmpty();
        }
    }

    private record PickupAllTarget(int slot, int count) {
    }

    private final ExchangeContainer exchangeContainer;
    private final String serverName;
    private final boolean local;
    private volatile boolean online;
    private volatile boolean refreshing;
    private int exchangeQuickCraftType = -1;
    private int exchangeQuickCraftStatus;
    private final java.util.Set<Slot> exchangeQuickCraftSlots = new java.util.LinkedHashSet<>();

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
        var future = local
                ? core.openLocalViewAsync(serverName)
                : core.openRemoteCachedViewAsync(serverName);
        future.whenComplete((state, error) ->
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
        future.whenComplete((state, error) -> {
            try {
                core.getApi().runOnMainThread(() -> {
                    try {
                        if (error != null || state == null || !isViewingServer(state.getServerName())) {
                            return;
                        }
                        applyViewState(state);
                    } finally {
                        refreshing = false;
                    }
                });
            } catch (RuntimeException e) {
                refreshing = false;
                throw e;
            }
        });
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
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized() || core.getMenuInteractionService() == null) {
            debugMenuChat("coreUnavailable", "Exchange 正在重载，请稍后再试",
                    slotIndex, buttonNum, containerInput, null, null, null);
            player.sendSystemMessage(Component.literal("Exchange 正在重载，请稍后再试"));
            return;
        }
        if (containerInput == ContainerInput.QUICK_CRAFT) {
            handleQuickCraft(slotIndex, buttonNum, player);
            return;
        }
        if (containerInput == ContainerInput.PICKUP_ALL) {
            handlePickupAll(buttonNum, player);
            return;
        }
        ExchangeInteraction interaction = buildInteraction(slotIndex, buttonNum, containerInput, player);
        ExchangeInteractionResult decision = core.getMenuInteractionService().decide(interaction);

        switch (decision.getAction()) {
            case PASS_TO_LOADER -> {
                super.clicked(slotIndex, buttonNum, containerInput, player);
                return;
            }
            case REJECT -> {
                if (decision.getMessage() != null) {
                    debugMenuChat("decisionReject", decision.getMessage(),
                            slotIndex, buttonNum, containerInput, interaction, decision, null);
                    player.sendSystemMessage(Component.literal(decision.getMessage()));
                }
                return;
            }
            case REFRESH -> {
                if (decision.getMessage() != null) {
                    debugMenuChat("decisionRefresh", decision.getMessage(),
                            slotIndex, buttonNum, containerInput, interaction, decision, null);
                    player.sendSystemMessage(Component.literal(decision.getMessage()));
                }
                refreshFromCache();
                return;
            }
            case PUT_REMOTE -> {
                applyRemotePut(decision, slotIndex, buttonNum, containerInput, player);
                return;
            }
            case TAKE_REMOTE -> {
                applyRemoteTake(decision, buttonNum, containerInput, player);
                return;
            }
            case SWAP_REMOTE -> {
                applyRemoteSwap(decision, slotIndex, buttonNum, containerInput, player);
                return;
            }
        }
    }

    private void handleQuickCraft(int slotIndex, int buttonNum, Player player) {
        int previousStatus = exchangeQuickCraftStatus;
        exchangeQuickCraftStatus = getQuickcraftHeader(buttonNum);
        if ((previousStatus != 1 || exchangeQuickCraftStatus != 2)
                && previousStatus != exchangeQuickCraftStatus) {
            resetExchangeQuickCraft();
        } else if (getCarried().isEmpty()) {
            resetExchangeQuickCraft();
        } else if (exchangeQuickCraftStatus == 0) {
            exchangeQuickCraftType = getQuickcraftType(buttonNum);
            if (isValidQuickcraftType(exchangeQuickCraftType, player)) {
                exchangeQuickCraftStatus = 1;
                exchangeQuickCraftSlots.clear();
            } else {
                resetExchangeQuickCraft();
            }
        } else if (exchangeQuickCraftStatus == 1) {
            if (slotIndex < 0 || slotIndex >= this.slots.size()) return;
            Slot slot = this.slots.get(slotIndex);
            ItemStack carried = getCarried();
            if (canQuickCraftTo(slot, carried, exchangeQuickCraftSlots.size())) {
                exchangeQuickCraftSlots.add(slot);
            }
        } else if (exchangeQuickCraftStatus == 2) {
            finishQuickCraft(player);
        } else {
            resetExchangeQuickCraft();
        }
    }

    private boolean canQuickCraftTo(Slot slot, ItemStack carried, int selectedSlots) {
        return slot != null
                && canItemQuickReplace(slot, carried, true)
                && slot.mayPlace(carried)
                && (exchangeQuickCraftType == 2 || carried.getCount() > selectedSlots)
                && canDragTo(slot);
    }

    private void finishQuickCraft(Player player) {
        if (exchangeQuickCraftSlots.isEmpty()) {
            resetExchangeQuickCraft();
            return;
        }
        if (exchangeQuickCraftSlots.size() == 1) {
            int slot = exchangeQuickCraftSlots.iterator().next().index;
            int button = exchangeQuickCraftType;
            resetExchangeQuickCraft();
            clicked(slot, button, ContainerInput.PICKUP, player);
            return;
        }
        ItemStack source = getCarried().copy();
        if (source.isEmpty()) {
            resetExchangeQuickCraft();
            return;
        }
        java.util.List<Slot> slots = new java.util.ArrayList<>(exchangeQuickCraftSlots);
        int quickCraftType = exchangeQuickCraftType;
        resetExchangeQuickCraft();
        int slotCount = slots.size();
        for (Slot slot : slots) {
            ItemStack carried = getCarried();
            if (carried.isEmpty()) break;
            if (!canItemQuickReplace(slot, carried, true)
                    || !slot.mayPlace(carried)
                    || (quickCraftType != 2 && source.getCount() < slotCount)
                    || !canDragTo(slot)) {
                continue;
            }
            int existingCount = slot.hasItem() ? slot.getItem().getCount() : 0;
            int maxSize = Math.min(source.getMaxStackSize(), slot.getMaxStackSize(source));
            int newCount = Math.min(getQuickCraftPlaceCount(slotCount, quickCraftType, source) + existingCount,
                    maxSize);
            int insertCount = newCount - existingCount;
            if (insertCount <= 0) continue;
            if (slot.index < 54) {
                ExchangeInteractionResult decision = ExchangeInteractionResult.putRemote(slot.index,
                        neutralFromStack(source.copyWithCount(insertCount)), insertCount);
                applyRemotePut(decision, slot.index, 0, ContainerInput.QUICK_CRAFT, player);
            } else {
                ItemStack nowCarried = getCarried();
                if (nowCarried.isEmpty()) break;
                int actualInsert = Math.min(insertCount, nowCarried.getCount());
                slot.setByPlayer(source.copyWithCount(existingCount + actualInsert));
                nowCarried.shrink(actualInsert);
                setCarried(nowCarried.isEmpty() ? ItemStack.EMPTY : nowCarried);
                broadcastChanges();
            }
        }
    }

    private void resetExchangeQuickCraft() {
        exchangeQuickCraftStatus = 0;
        exchangeQuickCraftType = -1;
        exchangeQuickCraftSlots.clear();
    }

    private void handlePickupAll(int buttonNum, Player player) {
        ItemStack carried = getCarried();
        if (carried.isEmpty()) {
            return;
        }
        NeutralItem carriedNeutral = neutralFromStack(carried);
        if (carriedNeutral == null || carriedNeutral.isIncompatible()) {
            return;
        }
        java.util.List<PickupAllTarget> targets = pickupAllTargets(buttonNum, carriedNeutral, carried.getMaxStackSize());
        if (targets.isEmpty()) {
            return;
        }
        takePickupAllTarget(targets, 0, buttonNum, player);
    }

    private java.util.List<PickupAllTarget> pickupAllTargets(int buttonNum, NeutralItem carried, int maxStackSize) {
        java.util.List<PickupAllTarget> targets = new java.util.ArrayList<>();
        int currentCount = carried.getCount();
        int start = buttonNum == 0 ? 0 : 53;
        int step = buttonNum == 0 ? 1 : -1;
        for (int pass = 0; pass < 2 && currentCount < maxStackSize; pass++) {
            for (int slot = start; slot >= 0 && slot < 54 && currentCount < maxStackSize; slot += step) {
                NeutralItem item = neutralFromStack(exchangeContainer.getItem(slot));
                if (item == null || item.isEmpty() || item.isIncompatible() || !item.sameStackKind(carried)) {
                    continue;
                }
                if (pass == 0 && item.getCount() == maxStackSize) {
                    continue;
                }
                int takeCount = Math.min(item.getCount(), maxStackSize - currentCount);
                if (takeCount <= 0) continue;
                targets.add(new PickupAllTarget(slot, takeCount));
                currentCount += takeCount;
            }
        }
        return targets;
    }

    private void takePickupAllTarget(java.util.List<PickupAllTarget> targets, int index,
                                     int buttonNum, Player player) {
        if (index >= targets.size() || getCarried().isEmpty()) {
            refreshFromMemory();
            return;
        }
        ItemStack carried = getCarried();
        NeutralItem carriedNeutral = neutralFromStack(carried);
        if (carriedNeutral == null || carriedNeutral.isIncompatible()
                || carried.getCount() >= carried.getMaxStackSize()) {
            refreshFromMemory();
            return;
        }
        PickupAllTarget target = targets.get(index);
        NeutralItem slotItem = neutralFromStack(exchangeContainer.getItem(target.slot()));
        if (slotItem == null || slotItem.isEmpty() || slotItem.isIncompatible()
                || !slotItem.sameStackKind(carriedNeutral)) {
            takePickupAllTarget(targets, index + 1, buttonNum, player);
            return;
        }
        int count = Math.min(target.count(), carried.getMaxStackSize() - carried.getCount());
        count = Math.min(count, slotItem.getCount());
        if (count <= 0) {
            takePickupAllTarget(targets, index + 1, buttonNum, player);
            return;
        }
        final int takeCount = count;

        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;
        core.takeRemoteAsync(serverName, target.slot(), takeCount, playerContext(player))
                .whenComplete((result, error) -> core.getApi().runOnMainThread(() -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        String message = error != null
                                ? "取出失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "取出失败";
                        debugMenuChat("pickupAllTakeFailed", message, target.slot(), buttonNum,
                                ContainerInput.PICKUP_ALL, null,
                                ExchangeInteractionResult.takeRemote(target.slot(), takeCount), error);
                        refreshFromMemory();
                        return;
                    }
                    applyTakenItem(result, buttonNum, ContainerInput.PICKUP_ALL, player);
                    takePickupAllTarget(targets, index + 1, buttonNum, player);
                }));
    }

    private ExchangeInteraction buildInteraction(int slotIndex, int buttonNum,
                                                 ContainerInput containerInput, Player player) {
        return new ExchangeInteraction(
                serverName,
                local,
                online,
                slotIndex,
                buttonNum,
                mapClickType(containerInput),
                neutralFromSlot(slotIndex),
                neutralFromStack(getCarried()),
                neutralFromHotbar(buttonNum),
                snapshotNeutralItems(),
                new PlayerExchangeContext(player.getUUID().toString(), player.getName().getString()));
    }

    private void debugMenuChat(String stage, String message, int slotIndex, int buttonNum,
                               ContainerInput containerInput, ExchangeInteraction interaction,
                               ExchangeInteractionResult decision, Throwable error) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || core.getApi() == null) return;
        StringBuilder sb = new StringBuilder("[Exchange|Debug][MENU][chatError]");
        sb.append(" stage=").append(stage)
                .append(" server=").append(serverName)
                .append(" local=").append(local)
                .append(" online=").append(online)
                .append(" slot=").append(slotIndex)
                .append(" button=").append(buttonNum)
                .append(" mcClick=").append(containerInput)
                .append(" mappedClick=").append(String.valueOf(interaction != null ? interaction.getClickType() : null))
                .append(" message=").append(message)
                .append(" action=").append(decision != null ? decision.getAction() : null)
                .append(" targetSlot=").append(decision != null ? decision.getTargetSlot() : -1)
                .append(" count=").append(decision != null ? decision.getCount() : 0)
                .append(" boundedMerge=").append(decision != null && decision.isBoundedMerge())
                .append(" expectedItem=").append(decision != null ? decision.getExpectedItemId() : null)
                .append(" slotItem=").append(describeNeutral(interaction != null ? interaction.getSlotItem() : neutralFromSlot(slotIndex)))
                .append(" carried=").append(describeNeutral(interaction != null ? interaction.getCarriedItem() : neutralFromStack(getCarried())))
                .append(" hotbar=").append(describeNeutral(interaction != null ? interaction.getHotbarItem() : neutralFromHotbar(buttonNum)));
        if (decision != null) {
            sb.append(" decisionItem=").append(describeNeutral(decision.getItem()));
        }
        if (error != null) {
            sb.append(" error=").append(rootMessage(error));
        }
        core.getApi().getLogger().info(sb.toString());
    }

    private String describeNeutral(NeutralItem item) {
        if (item == null) return "null";
        byte[] extra = item.getExtraData();
        return "{id=" + item.getItemId()
                + ",count=" + item.getCount()
                + ",incompatible=" + item.isIncompatible()
                + ",extraLen=" + (extra == null ? -1 : extra.length)
                + ",extraHash=" + java.util.Arrays.hashCode(extra)
                + ",source=" + item.getSourceVersion()
                + ",version=" + item.getVersion()
                + "}";
    }

    private MenuClickType mapClickType(ContainerInput input) {
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
                                ContainerInput containerInput, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;

        SourceStack inFlight = removeSourceStack(decision.getCount(), slotIndex, buttonNum, containerInput);
        if (inFlight.isEmpty()) {
            debugMenuChat("putSourceMissing", "物品已变化，请重试",
                    slotIndex, buttonNum, containerInput, null, decision, null);
            player.sendSystemMessage(Component.literal("物品已变化，请重试"));
            refreshFromCache();
            return;
        }

        NeutralItem item = neutralFromStack(inFlight.stack());
        core.putRemoteAsync(serverName, decision.getTargetSlot(), item, playerContext(player))
                .whenComplete((result, error) -> core.getApi().runOnMainThread(() -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        restoreSourceStack(player, inFlight);
                        String message = error != null
                                ? "放入失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "放入失败";
                        debugMenuChat("putFailed", message, slotIndex, buttonNum,
                                containerInput, null, decision, error);
                        player.sendSystemMessage(Component.literal(message));
                    }
                    refreshFromMemory();
                }));
    }

    private SourceStack removeSourceStack(int count, int slotIndex, int buttonNum,
                                          ContainerInput containerInput) {
        if (count <= 0) return emptySource();
        if (containerInput == ContainerInput.PICKUP || containerInput == ContainerInput.QUICK_CRAFT) {
            ItemStack carried = getCarried();
            if (carried.isEmpty() || carried.getCount() < count) return emptySource();
            ItemStack removed = carried.copyWithCount(count);
            carried.shrink(count);
            setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
            broadcastChanges();
            return new SourceStack(removed, SourceKind.CARRIED, -1);
        }
        if (containerInput == ContainerInput.SWAP && buttonNum >= 0 && buttonNum <= 8) {
            return removeFromSlot(81 + buttonNum, count);
        }
        return removeFromSlot(slotIndex, count);
    }

    private SourceStack removeFromSlot(int slotIndex, int count) {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) return emptySource();
        Slot slot = this.slots.get(slotIndex);
        ItemStack source = slot.getItem();
        if (source.isEmpty() || source.getCount() < count) return emptySource();
        ItemStack removed = source.copyWithCount(count);
        source.shrink(count);
        if (source.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        broadcastChanges();
        return new SourceStack(removed, SourceKind.SLOT, slotIndex);
    }

    private SourceStack emptySource() {
        return new SourceStack(ItemStack.EMPTY, SourceKind.CARRIED, -1);
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

    private void restoreSourceStack(Player player, SourceStack source) {
        if (source == null || source.isEmpty()) return;
        if (player.isRemoved() || player.containerMenu != this) {
            giveOrDrop(player, source.stack());
            return;
        }
        if (!placeAtSource(source.stack(), source.kind(), source.slotIndex())) {
            giveOrDrop(player, source.stack());
        }
        broadcastChanges();
    }

    private boolean placeAtSource(ItemStack stack, SourceKind kind, int slotIndex) {
        if (stack == null || stack.isEmpty()) return true;
        if (kind == SourceKind.CARRIED) {
            ItemStack carried = getCarried();
            if (carried.isEmpty()) {
                setCarried(stack);
                return true;
            }
            if (ItemStack.isSameItemSameComponents(carried, stack)) {
                int max = carried.getMaxStackSize();
                if (carried.getCount() + stack.getCount() <= max) {
                    carried.grow(stack.getCount());
                    setCarried(carried);
                    return true;
                }
            }
            return false;
        }
        if (slotIndex < 0 || slotIndex >= this.slots.size()) return false;
        Slot slot = this.slots.get(slotIndex);
        ItemStack existing = slot.getItem();
        if (existing.isEmpty() && slot.mayPlace(stack)) {
            slot.setByPlayer(stack);
            return true;
        }
        if (ItemStack.isSameItemSameComponents(existing, stack)
                && existing.getCount() + stack.getCount() <= slot.getMaxStackSize(stack)) {
            existing.grow(stack.getCount());
            slot.setByPlayer(existing);
            return true;
        }
        return false;
    }

    private void applyRemoteTake(ExchangeInteractionResult decision, int buttonNum,
                                 ContainerInput containerInput, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;
        core.takeRemoteAsync(serverName, decision.getTargetSlot(), decision.getCount(), playerContext(player))
                .whenComplete((result, error) -> core.getApi().runOnMainThread(() -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        String message = error != null
                                ? "取出失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "取出失败";
                        debugMenuChat("takeFailed", message, decision.getTargetSlot(), buttonNum,
                                containerInput, null, decision, error);
                        player.sendSystemMessage(Component.literal(message));
                        refreshFromMemory();
                        return;
                    }
                    applyTakenItem(result, buttonNum, containerInput, player);
                    refreshFromMemory();
                }));
    }

    private void applyRemoteSwap(ExchangeInteractionResult decision, int slotIndex, int buttonNum,
                                 ContainerInput containerInput, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;

        int putCount = decision.getItem() == null ? 0 : decision.getItem().getCount();
        SourceStack inFlight = removeSourceStack(putCount, slotIndex, buttonNum, containerInput);
        if (inFlight.isEmpty()) {
            debugMenuChat("swapSourceMissing", "物品已变化，请重试",
                    slotIndex, buttonNum, containerInput, null, decision, null);
            player.sendSystemMessage(Component.literal("物品已变化，请重试"));
            refreshFromCache();
            return;
        }

        NeutralItem item = neutralFromStack(inFlight.stack());
        core.swapRemoteAsync(serverName, decision.getTargetSlot(), item,
                        decision.getExpectedItemId(), decision.getCount(),
                        decision.isBoundedMerge(), playerContext(player))
                .whenComplete((result, error) -> core.getApi().runOnMainThread(() -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        restoreSourceStack(player, inFlight);
                        String message = error != null
                                ? "交换失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "交换失败";
                        debugMenuChat("swapFailed", message, slotIndex, buttonNum,
                                containerInput, null, decision, error);
                        player.sendSystemMessage(Component.literal(message));
                        refreshFromMemory();
                        return;
                    }
                    applySwappedItem(result, inFlight, decision.isBoundedMerge(), player);
                    refreshFromMemory();
                }));
    }

    private void applySwappedItem(ExchangeMutationResult result, SourceStack source,
                                  boolean allowEmptyResult, Player player) {
        if (allowEmptyResult && (result.getItem() == null || result.getItem().isEmpty())) {
            broadcastChanges();
            return;
        }
        if (result.getItem() == null || result.getItem().isEmpty() || result.getItem().isIncompatible()) {
            debugMenuChat("swapDeserializeRejected", "不兼容物品禁止操作",
                    source.slotIndex(), -1, ContainerInput.PICKUP, null, null, null);
            player.sendSystemMessage(Component.literal("不兼容物品禁止操作"));
            return;
        }
        ExchangeAPI api = TheExchangeCore.getInstance().getApi();
        Object itemObj = api.getItemSerializer().deserialize(result.getItem());
        if (!(itemObj instanceof ItemStack giveStack) || giveStack.isEmpty()) {
            debugMenuChat("swapDeserializeFailed", "不兼容物品禁止操作",
                    source.slotIndex(), -1, ContainerInput.PICKUP, null, null, null);
            player.sendSystemMessage(Component.literal("不兼容物品禁止操作"));
            return;
        }
        if (player.isRemoved() || player.containerMenu != this) {
            giveOrDrop(player, giveStack);
            return;
        }
        if (!placeAtSource(giveStack, source.kind(), source.slotIndex())) {
            giveOrDrop(player, giveStack);
        }
        broadcastChanges();
    }

    private void applyTakenItem(ExchangeMutationResult result, int buttonNum,
                                ContainerInput containerInput, Player player) {
        if (result.getItem() == null || result.getItem().isEmpty() || result.getItem().isIncompatible()) {
            debugMenuChat("takeDeserializeRejected", "不兼容物品禁止操作",
                    -1, buttonNum, containerInput, null, null, null);
            player.sendSystemMessage(Component.literal("不兼容物品禁止操作"));
            return;
        }
        ExchangeAPI api = TheExchangeCore.getInstance().getApi();
        Object itemObj = api.getItemSerializer().deserialize(result.getItem());
        if (!(itemObj instanceof ItemStack giveStack) || giveStack.isEmpty()) {
            debugMenuChat("takeDeserializeFailed", "不兼容物品禁止操作",
                    -1, buttonNum, containerInput, null, null, null);
            player.sendSystemMessage(Component.literal("不兼容物品禁止操作"));
            return;
        }

        if (player.isRemoved() || player.containerMenu != this) {
            giveOrDrop(player, giveStack);
            return;
        }

        if (containerInput == ContainerInput.PICKUP || containerInput == ContainerInput.PICKUP_ALL) {
            ItemStack carried = getCarried();
            if (carried.isEmpty()) {
                setCarried(giveStack);
            } else if (ItemStack.isSameItemSameComponents(carried, giveStack)) {
                carried.grow(giveStack.getCount());
                setCarried(carried);
            } else if (!player.getInventory().add(giveStack)) {
                player.drop(giveStack, false);
            }
        } else if (containerInput == ContainerInput.SWAP && buttonNum >= 0 && buttonNum <= 8) {
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

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) return ItemStack.EMPTY;
        ItemStack before = this.slots.get(slotIndex).getItem().copy();
        clicked(slotIndex, 0, ContainerInput.QUICK_MOVE, player);
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
