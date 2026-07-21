package org.edtp.theexchange.service;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.CompatibilityChecker;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.OperationType;
import org.edtp.theexchange.network.protocol.messages.PutItemRequest;
import org.edtp.theexchange.network.protocol.messages.PutItemResponse;
import org.edtp.theexchange.network.protocol.messages.PlayerInventoryAccessRequest;
import org.edtp.theexchange.network.protocol.messages.PlayerInventoryAccessResponse;
import org.edtp.theexchange.network.protocol.messages.SwapItemRequest;
import org.edtp.theexchange.network.protocol.messages.SwapItemResponse;
import org.edtp.theexchange.network.protocol.messages.TakeItemRequest;
import org.edtp.theexchange.network.protocol.messages.TakeItemResponse;
import org.edtp.theexchange.storage.DatabaseManager;
import org.edtp.theexchange.storage.LocalInventoryCacheManager;
import org.edtp.theexchange.storage.LocalItemStore;
import org.edtp.theexchange.storage.OperationLogger;
import org.edtp.theexchange.storage.PlayerInventoryAuthStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInventoryAccessServiceTest {

    private static final String REQUESTER_UUID = "99999999-9999-9999-9999-999999999999";

    @TempDir
    Path tempDir;

    private final List<TestContext> contexts = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (TestContext context : contexts) {
            context.close();
        }
    }

    @Test
    void playerInventoryUsesResolvedUuidScopeAndRequiresPassword() {
        TestContext context = newContext("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        context.authStore.setPassword(scope, "Steve", "secret");

        PlayerInventoryAccessResponse wrongPassword = authenticateResponse(context, "Steve", "bad");
        assertFalse(wrongPassword.isSuccess());
        assertEquals("玩家仓库密码错误", wrongPassword.getFailReason());
        assertNull(context.localItemStore.getItem(scope, 0).item());

        InventoryAccess access = authenticate(context, "Steve", "secret");
        PutItemResponse ok = context.service.handleRemotePut(new PutItemRequest(
                0, item("minecraft:stone", 3), 0, "correct-password", "actor", "Viewer",
                access));

        assertTrue(ok.isSuccess());
        assertEquals(scope, ok.getScope());
        assertEquals("minecraft:stone", context.localItemStore.getItem(scope, 0).item().getItemId());
        assertNull(context.localItemStore.getItem(InventoryScope.server(), 0).item(),
                "player inventory writes must not fall through to the shared warehouse");
    }

    @Test
    void samePlayerNameCanMapToDifferentUuidScopesAndReuseRequestId() {
        TestContext context = newContext("11111111-1111-1111-1111-111111111111");
        InventoryScope firstScope = InventoryScope.player("11111111-1111-1111-1111-111111111111");
        InventoryScope secondScope = InventoryScope.player("22222222-2222-2222-2222-222222222222");
        context.authStore.setPassword(firstScope, "Alex", "secret");
        context.authStore.setPassword(secondScope, "Alex", "secret");

        InventoryAccess firstAccess = authenticate(context, "Alex", "secret");
        PutItemResponse first = context.service.handleRemotePut(new PutItemRequest(
                0, item("minecraft:diamond", 1), 0, "same-request-id", "actor", "Viewer",
                firstAccess));
        context.hooks.setResolvedUuid("22222222-2222-2222-2222-222222222222");
        InventoryAccess secondAccess = authenticate(context, "Alex", "secret");
        PutItemResponse second = context.service.handleRemotePut(new PutItemRequest(
                0, item("minecraft:emerald", 2), 0, "same-request-id", "actor", "Viewer",
                secondAccess));

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(firstScope, first.getScope());
        assertEquals(secondScope, second.getScope());
        assertEquals("minecraft:diamond", context.localItemStore.getItem(firstScope, 0).item().getItemId());
        assertEquals("minecraft:emerald", context.localItemStore.getItem(secondScope, 0).item().getItemId());
        assertNull(context.localItemStore.getItem(InventoryScope.server(), 0).item());
    }

    @Test
    void playerAccessResolutionFailuresDoNotWriteAnyScope() {
        TestContext context = newContext("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        InventoryScope resolvedScope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        PlayerInventoryAccessResponse emptyName = authenticateResponse(context, " ", "secret");
        assertFalse(emptyName.isSuccess());
        assertEquals("玩家名称不能为空", emptyName.getFailReason());

        context.hooks.setMissingPlayer();
        PlayerInventoryAccessResponse missingPlayer = authenticateResponse(context, "Steve", "secret");
        assertFalse(missingPlayer.isSuccess());
        assertEquals("玩家不存在或无法解析", missingPlayer.getFailReason());

        context.hooks.setResolvedUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        PlayerInventoryAccessResponse missingPassword = authenticateResponse(context, "Steve", "secret");
        assertFalse(missingPassword.isSuccess());
        assertEquals("玩家仓库尚未设置密码", missingPassword.getFailReason());

        assertNull(context.localItemStore.getItem(resolvedScope, 0).item());
        assertNull(context.localItemStore.getItem(InventoryScope.server(), 0).item());
    }

    @Test
    void playerAccessFailsWhenAuthStoreIsUnavailable() {
        TestContext context = newContext("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", false);

        PlayerInventoryAccessResponse response = authenticateResponse(context, "Steve", "secret");

        assertFalse(response.isSuccess());
        assertEquals("玩家仓库认证未初始化", response.getFailReason());
    }

    @Test
    void mutationsRejectSlotsOutsideWarehouseBounds() {
        TestContext context = newContext("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        for (int slot : new int[]{-1, ExchangeService.INVENTORY_SLOT_COUNT}) {
            PutItemResponse put = context.service.handleRemotePut(new PutItemRequest(
                    slot, item("minecraft:stone", 1), 0, "invalid-put-" + slot,
                    "actor", "Viewer", InventoryAccess.server()));
            TakeItemResponse take = context.service.handleRemoteTake(new TakeItemRequest(
                    slot, "minecraft:stone", 0, 1, "invalid-take-" + slot,
                    "actor", "Viewer", InventoryAccess.server()));
            SwapItemResponse swap = context.service.handleRemoteSwap(new SwapItemRequest(
                    slot, item("minecraft:dirt", 1), 0, "minecraft:stone", 1,
                    false, "invalid-swap-" + slot, "actor", "Viewer", InventoryAccess.server()));

            assertFalse(put.isSuccess());
            assertEquals("INVALID_SLOT", put.getFailReason());
            assertFalse(take.isSuccess());
            assertEquals("INVALID_SLOT", take.getFailReason());
            assertFalse(swap.isSuccess());
            assertEquals("INVALID_SLOT", swap.getFailReason());
        }

        assertNull(context.localItemStore.getItem(InventoryScope.server(), 0).item());
        assertNull(context.localItemStore.getItem(
                InventoryScope.server(), ExchangeService.INVENTORY_SLOT_COUNT - 1).item());
    }

    @Test
    void playerInventoryUpdatesOnlyTargetPeersWithAnActiveSession() {
        TestContext context = newContext("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        context.authStore.setPassword(scope, "Steve", "secret");

        assertTrue(context.service.canReceiveInventoryUpdate("unsubscribed", InventoryScope.server()));
        assertFalse(context.service.canReceiveInventoryUpdate("local", scope));
        assertFalse(context.service.canReceiveInventoryUpdate("another-peer", scope));

        authenticate(context, "Steve", "secret");

        assertTrue(context.service.canReceiveInventoryUpdate("local", scope));
        assertFalse(context.service.canReceiveInventoryUpdate("another-peer", scope));
    }

    @Test
    void takeAndSwapUseResolvedPlayerScope() {
        TestContext context = newContext("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        context.authStore.setPassword(scope, "Steve", "secret");
        InventoryAccess access = authenticate(context, "Steve", "secret");

        PutItemResponse putForTake = context.service.handleRemotePut(new PutItemRequest(
                0, item("minecraft:stone", 3), 0, "put-for-take", "actor", "Viewer",
                access));
        assertTrue(putForTake.isSuccess());

        TakeItemResponse take = context.service.handleRemoteTake(new TakeItemRequest(
                0, "minecraft:stone", putForTake.getNewVersion(), 2, "take-player",
                "actor", "Viewer", access));
        assertTrue(take.isSuccess());
        assertEquals(scope, take.getScope());
        assertNotNull(take.getItemsToGive());
        assertEquals(2, take.getItemsToGive().getCount());
        assertEquals(1, context.localItemStore.getItem(scope, 0).item().getCount());

        PutItemResponse putForSwap = context.service.handleRemotePut(new PutItemRequest(
                1, item("minecraft:dirt", 4), 0, "put-for-swap", "actor", "Viewer",
                access));
        assertTrue(putForSwap.isSuccess());

        SwapItemResponse swap = context.service.handleRemoteSwap(new SwapItemRequest(
                1, item("minecraft:emerald", 1), putForSwap.getNewVersion(),
                "minecraft:dirt", 4, false, "swap-player", "actor", "Viewer",
                access));
        assertTrue(swap.isSuccess());
        assertEquals(scope, swap.getScope());
        assertNotNull(swap.getTakenItem());
        assertEquals("minecraft:dirt", swap.getTakenItem().getItemId());
        assertEquals("minecraft:emerald", context.localItemStore.getItem(scope, 1).item().getItemId());
        assertNull(context.localItemStore.getItem(InventoryScope.server(), 1).item());
    }

    @Test
    void concurrentPutsToSamePlayerSlotAllowOnlyOneExpectedVersionWinner() throws Exception {
        TestContext context = newContext("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        context.authStore.setPassword(scope, "Steve", "secret");
        InventoryAccess access = authenticate(context, "Steve", "secret");
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ConcurrentLinkedQueue<PutItemResponse> responses = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        responses.add(context.service.handleRemotePut(new PutItemRequest(
                                0, item("minecraft:stone", 1), 0, "concurrent-" + index,
                                "actor-" + index, "Viewer", access)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not become ready");
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent puts timed out");
        } finally {
            pool.shutdownNow();
        }

        long successes = responses.stream().filter(PutItemResponse::isSuccess).count();
        long versionMismatches = responses.stream()
                .filter(response -> !response.isSuccess())
                .filter(response -> "VERSION_MISMATCH".equals(response.getFailReason()))
                .count();

        assertEquals(threads, responses.size());
        assertEquals(1, successes, "only one request can win expectedVersion=0 for one scope+slot");
        assertEquals(threads - 1L, versionMismatches);
        assertNotNull(context.localItemStore.getItem(scope, 0).item());
        assertEquals(1, context.localItemStore.getItem(scope, 0).item().getCount());
        assertEquals(1, context.localItemStore.getItem(scope, 0).version());
    }

    @Test
    void sameRequestIdCanCompleteConcurrentlyInDifferentScopes() throws Exception {
        TestContext context = newContext("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        InventoryScope playerScope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        context.authStore.setPassword(playerScope, "Steve", "secret");
        InventoryAccess access = authenticate(context, "Steve", "secret");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            CompletableFuture<PutItemResponse> serverFuture = CompletableFuture.supplyAsync(() -> {
                await(start);
                return context.service.handleRemotePut(new PutItemRequest(
                        0, item("minecraft:iron_ingot", 1), 0, "same-request",
                        "actor", "Viewer", InventoryAccess.server()));
            }, pool);
            CompletableFuture<PutItemResponse> playerFuture = CompletableFuture.supplyAsync(() -> {
                await(start);
                return context.service.handleRemotePut(new PutItemRequest(
                        0, item("minecraft:gold_ingot", 1), 0, "same-request",
                        "actor", "Viewer", access));
            }, pool);

            start.countDown();
            PutItemResponse server = serverFuture.get(30, TimeUnit.SECONDS);
            PutItemResponse player = playerFuture.get(30, TimeUnit.SECONDS);

            assertTrue(server.isSuccess());
            assertTrue(player.isSuccess());
            assertEquals(InventoryScope.server(), server.getScope());
            assertEquals(playerScope, player.getScope());
            assertEquals("minecraft:iron_ingot",
                    context.localItemStore.getItem(InventoryScope.server(), 0).item().getItemId());
            assertEquals("minecraft:gold_ingot",
                    context.localItemStore.getItem(playerScope, 0).item().getItemId());
        } finally {
            pool.shutdownNow();
        }
    }

    private TestContext newContext(String resolvedUuid) {
        return newContext(resolvedUuid, true);
    }

    private TestContext newContext(String resolvedUuid, boolean withAuthStore) {
        DatabaseManager db = new DatabaseManager(tempDir.resolve(resolvedUuid + ".db").toString());
        db.initialize();
        LocalItemStore localItemStore = new LocalItemStore(db);
        ItemSerializer serializer = serializer();
        LocalInventoryCacheManager cacheManager = new LocalInventoryCacheManager(localItemStore, serializer, logger(), 8);
        localItemStore.setCacheManager(cacheManager);
        OperationLogger operationLogger = new OperationLogger(tempDir.resolve("logs-" + resolvedUuid));
        PlayerInventoryAuthStore authStore = withAuthStore ? new PlayerInventoryAuthStore(db) : null;
        PlayerInventorySessionManager sessionManager = withAuthStore
                ? new PlayerInventorySessionManager(authStore) : null;
        TestHooks hooks = new TestHooks(resolvedUuid);
        ExchangeService service = new ExchangeService(null, localItemStore, operationLogger, sessionManager,
                null, new CompatibilityChecker(serializer), serializer, null, hooks, 1000);
        TestContext context = new TestContext(service, localItemStore, authStore, hooks,
                cacheManager, operationLogger, db);
        contexts.add(context);
        return context;
    }

    private static InventoryAccess authenticate(TestContext context, String ownerName, String password) {
        PlayerInventoryAccessResponse response = authenticateResponse(context, ownerName, password);
        assertTrue(response.isSuccess(), response.getFailReason());
        return InventoryAccess.playerSession(response.getOwnerName(), response.getToken(),
                REQUESTER_UUID, "Viewer", response.getScope(), response.getExpiresAt(),
                response.getSessionTtlMillis());
    }

    private static PlayerInventoryAccessResponse authenticateResponse(
            TestContext context, String ownerName, String password) {
        return context.service.handlePlayerInventoryAccess(new PlayerInventoryAccessRequest(
                "auth-" + System.nanoTime(), ownerName, password, REQUESTER_UUID, "Viewer"), "local");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static NeutralItem item(String id, int count) {
        return new NeutralItem(id, count, id, new byte[0], false, "test");
    }

    private static ItemSerializer serializer() {
        return new ItemSerializer() {
            @Override
            public NeutralItem serialize(Object itemStack) {
                return null;
            }

            @Override
            public Object deserialize(NeutralItem item) {
                return item;
            }

            @Override
            public boolean canDeserialize(NeutralItem item) {
                return true;
            }

            @Override
            public int getMaxStackSize(NeutralItem item) {
                return 64;
            }
        };
    }

    private static ExchangeAPI.Logger logger() {
        return new ExchangeAPI.Logger() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }

            @Override
            public void error(String message) {
            }

            @Override
            public void error(String message, Throwable t) {
            }
        };
    }

    private static final class TestHooks implements ExchangeService.RuntimeHooks {
        private final AtomicReference<String> resolvedUuid;

        private TestHooks(String resolvedUuid) {
            this.resolvedUuid = new AtomicReference<>(resolvedUuid);
        }

        private void setResolvedUuid(String resolvedUuid) {
            this.resolvedUuid.set(resolvedUuid);
        }

        private void setMissingPlayer() {
            this.resolvedUuid.set(null);
        }

        @Override
        public long currentGeneration() {
            return 1;
        }

        @Override
        public <T> CompletableFuture<T> submitIfGeneration(long expectedGeneration, Callable<T> task) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public ExchangeAPI.Logger logger() {
            return PlayerInventoryAccessServiceTest.logger();
        }

        @Override
        public void refreshRemoteInventoryView(String serverName) {
        }

        @Override
        public void refreshInventoryView(String serverName, InventoryScope scope) {
        }

        @Override
        public void redrawRemoteInventoryView(String serverName) {
        }

        @Override
        public void redrawInventoryView(String serverName, InventoryScope scope) {
        }

        @Override
        public void runOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public String localServerName() {
            return "local";
        }

        @Override
        public Optional<ExchangeAPI.PlayerIdentity> resolvePlayerIdentity(String playerName) {
            String uuid = resolvedUuid.get();
            return uuid != null ? Optional.of(new ExchangeAPI.PlayerIdentity(uuid, playerName)) : Optional.empty();
        }
    }

    private record TestContext(ExchangeService service,
                               LocalItemStore localItemStore,
                               PlayerInventoryAuthStore authStore,
                               TestHooks hooks,
                               LocalInventoryCacheManager cacheManager,
                               OperationLogger operationLogger,
                               DatabaseManager db) {
        private void close() {
            cacheManager.flushAll();
            operationLogger.shutdown();
            db.close();
        }
    }
}
