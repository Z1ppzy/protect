package ru.haven;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.haven.core.DataStore;
import ru.haven.storage.SqliteStorage;
import ru.haven.util.BlockKey;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты чистой логики (репутация, доверие, стафф, инциденты, кулдауны, владение контейнерами)
 * на временной SQLite-БД, без запущенного сервера.
 */
class HavenLogicTest {

    private SqliteStorage db;
    private DataStore store;
    private File dbFile;

    @BeforeEach
    void setup() throws Exception {
        // JDBC4+ драйверы регистрируются сами через ServiceLoader — Class.forName не нужен.
        dbFile = File.createTempFile("haven-test", ".db");
        dbFile.deleteOnExit();
        db = new SqliteStorage(dbFile);

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("staff", List.of("Admin"));
        Settings settings = new Settings();
        settings.load(cfg);

        // worker=null → DataStore выполняет flush sync, чтобы тесты могли сразу проверить persistence
        // (без race condition между enqueue и проверкой). См. JavaDoc DataStore: legacy/тесты fallback.
        store = new DataStore(null, db, null, settings);
        store.loadAll();
    }

    @AfterEach
    void teardown() {
        if (db != null) db.close();
        if (dbFile != null) dbFile.delete();
    }

    /** Создаёт «вменяемого» репортёра (playtime 120м), чьи жалобы имеют вес 1. */
    private UUID credibleReporter() {
        UUID r = UUID.randomUUID();
        store.getOrCreate(r, "R" + r.toString().substring(0, 6));
        store.getPlayer(r).playtimeMin = 120;
        return r;
    }

    @Test
    void reputationFromPlaytime() {
        UUID a = UUID.randomUUID();
        store.getOrCreate(a, "A");
        store.getPlayer(a).playtimeMin = 600; // 10 часов
        assertEquals(100, store.reputation(a));
        assertEquals("TRUSTED", store.status(a));
    }

    @Test
    void incidentLowersReputation() {
        UUID a = UUID.randomUUID();
        store.getOrCreate(a, "A");
        store.getPlayer(a).playtimeMin = 600; // rep 100
        store.addIncident(a, credibleReporter(), "test");
        assertEquals(75, store.reputation(a));
        assertEquals("NEUTRAL", store.status(a));
        assertEquals(1, store.incidentCount(a));
    }

    @Test
    void manyIncidentsMakeGriefer() {
        UUID c = UUID.randomUUID();
        store.getOrCreate(c, "C");
        for (int i = 0; i < 3; i++) store.addIncident(c, credibleReporter(), "g");
        assertEquals(-75, store.reputation(c));
        assertEquals("GRIEFER", store.status(c));
    }

    @Test
    void staffIsImmuneAndMaxRep() {
        UUID s = UUID.randomUUID();
        store.getOrCreate(s, "Admin");
        store.recomputeStaffFromNames();
        assertTrue(store.isStaff(s));
        assertEquals(9999, store.reputation(s));
        assertEquals("STAFF", store.status(s));
    }

    @Test
    void bypassFlag() {
        UUID b = UUID.randomUUID();
        assertFalse(store.isBypass(b));
        store.setBypass(b, "B", true);
        assertTrue(store.isBypass(b));
    }

    @Test
    void trustAddRemove() {
        UUID owner = UUID.randomUUID(), friend = UUID.randomUUID();
        assertFalse(store.trustContains(owner, friend));
        store.addTrust(owner, friend);
        assertTrue(store.trustContains(owner, friend));
        assertEquals(1, store.trustedOf(owner).size());
        store.removeTrust(owner, friend);
        assertFalse(store.trustContains(owner, friend));
    }

    @Test
    void blockOwnership() {
        BlockKey k = new BlockKey(UUID.randomUUID(), 1, 2, 3);
        UUID owner = UUID.randomUUID();
        assertNull(store.getOwner(k));
        store.setOwner(k, owner);
        assertEquals(owner, store.getOwner(k));
        store.removeOwner(k);
        assertNull(store.getOwner(k));
    }

    @Test
    void reportCooldown() {
        UUID r = UUID.randomUUID(), c = UUID.randomUUID();
        assertTrue(store.canReport(r, c));
        assertFalse(store.canReport(r, c)); // в пределах кулдауна
    }

    @Test
    void incidentPersistsAndReloads() {
        UUID c = UUID.randomUUID();
        store.getOrCreate(c, "C");
        store.addIncident(c, credibleReporter(), "x");

        DataStore store2 = new DataStore(null, db, null, store.settings());
        store2.loadAll();
        assertEquals(1, store2.incidentCount(c));
    }

    @Test
    void reputationClampsAtMin() {
        UUID c = UUID.randomUUID();
        store.getOrCreate(c, "C");
        for (int i = 0; i < 10; i++) store.addIncident(c, credibleReporter(), "g"); // -250 → клемп
        assertEquals(-100, store.reputation(c));
        assertEquals("GRIEFER", store.status(c));
    }

    @Test
    void resolveIncidentClearsCount() {
        UUID c = UUID.randomUUID();
        store.getOrCreate(c, "C");
        int id = store.addIncident(c, credibleReporter(), "x");
        assertEquals(1, store.incidentCount(c));
        assertTrue(store.resolveIncident(id));
        assertEquals(0, store.incidentCount(c));
    }

    @Test
    void statsCounters() {
        assertEquals(0, store.ownedBlockCount());
        store.setOwner(new BlockKey(UUID.randomUUID(), 0, 0, 0), UUID.randomUUID());
        assertEquals(1, store.ownedBlockCount());
        store.getOrCreate(UUID.randomUUID(), "X");
        assertTrue(store.trackedPlayers() >= 1);
    }

    @Test
    void protectBlocksDefaultsTrue() {
        Settings s = new Settings();
        s.load(new YamlConfiguration());
        assertTrue(s.protectBlocks);
    }

    @Test
    void protectableMaterials() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("containers", List.of("CHEST", "BARREL"));
        Settings s = new Settings();
        s.load(cfg);
        assertTrue(s.isProtectable(Material.CHEST));
        assertTrue(s.isProtectable(Material.BARREL));
        assertTrue(s.isProtectable(Material.RED_SHULKER_BOX)); // авто *_SHULKER_BOX
        assertFalse(s.isProtectable(Material.STONE));
    }

    @Test
    void debugFlagParsed() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("debug", true);
        Settings s = new Settings();
        s.load(cfg);
        assertTrue(s.debug);
    }

    @Test
    void configAutoMergeAddsMissingKeysOnly() {
        YamlConfiguration def = new YamlConfiguration();
        def.set("a", 1);
        def.set("section.x", true);
        def.set("section.y", "default-y"); // нового ключа нет у игрока
        def.set("list", List.of("p"));

        YamlConfiguration cur = new YamlConfiguration();
        cur.set("a", 99);              // игрок поменял
        cur.set("section.x", false);   // игрок поменял

        List<String> added = Haven.mergeMissingKeys(def, cur);

        // добавились только отсутствующие
        assertTrue(added.contains("section.y"));
        assertTrue(added.contains("list"));
        assertFalse(added.contains("a"));
        assertFalse(added.contains("section.x"));

        // значения игрока не затёрты
        assertEquals(99, cur.getInt("a"));
        assertFalse(cur.getBoolean("section.x"));
        // новые ключи добавлены с дефолтом
        assertEquals("default-y", cur.getString("section.y"));
        assertEquals(List.of("p"), cur.getStringList("list"));
    }

    @Test
    void protectEntitiesDefaultsTrue() {
        Settings s = new Settings();
        s.load(new YamlConfiguration());
        assertTrue(s.protectEntities);
    }

    @Test
    void blockOwnerPersistsAfterFlush() {
        BlockKey k = new BlockKey(UUID.randomUUID(), 5, 6, 7);
        UUID owner = UUID.randomUUID();
        store.setOwner(k, owner);
        store.drainToDb(); // принудительный сброс write-behind в БД

        DataStore store2 = new DataStore(null, db, null, store.settings());
        store2.loadAll();
        assertEquals(owner, store2.getOwner(k));
    }

    @Test
    void removedBlockGoneAfterFlush() {
        BlockKey k = new BlockKey(UUID.randomUUID(), 8, 9, 10);
        store.setOwner(k, UUID.randomUUID());
        store.drainToDb();
        store.removeOwner(k);
        store.drainToDb();

        DataStore store2 = new DataStore(null, db, null, store.settings());
        store2.loadAll();
        assertNull(store2.getOwner(k));
    }

    // ---- playtime-гейт ----
    @Test
    void gateBlocksNewPlayer() {
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "New");
        assertEquals(480, store.gateState(u, false)); // 8ч по умолчанию, наиграно 0
    }

    @Test
    void gateOpensAfterPlaytime() {
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "Old");
        store.getPlayer(u).playtimeMin = 8 * 60;
        assertEquals(0, store.gateState(u, false));
    }

    @Test
    void verifiedSkipsGate() {
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "VIP");
        assertTrue(store.gateState(u, false) > 0);
        store.setVerified(u, "VIP", true);
        assertEquals(0, store.gateState(u, false));
    }

    @Test
    void exemptSkipsGate() {
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "Op");
        assertEquals(0, store.gateState(u, true)); // exempt (стафф/право) → открыто
    }

    @Test
    void lowRepRestrictsMechanics() {
        UUID reporter = UUID.randomUUID();
        store.getOrCreate(reporter, "Rep");
        store.getPlayer(reporter).playtimeMin = 120; // вменяемый репортёр

        UUID grief = UUID.randomUUID();
        store.getOrCreate(grief, "Grief");
        for (int i = 0; i < 3; i++) store.addIncident(grief, reporter, "g"); // rep -75
        assertTrue(store.reputation(grief) <= -50);
        assertEquals(-1, store.gateState(grief, false)); // ограничено рейтингом
    }

    // ---- взвешивание жалоб ----
    @Test
    void incidentFromFreshReporterHasNoWeight() {
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "C");
        UUID alt = UUID.randomUUID();
        store.getOrCreate(alt, "Alt"); // playtime 0 → не вменяемый
        store.addIncident(culprit, alt, "spam");
        assertEquals(0, store.incidentCount(culprit), "жалоба от новичка/альта не влияет на рейтинг");
    }

    @Test
    void incidentFromCredibleReporterCounts() {
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "C");
        UUID rep = UUID.randomUUID();
        store.getOrCreate(rep, "Rep");
        store.getPlayer(rep).playtimeMin = 120;
        store.addIncident(culprit, rep, "real");
        assertEquals(1, store.incidentCount(culprit));
    }

    // ---- L1: дневной лимит репортов ----

    @Test
    void dailyReportLimitCutsAtN() {
        store.settings().maxReportsPerDay = 5;
        UUID r = credibleReporter();
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 5; i++) {
            assertTrue(store.canReportToday(r, t0 + i), "первые 5 проходят (i=" + i + ")");
        }
        assertFalse(store.canReportToday(r, t0 + 5), "6-й репорт режется");
        assertFalse(store.canReportToday(r, t0 + 100), "и любой следующий в пределах окна — тоже");
    }

    @Test
    void dailyReportLimitResetsAfter24h() {
        store.settings().maxReportsPerDay = 3;
        UUID r = credibleReporter();
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 3; i++) assertTrue(store.canReportToday(r, t0 + i));
        assertFalse(store.canReportToday(r, t0 + 100), "лимит исчерпан в окне");
        // спустя 24ч+ все три старые записи устарели → можно подать ещё 3
        long later = t0 + 86_400_500L;
        for (int i = 0; i < 3; i++) assertTrue(store.canReportToday(r, later + i));
        assertFalse(store.canReportToday(r, later + 100), "снова лимит в новом окне");
    }

    @Test
    void dailyReportLimitDisabledByZero() {
        store.settings().maxReportsPerDay = 0;
        UUID r = credibleReporter();
        for (int i = 0; i < 50; i++) assertTrue(store.canReportToday(r));
    }

    @Test
    void dailyReportLimitStaffExempt() {
        store.settings().maxReportsPerDay = 2;
        UUID admin = UUID.randomUUID();
        store.getOrCreate(admin, "Admin");
        store.recomputeStaffFromNames();
        assertTrue(store.isStaff(admin));
        for (int i = 0; i < 20; i++) assertTrue(store.canReportToday(admin));
    }

    @Test
    void dailyReportLimitVerifiedExempt() {
        store.settings().maxReportsPerDay = 2;
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "V");
        store.setVerified(u, "V", true);
        for (int i = 0; i < 20; i++) assertTrue(store.canReportToday(u));
    }

    @Test
    void dailyReportLimitIndependentPerReporter() {
        store.settings().maxReportsPerDay = 2;
        UUID a = credibleReporter(), b = credibleReporter();
        long t0 = 1_700_000_000_000L;
        assertTrue(store.canReportToday(a, t0));
        assertTrue(store.canReportToday(a, t0 + 1));
        assertFalse(store.canReportToday(a, t0 + 2));
        // лимит a не влияет на b
        assertTrue(store.canReportToday(b, t0 + 2));
        assertTrue(store.canReportToday(b, t0 + 3));
        assertFalse(store.canReportToday(b, t0 + 4));
    }

    @Test
    void reportsTodayCountsCurrentWindow() {
        store.settings().maxReportsPerDay = 10;
        UUID r = credibleReporter();
        assertEquals(0, store.reportsToday(r));
        store.canReportToday(r);
        store.canReportToday(r);
        store.canReportToday(r);
        assertEquals(3, store.reportsToday(r));
    }

    // ---- L3: N независимых credible-репортёров → [ПОДТВЕРЖДЕНО] ----

    @Test
    void distinctReportersInWindowCountsCredibleOnly() {
        store.settings().confirmFromReporters = 2;
        store.settings().confirmWindowHours = 24;
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "Culprit");
        UUID r1 = credibleReporter(), r2 = credibleReporter();
        UUID alt = UUID.randomUUID();
        store.getOrCreate(alt, "Alt"); // playtime 0 → не credible, weight=0
        store.addIncident(culprit, r1, "g1");
        store.addIncident(culprit, alt, "spam");
        store.addIncident(culprit, r2, "g2");
        assertEquals(2, store.distinctReportersInWindow(culprit));
    }

    @Test
    void distinctReportersInWindowIgnoresResolved() {
        store.settings().confirmFromReporters = 2;
        store.settings().confirmWindowHours = 24;
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "Culprit");
        UUID r1 = credibleReporter(), r2 = credibleReporter();
        int id1 = store.addIncident(culprit, r1, "g1");
        store.addIncident(culprit, r2, "g2");
        assertEquals(2, store.distinctReportersInWindow(culprit));
        store.resolveIncident(id1);
        assertEquals(1, store.distinctReportersInWindow(culprit), "resolved выпадают");
    }

    @Test
    void confirmationFiresOnCrossingThreshold() {
        store.settings().confirmFromReporters = 2;
        store.settings().confirmWindowHours = 24;
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "Culprit");
        UUID r1 = credibleReporter(), r2 = credibleReporter();
        store.addIncident(culprit, r1, "g1");
        assertFalse(store.shouldFireConfirmation(culprit), "одной жалобы мало");
        store.addIncident(culprit, r2, "g2");
        assertTrue(store.shouldFireConfirmation(culprit), "вторая — crossing → fire");
        assertFalse(store.shouldFireConfirmation(culprit), "повторно НЕ дублируется");
    }

    @Test
    void confirmationDoesNotDoubleCountSameReporter() {
        store.settings().confirmFromReporters = 2;
        store.settings().confirmWindowHours = 24;
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "Culprit");
        UUID r1 = credibleReporter();
        store.addIncident(culprit, r1, "g1");
        store.addIncident(culprit, r1, "g2");
        store.addIncident(culprit, r1, "g3");
        assertFalse(store.shouldFireConfirmation(culprit), "один и тот же — не distinct");
    }

    @Test
    void confirmationIgnoresWeightZeroAlts() {
        store.settings().confirmFromReporters = 2;
        store.settings().confirmWindowHours = 24;
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "Culprit");
        UUID alt1 = UUID.randomUUID(); store.getOrCreate(alt1, "Alt1");
        UUID alt2 = UUID.randomUUID(); store.getOrCreate(alt2, "Alt2");
        UUID alt3 = UUID.randomUUID(); store.getOrCreate(alt3, "Alt3");
        store.addIncident(culprit, alt1, "spam1");
        store.addIncident(culprit, alt2, "spam2");
        store.addIncident(culprit, alt3, "spam3");
        assertFalse(store.shouldFireConfirmation(culprit), "альты не считаются credible");
    }

    @Test
    void confirmationResetsAfterResolves() {
        store.settings().confirmFromReporters = 2;
        store.settings().confirmWindowHours = 24;
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "Culprit");
        UUID r1 = credibleReporter(), r2 = credibleReporter();
        int id1 = store.addIncident(culprit, r1, "g1");
        int id2 = store.addIncident(culprit, r2, "g2");
        assertTrue(store.shouldFireConfirmation(culprit));
        // resolve обоих → distinct=0 → флаг сбрасывается
        store.resolveIncident(id1);
        store.resolveIncident(id2);
        assertFalse(store.shouldFireConfirmation(culprit), "после resolve флаг снимается");
        // новые жалобы → снова crossing
        UUID r3 = credibleReporter(), r4 = credibleReporter();
        store.addIncident(culprit, r3, "g3");
        store.addIncident(culprit, r4, "g4");
        assertTrue(store.shouldFireConfirmation(culprit), "fresh crossing после очистки");
    }

    @Test
    void confirmationDisabledByLowThreshold() {
        store.settings().confirmFromReporters = 0;
        store.settings().confirmWindowHours = 24;
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "Culprit");
        for (int i = 0; i < 5; i++) store.addIncident(culprit, credibleReporter(), "g");
        assertFalse(store.shouldFireConfirmation(culprit), "при 0 фича выключена");
        store.settings().confirmFromReporters = 1;
        assertFalse(store.shouldFireConfirmation(culprit), "при 1 тоже выключена (одиночка = шум)");
    }

    @Test
    void confirmationWindowExcludesOldIncidents() {
        store.settings().confirmFromReporters = 2;
        store.settings().confirmWindowHours = 1; // 1 час
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "Culprit");
        // Старая жалоба (ts = 0) — точно за окном (cutoff ~ now - 1h).
        // Используем addIncident напрямую через storage, чтобы поставить произвольный ts.
        UUID r1 = credibleReporter(), r2 = credibleReporter();
        // r1 — давным-давно
        store.storage().addIncident(culprit, r1, "old", 1L, 1);
        // r2 — сейчас
        store.addIncident(culprit, r2, "now");
        assertEquals(1, store.distinctReportersInWindow(culprit), "старая жалоба вне окна не считается");
        assertFalse(store.shouldFireConfirmation(culprit));
    }

    // ---- L2: anti-AFK для playtime ----

    @Test
    void activePlayerGainsPlaytime() {
        store.settings().afkTimeoutMinutes = 5;
        UUID u = UUID.randomUUID();
        long t0 = 1_700_000_000_000L;
        store.markActive(u, t0);
        // через 1 минуту → активен, playtime растёт
        assertTrue(store.tickPlaytimeFor(u, "U", t0 + 60_000L));
        assertEquals(1, store.playtimeOf(u));
        // ещё через 2 минуты после move → активен
        store.markActive(u, t0 + 2 * 60_000L);
        assertTrue(store.tickPlaytimeFor(u, "U", t0 + 3 * 60_000L));
        assertEquals(2, store.playtimeOf(u));
    }

    @Test
    void afkPlayerDoesNotGainPlaytime() {
        store.settings().afkTimeoutMinutes = 5;
        UUID u = UUID.randomUUID();
        long t0 = 1_700_000_000_000L;
        store.markActive(u, t0);
        // через 1 мин — активен
        assertTrue(store.tickPlaytimeFor(u, "U", t0 + 60_000L));
        assertEquals(1, store.playtimeOf(u));
        // не двигался 6 минут от первой активности → AFK
        assertFalse(store.tickPlaytimeFor(u, "U", t0 + 6 * 60_000L + 1));
        assertEquals(1, store.playtimeOf(u), "playtime не растёт пока AFK");
        // ещё час AFK — всё ещё 1
        assertFalse(store.tickPlaytimeFor(u, "U", t0 + 60 * 60_000L));
        assertEquals(1, store.playtimeOf(u));
    }

    @Test
    void afkResumesAfterMove() {
        store.settings().afkTimeoutMinutes = 5;
        UUID u = UUID.randomUUID();
        long t0 = 1_700_000_000_000L;
        store.markActive(u, t0);
        store.tickPlaytimeFor(u, "U", t0 + 60_000L); // 1
        assertEquals(1, store.playtimeOf(u));
        // 10 минут AFK — тик пропускается
        assertFalse(store.tickPlaytimeFor(u, "U", t0 + 10 * 60_000L));
        // вернулся — снова move
        store.markActive(u, t0 + 11 * 60_000L);
        assertTrue(store.tickPlaytimeFor(u, "U", t0 + 11 * 60_000L + 1));
        assertEquals(2, store.playtimeOf(u), "после возврата playtime растёт");
    }

    @Test
    void afkDisabledByZeroAlwaysTicks() {
        store.settings().afkTimeoutMinutes = 0;
        UUID u = UUID.randomUUID();
        // markActive НЕ вызываем — всё равно должно тикать
        assertTrue(store.tickPlaytimeFor(u, "U", 1_700_000_000_000L));
        assertEquals(1, store.playtimeOf(u));
        assertTrue(store.tickPlaytimeFor(u, "U", 1_700_000_000_000L + 60_000L));
        assertEquals(2, store.playtimeOf(u));
    }

    @Test
    void neverActivePlayerIsAfk() {
        store.settings().afkTimeoutMinutes = 5;
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        // markActive не вызывали — должен считаться AFK сразу
        assertTrue(store.isAfk(u, System.currentTimeMillis()));
    }

    @Test
    void isAfkRespectsTimeoutBoundary() {
        store.settings().afkTimeoutMinutes = 5;
        UUID u = UUID.randomUUID();
        long t0 = 1_700_000_000_000L;
        store.markActive(u, t0);
        assertFalse(store.isAfk(u, t0));
        assertFalse(store.isAfk(u, t0 + 5 * 60_000L), "ровно 5м — ещё не AFK");
        assertTrue(store.isAfk(u, t0 + 5 * 60_000L + 1), "5м+1мс — AFK");
    }

    @Test
    void clearActiveResetsTracking() {
        store.settings().afkTimeoutMinutes = 5;
        UUID u = UUID.randomUUID();
        long t0 = 1_700_000_000_000L;
        store.markActive(u, t0);
        assertFalse(store.isAfk(u, t0 + 60_000L));
        store.clearActive(u);
        assertTrue(store.isAfk(u, t0 + 60_000L), "после clearActive — AFK (Map не растёт после quit)");
    }

    @Test
    void afkIsPerPlayer() {
        store.settings().afkTimeoutMinutes = 5;
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        long t0 = 1_700_000_000_000L;
        store.markActive(a, t0);
        // b не активен
        assertFalse(store.isAfk(a, t0 + 60_000L));
        assertTrue(store.isAfk(b, t0 + 60_000L));
    }

    // ---- Discord webhook (v26.5.0): JSON-payload builder ----

    @Test
    void discordPayloadBasic() {
        String p = ru.haven.util.DiscordNotifier.buildPayload("hello world", "");
        // содержимое в content
        assertTrue(p.contains("\"content\":\"hello world\""));
        // защита от @everyone — parse:[] всегда
        assertTrue(p.contains("\"allowed_mentions\":{\"parse\":[]}"));
        // нет roles когда нет роли
        assertFalse(p.contains("\"roles\""));
    }

    @Test
    void discordPayloadEscapesQuotesBackslashAndNewline() {
        String p = ru.haven.util.DiscordNotifier.buildPayload(
                "He said \"hi\" \\ then\nleft", "");
        assertTrue(p.contains("\\\"hi\\\""), "кавычки экранированы");
        assertTrue(p.contains("\\\\"), "обратный слэш экранирован");
        assertTrue(p.contains("\\n"), "перевод строки экранирован");
    }

    @Test
    void discordPayloadEscapesControlChars() { // unicode-escape для control chars
        // Char(1) во входе должен превратиться в 6-символьный escape-формат.
        String p = ru.haven.util.DiscordNotifier.buildPayload("ab", "");
        assertTrue(p.contains("\\u0001"), "control char encoded as escape sequence");
        // А сырой char(1) НЕ должен попадать в JSON.
        assertFalse(p.contains("ab"), "raw control char must be encoded");
    }


    @Test
    void discordPayloadAddsRoleMentionWhenSet() {
        String p = ru.haven.util.DiscordNotifier.buildPayload("griefer alert", "123456789");
        // префикс <@&role>
        assertTrue(p.contains("\"content\":\"<@&123456789> griefer alert\""));
        // и роль в allowed_mentions
        assertTrue(p.contains("\"roles\":[\"123456789\"]"));
    }

    @Test
    void discordPayloadBlocksEveryoneEvenIfInContent() {
        // Даже если ник игрока «@everyone» — Discord не запингует благодаря parse:[].
        String p = ru.haven.util.DiscordNotifier.buildPayload(
                "@everyone Steve griefed", "");
        // контент сохраняется как есть (читаемо), но parse:[] блокирует mention
        assertTrue(p.contains("@everyone"));
        assertTrue(p.contains("\"parse\":[]"));
    }

    @Test
    void discordSendIsSilentOnEmptyWebhook() {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("test");
        // Не должно бросить: ни null, ни пустая строка.
        ru.haven.util.DiscordNotifier.sendConfirmedAlert("X", 2, 24, null, null, log);
        ru.haven.util.DiscordNotifier.sendConfirmedAlert("X", 2, 24, "", "", log);
        // (нет утверждения — assert «не упало» по факту прохождения метода)
    }

    @Test
    void discordEscapeJsonHandlesNull() {
        assertEquals("", ru.haven.util.DiscordNotifier.escapeJson(null));
    }

    // ---- Decay заброшенных построек (v26.6.0) ----

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = 86_400_000L;

    /** Включить decay и убрать grace-after-startup, чтобы тесты были детерминированы. */
    private void enableDecay() {
        store.settings().decayEnabled = true;
        store.settings().decaySoftDays = 30;
        store.settings().decayGraceAfterStartupMinutes = 0;
        store.settings().decayStaffExempt = true;
        store.settings().decayBypassExempt = true;
        store.setStartupTs(0L); // старт давно → grace прошёл
    }

    @Test
    void decayDisabledByDefault() {
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        store.getPlayer(u).lastLoginTs = NOW - 100 * DAY;
        assertFalse(store.isDecayed(u, NOW), "при decay.enabled=false никогда не decayed");
    }

    @Test
    void decayTriggersAfterInactivity() {
        enableDecay();
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        store.getPlayer(u).lastLoginTs = NOW - 31 * DAY;
        assertTrue(store.isDecayed(u, NOW), "31д без входа при пороге 30 → decayed");
    }

    @Test
    void decayNotTriggeredWhenRecentLogin() {
        enableDecay();
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        store.getPlayer(u).lastLoginTs = NOW - 5 * DAY;
        assertFalse(store.isDecayed(u, NOW), "5д назад — ещё под защитой");
    }

    @Test
    void decayGraceForUnknownLastLogin() {
        enableDecay();
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U"); // lastLoginTs = 0
        assertFalse(store.isDecayed(u, NOW), "никогда не видели вход → грейс, не decayed");
    }

    @Test
    void decayStaffExempt() {
        enableDecay();
        UUID admin = UUID.randomUUID();
        store.getOrCreate(admin, "Admin");
        store.recomputeStaffFromNames();
        store.getPlayer(admin).lastLoginTs = NOW - 100 * DAY;
        assertTrue(store.isStaff(admin));
        assertFalse(store.isDecayed(admin, NOW), "staff не decay'ится");
    }

    @Test
    void decayBypassExempt() {
        enableDecay();
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        store.setBypass(u, "U", true);
        store.getPlayer(u).lastLoginTs = NOW - 100 * DAY;
        assertFalse(store.isDecayed(u, NOW), "bypass не decay'ится");
    }

    @Test
    void decayGraceAfterStartup() {
        store.settings().decayEnabled = true;
        store.settings().decaySoftDays = 30;
        store.settings().decayGraceAfterStartupMinutes = 60;
        store.setStartupTs(NOW - 10 * 60_000L); // старт 10 минут назад, grace 60 мин
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        store.getPlayer(u).lastLoginTs = NOW - 100 * DAY;
        assertFalse(store.isDecayed(u, NOW), "в грейсе после старта — decay не применяется");
        // после грейса — decayed
        assertTrue(store.isDecayed(u, NOW + 60 * 60_000L), "через час после старта grace прошёл");
    }

    @Test
    void markLoginClearsDecay() {
        enableDecay();
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        store.getPlayer(u).lastLoginTs = NOW - 31 * DAY;
        store.recomputeDecayed();
        assertTrue(store.isDecayedCached(u), "до входа — в кэше заброшенных");
        store.markLogin(u, "U");
        assertFalse(store.isDecayedCached(u), "после входа — снят из кэша");
    }

    @Test
    void recomputeDecayedPopulatesCache() {
        enableDecay();
        UUID old1 = UUID.randomUUID(), old2 = UUID.randomUUID(), fresh = UUID.randomUUID();
        store.getOrCreate(old1, "Old1"); store.getPlayer(old1).lastLoginTs = NOW - 40 * DAY;
        store.getOrCreate(old2, "Old2"); store.getPlayer(old2).lastLoginTs = NOW - 35 * DAY;
        store.getOrCreate(fresh, "Fresh"); store.getPlayer(fresh).lastLoginTs = System.currentTimeMillis();
        int n = store.recomputeDecayed();
        assertEquals(2, n, "два заброшенных");
        assertTrue(store.isDecayedCached(old1));
        assertTrue(store.isDecayedCached(old2));
        assertFalse(store.isDecayedCached(fresh));
    }

    @Test
    void decayZeroSoftDaysIsNotInstantDecay() {
        // Footgun-guard: soft-days=0 НЕ должно мгновенно забросить весь мир.
        store.settings().decayEnabled = true;
        store.settings().decaySoftDays = 0;
        store.settings().decayGraceAfterStartupMinutes = 0;
        store.setStartupTs(0L);
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        store.getPlayer(u).lastLoginTs = NOW - 1000 * DAY; // древний вход
        assertFalse(store.isDecayed(u, NOW), "soft-days=0 → decay не применяется");
        assertEquals(-1, store.decayMinutesRemaining(u, NOW));
    }

    @Test
    void recomputeRemovesReturnedPlayerFromCache() {
        enableDecay();
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        store.getPlayer(u).lastLoginTs = 1000L; // древний → decayed
        store.recomputeDecayed();
        assertTrue(store.isDecayedCached(u), "заброшен до возврата");
        store.markLogin(u, "U");               // вернулся → lastLoginTs=now
        store.recomputeDecayed();              // removeIf должен убрать его из кэша
        assertFalse(store.isDecayedCached(u), "после возврата и пересчёта — не в кэше");
    }

    @Test
    void decayMinutesRemainingArithmetic() {
        enableDecay();
        UUID u = UUID.randomUUID();
        store.getOrCreate(u, "U");
        store.getPlayer(u).lastLoginTs = NOW - 10 * DAY; // прошло 10д, порог 30д → осталось 20д
        long mins = store.decayMinutesRemaining(u, NOW);
        assertEquals(20 * 24 * 60, mins, "осталось ровно 20 дней в минутах");
        // уже за порогом → 0
        store.getPlayer(u).lastLoginTs = NOW - 31 * DAY;
        assertEquals(0, store.decayMinutesRemaining(u, NOW));
        // exempt/выкл → -1
        store.settings().decayEnabled = false;
        assertEquals(-1, store.decayMinutesRemaining(u, NOW));
    }

    // ---- Оффлайн-сводка вторжений (v26.7.0) ----

    @Test
    void intrusionRecordedAndAggregated() {
        UUID owner = UUID.randomUUID(), actor = UUID.randomUUID();
        store.getOrCreate(actor, "Steve");
        for (int i = 0; i < 5; i++) store.recordIntrusion(owner, actor, "сломать", "world", 10, 64, 20);
        store.flushIntrusions(); // worker=null → sync
        List<ru.haven.storage.Storage.IntrusionRow> s = store.storage().intrusionSummary(owner, 0, 20);
        assertEquals(1, s.size(), "5 событий одной пары → одна агрегированная строка");
        assertEquals(5, s.get(0).count);
    }

    @Test
    void intrusionDifferentActionsSeparate() {
        UUID owner = UUID.randomUUID(), actor = UUID.randomUUID();
        store.recordIntrusion(owner, actor, "сломать", "world", 1, 2, 3);
        store.recordIntrusion(owner, actor, "открыть", "world", 1, 2, 3);
        store.flushIntrusions();
        assertEquals(2, store.storage().intrusionSummary(owner, 0, 20).size(), "разные действия — разные строки");
    }

    @Test
    void intrusionIgnoresSelf() {
        UUID x = UUID.randomUUID();
        store.recordIntrusion(x, x, "сломать", "world", 1, 2, 3);
        store.flushIntrusions();
        assertTrue(store.storage().intrusionSummary(x, 0, 20).isEmpty(), "владелец сам себя не вторгает");
    }

    @Test
    void intrusionDisabledDoesNotRecord() {
        store.settings().offlineSummaryEnabled = false;
        UUID owner = UUID.randomUUID(), actor = UUID.randomUUID();
        store.recordIntrusion(owner, actor, "сломать", "world", 1, 2, 3);
        store.flushIntrusions();
        assertTrue(store.storage().intrusionSummary(owner, 0, 20).isEmpty());
    }

    @Test
    void buildAndClearSummaryFormatsAndClears() {
        UUID owner = UUID.randomUUID(), actor = UUID.randomUUID();
        store.getOrCreate(actor, "Griefer");
        store.recordIntrusion(owner, actor, "открыть", "world", 100, 64, -50);
        store.flushIntrusions();
        List<String> lines = store.buildAndClearSummary(owner);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("Griefer"), "содержит имя нарушителя");
        assertTrue(lines.get(0).contains("открыть"), "содержит действие");
        // показали → очистили
        assertTrue(store.buildAndClearSummary(owner).isEmpty(), "после показа сводка пуста");
    }

    @Test
    void intrusionPruneRemovesOld() {
        UUID owner = UUID.randomUUID(), actor = UUID.randomUUID();
        // вставляем «старую» запись напрямую (ts глубоко в прошлом)
        store.storage().recordIntrusions(List.of(
                new ru.haven.storage.Storage.IntrusionRow(owner, actor, "сломать", "world", 1, 2, 3, 1, 1000L)));
        assertEquals(1, store.storage().intrusionSummary(owner, 0, 20).size());
        store.pruneIntrusions(); // cutoff = now - 24h; ts=1000 далеко за порогом → удалится
        assertTrue(store.storage().intrusionSummary(owner, 0, 20).isEmpty(), "старое удалено TTL-prune");
    }

    @Test
    void intrusionMaxPendingCap() {
        store.settings().offlineMaxPending = 2;
        UUID owner = UUID.randomUUID();
        store.recordIntrusion(owner, UUID.randomUUID(), "x", "world", 1, 2, 3);
        store.recordIntrusion(owner, UUID.randomUUID(), "x", "world", 1, 2, 3);
        store.recordIntrusion(owner, UUID.randomUUID(), "x", "world", 1, 2, 3); // 3-й ключ — отброшен
        store.flushIntrusions();
        assertEquals(2, store.storage().intrusionSummary(owner, 0, 20).size(), "DoS-кэп: не больше maxPending ключей");
    }

    // ---- CoreProtect интеграция: evidence storage + graceful degradation (v26.8.0) ----

    @Test
    void incidentEvidenceRoundTrip() {
        UUID culprit = UUID.randomUUID();
        store.getOrCreate(culprit, "C");
        int id = store.addIncident(culprit, credibleReporter(), "гриф");
        assertNull(store.storage().getIncidentEvidence(id), "до capture — нет evidence");
        store.storage().saveIncidentEvidence(id, "CoreProtect:\n  Steve: сломал CHEST (1,2,3)");
        String ev = store.storage().getIncidentEvidence(id);
        assertNotNull(ev);
        assertTrue(ev.contains("Steve"));
        assertTrue(ev.contains("сломал"));
    }

    @Test
    void evidenceForUnknownIncidentIsNull() {
        assertNull(store.storage().getIncidentEvidence(999999), "несуществующий инцидент → null");
    }

    @Test
    void coreProtectHookUnavailableByDefault() {
        // Без вызова init() (нет Bukkit/CoreProtect) хук недоступен и деградирует тихо.
        ru.haven.util.CoreProtectHook hook = new ru.haven.util.CoreProtectHook(
                java.util.logging.Logger.getLogger("test"));
        assertFalse(hook.isAvailable());
        assertTrue(hook.lookup("Steve", null, 50, 1800, 50).isEmpty(),
                "недоступный хук → пустой результат, без исключений");
    }

    // ---- i18n / messages.yml (v26.9.0) ----

    private ru.haven.util.Messages msgsWith(String... kv) {
        YamlConfiguration cfg = new YamlConfiguration();
        for (int i = 0; i + 1 < kv.length; i += 2) cfg.set(kv[i], kv[i + 1]);
        ru.haven.util.Messages m = new ru.haven.util.Messages(null);
        m.loadDirect(cfg, "<gray>H</gray> ");
        return m;
    }

    @Test
    void messagesPlaceholderSubstitution() {
        ru.haven.util.Messages m = msgsWith("trust.added", "<green>Игрок <white>{name}</white> добавлен");
        String s = m.format("trust.added", "name", "Steve");
        assertTrue(s.contains("Steve"), "плейсхолдер подставлен");
        assertFalse(s.contains("{name}"), "плейсхолдер не остался сырым");
    }

    @Test
    void messagesMultiplePlaceholders() {
        ru.haven.util.Messages m = msgsWith("trust.list", "<gray>Доверенные <white>{count}</white>: {names}");
        String s = m.format("trust.list", "count", 3, "names", "A, B, C");
        assertTrue(s.contains("3"));
        assertTrue(s.contains("A, B, C"));
    }

    @Test
    void messagesMissingKeyVisible() {
        ru.haven.util.Messages m = msgsWith("a", "b");
        String s = m.raw("nonexistent.key");
        assertTrue(s.contains("missing"), "отсутствующий ключ помечается, не крашит");
        assertTrue(s.contains("nonexistent.key"));
    }

    @Test
    void messagesRenderProducesComponent() {
        // MiniMessage-градиент рендерится в непустой Component без исключений.
        ru.haven.util.Messages m = msgsWith("hi", "<gradient:#ff0000:#00ff00>Hello</gradient>");
        net.kyori.adventure.text.Component c = m.render("hi");
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(c);
        assertTrue(plain.contains("Hello"), "градиент-текст рендерится в Component");
    }

    @Test
    void messagesFormatKeepsRawMiniTags() {
        // format() не рендерит — отдаёт сырой MiniMessage (рендер делает send/render).
        ru.haven.util.Messages m = msgsWith("plain", "<gray>Просто текст");
        assertEquals("<gray>Просто текст", m.format("plain"));
    }

    @Test
    void welcomeSettingsDefaults() {
        Settings s = new Settings();
        s.load(new YamlConfiguration());
        assertTrue(s.welcomeEnabled, "welcome включён по умолчанию");
        assertTrue(s.welcomeOnFirstJoin, "показ на первый вход по умолчанию");
        assertTrue(s.welcomeUseDialog, "Dialog-окно по умолчанию");
    }

    @Test
    void messagesListReturnsWelcomeLines() {
        // welcome-гайд хранится списком строк — list() отдаёт их построчно.
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("welcome", List.of("<gray>line1", "<white>line2", "<aqua>line3"));
        ru.haven.util.Messages m = new ru.haven.util.Messages(null);
        m.loadDirect(cfg, "");
        assertEquals(3, m.list("welcome").size(), "welcome парсится построчно");
        assertTrue(m.list("missing-list").isEmpty(), "отсутствующий список → пусто, не null");
    }
}
