# BotManager

**Leaves 假人管理插件。**

两部分能力：

1. **领地保护**——让 `/bot` 假人像普通陌生玩家一样遵守 Residence 领地权限。
   这是前置工作：没有它，把假人开放给玩家是不安全的。
2. **假人管理系统**——玩家用 `/bm spawn` 召唤属于自己的假人
   （`test.1`、`test.2`……编号在 1 到上限之间自动补空号，也能自己指定），
   每人上限独立且可由管理员调整，假人随玩家上下线同步。

- **目标服务端**：Leaves 1.21.8 ~ 1.21.11（Paper 分支）
- **依赖**：Residence（软依赖，没装只是不做领地保护）
- **编译产物**：`target/BotManager-1.0.0.jar`

### Leaves 版本兼容性

Leaves 的假人 API 在 1.21.8 和 1.21.11 之间有 **三个方法改过签名/名字**，
插件启动时自动探测并适配，**两种版本用的是同一份 jar，不需要按版本编译**：

| 能力 | 1.21.8 及更早 | 1.21.11 起 | 适配方式 |
|---|---|---|---|
| 取假人裸名 | `Bot.getRealName()` | `Bot.getRawName()` | 两个名字都试 |
| 移除假人 | `remove(boolean save)` | `remove(boolean save, boolean resume)` | 按存在哪个调哪个 |
| 存档还原入口 | `BotList.loadNewBot(String)` | `BotList.loadNewManualSavedBot(String)` | 先试新的，退回旧的 |

前两个是**致命**的：接不上插件会直接停用（早期版本就是把整个启动链
串成一条 `getMethod` 链，第一个对不上就全崩）。所以 0.1 之后改成
**逐个解析、把缺的一次性列全再退出**，换版本时日志会一次给全清单，
不用反复重启试错。

单参 `remove(save)` 的内部实现写死 `resume=false`，与本插件两处调用
（`/bm remove` 和上下线同步）传的值一致，所以**退回到单参不丢任何语义**。

兼容层已在 **Leaves 1.21.8-138-9331167 真机实测**通过（见第 10 节）。

### Residence 版本兼容性

已按 **6.0.1.4** 核对（比对的源码区间为 tag `6.0.0.1` ~ `6.0.3.2`，6.0.1.4 落在其中）：

| 依赖点 | 结论 |
|---|---|
| 11 个被伪造的事件 | 6.0.0.1 起全部已监听，无缺项 |
| `containers.Flags` 枚举 | 路径一致；5.x 在 `protection` 下，已加回退探测 |
| `itemdrop` / `hook` flag | 两版均存在 |
| `FlagPermissions.getPerms(Location, Player)` | static，签名一致 |
| `FlagPermissions.playerHas(Player, Flags, boolean)` | 签名一致 |

启动时会把实际连接的 Residence 版本打进日志，`/bm status` 也能看到；
若某项保护不可用，启动日志的「覆盖自检」会显示 `=无`。

---

## 1. 这个 bug 的根因

假人能拆别人的房子，**问题不在 Residence，而在 Leaves**。

Paper 会在 `ServerPlayerGameMode` 里注入 Bukkit 事件（`BlockBreakEvent`、`PlayerInteractEvent`、
`BlockPlaceEvent`……），Residence 正是靠监听这些事件来保护的。

但 Leaves 给假人写了一个自己的 `ServerBotGameMode`
（`leaves-server/src/main/java/org/leavesmc/leaves/bot/ServerBotGameMode.java`），
它覆写了三个方法并直接改写世界，**把 Paper 注入的事件全部跳过了**：

| 被覆写的方法 | 后果 |
|---|---|
| `destroyBlock(BlockPos)` | 直接 `level.removeBlock()`，不触发 `BlockBreakEvent` |
| `destroyAndAck(BlockPos, int, String)` | 同上 |
| `useItemOn(...)` | 直接走原版逻辑，不触发 `PlayerInteractEvent` / `BlockPlaceEvent` |

所以 Residence 的监听器**根本收不到假人的任何方块操作**——不是权限判定出错，是事件压根没派发。
其他动作（攻击、实体交互、拾取物品等）走的是正常 NMS 路径，本来就能被拦，
所以只有**破坏**和**右键方块**这两类出现了漏洞。

---

## 2. 修复原理

Leaves 的所有 bot 动作在执行前都会经过
`AbstractBotAction.tryTick()` 派发的 **`BotActionExecuteEvent`**（可取消）。
这是唯一一个假人动作必然经过、且插件能插手的入口。

本插件在该事件里，把假人的动作**还原成「如果这是个真实玩家，服务端本该触发的 Bukkit 事件」**，
然后交给 `ResidenceHook` —— 它**只把事件投递给 Residence 自己的监听器**，不惊动其它任何插件：

```
/bot action <假人> break
        │
        ▼
AbstractBotAction.tryTick()  ──►  BotActionExecuteEvent（可取消）
                                        │
                                        ▼
                             本插件还原出 BlockBreakEvent
                                        │
                                        ▼
                       只投递给 Residence 注册在该事件上的监听器
                                        │
                        ┌───────────────┴───────────────┐
                     被取消                           未取消
                        │                               │
                 setCancelled(true)                  放行，动作照常执行
                （SOFT_CANCEL，本次动作跳过）
```

**关键点**：判定逻辑 100% 复用 Residence 自身的实现，所以它的**全部 135 个 flag**
（以及物品黑名单、租赁、raid 等非 flag 逻辑）都会自动对假人生效，
不需要在本插件里逐条复刻——也就不会出现"Residence 加了新 flag 但插件没跟上"的问题。

### 为什么只投递给 Residence，而不是 `callEvent()` 广播

`callEvent()` 会让所有插件收到这个伪造事件。像连锁挖矿、任务进度、
挖掘统计这类插件会在 `BlockBreakEvent` 里做真实的副作用，广播会导致数据被污染。
只投递给 Residence 既精准又无副作用。

---

## 3. 安装

1. 把 `BotManager-1.0.0.jar` 放进服务端 `plugins/` 目录
2. 重启服务端（或 `/reload confirm`）
3. 启动时控制台会打印一行自检结果：

```
[BotManager] Residence 事件覆盖自检 -> 破坏=OK 右键交互=OK 放置=OK 桶=OK 实体交互=OK 战斗=OK 通用伤害=OK 载具=OK 移动=OK
```

任何一项显示 `无` 都说明该类保护在假人身上不生效，需要贴日志排查。

### 不需要改 Leaves 的假人前缀

建议把 Leaves 的 `modify.fakeplayer.prefix` 和 `suffix` 都**留空**：

```yaml
modify:
  fakeplayer:
    prefix: ''
    suffix: ''
```

假人名不用前缀来区分，因为插件自己会在名字里放一个 `.`（`test.1`）——
`.` 不在 Minecraft 玩家名的合法字符集 `[a-zA-Z0-9_]` 里，
**正版验证服（`online-mode: true`）上真人拿不到这种名字的会话**，
名字抢注问题被结构性消除（细节见 8.1 和 8.5）。

> ⚠️ **离线服（`online-mode: false`）不成立。** 实测改过的客户端能用带点的名字登进来，
> 详见 §8.5「离线服实测：隔离不成立」。

前缀留空还有一个好处：它不占那 16 字符的额度。如果确实想加前缀，
**必须用不含 `.` 的形式**（比如 `b_`），否则插件的编号分隔符会和前缀混在一起。

> ⚠️ **要在开服发权限之前定好。** 假人 UUID 由全名推导，改前后缀会让已有假人的
> 存档和 Residence 权限全部对不上。

---

## 4. 给假人授权

假人的 UUID 是**由名字推导出来的固定值**：

```java
UUID.nameUUIDFromBytes(("Fakeplayer:" + fullName).getBytes(UTF_8))
```

`fullName` = 名字前缀 + 假人名 + 名字后缀（前后缀来自 Leaves 配置）。
也就是说：**只要名字不变，UUID 永远不变**；Residence 的权限是按 UUID 存在领地文件里的，
所以**假人下线再上线，之前授予的权限不会丢**。

```bash
# 查看在线假人的名字和 UUID
/bm status

# 给假人授予某块领地的建造权（和给普通玩家授权完全一样）
/res pset <领地> <假人名> build true
/res pset <领地> <假人名> place true
/res pset <领地> <假人名> destroy true
/res pset <领地> <假人名> container true
/res pset <领地> <假人名> use true

# 也可以直接按权限组批量授权
/res gset <领地> default build true
```

> ⚠️ **注意**：如果你修改了 Leaves 配置里的假人名字前后缀，`fullName` 会变，
> UUID 随之改变，之前授予的权限就会对不上。改名前请先把旧 UUID 的权限迁移过去。

### 4.1 `pset` 和 `set` 的层级关系

Residence 判定一个 flag 走的是**三层回退**（`FlagPermissions.playerHas`）：

```
pset（玩家级 playerFlags）        ← /res pset <领地> <玩家> <flag> <value>
  └─ 组级（groupFlags）           ← /res gset ...
       └─ set（领地级 cuboidFlags）← /res set <领地> <flag> <value>
            └─ flag 自带默认值
```

所以**没给假人单独 `pset` 时，生效的就是领地 `set` 的值**——假人和陌生玩家走同一套判定。
注意 `build` 这一层的默认值是 `true`，所以「什么都不设」不等于「禁止建造」，
该显式 `set` 的还是要设。

### 4.2 假人得先"上户口"，否则 `set` 设了也没用（2026-10-05 修复）

上面那条链有个**前置门槛**，在 `playerHas` 开头：

```java
PermissionGroup group = PermissionGroup.getGroup(player);
if (group == null)
    return false;        // ← 直接拒绝，set / pset 都不看
```

`getGroup` 只查 Residence 内存里的玩家档案表（`playersByUUID`），**不会自动加载**；
而档案的建立**只挂在 `PlayerJoinEvent` 上**。

**假人不触发 `PlayerJoinEvent`**——Leaves 创建假人时派发的是 `BotJoinEvent`
（`extends BotEvent`，和 `PlayerJoinEvent` 没有任何继承关系）。于是在修复前：

> 全新假人在 Residence 眼里「没有权限组」→ 任何 flag 判定都直接返回 `false`
> → **领地里攻击、捡物品、开箱子全都没权限，哪怕 `set` 里全开了。**

**症状是"看起来像权限没配好"，很容易被误判成配置问题而不是插件问题。**

**插件的解法**：替假人补上那条 `PlayerJoinEvent`——直接调
`Residence.getInstance().getPlayerManager().getResidencePlayer(botPlayer)`，
把假人写进 `playersByUUID`。时机有两个：

1. **假人上线时**（监听 Leaves 的 `BotJoinEvent`）——**这是主力路径**。
   必须在上线那一刻做：**捡物品这类判定根本不经过本插件**
   （Leaves 没有对应动作，是 Residence 自己的监听器在拦），
   等我们派发事件时再补就已经晚了。
2. **拦截到动作时兜底**——覆盖插件自身重载、Residence 重载后没来得及重建的情况。

档案一旦建立就会落盘（`plugins/Residence/Save/players/<uuid>.yml`），
服务器重启时由 `PlayerManager.load()` 预加载，所以**建一次就永久有效**。

> **怎么确认它生效了**：`/bm status` 的「假人领地档案」一行应显示「已建立 N 个」。
> 若显示「未接通」，假人在领地里的权限会**一律被拒**——那是"过严"，不会漏保护。
> 排查单个假人用 `/bm debug`，输出的第一行就是档案状态。

---

## 5. Flag 覆盖对照表

Residence 的 flag 按作用域分三类，下面分别说明假人的覆盖情况。

### 5.1 插件直接拦截（Leaves 绕过了事件、必须由本插件补的路径）

这些 flag 在假人身上原本**完全失效**，现在由插件补上对应事件后生效：

| 分类 | Flag | 假人动作 | 插件补的事件 |
|---|---|---|---|
| 破坏 | `destroy` `build` | `break` | `BlockBreakEvent` |
| 放置 | `place` `build` | `use_on` | `BlockPlaceEvent` |
| 交互 | `use` `container` `button` `lever` `door` `diode` `table` `bed` `cake` | `use_on` | `PlayerInteractEvent` |
| 功能方块 | `anvil` `beacon` `bank` `anchor` `brew` `cartography` `loom` `smithing` `stonecutter` `grindstone` `fletching` `flowerpot` `note` `enchant` `harvest` `pressure` `commandblock` `goathorn` | `use_on` | `PlayerInteractEvent` |
| 物品修饰 | `honey` `honeycomb` `brush` `copper` `dye` | `use_on` | `PlayerInteractEvent` |
| 载具 | `vehicleplacing` | `use_on` | `PlayerInteractEvent` |
| 液体 | `lavaflow` / 水流 | `use_on` | `PlayerBucketEmptyEvent` |
| 倒空桶 | （桶相关 flag） | `use_on` | `PlayerBucketFillEvent` |
| 点火 | `ignite` | `use_on` | `BlockIgniteEvent` |

### 5.2 走真实事件路径（Leaves 没绕过，Residence 本来就能拦）

这些是双保险：插件也做了检查，但即使插件不介入，Residence 也能收到真实事件。

| 分类 | Flag | 假人动作 | 真实事件 |
|---|---|---|---|
| 战斗 | `animalkilling` `mobkilling` `friendlyfire` | `attack` | `EntityDamageByEntityEvent` |
| 领地级战斗 | `damage` `pvp` `witherdamage` | `attack` | `EntityDamageEvent` |
| 实体交互 | `animalfeeding` `nametag` `trade` | `use_to` | `PlayerInteractEntityEvent` |
| 农牧 | `shear` `leash` | `use_to` | `PlayerShearEntityEvent` / `PlayerLeashEntityEvent` |
| 拾取 | `itempickup` | 走动 | `PlayerPickupItemEvent` |
| 踩踏 | `trample` | 走动 | `EntityChangeBlockEvent` |
| 投掷 | `enderpearl` `egg` | `use` | 投掷物 / 传送事件 |
| 载具 | `riding` | `mount` | `VehicleEnterEvent` |

### 5.3 由插件直接查 flag（真实事件无法可靠触发）

这两个动作在 Leaves 里不走标准路径，事件不可靠，所以直接读 flag：

| Flag | 假人动作 | 说明 |
|---|---|---|
| `itemdrop` | `drop` | `ServerDropAction` 调用 `bot.dropAll()`，不触发 `PlayerDropItemEvent` |
| `hook` | `fish` | 钓鱼逻辑由 Leaves 自己维护鱼钩状态 |

### 5.4 假人没有对应行为（不适用，无需覆盖）

`chat` `command` `subzone` `admin`（聊天 / 命令类）、
`tp` `chorustp` `elytra`（传送 / 飞行）、
`vehicledestroy`（假人不会主动拆载具）、
`push` `windexplode`（活塞 / 风弹机制）

假人在当前版本不会执行这些行为，因此不存在绕过风险。

### 5.5 领地级 flag（74 个 `FlagMode.Residence`）

这类 flag 用 `/res set <领地> <flag>` 设置，**对所有非成员生效**，
不区分玩家还是假人。只要事件能触发（即 5.1 / 5.2 覆盖的路径），它们就自动生效。

### 5.6 默认关闭的项

`move` `fly` `nofly` 对应假人的移动动作，默认**关闭**。
原因：假人多数时间在领地外活动，开启后会让假人卡在领地边界原地打转。
确有需要可在 `config.yml` 里把 `guard.move` 改成 `true`。

---

## 6. 配置项

见 `plugins/BotManager/config.yml`，每项都有中文注释。要点：

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `debug` | `false` | 打印每次拦截的详细日志（排查用） |
| `reach` | `5.0` | 视线检测距离，与 Leaves 内部保持一致即可 |
| `guard.break` | `true` | 破坏方块 |
| `guard.interact` | `true` | 右键交互 |
| `guard.place` | `true` | 放置方块 |
| `guard.use-item` | `true` | 使用物品 / 桶 / 点火 |
| `guard.attack` | `true` | 攻击实体 |
| `guard.vehicle` | `true` | 上下载具 |
| `guard.item-drop` | `true` | 丢弃物品 |
| `guard.fish` | `true` | 钓鱼 |
| `guard.move` | `false` | 移动（见 5.6） |
| `bot.default-limit` | `0` | 未单独设置的玩家默认能召唤几个 |
| `bot.sync-on-join` | `true` | 玩家上线自动拉起他的假人 |
| `bot.sync-on-quit` | `true` | 玩家下线自动存档移除他的假人 |
| `bot.restrict-inventory-to-owner` | `true` | 只能开自己假人的背包（见 8.4） |
| `bot.name-conflict` | `protect` | 玩家与假人同名时的处理，`protect` / `deny`（见 8.5，做了命名空间隔离后用不到） |
| `menu.max-slots` | `5` | 菜单里最多能买到第几个号位，到顶后购买按钮变成「已达购买上限」 |
| `menu.price-list` | `[50000, 100000, 200000, 300000, 500000]` | 显式价格表，第 n 项就是第 n 个号位的价 |
| `menu.price-per-slot` | `0.0` | 没配价格表时的第 1 档底价，`0` = 免费 |
| `menu.price-growth` | `1.0` | 价格表用完（或没配）之后，每往后一格乘这个倍数。`2.0` = 逐格翻倍 |

三项价格配置只要有一档大于 `0` 就需要 Vault + 一个经济插件（见 8.9）。
`price-list` 非空时优先用表；表用完之后的号位，拿最后一项继续按 `price-growth` 往上乘
（配 `growth: 1.0` 就是"表外不再涨，一直按最后一项收"）。

**价格跟着号位走，不跟着"买过几次"走**——`/bm limit add` 白送出去的上限也会占掉前几档。

**`max-slots` 只管菜单购买这一条路**，`/bm limit add` 是管理操作，不受它约束。
调它不会动任何已经买到的东西：把 5 改成 3，手上已有 5 个号位的玩家**一个都不会少**，
只是购买按钮变成牌子；改回去又能买。

改完配置执行 `/bm reload` 即可生效，无需重启。

---

## 7. 命令

### 7.0 `/bm help`

按执行者**实际拥有的权限**列出所有可用指令，一行一条；没权限执行的子命令**不会出现**在列表里。
它本身不需要任何权限，所有人都能用。直接敲不带参数的 `/bm` 效果等同——
区别是拥有 `botmanager.admin` 的人看到的是状态面板而不是帮助。

一个指令都不剩时（比如权限被显式拒绝成 `-botmanager.spawn`），会补一句红字说明，
而不是甩一个空列表让人以为是插件坏了。

### 7.1 假人管理（普通玩家可用）

| 命令 | 权限 | 默认 | 说明 |
|---|---|---|---|
| `/bm spawn [编号]` | `botmanager.spawn` | true | 召唤假人。不写编号就自动挑最小的空号 |
| `/bm list` | `botmanager.spawn` | true | 列出自己的假人、在线状态和下一个空号 |
| `/bm recall [编号]` | `botmanager.spawn` | true | **收回**假人：背包和坐标存档，编号留着，下次召唤原样回来 |
| `/bm remove <编号>` | `botmanager.spawn` | true | **遣散**假人（真删，物品掉在原地，编号回收） |
| `/bm action <编号> ...` | `botmanager.spawn` | true | 控制假人动作，语法同 `/bot action` |
| `/bm menu` | `botmanager.spawn` | true | 打开图形菜单（见 8.9） |

`/bm remove` 和 `/bm action` **必须写编号**——它们要明确指向哪个假人，没法替你猜。

#### 收回（`recall`）和遣散（`remove`）是两件事

面板上的「收回」按钮原本只有图形界面里有，命令层面只有 `remove`，这俩差得很远：

| | 收回 `/bm recall` | 遣散 `/bm remove` |
|---|---|---|
| 背包 | **存档**，下次召唤原样回来 | **掉在原地**，谁捡到算谁的 |
| 坐标 | 存档，站回原来那格 | 不存 |
| 编号 | **留着**，还是你的 | **回收**，号位上限不变但编号腾出来了 |

`/bm recall` 的**编号可以不写**，但有条件：

- 名下**恰好只有 1 个**假人在线 → 直接收回它，不用点名。
- **2 个及以上在线** → 插件**拒绝猜**，回一句
  「你有 N 个假人在线，得点名：`/bm recall <编号>`」，并列出在线的编号。
  再点一次带上编号即可。
- **一个都不在线** → 回「你现在没有在线的假人」，不是报错——离线假人的背包早就存着了，
  本来就不需要收回。想连编号一起清掉才用 `/bm remove <编号>`。

> 这里刻意不"自动挑第一个"。两个假人一个在挖矿一个在挂机，自动挑等于替玩家做决定，
> 收错了要重新跑回去。多一个假人在线就多打一个数字，比收错划算。

命令与菜单是**同一条实现**（`BotService#recall`），不存在"菜单能存档、命令只会删"这种分叉。

`/bm action` 与 `/bot action` 的唯一区别是**把假人名换成编号**：

```bash
/bot action test.1 start break      # Leaves 原生写法
/bm  action 1      start break      # 等价，编号就是名字里点后面那个数字
```

它**只能作用于你自己的假人**，而且是结构性保证，不是靠权限判定：
假人名是从执行者自己的名字推导的（`<你的名字>.<编号>`），
所以玩家 B 输入编号 1 只会去找 `B.1`，**根本寻址不到** `A.1`。

**普通玩家不需要 `bukkit.command.bot` 权限。** 转交时插件会临时给执行者挂上这个权限，
执行完立刻摘掉——授权窗口只有一次方法调用那么长，全程在主线程同步完成，
玩家输入插不进来，所以不存在"趁授权期再发一条 `/bot`"的可能。

> ⚠️ 这里有个反直觉的坑：**转交必须用 `Command#execute()`，不能用 `Bukkit.dispatchCommand()`**。
> 实测（Leaves 1.21.8）挂上 attachment 之后，
> `hasPermission("bukkit.command.bot")` 确实是 `true`、`testPermissionSilent()` 也返回 `true`、
> OP 派发和控制台派发都正常，但**非 OP 玩家**经 `dispatchCommand` 派发时，
> 仍然只得到一句 `I'm sorry, but you do not have permission...`，
> 而且命令体**根本没被执行**（返回 `true` 却没有任何回执）。
> 把 `bukkit.command.bot.action` 等子权限一并补上也救不回来，
> 说明这道关不在 Leaves 的权限判定里，而在 Paper 的 Brigadier 派发链路中。
> 代价：不走 `PlayerCommandPreprocessEvent`、也不进服务器命令日志——
> 对这个"换个名字转交"的场景反倒是好事，不会重复记账。

动作语法与参数表见 [第 8.7 节](#87-动作速查)。

### 7.2 管理（仅 OP）

| 命令 | 权限 | 说明 |
|---|---|---|
| `/bm limit <玩家>` | `botmanager.admin` | 查询该玩家的上限 |
| `/bm limit set <玩家> <数量>` | `botmanager.admin` | 直接设定上限 |
| `/bm limit add <玩家> <数量>` | `botmanager.admin` | 在其当前上限上增加 |
| `/bm limit reduce <玩家> <数量>` | `botmanager.admin` | 在其当前上限上减少 |
| `/bm status` | `botmanager.admin` | 查看运行状态、在线假人及其 UUID |
| `/bm reload` | `botmanager.admin` | 重载配置并刷新监听器缓存 |
| `/bm debug <假人名>` | `botmanager.admin` | 诊断该假人当前朝向的方块能否被破坏 |

Tab 补全已覆盖：子命令按权限过滤（`help` 无条件出现）、`/bm remove` 只列自己的编号、
`/bm debug` 列在线假人名、`/bm limit` 列操作符与在线玩家名。

帮助表（`BotManager.HELP`）和 `onCommand` 里的权限判定是**两份独立的清单**，
加了新子命令要同时改两处，否则 `/bm help` 会漏掉它。

`/bm debug` 会逐步打印判定链：假人位置、目标方块、该处的 `build`/`destroy` flag 判定值、
`canBreakBlock` 的等价表达式结果、以及派发结果和异常。用于区分两种失败——
是 Residence 判定为无权限（授权问题），还是本插件在 Residence 放行后仍拦下（插件问题）。

> **`destroy` 会覆盖 `build`。** Residence 的判定式是
> `playerHas(destroy, playerHas(build, true))`：只要领地显式设置了 `destroy=false`，
> 光给 `build=true` 是没用的。授权时若不确定，两个都给：
> `/res pset <领地> <假人> build true` 与 `/res pset <领地> <假人> destroy true`。

---

## 8. 假人管理系统

### 8.1 命名规则

```
/bm spawn          （玩家 test）
      │
      ▼  ① 编号：从 1 往上找第一个空号
rawName = test.2
      │
      ▼  ② Leaves 补上前后缀（prefix/suffix 建议留空）
全名 = test.2
```

#### 编号就是「号位」

每个玩家的可用编号是 **`1` 到他的上限**。上限既是"最多几个假人"，也是"能用哪些编号"
——两者是同一件事，所以编号合法就等于数量没超，不存在"编号对但名额满了"这种状态。

上限 5 的玩家能开 `test.1` … `test.5`。

#### 不写编号：自动挑最小的空号

`/bm spawn` 不带参数时，插件从 1 往上找**第一个空号**。已有 `test.1`、`test.5`、`test.6`
时，这次得到 `test.2`——空号会被补上，不会一路往上加。

#### 写编号：自己挑，但不能超过上限

`/bm spawn 5`（上限 5）合法，可以跳过前面直接开 5 号；
`/bm spawn 6` 被拒绝——**编号数字不能大于上限**。

`/bm list` 会直接告诉你下一个空号是几，`/bm spawn ` 的 Tab 补全也会把可用编号列出来。

#### 编号会被回收

`/bm remove 2` 之后，编号 2 立刻空出来，下一次 `/bm spawn` 就会补到 2。
**假人被打死也一样**——插件监听 Leaves 的移除事件，把号位一起回收（见 8.2）。
注意：服务器开着死亡不掉落也**照样掉**，这是故意的，理由见 8.2。

这是**真删**——假人消失，背包里的东西**掉在原地**（玩家能自己捡回来），
之后再召唤到同一编号是全新的假人、空背包。

> 为什么 `/bm remove` 不能"留着存档"？因为编号会复用。留着存档的话，
> 下次补到同一编号时会把删掉的假人连背包一起复活，玩家会以为见鬼了。
> 想保留背包就别 `/bm remove`——**玩家下线时假人本来就自动存档**（见 8.2），
> 那个路径不受影响。

#### 名字里的那个 `.` —— 命名空间隔离

这是本节最重要的设计。Minecraft 的玩家名只允许 `[a-zA-Z0-9_]`，而 `.` 不在其中。
所以 `test.1` 这个名字**在正版验证服上任何人都注册不到**，
撞名问题从根上不存在（见 8.5）。

> ⚠️ **`online-mode: false` 时这条不成立**，实测见 §8.5「离线服实测：隔离不成立」。
> 判断依据是**你自己的服务器是不是正版验证**，不是这个 `.` 本身。

所以 **Leaves 的前后缀建议留空**：`.` 由插件自己放在名字里，不靠前后缀。
前后缀留空还有个好处——不占那 16 字符的额度。

> 要加前缀的话**必须用不含 `.` 的形式**（比如 `b_`），否则前缀里的点和编号分隔符混在一起，
> 插件分不清哪段是玩家名。

#### 字符数

Leaves 对全名的硬上限是 16 字符。前后缀由 Leaves 配置决定，插件运行时读取，不写死。
前后缀留空时：`玩家名 + 1 (.) + 编号位数 ≤ 16`，即 **玩家名 + 编号位数 ≤ 15** ——
编号是个位数（1-9）时玩家名最长 14 字符，两位数（10-99）时 13 字符，
五位数（10000-99999）时 10 字符。超出时 `/bm spawn` 会提前拒绝并说明原因。

#### 插件在背后做了什么

Leaves 自己的校验 `BotUtil.isCreateLegal` 要求全名匹配 `^[a-zA-Z0-9_]{4,16}$`，
含 `.` 的名字会被它拦下。但它的写法留了口子（`BotList.createNewBot`）：

```java
event.setCancelled(!BotUtil.isCreateLegal(state.fullName()));   // 先按正则取消
pluginManager.callEvent(event);                                 // 再派发
if (event.isCancelled()) return null;                           // 唯一的一道闸
```

`BotCreateEvent` 是可取消事件，所以 `BotNameGate` 在监听器里按本插件的命名规则
把它改回放行即可——**不需要改动或重新编译 Leaves**。事件之后整条路径再无校验：
`createBotProfile` 直接 `new GameProfile(uuid, name, ...)`，不经 `NameValidator`；
存档加载路径（`loadNewBot`）更是完全不过这个正则。

放行有三个前提，缺一不可：命名确实与玩家名隔离、全名符合 `<前缀><玩家名>.<编号><后缀>`
的形状（所以 `/bot create foo.bar` 依然被挡）、当前没有同名假人在线
（Leaves 的查重被一并绕过了，插件自己补上）。

### 8.2 上下线同步

> 这一节的存档还原和 8.1 的 `/bm remove` 是**两条独立路径**：
> `/bm remove` 是真删（不写存档），上下线是自动存档还原。别把两者混起来理解。

假人的**存在本身就是状态**，插件不记录"第几次召唤"：

| 时机 | 行为 |
|---|---|
| 玩家**下线** | 名下每个假人 `remove(save=true, resume=false)`——写进 manual 存档后消失 |
| 玩家**上线** | 对每个登记在册的编号：先试 `loadNewManualSavedBot(全名)` 还原，无存档则重新创建 |
| 服务器**关服** | `onDisable` 里尽力补一次存档（见 8.4） |

于是首次召唤的假人出现在**玩家脚下**，之后每次上下线都回到**上次下线时的位置**，
背包、血量、正在执行的动作一并恢复——即需求里的"两者结合"。

用 `sync-on-join` / `sync-on-quit` 可以分别关掉这两个方向。

#### 各种"消失"场景下，东西去哪了

假人消失的途径不止一种，物品的去向**取决于 Leaves 收到的 `save` 参数**——
`BotList.removeBot` 里只有一道分支（`if (event.shouldSave())`）：存档，**或者**掉一地。

| 场景 | 触发方 | `save` | 物品去向 | 号位 |
|---|---|---|---|---|
| 玩家**下线** | 插件 `dismissAll` | **true** | **存档保留**，下次上线原样还原 | 保留 |
| 玩家**上线**时的还原失败 | 插件 `restoreAll` | — | 存档还在，下次再试 | 保留 |
| **`/bm remove`** | 插件 `dismiss` | false | **掉在原地** | 回收 |
| **假人被打死** | Leaves `ServerBot#die` | false | **掉在原地** | 回收（插件监听 `BotRemoveEvent`） |
| **服务器关服**（`/stop`） | 插件 `onServerCommand` 抢在 Leaves 之前存档（8.6） | **true** | **存档保留** | 保留 |
| 玩家登录**撞名** | Leaves `placeNewPlayer` | false | **掉一地**——但插件抢在它之前存档了（8.5） | 保留 |

所以：

- **玩家下线、服务器重启都不会丢东西。** 下线走存档分支；
  关服由插件抢在 Leaves 的 `removeAll()` 之前把在线假人存走，
  `resident-fakeplayer` 配 `true` 还是 `false` 都一样（机制见 8.6）。
- **只有 `/bm remove` 和被打死会让东西掉在原地**，这两者行为完全一致（都是 `save=false`），
  也是刻意统一的：玩家主动不要了 / 被打死了，东西都该留在现场让人捡。
- **服务器崩溃（非正常关服）** 是唯一的例外：`removeAll()` 和插件的抢先存档都没机会跑，
  假人状态会退回**上一次存档点**（玩家上次下线，或上一次 `/stop`）。
  这是任何服务端都躲不掉的，只能靠定期备份。

#### 假人死亡**不**受 `keepInventory` 保护（刻意保留的）

如果你的服务器开着死亡不掉落，会注意到一件事：**真人死了不掉东西，假人死了照掉。**

这不是插件漏了，是**刻意保留 Leaves 的原生行为**。原因是：

- `ServerBot.die()` 整个覆写了 `ServerPlayer.die()`（源码里留着
  `// TODO: separate die and remove logic, call super.die here`），末尾直接调
  `removeBot(DEATH, save=false)`；`ServerBot.dropAll(boolean)` 从背包到装备栏逐格 `drop()`，
  **全程没有任何 gamerule 检查**。
- 拦下它是做得到的（`BotDeathEvent` 在掉落之前派发，且可取消），但**我们选择不拦**：
  假人在本插件里就是个随叫随到的"容器/劳力"，不是需要保护进度的真人。
  让它死了掉一地，语义才和 `/bm remove` 一致——**不要了就该吐出来**，
  也没法拿假人当"永远不会丢东西的保险箱"。

> 所以打死别人的假人是能把东西捡走的。这是设计如此，**不是漏洞**。
> 不希望玩家互相偷家的话，靠 Residence 的 `pvp`/`damage` 等 flag 管住假人所在的区域，
> 而不是靠 `keepInventory`。
> `/bm status` 的「假人死亡掉落」一行会提示当前服务器的 `keepInventory` 状态。

### 8.3 数据文件 `plugins/BotManager/bots.yml`

```yaml
limits:                                    # 每玩家上限，键为 UUID
  550e8400-e29b-41d4-a716-446655440000: 3
owned:                                     # 该玩家名下已有的编号
  550e8400-e29b-41d4-a716-446655440000:
  - 1
  - 2
```

键用**玩家 UUID** 而非名字，玩家改名后配额不会归零。
一般不需要手改——`/bm limit`、`/bm spawn`、`/bm remove` 会维护它。

编号从 **1 到上限**，所以这个列表里所有编号都 ≤ `limits` 里那个数。

### 8.4 背包归属

Leaves 的 `modify.fakeplayer.open-fakeplayer-inventory` 一旦打开，**任何玩家空手右键
任意假人就能翻它的背包**——包括别人的假人。本插件会拦下不属于开启者的假人：

```
玩家右键假人 → ServerBot.interact()
                     │
                     ▼
            BotInventoryOpenEvent（可取消）
                     │
         ┌───────────┴───────────┐
      是自己的                不是自己的
         │                       │
     放行，开背包          setCancelled(true)
```

归属判定与上下线同步用的是**同一个口径**（`LeavesBridge.ownsRawName`），
所以不会出现「背包打不开、但一下线就被收走」这种前后矛盾的行为。

| 配置 / 权限 | 默认 | 说明 |
|---|---|---|
| `bot.restrict-inventory-to-owner` | `true` | 关掉则恢复 Leaves 原行为（谁都能开） |
| `botmanager.inventory.others` | op | 拥有者不受归属限制，可开任意假人背包 |

该配置每次事件现读，`/bm reload` 后立即生效，不需要重注册监听器。
`/bm status` 会显示当前是否生效。

> 若 Leaves 那边没开 `open-fakeplayer-inventory`，本来就没谁能打开假人背包，
> 本项自然也不起作用——`/bm status` 会把两个状态分别列出来。

### 8.5 名字抢注

> ⚠️ **2026-09-15 实测更正：`online-mode: false` 的服务器上，本节描述的威胁是真实存在的。**
>
> 原先的判断是「假人名形如 `test.1`，中间那个点由插件自己放，而 Minecraft 玩家名只允许
> `[a-zA-Z0-9_]`，真人注册不到这种名字」。**这个判断只在正版验证（`online-mode: true`）下成立**——
> 那时名字由 Mojang 的会话服务器背书，服务端会核对来源，带点的名字拿不到有效会话。
>
> **离线服上没有任何东西在服务端校验名字格式**：那条 `^[a-zA-Z0-9_]{3,16}$` 规则
> 只是原版客户端输入框的限制，改过的客户端（或者直接用 mineflayer 这类库）
> 想叫什么就叫什么。实测往下看「离线服实测：隔离不成立」。
>
> ✅ **同一天已补上解法**：`namespaceGuard` 在登录阶段就把这种形状的名字挡在门外，
> 见下面的「解法：登录闸口」。所以离线服不再是裸奔状态——
> 但要注意，**之前**如果已经被抢注过（usercache 里已存在这种名字的账号），
> 那个账号从此进不来了，它的 `playerdata` 会留在盘上。

假人的名字空间和玩家的名字空间本来是**同一个**：Leaves 里假人的全名就是它的游戏档案名。
Minecraft 不允许两个同名在线，所以 `test` 的假人 `test.1` 和一个真名叫 `test.1`
的玩家必然互斥——名字里不带特殊字符时，**这个问题无法从根上消除**，只能决定冲突时牺牲谁。

#### 玩家的两个担心，逐个回答

**「权限被夺走」——不会。** 假人 UUID 来自
`UUID.nameUUIDFromBytes("Fakeplayer:" + 全名)`（第 4 节），真实玩家要么是 Mojang 分配的
正版 UUID，要么是离线服的 `nameUUIDFromBytes("OfflinePlayer:" + 名字)`。
前缀不同，哈希必然不同，**两者 UUID 永不相等**。Residence 权限按 UUID 存，
所以授予假人的领地权限不会落到抢名玩家头上——那个玩家进服后面对的还是他自己那份权限。

**「这个编号永远用不了」——会发生，但可以控制。** Leaves 自己遇到同名时的处理是
「删假人、留玩家」，而且删的时候不走存档：

```
PlayerList.placeNewPlayer()          ← 玩家进服
   └─ removeBot(bot, INTERNAL, player, save=false, resume=false)
          └─ bot.dropExperience(); bot.dropAll(true)   ← 背包物品直接掉一地
```

注意 `save=false`：**假人背包里的东西不会进存档，只会掉在地上**。更麻烦的是拦不住——
`removeBot` 的取消检查是 `if (event.isCancelled() && reason != INTERNAL)`，
而这里恰恰是 `INTERNAL`，**取消 `BotRemoveEvent` 也没用**。

唯一可行的插手时机在 `placeNewPlayer` **之前**，也就是 `PlayerLoginEvent`：

```
玩家登录 → PlayerLoginEvent ← 本插件在这里抢先把假人存档并移除
              │
              ▼
        Leaves 的 placeNewPlayer 找不到同名假人 → 什么都不发生
```

假人已被插件存档收走，等主人下次登录时原样恢复（背包、位置、血量、动作都在），
**物品一件都不会掉**。

#### 两个取值

| `bot.name-conflict` | 抢名玩家 | 假人 | 适用 |
|---|---|---|---|
| `protect`（默认） | 放行进服 | 存档收走，主人下次登录恢复 | 不想踢人，能接受主人暂时用不了该编号 |
| `deny` | **拒绝登录** | 原样留着，不动 | 想彻底防住恶意抢注 |

两种取值都会给在线 OP 发提醒，控制台也会记录。

> `deny` 的代价：那个玩家会被踢，且在主人 `/bm remove <编号>` 之前**永远进不来**。
> 如果他恰好是正常玩家而不是捣乱的，这就变成了误伤。

#### 为什么会撞上

只要名字是 `bot_test_1` 这种纯 `[a-zA-Z0-9_]` 的形式，它就完全符合 Minecraft 的
名字规则（`^[a-zA-Z0-9_]{3,16}$`），**任何人都能注册**。加上 Leaves 默认前缀是 `bot_`，
猜到别人假人全名基本不需要成本。

名字里带上 `.` 之后就成立不了：`.` 不在 `[a-zA-Z0-9_]` 里，真人根本注册不到。
这也是 Floodgate（基岩版玩家互通）用 `.` 当前缀的同一个理由——
那套方案同样依赖"Java 版玩家不可能有这个名字"。

> ⚠️ **两套方案的依赖是同一个，限制也是同一个**：都只在正版验证服上成立。
> Floodgate 自己是靠改名规则绕过去的（`*` 前缀），也不能拿来当反例。

#### 插件的兜底逻辑

命名没有隔离时（`/bm status` 的「假人命名空间」显示未隔离），`BotService.onLogin`
仍然在 `PlayerLoginEvent`（`LOWEST`）上守着上面那套 protect / deny 流程。
隔离之后它**永远不会触发**——没有真人能拿这种名字登录。

留着它是为了两种情况：有人把 `LeavesBridge.SEPARATOR` 改回了普通字符，
以及将来万一有插件或服务端绕过了名字校验。

#### 离线服实测：隔离不成立（2026-09-15，Leaves 1.21.8，`online-mode=false`）

mineflayer 用 `auth: 'offline'`、用户名直接写 `<玩家名>.<编号>`，
**服务端照单全收，没有任何「Invalid characters in username」**。
`usercache.json` 里实打实地多了一条 `bmda.3` 的记录，
`world/playerdata/` 下也写了这个"玩家"的 `.dat`。

这里有个**最要命的设计缺口**：`BotService.loginFallback()` 这个兜底监听器
**只有在 `isNamespaced()` 为 `false` 时才注册**。而带点的命名下
`isNamespaced()` 恒为 `true`——**所以离线服上，正好是唯一需要它的场合，
它偏偏不在岗**。Leaves 自己那条 `removeBot(bot, INTERNAL, player, save=false, resume=false)`
路径（`save=false`！）就此畅通无阻。

**两种登录顺序，两种结果**：

| 顺序 | 结果 |
|---|---|
| **抢注者先登录**，主人再 `/bm spawn 3` | 被正确拒绝：「有个真实玩家就叫 `bmda.3`，这个名字不能用来造假人。」+「就算硬造出来，他一登录 Leaves 就会把假人连背包一起清掉，所以这里直接拦住。」——**这是有效的防御** |
| **假人先在线**，抢注者再登录 | ❌ **Leaves 把假人踢掉，让真人进来** |

第二种顺序的实测输出：

```
1) 假人上线: Total number: (1/30) | world(1): bmda.3 |   背包 = diamond×7
2) 真人用同一个名字 "bmda.3" 登录 → 登进去了
3) 之后 /bot list: Total number: (0/30) | world(0): |
   假人 bmda.3 还在线的? false
   原地有掉落物吗: 有            ← 整袋 7 颗钻石掉在假人脚下
   主人收到的消息: ["bmda.3 left the game", "bmda.3 joined the game"]
   存档 .dat: 没有
```

服务端日志里那两行是同一秒发生的：

```
[15:37:13 INFO]: bmda.3 left the game
[15:37:13 INFO]: bmda.3[/127.0.0.1:65406] logged in with entity id 56 at ([world]5.5, -60.0, -5.5)
```

**具体的损失，一条条说**：

1. **背包整袋掉在假人脚下的地上。** 主人不在线的话，`item` 实体 5 分钟就消失，
   东西**没了**。在线的抢注者知道名字、可以满世界找掉落物（掉落物对所有玩家可见）。
2. **假人复活后是空的。** 位置、背包、血量全丢——Leaves 用 `save=false` 踢的人，
   磁盘上什么都不会补写。下一次 `restoreAll` 给你一个空假人。
3. **主人只看到两行原版提示**（`X left the game` / `X joined the game`），
   **BotManager 没有任何提醒**——从主人视角看，就是自己的假人莫名其妙没了，
   然后有个叫 `bmda.3` 的人上线了。他会以为是假人卡掉了。
4. 抢注者在线期间，主人**暂时用不了这个编号**（上面那张表的第二种顺序），
   但**不是永久**——实测抢注者一下线，`/bm spawn 3` 立刻就能拿回来
   （`[BotManager] 已召唤 bmda.3`）。因为那个检查走的是
   `Bukkit.getPlayerExact()` → **只拦"当前在线"的同名真人**。

**会不会复制物品？——实测不会。** 这是唯一的好消息，机制值得记下来：

`world/fakeplayerdata/<uuid>.dat` 在**假人被还原上线时就被消费掉了**
（实测：主人下线写出存档 → 重登还原背包 7 颗 → **磁盘上那个 `.dat` 已经没了**）。
所以假人在线期间磁盘上没有它的陈旧存档，
被踢掉时也就没有"地上掉一份 + 存档里还留一份"的双份。实测把这条完整跑了一遍：

```
1) 造出有存档的假人（7 颗钻石）→ 主人下线 → 存档写出 ✅
2) 主人重登 → 背包还原 7 颗 ✅ → 【还原之后存档文件不在了，被消费掉了】
3) 抢注者顶掉它 → 假人没了，原地有掉落物，存档仍然没有
4) 主人再上下线 → 假人复活是【空】的，地上那 7 颗还在
   >>> 总量守恒，没有复制
```

（这一条要是反了就是无限刷物品，所以专门验了。）

#### 解法：登录闸口 `namespaceGuard`（2026-09-15 已实现并实测）

思路很直接：**在登录阶段就把这种形状的名字挡在门外**，
Leaves 走不到 `placeNewPlayer`，假人自然不会被顶掉。

```java
// BotService#namespaceGuard，注册在 AsyncPlayerPreLoginEvent（LOWEST）
String name = event.getName();
if (!leaves.isOurName(name)) return;          // 只拦「玩家名.编号」这个形状
event.disallow(KICK_OTHER, "这个名字的格式（玩家名·数字）是留给假人的，请换一个名字。");
```

三个关键取舍，都有实测依据：

**① 只在 `isNamespaced()` 为真时注册。** 分隔符要是被改回 `_`，
这个形状会开始匹配普通玩家名（`my_player_1`），那就成了误伤。

**② 只拦形状，不拦「名字里带点」。** `leaves.isOurName()` 认的是
`^<前缀>[a-zA-Z0-9_]+<分隔符>\d+<后缀>$`（前后缀按 Leaves 配置推导）。
一刀切会误伤**基岩版玩家**——Geyser / Floodgate 给他们的名字前缀正是 `.`。
而 `.Steve`、`Steve.abc` 这种名字本来就撞不上假人（假人编号恒为纯数字）。

**③ 用 `AsyncPlayerPreLoginEvent`，不用 `PlayerLoginEvent`。**
后者会触发 Paper 的警告：

```
[HorriblePlayerLoginEventHack] You have plugins listening to the PlayerLoginEvent,
 this will cause re-configuration APIs to be unavailable: [Residence, NameGateTest]
```

改完之后这条警告里**只剩 `[Residence]`**，本插件不再贡献。

**假人不会拦到自己人**：Leaves 的假人走 `BotList.placeNewBot()`，
只派发 `BotSpawnLocationEvent` / `BotJoinEvent`，**根本不经过 `placeNewPlayer`**。
源码可查，实测也确认（假人上线那一刻监听器一条日志都没有）。

##### 实测结果（Leaves 1.21.8，离线模式）

| 登录名 | 期望 | 实测 |
|---|---|---|
| `bmka` | 放行 | ✅ 进去了 |
| `bmka.3` | 拒绝 | ✅ 被拒 |
| `bmka.03`（前导零） | 拒绝 | ✅ 被拒 |
| `bmka.abc`（点后面不是数字） | 放行 | ✅ 进去了 |
| `.Bedrock`（基岩版玩家的形状） | 放行 | ✅ 进去了 |
| `bmka_1`（下划线，没有点） | 放行 | ✅ 进去了 |

真正的攻击场景（假人 `bmka.3` 带着 7 颗钻石在线，抢注者用同名登录）：

```
抢注者被拒 ✅        假人活下来了 ✅
假人背包 diamond×7 没动 ✅        地上没有掉落物 ✅
之后主人给假人塞东西、传送它都正常 ✅
```

**14 项断言全过**；同一份 jar 上菜单防刷回归 **40 项全过**。

> 闸口挡下时会往控制台打 WARNING，并给在线的 `botmanager.admin` 发一条提醒。
> 通知走 `runTask` 丢回主线程——`AsyncPlayerPreLoginEvent` 是异步的，不直接碰玩家。
>
> `bot.name-conflict`（protect / deny）仍然保留，但**只在命名空间隔离失效时才有意义**
> ——隔离生效时真人根本进不来，那道兜底永远不会触发。

Leaves 的 `modify.fakeplayer.resident-fakeplayer`（字段 `canResident`）会改变**关服**行为，
两种取值下插件都工作正常，但表现不同：

| `resident-fakeplayer` | 关服时 Leaves 做什么 | 结果 |
|---|---|---|
| `false`（默认） | `removeAll()` 以 `save=false` 移除：**存档丢弃，背包连经验掉一地** | 插件在 `/stop` 执行前抢先存档收走假人（见下），**存档保留** |
| `true` | `removeAll()` 以 `save=true, resume=true` 存档 | **Leaves 自己就能完整还原**，插件无需插手 |

#### 关服为什么必须抢在 `/stop` **执行之前**（不只是"插件关得早"）

目标服配的是 `false`，也就是上面那行「存档丢弃、掉一地」。原来的做法是在
`onDisable` 里补存档，**但那条路根本走不通**——Leaves 的顺序是（已对部署 jar
用 `javap -c` 核对过 `stopServer()` 的字节码）：

```
stopServer()
   81: "Stopping server"
  102: BotList.removeAll()          ← 假人在这一行就被清掉了
  126: CraftServer.disablePlugins() ← 插件的 onDisable 这才开始跑
```

轮到 `onDisable` 时在线列表已经是空的，一个都存不到。所以关服时看到的是两个
绑在一起的现象：**东西掉一地**（`removeAll` 走了 `dropAll` 分支），
**重启后假人出现在玩家脚下而不是关服时的位置**（存档被删了，`restoreAll`
只能走新建分支）。

唯一的时机是命令执行之前。插件监听 `ServerCommandEvent`（控制台、面板的
「停止」按钮从这条走）、`RemoteServerCommandEvent`（RCON）和
`PlayerCommandPreprocessEvent`（OP 玩家敲 `/stop`），一看到 `stop` / `restart`
就先把在线假人 `saveAndRemove` 掉。等 Leaves 再 `removeAll()` 时列表已空，
掉物品的分支进都进不去。

> ⚠️ RCON 那条**必须单独注册**。`RemoteServerCommandEvent` 虽然继承自
> `ServerCommandEvent`，但它自己声明了一份独立的 `HANDLER_LIST`，
> 父类监听器收不到。详见 §10.0.1。

关掉 `bot.save-on-shutdown` 可恢复 Leaves 的原生行为。

`kill -9` 这类强杀没有任何代码能跑，`save-on-shutdown` 覆盖不到——
它归 `bot.autosave-interval-minutes` 管（见下）。

关键机制（`BotList.removeAll` → `removeBot`）：

```
关服 removeAll()
   ├─ bot.resume = canResident
   └─ removeBot(..., save=canResident, resume=canResident)
          │
          ├─ resume=true  → 存进 resumeDataStorage，开服时 loadResume() 自动拉起
          └─ resume=false → 存进 manualSaveDataStorage，等插件按需 loadNewManualSavedBot()
```

本插件的 `dismissAll`（玩家下线）固定用 `remove(save=true, resume=false)`，
即**始终写 manual 存档**，所以玩家下线后他的假人**不会**在开服时自己冒出来——
`resume` 那条路径是留给 Leaves 自己用的。两条路径互不干扰，也不会重复召唤
（`restoreAll` 会先查假人是否已在线）。

> **`resident-fakeplayer: true` 的副作用**：关服那一刻还在线的假人会被 Leaves 记进 resume 存档，
> 下次开服时**在任何人登录之前**就自动回来。所以如果关服时玩家 A 在线，
> 开服后 A 的假人会先站一会儿，直到 A 自己登录（`restoreAll` 发现它已在线，跳过）。
> 想让假人严格等于「主人登录期间才存在」，把这一项设回 `false`，
> 由本插件独占存档即可。

### 8.7 动作速查

> 只想让假人「长按左键」「间歇右键」这些，**不用记命令**——`/bm menu` 的操作面板上
> 有现成按钮（见 8.9）。本节是按钮背后的东西，也是面板没覆盖到的那些动作用的。

`/bm action <编号> <子命令>`，子命令是 `start` / `stop` / `list`。

#### start 的参数

**参数按位置解析**，不能写 `delay=5` 这种名字；**全部可选**（除 `move`、`look`），
省略就是从左边截断的前缀。默认值：`delay=0`、`interval=20`、`do_number=1`、`use_timeout=-1`。

| 动作 | 参数 |
|---|---|
| `attack` `break` `drop` `jump` | `[delay] [interval] [do_number]` |
| `fish` | `[delay] [interval] [do_number]`（`interval` 是**重新抛竿**间隔，动作本身每 tick 都跑） |
| `use` `use_auto` `use_on` `use_to` `use_offhand` `use_on_offhand` `use_to_offhand` | `[delay] [interval] [do_number] [use_timeout]` |
| `sneak` `swim` `mount` `swap` | 无参数 |
| `rotation` | `[yaw] [pitch]`（各为浮点，范围 -180..180 / -90..90；不给则用你的朝向） |
| `move` | `<方向>`，**必填**：`forward` / `backward` / `left` / `right` |
| `look` | `<坐标>`（必填，支持 `~` 相对坐标）**或** `[玩家名]`——二选一 |

- `do_number` 填 `-1` 表示**无限重复**；`0` 无意义。
- `interval` 单位是 tick（20 tick = 1 秒）。
- `look` 和 `rotation` 不给参数时取**执行命令的玩家**的坐标/朝向，所以
  `/bm action 1 start rotation` 会让假人转向你看向的方向。

```bash
/bm action 1 start break              # 一直挖面前的方向（do_number 默认 1，挖一次）
/bm action 1 start break 0 20 -1      # 延迟 0，每 20 tick 挖一次，无限次
/bm action 1 start move forward       # 向前走
/bm action 1 start rotation 90 0      # 转向正东、平视
/bm action 1 start look ~ ~ ~1        # 看向身后一格
/bm action 1 list                     # 看当前挂着的动作及序号
/bm action 1 stop all                 # 全部停掉
/bm action 1 stop 0                   # 停掉序号 0 的动作
```

#### 为什么是委托，不是自己实现

Leaves 的 19 个动作分散在 4 个抽象基类上，`look` 还有一套 forked 参数树
（坐标 / 玩家选择器二选一），参数类型涵盖整数、浮点区间、枚举和 Paper 的
`finePosition` / `player` 解析器。在插件里复刻一份，意味着 Leaves 每加一个动作、
每改一个参数都要跟着改，迟早对不上。

所以 `/bm action` **把命令行原样转交给 Leaves 的 `/bot action`**，语法永远同步。
代价是多一步权限处理：`/bot action` 要 `bukkit.command.bot` 权限，普通玩家没有，
插件会在执行期间**临时挂一个权限附件**，执行完立刻摘掉——而不是用控制台身份执行，
这样回执和报错都直接落到玩家聊天栏，`BotActionExecuteEvent` 里记录的也仍是玩家本人。

> ⚠️ 转交是**原样透传**：你多打的参数由 Leaves 自己校验，报错也是 Leaves 的原文。
> 想知道某个动作到底收什么，`/bot action` 的原生补全比本文档更权威。

### 8.8 已知限制

- **玩家改名 = 换假人 UUID。** 假人 UUID 由全名推导（见第 4 节），玩家改名后
  rawName 变了，全名跟着变，旧假人的 Residence 权限和存档都对不上。
  这是 Leaves 的机制决定的，无法规避。
- **假人名字可以被抢注。** 见 8.5。当前命名（`test.1`，含 `.`）下这个问题不存在；
  如果哪天把 `LeavesBridge.SEPARATOR` 改回 `_`，插件就只能做到「不让假人掉东西」，
  代价是主人暂时用不了那个编号（`protect`）或踢掉抢名玩家（`deny`）。
- **`/bm remove` 是真删，物品掉在原地。** 见 8.1。假人连存档一起没了，
  背包里的东西掉在它脚下等人捡。想保留背包就别 remove——玩家下线时假人自动存档（8.2）。
- **假人死亡 = 自动回收号位，但背包不会留。** Leaves 打死假人时传 `save=false`，
  物品掉在原地（和 `/bm remove` 一致），插件监听 `BotRemoveEvent` 把号位回收掉。
  **服务器的 `keepInventory` 管不着假人**——真人死了不掉、假人死了照掉，这是刻意的（见 8.2）。
  反推不出主人 UUID 时（玩家改过名、且不在线）号位不回收，玩家自己 `/bm remove <编号>` 清。
- **改前后缀会让假人 UUID 变化。** UUID 由全名推导（见第 4 节），
  所以改 Leaves 的 `modify.fakeplayer.prefix`/`suffix` 后，
  旧假人的存档和 Residence 权限都对不上。要在发权限之前定好。
- **关服时有玩家在线 → 他的假人会自己回来。** 见 8.6，这是 `resident-fakeplayer: true`
  的固有行为，不是 bug。
- **全局限额是硬顶。** Leaves 的 `modify.fakeplayer.limit`（默认 10）是**全服**上限，
  不是每玩家上限。达到后召唤会失败，提示里会说明是全局上限已满而非玩家上限。
  玩家配额之和可以超过它，但实际能同时在线的不超过这个数。
- **`loadNewManualSavedBot` 是服务端内部方法**，不在 leaves-api 的文档面上。
  所有调用都走反射并带降级路径：拿不到时假人退化为"每次都是全新状态"，
  启动日志会打印警告。
- **手工创建的假人可能被误伤。** 插件用 raw name 前缀（`<玩家名>.`）判断归属，
  所以管理员用 `/bot create test.1` 手工造的假人（要过 `BotNameGate` 那道放行），
  会被当成玩家 `test` 的假人在其下线时一并收走。想避免就给手工假人换个名字。
- **上表里"实测"过的是第 10 节列出的那些**，其余仍来自 Leaves 源码阅读。
  没进第 10 节的，按第 10 节的步骤自己过一遍。

### 8.9 图形菜单 `/bm menu`

把「买号位 → 召唤 → 操作」串成一个箱子界面，省掉敲命令。**入口是 `/bm menu`**，
需要 `botmanager.spawn`（和 `/bm spawn` 同一个权限）。

#### 主菜单 = 仓库

格子从第 1 格起按顺序排，**一格一个号位**，格子下标 i 对应号位 i+1：

| 状态 | 界面 |
|---|---|
| 上限 0 | `[购买]` `-` `-` `-` … |
| 上限 2，没召唤 | `[可用1]` `[可用2]` `[购买]` `-` … |
| 上限 2，召唤了 1 个 | `[实例1]` `[可用2]` `[购买]` `-` … |
| 上限 2，召唤了 2 个 | `[实例1]` `[实例2]` `[购买]` `-` … |

也就是**前「上限」格是号位，紧跟的那一格固定是购买按钮**，其余为空。
容器行数按需增长（上限每满 9 个长一行，最多 6 行）。

**购买一次只加一个上限**，等价于 `/bm limit add <玩家> 1`。因为购买按钮永远出现在
最后一个号位之后，它代表的就是下一个号位——"点哪买哪"和"加一个"是同一件事，
不存在跳号多收钱的情况。

#### 三种格子的点击行为

| 格子 | 物品 | 点击后 |
|---|---|---|
| **可用**（号位在你名下、还没召唤过） | 淡绿色染料 | 进小面板：只有「生成假人」和「返回」 |
| **实例**（已经召唤过，含离线） | 玩家头颅 | 进完整面板：召唤 / 收回 / 移除 / 七个动作 / 停止全部 / 返回 |
| **购买** | 金锭 | 上限 +1，那一格随即变成「可用」；买到 `menu.max-slots` 后变成屏障「已达购买上限」 |

**点「生成假人」会把该格子对应的号位真的拉上线**——点第 3 格生成的就是 `#3`，
不是"随便找个空号塞进去"，所以界面上看到的编号和生成出来的编号必然一致。

#### 「可用」和「实例」的判据是 `bots.yml` 的登记，不是"在不在线"

假人离线（被收回、服务器重启、主人下线）时**号位仍然属于玩家**，这时它是
「离线实例」——点进去看到的是完整面板里写着「离线」的信息头、可用的召唤，
而不是被当成一个还没买过的空号。回收号位只有两条路：`/bm remove`（真删）
和假人死亡。

#### 操作面板

**完整面板**（实例，6 行）：

```
                 ·          ·          ·          ·
 ① 信息                     4 假人头
                 ·          ·          ·          ·

 ② 去留         11 召唤假人  13 收回假人  15 移除假人

 ③ 点按         20 点按左键  22 点按右键  24 潜行
 ④ 间歇         29 间歇左键  31 间歇右键  33 跳跃
 ⑤ 长按         38 长按左键  40 长按右键  42 停止所有操作

 ⑥ 挖方块 / 返回 47 挖方块   49 返回            53 命令用法
```

**一列管一个鼠标键**：列 2 全是左键（20 / 29 / 38 / 47），列 4 全是右键（22 / 31 / 40），
列 6 放不属于任何一边的（潜行 · 跳跃 · 停止所有操作）。列内再按**动作**分组——
左键列的上面两格都是 `attack`（打实体），下面两格都是 `break`（挖方块），
这就是用户要的「攻击和攻击放一起，破坏和破坏放一起」。

左右两列的**前三行节奏一一对齐**（点按 / 间歇 / 长按），横着看过去就是同一个档位的
左键版和右键版；左键列多出来的第 4 格「挖方块」是「长按左键」那个 `break` 的自选间隔版，
紧挨着放。

| 格子 | 内容 |
|---|---|
| 4 | 假人头颅，lore 写号位、在线/离线、正在跑的动作（离线时不写动作那行） |
| 11 / 13 / 15 | 召唤 · 收回 · **移除假人** |
| 20 / 29 / 38 / 47 | 左键列：点按左键 · 间歇左键 · 长按左键 · 挖方块 |
| 22 / 31 / 40 | 右键列：点按右键 · 间歇右键 · 长按右键 |
| 24 / 33 / 42 | 潜行 · 跳跃 · 停止所有操作 |
| 49 | 回主菜单 |
| 53 | 「命令用法」说明书——**只展示 lore，点了没反应**（见下） |

**小面板**（可用）：11 生成假人、15 返回。

「收回」走的是 `BotService#recall`——**存档并下线**，背包、坐标、血量都写进存档，
号位保留，下次召唤原样还原。和下面「移除假人」那个真删是两回事。

#### 移除假人（15 号格，带二次确认）

**遣散的是假人，不是号位。** 这句话是这一格的重点，界面上反复写了两遍——
因为它紧挨着「收回假人」，而两者差着十万八千里：

| | 收回假人 | 移除假人 |
|---|---|---|
| 假人 | 下线，**存档** | 消失，**存档清除** |
| 身上的东西 | 原样存着，下次回来还在 | **掉在原地**，得自己去捡 |
| 还能召回来吗 | 能，原样还原 | **不能**，召回来的是全新空假人 |
| 号位归谁 | 还是你 | **还是你** |
| 号位上限 | 不变 | **不变** |

所以移除之后那一格会变回「可用假人」，随时能再生成一个——
**点它不会让你少买一个号位**，花的钱和买到的名额都还在。
（实现上只清了 `bots.yml` 里的号位登记，`limits` 一个字没动，
全项目里只有 `/bm limit` 会改上限。）

点一下先进一个 27 格的确认窗口——11 红色「确认移除」、13 假人头颅、
15 绿色「取消」，按 Esc 或点取消都当没发生过。确认窗口里也写了"上限不会减少"，
并顺手指路「只想让它下线、把东西留着？那是收回假人」。

确认后走的是 `/bm remove <编号>`，不在菜单里重写第二份删除逻辑。

> ⚠️ 假人身上的东西会**掉在原地**，不是掉在你脚下。假人在离家 3000 格的矿洞里，
> 掉落物就留在那儿。

#### 动作按钮

面板上的七个动作键最后都转交给 `/bot action <假人> start …`，和手动敲命令走同一条路，
所以参数解析、动作名、冷却这些永远和 Leaves 自己保持一致。假人当前跑了哪些动作
**每次都现查服务端**（`LeavesBridge#runningActions`），不是本地记一本账——
菜单开着的时候别人用命令给假人加了动作，界面也看得出来。

| 按钮 | 格子 | 动作 | 命令 | 点亮判据 |
|---|---|---|---|---|
| **点按左键** | **20** | `attack` | `start attack 0 0 1` | 有个 `attack` 且次数 = 1 |
| 间歇左键 | 29 | `attack` | `start attack 0 <你输的> -1` | 有个 `attack` 且间隔 ≠ 1 |
| 长按左键 | 38 | `break` | `start break 0 1 -1` | 有个 `break` 且间隔 = 1 |
| **挖方块** | **47** | `break` | `start break 0 <你输的> -1` | 有个 `break` 且间隔 ≠ 1 |
| **点按右键** | **22** | `use_auto` | `start use_auto 0 0 1` | 有个 `use_auto` 且次数 = 1 |
| 间歇右键 | 31 | `use_auto` | `start use_auto 0 <你输的> -1` | 有个 `use_auto` 且间隔 ≠ 1 |
| 长按右键 | 40 | `use_auto` | `start use_auto 0 1 -1` | 有个 `use_auto` 且间隔 = 1 |
| 潜行 | 24 | `sneak` | `start sneak` | 有个 `sneak` |
| 跳跃 | 33 | `jump` | `start jump 0 <你输的> -1` | 有个 `jump` |
| 停止所有操作 | 42 | — | `stop all` | 有任意动作在跑 |

**「点按」和「长按」是两套参数，不是两档节奏。** 长按那两格是「一直做，间隔 1 tick」，
点按那两格是「做一次就收手」——`do_number = 1`。所以面板上是**四格**，按鼠标键分列摆：
左键列 20 / 29 / 38 / 47、右键列 22 / 31 / 40，两边都是点按在前、长按在后。

#### 命令用法（53 号格，一张只读的说明书）

面板按钮能替你填的只有**第 4 个数（间隔）**：首次延迟固定 `0`、次数固定 `-1`。
想改这两个就只能走命令，所以右下角放了一本「命令用法」——**只有 lore，没有点击事件**：
点它不掉东西、不关窗、不改任何状态（连 `hint()` 那句「▶ 左键 …」都没写，
那是给能点的格子用的）。lore 里写的就是这两条：

```
/bm action <编号> start <操作> <首次延迟> <之后每次延迟> <次数>
/bm action <编号> stop <动作编号>        /bm action <编号> stop all
```

- **单位是 tick**（20 tick = 1 秒）；**次数填 `-1` 就是一直做下去**——面板上长按那几格用的就是它。
- 操作名就是面板上跑起来时写的那个词：`attack` / `break` / `use_auto` / `sneak` / `jump`。
- 动作编号用 `/bm action <编号> list` 看。

**三个数的顺序是实测定下来的，不是照印象写的。** 2026-09-15 用鸡蛋消耗量做了个判别实验
（`delayprobe.js`）：给假人 16 个鸡蛋、跑 `start use_auto 100 20 -1` 观察 15 秒。
若第 1 个数是首次延迟（5 秒后开始、之后每 1 秒一个）应当扔出 11 个；若顺序反过来
（1 秒后开始、之后每 5 秒一个）只会有 3 个——**实测 10 个**，顺序就是
「首次延迟 → 之后每次的延迟 → 次数」。

> 判据为什么不是数掉落物：**投出去的鸡蛋落地会碎**，压根不会变成地上的实体。
> 只能用 `/clear <假人> minecraft:egg 0` 拿一个计数回来（`maxCount=0` 是"只数不清"，
> 样本还在），所以整个实验只能测一个时间窗、不能中途轮询。

> 💡 **停掉一个动作之后，它后面那些动作的编号会往前挪。** 真机上 `list` 从
> `0 use_auto / 1 attack` 变成 `0 attack`——编号是列表里的位置，不是身份证。
> 连着停两个时最容易照着刚才看到的编号停错，所以这条也写进 lore 了。

#### 左键一列：挖 / 打 / 来一下

跟游戏里的手感对齐：**长按左键就是挖方块，点按左键才是打生物**。左键这一列的四格：

| | 点按左键（20） | 间歇左键（29） | 长按左键（38） | 挖方块（47） |
|---|---|---|---|---|
| 动作 | `attack` | `attack` | `break` | `break` |
| 实现类 | `ServerAttackAction` | `ServerAttackAction` | `ServerBreakBlockAction` | `ServerBreakBlockAction` |
| 打什么 | 视线里的**实体** | 视线里的**实体** | 视线里的**方块** | 视线里的**方块** |
| 取目标 | 实体射线 `getEntityHitResult` | 同 20 | `getTargetBlockExact(5)` | 同 38 |
| 节奏 | **次数 1，间隔 0** | 你输的间隔 | 固定间隔 1 | 你输的间隔 |
| 图标 | 木剑 | 铁剑 | 钻石镐 | 钻石镐 |

**列内按动作分组**：上面两格都是 `attack`（打实体），下面两格都是 `break`（挖方块），
同名的紧挨着——一眼看得出哪几格是一回事。

#### 右键一列：同是 `use_auto`

右键那一列同理，动作全是 `use_auto`：

| | 点按右键（22） | 间歇右键（31） | 长按右键（40） |
|---|---|---|---|
| 动作 | `use_auto` | `use_auto` | `use_auto` |
| 做什么 | 跟真人右键一样**来一下** | 有间隔地按 | **一直按着** |
| 节奏 | **次数 1，间隔 0** | 你输的间隔 | 固定间隔 1 |
| 图标 | 草方块 | 钓鱼竿 | 钓鱼竿 |

**`use` 和 `use_auto` 不是同一动作的两档，是包含关系**——这是 2026-09-15 用户实测
「点按右键放不了方块」之后才查清的。**`use` 没有理由选，要完整的右键就用 `use_auto`。**

**`use_auto`（`ServerUseItemAutoAction`）才是真人的完整右键。** 它先 `getHitResult`
射线取目标，然后对主手/副手依次：

1. 命中**方块** → `useItemOn` —— 放方块、开箱子、按按钮、锄地都在这条路
2. 命中**实体** → `useItemTo`
3. 都轮不上 → `useItem` —— 吃东西、扔雪球、拉弓、喝药水

**`use`（`ServerUseItemAction`）只做第 3 步，根本不射线**，所以它放不了方块、开不了
箱子、按不了按钮，只能"用"手上的东西。

真机对照（假人手持 64 块石头、`pitch 60` 朝下看着前下方一格空气）：

| | 那一格 | 手里石头 | 动作列表 |
|---|---|---|---|
| `use 0 0 1` | 空气 → **还是空气** | 64 → **64** | **还挂着** |
| `use_auto 0 0 1` | 空气 → **变成石头** | 64 → **63** | 自己收了 |

`use` 那一行尤其难看：它连"用了一次"都算不上——对空气用方块物品返回的是"不消费"，
`do_number` 不扣，于是动作**永远挂着等**，按钮也一直亮着。

> **别被"两个名字"骗了。** 光看 `use` / `use_auto` 会以为是"简单版 / 完整版"两个平级
> 选项，实际上 `use` ⊂ `use_auto`。这一点字节码里写得很清楚：`ServerUseItemAutoAction`
> 的 `interact` 走到最后一步时，调的就是 `ServerUseItemAction.useItem`（static，被复用），
> 而 `ServerUseItemAction.interact` 本身**全部内容就是这一句**。

**2026-09-15 改的**：长按左键这一格原本挂的是 `attack`，被用户否掉了——模拟"长按左键"
的按钮去起 `attack` 从一开始就对不上，它在视线里没有可攻击实体时**什么都不做**
（连挥手动画都不发），玩家对着石头按到手酸也挖不掉一块土。实测对照：改之前 `attack`
跑满 4 秒石头纹丝不动；改之后同一个按钮**第 7 秒挖穿**。图标也顺势从铁剑换成了镐子——
这格已经不打算打任何东西了，摆把剑只会让人以为它还能打。

**点按那两格**（20 / 22）是同一天加的，补的是另一半：光有"长按"没有"来一下"，
想让它打一下或者用一次东西就得回聊天栏敲命令。

这几格是**不同的动作**，所以**可以同时跑**：一边挖一边打是合理组合，不像原来那样
互斥。真正的同名互斥搬到了 `break` 上——38 格和 47 格共用这一个动作，来回点只会剩一个。

#### 同名不同档：为什么按钮要认「次数」

`attack` 这个名字 20 和 29 两格都在用，`use_auto` 22 / 31 / 40 三格都在用。**光看间隔
分不开它们**：20 格的间隔是 0，落在"间隔 ≠ 1"那一档里，会跟 29 格一起点亮。

所以按钮的判据里多了**次数**这一维（`LeavesBridge.RunningAction#doNumber`，取
`ServerBotAction#getDoNumber`）。点亮要同时对上名字、间隔、次数三项：

- 20 格 `attack` 次数 = 1 → 点 20 亮的只有 20，29 不跟着亮。
- 29 格 `attack` 次数 = -1（不限）→ 点 29 亮的只有 29。

> `do_number = -1` 是**合法值**（不限次），不能拿来当"读不到"的占位。读不到时
> `LeavesBridge` 返回 `Integer.MIN_VALUE`，`numberKnown()` 才是 false——这时退回
> 只看间隔的旧逻辑，不会把界面点亮成一片。

「挖方块」（47）跟「长按左键」（38）是同一个动作的两档节奏，跟右键那两格（长按固定 1 /
间歇自选）是同一套分法。它排在左键列第 4 格——是 `break` 里"自选间隔"那一档，
就贴在「长按左键」下面，不用满面板找。

- 挖的是**视线正前方 5 格内**的方块，**对着哪儿就挖哪儿**。想让它挖某个方向，
  先用 `/bm action <编号> start rotation <yaw> <pitch>` 把朝向转过去。
- 挖穿一个算一次，隔 `<interval>` tick 接着挖下一个；`do_number` 固定 `-1`，即**无限次**，
  停不停玩家说了算（再点一次按钮即可）。
- **间隔是"两块之间的停顿"，不是"每 tick 挥几下"**。实测：间隔 1 时相邻两块差 7.56 秒
  （就是空手挖一块石头本身的时间），间隔 60 时差 10.58 秒——**多的 3.02 秒正好是
  59 tick**（理论 2.95 秒），间隔确实是原样传到 Leaves 的。
- 假人手里**没有工具**，空手挖石头一块要 7.5 秒。真要它干活，先拿镐子塞进它手里。

- **再点一次就是关掉**，不用另找按钮。
- **点了弹一个原版对话框**（Minecraft 1.21.6+ 的原生对话框），里头有个输入框，
  填完按「确定」即可。箱子界面塞不下输入框，所以退出来弹。
- **填的数不会进公屏。** 这条链路上压根没有聊天事件——输入走的是对话框响应包。
  （早先版本是"回聊天栏敲数字"，装了 SimpleChat 这类插件时数字必然进公屏，
  事件层面无解，详见 [10.5](#105-聊天输入换成了原生对话框2026-09-15-真机定位并修复)。）
- 输入范围 `1` ~ `72000` tick（20 tick = 1 秒）。填 `abc`、`0`、负数都会被拒：
  对话框会**原地再弹一次**，原因用红字写在正文里，**不用退出去重新点菜单**。
- 想放弃就按 Esc、右上角的叉，或者点「取消」——都只是把框关掉，什么都不会发生。
- **共用同一个 Leaves 动作的两格是互斥的**，区别只在间隔：间隔 1 就是最密的节奏
  （长按），大于 1 就是间歇。所以「长按左键」和「挖方块」共用一个 `break`，
  点一个会把另一个停掉再起新的，绝不会叠成两个 `break` 在同一个假人身上各挖各的。
  右键那两格同理（共用 `use_auto`）。

  > **左键那几格是例外，它们本来就不互斥**：「长按左键」是 `break`（挖方块）、
  > 「间歇左键」是 `attack`（打实体），是两个动作，可以同时跑——一边挖一边打是
  > 合理组合。2026-09-15 改之前两格都是 `attack`，那时才是互斥的。
  >
  > **「点按左键」（20）跟「间歇左键」（29）同名 `attack`，但不同档**（次数 1 vs 不限），
  > 共存判据靠 `doNumber`，见 [8.9 同名不同档](#同名不同档为什么按钮要认次数)。

  > Leaves 的 `addBotAction` 是**纯追加、不去重**的，同一个动作挂两次就是真做两次。
  > 插件在启动新动作前会先按动作名清一遍，这是踩出来的——早期版本从「间歇左键」
  > 切到「长按左键」会留下两个 `attack`。
- 「停止所有操作」（42 号格）一次清空，按钮的 lore 里会列出正在跑的每一个动作
  和它们的间隔。清完之后界面**要等 2 tick 再刷新**：Leaves 是在动作被取消后的
  下一 tick 才把它从列表里摘掉的，刷太快会看到还在。

> **「运行中」说的是动作在跑，不是"打到了/挖到了"。** `ServerAttackAction.doTick` 要求
> 视线里真的有一个可攻击的实体（`isAttackable() && !skipAttackInteraction(bot)`），
> 射线打空就直接 `return false`，连挥手动画都不发；`ServerBreakBlockAction` 同理，
> 视线 5 格内没有方块就空转。面板上按钮照样写「运行中」。
>
> **`do_number` 是"命中才算数"的。** `doTick` 只有真的打到了才返回 true、才扣掉一次。
> 所以「点按左键」在视线里没有实体时**不会自己消失**，它会一直挂在那儿等目标出现，
> 打中一下才收手——这是 `do_number = 1` 该有的语义，不是卡住了。想中途取消就再点
> 一次那一格，或者点「停止所有操作」。
>
> 「点按右键」（`use_auto`）同理：射线打空、而且手上东西也 `consumesAction()` 不了
> （空手、或拿着放不下也用不出的东西）时，它会一直等下去。

#### 经济（可选）

三项价格配置**全 0 时免费解锁，不需要装任何经济插件**——这是默认状态。

有任意一档大于 0 时需要 **Vault + 一个经济插件**（Vault 本身只是接口，不带钱）。
插件用反射接入 Vault，没装也不会启动失败，只是启动时警告一句、玩家点购买会被告知
「服务器没有接入经济插件」。扣款失败（余额不足）不会给号位，给号位失败会**自动退款**。

价格有两种配法，见 6 章配置表。出厂默认是写法 B——五档 5万 / 10万 / 20万 / 30万 / 50万：

```yaml
menu:
  max-slots: 5          # 最多买到第 5 个号位
  price-list:           # 涨法不规律就直接列，第 n 项 = 第 n 个号位的价
    - 50000
    - 100000
    - 200000
    - 300000
    - 500000

  # 写法 A：涨法有规律时两行搞定，第 1 格 5 万、每格翻倍 → 5万 / 10万 / 20万 / 40万 …
  # price-per-slot: 50000
  # price-growth: 2.0
```

**买满之后**，购买按钮那一格变成屏障「已达购买上限」，点不动、不扣钱，
并且写明当前上限是几个。号位和假人一个都不动。

> `max-slots` 和 `price-list` 的长度不用对齐。`max-slots` 调得比表长，
> 多出来的档位拿表里最后一项继续按 `price-growth` 乘——
> 默认 `growth: 1.0` 就是一直按最后一项收（5 档表 + `max-slots: 8` ⇒ 后 3 个还是 50 万）。
> 想让它继续涨，把 `price-growth` 配成 `2.0` 之类。

**界面上印的价不是收的钱。** 收款金额在点击的那一刻用 `priceFor()` 现算，
不信物品 lore 里写的数——菜单开着的这段时间里，管理员可能 `/bm reload` 改了价，
也可能用 `/bm limit add` 把号位送出去几个。中间任何一步对不上（钱不够、
号位已经没了、经济插件掉线）都直接中止并原样退款。

**价格配置写坏时不会偷偷按 0 收。** 整个 `menu` 段读不出来（YAML 格式错误）时，
购买按钮会变成屏障、点击不予受理，并提示管理员检查配置——
Bukkit 在 YAML 出错时**静默加载空配置**且不会禁用插件，不显式挡一下的话
所有人都会以为号位是免费的。

**服务器上装了多个经济插件时，认准扣的是谁的钱。**

Vault 只是个接口，谁注册进来都行；装了多个时，`ServicesManager` 只会把
**优先级最高**的那一个交出来，插件代码和玩家界面都看不出区别——
买号位扣了一笔钱，玩家那边少了、管理员那边没收到，账就对不上了。
（开发时真踩过：测试服同时装着自制测试经济和 XConomy，菜单一直在扣前者的钱。）

所以开服日志和 `/bm status` 都会把名字报出来：

```
[BotManager] 购买走 XConomy（Vault 里优先级最高的那个经济插件）
```

```
经济后端：XConomy　扣款走 Vault
```

对不上账时先看这一行，确认两边说的是不是同一个后端。

**界面上印的金额一律用经济插件自己的写法。** 走 `Economy#format()`，
不自己拼货币符号——这样"钱包里显示的"和"菜单里印的"永远一致，
换货币单位（元 / dollars / 金币）也不用改插件。
购买按钮那条"下个多少钱"的预告行同理，早先用中文数量级压成 `10.0万`，
和上面一行的 `50,000.00 dollars` 并排，看着像两个系统，已经改掉。

#### lore 排版

界面上每条 lore 都按同一套规则写，不是随手拼字符串：

| 行型 | 样子 | 用途 |
|---|---|---|
| 分隔线 | `▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬`（暗灰） | 把说明切成几块，别糊成一坨 |
| 键值 | ` 状态 ● 在线`（标签暗灰、值按语义上色） | 竖着对得齐，眼睛直接跳到值上 |
| 正文 | ` 把号位 3 拉上线。`（灰） | 解释 |
| 操作提示 | ` ▶ 左键 生成假人`（金色箭头 + 亮黄动作词） | **永远在最后一行**，位置固定 |

标签统一两字（`状态` `价格` `号位` `在跑` `上限`…），所以同一块里的值是对齐的。
状态一律用圆点：`●` 绿=在线/运行中，`○` 暗灰=离线/未启用。
可点的格子都用 `▶` 那行收尾——扫一眼最底下就知道能干什么，不用逐行读。

分隔线用 `▬` 而不是一长串 `-`：原版字体自带这个字形，等宽、不抢眼，
一串连字符看着像没写完的减号。16 个刚好不会把 tooltip 撑到换行。

#### 几个刻意的实现选择

- **用 `InventoryHolder` 认界面，不认标题。** 标题带颜色码、会随文案改，
  玩家还能用铁砧把箱子改成同名来伪造一个；holder 是打开时由代码塞进去的对象引用，
  外部伪造不出来。
- **点击一律先 `setCancelled(true)` 再谈逻辑**，并且玩家背包那一侧也一起取消——
  否则 Shift 点击能把菜单当仓库用。拖拽事件单独拦一次。
- **只认左键。** 右键/中键一律当无事发生，否则同一个动作会触发两次购买。
- **每次点击都重新查一遍 `bots.yml`**，不信菜单里摆着的物品——菜单开着的这段时间里，
  管理员可能把上限减了，假人也可能被打死回收了号位。
- **跨行时会整个重开界面。** 买了第 9 个号位时容器要从 1 行长成 2 行，
  而**开着的界面改不了大小**，只能重开；没跨行就原地刷新，避免界面闪一下。
- **点「生成假人」是转交给 `/bm spawn <编号>` 跑的**，不在菜单里重写第二份逻辑——
  名字太长、撞真人名、全局上限这些边界情况那里都已经处理过了。

---

## 9. 代码结构

| 文件 | 职责 |
|---|---|
| `BotManager` | 插件入口、命令分发与 Tab 补全、事件监听注册 |
| `BotActionInterceptor` | 拦 `BotActionExecuteEvent`，把假人动作翻译回 Bukkit 事件 |
| `ResidenceHook` | 只把伪造事件投递给 Residence 自己的监听器 |
| `ResidenceFlags` | 直接查 Residence flag 的反射桥（用于事件不可靠的动作） |
| `GuardSettings` | `config.yml` 的 `guard.*` 开关 |
| `LeavesBridge` | **所有**对 Leaves 的反射调用的唯一出口 |
| `BotRegistry` | `bots.yml`：每玩家上限（= 可用编号范围）与名下已有编号，含空号查找 |
| `BotService` | 假人生命周期：召唤 / 遣散 / 上下线同步 / 死亡回收号位 / 同名玩家登录时的抢存档 |
| `BotInventoryGuard` | 假人背包归属：只允许开启者翻自己的假人背包 |
| `BotNameGate` | 命名空间隔离：放行含 `.` 的假人名，让真人注册不到同名账号 |
| `BotMenu` | 图形菜单：仓库式主菜单 + 完整面板 + 可用假人小面板（见 8.9） |
| `BotMenuHolder` | 菜单的身份标记，用它判断"这是不是本插件的界面" |
| `VaultEconomy` | Vault 经济的反射桥，没装时降级为"买不了"而不是崩 |

对 Leaves 的反射集中在 `LeavesBridge` 一个文件里，将来 Leaves 改 API 只需要改它。

---

## 10. 验证清单

### 10.0 已实测到哪一步（2026-09-15）

在本地架设 **Leaves 1.21.8-138-9331167** 真实服务端，
用 mineflayer 以真实玩家身份（`tester`，OP）连入，跑完了下列条目：

| 条目 | 结果 |
|---|---|
| 插件在 1.21.8 上启用 | ✅ 无异常，兼容层正确识别 `getRealName` / 单参 `remove` |
| 10.2-1 开号位 | ✅ `你的假人上限：5` |
| 10.2-2~6 自动分配 / 指定 / 超限 / 补空号 / 满了 | ✅ 依次得到 1、2、3、5；`spawn 6` 被拒**且未创建**；补到 4；满了不再创建 |
| 10.2-7 真删 + 原地掉落 | ✅ `/bm remove 1` 后钻石与金苹果以掉落物实体出现在原坐标 |
| 10.2-8 复用是全新的 | ✅ 补回 1 号后 `/bm list` 正常，号位计数正确 |
| 10.2-9 上下线还原背包 | ✅ 塞 7 颗钻石 → 玩家下线（**掉落物 0 个**）→ 重登自动还原 → `/bm remove` 钻石掉出 |
| 10.2-10 死亡回收号位 | ✅ 第一轮测试里假人被史莱姆打死，日志出现「已死亡，号位 N 已回收」，`/bm list` 空号随之变化 |
| 10.2-15 命令回执 | ✅ 全部中文回执正常渲染 |
| `/bm help` 权限过滤 | ✅ OP 见 10 条；非 OP 见 5 条（管理类全隐）；权限被拒光时只见红字说明 |
| `/bm` 无参 / 未知子命令 | ✅ 非 OP 无参见帮助；未知子命令先报错再列帮助 |
| 10.2-18 非 OP 用 `/bm action` | ✅ 用无 OP 的 `acttest` 跑 `list` / `start break` / `stop break` 全部成功，**权限报错 0 次**；动作经控制台原生 `/bot action acttest.1 list` 复核确实挂上了 |

| 10.2-9 背包跨上下线 | ✅ 往 `tester.4` 塞 7 颗钻石 → 玩家下线 → 重登，`data get entity tester.4 Inventory` 前后都是 `[{Slot: 0b, id: "minecraft:diamond", count: 7}]` |
| 10.2-9 还原计数 | ✅ 修复后日志为「还原 4 个，新建 0 个，失败 0 个」，无 `ClassCastException` |

> 🔧 **2026-09-15 修掉的一个隐蔽 bug**：日志里曾反复出现
> `还原假人 <名字> 的存档失败：java.lang.ClassCastException: class org.leavesmc.leaves.bot.ServerBot
> cannot be cast to class org.bukkit.entity.Player`。
>
> 根因：`BotList.loadNewBot(String)` 的返回类型是 NMS 体系的 `ServerBot`，不是 Bukkit 的 `Player`，
> 而 `LeavesBridge.loadSaved()` 直接 `(Player)` 强转，**每次必炸**。
>
> **炸得最阴的地方在于——假人其实是回来了的。** `loadNewBot()` 的语义就是
> "从存档加载并让假人上线"，副作用先于返回值发生：假人已经带着背包站在世界里了，
> 插件却因为强转失败拿到 `null`、以为自己失败了。于是：
> 日志里每次上线刷 N 条 WARN；计数「还原 0 个，新建 N 个，失败 N 个」**全是错的**；
> 还会再调一次注定失败的 `spawn`。
>
> 实测确认这不是"可有可无的冗余代码"：把 `restoreAll` 临时改成空操作后，玩家重登
> **一个假人都回不来**——Leaves 在 `resident-fakeplayer: false` 下**不会**自己恢复假人，
> 全靠插件调 `loadNewBot()`。所以修法是**把返回值转换修对**，而不是删掉这段逻辑。
>
> 修法：`loadSaved()` 里先过一道 `getBukkitEntity()`
> （`ServerBot.getBukkitEntity()` 返回 `CraftBot`，是 `CraftPlayer` 的子类，能安全转成 `Player`）；
> 另外 `restoreAll()` 和 `summon()` 在 `loadSaved` 返回 null 后都补查了一次在线状态，
> 防止"副作用已生效但没拿到句柄"时再造一个同名假人。

#### 后续补测结果汇总（2026-09-15 下半场）

测试服的 `leaves.yml` 已按目标服对齐（`limit: 30`、`open-fakeplayer-inventory: true`、
`cache-skin: true`、`in-game.skip-sleep-check: true`）；
Residence 6.0.1.4 + CMILib + Vault 2.5.0 全部装好，
经济用 **XConomy-Paper-2.26.3**（真实经济，SQLite 存余额；自制测试经济已挪到
`testserver/plugins-disabled/`，见 10.2 末尾），
mineflayer 可以同时挂多个账号当"第二个人"。

| 条目 | 结果 | 详见 |
|---|---|---|
| 10.2-9 上下线还原背包 | ✅ 塞 7 颗钻石 → 下线（存档写盘）→ 重登 → **原样带回 7 颗**；还原时存档文件被消费掉 | 本节 + 8.2 |
| 10.2-11 `keepInventory` 对照组 | ✅ `true` / `false` **两种取值下假人死了都照常掉东西**，真人能捡走——刻意保留的行为，不是漏拦 | §10.2-11 |
| 10.2-16 背包归属 | ✅ 空手右键**别人**的假人打不开；右键**自己**的能打开 | §10.2-16 |
| 10.2-18 动作归属 | ✅ A 的 `/bm action` 只认 `A.N`，碰不到 `B.N`；B 挂动作不会污染 A | §10.2-18 |
| 10.2-19 命名空间隔离 | ⚠️ **换台服务器就不成立**——见下面的"重要更正" | §8.5 |
| 10.3 菜单防刷 / 付费 / 双人 | ✅ 40 / 12 / 22 项断言 | §10.3 |

**尚未实测**：

- 10.1 的"授权后可以破坏"与"权限持久性"（`/res pset <领地> <假人名> build true` 之后）
- 10.1 的"不误伤其他插件"
- 10.2-20 兜底逻辑（要先把 `LeavesBridge.SEPARATOR` 改回 `_` 才有意义）

#### ⚠️ 重要更正：`.` 只在正版验证服上挡得住抢注（已修）

§8.5 开头那句「本节描述的威胁在当前命名下不存在」**只在 `online-mode=true` 时成立**。
离线服上真人能直接用这种名字登录，实测会把在线假人顶掉、背包整袋洒在地上。

**当天已补上解法**：`BotService#namespaceGuard` 在 `AsyncPlayerPreLoginEvent`
拦下「玩家名.编号」形状的名字，假人毫发无伤。详见 §8.5「解法：登录闸口」。

### 10.0.1 关服与崩溃（2026-09-15 实测）

**`/stop` 三条路径全部覆盖**，每条都亲眼看到「关服前已存档 N 个假人。」
出现在 `Stopping server` **之前**：

| 关服方式 | 命中的事件 | 主线程 | 结果 |
|---|---|---|---|
| OP 玩家敲 `/stop` | `PlayerCommandPreprocessEvent` | 是 | ✅ |
| 控制台 / 面板 stdin `stop` | `ServerCommandEvent` | 是 | ✅ |
| RCON `stop` | `RemoteServerCommandEvent` | 是 | ✅ |

> ⚠️ **RCON 那条一开始是漏的。** 曾以为 `RemoteServerCommandEvent extends
> ServerCommandEvent`，注册父类监听器就能接住——**错的**。它自己声明了一份独立的
> `HANDLER_LIST` 和 `getHandlers()`，Bukkit 派发时用的是子类那张表，父类监听器收不到。
> 现象是 RCON 发 `stop` 时日志里干干净净、一个假人都不存。
> 修法：单独注册 `onRemoteServerCommand`。**别再按继承关系推事件覆盖范围。**

端到端复核（RCON `stop` → 重启 → `tester` 上线）：
`tester.1` 回到 `[500.5d, -60.0d, 500.5d]`（关服时的坐标，**不是**玩家出生点
`-3.5/-60/2.5`），背包 `[{Slot: 0b, id: "minecraft:diamond", count: 14}]`，
血量满；上线前 `execute if entity @e[type=item]` 为 **Test failed**（地面无掉落物）。

**意外崩溃（`kill -9`）实测结果**——和 `/stop` 是两码事：

| 观察项 | 结果 |
|---|---|
| 地面掉落物 | **0 个**（`removeAll()` 根本没跑，`dropAll` 不会触发）|
| 假人还原 | 取决于 `autosave-interval-minutes`：**关闭时**「还原 0 个，新建 5 个」，磁盘上压根没有 `tester` 的记录；**开启时**「还原 5 个，新建 0 个」|
| 背包 / 坐标 | 关闭时全空 + 回到玩家脚下；开启时回到上次自动存档时的状态 |

原因（`javap` 实证，不是推测）：

- `BotDataStorage.save(Player)` 是**立刻写盘**的（`NbtIo.writeCompressed` → `world/fakeplayerdata/<uuid>.dat`），
  数据本身没问题；
- 但 `world/fakeplayer.dat`（待复原名单，`BotDataStorage.savedBotList`）只在
  **世界存档落盘**时才写。`restoreAll` 把假人拉上线后会消费掉名单里的条目，
  所以**假人在线期间，磁盘上没有任何"它该被还原"的记录**；
- `LeavesConfig.modify.fakeplayer.saveAllResumeBots` 受 `canResident` 门控，
  目标服是 `resident-fakeplayer: false` → **Leaves 自己不提供任何周期性自动存档**。

**这就是 `autosave-interval-minutes` 存在的理由**：插件每 N 分钟对每个在线假人
直接调一次 `BotDataStorage.save(bot)`，把完整状态写进 `.dat`。
**写盘时假人不下线、不掉东西、照常干活。**

#### 崩溃验收（2026-09-15，`kill -9`，间隔设为 1 分钟）

```
[执行] tp tester.1 400 -60 400  +  give tester.1 emerald_block 9
[等待] 一个自动存档周期
[崩溃前] Pos = [400.5d, -60.0d, 400.5d]
         Inventory = [{Slot: 0b, id: "minecraft:emerald_block", count: 9}]
[执行] taskkill /F  →  重启  →  玩家上线
[还原] "玩家 tester 上线，假人同步：还原 5 个，新建 0 个，失败 0 个。"
[还原后] Pos = [400.5d, -60.0d, 400.5d]          ← 崩溃时的坐标
         Inventory = [{Slot: 0b, id: "minecraft:emerald_block", count: 9}]
[地面]  0 个来自假人的掉落物
```

#### 关键发现：`purgeSaved` 必须删 `.dat`，清名单没用

`/bm remove` 之后再 `/bm spawn`，一开始**会诈尸**——假人带着 7 个下界合金块
原地复活，同时地上还躺着一份，等于凭空复制。

一度以为是"过期的名单条目没清掉"，但清了名单照样复现。
`javap BotList.loadNewBot(String, IPlayerDataStorage)` 才看清真正机制：

```java
UUID uuid = BotUtil.getBotUUID(name);      // ← UUID 由名字直接算出来
new BotLoadEvent(name, uuid);
new ServerBot(server, overworld, new GameProfile(uuid, name));
storage.load(bot, reporter);               // ← 直接按 UUID 找 .dat
```

**保存名单从那头到尾没被查过。** 决定"能不能复活"的只有
`world/fakeplayerdata/<uuid>.dat` 在不在。

所以 `purgeSaved` 的主路径是 `名字 → BotUtil.getBotUUID → botDir/<uuid>.dat` 删文件，
清名单只是顺手（避免名单里留垃圾条目）。修完复测：
`/bm spawn 1` 出的是**空背包**新假人，坐标在玩家脚下，不再复制。

> ⚠️ `CraftBot.remove(boolean save)` **永远返回 true**（字节码就是 `iconst_1`），
> 所以插件里"已存档"那句提示**不能当作存档成功的证据**，得看磁盘。

> ⚠️ 真删（`/bm remove`）和死亡回收都要调 `purgeSaved`：
> 自动存档可能刚替它写过 `.dat` 和名单条目，不清的话下次上线
> 它会连背包一起复活——而东西**已经掉在地上了**，等于凭空多一份。

`loadResume(String)` 开头是 `if (!enable || !canResident) return;`，
所以名单条目里写 `resume: true` **不会**导致启动时自动生成假人——
目标服的 `resident-fakeplayer: false` 在这条路上是安全的。

### 10.0.2 假人档案：`set` 过的权限对假人全部失效（2026-10-05 修复）

**现象（用户真机实测）**：假人在领地里**没有攻击、捡物品等任何基本权限**，
而这些 flag 都在 `/res set` 里开过，假人也没有做过任何 `pset`。

**根因**：`FlagPermissions.playerHas()` 开头先取 `PermissionGroup`，取不到就
**直接返回 false**，压根不看 `set`/`pset`。而 `getGroup` 只查 Residence 内存里的
玩家档案表，**不会自动加载**——建档只挂在 `PlayerJoinEvent` 上，而 Leaves 的假人
派发的是 `BotJoinEvent`（`extends BotEvent`，与 `PlayerJoinEvent` 无继承关系），
所以假人**从来没有档案**。详见 4.2。

**为什么"攻击/捡物品"比"破坏/放置"更明显**：Residence 的 `canBreakBlock` /
`canPlaceBlock` 在判定失败后**顺手**调了一次会建档的重载，所以破坏/放置是
"第一次被拒、第二次起自愈"；而攻击、捡物品走的 handler 没有这一步，
**永远**拿不到档案 → 永远拒绝。

**修复**：新增 `ResidenceCache`，在假人上线（`BotJoinEvent`）时替它补建档案，
并在动作拦截时兜底。修复后**待实测**的条目：

| 验证项 | 期望 |
|---|---|
| `/bm status` 的「假人领地档案」 | 「已建立 N 个」（N ≈ 在线假人数） |
| 召唤新假人后立刻查看 | N 递增，日志无警告 |
| 领地 `set build false` 的假人挖方块 | 拦住（保护仍在，没被这次修复削弱） |
| 领地 `set build true` 的假人挖方块 | **第一下就能挖**（修复前要第二下） |
| 领地 `set itempickup true` 的假人走过掉落物 | **捡得起来**（修复前永远捡不起） |
| 领地攻击类 flag（`pvp` / `damage` 等） | 按 `set` 生效（修复前一律被拒） |
| 重启服务器、不重新召唤直接测 | 仍然生效（档案落盘，启动时由 `load()` 预加载） |
| `/bm debug <假人>` 第一行 | 「Residence 档案：已接通」 |

> 修复前的行为是**过严**（一律拒绝），所以这段期间不存在"假人绕过领地保护"的风险；
> 受影响的只是"想让假人在领地里正常干活"的场景。

---

### 10.1 领地保护（2026-09-15 真机实测）

环境：**Leaves 1.21.8-138 + Residence 6.0.1.4 + CMILib 1.5.9.6**。

> ⚠️ **Residence 6.0.1.4 硬依赖 CMILib**（`depend: [CMILib]`）。
> 只放 Residence 的 jar，插件**根本不会加载**，日志里只有一行
> `Unknown/missing dependency plugins: [CMILib]`；
> 而 BotManager 只会淡淡地说一句「未找到已启用的 Residence」。
> 部署时两个 jar 必须一起上。CMILib 从 https://www.zrips.net/CMILib/CMILib<版本>.jar 直取。

启动自检（插件连上后自动跑，全绿才算接上）：

```
[BotManager] 已连接 Residence 6.0.1.4：/bot 假人现在与普通陌生玩家一样受领地权限约束。
[BotManager] Residence 事件覆盖自检 -> 破坏=OK 右键交互=OK 放置=OK 桶=OK
             实体交互=OK 战斗=OK 通用伤害=OK 载具=OK 移动=OK
```

#### 对照实验

领地 `bmtest`（20×20，owner=`tester`），假人 `tester.N` 用
`/tp tester.N 202.5 -60 200.5 90 3` 摆好位置看向目标方块，
`/bm action N start <动作>`。**两组的假人、动作、几何关系完全相同，
唯一变量是目标方块在不在领地里。**

| | 领地内 `200,-59,200` | 领地外 `250,-59,250` |
|---|---|---|
| `getPerms` 返回 | `ResidencePermissions` | `FlagPermissions` |
| `build` / `destroy` | `false` / `false` | `true` / `true` |
| `canBreakBlock` | `false` → 本应拦下 | `true` → 本应放行 |
| **派发结果** | **拦截** | **放行** |
| 方块实际 | 保留 ✅ | 被破坏 ✅ |

`/bm debug <假人名>` 的完整输出（领地内）：

```
Residence 监听 BlockBreakEvent : true
目标方块        : DIRT @ world 200,-59,200
getPerms        : ResidencePermissions
build           : false   (默认 true)
destroy         : false   (默认 = build 的结果)
canBreakBlock   : false  -> Residence 本应拦下
派发结果        : 拦截
派发异常        : 无
```

注意 `目标方块` 那一行——它证明**假人确实瞄到了方块**，
`拦截` 不是「没找到目标所以什么也没发生」的假象。
做这类测试时务必先确认这一行有值，否则结论是空的。

服务端日志（`debug: true`）里的直接凭据：

```
[BotManager] [调试] 已拦截假人 tester.1 的 break  动作（位于 world 202,-60,200）
[BotManager] [调试] 已拦截假人 tester.2 的 use_on 动作（位于 world 202,-60,200）
```

`break`（破坏）和 `use_on`（右键方块，覆盖开容器/按按钮/开门/贴方块放置）
**两条独立的代码路径都验证通过**；领地外的同一动作在整个日志里
**一条拦截记录都没有**。

#### 还没验的

1. **授权后可以破坏**：`/res pset <领地> <假人名> build true` 之后应能正常挖。
   这条**没测**——上面验的是默认（非成员）行为。
   （若授权后仍不动，补一条 `destroy true`，原因见第 7 节。）
2. **权限持久性**：`/bm remove` 后重新 `/bm spawn`，确认不用重新授权（UUID 未变）。
3. **不误伤其他插件**：连锁挖矿、任务、统计类插件是否因假人产生异常数据。

### 10.2 假人管理系统

**准备**：把 Leaves 的 `modify.fakeplayer.prefix` 和 `suffix` 都留空。
玩家名太长会顶到 16 字符上限的，换个短 ID 的号测。

1. **自动分配**：`/bm limit set <自己> 5` → `/bm list` 显示「号位：1 - 5　已占用：0/5」
2. 连做三次 `/bm spawn` → 依次得到 `<你>.1`、`<你>.2`、`<你>.3`；
   `/bot list` 能看到，`/bm list` 也列得出来，且「下一个空号：4」
3. **自己挑编号**：`/bm spawn 5` → 得到 `<你>.5`（跳过 4 是允许的）
4. **超上限被拒**：`/bm spawn 6` → 拒绝并提示「编号只能是 1 - 5」
5. **补空号**：现在有 1/2/3/5，再 `/bm spawn`（不写编号）→ 得到 `<你>.4`
6. **满了**：此时再 `/bm spawn` → 提示号位全满，不会报错崩掉
7. **编号回收 + 真删**：往 `<你>.2` 背包里塞几块钻石 → `/bm remove 2` →
   假人消失，**钻石掉在原地**（能捡回来）；`/bm list` 的「下一个空号」变回 2
8. **复用是全新的**：`/bm spawn` → 拿到 `<你>.2`，**空背包**、脚下位置
   （不是第 7 步那个带钻石的旧假人复活）
9. **上下线还原不受影响（关键对照）**：往 `<你>.1` 背包塞点东西、走到别处 →
   **退出游戏** → 重新登录 → 假人回到**上次的位置**且**背包东西还在**。
   这一步走的是存档路径，和第 7 步的真删是两回事——**如果这一步东西丢了，
   说明下线同步用错了移除方式**，是严重 bug，不是配置问题
10. **死亡回收号位 + 照常掉落**：往 `<你>.3` 背包塞点东西 → 把它打死
    （`/kill <你>.3` 或让怪物咬死）→
    - 东西**掉在原地**（和 `/bm remove` 一致）
    - 在线的主人收到「编号 3 已空出来」提示
    - `/bm list` 的「下一个空号」变回 3，`/bm spawn` 能补到 3
    - 若主人不在线（关掉 `sync-on-quit` 后测），号位同样回收，只是没有提示
11. **`keepInventory` 管不着假人（关键，你的服就是这种）**：确认 `/gamerule keepInventory`
    是 `true` → 往 `<你>.3` 背包塞点东西 → 把它打死 →
    - **东西照样掉在原地**——这是设计如此，不是漏拦
    - 拿另一个账号去把掉落物捡走，确认捡得到（假人不是"保险箱"）
    - `/bm status` 的「假人死亡掉落」显示「照常掉落（本服开着死亡不掉落，但那只保护真人…）」
    - 对照组：真人死一次，确认**不掉**东西。两者不一致是预期的

    > ✅ **2026-09-15 实测通过**（`bmca.1` / `bmca.2`，`keepInventory` **false 和 true 各跑一遍**）：
    > 两种取值下，打死假人后原地都出现掉落物实体
    > （`execute positioned <假人坐标> run execute if entity @e[type=minecraft:item,distance=..6]`
    > 返回 **Test passed**），真人走过去**能捡进自己背包**。
    > 所以"假人不受 `keepInventory` 保护"确认是真实的、可复现的，
    > 不是"物品没掉只是判据写错了"。
    > ⚠️ 判据要用**掉落物实体**，不能只看"玩家背包里多没多东西"——
    > 第一轮就是因为玩家站得不够近，捡不到，被误判成"没掉"。
12. **关服不丢东西**：假人背包塞点东西、**走到一个离玩家出生点很远的位置** →
    让玩家和假人都保持在线 → **正常关服再开服** → 主人登录后假人连同背包原样回来，
    **位置是关服时那个位置，不是主人脚下**。
    同时确认关服日志里有「关服前已存档 N 个假人」，
    且**地面上没有假人掉落的物品**（这是 `resident-fakeplayer: false` 下最容易踩的坑，见 8.6）
13. 长 ID 玩家执行 `/bm spawn` 应看到明确的 16 字符报错
14. 权限：非 OP 玩家能 `spawn`/`list`/`remove`，但 `/bm reload` 被拒
15. Tab 补全：`/bm ` 列出子命令；`/bm spawn ` 列可用编号；
    `/bm remove ` 只列自己名下已有的编号
16. **背包归属**（需 Leaves 开 `open-fakeplayer-inventory: true`）：
    A 召唤假人后空手右键它 → 能打开；换 B 空手右键同一个假人 → 打不开并收到提示；
    OP 右键 → 能打开（`botmanager.inventory.others`）

    > ✅ **2026-09-15 实测通过**（A=`bmba`，B=`bmbb`）：B 传送到 A 的假人旁边、
    > 看向它、空手右键 → **窗口没有打开**（`bot.currentWindow` 仍为 `null`）；
    > 换成右键**自己**的假人 → **窗口打开了**。
    > 两次用的是同一段代码、同一个假人类型、同一把"空手"，
    > 唯一变量是右键者和假人主人是不是同一个人。
    > （判据用客户端窗口状态，因为这是"事件有没有被取消"在玩家侧的直接表现。）
17. **动作控制**：`/bm action 1 start rotation` → 假人转向你看的方向；
    `/bm action 1 list` → 能看到刚挂上的动作；`/bm action 1 stop all` → 停掉。
    （这条同时验证 `.` 没卡住 Brigadier 的参数解析）
18. **动作归属**：B 执行 `/bm action 1 start break` 时，只会去找 `B.1`；
    若 B 没有编号 1 的假人会提示「不在线」，**绝不会操作到 A 的假人**

    > ✅ **2026-09-15 实测通过**（A=`bmba`，B=`bmbb`，两边各开 1 号假人）：
    > A 执行 `/bm action 1 start break` 后，A 的 `list` 里**只有 `bmba.1`**，
    > **没有** `bmbb.1`；反过来 B 挂 `rotation` 之后，
    > `bmbb.1` 有 `rotation`，而 `bmba.1` 仍然只有 `break`、**没被带上**。
    >
    > ⚠️ 这条第一轮验错了：当时拿 `/bot list` 去查"动作挂上没挂上"——
    > `/bot list` 只列**假人名**，根本不显示动作，所以判据是空的（报了 3 个假失败）。
    > 要验动作得用 `/bm action <编号> list` 的回执。
19. **命名空间隔离**：
    - `/bm status` 的「假人命名空间」显示**已隔离**、「死亡号位回收」显示**已生效**
    - 启动日志出现「假人命名空间已隔离：假人名形如 &lt;玩家名&gt;.&lt;编号&gt;」
    - **拿一个客户端把用户名改成 `&lt;你&gt;.1` 登录 → 连客户端都不该让你输入**；
      若用离线启动器硬发这个名字，服务端应报「Invalid characters in username」拒绝
    - 给假人授权 `/res pset &lt;领地&gt; &lt;你&gt;.1 build true`，确认权限能正常授予

    > ❌ **2026-09-15 实测：后两条在离线服上是错的。** 详见 §8.5 的
    > 「离线服实测：隔离不成立」。mineflayer 用 `auth: 'offline'`、
    > 用户名直接写 `bmca.3`，**服务端照单全收**，没有任何
    > 「Invalid characters in username」。
    > 名字规则是**客户端**的输入框在管，服务端（离线模式）不校验。
    >
    > ✅ **已补上登录闸口**（§8.5「解法：登录闸口」）：现在这种名字会被
    > `AsyncPlayerPreLoginEvent` 直接拒掉。实测 `bmka.3` / `bmka.03` 被拒，
    > `.Bedrock`（基岩版形状）/ `bmka.abc` / `bmka_1` 正常放行，14 项断言全过。
20. **兜底逻辑**（只在把 `LeavesBridge.SEPARATOR` 改回 `_` 后才需要测）：
    A 造出 `A.1` 形状的假人，往它背包里塞几块钻石；
    另开一个真名和它相同的账号登录 →
    - `bot.name-conflict: protect`（默认）：玩家正常进服，假人消失，
      **地上不该有任何掉落物**；控制台出现「与在线假人同名」警告；A 重新登录后假人连同钻石原样回来
    - 改成 `deny` 后 `/bm reload` 重复：抢名玩家被踢，A 的假人和钻石**原封不动**
    - 两种情况下，抢名玩家到该假人有权限的领地破坏 → **都应被拒绝**
      （假人 UUID 是 `Fakeplayer:` 前缀，与玩家 UUID 不同，见 8.5）

### 10.3 图形菜单（2026-09-15 真机实测）

**这批不是看代码推的**，是拿 mineflayer 当客户端连上真机，把菜单一格一格点过来的，
每一步都对着窗口里的**实际物品**和 `bots.yml` 的**实际状态**核对。38 项断言全过。

环境：Leaves 1.21.8 + Residence 6.0.1.4 + CMILib + Vault 2.5.0（无经济插件，
所以 `price-per-slot` 用默认的 `0`）。测试账号 `bmprobe3`，`bot.default-limit: 0`。

| 验的东西 | 实测结果 |
|---|---|
| 上限 0 | 容器 **9 格**，0 号格 = 金锭「购买号位 #1」，1-8 号格全空 |
| 点购买 | 上限 0→1；0 号格变**淡绿色染料**「可用假人 #1」、1 号格变金锭「购买号位 #2」、2 号格空 |
| 点可用假人 | 进 **27 格**小面板，11 号格 = 草方块「生成假人」、15 号格 = 箭「返回」 |
| 小面板的隔离 | 小面板 0 号格、4 号格**都是空的**——确认它不是完整面板 |
| 点返回 | 回到 **9 格**主菜单，0 号格仍是「可用假人」 |
| 点生成假人 | 假人 `bmprobe3.1` **真的上线了**（在 `/list` 里能看到），界面自动回主菜单 |
| 生成后那一格 | 0 号格变**玩家头颅**「假人 #1」，确认「可用」→「实例」的绑定生效 |
| 点实例 | 进 **27 格**完整面板：4 号格信息头、11 灰染料（已在线）、13 红石火把、15 末影珍珠、22 箭 |
| 点收回 | 假人**下线了**；重开主菜单，0 号格**还是玩家头颅** |
| 跨行增长 | 连买到上限 9 时容器从 **9 格长成 18 格** |
| 跨行后还能不能点 | **能**——重开出来的 18 格界面里点 9 号格，成功买到号位 10，10 号格随即变购买按钮 |

最后一格是关键：`refreshMain` 遇到跨行会走 `openMain`（延后一 tick 重开界面），
如果那样重开的界面点不动，玩家买完第 9 个号位就卡死了。**实测没卡**。

#### 防刷 / 防丢物品（2026-09-15 补测，40 项断言全过）

装了一个测试用的 Vault 经济插件（见下），把 `price-per-slot` 改成 `100`，
然后**用手搓的原始 `window_click` 包**把每一种点击手法都打在菜单上：

| 手法 | 协议 mode | 结果 |
|---|---|---|
| 右键购买按钮 | 0，button 1 | 不买、不扣钱 ✅ |
| Shift 左键购买按钮 | 1 | 不买、不扣钱 ✅ |
| 数字键（热键换位） | 2 | 不买、不扣钱 ✅ |
| 中键（复制方块） | 3 | 不买、不扣钱 ✅ |
| Q / 丢弃键 | 4 | 不买、不扣钱 ✅ |
| 双击（聚拢同类） | 6 | 不买、不扣钱 ✅ |
| 拖拽（三个包：`(-999,0)`→`(slot,1)`→`(-999,2)`） | 5 | 不买、不扣钱 ✅ |
| Shift 左键**自己背包** | 1 | 物品**不会**被移进菜单 ✅ |

反过来还要验"**展示品能不能偷走**"：菜单里那些金锭/染料/头颅都是真实物品，
对着它们把上面每一种手法再来一遍，**一件都拿不出来**；
全程用 `data get entity <玩家> Inventory` 在服务端核对，
钻石数从头到尾没变过（`diamond×1`），血量 20 → 20。

> ⚠️ **这一轮最初全是假通过，值得记一笔。** mineflayer 的 `bot.clickWindow` 对
> mode 5（拖拽）抛 `invalid operation`、对 mode 6（双击）抛 `unimplemented`——
> **包根本没发出去**，测试却"通过"了。同理，手搓 `window_click` 时
> `cursorItem` 必须写 `null`；写成 `{slot: -1}` 会让 protodef 抛
> `SizeOf error`，同样是一个包都发不出去。
> 所以这批测试**必须先做正控制**：先用原始包左键点一下购买按钮，
> 确认它**真的买成了**，才能说明后面那些"没买成"是拦截生效而不是包没发。

#### 付费路径（2026-09-15 补测，12 过 / 3 失败）

测试服加了 `Vault-2.5.0` + 一个自制的 `TestEco`（内存余额、可任意摆布、
每次扣款退款都打日志），`price-per-slot: 100`。
> 这一轮用的是自制经济，**后来换成 XConomy 真实经济重跑过一遍**（见本节的
> 「换成真实经济插件（XConomy）复测」）；下面这些结论仍然成立。

| 路径 | 实测结果 |
|---|---|
| 钱够 | 余额 `1000 → 900`（**正好扣 100**），上限 `0 → 1`，回执「已买下号位 1，花费 **$100.00**」，0 号格随即变「可用假人」✅ |
| 钱不够 | 余额 `50` 原地不动，上限不变，回执「余额不足，买下号位 2 需要 $100.00。」✅ |
| 购买按钮的 lore | 「价格：**$100.00**」——说明 `economy.format()` 走的真是经济插件的实现，不是兜底的 `%.2f` ✅ |

**那 3 条失败的全是退款分支，结论是：这个分支走不到。**

代码里是这么写的：

```java
if (!dispatched || registry.getLimit(id) <= before) { ... 退款 ... }
```

要触发它，得让 `Bukkit.dispatchCommand(console, "bm limit add ...")` 返回 `false`，
或者命令跑完了上限却没涨。实测两条路都堵死：

- **`ServerCommandEvent` 在 `dispatchCommand` 这条路上压根不触发。**
  一开始想用「取消 ServerCommandEvent」来制造失败，写了个监听器把
  **每一条** ServerCommandEvent 都打日志——结果**一条都没打出来**。
  `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ...)` 在 Paper/Leaves 1.21.8 上
  不发这个事件，插件**没有任何办法拦截或取消它**。
- **把 `bm` 从 `CommandMap` 里摘掉也没用。** 摘除是成功的（日志有记录），
  但 `dispatchCommand` 照样解析得到 `bm`——Paper 走的是 Brigadier，
  跟 `getKnownCommands()` 那张表不是一回事。
- `bm limit add <在线玩家> 1` 必然解析得到（玩家在线，`resolvePlayer` 走
  `getPlayerExact`），而 `setLimit` 没有失败路径。

所以那三行是**防御性代码，不是活的保险**。真要提意见的话：
它是 `if` 不是 `try/finally`，万一 `dispatchCommand` 抛异常，退款**不会**执行。
风险很低（上面已证明它不会返回 false），但改成 `try/finally` 更符合"钱一旦出去就必须有下文"的意图。

> ⚠️ 顺带踩到的一个反射坑：`Eco` 类一开始写成包私有，
> `VaultEconomy.format()` 反射调用时抛 `IllegalAccessException`，
> 被兜底成 `String.format("%.2f")`——界面上显示的是「花费 100.00」而不是「花费 $100.00」，
> **不报错、不崩，只是静默降级**。把类改成 `public static final` 之后，
> 界面和 lore 两处才都变成 `$100.00`。给别的经济插件做兼容时会踩到同一个坑。

#### 两个玩家同开 + 过期报价（2026-09-15 补测，22 项断言全过）

| 场景 | 实测结果 |
|---|---|
| 两人同时开着菜单 | A 点了买，只涨 A 的上限，**B 的上限和界面纹丝不动**；B 那边**没有**"已买下"提示 ✅ |
| 各自看到的编号 | A 的「可用假人」写着 `A.1`，B 的写着 `B.1`，互不串 ✅ |
| 一人开了假人 | B 的菜单里**看不到** `A.1` 的任何影子 ✅ |
| **菜单开着时上限被管理员调高** | 点那个**过期的购买按钮**：上限仍是 3（没被多加）、**一分钱没扣**、没有"已买下"提示；界面自己刷新到了新上限 ✅ |
| **菜单开着时上限被调低** | 点已经不属于自己的号位：上限不变、原来那格已经空了 ✅ |
| 收尾 | 假人还能正常收回 ✅ |

过期报价那一格是靠 `buy()` 开头的
`if (before + 1 != index) { refreshMain(player); return; }` 挡下来的——
点下去不报错、不扣钱，直接把界面刷成最新的，体验上是对的。

#### 操作面板：动作按钮 + 移除确认（2026-09-15 补测，42 项断言全过）

测试脚本 `paneltest.js`（mineflayer 玩家客户端 + RCON 核真值）。
**界面上写的"运行中"是我们自己画的，所以每一步都拿服务端的 `/bot action <假人> list`
对照**——界面说启动了，不等于假人真的动了。

| 场景 | 实测结果 |
|---|---|
| 面板结构 | 传送按钮已移除、15 格换成 TNT「移除假人」、六个动作键 + 停止全部都在对号格子里 ✅ |
| 长按左键 | 点一下 → 服务端出现 `["attack"]`、按钮变染料「运行中」；再点一下 → `[]`、回到「未启用」 ✅ |
| 间歇右键 + 聊天输入 | 敲 `20` → 服务端读回间隔 20、界面写「每 20 tick 一次」，**这个数字没进公屏** ✅ |
| 同名互斥 | 从「间歇左键」切到「长按左键」后服务端仍只有**一个** `attack`，亮的是新按钮 ✅ |
| 非法输入 | `abc` / `0` 被拒并提示重输，**没有起动作**；接着敲 `7` 能接上（不用重新点菜单） ✅ |
| 停止所有操作 | 3 个动作在跑时按钮 lore 逐个列出（`attack(间隔7) + sneak + jump(间隔10)`），点一下全清 ✅ |
| 移除确认 | 点移除先进确认窗口；点取消假人还在；点确认假人真的没了、**背包里的钻石掉到了地上** ✅ |

> ⚠️ **表里「长按左键」和「同名互斥」两行记的是改动前的版本**：那时「长按左键」挂的是
> `attack`，跟「间歇左键」共用同一个动作，所以才会互斥。2026-09-15 已把该格换成
> `break`（见 8.9「左键四格」一节），互斥搬到了 `break` 上。
> **「间歇右键 + 聊天输入」那一行也是旧通道**：那轮确实是"敲 `20`"，但同一天晚些时候
> 就换成了原生对话框（见 [10.5](#105-聊天输入换成了原生对话框2026-09-15-真机定位并修复)），
> 现在不存在任何"往聊天栏敲数字"的入口。
> 这张表保持原样是因为它如实记录了当时观测到什么，不是现行行为的说明。

**间隔真的传下去了吗——怎么量的。** 这条最难验：面板上写的间隔是插件自己打印的，
要证明它真的落到 Leaves 的动作上，得看假人的**物理行为**。最后用的是「间歇右键」+
16 个鸡蛋：右键真的会把鸡蛋扔出去并消耗掉，数消耗量就等于数执行次数。
实测 3 秒内 `interval=20` 扔 5 个、`interval=5` 扔 16 个（撞到鸡蛋额度上限），差距足够下判断。

测试路上有三个坑，全都会让"间隔没生效"的假象出现：

- **`attack` 打空气什么都不做。** `ServerAttackAction.doTick` 要求视线里真的有一个
  可攻击实体，没命中就直接 `return false`——**连挥手动画都不发**。所以"数挥手次数"
  这条观测路走不通，抓包确认过：一个 `entity_animation` 数据包都没有。
- **`/clear` 在没匹配到物品时输出 `No items were found on player bmpanel.1`**，
  用 `/\b(\d+)\b/` 兜底会匹配到玩家名末尾那个 `1`，**稳定地数出 1 个来**。
  得用 `/Found\s+(\d+)\s+matching/`，匹配不上就是 0。
- **投掷物都是 16 一叠**（雪球、鸡蛋一样）。`item replace … with minecraft:egg 64`
  会被 `can only stack up to 16` 顶回来，假人手上空空如也，右键等于没按。
  而且必须用 `item replace … hotbar.0` 指定槽位——`/give` 只找"第一个空格"，
  主手会被别的东西占着。

> **踩出来的一个真 bug：Leaves 的 `addBotAction` 是纯追加、不去重的。**
> 早期版本从「间歇左键」切到「长按左键」时，新动作确实是加了，但旧的 `attack`
> **没被停掉**——服务端读出 `["attack","attack"]`，假人每 tick 打两次。
> 修法是启动任何动作前先按动作名清一遍（`LeavesBridge#stopActionsNamed`），
> 在 "直接起动作" 和 "等玩家输完间隔" 两条路上都要清。

#### 「点按一次」两个新按钮（2026-09-15 补测，33 项断言全过）

测试脚本 `clicktest.js`。分两块：**界面/协议**（1~4 节）和**行为**（5~6 节）。
界面那半是简单的——材质、lore、点亮状态都读得到；难的是证明「点一下真的只打一下」
「点一下真的放得下方块」——**回执只能说明动作起了，说明不了这个动作干成了没有**。

| 场景 | 实测结果 |
|---|---|
| 两格画出来 | 20 是木剑「点按左键」，22 是草方块「点按右键」，长按两格没被挤掉 ✅ |
| 点 20 起的是 `attack` | 回执 `Action attack has been issued`，服务端列表出现 `attack` ✅ |
| 点 22 起的是 `use_auto` | 列表出现的是 `use_auto`，40 和 31 两格都没跟着亮 ✅ |
| **同名不同档不串台** | 20 亮着时 29（间歇左键）**不亮**；换成 29 后亮的只有 29 ✅ |
| 行为：只打一下 | 站到假人正前方，点 20 → **血量 20 → 19，只掉 1 次**，动作随之消失 ✅ |
| 行为：是 1 点伤 | 空手 1 点，读数准确 ✅ |
| 行为：击退印证 | 同时被推出去 1.99 格——不经过血量这条路，回血也抹不掉 ✅ |
| **行为：放得下方块** | 假人手持 64 石头、朝下看着一格空气，点 22 → **那格变成石头、手里 64 → 63**、动作自己收了 ✅ |

> **槽位是 20 / 22，不是最早那版的 19 / 21。** 这张表 2026-09-15 当天先按 19/21 测的，
> 同一天晚些时候面板按鼠标键重排了两列（见 8.9「操作面板」），两格各右移一位。
> **重排之后这套断言又原样跑了一遍，39 项全过**（多了 1b 节专门钉列位置）——
> 行为一个字没变，动的只是格子编号。

> **22 格改过一次动作名，是用户当场试出来的。** 最早挂的是 `use`——按"点按右键 =
> 用一次手上的东西"的字面理解选的，看着很合理。用户上手一点发现**放不了方块**。
> 查下来 `use` 和 `use_auto` 是包含关系（见 [8.9 右键那一列](#右键一列同是-use_auto)），
> 已改成 `use_auto 0 0 1`，图标也从雪球换成了草方块。
>
> 教训跟 10.4 那次是同一个：**"名字看着对"不等于"行为对"**。这次好在有真机——
> 摆一块石头让它放，放没放下去一眼就知道。要是只在代码里推，`use` 这个名字
> 会一直看起来是对的。

> **这一节被自然回血骗了整整三轮。** 假人空手打是 **1 点**伤害，打完之后自然回血
> 几秒就补满，血量读数回到 20——跟"压根没挨打"**长得一模一样**。中间一度拿盔甲架
> 当靶子（想避开回血），结果更难判：动作明明消失了（`ServerAttackAction.doTick`
> 命中才返回 true），盔甲架血量却纹丝不动，分不清是没打中还是打了别的实体。
>
> 最后是**关掉 `gamerule naturalRegeneration`** 才读准的。同时加了两条互相独立的
> 观测：血量掉多少、人被推出去几格。**只靠一条读数下结论，就会像上面那样得出
> "按钮没生效"的错误结论。**
>
> 测试服是 `difficulty=peaceful`，这让回血比平时更凶（和平模式本身就每秒回 1 点），
> 1 点伤害转眼就没了。同一个设置还顺手坑了另一条路：`summon zombie` 会回一句
> `Summoned new Zombie`，但和平模式当场把它删掉，紧接着的选择器只报
> `No entity was found`——**"召唤成功"和"它还在"是两件事**。

> **同名词是这把尺子的另一个坑。** 19 和 29 都叫 `attack`。只按名字+间隔判亮的话，
> 19 格的间隔是 0，落进"间隔 ≠ 1"那一档，会跟 29 格一起点亮——**界面上说两个按钮
> 都开着，实际只有一个动作**。所以 `RunningAction` 多了 `doNumber` 这一维。
> 注意 `-1` 是合法值（不限次），占位符得用别的（`Integer.MIN_VALUE` + `numberKnown()`）。

#### 购买上限 + 五档价格 + lore 排版（2026-09-15 补测，46 项断言全过）

两个脚本：`shoptest.js`（38 项）验价格、上限、排版、移除说明；
`captest.js`（8 项）验管理员改 `menu.max-slots` 之后的一致性。
这一轮经济用的是测试专用的内存插件（`/testeco set` 直接摆布余额），不用真去赚钱。
**下一节的 XConomy 复测把这两套换成真实经济重跑了一遍**（38 → 40 项），见下。

| 场景 | 实测结果 |
|---|---|
| 五档价格 | 5万 / 10万 / 20万 / 30万 / 50万 逐档核对，**界面报价 == 实扣 == 预期**，余额精确递减 ✅ |
| 买满 5 个 | 第 6 格从金锭变成屏障「已达购买上限」，写着"最多 5 个号位" ✅ |
| 点那个牌子 | **不扣钱、上限不变**，回一句「号位最多买到第 5 个，你已经到顶了」✅ |
| 移除假人 | lore 写明「遣散」「上限也不会减少」「会变回可用假人」，确认窗口和 `/bm remove` 的回执里也都写了 ✅ |
| **移除后上限真的没掉** | 真移除一个，回来数还是 5 个号位格，第 1 格变回「可用假人」，**余额一分没动** ✅ |
| 排版字符 | `▬` `▶` `●` `○` 都原样到了客户端（协议是 UTF-8，不会被吞）✅ |
| `max-slots` 5→3 | 老玩家**已买的 5 个一个都没少**，只是购买按钮变牌子、写着新上限 3 ✅ |
| `max-slots` 3→8 | 又能买了；第 6 档按表尾 50 万续算（`growth: 1.0` 不涨）✅ |
| 改回 5 | 第 6 个又买不了——判据是 `next > max-slots`，跟"配置改没改回来"无关 ✅ |

> 最后那条一开始被写成了"改回去就该又能买"，跑出来是 FAIL。**是断言错了不是代码错了**：
> 玩家手上已经有 5 个号位，第 6 个本来就该被 `max-slots: 5` 挡住。
> 值得记一笔是因为这类"测试自己写错了"和"真 bug"在输出上长得一模一样，
> 判定标准只能是：先把预期行为推一遍，再决定是改代码还是改断言。

> ⚠️ **一个没验到的点：字符到底长什么样。** 自动化只能证明 `▬ ▶ ● ○` 原样送到了客户端，
> 证明不了原版字体把它们渲染成了什么样。这些字符落在 BMP 的 Geometric Shapes /
> Box Drawing 区，原版自带的 `unicode_page_*.png` 覆盖得到，但那一套是 8×8 字形，
> 比默认字体的字形略小。真觉得难看的话，换成 `-` `>` `*` 这类纯 ASCII 是最稳的退路。

#### 换成真实经济插件（XConomy）复测（2026-09-15，99 项断言全过）

**起因**：上面几轮的付费测试全跑在自制测试经济上。但服务器真正要用的经济插件是
**XConomy**（`XConomy-Paper-2.26.3.jar`，SQLite 存余额）——也就是说，
"扣款走的是 Vault 反射"这件事虽然验过，**扣的却不是玩家钱包里那笔钱**。

**查出来的原因**：两个经济插件同时装在测试服上，测试经济以
`ServicePriority.Highest` 注册，而 `ServicesManager` 只交出优先级最高的那一个，
XConomy 全程被盖住。实测证据——同一时刻两个后端各记各的账：

```
买前：TestEco $1,000,000.00   XConomy $0.00
点下"购买号位 #1"（5 万）
买后：TestEco   $950,000.00   XConomy $0.00     ← 钱只从假经济里扣
```

**处理**：测试经济挪到 `testserver/plugins-disabled/`（改名不删，随时挪回来），
重启后 `购买走 XConomy（Vault 里优先级最高的那个经济插件）` 出现在日志里。

**顺带修掉的两处**（都是这轮才暴露的）：

| 问题 | 说明 |
|---|---|
| 同一格 lore 里两种货币写法 | 「价格」行走 `Economy#format()`（`50,000.00 dollars`），而"下个多少钱"那行自己拼中文数量级（`10.0万`）。并排看着像两个系统，而且"万/亿"是写死的，换个货币单位就不对味 → **预告行改为同样走 `format()`，且只预告一档**（三档摞一行会撑爆）✅ |
| 看不出扣的是谁的钱 | 新增开服日志与 `/bm status` 各报一行经济后端名字 ✅ |

**复测结果**（余额全部读写 XConomy 真实账户，`/money set` 摆布、`/money <玩家>` 核对）：

| 脚本 | 项数 | 结果 |
|---|---|---|
| `shoptest.js` | 40 | 全过。五档 5/10/20/30/50 万逐档核对，**余额 200万 → 85万，正好扣 115 万**；界面报价与实扣一致 |
| `captest.js` | 8 | 全过。`max-slots` 5→3→8→5 与真实经济无关，无回归 |
| `paneltest.js` | 42 | 全过。面板与动作不受经济后端影响 |
| `poortest.js`（新增） | 9 | 全过。只给 6 万：买下第 1 档剩 1 万；第 2 档要 10 万 → **余额一分没动、没扣成负数、号位没白送**，提示「余额不足，买下号位 2 需要 100,000.00 dollars」 |

> `poortest` 头一次跑出 3 个 FAIL，是断言把 `bm limit set 0` 之后的号位数当成 1 了
> （实际是 0）。又是"测试自己写错"，跟代码无关。

> ⚠️ **一个仍然没验到的点：自动退款那段**。扣款成功、但号位没开成时会调
> `depositPlayer` 把钱退回去（见 8.9）。这条路径要靠经济插件"拒绝给号位"才触发，
> 之前是用测试经济的注入开关（`failgrant`）造出来的；现在测试经济已让位，
> **在 XConomy 上没能自然复现，这一版没有实测覆盖**。
> 代码路径本身没改（仍是同一个 Vault 反射桥），但"XConomy 的 `depositPlayer`
> 对离线玩家是否照常成功"这件事，目前只有代码推理、没有真机证据。
> 需要覆盖时把 `plugins-disabled/TestEco.jar` 挪回 `plugins/` 重启即可复现注入。

#### 挖方块按钮 + `/bm recall`（2026-09-15 补测，48 项断言全过）

两个脚本：`breaktest.js`（24 项）验「挖方块」按钮**和左键那两格的分工**；
`recalltest.js`（24 项，含新补的多假人分支）验 `/bm recall` 命令。

**左键那两格为什么是这么分的。** 起因是「长按左键」原本挂 `attack`，用户判断这从
一开始就对不上——**游戏里长按左键做的就是"挖"，点按左键才是"打"**。真机把这件事
钉死了：假人 `tp` 到固定坐标、朝向正对一块石头（`yaw=0`，方块摆在视线正前方 2 格、
眼睛高度那一层），`attack` 跑满 4 秒**那块石头纹丝不动**（它在视线里没有可攻击实体时
什么都不做，连挥手动画都不发）；而 `break` 在同一个位置第 6 秒挖穿。
所以 2026-09-15 把「长按左键」换成了 `break`，「间歇左键」保持 `attack`。

| 验的东西 | 实测结果 |
|---|---|
| 长按左键换成了 `break` | 图标是**钻石镐**、动作是 `break`、lore 写"对着哪儿就一直挖穿它" ✅ |
| 长按左键真能挖穿 | 视线正对石头，**第 7 秒挖穿**（改之前同一格跑 4 秒纹丝不动）✅ |
| 间歇左键仍是 `attack` | 29 格图标仍是铁剑；跑满 4 秒方块仍是 `stone`（**点按打的是实体**）✅ |
| 两格能同时跑 | 服务端同时有 `break` 和 `attack`——不同动作，本就不该互斥 ✅ |
| `break` 的两格互斥 | 从「挖方块」(间隔 7) 切到「长按左键」(间隔 1)，服务端只剩**一个** `break` ✅ |
| 「挖方块」那一格 | `diamond_pickaxe / 挖方块`，lore 写明"挖它视线正前方 5 格内的方块" ✅ |
| 点它先问间隔 | 回「给「挖方块」设一个间隔，单位是 tick（20 tick = 1 秒）」✅ |
| `do_number = -1` 真是无限次 | 摆一排 **3 个**石头，**全部逐个挖穿**（4.5s / 12.1s / 19.6s）✅ |
| 间隔原样传给了 Leaves | 间隔 1：相邻两块差 **7.56 秒**；间隔 60：差 **10.58 秒**。差值 **3.02 秒**，而 59 tick 的理论值是 **2.95 秒** ✅ |
| 亮起 / 能停 | 跑起来按钮变 `lime_dye`、lore 写「状态 ● 运行中」；再点一次提示「已停止「挖方块」」，之后 4 秒没再碰新方块 ✅ |
| `/bm recall` 无编号 + 1 个在线 | 自动选中并收回，回执点明"背包和坐标已存档" ✅ |
| 收回后东西原样回来 | 塞 3 颗钻石 → recall → 再 spawn，**3 颗还在** ✅ |
| 对照：`remove` 是真删 | 同样塞 5 颗 → remove → 再 spawn，**0 颗**（东西掉在原地了）✅ |
| 对离线假人 recall | 回「不在线，不用收回」，不是报错 ✅ |
| 2 个在线时不写编号 | **拒绝猜**，回「你有 2 个假人在线，得点名」，并列出「在线的编号：1、2」✅ |
| 拒绝之后不乱收 | 两个都还在 ✅ |
| 点名编号 | 只收指定的那个（1 号在线、2 号下线）✅ |
| 收到只剩 1 个 | 再不写编号又能自动选中 ✅ |

> **槽位同样是重排前的。** 这张表测的时候「长按左键」在 20、「挖方块」在 38；
> 当天晚些时候面板按鼠标键分列，两格分别挪到了 **38** 和 **47**（见 8.9「操作面板」）。
> 表里凡是点名的格子编号都换成了描述性说法，**断言本身一条没改**——
> 挖不挖得穿、间隔有没有生效，这些跟格子在哪儿无关。

> **踩出来的坑：`bot list` 的名字后面带逗号。** 输出是
> `world(2): bmrecall.1, bmrecall.2`，按空白切开得到的是 `"bmrecall.1,"`——
> **只有列表里最后一个才不带逗号**。于是"第一个假人查不到"，
> 表现成一堆莫名其妙的间歇性失败（`1号=不在 2号=在线`，几步之后它又在线了）。
> 先把逗号换成空格再切就好了。**这是测试脚本的 bug，和插件无关**——
> 同一轮里插件的回执自始至终是对的。

> **另一个坑：假人会在主人登录时自动上线。** 前置清理如果只 `remove` 一次，
> 可能被这次自动上线抢先，导致"前置很干净"的假象；而且上一轮留下的 **2 号**
> 也在，只清 1 号会漏。清理要**循环到真干净**、并且**清所有编号**。

### 10.4 假人头像把服务器 IP 问进了限流（2026-09-15 真机定位并修复）

**用户报的现象：** 服务器后台反复刷

```
WARN]: Couldn't look up profile properties for a2fb754d-7bcb-3206-9ba0-82ac34fe521f
com.mojang.authlib.exceptions.MinecraftClientHttpException: Status: 429
    at com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService.fetchProfileUncached(...)
    at com.destroystokyo.paper.profile.PaperMinecraftSessionService.fetchProfile(...)
    at org.bukkit.craftbukkit.profile.CraftPlayerProfile.getUpdatedProfile(...)
    at java.util.concurrent.CompletableFuture$AsyncSupply.run(...)
```

**根因（在 BotMenu 里，不在别的插件）：** 菜单给每个假人头调了
`skull.setOwningPlayer(bot)`。Paper 把物品写进容器时会跑
`CraftMetaSkull.applyToItem`，反编译出来是这个形状：

```java
if (hasOwner()) {
    applicator.put(SKULL_PROFILE, this.profile);
    CraftPlayerProfile p = new CraftPlayerProfile(this.profile);
    if (p.getTextures().isEmpty()) {          // ← 这颗头没有皮肤
        p.update()...                          // ← 异步去 Mojang 查一次
    }
}
```

假人的档案里**永远没有皮肤**，于是这个判断恒为真：**每开一次菜单 × 每个假人头
= 一次 Mojang 查询**。而假人 UUID 在 Mojang 根本不存在（`204`），
失败结果不进缓存，于是下次开菜单**再查一遍** —— 几十个假人、点几次菜单，
服务器 IP 就被限流了 429。

**排查中的两个教训：**

| 教训 | 说明 |
| --- | --- |
| **"日志没有"≠"没发生"** | 测试服日志里 `Couldn't look up profile` 出现 **0 次**，据此曾误判"不是我们"。实际是测试服连得上 Mojang、拿到 `204` 属正常返回**不抛异常所以不打日志**——请求一直在发。 |
| **栈里没有自己的帧，不等于与自己无关** | 触发链是"谁的物品 → 服务端自己的钩子 → 联网"，调用方在栈里出现不了。判定得从**物品是谁造的**入手。 |

**排查用的工具（`mfbot/proftest.js`）：** 用 mineflayer 打开菜单，直接读头颅物品的
`profile` 组件。修复前：

```
槽位 0: player_head 「假人 #1  bmprof.1」
    带 profile 档案（owner）: 是
    档案内容: {"name":"bmprof.1","uuid":"15931c02-767f-300d-b20c-2a892eecdc89","properties":[]}
                                                                           ^^^^^^^^^^^^^^^^ 空的
```

UUID 第三段 `300d` 是 **v3**（和报错的 `...-3206-...` 同型），`properties` 为空 ——
正好落进上面那个 `getTextures().isEmpty()` 分支。修复后同一处：

```
    带 profile 档案（owner）: 否        ← hasOwner() 为假，整个分支都不进
```

**修复：** 两处 `setOwningPlayer` 全部去掉（假人仓库列表、面板顶上那颗头、
以及移除确认窗），`buildBotHead` 的 `bot` 参数随之删掉。因为假人本来就没有皮肤，
**有 owner 也是显示默认皮肤，去掉不少任何视觉信息**——菜单里在线/离线是靠名字颜色
（`AQUA`/`GRAY`）区分的，不靠头像。

| 验证项 | 结果 |
| --- | --- |
| 修复前：头颅带 owner 且 `properties` 为空 | ✅（就是查询的来源） |
| 修复后：头颅不带 owner | ✅ |
| 菜单面板回归 `paneltest.js` | **43 / 43** ✅（那一轮的用例数；现在这套涨到 52，见 10.6） |

> **注意 UUID 版本号不能用来分辨"假人 vs 离线玩家"。** 假人是
> `UUID.nameUUIDFromBytes("Fakeplayer:" + 全名)`，离线玩家是
> `"OfflinePlayer:" + 名字`，**两者都是 v3**。要确认某个 UUID 是不是假人，
> 去 `world/fakeplayerdata/<uuid>.dat` 看文件在不在，比看版本号靠谱。

### 10.5 聊天输入换成了原生对话框（2026-09-15 真机定位并修复）

**用户报的现象：**「聊天框输入间隔的操作，在测试服里可行，在目标服务器里只会直接输出内容。」
用户把 SimpleChat 放进测试服之后**当场复现**——这条报告的价值就在这儿：不用去猜目标服的差异。

#### 第一层：`ignoreCancelled = true` 让监听器整个不执行

`ChatPrompt#onChat` 原本注册在 `AsyncChatEvent` 上，带 `ignoreCancelled = true`。
而 SimpleChat 监听的是**老的** `AsyncPlayerChatEvent`（`ChatListener#onPlayerChat`）。
Paper 为了兼容这类老插件，会在派发 `AsyncChatEvent` **之前**先把它标成 cancelled，
交给 legacy 路径去广播——于是那句 `ignoreCancelled` 让我们的监听器**根本不被调用**，
提示挂在那儿永远等不到答案。

调试日志实锤（`接住 X 的输入，进来时 isCancelled=true`）：

| | 现象 |
|---|---|
| 改之前 | 监听器不执行 → 动作列表空 `""` → 玩家看到数字被打成公屏 |
| 去掉 `ignoreCancelled` 之后 | 动作列表 `0 break` → **功能恢复**，但数字照样上公屏 |

#### 第二层：公屏拦不住，这条路本身是死的

去掉 `ignoreCancelled` 只修好了"功能"。SimpleChat 的 `ChatListener#onPlayerChat`
字节码里**一次 `isCancelled()` 都没有**——它压根不看事件状态，无条件格式化并广播。
所以 `event.setCancelled(true)` 对它完全无效。

试过补一个 legacy 监听器抢在它前面取消（我们挂 `LOWEST`、它默认 `NORMAL`，确定跑在它前面），
实测**公屏上照样出现** `[Lv.0] [world] <玩家> 1`。已回退，不留一段自己声称能做某事、
实际做不到的代码。

> **只要服上有"不看取消状态"的聊天插件，聊天输入就一定会进公屏。事件层面无解。**
> 于是换通道，不再经过聊天事件。

#### 做法：`DialogPrompt`（Paper Dialog API）

`ChatPrompt` 整个删掉，换成 `DialogPrompt`——用 Minecraft 1.21.6+ 的原生对话框收输入。
Leaves 1.21.8 带这套 API（`io.papermc.paper.dialog`，jar 里有 `PaperDialog` 和完整的
原版 dialog 数据包）。对话框长这样：

- 标题「设置间隔」，正文是那句问题（第一次是「给「挖方块」设一个间隔……」）。
- 一个文本框：label「间隔（tick，20 tick = 1 秒）」、初值 `20`、`max_length` 6。
- 两个按钮：「确定」（绿）/「取消」（灰）。Esc 和右上角的叉也能关。
- `afterAction = CLOSE`：点了就关，不留着转圈。

几个设计上的取舍：

| 取舍 | 原因 |
| --- | --- |
| 类里**一个字段都不存** | 老实现有个 `waiting` map + 30 秒超时 + `onQuit` 清理。对话框把回调闭包在 `Dialog` 对象上，玩家下线/走开/把框晾着都不用管，那三样整个删了。 |
| `Handler#accept` 返回 `String` 而不是 `boolean` | `null` = 收下；非 null = **不合法的原因**。原因是拿来画在对话框正文里的，比原来 `sendMessage` 到聊天栏贴切。 |
| 输错了**原地重弹** | 不退出去重新点菜单。正文会变成两行：问题 + 红字原因。 |
| handler 主体仍包在 `runTask` 里 | 自定义点击回调本来就在主线程上，但多绕这一跳只花一个 tick，换来的是不必赌它的线程模型。 |
| 老实现里的 `cancel` / `30 秒自动作废` 都没了 | 对话框天生就有取消（Esc / 叉 / 取消按钮），超时是多余的。 |

需要 1.21.6+ 的客户端——这是换过来的代价。

#### 测试：mineflayer 点不动对话框，所以自己拼包

mineflayer 不认 1.21.6+ 的对话框 UI，没法"人工点确定"。做法是抓 `show_dialog` 报文，
把按钮里的回调 UUID 抠出来，再照着造一个 `custom_click_action` 发回去
（`tmp/mfbot/dialogclick.js`）。走的是和真人客户端**完全同一条**服务端处理路径
（`ServerboundCustomClickActionPacket` → `ClickCallbackProviderImpl$DialogClickManager`
→ 我们的 `DialogActionCallback`），所以"回调跑不跑得通、`getText` 读不读得到数"
是真验了；只有"原版客户端会不会把这个框画出来、按钮点不点得动"验不了，那部分得人眼。

拼这个包踩了两个坑，都值得记：

1. **minecraft-data 1.21.8 里这个包的定义是错的。** `protocol.json` 写的是
   `["option","anonymousNbt"]`（一个 bool 前缀），服务端实际是
   `ByteBufCodecs.optionalTagCodec(...).apply(lengthPrefixed(65536))`——
   **没有 bool，但外面套了一层 varint 长度前缀**。照协议文件编码，服务端 decode 直接失败，
   而且是断线级别的：`Failed to decode packet 'serverbound/minecraft:custom_click_action'`。
2. **`writeRaw` 要的是不含长度前缀的包体。** 长度前缀由 framer 自己加
   （`transforms/framing.js` 的 `Framer._transform`）。多加了前缀的症状很好认：
   服务端报 `Received unknown packet id 71`——那个 71 就是你算出来的总长度。

回到插件这边，**这条链路真的跑通了**：点「挖方块」→ 弹对话框 → 回填 `1` → 点确定
→ `break` 起来；换填 `34` 再走一遍，面板信息头写成 `break(间隔34)`，说明数确实到了 Leaves。

#### 顺带修掉一个假通过的断言

`paneltest.js` 里「聊天框里输的 20 没进公屏」这条**一直是假绿**。分桶逻辑是：

```js
if (/^<\w+>/.test(t)) chat.push(t); else sys.push(t)   // ← 只认原版格式
```

装 SimpleChat 后公屏消息长这样：`[Lv.0] [world] <bmpanel> 20`，开头是 `[`，
被判成系统消息丢进了 `sys`。断言在**空数组**上求值，**永远通过**——
而另开一个旁观客户端能实打实收到那条消息。

修法：新增一个收全部的 `all`，判"有没有进公屏"一律用 `spoke(name, text)` 查 `all`，
不再依赖格式分桶。修完之后这条**正确地变红**：

```
FAIL  聊天框里输的 20 没进公屏（被插件吃掉了）   <- ["[Lv.0] [world] <bmpanel> 20"]
```

换成对话框之后这条断言更硬了——整条链路压根不碰聊天，判据从"数字没被广播"升级成
**"公屏上一个字都没有"**（`!all.some(l => /<[A-Za-z0-9_.]+>/.test(l))`）。

#### 验证结果（2026-09-15，真机）

| 验证项 | 结果 |
| --- | --- |
| 点「挖方块」弹的是对话框不是聊天提示 | ✅ 标题/正文/输入项/按钮全对 |
| 回填 `1` 点确定 → `break` 起来 | ✅ `0 break` |
| 填进去的数真到了 Leaves | ✅ 填 `34` 后面板信息头写成 `break(间隔34)`；填 `1` 时不写（1 是默认值） |
| 数字有没有进公屏 | ✅ 旁观客户端**一条都没收到**，全程公屏为零 |
| 填 `abc` / `0` / `99999` | ✅ 原地重弹一个框，红字写明原因，动作不起 |
| 点「取消」/ Esc | ✅ 什么都不发生 |
| `dialogtest.js`（新） | **25 / 25** ✅ |
| `paneltest.js` | **52 / 52** ✅（2026-09-15 加说明书那 9 条断言后；40 条那轮是 43，见 10.4） |
| `clicktest.js` | **39 / 39** ✅ |
| `breaktest.js` | **24 / 24** ✅（那一轮；现为 25，见 10.6） |

> **假绿比红更危险。** 这条断言在装 SimpleChat 之前是**真的**通过（原版格式下分桶成立），
> 所以它一直看着可靠；换个服务端配置就变成了永远通过。**凡是 `!array.some(...)` 形态的否定断言，
> 都要先确认那个数组在"应该非空"的时候确实非空。**
>
> 同一天还踩到同一类问题的另一面（**假红**）：`paneltest.js` 的「掉落物变多」数的是
> **全世界**的 item 实体总数，上一节扔的雪球/鸡蛋还躺在地上，基线是多少全看运气——
> 实测过一次 `4 -> 4`，东西明明掉了，只是同时有别的在消失。改成按实体 id 差分
> （只看确认移除之后**新出现**的那些）反而又红了两次。真因是最后抓到的这一组数据：
> 改法上线后连跑两次，其中一次的诊断是
> **「全图掉落物 9 → 9」但「脚下新出现 1 个」**——
> 全图计数根本不是"稳的基线被别的东西污染"，它**本身就是两个方向同时在变的净值**：
> 钻石掉出来的同时，别处的旧掉落物正在被捡走或消失。拿净值判"有没有多出来"，
> 红绿全看运气。合并（新掉的叠被吸进同一个实体、id 不变数量变大）是同一类麻烦，
> 只是不是主因。
>
> 最后两个改动一起上：只看**假人脚下 4 格内**的掉落物（东西只掉在它脚下，别处的
> 都跟这条断言无关），并在点确认前把脚边的旧掉落物扫掉，让"新出现的"真的可判。
> 改完连跑 4 次全绿。
>
> 顺带确认了两件事：**掉落行为本身从来没问题**——绕开插件直接测原生路径
> （`/bot create` → `item replace` 塞 3 颗钻石 → `/bot remove`），1.2 秒后地上就
> 多出那叠钻石；而且点在确认之前那一刻，假人背包里**确实还揣着那 3 颗**
> （`data get entity` 打的诊断）。红的几次全是测法的问题。

### 10.6 面板右下角加了一张只读的说明书（2026-09-15 真机验证）

**用户要的：** 面板上再加一个没有点击事件的物品，只用 lore 说明
`/bm action <编号> start …` 和 `/bm action <编号> stop …` 这两条命令，
让玩家知道面板那几下之外还能更精细地调。

**为什么需要它：** 面板按钮能替玩家填的只有**间隔**这一个数——首次延迟恒 `0`、
次数恒 `-1`（见 8.9 的动作按钮表）。想"先等 3 秒再动手"或者"只做 20 次就收手"，
面板上根本没有入口，只能走命令。以前这条知识只存在于这份 README 里，
玩家看不到。

| 项 | 内容 |
| --- | --- |
| 位置 | 53 格（右下角，`BOT_BACK` 之后） |
| 物品 | `WRITABLE_BOOK`，名字「命令用法」 |
| 点击 | **没有事件**。点击在 `BotMenu` 的 `InventoryClickEvent` 里整片 `setCancelled(true)`，`onBotClick` 的 switch 也落不到它这一支，等于无事发生；拖拽同样被拦，拿不走 |
| 排版 | 没写 `hint()`——那句「▶ 左键 …」是给能点的格子用的，写了就是在骗玩家去点 |

**lore 里的字全部是真机验过的，不是照着印象写：**

- **三个数的顺序**：`start <操作> <首次延迟> <之后每次延迟> <次数>`。这条专门做了
  判别实验（`tmp/mfbot/delayprobe.js`）：给假人 16 个鸡蛋、跑
  `start use_auto 100 20 -1` 观察 15 秒——
  「100 是首次延迟」预言 11 个，「顺序反过来」预言 3 个，**实测 10 个**。
- **判据为什么用鸡蛋消耗量**：本来想给每个鸡蛋在地上的实体打时间戳，
  结果**投出去的鸡蛋落地会碎**（1/8 概率生成小鸡，其余直接消失），
  压根不会变成掉落物。只能靠 `/clear <假人> minecraft:egg 0` 回收一个计数
  （`maxCount=0` 是"只数不清"，样本还在）——所以整个实验只能测一个时间窗。
- **`stop` 收编号也收 `all`**：`javap` 从 `BotActionCommand` 的字符串常量里
  读到 `start` / `stop` / `list` / `all`，再在真机上跑通。
- **`stop <编号>` 之后编号会往前挪**：实测 `list` 从 `0 use_auto / 1 attack`
  变成 `0 attack`——编号是列表里的位置，不是身份证。这条最容易踩（连着停两个
  就会照着旧编号停错），所以写进 lore 了。

**验证（`paneltest.js`，2026-09-15，连跑两次都是 52 / 52）：**

| 断言 | 结果 |
| --- | --- |
| 53 格是「命令用法」、物品是书 | ✅ |
| lore 写全了 `start` 的每个参数位 | ✅ |
| lore 里有 `stop <动作编号>` 和 `stop all` | ✅ |
| lore 说明了单位是 tick、`-1` 是无限 | ✅ |
| lore 提醒了停完之后编号会往前挪 | ✅ |
| lore 里没有「▶ 左键」（它不可点） | ✅ |
| 点它：窗口没关也没换 | ✅ |
| 点它：书还在格子里（拿不走） | ✅ |

> 这次红的唯一一条是**测试自己的 bug**：`paneltest.js` 的 `slotOf()` 返回的字段叫
> `name`，我在新断言里写了 `id`（`breaktest.js` 里那个 helper 才叫 `id`），
> 于是拿到 `undefined` 判成"不是书"。两个测试文件同一件事两个写法，
> 照抄另一个文件就会踩——报错信息（`undefined / 命令用法`）里其实写得很清楚。

**加它之后的回归总数（2026-09-15，全部真机复跑）**：`paneltest` **52 / 52**（连跑两次）+
`breaktest` **25 / 25** + `clicktest` **39 / 39** + `dialogtest` **25 / 25** = **141 项断言，0 失败**。
（`breaktest` 从 24 涨到 25，是同一轮给「挖方块」的 lore 补了一条不许再出现「聊天框」。）

### 10.7 对话框的异常路径与安全边界（2026-09-15 真机验证）

用户看到点按钮后"进了一个自定义屏幕"——**那不是插件自绘的界面**，是原版对话框
（Paper 1.21.6+ 的 Dialog API）：插件只发一个 `show_dialog` 包，标题、文本框、
确定/取消按钮全是客户端原版渲染的。这一节回答"这样会不会出问题"。

`CraftPlayer#showDialog` 的字节码只有一道守卫——`connection != null`（玩家得在线），
**没有客户端版本检查**。所以边界在协议层：原版客户端连 1.21.8 服本来就必须同版本，
只有服上挂了 **ViaVersion** 让老客户端进来时才需要担心（那种客户端收到这个包会报未知包）。

| 场景 | 真机结果 |
| --- | --- |
| 框开着时**传送/换维度** | 换维度本身**不会**起动作（点之前 `bot action list` 是空的）；换维度后用换之前抓到的回调点，**仍然认**——点了照你填的数生效。两种都无害 |
| 框开着时**重登** | 旧回调**仍然认**（跨会话活着）。但真实客户端重登后不会再显示那个框，玩家点不到；就算点了，下去看下一行 |
| **别人拿到我的回调 UUID** | **动不了我的假人**（A 的假人 `[]`，B 只作用到了自己身上） |
| 服务端日志 | 无异常（`at com.botmanager` 0 条） |

**为什么跨玩家跨不过去（结构性原因，不是运气）：** 回调里做事的
`BotMenu#askInterval` 是用 `botOf(who, index)` 找假人的，而它的实现是
`BotService.rawName(who, index)` ——**假人名由"点击者"的名字推导**（`<点击者>.<编号>`）。
B 拿着 A 的 UUID 点，解析出来的是 `B.1`，寻址不到 `A.1`。跟 `/bm action` 是同一条保证。

> **这份设计为什么不脆：`DialogPrompt` 一个字段都不存。** 没有 `waiting` map、
> 没有超时计时器、没有 `onQuit` 清理——所以"下线/传送/超时/换维度"这些路径上
> **压根没有状态可以残留或错位**。回调闭包挂在 Dialog 对象上，出任何事最多是
> "框没了、重新点一次菜单"。老的 `ChatPrompt` 之所以脆，正是因为它有三样都要清理的状态。

测试脚本：`tmp/mfbot/dimtest.js`（换维度 / 重登）、`tmp/mfbot/sectest.js`（跨玩家重放）。
写它们时踩的坑也记一下，全是"测试自己错"而不是插件的错：
- **两个面板两套词**：小面板那格是「生成假人」，完整面板是「召唤假人」，
  而且**跑过一次之后必然变成后者**（`/bot remove` 只拔假人，`bots.yml` 里的号位登记还在）。
- **上一节点亮的按钮还挂着**，再点同一个按钮是"停"不是"问间隔"，于是根本不会弹框。
- 点完号位要等界面切过来，**别拿固定的 `sleep(700)` 赌**——等不到就再点一次。

#### 基岩版（Geyser）玩家能用吗？——能，Geyser 专门做了对话框翻译

2026-09-15 查的 Geyser 源码（`GeyserMC/Geyser` master）：

| 环节 | Geyser 里的对应实现 |
| --- | --- |
| 收到游戏内 `show_dialog` | `translator/protocol/java/dialogues/JavaShowDialogGameTranslator` → `DialogManager#openDialog`，**转成 Bedrock 的 Cumulus 表单** |
| confirmation 类型 | `session/dialog/ConfirmationDialog`（yes / no 两个按钮，Esc/叉 = 按 `no`，跟 Java 一致） |
| 文本框输入项 | `session/dialog/input/TextInput` → `CustomForm.Builder#input`（就是基岩版原生的输入框） |
| **输入框的值回传** | `session/dialog/action/DialogAction.DynamicCustom#run`：`inputs.asNbtMap()` 再 `putAll(additions)`，拼成 `ServerboundCustomClickActionPacket` 发回服务端 |

最后一行是关键，它决定"玩家填的数字能不能回来"。**能**，而且形状跟原版客户端发的一模一样：

- Paper 的 `DialogAction.customClick` 生成的按钮，动作类型是 **`minecraft:dynamic/custom`**（不是 `custom`）——
  这是我们自己抓 `show_dialog` 报文时实测到的：`{type:'minecraft:dynamic/custom', id:'paper:dialog_click_callback', additions:{id:[4 个 int]}}`。
- 原版客户端点下去时，会把**输入项的值并进 `additions`** 一起发回来（所以实测能收到 `{id: <UUID>, interval: "34"}`）。
- Geyser 的 `DynamicCustom` 做的正是同一件事：`inputs.asNbtMap()`（文本项 → `putString("interval", 值)`）+ `additions`。

所以插件侧收到的东西**来自基岩版和 Java 版没有任何区别**，`view.getText("interval")` 照样拿得到。
时间点也对得上：这个翻译器 **2025-06-01** 就有了（跟着 1.21.6 的对话框系统一起加的）。

**外观差别（功能不受影响）：** Java 是两个并排的按钮，Bedrock 那边变成「输入框 + 一个下拉选 确定/取消」的表单——
这是 Geyser 把 `DialogWithButtons` 统一映射成 `CustomForm` 的结果。

> ⚠️ **这条是读 Geyser 源码得出的，不是拿真基岩客户端跑出来的**——测试服压根没装 Geyser。
> 真要按"真机验证"的标准走，得在测试服装 Geyser（+ Floodgate 或 offline 模式），
> 用基岩版连 `19132` 点一次才知道。唯一需要留意的边界是 **Geyser 版本太老**（2025-06 之前不认识 `show_dialog`）。

#### 为什么不用牌子（sign）做输入

Geyser **也**支持牌子编辑（`JavaOpenSignEditorTranslator` → `OpenSignPacket`），所以牌子在基岩版这边不是拦路虎。
不用它的理由是**别的**：

1. **要把刚删掉的整套路又搬回来。** 对话框现在是**零状态**的（§10.5 / §10.7）。牌子输入必须有
   "这个玩家正在编辑牌子"的等待表 + 掉线清理 + 超时，还得在他眼前造/放一块真牌子（或发假方块包），
   人一走开就留下野牌子——正是 `ChatPrompt` 被换掉的那类脆点。
2. **跟 ServerSigns 直接冲突。** 这个服的插件列表里有 `ServerSigns-4.10.1.jar`，它就是盯着牌子编辑干活的。
3. **输入体验更差**：4 行 × 每行 15 字符，还得再点一次「完成」；而现在是一个数字框 + 确定。

**真到了不能用对话框的场合，兜底已经有了**：`/bm action <编号> start <操作> <首次延迟> <之后每次延迟> <次数>`——
聊天栏打命令在 Java 和基岩版都通，而且这句话就写在面板 53 格那本说明书里。
