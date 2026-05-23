# 修复计划

7 个 Phase，按依赖关系排序。不修项见末尾。

| Phase | 范围 | 文件数 |
|-------|------|--------|
| 1 | 数据对象 + 网络基础 | 10 |
| 2 | NetworkManager 整修 | 1 |
| 3 | storage 层 | 3 |
| 4 | 并发正确性 | 4 |
| 5 | service + config | 3 |
| 6 | fabric 适配层 | 4 |
| 7 | DoS 批量校验 | 2 |

### 不修

| # | 问题 | 原因 |
|---|------|------|
| S-2 | TLS trust-all | 自建 MC 服务器无可信 CA 链，TOFU 已够 |
| S-3 | keytool 密码可见 | Windows 无此问题 |
| S-6 | keystore 密码硬编码 | 自签证书 keystore 密码安全价值低，真正保护来自 TLS+TOFU |
| C-7 | SequenceWindow 极端跳跃 | `index` 永远=512 |
| D-3 | 10MB 帧 | 合理 |
| D-4 | sendAsync 线程 | 请求频率低 |
| E-4/O-12 | rows 可写 | 槽位固定 54，不打算变动 |
| E-5 | 遍历所有玩家 | 规模可接受 |
| M-1 | 代码重复 | 差异仅两处 |
| M-7 | 单连接 | 读多写少 |
| O-4 | replaceAll 非原子 | reload 期间无并发 |
