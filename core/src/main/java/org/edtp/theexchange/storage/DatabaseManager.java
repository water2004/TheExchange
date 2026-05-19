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
            migrateToV2IfNeeded();
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
                    "scope_type  TEXT    NOT NULL DEFAULT 'SERVER'," +
                    "scope_id    TEXT    NOT NULL DEFAULT '' ," +
                    "slot        INTEGER NOT NULL," +
                    "item_data   BLOB    NOT NULL," +
                    "added_by    TEXT," +
                    "added_at    INTEGER NOT NULL," +
                    "updated_at  INTEGER NOT NULL DEFAULT 0," +
                    "version     INTEGER NOT NULL DEFAULT 1," +
                    "PRIMARY KEY (scope_type, scope_id, slot))");

            // Remote server config
            stmt.execute("CREATE TABLE IF NOT EXISTS remote_servers (" +
                    "name        TEXT PRIMARY KEY," +
                    "address     TEXT    NOT NULL," +
                    "port        INTEGER NOT NULL," +
                    "password_hash TEXT  NOT NULL," +
                    "enabled     INTEGER NOT NULL DEFAULT 1)");

            // Remote inventory cache
            stmt.execute("CREATE TABLE IF NOT EXISTS remote_cache (" +
                    "server_name      TEXT    NOT NULL," +
                    "scope_type       TEXT    NOT NULL DEFAULT 'SERVER'," +
                    "scope_id         TEXT    NOT NULL DEFAULT '' ," +
                    "items_blob       BLOB    NOT NULL," +
                    "synced_at        INTEGER NOT NULL," +
                    "remote_timestamp INTEGER NOT NULL," +
                    "PRIMARY KEY (server_name, scope_type, scope_id))");

            // Operation log with idempotency support
            stmt.execute("CREATE TABLE IF NOT EXISTS operation_log (" +
                    "id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "timestamp    INTEGER NOT NULL," +
                    "op_type      TEXT    NOT NULL," +
                    "scope_type   TEXT    NOT NULL DEFAULT 'SERVER'," +
                    "scope_id     TEXT    NOT NULL DEFAULT '' ," +
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
            stmt.execute("INSERT OR IGNORE INTO exchange_metadata (key, value) " +
                    "SELECT 'last_modified', COALESCE(MAX(updated_at), 0) FROM exchange_items");

            stmt.execute("CREATE TABLE IF NOT EXISTS inventory_metadata (" +
                    "scope_type    TEXT NOT NULL DEFAULT 'SERVER'," +
                    "scope_id      TEXT NOT NULL DEFAULT ''," +
                    "last_modified INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (scope_type, scope_id))");
            stmt.execute("INSERT OR IGNORE INTO inventory_metadata (scope_type, scope_id, last_modified) " +
                    "SELECT 'SERVER', '', COALESCE(MAX(updated_at), 0) FROM exchange_items " +
                    "WHERE scope_type = 'SERVER' AND scope_id = ''");
        }

        // Run migrations
        int currentVersion = getSchemaVersion();
        if (currentVersion < 1) {
            setSchemaVersion(1);
        }
    }

    private void migrateToV2IfNeeded() throws SQLException {
        int currentVersion = getSchemaVersion();
        if (currentVersion >= 2) {
            ensureOperationLogScopeColumns();
            return;
        }

        try (Statement stmt = connection.createStatement()) {
            if (tableExists("exchange_items") && !hasColumn("exchange_items", "scope_type")) {
                stmt.execute("ALTER TABLE exchange_items RENAME TO exchange_items_legacy");
                stmt.execute("CREATE TABLE exchange_items (" +
                        "scope_type  TEXT    NOT NULL DEFAULT 'SERVER'," +
                        "scope_id    TEXT    NOT NULL DEFAULT '' ," +
                        "slot        INTEGER NOT NULL," +
                        "item_data   BLOB    NOT NULL," +
                        "added_by    TEXT," +
                        "added_at    INTEGER NOT NULL," +
                        "updated_at  INTEGER NOT NULL DEFAULT 0," +
                        "version     INTEGER NOT NULL DEFAULT 1," +
                        "PRIMARY KEY (scope_type, scope_id, slot))");
                stmt.execute("INSERT INTO exchange_items (scope_type, scope_id, slot, item_data, added_by, added_at, updated_at, version) " +
                        "SELECT 'SERVER', '', slot, item_data, added_by, added_at, updated_at, version FROM exchange_items_legacy");
                stmt.execute("DROP TABLE exchange_items_legacy");
            }

            if (tableExists("remote_cache") && !hasColumn("remote_cache", "scope_type")) {
                stmt.execute("ALTER TABLE remote_cache RENAME TO remote_cache_legacy");
                stmt.execute("CREATE TABLE remote_cache (" +
                        "server_name      TEXT    NOT NULL," +
                        "scope_type       TEXT    NOT NULL DEFAULT 'SERVER'," +
                        "scope_id         TEXT    NOT NULL DEFAULT '' ," +
                        "items_blob       BLOB    NOT NULL," +
                        "synced_at        INTEGER NOT NULL," +
                        "remote_timestamp INTEGER NOT NULL," +
                        "PRIMARY KEY (server_name, scope_type, scope_id))");
                stmt.execute("INSERT INTO remote_cache (server_name, scope_type, scope_id, items_blob, synced_at, remote_timestamp) " +
                        "SELECT server_name, 'SERVER', '', items_blob, synced_at, remote_timestamp FROM remote_cache_legacy");
                stmt.execute("DROP TABLE remote_cache_legacy");
            }
        }

        ensureOperationLogScopeColumns();
        setSchemaVersion(2);
    }

    private void ensureOperationLogScopeColumns() throws SQLException {
        addColumnIfMissing("operation_log", "scope_type", "TEXT NOT NULL DEFAULT 'SERVER'");
        addColumnIfMissing("operation_log", "scope_id", "TEXT NOT NULL DEFAULT ''");
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

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
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
