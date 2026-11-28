package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import edu.cufe.auction.world.composed.CompanyOpeningDocuments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 同一开局分别检验真实知悉、公司财报的正式发布和基金、本人报单执行边界。 */
class MaterialTradingTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BANK = "north", STOCK = "BNK1", MANAGER = "nina", FUND = "ACC-FUND";
    private static final String BLOCKED = "ISSUER_REPORT_AWAITING_DISCLOSURE";
    private static final long DAY = 86_400_000L;
    private static final List<String> DIRECTORS = List.of("alice", "bob", "carol");
    private static final BigDecimal PRICE = new BigDecimal("15.00");

    static void prepare(Path file, boolean current, boolean otherMandate) throws Exception {
        try (var w = current ? ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)
                : ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            String charter = CompanyOpeningDocuments.documentId(BANK);
            for (String director : DIRECTORS) read(w, director, charter);
            long t = now(w) + 10;
            String meeting = "appointment";
            w.scheduleCompanyBoardConvene("open", "open", t, BANK, "alice", meeting, t+50, t+100, t+3000, true);
            for (int i = 0; i < DIRECTORS.size(); i++) {
                String id = "attend-" + i;
                w.scheduleCompanyBoardAttend(id, id, t+10+i, BANK, DIRECTORS.get(i), meeting);
            }
            w.scheduleCompanyBoardMaterial("charter", "charter", t+30, BANK, "alice", meeting, "material", charter, 1);
            w.scheduleCompanyBoardAppointmentProposal("propose", "propose", t+40, BANK, "alice", meeting,
                    new BoardModel.AppointmentRequest("bank-ceo", MANAGER, BANK, RoleAccess.Role.COMPANY_MANAGER,
                            BoardModel.AppointmentAction.APPOINT, t+6000, 90*DAY));
            for (int i = 0; i < DIRECTORS.size(); i++) {
                String id = "vote-" + i;
                w.scheduleCompanyBoardVote(id, id, t+2000+i, BANK, DIRECTORS.get(i), meeting, BoardModel.Vote.YES, 1000);
            }
            w.scheduleCompanyBoardImplement("implement", "implement", t+4000, BANK, "alice", meeting);
            w.advanceTo(t+6000);
            assertTrue(w.isActiveManager(BANK, MANAGER, now(w)));
            w.advanceTo(PeriodicModel.PERIOD_MILLIS);
            var period = w.periodicOf(BANK).orElseThrow().snapshots().get(0);
            var board = w.boardOfCompany(BANK).orElseThrow().board().meeting(period.meetingId());
            for (String director : DIRECTORS) {
                String id = "review-seat-"+director;
                w.scheduleCompanyBoardAttend(id,id,now(w)+2000, BANK, director, period.meetingId());
            }
            w.advanceTo(board.opensAt()+2000);
            periodic(w, MANAGER, "PREPARE", null, "", "", "");
            var draft = first(w);
            assertEquals(period.materialDocId(), draft.docId());
            long day = now(w)/DAY;
            while (day%7 >= 5 || day*DAY+34_500_000L < now(w)) day++;
            w.advanceTo(day*DAY+34_500_000L);
            String mandate = "fund-mandate:cufe:fund-a";
            read(w, "alice", mandate);
            authorize(w, STOCK, PRICE, mandate);
            if (otherMandate) authorize(w, "DIGITAL", BigDecimal.TEN, mandate);
            assertTrue(w.snapshot().exchange().orElseThrow().orders().isEmpty());
        }
    }

    private static void authorize(WorldRuntime w, String symbol, BigDecimal price, String mandate) throws Exception {
        String research = "research-"+symbol, note = "note-"+symbol, target = "target-"+symbol;
        long t = now(w)+1;
        w.openRoleSession("alice").submitResearch(research,research,t,new WorldRoleSession.ResearchDraft(research,
                "cufe","fund-a",symbol,note,"受控研究","核对产品授权及可用账户。",List.of(new WorldRoleSession.Citation(mandate,1))));
        w.advanceTo(t);
        read(w,"bob",note);
        long held = w.roleView("bob").holdings().stream().filter(row -> FUND.equals(row.accountId()) && symbol.equals(row.symbol()))
                .mapToLong(RoleView.Holding::quantity).sum();
        t = now(w)+1;
        long until = t + 2*DAY;
        w.openRoleSession("bob").setTarget(target,target,t,new WorldRoleSession.TargetDraft(target,0,"cufe","fund-a",FUND,
                symbol,held+4,price,until,research));
        w.advanceTo(t);
        String risk = "risk-"+symbol;
        w.openRoleSession("carol").reviewTarget(risk,risk,++t,new WorldRoleSession.ReviewDraft(target,1,true,4,4,price,until,"限定总量和单价"));
        w.advanceTo(t);
        String approved = "confirm-"+symbol;
        w.openRoleSession("bob").confirmTarget(approved,approved,++t,target,1,1);
        w.advanceTo(t);
        assertEquals("OK", w.openRoleSession("bob").view().notices().stream()
                .filter(row -> approved.equals(row.clientCommandId())).findFirst().orElseThrow().reason());
    }

    private static long now(WorldRuntime w) { return w.snapshot().timeMillis(); }

    static void read(WorldRuntime w, String actor, String doc) throws Exception {
        long t=now(w)+1;
        String id="read-"+actor+"-"+t;
        w.openRoleSession(actor).startReading(id,id,t,doc,1);
        w.advanceTo(t);
        w.advanceTo(w.roleView(actor).readings().stream().filter(row -> doc.equals(row.ref().docId())).findFirst().orElseThrow().dueAt());
        assertTrue(w.canCite(actor,doc,1));
    }

    static PeriodicModel.Draft first(WorldRuntime w) { return w.periodicOf(BANK).orElseThrow().drafts().get(0); }

    private static void periodic(WorldRuntime w,String actor,String action,PeriodicModel.Draft draft,
                                 String publisher,String grant,String note) throws Exception {
        long t=now(w)+2_000;
        String id=action+"-"+t;
        w.schedulePeriodicAction(id,id,t,BANK,actor,action,1,draft==null?"":draft.draftId(),draft==null?0:draft.version(),
                publisher,publisher.isEmpty()?0:t+DAY,grant,List.of(),note,1000);
        w.advanceTo(t);
    }

    private static void publish(WorldRuntime w, PeriodicModel.Draft draft) throws Exception {
        for (String director : DIRECTORS) {
            read(w,director,draft.docId());
            periodic(w,director,"APPROVE",draft,"","","");
        }
        periodic(w,MANAGER,"AUTHORIZE",draft,"alice","","");
        var grant=w.periodicOf(BANK).orElseThrow().authorizations().stream()
                .filter(auth -> auth.draftId().equals(draft.draftId())).findFirst().orElseThrow();
        periodic(w,"alice","PUBLISH",draft,"",grant.authorizationId(),"");
        assertTrue(w.periodicOf(BANK).orElseThrow().releases().stream().anyMatch(r -> draft.bodyDigest().equals(r.bodyDigest())));
    }

    static JsonNode restricted(WorldRuntime w, String actor) throws Exception {
        return w.autonomyRoleObservation(actor).at("/engineering/materialTrading/restrictions");
    }

    private static void child(WorldRuntime w, String symbol, String id, String reason) throws Exception {
        var price=STOCK.equals(symbol)?PRICE:BigDecimal.TEN;
        var session=w.openRoleSession("dave");
        long t=now(w)+1;
        session.submitChild(id,id,t,new WorldRoleSession.ChildDraft("target-"+symbol,1,1,id,1,price));
        w.advanceTo(t);
        assertEquals(reason,session.view().notices().stream().filter(row -> id.equals(row.clientCommandId())).findFirst().orElseThrow().reason());
    }

    @Test void personalMarketAndLimitOrdersUseTheSameRuleInDecisionsAndOnTheTakeoverDesk() throws Exception {
        Path file=directory.resolve("personal.journal");
        prepare(file,true,false);
        BranchProfile profile;
        String childId="material-personal";
        try(var w=ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)) {
            var sent=AutonomousIssuerDividendTest.call(w,MANAGER,"personal-market");
            var input=JSON.readTree(sent.inputText());
            assertEquals(STOCK,input.at("/engineering/materialTrading/restrictions/0/symbol").asText());
            assertTrue(sent.allowedTools().contains(PersonalMarket.MARKET));
            var args=JSON.createObjectNode().put("accountId","HOUSE-NINA").put("symbol",STOCK).put("side","SELL").put("quantity",1);
            var ref=AutonomousIssuerDividendTest.reply(w,sent,MANAGER,PersonalMarket.MARKET,args);
            assertNotNull(ref);
            w.advanceTo(ref.dueMillis());
            assertEquals(BLOCKED,w.snapshot().exchange().orElseThrow().reports().get(ref.taskId()).reason());
            w.createBranch(childId,w.snapshot().position(),BranchOrigin.Kind.GOD_TAKEOVER,"核对本人财报限制");
            profile=w.profile();
        }
        try(var host=new WorldWebHost(() -> {
            var w=ComposedIndustryScenario.openLoanLossTenCompanyStudy(file);
            return new WorldWebHost.Opened(w,w.profile().manifest().runId());
        })) {
            var page=JSON.valueToTree(host.personPage(MANAGER).personalResearch());
            assertEquals(STOCK,page.at("/materialTrading/restrictions/0/symbol").textValue());
            assertTrue(JSON.valueToTree(host.personPage("investor").personalResearch()).at("/materialTrading/restrictions").isEmpty());
        }
        var repo=new BranchRepository(directory.resolve("runs"));
        try(var w=repo.open(childId,profile)) {
            var rejected=PersonalOrderDesk.schedule(w,MANAGER,now(w),"private",
                    new PersonalOrderDesk.Instruction("LIMIT","HOUSE-NINA",STOCK,Side.BUY,PRICE,1,null));
            w.advanceTo(Long.parseLong(rejected.get("dueTimeMillis")));
            assertEquals(BLOCKED,PersonalOrderDesk.view(w,MANAGER).at("/receipts/0/reason").asText());
            var ok=PersonalOrderDesk.schedule(w,MANAGER,now(w),"elsewhere",
                    new PersonalOrderDesk.Instruction("LIMIT","HOUSE-NINA","DIGITAL",Side.BUY,BigDecimal.TEN,1,null));
            w.advanceTo(Long.parseLong(ok.get("dueTimeMillis")));
            assertTrue(PersonalOrderDesk.view(w,MANAGER).at("/receipts/0/accepted").asBoolean());
            publish(w,first(w));
            var released=PersonalOrderDesk.schedule(w,MANAGER,now(w),"published",
                    new PersonalOrderDesk.Instruction("LIMIT","HOUSE-NINA",STOCK,Side.BUY,PRICE,1,null));
            w.advanceTo(Long.parseLong(released.get("dueTimeMillis")));
            assertTrue(PersonalOrderDesk.view(w,MANAGER).at("/receipts/0/accepted").asBoolean());
        }
    }

    @Test void precedingProfileCanTradeWithTheSameUnpublishedReadAndReopensUnchanged() throws Exception {
        Path file=directory.resolve("v14");
        prepare(file,false,false);
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            read(w,"dave",first(w).docId());
            assertTrue(restricted(w,"dave").isMissingNode());
            child(w,STOCK,"old-admitted","OK");
        }
        byte[] before=Files.readAllBytes(file);
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            assertEquals(1,w.snapshot().exchange().orElseThrow().orders().size());
        }
        assertArrayEquals(before,Files.readAllBytes(file));
    }

    @Test void oneAuthorizedOrderCanRestBeforeLearningOrAfterDisclosureAndKeepsBothAccountBoundaries() throws Exception {
        Path mother=directory.resolve("mother");
        prepare(mother,true,true);
        Path control=Files.copy(mother,directory.resolve("unread")), aware=Files.copy(mother,directory.resolve("aware"));
        long knownAt;
        try(var w=ComposedIndustryScenario.openLoanLossTenCompanyStudy(aware)) {
            assertTrue(restricted(w,"dave").isEmpty());
            var draft=first(w);
            read(w,"dave",draft.docId());
            knownAt=now(w);
            assertEquals(draft.docId(),restricted(w,"dave").get(0).get("document").asText());
            assertEquals(1,restricted(w,MANAGER).size());
            assertTrue(restricted(w,"investor").isEmpty());
            child(w,STOCK,"same-child",BLOCKED);
            String action="fund-decision";
            w.beginDecisionSession("session-"+action);
            var route=new DecisionModel.Route("trading","key-1","deepseek-flash",1,40,1_000_000,2048,1);
            w.openDecisions("open-"+action,List.of(new DecisionRun.Request("dave",action,"wake",route)));
            var call=w.dispatchLiveDecision("dispatch-"+action,action).call();
            assertTrue(call.allowedTools().contains(RoleTradingView.TOOL_CHILD_SUBMIT));
            var instructions=JSON.createObjectNode().put("parentId","target-"+STOCK).put("parentVersion",1)
                    .put("riskRevision",1).put("childId",action).put("quantity",1).put("limitPrice",PRICE);
            var queued=AutonomousIssuerDividendTest.reply(w,call,"dave",RoleTradingView.TOOL_CHILD_SUBMIT,instructions);
            assertNotNull(queued);
            w.advanceTo(queued.dueMillis());
            assertTrue(w.openRoleSession("dave").view().notices().stream().anyMatch(row -> BLOCKED.equals(row.reason())
                    && row.timeMillis()==queued.dueMillis()));
            assertTrue(w.snapshot().exchange().orElseThrow().orders().isEmpty());
            child(w,"DIGITAL","other-issuer","OK");
            publish(w,draft);
            assertTrue(restricted(w,"dave").isEmpty());
            child(w,STOCK,"released","OK");
            periodic(w,MANAGER,"PREPARE",null,"","","更正后的正式说明");
            var revision=w.periodicOf(BANK).orElseThrow().drafts().get(1);
            assertNotEquals(draft.bodyDigest(),revision.bodyDigest());
            assertTrue(restricted(w,"dave").isEmpty());
            read(w,"dave",revision.docId());
            assertEquals(revision.docId(),restricted(w,"dave").get(0).get("document").asText());
            child(w,STOCK,"correction-private",BLOCKED);
            publish(w,revision);
            child(w,STOCK,"correction-public","OK");
        }
        try(var w=ComposedIndustryScenario.openLoanLossTenCompanyStudy(control)) {
            w.advanceTo(knownAt);
            child(w,STOCK,"same-child","OK");
            assertEquals(1,w.snapshot().exchange().orElseThrow().liveOrders().size());
            assertTrue(w.snapshot().exchange().orElseThrow().trades().isEmpty());
            read(w,"dave",first(w).docId());
            long due=now(w)+1;
            w.openRoleSession("dave").cancelChild("withdraw","withdraw",due,new WorldRoleSession.ChildRef("target-"+STOCK,"dave","same-child"));
            w.advanceTo(due);
            assertTrue(w.snapshot().exchange().orElseThrow().liveOrders().isEmpty());
        }
        try(var w=ComposedIndustryScenario.openLoanLossTenCompanyStudy(aware)) {
            assertTrue(restricted(w,"dave").isEmpty());
            assertEquals(3,w.snapshot().exchange().orElseThrow().orders().size());
        }
    }
}
