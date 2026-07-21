package org.edtp.theexchange.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInventoryConnectionSpecTest {

    @Test
    void parsesConnectionWithOrWithoutPassword() {
        PlayerInventoryConnectionSpec complete =
                PlayerInventoryConnectionSpec.parse("Steve@survival:secret:with-colon");
        assertEquals("Steve", complete.playerName());
        assertEquals("survival", complete.serverName());
        assertEquals("secret:with-colon", complete.password().orElseThrow());

        PlayerInventoryConnectionSpec passwordless =
                PlayerInventoryConnectionSpec.parse(" Alex @ creative ");
        assertEquals("Alex", passwordless.playerName());
        assertEquals("creative", passwordless.serverName());
        assertTrue(passwordless.password().isEmpty());
    }

    @Test
    void rejectsMalformedOrUnboundedConnections() {
        assertThrows(IllegalArgumentException.class,
                () -> PlayerInventoryConnectionSpec.parse(null));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerInventoryConnectionSpec.parse("Steve"));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerInventoryConnectionSpec.parse("@survival"));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerInventoryConnectionSpec.parse("Steve@"));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerInventoryConnectionSpec.parse("Steve@@survival"));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerInventoryConnectionSpec.parse("x".repeat(65) + "@survival"));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerInventoryConnectionSpec.parse("Steve@survival:" + "x".repeat(257)));
    }

    @Test
    void redactedFormNeverContainsPassword() {
        PlayerInventoryConnectionSpec connection =
                PlayerInventoryConnectionSpec.parse("Steve@survival:top-secret");

        assertEquals("Steve@survival", connection.redacted());
        assertFalse(connection.toString().contains("top-secret"));
    }
}
