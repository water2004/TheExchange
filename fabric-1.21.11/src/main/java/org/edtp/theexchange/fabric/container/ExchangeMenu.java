package org.edtp.theexchange.fabric.container;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

// Diff vs 26.1: AbstractContainerMenu.clicked() signature may differ.
// 1.21.11: clicked(slotIndex, button, actionType, player)

public class ExchangeMenu extends AbstractContainerMenu {
    public ExchangeMenu(int containerId, Inventory playerInventory) { super(MenuType.GENERIC_9x6, containerId); }
    @Override public boolean stillValid(Player player) { return !player.isRemoved(); }
    @Override public void removed(Player player) { super.removed(player); }
}
