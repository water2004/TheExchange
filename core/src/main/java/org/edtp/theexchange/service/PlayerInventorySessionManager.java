package org.edtp.theexchange.service;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.storage.PlayerInventoryAuthStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Owns short-lived, peer-bound access sessions for player inventories.
 *
 * Passwords are only processed while issuing a session. Inventory queries and
 * mutations carry an opaque token and renew its sliding expiry after every
 * successful validation.
 */
public final class PlayerInventorySessionManager {
    public static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(5);
    public static final Duration DEFAULT_LOCK_DURATION = Duration.ofMinutes(10);
    public static final int DEFAULT_MAX_PASSWORD_FAILURES = 5;

    private static final int TOKEN_BYTES = 32;
    private static final String INVALID_TOKEN = "玩家仓库访问令牌无效或已过期";

    private final PlayerInventoryAuthStore authStore;
    private final LongSupplier clock;
    private final long sessionTtlMillis;
    private final long lockDurationMillis;
    private final int maxPasswordFailures;
    private final SecureRandom secureRandom;
    private final ConcurrentHashMap<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<FailureKey, FailureState> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PeerScopeKey, ConcurrentHashMap.KeySetView<String, Boolean>> subscriptions =
            new ConcurrentHashMap<>();

    public PlayerInventorySessionManager(PlayerInventoryAuthStore authStore) {
        this(authStore, System::currentTimeMillis, DEFAULT_SESSION_TTL,
                DEFAULT_LOCK_DURATION, DEFAULT_MAX_PASSWORD_FAILURES, new SecureRandom());
    }

    PlayerInventorySessionManager(PlayerInventoryAuthStore authStore, LongSupplier clock,
                                  Duration sessionTtl, Duration lockDuration,
                                  int maxPasswordFailures) {
        this(authStore, clock, sessionTtl, lockDuration, maxPasswordFailures, new SecureRandom());
    }

    PlayerInventorySessionManager(PlayerInventoryAuthStore authStore, LongSupplier clock,
                                  Duration sessionTtl, Duration lockDuration,
                                  int maxPasswordFailures, SecureRandom secureRandom) {
        this.authStore = Objects.requireNonNull(authStore, "authStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionTtlMillis = requirePositive(sessionTtl, "sessionTtl");
        this.lockDurationMillis = requirePositive(lockDuration, "lockDuration");
        if (maxPasswordFailures <= 0) {
            throw new IllegalArgumentException("maxPasswordFailures must be positive");
        }
        this.maxPasswordFailures = maxPasswordFailures;
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public SessionResult authenticate(InventoryScope scope, String ownerName, String password,
                                      AccessPrincipal principal) {
        requirePlayerScope(scope);
        AccessPrincipal normalizedPrincipal = requirePrincipal(principal);
        long now = clock.getAsLong();
        FailureKey failureKey = FailureKey.of(normalizedPrincipal);
        FailureState existingFailure = failures.get(failureKey);
        if (existingFailure != null && existingFailure.isLocked(now)) {
            return SessionResult.locked(existingFailure.lockedUntil());
        }
        if (existingFailure != null && existingFailure.lockedUntil() > 0) {
            failures.remove(failureKey, existingFailure);
        }

        PlayerInventoryAuthStore.AuthResult auth = authStore.verify(scope, password);
        if (!auth.success()) {
            FailureState failure = recordFailure(failureKey, now);
            if (failure.isLocked(now)) {
                return SessionResult.locked(failure.lockedUntil());
            }
            return SessionResult.fail(auth.failReason());
        }

        failures.remove(failureKey);
        String token = newToken();
        String tokenHash = hashToken(token);
        long expiresAt = safeAdd(now, sessionTtlMillis);
        SessionRecord record = new SessionRecord(scope, normalizeOwnerName(ownerName),
                normalizedPrincipal, expiresAt);
        sessions.put(tokenHash, record);
        subscriptions.computeIfAbsent(PeerScopeKey.of(normalizedPrincipal.peerId(), scope),
                ignored -> ConcurrentHashMap.newKeySet()).add(tokenHash);
        return SessionResult.success(token, scope, record.ownerName(), expiresAt);
    }

    public SessionResult validateAndRefresh(String token, AccessPrincipal principal) {
        if (token == null || token.isBlank()) {
            return SessionResult.fail(INVALID_TOKEN);
        }
        AccessPrincipal normalizedPrincipal;
        try {
            normalizedPrincipal = requirePrincipal(principal);
        } catch (IllegalArgumentException e) {
            return SessionResult.fail(INVALID_TOKEN);
        }
        String tokenHash = hashToken(token);
        long now = clock.getAsLong();
        AtomicReference<SessionResult> result = new AtomicReference<>(SessionResult.fail(INVALID_TOKEN));
        AtomicReference<SessionRecord> removed = new AtomicReference<>();
        sessions.compute(tokenHash, (ignored, record) -> {
            if (record == null) {
                return null;
            }
            if (record.expiresAt() <= now || !record.principal().equals(normalizedPrincipal)) {
                if (record.expiresAt() <= now) {
                    removed.set(record);
                    return null;
                }
                return record;
            }
            long expiresAt = safeAdd(now, sessionTtlMillis);
            SessionRecord refreshed = record.withExpiresAt(expiresAt);
            result.set(SessionResult.success(token, refreshed.scope(), refreshed.ownerName(), expiresAt));
            return refreshed;
        });
        if (removed.get() != null) {
            removeSubscription(tokenHash, removed.get());
        }
        return result.get();
    }

    public boolean hasActiveSession(String peerId, InventoryScope scope) {
        if (peerId == null || peerId.isBlank() || scope == null || !scope.isPlayer()) {
            return false;
        }
        PeerScopeKey key = PeerScopeKey.of(peerId, scope);
        ConcurrentHashMap.KeySetView<String, Boolean> tokenHashes = subscriptions.get(key);
        if (tokenHashes == null || tokenHashes.isEmpty()) {
            return false;
        }
        long now = clock.getAsLong();
        boolean active = false;
        for (String tokenHash : tokenHashes) {
            SessionRecord record = sessions.get(tokenHash);
            if (record == null || record.expiresAt() <= now) {
                if (record != null && sessions.remove(tokenHash, record)) {
                    removeSubscription(tokenHash, record);
                } else {
                    tokenHashes.remove(tokenHash);
                }
                continue;
            }
            active = true;
        }
        if (tokenHashes.isEmpty()) {
            subscriptions.remove(key, tokenHashes);
        }
        return active;
    }

    public void revokeScope(InventoryScope scope) {
        if (scope == null || !scope.isPlayer()) {
            return;
        }
        for (Map.Entry<String, SessionRecord> entry : sessions.entrySet()) {
            SessionRecord record = entry.getValue();
            if (scope.equals(record.scope()) && sessions.remove(entry.getKey(), record)) {
                removeSubscription(entry.getKey(), record);
            }
        }
    }

    public void revokePeer(String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return;
        }
        String normalizedPeer = normalize(peerId);
        for (Map.Entry<String, SessionRecord> entry : sessions.entrySet()) {
            SessionRecord record = entry.getValue();
            if (record.principal().peerId().equals(normalizedPeer)
                    && sessions.remove(entry.getKey(), record)) {
                removeSubscription(entry.getKey(), record);
            }
        }
    }

    /** Clears all process-local tokens, subscriptions, and password-failure state. */
    public void clear() {
        sessions.clear();
        subscriptions.clear();
        failures.clear();
    }

    public long sessionTtlMillis() {
        return sessionTtlMillis;
    }

    private FailureState recordFailure(FailureKey key, long now) {
        return failures.compute(key, (ignored, previous) -> {
            if (previous == null || (previous.lockedUntil() > 0 && previous.lockedUntil() <= now)) {
                previous = new FailureState(0, 0);
            }
            if (previous.isLocked(now)) {
                return previous;
            }
            int attempts = previous.attempts() + 1;
            long lockedUntil = attempts >= maxPasswordFailures ? safeAdd(now, lockDurationMillis) : 0;
            return new FailureState(attempts, lockedUntil);
        });
    }

    private void removeSubscription(String tokenHash, SessionRecord record) {
        PeerScopeKey key = PeerScopeKey.of(record.principal().peerId(), record.scope());
        ConcurrentHashMap.KeySetView<String, Boolean> tokenHashes = subscriptions.get(key);
        if (tokenHashes == null) {
            return;
        }
        tokenHashes.remove(tokenHash);
        if (tokenHashes.isEmpty()) {
            subscriptions.remove(key, tokenHashes);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private AccessPrincipal requirePrincipal(AccessPrincipal principal) {
        if (principal == null || principal.peerId() == null || principal.peerId().isBlank()
                || principal.requesterId() == null || principal.requesterId().isBlank()) {
            throw new IllegalArgumentException("玩家仓库访问主体无效");
        }
        return new AccessPrincipal(normalize(principal.peerId()), normalize(principal.requesterId()));
    }

    private void requirePlayerScope(InventoryScope scope) {
        if (scope == null || !scope.isPlayer() || scope.getScopeId().isBlank()) {
            throw new IllegalArgumentException("玩家仓库 scope 无效");
        }
    }

    private String normalizeOwnerName(String ownerName) {
        return ownerName != null ? ownerName.trim() : "";
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static long requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        long millis = duration.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return millis;
    }

    private static long safeAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public record AccessPrincipal(String peerId, String requesterId) {
    }

    public record SessionResult(boolean success, boolean locked, String failReason,
                                String token, InventoryScope scope, String ownerName,
                                long expiresAt, long lockedUntil) {
        static SessionResult success(String token, InventoryScope scope, String ownerName, long expiresAt) {
            return new SessionResult(true, false, null, token, scope, ownerName, expiresAt, 0);
        }

        static SessionResult fail(String failReason) {
            return new SessionResult(false, false, failReason, null, null, null, 0, 0);
        }

        static SessionResult locked(long lockedUntil) {
            return new SessionResult(false, true, "密码错误次数过多，玩家仓库访问已锁定",
                    null, null, null, 0, lockedUntil);
        }
    }

    private record SessionRecord(InventoryScope scope, String ownerName,
                                 AccessPrincipal principal, long expiresAt) {
        SessionRecord withExpiresAt(long value) {
            return new SessionRecord(scope, ownerName, principal, value);
        }
    }

    private record FailureKey(String peerId, String requesterId) {
        static FailureKey of(AccessPrincipal principal) {
            return new FailureKey(principal.peerId(), principal.requesterId());
        }
    }

    private record FailureState(int attempts, long lockedUntil) {
        boolean isLocked(long now) {
            return lockedUntil > now;
        }
    }

    private record PeerScopeKey(String peerId, InventoryScope scope) {
        static PeerScopeKey of(String peerId, InventoryScope scope) {
            return new PeerScopeKey(normalize(peerId), scope);
        }
    }
}
