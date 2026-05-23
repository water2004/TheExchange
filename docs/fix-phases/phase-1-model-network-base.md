# Phase 1：数据对象 + 网络基础

无依赖，先清地基。

## NeutralItem.java — O-1

`getExtraData()` 直接返回内部 `byte[]` 引用，调用者可修改数组内容，违反 byte-for-byte preserve。

**改**：`getExtraData()` 返回 `extraData != null ? Arrays.copyOf(extraData, extraData.length) : null`；`setExtraData(byte[])` 做防御性拷贝。

**性能确认**：已验证 `writeTo`、`sameStackKind`、`copy`、`equals`、`hashCode` 等热路径全部直接访问 `this.extraData` 字段，不经过 getter。防御性拷贝仅影响外部调用者，无性能问题。

## ExchangeInteraction.java — O-2

`getExchangeItems()` 返回内部可变 `ArrayList` 的引用。

**改**：返回 `Collections.unmodifiableList(exchangeItems)`。

## ExchangeViewState.java — M-12

`titleTemplate` 字段和 `getTitleTemplate()` 方法无人使用，`getTitle()` 硬编码格式。

**改**：删除 `titleTemplate` 字段和 `getTitleTemplate()`。

## ExchangeAPI.java — O-7

`getMinecraftVersion()` 默认返回硬编码 `"26.1.2"`，适配器未覆盖时静默出错。

**改**：默认实现抛 `UnsupportedOperationException`。

## HeartbeatManager.java — O-10

`running` 字段声明为 volatile 并在 `start()`/`stop()` 中设置，但 `sendHeartbeats()` 和 `checkTimeouts()` 任务体内不检查。`stop()` 调 `scheduler.shutdownNow()` 中断线程，但任务本身应自己做守卫。

**改**：`sendHeartbeats()` 和 `checkTimeouts()` 开头加 `if (!running) return`。

## Connection.java — O-11

`close()` 不清理 `pendingResponses`——只有 `handleDisconnect()` 清理。如果 readThread 已意外退出后调用 `close()`，pending futures 永远不完成。

**改**：`close()` 中遍历 `pendingResponses.values()` 逐个 `completeExceptionally(new IOException("Connection closed"))`。

## TcpClient.java — D-7

`createSocket(address, port)` 无超时参数，远端不响应时永久阻塞。

**改**：设置 10s 连接超时。

## FrameDecoder.java — C-6

静态 `readFully` 返回实际读取字节数，流提前关闭时 `total < buf.length`，但调用方不检查返回值，直接使用部分填充的 buffer。

**改**：调用处检查返回值，不足时抛 `IOException`。

## FrameType.java — E-6

`fromCode(short)` 对每个收到的帧遍历全部枚举值做线性搜索。

**改**：加 `static final Map<Short, FrameType> BY_CODE`，初始化为 O(1) 查找。

## PinnedPeerKeyStore.java — O-9

`Files.move(tmp, pinFile, ATOMIC_MOVE)` 在某些 Windows 文件系统或跨卷场景下抛 `AtomicMoveNotSupportedException`。

**改**：catch 异常后降级为 `REPLACE_EXISTING`。

## DatabaseManager.java — M-8, M-10

缺少 `busy_timeout` PRAGMA，多线程并发写入时可能 `SQLITE_BUSY`。
`CREATE INDEX idx_log_request` 与 `request_id UNIQUE` 约束重复——SQLite 为 UNIQUE 自动建索引。

**改**：初始化时 `PRAGMA busy_timeout=5000`；删除 `CREATE INDEX idx_log_request`。
