# Phase 1：数据对象 + 网络基础

> **状态：已完成。** 所有修改项已在当前代码中实现，无需额外操作。

以下为已验证的修复清单：

- ✅ O-1: `NeutralItem.getExtraData()` / `setExtraData()` 已做防御性拷贝 (`copyBytes`)
- ✅ O-2: `ExchangeInteraction.getExchangeItems()` 已返回 `Collections.unmodifiableList`
- ✅ M-12: `ExchangeViewState.titleTemplate` 字段已移除
- ✅ O-7: `ExchangeAPI.getMinecraftVersion()` 默认抛 `UnsupportedOperationException`
- ✅ O-10: `HeartbeatManager.sendHeartbeats()` / `checkTimeouts()` 开头已有 `if (!running) return`
- ✅ O-11: `Connection.close()` 已遍历 `pendingResponses` 并 `completeExceptionally`
- ✅ D-7: `TcpClient` 已设置 10s 连接超时 (`CONNECT_TIMEOUT_MS`)
- ✅ C-6: `FrameDecoder.readFrame()` 已检查 `readFully` 返回值，不足时抛 IOException
- ✅ E-6: `FrameType.fromCode()` 已使用 `Map<Short, FrameType> BY_CODE` O(1) 查找
- ✅ O-9: `PinnedPeerKeyStore.store()` 已 catch `AtomicMoveNotSupportedException` 降级
- ✅ M-8: `DatabaseManager.initialize()` 已执行 `PRAGMA busy_timeout=5000`
- ✅ M-10: 冗余索引 `idx_log_request` 已移除
