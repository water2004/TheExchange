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
        if (slots == null || slots.isEmpty() || incoming == null
                || incoming.isEmpty() || incoming.isIncompatible()) {
            return OptionalInt.empty();
        }
        int limit = Math.min(slots.size(), ExchangeService.INVENTORY_SLOT_COUNT);
        for (int slot = 0; slot < limit; slot++) {
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
        for (int slot = 0; slot < limit; slot++) {
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
        if (slots == null || slots.isEmpty()) {
            return OptionalInt.empty();
        }
        int limit = Math.min(slots.size(), ExchangeService.INVENTORY_SLOT_COUNT);
        for (int slot = 0; slot < limit; slot++) {
            NeutralItem item = slots.get(slot);
            if (item != null && !item.isEmpty() && !item.isIncompatible()
                    && (destinationAccepts == null || destinationAccepts.test(item.copy()))) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }
}
