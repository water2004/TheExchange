# 互通有无 / The Exchange — 架构设计文档

> 版本: 1.0
> 日期: 2026-05-18
> 基于需求文档: REQUIREMENTS.md v1.0

---

# 第一部分：项目结构

## 1. 多模块 Gradle 项目布局

采用 **Gradle 多模块** 架构，核心逻辑与加载器适配层物理隔离。

```
TheExchange/
├── build.gradle                          # 根构建脚本（子模块管理）
├── settings.gradle                       # 子模块声明
├── gradle.properties                     # 全局版本属性
├── gradle/
│   └── libs.versions.toml                # 版本目录（集中版本管理）
│
├── core/                                 # === 核心模块（纯 Java，无加载器依赖）===
│   ├── build.gradle
│   └── src/main/java/org/edtp/theexchange/
│       ├── TheExchangeCore.java          # 核心初始化入口
│       ├── api/
│       │   ├── ExchangeAPI.java          # 面向适配层的公共 API 接口
│       │   └── ExchangeEvent.java        # 核心事件定义
│       ├── model/
│       │   ├── ExchangeItem.java         # 物品中立模型
│       │   ├── RemoteServer.java         # 远程服务器配置模型
│       │   ├── ServerStatus.java         # 在线状态枚举
│       │   ├── OperationType.java        # 操作类型枚举
│       │   ├── SyncResult.java           # 同步结果
│       │   └── AuthResult.java           # 认证结果
│       ├── network/
│       │   ├── NetworkManager.java       # 网络层总控（启动/停止/连接管理）
│       │   ├── TcpServer.java            # TCP 服务端（接受入站连接）
│       │   ├── TcpClient.java            # TCP 客户端（发起出站连接）
│       │   ├── Connection.java           # 单个连接抽象（读写、心跳）
│       │   ├── codec/
│       │   │   ├── FrameDecoder.java     # 帧解码器（处理 TCP 粘包/半包）
│       │   │   ├── FrameEncoder.java     # 帧编码器
│       │   │   ├── MessageDecoder.java   # Payload 反序列化（结构化二进制）
│       │   │   └── MessageEncoder.java   # Payload 序列化（结构化二进制）
│       │   ├── tls/
│       │   │   ├── TlsContext.java       # TLS 上下文工厂
│       │   │   └── SelfSignedCert.java   # 自签名证书生成
│       │   └── protocol/
│       │       ├── FrameType.java        # 帧类型枚举
│       │       ├── Frame.java            # 帧结构定义
│       │       ├── messages/             # 各类消息体（固定字段顺序 POJO）
│       │       │   ├── AuthRequest.java
│       │       │   ├── AuthResponse.java
│       │       │   ├── Heartbeat.java
│       │       │   ├── QueryItemsRequest.java
│       │       │   ├── QueryItemsResponse.java
│       │       │   ├── QueryTimestampRequest.java
│       │       │   ├── QueryTimestampResponse.java
│       │       │   ├── PutItemRequest.java
│       │       │   ├── PutItemResponse.java
│       │       │   ├── TakeItemRequest.java
│       │       │   ├── TakeItemResponse.java
│       │       │   └── PushNotification.java
│       │       └── sequence/
│       │           └── SequenceWindow.java  # 防重放序列号窗口
│       ├── storage/
│       │   ├── DatabaseManager.java      # SQLite 连接管理、建表、迁移
│       │   ├── LocalItemStore.java       # 本服物品 CRUD
│       │   ├── RemoteCacheStore.java     # 远程服务器缓存 CRUD
│       │   ├── OperationLogger.java      # 操作日志写入/查询/清理
│       │   ├── ConfigStore.java          # 配置持久化（远程服务器信息等）
│       │   └── migration/
│       │       └── Migrations.java       # 数据库迁移脚本
│       ├── service/
│       │   ├── ExchangeService.java      # 业务逻辑编排（放入/取出/同步）
│       │   ├── ServerRegistry.java       # 远程服务器注册表（增删查）
│       │   ├── CacheManager.java         # 缓存管理（时间戳比对、过期清理）
│       │   └── HeartbeatManager.java     # 心跳检测与在线状态管理
│       ├── security/
│       │   ├── PasswordHasher.java       # 密码哈希（bcrypt）
│       │   └── ConfigSanitizer.java      # 配置密码自动哈希
│       ├── sync/
│       │   ├── SyncEngine.java           # 同步引擎（增量/全量）
│       │   └── TimestampTracker.java     # 时间戳跟踪
│       ├── compat/
│       │   ├── ItemSerializer.java       # 物品序列化接口（由适配层实现注入）
│       │   ├── NeutralItem.java          # 中立物品表示 {id, count, displayName, extraData}
│       │   └── CompatibilityChecker.java # 兼容性检查（对端版本 vs 物品）
│       └── concurrent/
│           ├── OptimisticLock.java       # 乐观锁工具
│           └── StripedLock.java          # 分段锁（按物品 ID 哈希分段）
│
├── fabric/                               # === Fabric 适配层 ===
│   ├── build.gradle
│   └── src/
│       ├── main/java/org/edtp/theexchange/fabric/
│       │   ├── FabricMod.java            # ModInitializer 入口
│       │   ├── FabricExchangeAPI.java    # 核心 API 的 Fabric 实现注入
│       │   ├── command/
│       │   │   └── FabricCommandRegister.java  # 指令注册（Fabric Command API）
│       │   ├── container/
│       │   │   ├── VirtualContainer.java       # 虚拟容器实现（ServerPlayer.openMenu）
│       │   │   ├── ExchangeMenu.java           # 自定义 Menu 子类（核心共享容器）
│       │   │   └── ScreenHandlerBridge.java    # Menu 与物品同步的桥接
│       │   ├── item/
│       │   │   └── FabricItemSerializer.java   # Fabric 版本的 ItemStack ↔ NeutralItem
│       │   ├── config/
│       │   │   └── FabricConfigLoader.java     # 配置文件加载（JSON/TOML）
│       │   ├── event/
│       │   │   └── FabricEventBridge.java      # Fabric 事件 → 核心事件
│       │   └── network/
│       │       └── FabricPacketSender.java     # 向玩家发包的工具（原版封包）
│       ├── client/java/org/edtp/theexchange/fabric/client/
│       │   ├── FabricClientMod.java            # ClientModInitializer 入口
│       │   ├── gui/
│       │   │   ├── ExchangeScreen.java         # 增强 GUI 屏幕
│       │   │   └── ExchangeScreenHandler.java  # 增强 GUI 交互处理器
│       │   └── render/
│       │       └── CompatibilityOverlay.java   # 不兼容物品视觉标记
│       └── resources/
│           ├── fabric.mod.json
│           └── theexchange.fabric.mixins.json
│
├── forge/                                # === Forge 适配层 ===
│   ├── build.gradle
│   └── src/main/java/org/edtp/theexchange/forge/
│       ├── ForgeMod.java
│       ├── ForgeExchangeAPI.java
│       ├── command/
│       │   └── ForgeCommandRegister.java       # Forge 指令注册（RegisterCommandsEvent）
│       ├── container/
│       │   ├── ForgeVirtualContainer.java
│       │   └── ExchangeMenu.java
│       ├── item/
│       │   └── ForgeItemSerializer.java        # Forge 版本 ItemStack 序列化
│       ├── config/
│       │   └── ForgeConfigLoader.java          # Forge 配置系统
│       └── event/
│           └── ForgeEventBridge.java
│   └── resources/
│       ├── META-INF/mods.toml
│       └── theexchange.forge.mixins.json
│
├── neoforge/                             # === NeoForge 适配层 ===
│   ├── build.gradle
│   └── src/main/java/org/edtp/theexchange/neoforge/
│       ├── NeoForgeMod.java
│       ├── NeoForgeExchangeAPI.java
│       ├── command/
│       │   └── NeoForgeCommandRegister.java
│       ├── container/
│       │   ├── NeoForgeVirtualContainer.java
│       │   └── ExchangeMenu.java
│       ├── item/
│       │   └── NeoForgeItemSerializer.java
│       ├── config/
│       │   └── NeoForgeConfigLoader.java
│       └── event/
│           └── NeoForgeEventBridge.java
│   └── resources/
│       ├── META-INF/neoforge.mods.toml
│       └── theexchange.neoforge.mixins.json
│
├── compat/                               # === 跨版本兼容辅助（可选独立 jar）===
│   ├── build.gradle
│   └── src/main/java/org/edtp/theexchange/compat/
│       ├── ItemRegistryDiffer.java       # 不同 MC 版本间物品注册表差异分析
│       └── VersionBridge.java            # 版本间 ItemStack NBT 格式转换
│
├── config/                               # === 运行时配置目录 ===
│   └── (由适配层在运行时生成)
│       ├── theexchange.json              # 主配置文件
│       └── data.db                       # SQLite 数据库（运行时生成）
│
└── docs/
    ├── REQUIREMENTS.md
    └── ARCHITECTURE.md                   # 本文件
```

## 2. 模块依赖关系

```
         ┌───────────────┐
         │     core      │  纯 Java/Kotlin，零加载器依赖，零 Minecraft API 依赖
         └───┬───┬───┬───┘
             │   │   │
    ┌────────┘   │   └────────┐
    ▼            ▼            ▼
┌──────┐   ┌──────┐    ┌──────────┐
│fabric│   │forge │    │ neoforge │   每个适配层独立，依赖 core
└──────┘   └──────┘    └──────────┘
```

- **core** 不依赖任何 Mod Loader 或 Minecraft API。
- **适配层** 依赖 core，负责将 core 的接口适配到具体加载器。
- **core** 中所有涉及 Minecraft 对象的部分（如物品序列化）通过接口定义，由适配层注入实现。

## 3. 版本目录管理（libs.versions.toml）

```toml
[versions]
minecraft = "26.1.2"          # 对应 1.21.11
fabric-loader = "0.19.2"
fabric-api = "0.149.0"
forge = "..."                 # 待定
neoforge = "..."              # 待定
sqlite = "3.45.1"
bcrypt = "0.10.2"
slf4j = "2.0.9"
junit = "5.10.1"

[libraries]
sqlite = { module = "org.xerial:sqlite-jdbc", version.ref = "sqlite" }
bcrypt = { module = "at.favre.lib:bcrypt", version.ref = "bcrypt" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
```

---

# 第二部分：Core 模块设计

## 4. 核心 API 接口

```java
// core/src/main/java/org/edtp/theexchange/api/ExchangeAPI.java

public interface ExchangeAPI {
    // 由适配层实现，在 core 初始化时注入

    /** 物品序列化：MC ItemStack → 中立模型 */
    ItemSerializer getItemSerializer();

    /** 配置文件加载 */
    ConfigLoader getConfigLoader();

    /** 日志输出（桥接到 MC 日志系统） */
    Logger getLogger();

    /** 调度异步任务到主线程 */
    void runOnMainThread(Runnable task);

    /** 调度异步任务到后台线程池 */
    void runAsync(Runnable task);

    /** 获取本服显示名称 */
    String getServerName();

    /** 获取本服版本信息（用于兼容性协商） */
    String getServerVersion();
}
```

```java
// core/src/main/java/org/edtp/theexchange/api/ExchangeEvent.java

/** 供适配层订阅后转发到各 Mod Loader 的事件系统 */
public interface ExchangeEventListener {
    void onRemoteServerOnline(String serverName);
    void onRemoteServerOffline(String serverName);
    void onPlayerOperation(String playerName, OperationType type, String targetServer, boolean success);
}
```

## 5. 核心初始化流程

```
TheExchangeCore.initialize(ExchangeAPI api)
    │
    ├─► ConfigLoader.load()                    # 加载配置
    ├─► PasswordHasher.sanitize(config)         # 自动哈希明文密码
    ├─► DatabaseManager.initialize(databasePath)# 建库建表
    ├─► ServerRegistry.load(config, db)         # 加载远程服务器列表
    ├─► CacheManager.initialize(db)             # 初始化缓存管理器
    ├─► NetworkManager.start(port, password)    # 启动本机 TCP 服务器
    │     ├─► TcpServer.start()
    │     └─► 对每个已配置远程服务器，尝试连接
    │           └─► TcpClient.connect(addr, port, password)
    ├─► HeartbeatManager.start(interval=10s)    # 启动心跳定时器
    ├─► SyncEngine.initialize()
    ├─► OperationLogger.scheduleCleanup(cron)   # 启动日志清理定时任务
    └─► 注册 /exchange 指令（由适配层完成）
```

---

# 第三部分：网络协议设计

## 6. 帧格式

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        Magic (4B)                             |
|                      0x45584348 "EXCH"                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          Version (2B)         |          Length (4B)          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Length (cont) |    Type (2B)  |          Sequence (8B)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Sequence (cont)                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Timestamp (8B)                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Timestamp (cont)                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                    Payload (structured binary, variable)      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| 字段 | 大小 | 类型 | 说明 |
|------|------|------|------|
| Magic | 4 字节 | u32 | 固定值 `0x45584348`（"EXCH"） |
| Version | 2 字节 | u16 | 协议版本号，初始为 1。不兼容变更递增 |
| Length | 4 字节 | u32 | Payload 字节数（不含帧头） |
| Type | 2 字节 | u16 | 帧类型（见 6.1） |
| Sequence | 8 字节 | u64 | 单调递增序列号（防重放，每连接独立） |
| Timestamp | 8 字节 | s64 | Unix 毫秒时间戳（防重放） |
| Payload | 变长 | bytes | 按消息类型固定字段顺序编码的结构化二进制 |

**帧头固定 28 字节。**

### 6.0 帧大小限制

- `FrameDecoder` 必须在读取 `Length` 后、分配 payload 缓冲区前校验大小。
- 首期硬编码 `MAX_FRAME_SIZE = 10 * 1024 * 1024`（10 MiB，指 Payload 最大长度），超过上限立即记录安全日志并关闭连接。
- `Length` 不得为负数、不得超过配置上限、不得导致 `header + payload` 整数溢出。
- 心跳超时保持 30 秒；最大帧大小不得配置到在常见服务器带宽下可能阻塞发送队列超过心跳超时时间。

### 6.1 帧类型枚举

| 类型值 | 常量名 | 方向 | 说明 |
|--------|--------|------|------|
| 0x0000 | `AUTH_CHALLENGE` | S→C | 认证挑战 nonce |
| 0x0001 | `AUTH_REQUEST` | C→S | 认证请求 |
| 0x0002 | `AUTH_RESPONSE` | S→C | 认证结果 |
| 0x0003 | `HEARTBEAT` | 双向 | 心跳 PING/PONG |
| 0x0010 | `QUERY_TIMESTAMP` | C→S | 查询最后修改时间戳 |
| 0x0011 | `TIMESTAMP_RESPONSE` | S→C | 返回时间戳 |
| 0x0012 | `QUERY_ITEMS` | C→S | 查询物品列表（全量） |
| 0x0013 | `ITEMS_RESPONSE` | S→C | 返回物品列表 |
| 0x0020 | `PUT_ITEM` | C→S | 放入物品 |
| 0x0021 | `PUT_ITEM_RESPONSE` | S→C | 放入结果 |
| 0x0022 | `TAKE_ITEM` | C→S | 取出物品 |
| 0x0023 | `TAKE_ITEM_RESPONSE` | S→C | 取出结果 |
| 0x0030 | `PUSH_UPDATE` | S→C | 被动推送（对方物品变更通知） |
| 0xFFFF | `ERROR` | 双向 | 协议层错误 |

## 7. 消息体定义

所有消息体使用结构化二进制序列化：字段顺序由消息类型唯一确定，整数字段使用定长大端编码，字符串与字节数组使用 4 字节长度前缀（`-1` 表示 null）后跟原始 UTF-8/字节内容。以下用伪 JSON 描述字段语义，实际传输不包含 key 名称。

### 7.1 AUTH_CHALLENGE（0x0000） / AUTH_REQUEST（0x0001）

```json
// Challenge
{
    "server_nonce": "base64-random-32-bytes",
    "expires_at": 1715900005000
}

// Request
{
    "server_name": "Survival",
    "client_nonce": "base64-random-32-bytes",
    "auth": "base64-hmac-sha256(password_key, server_nonce || client_nonce || server_name)",
    "version": "1.0",
    "mc_version": "1.21.11"
}
```

### 7.2 AUTH_RESPONSE（0x0002）

```json
{
    "success": true,
    "message": "Auth OK",                // 失败时的原因
    "server_name": "Creative",           // 对方服务器显示名称
    "mc_version": "1.21.11",             // 对方 MC 版本
    "last_modified_timestamp": 1715900000000
}
```

### 7.3 HEARTBEAT（0x0003）

```json
{
    "is_reply": false,                   // true = PONG, false = PING
    "timestamp": 1715900000000
}
```

### 7.4 QUERY_TIMESTAMP（0x0010） / TIMESTAMP_RESPONSE（0x0011）

```json
// Request
{
    "cached_timestamp": 1715900000000    // 本地缓存的时间戳，0 表示无缓存
}

// Response
{
    "current_timestamp": 1715900100000,
    "changed": true                      // true = 需要拉取，false = 缓存仍有效
}
```

### 7.5 QUERY_ITEMS（0x0012） / ITEMS_RESPONSE（0x0013）

```json
// Request
{
    "offset": 0,
    "limit": 54                          // 最大槽位数，支持分页
}

// Response
{
    "items": [
        {
            "slot": 0,
            "item_id": "minecraft:diamond",
            "count": 64,
            "version": 3,
            "display_name": "{\"text\":\"钻石\"}",
            "extra_data": null,          // NBT tag bytes, hex-encoded 或 null
            "incompatible": false,
            "added_by": "uuid-string",
            "added_at": 1715900000000
        }
    ],
    "total_slots": 54,
    "timestamp": 1715900100000,          // 当前时间戳，用于后续增量比对
    "server_version": "1.21.11"
}
```

### 7.6 PUT_ITEM（0x0020） / PUT_ITEM_RESPONSE（0x0021）

```json
// Request
{
    "request_id": "uuid-v7-or-random-uuid",
    "slot": 5,
    "expected_version": 0,                // 0 = 期望空槽；>0 = 期望覆盖/堆叠的槽位版本
    "item_id": "minecraft:diamond",
    "count": 64,
    "display_name": "{\"text\":\"钻石\"}",
    "extra_data": null,
    "player_uuid": "uuid",
    "player_name": "Steve"
}

// Response
{
    "success": true,
    "request_id": "uuid-v7-or-random-uuid",
    "slot": 5,
    "current_item": { ... },             // 操作后该槽位实际状态，包含 version
    "fail_reason": null,                 // "VERSION_MISMATCH", "SLOT_OCCUPIED", "INCOMPATIBLE"
    "new_timestamp": 1715900105000
}
```

### 7.7 TAKE_ITEM（0x0022） / TAKE_ITEM_RESPONSE（0x0023）

```json
// Request
{
    "request_id": "uuid-v7-or-random-uuid",
    "slot": 5,
    "expected_item_id": "minecraft:diamond",  // 乐观锁：期望的物品 ID
    "expected_count": 64,                     // 乐观锁：期望的数量
    "expected_version": 3,                    // 乐观锁：期望的槽位版本
    "request_count": 32,                       // 实际要取出的数量
    "player_uuid": "uuid",
    "player_name": "Steve"
}

// Response
{
    "success": true,
    "request_id": "uuid-v7-or-random-uuid",
    "slot": 5,
    "current_item": { ... },             // 操作后该槽位实际状态（可能为空，非空时包含 version）
    "fail_reason": null,                 // "INSUFFICIENT", "VERSION_MISMATCH", "INCOMPATIBLE"
    "new_timestamp": 1715900110000,
    "items_to_give": {                   // 成功时：接收方应给予玩家的物品数据
        "item_id": "minecraft:diamond",
        "count": 32,
        "display_name": "{\"text\":\"钻石\"}",
        "extra_data": null
    }
}
```

### 7.8 PUSH_UPDATE（0x0030）

```json
{
    "changed_slots": [5, 12],
    "timestamp": 1715900120000
}
```

### 7.9 ERROR（0xFFFF）

```json
{
    "code": 401,
    "message": "Authentication failed"
}
```

| 错误码 | 说明 |
|--------|------|
| 401 | 认证失败 |
| 403 | 权限不足 |
| 404 | 物品/槽位不存在 |
| 409 | 冲突（乐观锁失败，需重新拉取） |
| 429 | 速率限制 |
| 500 | 服务端内部错误 |
| 503 | 对方服务器离线 |

## 8. 连接生命周期

```
发起方 (Client)                          接收方 (Server)
     │                                        │
     │──── TCP connect ───────────────────────│
     │──── TLS 1.3 握手 ──────────────────────│
     │       (自签名证书, 信任基于密码)        │
     │◄─── AUTH_CHALLENGE ───────────────────│
     │──── AUTH_REQUEST ──────────────────────│
     │                                        │── 校验 HMAC + nonce
     │                                        │── 检查黑白名单
     │◄─── AUTH_RESPONSE ────────────────────│
     │                                        │
     │  [认证成功，开始心跳]                    │
     │◄───► HEARTBEAT (每 10 秒) ────────────│
     │                                        │
     │  [连接超时 30 秒未收到心跳 → 标记离线]   │
     │                                        │
     │  [业务请求 / 响应]                       │
     │──── QUERY_TIMESTAMP ───────────────────│
     │◄─── TIMESTAMP_RESPONSE ───────────────│
     │──── QUERY_ITEMS ──────────────────────│
     │◄─── ITEMS_RESPONSE ───────────────────│
     │                                        │
     │──── PUT_ITEM / TAKE_ITEM ─────────────│
     │◄─── PUT_ITEM_RESPONSE / TAKE_ITEM_RESP│
```

## 9. 防重放机制

```
每个连接维护：
  - sendSequence:     AtomicLong   # 发送序列号（单调递增）
  - recvSequenceWindow: SequenceWindow  # 接收序列号滑动窗口

SequenceWindow (大小: 1024):
  ┌───┬───┬───┬───┬───┬───┬─────┬───┐
  │ 0 │ 1 │ 1 │ 0 │ 1 │ 0 │ ... │ 0 │   bitset
  └───┴───┴───┴───┴───┴───┴─────┴───┘
   base                                  base + 1023

  - base: 该窗口可接收的最小序列号
  - window: 1024 位的 bitset

验证逻辑：
  1. 若 seq < base → 丢弃（太旧，可能重放）
  2. 若 seq >= base + 1024 → 推进窗口 base = seq - 512
  3. 若 seq 在窗口内 but bit[seq - base] == 1 → 丢弃（重放）
  4. 否则 → 标记 bit[seq - base] = 1，接受

时间戳校验：
  - 若 |msg.timestamp - System.currentTimeMillis()| > 60s → 丢弃
```

---

# 第四部分：数据库设计

## 10. SQLite 表结构

### 10.1 本服物品表

```sql
CREATE TABLE IF NOT EXISTS exchange_items (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    slot       INTEGER NOT NULL UNIQUE,   -- 槽位号 0~53
    item_data  BLOB   NOT NULL,           -- 结构化二进制序列化的 NeutralItem
    item_id    TEXT   NOT NULL,           -- 快速校验与日志查询
    count      INTEGER NOT NULL,           -- 当前数量，必须与 item_data 内数量一致
    added_by   TEXT,                      -- 放入者 UUID
    added_at   INTEGER NOT NULL,          -- Unix 毫秒时间戳
    updated_at INTEGER NOT NULL,          -- Unix 毫秒时间戳
    version    INTEGER NOT NULL DEFAULT 1 -- 乐观锁版本号
);

CREATE INDEX IF NOT EXISTS idx_items_slot ON exchange_items(slot);
```

`exchange_items` 只保存本服共享空间的权威库存。其他服务器访问本服库存时，必须通过本服的 `ExchangeService` 和数据库事务修改该表；远程缓存表不得反向写入本表。

### 10.2 远程服务器配置表

```sql
CREATE TABLE IF NOT EXISTS remote_servers (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL UNIQUE,   -- 本服本地别名
    address     TEXT    NOT NULL,          -- IP/域名
    port        INTEGER NOT NULL,          -- TCP 端口
    password    TEXT    NOT NULL,          -- bcrypt 哈希
    enabled     INTEGER NOT NULL DEFAULT 1
);
```

### 10.3 远程缓存表

```sql
CREATE TABLE IF NOT EXISTS remote_cache (
    server_name TEXT    NOT NULL UNIQUE,  -- 远程服务器别名
    items_blob  BLOB    NOT NULL,         -- 结构化二进制序列化的物品列表
    synced_at   INTEGER NOT NULL,         -- Unix 毫秒（本地最后同步时间）
    remote_timestamp INTEGER NOT NULL,    -- 远程服务器最后修改时间戳
    PRIMARY KEY (server_name)
);
```

### 10.4 操作日志表

```sql
CREATE TABLE IF NOT EXISTS operation_log (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp    INTEGER NOT NULL,          -- Unix 毫秒
    op_type      TEXT    NOT NULL,          -- 'PUT' | 'TAKE'
    player_uuid  TEXT    NOT NULL,
    player_name  TEXT    NOT NULL,
    server_name  TEXT    NOT NULL,          -- 远程服务器名称
    item_id      TEXT    NOT NULL,          -- 如 "minecraft:diamond"
    quantity     INTEGER NOT NULL,
    result       TEXT    NOT NULL,          -- 'SUCCESS' | 'FAIL'
    fail_reason  TEXT
);

CREATE INDEX IF NOT EXISTS idx_log_timestamp ON operation_log(timestamp);
CREATE INDEX IF NOT EXISTS idx_log_player    ON operation_log(player_uuid);
CREATE INDEX IF NOT EXISTS idx_log_server    ON operation_log(server_name);
```

### 10.5 已处理请求表（幂等）

```sql
CREATE TABLE IF NOT EXISTS processed_requests (
    request_id   TEXT PRIMARY KEY,
    peer_server  TEXT    NOT NULL,
    op_type      TEXT    NOT NULL,          -- 'PUT' | 'TAKE'
    slot         INTEGER NOT NULL,
    result_blob  BLOB    NOT NULL,          -- 结构化二进制序列化的响应
    created_at   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_processed_peer ON processed_requests(peer_server);
CREATE INDEX IF NOT EXISTS idx_processed_time ON processed_requests(created_at);
```

权威服务器在处理 `PUT_ITEM` / `TAKE_ITEM` 前先查询 `processed_requests`。若 `request_id` 已存在，直接返回已保存响应，不再次修改库存；若不存在，则在同一个数据库事务内完成库存变更、日志写入和请求结果保存。

`processed_requests` 不是永久审计表，必须由定时清理任务删除过期记录。首期建议保留 1 小时，配置项为 `idempotency_retention_minutes`；清理阈值必须大于请求超时、重连抖动和防重放时间窗口。

### 10.6 放入暂存与离线补偿表

```sql
CREATE TABLE IF NOT EXISTS player_compensation (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid  TEXT    NOT NULL,
    player_name  TEXT    NOT NULL,
    item_blob    BLOB    NOT NULL,          -- 结构化二进制序列化的 NeutralItem
    reason       TEXT    NOT NULL,          -- 'PUT_ROLLBACK' | 'TAKE_DELIVERY'
    created_at   INTEGER NOT NULL,
    delivered_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_comp_player ON player_compensation(player_uuid);
CREATE INDEX IF NOT EXISTS idx_comp_pending ON player_compensation(delivered_at);
```

`InFlightPutStore` 优先使用内存结构保存 PUT 已从玩家背包真实扣除但远程未确认的物品。服务器关闭、玩家断线或回滚发还失败时，将物品写入 `player_compensation`，玩家下次上线后尝试发还；若玩家在线但背包已满，则掉落在玩家当前位置。

### 10.7 本服配置表（键值对）

```sql
CREATE TABLE IF NOT EXISTS exchange_config (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- 存储项：
-- 'server.display_name' → '我的世界生存服'
-- 'server.password'     → '$2a$10$...' (bcrypt)
-- 'server.port'         → '25566'
-- 'log.retention_days'  → '30'
```

### 10.8 数据库迁移策略

```java
// core/.../storage/migration/Migrations.java
public class Migrations {
    private static final List<Migration> MIGRATIONS = List.of(
        new Migration(1, "CREATE TABLE IF NOT EXISTS exchange_config (...)"),
        new Migration(2, "ALTER TABLE exchange_items ADD COLUMN version INTEGER NOT NULL DEFAULT 1"),
        // ...
    );

    public static void run(DatabaseManager db) {
        int currentVersion = db.getSchemaVersion();
        for (Migration m : MIGRATIONS) {
            if (m.version > currentVersion) {
                db.execute(m.sql);
                db.setSchemaVersion(m.version);
            }
        }
    }
}
```

---

# 第五部分：业务逻辑设计

## 11. 同步流程

### 11.1 打开共享空间（玩家触发 /exchange view）

```
Player ─────► Server (本服) ─────► Remote (目标服)
  │                │                      │
  │   /exchange    │                      │
  │   view A       │                      │
  │                │── QUERY_TIMESTAMP ──►│
  │                │   (cached_ts)        │
  │                │                      │── 比对时间戳
  │                │◄── TIMESTAMP_RESPONSE│
  │                │   (changed=true)     │
  │                │                      │
  │                │── QUERY_ITEMS ──────►│
  │                │◄── ITEMS_RESPONSE ───│
  │                │                      │
  │                │── 更新 remote_cache   │
  │                │── 构建虚拟容器        │
  │◄── 打开容器 ──│                      │
  │   界面        │                      │
```

### 11.2 增量同步触发条件

| 场景 | 操作 | 说明 |
|------|------|------|
| 玩家打开容器 | 时间戳比对 | 一致 → 直接用缓存；不一致 → 拉取全量 |
| 玩家放入成功 | 操作响应自带 | PUT_ITEM_RESPONSE 含 `current_item` + `new_timestamp` |
| 玩家取出成功 | 操作响应自带 | TAKE_ITEM_RESPONSE 含 `current_item` + `new_timestamp` |
| 对方推送通知 | PUSH_UPDATE | 仅通知哪些槽位变了，不传物品数据 |
| 玩家手动 /exchange refresh | 全量拉取 | 跳过时间戳比对 |
| 对方离线后恢复 | 全量拉取 | 重连后自动触发 |

### 11.3 缓存一致性边界

- `remote_cache` 是只读展示快照，不能作为库存真相，也不能在失败或超时后推测更新。
- 所有 `PUT` / `TAKE` 必须发往目标服务器，由目标服务器作为权威写入方提交事务。
- 成功响应必须携带 `request_id`、`new_timestamp` 和变更槽位的新 `version`；发起方只在收到成功响应后更新缓存。
- `PUSH_UPDATE` 只作为失效通知。收到推送后，若玩家正在查看对应共享空间，后台拉取最新数据并刷新 GUI；不得直接根据推送内容改库存。
- 网络超时后请求状态未知，发起方不得自动重试会改变库存的请求。若需要重试，必须使用同一个 `request_id`，依赖权威服务器的幂等结果。

### 11.4 离线处理

```
if (目标服务器在线状态 == OFFLINE) {
    // 允许打开容器
    容器标题 = "[离线] " + serverName
    物品数据 = remote_cache 中缓存数据
    
    // 拒绝所有写操作
    玩家拖拽物品 → 弹回物品 → 聊天栏提示 "目标服务器离线，仅可查看"
}
```

## 12. 物品放入流程

```
Player 拖拽物品从背包到容器槽位
    │
    ▼
适配层 (container click handler)
    │── 解析槽位号 + 物品数据
    │── 调用 ExchangeService.putItem(slot, itemStack, player)
    ▼
ExchangeService.putItem()
    │
    ├─► 校验目标服务器在线状态
    │     └─ 离线 → 拒绝, 物品弹回, 提示玩家
    │
    ├─► 序列化物品: ItemSerializer.toNeutral(itemStack)
    │
    ├─► 在主线程真实扣除玩家背包中的 ItemStack
    │     ├─ 扣除成功 → 写入 InFlightPutStore(request_id, player, item)
    │     └─ 扣除失败 → 取消操作并刷新容器/背包
    │
    ├─► 从 remote_cache 获取目标槽位 expected_version（空槽为 0）
    │
    ├─► 构建 PUT_ITEM 请求
    │     └─ Connection.send(PUT_ITEM, {request_id, slot, expected_version, item})
    │
    ├─► 等待响应 (超时 5 秒)
    │     ├─ PUT_ITEM_RESPONSE.success=true
    │     │   ├─ 清除 InFlightPutStore 中的暂存物品
    │     │   ├─ 更新 remote_cache（该槽位数据 + version + 新时间戳）
    │     │   └─ 向玩家确认（容器该槽位显示物品）
    │     │
    │     └─ PUT_ITEM_RESPONSE.success=false / 超时
    │         ├─ 从 InFlightPutStore 取出暂存物品
    │         ├─ 主线程尝试塞回玩家背包；背包满则掉落在玩家位置
    │         ├─ 玩家离线/世界不可用 → 写入 player_compensation
    │         └─ 提示玩家失败原因
    │
    └─► OperationLogger.log(PUT, player, server, item, result)
```

## 13. 物品取出流程（含乐观锁）

```
Player 拖拽物品从容器到背包
    │
    ▼
ExchangeService.takeItem(slot, requestCount, player)
    │
    ├─► 校验目标服务器在线
    │     └─ 离线 → 拒绝
    │
    ├─► 从 remote_cache 获取该槽位当前状态 (expected_item_id, expected_count)
    │
    ├─► 构建 TAKE_ITEM 请求 (含乐观锁版本信息)
    │     └─ Connection.send(TAKE_ITEM, {request_id, slot, expected_item_id, expected_count, expected_version, request_count})
    │
    ├─► 等待响应
    │     ├─ TAKE_ITEM_RESPONSE.success=true
    │     │   ├─ 在主线程重新校验玩家在线、未移除、容器仍有效
    │     │   ├─ 校验通过 → 用 items_to_give 反序列化为 ItemStack 给玩家
    │     │   ├─ 校验失败 → 掉落在玩家最后位置或写入 player_compensation
    │     │   ├─ 更新 remote_cache（该槽位变为 current_item 或空，非空时带新 version）
    │     │   └─ 更新容器显示
    │     │
    │     ├─ TAKE_ITEM_RESPONSE {fail_reason="VERSION_MISMATCH"}
    │     │   ├─ 拉取最新数据 → 警告玩家 "物品已被他人修改，请重试"
    │     │   └─ 刷新容器界面
    │     │
    │     └─ TAKE_ITEM_RESPONSE {fail_reason="INSUFFICIENT"}
    │         └─ 提示 "物品数量不足"
    │
    └─► OperationLogger.log(TAKE, player, server, item, result)
```

## 14. 并发控制（乐观锁）

### 14.1 一致性原则

- **权威服务器唯一写入**：某台服务器的共享空间只由该服务器本地数据库决定。本服玩家和外服玩家同时操作时，都进入同一个本地 `ExchangeService` 写入路径。
- **锁不跨网络**：任何 JVM 锁、分段锁或 SQLite 事务都不得在等待远程服务器响应时持有。网络调用只发生在发起方，权威方只在收到完整请求后开启短事务。
- **同槽位版本递增**：每次成功修改槽位都让该槽位 `version += 1`，并更新全局 `last_modified_timestamp`。
- **连接顺序不等于业务顺序**：单连接内按帧序处理；不同连接、不同服务器来的请求按到达权威服务器并成功提交事务的顺序生效。
- **真实扣除优先**：PUT 发起方必须先在服务端真实扣除玩家背包物品并放入 `InFlightPutStore`，GUI pending 只能作为视觉状态，不能作为库存安全边界。
- **回调默认失效**：所有异步回调回到主线程后，都必须假设玩家可能已离线、死亡、换维度或关闭容器；发还/发放失败必须进入掉落或 `player_compensation`。

### 14.2 取出竞争示例

```
Server A (本服)                       Server B (物品存储方)
     │                                      │
     │── TAKE_ITEM ────────────────────────►│
     │   {request_id, slot:5,               │
     │    expected_count:64,                │
     │    request_count:32,                 │
     │    expected_version:3}               │
     │                                      │── BEGIN TRANSACTION
     │                                      │── SELECT result FROM processed_requests WHERE request_id=?
     │                                      │   └─ 若存在：直接返回历史响应
     │                                      │── SELECT * FROM items WHERE slot=5
     │                                      │── 比对: version==3? count>=32?
     │                                      │   ├─ YES: UPDATE count=32, version=4
     │                                      │   │        INSERT operation_log
     │                                      │   │        INSERT processed_requests
     │                                      │   │        COMMIT
     │                                      │   │        返回 {success:true, current:{count:32,...}}
     │                                      │   └─ NO:  ROLLBACK
     │                                      │           返回 {success:false, fail_reason:"VERSION_MISMATCH"}
     │◄── TAKE_ITEM_RESPONSE ───────────────│
     │                                      │
```

- 冲突概率低（≤10 台服务器，玩家操作频率不高），乐观锁适合此场景。
- 冲突时客户端刷新界面即可，无需复杂的重试机制。

### 14.3 放入竞争规则

| 场景 | 判定 | 结果 |
|------|------|------|
| 两个玩家同时向空槽放入 | 两个请求都携带 `expected_version=0` | 先提交者成功，后提交者 `VERSION_MISMATCH` |
| 玩家向已有槽位堆叠 | `item_id`、兼容性、最大堆叠数和 `expected_version` 都匹配 | 事务内合并数量并递增版本 |
| 玩家向已有槽位放入不同物品 | 槽位非空且物品不一致 | 拒绝，返回 `SLOT_OCCUPIED` |
| 放入请求超时后重发 | 使用同一 `request_id` | 权威服务器返回第一次处理结果 |

### 14.4 SQLite 写入策略

- 数据库启用 WAL 模式，设置 `busy_timeout`，读写连接分离；写入仍由 SQLite 串行提交。
- `LocalItemStore.putItemTransactional` 和 `takeItemTransactional` 是唯一修改 `exchange_items` 的方法。
- 事务顺序：查询幂等表 → 查询槽位 → 校验版本/数量/兼容性 → 更新库存 → 写操作日志 → 保存幂等响应 → 提交。
- 首期优先使用单线程 `DbWriter` 队列串行执行所有写事务，避免多个 Core Worker 线程争抢 SQLite 全局写锁。
- `StripedLock` 降级为可选优化，仅当未来改用多写线程或非 SQLite 存储时启用；正确性仍以数据库事务和版本号为准。

## 15. 心跳与在线检测

```
HeartbeatManager：
  - 每 10 秒遍历所有连接，发送 HEARTBEAT PING
  - 连接维护上次收到任何数据的时间戳 lastRecvTime
  - 检测线程每 5 秒检查: now - lastRecvTime > 30s → 标记离线
  - 离线后启动重连定时器：指数退避 5s → 10s → 20s → 30s (max)
  - 重连成功 → 全量同步该服务器库存
  - 连接状态变更 → 触发 ExchangeEvent → 通知所有在线玩家的 GUI
```

---

# 第六部分：安全设计

## 16. 密码管理

```java
// core/.../security/PasswordHasher.java
public class PasswordHasher {
    // bcrypt, cost=12
    public static String hash(String plainPassword) {
        return BCrypt.withDefaults().hashToString(12, plainPassword.toCharArray());
    }

    public static boolean verify(String plainPassword, String hash) {
        return BCrypt.verifyer().verify(plainPassword.toCharArray(), hash).verified;
    }
}
```

- 配置文件中密码若为明文，首次加载时自动 bcrypt 哈希化并回写配置。
- 网络传输中**不传输明文密码或可复用的密码哈希**。TLS 建立后，接收方生成一次性 challenge nonce，发起方返回 `HMAC-SHA256(password, challenge_nonce || client_nonce || server_name)`。
- 接收方用本地保存的密码材料校验 HMAC。若配置中只保存 bcrypt/argon2 派生值，则实现需要保存一个专用于 HMAC 的随机 salt 派生密钥，不能把 bcrypt hash 当作可长期复用的 bearer token 发送。
- 认证成功后才接受业务帧；认证失败、nonce 过期或 nonce 重放均关闭连接并记录安全事件。

## 17. TLS 配置

```
- TLS 1.3 only (禁止回退到 TLS 1.2 及以下)
- 传输加密实现优先级：
  1. 优先使用 JDK/运行环境可直接支持的 TLS 1.3 配置，避免引入大型证书生成依赖
  2. 若必须使用自签名证书，提供轻量证书生成实现或明确引入可 shade 的最小依赖
  3. 若目标 JDK/安全提供者支持 TLS-PSK，可评估使用密码派生 PSK，减少 X.509 证书复杂度
- 密码认证替代 PKI 信任链：即使 TLS 握手完成，仍需通过 AUTH_REQUEST/AUTH_RESPONSE 鉴权
- 密码套件：TLS_AES_256_GCM_SHA384
```

自签名证书不是身份信任的唯一来源，身份仍由 HMAC challenge-response 证明。实现时不得为了生成 X.509 证书而引入无法 relocate 或体积过大的依赖；若引入 BouncyCastle，必须在发布 jar 中 shade/relocate 并记录兼容性风险。

## 18. 数据安全层级

| 层级 | 措施 |
|------|------|
| 传输 | TLS 1.3 + 自签名证书 |
| 认证 | bcrypt 密码哈希 |
| 防篡改 | TLS 内置消息完整性校验 |
| 防重放 | 单调序列号 + 滑动窗口 + 时间戳 |
| 存储 | bcrypt 哈希化密码存储，物品数据结构化二进制序列化 |

---

# 第七部分：跨版本兼容

## 19. 中立物品协议

不同 MC 版本之间传输物品时不直接传 NBT，而是使用最小化中立格式：

```java
// core/.../compat/NeutralItem.java
public class NeutralItem {
    private String itemId;           // "minecraft:diamond"
    private int count;
    private String displayName;      // JSON 文本组件字符串，如 '{"text":"钻石"}'
    private byte[] extraData;        // 可选：NBT/Data Components 的黑盒透传字节
    private boolean incompatible;    // 接收方标记此物品是否可解析
    private String sourceVersion;    // 物品来源的 MC 版本，如 "1.21.11"
}
```

## 20. 兼容性判定规则

```
发送方序列化时：
  ItemStack → NeutralItem
  若物品包含已知在新版本中有变化的 NBT → 标记在 extraData 中
  sourceVersion = 发送方 MC 版本

接收方反序列化时：
  NeutralItem → ItemStack (reconstructed)
  1. item_id 在本地 Registry 中找不到 → incompatible = true
  2. extraData 解析失败 → incompatible = true
  3. 两者都 OK → incompatible = false, 正常渲染

兼容性策略（接收方 = 目标服，即物品存储方）：
  - 目标服总是能做最终兼容性判定
  - 不兼容物品：显示为屏障方块 + Lore "不兼容 - {item_id}"
  - 不兼容物品禁止拖拽取出
  - 不兼容物品可以被有权限的管理员清理（未来扩展）
```

### 20.1 黑盒透传原则

当目标权威服务器无法解析 `extraData`（例如 1.20.x 服务器收到 1.21.x Data Components）时：

- 不得丢弃、重写、规范化或尝试“清理”未知字节。
- `exchange_items.item_data` 必须原样保存发送方传来的 `extraData`、`sourceVersion` 和兼容性标记。
- 查询和取出响应必须原样返回这些字节，让能够理解该版本数据的服务器或客户端恢复物品。
- 权威服务器只对通用字段做最小校验：`item_id` 非空、`count` 合法、payload 未超过大小限制、协议版本允许。
- 对本服无法解析的物品，GUI 使用占位物展示并禁止本服玩家取出；远端是否可取出由发起方反序列化能力和协议兼容性共同决定。

## 21. 跨版本接口隔离

```java
// core/.../compat/ItemSerializer.java (核心接口)
public interface ItemSerializer {
    /** MC ItemStack → 中立协议格式 */
    NeutralItem serialize(Object mcItemStack);

    /** 中立协议格式 → MC ItemStack（可能为不兼容物品） */
    Object deserialize(NeutralItem neutralItem);
}

// 各适配层各自实现：
// - fabric/item/FabricItemSerializer.java
// - forge/item/ForgeItemSerializer.java
// - neoforge/item/NeoForgeItemSerializer.java
```

不同 MC 版本之间的 ItemSerializer 只需要处理各自版本内的 ItemStack ↔ NeutralItem 转换。同一加载器的不同 MC 版本（如 Fabric 1.20.4 vs Fabric 1.21.11）必须使用不同的 ItemSerializer 实现（NBT 与 Data Components API 不兼容），建议拆成 `fabric-1.20.4`、`fabric-1.21.11` 等子模块或 source set，并分别产出构建产物。不要在同一个 JVM 编译目标中同时依赖旧版 NBT API 和新版 Data Components API。

---

# 第八部分：虚拟容器设计

## 22. 原版容器实现（仅服务端）

核心原理：利用 Minecraft 的 `ServerPlayer.openMenu()` 打开一个自定义 `AbstractContainerMenu`，服务端完全控制容器内物品的增删。

### 22.1 Menu 类型选择

使用 **GenericContainerScreen**（通用箱子 GUI）：

```
容器类型: MenuType.GENERIC_9x6 (54 槽位箱子)
├── 槽位 0~53: 远程空间物品（上半部分，6 行 x 9 列）
├── 槽位 54~80: 玩家背包（下半部分，27 槽）
└── 槽位 81~89: 玩家快捷栏（9 槽）
```

### 22.2 虚拟容器实现要点

```java
// fabric/.../container/ExchangeMenu.java (概念示意，非完整代码)
public class ExchangeMenu extends AbstractContainerMenu {
    private final String serverName;
    private final boolean isOnline;
    private final SimpleContainer virtualInventory;  // 54 槽位虚拟容器
    private final Player player;
    private boolean readOnly;

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        // slot 0~53: 远程空间物品
        // slot 54~89: 玩家背包+快捷栏

        if (slotIndex < 54) {
            // 从远程空间 → 玩家背包 = TAKE
            if (readOnly) { return ItemStack.EMPTY; }
            // 调用 ExchangeService.takeItem(...)
        } else {
            // 从玩家背包 → 远程空间 = PUT
            if (readOnly) { return ItemStack.EMPTY; }
            // 调用 ExchangeService.putItem(...)
        }
    }

    @Override
    public boolean stillValid(Player player) {
        // 始终有效（或设置合理超时）
        return !player.isRemoved();
    }
}
```

### 22.3 关键适配层差异

| 适配层 | openMenu API | Menu 注册方式 |
|--------|-------------|---------------|
| Fabric | `player.openMenu(new SimpleMenuProvider(...))` | 直接 new Menu 实例 |
| Forge | `NetworkHooks.openScreen(player, provider, buf)` | 需注册 MenuType + IForgeContainerType |
| NeoForge | 同 Forge | 同 Forge |

---

# 第九部分：配置文件格式

## 23. 主配置文件（theexchange.json）

```json
{
    "server": {
        "display_name": "生存服-主城",
        "port": 25566,
        "password": "$2a$10$abcdefghijklmnopqrstuvwxyz1234567890"
    },
    "remote_servers": [
        {
            "name": "创造服",
            "address": "10.0.0.2",
            "port": 25566,
            "password": "$2a$10$..."
        },
        {
            "name": "资源服",
            "address": "10.0.0.3",
            "port": 25566,
            "password": "$2a$10$..."
        }
    ],
    "network": {
        "heartbeat_interval_seconds": 10,
        "heartbeat_timeout_seconds": 30,
        "reconnect_initial_delay_seconds": 5,
        "reconnect_max_delay_seconds": 30,
        "request_timeout_seconds": 5,
        "max_frame_size_bytes": 10485760
    },
    "cache": {
        "offline_retention_hours": 24,
        "idempotency_retention_minutes": 60
    },
    "logging": {
        "retention_days": 30,
        "cleanup_interval_hours": 1
    },
    "container": {
        "rows": 6,
        "title_template": "{server_name} 的共享空间"
    }
}
```

---

# 第十部分：适配层接口规范

## 24. 适配层必须实现的接口

每个 Mod Loader 适配层需要实现以下注入点：

```java
// 适配层必须向 core 提供这些实现
interface AdapterProvider {
    ItemSerializer getItemSerializer();      // ItemStack ↔ NeutralItem
    ConfigLoader getConfigLoader();          // 加载/保存 JSON 配置
    Logger getLogger();                      // 日志桥接
    void registerCommands();                 // 注册 /exchange 指令
    void registerEvents();                   // 注册生命周期事件
    void openVirtualContainer(Player player, String serverName, List<NeutralItem> items, boolean isOnline);
    void sendChatMessage(Player player, Component message);
    void giveItem(Player player, NeutralItem item);       // 给玩家物品
    void removeItem(Player player, int slot, int count);  // 从玩家背包扣物品
    void dropItemAtPlayerOrLastPosition(UUID playerUuid, NeutralItem item); // 玩家不可用时的补偿掉落
    boolean isPlayerOp(Player player);                    // 判断是否 OP（管理员指令鉴权）
}
```

## 25. Mixin 最小化原则

Mixin 仅用于以下场景，且每个场景不超过 1 个 Mixin 类：

| Mixin 目标 | 用途 | 必要性 |
|------------|------|--------|
| `ServerPlayer` | 拦截玩家断线事件（清理打开的容器），监听物品栏交互 | 高 |
| `ServerGamePacketListenerImpl` | 拦截容器点击封包（处理虚��容器中的拖拽） | 高 |
| `MinecraftServer` | 监听服务器启动/关闭事件（启动/停止 NetworkManager） | 中 |

共计 **≤ 3 个 Mixin 类**，每个加载器各有一份独立但等价的 Mixin 实现。

---

# 第十一部分：线程模型

## 26. 线程划分

```
┌──────────────────────────────────────────────────┐
│                   Main Thread                     │
│  - Minecraft Server Tick                         │
│  - Event dispatching                             │
│  - Container interaction handling                │
│  - Command execution                             │
└──────────────────────────────────────────────────┘
           │ 调用 ExchangeService (async dispatch)
           ▼
┌──────────────────────────────────────────────────┐
│              Network I/O Threads                  │
│  (per-connection: 1 read + 1 write thread)       │
│  - TCP read/write                                │
│  - Frame encode/decode                           │
│  - TLS operations                                │
└──────────────────────────────────────────────────┘
           │ 消息反序列化后入队
           ▼
┌──────────────────────────────────────────────────┐
│            Core Worker Pool (4 threads)           │
│  - Business logic execution                      │
│  - Message handling                              │
└──────────────────────────────────────────────────┘
           │ 写事务入队
           ▼
┌──────────────────────────────────────────────────┐
│             DB Writer Thread (1 thread)           │
│  - SQLite write transactions                      │
│  - processed_requests / operation_log writes      │
│  - compensation writes                            │
└──────────────────────────────────────────────────┘
           │ 需要主线程执行的操作 (如 openMenu)
           ▼
┌──────────────────────────────────────────────────┐
│              Scheduled Tasks                      │
│  - Heartbeat timer (10s interval)                │
│  - Log cleanup timer (1h interval)               │
│  - Cache expiry timer (1h interval)              │
└──────────────────────────────────────────────────┘
```

- 网络 I/O 使用 `java.nio.channels`（非阻塞）或 Netty（若 Minecraft 环境允许复用）。
- SQLite 写事务统一进入单线程 DB Writer 队列；读操作可在 Core Worker Pool 使用只读连接执行。
- 所有对 MC 世界的修改必须在 Main Thread 执行（通过 `runOnMainThread` 调度）。

---

# 第十二部分：错误处理与恢复

## 27. 异常场景与处理

| 场景 | 处理策略 |
|------|----------|
| 网络超时（PUT/TAKE 请求 5 秒无响应） | PUT 从暂存区发还；TAKE 不生成物品；提示玩家，记录日志 |
| PUT 回滚时玩家离线/背包满 | 尝试掉落在玩家最后位置；失败则写入 `player_compensation` |
| TAKE 成功回调时玩家离线/容器关闭 | 掉落在玩家最后位置或写入 `player_compensation`，不得丢弃 |
| 连接断开 | 标记离线，启动重连，通知所有在线玩家的 GUI |
| 帧长度超过上限 | 记录安全日志，立即关闭连接 |
| 数据库写入失败 | 回滚事务，记录错误日志，提示管理员检查磁盘 |
| Payload 反序列化失败 | 丢弃该帧，记录错误日志，不中断连接 |
| TLS 握手失败 | 关闭连接，记录日志 |
| 认证失败 | 关闭连接，记录安全事件 |
| 服务器关闭 | 优雅关闭所有连接，保存缓存，停止定时任务 |

## 28. 优雅关闭流程

```
Server stopping event
    │
    ├─► HeartbeatManager.stop()
    ├─► 冻结新的玩家 PUT/TAKE 操作
    ├─► 将 InFlightPutStore 中未完成物品发还或写入 player_compensation
    ├─► NetworkManager.shutdown()
    │     ├─► 向所有连接发送 CLOSE 帧（若协议支持）
    │     ├─► 关闭所有 TcpClient 连接
    │     └─► 关闭 TcpServer
    ├─► 等待 3 秒（未完成的操作超时）
    ├─► DatabaseManager.close()
    └─► 标记模块已关闭
```

---

# 第十三部分：构建与发布

## 29. 构建产物

```
build/
├── libs/
│   ├── TheExchange-core-1.0.jar           # 核心库（可被其他 Mod 引用）
│   ├── TheExchange-fabric-1.0.jar         # Fabric 版（内嵌 core）
│   ├── TheExchange-forge-1.0.jar          # Forge 版（内嵌 core）
│   └── TheExchange-neoforge-1.0.jar       # NeoForge 版（内嵌 core）
└── ...
```

- 每个加载器的发布 jar 将 core 作为 shadow（内嵌）依赖，避免要求用户单独安装 core。
- `core` 模块也可以独立发布到 Maven 仓库。
- 使用 Gradle Shadow 插件将 core + 其依赖（SQLite JDBC, bcrypt）shade 并 relocate 到适配层 jar 中，避免与其他 Mod 的依赖冲突。

## 30. CI / 版本矩阵

目标覆盖版本：

| 加载器 | 1.20.4 | 1.20.6 | 1.21.0 | 1.21.11 | 1.22+ |
|--------|--------|--------|--------|---------|-------|
| Fabric | V2 | V2 | V2 | V1 | V3 |
| Forge | V2 | V2 | V2 | V1 | V3 |
| NeoForge | — | V2 | V2 | V1 | V3 |

- V1 = 首期发布
- V2 = 第二批适配
- V3 = 后续跟进

---

# 附录 A：关键类依赖关系图

```
TheExchangeCore
    ├──► ExchangeAPI (injected by adapter)
    ├──► ConfigLoader (via ExchangeAPI)
    ├──► DatabaseManager
    │       ├──► LocalItemStore
    │       ├──► RemoteCacheStore
    │       ├──► OperationLogger
    │       └──► ConfigStore
    ├──► ServerRegistry
    │       └──► RemoteServer (model)
    ├──► NetworkManager
    │       ├──► TcpServer
    │       │       └──► Connection (per peer)
    │       │               ├──► FrameDecoder / FrameEncoder
    │       │               ├──► MessageDecoder / MessageEncoder
    │       │               ├──► TlsContext
    │       │               └──► SequenceWindow
    │       └──► TcpClient
    │               └──► Connection (same as above)
    ├──► ExchangeService (main business logic)
    │       ├──► ServerRegistry
    │       ├──► CacheManager
    │       ├──► SyncEngine
    │       ├──► OperationLogger
    │       └──► Connection (via NetworkManager)
    ├──► HeartbeatManager
    │       └──► Connection (via NetworkManager)
    └──► ExchangeEventListener (to adapter layer)
```

---

# 附录 B：首期实施小计划

> 原则：先完成权威库存、事务和协议，再接 GUI。任何会改变库存的功能必须有并发测试后才能接入玩家交互。

## B.1 P0 Core 与构建骨架

| 小计划 | 内容 | 验收 |
|--------|------|------|
| P0.1 | 建立 Gradle 多模块、版本目录、core/fabric/forge/neoforge 空模块 | `./gradlew build` 可跑到空实现 |
| P0.2 | 定义 `ExchangeAPI`、事件、配置加载接口、日志接口 | 适配层可注入 mock API |
| P0.3 | 定义 `NeutralItem`、`ExchangeItem`、`RemoteServer`、结果枚举 | core 单元测试覆盖序列化 DTO |

## B.2 P1 权威存储与并发基座

| 小计划 | 内容 | 验收 |
|--------|------|------|
| P1.1 | SQLite 初始化、WAL、`busy_timeout`、迁移表 | 重复启动迁移幂等 |
| P1.2 | 创建 `exchange_items`、`operation_log`、`processed_requests`、`remote_cache`、`player_compensation` | 表结构与索引自动创建 |
| P1.3 | 实现 `putItemTransactional`：版本校验、堆叠规则、日志、幂等结果 | 并发空槽放入只有一个成功 |
| P1.4 | 实现 `takeItemTransactional`：版本/数量校验、扣减、日志、幂等结果 | 并发取同一槽不会超发 |
| P1.5 | 为同一 `request_id` 重放返回首次响应 | 重试不会重复放入或重复扣减 |
| P1.6 | 单线程 DB Writer 队列与幂等表定时清理 | 无 `SQLITE_BUSY` 风暴，过期幂等记录可清理 |

## B.3 P2 网络协议与安全连接

| 小计划 | 内容 | 验收 |
|--------|------|------|
| P2.1 | 帧编码/解码、长度限制、结构化二进制消息映射 | 半包/粘包测试通过 |
| P2.2 | TLS 1.3 自签名上下文和连接启动/关闭 | 两个本地 core 节点可加密握手 |
| P2.3 | 认证消息改为挑战-应答或至少不传明文；保留 bcrypt 存储 | 错误密码拒绝，正确密码建立会话 |
| P2.4 | 序列号窗口和时间戳防重放 | 重复帧和过期帧被拒绝 |
| P2.5 | 死亡包/超大帧防护 | 超过 `MAX_FRAME_SIZE` 立即断连且不分配大内存 |

## B.4 P3 服务端间业务协议

| 小计划 | 内容 | 验收 |
|--------|------|------|
| P3.1 | `QUERY_TIMESTAMP`、`QUERY_ITEMS`、分页返回和槽位版本 | 缓存可获得完整快照 |
| P3.2 | `PUT_ITEM` 接入 P1 事务，响应含 `request_id`、`current_item.version`、时间戳 | 远程放入与本服放入走同一逻辑 |
| P3.3 | `TAKE_ITEM` 接入 P1 事务，成功响应携带 `items_to_give` | 远程取出不会复制或吞物品 |
| P3.4 | `PUSH_UPDATE` 只做缓存失效通知 | 推送不直接改缓存库存 |
| P3.5 | 不兼容物品 `extraData` 黑盒透传 | 低版本权威服不会破坏高版本物品数据 |

## B.5 P4 缓存、同步与在线状态

| 小计划 | 内容 | 验收 |
|--------|------|------|
| P4.1 | `RemoteCacheStore` 保存快照、版本和同步时间 | 离线后可展示最后快照 |
| P4.2 | 打开共享空间时先比对时间戳，不一致才全量拉取 | 未变化时不传完整列表 |
| P4.3 | 心跳、离线判定、指数退避重连 | 断线 30 秒内标记离线，恢复后全量同步 |
| P4.4 | 缓存过期清理和手动刷新 | `/exchange refresh` 跳过时间戳比对 |

## B.6 P5 Fabric 首个可玩适配层

| 小计划 | 内容 | 验收 |
|--------|------|------|
| P5.1 | Fabric 初始化、配置、指令注册 | `/exchange list/view/reload` 基础可用 |
| P5.2 | Fabric `ItemSerializer` | 常见物品和带组件物品可往返 |
| P5.3 | 原版虚拟容器展示远程缓存和离线标题 | 无客户端 Mod 可打开 |
| P5.4 | PUT 真实扣除、`InFlightPutStore` 暂存、失败发还 | 关闭 GUI、丢弃键、快捷键交换都不能复制物品 |
| P5.5 | 不兼容物品占位展示并禁止取出 | 操作被拒绝且提示原因 |
| P5.6 | TAKE 成功回调失效补偿 | 玩家离线/死亡/关容器不会吞物品 |

## B.7 P6 审计与管理命令

| 小计划 | 内容 | 验收 |
|--------|------|------|
| P6.1 | 服务器 add/remove/list 持久化和热重载 | 配置重载不破坏现有连接状态 |
| P6.2 | 日志查询、导出、定期清理 | 指定天数导出内容正确 |
| P6.3 | 管理员清理异常槽位或不兼容物品的预留接口 | 首期可隐藏，接口留测试 |

## B.8 P7 集成与压力测试

| 小计划 | 内容 | 验收 |
|--------|------|------|
| P7.1 | 双服本地集成测试：查询、放入、取出、断线恢复 | 自动化脚本一键跑通 |
| P7.2 | 三服以上竞争测试：多个玩家同时取同一槽 | 无超发，冲突提示正确 |
| P7.3 | 请求重放和网络超时测试 | 同一请求 ID 幂等，未知状态不自动生成物品 |
| P7.4 | 长时间心跳、缓存清理、日志清理测试 | 无线程泄漏和数据库锁死 |
| P7.5 | Minecraft 交互绕过测试：关 GUI、Q 丢弃、数字键交换、Shift 快移 | PUT/TAKE 不复制、不吞物品 |

## B.9 P8 Forge / NeoForge 与增强客户端

| 小计划 | 内容 | 验收 |
|--------|------|------|
| P8.1 | Forge 适配层复用 core 契约 | 与 Fabric 节点互通 |
| P8.2 | NeoForge 适配层复用 core 契约 | 与 Fabric/Forge 节点互通 |
| P8.3 | 客户端增强 GUI：服务器侧栏、同步时间、兼容标记 | 不改变 core 事务语义 |
