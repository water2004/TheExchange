package org.edtp.theexchange.model;

import java.util.ArrayList;
import java.util.List;

public class CachedInventory {
    private List<NeutralItem> items;
    private int totalSlots;
    private long syncedAt;
    private long remoteTimestamp;

    public CachedInventory() {
        this.items = new ArrayList<>();
    }

    public CachedInventory(List<NeutralItem> items, int totalSlots, long syncedAt, long remoteTimestamp) {
        this.items = items;
        this.totalSlots = totalSlots;
        this.syncedAt = syncedAt;
        this.remoteTimestamp = remoteTimestamp;
    }

    public List<NeutralItem> getItems() { return items; }
    public void setItems(List<NeutralItem> items) { this.items = items; }

    public int getTotalSlots() { return totalSlots; }
    public void setTotalSlots(int totalSlots) { this.totalSlots = totalSlots; }

    public long getSyncedAt() { return syncedAt; }
    public void setSyncedAt(long syncedAt) { this.syncedAt = syncedAt; }

    public long getRemoteTimestamp() { return remoteTimestamp; }
    public void setRemoteTimestamp(long remoteTimestamp) { this.remoteTimestamp = remoteTimestamp; }

    public NeutralItem getItem(int slot) {
        if (slot >= 0 && slot < items.size()) {
            return items.get(slot);
        }
        return null;
    }

    public boolean isStale(long expirationMillis) {
        return System.currentTimeMillis() - syncedAt > expirationMillis;
    }
}
