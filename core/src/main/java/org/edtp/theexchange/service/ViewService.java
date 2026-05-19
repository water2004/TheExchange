package org.edtp.theexchange.service;

import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.CachedInventory;
import org.edtp.theexchange.model.ExchangeViewState;

public class ViewService {

    private final SyncEngine syncEngine;
    private final CacheManager cacheManager;

    public ViewService(SyncEngine syncEngine, CacheManager cacheManager) {
        this.syncEngine = syncEngine;
        this.cacheManager = cacheManager;
    }

    public ExchangeViewState openLocalView(String serverName) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null) {
            return ExchangeViewState.local(serverName, null, 0);
        }
        return ExchangeViewState.local(serverName,
                core.getLocalItemStore().getAllItems(),
                core.getLocalItemStore().getLastModifiedTimestamp());
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
