package org.edtp.theexchange.fabric.config;

import net.fabricmc.loader.api.FabricLoader;
import org.edtp.theexchange.api.ExchangeAPI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Diff vs 26.1: Same API, FabricLoader is stable across versions. No changes needed.

public class FabricConfigLoader implements ExchangeAPI.ConfigLoader {
    private static final String CONFIG_DIR = "theexchange";
    private final Path configDir = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_DIR);

    @Override public String getConfigDir() { return configDir.toAbsolutePath().toString(); }
    @Override public String getDatabasePath() { return configDir.resolve("data.db").toAbsolutePath().toString(); }
    @Override public String loadConfig() { return "{}"; }
    @Override public void saveConfig(String json) {}
}
