package org.edtp.theexchange.service;

import org.edtp.theexchange.model.NeutralItem;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;
import java.util.function.Predicate;

/** Loader-independent slot selection policy for automated warehouse transfers. */
public final class WarehouseAutomationPlanner {
    private WarehouseAutomationPlanner() {
    }

    public static OptionalInt findPutSlot(List<NeutralItem> slots, NeutralItem incoming,
                                          ToIntFunction<NeutralItem> maxStackSizeProvider) {
        return findPutSlot(slots, incoming, maxStackSizeProvider, 0);
    }

    public static OptionalInt findPutSlot(List<NeutralItem> slots, NeutralItem incoming,
                                          ToIntFunction<NeutralItem> maxStackSizeProvider,
                                          int startSlot) {
        if (slots == null || slots.isEmpty() || incoming == null
                || incoming.isEmpty() || incoming.isIncompatible()) {
            return OptionalInt.empty();
        }
        int limit = Math.min(slots.size(), ExchangeService.INVENTORY_SLOT_COUNT);
        int start = Math.floorMod(startSlot, limit);
        for (int offset = 0; offset < limit; offset++) {
            int slot = (start + offset) % limit;
            NeutralItem current = slots.get(slot);
            if (current == null || current.isEmpty() || current.isIncompatible()
                    || !current.sameStackKind(incoming)) {
                continue;
            }
            int maxStack;
            try {
                maxStack = maxStackSizeProvider != null
                        ? Math.max(1, maxStackSizeProvider.applyAsInt(current.copy())) : 64;
            } catch (RuntimeException ignored) {
                continue;
            }
            if ((long) current.getCount() + incoming.getCount() <= maxStack) {
                return OptionalInt.of(slot);
            }
        }
        for (int offset = 0; offset < limit; offset++) {
            int slot = (start + offset) % limit;
            NeutralItem current = slots.get(slot);
            if (current == null || current.isEmpty()) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public static OptionalInt findTakeSlot(List<NeutralItem> slots) {
        return findTakeSlot(slots, ignored -> true);
    }

    public static OptionalInt findTakeSlot(List<NeutralItem> slots,
                                           Predicate<NeutralItem> destinationAccepts) {
        return findTakeSlot(slots, destinationAccepts, 0);
    }

    public static OptionalInt findTakeSlot(List<NeutralItem> slots,
                                           Predicate<NeutralItem> destinationAccepts,
                                           int startSlot) {
        if (slots == null || slots.isEmpty()) {
            return OptionalInt.empty();
        }
        int limit = Math.min(slots.size(), ExchangeService.INVENTORY_SLOT_COUNT);
        int start = Math.floorMod(startSlot, limit);
        for (int offset = 0; offset < limit; offset++) {
            int slot = (start + offset) % limit;
            NeutralItem item = slots.get(slot);
            if (item != null && !item.isEmpty() && !item.isIncompatible()
                    && (destinationAccepts == null || destinationAccepts.test(item.copy()))) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }
}
