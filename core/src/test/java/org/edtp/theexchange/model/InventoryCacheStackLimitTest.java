package org.edtp.theexchange.model;

import org.edtp.theexchange.storage.LocalInventoryCache;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InventoryCacheStackLimitTest {

    @Test
    void cacheRejectsUnknownAndOverflowingLimitsWithoutFallback() {
        LocalInventoryCache cache = new LocalInventoryCache(InventoryScope.server(), 1);

        LocalInventoryCache.Result unknown = cache.put(0, item(1, 0), 0, "test");
        assertFalse(unknown.success());
        assertEquals("STACK_LIMIT_UNKNOWN", unknown.failReason());

        LocalInventoryCache.Result overflow = cache.put(0, item(17, 16), 0, "test");
        assertFalse(overflow.success());
        assertEquals("STACK_OVERFLOW", overflow.failReason());
    }

    @Test
    void cacheUsesStoredSnapshotLimitForMerges() {
        LocalInventoryCache cache = new LocalInventoryCache(InventoryScope.server(), 1);
        assertTrue(cache.put(0, item(15, 16), 0, "test").success());

        int version = cache.getVersion(0);
        assertTrue(cache.put(0, item(1, 64), version, "test").success());

        LocalInventoryCache.Result overflow = cache.put(
                0, item(1, 64), cache.getVersion(0), "test");
        assertFalse(overflow.success());
        assertEquals("STACK_OVERFLOW", overflow.failReason());
    }

    @Test
    void remoteCacheRefreshesMissingTransientLimitEvenWhenVersionMatches() {
        CachedInventory cache = new CachedInventory();
        NeutralItem persistedItem = item(8, 0);
        cache.markLoaded(List.of(
                new CachedInventory.SlotSnapshot(0, persistedItem, 7)), System.currentTimeMillis());

        assertEquals(List.of(0), cache.changedSlots(List.of(7)));

        persistedItem.setMaxStackSize(16);
        cache.replaceSlot(0, persistedItem, 7);
        assertTrue(cache.changedSlots(List.of(7)).isEmpty());
    }

    private NeutralItem item(int count, int maxStackSize) {
        NeutralItem item = new NeutralItem(
                "minecraft:stone", count, "Stone", new byte[0], false, "test");
        item.setMaxStackSize(maxStackSize);
        return item;
    }
}
