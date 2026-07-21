package org.edtp.theexchange.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WarehouseAutomationGateTest {

    @Test
    void rejectsEverySecondOperationForTheSameHopperUntilCompletion() {
        WarehouseAutomationGate<String> gate = new WarehouseAutomationGate<>();

        WarehouseAutomationGate.Lease<String> first = gate.tryAcquire("hopper-a").orElseThrow();

        assertTrue(gate.isBusy("hopper-a"));
        assertTrue(gate.tryAcquire("hopper-a").isEmpty(),
                "a pull must be rejected while a push for the same hopper is in flight");

        first.close();

        assertFalse(gate.isBusy("hopper-a"));
        assertTrue(gate.tryAcquire("hopper-a").isPresent());
    }

    @Test
    void differentHoppersRemainIndependent() {
        WarehouseAutomationGate<String> gate = new WarehouseAutomationGate<>();

        try (WarehouseAutomationGate.Lease<String> ignored = gate.tryAcquire("hopper-a").orElseThrow()) {
            assertTrue(gate.tryAcquire("hopper-b").isPresent());
        }
    }

    @Test
    void releaseIsIdempotentAndCannotReleaseANewerLease() {
        WarehouseAutomationGate<String> gate = new WarehouseAutomationGate<>();
        WarehouseAutomationGate.Lease<String> first = gate.tryAcquire("hopper-a").orElseThrow();

        first.close();
        WarehouseAutomationGate.Lease<String> second = gate.tryAcquire("hopper-a").orElseThrow();
        first.close();

        assertTrue(gate.isBusy("hopper-a"));
        second.close();
        assertFalse(gate.isBusy("hopper-a"));
    }

    @Test
    void concurrentRequestsHaveExactlyOneWinner() throws Exception {
        WarehouseAutomationGate<String> gate = new WarehouseAutomationGate<>();
        int contenders = 16;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contenders);
        AtomicInteger winners = new AtomicInteger();

        try {
            for (int i = 0; i < contenders; i++) {
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        Optional<WarehouseAutomationGate.Lease<String>> lease = gate.tryAcquire("hopper-a");
                        if (lease.isPresent()) winners.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, winners.get());
    }
}
