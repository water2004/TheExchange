package org.edtp.theexchange.service;

import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.MutationHashes;
import org.edtp.theexchange.network.protocol.messages.MutationExecute;
import org.edtp.theexchange.network.protocol.messages.MutationResultMessage;
import org.edtp.theexchange.network.protocol.messages.TransactionClosed;
import org.edtp.theexchange.network.protocol.messages.TransactionSettled;
import org.edtp.theexchange.network.protocol.messages.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class MutationTransactionFaultMatrixLoopbackTest {
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
    void droppedQueryIsRetriedWithoutRepeatingTheMutation() throws Exception {
        String transactionId = "lost-query";
        cluster.a.faults.dropNext(FrameType.MUTATION_RESULT);
        cluster.b.faults.dropNext(FrameType.TRANSACTION_QUERY);

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 20)).get(5, TimeUnit.SECONDS);

        assertSuccessful(receipt, transactionId);
        assertTrue(cluster.b.faults.received(FrameType.TRANSACTION_QUERY) >= 2);
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void droppedStatusIsRetriedWithoutRepeatingTheMutation() throws Exception {
        String transactionId = "lost-status";
        cluster.a.faults.dropNext(FrameType.MUTATION_RESULT);
        cluster.a.faults.dropNext(FrameType.TRANSACTION_STATUS);

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 21)).get(5, TimeUnit.SECONDS);

        assertSuccessful(receipt, transactionId);
        List<TransactionStatus> statuses = cluster.a.faults.messages(
                FrameType.TRANSACTION_STATUS, TransactionStatus.class);
        assertTrue(statuses.size() >= 2);
        assertTrue(statuses.stream().allMatch(status -> status.getState() == TransactionStatus.State.DECIDED));
        assertTrue(statuses.stream().allMatch(status -> receipt.result().getIntentHash().equals(status.getIntentHash())));
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void droppedRecoverRepeatsRecoveryButCommitsOnlyOnce() throws Exception {
        String transactionId = "lost-recover";
        cluster.b.faults.dropNext(FrameType.MUTATION_EXECUTE);
        cluster.b.faults.dropNext(FrameType.MUTATION_RECOVER);

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 22)).get(5, TimeUnit.SECONDS);

        assertSuccessful(receipt, transactionId);
        assertTrue(cluster.b.faults.received(FrameType.MUTATION_RECOVER) >= 2);
        List<TransactionStatus> statuses = cluster.a.faults.messages(
                FrameType.TRANSACTION_STATUS, TransactionStatus.class);
        assertTrue(statuses.size() >= 2);
        assertTrue(statuses.stream().allMatch(status -> status.getState() == TransactionStatus.State.UNKNOWN));
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void runningStatusKeepsTheRequestPendingUntilTheOriginalExecutionCompletes() throws Exception {
        String transactionId = "running-status";
        cluster.b.inventory.pauseExecution(transactionId);
        CompletableFuture<MutationTransactionCoordinator.Receipt> future = cluster.executeFromA(
                cluster.putRequest(transactionId, 23));
        cluster.b.inventory.awaitExecutionStarted(transactionId);

        TransactionStatus running = cluster.a.faults.awaitStatus(
                transactionId, TransactionStatus.State.RUNNING);
        assertNull(running.getResult());
        assertFalse(future.isDone());
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(0, cluster.b.inventory.commits(transactionId));

        cluster.b.inventory.completeExecution(transactionId);
        MutationTransactionCoordinator.Receipt receipt = future.get(5, TimeUnit.SECONDS);
        assertSuccessful(receipt, transactionId);
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void invalidAndMismatchedResultsCannotCompleteTheReceipt() throws Exception {
        String transactionId = "forged-results";
        MutationExecute request = cluster.putRequest(transactionId, 24);
        cluster.b.inventory.pauseExecution(transactionId);
        CompletableFuture<MutationTransactionCoordinator.Receipt> future = cluster.executeFromA(request);
        cluster.b.inventory.awaitExecutionStarted(transactionId);

        MutationResultMessage invalidHash = resultFor(request, request.getIntentHash());
        invalidHash.setResultHash("invalid-result-hash");
        cluster.b.connection(LoopbackMutationTestCluster.NODE_A)
                .sendOneWay(FrameType.MUTATION_RESULT, invalidHash).get(5, TimeUnit.SECONDS);
        cluster.a.faults.awaitMessage(FrameType.MUTATION_RESULT, MutationResultMessage.class,
                result -> "invalid-result-hash".equals(result.getResultHash()));
        assertFalse(future.isDone());
        cluster.a.awaitLog(message -> message.contains("Rejected invalid mutation result"),
                "invalid result was not rejected");

        MutationResultMessage mismatchedIntent = resultFor(request, "different-intent");
        cluster.b.connection(LoopbackMutationTestCluster.NODE_A)
                .sendOneWay(FrameType.MUTATION_RESULT, mismatchedIntent).get(5, TimeUnit.SECONDS);
        cluster.a.faults.awaitMessage(FrameType.MUTATION_RESULT, MutationResultMessage.class,
                result -> "different-intent".equals(result.getIntentHash()));
        assertFalse(future.isDone());
        cluster.a.awaitLog(message -> message.contains("Rejected mismatched mutation result")
                        && message.contains(transactionId),
                "mismatched result was not rejected");

        cluster.b.inventory.completeExecution(transactionId);
        MutationTransactionCoordinator.Receipt receipt = future.get(5, TimeUnit.SECONDS);
        assertSuccessful(receipt, transactionId);
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void executorFailureProducesOneSettledFailureAndNoInventoryCommit() throws Exception {
        String transactionId = "executor-failure";
        cluster.b.inventory.pauseExecution(transactionId);
        CompletableFuture<MutationTransactionCoordinator.Receipt> future = cluster.executeFromA(
                cluster.putRequest(transactionId, 25));
        cluster.b.inventory.awaitExecutionStarted(transactionId);

        cluster.b.inventory.failExecution(transactionId, new IllegalStateException("test failure"));
        MutationTransactionCoordinator.Receipt receipt = future.get(5, TimeUnit.SECONDS);

        assertFalse(receipt.result().isSuccess());
        assertEquals("INTERNAL_ERROR", receipt.result().getFailReason());
        assertTrue(MutationHashes.validResult(receipt.result()));
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(0, cluster.b.inventory.commits(transactionId));
        receipt.acknowledgeSettlement();
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
        assertEquals(1, cluster.b.faults.received(FrameType.TRANSACTION_SETTLED));
    }

    @Test
    void wrongSettlementAndCloseHashesCannotAdvanceTheLifecycle() throws Exception {
        String transactionId = "wrong-ack-hashes";
        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(
                cluster.putRequest(transactionId, 26)).get(5, TimeUnit.SECONDS);
        String resultHash = receipt.result().getResultHash();

        cluster.a.connection(LoopbackMutationTestCluster.NODE_B)
                .sendOneWay(FrameType.TRANSACTION_SETTLED,
                        new TransactionSettled(transactionId, "wrong-result-hash"))
                .get(5, TimeUnit.SECONDS);
        cluster.b.faults.awaitReceived(FrameType.TRANSACTION_SETTLED, 1);
        assertEquals(1, cluster.b.coordinator.inboundCount());
        assertEquals(0, cluster.a.faults.received(FrameType.TRANSACTION_CLOSED));

        cluster.a.faults.holdNext(FrameType.TRANSACTION_CLOSED);
        receipt.acknowledgeSettlement();
        receipt.acknowledgeSettlement();
        cluster.a.faults.awaitReceived(FrameType.TRANSACTION_CLOSED, 1);
        assertEquals(0, cluster.b.coordinator.inboundCount());
        assertEquals(1, cluster.a.coordinator.outboundCount());

        cluster.b.connection(LoopbackMutationTestCluster.NODE_A)
                .sendOneWay(FrameType.TRANSACTION_CLOSED,
                        new TransactionClosed(transactionId, "wrong-result-hash"))
                .get(5, TimeUnit.SECONDS);
        cluster.a.faults.awaitReceived(FrameType.TRANSACTION_CLOSED, 2);
        assertEquals(1, cluster.a.coordinator.outboundCount());

        cluster.a.faults.releaseOne(FrameType.TRANSACTION_CLOSED);
        cluster.awaitClosed();
        assertEquals(2, cluster.b.faults.received(FrameType.TRANSACTION_SETTLED));
        assertEquals(resultHash, receipt.result().getResultHash());
        assertEquals(1, cluster.b.inventory.commits(transactionId));
    }

    @Test
    void duplicateLiveTransactionIsRejectedLocallyWithoutASecondFrame() throws Exception {
        String transactionId = "duplicate-live";
        MutationExecute request = cluster.putRequest(transactionId, 27);
        cluster.b.inventory.pauseExecution(transactionId);
        CompletableFuture<MutationTransactionCoordinator.Receipt> first = cluster.executeFromA(request);
        cluster.b.inventory.awaitExecutionStarted(transactionId);

        CompletableFuture<MutationTransactionCoordinator.Receipt> duplicate = cluster.executeFromA(request);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> duplicate.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("Duplicate live transaction"));
        assertEquals(1, cluster.b.faults.received(FrameType.MUTATION_EXECUTE));

        cluster.b.inventory.completeExecution(transactionId);
        MutationTransactionCoordinator.Receipt receipt = first.get(5, TimeUnit.SECONDS);
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void statusConflictClosesTheContenderWithoutExecutingIt() throws Exception {
        String transactionId = "status-conflict";
        MutationExecute existing = cluster.putRequest(transactionId, 28);
        NeutralItem dirt = existing.getOfferedItem().copy();
        dirt.setItemId("minecraft:dirt");
        existing.setOfferedItem(dirt);
        existing.setIntentHash(MutationHashes.intent(existing));
        cluster.b.inventory.pauseExecution(transactionId);
        cluster.a.connection(LoopbackMutationTestCluster.NODE_B)
                .sendOneWay(FrameType.MUTATION_EXECUTE, existing).get(5, TimeUnit.SECONDS);
        cluster.b.inventory.awaitExecutionStarted(transactionId);
        cluster.b.inventory.completeExecution(transactionId);
        MutationResultMessage existingResult = cluster.a.faults.awaitMessage(
                FrameType.MUTATION_RESULT, MutationResultMessage.class,
                result -> existing.getIntentHash().equals(result.getIntentHash()));

        cluster.b.faults.dropNext(FrameType.MUTATION_EXECUTE);
        MutationExecute contender = cluster.putRequest(transactionId, 28);
        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(contender)
                .get(5, TimeUnit.SECONDS);

        TransactionStatus conflict = cluster.a.faults.awaitStatus(
                transactionId, TransactionStatus.State.CONFLICT);
        assertEquals(contender.getIntentHash(), conflict.getIntentHash());
        assertFalse(receipt.result().isSuccess());
        assertEquals("IDEMPOTENCY_CONFLICT", receipt.result().getFailReason());
        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        assertEquals(0, cluster.a.coordinator.outboundCount());

        cluster.a.connection(LoopbackMutationTestCluster.NODE_B)
                .sendOneWay(FrameType.TRANSACTION_SETTLED,
                        new TransactionSettled(transactionId, existingResult.getResultHash()))
                .get(5, TimeUnit.SECONDS);
        cluster.awaitClosed();
    }

    @Test
    void offlineAndDrainingExecutionsFailBeforeCreatingProtocolState() {
        cluster.disconnectAfromB();
        CompletableFuture<MutationTransactionCoordinator.Receipt> offline = cluster.executeFromA(
                cluster.putRequest("offline", 29));
        assertThrows(ExecutionException.class, () -> offline.get(5, TimeUnit.SECONDS));
        assertEquals(0, cluster.a.coordinator.outboundCount());
        assertEquals(0, cluster.b.inventory.executions());

        cluster.a.coordinator.beginDraining();
        CompletableFuture<MutationTransactionCoordinator.Receipt> draining = cluster.executeFromA(
                cluster.putRequest("draining", 30));
        assertThrows(ExecutionException.class, () -> draining.get(5, TimeUnit.SECONDS));
        assertEquals(0, cluster.a.coordinator.outboundCount());
        assertEquals(0, cluster.b.inventory.executions());
    }

    private void assertSuccessful(MutationTransactionCoordinator.Receipt receipt,
                                  String transactionId) {
        MutationResultMessage result = receipt.result();
        assertTrue(result.isSuccess());
        assertEquals(transactionId, result.getTransactionId());
        assertTrue(MutationHashes.validResult(result));
    }

    private MutationResultMessage resultFor(MutationExecute request, String intentHash) {
        NeutralItem current = request.getOfferedItem().copy();
        current.setVersion(1);
        MutationResultMessage result = new MutationResultMessage(
                request.getTransactionId(), intentHash, null, request.getKind(), true,
                request.getSlot(), current, null, null, System.currentTimeMillis(), 1,
                org.edtp.theexchange.model.InventoryScope.server());
        result.setResultHash(MutationHashes.result(result));
        return result;
    }
}
