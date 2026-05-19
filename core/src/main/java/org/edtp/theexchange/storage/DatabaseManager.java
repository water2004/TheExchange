package org.edtp.theexchange.storage;

import java.sql.*;

public class DatabaseManager {

    private final String databasePath;
    private Connection connection;

    public DatabaseManager(String databasePath) {
        this.databasePath = databasePath;
    }

    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }
            createTables();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database at " + databasePath, e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Schema version tracking
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (" +
                    "version INTEGER PRIMARY KEY)");

            // Local exchange items (authoritative)
            stmt.execute("CREATE TABLE IF NOT EXISTS exchange_items (" +
                    "slot        INTEGER PRIMARY KEY," +
                    "item_data   BLOB    NOT NULL," +
                    "added_by    TEXT," +
                    "added_at    INTEGER NOT NULL," +
                    "updated_at  INTEGER NOT NULL DEFAULT 0," +
                    "version     INTEGER NOT NULL DEFAULT 1)");
            addColumnIfMissing("exchange_items", "updated_at", "INTEGER NOT NULL DEFAULT 0");
            stmt.execute("UPDATE exchange_items SET updated_at = added_at WHERE updated_at = 0");

            // Remote server config
            stmt.execute("CREATE TABLE IF NOT EXISTS remote_servers (" +
                    "name        TEXT PRIMARY KEY," +
                    "address     TEXT    NOT NULL," +
                    "port        INTEGER NOT NULL," +
                    "password_hash TEXT  NOT NULL," +
                    "enabled     INTEGER NOT NULL DEFAULT 1)");

            // Remote inventory cache
            stmt.execute("CREATE TABLE IF NOT EXISTS remote_cache (" +
                    "server_name      TEXT PRIMARY KEY," +
                    "items_blob       BLOB    NOT NULL," +
                    "synced_at        INTEGER NOT NULL," +
                    "remote_timestamp INTEGER NOT NULL)");

            // Operation log with idempotency support
            stmt.execute("CREATE TABLE IF NOT EXISTS operation_log (" +
                    "id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "timestamp    INTEGER NOT NULL," +
                    "op_type      TEXT    NOT NULL," +
                    "player_uuid  TEXT    NOT NULL," +
                    "player_name  TEXT    NOT NULL," +
                    "server_name  TEXT    NOT NULL," +
                    "item_id      TEXT    NOT NULL," +
                    "quantity     INTEGER NOT NULL," +
                    "result       TEXT    NOT NULL," +
                    "fail_reason  TEXT," +
                    "request_id   TEXT    NOT NULL UNIQUE)");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_log_timestamp ON operation_log(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_log_player    ON operation_log(player_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_log_server    ON operation_log(server_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_log_request   ON operation_log(request_id)");

            // Config key-value store
            stmt.execute("CREATE TABLE IF NOT EXISTS exchange_config (" +
                    "key   TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS exchange_metadata (" +
                    "key   TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");
            stmt.execute("INSERT OR IGNORE INTO exchange_metadata (key, value) " +
                    "SELECT 'last_modified', COALESCE(MAX(updated_at), 0) FROM exchange_items");
        }

        // Run migrations
        int currentVersion = getSchemaVersion();
        if (currentVersion < 1) {
            setSchemaVersion(1);
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    public int getSchemaVersion() {
        try {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
                if (!rs.next() || rs.getInt(1) == 0) return 0;
            }
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public void setSchemaVersion(int version) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO schema_version (version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set schema version", e);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }
}
