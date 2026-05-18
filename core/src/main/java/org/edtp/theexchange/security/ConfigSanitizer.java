package org.edtp.theexchange.security;

import org.edtp.theexchange.model.RemoteServer;
import java.util.List;

/**
 * Detects plain-text passwords in configuration and auto-hashes them on first load.
 */
public final class ConfigSanitizer {

    private ConfigSanitizer() {}

    /**
     * If the password is not already bcrypt-hashed, hash it and return the hash.
     */
    public static String sanitizePassword(String password) {
        if (password == null || password.isEmpty()) return password;
        if (PasswordHasher.isHashed(password)) return password;
        return PasswordHasher.hash(password);
    }

    /**
     * Sanitize all remote server passwords in the list.
     * Returns true if any password was upgraded from plain-text.
     */
    public static boolean sanitizeRemoteServers(List<RemoteServer> servers) {
        boolean changed = false;
        for (RemoteServer server : servers) {
            if (!PasswordHasher.isHashed(server.getPasswordHash())) {
                server.setPasswordHash(PasswordHasher.hash(server.getPasswordHash()));
                changed = true;
            }
        }
        return changed;
    }
}
