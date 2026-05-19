package org.edtp.theexchange;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.model.ExchangeMutationResult;
import org.edtp.theexchange.model.ExchangeViewState;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.PlayerExchangeContext;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.service.*;
import org.edtp.theexchange.storage.*;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class TheExchangeCore {

    private static TheExchangeCore instance;
    private final ExchangeAPI api;
    private final ExecutorService coreExecutor;
    private volatile boolean initialized;
    private volatile boolean shuttingDown;
    private volatile Thread coreThread;
    private CompletableFuture<Void> startupFuture;

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
    private MenuInteractionService menuInteractionService;
    private HeartbeatManager heartbeatManager;
    private ExchangeService exchangeService;

    public TheExchangeCore(ExchangeAPI api) {
        this.api = api;
        this.coreExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "exchange-core");
                thread.setDaemon(true);
                coreThread = thread;
                return thread;
            }
        });
        instance = this;
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
    public MenuInteractionService getMenuInteractionService() { return menuInteractionService; }
    public ExchangeService getExchangeService() { return exchangeService; }
    public boolean isInitialized() { return initialized; }
    public boolean isOnCoreThread() { return Thread.currentThread() == coreThread; }

    public CompletableFuture<Void> startAsync() {
        if (startupFuture != null) {
            return startupFuture;
        }
        startupFuture = submit(() -> {
            initialize();
            return null;
        });
        return startupFuture;
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        if (shuttingDown) {
            return CompletableFuture.failedFuture(new IllegalStateException("TheExchange core is shutting down"));
        }
        if (isOnCoreThread()) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        coreExecutor.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public CompletableFuture<Void> executeCore(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    public CompletableFuture<ExchangeViewState> openLocalViewAsync(String serverName) {
        return submit(() -> ExchangeViewState.local(serverName,
                localItemStore.getAllItems(),
                localItemStore.getLastModifiedTimestamp()));
    }

    public CompletableFuture<ExchangeViewState> openRemoteViewAsync(String serverName) {
        if (syncEngine == null) {
            return submit(() -> remoteFromCache(serverName, false));
        }
        return syncEngine.syncIfNeededAsync(serverName)
                .thenCompose(result -> submit(() -> result != null
                        ? ExchangeViewState.remote(serverName, result.isOnline(),
                        result.getItems(), result.getRemoteTimestamp())
                        : remoteFromCache(serverName, false)));
    }

    public CompletableFuture<Void> refreshRemoteViewAsync(String serverName) {
        if (syncEngine == null) {
            return CompletableFuture.completedFuture(null);
        }
        return syncEngine.syncIfNeededAsync(serverName).thenApply(ignored -> null);
    }

    public CompletableFuture<Void> applyLocalSnapshotAsync(java.util.List<NeutralItem> before,
                                                           java.util.List<NeutralItem> after,
                                                           PlayerExchangeContext player) {
        return executeCore(() -> menuInteractionService.applyLocalSnapshot(before, after, player));
    }

    public CompletableFuture<ExchangeMutationResult> putRemoteAsync(String serverName, int slot,
                                                                    NeutralItem item,
                                                                    PlayerExchangeContext player) {
        return submit(() -> exchangeService.putNeutralItemAsync(
                        serverName, slot, player.uuid(), player.name(), item))
                .thenCompose(future -> future)
                .thenApply(result -> result.isSuccess()
                        ? ExchangeMutationResult.success()
                        : ExchangeMutationResult.fail(result.getFailReason()));
    }

    public CompletableFuture<ExchangeMutationResult> takeRemoteAsync(String serverName, int slot,
                                                                     int count,
                                                                     PlayerExchangeContext player) {
        return submit(() -> exchangeService.takeItemAsync(
                        serverName, slot, count, player.uuid(), player.name()))
                .thenCompose(future -> future)
                .thenApply(result -> result.isSuccess()
                        ? ExchangeMutationResult.success(result.getItemsToGive())
                        : ExchangeMutationResult.fail(result.getFailReason()));
    }

    private ExchangeViewState remoteFromCache(String serverName, boolean online) {
        var cache = cacheManager.getCache(serverName);
        return ExchangeViewState.remote(serverName, online,
                cache != null ? cache.getItems() : null,
                cache != null ? cache.getRemoteTimestamp() : 0);
    }

    /**
     * Initialize with config values provided by the adapter layer.
     * This ensures config is available before database/network setup.
     */
    public void initialize(int localPort, String localPassword) {
        if (!isOnCoreThread()) {
            submit(() -> {
                initialize(localPort, localPassword);
                return null;
            }).join();
            return;
        }
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
        menuInteractionService = new MenuInteractionService(exchangeService, localItemStore);

        // 6. Heartbeat (only if network is up)
        if (networkManager != null) {
            heartbeatManager = new HeartbeatManager(networkManager, serverRegistry);
            heartbeatManager.start();
            networkManager.setMessageRouter((conn, type, msg) ->
                    exchangeService.routeMessage(conn, type, msg));
            for (var server : serverRegistry.getAllServers()) {
                if (server.isEnabled()) {
                    networkManager.connectToRemote(server);
                }
            }
        }

        initialized = true;
        api.getLogger().info("TheExchange core initialized");
    }

    public void initialize() {
        if (!isOnCoreThread()) {
            submit(() -> {
                initialize();
                return null;
            }).join();
            return;
        }
        api.getConfigLoader().loadConfig();
        ExchangeAPI.RuntimeConfig config = api.getConfigLoader().getRuntimeConfig();
        initialize(config.getPort(), config.getPassword());

        configStore.set("server.display_name", config.getDisplayName());
        configStore.set("server.password", config.getPassword());
        configStore.set("server.port", String.valueOf(config.getPort()));
        if (serverRegistry.getAllServers().isEmpty()) {
            for (var remote : config.getRemoteServers()) {
                try {
                    serverRegistry.addServer(remote.getName(), remote.getAddress(),
                            remote.getPort(), remote.getPassword());
                } catch (Exception e) {
                    api.getLogger().error("Failed to import configured server: " + remote.getName(), e);
                }
            }
        }
        api.getLogger().info("TheExchange configured. Port: " + config.getPort()
                + ", Servers: " + serverRegistry.getAllServers().size());
    }

    public void shutdown() {
        shuttingDown = true;
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        coreExecutor.execute(() -> {
            try {
                if (!initialized) {
                    stopped.complete(null);
                    return;
                }
                api.getLogger().info("Shutting down TheExchange core...");

                if (heartbeatManager != null) heartbeatManager.stop();
                if (networkManager != null) networkManager.shutdown();
                if (databaseManager != null) databaseManager.close();

                instance = null;
                initialized = false;
                api.getLogger().info("TheExchange core shut down");
                stopped.complete(null);
            } catch (Throwable t) {
                stopped.completeExceptionally(t);
            }
        });
        stopped.join();
        coreExecutor.shutdownNow();
    }
}
