package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.BankActions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** 原生制造交付、五维评分、权限和冻结旧档对照，使用确定性角色回执。 */
public class IssuerPeriodEvidenceTest {
    @TempDir Path dir;
    private static final long DAY = 86_400_000L;
    private static final long END = 60 * DAY;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DELIVERY = "issuer-fact:DELIVERED_REVENUE:" + STRUCT + ":2";
    private static final String CREDIT = "issuer-fact:RECEIVABLES:" + STRUCT + ":2";
    private static final String CAPEX = "issuer-fact:EXPANSION_SPENDING:" + STRUCT + ":2";
    private static final String DIRECTOR = STRUCT_DIRECTORS.get(0);

    @Test void onlyRealOperatingResultsReachActualDirectorBallots() throws Exception {
        Path file = dir.resolve("v4.journal");
        try (var world = ComposedIndustryScenario.openCurrentTenCompanyStudy(file)) {
            govern(world);
            world.advanceTo(31 * DAY);
            operate(world, "parts", CompanyOperationsRun.Action.ORDER_MATERIAL, OperationsFixture.op().material("MAT-C", 6).build());
            var led = world.operationsRun().orElseThrow().company(STRUCT).ledger();
            world.advanceTo(led.purchases().values().iterator().next().arrivalAt());
            operate(world, "batch", CompanyOperationsRun.Action.START_BATCH, OperationsFixture.op().product("P1", 1, 3).build());
            world.advanceTo(world.operationsRun().orElseThrow().company(STRUCT).ledger().batches().values().iterator().next().completesAt());
            long now = world.snapshot().timeMillis();
            operate(world, "contract", CompanyOperationsRun.Action.SIGN_CONTRACT,
                    OperationsFixture.op().contract("sale", "builder-a", "P1", 1, 3, new BigDecimal("100.00"), now + DAY, now + 10 * DAY).build());
            world.advanceTo(now + DAY);
            operate(world, "delivery", CompanyOperationsRun.Action.DELIVER, OperationsFixture.op().delivery("sale", 3, "invoice").build());
            operate(world, "expansion", CompanyOperationsRun.Action.START_EXPANSION,
                    OperationsFixture.op().expansion("plant-2", new BigDecimal("2000.00"), 30 * DAY, 3, 50L).build());
            world.advanceTo(END);
            var periods = world.periodicOf(STRUCT).orElseThrow();
            var snap = periods.snapshots().get(1);
            assertFalse(periods.snapshots().get(0).evidenceRefs().stream().anyMatch(r -> r.startsWith("issuer-fact:")));
            assertTrue(snap.evidenceRefs().containsAll(List.of(DELIVERY, CREDIT, CAPEX, "disclosure:" + STRUCT + ":1:missed")));
            String cash = "account:" + world.operationsRun().orElseThrow().company(STRUCT).cashAccountId();
            var meeting = world.boardOfCompany(STRUCT).orElseThrow().board().meeting(snap.meetingId());
            for (int i = 0; i < 4; i++) world.scheduleCompanyBoardAttend("att" + i, "att-t" + i,
                    END + 100 + i, STRUCT, STRUCT_DIRECTORS.get(i), snap.meetingId());
            world.advanceTo(meeting.opensAt() + 1);
            assertTrue(world.periodicRoleView("omar", STRUCT).isEmpty());
            assertFalse(world.periodicRoleView(DIRECTOR, STRUCT).toString().contains(DELIVERY));
            periodic(world, "prepare", STRUCT_MANAGER, "PREPARE", List.of());
            var doc = world.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(d -> d.docId().equals(snap.materialDocId())).findFirst().orElseThrow().at(1);
            assertTrue(doc.body().contains(DELIVERY + " 本期实际交付确认收入（元）=300.00"), doc.body());
            assertTrue(doc.body().contains(CAPEX + " 本期实际支付扩建款（元）=2000.00"));
            var score = metric("OPERATING_COMMITMENT", DELIVERY, END);
            assertEquals("NOT_READ_EXACT_VERSION", periodic(world, "unread", DIRECTOR, "SCORE", List.of(score)));
            long read = world.snapshot().timeMillis() + 100;
            for (int i = 0; i < 3; i++) world.scheduleStartReading("read" + i, "read-t" + i, read + i,
                    STRUCT_DIRECTORS.get(i), snap.materialDocId(), 1);
            world.advanceTo(read + doc.readDurationMillis() - 1);
            assertFalse(world.periodicRoleView(DIRECTOR, STRUCT).toString().contains(DELIVERY));
            world.advanceTo(read + doc.readDurationMillis() + 3);
            assertTrue(world.periodicRoleView(DIRECTOR, STRUCT).toString().contains(DELIVERY));
            assertFalse(world.periodicRoleView(STRUCT_DIRECTORS.get(3), STRUCT).toString().contains(DELIVERY));
            assertEquals("EVIDENCE_NOT_ELIGIBLE", periodic(world, "fake", DIRECTOR, "SCORE",
                    List.of(metric("OPERATING_COMMITMENT", "batch:" + STRUCT + ":batch", END))));
            assertEquals("EVIDENCE_NOT_ELIGIBLE", periodic(world, "wrong-dimension", DIRECTOR, "SCORE",
                    List.of(metric("OPERATING_COMMITMENT", CREDIT, END))));
            assertEquals("EVIDENCE_NOT_ELIGIBLE", periodic(world, "wrong-time", DIRECTOR, "SCORE",
                    List.of(metric("OPERATING_COMMITMENT", DELIVERY, END - 1))));
            assertTrue(world.periodicOf(STRUCT).orElseThrow().ballots().isEmpty());
            long missedAt = periods.missed().get(0).missedAt();
            var metrics = List.of(score, metric("CASH_SOLVENCY", cash, END), metric("RISK", CREDIT, END),
                    metric("DISCLOSURE", "disclosure:" + STRUCT + ":1:missed", missedAt),
                    metric("LONG_TERM_INVESTMENT", CAPEX, END));
            for (int i = 0; i < 3; i++) scoreThroughDecision(world, STRUCT_DIRECTORS.get(i), "score" + i, metrics);
            assertEquals(3, world.periodicOf(STRUCT).orElseThrow().ballots().size());
            String resultId = "periodic-board-result:" + STRUCT + ":2";
            long publish = world.snapshot().timeMillis() + 1;
            world.schedulePublishDocument("other-doc", "other-doc-t", publish, resultId, "omar",
                    InformationModel.Audience.direct("omar"), "私人备忘", "独立的既有资料", "私人资料", 1000, List.of());
            world.advanceTo(meeting.closesAt() + 1);
            var closed = world.periodicOf(STRUCT).orElseThrow().scores().stream().filter(s -> s.period() == 2).findFirst().orElseThrow();
            assertEquals(3, closed.seatCount());
            assertEquals(0, closed.total().compareTo(new BigDecimal("70.00")));
            String actualResult = resultId + ":1";
            var version = world.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(d -> d.docId().equals(actualResult)).findFirst().orElseThrow().at(1);
            assertTrue(version.body().contains("已投席数=3 完整席数=3") && version.body().contains("加权总分=70.00"));
            assertFalse(version.body().contains(DIRECTOR), "定稿只给聚合数");
            assertTrue(world.canReadDocumentVersion(STRUCT_DIRECTORS.get(3), actualResult, 1), "无投票的合法董事仍可取得档案");
            assertFalse(world.canReadDocumentVersion(STRUCT_MANAGER, actualResult, 1), "经理未持本公司董事席");
            assertFalse(world.canReadDocumentVersion("omar", actualResult, 1));
            assertTrue(world.openRoleSession(DIRECTOR).view().profile().catalog().stream().anyMatch(r -> r.docId().equals(actualResult)));
            assertFalse(world.openRoleSession("omar").view().profile().catalog().stream().anyMatch(r -> r.docId().equals(actualResult)));
            var unreadResult = JSON.valueToTree(world.periodicRoleView(DIRECTOR, STRUCT)).at("/periods/1");
            assertEquals(actualResult, unreadResult.path("reviewDocId").asText());
            assertFalse(unreadResult.has("reviewResult"));
            assertFalse(JSON.valueToTree(world.periodicRoleView(STRUCT_MANAGER, STRUCT)).at("/periods/1").has("reviewDocId"));
            assertFalse(world.openRoleSession(DIRECTOR).canCite(actualResult, 1));
            long startsAt = world.snapshot().timeMillis() + 10;
            world.scheduleStartReading("final-read", "final-read-t", startsAt, DIRECTOR, actualResult, 1);
            world.advanceTo(startsAt + version.readDurationMillis());
            assertTrue(world.openRoleSession(DIRECTOR).canCite(actualResult, 1));
            var result = JSON.valueToTree(world.periodicRoleView(DIRECTOR, STRUCT)).at("/periods/1/reviewResult");
            assertEquals("COMPLETE", result.path("status").asText());
            assertEquals("70.00", result.path("total").asText());
            assertEquals(3, result.path("seatCount").asInt());
            var roleInput = world.autonomyRoleObservation(DIRECTOR);
            assertTrue(roleInput.findValues("reviewResult").stream().anyMatch(r ->
                    r.path("total").asText().equals("70.00") && r.path("seatCount").asInt() == 3));
            assertFalse(JSON.valueToTree(world.periodicRoleView(STRUCT_DIRECTORS.get(3), STRUCT)).at("/periods/1").has("reviewResult"));
            assertTrue(world.snapshot().information().orElseThrow().information().observations().stream().anyMatch(r ->
                    r.personPartyId().equals(DIRECTOR) && r.docId().equals(actualResult) && r.body().equals(version.body())));
            world.advanceTo(135 * DAY);
            assertFalse(new PeriodicWorldAccess(world).recallEligible(DIRECTOR, actualResult, 1, world.snapshot().timeMillis()));
            assertFalse(JSON.valueToTree(world.periodicRoleView(DIRECTOR, STRUCT)).at("/periods/1").has("reviewResult"));
            assertFalse(world.periodicRoleView(DIRECTOR, STRUCT).toString().contains(DELIVERY), "遗忘后旧期证据不能继续充当个人当前知识");
            var later = world.periodicOf(STRUCT).orElseThrow().snapshots().get(2);
            assertFalse(later.evidenceRefs().stream().anyMatch(r -> r.startsWith("issuer-fact:DELIVERED_REVENUE:")));
            assertTrue(later.evidenceRefs().contains("issuer-fact:RECEIVABLES:" + STRUCT + ":3"));
            assertFalse(later.evidenceRefs().contains("issuer-fact:EXPANSION_SPENDING:" + STRUCT + ":3"));
        }
        long size = Files.size(file);
        try (var world = ComposedIndustryScenario.openCurrentTenCompanyStudy(file)) {
            assertEquals(3, world.periodicOf(STRUCT).orElseThrow().ballots().size());
        }
        assertEquals(size, Files.size(file));
    }

    @Test void approvedUndrawnBankLoanIsDistinctFromDisbursementAndClosingExposure() throws Exception {
        Path file = dir.resolve("bank.journal");
        try (var w = ComposedIndustryScenario.openCurrentTenCompanyStudy(file)) {
            govern(w, "north", List.of("alice", "bob", "carol", "dave"), "erin", false);
            for (var kind : List.of(BankActions.Kind.LOAN_REQUEST, BankActions.Kind.LOAN_ACCEPT, BankActions.Kind.LOAN_APPROVE)) {
                String person = kind == BankActions.Kind.LOAN_APPROVE ? "erin" : "investor";
                loan(w, person, kind);
            }
            assertEquals(BankActions.LoanStatus.APPROVED, w.bankBook().loan("ln-req").status());
            w.advanceTo(30 * DAY);
            var first = w.periodicOf("north").orElseThrow().snapshots().get(0);
            assertFalse(first.evidenceRefs().contains("issuer-fact:ORIGINATED_LOANS:north:1"));
            loan(w, "investor", BankActions.Kind.LOAN_DRAW);
            assertEquals(BankActions.LoanStatus.DRAWN, w.bankBook().loan("ln-req").status());
            w.advanceTo(END);
            var second = w.periodicOf("north").orElseThrow().snapshots().get(1);
            assertTrue(second.evidenceRefs().containsAll(List.of("issuer-fact:ORIGINATED_LOANS:north:2", "issuer-fact:LOAN_EXPOSURE:north:2")));
            assertTrue(w.periodicStatementForTest("north", 2).contains("本期具名合同实际发放本金（元）=200.00"));
            w.advanceTo(90 * DAY);
            var third = w.periodicOf("north").orElseThrow().snapshots().get(2);
            assertFalse(third.evidenceRefs().contains("issuer-fact:ORIGINATED_LOANS:north:3"));
            assertTrue(third.evidenceRefs().contains("issuer-fact:LOAN_EXPOSURE:north:3"));
        }
        try (var w = ComposedIndustryScenario.openCurrentTenCompanyStudy(file)) {
            assertEquals(BankActions.LoanStatus.DRAWN, w.bankBook().loan("ln-req").status());
        }
    }

    private static void loan(WorldRuntime w, String who, BankActions.Kind kind) throws Exception {
        String id = kind.name();
        var draft = new WorldRoleSession.BankActionDraft(id, kind, "north", "investor", new BigDecimal("200.00"),
                20_000, 300 * DAY, "跨期测试", "", "", "ln-req");
        long due = w.snapshot().timeMillis() + 3;
        w.openRoleSession(who).bankAction(id, id, due, 1, draft);
        w.advanceTo(due);
        assertEquals("OK", w.taskCommitResult(WorldRuntime.roleTaskId(who, id) + ":finish"), id);
    }

    @Test void v3RetainsHistoricalEvidenceAndInputsOnReopen() throws Exception {
        Path file = dir.resolve("old.journal");
        try (var world = ComposedIndustryScenario.openStudyTenCompanyAutonomy(file)) {
            assertFalse(world.profile().manifest().dataVersions().containsKey(RunManifest.ISSUER_PERIOD_METRICS));
            world.advanceTo(30 * DAY);
            assertTrue(world.periodicRoleView(DIRECTOR, STRUCT).toString().contains("account:"));
            assertFalse(world.periodicOf("north").orElseThrow().snapshots().get(0).evidenceRefs().stream().anyMatch(r -> r.startsWith("issuer-fact:")));
        }
        byte[] before = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        try (var world = ComposedIndustryScenario.openCurrentTenCompanyStudy(file)) {
            assertFalse(world.profile().manifest().dataVersions().containsKey(RunManifest.ISSUER_PERIOD_METRICS));
            assertTrue(world.periodicRoleView(DIRECTOR, STRUCT).toString().contains("account:"));
        }
        assertArrayEquals(before, java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** 经标准开局的实际董事会任命结构制造经理、批准预算，供治理与网页联验使用。 */
    public static void govern(WorldRuntime w) throws Exception {
        govern(w, STRUCT, STRUCT_DIRECTORS, STRUCT_MANAGER, true);
    }

    private static void govern(WorldRuntime w, String company, List<String> directors, String manager, boolean budget) throws Exception {
        String chair = directors.get(0);
        String source = CompanyOpeningDocuments.documentId(company);
        for (int i = 0; i < 4; i++) w.scheduleStartReading("opening" + i, "opening-t" + i, 100 + i, directors.get(i), source, 1);
        w.advanceTo(DAY);
        for (boolean appointment : budget ? List.of(false, true) : List.of(true)) {
            String id = appointment ? "appointment" : "budget";
            long t = w.snapshot().timeMillis() + 10;
            w.scheduleCompanyBoardConvene(id, id + "-task", t, company, chair, id, t + 50, t + 100, t + 3000, appointment);
            for (int i = 0; i < 4; i++) w.scheduleCompanyBoardAttend(id+"a"+i, id+"a-t"+i, t+10+i, company, directors.get(i), id);
            w.scheduleCompanyBoardMaterial(id+"m", id+"mt", t+30, company, chair, id, "m1", source, 1);
            if (appointment) w.scheduleCompanyBoardAppointmentProposal("appoint-proposal", "appoint-proposal-t", t+40, company, chair, id,
                    new BoardModel.AppointmentRequest("real-manager", manager, company, RoleAccess.Role.COMPANY_MANAGER,
                            BoardModel.AppointmentAction.APPOINT, t+6000, 90 * DAY));
            else w.scheduleCompanyBoardBudgetProposal("budget-proposal","budget-proposal-t",t+40,company,chair,id,
                    List.of(new BoardModel.BudgetLine(1, BoardModel.BudgetCategory.PROCUREMENT, new BigDecimal("10000.00")),
                            new BoardModel.BudgetLine(1, BoardModel.BudgetCategory.PRODUCTION_LABOR, new BigDecimal("10000.00"))));
            for (int i=0; i<4; i++) w.scheduleCompanyBoardVote(id+"v"+i, id+"v-t"+i,t+2000+i,company,directors.get(i),id,BoardModel.Vote.YES,1000);
            w.scheduleCompanyBoardImplement(id+"impl",id+"impl-t",t+4000,company,chair,id);
            w.advanceTo(t+6000);
        }
        assertTrue(w.isActiveManager(company, manager, w.snapshot().timeMillis()));
    }

    private static void operate(WorldRuntime w, String id, CompanyOperationsRun.Action action, CompanyOperationsRun.Fields fields) throws Exception {
        long t = w.snapshot().timeMillis()+2000;
        w.scheduleOperationsAction(id,id+"-t",t,STRUCT,STRUCT_MANAGER,action.name(),fields,1000);
        w.advanceTo(t);
        var report = w.operationsRun().orElseThrow().report(id+"-t:finish");
        assertTrue(report.accepted(), report.reason());
    }

    private static PeriodicActionPayload.PeriodicMetric metric(String dim, String ref, long at) {
        return new PeriodicActionPayload.PeriodicMetric(dim,new BigDecimal("70.00"),ref,at);
    }

    private static String periodic(WorldRuntime w, String id, String person, String action, List<PeriodicActionPayload.PeriodicMetric> metrics) throws Exception {
        long t = w.snapshot().timeMillis()+2000;
        w.schedulePeriodicAction(id,id+"-t",t,STRUCT,person,action,2,"",0,"",0,"",metrics,"",1000);
        w.advanceTo(t);
        return w.taskResultForTest(id+"-t:finish");
    }

    private static void scoreThroughDecision(WorldRuntime w, String person, String id, List<PeriodicActionPayload.PeriodicMetric> metrics) throws Exception {
        w.beginDecisionSession("session-"+id);
        var route = new DecisionModel.Route("governance","key-1","deepseek-flash",1,60,1_000_000,1024,1,"lifecycle-v1",null);
        w.openDecisions("open-"+id,List.of(new DecisionRun.Request(person,id,"wake",route)));
        var call = w.dispatchLiveDecision("dispatch-"+id,id).call();
        assertNotNull(call);
        assertTrue(call.inputText().contains("periodic.score"));
        assertTrue(call.inputText().contains(DELIVERY));
        var body = JSON.writeValueAsString(Map.of("tool","periodic.score","args", Map.of("companyPartyId",STRUCT,"period",2,"metrics",metrics),
                "decision",Map.of("objective","director:" + STRUCT, "basis","本人已阅读该期董事材料","reviewAfterMillis",60000)));
        var reply = new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),
                body,DecisionModel.Usage.unknownCost(1,60,20),"fixture:director");
        assertEquals(WorldRuntime.CompletionOffer.QUEUED,w.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
        assertEquals(1,w.drainDecisionCallbacks());
        var action = w.snapshot().decisions().orElseThrow().actionRef(id);
        assertNotNull(action, () -> w.snapshot().decisions().orElseThrow().state().decisions().stream()
                .filter(d -> d.actionId().equals(id)).findFirst().orElseThrow().toString());
        w.advanceTo(action.dueMillis());
        var finish = w.snapshot().pending().stream().filter(task -> task.taskId().equals(action.taskId()+":finish"))
                .findFirst().orElseThrow(() -> new AssertionError(w.taskResultForTest(action.taskId())));
        w.advanceTo(finish.dueTimeMillis());
        assertEquals("ACCEPTED",w.taskResultForTest(finish.taskId()));
    }
}
