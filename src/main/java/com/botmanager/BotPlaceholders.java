package com.botmanager;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI 占位符：把玩家的假人配额与占用情况暴露给其它插件
 * （TAB、计分板、聊天格式等）。
 *
 * <p>三个占位符（identifier 为 {@code botmanager}）：
 * <ul>
 *   <li>{@code %botmanager_limit%} —— 假人上限，也就是买下的号位总数</li>
 *   <li>{@code %botmanager_online%} —— 当前在线的假人数量</li>
 *   <li>{@code %botmanager_used%}（别名 {@code %botmanager_owned%}）——
 *       已占用的号位数量。比 {@code online} 多出来的部分，是「收回」
 *       （{@code /bm recall}）之后仍在名下的假人：它们已下线，但号位保留、
 *       随时可以再拉起来</li>
 *   <li>{@code %botmanager_price%}（别名 {@code %botmanager_next_price%}）——
 *       买下一个号位的价格（纯数字，不带货币符号）。已买到
 *       {@code menu.max-slots} 时不可购买，返回<b>空字符串</b></li>
 * </ul>
 *
 * <p>数据口径与插件本体一致：上限和占用读 {@link BotRegistry}（bots.yml），
 * 在线数用 {@link LeavesBridge#botsOf}（与归属判定同一个过滤口径）。
 *
 * <p>只在服上有 PlaceholderAPI 时才会被注册（{@code softdepend} 保证它先启用），
 * 没装时本类根本不会被加载。
 */
public final class BotPlaceholders extends PlaceholderExpansion {

    private final BotManager plugin;
    private final BotRegistry registry;
    private final LeavesBridge leaves;
    private final BotMenu menu;

    public BotPlaceholders(BotManager plugin, BotRegistry registry, LeavesBridge leaves, BotMenu menu) {
        this.plugin = plugin;
        this.registry = registry;
        this.leaves = leaves;
        this.menu = menu;
    }

    @Override
    public String getIdentifier() {
        return "botmanager";
    }

    @Override
    public String getAuthor() {
        return "ReythXW";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** 别在 PlaceholderAPI 自己重载时被顺手卸载——本插件只注册这一次。 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return null; // 三个都是玩家级占位符，无玩家上下文时不适用
        }
        String name = player.getName();
        switch (params.toLowerCase()) {
            case "limit":
                return String.valueOf(registry.getLimit(player.getUniqueId()));
            case "online":
                // botsOf 收玩家名；离线档案拿不到名字时按 0 算（在线玩家不会走到这）
                return name == null ? "0" : String.valueOf(leaves.botsOf(name).size());
            case "used":
            case "owned":
                return String.valueOf(registry.getOwned(player.getUniqueId()).size());
            case "price":
            case "next_price": {
                double price = menu.nextSlotPrice(player.getUniqueId());
                // -1 = 已到 menu.max-slots、买不了；返回空串让使用方自行判断
                return price < 0 ? "" : formatAmount(price);
            }
            default:
                return null;
        }
    }

    /** 纯数字金额：整数不带小数点（{@code 50000}），有小数则原样保留（{@code 1234.5}）。 */
    private static String formatAmount(double value) {
        if (value == Math.floor(value) && Math.abs(value) < 1.0E15D) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
