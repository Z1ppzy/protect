package ru.haven.listeners;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ru.haven.core.DataStore;
import ru.haven.util.BlockKey;
import ru.haven.util.Msg;
import ru.haven.util.Notify;

import java.util.UUID;

/** Владение и защита ВСЕХ поставленных блоков (постройки), плюс контейнеры. Природные блоки — без владельца. */
public class BlockProtectionListener implements Listener {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final DataStore store;

    public BlockProtectionListener(DataStore store) { this.store = store; }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e instanceof BlockMultiPlaceEvent) return; // мульти-блоки (кровать/дверь) — отдельно
        Block b = e.getBlockPlaced();
        Material m = b.getType();
        boolean container = store.settings().isProtectable(m);
        if (!container && !store.settings().protectBlocks) return; // защита построек выключена

        Player p = e.getPlayer();

        // Анти-эксплойт слияния сундука с чужим (доступ к чужому луту через двойной сундук).
        if (m == Material.CHEST || m == Material.TRAPPED_CHEST) {
            for (BlockFace f : SIDES) {
                Block n = b.getRelative(f);
                if (n.getType() == m) {
                    UUID no = store.getOwner(BlockKey.of(n));
                    if (no != null && !no.equals(p.getUniqueId()) && !store.canAccess(p, no)) {
                        e.setCancelled(true);
                        Msg.send(p, store.settings().prefix + "&cЗдесь нельзя поставить сундук — рядом чужой.");
                        store.debug(() -> "PLACE denied (примыкает к чужому сундуку): " + p.getName() + " at " + loc(b));
                        return;
                    }
                }
            }
        }

        store.debug(() -> "PLACE allowed " + m + " at " + loc(b) + " owner=" + p.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void lockPlaced(BlockPlaceEvent e) {
        if (e instanceof BlockMultiPlaceEvent) return;
        Block b = e.getBlockPlaced();
        if (!shouldTrack(b.getType())) return;

        store.setOwner(BlockKey.of(b), e.getPlayer().getUniqueId());
        store.debug(() -> "LOCK " + b.getType() + " at " + loc(b) + " owner=" + e.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void lockMultiPlaced(BlockMultiPlaceEvent e) {
        if (!store.settings().protectBlocks && !anyContainer(e)) return;
        Player p = e.getPlayer();
        for (BlockState st : e.getReplacedBlockStates()) {
            Block b = st.getBlock();
            if (shouldTrack(b.getType())) {
                store.setOwner(BlockKey.of(b), p.getUniqueId());
            }
        }
        store.debug(() -> "LOCK multi (" + e.getReplacedBlockStates().size() + ") owner=" + p.getName());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        UUID owner = firstOwner(b);
        if (owner == null) return; // природный/неотслеживаемый блок — копай свободно
        boolean container = store.settings().isProtectable(b.getType());
        if (!container && !store.settings().protectBlocks) return; // защита построек выключена

        Player p = e.getPlayer();
        UUID blockingOwner = blockingOwner(b, p);
        if (blockingOwner == null) {
            store.debug(() -> "BREAK allowed " + b.getType() + " at " + loc(b) + " (" + p.getName() + ")");
            return;
        }
        e.setCancelled(true);
        Msg.send(p, store.settings().prefix + "&cЭто чужой блок — ломать нельзя.");
        store.debug(() -> "BREAK denied: " + p.getName() + " -> owner " + blockingOwner + " at " + loc(b));
        Notify.owner(store, blockingOwner, p, "сломать");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unlockBroken(BlockBreakEvent e) {
        Block b = e.getBlock();
        UUID owner = firstOwner(b);
        if (owner == null || !shouldTrack(b.getType())) return;

        removeOwner(b);
        Block linked = linkedBlock(b);
        if (linked != null) removeOwner(linked);
        store.debug(() -> "UNLOCK " + b.getType() + " at " + loc(b) + " (broke " + e.getPlayer().getName() + ")");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerPhysical(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.FARMLAND) return;
        UUID owner = store.getOwner(BlockKey.of(b));
        if (owner == null || store.canAccess(e.getPlayer(), owner)) return;

        e.setCancelled(true);
        store.debug(() -> "FARMLAND trample denied: " + e.getPlayer().getName() + " -> owner " + owner + " at " + loc(b));
        Notify.owner(store, owner, e.getPlayer(), "сломать");
    }

    /**
     * Запрет открытия чужих дверей/люков/калиток. Дверь — 2 блока: клик по любой половине
     * толкает всю дверь, поэтому проверяем ОБЕ половины (через {@link #blockingOwner}), иначе
     * игрок открывал бы чужую дверь, кликнув по неотслеженной половине.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpenInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return; // PIE стреляет дважды (main+off hand)
        if (!store.settings().protectBlocks) return; // защита построек выключена
        Block b = e.getClickedBlock();
        if (b == null || !(b.getBlockData() instanceof Openable)) return; // только двери/люки/калитки

        Player p = e.getPlayer();
        UUID blockingOwner = blockingOwner(b, p);
        if (blockingOwner == null) return;
        e.setCancelled(true);
        Msg.send(p, store.settings().prefix + "&cЭто чужой блок — открывать нельзя.");
        store.debug(() -> "OPEN(door) denied: " + p.getName() + " -> owner " + blockingOwner + " at " + loc(b));
        Notify.owner(store, blockingOwner, p, "открыть");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPhysical(EntityInteractEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.FARMLAND) return;
        UUID owner = store.getOwner(BlockKey.of(b));
        if (owner == null) return;
        if (e.getEntity() instanceof Player p && store.canAccess(p, owner)) return;

        e.setCancelled(true);
        store.debug(() -> "FARMLAND entity trample denied: " + e.getEntityType() + " at " + loc(b));
    }

    /** Мобы (эндермен и пр.) не трогают чужие блоки. Падающие owned-блоки не даём превратить в stale AIR-owner. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        if (!store.settings().protectBlocks) return;
        Block b = e.getBlock();
        if (firstOwner(b) == null) return;
        if (isPhysicsSensitive(b)) {
            e.setCancelled(true);
            store.debug(() -> "PHYSICS blocked: owned " + b.getType() + " at " + loc(b));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!store.settings().protectBlocks) return;
        if (e.getEntity() instanceof FallingBlock fb) {
            if (fallingBlockTouchesOwnedBlock(e, fb)) {
                e.setCancelled(true);
                fb.setDropItem(false);
                fb.remove();
                store.debug(() -> "FALLING_BLOCK cancelled: " + fb.getMaterial() + " at " + loc(e.getBlock()));
            }
            return;
        }
        if (store.getOwner(BlockKey.of(e.getBlock())) != null) {
            e.setCancelled(true);
            store.debug(() -> "MOB-grief отменён: " + e.getEntityType() + " -> " + loc(e.getBlock()));
        }
    }

    private boolean fallingBlockTouchesOwnedBlock(EntityChangeBlockEvent e, FallingBlock fb) {
        if (store.getOwner(BlockKey.of(e.getBlock())) != null) return true;
        org.bukkit.Location source = fb.getSourceLoc();
        return source != null && source.getWorld() != null && store.getOwner(BlockKey.of(source)) != null;
    }

    private boolean shouldTrack(Material m) {
        return store.settings().protectBlocks || store.settings().isProtectable(m);
    }

    private UUID firstOwner(Block b) {
        UUID owner = store.getOwner(BlockKey.of(b));
        if (owner != null) return owner;
        Block linked = linkedBlock(b);
        return linked != null ? store.getOwner(BlockKey.of(linked)) : null;
    }

    private UUID blockingOwner(Block b, Player p) {
        UUID owner = store.getOwner(BlockKey.of(b));
        if (owner != null && !store.canAccess(p, owner)) return owner;
        Block linked = linkedBlock(b);
        if (linked == null) return null;
        UUID linkedOwner = store.getOwner(BlockKey.of(linked));
        return linkedOwner != null && !store.canAccess(p, linkedOwner) ? linkedOwner : null;
    }

    private void removeOwner(Block b) {
        store.removeOwner(BlockKey.of(b));
    }

    private Block linkedBlock(Block b) {
        BlockData data = b.getBlockData();
        if (data instanceof Bed bed) {
            BlockFace face = bed.getPart() == Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace();
            return b.getRelative(face);
        }
        if (data instanceof Bisected bisected) {
            return b.getRelative(bisected.getHalf() == Bisected.Half.TOP ? BlockFace.DOWN : BlockFace.UP);
        }
        return null;
    }

    private boolean isPhysicsSensitive(Block b) {
        if (b.getType().hasGravity() || linkedBlock(b) != null) return true;
        Material m = b.getType();
        return Tag.CROPS.isTagged(m)
                || Tag.CARPETS.isTagged(m)
                || Tag.RAILS.isTagged(m)
                || Tag.SAPLINGS.isTagged(m)
                || Tag.FLOWERS.isTagged(m)
                || Tag.BUTTONS.isTagged(m)
                || Tag.PRESSURE_PLATES.isTagged(m)
                || Tag.CANDLES.isTagged(m)
                || Tag.ALL_SIGNS.isTagged(m)
                || isBanner(m);
    }

    private boolean isBanner(Material m) {
        return m.name().endsWith("_BANNER");
    }

    private boolean anyContainer(BlockMultiPlaceEvent e) {
        for (BlockState st : e.getReplacedBlockStates()) {
            if (store.settings().isProtectable(st.getType())) return true;
        }
        return false;
    }

    private static String loc(Block b) {
        return b.getWorld().getName() + " " + b.getX() + "," + b.getY() + "," + b.getZ();
    }
}
