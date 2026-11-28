package edu.cufe.auction.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchReplayProgressTest {
    @Test
    void onlyAnAdvancingVerifiedPositionRenewsTheStartupWindow() {
        var watch = new RunLauncher.ReplayProgress();
        long start = System.nanoTime();
        watch.observe("BRANCH_VERIFIED 3", start + 20);
        assertEquals(10, watch.idleNanos(start, start + 30));
        for (String row : List.of("BRANCH_VERIFIED 3", "BRANCH_VERIFIED 2", "BRANCH_VERIFIED 0",
                "BRANCH_VERIFIED +4", "BRANCH_VERIFIED 04", "BRANCH_VERIFIED 4 ", "BRANCH_VERIFIED -4",
                "BRANCH_VERIFIED 9223372036854775808", "log BRANCH_VERIFIED 5", "BRANCH_READY")) {
            watch.observe(row, start + 35);
        }
        assertEquals(30, watch.idleNanos(start, start + 50));
        watch.observe("BRANCH_VERIFIED 5", start + 55);
        assertEquals(5, watch.idleNanos(start, start + 60));
    }

    @Test
    void childReporterUsesOnlyTheBoundedCompleteLineProtocol() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        RunLauncher.replayReporter(output).accept(345);
        String valid = bytes.toString(StandardCharsets.UTF_8);
        assertEquals("BRANCH_VERIFIED 345", valid.strip());
        var watch = new RunLauncher.ReplayProgress();
        long start = System.nanoTime();
        String input = "x".repeat(9_000) + valid + "BRANCH_VERIFIED 345";
        watch.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        long afterInvalid = System.nanoTime();
        assertEquals(afterInvalid - start, watch.idleNanos(start, afterInvalid));
        watch.read(new ByteArrayInputStream(bytes.toByteArray()));
        long afterValid = System.nanoTime();
        assertTrue(watch.idleNanos(start, afterValid) <= afterValid - afterInvalid);
    }
}
