package org.edtp.theexchange.service;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncEnginePlayerInventoryGateTest {

    @Test
    void disabledGateRejectsEveryPlayerInventoryQueryWithoutNetworkAccess() {
        SyncEngine engine = new SyncEngine(null, null, null, 1_000, () -> false);
        InventoryAccess access = InventoryAccess.playerSession(
                "Steve", "token", "requester", "Viewer",
                InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                Long.MAX_VALUE);

        assertDisabled(engine.refreshChangedSlotsAsync("remote", access));
        assertDisabled(engine.querySlotsAsync("remote", List.of(), access));
        assertDisabled(engine.querySlotVersionAsync("remote", 0, access));
        assertDisabled(engine.querySlotStateAsync("remote", 0, access));
    }

    private static void assertDisabled(CompletableFuture<?> future) {
        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertEquals(ExchangeService.PLAYER_INVENTORIES_DISABLED, error.getCause().getMessage());
    }
}
