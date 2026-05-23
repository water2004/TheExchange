package org.edtp.theexchange.network;

import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.model.ServerStatus;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.network.tls.PinnedPeerKeyStore;
import org.edtp.theexchange.network.tls.TlsContext;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NetworkManager {

    private static final String TAG = "[Exchange|Net] ";
    private static final int MAX_AUTH_FAILURES = 5;
    private static final long AUTH_BAN_MS = 30_000L;

    private final TlsContext tlsContext;
    private final PinnedPeerKeyStore pinnedPeerKeyStore;
    private final TcpServer tcpServer;
    private final TcpClient tcpClient;
    private final String serverVersion;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerStatus> serverStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthFailure> authFailures = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BiConsumer<String, ServerStatus>> statusListeners = new CopyOnWriteArrayList<>();

    private volatile String localServerName;
    private volatile String localPassword;
    private volatile MessageHandler messageRouter;
    private volatile Consumer<String> onlineHandler;
    private volatile boolean acceptingInbound;

    @FunctionalInterface
    public interface MessageHandler {
        void handle(Connection conn, FrameType type, Object message);
    }

    public NetworkManager(int localPort, Path keystorePath, PinnedPeerKeyStore pinnedPeerKeyStore,
                          String cn, char[] keystorePassword, String serverVersion) {
        this.tlsContext = TlsContext.create(keystorePath, cn, keystorePassword);
        this.pinnedPeerKeyStore = pinnedPeerKeyStore;
        this.tcpServer = new TcpServer(localPort, tlsContext);
        this.tcpClient = new TcpClient(tlsContext, pinnedPeerKeyStore);
        this.serverVersion = serverVersion;
        System.out.println(TAG + "Created, local port=" + localPort);
    }

    public void setLocalPassword(String password) {
        this.localPassword = password;
        System.out.println(TAG + "Local password set");
    }

    public void setLocalServerName(String localServerName) {
        this.localServerName = localServerName;
        System.out.println(TAG + "Local server name set to " + localServerName);
    }

    public void setMessageRouter(MessageHandler router) {
        this.messageRouter = router;
    }

    public void setOnlineHandler(Consumer<String> onlineHandler) {
        this.onlineHandler = onlineHandler;
    }

    public void start() {
        startInbound();
    }

    public void startInbound() {
        if (tcpServer.isRunning()) {
            acceptingInbound = true;
            return;
        }
        System.out.println(TAG + "Starting TCP server...");
        tcpServer.start(conn -> {
            System.out.println(TAG + "Inbound connection from " + conn.getRemoteName());
            conn.start((type, msg) -> {
                System.out.println(TAG + "Inbound frame: type=" + type + " msg="
                        + (msg != null ? msg.getClass().getSimpleName() : "null"));
                if (type == FrameType.AUTH_REQUEST) {
                    handleInboundAuth(conn, (AuthRequest) msg);
                } else if (conn.isAuthenticated()) {
                    conn.onResponse(type, msg);
                    if (messageRouter != null) {
                        messageRouter.handle(conn, type, msg);
                    }
                }
            });
            conn.setDisconnectHandler((c, graceful) -> {
                System.out.println(TAG + "Inbound connection closed: " + c.getRemoteName());
                tcpServer.removeConnection(c);
            });
        });
        acceptingInbound = true;
        System.out.println(TAG + "TCP server started");
    }

    public void stopInbound() {
        acceptingInbound = false;
        tcpServer.shutdown();
        for (String name : new java.util.ArrayList<>(connections.keySet())) {
            Connection conn = connections.get(name);
            if (conn != null && isInboundConnection(conn)) {
                disconnect(name);
            }
        }
        System.out.println(TAG + "TCP server stopped");
    }

    private void handleInboundAuth(Connection conn, AuthRequest request) {
        if (!acceptingInbound) {
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "Inbound connections disabled", null, null, 0));
            conn.close();
            return;
        }
        String authKey = authFailureKey(conn);
        if (isAuthBanned(authKey)) {
            System.out.println(TAG + "AUTH throttled from " + authKey);
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "Authentication temporarily blocked", null, null, 0));
            conn.close();
            return;
        }
        System.out.println(TAG + "AUTH from " + request.getServerName()
                + " mcVer=" + request.getMcVersion());

        if (localPassword == null) {
            System.out.println(TAG + "AUTH FAIL: localPassword is null (config not loaded?)");
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "Server not configured", null, null, 0));
            conn.close();
            return;
        }

        if (passwordMatches(localPassword, request.getPassword())) {
            authFailures.remove(authKey);
            System.out.println(TAG + "AUTH OK from " + request.getServerName());
            conn.setAuthenticated(true);
            conn.setInbound(true);
            conn.setPeerServerName(request.getServerName());
            connections.put(request.getServerName(), conn);
            serverStatus.put(request.getServerName(), ServerStatus.ONLINE);

            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(true, "OK",
                            localServerName != null ? localServerName : "local",
                            serverVersion,
                            System.currentTimeMillis()));

            notifyStatusChange(request.getServerName(), ServerStatus.ONLINE);
        } else {
            recordAuthFailure(authKey);
            System.out.println(TAG + "AUTH FAIL: password mismatch for " + request.getServerName());
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "Authentication failed", null, null, 0));
            conn.close();
        }
    }

    public boolean connectToRemote(RemoteServer server) {
        Connection existing = connections.get(server.getName());
        if (existing != null && existing.isRunning()) {
            return true;
        }
        if (existing != null) {
            connections.remove(server.getName());
        }
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
        AuthRequest auth = new AuthRequest(authServerName, server.getPasswordHash(), "1", serverVersion);
        conn.send(FrameType.AUTH_REQUEST, auth);

        conn.start((type, msg) -> {
            System.out.println(TAG + "Response from " + server.getName()
                    + ": type=" + type + " msg="
                    + (msg != null ? msg.getClass().getSimpleName() : "null"));
            conn.onResponse(type, msg);
            if (type == FrameType.AUTH_RESPONSE) {
                AuthResponse resp = (AuthResponse) msg;
                System.out.println(TAG + "AUTH response from " + server.getName()
                        + ": success=" + resp.isSuccess()
                        + " msg=" + resp.getMessage());
                if (resp.isSuccess()) {
                    conn.setAuthenticated(true);
                    conn.setInbound(false);
                    conn.setPeerServerName(server.getName());
                    connections.put(server.getName(), conn);
                    serverStatus.put(server.getName(), ServerStatus.ONLINE);
                    notifyStatusChange(server.getName(), ServerStatus.ONLINE);
                    if (messageRouter != null) {
                        // let higher layer decide whether to refresh open views
                    }
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

        System.out.println(TAG + "Connection pending auth for " + server.getName());
        return true;
    }

    public void disconnect(String serverName) {
        Connection conn = connections.remove(serverName);
        if (conn != null) conn.close();
        serverStatus.put(serverName, ServerStatus.OFFLINE);
        notifyStatusChange(serverName, ServerStatus.OFFLINE);
    }

    public void disconnectOutboundNotIn(Set<String> allowedServerNames) {
        for (String serverName : new java.util.ArrayList<>(connections.keySet())) {
            Connection conn = connections.get(serverName);
            if (conn != null && !conn.isInbound()
                    && (allowedServerNames == null || !allowedServerNames.contains(serverName))) {
                disconnect(serverName);
            }
        }
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
        if (status == ServerStatus.ONLINE) {
            Consumer<String> handler = onlineHandler;
            if (handler != null) {
                handler.accept(serverName);
            }
        }
    }

    public int getLocalPort() { return tcpServer.getPort(); }
    public boolean isInboundRunning() { return tcpServer.isRunning(); }

    private boolean isInboundConnection(Connection conn) {
        return conn != null && conn.isInbound();
    }

    private boolean passwordMatches(String expected, String actual) {
        byte[] expectedBytes = expected != null ? expected.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] actualBytes = actual != null ? actual.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private String authFailureKey(Connection conn) {
        String remote = conn != null ? conn.getRemoteName() : "";
        int lastColon = remote.lastIndexOf(':');
        return lastColon > 0 ? remote.substring(0, lastColon) : remote;
    }

    private boolean isAuthBanned(String key) {
        AuthFailure failure = authFailures.get(key);
        if (failure == null || failure.failCount < MAX_AUTH_FAILURES) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - failure.lastFailTime;
        if (elapsed < AUTH_BAN_MS) {
            return true;
        }
        authFailures.remove(key, failure);
        return false;
    }

    private void recordAuthFailure(String key) {
        long now = System.currentTimeMillis();
        authFailures.compute(key, (ignored, previous) -> previous == null
                ? new AuthFailure(1, now)
                : new AuthFailure(previous.failCount + 1, now));
    }

    public void shutdown() {
        tcpServer.shutdown();
        for (Connection conn : connections.values()) conn.close();
        connections.clear();
    }

    private record AuthFailure(int failCount, long lastFailTime) {}
}
