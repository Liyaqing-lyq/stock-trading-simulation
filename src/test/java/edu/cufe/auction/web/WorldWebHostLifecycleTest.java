package edu.cufe.auction.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 唯一 writer 生命周期：工厂失败回收、关闭拒绝新任务、writer 内关闭不自 join、并发关闭不挂住。
 */
class WorldWebHostLifecycleTest {

    private static Path dir(String name) throws Exception {
        Path dir = Path.of("target", "web-test", "host", name + "-" + System.nanoTime());
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    @DisplayName("工厂失败：已入队任务立即收到失败，宿主标记关闭，不再半活")
    void factoryFailureDrainsWaiters() throws Exception {
        long start = System.currentTimeMillis();
        WorldWebHost host = new WorldWebHost(() -> {
            throw new IllegalStateException("工厂故意失败");
        });
        AtomicReference<Throwable> seen = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                host.call(() -> null);
            } catch (Throwable failure) {
                seen.set(failure);
            }
        });
        caller.start();
        caller.join(5_000L);
        assertFalse(caller.isAlive(), "工厂失败不得让调用方永久等待");
        assertTrue(seen.get() instanceof IllegalStateException, String.valueOf(seen.get()));
        assertTrue(host.isClosed());
        assertThrows(IllegalStateException.class, () -> host.call(() -> null));
        assertFalse(host.writerAlive());
        assertTrue(System.currentTimeMillis() - start < 5_000);
    }

    @Test
    @DisplayName("正常关闭：任务完成后 writer 结束，关闭后拒绝新任务")
    void closeRejectsNewWork() throws Exception {
        WorldWebHost host = new WorldWebHost(() -> WorldWebScenario.open(dir("close").resolve("w.journal")));
        assertEquals("ok", host.call(() -> "ok"));
        host.close();
        assertFalse(host.writerAlive());
        assertThrows(IllegalStateException.class, () -> host.call(() -> null));
        host.close();
    }

    @Test
    @DisplayName("writer 线程内关闭宿主：不自 join，当前任务正常返回")
    void closeFromWriterDoesNotDeadlock() throws Exception {
        WorldWebHost host = new WorldWebHost(() -> WorldWebScenario.open(dir("self").resolve("w.journal")));
        WorldWebHost[] holder = {host};
        long start = System.currentTimeMillis();
        String result = host.call(() -> {
            holder[0].close();
            return "done";
        });
        assertEquals("done", result);
        assertTrue(System.currentTimeMillis() - start < 5_000);
        host.close();
        assertFalse(host.writerAlive());
    }

    @Test
    @DisplayName("并发关闭与提交：所有调用立即有结论，不挂住，无意外异常")
    void concurrentCloseAndSubmit() throws Exception {
        WorldWebHost host = new WorldWebHost(() -> WorldWebScenario.open(dir("race").resolve("w.journal")));
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    host.call(() -> {
                        Thread.sleep(20L);
                        return null;
                    });
                    completed.incrementAndGet();
                } catch (WorldWebHost.BusyException | IllegalStateException expected) {
                    rejected.incrementAndGet();
                } catch (Throwable other) {
                    unexpected.compareAndSet(null, other);
                }
            });
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        Thread.sleep(10L);
        host.close();
        for (Thread thread : threads) {
            thread.join(5_000L);
        }
        assertFalse(threads.stream().anyMatch(Thread::isAlive), "有调用方卡住");
        assertNull(unexpected.get(), String.valueOf(unexpected.get()));
        assertEquals(8, completed.get() + rejected.get());
        assertFalse(host.writerAlive());
    }

    @Test
    @DisplayName("队列满时明确 BUSY，不无限排队")
    void boundedQueue() throws Exception {
        WorldWebHost host = new WorldWebHost(() -> WorldWebScenario.open(dir("busy").resolve("w.journal")));
        try {
            CountDownLatch blocked = new CountDownLatch(1);
            AtomicInteger busy = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < WorldWebHost.QUEUE_CAPACITY + 8; index++) {
                Thread thread = new Thread(() -> {
                    try {
                        blocked.await();
                        host.call(() -> {
                            Thread.sleep(80L);
                            return null;
                        });
                    } catch (WorldWebHost.BusyException full) {
                        busy.incrementAndGet();
                    } catch (Exception ignored) {
                        // 关闭竞态
                    }
                });
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
            blocked.countDown();
            for (Thread thread : threads) {
                thread.join(10_000L);
            }
            assertTrue(busy.get() > 0, "队列满时必须明确 BUSY");
        } finally {
            host.close();
        }
    }
}
