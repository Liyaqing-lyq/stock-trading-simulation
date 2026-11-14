package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独审 D4 回归：真实制造关期批（{@code MANUFACTURING_PERIOD_CLOSE_V1}，工程档案里无治理效应）
 * 里只改 snapshot + 帧 SHA，给任免会议插入提案或伪造治理回报，恢复必须拒绝；
 * 同一条合法路径（真预算会议召集/材料/提案/投票/实施、真实制造关期）必须正常通过。
 */
class GovernanceDerivationBindingTest {

    private static Path dir() throws Exception {
        Path dir = Path.of("target", "fix6-derivation-test");
        Files.createDirectories(dir);
        return dir;
    }

    private static Path built(String name) throws Exception {
        Path file = dir().resolve(name + ".journal");
        Files.deleteIfExists(file);
        GovernanceProposalTamperProbe.build(file);
        return file;
    }

    @Test
    void untamperedJournalKeepsRealMeetings() throws Exception {
        Path file = built("clean");
        try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
            BoardModel.Meeting meeting = world.boardOfCompany("cufe").orElseThrow().board()
                    .meeting("appt-2");
            // 到期自动关会：没有提案与选票，形成明确的 REJECTED 终态（不是“已完成任免”）。
            assertEquals(BoardModel.MeetingStatus.REJECTED, meeting.status());
            assertEquals(List.of("s1", "s2", "s3"), meeting.attendeeSeatIds());
            assertNull(meeting.appointmentProposal(), "真实路径没有任免提案");
        }
    }

    @Test
    void tamperedProposalOrReportInManufacturingBatchIsRejected() throws Exception {
        for (GovernanceProposalTamperProbe.Kind kind : GovernanceProposalTamperProbe.Kind.values()) {
            Path file = built("tamper-" + kind.name().toLowerCase());
            assertTrue(GovernanceProposalTamperProbe.tamper(file, kind),
                    "篡改必须真的改到内容：" + kind);
            Exception rejected = assertThrows(Exception.class, () -> {
                try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
                    world.snapshot();
                }
            }, "真实关期批里的无因治理变更必须被拒绝：" + kind);
            assertNotNull(rejected.getMessage());
            assertTrue(rejected.getMessage().contains("派生")
                            || rejected.getMessage().contains("不一致")
                            || rejected.getMessage().contains("改写"),
                    "拒绝原因必须指向治理派生不一致：" + rejected.getMessage());
        }
    }

    @Test
    void legitimateBudgetMeetingAndManufacturingCloseStillCommit() throws Exception {
        Path file = dir().resolve("legit-governance.journal");
        Files.deleteIfExists(file);
        try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
            world.advanceTo(200L);
            String chair = "s1";
            world.scheduleBoardMeeting("legit-convene", "legit-convene-t", 300L, chair,
                    "legit-budget", 350L, 400L, 500L, List.of("s1", "s2", "s3"), List.of());
            world.scheduleBoardMaterial("legit-material", "legit-material-t", 320L, chair,
                    "legit-budget", "m-1", "doc:legit", 1L);
            world.scheduleBudgetProposal("legit-budget", "legit-budget-t", 330L, chair,
                    "legit-budget", List.of(new BoardModel.BudgetLine(0L,
                            BoardModel.BudgetCategory.PRODUCTION_LABOR, new BigDecimal("100.00"))));
            world.scheduleBoardVote("legit-v1", "legit-v1-t", 450L, "s1", "legit-budget",
                    BoardModel.Vote.YES);
            world.scheduleBoardVote("legit-v2", "legit-v2-t", 451L, "s2", "legit-budget",
                    BoardModel.Vote.YES);
            world.scheduleBoardVote("legit-v3", "legit-v3-t", 452L, "s3", "legit-budget",
                    BoardModel.Vote.YES);
            world.scheduleBudgetImplementation("legit-implement", "legit-implement-t", 550L,
                    "s2", "legit-budget");
            world.advanceTo(560L);
            BoardModel.Meeting meeting = world.boardOfCompany("cufe").orElseThrow().board()
                    .meeting("legit-budget");
            assertEquals(BoardModel.MeetingStatus.IMPLEMENTED, meeting.status(),
                    "合法预算会议必须真实走到已实施");
            assertEquals(3, meeting.ballots().size());
            world.advanceTo(2L * PeriodicModel.PERIOD_MILLIS + 5L);
        }
        try (WorldRuntime reopened = WorldWebScenario.openEngineering(file).runtime()) {
            assertEquals(BoardModel.MeetingStatus.IMPLEMENTED,
                    reopened.boardOfCompany("cufe").orElseThrow().board().meeting("legit-budget")
                            .status(), "合法治理批次必须可按原任务重放");
        }
    }
}
