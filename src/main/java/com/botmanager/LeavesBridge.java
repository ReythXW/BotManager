package com.botmanager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 对 Leaves 假人 API 的唯一封装层。
 *
 * <p>插件其余部分一律通过本类操作假人，不直接碰反射，这样将来 Leaves 改 API 时只需改这一个文件。
 *
 * <p>用到的接口分两类：
 * <ul>
 *   <li><b>公开 API</b>（{@code leaves-api} 包，稳定）：
 *       {@code Server#getBotManager()} → {@code BotManager#getBots()/botCreator()} →
 *       {@code BotCreator#spawn()}、{@code Bot#remove(boolean,boolean)}、{@code Bot#getRawName()}。</li>
 *   <li><b>服务端内部 public 方法</b>（不在 API 文档面上，故带降级路径）：
 *       {@code BotList#loadNewManualSavedBot(String)}——唯一能把存档里的背包/位置/血量/动作
 *       还原回来的入口。创建路径不读存档，只靠它是回不来的。</li>
 * </ul>
 */
public final class LeavesBridge {

    /**
     * 假人名里分隔「玩家名」和「编号」的字符。
     *
     * <p>必须是**真人名字里不可能出现**的字符——Minecraft 的玩家名只允许 {@code [a-zA-Z0-9_]}。
     * 用 {@code .} 之后，假人名 {@code test.1} 永远不可能被注册成玩家账号，
     * 抢名问题从根上消失（机制见 {@link BotNameGate}）。
     *
     * <p><b>改回 {@code _} 会同时失去这层保护</b>，退回"能撞名、撞了再处理"的老路。
     */
    public static final String SEPARATOR = ".";

    /** 假人 raw name 的形状：{@code <玩家名>.<编号>}。玩家名部分由 Minecraft 自己保证合法。 */
    private static final Pattern RAW_PART =
            Pattern.compile("^[a-zA-Z0-9_]+" + Pattern.quote(SEPARATOR) + "\\d+$");

    private static final String API_BOT = "org.leavesmc.leaves.entity.bot.Bot";
    private static final String API_BOT_MANAGER = "org.leavesmc.leaves.entity.bot.BotManager";
    private static final String API_BOT_CREATOR = "org.leavesmc.leaves.entity.bot.BotCreator";
    private static final String SERVER_BOT_LIST = "org.leavesmc.leaves.bot.BotList";
    private static final String SERVER_BOT_DATA_STORAGE = "org.leavesmc.leaves.bot.BotDataStorage";
    private static final String SERVER_BOT_UTIL = "org.leavesmc.leaves.bot.BotUtil";
    private static final String SERVER_CONFIG = "org.leavesmc.leaves.LeavesConfig";
    private static final String SERVER_ACTIONS = "org.leavesmc.leaves.bot.agent.Actions";

    /** Leaves 的 /bot 命令需要的权限节点，委托执行前临时授予。 */
    public static final String LEAVES_BOT_PERMISSION = "bukkit.command.bot";

    private final BotManager plugin;

    private Class<?> botClass;
    private Method getBotsMethod;
    private Method botCreatorMethod;
    private Method creatorMethod;
    private Method spawnMethod;
    private Method removeMethod;
    /** {@link #removeMethod} 是否带 resume 参数（Leaves 1.21.11+ 才有）。 */
    private boolean removeTakesResume;
    private Method getRawNameMethod;
    private Method loadSavedMethod;
    /** 把 NMS 侧的假人实体换成 Bukkit 侧句柄；{@link #loadSaved} 首次用到时才解析。 */
    private Method getBukkitEntityMethod;
    private Object botList;
    private Object botManager;

    /**
     * Leaves 的假人存档后端，即 {@code BotList.dataStorage}。
     *
     * <p>那是个 {@code private final} 字段，只能反射取；类型
     * {@code org.leavesmc.leaves.bot.BotDataStorage}。自动存档和名单清理都挂在它上面。
     */
    private Object botDataStorage;
    /** {@code BotDataStorage.save(Player)} —— 唯一「写盘但**不移除**假人」的入口。 */
    private Method autosaveMethod;
    /** {@code BotDataStorage.getSavedBotList()}，取「待复原名单」那个 CompoundTag。 */
    private Method savedListMethod;
    /** {@code BotDataStorage.saveBotList()}（private），把名单刷到磁盘。 */
    private Method flushSavedListMethod;
    /** {@code CompoundTag.remove(String)}，从名单里摘掉一个条目。 */
    private Method tagRemoveMethod;
    /** {@code CraftPlayer.getHandle()}，把 Bukkit 句柄换成 NMS 实体给 save() 用。 */
    private Method getHandleMethod;

    // ---- 假人动作（操作面板用），全部惰性解析 ----
    private Method actionSizeMethod;
    private Method actionAtMethod;
    private Method stopActionMethod;
    private Method stopAllActionsMethod;
    private Method actionHandleMethod;
    private Method actionIntervalMethod;
    private Method actionNumberMethod;
    private boolean actionsUnavailableLogged;
    /** {@code BotUtil.getBotUUID(String)}——假人 UUID 由名字直接算出来，抹存档要靠它定位文件。 */
    private Method botUuidMethod;
    /** 自动存档失败只报一次，避免每 5 分钟刷一行日志。 */
    private boolean autosaveFailureLogged;

    private boolean ready;
    private boolean rawNameFailureLogged;

    public LeavesBridge(BotManager plugin) {
        this.plugin = plugin;
    }

    /**
     * 连接 Leaves API。
     *
     * @return 是否成功。失败时插件应停用——没有假人 API 就无从管理。
     */
    public boolean setup() {
        try {
            this.botClass = Class.forName(API_BOT);
            Class<?> botManagerClass = Class.forName(API_BOT_MANAGER);
            Class<?> botCreatorClass = Class.forName(API_BOT_CREATOR);

            Object server = Bukkit.getServer();
            this.botManager = server.getClass().getMethod("getBotManager").invoke(server);
            if (this.botManager == null) {
                plugin.getLogger().severe("服务端的 getBotManager() 返回 null，这不是 Leaves 服务端。");
                return false;
            }

            // 逐个解析、把缺的一次性列全再退出。
            // 早先的写法是第一个 NoSuchMethodException 就抛出去，一次只暴露一个问题，
            // 换个 Leaves 版本要反复重启试错——这里改成攒起来一起报。
            List<String> missing = new ArrayList<>();
            this.getBotsMethod = require(missing, botManagerClass, "getBots");
            this.botCreatorMethod = require(missing, botManagerClass, "botCreator", String.class, Location.class);
            this.creatorMethod = require(missing, botCreatorClass, "creator", CommandSender.class);
            this.spawnMethod = require(missing, botCreatorClass, "spawn");

            // 同一个东西跨版本改过名：1.21.8 及更早叫 getRealName()，1.21.11 起叫 getRawName()。
            // 都是"不含 Leaves 前后缀的那段名字"，是归属判定和存档寻址的地基，缺了等于全废。
            this.getRawNameMethod = find(botClass, "getRawName");
            if (this.getRawNameMethod == null) {
                this.getRawNameMethod = find(botClass, "getRealName");
                if (this.getRawNameMethod == null) {
                    missing.add("Bot.getRawName() 或 Bot.getRealName()");
                } else {
                    plugin.getLogger().info("检测到 Bot.getRealName()（Leaves 1.21.8 及更早的命名），已适配。");
                }
            }

            // remove 的签名跨版本变过，得兼容两种：
            //   1.21.8 及更早：remove(boolean save)                    —— 内部 removeBot(..., save, false)
            //   1.21.11 起    ：remove(boolean save, boolean resume)
            // 本插件两处调用传的 resume 都是 false，单参版本语义完全等价，不会丢存档。
            this.removeMethod = find(botClass, "remove", boolean.class, boolean.class);
            if (this.removeMethod != null) {
                this.removeTakesResume = true;
            } else {
                this.removeMethod = find(botClass, "remove", boolean.class);
                this.removeTakesResume = false;
                if (this.removeMethod == null) {
                    missing.add("Bot.remove(boolean) 或 Bot.remove(boolean,boolean)");
                } else {
                    plugin.getLogger().info("检测到单参 Bot.remove(boolean)（Leaves 1.21.8 及更早的签名），"
                            + "已按等价语义适配。");
                }
            }

            if (!missing.isEmpty()) {
                plugin.getLogger().severe("该服务端的 Leaves 假人 API 与本插件不匹配，缺少以下方法：");
                for (String item : missing) {
                    plugin.getLogger().severe("  - " + item);
                }
                plugin.getLogger().severe("请把上面这份清单连同服务端版本号一起反馈；"
                        + "当前插件目标版本为 Leaves 1.21.8 ~ 1.21.11。");
                this.ready = false;
                return false;
            }

            // 存档还原入口：leaves-server 内部 public 方法，拿不到就退化为"永远新建"
            try {
                Class<?> botListClass = Class.forName(SERVER_BOT_LIST);
                this.botList = botListClass.getField("INSTANCE").get(null);

                // 入口签名也变过：
                //   1.21.8 及更早：loadNewBot(String realName)  —— 存档区只有一份（dataStorage）
                //   1.21.11 起    ：loadNewManualSavedBot(String fullName) / loadNewResumeBot(...)
                // 两边读的都是"手动存档"那一份，语义对应。
                this.loadSavedMethod = find(botListClass, "loadNewManualSavedBot", String.class);
                if (this.loadSavedMethod == null) {
                    this.loadSavedMethod = find(botListClass, "loadNewBot", String.class);
                    if (this.loadSavedMethod == null) {
                        plugin.getLogger().warning("不认识 Leaves 的假人存档还原入口，"
                                + "假人下线后将无法还原背包与位置（仍可召唤，只是每次上线都是全新状态）。");
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("无法连接 Leaves 的假人存档接口，假人下线后将无法还原背包与位置：" + t);
            }

            // 自动存档通道单独探测。**必须另起一个 try**：它失败只该让自动存档失效，
            // 不该把上面的"还原"一起拖下水——那两件事用的是不同的入口。
            try {
                Class<?> botListClass = Class.forName(SERVER_BOT_LIST);
                Class<?> storageClass = Class.forName(SERVER_BOT_DATA_STORAGE);
                this.botDataStorage = readPrivate(botList, "dataStorage");

                // save 的形参是 NMS 侧的 Player（就是 CraftBot.getHandle() 的返回类型）。
                // 不硬编码那个类名——按「叫 save、单参、非基本类型」认，
                // 跨版本改 NMS 包名也照样接得上。
                for (Method candidate : storageClass.getMethods()) {
                    if (candidate.getName().equals("save")
                            && candidate.getParameterCount() == 1
                            && !candidate.getParameterTypes()[0].isPrimitive()) {
                        this.autosaveMethod = candidate;
                        break;
                    }
                }

                // 名单清理三件套：读名单 -> 摘条目 -> 刷盘。
                this.savedListMethod = find(storageClass, "getSavedBotList");
                if (this.savedListMethod != null) {
                    this.tagRemoveMethod = find(this.savedListMethod.getReturnType(), "remove", String.class);
                }
                this.flushSavedListMethod = storageClass.getDeclaredMethod("saveBotList");
                this.flushSavedListMethod.setAccessible(true);

                // 抹存档走「名字 -> UUID -> 文件」这条路定位 .dat（原因见 #purgeSaved）。
                // getBotUUID 是静态的，按「叫这名字、单 String 参数」认。
                for (Method candidate : Class.forName(SERVER_BOT_UTIL).getMethods()) {
                    if (candidate.getName().equals("getBotUUID")
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0] == String.class) {
                        this.botUuidMethod = candidate;
                        break;
                    }
                }

                if (this.botDataStorage == null || this.autosaveMethod == null) {
                    throw new NoSuchMethodException("取不到 BotList.dataStorage 或 BotDataStorage.save");
                }
            } catch (Throwable t) {
                this.botDataStorage = null;
                this.autosaveMethod = null;
                plugin.getLogger().warning("无法连接 Leaves 的假人自动存档接口，"
                        + "进程被强杀（kill -9 / 断电 / OOM）时假人会回退到上次落盘的状态：" + t);
            }

            this.ready = true;
            return true;
        } catch (Throwable t) {
            plugin.getLogger().severe("无法连接 Leaves 的假人 API：" + t);
            this.ready = false;
            return false;
        }
    }

    /** 查一个必需的方法；查不到不抛异常，只把描述记进 {@code missing} 并返回 null。 */
    private static Method require(List<String> missing, Class<?> owner, String name, Class<?>... params) {
        Method method = find(owner, name, params);
        if (method == null) {
            StringBuilder sb = new StringBuilder(owner.getSimpleName()).append('.').append(name).append('(');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params[i].getSimpleName());
            }
            missing.add(sb.append(')').toString());
        }
        return method;
    }

    private static Method find(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** 读一个 private 字段的值；读不到返回 null（调用方自行判空）。 */
    private static Object readPrivate(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isReady() {
        return ready;
    }

    /** 是否支持存档还原。false 时假人每次上线都是全新状态。 */
    public boolean canRestore() {
        return loadSavedMethod != null && botList != null;
    }

    /**
     * 是否支持「只写盘、不移除假人」的自动存档。
     *
     * <p>false 时周期性自动存档不会启动，进程被强杀后假人只能回退到上次正常落盘的状态。
     */
    public boolean canAutosave() {
        return autosaveMethod != null && botDataStorage != null;
    }

    // ------------------------------------------------------------------
    //  查询
    // ------------------------------------------------------------------

    /** 当前在线的全部假人。 */
    public List<Player> listBots() {
        List<Player> result = new ArrayList<>();
        if (!ready) {
            return result;
        }
        try {
            Collection<?> bots = (Collection<?>) getBotsMethod.invoke(botManager);
            for (Object bot : bots) {
                if (bot instanceof Player player) {
                    result.add(player);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("获取在线假人列表失败：" + t);
        }
        return result;
    }

    /**
     * 某个假人的 raw name（不含 Leaves 的前后缀）；取不到返回 null。
     *
     * <p>调用方一律**不能**把 null 当成"放行"：归属判定靠的就是它，
     * 判不出来时按不属于处理才安全。所以这里失败只告警一次——
     * 该方法在 {@link #findByRawName}、{@link #botsOf} 里是按假人逐个调的，
     * 每个都打日志会刷屏。只报一次，避免"静默失效"这种最难查的故障。
     */
    public String rawNameOf(Player bot) {
        try {
            return (String) getRawNameMethod.invoke(bot);
        } catch (Throwable t) {
            if (!rawNameFailureLogged) {
                rawNameFailureLogged = true;
                plugin.getLogger().warning("读取假人 raw name 失败，归属判定将全部按「不是你的」处理：" + t);
            }
            return null;
        }
    }

    /**
     * 按**全名**查找在线假人（大小写不敏感，与 Leaves 的 {@code getBotByName} 一致）。
     *
     * <p>假人的全名就是它的游戏档案名，即 {@link Player#getName()}，
     * 所以这里直接用名字比较，不必再拼前后缀。
     */
    public Player findByFullName(String fullName) {
        if (fullName == null) {
            return null;
        }
        for (Player bot : listBots()) {
            if (fullName.equalsIgnoreCase(bot.getName())) {
                return bot;
            }
        }
        return null;
    }

    /** 按 raw name 精确查找在线假人。 */
    public Player findByRawName(String rawName) {
        for (Player bot : listBots()) {
            if (rawName.equals(rawNameOf(bot))) {
                return bot;
            }
        }
        return null;
    }

    /**
     * 某个玩家的全部在线假人。
     *
     * <p>用 raw name 匹配而非 {@code Bot#getCreatePlayerUUID()}——后者不写进存档，
     * 服务器重启后一律为 null，靠不住。
     */
    public List<Player> botsOf(String playerName) {
        List<Player> result = new ArrayList<>();
        for (Player bot : listBots()) {
            if (ownsRawName(playerName, rawNameOf(bot))) {
                result.add(bot);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    //  创建 / 移除
    // ------------------------------------------------------------------

    /**
     * 新建一个假人。
     *
     * @return 创建出的假人；失败返回 null（全局限额已满、名字被占用等）
     */
    public Player spawn(String rawName, Location location, Player creator) {
        if (!ready) {
            return null;
        }
        try {
            Object botCreator = botCreatorMethod.invoke(botManager, rawName, location);
            if (creator != null) {
                // 写进 ServerBot.createPlayer，仅供运行时参考（不持久化）
                botCreator = creatorMethod.invoke(botCreator, creator);
            }
            return (Player) spawnMethod.invoke(botCreator);
        } catch (Throwable t) {
            plugin.getLogger().warning("创建假人 " + rawName + " 失败：" + t);
            return null;
        }
    }

    /**
     * 从存档还原假人（背包、位置、血量、正在执行的动作全部恢复）。
     *
     * <h2>为什么不能直接强转返回值</h2>
     * {@code BotList.loadNewBot(String)} 的返回类型是 {@code org.leavesmc.leaves.bot.ServerBot}，
     * 那是 NMS 那边的 {@code ServerPlayer} 体系，**不是** Bukkit 的 {@code Player}，
     * 直接 {@code (Player)} 强转必抛 {@code ClassCastException}。
     * 得先过一道 {@code getBukkitEntity()} 换成 Bukkit 实体
     * （{@code ServerBot.getBukkitEntity()} 返回 {@code CraftBot}，它是 {@code CraftPlayer} 的子类）。
     *
     * <h2>调用方必须知道的副作用</h2>
     * {@code loadNewBot()} 干的事就是「把假人从存档加载出来并上线」，**这个方法一旦被调用，
     * 假人就已经站在世界里了**。所以哪怕后面抛异常、这里返回了 null，
     * 假人也已经上线——调用方不能拿 null 当"什么都没发生"，
     * 必须再查一次在线状态，否则会再造一个同名的假人出来。
     *
     * @return 还原出的假人；没有对应存档或还原失败返回 null
     */
    public Player loadSaved(String fullName) {
        if (!canRestore()) {
            return null;
        }
        try {
            Object bot = loadSavedMethod.invoke(botList, fullName);
            if (bot == null) {
                return null;
            }

            Method toBukkit = getBukkitEntityMethod;
            if (toBukkit == null) {
                toBukkit = find(bot.getClass(), "getBukkitEntity");
                if (toBukkit == null) {
                    plugin.getLogger().warning("Leaves 的假人实体没有 getBukkitEntity()，"
                            + "还原 " + fullName + " 拿不到 Bukkit 句柄（假人已经上线）。");
                    return null;
                }
                this.getBukkitEntityMethod = toBukkit;
            }
            return (Player) toBukkit.invoke(bot);
        } catch (Throwable t) {
            plugin.getLogger().warning("还原假人 " + fullName + " 的存档失败：" + t);
            return null;
        }
    }

    /**
     * 移除假人并保留存档，下次登录时可原样还原。
     *
     * <p>{@code save=true, resume=false} 会写进 manual 存档：{@code .dat} 与存档列表条目都保留，
     * 而 resume 列表被清掉（所以不会在开服时自己冒出来）。
     *
     * <p>用于玩家上下线同步——假人"消失"只是跟着主人离线，东西必须留着。
     */
    public boolean saveAndRemove(Player bot) {
        try {
            return (boolean) invokeRemove(bot, true);
        } catch (Throwable t) {
            plugin.getLogger().warning("移除假人 " + bot.getName() + " 失败：" + t);
            return false;
        }
    }

    /**
     * 真删假人：不写存档，**背包里的东西掉在它脚下**，之后再召唤是全新的。
     *
     * <p>用于玩家主动 {@code /bm remove}。与 {@link #saveAndRemove} 的区别是语义上的：
     * 那个是"跟着主人下线"，这个是"这个假人不要了"。编号会被回收再用，
     * 所以这里**必须**不写存档，否则下次补到同一编号会把删掉的假人连背包一起复活。
     *
     * <p>掉物品是 Leaves 的固有行为（{@code BotList.removeBot} 里
     * {@code save=false} 会走 {@code bot.dropExperience(); bot.dropAll(true)}），
     * 也是想要的效果：玩家的东西不会凭空蒸发，能自己捡回来。
     */
    public boolean removeDiscardingSave(Player bot) {
        try {
            return (boolean) invokeRemove(bot, false);
        } catch (Throwable t) {
            plugin.getLogger().warning("删除假人 " + bot.getName() + " 失败：" + t);
            return false;
        }
    }

    /**
     * 把假人当前状态写进磁盘，**假人继续在线、不掉任何东西**。
     *
     * <h2>为什么非得自己存</h2>
     * Leaves 的假人存档是两层的：
     * <ul>
     *   <li>{@code world/fakeplayerdata/<uuid>.dat} —— 单个假人的完整状态（背包、坐标、血量）；</li>
     *   <li>{@code world/fakeplayer.dat} —— 「待复原名单」索引，只有 name/uuid。</li>
     * </ul>
     * 而把假人拉上线那一步会**消费掉名单条目**，所以假人在线期间，
     * 磁盘上没有任何「它该被还原」的记录。目标服又是 {@code resident-fakeplayer: false}，
     * Leaves 自带的定时存档（{@code saveAllResumeBots}）也一并被关掉了。
     * 两者叠加的结果：进程被强杀时假人只能回退到上次落盘的状态，窗口不封顶。
     *
     * <p>{@code BotDataStorage.save(Player)} 正好是缺的那一环。反编译确认它做两件事：
     * 把实体整个写成 {@code <uuid>.dat}，再往名单里补一条并**立刻刷盘**——
     * {@code saveBotList()} 是在 save 内部调的，不依赖世界存档的时机。
     * Leaves 自己只在 {@code removeBot(save=true)} 时才会调它，所以在线期间没人写。
     *
     * <p>它**不会**让假人开机自启：{@code BotList.loadResume()} 开头就是
     * {@code if (!canResident) return;}，目标服那个开关是 false，
     * 名单条目只对插件显式调用的 {@code loadNewBot()} 有意义。
     *
     * @return 是否写入成功
     */
    public boolean autosave(Player bot) {
        if (!canAutosave()) {
            return false;
        }
        try {
            Object handle = nmsHandle(bot);
            if (handle == null) {
                return false;
            }
            autosaveMethod.invoke(botDataStorage, handle);
            return true;
        } catch (Throwable t) {
            if (!autosaveFailureLogged) {
                autosaveFailureLogged = true;
                plugin.getLogger().warning("假人自动存档失败，后续同类错误不再重复报告：" + t);
            }
            return false;
        }
    }

    /**
     * 抹掉假人的存档，让它**再也还原不回来**。
     *
     * <h2>为什么必须删 .dat，光摘名单没用</h2>
     * 反编译 {@code BotList.loadNewBot(String, IPlayerDataStorage)} 才看明白它怎么找存档：
     * 第一步就是 {@code BotUtil.getBotUUID(名字)}——**UUID 由名字直接算出来**，
     * 全程不查「待复原名单」；拿到 UUID 后再去读
     * {@code world/fakeplayerdata/<uuid>.dat}，读得到就还原，读不到才返回 null。
     *
     * <p>也就是说名单条目只服务于 {@code loadResume()}（resident 模式），
     * 对插件这种「按名字点对点还原」的用法毫无影响。**实测踩过**：一开始只摘名单条目，
     * 结果真删掉的假人 {@code /bm spawn} 之后连背包一起复活了，而东西已经掉在地上——
     * 凭空多出一份。
     *
     * <p>所以这里以**删文件**为准，顺带把名单条目也摘掉：万一哪天服主把
     * {@code resident-fakeplayer} 打开，也不会从那边诈尸。
     *
     * @param rawName 假人的 raw name，即 {@code <玩家名>.<编号>}。
     *                全名由 {@link #fullName} 现算——必须和 {@link #loadSaved}
     *                传给 {@code loadNewBot} 的是同一个，否则算出的 UUID 对不上文件
     */
    public boolean purgeSaved(String rawName) {
        if (rawName == null) {
            return false;
        }
        boolean deleted = deleteSavedData(fullName(rawName));
        boolean delisted = removeFromSavedList(rawName);
        return deleted || delisted;
    }

    /**
     * 删掉 {@code world/fakeplayerdata/<uuid>.dat}——这一步才是「还原不回来」的关键。
     *
     * <p>目录不硬编码：{@code BotDataStorage.botDir} 字段存的就是 Leaves 自己算出来的
     * 那个路径，直接读它，跨版本改目录结构也不会失手。
     */
    private boolean deleteSavedData(String fullName) {
        if (fullName == null || botDataStorage == null || botUuidMethod == null) {
            return false;
        }
        try {
            Object uuid = botUuidMethod.invoke(null, fullName);
            Object dir = readPrivate(botDataStorage, "botDir");
            if (uuid == null || !(dir instanceof File botDir)) {
                return false;
            }
            return new File(botDir, uuid + ".dat").delete();
        } catch (Throwable t) {
            plugin.getLogger().warning("删除假人 " + fullName + " 的存档文件失败：" + t);
            return false;
        }
    }

    /**
     * 从「待复原名单」里摘掉条目并刷盘。
     *
     * <p>key 用 raw name：{@code BotDataStorage.save} 写进去时用的就是
     * {@code createState.realName()}，不含 Leaves 的前后缀。
     */
    private boolean removeFromSavedList(String rawName) {
        if (botDataStorage == null || savedListMethod == null
                || flushSavedListMethod == null || tagRemoveMethod == null) {
            return false;
        }
        try {
            Object list = savedListMethod.invoke(botDataStorage);
            if (list == null) {
                return false;
            }
            tagRemoveMethod.invoke(list, rawName);
            flushSavedListMethod.invoke(botDataStorage);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("清理假人 " + rawName + " 的存档名单失败：" + t);
            return false;
        }
    }

    /** 取假人对应的 NMS 实体，给 {@code BotDataStorage.save} 当形参。 */
    private Object nmsHandle(Player bot) throws ReflectiveOperationException {
        Method method = getHandleMethod;
        if (method == null) {
            method = find(bot.getClass(), "getHandle");
            if (method == null) {
                return null;
            }
            this.getHandleMethod = method;
        }
        return method.invoke(bot);
    }

    /**
     * 按 {@link #removeTakesResume} 选择调用的重载。
     *
     * <p>单参版本（Leaves 1.21.8 及更早）内部写死 {@code resume=false}，
     * 与这里的 {@code false} 一致，所以两种签名下行为完全相同。
     */
    private Object invokeRemove(Player bot, boolean save) throws ReflectiveOperationException {
        return removeTakesResume
                ? removeMethod.invoke(bot, save, false)
                : removeMethod.invoke(bot, save);
    }

    // ------------------------------------------------------------------
    //  Leaves 配置读取
    // ------------------------------------------------------------------

    /** Leaves 配置的假人名字前缀，读不到时返回空串。 */
    public String getPrefix() {
        return fakeplayerString("prefix", "");
    }

    /** Leaves 配置的假人名字后缀，读不到时返回空串。 */
    public String getSuffix() {
        return fakeplayerString("suffix", "");
    }

    /**
     * Leaves 当前注册的全部动作名（attack / break / move / look ...），用于 Tab 补全。
     *
     * <p>反射读 {@code Actions.getNames()} 而不是在插件里写死一份：Leaves 加了新动作，
     * 补全自动跟上，不用改代码。读不到返回空集合——补全少几个候选不影响功能。
     */
    public Set<String> getActionNames() {
        try {
            Class<?> actionsClass = Class.forName(SERVER_ACTIONS);
            Object names = actionsClass.getMethod("getNames").invoke(null);
            if (names instanceof Set<?> set) {
                Set<String> result = new TreeSet<>();
                for (Object name : set) {
                    if (name != null) {
                        result.add(name.toString());
                    }
                }
                return result;
            }
        } catch (Throwable t) {
            // 只是补全少了候选，不影响命令能否执行
        }
        return Collections.emptySet();
    }

    // ------------------------------------------------------------------
    //  假人动作：读出来 + 停掉（操作面板用）
    // ------------------------------------------------------------------

    /**
     * 假人身上一个正在跑的动作。
     *
     * @param name     动作名（{@code attack} / {@code use_auto} / {@code sneak} …），
     *                 就是 {@code /bot action <bot> start} 后面那个词
     * @param interval 每几 tick 跑一次；{@link #NO_INTERVAL} 表示这个动作没有周期概念
     *                 （{@code sneak} / {@code swim} 这类状态型动作），或者读不出来
     * @param doNumber 一共做几次；{@code -1} 是<b>无限次</b>（界面上的「一直做」），
     *                 {@code 1} 是"做一次就完"（{@link BotMenu.ActionButton} 里的「点按」那两格）。
     *                 {@link #NO_NUMBER} 表示没读到——注意它跟 {@code -1} 是两回事
     */
    public record RunningAction(String name, int interval, int doNumber) {

        /** 读不到间隔时的占位值。别拿它去和玩家配的间隔比大小。 */
        public static final int NO_INTERVAL = -1;

        /**
         * 读不到次数时的占位值。
         *
         * <p>不能拿 {@code -1} 顶替：{@link #doNumber} 为 {@code -1} 是<b>合法值</b>
         * （无限次），两者撞在一起就分不清"一直做"和"压根没读到"了。
         */
        public static final int NO_NUMBER = Integer.MIN_VALUE;

        /** 间隔读出来了没有。 */
        public boolean intervalKnown() {
            return interval != NO_INTERVAL;
        }

        /** 次数读出来了没有。 */
        public boolean numberKnown() {
            return doNumber != NO_NUMBER;
        }
    }

    /**
     * 假人身上正在跑的动作，顺序与 {@code /bot action <bot> list} 打印的一致。
     *
     * <p>读的是 Leaves 的公开假人 API：{@code Bot#getActionSize()} 与 {@code Bot#getAction(int)}。
     * 拿到的动作对象再往下一层读 interval——那层（{@code CraftBotAction#getHandle()} →
     * {@code AbstractBotAction#getDoIntervalTick()}）是**逐个试**的，不串成一条链：
     * 中间任何一个方法改了名，最坏也只是"不知道间隔是多少"，不会连"这个动作在跑"都读不到。
     *
     * <p>读不到时返回空表，调用方按"它没有动作在跑"处理即可，不要当成出错。
     */
    public List<RunningAction> runningActions(Player bot) {
        List<RunningAction> result = new ArrayList<>();
        if (bot == null || !ensureActionMethods(bot)) {
            return result;
        }
        try {
            int size = (int) actionSizeMethod.invoke(bot);
            for (int i = 0; i < size; i++) {
                Object action = actionAtMethod.invoke(bot, i);
                if (action == null) {
                    continue;
                }
                Method nameMethod = find(action.getClass(), "getName");
                if (nameMethod == null) {
                    continue;
                }
                Object name = nameMethod.invoke(action);
                if (name == null) {
                    continue;
                }
                result.add(new RunningAction(name.toString(), intervalOf(action),
                        doNumberOf(action)));
            }
        } catch (Throwable t) {
            // 读动作只是为了画界面，读不到就当没动作，不要打断玩家正在干的事
            if (!actionsUnavailableLogged) {
                actionsUnavailableLogged = true;
                plugin.getLogger().warning("读不到假人身上的动作列表，操作面板将不显示"
                        + "「运行中」状态（不影响起停动作本身）：" + t);
            }
            result.clear();
        }
        return result;
    }

    /**
     * 停掉假人身上第 {@code index} 个动作（下标口径同 {@link #runningActions}）。
     *
     * <p>{@code stopAction} 只是把动作<b>标记成已取消</b>，真正从列表里摘掉要等下一 tick
     * 由 {@code ServerBot} 统一 {@code removeIf(isCancelled)}。所以停完立刻读
     * {@link #runningActions}，那个动作可能还在——界面要延后一两 tick 再刷新。
     */
    public boolean stopActionAt(Player bot, int index) {
        if (bot == null || !ensureActionMethods(bot) || stopActionMethod == null) {
            return false;
        }
        try {
            stopActionMethod.invoke(bot, index);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("停掉 " + bot.getName() + " 的第 " + index + " 个动作失败：" + t);
            return false;
        }
    }

    /**
     * 停掉假人身上所有名为 {@code actionName} 的动作，返回停掉几个。
     *
     * <p>按下标<b>从大到小</b>停。当前实现里 {@code stopAction} 不会即时改列表长度，
     * 顺序其实无所谓；但万一将来改成即时移除，倒着走才不会边停边越界。
     *
     * <p>面板上「再次点击关闭」和「换成另一种间隔」都走这里——Leaves 的
     * {@code addBotAction} 是纯追加、不去重，起新的之前必须先把同名的旧动作停干净，
     * 否则假人身上会同时挂着两个 {@code attack}，一 tick 打两下。
     */
    public int stopActionsNamed(Player bot, String actionName) {
        List<RunningAction> running = runningActions(bot);
        int stopped = 0;
        for (int i = running.size() - 1; i >= 0; i--) {
            if (running.get(i).name().equals(actionName) && stopActionAt(bot, i)) {
                stopped++;
            }
        }
        return stopped;
    }

    /**
     * 停掉假人身上全部动作，返回**停之前**有几个。
     *
     * <p>用 Leaves 的 {@code stopAllActions()} 而不是自己按下标循环：
     * 这里是"全停"，不需要关心中间有没有动作已经自己跑完了。
     */
    public int stopAllActions(Player bot) {
        if (bot == null || !ensureActionMethods(bot) || stopAllActionsMethod == null) {
            return 0;
        }
        try {
            int before = (int) actionSizeMethod.invoke(bot);
            stopAllActionsMethod.invoke(bot);
            return before;
        } catch (Throwable t) {
            plugin.getLogger().warning("停掉 " + bot.getName() + " 的全部动作失败：" + t);
            return 0;
        }
    }

    /** 一次性解析动作相关的几个方法。解析不了返回 false，调用方按"读不到"处理。 */
    private boolean ensureActionMethods(Player bot) {
        if (actionSizeMethod != null) {
            return true;
        }
        Class<?> type = bot.getClass();
        Method size = find(type, "getActionSize");
        Method at = find(type, "getAction", int.class);
        if (size == null || at == null) {
            return false;       // 拿到的不是假人实体，或者 Leaves 没这套 API
        }
        this.actionSizeMethod = size;
        this.actionAtMethod = at;
        this.stopActionMethod = find(type, "stopAction", int.class);
        this.stopAllActionsMethod = find(type, "stopAllActions");
        return true;
    }

    /**
     * 读动作对象的 interval，读不到返回 {@link RunningAction#NO_INTERVAL}。
     *
     * <p>{@code BotAction} 这个接口只有名字，间隔在服务端实现上，得先 {@code getHandle()}
     * 钻一层。两步分开判空：任一步不行都只是"不知道间隔"，不影响"动作在跑"这个结论。
     */
    private int intervalOf(Object action) {
        try {
            Method toHandle = actionHandleMethod;
            if (toHandle == null) {
                toHandle = find(action.getClass(), "getHandle");
                if (toHandle == null) {
                    return RunningAction.NO_INTERVAL;
                }
                this.actionHandleMethod = toHandle;
            }
            Object handle = toHandle.invoke(action);
            if (handle == null) {
                return RunningAction.NO_INTERVAL;
            }
            Method interval = actionIntervalMethod;
            if (interval == null) {
                interval = find(handle.getClass(), "getDoIntervalTick");
                if (interval == null) {
                    return RunningAction.NO_INTERVAL;
                }
                this.actionIntervalMethod = interval;
            }
            return (int) interval.invoke(handle);
        } catch (Throwable t) {
            return RunningAction.NO_INTERVAL;
        }
    }

    /**
     * 读动作对象的 do_number（一共做几次），读不到返回 {@link RunningAction#NO_NUMBER}。
     *
     * <p>跟 {@link #intervalOf} 走同一条路：先 {@code getHandle()}，再读
     * {@code getDoNumber()}——两个方法都挂在 Leaves 的同一个动作基类上
     * （{@code ServerBotAction}），所以反射出来的 handle 是同一个对象。
     *
     * <p>读不出来只意味着"不知道它打算做几次"，界面退化成按名字 + 间隔判断，
     * 不影响起停动作本身。{@code -1} 是合法值（无限次），占位得用
     * {@link RunningAction#NO_NUMBER}。
     */
    private int doNumberOf(Object action) {
        try {
            Method toHandle = actionHandleMethod;
            if (toHandle == null) {
                toHandle = find(action.getClass(), "getHandle");
                if (toHandle == null) {
                    return RunningAction.NO_NUMBER;
                }
                this.actionHandleMethod = toHandle;
            }
            Object handle = toHandle.invoke(action);
            if (handle == null) {
                return RunningAction.NO_NUMBER;
            }
            Method number = actionNumberMethod;
            if (number == null) {
                number = find(handle.getClass(), "getDoNumber");
                if (number == null) {
                    return RunningAction.NO_NUMBER;
                }
                this.actionNumberMethod = number;
            }
            return (int) number.invoke(handle);
        } catch (Throwable t) {
            return RunningAction.NO_NUMBER;
        }
    }

    /**
     * Leaves 是否允许玩家右键打开假人背包（{@code modify.fakeplayer.open-fakeplayer-inventory}）。
     *
     * <p>读不到时返回 false——此时 {@link BotInventoryGuard} 不会报警告，
     * 因为它也无从判断该功能到底开没开。
     */
    public boolean isInventoryOpenEnabled() {
        Object fakeplayer = fakeplayer();
        if (fakeplayer == null) {
            return false;
        }
        try {
            return (boolean) fakeplayer.getClass().getField("canOpenInventory").get(fakeplayer);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Leaves 的全局假人上限（注意是**全服**上限，不是每玩家）；读不到返回 -1。 */
    public int getGlobalLimit() {
        Object fakeplayer = fakeplayer();
        if (fakeplayer == null) {
            return -1;
        }
        try {
            return ((Number) fakeplayer.getClass().getField("limit").get(fakeplayer)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    private String fakeplayerString(String field, String def) {
        Object fakeplayer = fakeplayer();
        if (fakeplayer == null) {
            return def;
        }
        try {
            Object value = fakeplayer.getClass().getField(field).get(fakeplayer);
            return value == null ? def : value.toString();
        } catch (Throwable t) {
            return def;
        }
    }

    private Object fakeplayer() {
        try {
            Class<?> configClass = Class.forName(SERVER_CONFIG);
            Field modifyField = configClass.getField("modify");
            Object modify = modifyField.get(null);
            return modify.getClass().getField("fakeplayer").get(modify);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 raw name 拼成 Leaves 里的全名。 */
    public String fullName(String rawName) {
        return getPrefix() + rawName + getSuffix();
    }

    /**
     * 去掉配置的前后缀，取出中间那段 raw name；不匹配返回 null。
     */
    public String rawPartOf(String fullName) {
        if (fullName == null) {
            return null;
        }
        String prefix = getPrefix();
        String suffix = getSuffix();
        if (fullName.length() < prefix.length() + suffix.length()) {
            return null;
        }
        if (!fullName.startsWith(prefix) || !fullName.endsWith(suffix)) {
            return null;
        }
        return fullName.substring(prefix.length(), fullName.length() - suffix.length());
    }

    /** 全名是否符合本插件的命名规则 {@code <前缀><玩家名>.<编号><后缀>}。 */
    public boolean isOurName(String fullName) {
        String raw = rawPartOf(fullName);
        return raw != null && RAW_PART.matcher(raw).matches();
    }

    /**
     * 全名是否可用（长度、形状都对）。
     *
     * <p>Leaves 的硬约束是 {@code ^[a-zA-Z0-9_]{4,16}$}，但本插件的名字里**故意**带了
     * {@link #SEPARATOR}（见 {@link BotNameGate}），永远不可能匹配那个正则。
     * 所以这里改成**按本插件的命名规则校验**：长度仍守 Leaves 的 4-16，
     * 中间那段必须是"玩家名 + 编号"的形状。
     */
    public boolean isFullNameLegal(String fullName) {
        if (fullName == null || fullName.length() < 4 || fullName.length() > 16) {
            return false;
        }
        return isOurName(fullName);
    }

    /**
     * 当前命名是否已与玩家名隔离，即假人全名里含有真人不可能有的字符。
     *
     * <p>只看 {@link #SEPARATOR}——它在每个假人名里都出现，只要它不是
     * {@code [a-zA-Z0-9_]} 里的字符，这套保护就是成立的。
     * 为 false 时 {@link BotNameGate} 完全不介入，Leaves 的原始校验原样保留。
     */
    public boolean isNamespaced() {
        return hasMarkerChar(SEPARATOR);
    }

    /** 字符串里是否含有 {@code [a-zA-Z0-9_]} 之外的字符。 */
    public static boolean hasMarkerChar(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!plain) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归属判定：{@code rawName} 是否是 {@code playerName} 名下的假人。
     *
     * <p>要求形如 {@code <玩家名>.<纯数字>}。数字那一段不能省——玩家 {@code B} 和
     * 玩家 {@code B.1} 同时存在时，少了它 {@code B} 会把 {@code B.1.1} 也认成自己的。
     *
     * <p>这是全插件唯一的归属口径：{@link #botsOf}（上下线收人）与背包归属判定共用，
     * 免得两处不一致，出现「背包打不开、但下线时会被收走」这类怪事。
     */
    public static boolean ownsRawName(String playerName, String rawName) {
        if (playerName == null || rawName == null) {
            return false;
        }
        String prefix = playerName + SEPARATOR;
        if (!rawName.startsWith(prefix)) {
            return false;
        }
        String tail = rawName.substring(prefix.length());
        if (tail.isEmpty()) {
            return false;
        }
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 取出属于 {@code playerName} 的假人编号；不属于他或解析不出返回 -1。
     *
     * <p>与 {@link #ownsRawName} 共用同一套口径，不会出现"认得出归属但读不出编号"的情况。
     */
    public static int indexOf(String playerName, String rawName) {
        if (!ownsRawName(playerName, rawName)) {
            return -1;
        }
        try {
            return Integer.parseInt(rawName.substring(playerName.length() + SEPARATOR.length()));
        } catch (NumberFormatException e) {
            // ownsRawName 已经保证尾部全是数字，唯一可能是超出 int 范围
            return -1;
        }
    }
}
