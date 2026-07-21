package org.edtp.theexchange.service;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-local cache of player-inventory bearer tokens.
 *
 * This class deliberately has no persistence dependency. Tokens disappear on
 * core reload or process restart and must never be written to configuration,
 * databases, logs, or remote inventory caches.
 */
public final class PlayerInventoryClientSessionStore {
    private final ConcurrentHashMap<SessionKey, InventoryAccess> sessions = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public PlayerInventoryClientSessionStore() {
        this(System::currentTimeMillis);
    }

    PlayerInventoryClientSessionStore(LongSupplier clock) {
        this.clock = clock;
    }

    public InventoryAccess remember(String serverName, InventoryAccess access) {
        requireSession(access);
        if (access.isLocallyExpired(clock.getAsLong())) {
            throw new IllegalArgumentException("玩家仓库访问令牌已过期");
        }
        sessions.put(SessionKey.of(serverName, access.ownerName(), access.requesterUuid()), access);
        return access;
    }

    public Optional<InventoryAccess> findValid(String serverName, String ownerName, String requesterUuid) {
        SessionKey key = SessionKey.of(serverName, ownerName, requesterUuid);
        InventoryAccess access = sessions.get(key);
        if (access == null) {
            return Optional.empty();
        }
        if (access.isLocallyExpired(clock.getAsLong())) {
            sessions.remove(key, access);
            return Optional.empty();
        }
        return Optional.of(access);
    }

    /** Returns the newest in-memory token for an access descriptor held by an older view. */
    public InventoryAccess resolve(String serverName, InventoryAccess access) {
        if (access == null || access.isServer()) {
            return InventoryAccess.server();
        }
        return findValid(serverName, access.ownerName(), access.requesterUuid())
                .orElseThrow(() -> new IllegalArgumentException("玩家仓库访问令牌无效或已过期"));
    }

    /** Slides the client-side expiry after the remote server accepted an operation. */
    public InventoryAccess touch(String serverName, InventoryAccess access) {
        requireSession(access);
        SessionKey key = SessionKey.of(serverName, access.ownerName(), access.requesterUuid());
        InventoryAccess updated = sessions.computeIfPresent(key, (ignored, current) -> {
            if (!current.token().equals(access.token())) {
                return current;
            }
            long ttl = current.sessionTtlMillis();
            return ttl > 0 ? current.withExpiry(saturatedAdd(clock.getAsLong(), ttl)) : current;
        });
        if (updated == null) {
            throw new IllegalArgumentException("玩家仓库访问令牌无效或已过期");
        }
        return updated;
    }

    public void invalidate(String serverName, InventoryAccess access) {
        if (access == null || !access.isPlayer()) {
            return;
        }
        sessions.remove(SessionKey.of(serverName, access.ownerName(), access.requesterUuid()));
    }

    public void invalidateScope(InventoryScope scope) {
        if (scope == null || !scope.isPlayer()) {
            return;
        }
        sessions.entrySet().removeIf(entry -> scope.equals(entry.getValue().effectiveScope()));
    }

    public void clear() {
        sessions.clear();
    }

    private static void requireSession(InventoryAccess access) {
        if (access == null || !access.isPlayer() || !access.hasToken()
                || access.ownerName().isBlank() || access.requesterUuid().isBlank()
                || access.effectiveScope() == null || !access.effectiveScope().isPlayer()) {
            throw new IllegalArgumentException("玩家仓库访问会话无效");
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record SessionKey(String serverName, String ownerName, String requesterUuid) {
        private static SessionKey of(String serverName, String ownerName, String requesterUuid) {
            return new SessionKey(normalize(serverName), normalize(ownerName), normalize(requesterUuid));
        }

        private static String normalize(String value) {
            return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        }
    }
}
