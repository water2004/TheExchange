package org.edtp.theexchange.network;

import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.model.ServerStatus;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.network.tls.PinnedPeerKeyStore;
import org.edtp.theexchange.network.tls.TlsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkManager.class);
    private static final int MAX_AUTH_FAILURES = 5;
    private static final long AUTH_BAN_MS = 30_000L;
    private static final long AUTH_FAILURE_CLEANUP_INTERVAL_MS = 30_000L;

    private final TlsContext tlsContext;
    private final PinnedPeerKeyStore pinnedPeerKeyStore;
    private final TcpServer tcpServer;
    private final TcpClient tcpClient;
    private final String serverVersion;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerStatus> serverStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthFailure> authFailures = new ConcurrentHashMap<>();
    private final AtomicLong lastAuthFailureCleanupAt = new AtomicLong();

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
        LOGGER.debug("Created network manager on local port {}", localPort);
    }

    public void setLocalPassword(String password) {
        this.localPassword = password;
        LOGGER.debug("Local network password configured");
    }

    public void setLocalServerName(String localServerName) {
        this.localServerName = localServerName;
        LOGGER.debug("Local server name set to {}", localServerName);
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
        LOGGER.debug("Starting TCP server");
        tcpServer.start(conn -> {
            LOGGER.debug("Inbound connection from {}", conn.getRemoteName());
            conn.start((type, msg) -> {
                LOGGER.trace("Inbound frame: type={} message={}", type,
                        msg != null ? msg.getClass().getSimpleName() : "null");
                if (type == FrameType.AUTH_REQUEST) {
                    handleInboundAuth(conn, (AuthRequest) msg);
                } else if (conn.isAuthenticated()) {
                    conn.onResponse(type, msg);
                    if (messageRouter != null) {
                        messageRouter.handle(conn, type, msg);
                    }
                } else {
                    conn.send(FrameType.ERROR, new ErrorMessage(426, "Protocol V2 authentication required"));
                    conn.close();
                }
            });
            conn.setDisconnectHandler((c, graceful) -> {
                LOGGER.debug("Inbound connection closed: {}", c.getRemoteName());
                tcpServer.removeConnection(c);
                removeCurrentConnection(c);
            });
        });
        acceptingInbound = true;
        LOGGER.debug("TCP server started");
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
        LOGGER.debug("TCP server stopped");
    }

    private void handleInboundAuth(Connection conn, AuthRequest request) {
        if (!acceptingInbound) {
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "Inbound connections disabled", null, null, 0));
            conn.close();
            return;
        }
        if (request == null || !AuthRequest.CURRENT_PROTOCOL_VERSION.equals(request.getVersion())) {
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "UNSUPPORTED_PROTOCOL required="
                            + AuthRequest.CURRENT_PROTOCOL_VERSION, null, null, 0));
            conn.close();
            return;
        }
        String authKey = authFailureKey(conn);
        if (isAuthBanned(authKey)) {
            LOGGER.warn("Authentication throttled from {}", authKey);
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "Authentication temporarily blocked", null, null, 0));
            conn.close();
            return;
        }
        LOGGER.debug("Authentication request from {} (Minecraft {})",
                request.getServerName(), request.getMcVersion());

        if (localPassword == null) {
            LOGGER.error("Authentication rejected because the local password is not configured");
            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(false, "Server not configured", null, null, 0));
            conn.close();
            return;
        }

        if (passwordMatches(localPassword, request.getPassword())) {
            authFailures.remove(authKey);
            LOGGER.info("Authenticated inbound peer {}", request.getServerName());
            conn.setAuthenticated(true);
            conn.setInbound(true);
            conn.setPeerServerName(request.getServerName());
            boolean installed = installConnection(request.getServerName(), conn);

            conn.send(FrameType.AUTH_RESPONSE,
                    new AuthResponse(true, "OK",
                            localServerName != null ? localServerName : "local",
                            serverVersion,
                            System.currentTimeMillis()));

            if (!installed) {
                conn.close();
                return;
            }

            serverStatus.put(request.getServerName(), ServerStatus.ONLINE);

            notifyStatusChange(request.getServerName(), ServerStatus.ONLINE);
        } else {
            recordAuthFailure(authKey);
            LOGGER.warn("Authentication failed for {}: password mismatch", request.getServerName());
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
        LOGGER.debug("Connecting to {} at {}:{}", server.getName(), server.getAddress(), server.getPort());
        Connection conn = tcpClient.connect(server.getName(), server.getAddress(), server.getPort());
        if (conn == null) {
            serverStatus.put(server.getName(), ServerStatus.OFFLINE);
            return false;
        }

        LOGGER.debug("TLS connected to {}; sending authentication", server.getName());
        String authServerName = localServerName != null ? localServerName : "local";
        AuthRequest auth = new AuthRequest(authServerName, server.getPasswordHash(),
                AuthRequest.CURRENT_PROTOCOL_VERSION, serverVersion);
        conn.send(FrameType.AUTH_REQUEST, auth);

        conn.start((type, msg) -> {
            LOGGER.trace("Frame from {}: type={} message={}", server.getName(), type,
                    msg != null ? msg.getClass().getSimpleName() : "null");
            conn.onResponse(type, msg);
            if (type == FrameType.AUTH_RESPONSE) {
                AuthResponse resp = (AuthResponse) msg;
                LOGGER.debug("Authentication response from {}: success={} message={}",
                        server.getName(), resp.isSuccess(), resp.getMessage());
                if (resp.isSuccess()
                        && AuthResponse.CURRENT_PROTOCOL_VERSION.equals(resp.getVersion())) {
                    conn.setAuthenticated(true);
                    conn.setInbound(false);
                    conn.setPeerServerName(server.getName());
                    if (!installConnection(server.getName(), conn)) {
                        conn.close();
                        return;
                    }
                    serverStatus.put(server.getName(), ServerStatus.ONLINE);
                    LOGGER.info("Authenticated with outbound peer {}", server.getName());
                    notifyStatusChange(server.getName(), ServerStatus.ONLINE);
                    if (messageRouter != null) {
                        // let higher layer decide whether to refresh open views
                    }
                } else {
                    if (resp.isSuccess()) {
                        LOGGER.warn("Authentication failed for {}: unsupported protocol {}",
                                server.getName(), resp.getVersion());
                    }
                    serverStatus.put(server.getName(), ServerStatus.OFFLINE);
                    connections.remove(server.getName());
                    conn.close();
                }
            } else if (conn.isAuthenticated() && messageRouter != null) {
                messageRouter.handle(conn, type, msg);
            } else if (!conn.isAuthenticated()) {
                conn.close();
            }
        });

        conn.setDisconnectHandler((c, graceful) -> {
            LOGGER.info("Disconnected from {}", server.getName());
            if (connections.remove(server.getName(), c)) {
                serverStatus.put(server.getName(), ServerStatus.OFFLINE);
                notifyStatusChange(server.getName(), ServerStatus.OFFLINE);
            }
        });

        LOGGER.debug("Connection to {} is pending authentication", server.getName());
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

    public boolean isCurrentConnection(Connection connection) {
        if (connection == null || connection.getPeerServerName() == null) return false;
        return connections.get(connection.getPeerServerName()) == connection;
    }

    public Collection<Connection> getConnections() {
        return connections.values();
    }

    public void broadcast(FrameType type, Object message, Connection exclude) {
        broadcast(type, message, exclude, ignored -> true);
    }

    public void broadcast(FrameType type, Object message, Connection exclude,
                          Predicate<Connection> recipientFilter) {
        for (Connection conn : connections.values()) {
            if (conn == null || conn == exclude || !conn.isRunning()) continue;
            if (recipientFilter != null && !recipientFilter.test(conn)) continue;
            conn.send(type, message);
        }
    }

    public ServerStatus getStatus(String serverName) {
        return serverStatus.getOrDefault(serverName, ServerStatus.OFFLINE);
    }

    private void notifyStatusChange(String serverName, ServerStatus status) {
        LOGGER.debug("Peer status changed: {} -> {}", serverName, status);
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

    private boolean installConnection(String serverName, Connection connection) {
        while (true) {
            Connection existing = connections.get(serverName);
            if (existing == connection) return true;
            if (existing != null && existing.isRunning()
                    && prefer(existing, connection, serverName) == existing) {
                return false;
            }
            boolean installed = existing == null
                    ? connections.putIfAbsent(serverName, connection) == null
                    : connections.replace(serverName, existing, connection);
            if (!installed) continue;
            if (existing != null) existing.close();
            return true;
        }
    }

    /** Both peers deterministically retain the same physical connection during simultaneous dialing. */
    private Connection prefer(Connection existing, Connection candidate, String peerName) {
        String local = localServerName != null ? localServerName : "local";
        boolean preferInbound = local.compareToIgnoreCase(peerName) > 0;
        boolean existingPreferred = existing.isInbound() == preferInbound;
        boolean candidatePreferred = candidate.isInbound() == preferInbound;
        if (existingPreferred != candidatePreferred) {
            return existingPreferred ? existing : candidate;
        }
        return candidate;
    }

    private void removeCurrentConnection(Connection connection) {
        String serverName = connection != null ? connection.getPeerServerName() : null;
        if (serverName != null && connections.remove(serverName, connection)) {
            serverStatus.put(serverName, ServerStatus.OFFLINE);
            notifyStatusChange(serverName, ServerStatus.OFFLINE);
        }
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
        long now = System.currentTimeMillis();
        cleanupExpiredAuthFailures(now);
        AuthFailure failure = authFailures.get(key);
        if (failure == null) {
            return false;
        }
        if (now - failure.lastFailTime >= AUTH_BAN_MS) {
            authFailures.remove(key, failure);
            return false;
        }
        return failure.failCount >= MAX_AUTH_FAILURES;
    }

    private void recordAuthFailure(String key) {
        long now = System.currentTimeMillis();
        cleanupExpiredAuthFailures(now);
        authFailures.compute(key, (ignored, previous) -> previous == null
                ? new AuthFailure(1, now)
                : new AuthFailure(previous.failCount + 1, now));
    }

    private void cleanupExpiredAuthFailures(long now) {
        long previousCleanup = lastAuthFailureCleanupAt.get();
        if (now - previousCleanup < AUTH_FAILURE_CLEANUP_INTERVAL_MS
                || !lastAuthFailureCleanupAt.compareAndSet(previousCleanup, now)) {
            return;
        }
        for (var entry : authFailures.entrySet()) {
            AuthFailure failure = entry.getValue();
            if (now - failure.lastFailTime >= AUTH_BAN_MS) {
                authFailures.remove(entry.getKey(), failure);
            }
        }
    }

    public void shutdown() {
        tcpServer.shutdown();
        for (Connection conn : connections.values()) conn.close();
        connections.clear();
    }

    private record AuthFailure(int failCount, long lastFailTime) {}
}
