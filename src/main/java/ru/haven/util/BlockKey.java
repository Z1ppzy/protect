package ru.haven.util;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.UUID;

/** Лёгкий иммутабельный ключ блока для in-memory карт владельцев. */
public record BlockKey(UUID world, int x, int y, int z) {

    public static BlockKey of(Block b) {
        return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
    }

    public static BlockKey of(Location l) {
        return new BlockKey(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
