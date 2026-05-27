# TheExchange — 架构设计文档

> 基于代码实际实现。REQUIREMENTS.md 为需求文档，本文档为实现文档。

---

# 1. 项目结构（NeoForge分支）

```
TheExchange
├── build.gradle ← NeoForge ModDevGradle 构建
├── gradle.properties
├── settings.gradle
├── src/main/java/org/edtp/theexchange/
│ ├── Theexchange.java ← @Mod 主类，生命周期注册
│ ├── TheExchangeCore.java ← 核心入口（原 core 模块已合并至此）
│ ├── api/
│ │ ├── ExchangeAPI.java
│ │ └── RefreshableExchangeView.java
│ ├── model/ ← NeutralItem, CachedInventory...
│ ├── network/ ← NetworkManager, TLS, 防重放...
│ ├── storage/ ← DatabaseManager, LocalItemStore...
│ ├── service/ ← ExchangeService, SyncEngine...
│ ├── compat/ ← ItemSerializer 接口
│ ├── config/ ← ExchangeConfigManager
│ └── neoforge/ ← NeoForge 平台适配层
│ ├── NeoForgeExchangeAPI.java
│ ├── command/ExchangeCommand.java
│ ├── config/NeoForgeConfigLoader.java
│ └── item/NeoForgeItemSerializer.java
└── src/main/resources/
└── META-INF/neoforge.mods.toml
```

原 Fabric 多模块中的 core/ 代码已完全内聚到主模块，无需额外子项目。

# 2. 核心初始化

## 2.1 启动流程

```
Theexchange.onRegisterCommands()
  → CommandRegistrationCallback               // 注册 /exchange 指令树
  → ServerLifecycleEvents.SERVER_STARTED
    → new FabricExchangeAPI(server)           // 适配层实例化
    → new TheExchangeCore(api)                // 核心创建
    → core.startAsync()                       // lifecycleExecutor 执行
      → initialize():
        1. configManager = new ExchangeConfigManager(api.getConfigLoader())
        2. runtimeConfig = configManager.current()
        3. startCoreExecutor(runtimeConfig)   // FixedThreadPool(core_threads)
        4. databaseManager.initialize()       // SQLite WAL, 建 5 张表
        5. localItemStore, remoteCacheStore, operationLogger
        6. buildRuntime(runtimeConfig)        // 构建所有服务
        7. initialized = true
```

## 2.2 buildRuntime 服务构建顺序

```
1. pinnedPeerKeyStore (惰性初始化)
2. pruneRemoteState(config)           // 清理删除的服务器的 DB 缓存 + 公钥
3. localInventoryCacheManager         // 本服库存 in-memory LRU
4. cacheManager                       // 远端缓存 LRU
5. networkManager (新建或复用)        // TLS + TCP
6. serverRegistry                     // 远端服务器注册表
7. syncEngine                         // 同步引擎
8. exchangeService                    // 业务逻辑
9. menuInteractionService             // GUI 交互决策
10. networkManager.setMessageRouter() // 入站消息 → submit() → exchangeService.routeMessage()
11. heartbeatManager.start()          // 心跳 + 重连
12. disconnectOutboundNotIn()         // 断开不在新配置中的连接
13. connectAllEnabled()               // 向所有配置的远端发起连接
```

# 3. 线程模型

## 3.1 线程池

| 线程池 | 大小 | 职责 |
|--------|------|------|
| lifecycleExecutor | 1 (SingleThread) | 初始化/reload/shutdown，串行化生命周期 |
| coreExecutor | core_threads (默认 4) | 所有业务任务 (submit) |
| Connection read thread | 1 per connection | 帧解码 + messageRouter 分发 |
| Connection sendAsync thread | 1 per request | sendAndWait 阻塞发送 |
| Cache writer | 1 per cache | 异步刷盘到 SQLite |
| Cache flusher | 1 per cache | 30s 定期兜底刷盘 |
| Heartbeat scheduler | 2 (ScheduledPool) | 心跳发送 + 超时检测 |

## 3.2 submit 机制

所有业务操作统一通过 `TheExchangeCore.submit(Callable)` 提交到 coreExecutor：

```java
public <T> CompletableFuture<T> submit(Callable<T> task) {
    if (shuttingDown) → 拒绝
    ExecutorService executor = coreExecutor;
    if (executor == null) → 拒绝
    synchronized (taskMonitor) {
        if (!acceptingTasks || reloading) → 拒绝
        inFlightTasks++;          // 在途计数
    }
    executor.execute(() -> {
        if (taskGeneration != generation.get()) → 拒绝 (reload 后过期)
        task.call()
        completeTask()            // inFlightTasks--, notifyAll if 0
    })
}
```

## 3.3 reload 机制

```
reloadConfigInternal()                           // lifecycleExecutor 线程
  → beginReload():
      synchronized(taskMonitor): acceptingTasks=false, reloading=true
      waitForTasksToDrain(): inFlightTasks→0 前阻塞
  → localInventoryCacheManager.flushAll()        // 同步刷盘
  → cacheManager.shutdown()                      // 同步刷盘
  → stopReloadableServices()                     // 停心跳, 旧网络 (端口变则关)
  → stopCoreExecutor()                           // shutdown + awaitTermination(30s)
  → swapCoreExecutor()                           // 新建线程池
  → generation.incrementAndGet()                 // 旧任务全部失效
  → buildRuntime()                               // 重建所有服务
  → endReload(): acceptingTasks=true
```

drain 期间新任务被拒绝，已在 coreExecutor 中的任务执行完毕后 reload 才继续。sendAsync 独立线程不受 drain 控制，但其回调进入 submitIfGeneration 时因 generation 已递增被拒绝，物品正常发还。

# 4. 网络协议

## 4.1 帧结构 (28 字节头 + payload)

```
Offset  Size  Field
0       4     Magic       = 0x45584348 ("EXCH")
4       2     Version     = 1
6       4     Length      = payload 字节数 (≤ 10 MiB)
10      2     Type        = FrameType 枚举码
12      8     Sequence    = 单调递增 (per-connection)
20      8     Timestamp   = Unix 毫秒
28      var   Payload     = 结构化二进制 (MessageCodec)
```

## 4.2 FrameType 完整枚举

| 码 | 名称 | 方向 | 说明 |
|----|------|------|------|
| 0x0001 | AUTH_REQUEST | C→S | 服务名 + 密码 + MC 版本 |
| 0x0002 | AUTH_RESPONSE | S→C | 成功/失败 + 对方服务名 + MC 版本 + 时间戳 |
| 0x0003 | HEARTBEAT | 双向 | PING(isReply=false) / PONG(isReply=true) |
| 0x0010 | QUERY_TIMESTAMP | C→S | 查询时间戳 (当前未使用) |
| 0x0011 | TIMESTAMP_RESPONSE | S→C | 时间戳响应 (当前未使用) |
| 0x0012 | QUERY_ITEMS | C→S | 全量查询 (当前未使用) |
| 0x0013 | ITEMS_RESPONSE | S→C | 全量响应 (当前未使用) |
| 0x0014 | QUERY_SLOT_VERSION | C→S | 查询单槽版本 |
| 0x0015 | SLOT_VERSION_RESPONSE | S→C | 单槽版本号 |
| 0x0016 | QUERY_SLOT_STATE | C→S | 查询单槽状态 |
| 0x0017 | SLOT_STATE_RESPONSE | S→C | 单槽 item + version |
| 0x0018 | QUERY_SLOT_VERSIONS | C→S | 查询所有槽位版本号 |
| 0x0019 | SLOT_VERSIONS_RESPONSE | S→C | 列表 [version, ...] |
| 0x001A | QUERY_SLOTS | C→S | 批量查询指定槽位 |
| 0x001B | SLOTS_STATE_RESPONSE | S→C | 批量槽位状态 |
| 0x0020 | PUT_ITEM | C→S | 放入物品 (slot, item, expectedVersion, requestId, playerUuid, playerName, remoteVersion) |
| 0x0021 | PUT_ITEM_RESPONSE | S→C | 放入结果 (success, currentItem, newVersion, requestId...) |
| 0x0022 | TAKE_ITEM | C→S | 取出物品 (slot, expectedItemId, expectedVersion, requestCount, ...) |
| 0x0023 | TAKE_ITEM_RESPONSE | S→C | 取出结果 (success, currentItem, itemsToGive, newVersion...) |
| 0x0030 | PUSH_UPDATE | S→C | 变更通知 (changedSlots[], timestamp) |
| 0xFFFF | ERROR | 双向 | 错误码 + 消息 |

## 4.3 连接生命周期

```
Client                                     Server
  │─ TCP connect ───────────────────────────│
  │─ TLS 1.3 握手 ──────────────────────────│
  │  · 客户端宽松握手 (不校验证书)           │
  │  · 加密套件: AES-256-GCM / AES-128-GCM  │
  │─ AUTH_REQUEST ─────────────────────────│
  │  {serverName, password, version,       │
  │   mcVersion}                            │── 密码比对
  │                                         │── 入站开关检查 (acceptingInbound)
  │─ AUTH_RESPONSE ────────────────────────│
  │  {success, serverName, mcVersion,      │
  │   lastModifiedTimestamp}                │
  │                                         │
  │  [认证成功, 标记 authenticated,          │
  │   注册到 connections map]                │
  │                                         │
  │─ HEARTBEAT (PING) ─────────────────────│  每 10s
  │─ HEARTBEAT (PONG) ─────────────────────│
  │                                         │
  │  [30s 无数据 → 离线 → 指数退避重连]      │
  │                                         │
  │─ QUERY_SLOT_VERSIONS ──────────────────│  打开共享空间
  │─ SLOT_VERSIONS_RESPONSE ───────────────│
  │─ QUERY_SLOTS (changed) ───────────────│  仅拉变更槽位
  │─ SLOTS_STATE_RESPONSE ────────────────│
  │                                         │
  │─ PUT_ITEM / TAKE_ITEM ────────────────│  玩家操作
  │─ PUT_ITEM_RESPONSE / TAKE_ITEM_RESP ──│
```

## 4.4 TOFU 公钥固定

```
TcpClient.connect()
  1. TLS 握手 (trustAll — 接受任何证书)
  2. pinnedPeerKeyStore.verifyOrPin(serverName, socket)
     └→ 提取对端证书公钥 (PublicKey.getEncoded() → Base64)
        首次连接: 写入 tls/known-peers.properties
        后续连接: 比对已保存公钥, 不匹配 → SSLHandshakeException
  3. AUTH_REQUEST (应用层密码鉴权)
```

首次连接时中间人攻击仍有可能 (TOFU 固有局限)，之后每次连接都会检测到公钥变化并拒绝。

## 4.5 防重放

`SequenceWindow`: 1024 位滑动窗口 + 60 秒时间戳容差。

```
validate(sequence, timestamp):
  |now - timestamp| > 60s → 拒绝
  sequence < base       → 拒绝 (太旧)
  sequence >= base+1024 → 推进窗口
  bit[sequence-base]=1  → 拒绝 (重放)
  否则                   → 标记 bit=1, 接受
```

# 5. 数据存储

## 5.1 SQLite 表结构

```sql
-- 本服权威库存 (交换空间)
CREATE TABLE exchange_items (
                                scope_type  TEXT    NOT NULL,         -- 'SERVER' | 'PLAYER'
                                scope_id    TEXT    NOT NULL,         -- '' 或 playerUUID
                                slot        INTEGER NOT NULL,         -- 槽位号 0~53+
                                item_data   BLOB,                     -- NeutralItemBlobCodec 编码
                                added_by    TEXT,
                                added_at    INTEGER NOT NULL,
                                updated_at  INTEGER NOT NULL,
                                version     INTEGER NOT NULL,         -- 乐观锁版本号
                                PRIMARY KEY (scope_type, scope_id, slot)
);

-- 远端库存缓存
CREATE TABLE remote_cache (
                              server_name  TEXT    NOT NULL,
                              scope_type   TEXT    NOT NULL,
                              scope_id     TEXT    NOT NULL,
                              slot         INTEGER NOT NULL,
                              items_blob   BLOB,                   -- NeutralItemBlobCodec 编码
                              version      INTEGER NOT NULL,
                              synced_at    INTEGER NOT NULL,
                              PRIMARY KEY (server_name, scope_type, scope_id, slot)
);

-- 操作审计日志 (幂等)
CREATE TABLE operation_log (
                               id           INTEGER PRIMARY KEY AUTOINCREMENT,
                               timestamp    INTEGER NOT NULL,
                               op_type      TEXT    NOT NULL,        -- 'PUT' | 'TAKE'
                               scope_type   TEXT    NOT NULL,
                               scope_id     TEXT    NOT NULL,
                               player_uuid  TEXT    NOT NULL,
                               player_name  TEXT    NOT NULL,
                               server_name  TEXT    NOT NULL,
                               item_id      TEXT    NOT NULL,
                               quantity     INTEGER NOT NULL,
                               result       TEXT    NOT NULL,        -- 'SUCCESS' | 'FAIL'
                               fail_reason  TEXT,
                               request_id   TEXT    NOT NULL UNIQUE  -- 幂等键
);

-- 库存元数据
CREATE TABLE inventory_metadata (
                                    scope_type    TEXT NOT NULL,
                                    scope_id      TEXT NOT NULL,
                                    last_modified INTEGER NOT NULL,
                                    PRIMARY KEY (scope_type, scope_id)
);

-- 键值配置
CREATE TABLE exchange_metadata (
                                   key   TEXT PRIMARY KEY,
                                   value TEXT NOT NULL
);
```

WAL 模式。写操作通过 `DatabaseManager.lock()` (ReentrantLock) 串行化 — 所有 SQLite 写操作都先 `db.lock()` 再执行。

## 5.2 缓存架构

```
LocalInventoryCacheManager
  ├── LinkedHashMap<InventoryScope, LocalInventoryCache>  (LRU)
  ├── 容量: local_inventory_cache_capacity (默认 32)
  ├── writer: 单线程异步刷盘 (每次 mutation 后 scheduleFlush)
  └── flusher: 30s 定期兜底 flushDirtyCachesSafely()

CacheManager
  ├── LinkedHashMap<RemoteScopeKey, CachedInventory>  (LRU)
  ├── 容量: remote_inventory_cache_capacity (默认 64)
  ├── 过期: 24h 未访问清理 (cleanupExpired)
  ├── writer: 单线程异步刷盘 (每次 updateCacheSlot 后 scheduleFlush)
  └── flusher: 30s 定期兜底

LocalInventoryCache / CachedInventory
  └── extends AbstractSlotInventoryCache
      ├── ArrayList<SlotState>  (只增不删)
      ├── StampedLock  (stateForRead = optimisticRead, stateForWrite = writeLock)
      ├── per-slot ReentrantLock  (put/take 操作持有)
      ├── AtomicLong revision  (dirty 追踪, markDirty 递增)
      └── AtomicBoolean flushQueued  (防止重复调度 flush)
```

### 异步刷盘流程

```
put/take → markDirty → scheduleFlush()
  → markFlushQueued() CAS 检查 (防重复)
  → writer.execute(() → flushDirty())
  → clearFlushQueued()
  → 如果 closed=false 且仍 dirty → 重新 scheduleFlush

flushDirty():
  → cache.snapshotForFlush() → 列出 dirty slots
  → persistSlot / persistScopeSnapshot
  → beginImmediate → 批量 INSERT/UPDATE/DELETE → COMMIT
  → cache.markClean(revision)

关闭时:
  flushAll() → closed=true → scheduleFlush 改为同步执行
```

### 操作日志幂等

`handleRemotePut/handleRemoteTake` 在槽位锁内:
1. `operationLogger.findByRequestId(requestId)` — 查是否已处理
2. 已处理 → 直接返回历史结果 (不发重复响应给远端, 但返回相同 payload)
3. 未处理 → 执行库存变更 → `operationLogger.log()` → `request_id` UNIQUE 约束保证不重复

# 6. 并发控制

## 6.1 锁层次

```
reload 排空
  └── beginReload() → taskMonitor → acceptingTasks=false, drain inFlightTasks

coreExecutor (core_threads 线程)
  └── 所有业务操作提交到此

消息路由 (入站)
  └── messageRouter.handle() → submit() → coreExecutor

ExchangeService 槽位锁
  └── ConcurrentHashMap<Integer, ReentrantLock> (per slot)
      └── 同槽位 PUT/TAKE 串行化

AbstractSlotInventoryCache 锁
  ├── StampedLock (structureLock)
  │   ├── stateForRead: tryOptimisticRead → 乐观读, 仅在扩容时退化到 readLock
  │   └── stateForWrite: tryOptimisticRead → writeLock (仅扩容时)
  └── per-slot ReentrantLock (SlotState.lock)
      └── putIntoSlot / takeFromSlot 持锁

CacheManager
  ├── ReentrantLock (lock) → LRU map 操作
  └── AtomicBoolean (flushQueued) → 防重复调度
```

## 6.2 PUT 路径 (权威服务端)

```
Connection read thread → messageRouter.handle(PUT_ITEM)
  → TheExchangeCore.submit()
    → coreExecutor worker
      → ExchangeService.routeMessage()
        → handleRemotePut(request)
          → localSlotLock(slot).lock()
            → operationLogger.findByRequestId()    // DB 同步读
            → compatibilityChecker.checkAndMark()   // 纯 CPU
            → localItemStore.putItem()
              → LocalInventoryCacheManager.put()
                → cache.putIntoSlot()              // 槽位锁内
                  · 空槽: 版本校验 → 放入 → markDirty
                  · 同种: 堆叠校验 → 合并 → markDirty
                  · 不同种: SLOT_OCCUPIED
                → scheduleFlush()                  // 异步刷盘
            → operationLogger.log()                // DB 同步 INSERT
          → slotLock.unlock()
        → conn.send(PUT_ITEM_RESPONSE)             // 立即响应
        → broadcastInventoryUpdate(PUSH_UPDATE)     // 通知其他连接
```

## 6.3 TAKE 路径 (权威服务端)

```
同上路径
  → handleRemoteTake(request)
    → localSlotLock(slot).lock()
      → 幂等检查
      → localItemStore.takeItem()
        → cache.takeFromSlot()
          · 版本校验 (expectedVersion)
          · itemId 校验
          · 数量校验 (INSUFFICIENT)
          · 完全取出: state.item = null
          · 部分取出: setCount(remaining)
          · markDirty
        → scheduleFlush()
      → operationLogger.log()
    → slotLock.unlock()
    → conn.send(TAKE_ITEM_RESPONSE)               // 含 itemsToGive
```

## 6.4 同步引擎

```
SyncEngine.refreshChangedSlotsAsync(serverName)
  → conn.sendAsync(QUERY_SLOT_VERSIONS)
  → 响应: List<Integer> remoteVersions
  → CacheManager.changedSlots(local, remote)
    → 比对每个槽位版本号, 不同 → changed
  → 无变更 → 完成
  → 有变更 → querySlotsAsync(serverName, changed)
    → conn.sendAsync(QUERY_SLOTS(changed))
    → 响应: List<SlotStateResponse>
    → applySlotStates()
      → compatibilityChecker.checkAndMark()  (每个 item)
      → CacheManager.updateCacheSlots()      (批量更新 in-memory)
        → scheduleFlush()                    (异步刷盘)
```

PUSH_UPDATE 处理:
```
收到 PUSH_UPDATE(changedSlots, timestamp)
  → querySlotsAsync(sourceServer, changedSlots)  // 拉取实际数据
  → redrawRemoteInventoryView()                  // 刷新 GUI
```
PUSH_UPDATE 不含物品数据，只作失效通知。

# 7. 心跳与在线检测

```
HeartbeatManager.start()
  → scheduler.scheduleAtFixedRate(sendHeartbeats, 10s, 10s)
    → 遍历所有已配置远端
    → conn.isRunning() → 发送 HEARTBEAT PING
    → conn == null && enabled → scheduleReconnect()
  → scheduler.scheduleAtFixedRate(checkTimeouts, 5s, 5s)
    → now - conn.lastRecvTime > heartbeatTimeoutSeconds (默认 30)
    → disconnect() → scheduleReconnect()

scheduleReconnect(server)
  → putIfAbsent(reconnectScheduled) 防重复
  → scheduler.schedule(delay, → tryReconnect)
    → networkManager.connectToRemote(server)
    → 成功 → 清除重连延迟
    → 失败 → 指数退避 (5→10→20→30s 上限)
  → finally: reconnectScheduled.remove()
```

# 8. GUI 交互决策

`MenuInteractionService.decide(ExchangeInteraction)` 根据点击类型、目标槽位、在线状态、物品兼容性决定操作:

```
1. touchesIncompatibleItem() → REJECT
2. !touchesExchangeSpace() → PASS_TO_LOADER (原版处理)
3. !isOnline() → REFRESH ("目标服务器离线")
4. QUICK_MOVE → decideQuickMove
   └→ 从交换空间拖: TAKE_REMOTE
     从背包拖: PUT_REMOTE (含 findTargetSlot 找空槽/合并)
5. PICKUP → decidePickup
   └→ 空手点: TAKE_REMOTE (button=1 取一半)
     手上有点: PUT_REMOTE (都放或放 1 个)
6. SWAP → decideSwap
   └→ 快捷栏有物: PUT_REMOTE, 空手取: TAKE_REMOTE
7. 其他 (QUICK_CRAFT/PICKUP_ALL/THROW/CLONE) → REFRESH ("暂不支持")
```

本地模式不再走容器快照同步。菜单层对本地和远端都产出同一类 `PUT_REMOTE` / `TAKE_REMOTE` / `SWAP_REMOTE` 操作；core 对本地目标使用进程内 loopback，构造同样的 request 并进入 `handleRemotePut/Take/Swap`，因此本地也经过版本检查、slot lock、幂等记录、兼容性检查和统一刷新/广播。

# 9. 配置管理

`config/theexchange/theexchange.json`:

```jsonc
{
  "server": {
    "display_name": "Default Server",     // 本服显示名
    "port": 25566,                        // 监听端口
    "password": "changeme"                // 连接密码
  },
  "network": {
    "heartbeat_interval_seconds": 10,
    "heartbeat_timeout_seconds": 30,
    "reconnect_initial_delay_seconds": 5,
    "reconnect_max_delay_seconds": 30,
    "request_timeout_seconds": 5,
    "inbound_enabled": false
  },
  "cache": {
    "offline_retention_hours": 24,
    "local_inventory_cache_capacity": 32,
    "remote_inventory_cache_capacity": 64
  },
  "performance": {
    "core_threads": 4                     // coreExecutor 线程数
  },
  "logging": {
    "retention_days": 30,
    "cleanup_interval_hours": 1
  },
  "container": {
    "rows": 6,                            // 固定 6 (9×6=54 槽)
    "title_template": "{server_name} 的共享空间"
  },
  "remoteServers": [
    { "name": "生存服", "address": "10.0.0.2", "port": 25566, "password": "..." }
  ]
}
```

`ExchangeConfigManager.readablePaths()` / `writablePaths()` 提供有效路径列表用于命令自动补全。校验规则:
- display_name / password: 非空
- port: 1–65535
- 数值项: >0
- rows: 固定 6
- remote name: 非空, 不含空白, 不能为 "local", 不允许重复

# 10. 跨版本兼容

## 10.1 适配层差异

| API           | fabric-26.1                     | fabric-1.21.11                           | neoforge-1.21.11                             |
|---------------|---------------------------------|------------------------------------------|----------------------------------------------|
| 构建插件          | net.fabricmc.fabric-loom 1.15.5 | fabric-loom 1.15-SNAPSHOT                | **net.neoforged.moddevgradle**               |
| Java target   | 25                              | 21                                       | **21**                                       |
| clicked()     | ContainerInput enum             | ClickType enum                           | **ClickType**                                |
| 消息发送          | sendSystemMessage(Component)    | displayClientMessage(Component, boolean) | **displayClientMessage(Component, boolean)** |
| 权限            | Permissions.COMMANDS_ADMIN      | 同 (存在)                                   | **同**                                        |
| ItemStack 序列化 | CODEC (DataComponentMap)        | CODEC (同, MapCodec)                      | **CODEC (同)**                                |
核心 `ItemStack.CODEC` 序列化 API 在所有平台上完全一致，`NeoForgeItemSerializer` 与 `FabricItemSerializer` 的实现可以通用，无需修改。

## 10.2 NeutralItem

```java
class NeutralItem {
    String itemId;           // "minecraft:diamond"
    int count;
    String displayName;      // JSON 文本组件
    byte[] extraData;        // 黑盒透传 (NBT/Data Components)
    boolean incompatible;    // 接收方标记
    String sourceVersion;    // 来源 MC 版本
    int version;             // 乐观锁版本 (仅 DB 层使用)
}
```

`CompatibilityChecker.checkAndMark()` 在接收方调用: `itemSerializer.canDeserialize(item)` 失败 → `incompatible=true`。不兼容物品禁止操作，extraData 原样保存原样返回 (F-40)。

# 11. 跨服物品操作流程

## 11.1 远端 PUT (本服玩家 → 远端)

```
ExchangeMenu.clicked() → decide() → PUT_REMOTE
  → removeSourceStack()              // 从背包真实扣除, 存 ItemStack 局部变量
  → core.putRemoteAsync(server, slot, item, player)
    → submit() → exchangeService.putNeutralItemAsync()
      → 检查: networkManager, conn, syncEngine 非 null
      → cacheManager.getSlot() → 检查不兼容
      → cacheManager.getSlotVersion() → expectedVersion
      → conn.sendAsync(PUT_ITEM, request, 5s timeout)
        → 发送到远端...

  远端权威服务器:
    handleRemotePut() → slotLock → 幂等检查 → 兼容性检查 → putItem → log → 响应

      ← PUT_ITEM_RESPONSE 返回
    → .handle() 回调:
      → submitIfGeneration(opGeneration, finishRemotePut)
        → 成功: cacheManager.updateCacheSlot(), redrawOpenViews()
        → 失败: operationLogger.log(FAIL)
    → ExchangeMenu 回调:
      → 成功: refreshFromMemory()
      → 失败: giveOrDrop(player, inFlight)  // 退回物品
```

## 11.2 远端 TAKE (本服玩家 ← 远端)

```
ExchangeMenu.clicked() → decide() → TAKE_REMOTE
  → core.takeRemoteAsync(server, slot, count, player)
    → submit() → exchangeService.takeItemAsync()
      → cacheManager.getSlot() → expectedItem + expectedVersion
      → conn.sendAsync(TAKE_ITEM, request, 5s timeout)

  远端权威服务器:
    handleRemoteTake() → slotLock → 幂等检查 → 存在/版本/id/数量校验 → takeItem → log → 响应

      ← TAKE_ITEM_RESPONSE (含 itemsToGive)
    → finishRemoteTake()
      → 成功: 检查 itemsToGive 非空非不兼容 → 更新缓存
      → 失败: 更新缓存 (保持当前状态)
    → ExchangeMenu 回调:
      → 成功: applyTakenItem() → deserialize → 放入背包 / setCarried / 掉落
      → 失败: 提示原因, refreshFromMemory()
```

# 12. 已知与需求差异

| 需求 | 实现 | 原因 |
|------|------|------|
| F-23 密码哈希存储 | 明文存储 | TLS + TOFU 公钥固定保护传输；bcrypt 待后续 |
| F-38 离线补偿表 | 未实现 player_compensation | InFlight 物品存局部变量，崩溃丢失；待后续 |
| 客户端增强 GUI (G-08~G-11) | 未实现 | 首期仅服务端 |
| Forge/NeoForge 适配 | 未实现 | 首期仅 Fabric |
| 细粒度权限控制 (§6) | 所有玩家均可操作 | 首期不分权限 |
