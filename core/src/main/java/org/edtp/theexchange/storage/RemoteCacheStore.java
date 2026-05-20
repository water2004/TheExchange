package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.CachedInventory;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import java.sql.*;
import java.util.List;

/**
 * Stores cached snapshots of remote server inventories.
 * Cache is NOT authoritative — writes always go to the remote server.
 */
public class RemoteCacheStore {

    private final DatabaseManager db;

    public RemoteCacheStore(DatabaseManager db) {
        this.db = db;
    }

    public CachedInventory getCache(String serverName) {
        return getCache(serverName, InventoryScope.server());
    }

    public CachedInventory getCache(String serverName, InventoryScope scope) {
        String sql = "SELECT items_blob, synced_at, remote_timestamp FROM remote_cache " +
                "WHERE server_name = ? AND scope_type = ? AND scope_id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setString(2, scope.typeName());
            ps.setString(3, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] blob = rs.getBytes("items_blob");
                    List<NeutralItem> items = NeutralItemBlobCodec.decodeList(blob);
                    if (items != null) {
                        for (int i = 0; i < items.size(); i++) {
                            NeutralItem item = items.get(i);
                            if (item != null && item.getVersion() <= 0) {
                                item.setVersion(1);
                            }
                        }
                    }
                    return new CachedInventory(items, items.size(),
                            rs.getLong("synced_at"), rs.getLong("remote_timestamp"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get cache for " + serverName, e);
        }
        return null;
    }

    public void putCache(String serverName, List<NeutralItem> items, long remoteTimestamp) {
        putCache(serverName, InventoryScope.server(), items, remoteTimestamp);
    }

    public void putCache(String serverName, InventoryScope scope, List<NeutralItem> items, long remoteTimestamp) {
        String sql = "INSERT OR REPLACE INTO remote_cache (server_name, scope_type, scope_id, items_blob, synced_at, remote_timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setString(2, scope.typeName());
            ps.setString(3, scope.getScopeId());
            ps.setBytes(4, NeutralItemBlobCodec.encodeList(items));
            ps.setLong(5, System.currentTimeMillis());
            ps.setLong(6, remoteTimestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to put cache for " + serverName, e);
        }
    }

    public void removeCache(String serverName) {
        removeCache(serverName, InventoryScope.server());
    }

    public void removeCache(String serverName, InventoryScope scope) {
        String sql = "DELETE FROM remote_cache WHERE server_name = ? AND scope_type = ? AND scope_id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setString(2, scope.typeName());
            ps.setString(3, scope.getScopeId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove cache for " + serverName, e);
        }
    }

    public void cleanupExpired(long retentionMillis) {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        String sql = "DELETE FROM remote_cache WHERE synced_at < ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to cleanup expired caches", e);
        }
    }
}
