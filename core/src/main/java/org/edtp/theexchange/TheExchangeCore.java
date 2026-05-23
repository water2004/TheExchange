package org.edtp.theexchange;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.config.ExchangeConfigManager;
import org.edtp.theexchange.model.ExchangeMutationResult;
import org.edtp.theexchange.model.ExchangeViewState;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.PlayerExchangeContext;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.tls.PinnedPeerKeyStore;
import org.edtp.theexchange.service.CacheManager;
import org.edtp.theexchange.service.ExchangeService;
import org.edtp.theexchange.service.HeartbeatManager;
import org.edtp.theexchange.service.MenuInteractionService;
import org.edtp.theexchange.service.ServerRegistry;
import org.edtp.theexchange.service.SyncEngine;
import org.edtp.theexchange.storage.DatabaseManager;
import org.edtp.theexchange.storage.LocalInventoryCacheManager;
import org.edtp.theexchange.storage.LocalItemStore;
import org.edtp.theexchange.storage.OperationLogger;
import org.edtp.theexchange.storage.RemoteCacheStore;

import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TheExchangeCore {

    private static TheExchangeCore instance;

    private final ExchangeAPI api;
    private final ExecutorService lifecycleExecutor;
    private final AtomicLong generation = new AtomicLong();
    private final Object taskMonitor = new Object();
    private final Object executorLock = new Object();
    private volatile ExecutorService coreExecutor;
    private volatile boolean acceptingTasks;
    private volatile boolean reloading;
    private int inFlightTasks;

    private volatile boolean initialized;
    private volatile boolean shuttingDown;
    private CompletableFuture<Void> startupFuture;
    private ExchangeConfigManager configManager;
    private ExchangeAPI.RuntimeConfig runtimeConfig;
    private PinnedPeerKeyStore pinnedPeerKeyStore;

    private DatabaseManager databaseManager;
    private LocalItemStore localItemStore;
    private RemoteCacheStore remoteCacheStore;
    private OperationLogger operationLogger;
    private LocalInventoryCacheManager localInventoryCacheManager;

    private NetworkManager networkManager;
    private ServerRegistry serverRegistry;
    private CacheManager cacheManager;
    private SyncEngine syncEngine;
    private MenuInteractionService menuInteractionService;
    private HeartbeatManager heartbeatManager;
    private ExchangeService exchangeService;

    public TheExchangeCore(ExchangeAPI api) {
        this.api = api;
        this.lifecycleExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("exchange-core-lifecycle"));
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + index.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    public static TheExchangeCore getInstance() { return instance; }
    public ExchangeAPI getApi() { return api; }
    public ExchangeConfigManager getConfigManager() { return configManager; }
    public ExchangeAPI.RuntimeConfig getRuntimeConfig() { return runtimeConfig; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public LocalItemStore getLocalItemStore() { return localItemStore; }
    public RemoteCacheStore getRemoteCacheStore() { return remoteCacheStore; }
    public OperationLogger getOperationLogger() { return operationLogger; }
    public LocalInventoryCacheManager getLocalInventoryCacheManager() { return localInventoryCacheManager; }
    public NetworkManager getNetworkManager() { return networkManager; }
    public ServerRegistry getServerRegistry() { return serverRegistry; }
    public CacheManager getCacheManager() { return cacheManager; }
    public SyncEngine getSyncEngine() { return syncEngine; }
    public MenuInteractionService getMenuInteractionService() { return menuInteractionService; }
    public ExchangeService getExchangeService() { return exchangeService; }
    public boolean isInitialized() { return initialized; }

    public CompletableFuture<Void> startAsync() {
        CompletableFuture<Void> future;
        synchronized (this) {
            if (startupFuture != null) return startupFuture;
            startupFuture = new CompletableFuture<>();
            future = startupFuture;
        }
        lifecycleExecutor.execute(() -> {
            try {
                initialize();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        if (shuttingDown) {
            return CompletableFuture.failedFuture(new IllegalStateException("TheExchange core is shutting down"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        long taskGeneration = generation.get();
        ExecutorService executor = coreExecutor;
        if (executor == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("TheExchange core executor is not running"));
        }
        synchronized (taskMonitor) {
            if (!acceptingTasks || reloading) {
                return CompletableFuture.failedFuture(new IllegalStateException("Exchange runtime reloading"));
            }
            inFlightTasks++;
        }
        try {
            executor.execute(() -> {
                try {
                    if (taskGeneration != generation.get()) {
                        future.completeExceptionally(new IllegalStateException("Exchange runtime reloaded; operation interrupted"));
                        return;
                    }
                    future.complete(task.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                } finally {
                    completeTask();
                }
            });
        } catch (RejectedExecutionException e) {
            completeTask();
            return CompletableFuture.failedFuture(e);
        }
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
        long opGeneration = generation.get();
        return submit(() -> {
            boolean online = networkManager != null
                    && networkManager.getConnection(serverName) != null
                    && networkManager.getConnection(serverName).isRunning();
            if (!online || syncEngine == null) {
                return CompletableFuture.completedFuture(remoteFromCache(serverName, online));
            }
            return syncEngine.refreshChangedSlotsAsync(serverName)
                    .handle((ignored, error) -> null)
                    .thenCompose(ignored -> submitIfCurrent(opGeneration, () -> remoteFromCache(serverName, true)));
        }).thenCompose(future -> future);
    }

    public CompletableFuture<ExchangeViewState> openRemoteCachedViewAsync(String serverName) {
        return submit(() -> {
            boolean online = networkManager != null
                    && networkManager.getConnection(serverName) != null
                    && networkManager.getConnection(serverName).isRunning();
            return remoteFromCache(serverName, online);
        });
    }

    public CompletableFuture<Void> refreshRemoteViewAsync(String serverName) {
        long opGeneration = generation.get();
        CompletableFuture<CompletableFuture<Void>> future = submit(() -> {
            if (syncEngine != null) {
                return syncEngine.refreshChangedSlotsAsync(serverName)
                        .thenCompose(ignored -> ensureCurrent(opGeneration));
            }
            if (cacheManager != null) {
                cacheManager.getCache(serverName);
            }
            return CompletableFuture.completedFuture(null);
        });
        return future.thenCompose(inner -> inner);
    }

    public CompletableFuture<ExchangeMutationResult> putRemoteAsync(String serverName, int slot,
                                                                    NeutralItem item,
                                                                    PlayerExchangeContext player) {
        long opGeneration = generation.get();
        return submit(() -> exchangeService.putNeutralItemAsync(
                        serverName, slot, player.uuid(), player.name(), item))
                .thenCompose(future -> future)
                .thenCompose(result -> ensureCurrent(opGeneration).thenApply(ignored -> result))
                .thenApply(result -> result.isSuccess()
                        ? ExchangeMutationResult.success()
                        : ExchangeMutationResult.fail(result.getFailReason()));
    }

    public CompletableFuture<ExchangeMutationResult> takeRemoteAsync(String serverName, int slot,
                                                                     int count,
                                                                     PlayerExchangeContext player) {
        long opGeneration = generation.get();
        return submit(() -> exchangeService.takeItemAsync(
                        serverName, slot, count, player.uuid(), player.name()))
                .thenCompose(future -> future)
                .thenCompose(result -> ensureCurrent(opGeneration).thenApply(ignored -> result))
                .thenApply(result -> result.isSuccess()
                        ? ExchangeMutationResult.success(result.getItemsToGive())
                        : ExchangeMutationResult.fail(result.getFailReason()));
    }

    public CompletableFuture<ExchangeMutationResult> swapRemoteAsync(String serverName, int slot,
                                                                     NeutralItem item,
                                                                     String expectedItemId,
                                                                     int takeCount,
                                                                     boolean boundedMerge,
                                                                     PlayerExchangeContext player) {
        long opGeneration = generation.get();
        return submit(() -> exchangeService.swapItemAsync(
                        serverName, slot, item, expectedItemId, takeCount,
                        boundedMerge, player.uuid(), player.name()))
                .thenCompose(future -> future)
                .thenCompose(result -> ensureCurrent(opGeneration).thenApply(ignored -> result))
                .thenApply(result -> result.isSuccess()
                        ? ExchangeMutationResult.success(result.getTakenItem())
                        : ExchangeMutationResult.fail(result.getFailReason()));
    }

    public CompletableFuture<ExchangeAPI.RuntimeConfig> reloadConfigAsync() {
        if (shuttingDown) {
            return CompletableFuture.failedFuture(new IllegalStateException("TheExchange core is shutting down"));
        }
        CompletableFuture<ExchangeAPI.RuntimeConfig> future = new CompletableFuture<>();
        lifecycleExecutor.execute(() -> {
            try {
                reloadConfigInternal();
                future.complete(runtimeConfig);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public long currentGeneration() {
        return generation.get();
    }

    public <T> CompletableFuture<T> submitIfGeneration(long expectedGeneration, Callable<T> task) {
        if (expectedGeneration != generation.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Exchange runtime reloaded; operation interrupted"));
        }
        return submit(task);
    }

    private <T> CompletableFuture<T> submitIfCurrent(long expectedGeneration, Callable<T> task) {
        return submitIfGeneration(expectedGeneration, task);
    }

    private CompletableFuture<Void> ensureCurrent(long expectedGeneration) {
        if (expectedGeneration != generation.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Exchange runtime reloaded; operation interrupted"));
        }
        return CompletableFuture.completedFuture(null);
    }

    private ExchangeViewState remoteFromCache(String serverName, boolean online) {
        var cache = cacheManager.getCache(serverName);
        return ExchangeViewState.remote(serverName, online,
                cache != null ? cache.snapshot() : null,
                0);
    }

    private void initialize() {
        if (initialized) {
            api.getLogger().warn("TheExchange core already initialized");
            return;
        }
        api.getLogger().info("Initializing TheExchange core...");
        configManager = new ExchangeConfigManager(api.getConfigLoader());
        runtimeConfig = configManager.current();
        startCoreExecutor(runtimeConfig);

        databaseManager = new DatabaseManager(api.getConfigLoader().getDatabasePath());
        databaseManager.initialize();
        api.getLogger().info("Database initialized");

        localItemStore = new LocalItemStore(databaseManager);
        remoteCacheStore = new RemoteCacheStore(databaseManager);
        operationLogger = new OperationLogger(Path.of(api.getConfigLoader().getConfigDir(), "logs"));

        RuntimeBundle initialRuntime = buildRuntime(runtimeConfig, null);
        publishRuntime(initialRuntime);
        activateRuntime(initialRuntime, runtimeConfig);

        instance = this;
        initialized = true;
        api.getLogger().info("TheExchange configured. Port: " + runtimeConfig.getPort()
                + ", inbound=" + runtimeConfig.getNetwork().isInboundEnabled()
                + ", Servers: " + serverRegistry.getAllServers().size());
        api.getLogger().info("TheExchange core initialized");
    }

    private void reloadConfigInternal() {
        api.getLogger().info("Reloading TheExchange config...");
        ExchangeAPI.RuntimeConfig oldConfig = runtimeConfig;
        RuntimeBundle oldRuntime = currentRuntimeBundle();
        ExecutorService oldExecutor;
        synchronized (executorLock) {
            oldExecutor = coreExecutor;
        }
        RuntimeBundle newRuntime = null;
        ExecutorService newExecutor = null;
        try {
            beginReload();
            ExchangeAPI.RuntimeConfig reloaded = configManager.reload();
            boolean reuseNetwork = oldRuntime.networkManager != null
                    && oldConfig != null
                    && oldConfig.getPort() == reloaded.getPort();
            stopHeartbeat(oldRuntime.heartbeatManager);
            newExecutor = newCoreExecutor(reloaded);
            generation.incrementAndGet();
            runtimeConfig = reloaded;
            newRuntime = buildRuntime(reloaded, reuseNetwork ? oldRuntime.networkManager : null);
            synchronized (executorLock) {
                coreExecutor = newExecutor;
            }
            publishRuntime(newRuntime);
            activateRuntime(newRuntime, reloaded);
            if (!reuseNetwork) {
                shutdownNetwork(oldRuntime.networkManager);
            }
            shutdownRuntimeCaches(oldRuntime);
            shutdownExecutor(oldExecutor);
            api.getLogger().info("TheExchange config reloaded. Port: " + reloaded.getPort()
                    + ", inbound=" + reloaded.getNetwork().isInboundEnabled()
                    + ", Servers: " + serverRegistry.getAllServers().size());
        } catch (Throwable t) {
            runtimeConfig = oldConfig;
            restoreNetworkConfig(oldRuntime.networkManager, oldConfig);
            RuntimeBundle restored = withRestartedHeartbeat(oldRuntime, oldConfig);
            synchronized (executorLock) {
                coreExecutor = oldExecutor;
            }
            publishRuntime(restored);
            if (newRuntime != null) {
                shutdownFailedReloadRuntime(newRuntime, oldRuntime);
            }
            shutdownExecutor(newExecutor);
            api.getLogger().error("TheExchange config reload failed; keeping previous runtime", t);
            throw t;
        } finally {
            endReload();
        }
    }

    private RuntimeBundle buildRuntime(ExchangeAPI.RuntimeConfig config, NetworkManager reusableNetworkManager) {
        Path pinnedPeerKeysPath = Path.of(api.getConfigLoader().getConfigDir(), "tls", "known-peers.properties");
        if (pinnedPeerKeyStore == null) {
            pinnedPeerKeyStore = new PinnedPeerKeyStore(pinnedPeerKeysPath);
        }
        pruneRemoteState(config);

        LocalInventoryCacheManager nextLocalInventoryCacheManager = new LocalInventoryCacheManager(
                localItemStore, api.getItemSerializer(), api.getLogger(),
                config.getCache().getLocalInventoryCacheCapacity());
        CacheManager nextCacheManager = new CacheManager(remoteCacheStore, api.getLogger(),
                config.getCache().getRemoteInventoryCacheCapacity());

        Path keystorePath = Path.of(api.getConfigLoader().getConfigDir(), "tls", "keystore.jks");
        NetworkManager nextNetworkManager = reusableNetworkManager;
        if (nextNetworkManager == null) {
            try {
                nextNetworkManager = new NetworkManager(config.getPort(), keystorePath, pinnedPeerKeyStore,
                        config.getDisplayName(), "theexchange".toCharArray(), api.getServerVersion());
            } catch (Exception e) {
                logNetworkStartFailure(config.getPort(), e);
                nextNetworkManager = null;
            }
        }
        if (nextNetworkManager != null) {
            nextNetworkManager.setLocalServerName(config.getDisplayName());
            nextNetworkManager.setLocalPassword(config.getPassword());
            nextNetworkManager.setOnlineHandler(serverName ->
                    api.runOnMainThread(() -> api.refreshRemoteInventoryView(serverName)));
        }

        ServerRegistry nextServerRegistry = new ServerRegistry(nextNetworkManager, config.getRemoteServers());
        CompatibilityChecker compatibilityChecker = new CompatibilityChecker(api.getItemSerializer());
        SyncEngine nextSyncEngine = nextNetworkManager != null
                ? new SyncEngine(nextNetworkManager, nextCacheManager, compatibilityChecker)
                : null;
        ExchangeService nextExchangeService = new ExchangeService(nextNetworkManager, localItemStore,
                operationLogger, nextCacheManager, compatibilityChecker, api.getItemSerializer(), nextSyncEngine,
                exchangeServiceHooks(), requestTimeoutMs(config));
        MenuInteractionService nextMenuInteractionService = new MenuInteractionService(nextExchangeService);

        HeartbeatManager nextHeartbeatManager = null;
        if (nextNetworkManager != null) {
            nextNetworkManager.setMessageRouter((conn, type, msg) ->
                    submit(() -> {
                        ExchangeService service = exchangeService;
                        if (service != null) {
                            service.routeMessage(conn, type, msg);
                        }
                        return null;
                    }).whenComplete((ignored, error) -> {
                        if (error != null) {
                            api.getLogger().warn("Dropped inbound exchange message during reload: " + error.getMessage());
                        }
            }));
            nextHeartbeatManager = new HeartbeatManager(nextNetworkManager, nextServerRegistry, config.getNetwork());
        }
        return new RuntimeBundle(nextLocalInventoryCacheManager, nextNetworkManager, nextServerRegistry,
                nextCacheManager, nextSyncEngine, nextMenuInteractionService, nextHeartbeatManager,
                nextExchangeService);
    }

    private ExchangeService.RuntimeHooks exchangeServiceHooks() {
        return new ExchangeService.RuntimeHooks() {
            @Override
            public long currentGeneration() {
                return TheExchangeCore.this.currentGeneration();
            }

            @Override
            public <T> CompletableFuture<T> submitIfGeneration(long expectedGeneration, Callable<T> task) {
                return TheExchangeCore.this.submitIfGeneration(expectedGeneration, task);
            }

            @Override
            public ExchangeAPI.Logger logger() {
                return api.getLogger();
            }

            @Override
            public void refreshRemoteInventoryView(String serverName) {
                api.refreshRemoteInventoryView(serverName);
            }

            @Override
            public void redrawRemoteInventoryView(String serverName) {
                api.redrawRemoteInventoryView(serverName);
            }

            @Override
            public void runOnMainThread(Runnable task) {
                api.runOnMainThread(task);
            }

            @Override
            public String localServerName() {
                return runtimeConfig != null ? runtimeConfig.getDisplayName() : "local";
            }
        };
    }

    private long requestTimeoutMs(ExchangeAPI.RuntimeConfig config) {
        return TimeUnit.SECONDS.toMillis(config.getNetwork().getRequestTimeoutSeconds());
    }

    private void pruneRemoteState(ExchangeAPI.RuntimeConfig config) {
        Set<String> configuredServers = config.getRemoteServers().stream()
                .map(ExchangeAPI.RemoteServerConfig::getName)
                .collect(java.util.stream.Collectors.toSet());
        remoteCacheStore.retainOnlyServers(configuredServers);
        try {
            pinnedPeerKeyStore.retainOnly(configuredServers);
        } catch (IOException e) {
            throw new RuntimeException("Failed to prune pinned peer keys", e);
        }
    }

    private void applyInboundConfig(NetworkManager manager, ExchangeAPI.RuntimeConfig config) {
        if (manager == null) {
            return;
        }
        if (config.getNetwork().isInboundEnabled()) {
            manager.startInbound();
            api.getLogger().info("Inbound Exchange listener started on port " + config.getPort());
        } else {
            manager.stopInbound();
            api.getLogger().info("Inbound Exchange listener disabled");
        }
    }

    private void stopRuntimeServices(boolean flush) {
        RuntimeBundle current = currentRuntimeBundle();
        if (flush) {
            shutdownRuntimeCaches(current);
        }
        stopHeartbeat(current.heartbeatManager);
        shutdownNetwork(current.networkManager);
        publishRuntime(new RuntimeBundle(null, null, null, null, null, null, null, null));
        stopCoreExecutor();
    }

    private void publishRuntime(RuntimeBundle runtime) {
        localInventoryCacheManager = runtime.localInventoryCacheManager;
        localItemStore.setCacheManager(runtime.localInventoryCacheManager);
        networkManager = runtime.networkManager;
        serverRegistry = runtime.serverRegistry;
        cacheManager = runtime.cacheManager;
        syncEngine = runtime.syncEngine;
        menuInteractionService = runtime.menuInteractionService;
        heartbeatManager = runtime.heartbeatManager;
        exchangeService = runtime.exchangeService;
    }

    private RuntimeBundle currentRuntimeBundle() {
        return new RuntimeBundle(localInventoryCacheManager, networkManager, serverRegistry, cacheManager,
                syncEngine, menuInteractionService, heartbeatManager, exchangeService);
    }

    private void activateRuntime(RuntimeBundle runtime, ExchangeAPI.RuntimeConfig config) {
        if (runtime == null || runtime.networkManager == null) {
            return;
        }
        applyInboundConfig(runtime.networkManager, config);
        runtime.networkManager.disconnectOutboundNotIn(config.getRemoteServers().stream()
                .map(ExchangeAPI.RemoteServerConfig::getName)
                .collect(java.util.stream.Collectors.toSet()));
        if (runtime.heartbeatManager != null) {
            runtime.heartbeatManager.start();
        }
        if (runtime.serverRegistry != null) {
            runtime.serverRegistry.connectAllEnabled();
        }
    }

    private RuntimeBundle withRestartedHeartbeat(RuntimeBundle runtime, ExchangeAPI.RuntimeConfig config) {
        if (runtime == null || runtime.networkManager == null || runtime.serverRegistry == null || config == null) {
            return runtime;
        }
        HeartbeatManager restarted = new HeartbeatManager(runtime.networkManager, runtime.serverRegistry,
                config.getNetwork());
        restarted.start();
        return new RuntimeBundle(runtime.localInventoryCacheManager, runtime.networkManager,
                runtime.serverRegistry, runtime.cacheManager, runtime.syncEngine,
                runtime.menuInteractionService, restarted, runtime.exchangeService);
    }

    private void restoreNetworkConfig(NetworkManager manager, ExchangeAPI.RuntimeConfig config) {
        if (manager == null || config == null) {
            return;
        }
        manager.setLocalServerName(config.getDisplayName());
        manager.setLocalPassword(config.getPassword());
        manager.setOnlineHandler(serverName ->
                api.runOnMainThread(() -> api.refreshRemoteInventoryView(serverName)));
        manager.setMessageRouter((conn, type, msg) ->
                submit(() -> {
                    ExchangeService service = exchangeService;
                    if (service != null) {
                        service.routeMessage(conn, type, msg);
                    }
                    return null;
                }).whenComplete((ignored, error) -> {
                    if (error != null) {
                        api.getLogger().warn("Dropped inbound exchange message during reload: " + error.getMessage());
                    }
                }));
        applyInboundConfig(manager, config);
        manager.disconnectOutboundNotIn(config.getRemoteServers().stream()
                .map(ExchangeAPI.RemoteServerConfig::getName)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private void stopHeartbeat(HeartbeatManager manager) {
        if (manager != null) {
            manager.stop();
        }
    }

    private void shutdownNetwork(NetworkManager manager) {
        if (manager != null) {
            manager.shutdown();
        }
    }

    private void shutdownRuntimeCaches(RuntimeBundle runtime) {
        if (runtime == null) {
            return;
        }
        if (runtime.localInventoryCacheManager != null) {
            runtime.localInventoryCacheManager.flushAll();
        }
        if (runtime.cacheManager != null) {
            runtime.cacheManager.shutdown();
        }
    }

    private void shutdownRuntimeServices(RuntimeBundle runtime) {
        if (runtime == null) {
            return;
        }
        stopHeartbeat(runtime.heartbeatManager);
        shutdownNetwork(runtime.networkManager);
        shutdownRuntimeCaches(runtime);
    }

    private void shutdownFailedReloadRuntime(RuntimeBundle failedRuntime, RuntimeBundle oldRuntime) {
        if (failedRuntime == null) {
            return;
        }
        stopHeartbeat(failedRuntime.heartbeatManager);
        if (oldRuntime == null || failedRuntime.networkManager != oldRuntime.networkManager) {
            shutdownNetwork(failedRuntime.networkManager);
        }
        shutdownRuntimeCaches(failedRuntime);
    }

    private void logNetworkStartFailure(int port, Exception e) {
        String cause = e.getMessage();
        if (e.getCause() != null) cause = e.getCause().getMessage();
        if (cause != null && cause.contains("Address already in use")) {
            api.getLogger().error("Port " + port + " is already in use; change server.port and run /exchange config reload");
        } else if (cause != null && cause.contains("Permission denied")) {
            api.getLogger().error("No permission to bind port " + port + " (ports < 1024 need root on Linux)");
        } else {
            api.getLogger().error("Failed to start network on port " + port + ": "
                    + (cause != null ? cause : "unknown"), e);
        }
    }

    public void shutdown() {
        shuttingDown = true;
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        lifecycleExecutor.execute(() -> {
            try {
                api.getLogger().info("Shutting down TheExchange core...");
                stopRuntimeServices(true);
                if (operationLogger != null) operationLogger.shutdown();
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
        lifecycleExecutor.shutdownNow();
    }

    private void beginReload() {
        synchronized (taskMonitor) {
            reloading = true;
            acceptingTasks = false;
        }
        waitForTasksToDrain();
    }

    private void endReload() {
        synchronized (taskMonitor) {
            reloading = false;
            acceptingTasks = true;
        }
    }

    private void waitForTasksToDrain() {
        synchronized (taskMonitor) {
            while (inFlightTasks > 0) {
                try {
                    taskMonitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for core tasks to drain", e);
                }
            }
        }
    }

    private void completeTask() {
        synchronized (taskMonitor) {
            if (inFlightTasks > 0) {
                inFlightTasks--;
            }
            if (inFlightTasks == 0) {
                taskMonitor.notifyAll();
            }
        }
    }

    private void startCoreExecutor(ExchangeAPI.RuntimeConfig config) {
        synchronized (executorLock) {
            coreExecutor = newCoreExecutor(config);
        }
        synchronized (taskMonitor) {
            acceptingTasks = true;
            reloading = false;
        }
    }

    private void stopCoreExecutor() {
        ExecutorService oldExecutor;
        synchronized (executorLock) {
            oldExecutor = coreExecutor;
            coreExecutor = null;
        }
        shutdownExecutor(oldExecutor);
    }

    private ExecutorService newCoreExecutor(ExchangeAPI.RuntimeConfig config) {
        int configured = config != null && config.getPerformance() != null
                ? config.getPerformance().getCoreThreads()
                : 4;
        int threads = Math.max(1, Math.min(configured, Runtime.getRuntime().availableProcessors()));
        AtomicLong index = new AtomicLong();
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "exchange-core-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private record RuntimeBundle(LocalInventoryCacheManager localInventoryCacheManager,
                                 NetworkManager networkManager,
                                 ServerRegistry serverRegistry,
                                 CacheManager cacheManager,
                                 SyncEngine syncEngine,
                                 MenuInteractionService menuInteractionService,
                                 HeartbeatManager heartbeatManager,
                                 ExchangeService exchangeService) {
    }
}
