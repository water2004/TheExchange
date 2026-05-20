package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class LocalInventoryCache {

    private final InventoryScope scope;
    private final ReentrantReadWriteLock snapshotLock = new ReentrantReadWriteLock();
    private final List<SlotState> slots;
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private volatile boolean dirty;
    private volatile boolean loaded;
    private volatile long lastAccessAt;
    private volatile long lastModifiedAt;

    public LocalInventoryCache(InventoryScope scope, int capacity) {
        this.scope = scope;
        int size = Math.max(0, capacity);
        this.slots = new ArrayList<>(Collections.nCopies(size, null));
        for (int i = 0; i < size; i++) {
            this.slots.set(i, new SlotState());
        }
        this.lastAccessAt = System.currentTimeMillis();
    }

    public InventoryScope getScope() {
        return scope;
    }

    public void markLoaded(List<NeutralItem> source, long lastModifiedAt) {
        snapshotLock.writeLock().lock();
        try {
            int targetSize = Math.max(slotCount(), source != null ? source.size() : 0);
            ensureCapacity(targetSize);
            for (SlotState slot : slotSnapshot()) {
                slot.item = null;
                slot.dirty = false;
            }
            if (source != null) {
                for (int i = 0; i < source.size(); i++) {
                    slots.get(i).item = copyOf(source.get(i));
                }
            }
            dirty = false;
            loaded = true;
            this.lastModifiedAt = lastModifiedAt;
            this.revision.set(0L);
            touchLocked();
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public long getLastModifiedAt() {
        return lastModifiedAt;
    }

    public long getLastAccessAt() {
        return lastAccessAt;
    }

    public long getRevision() {
        return revision.get();
    }

    public boolean isRevision(long expectedRevision) {
        return revision.get() == expectedRevision;
    }

    public boolean isDirty() {
        return dirty;
    }

    public List<NeutralItem> snapshot() {
        snapshotLock.writeLock().lock();
        try {
            touch();
            List<SlotState> currentSlots = slotSnapshot();
            List<NeutralItem> copy = new ArrayList<>(currentSlots.size());
            for (SlotState slot : currentSlots) {
                copy.add(copyOf(slot.item));
            }
            return copy;
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    public Snapshot snapshotForFlush() {
        snapshotLock.writeLock().lock();
        try {
            touch();
            List<SlotState> currentSlots = slotSnapshot();
            List<NeutralItem> copy = new ArrayList<>(currentSlots.size());
            for (SlotState slot : currentSlots) {
                copy.add(copyOf(slot.item));
            }
            return new Snapshot(copy, lastModifiedAt, revision.get());
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    public NeutralItem get(int slot) {
        snapshotLock.readLock().lock();
        try {
            touch();
            SlotState state = existingSlot(slot);
            if (state == null) return null;
            state.lock.lock();
            try {
                return copyOf(state.item);
            } finally {
                state.lock.unlock();
            }
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    public Result put(int slot, NeutralItem item, int expectedVersion, String addedBy,
                      BiPredicate<NeutralItem, NeutralItem> sameStackKind,
                      ToIntFunction<NeutralItem> maxStackSizeProvider) {
        snapshotLock.readLock().lock();
        try {
            touchLocked();
            if (slot < 0) {
                return Result.fail("INVALID_SLOT");
            }
            ensureCapacity(slot + 1);
            SlotState state = existingSlot(slot);
            state.lock.lock();
            try {
                NeutralItem current = state.item;
            if (item == null || item.isEmpty()) {
                return Result.fail("EMPTY_ITEM");
            }
            if (item.isIncompatible()) {
                return Result.fail("INCOMPATIBLE");
            }
            if (current == null || current.isEmpty()) {
                if (expectedVersion != 0) {
                    return Result.fail("VERSION_MISMATCH");
                }
                NeutralItem stored = copyOf(item);
                stored.setVersion(1);
                state.item = stored;
                markDirty(state);
                lastModifiedAt = System.currentTimeMillis();
                return Result.success(stored.copy(), 1);
            }
            if (current.getVersion() != expectedVersion) {
                return Result.fail("VERSION_MISMATCH");
            }
            if (current.isIncompatible()) {
                return Result.fail("INCOMPATIBLE");
            }
            if (sameStackKind == null || !sameStackKind.test(current, item)) {
                return Result.fail("SLOT_OCCUPIED");
            }
            int maxStack = maxStackSizeProvider != null ? Math.max(1, maxStackSizeProvider.applyAsInt(current.copy())) : 64;
            int mergedCount = current.getCount() + item.getCount();
            if (mergedCount > maxStack) {
                return Result.fail("STACK_OVERFLOW");
            }
            current.setCount(mergedCount);
            current.setVersion(current.getVersion() + 1);
            state.item = current;
            markDirty(state);
            lastModifiedAt = System.currentTimeMillis();
            return Result.success(current.copy(), current.getVersion());
            } finally {
                state.lock.unlock();
            }
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    public Result take(int slot, String expectedItemId, int expectedVersion, int requestCount) {
        snapshotLock.readLock().lock();
        try {
            touchLocked();
            SlotState state = existingSlot(slot);
            if (state == null) {
                return Result.fail("INVALID_SLOT");
            }
            state.lock.lock();
            try {
                NeutralItem current = state.item;
            if (current == null || current.isEmpty()) {
                return Result.fail("ITEM_NOT_FOUND");
            }
            if (current.getVersion() != expectedVersion) {
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
            int newVersion = current.getVersion() + 1;
            if (remaining > 0) {
                current.setCount(remaining);
                current.setVersion(newVersion);
                state.item = current;
            } else {
                state.item = null;
            }
            markDirty(state);
            lastModifiedAt = System.currentTimeMillis();
            taken.setVersion(newVersion);
            return Result.success(taken, newVersion);
            } finally {
                state.lock.unlock();
            }
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    public void replaceSlot(int slot, NeutralItem item) {
        snapshotLock.readLock().lock();
        try {
            touchLocked();
            if (slot < 0) {
                return;
            }
            ensureCapacity(slot + 1);
            SlotState state = existingSlot(slot);
            state.lock.lock();
            try {
                state.item = copyOf(item);
                markDirty(state);
            } finally {
                state.lock.unlock();
            }
            lastModifiedAt = System.currentTimeMillis();
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    public void markClean(long persistedRevision) {
        snapshotLock.writeLock().lock();
        try {
            if (revision.get() != persistedRevision) {
                return;
            }
            dirty = false;
            for (SlotState slot : slotSnapshot()) {
                slot.dirty = false;
            }
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    public List<Integer> dirtySlots() {
        snapshotLock.writeLock().lock();
        try {
            List<Integer> dirtySlots = new ArrayList<>();
            List<SlotState> currentSlots = slotSnapshot();
            for (int i = 0; i < currentSlots.size(); i++) {
                if (currentSlots.get(i).dirty) dirtySlots.add(i);
            }
            return dirtySlots;
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    public boolean markFlushQueued() {
        return flushQueued.compareAndSet(false, true);
    }

    public void clearFlushQueued() {
        flushQueued.set(false);
    }

    private void markDirty(SlotState slot) {
        slot.dirty = true;
        dirty = true;
        revision.incrementAndGet();
    }

    private void ensureCapacity(int minSize) {
        synchronized (slots) {
            while (slots.size() < minSize) {
                slots.add(new SlotState());
            }
        }
    }

    private int slotCount() {
        synchronized (slots) {
            return slots.size();
        }
    }

    private SlotState existingSlot(int slot) {
        if (slot < 0) {
            return null;
        }
        synchronized (slots) {
            return slot < slots.size() ? slots.get(slot) : null;
        }
    }

    private List<SlotState> slotSnapshot() {
        synchronized (slots) {
            return new ArrayList<>(slots);
        }
    }

    private void touch() {
        lastAccessAt = System.currentTimeMillis();
    }

    private void touchLocked() {
        lastAccessAt = System.currentTimeMillis();
    }

    private static NeutralItem copyOf(NeutralItem item) {
        return item == null ? null : item.copy();
    }

    private static final class SlotState {
        private final ReentrantLock lock = new ReentrantLock();
        private NeutralItem item;
        private boolean dirty;
    }

    public record Snapshot(List<NeutralItem> items, long lastModifiedAt, long revision) {}

    public record Result(boolean success, String failReason, NeutralItem item, int newVersion) {
        public static Result success(NeutralItem item, int newVersion) {
            return new Result(true, null, item, newVersion);
        }

        public static Result fail(String reason) {
            return new Result(false, reason, null, -1);
        }
    }
}
