package ru.haven.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.haven.core.DataStore;

import java.util.UUID;

/** Интерактивные уведомления: владельцу про вторжение, жертве про PvP-смерть. */
public final class Notify {
    private Notify() {}

    /**
     * Жертве после PvP-смерти: «тебя убил X — это согласованный бой или нападение?»
     * Кнопки кликабельно вызывают {@link #pvpAcceptCommand(int)} / {@link #pvpComplainCommand(int)}.
     * Bukkit буферит sendMessage пока игрок мёртв — придёт сразу после respawn.
     */
    public static void pvpInquiry(DataStore store, Player victim, String killerName, int inquiryId) {
        Component msg = Component.text("⚔ ", NamedTextColor.RED)
                .append(Component.text("Тебя убил ", NamedTextColor.GRAY))
                .append(Component.text(killerName, NamedTextColor.YELLOW))
                .append(Component.text(". Это был согласованный бой?", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text(" [✓ Был бой]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand(pvpAcceptCommand(inquiryId)))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Договорились заранее, всё ок — рейтинг " + killerName + " не меняется",
                                NamedTextColor.GREEN))))
                .append(Component.text("    "))
                .append(Component.text("[✖ Нападение]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand(pvpComplainCommand(inquiryId)))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Создать жалобу на " + killerName + " (инцидент, рейтинг падает)",
                                NamedTextColor.RED))));
        victim.sendMessage(msg);
    }

    /** Полная команда для кнопки [Был бой]. Вынесено отдельно: тестируется без MockBukkit. */
    public static String pvpAcceptCommand(int inquiryId) {
        return String.format("/hpvp accept %d", inquiryId);
    }

    /** Полная команда для кнопки [Нападение]. Вынесено отдельно: тестируется без MockBukkit. */
    public static String pvpComplainCommand(int inquiryId) {
        return String.format("/hpvp complain %d", inquiryId);
    }

    public static void owner(DataStore store, UUID owner, Player actor, String what) {
        if (owner.equals(actor.getUniqueId())) return;
        Player o = Bukkit.getPlayer(owner);
        if (o == null || !o.isOnline()) {
            // Владелец оффлайн — копим для сводки «пока тебя не было…» (write-behind, дедуп).
            org.bukkit.Location l = actor.getLocation();
            store.recordIntrusion(owner, actor.getUniqueId(), what,
                    l.getWorld() != null ? l.getWorld().getName() : "?",
                    l.getBlockX(), l.getBlockY(), l.getBlockZ());
            return;
        }
        if (!store.canNotify(owner, actor.getUniqueId())) return;

        String name = actor.getName();
        Component msg = Component.text("⚠ ", NamedTextColor.GOLD)
                .append(Component.text(name, NamedTextColor.YELLOW))
                .append(Component.text(" пытается " + what + " ваше. Это свой?", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text(" [✔ Свой]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/trust " + name))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Доверять " + name + ": сможет строить/ломать/открывать ваше", NamedTextColor.GREEN))))
                .append(Component.text("    "))
                .append(Component.text("[✖ Гриф!]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/report " + name + " гриф чужих блоков"))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Пожаловаться на " + name + " (создаст инцидент)", NamedTextColor.RED))));
        o.sendMessage(msg);
        store.debug("NOTIFY владельцу " + o.getName() + ": " + name + " (" + what + ")");
    }
}
