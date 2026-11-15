package edu.cufe.auction.world.pharmaaudit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.ResearchDecisionTools;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedPharmaScenario;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 独审第三组：非董事经理 kate 与 {@code COMPANY_OPERATING} 受众。
 *
 * <p>研发结果只允许「本公司董事」或「现任且具备 READ_COMPANY_LEDGER 的经理」读到；董事会专用
 * 材料仍是 {@code BOARD} 受众。这里主动换身份绕过：外公司董事、撤职后的历史位置、模型输入
 * 投影。断言口径全部来自真实运行状态（阅读单、经历、角色输入），不 mock。</p>
 */
class PharmaOperatingAudienceTest {

    private static final String PHARMA = ComposedPharmaScenario.PHARMA;
    private static final String RESEARCH_DOC =
            "research:pharma:p1:RESEARCH:SUCCESS";
    private static final long DAY = PharmaAuditHarness.DAY;

    @TempDir
    Path dir;

    @Test
    void researchResultIsOperatingAudienceAndOnlyOwnManagerOrDirectorCanRead() throws Exception {
        Path journal = dir.resolve("audience.journal");
        long docsAt;
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            docsAt = completeFirstStage(world);
            var info = world.snapshot().information().orElseThrow().information();
            var doc = info.documents().stream()
                    .filter(each -> each.docId().equals(RESEARCH_DOC)).findFirst().orElse(null);
            assertNotNull(doc, "研发结果必须真实发布：" + RESEARCH_DOC);
            assertEquals(InformationModel.AudienceKind.COMPANY_OPERATING,
                    doc.audience().kind(), "研发结果必须用新经营受众");
            assertEquals(PHARMA, doc.audience().partyId());

            long before = world.snapshot().timeMillis();
            assertFalse(InformationModel.canCite(info, "frank", RESEARCH_DOC, 1, before),
                    "未完成阅读前不得引用正文");

            // 外公司董事 alice：既不是 pharma 董事，也不是 pharma 经理。
            long at = docsAt + 1_000L;
            world.scheduleStartReading("x-alice", "x-alice-task", at, "alice", RESEARCH_DOC, 1L);
            world.advanceTo(at + 1_000L);
            assertFalse(InformationModel.canCite(world.snapshot().information().orElseThrow()
                    .information(), "alice", RESEARCH_DOC, 1, at + 1_000L),
                    "外公司董事不得读到研发结果");

            // 本公司董事 frank：真实席位即可读。
            world.scheduleStartReading("x-frank", "x-frank-task", at + 2_000L, "frank",
                    RESEARCH_DOC, 1L);
            // 非董事经理 kate：现任经理 + READ_COMPANY_LEDGER 即可读。
            world.scheduleStartReading("x-kate", "x-kate-task", at + 2_000L, "kate",
                    RESEARCH_DOC, 1L);
            world.advanceTo(at + 4_000L);
            var after = world.snapshot().information().orElseThrow().information();
            assertTrue(InformationModel.canCite(after, "frank", RESEARCH_DOC, 1, at + 4_000L),
                    "本公司董事必须能读到研发结果");
            assertTrue(InformationModel.canCite(after, "kate", RESEARCH_DOC, 1, at + 4_000L),
                    "现任有账本权限的经理必须能读到研发结果");
            assertFalse(InformationModel.canCite(after, "alice", RESEARCH_DOC, 1, at + 4_000L));
        }
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            var info = world.snapshot().information().orElseThrow().information();
            assertTrue(InformationModel.canCite(info, "kate", RESEARCH_DOC, 1,
                    world.snapshot().timeMillis()), "已读经历必须跨重开保留");
        }
    }

    @Test
    void boardAudienceCannotBeForgedThroughOrdinaryPublish() throws Exception {
        Path journal = dir.resolve("board-only.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            PharmaAuditHarness.approveBudgetAndAppoint(world, "kate");
            long publishDue = world.snapshot().timeMillis() + 10L;
            world.schedulePublishDocument("board-pub", "board-pub-task", publishDue,
                    "audit-board-doc", "frank", InformationModel.Audience.board(PHARMA),
                    "董事会专用材料", "董事会专用正文：仅董事可见", "董事会专用正文：仅董事可见",
                    10L, List.of());
            world.advanceTo(publishDue + 1_000L);
            var info = world.snapshot().information().orElseThrow().information();
            assertTrue(info.documents().stream().noneMatch(doc -> doc.docId()
                            .equals("audit-board-doc")),
                    "普通发表不得占用董事会受众空间");
            assertFalse("ACCEPTED".equals(world.taskResultForTest("board-pub-task")),
                    "普通发表写董事会受众必须被拒绝：" + world.taskResultForTest("board-pub-task"));
            // 非董事经理仍然拿不到任何董事会专用材料：以 pharma 董事会议材料为例，
            // 这些材料只由真实治理链引用，kate 没有席位就没有对应用户经历。
            assertFalse(world.boardOfCompany(PHARMA).orElseThrow().board().charter()
                    .seatOf("kate") != null, "构造前提：kate 不是董事");
        }
    }

    @Test
    void revokedMidReadingProducesNoObservationButKeepsEarlierHistory() throws Exception {
        Path journal = dir.resolve("revoke.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            long docsAt = completeFirstStage(world);
            long at = docsAt + 1_000L;
            world.scheduleStartReading("first-read", "first-read-task", at, "kate",
                    RESEARCH_DOC, 1L);
            world.advanceTo(at + 1_000L);
            var info = world.snapshot().information().orElseThrow().information();
            assertTrue(InformationModel.canCite(info, "kate", RESEARCH_DOC, 1, at + 1_000L),
                    "第一份必须真实读完");
            long observations = info.observations().size();

            // 第二份：开始后、完成前撤职（任命 12,000 起 90 天，此处仍在任期内）。
            long second = at + 2_000L;
            world.scheduleStartReading("second-read", "second-read-task", second, "kate",
                    RESEARCH_DOC, 1L);
            world.scheduleRevokeAppointment("revoke-kate", "revoke-kate-task", second + 5L,
                    "mgr-kate");
            world.advanceTo(second + 1_000L);
            var after = world.snapshot().information().orElseThrow().information();
            assertEquals(observations, after.observations().size(),
                    "阅读途中撤职不得产生新的完成经历");
            assertTrue(InformationModel.canCite(after, "kate", RESEARCH_DOC, 1,
                    second + 1_000L), "此前已读记忆必须保留");
            assertFalse(world.isActiveManager(PHARMA, "kate", second + 1_000L),
                    "撤职必须真实生效");
        }
    }

    @Test
    void roleInputNeverExposesGodTruthOtherAccountsOrFutureOutcome() throws Exception {
        Path journal = dir.resolve("input.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            completeFirstStage(world);
            JsonNode kate = world.engineeringRoleInput("kate");
            JsonNode frank = world.engineeringRoleInput("frank");
            assertNotNull(kate);
            assertNotNull(frank);
            String managerText = kate.toString();
            String directorText = frank.toString();
            for (String leak : List.of("rollSeed", "ENG-A", "RSV-NORTH", "5000.00")) {
                assertFalse(managerText.contains(leak),
                        "经理输入不得含 God 真值或其它账户：" + leak);
                assertFalse(directorText.contains(leak),
                        "董事输入不得含 God 真值或其它账户：" + leak);
            }
            assertFalse(managerText.contains("RESEARCH-CONTRACTOR"),
                    "经理输入不得含承包方账户");
            // 未读之前，经理输入里不得出现研发结果的结论文本。
            var info = world.snapshot().information().orElseThrow().information();
            assertFalse(InformationModel.canCite(info, "kate", RESEARCH_DOC, 1,
                    world.snapshot().timeMillis()),
                    "构造前提：经理尚未读结果");
            assertFalse(managerText.contains("结果=SUCCESS"),
                    "未读之前不得在模型输入里看到结果结论");

            // 董事只能看到自己公司；pharma 董事的 directorships 不含 north。
            assertEquals(PHARMA, frank.path("directorships").get(0).path("companyPartyId").asText());
            assertEquals(1, frank.path("directorships").size(),
                    "pharma 董事不得同时出现在别的公司董事会");
        }
    }

    @Test
    void researchOperationsDisappearOnceManagerIsRevoked() throws Exception {
        Path journal = dir.resolve("revoked-input.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            PharmaAuditHarness.approveBudgetAndAppoint(world, "kate");
            assertTrue(world.engineeringRoleInput("kate").has("researchOperations"),
                    "现任经理必须拿到研发经营投影");
            long at = world.snapshot().timeMillis();
            world.scheduleRevokeAppointment("r-kate", "r-kate-task", at + 10L, "mgr-kate");
            world.advanceTo(at + 20L);
            JsonNode after = world.engineeringRoleInput("kate");
            assertFalse(after.has("researchOperations"),
                    "撤职后不得再拿到研发经营投影：" + after);
            JsonNode frank = world.engineeringRoleInput("frank");
            assertFalse(frank.has("researchOperations"),
                    "董事不是经理，也不该拿到经理的研发经营投影");
        }
    }

    /** 真实预算+任免 → 立项 → 第一阶段完成；返回结果证据的发布时点。 */
    private static long completeFirstStage(WorldRuntime world) throws Exception {
        PharmaAuditHarness.approveBudgetAndAppoint(world, "kate");
        String registered = PharmaAuditHarness.tool(world, "kate", "au-register",
                ResearchDecisionTools.ACT, Map.of("action", "REGISTER", "projectId", "p1",
                        "stage", "", "meetingId", ""));
        assertTrue(registered.contains("REGISTER") || "REGISTERED".equals(registered),
                "立项必须留下真实回执：" + registered);
        long finish = PharmaAuditHarness.reply(world,
                PharmaAuditHarness.open(world, "kate", "au-begin"), ResearchDecisionTools.ACT,
                Map.of("action", "BEGIN", "projectId", "p1", "stage", "RESEARCH",
                        "meetingId", ""));
        world.advanceTo(finish);
        world.advanceTo(finish + DAY);
        assertEquals(ResearchModel.StageStatus.COMPLETED, world.researchRun().orElseThrow()
                .state().projects().get("p1").stages().get(0).status());
        return world.snapshot().timeMillis();
    }
}
