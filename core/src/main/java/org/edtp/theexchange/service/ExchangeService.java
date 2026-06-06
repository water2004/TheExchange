package org.edtp.theexchange.service;

import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.OperationType;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.storage.LocalItemStore;
import org.edtp.theexchange.storage.OperationLogger;
import org.edtp.theexchange.storage.PlayerInventoryAuthStore;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
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
    private final PlayerInventoryAuthStore playerInventoryAuthStore;
    private final CacheManager cacheManager;
    private final CompatibilityChecker compatibilityChecker;
    private final ItemSerializer itemSerializer;
    private final SyncEngine syncEngine;
    private final RuntimeHooks runtimeHooks;
    private final long requestTimeoutMs;
    private final ConcurrentHashMap<ScopeSlotKey, ReentrantLock> localSlotLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ScopedRequestKey, CompletedOp> recentOps = new ConcurrentHashMap<>();
    private volatile long lastRecentOpCleanup;

    public ExchangeService(NetworkManager networkManager, LocalItemStore localItemStore,
                           OperationLogger operationLogger, PlayerInventoryAuthStore playerInventoryAuthStore,
                           CacheManager cacheManager,
                           CompatibilityChecker compatibilityChecker, ItemSerializer itemSerializer,
                           SyncEngine syncEngine, RuntimeHooks runtimeHooks, long requestTimeoutMs) {
        this.networkManager = networkManager;
        this.localItemStore = localItemStore;
        this.operationLogger = operationLogger;
        this.playerInventoryAuthStore = playerInventoryAuthStore;
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
        void refreshInventoryView(String serverName, InventoryScope scope);
        void redrawRemoteInventoryView(String serverName);
        void redrawInventoryView(String serverName, InventoryScope scope);
        void runOnMainThread(Runnable task);
        String localServerName();
        Optional<ExchangeAPI.PlayerIdentity> resolvePlayerIdentity(String playerName);
    }

    private record CompletedOp(long completedAt, boolean success, String failReason,
                               NeutralItem currentItem, NeutralItem takenItem, int newVersion,
                               InventoryScope scope) {}

    private record ScopeSlotKey(InventoryScope scope, int slot) {}

    private record ScopedRequestKey(InventoryScope scope, String requestId) {
        private static ScopedRequestKey of(InventoryScope scope, String requestId) {
            return new ScopedRequestKey(scope != null ? scope : InventoryScope.server(), requestId);
        }
    }

    private record AccessResolution(boolean success, InventoryScope scope, InventoryAccess access, String failReason) {
        static AccessResolution success(InventoryScope scope, InventoryAccess access) {
            InventoryScope resolvedScope = scope != null ? scope : InventoryScope.server();
            InventoryAccess resolvedAccess = access != null ? access.withResolvedScope(resolvedScope) : InventoryAccess.server();
            return new AccessResolution(true, resolvedScope, resolvedAccess, null);
        }

        static AccessResolution fail(InventoryScope scope, InventoryAccess access, String failReason) {
            return new AccessResolution(false, scope != null ? scope : InventoryScope.server(),
                    access != null ? access : InventoryAccess.server(), failReason);
        }
    }

    public int getMaxStackSize(NeutralItem item) {
        return itemSerializer.getMaxStackSize(item);
    }

    public CompletableFuture<PutResult> putNeutralItemAsync(String serverName, int slot,
                                                            String playerUuid, String playerName,
                                                            NeutralItem item) {
        return putNeutralItemAsync(serverName, slot, playerUuid, playerName, item, InventoryAccess.server());
    }

    public CompletableFuture<PutResult> putNeutralItemAsync(String serverName, int slot,
                                                            String playerUuid, String playerName,
                                                            NeutralItem item, InventoryAccess requestedAccess) {
        if (item == null || item.isEmpty()) {
            return CompletableFuture.completedFuture(PutResult.fail("物品为空"));
        }
        InventoryAccess access = normalizeAccess(requestedAccess);
        boolean localTarget = isLocalTarget(serverName);
        InventoryScope operationScope = access.effectiveScope();
        if (localTarget) {
            AccessResolution resolution = resolveAccess(access);
            if (!resolution.success()) {
                return CompletableFuture.completedFuture(PutResult.fail(resolution.failReason()));
            }
            access = resolution.access();
            operationScope = resolution.scope();
        } else if (operationScope == null) {
            return CompletableFuture.completedFuture(PutResult.fail("玩家仓库尚未加载，请先打开仓库"));
        }
        Connection conn = null;
        if (!localTarget) {
            if (networkManager == null) {
                return CompletableFuture.completedFuture(PutResult.fail("网络功能未启用，请检查端口配置"));
            }
            conn = networkManager.getConnection(serverName);
            if (conn == null) {
                return CompletableFuture.completedFuture(PutResult.fail("目标服务器离线"));
            }
            if (!supportsRequestedAccess(conn, access)) {
                return CompletableFuture.completedFuture(PutResult.fail(unsupportedPlayerInventoryMessage()));
            }
            if (syncEngine == null) {
                return CompletableFuture.completedFuture(PutResult.fail("同步引擎未初始化"));
            }
            NeutralItem cached = cacheManager.getSlot(serverName, operationScope, slot);
            if (cached != null && cached.isIncompatible()) {
                return CompletableFuture.completedFuture(PutResult.fail("不兼容物品禁止操作"));
            }
        }
        int expectedVersion = localTarget
                ? localSlotVersion(operationScope, slot)
                : cacheManager.getSlotVersion(serverName, operationScope, slot);
        String requestId = UUID.randomUUID().toString();
        PutItemRequest request = new PutItemRequest(slot, item, expectedVersion,
                requestId, playerUuid, playerName, access);
        long opGeneration = runtimeHooks.currentGeneration();
        InventoryAccess finalAccess = access;
        if (localTarget) {
            return runtimeHooks.submitIfGeneration(opGeneration,
                    () -> finishRemotePut(serverName, slot, playerUuid, playerName, item,
                            requestId, handleRemotePut(request), null, true, finalAccess));
        }
        return conn.<PutItemResponse>sendAsync(
                        FrameType.PUT_ITEM, request, FrameType.PUT_ITEM_RESPONSE, requestTimeoutMs)
                .handle((response, error) -> runtimeHooks.submitIfGeneration(opGeneration, () -> finishRemotePut(serverName, slot, playerUuid,
                        playerName, item, requestId, response, error, false, finalAccess)))
                .thenCompose(future -> future);
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot, int requestCount,
                                                       String playerUuid, String playerName) {
        return takeItemAsync(serverName, slot, requestCount, playerUuid, playerName, InventoryAccess.server());
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot, int requestCount,
                                                       String playerUuid, String playerName,
                                                       InventoryAccess requestedAccess) {
        InventoryAccess access = normalizeAccess(requestedAccess);
        boolean localTarget = isLocalTarget(serverName);
        InventoryScope operationScope = access.effectiveScope();
        if (localTarget) {
            AccessResolution resolution = resolveAccess(access);
            if (!resolution.success()) {
                return CompletableFuture.completedFuture(TakeResult.fail(resolution.failReason()));
            }
            access = resolution.access();
            operationScope = resolution.scope();
        } else if (operationScope == null) {
            return CompletableFuture.completedFuture(TakeResult.fail("玩家仓库尚未加载，请先打开仓库"));
        }
        if (!localTarget && syncEngine == null) {
            return CompletableFuture.completedFuture(TakeResult.fail("同步引擎未初始化"));
        }
        NeutralItem expected = localTarget
                ? localSlotItem(operationScope, slot)
                : cacheManager.getSlot(serverName, operationScope, slot);
        if (expected == null || expected.isEmpty()) {
            return CompletableFuture.completedFuture(TakeResult.fail("物品已变化，请重试"));
        }
        if (expected.isIncompatible()) {
            return CompletableFuture.completedFuture(TakeResult.fail("不兼容物品禁止操作"));
        }
        int expectedVersion = localTarget
                ? localSlotVersion(operationScope, slot)
                : cacheManager.getSlotVersion(serverName, operationScope, slot);
        return takeItemAsync(serverName, slot, expected.getItemId(),
                expectedVersion, requestCount, playerUuid, playerName, access);
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot,
                                                       String expectedItemId, int expectedVersion,
                                                       int requestCount,
                                                       String playerUuid, String playerName) {
        return takeItemAsync(serverName, slot, expectedItemId, expectedVersion, requestCount,
                playerUuid, playerName, InventoryAccess.server());
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot,
                                                       String expectedItemId, int expectedVersion,
                                                       int requestCount,
                                                       String playerUuid, String playerName,
                                                       InventoryAccess requestedAccess) {
        InventoryAccess access = normalizeAccess(requestedAccess);
        boolean localTarget = isLocalTarget(serverName);
        if (localTarget) {
            AccessResolution resolution = resolveAccess(access);
            if (!resolution.success()) {
                return CompletableFuture.completedFuture(TakeResult.fail(resolution.failReason()));
            }
            access = resolution.access();
        } else if (access.effectiveScope() == null) {
            return CompletableFuture.completedFuture(TakeResult.fail("玩家仓库尚未加载，请先打开仓库"));
        }
        Connection conn = null;
        if (!localTarget) {
            if (networkManager == null) {
                return CompletableFuture.completedFuture(TakeResult.fail("网络功能未启用，请检查端口配置"));
            }
            conn = networkManager.getConnection(serverName);
            if (conn == null) {
                return CompletableFuture.completedFuture(TakeResult.fail("目标服务器离线"));
            }
            if (!supportsRequestedAccess(conn, access)) {
                return CompletableFuture.completedFuture(TakeResult.fail(unsupportedPlayerInventoryMessage()));
            }
        }

        String requestId = UUID.randomUUID().toString();
        TakeItemRequest request = new TakeItemRequest(slot, expectedItemId,
                expectedVersion, requestCount, requestId, playerUuid, playerName, access);

        long opGeneration = runtimeHooks.currentGeneration();
        InventoryAccess finalAccess = access;
        if (localTarget) {
            return runtimeHooks.submitIfGeneration(opGeneration,
                    () -> finishRemoteTake(serverName, slot, expectedItemId, requestCount,
                            playerUuid, playerName, requestId, handleRemoteTake(request), null, true, finalAccess));
        }
        return conn.<TakeItemResponse>sendAsync(
                        FrameType.TAKE_ITEM, request, FrameType.TAKE_ITEM_RESPONSE, requestTimeoutMs)
                .handle((response, error) -> runtimeHooks.submitIfGeneration(opGeneration, () -> finishRemoteTake(serverName, slot,
                        expectedItemId, requestCount, playerUuid, playerName,
                        requestId, response, error, false, finalAccess)))
                .thenCompose(future -> future);
    }

    public CompletableFuture<SwapResult> swapItemAsync(String serverName, int slot,
                                                       NeutralItem newItem,
                                                       String expectedItemId,
                                                       int takeCount,
                                                       boolean boundedMerge,
                                                       String playerUuid, String playerName) {
        return swapItemAsync(serverName, slot, newItem, expectedItemId, takeCount,
                boundedMerge, playerUuid, playerName, InventoryAccess.server());
    }

    public CompletableFuture<SwapResult> swapItemAsync(String serverName, int slot,
                                                       NeutralItem newItem,
                                                       String expectedItemId,
                                                       int takeCount,
                                                       boolean boundedMerge,
                                                       String playerUuid, String playerName,
                                                       InventoryAccess requestedAccess) {
        if (newItem == null || newItem.isEmpty()) {
            return CompletableFuture.completedFuture(SwapResult.fail("物品为空"));
        }
        if (newItem.isIncompatible()) {
            return CompletableFuture.completedFuture(SwapResult.fail("不兼容物品禁止操作"));
        }
        InventoryAccess access = normalizeAccess(requestedAccess);
        boolean localTarget = isLocalTarget(serverName);
        InventoryScope operationScope = access.effectiveScope();
        if (localTarget) {
            AccessResolution resolution = resolveAccess(access);
            if (!resolution.success()) {
                return CompletableFuture.completedFuture(SwapResult.fail(resolution.failReason()));
            }
            access = resolution.access();
            operationScope = resolution.scope();
        } else if (operationScope == null) {
            return CompletableFuture.completedFuture(SwapResult.fail("玩家仓库尚未加载，请先打开仓库"));
        }
        Connection conn = null;
        if (!localTarget) {
            if (networkManager == null) {
                return CompletableFuture.completedFuture(SwapResult.fail("网络功能未启用，请检查端口配置"));
            }
            conn = networkManager.getConnection(serverName);
            if (conn == null) {
                return CompletableFuture.completedFuture(SwapResult.fail("目标服务器离线"));
            }
            if (!supportsRequestedAccess(conn, access)) {
                return CompletableFuture.completedFuture(SwapResult.fail(unsupportedPlayerInventoryMessage()));
            }
        }
        NeutralItem expected = localTarget
                ? localSlotItem(operationScope, slot)
                : cacheManager.getSlot(serverName, operationScope, slot);
        if (expected == null || expected.isEmpty()) {
            return CompletableFuture.completedFuture(SwapResult.fail("物品已变化，请重试"));
        }
        if (expected.isIncompatible()) {
            return CompletableFuture.completedFuture(SwapResult.fail("不兼容物品禁止操作"));
        }
        int expectedVersion = localTarget
                ? localSlotVersion(operationScope, slot)
                : cacheManager.getSlotVersion(serverName, operationScope, slot);
        String requestId = UUID.randomUUID().toString();
        SwapItemRequest request = new SwapItemRequest(slot, newItem, expectedVersion,
                expectedItemId, takeCount, boundedMerge, requestId, playerUuid, playerName, access);

        long opGeneration = runtimeHooks.currentGeneration();
        InventoryAccess finalAccess = access;
        if (localTarget) {
            return runtimeHooks.submitIfGeneration(opGeneration,
                    () -> finishRemoteSwap(serverName, slot, playerUuid, playerName,
                            newItem, requestId, handleRemoteSwap(request), null, true, finalAccess));
        }
        return conn.<SwapItemResponse>sendAsync(
                        FrameType.SWAP_ITEM, request, FrameType.SWAP_ITEM_RESPONSE, requestTimeoutMs)
                .handle((response, error) -> runtimeHooks.submitIfGeneration(opGeneration,
                        () -> finishRemoteSwap(serverName, slot, playerUuid, playerName,
                                newItem, requestId, response, error, false, finalAccess)))
                .thenCompose(future -> future);
    }

    private PutResult finishRemotePut(String serverName, int slot, String playerUuid,
                                      String playerName, NeutralItem item, String requestId,
                                      PutItemResponse response, Throwable error,
                                      boolean localLoopback, InventoryAccess access) {
        InventoryScope scope = response != null ? response.getScope() : scopeFromAccess(access);
        if (error != null || response == null) {
            logRequester(localLoopback, scope, requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), false,
                    error != null ? error.getMessage() : "TIMEOUT");
            return PutResult.fail("请求超时，物品已退回");
        }

        if (response.isSuccess()) {
            logRequester(localLoopback, scope, requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), true, null);
            finishSuccessfulMutation(serverName, scope, slot, response.getCurrentItem(),
                    response.getNewVersion(), localLoopback);
            return PutResult.success(response.getCurrentItem());
        }

        logRequester(localLoopback, scope, requestId, OperationType.PUT, playerUuid, playerName,
                serverName, item.getItemId(), item.getCount(), false, response.getFailReason());
        finishFailedMutation(serverName, scope, slot, response.getCurrentItem(),
                response.getNewVersion(), localLoopback);
        return PutResult.fail(response.getFailReason());
    }

    private TakeResult finishRemoteTake(String serverName, int slot, String expectedItemId,
                                        int requestCount, String playerUuid, String playerName,
                                        String requestId, TakeItemResponse response,
                                        Throwable error, boolean localLoopback, InventoryAccess access) {
        InventoryScope scope = response != null ? response.getScope() : scopeFromAccess(access);
        if (error != null || response == null) {
            logRequester(localLoopback, scope, requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, false,
                    error != null ? error.getMessage() : "TIMEOUT");
            return TakeResult.fail("请求超时");
        }

        if (response.isSuccess()) {
            if (response.getItemsToGive() == null || response.getItemsToGive().isEmpty()
                    || response.getItemsToGive().isIncompatible()) {
                logRequester(localLoopback, scope, requestId, OperationType.TAKE, playerUuid, playerName,
                        serverName, expectedItemId, requestCount, false, "INCOMPATIBLE");
                debugTake("rejectResponse", serverName, slot, expectedItemId, requestCount,
                        requestId, response, null, null);
                return TakeResult.fail("不兼容物品禁止操作");
            }
            logRequester(localLoopback, scope, requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, true, null);
            finishSuccessfulMutation(serverName, scope, slot, response.getCurrentItem(),
                    response.getNewVersion(), localLoopback);
            return TakeResult.success(response.getItemsToGive(), response.getNewVersion());
        }

        logRequester(localLoopback, scope, requestId, OperationType.TAKE, playerUuid, playerName,
                serverName, expectedItemId, requestCount, false, response.getFailReason());
        finishFailedMutation(serverName, scope, slot, response.getCurrentItem(),
                response.getNewVersion(), localLoopback);
        return TakeResult.fail(response.getFailReason());
    }

    private SwapResult finishRemoteSwap(String serverName, int slot, String playerUuid,
                                        String playerName, NeutralItem newItem, String requestId,
                                        SwapItemResponse response, Throwable error,
                                        boolean localLoopback, InventoryAccess access) {
        InventoryScope scope = response != null ? response.getScope() : scopeFromAccess(access);
        if (error != null || response == null) {
            logRequester(localLoopback, scope, requestId, OperationType.SWAP, playerUuid, playerName,
                    serverName, newItem.getItemId(), newItem.getCount(), false,
                    error != null ? error.getMessage() : "TIMEOUT");
            return SwapResult.fail("请求超时，物品已退回");
        }

        if (response.isSuccess()) {
            if (response.getTakenItem() != null && !response.getTakenItem().isEmpty()
                    && response.getTakenItem().isIncompatible()) {
                logRequester(localLoopback, scope, requestId, OperationType.SWAP, playerUuid, playerName,
                        serverName, newItem.getItemId(), newItem.getCount(), false, "INCOMPATIBLE");
                debugSwap("rejectResponse", serverName, slot, newItem, requestId, response, null, null);
                return SwapResult.fail("不兼容物品禁止操作");
            }
            logRequester(localLoopback, scope, requestId, OperationType.SWAP, playerUuid, playerName,
                    serverName, newItem.getItemId(), newItem.getCount(), true, null);
            finishSuccessfulMutation(serverName, scope, slot, response.getCurrentItem(),
                    response.getNewVersion(), localLoopback);
            return SwapResult.success(response.getTakenItem(), response.getNewVersion());
        }

        logRequester(localLoopback, scope, requestId, OperationType.SWAP, playerUuid, playerName,
                serverName, newItem.getItemId(), newItem.getCount(), false, response.getFailReason());
        finishFailedMutation(serverName, scope, slot, response.getCurrentItem(),
                response.getNewVersion(), localLoopback);
        return SwapResult.fail(response.getFailReason());
    }

    public PutItemResponse handleRemotePut(PutItemRequest request) {
        AccessResolution resolution = resolveAccess(request.getAccess());
        if (!resolution.success()) {
            return rememberPut(request, new PutItemResponse(false, request.getSlot(), null,
                    resolution.failReason(), 0, 0, request.getRequestId(), resolution.scope()));
        }
        InventoryScope scope = resolution.scope();
        request.setAccess(resolution.access());
        ReentrantLock slotLock = localSlotLock(scope, request.getSlot());
        slotLock.lock();
        try {
            return handleRemotePutLocked(scope, request);
        } finally {
            slotLock.unlock();
        }
    }

    private PutItemResponse handleRemotePutLocked(InventoryScope scope, PutItemRequest request) {
        cleanupRecentOpsIfDue();
        PutItemResponse cached = recentPut(scope, request);
        if (cached != null) return cached;

        try {
            NeutralItem item = request.getItem();
            LocalItemStore.ItemRecord existingRecord = localItemStore.getItem(scope, request.getSlot());
            debugPut("incoming", request, item, existingRecord, null, null);
            compatibilityChecker.checkAndMark(item);
            debugPut("afterCompat", request, item, existingRecord, null, null);

            LocalItemStore.PutResult result = localItemStore.putItem(
                    scope, request.getSlot(), item, request.getExpectedVersion(), request.getPlayerUuid());
            LocalItemStore.ItemRecord after = localItemStore.getItem(scope, request.getSlot());
            debugPut("storeResult", request, item, after, result, null);

            if (result.isSuccess()) {
                operationLogger.log(scope, request.getRequestId(), OperationType.PUT,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", item.getItemId(), item.getCount(), true, null);

                long timestamp = localItemStore.getLastModifiedTimestamp(scope);
                return rememberPut(request, new PutItemResponse(true, request.getSlot(),
                        after != null ? after.item() : result.getItem(),
                        null, timestamp,
                        after != null ? after.version() : result.getNewVersion(),
                        request.getRequestId(), scope));
            }

            operationLogger.log(scope, request.getRequestId(), OperationType.PUT,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", item.getItemId(), item.getCount(), false, result.getFailReason());
            LocalItemStore.ItemRecord current = localItemStore.getItem(scope, request.getSlot());
            return rememberPut(request, new PutItemResponse(false, request.getSlot(),
                    current != null ? current.item() : null,
                    result.getFailReason(), localItemStore.getLastModifiedTimestamp(scope),
                    current != null ? current.version() : 0,
                    request.getRequestId(), scope));
        } catch (Exception e) {
            NeutralItem item = request.getItem();
            debugPut("exception", request, item, null, null, e);
            operationLogger.log(scope, request.getRequestId(), OperationType.PUT,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local",
                    item != null ? item.getItemId() : null,
                    item != null ? item.getCount() : 0,
                    false, e.getMessage());
            return rememberPut(request, new PutItemResponse(false, request.getSlot(), null,
                    "INTERNAL_ERROR", 0, 0, request.getRequestId(), scope));
        }
    }

    public TakeItemResponse handleRemoteTake(TakeItemRequest request) {
        AccessResolution resolution = resolveAccess(request.getAccess());
        if (!resolution.success()) {
            return rememberTake(request, new TakeItemResponse(false, request.getSlot(), null,
                    resolution.failReason(), 0, 0, null, request.getRequestId(), resolution.scope()));
        }
        InventoryScope scope = resolution.scope();
        request.setAccess(resolution.access());
        ReentrantLock slotLock = localSlotLock(scope, request.getSlot());
        slotLock.lock();
        try {
            return handleRemoteTakeLocked(scope, request);
        } finally {
            slotLock.unlock();
        }
    }

    private TakeItemResponse handleRemoteTakeLocked(InventoryScope scope, TakeItemRequest request) {
        cleanupRecentOpsIfDue();
        TakeItemResponse cached = recentTake(scope, request);
        if (cached != null) return cached;

        try {
            LocalItemStore.ItemRecord before = localItemStore.getItem(scope, request.getSlot());
            debugTake("incoming", "local", request.getSlot(), request.getExpectedItemId(),
                    request.getRequestCount(), request.getRequestId(), null, before, null);
            if (before == null || before.item() == null || before.item().isEmpty()) {
                operationLogger.log(scope, request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, "ITEM_NOT_FOUND");
                return rememberTake(request, new TakeItemResponse(false, request.getSlot(), null,
                        "ITEM_NOT_FOUND", localItemStore.getLastModifiedTimestamp(scope),
                        before != null ? before.version() : 0,
                        null, request.getRequestId(), scope));
            }
            if (before.item().isIncompatible()) {
                debugTake("rejectIncompatible", "local", request.getSlot(), request.getExpectedItemId(),
                        request.getRequestCount(), request.getRequestId(), null, before, null);
                operationLogger.log(scope, request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, "INCOMPATIBLE");
                return rememberTake(request, new TakeItemResponse(false, request.getSlot(), before.item(),
                        "INCOMPATIBLE", localItemStore.getLastModifiedTimestamp(scope), before.version(), null,
                        request.getRequestId(), scope));
            }
            LocalItemStore.TakeResult result = localItemStore.takeItem(
                    scope, request.getSlot(), request.getExpectedItemId(),
                    request.getExpectedVersion(), request.getRequestCount());
            debugTake("storeResult", "local", request.getSlot(), request.getExpectedItemId(),
                    request.getRequestCount(), request.getRequestId(), null, before, result);

            if (result.isSuccess()) {
                operationLogger.log(scope, request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        true, null);

                long timestamp = localItemStore.getLastModifiedTimestamp(scope);
                LocalItemStore.ItemRecord updated = localItemStore.getItem(scope, request.getSlot());

                return rememberTake(request, new TakeItemResponse(true, request.getSlot(),
                        updated != null ? updated.item() : null,
                        null, timestamp,
                        updated != null ? updated.version() : result.getNewVersion(),
                        result.getItem(), request.getRequestId(), scope));
            } else {
                operationLogger.log(scope, request.getRequestId(), OperationType.TAKE,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", request.getExpectedItemId(), request.getRequestCount(),
                        false, result.getFailReason());

                LocalItemStore.ItemRecord r = localItemStore.getItem(scope, request.getSlot());
                return rememberTake(request, new TakeItemResponse(false, request.getSlot(),
                        r != null ? r.item() : null,
                        result.getFailReason(), localItemStore.getLastModifiedTimestamp(scope),
                        r != null ? r.version() : 0,
                        null, request.getRequestId(), scope));
            }
        } catch (Exception e) {
            debugTake("exception", "local", request.getSlot(), request.getExpectedItemId(),
                    request.getRequestCount(), request.getRequestId(), null, null, null, e);
            operationLogger.log(scope, request.getRequestId(), OperationType.TAKE,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", request.getExpectedItemId(), request.getRequestCount(),
                    false, e.getMessage());
            return rememberTake(request, new TakeItemResponse(false, request.getSlot(), null,
                    "INTERNAL_ERROR", 0, 0, null, request.getRequestId(), scope));
        }
    }

    public SwapItemResponse handleRemoteSwap(SwapItemRequest request) {
        AccessResolution resolution = resolveAccess(request.getAccess());
        if (!resolution.success()) {
            return rememberSwap(request, new SwapItemResponse(false, request.getSlot(), null, null, 0,
                    resolution.failReason(), request.getRequestId(), resolution.scope()));
        }
        InventoryScope scope = resolution.scope();
        request.setAccess(resolution.access());
        ReentrantLock slotLock = localSlotLock(scope, request.getSlot());
        slotLock.lock();
        try {
            return handleRemoteSwapLocked(scope, request);
        } finally {
            slotLock.unlock();
        }
    }

    private SwapItemResponse handleRemoteSwapLocked(InventoryScope scope, SwapItemRequest request) {
        cleanupRecentOpsIfDue();
        SwapItemResponse cached = recentSwap(scope, request);
        if (cached != null) return cached;

        try {
            NeutralItem newItem = request.getNewItem();
            LocalItemStore.ItemRecord before = localItemStore.getItem(scope, request.getSlot());
            debugSwap("incoming", "local", request.getSlot(), newItem,
                    request.getRequestId(), null, before, null);
            compatibilityChecker.checkAndMark(newItem);
            if (newItem == null || newItem.isEmpty() || newItem.isIncompatible()) {
                operationLogger.log(scope, request.getRequestId(), OperationType.SWAP,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", newItem != null ? newItem.getItemId() : null,
                        newItem != null ? newItem.getCount() : 0,
                        false, "INCOMPATIBLE");
                return rememberSwap(request, new SwapItemResponse(false, request.getSlot(),
                        before != null ? before.item() : null,
                        null,
                        before != null ? before.version() : 0,
                        "INCOMPATIBLE", request.getRequestId(), scope));
            }
            if (before == null || before.item() == null || before.item().isEmpty()) {
                operationLogger.log(scope, request.getRequestId(), OperationType.SWAP,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", newItem.getItemId(), newItem.getCount(),
                        false, "ITEM_NOT_FOUND");
                return rememberSwap(request, new SwapItemResponse(false, request.getSlot(), null, null, 0,
                        "ITEM_NOT_FOUND", request.getRequestId(), scope));
            }
            if (before.item().isIncompatible()) {
                debugSwap("rejectIncompatible", "local", request.getSlot(), newItem,
                        request.getRequestId(), null, before, null);
                operationLogger.log(scope, request.getRequestId(), OperationType.SWAP,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", newItem.getItemId(), newItem.getCount(),
                        false, "INCOMPATIBLE");
                return rememberSwap(request, new SwapItemResponse(false, request.getSlot(), before.item(), null,
                        before.version(), "INCOMPATIBLE", request.getRequestId(), scope));
            }

            LocalItemStore.SwapResult result = localItemStore.swapItem(scope, request.getSlot(), newItem,
                    request.getExpectedItemId(), request.getExpectedVersion(),
                    request.getTakeCount(), request.isBoundedMerge(), request.getPlayerUuid());
            LocalItemStore.ItemRecord after = localItemStore.getItem(scope, request.getSlot());
            debugSwap("storeResult", "local", request.getSlot(), newItem,
                    request.getRequestId(), null, after, result);

            if (result.isSuccess()) {
                operationLogger.log(scope, request.getRequestId(), OperationType.SWAP,
                        request.getPlayerUuid(), request.getPlayerName(),
                        "local", newItem.getItemId(), newItem.getCount(),
                        true, null);
                return rememberSwap(request, new SwapItemResponse(true, request.getSlot(),
                        after != null ? after.item() : newItem,
                        result.getTakenItem(),
                        after != null ? after.version() : result.getNewVersion(),
                        null, request.getRequestId(), scope));
            }

            operationLogger.log(scope, request.getRequestId(), OperationType.SWAP,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local", newItem.getItemId(), newItem.getCount(),
                    false, result.getFailReason());
            LocalItemStore.ItemRecord current = localItemStore.getItem(scope, request.getSlot());
            return rememberSwap(request, new SwapItemResponse(false, request.getSlot(),
                    current != null ? current.item() : null,
                    null,
                    current != null ? current.version() : 0,
                    result.getFailReason(), request.getRequestId(), scope));
        } catch (Exception e) {
            debugSwap("exception", "local", request.getSlot(), request.getNewItem(),
                    request.getRequestId(), null, null, null, e);
            operationLogger.log(scope, request.getRequestId(), OperationType.SWAP,
                    request.getPlayerUuid(), request.getPlayerName(),
                    "local",
                    request.getNewItem() != null ? request.getNewItem().getItemId() : null,
                    request.getNewItem() != null ? request.getNewItem().getCount() : 0,
                    false, e.getMessage());
            return rememberSwap(request, new SwapItemResponse(false, request.getSlot(), null, null, 0,
                    "INTERNAL_ERROR", request.getRequestId(), scope));
        }
    }

    private PutItemResponse recentPut(InventoryScope scope, PutItemRequest request) {
        CompletedOp op = recentOps.get(ScopedRequestKey.of(scope, request.getRequestId()));
        if (op == null) return null;
        return new PutItemResponse(op.success(), request.getSlot(), op.currentItem(),
                op.failReason(), op.completedAt(), op.newVersion(), request.getRequestId(),
                op.scope() != null ? op.scope() : scope);
    }

    private TakeItemResponse recentTake(InventoryScope scope, TakeItemRequest request) {
        CompletedOp op = recentOps.get(ScopedRequestKey.of(scope, request.getRequestId()));
        if (op == null) return null;
        return new TakeItemResponse(op.success(), request.getSlot(), op.currentItem(),
                op.failReason(), op.completedAt(), op.newVersion(), op.takenItem(),
                request.getRequestId(), op.scope() != null ? op.scope() : scope);
    }

    private SwapItemResponse recentSwap(InventoryScope scope, SwapItemRequest request) {
        CompletedOp op = recentOps.get(ScopedRequestKey.of(scope, request.getRequestId()));
        if (op == null) return null;
        return new SwapItemResponse(op.success(), request.getSlot(), op.currentItem(),
                op.takenItem(), op.newVersion(), op.failReason(), request.getRequestId(),
                op.scope() != null ? op.scope() : scope);
    }

    private PutItemResponse rememberPut(PutItemRequest request, PutItemResponse response) {
        remember(request.getRequestId(), response.isSuccess(), response.getFailReason(),
                response.getCurrentItem(), null, response.getNewVersion(), response.getScope());
        return response;
    }

    private TakeItemResponse rememberTake(TakeItemRequest request, TakeItemResponse response) {
        remember(request.getRequestId(), response.isSuccess(), response.getFailReason(),
                response.getCurrentItem(), response.getItemsToGive(), response.getNewVersion(), response.getScope());
        return response;
    }

    private SwapItemResponse rememberSwap(SwapItemRequest request, SwapItemResponse response) {
        remember(request.getRequestId(), response.isSuccess(), response.getFailReason(),
                response.getCurrentItem(), response.getTakenItem(), response.getNewVersion(), response.getScope());
        return response;
    }

    private boolean isLocalTarget(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return false;
        }
        return "local".equalsIgnoreCase(serverName)
                || serverName.equalsIgnoreCase(runtimeHooks.localServerName());
    }

    private InventoryAccess normalizeAccess(InventoryAccess access) {
        return access != null ? access : InventoryAccess.server();
    }

    private boolean supportsRequestedAccess(Connection conn, InventoryAccess access) {
        return access == null || !access.isPlayer() || conn.supportsInventoryAccess();
    }

    private String unsupportedPlayerInventoryMessage() {
        return "目标服务器版本不支持玩家仓库，请升级 TheExchange";
    }

    private AccessResolution resolveAccess(InventoryAccess requestedAccess) {
        InventoryAccess access = normalizeAccess(requestedAccess);
        if (access.isServer()) {
            return AccessResolution.success(InventoryScope.server(), InventoryAccess.server());
        }
        if (access.ownerName() == null || access.ownerName().isBlank()) {
            return AccessResolution.fail(null, access, "玩家名称不能为空");
        }
        Optional<ExchangeAPI.PlayerIdentity> identity;
        try {
            identity = runtimeHooks.resolvePlayerIdentity(access.ownerName());
        } catch (Exception e) {
            return AccessResolution.fail(null, access, "玩家名称解析失败: " + e.getMessage());
        }
        if (identity == null || identity.isEmpty() || identity.get().getUuid() == null || identity.get().getUuid().isBlank()) {
            return AccessResolution.fail(null, access, "玩家不存在或无法解析");
        }
        ExchangeAPI.PlayerIdentity player = identity.get();
        InventoryScope scope = InventoryScope.player(player.getUuid());
        InventoryAccess resolvedAccess = InventoryAccess.player(player.getName(), access.password()).withResolvedScope(scope);
        if (playerInventoryAuthStore == null) {
            return AccessResolution.fail(scope, resolvedAccess, "玩家仓库认证未初始化");
        }
        PlayerInventoryAuthStore.AuthResult auth = playerInventoryAuthStore.verify(scope, player.getName(), access.password());
        return auth.success()
                ? AccessResolution.success(scope, resolvedAccess)
                : AccessResolution.fail(scope, resolvedAccess, auth.failReason());
    }

    private InventoryScope scopeFromAccess(InventoryAccess access) {
        InventoryScope scope = normalizeAccess(access).effectiveScope();
        return scope != null ? scope : InventoryScope.server();
    }

    private NeutralItem localSlotItem(InventoryScope scope, int slot) {
        LocalItemStore.ItemRecord record = localItemStore.getItem(scope, slot);
        return record != null ? record.item() : null;
    }

    private int localSlotVersion(InventoryScope scope, int slot) {
        LocalItemStore.ItemRecord record = localItemStore.getItem(scope, slot);
        return record != null ? record.version() : 0;
    }

    private void logRequester(boolean localLoopback, InventoryScope scope, String requestId, OperationType type,
                              String playerUuid, String playerName, String serverName,
                              String itemId, int count, boolean success, String failReason) {
        if (localLoopback) {
            return;
        }
        operationLogger.log(scope, requestId, type, playerUuid, playerName,
                serverName, itemId, count, success, failReason);
    }

    private void finishSuccessfulMutation(String serverName, InventoryScope scope, int slot, NeutralItem currentItem,
                                          int newVersion, boolean localLoopback) {
        if (localLoopback) {
            publishLocalInventoryUpdate(scope, List.of(slot));
            redrawOpenViews(serverName, scope);
            return;
        }
        cacheManager.updateCacheSlot(serverName, scope, slot, currentItem, newVersion);
        redrawOpenViews(serverName, scope);
    }

    private void finishFailedMutation(String serverName, InventoryScope scope, int slot, NeutralItem currentItem,
                                      int newVersion, boolean localLoopback) {
        if (!localLoopback) {
            cacheManager.updateCacheSlot(serverName, scope, slot, currentItem, newVersion);
        }
        redrawOpenViews(serverName, scope);
    }

    private void remember(String requestId, boolean success, String failReason,
                          NeutralItem currentItem, NeutralItem takenItem, int newVersion,
                          InventoryScope scope) {
        if (requestId == null || requestId.isBlank()) return;
        InventoryScope resolvedScope = scope != null ? scope : InventoryScope.server();
        recentOps.put(ScopedRequestKey.of(resolvedScope, requestId),
                new CompletedOp(System.currentTimeMillis(), success, failReason,
                        currentItem, takenItem, newVersion, resolvedScope));
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

    public void routeMessage(org.edtp.theexchange.network.Connection conn,
                              FrameType type, Object message) {
        switch (type) {
            case QUERY_TIMESTAMP, QUERY_ITEMS -> {}
            case QUERY_SLOT_VERSION -> {
                QuerySlotVersionRequest req = (QuerySlotVersionRequest) message;
                AccessResolution access = resolveAccess(req.getAccess());
                if (!access.success()) {
                    QuerySlotVersionResponse response = new QuerySlotVersionResponse(
                            req.getRequestId(), req.getSlot(), 0, access.scope());
                    response.setSuccess(false);
                    response.setFailReason(access.failReason());
                    conn.send(FrameType.SLOT_VERSION_RESPONSE, response);
                    return;
                }
                LocalItemStore.ItemRecord record = localItemStore.getItem(access.scope(), req.getSlot());
                int version = record != null ? record.version() : 0;
                conn.send(FrameType.SLOT_VERSION_RESPONSE,
                        new QuerySlotVersionResponse(req.getRequestId(), req.getSlot(), version, access.scope()));
            }
            case QUERY_SLOT_STATE -> {
                QuerySlotStateRequest req = (QuerySlotStateRequest) message;
                AccessResolution access = resolveAccess(req.getAccess());
                if (!access.success()) {
                    SlotStateResponse response = new SlotStateResponse(
                            req.getRequestId(), req.getSlot(), null, 0, access.scope());
                    response.setSuccess(false);
                    response.setFailReason(access.failReason());
                    conn.send(FrameType.SLOT_STATE_RESPONSE, response);
                    return;
                }
                LocalItemStore.ItemRecord record = localItemStore.getItem(access.scope(), req.getSlot());
                conn.send(FrameType.SLOT_STATE_RESPONSE,
                        new SlotStateResponse(req.getRequestId(), req.getSlot(),
                                record != null ? record.item() : null,
                                record != null ? record.version() : 0,
                                access.scope()));
            }
            case QUERY_SLOT_VERSIONS -> {
                QuerySlotVersionsRequest req = (QuerySlotVersionsRequest) message;
                AccessResolution access = resolveAccess(req.getAccess());
                if (!access.success()) {
                    SlotVersionsResponse response = new SlotVersionsResponse(req.getRequestId(), List.of(), access.scope());
                    response.setSuccess(false);
                    response.setFailReason(access.failReason());
                    conn.send(FrameType.SLOT_VERSIONS_RESPONSE, response);
                    return;
                }
                conn.send(FrameType.SLOT_VERSIONS_RESPONSE,
                        new SlotVersionsResponse(req.getRequestId(), localSlotVersions(access.scope()), access.scope()));
            }
            case QUERY_SLOTS -> {
                QuerySlotsRequest req = (QuerySlotsRequest) message;
                AccessResolution access = resolveAccess(req.getAccess());
                if (!access.success()) {
                    SlotsStateResponse response = new SlotsStateResponse(req.getRequestId(), List.of(), access.scope());
                    response.setSuccess(false);
                    response.setFailReason(access.failReason());
                    conn.send(FrameType.SLOTS_STATE_RESPONSE, response);
                    return;
                }
                java.util.ArrayList<SlotStateResponse> slots = new java.util.ArrayList<>();
                if (req.getSlots() != null) {
                    for (int slot : req.getSlots()) {
                        LocalItemStore.ItemRecord record = localItemStore.getItem(access.scope(), slot);
                        slots.add(new SlotStateResponse(req.getRequestId(), slot,
                                record != null ? record.item() : null,
                                record != null ? record.version() : 0,
                                access.scope()));
                    }
                }
                conn.send(FrameType.SLOTS_STATE_RESPONSE, new SlotsStateResponse(req.getRequestId(), slots, access.scope()));
            }
            case PUT_ITEM -> {
                PutItemResponse resp = handleRemotePut((PutItemRequest) message);
                conn.send(FrameType.PUT_ITEM_RESPONSE, resp);
                if (resp.isSuccess()) {
                    broadcastInventoryUpdate(conn, resp.getScope(), List.of(((PutItemRequest) message).getSlot()),
                            localItemStore.getLastModifiedTimestamp(resp.getScope()));
                    refreshOpenViews(sourceServerName(conn), resp.getScope());
                }
            }
            case TAKE_ITEM -> {
                TakeItemResponse resp = handleRemoteTake((TakeItemRequest) message);
                conn.send(FrameType.TAKE_ITEM_RESPONSE, resp);
                if (resp.isSuccess()) {
                    broadcastInventoryUpdate(conn, resp.getScope(), List.of(((TakeItemRequest) message).getSlot()),
                            localItemStore.getLastModifiedTimestamp(resp.getScope()));
                    refreshOpenViews(sourceServerName(conn), resp.getScope());
                }
            }
            case SWAP_ITEM -> {
                SwapItemResponse resp = handleRemoteSwap((SwapItemRequest) message);
                conn.send(FrameType.SWAP_ITEM_RESPONSE, resp);
                if (resp.isSuccess()) {
                    broadcastInventoryUpdate(conn, resp.getScope(), List.of(((SwapItemRequest) message).getSlot()),
                            localItemStore.getLastModifiedTimestamp(resp.getScope()));
                    refreshOpenViews(sourceServerName(conn), resp.getScope());
                }
            }
            case PUSH_UPDATE -> {
                PushUpdate update = (PushUpdate) message;
                if (update == null) return;
                final String sourceServerName = conn.getPeerServerName() != null
                        ? conn.getPeerServerName()
                        : conn.getRemoteName();
                runtimeHooks.runOnMainThread(() -> runtimeHooks.refreshInventoryView(sourceServerName, update.getScope()));
            }
            default -> {}
        }
    }

    private void refreshOpenViews(String serverName) {
        runtimeHooks.refreshRemoteInventoryView(serverName);
    }

    private void refreshOpenViews(String serverName, InventoryScope scope) {
        runtimeHooks.refreshInventoryView(serverName, scope);
    }

    private void redrawOpenViews(String serverName) {
        runtimeHooks.redrawRemoteInventoryView(serverName);
    }

    private void redrawOpenViews(String serverName, InventoryScope scope) {
        runtimeHooks.redrawInventoryView(serverName, scope);
    }

    private String sourceServerName(Connection conn) {
        return conn.getPeerServerName() != null ? conn.getPeerServerName() : conn.getRemoteName();
    }

    private ReentrantLock localSlotLock(InventoryScope scope, int slot) {
        return localSlotLocks.computeIfAbsent(
                new ScopeSlotKey(scope != null ? scope : InventoryScope.server(), slot),
                ignored -> new ReentrantLock());
    }

    private java.util.List<Integer> localSlotVersions(InventoryScope scope) {
        java.util.ArrayList<Integer> versions = new java.util.ArrayList<>();
        List<NeutralItem> items = localItemStore.getAllItems(scope);
        for (int slot = 0; slot < items.size(); slot++) {
            NeutralItem item = items.get(slot);
            versions.add(item != null ? item.getVersion() : 0);
        }
        return versions;
    }

    private void broadcastInventoryUpdate(Connection sourceConn, InventoryScope scope, List<Integer> changedSlots, long timestamp) {
        if (networkManager == null || changedSlots == null || changedSlots.isEmpty()) return;
        PushUpdate update = new PushUpdate(changedSlots, timestamp, scope);
        networkManager.broadcast(FrameType.PUSH_UPDATE, update, sourceConn);
    }

    public void publishLocalInventoryUpdate(List<Integer> changedSlots) {
        publishLocalInventoryUpdate(InventoryScope.server(), changedSlots);
    }

    public void publishLocalInventoryUpdate(InventoryScope scope, List<Integer> changedSlots) {
        long timestamp = localItemStore.getLastModifiedTimestamp(scope);
        broadcastInventoryUpdate(null, scope, changedSlots, timestamp);
        String localName = runtimeHooks.localServerName();
        runtimeHooks.runOnMainThread(() -> runtimeHooks.refreshInventoryView(localName, scope));
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
