package org.edtp.theexchange.storage;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class DatabaseManager {

    private final String databasePath;
    private final ReentrantLock dbLock = new ReentrantLock(true);
    private Connection connection;

    public DatabaseManager(String databasePath) {
        this.databasePath = databasePath;
    }

    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA busy_timeout=5000");
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
                    "item_data   BLOB," +
                    "added_by    TEXT," +
                    "added_at    INTEGER NOT NULL," +
                    "updated_at  INTEGER NOT NULL," +
                    "version     INTEGER NOT NULL," +
                    "PRIMARY KEY (scope_type, scope_id, slot))");

            // Remote inventory cache
            stmt.execute("CREATE TABLE IF NOT EXISTS remote_cache (" +
                    "server_name      TEXT    NOT NULL," +
                    "scope_type       TEXT    NOT NULL," +
                    "scope_id         TEXT    NOT NULL," +
                    "slot             INTEGER NOT NULL," +
                    "items_blob       BLOB," +
                    "version          INTEGER NOT NULL," +
                    "synced_at        INTEGER NOT NULL," +
                    "PRIMARY KEY (server_name, scope_type, scope_id, slot))");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_remote_cache_scope ON remote_cache(server_name, scope_type, scope_id)");

            stmt.execute("CREATE TABLE IF NOT EXISTS exchange_metadata (" +
                    "key   TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS inventory_metadata (" +
                    "scope_type    TEXT NOT NULL," +
                    "scope_id      TEXT NOT NULL," +
                    "last_modified INTEGER NOT NULL," +
                    "PRIMARY KEY (scope_type, scope_id))");

            stmt.execute("CREATE TABLE IF NOT EXISTS player_inventory_auth (" +
                    "player_uuid   TEXT PRIMARY KEY," +
                    "password_hash TEXT NOT NULL," +
                    "created_at    INTEGER NOT NULL," +
                    "updated_at    INTEGER NOT NULL)");

            // Only unresolved end-to-end mutations are stored. Closed transactions are deleted immediately.
            stmt.execute("CREATE TABLE IF NOT EXISTS mutation_recovery (" +
                    "direction    TEXT NOT NULL," +
                    "peer_id      TEXT NOT NULL," +
                    "transaction_id TEXT NOT NULL," +
                    "state        TEXT NOT NULL," +
                    "request_blob BLOB NOT NULL," +
                    "result_blob  BLOB," +
                    "updated_at   INTEGER NOT NULL," +
                    "PRIMARY KEY (direction, peer_id, transaction_id))");

            stmt.execute("CREATE TABLE IF NOT EXISTS settlement_vault (" +
                    "transaction_id TEXT PRIMARY KEY," +
                    "owner_uuid    TEXT," +
                    "owner_name    TEXT," +
                    "item_blob     BLOB NOT NULL," +
                    "reason        TEXT," +
                    "created_at    INTEGER NOT NULL)");
        }
        migratePlayerInventoryAuthToUuidOnly();
    }

    private void migratePlayerInventoryAuthToUuidOnly() throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(player_inventory_auth)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        if (columns.equals(Set.of("player_uuid", "password_hash", "created_at", "updated_at"))) {
            return;
        }
        String sourceUuidColumn = columns.contains("player_uuid") ? "player_uuid" : "scope_id";
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS player_inventory_auth_uuid_only");
            statement.execute("CREATE TABLE player_inventory_auth_uuid_only (" +
                    "player_uuid TEXT PRIMARY KEY, password_hash TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO player_inventory_auth_uuid_only " +
                    "(player_uuid, password_hash, created_at, updated_at) " +
                    "SELECT " + sourceUuidColumn + ", password_hash, created_at, updated_at " +
                    "FROM player_inventory_auth");
            statement.execute("DROP TABLE player_inventory_auth");
            statement.execute("ALTER TABLE player_inventory_auth_uuid_only RENAME TO player_inventory_auth");
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void lock() {
        dbLock.lock();
    }

    public void unlock() {
        dbLock.unlock();
    }

    public void close() {
        dbLock.lock();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        } finally {
            dbLock.unlock();
        }
    }

}
