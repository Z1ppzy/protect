package ru.haven.storage;

import org.bukkit.plugin.Plugin;
import ru.haven.Settings;

import java.io.File;
import java.util.logging.Logger;

/** Выбирает реализацию {@link Storage} по {@code settings.storageType}. */
public final class StorageFactory {

    private StorageFactory() {}

    public static Storage create(Plugin plugin, Settings settings, Logger logger) throws Exception {
        String type = settings.storageType == null ? "sqlite" : settings.storageType.toLowerCase();
        switch (type) {
            case "mysql":
            case "mariadb":
                logger.info("Storage: MySQL/MariaDB " + settings.mysqlHost + ":" + settings.mysqlPort
                        + "/" + settings.mysqlDatabase + " (pool max=" + settings.mysqlPoolMax + ")");
                return new MysqlStorage(settings, logger);
            case "sqlite":
            default:
                if (!"sqlite".equals(type)) {
                    logger.warning("Неизвестный storage.type='" + type + "', откатываюсь на SQLite.");
                }
                // JDBC4+: drivers register сами через ServiceLoader (META-INF/services/java.sql.Driver);
                // shade-plugin переписывает этот файл вместе с classes, так что и в relocated jar работает.
                File f = new File(plugin.getDataFolder(), "haven.db");
                logger.info("Storage: SQLite (" + f.getAbsolutePath() + ")");
                return new SqliteStorage(f, logger);
        }
    }
}
