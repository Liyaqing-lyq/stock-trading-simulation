package edu.cufe.auction.web;

import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.InterviewDialogue;
import edu.cufe.auction.world.InterviewModel;
import edu.cufe.auction.world.MediaRun;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.WorldRoleSession;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** schema15 运行桥端到端：真实命令、真实阅读、唯一会话 Work 与同进程重开恢复。 */
class DialogueRuntimeTest {

    static final String DOC = "contact:investor-public";

    @Test
    void fullDialogueWithRealCommandsAndRecovery() throws Exception {
        Path journal = Files.createTempFile(Files.createDirectories(Path.of("target", "dialogue-test-tmp")), "dialogue-", ".jsonl");
        var opened = WorldWebScenario.openInterviewDialogue(journal);
        WorldRuntime runtime = opened.runtime();
        try {
            assertTrue(runtime.snapshot().position() >= 0);
            runtime.advanceTo(1_000L);
            assertNotNull(runtime.snapshot().information().orElseThrow().information()
                    .documents().stream().filter(d -> d.docId().equals(DOC)).findFirst().orElse(null));

            WorldRoleSession rita = runtime.openRoleSession("rita");
            rita.startReading("c-read-c", "t-read-c", 2_000L, DOC, 1L);
            runtime.advanceTo(8_000L);
            assertTrue(canCiteDoc(runtime, "rita", DOC), "rita read");

            rita = runtime.openRoleSession("rita");
            rita.inviteInterview("c-inv", "t-inv", 9_000L,
                    new WorldRoleSession.InterviewInviteDraft("inv-1", "investor", "topic",
                            80_000L, 200_000L, 120_000L));
            runtime.advanceTo(9_000L);
            String requestId = InterviewModel.requestId("rita", "inv-1");
            WorldRoleSession investor = runtime.openRoleSession("investor");
            investor.respondInterview("c-resp", "t-resp", 10_000L,
                    new WorldRoleSession.InterviewRespondDraft(requestId, true, 80_000L));
            runtime.advanceTo(80_001L);
            assertTrue(sessionOpen(runtime, "SESSION-" + requestId), "session open @" + runtime.snapshot().timeMillis());

            rita = runtime.openRoleSession("rita");
            rita.speakInterview("c-sp1", "t-sp1", 81_001L,
                    new WorldRoleSession.DialogueSpeakDraft(requestId, "Q1", "question", 5_000L));
            runtime.advanceTo(81_001L);
            String doc1 = turnDoc("SESSION-" + requestId, 1);
            assertFalse(canCiteDoc(runtime, "investor", doc1));

            investor = runtime.openRoleSession("investor");
            investor.startReading("c-read-1", "t-read-1", 82_001L, doc1, 1L);
            runtime.advanceTo(87_001L);
            assertTrue(canCiteDoc(runtime, "investor", doc1), "investor read t=" + runtime.snapshot().timeMillis() + " obs=" + runtime.snapshot().information().orElseThrow().information().observations().size() + " active=" + runtime.snapshot().information().orElseThrow().information().active().size());
            investor = runtime.openRoleSession("investor");
            investor.speakInterview("c-sp2", "t-sp2", 88_001L,
                    new WorldRoleSession.DialogueSpeakDraft(requestId, "A1", "answer", 5_000L));
            runtime.advanceTo(88_001L);

            String doc2 = turnDoc("SESSION-" + requestId, 2);
            rita = runtime.openRoleSession("rita");
            rita.startReading("c-read-2", "t-read-2", 89_001L, doc2, 1L);
            runtime.advanceTo(94_001L);
            rita = runtime.openRoleSession("rita");
            rita.speakInterview("c-sp3", "t-sp3", 95_001L,
                    new WorldRoleSession.DialogueSpeakDraft(requestId, "Q2", "follow-up", 5_000L));
            runtime.advanceTo(95_001L);

            investor = runtime.openRoleSession("investor");
            investor.endInterview("c-end", "t-end", 95_501L,
                    new WorldRoleSession.InterviewRequestDraft(requestId));
            runtime.advanceTo(95_501L);

            var interviews = runtime.interviews();
            var dialogue = interviews.dialoguesOf("wire");
            assertEquals(3, dialogue.turnsOf("SESSION-" + requestId).size());
            assertEquals(InterviewDialogue.CloseReason.CLOSED_BY_PARTY,
                    dialogue.closeOf("SESSION-" + requestId).orElseThrow().reason());
            long sessionWorks = runtime.snapshot().media().orElseThrow().works().stream()
                    .filter(w -> MediaRun.KIND_INTERVIEW_SESSION.equals(w.kind()))
                    .count();
            assertEquals(1L, sessionWorks);
            long speakWorks = runtime.snapshot().media().orElseThrow().works().stream()
                    .filter(w -> w.kind().equals(edu.cufe.auction.world.PendingTask.INTERVIEW_SPEAK_V1))
                    .count();
            assertEquals(0L, speakWorks);
        } finally {
            runtime.close();
        }

        var reopened = WorldWebScenario.openInterviewDialogue(journal);
        try {
            var interviews = reopened.runtime().interviews();
            assertEquals(3, interviews.dialoguesOf("wire").turnsOf(
                    "SESSION-" + InterviewModel.requestId("rita", "inv-1")).size());
            assertEquals(1L, reopened.runtime().snapshot().media().orElseThrow().works().stream()
                    .filter(w -> MediaRun.KIND_INTERVIEW_SESSION.equals(w.kind())).count());
        } finally {
            reopened.runtime().close();
            Files.deleteIfExists(journal);
        }
    }

    @Test
    void speakAtReplyDeadlineIsRejectedAndSessionClosesOnce() throws Exception {
        Path journal = Files.createTempFile(Files.createDirectories(Path.of("target", "dialogue-test-tmp")), "s5-", ".jsonl");
        var opened = WorldWebScenario.openInterviewDialogue(journal);
        WorldRuntime runtime = opened.runtime();
        try {
            runtime.advanceTo(1_000L);
            WorldRoleSession rita = runtime.openRoleSession("rita");
            rita.startReading("s5-rc", "s5-tc", 2_000L, DOC, 1L);
            runtime.advanceTo(7_000L);
            rita = runtime.openRoleSession("rita");
            rita.inviteInterview("s5-inv", "s5-tinv", 8_000L,
                    new WorldRoleSession.InterviewInviteDraft("inv-s5", "investor", "topic",
                            80_000L, 200_000L, 120_000L));
            runtime.advanceTo(9_000L);
            String requestId = InterviewModel.requestId("rita", "inv-s5");
            String sessionId = "SESSION-" + requestId;
            WorldRoleSession investor = runtime.openRoleSession("investor");
            investor.respondInterview("s5-resp", "s5-tresp", 10_000L,
                    new WorldRoleSession.InterviewRespondDraft(requestId, true, 80_000L));
            runtime.advanceTo(80_001L);
            rita = runtime.openRoleSession("rita");
            rita.speakInterview("s5-sp1", "s5-tsp1", 81_001L,
                    new WorldRoleSession.DialogueSpeakDraft(requestId, "Q1", "q", 5_000L));
            runtime.advanceTo(81_001L);
            String doc1 = turnDoc(sessionId, 1);
            long published = runtime.snapshot().information().orElseThrow().information()
                    .documents().stream().filter(d -> d.docId().equals(doc1)).findFirst()
                    .orElseThrow().versions().get(0).publishedAt();
            long deadline = published + WorldWebScenario.dialogueConfig().replyDeadlineMillis();
            investor = runtime.openRoleSession("investor");
            investor.startReading("s5-r1", "s5-tr1", published + 1_000L, doc1, 1L);
            runtime.advanceTo(published + 6_000L);
            runtime.advanceTo(deadline - 1_000L);
            assertTrue(sessionOpen(runtime, sessionId),
                    "open before atCut @" + runtime.snapshot().timeMillis()
                            + " deadline=" + deadline);
            WorldRoleSession atCut = runtime.openRoleSession("investor");
            atCut.speakInterview("s5-sp-at", "s5-tsp-at", deadline,
                    new WorldRoleSession.DialogueSpeakDraft(requestId, "A1", "a", 5_000L));
            runtime.advanceTo(deadline + 1L);

            var dialogue = runtime.interviews().dialoguesOf("wire");
            assertEquals(1, dialogue.turnsOf(sessionId).size());
            assertEquals(InterviewDialogue.CloseReason.DEADLINE_NO_REPLY,
                    dialogue.closeOf(sessionId).orElseThrow().reason());
            assertFalse(sessionOpen(runtime, sessionId));
            assertEquals(1L, runtime.snapshot().media().orElseThrow().works().stream()
                    .filter(w -> MediaRun.KIND_INTERVIEW_SESSION.equals(w.kind())).count());
        } finally {
            runtime.close();
            Files.deleteIfExists(journal);
        }
    }

    static final DecisionModel.Route ROUTE =
            new DecisionModel.Route("media", "key-1", "deepseek-flash", 1L,
                    8, 100_000, 4_000, 2);

    static String decisionInput(WorldRuntime runtime, String actionId) {
        return runtime.snapshot().decisions().orElseThrow().state().decisions().stream()
                .filter(d -> d.actionId().equals(actionId)).findFirst().orElseThrow()
                .inputText();
    }

    static void openDecision(WorldRuntime runtime, String person, String actionId) throws Exception {
        runtime.openDecisions("open-" + actionId, List.of(
                new DecisionRun.Request(person, actionId, "wake", ROUTE)));
    }

    @Test
    void decisionInputShowsOwnInviteButNotOthers() throws Exception {
        Path journal = Files.createTempFile(Files.createDirectories(Path.of("target", "dialogue-test-tmp")), "in-a-", ".jsonl");
        var opened = WorldWebScenario.openInterviewDialogue(journal);
        WorldRuntime runtime = opened.runtime();
        try {
            runtime.advanceTo(1_000L);
            WorldRoleSession rita = runtime.openRoleSession("rita");
            rita.startReading("ia-rc", "ia-tc", 2_000L, DOC, 1L);
            runtime.advanceTo(7_000L);
            rita = runtime.openRoleSession("rita");
            rita.inviteInterview("ia-inv", "ia-tinv", 8_000L,
                    new WorldRoleSession.InterviewInviteDraft("inv-a", "investor", "topic",
                            80_000L, 200_000L, 120_000L));
            runtime.advanceTo(9_000L);
            String requestId = InterviewModel.requestId("rita", "inv-a");
            runtime.beginDecisionSession("begin-a");
            openDecision(runtime, "investor", "a-inv");
            openDecision(runtime, "sam", "a-sam");
            String investor = decisionInput(runtime, "a-inv");
            String sam = decisionInput(runtime, "a-sam");
            assertTrue(investor.contains(requestId));
            assertTrue(investor.contains("\"status\":\"PROPOSED\""));
            assertFalse(sam.contains(requestId));
            assertTrue(sam.contains("\"invites\":[]"));
        } finally {
            runtime.close();
            Files.deleteIfExists(journal);
        }
    }

    @Test
    void decisionInputHidesUnreadBodyAndShowsAfterRead() throws Exception {
        for (boolean read : new boolean[]{false, true}) {
            Path journal = Files.createTempFile(Files.createDirectories(Path.of("target", "dialogue-test-tmp")), "in-b-", ".jsonl");
            var opened = WorldWebScenario.openInterviewDialogue(journal);
            WorldRuntime runtime = opened.runtime();
            try {
                runtime.advanceTo(1_000L);
                WorldRoleSession rita = runtime.openRoleSession("rita");
                rita.startReading("ib-rc", "ib-tc", 2_000L, DOC, 1L);
                runtime.advanceTo(7_000L);
                rita = runtime.openRoleSession("rita");
                rita.inviteInterview("ib-inv", "ib-tinv", 8_000L,
                        new WorldRoleSession.InterviewInviteDraft("inv-b", "investor", "topic",
                                80_000L, 200_000L, 120_000L));
                runtime.advanceTo(9_000L);
                String requestId = InterviewModel.requestId("rita", "inv-b");
                WorldRoleSession investor = runtime.openRoleSession("investor");
                investor.respondInterview("ib-resp", "ib-tresp", 10_000L,
                        new WorldRoleSession.InterviewRespondDraft(requestId, true, 80_000L));
                runtime.advanceTo(80_001L);
                rita = runtime.openRoleSession("rita");
                rita.speakInterview("ib-sp1", "ib-tsp1", 81_001L,
                        new WorldRoleSession.DialogueSpeakDraft(requestId, "Q1",
                                "SECRET_BODY_XYZ", 5_000L));
                runtime.advanceTo(81_001L);
                String doc1 = turnDoc("SESSION-" + requestId, 1);
                if (read) {
                    investor = runtime.openRoleSession("investor");
                    investor.startReading("ib-r1", "ib-tr1", 82_001L, doc1, 1L);
                    runtime.advanceTo(87_001L);
                }
                runtime.beginDecisionSession("begin-b" + read);
                openDecision(runtime, "investor", "a-b" + read);
                String input = decisionInput(runtime, "a-b" + read);
                assertTrue(input.contains(doc1));
                assertTrue(input.contains("\"readByMe\":" + read));
                assertEquals(read, input.contains("SECRET_BODY_XYZ"));
            } finally {
                runtime.close();
                Files.deleteIfExists(journal);
            }
        }
    }

    @Test
    void decisionInputStableAcrossReopen() throws Exception {
        Path journal = Files.createTempFile(Files.createDirectories(Path.of("target", "dialogue-test-tmp")), "in-c-", ".jsonl");
        var opened = WorldWebScenario.openInterviewDialogue(journal);
        WorldRuntime runtime = opened.runtime();
        String captured;
        try {
            runtime.advanceTo(1_000L);
            WorldRoleSession rita = runtime.openRoleSession("rita");
            rita.startReading("ic-rc", "ic-tc", 2_000L, DOC, 1L);
            runtime.advanceTo(7_000L);
            rita = runtime.openRoleSession("rita");
            rita.inviteInterview("ic-inv", "ic-tinv", 8_000L,
                    new WorldRoleSession.InterviewInviteDraft("inv-c", "investor", "topic",
                            80_000L, 200_000L, 120_000L));
            runtime.advanceTo(9_000L);
            String requestId = InterviewModel.requestId("rita", "inv-c");
            WorldRoleSession investor = runtime.openRoleSession("investor");
            investor.respondInterview("ic-resp", "ic-tresp", 10_000L,
                    new WorldRoleSession.InterviewRespondDraft(requestId, true, 80_000L));
            runtime.advanceTo(80_001L);
            rita = runtime.openRoleSession("rita");
            rita.speakInterview("ic-sp1", "ic-tsp1", 81_001L,
                    new WorldRoleSession.DialogueSpeakDraft(requestId, "Q1", "q", 5_000L));
            runtime.advanceTo(81_001L);
            runtime.beginDecisionSession("begin-c");
            openDecision(runtime, "investor", "a-c");
            captured = decisionInput(runtime, "a-c");
        } finally {
            runtime.close();
        }
        var reopened = WorldWebScenario.openInterviewDialogue(journal);
        try {
            assertEquals(captured, decisionInput(reopened.runtime(), "a-c"));
        } finally {
            reopened.runtime().close();
            Files.deleteIfExists(journal);
        }
    }

    @Test
    void dialogueToolContractAddsInterviewParametersOnlyInV3() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        var v3 = mapper.readTree(DecisionRun.inputText(
                WorldWebScenario.openInterviewDialogue(
                        Files.createTempFile(Files.createDirectories(Path.of("target", "dialogue-test-tmp")), "tools-", ".jsonl"))
                        .runtime().openRoleSession("rita").view(),
                "test", 0L, List.of(),
                List.of(edu.cufe.auction.world.RoleTradingView.TOOL_INTERVIEW_SPEAK,
                        edu.cufe.auction.world.RoleTradingView.TOOL_INTERVIEW_INVITE),
                WorldWebScenario.dialogueDecisionConfig(),
                DecisionRun.TOOL_CONTRACT_VERSION_V3));
        var speak = findTool(v3, "interview.speak");
        var invite = findTool(v3, "interview.invite");
        assertTrue(speak.get("parameters").size() == 4);
        assertTrue(invite.get("parameters").size() == 6);
        assertTrue(speak.toString().contains("readDurationMillis"));

        var v2 = mapper.readTree(DecisionRun.inputText(
                WorldWebScenario.openInterviewDialogue(
                        Files.createTempFile(Files.createDirectories(Path.of("target", "dialogue-test-tmp")), "tools2-", ".jsonl"))
                        .runtime().openRoleSession("rita").view(),
                "test", 0L, List.of(),
                List.of(edu.cufe.auction.world.RoleTradingView.TOOL_INTERVIEW_INVITE),
                WorldWebScenario.dialogueDecisionConfig(),
                DecisionRun.TOOL_CONTRACT_VERSION_V2));
        assertFalse(v2.has("interview"));
        assertEquals(0, findTool(v2, "interview.invite").get("parameters").size());
        assertEquals(0, DecisionRun.toolParameters("interview.speak").size());
    }

    static com.fasterxml.jackson.databind.JsonNode findTool(
            com.fasterxml.jackson.databind.JsonNode root, String name) {
        for (var node : root.get("tools")) {
            if (node.get("name").asText().equals(name)) {
                return node;
            }
        }
        throw new AssertionError("missing tool " + name);
    }

    static boolean canCiteDoc(WorldRuntime runtime, String person, String docId) {
        InformationModel.State info = runtime.snapshot().information().orElseThrow().information();
        return InformationModel.canCite(info, person, docId, 1L, runtime.snapshot().timeMillis());
    }

    static boolean sessionOpen(WorldRuntime runtime, String sessionId) {
        return runtime.interviews().states().stream()
                .flatMap(s -> s.sessions().stream())
                .anyMatch(s -> s.sessionId().equals(sessionId) && s.open());
    }

    static String turnDoc(String sessionId, int sequence) {
        return "dialogue-turn:" + sessionId.length() + ":" + sessionId + ":" + sequence;
    }
}
