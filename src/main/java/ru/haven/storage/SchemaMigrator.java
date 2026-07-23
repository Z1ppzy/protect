package ru.haven.storage;

import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Цепочка миграций схемы БД (Flyway-style, но минимальный).
 *
 * <p>Каждая реализация {@link Storage} вызывает {@link #migrate(Storage, String, Logger)} в конце
 * своего {@code init()}. Migrator читает текущую версию из {@code _haven_schema_version},
 * применяет все pending миграции по очереди, фиксирует версию после каждой.</p>
 *
 * <h3>Как добавить новую миграцию (например v2)</h3>
 * <ol>
 *   <li>Инкрементить {@link #TARGET_VERSION} до 2.</li>
 *   <li>Добавить {@code if (current &lt; 2) { v2_yourChange(db, dialect, log); db.writeSchemaVersion(2); }}.</li>
 *   <li>Реализовать {@code v2_yourChange} — обычно несколько {@code db.executeSchemaSql("ALTER TABLE …")}.
 *       Если SQL разный для SQLite и MySQL — switch по {@code dialect}.</li>
 *   <li>Минор-bump версии плагина.</li>
 * </ol>
 *
 * <p>Существующие пользователи (которые установили плагин до versioning) после первого запуска
 * новой версии получат {@code v=1} — таблицы у них уже есть (созданы через
 * {@code CREATE TABLE IF NOT EXISTS}), миграция v0→v1 — no-op для них.</p>
 */
public final class SchemaMigrator {

    /** Текущая «правильная» версия схемы. Инкрементить при добавлении ALTER-миграции. */
    public static final int TARGET_VERSION = 4;

    public static final String DIALECT_SQLITE = "sqlite";
    public static final String DIALECT_MYSQL = "mysql";

    private SchemaMigrator() {}

    public static void migrate(Storage db, String dialect, Logger log) throws SQLException {
        int current = db.readSchemaVersion();
        if (current >= TARGET_VERSION) {
            log.fine("Storage schema version " + current + " (target=" + TARGET_VERSION + ") — no migrations needed.");
            return;
        }

        log.info("Storage schema: " + current + " → " + TARGET_VERSION + " (running migrations...)");

        if (current < 1) {
            // v0 → v1: initial schema. Сами таблицы созданы в init() через CREATE IF NOT EXISTS —
            // эта миграция фиксирует версию для baseline.
            log.info("Migration v0→v1: marking initial schema as version 1.");
            db.writeSchemaVersion(1);
            current = 1;
        }
        if (current < 2) {
            // v1 → v2: добавлена таблица pvp_kills (создаётся в init() через CREATE IF NOT EXISTS
            // и в новых, и в существующих БД). Фиксируем версию.
            log.info("Migration v1→v2: pvp_kills table added (kill audit log).");
            db.writeSchemaVersion(2);
            current = 2;
        }
        if (current < 3) {
            // v2 → v3: players.last_login_ts (для decay заброшенных построек). Колонка добавляется
            // в init() через CREATE-with-column (новые БД) и ALTER-try-catch (существующие).
            log.info("Migration v2→v3: players.last_login_ts column added (abandoned-build decay).");
            db.writeSchemaVersion(3);
            current = 3;
        }
        if (current < 4) {
            // v3 → v4: intrusion_events (оффлайн-сводка) + incidents.evidence (CoreProtect-снимок).
            // Обе создаются/добавляются в init() (CREATE IF NOT EXISTS / ALTER-try-catch). Фиксируем версию.
            log.info("Migration v3→v4: intrusion_events + incidents.evidence added.");
            db.writeSchemaVersion(4);
            current = 4;
        }
        // Future migrations:
        // if (current < 5) { v5_uuidsToBinary(db, dialect, log); db.writeSchemaVersion(5); current = 5; }

        log.info("Storage schema migrated to version " + current);
    }
}
