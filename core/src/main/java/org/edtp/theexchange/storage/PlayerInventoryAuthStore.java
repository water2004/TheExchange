package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

public class PlayerInventoryAuthStore {
    private static final String HASH_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String HASH_PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final DatabaseManager db;
    private final SecureRandom random = new SecureRandom();

    public PlayerInventoryAuthStore(DatabaseManager db) {
        this.db = db;
    }

    public void setPassword(InventoryScope scope, String password) {
        requirePlayerScope(scope);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("玩家仓库密码不能为空");
        }
        String hash = hashPassword(password);
        long now = System.currentTimeMillis();
        db.lock();
        try {
            String sql = "INSERT INTO player_inventory_auth " +
                    "(player_uuid, password_hash, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(player_uuid) DO UPDATE SET " +
                    "password_hash = excluded.password_hash, " +
                    "updated_at = excluded.updated_at";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, scope.getScopeId());
                ps.setString(2, hash);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save player inventory password", e);
        } finally {
            db.unlock();
        }
    }

    public AuthResult verify(InventoryScope scope, String password) {
        requirePlayerScope(scope);
        if (password == null || password.isBlank()) {
            return AuthResult.fail("玩家仓库密码不能为空");
        }
        AuthRecord record = read(scope);
        if (record == null) {
            return AuthResult.fail("玩家仓库不存在或尚未创建");
        }
        return verifyPassword(password, record.passwordHash())
                ? AuthResult.ok()
                : AuthResult.fail("玩家仓库密码错误");
    }

    public boolean hasPassword(InventoryScope scope) {
        requirePlayerScope(scope);
        return read(scope) != null;
    }

    private AuthRecord read(InventoryScope scope) {
        db.lock();
        try {
            String sql = "SELECT password_hash FROM player_inventory_auth WHERE player_uuid = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, scope.getScopeId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                    return new AuthRecord(rs.getString("password_hash"));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read player inventory password", e);
        } finally {
            db.unlock();
        }
        return null;
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        return HASH_PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPassword(String password, String encoded) {
        if (encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash player inventory password", e);
        }
    }

    private void requirePlayerScope(InventoryScope scope) {
        if (scope == null || !scope.isPlayer() || scope.getScopeId().isBlank()) {
            throw new IllegalArgumentException("玩家仓库 scope 无效");
        }
        try {
            java.util.UUID.fromString(scope.getScopeId());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("玩家仓库 UUID 无效", e);
        }
    }

    private record AuthRecord(String passwordHash) {}

    public record AuthResult(boolean success, String failReason) {
        public static AuthResult ok() {
            return new AuthResult(true, null);
        }

        public static AuthResult fail(String reason) {
            return new AuthResult(false, reason);
        }
    }
}
