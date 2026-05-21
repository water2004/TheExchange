package org.edtp.theexchange.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class CachedInventory {

    private final InventoryScope scope;
    private final List<SlotState> slots = new ArrayList<>();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private final ReentrantLock structureLock = new ReentrantLock();
    private volatile boolean dirty;
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

    public boolean isDirty() {
        return dirty;
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
                    state.lock.lock();
                    try {
                        state.item = snapshot.item() != null ? snapshot.item().copy() : null;
                        state.version = snapshot.version();
                        if (state.item != null) {
                            state.item.setVersion(state.version);
                        }
                    } finally {
                        state.lock.unlock();
                    }
                }
            }
            revision.set(0L);
            dirty = false;
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
            NeutralItem copy = state.item == null ? null : state.item.copy();
            if (copy != null) {
                copy.setVersion(state.version);
            }
            return copy;
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
            return state.version;
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
            state.version = version;
            markDirty(state);
            touch();
        } finally {
            state.lock.unlock();
        }
    }

    public void removeSlot(int slot) {
        replaceSlot(slot, null, getVersion(slot));
    }

    public List<SlotSnapshot> snapshotSlots() {
        structureLock.lock();
        try {
            touch();
            return snapshotSlotsUnlocked();
        } finally {
            structureLock.unlock();
        }
    }

    public Snapshot snapshotForFlush() {
        structureLock.lock();
        try {
            touch();
            return new Snapshot(snapshotSlotsUnlocked(), revision.get());
        } finally {
            structureLock.unlock();
        }
    }

    public void markClean(long persistedRevision) {
        structureLock.lock();
        try {
            if (revision.get() != persistedRevision) {
                return;
            }
            dirty = false;
            for (SlotState state : slots) {
                if (state == null) continue;
                state.lock.lock();
                try {
                    state.dirty = false;
                } finally {
                    state.lock.unlock();
                }
            }
        } finally {
            structureLock.unlock();
        }
    }

    public List<Integer> dirtySlots() {
        structureLock.lock();
        try {
            List<Integer> dirtySlots = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                SlotState state = slots.get(i);
                if (state == null) continue;
                state.lock.lock();
                try {
                    if (state.dirty) {
                        dirtySlots.add(i);
                    }
                } finally {
                    state.lock.unlock();
                }
            }
            return dirtySlots;
        } finally {
            structureLock.unlock();
        }
    }

    public boolean markFlushQueued() {
        return flushQueued.compareAndSet(false, true);
    }

    public void clearFlushQueued() {
        flushQueued.set(false);
    }

    public List<Integer> versions() {
        structureLock.lock();
        try {
            touch();
            List<Integer> versions = new ArrayList<>(slots.size());
            for (int i = 0; i < slots.size(); i++) {
                SnapshotValue value = copyValue(slots.get(i));
                versions.add(value.version());
            }
            return versions;
        } finally {
            structureLock.unlock();
        }
    }

    public List<Integer> changedSlots(List<Integer> remoteVersions) {
        List<Integer> localVersions = versions();
        List<Integer> remote = remoteVersions != null ? remoteVersions : List.of();
        List<Integer> changed = new ArrayList<>();
        int max = Math.max(localVersions.size(), remote.size());
        for (int slot = 0; slot < max; slot++) {
            int localVersion = slot < localVersions.size() && localVersions.get(slot) != null ? localVersions.get(slot) : 0;
            int remoteVersion = slot < remote.size() && remote.get(slot) != null ? remote.get(slot) : 0;
            if (localVersion != remoteVersion) {
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

    private void markDirty(SlotState state) {
        state.dirty = true;
        dirty = true;
        revision.incrementAndGet();
    }

    private List<SlotSnapshot> snapshotSlotsUnlocked() {
        List<SlotSnapshot> snapshots = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            SlotState state = slots.get(i);
            SnapshotValue value = copyValue(state);
            snapshots.add(new SlotSnapshot(i, value.item(), value.version()));
        }
        return snapshots;
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
            if (item != null) {
                item.setVersion(state.version);
            }
            return new SnapshotValue(item, state.version);
        } finally {
            state.lock.unlock();
        }
    }

    private static final class SlotState {
        private final ReentrantLock lock = new ReentrantLock();
        private NeutralItem item;
        private int version;
        private boolean dirty;
    }

    public record SlotSnapshot(int slot, NeutralItem item, int version) {}

    public record Snapshot(List<SlotSnapshot> slots, long revision) {}

    private record SnapshotValue(NeutralItem item, int version) {}
}
