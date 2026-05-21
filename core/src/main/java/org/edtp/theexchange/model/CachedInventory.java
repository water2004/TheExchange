package org.edtp.theexchange.model;

import java.util.List;

public final class CachedInventory extends AbstractSlotInventoryCache {

    private final InventoryScope scope;
    private volatile long lastSyncedAt;

    public CachedInventory(InventoryScope scope) {
        super(0);
        this.scope = scope;
        this.lastSyncedAt = System.currentTimeMillis();
    }

    public InventoryScope getScope() {
        return scope;
    }

    public long getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void markLoaded(List<SlotSnapshot> snapshots, long syncedAt) {
        this.lastSyncedAt = syncedAt;
        loadFromSnapshots(snapshots, syncedAt);
    }

    public List<NeutralItem> snapshot() {
        return snapshotItems();
    }

    public void replaceSlot(int slot, NeutralItem item, int version) {
        setSlotExact(slot, item, version);
        lastSyncedAt = System.currentTimeMillis();
    }

    public void removeSlot(int slot) {
        replaceSlot(slot, null, getVersion(slot));
    }

    public Result put(int slot, NeutralItem item, int expectedVersion,
                      java.util.function.BiPredicate<NeutralItem, NeutralItem> sameStackKind,
                      java.util.function.ToIntFunction<NeutralItem> maxStackSizeProvider) {
        return putIntoSlot(slot, item, expectedVersion, sameStackKind, maxStackSizeProvider);
    }

    @Override
    protected long metadataAt() {
        return lastSyncedAt;
    }

    @Override
    protected void setMetadataAt(long metadataAt) {
        this.lastSyncedAt = metadataAt;
    }
}
