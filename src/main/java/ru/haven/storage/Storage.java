package ru.haven.storage;

import ru.haven.util.BlockKey;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Бэкенд хранилища (SQLite по умолчанию или MySQL/MariaDB через config.yml).
 *
 * <h3>Контракт потоков</h3>
 * <ul>
 *   <li><b>load*</b> — синхронные, вызываются ОДИН РАЗ на onEnable (main-тред).</li>
 *   <li><b>flush*</b> и точечные мутации — могут вызываться из любого треда; внутри реализаций они
 *       идут через {@link StorageWorker} (single dedicated writer) или защищены пулом соединений.
 *       <b>НЕ блокирующие main</b>: см. {@code DataStore} — там все мутации enqueue.</li>
 *   <li><b>close()</b> — на onDisable, после {@link StorageWorker#shutdown}.</li>
 * </ul>
 */
public interface Storage {

    // ---- блоки ----
    Map<BlockKey, UUID> loadBlocks();

    /** Пакетная запись/удаление владельцев блоков в одной транзакции. */
    void flushBlocks(Map<BlockKey, UUID> writes, Collection<BlockKey> deletes);

    // ---- trust ----
    Map<UUID, Set<UUID>> loadTrust();

    void addTrust(UUID owner, UUID trusted);

    void removeTrust(UUID owner, UUID trusted);

    // ---- players ----
    /** Заполняет out: uuid → [name(String), playtimeMin(Integer), bypass(Boolean), verified(Boolean)]. */
    void loadPlayers(Map<UUID, Object[]> out);

    /** Пакетная запись игроков. */
    void flushPlayers(Collection<PlayerRow> rows);

    // ---- incidents ----
    Map<UUID, Integer> loadIncidentCounts();

    /**
     * Записывает инцидент. SQLite возвращает autoincrement id синхронно, MySQL — тоже синхронно
     * (если вызван из writer-треда). Возвращает -1 на ошибке.
     */
    int addIncident(UUID culprit, UUID reporter, String reason, long ts, int weight);

    boolean resolveIncident(int id);

    List<String> listIncidents(UUID culprit, int limit);

    /** Сохранить CoreProtect-снимок (evidence) к инциденту. См. CoreProtect-интеграцию. */
    void saveIncidentEvidence(int incidentId, String evidence);

    /** Прочитать evidence инцидента; null — если нет или инцидент не найден. */
    String getIncidentEvidence(int incidentId);

    /**
     * Сколько РАЗНЫХ репортёров пожаловались на {@code culprit} с момента {@code sinceTs}
     * (только credible/{@code weight > 0} и {@code status = 'OPEN'}). Используется для
     * L3-алерта «N независимых жалоб» (anti-report-spam). См. docs/PLAN-anti-report-spam.md.
     */
    int distinctReportersFor(UUID culprit, long sinceTs);

    // ---- pvp kills (audit log) ----
    /**
     * Записывает факт убийства игрока игроком. Ник сохраняется snapshot'ом на момент события —
     * если убийца сменит ник через год, в логе останется старое имя (это правильно для аудита).
     * Возвращает auto-increment id для последующего {@link #updateKillInquiryResult(int, String)}.
     */
    int logKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName,
                long ts, String world, int x, int y, int z);

    /** Обновляет inquiry-результат на ранее записанном убийстве: "ACCEPTED" / "COMPLAINED". */
    void updateKillInquiryResult(int killId, String result);

    /** Последние убийства начиная с {@code sinceTs} (millis), отсортированы новейшие сверху. */
    List<KillRecord> recentKills(long sinceTs, int limit);

    /**
     * Убийства с участием игрока — как убийца, как жертва, или и то и другое.
     * @param asKiller включить убийства, где игрок был killer'ом
     * @param asVictim включить убийства, где игрок был victim'ом
     */
    List<KillRecord> killsByPlayer(UUID player, long sinceTs, int limit, boolean asKiller, boolean asVictim);

    // ---- offline intrusion summary ----
    /**
     * Пакетная запись попыток вторжения к оффлайн-владельцам. UPSERT по
     * {@code (owner,actor,action,world)}: {@code count += delta}, координаты/ts обновляются на последние.
     * Так 30 сломанных блоков от одного игрока = одна строка с count=30, а не 30 строк.
     */
    void recordIntrusions(Collection<IntrusionRow> rows);

    /** Сводка вторжений к владельцу (свежее сверху), не старше {@code sinceTs}. Для показа на входе. */
    List<IntrusionRow> intrusionSummary(UUID owner, long sinceTs, int limit);

    /** Удалить показанные вторжения владельца (вызывается после отрисовки на входе). */
    void clearIntrusions(UUID owner);

    /** TTL-очистка: удалить все вторжения старше {@code olderThanTs}. Возвращает число удалённых. */
    int pruneIntrusions(long olderThanTs);

    // ---- schema versioning ----
    /**
     * Версия схемы в БД (0 = до versioning, fresh install получает {@link SchemaMigrator#TARGET_VERSION}).
     * Используется {@link SchemaMigrator} для применения цепочки миграций при старте.
     */
    int readSchemaVersion();

    /** Записывает текущую версию (вызывается миграциями). */
    void writeSchemaVersion(int version);

    /**
     * Произвольный DDL для миграций (ALTER, CREATE INDEX, и т.д.). НЕ для бизнес-данных —
     * только {@link SchemaMigrator} имеет право вызывать.
     */
    void executeSchemaSql(String sql) throws java.sql.SQLException;

    // ---- meta ----
    /** Человекочитаемое имя бэкенда — для /hv diag. */
    String backendName();

    /** Диагностика пула — для /hv diag. SQLite: «single connection», MySQL: «pool 4/10» и т.п. */
    String poolStats();

    void close();

    /** Иммутабельная запись в pvp_kills для read-методов. */
    final class KillRecord {
        public final int id;
        public final UUID killerUuid;
        public final String killerName;
        public final UUID victimUuid;
        public final String victimName;
        public final long ts;
        public final String world;
        public final int x, y, z;
        /** "ACCEPTED", "COMPLAINED" или null (нет inquiry / не ответили). */
        public final String inquiryResult;

        public KillRecord(int id, UUID killerUuid, String killerName, UUID victimUuid, String victimName,
                          long ts, String world, int x, int y, int z, String inquiryResult) {
            this.id = id;
            this.killerUuid = killerUuid;
            this.killerName = killerName;
            this.victimUuid = victimUuid;
            this.victimName = victimName;
            this.ts = ts;
            this.world = world;
            this.x = x; this.y = y; this.z = z;
            this.inquiryResult = inquiryResult;
        }
    }

    /** Строка оффлайн-сводки вторжений (агрегат по owner+actor+action+world). */
    final class IntrusionRow {
        public final UUID owner;
        public final UUID actor;
        public final String action;        // "сломать", "открыть", ...
        public final String world;
        public final int x, y, z;
        public final int count;
        public final long ts;
        public IntrusionRow(UUID owner, UUID actor, String action, String world,
                            int x, int y, int z, int count, long ts) {
            this.owner = owner; this.actor = actor; this.action = action; this.world = world;
            this.x = x; this.y = y; this.z = z; this.count = count; this.ts = ts;
        }
    }

    /** Снимок строки таблицы players для batch-сброса. Был в Database.PlayerRow — переехал в interface scope. */
    final class PlayerRow {
        public final UUID uuid;
        public final String name;
        public final int playtimeMin;
        public final boolean bypass;
        public final boolean verified;
        /** Последний вход (epoch ms); 0 = неизвестно. Для decay заброшенных построек. */
        public final long lastLoginTs;

        /** Полный конструктор (с last_login_ts). */
        public PlayerRow(UUID uuid, String name, int playtimeMin, boolean bypass, boolean verified, long lastLoginTs) {
            this.uuid = uuid;
            this.name = name;
            this.playtimeMin = playtimeMin;
            this.bypass = bypass;
            this.verified = verified;
            this.lastLoginTs = lastLoginTs;
        }

        /** Legacy-конструктор без last_login_ts (=0). Сохранён для совместимости тестов/мигратора. */
        public PlayerRow(UUID uuid, String name, int playtimeMin, boolean bypass, boolean verified) {
            this(uuid, name, playtimeMin, bypass, verified, 0L);
        }
    }
}
