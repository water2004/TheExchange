package org.edtp.theexchange.service;

import org.edtp.theexchange.model.NeutralItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarehouseAutomationPlannerTest {

    @Test
    void putPrefersCompatiblePartialStackThenEmptySlot() {
        List<NeutralItem> slots = slots(
                item("minecraft:stone", 64, false),
                item("minecraft:stone", 63, false),
                null,
                item("minecraft:stone", 1, true));

        assertEquals(1, WarehouseAutomationPlanner.findPutSlot(
                slots, item("minecraft:stone", 1, false), ignored -> 64).orElseThrow());

        slots.set(1, item("minecraft:stone", 64, false));
        assertEquals(2, WarehouseAutomationPlanner.findPutSlot(
                slots, item("minecraft:stone", 1, false), ignored -> 64).orElseThrow());
    }

    @Test
    void incompatibleItemsAreNeverSelectedForAnyAutomationOperation() {
        List<NeutralItem> slots = slots(
                item("mod:unknown", 3, true),
                item("minecraft:stone", 1, false),
                null);

        assertEquals(1, WarehouseAutomationPlanner.findTakeSlot(slots).orElseThrow());
        assertEquals(2, WarehouseAutomationPlanner.findPutSlot(
                slots, item("mod:unknown", 1, false), ignored -> 64).orElseThrow());
        assertTrue(WarehouseAutomationPlanner.findPutSlot(
                slots, item("minecraft:stone", 1, true), ignored -> 64).isEmpty());
        assertEquals(1, WarehouseAutomationPlanner.findTakeSlot(slots,
                item -> item.getItemId().equals("minecraft:stone")).orElseThrow());
        assertTrue(WarehouseAutomationPlanner.findTakeSlot(slots, item -> false).isEmpty());
    }

    @Test
    void fullOrInvalidWarehouseHasNoTarget() {
        List<NeutralItem> full = slots(item("minecraft:stone", 64, false));

        assertTrue(WarehouseAutomationPlanner.findPutSlot(
                full, item("minecraft:stone", 1, false), ignored -> 64).isEmpty());
        assertTrue(WarehouseAutomationPlanner.findTakeSlot(List.of()).isEmpty());
        assertTrue(WarehouseAutomationPlanner.findTakeSlot(null).isEmpty());
    }

    private static List<NeutralItem> slots(NeutralItem... items) {
        List<NeutralItem> result = new ArrayList<>();
        java.util.Collections.addAll(result, items);
        return result;
    }

    private static NeutralItem item(String id, int count, boolean incompatible) {
        return new NeutralItem(id, count, id, new byte[0], incompatible, "test");
    }
}
