package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInventoryAuthStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void setPasswordStoresHashAndVerifiesPassword() throws Exception {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("exchange.db").toString());
        db.initialize();
        try {
            PlayerInventoryAuthStore store = new PlayerInventoryAuthStore(db);
            InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

            store.setPassword(scope, "secret123");

            assertTrue(store.verify(scope, "secret123").success());
            PlayerInventoryAuthStore.AuthResult wrong = store.verify(scope, "bad-password");
            assertFalse(wrong.success());
            assertEquals("玩家仓库密码错误", wrong.failReason());

            String encoded = readPasswordHash(db, scope);
            assertNotNull(encoded);
            assertNotEquals("secret123", encoded);
            assertTrue(encoded.startsWith("pbkdf2_sha256$"));
            assertFalse(encoded.contains("secret123"));
            assertEquals(Set.of("player_uuid", "password_hash", "created_at", "updated_at"),
                    authColumns(db), "the warehouse profile must not persist a player name");
        } finally {
            db.close();
        }
    }

    @Test
    void verifyFailsWhenPasswordHasNotBeenSet() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("missing.db").toString());
        db.initialize();
        try {
            PlayerInventoryAuthStore store = new PlayerInventoryAuthStore(db);
            InventoryScope scope = InventoryScope.player("ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee");

            PlayerInventoryAuthStore.AuthResult result = store.verify(scope, "secret123");

            assertFalse(result.success());
            assertEquals("玩家仓库不存在或尚未创建", result.failReason());
        } finally {
            db.close();
        }
    }

    @Test
    void migratesLegacyNameColumnToUuidOnlyProfile() throws Exception {
        Path path = tempDir.resolve("legacy.db");
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE player_inventory_auth (" +
                    "scope_id TEXT PRIMARY KEY, player_name TEXT NOT NULL, " +
                    "password_hash TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO player_inventory_auth VALUES (" +
                    "'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', 'steve', 'legacy-hash', 1, 2)");
        }

        DatabaseManager db = new DatabaseManager(path.toString());
        db.initialize();
        try {
            assertEquals(Set.of("player_uuid", "password_hash", "created_at", "updated_at"),
                    authColumns(db));
            try (PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT password_hash FROM player_inventory_auth WHERE player_uuid = ?")) {
                ps.setString(1, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("legacy-hash", rs.getString(1));
                }
            }
        } finally {
            db.close();
        }
    }

    private String readPasswordHash(DatabaseManager db, InventoryScope scope) throws Exception {
        db.lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT password_hash FROM player_inventory_auth WHERE player_uuid = ?")) {
            ps.setString(1, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } finally {
            db.unlock();
        }
    }

    private Set<String> authColumns(DatabaseManager db) throws Exception {
        Set<String> columns = new HashSet<>();
        db.lock();
        try (Statement statement = db.getConnection().createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(player_inventory_auth)")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        } finally {
            db.unlock();
        }
        return columns;
    }
}
