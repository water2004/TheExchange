package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.InventoryScope;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Authoritative storage for the local server's exchange items.
 * All PUT/TAKE operations from remote servers execute against this store in transactions.
 */
public class LocalItemStore {

    private final DatabaseManager db;

    public LocalItemStore(DatabaseManager db) {
        this.db = db;
    }

    public List<NeutralItem> getAllItems() {
        return getAllItems(InventoryScope.server());
    }

    public List<NeutralItem> getAllItems(InventoryScope scope) {
        List<NeutralItem> items = new ArrayList<>();
        String sql = "SELECT slot, scope_type, scope_id, item_data, added_by, added_at, updated_at, version " +
                "FROM exchange_items WHERE scope_type = ? AND scope_id = ? ORDER BY slot";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int slot = rs.getInt("slot");
                // Pad with nulls so list index == slot number
                while (items.size() <= slot) {
                    items.add(null);
                }
                byte[] blob = rs.getBytes("item_data");
                NeutralItem item = NeutralItemBlobCodec.decode(blob);
                if (item != null) {
                    item.setVersion(rs.getInt("version"));
                }
                items.set(slot, item);
            }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all items", e);
        }
        return items;
    }

    public ItemRecord getItem(int slot) {
        return getItem(InventoryScope.server(), slot);
    }

    /**
     * Insert or merge an item into a slot. Returns the new version.
     * Caller must wrap in a transaction.
     */
    public synchronized PutResult putItem(int slot, NeutralItem item, int expectedVersion, String addedBy) {
        return putItem(InventoryScope.server(), slot, item, expectedVersion, addedBy);
    }

    public synchronized PutResult putItem(InventoryScope scope, int slot, NeutralItem item,
                                          int expectedVersion, String addedBy) {
        if (slot < 0 || slot >= 54) {
            return PutResult.fail("INVALID_SLOT");
        }
        if (item == null || item.isEmpty()) {
            return PutResult.fail("EMPTY_ITEM");
        }
        if (item.isIncompatible()) {
            return PutResult.fail("INCOMPATIBLE");
        }

        try {
            beginImmediate();
            ItemRecord existing = getItem(scope, slot);
            if (existing == null || existing.item == null || existing.item.isEmpty()) {
                if (expectedVersion != 0) {
                    rollbackQuietly();
                    return PutResult.fail("VERSION_MISMATCH");
                }
                long now = System.currentTimeMillis();
                item.setVersion(1);
                insertItem(scope, slot, item, addedBy, now);
                setLastModifiedTimestamp(scope, now);
                commit();
                return PutResult.success(item, 1);
            }

            if (existing.version != expectedVersion) {
                rollbackQuietly();
                return PutResult.fail("VERSION_MISMATCH");
            }
            if (existing.item.isIncompatible()) {
                rollbackQuietly();
                return PutResult.fail("INCOMPATIBLE");
            }
            if (!sameStackKind(existing.item, item)) {
                rollbackQuietly();
                return PutResult.fail("SLOT_OCCUPIED");
            }
            int maxStack = 64;
            if (existing.item.getCount() + item.getCount() > maxStack) {
                rollbackQuietly();
                return PutResult.fail("STACK_OVERFLOW");
            }

            int newVersion = existing.version + 1;
            existing.item.setCount(existing.item.getCount() + item.getCount());
            existing.item.setVersion(newVersion);
            long now = System.currentTimeMillis();
            updateItem(scope, slot, existing.item, newVersion, now);
            setLastModifiedTimestamp(scope, now);
            commit();
            return PutResult.success(existing.item, newVersion);
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException("Failed to put item at slot " + slot, e);
        }
    }

    public synchronized boolean replaceSlotFromLocal(int slot, NeutralItem item, String addedBy) {
        return replaceSlotFromLocal(InventoryScope.server(), slot, item, addedBy);
    }

    public synchronized boolean replaceSlotFromLocal(InventoryScope scope, int slot, NeutralItem item, String addedBy) {
        if (slot < 0 || slot >= 54) {
            return false;
        }
        if (item != null && item.isIncompatible()) {
            return false;
        }

        try {
            beginImmediate();
            ItemRecord existing = getItem(scope, slot);
            if (item == null || item.isEmpty()) {
                if (existing == null || existing.item == null || existing.item.isEmpty()) {
                    rollbackQuietly();
                    return false;
                }
                deleteItem(scope, slot);
                setLastModifiedTimestamp(scope, System.currentTimeMillis());
                commit();
                return true;
            }

            if (existing != null && existing.item != null
                    && existing.item.getCount() == item.getCount()
                    && sameStackKind(existing.item, item)) {
                rollbackQuietly();
                return false;
            }

            long now = System.currentTimeMillis();
            int newVersion = existing != null ? existing.version + 1 : 1;
            item.setVersion(newVersion);
            if (existing == null) {
                insertItem(scope, slot, item, addedBy, now);
            } else {
                updateItem(scope, slot, item, newVersion, now);
            }
            setLastModifiedTimestamp(scope, now);
            commit();
            return true;
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException("Failed to replace item at slot " + slot, e);
        }
    }

    private void insertItem(InventoryScope scope, int slot, NeutralItem item, String addedBy, long now) {
        String sql = "INSERT OR REPLACE INTO exchange_items (scope_type, scope_id, slot, item_data, added_by, added_at, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, 1)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            ps.setInt(3, slot);
            ps.setBytes(4, NeutralItemBlobCodec.encode(item));
            ps.setString(5, addedBy);
            ps.setLong(6, now);
            ps.executeUpdate();
            touchItem(scope, slot, now);
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

    /**
     * Take items from a slot with optimistic locking.
     * @return TakeResult with success status and the items to give
     */
    public synchronized TakeResult takeItem(int slot, String expectedItemId, int expectedVersion,
                                int requestCount) {
        return takeItem(InventoryScope.server(), slot, expectedItemId, expectedVersion, requestCount);
    }

    public synchronized TakeResult takeItem(InventoryScope scope, int slot, String expectedItemId, int expectedVersion,
                                int requestCount) {
        try {
            beginImmediate();
            ItemRecord record = getItem(scope, slot);
            if (record == null || record.item == null || record.item.isEmpty()) {
                rollbackQuietly();
                return TakeResult.fail("ITEM_NOT_FOUND");
            }
            if (record.version != expectedVersion) {
                rollbackQuietly();
                return TakeResult.fail("VERSION_MISMATCH");
            }
            if (!record.item.getItemId().equals(expectedItemId)) {
                rollbackQuietly();
                return TakeResult.fail("ITEM_MISMATCH");
            }
            if (record.item.isIncompatible()) {
                rollbackQuietly();
                return TakeResult.fail("INCOMPATIBLE");
            }
            if (requestCount <= 0 || record.item.getCount() < requestCount) {
                rollbackQuietly();
                return TakeResult.fail("INSUFFICIENT");
            }

            NeutralItem taken = new NeutralItem(
                    record.item.getItemId(), requestCount,
                    record.item.getDisplayName(), record.item.getExtraData(),
                    record.item.isIncompatible(), record.item.getSourceVersion());

            int newVersion = record.version + 1;
            taken.setVersion(newVersion);
            int remaining = record.item.getCount() - requestCount;
            if (remaining > 0) {
                record.item.setCount(remaining);
                record.item.setVersion(newVersion);
                long now = System.currentTimeMillis();
                updateItem(scope, slot, record.item, newVersion, now);
                setLastModifiedTimestamp(scope, now);
            } else {
                deleteItem(scope, slot);
                setLastModifiedTimestamp(scope, System.currentTimeMillis());
            }
            commit();
            return TakeResult.success(taken, newVersion);
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException("Failed to take item at slot " + slot, e);
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

    public ItemRecord getItem(InventoryScope scope, int slot) {
        String sql = "SELECT slot, scope_type, scope_id, item_data, added_by, added_at, updated_at, version " +
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
        }
        return null;
    }

    public long getLastModifiedTimestamp() {
        return getLastModifiedTimestamp(InventoryScope.server());
    }

    public long getLastModifiedTimestamp(InventoryScope scope) {
        String sql = "SELECT last_modified FROM inventory_metadata WHERE scope_type = ? AND scope_id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            }
        } catch (SQLException e) {
            return 0;
        }
        String fallback = "SELECT MAX(updated_at) FROM exchange_items WHERE scope_type = ? AND scope_id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(fallback)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public int getMaxSlot() {
        String sql = "SELECT MAX(slot) FROM exchange_items";
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            return -1;
        }
    }

    private void touchItem(InventoryScope scope, int slot, long now) {
        String sql = "UPDATE exchange_items SET updated_at = ? WHERE scope_type = ? AND scope_id = ? AND slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, scope.typeName());
            ps.setString(3, scope.getScopeId());
            ps.setInt(4, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to touch item at slot " + slot, e);
        }
    }

    private void setLastModifiedTimestamp(InventoryScope scope, long timestamp) {
        String sql = "INSERT OR REPLACE INTO inventory_metadata (scope_type, scope_id, last_modified) VALUES (?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, scope.typeName());
            ps.setString(2, scope.getScopeId());
            ps.setLong(3, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update inventory timestamp", e);
        }
        if (InventoryScope.server().equals(scope)) {
            String legacySql = "INSERT OR REPLACE INTO exchange_metadata (key, value) VALUES ('last_modified', ?)";
            try (PreparedStatement ps = db.getConnection().prepareStatement(legacySql)) {
                ps.setString(1, Long.toString(timestamp));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to update legacy inventory timestamp", e);
            }
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

    private boolean sameStackKind(NeutralItem a, NeutralItem b) {
        if (a == null || b == null) return false;
        return Objects.equals(a.getItemId(), b.getItemId())
                && Arrays.equals(a.getExtraData(), b.getExtraData());
    }

    public record ItemRecord(int slot, NeutralItem item, String addedBy, long addedAt,
                             long updatedAt, int version) {}

    public static class PutResult {
        private final boolean success;
        private final NeutralItem item;
        private final String failReason;
        private final int newVersion;

        private PutResult(boolean success, NeutralItem item, String failReason, int newVersion) {
            this.success = success;
            this.item = item;
            this.failReason = failReason;
            this.newVersion = newVersion;
        }

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

    public static class TakeResult {
        private final boolean success;
        private final NeutralItem item;
        private final String failReason;
        private final int newVersion;

        private TakeResult(boolean success, NeutralItem item, String failReason, int newVersion) {
            this.success = success;
            this.item = item;
            this.failReason = failReason;
            this.newVersion = newVersion;
        }

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
