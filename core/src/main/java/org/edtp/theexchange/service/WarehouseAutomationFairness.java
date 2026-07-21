package org.edtp.theexchange.service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Small process-local fairness state for independent asynchronous hoppers.
 *
 * <p>It does not limit warehouse-wide concurrency or queue operations. Each
 * hopper owns only a rotating slot cursor and its last started direction.</p>
 */
public final class WarehouseAutomationFairness<K> {
    private static final int DEFAULT_CLEANUP_INTERVAL = 256;

    private final int slotCount;
    private final LongSupplier clock;
    private final long idleTtlMillis;
    private final int cleanupInterval;
    private final ConcurrentHashMap<K, HopperState> states = new ConcurrentHashMap<>();
    private final AtomicInteger nextInitialSlot = new AtomicInteger();
    private final AtomicInteger accesses = new AtomicInteger();

    public WarehouseAutomationFairness(int slotCount) {
        this(slotCount, System::currentTimeMillis, Duration.ofMinutes(10));
    }

    WarehouseAutomationFairness(int slotCount, LongSupplier clock, Duration idleTtl) {
        this(slotCount, clock, idleTtl, DEFAULT_CLEANUP_INTERVAL);
    }

    WarehouseAutomationFairness(int slotCount, LongSupplier clock,
                                Duration idleTtl, int cleanupInterval) {
        if (slotCount <= 0) throw new IllegalArgumentException("slotCount must be positive");
        this.slotCount = slotCount;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idleTtlMillis = requirePositive(idleTtl, "idleTtl");
        if (cleanupInterval <= 0) {
            throw new IllegalArgumentException("cleanupInterval must be positive");
        }
        this.cleanupInterval = cleanupInterval;
    }

    /** Returns a circular scan start and advances this hopper even if its operation later conflicts. */
    public int claimSlotStart(K hopperKey) {
        long now = clock.getAsLong();
        HopperState state = state(hopperKey, now);
        int result;
        synchronized (state) {
            result = state.nextSlot;
            state.nextSlot = (state.nextSlot + state.stride) % slotCount;
            state.lastTouched = now;
        }
        cleanupIfDue(now);
        return result;
    }

    /**
     * Returns true when the same direction ran last and the opposite direction
     * is currently able to make an attempt. The supplier is evaluated lazily.
     */
    public boolean shouldYield(K hopperKey, Direction requested,
                               BooleanSupplier otherDirectionAvailable) {
        Objects.requireNonNull(hopperKey, "hopperKey");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(otherDirectionAvailable, "otherDirectionAvailable");
        HopperState state = states.get(hopperKey);
        if (state == null) return false;
        state.lastTouched = clock.getAsLong();
        return state.lastDirection == requested && otherDirectionAvailable.getAsBoolean();
    }

    public void recordStarted(K hopperKey, Direction direction) {
        long now = clock.getAsLong();
        HopperState state = state(hopperKey, now);
        state.lastDirection = Objects.requireNonNull(direction, "direction");
        state.lastTouched = now;
        cleanupIfDue(now);
    }

    int trackedHoppers() {
        return states.size();
    }

    boolean isTracked(K hopperKey) {
        return states.containsKey(hopperKey);
    }

    private HopperState state(K hopperKey, long now) {
        K key = Objects.requireNonNull(hopperKey, "hopperKey");
        return states.computeIfAbsent(key, ignored -> {
            int initial = Math.floorMod(nextInitialSlot.getAndIncrement(), slotCount);
            return new HopperState(initial, coprimeStride(key.hashCode()), now);
        });
    }

    private int coprimeStride(int hash) {
        if (slotCount == 1) return 1;
        int candidate = Math.floorMod(spread(hash), slotCount - 1) + 1;
        while (greatestCommonDivisor(candidate, slotCount) != 1) {
            candidate = candidate % (slotCount - 1) + 1;
        }
        return candidate;
    }

    private void cleanupIfDue(long now) {
        if (Math.floorMod(accesses.incrementAndGet(), cleanupInterval) != 0) return;
        long cutoff = now - idleTtlMillis;
        states.entrySet().removeIf(entry -> entry.getValue().lastTouched < cutoff);
    }

    private static int spread(int value) {
        return value ^ (value >>> 16);
    }

    private static int greatestCommonDivisor(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    private static long requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        long millis = duration.toMillis();
        if (millis <= 0) throw new IllegalArgumentException(name + " must be positive");
        return millis;
    }

    public enum Direction {
        PUSH,
        PULL
    }

    private static final class HopperState {
        private int nextSlot;
        private final int stride;
        private volatile Direction lastDirection;
        private volatile long lastTouched;

        private HopperState(int nextSlot, int stride, long lastTouched) {
            this.nextSlot = nextSlot;
            this.stride = stride;
            this.lastTouched = lastTouched;
        }
    }
}
