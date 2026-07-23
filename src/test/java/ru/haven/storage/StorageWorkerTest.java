package ru.haven.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Юнит-тесты для {@link StorageWorker}: задачи реально выполняются в worker-треде,
 * запросы во время shutdown не отбрасываются, очередь дренируется до завершения.
 */
class StorageWorkerTest {

    @Test
    void tasksExecuteInOrder() throws InterruptedException {
        StorageWorker w = new StorageWorker(null);
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger ctr = new AtomicInteger();
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            int n = i;
            assertTrue(w.submit(() -> {
                synchronized (order) { order.append(n).append(','); }
                ctr.incrementAndGet();
                latch.countDown();
            }));
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "все 100 задач должны выполниться");
        assertEquals(100, ctr.get());
        // Порядок — FIFO
        String[] parts = order.toString().split(",");
        for (int i = 0; i < 100; i++) assertEquals(String.valueOf(i), parts[i]);
        w.shutdown(Duration.ofSeconds(2));
    }

    @Test
    void shutdownDrainsQueue() {
        StorageWorker w = new StorageWorker(null);
        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < 50; i++) {
            w.submit(() -> {
                try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                done.incrementAndGet();
            });
        }
        w.shutdown(Duration.ofSeconds(5));
        assertEquals(50, done.get(), "shutdown должен дождаться дренирования всех 50 задач");
    }

    @Test
    void shutdownRejectsNewSubmits() {
        StorageWorker w = new StorageWorker(null);
        w.shutdown(Duration.ofSeconds(1));
        assertFalse(w.submit(() -> {}), "после shutdown enqueue должен возвращать false");
        assertTrue(w.droppedTasks() >= 1);
    }

    @Test
    void exceptionInTaskDoesNotKillWorker() throws InterruptedException {
        StorageWorker w = new StorageWorker(null);
        w.submit(() -> { throw new RuntimeException("boom"); });
        CountDownLatch survived = new CountDownLatch(1);
        w.submit(survived::countDown);
        assertTrue(survived.await(2, TimeUnit.SECONDS), "worker должен пережить исключение");
        assertNotNull(w.lastError());
        assertTrue(w.lastError().contains("boom"));
        w.shutdown(Duration.ofSeconds(1));
    }

    @Test
    void degradedAfterConsecutiveFailures() throws InterruptedException {
        StorageWorker w = new StorageWorker(null);
        java.util.concurrent.atomic.AtomicBoolean alerted = new java.util.concurrent.atomic.AtomicBoolean();
        w.setOnDegraded(() -> alerted.set(true));

        // FAILURE_THRESHOLD подряд-фейлов
        for (int i = 0; i < StorageWorker.FAILURE_THRESHOLD; i++) {
            w.submit(() -> { throw new RuntimeException("db down"); });
        }
        // Дать worker'у их выполнить.
        long deadline = System.currentTimeMillis() + 2000;
        while (!w.isDegraded() && System.currentTimeMillis() < deadline) Thread.sleep(10);

        assertTrue(w.isDegraded(), "после " + StorageWorker.FAILURE_THRESHOLD + " подряд-фейлов должен быть DEGRADED");
        assertFalse(w.isAccepting(), "в DEGRADED новые задачи не принимаются");
        assertTrue(alerted.get(), "onDegraded callback должен вызваться");
        assertEquals(StorageWorker.FAILURE_THRESHOLD, w.failedFlushes());
        // submit после degraded → дропается
        assertFalse(w.submit(() -> {}));
        assertTrue(w.droppedTasks() >= 1);
        w.shutdown(Duration.ofSeconds(1));
    }

    @Test
    void successResetsConsecutiveFailureCounter() throws InterruptedException {
        StorageWorker w = new StorageWorker(null);
        // 4 фейла, потом 1 успех — degraded не должен случиться (счётчик сброшен).
        for (int i = 0; i < StorageWorker.FAILURE_THRESHOLD - 1; i++) {
            w.submit(() -> { throw new RuntimeException("flaky"); });
        }
        java.util.concurrent.CountDownLatch okDone = new java.util.concurrent.CountDownLatch(1);
        w.submit(okDone::countDown);
        assertTrue(okDone.await(2, TimeUnit.SECONDS));
        // Ещё 4 фейла — всё равно меньше threshold подряд после сброса.
        for (int i = 0; i < StorageWorker.FAILURE_THRESHOLD - 1; i++) {
            w.submit(() -> { throw new RuntimeException("flaky again"); });
        }
        java.util.concurrent.CountDownLatch okDone2 = new java.util.concurrent.CountDownLatch(1);
        w.submit(okDone2::countDown);
        assertTrue(okDone2.await(2, TimeUnit.SECONDS));
        assertFalse(w.isDegraded(), "удачные flush'и между фейлами не должны вести в DEGRADED");
        w.shutdown(Duration.ofSeconds(1));
    }

    @Test
    void metricsReportProgress() throws InterruptedException {
        StorageWorker w = new StorageWorker(null);
        CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            w.submit(() -> {
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                latch.countDown();
            });
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(3, w.completedFlushes());
        assertTrue(w.lastFlushMillis() >= 0);
        w.shutdown(Duration.ofSeconds(1));
    }
}
