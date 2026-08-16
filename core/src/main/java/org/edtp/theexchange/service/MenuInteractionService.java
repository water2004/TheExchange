package org.edtp.theexchange.service;

import org.edtp.theexchange.model.ExchangeInteraction;
import org.edtp.theexchange.model.ExchangeInteractionResult;
import org.edtp.theexchange.model.MenuClickType;
import org.edtp.theexchange.model.NeutralItem;

public class MenuInteractionService {
    public ExchangeInteractionResult decide(ExchangeInteraction input) {
        if (touchesIncompatibleItem(input)) {
            return ExchangeInteractionResult.reject("不兼容物品禁止操作");
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
        var targetSlot = ExchangeSlotPlanner.findPutSlot(input.getExchangeItems(), item);
        if (targetSlot.isEmpty()) {
            return ExchangeInteractionResult.reject("共享空间已满");
        }
        return ExchangeInteractionResult.putRemote(targetSlot.getAsInt(), item, item.getCount());
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
        NeutralItem remote = input.getSlotItem();
        if (isEmpty(remote)) {
            int count = input.getButton() == 1 ? 1 : input.getCarriedItem().getCount();
            return ExchangeInteractionResult.putRemote(input.getSlotIndex(), input.getCarriedItem(), count);
        }
        if (remote.isIncompatible()) {
            return ExchangeInteractionResult.reject("不兼容物品禁止操作");
        }
        if (remote.sameStackKind(input.getCarriedItem())) {
            int count = input.getButton() == 1 ? 1 : input.getCarriedItem().getCount();
            int maxStack = remote.getMaxStackSize();
            int capacity = Math.max(0, maxStack - remote.getCount());
            if (count <= capacity) {
                return ExchangeInteractionResult.putRemote(input.getSlotIndex(), input.getCarriedItem(), count);
            }
            if (capacity > 0) {
                return ExchangeInteractionResult.boundedMergeRemote(input.getSlotIndex(), input.getCarriedItem(),
                        remote.getCount(), remote.getItemId());
            }
            if (input.getButton() != 1
                    && input.getCarriedItem().getCount() <= maxStack) {
                return ExchangeInteractionResult.swapRemote(input.getSlotIndex(), input.getCarriedItem(),
                        remote.getCount(), remote.getItemId());
            }
            return ExchangeInteractionResult.refresh(null);
        }
        return ExchangeInteractionResult.swapRemote(input.getSlotIndex(), input.getCarriedItem(),
                remote.getCount(), remote.getItemId());
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
                if (remote.sameStackKind(input.getHotbarItem())) {
                    int maxStack = remote.getMaxStackSize();
                    if (remote.getCount() + input.getHotbarItem().getCount() <= maxStack) {
                        return ExchangeInteractionResult.putRemote(input.getSlotIndex(),
                                input.getHotbarItem(), input.getHotbarItem().getCount());
                    }
                    if (input.getHotbarItem().getCount() > maxStack) {
                        return ExchangeInteractionResult.refresh(null);
                    }
                }
                return ExchangeInteractionResult.swapRemote(input.getSlotIndex(),
                        input.getHotbarItem(), remote.getCount(), remote.getItemId());
            }
            var targetSlot = ExchangeSlotPlanner.findPutSlot(
                    input.getExchangeItems(), input.getHotbarItem());
            if (targetSlot.isEmpty()) {
                return ExchangeInteractionResult.reject("共享空间已满");
            }
            return ExchangeInteractionResult.putRemote(targetSlot.getAsInt(),
                    input.getHotbarItem(), input.getHotbarItem().getCount());
        }
        if (isEmpty(remote)) return ExchangeInteractionResult.refresh(null);
        return ExchangeInteractionResult.takeRemote(input.getSlotIndex(), remote.getCount());
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
        if (input.getClickType() == MenuClickType.QUICK_MOVE
                && input.getSlotIndex() >= ExchangeService.INVENTORY_SLOT_COUNT) return true;
        if (input.getClickType() == MenuClickType.SWAP
                && isExchangeSlot(input.getSlotIndex())
                && input.getButton() >= 0 && input.getButton() < 9) return true;
        return input.getClickType() == MenuClickType.QUICK_CRAFT
                || input.getClickType() == MenuClickType.PICKUP_ALL;
    }

    private boolean isExchangeSlot(int slot) {
        return slot >= 0 && slot < ExchangeService.INVENTORY_SLOT_COUNT;
    }

    private boolean isEmpty(NeutralItem item) {
        return item == null || item.isEmpty();
    }

}
