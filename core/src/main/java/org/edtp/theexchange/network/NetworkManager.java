package org.edtp.theexchange.network;

import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.model.ServerStatus;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.network.tls.TlsContext;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Central manager for all network operations.
 * Owns the TcpServer (inbound) and all TcpClient connections (outbound).
 */
public class NetworkManager {

    private final TlsContext tlsContext;
    private final TcpServer tcpServer;
    private final TcpClient tcpClient;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerStatus> serverStatus = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BiConsumer<String, ServerStatus>> statusListeners = new CopyOnWriteArrayList<>();

    private String localPassword;
    private Consumer<Connection> inboundConnectionHandler;
    private BiConsumer<FrameType, Object> messageRouter;

    public NetworkManager(int localPort, Path keystorePath, String cn, char[] keystorePassword) {
        this.tlsContext = TlsContext.create(keystorePath, cn, keystorePassword);
        this.tcpServer = new TcpServer(localPort, tlsContext);
        this.tcpClient = new TcpClient(tlsContext);
    }

    public void setLocalPassword(String password) {
        this.localPassword = password;
    }

    public String getLocalPassword() {
        return localPassword;
    }

    public void setMessageRouter(BiConsumer<FrameType, Object> router) {
        this.messageRouter = router;
    }

    public void setInboundConnectionHandler(Consumer<Connection> handler) {
        this.inboundConnectionHandler = handler;
    }

    public void start() {
        tcpServer.start(conn -> {
            // Inbound connection: start reader, authentication expected
            conn.start((type, msg) -> {
                if (type == FrameType.AUTH_REQUEST) {
                    handleInboundAuth(conn, (AuthRequest) msg);
                } else if (messageRouter != null) {
                    messageRouter.accept(type, msg);
                }
            });
            conn.setDisconnectHandler((c, graceful) -> {
                tcpServer.removeConnection(c);
            });
        });
    }

    private void handleInboundAuth(Connection conn, AuthRequest request) {
        // Verify password
        if (localPassword != null && localPassword.equals(request.getPassword())) {
            serverStatus.put(request.getServerName(), ServerStatus.ONLINE);
            connections.put(request.getServerName(), conn);

            // Update connection identity
            AuthResponse response = new AuthResponse(true, "OK",
                    "local", "26.1.2", System.currentTimeMillis());
            conn.sendResponse(FrameType.AUTH_RESPONSE, response);

            // Now set up the full message handler
            conn.start((type, msg) -> {
                if (messageRouter != null) {
                    messageRouter.accept(type, msg);
                }
            });

            notifyStatusChange(request.getServerName(), ServerStatus.ONLINE);
        } else {
            AuthResponse response = new AuthResponse(false, "Authentication failed",
                    null, null, 0);
            conn.sendResponse(FrameType.AUTH_RESPONSE, response);
            conn.close();
        }
    }

    /**
     * Initiate an outbound connection, authenticate, and register.
     */
    public boolean connectToRemote(RemoteServer server) {
        Connection conn = tcpClient.connect(server.getName(), server.getAddress(), server.getPort());
        if (conn == null) {
            serverStatus.put(server.getName(), ServerStatus.OFFLINE);
            return false;
        }

        // Send auth
        AuthRequest auth = new AuthRequest("local", server.getPasswordHash(),
                "1", "26.1.2");
        conn.send(FrameType.AUTH_REQUEST, auth);

        // Start reader
        conn.start((type, msg) -> {
            if (type == FrameType.AUTH_RESPONSE) {
                AuthResponse resp = (AuthResponse) msg;
                if (resp.isSuccess()) {
                    serverStatus.put(server.getName(), ServerStatus.ONLINE);
                    notifyStatusChange(server.getName(), ServerStatus.ONLINE);
                } else {
                    serverStatus.put(server.getName(), ServerStatus.OFFLINE);
                    connections.remove(server.getName());
                    conn.close();
                }
            } else if (messageRouter != null) {
                messageRouter.accept(type, msg);
            }
        });

        conn.setDisconnectHandler((c, graceful) -> {
            serverStatus.put(server.getName(), ServerStatus.OFFLINE);
            connections.remove(server.getName());
            notifyStatusChange(server.getName(), ServerStatus.OFFLINE);
        });

        connections.put(server.getName(), conn);
        return true;
    }

    public void disconnect(String serverName) {
        Connection conn = connections.remove(serverName);
        if (conn != null) {
            conn.close();
        }
        serverStatus.put(serverName, ServerStatus.OFFLINE);
        notifyStatusChange(serverName, ServerStatus.OFFLINE);
    }

    public Connection getConnection(String serverName) {
        return connections.get(serverName);
    }

    public ServerStatus getStatus(String serverName) {
        return serverStatus.getOrDefault(serverName, ServerStatus.OFFLINE);
    }

    public ConcurrentHashMap<String, ServerStatus> getAllStatuses() {
        return new ConcurrentHashMap<>(serverStatus);
    }

    public void addStatusListener(BiConsumer<String, ServerStatus> listener) {
        statusListeners.add(listener);
    }

    private void notifyStatusChange(String serverName, ServerStatus status) {
        for (BiConsumer<String, ServerStatus> listener : statusListeners) {
            listener.accept(serverName, status);
        }
    }

    public int getLocalPort() {
        return tcpServer.getPort();
    }

    public void shutdown() {
        tcpServer.shutdown();
        for (Connection conn : connections.values()) {
            conn.close();
        }
        connections.clear();
    }
}
