package org.edtp.theexchange.service;

import org.edtp.theexchange.model.CachedInventory;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.storage.RemoteCacheStore;

import java.util.List;

public class CacheManager {

    private final RemoteCacheStore cacheStore;
    private static final long CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000; // 24 hours

    public CacheManager(RemoteCacheStore cacheStore) {
        this.cacheStore = cacheStore;
    }

    public CachedInventory getCache(String serverName) {
        CachedInventory cache = cacheStore.getCache(serverName);
        if (cache == null) return null;
        if (cache.isStale(CACHE_EXPIRY_MS)) {
            cacheStore.removeCache(serverName);
            return null;
        }
        return cache;
    }

    public void updateCache(String serverName, List<NeutralItem> items, long remoteTimestamp) {
        cacheStore.putCache(serverName, items, remoteTimestamp);
    }

    public void updateCacheSlot(String serverName, int slot, NeutralItem item, long remoteTimestamp) {
        cacheStore.updateSlot(serverName, slot, item, remoteTimestamp);
    }

    public long getRemoteTimestamp(String serverName) {
        CachedInventory cache = cacheStore.getCache(serverName);
        return cache != null ? cache.getRemoteTimestamp() : 0;
    }

    public boolean needsSync(String serverName, long remoteTimestamp) {
        long localTs = getRemoteTimestamp(serverName);
        return localTs != remoteTimestamp;
    }

    public void clearCache(String serverName) {
        cacheStore.removeCache(serverName);
    }

    public void cleanupExpired() {
        cacheStore.cleanupExpired(CACHE_EXPIRY_MS);
    }
}
