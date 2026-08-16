package org.edtp.theexchange.service;

import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.TransactionStatus;
import org.edtp.theexchange.storage.MutationRecoveryJournal;
import org.edtp.theexchange.storage.SettlementVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class MutationTransactionRestartLoopbackTest {
    private static final long PROBE_MILLIS = 120L;

    @TempDir
    static Path tempDir;

    private LoopbackMutationTestCluster cluster;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new LoopbackMutationTestCluster(tempDir, PROBE_MILLIS, true);
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) cluster.close();
    }

    @Test
    void outboundRecoveringStateSurvivesRestartAndQueriesTheStoredRemoteDecision() {
        String transactionId = "restart-outbound-recovering";
        cluster.a.faults.dropNext(FrameType.MUTATION_RESULT);
        cluster.a.faults.holdNext(FrameType.TRANSACTION_STATUS);
        CompletableFuture<MutationTransactionCoordinator.Receipt> abandonedFuture =
                cluster.executeFromA(cluster.putRequest(transactionId, 31));

        cluster.a.faults.awaitStatus(transactionId, TransactionStatus.State.DECIDED);
        awaitJournal(cluster.a, MutationRecoveryJournal.Direction.OUTBOUND,
                transactionId, "RECOVERING");
        awaitJournal(cluster.b, MutationRecoveryJournal.Direction.INBOUND,
                transactionId, "DECIDED");
        assertFalse(abandonedFuture.isDone());
        assertEquals(1, cluster.b.inventory.commits(transactionId));

        cluster.checkpointAndRestartA();
        cluster.awaitClosed();

        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        assertTrue(cluster.a.journal.loadAll().isEmpty());
        assertTrue(cluster.b.journal.loadAll().isEmpty());
        assertTrue(cluster.a.vault.list("player-uuid").isEmpty());
    }

    @Test
    void inboundDecidedStateSurvivesAuthorityRestartWithoutReexecution() throws Exception {
        String transactionId = "restart-inbound-decided";
        cluster.a.faults.dropNext(FrameType.MUTATION_RESULT);
        cluster.a.faults.holdNext(FrameType.TRANSACTION_STATUS);
        CompletableFuture<MutationTransactionCoordinator.Receipt> future =
                cluster.executeFromA(cluster.putRequest(transactionId, 32));

        cluster.a.faults.awaitStatus(transactionId, TransactionStatus.State.DECIDED);
        awaitJournal(cluster.b, MutationRecoveryJournal.Direction.INBOUND,
                transactionId, "DECIDED");
        assertFalse(future.isDone());

        cluster.checkpointAndRestartB();
        MutationTransactionCoordinator.Receipt receipt = future.get(5, TimeUnit.SECONDS);

        assertTrue(receipt.result().isSuccess());
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
        assertTrue(cluster.a.journal.loadAll().isEmpty());
        assertTrue(cluster.b.journal.loadAll().isEmpty());
    }

    @Test
    void bothPeersCanRestartFromRecoveringAndDecidedCheckpoints() {
        String transactionId = "restart-both";
        cluster.a.faults.dropNext(FrameType.MUTATION_RESULT);
        cluster.a.faults.holdNext(FrameType.TRANSACTION_STATUS);
        CompletableFuture<MutationTransactionCoordinator.Receipt> abandonedFuture =
                cluster.executeFromA(cluster.putRequest(transactionId, 33));

        cluster.a.faults.awaitStatus(transactionId, TransactionStatus.State.DECIDED);
        awaitJournal(cluster.a, MutationRecoveryJournal.Direction.OUTBOUND,
                transactionId, "RECOVERING");
        awaitJournal(cluster.b, MutationRecoveryJournal.Direction.INBOUND,
                transactionId, "DECIDED");
        assertFalse(abandonedFuture.isDone());

        cluster.checkpointAndRestartBoth();
        cluster.awaitClosed();

        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        assertTrue(cluster.a.journal.loadAll().isEmpty());
        assertTrue(cluster.b.journal.loadAll().isEmpty());
    }

    @Test
    void settledWaitCloseCheckpointSurvivesRestartAfterAuthorityForgotTheTransaction() {
        String transactionId = "restart-settled";
        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 34)).join();
        cluster.a.faults.dropNext(FrameType.TRANSACTION_CLOSED);

        receipt.acknowledgeSettlement();
        cluster.a.faults.awaitReceived(FrameType.TRANSACTION_CLOSED, 1);
        LoopbackMutationTestCluster.await(
                () -> cluster.b.coordinator.inboundCount() == 0,
                LoopbackMutationTestCluster.DEFAULT_WAIT,
                "authority did not forget the settled transaction");
        assertEquals(1, cluster.a.coordinator.outboundCount());
        cluster.a.coordinator.checkpointOutstanding();
        awaitJournal(cluster.a, MutationRecoveryJournal.Direction.OUTBOUND,
                transactionId, "SETTLED_WAIT_CLOSE");

        cluster.checkpointAndRestartA();
        cluster.awaitClosed();

        assertEquals(1, cluster.b.inventory.commits(transactionId));
        assertTrue(cluster.a.journal.loadAll().isEmpty());
        assertTrue(cluster.b.journal.loadAll().isEmpty());
        assertTrue(cluster.a.vault.list("player-uuid").isEmpty());
    }

    @Test
    void recoveredFailedPutIsVaultedExactlyOnceBeforeSettlement() throws Exception {
        String transactionId = "restart-vault-return";
        cluster.a.faults.holdNext(FrameType.MUTATION_RESULT);
        cluster.b.inventory.pauseExecution(transactionId);
        CompletableFuture<MutationTransactionCoordinator.Receipt> future = cluster.executeFromA(
                cluster.putRequest(transactionId, 35));
        cluster.b.inventory.awaitExecutionStarted(transactionId);
        cluster.b.inventory.failExecution(transactionId,
                new IllegalStateException("simulated authority failure"));

        TransactionStatus decided = cluster.a.faults.awaitStatus(
                transactionId, TransactionStatus.State.DECIDED);
        assertFalse(decided.getResult().isSuccess());
        MutationTransactionCoordinator.Receipt receipt = future.get(5, TimeUnit.SECONDS);
        assertEquals("INTERNAL_ERROR", receipt.result().getFailReason());
        assertEquals(0, cluster.b.inventory.commits(transactionId));
        awaitJournal(cluster.a, MutationRecoveryJournal.Direction.OUTBOUND,
                transactionId, "DECIDED");

        cluster.checkpointAndRestartA();
        cluster.awaitClosed();

        List<SettlementVault.Entry> entries = cluster.a.vault.list("player-uuid");
        assertEquals(1, entries.size());
        SettlementVault.Entry entry = entries.getFirst();
        assertEquals(transactionId, entry.transactionId());
        assertEquals("RECOVERED_RETURN", entry.reason());
        assertEquals("minecraft:stone", entry.item().getItemId());
        assertEquals(1, entry.item().getCount());
        assertEquals(0, entry.item().getMaxStackSize(),
                "transient remote stack limits must not be persisted");
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(0, cluster.b.inventory.commits(transactionId));
        assertTrue(cluster.a.journal.loadAll().isEmpty());
        assertTrue(cluster.b.journal.loadAll().isEmpty());
    }

    private void awaitJournal(LoopbackMutationTestCluster.Node node,
                              MutationRecoveryJournal.Direction direction,
                              String transactionId, String state) {
        LoopbackMutationTestCluster.await(() -> node.journal.loadAll().stream().anyMatch(entry ->
                        entry.direction() == direction
                                && transactionId.equals(entry.request().getTransactionId())
                                && state.equals(entry.state())),
                LoopbackMutationTestCluster.DEFAULT_WAIT,
                "missing journal checkpoint " + direction + "/" + transactionId + "/" + state);
    }
}
