package org.edtp.theexchange.fabric.container;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.service.ExchangeService;

import java.util.List;
import java.util.UUID;

/**
 * Virtual container backing the exchange GUI.
 *
 * LOCAL mode: this container IS the view of LocalItemStore.
 *   Every setItem/removeItem persists immediately to the database,
 *   so ALL interaction methods (click, shift+click, drag, number keys)
 *   are automatically durable without per-method interception.
 *
 * REMOTE mode: reads from cache, writes go through ExchangeService network calls.
 */
public class ExchangeContainer extends SimpleContainer {

    private final String serverName;
    private final boolean local;
    private final boolean online;
    private boolean loading; // true during initial load, suppresses persistence

    public ExchangeContainer(String serverName, boolean local, boolean online, int rows) {
        super(rows * 9);
        this.serverName = serverName;
        this.local = local;
        this.online = online;
    }

    public String getServerName() { return serverName; }
    public boolean isLocal() { return local; }
    public boolean isOnline() { return online; }

    @Override
    public boolean stillValid(Player player) {
        return !player.isRemoved();
    }

    // ============================================================
    //  Load from data source (does NOT trigger persistence)
    // ============================================================

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
        loading = true;
        try {
            clearContent();
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
        } finally {
            loading = false;
        }
    }

    public void reloadFromLocal() {
        loadFromLocal();
    }

    // ============================================================
    //  Write hooks — persist every change immediately
    // ============================================================

    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        if (loading) return;

        if (local) {
            persistSlotToLocal(slot, stack);
        }
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack removed = super.removeItem(slot, count);
        if (loading) return removed;

        if (local) {
            ItemStack remaining = super.getItem(slot);
            persistSlotToLocal(slot, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = super.removeItemNoUpdate(slot);
        if (loading) return removed;

        if (local) {
            persistSlotToLocal(slot, ItemStack.EMPTY);
        }
        return removed;
    }

    @Override
    public void clearContent() {
        super.clearContent();
        // Don't persist during loading — loadFromLocal handles it
    }

    /**
     * Persist a single slot to LocalItemStore via ExchangeService.
     * Uses a generated requestId for idempotency.
     */
    private void persistSlotToLocal(int slot, ItemStack stack) {
        var core = TheExchangeCore.getInstance();
        var serializer = core.getApi().getItemSerializer();
        ExchangeService service = core.getExchangeService();

        if (stack.isEmpty()) {
            // Slot cleared — delete from store by setting version to 0
            // We handle this via takeItem with the full count
            var record = core.getLocalItemStore().getItem(slot);
            if (record != null && record.item() != null && !record.item().isEmpty()) {
                var request = new org.edtp.theexchange.network.protocol.messages.TakeItemRequest(
                        slot, record.item().getItemId(), record.version(),
                        record.item().getCount(),
                        UUID.randomUUID().toString(), "", "");
                service.handleRemoteTake(request);
            }
        } else {
            NeutralItem neutral = serializer.serialize(stack);
            if (neutral == null) return;
            var request = new org.edtp.theexchange.network.protocol.messages.PutItemRequest(
                    slot, neutral, UUID.randomUUID().toString(), "", "");
            service.handleRemotePut(request);
        }
    }

    // ============================================================
    //  Public sync helpers
    // ============================================================

    public void updateSlotFromCache(int slot) {
        var cache = TheExchangeCore.getInstance().getCacheManager().getCache(serverName);
        if (cache != null) {
            List<NeutralItem> items = cache.getItems();
            if (slot < items.size()) {
                NeutralItem item = items.get(slot);
                var serializer = TheExchangeCore.getInstance().getApi().getItemSerializer();
                Object mcStack = serializer.deserialize(item != null ? item : null);
                loading = true;
                super.setItem(slot, mcStack instanceof ItemStack s ? s : ItemStack.EMPTY);
                loading = false;
            }
        }
    }
}
