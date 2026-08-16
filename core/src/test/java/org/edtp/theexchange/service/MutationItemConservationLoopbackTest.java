package org.edtp.theexchange.service;

import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.MutationExecute;
import org.edtp.theexchange.network.protocol.messages.MutationResultMessage;
import org.edtp.theexchange.network.protocol.messages.TransactionStatus;
import org.edtp.theexchange.storage.SettlementVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class MutationItemConservationLoopbackTest {
    private static final long PROBE_MILLIS = 120L;

    @TempDir
    Path tempDir;

    private final List<LoopbackMutationTestCluster> clusters = new ArrayList<>();

    @AfterEach
    void tearDown() {
        clusters.forEach(LoopbackMutationTestCluster::close);
    }

    @Test
    void recoveredPutConsumesTheReservationExactlyOnce() throws Exception {
        LoopbackMutationTestCluster cluster = cluster(false);
        FakeSource source = new FakeSource(cluster.item("minecraft:stone", 5));
        NeutralItem reserved = source.reserve(3);
        MutationExecute request = cluster.putRequest("conserve-put-success", 0, reserved, 0);
        cluster.a.faults.dropNext(FrameType.MUTATION_RESULT);

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(request)
                .get(5, TimeUnit.SECONDS);

        assertTrue(receipt.result().isSuccess());
        assertNull(receipt.result().getTransferredItem());
        assertItem(cluster.b.inventory.item(0), "minecraft:stone", 3);
        assertEquals(2, source.count("minecraft:stone"));
        assertEquals(5, source.count("minecraft:stone")
                + cluster.b.inventory.totalItems("minecraft:stone"));
        assertEquals(1, cluster.b.inventory.commits(request.getTransactionId()));
        receipt.acknowledgeSettlement();
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
        assertEquals(1, cluster.b.faults.received(FrameType.TRANSACTION_SETTLED));
    }

    @Test
    void failedPutReturnsToTheOriginalSourceWithoutChangingAuthority() throws Exception {
        LoopbackMutationTestCluster cluster = cluster(false);
        cluster.b.inventory.seed(1, cluster.item("minecraft:dirt", 1));
        FakeSource source = new FakeSource(cluster.item("minecraft:stone", 5));
        NeutralItem reserved = source.reserve(3);
        MutationExecute request = cluster.putRequest(
                "conserve-put-failure", 1, reserved, cluster.b.inventory.version(1));

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(request)
                .get(5, TimeUnit.SECONDS);

        assertFalse(receipt.result().isSuccess());
        assertEquals("SLOT_OCCUPIED", receipt.result().getFailReason());
        source.restoreOrDrop(reserved);
        assertEquals(5, source.count("minecraft:stone"));
        assertEquals(0, source.dropped("minecraft:stone"));
        assertItem(cluster.b.inventory.item(1), "minecraft:dirt", 1);
        assertEquals(0, cluster.b.inventory.commits(request.getTransactionId()));
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void failedPutDropsTheExactReservationWhenItsSourceWasReplaced() throws Exception {
        LoopbackMutationTestCluster cluster = cluster(false);
        cluster.b.inventory.seed(2, cluster.item("minecraft:dirt", 64));
        FakeSource source = new FakeSource(cluster.item("minecraft:stone", 3));
        NeutralItem reserved = source.reserve(3);
        source.replace(cluster.item("minecraft:dirt", 1));
        MutationExecute request = cluster.putRequest(
                "conserve-put-drop", 2, reserved, cluster.b.inventory.version(2));

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(request)
                .get(5, TimeUnit.SECONDS);

        assertFalse(receipt.result().isSuccess());
        source.restoreOrDrop(reserved);
        assertEquals(3, source.dropped("minecraft:stone"));
        assertEquals(0, source.count("minecraft:stone"));
        assertEquals(3, source.dropped("minecraft:stone")
                + source.count("minecraft:stone")
                + cluster.b.inventory.totalItems("minecraft:stone"));
        assertEquals(0, cluster.b.inventory.commits(request.getTransactionId()));
        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void takeResultCanBePartiallyDeliveredAndDroppedWithoutChangingTheTotal() throws Exception {
        LoopbackMutationTestCluster cluster = cluster(false);
        cluster.b.inventory.seed(3, cluster.item("minecraft:diamond", 5));
        MutationExecute request = cluster.takeRequest("conserve-take", 3,
                "minecraft:diamond", cluster.b.inventory.version(3), 3);
        cluster.a.faults.holdNext(FrameType.MUTATION_RESULT);

        MutationTransactionCoordinator.Receipt receipt = cluster.executeFromA(request)
                .get(5, TimeUnit.SECONDS);
        MutationResultMessage result = receipt.result();
        assertTrue(result.isSuccess());
        assertItem(result.getTransferredItem(), "minecraft:diamond", 3);
        assertItem(result.getCurrentItem(), "minecraft:diamond", 2);

        FakeDestination destination = new FakeDestination(1);
        destination.deliverOrDrop(result.getTransferredItem());
        assertEquals(1, destination.delivered("minecraft:diamond"));
        assertEquals(2, destination.dropped("minecraft:diamond"));
        assertEquals(5, cluster.b.inventory.totalItems("minecraft:diamond")
                + destination.delivered("minecraft:diamond")
                + destination.dropped("minecraft:diamond"));
        assertEquals(1, cluster.b.inventory.commits(request.getTransactionId()));

        receipt.acknowledgeSettlement();
        cluster.awaitClosed();
        cluster.a.faults.releaseOne(FrameType.MUTATION_RESULT);
        assertTrue(cluster.a.logs.stream().anyMatch(message ->
                message.contains("Ignored orphan mutation result")
                        && message.contains(request.getTransactionId())));
        assertEquals(5, cluster.b.inventory.totalItems("minecraft:diamond")
                + destination.delivered("minecraft:diamond")
                + destination.dropped("minecraft:diamond"));
    }

    @Test
    void fullSwapAndBoundedMergeConserveEachItemKind() throws Exception {
        LoopbackMutationTestCluster cluster = cluster(false);
        cluster.b.inventory.seed(4, cluster.item("minecraft:diamond", 4));
        FakeSource source = new FakeSource(cluster.item("minecraft:stone", 2));
        NeutralItem offered = source.reserve(2);
        MutationExecute swap = cluster.swapRequest("conserve-swap", 4, offered,
                "minecraft:diamond", cluster.b.inventory.version(4), 4, false);

        MutationTransactionCoordinator.Receipt swapReceipt = cluster.executeFromA(swap)
                .get(5, TimeUnit.SECONDS);
        assertTrue(swapReceipt.result().isSuccess());
        assertItem(cluster.b.inventory.item(4), "minecraft:stone", 2);
        assertItem(swapReceipt.result().getTransferredItem(), "minecraft:diamond", 4);
        assertEquals(2, source.count("minecraft:stone")
                + cluster.b.inventory.totalItems("minecraft:stone"));
        assertEquals(4, swapReceipt.result().getTransferredItem().getCount()
                + cluster.b.inventory.totalItems("minecraft:diamond"));
        swapReceipt.acknowledgeSettlement();
        cluster.awaitClosed();

        cluster.b.inventory.seed(5, cluster.item("minecraft:stone", 60));
        FakeSource mergeSource = new FakeSource(cluster.item("minecraft:stone", 10));
        NeutralItem mergeOffered = mergeSource.reserve(10);
        MutationExecute merge = cluster.swapRequest("conserve-bounded-merge", 5, mergeOffered,
                "minecraft:stone", cluster.b.inventory.version(5), 60, true);

        MutationTransactionCoordinator.Receipt mergeReceipt = cluster.executeFromA(merge)
                .get(5, TimeUnit.SECONDS);
        assertTrue(mergeReceipt.result().isSuccess());
        assertItem(cluster.b.inventory.item(5), "minecraft:stone", 64);
        assertItem(mergeReceipt.result().getTransferredItem(), "minecraft:stone", 6);
        mergeSource.restoreOrDrop(mergeReceipt.result().getTransferredItem());
        assertEquals(6, mergeSource.count("minecraft:stone"));
        assertEquals(70, cluster.b.inventory.item(5).getCount()
                + mergeSource.count("minecraft:stone")
                + mergeSource.dropped("minecraft:stone"));
        mergeReceipt.acknowledgeSettlement();
        cluster.awaitClosed();
    }

    @Test
    void recoveredTakeMovesTheTransferredItemToVaultExactlyOnce() {
        LoopbackMutationTestCluster cluster = cluster(true);
        cluster.b.inventory.seed(6, cluster.item("minecraft:emerald", 5));
        MutationExecute request = cluster.takeRequest("conserve-recovered-take", 6,
                "minecraft:emerald", cluster.b.inventory.version(6), 3);
        cluster.a.faults.holdNext(FrameType.MUTATION_RESULT);

        cluster.executeFromA(request);
        TransactionStatus status = cluster.a.faults.awaitStatus(
                request.getTransactionId(), TransactionStatus.State.DECIDED);
        assertItem(status.getResult().getTransferredItem(), "minecraft:emerald", 3);
        assertEquals(2, cluster.b.inventory.totalItems("minecraft:emerald"));

        cluster.checkpointAndRestartA();
        cluster.awaitClosed();

        List<SettlementVault.Entry> entries = cluster.a.vault.list("player-uuid");
        assertEquals(1, entries.size());
        assertEquals("RECOVERED_RESULT", entries.getFirst().reason());
        assertItem(entries.getFirst().item(), "minecraft:emerald", 3);
        assertEquals(5, cluster.b.inventory.totalItems("minecraft:emerald")
                + entries.getFirst().item().getCount());
        assertEquals(1, cluster.b.inventory.commits(request.getTransactionId()));
        assertTrue(cluster.a.journal.loadAll().isEmpty());
        assertTrue(cluster.b.journal.loadAll().isEmpty());
    }

    private LoopbackMutationTestCluster cluster(boolean persistent) {
        try {
            LoopbackMutationTestCluster created = new LoopbackMutationTestCluster(
                    tempDir.resolve("case-" + clusters.size()), PROBE_MILLIS, persistent);
            clusters.add(created);
            return created;
        } catch (Exception error) {
            throw new AssertionError("failed to create conservation cluster", error);
        }
    }

    private static void assertItem(NeutralItem item, String itemId, int count) {
        assertNotNull(item);
        assertEquals(itemId, item.getItemId());
        assertEquals(count, item.getCount());
    }

    private static final class FakeSource {
        private NeutralItem stack;
        private final List<NeutralItem> dropped = new ArrayList<>();

        private FakeSource(NeutralItem stack) {
            this.stack = stack.copy();
        }

        private NeutralItem reserve(int count) {
            if (stack == null || stack.isEmpty() || stack.getCount() < count) {
                throw new IllegalStateException("insufficient source item");
            }
            NeutralItem reserved = stack.copy();
            reserved.setCount(count);
            stack.setCount(stack.getCount() - count);
            if (stack.getCount() == 0) stack = null;
            return reserved;
        }

        private void replace(NeutralItem replacement) {
            stack = replacement != null ? replacement.copy() : null;
        }

        private void restoreOrDrop(NeutralItem returned) {
            if (returned == null || returned.isEmpty()) return;
            if (stack == null || stack.isEmpty()) {
                stack = returned.copy();
                return;
            }
            if (stack.sameStackKind(returned)
                    && stack.getCount() + returned.getCount() <= stack.getMaxStackSize()) {
                stack.setCount(stack.getCount() + returned.getCount());
                return;
            }
            dropped.add(returned.copy());
        }

        private int count(String itemId) {
            return stack != null && itemId.equals(stack.getItemId()) ? stack.getCount() : 0;
        }

        private int dropped(String itemId) {
            return dropped.stream().filter(item -> itemId.equals(item.getItemId()))
                    .mapToInt(NeutralItem::getCount).sum();
        }
    }

    private static final class FakeDestination {
        private final int capacity;
        private final List<NeutralItem> delivered = new ArrayList<>();
        private final List<NeutralItem> dropped = new ArrayList<>();

        private FakeDestination(int capacity) {
            this.capacity = capacity;
        }

        private void deliverOrDrop(NeutralItem item) {
            NeutralItem remaining = item.copy();
            int accepted = Math.min(capacity, remaining.getCount());
            if (accepted > 0) {
                NeutralItem placed = remaining.copy();
                placed.setCount(accepted);
                delivered.add(placed);
                remaining.setCount(remaining.getCount() - accepted);
            }
            if (!remaining.isEmpty()) dropped.add(remaining);
        }

        private int delivered(String itemId) {
            return delivered.stream().filter(item -> itemId.equals(item.getItemId()))
                    .mapToInt(NeutralItem::getCount).sum();
        }

        private int dropped(String itemId) {
            return dropped.stream().filter(item -> itemId.equals(item.getItemId()))
                    .mapToInt(NeutralItem::getCount).sum();
        }
    }
}
