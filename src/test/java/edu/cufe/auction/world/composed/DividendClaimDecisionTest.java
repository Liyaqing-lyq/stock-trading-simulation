package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static edu.cufe.auction.world.composed.IssuerCreditDecisionTest.*;

class DividendClaimDecisionTest {
    @TempDir Path dir;
    static final String ISSUER="eng-a", SCHEME="arrears", IMPAIR="dividend.claim.impair", RETRY="issuer.dividend.retryPayment";

    private static void appointAfterCharterRead(WorldRuntime w) throws Exception {
        var doc=CompanyOpeningDocuments.documentId(ISSUER);
        var voters=List.of("alice","bob","carol","dave");
        long now=w.snapshot().timeMillis();
        for (var who:List.of("alice","bob","carol","dave","erin"))
            w.scheduleStartReading("read-"+who,"read-"+who,now+1,who,doc,1);
        w.advanceTo(now+1);
        w.advanceTo(w.roleView("alice").readings().get(0).dueAt());
        for (var who:voters) assertTrue(w.canCite(who,doc,1));
        now=w.snapshot().timeMillis();
        w.scheduleEngineeringAppointmentConvene("appoint","appoint",now+3000,ISSUER,"alice","appointment",now+6000,now+7000,now+10500);
        for(var who:voters) w.scheduleEngineeringAttend("job-"+who,"job-"+who,now+4000,ISSUER,who,"appointment",false);
        w.scheduleEngineeringMaterial("job-material","job-material",now+4500,ISSUER,"alice","appointment","charter",doc,1);
        var req=new BoardModel.AppointmentRequest("manager-erin","erin",ISSUER,RoleAccess.Role.COMPANY_MANAGER,
                BoardModel.AppointmentAction.APPOINT,now+14000,now+90L*86_400_000);
        w.scheduleEngineeringAppointmentProposal("nominate","nominate",now+5000,ISSUER,"alice","appointment",List.of(),req);
        for(var who:voters) w.scheduleEngineeringVote("job-vote-"+who,"job-vote-"+who,now+9000,ISSUER,who,"appointment",BoardModel.Vote.YES,1000);
        w.scheduleEngineeringImplement("install","install",now+11000,ISSUER,"alice","appointment");
        w.advanceTo(now+14000);
        assertTrue(w.snapshot().roles().orElseThrow().access().appointments().stream()
                .anyMatch(a->a.id().equals("manager-erin") && a.activeAt(w.snapshot().timeMillis())));
    }

    private static long authorize(WorldRuntime w) throws Exception {
        long now=w.snapshot().timeMillis();
        var voters=List.of("alice","bob","carol","dave","erin");
        var doc=CompanyOpeningDocuments.documentId(ISSUER);
        var spec=w.snapshot().engineering().orElseThrow().config().specOf(ISSUER);
        var security=w.openingRegistry().orElseThrow().securities().stream().filter(s->ISSUER.equals(s.issuerOrganizationId())).findFirst().orElseThrow();
        var request=new DividendModel.SchemeRequest(SCHEME,security.symbol(),spec.companyOwnerId(),spec.companyAccountId(),
                new BigDecimal("8.00"),now+20_000,now+21_000,now+22_000);
        var chair=w.openRoleSession("alice");
        chair.issuerDividend("convene","convene",now+3000,"CONVENE","meeting",now+6000,now+7000,now+10500,null);
        for (var who:voters) w.scheduleEngineeringAttend("attend-"+who,"attend-"+who,now+4000,ISSUER,who,"meeting",false);
        w.scheduleEngineeringMaterial("material","material",now+4500,ISSUER,"alice","meeting","charter",doc,1);
        chair.issuerDividend("propose","propose",now+5000,"PROPOSE","meeting",0,0,0,request);
        for (var who:voters) w.scheduleEngineeringVote("vote-"+who,"vote-"+who,now+9000,ISSUER,who,"meeting",BoardModel.Vote.YES,1000);
        chair.issuerDividend("authorize","authorize",now+11000,"AUTHORIZE","meeting",0,0,0,null);
        return request.payAtMillis();
    }

    private static JsonNode choose(WorldRuntime w,String actor,String id,String tool,ObjectNode args) throws Exception {
        var ref=decide(w,actor,id,tool,args);
        assertNotNull(ref,id);
        w.advanceTo(ref.dueMillis());
        var receipt=w.autonomyRoleObservation(actor).at("/engineering/deliberation/lastActionResult");
        assertEquals(id,receipt.path("actionId").asText());
        assertEquals(ref.dueMillis(),receipt.required("resultAt").longValue());
        return receipt;
    }

    @Test void onlyPersonalHoldersAssessTheirClaimsAndChairCanRetryOnlyOriginalAuthorizedCashLegs() throws Exception {
        var file=dir.resolve("holder-and-issuer.journal");
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            appointAfterCharterRead(w);
            var ownBank=JSON.createObjectNode().put("bank","north").put("borrower",ISSUER).put("amount","1000.00");
            applied(w,"erin","company-deposits-own-cash","bank.cash.deposit",ownBank);
            assertEquals(new BigDecimal("4000.00"),w.snapshot().accounts().get("ENG-A").cash());
            long pay=authorize(w);
            w.advanceTo(pay-1);
            assertFalse(tool(view(w,"nina"),IMPAIR));
            assertFalse(tool(view(w,"alice"),RETRY));
            w.advanceTo(pay);
            var nina=view(w,"nina");
            var mine=nina.at("/engineering/dividendClaims/ownClaims/0");
            assertEquals("HOUSE-NINA",mine.required("accountId").textValue());
            assertEquals(1,nina.at("/engineering/dividendClaims/ownClaims").size());
            assertTrue(nina.at("/engineering/dividendClaims/issuerArrears").isMissingNode());
            assertFalse(tool(nina,RETRY)); assertTrue(tool(nina,IMPAIR));
            for (var officer:List.of("alice","bob","carol","dave")) {
                assertFalse(tool(view(w,officer),IMPAIR));
                assertTrue(view(w,officer).at("/engineering/dividendClaims/ownClaims").isMissingNode());
            }
            assertFalse(tool(view(w,"erin"),RETRY));
            assertTrue(tool(view(w,"alice"),RETRY));
            var amount=JSON.createObjectNode().put("claimId",mine.required("claimId").textValue()).put("amount","25.00")
                    .put("evidenceRef",mine.required("failureRef").textValue());
            var accounts=w.snapshot().accounts();
            assertNull(decide(w,"omar","wrong-holder",IMPAIR,amount));
            var fundClaim=DividendModel.claims(w.snapshot().dividends().orElseThrow(),SCHEME).stream()
                    .filter(c->c.accountId().equals("ACC-FUND")).findFirst().orElseThrow();
            assertNull(decide(w,"nina","fund-property",IMPAIR,amount.deepCopy().put("claimId",fundClaim.claimId())));
            assertNull(decide(w,"nina","false-evidence",IMPAIR,amount.deepCopy().put("evidenceRef","unseen")));
            var excessive=choose(w,"nina","too-large",IMPAIR,amount.deepCopy().put("amount","99999.00"));
            assertFalse(excessive.required("accepted").booleanValue());
            assertTrue(choose(w,"nina","assess",IMPAIR,amount).required("accepted").booleanValue());
            assertEquals(accounts,w.snapshot().accounts());
            assertEquals(new BigDecimal("4800.00"),w.engineeringContext(ISSUER).orElseThrow().unpaidDividends());
            var assessed=view(w,"nina").at("/engineering/dividendClaims/ownClaims/0");
            assertEquals("25.00",assessed.required("impairment").asText());
            assertEquals(new BigDecimal(mine.required("face").asText()).subtract(new BigDecimal("25.00")).toPlainString(),
                    assessed.required("netReceivable").asText());
            var retry=JSON.createObjectNode().put("schemeId",SCHEME);
            assertNull(decide(w,"alice","foreign-scheme",RETRY,retry.deepCopy().put("schemeId","another")));
            var failed=choose(w,"alice","same-cash",RETRY,retry);
            assertEquals("INSUFFICIENT_CASH",failed.required("reason").asText());
            assertEquals(accounts,w.snapshot().accounts());
            applied(w,"erin","company-withdraws-own-deposit","bank.cash.withdraw",ownBank);
            var before=w.snapshot().accounts();
            assertTrue(choose(w,"alice","pays-original",RETRY,retry).required("accepted").booleanValue());
            assertEquals(before.get("ENG-A").cash().subtract(new BigDecimal("4800.00")),w.snapshot().accounts().get("ENG-A").cash());
            var div=w.snapshot().dividends().orElseThrow();
            for (var claim:DividendModel.claims(div,SCHEME)) {
                assertEquals(claim.face(),claim.paid());
                assertEquals(0,claim.impairment().signum());
                assertEquals(before.get(claim.accountId()).cash().add(claim.face()),w.snapshot().accounts().get(claim.accountId()).cash());
            }
            assertFalse(tool(view(w,"nina"),IMPAIR)); assertFalse(tool(view(w,"alice"),RETRY));
            assertEquals(1,div.payments().size());
        }
        var bytes=Files.readAllBytes(file);
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            assertEquals(1,w.snapshot().dividends().orElseThrow().impairments().size());
            assertEquals(1,w.snapshot().dividends().orElseThrow().payments().size());
        }
        assertArrayEquals(bytes,Files.readAllBytes(file));
        var forged=dir.resolve("foreign-holder.journal");
        boolean changed=false;
        try(var source=new WorldJournal(file, RunManifest.MEDIA_WITHDRAWAL_RUN_ID);
            var target=new WorldJournal(forged,RunManifest.MEDIA_WITHDRAWAL_RUN_ID)) {
            for (var frame:source.commits()) {
                var events=new java.util.ArrayList<>(frame.events());
                for (int i=0;i<events.size();i++) {
                    var event=events.get(i);
                    if (!event.type().equals("DECISION_ACTION_SCHEDULED_V1")) continue;
                    var task=(ObjectNode)JSON.readTree(event.payload());
                    if (!task.path("claimId").asText().equals("") && task.path("decisionWork").path("person").asText().equals("nina")) {
                        ((ObjectNode)task.get("decisionWork")).put("person","omar");
                        events.set(i,new WorldEvent(event.eventId(),event.type(),task.toString()));
                        changed=true;
                        break;
                    }
                }
                target.commit(frame.commandId(),frame.commandContent(),frame.result(),events);
                if (changed) break;
            }
        }
        assertTrue(changed);
        assertThrows(java.io.IOException.class,()->ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(forged).close());
    }
}
