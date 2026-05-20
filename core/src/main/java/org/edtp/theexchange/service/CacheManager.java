package org.edtp.theexchange.service;

import org.edtp.theexchange.model.CachedInventory;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.storage.RemoteCacheStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class CacheManager {

    private static final long CACHE_EXPIRY_MS = 24L * 60L * 60L * 1000L;

    private final RemoteCacheStore cacheStore;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<RemoteScopeKey, CachedInventory> caches =
            new LinkedHashMap<>(16, 0.75f, true);
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "exchange-remote-cache-writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    public CacheManager(RemoteCacheStore cacheStore, int capacity) {
        this.cacheStore = cacheStore;
        this.capacity = Math.max(1, capacity);
    }

    public CachedInventory getCache(String serverName) {
        return getCache(serverName, InventoryScope.server());
    }

    public CachedInventory getCache(String serverName, InventoryScope scope) {
        RemoteScopeKey key = RemoteScopeKey.of(serverName, scope);
        CachedInventory cached = getCached(key);
        if (cached == null) {
            cached = cacheStore.getCache(serverName, scope);
            if (cached == null) return null;
            putCached(key, cached);
        }
        if (cached.isStale(CACHE_EXPIRY_MS)) {
            clearCache(serverName, scope);
            return null;
        }
        return cached.copy();
    }

    public void updateCache(String serverName, List<NeutralItem> items, long remoteTimestamp) {
        updateCache(serverName, InventoryScope.server(), items, remoteTimestamp);
    }

    public void updateCache(String serverName, InventoryScope scope, List<NeutralItem> items, long remoteTimestamp) {
        CachedInventory cache = new CachedInventory(copyItems(items),
                items != null ? items.size() : 0, System.currentTimeMillis(), remoteTimestamp);
        RemoteScopeKey key = RemoteScopeKey.of(serverName, scope);
        putCached(key, cache);
        persistAsync(key, cache);
    }

    public void updateCacheSlot(String serverName, int slot, NeutralItem item, long remoteTimestamp) {
        updateCacheSlot(serverName, InventoryScope.server(), slot, item, remoteTimestamp);
    }

    public void updateCacheSlot(String serverName, InventoryScope scope, int slot, NeutralItem item, long remoteTimestamp) {
        RemoteScopeKey key = RemoteScopeKey.of(serverName, scope);
        CachedInventory cache = getCached(key);
        if (cache == null) {
            cache = cacheStore.getCache(serverName, scope);
        }
        if (cache == null) return;
        List<NeutralItem> items = copyItems(cache.getItems());
        while (items.size() <= slot) {
            items.add(null);
        }
        items.set(slot, copyOf(item));
        CachedInventory updated = new CachedInventory(items, Math.max(cache.getTotalSlots(), items.size()),
                System.currentTimeMillis(), remoteTimestamp);
        putCached(key, updated);
        persistAsync(key, updated);
    }

    public long getRemoteTimestamp(String serverName) {
        CachedInventory cache = getCache(serverName);
        return cache != null ? cache.getRemoteTimestamp() : 0;
    }

    public boolean needsSync(String serverName, long remoteTimestamp) {
        long localTs = getRemoteTimestamp(serverName);
        return localTs != remoteTimestamp;
    }

    public void clearCache(String serverName) {
        clearCache(serverName, InventoryScope.server());
    }

    public void clearCache(String serverName, InventoryScope scope) {
        RemoteScopeKey key = RemoteScopeKey.of(serverName, scope);
        lock.lock();
        try {
            caches.remove(key);
        } finally {
            lock.unlock();
        }
        removeAsync(key);
    }

    public void cleanupExpired() {
        List<RemoteScopeKey> staleKeys = new ArrayList<>();
        lock.lock();
        try {
            for (Map.Entry<RemoteScopeKey, CachedInventory> entry : caches.entrySet()) {
                if (entry.getValue().isStale(CACHE_EXPIRY_MS)) {
                    staleKeys.add(entry.getKey());
                }
            }
            for (RemoteScopeKey key : staleKeys) {
                caches.remove(key);
            }
        } finally {
            lock.unlock();
        }
        cleanupExpiredAsync();
    }

    public void shutdown() {
        closed = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    private CachedInventory getCached(RemoteScopeKey key) {
        lock.lock();
        try {
            CachedInventory cache = caches.get(key);
            return cache != null ? cache.copy() : null;
        } finally {
            lock.unlock();
        }
    }

    private void putCached(RemoteScopeKey key, CachedInventory cache) {
        List<Map.Entry<RemoteScopeKey, CachedInventory>> evicted = new ArrayList<>();
        lock.lock();
        try {
            caches.put(key, cache.copy());
            while (caches.size() > capacity) {
                RemoteScopeKey eldest = caches.keySet().iterator().next();
                CachedInventory removed = caches.remove(eldest);
                if (removed != null) {
                    evicted.add(Map.entry(eldest, removed.copy()));
                }
            }
        } finally {
            lock.unlock();
        }
        for (Map.Entry<RemoteScopeKey, CachedInventory> entry : evicted) {
            persistAsync(entry.getKey(), entry.getValue());
        }
    }

    private void persistAsync(RemoteScopeKey key, CachedInventory cache) {
        if (closed) {
            persist(key, cache);
            return;
        }
        try {
            writer.execute(() -> persist(key, cache));
        } catch (RejectedExecutionException e) {
            persist(key, cache);
        }
    }

    private void persist(RemoteScopeKey key, CachedInventory cache) {
        cacheStore.putCache(key.serverName(), key.scope(), cache.getItems(), cache.getRemoteTimestamp());
    }

    private void removeAsync(RemoteScopeKey key) {
        if (closed) {
            remove(key);
            return;
        }
        try {
            writer.execute(() -> remove(key));
        } catch (RejectedExecutionException e) {
            remove(key);
        }
    }

    private void cleanupExpiredAsync() {
        if (closed) {
            cacheStore.cleanupExpired(CACHE_EXPIRY_MS);
            return;
        }
        try {
            writer.execute(() -> cacheStore.cleanupExpired(CACHE_EXPIRY_MS));
        } catch (RejectedExecutionException e) {
            cacheStore.cleanupExpired(CACHE_EXPIRY_MS);
        }
    }

    private void remove(RemoteScopeKey key) {
        cacheStore.removeCache(key.serverName(), key.scope());
    }

    private static List<NeutralItem> copyItems(List<NeutralItem> source) {
        List<NeutralItem> copy = new ArrayList<>();
        if (source == null) return copy;
        for (NeutralItem item : source) {
            copy.add(copyOf(item));
        }
        return copy;
    }

    private static NeutralItem copyOf(NeutralItem item) {
        return item == null ? null : item.copy();
    }

    private record RemoteScopeKey(String serverName, InventoryScope scope) {
        private static RemoteScopeKey of(String serverName, InventoryScope scope) {
            return new RemoteScopeKey(serverName, scope != null ? scope : InventoryScope.server());
        }
    }
}
