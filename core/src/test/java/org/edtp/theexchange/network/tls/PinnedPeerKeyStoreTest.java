package org.edtp.theexchange.network.tls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedPeerKeyStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void pinsOnFirstUseAndAcceptsSameKey() {
        Path pinFile = tempDir.resolve("known-peers.properties");
        PinnedPeerKeyStore store = new PinnedPeerKeyStore(pinFile);

        byte[] publicKey = Base64.getDecoder().decode("MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBALB7Qm7J8e8m9fQ3z2Wn8v9X7B0d+0pP9n0U8vNQ8o4M1K1cXoQY2sZsY4rK8XQJc3q0mK6kFJd5QYQ7G2xk7Y0CAwEAAQ==");

        assertDoesNotThrow(() -> store.verifyOrPin("remote-a", publicKey));
        assertTrue(Files.exists(pinFile));
        assertDoesNotThrow(() -> store.verifyOrPin("remote-a", publicKey));
    }

    @Test
    void rejectsDifferentKeyForSameServer() {
        Path pinFile = tempDir.resolve("known-peers.properties");
        PinnedPeerKeyStore store = new PinnedPeerKeyStore(pinFile);

        byte[] first = new byte[] {1, 2, 3, 4};
        byte[] second = new byte[] {4, 3, 2, 1};

        assertDoesNotThrow(() -> store.verifyOrPin("remote-b", first));
        assertThrows(Exception.class, () -> store.verifyOrPin("remote-b", second));
    }
}
