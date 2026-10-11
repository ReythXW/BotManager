package com.botmanager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;

/**
 * 假人背包归属守卫：只允许玩家打开自己召唤的假人的背包。
 *
 * <h2>为什么需要它</h2>
 * Leaves 的 {@code modify.fakeplayer.open-fakeplayer-inventory} 打开后，
 * 玩家空手右键任意假人即可查看并翻动它的背包——包括别人的假人。
 * 在本插件把假人开放给玩家之后，这等于给了所有人翻别人家当的权限。
 *
 * <h2>怎么修</h2>
 * Leaves 在真正调 {@code player.openMenu(...)} 之前，会先派发可取消的
 * {@code BotInventoryOpenEvent}（{@code ServerBot#interact}）：
 *
 * <pre>
 * 玩家右键假人 → ServerBot.interact()
 *                      │
 *                      ▼
 *            BotInventoryOpenEvent（可取消）
 *                      │
 *          ┌───────────┴───────────┐
 *       是自己的                不是自己的
 *          │                       │
 *      放行，开背包          setCancelled(true)
 * </pre>
 *
 * <p>归属口径与上下线同步完全一致，都用 {@link LeavesBridge#ownsRawName}——
 * 不会出现「背包打不开、但一下线就被收走」这种前后矛盾的行为。
 */
public final class BotInventoryGuard implements Listener {

    /** Leaves 的事件类名。用字符串引用，避免编译期依赖具体 Leaves 版本。 */
    private static final String BOT_INVENTORY_EVENT = "org.leavesmc.leaves.event.bot.BotInventoryOpenEvent";

    /** 能打开任意假人背包的权限（管理员排障用）。 */
    public static final String PERMISSION_OTHERS = "botmanager.inventory.others";

    private final BotManager plugin;

    private Method getBotMethod;
    private Method getOpenPlayerMethod;
    private boolean registered;

    public BotInventoryGuard(BotManager plugin) {
        this.plugin = plugin;
    }

    /** 监听器是否注册成功。false 表示当前服务端没有该事件，本项保护不生效。 */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * 动态注册监听器。
     *
     * <p>与 {@link BotActionInterceptor} 同样不用 {@code @EventHandler} 注解：
     * 事件类来自 Leaves，编译期依赖它会让插件在非 Leaves 服务端上直接
     * NoClassDefFoundError。动态注册可以在运行时探测，不存在时优雅退出。
     *
     * @return 当前服务端是否支持该事件
     */
    public boolean register() {
        try {
            Class<?> raw = Class.forName(BOT_INVENTORY_EVENT);
            if (!Event.class.isAssignableFrom(raw) || !Cancellable.class.isAssignableFrom(raw)) {
                return false;
            }
            this.getBotMethod = raw.getMethod("getBot");
            this.getOpenPlayerMethod = raw.getMethod("getOpenPlayer");

            Bukkit.getPluginManager().registerEvent(
                    raw.asSubclass(Event.class),
                    this,
                    EventPriority.NORMAL,
                    this::handle,
                    plugin,
                    false
            );
            this.registered = true;
            return true;
        } catch (Throwable t) {
            // 老版本 Leaves 没有这个事件属正常。但若服务端开着 open-fakeplayer-inventory
            // 而我们又该管这件事，却拿不到事件，说明保护实际是失效的——
            // 必须吼一声，不能静默漏过去。
            if (plugin.getConfig().getBoolean("bot.restrict-inventory-to-owner", true)
                    && plugin.getLeaves().isInventoryOpenEnabled()) {
                plugin.getLogger().warning("Leaves 已开启 open-fakeplayer-inventory，"
                        + "但本插件接不上 BotInventoryOpenEvent，任何玩家都能翻别人的假人背包。");
                plugin.getLogger().warning("原因：" + t);
            }
            return false;
        }
    }

    private void handle(Listener listener, Event event) {
        // 每一 tick 现读配置，这样 /bm reload 能立刻生效，不必重注册监听器
        if (!plugin.getConfig().getBoolean("bot.restrict-inventory-to-owner", true)) {
            return;
        }
        try {
            Player opener = (Player) getOpenPlayerMethod.invoke(event);
            if (opener == null) {
                // 理论上不会发生（Leaves 只在玩家右键时派发）。真出现了也无法归属，
                // 放行——否则就成了「谁都打不开背包」，比漏一个还糟。
                return;
            }
            if (opener.hasPermission(PERMISSION_OTHERS)) {
                return;
            }

            Player bot = (Player) getBotMethod.invoke(event);
            String raw = bot == null ? null : plugin.getLeaves().rawNameOf(bot);
            if (LeavesBridge.ownsRawName(opener.getName(), raw)) {
                return;
            }

            ((Cancellable) event).setCancelled(true);
            opener.sendMessage(ChatColor.RED + "[BotManager] 这不是你的假人，只能打开自己召唤的假人的背包。");
            if (plugin.getSettings().isDebug()) {
                plugin.getLogger().info("已拦截 " + opener.getName()
                        + " 打开假人 " + (bot == null ? "?" : bot.getName()) + " 的背包。");
            }
        } catch (Throwable t) {
            // 与拦截器同一原则：插件自身出错绝不能变成绕过通道，所以这里默认拦下。
            plugin.getLogger().warning("处理假人背包开启事件失败，已按不放行处理：" + t);
            if (event instanceof Cancellable cancellable) {
                cancellable.setCancelled(true);
            }
        }
    }
}
