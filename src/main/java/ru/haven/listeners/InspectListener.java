package ru.haven.listeners;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ru.haven.core.DataStore;
import ru.haven.util.BlockKey;
import ru.haven.util.Msg;

import java.util.UUID;

/** Режим инспектора (/hv inspect): клик по блоку показывает владельца, само действие отменяется. */
public class InspectListener implements Listener {

    private final DataStore store;

    public InspectListener(DataStore store) { this.store = store; }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!store.isInspecting(p.getUniqueId())) return;
        if (e.getHand() != EquipmentSlot.HAND) return; // PIE стреляет дважды
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;

        e.setCancelled(true);
        UUID owner = store.getOwner(BlockKey.of(b));
        String where = b.getType() + " " + b.getX() + "," + b.getY() + "," + b.getZ();
        if (owner == null) {
            Msg.send(p, store.settings().prefix + "&7" + where + ": &fничей &8(природный/не отслеживается)");
        } else {
            String n = store.nameOf(owner);
            Msg.send(p, store.settings().prefix + "&7" + where + ": &f"
                    + (n != null ? n : owner.toString().substring(0, 8)) + " " + store.tag(owner));
        }
    }
}
