# Phase 3：storage 层

## LocalItemStore.java — M-11, C-12

`replaceSlotFromLocal` 永远返回 `true`，异常直接传播，返回值无意义。
`getAllItems(int limit, InventoryScope scope)` 完全忽略 `limit` 参数。

**改**：
- `replaceSlotFromLocal` 改为 `void`
- `getAllItems(int limit, ...)` 移除 `limit` 参数，简化为 `getAllItems(scope)`
- 调用方 `MenuInteractionService.applyLocalSnapshot:159` 适配 void 返回

## OperationLogger.java — M-5, D-5

幂等判断依赖错误消息文本 `.contains("UNIQUE constraint failed")`，不同 SQLite 驱动版本或 locale 下可能不匹配。
`queryLogs` 无结果数量限制，`sinceTimestamp=0` 时可返回全表。

**改**：
- 优先方案：改用 `INSERT OR IGNORE` + 检查 `changes() == 0` 判断重复（最可靠）
- 备选方案：`e.getErrorCode() == 19` (SQLITE_CONSTRAINT)
- `queryLogs` SQL 加 `LIMIT 10000`

## RemoteCacheStore.java — M-9

`loadSlotVersion` 执行完整 `SELECT items_blob, version` 并反序列化整个 blob，然后丢弃物品数据只取 version。

**改**：添加专用 `SELECT version FROM remote_cache WHERE ...` 查询。
