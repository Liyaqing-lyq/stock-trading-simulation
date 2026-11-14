package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 周期采样/会议/窗口的最小真实探针：先证明写入链真的会跑。 */
class EngineeringPeriodicWorldWriteTest {

    private static final long DAY = PeriodicModel.PERIOD_MILLIS / 30L;

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-periodic-write-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static WorldRuntime open(Path file) throws Exception {
        return WorldWebScenario.engineeringRuntime(file, WorldWebScenario.engineeringConfig());
    }

    @Test
    void captureCreatesRealSnapshotAndCharterMeeting() throws Exception {
        Path file = journal("capture");
        WorldRuntime world = open(file);
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
        PeriodicModel.CompanyPeriods periods = world.periodicOf(WorldWebScenario.ENGINE_A)
                .orElseThrow();
        assertEquals(1, periods.snapshots().size());
        PeriodicModel.Snapshot snapshot = periods.snapshots().get(0);
        assertEquals(PeriodicModel.PERIOD_MILLIS, snapshot.capturedAt());
        assertEquals("periodic-meeting:" + WorldWebScenario.ENGINE_A + ":1",
                snapshot.meetingId());
        assertTrue(snapshot.evidenceRefs().contains(snapshot.materialSourceRef()));
        assertTrue(snapshot.statementDigest().length() == 64);
        BoardModel.Meeting meeting = world.boardOfCompany(WorldWebScenario.ENGINE_A)
                .orElseThrow().board().meeting(snapshot.meetingId());
        assertEquals(BoardModel.CHARTER_AUTO, meeting.convenerSeatId());
        assertEquals(snapshot.capturedAt() + PeriodicWorldEngine.MATERIAL_LEAD_MILLIS,
                meeting.opensAt());
        assertFalse(world.snapshot().engineering().orElseThrow().reports().isEmpty());
        world.close();
    }
}
