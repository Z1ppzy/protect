package ru.haven.listeners;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.haven.core.DataStore;

/**
 * Anti-AFK для playtime (L2 anti-report-spam): фиксирует осмысленную активность игрока,
 * чтобы автокликер / AFK-пул не накручивали наигрыш для прохода {@code playtime-gate} и
 * получения credible-веса жалобам.
 *
 * <p><b>Что считается активностью:</b>
 * <ul>
 *   <li>{@link PlayerMoveEvent} — только при смене БЛОКА (вращение камеры не в счёт);</li>
 *   <li>{@link BlockPlaceEvent} / {@link BlockBreakEvent} — стройка;</li>
 *   <li>{@link PlayerInteractEvent} — взаимодействие с миром (правый клик, сундуки и т.д.);</li>
 *   <li>{@link PlayerJoinEvent} — грейс-период при входе (иначе первый
 *       {@link DataStore#tickPlaytime} сразу пометит игрока AFK).</li>
 * </ul>
 *
 * <p>Все хэндлеры — {@code MONITOR + ignoreCancelled}: мы только наблюдаем, не блокируем.
 * Move-фильтр по {@code getBlockX/Y/Z} убирает спам от мышиных микро-движений.
 */
public class ActivityListener implements Listener {

    private final DataStore store;

    public ActivityListener(DataStore store) { this.store = store; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()) {
            store.markActive(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        store.markActive(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        store.markActive(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        store.markActive(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        // Грейс-период при входе: только зашёл — не должен сразу попасть в AFK.
        store.markActive(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // Чистим запись чтобы Map не рос бесконечно на долгоживущем сервере.
        store.clearActive(e.getPlayer().getUniqueId());
    }
}
