package com.botmanager;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直接查询 Residence 的 flag 判定结果。
 *
 * <p>绝大多数的保护场景都是通过「伪造 Bukkit 事件」来覆盖的（见 {@link ResidenceHook}），
 * 但个别事件需要一个真实的实体作为构造参数（例如 PlayerDropItemEvent 需要物品实体、
 * PlayerFishEvent 需要鱼钩实体），无法安全伪造。这类少数场景改为直接读取对应 flag。
 */
public final class ResidenceFlags {

    /** 占位符，用于在缓存里标记「Residence 没有这个 flag」，避免每次都重新反射查找。 */
    private static final Object ABSENT = new Object();

    private final BotManager plugin;
    private final ResidenceCache cache;
    private final Map<String, Object> flagConstants = new ConcurrentHashMap<>();

    private Class<?> flagsClass;
    private Method getPermsMethod;
    private Method playerHasMethod;
    private boolean ready;

    public ResidenceFlags(BotManager plugin, ResidenceCache cache) {
        this.plugin = plugin;
        this.cache = cache;
    }

    /**
     * 连接 Residence 的内部判定接口。
     *
     * @return 是否成功。失败时 {@link #has} 会一律放行，避免因接口变动把假人完全锁死。
     */
    public boolean setup() {
        try {
            Class<?> permsClass = Class.forName("com.bekvon.bukkit.residence.protection.FlagPermissions");
            // 6.x 在 containers 下；5.x 在 protection 下。已确认 6.0.0.1 ~ 6.0.3.2 均为 containers。
            this.flagsClass = findClass(
                    "com.bekvon.bukkit.residence.containers.Flags",
                    "com.bekvon.bukkit.residence.protection.Flags");
            this.getPermsMethod = permsClass.getMethod("getPerms", Location.class, Player.class);
            this.playerHasMethod = permsClass.getMethod("playerHas", Player.class, flagsClass, boolean.class);
            this.ready = true;
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("无法连接 Residence 的 flag 判定接口（这部分检查将放行）：" + t);
            this.ready = false;
            return false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    /** 依次尝试若干候选类名，返回第一个存在的。 */
    private static Class<?> findClass(String... candidates) throws ClassNotFoundException {
        for (String name : candidates) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // 试下一个
            }
        }
        throw new ClassNotFoundException(String.join(" / ", candidates));
    }

    /**
     * 判断玩家在指定位置是否拥有某个 flag。
     *
     * @param flagName Residence 的 flag 名，例如 itemdrop / hook
     * @param def      该 flag 未显式设置时的默认值，通常传 true（与 Residence 原生行为一致）
     */
    public boolean has(Player player, Location loc, String flagName, boolean def) {
        if (!ready || player == null || loc == null) {
            return true;
        }
        // playerHas 在拿不到 PermissionGroup 时会一律返回 false，
        // 所以查之前必须确保 Residence 认得这个假人（详见 ResidenceCache）。
        cache.prime(player);
        try {
            return rawHas(player, loc, flagName, def);
        } catch (Throwable t) {
            plugin.getLogger().warning("读取 Residence flag [" + flagName + "] 时出错，已放行：" + t);
            return true;
        }
    }

    /** 与 {@link #has} 判定一致，但异常直接抛出，供诊断用。 */
    private boolean rawHas(Player player, Location loc, String flagName, boolean def) throws Exception {
        Object flag = flagConstants.computeIfAbsent(flagName, name -> {
            try {
                return flagsClass.getField(name).get(null);
            } catch (Throwable ignored) {
                return ABSENT;
            }
        });
        if (flag == ABSENT) {
            // Residence 里没有这个 flag，视为不限制
            return def;
        }
        Object perms = getPermsMethod.invoke(null, loc, player);
        if (perms == null) {
            return def;
        }
        return (boolean) playerHasMethod.invoke(perms, player, flag, def);
    }

    /**
     * 诊断：报告 Residence 对某个假人在指定位置的 flag 判定。
     *
     * <p>关键在于复刻 {@code ResidenceBlockListener.canBreakBlock} 里的那个表达式：
     * <pre>playerHas(destroy, playerHas(build, true))</pre>
     * 如果这里是 false，先看上面的「Residence 档案」一行——假人没有档案时
     * {@code playerHas} 会**一律返回 false**，与 flag 怎么设的无关，那不是本插件拦错了。
     */
    public List<String> diagnose(Player bot, Location loc) {
        List<String> out = new ArrayList<>();
        if (!ready) {
            out.add("  flag 直查接口未就绪（见启动日志的警告），以下无法诊断");
            return out;
        }
        out.add("  Residence 档案  : " + (cache.isReady()
                ? "已接通（本次查询前已确保建立）"
                : "未接通！假人的 flag 判定会一律为拒绝，set 过的权限也不生效"));
        cache.prime(bot);
        try {
            Object perms = getPermsMethod.invoke(null, loc, bot);
            out.add("  getPerms        : " + (perms == null ? "null" : perms.getClass().getSimpleName()));
            if (perms == null) {
                return out;
            }
            boolean build = rawHas(bot, loc, "build", true);
            boolean destroy = rawHas(bot, loc, "destroy", build);
            out.add("  build           : " + build + "   (默认 true)");
            out.add("  destroy         : " + destroy + "   (默认 = build 的结果)");
            out.add("  canBreakBlock   : " + destroy + (destroy ? "  -> Residence 本应放行" : "  -> Residence 本应拦下"));
        } catch (Throwable t) {
            out.add("  诊断出错：" + t);
        }
        return out;
    }
}
