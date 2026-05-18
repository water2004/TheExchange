package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.NeutralItem;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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
        String sql = "SELECT slot, item_data, added_by, added_at, version FROM exchange_items ORDER BY slot";
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                byte[] blob = rs.getBytes("item_data");
                NeutralItem item = MessagePackBlobCodec.decode(blob, NeutralItem.class);
                items.add(item);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all items", e);
        }
        return items;
    }

    public ItemRecord getItem(int slot) {
        String sql = "SELECT slot, item_data, added_by, added_at, version FROM exchange_items WHERE slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setInt(1, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] blob = rs.getBytes("item_data");
                    NeutralItem item = MessagePackBlobCodec.decode(blob, NeutralItem.class);
                    return new ItemRecord(slot, item, rs.getString("added_by"),
                            rs.getLong("added_at"), rs.getInt("version"));
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
    public int putItem(int slot, NeutralItem item, String addedBy) {
        ItemRecord existing = getItem(slot);
        if (existing != null && existing.item != null) {
            // Merge: same item type, combine stacks
            if (existing.item.getItemId().equals(item.getItemId())) {
                int newCount = existing.item.getCount() + item.getCount();
                existing.item.setCount(newCount);
                updateItem(slot, existing.item, existing.version + 1);
                return existing.version + 1;
            }
            // Different item type: replace (should be validated by caller)
        }
        insertItem(slot, item, addedBy);
        return 1;
    }

    private void insertItem(int slot, NeutralItem item, String addedBy) {
        String sql = "INSERT OR REPLACE INTO exchange_items (slot, item_data, added_by, added_at, version) " +
                "VALUES (?, ?, ?, ?, 1)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setInt(1, slot);
            ps.setBytes(2, MessagePackBlobCodec.encode(item));
            ps.setString(3, addedBy);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert item at slot " + slot, e);
        }
    }

    private void updateItem(int slot, NeutralItem item, int newVersion) {
        String sql = "UPDATE exchange_items SET item_data = ?, version = ? WHERE slot = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setBytes(1, MessagePackBlobCodec.encode(item));
            ps.setInt(2, newVersion);
            ps.setInt(3, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update item at slot " + slot, e);
        }
    }

    /**
     * Take items from a slot with optimistic locking.
     * @return TakeResult with success status and the items to give
     */
    public TakeResult takeItem(int slot, String expectedItemId, int expectedVersion,
                                int requestCount) {
        ItemRecord record = getItem(slot);
        if (record == null || record.item == null || record.item.isEmpty()) {
            return TakeResult.fail("ITEM_NOT_FOUND");
        }
        if (record.version != expectedVersion) {
            return TakeResult.fail("VERSION_MISMATCH");
        }
        if (record.item.isIncompatible()) {
            return TakeResult.fail("INCOMPATIBLE");
        }
        if (record.item.getCount() < requestCount) {
            return TakeResult.fail("INSUFFICIENT");
        }

        NeutralItem taken = new NeutralItem(
                record.item.getItemId(), requestCount,
                record.item.getDisplayName(), record.item.getExtraData(),
                record.item.isIncompatible(), record.item.getSourceVersion());

        int remaining = record.item.getCount() - requestCount;
        if (remaining > 0) {
            record.item.setCount(remaining);
            updateItem(slot, record.item, record.version + 1);
        } else {
            deleteItem(slot);
        }
        return TakeResult.success(taken, record.version + 1);
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
        String sql = "SELECT MAX(added_at) FROM exchange_items";
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
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

    public record ItemRecord(int slot, NeutralItem item, String addedBy, long addedAt, int version) {}

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
