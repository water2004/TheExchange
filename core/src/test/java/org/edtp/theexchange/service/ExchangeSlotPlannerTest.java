package org.edtp.theexchange.service;

import org.edtp.theexchange.model.NeutralItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeSlotPlannerTest {

    @Test
    void putPrefersCompatiblePartialStackThenEmptySlot() {
        List<NeutralItem> slots = slots(
                item("minecraft:stone", 64, false),
                item("minecraft:stone", 63, false),
                null,
                item("minecraft:stone", 1, true));

        assertEquals(1, ExchangeSlotPlanner.findPutSlot(
                slots, item("minecraft:stone", 1, false)).orElseThrow());

        slots.set(1, item("minecraft:stone", 64, false));
        assertEquals(2, ExchangeSlotPlanner.findPutSlot(
                slots, item("minecraft:stone", 1, false)).orElseThrow());
    }

    @Test
    void incompatibleItemsAreNeverSelectedForAnyAutomationOperation() {
        List<NeutralItem> slots = slots(
                item("mod:unknown", 3, true),
                item("minecraft:stone", 1, false),
                null);

        assertEquals(1, ExchangeSlotPlanner.findTakeSlot(slots).orElseThrow());
        assertEquals(2, ExchangeSlotPlanner.findPutSlot(
                slots, item("mod:unknown", 1, false)).orElseThrow());
        assertTrue(ExchangeSlotPlanner.findPutSlot(
                slots, item("minecraft:stone", 1, true)).isEmpty());
        assertEquals(1, ExchangeSlotPlanner.findTakeSlot(slots,
                item -> item.getItemId().equals("minecraft:stone")).orElseThrow());
        assertTrue(ExchangeSlotPlanner.findTakeSlot(slots, item -> false).isEmpty());
    }

    @Test
    void fullOrInvalidWarehouseHasNoTarget() {
        List<NeutralItem> full = slots(item("minecraft:stone", 64, false));

        assertTrue(ExchangeSlotPlanner.findPutSlot(
                full, item("minecraft:stone", 1, false)).isEmpty());
        assertTrue(ExchangeSlotPlanner.findTakeSlot(List.of()).isEmpty());
        assertTrue(ExchangeSlotPlanner.findTakeSlot(null).isEmpty());
    }

    @Test
    void circularStartDistributesConcurrentHoppersWithoutChangingSelectionRules() {
        List<NeutralItem> empty = slots(null, null, null, null);
        NeutralItem stone = item("minecraft:stone", 1, false);

        assertEquals(0, ExchangeSlotPlanner.findPutSlot(
                empty, stone, 0).orElseThrow());
        assertEquals(2, ExchangeSlotPlanner.findPutSlot(
                empty, stone, 2).orElseThrow());
        assertEquals(1, ExchangeSlotPlanner.findPutSlot(
                empty, stone, 5).orElseThrow());

        List<NeutralItem> items = slots(stone, null, item("minecraft:dirt", 1, false), null);
        assertEquals(2, ExchangeSlotPlanner.findTakeSlot(
                items, ignored -> true, 2).orElseThrow());
        assertEquals(0, ExchangeSlotPlanner.findTakeSlot(
                items, ignored -> true, 3).orElseThrow());
    }

    private static List<NeutralItem> slots(NeutralItem... items) {
        List<NeutralItem> result = new ArrayList<>();
        java.util.Collections.addAll(result, items);
        return result;
    }

    private static NeutralItem item(String id, int count, boolean incompatible) {
        NeutralItem item = new NeutralItem(id, count, id, new byte[0], incompatible, "test");
        item.setMaxStackSize(64);
        return item;
    }
}
