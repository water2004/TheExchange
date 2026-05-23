# Phase 5：service 层 + config

## ExchangeService.java — S-7, C-14

**S-7**: `handleRemotePutLocked` 和 `handleRemoteTakeLocked` 的 catch 分支：
```java
"INTERNAL_ERROR: " + e.getMessage()
```
异常消息可能含文件路径、SQL 细节等，直接返回给远端。

**改**：返回 `"INTERNAL_ERROR"`，完整异常信息写入 `debugPut`/`debugTake` 日志。

**C-14**: `PutItemRequest` 的 `remoteVersion` 字段在服务端 `handleRemotePutLocked` 中从未被读取，是死字段。但协议中仍然编解码传输它，且 `putNeutralItemAsync` 中将 `expectedVersion` 传了两次，容易让后续维护者误以为两者有不同语义。

**改**：
- 从 `PutItemRequest` 中移除 `remoteVersion` 字段及其 getter/setter
- 移除 7 参数构造函数，保留 6 参数版本
- `MessageCodec` 中 PUT_ITEM 的编解码移除该字段
- `TakeItemRequest` 如有同样的死字段一并清理

## MenuInteractionService.java + ExchangeService.java — C-4

`decideSwap` 中当远程 slot 有物品且玩家快捷栏也有物品时，只发 `PUT_REMOTE`，远程原有物品丢失。根本原因：协议缺少原子交换语义。

**改**：新增 `SWAP_ITEM` 协议消息，服务端在单个 slot 锁内原子完成取出+放入。

### 协议层

新增帧类型：
- `SWAP_ITEM` (0x0024) C→S
- `SWAP_ITEM_RESPONSE` (0x0025) S→C

```java
// 请求
class SwapItemRequest implements CorrelatedMessage {
    int slot;
    NeutralItem newItem;        // 要放入的物品
    int expectedVersion;        // 乐观锁
    String expectedItemId;      // 期望取出的物品 ID（校验）
    int takeCount;              // 要取出的数量（通常为全部）
    String requestId;
    String playerUuid;
    String playerName;
}

// 响应
class SwapItemResponse implements CorrelatedMessage {
    boolean success;
    NeutralItem takenItem;      // 取出的物品（成功时非 null）
    int newVersion;             // 操作后的版本号
    String failReason;
    String requestId;
}
```

### 服务端 ExchangeService.handleRemoteSwap

```
slotLock(slot).lock()
  → 幂等检查 (requestId)
  → 版本校验 (expectedVersion)
  → itemId 校验 (expectedItemId)
  → 兼容性检查 (newItem + currentItem)
  → 取出 currentItem (全部或 takeCount)
  → 放入 newItem
  → version += 1 (单次递增，整个 swap 是一个版本变更)
  → markDirty + operationLogger.log
slotLock.unlock()
→ 响应 (takenItem, newVersion)
→ broadcastPushUpdate
```

单次 slot 锁内完成，无中间态。

### 客户端 ExchangeMenu

```
decideSwap 返回 SWAP_REMOTE (新增 ExchangeInteractionResult 类型)
→ removeSourceStack (从快捷栏移除 hotbarItem，存为 inFlight)
→ core.swapRemoteAsync(server, slot, inFlight, expectedItemId, takeCount, player)
  → sendAsync(SWAP_ITEM, request, timeout)
→ 成功: 将 takenItem 放入玩家快捷栏对应格子，更新缓存
→ 失败: giveOrDrop(player, inFlight) 归还
```

### 性能

- 单次网络往返（vs 两步方案的两次）
- 单次 slot 锁获取（vs 两步方案的两次）
- 与现有 PUT/TAKE 相同的超时和重试语义

### MenuInteractionService.decideSwap 修改

```java
if (!isEmpty(hotbarItem) && !isEmpty(remote)) {
    // 双方都有物品 → 原子交换
    return result(SWAP_REMOTE, slot, hotbarItem.getCount(), remote.getItemId());
}
// 单方有物品的情况保持不变 (PUT_REMOTE / TAKE_REMOTE)
```

## ExchangeConfigManager.java — S-4

`configShow` 和 `get("server.password")` 直接返回完整 JSON / 值，含明文密码。

**改**：对 path 以 `.password` 结尾的键，返回值遮盖为 `"***"`。`show()` 中 `server.password` 和 `remoteServers[].password` 两处遮盖。
