package com.botmanager;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 问玩家一个数字，用<b>原版对话框</b>收。
 *
 * <h2>为什么不用聊天栏输入了</h2>
 *
 * <p>这原本是 {@code ChatPrompt}：让玩家回聊天栏敲一个数，插件监听 {@code AsyncChatEvent}
 * 把它吃掉。在测试服一直好使，直到 2026-09-15 用户报「目标服务器里只会直接输出内容」。
 *
 * <p>查下来是两层问题，第二层无解：
 *
 * <ol>
 *   <li><b>监听器压根不执行。</b>目标服装了 SimpleChat，它监听的是老的
 *       {@code AsyncPlayerChatEvent}；Paper 为兼容这类插件，会在派发
 *       {@code AsyncChatEvent} 之前先把它标成 cancelled。我们当时注册时带了
 *       {@code ignoreCancelled = true}，于是监听器<b>整个被跳过</b>。
 *       去掉那句话功能就恢复了——但这只是让"能接住"。</li>
 *   <li><b>公屏拦不住。</b>SimpleChat 的 {@code onPlayerChat} 字节码里
 *       <b>一次 {@code isCancelled()} 都没有</b>，它不看事件状态、无条件格式化并广播。
 *       补过 legacy 监听器抢在它前面取消（我们 LOWEST、它默认 NORMAL，确定先跑），
 *       实测公屏上照样出现 {@code [Lv.0] [world] <玩家> 1}。<b>事件层面无解。</b></li>
 * </ol>
 *
 * <p>所以换通道：走 Minecraft 1.21.6+ 的原生对话框（Paper 的
 * {@code io.papermc.paper.dialog}）。玩家点确定才把值交回来，
 * <b>全程不经过聊天事件，数字不可能进公屏</b>，顺带也不用再教玩家"去聊天栏敲数字"。
 *
 * <p>Leaves 1.21.8 确认带这套 API（{@code versions/1.21.8/leaves-1.21.8.jar} 里有
 * {@code PaperDialog.class} 和完整的原版 dialog 数据包）。
 *
 * <h2>几条规矩</h2>
 * <ul>
 *   <li><b>不持有任何状态。</b>一问就是一个 {@link Dialog} 对象，回调闭包在它上面。
 *       玩家下线、走开、把对话框晾着，都不需要清理——老的 {@code waiting} 表和
 *       30 秒超时因此整个删掉了。</li>
 *   <li><b>输错了原地重弹一次</b>，错误原因写在对话框正文里，不用退出去重新点菜单。</li>
 *   <li><b>谁都能取消</b>：Esc、右上角叉、取消按钮，都只是把对话框关掉，什么都不会发生。</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public final class DialogPrompt {

    /**
     * 文本框在响应里的键。
     *
     * <p>原版 dialog 的输入项就是这个形状（`key` 是普通字符串，不是 ResourceLocation），
     * {@code /dialog} 命令的内置对话框也是这么写的。
     */
    private static final String KEY = "interval";

    /** 文本框里预先填好的值。20 tick = 1 秒，也是 Leaves 自己的默认间隔。 */
    private static final String INITIAL = "20";

    private final BotManager plugin;

    public DialogPrompt(BotManager plugin) {
        this.plugin = plugin;
    }

    /**
     * 收到输入之后干什么。
     *
     * <p>实现里可以直接回消息给玩家——比如"假人已经不在线了"这种跟输入本身无关的失败，
     * 比塞回对话框里合适。
     */
    public interface Handler {
        /**
         * @return {@code null} = 这个输入收下了；非 null = 不合法的原因，
         *         会显示在对话框里并让他重填
         */
        String accept(Player player, String text);
    }

    /**
     * 弹一个对话框问一句。
     *
     * <p>同一个玩家重复调用就再弹一个——他刚点了别的按钮，新问题才是他关心的。
     */
    public void ask(Player player, String question, Handler handler) {
        if (!player.isOnline()) {
            return;
        }
        player.showDialog(build(question, null, handler));
    }

    /**
     * 拼一个对话框。
     *
     * @param error 上一次输入被拒的原因，画在正文下面一行；没有就传 null
     */
    private Dialog build(String question, String error, Handler handler) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(question)));
        if (error != null) {
            body.add(DialogBody.plainMessage(Component.text(error).color(NamedTextColor.RED)));
        }

        TextDialogInput input = DialogInput.text(KEY, Component.text("间隔（tick，20 tick = 1 秒）"))
                .initial(INITIAL)
                .maxLength(6)
                .width(200)
                .build();

        ActionButton confirm = ActionButton.create(
                Component.text("确定").color(NamedTextColor.GREEN),
                Component.text("按这个间隔起动作"),
                100,
                DialogAction.customClick(
                        (view, audience) -> submit(audience, view, question, handler),
                        callbackOptions()));

        ActionButton cancel = ActionButton.create(
                Component.text("取消").color(NamedTextColor.GRAY),
                Component.text("什么都不做"),
                100,
                DialogAction.customClick(
                        (view, audience) -> {
                            if (audience instanceof Player who) {
                                who.sendMessage(ChatColor.GRAY + "[BotManager] 已取消。");
                            }
                        },
                        callbackOptions()));

        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("设置间隔"))
                        .body(body)
                        .inputs(List.of(input))
                        .canCloseWithEscape(true)
                        // CLOSE：点了就关。输错了在下面重弹一个，比让它挂着转圈简单。
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(confirm, cancel)));
    }

    /**
     * 点了「确定」。
     *
     * <p>回调拿到的 {@link Audience} 就是点按钮的玩家，但要先确认一下再往下走——
     * 拿不到人就没法回消息、也没法起动作了。
     */
    private void submit(Audience audience, DialogResponseView view, String question, Handler handler) {
        if (!(audience instanceof Player player)) {
            return;
        }
        String text = view.getText(KEY);
        // 回主线程再动实体/背包/界面。自定义点击回调本来就在主线程上，
        // 但多绕这一跳代价只有一个 tick，换来的是不必赌它的线程模型。
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            String error = handler.accept(player, text == null ? "" : text.trim());
            if (error != null) {
                // 输错了：把同一问连错误原因一起重弹一次
                player.showDialog(build(question, error, handler));
            }
        });
    }

    /**
     * 回调的有效期。
     *
     * <p>{@code uses(1)}：一个对话框只点得动一次「确定」，每弹一次都是新拼的 Dialog，
     * 所以这里不需要留余量。{@code lifetime} 给足——玩家可能盯着这个框想半天。
     */
    private static ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(30))
                .build();
    }
}
