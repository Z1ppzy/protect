package ru.haven;

import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Door;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import ru.haven.util.BlockKey;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Интеграционные тесты на моковом сервере (MockBukkit): плагин реально включается, листенеры работают. */
class HavenPluginTest {

    private ServerMock server;
    private Haven plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Haven.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginEnablesWithVersion() {
        assertTrue(plugin.isEnabled(), "плагин должен включиться без ошибок");
        // Версия читается из pom.xml через ${project.version} в plugin.yml — не хардкодим конкретное число.
        assertTrue(plugin.getDescription().getVersion().matches("\\d+\\.\\d+\\.\\d+"),
                "версия должна быть semver: " + plugin.getDescription().getVersion());
    }

    @Test
    void breakingOthersBlockIsCancelled() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        Block block = world.getBlockAt(10, 64, 10);
        block.setType(Material.OAK_PLANKS);
        plugin.store().setOwner(BlockKey.of(block), alice.getUniqueId());

        BlockBreakEvent byBob = new BlockBreakEvent(block, bob);
        server.getPluginManager().callEvent(byBob);
        assertTrue(byBob.isCancelled(), "чужой не должен ломать блок");

        BlockBreakEvent byAlice = new BlockBreakEvent(block, alice);
        server.getPluginManager().callEvent(byAlice);
        assertFalse(byAlice.isCancelled(), "владелец должен ломать свой блок");
        assertNull(plugin.store().getOwner(BlockKey.of(block)), "после слома владельцем запись снимается");
    }

    @Test
    void trustedPlayerCanBreak() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        Block block = world.getBlockAt(1, 64, 1);
        block.setType(Material.STONE_BRICKS);
        plugin.store().setOwner(BlockKey.of(block), alice.getUniqueId());
        plugin.store().addTrust(alice.getUniqueId(), bob.getUniqueId());

        BlockBreakEvent ev = new BlockBreakEvent(block, bob);
        server.getPluginManager().callEvent(ev);
        assertFalse(ev.isCancelled(), "доверенный должен ломать");
    }

    @Test
    void cancelledPlaceDoesNotCreateOwner() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGH)
            public void cancel(BlockPlaceEvent e) {
                e.setCancelled(true);
            }
        }, plugin);

        Block block = world.getBlockAt(2, 64, 2);
        block.setType(Material.OAK_PLANKS);
        BlockState replaced = block.getState();
        BlockPlaceEvent ev = new BlockPlaceEvent(block, replaced, block,
                new ItemStack(Material.OAK_PLANKS), alice, true, EquipmentSlot.HAND);

        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "late-cancel plugin cancelled the place");
        assertNull(plugin.store().getOwner(BlockKey.of(block)), "cancelled place must not leave an AIR-owner");
    }

    @Test
    void cancelledBreakKeepsOwner() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGH)
            public void cancel(BlockBreakEvent e) {
                e.setCancelled(true);
            }
        }, plugin);

        Block block = world.getBlockAt(3, 64, 3);
        block.setType(Material.STONE_BRICKS);
        plugin.store().setOwner(BlockKey.of(block), alice.getUniqueId());

        BlockBreakEvent ev = new BlockBreakEvent(block, alice);
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "late-cancel plugin cancelled the break");
        assertEquals(alice.getUniqueId(), plugin.store().getOwner(BlockKey.of(block)),
                "cancelled break must keep ownership");
    }

    @Test
    void ownedGravityBlockPhysicsIsCancelled() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block sand = world.getBlockAt(4, 70, 4);
        sand.setType(Material.SAND);
        plugin.store().setOwner(BlockKey.of(sand), alice.getUniqueId());

        BlockPhysicsEvent ev = new BlockPhysicsEvent(sand, sand.getBlockData());
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "owned gravity block must not fall and desync BlockKey ownership");
    }

    @Test
    void unownedGravityBlockPhysicsIsAllowed() {
        WorldMock world = server.addSimpleWorld("world");

        Block sand = world.getBlockAt(5, 70, 5);
        sand.setType(Material.SAND);

        BlockPhysicsEvent ev = new BlockPhysicsEvent(sand, sand.getBlockData());
        server.getPluginManager().callEvent(ev);

        assertFalse(ev.isCancelled(), "unowned gravity block physics should remain vanilla");
    }

    @Test
    void liquidFlowIntoOwnedCropIsCancelled() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block water = world.getBlockAt(6, 64, 6);
        water.setType(Material.WATER);
        Block wheat = world.getBlockAt(7, 64, 6);
        wheat.setType(Material.WHEAT);
        plugin.store().setOwner(BlockKey.of(wheat), alice.getUniqueId());

        BlockFromToEvent ev = new BlockFromToEvent(water, wheat);
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "water flow must not wash an owned crop");
    }

    @Test
    void liquidPhysicsOnOwnedCarpetIsCancelled() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block water = world.getBlockAt(8, 64, 8);
        water.setType(Material.WATER);
        Block carpet = world.getBlockAt(9, 64, 8);
        carpet.setType(Material.RED_CARPET);
        plugin.store().setOwner(BlockKey.of(carpet), alice.getUniqueId());

        BlockPhysicsEvent ev = new BlockPhysicsEvent(carpet, carpet.getBlockData(), water);
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "liquid-triggered physics must not pop owned carpets");
    }

    @Test
    void bucketOnForeignProtectedBlockIsCancelled() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        Block carpet = world.getBlockAt(10, 65, 10);
        carpet.setType(Material.BLUE_CARPET);
        plugin.store().setOwner(BlockKey.of(carpet), alice.getUniqueId());
        Block clicked = world.getBlockAt(10, 64, 10);
        clicked.setType(Material.STONE);

        PlayerBucketEmptyEvent ev = new PlayerBucketEmptyEvent(bob, carpet, clicked,
                BlockFace.UP, Material.WATER_BUCKET, new ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND);
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "player bucket must not replace a foreign protected block");
    }

    @Test
    void dispenserWaterNearOwnedBlockIsCancelled() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block dispenser = world.getBlockAt(30, 64, 30);
        dispenser.setType(Material.DISPENSER);
        Directional data = (Directional) Material.DISPENSER.createBlockData();
        data.setFacing(BlockFace.EAST);
        dispenser.setBlockData(data);

        Block crop = world.getBlockAt(31, 64, 30);
        crop.setType(Material.WHEAT);
        plugin.store().setOwner(BlockKey.of(crop), alice.getUniqueId());

        BlockDispenseEvent ev = new BlockDispenseEvent(dispenser,
                new ItemStack(Material.WATER_BUCKET), new Vector(1, 0, 0));
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "dispenser water must not be able to wash an owned block");
    }

    @Test
    void foreignPlayerCannotTrampleOwnedFarmland() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        Block farmland = world.getBlockAt(11, 64, 11);
        farmland.setType(Material.FARMLAND);
        plugin.store().setOwner(BlockKey.of(farmland), alice.getUniqueId());

        PlayerInteractEvent ev = new PlayerInteractEvent(bob, Action.PHYSICAL, null,
                farmland, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "foreign player must not trample owned farmland");
    }

    @Test
    void ownerCanStepOnOwnedFarmland() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block farmland = world.getBlockAt(12, 64, 12);
        farmland.setType(Material.FARMLAND);
        plugin.store().setOwner(BlockKey.of(farmland), alice.getUniqueId());

        PlayerInteractEvent ev = new PlayerInteractEvent(alice, Action.PHYSICAL, null,
                farmland, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(ev);

        assertFalse(ev.isCancelled(), "owner should be allowed to step on owned farmland");
    }

    @Test
    void ownedArmorStandCannotBeMovedByLiquid() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block water = world.getBlockAt(13, 64, 13);
        water.setType(Material.WATER);
        Location from = new Location(world, 13.5, 64.0, 13.5);
        ArmorStand stand = world.spawn(from, ArmorStand.class);

        EntityPlaceEvent place = new EntityPlaceEvent(stand, alice, water, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);

        EntityMoveEvent move = new EntityMoveEvent(stand, from, from.clone().add(0.25, 0, 0));
        server.getPluginManager().callEvent(move);

        assertTrue(move.isCancelled(), "owned armor stand must not be carried by water");
    }

    @Test
    void ownedArmorStandDryMoveIsAllowed() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block floor = world.getBlockAt(14, 64, 14);
        floor.setType(Material.STONE);
        Location from = new Location(world, 14.5, 65.0, 14.5);
        ArmorStand stand = world.spawn(from, ArmorStand.class);

        EntityPlaceEvent place = new EntityPlaceEvent(stand, alice, floor, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);

        EntityMoveEvent move = new EntityMoveEvent(stand, from, from.clone().add(0.25, 0, 0));
        server.getPluginManager().callEvent(move);

        assertFalse(move.isCancelled(), "owned armor stand movement away from liquids should remain untouched");
    }

    @Test
    void foreignPlayerCannotOpenOwnedChestMinecart() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        StorageMinecart cart = (StorageMinecart) world.spawnEntity(
                new Location(world, 32.5, 64.0, 32.5), EntityType.CHEST_MINECART);
        EntityPlaceEvent place = new EntityPlaceEvent(cart, alice,
                world.getBlockAt(32, 64, 32), BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);

        InventoryOpenEvent open = new InventoryOpenEvent(new SimpleInventoryViewMock(
                bob, cart.getInventory(), bob.getInventory(), InventoryType.CHEST));
        server.getPluginManager().callEvent(open);

        assertTrue(open.isCancelled(), "foreign player must not open an owned chest minecart");
    }

    @Test
    void hopperCannotDrainOwnedChestMinecart() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        StorageMinecart cart = (StorageMinecart) world.spawnEntity(
                new Location(world, 33.5, 64.0, 33.5), EntityType.CHEST_MINECART);
        EntityPlaceEvent place = new EntityPlaceEvent(cart, alice,
                world.getBlockAt(33, 64, 33), BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);

        Inventory destination = server.createInventory(null, 9);
        InventoryMoveItemEvent move = new InventoryMoveItemEvent(cart.getInventory(),
                new ItemStack(Material.DIAMOND), destination, true);
        server.getPluginManager().callEvent(move);

        assertTrue(move.isCancelled(), "hopper-style transfer must not drain an owned chest minecart");
    }

    @Test
    void breakingLinkedDoorHalfChecksOtherHalfOwner() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        Block lower = world.getBlockAt(34, 64, 34);
        Block upper = world.getBlockAt(34, 65, 34);
        setDoorHalf(lower, Bisected.Half.BOTTOM);
        setDoorHalf(upper, Bisected.Half.TOP);
        plugin.store().setOwner(BlockKey.of(lower), alice.getUniqueId());

        BlockBreakEvent ev = new BlockBreakEvent(upper, bob);
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "top door half must inherit protection from the owned bottom half");
    }

    @Test
    void ownerDoorBreakRemovesBothHalfOwners() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block lower = world.getBlockAt(35, 64, 35);
        Block upper = world.getBlockAt(35, 65, 35);
        setDoorHalf(lower, Bisected.Half.BOTTOM);
        setDoorHalf(upper, Bisected.Half.TOP);
        plugin.store().setOwner(BlockKey.of(lower), alice.getUniqueId());
        plugin.store().setOwner(BlockKey.of(upper), alice.getUniqueId());

        BlockBreakEvent ev = new BlockBreakEvent(lower, alice);
        server.getPluginManager().callEvent(ev);

        assertFalse(ev.isCancelled(), "owner should be able to break the door");
        assertNull(plugin.store().getOwner(BlockKey.of(lower)), "bottom door owner must be removed");
        assertNull(plugin.store().getOwner(BlockKey.of(upper)), "top door owner must be removed");
    }

    @Test
    void openingForeignDoorFromOtherHalfIsBlocked() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        Block lower = world.getBlockAt(36, 64, 36);
        Block upper = world.getBlockAt(36, 65, 36);
        setDoorHalf(lower, Bisected.Half.BOTTOM);
        setDoorHalf(upper, Bisected.Half.TOP);
        plugin.store().setOwner(BlockKey.of(lower), alice.getUniqueId()); // владелец только на нижней половине

        // Bob кликает по ВЕРХНЕЙ половине — раньше так можно было открыть чужую дверь в обход.
        PlayerInteractEvent ev = new PlayerInteractEvent(bob, Action.RIGHT_CLICK_BLOCK, null,
                upper, BlockFace.NORTH, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "clicking the other door half must not bypass open-protection");
    }

    @Test
    void ownerCanOpenOwnDoor() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block lower = world.getBlockAt(37, 64, 37);
        Block upper = world.getBlockAt(37, 65, 37);
        setDoorHalf(lower, Bisected.Half.BOTTOM);
        setDoorHalf(upper, Bisected.Half.TOP);
        plugin.store().setOwner(BlockKey.of(lower), alice.getUniqueId());
        plugin.store().setOwner(BlockKey.of(upper), alice.getUniqueId());

        PlayerInteractEvent ev = new PlayerInteractEvent(alice, Action.RIGHT_CLICK_BLOCK, null,
                upper, BlockFace.NORTH, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(ev);

        assertFalse(ev.isCancelled(), "owner should be able to open their own door");
    }

    @Test
    void ownedWallSignPhysicsIsCancelled() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block sign = world.getBlockAt(36, 64, 36);
        sign.setType(Material.OAK_WALL_SIGN);
        plugin.store().setOwner(BlockKey.of(sign), alice.getUniqueId());

        BlockPhysicsEvent ev = new BlockPhysicsEvent(sign, sign.getBlockData());
        server.getPluginManager().callEvent(ev);

        assertTrue(ev.isCancelled(), "owned wall signs must not pop from physics and leave stale ownership");
    }

    @Test
    void scrubOwnersDryRunDoesNotRemoveAirOwner() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock admin = server.addPlayer("Admin");
        PlayerMock alice = server.addPlayer("Alice");
        admin.setOp(true);
        admin.teleport(new Location(world, 40.5, 64.0, 40.5));
        world.loadChunk(40 >> 4, 40 >> 4);

        Block air = world.getBlockAt(40, 64, 40);
        air.setType(Material.AIR);
        plugin.store().setOwner(BlockKey.of(air), alice.getUniqueId());

        assertTrue(admin.performCommand("haven scrubowners radius 5 --dry-run"));

        assertEquals(alice.getUniqueId(), plugin.store().getOwner(BlockKey.of(air)),
                "dry-run must not remove stale ownership");
    }

    @Test
    void scrubOwnersWorldRemovesAirAndInvalidOwnersOnly() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock admin = server.addPlayer("Admin");
        PlayerMock alice = server.addPlayer("Alice");
        admin.setOp(true);
        admin.teleport(new Location(world, 41.5, 64.0, 41.5));
        world.loadChunk(41 >> 4, 41 >> 4);

        Block air = world.getBlockAt(41, 64, 41);
        air.setType(Material.AIR);
        Block water = world.getBlockAt(42, 64, 41);
        water.setType(Material.WATER);
        Block stone = world.getBlockAt(43, 64, 41);
        stone.setType(Material.STONE);
        plugin.store().setOwner(BlockKey.of(air), alice.getUniqueId());
        plugin.store().setOwner(BlockKey.of(water), alice.getUniqueId());
        plugin.store().setOwner(BlockKey.of(stone), alice.getUniqueId());

        assertTrue(admin.performCommand("haven scrubowners world world"));

        assertNull(plugin.store().getOwner(BlockKey.of(air)), "AIR owner must be scrubbed");
        assertNull(plugin.store().getOwner(BlockKey.of(water)), "liquid owner must be scrubbed as invalid");
        assertEquals(alice.getUniqueId(), plugin.store().getOwner(BlockKey.of(stone)),
                "solid owned block must remain protected");
    }

    @Test
    void pistonsCannotMoveOwnedBuildBlocks() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block piston = world.getBlockAt(10, 64, 10);
        piston.setType(Material.PISTON);
        Block block = world.getBlockAt(11, 64, 10);
        block.setType(Material.OAK_PLANKS);
        plugin.store().setOwner(BlockKey.of(block), alice.getUniqueId());

        BlockPistonExtendEvent extend = new BlockPistonExtendEvent(piston, List.of(block), BlockFace.EAST);
        server.getPluginManager().callEvent(extend);
        assertTrue(extend.isCancelled(), "piston must not move an owned build block");

        BlockPistonRetractEvent retract = new BlockPistonRetractEvent(piston, List.of(block), BlockFace.WEST);
        server.getPluginManager().callEvent(retract);
        assertTrue(retract.isCancelled(), "sticky piston must not pull an owned build block");
    }

    @Test
    void pistonsCanMoveUnownedBlocks() {
        WorldMock world = server.addSimpleWorld("world");

        Block piston = world.getBlockAt(20, 64, 20);
        piston.setType(Material.PISTON);
        Block block = world.getBlockAt(21, 64, 20);
        block.setType(Material.OAK_PLANKS);

        BlockPistonExtendEvent extend = new BlockPistonExtendEvent(piston, List.of(block), BlockFace.EAST);
        server.getPluginManager().callEvent(extend);
        assertFalse(extend.isCancelled(), "piston may move an unowned block");
    }

    @Test
    void reportWarnsCulpritAndCountsIncident() {
        server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        plugin.store().getPlayer(alice.getUniqueId()).playtimeMin = 120; // вменяемый репортёр (вес 1)

        alice.performCommand("report Bob грифит базу");

        assertEquals(1, plugin.store().incidentCount(bob.getUniqueId()), "жалоба должна засчитаться");

        boolean warned = false;
        String m;
        while ((m = bob.nextMessage()) != null) {
            // Фраза culprit-warned теперь MiniMessage; ищем устойчивый корень «пожаловал».
            if (m.contains("пожаловал") || m.contains("репутаци")) { warned = true; break; }
        }
        assertTrue(warned, "нарушитель должен получить предупреждение о жалобе");
    }

    private void setDoorHalf(Block block, Bisected.Half half) {
        Door data = (Door) Material.OAK_DOOR.createBlockData();
        data.setFacing(BlockFace.NORTH);
        data.setHalf(half);
        block.setBlockData(data);
    }
}
