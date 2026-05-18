package org.edtp.theexchange.network.sequence;

import java.util.BitSet;

/**
 * Anti-replay sliding window for received sequence numbers.
 * Window size: 1024 sequences, with 60-second timestamp tolerance.
 */
public class SequenceWindow {

    private static final int WINDOW_SIZE = 1024;
    private long base;          // minimum acceptable sequence
    private final BitSet window;

    public SequenceWindow() {
        this.base = 0;
        this.window = new BitSet(WINDOW_SIZE);
    }

    /**
     * Validate and record a received sequence number.
     * @return true if the sequence is valid (not a replay), false if it should be discarded
     */
    public synchronized boolean validate(long sequence, long timestamp) {
        // Timestamp check: reject messages more than 60s out of sync
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > 60_000) {
            return false;
        }

        // Too old: reject
        if (sequence < base) {
            return false;
        }

        // Too far ahead: shift window forward
        if (sequence >= base + WINDOW_SIZE) {
            long shift = sequence - base - WINDOW_SIZE / 2;
            base += shift;
            // Shift bitset
            if (shift >= WINDOW_SIZE) {
                window.clear();
            } else {
                for (int i = 0; i < WINDOW_SIZE - shift; i++) {
                    window.set(i, window.get((int) (i + shift)));
                }
                for (int i = (int) (WINDOW_SIZE - shift); i < WINDOW_SIZE; i++) {
                    window.clear(i);
                }
            }
        }

        int index = (int) (sequence - base);
        if (index >= WINDOW_SIZE) {
            // Still out of range after shifting — accept but don't track
            return true;
        }

        // Check for replay
        if (window.get(index)) {
            return false; // Replay detected
        }

        window.set(index);
        return true;
    }

    public synchronized void reset() {
        base = 0;
        window.clear();
    }
}
