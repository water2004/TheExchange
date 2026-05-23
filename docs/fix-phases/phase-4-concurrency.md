# Phase 4：并发正确性

## AbstractSlotInventoryCache.java — C-1

`markClean` 方法：
```java
List<SlotState> current = slotRefs();         // optimistic read
if (revision.get() != persistedRevision) {    // 检查
    return;
}
dirty = false;                                 // 清空
for (SlotState slot : current) {
    slot.lock.lock();
    try { slot.dirty = false; } finally { slot.lock.unlock(); }
}
```
`slotRefs()` 用了 optimistic read 取 snapshot。revision 检查与 `dirty=false` 之间无锁保护。另一线程可在检查通过后做 mutation 并递增 revision，dirty 标记被错误清除。

**改**：`markClean` 全程持 writeLock，确保期间无新 mutation。

**实现注意**：StampedLock 不支持重入。持 writeLock 期间不能调用 `slotRefs()`（内部获取 readLock 会死锁）。应直接访问 `slots` 字段：`List<SlotState> current = new ArrayList<>(slots);`。

## CacheManager.java + LocalInventoryCacheManager.java — C-2, M-2

**C-2**: `flushDirty` 先 `snapshotForFlush()` 取带 revision 的 snapshot，再 `dirtySlots()` 取 dirty 列表。两者之间有窗口。`markClean(persistedRevision)` 如果 revision 对不上会跳过——但这恰是 C-1 的保护点。两处联动修复后，`markClean` 持写锁保证原子性，`flushDirty` 的两次调用之间不再有竞争。

**M-2**: `flushDirtyCachesSafely` 和 `flushDirtyCachesSafely` 都用 `catch (Throwable ignored)` 静默吞掉包括 OOM、StackOverflow 在内的所有异常。

**改**：
- C-2 在 C-1 修完后自然修复（markClean 持锁后 snapshot 和 dirtySlots 之间无窗口）
- M-2: `catch (Throwable ignored)` → `catch (Exception e) { api.getLogger().warn("Cache flush failed", e); }`

## TheExchangeCore.java — C-9, C-5

**C-9**: `startAsync()` 中 `startupFuture != null` 检查无同步保护，两个线程可同时通过检查并双次初始化。

**改**：`synchronized(this)` 保护检查和赋值。

**C-5**: `reloadConfigInternal` 中 `buildRuntime` 抛异常时：
```java
try {
    ... buildRuntime(reloaded, oldConfig);  // 可能抛异常
    ... 日志 ...
} finally {
    endReload();  // 恢复 acceptingTasks=true
}
```
此时 `runtimeConfig` 已更新为新配置，但新服务可能未完全构建。`acceptingTasks` 恢复后新请求将操作 null 引用。

**改**：`buildRuntime` 前保存旧服务引用（`oldExchangeService` 等），异常时回滚 `runtimeConfig = oldConfig`，重新设置旧服务字段，记录错误日志。确保 `endReload` 后系统处于完整状态（要么新、要么旧）。

---

## CacheManager.java — C-3

**已确认竞态存在。** `updateCacheSlot` 中 `getOrLoad(key)` 返回 null 后直接 `new CachedInventory()` + `putCached(key, cache)`。`getOrLoad` 的 double-check 仅在 `cacheStore.loadScope` 返回非 null 时生效；返回 null 时直接返回 null，不做任何 put。两个线程同时对同一 key 首次写入时，后者的 `putCached` 会覆盖前者刚写入的数据。

**改**：将 `updateCacheSlot` 中的 check-then-create 逻辑移入锁内：
```java
lock.lock();
try {
    CachedInventory cache = caches.get(key);
    if (cache == null) {
        cache = new CachedInventory(scope);
        putCachedLocked(key, cache);
    }
} finally {
    lock.unlock();
}
cache.replaceSlot(slot, item, version);
```
