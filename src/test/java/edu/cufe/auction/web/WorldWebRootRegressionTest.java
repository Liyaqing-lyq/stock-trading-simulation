package edu.cufe.auction.web;

import edu.cufe.auction.app.WorldWebApp;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 独立验收发现的启动失败和满队列关闭回归。 */
class WorldWebRootRegressionTest {
    @Test
    void cashOnlyViewPreservesLargeNumbersWithoutDecisionModule() throws Exception {
        Path work = Files.createDirectories(Path.of("target", "web-test", "root-exact-" + System.nanoTime()));
        var manifest = new edu.cufe.auction.world.RunManifest("exact-web", 1L,
                "cash-world-v1", java.util.Map.of("cash", 1, "scheduler", 1), java.util.Map.of(), "none");
        var openings = java.util.List.of(new edu.cufe.auction.world.CashLedger.OpeningAccount(
                "A", "a", new java.math.BigDecimal("9007199254740993.01"), "fixture"));
        try (var host = new WorldWebHost(() -> new WorldWebHost.Opened(
                new edu.cufe.auction.world.WorldRuntime(work.resolve("world.journal"), manifest, openings),
                manifest.runId()))) {
            var first = host.adminView();
            host.advance(Long.parseLong(first.position()), 9007199254740993L);
            var view = host.adminView();
            assertEquals("9007199254740993", view.timeMillis());
            assertEquals("9007199254740993.01", view.accounts().get(0).cash());
            assertEquals("UNAVAILABLE", view.providerKind());
            assertFalse(view.barrierPending());
            assertFalse(view.valuationsAvailable());
        }
    }

    @Test
    void startupFailureMustNotReportSuccessfulExit() throws Exception {
        Path work = Files.createDirectories(Path.of("target", "web-test", "root-start-" + System.nanoTime()));
        assertThrows(Exception.class, () -> WorldWebApp.main(new String[]{work.toString(),
                work.resolve("missing.keys").toString(), "0"}));
    }

    @Test
    void writerCanCloseWhenItsQueueIsFull() throws Exception {
        Path work = Files.createDirectories(Path.of("target", "web-test", "root-close-" + System.nanoTime()));
        WorldWebHost host = new WorldWebHost(() -> WorldWebScenario.open(work.resolve("world.journal")));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var callers = new ArrayList<Thread>();
        Thread closer = new Thread(() -> {
            try {
                host.call(() -> { entered.countDown(); release.await(); host.close(); return null; });
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        closer.setDaemon(true);
        try {
            closer.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < WorldWebHost.QUEUE_CAPACITY; index++) {
                Thread waiter = new Thread(() -> {
                    try { host.call(() -> null); } catch (IllegalStateException expected) {
                        // 关闭时尚未执行的请求允许明确拒绝。
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
                waiter.setDaemon(true); callers.add(waiter); waiter.start();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (host.queueSize() < WorldWebHost.QUEUE_CAPACITY && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(WorldWebHost.QUEUE_CAPACITY, host.queueSize());
            release.countDown(); closer.join(3000);
            for (Thread waiter : callers) waiter.join(3000);
            assertFalse(closer.isAlive());
            assertTrue(callers.stream().noneMatch(Thread::isAlive));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (host.writerAlive() && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(host.writerAlive(), "writer 自身在满队列下关闭也必须收到终止标记");
        } finally { release.countDown(); host.close(); }
    }
}
