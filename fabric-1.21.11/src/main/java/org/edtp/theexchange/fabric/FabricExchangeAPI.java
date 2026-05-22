package org.edtp.theexchange.fabric;

import net.minecraft.server.MinecraftServer;
import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.fabric.config.FabricConfigLoader;
import org.edtp.theexchange.fabric.item.FabricItemSerializer;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

// Diff vs 26.1: getServerVersion() uses SharedConstants.getCurrentVersion().getId()
// in 1.21.11 vs server.getServerVersion() in 26.1.

public class FabricExchangeAPI implements ExchangeAPI {

    private final MinecraftServer server;
    private final Logger slf4jLogger;
    private final ExchangeAPI.Logger exchangeLogger;
    private final FabricItemSerializer itemSerializer;
    private final FabricConfigLoader configLoader;

    public FabricExchangeAPI(MinecraftServer server) {
        this.server = server;
        this.slf4jLogger = LogUtils.getLogger();
        this.exchangeLogger = new ExchangeAPI.Logger() {
            @Override public void info(String msg) { slf4jLogger.info("[Exchange] " + msg); }
            @Override public void warn(String msg) { slf4jLogger.warn("[Exchange] " + msg); }
            @Override public void error(String msg) { slf4jLogger.error("[Exchange] " + msg); }
            @Override public void error(String msg, Throwable t) { slf4jLogger.error("[Exchange] " + msg, t); }
        };
        this.itemSerializer = new FabricItemSerializer();
        this.configLoader = new FabricConfigLoader();
    }

    @Override public Logger getLogger() { return exchangeLogger; }
    @Override public ItemSerializer getItemSerializer() { return itemSerializer; }
    @Override public ConfigLoader getConfigLoader() { return configLoader; }

    @Override
    public String getServerVersion() {
        // TODO: 1.21.11 uses SharedConstants.getCurrentVersion().getId()
        return "1.21.11";
    }

    @Override public String getServerName() { return "Default Server"; }
    @Override public void runOnMainThread(Runnable task) { server.execute(task); }
    @Override public void runAsync(Runnable task) { new Thread(task, "exchange-async").start(); }

    @Override
    public void refreshRemoteInventoryView(String serverName) {
        // TODO: iterate server.getPlayerList().getPlayers()
    }

    @Override
    public void redrawRemoteInventoryView(String serverName) {
        // TODO
    }
}
