package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.InterviewDialogue;
import edu.cufe.auction.world.InterviewModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 正常宿主调度、有权模型输入和真实待办结算；提供方仅返回受控选择。 */
class WorldWebDialogueWindowIsolationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long START = 34_300_000L;
    private static final long SILENCE = 60_000L;
    private static final String REQUEST = InterviewModel.requestId("rita", "interview-window");
    private static final String SESSION = "SESSION-" + REQUEST;
    private static final String QUESTION = "该笔收入以哪份原件为准？";
    private static final String ANSWER = "我只核实了自己的收入记录。";
    @TempDir Path dir;

    enum Choice { EXCHANGE, SILENCE, UNRELATED_ORDER }
    record Observed(String person, long time, String actionId, JsonNode session, List<String> tools, String input, String choice) {}

    private void seed(Path file) throws Exception {
        try (var w = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(file)) {
            long readAt = w.snapshot().timeMillis() + 1;
            w.openRoleSession("rita").startReading("read-contact", "read-contact-task", readAt, "contact:investor-public", 1);
            w.advanceTo(readAt);
            w.advanceTo(w.roleView("rita").readings().get(0).dueAt());
            long inviteAt = w.snapshot().timeMillis() + w.interviewRun().config().inviteMillis();
            w.openRoleSession("rita").inviteInterview("arrange", "arrange-task", inviteAt,
                    new WorldRoleSession.InterviewInviteDraft("interview-window", "investor", "约访",
                            START, START + 120_001, 120_000));
            w.advanceTo(inviteAt);
            long confirmAt = inviteAt + w.interviewRun().config().respondMillis();
            w.openRoleSession("investor").respondInterview("agree", "agree-task", confirmAt,
                    new WorldRoleSession.InterviewRespondDraft(REQUEST, true, START));
            w.advanceTo(confirmAt);
            assertEquals("ACCEPTED", w.interviewView("investor").invites().get(0).status());
            w.advanceTo(START - 8_000);
            assertTrue(w.autonomyRoleObservation("investor").at("/engineering/exchangeSessions/sessionOpen").asBoolean());
        }
    }

    private static final class Choices implements WorldWebHost.DecisionProvider {
        final Choice policy;
        final List<Observed> rows = new CopyOnWriteArrayList<>();
        Choices(Choice policy) { this.policy = policy; }

        @Override public LiveCompletionInbox.Completion evaluate(DecisionRun.Call call, long timeout) {
            try {
                JsonNode input = JSON.readTree(call.inputText());
                String who = input.required("person").asText();
                long now = input.required("time").longValue();
                JsonNode session = input.at("/interview/sessions/0");
                String tool = "role.wait";
                ObjectNode args = JSON.createObjectNode();
                if (!session.isMissingNode() && session.path("endedAtMillis").asLong() < 0 && policy != Choice.SILENCE) {
                    JsonNode turns = session.required("turns");
                    if (who.equals("rita") && turns.isEmpty()) {
                        tool = "interview.speak";
                        args.put("requestId", REQUEST).put("title", "问题").put("body", QUESTION).put("readDurationMillis", 1_000);
                    } else if (who.equals("investor") && turns.size() == 1) {
                        JsonNode previous = turns.get(0);
                        if (policy == Choice.UNRELATED_ORDER) {
                            tool = "personal.order.limit";
                            args.put("accountId", "INV").put("symbol", "AGED").put("side","BUY")
                                    .put("quantity", 1).put("limitPrice", "9.50");
                        } else if (!previous.required("readByMe").asBoolean()) {
                            tool = "role.reading.start";
                            args.put("docId", previous.required("docId").asText()).put("version", previous.required("docVersion").longValue());
                        } else {
                            tool = "interview.speak";
                            args.put("requestId", REQUEST).put("title", "答复").put("body", ANSWER).put("readDurationMillis", 1_000);
                        }
                    }
                }
                var reply = JSON.createObjectNode().put("tool", tool);
                reply.set("args", args);
                reply.putObject("decision").put("objective", "person:"+who).put("basis", "按本人预约和已有资料办理")
                        .put("reviewAfterMillis", 500_000);
                rows.add(new Observed(who,now,call.actionId(),session,call.allowedTools(),call.inputText(),tool));
                return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),
                        call.attemptId(),call.attemptNo(),call.recoveryGeneration(),reply.toString(),
                        DecisionModel.Usage.unknownCost(1,20,12),"controlled"));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private void run(Choice policy) throws Exception {
        Path file = dir.resolve("world.journal");
        seed(file);
        Choices choices = new Choices(policy);
        var current = new AtomicReference<WorldRuntime>();
        try (var routes = SceneRoutes.open(dir)) {
            routes.replace(0, AutonomousLifecycleProbe.probeRoutes(routes.view(),"TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY"));
            try (var host = new WorldWebHost(() -> {
                var w = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(file);
                current.set(w);
                return new WorldWebHost.Opened(w,w.profile().manifest().runId());
            }, routes, choices)) {
                host.startAutonomy(Long.parseLong(host.liveCapabilities().position()), routes.view().revision(),
                        START + SILENCE + 40_000, 3_600_000, Set.of("rita","investor","nina"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                while (host.autonomyStatus().running() && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals("TARGET_REACHED", host.autonomyStatus().reason(), () -> choices.rows.stream()
                        .map(r -> r.person() + "@" + r.time() + " " + r.choice()).toList().toString());
                assertTrue(choices.rows.stream().anyMatch(r -> r.person().equals("rita")
                        && r.time() >= START && r.time() < START + SILENCE && r.tools().contains("interview.speak")));
                assertTrue(choices.rows.stream().anyMatch(r -> r.person().equals("nina")));
                assertTrue(choices.rows.stream().filter(r->r.person().equals("nina"))
                        .allMatch(r->r.session().isMissingNode() && !r.input().contains(QUESTION) && !r.input().contains(ANSWER)));
                host.call(() -> { verify(current.get(),choices); return null; });
            }
        }
        try (var w = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(file)) { verify(w,choices); }
    }

    private void verify(WorldRuntime w, Choices choices) throws Exception {
        var dialogue = w.interviewRun().dialoguesOf("wire");
        var turns = dialogue.turnsOf(SESSION);
        assertEquals(InterviewDialogue.CloseReason.DEADLINE_NO_REPLY,dialogue.closeOf(SESSION).orElseThrow().reason());
        assertTrue(w.snapshot().exchange().orElseThrow().orders().isEmpty());
        var investor = choices.rows.stream().filter(r->r.person().equals("investor")).toList();
        assertTrue(investor.stream().anyMatch(r->r.time()<START && r.tools().contains("personal.order.limit")));
        if (choices.policy == Choice.SILENCE) {
            assertTrue(turns.isEmpty());
            assertTrue(choices.rows.stream().filter(r->r.person().equals("rita") && r.time()>=START && r.time()<START+SILENCE)
                    .allMatch(r -> r.choice().equals("role.wait")));
            return;
        }
        assertEquals("rita",turns.get(0).speakerPartyId());
        if (choices.policy == Choice.UNRELATED_ORDER) {
            assertEquals(1,turns.size());
            var blocked = investor.stream().filter(r -> r.choice().equals("personal.order.limit")
                    && !r.tools().contains("personal.order.limit")).findFirst().orElseThrow();
            var decision = w.snapshot().decisions().orElseThrow().state().decisions().stream()
                    .filter(d -> d.actionId().equals(blocked.actionId())).findFirst().orElseThrow();
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decision.outcome());
            return;
        }
        assertEquals(List.of("rita","investor"),turns.stream().map(InterviewDialogue.Turn::speakerPartyId).toList());
        var read = investor.stream().filter(r->r.choice().equals("role.reading.start")).findFirst().orElseThrow();
        var speak = investor.stream().filter(r->r.choice().equals("interview.speak")).findFirst().orElseThrow();
        assertFalse(read.session().at("/turns/0/readByMe").asBoolean());
        assertFalse(read.input().contains(QUESTION));
        assertTrue(read.tools().contains("role.reading.start"));
        assertFalse(read.tools().contains("interview.speak"));
        assertTrue(speak.time()>read.time() && speak.session().at("/turns/0/readByMe").asBoolean());
        assertTrue(speak.input().contains(QUESTION));
        assertTrue(speak.tools().contains("interview.speak"));
        assertEquals(1,investor.stream().filter(r->r.choice().equals("interview.speak")).count());
    }

    @Test void addressedTurnMustBeReadBeforeTheRealHostDispatchesItsResponse() throws Exception { run(Choice.EXCHANGE); }
    @Test void silenceClosesWithoutInventingAnyUtterance() throws Exception { run(Choice.SILENCE); }
    @Test void anOpenTradingSessionDoesNotPermitAnOrderDuringTheInterview() throws Exception { run(Choice.UNRELATED_ORDER); }

    @Test void duplicateAndOutOfTurnCommandsCannotCreateAnotherTurnAtTheHost() throws Exception {
        Path file = dir.resolve("manual.journal");
        seed(file);
        var current = new AtomicReference<WorldRuntime>();
        var speech = new WorldRoleSession.DialogueSpeakDraft(REQUEST, "问题", QUESTION, 1_000L);
        long firstDue = START + WorldWebScenario.dialogueConfig().speakMillis();
        try (var host = new WorldWebHost(() -> {
            var w = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(file);
            current.set(w);
            return new WorldWebHost.Opened(w,w.profile().manifest().runId());
        })) {
            host.call(() -> {
                var w = current.get();
                w.advanceTo(START);
                var speaker = w.openRoleSession("rita");
                assertThrows(IllegalArgumentException.class, () -> w.openRoleSession("nina")
                        .speakInterview("outsider", "outsider", firstDue, speech));
                speaker.speakInterview("first", "first", firstDue, speech);
                long acceptedPosition = w.snapshot().position();
                assertThrows(IllegalArgumentException.class, () -> speaker.speakInterview("first", "first", firstDue, speech));
                assertEquals(acceptedPosition, w.snapshot().position());
                w.advanceTo(firstDue);
                long nextDue = firstDue + WorldWebScenario.dialogueConfig().speakMillis();
                w.openRoleSession("rita").speakInterview("repeat", "repeat", nextDue, speech);
                w.openRoleSession("investor").speakInterview("unread", "unread", nextDue, speech);
                w.advanceTo(nextDue);
                var results = w.interviewRun().coordinations();
                assertTrue(results.get(WorldRuntime.roleTaskId("rita", "first")).accepted());
                assertEquals(InterviewDialogue.REASON_STATE, results.get(WorldRuntime.roleTaskId("rita", "repeat")).reason());
                assertEquals(InterviewDialogue.REASON_NOT_READ, results.get(WorldRuntime.roleTaskId("investor", "unread")).reason());
                w.advanceTo(firstDue + SILENCE);
                assertThrows(IllegalArgumentException.class, () -> w.openRoleSession("investor")
                        .speakInterview("late", "late", w.snapshot().timeMillis() + 1_000L, speech));
                return null;
            });
        }
        try (var w = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(file)) {
            var dialogue = w.interviewRun().dialoguesOf("wire");
            assertEquals(1, dialogue.turnsOf(SESSION).size());
            assertEquals("rita", dialogue.turnsOf(SESSION).get(0).speakerPartyId());
            assertEquals(InterviewDialogue.CloseReason.DEADLINE_NO_REPLY, dialogue.closeOf(SESSION).orElseThrow().reason());
            assertTrue(w.interviewView("nina").sessions().isEmpty());
        }
    }
}
