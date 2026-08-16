package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.ItemSerializer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
    private final ExchangeAPI.Logger logger;
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
    private final IdentityHashMap<LocalInventoryCache, Integer> activeMutations = new IdentityHashMap<>();
    private volatile boolean closed;

    public LocalInventoryCacheManager(LocalItemStore store, ItemSerializer itemSerializer,
                                      ExchangeAPI.Logger logger, int capacity) {
        this.store = store;
        this.itemSerializer = itemSerializer;
        this.logger = logger;
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
        LocalInventoryCache cache = acquireMutationCache(scope);
        try {
            LocalInventoryCache.Result result = cache.put(slot, normalizeAuthoritative(item), expectedVersion, addedBy);
            if (!result.success()) {
                return LocalItemStore.PutResult.fail(result.failReason());
            }
            scheduleFlush(scope, cache);
            return LocalItemStore.PutResult.success(result.item(), result.newVersion());
        } finally {
            releaseMutationCache(cache);
        }
    }

    public LocalItemStore.TakeResult take(InventoryScope scope, int slot, String expectedItemId, int expectedVersion, int requestCount) {
        LocalInventoryCache cache = acquireMutationCache(scope);
        try {
            LocalInventoryCache.Result result = cache.take(slot, expectedItemId, expectedVersion, requestCount);
            if (!result.success()) {
                return LocalItemStore.TakeResult.fail(result.failReason());
            }
            scheduleFlush(scope, cache);
            return LocalItemStore.TakeResult.success(result.item(), result.newVersion());
        } finally {
            releaseMutationCache(cache);
        }
    }

    public LocalItemStore.SwapResult swap(InventoryScope scope, int slot, NeutralItem newItem,
                                          String expectedItemId, int expectedVersion,
                                          int takeCount, boolean boundedMerge, String addedBy) {
        LocalInventoryCache cache = acquireMutationCache(scope);
        try {
            LocalInventoryCache.Result result = cache.swap(slot, normalizeAuthoritative(newItem), expectedItemId,
                    expectedVersion, takeCount, boundedMerge, addedBy);
            if (!result.success()) {
                return LocalItemStore.SwapResult.fail(result.failReason());
            }
            scheduleFlush(scope, cache);
            return LocalItemStore.SwapResult.success(result.item(), result.newVersion());
        } finally {
            releaseMutationCache(cache);
        }
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

    private LocalInventoryCache load(InventoryScope scope) {
        LocalItemStore.ScopeSnapshot snapshot = store.loadScopeSnapshot(scope);
        List<LocalInventoryCache.SlotSnapshot> items = normalizeSnapshots(snapshot.slots());
        long ts = snapshot.lastModifiedAt();
        LocalInventoryCache cache = new LocalInventoryCache(scope, Math.max(54, items.size()));
        cache.markLoaded(items, ts);
        lock.lock();
        try {
            LocalInventoryCache existing = caches.get(scope);
            if (existing != null) {
                return existing;
            }
            caches.put(scope, cache);
            evictIfNeededLocked();
        } finally {
            lock.unlock();
        }
        return cache;
    }

    private LocalInventoryCache acquireMutationCache(InventoryScope scope) {
        while (true) {
            lock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("Local inventory cache is closed");
                }
                LocalInventoryCache cache = caches.get(scope);
                if (cache != null) {
                    activeMutations.merge(cache, 1, Integer::sum);
                    return cache;
                }
            } finally {
                lock.unlock();
            }
            getOrLoad(scope);
        }
    }

    private void releaseMutationCache(LocalInventoryCache cache) {
        lock.lock();
        try {
            Integer users = activeMutations.get(cache);
            if (users == null || users <= 0) {
                throw new IllegalStateException("Local cache mutation lifecycle mismatch");
            }
            if (users == 1) {
                activeMutations.remove(cache);
            } else {
                activeMutations.put(cache, users - 1);
            }
            evictIfNeededLocked();
        } finally {
            lock.unlock();
        }
    }

    private List<LocalInventoryCache.SlotSnapshot> normalizeSnapshots(
            List<LocalInventoryCache.SlotSnapshot> snapshots) {
        List<LocalInventoryCache.SlotSnapshot> normalized = new ArrayList<>();
        if (snapshots == null) return normalized;
        for (LocalInventoryCache.SlotSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            normalized.add(new LocalInventoryCache.SlotSnapshot(
                    snapshot.slot(), normalizeAuthoritative(snapshot.item()), snapshot.version()));
        }
        return normalized;
    }

    private NeutralItem normalizeAuthoritative(NeutralItem item) {
        if (item == null || item.isEmpty()) return item;
        NeutralItem normalized = item.copy();
        try {
            normalized.setMaxStackSize(itemSerializer.getMaxStackSize(normalized));
            normalized.setIncompatible(false);
        } catch (RuntimeException error) {
            normalized.setIncompatible(true);
            normalized.setMaxStackSize(0);
        }
        return normalized;
    }

    private void evictIfNeededLocked() {
        while (caches.size() > capacity) {
            InventoryScope eldestScope = caches.keySet().iterator().next();
            LocalInventoryCache eldest = caches.get(eldestScope);
            if (eldest == null) {
                caches.remove(eldestScope);
                continue;
            }
            if (activeMutations.containsKey(eldest)) {
                return;
            }
            // Keep the entry discoverable until its last snapshot has reached storage. This
            // prevents another thread from loading a stale second cache for the same scope.
            flushCache(eldestScope, eldest);
            caches.remove(eldestScope, eldest);
        }
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
        } catch (Exception e) {
            if (logger != null) {
                logger.warn("Local cache flush failed: " + e.getMessage());
            }
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
