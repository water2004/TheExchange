package org.edtp.theexchange;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.edtp.theexchange.neoforge.NeoForgeExchangeAPI;
import org.edtp.theexchange.neoforge.command.ExchangeCommand;
import org.slf4j.Logger;


@Mod(Theexchange.MODID)
public class Theexchange {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "theexchange";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    private TheExchangeCore core;

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Theexchange(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);


        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        // modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            LOGGER.info("[Exchange] Initializing...");
            NeoForgeExchangeAPI api = new NeoForgeExchangeAPI(server);

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

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[Exchange] Shutting down...");
        if (core != null) {
            try {
                core.shutdown();
            } catch (Exception e) {
                LOGGER.error("[Exchange] Shutdown error", e);
            }
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LOGGER.info("[Exchange] Registering commands...");
        ExchangeCommand.register(event.getDispatcher());
    }


}
