package ru.haven.listeners;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import ru.haven.Haven;
import ru.haven.core.DataStore;
import ru.haven.util.BlockKey;
import ru.haven.util.Msg;
import ru.haven.util.Notify;

import java.util.UUID;

/** Контейнер-специфика: запрет открытия чужого и откачки воронкой/вагонеткой. (Установка/слом — в BlockProtectionListener.) */
public class ContainerListener implements Listener {

    private final DataStore store;
    private final NamespacedKey ownerKey;

    public ContainerListener(Haven plugin, DataStore store) {
        this.store = store;
        this.ownerKey = new NamespacedKey(plugin, "owner");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        UUID owner = blockingOwner(e.getInventory(), p);
        if (owner != null) {
            e.setCancelled(true);
            Msg.send(p, store.settings().prefix + "&cЭто чужой контейнер — доступ закрыт.");
            store.debug(() -> "OPEN denied: " + p.getName() + " -> owner " + owner);
            Notify.owner(store, owner, p, "открыть");
        }
    }

    /**
     * Запрет откачки воронкой / вагонеткой с воронкой из чужого контейнера.
     * Hot event: до 1000+ срабатываний в тик. Bail-out как можно раньше, без аллокаций.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent e) {
        UUID src = ownerOfInventory(e.getSource());
        if (src == null) return; // источник не защищён — не наше дело
        UUID dst = ownerOfInventory(e.getDestination());
        if (dst != null && (dst.equals(src) || store.trustContains(src, dst))) return;
        e.setCancelled(true);
        store.debug(() -> "HOPPER blocked: откачка из контейнера owner=" + src);
    }

    // ---- helpers ----
    private UUID ownerOfInventory(Inventory inv) {
        InventoryHolder h = inv.getHolder();
        if (h instanceof DoubleChest dc) {
            UUID o = ownerOfHolder(dc.getLeftSide());
            return o != null ? o : ownerOfHolder(dc.getRightSide());
        }
        return ownerOfHolder(h);
    }

    private UUID ownerOfHolder(InventoryHolder h) {
        if (h instanceof Container c) return store.getOwner(BlockKey.of(c.getBlock()));
        if (h instanceof Entity ent) return ownerOfEntity(ent);
        return null;
    }

    private UUID ownerOfEntity(Entity ent) {
        String s = ent.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    /** Возвращает владельца, который БЛОКИРУЕТ доступ игрока (или null, если можно). */
    private UUID blockingOwner(Inventory inv, Player p) {
        InventoryHolder h = inv.getHolder();
        if (h instanceof DoubleChest dc) {
            UUID o1 = ownerOfHolder(dc.getLeftSide());
            if (o1 != null && !store.canAccess(p, o1)) return o1;
            UUID o2 = ownerOfHolder(dc.getRightSide());
            if (o2 != null && !store.canAccess(p, o2)) return o2;
            return null;
        }
        UUID o = ownerOfHolder(h);
        return (o != null && !store.canAccess(p, o)) ? o : null;
    }
}
