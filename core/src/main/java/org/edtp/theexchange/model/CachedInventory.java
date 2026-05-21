package org.edtp.theexchange.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class CachedInventory {

    private final InventoryScope scope;
    private final List<SlotState> slots = new ArrayList<>();
    private final AtomicLong revision = new AtomicLong();
    private final ReentrantLock structureLock = new ReentrantLock();
    private volatile long lastAccessAt;
    private volatile long lastSyncedAt;

    public CachedInventory(InventoryScope scope) {
        this.scope = scope;
        this.lastAccessAt = System.currentTimeMillis();
    }

    public InventoryScope getScope() {
        return scope;
    }

    public long getLastAccessAt() {
        return lastAccessAt;
    }

    public long getLastSyncedAt() {
        return lastSyncedAt;
    }

    public long getRevision() {
        return revision.get();
    }

    public void markLoaded(List<SlotSnapshot> snapshots, long syncedAt) {
        structureLock.lock();
        try {
            slots.clear();
            int maxSlot = -1;
            if (snapshots != null) {
                for (SlotSnapshot snapshot : snapshots) {
                    if (snapshot != null) {
                        maxSlot = Math.max(maxSlot, snapshot.slot());
                    }
                }
            }
            for (int i = 0; i <= maxSlot; i++) {
                slots.add(new SlotState());
            }
            if (snapshots != null) {
                for (SlotSnapshot snapshot : snapshots) {
                    if (snapshot == null || snapshot.slot() < 0) continue;
                    ensureCapacity(snapshot.slot() + 1);
                    SlotState state = slots.get(snapshot.slot());
                    state.item = snapshot.item() != null ? snapshot.item().copy() : null;
                    if (state.item != null) {
                        state.item.setVersion(snapshot.version());
                    }
                }
            }
            revision.set(0L);
            lastSyncedAt = syncedAt;
            touch();
        } finally {
            structureLock.unlock();
        }
    }

    public List<NeutralItem> snapshot() {
        structureLock.lock();
        try {
            touch();
            List<NeutralItem> copy = new ArrayList<>(slots.size());
            for (SlotState state : slots) {
                copy.add(copyItem(state));
            }
            return copy;
        } finally {
            structureLock.unlock();
        }
    }

    public NeutralItem getItem(int slot) {
        SlotState state = state(slot);
        if (state == null) return null;
        state.lock.lock();
        try {
            touch();
            return state.item == null ? null : state.item.copy();
        } finally {
            state.lock.unlock();
        }
    }

    public int getVersion(int slot) {
        SlotState state = state(slot);
        if (state == null) return 0;
        state.lock.lock();
        try {
            touch();
            return state.item == null ? 0 : state.item.getVersion();
        } finally {
            state.lock.unlock();
        }
    }

    public boolean hasSlot(int slot) {
        return getItem(slot) != null;
    }

    public void replaceSlot(int slot, NeutralItem item, int version) {
        SlotState state = ensureState(slot);
        state.lock.lock();
        try {
            state.item = item == null ? null : item.copy();
            if (state.item != null) {
                state.item.setVersion(version);
            }
            revision.incrementAndGet();
            touch();
        } finally {
            state.lock.unlock();
        }
    }

    public void removeSlot(int slot) {
        replaceSlot(slot, null, 0);
    }

    public List<SlotSnapshot> snapshotSlots() {
        structureLock.lock();
        try {
            touch();
            List<SlotSnapshot> snapshots = new ArrayList<>(slots.size());
            for (int i = 0; i < slots.size(); i++) {
                SlotState state = slots.get(i);
                SnapshotValue value = copyValue(state);
                snapshots.add(new SlotSnapshot(i, value.item(), value.version()));
            }
            return snapshots;
        } finally {
            structureLock.unlock();
        }
    }

    public Map<Integer, Integer> versionMap() {
        structureLock.lock();
        try {
            touch();
            Map<Integer, Integer> versions = new LinkedHashMap<>();
            for (int i = 0; i < slots.size(); i++) {
                SnapshotValue value = copyValue(slots.get(i));
                if (value.version() > 0) {
                    versions.put(i, value.version());
                }
            }
            return versions;
        } finally {
            structureLock.unlock();
        }
    }

    public List<Integer> changedSlots(Map<Integer, Integer> remoteVersions) {
        Map<Integer, Integer> localVersions = versionMap();
        Map<Integer, Integer> remote = remoteVersions != null ? remoteVersions : Map.of();
        List<Integer> changed = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : remote.entrySet()) {
            int slot = entry.getKey();
            int remoteVersion = entry.getValue() != null ? entry.getValue() : 0;
            if (localVersions.getOrDefault(slot, 0) != remoteVersion) {
                changed.add(slot);
            }
        }
        for (Map.Entry<Integer, Integer> entry : localVersions.entrySet()) {
            int slot = entry.getKey();
            if (!remote.containsKey(slot) && entry.getValue() > 0) {
                changed.add(slot);
            }
        }
        return changed;
    }

    private SlotState ensureState(int slot) {
        structureLock.lock();
        try {
            ensureCapacity(slot + 1);
            return slots.get(slot);
        } finally {
            structureLock.unlock();
        }
    }

    private SlotState state(int slot) {
        structureLock.lock();
        try {
            if (slot < 0 || slot >= slots.size()) {
                return null;
            }
            SlotState state = slots.get(slot);
            if (state == null) {
                state = new SlotState();
                slots.set(slot, state);
            }
            return state;
        } finally {
            structureLock.unlock();
        }
    }

    private void ensureCapacity(int minSize) {
        while (slots.size() < minSize) {
            slots.add(new SlotState());
        }
    }

    private void touch() {
        lastAccessAt = System.currentTimeMillis();
    }

    private static NeutralItem copyItem(SlotState state) {
        return copyValue(state).item();
    }

    private static SnapshotValue copyValue(SlotState state) {
        if (state == null) {
            return new SnapshotValue(null, 0);
        }
        state.lock.lock();
        try {
            NeutralItem item = state.item == null ? null : state.item.copy();
            return new SnapshotValue(item, item != null ? item.getVersion() : 0);
        } finally {
            state.lock.unlock();
        }
    }

    private static final class SlotState {
        private final ReentrantLock lock = new ReentrantLock();
        private NeutralItem item;
    }

    public record SlotSnapshot(int slot, NeutralItem item, int version) {}

    private record SnapshotValue(NeutralItem item, int version) {}
}
