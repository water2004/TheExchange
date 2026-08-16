package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.NeutralItem;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Items from recovered operations that no longer have a live platform destination. */
public final class SettlementVault {
    public record Entry(String transactionId, String ownerUuid, String ownerName,
                        NeutralItem item, String reason, long createdAt) {}

    private final DatabaseManager db;

    public SettlementVault(DatabaseManager db) {
        this.db = db;
    }

    public void deposit(String transactionId, String ownerUuid, String ownerName,
                        NeutralItem item, String reason) {
        if (item == null || item.isEmpty()) return;
        String sql = "INSERT OR IGNORE INTO settlement_vault " +
                "(transaction_id, owner_uuid, owner_name, item_blob, reason, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        db.lock();
        try (PreparedStatement statement = db.getConnection().prepareStatement(sql)) {
            statement.setString(1, transactionId);
            statement.setString(2, ownerUuid);
            statement.setString(3, ownerName);
            statement.setBytes(4, NeutralItemBlobCodec.encode(item));
            statement.setString(5, reason);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new RuntimeException("Failed to deposit settlement item", error);
        } finally {
            db.unlock();
        }
    }

    public List<Entry> list(String ownerUuid) {
        List<Entry> entries = new ArrayList<>();
        String sql = "SELECT transaction_id, owner_uuid, owner_name, item_blob, reason, created_at " +
                "FROM settlement_vault WHERE owner_uuid=? ORDER BY created_at, transaction_id";
        db.lock();
        try (PreparedStatement statement = db.getConnection().prepareStatement(sql)) {
            statement.setString(1, ownerUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new Entry(resultSet.getString("transaction_id"),
                            resultSet.getString("owner_uuid"), resultSet.getString("owner_name"),
                            NeutralItemBlobCodec.decode(resultSet.getBytes("item_blob")),
                            resultSet.getString("reason"), resultSet.getLong("created_at")));
                }
            }
            return entries;
        } catch (SQLException error) {
            throw new RuntimeException("Failed to list settlement items", error);
        } finally {
            db.unlock();
        }
    }

    public NeutralItem claim(String transactionId, String ownerUuid) {
        db.lock();
        try {
            NeutralItem item = null;
            try (PreparedStatement query = db.getConnection().prepareStatement(
                    "SELECT item_blob FROM settlement_vault WHERE transaction_id=? AND owner_uuid=?")) {
                query.setString(1, transactionId);
                query.setString(2, ownerUuid);
                try (ResultSet resultSet = query.executeQuery()) {
                    if (resultSet.next()) item = NeutralItemBlobCodec.decode(resultSet.getBytes(1));
                }
            }
            if (item != null) {
                try (PreparedStatement delete = db.getConnection().prepareStatement(
                        "DELETE FROM settlement_vault WHERE transaction_id=? AND owner_uuid=?")) {
                    delete.setString(1, transactionId);
                    delete.setString(2, ownerUuid);
                    delete.executeUpdate();
                }
            }
            return item;
        } catch (SQLException error) {
            throw new RuntimeException("Failed to claim settlement item", error);
        } finally {
            db.unlock();
        }
    }
}
