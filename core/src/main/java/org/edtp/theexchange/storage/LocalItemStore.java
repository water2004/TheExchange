package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade for the authoritative local exchange space.
 * All operations go through the in-memory cache manager; persistence stays hidden behind it.
 */
public class LocalItemStore {

    private final DatabaseManager db;
    private volatile LocalInventoryCacheManager cacheManager;

    public LocalItemStore(DatabaseManager db) {
        this.db = db;
    }

    public void setCacheManager(LocalInventoryCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    private LocalInventoryCacheManager requireCacheManager() {
        LocalInventoryCacheManager manager = cacheManager;
        if (manager == null) {
            throw new IllegalStateException("Local inventory cache is not initialized");
        }
        return manager;
    }

    public List<NeutralItem> getAllItems() {
        return getAllItems(InventoryScope.server());
    }

    public List<NeutralItem> getAllItems(InventoryScope scope) {
        return requireCacheManager().snapshot(scope);
    }

    public ItemRecord getItem(int slot) {
        return getItem(InventoryScope.server(), slot);
    }

    public ItemRecord getItem(InventoryScope scope, int slot) {
        LocalInventoryCacheManager manager = requireCacheManager();
        NeutralItem item = manager.get(scope, slot);
        return new ItemRecord(slot, item, null, 0,
                manager.getOrLoad(scope).getLastModifiedAt(), manager.getVersion(scope, slot));
    }

    public PutResult putItem(int slot, NeutralItem item, int expectedVersion, String addedBy) {
        return putItem(InventoryScope.server(), slot, item, expectedVersion, addedBy);
    }

    public PutResult putItem(InventoryScope scope, int slot, NeutralItem item,
                             int expectedVersion, String addedBy) {
        return requireCacheManager().put(scope, slot, item, expectedVersion, addedBy);
    }

    public void replaceSlotFromLocal(int slot, NeutralItem item, String addedBy) {
        replaceSlotFromLocal(InventoryScope.server(), slot, item, addedBy);
    }

    public void replaceSlotFromLocal(InventoryScope scope, int slot, NeutralItem item, String addedBy) {
        requireCacheManager().replaceFromLocal(scope, slot, item, addedBy);
    }

    public TakeResult takeItem(int slot, String expectedItemId, int expectedVersion,
                               int requestCount) {
        return takeItem(InventoryScope.server(), slot, expectedItemId, expectedVersion, requestCount);
    }

    public TakeResult takeItem(InventoryScope scope, int slot, String expectedItemId,
                               int expectedVersion, int requestCount) {
        return requireCacheManager().take(scope, slot, expectedItemId, expectedVersion, requestCount);
    }

    public long getLastModifiedTimestamp() {
        return getLastModifiedTimestamp(InventoryScope.server());
    }

    public long getLastModifiedTimestamp(InventoryScope scope) {
        return requireCacheManager().getOrLoad(scope).getLastModifiedAt();
    }

    public int getMaxSlot() {
        return getMaxSlot(InventoryScope.server());
    }

    public int getMaxSlot(InventoryScope scope) {
        List<NeutralItem> items = requireCacheManager().snapshot(scope);
        for (int i = items.size() - 1; i >= 0; i--) {
            NeutralItem item = items.get(i);
            if (item != null && !item.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    ScopeSnapshot loadScopeSnapshot(InventoryScope scope) {
        return new ScopeSnapshot(readAllSlotsFromDb(scope), readLastModifiedTimestampFromDb(scope), getMaxSlotFromDb(scope));
    }

    void persistScopeSnapshot(InventoryScope scope, List<LocalInventoryCache.SlotSnapshot> slots,
                              long lastModifiedAt, long revision) {
        persistScopeSnapshot(scope, slots, lastModifiedAt, revision, null);
    }

    void persistScopeSnapshot(InventoryScope scope, List<LocalInventoryCache.SlotSnapshot> slots,
                              long lastModifiedAt, long revision, String defaultAddedBy) {
        db.lock();
        try {
            persistScopeSnapshotLocked(scope, slots, lastModifiedAt, revision, defaultAddedBy);
        } finally {
            db.unlock();
        }
    }

    private void persistScopeSnapshotLocked(InventoryScope scope, List<LocalInventoryCache.SlotSnapshot> slots,
                                            long lastModifiedAt, long revision, String defaultAddedBy) {
        int upperBound = Math.max(getMaxSlotFromDb(scope), maxSnapshotSlot(slots));
        if (upperBound < 0) {
            setLastModifiedTimestamp(scope, lastModifiedAt);
            return;
        }
        try {
            beginImmediate();
            for (int slot = 0; slot <= upperBound; slot++) {
                LocalInventoryCache.SlotSnapshot snapshot = snapshotAt(slots, slot);
                NeutralItem item = snapshot != null ? snapshot.item() : null;
                int version = snapshot != null ? snapshot.version() : 0;
                ItemRecord current = readItemFromDb(scope, slot);
                if (item == null || item.isEmpty()) {
                    if (version == 0) {
                        if (current != null) {
                            deleteItem(scope, slot);
                        }
                    } else if (current == null) {
                        insertItem(scope, slot, null, defaultAddedBy, System.currentTimeMillis(), version);
                    } else {
                        updateItem(scope, slot, null, version, System.currentTimeMillis());
                    }
                    continue;
                }
                NeutralItem copy = item.copy();
                copy.setVersion(version);
                if (current == null || current.item == null || current.item.isEmpty()) {
                    insertItem(scope, slot, copy, defaultAddedBy, System.currentTimeMillis(), version);
                } else {
                    updateItem(scope, slot, copy, version, System.currentTimeMillis());
                }
            }
            setLastModifiedTimestamp(scope, lastModifiedAt);
            commit();
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException("Failed to persist scope snapshot", e);
        }
    }

    private List<LocalInventoryCache.SlotSnapshot> readAllSlotsFromDb(InventoryScope scope) {
        db.lock();
        List<LocalInventoryCache.SlotSnapshot> slots = new ArrayList<>();
        String sql = "SELECT slot, item_data, version FROM exchange_items WHERE scope_type = ? AND scope_id = ? ORDER BY slot";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt("slot");
                    byte[] blob = rs.getBytes("item_data");
                    NeutralItem item = NeutralItemBlobCodec.decode(blob);
                    int version = rs.getInt("version");
                    if (item != null) {
                        item.setVersion(version);
                    }
                    slots.add(new LocalInventoryCache.SlotSnapshot(slot, item, version));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all items", e);
        } finally {
            db.unlock();
        }
        return slots;
    }

    private ItemRecord readItemFromDb(InventoryScope scope, int slot) {
        db.lock();
        String sql = "SELECT slot, item_data, added_by, added_at, updated_at, version " +
                "FROM exchange_items WHERE scope_type = ? AND scope_id = ? AND slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            ps.setInt(3, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] blob = rs.getBytes("item_data");
                    NeutralItem item = NeutralItemBlobCodec.decode(blob);
                    if (item != null) {
                        item.setVersion(rs.getInt("version"));
                    }
                    return new ItemRecord(slot, item, rs.getString("added_by"),
                            rs.getLong("added_at"), rs.getLong("updated_at"), rs.getInt("version"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get item at slot " + slot, e);
        } finally {
            db.unlock();
        }
        return null;
    }

    private void insertItem(InventoryScope scope, int slot, NeutralItem item, String addedBy, long now, int version) {
        String sql = "INSERT OR REPLACE INTO exchange_items (scope_type, scope_id, slot, item_data, added_by, added_at, updated_at, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            ps.setInt(3, slot);
            ps.setBytes(4, NeutralItemBlobCodec.encode(item));
            ps.setString(5, addedBy);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setInt(8, version);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert item at slot " + slot, e);
        }
    }

    private void updateItem(InventoryScope scope, int slot, NeutralItem item, int newVersion, long now) {
        String sql = "UPDATE exchange_items SET item_data = ?, version = ?, updated_at = ? " +
                "WHERE scope_type = ? AND scope_id = ? AND slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setBytes(1, NeutralItemBlobCodec.encode(item));
            ps.setInt(2, newVersion);
            ps.setLong(3, now);
            ps.setString(4, scope.typeName());
            ps.setString(5, scope.getScopeId());
            ps.setInt(6, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update item at slot " + slot, e);
        }
    }

    private void deleteItem(InventoryScope scope, int slot) {
        String sql = "DELETE FROM exchange_items WHERE scope_type = ? AND scope_id = ? AND slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            ps.setInt(3, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete item at slot " + slot, e);
        }
    }

    private long readLastModifiedTimestampFromDb(InventoryScope scope) {
        db.lock();
        String sql = "SELECT last_modified FROM inventory_metadata WHERE scope_type = ? AND scope_id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException ignored) {
        } finally {
            db.unlock();
        }
        return 0;
    }

    private int getMaxSlotFromDb(InventoryScope scope) {
        db.lock();
        String sql = "SELECT MAX(slot) FROM exchange_items WHERE scope_type = ? AND scope_id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            return -1;
        } finally {
            db.unlock();
        }
    }

    private void setLastModifiedTimestamp(InventoryScope scope, long timestamp) {
        db.lock();
        String sql = "INSERT OR REPLACE INTO inventory_metadata (scope_type, scope_id, last_modified) VALUES (?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            ps.setLong(3, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update inventory timestamp", e);
        } finally {
            db.unlock();
        }
    }

    private void beginImmediate() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement()) {
            stmt.execute("BEGIN IMMEDIATE");
        }
    }

    private void commit() throws SQLException {
        try (Statement stmt = db.getConnection().createStatement()) {
            stmt.execute("COMMIT");
        }
    }

    private void rollbackQuietly() {
        try (Statement stmt = db.getConnection().createStatement()) {
            stmt.execute("ROLLBACK");
        } catch (SQLException ignored) {
        }
    }

    private int maxSnapshotSlot(List<LocalInventoryCache.SlotSnapshot> snapshots) {
        int max = -1;
        if (snapshots != null) {
            for (LocalInventoryCache.SlotSnapshot snapshot : snapshots) {
                if (snapshot != null) {
                    max = Math.max(max, snapshot.slot());
                }
            }
        }
        return max;
    }

    private LocalInventoryCache.SlotSnapshot snapshotAt(List<LocalInventoryCache.SlotSnapshot> snapshots, int slot) {
        if (snapshots == null) return null;
        for (LocalInventoryCache.SlotSnapshot snapshot : snapshots) {
            if (snapshot != null && snapshot.slot() == slot) {
                return snapshot;
            }
        }
        return null;
    }

    public record ItemRecord(int slot, NeutralItem item, String addedBy, long addedAt,
                             long updatedAt, int version) {}

    record ScopeSnapshot(List<LocalInventoryCache.SlotSnapshot> slots, long lastModifiedAt, int maxSlot) {}

    public record PutResult(boolean success, NeutralItem item, String failReason, int newVersion) {
        public static PutResult success(NeutralItem item, int newVersion) {
            return new PutResult(true, item, null, newVersion);
        }

        public static PutResult fail(String reason) {
            return new PutResult(false, null, reason, -1);
        }

        public boolean isSuccess() { return success; }
        public NeutralItem getItem() { return item; }
        public String getFailReason() { return failReason; }
        public int getNewVersion() { return newVersion; }
    }

    public record TakeResult(boolean success, NeutralItem item, String failReason, int newVersion) {
        public static TakeResult success(NeutralItem item, int newVersion) {
            return new TakeResult(true, item, null, newVersion);
        }

        public static TakeResult fail(String reason) {
            return new TakeResult(false, null, reason, -1);
        }

        public boolean isSuccess() { return success; }
        public NeutralItem getItem() { return item; }
        public String getFailReason() { return failReason; }
        public int getNewVersion() { return newVersion; }
    }
}
