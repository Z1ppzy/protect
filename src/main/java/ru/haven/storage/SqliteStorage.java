package ru.haven.storage;

import ru.haven.util.BlockKey;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite-бэкенд. Один файл, одно соединение, WAL.
 *
 * <p>Почему НЕ HikariCP: SQLite — single-writer. Несколько соединений только увеличат lock
 * contention (BUSY/LOCKED) и сбросят in-memory page cache при ротации. Один long-lived connection
 * с включённым WAL — стандарт для embedded SQLite (см. phiresky / SQLite docs).</p>
 *
 * <p>Threading: все мутации в продакшене идут через {@link StorageWorker} (single dedicated
 * thread), поэтому методы могут быть не synchronized. Но read-методы вызываются и из тестов на
 * main, поэтому оставлены synchronized на случай прямого использования.</p>
 */
public class SqliteStorage implements Storage {

    private final Connection conn;
    private final Logger logger;
    private final File file;

    public SqliteStorage(File file, Logger logger) throws SQLException {
        this.logger = logger != null ? logger : Logger.getLogger("Haven");
        this.file = file;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        init();
    }

    /** Перегрузка для тестов: без явного логгера. */
    public SqliteStorage(File file) throws SQLException { this(file, null); }

    private void init() throws SQLException {
        try (Statement s = conn.createStatement()) {
            // Tuned PRAGMAs (phiresky / Database School recommendations).
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous  = NORMAL");
            s.execute("PRAGMA temp_store   = MEMORY");
            s.execute("PRAGMA mmap_size    = 268435456");   // 256 MiB
            s.execute("PRAGMA cache_size   = -64000");      // 64 MiB (минус = KiB)
            s.execute("PRAGMA busy_timeout = 5000");
            // block_owners хранит владельцев ВСЕХ защищённых блоков (контейнеры — частный случай).
            s.executeUpdate("CREATE TABLE IF NOT EXISTS block_owners(world TEXT, x INT, y INT, z INT, owner TEXT, PRIMARY KEY(world,x,y,z))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trust(owner TEXT, trusted TEXT, PRIMARY KEY(owner,trusted))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, name TEXT, playtime_min INT DEFAULT 0, bypass INT DEFAULT 0, verified INT DEFAULT 0)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS incidents(id INTEGER PRIMARY KEY AUTOINCREMENT, culprit TEXT, reporter TEXT, reason TEXT, ts INT, status TEXT DEFAULT 'OPEN', weight INT DEFAULT 1)");
            // Индекс под горячий запрос loadIncidentCounts() (SUM(weight) GROUP BY culprit WHERE status='OPEN').
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_incidents_status_culprit ON incidents(status, culprit)");
            // Аудит-лог PvP-убийств. inquiry_result: NULL / 'ACCEPTED' / 'COMPLAINED'.
            s.executeUpdate("CREATE TABLE IF NOT EXISTS pvp_kills(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "killer TEXT NOT NULL, killer_name TEXT," +
                    "victim TEXT NOT NULL, victim_name TEXT," +
                    "ts INTEGER NOT NULL," +
                    "world TEXT, x INT, y INT, z INT," +
                    "inquiry_result TEXT)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pvp_kills_ts ON pvp_kills(ts DESC)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pvp_kills_killer_ts ON pvp_kills(killer, ts DESC)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pvp_kills_victim_ts ON pvp_kills(victim, ts DESC)");
            // Оффлайн-сводка вторжений: одна строка на (owner,actor,action,world), count агрегируется.
            s.executeUpdate("CREATE TABLE IF NOT EXISTS intrusion_events(" +
                    "owner TEXT NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, world TEXT," +
                    "x INT, y INT, z INT, count INT DEFAULT 1, ts INTEGER NOT NULL," +
                    "PRIMARY KEY(owner,actor,action,world))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_intrusion_owner ON intrusion_events(owner)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_intrusion_ts ON intrusion_events(ts)");
            // Таблица версии схемы (Flyway-стиль). См. SchemaMigrator.
            s.executeUpdate("CREATE TABLE IF NOT EXISTS _haven_schema_version (version INTEGER NOT NULL)");
            // Миграция со старой таблицы containers (если осталась с предыдущей версии).
            try {
                s.executeUpdate("INSERT OR IGNORE INTO block_owners(world,x,y,z,owner) SELECT world,x,y,z,owner FROM containers");
                s.executeUpdate("DROP TABLE containers");
            } catch (SQLException ignored) { /* старой таблицы нет — норма */ }
            // Миграции колонок для апгрейда со старых версий (дубликат → SQLite кинет ошибку → игнор).
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN verified INT DEFAULT 0"); } catch (SQLException ignored) {}
            try { s.executeUpdate("ALTER TABLE incidents ADD COLUMN weight INT DEFAULT 1"); } catch (SQLException ignored) {}
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN last_login_ts INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
            try { s.executeUpdate("ALTER TABLE incidents ADD COLUMN evidence TEXT"); } catch (SQLException ignored) {}
        }
        // После создания таблиц — запускаем цепочку миграций SchemaMigrator (v0 → TARGET_VERSION).
        SchemaMigrator.migrate(this, SchemaMigrator.DIALECT_SQLITE, logger);
    }

    private void err(String what, SQLException e) {
        logger.log(Level.SEVERE, "Haven SQLite: " + what, e);
    }

    // ---- owned blocks ----
    @Override
    public synchronized Map<BlockKey, UUID> loadBlocks() {
        Map<BlockKey, UUID> m = new HashMap<>();
        try (Statement s = conn.createStatement();
             ResultSet r = s.executeQuery("SELECT world,x,y,z,owner FROM block_owners")) {
            while (r.next()) {
                try {
                    m.put(new BlockKey(UUID.fromString(r.getString(1)), r.getInt(2), r.getInt(3), r.getInt(4)),
                            UUID.fromString(r.getString(5)));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (SQLException e) { err("loadBlocks", e); }
        return m;
    }

    @Override
    public synchronized void flushBlocks(Map<BlockKey, UUID> writes, Collection<BlockKey> deletes) {
        if (writes.isEmpty() && deletes.isEmpty()) return;
        try {
            conn.setAutoCommit(false);
            if (!writes.isEmpty()) {
                try (PreparedStatement p = conn.prepareStatement("INSERT OR REPLACE INTO block_owners(world,x,y,z,owner) VALUES(?,?,?,?,?)")) {
                    for (Map.Entry<BlockKey, UUID> e : writes.entrySet()) {
                        BlockKey k = e.getKey();
                        p.setString(1, k.world().toString());
                        p.setInt(2, k.x()); p.setInt(3, k.y()); p.setInt(4, k.z());
                        p.setString(5, e.getValue().toString());
                        p.addBatch();
                    }
                    p.executeBatch();
                }
            }
            if (!deletes.isEmpty()) {
                try (PreparedStatement p = conn.prepareStatement("DELETE FROM block_owners WHERE world=? AND x=? AND y=? AND z=?")) {
                    for (BlockKey k : deletes) {
                        p.setString(1, k.world().toString());
                        p.setInt(2, k.x()); p.setInt(3, k.y()); p.setInt(4, k.z());
                        p.addBatch();
                    }
                    p.executeBatch();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            err("flushBlocks", e);
            try { conn.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ---- trust ----
    @Override
    public synchronized Map<UUID, Set<UUID>> loadTrust() {
        Map<UUID, Set<UUID>> m = new HashMap<>();
        try (Statement s = conn.createStatement();
             ResultSet r = s.executeQuery("SELECT owner,trusted FROM trust")) {
            while (r.next()) {
                try {
                    m.computeIfAbsent(UUID.fromString(r.getString(1)), k -> new HashSet<>())
                            .add(UUID.fromString(r.getString(2)));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (SQLException e) { err("loadTrust", e); }
        return m;
    }

    @Override
    public synchronized void addTrust(UUID owner, UUID trusted) {
        try (PreparedStatement p = conn.prepareStatement("INSERT OR IGNORE INTO trust(owner,trusted) VALUES(?,?)")) {
            p.setString(1, owner.toString()); p.setString(2, trusted.toString()); p.executeUpdate();
        } catch (SQLException e) { err("addTrust", e); }
    }

    @Override
    public synchronized void removeTrust(UUID owner, UUID trusted) {
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM trust WHERE owner=? AND trusted=?")) {
            p.setString(1, owner.toString()); p.setString(2, trusted.toString()); p.executeUpdate();
        } catch (SQLException e) { err("removeTrust", e); }
    }

    // ---- players ----
    @Override
    public synchronized void loadPlayers(Map<UUID, Object[]> out) {
        try (Statement s = conn.createStatement();
             ResultSet r = s.executeQuery("SELECT uuid,name,playtime_min,bypass,verified,last_login_ts FROM players")) {
            while (r.next()) {
                try {
                    out.put(UUID.fromString(r.getString(1)),
                            new Object[]{r.getString(2), r.getInt(3), r.getInt(4) != 0, r.getInt(5) != 0, r.getLong(6)});
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (SQLException e) { err("loadPlayers", e); }
    }

    @Override
    public synchronized void flushPlayers(Collection<PlayerRow> rows) {
        if (rows.isEmpty()) return;
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement p = conn.prepareStatement(
                    "INSERT OR REPLACE INTO players(uuid,name,playtime_min,bypass,verified,last_login_ts) VALUES(?,?,?,?,?,?)")) {
                for (PlayerRow r : rows) {
                    p.setString(1, r.uuid.toString()); p.setString(2, r.name);
                    p.setInt(3, r.playtimeMin); p.setInt(4, r.bypass ? 1 : 0); p.setInt(5, r.verified ? 1 : 0);
                    p.setLong(6, r.lastLoginTs);
                    p.addBatch();
                }
                p.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            err("flushPlayers", e);
            try { conn.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ---- incidents ----
    @Override
    public synchronized Map<UUID, Integer> loadIncidentCounts() {
        Map<UUID, Integer> m = new HashMap<>();
        try (Statement s = conn.createStatement();
             ResultSet r = s.executeQuery("SELECT culprit,SUM(weight) FROM incidents WHERE status='OPEN' GROUP BY culprit")) {
            while (r.next()) {
                try { m.put(UUID.fromString(r.getString(1)), r.getInt(2)); } catch (IllegalArgumentException ignored) {}
            }
        } catch (SQLException e) { err("loadIncidentCounts", e); }
        return m;
    }

    @Override
    public synchronized int addIncident(UUID culprit, UUID reporter, String reason, long ts, int weight) {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO incidents(culprit,reporter,reason,ts,status,weight) VALUES(?,?,?,?,'OPEN',?)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, culprit.toString()); p.setString(2, reporter.toString());
            p.setString(3, reason); p.setLong(4, ts); p.setInt(5, weight);
            p.executeUpdate();
            try (ResultSet g = p.getGeneratedKeys()) { if (g.next()) return g.getInt(1); }
        } catch (SQLException e) { err("addIncident", e); }
        return -1;
    }

    @Override
    public synchronized boolean resolveIncident(int id) {
        try (PreparedStatement p = conn.prepareStatement("UPDATE incidents SET status='RESOLVED' WHERE id=? AND status='OPEN'")) {
            p.setInt(1, id);
            return p.executeUpdate() > 0;
        } catch (SQLException e) { err("resolveIncident", e); return false; }
    }

    @Override
    public synchronized int distinctReportersFor(UUID culprit, long sinceTs) {
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT COUNT(DISTINCT reporter) FROM incidents "
                        + "WHERE culprit=? AND ts>=? AND weight>0 AND status='OPEN'")) {
            p.setString(1, culprit.toString());
            p.setLong(2, sinceTs);
            try (ResultSet r = p.executeQuery()) { if (r.next()) return r.getInt(1); }
        } catch (SQLException e) { err("distinctReportersFor", e); }
        return 0;
    }

    @Override
    public synchronized List<String> listIncidents(UUID culprit, int limit) {
        List<String> out = new ArrayList<>();
        String sql = culprit == null
                ? "SELECT id,culprit,reporter,reason,status FROM incidents ORDER BY id DESC LIMIT ?"
                : "SELECT id,culprit,reporter,reason,status FROM incidents WHERE culprit=? ORDER BY id DESC LIMIT ?";
        try (PreparedStatement p = conn.prepareStatement(sql)) {
            int idx = 1;
            if (culprit != null) p.setString(idx++, culprit.toString());
            p.setInt(idx, limit);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.add("#" + r.getInt(1) + " [" + r.getString(5) + "] culprit=" + r.getString(2)
                            + " by=" + r.getString(3) + " : " + r.getString(4));
                }
            }
        } catch (SQLException e) { err("listIncidents", e); }
        return out;
    }

    @Override
    public synchronized void saveIncidentEvidence(int incidentId, String evidence) {
        try (PreparedStatement p = conn.prepareStatement("UPDATE incidents SET evidence=? WHERE id=?")) {
            p.setString(1, evidence); p.setInt(2, incidentId); p.executeUpdate();
        } catch (SQLException e) { err("saveIncidentEvidence", e); }
    }

    @Override
    public synchronized String getIncidentEvidence(int incidentId) {
        try (PreparedStatement p = conn.prepareStatement("SELECT evidence FROM incidents WHERE id=?")) {
            p.setInt(1, incidentId);
            try (ResultSet r = p.executeQuery()) { if (r.next()) return r.getString(1); }
        } catch (SQLException e) { err("getIncidentEvidence", e); }
        return null;
    }

    // ---- offline intrusion summary ----
    @Override
    public synchronized void recordIntrusions(Collection<IntrusionRow> rows) {
        if (rows.isEmpty()) return;
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement p = conn.prepareStatement(
                    "INSERT INTO intrusion_events(owner,actor,action,world,x,y,z,count,ts) VALUES(?,?,?,?,?,?,?,?,?) " +
                            "ON CONFLICT(owner,actor,action,world) DO UPDATE SET " +
                            "count=count+excluded.count, x=excluded.x, y=excluded.y, z=excluded.z, ts=excluded.ts")) {
                for (IntrusionRow r : rows) {
                    p.setString(1, r.owner.toString()); p.setString(2, r.actor.toString());
                    p.setString(3, r.action); p.setString(4, r.world);
                    p.setInt(5, r.x); p.setInt(6, r.y); p.setInt(7, r.z);
                    p.setInt(8, r.count); p.setLong(9, r.ts);
                    p.addBatch();
                }
                p.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            err("recordIntrusions", e);
            try { conn.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    @Override
    public synchronized List<IntrusionRow> intrusionSummary(UUID owner, long sinceTs, int limit) {
        List<IntrusionRow> out = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT owner,actor,action,world,x,y,z,count,ts FROM intrusion_events " +
                        "WHERE owner=? AND ts>=? ORDER BY ts DESC LIMIT ?")) {
            p.setString(1, owner.toString()); p.setLong(2, sinceTs); p.setInt(3, limit);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    try {
                        out.add(new IntrusionRow(UUID.fromString(r.getString(1)), UUID.fromString(r.getString(2)),
                                r.getString(3), r.getString(4), r.getInt(5), r.getInt(6), r.getInt(7),
                                r.getInt(8), r.getLong(9)));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (SQLException e) { err("intrusionSummary", e); }
        return out;
    }

    @Override
    public synchronized void clearIntrusions(UUID owner) {
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM intrusion_events WHERE owner=?")) {
            p.setString(1, owner.toString()); p.executeUpdate();
        } catch (SQLException e) { err("clearIntrusions", e); }
    }

    @Override
    public synchronized int pruneIntrusions(long olderThanTs) {
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM intrusion_events WHERE ts<?")) {
            p.setLong(1, olderThanTs);
            return p.executeUpdate();
        } catch (SQLException e) { err("pruneIntrusions", e); return 0; }
    }

    // ---- pvp kills ----
    @Override
    public synchronized int logKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName,
                                    long ts, String world, int x, int y, int z) {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO pvp_kills(killer,killer_name,victim,victim_name,ts,world,x,y,z) VALUES(?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, killerUuid.toString()); p.setString(2, killerName);
            p.setString(3, victimUuid.toString()); p.setString(4, victimName);
            p.setLong(5, ts); p.setString(6, world);
            p.setInt(7, x); p.setInt(8, y); p.setInt(9, z);
            p.executeUpdate();
            try (ResultSet g = p.getGeneratedKeys()) { if (g.next()) return g.getInt(1); }
        } catch (SQLException e) { err("logKill", e); }
        return -1;
    }

    @Override
    public synchronized void updateKillInquiryResult(int killId, String result) {
        try (PreparedStatement p = conn.prepareStatement("UPDATE pvp_kills SET inquiry_result=? WHERE id=?")) {
            p.setString(1, result); p.setInt(2, killId);
            p.executeUpdate();
        } catch (SQLException e) { err("updateKillInquiryResult", e); }
    }

    @Override
    public synchronized List<KillRecord> recentKills(long sinceTs, int limit) {
        return readKills("SELECT id,killer,killer_name,victim,victim_name,ts,world,x,y,z,inquiry_result "
                + "FROM pvp_kills WHERE ts>=? ORDER BY ts DESC LIMIT ?",
                ps -> { ps.setLong(1, sinceTs); ps.setInt(2, limit); });
    }

    @Override
    public synchronized List<KillRecord> killsByPlayer(UUID player, long sinceTs, int limit,
                                                       boolean asKiller, boolean asVictim) {
        if (!asKiller && !asVictim) return new ArrayList<>();
        String where;
        if (asKiller && asVictim) where = "(killer=? OR victim=?)";
        else if (asKiller)        where = "killer=?";
        else                      where = "victim=?";
        String sql = "SELECT id,killer,killer_name,victim,victim_name,ts,world,x,y,z,inquiry_result "
                + "FROM pvp_kills WHERE " + where + " AND ts>=? ORDER BY ts DESC LIMIT ?";
        final boolean both = asKiller && asVictim;
        return readKills(sql, ps -> {
            int i = 1;
            ps.setString(i++, player.toString());
            if (both) ps.setString(i++, player.toString());
            ps.setLong(i++, sinceTs);
            ps.setInt(i, limit);
        });
    }

    @FunctionalInterface
    private interface PsBinder { void bind(PreparedStatement ps) throws SQLException; }

    private List<KillRecord> readKills(String sql, PsBinder binder) {
        List<KillRecord> out = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement(sql)) {
            binder.bind(p);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    try {
                        out.add(new KillRecord(
                                r.getInt(1),
                                UUID.fromString(r.getString(2)), r.getString(3),
                                UUID.fromString(r.getString(4)), r.getString(5),
                                r.getLong(6), r.getString(7),
                                r.getInt(8), r.getInt(9), r.getInt(10),
                                r.getString(11)));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (SQLException e) { err("readKills", e); }
        return out;
    }

    // ---- schema versioning ----
    @Override
    public synchronized int readSchemaVersion() {
        try (Statement s = conn.createStatement();
             ResultSet r = s.executeQuery("SELECT version FROM _haven_schema_version LIMIT 1")) {
            return r.next() ? r.getInt(1) : 0;
        } catch (SQLException e) { err("readSchemaVersion", e); return 0; }
    }

    @Override
    public synchronized void writeSchemaVersion(int version) {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("DELETE FROM _haven_schema_version");
            s.executeUpdate("INSERT INTO _haven_schema_version(version) VALUES(" + version + ")");
        } catch (SQLException e) { err("writeSchemaVersion", e); }
    }

    @Override
    public synchronized void executeSchemaSql(String sql) throws SQLException {
        try (Statement s = conn.createStatement()) { s.executeUpdate(sql); }
    }

    // ---- meta ----
    @Override
    public String backendName() {
        return "SQLite (" + file.getName() + ", " + sizeMb() + " MiB)";
    }

    @Override
    public String poolStats() {
        return "single connection, WAL";
    }

    private String sizeMb() {
        long bytes = file.length();
        if (bytes <= 0) return "0";
        double mb = bytes / 1048576.0;
        return mb < 0.1 ? String.format("%.2f", mb) : String.format("%.1f", mb);
    }

    @Override
    public synchronized void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }

    /** Доступ к raw-соединению для миграции (только SQLite). */
    public Connection rawConnection() { return conn; }
}
