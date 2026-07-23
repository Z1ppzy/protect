package ru.haven.storage;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.state.BlockStateMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import ru.haven.Haven;
import ru.haven.util.BlockKey;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-тест storage-слоя через MockBukkit: плагин реально стартует,
 * {@link Haven#onEnable} создаёт {@link SqliteStorage} + {@link StorageWorker},
 * write-behind enqueue реально проходит через writer-тред, и при reload в новый
 * DataStore данные на диске видны.
 *
 * <p>Закрывает пункт «Этап 4.3 — Добавить интеграционный тест на SqliteStorage через MockBukkit».</p>
 */
class SqliteStoragePluginTest {

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
    void storageBackendIsSqliteByDefault() {
        assertNotNull(plugin.db(), "Storage должен быть инициализирован при onEnable");
        assertTrue(plugin.db() instanceof SqliteStorage, "default storage backend = SQLite");
        assertNotNull(plugin.worker(), "StorageWorker должен быть инициализирован");
        assertTrue(plugin.worker().isAccepting(), "worker принимает задачи во время работы плагина");
        // diag-метрики доступны
        assertTrue(plugin.db().backendName().startsWith("SQLite"));
        assertEquals("single connection, WAL", plugin.db().poolStats());
    }

    @Test
    void blockPlaceFlowsThroughWorkerAndPersists() throws InterruptedException {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block block = world.getBlockAt(42, 64, 42);
        block.setType(Material.OAK_PLANKS);

        // Эмулируем place: real BlockProtectionListener должен снять owner = Alice.
        BlockStateMock replaced = (BlockStateMock) block.getState();
        BlockPlaceEvent place = new BlockPlaceEvent(block, replaced, block,
                new ItemStack(Material.OAK_PLANKS), alice, true, org.bukkit.inventory.EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);
        assertFalse(place.isCancelled(), "своя установка не должна быть отменена");
        assertEquals(alice.getUniqueId(), plugin.store().getOwner(BlockKey.of(block)),
                "после place владелец зафиксирован in-memory");

        // Триггерим write-behind drain → enqueue в worker.
        plugin.store().drainToDb();

        // Ждём, пока writer реально дописал в SQLite (queue → 0 + дополнительный grace).
        long deadline = System.currentTimeMillis() + 2000;
        while (plugin.worker().queueSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, plugin.worker().queueSize(), "writer должен сдренировать очередь за &lt;2с");
        assertTrue(plugin.worker().completedFlushes() >= 1, "по крайней мере один flush был");

        // Перечитываем напрямую из SQLite (минуем in-memory кэш DataStore).
        var diskMap = plugin.db().loadBlocks();
        assertEquals(alice.getUniqueId(), diskMap.get(BlockKey.of(block)),
                "после flush владелец должен быть на диске");
    }

    @Test
    void breakRemovesOwnerAndPersistsDelete() throws InterruptedException {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock alice = server.addPlayer("Alice");

        Block block = world.getBlockAt(1, 64, 1);
        block.setType(Material.STONE_BRICKS);
        plugin.store().setOwner(BlockKey.of(block), alice.getUniqueId());
        plugin.store().drainToDb();

        BlockBreakEvent ev = new BlockBreakEvent(block, alice);
        server.getPluginManager().callEvent(ev);
        assertFalse(ev.isCancelled(), "владелец ломает свой блок");
        assertNull(plugin.store().getOwner(BlockKey.of(block)),
                "after-break in-memory: owner снят");

        plugin.store().drainToDb();
        long deadline = System.currentTimeMillis() + 2000;
        while (plugin.worker().queueSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNull(plugin.db().loadBlocks().get(BlockKey.of(block)),
                "after-break on-disk: запись удалена");
    }

    @Test
    void trustChangesArePersistedAsynchronously() throws InterruptedException {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");

        plugin.store().addTrust(alice.getUniqueId(), bob.getUniqueId());
        assertTrue(plugin.store().trustContains(alice.getUniqueId(), bob.getUniqueId()),
                "trust сразу виден in-memory");

        // Ждём writer.
        long deadline = System.currentTimeMillis() + 2000;
        while (plugin.worker().queueSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        // Раздельный read через storage → видно на диске.
        var trustOnDisk = plugin.db().loadTrust();
        assertNotNull(trustOnDisk.get(alice.getUniqueId()), "owner-запись должна быть на диске");
        assertTrue(trustOnDisk.get(alice.getUniqueId()).contains(bob.getUniqueId()),
                "trusted UUID должен быть на диске");
    }
}
