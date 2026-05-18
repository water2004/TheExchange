package org.edtp.theexchange.fabric.container;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.NeutralItem;

import java.util.List;
import java.util.Set;

/**
 * Virtual container backing the exchange GUI.
 * Content is populated from cache/remote and tracked per-slot.
 */
public class ExchangeContainer extends SimpleContainer implements Container {

    private final String serverName;
    private final boolean online;
    private Runnable changeListener;

    public ExchangeContainer(String serverName, boolean online, int rows) {
        super(rows * 9); // 54 for 6 rows
        this.serverName = serverName;
        this.online = online;
    }

    public String getServerName() { return serverName; }
    public boolean isOnline() { return online; }

    @Override
    public boolean stillValid(Player player) {
        return !player.isRemoved();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (changeListener != null) {
            changeListener.run();
        }
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    public void loadFromCache() {
        var cache = TheExchangeCore.getInstance().getCacheManager().getCache(serverName);
        if (cache != null) {
            fillFromNeutralItems(cache.getItems());
        }
    }

    public void loadFromLocal() {
        var items = TheExchangeCore.getInstance().getLocalItemStore().getAllItems();
        fillFromNeutralItems(items);
    }

    private void fillFromNeutralItems(List<NeutralItem> items) {
        clearContent();
        var serializer = TheExchangeCore.getInstance().getApi().getItemSerializer();
        for (int i = 0; i < items.size() && i < getContainerSize(); i++) {
            NeutralItem item = items.get(i);
            if (item != null && !item.isEmpty()) {
                Object mcStack = serializer.deserialize(item);
                if (mcStack instanceof ItemStack stack) {
                    setItem(i, stack);
                }
            }
        }
    }

    public void updateSlotFromCache(int slot) {
        var cache = TheExchangeCore.getInstance().getCacheManager().getCache(serverName);
        if (cache != null) {
            List<NeutralItem> items = cache.getItems();
            if (slot < items.size()) {
                NeutralItem item = items.get(slot);
                var serializer = TheExchangeCore.getInstance().getApi().getItemSerializer();
                Object mcStack = serializer.deserialize(item != null ? item : null);
                setItem(slot, mcStack instanceof ItemStack s ? s : ItemStack.EMPTY);
            }
        }
    }
}
