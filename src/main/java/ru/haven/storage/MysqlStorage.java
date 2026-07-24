package ru.haven.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import ru.haven.Settings;
import ru.haven.util.BlockKey;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL/MariaDB-бэкенд через HikariCP.
 *
 * <p>Схема намеренно остаётся типо-совместимой с SQLite (VARCHAR(36) для UUID,
 * INT AUTO_INCREMENT для id), чтобы миграция SQLite → MySQL была 1-к-1 без перекодировок.
 * Это компромисс: BINARY(16) был бы ~9× компактнее ({@code emmer.dev / planetscale}),
 * но усложнил бы миграцию и тесты. Под наш масштаб (≤10⁶ строк) разница в IO — &lt;200MB,
 * приемлемо. См. отчёт «known limitations» по будущей оптимизации схемы.</p>
 *
 * <p>JDBC-флаги (см. research-отчёт): {@code rewriteBatchedStatements=true},
 * {@code cachePrepStmts=true}, {@code useServerPrepStmts=true} — критичны для batch-INSERT.</p>
 *
 * <p>Драйвер: либо {@code org.mariadb.jdbc.Driver} (LGPL, предпочтительный, jdbc:mariadb://),
 * либо {@code com.mysql.cj.jdbc.Driver} (если у пользователя стоит MySQL Connector/J).
 * Оба грузим через Paper {@code libraries:} — не shaded.</p>
 */
public class MysqlStorage implements Storage {

    /** Версия MySQL, с которой доступен алиас вставляемой строки: {@code INSERT ... AS new}. */
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    private final HikariDataSource pool;
    private final Logger logger;
    private final String backendLabel;
    /** См. {@link #detectRowAlias()}: какой диалект UPSERT понимает конкретный сервер. */
    private final boolean rowAlias;

    public MysqlStorage(Settings s, Logger logger) throws SQLException {
        this.logger = logger != null ? logger : Logger.getLogger("Haven");

        HikariConfig hc = new HikariConfig();
        boolean useMariaDriver = driverAvailable("org.mariadb.jdbc.Driver");
        String urlPrefix = useMariaDriver ? "jdbc:mariadb://" : "jdbc:mysql://";
        if (useMariaDriver) {
            hc.setDriverClassName("org.mariadb.jdbc.Driver");
        } else if (driverAvailable("com.mysql.cj.jdbc.Driver")) {
            hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        } else {
            throw new SQLException("Не найден JDBC-драйвер для MySQL/MariaDB. "
                    + "Убедись что Paper подгрузил libraries из plugin.yml.");
        }
        this.backendLabel = (useMariaDriver ? "MariaDB" : "MySQL") + " " + s.mysqlHost + ":" + s.mysqlPort
                + "/" + s.mysqlDatabase;

        String base = urlPrefix + s.mysqlHost + ":" + s.mysqlPort + "/" + s.mysqlDatabase
                + "?useUnicode=true&characterEncoding=utf8mb4"
                + "&useSSL=" + s.mysqlUseSsl;
        // Параметры из конфига идут последними — чтобы админ мог перебить любой наш дефолт.
        String extra = s.mysqlExtraParams == null || s.mysqlExtraParams.isBlank() ? ""
                : (s.mysqlExtraParams.startsWith("&") ? "" : "&") + s.mysqlExtraParams;

        // Диалект UPSERT задаёт сервер, а не драйвер, поэтому спрашиваем версию отдельным
        // коротким коннектом — до пула, потому что от ответа зависят и флаги в URL.
        this.rowAlias = detectRowAlias(base + extra, s.mysqlUser, s.mysqlPassword);

        String url = base
                // Performance flags — см. MySQL Connector/J Performance Extensions docs.
                // rewriteBatchedStatements склеивает батч в multi-values INSERT и спотыкается о
                // legacy VALUES(col) в ON DUPLICATE KEY UPDATE → включаем только для new.col-диалекта.
                // На MariaDB батчи и без него идут пачкой (bulk-протокол, useBulkStmts).
                + "&rewriteBatchedStatements=" + rowAlias
                + "&cachePrepStmts=true"
                + "&useServerPrepStmts=true"
                + "&prepStmtCacheSize=250"
                + "&prepStmtCacheSqlLimit=2048"
                + "&useLocalSessionState=true"
                + "&cacheServerConfiguration=true"
                + "&cacheResultSetMetadata=true"
                + (useMariaDriver ? "" : "&permitMysqlScheme=true") // no-op для cj
                + extra;

        hc.setJdbcUrl(url);
        hc.setUsername(s.mysqlUser);
        hc.setPassword(s.mysqlPassword);
        hc.setPoolName("Haven-Hikari");
        hc.setMaximumPoolSize(s.mysqlPoolMax);
        hc.setMinimumIdle(s.mysqlPoolMinIdle);
        hc.setConnectionTimeout(s.mysqlConnTimeoutMs);
        hc.setMaxLifetime(s.mysqlMaxLifetimeMs);
        // READ COMMITTED: research показывает, что REPEATABLE READ (default) тяжелит INSERT под нагрузкой.
        hc.setTransactionIsolation("TRANSACTION_READ_COMMITTED");

        this.pool = new HikariDataSource(hc);
        init();
    }

    private boolean driverAvailable(String fqn) {
        try { Class.forName(fqn); return true; } catch (ClassNotFoundException e) { return false; }
    }

    /**
     * Понимает ли сервер алиас вставляемой строки
     * ({@code INSERT ... AS new ... ON DUPLICATE KEY UPDATE col=new.col})?
     *
     * <p>Это синтаксис MySQL 8.0.19+. MariaDB его не поддерживает ни в одной версии и отвечает
     * «You have an error in your SQL syntax», поэтому для неё (и для старых MySQL) остаётся
     * legacy-форма {@code VALUES(col)} — её понимают оба сервера. Драйвер тут не показатель:
     * mariadb-java-client спокойно ходит и в MySQL, так что спрашиваем сам сервер.</p>
     *
     * <p>Не достучались — берём совместимый путь: он рабочий везде, просто медленнее на батчах.
     * Реальную ошибку подключения всё равно поднимет Hikari следующей строкой.</p>
     */
    private boolean detectRowAlias(String url, String user, String password) {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            String v = c.getMetaData().getDatabaseProductVersion();
            if (v == null || v.toLowerCase(Locale.ROOT).contains("mariadb")) return false;
            return atLeast(v, 8, 0, 19);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Haven MySQL: не удалось определить версию сервера — "
                    + "используем совместимый синтаксис UPSERT", e);
            return false;
        }
    }

    /** Сравнение версии вида {@code 8.0.36-log} с порогом. Нераспознанное → false (совместимый путь). */
    private static boolean atLeast(String version, int major, int minor, int patch) {
        Matcher m = VERSION.matcher(version);
        if (!m.find()) return false;
        int[] got = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        int[] need = {major, minor, patch};
        for (int i = 0; i < 3; i++) {
            if (got[i] != need[i]) return got[i] > need[i];
        }
        return true;
    }

    /** {@code AS new} перед ON DUPLICATE KEY UPDATE — только там, где сервер его понимает. */
    private String rowAliasClause() {
        return rowAlias ? " AS new " : " ";
    }

    /** Ссылка на значение вставляемой строки внутри ON DUPLICATE KEY UPDATE. */
    private String inserted(String column) {
        return rowAlias ? "new." + column : "VALUES(" + column + ")";
    }

    private void init() throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            // VARCHAR(36) ascii для UUID — пишем строкой как в SQLite. ascii в утилизации = 1 байт/символ.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS block_owners(
                  world VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
                  owner VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  PRIMARY KEY(world, x, z, y)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trust(
                  owner VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  trusted VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  PRIMARY KEY(owner, trusted)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players(
                  uuid VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  name VARCHAR(32),
                  playtime_min INT DEFAULT 0,
                  bypass TINYINT DEFAULT 0,
                  verified TINYINT DEFAULT 0,
                  last_login_ts BIGINT DEFAULT 0,
                  PRIMARY KEY(uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS incidents(
                  id INT NOT NULL AUTO_INCREMENT,
                  culprit VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  reporter VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  reason VARCHAR(500),
                  ts BIGINT,
                  status VARCHAR(16) CHARACTER SET ascii DEFAULT 'OPEN',
                  weight INT DEFAULT 1,
                  evidence TEXT,
                  PRIMARY KEY(id),
                  INDEX idx_status_culprit (status, culprit)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            // PvP kills (аудит). inquiry_result: NULL / 'ACCEPTED' / 'COMPLAINED'.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pvp_kills(
                  id INT NOT NULL AUTO_INCREMENT,
                  killer VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  killer_name VARCHAR(32),
                  victim VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  victim_name VARCHAR(32),
                  ts BIGINT NOT NULL,
                  world VARCHAR(36), x INT, y INT, z INT,
                  inquiry_result VARCHAR(16) CHARACTER SET ascii,
                  PRIMARY KEY(id),
                  INDEX idx_ts (ts),
                  INDEX idx_killer_ts (killer, ts),
                  INDEX idx_victim_ts (victim, ts)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            // Оффлайн-сводка вторжений: одна строка на (owner,actor,action,world), count агрегируется.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS intrusion_events(
                  owner VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  actor VARCHAR(36) CHARACTER SET ascii NOT NULL,
                  action VARCHAR(32) NOT NULL,
                  world VARCHAR(36),
                  x INT, y INT, z INT,
                  count INT DEFAULT 1,
                  ts BIGINT NOT NULL,
                  PRIMARY KEY(owner,actor,action,world),
                  INDEX idx_intrusion_owner (owner),
                  INDEX idx_intrusion_ts (ts)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
            // Таблица версии схемы (Flyway-стиль). См. SchemaMigrator.
            s.executeUpdate("CREATE TABLE IF NOT EXISTS _haven_schema_version (version INT NOT NULL) ENGINE=InnoDB");
            // Миграция колонки для апгрейда с БД, созданной до v3 (дубликат → MySQL кинет ошибку → игнор).
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN last_login_ts BIGINT DEFAULT 0"); } catch (SQLException ignored) {}
            try { s.executeUpdate("ALTER TABLE incidents ADD COLUMN evidence TEXT"); } catch (SQLException ignored) {}
        }
        SchemaMigrator.migrate(this, SchemaMigrator.DIALECT_MYSQL, logger);
    }

    private void err(String what, SQLException e) {
        logger.log(Level.SEVERE, "Haven MySQL: " + what, e);
    }

    // ---- blocks ----
    @Override
    public Map<BlockKey, UUID> loadBlocks() {
        Map<BlockKey, UUID> m = new HashMap<>();
        try (Connection c = pool.getConnection();
             Statement s = c.createStatement();
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
    public void flushBlocks(Map<BlockKey, UUID> writes, Collection<BlockKey> deletes) {
        if (writes.isEmpty() && deletes.isEmpty()) return;
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!writes.isEmpty()) {
                    try (PreparedStatement p = c.prepareStatement(
                            "INSERT INTO block_owners(world,x,y,z,owner) VALUES(?,?,?,?,?)" + rowAliasClause()
                                    + "ON DUPLICATE KEY UPDATE owner=" + inserted("owner"))) {
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
                    try (PreparedStatement p = c.prepareStatement(
                            "DELETE FROM block_owners WHERE world=? AND x=? AND y=? AND z=?")) {
                        for (BlockKey k : deletes) {
                            p.setString(1, k.world().toString());
                            p.setInt(2, k.x()); p.setInt(3, k.y()); p.setInt(4, k.z());
                            p.addBatch();
                        }
                        p.executeBatch();
                    }
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) { err("flushBlocks", e); }
    }

    // ---- trust ----
    @Override
    public Map<UUID, Set<UUID>> loadTrust() {
        Map<UUID, Set<UUID>> m = new HashMap<>();
        try (Connection c = pool.getConnection();
             Statement s = c.createStatement();
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
    public void addTrust(UUID owner, UUID trusted) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "INSERT IGNORE INTO trust(owner,trusted) VALUES(?,?)")) {
            p.setString(1, owner.toString()); p.setString(2, trusted.toString());
            p.executeUpdate();
        } catch (SQLException e) { err("addTrust", e); }
    }

    @Override
    public void removeTrust(UUID owner, UUID trusted) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement("DELETE FROM trust WHERE owner=? AND trusted=?")) {
            p.setString(1, owner.toString()); p.setString(2, trusted.toString());
            p.executeUpdate();
        } catch (SQLException e) { err("removeTrust", e); }
    }

    // ---- players ----
    @Override
    public void loadPlayers(Map<UUID, Object[]> out) {
        try (Connection c = pool.getConnection();
             Statement s = c.createStatement();
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
    public void flushPlayers(Collection<PlayerRow> rows) {
        if (rows.isEmpty()) return;
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO players(uuid,name,playtime_min,bypass,verified,last_login_ts) VALUES(?,?,?,?,?,?)"
                                + rowAliasClause()
                                + "ON DUPLICATE KEY UPDATE name=" + inserted("name")
                                + ", playtime_min=" + inserted("playtime_min")
                                + ", bypass=" + inserted("bypass")
                                + ", verified=" + inserted("verified")
                                + ", last_login_ts=" + inserted("last_login_ts"))) {
                    for (PlayerRow r : rows) {
                        p.setString(1, r.uuid.toString()); p.setString(2, r.name);
                        p.setInt(3, r.playtimeMin); p.setInt(4, r.bypass ? 1 : 0); p.setInt(5, r.verified ? 1 : 0);
                        p.setLong(6, r.lastLoginTs);
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) { err("flushPlayers", e); }
    }

    // ---- incidents ----
    @Override
    public Map<UUID, Integer> loadIncidentCounts() {
        Map<UUID, Integer> m = new HashMap<>();
        try (Connection c = pool.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT culprit,SUM(weight) FROM incidents WHERE status='OPEN' GROUP BY culprit")) {
            while (r.next()) {
                try { m.put(UUID.fromString(r.getString(1)), r.getInt(2)); } catch (IllegalArgumentException ignored) {}
            }
        } catch (SQLException e) { err("loadIncidentCounts", e); }
        return m;
    }

    @Override
    public int addIncident(UUID culprit, UUID reporter, String reason, long ts, int weight) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement(
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
    public boolean resolveIncident(int id) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement("UPDATE incidents SET status='RESOLVED' WHERE id=? AND status='OPEN'")) {
            p.setInt(1, id);
            return p.executeUpdate() > 0;
        } catch (SQLException e) { err("resolveIncident", e); return false; }
    }

    @Override
    public int distinctReportersFor(UUID culprit, long sinceTs) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement(
                     "SELECT COUNT(DISTINCT reporter) FROM incidents "
                             + "WHERE culprit=? AND ts>=? AND weight>0 AND status='OPEN'")) {
            p.setString(1, culprit.toString());
            p.setLong(2, sinceTs);
            try (ResultSet r = p.executeQuery()) { if (r.next()) return r.getInt(1); }
        } catch (SQLException e) { err("distinctReportersFor", e); }
        return 0;
    }

    @Override
    public List<String> listIncidents(UUID culprit, int limit) {
        List<String> out = new ArrayList<>();
        String sql = culprit == null
                ? "SELECT id,culprit,reporter,reason,status FROM incidents ORDER BY id DESC LIMIT ?"
                : "SELECT id,culprit,reporter,reason,status FROM incidents WHERE culprit=? ORDER BY id DESC LIMIT ?";
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement(sql)) {
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
    public void saveIncidentEvidence(int incidentId, String evidence) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement("UPDATE incidents SET evidence=? WHERE id=?")) {
            p.setString(1, evidence); p.setInt(2, incidentId); p.executeUpdate();
        } catch (SQLException e) { err("saveIncidentEvidence", e); }
    }

    @Override
    public String getIncidentEvidence(int incidentId) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT evidence FROM incidents WHERE id=?")) {
            p.setInt(1, incidentId);
            try (ResultSet r = p.executeQuery()) { if (r.next()) return r.getString(1); }
        } catch (SQLException e) { err("getIncidentEvidence", e); }
        return null;
    }

    // ---- offline intrusion summary ----
    @Override
    public void recordIntrusions(Collection<IntrusionRow> rows) {
        if (rows.isEmpty()) return;
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO intrusion_events(owner,actor,action,world,x,y,z,count,ts) "
                                + "VALUES(?,?,?,?,?,?,?,?,?)" + rowAliasClause()
                                + "ON DUPLICATE KEY UPDATE count=count+" + inserted("count")
                                + ", x=" + inserted("x")
                                + ", y=" + inserted("y")
                                + ", z=" + inserted("z")
                                + ", ts=" + inserted("ts"))) {
                    for (IntrusionRow r : rows) {
                        p.setString(1, r.owner.toString()); p.setString(2, r.actor.toString());
                        p.setString(3, r.action); p.setString(4, r.world);
                        p.setInt(5, r.x); p.setInt(6, r.y); p.setInt(7, r.z);
                        p.setInt(8, r.count); p.setLong(9, r.ts);
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) { err("recordIntrusions", e); }
    }

    @Override
    public List<IntrusionRow> intrusionSummary(UUID owner, long sinceTs, int limit) {
        List<IntrusionRow> out = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement(
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
    public void clearIntrusions(UUID owner) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement("DELETE FROM intrusion_events WHERE owner=?")) {
            p.setString(1, owner.toString()); p.executeUpdate();
        } catch (SQLException e) { err("clearIntrusions", e); }
    }

    @Override
    public int pruneIntrusions(long olderThanTs) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement("DELETE FROM intrusion_events WHERE ts<?")) {
            p.setLong(1, olderThanTs);
            return p.executeUpdate();
        } catch (SQLException e) { err("pruneIntrusions", e); return 0; }
    }

    // ---- pvp kills ----
    @Override
    public int logKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName,
                       long ts, String world, int x, int y, int z) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement(
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
    public void updateKillInquiryResult(int killId, String result) {
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement("UPDATE pvp_kills SET inquiry_result=? WHERE id=?")) {
            p.setString(1, result); p.setInt(2, killId);
            p.executeUpdate();
        } catch (SQLException e) { err("updateKillInquiryResult", e); }
    }

    @Override
    public List<KillRecord> recentKills(long sinceTs, int limit) {
        return readKills(
                "SELECT id,killer,killer_name,victim,victim_name,ts,world,x,y,z,inquiry_result "
                        + "FROM pvp_kills WHERE ts>=? ORDER BY ts DESC LIMIT ?",
                ps -> { ps.setLong(1, sinceTs); ps.setInt(2, limit); });
    }

    @Override
    public List<KillRecord> killsByPlayer(UUID player, long sinceTs, int limit,
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
        try (Connection c = pool.getConnection();
             PreparedStatement p = c.prepareStatement(sql)) {
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
    public int readSchemaVersion() {
        try (Connection c = pool.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT version FROM _haven_schema_version LIMIT 1")) {
            return r.next() ? r.getInt(1) : 0;
        } catch (SQLException e) { err("readSchemaVersion", e); return 0; }
    }

    @Override
    public void writeSchemaVersion(int version) {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM _haven_schema_version");
            s.executeUpdate("INSERT INTO _haven_schema_version(version) VALUES(" + version + ")");
        } catch (SQLException e) { err("writeSchemaVersion", e); }
    }

    @Override
    public void executeSchemaSql(String sql) throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    // ---- meta ----
    @Override
    public String backendName() { return backendLabel; }

    @Override
    public String poolStats() {
        HikariPoolMXBean mx = pool.getHikariPoolMXBean();
        if (mx == null) return "pool not running";
        return "pool active=" + mx.getActiveConnections() + " idle=" + mx.getIdleConnections()
                + " total=" + mx.getTotalConnections() + " wait=" + mx.getThreadsAwaitingConnection();
    }

    @Override
    public void close() {
        if (!pool.isClosed()) pool.close();
    }
}
