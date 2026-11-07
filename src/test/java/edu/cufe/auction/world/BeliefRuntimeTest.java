package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4g.2 信念运行桥：真实 WorldRuntime 入口上的发布/阅读/判断/事实/学习/输入/审计。
 *
 * <p>不使用平行状态，不使用条件跳过；每个断言都落在真实持久批次的结果上。</p>
 */
class BeliefRuntimeTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String COMPANY = "cufe";
    private static final long DAY = 86_400_000L;

    @Test
    void newArchiveIsSchema12WithBeliefToolsAndEmptyAudit() throws Exception {
        Path dir = newDir("belief-runtime");
        WorldRuntime rt = WorldWebScenario.openBelief(dir.resolve("world.journal")).runtime();
        try {
            assertTrue(rt.snapshot().position() > 0);
            WorldRuntime.BeliefAudit audit = rt.beliefAudit(ALICE).orElseThrow();
            assertEquals(COMPANY, WorldWebScenario.COMPANY);
            assertTrue(audit.state().beliefs().isEmpty());
            RoleTradingView view = rt.roleTradingView(rt.openRoleSession(ALICE));
            assertTrue(view.tools().contains(RoleTradingView.TOOL_BELIEF_ASSERT));
            assertTrue(view.tools().contains(RoleTradingView.TOOL_FORECAST_PUBLISH));
            assertTrue(view.tools().contains(RoleTradingView.TOOL_COMPANY_REPORT_ORDERS));
            assertNotNull(view.profile().cognition());
        } finally {
            rt.close();
        }
    }

    @Test
    void legacyArchiveKeepsV1ToolsAndNoCognition() throws Exception {
        Path dir = newDir("belief-legacy");
        WorldRuntime rt = WorldWebScenario.openCognition(dir.resolve("world.journal")).runtime();
        try {
            RoleTradingView view = rt.roleTradingView(rt.openRoleSession(ALICE));
            assertFalse(view.tools().contains(RoleTradingView.TOOL_BELIEF_ASSERT));
            assertNull(view.profile().cognition());
            assertTrue(rt.beliefAudit(ALICE).isEmpty());
        } finally {
            rt.close();
        }
    }

    @Test
    void publishReadThenAssertBeliefShowsInInputAndAudit() throws Exception {
        try (Harness h = new Harness()) {
            h.publish("DOC-A", ALICE, "公司公告A", 1_000L);
            h.read(ALICE, "DOC-A", 2_000L);
            long now = h.rt.snapshot().timeMillis();
            WorldRoleSession session = h.rt.openRoleSession(ALICE);
            assertNotNull(session.observedPosition());
            String scheduled = session.assertBelief("cmd-1", "task-1", now + 1,
                    new WorldRoleSession.BeliefDraft("B-1", COMPANY,
                            BeliefModel.Comparator.AT_LEAST, 1L, now + DAY, null,
                            List.of(h.ref(ALICE, "DOC-A")), "我看好订单"));
            assertEquals("SCHEDULED:task-1", scheduled);
            h.rt.advanceTo(now + 1);
            WorldRuntime.BeliefAudit audit = h.rt.beliefAudit(ALICE).orElseThrow();
            assertEquals(1, audit.state().beliefs().size());
            assertEquals("B-1", audit.state().beliefs().get(0).beliefId());
            assertEquals(ALICE, audit.state().beliefs().get(0).personPartyId());
            assertEquals(ALICE, audit.state().beliefs().get(0).verificationPublisherPartyId(),
                    "核验者由章程主席决定，模型不可填");

            RoleTradingView view = h.rt.roleTradingView(h.rt.openRoleSession(ALICE));
            String input = DecisionRun.inputText(view, "SCHEDULED",
                    h.rt.snapshot().timeMillis(), List.of(), view.tools(),
                    WorldWebScenario.liveConfig(), DecisionRun.TOOL_CONTRACT_VERSION_V2);
            assertTrue(input.contains("\"cognition\""), "新档输入必须含认知块");
            assertTrue(input.contains("\"B-1\""), "输入必须含本人判断");
            assertTrue(input.contains("\"toolContractVersion\":\"role-decision-tools-v2\""));
            assertFalse(input.contains("confidencePermille"), "不得输出数值置信");
        }
    }

    @Test
    void evidenceReadAfterTheObservedFrameIsRejected() throws Exception {
        try (Harness h = new Harness()) {
            h.publish("DOC-A", ALICE, "公司公告A", 1_000L);
            h.read(ALICE, "DOC-A", 2_000L);
            WorldRoleSession observed = h.rt.openRoleSession(ALICE);
            // 观察帧之后又读完一篇
            h.publish("DOC-B", ALICE, "公司公告B", 3_000L);
            h.read(ALICE, "DOC-B", 4_000L);
            long now = h.rt.snapshot().timeMillis();
            observed.assertBelief("cmd-1", "task-1", now + 1,
                    new WorldRoleSession.BeliefDraft("B-1", COMPANY,
                            BeliefModel.Comparator.AT_LEAST, 1L, now + DAY, null,
                            List.of(h.ref(ALICE, "DOC-B")), "看了后一帧"));
            h.rt.advanceTo(now + 1);
            assertTrue(h.rt.beliefAudit(ALICE).orElseThrow().state().beliefs().isEmpty(),
                    "同毫秒之后读到的材料不能补旧观察");
            assertEquals("STALE_OBSERVATION",
                    h.tradingReports().values().stream()
                            .filter(r -> r.kind().equals(PendingTask.ROLE_TRADING_BELIEF_V1))
                            .findFirst().orElseThrow().reason());
        }
    }

    @Test
    void chairReportsOrdersCaptureThenPublishFrozenFact() throws Exception {
        try (Harness h = new Harness()) {
            long now = h.rt.snapshot().timeMillis();
            long asOf = now + 10_000L;
            WorldRoleSession chair = h.rt.openRoleSession(ALICE);
            chair.reportOrders("cmd-r", "task-r", now + 1,
                    new WorldRoleSession.ReportDraft(COMPANY, asOf));
            h.rt.advanceTo(now + 1);
            assertEquals("SCHEDULED",
                    h.tradingReports().values().stream()
                            .filter(r -> r.kind().equals(PendingTask.ROLE_TRADING_COMPANY_REPORT_V1))
                            .findFirst().orElseThrow().reason());
            // 捕获发生在 asOf 的 OBSERVATION；发布在下一毫秒
            h.rt.advanceTo(asOf);
            assertEquals(asOf, h.rt.snapshot().timeMillis());
            h.rt.advanceTo(asOf + 1);
            WorldRuntime.BeliefAudit audit = h.rt.beliefAudit(ALICE).orElseThrow();
            assertEquals(1, audit.factClaims().size());
            BeliefModel.FactClaim fact = audit.factClaims().get(0);
            assertEquals(asOf, fact.asOfMillis());
            assertEquals(0L, fact.value(), "当前台账没有已确认合同");
            assertEquals(ALICE, fact.verifierPartyId());
            assertNotNull(h.docOf(asOf + 1, fact.docId()), "事实文档必须在 asOf+1 发布");
        }
    }

    @Test
    void factEvaluationRequiresActualRead() throws Exception {
        try (Harness h = new Harness()) {
            h.publish("DOC-A", ALICE, "公司公告A", 1_000L);
            h.read(ALICE, "DOC-A", 2_000L);
            long now = h.rt.snapshot().timeMillis();
            long asOf = now + 10_000L;
            h.rt.openRoleSession(ALICE).assertBelief("cmd-b", "task-b", now + 1,
                    new WorldRoleSession.BeliefDraft("B-1", COMPANY,
                            BeliefModel.Comparator.AT_LEAST, 1L, asOf, null,
                            List.of(h.ref(ALICE, "DOC-A")), "订单会到 1"));
            h.rt.advanceTo(now + 1);
            h.rt.openRoleSession(ALICE).reportOrders("cmd-r", "task-r", now + 2,
                    new WorldRoleSession.ReportDraft(COMPANY, asOf));
            h.rt.advanceTo(now + 2);
            h.rt.advanceTo(asOf + 1);
            assertNull(BeliefModel.verdict(
                    h.rt.beliefAudit(ALICE).orElseThrow().state(), "B-1"), "没读核验材料不学习");
            BeliefModel.FactClaim fact = h.rt.beliefAudit(ALICE).orElseThrow().factClaims().get(0);
            h.read(ALICE, fact.docId(), asOf + 2);
            assertEquals(BeliefModel.Verdict.MISS,
                    BeliefModel.verdict(h.rt.beliefAudit(ALICE).orElseThrow().state(), "B-1"));
        }
    }

    @Test
    void nonChairCannotReportOrders() throws Exception {
        try (Harness h = new Harness()) {
            long now = h.rt.snapshot().timeMillis();
            h.rt.openRoleSession(BOB).reportOrders("cmd-r", "task-r", now + 1,
                    new WorldRoleSession.ReportDraft(COMPANY, now + 10_000L));
            h.rt.advanceTo(now + 1);
            assertTrue(h.rt.beliefAudit(ALICE).orElseThrow().factClaims().isEmpty());
            assertEquals("NOT_AUTHORIZED",
                    h.tradingReports().values().stream()
                            .filter(r -> r.kind().equals(PendingTask.ROLE_TRADING_COMPANY_REPORT_V1))
                            .findFirst().orElseThrow().reason());
        }
    }

    @Test
    void forecastPublishCreatesClaimAndOthersCanAdoptIt() throws Exception {
        try (Harness h = new Harness()) {
            long now = h.rt.snapshot().timeMillis();
            long target = now + DAY;
            h.rt.openRoleSession(ALICE).publishForecast("cmd-f", "task-f", now + 1,
                    new WorldRoleSession.ForecastDraft("SC-1", "FORECAST-1", "订单预测",
                            "我预计订单会达到 1 笔", "公开预测", 1_000L, COMPANY,
                            BeliefModel.Comparator.AT_LEAST, 1L, target));
            h.rt.advanceTo(now + 1);
            WorldRuntime.BeliefAudit audit = h.rt.beliefAudit(ALICE).orElseThrow();
            assertEquals(1, audit.sourceClaims().size());
            assertEquals(ALICE, audit.sourceClaims().get(0).authorPartyId());
            assertEquals(ALICE, audit.sourceClaims().get(0).verificationPublisherPartyId(),
                    "核验者由章程主席冻结");

            h.read(BOB, "FORECAST-1", now + 2);
            long after = h.rt.snapshot().timeMillis();
            h.rt.openRoleSession(BOB).assertBelief("cmd-b", "task-b", after + 1,
                    new WorldRoleSession.BeliefDraft("B-BOB", COMPANY,
                            BeliefModel.Comparator.AT_LEAST, 1L, target, "SC-1",
                            List.of(h.ref(BOB, "FORECAST-1")), "采纳该预测"));
            h.rt.advanceTo(after + 1);
            BeliefModel.Belief adopted = h.rt.beliefAudit(BOB).orElseThrow().state().beliefs()
                    .stream().filter(b -> b.beliefId().equals("B-BOB")).findFirst().orElseThrow();
            assertEquals("SC-1", adopted.adoptedClaimId());
            assertEquals(ALICE, adopted.sourcePartyId());
        }
    }

    @Test
    void beliefsAndClaimsSurviveRestart() throws Exception {
        Path dir = newDir("belief-restart");
        long position;
        try (WorldRuntime rt = WorldWebScenario.openBelief(dir.resolve("world.journal")).runtime()) {
            long now = rt.snapshot().timeMillis();
            rt.schedulePublishDocument("pub-1", "p-1", now + 1, "DOC-A", ALICE,
                    InformationModel.Audience.publicAudience(), "公告", "正文", "来源A",
                    1_000L, List.of());
            rt.advanceTo(now + 1);
            rt.scheduleStartReading("rd-1", "r-1", now + 2, ALICE, "DOC-A", 1);
            rt.advanceTo(now + 2 + 1_000L);
            long afterRead = rt.snapshot().timeMillis();
            InformationModel.State info = rt.snapshot().information().orElseThrow().information();
            InformationModel.ReadObservation observation = info.observations().get(0);
            String readId = observation.readId();
            List<String> ids = new ArrayList<>();
            info.closed().forEach(c -> {
                if (c.personPartyId().equals(ALICE)) {
                    ids.add(c.readId());
                }
            });
            ids.sort(Comparator.comparingLong(BeliefRuntimeTest::sequence));
            int index = ids.indexOf(readId) + 1;
            rt.openRoleSession(ALICE).assertBelief("cmd-1", "task-1", afterRead + 1,
                    new WorldRoleSession.BeliefDraft("B-1", COMPANY,
                            BeliefModel.Comparator.AT_LEAST, 1L, afterRead + DAY, null,
                            List.of(new RoleView.ReadingRef(ALICE, "DOC-A", 1, index)), "判断"));
            rt.advanceTo(afterRead + 1);
            position = rt.snapshot().position();
            assertEquals(1, rt.beliefAudit(ALICE).orElseThrow().state().beliefs().size());
        }
        try (WorldRuntime reopened = WorldWebScenario.openBelief(
                dir.resolve("world.journal")).runtime()) {
            assertEquals(position, reopened.snapshot().position());
            assertEquals(1, reopened.beliefAudit(ALICE).orElseThrow().state().beliefs().size());
            assertEquals("B-1", reopened.beliefAudit(ALICE).orElseThrow().state().beliefs()
                    .get(0).beliefId());
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static final class Harness implements AutoCloseable {
        final Path dir;
        final WorldRuntime rt;

        Harness() throws Exception {
            dir = newDir("belief-harness");
            rt = WorldWebScenario.openBelief(dir.resolve("world.journal")).runtime();
        }

        void publish(String docId, String publisher, String source, long at) throws Exception {
            long now = rt.snapshot().timeMillis();
            rt.schedulePublishDocument("pub-" + docId, "p-" + docId, Math.max(at, now + 1), docId,
                    publisher, InformationModel.Audience.publicAudience(), "公告-" + docId,
                    "正文-" + docId, source, 1_000L, List.of("HIDDEN-" + docId));
            rt.advanceTo(Math.max(at, now + 1));
        }

        void read(String person, String docId, long at) throws Exception {
            long now = rt.snapshot().timeMillis();
            long start = Math.max(at, now + 1);
            rt.scheduleStartReading("rd-" + person + "-" + docId, "r-" + person + "-" + docId,
                    start, person, docId, 1);
            rt.advanceTo(start + 1_000L);
        }

        RoleView.ReadingRef ref(String person, String docId) {
            InformationModel.State info = rt.snapshot().information().orElseThrow().information();
            InformationModel.ReadObservation observation = null;
            for (InformationModel.ReadObservation item : info.observations()) {
                if (item.personPartyId().equals(person) && item.docId().equals(docId)) {
                    observation = item;
                }
            }
            assertNotNull(observation, "缺少真实经历：" + docId);
            List<String> ids = new ArrayList<>();
            for (InformationModel.Reading reading : info.active()) {
                if (reading.personPartyId().equals(person)) {
                    ids.add(reading.readId());
                }
            }
            for (InformationModel.ClosedReading closed : info.closed()) {
                if (closed.personPartyId().equals(person)) {
                    ids.add(closed.readId());
                }
            }
            ids.sort(Comparator.comparingLong(BeliefRuntimeTest::sequence));
            return new RoleView.ReadingRef(person, docId, 1, ids.indexOf(observation.readId()) + 1);
        }

        java.util.Map<String, RoleTradingRun.Report> tradingReports() {
            return rt.snapshot().trading().orElseThrow().reports();
        }

        InformationModel.DocumentVersion docOf(long ignored, String docId) {
            return rt.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(d -> d.docId().equals(docId)).findFirst().orElseThrow().at(1);
        }

        @Override
        public void close() {
            rt.close();
        }
    }

    /** 私有镜像内的测试目录：不使用系统 Temp，也不碰主树 target。 */
    private static Path newDir(String prefix) throws Exception {
        Path base = Path.of("target", "belief-runtime-tests");
        Files.createDirectories(base);
        return Files.createTempDirectory(base, prefix + "-");
    }

    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static long sequence(String readId) {
        return Long.parseLong(readId.substring("READ-".length()));
    }
}
