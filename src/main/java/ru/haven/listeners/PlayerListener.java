package ru.haven.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import ru.haven.core.DataStore;
import ru.haven.util.Msg;

import java.util.List;

/** Регистрация игрока, резолв стаффа по UUID, оффлайн-сводка на входе, сохранение времени игры при выходе. */
public class PlayerListener implements Listener {

    private final Plugin plugin;
    private final DataStore store;

    public PlayerListener(Plugin plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // markLogin делает getOrCreate + обновляет last_login_ts + снимает decay-флаг (вернулся вовремя).
        store.markLogin(p.getUniqueId(), p.getName());
        store.resolveStaff(p);
        store.debug("JOIN " + p.getName() + " rep=" + store.reputation(p.getUniqueId())
                + " status=" + store.status(p.getUniqueId()));

        // Оффлайн-сводка «пока тебя не было…»: грузим/чистим async (БД), показываем на main через 2с
        // (даём чанку прогрузиться, чтобы сообщение не утонуло в спавн-спаме).
        if (store.settings().offlineSummaryEnabled) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                List<String> lines = store.buildAndClearSummary(p.getUniqueId());
                if (lines.isEmpty()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    Msg.send(p, store.settings().prefix + "&e⚠ Пока тебя не было, кто-то трогал твоё:");
                    for (String line : lines) Msg.send(p, store.settings().prefix + line);
                    Msg.send(p, store.settings().prefix + "&7Подробности и репорт — &f/rep&7, &f/report ник&7.");
                });
            }, 40L);
        }

        // Приветствие при ПЕРВОМ входе (Dialog-окно или чат — решает Haven.showWelcome по конфигу).
        // hasPlayedBefore() = false только в самый первый раз (Bukkit пишет playerdata при выходе).
        if (plugin instanceof ru.haven.Haven hv
                && store.settings().welcomeEnabled && store.settings().welcomeOnFirstJoin
                && !p.hasPlayedBefore()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) hv.showWelcome(p);
            }, 60L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Mark dirty + сразу скидываем в writer-очередь: если сервер крашнется (OOM, kill -9)
        // до следующего 5-минутного тика, playtime за сессию не теряется.
        // flushPlayersAsync — это enqueue O(N_dirty), не блокирует main.
        store.persistPlayer(e.getPlayer().getUniqueId());
        store.flushPlayersAsync();
    }
}
