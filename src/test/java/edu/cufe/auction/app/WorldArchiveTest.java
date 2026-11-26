package edu.cufe.auction.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldArchiveTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void unavailableCashModuleStillAllowsReadingAndByteExactCutExport() throws Exception {
        Path source = dir.resolve("original"), archived = dir.resolve("world.journal");
        try (var runtime = WorldWebScenario.open(source).runtime()) {
            runtime.advanceTo(runtime.snapshot().timeMillis() + 100);
        }
        var original = WorldJournalScanner.scan(source).commits();
        try (var out = new WorldJournal(archived, original.get(0).runId())) {
            var first = original.get(0);
            var opening = (ObjectNode) JSON.readTree(first.commandContent());
            ((ObjectNode) opening.at("/manifest/moduleVersions")).put("cash", 99);
            String content = opening.toString();
            var events = first.events().stream().map(e -> "RUN_MANIFEST_V1".equals(e.type())
                    ? new WorldEvent(e.eventId(), e.type(), content) : e).toList();
            out.commit(first.commandId(), content, first.result(), events);
        }
        byte[] firstFrame = Files.readAllBytes(archived);
        try (var out = new WorldJournal(archived, original.get(0).runId())) {
            for (var commit : original.subList(1, original.size()))
                out.commit(commit.commandId(), commit.commandContent(), commit.result(), commit.events());
        }
        Files.write(archived, new byte[]{9, 8, 7}, StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(archived);
        Path keys = dir.resolve("fixture-keys");
        Files.write(keys, java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> "sk-" + "fixture".repeat(3) + i).toList());
        var failure = assertThrows(IOException.class,
                () -> WorldWebApp.main(new String[]{dir.toString(), keys.toString(), "0", "scripted"}));
        assertTrue(failure.getMessage().contains("--archive inspect"));
        assertTrue(failure.getMessage().contains(archived.toString()));
        try (var host = new WorldWebHost(() -> WorldWebScenario.open(archived))) {
            assertThrows(IOException.class, host::awaitOpening);
            assertThrows(Exception.class, () -> host.createBranch("other", 1, BranchOrigin.Kind.OPERATOR_BRANCH, ""));
        }
        assertFalse(Files.exists(dir.resolve("runs")));
        assertArrayEquals(before, Files.readAllBytes(archived));
        String file = archived.toString();
        var seen = WorldArchive.run(new String[]{"--archive", "inspect", file});
        assertEquals(99, seen.at("/manifest/moduleVersions/cash").asInt());
        assertEquals(original.size(), seen.required("position").asLong());
        assertEquals(original.get(0).result(), WorldArchive.run(new String[]{"--archive", "show", file, "1"})
                .at("/commit/result").asText());
        Path copy = dir.resolve("cut.journal");
        var result = WorldArchive.run(new String[]{"--archive", "export", file, "1", copy.toString()});
        assertArrayEquals(firstFrame, Files.readAllBytes(copy));
        assertEquals(WorldJournalScanner.sha256Prefix(copy, firstFrame.length), result.required("sha256").asText());
        assertThrows(IOException.class, () -> WorldArchive.run(new String[]{"--archive", "export", file, "end", copy.toString()}));
        assertArrayEquals(firstFrame, Files.readAllBytes(copy));
    }

    @Test void incompleteTailReportedAndCorruptionNeverPublishesAPartialExport() throws Exception {
        Path source = dir.resolve("source"), target = dir.resolve("copy");
        try (var out = new WorldJournal(source, "example")) {
            out.commit("__open__", "{\"manifest\":{\"runId\":\"example\"}}", "OPENED", List.of());
            out.commit("next", "opaque", "done", List.of());
        }
        byte[] complete = Files.readAllBytes(source);
        Files.write(source, new byte[]{1, 2, 3}, StandardOpenOption.APPEND);
        var input = new String[]{"--archive", "export", source.toString(), "end", target.toString()};
        assertTrue(WorldArchive.run(input).required("tailIncompleteAtScanEnd").asBoolean());
        assertArrayEquals(complete, Files.readAllBytes(target));
        assertEquals(complete.length + 3, Files.size(source));
        Files.delete(target);
        input[3] = "3";
        assertThrows(IOException.class, () -> WorldArchive.run(input));
        assertFalse(Files.exists(target));
        input[3] = "end";
        complete[complete.length - 1] ^= 1;
        Files.write(source, complete);
        assertThrows(WorldJournalScanner.CorruptFrameException.class, () -> WorldArchive.run(input));
        assertFalse(Files.exists(target));
        try (var files = Files.list(dir)) { assertEquals(List.of(source), files.toList()); }
    }
}
