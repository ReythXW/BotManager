package com.botmanager;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * 菜单的身份标记。
 *
 * <p>{@link Inventory#getHolder()} 返回本对象，用它判断"这是不是本插件的菜单"。
 * <b>别拿标题字符串来判断</b>：标题带颜色码、会随文案改，玩家还能用铁砧把箱子改成同名来伪造一个。
 * holder 是打开时由代码塞进去的对象引用，外部伪造不出来。
 */
public final class BotMenuHolder implements InventoryHolder {

    public enum Kind {
        /** 主菜单：按顺序排开的号位格 + 末尾一个购买按钮。 */
        MAIN,
        /** 已绑定假人的完整操作面板。 */
        BOT,
        /** 还没绑定假人的「可用假人」小面板：只有生成和返回两个按钮。 */
        UNBOUND,
        /** 移除假人前的二次确认。 */
        CONFIRM_REMOVE
    }

    private final Kind kind;
    /** 菜单归属的玩家。每次点击都要拿它复核——菜单里摆着的物品不可信。 */
    private final UUID owner;
    /** {@link Kind#BOT} 面板对应的号位；{@link Kind#MAIN} 恒为 0。 */
    private final int index;

    private Inventory inventory;

    BotMenuHolder(Kind kind, UUID owner, int index) {
        this.kind = kind;
        this.owner = owner;
        this.index = index;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    public Kind kind() {
        return kind;
    }

    public UUID owner() {
        return owner;
    }

    public int index() {
        return index;
    }
}
