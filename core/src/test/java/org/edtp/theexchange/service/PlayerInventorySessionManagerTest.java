package org.edtp.theexchange.service;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.storage.DatabaseManager;
import org.edtp.theexchange.storage.PlayerInventoryAuthStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInventorySessionManagerTest {
    private static final Duration SESSION_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(10);

    @TempDir
    Path tempDir;
    private final List<DatabaseManager> databases = new ArrayList<>();

    @AfterEach
    void closeDatabases() {
        databases.forEach(DatabaseManager::close);
        databases.clear();
    }

    @Test
    void issuedTokenIsBoundToPeerAndRequesterAndUsesSlidingExpiry() {
        TestContext context = context();
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        context.authStore.setPassword(scope, "Steve", "secret");
        PlayerInventorySessionManager.AccessPrincipal principal =
                new PlayerInventorySessionManager.AccessPrincipal("survival", "viewer-uuid");

        PlayerInventorySessionManager.SessionResult issued = context.manager.authenticate(
                scope, "Steve", "secret", principal);

        assertTrue(issued.success());
        assertNotNull(issued.token());
        assertEquals(scope, issued.scope());
        assertTrue(context.manager.hasActiveSession("survival", scope));

        assertFalse(context.manager.validateAndRefresh(issued.token(),
                new PlayerInventorySessionManager.AccessPrincipal("creative", "viewer-uuid")).success());
        assertFalse(context.manager.validateAndRefresh(issued.token(),
                new PlayerInventorySessionManager.AccessPrincipal("survival", "other-viewer")).success());

        context.clock.addAndGet(Duration.ofMinutes(4).toMillis());
        PlayerInventorySessionManager.SessionResult refreshed =
                context.manager.validateAndRefresh(issued.token(), principal);
        assertTrue(refreshed.success());
        assertEquals(context.clock.get() + SESSION_TTL.toMillis(), refreshed.expiresAt());

        context.clock.addAndGet(Duration.ofMinutes(2).toMillis());
        assertTrue(context.manager.validateAndRefresh(issued.token(), principal).success(),
                "sliding refresh must keep the token alive past its original expiry");

        context.clock.addAndGet(SESSION_TTL.plusSeconds(1).toMillis());
        assertFalse(context.manager.validateAndRefresh(issued.token(), principal).success());
        assertFalse(context.manager.hasActiveSession("survival", scope));
    }

    @Test
    void fifthBadPasswordLocksRequesterForTenMinutes() {
        TestContext context = context();
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        context.authStore.setPassword(scope, "Steve", "secret");
        PlayerInventorySessionManager.AccessPrincipal principal =
                new PlayerInventorySessionManager.AccessPrincipal("survival", "viewer-uuid");

        for (int attempt = 1; attempt < 5; attempt++) {
            PlayerInventorySessionManager.SessionResult result = context.manager.authenticate(
                    scope, "Steve", "wrong-" + attempt, principal);
            assertFalse(result.success());
            assertFalse(result.locked(), "attempt " + attempt + " must not lock early");
        }

        PlayerInventorySessionManager.SessionResult fifth = context.manager.authenticate(
                scope, "Steve", "wrong-5", principal);
        assertFalse(fifth.success());
        assertTrue(fifth.locked());
        assertEquals(context.clock.get() + LOCK_DURATION.toMillis(), fifth.lockedUntil());

        PlayerInventorySessionManager.SessionResult correctWhileLocked = context.manager.authenticate(
                scope, "Steve", "secret", principal);
        assertFalse(correctWhileLocked.success());
        assertTrue(correctWhileLocked.locked());

        context.clock.addAndGet(LOCK_DURATION.minusMillis(1).toMillis());
        assertFalse(context.manager.authenticate(scope, "Steve", "secret", principal).success());

        context.clock.incrementAndGet();
        PlayerInventorySessionManager.SessionResult afterUnlock = context.manager.authenticate(
                scope, "Steve", "secret", principal);
        assertTrue(afterUnlock.success());
        assertFalse(afterUnlock.locked());
    }

    @Test
    void lockIsScopedToTheAccessingUserAndPasswordResetRevokesOwnerSessions() {
        TestContext context = context();
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        context.authStore.setPassword(scope, "Steve", "secret");
        PlayerInventorySessionManager.AccessPrincipal attacker =
                new PlayerInventorySessionManager.AccessPrincipal("survival", "attacker");
        PlayerInventorySessionManager.AccessPrincipal owner =
                new PlayerInventorySessionManager.AccessPrincipal("survival", "owner");

        for (int attempt = 0; attempt < 5; attempt++) {
            context.manager.authenticate(scope, "Steve", "bad", attacker);
        }
        assertTrue(context.manager.authenticate(scope, "Steve", "secret", owner).success(),
                "one user must not be able to lock every other user out of the warehouse");

        PlayerInventorySessionManager.SessionResult session = context.manager.authenticate(
                scope, "Steve", "secret", owner);
        assertTrue(session.success());
        context.manager.revokeScope(scope);

        assertFalse(context.manager.validateAndRefresh(session.token(), owner).success());
        assertFalse(context.manager.hasActiveSession("survival", scope));
    }

    @Test
    void clearingInMemoryStateInvalidatesAllTokensAndLockouts() {
        TestContext context = context();
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        context.authStore.setPassword(scope, "Steve", "secret");
        PlayerInventorySessionManager.AccessPrincipal principal =
                new PlayerInventorySessionManager.AccessPrincipal("survival", "viewer-uuid");
        PlayerInventorySessionManager.SessionResult session = context.manager.authenticate(
                scope, "Steve", "secret", principal);
        assertTrue(session.success());

        for (int attempt = 0; attempt < 5; attempt++) {
            context.manager.authenticate(scope, "Steve", "wrong", principal);
        }
        assertTrue(context.manager.authenticate(scope, "Steve", "secret", principal).locked());

        context.manager.clear();

        assertFalse(context.manager.validateAndRefresh(session.token(), principal).success());
        assertFalse(context.manager.hasActiveSession("survival", scope));
        assertTrue(context.manager.authenticate(scope, "Steve", "secret", principal).success(),
                "a core restart/reload starts with no persisted lockout or token state");
    }

    private TestContext context() {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("session-test.db").toString());
        db.initialize();
        databases.add(db);
        PlayerInventoryAuthStore authStore = new PlayerInventoryAuthStore(db);
        AtomicLong clock = new AtomicLong(1_000_000L);
        PlayerInventorySessionManager manager = new PlayerInventorySessionManager(
                authStore, clock::get, SESSION_TTL, LOCK_DURATION, 5);
        return new TestContext(authStore, manager, clock);
    }

    private record TestContext(PlayerInventoryAuthStore authStore,
                               PlayerInventorySessionManager manager,
                               AtomicLong clock) {
    }
}
