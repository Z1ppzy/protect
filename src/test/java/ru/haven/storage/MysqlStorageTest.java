package ru.haven.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import ru.haven.Settings;
import ru.haven.util.BlockKey;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты {@link MysqlStorage} против реальной MariaDB/MySQL.
 *
 * <h3>Стратегия выбора БД</h3>
 * <ol>
 *   <li><b>Testcontainers</b>: если Docker доступен через docker-java API — поднимаем чистый
 *       MariaDB-контейнер на тестовый класс (CI-friendly, гарантированная изоляция).</li>
 *   <li><b>Локальный MySQL</b>: если Testcontainers упал (типичный случай на Windows + Docker Desktop
 *       npipe), пробуем подключиться к {@code HAVEN_TEST_MYSQL_URL} или к
 *       {@code localhost:3307/haven_test} (дефолт — соответствует контейнеру {@code local_mysql}
 *       у разработчика). Между тестами таблицы чистятся через TRUNCATE.</li>
 *   <li><b>Skip</b>: если ни Docker, ни localhost MySQL не отвечают — тесты пропускаются
 *       с понятным сообщением (а не падают красным в CI без Docker).</li>
 * </ol>
 *
 * <h3>Локальная настройка под dev (один раз)</h3>
 * <pre>
 * docker exec &lt;mysql-container&gt; mysql -uroot -p&lt;pwd&gt; -e \
 *   "CREATE DATABASE haven_test; CREATE USER 'haven_test'@'%' IDENTIFIED BY 'haven_test'; \
 *    GRANT ALL ON haven_test.* TO 'haven_test'@'%';"
 * </pre>
 */
class MysqlStorageTest {

    private static MariaDBContainer<?> container;
    private static String jdbcUrl;
    private static String user;
    private static String password;
    private static String host;
    private static int port;
    private static String database;

    private MysqlStorage db;

    @BeforeAll
    static void resolveBackend() {
        // (1) Попробуем Testcontainers.
        try {
            container = new MariaDBContainer<>("mariadb:11.4")
                    .withDatabaseName("haven")
                    .withUsername("haven")
                    .withPassword("haven_test");
            container.start();
            jdbcUrl = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
            host = container.getHost();
            port = container.getFirstMappedPort();
            database = container.getDatabaseName();
            System.out.println("[MysqlStorageTest] Using Testcontainers MariaDB at " + jdbcUrl);
            return;
        } catch (Throwable t) {
            System.out.println("[MysqlStorageTest] Testcontainers unavailable (" + t.getClass().getSimpleName()
                    + "): " + t.getMessage() + " — fallback to localhost MySQL.");
            container = null;
        }
        // (2) Локальный MySQL: env override или дефолт под local_mysql контейнер.
        host = System.getenv().getOrDefault("HAVEN_TEST_MYSQL_HOST", "localhost");
        port = Integer.parseInt(System.getenv().getOrDefault("HAVEN_TEST_MYSQL_PORT", "3307"));
        database = System.getenv().getOrDefault("HAVEN_TEST_MYSQL_DB", "haven_test");
        user = System.getenv().getOrDefault("HAVEN_TEST_MYSQL_USER", "haven_test");
        password = System.getenv().getOrDefault("HAVEN_TEST_MYSQL_PASS", "haven_test");
        jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?permitMysqlScheme=true";
        // Sanity-check: можем ли реально подключиться?
        boolean ok = false;
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, password)) {
            assertNotNull(c);
            System.out.println("[MysqlStorageTest] Using local MySQL at " + jdbcUrl);
            ok = true;
        } catch (Throwable t) {
            System.out.println("[MysqlStorageTest] localhost MySQL probe failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
        Assumptions.assumeTrue(ok, "MySQL недоступен (ни Testcontainers, ни " + jdbcUrl + ") — тесты пропущены.");
    }

    @AfterAll
    static void stopContainer() {
        if (container != null) container.stop();
    }

    @BeforeEach
    void setup() throws Exception {
        Settings s = new Settings();
        s.load(new YamlConfiguration());
        s.storageType = "mysql";
        s.mysqlHost = host;
        s.mysqlPort = port;
        s.mysqlDatabase = database;
        s.mysqlUser = user;
        s.mysqlPassword = password;
        s.mysqlExtraParams = "";
        s.mysqlUseSsl = false;
        s.mysqlPoolMax = 4;
        s.mysqlPoolMinIdle = 1;
        s.mysqlConnTimeoutMs = 5000;
        s.mysqlMaxLifetimeMs = 1800000;

        db = new MysqlStorage(s, null);
        truncateAll();
    }

    private void truncateAll() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, password);
             Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS=0");
            st.execute("TRUNCATE TABLE block_owners");
            st.execute("TRUNCATE TABLE trust");
            st.execute("TRUNCATE TABLE players");
            st.execute("TRUNCATE TABLE incidents");
            st.execute("TRUNCATE TABLE pvp_kills");
            st.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    // ---- backend identity ----
    @Test
    void backendNameAndPoolStatsReport() {
        assertTrue(db.backendName().startsWith("MariaDB") || db.backendName().startsWith("MySQL"));
        assertTrue(db.poolStats().contains("pool"));
        assertTrue(db.poolStats().contains("total="));
    }

    // ---- block_owners ----
    @Test
    void blockRoundtrip() {
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID();
        BlockKey k = new BlockKey(world, 10, 64, 20);

        Map<BlockKey, UUID> writes = new HashMap<>();
        writes.put(k, owner);
        db.flushBlocks(writes, new HashSet<>());

        Map<BlockKey, UUID> read = db.loadBlocks();
        assertEquals(1, read.size());
        assertEquals(owner, read.get(k));
    }

    @Test
    void blockUpsertReplacesOwner() {
        UUID world = UUID.randomUUID(), o1 = UUID.randomUUID(), o2 = UUID.randomUUID();
        BlockKey k = new BlockKey(world, 1, 1, 1);

        Map<BlockKey, UUID> w1 = new HashMap<>(); w1.put(k, o1);
        db.flushBlocks(w1, new HashSet<>());

        Map<BlockKey, UUID> w2 = new HashMap<>(); w2.put(k, o2);
        db.flushBlocks(w2, new HashSet<>());

        assertEquals(o2, db.loadBlocks().get(k), "upsert должен заменять owner");
    }

    @Test
    void blockDeleteRemoves() {
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID();
        BlockKey k = new BlockKey(world, 5, 5, 5);
        Map<BlockKey, UUID> w = new HashMap<>(); w.put(k, owner);
        db.flushBlocks(w, new HashSet<>());
        assertEquals(owner, db.loadBlocks().get(k));

        List<BlockKey> dels = new ArrayList<>(); dels.add(k);
        db.flushBlocks(new HashMap<>(), dels);
        assertNull(db.loadBlocks().get(k), "после delete запись должна исчезнуть");
    }

    @Test
    void blockBatchInsertsMany() {
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID();
        Map<BlockKey, UUID> batch = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            batch.put(new BlockKey(world, i, 0, i), owner);
        }
        db.flushBlocks(batch, new HashSet<>());
        assertEquals(500, db.loadBlocks().size());
    }

    // ---- trust ----
    @Test
    void trustAddAndLoad() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        db.addTrust(a, b);
        db.addTrust(a, c);
        var loaded = db.loadTrust();
        assertEquals(1, loaded.size());
        assertEquals(2, loaded.get(a).size());
        assertTrue(loaded.get(a).contains(b));
        assertTrue(loaded.get(a).contains(c));
    }

    @Test
    void trustAddIsIdempotent() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        db.addTrust(a, b);
        db.addTrust(a, b); // no-op (INSERT IGNORE)
        assertEquals(1, db.loadTrust().get(a).size());
    }

    @Test
    void trustRemove() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        db.addTrust(a, b);
        db.removeTrust(a, b);
        var t = db.loadTrust().get(a);
        assertTrue(t == null || t.isEmpty());
    }

    // ---- players ----
    @Test
    void playerFlushAndLoad() {
        UUID u = UUID.randomUUID();
        List<Storage.PlayerRow> rows = List.of(
                new Storage.PlayerRow(u, "Alice", 123, true, false)
        );
        db.flushPlayers(rows);

        Map<UUID, Object[]> out = new HashMap<>();
        db.loadPlayers(out);
        Object[] v = out.get(u);
        assertNotNull(v);
        assertEquals("Alice", v[0]);
        assertEquals(123, (int) v[1]);
        assertEquals(true, v[2]);
        assertEquals(false, v[3]);
    }

    @Test
    void playerUpsertOverwrites() {
        UUID u = UUID.randomUUID();
        db.flushPlayers(List.of(new Storage.PlayerRow(u, "Old", 10, false, false)));
        db.flushPlayers(List.of(new Storage.PlayerRow(u, "New", 99, true, true)));

        Map<UUID, Object[]> out = new HashMap<>();
        db.loadPlayers(out);
        Object[] v = out.get(u);
        assertEquals("New", v[0]);
        assertEquals(99, (int) v[1]);
        assertEquals(true, v[2]);
        assertEquals(true, v[3]);
    }

    // ---- incidents ----
    @Test
    void incidentInsertReturnsAutoIncrementId() {
        UUID c = UUID.randomUUID(), r = UUID.randomUUID();
        int id1 = db.addIncident(c, r, "test 1", System.currentTimeMillis(), 1);
        int id2 = db.addIncident(c, r, "test 2", System.currentTimeMillis(), 1);
        assertTrue(id1 > 0);
        assertTrue(id2 > id1);
    }

    @Test
    void incidentCountsSumByCulpritWhenOpen() {
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), r = UUID.randomUUID();
        db.addIncident(c1, r, "x", 1, 1);
        db.addIncident(c1, r, "y", 2, 1);
        db.addIncident(c2, r, "z", 3, 2);

        Map<UUID, Integer> counts = db.loadIncidentCounts();
        assertEquals(2, counts.get(c1));
        assertEquals(2, counts.get(c2));
    }

    @Test
    void incidentResolveHidesFromCounts() {
        UUID c = UUID.randomUUID(), r = UUID.randomUUID();
        int id = db.addIncident(c, r, "boom", 1, 1);
        assertEquals(1, db.loadIncidentCounts().get(c));

        assertTrue(db.resolveIncident(id));
        var afterCounts = db.loadIncidentCounts();
        assertTrue(afterCounts.get(c) == null || afterCounts.get(c) == 0,
                "после resolve SUM(weight) WHERE OPEN не должен включать этот инцидент");
        assertFalse(db.resolveIncident(id));
    }

    @Test
    void listIncidentsAllAndFiltered() {
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), r = UUID.randomUUID();
        db.addIncident(c1, r, "1", 1, 1);
        db.addIncident(c2, r, "2", 2, 1);
        db.addIncident(c1, r, "3", 3, 1);

        List<String> all = db.listIncidents(null, 100);
        assertEquals(3, all.size());

        List<String> filtered = db.listIncidents(c1, 100);
        assertEquals(2, filtered.size());
        for (String s : filtered) assertTrue(s.contains(c1.toString()));
    }

    // ---- pvp kills ----
    @Test
    void killLogRoundtripInMysql() {
        UUID k = UUID.randomUUID(), v = UUID.randomUUID();
        long now = System.currentTimeMillis();
        int id1 = db.logKill(k, "Killer", v, "Victim", now - 1000, "world", 1, 64, 2);
        int id2 = db.logKill(k, "Killer", v, "Victim", now - 500, "world", 3, 64, 4);
        assertTrue(id1 > 0);
        assertTrue(id2 > id1);

        var rows = db.recentKills(0, 100);
        assertEquals(2, rows.size());
        // Sort DESC по ts.
        assertTrue(rows.get(0).ts > rows.get(1).ts);
    }

    @Test
    void killByPlayerFiltersAndUpdateResultInMysql() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        long now = System.currentTimeMillis();
        int id = db.logKill(a, "A", b, "B", now, "world", 0, 0, 0);

        var both = db.killsByPlayer(a, 0, 100, true, true);
        assertEquals(1, both.size());
        assertEquals(a, both.get(0).killerUuid);
        assertEquals("A", both.get(0).killerName);
        assertNull(both.get(0).inquiryResult, "до апдейта inquiry_result = null");

        db.updateKillInquiryResult(id, "COMPLAINED");
        var afterUpdate = db.killsByPlayer(a, 0, 100, true, true);
        assertEquals("COMPLAINED", afterUpdate.get(0).inquiryResult);

        // Только как victim — пусто.
        assertTrue(db.killsByPlayer(a, 0, 100, false, true).isEmpty());
    }

    // ---- end-to-end через DataStore + StorageWorker ----
    @Test
    void endToEndThroughWorker() throws InterruptedException {
        StorageWorker worker = new StorageWorker(null);
        try {
            Settings settings = new Settings();
            settings.load(new YamlConfiguration());
            var store = new ru.haven.core.DataStore(null, db, worker, settings);
            store.loadAll();

            UUID world = UUID.randomUUID(), owner = UUID.randomUUID();
            BlockKey k = new BlockKey(world, 100, 50, 200);
            store.setOwner(k, owner);
            store.drainToDb();

            long deadline = System.currentTimeMillis() + 3000;
            while (worker.queueSize() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);

            assertEquals(owner, db.loadBlocks().get(k),
                    "через worker → MySQL: write-behind end-to-end должен работать");
        } finally {
            worker.shutdown(java.time.Duration.ofSeconds(5));
        }
    }
}
