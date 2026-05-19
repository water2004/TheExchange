package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.NeutralItem;
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
        List<NeutralItem> items = new ArrayList<>();
        String sql = "SELECT slot, item_data, added_by, added_at, updated_at, version FROM exchange_items ORDER BY slot";
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int slot = rs.getInt("slot");
                // Pad with nulls so list index == slot number
                while (items.size() <= slot) {
                    items.add(null);
                }
                byte[] blob = rs.getBytes("item_data");
                NeutralItem item = MessagePackBlobCodec.decode(blob, NeutralItem.class);
                if (item != null) {
                    item.setVersion(rs.getInt("version"));
                }
                items.set(slot, item);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all items", e);
        }
        return items;
    }

    public ItemRecord getItem(int slot) {
        String sql = "SELECT slot, item_data, added_by, added_at, updated_at, version FROM exchange_items WHERE slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setInt(1, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] blob = rs.getBytes("item_data");
                    NeutralItem item = MessagePackBlobCodec.decode(blob, NeutralItem.class);
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

    /**
     * Insert or merge an item into a slot. Returns the new version.
     * Caller must wrap in a transaction.
     */
    public synchronized PutResult putItem(int slot, NeutralItem item, int expectedVersion, String addedBy) {
        if (slot < 0 || slot >= 54) {
            return PutResult.fail("INVALID_SLOT");
        }
        if (item == null || item.isEmpty()) {
            return PutResult.fail("EMPTY_ITEM");
        }

        try {
            beginImmediate();
            ItemRecord existing = getItem(slot);
            if (existing == null || existing.item == null || existing.item.isEmpty()) {
                if (expectedVersion != 0) {
                    rollbackQuietly();
                    return PutResult.fail("VERSION_MISMATCH");
                }
                long now = System.currentTimeMillis();
                item.setVersion(1);
                insertItem(slot, item, addedBy, now);
                setLastModifiedTimestamp(now);
                commit();
                return PutResult.success(item, 1);
            }

            if (existing.version != expectedVersion) {
                rollbackQuietly();
                return PutResult.fail("VERSION_MISMATCH");
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
            updateItem(slot, existing.item, newVersion, now);
            setLastModifiedTimestamp(now);
            commit();
            return PutResult.success(existing.item, newVersion);
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException("Failed to put item at slot " + slot, e);
        }
    }

    public synchronized boolean replaceSlotFromLocal(int slot, NeutralItem item, String addedBy) {
        if (slot < 0 || slot >= 54) {
            return false;
        }

        try {
            beginImmediate();
            ItemRecord existing = getItem(slot);
            if (item == null || item.isEmpty()) {
                if (existing == null || existing.item == null || existing.item.isEmpty()) {
                    rollbackQuietly();
                    return false;
                }
                deleteItem(slot);
                setLastModifiedTimestamp(System.currentTimeMillis());
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
                insertItem(slot, item, addedBy, now);
            } else {
                updateItem(slot, item, newVersion, now);
            }
            setLastModifiedTimestamp(now);
            commit();
            return true;
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException("Failed to replace item at slot " + slot, e);
        }
    }

    private void insertItem(int slot, NeutralItem item, String addedBy, long now) {
        String sql = "INSERT OR REPLACE INTO exchange_items (slot, item_data, added_by, added_at, version) " +
                "VALUES (?, ?, ?, ?, 1)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setInt(1, slot);
            ps.setBytes(2, MessagePackBlobCodec.encode(item));
            ps.setString(3, addedBy);
            ps.setLong(4, now);
            ps.executeUpdate();
            touchItem(slot, now);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert item at slot " + slot, e);
        }
    }

    private void updateItem(int slot, NeutralItem item, int newVersion, long now) {
        String sql = "UPDATE exchange_items SET item_data = ?, version = ?, updated_at = ? WHERE slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setBytes(1, MessagePackBlobCodec.encode(item));
            ps.setInt(2, newVersion);
            ps.setLong(3, now);
            ps.setInt(4, slot);
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
        try {
            beginImmediate();
            ItemRecord record = getItem(slot);
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
                updateItem(slot, record.item, newVersion, now);
                setLastModifiedTimestamp(now);
            } else {
                deleteItem(slot);
                setLastModifiedTimestamp(System.currentTimeMillis());
            }
            commit();
            return TakeResult.success(taken, newVersion);
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException("Failed to take item at slot " + slot, e);
        }
    }

    private void deleteItem(int slot) {
        String sql = "DELETE FROM exchange_items WHERE slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setInt(1, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete item at slot " + slot, e);
        }
    }

    public long getLastModifiedTimestamp() {
        String sql = "SELECT value FROM exchange_metadata WHERE key = 'last_modified'";
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return Long.parseLong(rs.getString(1));
            }
        } catch (SQLException e) {
            return 0;
        }
        String fallback = "SELECT MAX(updated_at) FROM exchange_items";
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(fallback)) {
            return rs.next() ? rs.getLong(1) : 0;
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

    private void touchItem(int slot, long now) {
        String sql = "UPDATE exchange_items SET updated_at = ? WHERE slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to touch item at slot " + slot, e);
        }
    }

    private void setLastModifiedTimestamp(long timestamp) {
        String sql = "INSERT OR REPLACE INTO exchange_metadata (key, value) VALUES ('last_modified', ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, Long.toString(timestamp));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update inventory timestamp", e);
        }
    }

    private boolean sameStackKind(NeutralItem a, NeutralItem b) {
        if (a == null || b == null) return false;
        return Objects.equals(a.getItemId(), b.getItemId())
                && Objects.equals(a.getDisplayName(), b.getDisplayName())
                && Arrays.equals(a.getExtraData(), b.getExtraData())
                && a.isIncompatible() == b.isIncompatible()
                && Objects.equals(a.getSourceVersion(), b.getSourceVersion());
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
