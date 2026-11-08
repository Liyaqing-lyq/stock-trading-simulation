package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static edu.cufe.auction.world.WorldScheduler.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldSchedulerTest {

    @Test
    void phasesRunInDeclaredOrder() {
        WorldScheduler scheduler = new WorldScheduler(7);
        List<String> seen = new ArrayList<>();
        scheduler.schedule("observe", 10, OBSERVATION, () -> seen.add("observe"));
        scheduler.schedule("complete", 10, COMPLETION, () -> seen.add("complete"));
        scheduler.schedule("deliver", 10, DELIVERY, () -> seen.add("deliver"));
        scheduler.schedule("rule", 10, RULES, () -> seen.add("rule"));
        scheduler.runUntil(10);
        assertEquals(List.of("rule", "deliver", "complete", "observe"), seen);
        assertEquals(10, scheduler.nowMillis());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void sameSeedIgnoresInsertionOrder() {
        List<String> forward = order(42, List.of("a", "b", "c", "d", "e", "f"));
        assertEquals(forward, order(42, List.of("f", "e", "d", "c", "b", "a")));
        assertNotEquals(forward, order(43, List.of("a", "b", "c", "d", "e", "f")));
        assertEquals(6, forward.stream().distinct().count());
    }

    @Test
    void deliveryCreatedByCompletionStartsNextMillisecond() {
        WorldScheduler scheduler = new WorldScheduler(1);
        List<Long> seen = new ArrayList<>();
        scheduler.schedule("finish", 10, COMPLETION, () -> {
            scheduler.scheduleDelivery("message", 0, () -> seen.add(scheduler.nowMillis()));
            scheduler.scheduleDelivery("slow", 30, () -> seen.add(scheduler.nowMillis()));
        });
        scheduler.runUntil(10);
        assertTrue(seen.isEmpty());
        scheduler.runUntil(11);
        assertEquals(List.of(11L), seen);
        scheduler.runUntil(40);
        assertEquals(List.of(11L, 40L), seen);
    }

    @Test
    void rejectsPastAndCompletedPhase() {
        WorldScheduler scheduler = new WorldScheduler(1);
        scheduler.schedule("finish", 10, COMPLETION, () -> {
            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.schedule("past-phase", 10, DELIVERY, () -> {}));
            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.schedule("same-phase", 10, COMPLETION, () -> {}));
            scheduler.schedule("later-phase", 10, OBSERVATION, () -> {});
        });
        scheduler.runUntil(20);
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule("past-time", 19, RULES, () -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule("closed-time", 20, OBSERVATION, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> scheduler.runUntil(19));
    }

    @Test
    void pauseRetainsPendingWork() {
        WorldScheduler scheduler = new WorldScheduler(2);
        List<Integer> seen = new ArrayList<>();
        scheduler.schedule("pause", 5, COMPLETION, () -> {
            seen.add(1);
            scheduler.pause();
        });
        scheduler.schedule("later", 10, COMPLETION, () -> seen.add(2));
        scheduler.runUntil(20);
        assertTrue(scheduler.isPaused());
        assertEquals(5, scheduler.nowMillis());
        assertEquals(1, scheduler.pendingCount());
        scheduler.runUntil(20);
        assertEquals(List.of(1), seen);
        scheduler.resume();
        scheduler.runUntil(20);
        assertEquals(List.of(1, 2), seen);
        assertEquals(20, scheduler.nowMillis());
    }

    @Test
    void failurePausesWithoutReplayingSideEffects() {
        WorldScheduler scheduler = new WorldScheduler(3);
        List<String> seen = new ArrayList<>();
        scheduler.schedule("broken", 5, RULES, () -> {
            seen.add("partial");
            throw new IllegalStateException("故障注入");
        });
        scheduler.schedule("later", 5, OBSERVATION, () -> seen.add("later"));
        assertThrows(IllegalStateException.class, () -> scheduler.runUntil(10));
        assertTrue(scheduler.isPaused());
        assertEquals(5, scheduler.nowMillis());
        assertEquals(List.of("partial"), seen);
        scheduler.resume();
        scheduler.runUntil(10);
        assertEquals(List.of("partial", "later"), seen);
    }

    @Test
    void rejectsRecursiveAdvancementAndDuplicateIds() {
        WorldScheduler scheduler = new WorldScheduler(1);
        scheduler.schedule("a", 1, RULES, () ->
                assertThrows(IllegalStateException.class, () -> scheduler.runUntil(3)));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule("a", 2, DELIVERY, () -> {}));
        scheduler.runUntil(1);
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule("a", 2, RULES, () -> {}));
    }

    @Test
    void validatesTimeAndDeliveryOverflow() {
        WorldScheduler scheduler = new WorldScheduler(1);
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule("negative", -1, RULES, () -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.scheduleDelivery("negative-delay", -1, () -> {}));
        scheduler.runUntil(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,
                () -> scheduler.scheduleDelivery("overflow", 0, () -> {}));
    }

    @Test
    void otherThreadsCannotChooseDeliveryTimeOrAdvanceWorld() throws Exception {
        WorldScheduler scheduler = new WorldScheduler(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(executor.submit(() -> {
                assertThrows(IllegalStateException.class,
                        () -> scheduler.scheduleDelivery("foreign", 10, () -> {}));
                assertThrows(IllegalStateException.class, () -> scheduler.runUntil(50));
                return true;
            }).get(2, TimeUnit.SECONDS));
            assertEquals(0, scheduler.pendingCount());
            assertEquals(0, scheduler.nowMillis());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void pausedWorldRejectsNewSchedulesWithoutChangingPendingWork() {
        WorldScheduler scheduler = new WorldScheduler(1);
        scheduler.schedule("pause", 10, RULES, scheduler::pause);
        scheduler.runUntil(20);
        assertThrows(IllegalStateException.class,
                () -> scheduler.schedule("injected", 10, DELIVERY, () -> {}));
        assertThrows(IllegalStateException.class,
                () -> scheduler.scheduleDelivery("injected-next", 1, () -> {}));
        assertEquals(0, scheduler.pendingCount());
        scheduler.resume();
        scheduler.scheduleDelivery("new-after-resume", 0, () -> {});
        scheduler.runUntil(11);
        assertEquals(11, scheduler.nowMillis());
    }

    private List<String> order(long seed, List<String> ids) {
        WorldScheduler scheduler = new WorldScheduler(seed);
        List<String> seen = new ArrayList<>();
        ids.forEach(id -> scheduler.schedule(id, 1, COMPLETION, () -> seen.add(id)));
        scheduler.runUntil(1);
        return seen;
    }
}
