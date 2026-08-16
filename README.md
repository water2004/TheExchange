# TheExchange — Minecraft 跨服物品交换

基于 TLS 1.3 加密信道和乐观锁并发控制的跨服共享空间模组，各服务器之间可以互相查看、放入、取出物品。

## 配置

配置文件位于 `config/theexchange/theexchange.json`，首次启动自动生成。支持热重载。

```jsonc
{
  "server": {
    "display_name": "Default Server",     // 本服名称，显示在容器标题
    "port": 25566,                        // 监听端口 1–65535
    "password": "changeme"                // 连接密码（TLS 内传输，TOFU 公钥固定防中间人）
  },
  "network": {
    "heartbeat_interval_seconds": 10,     // 心跳间隔
    "heartbeat_timeout_seconds": 30,      // 超时判定离线
    "reconnect_initial_delay_seconds": 5, // 重连初始延迟
    "reconnect_max_delay_seconds": 30,    // 重连最大延迟（指数退避上限）
    "request_timeout_seconds": 5,         // 变更事务超时探测间隔
    "inbound_enabled": false              // 是否接受入站连接
  },
  "cache": {
    "offline_retention_hours": 24,        // 离线缓存保留时长
    "local_inventory_cache_capacity": 32, // 本地库存 LRU 容量
    "remote_inventory_cache_capacity": 64 // 远端缓存 LRU 容量
  },
  "performance": {
    "core_threads": 4                     // 工作线程池大小（上限 = CPU 核心数）
  },
  "logging": {
    "retention_days": 30,                 // 操作日志保留天数
    "cleanup_interval_hours": 1           // 日志清理间隔
  },
  "container": {
    "rows": 6,                            // 容器行数（固定 6，即 9×6=54 槽）
    "title_template": "{server_name} 的共享空间"
  },
  "player_inventory": {
    "enabled": true                       // 是否允许访问玩家私有仓库
  },
  "remoteServers": [
    {
      "name": "创造服",
      "address": "10.0.0.2",
      "port": 25566,
      "password": "changeme"
    }
  ]
}
```

### 热重载

修改配置文件后执行 `/exchange config reload` 生效。以下配置项可用 `/exchange config set <path> <value>` 实时修改后 reload，无需手动编辑文件：

| path | 类型 | 说明 |
|------|------|------|
| `server.display_name` | string | 本服名称 |
| `server.port` | int (1–65535) | 监听端口 |
| `server.password` | string | 连接密码 |
| `network.heartbeat_interval_seconds` | int (>0) | 心跳间隔 |
| `network.heartbeat_timeout_seconds` | int (>0) | 心跳超时 |
| `network.reconnect_initial_delay_seconds` | int (>0) | 重连初始延迟 |
| `network.reconnect_max_delay_seconds` | int (>0) | 重连最大延迟 |
| `network.request_timeout_seconds` | int (>0) | 变更事务超时探测间隔（超时进入恢复，不判定失败） |
| `network.inbound_enabled` | bool | 接收入站连接 |
| `cache.*` | int (>0) | 缓存各项容量/时长 |
| `performance.core_threads` | int (>0) | 线程池大小 |
| `logging.*` | int (>0) | 日志保留/清理间隔 |
| `container.title_template` | string | 标题模板 |
| `player_inventory.enabled` | bool | 玩家仓库总开关；关闭后拒绝认证、查看、修改和漏斗自动化 |

远端服务器用 `remote add/remove` 子命令管理。

关闭玩家仓库并立即生效：

```
/exchange config set player_inventory.enabled false
/exchange config reload
```

旧配置没有 `player_inventory` 段时按 `enabled: true` 处理。关闭后共享空间不受影响；已有玩家仓库菜单会失效，漏斗不会预扣物品或发送仓库请求。重新开启需要重新认证，因为热重载会清空内存会话。

运行日志通过 SLF4J 交给 Minecraft/Fabric 的日志后端，日志级别、归档、压缩和历史文件清理由服务端日志配置统一负责。`logging.*` 配置只用于物品操作审计日志，不控制 Minecraft 运行日志。

## 命令

所有命令以 `/exchange` 为根。

### 查看共享空间

```
/exchange view <server>    打开指定服务器的共享空间 GUI
/exchange view local       打开本服共享空间
/exchange refresh <server> 手动刷新指定服务器的共享空间
```

- 在线时：实时操作，查看、放入、取出
- 离线时：只读查看缓存数据，标题显示 `[离线]`
- 不兼容物品（跨版本无法解析）：显示为屏障方块，禁止操作

### 玩家私有仓库

```
/exchange player password <password>       创建或修改自己的玩家仓库密码
/exchange player view <player>@<server>    使用内存中的有效会话打开仓库
/exchange player view <player>@<server>:<password>
/exchange player login <password>          为上一次待验证连接补输密码
```

- 目标玩家可以离线。名字到 UUID 的解析完全使用 Minecraft 的 `nameToIdCache`：在线认证模式会查询 Mojang 档案服务，离线模式遵循 Minecraft 自己的离线 UUID 规则。
- 只有玩家主动执行 `player password` 才会创建仓库档案；远程查询不会自动建档。数据库档案只保存 UUID、密码哈希和时间戳，不保存角色名。
- 密码只用于换取 5 分钟滑动过期令牌。令牌、订阅和自动化委托均只在内存中，重启或成功热重载后立即失效。
- 同一访问者连续 5 次密码错误会锁定 10 分钟；锁定按来源服务器和访问玩家隔离。

### 告示牌末影箱与漏斗

将含有 `<player>@<server>:<password>`（密码可省略）的一行告示牌实际附着在末影箱上，该末影箱会映射到目标玩家仓库。支持墙上告示牌、立式告示牌和悬挂告示牌：

- 推荐使用不含密码的告示牌并先由玩家手动验证；告示牌文本本身不是秘密存储，写入密码会让能读取该告示牌或世界数据的人看到密码。
- 玩家打开末影箱时使用同一套 54 槽远程仓库 GUI 和并发控制。
- 告示牌省略密码且没有有效会话时，只需执行提示的 `/exchange player login <password>`，不必重输连接串。
- 漏斗推入/抽取走非阻塞异步远程操作；同一漏斗的上一次请求完成前，后续推入和抽取都会立即返回失败，不排队也不等待。源物品先预留，失败时归还。崩溃发生在远程确认窗口内时可能丢失该件物品，这是当前明确接受的取舍。
- 不同漏斗彼此独立并发，不做仓库级串行化；每个漏斗使用独立循环槽位起点分散冲突。单个漏斗同时具备远程输入和输出时，在两个方向都可工作时轮流尝试。
- 漏斗抽取会跳过本服无法反序列化的远程物品；所有玩家操作也会拒绝不兼容物品。
- 无密码告示牌的自动化必须先由玩家打开该末影箱并完成一次验证；委托只绑定这一只末影箱，不会被其他漏斗借用。

### 服务器列表

```
/exchange server list      列出所有已配置的远端服务器及在线状态
/exchange list             同上
```

### 配置管理（需 OP 权限）

```
/exchange config show                   展示完整配置
/exchange config get <path>             读取单个配置项
/exchange config set <path> <value>     设置配置项（需 reload 后生效）
/exchange config reload                 热重载配置
/exchange config remote list            列出远端服务器
/exchange config remote add <name> <address> <port> <password>  添加远端
/exchange config remote remove <name>   移除远端
/exchange reload                        热重载（同上）
```

### 操作日志

```
/exchange log export [days]  导出最近 N 天操作日志（默认 30）
/exchange log clear [days]   清理 N 天前的日志（默认 30，需 OP）
```

## 安全

- **TLS 1.3** 加密信道，AES-256-GCM / AES-128-GCM
- **TOFU 公钥固定**：首次连接自动保存对端公钥到 `config/theexchange/tls/known-peers.properties`，后续连接校验公钥一致，防止中间人攻击
- **密码认证**：TLS 握手后在应用层二次鉴权
- **玩家仓库令牌**：256 位随机 bearer token；服务端仅保存 SHA-256 摘要，令牌绑定来源服务器与访问者，滑动 TTL 5 分钟，绝不持久化
- **玩家仓库密码**：PBKDF2-HMAC-SHA256（120,000 次迭代）+ 独立随机盐；仓库档案以 UUID 为唯一键
- **服务器连接密码**：保存在服务器私有配置中并仅在 TLS 通道内使用；当前不做额外哈希封装
- **防重放**：滑动窗口（1024 位）+ 时间戳（±60s）+ 单调序列号
- **乐观锁**：每个槽位独立版本号，并发冲突时拒绝操作并刷新 GUI

## 架构

```
core/                         纯 Java 核心库，零 Minecraft/loader 依赖
  model/                      中立物品、scope、连接串与交互模型
  network/                    TLS/TOFU、应用协议 v2、消息关联与防重放
  storage/                    SQLite 权威库存、UUID 仓库档案、LRU 缓存
  service/                    鉴权会话、同步、乐观锁、更新受众、自动化槽位规划
  compat/                     跨版本物品序列化接口

fabric-1.21.11/               Minecraft 1.21.11 适配层（Java 21）
fabric-26.1/                  Minecraft 26.1.2 适配层（Java 25）
fabric-26.2/                  Minecraft 26.2 适配层（Java 25）
  fabric/command/             指令注册
  fabric/container/           9×6 服务端虚拟容器
  fabric/item/                ItemStack ↔ NeutralItem
  fabric/player/              密码提示与仓库打开协调器
  fabric/block/               附着告示牌解析
  fabric/automation/          末影箱自动化会话与异步漏斗桥
  fabric/mixin/               末影箱、漏斗的最小注入点
```

## 并发基准测试

测试机器：Intel Ultra 9 285H (6P+8E)，JDK 21.0.8，Windows 11

### 测试链路

基准测试在 `127.0.0.1` 上启动两个真实 Exchange 节点并建立一条持久 TLS 1.3/TCP 连接。测试数据由 core 测试夹具生成，不依赖 Fabric 或 Minecraft；网络、协议和事务协调器使用生产实现。

```
并发 caller
    → Connection 写线程
    → TLS/TCP + 帧编解码
    → 远端 MutationTransactionCoordinator
    → 权威库存工作线程池 + LocalInventoryCache
    → RESULT → SETTLED → CLOSED
```

### 测试方法

异步有界窗口持续补充请求，任何 caller 都不会同步等待上一笔完成。默认使用 8 个权威库存工作线程，每个测量点执行 100K 笔事务，并把在途事务数从 1 扫到 4096。每组先执行 1000 笔独立仓库事务预热。健康路径不触发 SQLite recovery；认证、连接读写、协议编解码、事务幂等与结算流程均使用生产实现。

故障测试需要保留完整帧轨迹和逐事务计数，饱和压测则显式关闭这些诊断数据，避免测试夹具进入热路径。每个测量点记录完整握手吞吐、提交成功率以及收到 RESULT 的 P50/P95/P99/最大延迟。

| 场景 | 说明 |
|------|------|
| 独立仓库 | 每条并发 lane 使用独立玩家私有仓库，SWAP 后物品总量不变，预期 100% 成功 |
| 随机竞争 | 所有请求在同一个 54 槽服务端仓库随机 SWAP |
| 完全竞争 | 所有请求在同一个仓库的同一槽执行 SWAP，制造最坏乐观锁竞争 |

每组结束时会精确断言：每笔提交的事务都到达权威端、成功响应数等于库存提交数、双方事务状态最终全部关闭。失败率表示乐观锁冲突或库存条件不满足，不表示网络丢包。

### 结果

下表是一次开启 JFR 的默认规模实跑。吞吐按所有事务完成 `RESULT → SETTLED → CLOSED` 计算；P99 是独立玩家仓库收到 RESULT 的尾延迟。

![](bench_report.png)

| 在途事务 | 独立仓库 tx/s | 独立仓库 P99 | 随机竞争 tx/s | 完全竞争 tx/s |
|------:|------:|------:|------:|------:|
| 1 | 13,740 | 0.23 ms | 15,179 | 15,555 |
| 8 | 58,582 | 0.36 ms | 59,312 | 61,013 |
| 32 | 66,489 | 0.87 ms | 66,138 | 68,399 |
| 128 | 65,402 | 4.01 ms | 65,703 | 70,423 |
| 512 | 62,461 | 12.31 ms | 68,353 | 71,327 |
| 2048 | 61,843 | 52.90 ms | 71,685 | 71,327 |
| 4096 | 60,716 | 82.96 ms | 68,166 | 70,621 |

### 结论

- 独立玩家仓库在约 32–128 笔在途时饱和于 65K–67K tx/s；继续加压不增加吞吐，只增加排队和尾延迟
- TLS socket 启用 `TCP_NODELAY` 后，8 路独立仓库从约 1.1K 提升至约 59K tx/s，P99 从约 30 ms 降至 0.36 ms，消除了 Nagle/延迟 ACK 对小事务帧的低并发断崖
- 随机/同槽吞吐包含被乐观锁快速拒绝的请求，因此不能当作成功写入容量；成功事务容量应以独立仓库场景为准
- JFR 显示当前饱和热区转移到二进制字符串编码、帧缓冲分配以及每事务恢复定时任务；没有 Java monitor contention
- 网络帧细节使用 SLF4J `trace`，默认不会让控制台 I/O 污染吞吐结果；运行时日志由 Minecraft 的日志后端负责归档和清理

### 本地运行

```bash
# 仅正确性测试（默认 test 任务不含基准测试）
./gradlew test

# 基准测试 + 生成图表（需要 Python 3 + matplotlib）
bash bench.sh                      # Git Bash / WSL
.\bench.ps1                        # PowerShell
.\bench.ps1 -Python D:\python.exe  # 指定 Python 路径
.\bench.ps1 -Operations 10000 -InFlight "1,32,128"  # 快速试跑
.\bench.ps1 -Profile                # 同时生成 JFR

# Git Bash / WSL 下的 JFR
bash bench.sh python 100000 8 "1,8,32,128,512,2048,4096" --profile
```

生成 `bench_data.csv`（原始数据）和 `bench_report.png`（吞吐/成功率折线图）。

### 真实网络并发正确性

`MutationTransactionNetworkStressTest` 会在 `127.0.0.1` 上启动两个真实 TLS/TCP 节点，由 16 个客户端线程并发经过认证、帧编解码、连接读写线程和 V2 事务协调器。默认测试包含 1080 笔跨 54 槽事务、256 笔同槽乐观锁竞争和 256 个同 UUID 重复帧；断言最终库存、成功/返还数量、执行次数、提交次数和双方关闭状态，不以不稳定的机器吞吐阈值判定正确性。

```bash
./gradlew :core:test --tests org.edtp.theexchange.service.MutationTransactionNetworkStressTest
```
