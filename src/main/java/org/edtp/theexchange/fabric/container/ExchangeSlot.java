package org.edtp.theexchange.fabric.container;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Slot in the exchange GUI. Read-only when server is offline.
 * PUT/TAKE decisions are made by the core interaction service.
 */
public class ExchangeSlot extends Slot {

    private final ExchangeContainer exchangeContainer;
    private final int slotIndex;
    private boolean readOnly;

    public ExchangeSlot(ExchangeContainer container, int slot, int x, int y) {
        super(container, slot, x, y);
        this.exchangeContainer = container;
        this.slotIndex = slot;
        this.readOnly = !container.isOnline();
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return !readOnly && !stack.isEmpty();
    }

    @Override
    public boolean mayPickup(Player player) {
        return !readOnly;
    }

    @Override
    public boolean allowModification(Player player) {
        return !readOnly;
    }

    @Override
    public ItemStack safeInsert(ItemStack stack, int count) {
        if (readOnly) return stack;
        return super.safeInsert(stack, count);
    }
}
