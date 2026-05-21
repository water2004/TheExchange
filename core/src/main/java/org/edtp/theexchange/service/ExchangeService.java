package org.edtp.theexchange.service;

import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.InventoryScope;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

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
    private final SyncEngine syncEngine;
    private final ConcurrentHashMap<Integer, ReentrantLock> localSlotLocks = new ConcurrentHashMap<>();

    public ExchangeService(NetworkManager networkManager, LocalItemStore localItemStore,
                           OperationLogger operationLogger, CacheManager cacheManager,
                           CompatibilityChecker compatibilityChecker, ItemSerializer itemSerializer,
                           SyncEngine syncEngine) {
        this.networkManager = networkManager;
        this.localItemStore = localItemStore;
        this.operationLogger = operationLogger;
        this.cacheManager = cacheManager;
        this.compatibilityChecker = compatibilityChecker;
        this.itemSerializer = itemSerializer;
        this.syncEngine = syncEngine;
    }

    public CompletableFuture<PutResult> putItemAsync(String serverName, int slot, String playerUuid,
                                                     String playerName, Object itemStack) {
        NeutralItem item = itemSerializer.serialize(itemStack);
        return putNeutralItemAsync(serverName, slot, playerUuid, playerName, item);
    }

    public int getMaxStackSize(NeutralItem item) {
        return itemSerializer.getMaxStackSize(item);
    }

    public CompletableFuture<PutResult> putNeutralItemAsync(String serverName, int slot,
                                                            String playerUuid, String playerName,
                                                            NeutralItem item) {
        if (networkManager == null) {
            return CompletableFuture.completedFuture(PutResult.fail("网络功能未启用，请检查端口配置"));
        }
        Connection conn = networkManager.getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(PutResult.fail("目标服务器离线"));
        }
        if (item == null || item.isEmpty()) {
            return CompletableFuture.completedFuture(PutResult.fail("物品为空"));
        }
        if (syncEngine == null) {
            return CompletableFuture.completedFuture(PutResult.fail("同步引擎未初始化"));
        }
        NeutralItem cached = cacheManager.getSlot(serverName, InventoryScope.server(), slot);
        if (cached != null && cached.isIncompatible()) {
            return CompletableFuture.completedFuture(PutResult.fail("不兼容物品禁止操作"));
        }
        int expectedVersion = cacheManager.getSlotVersion(serverName, InventoryScope.server(), slot);
        String requestId = UUID.randomUUID().toString();
        PutItemRequest request = new PutItemRequest(slot, item, expectedVersion,
                requestId, playerUuid, playerName, expectedVersion);
        return conn.<PutItemResponse>sendAsync(
                        FrameType.PUT_ITEM, request, FrameType.PUT_ITEM_RESPONSE, REQUEST_TIMEOUT_MS)
                .handle((response, error) -> {
                    TheExchangeCore core = TheExchangeCore.getInstance();
                    if (core != null) {
                        return core.submit(() -> finishRemotePut(serverName, slot, playerUuid,
                                playerName, item, requestId, response, error));
                    }
                    return CompletableFuture.completedFuture(PutResult.fail("核心已停止"));
                })
                .thenCompose(future -> future);
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot, int requestCount,
                                                       String playerUuid, String playerName) {
        if (syncEngine == null) {
            return CompletableFuture.completedFuture(TakeResult.fail("同步引擎未初始化"));
        }
        NeutralItem expected = cacheManager.getSlot(serverName, InventoryScope.server(), slot);
        if (expected == null || expected.isEmpty()) {
            return CompletableFuture.completedFuture(TakeResult.fail("物品已变化，请重试"));
        }
        if (expected.isIncompatible()) {
            return CompletableFuture.completedFuture(TakeResult.fail("不兼容物品禁止操作"));
        }
        int expectedVersion = cacheManager.getSlotVersion(serverName, InventoryScope.server(), slot);
        return takeItemAsync(serverName, slot, expected.getItemId(),
                expectedVersion, requestCount, playerUuid, playerName, expectedVersion);
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot,
                                                       String expectedItemId, int expectedVersion,
                                                       int requestCount,
                                                       String playerUuid, String playerName) {
        return takeItemAsync(serverName, slot, expectedItemId, expectedVersion, requestCount, playerUuid, playerName, expectedVersion);
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot,
                                                       String expectedItemId, int expectedVersion,
                                                       int requestCount,
                                                       String playerUuid, String playerName,
                                                       int remoteVersion) {
        if (networkManager == null) {
            return CompletableFuture.completedFuture(TakeResult.fail("网络功能未启用，请检查端口配置"));
        }
        Connection conn = networkManager.getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(TakeResult.fail("目标服务器离线"));
        }

        String requestId = UUID.randomUUID().toString();
        TakeItemRequest request = new TakeItemRequest(slot, expectedItemId,
                expectedVersion, requestCount, requestId, playerUuid, playerName, remoteVersion);

        return conn.<TakeItemResponse>sendAsync(
                        FrameType.TAKE_ITEM, request, FrameType.TAKE_ITEM_RESPONSE, REQUEST_TIMEOUT_MS)
                .handle((response, error) -> {
                    TheExchangeCore core = TheExchangeCore.getInstance();
                    if (core != null) {
                        return core.submit(() -> finishRemoteTake(serverName, slot,
                                expectedItemId, requestCount, playerUuid, playerName,
                                requestId, response, error));
                    }
                    return CompletableFuture.completedFuture(TakeResult.fail("核心已停止"));
                })
                .thenCompose(future -> future);
    }

    private PutResult finishRemotePut(String serverName, int slot, String playerUuid,
                                      String playerName, NeutralItem item, String requestId,
                                      PutItemResponse response, Throwable error) {
        if (error != null || response == null) {
            operationLogger.log(requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), false,
                    error != null ? error.getMessage() : "TIMEOUT");
            return PutResult.fail("请求超时，物品已退回");
        }

        if (response.isSuccess()) {
            operationLogger.log(requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), true, null);
            cacheManager.updateCacheSlot(serverName, InventoryScope.server(), slot, response.getCurrentItem(),
                    response.getNewVersion());
            redrawOpenViews(serverName);
            return PutResult.success(response.getCurrentItem());
        }

        operationLogger.log(requestId, OperationType.PUT, playerUuid, playerName,
                serverName, item.getItemId(), item.getCount(), false, response.getFailReason());
        cacheManager.updateCacheSlot(serverName, InventoryScope.server(), slot, response.getCurrentItem(),
                response.getNewVersion());
        redrawOpenViews(serverName);
        return PutResult.fail(response.getFailReason());
    }

    private TakeResult finishRemoteTake(String serverName, int slot, String expectedItemId,
                                        int requestCount, String playerUuid, String playerName,
                                        String requestId, TakeItemResponse response,
                                        Throwable error) {
        if (error != null || response == null) {
            operationLogger.log(requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, false,
                    error != null ? error.getMessage() : "TIMEOUT");
            return TakeResult.fail("请求超时");
        }

        if (response.isSuccess()) {
            if (response.getItemsToGive() == null || response.getItemsToGive().isEmpty()
                    || response.getItemsToGive().isIncompatible()) {
                operationLogger.log(requestId, OperationType.TAKE, playerUuid, playerName,
                        serverName, expectedItemId, requestCount, false, "INCOMPATIBLE");
                debugTake("rejectResponse", serverName, slot, expectedItemId, requestCount,
                        requestId, response, null, null);
                return TakeResult.fail("不兼容物品禁止操作");
            }
            operationLogger.log(requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, true, null);
            cacheManager.updateCacheSlot(serverName, InventoryScope.server(), slot, response.getCurrentItem(),
                    response.getNewVersion());
            redrawOpenViews(serverName);
            return TakeResult.success(response.getItemsToGive(), response.getNewVersion());
        }

        operationLogger.log(requestId, OperationType.TAKE, playerUuid, playerName,
                serverName, expectedItemId, requestCount, false, response.getFailReason());
        cacheManager.updateCacheSlot(serverName, InventoryScope.server(), slot, response.getCurrentItem(),
                response.getNewVersion());
        redrawOpenViews(serverName);
        return TakeResult.fail(response.getFailReason());
    }

    public PutItemResponse handleRemotePut(PutItemRequest request) {
        ReentrantLock slotLock = localSlotLock(request.getSlot());
        slotLock.lock();
        try {
            return handleRemotePutLocked(request);
        } finally {
            slotLock.unlock();
        }
    }

    private PutItemResponse handleRemotePutLocked(PutItemRequest request) {
        OperationLogger.LogEntry existing = operationLogger.findByRequestId(request.getRequestId());
        if (existing != null) {
            LocalItemStore.ItemRecord r = localItemStore.getItem(request.getSlot());
            return new PutItemResponse(existing.success(), request.getSlot(),
                    r != null ? r.item() : null,
                    existing.failReason(), localItemStore.getLastModifiedTimestamp(),
                    r != null ? r.version() : 0,
                    r != null ? r.version() : 0,
                    request.getRequestId());
        }

        try {
            NeutralItem item = request.getItem();
            LocalItemStore.ItemRecord existingRecord = localItemStore.getItem(request.getSlot());
            debugPut("incoming", request, item, existingRecord, null, null);
            compatibilityChecker.checkAndMark(item);
            debugPut("afterCompat", request, item, existingRecord, null, null);

            LocalItemStore.PutResult result = localItemStore.putItem(
                    request.getSlot(), item, request.getExpectedVersion(), request.getPlayerUuid());
            LocalItemStore.ItemRecord after = localItemStore.getItem(request.getSlot());
            debugPut("storeResult", request, item, after, result, null);

            if (result.isSuccess()) {
                operationLogger.log(request.getRequestId(), OperationType.PUT,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", item.getItemId(), item.getCount(), true, null);

                long timestamp = localItemStore.getLastModifiedTimestamp();
                return new PutItemResponse(true, request.getSlot(),
                        after != null ? after.item() : result.getItem(),
                        null, timestamp,
                        after != null ? after.version() : result.getNewVersion(),
                        after != null ? after.version() : result.getNewVersion(),
                        request.getRequestId());
            }

            operationLogger.log(request.getRequestId(), OperationType.PUT,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", item.getItemId(), item.getCount(), false, result.getFailReason());
            LocalItemStore.ItemRecord current = localItemStore.getItem(request.getSlot());
            return new PutItemResponse(false, request.getSlot(),
                    current != null ? current.item() : null,
                    result.getFailReason(), localItemStore.getLastModifiedTimestamp(),
                    current != null ? current.version() : 0,
                    current != null ? current.version() : 0,
                    request.getRequestId());
        } catch (Exception e) {
            debugPut("exception", request, request.getItem(), null, null, e);
            operationLogger.log(request.getRequestId(), OperationType.PUT,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", request.getItem().getItemId(), request.getItem().getCount(),
                    false, e.getMessage());
            return new PutItemResponse(false, request.getSlot(), null,
                    "INTERNAL_ERROR: " + e.getMessage(), 0, 0, 0, request.getRequestId());
        }
    }

    public TakeItemResponse handleRemoteTake(TakeItemRequest request) {
        ReentrantLock slotLock = localSlotLock(request.getSlot());
        slotLock.lock();
        try {
            return handleRemoteTakeLocked(request);
        } finally {
            slotLock.unlock();
        }
    }

    private TakeItemResponse handleRemoteTakeLocked(TakeItemRequest request) {
        OperationLogger.LogEntry existing = operationLogger.findByRequestId(request.getRequestId());
        if (existing != null) {
            LocalItemStore.ItemRecord r = localItemStore.getItem(request.getSlot());
                return new TakeItemResponse(existing.success(), request.getSlot(),
                        r != null ? r.item() : null,
                        existing.failReason(), localItemStore.getLastModifiedTimestamp(),
                        r != null ? r.version() : 0,
                        r != null ? r.version() : 0, null, request.getRequestId());
        }

        try {
            LocalItemStore.ItemRecord before = localItemStore.getItem(request.getSlot());
            debugTake("incoming", "local", request.getSlot(), request.getExpectedItemId(),
                    request.getRequestCount(), request.getRequestId(), null, before, null);
            if (before == null || before.item() == null || before.item().isEmpty()) {
                operationLogger.log(request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, "ITEM_NOT_FOUND");
                return new TakeItemResponse(false, request.getSlot(), null,
                        "ITEM_NOT_FOUND", localItemStore.getLastModifiedTimestamp(),
                        before != null ? before.version() : 0,
                        before != null ? before.version() : 0, null, request.getRequestId());
            }
            if (before.item().isIncompatible()) {
                debugTake("rejectIncompatible", "local", request.getSlot(), request.getExpectedItemId(),
                        request.getRequestCount(), request.getRequestId(), null, before, null);
                operationLogger.log(request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, "INCOMPATIBLE");
                return new TakeItemResponse(false, request.getSlot(), before.item(),
                        "INCOMPATIBLE", localItemStore.getLastModifiedTimestamp(), before.version(), before.version(), null,
                        request.getRequestId());
            }
            LocalItemStore.TakeResult result = localItemStore.takeItem(
                    request.getSlot(), request.getExpectedItemId(),
                    request.getExpectedVersion(), request.getRequestCount());
            debugTake("storeResult", "local", request.getSlot(), request.getExpectedItemId(),
                    request.getRequestCount(), request.getRequestId(), null, before, result);

            if (result.isSuccess()) {
                operationLogger.log(request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        true, null);

                long timestamp = localItemStore.getLastModifiedTimestamp();
                LocalItemStore.ItemRecord updated = localItemStore.getItem(request.getSlot());

                return new TakeItemResponse(true, request.getSlot(),
                        updated != null ? updated.item() : null,
                        null, timestamp,
                        updated != null ? updated.version() : result.getNewVersion(),
                        updated != null ? updated.version() : result.getNewVersion(),
                        result.getItem(), request.getRequestId());
            } else {
                operationLogger.log(request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, result.getFailReason());

                LocalItemStore.ItemRecord r = localItemStore.getItem(request.getSlot());
                return new TakeItemResponse(false, request.getSlot(),
                        r != null ? r.item() : null,
                        result.getFailReason(), localItemStore.getLastModifiedTimestamp(),
                        r != null ? r.version() : 0,
                        r != null ? r.version() : 0, null, request.getRequestId());
            }
        } catch (Exception e) {
            operationLogger.log(request.getRequestId(), OperationType.TAKE,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", request.getExpectedItemId(), request.getRequestCount(),
                    false, e.getMessage());
            return new TakeItemResponse(false, request.getSlot(), null,
                    "INTERNAL_ERROR: " + e.getMessage(), 0, 0, 0, null, request.getRequestId());
        }
    }

    private void debugTake(String stage, String serverName, int slot, String expectedItemId,
                           int requestCount, String requestId, TakeItemResponse response,
                           LocalItemStore.ItemRecord existing, LocalItemStore.TakeResult result) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || core.getApi() == null || core.getApi().getLogger() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Exchange|Debug][TAKE][").append(stage).append("] ")
                .append("server=").append(serverName)
                .append(" slot=").append(slot)
                .append(" expectedItem=").append(expectedItemId)
                .append(" count=").append(requestCount)
                .append(" reqId=").append(requestId);
        if (existing != null) {
            sb.append(" existing=").append(describeRecord(existing));
        }
        if (response != null) {
            sb.append(" responseSuccess=").append(response.isSuccess())
                    .append(" failReason=").append(response.getFailReason())
                    .append(" current=").append(describeItem(response.getCurrentItem()))
                    .append(" give=").append(describeItem(response.getItemsToGive()))
                    .append(" newVersion=").append(response.getNewVersion());
        }
        if (result != null) {
            sb.append(" resultSuccess=").append(result.isSuccess())
                    .append(" failReason=").append(result.getFailReason())
                    .append(" taken=").append(describeItem(result.getItem()))
                    .append(" newVersion=").append(result.getNewVersion());
        }
        core.getApi().getLogger().info(sb.toString());
    }

    private void debugPut(String stage, PutItemRequest request, NeutralItem item,
                          LocalItemStore.ItemRecord existing,
                          LocalItemStore.PutResult result, Throwable error) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || core.getApi() == null || core.getApi().getLogger() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Exchange|Debug][PUT][").append(stage).append("] ")
                .append("slot=").append(request.getSlot())
                .append(" expectedVersion=").append(request.getExpectedVersion())
                .append(" reqId=").append(request.getRequestId())
                .append(" player=").append(request.getPlayerName())
                .append(" item=").append(describeItem(item));
        if (existing != null) {
            sb.append(" existing=").append(describeRecord(existing));
            sb.append(" sameKind=").append(existing.item() != null && item != null
                    && existing.item().sameStackKind(item));
        }
        if (result != null) {
            sb.append(" resultSuccess=").append(result.isSuccess())
                    .append(" failReason=").append(result.getFailReason())
                    .append(" newVersion=").append(result.getNewVersion());
        }
        if (error != null) {
            sb.append(" error=").append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage());
        }
        core.getApi().getLogger().info(sb.toString());
    }

    private String describeRecord(LocalItemStore.ItemRecord record) {
        if (record == null) return "null";
        return "slot=" + record.slot()
                + ",version=" + record.version()
                + ",item=" + describeItem(record.item());
    }

    private String describeItem(NeutralItem item) {
        if (item == null) return "null";
        byte[] extra = item.getExtraData();
        return "{id=" + item.getItemId()
                + ",count=" + item.getCount()
                + ",incompatible=" + item.isIncompatible()
                + ",extraLen=" + (extra == null ? -1 : extra.length)
                + ",extraHash=" + java.util.Arrays.hashCode(extra)
                + ",source=" + item.getSourceVersion()
                + ",version=" + item.getVersion()
                + "}";
    }

    // ========== Message routing ==========

    public void routeMessage(org.edtp.theexchange.network.Connection conn,
                              FrameType type, Object message) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        switch (type) {
            case QUERY_TIMESTAMP, QUERY_ITEMS -> {}
            case QUERY_SLOT_VERSION -> {
                QuerySlotVersionRequest req = (QuerySlotVersionRequest) message;
                LocalItemStore.ItemRecord record = localItemStore.getItem(req.getSlot());
                int version = record != null ? record.version() : 0;
                conn.send(FrameType.SLOT_VERSION_RESPONSE,
                        new QuerySlotVersionResponse(req.getRequestId(), req.getSlot(), version));
            }
            case QUERY_SLOT_STATE -> {
                QuerySlotStateRequest req = (QuerySlotStateRequest) message;
                LocalItemStore.ItemRecord record = localItemStore.getItem(req.getSlot());
                conn.send(FrameType.SLOT_STATE_RESPONSE,
                        new SlotStateResponse(req.getRequestId(), req.getSlot(),
                                record != null ? record.item() : null,
                                record != null ? record.version() : 0));
            }
            case QUERY_SLOT_VERSIONS -> {
                QuerySlotVersionsRequest req = (QuerySlotVersionsRequest) message;
                conn.send(FrameType.SLOT_VERSIONS_RESPONSE,
                        new SlotVersionsResponse(req.getRequestId(), localSlotVersions()));
            }
            case QUERY_SLOTS -> {
                QuerySlotsRequest req = (QuerySlotsRequest) message;
                java.util.ArrayList<SlotStateResponse> slots = new java.util.ArrayList<>();
                if (req.getSlots() != null) {
                    for (int slot : req.getSlots()) {
                        LocalItemStore.ItemRecord record = localItemStore.getItem(slot);
                        slots.add(new SlotStateResponse(req.getRequestId(), slot,
                                record != null ? record.item() : null,
                                record != null ? record.version() : 0));
                    }
                }
                conn.send(FrameType.SLOTS_STATE_RESPONSE, new SlotsStateResponse(req.getRequestId(), slots));
            }
            case PUT_ITEM -> {
                PutItemResponse resp = handleRemotePut((PutItemRequest) message);
                conn.send(FrameType.PUT_ITEM_RESPONSE, resp);
                if (resp.isSuccess()) {
                    broadcastInventoryUpdate(conn, List.of(((PutItemRequest) message).getSlot()),
                            localItemStore.getLastModifiedTimestamp());
                    refreshOpenViews(sourceServerName(conn));
                }
            }
            case TAKE_ITEM -> {
                TakeItemResponse resp = handleRemoteTake((TakeItemRequest) message);
                conn.send(FrameType.TAKE_ITEM_RESPONSE, resp);
                if (resp.isSuccess()) {
                    broadcastInventoryUpdate(conn, List.of(((TakeItemRequest) message).getSlot()),
                            localItemStore.getLastModifiedTimestamp());
                    refreshOpenViews(sourceServerName(conn));
                }
            }
            case PUSH_UPDATE -> {
                PushUpdate update = (PushUpdate) message;
                if (update == null) return;
                final String sourceServerName = conn.getPeerServerName() != null
                        ? conn.getPeerServerName()
                        : conn.getRemoteName();
                if (core != null && core.getSyncEngine() != null) {
                    core.getSyncEngine().querySlotsAsync(sourceServerName, update.getChangedSlots())
                            .whenComplete((ignored, error) -> {
                                if (core.getApi() != null) {
                                    core.getApi().runOnMainThread(() -> core.getApi().redrawRemoteInventoryView(sourceServerName));
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

    private void redrawOpenViews(String serverName) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core != null && core.getApi() != null) {
            core.getApi().redrawRemoteInventoryView(serverName);
        }
    }

    private String sourceServerName(Connection conn) {
        return conn.getPeerServerName() != null ? conn.getPeerServerName() : conn.getRemoteName();
    }

    private ReentrantLock localSlotLock(int slot) {
        return localSlotLocks.computeIfAbsent(slot, ignored -> new ReentrantLock());
    }

    private java.util.List<Integer> localSlotVersions() {
        java.util.ArrayList<Integer> versions = new java.util.ArrayList<>();
        List<NeutralItem> items = localItemStore.getAllItems();
        for (int slot = 0; slot < items.size(); slot++) {
            NeutralItem item = items.get(slot);
            versions.add(item != null ? item.getVersion() : 0);
        }
        return versions;
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
