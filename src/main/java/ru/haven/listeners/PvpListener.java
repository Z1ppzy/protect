package ru.haven.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import ru.haven.core.DataStore;
import ru.haven.core.PvpInquiry;
import ru.haven.util.Notify;

/**
 * PvP: логирует убийства игроков (для аудита) и при настройке {@code pvp.enabled}
 * предлагает жертве оценить — был ли это согласованный бой или нападение.
 *
 * <p>Жалоба идёт через ту же систему {@link DataStore#addIncident}, что и {@code /report}, —
 * то есть рейтинг убийцы падает только если у жертвы достаточно playtime (credible reporter).</p>
 *
 * <p>Анти-спам: одна inquiry на пару (killer, victim) за {@code pvp.inquiry-cooldown-seconds};
 * если эти двое реально дерутся постоянно, не утопим жертву кнопками.</p>
 */
public class PvpListener implements Listener {

    private final DataStore store;

    public PvpListener(DataStore store) { this.store = store; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (!store.settings().pvpEnabled) return;
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return; // самоубийство / мобы / окружение — не наше дело

        var loc = victim.getLocation();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "?";

        // Аудит-лог в консоль (если включено) — независимо от inquiry.
        if (store.settings().pvpLogKills) {
            store.info("DEATH: " + victim.getName() + " убит " + killer.getName()
                    + " в " + worldName + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
        }

        // Persistent аудит-запись в pvp_kills — ВСЕГДА (даже для стаффа и при cooldown).
        // Это лог для /hv kills, он должен видеть всё что случилось.
        int killId = store.storage().logKill(
                killer.getUniqueId(), killer.getName(),
                victim.getUniqueId(), victim.getName(),
                System.currentTimeMillis(),
                worldName, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        // Стафф / bypass — дальше не пингуем жертву inquiry.
        if (store.isStaff(killer)) return;
        if (killer.hasPermission("haven.pvp.bypass")) return;

        // Анти-спам кулдаун между парой (killer → victim).
        if (!store.canPvpInquiry(killer.getUniqueId(), victim.getUniqueId())) {
            store.debug(() -> "PVP inquiry skipped (cooldown): " + killer.getName() + " → " + victim.getName());
            return;
        }

        PvpInquiry inq = new PvpInquiry(killer.getUniqueId(), killer.getName(),
                                        victim.getUniqueId(), victim.getName());
        inq.killRecordId = killId;
        store.pvpInquiries().register(inq);
        // Жертва уже мертва — сообщение придёт когда зареспаунится. Bukkit гарантирует доставку
        // sendMessage оффлайн/мёртвому игроку (буферится до respawn).
        Notify.pvpInquiry(store, victim, killer.getName(), inq.id);
        store.debug(() -> "PVP inquiry #" + inq.id + " (kill #" + killId + "): "
                + killer.getName() + " → " + victim.getName());
    }
}
