package com.botmanager;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 让 Residence 认得假人——这是「假人能否按领地 flag 工作」的前提。
 *
 * <p><b>问题</b>：Residence 判定 flag 走 {@code FlagPermissions.playerHas(...)}，
 * 它开头有一道闸：
 * <pre>
 *   PermissionGroup group = PermissionGroup.getGroup(player);
 *   if (group == null)
 *       return false;        // ← 直接拒绝，set / pset 都不看
 * </pre>
 * 而 {@code getGroup} 只查 {@code PlayerManager.playersByUUID} 这个**内存缓存**，
 * 不会自动加载。缓存的自动建立只有一条路：{@code PlayerJoinEvent} →
 * {@code playerJoin(player)}。
 *
 * <p><b>假人为什么进不去</b>：Leaves 创建假人时派发的是 {@code BotJoinEvent}
 * （{@code extends BotEvent}，自带 {@code getHandlerList()}），
 * 和 {@code PlayerJoinEvent} **没有任何继承关系**，Residence 的
 * {@code onPlayerJoin} 收不到。于是全新假人在 Residence 眼里「没有权限组」，
 * 任何 flag 判定都直接返回 false——领地 {@code /res set} 设过什么都白搭。
 *
 * <p><b>解法</b>：替假人把那条 {@code PlayerJoinEvent} 补上——直接调
 * {@code Residence.getInstance().getPlayerManager().getResidencePlayer(bot)}。
 * 这个重载会 {@code new ResidencePlayer} + {@code addPlayer}（写进 playersByUUID）
 * + 排队存档，之后 Residence 的**全部**监听器就都能正常认出这个假人了，
 * 判定链路也就回到 {@code pset > 组 > set > flag 默认值} 的正常顺序。
 *
 * <p><b>两个必须绕开的坑（2026-10 修复）</b>。
 * <p>其一，<b>类加载器</b>：Bukkit 每个插件各有一个类加载器，搜索路径按
 * plugin.yml 的 {@code depend/softdepend} 建。Residence 声明了
 * {@code softdepend: WorldEdit}，它的 loader 看得到 WE；本插件没声明，
 * 看不到。用本插件默认的 {@code Class.forName} 加载 Residence 的类，
 * 轻则另定义一份静态状态与 Residence 实际使用的脱节，
 * 重则链接时找不到 WE 类。所以统一从
 * {@code residence.getClass().getClassLoader()} 取类。
 *
 * <p>其二，<b>反射入口</b>：{@code Residence.class.getMethod("getPlayerManager")}
 * 会遍历类的**全部**方法签名来决定重载，而 Residence 里有一个
 * {@code getWorldEdit()} 返回 {@code com.sk89q.worldedit.bukkit.WorldEditPlugin}。
 * 目标服务器没装 WorldEdit，这一步就抛
 * {@code NoClassDefFoundError: com/sk89q/worldedit/bukkit/WorldEditPlugin}——
 * 哪怕我们根本不调用 getWorldEdit。改用 {@link MethodHandle} 只解析
 * {@code getPlayerManager} 一个方法，不碰其它签名。
 *
 * <p>每个 UUID 只做一次（记在 {@link #primed} 里去重）：这一步会写盘，
 * 反复调用没有意义。
 */
public final class ResidenceCache {

    private final BotManager plugin;

    /** Residence 插件实例。反射调用与类加载都以它为准。 */
    private final Plugin residence;

    /** 已经预热过的假人。UUID 由假人名字推导，名字不变则 UUID 不变，可以长期持有。 */
    private final Set<UUID> primed = ConcurrentHashMap.newKeySet();

    private MethodHandle getPlayerManager;
    private Method getResidencePlayer;
    private boolean ready;

    public ResidenceCache(BotManager plugin, Plugin residence) {
        this.plugin = plugin;
        this.residence = residence;
    }

    /**
     * 连接 Residence 的玩家管理器。
     *
     * @return 是否成功。失败时 {@link #prime} 变成空操作，
     *         假人会退回「Residence 一律拒绝」的旧行为（保护仍在，只是过严）。
     */
    public boolean setup() {
        try {
            // 关键一：用 Residence 自己的类加载器。详见类注释。
            ClassLoader loader = residence.getClass().getClassLoader();
            Class<?> residenceClass = Class.forName("com.bekvon.bukkit.residence.Residence", true, loader);
            Class<?> managerClass = Class.forName("com.bekvon.bukkit.residence.protection.PlayerManager", true, loader);

            // 关键二：不能用 residenceClass.getMethod("getPlayerManager")。
            // 它会解析 Residence 的全部方法签名，遇到 getWorldEdit() 的
            // WorldEditPlugin 返回类型就抛 NoClassDefFoundError（服务器没装 WE 时必现）。
            // MethodHandle 只解析指定的这一个方法，不遍历其它签名。
            this.getPlayerManager = MethodHandles.lookup().findVirtual(
                    residenceClass, "getPlayerManager", MethodType.methodType(managerClass));

            // PlayerManager 自身不引用 WorldEdit，getMethod 是安全的。
            // 四个重载里必须精确取 (Player) 这个——只有它会走 playerJoin 建缓存；
            // (UUID) 那个是纯查表，正是拿不到值的那条路。
            this.getResidencePlayer = managerClass.getMethod("getResidencePlayer", Player.class);
            this.ready = true;
            return true;
        } catch (Throwable t) {
            String hint = "";
            if (plugin.getServer().getPluginManager().getPlugin("WorldEdit") == null
                    && plugin.getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") == null) {
                hint = "（服务端未安装 WorldEdit / FastAsyncWorldEdit，"
                        + "而当前 Residence 版本的玩家档案类引用了它）";
            }
            plugin.getLogger().warning("无法接入 Residence 的玩家档案接口" + hint + "，"
                    + "假人在领地里会被一律判定为无权限（set 过的 flag 也不生效）：" + t);
            this.ready = false;
            return false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 确保 Residence 的玩家缓存里有这个假人。幂等，可放心在热路径上调用。
     *
     * <p>已在缓存里时这里只是一次 Set 查找，不做反射。
     */
    public void prime(Player bot) {
        if (!ready || bot == null) {
            return;
        }
        UUID id = bot.getUniqueId();
        if (!primed.add(id)) {
            return;
        }
        try {
            Object manager = getPlayerManager.invoke(residence);
            if (manager == null) {
                primed.remove(id);
                return;
            }
            // 调两次，第二次是必要的：
            //   第 1 次走 playerJoin 建档案，但那时档案还不知道自己叫什么——
            //          Residence 靠 Bukkit.getPlayer(uuid) 反查名字，而假人不在
            //          getOnlinePlayers() 里，于是名字被存成 null。
            //   第 2 次命中缓存，走 updatePlayer(player) 把真名补上，
            //          之后 /res 里看到的就是 test.1 而不是 null。
            // 两次都是纯内存操作：存档只是排进待写队列，不落盘、无网络请求。
            Object created = getResidencePlayer.invoke(manager, bot);
            if (created != null) {
                getResidencePlayer.invoke(manager, bot);
            }
        } catch (Throwable t) {
            // 失败就撤销标记，下次再试——否则这个假人会永远停在"无权限组"的状态
            primed.remove(id);
            plugin.getLogger().warning("为假人 " + bot.getName()
                    + " 建立 Residence 档案失败，它在领地里的权限判定会一直是拒绝：" + t);
        }
    }

    /**
     * 丢弃预热记录，让所有假人下次重新建立档案。
     *
     * <p>Residence 被重载时其内存缓存会清空，我们这边的记录必须跟着失效，
     * 否则会以为"已经建过"而不再补建。
     */
    public void reset() {
        primed.clear();
    }

    /** 已预热的假人数量，供 {@code /bm status} 显示。 */
    public int primedCount() {
        return primed.size();
    }
}
