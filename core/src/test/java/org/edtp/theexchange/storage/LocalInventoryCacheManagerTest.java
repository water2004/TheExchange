package org.edtp.theexchange.storage;

import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalInventoryCacheManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void activeMutationCannotBeEvictedAndReplacedByAStaleCache() throws Exception {
        DatabaseManager database = new DatabaseManager(tempDir.resolve("exchange.db").toString());
        database.initialize();
        LocalItemStore store = new LocalItemStore(database);
        BlockingSerializer serializer = new BlockingSerializer();
        LocalInventoryCacheManager manager = new LocalInventoryCacheManager(
                store, serializer, null, 1);
        store.setCacheManager(manager);
        InventoryScope first = InventoryScope.player("11111111-1111-1111-1111-111111111111");
        InventoryScope second = InventoryScope.player("22222222-2222-2222-2222-222222222222");

        try {
            manager.getOrLoad(first);
            serializer.blockNextCall();
            CompletableFuture<LocalItemStore.PutResult> mutation = CompletableFuture.supplyAsync(() ->
                    manager.put(first, 0, item(), 0, "test"));
            assertTrue(serializer.awaitBlocked(), "mutation did not reach item normalization");

            manager.getOrLoad(second);
            manager.getOrLoad(first);
            serializer.release();

            assertTrue(mutation.get(5, TimeUnit.SECONDS).isSuccess());
            NeutralItem visible = manager.get(first, 0);
            assertNotNull(visible,
                    "a successful mutation must remain visible through the manager after concurrent LRU eviction");
            assertEquals("minecraft:stone", visible.getItemId());
        } finally {
            serializer.release();
            manager.flushAll();
            database.close();
        }
    }

    private static NeutralItem item() {
        return new NeutralItem("minecraft:stone", 1, "Stone", new byte[0], false, "test");
    }

    private static final class BlockingSerializer implements ItemSerializer {
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private volatile CountDownLatch blocked = new CountDownLatch(0);
        private volatile CountDownLatch released = new CountDownLatch(0);

        private void blockNextCall() {
            blocked = new CountDownLatch(1);
            released = new CountDownLatch(1);
            blockNext.set(true);
        }

        private boolean awaitBlocked() throws InterruptedException {
            return blocked.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }

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
            if (blockNext.compareAndSet(true, false)) {
                blocked.countDown();
                try {
                    if (!released.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test serializer was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            return 64;
        }
    }
}
