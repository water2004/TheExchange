package org.edtp.theexchange.fabric.container;

import net.minecraft.world.SimpleContainer;

// Diff vs 26.1: SimpleContainer API is stable. No changes expected.

public class ExchangeContainer extends SimpleContainer {
    public ExchangeContainer(boolean online, int rows) { super(rows * 9); }
}
