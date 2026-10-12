package com.botmanager;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 让假人不被 AuthMe 的「未登录保护」拦住——尤其是**能被攻击**。
 *
 * <p><b>问题</b>：AuthMe 把没执行过 {@code /login} 的玩家当「未登录」处理，
 * 处于受保护状态（伤害被取消、交互受限）。假人永远不会登录，所以在装了
 * AuthMe 的服上，玩家打不动自己的假人。
 *
 * <p><b>解法</b>：AuthMe 提供了一个免登录限制的白名单
 * {@code settings.unrestrictions.UnrestrictedName}（写在它自己的 config.yml 里），
 * 名字在名单里的人不受未登录保护。本类负责在假人上线时把它的全名写进去、
 * 移除时撤掉。
 *
 * <p><b>零代码依赖</b>：不引用 AuthMe 的任何类——只读写它的 config.yml，
 * 再用控制台命令 {@code authme reload} 让它重新读配置。名单文件路径和这个键
 * 是少数稳定契约，所以 AuthMe 换版本也不影响接入。
 * 整套做法照搬自独立插件 {@code BotUnrestrict-1.0.0}（作者 io.github.mcserver）。
 *
 * <p><b>与那个独立插件的唯一区别</b>是挂载点：它监听 {@code BotCreateEvent}，
 * 本插件改用 {@code BotJoinEvent}（由调用方 {@link BotManager} 注册）。
 * 原因是本插件的上下线同步会把假人 remove 之后再用
 * {@code loadNewManualSavedBot} 还原，而**还原不触发 BotCreateEvent**
 * ——挂错点的话，玩家「下线→上线」一轮之后假人就丢了白名单、又打不动了。
 * 而 {@code BotJoinEvent} 在 {@code BotList.placeNewBot} 里派发，
 * 召唤 / 存档还原 / 关服 resume 三条上线路径都会经过它。
 */
public final class AuthMeUnrestrict {

    /** AuthMe 配置里白名单的键路径。 */
    private static final String KEY = "settings.unrestrictions.UnrestrictedName";

    private final BotManager plugin;
    private final File authmeConfigFile;

    public AuthMeUnrestrict(BotManager plugin) {
        this.plugin = plugin;
        // plugins/BotManager/ 的上一级就是 plugins/，AuthMe 的配置在它下面
        this.authmeConfigFile = new File(plugin.getDataFolder().getParentFile(), "AuthMe/config.yml");
    }

    /**
     * @return 是否接上（AuthMe 的 config.yml 存在）。false 时本模块整体不生效，
     *         假人退回 AuthMe 的默认行为（受未登录保护）。
     */
    public boolean setup() {
        if (!authmeConfigFile.isFile()) {
            plugin.getLogger().info("未检测到 AuthMe（" + authmeConfigFile
                    + "），跳过假人 UnrestrictedName 同步。");
            return false;
        }
        return true;
    }

    /** 假人上线（召唤 / 存档还原 / resume 拉回）时调用：把它加进免限制名单。 */
    public void unrestrict(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (addUnrestrictedName(name)) {
            reloadAuthMe();
            plugin.getLogger().info("已把假人 " + name + " 加入 AuthMe UnrestrictedName。");
        }
    }

    /** 假人被移除（死亡 / 上下线同步 / 遣散 / 关服）时调用：从名单里撤掉。 */
    public void restrict(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (removeUnrestrictedName(name)) {
            reloadAuthMe();
            plugin.getLogger().info("已把假人 " + name + " 移出 AuthMe UnrestrictedName。");
        }
    }

    /** @return 是否真的改了文件（已存在则不重复写）。 */
    private boolean addUnrestrictedName(String name) {
        if (!authmeConfigFile.exists()) {
            return false;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(authmeConfigFile);
        List<String> names = config.getStringList(KEY);
        for (String one : names) {
            if (one.equalsIgnoreCase(name)) {
                return false;
            }
        }
        names.add(name);
        config.set(KEY, names);
        return saveConfig(config);
    }

    /** @return 是否真的改了文件（本来就不在则不动）。 */
    private boolean removeUnrestrictedName(String name) {
        if (!authmeConfigFile.exists()) {
            return false;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(authmeConfigFile);
        List<String> names = config.getStringList(KEY);
        List<String> kept = new ArrayList<>();
        boolean found = false;
        for (String one : names) {
            if (one.equalsIgnoreCase(name)) {
                found = true;
            } else {
                kept.add(one);
            }
        }
        if (!found) {
            return false;
        }
        config.set(KEY, kept);
        return saveConfig(config);
    }

    private boolean saveConfig(FileConfiguration config) {
        try {
            config.save(authmeConfigFile);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("写 AuthMe 配置失败：" + e.getMessage());
            return false;
        }
    }

    /** 让 AuthMe 重新读配置，名单立即生效。 */
    private void reloadAuthMe() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "authme reload");
    }
}
