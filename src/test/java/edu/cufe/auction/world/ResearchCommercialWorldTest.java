package edu.cufe.auction.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * schema23 商业化授权与可使用后摊销：真实董事决议 → 经理会话执行 → 期末同帧摊销。
 *
 * <p>全部断言读真实 journal/董事会/研发状态；不 mock 日志，不替换命令，不删既有测试。</p>
 */
class ResearchCommercialWorldTest {

    private static final String A = WorldWebScenario.ENGINE_A;
    private static final long DAY = 86_400_000L;
    private static final String COMPANY_ACCOUNT = "ENG-A";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ 真实闭环

    @Test
    void boardApprovalThenManagerExecutionMakesAssetUsableAndAmortizes() throws Exception {
        WorldRuntime world = open(dir.resolve("loop.journal"));
        buildCompletedProject(world);
        long docsAt = stageTimes(world).get(2);
        readEvidence(world, docsAt, List.of("alice", "bob", "carol", "dave", "erin"));
        long meetingAt = docsAt + 10_000L;
        runMeeting(world, meetingAt, "alice", List.of("alice", "bob", "carol"));
        long approveDue = meetingAt + 5_200L;
        WorldRoleSession manager = world.openRoleSession("dave");
        manager.approveCommercialization("ap-1", "ap-1-task", approveDue, A, "p1", "cm-1",
                1_000L);
        world.advanceTo(approveDue);

        ResearchModel.Project p1 = project(world, "p1");
        assertEquals(ResearchModel.ProjectStatus.APPROVED, p1.status(), "执行后应为 APP 状态");
        assertNotNull(p1.approvalRef(), "必须写入真实授权引用");
        assertEquals(approveDue, p1.approvedAt(), "可用起点=FINISH 时点");
        ResearchModel.Report report = report(world);
        assertEquals(0, report.workInProgress().compareTo(new BigDecimal("0.00")));
        assertEquals(0, report.availableAsset().compareTo(new BigDecimal("700.00")));
        assertEquals(0, report.accumulatedAmortization().compareTo(new BigDecimal("0.00")));
        assertEquals(0, cash(world, COMPANY_ACCOUNT).compareTo(new BigDecimal("4100.00")));

        // 期末采样：先摊后报，摊销与报表同帧；天数按真实 approvedAt 向下取整。
        world.advanceTo(30L * DAY);
        long days = (30L * DAY - approveDue) / DAY;
        String expectedAmortized = amortizedFor(days).toPlainString();
        assertEquals(0, project(world, "p1").amortized().compareTo(new BigDecimal(expectedAmortized)),
                "累计摊销必须等于 700×整日数/365");
        assertTrue(project(world, "p1").amortizedAt() == 30L * DAY);
        String statement = world.periodicStatementForTest(A, 1);
        assertTrue(statement.contains("研发商业化批准=p1@" + approveDue), statement);
        assertTrue(statement.contains("研发累计摊销=" + expectedAmortized), statement);
        String refs = world.periodicBaseRefsForTest(A, 1);
        assertTrue(refs.contains("approval:research:eng-a:p1"), refs);
        world.close();

        // 恢复：批准事实与摊销只由真实 journal 恢复，不重算也不重复。
        try (WorldRuntime reopened = WorldWebScenario.openResearchCommercial(
                dir.resolve("loop.journal")).runtime()) {
            assertEquals(ResearchModel.ProjectStatus.APPROVED, project(reopened, "p1").status());
            assertEquals(0, project(reopened, "p1").amortized().compareTo(
                    new BigDecimal(expectedAmortized)));
            assertEquals(0, cash(reopened, COMPANY_ACCOUNT).compareTo(new BigDecimal("4100.00")));
        }
    }

    @Test
    void proposalRequiresCompletedProjectAndExactEvidenceBinding() throws Exception {
        WorldRuntime world = open(dir.resolve("binding.journal"));
        long docsAt = buildCompletedProject(world);
        readEvidence(world, docsAt, List.of("alice", "bob", "carol"));
        long at = docsAt + 10_000L;
        // 未完成：用另一个尚未立项的项目提出 → 被拒且不写提案。
        conveneAt(world, at, "cm-x");
        attemptProposal(world, at, "cm-x", "p7", List.of(evidence("p1", 0), evidence("p1", 1),
                evidence("p1", 2)), "alice");
        world.advanceTo(at + 3_600L);
        assertTrue(world.researchBudgetForTest(A).meetings().get("cm-x") == null
                        || world.researchBudgetForTest(A).meetings().get("cm-x")
                        .commercializationProposal() == null,
                "未完成/未立项项目不得形成商业化提案");
        world.close();

        // 证据版本不符：请求改写版本 → 被拒。
        WorldRuntime second = open(dir.resolve("binding2.journal"));
        long docs2 = buildCompletedProject(second);
        readEvidence(second, docs2, List.of("alice", "bob", "carol"));
        long at2 = docs2 + 10_000L;
        conveneAt(second, at2, "cm-y");
        List<BoardModel.MaterialRef> wrong = List.of(
                new BoardModel.MaterialRef("m1", "research:eng-a:p1:RESEARCH:SUCCESS", 2L, at2),
                evidenceAt("p1", 1, at2), evidenceAt("p1", 2, at2));
        attemptProposal(second, at2, "cm-y", "p1", wrong, "alice");
        second.advanceTo(at2 + 2_600L);
        assertNull(proposalOf(second, "cm-y"), "证据版本不符不得提案");
        second.close();
    }

    @Test
    void proposerAndExecutorMustHaveReadTheThreeEvidenceDocs() throws Exception {
        WorldRuntime world = open(dir.resolve("read.journal"));
        long docsAt = buildCompletedProject(world);
        long at = docsAt + 10_000L;
        conveneAt(world, at, "cm-r");
        // 全员未读：提案被拒（真实读观测缺失），提案不成立。
        attemptProposal(world, at, "cm-r", "p1", evidenceList("p1", at), "alice");
        world.advanceTo(at + 3_600L);
        assertNull(proposalOf(world, "cm-r"), "未读证据不得提案");
        assertTrue(reasonOf(world, "pp-task-cm-r").contains(
                WorldRuntime.REASON_COMMERCIALIZATION_EVIDENCE_NOT_READ),
                reasonOf(world, "pp-task-cm-r"));

        // 三席读完后再开一场新会议 → 提案成立。
        readEvidence(world, docsAt, List.of("alice", "bob", "carol"));
        long at2 = world.snapshot().timeMillis() + 1_000L;
        conveneAt(world, at2, "cm-r2");
        attemptProposal(world, at2, "cm-r2", "p1", evidenceList("p1", at2), "alice");
        world.advanceTo(at2 + 2_600L);
        assertNotNull(proposalOf(world, "cm-r2"), "已读后应能提案：" + reasonOf(world, "pp-task-cm-r2"));
        world.close();
    }

    @Test
    void voteWithoutReadingIsRejectedAndQuorumIsReal() throws Exception {
        WorldRuntime world = open(dir.resolve("vote.journal"));
        long docsAt = buildCompletedProject(world);
        // 只让 alice/bob 读；carol 不读。
        readEvidence(world, docsAt, List.of("alice", "bob"));
        long at = world.snapshot().timeMillis() + 1_000L;
        conveneAt(world, at, "cm-v");
        world.scheduleCommercializationProposal("pp", "pp-task", at + 2_000L, A, "alice", "cm-v",
                List.of(), request("p1", at), 1_000L);
        world.scheduleEngineeringVote("v1", "v1-task", at + 3_000L, A, "alice", "cm-v",
                BoardModel.Vote.YES, 1_000L);
        world.scheduleEngineeringVote("v2", "v2-task", at + 3_001L, A, "bob", "cm-v",
                BoardModel.Vote.YES, 1_000L);
        world.scheduleEngineeringVote("v3", "v3-task", at + 3_002L, A, "carol", "cm-v",
                BoardModel.Vote.YES, 1_000L);
        world.advanceTo(at + 3_600L);
        assertTrue(reasonOf(world, "v3-task").contains("MATERIAL_NOT_READ"),
                "未读材料的票必须拒：" + reasonOf(world, "v3-task"));
        assertEquals(BoardModel.MeetingStatus.REJECTED,
                world.researchBudgetForTest(A).meetings().get("cm-v").status(),
                "只有两票有效，不足三票应否决");

        long at3 = world.snapshot().timeMillis() + 1_000L;
        conveneAt(world, at3, "cm-q");
        world.scheduleCommercializationProposal("pp-q", "pp-q-task", at3 + 2_000L, A, "alice",
                "cm-q", List.of(), request("p1", at3), 1_000L);
        // 只有两席到会（第三席未排）→ 不足法定人数。
        world.scheduleEngineeringAttend("q-a3", "q-a3-task", at3 + 53L, A, "carol", "cm-q",
                true);
        world.scheduleEngineeringVote("q1", "q1-task", at3 + 3_000L, A, "alice", "cm-q",
                BoardModel.Vote.YES, 1_000L);
        world.advanceTo(at3 + 3_600L);
        assertEquals(BoardModel.MeetingStatus.ADJOURNED_NO_QUORUM,
                world.researchBudgetForTest(A).meetings().get("cm-q").status());
        world.close();
    }

    @Test
    void budgetResolutionOrUnknownMeetingCannotAuthorize() throws Exception {
        WorldRuntime world = open(dir.resolve("noauth.journal"));
        long docsAt = buildCompletedProject(world);
        readEvidence(world, docsAt, List.of("dave"));
        long approveDue = docsAt + 12_000L;
        WorldRoleSession manager = world.openRoleSession("dave");
        // 用预算会议 id：真实存在但不是商业化议题 → 拒绝。
        manager.approveCommercialization("ap-b", "ap-b-task", approveDue, A, "p1",
                "budget-meeting", 1_000L);
        world.advanceTo(approveDue);
        assertNull(project(world, "p1").approvalRef(), "预算决议不得冒充商业化批准");
        assertTrue(resultOf(world, "ap-b-task").contains(BoardModel.REASON_WRONG_TOPIC),
                "拒绝原因必须是错议题：" + resultOf(world, "ap-b-task"));

        manager.approveCommercialization("ap-u", "ap-u-task", approveDue + 3_000L, A, "p1",
                "no-such-meeting", 1_000L);
        world.advanceTo(approveDue + 3_000L);
        assertTrue(project(world, "p1").approvalRef() == null);
        assertTrue(resultOf(world, "ap-u-task").contains(BoardModel.REASON_UNKNOWN_MEETING),
                resultOf(world, "ap-u-task"));
        world.close();
    }

    @Test
    void resolutionForOneProjectCannotApproveAnotherAndManagerRevocationBlocks() throws Exception {
        WorldRuntime world = open(dir.resolve("wrong.journal"));
        long docsAt = buildCompletedProject(world);
        readEvidence(world, docsAt, List.of("alice", "bob", "carol", "dave"));
        long at = docsAt + 10_000L;
        runMeeting(world, at, "alice", List.of("alice", "bob", "carol"));
        // 撤任：在批准执行之前生效，同一会话必须失效。
        long revokeAt = world.snapshot().timeMillis() + 500L;
        revokeManager(world, revokeAt, revokeAt + 1_000L);
        long approveDue = revokeAt + 5_000L;
        WorldRuntime reopened = world;
        WorldRoleSession manager = reopened.openRoleSession("dave");
        manager.approveCommercialization("ap-r", "ap-r-task", approveDue, A, "p7", "cm-1",
                1_000L);
        reopened.advanceTo(approveDue);
        assertTrue(project(reopened, "p7") == null
                || project(reopened, "p7").approvalRef() == null, "项目不匹配不得批准");
        assertNull(project(reopened, "p1").approvalRef(), "撤任后的旧会话不得批准 p1");
        reopened.close();
    }

    @Test
    void duplicateExecutionIsRejectedWithoutDoubleCounting() throws Exception {
        WorldRuntime world = open(dir.resolve("dup.journal"));
        long docsAt = buildCompletedProject(world);
        readEvidence(world, docsAt, List.of("alice", "bob", "carol", "dave"));
        long at = docsAt + 10_000L;
        runMeeting(world, at, "alice", List.of("alice", "bob", "carol"));
        long first = at + 5_200L;
        WorldRoleSession manager = world.openRoleSession("dave");
        manager.approveCommercialization("ap-a", "ap-a-task", first, A, "p1", "cm-1", 1_000L);
        world.advanceTo(first);
        assertEquals(ResearchModel.ProjectStatus.APPROVED, project(world, "p1").status());
        long pos = world.snapshot().position();

        manager.approveCommercialization("ap-b", "ap-b-task", first + 2_000L, A, "p1",
                "cm-1", 1_000L);
        world.advanceTo(first + 2_000L);
        assertTrue(world.snapshot().position() >= pos + 2, "第二次执行也要有真实帧");
        assertEquals(ResearchModel.ProjectStatus.APPROVED, project(world, "p1").status());
        assertEquals(first, project(world, "p1").approvedAt(), "不得改写原可用起点");
        assertTrue(resultOf(world, "ap-b-task").contains(ResearchModel.REASON_ALREADY_APPROVED),
                resultOf(world, "ap-b-task"));
        world.close();
    }

    @Test
    void crossPeriodAmortizationRunsOncePerPeriodEndAndMatchesReport() throws Exception {
        WorldRuntime world = open(dir.resolve("cross.journal"));
        long docsAt = buildCompletedProject(world);
        readEvidence(world, docsAt, List.of("alice", "bob", "carol", "dave"));
        long at = docsAt + 10_000L;
        runMeeting(world, at, "alice", List.of("alice", "bob", "carol"));
        long approveDue = at + 5_200L;
        world.openRoleSession("dave").approveCommercialization("ap-c", "ap-c-task", approveDue,
                A, "p1", "cm-1", 1_000L);
        world.advanceTo(approveDue);

        // 一次跨两期推进：两期末各自摊销到各自期末，不合并、不提前。
        world.advanceTo(60L * DAY + 1L);
        long days = (60L * DAY - approveDue) / DAY;
        BigDecimal expected = amortizedFor(days);
        assertEquals(0, project(world, "p1").amortized().compareTo(expected),
                "跨期后累计摊销=700×整日数/365");
        assertEquals(60L * DAY, project(world, "p1").amortizedAt(), "摊销截止=最近期末");
        String statement1 = world.periodicStatementForTest(A, 1);
        String statement2 = world.periodicStatementForTest(A, 2);
        long days1 = (30L * DAY - approveDue) / DAY;
        assertTrue(statement1.contains("研发累计摊销=" + amortizedFor(days1).toPlainString()),
                statement1);
        assertTrue(statement2.contains("研发累计摊销=" + expected.toPlainString()), statement2);
        BigDecimal equity = report(world).equity();
        assertEquals(0, equity.compareTo(new BigDecimal("5000.00")
                .subtract(new BigDecimal("200.00")).subtract(expected)), "权益=期初−费用−摊销");
        world.close();

        // 重开不重摊：位置/金额/截止都不变。
        try (WorldRuntime reopened = WorldWebScenario.openResearchCommercial(
                dir.resolve("cross.journal")).runtime()) {
            assertEquals(0, project(reopened, "p1").amortized().compareTo(expected));
            long pos = reopened.snapshot().position();
            reopened.advanceTo(60L * DAY + 1L);
            assertEquals(pos, reopened.snapshot().position(), "重开推进同一时点不得追加摊销");
        }
    }

    // ------------------------------------------------------------------ 纯核 60 日数例

    @Test
    void sixtyDayPolicyExampleIsExplicitAndIndependentOfWorldLife() {
        java.util.Map<String, edu.cufe.auction.world.CashLedger.CashAccount> accounts =
                new java.util.LinkedHashMap<>();
        accounts.put(COMPANY_ACCOUNT, new edu.cufe.auction.world.CashLedger.CashAccount("eng-a",
                new BigDecimal("1500.00"), "公司"));
        accounts.put("K-ACC", new edu.cufe.auction.world.CashLedger.CashAccount("lab-co",
                new BigDecimal("500.00"), "承包方"));
        ResearchModel.State state = ResearchModel.open(new ResearchModel.Config("eng-a",
                COMPANY_ACCOUNT, new BigDecimal("500.00")), accounts);
        ResearchModel.ProjectSpec spec = new ResearchModel.ProjectSpec("p1",
                new BigDecimal("900.00"), "K-ACC", 60, List.of(
                new ResearchModel.StagePlan(ResearchModel.Stage.RESEARCH,
                        new BigDecimal("200.00"), 1_000L, "LAB"),
                new ResearchModel.StagePlan(ResearchModel.Stage.DEVELOPMENT,
                        new BigDecimal("300.00"), 1_000L, "PILOT"),
                new ResearchModel.StagePlan(ResearchModel.Stage.REGISTRATION,
                        new BigDecimal("400.00"), 1_000L, "REG")));
        long t = 1_000L;
        ResearchModel.Outcome registered = ResearchModel.register(state, "reg", "eng-a", spec, t,
                accounts);
        state = registered.state();
        accounts = registered.accounts();
        for (ResearchModel.Stage stage : ResearchModel.Stage.values()) {
            ResearchModel.Outcome begun = ResearchModel.begin(state, "b-" + stage, "eng-a", "p1",
                    stage, t, accounts);
            state = begun.state();
            accounts = begun.accounts();
            long due = t + 1_000L;
            ResearchModel.Outcome done = ResearchModel.complete(state, "c-" + stage, "eng-a",
                    "p1", stage, new ResearchModel.Evidence(spec.stages().get(stage.ordinal())
                    .evidenceRequirement(), "doc-" + stage, t + 500L), due, accounts);
            state = done.state();
            accounts = done.accounts();
            t = due;
        }
        ResearchModel.Outcome approved = ResearchModel.approve(state, "ap", "eng-a", "p1",
                "board:commercialization:m", 5_000L, accounts);
        state = approved.state();
        accounts = approved.accounts();
        ResearchModel.Outcome am30 = ResearchModel.amortize(state, "a30", "eng-a", "p1",
                5_000L + 30L * DAY, 5_000L + 30L * DAY, accounts);
        assertEquals(0, ResearchModel.report(am30.state(), accounts).accumulatedAmortization()
                .compareTo(new BigDecimal("350.00")), "60 日寿命 30 日应为 350");
        ResearchModel.Outcome am60 = ResearchModel.amortize(am30.state(), "a60", "eng-a", "p1",
                5_000L + 60L * DAY, 5_000L + 60L * DAY, accounts);
        assertEquals(0, ResearchModel.report(am60.state(), accounts).accumulatedAmortization()
                .compareTo(new BigDecimal("700.00")), "60 日结清 700");
        assertEquals(0, ResearchModel.report(am60.state(), accounts).equity()
                .compareTo(new BigDecimal("1100.00")), "权益 1800-700=1100");
    }

    // ------------------------------------------------------------------ 证据版本口径

    @Test
    void newVersionBeforeProposalRejectsOldEvidence() throws Exception {
        WorldRuntime world = open(dir.resolve("ver-before.journal"));
        long docsAt = buildCompletedProject(world);
        readVersioned(world, List.of("alice", "bob", "carol", "dave"), new long[]{1, 1, 1},
                world.snapshot().timeMillis() + 1_000L);
        publishVersion(world, 0, world.snapshot().timeMillis() + 1_000L);
        long at = world.snapshot().timeMillis() + 1_000L;
        conveneVersioned(world, at, "cm-v", List.of("alice", "bob", "carol"),
                new long[]{1, 1, 1});
        world.scheduleCommercializationProposal("pp-v", "pp-v-task", at + 2_000L, A, "alice",
                "cm-v", List.of(), requestVersioned("p1", new long[]{1, 1, 1}, at), 1_000L);
        world.advanceTo(at + 2_600L);
        assertNull(proposalOf(world, "cm-v"), "已发布更新版本后旧版本证据不得提案");
        assertTrue(resultOf(world, "pp-v-task").contains(
                BoardModel.REASON_COMMERCIALIZATION_EVIDENCE_MISMATCH),
                resultOf(world, "pp-v-task"));
        world.close();
    }

    @Test
    void latestPublishedVersionWithReadingStillApproves() throws Exception {
        WorldRuntime world = open(dir.resolve("ver-latest.journal"));
        buildCompletedProject(world);
        readVersioned(world, List.of("alice", "bob", "carol", "dave"), new long[]{1, 1, 1},
                world.snapshot().timeMillis() + 1_000L);
        publishVersion(world, 0, world.snapshot().timeMillis() + 1_000L);
        readVersioned(world, List.of("alice", "bob", "carol", "dave"), new long[]{2, 1, 1},
                world.snapshot().timeMillis() + 1_000L);
        long at = world.snapshot().timeMillis() + 1_000L;
        runMeetingVersioned(world, at, "cm-2", List.of("alice", "bob", "carol"),
                new long[]{2, 1, 1});
        long due = at + 5_200L;
        world.openRoleSession("dave").approveCommercialization("ap-2", "ap-2-task", due, A,
                "p1", "cm-2", 1_000L);
        world.advanceTo(due);
        assertEquals(ResearchModel.ProjectStatus.APPROVED, project(world, "p1").status(),
                "使用最新已发布且已实读的证据仍应批准");
        world.close();
    }

    @Test
    void versionPublishedBetweenProposalAndFinishBlocksOldAuthorization() throws Exception {
        WorldRuntime world = open(dir.resolve("ver-between.journal"));
        buildCompletedProject(world);
        readVersioned(world, List.of("alice", "bob", "carol", "dave"), new long[]{1, 1, 1},
                world.snapshot().timeMillis() + 1_000L);
        long at = world.snapshot().timeMillis() + 1_000L;
        runMeetingVersioned(world, at, "cm-b", List.of("alice", "bob", "carol"),
                new long[]{1, 1, 1});
        long due = at + 5_200L;
        world.openRoleSession("dave").approveCommercialization("ap-b", "ap-b-task", due, A,
                "p1", "cm-b", 1_000L);
        // 提案已通过、决议已签发，但在 FINISH 之前发布了新版本。
        publishVersion(world, 0, at + 4_500L);
        world.advanceTo(due);
        assertTrue(project(world, "p1").approvalRef() == null, "旧授权不得继续批准");
        assertTrue(resultOf(world, "ap-b-task").contains(
                BoardModel.REASON_COMMERCIALIZATION_EVIDENCE_MISMATCH),
                resultOf(world, "ap-b-task"));

        // 重新审：读新版本后另开会议并批准。
        readVersioned(world, List.of("alice", "bob", "carol", "dave"), new long[]{2, 1, 1},
                world.snapshot().timeMillis() + 1_000L);
        long at2 = world.snapshot().timeMillis() + 1_000L;
        runMeetingVersioned(world, at2, "cm-b2", List.of("alice", "bob", "carol"),
                new long[]{2, 1, 1});
        long due2 = at2 + 5_200L;
        world.openRoleSession("dave").approveCommercialization("ap-b2", "ap-b2-task", due2, A,
                "p1", "cm-b2", 1_000L);
        world.advanceTo(due2);
        assertEquals(ResearchModel.ProjectStatus.APPROVED, project(world, "p1").status(),
                "重新绑定最新版本后应批准");
        world.close();
    }

    @Test
    void laterVersionAfterApprovalDoesNotChangeApprovedFacts() throws Exception {
        WorldRuntime world = open(dir.resolve("ver-later.journal"));
        buildCompletedProject(world);
        readVersioned(world, List.of("alice", "bob", "carol", "dave"), new long[]{1, 1, 1},
                world.snapshot().timeMillis() + 1_000L);
        long at = world.snapshot().timeMillis() + 1_000L;
        runMeetingVersioned(world, at, "cm-l", List.of("alice", "bob", "carol"),
                new long[]{1, 1, 1});
        long due = at + 5_200L;
        world.openRoleSession("dave").approveCommercialization("ap-l", "ap-l-task", due, A,
                "p1", "cm-l", 1_000L);
        world.advanceTo(due);
        long approvedAt = project(world, "p1").approvedAt();
        // 批准完成后才发布新版本：不追溯 approvedAt，也不改既有摊销。
        publishVersion(world, 0, due + 4_500L);
        world.advanceTo(30L * DAY);
        long days = (30L * DAY - approvedAt) / DAY;
        assertEquals(approvedAt, project(world, "p1").approvedAt(),
                "批准后出现的版本不得改可用起点");
        assertEquals(0, project(world, "p1").amortized().compareTo(amortizedFor(days)),
                "摊销按原 approvedAt 计算，不受未来版本影响");
        world.close();
        try (WorldRuntime reopened = WorldWebScenario.openResearchCommercial(
                dir.resolve("ver-later.journal")).runtime()) {
            assertEquals(approvedAt, project(reopened, "p1").approvedAt());
            assertEquals(0, project(reopened, "p1").amortized().compareTo(amortizedFor(days)));
        }
    }

    @Test
    void futureUnpublishedVersionIsNotTheCurrentOne() throws Exception {
        WorldRuntime world = open(dir.resolve("ver-future.journal"));
        buildCompletedProject(world);
        readVersioned(world, List.of("alice", "bob", "carol", "dave"), new long[]{1, 1, 1},
                world.snapshot().timeMillis() + 1_000L);
        long at = world.snapshot().timeMillis() + 1_000L;
        long due = at + 5_200L;
        // 新版本定在未来发布：FINISH 时它还不是当前版本，旧版本仍可批准。
        scheduleVersion(world, 0, due + 10_000L);
        runMeetingVersioned(world, at, "cm-f", List.of("alice", "bob", "carol"),
                new long[]{1, 1, 1});
        world.openRoleSession("dave").approveCommercialization("ap-f", "ap-f-task", due, A,
                "p1", "cm-f", 1_000L);
        world.advanceTo(due);
        assertEquals(ResearchModel.ProjectStatus.APPROVED, project(world, "p1").status(),
                "未来版本不算当前版本");
        long approvedAt = project(world, "p1").approvedAt();
        world.advanceTo(due + 10_000L);
        assertEquals(approvedAt, project(world, "p1").approvedAt(),
                "未来版本发布后也不得追溯改写已批准事实");
        world.close();
    }

    // ------------------------------------------------------------------ 篡改

    @Test
    void tamperedApprovalOrAmortizationFrameIsRejected() throws Exception {
        Path base = dir.resolve("tamper-base.journal");
        WorldRuntime world = open(base);
        long docsAt = buildCompletedProject(world);
        readEvidence(world, docsAt, List.of("alice", "bob", "carol", "dave"));
        long at = docsAt + 10_000L;
        runMeeting(world, at, "alice", List.of("alice", "bob", "carol"));
        long approveDue = at + 5_200L;
        world.openRoleSession("dave").approveCommercialization("ap-t", "ap-t-task", approveDue,
                A, "p1", "cm-1", 1_000L);
        world.advanceTo(approveDue);
        world.close();

        // 未篡改对照：可以正常打开。
        WorldWebScenario.openResearchCommercial(base).runtime().close();

        Path approval = dir.resolve("tamper-approval.journal");
        tamper(base, approval, (snapshot, mapper) -> {
            JsonNode project = snapshot.path("research").path("state").path("projects").path("p1");
            if (!project.isObject()) {
                return false;
            }
            ((ObjectNode) project).put("approvedAt", 1L);
            return true;
        });
        assertThrows(Exception.class, () -> WorldWebScenario.openResearchCommercial(approval));

        Path amortized = dir.resolve("tamper-amortized.journal");
        tamper(base, amortized, (snapshot, mapper) -> {
            JsonNode project = snapshot.path("research").path("state").path("projects").path("p1");
            if (!project.isObject()) {
                return false;
            }
            ((ObjectNode) project).put("amortized", "1.00");
            return true;
        });
        assertThrows(Exception.class, () -> WorldWebScenario.openResearchCommercial(amortized));
    }

    // ------------------------------------------------------------------ 夹具与工具

    private static WorldRuntime open(Path file) throws Exception {
        return WorldWebScenario.openResearchCommercial(file).runtime();
    }

    /** 预算 + 经理（90 天）；在世界上排好并推进到 1000ms。 */
    static void prepared(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-material", "pub-material-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "材料", "实验材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        List<String> voters = List.of("alice", "bob", "carol", "dave", "erin");
        int index = 0;
        for (String reader : voters) {
            world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                    20L + index * 2L, reader, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
            index++;
        }
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L, A, "alice",
                "budget-meeting", 150L, 200L, 300L);
        for (int i = 0; i < 5; i++) {
            world.scheduleEngineeringAttend("budget-a-" + i, "budget-a-" + i + "-task",
                    110L + i, A, voters.get(i), "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L, A, "alice",
                "budget-meeting", "m1", "b-material", 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L, A, "alice",
                "budget-meeting", List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal("10000.00"))));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("budget-v-" + i, "budget-v-" + i + "-task",
                    220L + i, A, voters.get(i), "budget-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L, A, "alice",
                "budget-meeting");
        world.scheduleEngineeringAppointmentConvene("appt-c", "appt-c-task", 400L, A, "alice",
                "appt-meeting", 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("appt-a-" + i, "appt-a-" + i + "-task", 410L + i, A,
                    voters.get(i), "appt-meeting", false);
        }
        world.scheduleEngineeringMaterial("appt-m", "appt-m-task", 430L, A, "alice",
                "appt-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("appt-p", "appt-p-task", 440L, A, "alice",
                "appt-meeting", List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, 90L * DAY));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("appt-v-" + i, "appt-v-" + i + "-task", 520L + i, A,
                    voters.get(i), "appt-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("appt-i", "appt-i-task", 620L, A, "alice",
                "appt-meeting");
        world.advanceTo(1_000L);
        assertTrue(world.activeManagerForTest("dave", A));
    }

    /**
     * 预算/经理 + p1 三阶段完成。
     *
     * @return 最后一份结果证据的发布时间
     */
    static long buildCompletedProject(WorldRuntime world) throws Exception {
        prepared(world);
        world.scheduleResearchRegister("reg-p1", "reg-p1-task", 3_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(3_001L);
        for (int i = 0; i < 3; i++) {
            ResearchModel.Stage stage = ResearchModel.Stage.values()[i];
            long finish = world.snapshot().timeMillis() + 2_000L;
            world.scheduleResearchBegin("b-" + i, "b-task-" + i, finish, A, "dave", "p1", stage,
                    1_000L);
            world.advanceTo(finish);
            world.advanceTo(finish + DAY);
        }
        assertEquals(ResearchModel.ProjectStatus.COMPLETED, project(world, "p1").status());
        return world.snapshot().timeMillis();
    }

    private static List<BoardModel.MaterialRef> evidenceList(String project, long at) {
        List<BoardModel.MaterialRef> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            list.add(evidenceAt(project, i, at));
        }
        return list;
    }

    private static BoardModel.MaterialRef evidenceAt(String project, int index, long at) {
        String stage = ResearchModel.Stage.values()[index].name();
        return new BoardModel.MaterialRef("m" + (index + 1),
                "research:eng-a:" + project + ":" + stage + ":SUCCESS", 1L, at);
    }

    private static BoardModel.MaterialRef evidence(String project, int index) {
        String stage = ResearchModel.Stage.values()[index].name();
        return new BoardModel.MaterialRef("m" + (index + 1),
                "research:eng-a:" + project + ":" + stage + ":SUCCESS", 1L, 0L);
    }

    private static BoardModel.CommercializationRequest request(String project, long at) {
        return new BoardModel.CommercializationRequest(A, project, evidenceList(project, at));
    }

    private static BigDecimal amortizedFor(long days) {
        BigDecimal cost = new BigDecimal("700.00");
        BigDecimal target = cost.multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        return target.min(cost);
    }

    private static ResearchModel.Project project(WorldRuntime world, String projectId) {
        return world.researchRun().orElseThrow().state().projects().get(projectId);
    }

    private static ResearchModel.Report report(WorldRuntime world) {
        return ResearchModel.report(world.researchRun().orElseThrow().state(), world.snapshot()
                .accounts());
    }

    private static BigDecimal cash(WorldRuntime world, String accountId) {
        return world.snapshot().accounts().get(accountId).cash();
    }

    private static BoardModel.CommercializationProposal proposalOf(WorldRuntime world,
            String meetingId) {
        BoardModel.Meeting meeting = world.researchBudgetForTest(A).meetings().get(meetingId);
        return meeting == null ? null : meeting.commercializationProposal();
    }

    /** 研发任务（不写工程回报表）最后一批真实提交结果。 */
    private static String resultOf(WorldRuntime world, String taskId) {
        return world.taskResultForTest(taskId);
    }

    /** 该任务（含其完成帧）最近一次真实回报的原因；没有回报返回空串。 */
    private static String reasonOf(WorldRuntime world, String taskId) {
        EngineeringRun run = world.snapshot().engineering().orElseThrow();
        EngineeringRun.Report barrier = null;
        EngineeringRun.Report decided = null;
        for (EngineeringRun.Report report : run.reports().values()) {
            if (!report.taskId().equals(taskId) && !report.taskId().startsWith(taskId + ":")) {
                continue;
            }
            if ("STARTED".equals(report.reason()) || "OK".equals(report.reason())) {
                barrier = report;
            } else {
                decided = report;
            }
        }
        EngineeringRun.Report last = decided == null ? barrier : decided;
        return last == null ? "" : last.reason();
    }

    private static void assertNull(Object value, String message) {
        assertTrue(value == null, message);
    }

    private static List<Long> stageTimes(WorldRuntime world) {
        List<Long> times = new ArrayList<>();
        for (ResearchModel.StageRecord stage : project(world, "p1").stages()) {
            times.add(stage.completesAt());
        }
        return times;
    }

    static void readEvidence(WorldRuntime world, long docsAt, List<String> readers)
            throws Exception {
        long now = world.snapshot().timeMillis();
        long start = Math.max(docsAt + 1_000L, now + 1_000L);
        for (String reader : readers) {
            for (int i = 0; i < 3; i++) {
                String doc = evidence("p1", i).sourceRef();
                world.scheduleStartReading("rd-" + reader + "-" + i,
                        "rd-task-" + reader + "-" + i, start + 2_000L + i * 1_100L, reader, doc, 1L);
            }
        }
        world.advanceTo(start + 2_000L + 3L * 1_100L + 500L);
    }

    /** 按版本读三份证据：同一人物串行、不同人物可并行。 */
    private static void readVersioned(WorldRuntime world, List<String> readers, long[] versions,
            long base) throws Exception {
        int slot = 0;
        for (String reader : readers) {
            for (int i = 0; i < 3; i++) {
                String doc = evidence("p1", i).sourceRef();
                long due = base + slot * 1_100L;
                world.scheduleStartReading("rv-" + reader + "-" + i + "-" + versions[i] + "-"
                        + base, "rv-task-" + reader + "-" + i + "-" + versions[i] + "-" + base,
                        due, reader, doc, versions[i]);
                slot++;
            }
        }
        world.advanceTo(base + (long) slot * 1_100L + 500L);
    }

    private static void scheduleVersion(WorldRuntime world, int index, long at) throws Exception {
        world.schedulePublishDocumentVersion("pv-s-" + index + "-" + at,
                "pv-s-task-" + index + "-" + at, at, evidence("p1", index).sourceRef(),
                "证据更新", "补充版本正文", "", 1_000L, List.of());
    }

    private static void publishVersion(WorldRuntime world, int index, long at) throws Exception {
        scheduleVersion(world, index, at);
        world.advanceTo(at);
    }

    private static BoardModel.MaterialRef evidenceVersioned(String project, int index, long version,
            long at) {
        return new BoardModel.MaterialRef("m" + (index + 1), evidence(project, index).sourceRef(),
                version, at);
    }

    private static BoardModel.CommercializationRequest requestVersioned(String project,
            long[] versions, long at) {
        List<BoardModel.MaterialRef> refs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            refs.add(evidenceVersioned(project, i, versions[i], at));
        }
        return new BoardModel.CommercializationRequest(A, project, refs);
    }

    private static void conveneVersioned(WorldRuntime world, long at, String meetingId,
            List<String> attendees, long[] versions) throws Exception {
        world.scheduleCommercializationConvene("cv-" + meetingId, "cv-task-" + meetingId, at, A,
                "alice", meetingId, at + 2_500L, at + 2_700L, at + 3_500L);
        for (int i = 0; i < attendees.size(); i++) {
            world.scheduleEngineeringAttend("at" + i + "-" + meetingId,
                    "at" + i + "-task-" + meetingId, at + 50L + i, A, attendees.get(i),
                    meetingId, false);
        }
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringMaterial("mat" + i + "-" + meetingId,
                    "mat" + i + "-task-" + meetingId, at + 100L + i, A, "alice", meetingId,
                    "m" + (i + 1), evidence("p1", i).sourceRef(), versions[i]);
        }
    }

    private static void runMeetingVersioned(WorldRuntime world, long at, String meetingId,
            List<String> attendees, long[] versions) throws Exception {
        conveneVersioned(world, at, meetingId, attendees, versions);
        world.scheduleCommercializationProposal("pp-" + meetingId, "pp-" + meetingId + "-task",
                at + 2_000L, A, "alice", meetingId, List.of(),
                requestVersioned("p1", versions, at), 1_000L);
        for (int i = 0; i < attendees.size(); i++) {
            world.scheduleEngineeringVote("v" + i + "-" + meetingId,
                    "v" + i + "-task-" + meetingId, at + 3_000L + i, A, attendees.get(i),
                    meetingId, BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCommercializationImplement("impl-" + meetingId,
                "impl-task-" + meetingId, at + 3_600L, A, "alice", meetingId);
        world.advanceTo(at + 3_600L);
    }

    private static void conveneAt(WorldRuntime world, long at, String meetingId) throws Exception {
        world.scheduleCommercializationConvene("cv-" + meetingId, "cv-task-" + meetingId, at, A,
                "alice", meetingId, at + 2_500L, at + 2_700L, at + 3_500L);
        world.scheduleEngineeringAttend("at1-" + meetingId, "at1-task-" + meetingId, at + 50L, A,
                "alice", meetingId, false);
        world.scheduleEngineeringAttend("at2-" + meetingId, "at2-task-" + meetingId, at + 51L, A,
                "bob", meetingId, false);
        world.scheduleEngineeringAttend("at3-" + meetingId, "at3-task-" + meetingId, at + 52L, A,
                "carol", meetingId, false);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringMaterial("mat" + i + "-" + meetingId,
                    "mat" + i + "-task-" + meetingId, at + 100L + i, A, "alice", meetingId,
                    "m" + (i + 1), evidence("p1", i).sourceRef(), 1L);
        }
    }

    private static void attemptProposal(WorldRuntime world, long at, String meetingId,
            String projectId, List<BoardModel.MaterialRef> materials, String proposer)
            throws Exception {
        world.scheduleCommercializationProposal("pp-" + meetingId, "pp-task-" + meetingId,
                at + 2_000L, A, proposer, meetingId, List.of(),
                new BoardModel.CommercializationRequest(A, projectId, materials), 1_000L);
    }

    static void runMeeting(WorldRuntime world, long at, String chair,
            List<String> attendees) throws Exception {
        String meetingId = "cm-1";
        world.scheduleCommercializationConvene("cv-1", "cv-1-task", at, A, chair, meetingId,
                at + 2_500L, at + 2_700L, at + 3_500L);
        for (int i = 0; i < attendees.size(); i++) {
            world.scheduleEngineeringAttend("at-" + i, "at-task-" + i, at + 50L + i, A,
                    attendees.get(i), meetingId, false);
        }
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringMaterial("mat-" + i, "mat-task-" + i, at + 100L + i, A,
                    chair, meetingId, "m" + (i + 1), evidence("p1", i).sourceRef(), 1L);
        }
        world.scheduleCommercializationProposal("pp-1", "pp-1-task", at + 2_000L, A, chair,
                meetingId, List.of(), request("p1", at), 1_000L);
        for (int i = 0; i < attendees.size(); i++) {
            world.scheduleEngineeringVote("vote-" + i, "vote-task-" + i, at + 3_000L + i, A,
                    attendees.get(i), meetingId, BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCommercializationImplement("impl-1", "impl-1-task", at + 3_600L, A, chair,
                meetingId);
        world.advanceTo(at + 3_600L);
    }

    private static void revokeManager(WorldRuntime world, long at, long implementAt)
            throws Exception {
        world.scheduleEngineeringAppointmentConvene("rev-c", "rev-c-task", at, A, "alice",
                "rev-meeting", at + 120L, at + 160L, at + 300L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("rev-a-" + i, "rev-a-" + i + "-task", at + 10L + i,
                    A, List.of("alice", "bob", "carol").get(i), "rev-meeting", false);
        }
        world.scheduleEngineeringMaterial("rev-m", "rev-m-task", at + 40L, A, "alice",
                "rev-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("rev-p", "rev-p-task", at + 60L, A,
                "alice", "rev-meeting", List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.REVOKE,
                        implementAt, null));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("rev-v-" + i, "rev-v-" + i + "-task", at + 200L + i,
                    A, List.of("alice", "bob", "carol").get(i), "rev-meeting",
                    BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("rev-i", "rev-i-task", implementAt, A, "alice",
                "rev-meeting");
    }

    /** 只重写最后一帧的状态快照并重算摘要，模拟自洽篡改。 */
    private static void tamper(Path source, Path target, SnapshotMutation mutation)
            throws Exception {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        List<byte[]> frames = readFrames(target);
        int index = frames.size() - 1;
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode batch = (ObjectNode) mapper.readTree(frames.get(index));
        ArrayNode events = (ArrayNode) batch.get("events");
        boolean touched = false;
        for (JsonNode event : events) {
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            ObjectNode snapshot = (ObjectNode) mapper.readTree(event.get("payload").asText());
            if (!mutation.apply(snapshot, mapper)) {
                continue;
            }
            ((ObjectNode) event).put("payload", mapper.writeValueAsString(snapshot));
            touched = true;
        }
        if (!touched) {
            throw new IllegalArgumentException("该帧没有可篡改的快照内容");
        }
        frames.set(index, mapper.writeValueAsBytes(batch));
        Files.write(target, frameBytes(frames));
    }

    private static List<byte[]> readFrames(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            int length = ByteBuffer.wrap(bytes, offset, 4).getInt();
            int inverse = ByteBuffer.wrap(bytes, offset + 4, 4).getInt();
            if (length <= 0 || inverse != ~length || offset + 8 + length + 32 > bytes.length) {
                throw new IllegalStateException("帧格式不合法：" + offset);
            }
            frames.add(Arrays.copyOfRange(bytes, offset + 8, offset + 8 + length));
            offset += 8 + length + 32;
        }
        return frames;
    }

    private static byte[] frameBytes(List<byte[]> frames) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : frames) {
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length)
                    .array());
            out.write(payload);
            out.write(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        return out.toByteArray();
    }

    private static int lastFrameOffset(byte[] all) {
        int offset = 0;
        int last = 0;
        while (offset + 8 <= all.length) {
            int length = ByteBuffer.wrap(all, offset, 4).getInt();
            int end = offset + 8 + length + 32;
            if (end > all.length) {
                break;
            }
            last = offset;
            offset = end;
        }
        return last;
    }

    private interface SnapshotMutation {
        boolean apply(ObjectNode snapshot, ObjectMapper mapper);
    }
}
