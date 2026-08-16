package org.edtp.theexchange.model;

import java.util.List;

public final class CachedInventory extends AbstractSlotInventoryCache {

    private volatile long lastSyncedAt;

    public CachedInventory() {
        super(0);
        this.lastSyncedAt = System.currentTimeMillis();
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

    @Override
    protected long metadataAt() {
        return lastSyncedAt;
    }

    @Override
    protected void setMetadataAt(long metadataAt) {
        this.lastSyncedAt = metadataAt;
    }
}
