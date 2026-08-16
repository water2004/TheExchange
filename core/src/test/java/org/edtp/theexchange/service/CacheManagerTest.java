package org.edtp.theexchange.service;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.storage.RemoteCacheStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheManagerTest {
    @Test
    void scopeCannotReloadAStaleSnapshotWhileItsEvictionIsBeingFlushed() throws Exception {
        BlockingRemoteCacheStore store = new BlockingRemoteCacheStore();
        CacheManager manager = new CacheManager(store, null, 1);
        try {
            manager.updateCacheSlot("first", InventoryScope.server(), 0, item(), 1);
            store.blockNextSave();

            CompletableFuture<?> eviction = CompletableFuture.runAsync(() ->
                    manager.getCache("second", InventoryScope.server()));
            assertTrue(store.awaitBlocked(), "eviction did not begin persistence");

            CompletableFuture<?> reload = CompletableFuture.runAsync(() ->
                    manager.getCache("first", InventoryScope.server()));
            Thread.sleep(50L);
            assertFalse(reload.isDone(),
                    "the evicted scope must not be reloaded until its snapshot is persisted");
            store.release();
            eviction.get(5, TimeUnit.SECONDS);
            reload.get(5, TimeUnit.SECONDS);

            NeutralItem visible = manager.getSlot("first", InventoryScope.server(), 0);
            assertNotNull(visible,
                    "a scope must remain discoverable until its dirty eviction snapshot is persisted");
            assertEquals("minecraft:stone", visible.getItemId());
            assertEquals(1, manager.getSlotVersion("first", InventoryScope.server(), 0));
        } finally {
            store.release();
            manager.shutdown();
        }
    }

    private static NeutralItem item() {
        NeutralItem item = new NeutralItem(
                "minecraft:stone", 1, "Stone", new byte[0], false, "test");
        item.setMaxStackSize(64);
        return item;
    }

    private static final class BlockingRemoteCacheStore extends RemoteCacheStore {
        private final ConcurrentHashMap<String, RemoteSlotSnapshot> slots = new ConcurrentHashMap<>();
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private volatile CountDownLatch blocked = new CountDownLatch(0);
        private volatile CountDownLatch released = new CountDownLatch(0);

        private BlockingRemoteCacheStore() {
            super(null);
        }

        private void blockNextSave() {
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
        public void saveSlot(String serverName, InventoryScope scope, int slot,
                             NeutralItem item, int version) {
            if (blockNext.compareAndSet(true, false)) {
                blocked.countDown();
                try {
                    if (!released.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test store was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            slots.put(serverName, new RemoteSlotSnapshot(slot,
                    item != null ? item.copy() : null, version));
        }

        @Override
        public List<RemoteSlotSnapshot> loadScope(String serverName, InventoryScope scope) {
            RemoteSlotSnapshot snapshot = slots.get(serverName);
            return snapshot != null ? List.of(snapshot) : List.of();
        }
    }
}
