package org.edtp.theexchange.service;

import org.edtp.theexchange.model.ExchangeInteraction;
import org.edtp.theexchange.model.ExchangeInteractionResult;
import org.edtp.theexchange.model.MenuClickType;
import org.edtp.theexchange.model.NeutralItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MenuInteractionServiceTest {

    @Test
    void rejectsIncompatibleCarriedItemBeforePut() {
        MenuInteractionService service = new MenuInteractionService();
        NeutralItem carried = item("minecraft:barrier", 32, true);
        ExchangeInteraction input = baseInteraction(0, MenuClickType.PICKUP, null, carried, null);

        ExchangeInteractionResult result = service.decide(input);

        assertEquals(ExchangeInteractionResult.Action.REJECT, result.getAction());
        assertEquals("不兼容物品禁止操作", result.getMessage());
    }

    @Test
    void rejectsIncompatibleRemoteItemBeforeTake() {
        MenuInteractionService service = new MenuInteractionService();
        NeutralItem slotItem = item("minecraft:barrier", 32, true);
        ExchangeInteraction input = baseInteraction(0, MenuClickType.PICKUP, slotItem, null, null);

        ExchangeInteractionResult result = service.decide(input);

        assertEquals(ExchangeInteractionResult.Action.REJECT, result.getAction());
        assertEquals("不兼容物品禁止操作", result.getMessage());
    }

    @Test
    void sameStackPickupUsesPutWhenSlotHasCapacity() {
        MenuInteractionService service = new MenuInteractionService();
        NeutralItem slotItem = item("minecraft:ender_eye", 16, false);
        NeutralItem carried = item("minecraft:ender_eye", 32, false);
        ExchangeInteraction input = baseInteraction(0, MenuClickType.PICKUP, slotItem, carried, null);

        ExchangeInteractionResult result = service.decide(input);

        assertEquals(ExchangeInteractionResult.Action.PUT_REMOTE, result.getAction());
        assertFalse(result.isBoundedMerge());
        assertEquals(32, result.getCount());
    }

    @Test
    void sameStackPickupUsesBoundedMergeWhenSlotWouldOverflow() {
        MenuInteractionService service = new MenuInteractionService();
        NeutralItem slotItem = item("minecraft:ender_eye", 48, false);
        NeutralItem carried = item("minecraft:ender_eye", 32, false);
        ExchangeInteraction input = baseInteraction(0, MenuClickType.PICKUP, slotItem, carried, null);

        ExchangeInteractionResult result = service.decide(input);

        assertEquals(ExchangeInteractionResult.Action.SWAP_REMOTE, result.getAction());
        assertTrue(result.isBoundedMerge());
        assertEquals(48, result.getCount());
        assertEquals("minecraft:ender_eye", result.getExpectedItemId());
    }

    @Test
    void sameStackPickupUsesFullSwapWhenSlotIsFull() {
        MenuInteractionService service = new MenuInteractionService();
        NeutralItem slotItem = item("minecraft:ender_eye", 64, false);
        NeutralItem carried = item("minecraft:ender_eye", 32, false);
        ExchangeInteraction input = baseInteraction(0, MenuClickType.PICKUP, slotItem, carried, null);

        ExchangeInteractionResult result = service.decide(input);

        assertEquals(ExchangeInteractionResult.Action.SWAP_REMOTE, result.getAction());
        assertFalse(result.isBoundedMerge());
        assertEquals(64, result.getCount());
    }

    @Test
    void differentStackSwapDoesNotUseRequestingRuntimeLimit() {
        MenuInteractionService service = new MenuInteractionService();
        NeutralItem remote = item("minecraft:stone", 64, false);
        NeutralItem carried = item("minecraft:shulker_box", 2, false);
        carried.setMaxStackSize(1);

        ExchangeInteractionResult result = service.decide(
                baseInteraction(0, MenuClickType.PICKUP, remote, carried, null));

        assertEquals(ExchangeInteractionResult.Action.SWAP_REMOTE, result.getAction());
        assertEquals("minecraft:shulker_box", result.getItem().getItemId());
    }

    @Test
    void quickMoveChoosesTargetForSourceInsteadOfHotbarItem() {
        MenuInteractionService service = new MenuInteractionService();
        NeutralItem source = item("minecraft:apple", 1, false);
        NeutralItem hotbar = item("minecraft:stone", 1, false);
        List<NeutralItem> remoteItems = new ArrayList<>();
        for (int slot = 0; slot < ExchangeService.INVENTORY_SLOT_COUNT; slot++) {
            remoteItems.add(item("minecraft:dirt", 64, false));
        }
        remoteItems.set(3, item("minecraft:stone", 1, false));
        remoteItems.set(7, item("minecraft:apple", 1, false));

        ExchangeInteraction input = new ExchangeInteraction(
                true, 54, 0, MenuClickType.QUICK_MOVE,
                source, null, hotbar, remoteItems);

        ExchangeInteractionResult result = service.decide(input);

        assertEquals(ExchangeInteractionResult.Action.PUT_REMOTE, result.getAction());
        assertEquals(7, result.getTargetSlot());
        assertEquals("minecraft:apple", result.getItem().getItemId());
    }

    private ExchangeInteraction baseInteraction(int slot, MenuClickType clickType,
                                                NeutralItem slotItem, NeutralItem carriedItem,
                                                NeutralItem hotbarItem) {
        return new ExchangeInteraction(
                true,
                slot,
                0,
                clickType,
                slotItem,
                carriedItem,
                hotbarItem,
                List.of()
        );
    }

    private NeutralItem item(String id, int count, boolean incompatible) {
        NeutralItem item = new NeutralItem(id, count, id, new byte[0], incompatible, "26.1.2");
        item.setVersion(1);
        item.setMaxStackSize(64);
        return item;
    }
}
