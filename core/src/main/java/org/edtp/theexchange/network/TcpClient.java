package org.edtp.theexchange.network;

import org.edtp.theexchange.network.tls.TlsContext;

import javax.net.ssl.SSLSocket;

public class TcpClient {

    private final TlsContext tlsContext;

    public TcpClient(TlsContext tlsContext) {
        this.tlsContext = tlsContext;
    }

    public Connection connect(String serverName, String address, int port) {
        try {
            SSLSocket socket = (SSLSocket) tlsContext.getSocketFactory()
                    .createSocket(address, port);
            TlsContext.configureSocket(socket);
            socket.startHandshake();
            return new Connection(serverName, socket);
        } catch (Exception e) {
            System.err.println("[Exchange] Failed to connect to " + serverName
                    + " (" + address + ":" + port + "): " + e.getMessage());
            return null;
        }
    }
}
