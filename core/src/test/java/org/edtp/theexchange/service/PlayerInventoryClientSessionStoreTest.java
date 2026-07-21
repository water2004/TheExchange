package org.edtp.theexchange.service;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInventoryClientSessionStoreTest {

    @Test
    void findsRememberedSessionByServerOwnerAndRequesterIgnoringCase() {
        AtomicLong now = new AtomicLong(1_000);
        PlayerInventoryClientSessionStore store = new PlayerInventoryClientSessionStore(now::get);
        InventoryAccess access = access("Steve", "token-one", 2_000, 1_000);

        store.remember("Survival", access);

        Optional<InventoryAccess> found = store.findValid("survival", "steve", "REQUESTER");
        assertTrue(found.isPresent());
        assertEquals("token-one", found.get().token());
        assertEquals("Steve", found.get().ownerName());
    }

    @Test
    void expiredSessionIsRemovedAndCannotBeResolved() {
        AtomicLong now = new AtomicLong(1_000);
        PlayerInventoryClientSessionStore store = new PlayerInventoryClientSessionStore(now::get);
        InventoryAccess access = access("Steve", "token-one", 1_500, 500);
        store.remember("survival", access);

        now.set(1_500);

        assertTrue(store.findValid("survival", "Steve", "requester").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.resolve("survival", access));
    }

    @Test
    void successfulUseSlidesLocalExpiryAndReturnsCurrentTokenForStaleViews() {
        AtomicLong now = new AtomicLong(1_000);
        PlayerInventoryClientSessionStore store = new PlayerInventoryClientSessionStore(now::get);
        InventoryAccess first = access("Steve", "token-one", 2_000, 1_000);
        store.remember("survival", first);

        InventoryAccess replacement = access("Steve", "token-two", 2_500, 1_000);
        store.remember("survival", replacement);
        now.set(2_000);

        InventoryAccess current = store.resolve("survival", first);
        assertEquals("token-two", current.token(), "an already-open view must use the newest token");

        InventoryAccess touched = store.touch("survival", current);
        assertEquals(3_000, touched.expiresAt());
        assertEquals(3_000, store.findValid("survival", "Steve", "requester").orElseThrow().expiresAt());
    }

    @Test
    void sessionsAreIsolatedByRequester() {
        PlayerInventoryClientSessionStore store = new PlayerInventoryClientSessionStore(() -> 1_000);
        store.remember("survival", access("Steve", "token-one", 2_000, 1_000));

        assertTrue(store.findValid("survival", "Steve", "someone-else").isEmpty());
    }

    private static InventoryAccess access(String owner, String token, long expiresAt, long ttl) {
        return InventoryAccess.playerSession(owner, token, "requester", "Viewer",
                InventoryScope.player("owner-uuid"), expiresAt, ttl);
    }
}
