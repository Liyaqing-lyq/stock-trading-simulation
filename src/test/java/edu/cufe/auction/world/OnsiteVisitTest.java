package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OnsiteVisitTest {
    @TempDir Path dir;
    static final String REPORTER="rita", HOST="investor";
    static String book(WorldRuntime w,long slot) throws Exception {
        String doc=WorldWebScenario.INTERVIEW_CONTACT_DOC;
        long now=w.snapshot().timeMillis();
        if (w.roleView(REPORTER).catalog().stream().noneMatch(d->d.docId().equals(doc))) {
            w.schedulePublishDocument("pub","pub",++now,doc,HOST,InformationModel.Audience.publicAudience(),
                    "接待方公开联系说明","本次预约可使用公开投关热线","现场试验",1000,List.of());
            w.advanceTo(now);
        }
        w.openRoleSession(REPORTER).startReading("read","read",++now,doc,1);
        w.advanceTo(now+60_000);
        now=w.snapshot().timeMillis();
        w.openRoleSession(REPORTER).inviteInterview("invite","invite",now+1000,new WorldRoleSession.InterviewInviteDraft(
                "visit",HOST,"接待方现场拜访",slot-10_000,slot+240_000,120_000));
        w.advanceTo(now+1000);
        var invite=w.interviewView(HOST).invites().get(0);
        w.openRoleSession(HOST).respondInterview("accept","accept",now+2000,new WorldRoleSession.InterviewRespondDraft(invite.requestId(),true,slot));
        w.advanceTo(now+2000);
        assertEquals("ACCEPTED",w.interviewView(HOST).invites().get(0).status());
        return invite.requestId();
    }
    static void act(WorldRuntime w,String who,String id,String action) throws Exception {
        long due=w.snapshot().timeMillis()+1000;
        w.openRoleSession(who).siteVisit(action,action,due,new OnsiteVisit.Draft(id,action));
        w.advanceTo(due);
    }
    static OnsiteVisit.Row row(WorldRuntime w){return w.siteVisitsFor(REPORTER).appointments().get(0);}
    @Test void midTripRevocationCancelsEntryAndKeepsTicketCashAndActualReadingAcrossReopen() throws Exception {
        Path file=dir.resolve("trip.journal");String id;long slot=500_000;
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            id=book(w,slot);
            assertTrue(w.siteVisitsFor("sam").appointments().isEmpty());
            assertEquals(List.of("HOST_CONSENT"),w.siteVisitsFor(HOST).appointments().get(0).actions());
            assertThrows(IllegalArgumentException.class,()->act(w,"sam",id,"HOST_CONSENT"));
            assertThrows(IllegalArgumentException.class,()->act(w,REPORTER,id,"DEPART"));
            act(w,HOST,id,"HOST_CONSENT");
            act(w,REPORTER,id,"DEPART");
            assertEquals("BOOKED",row(w).stage());assertEquals("0.00",row(w).farePaid());
            w.advanceTo(slot-10_000);
            assertEquals("OUTBOUND",row(w).stage());
            assertEquals("12.00",row(w).farePaid());
            assertEquals(new BigDecimal("4988.00"),w.snapshot().accounts().get("INV").cash());
            assertEquals(new BigDecimal("12.00"),w.snapshot().accounts().get("VISIT-CARRIER").cash());
            assertTrue(w.autonomyRoleBusy(REPORTER));
            assertTrue(w.autonomyRoleObservation(HOST).path("tools").toString().contains(OnsiteVisit.TOOL));
        }
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            assertEquals("OUTBOUND",row(w).stage());
            assertThrows(IllegalArgumentException.class,()->w.openRoleSession(REPORTER).startReading("overlap","overlap",
                    w.snapshot().timeMillis()+50,WorldWebScenario.INTERVIEW_CONTACT_DOC,1L));
            act(w,HOST,id,"REVOKE");
            assertEquals("RETURNING",row(w).stage()); assertFalse(row(w).observed());
            long home=row(w).returnMillis(); w.advanceTo(home-1); assertTrue(w.autonomyRoleBusy(REPORTER));
            w.advanceTo(home); assertEquals("HOME",row(w).stage()); assertFalse(w.autonomyRoleBusy(REPORTER));
            w.advanceTo(slot+130_000);
            assertFalse(row(w).observed()); assertEquals("12.00",row(w).farePaid());
            assertTrue(w.roleView(REPORTER).observations().stream().anyMatch(o->o.ref().docId().equals(WorldWebScenario.INTERVIEW_CONTACT_DOC)));
        }
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {assertEquals("HOME",row(w).stage());}
    }
    @Test void legitimateAdmissionSurvivesEarlyReturnAndIsOfferedOnlyToTheTwoParticipants() throws Exception {
        var file=dir.resolve("entry.journal");long slot=500_000;
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            String id=book(w,slot);
            var ref=PersonalMarketTest.decide(w,HOST,"consent-via-model",OnsiteVisit.TOOL,
                    new ObjectMapper().createObjectNode().put("requestId",id).put("action","HOST_CONSENT"));
            assertNotNull(ref);
            w.advanceTo(ref.dueMillis());
            assertEquals("CONSENTED",row(w).stage());
            w.advanceTo(slot-12_000);act(w,REPORTER,id,"DEPART");
            w.advanceTo(slot); assertEquals("ONSITE",row(w).stage()); assertTrue(row(w).observed());
            act(w,REPORTER,id,"RETURN"); assertEquals("RETURNING",row(w).stage());
            w.advanceTo(row(w).returnMillis());assertTrue(row(w).observed()); assertEquals("HOME",row(w).stage());
            assertFalse(w.siteVisitsFor("sam").traveling());
        }
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {assertTrue(row(w).observed());}
    }

    @Test void withdrawingTheActualReporterAppointmentInTransitPreventsAdmissionAndPreservesTicket() throws Exception {
        long slot=500_000;var file=dir.resolve("employment.journal");
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            String id=book(w,slot); act(w,HOST,id,"HOST_CONSENT");act(w,REPORTER,id,"DEPART");
            w.advanceTo(slot-9_000);
            var appointment=w.snapshot().roles().orElseThrow().access().appointments().stream().filter(a->
                    a.personPartyId().equals(REPORTER) && a.organizationPartyId().equals("wire") && a.role()==RoleAccess.Role.JOURNALIST)
                    .findFirst().orElseThrow();
            w.scheduleRevokeAppointment("revoke-position","revoke-position",slot-8_000,appointment.id());
            w.advanceTo(slot);
            assertEquals("RETURNING",row(w).stage());assertFalse(row(w).observed());
            w.advanceTo(row(w).returnMillis());assertFalse(w.autonomyRoleBusy(REPORTER));
            assertEquals("HOME",row(w).stage());assertEquals("12.00",row(w).farePaid());
        }
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {assertFalse(row(w).observed());}
        var scan=WorldJournalScanner.scan(file);
        assertEquals(1,scan.commits().stream().flatMap(c->c.events().stream()).filter(e->e.type().equals("ONSITE_FARE_PAID_V1")).count());
        var forged=dir.resolve("omitted-ticket.journal");
        try(var writer=new WorldJournal(forged,scan.runId())) {for(var c:scan.commits()) writer.commit(c.commandId(),c.commandContent(),
                c.result(),c.events().stream().filter(e->!e.type().equals("ONSITE_FARE_PAID_V1")).toList());}
        assertThrows(java.io.IOException.class,()->ComposedIndustryScenario.openTenCompanyAutonomy(forged));
    }

    @Test void preDepartureCancellationAndInsufficientMoneyNeverPayOrAdmit() throws Exception {
        for(String cause:List.of("cancel","deposit","frozen")) {
            long start=cause.equals("frozen")?570*60_000L:0;
            long slot=start+500_000;
            var file=dir.resolve("unissued-"+cause+".journal");
            try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
                if (start>0) w.advanceTo(start);
                String id=book(w,slot);act(w,HOST,id,"HOST_CONSENT");act(w,REPORTER,id,"DEPART");
                assertEquals("BOOKED",row(w).stage()); long now=w.snapshot().timeMillis();
                if(cause.equals("cancel")) {
                    w.openRoleSession(HOST).cancelInterview("withdraw-booking","withdraw-booking",now+500,
                            new WorldRoleSession.InterviewRequestDraft(id));
                } else if(cause.equals("deposit")) {
                    w.openRoleSession(HOST).bankAction("deposit","deposit",now+5,3,new WorldRoleSession.BankActionDraft("deposit",
                            edu.cufe.auction.world.bank.BankActions.Kind.DEPOSIT,"north","",new BigDecimal("5000.00"),0,0,"","","",""));
                } else {
                    w.scheduleOrder("reserved","reserved",now+1,HOST,"INV","HY1",edu.cufe.auction.model.Side.BUY,
                            edu.cufe.auction.model.OrderType.LIMIT,new BigDecimal("10.00"),500L);
                }
                w.advanceTo(slot+130_000);
                assertEquals("REVOKED",row(w).stage(),cause+" "+w.snapshot().accounts().get("INV")+" "+w.taskCommitResult("reserved"));
                assertEquals("0.00",row(w).farePaid());
                assertEquals(new BigDecimal("0.00"),w.snapshot().accounts().get("VISIT-CARRIER").cash());
                assertFalse(w.autonomyRoleBusy(REPORTER));assertFalse(row(w).observed());
            }
            try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {assertEquals("REVOKED",row(w).stage());}
        }
    }
}
