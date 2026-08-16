package org.edtp.theexchange.service;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.MutationHashes;
import org.edtp.theexchange.network.protocol.messages.MutationExecute;
import org.edtp.theexchange.network.protocol.messages.MutationKind;
import org.edtp.theexchange.network.protocol.messages.MutationResultMessage;
import org.edtp.theexchange.network.protocol.messages.TransactionStatus;
import org.edtp.theexchange.network.tls.PinnedPeerKeyStore;
import org.edtp.theexchange.storage.DatabaseManager;
import org.edtp.theexchange.storage.LocalInventoryCache;
import org.edtp.theexchange.storage.MutationRecoveryJournal;
import org.edtp.theexchange.storage.SettlementVault;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Two real TLS/TCP Exchange nodes with deterministic faults injected only after frame decoding.
 * Production network and transaction classes are used unchanged.
 */
public final class LoopbackMutationTestCluster implements AutoCloseable {
    public static final String NODE_A = "node-a";
    public static final String NODE_B = "node-b";
    static final Duration DEFAULT_WAIT = Duration.ofSeconds(8);
    private static final String PASSWORD_A = "password-a";
    private static final String PASSWORD_B = "password-b";
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();
    private static final AtomicInteger CLUSTER_IDS = new AtomicInteger();

    Node a;
    Node b;
    private RemoteServer nodeBRemote;
    private final long probeMillis;
    private final boolean persistent;
    private final Path identityRoot;
    private final Path dataRoot;
    private final TestInventory inventoryA;
    private final TestInventory inventoryB;
    private final boolean recordDiagnostics;

    public LoopbackMutationTestCluster(Path root, long probeMillis) throws Exception {
        this(root, probeMillis, false, 8);
    }

    public LoopbackMutationTestCluster(Path root, long probeMillis, boolean persistent) throws Exception {
        this(root, probeMillis, persistent, 8);
    }

    public LoopbackMutationTestCluster(Path root, long probeMillis, boolean persistent,
                                       int inventoryThreads) throws Exception {
        this(root, probeMillis, persistent, inventoryThreads, true);
    }

    public LoopbackMutationTestCluster(Path root, long probeMillis, boolean persistent,
                                       int inventoryThreads, boolean recordDiagnostics) throws Exception {
        Objects.requireNonNull(root, "root");
        this.probeMillis = Math.max(100L, probeMillis);
        this.persistent = persistent;
        this.recordDiagnostics = recordDiagnostics;
        int threads = Math.max(1, inventoryThreads);
        this.inventoryA = new TestInventory(threads, recordDiagnostics);
        this.inventoryB = new TestInventory(threads, recordDiagnostics);
        this.identityRoot = root.resolve("identities");
        this.dataRoot = root.resolve("cluster-" + CLUSTER_IDS.incrementAndGet());
        Node first = null;
        Node second = null;
        try {
            int firstPort = freePort();
            int secondPort;
            do {
                secondPort = freePort();
            } while (secondPort == firstPort);
            first = newNode(NODE_A, PASSWORD_A, firstPort, inventoryA);
            second = newNode(NODE_B, PASSWORD_B, secondPort, inventoryB);
            this.a = first;
            this.b = second;
            updateNodeBRemote();

            first.manager.startInbound();
            second.manager.startInbound();
            connectAtoB();
        } catch (Throwable error) {
            if (first != null) first.close();
            if (second != null) second.close();
            throw error;
        }
    }

    public MutationExecute putRequest(String transactionId, int slot) {
        return putRequest(transactionId, slot, item("minecraft:stone", 1), 0);
    }

    public MutationExecute putRequest(String transactionId, int slot, NeutralItem item,
                                      int expectedVersion) {
        MutationExecute request = new MutationExecute(transactionId, null, MutationKind.PUT,
                slot, item, null, expectedVersion, item.getCount(), false,
                "player-uuid", "Player", InventoryAccess.server());
        request.setIntentHash(MutationHashes.intent(request));
        return request;
    }

    public MutationExecute takeRequest(String transactionId, int slot, String expectedItemId,
                                       int expectedVersion, int count) {
        MutationExecute request = new MutationExecute(transactionId, null, MutationKind.TAKE,
                slot, null, expectedItemId, expectedVersion, count, false,
                "player-uuid", "Player", InventoryAccess.server());
        request.setIntentHash(MutationHashes.intent(request));
        return request;
    }

    public MutationExecute swapRequest(String transactionId, int slot, NeutralItem offered,
                                       String expectedItemId, int expectedVersion, int count,
                                       boolean boundedMerge) {
        return swapRequest(transactionId, slot, offered, expectedItemId, expectedVersion,
                count, boundedMerge, "player-uuid", "Player", InventoryAccess.server());
    }

    public MutationExecute swapRequest(String transactionId, int slot, NeutralItem offered,
                                       String expectedItemId, int expectedVersion, int count,
                                       boolean boundedMerge, String playerUuid, String playerName,
                                       InventoryAccess access) {
        MutationExecute request = new MutationExecute(transactionId, null, MutationKind.SWAP,
                slot, offered, expectedItemId, expectedVersion, count, boundedMerge,
                playerUuid, playerName, access);
        request.setIntentHash(MutationHashes.intent(request));
        return request;
    }

    public NeutralItem item(String itemId, int count) {
        NeutralItem item = new NeutralItem(
                itemId, count, itemId, new byte[0], false, "loopback-test");
        item.setMaxStackSize(64);
        return item;
    }

    public CompletableFuture<MutationTransactionCoordinator.Receipt> executeFromA(
            MutationExecute request) {
        return a.coordinator.execute(NODE_B, request);
    }

    public void seedRemote(int slot, NeutralItem item) {
        seedRemote(InventoryScope.server(), slot, item);
    }

    public void seedRemote(InventoryScope scope, int slot, NeutralItem item) {
        b.inventory.seed(scope, slot, item);
    }

    public NeutralItem remoteItem(int slot) {
        return b.inventory.item(slot);
    }

    public int remoteVersion(int slot) {
        return remoteVersion(InventoryScope.server(), slot);
    }

    public int remoteVersion(InventoryScope scope, int slot) {
        return b.inventory.version(scope, slot);
    }

    public int remoteExecutions() {
        return b.inventory.executions();
    }

    public int remoteCommits() {
        return b.inventory.commits();
    }

    public void awaitTransactionsClosed(Duration timeout) {
        await(() -> a.coordinator.outboundCount() == 0 && b.coordinator.inboundCount() == 0,
                timeout, "transactions did not close");
    }

    void disconnectAfromB() {
        a.manager.disconnect(NODE_B);
        await(() -> a.manager.getConnection(NODE_B) == null
                        && b.manager.getConnection(NODE_A) == null,
                DEFAULT_WAIT, "loopback peers did not disconnect");
    }

    void connectAtoB() {
        if (!a.manager.connectToRemote(nodeBRemote)) {
            throw new AssertionError("loopback TCP connect failed");
        }
        await(this::isConnected, DEFAULT_WAIT, "loopback peers did not authenticate");
    }

    void checkpointAndRestartA() {
        a.coordinator.checkpointOutstanding();
        a.close();
        await(() -> b.manager.getConnection(NODE_A) == null,
                DEFAULT_WAIT, "node B did not observe node A shutdown");
        a = newNode(NODE_A, PASSWORD_A, uncheckedFreePort(), inventoryA);
        a.manager.startInbound();
        connectAtoB();
    }

    void checkpointAndRestartB() {
        b.coordinator.checkpointOutstanding();
        b.close();
        await(() -> a.manager.getConnection(NODE_B) == null,
                DEFAULT_WAIT, "node A did not observe node B shutdown");
        b = newNode(NODE_B, PASSWORD_B, uncheckedFreePort(), inventoryB);
        updateNodeBRemote();
        b.manager.startInbound();
        connectAtoB();
    }

    void checkpointAndRestartBoth() {
        a.coordinator.checkpointOutstanding();
        b.coordinator.checkpointOutstanding();
        a.close();
        b.close();
        int firstPort = uncheckedFreePort();
        int secondPort;
        do {
            secondPort = uncheckedFreePort();
        } while (secondPort == firstPort);
        a = newNode(NODE_A, PASSWORD_A, firstPort, inventoryA);
        b = newNode(NODE_B, PASSWORD_B, secondPort, inventoryB);
        updateNodeBRemote();
        a.manager.startInbound();
        b.manager.startInbound();
        connectAtoB();
    }

    boolean isConnected() {
        return authenticated(a.manager.getConnection(NODE_B))
                && authenticated(b.manager.getConnection(NODE_A));
    }

    void awaitClosed() {
        await(() -> a.coordinator.outboundCount() == 0 && b.coordinator.inboundCount() == 0,
                DEFAULT_WAIT, "transaction did not reach CLOSED on both nodes");
        int observedFrames = a.faults.received() + b.faults.received();
        sleep(Math.max(250L, probeMillis * 2L));
        if (a.coordinator.outboundCount() != 0 || b.coordinator.inboundCount() != 0) {
            throw new AssertionError("closed transaction was resurrected during the quiet period");
        }
        int framesAfterQuietPeriod = a.faults.received() + b.faults.received();
        if (framesAfterQuietPeriod != observedFrames) {
            throw new AssertionError("closed transaction emitted "
                    + (framesAfterQuietPeriod - observedFrames) + " late mutation frame(s)");
        }
    }

    static void await(BooleanSupplier condition, Duration timeout, String failureMessage) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(failureMessage);
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting: " + failureMessage, error);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted during loopback quiet period", error);
        }
    }

    @Override
    public void close() {
        a.close();
        b.close();
        inventoryA.close();
        inventoryB.close();
    }

    private static boolean authenticated(Connection connection) {
        return connection != null && connection.isRunning() && connection.isAuthenticated();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static int uncheckedFreePort() {
        try {
            return freePort();
        } catch (IOException error) {
            throw new IllegalStateException("failed to allocate loopback port", error);
        }
    }

    private Node newNode(String name, String password, int port, TestInventory inventory) {
        return new Node(name, password, port, identityRoot.resolve(name), dataRoot.resolve(name),
                probeMillis, inventory, persistent, recordDiagnostics);
    }

    private void updateNodeBRemote() {
        nodeBRemote = new RemoteServer(NODE_B, "127.0.0.1",
                b.manager.getLocalPort(), PASSWORD_B, true);
    }

    static final class Node implements AutoCloseable {
        final NetworkManager manager;
        final MutationTransactionCoordinator coordinator;
        final FrameFaults faults;
        final TestInventory inventory;
        final List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
        final DatabaseManager database;
        final MutationRecoveryJournal journal;
        final SettlementVault vault;

        private Node(String name, String password, int port, Path identityRoot, Path dataRoot,
                     long probeMillis, TestInventory inventory, boolean persistent,
                     boolean recordDiagnostics) {
            this.inventory = inventory;
            this.faults = new FrameFaults(recordDiagnostics);
            try {
                Files.createDirectories(identityRoot);
                Files.createDirectories(dataRoot);
            } catch (IOException error) {
                throw new IllegalStateException("failed to create loopback node directories", error);
            }
            manager = new NetworkManager(port, identityRoot.resolve("server.p12"),
                    new PinnedPeerKeyStore(identityRoot.resolve("known-peers.properties")),
                    name, KEYSTORE_PASSWORD, "loopback-test");
            if (persistent) {
                database = new DatabaseManager(dataRoot.resolve("exchange.db").toString());
                database.initialize();
                journal = new MutationRecoveryJournal(database);
                vault = new SettlementVault(database);
                coordinator = new MutationTransactionCoordinator(probeMillis, logs::add, journal, vault);
            } else {
                database = null;
                journal = null;
                vault = null;
                coordinator = new MutationTransactionCoordinator(probeMillis, logs::add);
            }
            coordinator.bind(manager, probeMillis);
            manager.setLocalServerName(name);
            manager.setLocalPassword(password);
            manager.setOnlineHandler(coordinator::onPeerOnline);
            manager.setMessageRouter((connection, type, message) -> faults.route(type, message, () ->
                    coordinator.route(connection, type, message, inventory::execute)));
        }

        Connection connection(String peerId) {
            return manager.getConnection(peerId);
        }

        void awaitLog(Predicate<String> predicate, String failureMessage) {
            await(() -> logs.stream().anyMatch(predicate), DEFAULT_WAIT, failureMessage);
        }

        @Override
        public void close() {
            manager.shutdown();
            coordinator.close();
            if (database != null) database.close();
        }
    }

    static final class FrameFaults {
        private enum Action { DROP, HOLD, DUPLICATE }

        record FrameEvent(FrameType type, Object message) {}

        private final Map<FrameType, ConcurrentLinkedQueue<Action>> actions = new ConcurrentHashMap<>();
        private final Map<FrameType, BlockingQueue<Runnable>> held = new ConcurrentHashMap<>();
        private final Map<FrameType, AtomicInteger> received = new ConcurrentHashMap<>();
        private final boolean recordDiagnostics;
        private final ConcurrentLinkedQueue<FrameEvent> events = new ConcurrentLinkedQueue<>();

        private FrameFaults(boolean recordDiagnostics) {
            this.recordDiagnostics = recordDiagnostics;
        }

        void dropNext(FrameType type) {
            action(type, Action.DROP);
        }

        void holdNext(FrameType type) {
            action(type, Action.HOLD);
        }

        void duplicateNext(FrameType type) {
            action(type, Action.DUPLICATE);
        }

        int received(FrameType type) {
            AtomicInteger count = received.get(type);
            return count != null ? count.get() : 0;
        }

        int received() {
            return events.size();
        }

        <T> List<T> messages(FrameType type, Class<T> messageType) {
            return events.stream()
                    .filter(event -> event.type() == type && messageType.isInstance(event.message()))
                    .map(event -> messageType.cast(event.message()))
                    .toList();
        }

        <T> T awaitMessage(FrameType type, Class<T> messageType, Predicate<T> predicate) {
            await(() -> messages(type, messageType).stream().anyMatch(predicate), DEFAULT_WAIT,
                    "did not receive expected " + type + " message");
            return messages(type, messageType).stream().filter(predicate).findFirst().orElseThrow();
        }

        TransactionStatus awaitStatus(String transactionId, TransactionStatus.State state) {
            return awaitMessage(FrameType.TRANSACTION_STATUS, TransactionStatus.class,
                    status -> Objects.equals(transactionId, status.getTransactionId())
                            && status.getState() == state);
        }

        void awaitReceived(FrameType type, int count) {
            await(() -> received(type) >= count, DEFAULT_WAIT,
                    "did not receive " + count + " frame(s) of type " + type);
        }

        void releaseOne(FrameType type) {
            BlockingQueue<Runnable> queue = held.get(type);
            Runnable delivery = queue != null ? queue.poll() : null;
            if (delivery == null) {
                throw new AssertionError("no held frame of type " + type);
            }
            delivery.run();
        }

        private void action(FrameType type, Action action) {
            actions.computeIfAbsent(type, ignored -> new ConcurrentLinkedQueue<>()).add(action);
        }

        private void route(FrameType type, Object message, Runnable delivery) {
            if (recordDiagnostics) {
                events.add(new FrameEvent(type, message));
                received.computeIfAbsent(type, ignored -> new AtomicInteger()).incrementAndGet();
            }
            ConcurrentLinkedQueue<Action> queue = actions.get(type);
            Action action = queue != null ? queue.poll() : null;
            if (action == Action.DROP) {
                return;
            }
            if (action == Action.HOLD) {
                held.computeIfAbsent(type, ignored -> new LinkedBlockingQueue<>()).add(delivery);
                return;
            }
            delivery.run();
            if (action == Action.DUPLICATE) {
                delivery.run();
            }
        }
    }

    static final class TestInventory implements AutoCloseable {
        private final ExecutorService executor;
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final boolean recordDiagnostics;
        private final ConcurrentHashMap<String, AtomicInteger> executionsByTransaction = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> commitsByTransaction = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ExecutionPause> executionPauses = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<InventoryScope, LocalInventoryCache> inventories = new ConcurrentHashMap<>();
        private volatile CyclicBarrier executionBarrier;

        private TestInventory(int threads, boolean recordDiagnostics) {
            this.recordDiagnostics = recordDiagnostics;
            inventories.put(InventoryScope.server(), new LocalInventoryCache(InventoryScope.server(), 54));
            executor = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "exchange-loopback-inventory");
                thread.setDaemon(true);
                return thread;
            });
        }

        CompletableFuture<MutationResultMessage> execute(String peerId, MutationExecute request) {
            ExecutionPause pause = recordDiagnostics
                    ? executionPauses.get(request.getTransactionId()) : null;
            if (pause != null) {
                recordExecutionStart(request);
                pause.request = request;
                pause.started.countDown();
                pause.result.whenComplete((ignored, error) -> active.decrementAndGet());
                return pause.result;
            }
            return CompletableFuture.supplyAsync(() -> apply(request), executor);
        }

        int executions() {
            return executions.get();
        }

        int executions(String transactionId) {
            AtomicInteger count = executionsByTransaction.get(transactionId);
            return count != null ? count.get() : 0;
        }

        int commits() {
            return commits.get();
        }

        int commits(String transactionId) {
            AtomicInteger count = commitsByTransaction.get(transactionId);
            return count != null ? count.get() : 0;
        }

        int maxConcurrentExecutions() {
            return maxActive.get();
        }

        void seed(int slot, NeutralItem item) {
            seed(InventoryScope.server(), slot, item);
        }

        void seed(InventoryScope scope, int slot, NeutralItem item) {
            LocalInventoryCache inventory = inventory(scope);
            var result = inventory.put(slot, item, inventory.getVersion(slot), "seed");
            if (!result.success()) {
                throw new IllegalStateException("failed to seed slot " + slot + ": " + result.failReason());
            }
        }

        NeutralItem item(int slot) {
            return inventory(InventoryScope.server()).get(slot);
        }

        int version(int slot) {
            return version(InventoryScope.server(), slot);
        }

        int version(InventoryScope scope, int slot) {
            return inventory(scope).getVersion(slot);
        }

        int totalItems(String itemId) {
            return inventory(InventoryScope.server()).snapshot().stream()
                    .filter(Objects::nonNull)
                    .filter(item -> !item.isEmpty() && Objects.equals(itemId, item.getItemId()))
                    .mapToInt(NeutralItem::getCount)
                    .sum();
        }

        void requireConcurrentExecutions(int parties) {
            executionBarrier = new CyclicBarrier(parties);
        }

        void pauseExecution(String transactionId) {
            if (executionPauses.putIfAbsent(transactionId, new ExecutionPause()) != null) {
                throw new IllegalStateException("execution already paused: " + transactionId);
            }
        }

        void awaitExecutionStarted(String transactionId) {
            ExecutionPause pause = executionPauses.get(transactionId);
            if (pause == null) throw new IllegalStateException("execution is not paused: " + transactionId);
            try {
                if (!pause.started.await(DEFAULT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("execution did not start: " + transactionId);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting execution: " + transactionId, error);
            }
        }

        void completeExecution(String transactionId) {
            ExecutionPause pause = executionPauses.get(transactionId);
            if (pause == null) throw new IllegalStateException("execution is not paused: " + transactionId);
            MutationExecute request = pause.request;
            if (request == null) throw new IllegalStateException("execution has not started: " + transactionId);
            if (!pause.result.complete(successResult(request))) {
                throw new IllegalStateException("execution already completed: " + transactionId);
            }
        }

        void failExecution(String transactionId, Throwable error) {
            ExecutionPause pause = executionPauses.get(transactionId);
            if (pause == null) throw new IllegalStateException("execution is not paused: " + transactionId);
            if (!pause.result.completeExceptionally(error)) {
                throw new IllegalStateException("execution already completed: " + transactionId);
            }
        }

        private MutationResultMessage apply(MutationExecute request) {
            recordExecutionStart(request);
            try {
                CyclicBarrier barrier = executionBarrier;
                if (barrier != null) {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception error) {
                        throw new IllegalStateException("concurrent execution barrier failed", error);
                    }
                }
                return successResult(request);
            } finally {
                active.decrementAndGet();
            }
        }

        private void recordExecutionStart(MutationExecute request) {
            int concurrent = active.incrementAndGet();
            maxActive.accumulateAndGet(concurrent, Math::max);
            executions.incrementAndGet();
            if (recordDiagnostics) {
                executionsByTransaction.computeIfAbsent(request.getTransactionId(),
                        ignored -> new AtomicInteger()).incrementAndGet();
            }
        }

        private MutationResultMessage successResult(MutationExecute request) {
            InventoryScope scope = request.getAccess().effectiveScope();
            if (scope == null) scope = InventoryScope.server();
            LocalInventoryCache inventory = inventory(scope);
            var outcome = switch (request.getKind()) {
                case PUT -> inventory.put(request.getSlot(), request.getOfferedItem(),
                        request.getExpectedVersion(), request.getPlayerUuid());
                case TAKE -> inventory.take(request.getSlot(), request.getExpectedItemId(),
                        request.getExpectedVersion(), request.getCount());
                case SWAP -> inventory.swap(request.getSlot(), request.getOfferedItem(),
                        request.getExpectedItemId(), request.getExpectedVersion(), request.getCount(),
                        request.isBoundedMerge(), request.getPlayerUuid());
            };
            if (outcome.success()) {
                commits.incrementAndGet();
                if (recordDiagnostics) {
                    commitsByTransaction.computeIfAbsent(request.getTransactionId(),
                            ignored -> new AtomicInteger()).incrementAndGet();
                }
            }
            NeutralItem current = inventory.get(request.getSlot());
            NeutralItem transferred = outcome.success() && request.getKind() != MutationKind.PUT
                    ? outcome.item() : null;
            int version = inventory.getVersion(request.getSlot());
            MutationResultMessage result = new MutationResultMessage(
                    request.getTransactionId(), request.getIntentHash(), null,
                    request.getKind(), outcome.success(), request.getSlot(), current, transferred,
                    outcome.failReason(), System.currentTimeMillis(), version, scope);
            result.setResultHash(MutationHashes.result(result));
            return result;
        }

        private LocalInventoryCache inventory(InventoryScope scope) {
            InventoryScope resolved = scope != null ? scope : InventoryScope.server();
            return inventories.computeIfAbsent(resolved,
                    key -> new LocalInventoryCache(key, 54));
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }

        private static final class ExecutionPause {
            private final CountDownLatch started = new CountDownLatch(1);
            private final CompletableFuture<MutationResultMessage> result = new CompletableFuture<>();
            private volatile MutationExecute request;
        }
    }
}
