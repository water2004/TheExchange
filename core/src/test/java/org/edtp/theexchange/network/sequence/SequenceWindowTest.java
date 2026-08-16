package org.edtp.theexchange.network.sequence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequenceWindowTest {

    @Test
    void sequenceArithmeticDoesNotOverflowAtLongMaximum() {
        SequenceWindow window = new SequenceWindow();
        long now = System.currentTimeMillis();

        assertTrue(window.validate(Long.MAX_VALUE, now));
        assertTrue(window.validate(Long.MAX_VALUE - 1, now));
        assertFalse(window.validate(Long.MAX_VALUE, now));
    }
}
