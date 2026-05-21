package org.edtp.theexchange.service;

import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.model.ServerStatus;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.Heartbeat;

import java.util.concurrent.*;

public class HeartbeatManager {

    private final NetworkManager networkManager;
    private final ServerRegistry serverRegistry;
    private final int heartbeatIntervalSeconds;
    private final int heartbeatTimeoutSeconds;
    private final int reconnectInitialDelaySeconds;
    private final int reconnectMaxDelaySeconds;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ConcurrentHashMap<String, Integer> reconnectDelays = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> reconnectScheduled = new ConcurrentHashMap<>();
    private volatile boolean running;

    public HeartbeatManager(NetworkManager networkManager, ServerRegistry serverRegistry,
                            org.edtp.theexchange.api.ExchangeAPI.NetworkConfig config) {
        this.networkManager = networkManager;
        this.serverRegistry = serverRegistry;
        this.heartbeatIntervalSeconds = config.getHeartbeatIntervalSeconds();
        this.heartbeatTimeoutSeconds = config.getHeartbeatTimeoutSeconds();
        this.reconnectInitialDelaySeconds = config.getReconnectInitialDelaySeconds();
        this.reconnectMaxDelaySeconds = config.getReconnectMaxDelaySeconds();
    }

    public void start() {
        running = true;
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, heartbeatIntervalSeconds,
                heartbeatIntervalSeconds, TimeUnit.SECONDS);
        // Check timeouts every 5 seconds
        scheduler.scheduleAtFixedRate(this::checkTimeouts, 5, 5, TimeUnit.SECONDS);
        scheduler.execute(this::connectAllMissing);
    }

    private void connectAllMissing() {
        for (RemoteServer server : serverRegistry.getAllServers()) {
            if (!server.isEnabled()) continue;
            if (networkManager.getConnection(server.getName()) == null) {
                scheduleReconnect(server);
            }
        }
    }

    private void sendHeartbeats() {
        for (RemoteServer server : serverRegistry.getAllServers()) {
            Connection conn = networkManager.getConnection(server.getName());
            if (conn != null && conn.isRunning()) {
                Heartbeat hb = new Heartbeat(false, System.currentTimeMillis());
                conn.send(FrameType.HEARTBEAT, hb);
            } else if (server.isEnabled()) {
                scheduleReconnect(server);
            }
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        long timeoutMs = (long) heartbeatTimeoutSeconds * 1000;

        for (RemoteServer server : serverRegistry.getAllServers()) {
            Connection conn = networkManager.getConnection(server.getName());
            if (conn != null && conn.isRunning()) {
                if (now - conn.getLastRecvTime() > timeoutMs) {
                    // Connection timed out
                    networkManager.disconnect(server.getName());
                    scheduleReconnect(server);
                }
            } else if (networkManager.getStatus(server.getName()) == ServerStatus.OFFLINE) {
                // Already disconnected — retry if not already scheduled
                if (!reconnectDelays.containsKey(server.getName())) {
                    scheduleReconnect(server);
                }
            }
        }
    }

    private void scheduleReconnect(RemoteServer server) {
        if (reconnectScheduled.putIfAbsent(server.getName(), Boolean.TRUE) != null) {
            return;
        }
        int delay = reconnectDelays.getOrDefault(server.getName(), reconnectInitialDelaySeconds);
        scheduler.schedule(() -> {
            try {
                boolean success = networkManager.connectToRemote(server);
                if (success) {
                    reconnectDelays.remove(server.getName());
                } else {
                    // Exponential backoff
                    int nextDelay = Math.min(delay * 2, reconnectMaxDelaySeconds);
                    reconnectDelays.put(server.getName(), nextDelay);
                }
            } finally {
                reconnectScheduled.remove(server.getName());
            }
        }, delay, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }
}
