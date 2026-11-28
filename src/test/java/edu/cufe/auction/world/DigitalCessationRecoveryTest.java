package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import edu.cufe.auction.world.composed.DigitalCessationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 无业务时钟不能产生、删除或改写合法停业回执；重算帧和状态摘要后仍须拒绝。 */
class DigitalCessationRecoveryTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    @Test void zeroBusinessClockCannotCreateCessationInOldOrNewArchive() throws Exception {
        check(false, false, List.of("add"));
        check(true, false, List.of("add"));
    }

    @Test void zeroBusinessClockCannotRemoveOrReplaceExecutedResolution() throws Exception {
        check(true, true, List.of("remove", "replace"));
    }

    private void check(boolean enabled, boolean closeFirst, List<String> changes) throws Exception {
        Path source = dir.resolve("source-" + enabled + "-" + closeFirst);
        String before;
        String after;
        String run;
        long position;
        try (var w = open(source, enabled)) {
            if (closeFirst) DigitalCessationTest.windDownWithOldContracts(w);
            before = w.checkpoint(w.snapshot().position()).stateJson();
            w.advanceTo(w.snapshot().timeMillis() + 1);
            position = w.snapshot().position();
            after = w.checkpoint(position).stateJson();
            run = w.profile().manifest().runId();
        }
        for (String change : changes) {
            ObjectNode altered = (ObjectNode) JSON.readTree(after);
            ObjectNode company = (ObjectNode) altered.at("/companyOperations/digital");
            if (change.equals("remove")) assertNotNull(company.remove("cessation"));
            else if (change.equals("replace")) ((ObjectNode) company.get("cessation")).put("meetingId", "different-meeting");
            else company.putObject("cessation").put("meetingId", "no-such-meeting").set("timeMillis", altered.get("timeMillis"));
            String delta = StateDeltaCodec.encode(run, position, before, JSON.writeValueAsString(altered));
            assertEquals(altered.at("/companyOperations/digital/cessation"),
                    JSON.readTree(StateDeltaCodec.apply(run, position, before, delta)).at("/companyOperations/digital/cessation"));
            Path copy = dir.resolve("changed-" + enabled + "-" + change);
            try (var input = new WorldJournal(source, run); var output = new WorldJournal(copy, run)) {
                for (var frame : input.commits()) {
                    var events = new ArrayList<>(frame.events());
                    if (frame.position() == position) {
                        assertTrue(frame.commandId().startsWith("clock:"));
                        assertTrue(events.removeIf(event -> StateDeltaCodec.TYPE.equals(event.type())));
                        var original = frame.events().stream().filter(event -> StateDeltaCodec.TYPE.equals(event.type())).findFirst().orElseThrow();
                        events.add(new WorldEvent(original.eventId(), original.type(), delta));
                    }
                    output.commit(frame.commandId(), frame.commandContent(), frame.result(), events);
                }
            }
            byte[] bytes = Files.readAllBytes(copy);
            var error = assertThrows(IOException.class, () -> open(copy, enabled));
            assertTrue(error.getMessage().contains("重算"), error.toString());
            assertArrayEquals(bytes, Files.readAllBytes(copy));
        }
    }

    private WorldRuntime open(Path path, boolean enabled) throws Exception {
        return enabled ? ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(path)
                : ComposedIndustryScenario.openMeetingTenCompanyStudy(path);
    }
}

