package com.botmanager;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把「伪造的 Bukkit 事件」只投递给 Residence 自己的监听器。
 *
 * <p>这样做的好处：
 * <ul>
 *   <li>Residence 的全部保护逻辑（135 个 flag 加上物品黑名单、租赁、
 *       raid 等非 flag 逻辑）原样生效，不需要我们逐个 flag 去手工复刻；</li>
 *   <li>其它插件完全收不到这些伪造事件，不会被误触发副作用
 *       （比如连锁挖矿、任务统计、掉落加倍）。</li>
 * </ul>
 *
 * <p>返回值的语义：{@code true} = Residence 未拦截（允许），
 * {@code false} = Residence 取消了该事件（拒绝）。
 */
public final class ResidenceHook {

    private final BotManager plugin;
    private final Plugin residence;

    /**
     * 事件类型 -> Residence 注册在该事件上的监听器。
     * 事件类型数量有限（十几种），启动后基本不变，缓存下来避免每次动作都遍历 HandlerList。
     */
    private final Map<Class<? extends Event>, List<RegisteredListener>> listenerCache = new ConcurrentHashMap<>();

    /** 最近一次派发中 Residence 抛出的异常，供 /bm debug 显示。 */
    private volatile String lastFailure;

    public ResidenceHook(BotManager plugin, Plugin residence) {
        this.plugin = plugin;
        this.residence = residence;
    }

    /** 配置重载后调用，丢弃缓存以便重新扫描监听器。 */
    public void invalidateCache() {
        listenerCache.clear();
    }

    /**
     * 取回 Residence 部署在指定事件类型上的监听器。
     */
    private List<RegisteredListener> listenersFor(Class<? extends Event> eventClass) {
        List<RegisteredListener> cached = listenerCache.get(eventClass);
        if (cached != null) {
            return cached;
        }

        List<RegisteredListener> found = new ArrayList<>(2);
        try {
            // 每个具体事件类都有一个静态的 getHandlerList()，无需实例化即可拿到它的监听器列表
            HandlerList handlers = (HandlerList) eventClass.getMethod("getHandlerList").invoke(null);
            for (RegisteredListener registered : handlers.getRegisteredListeners()) {
                if (registered.getPlugin() == residence) {
                    found.add(registered);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("无法读取事件 " + eventClass.getSimpleName()
                    + " 的监听器列表，该事件将不做检查：" + t);
        }

        List<RegisteredListener> immutable = List.copyOf(found);
        listenerCache.put(eventClass, immutable);
        return immutable;
    }

    /**
     * 把事件投递给 Residence 并判断是否被拒绝。
     *
     * <p>若 Residence 根本没有监听该事件，说明它不关心这类操作，直接放行。
     *
     * @return true 表示允许该操作，false 表示被 Residence 拦下
     */
    public boolean dispatch(Event event) {
        List<RegisteredListener> listeners = listenersFor(event.getClass());
        if (listeners.isEmpty()) {
            return true;
        }

        for (RegisteredListener registered : listeners) {
            try {
                // callEvent 内部会遵守 ignoreCancelled 语义：事件一旦被取消，
                // 后续声明了 ignoreCancelled 的监听器会被自动跳过，与真实派发一致
                registered.callEvent(event);
            } catch (Throwable t) {
                // 单个监听器出错不能演变成"假人绕过保护"，这里选择保守处理
                lastFailure = event.getClass().getSimpleName() + " -> " + t;
                plugin.getLogger().warning("Residence 处理 " + event.getClass().getSimpleName()
                        + " 时抛出异常：" + t);
                return false;
            }
        }
        lastFailure = null;
        return !isDenied(event);
    }

    /** 最近一次派发时 Residence 抛出的异常；null 表示上次派发正常。 */
    public String getLastFailure() {
        return lastFailure;
    }

    /**
     * 判断事件是否表达了「拒绝」。
     * 除了常规的 setCancelled，方块交互类事件还可能通过 setUseInteractedBlock(DENY) 表达拒绝。
     */
    private boolean isDenied(Event event) {
        if (event instanceof Cancellable cancellable && cancellable.isCancelled()) {
            return true;
        }
        if (event instanceof PlayerInteractEvent interact) {
            return interact.useInteractedBlock() == Event.Result.DENY;
        }
        return false;
    }

    /** 该事件类型上是否存在 Residence 监听器，用于启动时自检。 */
    public boolean isWatching(Class<? extends Event> eventClass) {
        return !listenersFor(eventClass).isEmpty();
    }
}
