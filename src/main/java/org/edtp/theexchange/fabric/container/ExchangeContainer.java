package org.edtp.theexchange.fabric.container;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.NeutralItem;

import java.util.List;

public class ExchangeContainer extends SimpleContainer {

    private final boolean online;

    public ExchangeContainer(boolean online, int rows) {
        super(rows * 9);
        this.online = online;
    }

    public boolean isOnline() { return online; }

    @Override
    public boolean stillValid(Player player) {
        return !player.isRemoved();
    }

    public void loadFromItems(List<NeutralItem> items) {
        clearContent();
        if (items == null) return;
        var serializer = TheExchangeCore.getInstance().getApi().getItemSerializer();
        for (int i = 0; i < items.size() && i < getContainerSize(); i++) {
            NeutralItem item = items.get(i);
            if (item != null && !item.isEmpty()) {
                Object mcStack = serializer.deserialize(item);
                if (mcStack instanceof ItemStack stack) {
                    super.setItem(i, stack.copy());
                }
            }
        }
    }
}
