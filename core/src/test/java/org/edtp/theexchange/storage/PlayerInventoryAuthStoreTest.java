package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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

            store.setPassword(scope, "Steve", "secret123");

            assertTrue(store.verify(scope, "Steve", "secret123").success());
            PlayerInventoryAuthStore.AuthResult wrong = store.verify(scope, "Steve", "bad-password");
            assertFalse(wrong.success());
            assertEquals("玩家仓库密码错误", wrong.failReason());

            String encoded = readPasswordHash(db, scope);
            assertNotNull(encoded);
            assertNotEquals("secret123", encoded);
            assertTrue(encoded.startsWith("pbkdf2_sha256$"));
            assertFalse(encoded.contains("secret123"));
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

            PlayerInventoryAuthStore.AuthResult result = store.verify(scope, "Steve", "secret123");

            assertFalse(result.success());
            assertEquals("玩家仓库尚未设置密码", result.failReason());
        } finally {
            db.close();
        }
    }

    private String readPasswordHash(DatabaseManager db, InventoryScope scope) throws Exception {
        db.lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT password_hash FROM player_inventory_auth WHERE scope_id = ?")) {
            ps.setString(1, scope.getScopeId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } finally {
            db.unlock();
        }
    }
}
