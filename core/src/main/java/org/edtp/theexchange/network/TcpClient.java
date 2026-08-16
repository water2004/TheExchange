package org.edtp.theexchange.network;

import org.edtp.theexchange.network.tls.TlsContext;
import org.edtp.theexchange.network.tls.PinnedPeerKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpClient.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final TlsContext tlsContext;
    private final PinnedPeerKeyStore pinnedPeerKeyStore;

    public TcpClient(TlsContext tlsContext, PinnedPeerKeyStore pinnedPeerKeyStore) {
        this.tlsContext = tlsContext;
        this.pinnedPeerKeyStore = pinnedPeerKeyStore;
    }

    public Connection connect(String serverName, String address, int port) {
        SSLSocket socket = null;
        try {
            Socket rawSocket = new Socket();
            rawSocket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            socket = (SSLSocket) tlsContext.getSocketFactory()
                    .createSocket(rawSocket, address, port, true);
            TlsContext.configureSocket(socket);
            socket.startHandshake();
            pinnedPeerKeyStore.verifyOrPin(serverName, socket);
            return new Connection(serverName, socket);
        } catch (Exception e) {
            LOGGER.warn("Failed to connect to {} ({}:{}): {}",
                    serverName, address, port, e.getMessage());
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            return null;
        }
    }
}
