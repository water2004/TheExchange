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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone throughput benchmark — writes bench_data.csv for external plotting.
 * Not a correctness test; deliberately excluded from CI via the bench tag.
 *
 * Scenarios:
 *   dedicated — each thread owns exclusive slots, zero contention
 *   random    — threads pick random slots from shared pool of 54
 *   sameSlot  — all threads hammer slot 0, max contention
 */
class ConcurrencyBenchmark {

    private static final int SLOTS = 54;
    private static final int MAX_STACK = 64;

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
        int[] tcs = {1, 2, 4, 6, 8, 10, 12, 14};
        List<Integer> threadCounts = new ArrayList<>();
        for (int t : tcs) if (t <= maxCores) threadCounts.add(t);
        int itersPerThread = 50000;

        // ---- Find project root ----
        Path csvFile = findProjectRoot().resolve("bench_data.csv");
        List<String> lines = new ArrayList<>();
        lines.add("scenario,threads,ops,elapsedMs,rate,successRate");

        System.out.println("[bench] cores=" + maxCores + "  csv=" + csvFile);

        for (int threads : threadCounts) {
            runScenario("dedicated", threads, itersPerThread, (cache) ->
                benchmarkDedicated(cache, threads, itersPerThread), lines);
            runScenario("random", threads, itersPerThread, (cache) ->
                benchmarkRandom(cache, threads, itersPerThread), lines);
            runScenario("sameSlot", threads, itersPerThread, (cache) ->
                benchmarkSameSlot(cache, threads, itersPerThread), lines);
        }

        Files.write(csvFile, lines);
        System.out.println("[bench] wrote " + csvFile + " (" + lines.size() + " rows)");
    }

    @FunctionalInterface
    interface Scenario {
        long[] run(LocalInventoryCache cache) throws Exception;
        // returns [success, fail]
    }

    private void runScenario(String name, int threads, int itersPerThread,
                            Scenario scenario, List<String> lines) throws Exception {
        LocalInventoryCache cache = newCache();
        long begin = System.nanoTime();
        long[] counts = scenario.run(cache);
        long elapsedMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
        long ops = counts[0] + counts[1];
        double rate = ops * 1000.0 / elapsedMs;
        double successRate = ops > 0 ? counts[0] * 100.0 / ops : 0;
        String line = String.format("%s,%d,%d,%d,%.0f,%.1f",
                name, threads, ops, elapsedMs, rate, successRate);
        lines.add(line);
        System.out.println("[bench] " + line);
    }

    private long[] benchmarkDedicated(LocalInventoryCache cache, int threads,
                                       int itersPerThread) throws Exception {
        AtomicLong success = new AtomicLong();
        AtomicLong fail = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int slotA = (t * 2) % SLOTS;
            final int slotB = (t * 2 + 1) % SLOTS;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                int va = 1, vb = 1;
                for (int i = 0; i < itersPerThread; i++) {
                    int slot = (i % 2 == 0) ? slotA : slotB;
                    int v = (i % 2 == 0) ? va : vb;
                    var r = cache.take(slot, "minecraft:diamond", v, 1);
                    if (r.success()) { success.incrementAndGet(); v = r.newVersion(); }
                    else { fail.incrementAndGet(); v = cache.getVersion(slot); }
                    r = cache.put(slot, diamondWithCount(1), v, "test", item -> MAX_STACK);
                    if (r.success()) { success.incrementAndGet(); v = r.newVersion(); }
                    else { fail.incrementAndGet(); v = cache.getVersion(slot); }
                    if (i % 2 == 0) va = v; else vb = v;
                }
                done.countDown();
            });
        }

        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS));
        pool.shutdownNow();
        return new long[]{success.get(), fail.get()};
    }

    private long[] benchmarkRandom(LocalInventoryCache cache, int threads,
                                    int itersPerThread) throws Exception {
        AtomicLong success = new AtomicLong();
        AtomicLong fail = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                java.util.Random rng = new java.util.Random(Thread.currentThread().getId());
                for (int i = 0; i < itersPerThread; i++) {
                    int s = rng.nextInt(SLOTS);
                    int v = cache.getVersion(s);
                    var r = cache.take(s, "minecraft:diamond", v, 1);
                    if (r.success()) success.incrementAndGet(); else fail.incrementAndGet();
                    s = rng.nextInt(SLOTS);
                    v = cache.getVersion(s);
                    r = cache.put(s, diamondWithCount(1), v, "test", item -> MAX_STACK);
                    if (r.success()) success.incrementAndGet(); else fail.incrementAndGet();
                }
                done.countDown();
            });
        }

        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS));
        pool.shutdownNow();
        return new long[]{success.get(), fail.get()};
    }

    private long[] benchmarkSameSlot(LocalInventoryCache cache, int threads,
                                      int itersPerThread) throws Exception {
        AtomicLong success = new AtomicLong();
        AtomicLong fail = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < itersPerThread; i++) {
                    int v = cache.getVersion(0);
                    var r = cache.take(0, "minecraft:diamond", v, 1);
                    if (r.success()) success.incrementAndGet(); else fail.incrementAndGet();
                }
                done.countDown();
            });
        }

        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS));
        pool.shutdownNow();
        return new long[]{success.get(), fail.get()};
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
