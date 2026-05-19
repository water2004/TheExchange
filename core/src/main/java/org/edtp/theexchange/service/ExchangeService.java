package org.edtp.theexchange.service;

import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.OperationType;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.storage.LocalItemStore;
import org.edtp.theexchange.storage.OperationLogger;

import java.util.UUID;
import java.util.List;

/**
 * Core business logic for item exchange operations.
 * Implements F-31 through F-40 concurrency and consistency requirements.
 */
public class ExchangeService {

    private static final long REQUEST_TIMEOUT_MS = 5000;

    private final NetworkManager networkManager;
    private final LocalItemStore localItemStore;
    private final OperationLogger operationLogger;
    private final CacheManager cacheManager;
    private final CompatibilityChecker compatibilityChecker;
    private final ItemSerializer itemSerializer;

    public ExchangeService(NetworkManager networkManager, LocalItemStore localItemStore,
                           OperationLogger operationLogger, CacheManager cacheManager,
                           CompatibilityChecker compatibilityChecker, ItemSerializer itemSerializer) {
        this.networkManager = networkManager;
        this.localItemStore = localItemStore;
        this.operationLogger = operationLogger;
        this.cacheManager = cacheManager;
        this.compatibilityChecker = compatibilityChecker;
        this.itemSerializer = itemSerializer;
    }

    public PutResult putItem(String serverName, int slot, String playerUuid,
                              String playerName, Object itemStack) {
        NeutralItem item = itemSerializer.serialize(itemStack);
        return putNeutralItem(serverName, slot, playerUuid, playerName, item);
    }

    public PutResult putNeutralItem(String serverName, int slot, String playerUuid,
                                    String playerName, NeutralItem item) {
        if (networkManager == null) return PutResult.fail("网络功能未启用，请检查端口配置");
        Connection conn = networkManager.getConnection(serverName);
        if (conn == null) return PutResult.fail("目标服务器离线");

        if (item == null || item.isEmpty()) return PutResult.fail("物品为空");
        int expectedVersion = 0;
        var cache = cacheManager.getCache(serverName);
        if (cache != null) {
            NeutralItem cached = cache.getItem(slot);
            expectedVersion = cached != null && !cached.isEmpty() ? cached.getVersion() : 0;
        }

        String requestId = UUID.randomUUID().toString();

        PutItemRequest request = new PutItemRequest(slot, item, expectedVersion,
                requestId, playerUuid, playerName);
        PutItemResponse response = conn.sendAndWait(
                FrameType.PUT_ITEM, request, FrameType.PUT_ITEM_RESPONSE, REQUEST_TIMEOUT_MS);

        if (response == null) {
            operationLogger.log(requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), false, "TIMEOUT");
            return PutResult.fail("请求超时，物品已退回");
        }

        if (response.isSuccess()) {
            operationLogger.log(requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), true, null);
            cacheManager.updateCacheSlot(serverName, slot, response.getCurrentItem(),
                    response.getNewTimestamp());
            refreshOpenViews(serverName);
            return PutResult.success(response.getCurrentItem());
        } else {
            operationLogger.log(requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), false, response.getFailReason());
            return PutResult.fail(response.getFailReason());
        }
    }

    public TakeResult takeItem(String serverName, int slot, int requestCount,
                               String playerUuid, String playerName) {
        var cache = cacheManager.getCache(serverName);
        NeutralItem expected = cache != null ? cache.getItem(slot) : null;
        if (expected == null || expected.isEmpty()) {
            return TakeResult.fail("物品已变化，请重试");
        }
        return takeItem(serverName, slot, expected.getItemId(), expected.getVersion(),
                requestCount, playerUuid, playerName);
    }

    public TakeResult takeItem(String serverName, int slot, String expectedItemId,
                                int expectedVersion, int requestCount,
                                String playerUuid, String playerName) {
        if (networkManager == null) return TakeResult.fail("网络功能未启用，请检查端口配置");
        Connection conn = networkManager.getConnection(serverName);
        if (conn == null) return TakeResult.fail("目标服务器离线");

        String requestId = UUID.randomUUID().toString();

        TakeItemRequest request = new TakeItemRequest(slot, expectedItemId,
                expectedVersion, requestCount, requestId, playerUuid, playerName);
        TakeItemResponse response = conn.sendAndWait(
                FrameType.TAKE_ITEM, request, FrameType.TAKE_ITEM_RESPONSE, REQUEST_TIMEOUT_MS);

        if (response == null) {
            operationLogger.log(requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, false, "TIMEOUT");
            return TakeResult.fail("请求超时");
        }

        if (response.isSuccess()) {
            operationLogger.log(requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, true, null);
            cacheManager.updateCacheSlot(serverName, slot, response.getCurrentItem(),
                    response.getNewTimestamp());
            refreshOpenViews(serverName);
            return TakeResult.success(response.getItemsToGive(), response.getNewVersion());
        } else {
            operationLogger.log(requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, false, response.getFailReason());
            return TakeResult.fail(response.getFailReason());
        }
    }

    public PutItemResponse handleRemotePut(PutItemRequest request) {
        OperationLogger.LogEntry existing = operationLogger.findByRequestId(request.getRequestId());
        if (existing != null) {
            LocalItemStore.ItemRecord r = localItemStore.getItem(request.getSlot());
            return new PutItemResponse(existing.success(), request.getSlot(),
                    r != null ? r.item() : null,
                    existing.failReason(), localItemStore.getLastModifiedTimestamp(),
                    r != null ? r.version() : 1);
        }

        try {
            NeutralItem item = request.getItem();
            compatibilityChecker.checkAndMark(item);

            LocalItemStore.PutResult result = localItemStore.putItem(
                    request.getSlot(), item, request.getExpectedVersion(), request.getPlayerUuid());

            if (result.isSuccess()) {
                operationLogger.log(request.getRequestId(), OperationType.PUT,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", item.getItemId(), item.getCount(), true, null);

                long timestamp = localItemStore.getLastModifiedTimestamp();
                LocalItemStore.ItemRecord updated = localItemStore.getItem(request.getSlot());

                return new PutItemResponse(true, request.getSlot(),
                        updated != null ? updated.item() : result.getItem(),
                        null, timestamp, result.getNewVersion());
            }

            operationLogger.log(request.getRequestId(), OperationType.PUT,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", item.getItemId(), item.getCount(), false, result.getFailReason());
            LocalItemStore.ItemRecord current = localItemStore.getItem(request.getSlot());
            return new PutItemResponse(false, request.getSlot(),
                    current != null ? current.item() : null,
                    result.getFailReason(), localItemStore.getLastModifiedTimestamp(),
                    current != null ? current.version() : 0);
        } catch (Exception e) {
            operationLogger.log(request.getRequestId(), OperationType.PUT,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", request.getItem().getItemId(), request.getItem().getCount(),
                    false, e.getMessage());
            return new PutItemResponse(false, request.getSlot(), null,
                    "INTERNAL_ERROR: " + e.getMessage(), 0, 0);
        }
    }

    public TakeItemResponse handleRemoteTake(TakeItemRequest request) {
        OperationLogger.LogEntry existing = operationLogger.findByRequestId(request.getRequestId());
        if (existing != null) {
            LocalItemStore.ItemRecord r = localItemStore.getItem(request.getSlot());
            return new TakeItemResponse(existing.success(), request.getSlot(),
                    r != null ? r.item() : null,
                    existing.failReason(), localItemStore.getLastModifiedTimestamp(),
                    r != null ? r.version() : 0, null);
        }

        try {
            LocalItemStore.TakeResult result = localItemStore.takeItem(
                    request.getSlot(), request.getExpectedItemId(),
                    request.getExpectedVersion(), request.getRequestCount());

            if (result.isSuccess()) {
                operationLogger.log(request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        true, null);

                long timestamp = localItemStore.getLastModifiedTimestamp();
                LocalItemStore.ItemRecord updated = localItemStore.getItem(request.getSlot());

                return new TakeItemResponse(true, request.getSlot(),
                        updated != null ? updated.item() : null,
                        null, timestamp, result.getNewVersion(), result.getItem());
            } else {
                operationLogger.log(request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, result.getFailReason());

                LocalItemStore.ItemRecord r = localItemStore.getItem(request.getSlot());
                return new TakeItemResponse(false, request.getSlot(),
                        r != null ? r.item() : null,
                        result.getFailReason(), localItemStore.getLastModifiedTimestamp(),
                        r != null ? r.version() : 0, null);
            }
        } catch (Exception e) {
            operationLogger.log(request.getRequestId(), OperationType.TAKE,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", request.getExpectedItemId(), request.getRequestCount(),
                    false, e.getMessage());
            return new TakeItemResponse(false, request.getSlot(), null,
                    "INTERNAL_ERROR: " + e.getMessage(), 0, 0, null);
        }
    }

    // ========== Message routing ==========

    public void routeMessage(org.edtp.theexchange.network.Connection conn,
                              FrameType type, Object message) {
        switch (type) {
            case QUERY_TIMESTAMP -> {
                QueryTimestampRequest req = (QueryTimestampRequest) message;
                long ts = localItemStore.getLastModifiedTimestamp();
                boolean changed = req.getCachedTimestamp() != ts;
                conn.send(FrameType.TIMESTAMP_RESPONSE,
                        new QueryTimestampResponse(ts, changed));
            }
            case QUERY_ITEMS -> {
                var items = localItemStore.getAllItems();
                conn.send(FrameType.ITEMS_RESPONSE,
                        new QueryItemsResponse(items, 54,
                                localItemStore.getLastModifiedTimestamp(), "26.1.2"));
            }
            case PUT_ITEM -> {
                PutItemResponse resp = handleRemotePut((PutItemRequest) message);
                conn.send(FrameType.PUT_ITEM_RESPONSE, resp);
                if (resp.isSuccess()) {
                    broadcastInventoryUpdate(conn, List.of(((PutItemRequest) message).getSlot()),
                            resp.getNewTimestamp());
                    refreshOpenViews(sourceServerName(conn));
                }
            }
            case TAKE_ITEM -> {
                TakeItemResponse resp = handleRemoteTake((TakeItemRequest) message);
                conn.send(FrameType.TAKE_ITEM_RESPONSE, resp);
                if (resp.isSuccess()) {
                    broadcastInventoryUpdate(conn, List.of(((TakeItemRequest) message).getSlot()),
                            resp.getNewTimestamp());
                    refreshOpenViews(sourceServerName(conn));
                }
            }
            case PUSH_UPDATE -> {
                PushUpdate update = (PushUpdate) message;
                if (update == null) return;
                final String sourceServerName = conn.getPeerServerName() != null
                        ? conn.getPeerServerName()
                        : conn.getRemoteName();
                cacheManager.clearCache(sourceServerName);
                TheExchangeCore core = TheExchangeCore.getInstance();
                if (core != null && core.getApi() != null && core.getSyncEngine() != null) {
                    core.getApi().runAsync(() -> {
                        try {
                            core.getSyncEngine().fullSync(sourceServerName);
                            core.getApi().runOnMainThread(() ->
                                    core.getApi().refreshRemoteInventoryView(sourceServerName));
                        } catch (Exception e) {
                            core.getApi().getLogger().warn("Push sync failed for " + sourceServerName
                                    + ": " + e.getMessage());
                        }
                    });
                }
            }
            default -> {}
        }
    }

    private void refreshOpenViews(String serverName) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core != null && core.getApi() != null) {
            core.getApi().refreshRemoteInventoryView(serverName);
        }
    }

    private String sourceServerName(Connection conn) {
        return conn.getPeerServerName() != null ? conn.getPeerServerName() : conn.getRemoteName();
    }

    private void broadcastInventoryUpdate(Connection sourceConn, List<Integer> changedSlots, long timestamp) {
        if (networkManager == null || changedSlots == null || changedSlots.isEmpty()) return;
        PushUpdate update = new PushUpdate(changedSlots, timestamp);
        networkManager.broadcast(FrameType.PUSH_UPDATE, update, sourceConn);
    }

    public void publishLocalInventoryUpdate(List<Integer> changedSlots) {
        long timestamp = localItemStore.getLastModifiedTimestamp();
        broadcastInventoryUpdate(null, changedSlots, timestamp);
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core != null && core.getApi() != null) {
            String localName = core.getApi().getServerName();
            core.getApi().runOnMainThread(() -> core.getApi().refreshRemoteInventoryView(localName));
        }
    }

    // ========== Result types ==========

    public static class PutResult {
        private final boolean success;
        private final String failReason;
        private final NeutralItem currentItem;

        private PutResult(boolean success, String failReason, NeutralItem currentItem) {
            this.success = success;
            this.failReason = failReason;
            this.currentItem = currentItem;
        }

        public static PutResult success(NeutralItem currentItem) {
            return new PutResult(true, null, currentItem);
        }

        public static PutResult fail(String reason) {
            return new PutResult(false, reason, null);
        }

        public boolean isSuccess() { return success; }
        public String getFailReason() { return failReason; }
        public NeutralItem getCurrentItem() { return currentItem; }
    }

    public static class TakeResult {
        private final boolean success;
        private final String failReason;
        private final NeutralItem itemsToGive;
        private final int newVersion;

        private TakeResult(boolean success, String failReason, NeutralItem itemsToGive, int newVersion) {
            this.success = success;
            this.failReason = failReason;
            this.itemsToGive = itemsToGive;
            this.newVersion = newVersion;
        }

        public static TakeResult success(NeutralItem itemsToGive, int newVersion) {
            return new TakeResult(true, null, itemsToGive, newVersion);
        }

        public static TakeResult fail(String reason) {
            return new TakeResult(false, reason, null, -1);
        }

        public boolean isSuccess() { return success; }
        public String getFailReason() { return failReason; }
        public NeutralItem getItemsToGive() { return itemsToGive; }
        public int getNewVersion() { return newVersion; }
    }
}
