package org.edtp.theexchange.service;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.MutationHashes;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.storage.MutationRecoveryJournal;
import org.edtp.theexchange.storage.SettlementVault;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * End-to-end lifecycle for remote inventory mutations.
 *
 * Only transactions that have not completed the RESULT -> SETTLED -> CLOSED handshake are retained.
 * No successful-request history, TTL cache, or tombstone is kept.
 */
public final class MutationTransactionCoordinator implements AutoCloseable {
    @FunctionalInterface
    public interface InboundExecutor {
        CompletableFuture<MutationResultMessage> execute(String peerId, MutationExecute request);
    }

    public static final class Receipt {
        private final MutationResultMessage result;
        private final Runnable settlement;
        private final AtomicBoolean settled = new AtomicBoolean();

        private Receipt(MutationResultMessage result, Runnable settlement) {
            this.result = result;
            this.settlement = settlement;
        }

        public MutationResultMessage result() {
            return result;
        }

        /** Called only after the platform has delivered, returned, dropped, or vaulted the item. */
        public void acknowledgeSettlement() {
            if (settled.compareAndSet(false, true)) {
                settlement.run();
            }
        }
    }

    private enum OutboundState { PENDING, RECOVERING, DECIDED, SETTLED_WAIT_CLOSE }

    private record TransactionKey(String peerId, String transactionId) {}

    private static final class OutboundTransaction {
        private final MutationExecute request;
        private final CompletableFuture<Receipt> receipt = new CompletableFuture<>();
        private final AtomicReference<ScheduledFuture<?>> probe = new AtomicReference<>();
        private volatile OutboundState state = OutboundState.PENDING;
        private volatile MutationResultMessage result;
        private volatile boolean journaled;
        private volatile String persistedState;
        private volatile String persistedResultHash;

        private OutboundTransaction(MutationExecute request) {
            this.request = request;
        }
    }

    private static final class InboundTransaction {
        private final MutationExecute request;
        private final CompletableFuture<MutationResultMessage> result = new CompletableFuture<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile boolean journaled;
        private volatile String persistedState;
        private volatile String persistedResultHash;

        private InboundTransaction(MutationExecute request) {
            this.request = request;
        }
    }

    private final ConcurrentHashMap<TransactionKey, OutboundTransaction> outbound = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TransactionKey, InboundTransaction> inbound = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "exchange-transaction-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<String> logger;
    private final MutationRecoveryJournal journal;
    private final SettlementVault settlementVault;
    private volatile NetworkManager networkManager;
    private volatile long requestTimeoutMs;
    private volatile boolean draining;

    public MutationTransactionCoordinator(long requestTimeoutMs, Consumer<String> logger) {
        this(requestTimeoutMs, logger, null, null);
    }

    public MutationTransactionCoordinator(long requestTimeoutMs, Consumer<String> logger,
                                          MutationRecoveryJournal journal,
                                          SettlementVault settlementVault) {
        this.requestTimeoutMs = Math.max(100L, requestTimeoutMs);
        this.logger = logger != null ? logger : ignored -> {};
        this.journal = journal;
        this.settlementVault = settlementVault;
        restoreJournal();
    }

    public void bind(NetworkManager networkManager, long requestTimeoutMs) {
        this.networkManager = networkManager;
        this.requestTimeoutMs = Math.max(100L, requestTimeoutMs);
        this.draining = false;
    }

    public CompletableFuture<Receipt> execute(String peerId, MutationExecute request) {
        if (draining) {
            return CompletableFuture.failedFuture(new IllegalStateException("Exchange is shutting down"));
        }
        validateNewRequest(peerId, request);
        NetworkManager manager = networkManager;
        Connection connection = manager != null ? manager.getConnection(peerId) : null;
        if (connection == null || !connection.isRunning() || !connection.isAuthenticated()) {
            return CompletableFuture.failedFuture(new IllegalStateException("目标服务器离线"));
        }

        TransactionKey key = new TransactionKey(peerId, request.getTransactionId());
        OutboundTransaction transaction = new OutboundTransaction(request);
        if (outbound.putIfAbsent(key, transaction) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Duplicate live transaction: " + request.getTransactionId()));
        }
        send(connection, FrameType.MUTATION_EXECUTE, request)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        transaction.state = OutboundState.RECOVERING;
                        persistOutbound(key, transaction);
                        log("Mutation write became ambiguous " + key + ": " + error.getMessage());
                    }
                    scheduleProbe(key, transaction);
                });
        return transaction.receipt;
    }

    public boolean route(Connection connection, FrameType type, Object message,
                         InboundExecutor executor) {
        if (type == null || !type.isMutationLifecycle()) return false;
        NetworkManager manager = networkManager;
        if (manager == null || !manager.isCurrentConnection(connection)) {
            log("Rejected mutation frame from fenced connection " + peerName(connection));
            return true;
        }
        String peerId = peerName(connection);
        switch (type) {
            case MUTATION_EXECUTE, MUTATION_RECOVER -> handleExecute(connection, peerId,
                    (MutationExecute) message, executor);
            case MUTATION_RESULT -> handleResult(peerId, (MutationResultMessage) message);
            case TRANSACTION_QUERY -> handleQuery(connection, peerId, (TransactionQuery) message);
            case TRANSACTION_STATUS -> handleStatus(peerId, (TransactionStatus) message);
            case TRANSACTION_SETTLED -> handleSettled(connection, peerId, (TransactionSettled) message);
            case TRANSACTION_CLOSED -> handleClosed(peerId, (TransactionClosed) message);
            default -> throw new IllegalStateException("Unhandled mutation frame " + type);
        }
        return true;
    }

    public void onPeerOnline(String peerId) {
        for (var entry : outbound.entrySet()) {
            if (!entry.getKey().peerId().equals(peerId)) continue;
            OutboundTransaction transaction = entry.getValue();
            if (transaction.state == OutboundState.SETTLED_WAIT_CLOSE) {
                sendSettled(entry.getKey(), transaction);
            } else {
                sendQuery(entry.getKey(), transaction);
            }
        }
    }

    public void beginDraining() {
        draining = true;
    }

    public void checkpointOutstanding() {
        if (journal == null) return;
        for (var entry : outbound.entrySet()) {
            persistOutbound(entry.getKey(), entry.getValue());
        }
        for (var entry : inbound.entrySet()) {
            MutationResultMessage result = entry.getValue().result.getNow(null);
            persistInbound(entry.getKey(), entry.getValue(), result);
        }
    }

    public void refreshAccess(String peerId, org.edtp.theexchange.model.InventoryAccess access) {
        if (access == null || !access.isPlayer() || !access.hasToken()) return;
        for (var entry : outbound.entrySet()) {
            if (!entry.getKey().peerId().equals(peerId)) continue;
            MutationExecute request = entry.getValue().request;
            org.edtp.theexchange.model.InventoryAccess previous = request.getAccess();
            if (!previous.isPlayer()
                    || !Objects.equals(previous.requesterUuid(), access.requesterUuid())
                    || (previous.resolvedScope() != null
                        && !Objects.equals(previous.resolvedScope(), access.resolvedScope()))) {
                continue;
            }
            request.setAccess(access);
            persistOutbound(entry.getKey(), entry.getValue());
            sendQuery(entry.getKey(), entry.getValue());
        }
    }

    public int outboundCount() { return outbound.size(); }
    public int inboundCount() { return inbound.size(); }

    private void handleExecute(Connection connection, String peerId, MutationExecute request,
                               InboundExecutor executor) {
        if (!validRequest(request)) {
            log("Rejected malformed mutation from " + peerId);
            return;
        }
        TransactionKey key = new TransactionKey(peerId, request.getTransactionId());
        InboundTransaction candidate = new InboundTransaction(request);
        InboundTransaction transaction = inbound.putIfAbsent(key, candidate);
        if (transaction == null) {
            transaction = candidate;
        } else if (!Objects.equals(transaction.request.getIntentHash(), request.getIntentHash())) {
            send(connection, FrameType.MUTATION_RESULT, conflictResult(request));
            return;
        }

        InboundTransaction selected = transaction;
        selected.result.thenAccept(result -> sendCurrent(peerId, FrameType.MUTATION_RESULT, result));
        if (!selected.started.compareAndSet(false, true)) {
            return;
        }
        if (draining) {
            selected.result.complete(failureResult(request, "SHUTTING_DOWN", requestScope(request)));
            return;
        }
        try {
            CompletableFuture<MutationResultMessage> execution = executor.execute(peerId, request);
            if (execution == null) {
                selected.result.complete(failureResult(request, "INTERNAL_ERROR", requestScope(request)));
                return;
            }
            execution.whenComplete((result, error) -> {
                if (error != null || result == null || !validResultFor(request, result)) {
                    log("Mutation execution failed " + key + ": "
                            + (error != null ? error.getMessage() : "invalid result"));
                    selected.result.complete(failureResult(request, "INTERNAL_ERROR", requestScope(request)));
                } else {
                    selected.result.complete(result);
                }
                if (selected.journaled) {
                    persistInbound(key, selected, selected.result.getNow(null));
                }
            });
        } catch (RuntimeException error) {
            selected.result.complete(failureResult(request, "INTERNAL_ERROR", requestScope(request)));
        }
    }

    private void handleQuery(Connection connection, String peerId, TransactionQuery query) {
        if (query == null || blank(query.transactionId())) return;
        TransactionKey key = new TransactionKey(peerId, query.transactionId());
        InboundTransaction transaction = inbound.get(key);
        if (transaction == null) {
            send(connection, FrameType.TRANSACTION_STATUS,
                    new TransactionStatus(query.transactionId(), query.intentHash(),
                            TransactionStatus.State.UNKNOWN, null));
            return;
        }
        if (!Objects.equals(transaction.request.getIntentHash(), query.intentHash())) {
            send(connection, FrameType.TRANSACTION_STATUS,
                    new TransactionStatus(query.transactionId(), query.intentHash(),
                            TransactionStatus.State.CONFLICT, null));
            return;
        }
        persistInbound(key, transaction, transaction.result.getNow(null));
        MutationResultMessage result = transaction.result.getNow(null);
        if (result == null && !transaction.started.get()) {
            send(connection, FrameType.TRANSACTION_STATUS,
                    new TransactionStatus(query.transactionId(), query.intentHash(),
                            TransactionStatus.State.UNKNOWN, null));
            return;
        }
        send(connection, FrameType.TRANSACTION_STATUS,
                new TransactionStatus(query.transactionId(), query.intentHash(),
                        result != null ? TransactionStatus.State.DECIDED : TransactionStatus.State.RUNNING,
                        result));
    }

    private void handleResult(String peerId, MutationResultMessage result) {
        if (result == null || blank(result.getTransactionId()) || !MutationHashes.validResult(result)) {
            log("Rejected invalid mutation result from " + peerId);
            return;
        }
        TransactionKey key = new TransactionKey(peerId, result.getTransactionId());
        OutboundTransaction transaction = outbound.get(key);
        if (transaction == null) {
            log("Ignored orphan mutation result " + key);
            return;
        }
        if (!validResultFor(transaction.request, result)) {
            log("Rejected mismatched mutation result " + key);
            return;
        }
        MutationResultMessage existingResult = transaction.result;
        if (existingResult != null) {
            if (!Objects.equals(existingResult.getResultHash(), result.getResultHash())) {
                log("Rejected conflicting outcomes for " + key);
            } else if (transaction.state == OutboundState.SETTLED_WAIT_CLOSE) {
                sendSettled(key, transaction);
            } else {
                scheduleProbe(key, transaction);
            }
            return;
        }
        if (transaction.state == OutboundState.SETTLED_WAIT_CLOSE) {
            sendSettled(key, transaction);
            return;
        }
        transaction.result = result;
        transaction.state = OutboundState.DECIDED;
        cancelProbe(transaction);
        if (transaction.journaled) {
            persistOutbound(key, transaction);
        }
        transaction.receipt.complete(new Receipt(result, () -> settle(key, transaction)));
        scheduleProbe(key, transaction);
    }

    private void handleStatus(String peerId, TransactionStatus status) {
        if (status == null || blank(status.getTransactionId())) return;
        TransactionKey key = new TransactionKey(peerId, status.getTransactionId());
        OutboundTransaction transaction = outbound.get(key);
        if (transaction == null || !Objects.equals(transaction.request.getIntentHash(), status.getIntentHash())) {
            return;
        }
        if (status.getState() == TransactionStatus.State.CONFLICT) {
            closeOutbound(key, transaction);
            transaction.receipt.complete(new Receipt(
                    conflictResult(transaction.request), () -> {}));
        } else if (status.getState() == TransactionStatus.State.DECIDED) {
            handleResult(peerId, status.getResult());
        } else if (status.getState() == TransactionStatus.State.UNKNOWN) {
            if (transaction.state == OutboundState.SETTLED_WAIT_CLOSE) {
                closeOutbound(key, transaction);
            } else {
                transaction.state = OutboundState.RECOVERING;
                if (transaction.request.getAccess().isPlayer()
                        && !transaction.request.getAccess().hasToken()) {
                    persistOutbound(key, transaction);
                    log("Recovered player transaction awaits re-authentication " + key);
                    scheduleProbe(key, transaction);
                    return;
                }
                sendCurrent(peerId, FrameType.MUTATION_RECOVER, transaction.request)
                        .whenComplete((ignored, error) -> scheduleProbe(key, transaction));
            }
        } else {
            scheduleProbe(key, transaction);
        }
    }

    private void handleSettled(Connection connection, String peerId, TransactionSettled settled) {
        if (settled == null || blank(settled.transactionId())) return;
        TransactionKey key = new TransactionKey(peerId, settled.transactionId());
        InboundTransaction transaction = inbound.get(key);
        if (transaction != null) {
            MutationResultMessage result = transaction.result.getNow(null);
            if (result == null || !Objects.equals(result.getResultHash(), settled.resultHash())) {
                return;
            }
            inbound.remove(key, transaction);
        }
        deleteJournal(MutationRecoveryJournal.Direction.INBOUND, key);
        // Repeated settlement after deletion is safe: the sender proves it already applied the result.
        send(connection, FrameType.TRANSACTION_CLOSED,
                new TransactionClosed(settled.transactionId(), settled.resultHash()));
    }

    private void handleClosed(String peerId, TransactionClosed closed) {
        if (closed == null || blank(closed.transactionId())) return;
        TransactionKey key = new TransactionKey(peerId, closed.transactionId());
        OutboundTransaction transaction = outbound.get(key);
        if (transaction != null && transaction.state == OutboundState.SETTLED_WAIT_CLOSE
                && transaction.result != null
                && Objects.equals(transaction.result.getResultHash(), closed.resultHash())) {
            closeOutbound(key, transaction);
        }
    }

    private void settle(TransactionKey key, OutboundTransaction transaction) {
        if (outbound.get(key) != transaction || transaction.result == null) return;
        transaction.state = OutboundState.SETTLED_WAIT_CLOSE;
        if (transaction.journaled) {
            persistOutbound(key, transaction);
        }
        sendSettled(key, transaction);
    }

    private void sendSettled(TransactionKey key, OutboundTransaction transaction) {
        MutationResultMessage result = transaction.result;
        if (result == null) return;
        sendCurrent(key.peerId(), FrameType.TRANSACTION_SETTLED,
                new TransactionSettled(key.transactionId(), result.getResultHash()))
                .whenComplete((ignored, error) -> scheduleProbe(key, transaction));
    }

    private void sendQuery(TransactionKey key, OutboundTransaction transaction) {
        sendCurrent(key.peerId(), FrameType.TRANSACTION_QUERY,
                new TransactionQuery(key.transactionId(), transaction.request.getIntentHash()))
                .whenComplete((ignored, error) -> scheduleProbe(key, transaction));
    }

    private void scheduleProbe(TransactionKey key, OutboundTransaction transaction) {
        if (outbound.get(key) != transaction) return;
        ScheduledFuture<?> next = scheduler.schedule(() -> {
            if (outbound.get(key) != transaction) return;
            transaction.state = transaction.state == OutboundState.PENDING
                    ? OutboundState.RECOVERING : transaction.state;
            persistOutbound(key, transaction);
            if (transaction.state == OutboundState.SETTLED_WAIT_CLOSE) {
                sendSettled(key, transaction);
            } else {
                sendQuery(key, transaction);
            }
        }, requestTimeoutMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = transaction.probe.getAndSet(next);
        if (previous != null) previous.cancel(false);
    }

    private void cancelProbe(OutboundTransaction transaction) {
        ScheduledFuture<?> previous = transaction.probe.getAndSet(null);
        if (previous != null) previous.cancel(false);
    }

    private void closeOutbound(TransactionKey key, OutboundTransaction transaction) {
        if (outbound.remove(key, transaction)) {
            cancelProbe(transaction);
            deleteJournal(MutationRecoveryJournal.Direction.OUTBOUND, key);
        }
    }

    private CompletableFuture<Void> sendCurrent(String peerId, FrameType type, Object message) {
        NetworkManager manager = networkManager;
        Connection connection = manager != null ? manager.getConnection(peerId) : null;
        if (connection == null || !connection.isRunning() || !connection.isAuthenticated()) {
            return CompletableFuture.failedFuture(new IOException("Peer offline: " + peerId));
        }
        return send(connection, type, message);
    }

    private CompletableFuture<Void> send(Connection connection, FrameType type, Object message) {
        return connection.sendOneWay(type, message);
    }

    private MutationResultMessage conflictResult(MutationExecute request) {
        return failureResult(request, "IDEMPOTENCY_CONFLICT",
                requestScope(request));
    }

    private InventoryScope requestScope(MutationExecute request) {
        InventoryScope scope = request != null ? request.getAccess().effectiveScope() : null;
        return scope != null ? scope : InventoryScope.server();
    }

    private MutationResultMessage failureResult(MutationExecute request, String reason,
                                                InventoryScope scope) {
        MutationResultMessage result = new MutationResultMessage(
                request.getTransactionId(), request.getIntentHash(), null, request.getKind(),
                false, request.getSlot(), null, null, reason, 0, 0,
                scope != null ? scope : InventoryScope.server());
        result.setResultHash(MutationHashes.result(result));
        return result;
    }

    private void validateNewRequest(String peerId, MutationExecute request) {
        if (blank(peerId) || !validRequest(request)) {
            throw new IllegalArgumentException("Invalid mutation request");
        }
    }

    private boolean validRequest(MutationExecute request) {
        return request != null && !blank(request.getTransactionId())
                && request.getKind() != null && MutationHashes.validIntent(request);
    }

    private boolean validResultFor(MutationExecute request, MutationResultMessage result) {
        return result != null
                && Objects.equals(request.getTransactionId(), result.getTransactionId())
                && Objects.equals(request.getIntentHash(), result.getIntentHash())
                && request.getKind() == result.getKind()
                && MutationHashes.validResult(result);
    }

    private String peerName(Connection connection) {
        return connection.getPeerServerName() != null
                ? connection.getPeerServerName() : connection.getRemoteName();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void log(String message) {
        logger.accept("[Exchange|Transaction] " + message);
    }

    private void restoreJournal() {
        if (journal == null) return;
        for (MutationRecoveryJournal.Entry entry : journal.loadAll()) {
            if (!validRequest(entry.request())
                    || (entry.result() != null && !validResultFor(entry.request(), entry.result()))) {
                log("Rejected invalid recovery entry " + entry.peerId() + "/"
                        + (entry.request() != null ? entry.request().getTransactionId() : "unknown"));
                continue;
            }
            TransactionKey key = new TransactionKey(entry.peerId(), entry.request().getTransactionId());
            if (entry.direction() == MutationRecoveryJournal.Direction.INBOUND) {
                InboundTransaction transaction = new InboundTransaction(entry.request());
                transaction.journaled = true;
                transaction.persistedState = entry.state();
                transaction.persistedResultHash = entry.result() != null
                        ? entry.result().getResultHash() : null;
                if (entry.result() != null) {
                    transaction.started.set(true);
                    transaction.result.complete(entry.result());
                }
                inbound.putIfAbsent(key, transaction);
                continue;
            }
            OutboundTransaction transaction = new OutboundTransaction(entry.request());
            transaction.journaled = true;
            transaction.persistedState = entry.state();
            transaction.persistedResultHash = entry.result() != null ? entry.result().getResultHash() : null;
            transaction.result = entry.result();
            transaction.state = parseOutboundState(entry.state(), entry.result());
            outbound.putIfAbsent(key, transaction);
            transaction.receipt.thenAccept(receipt -> settleRecovered(transaction.request, receipt));
            if (entry.result() != null && transaction.state != OutboundState.SETTLED_WAIT_CLOSE) {
                transaction.receipt.complete(new Receipt(entry.result(), () -> settle(key, transaction)));
            }
        }
    }

    private OutboundState parseOutboundState(String state, MutationResultMessage result) {
        if ("SETTLED_WAIT_CLOSE".equals(state)) return OutboundState.SETTLED_WAIT_CLOSE;
        if (result != null) return OutboundState.DECIDED;
        return OutboundState.RECOVERING;
    }

    private void settleRecovered(MutationExecute request, Receipt receipt) {
        MutationResultMessage result = receipt.result();
        org.edtp.theexchange.model.NeutralItem item = null;
        String reason = null;
        if (result.isSuccess()) {
            if (request.getKind() == MutationKind.TAKE || request.getKind() == MutationKind.SWAP) {
                item = result.getTransferredItem();
                reason = "RECOVERED_RESULT";
            }
        } else if (request.getKind() == MutationKind.PUT || request.getKind() == MutationKind.SWAP) {
            item = request.getOfferedItem();
            reason = "RECOVERED_RETURN";
        }
        if (item != null && !item.isEmpty()) {
            if (settlementVault == null) {
                log("Recovered item has no settlement vault: " + request.getTransactionId());
                return;
            }
            settlementVault.deposit(request.getTransactionId(), request.getPlayerUuid(),
                    request.getPlayerName(), item, reason);
        }
        receipt.acknowledgeSettlement();
    }

    private void persistOutbound(TransactionKey key, OutboundTransaction transaction) {
        if (journal == null || outbound.get(key) != transaction) return;
        String state = transaction.state.name();
        String resultHash = transaction.result != null ? transaction.result.getResultHash() : null;
        if (transaction.journaled && Objects.equals(state, transaction.persistedState)
                && Objects.equals(resultHash, transaction.persistedResultHash)) {
            return;
        }
        try {
            journal.upsert(MutationRecoveryJournal.Direction.OUTBOUND, key.peerId(),
                    state, transaction.request, transaction.result);
            transaction.journaled = true;
            transaction.persistedState = state;
            transaction.persistedResultHash = resultHash;
        } catch (RuntimeException error) {
            log("Failed to persist recovery transaction " + key + ": " + error.getMessage());
        }
    }

    private void persistInbound(TransactionKey key, InboundTransaction transaction,
                                MutationResultMessage result) {
        if (journal == null || inbound.get(key) != transaction) return;
        String state = result != null ? "DECIDED" : "RUNNING";
        String resultHash = result != null ? result.getResultHash() : null;
        if (transaction.journaled && Objects.equals(state, transaction.persistedState)
                && Objects.equals(resultHash, transaction.persistedResultHash)) {
            return;
        }
        try {
            journal.upsert(MutationRecoveryJournal.Direction.INBOUND, key.peerId(),
                    state, transaction.request, result);
            transaction.journaled = true;
            transaction.persistedState = state;
            transaction.persistedResultHash = resultHash;
        } catch (RuntimeException error) {
            log("Failed to persist inbound recovery transaction " + key + ": " + error.getMessage());
        }
    }

    private void deleteJournal(MutationRecoveryJournal.Direction direction, TransactionKey key) {
        if (journal == null) return;
        try {
            journal.delete(direction, key.peerId(), key.transactionId());
        } catch (RuntimeException error) {
            log("Failed to delete recovery transaction " + key + ": " + error.getMessage());
        }
    }

    @Override
    public void close() {
        draining = true;
        scheduler.shutdownNow();
    }
}
