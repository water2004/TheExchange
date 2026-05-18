package org.edtp.theexchange.network;

import org.edtp.theexchange.network.tls.TlsContext;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Accepts inbound TLS connections from remote Exchange servers.
 */
public class TcpServer {

    private final int port;
    private final TlsContext tlsContext;
    private final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<>();
    private Thread acceptThread;
    private SSLServerSocket serverSocket;
    private volatile boolean running;
    private Consumer<Connection> connectionHandler;

    public TcpServer(int port, TlsContext tlsContext) {
        this.port = port;
        this.tlsContext = tlsContext;
    }

    public void start(Consumer<Connection> handler) {
        this.connectionHandler = handler;
        try {
            serverSocket = (SSLServerSocket) tlsContext.getServerSocketFactory()
                    .createServerSocket(port);
            serverSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
            serverSocket.setNeedClientAuth(false); // Trust via password auth, not client cert
            serverSocket.setReuseAddress(true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Exchange server on port " + port, e);
        }

        running = true;
        acceptThread = new Thread(this::acceptLoop, "exchange-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                TlsContext.configureSocket(socket);

                Connection conn = new Connection(socket.getInetAddress().getHostAddress(),
                        socket);
                connections.add(conn);
                if (connectionHandler != null) {
                    connectionHandler.accept(conn);
                }
            } catch (IOException e) {
                if (running) {
                    // Log but continue
                    System.err.println("[Exchange] Accept error: " + e.getMessage());
                }
            }
        }
    }

    public int getPort() {
        return port;
    }

    public int getConnectionCount() {
        return connections.size();
    }

    public void removeConnection(Connection conn) {
        connections.remove(conn);
    }

    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        for (Connection conn : connections) {
            conn.close();
        }
        connections.clear();
    }
}
