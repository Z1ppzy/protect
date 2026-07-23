package ru.haven.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ru.haven.core.DataStore;
import ru.haven.util.Msg;

/** Гейт опасных механик: новичкам (мало playtime) и игрокам с низким рейтингом нельзя ставить/использовать blacklist-предметы. */
public class PlaytimeGateListener implements Listener {

    private final DataStore store;

    public PlaytimeGateListener(DataStore store) { this.store = store; }

    private boolean active() {
        return store.settings().playtimeGateEnabled || store.settings().sanctionsEnabled;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!active()) return;
        if (!store.settings().gatedItems.contains(e.getBlockPlaced().getType())) return;
        deny(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent e) {
        if (!active() || e.getItem() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (e.getHand() != EquipmentSlot.HAND) return; // PIE стреляет дважды — обрабатываем только MAIN
        if (!store.settings().gatedItems.contains(e.getItem().getType())) return;
        deny(e.getPlayer(), e);
    }

    private void deny(Player p, Cancellable e) {
        int st = store.gateState(p.getUniqueId(), store.isStaff(p));
        if (st == 0) return; // доступ открыт
        e.setCancelled(true);
        if (st < 0) {
            Msg.send(p, store.settings().prefix + "&cОпасные механики ограничены из-за низкого рейтинга. Восстанови репутацию.");
        } else {
            Msg.send(p, store.settings().prefix + "&cОткроется после " + (store.settings().requiredGateMinutes / 60)
                    + " ч игры. Осталось: &f" + fmt(st) + "&c. &7(/access)");
        }
        store.debug(() -> "GATE denied: " + p.getName() + " state=" + st);
    }

    /** Минуты → «Xч Yм». */
    public static String fmt(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        return (h > 0 ? h + "ч " : "") + m + "м";
    }
}
