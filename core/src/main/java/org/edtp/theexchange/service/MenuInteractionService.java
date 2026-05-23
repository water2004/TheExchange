package org.edtp.theexchange.service;

import org.edtp.theexchange.model.ExchangeInteraction;
import org.edtp.theexchange.model.ExchangeInteractionResult;
import org.edtp.theexchange.model.MenuClickType;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.PlayerExchangeContext;

public class MenuInteractionService {
    private static final int EXCHANGE_SLOTS = 54;
    private final ExchangeService exchangeService;
    private final org.edtp.theexchange.storage.LocalItemStore localItemStore;

    public MenuInteractionService(ExchangeService exchangeService,
                                  org.edtp.theexchange.storage.LocalItemStore localItemStore) {
        this.exchangeService = exchangeService;
        this.localItemStore = localItemStore;
    }

    public ExchangeInteractionResult decide(ExchangeInteraction input) {
        if (touchesIncompatibleItem(input)) {
            return ExchangeInteractionResult.reject("不兼容物品禁止操作");
        }
        if (input.isLocal()) {
            return ExchangeInteractionResult.localApply();
        }
        if (!touchesExchangeSpace(input)) {
            return ExchangeInteractionResult.passToLoader();
        }
        if (!input.isOnline()) {
            return ExchangeInteractionResult.refresh("目标服务器离线，仅可查看");
        }

        return switch (input.getClickType()) {
            case QUICK_MOVE -> decideQuickMove(input);
            case PICKUP -> decidePickup(input);
            case SWAP -> decideSwap(input);
            case QUICK_CRAFT, PICKUP_ALL, THROW, CLONE ->
                    ExchangeInteractionResult.refresh("远程共享空间暂不支持该操作，请使用点击或 Shift 点击");
        };
    }

    private ExchangeInteractionResult decideQuickMove(ExchangeInteraction input) {
        if (isExchangeSlot(input.getSlotIndex())) {
            NeutralItem item = input.getSlotItem();
            if (isEmpty(item)) return ExchangeInteractionResult.refresh(null);
            if (item.isIncompatible()) return ExchangeInteractionResult.reject("不兼容物品禁止操作");
            return ExchangeInteractionResult.takeRemote(input.getSlotIndex(), item.getCount());
        }
        NeutralItem item = input.getSlotItem();
        if (isEmpty(item)) return ExchangeInteractionResult.refresh(null);
        if (item.isIncompatible()) return ExchangeInteractionResult.reject("不兼容物品禁止操作");
        int targetSlot = findTargetSlot(input);
        if (targetSlot < 0) {
            return ExchangeInteractionResult.reject("共享空间已满");
        }
        return ExchangeInteractionResult.putRemote(targetSlot, item, item.getCount());
    }

    private ExchangeInteractionResult decidePickup(ExchangeInteraction input) {
        if (input.getSlotIndex() < 0) {
            return ExchangeInteractionResult.passToLoader();
        }
        if (!isExchangeSlot(input.getSlotIndex())) {
            return ExchangeInteractionResult.passToLoader();
        }
        if (isEmpty(input.getCarriedItem())) {
            NeutralItem remote = input.getSlotItem();
            if (isEmpty(remote)) return ExchangeInteractionResult.refresh(null);
            if (remote.isIncompatible()) return ExchangeInteractionResult.reject("不兼容物品禁止操作");
            int count = input.getButton() == 1 ? (remote.getCount() + 1) / 2 : remote.getCount();
            return ExchangeInteractionResult.takeRemote(input.getSlotIndex(), count);
        }
        if (input.getCarriedItem().isIncompatible()) {
            return ExchangeInteractionResult.reject("不兼容物品禁止操作");
        }
        int count = input.getButton() == 1 ? 1 : input.getCarriedItem().getCount();
        return ExchangeInteractionResult.putRemote(input.getSlotIndex(), input.getCarriedItem(), count);
    }

    private ExchangeInteractionResult decideSwap(ExchangeInteraction input) {
        if (!isExchangeSlot(input.getSlotIndex()) || input.getButton() < 0 || input.getButton() > 8) {
            return ExchangeInteractionResult.refresh(null);
        }
        NeutralItem remote = input.getSlotItem();
        if (!isEmpty(remote) && remote.isIncompatible()) {
            return ExchangeInteractionResult.reject("不兼容物品禁止操作");
        }
        if (!isEmpty(input.getHotbarItem())) {
            if (input.getHotbarItem().isIncompatible()) {
                return ExchangeInteractionResult.reject("不兼容物品禁止操作");
            }
            if (!isEmpty(remote)) {
                return ExchangeInteractionResult.swapRemote(input.getSlotIndex(),
                        input.getHotbarItem(), input.getHotbarItem().getCount());
            }
            int targetSlot = findTargetSlot(input);
            if (targetSlot < 0) {
                return ExchangeInteractionResult.reject("共享空间已满");
            }
            return ExchangeInteractionResult.putRemote(targetSlot, input.getHotbarItem(), input.getHotbarItem().getCount());
        }
        if (isEmpty(remote)) return ExchangeInteractionResult.refresh(null);
        return ExchangeInteractionResult.takeRemote(input.getSlotIndex(), remote.getCount());
    }

    private int findTargetSlot(ExchangeInteraction input) {
        NeutralItem stack = !isEmpty(input.getHotbarItem()) ? input.getHotbarItem() : input.getSlotItem();
        if (isEmpty(stack)) return -1;
        for (int i = 0; i < EXCHANGE_SLOTS; i++) {
            NeutralItem current = itemAt(input, i);
            if (!isEmpty(current) && current.sameStackKind(stack)
                    && current.getCount() + stack.getCount() <= exchangeService.getMaxStackSize(current)) {
                return i;
            }
        }
        for (int i = 0; i < EXCHANGE_SLOTS; i++) {
            if (isEmpty(itemAt(input, i))) return i;
        }
        return -1;
    }

    private NeutralItem itemAt(ExchangeInteraction input, int slot) {
        if (slot < 0 || slot >= input.getExchangeItems().size()) return null;
        return input.getExchangeItems().get(slot);
    }

    private boolean touchesIncompatibleItem(ExchangeInteraction input) {
        NeutralItem slotItem = input.getSlotItem();
        NeutralItem carriedItem = input.getCarriedItem();
        NeutralItem hotbarItem = input.getHotbarItem();
        return (slotItem != null && slotItem.isIncompatible())
                || (carriedItem != null && carriedItem.isIncompatible())
                || (hotbarItem != null && hotbarItem.isIncompatible());
    }

    private boolean touchesExchangeSpace(ExchangeInteraction input) {
        if (isExchangeSlot(input.getSlotIndex())) return true;
        if (input.getClickType() == MenuClickType.QUICK_MOVE && input.getSlotIndex() >= EXCHANGE_SLOTS) return true;
        if (input.getClickType() == MenuClickType.SWAP
                && isExchangeSlot(input.getSlotIndex())
                && input.getButton() >= 0 && input.getButton() < 9) return true;
        return input.getClickType() == MenuClickType.QUICK_CRAFT
                || input.getClickType() == MenuClickType.PICKUP_ALL;
    }

    private boolean isExchangeSlot(int slot) {
        return slot >= 0 && slot < EXCHANGE_SLOTS;
    }

    private boolean isEmpty(NeutralItem item) {
        return item == null || item.isEmpty();
    }

    public void applyLocalSnapshot(java.util.List<NeutralItem> before,
                                   java.util.List<NeutralItem> after,
                                   PlayerExchangeContext player) {
        java.util.List<Integer> changed = new java.util.ArrayList<>();
        for (int i = 0; i < EXCHANGE_SLOTS; i++) {
            NeutralItem prev = i < before.size() ? before.get(i) : null;
            NeutralItem current = i < after.size() ? after.get(i) : null;
            if (sameItemState(prev, current)) continue;
            localItemStore.replaceSlotFromLocal(i, current, player.uuid());
            changed.add(i);
        }
        if (!changed.isEmpty()) {
            exchangeService.publishLocalInventoryUpdate(changed);
        }
    }

    private boolean sameItemState(NeutralItem a, NeutralItem b) {
        if (isEmpty(a) && isEmpty(b)) return true;
        if (isEmpty(a) || isEmpty(b)) return false;
        return a.getCount() == b.getCount() && a.sameStackKind(b);
    }

}
