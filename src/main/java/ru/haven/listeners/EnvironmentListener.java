package ru.haven.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.haven.Haven;
import ru.haven.core.DataStore;
import ru.haven.util.BlockKey;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Отключение средового грифа: взрывы/огонь/лава не портят блоки; поршни не двигают чужие контейнеры. */
public class EnvironmentListener implements Listener {

    private final DataStore store;
    private final NamespacedKey ownerKey;

    public EnvironmentListener(Haven plugin, DataStore store) {
        this.store = store;
        this.ownerKey = new NamespacedKey(plugin, "owner");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { handleExplosion(e.blockList(), e.getEntityType().toString()); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { handleExplosion(e.blockList(), "BLOCK"); }

    private void handleExplosion(List<Block> blocks, String cause) {
        if (store.settings().blockExplosionDamage) {
            int n = blocks.size();
            blocks.clear(); // полностью без урона блокам (крипер «под сундук» бесполезен)
            if (n > 0) store.debug(() -> "EXPLOSION нейтрализован: спасено " + n + " блоков (" + cause + ")");
        } else {
            // Хотя бы защитить именованные блоки. Iterator-remove без promo BlockKey-аллокации к каждому блоку:
            // удаление происходит только если блок защищён.
            int before = blocks.size();
            Iterator<Block> it = blocks.iterator();
            while (it.hasNext()) {
                if (store.getOwner(BlockKey.of(it.next())) != null) it.remove();
            }
            int saved = before - blocks.size();
            if (saved > 0) store.debug(() -> "EXPLOSION: спасено " + saved + " защищённых блоков (" + cause + ")");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (store.settings().preventFireSpread && e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD) {
            e.setCancelled(true);
            store.debug(() -> "FIRE spread отменён at " + xyz(e.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (store.settings().preventFireSpread) {
            e.setCancelled(true);
            store.debug(() -> "FIRE burn отменён at " + xyz(e.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent e) {
        if (!store.settings().protectFromLiquids) return;
        Material t = e.getBlock().getType();
        if ((t == Material.LAVA || t == Material.WATER)
                && isOwned(e.getToBlock())) {
            e.setCancelled(true);
            store.debug(() -> t + " flow отменён к защищённому блоку at " + xyz(e.getToBlock()));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!store.settings().protectFromLiquids || !isLiquidBucket(e.getBucket())) return;
        Block target = e.getBlock();
        java.util.UUID owner = store.getOwner(BlockKey.of(target));
        if (owner != null && !store.canAccess(e.getPlayer(), owner)) {
            e.setCancelled(true);
            store.debug(() -> "BUCKET " + e.getBucket() + " denied: " + e.getPlayer().getName() + " -> " + xyz(target));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        ItemStack stack = e.getItem();
        if (stack == null || !isRiskyDispense(stack.getType())) return;

        Block dispenser = e.getBlock();
        UUID dispenserOwner = ownerOf(dispenser);
        Block target = dispenser.getRelative(dispenseFace(dispenser));
        int radius = dispenseScanRadius(stack.getType());

        UUID blockOwner = firstConflictingBlockOwner(target, dispenserOwner, radius);
        if (blockOwner != null) {
            e.setCancelled(true);
            store.debug(() -> "DISPENSE blocked: " + stack.getType() + " -> owned block near " + xyz(target));
            return;
        }

        UUID entityOwner = firstConflictingEntityOwner(target, dispenserOwner, radius);
        if (entityOwner != null) {
            e.setCancelled(true);
            store.debug(() -> "DISPENSE blocked: " + stack.getType() + " -> owned entity near " + xyz(target));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLiquidPhysics(BlockPhysicsEvent e) {
        if (!store.settings().protectFromLiquids) return;
        Block b = e.getBlock();
        if (!isOwned(b) || !isLiquidSensitive(b.getType()) || !liquidInvolved(e)) return;

        e.setCancelled(true);
        store.debug(() -> "LIQUID physics cancelled for protected " + b.getType() + " at " + xyz(b));
    }

    private static String xyz(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private boolean isOwned(Block b) {
        return store.getOwner(BlockKey.of(b)) != null;
    }

    private UUID ownerOf(Block b) {
        return store.getOwner(BlockKey.of(b));
    }

    private UUID ownerOf(Entity ent) {
        String s = ent.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    private boolean liquidInvolved(BlockPhysicsEvent e) {
        return isLiquid(e.getSourceBlock().getType())
                || isLiquid(e.getChangedType())
                || isLiquid(e.getChangedBlockData().getMaterial());
    }

    private boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.LAVA;
    }

    private boolean isLiquidBucket(Material m) {
        return m == Material.WATER_BUCKET || m == Material.LAVA_BUCKET;
    }

    private boolean isRiskyDispense(Material m) {
        return isBucketPlacement(m)
                || isIgniter(m)
                || isVehicleItem(m)
                || m == Material.TNT
                || m == Material.ARMOR_STAND;
    }

    private boolean isBucketPlacement(Material m) {
        return m.name().endsWith("_BUCKET")
                && m != Material.BUCKET
                && m != Material.MILK_BUCKET;
    }

    private boolean isIgniter(Material m) {
        return m == Material.FLINT_AND_STEEL || m == Material.FIRE_CHARGE;
    }

    private boolean isVehicleItem(Material m) {
        String name = m.name();
        return m == Material.MINECART
                || name.endsWith("_MINECART")
                || name.endsWith("_BOAT")
                || name.endsWith("_RAFT");
    }

    private int dispenseScanRadius(Material m) {
        if (isBucketPlacement(m) || m == Material.TNT || m == Material.TNT_MINECART) return 7;
        if (isIgniter(m)) return 2;
        return 1;
    }

    private BlockFace dispenseFace(Block dispenser) {
        BlockData data = dispenser.getBlockData();
        if (data instanceof Directional directional) return directional.getFacing();
        return BlockFace.SELF;
    }

    private UUID firstConflictingBlockOwner(Block target, UUID dispenserOwner, int radius) {
        if (!store.settings().protectBlocks && !store.settings().protectFromLiquids) return null;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    UUID owner = ownerOf(target.getRelative(x, y, z));
                    if (owner != null && !owner.equals(dispenserOwner)) return owner;
                }
            }
        }
        return null;
    }

    private UUID firstConflictingEntityOwner(Block target, UUID dispenserOwner, int radius) {
        if (!store.settings().protectEntities) return null;
        Location center = target.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : target.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            UUID owner = ownerOf(entity);
            if (owner != null && !owner.equals(dispenserOwner)) return owner;
        }
        return null;
    }

    private boolean isLiquidSensitive(Material m) {
        return Tag.CROPS.isTagged(m)
                || Tag.CARPETS.isTagged(m)
                || Tag.RAILS.isTagged(m)
                || Tag.SAPLINGS.isTagged(m)
                || Tag.FLOWERS.isTagged(m)
                || Tag.BUTTONS.isTagged(m)
                || Tag.PRESSURE_PLATES.isTagged(m)
                || Tag.CANDLES.isTagged(m)
                || Tag.ALL_SIGNS.isTagged(m)
                || m == Material.REDSTONE_WIRE
                || m == Material.TORCH
                || m == Material.WALL_TORCH
                || m == Material.SOUL_TORCH
                || m == Material.SOUL_WALL_TORCH
                || m == Material.REDSTONE_TORCH
                || m == Material.REDSTONE_WALL_TORCH
                || m == Material.COPPER_TORCH
                || m == Material.COPPER_WALL_TORCH;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!store.settings().preventPistonMove) return;
        for (Block b : e.getBlocks()) {
            // Любой tracked-блок с активной защитой нельзя двигать: ownership привязан к координате.
            if (isProtectedFromPistons(b)) {
                e.setCancelled(true);
                store.debug(() -> "PISTON отменён (двигает защищённый блок) at " + xyz(b));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!store.settings().preventPistonMove) return;
        for (Block b : e.getBlocks()) {
            // Любой tracked-блок с активной защитой нельзя двигать: ownership привязан к координате.
            if (isProtectedFromPistons(b)) {
                e.setCancelled(true);
                store.debug(() -> "PISTON отменён (двигает защищённый блок) at " + xyz(b));
                return;
            }
        }
    }

    private boolean isProtectedFromPistons(Block b) {
        return store.getOwner(BlockKey.of(b)) != null
                && (store.settings().protectBlocks || store.settings().isProtectable(b.getType()));
    }
}
