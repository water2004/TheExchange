package org.edtp.theexchange;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.edtp.theexchange.fabric.FabricExchangeAPI;
import org.edtp.theexchange.fabric.command.ExchangeCommand;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

// Diff vs 26.1: ServerLifecycleEvents.SERVER_STARTING → SERVER_STARTED may differ.
// 1.21.11 uses Fabric API's event system, same as 26.1.

public class Theexchange implements ModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private TheExchangeCore core;

    @Override
    public void onInitialize() {
        // TODO
    }
}
