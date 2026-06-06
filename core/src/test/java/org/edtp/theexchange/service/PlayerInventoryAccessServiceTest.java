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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlayerInventoryAccessServiceTest {

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

        PutItemResponse wrongPassword = context.service.handleRemotePut(new PutItemRequest(
                0, item("minecraft:stone", 3), 0, "wrong-password", "actor", "Viewer",
                InventoryAccess.player("Steve", "bad")));

        assertFalse(wrongPassword.isSuccess());
        assertEquals("玩家仓库密码错误", wrongPassword.getFailReason());
        assertNull(context.localItemStore.getItem(scope, 0).item());

        PutItemResponse ok = context.service.handleRemotePut(new PutItemRequest(
                0, item("minecraft:stone", 3), 0, "correct-password", "actor", "Viewer",
                InventoryAccess.player("Steve", "secret")));

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

        PutItemResponse first = context.service.handleRemotePut(new PutItemRequest(
                0, item("minecraft:diamond", 1), 0, "same-request-id", "actor", "Viewer",
                InventoryAccess.player("Alex", "secret")));
        context.hooks.setResolvedUuid("22222222-2222-2222-2222-222222222222");
        PutItemResponse second = context.service.handleRemotePut(new PutItemRequest(
                0, item("minecraft:emerald", 2), 0, "same-request-id", "actor", "Viewer",
                InventoryAccess.player("Alex", "secret")));

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(firstScope, first.getScope());
        assertEquals(secondScope, second.getScope());
        assertEquals("minecraft:diamond", context.localItemStore.getItem(firstScope, 0).item().getItemId());
        assertEquals("minecraft:emerald", context.localItemStore.getItem(secondScope, 0).item().getItemId());
        assertNull(context.localItemStore.getItem(InventoryScope.server(), 0).item());
    }

    private TestContext newContext(String resolvedUuid) {
        DatabaseManager db = new DatabaseManager(tempDir.resolve(resolvedUuid + ".db").toString());
        db.initialize();
        LocalItemStore localItemStore = new LocalItemStore(db);
        ItemSerializer serializer = serializer();
        LocalInventoryCacheManager cacheManager = new LocalInventoryCacheManager(localItemStore, serializer, logger(), 8);
        localItemStore.setCacheManager(cacheManager);
        OperationLogger operationLogger = new OperationLogger(tempDir.resolve("logs-" + resolvedUuid));
        PlayerInventoryAuthStore authStore = new PlayerInventoryAuthStore(db);
        TestHooks hooks = new TestHooks(resolvedUuid);
        ExchangeService service = new ExchangeService(null, localItemStore, operationLogger, authStore,
                null, new CompatibilityChecker(serializer), serializer, null, hooks, 1000);
        TestContext context = new TestContext(service, localItemStore, authStore, hooks,
                cacheManager, operationLogger, db);
        contexts.add(context);
        return context;
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
            return Optional.of(new ExchangeAPI.PlayerIdentity(resolvedUuid.get(), playerName));
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
