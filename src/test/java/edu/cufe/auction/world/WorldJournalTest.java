package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldJournalTest {
    @TempDir Path directory;

    @Test
    void duplicateCommandReturnsOriginalResultWithoutAnotherCommit() throws Exception {
        try (WorldJournal journal = open()) {
            WorldCommit first = journal.commit("pay", "A->B:10", "paid", List.of(event("e1")));
            WorldCommit again = journal.commit("pay", "A->B:10", "different result", List.of(event("e2")));
            assertEquals(first, again);
            assertEquals("paid", again.result());
            assertEquals(1, journal.commits().size());
            assertThrows(IllegalArgumentException.class,
                    () -> journal.commit("pay", "A->B:20", "paid", List.of(event("e3"))));
            assertEquals(1, journal.commits().size());
        }
    }

    @Test
    void restartRestoresResultsAndEventsThenAppends() throws Exception {
        try (WorldJournal journal = open()) {
            journal.commit("c1", "one", "ok1", List.of(event("e1"), event("e2")));
        }
        try (WorldJournal journal = open()) {
            assertEquals("ok1", journal.commits().get(0).result());
            assertEquals(List.of(event("e1"), event("e2")), journal.commits().get(0).events());
            assertEquals(2, journal.commit("c2", "two", "ok2", List.of(event("e3"))).position());
        }
        try (WorldJournal journal = open()) {
            assertEquals(List.of("c1", "c2"), journal.commits().stream().map(WorldCommit::commandId).toList());
        }
    }

    @Test
    void incompleteLastFrameRecoversOnlyEarlierCommittedBatch() throws Exception {
        long firstLength;
        try (WorldJournal journal = open()) {
            journal.commit("c1", "one", "ok1", List.of(event("e1")));
            firstLength = Files.size(file());
            journal.commit("c2", "two", "ok2", List.of(event("e2")));
        }
        byte[] bytes = Files.readAllBytes(file());
        Files.write(file(), java.util.Arrays.copyOf(bytes, bytes.length - 10));
        try (WorldJournal journal = open()) {
            assertEquals(1, journal.commits().size());
            assertEquals(firstLength, Files.size(file()));
            assertEquals(2, journal.commit("c2", "two", "ok2", List.of(event("e2"))).position());
        }
    }

    @Test
    void completeCorruptFrameFailsInsteadOfSilentlyLosingHistory() throws Exception {
        try (WorldJournal journal = open()) {
            journal.commit("c1", "one", "ok", List.of(event("e1")));
        }
        byte[] bytes = Files.readAllBytes(file());
        bytes[bytes.length - 1] ^= 1;
        Files.write(file(), bytes);
        assertThrows(IOException.class, this::open);
        assertArrayEquals(bytes, Files.readAllBytes(file()));
    }

    @Test
    void rejectsOtherRunAndSecondWriter() throws Exception {
        try (WorldJournal journal = open()) {
            journal.commit("c1", "one", "ok", List.of(event("e1")));
            assertThrows(IOException.class, this::open);
        }
        assertThrows(IOException.class, () -> new WorldJournal(file(), "another-run"));
        try (WorldJournal journal = open()) {
            assertEquals(1, journal.commits().size());
        }
    }

    @Test
    void immutableBatchesRejectDuplicateEventIds() throws Exception {
        List<WorldEvent> events = new ArrayList<>(List.of(event("e1")));
        try (WorldJournal journal = open()) {
            WorldCommit first = journal.commit("c1", "one", "ok", events);
            events.clear();
            assertEquals(1, first.events().size());
            assertThrows(UnsupportedOperationException.class, () -> first.events().clear());
            assertThrows(UnsupportedOperationException.class, () -> journal.commits().clear());
            assertThrows(IllegalArgumentException.class,
                    () -> journal.commit("c2", "two", "ok", List.of(event("e1"))));
            assertThrows(IllegalArgumentException.class,
                    () -> journal.commit("c3", "three", "ok", List.of(event("e2"), event("e2"))));
            assertEquals(1, journal.commits().size());
        }
    }

    @Test
    void closedJournalCannotAcknowledgeAnotherCommit() throws Exception {
        WorldJournal journal = open();
        journal.commit("c1", "one", "ok", List.of(event("e1")));
        journal.close();
        assertThrows(IOException.class,
                () -> journal.commit("c2", "two", "ok", List.of(event("e2"))));
        try (WorldJournal restored = open()) {
            assertEquals(1, restored.commits().size());
        }
    }

    @Test
    void oversizedFrameDoesNotDamageExistingCommits() throws Exception {
        try (WorldJournal journal = open()) {
            journal.commit("c1", "one", "ok", List.of(event("e1")));
            assertThrows(IllegalArgumentException.class,
                    () -> journal.commit("c2", "two", "x".repeat(16 * 1024 * 1024), List.of()));
        }
        try (WorldJournal restored = open()) {
            assertEquals(1, restored.commits().size());
        }
    }

    @Test
    void interruptedOpenPreservesCancellationAndDoesNotCreateFile() {
        try {
            Thread.currentThread().interrupt();
            IOException failure = assertThrows(IOException.class, this::open);
            assertNotNull(failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(file()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void incompleteHeaderCanBeRemovedButDamagedLengthCannot() throws Exception {
        long confirmed;
        try (WorldJournal journal = open()) {
            journal.commit("c1", "one", "ok", List.of(event("e1")));
            confirmed = Files.size(file());
        }
        Files.write(file(), new byte[]{0, 0, 1}, java.nio.file.StandardOpenOption.APPEND);
        try (WorldJournal journal = open()) {
            assertEquals(confirmed, Files.size(file()));
            assertEquals(1, journal.commits().size());
        }
        byte[] bytes = Files.readAllBytes(file());
        bytes[0] ^= 1;
        Files.write(file(), bytes);
        assertThrows(IOException.class, this::open);
        assertArrayEquals(bytes, Files.readAllBytes(file()));
    }

    private Path file() {
        return directory.resolve("run.journal");
    }

    private WorldJournal open() throws IOException {
        return new WorldJournal(file(), "run-1");
    }

    private WorldEvent event(String id) {
        return new WorldEvent(id, "CASH_TRANSFERRED", "{\"amount\":\"10.00\"}");
    }
}
