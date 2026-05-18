package org.edtp.theexchange.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Key-value config store backed by the exchange_config table.
 */
public class ConfigStore {

    private final DatabaseManager db;

    public ConfigStore(DatabaseManager db) {
        this.db = db;
    }

    public String get(String key) {
        String sql = "SELECT value FROM exchange_config WHERE key = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get config " + key, e);
        }
    }

    public void set(String key, String value) {
        String sql = "INSERT OR REPLACE INTO exchange_config (key, value) VALUES (?, ?)";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set config " + key, e);
        }
    }

    public String getOrDefault(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }
}
