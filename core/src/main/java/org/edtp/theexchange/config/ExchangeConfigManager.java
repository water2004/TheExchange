package org.edtp.theexchange.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.edtp.theexchange.api.ExchangeAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExchangeConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> READABLE_PATHS = List.of(
            "server",
            "server.display_name",
            "server.port",
            "server.password",
            "network",
            "network.heartbeat_interval_seconds",
            "network.heartbeat_timeout_seconds",
            "network.reconnect_initial_delay_seconds",
            "network.reconnect_max_delay_seconds",
            "network.request_timeout_seconds",
            "network.inbound_enabled",
            "cache",
            "cache.offline_retention_hours",
            "cache.local_inventory_cache_capacity",
            "cache.remote_inventory_cache_capacity",
            "performance",
            "performance.core_threads",
            "logging",
            "logging.retention_days",
            "logging.cleanup_interval_hours",
            "container",
            "container.rows",
            "container.title_template",
            "remoteServers"
    );
    private static final List<String> WRITABLE_PATHS = List.of(
            "server.display_name",
            "server.port",
            "server.password",
            "network.heartbeat_interval_seconds",
            "network.heartbeat_timeout_seconds",
            "network.reconnect_initial_delay_seconds",
            "network.reconnect_max_delay_seconds",
            "network.request_timeout_seconds",
            "network.inbound_enabled",
            "cache.offline_retention_hours",
            "cache.local_inventory_cache_capacity",
            "cache.remote_inventory_cache_capacity",
            "performance.core_threads",
            "logging.retention_days",
            "logging.cleanup_interval_hours",
            "container.rows",
            "container.title_template"
    );

    private final ExchangeAPI.ConfigLoader loader;
    private volatile ExchangeAPI.RuntimeConfig active;

    public ExchangeConfigManager(ExchangeAPI.ConfigLoader loader) {
        this.loader = loader;
        this.active = parse(loader.loadConfig());
    }

    public ExchangeAPI.RuntimeConfig current() {
        return active;
    }

    public synchronized ExchangeAPI.RuntimeConfig reload() {
        ExchangeAPI.RuntimeConfig reloaded = parse(loader.loadConfig());
        active = reloaded;
        return reloaded;
    }

    public synchronized String show() {
        return GSON.toJson(toJson(loadFileSnapshot()));
    }

    public synchronized String get(String path) {
        return valueToString(getValue(loadFileSnapshot(), path));
    }

    public List<String> readablePaths() {
        return READABLE_PATHS;
    }

    public List<String> writablePaths() {
        return WRITABLE_PATHS;
    }

    public synchronized void set(String path, String rawValue) {
        ConfigData data = loadFileSnapshot();
        setValue(data, path, rawValue);
        saveFileSnapshot(data);
    }

    public synchronized void addRemote(String name, String address, int port, String password) {
        ConfigData data = loadFileSnapshot();
        for (RemoteServerData remote : data.remoteServers) {
            if (remote.name.equals(name)) {
                remote.address = address;
                remote.port = port;
                remote.password = password;
                saveFileSnapshot(data);
                return;
            }
        }
        data.remoteServers.add(new RemoteServerData(name, address, port, password));
        saveFileSnapshot(data);
    }

    public synchronized boolean removeRemote(String name) {
        ConfigData data = loadFileSnapshot();
        boolean removed = data.remoteServers.removeIf(remote -> remote.name.equals(name));
        saveFileSnapshot(data);
        return removed;
    }

    public synchronized List<ExchangeAPI.RemoteServerConfig> listRemoteServers() {
        return parse(GSON.toJson(loadFileSnapshot())).getRemoteServers();
    }

    private ConfigData loadFileSnapshot() {
        return parseData(loader.loadConfig());
    }

    private void saveFileSnapshot(ConfigData data) {
        validate(data);
        loader.saveConfig(GSON.toJson(data));
    }

    private ExchangeAPI.RuntimeConfig parse(String json) {
        ConfigData data = parseData(json);
        validate(data);
        List<ExchangeAPI.RemoteServerConfig> remotes = new ArrayList<>();
        for (RemoteServerData remote : data.remoteServers) {
            remotes.add(new ExchangeAPI.RemoteServerConfig(remote.name, remote.address, remote.port, remote.password));
        }
        return new ExchangeAPI.RuntimeConfig(
                new ExchangeAPI.ServerConfig(data.server.displayName, data.server.port, data.server.password),
                new ExchangeAPI.NetworkConfig(data.network.heartbeatIntervalSeconds, data.network.heartbeatTimeoutSeconds,
                        data.network.reconnectInitialDelaySeconds, data.network.reconnectMaxDelaySeconds,
                        data.network.requestTimeoutSeconds, data.network.inboundEnabled),
                new ExchangeAPI.CacheConfig(data.cache.offlineRetentionHours, data.cache.localInventoryCacheCapacity,
                        data.cache.remoteInventoryCacheCapacity),
                new ExchangeAPI.PerformanceConfig(data.performance.coreThreads),
                new ExchangeAPI.LoggingConfig(data.logging.retentionDays, data.logging.cleanupIntervalHours),
                new ExchangeAPI.ContainerConfig(data.container.rows, data.container.titleTemplate),
                remotes);
    }

    private ConfigData parseData(String json) {
        ConfigData data = GSON.fromJson(json, ConfigData.class);
        if (data == null) throw new IllegalArgumentException("Config root must be an object");
        if (data.server == null) throw new IllegalArgumentException("Missing config section: server");
        if (data.network == null) throw new IllegalArgumentException("Missing config section: network");
        if (data.cache == null) throw new IllegalArgumentException("Missing config section: cache");
        if (data.performance == null) throw new IllegalArgumentException("Missing config section: performance");
        if (data.logging == null) throw new IllegalArgumentException("Missing config section: logging");
        if (data.container == null) throw new IllegalArgumentException("Missing config section: container");
        if (data.remoteServers == null) throw new IllegalArgumentException("Missing config section: remoteServers");
        return data;
    }

    private void validate(ConfigData data) {
        requireText("server.display_name", data.server.displayName);
        requirePort("server.port", data.server.port);
        requireText("server.password", data.server.password);
        requirePositive("network.heartbeat_interval_seconds", data.network.heartbeatIntervalSeconds);
        requirePositive("network.heartbeat_timeout_seconds", data.network.heartbeatTimeoutSeconds);
        requirePositive("network.reconnect_initial_delay_seconds", data.network.reconnectInitialDelaySeconds);
        requirePositive("network.reconnect_max_delay_seconds", data.network.reconnectMaxDelaySeconds);
        requirePositive("network.request_timeout_seconds", data.network.requestTimeoutSeconds);
        requirePositive("cache.offline_retention_hours", data.cache.offlineRetentionHours);
        requirePositive("cache.local_inventory_cache_capacity", data.cache.localInventoryCacheCapacity);
        requirePositive("cache.remote_inventory_cache_capacity", data.cache.remoteInventoryCacheCapacity);
        requirePositive("performance.core_threads", data.performance.coreThreads);
        requirePositive("logging.retention_days", data.logging.retentionDays);
        requirePositive("logging.cleanup_interval_hours", data.logging.cleanupIntervalHours);
        if (data.container.rows != 6) {
            throw new IllegalArgumentException("container.rows must be 6 for the current generic 9x6 menu");
        }
        requireText("container.title_template", data.container.titleTemplate);
        for (int i = 0; i < data.remoteServers.size(); i++) {
            RemoteServerData remote = data.remoteServers.get(i);
            requireText("remoteServers[" + i + "].name", remote.name);
            requireText("remoteServers[" + i + "].address", remote.address);
            requirePort("remoteServers[" + i + "].port", remote.port);
            requireText("remoteServers[" + i + "].password", remote.password);
        }
    }

    private void requireText(String path, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " must be a non-empty string");
        }
    }

    private void requirePositive(String path, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(path + " must be > 0");
        }
    }

    private void requirePort(String path, int value) {
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(path + " must be between 1 and 65535");
        }
    }

    private JsonObject toJson(ConfigData data) {
        return GSON.toJsonTree(data).getAsJsonObject();
    }

    private JsonElement getValue(ConfigData data, String path) {
        return getJsonValue(toJson(data), path);
    }

    private JsonElement getJsonValue(JsonObject root, String path) {
        String[] parts = splitPath(path);
        JsonElement cursor = root;
        for (String part : parts) {
            if (!(cursor instanceof JsonObject object) || !object.has(part)) {
                throw new IllegalArgumentException("Unknown config path: " + path);
            }
            cursor = object.get(part);
        }
        return cursor == null ? JsonNull.INSTANCE : cursor;
    }

    private void setValue(ConfigData data, String path, String rawValue) {
        switch (path) {
            case "server.display_name" -> data.server.displayName = rawValue;
            case "server.port" -> data.server.port = parsePort(path, rawValue);
            case "server.password" -> data.server.password = rawValue;
            case "network.heartbeat_interval_seconds" -> data.network.heartbeatIntervalSeconds = parsePositive(path, rawValue);
            case "network.heartbeat_timeout_seconds" -> data.network.heartbeatTimeoutSeconds = parsePositive(path, rawValue);
            case "network.reconnect_initial_delay_seconds" -> data.network.reconnectInitialDelaySeconds = parsePositive(path, rawValue);
            case "network.reconnect_max_delay_seconds" -> data.network.reconnectMaxDelaySeconds = parsePositive(path, rawValue);
            case "network.request_timeout_seconds" -> data.network.requestTimeoutSeconds = parsePositive(path, rawValue);
            case "network.inbound_enabled" -> data.network.inboundEnabled = parseBoolean(path, rawValue);
            case "cache.offline_retention_hours" -> data.cache.offlineRetentionHours = parsePositive(path, rawValue);
            case "cache.local_inventory_cache_capacity" -> data.cache.localInventoryCacheCapacity = parsePositive(path, rawValue);
            case "cache.remote_inventory_cache_capacity" -> data.cache.remoteInventoryCacheCapacity = parsePositive(path, rawValue);
            case "performance.core_threads" -> data.performance.coreThreads = parsePositive(path, rawValue);
            case "logging.retention_days" -> data.logging.retentionDays = parsePositive(path, rawValue);
            case "logging.cleanup_interval_hours" -> data.logging.cleanupIntervalHours = parsePositive(path, rawValue);
            case "container.rows" -> data.container.rows = parsePositive(path, rawValue);
            case "container.title_template" -> data.container.titleTemplate = rawValue;
            default -> throw new IllegalArgumentException("Unknown or read-only config path: " + path);
        }
    }

    private String[] splitPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Config path is empty");
        }
        return path.split("\\.");
    }

    private String valueToString(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";
        if (element instanceof JsonPrimitive primitive) {
            if (primitive.isString()) return primitive.getAsString();
            return primitive.toString();
        }
        return GSON.toJson(element);
    }

    private int parsePositive(String path, String rawValue) {
        try {
            int value = Integer.parseInt(rawValue);
            requirePositive(path, value);
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
    }

    private int parsePort(String path, String rawValue) {
        try {
            int value = Integer.parseInt(rawValue);
            requirePort(path, value);
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
    }

    private boolean parseBoolean(String path, String rawValue) {
        String value = rawValue.toLowerCase(Locale.ROOT);
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException(path + " must be true or false");
    }

    private static final class ConfigData {
        ServerData server;
        NetworkData network;
        CacheData cache;
        PerformanceData performance;
        LoggingData logging;
        ContainerData container;
        List<RemoteServerData> remoteServers;
    }

    private static final class ServerData {
        @com.google.gson.annotations.SerializedName("display_name")
        String displayName;
        int port;
        String password;
    }

    private static final class NetworkData {
        @com.google.gson.annotations.SerializedName("heartbeat_interval_seconds")
        int heartbeatIntervalSeconds;
        @com.google.gson.annotations.SerializedName("heartbeat_timeout_seconds")
        int heartbeatTimeoutSeconds;
        @com.google.gson.annotations.SerializedName("reconnect_initial_delay_seconds")
        int reconnectInitialDelaySeconds;
        @com.google.gson.annotations.SerializedName("reconnect_max_delay_seconds")
        int reconnectMaxDelaySeconds;
        @com.google.gson.annotations.SerializedName("request_timeout_seconds")
        int requestTimeoutSeconds;
        @com.google.gson.annotations.SerializedName("inbound_enabled")
        boolean inboundEnabled;
    }

    private static final class CacheData {
        @com.google.gson.annotations.SerializedName("offline_retention_hours")
        int offlineRetentionHours;
        @com.google.gson.annotations.SerializedName("local_inventory_cache_capacity")
        int localInventoryCacheCapacity;
        @com.google.gson.annotations.SerializedName("remote_inventory_cache_capacity")
        int remoteInventoryCacheCapacity;
    }

    private static final class PerformanceData {
        @com.google.gson.annotations.SerializedName("core_threads")
        int coreThreads;
    }

    private static final class LoggingData {
        @com.google.gson.annotations.SerializedName("retention_days")
        int retentionDays;
        @com.google.gson.annotations.SerializedName("cleanup_interval_hours")
        int cleanupIntervalHours;
    }

    private static final class ContainerData {
        int rows;
        @com.google.gson.annotations.SerializedName("title_template")
        String titleTemplate;
    }

    private static final class RemoteServerData {
        String name;
        String address;
        int port;
        String password;

        RemoteServerData(String name, String address, int port, String password) {
            this.name = name;
            this.address = address;
            this.port = port;
            this.password = password;
        }
    }
}
