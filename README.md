# BotManager

**Leaves 假人管理插件**：让 `/bot` 假人像普通陌生玩家一样遵守 Residence 领地权限，并让玩家用 `/bm spawn` 召唤属于自己的假人。

- **目标服务端**：Leaves 1.21.8 ~ 1.21.11（同一份 jar 通用，启动时自动适配）
- **依赖**：[Residence](https://www.spigotmc.org/resources/residence-1-7-10-up-to-1-21.11480/)（软依赖，没装只是不做领地保护）；Vault + 经济插件（可选，只在菜单里出售号位时需要）
- **编译产物**：`target/BotManager-1.0.0.jar`

> 本文是**使用教程**。实现原理、版本兼容性分析与实测记录为内部文档，未随仓库发布。

## 两部分能力

1. **领地保护** —— 假人做任何事（挖方块、开箱子、放方块、攻击……）都和普通陌生玩家一样受 Residence 限制。这是前置工作：没有它，把假人开放给玩家是不安全的。
2. **假人管理系统** —— 玩家用 `/bm spawn` 召唤自己的假人（`test.1`、`test.2`……编号自动补空号），每人上限独立且可由管理员调整，假人随玩家上下线自动存档还原。

## 安装

1. 把 `BotManager-1.0.0.jar` 放进服务端 `plugins/` 目录
2. 重启服务端（或 `/reload confirm`）
3. 启动时控制台会打印自检结果：

```
[BotManager] Residence 事件覆盖自检 -> 破坏=OK 右键交互=OK 放置=OK 桶=OK 实体交互=OK 战斗=OK 通用伤害=OK 载具=OK 移动=OK
```

任何一项显示 `无` 都说明该类保护在假人身上不生效，需要贴日志排查。

**建议把 Leaves 的假人前后缀留空**（`modify.fakeplayer.prefix` 和 `suffix` 都设为 `''`）：插件自己会在名字里放一个 `.`（`test.1`），前缀留空不占 16 字符的额度。

> ⚠️ **要在开放权限之前定好**——假人 UUID 由全名推导，改前后缀会让已有假人的存档和 Residence 权限全部对不上。

## 快速上手（服主）

`config.yml` 的 `bot.default-limit` 默认为 `0`，即**默认谁都召唤不了**。开放步骤：

```
/bm limit set <玩家> 5     # 给某人 5 个假人的额度（编号 1-5）
/bm status                 # 随时查看运行状态和在线假人
```

玩家那边敲 `/bm menu`（图形菜单）或 `/bm spawn`（命令）即可开始使用。

若想在菜单里出售号位，装 Vault + 一个经济插件，并在 `config.yml` 配好价格（见下文「经济」）。

## 玩家指南

### 召唤与管理

| 命令 | 权限 | 说明 |
|---|---|---|
| `/bm help` | 无 | 按你实际拥有的权限列出所有可用指令 |
| `/bm spawn [编号]` | `botmanager.spawn`（默认开） | 召唤。不写编号自动补最小空号；写编号可自己挑（不能超过上限） |
| `/bm list` | 同上 | 列出自己的假人、在线状态、下一个空号 |
| `/bm recall [编号]` | 同上 | **收回**：背包 / 坐标 / 血量存档，编号保留，下次召唤原样回来 |
| `/bm remove <编号>` | 同上 | **遣散**：真删，物品掉在原地，编号回收 |
| `/bm action <编号> ...` | 同上 | 控制假人动作（语法见下） |
| `/bm menu` | 同上 | 打开图形菜单 |

`/bm remove` 和 `/bm action` **必须写编号**。

**收回 ≠ 遣散** —— 菜单上的「收回假人」和「移除假人」也是两回事：

| | `/bm recall` 收回 | `/bm remove` 遣散 |
|---|---|---|
| 背包 / 坐标 | **存档**，下次召唤原样回来 | **掉在原地**，谁捡到算谁的 |
| 还能召回来吗 | 能，原样还原 | **不能**，召回来是全新空假人 |
| 编号 | 留着，还是你的 | 回收（下次 `spawn` 会补上） |
| 号位上限 | 不变 | **不变**（花的钱和买到的名额都还在） |

`/bm recall` 的编号可以不写，但有条件：名下**恰好 1 个**假人在线 → 直接收回；**2 个及以上** → 插件拒绝替你猜，会让你点名 `/bm recall <编号>`；**都不在线** → 提示"没有在线的假人"（它们的背包早就存着了）。

### 图形菜单 `/bm menu`

主菜单里一格一个号位，例如：

```
上限 2，召唤了 1 个：[实例1] [可用2] [购买]
```

- 点「**可用**」→ 小面板，只有「生成假人」
- 点「**实例**」→ 完整操作面板
- 点「**购买**」→ 上限 +1，新号位随即出现（结算走 Vault）

完整操作面板（6 行）：

```
 ② 去留         11 召唤假人  13 收回假人  15 移除假人
 ③ 点按         20 点按左键  22 点按右键  24 潜行
 ④ 间歇         29 间歇左键  31 间歇右键  33 跳跃
 ⑤ 长按         38 长按左键  40 长按右键  42 停止所有操作
 ⑥ 挖方块/返回  47 挖方块    49 返回      53 命令用法
```

- **一列管一个鼠标键**：左键列（20 / 29 / 38 / 47）、右键列（22 / 31 / 40），横着看就是同一档位的左键版和右键版。
- **点按** = 做一次就收手；**长按** = 一直做（间隔 1 tick）；**间歇** = 按你输入的间隔做。
- 左键列按游戏手感对齐：点按 = 打生物（`attack`），长按 / 挖方块 = 挖方块（`break`）。
- 右键列全是 `use_auto`（真人完整右键：放方块、开箱子、按按钮、吃东西都行）。
- 53 号格是只读的「命令用法」说明书——**只有文字，点了没反应**。
- 「移除假人」有二次确认窗口；**遣散的是假人，不是号位**，点了不会少一个号位。

### 动作控制 `/bm action`

```
/bm action <编号> start <操作> <首次延迟> <之后每次延迟> <次数>
/bm action <编号> stop <动作编号>     （或  /bm action <编号> stop all）
/bm action <编号> list                （查看正在跑的动作编号）
```

- 时间单位是 **tick**（20 tick = 1 秒）；次数填 `-1` 就是一直做下去。
- 操作名：`attack` / `break` / `use_auto` / `sneak` / `jump`（与 Leaves 原生 `/bot action` 一致，只是把假人名换成编号）。
- 参数顺序是「首次延迟 → 之后每次的延迟 → 次数」。
- **只能操作自己的假人**：假人名由执行者名字推导（`<你的名字>.<编号>`），输入别人的编号根本寻址不到。
- ⚠️ `stop` 掉一个动作后，**它后面动作的编号会往前挪**，连着停多个时要重新 `list` 看编号。

## 管理员命令（`botmanager.admin`，默认仅 OP）

| 命令 | 说明 |
|---|---|
| `/bm limit <玩家>` | 查询该玩家上限 |
| `/bm limit set/add/reduce <玩家> <数量>` | 设定 / 增加 / 减少上限 |
| `/bm status` | 运行状态、在线假人及 UUID、领地档案自检 |
| `/bm reload` | 重载配置并刷新监听器缓存 |
| `/bm debug <假人名>` | 诊断该假人朝向的方块能否被破坏 |

## 配置要点

全部配置项都带中文注释，见 `plugins/BotManager/config.yml`。要点速查：

| 配置 | 默认 | 说明 |
|---|---|---|
| `guard.break` 等 9 项 | 大多 `true` | 各类动作的领地保护开关（break / interact / place / use-item / attack / vehicle / item-drop / fish；`move` 默认 `false`） |
| `bot.default-limit` | `0` | 默认额度，同时也是可用编号范围（上限 5 ⇒ 只能用编号 1-5） |
| `bot.sync-on-join` / `sync-on-quit` | `true` | 上下线自动拉起 / 存档移除假人 |
| `bot.save-on-shutdown` | `true` | 关服前替假人存档（防 `/stop` 时假人连经验一起掉一地） |
| `bot.autosave-interval-minutes` | `5` | 周期存档，防强杀（`kill -9`）回退；写盘时假人不下线、不掉东西 |
| `bot.restrict-inventory-to-owner` | `true` | 只能开自己假人的背包（`botmanager.inventory.others` 权限者不受限） |
| `menu.max-slots` | `5` | 菜单里最多能买到第几个号位 |
| `menu.price-*` | — | 价格，见下 |

改完执行 `/bm reload` 生效，无需重启。

### 经济（可选）

三项价格配置只要有一档大于 `0`，就需要 Vault + 一个经济插件（用反射接入；没装时插件照常启动，只是点号位会提示"没有接入经济插件"）。

两种配法：

```yaml
# 写法 A：涨法有规律
price-per-slot: 50000
price-growth: 2.0          # 每格翻倍

# 写法 B：直接列表
price-list: [50000, 100000, 200000, 300000, 500000]
price-growth: 1.0          # 表用完之后不再涨
```

**价格跟着号位走，不跟着"买过几次"走**——用 `/bm limit add` 白送出去的额度也会占掉前几档。

## PlaceholderAPI 占位符（可选）

装了 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 后自动注册（软依赖，没装就跳过），可用于 TAB、计分板、聊天格式等：

| 占位符 | 含义 |
|---|---|
| `%botmanager_limit%` | 该玩家的假人上限（买下的号位总数） |
| `%botmanager_online%` | 当前在线的假人数量 |
| `%botmanager_used%` | 已占用的号位数量（**含「收回」但未「移除」的假人**——它们已下线但号位保留） |
| `%botmanager_price%` | 买**下一个**号位需要的价格（纯数字；已到 `menu.max-slots` 时返回空字符串） |

`%botmanager_owned%` 与 `used` 等价，`%botmanager_next_price%` 与 `price` 等价。调试可用 `/papi parse <玩家> %botmanager_price%`。

## 领地授权（Residence）

玩家想在领地里让假人干活，需要给**假人**授权（和给普通玩家授权完全一样）：

```
/res pset <领地> <假人名> build true
/res pset <领地> <假人名> destroy true
```

> ⚠️ **`destroy` 会覆盖 `build`**：领地显式设过 `destroy=false` 时，光给 `build=true` 没用，两个都给最稳。

反过来说，不授权的领地对假人天然严格。**假人的领地档案由插件自动补建**——Residence 只在真人登录时建档（`PlayerJoinEvent`），假人不触发那个事件，不补的话 `set` 过的权限对假人全部无效。是否生效看 `/bm status` 的「假人领地档案」一行。

## 常见问题

**假人死了会掉东西吗？** 会——**即使服务器开着死亡不掉落也照掉**。这是刻意保留的：假人是随叫随到的"劳力/容器"，不是需要保护进度的真人。想防偷家请用 Residence 的 pvp / damage 等 flag。

**关服时假人会掉一地吗？** 不会（`save-on-shutdown: true` 默认开）。被 `kill -9` / 断电强杀时最多回退 `autosave-interval-minutes` 分钟，并且**不会掉东西**。

**为什么假人名里有个 `.`？** Minecraft 玩家名只允许 `[a-zA-Z0-9_]`，真人**注册不到**带点的名字，假人与真人的命名空间彻底隔离、没人能抢注。⚠️ 这条**只在正版验证服（`online-mode: true`）成立**，离线服上该隔离不成立，此时请把 `bot.name-conflict` 保持默认的 `protect`（见 `config.yml` 注释）。

**别人能翻我假人的背包吗？** 默认不能（`restrict-inventory-to-owner: true`）。

**`/bm spawn` 提示名字超长？** Leaves 的全名硬上限是 16 字符；前后缀留空时"玩家名 + 编号位数 ≤ 15"，超出会被提前拒绝。

**假人卡在领地边界进不去？** 检查 `guard.move`——建议保持 `false`（默认值），移动类拦截只在确实需要时才开。

**普通玩家需要 `bukkit.command.bot` 权限吗？** 不需要。`/bm action` 转交到 Leaves 的 `/bot action` 时插件会临时挂权限、执行完立刻摘掉，玩家无需任何额外权限。

**装了 AuthMe 之后打不动假人？** 插件会自动处理——把上线的假人写进 AuthMe 的 `UnrestrictedName` 白名单（假人不会 `/login`，默认会被 AuthMe 当「未登录玩家」保护起来）。这就是独立插件 BotUnrestrict 的功能，已并入本插件（前提：`plugins/AuthMe/config.yml` 存在）。

## 从源码构建

Maven 项目：

```bash
mvn package
```

产物：`target/BotManager-1.0.0.jar`。

## 更多

实现原理（如何为假人重建 Bukkit 事件并投递给 Residence）、Leaves / Residence 版本兼容性分析与逐条真机实测验收记录，为作者内部文档，未随仓库发布。
