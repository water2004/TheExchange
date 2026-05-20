package org.edtp.theexchange.service;

import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.QueryItemsRequest;
import org.edtp.theexchange.network.protocol.messages.QueryItemsResponse;
import org.edtp.theexchange.network.protocol.messages.QueryTimestampRequest;
import org.edtp.theexchange.network.protocol.messages.QueryTimestampResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SyncEngine {

    private static final long SYNC_TIMEOUT_MS = 5000;

    private final NetworkManager networkManager;
    private final CacheManager cacheManager;
    private final CompatibilityChecker compatibilityChecker;

    public SyncEngine(NetworkManager networkManager, CacheManager cacheManager,
                      CompatibilityChecker compatibilityChecker) {
        this.networkManager = networkManager;
        this.cacheManager = cacheManager;
        this.compatibilityChecker = compatibilityChecker;
    }

    public CompletableFuture<SyncResult> syncIfNeededAsync(String serverName) {
        if (networkManager == null) {
            return CompletableFuture.completedFuture(SyncResult.offline(cacheManager.getCache(serverName)));
        }
        Connection conn = networkManager.getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(SyncResult.offline(cacheManager.getCache(serverName)));
        }

        long cachedTs = cacheManager.getRemoteTimestamp(serverName);
        QueryTimestampRequest tsReq = new QueryTimestampRequest(cachedTs);
        return conn.<QueryTimestampResponse>sendAsync(
                        FrameType.QUERY_TIMESTAMP, tsReq,
                        FrameType.TIMESTAMP_RESPONSE, SYNC_TIMEOUT_MS)
                .handle((response, error) -> {
                    if (error != null || response == null) {
                        return CompletableFuture.completedFuture(SyncResult.timeout());
                    }
                    if (!response.isChanged()) {
                        TheExchangeCore core = TheExchangeCore.getInstance();
                        if (core != null) {
                            return core.submit(() -> SyncResult.fromCache(cacheManager.getCache(serverName)));
                        }
                        return CompletableFuture.completedFuture(SyncResult.fromCache(cacheManager.getCache(serverName)));
                    }
                    return fullSyncAsync(serverName, conn);
                })
                .thenCompose(future -> future);
    }

    public CompletableFuture<SyncResult> fullSyncAsync(String serverName) {
        if (networkManager == null) {
            return CompletableFuture.completedFuture(SyncResult.offline(cacheManager.getCache(serverName)));
        }
        Connection conn = networkManager.getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(SyncResult.offline(cacheManager.getCache(serverName)));
        }
        return fullSyncAsync(serverName, conn);
    }

    private CompletableFuture<SyncResult> fullSyncAsync(String serverName, Connection conn) {
        return conn.<QueryItemsResponse>sendAsync(
                        FrameType.QUERY_ITEMS, new QueryItemsRequest(0, 54),
                        FrameType.ITEMS_RESPONSE, SYNC_TIMEOUT_MS)
                .handle((response, error) -> {
                    TheExchangeCore core = TheExchangeCore.getInstance();
                    if (core != null) {
                        return core.submit(() -> finishFullSync(serverName, response, error));
                    }
                    return CompletableFuture.completedFuture(SyncResult.timeout());
                })
                .thenCompose(future -> future);
    }

    private SyncResult finishFullSync(String serverName, QueryItemsResponse resp, Throwable error) {
        if (error != null || resp == null) return SyncResult.timeout();
        var items = resp.getItems();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                if (item != null && item.getVersion() <= 0) {
                    item.setVersion(i + 1);
                }
                if (item != null && compatibilityChecker != null) {
                    compatibilityChecker.checkAndMark(item);
                }
            }
        }
        cacheManager.updateCache(serverName, items, resp.getTimestamp());
        return SyncResult.fromRemote(items, resp.getTimestamp(), resp.getServerVersion());
    }

    public static class SyncResult {
        private final boolean online;
        private final boolean timeout;
        private final List<NeutralItem> items;
        private final long remoteTimestamp;
        private final String serverVersion;

        private SyncResult(boolean online, boolean timeout, List<NeutralItem> items,
                           long remoteTimestamp, String serverVersion) {
            this.online = online;
            this.timeout = timeout;
            this.items = items;
            this.remoteTimestamp = remoteTimestamp;
            this.serverVersion = serverVersion;
        }

        public static SyncResult fromCache(org.edtp.theexchange.model.CachedInventory cache) {
            return new SyncResult(true, false,
                    cache != null ? cache.getItems() : null,
                    cache != null ? cache.getRemoteTimestamp() : 0,
                    null);
        }

        public static SyncResult fromRemote(List<NeutralItem> items, long ts, String version) {
            return new SyncResult(true, false, items, ts, version);
        }

        public static SyncResult offline(org.edtp.theexchange.model.CachedInventory cache) {
            return new SyncResult(false, false,
                    cache != null ? cache.getItems() : null,
                    cache != null ? cache.getRemoteTimestamp() : 0,
                    null);
        }

        public static SyncResult timeout() {
            return new SyncResult(false, true, null, 0, null);
        }

        public boolean isOnline() { return online; }
        public boolean isTimeout() { return timeout; }
        public List<NeutralItem> getItems() { return items; }
        public long getRemoteTimestamp() { return remoteTimestamp; }
        public String getServerVersion() { return serverVersion; }
    }
}
