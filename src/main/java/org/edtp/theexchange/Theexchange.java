package org.edtp.theexchange;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.edtp.theexchange.fabric.FabricExchangeAPI;
import org.edtp.theexchange.fabric.command.ExchangeCommand;
import org.slf4j.Logger;

public class Theexchange implements ModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private TheExchangeCore core;

    @Override
    public void onInitialize() {
        LOGGER.info("[Exchange] Registering commands...");
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ExchangeCommand.register(dispatcher);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        LOGGER.info("[Exchange] Mod loaded, waiting for server start");
    }

    private void onServerStarted(MinecraftServer server) {
        try {
            LOGGER.info("[Exchange] Initializing...");
            FabricExchangeAPI api = new FabricExchangeAPI(server);

            // Load JSON config
            api.getConfigLoader().loadConfig();
            var cfg = (org.edtp.theexchange.fabric.config.FabricConfigLoader) api.getConfigLoader();
            LOGGER.info("[Exchange] Config: name={}, port={}", cfg.getDisplayName(), cfg.getPort());

            // Initialize core with config values directly
            core = new TheExchangeCore(api);
            core.initialize(cfg.getPort(), cfg.getPassword());

            // Save config values to DB for persistence
            core.getConfigStore().set("server.display_name", cfg.getDisplayName());
            core.getConfigStore().set("server.password", cfg.getPassword());
            core.getConfigStore().set("server.port", String.valueOf(cfg.getPort()));

            // Add configured remote servers
            for (var remote : cfg.getRemoteServers()) {
                try {
                    core.getServerRegistry().addServer(
                            remote.name, remote.address, remote.port, remote.password);
                } catch (Exception e) {
                    LOGGER.error("[Exchange] Failed to add server: {}", remote.name, e);
                }
            }

            LOGGER.info("[Exchange] Ready. Port: {}, Servers: {}",
                    cfg.getPort(), cfg.getRemoteServers().size());
        } catch (Exception e) {
            LOGGER.error("[Exchange] Initialization failed!", e);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("[Exchange] Shutting down...");
        if (core != null) {
            try {
                core.shutdown();
            } catch (Exception e) {
                LOGGER.error("[Exchange] Shutdown error", e);
            }
        }
    }
}
