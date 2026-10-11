package com.botmanager;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;

/**
 * 假人命名空间守卫：把假人的名字挪到真人拿不到的字符集里去。
 *
 * <h2>问题</h2>
 * 假人的全名就是它的游戏档案名，和玩家名字用的是同一个名字空间。玩家名的合法字符是
 * {@code [a-zA-Z0-9_]}，Leaves 允许的假人名也是 {@code [a-zA-Z0-9_]}——两边完全重合。
 * 所以 {@code bot_test_1} 这个名字任何人都能注册，注册之后：
 * <ul>
 *   <li>他自己被 Leaves 当成"同名玩家"，一进服就把假人连背包一起清掉（见 {@link BotService#onLogin}）；</li>
 *   <li>主人 {@code test} 从此再也用不了这个编号。</li>
 * </ul>
 * 靠"检测到撞名再处理"永远只是补救，防不住。
 *
 * <h2>解法</h2>
 * 让假人名字里含一个真人拿不到的字符。{@link LeavesBridge#SEPARATOR} 取 {@code "."}，
 * 于是假人名长成 {@code test.1}——**不可能**被注册成玩家名，因为 Minecraft 只接受
 * {@code [a-zA-Z0-9_]}，{@code .} 不在其中。这是结构性的隔离，不依赖任何运行时判定。
 *
 * <p>注意 {@code .} 是插件自己放在 **raw name** 里的，**不需要动 Leaves 的
 * {@code modify.fakeplayer.prefix}/{@code suffix}**（那两个留空即可）。这样做的好处是
 * 不占用 16 字符额度，也不受 Leaves 侧配置变更影响。
 *
 * <p>麻烦在于 Leaves 自己会把这个名字拦下来（{@code BotUtil.isCreateLegal} 要求全名匹配
 * {@code ^[a-zA-Z0-9_]{4,16}$}）。但它的写法留了口子：
 *
 * <pre>
 * BotList.createNewBot:
 *     event.setCancelled(!BotUtil.isCreateLegal(state.fullName()));   ← 先按正则取消
 *     pluginManager.callEvent(event);                                 ← 再派发
 *     if (event.isCancelled()) return null;                           ← 唯一的一道闸
 * </pre>
 *
 * {@code BotCreateEvent} 是 {@link Cancellable}，所以插件在监听器里把它改回放行即可，
 * **不需要改动或重新编译 Leaves**。事件之后整条创建路径再无校验：
 * {@code createBotProfile} 直接 {@code new GameProfile(uuid, name, ...)}，不经
 * {@code NameValidator}；{@code placeNewBot} 也不碰名字。存档加载路径
 * （{@code BotList.loadNewBot}）更是压根不过这个正则。
 *
 * <h2>放行的边界</h2>
 * 只在**同时**满足下列条件时放行，任何一条不满足都维持 Leaves 的取消决定：
 * <ol>
 *   <li>命名确实与玩家名隔离了（{@link LeavesBridge#isNamespaced()}）——
 *       没有隔离时本类完全不介入，Leaves 的原始校验原样保留；</li>
 *   <li>全名符合本插件的命名规则 {@code <玩家名>.<编号>}——
 *       所以 {@code /bot create foo.bar} 这种名字依然会被挡住；</li>
 *   <li>当前没有同名假人在线——Leaves 的查重（{@code getBotByName}）被我们一并绕过了，
 *       得自己补回来，否则会造出重名假人。</li>
 * </ol>
 *
 * <p>出错时**维持 Leaves 的取消决定**：最坏结果是假人造不出来，不会污染名字空间。
 */
public final class BotNameGate implements Listener {

    /** Leaves 的事件类名。用字符串引用，避免编译期依赖具体 Leaves 版本。 */
    private static final String BOT_CREATE_EVENT = "org.leavesmc.leaves.event.bot.BotCreateEvent";

    private final BotManager plugin;
    private final LeavesBridge leaves;

    private Method getBotMethod;
    private boolean registered;

    public BotNameGate(BotManager plugin, LeavesBridge leaves) {
        this.plugin = plugin;
        this.leaves = leaves;
    }

    /** 监听器是否注册成功。false 表示当前服务端没有该事件。 */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * 动态注册监听器。
     *
     * <p>与 {@link BotInventoryGuard} 同样不用 {@code @EventHandler} 注解：
     * 事件类来自 Leaves，编译期依赖它会让插件在非 Leaves 服务端上直接 NoClassDefFoundError。
     */
    public boolean register() {
        try {
            Class<?> raw = Class.forName(BOT_CREATE_EVENT);
            if (!Event.class.isAssignableFrom(raw) || !Cancellable.class.isAssignableFrom(raw)) {
                return false;
            }
            this.getBotMethod = raw.getMethod("getBot");

            Bukkit.getPluginManager().registerEvent(
                    raw.asSubclass(Event.class),
                    this,
                    EventPriority.LOW,
                    this::handle,
                    plugin,
                    // 必须 false：Leaves 是先把事件设成"已取消"再派发的，
                    // 用 true 的话本监听器根本不会被调用，等于白注册。
                    false
            );
            this.registered = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void handle(Listener listener, Event event) {
        try {
            Cancellable cancellable = (Cancellable) event;
            if (!cancellable.isCancelled()) {
                return; // Leaves 本来就放行，不关我们的事
            }
            if (!leaves.isNamespaced()) {
                return; // 命名没有与玩家名隔离，Leaves 的原判定原样保留
            }

            String full = (String) getBotMethod.invoke(event);
            if (full == null || !leaves.isOurName(full)) {
                return; // 不是本插件的命名规则（例如手工 /bot create 的怪名字）
            }
            if (leaves.findByFullName(full) != null) {
                return; // 已有同名假人在线，补上被绕过的查重
            }

            cancellable.setCancelled(false);
            if (plugin.getSettings().isDebug()) {
                plugin.getLogger().info("已按特殊命名空间放行假人名 " + full + "。");
            }
        } catch (Throwable t) {
            // 维持 Leaves 的取消决定：最坏是假人造不出来，不会造成名字污染
            plugin.getLogger().warning("处理假人创建事件失败，已按 Leaves 的原判定处理：" + t);
        }
    }
}
