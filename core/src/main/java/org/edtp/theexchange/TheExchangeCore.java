package org.edtp.theexchange;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.service.*;
import org.edtp.theexchange.storage.*;

import java.nio.file.Path;

public class TheExchangeCore {

    private static TheExchangeCore instance;
    private final ExchangeAPI api;
    private boolean initialized;

    // Storage
    private DatabaseManager databaseManager;
    private LocalItemStore localItemStore;
    private RemoteCacheStore remoteCacheStore;
    private OperationLogger operationLogger;
    private ConfigStore configStore;

    // Network
    private NetworkManager networkManager;

    // Services
    private ServerRegistry serverRegistry;
    private CacheManager cacheManager;
    private SyncEngine syncEngine;
    private HeartbeatManager heartbeatManager;
    private ExchangeService exchangeService;

    public TheExchangeCore(ExchangeAPI api) {
        this.api = api;
    }

    public static TheExchangeCore getInstance() { return instance; }
    public ExchangeAPI getApi() { return api; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public LocalItemStore getLocalItemStore() { return localItemStore; }
    public RemoteCacheStore getRemoteCacheStore() { return remoteCacheStore; }
    public OperationLogger getOperationLogger() { return operationLogger; }
    public ConfigStore getConfigStore() { return configStore; }
    public NetworkManager getNetworkManager() { return networkManager; }
    public ServerRegistry getServerRegistry() { return serverRegistry; }
    public CacheManager getCacheManager() { return cacheManager; }
    public SyncEngine getSyncEngine() { return syncEngine; }
    public ExchangeService getExchangeService() { return exchangeService; }

    /**
     * Initialize with config values provided by the adapter layer.
     * This ensures config is available before database/network setup.
     */
    public void initialize(int localPort, String localPassword) {
        if (initialized) {
            api.getLogger().warn("TheExchange core already initialized");
            return;
        }
        api.getLogger().info("Initializing TheExchange core...");

        // 1. Database
        databaseManager = new DatabaseManager(api.getConfigLoader().getDatabasePath());
        databaseManager.initialize();
        api.getLogger().info("Database initialized");

        // 2. Stores
        localItemStore = new LocalItemStore(databaseManager);
        remoteCacheStore = new RemoteCacheStore(databaseManager);
        operationLogger = new OperationLogger(databaseManager);
        configStore = new ConfigStore(databaseManager);

        // 3. TLS
        String configDir = api.getConfigLoader().getConfigDir();
        Path keystorePath = Path.of(configDir, "tls", "keystore.jks");
        String cn = api.getServerName();
        char[] keystorePass = "theexchange".toCharArray();

        // 4. Network
        try {
            networkManager = new NetworkManager(localPort, keystorePath, cn, keystorePass);
            networkManager.setLocalServerName(api.getServerName());
            networkManager.setLocalPassword(localPassword);
            networkManager.start();
            api.getLogger().info("Network started on port " + localPort);
        } catch (Exception e) {
            String cause = e.getMessage();
            if (e.getCause() != null) cause = e.getCause().getMessage();
            if (cause != null && cause.contains("Address already in use")) {
                api.getLogger().error("Port " + localPort + " is already in use — "
                        + "change 'server.port' in config/theexchange/theexchange.json");
            } else if (cause != null && cause.contains("Permission denied")) {
                api.getLogger().error("No permission to bind port " + localPort
                        + " (ports < 1024 need root on Linux)");
            } else {
                api.getLogger().error("Failed to start network on port " + localPort
                        + ": " + (cause != null ? cause : "unknown"), e);
            }
            networkManager = null;
        }

        // 5. Services (work even without network)
        serverRegistry = new ServerRegistry(databaseManager, networkManager);
        serverRegistry.loadFromDatabase();

        cacheManager = new CacheManager(remoteCacheStore);

        syncEngine = networkManager != null
                ? new SyncEngine(networkManager, cacheManager)
                : null;

        CompatibilityChecker compatibilityChecker = new CompatibilityChecker(
                api.getItemSerializer());

        exchangeService = new ExchangeService(networkManager, localItemStore,
                operationLogger, cacheManager, compatibilityChecker,
                api.getItemSerializer());

        // 6. Heartbeat (only if network is up)
        if (networkManager != null) {
            heartbeatManager = new HeartbeatManager(networkManager, serverRegistry);
            heartbeatManager.start();
            networkManager.setMessageRouter((conn, type, msg) ->
                    exchangeService.routeMessage(conn, type, msg));
        }

        instance = this;
        initialized = true;
        api.getLogger().info("TheExchange core initialized");
    }

    public void shutdown() {
        if (!initialized) return;
        api.getLogger().info("Shutting down TheExchange core...");

        if (heartbeatManager != null) heartbeatManager.stop();
        if (networkManager != null) networkManager.shutdown();
        if (databaseManager != null) databaseManager.close();

        instance = null;
        initialized = false;
        api.getLogger().info("TheExchange core shut down");
    }
}
