package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 重算帧摘要并一致改写所有副本，仍须从原始开局事实拒绝伪造资料。 */
class RoleEvidenceIntegrityTest {
    @TempDir Path dir;
    @Test void consistentForgeryOfOpeningEvidenceCannotClaimNonexistentCash() throws Exception {
        Path original = dir.resolve("original.journal"), forged = dir.resolve("forged.journal");
        String runId, body;
        try (var world = ComposedIndustryScenario.openEvidenceAutonomous(original, false)) {
            runId = world.profile().manifest().runId();
            var docs = world.snapshot().information().orElseThrow().information().documents();
            body = docs.stream().filter(d -> d.docId().equals("role-evidence:mfg-struct:1"))
                    .findFirst().orElseThrow().versions().get(0).body();
        }
        String changed = body.replace("\"cash\":\"9000.00\"", "\"cash\":\"900000.00\"");
        assertNotEquals(body, changed);
        List<String> before = new ArrayList<>(), after = new ArrayList<>();
        var json = new ObjectMapper();
        for (int depth = 0; depth < 7; depth++) {
            before.add(body); after.add(changed);
            body = json.writeValueAsString(body); body = body.substring(1, body.length() - 1);
            changed = json.writeValueAsString(changed); changed = changed.substring(1, changed.length() - 1);
        }
        try (var source = new WorldJournal(original, runId); var target = new WorldJournal(forged, runId)) {
            for (var commit : source.commits()) {
                var events = commit.events().stream().map(e -> new WorldEvent(e.eventId(), e.type(),
                        replace(e.payload(), before, after))).toList();
                target.commit(commit.commandId(), replace(commit.commandContent(), before, after), commit.result(), events);
            }
        }
        try (var frames = new WorldJournal(forged, runId)) {
            assertFalse(frames.commits().isEmpty(), "帧格式与摘要必须合法，不能靠坏哈希通过负例");
        }
        var failure = assertThrows(java.io.IOException.class, () -> {
            try (var ignored = ComposedIndustryScenario.openEvidenceAutonomous(forged, false)) { }
        });
        assertTrue(failure.getMessage().contains("排程帧"), failure.toString());
    }
    private static String replace(String text, List<String> before, List<String> after) {
        for (int i = 0; i < before.size(); i++) text = text.replace(before.get(i), after.get(i));
        return text;
    }
}
