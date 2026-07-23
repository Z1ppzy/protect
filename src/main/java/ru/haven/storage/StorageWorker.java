package ru.haven.storage;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Один dedicated writer-тред + неблокирующая очередь.
 *
 * <p>Архитектура (CoreProtect-style consumer): main-тред {@link #submit} только enqueue —
 * O(1), без I/O. Все JDBC-операции (одна транзакция) выполняются в фоновом потоке.
 * Это даёт: (а) отсутствие блокировок main-треда на коммитах, (б) исчезновение connection-contention
 * в multi-thread пуле, (в) предсказуемую очерёдность записей (FIFO).</p>
 *
 * <p>Shutdown: {@link #shutdown(Duration)} закрывает приём и ждёт дрейн с таймаутом —
 * на onDisable плагина пытаемся не потерять последние мутации, но не виснем сервер.</p>
 *
 * <p><b>Health-check / fail-loud:</b> если подряд {@link #FAILURE_THRESHOLD} задач упало с
 * исключением (БД read-only, диск полный, MySQL отвалился) — writer переходит в
 * {@link #isDegraded() DEGRADED}-состояние, перестаёт принимать новые задачи и зовёт
 * {@link #setOnDegraded(Runnable) onDegraded-callback}. Лучше шумно отказать, чем тихо терять
 * данные в RAM, которые потом исчезнут при рестарте.</p>
 *
 * <p>Метрики (для {@code /hv diag}): {@link #queueSize}, {@link #lastFlushMillis},
 * {@link #completedFlushes}, {@link #isDegraded}.</p>
 */
public final class StorageWorker {

    /** Сколько подряд-фейлов до перевода в DEGRADED. */
    public static final int FAILURE_THRESHOLD = 5;

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private final Logger logger;
    private volatile boolean accepting = true;
    private volatile boolean stopped = false;
    private volatile boolean degraded = false;

    private final AtomicLong lastFlushNanos = new AtomicLong(0);
    private final AtomicLong completed = new AtomicLong(0);
    private final AtomicLong dropped = new AtomicLong(0);
    private final AtomicLong failed = new AtomicLong(0);
    private final AtomicReference<String> lastError = new AtomicReference<>(null);
    /** Меняется только из writer-треда — не нужен atomic. */
    private int consecutiveFailures = 0;

    private volatile Runnable onDegraded = null;

    public StorageWorker(Logger logger) {
        this.logger = logger != null ? logger : Logger.getLogger("Haven");
        this.thread = new Thread(this::loop, "Haven-Storage-Writer");
        // НЕ daemon: при kill -9 JVM упадёт и так, а тут хотим успеть дослить нашу очередь.
        this.thread.setDaemon(false);
        this.thread.start();
    }

    private void loop() {
        while (true) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            if (task == STOP_SENTINEL) break;
            long t0 = System.nanoTime();
            try {
                task.run();
                completed.incrementAndGet();
                consecutiveFailures = 0; // успешный flush сбрасывает счётчик
            } catch (Throwable t) {
                failed.incrementAndGet();
                consecutiveFailures++;
                lastError.set(t.getClass().getSimpleName() + ": " + t.getMessage());
                logger.log(Level.SEVERE, "Haven storage-writer task failed (consecutive=" + consecutiveFailures + ")", t);
                if (consecutiveFailures >= FAILURE_THRESHOLD && !degraded) {
                    triggerDegraded();
                }
            } finally {
                lastFlushNanos.set(System.nanoTime() - t0);
            }
        }
        stopped = true;
    }

    /**
     * Перевод в DEGRADED: перестаём принимать новые задачи (in-memory state продолжает работать,
     * но новые мутации не уходят в очередь — это сигнал админу что что-то очень не так с БД).
     * Очередь, что уже накопилась, всё ещё пытается выполняться — вдруг БД оживёт.
     */
    private void triggerDegraded() {
        degraded = true;
        accepting = false;
        logger.severe("================================================================");
        logger.severe(" HAVEN STORAGE DEGRADED: " + FAILURE_THRESHOLD + " подряд-фейлов записи в БД.");
        logger.severe(" Новые мутации НЕ принимаются (защита от потери данных в RAM).");
        logger.severe(" Последняя ошибка: " + lastError.get());
        logger.severe(" Проверь БД (диск/коннект/permissions) и перезапусти сервер.");
        logger.severe("================================================================");
        Runnable cb = onDegraded;
        if (cb != null) {
            try { cb.run(); } catch (Throwable t) {
                logger.log(Level.WARNING, "onDegraded callback failed", t);
            }
        }
    }

    /** Enqueue из любого треда. O(1), без I/O. Возвращает true если принято, false если worker остановлен/degraded. */
    public boolean submit(Runnable task) {
        if (!accepting) { dropped.incrementAndGet(); return false; }
        queue.offer(task);
        return true;
    }

    /**
     * Корректная остановка: больше не принимаем задачи, ждём дрейна с таймаутом.
     * Если очередь не успела сдренироваться — логируем потерянные задачи (но они остаются
     * в очереди и runtime придушит их при JVM shutdown).
     */
    public void shutdown(Duration timeout) {
        accepting = false;
        queue.offer(STOP_SENTINEL); // разбудит take() и завершит loop после дрейна
        try {
            thread.join(timeout.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (!stopped) {
            logger.warning("Haven storage-writer не остановился за " + timeout.toMillis() + "мс; "
                    + "в очереди осталось задач: " + queue.size() + " (некоторые мутации могут быть потеряны)");
            thread.interrupt();
        } else if (!queue.isEmpty()) {
            // stop_sentinel пришёл раньше дрейна (не должно случаться, но защита).
            logger.warning("Haven storage-writer остановлен, но в очереди осталось " + queue.size() + " задач.");
        }
    }

    /**
     * One-shot callback, выполняется в writer-треде ровно один раз при переходе в DEGRADED.
     * Плагин обычно подписывается чтобы крикнуть в админ-чат. Лямбда не должна бросать.
     */
    public void setOnDegraded(Runnable cb) { this.onDegraded = cb; }

    // ---- метрики для /hv diag ----
    public int queueSize() { return queue.size(); }
    public long completedFlushes() { return completed.get(); }
    public long failedFlushes() { return failed.get(); }
    public long droppedTasks() { return dropped.get(); }
    public long lastFlushMillis() { return lastFlushNanos.get() / 1_000_000L; }
    public String lastError() { return lastError.get(); }
    public boolean isAccepting() { return accepting; }
    public boolean isDegraded() { return degraded; }

    private static final Runnable STOP_SENTINEL = () -> {};
}
