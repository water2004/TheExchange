package org.edtp.theexchange.service;

import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.QuerySlotStateRequest;
import org.edtp.theexchange.network.protocol.messages.QuerySlotVersionRequest;
import org.edtp.theexchange.network.protocol.messages.QuerySlotVersionResponse;
import org.edtp.theexchange.network.protocol.messages.QuerySlotVersionsRequest;
import org.edtp.theexchange.network.protocol.messages.QuerySlotsRequest;
import org.edtp.theexchange.network.protocol.messages.SlotStateResponse;
import org.edtp.theexchange.network.protocol.messages.SlotVersionsResponse;
import org.edtp.theexchange.network.protocol.messages.SlotsStateResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

public class SyncEngine {

    private final NetworkManager networkManager;
    private final CacheManager cacheManager;
    private final CompatibilityChecker compatibilityChecker;
    private final long requestTimeoutMs;
    private final BooleanSupplier playerInventoriesEnabled;

    public SyncEngine(NetworkManager networkManager, CacheManager cacheManager,
                      CompatibilityChecker compatibilityChecker, long requestTimeoutMs) {
        this(networkManager, cacheManager, compatibilityChecker, requestTimeoutMs, () -> true);
    }

    public SyncEngine(NetworkManager networkManager, CacheManager cacheManager,
                      CompatibilityChecker compatibilityChecker, long requestTimeoutMs,
                      BooleanSupplier playerInventoriesEnabled) {
        this.networkManager = networkManager;
        this.cacheManager = cacheManager;
        this.compatibilityChecker = compatibilityChecker;
        this.requestTimeoutMs = requestTimeoutMs;
        this.playerInventoriesEnabled = playerInventoriesEnabled != null
                ? playerInventoriesEnabled : () -> true;
    }

    public CompletableFuture<Void> refreshChangedSlotsAsync(String serverName) {
        return refreshChangedSlotsAsync(serverName, InventoryAccess.server()).thenApply(ignored -> null);
    }

    public CompletableFuture<InventoryScope> refreshChangedSlotsAsync(String serverName, InventoryAccess access) {
        InventoryAccess requestAccess = access != null ? access : InventoryAccess.server();
        if (playerInventoryDisabled(requestAccess)) {
            return disabledFuture();
        }
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(scopeOrServer(requestAccess));
        }
        return timeoutAsNull(conn.<SlotVersionsResponse>sendAsync(
                        FrameType.QUERY_SLOT_VERSIONS, new QuerySlotVersionsRequest(null, requestAccess),
                        FrameType.SLOT_VERSIONS_RESPONSE, requestTimeoutMs))
                .thenCompose(response -> {
                    if (response == null) {
                        return CompletableFuture.completedFuture(scopeOrServer(requestAccess));
                    }
                    if (!response.isSuccess()) {
                        return CompletableFuture.failedFuture(new IllegalStateException(response.getFailReason()));
                    }
                    InventoryScope scope = response.getScope();
                    List<Integer> remoteVersions = response != null ? response.getVersions() : List.of();
                    List<Integer> changed = cacheManager.changedSlots(serverName, scope, remoteVersions);
                    if (changed.isEmpty()) {
                        return CompletableFuture.completedFuture(scope);
                    }
                    return querySlotsAsync(serverName, changed, requestAccess.withResolvedScope(scope)).thenApply(ignored -> scope);
                });
    }

    public CompletableFuture<Void> querySlotsAsync(String serverName, List<Integer> slots) {
        return querySlotsAsync(serverName, slots, InventoryAccess.server());
    }

    public CompletableFuture<Void> querySlotsAsync(String serverName, List<Integer> slots, InventoryAccess access) {
        InventoryAccess requestAccess = access != null ? access : InventoryAccess.server();
        if (playerInventoryDisabled(requestAccess)) {
            return disabledFuture();
        }
        if (slots == null || slots.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(null);
        }
        return timeoutAsNull(conn.<SlotsStateResponse>sendAsync(
                FrameType.QUERY_SLOTS, new QuerySlotsRequest(null, slots, requestAccess),
                FrameType.SLOTS_STATE_RESPONSE, requestTimeoutMs)).thenAccept(response -> {
            if (response == null) {
                return;
            }
            if (!response.isSuccess()) {
                throw new IllegalStateException(response.getFailReason());
            }
            applySlotStates(serverName, response.getScope(), response.getSlots());
        });
    }

    public CompletableFuture<Integer> querySlotVersionAsync(String serverName, int slot) {
        return querySlotVersionAsync(serverName, slot, InventoryAccess.server());
    }

    public CompletableFuture<Integer> querySlotVersionAsync(String serverName, int slot, InventoryAccess access) {
        InventoryAccess requestAccess = access != null ? access : InventoryAccess.server();
        if (playerInventoryDisabled(requestAccess)) {
            return disabledFuture();
        }
        InventoryScope cachedScope = scopeOrServer(requestAccess);
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(cacheManager.getSlotVersion(serverName, cachedScope, slot));
        }
        return timeoutAsNull(conn.<QuerySlotVersionResponse>sendAsync(
                FrameType.QUERY_SLOT_VERSION, new QuerySlotVersionRequest(null, slot, requestAccess),
                FrameType.SLOT_VERSION_RESPONSE, requestTimeoutMs)).thenApply(response -> {
            if (response == null) {
                return cacheManager.getSlotVersion(serverName, cachedScope, slot);
            }
            if (!response.isSuccess()) {
                throw new IllegalStateException(response.getFailReason());
            }
            return response.getVersion();
        });
    }

    public CompletableFuture<Void> querySlotStateAsync(String serverName, int slot) {
        return querySlotStateAsync(serverName, slot, InventoryAccess.server());
    }

    public CompletableFuture<Void> querySlotStateAsync(String serverName, int slot, InventoryAccess access) {
        InventoryAccess requestAccess = access != null ? access : InventoryAccess.server();
        if (playerInventoryDisabled(requestAccess)) {
            return disabledFuture();
        }
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(null);
        }
        return timeoutAsNull(conn.<SlotStateResponse>sendAsync(
                FrameType.QUERY_SLOT_STATE, new QuerySlotStateRequest(null, slot, requestAccess),
                FrameType.SLOT_STATE_RESPONSE, requestTimeoutMs)).thenAccept(response -> {
            if (response != null) {
                if (!response.isSuccess()) {
                    throw new IllegalStateException(response.getFailReason());
                }
                applySlotStates(serverName, response.getScope(), List.of(response));
            }
        });
    }

    private void applySlotStates(String serverName, InventoryScope scope, List<SlotStateResponse> slots) {
        if (slots == null || slots.isEmpty()) {
            return;
        }
        for (SlotStateResponse slot : slots) {
            if (slot != null && slot.getItem() != null && compatibilityChecker != null) {
                compatibilityChecker.checkAndMark(slot.getItem());
            }
        }
        cacheManager.updateCacheSlots(serverName, scope != null ? scope : InventoryScope.server(), slots);
    }

    private static <T> CompletableFuture<T> timeoutAsNull(CompletableFuture<T> future) {
        return future.exceptionally(error -> {
            if (isTimeout(error)) {
                return null;
            }
            if (error instanceof CompletionException completionException) {
                throw completionException;
            }
            throw new CompletionException(error);
        });
    }

    private static boolean isTimeout(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof TimeoutException;
    }

    private InventoryScope scopeOrServer(InventoryAccess access) {
        InventoryScope scope = access != null ? access.effectiveScope() : null;
        return scope != null ? scope : InventoryScope.server();
    }

    private Connection getConnection(String serverName) {
        if (networkManager == null) {
            return null;
        }
        return networkManager.getConnection(serverName);
    }

    private boolean playerInventoryDisabled(InventoryAccess access) {
        return access != null && access.isPlayer() && !playerInventoriesEnabled.getAsBoolean();
    }

    private static <T> CompletableFuture<T> disabledFuture() {
        return CompletableFuture.failedFuture(
                new IllegalStateException(ExchangeService.PLAYER_INVENTORIES_DISABLED));
    }

}
