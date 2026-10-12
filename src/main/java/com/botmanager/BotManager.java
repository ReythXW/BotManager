package com.botmanager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * BotManager —— Leaves 假人管理插件。
 *
 * <h2>领地保护（前置能力，已完成）</h2>
 * 修复的漏洞：{@code /bot} 生成的假人可以无视 Residence 保护、破坏他人建筑。
 *
 * <p>根因不在 Residence，而在 Leaves：假人的方块操作走的是
 * {@code ServerBotGameMode}，它覆写了原版逻辑并直接改写世界，
 * 把 Paper 注入的 Bukkit 事件（BlockBreakEvent / BlockPlaceEvent /
 * PlayerInteractEvent）全部跳过了。Residence 是事件驱动的保护插件，
 * 收不到事件自然也就无从拦截。
 *
 * <p>本插件在 Leaves 为每个 bot 动作派发的 {@code BotActionExecuteEvent}
 * （所有动作的必经入口，且可取消）里，把假人的动作重新翻译成对应的
 * Bukkit 事件，只交给 Residence 判定；被拒则取消该次动作。
 * 判定逻辑 100% 复用 Residence 自身实现，因此它的全部 flag 都会对假人生效。
 *
 * <h2>假人管理（当前）</h2>
 * 玩家用 {@code /bm spawn} 召唤属于自己的假人（{@code <玩家名>.<编号>}，编号按顺序自动分配），
 * 每人上限独立，假人随玩家上下线同步。归属与配额记录在 {@code bots.yml}，
 * 假人自身的背包/位置/血量靠 Leaves 的 manual 存档跨上下线还原。
 */
public final class BotManager extends JavaPlugin {

    private GuardSettings settings;
    private ResidenceHook hook;
    private ResidenceFlags flags;
    private ResidenceCache cache;
    private BotActionInterceptor interceptor;
    private Plugin residence;

    /** Leaves 的假人上线事件。用字符串引用，避免编译期依赖具体 Leaves 版本。 */
    private static final String BOT_JOIN_EVENT = "org.leavesmc.leaves.event.bot.BotJoinEvent";

    /** Leaves 的假人移除事件（同上，字符串引用）。 */
    private static final String BOT_REMOVE_EVENT = "org.leavesmc.leaves.event.bot.BotRemoveEvent";

    private LeavesBridge leaves;
    private BotRegistry registry;
    private BotService service;
    private BotInventoryGuard inventoryGuard;
    private BotNameGate nameGate;
    private AuthMeUnrestrict authme;
    private BotMenu menu;
    private DialogPrompt prompt;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = new GuardSettings(this);

        // Leaves 假人 API：管理功能的地基，拿不到说明服务端不是 Leaves，整个插件都没意义
        this.leaves = new LeavesBridge(this);
        if (!leaves.setup()) {
            getLogger().severe("无法连接 Leaves 的假人 API。");
            getLogger().severe("请确认服务端为 Leaves（本插件目标版本 1.21.8）。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Residence 是软依赖：没装只是不做领地保护，假人管理照常可用
        if (setupResidence()) {
            logCoverage();
        } else {
            getLogger().warning("未找到已启用的 Residence，领地保护不可用，仅假人管理生效。");
        }

        this.registry = new BotRegistry(this);
        this.service = new BotService(this, leaves, registry);
        getServer().getPluginManager().registerEvents(service, this);

        // 名字相关的两道闸，按命名空间隔离是否生效二选一：
        //   隔离生效   -> namespaceGuard：登录阶段直接拒绝「玩家名.编号」形状的名字
        //   隔离失效   -> loginFallback：真能注册到同名，只能事后补救（存档或踢人）
        // 两者都挂没有意义，而且 loginFallback 会触发 Paper 的
        // [HorriblePlayerLoginEventHack] 警告，能免则免。
        if (leaves.isNamespaced()) {
            getServer().getPluginManager().registerEvents(service.namespaceGuard(), this);
        } else {
            getServer().getPluginManager().registerEvents(service.loginFallback(), this);
            getLogger().warning("假人名里没有真人注册不到的字符，玩家可以抢注与假人同名的账号，"
                    + "已启用登录兜底保护（由 bot.name-conflict 决定放行还是拒绝）。");
        }

        // 假人被打死时 Leaves 不写存档也不通知插件，号位会一直被占着，得自己回收
        if (!service.registerDeathWatcher()) {
            getLogger().warning("接不上 Leaves 的假人移除事件：假人被打死后号位不会自动回收，"
                    + "需要玩家自己 /bm remove <编号> 清理。");
        }

        // AuthMe（软依赖）：把上线的假人写进它的 UnrestrictedName 白名单，否则假人
        // 在它眼里是「未登录玩家」而受保护——打不动。逻辑照搬独立插件 BotUnrestrict，
        // 只把挂载点从 BotCreateEvent 换成 BotJoinEvent（上下线还原走后者，见该类注释）。
        setupAuthMe();

        // 周期性自动存档。正常 /stop 由关服钩子兜底，这里管的是 kill -9 / 断电
        // 这类「没有任何代码有机会跑」的死法——那种情况下假人会回退到上次落盘的状态。
        service.startAutosave();

        // 假人背包归属：Leaves 默认允许任何人翻任意假人的背包，得按归属收口
        this.inventoryGuard = new BotInventoryGuard(this);
        if (inventoryGuard.register() && leaves.isInventoryOpenEnabled()) {
            getLogger().info("假人背包归属已生效：玩家只能打开自己召唤的假人的背包。");
        }

        // 命名空间隔离：前后缀含特殊字符时，Leaves 的合法性正则会拦下全部创建，
        // 由 BotNameGate 按本插件的命名规则放行
        this.nameGate = new BotNameGate(this, leaves);
        if (nameGate.register() && leaves.isNamespaced()) {
            getLogger().info("假人命名空间已隔离：假人名形如 " + leaves.getPrefix() + "<玩家名>"
                    + LeavesBridge.SEPARATOR + "<编号>" + leaves.getSuffix()
                    + "，这个形状的真人账号会在登录阶段被挡下（离线服上正版验证不生效，只能自己拦）。");
        } else if (leaves.isNamespaced()) {
            getLogger().warning("假人名里含特殊字符，但本插件接不上 BotCreateEvent，"
                    + "Leaves 会拒绝创建所有假人。");
        }

        // 原版对话框输入：间歇动作的间隔在一个真输入框里填。
        // 它不是 Listener，不用注册事件——2026-09-15 之前走聊天输入，
        // 撞上不看 isCancelled 的聊天插件（SimpleChat）后换的通道，详见 DialogPrompt 的类注释。
        this.prompt = new DialogPrompt(this);

        // 图形菜单：/bm menu。经济是软依赖，没装 Vault 时菜单照常开，只是买不了号位。
        this.menu = new BotMenu(this, registry, service, leaves, prompt);
        menu.setup();
        getServer().getPluginManager().registerEvents(menu, this);

        if (!leaves.canRestore()) {
            getLogger().warning("假人存档还原不可用，玩家上下线时假人会是全新状态（背包清空）。");
        }
        getLogger().info("已启用。假人管理：/bm spawn，管理员配额：/bm limit set <玩家> <数量>");
    }

    /**
     * 接入 Residence 并注册动作拦截器。
     *
     * @return 是否接入成功。false 时领地保护整体不可用，但插件继续以纯管理模式运行。
     */
    private boolean setupResidence() {
        Plugin residence = Bukkit.getPluginManager().getPlugin("Residence");
        if (residence == null || !residence.isEnabled()) {
            return false;
        }

        this.residence = residence;
        this.hook = new ResidenceHook(this, residence);

        // 假人不触发 PlayerJoinEvent，Residence 不会给它建玩家档案，
        // 结果是任何 flag 判定都直接 false——set 开过的权限也全都不生效。这里自己补上。
        // 必须先于 flags：后者查 flag 前要靠它把假人"介绍"给 Residence。
        this.cache = new ResidenceCache(this);
        if (!cache.setup()) {
            getLogger().warning("补建假人档案失败：假人在领地里的权限判定会一律为拒绝（过严，不会漏保护）。");
        }

        this.flags = new ResidenceFlags(this, cache);
        flags.setup();

        this.interceptor = new BotActionInterceptor(this, settings, hook, flags, cache);
        if (!interceptor.register()) {
            getLogger().warning("当前服务端没有 Leaves 的 BotActionExecuteEvent，领地保护不可用。");
            this.interceptor = null;
            this.hook = null;
            this.flags = null;
            this.cache = null;
            this.residence = null;
            return false;
        }

        // 假人上线（召唤 / 存档还原 / resume 拉回）时立刻补档案。
        // 必须在这个时机做，不能等派发事件时再补：捡物品之类根本不经本插件，
        // 是 Residence 自己的监听器在拦，那时我们收不到任何通知。
        registerBotJoinHook();

        // 插件自己重载时假人还活着，不会重新触发上线事件，这里补扫一遍
        primeExistingBots();

        // Residence 被重载时其监听器会重新注册，之前缓存的 RegisteredListener 会失效
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginEnable(PluginEnableEvent event) {
                if (event.getPlugin() == residence) {
                    hook.invalidateCache();
                    // 它的内存缓存也清空了，我们的"已预热"记录必须跟着失效
                    cache.reset();
                    getLogger().info("检测到 Residence 重新启用，已刷新监听器缓存和假人档案。");
                }
            }
        }, this);

        getLogger().info("已连接 Residence " + residence.getDescription().getVersion()
                + "：/bot 假人现在与普通陌生玩家一样受领地权限约束。");
        getLogger().info("提示：给假人授权请用 /res pset <领地> <假人名> <flag> true");
        return true;
    }

    /**
     * 接入 AuthMe 的假人白名单同步（软依赖）。
     *
     * <p>把假人上线（加名单）和移除（撤名单）两个事件都挂上。
     * <b>独立于 Residence 的注册路径</b>——没装 Residence 的服务器上，白名单同步照样要工作。
     */
    private void setupAuthMe() {
        this.authme = new AuthMeUnrestrict(this);
        if (!authme.setup()) {
            this.authme = null;
            return;
        }
        AuthMeUnrestrict target = this.authme;
        try {
            // 上线：BotJoinEvent 覆盖召唤 / manual 存档还原 / resume 拉回 三条路径
            Class<?> join = Class.forName(BOT_JOIN_EVENT);
            Method getJoinBot = join.getMethod("getBot");
            getServer().getPluginManager().registerEvent(
                    join.asSubclass(Event.class), new Listener() {
                    }, EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            if (getJoinBot.invoke(event) instanceof Player bot) {
                                target.unrestrict(bot.getName());
                            }
                        } catch (Throwable t) {
                            getLogger().warning("同步假人 AuthMe 白名单时出错：" + t);
                        }
                    }, this, true);

            // 移除：任何 reason 都撤名单（死亡 / 上下线同步 / 遣散 / 关服），与独立插件行为一致
            Class<?> remove = Class.forName(BOT_REMOVE_EVENT);
            Method getRemoveBot = remove.getMethod("getBot");
            getServer().getPluginManager().registerEvent(
                    remove.asSubclass(Event.class), new Listener() {
                    }, EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            // getBot() 返回 Bot，而 Bot extends Player
                            if (getRemoveBot.invoke(event) instanceof Player bot) {
                                target.restrict(bot.getName());
                            }
                        } catch (Throwable t) {
                            getLogger().warning("同步假人 AuthMe 白名单时出错：" + t);
                        }
                    }, this, true);
        } catch (Throwable t) {
            getLogger().warning("接不上 Leaves 的假人事件，AuthMe UnrestrictedName 同步不可用：" + t);
        }
    }

    /**
     * 假人一上线就替它补建 Residence 玩家档案。
     *
     * <p>时机很关键：**必须在上线那一刻做**。捡物品这类判定根本不经过本插件
     * （Leaves 没有对应动作，是 Residence 自己的监听器在拦），
     * 等我们派发事件时再补就已经晚了——玩家会看到「假人在自己领地里捡不起东西」。
     *
     * <p>Leaves 的 {@code BotJoinEvent} 覆盖了三条上线路径：召唤、manual 存档还原、
     * 关服 resume 拉回（它们都走 {@code BotList.placeNewBot}）。
     */
    private void registerBotJoinHook() {
        try {
            Class<?> raw = Class.forName(BOT_JOIN_EVENT);
            if (!Event.class.isAssignableFrom(raw)) {
                return;
            }
            Method getBotMethod = raw.getMethod("getBot");
            // 这个 Listener 只是个占位（主类没有 implements Listener），
            // 真正的处理逻辑在 executor 里；RegisteredListener 会持有它，不会被回收
            Listener holder = new Listener() {
            };
            getServer().getPluginManager().registerEvent(
                    raw.asSubclass(Event.class), holder, EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            if (getBotMethod.invoke(event) instanceof Player bot) {
                                cache.prime(bot);
                            }
                        } catch (Throwable t) {
                            getLogger().warning("补建假人档案时出错：" + t);
                        }
                    }, this, true);
        } catch (Throwable t) {
            getLogger().warning("接不上 Leaves 的假人上线事件：假人档案只能等它第一次动作时补建，"
                    + "捡物品这类不经本插件的判定会一直失效。" + t);
        }
    }

    /**
     * 给「插件加载时就已经在线」的假人补档案。
     *
     * <p>覆盖 {@code /reload} 场景：假人没下线，不会再触发一次 {@code BotJoinEvent}。
     */
    private void primeExistingBots() {
        if (cache == null) {
            return;
        }
        int count = 0;
        for (Player bot : leaves.listBots()) {
            cache.prime(bot);
            count++;
        }
        if (count > 0) {
            getLogger().info("已为 " + count + " 个在线假人补建 Residence 档案。");
        }
    }

    @Override
    public void onDisable() {
        // 兜底存档。**别指望它在正常关服时有用**：Leaves 的 stopServer() 是先
        // removeAll()、后 disablePlugins()，轮到这儿时在线列表已经空了。
        // 关服场景由 BotService#onServerCommand / #onPlayerCommand 抢在 /stop 执行前处理。
        // 这里能救的是 /reload 这种只禁用插件、不清假人的路径。
        if (service != null) {
            try {
                service.saveAllOnline();
            } catch (Throwable t) {
                getLogger().warning("关服前保存假人失败：" + t);
            }
        }
    }

    /**
     * 启动自检：确认 Residence 确实在监听我们伪造的那些事件。
     * 如果某项显示「无」，说明该类保护在假人身上无法生效，需要人工排查。
     */
    private void logCoverage() {
        StringBuilder sb = new StringBuilder("Residence 事件覆盖自检 -> ");
        sb.append(name("破坏", hook.isWatching(BlockBreakEvent.class))).append(' ');
        sb.append(name("右键交互", hook.isWatching(PlayerInteractEvent.class))).append(' ');
        sb.append(name("放置", hook.isWatching(BlockPlaceEvent.class))).append(' ');
        sb.append(name("桶", hook.isWatching(PlayerBucketEmptyEvent.class))).append(' ');
        sb.append(name("实体交互", hook.isWatching(PlayerInteractEntityEvent.class))).append(' ');
        sb.append(name("战斗", hook.isWatching(EntityDamageByEntityEvent.class))).append(' ');
        sb.append(name("通用伤害", hook.isWatching(EntityDamageEvent.class))).append(' ');
        sb.append(name("载具", hook.isWatching(VehicleEnterEvent.class))).append(' ');
        sb.append(name("移动", hook.isWatching(PlayerMoveEvent.class)));
        getLogger().info(sb.toString());

        if (!flags.isReady()) {
            getLogger().warning("flag 直查接口未就绪，丢弃物品 / 钓鱼两类检查将放行。");
        }
    }

    private String name(String label, boolean ok) {
        return label + (ok ? "=OK" : "=无");
    }

    // ------------------------------------------------------------------
    //  命令
    // ------------------------------------------------------------------

    private static final String PERM_ADMIN = "botmanager.admin";
    /** 包级可见：{@link BotMenu} 开界面前也要拿它判一次。 */
    static final String PERM_SPAWN = "botmanager.spawn";

    /** 一条帮助：用法、说明、所需权限（{@code null} 表示人人可用）。 */
    private record HelpEntry(String usage, String description, String permission) {}

    /**
     * 帮助表，{@code /bm help} 按执行者的权限过滤它。
     * 这里的 permission 必须和 {@link #onCommand} 里的判定保持一致，改一处就得改另一处。
     */
    private static final List<HelpEntry> HELP = List.of(
            new HelpEntry("/bm spawn [编号]", "召唤假人，不写编号就自动挑最小的空号", PERM_SPAWN),
            // 和下面那条 remove 是两件事，措辞上必须一眼看得出差别：
            // recall 存档保留（下次还在），remove 是真删（东西掉一地）。
            new HelpEntry("/bm recall [编号]", "收回假人，背包和坐标存档，下次召唤原样回来", PERM_SPAWN),
            new HelpEntry("/bm menu", "打开图形菜单：买号位、召唤、收回、动作", PERM_SPAWN),
            new HelpEntry("/bm list", "列出你名下的假人（含离线的）", PERM_SPAWN),
            // 别写"背包会保存下来"——那是旧行为。现在是真删：东西掉在原地，只有「收回」才存档。
            new HelpEntry("/bm remove <编号>", "遣散假人，东西掉在原地（号位上限不变）", PERM_SPAWN),
            new HelpEntry("/bm action <编号> <start|stop|list> [动作名]", "给假人挂上或摘掉动作", PERM_SPAWN),
            new HelpEntry("/bm limit <set|add|reduce> <玩家> <数量>", "设置某个玩家的假人配额", PERM_ADMIN),
            new HelpEntry("/bm limit <玩家>", "查询某个玩家的假人配额", PERM_ADMIN),
            new HelpEntry("/bm status", "查看插件与 Leaves 的对接状态", PERM_ADMIN),
            new HelpEntry("/bm debug <假人名>", "诊断单个假人的判定链", PERM_ADMIN),
            new HelpEntry("/bm reload", "重载配置并刷新监听器缓存", PERM_ADMIN),
            new HelpEntry("/bm help", "显示这份帮助", null)
    );

    /**
     * 权限在代码里按子命令分别判定，而不是写在 plugin.yml 的命令级 permission 上——
     * 后者会在进入 onCommand 之前就拦下所有子命令，普通玩家连 spawn 都用不了。
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 不带参数时，管理员看状态面板，其他人看帮助——比甩一句"没有权限"有用。
        if (args.length == 0) {
            if (sender.hasPermission(PERM_ADMIN)) {
                showStatus(sender);
            } else {
                showHelp(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("status") || sub.equals("reload") || sub.equals("debug") || sub.equals("limit")) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(ChatColor.RED + "[BotManager] 你没有权限使用该命令。");
                return true;
            }
        } else if (sub.equals("spawn") || sub.equals("list") || sub.equals("remove")
                || sub.equals("recall") || sub.equals("action") || sub.equals("menu")) {
            if (!sender.hasPermission(PERM_SPAWN)) {
                sender.sendMessage(ChatColor.RED + "[BotManager] 你没有权限操作假人。");
                return true;
            }
        }

        switch (sub) {
            case "reload": {
                reloadConfig();
                settings.reload(getConfig());
                if (hook != null) {
                    hook.invalidateCache();
                }
                // 自检要跟着重跑一遍：config.yml 的语法错误是 Bukkit 静默吞掉的，
                // 不重跑的话管理员在游戏里 /bm reload 之后不会看到任何提示，
                // 而菜单里的价格其实已经悄悄变成「读不到」了。
                menu.setup();
                sender.sendMessage(ChatColor.GREEN + "[BotManager] 配置已重载，监听器缓存已刷新。");
                return true;
            }
            case "status":
                showStatus(sender);
                return true;
            case "debug": {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "[BotManager] 用法：/bm debug <假人名>");
                    return true;
                }
                showDiagnosis(sender, args[1]);
                return true;
            }
            case "spawn":
                return handleSpawn(sender, args);
            case "recall":
                return handleRecall(sender, args);
            case "menu":
                return handleMenu(sender);
            case "list":
                return handleList(sender);
            case "remove":
                return handleDismiss(sender, args);
            case "action":
                return handleAction(sender, args);
            case "limit":
                return handleLimit(sender, args);
            case "help":
                showHelp(sender);
                return true;
            default:
                sender.sendMessage(ChatColor.YELLOW + "[BotManager] 未知子命令：" + args[0]);
                showHelp(sender);
                return true;
        }
    }

    // ------------------------------------------------------------------
    //  召唤 / 遣散 / 查看
    // ------------------------------------------------------------------

    private boolean handleSpawn(CommandSender sender, String[] args) {
        Player owner = asPlayer(sender);
        if (owner == null) {
            return true;
        }
        int limit = registry.getLimit(owner.getUniqueId());
        if (limit <= 0) {
            owner.sendMessage(ChatColor.RED + "[BotManager] 你的号位上限是 0，还不能召唤假人。");
            owner.sendMessage(ChatColor.GRAY + "让管理员执行 /bm limit set "
                    + owner.getName() + " <数量> 给你开号位。");
            return true;
        }

        BotService.SummonOutcome outcome;
        if (args.length > 1) {
            Integer index = parsePositive(args[1]);
            if (index == null) {
                owner.sendMessage(ChatColor.RED + "[BotManager] 编号必须是正整数：" + args[1]);
                return true;
            }
            outcome = service.summon(owner, index);
        } else {
            outcome = service.summon(owner);
        }

        String full = outcome.fullName();
        switch (outcome.result()) {
            case CREATED:
                owner.sendMessage(ChatColor.GREEN + "[BotManager] 已召唤 " + full);
                break;
            case RESTORED:
                owner.sendMessage(ChatColor.GREEN + "[BotManager] 已恢复 " + full + "，背包与位置已还原。");
                break;
            case ALREADY_ONLINE:
                owner.sendMessage(ChatColor.YELLOW + "[BotManager] " + full
                        + " 已经在线了，已记到你名下。");
                break;
            case LIMIT_REACHED:
                // 说的是"号位都占着"，不是"上限不够"——这两个玩家很容易混，
                // 所以措辞上把「号位」和「上限」分开，并且两条出路各修各的。
                owner.sendMessage(ChatColor.RED + "[BotManager] 你的 " + limit + " 个号位都已经有假人了。");
                owner.sendMessage(ChatColor.GRAY + "先 /bm remove <编号> 遣散一个把号位腾出来，"
                        + "或找管理员用 /bm limit add " + owner.getName() + " <数量> 提高上限。");
                break;
            case INDEX_OUT_OF_RANGE:
                owner.sendMessage(ChatColor.RED + "[BotManager] 编号 " + outcome.index()
                        + " 超出你的可用范围。你的上限是 " + limit + "，编号只能是 1 - " + limit + "。");
                owner.sendMessage(ChatColor.GRAY + "不带编号的 /bm spawn 会自动挑一个最小的空号。");
                break;
            case NAME_TOO_LONG:
                owner.sendMessage(ChatColor.RED + "[BotManager] 名字 " + full + " 长 " + full.length()
                        + " 字符，超出 Leaves 的 16 字符上限。");
                owner.sendMessage(ChatColor.GRAY + "全名 = Leaves 前缀 + 玩家名 + \".\" + 编号 + Leaves 后缀，"
                        + "上限 16 字符。请缩短服务端 config 里的 modify.fakeplayer.prefix 或 suffix。");
                break;
            case NAME_TAKEN_BY_PLAYER:
                owner.sendMessage(ChatColor.RED + "[BotManager] 有个真实玩家就叫 " + full
                        + "，这个名字不能用来造假人。");
                owner.sendMessage(ChatColor.GRAY + "就算硬造出来，他一登录 Leaves 就会把假人连背包一起清掉，"
                        + "所以这里直接拦住。");
                break;
            case GLOBAL_LIMIT_REACHED:
                int global = leaves.getGlobalLimit();
                owner.sendMessage(ChatColor.RED + "[BotManager] 服务器全局假人上限已满（"
                        + leaves.listBots().size() + "/" + global + "），暂时无法召唤。");
                break;
            default:
                owner.sendMessage(ChatColor.RED + "[BotManager] 召唤失败，详见服务端日志。");
                break;
        }
        return true;
    }

    /**
     * {@code /bm recall}：把假人收回存档。
     *
     * <p>跟 {@link #handleDismiss}（{@code /bm remove}）是两件完全不同的事：
     * remove 是**遣散**——东西掉在原地、编号空出来；recall 是**收起来**——
     * 背包和坐标存档、编号还是你的、下次 {@code /bm spawn} 原样带回来。
     * 菜单里这两个按钮一直并排放着，命令这边却只有 remove，
     * 于是"想保住背包就只能开菜单"——把这个缺口补上。
     *
     * <p>编号可省，但只在**恰好有一个在线假人**时才替他选。{@code /bm spawn}
     * 不写编号没有歧义（最小空号是确定的），recall 不写编号有歧义，
     * 所以多个在线时必须让玩家自己点名，不能猜。
     */
    private boolean handleRecall(CommandSender sender, String[] args) {
        Player owner = asPlayer(sender);
        if (owner == null) {
            return true;
        }

        int index;
        if (args.length > 1) {
            Integer parsed = parsePositive(args[1]);
            if (parsed == null) {
                owner.sendMessage(ChatColor.RED + "[BotManager] 编号必须是正整数：" + args[1]);
                return true;
            }
            index = parsed;
        } else {
            List<Integer> online = new ArrayList<>(service.onlineIndices(owner));
            if (online.isEmpty()) {
                owner.sendMessage(ChatColor.YELLOW + "[BotManager] 你现在没有在线的假人。");
                owner.sendMessage(ChatColor.GRAY + "用 /bm list 看看名下有哪些假人，"
                        + "离线的不需要收回。");
                return true;
            }
            if (online.size() > 1) {
                online.sort(null);
                owner.sendMessage(ChatColor.YELLOW + "[BotManager] 你有 " + online.size()
                        + " 个假人在线，得点名：/bm recall <编号>");
                owner.sendMessage(ChatColor.GRAY + "在线的编号："
                        + joinInts(online) + "　（/bm list 有完整列表）");
                return true;
            }
            index = online.get(0);
        }

        String full = service.fullName(owner, index);
        if (!service.isOnline(owner, index)) {
            owner.sendMessage(ChatColor.YELLOW + "[BotManager] " + full + " 不在线，不用收回。");
            owner.sendMessage(ChatColor.GRAY + "　（离线假人的背包早就存着了；"
                    + "要连编号一起清掉才用 /bm remove " + index + "）");
            return true;
        }
        if (service.recall(owner, index)) {
            // 措辞跟菜单里那句保持一致，玩家两边看到的说法得是同一个
            owner.sendMessage(ChatColor.GREEN + "[BotManager] 已收回 " + full
                    + "，背包和坐标已存档，下次召唤原样回来。");
            owner.sendMessage(ChatColor.GRAY + "　（编号 " + index + " 还是你的，"
                    + "用 /bm spawn " + index + " 把它叫回来。）");
        } else {
            owner.sendMessage(ChatColor.RED + "[BotManager] 收回 " + full + " 失败，详见服务端日志。");
        }
        return true;
    }

    /** 把编号列表拼成 {@code 1、3、5}。只给提示语用，不追求通用。 */
    private static String joinInts(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    /**
     * {@code /bm menu}：打开图形菜单。
     *
     * <p>权限在 {@link #onCommand} 里已经判过（与 {@code spawn} 同一档），
     * 这里只负责转交；{@link BotMenu#openMain} 自己还会再判一次，
     * 因为将来可能从别的地方（物品、NPC）打开同一个界面。
     */
    private boolean handleMenu(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        menu.openMain(player);
        return true;
    }

    private boolean handleDismiss(CommandSender sender, String[] args) {
        Player owner = asPlayer(sender);
        if (owner == null) {
            return true;
        }
        if (args.length < 2) {
            owner.sendMessage(ChatColor.YELLOW + "[BotManager] 用法：/bm remove <编号>");
            return true;
        }
        Integer index = parsePositive(args[1]);
        if (index == null) {
            owner.sendMessage(ChatColor.RED + "[BotManager] 编号必须是正整数：" + args[1]);
            return true;
        }

        String full = service.fullName(owner, index);
        if (service.dismiss(owner, index)) {
            owner.sendMessage(ChatColor.GREEN + "[BotManager] 已遣散 " + full
                    + "，背包里的东西掉在原地了。编号 " + index + " 空出来，下次 /bm spawn 会补上。");
        } else {
            owner.sendMessage(ChatColor.YELLOW + "[BotManager] 你名下没有编号 " + index
                    + " 的假人（" + full + " 不在线），已清除它的登记。");
        }
        // 玩家看到"编号空出来"很容易理解成"我的名额少了一个"，专门把上限说清楚：
        // 这里动的是假人，不是号位——限制他的是上限（见 /bm menu 的购买），那个数没动。
        // 说法要中性：上面两个分支一个真遣散了、一个只是清了残留登记，都得成立。
        owner.sendMessage(ChatColor.GRAY + "　（这里动的是假人，不是你买的号位——"
                + "号位上限不变，这一格随时能再生成一个。）");
        return true;
    }

    private boolean handleList(CommandSender sender) {
        Player owner = asPlayer(sender);
        if (owner == null) {
            return true;
        }
        UUID id = owner.getUniqueId();
        int limit = registry.getLimit(id);
        List<Integer> owned = registry.getOwned(id);

        owner.sendMessage(ChatColor.AQUA + "===== 我的假人 =====");
        if (limit <= 0) {
            owner.sendMessage(ChatColor.RED + "  你的号位上限是 0，还不能召唤假人。");
            owner.sendMessage(ChatColor.GRAY + "  让管理员执行 /bm limit set "
                    + owner.getName() + " <数量> 给你开号位。");
            return true;
        }
        owner.sendMessage(ChatColor.GRAY + "  号位：1 - " + limit
                + "　已占用：" + owned.size() + "/" + limit);

        if (owned.isEmpty()) {
            owner.sendMessage(ChatColor.DARK_GRAY + "  （还没有假人，用 /bm spawn 召唤）");
        }
        for (int index : owned) {
            String full = service.fullName(owner, index);
            boolean online = service.isOnline(owner, index);
            owner.sendMessage(ChatColor.GRAY + "  " + ChatColor.WHITE + full + "  "
                    + (online ? ChatColor.GREEN + "在线" : ChatColor.DARK_GRAY + "离线"));
        }

        // 让玩家知道"不写编号会拿到哪个号"，省得反复试
        int free = registry.findFreeIndex(id, limit, service.onlineIndices(owner)::contains);
        if (free < 0) {
            owner.sendMessage(ChatColor.RED + "  号位已满，/bm spawn 会失败。");
        } else {
            owner.sendMessage(ChatColor.DARK_GRAY + "  下一个空号：" + ChatColor.WHITE + free
                    + ChatColor.DARK_GRAY + "（/bm spawn 不写编号就用它）");
        }
        owner.sendMessage(ChatColor.DARK_GRAY + "  /bm remove 和 /bm action 的编号，"
                + "就是名字里 " + LeavesBridge.SEPARATOR + " 后面那个数字。");
        return true;
    }

    // ------------------------------------------------------------------
    //  动作控制
    // ------------------------------------------------------------------

    /**
     * {@code /bm action <编号> <子命令...>}：把自己名下的假人交给 Leaves 的
     * {@code /bot action} 去执行。
     *
     * <h2>为什么委托而不是自己实现</h2>
     * Leaves 有 19 个动作，每个的参数语法各不相同（坐标、次数、朝向、物品……），
     * 而且是 Brigadier 节点树。在插件里复刻一份意味着 Leaves 每次加动作、
     * 改参数都要跟着改，迟早对不上。直接把命令行原样转交，语法永远同步。
     *
     * <h2>归属为什么不会错</h2>
     * 假人名是**从执行者自己的名字推导**出来的（{@code <执行者名>.<编号>}），
     * 而不是从输入里读的。玩家 B 输入编号 1 只会去找 {@code B.1}，
     * 根本寻址不到 {@code A.1}——跨玩家操作在结构上就不可能发生。
     *
     * <h2>权限怎么办</h2>
     * 转交时由 {@link #runBotCommand} 临时给玩家挂上 {@code bukkit.command.bot}，
     * 所以普通玩家不需要这个权限也能用自己的 {@code /bm action}。
     */
    private boolean handleAction(CommandSender sender, String[] args) {
        Player owner = asPlayer(sender);
        if (owner == null) {
            return true;
        }
        if (args.length < 3) {
            owner.sendMessage(ChatColor.YELLOW
                    + "[BotManager] 用法：/bm action <编号> <start|stop|list> [...]");
            owner.sendMessage(ChatColor.GRAY + "  例：/bm action 1 start break");
            owner.sendMessage(ChatColor.GRAY + "  参数与 /bot action 完全一致，"
                    + "只是把假人名换成了编号。");
            return true;
        }

        Integer index = parsePositive(args[1]);
        if (index == null) {
            owner.sendMessage(ChatColor.RED + "[BotManager] 编号必须是正整数：" + args[1]);
            return true;
        }

        // 名字由执行者推导得出，因此查到的假人必然属于他
        String raw = BotService.rawName(owner, index);
        Player bot = leaves.findByRawName(raw);
        if (bot == null) {
            owner.sendMessage(ChatColor.RED + "[BotManager] 编号 " + index
                    + " 的假人（" + leaves.fullName(raw) + "）不在线。");
            owner.sendMessage(ChatColor.GRAY + "用 /bm list 看看自己的假人，或 /bm spawn "
                    + index + " 把它召唤出来。");
            return true;
        }

        // 编号换成假人名，其余参数原样透传给 /bot action
        String[] sub = args.clone();
        sub[1] = bot.getName();
        runBotCommand(owner, sub);
        return true;
    }

    /**
     * 以 {@code owner} 本人的身份执行一条 {@code /bot} 子命令。
     *
     * <h2>为什么调 execute() 而不是 Bukkit.dispatchCommand()</h2>
     * {@code /bot action} 要 {@code bukkit.command.bot} 权限，普通玩家没有，所以先临时挂一个
     * {@link PermissionAttachment}。挂上之后 {@code owner.hasPermission("bukkit.command.bot")}
     * 确实是 true，{@code Command#testPermissionSilent} 也返回 true，OP 与控制台派发都正常——
     * 但**非 OP 玩家**经 {@link Bukkit#dispatchCommand} 派发时，仍然只得到一句
     * {@code Bukkit.permissionMessage()}，而且命令体根本没被执行（返回 true 却没有任何回执）。
     * 实测把 {@code bukkit.command.bot.action} 等子权限一并补上也救不回来，
     * 说明这道关不在 Leaves 的权限判定里，而在 Paper 的 Brigadier 派发链路中。
     *
     * <p>换成直接调 {@link Command#execute} 就完全正常：这是 Bukkit 的正规 API，
     * 跑的就是 Leaves 自己那份 {@code execute}，语义与 {@code /bot action} 一致，
     * 只是绕开了 Brigadier 那一层。代价是不触发 {@code PlayerCommandPreprocessEvent}、
     * 也不进服务器命令日志——对这个「换个名字转交」的场景反倒是好事，不会重复记账。
     *
     * <h2>授权窗口的安全性</h2>
     * 授权只覆盖这一次调用，且全程在主线程上同步完成，玩家输入插不进来，
     * 因此不存在「趁授权期再发一条 /bot」的可能。finally 保证一定摘掉。
     */
    /**
     * 以 {@code owner} 的身份替他自己的假人跑一条 {@code /bot action} 子命令。给操作面板用。
     *
     * <p>走命令而不是直接 {@code Bot#addAction(...)} 构造动作对象：delay / interval /
     * do_number 这套参数语法由 Leaves 自己解析，插件里再写一份的话，
     * 哪天 Leaves 调了参数顺序，这边会静默错位（该传间隔的位置传成了执行次数）。
     *
     * @param bot  目标假人。调用方必须已经确认过它归属 {@code owner}——
     *             这里只负责转发，不做归属校验。
     * @param tail {@code start} 之后的部分。例如 {@code start attack 0 5 -1} 就传
     *             {@code "attack", "0", "5", "-1"}；状态型动作传 {@code "sneak"} 一个词。
     */
    void runBotAction(Player owner, Player bot, String... tail) {
        String[] sub = new String[tail.length + 2];
        sub[0] = "action";
        sub[1] = bot.getName();
        System.arraycopy(tail, 0, sub, 2, tail.length);
        runBotCommand(owner, sub);
    }

    private void runBotCommand(Player owner, String[] sub) {
        Command botCmd = Bukkit.getCommandMap().getCommand("bot");
        if (botCmd == null) {
            owner.sendMessage(ChatColor.RED + "[BotManager] 服务端没有注册 /bot 命令，"
                    + "请确认 Leaves 的假人功能已开启。");
            return;
        }

        PermissionAttachment attachment =
                owner.addAttachment(this, LeavesBridge.LEAVES_BOT_PERMISSION, true);
        try {
            if (!botCmd.execute(owner, "bot", sub)) {
                owner.sendMessage(ChatColor.RED + "[BotManager] Leaves 拒绝了这条命令，"
                        + "多半是动作名或参数写错了。");
                owner.sendMessage(ChatColor.GRAY + "输入 /bot action 可以看 Leaves 的原始用法。");
            }
        } finally {
            owner.removeAttachment(attachment);
        }
    }

    // ------------------------------------------------------------------
    //  配额管理
    // ------------------------------------------------------------------

    private boolean handleLimit(CommandSender sender, String[] args) {
        // /bm limit <玩家>
        if (args.length == 2) {
            OfflinePlayer target = resolvePlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "[BotManager] 找不到玩家：" + args[1]);
                return true;
            }
            sender.sendMessage(ChatColor.GRAY + "[BotManager] " + target.getName() + " 的假人上限："
                    + registry.getLimit(target.getUniqueId()));
            return true;
        }

        // /bm limit set|add|reduce <玩家> <数量>
        if (args.length < 4 || !isLimitOperation(args[1])) {
            sender.sendMessage(ChatColor.YELLOW
                    + "[BotManager] 用法：/bm limit <set|add|reduce> <玩家> <数量>，或 /bm limit <玩家> 查询");
            return true;
        }

        OfflinePlayer target = resolvePlayer(args[2]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "[BotManager] 找不到玩家：" + args[2]);
            return true;
        }
        Integer amount = parseNonNegative(args[3]);
        if (amount == null) {
            sender.sendMessage(ChatColor.RED + "[BotManager] 数量必须是非负整数：" + args[3]);
            return true;
        }

        UUID id = target.getUniqueId();
        int before = registry.getLimit(id);
        int after = switch (args[1].toLowerCase()) {
            case "set" -> amount;
            case "add" -> before + amount;
            default -> Math.max(0, before - amount);
        };
        registry.setLimit(id, after);

        sender.sendMessage(ChatColor.GREEN + "[BotManager] " + target.getName()
                + " 的假人上限：" + before + " -> " + after);
        return true;
    }

    private static boolean isLimitOperation(String raw) {
        String op = raw.toLowerCase();
        return op.equals("set") || op.equals("add") || op.equals("reduce");
    }

    /** 先找在线玩家，再退回离线档案；都没有则返回 null。 */
    private static OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline : null;
    }

    private static Integer parsePositive(String raw) {
        Integer value = parseNonNegative(raw);
        return value != null && value > 0 ? value : null;
    }

    private static Integer parseNonNegative(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 按执行者的权限过滤 {@link #HELP}，一行一条。 */
    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "===== BotManager 指令 =====");

        // 只要有一条带权限的条目可见，就不算"什么都没权限"。
        // /bm help 自己不带权限、永远可见，所以不能拿显示条数来判断。
        boolean any = false;
        for (HelpEntry entry : HELP) {
            if (entry.permission() != null && !sender.hasPermission(entry.permission())) {
                continue;
            }
            if (entry.permission() != null) {
                any = true;
            }
            sender.sendMessage(ChatColor.YELLOW + entry.usage()
                    + ChatColor.GRAY + "　" + entry.description());
        }

        if (!any) {
            sender.sendMessage(ChatColor.RED + "你没有任何 BotManager 指令的权限。");
        }
    }

    private Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(ChatColor.RED + "[BotManager] 该命令只能由玩家执行。");
        return null;
    }

    // ------------------------------------------------------------------
    //  Tab 补全
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission(PERM_SPAWN)) {
                out.addAll(List.of("spawn", "recall", "menu", "list", "remove", "action"));
            }
            if (sender.hasPermission(PERM_ADMIN)) {
                out.addAll(List.of("limit", "status", "reload", "debug"));
            }
            out.add("help");   // 人人可用，所以不放在上面两个分支里
            return filter(out, args[0]);
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            switch (sub) {
                case "debug" -> {
                    for (Player bot : leaves.listBots()) {
                        out.add(bot.getName());
                    }
                }
                case "remove" -> {
                    // remove 对离线假人也有意义（清掉残留登记），所以列全部编号
                    if (sender instanceof Player owner) {
                        for (int index : registry.getOwned(owner.getUniqueId())) {
                            out.add(String.valueOf(index));
                        }
                    }
                }
                case "recall" -> {
                    // recall 也只对在线假人有意义——离线的那本来就已经是存着的状态了
                    for (int index : onlineIndexes(sender)) {
                        out.add(String.valueOf(index));
                    }
                }
                case "action" -> {
                    // action 只能作用于在线假人，补出离线编号只会让人困惑
                    for (int index : onlineIndexes(sender)) {
                        out.add(String.valueOf(index));
                    }
                }
                case "limit" -> {
                    out.addAll(List.of("set", "add", "reduce"));
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        out.add(online.getName());
                    }
                }
                case "spawn" -> {
                    // 补出还没被占的号位，让玩家一眼看到自己能开哪个号。
                    // 不写编号也是合法的（自动挑最小的空号），所以补全只是省事，不是必须。
                    if (sender instanceof Player owner) {
                        Set<Integer> online = service.onlineIndices(owner);
                        for (int index = 1; index <= Math.min(registry.getLimit(owner.getUniqueId()),
                                BotRegistry.MAX_INDEX); index++) {
                            if (!online.contains(index)) {
                                out.add(String.valueOf(index));
                            }
                        }
                    }
                }
            }
            return filter(out, args[1]);
        }

        // /bm action <编号> <子命令>
        if (sub.equals("action") && args.length == 3 && parsePositive(args[1]) != null) {
            return filter(List.of("start", "stop", "list"), args[2]);
        }

        // /bm action <编号> start <动作名>
        if (sub.equals("action") && args.length == 4
                && parsePositive(args[1]) != null && args[2].equalsIgnoreCase("start")) {
            return filter(new ArrayList<>(leaves.getActionNames()), args[3]);
        }

        if (sub.equals("limit") && isLimitOperation(args[1])) {
            if (args.length == 3) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    out.add(online.getName());
                }
                return filter(out, args[2]);
            }
            if (args.length == 4) {
                return filter(List.of("1", "2", "3", "5", "10"), args[3]);
            }
        }

        return out;
    }

    /**
     * 执行者名下**当前在线**的假人编号，用于补全。
     *
     * <p>只列在线的：{@code /bm remove} 和 {@code /bm action} 都只能作用于在线假人，
     * 补出一个用不了的编号只会让人困惑。
     */
    private List<Integer> onlineIndexes(CommandSender sender) {
        if (!(sender instanceof Player owner)) {
            return List.of();
        }
        Set<Integer> online = service.onlineIndices(owner);
        List<Integer> result = new ArrayList<>();
        for (int index : registry.getOwned(owner.getUniqueId())) {
            if (online.contains(index)) {
                result.add(index);
            }
        }
        return result;
    }

    /** 按已输入的前缀做大小写不敏感的过滤。 */
    private static List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(lower)) {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * 诊断某个假人当前朝着的方块能否被破坏，把判定链逐步打印出来。
     * 让假人看向目标方块再执行本命令。
     */
    private void showDiagnosis(CommandSender sender, String name) {
        List<Player> bots = findOnlineBots();
        Player bot = null;
        for (Player candidate : bots) {
            if (candidate.getName().equalsIgnoreCase(name)) {
                bot = candidate;
                break;
            }
        }

        if (bot == null) {
            sender.sendMessage(ChatColor.RED + "[BotManager] 没有找到在线假人：" + name);
            if (bots.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "当前没有检测到任何在线假人。");
            } else {
                StringBuilder sb = new StringBuilder("当前在线假人：");
                for (Player candidate : bots) {
                    sb.append(candidate.getName()).append(' ');
                }
                sender.sendMessage(ChatColor.GRAY + sb.toString());
            }
            return;
        }

        sender.sendMessage(ChatColor.AQUA + "===== BotManager 诊断：" + bot.getName() + " =====");
        sender.sendMessage(ChatColor.GRAY + "  UUID            : " + bot.getUniqueId());
        if (interceptor == null) {
            sender.sendMessage(ChatColor.YELLOW + "  领地保护未启用，没有可诊断的判定链。");
            return;
        }
        for (String line : interceptor.diagnose(bot)) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
    }

    private void showStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "===== BotManager =====");
        sender.sendMessage(ChatColor.GRAY + "Residence 版本："
                + (residence == null ? "未连接" : residence.getDescription().getVersion()));
        sender.sendMessage(ChatColor.GRAY + "保护开关："
                + (interceptor == null ? "未启用" : settings.isEnabled() ? "开启" : "关闭"));
        sender.sendMessage(ChatColor.GRAY + "调试模式：" + (settings.isDebug() ? "开启" : "关闭"));
        sender.sendMessage(ChatColor.GRAY + "flag 直查接口："
                + (flags == null ? "未启用" : flags.isReady() ? "就绪" : "未就绪"));
        // 这一行是"假人为什么在领地里没权限"的第一诊断点：
        // 档案没建起来的话，Residence 对假人的任何 flag 判定都会直接返回拒绝
        sender.sendMessage(ChatColor.GRAY + "假人领地档案："
                + (cache == null ? "未接入（Residence 未连接）"
                        : !cache.isReady() ? ChatColor.RED + "未接通！假人的领地权限会一律被拒"
                        : "已建立 " + cache.primedCount() + " 个"));
        String eco = menu == null ? null : menu.economyName();
        sender.sendMessage(ChatColor.GRAY + "经济后端："
                + (eco == null
                        ? "未接入（菜单里买不了号位）"
                        : eco + "　扣款走 Vault"));
        int globalLimit = leaves.getGlobalLimit();
        sender.sendMessage(ChatColor.GRAY + "Leaves 全局假人上限："
                + (globalLimit > 0 ? globalLimit : "未知") + "　存档还原："
                + (leaves.canRestore() ? "可用" : "不可用"));
        sender.sendMessage(ChatColor.GRAY + "背包归属限制："
                + (inventoryGuard == null || !inventoryGuard.isRegistered()
                        ? "未生效（Leaves 无此事件）"
                        : getConfig().getBoolean("bot.restrict-inventory-to-owner", true)
                                ? "开启" : "关闭")
                + "　Leaves 开背包功能："
                + (leaves.isInventoryOpenEnabled() ? "已开" : "未开"));
        sender.sendMessage(ChatColor.GRAY + "假人命名空间："
                + (leaves.isNamespaced()
                        ? "已隔离（假人名含 " + LeavesBridge.SEPARATOR + "，真人注册不了同名账号）"
                        : "未隔离（假人名全是普通字符，真人可以注册同名账号）"));
        sender.sendMessage(ChatColor.GRAY + "死亡号位回收："
                + (service == null || !service.isDeathWatcherReady()
                        ? "未生效（号位需手动 /bm remove 清理）"
                        : "已生效"));
        long autosaveMinutes = getConfig().getLong("bot.autosave-interval-minutes", 5L);
        sender.sendMessage(ChatColor.GRAY + "崩溃自动存档："
                + (autosaveMinutes <= 0
                        ? "已关闭（强杀后假人回退到上次落盘的状态）"
                        : (leaves.canAutosave()
                                ? "每 " + autosaveMinutes + " 分钟一次"
                                : "未生效（接不上 Leaves 的存档写入口）")));
        // Leaves 的假人掉落不看 keepInventory——这是刻意的，不是 bug，所以这里只做说明
        boolean keepInv = getServer().getWorlds().stream()
                .anyMatch(w -> Boolean.TRUE.equals(w.getGameRuleValue(GameRule.KEEP_INVENTORY)));
        sender.sendMessage(ChatColor.GRAY + "假人死亡掉落："
                + (keepInv ? "照常掉落（本服开着死亡不掉落，但那只保护真人，假人不受影响）"
                           : "照常掉落"));
        String conflict = getConfig().getString("bot.name-conflict", "protect");
        sender.sendMessage(ChatColor.GRAY + "同名玩家处理："
                + ("deny".equalsIgnoreCase(conflict)
                        ? "deny（拒绝其登录，假人不受影响）"
                        : "protect（放行，假人存档移除）"));

        List<Player> bots = findOnlineBots();
        sender.sendMessage(ChatColor.GRAY + "在线假人：" + bots.size() + " 个");
        for (Player bot : bots) {
            // 把 UUID 打出来，方便直接复制给 /res pset 或权限插件使用
            sender.sendMessage(ChatColor.DARK_GRAY + "  - " + ChatColor.WHITE + bot.getName()
                    + ChatColor.DARK_GRAY + " UUID=" + bot.getUniqueId());
        }
        if (bots.isEmpty()) {
            sender.sendMessage(ChatColor.DARK_GRAY + "  （没有检测到在线假人）");
        }
    }

    /**
     * 在线假人列表，转交 {@link LeavesBridge}。
     *
     * <p><b>不能用 {@code Bukkit.getOnlinePlayers()}。</b>假人由 Leaves 单独维护在内部的
     * {@code BotList} 中，并不出现在 {@code getOnlinePlayers()} 返回的集合里；
     * 正确的公开入口是 {@code Server#getBotManager()#getBots()}。
     */
    private List<Player> findOnlineBots() {
        return leaves.listBots();
    }

    public GuardSettings getSettings() {
        return settings;
    }

    public LeavesBridge getLeaves() {
        return leaves;
    }

    public ResidenceHook getHook() {
        return hook;
    }
}
