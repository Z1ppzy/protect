package ru.haven.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import ru.haven.core.DataStore;

/** Заморозка (изолятор): обездвиживание + запрет действий. Применяется вручную админом. */
public class FreezeListener implements Listener {

    private final DataStore store;

    public FreezeListener(DataStore store) { this.store = store; }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (store.frozenEmpty()) return;
        if (!store.isFrozen(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (to.getBlockX() != from.getBlockX() || to.getBlockY() != from.getBlockY() || to.getBlockZ() != from.getBlockZ()) {
            Location keep = from.clone();
            keep.setYaw(to.getYaw());
            keep.setPitch(to.getPitch());
            e.setTo(keep); // оставляем осмотреться, но не двигаться
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return; // PIE стреляет дважды
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && frozen(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && frozen(p)) e.setCancelled(true);
    }

    private boolean frozen(Player p) { return store.isFrozen(p.getUniqueId()); }
}
