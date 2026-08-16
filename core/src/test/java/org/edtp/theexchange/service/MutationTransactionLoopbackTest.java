package org.edtp.theexchange.service;

import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.MutationHashes;
import org.edtp.theexchange.network.protocol.messages.MutationExecute;
import org.edtp.theexchange.network.protocol.messages.MutationResultMessage;
import org.edtp.theexchange.network.protocol.messages.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class MutationTransactionLoopbackTest {
    private static final long PROBE_MILLIS = 120L;

    @TempDir
    static Path tempDir;

    private LoopbackMutationTestCluster cluster;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new LoopbackMutationTestCluster(tempDir, PROBE_MILLIS);
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) cluster.close();
    }

    @Test
    void normalMutationClosesOnBothNodesAfterSettlement() throws Exception {
        String transactionId = "normal";

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 0)).get(5, TimeUnit.SECONDS);

        assertTrue(receipt.result().isSuccess());
        assertEquals(transactionId, receipt.result().getTransactionId());
        assertEquals(MutationHashes.result(receipt.result()), receipt.result().getResultHash());
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.a.coordinator.outboundCount());
        assertEquals(1, cluster.b.coordinator.inboundCount());

        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void lostInitialExecuteRecoversThroughUnknownStatusWithoutDuplicateMutation() throws Exception {
        String transactionId = "lost-execute";
        cluster.b.faults.dropNext(FrameType.MUTATION_EXECUTE);

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 1)).get(5, TimeUnit.SECONDS);

        assertTrue(receipt.result().isSuccess());
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.faults.received(FrameType.MUTATION_EXECUTE));
        assertEquals(1, cluster.b.faults.received(FrameType.MUTATION_RECOVER));
        TransactionStatus unknown = cluster.a.faults.awaitStatus(
                transactionId, TransactionStatus.State.UNKNOWN);
        assertEquals(receipt.result().getIntentHash(), unknown.getIntentHash());

        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void lostResultIsRecoveredByQueryingTheStoredDecision() throws Exception {
        String transactionId = "lost-result";
        cluster.a.faults.dropNext(FrameType.MUTATION_RESULT);

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 2)).get(5, TimeUnit.SECONDS);

        assertTrue(receipt.result().isSuccess());
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.faults.received(FrameType.TRANSACTION_QUERY));
        TransactionStatus decided = cluster.a.faults.awaitStatus(
                transactionId, TransactionStatus.State.DECIDED);
        assertNotNull(decided.getResult());
        assertEquals(receipt.result().getResultHash(), decided.getResult().getResultHash());

        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void lateOriginalResultAfterQueryRecoveryCannotResurrectClosedTransaction() throws Exception {
        String transactionId = "late-result";
        cluster.a.faults.holdNext(FrameType.MUTATION_RESULT);

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 3)).get(5, TimeUnit.SECONDS);
        assertTrue(receipt.result().isSuccess());
        assertTrue(cluster.a.faults.received(FrameType.TRANSACTION_STATUS) >= 1,
                "receipt should have been recovered from the status response");

        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
        cluster.a.faults.releaseOne(FrameType.MUTATION_RESULT);

        assertEquals(0, cluster.a.coordinator.outboundCount());
        assertEquals(0, cluster.b.coordinator.inboundCount());
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertTrue(cluster.a.logs.stream().anyMatch(message ->
                message.contains("Ignored orphan mutation result") && message.contains(transactionId)));
    }

    @Test
    void duplicateAndConflictingExecuteFramesStillMutateOnlyOnce() throws Exception {
        String transactionId = "duplicate-execute";
        cluster.b.faults.duplicateNext(FrameType.MUTATION_EXECUTE);
        MutationExecute original = cluster.putRequest(transactionId, 4);

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(original)
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, cluster.b.inventory.executions(transactionId));

        MutationExecute conflict = cluster.putRequest(transactionId, 4);
        NeutralItem different = conflict.getOfferedItem().copy();
        different.setItemId("minecraft:dirt");
        conflict.setOfferedItem(different);
        conflict.setIntentHash(MutationHashes.intent(conflict));
        cluster.a.connection(LoopbackMutationTestCluster.NODE_B)
                .sendOneWay(FrameType.MUTATION_EXECUTE, conflict).get(5, TimeUnit.SECONDS);
        cluster.b.faults.awaitReceived(FrameType.MUTATION_EXECUTE, 2);
        MutationResultMessage conflictResult = cluster.a.faults.awaitMessage(
                FrameType.MUTATION_RESULT, MutationResultMessage.class,
                result -> transactionId.equals(result.getTransactionId())
                        && "IDEMPOTENCY_CONFLICT".equals(result.getFailReason()));

        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(original.getIntentHash(), receipt.result().getIntentHash());
        assertEquals(conflict.getIntentHash(), conflictResult.getIntentHash());
        assertFalse(conflictResult.isSuccess());

        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void lostSettlementIsRetriedUntilBothNodesClose() throws Exception {
        String transactionId = "lost-settlement";
        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 5)).get(5, TimeUnit.SECONDS);
        cluster.b.faults.dropNext(FrameType.TRANSACTION_SETTLED);

        receipt.acknowledgeSettlement();

        cluster.b.faults.awaitReceived(FrameType.TRANSACTION_SETTLED, 2);
        cluster.awaitClosed();
        assertEquals(1, cluster.b.inventory.executions(transactionId));
    }

    @Test
    void lostCloseConfirmationIsRecoveredByRepeatingSettlement() throws Exception {
        String transactionId = "lost-close";
        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 6)).get(5, TimeUnit.SECONDS);
        cluster.a.faults.dropNext(FrameType.TRANSACTION_CLOSED);

        receipt.acknowledgeSettlement();

        cluster.a.faults.awaitReceived(FrameType.TRANSACTION_CLOSED, 2);
        cluster.awaitClosed();
        assertEquals(1, cluster.b.inventory.executions(transactionId));
    }

    @Test
    void disconnectDuringRemoteExecutionRecoversOnReconnectAndFencesOldConnection() throws Exception {
        String transactionId = "disconnect-during-execution";
        cluster.b.inventory.pauseExecution(transactionId);
        Connection oldInbound = cluster.b.connection(LoopbackMutationTestCluster.NODE_A);
        CompletableFuture<MutationTransactionCoordinator.Receipt> future = cluster.executeFromA(
                cluster.putRequest(transactionId, 7));
        cluster.b.inventory.awaitExecutionStarted(transactionId);
        assertEquals(1, cluster.b.inventory.executions(transactionId));

        cluster.disconnectAfromB();
        cluster.b.inventory.completeExecution(transactionId);
        assertFalse(future.isDone(), "an offline result must remain pending until recovery");
        assertEquals(0, cluster.a.faults.received(FrameType.MUTATION_RESULT));
        cluster.connectAtoB();
        assertNotSame(oldInbound, cluster.b.connection(LoopbackMutationTestCluster.NODE_A));

        MutationExecute fenced = cluster.putRequest("fenced-old-connection", 8);
        assertTrue(cluster.b.coordinator.route(oldInbound, FrameType.MUTATION_RECOVER,
                fenced, cluster.b.inventory::execute));
        assertEquals(0, cluster.b.inventory.executions("fenced-old-connection"));
        assertTrue(cluster.b.logs.stream().anyMatch(message -> message.contains("fenced connection")));

        MutationTransactionCoordinator.Receipt receipt = future.get(5, TimeUnit.SECONDS);
        assertTrue(receipt.result().isSuccess());
        assertEquals(1, cluster.b.inventory.executions(transactionId));

        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void independentTransactionsExecuteConcurrentlyAcrossTheRealConnection() throws Exception {
        int transactionCount = 4;
        cluster.b.inventory.requireConcurrentExecutions(transactionCount);
        List<CompletableFuture<MutationTransactionCoordinator.Receipt>> futures = new ArrayList<>();

        for (int index = 0; index < transactionCount; index++) {
            futures.add(cluster.executeFromA(cluster.putRequest("concurrent-" + index, 10 + index)));
        }

        List<MutationTransactionCoordinator.Receipt> receipts = new ArrayList<>();
        for (CompletableFuture<MutationTransactionCoordinator.Receipt> future : futures) {
            receipts.add(future.get(5, TimeUnit.SECONDS));
        }

        assertEquals(transactionCount, cluster.b.inventory.executions());
        assertEquals(transactionCount, cluster.b.inventory.maxConcurrentExecutions(),
                "all independent transactions must reach the execution barrier concurrently");
        for (int index = 0; index < transactionCount; index++) {
            assertEquals(1, cluster.b.inventory.executions("concurrent-" + index));
        }
        receipts.forEach(MutationTransactionCoordinator.Receipt::acknowledgeSettlement);
        cluster.awaitClosed();
    }
}
