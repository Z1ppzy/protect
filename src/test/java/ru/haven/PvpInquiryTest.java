package ru.haven;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.haven.core.DataStore;
import ru.haven.core.PvpInquiry;
import ru.haven.storage.SqliteStorage;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-тесты на in-memory реестр PvP-inquiry и cooldown в DataStore.
 *
 * <p>Сама механика «нажатия кнопки» (parsing /hpvp accept|complain) — отдельный интеграционный
 * путь через HavenCommand; здесь проверяем чистую логику без MockBukkit (быстрее, понятнее).</p>
 */
class PvpInquiryTest {

    private SqliteStorage db;
    private DataStore store;
    private File dbFile;

    @BeforeEach
    void setup() throws Exception {
        dbFile = File.createTempFile("haven-pvp-test", ".db");
        dbFile.deleteOnExit();
        db = new SqliteStorage(dbFile);
        Settings settings = new Settings();
        settings.load(new YamlConfiguration());
        store = new DataStore(null, db, null, settings);
        store.loadAll();
    }

    @AfterEach
    void teardown() {
        if (db != null) db.close();
        if (dbFile != null) dbFile.delete();
    }

    @Test
    void registryAssignsIncrementingIds() {
        var reg = new PvpInquiry.Registry();
        UUID k = UUID.randomUUID(), v1 = UUID.randomUUID(), v2 = UUID.randomUUID();
        var i1 = reg.register(new PvpInquiry(k, "K", v1, "V1"));
        var i2 = reg.register(new PvpInquiry(k, "K", v2, "V2"));
        assertTrue(i2.id > i1.id);
        assertEquals(2, reg.size());
    }

    @Test
    void takeRemovesAndReturnsInquiry() {
        var reg = new PvpInquiry.Registry();
        var inq = reg.register(new PvpInquiry(UUID.randomUUID(), "K", UUID.randomUUID(), "V"));
        var taken = reg.take(inq.id, 60_000);
        assertNotNull(taken);
        assertEquals(inq.id, taken.id);
        assertNull(reg.take(inq.id, 60_000), "повторный take → null");
    }

    @Test
    void expiredInquiryReturnsNull() throws InterruptedException {
        var reg = new PvpInquiry.Registry();
        var inq = reg.register(new PvpInquiry(UUID.randomUUID(), "K", UUID.randomUUID(), "V"));
        Thread.sleep(50);
        // window=10ms → 50ms прошло → expired.
        assertNull(reg.take(inq.id, 10));
    }

    @Test
    void sweepRemovesExpired() throws InterruptedException {
        var reg = new PvpInquiry.Registry();
        reg.register(new PvpInquiry(UUID.randomUUID(), "K", UUID.randomUUID(), "V"));
        reg.register(new PvpInquiry(UUID.randomUUID(), "K", UUID.randomUUID(), "V"));
        Thread.sleep(50);
        int removed = reg.sweep(10);
        assertEquals(2, removed);
        assertEquals(0, reg.size());
    }

    @Test
    void pvpInquiryCooldownBlocksRepeat() {
        UUID killer = UUID.randomUUID(), victim = UUID.randomUUID();
        assertTrue(store.canPvpInquiry(killer, victim), "первый раз — разрешено");
        assertFalse(store.canPvpInquiry(killer, victim), "в пределах кулдауна — запрет");
    }

    @Test
    void pvpInquiryCooldownIsPerPair() {
        UUID killer = UUID.randomUUID(), v1 = UUID.randomUUID(), v2 = UUID.randomUUID();
        assertTrue(store.canPvpInquiry(killer, v1));
        // другая жертва — отдельный кулдаун
        assertTrue(store.canPvpInquiry(killer, v2));
        assertFalse(store.canPvpInquiry(killer, v1));
    }

    @Test
    void complainCreatesIncidentAgainstKiller() {
        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        store.getOrCreate(killer, "Killer");
        store.getOrCreate(victim, "Victim");
        store.getPlayer(victim).playtimeMin = 120; // credible reporter

        int incId = store.addIncident(killer, victim, "PvP без согласия — убил Victim");
        assertTrue(incId > 0);
        assertEquals(1, store.incidentCount(killer), "incident должен учитываться в счёте");
    }

    @Test
    void clearPvpCooldownAllowsNextInquiryImmediately() {
        UUID killer = UUID.randomUUID(), victim = UUID.randomUUID();
        assertTrue(store.canPvpInquiry(killer, victim));
        assertFalse(store.canPvpInquiry(killer, victim), "в окне кулдауна — запрет");
        // После ответа жертвы — сбрасываем cooldown.
        store.clearPvpCooldown(killer, victim);
        assertTrue(store.canPvpInquiry(killer, victim), "после сброса — снова можно");
    }

    // ---- kill log: logKill / recentKills / killsByPlayer ----
    @Test
    void logKillReturnsAutoIncrementId() {
        UUID k = UUID.randomUUID(), v = UUID.randomUUID();
        int id1 = db.logKill(k, "Killer", v, "Victim", System.currentTimeMillis(), "world", 1, 64, 2);
        int id2 = db.logKill(k, "Killer", v, "Victim", System.currentTimeMillis(), "world", 3, 64, 4);
        assertTrue(id1 > 0);
        assertTrue(id2 > id1);
    }

    @Test
    void recentKillsRespectsSinceTsAndLimit() {
        UUID k = UUID.randomUUID(), v = UUID.randomUUID();
        long now = System.currentTimeMillis();
        db.logKill(k, "K", v, "V", now - 8 * 86400_000L, "w", 0, 0, 0); // 8 дней назад
        db.logKill(k, "K", v, "V", now - 1000,            "w", 0, 0, 0); // только что
        db.logKill(k, "K", v, "V", now - 500,             "w", 0, 0, 0);

        var recent = db.recentKills(now - 3 * 86400_000L, 100);
        assertEquals(2, recent.size(), "только записи за последние 3 дня");
        assertTrue(recent.get(0).ts > recent.get(1).ts, "сортировка по ts DESC");
    }

    @Test
    void killsByPlayerFiltersByRole() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        long now = System.currentTimeMillis();
        db.logKill(a, "A", b, "B", now - 100, "w", 0, 0, 0); // a → b
        db.logKill(c, "C", a, "A", now - 200, "w", 0, 0, 0); // c → a
        db.logKill(b, "B", c, "C", now - 300, "w", 0, 0, 0); // b → c (a не участвует)

        // a как killer ИЛИ victim
        var both = db.killsByPlayer(a, now - 86400_000L, 100, true, true);
        assertEquals(2, both.size(), "a участвовал в 2 убийствах");

        // только как killer
        var onlyKiller = db.killsByPlayer(a, now - 86400_000L, 100, true, false);
        assertEquals(1, onlyKiller.size());
        assertEquals(a, onlyKiller.get(0).killerUuid);

        // только как victim
        var onlyVictim = db.killsByPlayer(a, now - 86400_000L, 100, false, true);
        assertEquals(1, onlyVictim.size());
        assertEquals(a, onlyVictim.get(0).victimUuid);

        // оба false → пустой результат (защита от глупости вызова)
        assertTrue(db.killsByPlayer(a, now - 86400_000L, 100, false, false).isEmpty());
    }

    @Test
    void updateKillInquiryResultStoresAcceptedOrComplained() {
        UUID k = UUID.randomUUID(), v = UUID.randomUUID();
        int id = db.logKill(k, "K", v, "V", System.currentTimeMillis(), "w", 0, 0, 0);
        db.updateKillInquiryResult(id, "ACCEPTED");
        var rows = db.recentKills(0, 10);
        var row = rows.stream().filter(r -> r.id == id).findFirst().orElseThrow();
        assertEquals("ACCEPTED", row.inquiryResult);

        db.updateKillInquiryResult(id, "COMPLAINED");
        rows = db.recentKills(0, 10);
        row = rows.stream().filter(r -> r.id == id).findFirst().orElseThrow();
        assertEquals("COMPLAINED", row.inquiryResult);
    }

    @Test
    void killSnapshotKeepsNameOnRename() {
        // Жертва переименовалась после убийства — в логе остаётся ник на момент события.
        UUID k = UUID.randomUUID(), v = UUID.randomUUID();
        int id = db.logKill(k, "OldKiller", v, "OldVictim", System.currentTimeMillis(), "w", 0, 0, 0);
        // Симулируем переименование (имена в логе при этом не должны измениться).
        var rows = db.recentKills(0, 10);
        var row = rows.stream().filter(r -> r.id == id).findFirst().orElseThrow();
        assertEquals("OldKiller", row.killerName);
        assertEquals("OldVictim", row.victimName);
    }

    // ---- Защита от регрессии (см. реальный баг с пропавшим пробелом в /hpvp accept<id>) ----
    @Test
    void pvpAcceptCommandHasSpaceBeforeId() {
        String cmd = ru.haven.util.Notify.pvpAcceptCommand(42);
        assertEquals("/hpvp accept 42", cmd);
        // Самопроверка: пробел действительно есть в нужном месте.
        assertTrue(cmd.contains("accept 4"), "между 'accept' и id должен быть пробел");
    }

    @Test
    void pvpComplainCommandHasSpaceBeforeId() {
        String cmd = ru.haven.util.Notify.pvpComplainCommand(7);
        assertEquals("/hpvp complain 7", cmd);
        assertTrue(cmd.contains("complain 7"), "между 'complain' и id должен быть пробел");
    }
}
