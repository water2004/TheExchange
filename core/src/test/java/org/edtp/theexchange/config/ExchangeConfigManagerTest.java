package org.edtp.theexchange.config;

import org.edtp.theexchange.api.ExchangeAPI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeConfigManagerTest {

    @Test
    void existingConfigWithoutPlayerInventorySectionDefaultsToEnabled() {
        MemoryConfigLoader loader = new MemoryConfigLoader(configWithoutPlayerInventory());

        ExchangeConfigManager manager = new ExchangeConfigManager(loader);

        assertTrue(manager.current().getPlayerInventory().isEnabled());
    }

    @Test
    void administratorCanDisablePlayerInventoriesAndReloadTheSetting() {
        MemoryConfigLoader loader = new MemoryConfigLoader(configWithoutPlayerInventory());
        ExchangeConfigManager manager = new ExchangeConfigManager(loader);

        manager.set("player_inventory.enabled", "false");
        ExchangeAPI.RuntimeConfig reloaded = manager.reload();

        assertFalse(reloaded.getPlayerInventory().isEnabled());
        assertFalse(new ExchangeConfigManager(loader).current().getPlayerInventory().isEnabled());
    }

    private static String configWithoutPlayerInventory() {
        return """
                {
                  "server": {"display_name": "Test", "port": 25566, "password": "secret"},
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
                  "performance": {"core_threads": 4},
                  "logging": {"retention_days": 30, "cleanup_interval_hours": 1},
                  "container": {"rows": 6, "title_template": "{server_name}"},
                  "remoteServers": []
                }
                """;
    }

    private static final class MemoryConfigLoader implements ExchangeAPI.ConfigLoader {
        private String json;

        private MemoryConfigLoader(String json) {
            this.json = json;
        }

        @Override
        public String getConfigDir() {
            return ".";
        }

        @Override
        public String getDatabasePath() {
            return "test.db";
        }

        @Override
        public String loadConfig() {
            return json;
        }

        @Override
        public void saveConfig(String json) {
            this.json = json;
        }
    }
}
