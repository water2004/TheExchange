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
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.MenuClickType;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.PlayerExchangeContext;
import org.edtp.theexchange.service.MenuOperationGate;

import java.util.concurrent.CompletableFuture;

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

    private enum MenuResourceKind {
        CURSOR,
        LOCAL_SLOT,
        REMOTE_SLOT
    }

    private record MenuResource(MenuResourceKind kind, int slot) {
    }

    private static final class MenuOperation {
    }

    private final ExchangeContainer exchangeContainer;
    private final String serverName;
    private final boolean local;
    private volatile boolean online;
    private volatile InventoryAccess access;
    private volatile InventoryScope scope;
    private volatile boolean refreshing;
    private final MenuOperationGate<MenuResource> operationGate = new MenuOperationGate<>();
    private int exchangeQuickCraftType = -1;
    private int exchangeQuickCraftStatus;
    private final java.util.Set<Slot> exchangeQuickCraftSlots = new java.util.LinkedHashSet<>();

    public ExchangeMenu(int containerId, Inventory playerInventory,
                         ExchangeViewState state) {
        super(MenuType.GENERIC_9x6, containerId);
        this.serverName = state.getServerName();
        this.local = state.isLocal();
        this.online = state.isOnline();
        this.scope = state.getScope();
        this.access = state.getAccess().withResolvedScope(this.scope);
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

    @Override
    public boolean isViewingInventory(String name, InventoryScope scope) {
        return isViewingServer(name) && sameScope(this.scope, scope);
    }

    @Override
    public boolean isViewingInventory(String name, InventoryAccess access) {
        return isViewingServer(name) && sameScope(this.scope,
                access != null ? access.effectiveScope() : InventoryScope.server());
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
                ? core.openLocalViewAsync(serverName, access)
                : core.openRemoteCachedViewAsync(serverName, access);
        future.whenComplete((state, error) ->
                core.getApi().runOnMainThread(() -> {
                    if (error == null && state != null && isViewingInventory(state.getServerName(), state.getScope())) {
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
                ? core.openLocalViewAsync(serverName, access)
                : core.openRemoteViewAsync(serverName, access);
        future.whenComplete((state, error) -> {
            try {
                core.getApi().runOnMainThread(() -> {
                    try {
                        if (error != null || state == null || !isViewingInventory(state.getServerName(), state.getScope())) {
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
            scope = state.getScope();
            access = state.getAccess().withResolvedScope(scope);
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
        if (scope != null && scope.isPlayer()) {
            TheExchangeCore core = TheExchangeCore.getInstance();
            if (core == null || !core.isInitialized() || !core.isPlayerInventoriesEnabled()) {
                return false;
            }
        }
        return exchangeContainer.stillValid(player);
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ClickType clickType, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized() || core.getMenuInteractionService() == null) {
            debugMenuChat("coreUnavailable", "Exchange 正在重载，请稍后再试",
                    slotIndex, buttonNum, clickType, null, null, null);
            player.displayClientMessage(Component.literal("Exchange 正在重载，请稍后再试"), false);
            return;
        }
        if (clickType == ClickType.QUICK_CRAFT) {
            if (operationGate.conflicts(localClickResources(
                    slotIndex, buttonNum, MenuClickType.QUICK_CRAFT))) {
                rejectWhileBusy();
                return;
            }
            handleQuickCraft(slotIndex, buttonNum, player);
            return;
        }
        if (clickType == ClickType.PICKUP_ALL) {
            handlePickupAll(buttonNum, player);
            return;
        }
        ExchangeInteraction interaction = buildInteraction(slotIndex, buttonNum, clickType);
        ExchangeInteractionResult decision = core.getMenuInteractionService().decide(interaction);

        switch (decision.getAction()) {
            case PASS_TO_LOADER -> {
                if (operationGate.conflicts(localClickResources(
                        slotIndex, buttonNum, interaction.getClickType()))) {
                    rejectWhileBusy();
                    return;
                }
                super.clicked(slotIndex, buttonNum, clickType, player);
                return;
            }
            case REJECT -> {
                if (decision.getMessage() != null) {
                    debugMenuChat("decisionReject", decision.getMessage(),
                            slotIndex, buttonNum, clickType, interaction, decision, null);
                    player.displayClientMessage(Component.literal(decision.getMessage()), false);
                }
                return;
            }
            case REFRESH -> {
                if (decision.getMessage() != null) {
                    debugMenuChat("decisionRefresh", decision.getMessage(),
                            slotIndex, buttonNum, clickType, interaction, decision, null);
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
            case SWAP_REMOTE -> {
                applyRemoteSwap(decision, slotIndex, buttonNum, clickType, player);
                return;
            }
        }
    }

    private MenuOperation tryBeginOperation(java.util.Collection<MenuResource> resources) {
        MenuOperation operation = new MenuOperation();
        return operationGate.tryAcquire(operation, resources) ? operation : null;
    }

    private void rejectWhileBusy() {
        resetExchangeQuickCraft();
    }

    private java.util.Set<MenuResource> putOrSwapResources(
            ExchangeInteractionResult decision, int slotIndex, int buttonNum, MenuClickType clickType) {
        java.util.Set<MenuResource> resources = new java.util.LinkedHashSet<>();
        resources.add(remoteSlotResource(decision.getTargetSlot()));
        switch (clickType) {
            case PICKUP, QUICK_CRAFT -> resources.add(cursorResource());
            case SWAP -> addMenuSlotResource(resources, 81 + buttonNum);
            case QUICK_MOVE -> addMenuSlotResource(resources, slotIndex);
            default -> {
            }
        }
        return resources;
    }

    private java.util.Set<MenuResource> takeResources(
            ExchangeInteractionResult decision, int buttonNum, MenuClickType clickType) {
        java.util.Set<MenuResource> resources = new java.util.LinkedHashSet<>();
        resources.add(remoteSlotResource(decision.getTargetSlot()));
        switch (clickType) {
            case PICKUP, PICKUP_ALL -> resources.add(cursorResource());
            case SWAP -> addMenuSlotResource(resources, 81 + buttonNum);
            case QUICK_MOVE -> addPlayerInventoryResources(resources);
            default -> {
            }
        }
        return resources;
    }

    private java.util.Set<MenuResource> pickupAllResources(
            java.util.List<PickupAllTarget> targets) {
        java.util.Set<MenuResource> resources = new java.util.LinkedHashSet<>();
        resources.add(cursorResource());
        for (PickupAllTarget target : targets) {
            resources.add(remoteSlotResource(target.slot()));
        }
        return resources;
    }

    private java.util.Set<MenuResource> localClickResources(
            int slotIndex, int buttonNum, MenuClickType clickType) {
        java.util.Set<MenuResource> resources = new java.util.LinkedHashSet<>();
        switch (clickType) {
            case PICKUP, QUICK_CRAFT, CLONE -> {
                resources.add(cursorResource());
                addMenuSlotResource(resources, slotIndex);
            }
            case SWAP -> {
                addMenuSlotResource(resources, slotIndex);
                addMenuSlotResource(resources, 81 + buttonNum);
            }
            case QUICK_MOVE -> {
                addMenuSlotResource(resources, slotIndex);
                addPlayerInventoryResources(resources);
            }
            case THROW -> addMenuSlotResource(resources, slotIndex);
            case PICKUP_ALL -> resources.add(cursorResource());
        }
        return resources;
    }

    private void addPlayerInventoryResources(java.util.Set<MenuResource> resources) {
        int limit = Math.min(90, this.slots.size());
        for (int slot = 54; slot < limit; slot++) {
            resources.add(localSlotResource(slot));
        }
    }

    private void addMenuSlotResource(java.util.Set<MenuResource> resources, int slot) {
        if (slot >= 0 && slot < 54) {
            resources.add(remoteSlotResource(slot));
        } else if (slot >= 54 && slot < this.slots.size()) {
            resources.add(localSlotResource(slot));
        }
    }

    private MenuResource cursorResource() {
        return new MenuResource(MenuResourceKind.CURSOR, -1);
    }

    private MenuResource localSlotResource(int slot) {
        return new MenuResource(MenuResourceKind.LOCAL_SLOT, slot);
    }

    private MenuResource remoteSlotResource(int slot) {
        return new MenuResource(MenuResourceKind.REMOTE_SLOT, slot);
    }

    private void runOperationCompletion(TheExchangeCore core, MenuOperation operation,
                                        Runnable completion) {
        try {
            core.getApi().runOnMainThread(() -> {
                if (operationGate.isActive(operation)) {
                    try {
                        completion.run();
                    } catch (RuntimeException completionError) {
                        core.getApi().getLogger().error(
                                "[Exchange|Menu] Failed to apply async operation result",
                                completionError);
                        finishOperation(operation);
                    }
                }
            });
        } catch (RuntimeException schedulingError) {
            operationGate.release(operation);
            core.getApi().getLogger().error(
                    "[Exchange|Menu] Failed to schedule async operation completion: "
                            + rootMessage(schedulingError));
        }
    }

    private void refreshAndFinish(MenuOperation operation) {
        if (!operationGate.isActive(operation)) return;
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            finishOperation(operation);
            return;
        }

        CompletableFuture<ExchangeViewState> refreshFuture;
        try {
            refreshFuture = local
                    ? core.openLocalViewAsync(serverName, access)
                    : core.openRemoteCachedViewAsync(serverName, access);
        } catch (RuntimeException error) {
            finishOperation(operation);
            return;
        }

        refreshFuture.whenComplete((state, error) -> runOperationCompletion(core, operation, () -> {
            try {
                if (error == null && state != null
                        && isViewingInventory(state.getServerName(), state.getScope())) {
                    applyViewState(state);
                }
            } finally {
                finishOperation(operation);
            }
        }));
    }

    private void finishOperation(MenuOperation operation) {
        if (operationGate.release(operation)) {
            sendAllDataToRemote();
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
            clicked(slot, button, ClickType.PICKUP, player);
            return;
        }
        ItemStack source = getCarried().copy();
        if (source.isEmpty()) {
            resetExchangeQuickCraft();
            return;
        }
        java.util.Set<Slot> quickCraftSlotSet = new java.util.LinkedHashSet<>(exchangeQuickCraftSlots);
        java.util.List<Slot> slots = new java.util.ArrayList<>(quickCraftSlotSet);
        int quickCraftType = exchangeQuickCraftType;
        resetExchangeQuickCraft();
        if (slots.stream().anyMatch(slot -> slot.index < 54)) {
            player.displayClientMessage(Component.literal("共享空间暂不支持多槽拖拽分配"), false);
            sendAllDataToRemote();
            return;
        }
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
            int newCount = Math.min(getQuickCraftPlaceCount(quickCraftSlotSet, quickCraftType, source) + existingCount,
                    maxSize);
            int insertCount = newCount - existingCount;
            if (insertCount <= 0) continue;
            ItemStack nowCarried = getCarried();
            if (nowCarried.isEmpty()) break;
            int actualInsert = Math.min(insertCount, nowCarried.getCount());
            slot.setByPlayer(source.copyWithCount(existingCount + actualInsert));
            nowCarried.shrink(actualInsert);
            setCarried(nowCarried.isEmpty() ? ItemStack.EMPTY : nowCarried);
            broadcastChanges();
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
        MenuOperation operation = tryBeginOperation(pickupAllResources(targets));
        if (operation == null) {
            rejectWhileBusy();
            return;
        }
        takePickupAllTarget(targets, 0, buttonNum, player, operation);
    }

    private java.util.List<PickupAllTarget> pickupAllTargets(int buttonNum, NeutralItem carried, int maxStackSize) {
        java.util.List<PickupAllTarget> targets = new java.util.ArrayList<>();
        int currentCount = carried.getCount();
        int start = buttonNum == 0 ? 0 : 53;
        int step = buttonNum == 0 ? 1 : -1;
        for (int pass = 0; pass < 2 && currentCount < maxStackSize; pass++) {
            for (int slot = start; slot >= 0 && slot < 54 && currentCount < maxStackSize; slot += step) {
                NeutralItem item = exchangeContainer.getNeutralItem(slot);
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
                                     int buttonNum, Player player, MenuOperation operation) {
        if (index >= targets.size() || getCarried().isEmpty()) {
            refreshAndFinish(operation);
            return;
        }
        ItemStack carried = getCarried();
        NeutralItem carriedNeutral = neutralFromStack(carried);
        if (carriedNeutral == null || carriedNeutral.isIncompatible()
                || carried.getCount() >= carried.getMaxStackSize()) {
            refreshAndFinish(operation);
            return;
        }
        PickupAllTarget target = targets.get(index);
        NeutralItem slotItem = exchangeContainer.getNeutralItem(target.slot());
        if (slotItem == null || slotItem.isEmpty() || slotItem.isIncompatible()
                || !slotItem.sameStackKind(carriedNeutral)) {
            takePickupAllTarget(targets, index + 1, buttonNum, player, operation);
            return;
        }
        int count = Math.min(target.count(), carried.getMaxStackSize() - carried.getCount());
        count = Math.min(count, slotItem.getCount());
        if (count <= 0) {
            takePickupAllTarget(targets, index + 1, buttonNum, player, operation);
            return;
        }
        final int takeCount = count;

        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            refreshAndFinish(operation);
            return;
        }
        CompletableFuture<ExchangeMutationResult> future;
        try {
            future = core.takeRemoteAsync(
                    serverName, target.slot(), takeCount, playerContext(player), access);
        } catch (RuntimeException error) {
            refreshAndFinish(operation);
            return;
        }
        future.whenComplete((result, error) -> runOperationCompletion(core, operation, () -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        String message = error != null
                                ? "取出失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "取出失败";
                        debugMenuChat("pickupAllTakeFailed", message, target.slot(), buttonNum,
                                ClickType.PICKUP_ALL, null,
                                ExchangeInteractionResult.takeRemote(target.slot(), takeCount), error);
                        if (result != null) {
                            result.acknowledgeSettlement();
                        }
                        refreshAndFinish(operation);
                        return;
                    }
                    if (!applyTakenItem(result, buttonNum, ClickType.PICKUP_ALL, player)) {
                        refreshAndFinish(operation);
                        return;
                    }
                    result.acknowledgeSettlement();
                    takePickupAllTarget(targets, index + 1, buttonNum, player, operation);
                }));
    }

    private ExchangeInteraction buildInteraction(int slotIndex, int buttonNum,
                                                 ClickType clickType) {
        return new ExchangeInteraction(
                online,
                slotIndex,
                buttonNum,
                mapClickType(clickType),
                neutralFromSlot(slotIndex),
                neutralFromStack(getCarried()),
                neutralFromHotbar(buttonNum),
                snapshotNeutralItems());
    }

    private void debugMenuChat(String stage, String message, int slotIndex, int buttonNum,
                               ClickType clickType, ExchangeInteraction interaction,
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
                .append(" mcClick=").append(clickType)
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
        if (slotIndex < 54) return exchangeContainer.getNeutralItem(slotIndex);
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
        return exchangeContainer.snapshotNeutralItems();
    }

    private void applyRemotePut(ExchangeInteractionResult decision, int slotIndex, int buttonNum,
                                ClickType clickType, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;
        MenuOperation operation = tryBeginOperation(putOrSwapResources(
                decision, slotIndex, buttonNum, mapClickType(clickType)));
        if (operation == null) {
            rejectWhileBusy();
            return;
        }

        SourceStack inFlight = removeSourceStack(decision.getCount(), slotIndex, buttonNum, clickType);
        if (inFlight.isEmpty()) {
            debugMenuChat("putSourceMissing", "物品已变化，请重试",
                    slotIndex, buttonNum, clickType, null, decision, null);
            player.displayClientMessage(Component.literal("物品已变化，请重试"), false);
            refreshAndFinish(operation);
            return;
        }

        NeutralItem item;
        try {
            item = neutralFromStack(inFlight.stack());
        } catch (RuntimeException error) {
            restoreSourceStack(player, inFlight);
            refreshAndFinish(operation);
            return;
        }
        CompletableFuture<ExchangeMutationResult> future;
        try {
            future = core.putRemoteAsync(
                    serverName, decision.getTargetSlot(), item, playerContext(player), access);
        } catch (RuntimeException error) {
            restoreSourceStack(player, inFlight);
            refreshAndFinish(operation);
            return;
        }
        future.whenComplete((result, error) -> runOperationCompletion(core, operation, () -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        restoreSourceStack(player, inFlight);
                        String message = error != null
                                ? "放入失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "放入失败";
                        debugMenuChat("putFailed", message, slotIndex, buttonNum,
                                clickType, null, decision, error);
                        player.displayClientMessage(Component.literal(message), false);
                    }
                    if (result != null) {
                        result.acknowledgeSettlement();
                    }
                    refreshAndFinish(operation);
                }));
    }

    private SourceStack removeSourceStack(int count, int slotIndex, int buttonNum,
                                          ClickType clickType) {
        if (count <= 0) return emptySource();
        if (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_CRAFT) {
            ItemStack carried = getCarried();
            if (carried.isEmpty() || carried.getCount() < count) return emptySource();
            ItemStack removed = carried.copyWithCount(count);
            carried.shrink(count);
            setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
            broadcastChanges();
            return new SourceStack(removed, SourceKind.CARRIED, -1);
        }
        if (clickType == ClickType.SWAP && buttonNum >= 0 && buttonNum <= 8) {
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
        dropAtPlayer(player, stack);
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
                if (stack.getCount() <= stack.getMaxStackSize()) {
                    setCarried(stack);
                    return true;
                }
                return false;
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
        int slotMax = Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack));
        if (existing.isEmpty() && stack.getCount() <= slotMax && slot.mayPlace(stack)) {
            slot.setByPlayer(stack);
            return true;
        }
        if (ItemStack.isSameItemSameComponents(existing, stack)
                && existing.getCount() + stack.getCount() <= slotMax) {
            existing.grow(stack.getCount());
            slot.setByPlayer(existing);
            return true;
        }
        return false;
    }

    private void applyRemoteTake(ExchangeInteractionResult decision, int buttonNum,
                                 ClickType clickType, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;
        MenuOperation operation = tryBeginOperation(
                takeResources(decision, buttonNum, mapClickType(clickType)));
        if (operation == null) {
            rejectWhileBusy();
            return;
        }
        CompletableFuture<ExchangeMutationResult> future;
        try {
            future = core.takeRemoteAsync(
                    serverName, decision.getTargetSlot(), decision.getCount(), playerContext(player), access);
        } catch (RuntimeException error) {
            refreshAndFinish(operation);
            return;
        }
        future.whenComplete((result, error) -> runOperationCompletion(core, operation, () -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        String message = error != null
                                ? "取出失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "取出失败";
                        debugMenuChat("takeFailed", message, decision.getTargetSlot(), buttonNum,
                                clickType, null, decision, error);
                        player.displayClientMessage(Component.literal(message), false);
                        if (result != null) {
                            result.acknowledgeSettlement();
                        }
                        refreshAndFinish(operation);
                        return;
                    }
                    if (applyTakenItem(result, buttonNum, clickType, player)) {
                        result.acknowledgeSettlement();
                    }
                    refreshAndFinish(operation);
                }));
    }

    private void applyRemoteSwap(ExchangeInteractionResult decision, int slotIndex, int buttonNum,
                                 ClickType clickType, Player player) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) return;
        MenuOperation operation = tryBeginOperation(putOrSwapResources(
                decision, slotIndex, buttonNum, mapClickType(clickType)));
        if (operation == null) {
            rejectWhileBusy();
            return;
        }

        int putCount = decision.getItem() == null ? 0 : decision.getItem().getCount();
        SourceStack inFlight = removeSourceStack(putCount, slotIndex, buttonNum, clickType);
        if (inFlight.isEmpty()) {
            debugMenuChat("swapSourceMissing", "物品已变化，请重试",
                    slotIndex, buttonNum, clickType, null, decision, null);
            player.displayClientMessage(Component.literal("物品已变化，请重试"), false);
            refreshAndFinish(operation);
            return;
        }

        NeutralItem item;
        try {
            item = neutralFromStack(inFlight.stack());
        } catch (RuntimeException error) {
            restoreSourceStack(player, inFlight);
            refreshAndFinish(operation);
            return;
        }
        CompletableFuture<ExchangeMutationResult> future;
        try {
            future = core.swapRemoteAsync(serverName, decision.getTargetSlot(), item,
                    decision.getExpectedItemId(), decision.getCount(),
                    decision.isBoundedMerge(), playerContext(player), access);
        } catch (RuntimeException error) {
            restoreSourceStack(player, inFlight);
            refreshAndFinish(operation);
            return;
        }
        future.whenComplete((result, error) -> runOperationCompletion(core, operation, () -> {
                    if (error != null || result == null || !result.isSuccess()) {
                        restoreSourceStack(player, inFlight);
                        String message = error != null
                                ? "交换失败: " + rootMessage(error)
                                : result.getFailReason() != null ? result.getFailReason() : "交换失败";
                        debugMenuChat("swapFailed", message, slotIndex, buttonNum,
                                clickType, null, decision, error);
                        player.displayClientMessage(Component.literal(message), false);
                        if (result != null) {
                            result.acknowledgeSettlement();
                        }
                        refreshAndFinish(operation);
                        return;
                    }
                    if (applySwappedItem(result, inFlight, decision.isBoundedMerge(), player)) {
                        result.acknowledgeSettlement();
                    }
                    refreshAndFinish(operation);
                }));
    }

    private boolean applySwappedItem(ExchangeMutationResult result, SourceStack source,
                                     boolean allowEmptyResult, Player player) {
        if (allowEmptyResult && (result.getItem() == null || result.getItem().isEmpty())) {
            broadcastChanges();
            return true;
        }
        if (result.getItem() == null || result.getItem().isEmpty() || result.getItem().isIncompatible()) {
            debugMenuChat("swapDeserializeRejected", "不兼容物品禁止操作",
                    source.slotIndex(), -1, ClickType.PICKUP, null, null, null);
            player.displayClientMessage(Component.literal("不兼容物品禁止操作"), false);
            return false;
        }
        ExchangeAPI api = TheExchangeCore.getInstance().getApi();
        Object itemObj = api.getItemSerializer().deserialize(result.getItem());
        if (!(itemObj instanceof ItemStack giveStack) || giveStack.isEmpty()) {
            debugMenuChat("swapDeserializeFailed", "不兼容物品禁止操作",
                    source.slotIndex(), -1, ClickType.PICKUP, null, null, null);
            player.displayClientMessage(Component.literal("不兼容物品禁止操作"), false);
            return false;
        }
        if (player.isRemoved() || player.containerMenu != this) {
            giveOrDrop(player, giveStack);
            return true;
        }
        if (!placeAtSource(giveStack, source.kind(), source.slotIndex())) {
            giveOrDrop(player, giveStack);
        }
        broadcastChanges();
        return true;
    }

    private boolean applyTakenItem(ExchangeMutationResult result, int buttonNum,
                                   ClickType clickType, Player player) {
        if (result.getItem() == null || result.getItem().isEmpty() || result.getItem().isIncompatible()) {
            debugMenuChat("takeDeserializeRejected", "不兼容物品禁止操作",
                    -1, buttonNum, clickType, null, null, null);
            player.displayClientMessage(Component.literal("不兼容物品禁止操作"), false);
            return false;
        }
        ExchangeAPI api = TheExchangeCore.getInstance().getApi();
        Object itemObj = api.getItemSerializer().deserialize(result.getItem());
        if (!(itemObj instanceof ItemStack giveStack) || giveStack.isEmpty()) {
            debugMenuChat("takeDeserializeFailed", "不兼容物品禁止操作",
                    -1, buttonNum, clickType, null, null, null);
            player.displayClientMessage(Component.literal("不兼容物品禁止操作"), false);
            return false;
        }

        if (player.isRemoved() || player.containerMenu != this) {
            giveOrDrop(player, giveStack);
            return true;
        }

        if (clickType == ClickType.PICKUP || clickType == ClickType.PICKUP_ALL) {
            placeTakenOnCursor(player, giveStack);
        } else if (clickType == ClickType.SWAP && buttonNum >= 0 && buttonNum <= 8) {
            Slot hotbarSlot = this.slots.get(81 + buttonNum);
            if (!hotbarSlot.hasItem() && hotbarSlot.mayPlace(giveStack)) {
                int amount = Math.min(giveStack.getCount(),
                        Math.min(giveStack.getMaxStackSize(), hotbarSlot.getMaxStackSize(giveStack)));
                hotbarSlot.setByPlayer(giveStack.copyWithCount(amount));
                giveStack.shrink(amount);
            }
            giveOrDrop(player, giveStack);
        } else if (clickType == ClickType.QUICK_MOVE) {
            player.getInventory().add(giveStack);
            giveOrDrop(player, giveStack);
        } else {
            giveOrDrop(player, giveStack);
        }
        return true;
    }

    private void placeTakenOnCursor(Player player, ItemStack stack) {
        ItemStack remaining = stack.copy();
        ItemStack carried = getCarried();
        if (carried.isEmpty()) {
            int amount = Math.min(remaining.getCount(), Math.max(1, remaining.getMaxStackSize()));
            setCarried(remaining.copyWithCount(amount));
            remaining.shrink(amount);
        } else if (ItemStack.isSameItemSameComponents(carried, remaining)) {
            int capacity = Math.max(0, carried.getMaxStackSize() - carried.getCount());
            int amount = Math.min(capacity, remaining.getCount());
            if (amount > 0) {
                carried.grow(amount);
                setCarried(carried);
                remaining.shrink(amount);
            }
        }
        giveOrDrop(player, remaining);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) return ItemStack.EMPTY;
        ItemStack before = this.slots.get(slotIndex).getItem().copy();
        clicked(slotIndex, 0, ClickType.QUICK_MOVE, player);
        return before;
    }

    private PlayerExchangeContext playerContext(Player player) {
        return new PlayerExchangeContext(player.getUUID().toString(), player.getName().getString());
    }

    private boolean sameScope(InventoryScope left, InventoryScope right) {
        InventoryScope a = left != null ? left : InventoryScope.server();
        InventoryScope b = right != null ? right : InventoryScope.server();
        return a.equals(b);
    }

    private String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private void dropAtPlayer(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (!player.level().isClientSide()) {
            ItemStack remaining = stack.copy();
            while (!remaining.isEmpty()) {
                int amount = Math.min(remaining.getCount(), Math.max(1, remaining.getMaxStackSize()));
                ItemEntity entity = new ItemEntity(player.level(),
                        player.getX(), player.getY(), player.getZ(), remaining.split(amount));
                entity.setPickUpDelay(20);
                player.level().addFreshEntity(entity);
            }
        }
    }
}
