package org.edtp.theexchange.fabric.config;

import net.fabricmc.loader.api.FabricLoader;
import org.edtp.theexchange.api.ExchangeAPI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FabricConfigLoader implements ExchangeAPI.ConfigLoader {

    private static final String CONFIG_DIR_NAME = "theexchange";
    private static final String CONFIG_FILE_NAME = "theexchange.json";
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
        return configDir.resolve("data.db").toAbsolutePath().toString();
    }

    @Override
    public String loadConfig() {
        try {
            Files.createDirectories(configDir);
            Path file = configDir.resolve(CONFIG_FILE_NAME);
            if (Files.exists(file)) {
                return Files.readString(file);
            }
            String json = """
                    {
                      "server": {
                        "display_name": "Default Server",
                        "port": 25566,
                        "password": "changeme"
                      },
                      "network": {
                        "heartbeat_interval_seconds": 10,
                        "heartbeat_timeout_seconds": 30,
                        "reconnect_initial_delay_seconds": 5,
                        "reconnect_max_delay_seconds": 30,
                        "request_timeout_seconds": 5,
                        "inbound_enabled": false
                      },
                      "cache": {
                        "offline_retention_hours": 24,
                        "local_inventory_cache_capacity": 32,
                        "remote_inventory_cache_capacity": 64
                      },
                      "performance": {
                        "core_threads": 4
                      },
                      "logging": {
                        "retention_days": 30,
                        "cleanup_interval_hours": 1
                      },
                      "container": {
                        "rows": 6,
                        "title_template": "{server_name} 的共享空间"
                      },
                      "remoteServers": []
                    }
                    """;
            Files.writeString(file, json);
            return json;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

    @Override
    public void saveConfig(String json) {
        try {
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve(CONFIG_FILE_NAME), json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config", e);
        }
    }
}
