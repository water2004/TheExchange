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

    interface ConfigLoader {
        /** Get the path to the mod's config directory */
        String getConfigDir();

        /** Get the path to the SQLite database file */
        String getDatabasePath();

        /** Maximum number of authoritative local inventory scopes to keep in memory. */
        default int getLocalInventoryCacheCapacity() {
            return 32;
        }

        /** Load the main configuration as a JSON string */
        String loadConfig();

        /** Save the main configuration from a JSON string */
        void saveConfig(String json);

        /** Loader-neutral parsed runtime config. */
        default RuntimeConfig getRuntimeConfig() {
            return new RuntimeConfig("Default Server", 25566, "changeme", List.of());
        }
    }

    class RuntimeConfig {
        private final String displayName;
        private final int port;
        private final String password;
        private final List<RemoteServerConfig> remoteServers;

        public RuntimeConfig(String displayName, int port, String password,
                             List<RemoteServerConfig> remoteServers) {
            this.displayName = displayName;
            this.port = port;
            this.password = password;
            this.remoteServers = remoteServers != null ? new ArrayList<>(remoteServers) : new ArrayList<>();
        }

        public String getDisplayName() { return displayName; }
        public int getPort() { return port; }
        public String getPassword() { return password; }
        public List<RemoteServerConfig> getRemoteServers() { return remoteServers; }
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
