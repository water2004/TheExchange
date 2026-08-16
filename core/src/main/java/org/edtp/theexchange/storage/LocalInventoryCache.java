package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.AbstractSlotInventoryCache;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;

import java.util.List;

public final class LocalInventoryCache extends AbstractSlotInventoryCache {

    private final InventoryScope scope;
    private volatile long lastModifiedAt;

    public LocalInventoryCache(InventoryScope scope, int capacity) {
        super(capacity);
        this.scope = scope;
        this.lastModifiedAt = System.currentTimeMillis();
    }

    public InventoryScope getScope() {
        return scope;
    }

    public void markLoaded(List<SlotSnapshot> source, long lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
        loadFromSnapshots(source, lastModifiedAt);
    }

    public long getLastModifiedAt() {
        return lastModifiedAt;
    }

    public List<NeutralItem> snapshot() {
        return snapshotItems();
    }

    public NeutralItem get(int slot) {
        return getItem(slot);
    }

    public Result put(int slot, NeutralItem item, int expectedVersion, String addedBy) {
        return putIntoSlot(slot, item, expectedVersion);
    }

    public Result take(int slot, String expectedItemId, int expectedVersion, int requestCount) {
        return takeFromSlot(slot, expectedItemId, expectedVersion, requestCount);
    }

    public Result swap(int slot, NeutralItem newItem, String expectedItemId,
                       int expectedVersion, int takeCount, boolean boundedMerge,
                       String addedBy) {
        return swapSlot(slot, newItem, expectedItemId, expectedVersion, takeCount,
                boundedMerge);
    }

    @Override
    protected long metadataAt() {
        return lastModifiedAt;
    }

    @Override
    protected void setMetadataAt(long metadataAt) {
        this.lastModifiedAt = metadataAt;
    }
}
