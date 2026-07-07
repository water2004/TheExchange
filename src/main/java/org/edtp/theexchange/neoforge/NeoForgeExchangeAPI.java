package org.edtp.theexchange.neoforge;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.api.RefreshableExchangeView;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.neoforge.config.NeoForgeConfigLoader;
import org.edtp.theexchange.neoforge.item.NeoForgeItemSerializer;
import org.slf4j.Logger;

public class NeoForgeExchangeAPI implements ExchangeAPI {

    private final MinecraftServer server;
    private final org.slf4j.Logger slf4jLogger;
    private final ExchangeAPI.Logger exchangeLogger;
    private final ItemSerializer itemSerializer;
    private final ConfigLoader configLoader;

    public NeoForgeExchangeAPI(MinecraftServer server) {
        this.server = server;
        this.slf4jLogger = LogUtils.getLogger();
        this.exchangeLogger = new ExchangeAPI.Logger() {
            @Override public void info(String msg) { slf4jLogger.info("[Exchange] " + msg); }
            @Override public void warn(String msg) { slf4jLogger.warn("[Exchange] " + msg); }
            @Override public void error(String msg) { slf4jLogger.error("[Exchange] " + msg); }
            @Override public void error(String msg, Throwable t) { slf4jLogger.error("[Exchange] " + msg, t); }
        };
        this.itemSerializer = new NeoForgeItemSerializer(server.getServerVersion(), server.registryAccess());
        this.configLoader = new NeoForgeConfigLoader();
    }

    @Override
    public ExchangeAPI.Logger getLogger() {
        return exchangeLogger;
    }

    @Override
    public ItemSerializer getItemSerializer() {return itemSerializer;}

    @Override
    public ConfigLoader getConfigLoader() {
        return configLoader;
    }

    @Override
    public String getServerVersion() {
        return server.getServerVersion();
    }

    @Override
    public String getServerName() {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core != null && core.getRuntimeConfig() != null) {
            return core.getRuntimeConfig().getDisplayName();
        }
        return "Default Server";
    }

    @Override
    public void runOnMainThread(Runnable task) {
        server.execute(task); // MinecraftServer.execute 在 NeoForge 同样存在
    }

    @Override
    public void runAsync(Runnable task) {
        Thread thread = new Thread(task, "exchange-async");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void refreshRemoteInventoryView(String serverName) {
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.containerMenu instanceof RefreshableExchangeView menu
                        && menu.isViewingServer(serverName)) {
                    menu.refreshFromCache();
                }
            }
        });
    }

    @Override
    public void redrawRemoteInventoryView(String serverName) {
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.containerMenu instanceof RefreshableExchangeView menu
                        && menu.isViewingServer(serverName)) {
                    menu.refreshFromMemory();
                }
            }
        });
    }
}