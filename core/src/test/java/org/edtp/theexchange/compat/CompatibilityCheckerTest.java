package org.edtp.theexchange.compat;

import org.edtp.theexchange.model.NeutralItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityCheckerTest {

    @Test
    void clearsIncompatibleWhenDeserializationSucceeds() {
        ItemSerializer serializer = new ItemSerializer() {
            @Override
            public NeutralItem serialize(Object mcItemStack) {
                return null;
            }

            @Override
            public Object deserialize(NeutralItem neutralItem) {
                return new Object();
            }

            @Override
            public boolean canDeserialize(NeutralItem item) {
                return true;
            }

            @Override
            public boolean sameStackKind(NeutralItem a, NeutralItem b) {
                return false;
            }

            @Override
            public int getMaxStackSize(NeutralItem item) {
                return 64;
            }
        };

        CompatibilityChecker checker = new CompatibilityChecker(serializer);
        NeutralItem item = new NeutralItem("minecraft:barrier", 32, "barrier", new byte[0], true, "26.1.2");

        checker.checkAndMark(item);

        assertFalse(item.isIncompatible());
    }
}
