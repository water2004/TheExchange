package org.edtp.theexchange.api;

import org.edtp.theexchange.compat.ItemSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge between the core module and the mod loader adapter.
 * Each adapter (Fabric, Forge, NeoForge) implements this interface.
 */
public interface ExchangeAPI {

    /** Logger bridge to the mod loader's logging system */
    interface Logger {
        void info(String message);
        void warn(String message);
        void error(String message);
        void error(String message, Throwable t);
    }

    Logger getLogger();

    /** Get the ItemSerializer for this Minecraft version and loader */
    ItemSerializer getItemSerializer();

    /** Get the config loader for reading/writing mod configuration */
    ConfigLoader getConfigLoader();

    /** Get the Minecraft server version string (e.g. "26.1.2") */
    String getServerVersion();

    /** Get this server's display name (from config) */
    String getServerName();

    /** Schedule a task on the main server thread */
    void runOnMainThread(Runnable task);

    /** Schedule an async task */
    void runAsync(Runnable task);

    /** Refresh open exchange views after remote inventory changes */
    void refreshRemoteInventoryView(String serverName);

    /** Redraw open exchange views from already-updated memory cache. */
    void redrawRemoteInventoryView(String serverName);

    interface ConfigLoader {
        /** Get the path to the mod's config directory */
        String getConfigDir();

        /** Get the path to the SQLite database file */
        String getDatabasePath();

        /** Load the main configuration as a JSON string */
        String loadConfig();

        /** Save the main configuration from a JSON string */
        void saveConfig(String json);
    }

    class RuntimeConfig {
        private final ServerConfig server;
        private final NetworkConfig network;
        private final CacheConfig cache;
        private final PerformanceConfig performance;
        private final LoggingConfig logging;
        private final ContainerConfig container;
        private final String displayName;
        private final int port;
        private final String password;
        private final List<RemoteServerConfig> remoteServers;

        public RuntimeConfig(ServerConfig server, NetworkConfig network,
                             CacheConfig cache, PerformanceConfig performance, LoggingConfig logging,
                             ContainerConfig container, List<RemoteServerConfig> remoteServers) {
            this.server = server;
            this.network = network;
            this.cache = cache;
            this.performance = performance;
            this.logging = logging;
            this.container = container;
            this.displayName = server != null ? server.getDisplayName() : null;
            this.port = server != null ? server.getPort() : 0;
            this.password = server != null ? server.getPassword() : null;
            this.remoteServers = remoteServers != null ? new ArrayList<>(remoteServers) : new ArrayList<>();
        }

        public ServerConfig getServer() { return server; }
        public NetworkConfig getNetwork() { return network; }
        public CacheConfig getCache() { return cache; }
        public PerformanceConfig getPerformance() { return performance; }
        public LoggingConfig getLogging() { return logging; }
        public ContainerConfig getContainer() { return container; }
        public String getDisplayName() { return displayName; }
        public int getPort() { return port; }
        public String getPassword() { return password; }
        public List<RemoteServerConfig> getRemoteServers() { return remoteServers; }
    }

    class ServerConfig {
        private final String displayName;
        private final int port;
        private final String password;

        public ServerConfig(String displayName, int port, String password) {
            this.displayName = displayName;
            this.port = port;
            this.password = password;
        }

        public String getDisplayName() { return displayName; }
        public int getPort() { return port; }
        public String getPassword() { return password; }
    }

    class NetworkConfig {
        private final int heartbeatIntervalSeconds;
        private final int heartbeatTimeoutSeconds;
        private final int reconnectInitialDelaySeconds;
        private final int reconnectMaxDelaySeconds;
        private final int requestTimeoutSeconds;
        private final boolean inboundEnabled;

        public NetworkConfig(int heartbeatIntervalSeconds, int heartbeatTimeoutSeconds,
                             int reconnectInitialDelaySeconds, int reconnectMaxDelaySeconds,
                             int requestTimeoutSeconds, boolean inboundEnabled) {
            this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
            this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
            this.reconnectInitialDelaySeconds = reconnectInitialDelaySeconds;
            this.reconnectMaxDelaySeconds = reconnectMaxDelaySeconds;
            this.requestTimeoutSeconds = requestTimeoutSeconds;
            this.inboundEnabled = inboundEnabled;
        }

        public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
        public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
        public int getReconnectInitialDelaySeconds() { return reconnectInitialDelaySeconds; }
        public int getReconnectMaxDelaySeconds() { return reconnectMaxDelaySeconds; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public boolean isInboundEnabled() { return inboundEnabled; }
    }

    class CacheConfig {
        private final int offlineRetentionHours;
        private final int localInventoryCacheCapacity;
        private final int remoteInventoryCacheCapacity;

        public CacheConfig(int offlineRetentionHours, int localInventoryCacheCapacity,
                           int remoteInventoryCacheCapacity) {
            this.offlineRetentionHours = offlineRetentionHours;
            this.localInventoryCacheCapacity = localInventoryCacheCapacity;
            this.remoteInventoryCacheCapacity = remoteInventoryCacheCapacity;
        }

        public int getOfflineRetentionHours() { return offlineRetentionHours; }
        public int getLocalInventoryCacheCapacity() { return localInventoryCacheCapacity; }
        public int getRemoteInventoryCacheCapacity() { return remoteInventoryCacheCapacity; }
    }

    class PerformanceConfig {
        private final int coreThreads;

        public PerformanceConfig(int coreThreads) {
            this.coreThreads = coreThreads;
        }

        public int getCoreThreads() { return coreThreads; }
    }

    class LoggingConfig {
        private final int retentionDays;
        private final int cleanupIntervalHours;

        public LoggingConfig(int retentionDays, int cleanupIntervalHours) {
            this.retentionDays = retentionDays;
            this.cleanupIntervalHours = cleanupIntervalHours;
        }

        public int getRetentionDays() { return retentionDays; }
        public int getCleanupIntervalHours() { return cleanupIntervalHours; }
    }

    class ContainerConfig {
        private final int rows;
        private final String titleTemplate;

        public ContainerConfig(int rows, String titleTemplate) {
            this.rows = rows;
            this.titleTemplate = titleTemplate;
        }

        public int getRows() { return rows; }
        public String getTitleTemplate() { return titleTemplate; }
    }

    class RemoteServerConfig {
        private final String name;
        private final String address;
        private final int port;
        private final String password;

        public RemoteServerConfig(String name, String address, int port, String password) {
            this.name = name;
            this.address = address;
            this.port = port;
            this.password = password;
        }

        public String getName() { return name; }
        public String getAddress() { return address; }
        public int getPort() { return port; }
        public String getPassword() { return password; }
    }
}
