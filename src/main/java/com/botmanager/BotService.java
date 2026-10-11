package com.botmanager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.lang.reflect.Method;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 假人生命周期：召唤、遣散、以及随玩家上下线的同步。
 *
 * <p>命令层（{@link BotManager}）和事件层都走这里，业务逻辑只写一份。
 * 本类同时充当 {@link Listener}，直接处理上下线事件。
 */
public final class BotService implements Listener {

    /** Leaves 的假人移除事件。用字符串引用，避免编译期依赖具体 Leaves 版本。 */
    private static final String BOT_REMOVE_EVENT = "org.leavesmc.leaves.event.bot.BotRemoveEvent";

    /** 召唤结果。命令层负责把每种结果翻译成人话。 */
    public enum SummonResult {
        /** 全新创建，出现在玩家脚下。 */
        CREATED,
        /** 从存档还原，回到下线时的位置、带着原来的背包。 */
        RESTORED,
        /** 该编号的假人已经在线了（重复召唤，或管理员手工造的同名假人）。 */
        ALREADY_ONLINE,
        /** {@code 1..上限} 已经全被占满，没空位了。 */
        LIMIT_REACHED,
        /** 指定的编号超出 {@code 1..上限}。 */
        INDEX_OUT_OF_RANGE,
        /** 全名超过 Leaves 的 16 字符限制。 */
        NAME_TOO_LONG,
        /** 该名字正被一个真实玩家使用，造出来会被 Leaves 连人带背包清掉。 */
        NAME_TAKEN_BY_PLAYER,
        /** 撞上 Leaves 的全服假人上限。 */
        GLOBAL_LIMIT_REACHED,
        /** 其它失败。 */
        FAILED
    }

    private final BotManager plugin;
    private final LeavesBridge leaves;
    private final BotRegistry registry;

    private Method removeReasonMethod;
    private Method removeBotMethod;
    private boolean deathWatcherReady;

    public BotService(BotManager plugin, LeavesBridge leaves, BotRegistry registry) {
        this.plugin = plugin;
        this.leaves = leaves;
        this.registry = registry;
    }

    /**
     * 一次召唤的结果。
     *
     * <p>编号由 {@link #summon} 内部按顺序分配，调用方事先不知道，所以把用到的
     * 编号和全名一并带回来供消息使用。
     */
    public record SummonOutcome(SummonResult result, int index, String fullName) {
        public boolean ok() {
            return result == SummonResult.CREATED || result == SummonResult.RESTORED;
        }
    }

    /** 玩家假人在 Leaves 里的 raw name：{@code <玩家名>.<编号>}，前后缀由 Leaves 自己加。 */
    public static String rawName(Player owner, int index) {
        return owner.getName() + LeavesBridge.SEPARATOR + index;
    }


    /** 该 raw name 在 Leaves 里的全名，例如 {@code bot_test_1}。 */
    public String fullName(Player owner, int index) {
        return leaves.fullName(rawName(owner, index));
    }

    // ------------------------------------------------------------------
    //  命令入口
    // ------------------------------------------------------------------

    /**
     * 召唤一个新假人，编号自动分配——从 1 开始找**第一个**没用上的号。
     *
     * <p>举例：已有 {@code test.1}、{@code test.5}、{@code test.6} 时，这次得到 {@code test.2}。
     * 跳号是允许的（{@code /bm spawn 5} 能直接开 5 号），所以补空号不是"补到连续的号"，
     * 而是"补上能用的最小号"。
     */
    public SummonOutcome summon(Player owner) {
        UUID id = owner.getUniqueId();
        int limit = registry.getLimit(id);
        Set<Integer> online = onlineIndices(owner);
        int index = registry.findFreeIndex(id, limit, online::contains);
        if (index < 0) {
            // 全部号位占满。用 -1 回传：调用方拿不到具体编号，只能报"满了"。
            return new SummonOutcome(SummonResult.LIMIT_REACHED, -1, "");
        }
        return summon(owner, index);
    }

    /**
     * 召唤指定编号的假人，编号必须在 {@code 1..上限} 之内。
     *
     * <p>上限同时是"最多几个假人"和"能用哪些编号"，所以编号合法就等于数量没超，
     * 不需要再单独数一遍。
     */
    public SummonOutcome summon(Player owner, int index) {
        UUID id = owner.getUniqueId();
        String raw = rawName(owner, index);
        String full = leaves.fullName(raw);

        // ---- 预检：全部通过之前不写任何登记 ----

        if (!registry.isIndexAllowed(id, index)) {
            return new SummonOutcome(SummonResult.INDEX_OUT_OF_RANGE, index, full);
        }
        // 该编号已经有假人在线：重复召唤，或者是管理员手工 /bot create 出来的。
        // 两种都收编到名下——在线的东西没法再"重新创建"，玩家要的是它已经是他的了。
        if (isOnline(owner, index)) {
            registry.addOwned(id, index);
            return new SummonOutcome(SummonResult.ALREADY_ONLINE, index, full);
        }
        if (!leaves.isFullNameLegal(full)) {
            return new SummonOutcome(SummonResult.NAME_TOO_LONG, index, full);
        }
        if (nameTakenByRealPlayer(full)) {
            return new SummonOutcome(SummonResult.NAME_TAKEN_BY_PLAYER, index, full);
        }
        // 不查 registry 里有没有登记过：登记了但不在线（例如服务器刚重启）时本就该重新拉起来。
        // Leaves 的全局上限读不到时返回 -1，这里跳过预检，交给 spawn 失败兜底。
        int globalLimit = leaves.getGlobalLimit();
        if (globalLimit > 0 && leaves.listBots().size() >= globalLimit) {
            return new SummonOutcome(SummonResult.GLOBAL_LIMIT_REACHED, index, full);
        }

        // ---- 开始创建，到这里编号就占下了 ----

        // 先试存档还原：有存档就能拿回背包、位置、血量和正在执行的动作
        Player bot = leaves.loadSaved(full);
        boolean restored = bot != null;
        if (bot == null) {
            // loadSaved 的副作用就是「让假人上线」：可能已经拉起来了，只是没能返回句柄
            // （见 LeavesBridge.loadSaved 的说明）。不补这一刀就会再造一个同名的假人出来。
            bot = leaves.findByRawName(raw);
            restored = bot != null;
        }
        if (bot == null) {
            bot = leaves.spawn(raw, owner.getLocation(), owner);
        }
        if (bot == null) {
            return new SummonOutcome(SummonResult.FAILED, index, full);
        }

        registry.addOwned(id, index);
        return new SummonOutcome(restored ? SummonResult.RESTORED : SummonResult.CREATED, index, full);
    }

    /** 该编号的假人是否在线。收编判定和 /bm list 共用，保证口径一致。 */
    public boolean isOnline(Player owner, int index) {
        return leaves.findByRawName(rawName(owner, index)) != null;
    }

    // ------------------------------------------------------------------
    //  假人被打死时回收号位
    // ------------------------------------------------------------------

    /**
     * 监听 Leaves 的假人移除事件，把**被打死**的假人占的号位释放掉。
     *
     * <h2>为什么需要它</h2>
     * 假人被打死时 Leaves 直接移除它（{@code ServerBot#die} →
     * {@code removeBot(this, DEATH, null, false, false)}），插件收不到任何通知，
     * 所以 {@code bots.yml} 里的编号会一直留着——号位被一个死掉的假人占住，
     * {@code /bm spawn} 不会补到它，玩家只能自己 {@code /bm remove <编号>} 清。
     *
     * <h2>为什么不掉东西这件事不用管</h2>
     * 死亡传的是 {@code save=false}，Leaves 会走 {@code dropExperience(); dropAll(true)}
     * ——**物品已经掉在原地了**，和 {@code /bm remove} 的行为完全一致。
     * 插件的 {@link #dismiss} 之所以要显式用"不存档"的移除，就是冲着这个一致性去的。
     *
     * <h2>为什么只认 DEATH</h2>
     * 同一个事件有四种 reason，另外三种都不能动号位：
     * <ul>
     *   <li>{@code PLUGIN}——插件自己的上下线同步（{@link #dismissAll}）。
     *       清了号位，玩家下次上线就不会还原假人了，背包直接丢。</li>
     *   <li>{@code INTERNAL}——关服 {@code removeAll()}、玩家登录撞名。
     *       同理，号位必须留着等还原。</li>
     *   <li>{@code COMMAND}——管理员 {@code /bot remove}。算外部干预，
     *       不做假设更安全，玩家可用 {@code /bm remove} 自己清。</li>
     * </ul>
     *
     * @return 是否注册成功。失败时功能退化为"号位不自动回收"，其余一切照常。
     */
    public boolean registerDeathWatcher() {
        try {
            Class<?> raw = Class.forName(BOT_REMOVE_EVENT);
            if (!Event.class.isAssignableFrom(raw)) {
                return false;
            }
            this.removeReasonMethod = raw.getMethod("getReason");
            this.removeBotMethod = raw.getMethod("getBot");
            // MONITOR：只观察既成事实，不参与"要不要移除"的决策。
            // ignoreCancelled 传 true——被取消的移除等于没发生，不该回收号位。
            Bukkit.getPluginManager().registerEvent(
                    raw.asSubclass(Event.class), this, EventPriority.MONITOR,
                    this::handleBotRemove, plugin, true);
            this.deathWatcherReady = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 死亡号位回收是否已生效。false 时玩家需要自己 {@code /bm remove} 清理。 */
    public boolean isDeathWatcherReady() {
        return deathWatcherReady;
    }

    private void handleBotRemove(Listener listener, Event event) {
        try {
            if (!"DEATH".equals(String.valueOf(removeReasonMethod.invoke(event)))) {
                return;
            }
            // getBot() 返回 Bot，而 Bot extends Player
            if (!(removeBotMethod.invoke(event) instanceof Player dead)) {
                return;
            }
            String ownerName = ownerNameOf(dead);
            String raw = leaves.rawNameOf(dead);
            int index = LeavesBridge.indexOf(ownerName, raw);
            if (index <= 0) {
                return; // 不是本插件的命名（手工 /bot create 的），不碰
            }
            UUID id = ownerIdOf(ownerName);
            if (id == null) {
                // 反推不出主人 UUID。宁可不动 registry——号位卡住还有 /bm remove 可救，
                // 但改错账号的账本没法救。
                plugin.getLogger().warning("假人 " + dead.getName()
                        + " 被打死，但查不到主人 " + ownerName + " 的档案，号位 " + index + " 未回收。");
                return;
            }
            // 死了就是真没了（掉一地、Leaves 不写存档），但自动存档可能刚替它写过名单条目。
            // 不清掉的话，下次上线它会被还原连背包一起复活——而东西已经掉在地上了。
            leaves.purgeSaved(raw);
            registry.removeOwned(id, index);
            plugin.getLogger().info("假人 " + dead.getName() + " 被打死，号位 " + index
                    + " 已从 " + ownerName + " 名下回收。");

            Player owner = Bukkit.getPlayerExact(ownerName);
            if (owner != null) {
                owner.sendMessage(ChatColor.YELLOW + "[BotManager] 你的假人 " + dead.getName()
                        + " 被打死了，编号 " + index + " 已空出来。");
                owner.sendMessage(ChatColor.GRAY + "  背包里的东西掉在原地了，"
                        + "记得去捡；/bm spawn 可以把它重新召唤出来（空背包）。");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("处理假人移除事件失败：" + t);
        }
    }

    /**
     * 主人名 → UUID。
     *
     * <p>刻意用 {@code getOfflinePlayerIfCached} 而不是 {@code getOfflinePlayer(String)}：
     * 后者在正版模式下遇到缓存里没有的名字会**阻塞主线程**去查 Mojang 的 API。
     * 这里在假人死亡的事件里跑，绝不能有网络等待。查不到就返回 null，由调用方放弃回收。
     */
    private UUID ownerIdOf(String name) {
        if (name == null) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    /**
     * 该玩家名下**当前在线**的编号集合。
     *
     * <p>给"找空号"这类要逐个编号判定的场景用：一次遍历拿全，避免每个编号都反射一遍
     * {@code listBots()}——Tab 补全每次按键都会走这条路径，平方级的开销没必要。
     */
    public Set<Integer> onlineIndices(Player owner) {
        Set<Integer> result = new HashSet<>();
        for (Player bot : leaves.botsOf(owner.getName())) {
            // botsOf 已经按同一个口径过滤过归属，这里的编号必定属于该玩家
            int index = LeavesBridge.indexOf(owner.getName(), leaves.rawNameOf(bot));
            if (index > 0) {
                result.add(index);
            }
        }
        return result;
    }

    /**
     * 遣散假人。**真删**：不写存档，背包里的东西掉在原地，编号随即被回收。
     *
     * <p>与上下线同步（{@link #dismissAll}）是两回事——那个用存档保留，
     * 因为假人只是跟着主人离线；这个是玩家主动不要了，留着存档只会在补到同一编号时
     * 把删掉的假人连背包一起复活。
     *
     * @return true 表示确实有假人在线并被移除
     */
    public boolean dismiss(Player owner, int index) {
        Player bot = leaves.findByRawName(rawName(owner, index));
        if (bot == null) {
            // 不在线也允许清掉登记，否则服务器重启后残留的记录会把号位永久占住
            registry.removeOwned(owner.getUniqueId(), index);
            return false;
        }
        boolean removed = leaves.removeDiscardingSave(bot);
        if (removed) {
            // 真删是「这个假人不要了」，东西已经掉在地上了；但自动存档可能刚替它写过名单条目，
            // 不清掉的话下次登录会把它连背包一起复活，等于凭空多一份。
            leaves.purgeSaved(rawName(owner, index));
            registry.removeOwned(owner.getUniqueId(), index);
        }
        return removed;
    }

    /**
     * 收回假人：**存档并下线**，登记保留。
     *
     * <p>和 {@link #dismiss} 是两回事，别混——那个是真删（不写存档、背包掉在原地、号位随即回收），
     * 这个是「先不玩了」：背包、坐标、血量都写进存档，号位还留着，
     * 下次 {@code /bm spawn} 或玩家上线时原样还原。
     *
     * <p>玩家下线时的 {@link #dismissAll} 走的也是这条语义，
     * 所以这里不用动 registry —— 保留登记正是"下次还能还原"的前提。
     *
     * @return true 表示确实有假人在线并被收回
     */
    public boolean recall(Player owner, int index) {
        Player bot = leaves.findByRawName(rawName(owner, index));
        if (bot == null) {
            return false;
        }
        return leaves.saveAndRemove(bot);
    }

    // ------------------------------------------------------------------
    //  周期性自动存档
    // ------------------------------------------------------------------

    /**
     * 启动周期性自动存档。
     *
     * <p>目的只有一个：**扛住进程被强杀**（kill -9 / 断电 / OOM）。
     * 正常 {@code /stop} 由 {@link #preSaveForShutdown} 兜底，不靠这里；
     * 这里管的是「没有任何代码有机会跑」的那种死法。
     *
     * <p>间隔读 {@code bot.autosave-interval-minutes}，0 或负数表示关闭。
     * 接不上 Leaves 的存档写入口时也不启动——不能假装存上了。
     */
    public void startAutosave() {
        long minutes = plugin.getConfig().getLong("bot.autosave-interval-minutes", 5L);
        if (minutes <= 0) {
            plugin.getLogger().info("假人自动存档已关闭（bot.autosave-interval-minutes 为 0）。");
            return;
        }
        if (!leaves.canAutosave()) {
            plugin.getLogger().warning("假人自动存档未启用：接不上 Leaves 的存档写入口，"
                    + "进程被强杀时假人会回退到上次落盘的状态。");
            return;
        }
        long ticks = minutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::autosaveAll, ticks, ticks);
        plugin.getLogger().info("假人自动存档已启用：每 " + minutes + " 分钟一次，"
                + "进程被强杀时最多回退这个间隔的进度。");
    }

    /**
     * 把当前在线的全部假人写进磁盘。**不下线、不掉东西、不解除登记。**
     *
     * <p>和 {@link #saveAllOnline()} 的区别就在这儿：那个是「存档**并移除**」，给关服用；
     * 这个是「只存档」，假人继续在世界里干活。
     *
     * <p>失败不抛异常、不打断其他假人：{@code autosave} 自己吞掉异常并只报一次日志，
     * 这里照常往下走，能存几个是几个。
     */
    public void autosaveAll() {
        List<Player> bots = leaves.listBots();
        if (bots.isEmpty()) {
            return;
        }
        int count = 0;
        for (Player bot : bots) {
            if (leaves.autosave(bot)) {
                count++;
            }
        }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[调试] 自动存档 " + count + "/" + bots.size() + " 个假人。");
        }
    }

    // ------------------------------------------------------------------
    //  撞名防护
    // ------------------------------------------------------------------

    /**
     * 假冒人名的玩家登录时，抢在 Leaves 前面把假人存下来。
     *
     * <h2>为什么必须卡在登录这一层</h2>
     * Leaves 在 {@code PlayerList.placeNewPlayer} 里，发现进服玩家与在线假人同名时：
     *
     * <pre>
     * removeBot(bot, RemoveReason.INTERNAL, player, save=false, resume=false)
     * </pre>
     *
     * {@code save=false} 会走到 {@code bot.dropExperience(); bot.dropAll(true);}
     * ——**假人背包里的东西直接掉一地**，而且不写任何存档。
     *
     * <p>更麻烦的是拦不住：{@code removeBot} 的取消检查是
     * {@code if (event.isCancelled() && reason != INTERNAL)}，
     * 而这里恰恰是 INTERNAL，**取消 BotRemoveEvent 也没用**。
     *
     * <p>所以唯一的插手时机在 {@code placeNewPlayer} **之前**，也就是本事件。
     * 这里先自己存档移除假人，等 Leaves 走到那段代码时 {@code getBotByName} 已经查不到它，
     * 自然也就不会掉东西了。
     *
     * <h2>关于"权限被夺走"</h2>
     * 不会。假人 UUID 是 {@code nameUUIDFromBytes("Fakeplayer:" + 全名)}，真实玩家的 UUID
     * 要么是 Mojang 分配的，要么是 {@code nameUUIDFromBytes("OfflinePlayer:" + 名字)}——
     * 前缀不同，**哈希结果必然不同**。Residence 权限按 UUID 存，所以授予假人的领地权限
     * 不会落到这个玩家头上。
     */
    /**
     * 名字抢注兜底的监听器，**由调用方决定要不要注册**。
     *
     * <p>命名空间隔离生效时（假人名含 {@code .}，见 {@link LeavesBridge#SEPARATOR}）
     * 真人根本注册不到那种名字，碰撞不可能发生，这个兜底纯属多余。
     * 而 Leaves 只要发现有插件监听 {@code PlayerLoginEvent}，就会打
     * {@code [HorriblePlayerLoginEventHack]} 警告并关掉一部分重配置 API——
     * 能免则免，所以隔离生效时干脆不注册。
     *
     * <p>只有在 SEPARATOR 被改回 {@code _} 这种隔离失效的情况下它才有意义。
     */
    public Listener loginFallback() {
        return new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onLogin(PlayerLoginEvent event) {
                handleLogin(event);
            }
        };
    }

    /**
     * 命名空间闸口：在**登录阶段**把「&lt;玩家名&gt;.&lt;编号&gt;」这种形状的名字挡在门外。
     *
     * <p>命名空间隔离依赖「真人注册不到带点的名字」，而那条规则**只有正版验证服
     * （{@code online-mode=true}）成立**——那时名字由 Mojang 会话背书。
     * 离线服上 {@code ^[a-zA-Z0-9_]{3,16}$} 只是原版客户端输入框的限制，
     * 改过的客户端想叫什么就叫什么（2026-09-15 真机实测确认）。
     *
     * <p>一旦真人顶掉一个在线假人，Leaves 走的是
     * {@code removeBot(bot, INTERNAL, player, save=false, resume=false)}
     * （见 {@link #loginFallback} 里的分析）：假人背包**整袋掉在原地**，
     * 位置血量全丢，主人还收不到任何提醒。等它跑到那一步再补救已经晚了，
     * 必须抢在 {@code placeNewPlayer} 之前——也就是登录阶段。
     *
     * <p><b>为什么用 {@code AsyncPlayerPreLoginEvent} 而不是 {@code PlayerLoginEvent}</b>：
     * 前者更早、是异步的，而且**不触发** Paper 的 {@code [HorriblePlayerLoginEventHack]}；
     * 那条警告会关掉一部分重配置 API。能免则免。
     *
     * <p><b>为什么只拦 {@link LeavesBridge#isOurName} 认得的形状</b>，
     * 而不是「名字里带点就拒」：Geyser / Floodgate 给基岩版玩家的名字前缀正是 {@code .}，
     * 一刀切会把**所有基岩玩家**挡在门外。而 {@code .Steve}、{@code Steve.abc}
     * 这种名字本来就撞不上假人（假人编号恒为纯数字），没有拦的必要。
     *
     * <p>调用方只在 {@link LeavesBridge#isNamespaced()} 为真时注册——
     * 分隔符要是被改回 {@code _}，这个形状会开始匹配普通玩家名（{@code my_player_1}），
     * 那就成了误伤。
     */
    public Listener namespaceGuard() {
        return new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onPreLogin(AsyncPlayerPreLoginEvent event) {
                String name = event.getName();
                if (!leaves.isOurName(name)) {
                    return;
                }
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        ChatColor.RED + "这个名字的格式（玩家名·数字）是留给假人的，请换一个名字。\n"
                                + ChatColor.GRAY + "如果你认为这是误判，请联系管理员。");
                plugin.getLogger().warning("已拒绝玩家 " + name + " 登录：这个名字的形状会与假人重名，"
                        + "而真人顶掉在线假人会让它的背包整袋掉在地上。");
                // 异步线程里不直接碰玩家，丢回主线程再提醒 OP
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String alert = ChatColor.YELLOW + "[BotManager] 已挡下一次抢注假人名字的登录：" + name;
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (online.hasPermission("botmanager.admin")) {
                            online.sendMessage(alert);
                        }
                    }
                });
            }
        };
    }

    private void handleLogin(PlayerLoginEvent event) {
        String name = event.getPlayer().getName();
        Player bot = leaves.findByFullName(name);
        if (bot == null) {
            return;
        }

        // 先把主人记下来再动手（raw name 读的是创建时写死的字段，移除后也还在，
        // 但顺序上先取更省心：万一将来 Leaves 改成移除时清理状态，这里不会突然变 null）
        String owner = ownerNameOf(bot);

        // 配置每次现读，/bm reload 立即生效
        boolean deny = "deny".equalsIgnoreCase(
                plugin.getConfig().getString("bot.name-conflict", "protect"));

        String alert = ChatColor.YELLOW + "[BotManager] 玩家 " + name + " 与假人同名"
                + (owner == null ? "" : "（属于 " + owner + "）") + "。";

        if (deny) {
            // 玩家被挡在 placeNewPlayer 之前，Leaves 不会去动那个假人，所以这里也不能动它
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    ChatColor.RED + "这个名字正被一个假人使用。\n"
                            + ChatColor.GRAY + "如果你认为这是误判，请联系管理员。");
            plugin.getLogger().warning("已拒绝玩家 " + name + " 登录：与在线假人同名"
                    + (owner == null ? "。" : "（该假人属于 " + owner + "）。"));
            alert += ChatColor.GRAY + " 已按 deny 配置拒绝其登录，假人未受影响。";
        } else {
            // 抢在 Leaves 的 save=false 移除之前存档，保住假人的背包。
            // 必须放在这里而不是前面：deny 分支要把假人原样留着。
            boolean saved = leaves.saveAndRemove(bot);
            plugin.getLogger().warning("玩家 " + name + " 与在线假人同名（该假人属于 " + owner + "）。"
                    + (saved ? "已存档移除该假人，物品不会掉落。" : "该假人存档失败，物品可能掉在地上！"));
            plugin.getLogger().warning("想禁止这类登录，把 config.yml 的 bot.name-conflict 改成 deny。");
            alert += ChatColor.GRAY + " 假人已存档移除，主人下次登录可原样恢复。";
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("botmanager.admin")) {
                online.sendMessage(alert);
            }
        }
    }

    /** 从假人的 raw name 反推主人名（去不掉就返回 null）。 */
    private String ownerNameOf(Player bot) {
        String raw = leaves.rawNameOf(bot);
        if (raw == null) {
            return null;
        }
        // 从最后一个分隔符切，不是 '_'——玩家名本身可能含下划线（my_player.1），
        // 按下划线切会把主人名截成 "my_p"。
        int cut = raw.lastIndexOf(LeavesBridge.SEPARATOR);
        return cut > 0 ? raw.substring(0, cut) : null;
    }

    /**
     * 该全名是否正被一个真实玩家占用。
     *
     * <p>只查在线玩家。这里刻意**不**用 {@code Bukkit.getOfflinePlayer(name).hasPlayedBefore()}
     * 去兜住"离线但来过服"的名字：那会在主线程触发一次阻塞式档案查询。
     * 代价是可能造出一个暂时不会撞车的假人。
     *
     * <p>命名空间隔离生效时，{@link #namespaceGuard} 已经在登录阶段把这种形状的名字
     * 全挡在外面了，所以这条检查是**第二道保险**——它真正兜住的是隔离失效
     * （{@code SEPARATOR} 被改回普通字符）或者有别的东西绕过登录事件的情况。
     *
     * <p>假人不算数——它们不在 {@code getOnlinePlayers()} 里（见 {@code LeavesBridge.listBots}），
     * 所以 {@code getPlayerExact} 只会返回真实玩家。
     */
    public boolean nameTakenByRealPlayer(String fullName) {
        return Bukkit.getPlayerExact(fullName) != null;
    }

    // ------------------------------------------------------------------
    //  上下线同步
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("bot.sync-on-join", true)) {
            return;
        }
        Player owner = event.getPlayer();
        // 延后一 tick：等玩家真正落到世界里，位置才是准的
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreAll(owner), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("bot.sync-on-quit", true)) {
            return;
        }
        dismissAll(event.getPlayer());
    }

    // ------------------------------------------------------------------
    //  关服前的抢救
    // ------------------------------------------------------------------

    /**
     * 在 {@code /stop} 真正执行之前，把在线假人存档并移除。
     *
     * <h2>为什么非得抢在命令执行之前</h2>
     * Leaves 的 {@code MinecraftServer.stopServer()} 一开头就是这个顺序
     * （已对部署的 1.21.8 jar 用 {@code javap -c} 核对过字节码）：
     * <pre>
     *   LOGGER.info("Stopping server");
     *   this.getBotList().removeAll();    // ← 假人在这一行被清掉
     *   ...
     *   this.server.disablePlugins();     // ← 插件的 onDisable 才在这里跑
     * </pre>
     * 而 {@code removeAll()} 传给 {@code removeBot} 的 save 参数取的是 Leaves 配置的
     * {@code resident-fakeplayer}。目标服配的是 {@code false}，于是走进
     * {@code bot.dropExperience(); bot.dropAll(true)} 这条分支——
     * **背包连经验一起掉在地上，存档也被删掉**。
     *
     * <p>等 {@link BotManager#onDisable()} 再抢救已经晚了：那时在线列表是空的，
     * 一个都存不到。重启后玩家上线，假人因为没存档只能走新建分支，
     * 于是出现在玩家脚下而不是关服时的位置。这两件事是同一个原因。
     *
     * <p>所以唯一的时机就是命令执行之前。这里提前
     * {@link LeavesBridge#saveAndRemove 存档并移除}，等 {@code removeAll()} 再跑时
     * 列表已经空了，掉物品的分支根本进不去。
     *
     * <h2>为什么用 MONITOR 而不是 LOWEST</h2>
     * MONITOR 在所有插件处理完之后才跑，能先看一眼 {@code isCancelled()}：
     * 万一有别的插件拦下了这次关服，我们就不该把假人拆掉。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) {
        debugCommand(event.getCommand(), event.getClass().getSimpleName());
        if (event.isCancelled()) {
            return;
        }
        preSaveForShutdown(event.getCommand());
    }

    /**
     * RCON 发来的命令，必须**单独**注册一个监听器。
     *
     * <h2>为什么不能靠上面那个接住</h2>
     * {@code RemoteServerCommandEvent extends ServerCommandEvent}，看着是子类，
     * 但它**自己声明了 {@code getHandlers()} 和一份独立的 {@code HANDLER_LIST}**：
     * <pre>
     *   public class RemoteServerCommandEvent extends ServerCommandEvent {
     *       private static final HandlerList HANDLER_LIST;   // ← 自己的一份
     *       public HandlerList getHandlers();
     *       public static HandlerList getHandlerList();
     *   }
     * </pre>
     * Bukkit 派发事件时走的是 {@code event.getHandlers()}，拿到的就是这份独立的表，
     * 里面没有只注册了父类的监听器。所以**监听 {@code ServerCommandEvent}
     * 收不到 RCON 的命令**——这是实测踩出来的：RCON 发 {@code give}/{@code tp}/
     * {@code stop} 一条都触发不到上面的监听器，日志里干干净净。
     *
     * <p>执行线程不用担心：RCON 的命令是在
     * {@code DedicatedServer.runCommand} 的 {@code executeBlocking} 里跑的，
     * 也就是主线程上（RCON 线程会阻塞等它）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoteServerCommand(RemoteServerCommandEvent event) {
        debugCommand(event.getCommand(), "RemoteServerCommand");
        if (event.isCancelled()) {
            return;
        }
        preSaveForShutdown(event.getCommand());
    }

    /** OP 玩家自己敲 {@code /stop} 的情况走这条，控制台走上面那条。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        debugCommand(event.getMessage(), "PlayerCommandPreprocess");
        if (event.isCancelled()) {
            return;
        }
        preSaveForShutdown(event.getMessage());
    }

    /**
     * 开 {@code debug} 时把收到的事件打出来。
     *
     * <p>特意带上「是不是主线程」：RCON 发来的命令是在 RCON 线程上执行的
     * （Paper 的 {@code AsyncCatcher} 会因此报一条 error），
     * 排查关服存档失效时这是第一个要看的信息。
     */
    private void debugCommand(String command, String eventName) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[调试] 收到 " + eventName
                    + "（主线程=" + Bukkit.isPrimaryThread() + "）：" + command);
        }
    }

    /**
     * 是关服指令就抢先存一次假人，不是就什么都不做。
     *
     * <p>控制台、RCON、面板的「停止」按钮，都是把 {@code stop} 打进标准输入，
     * 一并走 {@link ServerCommandEvent}，所以这一条能覆盖掉绝大多数关服方式。
     *
     * <p>覆盖不到的是 {@code kill -9}／进程被强杀这类——那种情况没有任何代码能跑，
     * 只能靠玩家下线时的正常存档兜底。
     */
    private void preSaveForShutdown(String command) {
        if (!plugin.getConfig().getBoolean("bot.save-on-shutdown", true)) {
            return;
        }
        if (!isShutdownCommand(command)) {
            return;
        }
        saveAllOnline();
    }

    /**
     * 判断一条命令是不是关服指令。
     *
     * <p>三种写法都要认：控制台给的没有斜杠（{@code stop}），
     * 玩家给的有（{@code /stop}），以及带命名空间的（{@code /minecraft:stop}）。
     */
    private static boolean isShutdownCommand(String command) {
        if (command == null) {
            return false;
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int space = trimmed.indexOf(' ');
        String label = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        return label.equals("stop") || label.equals("restart");
    }

    /** 把该玩家登记在册的假人全部拉起来：有存档的还原，没存档的新建。 */
    public void restoreAll(Player owner) {
        List<Integer> owned = registry.getOwned(owner.getUniqueId());
        if (owned.isEmpty()) {
            return;
        }

        int restored = 0;
        int created = 0;
        int failed = 0;
        for (int index : owned) {
            String raw = rawName(owner, index);
            if (leaves.findByRawName(raw) != null) {
                continue;
            }
            String full = leaves.fullName(raw);
            if (!leaves.isFullNameLegal(full) || nameTakenByRealPlayer(full)) {
                failed++;
                continue;
            }
            Player bot = leaves.loadSaved(full);
            if (bot != null) {
                restored++;
                continue;
            }
            // loadSaved 的副作用就是「让假人上线」：它可能已经把假人拉起来了，只是没能返回句柄
            // （比如 getBukkitEntity 拿不到）。不补这一刀就会再造一个同名的假人出来。
            if (leaves.findByRawName(raw) != null) {
                restored++;
                continue;
            }
            if (leaves.spawn(raw, owner.getLocation(), owner) != null) {
                created++;
            } else {
                failed++;
            }
        }

        if (restored + created + failed > 0) {
            plugin.getLogger().info("玩家 " + owner.getName() + " 上线，假人同步：还原 "
                    + restored + " 个，新建 " + created + " 个，失败 " + failed + " 个。");
        }
    }

    /** 把该玩家所有在线假人存档并下线。 */
    public void dismissAll(Player owner) {
        List<Player> bots = leaves.botsOf(owner.getName());
        int count = 0;
        for (Player bot : bots) {
            if (leaves.saveAndRemove(bot)) {
                count++;
            }
        }
        if (count > 0) {
            plugin.getLogger().info("玩家 " + owner.getName() + " 下线，已存档并移除 " + count + " 个假人。");
        }
    }

    /**
     * 把当前在线的全部假人存档并移除。
     *
     * <p>两个调用点，时机都很关键：
     * <ul>
     *   <li>{@link #preSaveForShutdown} —— 收到 {@code /stop} 时，抢在 Leaves 的
     *       {@code stopServer()} 清场之前。这是关服场景下真正起作用的那次。</li>
     *   <li>{@link BotManager#onDisable()} —— 兜底。注意它在**正常关服时是空跑**：
     *       Leaves 的 {@code removeAll()} 排在 {@code disablePlugins()} 前面，
     *       轮到这里时在线列表已经空了。它真正能救的是 {@code /reload}
     *       这类只禁用插件、不清假人的路径。</li>
     * </ul>
     *
     * <p>用的 {@link LeavesBridge#saveAndRemove} 是「存档 + 移除」，
     * 所以调用之后这些假人就不在线了。这正是关服时要的效果——
     * Leaves 随后再 {@code removeAll()} 时列表为空，掉物品的分支不会触发。
     */
    public void saveAllOnline() {
        List<Player> bots = leaves.listBots();
        if (bots.isEmpty()) {
            return;
        }
        int count = 0;
        for (Player bot : bots) {
            if (leaves.saveAndRemove(bot)) {
                count++;
            }
        }
        if (count > 0) {
            plugin.getLogger().info("关服前已存档 " + count + " 个假人。");
        }
    }
}
