package org.edtp.theexchange.neoforge.container;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.NeutralItem;

import java.util.List;

public class ExchangeContainer extends SimpleContainer {

    private final boolean online;
    private final NeutralItem[] neutralItems;


    public ExchangeContainer(boolean online, int rows) {
        super(rows * 9);
        this.online = online;
        this.neutralItems = new NeutralItem[getContainerSize()];
    }

    public boolean isOnline() { return online; }

    @Override
    public boolean stillValid(Player player) {
        return !player.isRemoved();
    }

    public void loadFromItems(List<NeutralItem> items) {
        clearContent();
        java.util.Arrays.fill(neutralItems, null);
        if (items == null) return;
        var serializer = TheExchangeCore.getInstance().getApi().getItemSerializer();
        for (int i = 0; i < items.size() && i < getContainerSize(); i++) {
            NeutralItem item = items.get(i);
            neutralItems[i] = item != null ? item.copy() : null;
            if (item != null && !item.isEmpty()) {
                Object mcStack = serializer.deserialize(item);
                if (mcStack instanceof ItemStack stack) {
                    super.setItem(i, stack.copy());
                }
            }
        }
    }

    public NeutralItem getNeutralItem(int slot) {
        if (slot < 0 || slot >= neutralItems.length) return null;
        NeutralItem item = neutralItems[slot];
        return item != null ? item.copy() : null;
    }

    public List<NeutralItem> snapshotNeutralItems() {
        java.util.List<NeutralItem> snapshot = new java.util.ArrayList<>(neutralItems.length);
        for (NeutralItem item : neutralItems) {
            snapshot.add(item != null ? item.copy() : null);
        }
        return snapshot;
    }
}
