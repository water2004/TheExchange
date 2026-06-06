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

    public CompletableFuture<Void> refreshChangedSlotsAsync(String serverName) {
        return refreshChangedSlotsAsync(serverName, InventoryAccess.server()).thenApply(ignored -> null);
    }

    public CompletableFuture<InventoryScope> refreshChangedSlotsAsync(String serverName, InventoryAccess access) {
        InventoryAccess requestAccess = access != null ? access : InventoryAccess.server();
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(scopeOrServer(requestAccess));
        }
        if (!supportsRequestedAccess(conn, requestAccess)) {
            return CompletableFuture.failedFuture(new IllegalStateException(unsupportedPlayerInventoryMessage()));
        }
        return conn.<SlotVersionsResponse>sendAsync(
                        FrameType.QUERY_SLOT_VERSIONS, new QuerySlotVersionsRequest(null, requestAccess),
                        FrameType.SLOT_VERSIONS_RESPONSE, SYNC_TIMEOUT_MS)
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
        if (slots == null || slots.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        InventoryAccess requestAccess = access != null ? access : InventoryAccess.server();
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (!supportsRequestedAccess(conn, requestAccess)) {
            return CompletableFuture.failedFuture(new IllegalStateException(unsupportedPlayerInventoryMessage()));
        }
        return conn.<SlotsStateResponse>sendAsync(
                FrameType.QUERY_SLOTS, new QuerySlotsRequest(null, slots, requestAccess),
                FrameType.SLOTS_STATE_RESPONSE, SYNC_TIMEOUT_MS).thenAccept(response -> {
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
        InventoryScope cachedScope = scopeOrServer(requestAccess);
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(cacheManager.getSlotVersion(serverName, cachedScope, slot));
        }
        if (!supportsRequestedAccess(conn, requestAccess)) {
            return CompletableFuture.failedFuture(new IllegalStateException(unsupportedPlayerInventoryMessage()));
        }
        return conn.<QuerySlotVersionResponse>sendAsync(
                FrameType.QUERY_SLOT_VERSION, new QuerySlotVersionRequest(null, slot, requestAccess),
                FrameType.SLOT_VERSION_RESPONSE, SYNC_TIMEOUT_MS).thenApply(response -> {
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
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (!supportsRequestedAccess(conn, requestAccess)) {
            return CompletableFuture.failedFuture(new IllegalStateException(unsupportedPlayerInventoryMessage()));
        }
        return conn.<SlotStateResponse>sendAsync(
                FrameType.QUERY_SLOT_STATE, new QuerySlotStateRequest(null, slot, requestAccess),
                FrameType.SLOT_STATE_RESPONSE, SYNC_TIMEOUT_MS).thenAccept(response -> {
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

    private boolean supportsRequestedAccess(Connection conn, InventoryAccess access) {
        return access == null || !access.isPlayer() || conn.supportsInventoryAccess();
    }

    private String unsupportedPlayerInventoryMessage() {
        return "目标服务器版本不支持玩家仓库，请升级 TheExchange";
    }
}
