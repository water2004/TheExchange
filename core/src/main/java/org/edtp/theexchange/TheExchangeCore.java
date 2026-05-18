package org.edtp.theexchange;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.security.ConfigSanitizer;
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

    public static TheExchangeCore getInstance() {
        return instance;
    }

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

    public void initialize() {
        if (initialized) {
            api.getLogger().warn("TheExchange core already initialized");
            return;
        }
        api.getLogger().info("Initializing TheExchange core...");

        // 1. Database
        databaseManager = new DatabaseManager(api.getConfigLoader().getDatabasePath());
        databaseManager.initialize();

        // 2. Stores
        localItemStore = new LocalItemStore(databaseManager);
        remoteCacheStore = new RemoteCacheStore(databaseManager);
        operationLogger = new OperationLogger(databaseManager);
        configStore = new ConfigStore(databaseManager);

        // 3. Config & TLS
        String configDir = api.getConfigLoader().getConfigDir();
        Path keystorePath = Path.of(configDir, "tls", "keystore.jks");
        String cn = api.getServerName();
        char[] keystorePass = "theexchange".toCharArray(); // internal keystore password

        int localPort = Integer.parseInt(configStore.getOrDefault("server.port", "25566"));
        String localPassword = configStore.get("server.password");

        // 4. Network
        networkManager = new NetworkManager(localPort, keystorePath, cn, keystorePass);
        networkManager.setLocalPassword(localPassword); // TODO: use loaded config password
        networkManager.start();

        // 5. Services
        serverRegistry = new ServerRegistry(databaseManager, networkManager);
        serverRegistry.loadFromDatabase();

        cacheManager = new CacheManager(remoteCacheStore);

        syncEngine = new SyncEngine(networkManager, cacheManager);

        CompatibilityChecker compatibilityChecker = new CompatibilityChecker(
                api.getItemSerializer());

        exchangeService = new ExchangeService(networkManager, localItemStore,
                operationLogger, cacheManager, compatibilityChecker,
                api.getItemSerializer());

        // 6. Heartbeat
        heartbeatManager = new HeartbeatManager(networkManager, serverRegistry);
        heartbeatManager.start();

        // 7. Set up inbound message routing
        networkManager.setMessageRouter((type, msg) -> {
            exchangeService.routeMessage(type, msg);
        });

        instance = this;
        initialized = true;
        api.getLogger().info("TheExchange core initialized on port " + localPort);
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
