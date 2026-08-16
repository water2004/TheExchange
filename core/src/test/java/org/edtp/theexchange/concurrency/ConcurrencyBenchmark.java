package org.edtp.theexchange.concurrency;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.network.protocol.messages.MutationExecute;
import org.edtp.theexchange.service.LoopbackMutationTestCluster;
import org.edtp.theexchange.service.MutationTransactionCoordinator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saturation benchmark for V2 over one real loopback TLS/TCP connection.
 *
 * A bounded asynchronous window keeps the requested number of transactions in flight. Every
 * measured transaction traverses Connection writers/readers, frame encoding/decoding,
 * MutationTransactionCoordinator, the authoritative inventory and RESULT -> SETTLED -> CLOSED.
 * Diagnostic frame histories and per-transaction fixture maps are disabled on this benchmark
 * path so the harness does not dominate the protocol being measured.
 */
class ConcurrencyBenchmark {
    private static final int SLOTS = 54;
    private static final int DEFAULT_OPERATIONS = 100_000;
    private static final int DEFAULT_AUTHORITY_THREADS = 8;
    private static final int WARMUP_OPERATIONS = 1_000;
    private static final long PROBE_MILLIS = 30_000L;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(120);
    private static final AtomicLong TRANSACTION_IDS = new AtomicLong();

    @Tag("bench")
    @Test
    void run() throws Exception {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int authorityThreads = Math.min(availableProcessors, positiveInteger(
                "exchange.bench.authorityThreads", DEFAULT_AUTHORITY_THREADS));
        int operations = positiveInteger("exchange.bench.operations", DEFAULT_OPERATIONS);
        int[] concurrencyLevels = concurrencyLevels();
        Path projectRoot = findProjectRoot();
        Path csvFile = projectRoot.resolve("bench_data.csv");
        Path fixtureRoot = Files.createTempDirectory("exchange-network-bench-");
        List<String> lines = new ArrayList<>();
        lines.add("scenario,authority_threads,in_flight,ops,elapsedMs,rate_s,successRate,"
                + "result_p50_us,result_p95_us,result_p99_us,result_max_us");
        System.out.println("[bench] mode=real-loopback-tls-saturation cores="
                + availableProcessors + " authority_threads=" + authorityThreads
                + " operations=" + operations + " concurrency="
                + Arrays.toString(concurrencyLevels) + " csv=" + csvFile);

        try {
            for (int inFlight : concurrencyLevels) {
                System.out.println("[bench] --- in_flight=" + inFlight + " ---");
                runScenario("partitioned", authorityThreads, inFlight, operations,
                        fixtureRoot, lines, this::partitionedRequests);
                runScenario("random", authorityThreads, inFlight, operations,
                        fixtureRoot, lines, this::randomRequests);
                runScenario("sameSlot", authorityThreads, inFlight, operations,
                        fixtureRoot, lines, this::sameSlotRequests);
            }
            Files.write(csvFile, lines);
            System.out.println("[bench] wrote " + csvFile + " (" + lines.size() + " rows)");
        } finally {
            deleteRecursively(fixtureRoot);
        }
    }

    @FunctionalInterface
    private interface Scenario {
        RequestFactory prepare(LoopbackMutationTestCluster cluster, int inFlight, String runId);
    }

    @FunctionalInterface
    private interface RequestFactory {
        MutationExecute create(int operation, int lane);
    }

    private void runScenario(String name, int authorityThreads, int inFlight, int operations,
                             Path fixtureRoot, List<String> lines, Scenario scenario) throws Exception {
        try (LoopbackMutationTestCluster cluster = new LoopbackMutationTestCluster(
                fixtureRoot, PROBE_MILLIS, false, authorityThreads, false)) {
            int warmupConcurrency = Math.min(inFlight, 128);
            RequestFactory warmup = partitionedRequests(cluster, warmupConcurrency,
                    "warmup-" + name + '-' + inFlight);
            Sample warmupSample = runWindow(cluster, warmupConcurrency, WARMUP_OPERATIONS, warmup);
            cluster.awaitTransactionsClosed(Duration.ofSeconds(30));
            assertEquals(WARMUP_OPERATIONS, warmupSample.success(), "warm-up mutations must succeed");

            RequestFactory requests = scenario.prepare(cluster, inFlight,
                    "measure-" + name + '-' + inFlight);
            long executionsBeforeMeasurement = cluster.remoteExecutions();
            long commitsBeforeMeasurement = cluster.remoteCommits();
            long begin = System.nanoTime();
            Sample sample = runWindow(cluster, inFlight, operations, requests);
            cluster.awaitTransactionsClosed(CLOSE_TIMEOUT);
            long elapsedMs = Math.max(1L,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));

            assertEquals(operations, sample.success() + sample.failure(),
                    "every benchmark operation must produce a result");
            assertEquals(operations, cluster.remoteExecutions() - executionsBeforeMeasurement,
                    "every submitted mutation must reach the authority");
            assertEquals(sample.success(), cluster.remoteCommits() - commitsBeforeMeasurement,
                    "only successful authority results may commit inventory state");
            assertEquals(Math.min(inFlight, operations), sample.maxInFlight(),
                    "the asynchronous generator must reach its requested in-flight window");

            double rate = operations * 1_000.0 / elapsedMs;
            double successRate = sample.success() * 100.0 / operations;
            lines.add(String.format("%s,%d,%d,%d,%d,%.0f,%.1f,%d,%d,%d,%d",
                    name, authorityThreads, inFlight, operations, elapsedMs, rate, successRate,
                    sample.p50Micros(), sample.p95Micros(), sample.p99Micros(), sample.maxMicros()));
            System.out.printf("[bench] %-11s inFlight=%d ops=%d elapsed=%dms rate=%,.0f tx/s "
                            + "success=%.1f%% result-p50/p95/p99=%d/%d/%d us%n",
                    name, inFlight, operations, elapsedMs, rate, successRate,
                    sample.p50Micros(), sample.p95Micros(), sample.p99Micros());
        }
    }

    /** Independent player warehouses: high concurrency without artificial slot conflicts. */
    private RequestFactory partitionedRequests(LoopbackMutationTestCluster cluster,
                                               int inFlight, String runId) {
        InventoryScope[] scopes = new InventoryScope[inFlight];
        InventoryAccess[] accesses = new InventoryAccess[inFlight];
        String[] playerUuids = new String[inFlight];
        for (int lane = 0; lane < inFlight; lane++) {
            String playerUuid = runId + "-player-" + lane;
            InventoryScope scope = InventoryScope.player(playerUuid);
            playerUuids[lane] = playerUuid;
            scopes[lane] = scope;
            accesses[lane] = InventoryAccess.playerSession(
                    playerUuid, "benchmark-token", playerUuid, "Benchmark" + lane,
                    scope, Long.MAX_VALUE);
            cluster.seedRemote(scope, 0, cluster.item("minecraft:diamond", 64));
        }
        return (operation, lane) -> cluster.swapRequest(nextId(), 0,
                cluster.item("minecraft:diamond", 64), "minecraft:diamond",
                cluster.remoteVersion(scopes[lane], 0), 64, false,
                playerUuids[lane], "Benchmark" + lane, accesses[lane]);
    }

    /** One server warehouse, uniformly distributed over its 54 real slots. */
    private RequestFactory randomRequests(LoopbackMutationTestCluster cluster,
                                          int inFlight, String runId) {
        seedServerWarehouse(cluster);
        return (operation, lane) -> {
            int slot = mixedSlot(operation);
            return cluster.swapRequest(nextId(), slot,
                    cluster.item("minecraft:diamond", 64), "minecraft:diamond",
                    cluster.remoteVersion(slot), 64, false);
        };
    }

    /** Worst-case optimistic-lock contention on one real server-warehouse slot. */
    private RequestFactory sameSlotRequests(LoopbackMutationTestCluster cluster,
                                            int inFlight, String runId) {
        cluster.seedRemote(0, cluster.item("minecraft:diamond", 64));
        return (operation, lane) -> cluster.swapRequest(nextId(), 0,
                cluster.item("minecraft:diamond", 64), "minecraft:diamond",
                cluster.remoteVersion(0), 64, false);
    }

    private void seedServerWarehouse(LoopbackMutationTestCluster cluster) {
        for (int slot = 0; slot < SLOTS; slot++) {
            cluster.seedRemote(slot, cluster.item("minecraft:diamond", 64));
        }
    }

    private Sample runWindow(LoopbackMutationTestCluster cluster, int requestedInFlight,
                             int operations, RequestFactory factory) throws Exception {
        int lanes = Math.min(requestedInFlight, operations);
        AtomicInteger nextOperation = new AtomicInteger();
        AtomicInteger currentInFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicLong success = new AtomicLong();
        AtomicLong failure = new AtomicLong();
        long[] resultLatencyNanos = new long[operations];
        CountDownLatch completed = new CountDownLatch(operations);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int lane = 0; lane < lanes; lane++) {
            submitNext(cluster, factory, lane, operations, nextOperation,
                    currentInFlight, maxInFlight, success, failure,
                    resultLatencyNanos, completed, errors);
        }

        assertTrue(completed.await(180, TimeUnit.SECONDS),
                "benchmark timed out with " + completed.getCount() + " transactions incomplete");
        if (!errors.isEmpty()) {
            AssertionError benchmarkError = new AssertionError(
                    "benchmark transaction failed unexpectedly", errors.peek());
            errors.stream().skip(1).forEach(benchmarkError::addSuppressed);
            throw benchmarkError;
        }
        Arrays.sort(resultLatencyNanos);
        return new Sample(success.get(), failure.get(), maxInFlight.get(),
                percentileMicros(resultLatencyNanos, 0.50),
                percentileMicros(resultLatencyNanos, 0.95),
                percentileMicros(resultLatencyNanos, 0.99),
                TimeUnit.NANOSECONDS.toMicros(resultLatencyNanos[resultLatencyNanos.length - 1]));
    }

    private void submitNext(LoopbackMutationTestCluster cluster, RequestFactory factory, int lane,
                            int operations, AtomicInteger nextOperation,
                            AtomicInteger currentInFlight, AtomicInteger maxInFlight,
                            AtomicLong success, AtomicLong failure, long[] resultLatencyNanos,
                            CountDownLatch completed, ConcurrentLinkedQueue<Throwable> errors) {
        int operation = nextOperation.getAndIncrement();
        if (operation >= operations) return;

        MutationExecute request;
        CompletableFuture<MutationTransactionCoordinator.Receipt> future;
        boolean admitted = false;
        long startedAt = System.nanoTime();
        try {
            request = factory.create(operation, lane);
            int current = currentInFlight.incrementAndGet();
            admitted = true;
            maxInFlight.accumulateAndGet(current, Math::max);
            future = cluster.executeFromA(request);
        } catch (Throwable error) {
            if (admitted) currentInFlight.decrementAndGet();
            errors.add(error);
            completed.countDown();
            submitNext(cluster, factory, lane, operations, nextOperation,
                    currentInFlight, maxInFlight, success, failure,
                    resultLatencyNanos, completed, errors);
            return;
        }

        future.whenComplete((receipt, error) -> {
            resultLatencyNanos[operation] = System.nanoTime() - startedAt;
            currentInFlight.decrementAndGet();
            if (error != null || receipt == null) {
                errors.add(error != null ? error
                        : new IllegalStateException("mutation completed without a receipt"));
            } else {
                if (receipt.result().isSuccess()) success.incrementAndGet();
                else failure.incrementAndGet();
                receipt.acknowledgeSettlement();
            }
            completed.countDown();
            submitNext(cluster, factory, lane, operations, nextOperation,
                    currentInFlight, maxInFlight, success, failure,
                    resultLatencyNanos, completed, errors);
        });
    }

    private static long percentileMicros(long[] sortedNanos, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sortedNanos.length * percentile) - 1);
        return TimeUnit.NANOSECONDS.toMicros(sortedNanos[index]);
    }

    private static int mixedSlot(int operation) {
        int mixed = operation * 0x9E3779B9;
        mixed ^= mixed >>> 16;
        return Math.floorMod(mixed, SLOTS);
    }

    private static int positiveInteger(String property, int defaultValue) {
        return Math.max(1, Integer.getInteger(property, defaultValue));
    }

    private static int[] concurrencyLevels() {
        String configured = System.getProperty(
                "exchange.bench.inFlight", "1,8,32,128,512,2048,4096");
        int[] levels = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .mapToInt(Integer::parseInt)
                .filter(value -> value > 0)
                .distinct()
                .sorted()
                .toArray();
        if (levels.length == 0) {
            throw new IllegalArgumentException("exchange.bench.inFlight must contain a positive integer");
        }
        return levels;
    }

    private String nextId() {
        return "bench-" + TRANSACTION_IDS.incrementAndGet();
    }

    private record Sample(long success, long failure, int maxInFlight,
                          long p50Micros, long p95Micros, long p99Micros, long maxMicros) {}

    private static void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var children = Files.list(path)) {
                    children.forEach(ConcurrencyBenchmark::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static Path findProjectRoot() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Cannot find project root");
        return directory;
    }
}
