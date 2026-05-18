package org.edtp.theexchange.service;

import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.model.ServerStatus;
import org.edtp.theexchange.network.Connection;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.Heartbeat;

import java.util.concurrent.*;

public class HeartbeatManager {

    private static final int HEARTBEAT_INTERVAL_SEC = 10;
    private static final int HEARTBEAT_TIMEOUT_SEC = 30;
    private static final int INITIAL_RECONNECT_DELAY_SEC = 5;
    private static final int MAX_RECONNECT_DELAY_SEC = 30;

    private final NetworkManager networkManager;
    private final ServerRegistry serverRegistry;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ConcurrentHashMap<String, Integer> reconnectDelays = new ConcurrentHashMap<>();
    private volatile boolean running;

    public HeartbeatManager(NetworkManager networkManager, ServerRegistry serverRegistry) {
        this.networkManager = networkManager;
        this.serverRegistry = serverRegistry;
    }

    public void start() {
        running = true;
        // Send heartbeats every 10 seconds
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_INTERVAL_SEC,
                HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
        // Check timeouts every 5 seconds
        scheduler.scheduleAtFixedRate(this::checkTimeouts, 5, 5, TimeUnit.SECONDS);
    }

    private void sendHeartbeats() {
        for (RemoteServer server : serverRegistry.getAllServers()) {
            Connection conn = networkManager.getConnection(server.getName());
            if (conn != null && conn.isRunning()) {
                Heartbeat hb = new Heartbeat(false, System.currentTimeMillis());
                conn.send(FrameType.HEARTBEAT, hb);
            }
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        long timeoutMs = (long) HEARTBEAT_TIMEOUT_SEC * 1000;

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
        int delay = reconnectDelays.getOrDefault(server.getName(), INITIAL_RECONNECT_DELAY_SEC);
        scheduler.schedule(() -> {
            boolean success = networkManager.connectToRemote(server);
            if (success) {
                reconnectDelays.remove(server.getName());
            } else {
                // Exponential backoff
                int nextDelay = Math.min(delay * 2, MAX_RECONNECT_DELAY_SEC);
                reconnectDelays.put(server.getName(), nextDelay);
            }
        }, delay, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }
}
