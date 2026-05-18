package org.edtp.theexchange.network;

import org.edtp.theexchange.network.tls.TlsContext;

import javax.net.ssl.SSLSocket;
import java.io.IOException;

/**
 * Initiates an outbound TLS connection to a remote Exchange server.
 */
public class TcpClient {

    private final TlsContext tlsContext;

    public TcpClient(TlsContext tlsContext) {
        this.tlsContext = tlsContext;
    }

    /**
     * Connect to a remote server.
     * @param serverName Logical name of the remote server
     * @param address IP or hostname
     * @param port TCP port
     * @return A Connection if successful, null on failure
     */
    public Connection connect(String serverName, String address, int port) {
        try {
            SSLSocket socket = (SSLSocket) tlsContext.getSocketFactory()
                    .createSocket(address, port);
            TlsContext.configureSocket(socket);
            socket.startHandshake();

            return new Connection(serverName, socket);
        } catch (IOException e) {
            return null;
        }
    }
}
