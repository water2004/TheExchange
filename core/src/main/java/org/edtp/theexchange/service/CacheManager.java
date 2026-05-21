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
    private final LinkedHashMap<RemoteScopeKey, CachedInventory> caches = new LinkedHashMap<>(16, 0.75f, true);
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
        CachedInventory cache = getOrLoad(key);
        return cache == null ? null : cache;
    }

    public NeutralItem getSlot(String serverName, InventoryScope scope, int slot) {
        CachedInventory cache = getOrLoad(RemoteScopeKey.of(serverName, scope));
        return cache != null ? cache.getItem(slot) : null;
    }

    public int getSlotVersion(String serverName, InventoryScope scope, int slot) {
        CachedInventory cache = getOrLoad(RemoteScopeKey.of(serverName, scope));
        return cache != null ? cache.getVersion(slot) : 0;
    }

    public List<Integer> getVersions(String serverName, InventoryScope scope) {
        CachedInventory cache = getOrLoad(RemoteScopeKey.of(serverName, scope));
        return cache != null ? cache.versions() : List.of();
    }

    public List<Integer> changedSlots(String serverName, InventoryScope scope, List<Integer> remoteVersions) {
        CachedInventory cache = getOrLoad(RemoteScopeKey.of(serverName, scope));
        List<Integer> versions = remoteVersions != null ? remoteVersions : List.of();
        if (cache != null) {
            return cache.changedSlots(versions);
        }
        List<Integer> changed = new ArrayList<>(versions.size());
        for (int slot = 0; slot < versions.size(); slot++) {
            if (versions.get(slot) != null && versions.get(slot) != 0) {
                changed.add(slot);
            }
        }
        return changed;
    }

    public void updateCacheSlots(String serverName, InventoryScope scope,
                                 List<org.edtp.theexchange.network.protocol.messages.SlotStateResponse> slots) {
        if (slots == null || slots.isEmpty()) {
            return;
        }
        for (org.edtp.theexchange.network.protocol.messages.SlotStateResponse slot : slots) {
            if (slot == null) continue;
            updateCacheSlot(serverName, scope, slot.getSlot(), slot.getItem(), slot.getVersion());
        }
    }

    public void loadScope(String serverName, InventoryScope scope) {
        RemoteScopeKey key = RemoteScopeKey.of(serverName, scope);
        lock.lock();
        try {
            if (caches.containsKey(key)) {
                return;
            }
        } finally {
            lock.unlock();
        }
        List<RemoteCacheStore.RemoteSlotSnapshot> snapshots = cacheStore.loadScope(serverName, scope);
        CachedInventory cache = new CachedInventory(scope);
        cache.markLoaded(convertSnapshots(snapshots), System.currentTimeMillis());
        putCached(key, cache);
    }

    public void updateCacheSlot(String serverName, int slot, NeutralItem item, int version) {
        updateCacheSlot(serverName, InventoryScope.server(), slot, item, version);
    }

    public void updateCacheSlot(String serverName, InventoryScope scope, int slot, NeutralItem item, int version) {
        RemoteScopeKey key = RemoteScopeKey.of(serverName, scope);
        CachedInventory cache = getOrLoad(key);
        if (cache == null) {
            cache = new CachedInventory(scope);
            putCached(key, cache);
        }
        cache.replaceSlot(slot, item, version);
        persistSlotAsync(key, slot, item, version);
    }

    public void removeCacheSlot(String serverName, InventoryScope scope, int slot) {
        RemoteScopeKey key = RemoteScopeKey.of(serverName, scope);
        CachedInventory cache = getOrLoad(key);
        if (cache != null) {
            cache.removeSlot(slot);
            persistSlotAsync(key, slot, null, cache.getVersion(slot));
            return;
        }
        persistSlotAsync(key, slot, null, 0);
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
    }

    public void cleanupExpired() {
        List<RemoteScopeKey> stale = new ArrayList<>();
        lock.lock();
        try {
            for (Map.Entry<RemoteScopeKey, CachedInventory> entry : caches.entrySet()) {
                if (System.currentTimeMillis() - entry.getValue().getLastAccessAt() > CACHE_EXPIRY_MS) {
                    stale.add(entry.getKey());
                }
            }
            for (RemoteScopeKey key : stale) {
                caches.remove(key);
            }
        } finally {
            lock.unlock();
        }
    }

    public void flushAll() {
        List<Map.Entry<RemoteScopeKey, CachedInventory>> entries;
        lock.lock();
        try {
            entries = new ArrayList<>(caches.entrySet());
        } finally {
            lock.unlock();
        }
        for (Map.Entry<RemoteScopeKey, CachedInventory> entry : entries) {
            flush(entry.getKey(), entry.getValue());
        }
    }

    public void shutdown() {
        closed = true;
        flushAll();
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

    private CachedInventory getOrLoad(RemoteScopeKey key) {
        CachedInventory cache = getCached(key);
        if (cache != null) {
            return cache;
        }
        List<RemoteCacheStore.RemoteSlotSnapshot> snapshots = cacheStore.loadScope(key.serverName(), key.scope());
        if (snapshots == null) {
            return null;
        }
        CachedInventory loaded = new CachedInventory(key.scope());
        loaded.markLoaded(convertSnapshots(snapshots), System.currentTimeMillis());
        List<Map.Entry<RemoteScopeKey, CachedInventory>> evicted;
        lock.lock();
        try {
            CachedInventory existing = caches.get(key);
            if (existing != null) {
                return existing;
            }
            caches.put(key, loaded);
            evicted = evictIfNeededLocked();
        } finally {
            lock.unlock();
        }
        for (Map.Entry<RemoteScopeKey, CachedInventory> entry : evicted) {
            flush(entry.getKey(), entry.getValue());
        }
        return loaded;
    }

    private CachedInventory getCached(RemoteScopeKey key) {
        lock.lock();
        try {
            return caches.get(key);
        } finally {
            lock.unlock();
        }
    }

    private void putCached(RemoteScopeKey key, CachedInventory cache) {
        List<Map.Entry<RemoteScopeKey, CachedInventory>> evicted = new ArrayList<>();
        lock.lock();
        try {
            caches.put(key, cache);
            evicted.addAll(evictIfNeededLocked());
        } finally {
            lock.unlock();
        }
        for (Map.Entry<RemoteScopeKey, CachedInventory> entry : evicted) {
            flush(entry.getKey(), entry.getValue());
        }
    }

    private void persistSlotAsync(RemoteScopeKey key, int slot, NeutralItem item, int version) {
        if (closed) {
            persistSlot(key, slot, item, version);
            return;
        }
        try {
            writer.execute(() -> persistSlot(key, slot, item, version));
        } catch (RejectedExecutionException e) {
            persistSlot(key, slot, item, version);
        }
    }

    private void persistSlot(RemoteScopeKey key, int slot, NeutralItem item, int version) {
        cacheStore.saveSlot(key.serverName(), key.scope(), slot, item, version);
    }

    private void flush(RemoteScopeKey key, CachedInventory cache) {
        if (cache == null) return;
        for (CachedInventory.SlotSnapshot snapshot : cache.snapshotSlots()) {
            persistSlot(key, snapshot.slot(), snapshot.item(), snapshot.version());
        }
    }

    private List<CachedInventory.SlotSnapshot> convertSnapshots(List<RemoteCacheStore.RemoteSlotSnapshot> snapshots) {
        List<CachedInventory.SlotSnapshot> converted = new ArrayList<>();
        if (snapshots == null) {
            return converted;
        }
        for (RemoteCacheStore.RemoteSlotSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            converted.add(new CachedInventory.SlotSnapshot(snapshot.slot(), snapshot.item(), snapshot.version()));
        }
        return converted;
    }

    private List<Map.Entry<RemoteScopeKey, CachedInventory>> evictIfNeededLocked() {
        List<Map.Entry<RemoteScopeKey, CachedInventory>> evicted = new ArrayList<>();
        while (caches.size() > capacity) {
            RemoteScopeKey eldest = caches.keySet().iterator().next();
            CachedInventory removed = caches.remove(eldest);
            if (removed != null) {
                evicted.add(Map.entry(eldest, removed));
            }
        }
        return evicted;
    }

    private record RemoteScopeKey(String serverName, InventoryScope scope) {
        private static RemoteScopeKey of(String serverName, InventoryScope scope) {
            return new RemoteScopeKey(serverName, scope != null ? scope : InventoryScope.server());
        }
    }
}
