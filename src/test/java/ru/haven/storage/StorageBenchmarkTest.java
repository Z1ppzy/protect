package ru.haven.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.haven.Settings;
import ru.haven.core.DataStore;
import ru.haven.util.BlockKey;

import java.io.File;
import java.time.Duration;
import java.util.UUID;

/**
 * Синтетический бенчмарк: 10 000 placement events.
 *
 * <p>Измеряет время main-треда (только enqueue в worker) — это то, что реально влияет на TPS.
 * Сам flush идёт в фоне, измеряется отдельно ({@code worker.lastFlushMillis()}).</p>
 *
 * <p>Конкретные числа смотри в выводе теста (он печатает в stdout, не assert'ит — не падаем
 * на медленных CI; для смысловой регрессии числа должны быть <50ms total на main-треде).</p>
 */
class StorageBenchmarkTest {

    private SqliteStorage db;
    private StorageWorker worker;
    private DataStore store;
    private File dbFile;

    @BeforeEach
    void setup() throws Exception {
        Class.forName("org.sqlite.JDBC");
        dbFile = File.createTempFile("haven-bench", ".db");
        dbFile.deleteOnExit();
        db = new SqliteStorage(dbFile);
        worker = new StorageWorker(null);
        Settings settings = new Settings();
        settings.load(new YamlConfiguration());
        store = new DataStore(null, db, worker, settings);
        store.loadAll();
    }

    @AfterEach
    void teardown() {
        if (worker != null) worker.shutdown(Duration.ofSeconds(10));
        if (db != null) db.close();
        if (dbFile != null) dbFile.delete();
    }

    @Test
    void placement10kThroughput() throws InterruptedException {
        final int N = 10_000;
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        // ---- Прогрев (JIT, page cache) ----
        for (int i = 0; i < 200; i++) {
            store.setOwner(new BlockKey(world, -i, 0, -i), owner);
        }
        store.drainToDb();

        // ---- Бенчмарк: главное время — enqueue на main ----
        long mainT0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            store.setOwner(new BlockKey(world, i & 0xFFFF, (i >> 16) & 0xFF, i & 0xFF), owner);
        }
        // Один периодический "tick": сбрасываем все накопленные блоки одной транзакцией.
        store.drainToDb();
        long mainElapsedMs = (System.nanoTime() - mainT0) / 1_000_000L;

        // ---- Подождём, пока writer-тред допишет всё в БД ----
        long bgT0 = System.nanoTime();
        // Простое ожидание дренажа очереди. В проде это асинхронно.
        while (worker.queueSize() > 0) Thread.sleep(5);
        long bgElapsedMs = (System.nanoTime() - bgT0) / 1_000_000L;

        long throughput = (long) (N * 1000.0 / Math.max(1, mainElapsedMs));
        System.out.println("=== BENCHMARK: " + N + " placement events ===");
        System.out.println("  main-thread time (enqueue + ConcurrentHashMap puts): " + mainElapsedMs + "ms");
        System.out.println("  background flush latency (worker drained):           " + bgElapsedMs + "ms");
        System.out.println("  effective main-thread throughput:                    " + throughput + " events/sec");
        System.out.println("  last flush wall-time (from worker metrics):          " + worker.lastFlushMillis() + "ms");
        System.out.println("  total worker flushes completed:                      " + worker.completedFlushes());
    }
}
