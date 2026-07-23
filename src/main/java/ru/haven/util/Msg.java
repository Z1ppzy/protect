package ru.haven.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * Текстовый хелпер. Два формата:
 * <ul>
 *   <li><b>legacy &amp;-коды</b> ({@link #color}/{@link #send}) — для админ/диагностики (их много, не трогаем);</li>
 *   <li><b>MiniMessage</b> ({@link #mm}/{@link #mini}) — для player-facing: градиенты
 *       {@code <gradient:#a:#b>…</gradient>}, hex {@code <#rrggbb>}, без «колхозных» скобок.</li>
 * </ul>
 * Paper нативно бандлит Adventure + MiniMessage, отдельная зависимость не нужна.
 */
public final class Msg {
    private Msg() {}

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // ---- legacy &-коды ----
    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    @SuppressWarnings("deprecation")
    public static void send(CommandSender to, String s) {
        if (s != null && !s.isEmpty()) to.sendMessage(color(s));
    }

    // ---- MiniMessage (градиенты/hex) ----
    /** Распарсить MiniMessage-строку в Component. Пустая/ null → пустой компонент. */
    public static Component mm(String s) {
        return (s == null || s.isEmpty()) ? Component.empty() : MM.deserialize(s);
    }

    /** Отправить MiniMessage-сообщение (Paper CommandSender — это Audience). */
    public static void mini(CommandSender to, String s) {
        if (s != null && !s.isEmpty()) to.sendMessage(MM.deserialize(s));
    }
}
