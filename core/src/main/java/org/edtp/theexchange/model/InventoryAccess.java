package org.edtp.theexchange.model;

import java.util.Objects;

/**
 * Authorization carried by inventory queries and mutations.
 *
 * Player passwords never live in this object. Protocol v2 exchanges a password
 * once for a short-lived token, and all inventory traffic carries only that
 * token plus the requesting player identity.
 */
public final class InventoryAccess {
    private final InventoryScope.ScopeType type;
    private final String ownerName;
    private final String token;
    private final String requesterUuid;
    private final String requesterName;
    private final InventoryScope resolvedScope;
    private final long expiresAt;
    private final long sessionTtlMillis;

    private InventoryAccess(InventoryScope.ScopeType type, String ownerName, String token,
                            String requesterUuid, String requesterName,
                            InventoryScope resolvedScope, long expiresAt, long sessionTtlMillis) {
        this.type = type != null ? type : InventoryScope.ScopeType.SERVER;
        this.ownerName = ownerName == null ? "" : ownerName.trim();
        this.token = token != null ? token : "";
        this.requesterUuid = normalize(requesterUuid);
        this.requesterName = requesterName != null ? requesterName.trim() : "";
        this.resolvedScope = resolvedScope;
        this.expiresAt = Math.max(0L, expiresAt);
        this.sessionTtlMillis = Math.max(0L, sessionTtlMillis);
    }

    public static InventoryAccess server() {
        return new InventoryAccess(InventoryScope.ScopeType.SERVER, "", "", "", "",
                InventoryScope.server(), 0, 0);
    }

    public static InventoryAccess playerSession(String ownerName, String token,
                                                String requesterUuid, String requesterName,
                                                InventoryScope resolvedScope, long expiresAt) {
        return playerSession(ownerName, token, requesterUuid, requesterName,
                resolvedScope, expiresAt, 0);
    }

    public static InventoryAccess playerSession(String ownerName, String token,
                                                String requesterUuid, String requesterName,
                                                InventoryScope resolvedScope, long expiresAt,
                                                long sessionTtlMillis) {
        return new InventoryAccess(InventoryScope.ScopeType.PLAYER, ownerName, token,
                requesterUuid, requesterName, resolvedScope, expiresAt, sessionTtlMillis);
    }

    public InventoryAccess withResolvedScope(InventoryScope scope) {
        return new InventoryAccess(type, ownerName, token, requesterUuid, requesterName,
                scope, expiresAt, sessionTtlMillis);
    }

    public InventoryAccess withExpiry(long newExpiresAt) {
        return new InventoryAccess(type, ownerName, token, requesterUuid, requesterName,
                resolvedScope, newExpiresAt, sessionTtlMillis);
    }

    public InventoryScope.ScopeType type() {
        return type;
    }

    public String ownerName() {
        return ownerName;
    }

    public String token() {
        return token;
    }

    public String requesterUuid() {
        return requesterUuid;
    }

    public String requesterName() {
        return requesterName;
    }

    public InventoryScope resolvedScope() {
        return resolvedScope;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public long sessionTtlMillis() {
        return sessionTtlMillis;
    }

    public InventoryScope effectiveScope() {
        if (resolvedScope != null) {
            return resolvedScope;
        }
        return type == InventoryScope.ScopeType.SERVER ? InventoryScope.server() : null;
    }

    public boolean isServer() {
        return type == InventoryScope.ScopeType.SERVER;
    }

    public boolean isPlayer() {
        return type == InventoryScope.ScopeType.PLAYER;
    }

    public boolean hasToken() {
        return isPlayer() && !token.isBlank();
    }

    public boolean isLocallyExpired(long now) {
        return isPlayer() && expiresAt > 0 && expiresAt <= now;
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(java.util.Locale.ROOT) : "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventoryAccess that)) return false;
        return expiresAt == that.expiresAt
                && sessionTtlMillis == that.sessionTtlMillis
                && type == that.type
                && Objects.equals(ownerName, that.ownerName)
                && Objects.equals(token, that.token)
                && Objects.equals(requesterUuid, that.requesterUuid)
                && Objects.equals(requesterName, that.requesterName)
                && Objects.equals(resolvedScope, that.resolvedScope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, ownerName, token, requesterUuid, requesterName,
                resolvedScope, expiresAt, sessionTtlMillis);
    }
}
