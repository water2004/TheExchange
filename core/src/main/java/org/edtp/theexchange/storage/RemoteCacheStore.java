package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RemoteCacheStore {
    private final DatabaseManager db;

    public RemoteCacheStore(DatabaseManager db) {
        this.db = db;
    }

    public RemoteSlotSnapshot loadSlot(String serverName, InventoryScope scope, int slot) {
        db.lock();
        String sql = "SELECT items_blob, version FROM remote_cache WHERE server_name = ? AND scope_type = ? AND scope_id = ? AND slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setString(2, scope.typeName());
            ps.setString(3, scope.getScopeId());
            ps.setInt(4, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    NeutralItem item = NeutralItemBlobCodec.decode(rs.getBytes("items_blob"));
                    if (item != null) {
                        item.setVersion(rs.getInt("version"));
                    }
                    return new RemoteSlotSnapshot(slot, item, rs.getInt("version"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load remote slot", e);
        } finally {
            db.unlock();
        }
        return new RemoteSlotSnapshot(slot, null, 0);
    }

    public int loadSlotVersion(String serverName, InventoryScope scope, int slot) {
        db.lock();
        String sql = "SELECT version FROM remote_cache WHERE server_name = ? AND scope_type = ? AND scope_id = ? AND slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setString(2, scope.typeName());
            ps.setString(3, scope.getScopeId());
            ps.setInt(4, slot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("version") : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load remote slot version", e);
        } finally {
            db.unlock();
        }
    }

    public void saveSlot(String serverName, InventoryScope scope, int slot, NeutralItem item, int version) {
        db.lock();
        String sql = "INSERT OR REPLACE INTO remote_cache (server_name, scope_type, scope_id, slot, items_blob, version, synced_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setString(2, scope.typeName());
            ps.setString(3, scope.getScopeId());
            ps.setInt(4, slot);
            ps.setBytes(5, item != null && !item.isEmpty() ? NeutralItemBlobCodec.encode(item) : null);
            ps.setInt(6, version);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save remote slot", e);
        } finally {
            db.unlock();
        }
    }

    public void removeSlot(String serverName, InventoryScope scope, int slot) {
        db.lock();
        String sql = "DELETE FROM remote_cache WHERE server_name = ? AND scope_type = ? AND scope_id = ? AND slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setString(2, scope.typeName());
            ps.setString(3, scope.getScopeId());
            ps.setInt(4, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove remote slot", e);
        } finally {
            db.unlock();
        }
    }

    public List<RemoteSlotSnapshot> loadScope(String serverName, InventoryScope scope) {
        db.lock();
        List<RemoteSlotSnapshot> result = new ArrayList<>();
        String sql = "SELECT slot, items_blob, version FROM remote_cache WHERE server_name = ? AND scope_type = ? AND scope_id = ? ORDER BY slot";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, serverName);
            ps.setString(2, scope.typeName());
            ps.setString(3, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt("slot");
                    NeutralItem item = NeutralItemBlobCodec.decode(rs.getBytes("items_blob"));
                    if (item != null) {
                        item.setVersion(rs.getInt("version"));
                    }
                    result.add(new RemoteSlotSnapshot(slot, item, rs.getInt("version")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load remote scope", e);
        } finally {
            db.unlock();
        }
        return result;
    }

    public void retainOnlyServers(Set<String> allowedServerNames) {
        db.lock();
        try {
            if (allowedServerNames == null || allowedServerNames.isEmpty()) {
                try (PreparedStatement ps = db.getConnection().prepareStatement("DELETE FROM remote_cache")) {
                    ps.executeUpdate();
                }
                return;
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(allowedServerNames.size(), "?"));
            String sql = "DELETE FROM remote_cache WHERE server_name NOT IN (" + placeholders + ")";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                int index = 1;
                for (String serverName : allowedServerNames) {
                    ps.setString(index++, serverName);
                }
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to prune remote cache", e);
        } finally {
            db.unlock();
        }
    }

    public record RemoteSlotSnapshot(int slot, NeutralItem item, int version) {}
}
