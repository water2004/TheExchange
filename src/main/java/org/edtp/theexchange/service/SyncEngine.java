package org.edtp.theexchange.service;

import org.edtp.theexchange.compat.CompatibilityChecker;
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
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(null);
        }
        return conn.<SlotVersionsResponse>sendAsync(
                        FrameType.QUERY_SLOT_VERSIONS, new QuerySlotVersionsRequest(),
                        FrameType.SLOT_VERSIONS_RESPONSE, SYNC_TIMEOUT_MS)
                .thenCompose(response -> {
                    List<Integer> remoteVersions = response != null ? response.getVersions() : List.of();
                    List<Integer> changed = cacheManager.changedSlots(serverName, InventoryScope.server(), remoteVersions);
                    if (changed.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return querySlotsAsync(serverName, changed).thenApply(ignored -> null);
                });
    }

    public CompletableFuture<Void> querySlotsAsync(String serverName, List<Integer> slots) {
        if (slots == null || slots.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(null);
        }
        return conn.<SlotsStateResponse>sendAsync(
                FrameType.QUERY_SLOTS, new QuerySlotsRequest(slots),
                FrameType.SLOTS_STATE_RESPONSE, SYNC_TIMEOUT_MS).thenAccept(response -> {
            if (response == null) {
                return;
            }
            applySlotStates(serverName, response.getSlots());
        });
    }

    public CompletableFuture<Integer> querySlotVersionAsync(String serverName, int slot) {
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(cacheManager.getSlotVersion(serverName, InventoryScope.server(), slot));
        }
        return conn.<QuerySlotVersionResponse>sendAsync(
                FrameType.QUERY_SLOT_VERSION, new QuerySlotVersionRequest(slot),
                FrameType.SLOT_VERSION_RESPONSE, SYNC_TIMEOUT_MS).thenApply(response ->
                response != null ? response.getVersion() : cacheManager.getSlotVersion(serverName, InventoryScope.server(), slot));
    }

    public CompletableFuture<Void> querySlotStateAsync(String serverName, int slot) {
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return CompletableFuture.completedFuture(null);
        }
        return conn.<SlotStateResponse>sendAsync(
                FrameType.QUERY_SLOT_STATE, new QuerySlotStateRequest(slot),
                FrameType.SLOT_STATE_RESPONSE, SYNC_TIMEOUT_MS).thenAccept(response -> {
            if (response != null) {
                applySlotStates(serverName, List.of(response));
            }
        });
    }

    private void applySlotStates(String serverName, List<SlotStateResponse> slots) {
        if (slots == null || slots.isEmpty()) {
            return;
        }
        for (SlotStateResponse slot : slots) {
            if (slot != null && slot.getItem() != null && compatibilityChecker != null) {
                compatibilityChecker.checkAndMark(slot.getItem());
            }
        }
        cacheManager.updateCacheSlots(serverName, InventoryScope.server(), slots);
    }

    private Connection getConnection(String serverName) {
        if (networkManager == null) {
            return null;
        }
        return networkManager.getConnection(serverName);
    }
}
