# Phase 2：NetworkManager 整修

> **状态：已完成。** 所有修改项已在当前代码中实现，无需额外操作。

以下为已验证的修复清单：

- ✅ S-1: `passwordMatches()` 已使用 `MessageDigest.isEqual` 常量时间比较
- ✅ S-5: `authFailures` ConcurrentHashMap + `MAX_AUTH_FAILURES=5` + `AUTH_BAN_MS=30s` 已实现
- ✅ S-8: 日志仅打印 "password mismatch"，不含密码长度信息
- ✅ O-13: `localServerName`、`localPassword`、`messageRouter`、`onlineHandler` 均已标记 `volatile`
- ✅ C-8: `connections.put` 和 `conn.setInbound/setPeerServerName` 已在 `setAuthenticated(true)` 之后执行
