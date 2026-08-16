package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.MutationHashes;
import org.edtp.theexchange.network.protocol.messages.MutationExecute;
import org.edtp.theexchange.network.protocol.messages.MutationKind;
import org.edtp.theexchange.network.protocol.messages.MutationResultMessage;
import org.edtp.theexchange.service.MutationTransactionCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MutationRecoveryJournalTest {
    @TempDir
    Path tempDir;

    @Test
    void unresolvedMutationRoundTripsWithoutPersistingSessionToken() {
        DatabaseManager database = database();
        MutationRecoveryJournal journal = new MutationRecoveryJournal(database);
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        MutationExecute request = request(InventoryAccess.playerSession(
                "Steve", "secret-session-token", "requester", "Viewer", scope, 99_999L));
        MutationResultMessage result = result(request, scope);

        journal.upsert(MutationRecoveryJournal.Direction.OUTBOUND, "peer", "DECIDED", request, result);
        List<MutationRecoveryJournal.Entry> entries = journal.loadAll();

        assertEquals(1, entries.size());
        assertEquals("", entries.getFirst().request().getAccess().token());
        assertEquals(scope, entries.getFirst().request().getAccess().resolvedScope());
        assertEquals(0, entries.getFirst().request().getOfferedItem().getMaxStackSize());
        assertEquals(0, entries.getFirst().result().getCurrentItem().getMaxStackSize());
        assertEquals(result.getResultHash(), entries.getFirst().result().getResultHash());
        assertTrue(MutationHashes.validIntent(entries.getFirst().request()));
        assertTrue(MutationHashes.validResult(entries.getFirst().result()));

        journal.delete(MutationRecoveryJournal.Direction.OUTBOUND, "peer", request.getTransactionId());
        assertTrue(journal.loadAll().isEmpty());
        database.close();
    }

    @Test
    void settlementVaultDepositIsIdempotentByTransaction() {
        DatabaseManager database = database();
        SettlementVault vault = new SettlementVault(database);
        NeutralItem item = item(3);

        vault.deposit("tx", "owner", "Owner", item, "RECOVERED_RETURN");
        vault.deposit("tx", "owner", "Owner", item(9), "DUPLICATE");

        List<SettlementVault.Entry> entries = vault.list("owner");
        assertEquals(1, entries.size());
        assertEquals(3, entries.getFirst().item().getCount());
        assertEquals(item, vault.claim("tx", "owner"));
        assertTrue(vault.list("owner").isEmpty());
        database.close();
    }

    @Test
    void tokenRefreshDoesNotChangeIntent() {
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        MutationExecute first = request(InventoryAccess.playerSession(
                "Steve", "token-one", "requester", "Viewer", scope, 100L));
        MutationExecute refreshed = request(InventoryAccess.playerSession(
                "Steve", "token-two", "requester", "Viewer", scope, 200L));
        assertEquals(first.getIntentHash(), refreshed.getIntentHash());
    }

    @Test
    void intentHashIsBoundToTransactionId() {
        MutationExecute first = request(InventoryAccess.server());
        MutationExecute second = new MutationExecute("different-tx", null, first.getKind(),
                first.getSlot(), first.getOfferedItem(), first.getExpectedItemId(),
                first.getExpectedVersion(), first.getCount(), first.isBoundedMerge(),
                first.getPlayerUuid(), first.getPlayerName(), first.getAccess());
        second.setIntentHash(MutationHashes.intent(second));

        assertNotEquals(first.getIntentHash(), second.getIntentHash());
    }

    @Test
    void recoveredResultMovesItemToVaultBeforeSettlement() {
        DatabaseManager database = database();
        MutationRecoveryJournal journal = new MutationRecoveryJournal(database);
        SettlementVault vault = new SettlementVault(database);
        MutationExecute request = request(InventoryAccess.server());
        MutationResultMessage rejected = new MutationResultMessage(request.getTransactionId(),
                request.getIntentHash(), null, request.getKind(), false, request.getSlot(),
                null, null, "VERSION_MISMATCH", 123L, 8, InventoryScope.server());
        rejected.setResultHash(MutationHashes.result(rejected));
        journal.upsert(MutationRecoveryJournal.Direction.OUTBOUND, "peer", "DECIDED",
                request, rejected);

        MutationTransactionCoordinator coordinator = new MutationTransactionCoordinator(
                1_000, ignored -> {}, journal, vault);

        assertEquals(1, coordinator.outboundCount());
        assertEquals(1, vault.list("owner").size());
        assertEquals(3, vault.list("owner").getFirst().item().getCount());
        coordinator.close();
        database.close();
    }

    @Test
    void coordinatorRejectsRecoveryEntryWithInvalidIntentHash() {
        DatabaseManager database = database();
        MutationRecoveryJournal journal = new MutationRecoveryJournal(database);
        MutationExecute corrupted = request(InventoryAccess.server());
        corrupted.setIntentHash("00");
        journal.upsert(MutationRecoveryJournal.Direction.OUTBOUND, "peer", "RECOVERING",
                corrupted, null);
        List<String> logs = new ArrayList<>();

        MutationTransactionCoordinator coordinator = new MutationTransactionCoordinator(
                1_000, logs::add, journal, new SettlementVault(database));

        assertEquals(0, coordinator.outboundCount());
        assertTrue(logs.stream().anyMatch(message -> message.contains("invalid recovery")));
        coordinator.close();
        database.close();
    }

    private DatabaseManager database() {
        DatabaseManager database = new DatabaseManager(tempDir.resolve("exchange.db").toString());
        database.initialize();
        return database;
    }

    private MutationExecute request(InventoryAccess access) {
        MutationExecute request = new MutationExecute("tx", null, MutationKind.PUT,
                4, item(3), null, 7, 3, false, "owner", "Owner", access);
        request.setIntentHash(MutationHashes.intent(request));
        return request;
    }

    private MutationResultMessage result(MutationExecute request, InventoryScope scope) {
        MutationResultMessage result = new MutationResultMessage(request.getTransactionId(),
                request.getIntentHash(), null, request.getKind(), true, request.getSlot(),
                item(3), null, null, 123L, 8, scope);
        result.setResultHash(MutationHashes.result(result));
        return result;
    }

    private NeutralItem item(int count) {
        NeutralItem item = new NeutralItem("minecraft:stone", count, "Stone", new byte[] {1, 2},
                false, "test");
        item.setMaxStackSize(64);
        return item;
    }
}
