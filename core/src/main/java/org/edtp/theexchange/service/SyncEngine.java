package org.edtp.theexchange.service;

import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.QuerySlotStateRequest;
import org.edtp.theexchange.network.protocol.messages.QuerySlotVersionRequest;
import org.edtp.theexchange.network.protocol.messages.QuerySlotVersionResponse;
import org.edtp.theexchange.network.protocol.messages.SlotStateResponse;

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

    public java.util.concurrent.CompletableFuture<Integer> querySlotVersionAsync(String serverName, int slot) {
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(cacheManager.getSlotVersion(serverName, InventoryScope.server(), slot));
        }
        return conn.<QuerySlotVersionResponse>sendAsync(
                FrameType.QUERY_SLOT_VERSION, new QuerySlotVersionRequest(slot),
                FrameType.SLOT_VERSION_RESPONSE, SYNC_TIMEOUT_MS).thenApply(response ->
                response != null ? response.getVersion() : cacheManager.getSlotVersion(serverName, InventoryScope.server(), slot));
    }

    public java.util.concurrent.CompletableFuture<NeutralItem> querySlotStateAsync(String serverName, int slot) {
        Connection conn = getConnection(serverName);
        if (conn == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(cacheManager.getSlot(serverName, InventoryScope.server(), slot));
        }
        return conn.<SlotStateResponse>sendAsync(
                FrameType.QUERY_SLOT_STATE, new QuerySlotStateRequest(slot),
                FrameType.SLOT_STATE_RESPONSE, SYNC_TIMEOUT_MS).thenApply(response -> {
            if (response == null || response.getItem() == null) {
                return null;
            }
            NeutralItem item = response.getItem();
            if (item.getVersion() <= 0) {
                item.setVersion(response.getVersion());
            }
            if (compatibilityChecker != null) {
                compatibilityChecker.checkAndMark(item);
            }
            cacheManager.updateCacheSlot(serverName, InventoryScope.server(), slot, item, item.getVersion());
            return item;
        });
    }

    private Connection getConnection(String serverName) {
        if (networkManager == null) {
            return null;
        }
        return networkManager.getConnection(serverName);
    }
}
