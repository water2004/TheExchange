package org.edtp.theexchange.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MenuOperationGateTest {

    @Test
    void allowsOperationsWithDifferentSourcesAndDestinations() {
        MenuOperationGate<String> gate = new MenuOperationGate<>();
        Object first = new Object();
        Object second = new Object();

        assertTrue(gate.tryAcquire(first, Set.of("local:54", "remote:1")));
        assertTrue(gate.tryAcquire(second, Set.of("local:55", "remote:2")));
        assertEquals(2, gate.activeCount());
    }

    @Test
    void rejectsEitherSharedSourceOrSharedDestinationUntilRelease() {
        MenuOperationGate<String> gate = new MenuOperationGate<>();
        Object first = new Object();
        assertTrue(gate.tryAcquire(first, Set.of("local:54", "remote:1")));

        assertFalse(gate.tryAcquire(new Object(), Set.of("local:54", "remote:2")));
        assertFalse(gate.tryAcquire(new Object(), Set.of("local:55", "remote:1")));
        assertTrue(gate.conflicts(Set.of("remote:1")));

        assertTrue(gate.release(first));
        assertFalse(gate.isActive(first));
        assertTrue(gate.tryAcquire(new Object(), Set.of("local:54", "remote:1")));
    }
}
