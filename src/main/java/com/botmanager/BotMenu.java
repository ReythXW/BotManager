package com.botmanager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 假人菜单：仓库式的主菜单 + 两级操作面板。
 *
 * <h2>主菜单的排布</h2>
 * 格子从第 1 格起按顺序排，<b>一个格子就是一个号位</b>，格子下标 i 对应号位 i+1：
 * <pre>
 *   上限 0           [购买]  -    -    -    ...
 *   上限 2，没召唤    [可用1] [可用2] [购买] -  ...
 *   上限 2，召唤了 1  [实例1] [可用2] [购买] -  ...
 *   上限 2，召唤了 2  [实例1] [实例2] [购买] -  ...
 * </pre>
 * 也就是：<b>前「上限」个格子是号位，紧跟着的那一格固定是购买按钮</b>，其余为空。
 * 购买一次只加一个上限（{@code /bm limit add <玩家> 1}），所以购买按钮永远只有"下一个号位"
 * 这一种含义，点哪买哪，不存在跳号。
 *
 * <h2>三种格子的点击行为</h2>
 * <ul>
 *   <li><b>实例</b>（号位已绑定假人，不论在不在线）——进完整操作面板：
 *       召唤 / 收回 / 传送到假人 / 返回。</li>
 *   <li><b>可用</b>（号位在上限内，但还没召唤过）——进小面板：
 *       只有「生成假人」和「返回」。生成会把<b>这个格子对应的号位</b>真的拉上线并绑定，
 *       不是自动挑一个空号，所以生成出来的假人编号必然跟玩家点的格子一致。</li>
 *   <li><b>购买</b>——花钱把上限 +1，那一个格子随即变成新的「可用」。
 *       价钱按<b>号位</b>算（见 {@link #priceFor}），第 1 格 5 万、第 2 格 10 万这样逐级涨，
 *       涨法由 {@code menu.price-list} / {@code menu.price-per-slot} + {@code menu.price-growth} 配。</li>
 * </ul>
 *
 * <h2>"可用"和"实例"的判据</h2>
 * 用 {@link BotRegistry#owns}（bots.yml 里的登记），不是"在不在线"。
 * 因为假人离线（收回、服务器重启、主人下线）时号位仍然属于玩家，
 * 这时它是「离线实例」，点进去应该看到完整面板里的"召唤"按钮，而不是被当成还没买过的空号。
 */
public final class BotMenu implements Listener {

    // ---- 主菜单 ----
    private static final int ROW = 9;
    /** 容器最多几行。超过这个数，多出来的号位格子就显示不下了（购买按钮也会被挤掉）。 */
    private static final int MAX_ROWS = 6;

    // ---- 完整操作面板 ----
    private static final int BOT_SIZE = 54;
    /** 第一行：假人自己。 */
    private static final int BOT_INFO = 4;
    /** 第二行：生死存亡。 */
    private static final int BOT_SUMMON = 11;
    private static final int BOT_RECALL = 13;
    private static final int BOT_REMOVE = 15;
    /**
     * 第三到六行：<b>左键一列、右键一列</b>，各占一列竖着排。
     *
     * <pre>
     *   列 2 左键        列 4 右键        列 6 其它
     *   20 点按左键      22 点按右键      24 潜行
     *   29 间歇左键      31 间歇右键      33 跳跃
     *   38 长按左键      40 长按右键      42 停止所有操作
     *   47 挖方块        49 返回
     * </pre>
     *
     * <p>2026-09-15 用户嫌原来的排法丑——左右键混在同一行、{@code attack} 和
     * {@code break} 交错着排，看不出哪格管哪边。改成按<b>鼠标键</b>分列：
     * 一列全是对左键的模拟，一列全是对右键的模拟。
     *
     * <p>列内再按<b>动作</b>分组，同名的挨着：左键列上面两个是 {@code attack}
     * （点按左键、间歇左键），下面两个是 {@code break}（长按左键、挖方块）。
     * 这正是用户那句「攻击和攻击放一起，破坏和破坏放一起」。
     *
     * <p>顺序是「点按 → 间歇 → 长按 → 自选」。左右两列的前三行节奏一一对齐，
     * 横着看过去就是同一档的左键版和右键版；左键列多出来的第 4 格是挖方块，
     * 它是「长按左键」那个 {@code break} 的自选间隔版，紧挨着放。
     */
    private static final int BOT_ATTACK_CLICK = 20;
    private static final int BOT_ATTACK_TICK = 29;
    private static final int BOT_ATTACK_HOLD = 38;
    private static final int BOT_BREAK = 47;
    private static final int BOT_USE_CLICK = 22;
    private static final int BOT_USE_TICK = 31;
    private static final int BOT_USE_HOLD = 40;
    // ---- 列 6：不分左右键的那几个 ----
    /** 它是个状态，谈不上"做几次"。 */
    private static final int BOT_SNEAK = 24;
    private static final int BOT_JUMP = 33;
    /** 列 6 最后一行：一键全停。 */
    private static final int BOT_STOP_ALL = 42;
    /** 最后一行正中：回去。 */
    private static final int BOT_BACK = 49;

    /**
     * 右下角那本只读的说明书：面板按钮能调的只有「间隔」一个数，
     * 想改首次延迟、想限定次数就只能走命令，这里把 {@code /bm action} 的用法写清楚。
     *
     * <p><b>它没有点击事件，是刻意的</b>——点击在 {@code onBotClick} 里落不到任何分支，
     * 等于无事发生；上层的 {@link InventoryClickEvent} 早就整片取消掉了，
     * 所以也拿不走这件物品。别给它加 hint()，那句「▶ 左键 …」是给能点的格子用的。
     */
    private static final int BOT_CMD_HELP = 53;

    // ---- 可用假人的小面板 ----
    private static final int UNBOUND_SIZE = 27;
    private static final int UNBOUND_SPAWN = 11;
    private static final int UNBOUND_BACK = 15;

    // ---- 移除假人的二次确认 ----
    private static final int CONFIRM_SIZE = 27;
    private static final int CONFIRM_YES = 11;
    private static final int CONFIRM_BOT = 13;
    private static final int CONFIRM_NO = 15;

    /**
     * 间歇类动作允许的间隔范围（tick）。
     *
     * <p>下限 1 而不是 0：0 在 Leaves 那边是"每 tick 都跑"，跟「一直做」那排按钮没区别了，
     * 而玩家想表达"一直做"时该点的是那排。上限 1 小时——再长就不该用假人做，
     * 而且一个手滑多打几个零会让假人看起来像坏了。
     */
    private static final int INTERVAL_MIN = 1;
    private static final int INTERVAL_MAX = 72000;

    // ---- 价格 ----
    /**
     * 价格上限。{@code price-growth} 配大了（比如底价 5 万、倍数 3、买到第 20 格）
     * 会溢出成 {@code Infinity}，那东西塞进 lore 和 Vault 都不成样子，统一压到这里。
     */
    private static final double PRICE_CAP = 1.0E12D;

    // ---- lore 排版 ----
    /** 菜单里没配 {@code menu.max-slots} 时的上限。 */
    private static final int MAX_SLOTS_DEFAULT = 5;
    /** 启动日志里最多列几档价，免得 max-slots 配了 50 时刷满屏。 */
    private static final int LADDER_LOG_LIMIT = 8;
    /**
     * lore 里的分隔线。
     *
     * <p>用 {@code ▬} 而不是一长串 {@code -}：原版字体自带这个字形，等宽、不抢眼，
     * 一串连字符看着像没写完的减号。16 个刚好不会把 tooltip 撑到换行。
     */
    private static final String DIVIDER = ChatColor.DARK_GRAY + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";

    private final BotManager plugin;
    private final BotRegistry registry;
    private final BotService service;
    private final LeavesBridge leaves;
    private final VaultEconomy economy;
    /** 间歇动作要玩家输个间隔——箱子界面里没有输入框，弹原生对话框问。 */
    private final DialogPrompt prompt;

    public BotMenu(BotManager plugin, BotRegistry registry, BotService service,
                   LeavesBridge leaves, DialogPrompt prompt) {
        this.plugin = plugin;
        this.registry = registry;
        this.service = service;
        this.leaves = leaves;
        this.prompt = prompt;
        this.economy = VaultEconomy.hook();
    }

    /**
     * 启动自检。价格配了、但服务器没有经济插件时提前说清楚，
     * 否则玩家点上去只会得到一句"买不了"，管理员还得自己猜原因。
     *
     * <p>顺带把前几档价格打进日志——价格是算出来的（表 + 倍数），
     * 配错了在界面上一次只看得到一格，日志里一眼能看完整条曲线。
     */
    public void setup() {
        if (!isPricingConfigured()) {
            plugin.getLogger().severe("config.yml 里读不到 menu.price-per-slot / menu.price-list——"
                    + "多半是文件里有 YAML 语法错误，服务器已经把整份配置当成空的了。"
                    + "为避免「配置读挂了 ⇒ 号位全部免费」，菜单里的购买按钮已停售，"
                    + "以免玩家白拿号位。请翻服务端日志里 Bukkit 的 YAML 报错行（会指明行号），"
                    + "改好后 /bm reload。");
            return;
        }
        int cap = maxSlots();
        List<String> ladder = new ArrayList<>();
        for (int slot = 1; slot <= Math.min(cap, LADDER_LOG_LIMIT); slot++) {
            double price = priceFor(slot);
            ladder.add("#" + slot + "=" + (price <= 0 ? "免费" : compact(price)));
        }
        plugin.getLogger().info("菜单最多买 " + cap + " 个号位，价格："
                + String.join("，", ladder) + (cap > LADDER_LOG_LIMIT ? "，……" : "")
                + "（由 menu.max-slots 和 menu.price-list 决定）");

        if (priceFor(1) <= 0) {
            return;         // 免费解锁，没什么可自检的
        }
        if (!economy.isReady()) {
            plugin.getLogger().warning("菜单配了价格但没接上 Vault 经济，玩家买不了号位。"
                    + "装 Vault + 一个经济插件，或把 menu.price-per-slot 和 menu.price-list "
                    + "都留空/改成 0 表示免费解锁。");
        } else {
            // 装了多个经济插件时，Vault 只交出优先级最高的那一个，界面上完全看不出来。
            // 开服就把名字钉在日志里：玩家说"钱少了"、管理员说"我没收到钱"的时候，
            // 第一件事就是确认这两个人用的是不是同一个后端。
            plugin.getLogger().info("购买走 " + economy.providerName()
                    + "（Vault 里优先级最高的那个经济插件）");
        }
    }

    /**
     * 当下扣的是哪个经济插件的钱；没接上经济时返回 {@code null}。
     *
     * <p>包级可见，给 {@link BotManager#showStatus} 用。
     */
    String economyName() {
        return economy.isReady() ? economy.providerName() : null;
    }

    // ------------------------------------------------------------------
    //  主菜单
    // ------------------------------------------------------------------

    /**
     * 容器要几格：前「上限」格是号位，再留一格给购买按钮。
     *
     * <p>按上限算而不是按"已召唤几个"算——上限才是玩家看到的那排"可用假人"的个数。
     */
    private static int mainSize(int limit) {
        int needed = limit + 1;
        int rows = (needed + ROW - 1) / ROW;
        return Math.max(1, Math.min(MAX_ROWS, rows)) * ROW;
    }

    public void openMain(Player player) {
        if (!player.hasPermission(BotManager.PERM_SPAWN)) {
            player.sendMessage(ChatColor.RED + "[BotManager] 你没有权限操作假人。");
            return;
        }
        // 延后一 tick 再开：这个入口既可能来自命令，也可能来自点击事件里换界面。
        // 事件回调里直接换界面，偶尔会和还在飞的点击包对不上，光标上会挂住展示物品。
        // 统一延后就没这个问题，代价只是一 tick。
        Bukkit.getScheduler().runTask(plugin, () -> {
            int limit = registry.getLimit(player.getUniqueId());
            BotMenuHolder holder =
                    new BotMenuHolder(BotMenuHolder.Kind.MAIN, player.getUniqueId(), 0);
            Inventory inv = Bukkit.createInventory(holder, mainSize(limit),
                    ChatColor.DARK_GREEN + "假人仓库");
            holder.attach(inv);
            fillMain(player, inv);
            player.openInventory(inv);
        });
    }

    private void fillMain(Player player, Inventory inv) {
        inv.clear();
        int limit = registry.getLimit(player.getUniqueId());

        // 号位格：格子下标 i -> 号位 i+1
        for (int slot = 0; slot < limit && slot < inv.getSize(); slot++) {
            inv.setItem(slot, buildSlotItem(player, slot + 1));
        }
        // 购买按钮紧跟在最后一个号位后面；买到 menu.max-slots 之后换成"到顶了"的牌子。
        // 位置不动，只是不再是能点的东西——那一格空掉反而像是界面出错了。
        if (limit < inv.getSize()) {
            int next = limit + 1;
            inv.setItem(limit, next > maxSlots()
                    ? buildCappedItem(next)
                    : buildBuyItem(player, next));
        }
        // 上限大到把购买按钮挤出容器时不画它——总比画在越界位置上崩掉强。
        // 真出现这种情况（上限 ≥ 53）说明配额配得离谱，属于管理问题，不是菜单问题。
    }

    /** 一个号位格：已绑定就是「实例」，没绑定就是「可用」。 */
    private ItemStack buildSlotItem(Player player, int index) {
        boolean bound = registry.owns(player.getUniqueId(), index);
        boolean online = bound && service.isOnline(player, index);
        String full = service.fullName(player, index);

        if (!bound) {
            return item(Material.LIME_DYE,
                    ChatColor.GREEN + "可用假人 " + ChatColor.WHITE + "#" + index,
                    List.of(
                            DIVIDER,
                            kv("状态", dot(false, "未召唤")),
                            kv("假人名", ChatColor.DARK_GRAY + full),
                            DIVIDER,
                            hint("左键", "生成假人")));
        }

        List<String> lore = List.of(
                DIVIDER,
                kv("状态", dot(online, online ? "在线" : "离线")),
                kv("假人名", ChatColor.DARK_GRAY + full),
                DIVIDER,
                hint("左键", "打开操作面板"));

        // 这颗头故意不设 owner —— 以前设过，那是后台 429 刷屏的根源。
        // Paper 在把物品写进容器时会跑 CraftMetaSkull.applyToItem，那段逻辑是：
        //     if (hasOwner()) { ...; if (新造档案.getTextures().isEmpty()) 档案.update() ... }
        // 假人的档案里没有皮肤（真机抓下来就是 properties: []），于是每开一次菜单、
        // 每个假人头都实打实发一次 Mojang 查询；而假人 UUID 在 Mojang 根本不存在（204），
        // 失败结果不进缓存，于是一次次重查，把服务器 IP 查到被限流 —— 后台那些
        // "Couldn't look up profile properties ... Status: 429" 就是这么来的。
        // 有 owner 也只显示默认皮肤（假人本来就没皮肤），不设不会少任何东西。
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((online ? ChatColor.AQUA : ChatColor.GRAY)
                    + "假人 #" + index + ChatColor.DARK_GRAY + "  " + full);
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    /** 购买按钮：一次买一个上限，价钱按号位逐级算。 */
    private ItemStack buildBuyItem(Player player, int index) {
        double price = priceFor(index);
        List<String> lore = new ArrayList<>();
        lore.add(DIVIDER);
        lore.add(kv("解锁", ChatColor.WHITE + "号位 #" + index));
        lore.add(kv("名额", ChatColor.GRAY + "只买这一个，上限 +1"));

        if (!isPricingConfigured()) {
            // 读不到价格时绝不当成 0 卖——那等于白送
            lore.add(kv("价格", ChatColor.RED + "读取失败，暂停出售"));
            lore.add("");
            lore.add(line(ChatColor.RED + "请管理员检查 config.yml"));
            lore.add(line(ChatColor.DARK_GRAY + "服务端日志里有 YAML 报错行号"));
            lore.add(DIVIDER);
            return item(Material.BARRIER, ChatColor.RED + "购买号位 #" + index, lore);
        }

        if (price <= 0) {
            lore.add(kv("价格", ChatColor.GREEN + "免费"));
        } else if (!economy.isReady()) {
            lore.add(kv("价格", ChatColor.RED + "无法结算"));
            lore.add(line(ChatColor.RED + "服务器没装经济插件（Vault）"));
        } else {
            lore.add(kv("价格", ChatColor.GOLD + economy.format(price)));
        }
        lore.addAll(upcomingPrices(index));
        lore.add(DIVIDER);
        lore.add(hint("左键", "买下"));

        return item(Material.GOLD_INGOT,
                ChatColor.GOLD + "购买号位 " + ChatColor.YELLOW + "#" + index, lore);
    }

    /**
     * 已经买到 {@code menu.max-slots} 时顶上来的占位物品。
     *
     * <p>画的是屏障而不是干脆不画：那一格一直是购买按钮的位置，突然空掉会让玩家
     * 以为界面坏了、或者以为是自己点错了才没了。给个明确的"到顶了"更有交代。
     */
    private ItemStack buildCappedItem(int index) {
        return item(Material.BARRIER,
                ChatColor.DARK_RED + "已达购买上限",
                List.of(
                        DIVIDER,
                        kv("上限", ChatColor.WHITE + "最多 " + maxSlots() + " 个号位"),
                        kv("下一个", ChatColor.DARK_GRAY + "号位 #" + index),
                        "",
                        line("你的号位和假人都还在，只是不能再"),
                        line("买新的了。"),
                        line(ChatColor.DARK_GRAY + "确实需要更多，请联系管理员。")));
    }

    /**
     * 购买按钮上顺带预告下一档的价格。
     *
     * <p>价格是逐级涨的，只写"这一格多少钱"，玩家买完一格才发现下一格翻倍；
     * 把下一个摆出来，"再买一个要多少钱"就是明牌。
     * 价格不涨（倍数 1.0 且价格表已用完）时这行没有信息量，直接不画。
     *
     * <p><b>只预告一档</b>，而且和正经报价一样走 {@link VaultEconomy#format}。
     * 早先这里摞三档、用 {@link #compact} 压成"10.0万"——紧凑是紧凑，
     * 但同一格 lore 里"50,000.00 dollars"和"10.0万"两种写法并排，
     * 眼睛得先换算一道；而且"万/亿"是写死的中文数量级，
     * 服务器把货币单位换成别的就完全不对味（XConomy 默认就是英文的 dollars）。
     */
    private List<String> upcomingPrices(int index) {
        if (!economy.isReady()) {
            return List.of();   // 经济都没接上，预告价钱无从谈起
        }
        double next = priceFor(index + 1);
        if (next <= 0 || next == priceFor(index)) {
            return List.of();
        }
        return List.of(kv("下个", ChatColor.GRAY + economy.format(next)));
    }

    /**
     * 原地刷新主菜单。
     *
     * <p>买了最后一格时容器要长出一行来，而<b>开着的界面改不了大小</b>，
     * 这种情况只能整个重开；没跨行就原地刷，避免界面闪一下。
     */
    private void refreshMain(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof BotMenuHolder holder)
                || holder.kind() != BotMenuHolder.Kind.MAIN
                || !holder.owner().equals(player.getUniqueId())) {
            return;
        }
        if (top.getSize() != mainSize(registry.getLimit(player.getUniqueId()))) {
            openMain(player);
            return;
        }
        fillMain(player, top);
    }

    // ------------------------------------------------------------------
    //  已绑定假人的完整操作面板
    // ------------------------------------------------------------------

    private void openBot(Player player, int index) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            BotMenuHolder holder =
                    new BotMenuHolder(BotMenuHolder.Kind.BOT, player.getUniqueId(), index);
            Inventory inv = Bukkit.createInventory(holder, BOT_SIZE,
                    ChatColor.DARK_GREEN + "假人 " + service.fullName(player, index));
            holder.attach(inv);
            refreshBot(player, holder);
            player.openInventory(inv);
        });
    }

    /**
     * 操作面板上的一排动作按钮。
     *
     * <h2>同名动作互斥</h2>
     * 「长按左键」和「间歇左键」在 Leaves 那边是<b>同一个</b> {@code attack} 动作，
     * 只是 interval 不同（1 = 每 tick，其余 = 隔几 tick）。一个假人身上挂两个 {@code attack}
     * 就是每 tick 打两下，那不是玩家想要的，所以这里按<b>动作名</b>分组：
     * 同一组里最多只有一个在跑，点同组的另一个按钮会先把旧的停掉再起新的。
     *
     * <h2>点亮靠 interval 反推</h2>
     * 既然同名动作只能有一个，"现在亮哪个按钮"就得看它的 interval：
     * 正在跑的 {@code attack} 间隔是 1，亮的是「长按左键」；不是 1，亮的是「间歇左键」。
     * 只有一个按钮的动作（潜行、跳跃）不用分，名字对上就是它。
     * 间隔读不出来时（见 {@link LeavesBridge#runningActions}）退化成按名字匹配，
     * 同组两个按钮会一起亮——点哪个都能停掉，总比动作明明在跑、按钮却全暗着强。
     *
     * <h2>点一次的行为</h2>
     * <ul>
     *   <li>按钮正亮着 —— <b>停掉</b>这个动作（用户要的"再次点击关闭"）；</li>
     *   <li>按钮暗着 —— 停掉同名的旧动作，再按本按钮的间隔起一个新的。
     *       要玩家现输间隔的（{@link #asks}）先弹个原生对话框问。</li>
     * </ul>
     */
    private enum ActionButton {
        /**
         * 点按左键 = 打一下实体，对应 Leaves 的 {@code attack}，而且只做一次。
         *
         * <p>参数就是 {@code attack 0 0 1}（delay 0、interval 0、do_number 1）。
         * 它跟 {@link #ATTACK_HOLD} 是一对，补齐用户那句「左键长按是挖、点按是打」
         * 里一直缺着的点按那半边。
         *
         * <p>跟 {@link #ATTACK_TICK} 同为 {@code attack}，区别只在次数：那格无限次、
         * 间隔自选，这格做一次就收手。所以 {@link #isOn} 得靠 {@code do_number}
         * 把它们分开——靠间隔分不开，这格间隔是 0，正好落进"非 1"那一支，
         * 会和间歇那格一起亮。
         *
         * <p>注意它<b>不一定过一 tick 就结束</b>：Leaves 的 {@code ServerAttackAction}
         * 在视线里没有可打的实体时 {@code doTick} 直接返回 false，这次不算数，
         * 于是动作会挂在列表里等目标出现，按钮也就一直亮着。
         * 这是 {@code do_number = 1} 该有的语义——"打中一个就收手"，
         * 不是"过一 tick 就撤"。不想要了再点一次就停掉。
         */
        ATTACK_CLICK(BOT_ATTACK_CLICK, "attack", "点按左键", Material.WOODEN_SWORD, 0, false, 1,
                "打一下视线里的生物，打完就收手。",
                "跟下面那格配对：点按是打，长按是挖。"),
        /**
         * 长按左键 = <b>挖方块</b>，对应 Leaves 的 {@code break} 动作。
         *
         * <p>这一格原本挂的是 {@code attack}，2026-09-15 按用户的判断换掉了：
         * <b>游戏里长按左键做的就是"挖"，点按左键才是"打"</b>，所以模拟"长按左键"
         * 的按钮去起 {@code attack} 从一开始就对不上——它在没实体可打时
         * 什么都不做（连挥手动画都没有），玩家按到手酸也挖不掉一块土。
         * 而 Leaves 里这本来就是两个类：{@code attack} 走 {@code ServerAttackAction}
         * 只认实体，{@code break} 走 {@code ServerBreakBlockAction}
         * （{@code getTargetBlockExact(5)} 取视线 5 格内的方块、按进度挖穿）。
         *
         * <p>间隔固定 1（挖穿一块立刻挖下一块），跟同排的 {@link #USE_HOLD} 一个待遇；
         * 想自己定快慢用 {@link #BREAK}。图标也从铁剑换成了镐子——
         * 这格已经不打算打任何东西了，摆把剑只会让人以为它还能打。
         */
        ATTACK_HOLD(BOT_ATTACK_HOLD, "break", "长按左键", Material.DIAMOND_PICKAXE, 1, false, -1,
                "对着哪儿就一直挖穿它，挖穿一块立刻挖下一块。",
                "要自己定快慢，用下面的「挖方块」。"),
        /**
         * 点按右键 = 跟真人右键一样来一下，对应 Leaves 的 {@code use_auto}，只做一次。
         *
         * <p>参数 {@code use_auto 0 0 1}。<b>这一格最早挂的是 {@code use}，
         * 2026-09-15 被用户当场发现"放不了方块"</b>——两个动作名看着像一回事，
         * 其实是包含关系：{@code use_auto} 走 {@code ServerUseItemAutoAction}，
         * 先 {@code getHitResult} 射线，命中方块走 {@code useItemOn}
         * （放方块 / 开箱子 / 按按钮），命中实体走 {@code useItemTo}，
         * 都轮不上才调 {@code useItem}；而 {@code use} 走 {@code ServerUseItemAction}，
         * <b>只做最后那一步，根本不射线</b>，所以放置方块这条路它压根不走。
         *
         * <p>真机对照（假人手持 64 石头、看向前下方的一格空气）：{@code use 0 0 1}
         * 跑完石头还是 64、那格还是空气、动作还挂在列表里；{@code use_auto 0 0 1}
         * 石头 64 → 63、那格变成石头、动作自己消失。{@code use} 连"用了一次"都算不上，
         * 因为对空气用方块物品返回的是"不消费"，{@code do_number} 不扣，它会永远挂着等。
         *
         * <p>所以这一格跟 {@link #USE_HOLD} / {@link #USE_TICK} <b>是同一个动作名</b>，
         * 靠 {@code do_number} 分档：这格 1、那两格 -1。{@link #isOn} 里
         * {@code hasSibling()} 是按「动作名 + 次数」找兄弟的，这格找不到第二个
         * 带 1 次的 {@code use_auto}，于是退回"名字和次数对上就算亮"，
         * 间隔不参与判断。图标是草方块——这格现在真的会放方块了。
         */
        USE_CLICK(BOT_USE_CLICK, "use_auto", "点按右键", Material.GRASS_BLOCK, 0, false, 1,
                "跟真人右键一样：放方块、开箱子、按按钮。",
                "跟下面那格配对：点按来一下，长按一直按。"),
        USE_HOLD(BOT_USE_HOLD, "use_auto", "长按右键", Material.FISHING_ROD, 1, false, -1),
        SNEAK(BOT_SNEAK, "sneak", "潜行", Material.LEATHER_BOOTS, 0, false, -1),
        ATTACK_TICK(BOT_ATTACK_TICK, "attack", "间歇左键", Material.IRON_SWORD, 0, true, -1,
                "打实体，隔一会儿一下，快慢自己定。",
                "跟上面那格都用 attack，区别是做几次——点按那格打一下就收手。"),
        USE_TICK(BOT_USE_TICK, "use_auto", "间歇右键", Material.FISHING_ROD, 0, true, -1),
        JUMP(BOT_JUMP, "jump", "跳跃", Material.RABBIT_FOOT, 0, true, -1),
        /**
         * 挖方块，可自定义间隔——和 {@link #ATTACK_HOLD} 是同一个 Leaves 动作的两档。
         *
         * <p>跟「长按左键」的区别只有节奏：那格固定间隔 1（最密），这格由玩家现输
         * （{@link #asks}）。挖穿一个方块算一次，之后隔 {@code interval} tick
         * 接着挖下一个，{@code do_number} 恒为 {@code -1}（无限次）。
         * 跟右键那两格（{@link #USE_HOLD} / {@link #USE_TICK}）是同一套分法。
         */
        BREAK(BOT_BREAK, "break", "挖方块", Material.DIAMOND_PICKAXE, 0, true, -1,
                "挖它视线正前方 5 格内的方块，",
                "对着哪儿就一下一下挖穿它，快慢自己定。");

        private final int slot;
        /** Leaves 的动作名，{@code /bot action <bot> start} 后面那个词。 */
        private final String action;
        private final String label;
        private final Material material;
        /** 固定间隔；0 表示这个按钮要么要玩家现输、要么根本没有周期。 */
        private final int fixedInterval;
        /** 点下去要不要先问玩家"间隔多少 tick"。 */
        private final boolean asks;
        /**
         * 这个动作一共做几次，原样交给 Leaves 的 {@code do_number}。
         *
         * <p>{@code -1} = 一直做（停不停由玩家说了算），{@code 1} = 做一次就收手。
         * 状态型动作（{@code sneak}）不走这条路，那儿填什么都不起作用。
         */
        private final int doNumber;
        /** 未启用时额外印的说明行，用来讲清楚这个按钮到底做什么。 */
        private final List<String> detail;

        ActionButton(int slot, String action, String label, Material material,
                     int fixedInterval, boolean asks, int doNumber, String... detail) {
            this.slot = slot;
            this.action = action;
            this.label = label;
            this.material = material;
            this.fixedInterval = fixedInterval;
            this.asks = asks;
            this.doNumber = doNumber;
            this.detail = List.of(detail);
        }

        /**
         * 是不是"一直做"那类。{@code sneak} 是状态型——开了就一直保持，没有周期，
         * 停下来靠再点一次，不是靠跑够次数。
         */
        boolean isState() {
            return "sneak".equals(action);
        }

        /**
         * 同一动作名、同一"做几次"档位下，还有没有别的按钮。
         *
         * <p>分组要连 {@link #doNumber} 一起看：{@code attack} 底下有两个按钮，
         * 但一个是"做一次"、一个是"一直做"，它们各成一组、互不构成歧义。
         * 只有真的同档（比如两个 {@code break}：固定间隔 1 那个和自选间隔那个）
         * 才需要再靠间隔去分。
         */
        boolean hasSibling() {
            for (ActionButton other : values()) {
                if (other != this && other.action.equals(action) && other.doNumber == doNumber) {
                    return true;
                }
            }
            return false;
        }

        /** 正在跑的这个动作，是不是本按钮代表的那一个。 */
        boolean isOn(LeavesBridge.RunningAction running) {
            if (!running.name().equals(action)) {
                return false;
            }
            // 次数读得到就先看次数：同名不同档（attack 的"一次"/"一直做"）到这儿就分干净了
            if (running.numberKnown() && running.doNumber() != doNumber) {
                return false;
            }
            if (!running.intervalKnown()) {
                return true;        // 读不到间隔，退化成看名字
            }
            if (!hasSibling()) {
                return true;        // 同名同档只有我一个按钮，名字对上就是它
            }
            return fixedInterval > 0
                    ? running.interval() == fixedInterval
                    : running.interval() != 1;
        }

        /**
         * 按钮图标。亮着的用染料，暗着的用原图标——一眼能看出哪些在跑。
         *
         * <p>不用附魔光效（{@code addEnchantment} + {@code addItemFlags(HIDE_ENCHANTS)}）：
         * 那个光效在部分资源包里会连图标一起染色，反而看不清是什么动作。
         */
        Material icon(boolean on) {
            return on ? Material.LIME_DYE : material;
        }
    }

    private void refreshBot(Player player, BotMenuHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) {
            return;
        }
        inv.clear();
        int index = holder.index();
        boolean online = service.isOnline(player, index);
        Player bot = online ? leaves.findByRawName(BotService.rawName(player, index)) : null;

        // 动作状态只在线时读得到，离线假人身上不可能有动作在跑
        List<LeavesBridge.RunningAction> running =
                bot == null ? List.of() : leaves.runningActions(bot);

        inv.setItem(BOT_INFO, buildBotHead(player, index, online, running));

        inv.setItem(BOT_SUMMON, item(
                online ? Material.GRAY_DYE : Material.GRASS_BLOCK,
                online ? ChatColor.DARK_GRAY + "召唤假人" : ChatColor.GREEN + "召唤假人",
                online ? List.of(DIVIDER,
                                line("它已经在线上，不用再召唤一次"))
                       : List.of(DIVIDER,
                                line("把它拉上线：上次收回时的背包、"),
                                line("坐标、血量都会原样回来。"),
                                line(ChatColor.DARK_GRAY + "第一次召唤就在你站着的位置新建。"),
                                DIVIDER,
                                hint("左键", "召唤"))));

        inv.setItem(BOT_RECALL, item(
                online ? Material.REDSTONE_TORCH : Material.GRAY_DYE,
                online ? ChatColor.YELLOW + "收回假人" : ChatColor.DARK_GRAY + "收回假人",
                online ? List.of(DIVIDER,
                                line("让它下线，东西" + ChatColor.GREEN + "不会掉" + ChatColor.GRAY + "："),
                                line("背包、坐标、血量全存进存档。"),
                                DIVIDER,
                                line("号位还是你的，下次召唤原样回来。"),
                                DIVIDER,
                                hint("左键", "收回"))
                       : List.of(DIVIDER,
                                line("它现在不在线。"),
                                line(ChatColor.DARK_GRAY + "想让它上线请点「召唤假人」。"))));

        // 「移除」和「收回」挨着放，但两者差着十万八千里：收回是把它收起来，
        // 移除是把这一个假人遣散掉。lore 里必须把这件事说透，尤其是"号位还在"——
        // 玩家最怕的就是点一下把花钱买的号位一起点没了。
        inv.setItem(BOT_REMOVE, item(Material.TNT,
                ChatColor.RED + "移除假人",
                List.of(DIVIDER,
                        line("把这个假人" + ChatColor.YELLOW + "遣散" + ChatColor.GRAY
                                + "掉——只是这一个，"),
                        line("不是收回你的号位。"),
                        "",
                        line(ChatColor.RED + "· 背包和身上的东西掉在它脚下"),
                        line(ChatColor.RED + "· 存档一并清除，召不回来了"),
                        DIVIDER,
                        line("号位仍然归你，"
                                + ChatColor.GREEN + "上限也不会减少" + ChatColor.GRAY + "。"),
                        line("那一格会变回「可用假人」，随时能"),
                        line("重新生成一个新的。"),
                        DIVIDER,
                        hint("左键", "去确认"))));

        for (ActionButton button : ActionButton.values()) {
            boolean active = false;
            int activeInterval = 0;
            for (LeavesBridge.RunningAction action : running) {
                if (button.isOn(action)) {
                    active = true;
                    activeInterval = action.interval();
                    break;
                }
            }
            inv.setItem(button.slot, buildActionItem(button, online, active, activeInterval));
        }

        inv.setItem(BOT_STOP_ALL, item(
                running.isEmpty() ? Material.GRAY_DYE : Material.BARRIER,
                running.isEmpty()
                        ? ChatColor.DARK_GRAY + "停止所有操作"
                        : ChatColor.RED + "停止所有操作 " + ChatColor.DARK_GRAY + "(" + running.size() + ")",
                running.isEmpty()
                        ? List.of(DIVIDER,
                                  line(ChatColor.DARK_GRAY + "它现在什么也没在做。"))
                        : List.of(DIVIDER,
                                  kv("在跑", ChatColor.WHITE + String.valueOf(running.size())
                                          + " 个动作"),
                                  kv("明细", ChatColor.AQUA + summarize(running)),
                                  DIVIDER,
                                  hint("左键", "全部停掉"))));

        inv.setItem(BOT_BACK, item(Material.ARROW,
                ChatColor.YELLOW + "返回",
                List.of(DIVIDER,
                        line("回到假人仓库。"),
                        line(ChatColor.DARK_GRAY + "这里做的改动都已经生效了。"),
                        DIVIDER,
                        hint("左键", "返回"))));

        inv.setItem(BOT_CMD_HELP, buildCmdHelp());
    }

    /**
     * 右下角那本说明书：告诉玩家面板够不着的那半边命令。
     *
     * <p>文案里的每一个字都是真机上验过的，别照着印象改：
     * <ul>
     *   <li>三个数字的顺序是「首次延迟 → 之后每次的延迟 → 次数」。
     *       2026-09-15 用鸡蛋消耗量做的判别实验（{@code delayprobe.js}）：
     *       填 {@code 100 20} 观察 15 秒，实测扔出 10 个——
     *       按「100 是首次延迟」预言 11 个，按反过来的解释只预言 3 个。</li>
     *   <li>{@code stop} 既收编号也收 {@code all}（javap 读的字符串常量 + 真机跑过）。</li>
     *   <li><b>停掉一个之后，后面的编号会往前挪</b>——实测 {@code list} 从
     *       {@code 0 use_auto / 1 attack} 变成 {@code 0 attack}。不写这句的话，
     *       玩家会照着刚才看到的编号接着停，停错一个。</li>
     * </ul>
     */
    private static ItemStack buildCmdHelp() {
        return item(Material.WRITABLE_BOOK,
                ChatColor.AQUA + "命令用法",
                List.of(DIVIDER,
                        line("面板上的按钮只能调「间隔」这一个数。"),
                        line("想改" + ChatColor.YELLOW + "第一次等多久"
                                + ChatColor.GRAY + "、或者限定" + ChatColor.YELLOW + "做几次" + ChatColor.GRAY + "，"),
                        line("用这两条命令："),
                        "",
                        cmd("/bm action <编号> start <操作> <首次延迟> <之后每次延迟> <次数>"),
                        line(ChatColor.DARK_GRAY + "单位是 tick，20 tick = 1 秒；"),
                        line(ChatColor.DARK_GRAY + "次数填 -1 就是一直做下去。"),
                        "",
                        cmd("/bm action <编号> stop <动作编号>"),
                        cmd("/bm action <编号> stop all"),
                        DIVIDER,
                        line("操作名就是按钮跑起来时写在面板上的那个"),
                        line("词（" + ChatColor.AQUA + "attack" + ChatColor.GRAY + "、"
                                + ChatColor.AQUA + "break" + ChatColor.GRAY + "、"
                                + ChatColor.AQUA + "sneak" + ChatColor.GRAY + "…）。"),
                        line("动作编号用 " + ChatColor.WHITE + "/bm action <编号> list" + ChatColor.GRAY + " 看。"),
                        line(ChatColor.DARK_GRAY + "注意：停掉一个之后，它后面那些动作的"),
                        line(ChatColor.DARK_GRAY + "编号会往前挪一位，别照着旧的编号停。"),
                        DIVIDER,
                        line(ChatColor.DARK_GRAY + "这格只是说明，点它没有反应。")));
    }

    /** 面板顶上那颗假人头，顺带把正在跑的动作列出来。 */
    private ItemStack buildBotHead(Player player, int index, boolean online,
                                   List<LeavesBridge.RunningAction> running) {
        // 同样不设 owner，原因见假人仓库那颗头 —— 这里每开一次面板就来一下，
        // 是那堆 429 里最密的一处。
        ItemStack info = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = info.getItemMeta();
        if (meta == null) {
            return info;
        }
        List<String> lore = new ArrayList<>();
        lore.add(DIVIDER);
        lore.add(kv("号位", ChatColor.WHITE + "#" + index));
        lore.add(kv("状态", dot(online, online ? "在线" : "离线")));
        if (online) {
            lore.add(kv("动作", running.isEmpty()
                    ? ChatColor.DARK_GRAY + "空闲"
                    : ChatColor.AQUA + summarize(running)));
        } else {
            lore.add(kv("动作", ChatColor.DARK_GRAY + "—"));
        }
        lore.add(kv("假人名", ChatColor.DARK_GRAY + service.fullName(player, index)));
        lore.add(DIVIDER);
        meta.setDisplayName(ChatColor.AQUA + service.fullName(player, index));
        meta.setLore(lore);
        info.setItemMeta(meta);
        return info;
    }

    /** 把正在跑的动作缩成一行：{@code attack(间隔5) + use_auto}，超出长度就截断。 */
    private static String summarize(List<LeavesBridge.RunningAction> running) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < running.size(); i++) {
            if (i > 0) {
                sb.append(" + ");
            }
            LeavesBridge.RunningAction action = running.get(i);
            sb.append(action.name());
            if (action.intervalKnown() && action.interval() > 1) {
                sb.append("(间隔").append(action.interval()).append(')');
            }
        }
        if (sb.length() > 40) {
            return sb.substring(0, 39) + "…";
        }
        return sb.toString();
    }

    /** 一个动作按钮。亮 = 正在跑，点它停；暗 = 没跑，点它起。 */
    private ItemStack buildActionItem(ActionButton button, boolean online,
                                      boolean active, int activeInterval) {
        List<String> lore = new ArrayList<>();
        lore.add(DIVIDER);
        if (!online) {
            lore.add(kv("状态", dot(false, "不可用")));
            lore.add("");
            lore.add(line(ChatColor.DARK_GRAY + "它不在线，先召唤出来。"));
            return item(button.icon(false), ChatColor.DARK_GRAY + button.label, lore);
        }

        if (active) {
            lore.add(kv("状态", dot(true, "运行中")));
            if (activeInterval > 1) {
                lore.add(kv("间隔", ChatColor.GOLD + "每 " + activeInterval + " tick 一次"));
            } else if (!button.asks) {
                lore.add(kv("节奏", ChatColor.GOLD + "每 tick 一次"));
            }
            lore.add(DIVIDER);
            lore.add(hint("左键", "停止"));
        } else {
            lore.add(kv("状态", dot(false, "未启用")));
            lore.add("");
            // 没写 detail 的按钮这里什么都不加，lore 跟以前一模一样
            for (String note : button.detail) {
                lore.add(line(note));
            }
            if (button.isState()) {
                lore.add(line("一直保持这个姿态，"));
                lore.add(line("直到你再点一次关掉。"));
            } else if (button.asks) {
                lore.add(line("点完弹个" + ChatColor.YELLOW + "输入框" + ChatColor.GRAY + "填间隔，"));
                lore.add(line("单位是 tick（20 tick = 1 秒）。"));
            } else {
                lore.add(line("以最密的节奏持续做，"));
                lore.add(line("相当于一直按着不放。"));
            }
            lore.add(DIVIDER);
            lore.add(hint("左键", button.asks ? "设置间隔" : "开始"));
        }
        return item(button.icon(active),
                (active ? ChatColor.GREEN : ChatColor.WHITE) + button.label,
                lore);
    }

    // ------------------------------------------------------------------
    //  可用假人的小面板
    // ------------------------------------------------------------------

    private void openUnbound(Player player, int index) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            BotMenuHolder holder =
                    new BotMenuHolder(BotMenuHolder.Kind.UNBOUND, player.getUniqueId(), index);
            Inventory inv = Bukkit.createInventory(holder, UNBOUND_SIZE,
                    ChatColor.DARK_GREEN + "可用假人 " + service.fullName(player, index));
            holder.attach(inv);

            inv.setItem(UNBOUND_SPAWN, item(Material.GRASS_BLOCK,
                    ChatColor.GREEN + "生成假人 " + ChatColor.WHITE + "#" + index,
                    List.of(DIVIDER,
                            line("把号位 " + index + " 拉上线。"),
                            line(ChatColor.DARK_GRAY + "有存档就还原，没有就在你站着的位置新建。"),
                            line(ChatColor.DARK_GRAY + "生成出来的假人编号就是 " + index + "。"),
                            DIVIDER,
                            hint("左键", "生成"))));
            inv.setItem(UNBOUND_BACK, item(Material.ARROW,
                    ChatColor.YELLOW + "返回",
                    List.of(DIVIDER,
                            line("回到假人仓库。"),
                            DIVIDER,
                            hint("左键", "返回"))));

            player.openInventory(inv);
        });
    }

    // ------------------------------------------------------------------
    //  点击处理
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BotMenuHolder holder)) {
            return;
        }

        // 先无条件取消，再谈我们的逻辑。顺序反了的话，只要有一条路径没覆盖到
        // （Shift 点击、数字键、F 交换、双击、拖拽……）展示物品就会被抠出去。
        // 连玩家背包那一侧也一起取消：Shift 点击会把背包里的东西挪进菜单，等于把它当仓库用。
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!holder.owner().equals(player.getUniqueId())) {
            // 正常情况下不会发生，但菜单是持久对象，玩家可能已经换号或被踢过
            player.closeInventory();
            return;
        }
        // 点到下半截（玩家自己的背包）就到此为止
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            return;
        }
        // 只认左键。右键、中键一律当无事发生——否则同一个动作会触发两次购买。
        if (event.getClick() != ClickType.LEFT) {
            return;
        }

        switch (holder.kind()) {
            case MAIN -> onMainClick(player, event.getSlot());
            case UNBOUND -> onUnboundClick(player, holder.index(), event.getSlot());
            case BOT -> onBotClick(player, holder, event.getSlot());
            case CONFIRM_REMOVE -> onConfirmClick(player, holder.index(), event.getSlot());
        }
    }

    /**
     * 拖拽也要拦。只拦 {@link InventoryClickEvent} 的话，
     * 玩家能在菜单格子上拖一圈，把展示物品摊到光标上带走。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BotMenuHolder) {
            event.setCancelled(true);
        }
    }

    /**
     * 主菜单：格子下标就是号位。
     *
     * <p>每一格都重新判一次，不信菜单里摆着的东西——菜单开着的这段时间里，
     * 管理员可能把上限减了，假人也可能被打死回收了号位。
     */
    private void onMainClick(Player player, int slot) {
        int limit = registry.getLimit(player.getUniqueId());
        if (slot < limit) {
            int index = slot + 1;
            if (registry.owns(player.getUniqueId(), index)) {
                openBot(player, index);
            } else {
                openUnbound(player, index);
            }
        } else if (slot == limit) {
            buy(player, limit + 1);
        }
    }

    private void onUnboundClick(Player player, int index, int slot) {
        if (slot == UNBOUND_BACK) {
            openMain(player);
            return;
        }
        if (slot != UNBOUND_SPAWN) {
            return;
        }
        // 复核：上限可能被减了，号位也可能在别处已经被绑上了
        if (registry.getLimit(player.getUniqueId()) < index) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "[BotManager] 号位 " + index + " 已经不属于你了。");
            return;
        }
        if (registry.owns(player.getUniqueId(), index)) {
            openBot(player, index);
            return;
        }

        player.closeInventory();
        // 转交给 /bm spawn <编号> 原样跑一遍：消息文案、以及名字太长、撞真人名、
        // 全局上限这些边界情况那里都已经处理过了，不在这儿重写第二份。
        // 带上编号，所以生成出来的必然是玩家点的那个号位，不会跑到别处去。
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.dispatchCommand(player, "bm spawn " + index);
            openMain(player);   // 生成完回仓库，能看到那一格已经变成实例了
        });
    }

    private void onBotClick(Player player, BotMenuHolder holder, int slot) {
        int index = holder.index();
        if (registry.getLimit(player.getUniqueId()) < index) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "[BotManager] 号位 " + index + " 已经不属于你了。");
            return;
        }
        // 被遣散/打死后号位会解绑，这个格子应该退回"可用"，而不是继续当实例操作
        if (!registry.owns(player.getUniqueId(), index)) {
            openUnbound(player, index);
            return;
        }

        if (slot == BOT_BACK) {
            openMain(player);
            return;
        }

        if (slot == BOT_SUMMON) {
            if (service.isOnline(player, index)) {
                player.sendMessage(ChatColor.GRAY + "[BotManager] 它已经在线了。");
                return;
            }
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.dispatchCommand(player, "bm spawn " + index));
            return;
        }

        if (slot == BOT_RECALL) {
            if (!service.isOnline(player, index)) {
                player.sendMessage(ChatColor.GRAY + "[BotManager] 它不在线，不用收回。");
                return;
            }
            String full = service.fullName(player, index);
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (service.recall(player, index)) {
                    player.sendMessage(ChatColor.GREEN + "[BotManager] 已收回 " + full
                            + "，背包和坐标已存档，下次召唤原样回来。");
                } else {
                    player.sendMessage(ChatColor.RED + "[BotManager] 收回失败，详见服务端日志。");
                }
            });
            return;
        }

        if (slot == BOT_REMOVE) {
            openRemoveConfirm(player, index);
            return;
        }

        if (slot == BOT_STOP_ALL) {
            Player bot = botOf(player, index);
            if (bot == null) {
                player.sendMessage(ChatColor.GRAY + "[BotManager] 它不在线，没有动作可停。");
                return;
            }
            int stopped = leaves.stopAllActions(bot);
            if (stopped == 0) {
                player.sendMessage(ChatColor.GRAY + "[BotManager] 它本来就没在做什么。");
                return;
            }
            player.sendMessage(ChatColor.GREEN + "[BotManager] 已停掉全部 " + stopped + " 个动作。");
            refreshLater(player, holder);
            return;
        }

        for (ActionButton button : ActionButton.values()) {
            if (button.slot == slot) {
                onActionClick(player, index, holder, button);
                return;
            }
        }
    }

    /**
     * 点一个动作按钮。
     *
     * <p>按钮正亮着就是「关掉」，暗着就是「开起来」（或换成这个按钮代表的间隔）。
     * 开之前必须先停掉<b>同名</b>的旧动作：Leaves 的 {@code addBotAction} 是纯追加、
     * 不去重，不停干净的话假人身上会挂着两个 {@code attack}，一 tick 打两下。
     *
     * <p>要玩家现输间隔的按钮不在这里起动作——先弹对话框问，拿到数再回来起。
     */
    private void onActionClick(Player player, int index, BotMenuHolder holder, ActionButton button) {
        Player bot = botOf(player, index);
        if (bot == null) {
            player.sendMessage(ChatColor.GRAY + "[BotManager] 它不在线，先召唤出来再操作。");
            return;
        }

        boolean active = false;
        for (LeavesBridge.RunningAction running : leaves.runningActions(bot)) {
            if (button.isOn(running)) {
                active = true;
                break;
            }
        }

        if (active) {
            int stopped = leaves.stopActionsNamed(bot, button.action);
            player.sendMessage(stopped > 0
                    ? ChatColor.GREEN + "[BotManager] 已停止「" + button.label + "」。"
                    : ChatColor.GRAY + "[BotManager] 它已经停了。");
            refreshLater(player, holder);
            return;
        }

        if (button.asks) {
            askInterval(player, index, holder, button);
            return;
        }

        // 起新的之前先把同名的旧动作停干净。这一句不能省：「长按左键」和「间歇左键」
        // 在 Leaves 那边是同一个 attack，从间歇换成一直按的时候按钮是暗的（走到了这条分支），
        // 不停旧的就会变成两个 attack 同时跑，一 tick 打两下。
        leaves.stopActionsNamed(bot, button.action);
        // 状态型动作（潜行）不带参数；其余按 delay=0、固定间隔、按钮自带的次数起
        startAction(player, bot, button, button.fixedInterval);
        refreshLater(player, holder);
    }

    /**
     * 问玩家间隔多少 tick，拿到之后再起这个动作。
     *
     * <p>拿到数才停旧动作：玩家输到一半改主意了，不该把他原来在跑的那个也弄没了。
     */
    private void askInterval(Player player, int index, BotMenuHolder holder, ActionButton button) {
        DialogPrompt.Handler handler = (who, text) -> {
            Integer interval = parseInterval(text);
            if (interval == null) {
                // 非 null = 没收下，这句话会画在对话框里，他原地改个数重按确定就行
                return "得填 " + INTERVAL_MIN + " 到 " + INTERVAL_MAX
                        + " 之间的整数（tick），你填的是「" + text + "」。";
            }
            // 玩家可能在这期间走开了、假人被收回了，起之前重新确认一遍
            Player target = botOf(who, index);
            if (target == null) {
                who.sendMessage(ChatColor.GRAY + "[BotManager] 它已经不在线了，没起成。");
                return null;
            }
            // 到这一步才停旧的（不是在弹输入的时候）：玩家输到一半改主意跑了，
            // 不该把他原来在跑的那个也弄没了。
            leaves.stopActionsNamed(target, button.action);
            startAction(who, target, button, interval);
            refreshLater(who, holder);
            return null;
        };

        // 先把界面关了再弹：菜单还开着的话对话框会盖在它上面，玩家关掉对话框就退回菜单，
        // 动作到底起没起看不出来。
        player.closeInventory();
        prompt.ask(player, "给「" + button.label + "」设一个间隔，单位是 tick（20 tick = 1 秒）。", handler);
    }

    /** 真的去起动作。{@code action} 名和参数原样交给 Leaves 解析。 */
    private void startAction(Player player, Player bot, ActionButton button, int interval) {
        if (button.isState()) {
            plugin.runBotAction(player, bot, "start", button.action);
        } else {
            // delay=0 立刻开始；做几次由按钮自己带（-1 一直做 / 1 做一次就收手）
            plugin.runBotAction(player, bot, "start", button.action,
                    "0", String.valueOf(interval), String.valueOf(button.doNumber));
        }
    }

    /**
     * 解析玩家输的间隔。
     *
     * <p>只收纯十进制整数。不接 {@code 1e3} / {@code 0x10} / 带小数点这些写法——
     * 玩家真想输 1000 会直接打 1000，接受花式写法只会让"我明明输了 5 怎么变 5000"更难解释。
     */
    private static Integer parseInterval(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= INTERVAL_MIN && value <= INTERVAL_MAX ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 延后两 tick 再刷新界面。
     *
     * <p>{@code stopAction} 只是把动作标记成已取消，真正从列表里摘掉要等下一 tick
     * 由 {@code ServerBot} 统一 {@code removeIf(isCancelled)}。立刻刷新的话，
     * 刚停掉的那个动作还在列表里，按钮会亮回去——看起来像没点动。
     */
    private void refreshLater(Player player, BotMenuHolder holder) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder() == holder) {
                refreshBot(player, holder);
            }
        }, 2L);
    }

    /** 取号位对应的在线假人，不在线返回 null。 */
    private Player botOf(Player owner, int index) {
        return leaves.findByRawName(BotService.rawName(owner, index));
    }

    // ------------------------------------------------------------------
    //  移除假人的二次确认
    // ------------------------------------------------------------------

    /**
     * 确认面板。
     *
     * <p>移除是<b>不可撤销</b>的——东西掉一地、存档一起清掉、编号随即被回收，
     * 补到同一编号再召回来的是个全新的空假人。操作面板上「收回」和「移除」还挨着，
     * 手滑一下代价太大，所以单独拦一道。
     */
    private void openRemoveConfirm(Player player, int index) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            BotMenuHolder holder =
                    new BotMenuHolder(BotMenuHolder.Kind.CONFIRM_REMOVE, player.getUniqueId(), index);
            Inventory inv = Bukkit.createInventory(holder, CONFIRM_SIZE,
                    ChatColor.DARK_RED + "确认移除 " + service.fullName(player, index));
            holder.attach(inv);

            inv.setItem(CONFIRM_BOT, buildBotHead(player, index, service.isOnline(player, index),
                    List.of()));

            inv.setItem(CONFIRM_YES, item(Material.RED_CONCRETE,
                    ChatColor.RED + "确认移除",
                    List.of(DIVIDER,
                            line("把这个假人" + ChatColor.YELLOW + "遣散" + ChatColor.GRAY + "掉："),
                            line(ChatColor.RED + "· 背包里的东西掉在它脚下"),
                            line(ChatColor.RED + "· 存档一起清除，召不回来"),
                            DIVIDER,
                            line("号位仍然归你，"
                                    + ChatColor.GREEN + "上限也不会减少" + ChatColor.GRAY + "。"),
                            line("那一格会变回「可用假人」。"),
                            "",
                            line(ChatColor.DARK_GRAY + "只想让它下线、把东西留着？"),
                            line(ChatColor.DARK_GRAY + "那是「收回假人」，点取消回去用它。"),
                            DIVIDER,
                            hint("左键", "确认移除"))));

            inv.setItem(CONFIRM_NO, item(Material.LIME_CONCRETE,
                    ChatColor.GREEN + "取消",
                    List.of(DIVIDER,
                            line("什么都不做，回到操作面板。"),
                            DIVIDER,
                            hint("左键", "返回"))));

            player.openInventory(inv);
        });
    }

    private void onConfirmClick(Player player, int index, int slot) {
        if (slot == CONFIRM_NO) {
            openBot(player, index);
            return;
        }
        if (slot != CONFIRM_YES) {
            return;
        }
        // 复核：菜单开着的这段时间里假人可能已经被收回了
        if (registry.getLimit(player.getUniqueId()) < index
                || !registry.owns(player.getUniqueId(), index)) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "[BotManager] 号位 " + index + " 已经不属于你了。");
            return;
        }

        player.closeInventory();
        // 转交给 /bm remove 原样跑一遍：文案、离线假人清残留登记这些分支那里都处理过了。
        // 那条路要 botmanager.spawn，而能打开这个菜单的人必然有（openMain 已经查过）。
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.dispatchCommand(player, "bm remove " + index));
    }

    // ------------------------------------------------------------------
    //  购买
    // ------------------------------------------------------------------

    /**
     * 买下号位 {@code index}，即把上限 +1。
     *
     * <p>因为购买按钮永远只出现在"最后一个号位之后"，它代表的就是下一个号位，
     * 所以"一次加一个"和"点哪买哪"是同一件事，不存在跳号多收钱的问题。
     *
     * <p>收款金额现算（{@link #priceFor}），不信界面上印的价——
     * 菜单开着的时候管理员可能把价格表改了。
     *
     * @param index 这次要解锁的号位，等于购买时的上限 +1
     */
    private void buy(Player player, int index) {
        UUID id = player.getUniqueId();
        int before = registry.getLimit(id);
        if (before + 1 != index) {
            // 上限在菜单开着的时候被改过，按钮指向的已经不是下一个号位了。
            // 刷新一下让界面跟上，别照着过期报价收钱。
            refreshMain(player);
            return;
        }

        int cap = maxSlots();
        if (index > cap) {
            // 界面上那一格已经是"已达上限"的牌子、点不动了，走到这儿只可能是
            // 菜单开着的时候管理员把 max-slots 调小了。不扣钱，刷一下让界面跟上。
            player.sendMessage(ChatColor.RED + "[BotManager] 号位最多买到第 " + cap
                    + " 个，你已经到顶了。");
            refreshMain(player);
            return;
        }

        if (!isPricingConfigured()) {
            player.sendMessage(ChatColor.RED + "[BotManager] 号位价格配置读取失败，暂时停售，"
                    + "请联系管理员检查 config.yml（服务端日志里有 YAML 报错行号）。");
            return;
        }

        double price = priceFor(index);
        if (price > 0 && !economy.isReady()) {
            player.sendMessage(ChatColor.RED
                    + "[BotManager] 服务器没有接入经济插件（Vault），暂时买不了号位。");
            return;
        }
        if (price > 0 && !economy.withdraw(player, price)) {
            player.sendMessage(ChatColor.RED + "[BotManager] 余额不足，买下号位 " + index
                    + " 需要 " + economy.format(price) + "。");
            return;
        }

        // 必须走控制台派发。玩家自己派发的话，/bm limit 那条路要 botmanager.admin，
        // 普通玩家会被权限拦下，钱扣了号位却开不出来。
        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "bm limit add " + player.getName() + " 1");

        // dispatchCommand 的返回值只说明"这条命令被找到了"，不代表真的改成功。
        // 复核一遍实际结果，没开成就退款——不能收了钱不给货。
        if (!dispatched || registry.getLimit(id) <= before) {
            if (price > 0) {
                economy.deposit(player, price);
            }
            player.sendMessage(ChatColor.RED + "[BotManager] 购买失败，费用已退回。");
            plugin.getLogger().warning("为 " + player.getName() + " 购买号位 " + index
                    + " 失败（dispatched=" + dispatched + "，上限仍为 " + registry.getLimit(id) + "）"
                    + (price > 0 ? "，已退款 " + price + "。" : "。"));
            return;
        }

        player.sendMessage(ChatColor.GREEN + "[BotManager] 已买下号位 " + index
                + (price > 0 ? "，花费 " + economy.format(price) : "")
                + "。它现在是「可用假人」了。");

        refreshMain(player);
    }

    // ------------------------------------------------------------------
    //  小工具
    // ------------------------------------------------------------------

    /**
     * config.yml 的价格段到底读进来没有。
     *
     * <p>{@code config.yml} 只要有 YAML 语法错误（多一个缩进、漏一个引号、列表项没对齐），
     * Bukkit <b>不会</b>让插件启动失败，它只往日志里打一行错、然后把整份配置当空的用。
     * 对别的开关来说，最坏结果是"保护没生效"；但价格读空的后果是
     * <b>每一档都算成 0 ⇒ 号位全部免费</b>，玩家能白拿。
     *
     * <p>所以这里把「键在不在」和「值是多少」分开判：
     * 键不在 = 配置没读进来（本插件自带的 config.yml 一定带这两个键），一律停售；
     * 键在而值是 0 = 管理员有意配成免费，正常放行。
     */
    private boolean isPricingConfigured() {
        return plugin.getConfig().isSet("menu.price-per-slot")
                || plugin.getConfig().isSet("menu.price-list");
    }

    /**
     * 菜单里最多能买到第几个号位（{@code menu.max-slots}）。
     *
     * <p><b>只管菜单购买这一条路。</b>管理员用 {@code /bm limit add} 照样能把上限抬到更高——
     * 那是管理操作，不是消费行为，插件不该拦。抬过头之后玩家的号位照常能用，
     * 只是菜单里的购买按钮一直显示「已达购买上限」。
     *
     * <p>下限夹到 1：配成 0 或负数会让菜单一个都不卖，那多半是手滑，
     * 而"我就是要停售"有更明确的表达方式（价格停售那条路）。
     */
    private int maxSlots() {
        return Math.max(1, plugin.getConfig()
                .getInt("menu.max-slots", MAX_SLOTS_DEFAULT));
    }

    /**
     * 号位 {@code slot}（从 1 数）卖多少钱。
     *
     * <p>两级来源，价格表优先：
     * <ol>
     *   <li>{@code menu.price-list} 里的第 {@code slot} 项；超出表长时，
     *       拿最后一项继续按 {@code menu.price-growth} 往上乘；</li>
     *   <li>没配表时，以 {@code menu.price-per-slot} 为第 1 档底价，同样按倍数往上乘。</li>
     * </ol>
     * 所以「50000 / 100000 / 200000」既可以写成三行价格表，
     * 也可以写成 {@code price-per-slot: 50000} + {@code price-growth: 2.0}，两种等价。
     *
     * <p><b>价格跟着号位走，不跟着"买过几次"走。</b>号位就是假人编号，
     * 所以第 3 格永远是第 3 档价。副作用是管理员用 {@code /bm limit add} 白送的上限
     * 也会占掉前几档——白送 3 个之后，玩家第一次掏钱买的是第 4 格、第 4 档价。
     */
    private double priceFor(int slot) {
        List<Double> table = plugin.getConfig().getDoubleList("menu.price-list");
        if (!table.isEmpty()) {
            if (slot <= table.size()) {
                return Math.max(0.0D, table.get(slot - 1));
            }
            return grow(Math.max(0.0D, table.get(table.size() - 1)), slot - table.size());
        }
        return grow(Math.max(0.0D, plugin.getConfig()
                .getDouble("menu.price-per-slot", 0.0D)), slot - 1);
    }

    /**
     * 从 {@code from} 起连乘 {@code steps} 次 {@code menu.price-growth}。
     *
     * <p>倍数被夹到不小于 1——本项只用来涨价，写 0.5 之类的降序
     * 会让后面越买越便宜，多半是配错了，不如按不涨处理。
     */
    private double grow(double from, int steps) {
        double growth = Math.max(1.0D, plugin.getConfig()
                .getDouble("menu.price-growth", 1.0D));
        double value = (steps <= 0 || growth == 1.0D)
                ? from
                : from * Math.pow(growth, steps);
        if (!Double.isFinite(value) || value > PRICE_CAP) {
            return PRICE_CAP;
        }
        return Math.max(0.0D, value);
    }

    /**
     * 紧凑金额：`50000` / `10.0万` / `1.2亿`。
     *
     * <p>只给启动日志用——日志是管理员在控制台读的，一屏能看完整条价格阶梯最要紧，
     * 中文数量级在这里正合适。<b>菜单里不要用它</b>：玩家在界面上看到的金额，
     * 必须和钱包里的一致，也就是一律走 {@link VaultEconomy#format}。
     */
    private static String compact(double value) {
        if (value >= 100_000_000D) {
            return String.format("%.1f亿", value / 100_000_000D);
        }
        if (value >= 10_000D) {
            return String.format("%.1f万", value / 10_000D);
        }
        return String.format("%.0f", value);
    }

    // ---- lore 排版小工具 ----
    //
    // 之前每条 lore 都是整段一个颜色，玩家得逐字读完才知道哪个是状态、哪个是价钱。
    // 统一成三种行：kv（标签+值）、line（正文）、hint（末尾的操作提示），
    // 标签一律压成暗灰，值用各自的语义色，眼睛能直接跳到值上。

    /**
     * 一条「标签 值」。标签固定两字，所以竖着对得齐。
     *
     * <p>前导空格是故意的——lore 顶格写会和物品名的左边缘齐平，看着像标题。
     */
    private static String kv(String label, String value) {
        return ChatColor.DARK_GRAY + " " + label + " " + value;
    }

    /** 一条正文说明。 */
    private static String line(String text) {
        return ChatColor.GRAY + " " + text;
    }

    /** 一条要照着敲的命令原文：亮白，跟周围的灰正文拉开，免得看成一坨。 */
    private static String cmd(String text) {
        return ChatColor.WHITE + " " + text;
    }

    /**
     * 末尾的操作提示：金色箭头起头、动作词亮黄、其余压灰。
     *
     * <p>所有可点的格子都用它收尾，位置固定，玩家扫一眼最底下就知道能干什么。
     */
    private static String hint(String key, String tail) {
        return ChatColor.GOLD + " ▶ " + ChatColor.YELLOW + key
                + (tail.isEmpty() ? "" : ChatColor.GRAY + " " + tail);
    }

    /** 状态点：在线/运行中 = 绿实心，离线/未启用 = 暗灰空心。 */
    private static String dot(boolean on, String text) {
        return (on ? ChatColor.GREEN + "● " + ChatColor.GREEN
                   : ChatColor.DARK_GRAY + "○ " + ChatColor.DARK_GRAY) + text;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
