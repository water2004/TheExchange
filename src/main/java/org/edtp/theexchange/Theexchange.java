package org.edtp.theexchange;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.edtp.theexchange.fabric.FabricExchangeAPI;
import org.edtp.theexchange.fabric.command.ExchangeCommand;

public class Theexchange implements ModInitializer {

    private TheExchangeCore core;

    @Override
    public void onInitialize() {
        // Register commands early (even before server start)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ExchangeCommand.register(dispatcher);
        });

        // Initialize core when server starts
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);

        // Shutdown core when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
    }

    private void onServerStarting(MinecraftServer server) {
        try {
            FabricExchangeAPI api = new FabricExchangeAPI(server);

            // Load config first to populate the config store
            String config = api.getConfigLoader().loadConfig();
            api.getLogger().info("Configuration loaded");

            core = new TheExchangeCore(api);

            // Populate config store from JSON config
            var configLoader = (org.edtp.theexchange.fabric.config.FabricConfigLoader) api.getConfigLoader();
            core.getConfigStore().set("server.display_name", configLoader.getDisplayName());
            core.getConfigStore().set("server.password", configLoader.getPassword());
            core.getConfigStore().set("server.port", String.valueOf(configLoader.getPort()));

            core.initialize();

            // Add configured remote servers
            for (var remoteConfig : configLoader.getRemoteServers()) {
                core.getServerRegistry().addServer(
                        remoteConfig.name, remoteConfig.address,
                        remoteConfig.port, remoteConfig.password);
            }

            api.getLogger().info("TheExchange mod initialized successfully");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onServerStopping(MinecraftServer server) {
        if (core != null) {
            core.shutdown();
        }
    }
}
