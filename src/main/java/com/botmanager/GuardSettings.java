package com.botmanager;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置的运行时快照。{@code /bm reload} 时整体重读。
 */
public final class GuardSettings {

    private boolean debug;
    private boolean enabled;
    private boolean informBot;
    private double reach;

    private boolean guardBreak;
    private boolean guardInteract;
    private boolean guardPlace;
    private boolean guardUseItem;
    private boolean guardAttack;
    private boolean guardVehicle;
    private boolean guardItemDrop;
    private boolean guardFish;
    private boolean guardMove;

    public GuardSettings(BotManager plugin) {
        reload(plugin.getConfig());
    }

    public void reload(FileConfiguration cfg) {
        this.debug = cfg.getBoolean("debug", false);
        this.enabled = cfg.getBoolean("enabled", true);
        this.informBot = cfg.getBoolean("inform-bot", false);
        this.reach = Math.max(1.0D, cfg.getDouble("reach", 5.0D));

        this.guardBreak = cfg.getBoolean("guard.break", true);
        this.guardInteract = cfg.getBoolean("guard.interact", true);
        this.guardPlace = cfg.getBoolean("guard.place", true);
        this.guardUseItem = cfg.getBoolean("guard.use-item", true);
        this.guardAttack = cfg.getBoolean("guard.attack", true);
        this.guardVehicle = cfg.getBoolean("guard.vehicle", true);
        this.guardItemDrop = cfg.getBoolean("guard.item-drop", true);
        this.guardFish = cfg.getBoolean("guard.fish", true);
        this.guardMove = cfg.getBoolean("guard.move", false);
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInformBot() {
        return informBot;
    }

    public double getReach() {
        return reach;
    }

    public boolean isGuardBreak() {
        return guardBreak;
    }

    public boolean isGuardInteract() {
        return guardInteract;
    }

    public boolean isGuardPlace() {
        return guardPlace;
    }

    public boolean isGuardUseItem() {
        return guardUseItem;
    }

    public boolean isGuardAttack() {
        return guardAttack;
    }

    public boolean isGuardVehicle() {
        return guardVehicle;
    }

    public boolean isGuardItemDrop() {
        return guardItemDrop;
    }

    public boolean isGuardFish() {
        return guardFish;
    }

    public boolean isGuardMove() {
        return guardMove;
    }
}
