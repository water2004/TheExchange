package org.edtp.theexchange.service;

import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.MutationExecute;
import org.edtp.theexchange.network.protocol.messages.MutationResultMessage;
import org.edtp.theexchange.network.protocol.messages.TransactionSettled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class MutationTransactionNetworkStressTest {
    private static final int SLOTS = 54;
    private static final int CLIENT_THREADS = 16;
    private static final long PROBE_MILLIS = 1_000L;

    @TempDir
    static Path tempDir;

    private LoopbackMutationTestCluster cluster;
    private ExecutorService clients;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new LoopbackMutationTestCluster(tempDir, PROBE_MILLIS);
        clients = Executors.newFixedThreadPool(CLIENT_THREADS);
    }

    @AfterEach
    void tearDown() {
        if (clients != null) clients.shutdownNow();
        if (cluster != null) cluster.close();
    }

    @Test
    void multiProducerTrafficAcrossAllSlotsCommitsEveryTransactionExactlyOnce() throws Exception {
        int rounds = 20;
        int expectedTransactions = rounds * SLOTS;
        long startedAt = System.nanoTime();

        for (int round = 0; round < rounds; round++) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MutationTransactionCoordinator.Receipt>> futures = new ArrayList<>(SLOTS);
            for (int slot = 0; slot < SLOTS; slot++) {
                int targetSlot = slot;
                int expectedVersion = cluster.b.inventory.version(slot);
                MutationExecute request = cluster.putRequest(
                        "network-multi-" + round + "-" + slot, slot,
                        cluster.item("minecraft:diamond", 1), expectedVersion);
                futures.add(clients.submit(() -> {
                    start.await();
                    return cluster.executeFromA(request).get(20, TimeUnit.SECONDS);
                }));
            }
            start.countDown();
            for (Future<MutationTransactionCoordinator.Receipt> future : futures) {
                MutationTransactionCoordinator.Receipt receipt = future.get(25, TimeUnit.SECONDS);
                assertTrue(receipt.result().isSuccess(), receipt.result().getFailReason());
                receipt.acknowledgeSettlement();
            }
            awaitProtocolEmpty();
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertEquals(expectedTransactions, cluster.b.inventory.executions());
        assertEquals(expectedTransactions, cluster.b.inventory.commits());
        assertEquals(expectedTransactions, cluster.b.faults.received(FrameType.MUTATION_EXECUTE));
        assertEquals(expectedTransactions, cluster.b.faults.received(FrameType.TRANSACTION_SETTLED));
        assertEquals(expectedTransactions, cluster.a.faults.received(FrameType.MUTATION_RESULT));
        assertEquals(expectedTransactions, cluster.a.faults.received(FrameType.TRANSACTION_CLOSED));
        for (int slot = 0; slot < SLOTS; slot++) {
            NeutralItem item = cluster.b.inventory.item(slot);
            assertNotNull(item);
            assertEquals("minecraft:diamond", item.getItemId());
            assertEquals(rounds, item.getCount());
            assertEquals(rounds, cluster.b.inventory.version(slot));
        }
        assertEquals(expectedTransactions, cluster.b.inventory.totalItems("minecraft:diamond"));
        System.out.printf("[real-tcp-multi-slot] clients=%d transactions=%d elapsed=%dms rate=%,.0f tx/s%n",
                CLIENT_THREADS, expectedTransactions, elapsedMillis,
                expectedTransactions * 1_000.0 / Math.max(1L, elapsedMillis));
    }

    @Test
    void sameSlotOptimisticCompetitionHasOneWinnerAndReturnsEveryLoser() throws Exception {
        int contenders = 256;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MutationTransactionCoordinator.Receipt>> futures = new ArrayList<>(contenders);
        long startedAt = System.nanoTime();

        for (int index = 0; index < contenders; index++) {
            MutationExecute request = cluster.putRequest(
                    "network-contender-" + index, 0,
                    cluster.item("minecraft:emerald", 1), 0);
            futures.add(clients.submit(() -> {
                start.await();
                return cluster.executeFromA(request).get(20, TimeUnit.SECONDS);
            }));
        }
        start.countDown();

        int successes = 0;
        int returned = 0;
        for (Future<MutationTransactionCoordinator.Receipt> future : futures) {
            MutationTransactionCoordinator.Receipt receipt = future.get(25, TimeUnit.SECONDS);
            if (receipt.result().isSuccess()) {
                successes++;
            } else {
                assertEquals("VERSION_MISMATCH", receipt.result().getFailReason());
                returned++;
            }
            receipt.acknowledgeSettlement();
        }
        awaitProtocolEmpty();

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertEquals(1, successes);
        assertEquals(contenders - 1, returned);
        assertEquals(contenders, cluster.b.inventory.executions());
        assertEquals(1, cluster.b.inventory.commits());
        assertEquals(1, cluster.b.inventory.totalItems("minecraft:emerald"));
        assertEquals(contenders, cluster.b.inventory.totalItems("minecraft:emerald") + returned,
                "one item is committed and every failed reservation is returned");
        System.out.printf("[real-tcp-same-slot] clients=%d contenders=%d elapsed=%dms rate=%,.0f tx/s%n",
                CLIENT_THREADS, contenders, elapsedMillis,
                contenders * 1_000.0 / Math.max(1L, elapsedMillis));
    }

    @Test
    void duplicateWireFrameStormStillCommitsOneMutation() throws Exception {
        int duplicates = 256;
        String transactionId = "network-duplicate-storm";
        MutationExecute request = cluster.putRequest(transactionId, 1,
                cluster.item("minecraft:gold_ingot", 1), 0);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writes = new ArrayList<>(duplicates);

        for (int index = 0; index < duplicates; index++) {
            writes.add(clients.submit(() -> {
                start.await();
                cluster.a.connection(LoopbackMutationTestCluster.NODE_B)
                        .sendOneWay(FrameType.MUTATION_EXECUTE, request).get(20, TimeUnit.SECONDS);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> write : writes) write.get(25, TimeUnit.SECONDS);

        cluster.b.faults.awaitReceived(FrameType.MUTATION_EXECUTE, duplicates);
        MutationResultMessage result = cluster.a.faults.awaitMessage(
                FrameType.MUTATION_RESULT, MutationResultMessage.class,
                message -> transactionId.equals(message.getTransactionId()) && message.isSuccess());
        LoopbackMutationTestCluster.await(
                () -> cluster.a.faults.received(FrameType.MUTATION_RESULT) == duplicates,
                Duration.ofSeconds(20), "authority did not replay one result per duplicate frame");

        assertEquals(1, cluster.b.inventory.executions(transactionId));
        assertEquals(1, cluster.b.inventory.commits(transactionId));
        assertEquals(1, cluster.b.inventory.totalItems("minecraft:gold_ingot"));
        cluster.a.connection(LoopbackMutationTestCluster.NODE_B)
                .sendOneWay(FrameType.TRANSACTION_SETTLED,
                        new TransactionSettled(transactionId, result.getResultHash()))
                .get(10, TimeUnit.SECONDS);
        LoopbackMutationTestCluster.await(
                () -> cluster.b.coordinator.inboundCount() == 0,
                Duration.ofSeconds(10), "duplicate storm transaction did not settle");
    }

    private void awaitProtocolEmpty() {
        LoopbackMutationTestCluster.await(
                () -> cluster.a.coordinator.outboundCount() == 0
                        && cluster.b.coordinator.inboundCount() == 0,
                Duration.ofSeconds(20), "stress batch did not settle");
    }
}
