package org.edtp.theexchange.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.ToIntFunction;

public abstract class AbstractSlotInventoryCache {

    private final StampedLock structureLock = new StampedLock();
    private final List<SlotState> slots = new ArrayList<>();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private volatile boolean dirty;
    private volatile long lastAccessAt;

    protected AbstractSlotInventoryCache(int initialCapacity) {
        ensureCapacity(Math.max(0, initialCapacity));
        this.lastAccessAt = System.currentTimeMillis();
    }

    public final long getLastAccessAt() {
        return lastAccessAt;
    }

    public final long getRevision() {
        return revision.get();
    }

    public final boolean isRevision(long expectedRevision) {
        return revision.get() == expectedRevision;
    }

    public final boolean isDirty() {
        return dirty;
    }

    public final boolean markFlushQueued() {
        return flushQueued.compareAndSet(false, true);
    }

    public final void clearFlushQueued() {
        flushQueued.set(false);
    }

    public final List<SlotSnapshot> snapshotSlots() {
        List<SlotState> current = slotRefs();
        touch();
        List<SlotSnapshot> snapshots = new ArrayList<>(current.size());
        for (int i = 0; i < current.size(); i++) {
            snapshots.add(snapshotAt(current.get(i), i));
        }
        return snapshots;
    }

    public final FlushSnapshot snapshotForFlush() {
        return new FlushSnapshot(snapshotSlots(), metadataAt(), revision.get());
    }

    public final List<NeutralItem> snapshotItems() {
        List<SlotState> current = slotRefs();
        touch();
        List<NeutralItem> copy = new ArrayList<>(current.size());
        for (SlotState slot : current) {
            copy.add(copyItem(slot));
        }
        return copy;
    }

    public final NeutralItem getItem(int slot) {
        SlotState state = stateForRead(slot);
        if (state == null) {
            return null;
        }
        state.lock.lock();
        try {
            touch();
            return copyItem(state);
        } finally {
            state.lock.unlock();
        }
    }

    public final int getVersion(int slot) {
        SlotState state = stateForRead(slot);
        if (state == null) {
            return 0;
        }
        state.lock.lock();
        try {
            touch();
            return state.version;
        } finally {
            state.lock.unlock();
        }
    }

    public final boolean hasSlot(int slot) {
        return getVersion(slot) != 0 || getItem(slot) != null;
    }

    public final List<Integer> versions() {
        List<SlotState> current = slotRefs();
        touch();
        List<Integer> versions = new ArrayList<>(current.size());
        for (SlotState slot : current) {
            versions.add(copyValue(slot).version());
        }
        return versions;
    }

    public final List<Integer> changedSlots(List<Integer> remoteVersions) {
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

    public final List<Integer> dirtySlots() {
        List<SlotState> current = slotRefs();
        List<Integer> dirtySlots = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            SlotState slot = current.get(i);
            if (slot == null) {
                continue;
            }
            slot.lock.lock();
            try {
                if (slot.dirty) {
                    dirtySlots.add(i);
                }
            } finally {
                slot.lock.unlock();
            }
        }
        return dirtySlots;
    }

    public final void markClean(long persistedRevision) {
        long stamp = structureLock.writeLock();
        List<SlotState> locked = new ArrayList<>();
        try {
            List<SlotState> current = new ArrayList<>(slots);
            for (SlotState slot : current) {
                if (slot == null) {
                    continue;
                }
                slot.lock.lock();
                locked.add(slot);
            }
            if (revision.get() != persistedRevision) {
                return;
            }
            dirty = false;
            for (SlotState slot : locked) {
                slot.dirty = false;
            }
        } finally {
            for (int i = locked.size() - 1; i >= 0; i--) {
                locked.get(i).lock.unlock();
            }
            structureLock.unlockWrite(stamp);
        }
    }

    protected final void loadFromSnapshots(List<SlotSnapshot> source, long metadataAt) {
        long stamp = structureLock.writeLock();
        try {
            int maxSlot = -1;
            if (source != null) {
                for (SlotSnapshot snapshot : source) {
                    if (snapshot != null) {
                        maxSlot = Math.max(maxSlot, snapshot.slot());
                    }
                }
            }
            ensureCapacity(Math.max(slots.size(), maxSlot + 1));
            for (SlotState slot : slots) {
                slot.lock.lock();
                try {
                    slot.item = null;
                    slot.version = 0;
                    slot.dirty = false;
                } finally {
                    slot.lock.unlock();
                }
            }
            if (source != null) {
                for (SlotSnapshot snapshot : source) {
                    if (snapshot == null || snapshot.slot() < 0) {
                        continue;
                    }
                    ensureCapacity(snapshot.slot() + 1);
                    SlotState slot = slots.get(snapshot.slot());
                    slot.lock.lock();
                    try {
                        slot.item = copyOf(snapshot.item());
                        slot.version = snapshot.version();
                        if (slot.item != null) {
                            slot.item.setVersion(slot.version);
                        }
                    } finally {
                        slot.lock.unlock();
                    }
                }
            }
            revision.set(0L);
            dirty = false;
            clearFlushQueued();
            setMetadataAt(metadataAt);
            onLoaded(metadataAt);
            touch();
        } finally {
            structureLock.unlockWrite(stamp);
        }
    }

    protected final Result putIntoSlot(int slot, NeutralItem item, int expectedVersion,
                                       ToIntFunction<NeutralItem> maxStackSizeProvider) {
        if (slot < 0) {
            return Result.fail("INVALID_SLOT");
        }
        SlotState state = stateForWrite(slot);
        if (state == null) {
            return Result.fail("INVALID_SLOT");
        }
        state.lock.lock();
        try {
            touch();
            NeutralItem current = state.item;
            if (item == null || item.isEmpty()) {
                return Result.fail("EMPTY_ITEM");
            }
            if (item.isIncompatible()) {
                return Result.fail("INCOMPATIBLE");
            }
            if (current == null || current.isEmpty()) {
                if (state.version != expectedVersion) {
                    return Result.fail("VERSION_MISMATCH");
                }
                NeutralItem stored = copyOf(item);
                int newVersion = state.version + 1;
                stored.setVersion(newVersion);
                state.item = stored;
                state.version = newVersion;
                markDirty(state);
                onMutated();
                return Result.success(stored.copy(), newVersion);
            }
            if (state.version != expectedVersion) {
                return Result.fail("VERSION_MISMATCH");
            }
            if (current.isIncompatible()) {
                return Result.fail("INCOMPATIBLE");
            }
            if (!current.sameStackKind(item)) {
                return Result.fail("SLOT_OCCUPIED");
            }
            int maxStack = maxStackSizeProvider != null
                    ? Math.max(1, maxStackSizeProvider.applyAsInt(current.copy()))
                    : 64;
            int mergedCount = current.getCount() + item.getCount();
            if (mergedCount > maxStack) {
                return Result.fail("STACK_OVERFLOW");
            }
            int newVersion = state.version + 1;
            current.setCount(mergedCount);
            current.setVersion(newVersion);
            state.item = current;
            state.version = newVersion;
            markDirty(state);
            onMutated();
            return Result.success(current.copy(), newVersion);
        } finally {
            state.lock.unlock();
        }
    }

    protected final Result takeFromSlot(int slot, String expectedItemId, int expectedVersion, int requestCount) {
        if (slot < 0) {
            return Result.fail("INVALID_SLOT");
        }
        SlotState state = stateForRead(slot);
        if (state == null) {
            return Result.fail("INVALID_SLOT");
        }
        state.lock.lock();
        try {
            touch();
            NeutralItem current = state.item;
            if (current == null || current.isEmpty()) {
                return Result.fail("ITEM_NOT_FOUND");
            }
            if (state.version != expectedVersion) {
                return Result.fail("VERSION_MISMATCH");
            }
            if (!Objects.equals(current.getItemId(), expectedItemId)) {
                return Result.fail("ITEM_MISMATCH");
            }
            if (current.isIncompatible()) {
                return Result.fail("INCOMPATIBLE");
            }
            if (requestCount <= 0 || current.getCount() < requestCount) {
                return Result.fail("INSUFFICIENT");
            }
            NeutralItem taken = current.copy();
            taken.setCount(requestCount);
            int remaining = current.getCount() - requestCount;
            int newVersion = state.version + 1;
            state.version = newVersion;
            if (remaining > 0) {
                current.setCount(remaining);
                current.setVersion(newVersion);
                state.item = current;
            } else {
                state.item = null;
            }
            markDirty(state);
            onMutated();
            taken.setVersion(newVersion);
            return Result.success(taken, newVersion);
        } finally {
            state.lock.unlock();
        }
    }

    protected final Result swapSlot(int slot, NeutralItem newItem, String expectedItemId,
                                    int expectedVersion, int takeCount) {
        if (slot < 0) {
            return Result.fail("INVALID_SLOT");
        }
        SlotState state = stateForRead(slot);
        if (state == null) {
            return Result.fail("INVALID_SLOT");
        }
        state.lock.lock();
        try {
            touch();
            NeutralItem current = state.item;
            if (current == null || current.isEmpty()) {
                return Result.fail("ITEM_NOT_FOUND");
            }
            if (state.version != expectedVersion) {
                return Result.fail("VERSION_MISMATCH");
            }
            if (!Objects.equals(current.getItemId(), expectedItemId)) {
                return Result.fail("ITEM_MISMATCH");
            }
            if (current.isIncompatible() || newItem == null || newItem.isEmpty() || newItem.isIncompatible()) {
                return Result.fail("INCOMPATIBLE");
            }
            if (takeCount <= 0 || current.getCount() != takeCount) {
                return Result.fail("INSUFFICIENT");
            }
            NeutralItem taken = current.copy();
            taken.setCount(takeCount);
            int newVersion = state.version + 1;
            NeutralItem stored = copyOf(newItem);
            stored.setVersion(newVersion);
            state.item = stored;
            state.version = newVersion;
            markDirty(state);
            onMutated();
            taken.setVersion(newVersion);
            return Result.success(taken, newVersion);
        } finally {
            state.lock.unlock();
        }
    }

    protected final void setSlotExact(int slot, NeutralItem item, int version) {
        if (slot < 0) {
            return;
        }
        SlotState state = stateForWrite(slot);
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            touch();
            state.item = copyOf(item);
            if (state.item != null) {
                state.item.setVersion(version);
            }
            state.version = version;
            markDirty(state);
            onMutated();
        } finally {
            state.lock.unlock();
        }
    }

    protected final SlotState stateForRead(int slot) {
        if (slot < 0) {
            return null;
        }
        long stamp = structureLock.tryOptimisticRead();
        SlotState state = slot < slots.size() ? slots.get(slot) : null;
        if (structureLock.validate(stamp)) {
            return state;
        }
        stamp = structureLock.readLock();
        try {
            return slot < slots.size() ? slots.get(slot) : null;
        } finally {
            structureLock.unlockRead(stamp);
        }
    }

    protected final SlotState stateForWrite(int slot) {
        if (slot < 0) {
            return null;
        }
        long stamp = structureLock.tryOptimisticRead();
        SlotState state = slot < slots.size() ? slots.get(slot) : null;
        if (state != null && structureLock.validate(stamp)) {
            return state;
        }
        stamp = structureLock.writeLock();
        try {
            ensureCapacity(slot + 1);
            return slots.get(slot);
        } finally {
            structureLock.unlockWrite(stamp);
        }
    }

    protected final long currentRevision() {
        return revision.get();
    }

    protected abstract long metadataAt();

    protected abstract void setMetadataAt(long metadataAt);

    protected void onLoaded(long metadataAt) {
    }

    protected void onMutated() {
    }

    private void ensureCapacity(int minSize) {
        while (slots.size() < minSize) {
            slots.add(new SlotState());
        }
    }

    private List<SlotState> slotRefs() {
        long stamp = structureLock.readLock();
        try {
            return new ArrayList<>(slots);
        } finally {
            structureLock.unlockRead(stamp);
        }
    }

    private void touch() {
        lastAccessAt = System.currentTimeMillis();
    }

    private void markDirty(SlotState slot) {
        slot.dirty = true;
        dirty = true;
        revision.incrementAndGet();
    }

    private SlotSnapshot snapshotAt(SlotState slot, int index) {
        SnapshotValue value = copyValue(slot);
        return new SlotSnapshot(index, value.item(), value.version());
    }

    private static NeutralItem copyOf(NeutralItem item) {
        return item == null ? null : item.copy();
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

    protected static final class SlotState {
        private final ReentrantLock lock = new ReentrantLock();
        private NeutralItem item;
        private int version;
        private boolean dirty;
    }

    public record SlotSnapshot(int slot, NeutralItem item, int version) {
    }

    public record FlushSnapshot(List<SlotSnapshot> slots, long metadataAt, long revision) {
    }

    public record Result(boolean success, String failReason, NeutralItem item, int newVersion) {
        public static Result success(NeutralItem item, int newVersion) {
            return new Result(true, null, item, newVersion);
        }

        public static Result fail(String reason) {
            return new Result(false, reason, null, -1);
        }
    }

    private record SnapshotValue(NeutralItem item, int version) {
    }
}
