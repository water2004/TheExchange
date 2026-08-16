package org.edtp.theexchange.network;

import org.edtp.theexchange.network.tls.PinnedPeerKeyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;

class NetworkManagerAuthFailureTest {
    @TempDir
    Path tempDir;

    @Test
    void recordingANewFailureReclaimsExpiredPartialFailureEntries() throws Exception {
        NetworkManager manager = new NetworkManager(0, tempDir.resolve("server.p12"),
                new PinnedPeerKeyStore(tempDir.resolve("pins.properties")),
                "test", "changeit".toCharArray(), "test");
        try {
            Method recordFailure = NetworkManager.class.getDeclaredMethod("recordAuthFailure", String.class);
            recordFailure.setAccessible(true);
            Field failuresField = NetworkManager.class.getDeclaredField("authFailures");
            failuresField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Object> failures =
                    (ConcurrentHashMap<String, Object>) failuresField.get(manager);

            Class<?> failureType = Class.forName(NetworkManager.class.getName() + "$AuthFailure");
            Constructor<?> constructor = failureType.getDeclaredConstructor(int.class, long.class);
            constructor.setAccessible(true);
            failures.put("expired-peer", constructor.newInstance(1,
                    System.currentTimeMillis() - 60_000L));

            recordFailure.invoke(manager, "new-peer");

            assertFalse(failures.containsKey("expired-peer"),
                    "one-off authentication failures must not remain forever for every source address");
        } finally {
            manager.shutdown();
        }
    }
}
