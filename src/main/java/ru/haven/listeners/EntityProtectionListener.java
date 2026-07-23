package ru.haven.listeners;

import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.persistence.PersistentDataType;
import ru.haven.Haven;
import ru.haven.core.DataStore;
import ru.haven.util.Msg;
import ru.haven.util.Notify;

import java.util.UUID;

/**
 * Защита сущностей (рамки/картины/армор-стенды/лодки/вагонетки).
 * Владелец хранится в НАТИВНОМ PersistentDataContainer самой сущности — Paper сам сохраняет его с
 * сущностью и удаляет вместе с ней. Своя БД/карта/очистка не нужны.
 */
public class EntityProtectionListener implements Listener {

    private static final BlockFace[] LIQUID_SCAN = {
            BlockFace.SELF, BlockFace.DOWN, BlockFace.UP,
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final DataStore store;
    private final NamespacedKey ownerKey;

    public EntityProtectionListener(Haven plugin, DataStore store) {
        this.store = store;
        this.ownerKey = new NamespacedKey(plugin, "owner");
    }

    // ---- запись владельца при установке ----
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (!store.settings().protectEntities || e.getPlayer() == null) return;
        setOwner(e.getEntity(), e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (!store.settings().protectEntities || e.getPlayer() == null) return;
        Entity ent = e.getEntity();
        if (ent instanceof ArmorStand || ent instanceof Boat || ent instanceof Minecart) {
            setOwner(ent, e.getPlayer());
        }
    }

    // ---- защита от слома/удара ----
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!store.settings().protectEntities) return;
        Entity victim = e.getEntity();
        // Бейл-аут по типу до чтения PDC — на обычных мобах не платим за PDC-read.
        if (!(victim instanceof ItemFrame || victim instanceof ArmorStand)) return;
        guard(e, victim, resolve(e.getDamager()), "сломать");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent e) {
        if (!store.settings().protectEntities) return;
        guard(e, e.getEntity(), resolve(e.getRemover()), "сломать");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent e) {
        if (!store.settings().protectEntities) return;
        guard(e, e.getVehicle(), resolve(e.getAttacker()), "сломать");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProtectedEntityMove(EntityMoveEvent e) {
        if (!store.settings().protectEntities || !(e.getEntity() instanceof ArmorStand)) return;
        if (ownerOf(e.getEntity()) == null || !e.hasChangedPosition()) return;
        if (!liquidNear(e.getFrom()) && !liquidNear(e.getTo())) return;

        e.setCancelled(true);
        store.debug(() -> "ENTITY move blocked near liquid: " + e.getEntity().getType() + " owner=" + ownerOf(e.getEntity()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProtectedVehicleMove(VehicleMoveEvent e) {
        if (!store.settings().protectEntities) return;
        Entity vehicle = e.getVehicle();
        if (!(vehicle instanceof Boat || vehicle instanceof Minecart)) return;
        if (ownerOf(vehicle) == null || hasPlayerPassenger(vehicle)) return;
        if (!changedPosition(e.getFrom(), e.getTo())) return;
        if (!liquidNear(e.getFrom()) && !liquidNear(e.getTo())) return;

        vehicle.teleport(e.getFrom());
        store.debug(() -> "ENTITY vehicle moved back from liquid flow: " + vehicle.getType() + " owner=" + ownerOf(vehicle));
    }

    // ---- защита от изменения ----
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractFrame(PlayerInteractEntityEvent e) {
        if (!store.settings().protectEntities || !(e.getRightClicked() instanceof ItemFrame)) return;
        // Двойной триггер MAIN_HAND/OFF_HAND — обрабатываем только MAIN.
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        guard(e, e.getRightClicked(), e.getPlayer(), "трогать");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStandUse(PlayerArmorStandManipulateEvent e) {
        if (!store.settings().protectEntities) return;
        guard(e, e.getRightClicked(), e.getPlayer(), "трогать");
    }

    // ---- общий гард ----
    private void guard(org.bukkit.event.Cancellable event, Entity target, Player actor, String what) {
        UUID owner = ownerOf(target);
        if (owner == null) return;
        if (actor != null && store.canAccess(actor, owner)) return;
        event.setCancelled(true);
        if (actor != null) {
            Msg.send(actor, store.settings().prefix + "&cЭто чужое — " + (what.equals("сломать") ? "ломать" : "трогать") + " нельзя.");
            store.debug(() -> "ENTITY " + what + " denied: " + actor.getName() + " -> owner " + owner);
            Notify.owner(store, owner, actor, what);
        }
    }

    // ---- PDC helpers ----
    private void setOwner(Entity ent, Player p) {
        ent.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
        store.debug(() -> "ENTITY lock " + ent.getType() + " owner=" + p.getName());
    }

    private UUID ownerOf(Entity ent) {
        String s = ent.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    private boolean hasPlayerPassenger(Entity ent) {
        for (Entity passenger : ent.getPassengers()) {
            if (passenger instanceof Player) return true;
        }
        return false;
    }

    private boolean changedPosition(Location from, Location to) {
        return from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
    }

    private boolean liquidNear(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block center = loc.getBlock();
        for (BlockFace face : LIQUID_SCAN) {
            Material type = face == BlockFace.SELF ? center.getType() : center.getRelative(face).getType();
            if (type == Material.WATER || type == Material.LAVA) return true;
        }
        return false;
    }

    private Player resolve(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player sp) return sp;
        return null;
    }
}
