package org.edtp.theexchange.service;

import org.edtp.theexchange.model.CachedInventory;
import org.edtp.theexchange.model.ExchangeViewState;
import org.edtp.theexchange.storage.LocalItemStore;

public class ViewService {

    private final SyncEngine syncEngine;
    private final CacheManager cacheManager;
    private final LocalItemStore localItemStore;

    public ViewService(SyncEngine syncEngine, CacheManager cacheManager,
                       LocalItemStore localItemStore) {
        this.syncEngine = syncEngine;
        this.cacheManager = cacheManager;
        this.localItemStore = localItemStore;
    }

    public ExchangeViewState openLocalView(String serverName) {
        return ExchangeViewState.local(serverName,
                localItemStore.getAllItems(),
                localItemStore.getLastModifiedTimestamp());
    }

    public ExchangeViewState openRemoteView(String serverName) {
        var result = syncEngine != null ? syncEngine.syncIfNeeded(serverName) : null;
        CachedInventory cache = cacheManager.getCache(serverName);
        if (result != null) {
            return ExchangeViewState.remote(serverName, result.isOnline(),
                    result.getItems(), result.getRemoteTimestamp());
        }
        return ExchangeViewState.remote(serverName, false,
                cache != null ? cache.getItems() : null,
                cache != null ? cache.getRemoteTimestamp() : 0);
    }

    public void refreshRemoteView(String serverName) {
        if (syncEngine != null) {
            syncEngine.syncIfNeeded(serverName);
        }
    }
}
