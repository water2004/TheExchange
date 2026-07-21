package org.edtp.theexchange.service;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A non-blocking, per-endpoint gate for asynchronous warehouse automation.
 *
 * <p>Callers either acquire a lease immediately or reject the operation. A
 * lease remains active until the asynchronous result has been applied on the
 * owning thread; there is deliberately no waiting or request queue.</p>
 */
public final class WarehouseAutomationGate<K> {
    private final ConcurrentHashMap<K, Lease<K>> active = new ConcurrentHashMap<>();

    public Optional<Lease<K>> tryAcquire(K key) {
        K normalizedKey = Objects.requireNonNull(key, "key");
        Lease<K> candidate = new Lease<>(this, normalizedKey);
        Lease<K> existing = active.putIfAbsent(normalizedKey, candidate);
        return existing == null ? Optional.of(candidate) : Optional.empty();
    }

    public boolean isBusy(K key) {
        return key != null && active.containsKey(key);
    }

    public void clear() {
        active.clear();
    }

    private void release(Lease<K> lease) {
        active.remove(lease.key, lease);
    }

    public static final class Lease<K> implements AutoCloseable {
        private final WarehouseAutomationGate<K> owner;
        private final K key;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(WarehouseAutomationGate<K> owner, K key) {
            this.owner = owner;
            this.key = key;
        }

        public K key() {
            return key;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(this);
            }
        }
    }
}
