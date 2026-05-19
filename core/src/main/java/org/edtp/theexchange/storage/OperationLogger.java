package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.OperationType;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Records all PUT/TAKE operations with idempotency via requestId.
 */
public class OperationLogger {

    private final DatabaseManager db;

    public OperationLogger(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Log an operation. Returns true if logged, false if requestId already exists (idempotent).
     */
    public boolean log(String requestId, OperationType opType, String playerUuid, String playerName,
                       String serverName, String itemId, int quantity, boolean success, String failReason) {
        return log(InventoryScope.server(), requestId, opType, playerUuid, playerName,
                serverName, itemId, quantity, success, failReason);
    }

    public boolean log(InventoryScope scope, String requestId, OperationType opType,
                       String playerUuid, String playerName, String serverName,
                       String itemId, int quantity, boolean success, String failReason) {
        String sql = "INSERT INTO operation_log (timestamp, op_type, scope_type, scope_id, player_uuid, player_name, server_name, " +
                "item_id, quantity, result, fail_reason, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, opType.name());
            ps.setString(3, scope.typeName());
            ps.setString(4, scope.getScopeId());
            ps.setString(5, playerUuid);
            ps.setString(6, playerName);
            ps.setString(7, serverName);
            ps.setString(8, itemId);
            ps.setInt(9, quantity);
            ps.setString(10, success ? "SUCCESS" : "FAIL");
            ps.setString(11, failReason);
            ps.setString(12, requestId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                return false; // Idempotent: already logged
            }
            throw new RuntimeException("Failed to log operation", e);
        }
    }

    /**
     * Check if a requestId has already been processed (idempotency check).
     */
    public LogEntry findByRequestId(String requestId) {
        String sql = "SELECT * FROM operation_log WHERE request_id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapEntry(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find log by requestId", e);
        }
        return null;
    }

    public List<LogEntry> queryLogs(long sinceTimestamp) {
        List<LogEntry> results = new ArrayList<>();
        String sql = "SELECT * FROM operation_log WHERE timestamp >= ? ORDER BY timestamp DESC";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, sinceTimestamp);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapEntry(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query logs", e);
        }
        return results;
    }

    public int cleanupOldLogs(int retentionDays) {
        long cutoff = System.currentTimeMillis() - (long) retentionDays * 24 * 3600 * 1000;
        String sql = "DELETE FROM operation_log WHERE timestamp < ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to cleanup old logs", e);
        }
    }

    private LogEntry mapEntry(ResultSet rs) throws SQLException {
        return new LogEntry(
                rs.getLong("id"),
                rs.getLong("timestamp"),
                OperationType.valueOf(rs.getString("op_type")),
                new InventoryScope(
                        InventoryScope.ScopeType.valueOf(rs.getString("scope_type")),
                        rs.getString("scope_id")),
                rs.getString("player_uuid"),
                rs.getString("player_name"),
                rs.getString("server_name"),
                rs.getString("item_id"),
                rs.getInt("quantity"),
                "SUCCESS".equals(rs.getString("result")),
                rs.getString("fail_reason"),
                rs.getString("request_id")
        );
    }

    public record LogEntry(long id, long timestamp, OperationType opType,
                           InventoryScope scope,
                           String playerUuid, String playerName,
                           String serverName, String itemId,
                           int quantity, boolean success,
                           String failReason, String requestId) {}
}
