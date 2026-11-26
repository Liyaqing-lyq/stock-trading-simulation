package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.web.WorldWebScenario;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FundOfficeContactTest {
    @TempDir Path dir;
    private static final String MANDATE = "fund-mandate:cufe:fund-a";
    private static final long WRITE = WorldWebScenario.SOCIAL_SEND_MILLIS;
    private static final long DELIVERY = WorldWebScenario.SOCIAL_DELIVERY_MILLIS;

    private static void read(WorldRuntime w, String who, String doc) throws Exception {
        long start = w.snapshot().timeMillis()+1;
        w.openRoleSession(who).startReading("r"+start, "rt"+start, start, doc, 1);
        w.advanceTo(start);
        w.advanceTo(w.roleView(who).readings().get(0).dueAt());
    }

    private static List<InterviewRun.ContactEntry> desk(WorldRuntime w,String who) throws Exception {
        return w.socialView(who).contacts().stream().filter(c -> c.channel().startsWith("基金工作/")).toList();
    }

    private static String send(WorldRuntime w,String who,String to,String channel,String id) throws Exception {
        return w.openRoleSession(who).sendMessage(id,id,w.snapshot().timeMillis()+WRITE,
                new WorldRoleSession.SocialSendDraft(to,channel,"产品研究", id));
    }

    @Test void actualRosterRoutesRequireAReadAndCannotSurviveReassignmentOrPrivateMailReplies() throws Exception {
        Path file = dir.resolve("signed-routes.journal");
        try (var old = ComposedIndustryScenario.openFundMandateOperating(dir.resolve("prior.journal"));
             var w = ComposedIndustryScenario.openFundOfficeContactsOperating(file)) {
            read(old, "alice", MANDATE);
            assertTrue(desk(old, "alice").isEmpty());
            assertTrue(desk(w, "alice").isEmpty());
            read(w, "alice", MANDATE);
            var entries = desk(w, "alice");
            assertEquals(3, entries.size());
            assertTrue(entries.stream().anyMatch(c -> c.targetPartyId().equals("bob") && c.description().contains("PORTFOLIO_MANAGER")));
            String address = entries.stream().filter(c -> c.targetPartyId().equals("bob")).findFirst().orElseThrow().channel();
            assertFalse(send(w,"investor","bob",address,"guessed").startsWith("SCHEDULED"));
            assertTrue(desk(w,"investor").isEmpty());
            assertFalse(send(w,"carol","bob",address,"borrowed").startsWith("SCHEDULED"));
            assertTrue(send(w,"alice","bob",address,"genuine").startsWith("SCHEDULED"));
            w.advanceTo(w.snapshot().timeMillis()+WRITE+DELIVERY);
            var mail = w.socialView("bob").inbox().get(0);
            assertTrue(w.socialView("carol").inbox().isEmpty());
            read(w,"bob",mail.docId());
            assertTrue(desk(w,"bob").stream().anyMatch(c->c.channel().equals(address)));
            assertTrue(send(w,"bob","alice",address,"reply").startsWith("SCHEDULED"));
            long initial = w.snapshot().timeMillis();
            w.scheduleRevokeAppointment("rv-a","rv-at",initial+WRITE+1,"A-1");
            w.scheduleRevokeAppointment("rv-d","rv-dt",initial+WRITE+1,"A-4");
            w.scheduleAppointment("switch","switch-task",initial+WRITE+2,"A-1-new","alice","cufe",
                    RoleAccess.Role.EXECUTION_TRADER,initial+WRITE+2,360L*86_400_000L);
            w.advanceTo(initial+WRITE+DELIVERY);
            assertTrue(w.socialView("alice").inbox().isEmpty());
            assertEquals(SocialMessageModel.MessageStatus.UNREACHABLE,w.snapshot().social().orElseThrow().state().messages().stream()
                    .filter(m->m.from().equals("bob")).findFirst().orElseThrow().status());
            assertFalse(desk(w,"bob").stream().anyMatch(c->c.channel().equals(address)));
            assertFalse(send(w,"bob","alice",address,"staleReply").startsWith("SCHEDULED"));
            var newAddress=desk(w,"alice").stream().filter(c->c.targetPartyId().equals("bob")).findFirst().orElseThrow().channel();
            assertNotEquals(address,newAddress);
            assertTrue(send(w,"alice","bob",newAddress,"senderLeaves").startsWith("SCHEDULED"));
            long sent=w.snapshot().timeMillis();
            w.scheduleRevokeAppointment("rv-new","rv-new-task",sent+WRITE+1,"A-1-new");
            w.advanceTo(sent+WRITE+DELIVERY);
            assertEquals(1,w.socialView("bob").inbox().size());
            assertTrue(desk(w,"alice").isEmpty());
        }
        try (var w = ComposedIndustryScenario.openFundOfficeContactsOperating(file)) {
            assertEquals(1,w.socialView("bob").inbox().size());
            assertTrue(desk(w,"bob").isEmpty());
            assertTrue(desk(w,"alice").isEmpty());
        }
    }
}
