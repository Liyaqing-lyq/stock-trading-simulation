package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static edu.cufe.auction.world.WorldRuntimeHistoricalInformationCacheTest.*;

/** 本地脚本回执真实进入媒体排程；读取计数证明无需重扫已核验观察前缀。 */
class VerifiedFrameTimeMediaTest {
    static Path journal() throws Exception {
        Path directory = Path.of("logs/operating-cycle/task18-implementation/journals");
        Files.createDirectories(directory);
        return Files.createTempDirectory(directory, "media-").resolve("world.journal");
    }

    @Test void scriptedMediaReplyUsesVerifiedObservationWithoutHistoricalPrefixReads() throws Exception {
        Path journal = journal(); String saved;
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal)) {
            world.advanceTo(1);
            world.beginDecisionSession("local-session");
            assertEquals("DECIDING:1", world.openDecisions("local-open", List.of(
                    new DecisionRun.Request("rita", "local-proposal", "wake",
                            new DecisionModel.Route("research", "key-1", "deepseek-flash", 0, 20, 100000, 128, 1)))));
            var dispatched = world.dispatchLiveDecision("local-dispatch", "local-proposal");
            assertEquals("DISPATCHED", dispatched.verdict());
            var call = dispatched.call();
            // 决定观察帧已真实提交核验；另有后续提交使通用游标越过 cutoff。
            long p = world.snapshot().decisions().orElseThrow().state().decisions().stream().filter(d -> d.actionId().equals("local-proposal")).findFirst().orElseThrow().cutoffPosition();
            WorldCommit target = ((CommitSource) field("commitSource").get(world)).commitAt(p).orElseThrow();
            assertNotNull(projectionCache(world).find(field("historicalInformationSource").get(world), target));
            assertTrue((Long) component(field("deltaReadCursor").get(world), "position") > p);
            CountingSource source = new CountingSource((CommitSource) field("commitSource").get(world));
            field("commitSource").set(world, source);
            var json = new ObjectMapper();
            var input = json.readTree(call.inputText());
            String objective = input.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText();
            String body = json.writeValueAsString(Map.of("tool", RoleTradingView.TOOL_MEDIA_PROPOSE,
                    "decision", Map.of("objective", objective, "basis", "基于当前媒体任职开展提案", "nextReviewAt", 10000),
                    "args", Map.of("storyId", "verified-frame", "template", "WIRE", "topic", "本地历史观察")));
            var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                    call.recoveryGeneration(), body, DecisionModel.Usage.unknownCost(1, 20, 5), "test:local");
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
            assertEquals(1, world.drainDecisionCallbacks());
            assertTrue(world.snapshot().pending().stream().anyMatch(t -> t.kind().equals(PendingTask.MEDIA_STORY_PROPOSE_V1)), world.decisionAudits().toString());
            List<Long> acceptanceReads = List.copyOf(source.reads);
            Files.writeString(journal.getParent().resolve("acceptance-reads.json"),
                    new ObjectMapper().writeValueAsString(Map.of("observedPosition", p, "reads", acceptanceReads)));
            assertEquals(List.of(p), acceptanceReads, "real reply acceptance must authenticate only p, never replay 1..p");
            long due = DecisionModel.actionDueMillis(world.snapshot().decisions().orElseThrow().state(), "local-proposal");
            world.advanceTo(due);
            var report = world.snapshot().media().orElseThrow().reports().values().stream()
                    .filter(r -> r.kind().equals(PendingTask.MEDIA_STORY_PROPOSE_V1)).findFirst().orElseThrow();
            assertTrue(report.accepted(), report.reason());
            saved = stateJson(world);
        }
        try (WorldRuntime reopened = ComposedIndustryScenario.openCompactAccountableAutonomous(journal)) {
            assertEquals(saved, stateJson(reopened), "independent original-command recovery must reproduce all state and reports");
        }
    }
    static Long verifiedTime(WorldRuntime world, long position) throws Exception {
        var method = WorldRuntime.class.getDeclaredMethod("verifiedFrameTimeAtOrNull", long.class);
        method.setAccessible(true); return (Long) method.invoke(world, position);
    }

    @Test void missingChangedUnverifiedAndCapturedFramesCannotAcquireTimeProof() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal())) {
            world.advanceTo(4); long p = world.snapshot().position();
            Object originalState = field("state").get(world);
            world.beginDecisionSession("same-time"); long q = world.snapshot().position();
            assertTrue(q > p); assertEquals(4, world.snapshot().timeMillis());
            CountingSource source = new CountingSource((CommitSource) field("commitSource").get(world));
            field("commitSource").set(world, source);
            assertEquals(4L, verifiedTime(world, p)); assertEquals(List.of(p), source.reads);
            source.reads.clear(); assertEquals(4L, verifiedTime(world, q)); assertEquals(List.of(q), source.reads);
            source.missing.add(p); source.reads.clear(); assertNull(verifiedTime(world, p)); assertEquals(List.of(p), source.reads);
            source.missing.clear(); assertNull(verifiedTime(world, 0)); assertNull(verifiedTime(world, -1));
            assertNull(verifiedTime(world, q + 100));
            WorldCommit original = source.delegate.commitAt(p).orElseThrow();
            source.changed.put(p, source.delegate.commitAt(q).orElseThrow());
            assertNull(verifiedTime(world, p), "actual returned position must equal the requested position");
            source.changed.clear();
            var decode = WorldRuntime.class.getDeclaredMethod("decodeSnapshot", WorldCommit.class); decode.setAccessible(true);
            Object previous = decode.invoke(world, source.delegate.commitAt(p - 1).orElseThrow());
            var canonical = WorldRuntime.class.getDeclaredMethod("stateCanonical"); canonical.setAccessible(true);
            var mapper = (ObjectMapper) canonical.invoke(world);
            var changed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(originalState);
            changed.put("timeMillis", 3);
            String delta = StateDeltaCodec.encode(original.runId(), p, mapper.writeValueAsString(previous), mapper.writeValueAsString(changed));
            List<WorldEvent> events = new ArrayList<>(original.events());
            for (int i = 0; i < events.size(); i++) if (events.get(i).type().equals(StateDeltaCodec.TYPE))
                events.set(i, new WorldEvent(events.get(i).eventId(), StateDeltaCodec.TYPE, delta));
            WorldCommit replacement = new WorldCommit(original.schemaVersion(), p, original.runId(), original.commandId(), original.commandContent(), original.result(), events);
            source.changed.put(p, replacement);
            long frontier = field("verifiedInformationPosition").getLong(world);
            for (int attempt = 0; attempt < 2; attempt++) {
                field("deltaReadCursor").set(world, originalState); source.reads.clear();
                assertEquals(3L, verifiedTime(world, p), "a changed same-position target must pass the original full decoder");
                assertEquals(p, source.reads.size());
                assertNull(projectionCache(world).findTime(field("historicalInformationSource").get(world), replacement),
                        "decoder-valid replacement has no authoritative admission");
                assertEquals(frontier, field("verifiedInformationPosition").getLong(world));
            }
            source.changed.clear(); projectionCache(world).clear();
            field("verifiedInformationPosition").setLong(world, p - 1);
            field("capturing").setBoolean(world, true);
            try {
                source.reads.clear(); assertEquals(4L, verifiedTime(world, p));
                assertEquals(p, source.reads.size()); assertEquals(0, HistoricalInformationCacheTest.entries(projectionCache(world)));
                assertEquals(p - 1, field("verifiedInformationPosition").getLong(world));
            } finally { field("capturing").setBoolean(world, false); }
        }
    }

    @Test void malformedTimeTargetCannotReuseSamePositionCursor() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal())) {
            world.advanceTo(4); long p = world.snapshot().position(); Object originalState = field("state").get(world);
            CountingSource source = new CountingSource((CommitSource) field("commitSource").get(world));
            field("commitSource").set(world, source);
            WorldCommit original = source.delegate.commitAt(p).orElseThrow();
            var json = new ObjectMapper();
            WorldEvent delta = original.events().stream().filter(e -> e.type().equals(StateDeltaCodec.TYPE)).findFirst().orElseThrow();
            for (String name : List.of("baseDigest", "resultDigest", "basePosition", "position", "runId")) {
                var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(delta.payload());
                if (name.endsWith("Position") || name.equals("position")) payload.put(name, -1); else payload.put(name, "tampered");
                List<WorldEvent> events = new ArrayList<>(original.events());
                events.set(events.indexOf(delta), new WorldEvent(delta.eventId(), delta.type(), json.writeValueAsString(payload)));
                WorldCommit changed = new WorldCommit(original.schemaVersion(), p, original.runId(), original.commandId(), original.commandContent(), original.result(), events);
                source.changed.put(p, changed); source.reads.clear(); field("deltaReadCursor").set(world, originalState);
                assertNull(verifiedTime(world, p), "invalid delta cannot borrow old time: " + name);
                assertEquals(p, source.reads.size(), "changed target must really traverse its prefix");
                assertNull(projectionCache(world).findTime(field("historicalInformationSource").get(world), changed));
            }
        }
    }

    @Test void realSchedulingRejectsMissingPositionAndWrongCachedObservationTime() throws Exception {
        for (boolean missing : List.of(false, true)) {
            try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal())) {
                world.advanceTo(4); long p = world.snapshot().position();
                var actual = world.openRoleSession("rita");
                var malformed = new WorldRoleSession(world, actual.personPartyId(), missing ? 4 : 3,
                        actual.appointments(), missing ? null : p);
                CountingSource source = new CountingSource((CommitSource) field("commitSource").get(world));
                field("commitSource").set(world, source);
                long due = 4 + world.snapshot().media().orElseThrow().config().proposeMillis();
                var failure = assertThrows(java.io.IOException.class, () -> malformed.proposeStory("invalid-observation", "invalid-observation-task", due,
                        new WorldRoleSession.MediaProposeDraft("invalid-observation", "WIRE", "观察边界")));
                assertTrue(failure.getMessage().contains(missing ? "媒体待办缺少真实观察帧" : "媒体待办的观察帧不可解析"), failure.toString());
                assertEquals(p, world.snapshot().position(), "invalid observation must never append a candidate");
                assertEquals(missing ? List.of() : List.of(p), source.reads, "wrong cached time still authenticates the real target exactly once");
            }
        }
    }

}
