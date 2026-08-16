package org.edtp.theexchange.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-menu conflict gate. Operations may overlap in time when their claimed
 * local and remote resources are disjoint.
 */
public final class MenuOperationGate<R> {

    private final Map<Object, Set<R>> active = new IdentityHashMap<>();
    private final Set<R> claimed = new HashSet<>();

    public synchronized boolean tryAcquire(Object operation, Collection<R> resources) {
        Objects.requireNonNull(operation, "operation");
        if (active.containsKey(operation)) {
            throw new IllegalArgumentException("Operation is already active");
        }
        Set<R> requested = resources != null ? Set.copyOf(resources) : Set.of();
        if (!java.util.Collections.disjoint(claimed, requested)) {
            return false;
        }
        active.put(operation, requested);
        claimed.addAll(requested);
        return true;
    }

    public synchronized boolean conflicts(Collection<R> resources) {
        if (resources == null || resources.isEmpty() || claimed.isEmpty()) {
            return false;
        }
        for (R resource : resources) {
            if (claimed.contains(resource)) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isActive(Object operation) {
        return active.containsKey(operation);
    }

    public synchronized boolean release(Object operation) {
        Set<R> resources = active.remove(operation);
        if (resources == null) {
            return false;
        }
        claimed.removeAll(resources);
        return true;
    }

    public synchronized int activeCount() {
        return active.size();
    }
}
