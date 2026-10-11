package com.botmanager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Vault 经济接口的软接入。
 *
 * <p>整个类都用反射，跟 {@link LeavesBridge}、{@link ResidenceFlags} 一个路子：
 * Vault 是可有可无的软依赖，服务器没装时插件照常跑，只是菜单里买不了号位。
 * 把反射全关在这一个类里，{@link BotMenu} 那边看到的就是三个普通方法，
 * 而且 {@code pom.xml} 不用为它多挂一个仓库和依赖。
 *
 * <p>"没接上经济"和"钱不够"是两回事，所以 {@link #isReady()} 单独暴露出来：
 * 前者是配置问题（该装 Vault），后者是玩家自己的事，提示语完全不同。
 */
public final class VaultEconomy {

    private Object provider;
    private String providerName;
    private Method withdraw;
    private Method deposit;
    private Method format;

    private VaultEconomy() {
    }

    /** 找一个可用的经济实现；找不到就返回一个 {@link #isReady()} 为 false 的空壳。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static VaultEconomy hook() {
        VaultEconomy result = new VaultEconomy();
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration((Class) economyClass);
            if (registration == null || registration.getProvider() == null) {
                return result;
            }
            result.provider = registration.getProvider();
            result.providerName = registration.getPlugin() == null
                    ? registration.getProvider().getClass().getSimpleName()
                    : registration.getPlugin().getName();
            result.withdraw = findTransaction(result.provider.getClass(), "withdrawPlayer");
            result.deposit = findTransaction(result.provider.getClass(), "depositPlayer");
            result.format = find(result.provider.getClass(), "format", double.class);
        } catch (Throwable t) {
            // 没装 Vault、或者装了但没有经济插件注册进来——都算正常情况，
            // 由 isReady() 报告给调用方，这里不吵日志。
            result.provider = null;
        }
        return result;
    }

    /** 经济接口是否可用。false 时 {@link #withdraw} 一定失败，调用方据此拒绝购买。 */
    public boolean isReady() {
        return provider != null && withdraw != null;
    }

    /**
     * 接上的经济插件名，没接上时返回 {@code null}。
     *
     * <p>服务器上装两个经济插件时，Vault 只会把优先级最高的那个交出来，
     * 而"到底扣的是谁的钱"从菜单上是看不出来的——买号位扣款少了一笔，
     * 玩家第一反应是插件算错了。把这个名字报出来，对账才有得对。
     */
    public String providerName() {
        return providerName;
    }

    /** 扣钱。没接上经济、或金额非正时返回 false。 */
    public boolean withdraw(OfflinePlayer player, double amount) {
        return transact(withdraw, player, amount);
    }

    /** 退钱。用于"钱扣了但号位没开成"的回滚。 */
    public boolean deposit(OfflinePlayer player, double amount) {
        return transact(deposit, player, amount);
    }

    /** 按经济插件自己的格式渲染金额；没接上时退回两位小数。 */
    public String format(double amount) {
        if (format != null && provider != null) {
            try {
                return String.valueOf(format.invoke(provider, amount));
            } catch (Throwable ignored) {
                // 落到下面的兜底格式
            }
        }
        return String.format("%.2f", amount);
    }

    private boolean transact(Method method, OfflinePlayer player, double amount) {
        if (method == null || provider == null || amount <= 0) {
            return false;
        }
        try {
            Object response = method.invoke(provider, player, amount);
            if (response == null) {
                return false;
            }
            // EconomyResponse#transactionSuccess()
            Object ok = response.getClass().getMethod("transactionSuccess").invoke(response);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按名字 + 参数个数找存取款方法，不锁死第一个形参的类型。
     *
     * <p>Vault 同时有 {@code withdrawPlayer(OfflinePlayer, double)} 和
     * 早已废弃的 {@code withdrawPlayer(String, double)}，两个重载名字一样。
     * 优先挑第一个形参能吃 {@link OfflinePlayer} 的那个——传进去的正是 OfflinePlayer，
     * 挑错了会在 invoke 时炸。
     */
    private static Method findTransaction(Class<?> type, String name) {
        Method loose = null;
        for (Method candidate : type.getMethods()) {
            if (!candidate.getName().equals(name) || candidate.getParameterCount() != 2) {
                continue;
            }
            if (candidate.getParameterTypes()[0].isAssignableFrom(OfflinePlayer.class)) {
                return candidate;
            }
            if (loose == null) {
                loose = candidate;
            }
        }
        return loose;
    }

    private static Method find(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }
}
