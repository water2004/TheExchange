package org.edtp.theexchange.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WarehouseAutomationFairnessTest {

    @Test
    void givesActiveHoppersDifferentInitialSlotStarts() {
        WarehouseAutomationFairness<String> fairness = new WarehouseAutomationFairness<>(
                54, System::currentTimeMillis, Duration.ofMinutes(10));
        Set<Integer> starts = new HashSet<>();

        for (int hopper = 0; hopper < 54; hopper++) {
            starts.add(fairness.claimSlotStart("hopper-" + hopper));
        }

        assertEquals(54, starts.size());
    }

    @Test
    void visitsEverySlotBeforeRepeatingEvenAfterConflicts() {
        WarehouseAutomationFairness<String> fairness = new WarehouseAutomationFairness<>(
                54, System::currentTimeMillis, Duration.ofMinutes(10));
        Set<Integer> visited = new HashSet<>();
        int first = fairness.claimSlotStart("hopper-a");
        visited.add(first);

        for (int attempt = 1; attempt < 54; attempt++) {
            visited.add(fairness.claimSlotStart("hopper-a"));
        }

        assertEquals(54, visited.size());
        assertEquals(first, fairness.claimSlotStart("hopper-a"));
    }

    @Test
    void alternatesDirectionsOnlyWhenTheOtherDirectionCanWork() {
        WarehouseAutomationFairness<String> fairness = new WarehouseAutomationFairness<>(
                54, System::currentTimeMillis, Duration.ofMinutes(10));

        assertFalse(fairness.shouldYield(
                "hopper-a", WarehouseAutomationFairness.Direction.PUSH, () -> true));
        fairness.recordStarted("hopper-a", WarehouseAutomationFairness.Direction.PUSH);

        assertTrue(fairness.shouldYield(
                "hopper-a", WarehouseAutomationFairness.Direction.PUSH, () -> true));
        assertFalse(fairness.shouldYield(
                "hopper-a", WarehouseAutomationFairness.Direction.PUSH, () -> false));
        assertFalse(fairness.shouldYield(
                "hopper-a", WarehouseAutomationFairness.Direction.PULL, () -> true));

        fairness.recordStarted("hopper-a", WarehouseAutomationFairness.Direction.PULL);
        assertTrue(fairness.shouldYield(
                "hopper-a", WarehouseAutomationFairness.Direction.PULL, () -> true));
        assertFalse(fairness.shouldYield(
                "hopper-a", WarehouseAutomationFairness.Direction.PUSH, () -> true));
    }

    @Test
    void removesIdleHopperStateWithoutScanningOnEveryAttempt() {
        AtomicLong clock = new AtomicLong(1_000L);
        WarehouseAutomationFairness<String> fairness = new WarehouseAutomationFairness<>(
                54, clock::get, Duration.ofSeconds(10), 2);

        fairness.claimSlotStart("old-a");
        fairness.claimSlotStart("old-b");
        assertEquals(2, fairness.trackedHoppers());

        clock.addAndGet(11_000L);
        fairness.claimSlotStart("new-a");
        fairness.claimSlotStart("new-b");

        assertEquals(2, fairness.trackedHoppers());
        assertTrue(fairness.isTracked("new-a"));
        assertTrue(fairness.isTracked("new-b"));
    }
}
