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

            core = new TheExchangeCore(api);
            core.startAsync().whenComplete((ignored, error) -> {
                if (error != null) {
                    LOGGER.error("[Exchange] Initialization failed!", error);
                } else {
                    LOGGER.info("[Exchange] Ready");
                }
            });
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
