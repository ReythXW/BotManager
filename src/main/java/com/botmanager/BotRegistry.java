package com.botmanager;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * 假人归属与配额记录，落在 {@code plugins/BotManager/bots.yml}。
 *
 * <pre>
 * limits:                        # 每玩家上限，键为 UUID
 *   550e8400-e29b-41d4-a716-446655440000: 3
 * owned:                         # 该玩家名下已有的编号
 *   550e8400-e29b-41d4-a716-446655440000:
 *   - 1
 *   - 2
 * </pre>
 *
 * <p>键一律用玩家 UUID 而非名字：玩家改名后配额不该跟着归零。
 * （注意假人自己的 UUID 由名字推导，改名会让假人换 UUID——那是 Leaves 的机制，与本文件无关。）
 *
 * <p><b>编号的取值范围就是 {@code 1..上限}</b>，所以"编号合法"和"数量没超"是同一件事，
 * 不需要再单独数一遍。上限既是"最多能有几个假人"，也是"能用哪些编号"。
 */
public final class BotRegistry {

    private final BotManager plugin;
    private final File file;
    private final FileConfiguration data;

    public BotRegistry(BotManager plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bots.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    /** 新玩家的默认上限，取自 config.yml 的 {@code bot.default-limit}。 */
    public int getDefaultLimit() {
        return Math.max(0, plugin.getConfig().getInt("bot.default-limit", 0));
    }

    /** 该玩家的召唤上限。没有单独设置过就用默认值。 */
    public int getLimit(UUID player) {
        return Math.max(0, data.getInt("limits." + player, getDefaultLimit()));
    }

    public void setLimit(UUID player, int limit) {
        data.set("limits." + player, Math.max(0, limit));
        save();
    }

    /** 该玩家已召唤的假人编号，升序。 */
    public List<Integer> getOwned(UUID player) {
        List<Integer> raw = data.getIntegerList("owned." + player);
        if (raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<Integer> sorted = new ArrayList<>(raw);
        Collections.sort(sorted);
        return sorted;
    }

    public boolean owns(UUID player, int index) {
        return data.getIntegerList("owned." + player).contains(index);
    }

    public void addOwned(UUID player, int index) {
        List<Integer> owned = getOwned(player);
        if (!owned.contains(index)) {
            owned.add(index);
            data.set("owned." + player, owned);
            save();
        }
    }

    /** 编号的硬上限，防止管理员把上限设成天文数字后这里空转。 */
    public static final int MAX_INDEX = 99999;

    /**
     * 找一个可用的最小编号，范围 {@code 1..limit}；全被占满返回 -1。
     *
     * <p>「占用」有两种，都要算：
     * <ul>
     *   <li>已经在 {@code owned} 里——包括"登记了但暂时不在线"的编号。这一条防的是
     *       玩家刚登录、{@link BotService#restoreAll} 还没跑完的那个窗口期：
     *       只看在线状态的话，本该被还原的编号会被当成空位，新建的假人把要还原的顶掉。</li>
     *   <li>{@code inUse} 判为真的——在线假人。这一条兜住管理员手工
     *       {@code /bot create} 出来的同名假人。</li>
     * </ul>
     *
     * @param inUse 判定某个编号当前是否已有假人在线
     */
    public int findFreeIndex(UUID player, int limit, IntPredicate inUse) {
        List<Integer> owned = getOwned(player);
        int ceiling = Math.min(limit, MAX_INDEX);
        for (int index = 1; index <= ceiling; index++) {
            if (!owned.contains(index) && !inUse.test(index)) {
                return index;
            }
        }
        return -1;
    }

    /** 该编号是否在上限允许的范围内（{@code 1..limit}）。 */
    public boolean isIndexAllowed(UUID player, int index) {
        return index >= 1 && index <= Math.min(getLimit(player), MAX_INDEX);
    }

    public void removeOwned(UUID player, int index) {
        List<Integer> owned = getOwned(player);
        if (owned.remove(Integer.valueOf(index))) {
            if (owned.isEmpty()) {
                // 清干净，别在文件里留一堆空列表
                data.set("owned." + player, null);
            } else {
                data.set("owned." + player, owned);
            }
            save();
        }
    }

    private void save() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("无法创建插件数据目录，bots.yml 未能保存。");
                return;
            }
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("保存 bots.yml 失败：" + e);
        }
    }
}
