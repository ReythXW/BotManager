package com.botmanager;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 假人动作拦截器：本插件的核心。
 *
 * <h2>为什么需要它</h2>
 * Leaves 的假人执行操作时不走 Paper 打过补丁的逻辑：
 * <ul>
 *   <li>{@code ServerBotGameMode#destroyBlock} 直接调用 {@code level.removeBlock()}，
 *       跳过了 {@code BlockBreakEvent}；</li>
 *   <li>{@code ServerBotGameMode#useItemOn} 跳过了 {@code PlayerInteractEvent} /
 *       {@code BlockPlaceEvent}。</li>
 * </ul>
 * 结果是 Residence 的监听器根本收不到假人的任何操作，保护形同虚设。
 *
 * <h2>怎么修</h2>
 * 所有 bot 动作在真正执行前都会经过
 * {@code AbstractBotAction#tryTick} 派发的 {@code BotActionExecuteEvent}（可取消）。
 * 我们在该事件里，按动作类型还原出「如果是个真实玩家，服务端本该触发的事件」，
 * 交给 {@link ResidenceHook} 只投递给 Residence 判定；被拒就取消本次动作。
 *
 * <p>因为判定完全由 Residence 自己的监听器完成，它的全部 flag
 * （build/destroy/place/use/container/button/door/pvp/animalfeeding/
 * vehicledestroy/hook/itemdrop……）以及非 flag 逻辑（物品黑名单、租赁、raid）
 * 都会自动生效，不需要在本插件里逐条复刻。
 */
public final class BotActionInterceptor implements Listener {

    /** Leaves 的事件类名。用字符串引用，避免编译期依赖具体 Leaves 版本。 */
    private static final String BOT_ACTION_EVENT = "org.leavesmc.leaves.event.bot.BotActionExecuteEvent";

    private final BotManager plugin;
    private final GuardSettings settings;
    private final ResidenceHook hook;
    private final ResidenceFlags flags;
    private final ResidenceCache cache;

    private Class<? extends Event> eventClass;
    private Method getActionNameMethod;
    private Method getBotMethod;

    public BotActionInterceptor(BotManager plugin, GuardSettings settings,
                                ResidenceHook hook, ResidenceFlags flags, ResidenceCache cache) {
        this.plugin = plugin;
        this.settings = settings;
        this.hook = hook;
        this.flags = flags;
        this.cache = cache;
    }

    /**
     * 动态注册监听器。
     *
     * <p>不用 {@code @EventHandler} 注解是因为事件类来自 Leaves，若编译期依赖它，
     * 插件在非 Leaves 服务端上会直接 NoClassDefFoundError。动态注册则可以在
     * 运行时探测，不存在时优雅退出。
     *
     * @return 当前服务端是否支持（即是否为 Leaves）
     */
    public boolean register() {
        try {
            Class<?> raw = Class.forName(BOT_ACTION_EVENT);
            if (!Event.class.isAssignableFrom(raw)) {
                return false;
            }
            this.eventClass = raw.asSubclass(Event.class);
            this.getActionNameMethod = raw.getMethod("getActionName");
            // BotEvent#getBot() 返回 Leaves 的 Bot 接口，而 Bot extends Player
            this.getBotMethod = raw.getMethod("getBot");
        } catch (Throwable t) {
            return false;
        }

        Bukkit.getPluginManager().registerEvent(
                eventClass,
                this,
                EventPriority.LOWEST,
                this::handle,
                plugin,
                false
        );
        return true;
    }

    /**
     * 事件入口。这里抛出的任何异常都必须被吞掉——绝不能让插件自身的错误
     * 变成「假人绕过保护」的通道。
     */
    private void handle(Listener listener, Event event) {
        if (!settings.isEnabled() || event.getClass() != eventClass) {
            return;
        }

        String action;
        Player bot;
        try {
            action = (String) getActionNameMethod.invoke(event);
            Object who = getBotMethod.invoke(event);
            if (!(who instanceof Player player)) {
                return;
            }
            bot = player;
        } catch (Throwable t) {
            // 连动作名都取不到，无法判断意图，保守拦下
            cancel(event);
            return;
        }

        if (action == null || !bot.isOnline()) {
            return;
        }

        // 兜底补建 Residence 档案。正常路径上 BotJoinEvent 已经建好了，这里只是
        // 一次 Set 查找；能兜住插件重载、Residence 重载后没来得及重建的情况。
        // 少了这一步，下面所有 flag 判定都会因为「查不到权限组」而一律返回拒绝。
        cache.prime(bot);

        boolean allowed;
        try {
            allowed = check(action, bot);
        } catch (Throwable t) {
            plugin.getLogger().warning("检查假人 " + bot.getName() + " 的 "
                    + action + " 动作时出错，已拦截该动作：" + t);
            allowed = false;
        }

        if (!allowed) {
            cancel(event);
            if (settings.isDebug()) {
                plugin.getLogger().info("[调试] 已拦截假人 " + bot.getName()
                        + " 的 " + action + " 动作（位于 " + describe(bot.getLocation()) + "）");
            }
        }
    }

    /**
     * 按 bot 动作类型分派检查。
     * 返回 true 表示放行，false 表示本次动作应被取消。
     */
    private boolean check(String action, Player bot) {
        switch (action) {
            case "break":
                return !settings.isGuardBreak() || checkBreak(bot);

            // 右键方块：同时覆盖了开容器、按按钮、开门，以及贴方块放置
            case "use_on":
            case "use_on_offhand":
                return checkUseOnBlock(bot);

            // 使用手中物品：命中方块时按右键方块处理；对空气使用（吃东西、拉弓）无害
            case "use":
            case "use_auto":
            case "use_offhand":
                return checkUseItem(bot);

            // 对实体使用：喂食、剪羊毛、栓绳、交易等
            case "use_to":
            case "use_to_offhand":
                return checkUseOnEntity(bot);

            case "attack":
                return !settings.isGuardAttack() || checkAttack(bot);

            case "mount":
                return !settings.isGuardVehicle() || checkMount(bot);

            case "drop":
                return !settings.isGuardItemDrop() || checkDrop(bot);

            case "fish":
                return !settings.isGuardFish() || checkFish(bot);

            case "move":
            case "swim":
            case "jump":
                return !settings.isGuardMove() || checkMove(bot);

            default:
                // look / rotation / swap / sneak 等纯表现类动作不触及世界，直接放行
                return true;
        }
    }

    // ------------------------------------------------------------------
    //  破坏
    // ------------------------------------------------------------------

    private boolean checkBreak(Player bot) {
        Block target = targetBlock(bot);
        if (target == null || target.getType().isAir()) {
            return true;
        }
        return hook.dispatch(new BlockBreakEvent(target, bot));
    }

    /**
     * 诊断：把「假人破坏方块」这条判定链上的每一步都打出来。
     *
     * <p>用于区分两种失败：是 Residence 判定为无权限（授权问题），
     * 还是本插件在 Residence 放行后仍然拦下（自身问题）。
     */
    public List<String> diagnose(Player bot) {
        List<String> out = new ArrayList<>();
        out.add("  Residence 监听 BlockBreakEvent : " + hook.isWatching(BlockBreakEvent.class));
        out.add("  假人位置        : " + describe(bot.getLocation()));

        Block target = targetBlock(bot);
        if (target == null) {
            out.add("  目标方块        : 无（假人没有看向任何方块，破坏动作会被直接放行）");
            return out;
        }
        out.add("  目标方块        : " + target.getType() + " @ " + describe(target.getLocation()));

        out.addAll(flags.diagnose(bot, target.getLocation()));

        boolean allowed = hook.dispatch(new BlockBreakEvent(target, bot));
        out.add("  派发结果        : " + (allowed ? "放行" : "拦截"));
        out.add("  派发异常        : " + (hook.getLastFailure() == null ? "无" : hook.getLastFailure()));
        return out;
    }

    // ------------------------------------------------------------------
    //  右键方块（放置 / 交互 / 桶）
    // ------------------------------------------------------------------

    private boolean checkUseOnBlock(Player bot) {
        RayTraceResult trace = bot.rayTraceBlocks(settings.getReach(), FluidCollisionMode.NEVER);
        if (trace == null || trace.getHitBlock() == null) {
            return true;
        }

        Block clicked = trace.getHitBlock();
        BlockFace face = trace.getHitBlockFace();
        ItemStack hand = mainHand(bot);

        if (settings.isGuardInteract() && !checkInteract(bot, clicked, face, hand)) {
            return false;
        }
        if (settings.isGuardUseItem() && !checkBucket(bot, clicked, face, hand)) {
            return false;
        }
        if (settings.isGuardUseItem() && !checkIgnite(bot, clicked, face, hand)) {
            return false;
        }
        if (settings.isGuardPlace() && !checkPlace(bot, clicked, face, hand)) {
            return false;
        }
        return true;
    }

    /** 覆盖 use / container / button / door / lever / bed / brew / anvil 等全部交互类 flag。 */
    private boolean checkInteract(Player bot, Block clicked, BlockFace face, ItemStack hand) {
        PlayerInteractEvent event = new PlayerInteractEvent(
                bot, Action.RIGHT_CLICK_BLOCK, hand, clicked, face);
        return hook.dispatch(event);
    }

    /** 覆盖 place / build。 */
    private boolean checkPlace(Player bot, Block clicked, BlockFace face, ItemStack hand) {
        if (face == null || !isPlaceable(hand)) {
            return true;
        }
        Block destination = clicked.getRelative(face);
        // canBuild 传 true：这里问的是「Residence 允不允许」，不是「玩家原本有没有权限」
        BlockPlaceEvent event = new BlockPlaceEvent(
                destination, destination.getState(), clicked, hand, bot, true, EquipmentSlot.HAND);
        return hook.dispatch(event);
    }

    /** 覆盖桶相关的 flag（waterflow / lavaflow / use）。 */
    private boolean checkBucket(Player bot, Block clicked, BlockFace face, ItemStack hand) {
        if (face == null || hand == null) {
            return true;
        }
        Material type = hand.getType();
        if (type == Material.WATER_BUCKET || type == Material.LAVA_BUCKET
                || type == Material.POWDER_SNOW_BUCKET) {
            return hook.dispatch(new PlayerBucketEmptyEvent(bot, clicked, face, type, hand));
        }
        if (type == Material.BUCKET) {
            return hook.dispatch(new PlayerBucketFillEvent(bot, clicked, face, type, hand));
        }
        return true;
    }

    /**
     * 点火（打火石 / 火焰弹）。
     *
     * <p>点火在服务端走的是 {@code ItemStack#useOn}，同样属于被
     * {@code ServerBotGameMode#useItemOn} 绕过的事件路径，需要单独补一次
     * BlockIgniteEvent，否则 ignite flag 对假人不生效。
     */
    private boolean checkIgnite(Player bot, Block clicked, BlockFace face, ItemStack hand) {
        if (face == null || hand == null) {
            return true;
        }
        Material type = hand.getType();
        if (type != Material.FLINT_AND_STEEL && type != Material.FIRE_CHARGE) {
            return true;
        }
        Block destination = clicked.getRelative(face);
        BlockIgniteEvent event = new BlockIgniteEvent(
                destination, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, bot);
        return hook.dispatch(event);
    }

    // ------------------------------------------------------------------
    //  使用物品 / 对实体使用
    // ------------------------------------------------------------------

    private boolean checkUseItem(Player bot) {
        if (!settings.isGuardUseItem() && !settings.isGuardInteract() && !settings.isGuardPlace()) {
            return true;
        }
        // 对空气使用（进食、拉弓、投掷）不改变世界，无需检查
        RayTraceResult trace = bot.rayTraceBlocks(settings.getReach(), FluidCollisionMode.NEVER);
        if (trace == null || trace.getHitBlock() == null) {
            return true;
        }
        return checkUseOnBlock(bot);
    }

    /** 覆盖 animalfeeding / shear / leash / trade / nametag 等实体交互 flag。 */
    private boolean checkUseOnEntity(Player bot) {
        if (!settings.isGuardUseItem()) {
            return true;
        }
        Entity target = targetEntity(bot);
        if (target == null) {
            return true;
        }
        return hook.dispatch(new PlayerInteractEntityEvent(bot, target, EquipmentSlot.HAND));
    }

    // ------------------------------------------------------------------
    //  战斗
    // ------------------------------------------------------------------

    private boolean checkAttack(Player bot) {
        Entity victim = targetEntity(bot);
        if (victim == null) {
            return true;
        }

        // 战斗类 flag 分散在两个事件上：Residence 既监听 EntityDamageByEntityEvent
        // （pvp / friendlyfire / animalkilling / mobkilling），也监听 EntityDamageEvent
        // （damage / witherdamage 等）。两个都要喂给它。
        if (!hook.dispatch(new EntityDamageByEntityEvent(
                bot, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0D))) {
            return false;
        }
        return hook.dispatch(new EntityDamageEvent(
                victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0D));
    }

    // ------------------------------------------------------------------
    //  载具 / 物品 / 钓鱼 / 移动
    // ------------------------------------------------------------------

    /** 覆盖 riding / boarding / vehicledestroy。 */
    private boolean checkMount(Player bot) {
        Entity target = targetEntity(bot);
        if (!(target instanceof Vehicle vehicle)) {
            return true;
        }
        return hook.dispatch(new VehicleEnterEvent(vehicle, bot));
    }

    /**
     * 丢弃物品。
     * PlayerDropItemEvent 需要一个真实的物品实体作参数，凭空构造会污染世界，
     * 所以这里直接读 Residence 的 itemdrop flag。
     */
    private boolean checkDrop(Player bot) {
        return flags.has(bot, bot.getLocation(), "itemdrop", true);
    }

    /**
     * 钓鱼。
     * PlayerFishEvent 需要鱼钩实体，同样改为直接读 flag。
     */
    private boolean checkFish(Player bot) {
        return flags.has(bot, bot.getLocation(), "hook", true);
    }

    /** 覆盖 move / fly / nofly 等移动类 flag。 */
    private boolean checkMove(Player bot) {
        Location loc = bot.getLocation();
        return hook.dispatch(new PlayerMoveEvent(bot, loc, loc));
    }

    // ------------------------------------------------------------------
    //  工具方法
    // ------------------------------------------------------------------

    /** 取假人视线正对的方块，距离与 Leaves 的 break 动作保持一致。 */
    private Block targetBlock(Player bot) {
        return bot.getTargetBlockExact((int) Math.ceil(settings.getReach()), FluidCollisionMode.NEVER);
    }

    /** 取假人视线正对的实体，距离与 Leaves 的 attack / use_to 动作一致。 */
    private Entity targetEntity(Player bot) {
        RayTraceResult trace = bot.rayTraceEntities((int) Math.ceil(settings.getReach()));
        return trace == null ? null : trace.getHitEntity();
    }

    private ItemStack mainHand(Player bot) {
        return bot.getInventory().getItemInMainHand();
    }

    private boolean isPlaceable(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getType().isBlock();
    }

    private void cancel(Event event) {
        if (event instanceof Cancellable cancellable) {
            // setCancelled(true) 在 Leaves 里对应 SOFT_CANCEL：
            // 本次动作被跳过，但动作本身保留在队列里，与真实玩家「挖不动但能继续尝试」的表现一致
            cancellable.setCancelled(true);
        }
    }

    private String describe(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return "未知位置";
        }
        return loc.getWorld().getName() + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
