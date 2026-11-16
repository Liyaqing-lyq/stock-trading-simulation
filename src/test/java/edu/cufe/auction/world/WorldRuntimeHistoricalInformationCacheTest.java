package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 精确历史信息投影：提交后仍使用旧观察帧，不能重复重建整条 delta 前缀。 */
class WorldRuntimeHistoricalInformationCacheTest {
    static final class CountingSource implements CommitSource {
        final CommitSource delegate;
        final List<Long> reads = new ArrayList<>();
        final Map<Long, WorldCommit> changed = new HashMap<>();
        final Set<Long> missing = new HashSet<>();
        CountingSource(CommitSource delegate) { this.delegate = delegate; }
        public List<WorldCommit> commits() { return delegate.commits(); }
        public List<WorldCommit> nonSnapshotCommits() { return delegate.nonSnapshotCommits(); }
        public Optional<WorldCommit> commitAt(long position) { reads.add(position); return missing.contains(position) ? Optional.empty() : changed.containsKey(position) ? Optional.of(changed.get(position)) : delegate.commitAt(position); }
        public List<WorldCommit> commitsAfter(long position, int limit) { return delegate.commitsAfter(position, limit); }
    }
    static Field field(String name) throws Exception {
        Field field = WorldRuntime.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    static Object component(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(value);
    }
    static Path journal(String name) throws Exception {
        Path root = Path.of("logs/operating-cycle/task12-tests"); Files.createDirectories(root);
        return Files.createTempDirectory(root, name).resolve("world.journal");
    }
    static InformationModel.State observed(WorldRuntime world, long position, long time) throws Exception {
        Class<?> type = Class.forName("edu.cufe.auction.world.WorldRuntime$MediaTaskBody");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, long.class, long.class, long.class, boolean.class, String.class);
        constructor.setAccessible(true);
        Method method = WorldRuntime.class.getDeclaredMethod("observedInformation", type); method.setAccessible(true);
        return (InformationModel.State) method.invoke(world, constructor.newInstance("diagnostic", time, position, 0L, false, "{}"));
    }
    @Test void committedOldObservationUsesExactProjectionWithoutPrefixReplayOrCursorBacktracking() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal("warm-"))) {
            world.advanceTo(4);
            Object old = field("state").get(world);
            long position = (Long) component(old, "position");
            InformationModel.State expected = (InformationModel.State) component(component(old, "information"), "information");
            world.advanceTo(5);
            Object cursor = field("deltaReadCursor").get(world);
            CountingSource source = new CountingSource((CommitSource) field("commitSource").get(world));
            field("commitSource").set(world, source);
            InformationModel.State actual = observed(world, position, 4);
            assertEquals(InformationModel.toJson(expected), InformationModel.toJson(actual), "exact ordered historical information bytes");
            assertEquals(List.of(position), source.reads, "warm old observation must only resolve its exact target, not replay 1..p");
            assertSame(cursor, field("deltaReadCursor").get(world), "projection hit must not rewind the general decoder cursor");
        }
    }

    static HistoricalInformationCache projectionCache(WorldRuntime world) throws Exception { return (HistoricalInformationCache) field("historicalInformationCache").get(world); }
    static String stateJson(WorldRuntime world) throws Exception {
        Method canonical = WorldRuntime.class.getDeclaredMethod("stateCanonical"); canonical.setAccessible(true);
        return ((com.fasterxml.jackson.databind.ObjectMapper) canonical.invoke(world)).writeValueAsString(field("state").get(world));
    }
    @Test void cacheAdmissionFailureCannotTurnDurableCommitIntoFailure() throws Exception {
        Path file = journal("admission-failure-"); String saved;
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(file)) {
            java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
            var failing = new com.fasterxml.jackson.databind.ObjectMapper() {
                @Override public void writeValue(java.io.OutputStream out, Object value) throws java.io.IOException {
                    failures.incrementAndGet(); throw new java.io.IOException("injected optional admission failure");
                }
            };
            field("historicalInformationCache").set(world, new HistoricalInformationCache(failing, new com.fasterxml.jackson.databind.ObjectMapper()));
            long previous = world.snapshot().position();
            assertDoesNotThrow(() -> world.advanceTo(4));
            assertTrue(failures.get() > 0, "fault must actually occur after durable append");
            assertTrue(world.snapshot().position() > previous); assertEquals(4, world.snapshot().timeMillis());
            assertNull(world.commitFailureCode()); assertEquals(0, HistoricalInformationCacheTest.entries(projectionCache(world)));
            saved = stateJson(world);
        }
        try (WorldRuntime reopened = ComposedIndustryScenario.openCompactAccountableAutonomous(file)) {
            assertEquals(saved, stateJson(reopened), "successful append must really be durable and fully recoverable");
        }
    }

    @Test void captureUnverifiedLookupAndFailedAppendDoNotWarmTrustedFutureFrames() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal("trust-"))) {
            world.advanceTo(4);
            long p = world.snapshot().position();
            HistoricalInformationCache cache = projectionCache(world); cache.clear();
            field("verifiedInformationPosition").setLong(world, p - 1);
            assertNotNull(observed(world, p, 4));
            assertEquals(0, HistoricalInformationCacheTest.entries(cache), "raw decode ahead of audit frontier is not trusted");
            assertEquals(p - 1, field("verifiedInformationPosition").getLong(world));
            field("verifiedInformationPosition").setLong(world, p);
            field("capturing").setBoolean(world, true);
            try {
                assertNotNull(observed(world, p, 4));
                world.advanceTo(5);
                assertTrue(field("capturedCount").getInt(world) > 0);
                assertEquals(p, world.snapshot().position());
                assertEquals(0, HistoricalInformationCacheTest.entries(cache));
                assertEquals(p, field("verifiedInformationPosition").getLong(world));
            } finally { field("capturing").setBoolean(world, false); }
            ((WorldJournal) field("journal").get(world)).close();
            assertThrows(Exception.class, () -> world.advanceTo(6));
            assertEquals(p, world.snapshot().position());
            assertEquals(p, field("verifiedInformationPosition").getLong(world));
            assertEquals(0, HistoricalInformationCacheTest.entries(cache));
        }
    }

    @Test void changedMissingAndMalformedTargetCannotUseCachedOrSamePositionDecodedState() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal("target-"))) {
            world.advanceTo(4); long p = world.snapshot().position();
            CountingSource source = new CountingSource((CommitSource) field("commitSource").get(world)); field("commitSource").set(world, source);
            WorldCommit original = source.delegate.commitAt(p).orElseThrow();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            WorldEvent delta = original.events().stream().filter(e -> e.type().equals(StateDeltaCodec.TYPE)).findFirst().orElseThrow();
            List<List<WorldEvent>> changes = new ArrayList<>();
            for (String name : List.of("baseDigest", "resultDigest", "basePosition", "position", "runId")) {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(delta.payload());
                if (name.endsWith("Position") || name.equals("position")) node.put(name, -1); else node.put(name, "tampered");
                List<WorldEvent> events = new ArrayList<>(original.events());
                events.set(events.indexOf(delta), new WorldEvent(delta.eventId(), delta.type(), json.writeValueAsString(node))); changes.add(events);
            }
            var missingState = new ArrayList<>(original.events()); missingState.remove(delta); changes.add(missingState);
            var duplicateState = new ArrayList<>(original.events()); duplicateState.add(new WorldEvent("duplicate-state", delta.type(), delta.payload())); changes.add(duplicateState);
            var falseReport = new ArrayList<>(original.events()); falseReport.add(new WorldEvent("false-report", "INFORMATION_REPORT_V1",
                    "{\"taskId\":\"fake\",\"commandId\":\"fake\",\"kind\":\"fake\",\"accepted\":true,\"reason\":\"fake\",\"subjectId\":\"fake\",\"readerPartyId\":\"rita\",\"timeMillis\":4,\"position\":" + p + "}")); changes.add(falseReport);
            for (List<WorldEvent> events : changes) {
                source.changed.clear(); assertNotNull(observed(world, p, 4)); // 读取原目标；fallback不准入缓存，同位置游标也不得遮蔽下次篡改。
                source.changed.put(p, new WorldCommit(original.schemaVersion(), p, original.runId(), original.commandId(), original.commandContent(), original.result(), events));
                source.reads.clear(); assertNull(observed(world, p, 4), "changed target must pass through full decoder and be rejected");
                assertTrue(source.reads.size() >= p, "cached/same-position shortcut must not bypass codec gates");
            }
            source.changed.clear(); assertNotNull(observed(world, p, 4)); source.missing.add(p); source.reads.clear();
            assertNull(observed(world, p, 4)); assertEquals(List.of(p), source.reads);
            assertNull(observed(world, -1, 4)); assertNull(observed(world, p + 100, 4));
        }
    }

    @Test void alternatingAcceptedObservationsHitButEvictedObservationsAlwaysUseFallback() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal("fallback-"))) {
            world.advanceTo(4); long p = world.snapshot().position(); String old = InformationModel.toJson(observed(world, p, 4));
            world.advanceTo(5); long q = world.snapshot().position();
            CountingSource source = new CountingSource((CommitSource) field("commitSource").get(world)); field("commitSource").set(world, source);
            for (long requested : new long[]{p, q, p, q}) { source.reads.clear(); assertNotNull(observed(world, requested, requested == p ? 4 : 5)); assertEquals(List.of(requested), source.reads); }
            HistoricalInformationCache cache = projectionCache(world); cache.clear(); source.reads.clear();
            assertEquals(old, InformationModel.toJson(observed(world, p, 4))); assertEquals(p, source.reads.size());
            source.reads.clear(); assertEquals(old, InformationModel.toJson(observed(world, p, 4))); assertEquals(p, source.reads.size());
            assertEquals(0, HistoricalInformationCacheTest.entries(cache), "fallback decoding must not resurrect an evicted identity");
        }
    }

    @Test void realMediaCompletionAndStrictReopenKeepIdenticalStateAndHistoricalBytes() throws Exception {
        Path file = journal("media-"); String saved; String old; long observedPosition;
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(file)) {
            world.advanceTo(1); observedPosition = world.snapshot().position();
            old = InformationModel.toJson(observed(world, observedPosition, 1));
            long duration = world.snapshot().media().orElseThrow().config().proposeMillis();
            world.openRoleSession("rita").proposeStory("cache-proposal", "cache-proposal-task", 1 + duration,
                    new WorldRoleSession.MediaProposeDraft("cache-story", "WIRE", "历史帧缓存机制夹具"));
            world.advanceTo(1 + duration);
            var report = world.snapshot().media().orElseThrow().reports().values().stream().filter(r -> r.kind().equals(PendingTask.MEDIA_STORY_PROPOSE_V1)).findFirst().orElseThrow();
            assertTrue(report.accepted(), report.reason());
            assertEquals(old, InformationModel.toJson(observed(world, observedPosition, 1)));
            saved = stateJson(world);
        }
        try (WorldRuntime reopened = ComposedIndustryScenario.openCompactAccountableAutonomous(file)) {
            assertEquals(saved, stateJson(reopened), "live result and all-command replay result must be byte-identical");
            CountingSource source = new CountingSource((CommitSource) field("commitSource").get(reopened)); field("commitSource").set(reopened, source);
            assertEquals(old, InformationModel.toJson(observed(reopened, observedPosition, 1)));
            assertEquals(List.of(observedPosition), source.reads, "full sequential recovery should warm accepted exact historical projections");
            assertFalse(reopened.branchSupport(reopened.snapshot().position()).supported(), "compact branch support must remain unchanged");
        }
    }

    @Test void actualMediaSubmissionKeepsOldCutDespiteLaterPublicationAndReading() throws Exception {
        Path file = journal("media-old-cut-"); String saved;
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(file)) {
            world.advanceTo(1); WorldRoleSession oldSession = world.openRoleSession("rita");
            long oldPosition = world.snapshot().position();
            String oldBytes = InformationModel.toJson(observed(world, oldPosition, 1));
            MediaRun.Config config = world.snapshot().media().orElseThrow().config();
            long proposedAt = 1 + config.proposeMillis();
            oldSession.proposeStory("propose-old", "propose-old-task", proposedAt,
                    new WorldRoleSession.MediaProposeDraft("old-cut", "WIRE", "旧观察帧不知后来材料"));
            world.advanceTo(proposedAt);
            world.schedulePublishDocument("later-doc", "later-doc-task", proposedAt + 1, "later-public", "alice",
                    InformationModel.Audience.publicAudience(), "后来标题", "后来正文", "真实发布夹具", 1, List.of("later-public"));
            world.advanceTo(proposedAt + 1);
            world.openRoleSession("rita").startReading("later-read", "later-read-task", proposedAt + 2, "later-public", 1);
            world.advanceTo(proposedAt + 3);
            var ref = world.roleView("rita").observations().stream().filter(o -> o.ref().docId().equals("later-public")).findFirst().orElseThrow().ref();
            long now = world.snapshot().timeMillis(); long due = now + config.policyOf("wire").writeMillis();
            oldSession.submitStory("submit-old", "submit-old-task", due,
                    new WorldRoleSession.MediaSubmitDraft(WorldRuntime.mediaStoryKey("rita", "old-cut"), 0, "题", "文", "来源",
                            List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(), ref.index()))));
            PendingTask task = world.snapshot().pending().stream().filter(t -> t.kind().equals(PendingTask.MEDIA_STORY_SUBMIT_V1)).findFirst().orElseThrow();
            Object base = field("state").get(world);
            Method compute = WorldRuntime.class.getDeclaredMethod("computeMediaFrame", base.getClass(), PendingTask.class); compute.setAccessible(true);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            byte[] hot = json.writeValueAsBytes(compute.invoke(world, base, task));
            projectionCache(world).clear();
            byte[] cold = json.writeValueAsBytes(compute.invoke(world, base, task));
            assertArrayEquals(cold, hot, "real media frame output/order must equal complete historical decoding");
            assertEquals(oldBytes, InformationModel.toJson(observed(world, oldPosition, 1)));
            world.advanceTo(due);
            var report = world.snapshot().media().orElseThrow().reports().values().stream().filter(r -> r.kind().equals(PendingTask.MEDIA_STORY_SUBMIT_V1)).findFirst().orElseThrow();
            assertFalse(report.accepted()); assertEquals(MediaModel.REASON_EVIDENCE_NOT_READ, report.reason());
            saved = stateJson(world);
        }
        try (WorldRuntime reopened = ComposedIndustryScenario.openCompactAccountableAutonomous(file)) {
            assertEquals(saved, stateJson(reopened), "all-command replay must preserve the exact real rejection and state bytes");
        }
    }

    @Test void changedDecoderValidIdentityCannotAcquireTrustedCacheAdmission() throws Exception {
        assertReplacedIdentityNeverAdmitted(false);
    }

    @Test void evictedIdentityCannotBeResurrectedByNumericVerifiedFrontier() throws Exception {
        assertReplacedIdentityNeverAdmitted(true);
    }

    private static void assertReplacedIdentityNeverAdmitted(boolean evictOriginal) throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openCompactAccountableAutonomous(journal("replacement-"))) {
            world.advanceTo(4);
            Object originalState = field("state").get(world); long p = world.snapshot().position();
            CountingSource source = new CountingSource((CommitSource) field("commitSource").get(world));
            field("commitSource").set(world, source);
            WorldCommit original = source.delegate.commitAt(p).orElseThrow();
            Method decode = WorldRuntime.class.getDeclaredMethod("decodeSnapshot", WorldCommit.class); decode.setAccessible(true);
            Object previous = decode.invoke(world, source.delegate.commitAt(p - 1).orElseThrow());
            Method canonical = WorldRuntime.class.getDeclaredMethod("stateCanonical"); canonical.setAccessible(true);
            var mapper = (com.fasterxml.jackson.databind.ObjectMapper) canonical.invoke(world);
            var changedJson = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(originalState);
            var documents = (com.fasterxml.jackson.databind.node.ArrayNode) changedJson.path("information").path("information").path("documents");
            assertFalse(documents.isEmpty(), "fixture needs an already-authoritative document version");
            var version = (com.fasterxml.jackson.databind.node.ObjectNode) documents.get(0).path("versions").get(0);
            String changedTitle = "UNVERIFIED-HISTORICAL-IDENTITY";
            assertNotEquals(changedTitle, version.path("title").asText()); version.put("title", changedTitle);
            String delta = StateDeltaCodec.encode(original.runId(), p, mapper.writeValueAsString(previous), mapper.writeValueAsString(changedJson));
            List<WorldEvent> events = new ArrayList<>(original.events());
            for (int i = 0; i < events.size(); i++) if (events.get(i).type().equals(StateDeltaCodec.TYPE))
                events.set(i, new WorldEvent(events.get(i).eventId(), StateDeltaCodec.TYPE, delta));
            WorldCommit replacement = new WorldCommit(original.schemaVersion(), p, original.runId(), original.commandId(), original.commandContent(), original.result(), events);
            Method decodeFrame = WorldRuntime.class.getDeclaredMethod("decodeSnapshot", WorldCommit.class, originalState.getClass()); decodeFrame.setAccessible(true);
            Object replacedState = decodeFrame.invoke(world, replacement, previous); // 完整原 codec 接受其自洽 hash。
            Method validate = WorldRuntime.class.getDeclaredMethod("validate", originalState.getClass(), WorldCommit.class, originalState.getClass()); validate.setAccessible(true);
            InvocationTargetException rejection = assertThrows(InvocationTargetException.class, () -> validate.invoke(world, replacedState, replacement, previous));
            assertInstanceOf(java.io.IOException.class, rejection.getCause());
            assertTrue(rejection.getCause().getMessage().contains("旧资料版本前缀被改写"), rejection.getCause().toString());
            HistoricalInformationCache cache = projectionCache(world); Object identity = field("historicalInformationSource").get(world);
            assertNotNull(cache.find(identity, original), "original target was admitted by a real successful commit");
            if (evictOriginal) {
                for (long time = 5; time <= 68; time++) world.advanceTo(time); // 64 条真实新提交，淘汰原有有界证明。
                assertNull(cache.find(identity, original), "LRU eviction must actually have removed the old identity");
            }
            long frontier = field("verifiedInformationPosition").getLong(world);
            source.changed.put(p, replacement); field("deltaReadCursor").set(world, originalState);
            source.reads.clear(); InformationModel.State first = observed(world, p, 4); int firstReads = source.reads.size();
            boolean admitted = cache.find(identity, replacement) != null;
            source.reads.clear(); InformationModel.State second = observed(world, p, 4); int secondReads = source.reads.size();
            assertEquals(changedTitle, first.documents().get(0).versions().get(0).title(), "unchanged original fallback may return decoder-valid replacement");
            assertEquals(InformationModel.toJson(first), InformationModel.toJson(second), "fix does not redefine original decoder results");
            assertAll(
                    () -> assertFalse(admitted, "a numeric frontier cannot admit a different unvalidated identity"),
                    () -> assertTrue(cache.find(identity, replacement) == null, "repeated fallback cannot establish authoritative trust"),
                    () -> assertEquals(p, firstReads, "first mismatch/missing entry uses complete original prefix decoding"),
                    () -> assertEquals(p, secondReads, "repeated mismatch/missing entry must remain a miss, not become one-read hit"),
                    () -> assertEquals(frontier, field("verifiedInformationPosition").getLong(world), "read fallback must not advance authoritative frontier"));
        }
    }
}
