package org.edtp.theexchange.concurrency;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.storage.LocalInventoryCache;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Realistic throughput benchmark through submit → coreExecutor.
 *
 * Each scenario uses K core-threads + 2K callers. Every operation is
 * submitted via CompletableFuture.supplyAsync(task, coreExecutor).get(),
 * mimicking the production path: coreExecutor processes all requests, and
 * callers block on the future (simulating async completion handling).
 *
 * No network or DB I/O — measures the Java-level overhead of the submit path.
 *
 * Scenarios:
 *   dedicated — each caller pair owns exclusive slots, zero contention
 *   random    — callers pick random slots from shared pool of 54
 *   sameSlot  — all callers target slot 0, max contention
 */
class ConcurrencyBenchmark {

    private static final int SLOTS = 54;
    private static final int MAX_STACK = 64;
    private static final int ITERS_PER_CALLER = 5000;

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

    private static LocalInventoryCache newCache() {
        LocalInventoryCache cache = new LocalInventoryCache(InventoryScope.server(), SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            cache.put(i, diamond(), 0, "init", item -> MAX_STACK);
        }
        return cache;
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

            runPooled("dedicated", k, callers, lines, (exec, cache) ->
                pooledDedicated(exec, cache, callers));
            runPooled("random",    k, callers, lines, (exec, cache) ->
                pooledRandom(exec, cache, callers));
            runPooled("sameSlot",  k, callers, lines, (exec, cache) ->
                pooledSameSlot(exec, cache, callers));
        }

        Files.write(csvFile, lines);
        System.out.println("[bench] wrote " + csvFile + " (" + lines.size() + " rows)");
    }

    @FunctionalInterface
    interface PooledScenario {
        long[] run(ExecutorService coreExecutor, LocalInventoryCache cache) throws Exception;
    }

    private void runPooled(String name, int coreThreads, int callers,
                           List<String> lines, PooledScenario scenario) throws Exception {
        ExecutorService coreExecutor = Executors.newFixedThreadPool(coreThreads);
        LocalInventoryCache cache = newCache();
        long begin = System.nanoTime();
        long[] counts = scenario.run(coreExecutor, cache);
        long elapsedMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
        long ops = counts[0] + counts[1];
        double rate = ops * 1000.0 / elapsedMs;
        double sr = ops > 0 ? counts[0] * 100.0 / ops : 0;
        String line = String.format("%s,%d,%d,%d,%.0f,%.1f", name, coreThreads, ops, elapsedMs, rate, sr);
        lines.add(line);
        System.out.printf("[bench] %-10s  ops=%d  elapsed=%dms  rate=%,.0f/s  success=%.1f%%%n",
                name, ops, elapsedMs, rate, sr);
        coreExecutor.shutdownNow();
    }

    // ---- shared harness ----

    private long[] runCallers(ExecutorService coreExecutor, LocalInventoryCache cache,
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
                task.run(id, coreExecutor, cache, ITERS_PER_CALLER, success, fail);
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
        void run(int id, ExecutorService exec, LocalInventoryCache cache,
                 int iters, AtomicLong success, AtomicLong fail);
    }

    // ---- scenarios ----

    private long[] pooledDedicated(ExecutorService exec, LocalInventoryCache cache,
                                    int callers) throws Exception {
        return runCallers(exec, cache, callers, (id, e, c, iters, s, f) -> {
            int slotA = (id * 2) % SLOTS;
            int slotB = (id * 2 + 1) % SLOTS;
            int va = 1, vb = 1;
            for (int i = 0; i < iters; i++) {
                int slot = (i % 2 == 0) ? slotA : slotB;
                int v = (i % 2 == 0) ? va : vb;
                var r = supplyTake(e, c, slot, v);
                if (r.success()) { s.incrementAndGet(); v = r.newVersion(); }
                else { f.incrementAndGet(); v = readVersion(e, c, slot); }
                r = supplyPut(e, c, slot, v);
                if (r.success()) { s.incrementAndGet(); v = r.newVersion(); }
                else { f.incrementAndGet(); v = readVersion(e, c, slot); }
                if (i % 2 == 0) va = v; else vb = v;
            }
        });
    }

    private long[] pooledRandom(ExecutorService exec, LocalInventoryCache cache,
                                 int callers) throws Exception {
        return runCallers(exec, cache, callers, (id, e, c, iters, s, f) -> {
            java.util.Random rng = new java.util.Random(Thread.currentThread().getId());
            for (int i = 0; i < iters; i++) {
                int slot = rng.nextInt(SLOTS);
                int v = readVersion(e, c, slot);
                var r = supplyTake(e, c, slot, v);
                if (r.success()) s.incrementAndGet(); else f.incrementAndGet();
                slot = rng.nextInt(SLOTS);
                v = readVersion(e, c, slot);
                r = supplyPut(e, c, slot, v);
                if (r.success()) s.incrementAndGet(); else f.incrementAndGet();
            }
        });
    }

    private long[] pooledSameSlot(ExecutorService exec, LocalInventoryCache cache,
                                   int callers) throws Exception {
        return runCallers(exec, cache, callers, (id, e, c, iters, s, f) -> {
            for (int i = 0; i < iters; i++) {
                int v = readVersion(e, c, 0);
                var r = supplyTake(e, c, 0, v);
                if (r.success()) s.incrementAndGet(); else f.incrementAndGet();
            }
        });
    }

    // ---- submit helpers (mimic TheExchangeCore.submit) ----

    private static LocalInventoryCache.Result supplyTake(ExecutorService exec,
            LocalInventoryCache cache, int slot, int version) {
        try {
            return CompletableFuture.supplyAsync(
                () -> cache.take(slot, "minecraft:diamond", version, 1), exec).get();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static LocalInventoryCache.Result supplyPut(ExecutorService exec,
            LocalInventoryCache cache, int slot, int version) {
        try {
            return CompletableFuture.supplyAsync(
                () -> cache.put(slot, diamondWithCount(1), version, "test", item -> MAX_STACK), exec).get();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static int readVersion(ExecutorService exec, LocalInventoryCache cache, int slot) {
        try {
            return CompletableFuture.supplyAsync(() -> cache.getVersion(slot), exec).get();
        } catch (Exception e) { throw new RuntimeException(e); }
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
