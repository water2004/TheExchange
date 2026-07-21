package org.edtp.theexchange;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.config.ExchangeConfigManager;
import org.edtp.theexchange.model.ExchangeMutationResult;
import org.edtp.theexchange.model.ExchangeViewState;
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.PlayerExchangeContext;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.network.tls.PinnedPeerKeyStore;
import org.edtp.theexchange.service.CacheManager;
import org.edtp.theexchange.service.ExchangeService;
import org.edtp.theexchange.service.HeartbeatManager;
import org.edtp.theexchange.service.MenuInteractionService;
import org.edtp.theexchange.service.PlayerInventoryClientSessionStore;
import org.edtp.theexchange.service.PlayerInventorySessionManager;
import org.edtp.theexchange.service.ServerRegistry;
import org.edtp.theexchange.service.SyncEngine;
import org.edtp.theexchange.storage.DatabaseManager;
import org.edtp.theexchange.storage.LocalInventoryCacheManager;
import org.edtp.theexchange.storage.LocalItemStore;
import org.edtp.theexchange.storage.OperationLogger;
import org.edtp.theexchange.storage.PlayerInventoryAuthStore;
import org.edtp.theexchange.storage.RemoteCacheStore;

import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
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
    private PlayerInventoryAuthStore playerInventoryAuthStore;
    private PlayerInventorySessionManager playerInventorySessionManager;
    private final PlayerInventoryClientSessionStore playerInventoryClientSessionStore =
            new PlayerInventoryClientSessionStore();
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
    public PlayerInventoryAuthStore getPlayerInventoryAuthStore() { return playerInventoryAuthStore; }
    public PlayerInventorySessionManager getPlayerInventorySessionManager() { return playerInventorySessionManager; }
    public PlayerInventoryClientSessionStore getPlayerInventoryClientSessionStore() {
        return playerInventoryClientSessionStore;
    }
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
        return openLocalViewAsync(serverName, InventoryAccess.server());
    }

    public CompletableFuture<ExchangeViewState> openLocalViewAsync(String serverName, InventoryAccess access) {
        return submit(() -> {
            InventoryAccess resolvedAccess = resolvedLocalAccess(serverName, access);
            InventoryScope scope = scopeOrServer(resolvedAccess);
            return ExchangeViewState.local(serverName,
                    localItemStore.getAllItems(scope),
                    localItemStore.getLastModifiedTimestamp(scope),
                    scope,
                    resolvedAccess);
        });
    }

    public CompletableFuture<ExchangeViewState> openRemoteViewAsync(String serverName) {
        return openRemoteViewAsync(serverName, InventoryAccess.server());
    }

    public CompletableFuture<ExchangeViewState> openRemoteViewAsync(String serverName, InventoryAccess access) {
        long opGeneration = generation.get();
        return submit(() -> {
            InventoryAccess requestAccess = resolveClientAccess(serverName, access);
            boolean online = networkManager != null
                    && networkManager.getConnection(serverName) != null
                    && networkManager.getConnection(serverName).isRunning();
            if (!online || syncEngine == null) {
                return CompletableFuture.completedFuture(remoteFromCache(serverName, online, requestAccess));
            }
            return syncEngine.refreshChangedSlotsAsync(serverName, requestAccess)
                    .handle((scope, error) -> {
                        if (error != null && requestAccess.isPlayer()) {
                            invalidateRejectedAccess(serverName, requestAccess, error.getMessage());
                            throw new RuntimeException(error);
                        }
                        if (requestAccess.isPlayer()) {
                            playerInventoryClientSessionStore.touch(serverName, requestAccess);
                        }
                        return scope != null ? scope : scopeOrServer(requestAccess);
                    })
                    .thenCompose(scope -> submitIfCurrent(opGeneration,
                            () -> remoteFromCache(serverName, true,
                                    resolveClientAccess(serverName, requestAccess).withResolvedScope(scope))));
        }).thenCompose(future -> future);
    }

    public CompletableFuture<ExchangeViewState> openRemoteCachedViewAsync(String serverName) {
        return openRemoteCachedViewAsync(serverName, InventoryAccess.server());
    }

    public CompletableFuture<ExchangeViewState> openRemoteCachedViewAsync(String serverName, InventoryAccess access) {
        return submit(() -> {
            InventoryAccess requestAccess = resolveClientAccess(serverName, access);
            boolean online = networkManager != null
                    && networkManager.getConnection(serverName) != null
                    && networkManager.getConnection(serverName).isRunning();
            return remoteFromCache(serverName, online, requestAccess);
        });
    }

    public CompletableFuture<Void> refreshRemoteViewAsync(String serverName) {
        return refreshRemoteViewAsync(serverName, InventoryAccess.server());
    }

    public CompletableFuture<Void> refreshRemoteViewAsync(String serverName, InventoryAccess access) {
        long opGeneration = generation.get();
        CompletableFuture<CompletableFuture<Void>> future = submit(() -> {
            InventoryAccess requestAccess = resolveClientAccess(serverName, access);
            if (syncEngine != null) {
                return syncEngine.refreshChangedSlotsAsync(serverName, requestAccess)
                        .thenAccept(ignored -> {
                            if (requestAccess.isPlayer()) {
                                playerInventoryClientSessionStore.touch(serverName, requestAccess);
                            }
                        })
                        .exceptionallyCompose(error -> {
                            invalidateRejectedAccess(serverName, requestAccess, error.getMessage());
                            return CompletableFuture.failedFuture(error);
                        })
                        .thenCompose(ignored -> ensureCurrent(opGeneration));
            }
            if (cacheManager != null) {
                cacheManager.getCache(serverName, scopeOrServer(requestAccess));
            }
            return CompletableFuture.completedFuture(null);
        });
        return future.thenCompose(inner -> inner);
    }

    public CompletableFuture<ExchangeMutationResult> putRemoteAsync(String serverName, int slot,
                                                                    NeutralItem item,
                                                                    PlayerExchangeContext player) {
        return putRemoteAsync(serverName, slot, item, player, InventoryAccess.server());
    }

    public CompletableFuture<ExchangeMutationResult> putRemoteAsync(String serverName, int slot,
                                                                    NeutralItem item,
                                                                    PlayerExchangeContext player,
                                                                    InventoryAccess access) {
        long opGeneration = generation.get();
        return submit(() -> {
                    InventoryAccess requestAccess = resolveClientAccess(serverName, access);
                    return exchangeService.putNeutralItemAsync(
                                    serverName, slot, player.uuid(), player.name(), item, requestAccess)
                            .thenApply(result -> {
                                recordAccessResult(serverName, requestAccess,
                                        result.isSuccess(), result.getFailReason());
                                return result;
                            });
                })
                .thenCompose(future -> future)
                .thenCompose(result -> ensureCurrent(opGeneration).thenApply(ignored -> result))
                .thenApply(result -> result.isSuccess()
                        ? ExchangeMutationResult.success()
                        : ExchangeMutationResult.fail(result.getFailReason()));
    }

    public CompletableFuture<ExchangeMutationResult> takeRemoteAsync(String serverName, int slot,
                                                                     int count,
                                                                     PlayerExchangeContext player) {
        return takeRemoteAsync(serverName, slot, count, player, InventoryAccess.server());
    }

    public CompletableFuture<ExchangeMutationResult> takeRemoteAsync(String serverName, int slot,
                                                                     int count,
                                                                     PlayerExchangeContext player,
                                                                     InventoryAccess access) {
        long opGeneration = generation.get();
        return submit(() -> {
                    InventoryAccess requestAccess = resolveClientAccess(serverName, access);
                    return exchangeService.takeItemAsync(
                                    serverName, slot, count, player.uuid(), player.name(), requestAccess)
                            .thenApply(result -> {
                                recordAccessResult(serverName, requestAccess,
                                        result.isSuccess(), result.getFailReason());
                                return result;
                            });
                })
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
        return swapRemoteAsync(serverName, slot, item, expectedItemId, takeCount,
                boundedMerge, player, InventoryAccess.server());
    }

    public CompletableFuture<ExchangeMutationResult> swapRemoteAsync(String serverName, int slot,
                                                                     NeutralItem item,
                                                                     String expectedItemId,
                                                                     int takeCount,
                                                                     boolean boundedMerge,
                                                                     PlayerExchangeContext player,
                                                                     InventoryAccess access) {
        long opGeneration = generation.get();
        return submit(() -> {
                    InventoryAccess requestAccess = resolveClientAccess(serverName, access);
                    return exchangeService.swapItemAsync(
                                    serverName, slot, item, expectedItemId, takeCount,
                                    boundedMerge, player.uuid(), player.name(), requestAccess)
                            .thenApply(result -> {
                                recordAccessResult(serverName, requestAccess,
                                        result.isSuccess(), result.getFailReason());
                                return result;
                            });
                })
                .thenCompose(future -> future)
                .thenCompose(result -> ensureCurrent(opGeneration).thenApply(ignored -> result))
                .thenApply(result -> result.isSuccess()
                        ? ExchangeMutationResult.success(result.getTakenItem())
                        : ExchangeMutationResult.fail(result.getFailReason()));
    }

    public CompletableFuture<InventoryScope> setPlayerInventoryPasswordAsync(
            PlayerExchangeContext player, String password) {
        return submit(() -> {
            if (player == null || player.uuid() == null || player.uuid().isBlank()) {
                throw new IllegalArgumentException("玩家身份无效");
            }
            InventoryScope scope = InventoryScope.player(player.uuid());
            playerInventoryAuthStore.setPassword(scope, password);
            playerInventorySessionManager.revokeScope(scope);
            playerInventoryClientSessionStore.invalidateScope(scope);
            return scope;
        });
    }

    public CompletableFuture<InventoryAccess> authenticatePlayerInventoryAsync(
            String serverName, String ownerName, String password, PlayerExchangeContext requester) {
        String targetServer = canonicalTargetServerName(serverName);
        return submit(() -> exchangeService.authenticatePlayerInventoryAsync(
                        targetServer, ownerName, password, requester.uuid(), requester.name()))
                .thenCompose(future -> future)
                .thenApply(access -> playerInventoryClientSessionStore.remember(targetServer, access));
    }

    public Optional<InventoryAccess> findPlayerInventorySession(
            String serverName, String ownerName, PlayerExchangeContext requester) {
        if (requester == null) {
            return Optional.empty();
        }
        return playerInventoryClientSessionStore.findValid(
                canonicalTargetServerName(serverName), ownerName, requester.uuid());
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
        return remoteFromCache(serverName, online, InventoryAccess.server());
    }

    private ExchangeViewState remoteFromCache(String serverName, boolean online, InventoryAccess access) {
        if (access != null && access.isPlayer() && access.effectiveScope() == null) {
            throw new IllegalStateException("玩家仓库需要先连接目标服务器验证后才能查看缓存");
        }
        InventoryScope scope = scopeOrServer(access);
        var cache = cacheManager.getCache(serverName, scope);
        return ExchangeViewState.remote(serverName, online,
                cache != null ? cache.snapshot() : null,
                0,
                scope,
                access != null ? access.withResolvedScope(scope) : InventoryAccess.server());
    }

    private InventoryAccess resolvedLocalAccess(String serverName, InventoryAccess access) {
        InventoryAccess value = resolveClientAccess(serverName, access);
        if (value.isServer()) {
            return InventoryAccess.server();
        }
        var session = playerInventorySessionManager.validateAndRefresh(value.token(),
                new PlayerInventorySessionManager.AccessPrincipal(
                        runtimeConfig.getDisplayName(), value.requesterUuid()));
        if (!session.success()) {
            throw new IllegalArgumentException(session.failReason());
        }
        InventoryAccess refreshed = InventoryAccess.playerSession(session.ownerName(), value.token(),
                value.requesterUuid(), value.requesterName(), session.scope(),
                session.expiresAt(), value.sessionTtlMillis());
        playerInventoryClientSessionStore.remember(serverName, refreshed);
        return refreshed;
    }

    private InventoryAccess resolveClientAccess(String serverName, InventoryAccess access) {
        InventoryAccess value = access != null ? access : InventoryAccess.server();
        return value.isPlayer()
                ? playerInventoryClientSessionStore.resolve(canonicalTargetServerName(serverName), value)
                : InventoryAccess.server();
    }

    private void recordAccessResult(String serverName, InventoryAccess access,
                                    boolean success, String failReason) {
        if (access == null || !access.isPlayer()) {
            return;
        }
        String targetServer = canonicalTargetServerName(serverName);
        if (success) {
            playerInventoryClientSessionStore.touch(targetServer, access);
        } else {
            invalidateRejectedAccess(targetServer, access, failReason);
        }
    }

    private void invalidateRejectedAccess(String serverName, InventoryAccess access, String message) {
        if (access != null && access.isPlayer() && message != null
                && (message.contains("访问令牌无效") || message.contains("访问令牌已过期"))) {
            playerInventoryClientSessionStore.invalidate(serverName, access);
        }
    }

    private InventoryScope scopeOrServer(InventoryAccess access) {
        InventoryScope scope = access != null ? access.effectiveScope() : null;
        return scope != null ? scope : InventoryScope.server();
    }

    private String canonicalTargetServerName(String serverName) {
        String localName = runtimeConfig != null ? runtimeConfig.getDisplayName() : "local";
        return serverName == null || serverName.isBlank() || "local".equalsIgnoreCase(serverName)
                || serverName.equalsIgnoreCase(localName) ? localName : serverName;
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
        playerInventoryAuthStore = new PlayerInventoryAuthStore(databaseManager);
        playerInventorySessionManager = new PlayerInventorySessionManager(playerInventoryAuthStore);
        playerInventoryClientSessionStore.clear();

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
            playerInventorySessionManager.clear();
            playerInventoryClientSessionStore.clear();
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
                operationLogger, playerInventorySessionManager, nextCacheManager, compatibilityChecker, api.getItemSerializer(), nextSyncEngine,
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
            public void refreshInventoryView(String serverName, InventoryScope scope) {
                api.refreshInventoryView(serverName, scope);
            }

            @Override
            public void redrawRemoteInventoryView(String serverName) {
                api.redrawRemoteInventoryView(serverName);
            }

            @Override
            public void redrawInventoryView(String serverName, InventoryScope scope) {
                api.redrawInventoryView(serverName, scope);
            }

            @Override
            public void runOnMainThread(Runnable task) {
                api.runOnMainThread(task);
            }

            @Override
            public String localServerName() {
                return runtimeConfig != null ? runtimeConfig.getDisplayName() : "local";
            }

            @Override
            public Optional<ExchangeAPI.PlayerIdentity> resolvePlayerIdentity(String playerName) {
                return api.resolvePlayerIdentity(playerName);
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
                if (playerInventorySessionManager != null) playerInventorySessionManager.clear();
                playerInventoryClientSessionStore.clear();
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
