package org.edtp.theexchange.network.tls;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsContextSocketConfigurationTest {

    @Test
    void disablesNagleForLatencySensitiveTransactionFrames() throws Exception {
        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            assertFalse(socket.getTcpNoDelay(), "the JDK socket default should keep Nagle enabled");

            TlsContext.configureSocket(socket);

            assertTrue(socket.getTcpNoDelay(),
                    "Exchange transaction frames must not wait for Nagle/delayed-ACK batching");
        }
    }
}
