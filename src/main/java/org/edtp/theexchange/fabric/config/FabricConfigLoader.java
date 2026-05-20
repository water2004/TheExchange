package org.edtp.theexchange.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;
import org.edtp.theexchange.api.ExchangeAPI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FabricConfigLoader implements ExchangeAPI.ConfigLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_DIR_NAME = "theexchange";
    private static final String CONFIG_FILE_NAME = "theexchange.json";
    private static final String DB_FILE_NAME = "data.db";

    private ConfigData configData;
    private final Path configDir;

    public FabricConfigLoader() {
        this.configDir = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_DIR_NAME);
    }

    @Override
    public String getConfigDir() {
        return configDir.toAbsolutePath().toString();
    }

    @Override
    public String getDatabasePath() {
        return configDir.resolve(DB_FILE_NAME).toAbsolutePath().toString();
    }

    @Override
    public String loadConfig() {
        try {
            Files.createDirectories(configDir);
            Path configFile = configDir.resolve(CONFIG_FILE_NAME);
            if (Files.exists(configFile)) {
                String json = Files.readString(configFile);
                configData = GSON.fromJson(json, ConfigData.class);
                return json;
            } else {
                configData = createDefault();
                String json = GSON.toJson(configData);
                Files.writeString(configFile, json);
                return json;
            }
        } catch (IOException e) {
            configData = createDefault();
            return GSON.toJson(configData);
        }
    }

    @Override
    public void saveConfig(String json) {
        try {
            Files.createDirectories(configDir);
            Path configFile = configDir.resolve(CONFIG_FILE_NAME);
            Files.writeString(configFile, json);
            configData = GSON.fromJson(json, ConfigData.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config", e);
        }
    }

    public String getDisplayName() {
        if (configData == null) loadConfig();
        return configData.server.displayName;
    }

    public String getPassword() {
        if (configData == null) loadConfig();
        return configData.server.password;
    }

    public int getPort() {
        if (configData == null) loadConfig();
        return configData.server.port;
    }

    public List<RemoteServerConfig> getRemoteServers() {
        if (configData == null) loadConfig();
        return configData.remoteServers != null ? configData.remoteServers : List.of();
    }

    @Override
    public ExchangeAPI.RuntimeConfig getRuntimeConfig() {
        if (configData == null) loadConfig();
        List<ExchangeAPI.RemoteServerConfig> remotes = new ArrayList<>();
        for (RemoteServerConfig remote : getRemoteServers()) {
            remotes.add(new ExchangeAPI.RemoteServerConfig(
                    remote.name, remote.address, remote.port, remote.password));
        }
        return new ExchangeAPI.RuntimeConfig(
                configData.server.displayName,
                configData.server.port,
                configData.server.password,
                remotes);
    }

    public int getCacheRetentionHours() {
        if (configData == null) loadConfig();
        return configData.cache.offlineRetentionHours;
    }

    @Override
    public int getLocalInventoryCacheCapacity() {
        if (configData == null) loadConfig();
        return configData.cache.localInventoryCacheCapacity;
    }

    @Override
    public int getRemoteInventoryCacheCapacity() {
        if (configData == null) loadConfig();
        return configData.cache.remoteInventoryCacheCapacity;
    }

    public int getLogRetentionDays() {
        if (configData == null) loadConfig();
        return configData.logging.retentionDays;
    }

    private ConfigData createDefault() {
        ConfigData data = new ConfigData();
        data.server = new ServerConfig();
        data.server.displayName = "Default Server";
        data.server.port = 25566;
        data.server.password = "changeme";
        data.network = new NetworkConfig();
        data.network.heartbeatIntervalSeconds = 10;
        data.network.heartbeatTimeoutSeconds = 30;
        data.network.reconnectInitialDelaySeconds = 5;
        data.network.reconnectMaxDelaySeconds = 30;
        data.network.requestTimeoutSeconds = 5;
        data.cache = new CacheConfig();
        data.cache.offlineRetentionHours = 24;
        data.cache.localInventoryCacheCapacity = 32;
        data.cache.remoteInventoryCacheCapacity = 64;
        data.logging = new LoggingConfig();
        data.logging.retentionDays = 30;
        data.logging.cleanupIntervalHours = 1;
        data.container = new ContainerConfig();
        data.container.rows = 6;
        data.container.titleTemplate = "{server_name} 的共享空间";
        return data;
    }

    public static class ConfigData {
        public ServerConfig server;
        public List<RemoteServerConfig> remoteServers;
        public NetworkConfig network;
        public CacheConfig cache;
        public LoggingConfig logging;
        public ContainerConfig container;
    }

    public static class ServerConfig {
        @SerializedName("display_name")
        public String displayName;
        public int port;
        public String password;
    }

    public static class RemoteServerConfig {
        public String name;
        public String address;
        public int port;
        public String password;
    }

    public static class NetworkConfig {
        @SerializedName("heartbeat_interval_seconds")
        public int heartbeatIntervalSeconds;
        @SerializedName("heartbeat_timeout_seconds")
        public int heartbeatTimeoutSeconds;
        @SerializedName("reconnect_initial_delay_seconds")
        public int reconnectInitialDelaySeconds;
        @SerializedName("reconnect_max_delay_seconds")
        public int reconnectMaxDelaySeconds;
        @SerializedName("request_timeout_seconds")
        public int requestTimeoutSeconds;
    }

    public static class CacheConfig {
        @SerializedName("offline_retention_hours")
        public int offlineRetentionHours;
        @SerializedName("local_inventory_cache_capacity")
        public int localInventoryCacheCapacity;
        @SerializedName("remote_inventory_cache_capacity")
        public int remoteInventoryCacheCapacity;
    }

    public static class LoggingConfig {
        @SerializedName("retention_days")
        public int retentionDays;
        @SerializedName("cleanup_interval_hours")
        public int cleanupIntervalHours;
    }

    public static class ContainerConfig {
        public int rows;
        @SerializedName("title_template")
        public String titleTemplate;
    }
}
