package org.edtp.theexchange.network;

import org.edtp.theexchange.network.tls.TlsContext;
import org.edtp.theexchange.network.tls.PinnedPeerKeyStore;

import javax.net.ssl.SSLSocket;
import java.io.IOException;

public class TcpClient {

    private final TlsContext tlsContext;
    private final PinnedPeerKeyStore pinnedPeerKeyStore;

    public TcpClient(TlsContext tlsContext, PinnedPeerKeyStore pinnedPeerKeyStore) {
        this.tlsContext = tlsContext;
        this.pinnedPeerKeyStore = pinnedPeerKeyStore;
    }

    public Connection connect(String serverName, String address, int port) {
        SSLSocket socket = null;
        try {
            socket = (SSLSocket) tlsContext.getSocketFactory()
                    .createSocket(address, port);
            TlsContext.configureSocket(socket);
            socket.startHandshake();
            pinnedPeerKeyStore.verifyOrPin(serverName, socket);
            return new Connection(serverName, socket);
        } catch (Exception e) {
            System.err.println("[Exchange] Failed to connect to " + serverName
                    + " (" + address + ":" + port + "): " + e.getMessage());
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
