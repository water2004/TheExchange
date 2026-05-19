package org.edtp.theexchange.network;

import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.model.ServerStatus;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.network.tls.TlsContext;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NetworkManager {

    private static final String TAG = "[Exchange|Net] ";

    private final TlsContext tlsContext;
    private final TcpServer tcpServer;
    private final TcpClient tcpClient;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerStatus> serverStatus = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BiConsumer<String, ServerStatus>> statusListeners = new CopyOnWriteArrayList<>();

    private String localServerName;
    private String localPassword;
    private MessageHandler messageRouter;

    @FunctionalInterface
    public interface MessageHandler {
        void handle(Connection conn, FrameType type, Object message);
    }

    public NetworkManager(int localPort, Path keystorePath, String cn, char[] keystorePassword) {
        this.tlsContext = TlsContext.create(keystorePath, cn, keystorePassword);
        this.tcpServer = new TcpServer(localPort, tlsContext);
        this.tcpClient = new TcpClient(tlsContext);
        System.out.println(TAG + "Created, local port=" + localPort);
    }

    public void setLocalPassword(String password) {
        this.localPassword = password;
        System.out.println(TAG + "Local password set (len=" + (password != null ? password.length() : 0) + ")");
    }

    public void setLocalServerName(String localServerName) {
        this.localServerName = localServerName;
        System.out.println(TAG + "Local server name set to " + localServerName);
    }

    public void setMessageRouter(MessageHandler router) {
        this.messageRouter = router;
    }

    public void start() {
        System.out.println(TAG + "Starting TCP server...");
        tcpServer.start(conn -> {
            System.out.println(TAG + "Inbound connection from " + conn.getRemoteName());
            conn.start((type, msg) -> {
                System.out.println(TAG + "Inbound frame: type=" + type + " msg="
                        + (msg != null ? msg.getClass().getSimpleName() : "null"));
                if (type == FrameType.AUTH_REQUEST) {
                    handleInboundAuth(conn, (AuthRequest) msg);
                } else if (conn.isAuthenticated() && messageRouter != null) {
                    messageRouter.handle(conn, type, msg);
                }
            });
            conn.setDisconnectHandler((c, graceful) -> {
                System.out.println(TAG + "Inbound connection closed: " + c.getRemoteName());
                tcpServer.removeConnection(c);
            });
        });
        System.out.println(TAG + "TCP server started");
    }

    private void handleInboundAuth(Connection conn, AuthRequest request) {
        System.out.println(TAG + "AUTH from " + request.getServerName()
                + " mcVer=" + request.getMcVersion()
                + " pwdLen=" + (request.getPassword() != null ? request.getPassword().length() : 0));

        if (localPassword == null) {
            System.out.println(TAG + "AUTH FAIL: localPassword is null (config not loaded?)");
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "Server not configured", null, null, 0));
            conn.close();
            return;
        }

        if (localPassword.equals(request.getPassword())) {
            System.out.println(TAG + "AUTH OK from " + request.getServerName());
            serverStatus.put(request.getServerName(), ServerStatus.ONLINE);
            connections.put(request.getServerName(), conn);
            conn.setAuthenticated(true);
            conn.setPeerServerName(request.getServerName());

            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(true, "OK",
                            localServerName != null ? localServerName : "local",
                            "26.1.2",
                            System.currentTimeMillis()));

            notifyStatusChange(request.getServerName(), ServerStatus.ONLINE);
        } else {
            System.out.println(TAG + "AUTH FAIL: password mismatch for " + request.getServerName()
                    + " (received len=" + (request.getPassword() != null ? request.getPassword().length() : 0) + ")");
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "Authentication failed", null, null, 0));
            conn.close();
        }
    }

    public boolean connectToRemote(RemoteServer server) {
        System.out.println(TAG + "Connecting to " + server.getName()
                + " at " + server.getAddress() + ":" + server.getPort());
        Connection conn = tcpClient.connect(server.getName(), server.getAddress(), server.getPort());
        if (conn == null) {
            System.out.println(TAG + "Connect FAILED to " + server.getName());
            serverStatus.put(server.getName(), ServerStatus.OFFLINE);
            return false;
        }

        System.out.println(TAG + "TLS connected to " + server.getName() + ", sending AUTH...");
        String authServerName = localServerName != null ? localServerName : "local";
        AuthRequest auth = new AuthRequest(authServerName, server.getPasswordHash(), "1", "26.1.2");
        conn.send(FrameType.AUTH_REQUEST, auth);

        conn.start((type, msg) -> {
            System.out.println(TAG + "Response from " + server.getName()
                    + ": type=" + type + " msg="
                    + (msg != null ? msg.getClass().getSimpleName() : "null"));
            // Route to any pending sendAndWait future first
            conn.onResponse(type, msg);
            if (type == FrameType.AUTH_RESPONSE) {
                AuthResponse resp = (AuthResponse) msg;
                System.out.println(TAG + "AUTH response from " + server.getName()
                        + ": success=" + resp.isSuccess()
                        + " msg=" + resp.getMessage());
                if (resp.isSuccess()) {
                    conn.setAuthenticated(true);
                    conn.setPeerServerName(server.getName());
                    serverStatus.put(server.getName(), ServerStatus.ONLINE);
                    notifyStatusChange(server.getName(), ServerStatus.ONLINE);
                } else {
                    serverStatus.put(server.getName(), ServerStatus.OFFLINE);
                    connections.remove(server.getName());
                    conn.close();
                }
            } else if (conn.isAuthenticated() && messageRouter != null) {
                messageRouter.handle(conn, type, msg);
            }
        });

        conn.setDisconnectHandler((c, graceful) -> {
            System.out.println(TAG + "Disconnected from " + server.getName());
            serverStatus.put(server.getName(), ServerStatus.OFFLINE);
            connections.remove(server.getName());
            notifyStatusChange(server.getName(), ServerStatus.OFFLINE);
        });

        connections.put(server.getName(), conn);
        System.out.println(TAG + "Connection registered for " + server.getName());
        return true;
    }

    public void disconnect(String serverName) {
        Connection conn = connections.remove(serverName);
        if (conn != null) conn.close();
        serverStatus.put(serverName, ServerStatus.OFFLINE);
        notifyStatusChange(serverName, ServerStatus.OFFLINE);
    }

    public Connection getConnection(String serverName) {
        return connections.get(serverName);
    }

    public Collection<Connection> getConnections() {
        return connections.values();
    }

    public void broadcast(FrameType type, Object message, Connection exclude) {
        for (Connection conn : connections.values()) {
            if (conn == null || conn == exclude || !conn.isRunning()) continue;
            conn.send(type, message);
        }
    }

    public ServerStatus getStatus(String serverName) {
        return serverStatus.getOrDefault(serverName, ServerStatus.OFFLINE);
    }

    public void addStatusListener(BiConsumer<String, ServerStatus> listener) {
        statusListeners.add(listener);
    }

    private void notifyStatusChange(String serverName, ServerStatus status) {
        System.out.println(TAG + "Status: " + serverName + " → " + status);
        for (BiConsumer<String, ServerStatus> listener : statusListeners) {
            listener.accept(serverName, status);
        }
    }

    public int getLocalPort() { return tcpServer.getPort(); }

    public void shutdown() {
        tcpServer.shutdown();
        for (Connection conn : connections.values()) conn.close();
        connections.clear();
    }
}
