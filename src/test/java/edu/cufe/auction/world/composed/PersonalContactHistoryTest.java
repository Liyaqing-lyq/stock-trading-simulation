package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.RoleTradingView;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PersonalContactHistoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HISTORY = "/engineering/contactHistory";
    @TempDir Path dir;

    @Test void realLettersGroundSeparatePrivateJudgmentsAndAChosenFollowupAfterRestart() throws Exception {
        var journal = dir.resolve("contacts");
        String quote, cash;
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(journal)) {
            assertTrue(world.socialView("omar").contacts().isEmpty());
            String directory = WorldWebScenario.SOCIAL_CONTACT_DOC;
            read(world, "omar", directory);
            read(world, "investor", directory);
            quote = sendToNina(world, "omar", "报价只覆盖本期采购。");
            cash = sendToNina(world, "investor", "上一笔款已经到账。");
            assertTrue(world.autonomyRoleObservation("nina").at(HISTORY + "/namedReadings").isEmpty());
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decide(world, "nina", "unread",
                    input -> assess("omar", quote, "报价找此人复核")));
            read(world, "nina", quote);
            read(world, "nina", cash);
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decide(world, "nina", "wrong-source",
                    input -> assess("investor", quote, "不能替其他人署名")));
            assertEquals(DecisionModel.Outcome.WAIT, decide(world, "nina", "cash-contact",
                    input -> assess("investor", cash, "到账问题找他")));
            assertEquals(DecisionModel.Outcome.WAIT, decide(world, "nina", "quote-contact",
                    input -> assess("omar", quote, "新报价找他核实")));
            assertTrue(world.autonomyRoleObservation("investor").at(HISTORY + "/assessments").isEmpty());
            assertFalse(world.autonomyRoleObservation("investor").toString().contains("新报价找他核实"));
        }
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(journal)) {
            assertEquals(2, world.autonomyRoleObservation("nina").at(HISTORY + "/assessments").size());
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decide(world, "nina", "wrong-recipient",
                    input -> followup(input, "quote-contact", "investor")));
            assertEquals(DecisionModel.Outcome.INTENT, decide(world, "nina", "ask-price", input -> {
                for (var assessment : input.at(HISTORY + "/assessments"))
                    if ("新报价找他核实".equals(assessment.at("/assessment/judgment").asText()))
                        return followup(input, assessment.path("actionId").asText(), assessment.at("/assessment/personPartyId").asText());
                throw new AssertionError("本人冻结输入中应有仍然可见的报价判断");
            }));
            var run = world.snapshot().decisions().orElseThrow();
            world.advanceTo(DecisionModel.actionDueMillis(run.state(), "ask-price"));
            var sent = world.snapshot().social().orElseThrow().state().messages().stream()
                    .filter(m -> "nina".equals(m.from()) && "omar".equals(m.to())).findFirst().orElseThrow();
            world.advanceTo(sent.dueAt());
            assertEquals("quote-contact", world.autonomyRoleObservation("nina")
                    .at("/engineering/deliberation/lastActionResult/basedOnContactAssessment").asText());
            var received = world.socialView("omar").inbox().stream().filter(m -> "nina".equals(m.from())).findFirst().orElseThrow();
            assertTrue(world.socialView("investor").inbox().isEmpty());
            read(world, "omar", received.docId());
            assertTrue(world.roleView("omar").observations().stream().anyMatch(o -> received.docId().equals(o.ref().docId()) && o.body().contains("下期报价")));
            assertEquals(DecisionModel.Outcome.WAIT, decide(world, "nina", "withdraw", input -> assess("omar", quote, null)));
            var remaining = world.autonomyRoleObservation("nina").at(HISTORY + "/assessments");
            assertEquals(1, remaining.size());
            assertEquals("investor", remaining.get(0).at("/assessment/personPartyId").asText());
            world.advanceTo(world.snapshot().timeMillis() + 4 * world.profile().information().recallPolicy().halfLifeMillis());
            assertTrue(world.autonomyRoleObservation("nina").at(HISTORY + "/assessments").isEmpty());
            read(world, "nina", cash);
            assertEquals("cash-contact", world.autonomyRoleObservation("nina").at(HISTORY + "/assessments/0/actionId").asText());
        }
        try (var old = ComposedIndustryScenario.openContinuingRolesOperating(dir.resolve("prior"))) {
            assertTrue(old.autonomyRoleObservation("nina").at(HISTORY).isMissingNode());
            assertTrue(old.roleView("omar").catalog().stream().noneMatch(d -> WorldWebScenario.SOCIAL_CONTACT_DOC.equals(d.docId())));
        }
    }

    private static void read(WorldRuntime world, String person, String doc) throws Exception {
        long start = world.snapshot().timeMillis() + 1;
        world.openRoleSession(person).startReading("read-" + start, "task-" + start, start, doc, 1);
        world.advanceTo(start + WorldWebScenario.SOCIAL_READ_MILLIS);
        assertTrue(world.roleView(person).observations().stream().anyMatch(o -> doc.equals(o.ref().docId())));
    }

    @Test void anAcceptedInterviewOnlyAttributesTheReadCounterpartTurn() throws Exception {
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(dir.resolve("interview"))) {
            read(world, "rita", WorldWebScenario.INTERVIEW_CONTACT_DOC);
            long now = world.snapshot().timeMillis(), start = now + 300_000;
            world.openRoleSession("rita").inviteInterview("invite", "invite-task", now + 1_000,
                    new WorldRoleSession.InterviewInviteDraft("order-check", "investor", "订单回访",
                            start, start + 300_000, 120_000));
            world.advanceTo(now + 1_000);
            String id = world.interviewView("investor").invites().get(0).requestId();
            world.openRoleSession("investor").respondInterview("accept", "accept-task", now + 2_000,
                    new WorldRoleSession.InterviewRespondDraft(id, true, start));
            world.advanceTo(start);
            speak(world, "rita", id, "你说的是哪个月份的订单？");
            String question = world.autonomyRoleObservation("investor").at("/interview/sessions/0/turns/0/docId").asText();
            read(world, "investor", question);
            speak(world, "investor", id, "上月十份是意向书，这月才确认三份。");
            String answer = world.autonomyRoleObservation("rita").at("/interview/sessions/0/turns/1/docId").asText();
            assertTrue(world.autonomyRoleObservation("rita").at(HISTORY + "/namedReadings").isEmpty());
            read(world, "rita", answer);
            var evidence = world.autonomyRoleObservation("rita").at(HISTORY + "/namedReadings");
            assertEquals(1, evidence.size());
            assertEquals("INTERVIEW_TURN", evidence.get(0).path("kind").asText());
            assertEquals("investor", evidence.get(0).path("personPartyId").asText());
            world.advanceTo(start + 120_000);
            assertEquals(DecisionModel.Outcome.WAIT, decide(world, "rita", "interview-judgment",
                    input -> assess("investor", answer, "再问他订单时要分别核对意向与确认")));
            assertTrue(world.autonomyRoleObservation("sam").at(HISTORY + "/namedReadings").isEmpty());
        }
    }

    private static void speak(WorldRuntime world, String speaker, String id, String body) throws Exception {
        long due = world.snapshot().timeMillis() + 1000;
        world.openRoleSession(speaker).speakInterview("speak-" + speaker, "turn-" + speaker, due,
                new WorldRoleSession.DialogueSpeakDraft(id, "订单口径", body, WorldWebScenario.SOCIAL_READ_MILLIS));
        world.advanceTo(due);
    }

    private static String sendToNina(WorldRuntime world, String person, String body) throws Exception {
        var contact = world.socialView(person).contacts().stream().filter(e -> e.targetPartyId().equals("nina")).findFirst().orElseThrow();
        long due = world.snapshot().timeMillis() + WorldWebScenario.SOCIAL_SEND_MILLIS;
        assertEquals("SCHEDULED:letter-" + person, world.openRoleSession(person).sendMessage("send-" + person, "letter-" + person,
                due, new WorldRoleSession.SocialSendDraft("nina", contact.channel(), "业务来信", body)));
        world.advanceTo(due + WorldWebScenario.SOCIAL_DELIVERY_MILLIS);
        return world.socialView("nina").inbox().stream().filter(e -> person.equals(e.from())).findFirst().orElseThrow().docId();
    }

    private static ObjectNode assess(String peer, String doc, String judgment) {
        var reply = JSON.createObjectNode().put("tool", "role.wait");
        reply.putObject("contactAssessment").put("personPartyId", peer).put("docId", doc).put("version", 1).put("judgment", judgment);
        return reply;
    }

    private static ObjectNode followup(JsonNode input, String basis, String peer) {
        for (var c : input.at("/social/contacts")) {
            if (!peer.equals(c.path("targetPartyId").asText())) continue;
            var reply = JSON.createObjectNode().put("tool", RoleTradingView.TOOL_SOCIAL_SEND).put("basedOnContactAssessment", basis);
            reply.putObject("args").put("toPartyId", peer).put("channel", c.path("channel").asText()).put("title", "跟进").put("body", "请提供下期报价。");
            return reply;
        }
        throw new AssertionError("没有真实渠道");
    }

    private static DecisionModel.Outcome decide(WorldRuntime world, String person, String id,
                                               Function<JsonNode, ObjectNode> answer) throws Exception {
        var call = PharmaDecisionToolTest.open(world, person, id);
        var input = JSON.readTree(call.inputText());
        var reply = answer.apply(input);
        reply.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                .put("basis", "结合本人已读来信复查").put("reviewAfterMillis", 60_000);
        var response = new DecisionModel.Reply(call.runId(), id, call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                reply.toString(), DecisionModel.Usage.unknownCost(1, 100, 30), "fixture:contacts");
        assertEquals("QUEUED", world.offerDecisionCallback(response));
        assertEquals(1, world.drainDecisionCallbacks());
        var decision = DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(), id).orElseThrow();
        world.advanceTo(decision.barrierAtMillis());
        return decision.outcome();
    }
}
