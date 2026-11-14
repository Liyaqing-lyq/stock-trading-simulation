package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独审 D2 回归：任免会后在非工程批次里自洽重算帧 SHA、给会议追加未真实发生的
 * 到会/回避/状态/治理回报，恢复必须拒绝；未篡改的同一日志必须仍能重开。
 *
 * <p>反例都基于真实原始任务与帧，不引入第二份 journal 信任源；篡改只改 STATE_SNAPSHOT
 * 与帧 SHA（门 6 的既定威胁模型）。</p>
 */
class GovernanceTamperBindingTest {

    private static Path dir() throws Exception {
        Path dir = Path.of("target", "fix5-tamper-test");
        Files.createDirectories(dir);
        return dir;
    }

    private static Path built(String name) throws Exception {
        Path file = dir().resolve(name + ".journal");
        Files.deleteIfExists(file);
        GovernanceTamperProbe.build(file);
        return file;
    }

    private static void tamper(Path file, GovernanceTamperProbe.Kind kind) throws Exception {
        assertTrue(GovernanceTamperProbe.tamper(file, kind), "篡改必须真的改到内容：" + kind);
    }

    @Test
    void untamperedJournalStillReopensWithRealAttendance() throws Exception {
        Path file = built("clean");
        try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
            BoardModel.Meeting meeting = world.boardOfCompany("cufe").orElseThrow()
                    .board().meeting("appt-1");
            assertEquals(java.util.List.of("s1", "s2"), meeting.attendeeSeatIds(),
                    "真实到会只有 s1/s2");
            assertTrue(meeting.recusedSeatIds().isEmpty());
        }
    }

    @Test
    void tamperedGovernanceInClockBatchesIsRejected() throws Exception {
        for (GovernanceTamperProbe.Kind kind : new GovernanceTamperProbe.Kind[]{
                GovernanceTamperProbe.Kind.ATTENDANCE,
                GovernanceTamperProbe.Kind.RECUSAL}) {
            Path file = built("tamper-" + kind.name().toLowerCase());
            tamper(file, kind);
            Exception rejected = assertThrows(Exception.class, () -> {
                try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
                    world.snapshot();
                }
            }, "无真实命令的治理变更必须被拒绝：" + kind);
            assertTrue(rejected.getMessage() != null && !rejected.getMessage().isBlank(),
                    "拒绝必须带原因：" + kind);
        }
    }

    @Test
    void singleFrameTamperIsAlsoRejected() throws Exception {
        Path file = built("single-frame");
        tamper(file, GovernanceTamperProbe.Kind.SINGLE_FRAME);
        assertThrows(Exception.class, () -> {
            try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
                world.snapshot();
            }
        });
    }
}
