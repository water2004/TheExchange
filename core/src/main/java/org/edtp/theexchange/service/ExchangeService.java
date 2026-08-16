package org.edtp.theexchange.service;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.OperationType;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.MutationHashes;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.storage.LocalItemStore;
import org.edtp.theexchange.storage.OperationLogger;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core business logic for item exchange operations.
 * Implements F-31 through F-40 concurrency and consistency requirements.
 */
public class ExchangeService {
    public static final String PLAYER_INVENTORIES_DISABLED = "玩家仓库功能已被服务器管理员关闭";
    private final NetworkManager networkManager;
    private final LocalItemStore localItemStore;
    private final OperationLogger operationLogger;
    public static final int INVENTORY_SLOT_COUNT = 54;

    private final PlayerInventorySessionManager playerInventorySessionManager;
    private final CacheManager cacheManager;
    private final SyncEngine syncEngine;
    private final RuntimeHooks runtimeHooks;
    private final long requestTimeoutMs;
    private final MutationTransactionCoordinator transactionCoordinator;
    private final ConcurrentHashMap<ScopeSlotKey, LocalSlotLock> localSlotLocks = new ConcurrentHashMap<>();

    public ExchangeService(NetworkManager networkManager, LocalItemStore localItemStore,
                           OperationLogger operationLogger, PlayerInventorySessionManager playerInventorySessionManager,
                           CacheManager cacheManager,
                           SyncEngine syncEngine, RuntimeHooks runtimeHooks, long requestTimeoutMs,
                           MutationTransactionCoordinator transactionCoordinator) {
        this.networkManager = networkManager;
        this.localItemStore = localItemStore;
        this.operationLogger = operationLogger;
        this.playerInventorySessionManager = playerInventorySessionManager;
        this.cacheManager = cacheManager;
        this.syncEngine = syncEngine;
        this.runtimeHooks = runtimeHooks;
        this.requestTimeoutMs = requestTimeoutMs;
        this.transactionCoordinator = transactionCoordinator;
    }

    public ExchangeService(NetworkManager networkManager, LocalItemStore localItemStore,
                           OperationLogger operationLogger, PlayerInventorySessionManager playerInventorySessionManager,
                           CacheManager cacheManager, SyncEngine syncEngine,
                           RuntimeHooks runtimeHooks, long requestTimeoutMs) {
        this(networkManager, localItemStore, operationLogger, playerInventorySessionManager,
                cacheManager, syncEngine, runtimeHooks, requestTimeoutMs,
                new MutationTransactionCoordinator(requestTimeoutMs, ignored -> {}));
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

        default boolean playerInventoriesEnabled() {
            return true;
        }
    }

    private record ScopeSlotKey(InventoryScope scope, int slot) {}


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

    public CompletableFuture<InventoryAccess> authenticatePlayerInventoryAsync(
            String serverName, String ownerName, String password,
            String requesterUuid, String requesterName) {
        if (!runtimeHooks.playerInventoriesEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException(PLAYER_INVENTORIES_DISABLED));
        }
        if (ownerName == null || ownerName.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("玩家名称不能为空"));
        }
        if (requesterUuid == null || requesterUuid.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("访问者 UUID 不能为空"));
        }
        PlayerInventoryAccessRequest request = new PlayerInventoryAccessRequest(
                UUID.randomUUID().toString(), ownerName, password, requesterUuid, requesterName);
        if (isLocalTarget(serverName)) {
            return CompletableFuture.completedFuture(accessFromResponse(
                    handlePlayerInventoryAccess(request, runtimeHooks.localServerName()),
                    requesterUuid, requesterName));
        }
        if (networkManager == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("网络功能未启用，请检查端口配置"));
        }
        Connection conn = networkManager.getConnection(serverName);
        if (conn == null || !conn.isRunning()) {
            return CompletableFuture.failedFuture(new IllegalStateException("目标服务器离线"));
        }
        return conn.<PlayerInventoryAccessResponse>sendAsync(
                        FrameType.PLAYER_INVENTORY_ACCESS, request,
                        FrameType.PLAYER_INVENTORY_ACCESS_RESPONSE, requestTimeoutMs)
                .thenApply(response -> accessFromResponse(response, requesterUuid, requesterName))
                .thenApply(access -> {
                    transactionCoordinator.refreshAccess(serverName, access);
                    return access;
                });
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
        if (!isValidSlot(slot)) {
            return CompletableFuture.completedFuture(PutResult.fail("INVALID_SLOT"));
        }
        InventoryAccess access = normalizeAccess(requestedAccess);
        if (playerInventoryDisabled(access)) {
            return CompletableFuture.completedFuture(PutResult.fail(PLAYER_INVENTORIES_DISABLED));
        }
        boolean localTarget = isLocalTarget(serverName);
        InventoryScope operationScope = access.effectiveScope();
        if (localTarget) {
            AccessResolution resolution = resolveAccess(access, runtimeHooks.localServerName());
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
        MutationExecute request = mutation(MutationKind.PUT, slot, item, null,
                expectedVersion, item.getCount(), false, playerUuid, playerName, access);
        InventoryAccess finalAccess = access;
        if (localTarget) {
            return executeMutationAsync(runtimeHooks.localServerName(), request)
                    .thenApply(response -> finishRemotePut(serverName, slot, playerUuid, playerName,
                            item, request.getTransactionId(), response, true, finalAccess, () -> {}));
        }
        return transactionCoordinator.execute(serverName, request)
                .thenApply(receipt -> finishRemotePut(serverName, slot, playerUuid, playerName,
                        item, request.getTransactionId(), receipt.result(), false, finalAccess,
                        receipt::acknowledgeSettlement));
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot, int requestCount,
                                                       String playerUuid, String playerName) {
        return takeItemAsync(serverName, slot, requestCount, playerUuid, playerName, InventoryAccess.server());
    }

    public CompletableFuture<TakeResult> takeItemAsync(String serverName, int slot, int requestCount,
                                                       String playerUuid, String playerName,
                                                       InventoryAccess requestedAccess) {
        InventoryAccess access = normalizeAccess(requestedAccess);
        if (playerInventoryDisabled(access)) {
            return CompletableFuture.completedFuture(TakeResult.fail(PLAYER_INVENTORIES_DISABLED));
        }
        if (!isValidSlot(slot)) {
            return CompletableFuture.completedFuture(TakeResult.fail("INVALID_SLOT"));
        }
        boolean localTarget = isLocalTarget(serverName);
        InventoryScope operationScope = access.effectiveScope();
        if (localTarget) {
            AccessResolution resolution = resolveAccess(access, runtimeHooks.localServerName());
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
        if (playerInventoryDisabled(access)) {
            return CompletableFuture.completedFuture(TakeResult.fail(PLAYER_INVENTORIES_DISABLED));
        }
        if (!isValidSlot(slot)) {
            return CompletableFuture.completedFuture(TakeResult.fail("INVALID_SLOT"));
        }
        boolean localTarget = isLocalTarget(serverName);
        if (localTarget) {
            AccessResolution resolution = resolveAccess(access, runtimeHooks.localServerName());
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
        }

        MutationExecute request = mutation(MutationKind.TAKE, slot, null, expectedItemId,
                expectedVersion, requestCount, false, playerUuid, playerName, access);
        InventoryAccess finalAccess = access;
        if (localTarget) {
            return executeMutationAsync(runtimeHooks.localServerName(), request)
                    .thenApply(response -> finishRemoteTake(serverName, slot, expectedItemId,
                            requestCount, playerUuid, playerName, request.getTransactionId(),
                            response, true, finalAccess, () -> {}));
        }
        return transactionCoordinator.execute(serverName, request)
                .thenApply(receipt -> finishRemoteTake(serverName, slot, expectedItemId,
                        requestCount, playerUuid, playerName, request.getTransactionId(),
                        receipt.result(), false, finalAccess, receipt::acknowledgeSettlement));
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
        if (!isValidSlot(slot)) {
            return CompletableFuture.completedFuture(SwapResult.fail("INVALID_SLOT"));
        }
        if (newItem.isIncompatible()) {
            return CompletableFuture.completedFuture(SwapResult.fail("不兼容物品禁止操作"));
        }
        InventoryAccess access = normalizeAccess(requestedAccess);
        if (playerInventoryDisabled(access)) {
            return CompletableFuture.completedFuture(SwapResult.fail(PLAYER_INVENTORIES_DISABLED));
        }
        boolean localTarget = isLocalTarget(serverName);
        InventoryScope operationScope = access.effectiveScope();
        if (localTarget) {
            AccessResolution resolution = resolveAccess(access, runtimeHooks.localServerName());
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
        MutationExecute request = mutation(MutationKind.SWAP, slot, newItem, expectedItemId,
                expectedVersion, takeCount, boundedMerge, playerUuid, playerName, access);
        InventoryAccess finalAccess = access;
        if (localTarget) {
            return executeMutationAsync(runtimeHooks.localServerName(), request)
                    .thenApply(response -> finishRemoteSwap(serverName, slot, playerUuid, playerName,
                            newItem, request.getTransactionId(), response, true, finalAccess, () -> {}));
        }
        return transactionCoordinator.execute(serverName, request)
                .thenApply(receipt -> finishRemoteSwap(serverName, slot, playerUuid, playerName,
                        newItem, request.getTransactionId(), receipt.result(), false, finalAccess,
                        receipt::acknowledgeSettlement));
    }

    private PutResult finishRemotePut(String serverName, int slot, String playerUuid,
                                      String playerName, NeutralItem item, String requestId,
                                      MutationResultMessage response,
                                      boolean localLoopback, InventoryAccess access,
                                      Runnable settlement) {
        InventoryScope scope = response != null ? response.getScope() : scopeFromAccess(access);
        if (response.isSuccess()) {
            logRequester(localLoopback, scope, requestId, OperationType.PUT, playerUuid, playerName,
                    serverName, item.getItemId(), item.getCount(), true, null);
            finishSuccessfulMutation(serverName, scope, slot, response.getCurrentItem(),
                    response.getNewVersion(), localLoopback);
            return PutResult.success(response.getCurrentItem(), settlement);
        }

        logRequester(localLoopback, scope, requestId, OperationType.PUT, playerUuid, playerName,
                serverName, item.getItemId(), item.getCount(), false, response.getFailReason());
        finishFailedMutation(serverName, scope, slot, response.getCurrentItem(),
                response.getNewVersion(), localLoopback);
        return PutResult.fail(response.getFailReason(), settlement);
    }

    private TakeResult finishRemoteTake(String serverName, int slot, String expectedItemId,
                                        int requestCount, String playerUuid, String playerName,
                                        String requestId, MutationResultMessage response,
                                        boolean localLoopback, InventoryAccess access,
                                        Runnable settlement) {
        InventoryScope scope = response != null ? response.getScope() : scopeFromAccess(access);
        if (response.isSuccess()) {
            if (response.getTransferredItem() == null || response.getTransferredItem().isEmpty()
                    || response.getTransferredItem().isIncompatible()) {
                logRequester(localLoopback, scope, requestId, OperationType.TAKE, playerUuid, playerName,
                        serverName, expectedItemId, requestCount, false, "INCOMPATIBLE");
                return TakeResult.fail("不兼容物品禁止操作", settlement);
            }
            logRequester(localLoopback, scope, requestId, OperationType.TAKE, playerUuid, playerName,
                    serverName, expectedItemId, requestCount, true, null);
            finishSuccessfulMutation(serverName, scope, slot, response.getCurrentItem(),
                    response.getNewVersion(), localLoopback);
            return TakeResult.success(response.getTransferredItem(), response.getNewVersion(), settlement);
        }

        logRequester(localLoopback, scope, requestId, OperationType.TAKE, playerUuid, playerName,
                serverName, expectedItemId, requestCount, false, response.getFailReason());
        finishFailedMutation(serverName, scope, slot, response.getCurrentItem(),
                response.getNewVersion(), localLoopback);
        return TakeResult.fail(response.getFailReason(), settlement);
    }

    private SwapResult finishRemoteSwap(String serverName, int slot, String playerUuid,
                                        String playerName, NeutralItem newItem, String requestId,
                                        MutationResultMessage response,
                                        boolean localLoopback, InventoryAccess access,
                                        Runnable settlement) {
        InventoryScope scope = response != null ? response.getScope() : scopeFromAccess(access);
        if (response.isSuccess()) {
            if (response.getTransferredItem() != null && !response.getTransferredItem().isEmpty()
                    && response.getTransferredItem().isIncompatible()) {
                logRequester(localLoopback, scope, requestId, OperationType.SWAP, playerUuid, playerName,
                        serverName, newItem.getItemId(), newItem.getCount(), false, "INCOMPATIBLE");
                return SwapResult.fail("不兼容物品禁止操作", settlement);
            }
            logRequester(localLoopback, scope, requestId, OperationType.SWAP, playerUuid, playerName,
                    serverName, newItem.getItemId(), newItem.getCount(), true, null);
            finishSuccessfulMutation(serverName, scope, slot, response.getCurrentItem(),
                    response.getNewVersion(), localLoopback);
            return SwapResult.success(response.getTransferredItem(), response.getNewVersion(), settlement);
        }

        logRequester(localLoopback, scope, requestId, OperationType.SWAP, playerUuid, playerName,
                serverName, newItem.getItemId(), newItem.getCount(), false, response.getFailReason());
        finishFailedMutation(serverName, scope, slot, response.getCurrentItem(),
                response.getNewVersion(), localLoopback);
        return SwapResult.fail(response.getFailReason(), settlement);
    }


    private MutationExecute mutation(MutationKind kind, int slot, NeutralItem offeredItem,
                                     String expectedItemId, int expectedVersion, int count,
                                     boolean boundedMerge, String playerUuid, String playerName,
                                     InventoryAccess access) {
        MutationExecute request = new MutationExecute(UUID.randomUUID().toString(), null, kind,
                slot, offeredItem, expectedItemId, expectedVersion, count, boundedMerge,
                playerUuid, playerName, access);
        request.setIntentHash(MutationHashes.intent(request));
        return request;
    }

    private CompletableFuture<MutationResultMessage> executeMutationAsync(
            String peerId, MutationExecute request) {
        long generation = runtimeHooks.currentGeneration();
        return runtimeHooks.submitIfGeneration(generation, () -> executeMutation(peerId, request));
    }

    MutationResultMessage handleMutation(MutationExecute request, String peerId) {
        if (request == null || !MutationHashes.validIntent(request)) {
            throw new IllegalArgumentException("Invalid mutation intent");
        }
        return executeMutation(peerId, request);
    }

    private MutationResultMessage executeMutation(String peerId, MutationExecute request) {
        AccessResolution resolution = resolveAccess(request.getAccess(), peerId);
        if (!resolution.success()) {
            return mutationResult(request, false, null, null, resolution.failReason(),
                    0, 0, resolution.scope());
        }
        InventoryScope scope = resolution.scope();
        request.setAccess(resolution.access());
        if (!isValidSlot(request.getSlot())) {
            return mutationResult(request, false, null, null, "INVALID_SLOT", 0, 0, scope);
        }
        LocalSlotLockHandle slotLock = acquireLocalSlotLock(scope, request.getSlot());
        try {
            return switch (request.getKind()) {
                case PUT -> executePut(scope, request);
                case TAKE -> executeTake(scope, request);
                case SWAP -> executeSwap(scope, request);
            };
        } catch (Exception error) {
            NeutralItem offered = request.getOfferedItem();
            operationLogger.log(scope, request.getTransactionId(), operationType(request.getKind()),
                    request.getPlayerUuid(), request.getPlayerName(), "local",
                    offered != null ? offered.getItemId() : request.getExpectedItemId(),
                    offered != null ? offered.getCount() : request.getCount(), false,
                    error.getMessage());
            return mutationResult(request, false, null, null, "INTERNAL_ERROR", 0, 0, scope);
        } finally {
            releaseLocalSlotLock(slotLock);
        }
    }

    private MutationResultMessage executePut(InventoryScope scope, MutationExecute request) {
        NeutralItem item = request.getOfferedItem();
        if (item == null || item.isEmpty()) {
            return loggedFailure(scope, request, "ITEM_EMPTY",
                    localItemStore.getItem(scope, request.getSlot()));
        }
        LocalItemStore.PutResult stored = localItemStore.putItem(scope, request.getSlot(), item,
                request.getExpectedVersion(), request.getPlayerUuid());
        LocalItemStore.ItemRecord current = localItemStore.getItem(scope, request.getSlot());
        operationLogger.log(scope, request.getTransactionId(), OperationType.PUT,
                request.getPlayerUuid(), request.getPlayerName(), "local",
                item.getItemId(), item.getCount(), stored.isSuccess(), stored.getFailReason());
        return mutationResult(request, stored.isSuccess(), current != null ? current.item() : null, null,
                stored.getFailReason(), localItemStore.getLastModifiedTimestamp(scope),
                current != null ? current.version() : Math.max(0, stored.getNewVersion()), scope);
    }

    private MutationResultMessage executeTake(InventoryScope scope, MutationExecute request) {
        LocalItemStore.ItemRecord before = localItemStore.getItem(scope, request.getSlot());
        if (before == null || before.item() == null || before.item().isEmpty()) {
            return loggedFailure(scope, request, "ITEM_NOT_FOUND", before);
        }
        if (before.item().isIncompatible()) {
            return loggedFailure(scope, request, "INCOMPATIBLE", before);
        }
        LocalItemStore.TakeResult stored = localItemStore.takeItem(scope, request.getSlot(),
                request.getExpectedItemId(), request.getExpectedVersion(), request.getCount());
        LocalItemStore.ItemRecord current = localItemStore.getItem(scope, request.getSlot());
        operationLogger.log(scope, request.getTransactionId(), OperationType.TAKE,
                request.getPlayerUuid(), request.getPlayerName(), "local",
                request.getExpectedItemId(), request.getCount(), stored.isSuccess(), stored.getFailReason());
        return mutationResult(request, stored.isSuccess(), current != null ? current.item() : null,
                stored.isSuccess() ? stored.getItem() : null, stored.getFailReason(),
                localItemStore.getLastModifiedTimestamp(scope),
                current != null ? current.version() : Math.max(0, stored.getNewVersion()), scope);
    }

    private MutationResultMessage executeSwap(InventoryScope scope, MutationExecute request) {
        NeutralItem offered = request.getOfferedItem();
        LocalItemStore.ItemRecord before = localItemStore.getItem(scope, request.getSlot());
        if (offered == null || offered.isEmpty()) {
            return loggedFailure(scope, request, "ITEM_EMPTY", before);
        }
        if (before == null || before.item() == null || before.item().isEmpty()) {
            return loggedFailure(scope, request, "ITEM_NOT_FOUND", before);
        }
        if (before.item().isIncompatible()) {
            return loggedFailure(scope, request, "INCOMPATIBLE", before);
        }
        LocalItemStore.SwapResult stored = localItemStore.swapItem(scope, request.getSlot(), offered,
                request.getExpectedItemId(), request.getExpectedVersion(), request.getCount(),
                request.isBoundedMerge(), request.getPlayerUuid());
        LocalItemStore.ItemRecord current = localItemStore.getItem(scope, request.getSlot());
        operationLogger.log(scope, request.getTransactionId(), OperationType.SWAP,
                request.getPlayerUuid(), request.getPlayerName(), "local",
                offered.getItemId(), offered.getCount(), stored.isSuccess(), stored.getFailReason());
        return mutationResult(request, stored.isSuccess(), current != null ? current.item() : null,
                stored.isSuccess() ? stored.getTakenItem() : null, stored.getFailReason(),
                localItemStore.getLastModifiedTimestamp(scope),
                current != null ? current.version() : Math.max(0, stored.getNewVersion()), scope);
    }

    private MutationResultMessage loggedFailure(InventoryScope scope, MutationExecute request,
                                                String reason, LocalItemStore.ItemRecord current) {
        NeutralItem offered = request.getOfferedItem();
        operationLogger.log(scope, request.getTransactionId(), operationType(request.getKind()),
                request.getPlayerUuid(), request.getPlayerName(), "local",
                offered != null ? offered.getItemId() : request.getExpectedItemId(),
                offered != null ? offered.getCount() : request.getCount(), false, reason);
        return mutationResult(request, false, current != null ? current.item() : null, null, reason,
                localItemStore.getLastModifiedTimestamp(scope),
                current != null ? current.version() : 0, scope);
    }

    private MutationResultMessage mutationResult(MutationExecute request, boolean success,
                                                 NeutralItem currentItem, NeutralItem transferredItem,
                                                 String failReason, long timestamp, int version,
                                                 InventoryScope scope) {
        MutationResultMessage result = new MutationResultMessage(request.getTransactionId(),
                request.getIntentHash(), null, request.getKind(), success, request.getSlot(),
                currentItem, transferredItem, failReason, timestamp, version, scope);
        result.setResultHash(MutationHashes.result(result));
        return result;
    }

    private OperationType operationType(MutationKind kind) {
        return switch (kind) {
            case PUT -> OperationType.PUT;
            case TAKE -> OperationType.TAKE;
            case SWAP -> OperationType.SWAP;
        };
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

    private boolean playerInventoryDisabled(InventoryAccess access) {
        return access != null && access.isPlayer() && !runtimeHooks.playerInventoriesEnabled();
    }

    public PlayerInventoryAccessResponse handlePlayerInventoryAccess(
            PlayerInventoryAccessRequest request, String peerId) {
        if (request == null) {
            return PlayerInventoryAccessResponse.fail(null, "玩家仓库访问请求为空", 0);
        }
        if (!runtimeHooks.playerInventoriesEnabled()) {
            return PlayerInventoryAccessResponse.fail(
                    request.getRequestId(), PLAYER_INVENTORIES_DISABLED, 0);
        }
        if (playerInventorySessionManager == null) {
            return PlayerInventoryAccessResponse.fail(request.getRequestId(), "玩家仓库认证未初始化", 0);
        }
        if (request.getOwnerName() == null || request.getOwnerName().isBlank()) {
            return PlayerInventoryAccessResponse.fail(request.getRequestId(), "玩家名称不能为空", 0);
        }
        if (request.getOwnerName().length() > 64 || request.getRequesterUuid() == null
                || request.getRequesterUuid().isBlank() || request.getRequesterUuid().length() > 128
                || request.getPassword() == null || request.getPassword().length() > 256) {
            return PlayerInventoryAccessResponse.fail(request.getRequestId(), "玩家仓库访问参数无效", 0);
        }
        Optional<ExchangeAPI.PlayerIdentity> identity;
        try {
            identity = runtimeHooks.resolvePlayerIdentity(request.getOwnerName());
        } catch (Exception e) {
            return PlayerInventoryAccessResponse.fail(request.getRequestId(),
                    "玩家名称解析失败: " + e.getMessage(), 0);
        }
        if (identity == null || identity.isEmpty() || identity.get().getUuid() == null
                || identity.get().getUuid().isBlank()) {
            return PlayerInventoryAccessResponse.fail(request.getRequestId(), "玩家不存在或无法解析", 0);
        }
        ExchangeAPI.PlayerIdentity owner = identity.get();
        InventoryScope scope = InventoryScope.player(owner.getUuid());
        PlayerInventorySessionManager.SessionResult result = playerInventorySessionManager.authenticate(
                scope, owner.getName(), request.getPassword(),
                new PlayerInventorySessionManager.AccessPrincipal(peerId, request.getRequesterUuid()));
        if (!result.success()) {
            return PlayerInventoryAccessResponse.fail(request.getRequestId(),
                    result.failReason(), result.lockedUntil());
        }
        return PlayerInventoryAccessResponse.success(request.getRequestId(), result.ownerName(),
                result.token(), result.scope(), result.expiresAt(),
                playerInventorySessionManager.sessionTtlMillis());
    }

    private AccessResolution resolveAccess(InventoryAccess requestedAccess, String peerId) {
        InventoryAccess access = normalizeAccess(requestedAccess);
        if (access.isServer()) {
            return AccessResolution.success(InventoryScope.server(), InventoryAccess.server());
        }
        if (!runtimeHooks.playerInventoriesEnabled()) {
            return AccessResolution.fail(null, access, PLAYER_INVENTORIES_DISABLED);
        }
        if (playerInventorySessionManager == null) {
            return AccessResolution.fail(null, access, "玩家仓库认证未初始化");
        }
        if (!access.hasToken() || access.requesterUuid().isBlank()) {
            return AccessResolution.fail(null, access, "需要玩家仓库密码");
        }
        PlayerInventorySessionManager.SessionResult session =
                playerInventorySessionManager.validateAndRefresh(access.token(),
                        new PlayerInventorySessionManager.AccessPrincipal(peerId, access.requesterUuid()));
        if (!session.success()) {
            return AccessResolution.fail(null, access, session.failReason());
        }
        InventoryAccess resolvedAccess = InventoryAccess.playerSession(
                session.ownerName(), access.token(), access.requesterUuid(), access.requesterName(),
                session.scope(), session.expiresAt(), access.sessionTtlMillis());
        return AccessResolution.success(session.scope(), resolvedAccess);
    }

    private InventoryAccess accessFromResponse(PlayerInventoryAccessResponse response,
                                               String requesterUuid, String requesterName) {
        if (response == null) {
            throw new IllegalStateException("玩家仓库认证请求超时");
        }
        if (!response.isSuccess()) {
            throw new IllegalArgumentException(response.getFailReason() != null
                    ? response.getFailReason() : "玩家仓库认证失败");
        }
        if (response.getScope() == null || !response.getScope().isPlayer()
                || response.getToken() == null || response.getToken().isBlank()) {
            throw new IllegalStateException("玩家仓库认证响应无效");
        }
        return InventoryAccess.playerSession(response.getOwnerName(), response.getToken(),
                requesterUuid, requesterName, response.getScope(), response.getExpiresAt(),
                response.getSessionTtlMillis());
    }

    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < INVENTORY_SLOT_COUNT;
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


    // ========== Message routing ==========

    public void routeMessage(org.edtp.theexchange.network.Connection conn,
                              FrameType type, Object message) {
        String peerId = sourceServerName(conn);
        if (type != null && type.isMutationLifecycle()) {
            transactionCoordinator.route(conn, type, message, (sourcePeer, request) ->
                    executeMutationAsync(sourcePeer, request).thenApply(result -> {
                        if (result.isSuccess()) {
                            broadcastInventoryUpdate(conn, result.getScope(), List.of(result.getSlot()),
                                    result.getNewTimestamp());
                            refreshOpenViews(sourcePeer, result.getScope());
                        }
                        return result;
                    }));
            return;
        }
        switch (type) {
            case QUERY_TIMESTAMP, QUERY_ITEMS -> {}
            case PLAYER_INVENTORY_ACCESS -> conn.send(
                    FrameType.PLAYER_INVENTORY_ACCESS_RESPONSE,
                    handlePlayerInventoryAccess((PlayerInventoryAccessRequest) message, peerId));
            case QUERY_SLOT_VERSION -> {
                QuerySlotVersionRequest req = (QuerySlotVersionRequest) message;
                if (!isValidSlot(req.getSlot())) {
                    QuerySlotVersionResponse response = new QuerySlotVersionResponse(
                            req.getRequestId(), req.getSlot(), 0, InventoryScope.server());
                    response.setSuccess(false);
                    response.setFailReason("INVALID_SLOT");
                    conn.send(FrameType.SLOT_VERSION_RESPONSE, response);
                    return;
                }
                AccessResolution access = resolveAccess(req.getAccess(), peerId);
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
                if (!isValidSlot(req.getSlot())) {
                    SlotStateResponse response = new SlotStateResponse(
                            req.getRequestId(), req.getSlot(), null, 0, InventoryScope.server());
                    response.setSuccess(false);
                    response.setFailReason("INVALID_SLOT");
                    conn.send(FrameType.SLOT_STATE_RESPONSE, response);
                    return;
                }
                AccessResolution access = resolveAccess(req.getAccess(), peerId);
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
                AccessResolution access = resolveAccess(req.getAccess(), peerId);
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
                if (req.getSlots() != null
                        && req.getSlots().stream().anyMatch(slot -> slot == null || !isValidSlot(slot))) {
                    SlotsStateResponse response = new SlotsStateResponse(
                            req.getRequestId(), List.of(), InventoryScope.server());
                    response.setSuccess(false);
                    response.setFailReason("INVALID_SLOT");
                    conn.send(FrameType.SLOTS_STATE_RESPONSE, response);
                    return;
                }
                AccessResolution access = resolveAccess(req.getAccess(), peerId);
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

    private LocalSlotLockHandle acquireLocalSlotLock(InventoryScope scope, int slot) {
        ScopeSlotKey key = new ScopeSlotKey(scope != null ? scope : InventoryScope.server(), slot);
        LocalSlotLock slotLock = localSlotLocks.compute(key, (ignored, current) -> {
            LocalSlotLock retained = current != null ? current : new LocalSlotLock();
            retained.users++;
            return retained;
        });
        slotLock.lock.lock();
        return new LocalSlotLockHandle(key, slotLock);
    }

    private void releaseLocalSlotLock(LocalSlotLockHandle handle) {
        handle.slotLock().lock.unlock();
        localSlotLocks.compute(handle.key(), (ignored, current) -> {
            if (current != handle.slotLock()) {
                throw new IllegalStateException("Local slot lock lifecycle mismatch");
            }
            current.users--;
            return current.users == 0 ? null : current;
        });
    }

    private static final class LocalSlotLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    private record LocalSlotLockHandle(ScopeSlotKey key, LocalSlotLock slotLock) {
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
        networkManager.broadcast(FrameType.PUSH_UPDATE, update, sourceConn,
                connection -> canReceiveInventoryUpdate(sourceServerName(connection), scope));
    }

    boolean canReceiveInventoryUpdate(String peerId, InventoryScope scope) {
        if (scope == null || scope.isServer()) {
            return true;
        }
        return runtimeHooks.playerInventoriesEnabled()
                && playerInventorySessionManager != null
                && playerInventorySessionManager.hasActiveSession(peerId, scope);
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
        private final Runnable settlement;

        private PutResult(boolean success, String failReason, NeutralItem currentItem, Runnable settlement) {
            this.success = success;
            this.failReason = failReason;
            this.currentItem = currentItem;
            this.settlement = settlement != null ? settlement : () -> {};
        }

        public static PutResult success(NeutralItem currentItem) {
            return success(currentItem, () -> {});
        }

        public static PutResult success(NeutralItem currentItem, Runnable settlement) {
            return new PutResult(true, null, currentItem, settlement);
        }

        public static PutResult fail(String reason) {
            return fail(reason, () -> {});
        }

        public static PutResult fail(String reason, Runnable settlement) {
            return new PutResult(false, reason, null, settlement);
        }

        public boolean isSuccess() { return success; }
        public String getFailReason() { return failReason; }
        public NeutralItem getCurrentItem() { return currentItem; }
        public void acknowledgeSettlement() { settlement.run(); }
    }

    public static class TakeResult {
        private final boolean success;
        private final String failReason;
        private final NeutralItem itemsToGive;
        private final int newVersion;
        private final Runnable settlement;

        private TakeResult(boolean success, String failReason, NeutralItem itemsToGive, int newVersion,
                           Runnable settlement) {
            this.success = success;
            this.failReason = failReason;
            this.itemsToGive = itemsToGive;
            this.newVersion = newVersion;
            this.settlement = settlement != null ? settlement : () -> {};
        }

        public static TakeResult success(NeutralItem itemsToGive, int newVersion) {
            return success(itemsToGive, newVersion, () -> {});
        }

        public static TakeResult success(NeutralItem itemsToGive, int newVersion, Runnable settlement) {
            return new TakeResult(true, null, itemsToGive, newVersion, settlement);
        }

        public static TakeResult fail(String reason) {
            return fail(reason, () -> {});
        }

        public static TakeResult fail(String reason, Runnable settlement) {
            return new TakeResult(false, reason, null, -1, settlement);
        }

        public boolean isSuccess() { return success; }
        public String getFailReason() { return failReason; }
        public NeutralItem getItemsToGive() { return itemsToGive; }
        public int getNewVersion() { return newVersion; }
        public void acknowledgeSettlement() { settlement.run(); }
    }

    public static class SwapResult {
        private final boolean success;
        private final String failReason;
        private final NeutralItem takenItem;
        private final int newVersion;
        private final Runnable settlement;

        private SwapResult(boolean success, String failReason, NeutralItem takenItem, int newVersion,
                           Runnable settlement) {
            this.success = success;
            this.failReason = failReason;
            this.takenItem = takenItem;
            this.newVersion = newVersion;
            this.settlement = settlement != null ? settlement : () -> {};
        }

        public static SwapResult success(NeutralItem takenItem, int newVersion) {
            return success(takenItem, newVersion, () -> {});
        }

        public static SwapResult success(NeutralItem takenItem, int newVersion, Runnable settlement) {
            return new SwapResult(true, null, takenItem, newVersion, settlement);
        }

        public static SwapResult fail(String reason) {
            return fail(reason, () -> {});
        }

        public static SwapResult fail(String reason, Runnable settlement) {
            return new SwapResult(false, reason, null, -1, settlement);
        }

        public boolean isSuccess() { return success; }
        public String getFailReason() { return failReason; }
        public NeutralItem getTakenItem() { return takenItem; }
        public int getNewVersion() { return newVersion; }
        public void acknowledgeSettlement() { settlement.run(); }
    }
}
