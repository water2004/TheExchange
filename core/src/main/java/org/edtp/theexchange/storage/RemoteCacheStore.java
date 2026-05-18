package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.CachedInventory;
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
        String sql = "SELECT items_blob, synced_at, remote_timestamp FROM remote_cache WHERE server_name = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] blob = rs.getBytes("items_blob");
                    @SuppressWarnings("unchecked")
                    List<NeutralItem> items = MessagePackBlobCodec.decodeList(blob, NeutralItem.class);
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
        String sql = "INSERT OR REPLACE INTO remote_cache (server_name, items_blob, synced_at, remote_timestamp) " +
                "VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setBytes(2, MessagePackBlobCodec.encodeList(items));
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, remoteTimestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to put cache for " + serverName, e);
        }
    }

    public void removeCache(String serverName) {
        String sql = "DELETE FROM remote_cache WHERE server_name = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove cache for " + serverName, e);
        }
    }

    public void updateSlot(String serverName, int slot, NeutralItem item, long remoteTimestamp) {
        CachedInventory cache = getCache(serverName);
        if (cache == null) return;
        List<NeutralItem> items = cache.getItems();
        while (items.size() <= slot) {
            items.add(null);
        }
        items.set(slot, item);
        putCache(serverName, items, remoteTimestamp);
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
