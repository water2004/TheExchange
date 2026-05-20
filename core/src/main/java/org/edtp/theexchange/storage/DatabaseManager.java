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
            // Local exchange items (authoritative)
            stmt.execute("CREATE TABLE IF NOT EXISTS exchange_items (" +
                    "scope_type  TEXT    NOT NULL," +
                    "scope_id    TEXT    NOT NULL," +
                    "slot        INTEGER NOT NULL," +
                    "item_data   BLOB    NOT NULL," +
                    "added_by    TEXT," +
                    "added_at    INTEGER NOT NULL," +
                    "updated_at  INTEGER NOT NULL," +
                    "version     INTEGER NOT NULL," +
                    "PRIMARY KEY (scope_type, scope_id, slot))");

            // Remote server config
            stmt.execute("CREATE TABLE IF NOT EXISTS remote_servers (" +
                    "name        TEXT PRIMARY KEY," +
                    "address     TEXT    NOT NULL," +
                    "port        INTEGER NOT NULL," +
                    "password_hash TEXT  NOT NULL," +
                    "enabled     INTEGER NOT NULL)");

            // Remote inventory cache
            stmt.execute("CREATE TABLE IF NOT EXISTS remote_cache (" +
                    "server_name      TEXT    NOT NULL," +
                    "scope_type       TEXT    NOT NULL," +
                    "scope_id         TEXT    NOT NULL," +
                    "items_blob       BLOB    NOT NULL," +
                    "synced_at        INTEGER NOT NULL," +
                    "remote_timestamp INTEGER NOT NULL," +
                    "PRIMARY KEY (server_name, scope_type, scope_id))");

            // Operation log with idempotency support
            stmt.execute("CREATE TABLE IF NOT EXISTS operation_log (" +
                    "id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "timestamp    INTEGER NOT NULL," +
                    "op_type      TEXT    NOT NULL," +
                    "scope_type   TEXT    NOT NULL," +
                    "scope_id     TEXT    NOT NULL," +
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_log_scope     ON operation_log(scope_type, scope_id)");

            // Config key-value store
            stmt.execute("CREATE TABLE IF NOT EXISTS exchange_config (" +
                    "key   TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS exchange_metadata (" +
                    "key   TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS inventory_metadata (" +
                    "scope_type    TEXT NOT NULL," +
                    "scope_id      TEXT NOT NULL," +
                    "last_modified INTEGER NOT NULL," +
                    "PRIMARY KEY (scope_type, scope_id))");
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
