package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.compat.ItemSerializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class LocalInventoryCacheManager {

    private final LocalItemStore store;
    private final ItemSerializer itemSerializer;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "exchange-local-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "exchange-local-flusher");
        thread.setDaemon(true);
        return thread;
    });
    private final LinkedHashMap<InventoryScope, LocalInventoryCache> caches =
            new LinkedHashMap<>(16, 0.75f, true);
    private volatile boolean closed;

    public LocalInventoryCacheManager(LocalItemStore store, ItemSerializer itemSerializer, int capacity) {
        this.store = store;
        this.itemSerializer = itemSerializer;
        this.capacity = Math.max(1, capacity);
        flusher.scheduleWithFixedDelay(this::flushDirtyCachesSafely, 30, 30, TimeUnit.SECONDS);
    }

    public LocalInventoryCache getOrLoad(InventoryScope scope) {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Local inventory cache is closed");
            }
            LocalInventoryCache cache = caches.get(scope);
            if (cache != null) {
                return cache;
            }
        } finally {
            lock.unlock();
        }
        return load(scope);
    }

    public boolean hasCachedScope(InventoryScope scope) {
        lock.lock();
        try {
            return caches.containsKey(scope);
        } finally {
            lock.unlock();
        }
    }

    public List<NeutralItem> snapshot(InventoryScope scope) {
        return getOrLoad(scope).snapshot();
    }

    public NeutralItem get(InventoryScope scope, int slot) {
        return getOrLoad(scope).get(slot);
    }

    public int getVersion(InventoryScope scope, int slot) {
        return getOrLoad(scope).getVersion(slot);
    }

    public LocalItemStore.PutResult put(InventoryScope scope, int slot, NeutralItem item, int expectedVersion, String addedBy) {
        LocalInventoryCache cache = getOrLoad(scope);
        LocalInventoryCache.Result result = cache.put(slot, item, expectedVersion, addedBy,
                itemSerializer::sameStackKind,
                itemSerializer::getMaxStackSize);
        if (!result.success()) {
            return LocalItemStore.PutResult.fail(result.failReason());
        }
        scheduleFlush(scope, cache);
        return LocalItemStore.PutResult.success(result.item(), result.newVersion());
    }

    public LocalItemStore.TakeResult take(InventoryScope scope, int slot, String expectedItemId, int expectedVersion, int requestCount) {
        LocalInventoryCache cache = getOrLoad(scope);
        LocalInventoryCache.Result result = cache.take(slot, expectedItemId, expectedVersion, requestCount);
        if (!result.success()) {
            return LocalItemStore.TakeResult.fail(result.failReason());
        }
        scheduleFlush(scope, cache);
        return LocalItemStore.TakeResult.success(result.item(), result.newVersion());
    }

    public void replaceFromLocal(InventoryScope scope, int slot, NeutralItem item, String addedBy) {
        LocalInventoryCache cache = getOrLoad(scope);
        cache.replaceSlot(slot, item);
        scheduleFlush(scope, cache);
    }

    public void flushAll() {
        List<Map.Entry<InventoryScope, LocalInventoryCache>> entries;
        lock.lock();
        try {
            closed = true;
            entries = new ArrayList<>(caches.entrySet());
        } finally {
            lock.unlock();
        }
        flusher.shutdownNow();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        for (Map.Entry<InventoryScope, LocalInventoryCache> entry : entries) {
            flushCache(entry.getKey(), entry.getValue());
        }
    }

    public void clear(InventoryScope scope) {
        LocalInventoryCache removed;
        lock.lock();
        try {
            removed = caches.remove(scope);
        } finally {
            lock.unlock();
        }
        if (removed != null) {
            flushCache(scope, removed);
        }
    }

    private LocalInventoryCache load(InventoryScope scope) {
        LocalItemStore.ScopeSnapshot snapshot = store.loadScopeSnapshot(scope);
        List<LocalInventoryCache.SlotSnapshot> items = snapshot.slots();
        long ts = snapshot.lastModifiedAt();
        LocalInventoryCache cache = new LocalInventoryCache(scope, Math.max(54, items.size()));
        cache.markLoaded(items, ts);
        List<Map.Entry<InventoryScope, LocalInventoryCache>> evicted;
        lock.lock();
        try {
            LocalInventoryCache existing = caches.get(scope);
            if (existing != null) {
                return existing;
            }
            caches.put(scope, cache);
            evicted = evictIfNeeded();
        } finally {
            lock.unlock();
        }
        for (Map.Entry<InventoryScope, LocalInventoryCache> entry : evicted) {
            flushCache(entry.getKey(), entry.getValue());
        }
        return cache;
    }

    private List<Map.Entry<InventoryScope, LocalInventoryCache>> evictIfNeeded() {
        List<Map.Entry<InventoryScope, LocalInventoryCache>> evicted = new ArrayList<>();
        while (caches.size() > capacity) {
            InventoryScope eldestScope = caches.keySet().iterator().next();
            LocalInventoryCache eldest = caches.remove(eldestScope);
            if (eldest != null) {
                evicted.add(Map.entry(eldestScope, eldest));
            }
        }
        return evicted;
    }

    private void scheduleFlush(InventoryScope scope, LocalInventoryCache cache) {
        if (closed) {
            flushCache(scope, cache);
            return;
        }
        if (!cache.markFlushQueued()) {
            return;
        }
        try {
            writer.execute(() -> {
                try {
                    if (cache.isDirty()) {
                        flushCache(scope, cache);
                    }
                } finally {
                    cache.clearFlushQueued();
                    if (!closed && cache.isDirty()) {
                        scheduleFlush(scope, cache);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            cache.clearFlushQueued();
            if (closed) {
                flushCache(scope, cache);
                return;
            }
            throw e;
        }
    }

    private void flushCache(InventoryScope scope, LocalInventoryCache cache) {
        if (cache == null) return;
        org.edtp.theexchange.model.AbstractSlotInventoryCache.FlushSnapshot snapshot = cache.snapshotForFlush();
        store.persistScopeSnapshot(scope, snapshot.slots(), snapshot.metadataAt(), snapshot.revision());
        cache.markClean(snapshot.revision());
    }

    private void flushDirtyCachesSafely() {
        try {
            flushDirtyCaches();
        } catch (Throwable ignored) {
        }
    }

    private void flushDirtyCaches() {
        if (closed) {
            return;
        }
        List<Map.Entry<InventoryScope, LocalInventoryCache>> entries;
        lock.lock();
        try {
            entries = new ArrayList<>(caches.entrySet());
        } finally {
            lock.unlock();
        }
        for (Map.Entry<InventoryScope, LocalInventoryCache> entry : entries) {
            LocalInventoryCache cache = entry.getValue();
            if (cache != null && cache.isDirty()) {
                scheduleFlush(entry.getKey(), cache);
            }
        }
    }
}
