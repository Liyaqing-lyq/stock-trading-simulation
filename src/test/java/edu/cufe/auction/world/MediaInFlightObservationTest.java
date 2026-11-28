package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static edu.cufe.auction.world.WorldRuntimeHistoricalInformationCacheTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaInFlightObservationTest {
    @TempDir Path dir;

    private static CountingSource countReads(WorldRuntime world) throws Exception {
        var source = new CountingSource((CommitSource) field("commitSource").get(world));
        field("commitSource").set(world, source);
        return source;
    }

    @Test void sharedCutIsReleasedOnlyAfterBothEditorsOrReportersChooseOtherWork() throws Exception {
        try (var world = ComposedIndustryScenario.openCompactAccountableAutonomous(dir.resolve("wait.journal"))) {
            world.beginDecisionSession("session");
            long cut = world.snapshot().position();
            var route = new DecisionModel.Route("media", "key-1", "deepseek-flash", 1, 500, 5000000, 512, 4, "lifecycle-v1");
            String wake = world.snapshot().decisions().orElseThrow().state().config().thinkMillisByWake().keySet().iterator().next();
            world.openDecisions("open", List.of(new DecisionRun.Request("rita", "writer", wake, route),
                    new DecisionRun.Request("ed", "editor", wake, route)));
            var active = (Map<?, ?>) field("activeMediaObservations").get(world);
            assertEquals(java.util.Set.of(cut), active.keySet());
            for (String action : List.of("writer", "editor")) {
                var call = world.dispatchLiveDecision("send-" + action, action).call();
                var json = new ObjectMapper();
                var body = json.createObjectNode().put("tool", DecisionModel.TOOL_WAIT);
                body.putObject("decision").put("objective", json.readTree(call.inputText()).at("/engineering/deliberation/objectives/0/id").asText())
                        .put("basis", "待资料发布后复查").put("nextReviewAt", 10000);
                var reply = new DecisionModel.Reply(call.runId(), action, call.attemptId(), call.attemptNo(),
                        call.recoveryGeneration(), body.toString(), DecisionModel.Usage.unknownCost(1, 10, 30), "test:local");
                assertEquals(WorldRuntime.CompletionOffer.QUEUED, world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
                assertEquals(1, world.drainDecisionCallbacks());
                assertEquals(DecisionModel.Outcome.WAIT, DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(), action).orElseThrow().outcome());
                assertEquals(action.equals("writer"), active.containsKey(cut));
            }
        }
    }

    @Test void confirmedModelCutSurvivesEvictionAndStrictReopenUntilMediaWorkFinishes() throws Exception {
        Path journal = dir.resolve("model.journal");
        long cut;
        long due;
        String input;
        String atCut;
        try (var world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal)) {
            world.beginDecisionSession("session");
            cut = world.snapshot().position();
            long now = world.snapshot().timeMillis();
            atCut = InformationModel.toJson(observed(world, cut, now));
            var route = new DecisionModel.Route("media", "key-1", "deepseek-flash", 1, 500, 5000000, 512, 4, "lifecycle-v1");
            String wake = world.snapshot().decisions().orElseThrow().state().config().thinkMillisByWake().keySet().iterator().next();
            world.openDecisions("open", List.of(new DecisionRun.Request("rita", "story", wake, route)));
            var call = world.dispatchLiveDecision("send", "story").call();
            input = call.inputText();
            var json = new ObjectMapper();
            var replyBody = json.createObjectNode().put("tool", RoleTradingView.TOOL_MEDIA_PROPOSE);
            replyBody.putObject("args").put("storyId", "local").put("template", "WIRE").put("topic", "拟核对本地公司资料");
            replyBody.putObject("decision").put("objective", json.readTree(input).at("/engineering/deliberation/objectives/0/id").asText())
                    .put("basis", "核对选题所需资料").put("nextReviewAt", now + 10000);
            var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                    call.recoveryGeneration(), replyBody.toString(), DecisionModel.Usage.unknownCost(1, 10, 30), "test:local");
            assertEquals(WorldRuntime.CompletionOffer.QUEUED, world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
            assertEquals(1, world.drainDecisionCallbacks());
            due = world.snapshot().pending().stream().filter(t -> t.kind().equals(PendingTask.MEDIA_STORY_PROPOSE_V1))
                    .findFirst().orElseThrow().dueTimeMillis();
            for (int tick = 1; tick <= HistoricalInformationCache.MAX_ENTRIES + 3; tick++) world.advanceTo(now + tick);
            assertTrue(world.snapshot().timeMillis() < due);
            assertTrue(world.snapshot().position() - cut > HistoricalInformationCache.MAX_ENTRIES);
        }
        try (var world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal)) {
            var decision = DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(), "story").orElseThrow();
            assertEquals(input, decision.inputText());
            assertEquals(cut, decision.cutoffPosition());
            var source = countReads(world);
            var original = source.delegate.commitAt(cut).orElseThrow();
            assertNull(projectionCache(world).find(field("historicalInformationSource").get(world), original));
            var cursor = field("deltaReadCursor").get(world);
            assertEquals(atCut, InformationModel.toJson(observed(world, cut, decision.cutoffTimeMillis())));
            assertEquals(List.of(cut), source.reads);
            assertSame(cursor, field("deltaReadCursor").get(world));
            source.missing.add(cut);
            assertNull(observed(world, cut, decision.cutoffTimeMillis()));
            source.missing.clear();
            source.changed.put(cut, new WorldCommit(original.schemaVersion(), cut, original.runId(), original.commandId(),
                    original.commandContent(), original.result() + "-changed", original.events()));
            assertNull(observed(world, cut, decision.cutoffTimeMillis()));
            source.changed.clear();
            world.advanceTo(due);
            var report = world.snapshot().media().orElseThrow().reports().values().stream()
                    .filter(r -> r.kind().equals(PendingTask.MEDIA_STORY_PROPOSE_V1)).findFirst().orElseThrow();
            assertTrue(report.accepted(), report.reason());
            assertTrue(((Map<?, ?>) field("activeMediaObservations").get(world)).isEmpty(),
                    "已结算且没有同位置在途决定时须释放资料");
        }
        try (var reopened = ComposedIndustryScenario.openCompactAccountableAutonomous(journal)) {
            assertEquals(1, reopened.snapshot().media().orElseThrow().stateOf("wire").stories().size());
        }
    }

    @Test void laterPublicationAtTheSameMillisecondCannotEnterAnInFlightObservation() throws Exception {
        Path complete = dir.resolve("publications.journal");
        Path manual = dir.resolve("manual.journal");
        String runId;
        long beforeSecond;
        try (var world = ComposedIndustryScenario.openCompactAccountableAutonomous(complete)) {
            runId = world.profile().manifest().runId();
            long due = world.snapshot().timeMillis() + 1;
            for (String doc : List.of("proposal-a", "proposal-b"))
                world.schedulePublishDocument(doc, doc, due, doc, "alice", InformationModel.Audience.publicAudience(),
                        doc, "正文", "出处", 1, List.of());
            world.advanceTo(due);
            beforeSecond = world.snapshot().position() - 1;
        }
        int prefixLength;
        try (var archive = new WorldJournal(complete, runId)) {
            prefixLength = Math.toIntExact(archive.endOffsetOf(beforeSecond));
        }
        try (var input = Files.newInputStream(complete)) {
            Files.write(manual, input.readNBytes(prefixLength));
        }
        try (var world = ComposedIndustryScenario.openCompactAccountableAutonomous(manual)) {
            long now = world.snapshot().timeMillis();
            long cut = world.snapshot().position();
            var later = List.of("proposal-a", "proposal-b").stream().filter(doc -> !world.canReadDocumentVersion("rita", doc, 1)).toList();
            assertEquals(1, later.size());
            String second = later.get(0);
            long due = now + world.snapshot().media().orElseThrow().config().proposeMillis();
            world.openRoleSession("rita").proposeStory("propose", "propose-t", due,
                    new WorldRoleSession.MediaProposeDraft("local", "WIRE", "准备查证资料"));
            world.advanceTo(now);
            assertTrue(world.canReadDocumentVersion("rita", second, 1));
            projectionCache(world).clear();
            var source = countReads(world);
            assertTrue(observed(world, cut, now).documents().stream().noneMatch(d -> d.docId().equals(second)));
            assertEquals(List.of(cut), source.reads);
            field("verifiedInformationPosition").setLong(world, cut - 1);
            field("deltaReadCursor").set(world, null);
            source.reads.clear();
            assertTrue(observed(world, cut, now).documents().stream().noneMatch(d -> d.docId().equals(second)));
            assertTrue(source.reads.contains(1L), "未至可信前沿时只能走原始历史解码");
        }
    }

    @Test void delayedManualSessionsKeepTheirOwnPhysicalCutsUntilTheirTasksFinish() throws Exception {
        Path journal = dir.resolve("late-submissions.journal");
        long writerCut, editorCut, writerTime, editorTime, proposalDue, cancelDue;
        Map<?, ?> retained;
        try (var world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal)) {
            writerTime = world.snapshot().timeMillis();
            writerCut = world.snapshot().position();
            editorTime = writerTime + 1;
            world.advanceTo(editorTime);
            editorCut = world.snapshot().position();
            for (int i = 1; i <= HistoricalInformationCache.MAX_ENTRIES + 1; i++) world.advanceTo(editorTime + i);
            var source = countReads(world);
            var original = source.delegate.commitAt(writerCut).orElseThrow();
            assertNull(projectionCache(world).find(field("historicalInformationSource").get(world), original));
            // 修改结果仍可由快照解码，只有确认批次身份能识别这次替换。
            source.changed.put(writerCut, new WorldCommit(original.schemaVersion(), writerCut, original.runId(),
                    original.commandId(), original.commandContent(), "changed", original.events()));
            assertThrows(IOException.class, () -> world.openRoleSession("rita", writerCut));
            source.changed.clear();
            source.missing.add(writerCut);
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession("rita", writerCut));
            source.missing.clear();
            var writer = world.openRoleSession("rita", writerCut);
            source.reads.clear();
            assertEquals(writer, world.openRoleSession("rita", writerCut));
            assertEquals(List.of(writerCut), source.reads);
            var editor = world.openRoleSession("ed", editorCut);
            var config = world.snapshot().media().orElseThrow().config();
            proposalDue = world.snapshot().timeMillis() + config.proposeMillis();
            cancelDue = world.snapshot().timeMillis() + config.cancelMillis();
            var draft = new WorldRoleSession.MediaProposeDraft("late", "WIRE", "核对旧观察时点的资料");
            assertEquals("SCHEDULED:propose-task", writer.proposeStory("propose", "propose-task", proposalDue, draft));
            assertEquals("SCHEDULED:propose-task", writer.proposeStory("propose", "propose-task", proposalDue, draft));
            editor.cancelMediaWork("cancel", "cancel-task", cancelDue);
            retained = (Map<?, ?>) field("activeMediaObservations").get(world);
            assertEquals(Set.of(writerCut, editorCut), retained.keySet());
        }
        assertTrue(retained.isEmpty());
        try (var world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal)) {
            retained = (Map<?, ?>) field("activeMediaObservations").get(world);
            assertEquals(Set.of(writerCut, editorCut), retained.keySet());
            var source = countReads(world);
            for (int i = 0; i < 2; i++) {
                assertNotNull(observed(world, writerCut, writerTime));
                assertNotNull(observed(world, editorCut, editorTime));
            }
            assertEquals(List.of(writerCut, editorCut, writerCut, editorCut), source.reads);
            world.advanceTo(Math.max(proposalDue, cancelDue));
            var reports = world.snapshot().media().orElseThrow().reports().values();
            assertTrue(reports.stream().anyMatch(r -> r.personPartyId().equals("rita") && r.accepted()));
            assertTrue(reports.stream().anyMatch(r -> r.personPartyId().equals("ed") && !r.accepted()));
            assertTrue(retained.isEmpty());
            assertNull(field("lastHistoricalMediaObservation").get(world));
        }
    }
}
