package org.edtp.theexchange.fabric.container;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

// Diff vs 26.1: Slot API is stable. No changes expected.

public class ExchangeSlot extends Slot {
    public ExchangeSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
}
