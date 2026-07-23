package ru.haven.util;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Нативное <b>Dialog</b>-окно Paper (1.21.6+) с гайдом по фишкам Haven.
 *
 * <p>Содержимое берётся из {@code messages_<lang>.yml} (ключи {@code welcome-dialog.*}), так что
 * текст редактируется без кода и переводится. Любая несовместимость Dialog API (другое ядро,
 * экспериментальный статус) деградирует в {@link #fallbackChat} — гайд в чат, без ошибок.
 */
public final class WelcomeDialog {
    private WelcomeDialog() {}

    /**
     * Показать Dialog-окно игроку. При сбое Dialog API — чат-гайд (fallback).
     * @return {@code true} если показано нативное окно, {@code false} если ушёл fallback.
     */
    public static boolean show(Player p, Messages msg) {
        try {
            Component title = Msg.mm(msg.format("welcome-dialog.title"));

            List<DialogBody> body = new ArrayList<>();
            for (String line : msg.list("welcome-dialog.body")) {
                body.add(DialogBody.plainMessage(Msg.mm(line)));
            }

            // Кнопка «Команды в чат»: notice-кнопка ЗАКРЫВАЕТ окно и шлёт чат-гайд с кликабельными
            // командами (отдельная под-команда, чтобы не открыть Dialog заново).
            ActionButton commands = ActionButton.builder(Msg.mm(msg.format("welcome-dialog.button-commands")))
                    .action(DialogAction.staticAction(ClickEvent.runCommand("/haven help chat")))
                    .build();

            DialogBase base = DialogBase.builder(title)
                    .body(body)
                    .canCloseWithEscape(true) // Escape тоже закрывает окно
                    .build();

            Dialog dialog = Dialog.create(b -> b.empty()
                    .base(base)
                    .type(DialogType.notice(commands)));

            p.showDialog(dialog);
            return true;
        } catch (Throwable t) {
            fallbackChat(p, msg);
            return false;
        }
    }

    /** Текстовый гайд в чат — fallback, когда Dialog недоступен или выключен в конфиге. */
    public static void fallbackChat(Player p, Messages msg) {
        msg.sendList(p, "welcome");
    }
}
