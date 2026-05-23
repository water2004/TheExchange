package org.edtp.theexchange.concurrency;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.OperationType;
import org.edtp.theexchange.storage.DatabaseManager;
import org.edtp.theexchange.storage.LocalInventoryCache;
import org.edtp.theexchange.storage.LocalInventoryCacheManager;
import org.edtp.theexchange.storage.LocalItemStore;
import org.edtp.theexchange.storage.OperationLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Realistic throughput benchmark: full production path minus network and DB I/O.
 *
 * Goes through:
 *   1. submit() with synchronized(taskMonitor) + inFlightTasks counting
 *   2. LocalItemStore → LocalInventoryCacheManager → LocalInventoryCache
 *   3. OperationLogger.log() (in-memory SQLite, no persistent file)
 *   4. CompatibilityChecker.checkAndMark()
 *   5. Slot-level ReentrantLock + StampedLock optimistic read
 *
 * Excludes: network, CacheManager (remote), async DB flush (separate writer thread)
 */
class ConcurrencyBenchmark {

    private static final int SLOTS = 54;
    private static final int MAX_STACK = 64;
    private static final int ITERS_PER_CALLER = 20000;

    // ---- submit harness (mimics TheExchangeCore.submit) ----

    private final Object taskMonitor = new Object();
    private volatile boolean acceptingTasks = true;
    private int inFlightTasks;

    private <T> CompletableFuture<T> submit(Callable<T> task, ExecutorService coreExecutor,
                                             AtomicLong gen) {
        CompletableFuture<T> future = new CompletableFuture<>();
        long taskGeneration = gen.get();
        synchronized (taskMonitor) {
            if (!acceptingTasks) {
                return CompletableFuture.failedFuture(new IllegalStateException("reloading"));
            }
            inFlightTasks++;
        }
        coreExecutor.execute(() -> {
            try {
                if (taskGeneration != gen.get()) {
                    future.completeExceptionally(new IllegalStateException("reloaded"));
                    return;
                }
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                synchronized (taskMonitor) {
                    if (inFlightTasks > 0) inFlightTasks--;
                    if (inFlightTasks == 0) taskMonitor.notifyAll();
                }
            }
        });
        return future;
    }

    // ---- stub ItemSerializer for compatibility check ----

    private static final ItemSerializer STUB_SERIALIZER = new ItemSerializer() {
        @Override public NeutralItem serialize(Object itemStack) { return null; }
        @Override public Object deserialize(NeutralItem item) { return null; }
        @Override public boolean canDeserialize(NeutralItem item) { return true; }
        @Override public int getMaxStackSize(NeutralItem item) { return 64; }
    };

    private static final CompatibilityChecker COMPAT = new CompatibilityChecker(STUB_SERIALIZER);

    private static NeutralItem diamond() {
        NeutralItem item = new NeutralItem("minecraft:diamond", 64, "diamond", new byte[0], false, "26.1.2");
        item.setVersion(1);
        return item;
    }

    private static NeutralItem diamondWithCount(int count) {
        NeutralItem item = new NeutralItem("minecraft:diamond", count, "diamond", new byte[0], false, "26.1.2");
        item.setVersion(1);
        return item;
    }

    @Tag("bench")
    @Test
    void run() throws Exception {
        int maxCores = Runtime.getRuntime().availableProcessors();
        int[] poolSizes = {1, 2, 4, 6, 8};
        Path csvFile = findProjectRoot().resolve("bench_data.csv");
        List<String> lines = new ArrayList<>();
        lines.add("scenario,core_threads,ops,elapsedMs,rate_s,successRate");
        System.out.println("[bench] cores=" + maxCores + "  csv=" + csvFile);

        for (int k : poolSizes) {
            if (k > maxCores) break;
            int callers = k * 2;
            System.out.println("[bench] --- core_threads=" + k + "  callers=" + callers + " ---");

            runScenario("dedicated", k, callers, lines, this::benchDedicated);
            runScenario("random",    k, callers, lines, this::benchRandom);
            runScenario("sameSlot",  k, callers, lines, this::benchSameSlot);
        }

        Files.write(csvFile, lines);
        System.out.println("[bench] wrote " + csvFile + " (" + lines.size() + " rows)");
    }

    @FunctionalInterface
    interface Scenario {
        long[] run(ExecutorService coreExecutor, AtomicLong gen,
                    LocalItemStore store, OperationLogger opLogger) throws Exception;
    }

    private void runScenario(String name, int coreThreads, int callers,
                              List<String> lines, Scenario scenario) throws Exception {
        Path tempDir = Files.createTempDirectory("exchange-bench-" + name + "-" + coreThreads + "-");
        OperationLogger opLogger = new OperationLogger(tempDir.resolve("oplog"));

        DatabaseManager db = new DatabaseManager(tempDir.resolve("data.db").toString());
        db.initialize();

        LocalItemStore store = new LocalItemStore(db);
        ExchangeAPI.Logger benchLogger = new ExchangeAPI.Logger() {
            @Override public void info(String msg) {}
            @Override public void warn(String msg) {}
            @Override public void error(String msg) {}
            @Override public void error(String msg, Throwable t) {}
        };
        LocalInventoryCacheManager cacheMgr = new LocalInventoryCacheManager(
                store, STUB_SERIALIZER, benchLogger, 256);
        store.setCacheManager(cacheMgr);

        // Pre-fill all slots
        for (int i = 0; i < SLOTS; i++) {
            store.putItem(InventoryScope.server(), i, diamond(), 0, "init");
        }

        ExecutorService coreExecutor = Executors.newFixedThreadPool(coreThreads);
        AtomicLong gen = new AtomicLong();

        long begin = System.nanoTime();
        long[] counts = scenario.run(coreExecutor, gen, store, opLogger);
        long elapsedMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));

        long ops = counts[0] + counts[1];
        double rate = ops * 1000.0 / elapsedMs;
        double sr = ops > 0 ? counts[0] * 100.0 / ops : 0;
        String line = String.format("%s,%d,%d,%d,%.0f,%.1f", name, coreThreads, ops, elapsedMs, rate, sr);
        lines.add(line);
        System.out.printf("[bench] %-10s  ops=%d  elapsed=%dms  rate=%,.0f/s  success=%.1f%%%n",
                name, ops, elapsedMs, rate, sr);

        coreExecutor.shutdownNow();
        cacheMgr.flushAll();
        db.close();
        deleteRecursively(tempDir);
    }

    // ---- caller harness ----

    private long[] runCallers(ExecutorService coreExecutor, AtomicLong gen,
                               LocalItemStore store, OperationLogger opLogger,
                               int callers, CallerTask task) throws Exception {
        AtomicLong success = new AtomicLong();
        AtomicLong fail = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        ExecutorService callerPool = Executors.newFixedThreadPool(callers);

        for (int t = 0; t < callers; t++) {
            final int id = t;
            callerPool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                task.run(id, coreExecutor, gen, store, opLogger, ITERS_PER_CALLER, success, fail);
                done.countDown();
            });
        }

        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS));
        callerPool.shutdownNow();
        return new long[]{success.get(), fail.get()};
    }

    @FunctionalInterface
    interface CallerTask {
        void run(int id, ExecutorService exec, AtomicLong gen,
                 LocalItemStore store, OperationLogger opLogger,
                 int iters, AtomicLong success, AtomicLong fail);
    }

    // ---- full-path PUT/TAKE helpers ----

    private void fullTake(LocalItemStore store, OperationLogger opLogger,
                           int slot, int version, AtomicLong success, AtomicLong fail) {
        String reqId = UUID.randomUUID().toString();
        var item = store.getItem(slot);
        if (item == null || item.item() == null || item.item().isEmpty()) {
            opLogger.log(reqId, OperationType.TAKE, "u", "n", "local", "minecraft:diamond", 1, false, "EMPTY");
            fail.incrementAndGet();
            return;
        }
        COMPAT.checkAndMark(item.item());
        if (item.item().isIncompatible()) {
            opLogger.log(reqId, OperationType.TAKE, "u", "n", "local", "minecraft:diamond", 1, false, "INCOMPATIBLE");
            fail.incrementAndGet();
            return;
        }
        var result = store.takeItem(slot, "minecraft:diamond", version, 1);
        if (result.isSuccess()) {
            opLogger.log(reqId, OperationType.TAKE, "u", "n", "local", "minecraft:diamond", 1, true, null);
            success.incrementAndGet();
        } else {
            opLogger.log(reqId, OperationType.TAKE, "u", "n", "local", "minecraft:diamond", 1, false, result.getFailReason());
            fail.incrementAndGet();
        }
    }

    private void fullPut(LocalItemStore store, OperationLogger opLogger,
                          int slot, int version, AtomicLong success, AtomicLong fail) {
        String reqId = UUID.randomUUID().toString();
        var cached = store.getItem(slot);
        if (cached != null && cached.item() != null && cached.item().isIncompatible()) {
            opLogger.log(reqId, OperationType.PUT, "u", "n", "local", "minecraft:diamond", 1, false, "INCOMPATIBLE");
            fail.incrementAndGet();
            return;
        }
        var putItem = diamondWithCount(1);
        COMPAT.checkAndMark(putItem);
        if (putItem.isIncompatible()) {
            opLogger.log(reqId, OperationType.PUT, "u", "n", "local", "minecraft:diamond", 1, false, "INCOMPATIBLE");
            fail.incrementAndGet();
            return;
        }
        var result = store.putItem(slot, putItem, version, "bench");
        if (result.isSuccess()) {
            opLogger.log(reqId, OperationType.PUT, "u", "n", "local", putItem.getItemId(), putItem.getCount(), true, null);
            success.incrementAndGet();
        } else {
            opLogger.log(reqId, OperationType.PUT, "u", "n", "local", putItem.getItemId(), putItem.getCount(), false, result.getFailReason());
            fail.incrementAndGet();
        }
    }

    // ---- scenarios ----

    private long[] benchDedicated(ExecutorService exec, AtomicLong gen,
                                   LocalItemStore store, OperationLogger opLogger) throws Exception {
        int callers = ((java.util.concurrent.ThreadPoolExecutor) exec).getCorePoolSize() * 2;
        return runCallers(exec, gen, store, opLogger, callers, (id, e, g, s, l, iters, ok, fail) -> {
            int slotA = (id * 2) % SLOTS;
            int slotB = (id * 2 + 1) % SLOTS;
            int va = 1, vb = 1;
            for (int i = 0; i < iters; i++) {
                int slot = (i % 2 == 0) ? slotA : slotB;
                int v = (i % 2 == 0) ? va : vb;
                try {
                    int finalV = v;
                    submit(() -> {
                        var before = s.getItem(slot);
                        fullTake(s, l, slot, before != null ? before.version() : finalV, ok, fail);
                        var after = s.getItem(slot);
                        fullPut(s, l, slot, after != null ? after.version() : 0, ok, fail);
                        return null;
                    }, e, g).get();
                    v = afterSuccessfulTakePut(s, slot);
                } catch (Exception ex) { fail.incrementAndGet(); }
                if (i % 2 == 0) va = v; else vb = v;
            }
        });
    }

    private int afterSuccessfulTakePut(LocalItemStore store, int slot) {
        var item = store.getItem(slot);
        return item != null ? item.version() : 0;
    }

    private long[] benchRandom(ExecutorService exec, AtomicLong gen,
                                LocalItemStore store, OperationLogger opLogger) throws Exception {
        int callers = ((java.util.concurrent.ThreadPoolExecutor) exec).getCorePoolSize() * 2;
        return runCallers(exec, gen, store, opLogger, callers, (id, e, g, s, l, iters, ok, fail) -> {
            java.util.Random rng = new java.util.Random(Thread.currentThread().getId());
            for (int i = 0; i < iters; i++) {
                int takeSlot = rng.nextInt(SLOTS);
                int putSlot = rng.nextInt(SLOTS);
                try {
                    submit(() -> {
                        var b = s.getItem(takeSlot);
                        fullTake(s, l, takeSlot, b != null ? b.version() : 0, ok, fail);
                        var p = s.getItem(putSlot);
                        fullPut(s, l, putSlot, p != null ? p.version() : 0, ok, fail);
                        return null;
                    }, e, g).get();
                } catch (Exception ex) { fail.incrementAndGet(); }
            }
        });
    }

    private long[] benchSameSlot(ExecutorService exec, AtomicLong gen,
                                  LocalItemStore store, OperationLogger opLogger) throws Exception {
        int callers = ((java.util.concurrent.ThreadPoolExecutor) exec).getCorePoolSize() * 2;
        return runCallers(exec, gen, store, opLogger, callers, (id, e, g, s, l, iters, ok, fail) -> {
            for (int i = 0; i < iters; i++) {
                try {
                    submit(() -> {
                        var b = s.getItem(0);
                        int v = b != null ? b.version() : 0;
                        fullTake(s, l, 0, v, ok, fail);
                        return null;
                    }, e, g).get();
                } catch (Exception ex) { fail.incrementAndGet(); }
            }
        });
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) { s.forEach(ConcurrencyBenchmark::deleteRecursively); }
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {}
    }

    private static Path findProjectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) throw new IllegalStateException("Cannot find project root");
        return dir;
    }
}
