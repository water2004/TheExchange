# Phase 2：NetworkManager 整修

一文件多项，联动改。

## 密码对比时序攻击 — S-1

`NetworkManager.java:134`：`localPassword.equals(request.getPassword())` — `String.equals` 第一个不匹配字符短路返回。

**改**：`MessageDigest.isEqual(localPassword.getBytes(StandardCharsets.UTF_8), request.getPassword().getBytes(StandardCharsets.UTF_8))`。

## 认证速率限制 — S-5

`handleInboundAuth` 无失败次数计数和退避，攻击者可无限暴力破解。

**改**：加 `ConcurrentHashMap<String, AuthFailure>`：
- `AuthFailure` 记录 `failCount` 和 `lastFailTime`
- 连续 5 次失败后封禁该 IP 30 秒
- 成功认证后清除计数
- 在 `handleInboundAuth` 开头检查封禁状态，封禁期间直接拒绝

## 日志泄露密码长度 — S-8

两处打印密码长度信息：`setLocalPassword`（第 54 行）和 `handleInboundAuth`（第 124 行）。

**改**：删除 `pwdLen` 和 `password.length()` 日志输出。

## 字段缺 volatile — O-13

`localServerName`、`localPassword`、`messageRouter`、`onlineHandler` 非 volatile，但被不同线程读写（主线程设置，网络线程读取）。

**改**：加 `volatile`。

## 认证前连接可达 — C-8

`handleInboundAuth` 和 `connectToRemote` 都在 `conn.setAuthenticated(true)` 之前执行 `connections.put`。认证失败时虽然 remove 了，但窗口期内其他线程可拿到未认证连接并发送数据。

**改**：将 `connections.put` 和 `conn.setInbound/setPeerServerName` 移到 `conn.setAuthenticated(true)` 之后。
