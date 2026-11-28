package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.EngineeringCustomerPolicy;
import edu.cufe.auction.world.InterviewModel;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CustomerRepresentativeCommunicationTest {
    @TempDir Path dir;
    static final String PERSON = EngineeringCustomerPolicy.REPRESENTATIVE;
    static final long MONDAY = 34_500_000;

    private static void read(WorldRuntime w, String who, String doc) throws Exception {
        long start = w.snapshot().timeMillis() + 1;
        w.openRoleSession(who).startReading("read-" + start, "reading-" + start, start, doc, 1);
        w.advanceTo(start);
        w.advanceTo(w.roleView(who).readings().stream().filter(r -> r.ref().docId().equals(doc)).findFirst().orElseThrow().dueAt());
        assertTrue(w.canCite(who, doc, 1));
    }

    private static String send(WorldRuntime w, String who, String to, String id, String body) throws Exception {
        var contact = w.socialView(who).contacts().stream().filter(c -> c.targetPartyId().equals(to)).findFirst().orElseThrow();
        var config = w.socialRun().config();
        long due = w.snapshot().timeMillis() + config.sendMillis();
        assertTrue(w.openRoleSession(who).sendMessage(id, id, due,
                new WorldRoleSession.SocialSendDraft(to, contact.channel(), "场地交接记录", body)).startsWith("SCHEDULED:"));
        w.advanceTo(due + config.deliveryMillis());
        return w.socialView(to).inbox().stream().filter(m -> m.from().equals(who)).findFirst().orElseThrow().docId();
    }

    private static boolean interviewContact(WorldRuntime w, String who) throws Exception {
        return w.interviewView(who).contacts().stream().anyMatch(c -> c.targetPartyId().equals(PERSON));
    }

    /** 以正常本人操作准备两封定向私信，仅留下本人的下一次自由选择。 */
    static String prepareReplyableLetter(WorldRuntime w) throws Exception {
        w.advanceTo(MONDAY);
        assertFalse(interviewContact(w, "investor"));
        read(w, PERSON, "contact:investor-public");
        String first = send(w, PERSON, "investor", "handover", "两个房间的交接记录分别归档。");
        assertFalse(interviewContact(w, "investor"));
        read(w, "investor", first);
        assertTrue(w.interviewView("investor").contacts().stream().anyMatch(c -> c.targetPartyId().equals(PERSON) && c.docId().equals(first)));
        assertFalse(interviewContact(w, "rita"));
        var modeling = w.autonomyRoleObservation("investor");
        assertTrue(modeling.path("interview").path("contacts").toString().contains(first));
        assertFalse(w.canCite("rita", first, 1));
        String incoming = send(w, "investor", PERSON, "receipt", "收到。两间房的照片各有一张，归档时分别写了房号。");
        read(w, PERSON, incoming);
        return incoming;
    }

    @Test void previousOpeningKeepsItsOriginalRoleTools() throws Exception {
        Path file = dir.resolve("previous.journal");
        String input;
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            w.advanceTo(MONDAY);
            var observation = w.autonomyRoleObservation(PERSON);
            input = observation.toString();
            assertEquals(Set.of("role.wait", "role.reading.start", "role.reading.cancel", "media.subscription.join",
                    "media.subscription.leave"), observation.path("tools").findValues("name").stream()
                    .filter(n -> n.asText().contains(".")).map(n -> n.asText()).collect(Collectors.toSet()));
        }
        byte[] bytes = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            assertEquals(input, w.autonomyRoleObservation(PERSON).toString());
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void reporterWithReadReturnAddressCanInviteAndTheNamedCustomerCanAnswer() throws Exception {
        Path file = dir.resolve("current.journal");
        String incoming, request;
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)) {
            var cash = w.snapshot().accounts().get("ENG-A-CUS");
            incoming = prepareReplyableLetter(w);
            var observed = w.autonomyRoleObservation(PERSON);
            var tools = observed.path("tools").findValues("name").stream().map(n -> n.asText()).collect(Collectors.toSet());
            assertTrue(tools.containsAll(Set.of("social.send", "social.block", "role.library.search", "group.respond",
                    "platform.post", "interview.respond", "role.wait")), tools.toString());
            assertFalse(tools.stream().anyMatch(t -> t.startsWith("board.") || t.startsWith("bank.") || t.startsWith("issuer.")
                    || t.startsWith("engineering.") || t.contains("order.submit") || t.equals("forecast.publish")
                    || t.equals("interview.invite") || t.startsWith("media.story.")), tools.toString());
            assertEquals("eng-a-customer", observed.at("/engineering/customerMandate/organization").asText());
            long due = w.snapshot().timeMillis() + w.interviewRun().config().inviteMillis();
            long start = w.personalInterviewControls("investor").earliestWindowStartMillis() + 30_000;
            w.openRoleSession("investor").inviteInterview("invite-owner", "invite-owner", due,
                    new WorldRoleSession.InterviewInviteDraft("rooms", PERSON, "房间交接记录", start, start+60_000, 10_000));
            w.advanceTo(due);
            request = InterviewModel.requestId("investor", "rooms");
            assertTrue(w.interviewView(PERSON).invites().stream().anyMatch(i -> i.requestId().equals(request) && i.status().equals("PROPOSED")));
            assertTrue(w.interviewView("rita").invites().isEmpty());
            var call = AutonomousIssuerDividendTest.call(w, PERSON, "customer-answer");
            assertTrue(call.allowedTools().contains("interview.respond"));
            var action = AutonomousIssuerDividendTest.reply(w, call, PERSON, "interview.respond",
                    new ObjectMapper().createObjectNode().put("requestId",request).put("accept",true).put("startMillis",start));
            assertNotNull(action);
            w.advanceTo(action.dueMillis());
            assertTrue(w.interviewView(PERSON).invites().stream().anyMatch(i -> i.requestId().equals(request) && i.status().equals("ACCEPTED")));
            assertEquals(cash,w.snapshot().accounts().get("ENG-A-CUS"));
        }
        byte[] bytes = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)) {
            assertTrue(w.canCite(PERSON,incoming,1));
            assertTrue(w.interviewView("investor").invites().stream().anyMatch(i -> i.requestId().equals(request) && i.status().equals("ACCEPTED")));
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }
}
