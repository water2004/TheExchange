package org.edtp.theexchange.service;

import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.api.ExchangeAPI;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core business logic for item exchange operations.
 * Implements F-31 through F-40 concurrency and consistency requirements.
 */
public class ExchangeService {
    private static final long RECENT_OP_TTL_MS = 5 * 60 * 1000L;
    private static final long RECENT_OP_CLEANUP_INTERVAL_MS = 60 * 1000L;

    private final NetworkManager networkManager;
    private final LocalItemStore localItemStore;
    private final OperationLogger operationLogger;
    private final CacheManager cacheManager;
    private final CompatibilityChecker compatibilityChecker;
    private final ItemSerializer itemSerializer;
    private final SyncEngine syncEngine;
    private final RuntimeHooks runtimeHooks;
    private final long requestTimeoutMs;
    private final ConcurrentHashMap<Integer, ReentrantLock> localSlotLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletedOp> recentOps = new ConcurrentHashMap<>();
    private volatile long lastRecentOpCleanup;

    public ExchangeService(NetworkManager networkManager, LocalItemStore localItemStore,
                           OperationLogger operationLogger, CacheManager cacheManager,
                           CompatibilityChecker compatibilityChecker, ItemSerializer itemSerializer,
                           SyncEngine syncEngine, RuntimeHooks runtimeHooks, long requestTimeoutMs) {
        this.networkManager = networkManager;
        this.localItemStore = localItemStore;
        this.operationLogger = operationLogger;
        this.cacheManager = cacheManager;
        this.compatibilityChecker = compatibilityChecker;
        this.itemSerializer = itemSerializer;
        this.syncEngine = syncEngine;
        this.runtimeHooks = runtimeHooks;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public interface RuntimeHooks {
        long currentGeneration();
        <T> CompletableFuture<T> submitIfGeneration(long expectedGeneration, Callable<T> task);
        ExchangeAPI.Logger logger();
        void refreshRemoteInventoryView(String serverName);
        void redrawRemoteInventoryView(String serverName);
        void runOnMainThread(Runnable task);
        String localServerName();
    }

    private record CompletedOp(long completedAt, boolean success, String failReason,
                               NeutralItem currentItem, NeutralItem takenItem, int newVersion) {}

    public int getMaxStackSize(NeutralItem item) {
        return itemSerializer.getMaxStackSize(item);
    }

    public CompletableFuture<PutResult> putNeutralItemAsync(String serverName, int slot,
                                                            String playerUuid, String playerName,
                                                            NeutralItem item) {
        if (item == null || item.isEmpty()) {
            return CompletableFuture.completedFuture(PutResult.fail("物品为空"));
        }
        boolean localTarget = isLocalTarget(serverName);
        Connection conn = null;
        if (!localTarget) {
            if (networkManager == null) {
                return CompletableFuture.completedFuture(PutResult.fail("网络功能未启用，请检查端口配置"));
            }
            conn = networkManager.getConnection(serverName);
            if (conn == null) {
                return CompletableFuture.completedFuture(PutResult.fail("目标服务器离线"));
            }
            if (syncEngine == null) {
                return CompletableFuture.completedFuture(PutResult.fail("同步引擎未初始化"));
            }
            NeutralItem cached = cacheManager.getSlot(serverName, InventoryScope.server(), slot);
            if (cached != null && cached.isIncompatible()) {
                return CompletableFuture.completedFuture(PutResult.fail("不兼容物品禁止操作"));
            }
        }
        int expectedVersion = localTarget
                ? localSlotVersion(slot)
                : cacheManager.getSlotVersion(serverName, InventoryScope.server(), slot);
        String requestId = UUID.randomUUID().toString();
        PutItemRequest request = new PutItemRequest(slot, item, expectedVersion,
                requestId, playerUuid, playerName);
        long opGeneration = runtimeHooks.currentGeneration();
        if (localTarget) {
            return runtimeHooks.submitIfGeneration(opGeneration,
                    () -> finishRemotePut(serverName, slot, playerUuid, playerName, item,
                            requestId, handleRemotePut(request), null, true));
        }
        return conn.<PutItemResponse>sendAsync(
                        FrameType.PUT_ITEM, request, FrameType.PUT_ITEM_RESPONSE, requestTimeoutMs)
                .handle((response, error) -> runtimeHooks.submitIfGeneration(opGeneration, () -> finishRemotePut(serverName, slot, playerUuid,
                        playerName, item, requestId, response, error, false)))
                .thenCompose(future -> future);
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot, int requestCount,
                                                       String playerUuid, String playerName) {
        boolean localTarget = isLocalTarget(serverName);
        if (!localTarget && syncEngine == null) {
            return CompletableFuture.completedFuture(TakeResult.fail("同步引擎未初始化"));
        }
        NeutralItem expected = localTarget
                ? localSlotItem(slot)
                : cacheManager.getSlot(serverName, InventoryScope.server(), slot);
        if (expected == null || expected.isEmpty()) {
            return CompletableFuture.completedFuture(TakeResult.fail("物品已变化，请重试"));
        }
        if (expected.isIncompatible()) {
            return CompletableFuture.completedFuture(TakeResult.fail("不兼容物品禁止操作"));
        }
        int expectedVersion = localTarget
                ? localSlotVersion(slot)
                : cacheManager.getSlotVersion(serverName, InventoryScope.server(), slot);
        return takeItemAsync(serverName, slot, expected.getItemId(),
                expectedVersion, requestCount, playerUuid, playerName);
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot,
                                                       String expectedItemId, int expectedVersion,
                                                       int requestCount,
                                                       String playerUuid, String playerName) {
        boolean localTarget = isLocalTarget(serverName);
        Connection conn = null;
        if (!localTarget) {
            if (networkManager == null) {
                return CompletableFuture.completedFuture(TakeResult.fail("网络功能未启用，请检查端口配置"));
            }
            conn = networkManager.getConnection(serverName);
            if (conn == null) {
                return CompletableFuture.completedFuture(TakeResult.fail("目标服务器离线"));
            }
        }

        String requestId = UUID.randomUUID().toString();
        TakeItemRequest request = new TakeItemRequest(slot, expectedItemId,
                expectedVersion, requestCount, requestId, playerUuid, playerName);

        long opGeneration = runtimeHooks.currentGeneration();
        if (localTarget) {
            return runtimeHooks.submitIfGeneration(opGeneration,
                    () -> finishRemoteTake(serverName, slot, expectedItemId, requestCount,
                            playerUuid, playerName, requestId, handleRemoteTake(request), null, true));
        }
        return conn.<TakeItemResponse>sendAsync(
                        FrameType.TAKE_ITEM, request, FrameType.TAKE_ITEM_RESPONSE, requestTimeoutMs)
                .handle((response, error) -> runtimeHooks.submitIfGeneration(opGeneration, () -> finishRemoteTake(serverName, slot,
                        expectedItemId, requestCount, playerUuid, playerName,
                        requestId, response, error, false)))
                .thenCompose(future -> future);
    }

    public CompletableFuture<SwapResult> swapItemAsync(String serverName, int slot,
                                                       NeutralItem newItem,
                                                       String expectedItemId,
                                                       int takeCount,
                                                       boolean boundedMerge,
                                                       String playerUuid, String playerName) {
        if (newItem == null || newItem.isEmpty()) {
            return CompletableFuture.completedFuture(SwapResult.fail("物品为空"));
        }
        if (newItem.isIncompatible()) {
            return CompletableFuture.completedFuture(SwapResult.fail("不兼容物品禁止操作"));
        }
        boolean localTarget = isLocalTarget(serverName);
        Connection conn = null;
        if (!localTarget) {
            if (networkManager == null) {
                return CompletableFuture.completedFuture(SwapResult.fail("网络功能未启用，请检查端口配置"));
            }
            conn = networkManager.getConnection(serverName);
            if (conn == null) {
                return CompletableFuture.completedFuture(SwapResult.fail("目标服务器离线"));
            }
        }
        NeutralItem expected = localTarget
                ? localSlotItem(slot)
                : cacheManager.getSlot(serverName, InventoryScope.server(), slot);
        if (expected == null || expected.isEmpty()) {
            return CompletableFuture.completedFuture(SwapResult.fail("物品已变化，请重试"));
        }
        if (expected.isIncompatible()) {
            return CompletableFuture.completedFuture(SwapResult.fail("不兼容物品禁止操作"));
        }
        int expectedVersion = localTarget
                ? localSlotVersion(slot)
                : cacheManager.getSlotVersion(serverName, InventoryScope.server(), slot);
        String requestId = UUID.randomUUID().toString();
        SwapItemRequest request = new SwapItemRequest(slot, newItem, expectedVersion,
                expectedItemId, takeCount, boundedMerge, requestId, playerUuid, playerName);

        long opGeneration = runtimeHooks.currentGeneration();
        if (localTarget) {
            return runtimeHooks.submitIfGeneration(opGeneration,
                    () -> finishRemoteSwap(serverName, slot, playerUuid, playerName,
                            newItem, requestId, handleRemoteSwap(request), null, true));
        }
        return conn.<SwapItemResponse>sendAsync(
                        FrameType.SWAP_ITEM, request, FrameType.SWAP_ITEM_RESPONSE, requestTimeoutMs)
                .handle((response, error) -> runtimeHooks.submitIfGeneration(opGeneration,
                        () -> finishRemoteSwap(serverName, slot, playerUuid, playerName,
                                newItem, requestId, response, error, false)))
                .thenCompose(future -> future);
    }

    private PutResult finishRemotePut(String serverName, int slot, String playerUuid,
                                      String playerName, NeutralItem item, String requestId,
                                      PutItemResponse response, Throwable error,
                                      boolean localLoopback) {
        if (error != null || response == null) {
            logRequester(localLoopback, requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), false,
                    error != null ? error.getMessage() : "TIMEOUT");
            return PutResult.fail("请求超时，物品已退回");
        }

        if (response.isSuccess()) {
            logRequester(localLoopback, requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), true, null);
            finishSuccessfulMutation(serverName, slot, response.getCurrentItem(),
                    response.getNewVersion(), localLoopback);
            return PutResult.success(response.getCurrentItem());
        }

        logRequester(localLoopback, requestId, OperationType.PUT, playerUuid, playerName,
                serverName, item.getItemId(), item.getCount(), false, response.getFailReason());
        finishFailedMutation(serverName, slot, response.getCurrentItem(),
                response.getNewVersion(), localLoopback);
        return PutResult.fail(response.getFailReason());
    }

    private TakeResult finishRemoteTake(String serverName, int slot, String expectedItemId,
                                        int requestCount, String playerUuid, String playerName,
                                        String requestId, TakeItemResponse response,
                                        Throwable error, boolean localLoopback) {
        if (error != null || response == null) {
            logRequester(localLoopback, requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, false,
                    error != null ? error.getMessage() : "TIMEOUT");
            return TakeResult.fail("请求超时");
        }

        if (response.isSuccess()) {
            if (response.getItemsToGive() == null || response.getItemsToGive().isEmpty()
                    || response.getItemsToGive().isIncompatible()) {
                logRequester(localLoopback, requestId, OperationType.TAKE, playerUuid, playerName,
                        serverName, expectedItemId, requestCount, false, "INCOMPATIBLE");
                debugTake("rejectResponse", serverName, slot, expectedItemId, requestCount,
                        requestId, response, null, null);
                return TakeResult.fail("不兼容物品禁止操作");
            }
            logRequester(localLoopback, requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, true, null);
            finishSuccessfulMutation(serverName, slot, response.getCurrentItem(),
                    response.getNewVersion(), localLoopback);
            return TakeResult.success(response.getItemsToGive(), response.getNewVersion());
        }

        logRequester(localLoopback, requestId, OperationType.TAKE, playerUuid, playerName,
                serverName, expectedItemId, requestCount, false, response.getFailReason());
        finishFailedMutation(serverName, slot, response.getCurrentItem(),
                response.getNewVersion(), localLoopback);
        return TakeResult.fail(response.getFailReason());
    }

    private SwapResult finishRemoteSwap(String serverName, int slot, String playerUuid,
                                        String playerName, NeutralItem newItem, String requestId,
                                        SwapItemResponse response, Throwable error,
                                        boolean localLoopback) {
        if (error != null || response == null) {
            logRequester(localLoopback, requestId, OperationType.SWAP, playerUuid, playerName,
                    serverName, newItem.getItemId(), newItem.getCount(), false,
                    error != null ? error.getMessage() : "TIMEOUT");
            return SwapResult.fail("请求超时，物品已退回");
        }

        if (response.isSuccess()) {
            if (response.getTakenItem() != null && !response.getTakenItem().isEmpty()
                    && response.getTakenItem().isIncompatible()) {
                logRequester(localLoopback, requestId, OperationType.SWAP, playerUuid, playerName,
                        serverName, newItem.getItemId(), newItem.getCount(), false, "INCOMPATIBLE");
                debugSwap("rejectResponse", serverName, slot, newItem, requestId, response, null, null);
                return SwapResult.fail("不兼容物品禁止操作");
            }
            logRequester(localLoopback, requestId, OperationType.SWAP, playerUuid, playerName,
                    serverName, newItem.getItemId(), newItem.getCount(), true, null);
            finishSuccessfulMutation(serverName, slot, response.getCurrentItem(),
                    response.getNewVersion(), localLoopback);
            return SwapResult.success(response.getTakenItem(), response.getNewVersion());
        }

        logRequester(localLoopback, requestId, OperationType.SWAP, playerUuid, playerName,
                serverName, newItem.getItemId(), newItem.getCount(), false, response.getFailReason());
        finishFailedMutation(serverName, slot, response.getCurrentItem(),
                response.getNewVersion(), localLoopback);
        return SwapResult.fail(response.getFailReason());
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
        cleanupRecentOpsIfDue();
        PutItemResponse cached = recentPut(request);
        if (cached != null) return cached;

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
                return rememberPut(request, new PutItemResponse(true, request.getSlot(),
                        after != null ? after.item() : result.getItem(),
                        null, timestamp,
                        after != null ? after.version() : result.getNewVersion(),
                        request.getRequestId()));
            }

            operationLogger.log(request.getRequestId(), OperationType.PUT,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", item.getItemId(), item.getCount(), false, result.getFailReason());
            LocalItemStore.ItemRecord current = localItemStore.getItem(request.getSlot());
            return rememberPut(request, new PutItemResponse(false, request.getSlot(),
                    current != null ? current.item() : null,
                    result.getFailReason(), localItemStore.getLastModifiedTimestamp(),
                    current != null ? current.version() : 0,
                    request.getRequestId()));
        } catch (Exception e) {
            NeutralItem item = request.getItem();
            debugPut("exception", request, item, null, null, e);
            operationLogger.log(request.getRequestId(), OperationType.PUT,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local",
                    item != null ? item.getItemId() : null,
                    item != null ? item.getCount() : 0,
                    false, e.getMessage());
            return rememberPut(request, new PutItemResponse(false, request.getSlot(), null,
                    "INTERNAL_ERROR", 0, 0, request.getRequestId()));
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
        cleanupRecentOpsIfDue();
        TakeItemResponse cached = recentTake(request);
        if (cached != null) return cached;

        try {
            LocalItemStore.ItemRecord before = localItemStore.getItem(request.getSlot());
            debugTake("incoming", "local", request.getSlot(), request.getExpectedItemId(),
                    request.getRequestCount(), request.getRequestId(), null, before, null);
            if (before == null || before.item() == null || before.item().isEmpty()) {
                operationLogger.log(request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, "ITEM_NOT_FOUND");
                return rememberTake(request, new TakeItemResponse(false, request.getSlot(), null,
                        "ITEM_NOT_FOUND", localItemStore.getLastModifiedTimestamp(),
                        before != null ? before.version() : 0,
                        null, request.getRequestId()));
            }
            if (before.item().isIncompatible()) {
                debugTake("rejectIncompatible", "local", request.getSlot(), request.getExpectedItemId(),
                        request.getRequestCount(), request.getRequestId(), null, before, null);
                operationLogger.log(request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, "INCOMPATIBLE");
                return rememberTake(request, new TakeItemResponse(false, request.getSlot(), before.item(),
                        "INCOMPATIBLE", localItemStore.getLastModifiedTimestamp(), before.version(), null,
                        request.getRequestId()));
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

                return rememberTake(request, new TakeItemResponse(true, request.getSlot(),
                        updated != null ? updated.item() : null,
                        null, timestamp,
                        updated != null ? updated.version() : result.getNewVersion(),
                        result.getItem(), request.getRequestId()));
            } else {
                operationLogger.log(request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, result.getFailReason());

                LocalItemStore.ItemRecord r = localItemStore.getItem(request.getSlot());
                return rememberTake(request, new TakeItemResponse(false, request.getSlot(),
                        r != null ? r.item() : null,
                        result.getFailReason(), localItemStore.getLastModifiedTimestamp(),
                        r != null ? r.version() : 0,
                        null, request.getRequestId()));
            }
        } catch (Exception e) {
            debugTake("exception", "local", request.getSlot(), request.getExpectedItemId(),
                    request.getRequestCount(), request.getRequestId(), null, null, null, e);
            operationLogger.log(request.getRequestId(), OperationType.TAKE,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", request.getExpectedItemId(), request.getRequestCount(),
                    false, e.getMessage());
            return rememberTake(request, new TakeItemResponse(false, request.getSlot(), null,
                    "INTERNAL_ERROR", 0, 0, null, request.getRequestId()));
        }
    }

    public SwapItemResponse handleRemoteSwap(SwapItemRequest request) {
        ReentrantLock slotLock = localSlotLock(request.getSlot());
        slotLock.lock();
        try {
            return handleRemoteSwapLocked(request);
        } finally {
            slotLock.unlock();
        }
    }

    private SwapItemResponse handleRemoteSwapLocked(SwapItemRequest request) {
        cleanupRecentOpsIfDue();
        SwapItemResponse cached = recentSwap(request);
        if (cached != null) return cached;

        try {
            NeutralItem newItem = request.getNewItem();
            LocalItemStore.ItemRecord before = localItemStore.getItem(request.getSlot());
            debugSwap("incoming", "local", request.getSlot(), newItem,
                    request.getRequestId(), null, before, null);
            compatibilityChecker.checkAndMark(newItem);
            if (newItem == null || newItem.isEmpty() || newItem.isIncompatible()) {
                operationLogger.log(request.getRequestId(), OperationType.SWAP,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", newItem != null ? newItem.getItemId() : null,
                        newItem != null ? newItem.getCount() : 0,
                        false, "INCOMPATIBLE");
                return rememberSwap(request, new SwapItemResponse(false, request.getSlot(),
                        before != null ? before.item() : null,
                        null,
                        before != null ? before.version() : 0,
                        "INCOMPATIBLE", request.getRequestId()));
            }
            if (before == null || before.item() == null || before.item().isEmpty()) {
                operationLogger.log(request.getRequestId(), OperationType.SWAP,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", newItem.getItemId(), newItem.getCount(),
                        false, "ITEM_NOT_FOUND");
                return rememberSwap(request, new SwapItemResponse(false, request.getSlot(), null, null, 0,
                        "ITEM_NOT_FOUND", request.getRequestId()));
            }
            if (before.item().isIncompatible()) {
                debugSwap("rejectIncompatible", "local", request.getSlot(), newItem,
                        request.getRequestId(), null, before, null);
                operationLogger.log(request.getRequestId(), OperationType.SWAP,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", newItem.getItemId(), newItem.getCount(),
                        false, "INCOMPATIBLE");
                return rememberSwap(request, new SwapItemResponse(false, request.getSlot(), before.item(), null,
                        before.version(), "INCOMPATIBLE", request.getRequestId()));
            }

            LocalItemStore.SwapResult result = localItemStore.swapItem(request.getSlot(), newItem,
                    request.getExpectedItemId(), request.getExpectedVersion(),
                    request.getTakeCount(), request.isBoundedMerge(), request.getPlayerUuid());
            LocalItemStore.ItemRecord after = localItemStore.getItem(request.getSlot());
            debugSwap("storeResult", "local", request.getSlot(), newItem,
                    request.getRequestId(), null, after, result);

            if (result.isSuccess()) {
                operationLogger.log(request.getRequestId(), OperationType.SWAP,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", newItem.getItemId(), newItem.getCount(),
                        true, null);
                return rememberSwap(request, new SwapItemResponse(true, request.getSlot(),
                        after != null ? after.item() : newItem,
                        result.getTakenItem(),
                        after != null ? after.version() : result.getNewVersion(),
                        null, request.getRequestId()));
            }

            operationLogger.log(request.getRequestId(), OperationType.SWAP,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", newItem.getItemId(), newItem.getCount(),
                    false, result.getFailReason());
            LocalItemStore.ItemRecord current = localItemStore.getItem(request.getSlot());
            return rememberSwap(request, new SwapItemResponse(false, request.getSlot(),
                    current != null ? current.item() : null,
                    null,
                    current != null ? current.version() : 0,
                    result.getFailReason(), request.getRequestId()));
        } catch (Exception e) {
            debugSwap("exception", "local", request.getSlot(), request.getNewItem(),
                    request.getRequestId(), null, null, null, e);
            operationLogger.log(request.getRequestId(), OperationType.SWAP,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local",
                    request.getNewItem() != null ? request.getNewItem().getItemId() : null,
                    request.getNewItem() != null ? request.getNewItem().getCount() : 0,
                    false, e.getMessage());
            return rememberSwap(request, new SwapItemResponse(false, request.getSlot(), null, null, 0,
                    "INTERNAL_ERROR", request.getRequestId()));
        }
    }

    private PutItemResponse recentPut(PutItemRequest request) {
        CompletedOp op = recentOps.get(request.getRequestId());
        if (op == null) return null;
        return new PutItemResponse(op.success(), request.getSlot(), op.currentItem(),
                op.failReason(), op.completedAt(), op.newVersion(), request.getRequestId());
    }

    private TakeItemResponse recentTake(TakeItemRequest request) {
        CompletedOp op = recentOps.get(request.getRequestId());
        if (op == null) return null;
        return new TakeItemResponse(op.success(), request.getSlot(), op.currentItem(),
                op.failReason(), op.completedAt(), op.newVersion(), op.takenItem(),
                request.getRequestId());
    }

    private SwapItemResponse recentSwap(SwapItemRequest request) {
        CompletedOp op = recentOps.get(request.getRequestId());
        if (op == null) return null;
        return new SwapItemResponse(op.success(), request.getSlot(), op.currentItem(),
                op.takenItem(), op.newVersion(), op.failReason(), request.getRequestId());
    }

    private PutItemResponse rememberPut(PutItemRequest request, PutItemResponse response) {
        remember(request.getRequestId(), response.isSuccess(), response.getFailReason(),
                response.getCurrentItem(), null, response.getNewVersion());
        return response;
    }

    private TakeItemResponse rememberTake(TakeItemRequest request, TakeItemResponse response) {
        remember(request.getRequestId(), response.isSuccess(), response.getFailReason(),
                response.getCurrentItem(), response.getItemsToGive(), response.getNewVersion());
        return response;
    }

    private SwapItemResponse rememberSwap(SwapItemRequest request, SwapItemResponse response) {
        remember(request.getRequestId(), response.isSuccess(), response.getFailReason(),
                response.getCurrentItem(), response.getTakenItem(), response.getNewVersion());
        return response;
    }

    private boolean isLocalTarget(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return false;
        }
        return "local".equalsIgnoreCase(serverName)
                || serverName.equalsIgnoreCase(runtimeHooks.localServerName());
    }

    private NeutralItem localSlotItem(int slot) {
        LocalItemStore.ItemRecord record = localItemStore.getItem(slot);
        return record != null ? record.item() : null;
    }

    private int localSlotVersion(int slot) {
        LocalItemStore.ItemRecord record = localItemStore.getItem(slot);
        return record != null ? record.version() : 0;
    }

    private void logRequester(boolean localLoopback, String requestId, OperationType type,
                              String playerUuid, String playerName, String serverName,
                              String itemId, int count, boolean success, String failReason) {
        if (localLoopback) {
            return;
        }
        operationLogger.log(requestId, type, playerUuid, playerName,
                serverName, itemId, count, success, failReason);
    }

    private void finishSuccessfulMutation(String serverName, int slot, NeutralItem currentItem,
                                          int newVersion, boolean localLoopback) {
        if (localLoopback) {
            publishLocalInventoryUpdate(List.of(slot));
            redrawOpenViews(serverName);
            return;
        }
        cacheManager.updateCacheSlot(serverName, InventoryScope.server(), slot, currentItem, newVersion);
        redrawOpenViews(serverName);
    }

    private void finishFailedMutation(String serverName, int slot, NeutralItem currentItem,
                                      int newVersion, boolean localLoopback) {
        if (!localLoopback) {
            cacheManager.updateCacheSlot(serverName, InventoryScope.server(), slot, currentItem, newVersion);
        }
        redrawOpenViews(serverName);
    }

    private void remember(String requestId, boolean success, String failReason,
                          NeutralItem currentItem, NeutralItem takenItem, int newVersion) {
        if (requestId == null || requestId.isBlank()) return;
        recentOps.put(requestId, new CompletedOp(System.currentTimeMillis(), success, failReason,
                currentItem, takenItem, newVersion));
    }

    private void cleanupRecentOpsIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastRecentOpCleanup < RECENT_OP_CLEANUP_INTERVAL_MS) return;
        lastRecentOpCleanup = now;
        recentOps.entrySet().removeIf(entry -> now - entry.getValue().completedAt() > RECENT_OP_TTL_MS);
    }

    private void debugTake(String stage, String serverName, int slot, String expectedItemId,
                           int requestCount, String requestId, TakeItemResponse response,
                           LocalItemStore.ItemRecord existing, LocalItemStore.TakeResult result) {
        debugTake(stage, serverName, slot, expectedItemId, requestCount, requestId, response, existing, result, null);
    }

    private void debugTake(String stage, String serverName, int slot, String expectedItemId,
                           int requestCount, String requestId, TakeItemResponse response,
                           LocalItemStore.ItemRecord existing, LocalItemStore.TakeResult result,
                           Throwable error) {
        ExchangeAPI.Logger logger = runtimeHooks.logger();
        if (logger == null) {
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
        if (error != null) {
            sb.append(" error=").append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage());
        }
        logger.info(sb.toString());
    }

    private void debugSwap(String stage, String serverName, int slot, NeutralItem newItem,
                           String requestId, SwapItemResponse response,
                           LocalItemStore.ItemRecord existing, LocalItemStore.SwapResult result) {
        debugSwap(stage, serverName, slot, newItem, requestId, response, existing, result, null);
    }

    private void debugSwap(String stage, String serverName, int slot, NeutralItem newItem,
                           String requestId, SwapItemResponse response,
                           LocalItemStore.ItemRecord existing, LocalItemStore.SwapResult result,
                           Throwable error) {
        ExchangeAPI.Logger logger = runtimeHooks.logger();
        if (logger == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Exchange|Debug][SWAP][").append(stage).append("] ")
                .append("server=").append(serverName)
                .append(" slot=").append(slot)
                .append(" reqId=").append(requestId)
                .append(" newItem=").append(describeItem(newItem));
        if (existing != null) {
            sb.append(" existing=").append(describeRecord(existing));
        }
        if (response != null) {
            sb.append(" responseSuccess=").append(response.isSuccess())
                    .append(" failReason=").append(response.getFailReason())
                    .append(" current=").append(describeItem(response.getCurrentItem()))
                    .append(" taken=").append(describeItem(response.getTakenItem()))
                    .append(" newVersion=").append(response.getNewVersion());
        }
        if (result != null) {
            sb.append(" resultSuccess=").append(result.isSuccess())
                    .append(" failReason=").append(result.getFailReason())
                    .append(" taken=").append(describeItem(result.getTakenItem()))
                    .append(" newVersion=").append(result.getNewVersion());
        }
        if (error != null) {
            sb.append(" error=").append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage());
        }
        logger.info(sb.toString());
    }

    private void debugPut(String stage, PutItemRequest request, NeutralItem item,
                          LocalItemStore.ItemRecord existing,
                          LocalItemStore.PutResult result, Throwable error) {
        ExchangeAPI.Logger logger = runtimeHooks.logger();
        if (logger == null) {
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
        logger.info(sb.toString());
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

    public void routeMessage(Connection conn,
                             FrameType type, Object message) {
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
            case SWAP_ITEM -> {
                SwapItemResponse resp = handleRemoteSwap((SwapItemRequest) message);
                conn.send(FrameType.SWAP_ITEM_RESPONSE, resp);
                if (resp.isSuccess()) {
                    broadcastInventoryUpdate(conn, List.of(((SwapItemRequest) message).getSlot()),
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
                if (syncEngine != null) {
                    syncEngine.querySlotsAsync(sourceServerName, update.getChangedSlots())
                            .whenComplete((ignored, error) -> {
                                runtimeHooks.runOnMainThread(() -> runtimeHooks.redrawRemoteInventoryView(sourceServerName));
                            });
                }
            }
            default -> {}
        }
    }

    private void refreshOpenViews(String serverName) {
        runtimeHooks.refreshRemoteInventoryView(serverName);
    }

    private void redrawOpenViews(String serverName) {
        runtimeHooks.redrawRemoteInventoryView(serverName);
    }

    private String sourceServerName(Connection conn) {
        return conn.getPeerServerName() != null ? conn.getPeerServerName() : conn.getRemoteName();
    }

    private ReentrantLock localSlotLock(int slot) {
        return localSlotLocks.computeIfAbsent(slot, ignored -> new ReentrantLock());
    }

    private List<Integer> localSlotVersions() {
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
        String localName = runtimeHooks.localServerName();
        runtimeHooks.runOnMainThread(() -> runtimeHooks.refreshRemoteInventoryView(localName));
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

    public static class SwapResult {
        private final boolean success;
        private final String failReason;
        private final NeutralItem takenItem;
        private final int newVersion;

        private SwapResult(boolean success, String failReason, NeutralItem takenItem, int newVersion) {
            this.success = success;
            this.failReason = failReason;
            this.takenItem = takenItem;
            this.newVersion = newVersion;
        }

        public static SwapResult success(NeutralItem takenItem, int newVersion) {
            return new SwapResult(true, null, takenItem, newVersion);
        }

        public static SwapResult fail(String reason) {
            return new SwapResult(false, reason, null, -1);
        }

        public boolean isSuccess() { return success; }
        public String getFailReason() { return failReason; }
        public NeutralItem getTakenItem() { return takenItem; }
        public int getNewVersion() { return newVersion; }
    }
}
