package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.BankActions;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static edu.cufe.auction.world.DigitalIndustryConfig.*;
import static edu.cufe.auction.world.DigitalOperations.Action.*;
import static edu.cufe.auction.world.composed.BaselineIndustryWorldTest.*;
import static edu.cufe.auction.world.composed.ComposedIndustryScenario.*;
import static org.junit.jupiter.api.Assertions.*;

public class DigitalCessationTest {
    @TempDir Path dir;

    static DigitalCompanyRun digitalRun(WorldRuntime world) {
        return world.operationsRun().orElseThrow().digital();
    }

    private static void appoint(WorldRuntime world, String company, String manager, String prefix) throws Exception {
        meeting(world, company, prefix + "-budget-meeting", BoardModel.Topic.BUDGET, manager, 4);
        meeting(world, company, prefix + "-appointment", BoardModel.Topic.MANAGER_APPOINTMENT, manager, 4);
        assertTrue(world.isActiveManager(company, manager, world.snapshot().timeMillis()));
    }

    private static void vote(WorldRuntime world, String meeting, int yes) throws Exception {
        meeting(world, COMPANY, meeting, BoardModel.Topic.DIGITAL_CESSATION, MANAGER_PERSON, yes);
    }

    private static void meeting(WorldRuntime world, String company, String meeting, BoardModel.Topic topic,
                                String manager, int yes) throws Exception {
        var members = BaselineIndustryConfig.directors(company).subList(0, 4);
        String doc = meeting + "-terms";
        long now = world.snapshot().timeMillis();
        String terms = switch (topic) {
            case BUDGET -> "本公司第0和第1期生产人工预算各6000元，采购预算各3000元。";
            case MANAGER_APPOINTMENT -> "任命 " + manager + " 为本公司经理，任期90实验日。";
            default -> DigitalDecisionTools.CESSATION_TERMS;
        };
        world.schedulePublishDocument(doc, doc, now + 10, doc, members.get(0), InformationModel.Audience.publicAudience(),
                company + " 会议材料", topic.name(), terms, 10, List.of());
        for (int i = 0; i < members.size(); i++)
            world.scheduleStartReading(doc + "-read-" + i, doc + "-read-" + i, now + 40 + i, members.get(i), doc, 1);
        long start = now + 120_000;
        world.advanceTo(start - 2000);
        for (var member : members) assertTrue(InformationModel.observations(world.snapshot().information().orElseThrow().information(), member)
                .stream().anyMatch(o -> doc.equals(o.docId())), member);
        world.scheduleCompanyBoardConvene(meeting, meeting, start, company, members.get(0), meeting,
                start + 200, start + 500, start + 4000, topic);
        for (int i = 0; i < members.size(); i++) {
            String id = meeting + "-" + i;
            world.scheduleCompanyBoardAttend(id + "-attend", id + "-attend", start + 50 + i, company, members.get(i), meeting);
            world.scheduleCompanyBoardVote(id + "-vote", id + "-vote", start + 2000 + i, company, members.get(i), meeting,
                    i < yes ? BoardModel.Vote.YES : BoardModel.Vote.NO, 1000);
        }
        world.scheduleCompanyBoardMaterial(meeting + "-material", meeting + "-material", start + 140, company,
                members.get(0), meeting, "terms", doc, 1);
        if (topic == BoardModel.Topic.BUDGET) {
            var lines = new java.util.ArrayList<BoardModel.BudgetLine>();
            for (long period : List.of(0L, 1L)) {
                lines.add(new BoardModel.BudgetLine(period, BoardModel.BudgetCategory.PRODUCTION_LABOR, new BigDecimal("6000.00")));
                lines.add(new BoardModel.BudgetLine(period, BoardModel.BudgetCategory.PROCUREMENT, new BigDecimal("3000.00")));
            }
            world.scheduleCompanyBoardBudgetProposal(meeting + "-propose", meeting + "-propose", start + 150,
                    company, members.get(0), meeting, lines);
        } else if (topic == BoardModel.Topic.MANAGER_APPOINTMENT) {
            world.scheduleCompanyBoardAppointmentProposal(meeting + "-propose", meeting + "-propose", start + 150,
                    company, members.get(0), meeting, new BoardModel.AppointmentRequest(meeting + "-office", manager, company,
                    RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT, start + 6000, start + 6000 + 90 * DAY));
        }
        if (topic != BoardModel.Topic.DIGITAL_CESSATION)
            world.scheduleCompanyBoardImplement(meeting + "-implement", meeting + "-implement", start + 4500,
                    company, members.get(0), meeting);
        world.advanceTo(start + 6000);
        var result = world.boardOfCompany(company).orElseThrow().board().meeting(meeting);
        assertEquals(4, result.ballots().size());
        if (topic == BoardModel.Topic.DIGITAL_CESSATION)
            assertEquals(yes == 4 ? BoardModel.MeetingStatus.ADOPTED : BoardModel.MeetingStatus.REJECTED, result.status());
    }

    private static String denied(WorldRuntime world, String id, DigitalOperations.Action action, String... args) throws Exception {
        long due = world.snapshot().timeMillis() + 2000;
        world.openRoleSession(MANAGER_PERSON).digitalAction(id, id + "-task", due, COMPANY,
                new DigitalOperations.Request(action, fields(args)), DigitalDecisionTools.ACTION_MILLIS);
        world.advanceTo(due);
        var report = world.operationsRun().orElseThrow().report(id + "-task:finish");
        assertFalse(report.accepted());
        return report.reason();
    }

    private static void moveCash(WorldRuntime world, String id, BankActions.Kind kind, BigDecimal amount) throws Exception {
        long due = world.snapshot().timeMillis() + 100;
        world.openRoleSession(MANAGER_PERSON).bankAction(id, id + "-task", due, 1,
                new WorldRoleSession.BankActionDraft(id, kind, "north", COMPANY, amount, 0, 0, "", "", "", ""));
        world.advanceTo(due);
    }

    /** 通过角色实际任命、审批、签约及执行建立有存量合同的停业样本。 */
    public static void windDownWithOldContracts(WorldRuntime world) throws Exception {
        appoint(world, COMPANY, MANAGER_PERSON, "digital");
        digital(world, "pilot", BEGIN_PILOT);
        world.advanceTo(digitalRun(world).ledger().stageOf(DigitalServiceModel.Stage.PILOT).completesAt());
        digital(world, "validation", BEGIN_VALIDATION);
        world.advanceTo(digitalRun(world).ledger().stageOf(DigitalServiceModel.Stage.VALIDATION).completesAt());
        digital(world, "permit", APPROVE_COMMERCIAL, "approvalId", APPROVAL_COMMERCIAL);
        digital(world, "sign-prepaid", SIGN_CONTRACT, "contractId", "prepaid", "quantityUnits", "100", "prepaidCents", "500000");
        digital(world, "sign-credit", SIGN_CONTRACT, "contractId", "credit", "quantityUnits", "2", "prepaidCents", "0");
        vote(world, "digital-wind-down", 4);
        assertNull(digitalRun(world).cessation());
        var managerView = world.autonomyRoleObservation(MANAGER_PERSON).at("/engineering/digitalOperations");
        assertTrue(managerView.path("cessationDecisions").toString().contains("digital-wind-down"));
        assertTrue(managerView.path("actionFields").has("EXIT_BUSINESS"));
        var before = world.snapshot().accounts();
        digital(world, "implement-cessation", EXIT_BUSINESS, "meetingId", "digital-wind-down");
        assertEquals(before, world.snapshot().accounts());
    }

    @Test void oldDeliveryAndPartialRefundSurviveCashShortageAndStrictReopening() throws Exception {
        var path = dir.resolve("digital-contracts.journal");
        List<SecuritiesLedger.Lot> shares;
        long due;
        try (var w = openDigitalWindDownTenCompanyStudy(path)) {
            shares = w.snapshot().securities().orElseThrow().lots();
            windDownWithOldContracts(w);
            var view = w.autonomyRoleObservation(MANAGER_PERSON).at("/engineering/digitalOperations");
            assertEquals("digital-wind-down", view.at("/cessation/meetingId").asText());
            assertFalse(view.path("actionFields").has("SIGN_CONTRACT"));
            assertFalse(view.path("actionFields").has("BEGIN_PILOT"));
            assertTrue(view.path("actionFields").has("DELIVER"));
            assertFalse(w.autonomyRoleObservation("investor").at("/engineering").has("digitalOperations"));
            assertEquals("DIGITAL_INTAKE_CLOSED", denied(w, "resign", SIGN_CONTRACT, "contractId", "new", "quantityUnits", "1", "prepaidCents", "0"));
            assertEquals("DIGITAL_INTAKE_CLOSED", denied(w, "retry-pilot", BEGIN_PILOT));
            assertEquals("DIGITAL_INTAKE_CLOSED", denied(w, "repeat-exit", EXIT_BUSINESS, "meetingId", "digital-wind-down"));
            digital(w, "deliver-prepaid", DELIVER, "contractId", "prepaid", "units", "40", "receivableId", "earned");
            digital(w, "deliver-credit", DELIVER, "contractId", "credit", "units", "2", "receivableId", "old-ar");
            digital(w, "terminate-old", TERMINATE, "contractId", "prepaid");
            assertEquals(new BigDecimal("3000.00"), digitalRun(w).ledger().refundObligation());
            assertEquals(new BigDecimal("4200.00"), ComposedCompanyReport.of(w, COMPANY).income().get("服务收入（累计）"));
            moveCash(w, "deposit", BankActions.Kind.DEPOSIT, cash(w, COMPANY_ACCOUNT));
            assertEquals(new BigDecimal("0.00"), cash(w, COMPANY_ACCOUNT));
            var accounts = w.snapshot().accounts();
            assertEquals(DigitalServiceModel.INSUFFICIENT_CASH, denied(w, "cash-short", REFUND, "contractId", "prepaid", "refundCents", "300000"));
            assertEquals(accounts, w.snapshot().accounts());
            assertEquals(new BigDecimal("3000.00"), digitalRun(w).ledger().contracts().get("prepaid").unearned());
            due = digitalRun(w).ledger().receivables().get("old-ar").dueAt();
        }
        byte[] closed = Files.readAllBytes(path);
        try (var w = openDigitalWindDownTenCompanyStudy(path)) {
            assertEquals("digital-wind-down", digitalRun(w).cessation().meetingId());
            assertEquals(new BigDecimal("3000.00"), digitalRun(w).ledger().refundObligation());
        }
        assertArrayEquals(closed, Files.readAllBytes(path));
        try (var w = openDigitalWindDownTenCompanyStudy(path)) {
            w.advanceTo(due);
            BigDecimal customer = cash(w, CUSTOMER_ACCOUNT);
            digital(w, "collect-earned", COLLECT, "receivableId", "earned");
            digital(w, "collect-old", COLLECT, "receivableId", "old-ar");
            assertEquals(new BigDecimal("2200.00"), cash(w, COMPANY_ACCOUNT));
            assertEquals(customer.subtract(new BigDecimal("2200.00")), cash(w, CUSTOMER_ACCOUNT));
            digital(w, "refund-part", REFUND, "contractId", "prepaid", "refundCents", "10000");
            assertEquals(new BigDecimal("2900.00"), digitalRun(w).ledger().refundObligation());
            moveCash(w, "withdraw", BankActions.Kind.WITHDRAW, new BigDecimal("800.00"));
            digital(w, "refund-rest", REFUND, "contractId", "prepaid", "refundCents", "290000");
            assertEquals(new BigDecimal("0.00"), digitalRun(w).ledger().refundObligation());
            assertEquals(customer.add(new BigDecimal("800.00")), cash(w, CUSTOMER_ACCOUNT));
            assertEquals(shares, w.snapshot().securities().orElseThrow().lots());
            assertTrue(ComposedCompanyReport.of(w, COMPANY).balanced());
            appoint(w, AgedIndustryConfig.COMPANY, AgedIndustryConfig.MANAGER_PERSON, "other");
            aged(w, "other-order", AgedOperations.Action.ORDER_RAW, "orderId", "raw", "units", "1");
        }
    }

    @Test void noBorrowedAuthorityAndOldWorldRetainsItsOwnActions() throws Exception {
        var path = dir.resolve("old.journal");
        var directors = BaselineIndustryConfig.directors(COMPANY);
        try (var old = openMeetingTenCompanyStudy(path)) {
            assertFalse(old.digitalCessation(COMPANY));
            assertThrows(IllegalArgumentException.class, () -> old.scheduleCompanyBoardConvene("old", "old", 2000,
                    COMPANY, directors.get(0), "old", 2100, 2600, 6000, BoardModel.Topic.DIGITAL_CESSATION));
            assertFalse(old.autonomyRoleObservation(directors.get(0)).toString().contains("DIGITAL_CESSATION"));
        }
        byte[] bytes = Files.readAllBytes(path);
        try (var old = openMeetingTenCompanyStudy(path)) { assertNull(digitalRun(old).cessation()); }
        assertArrayEquals(bytes, Files.readAllBytes(path));
        try (var w = openDigitalWindDownTenCompanyStudy(dir.resolve("no-authority.journal"))) {
            assertTrue(w.autonomyRoleObservation(directors.get(0)).toString().contains("DIGITAL_CESSATION"));
            assertThrows(IllegalArgumentException.class, () -> w.scheduleCompanyBoardConvene("wrong-chair", "wrong-chair", 2000,
                    COMPANY, directors.get(1), "x", 2100, 2600, 6000, BoardModel.Topic.DIGITAL_CESSATION));
            assertThrows(IllegalArgumentException.class, () -> w.scheduleCompanyBoardConvene("wrong-issuer", "wrong-issuer", 2000,
                    RESOURCE, ComposedIndustryScenario.DIRECTORS.get(0), "x", 2100, 2600, 6000, BoardModel.Topic.DIGITAL_CESSATION));
            appoint(w, COMPANY, MANAGER_PERSON, "manager");
            vote(w, "only-three", 3);
            assertEquals("DIGITAL_CESSATION_NOT_AUTHORIZED", denied(w, "not-adopted", EXIT_BUSINESS, "meetingId", "only-three"));
            assertEquals("DIGITAL_CESSATION_NOT_AUTHORIZED", denied(w, "unrelated-meeting", EXIT_BUSINESS, "meetingId", "manager-budget-meeting"));
            assertNull(digitalRun(w).cessation());
            digital(w, "still-open", BEGIN_PILOT);
            long completion = digitalRun(w).ledger().stageOf(DigitalServiceModel.Stage.PILOT).completesAt();
            vote(w, "new-vote", 4);
            var args = IssuerCreditDecisionTest.JSON.createObjectNode().put("action", "EXIT_BUSINESS");
            args.putObject("arguments").put("meetingId", "new-vote");
            var placed = IssuerCreditDecisionTest.decide(w, MANAGER_PERSON, "actual-tool", DigitalDecisionTools.ACT, args);
            assertNotNull(placed);
            w.advanceTo(placed.dueMillis() + DigitalDecisionTools.ACTION_MILLIS);
            assertEquals("new-vote", digitalRun(w).cessation().meetingId());
            w.advanceTo(completion);
            assertEquals(DigitalServiceModel.StageStatus.PASSED, digitalRun(w).ledger().stageOf(DigitalServiceModel.Stage.PILOT).status());
            assertEquals("DIGITAL_INTAKE_CLOSED", denied(w, "no-new-validation", BEGIN_VALIDATION));
        }
    }
}

