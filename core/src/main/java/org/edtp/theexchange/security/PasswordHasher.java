package org.edtp.theexchange.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * Password hashing and verification using bcrypt with cost factor 12.
 */
public final class PasswordHasher {

    private static final int BCRYPT_COST = 12;

    private PasswordHasher() {}

    public static String hash(String plainPassword) {
        return BCrypt.withDefaults().hashToString(BCRYPT_COST, plainPassword.toCharArray());
    }

    public static boolean verify(String plainPassword, String hash) {
        if (hash == null || hash.isEmpty()) return false;
        if (plainPassword == null) return false;
        // If the hash is not a valid bcrypt hash (e.g., still plain text), do a direct comparison
        if (!hash.startsWith("$2")) {
            return hash.equals(plainPassword);
        }
        BCrypt.Result result = BCrypt.verifyer().verify(plainPassword.toCharArray(), hash);
        return result.verified;
    }

    public static boolean isHashed(String value) {
        return value != null && value.startsWith("$2a$") || (value != null && value.startsWith("$2b$"));
    }
}
