package org.edtp.theexchange.api;

import org.edtp.theexchange.compat.ItemSerializer;

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
}
