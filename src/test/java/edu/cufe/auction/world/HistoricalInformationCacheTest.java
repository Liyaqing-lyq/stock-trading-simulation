package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static edu.cufe.auction.world.MediaEvidenceFixtures.*;

/** 缓存是可丢弃的派生数据：身份、实际输出顺序和硬资源边界均独立核对。 */
class HistoricalInformationCacheTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static HistoricalInformationCache cache() { return new HistoricalInformationCache(JSON, JSON); }
    static WorldCommit commit(long p) { return new WorldCommit(1, p, "run", "command" + p, "原命令", "OK",
            List.of(new WorldEvent("event" + p, "TYPE_V1", "正文"), new WorldEvent("second" + p, "TYPE_V2", "第二条"))); }
    static Object field(Object value, String name) throws Exception { Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value); }
    static int entries(HistoricalInformationCache cache) throws Exception { return ((Map<?, ?>) field(cache, "entries")).size(); }
    static int bytes(HistoricalInformationCache cache) throws Exception { return (Integer) field(cache, "bytes"); }

    @Test void everyCommitFieldEventOrderAndSourceIdentityParticipateInCacheIdentity() {
        assertEquals(List.of("schemaVersion", "position", "runId", "commandId", "commandContent", "result", "events"),
                Arrays.stream(WorldCommit.class.getRecordComponents()).map(RecordComponent::getName).toList());
        assertThrows(IllegalArgumentException.class, () -> new WorldCommit(2, 1, "run", "id", "", "", List.of()));
        Object source = new Object(); HistoricalInformationCache cache = cache(); WorldCommit original = commit(1);
        var events = original.events();
        List<WorldCommit> changed = List.of(
                new WorldCommit(1, 2, "run", original.commandId(), original.commandContent(), original.result(), events),
                new WorldCommit(1, 1, "other", original.commandId(), original.commandContent(), original.result(), events),
                new WorldCommit(1, 1, "run", "other", original.commandContent(), original.result(), events),
                new WorldCommit(1, 1, "run", original.commandId(), "changed", original.result(), events),
                new WorldCommit(1, 1, "run", original.commandId(), original.commandContent(), "changed", events),
                new WorldCommit(1, 1, "run", original.commandId(), original.commandContent(), original.result(), List.of(events.get(1), events.get(0))),
                new WorldCommit(1, 1, "run", original.commandId(), original.commandContent(), original.result(), List.of(events.get(0))),
                new WorldCommit(1, 1, "run", original.commandId(), original.commandContent(), original.result(), List.of(new WorldEvent("changed", "TYPE_V1", "正文"), events.get(1))),
                new WorldCommit(1, 1, "run", original.commandId(), original.commandContent(), original.result(), List.of(new WorldEvent("event1", "changed", "正文"), events.get(1))),
                new WorldCommit(1, 1, "run", original.commandId(), original.commandContent(), original.result(), List.of(new WorldEvent("event1", "TYPE_V1", "changed"), events.get(1))));
        for (WorldCommit candidate : changed) {
            cache.remember(source, original, information(), 123L);
            assertNull(cache.findTime(source, candidate), candidate.toString());
            assertNull(cache.find(source, candidate), candidate.toString());
        }
        cache.remember(source, original, information(), 123L);
        assertNull(cache.findTime(new Object(), original));
        assertNull(cache.find(new Object(), original), "equal positions from another source must not alias");
        assertEquals(123L, cache.findTime(source, original));
        assertNotNull(cache.find(source, original));
        var left = new WorldCommit(1, 1, "run", "ab", "c", "OK", List.of());
        var right = new WorldCommit(1, 1, "run", "a", "bc", "OK", List.of());
        cache.remember(source, left, information(), 123L); assertNull(cache.find(source, right), "length prefixes prevent concatenation collisions");
    }

    @Test void entryLimitEvictsLeastRecentlyUsedAndDoesNotRetainWorldGraphs() throws Exception {
        Object source = new Object(); HistoricalInformationCache cache = cache();
        for (int i = 1; i <= 64; i++) cache.remember(source, commit(i), information(), 123L);
        assertNotNull(cache.find(source, commit(1)));
        cache.remember(source, commit(65), information(), 123L);
        assertEquals(64, entries(cache)); assertNull(cache.findTime(source, commit(2))); assertNull(cache.find(source, commit(2))); assertNotNull(cache.find(source, commit(1)));
        for (Object entry : ((Map<?, ?>) field(cache, "entries")).values()) {
            assertTrue(Arrays.stream(entry.getClass().getRecordComponents()).allMatch(c -> c.getType() == byte[].class || c.getType() == long.class),
                    "entry may retain only digests/projection bytes and fixed time, not WorldCommit/WorldState graphs");
        }
        cache.clear(); assertEquals(0, entries(cache)); assertEquals(0, bytes(cache));
    }

    @Test void payloadLimitEvictsAndSingleOversizeProjectionIsNotAdmitted() throws Exception {
        Object source = new Object(); HistoricalInformationCache cache = cache();
        var large = InformationModel.publish(information(), "large", CHAIR, InformationModel.Audience.publicAudience(),
                new InformationModel.VersionDraft("large", "x".repeat(3_000_000), "source", 0, 1, List.of("large")));
        for (int i = 1; i <= 3; i++) cache.remember(source, commit(i), large, 123L);
        assertTrue(bytes(cache) <= 8 * 1024 * 1024); assertEquals(2, entries(cache)); assertNull(cache.findTime(source, commit(1))); assertNull(cache.find(source, commit(1)));
        var oversize = InformationModel.publish(information(), "oversize", CHAIR, InformationModel.Audience.publicAudience(),
                new InformationModel.VersionDraft("large", "x".repeat(9_000_000), "source", 0, 1, List.of("large")));
        cache.remember(source, commit(4), oversize, 123L);
        assertNull(cache.findTime(source, commit(4))); assertNull(cache.find(source, commit(4))); assertEquals(2, entries(cache)); assertTrue(bytes(cache) <= 8 * 1024 * 1024);
    }

    @Test void ordinarySerializationAndParseFailuresAreMissesButFatalErrorsAreNotSwallowed() throws Exception {
        Object source = new Object();
        ObjectMapper failing = new ObjectMapper() { @Override public void writeValue(OutputStream out, Object value) throws IOException { throw new IOException("injected admission failure"); } };
        HistoricalInformationCache failingCache = new HistoricalInformationCache(failing, JSON);
        assertDoesNotThrow(() -> failingCache.remember(source, commit(1), information(), 123L)); assertEquals(0, entries(failingCache));
        HistoricalInformationCache cache = cache(); cache.remember(source, commit(1), information(), 123L);
        Object entry = ((Map<?, ?>) field(cache, "entries")).values().iterator().next();
        Method payload = entry.getClass().getDeclaredMethod("information"); payload.setAccessible(true);
        Arrays.fill((byte[]) payload.invoke(entry), (byte) '!');
        assertNull(cache.find(source, commit(1))); assertEquals(0, entries(cache)); assertEquals(0, bytes(cache));
        ObjectMapper fatal = new ObjectMapper() { @Override public void writeValue(OutputStream out, Object value) { throw new AssertionError("fatal probe"); } };
        HistoricalInformationCache fatalCache = new HistoricalInformationCache(fatal, JSON);
        assertThrows(AssertionError.class, () -> fatalCache.remember(source, commit(1), information(), 123L));
    }

    @Test void orderedRoundtripPreservesActualMediaReadPrivacyRecallAndLaterFacts() throws Exception {
        Object source = new Object(); HistoricalInformationCache cache = cache();
        var beforeRead = primary(information(), "z-public", CHAIR, 0);
        beforeRead = internal(beforeRead, "a-private", CFO, 0);
        var old = read(beforeRead, roles(), REPORTER, "z-public", 1, 0);
        old = read(old, roles(), REPORTER, "a-private", 1, 100);
        var later = primary(old, "future-public", CFO, 200); // 与旧位置相同时刻，但更晚发布帧。
        later = read(later, roles(), REPORTER, "future-public", 1, 200);
        cache.remember(source, commit(1), beforeRead, 123L); cache.remember(source, commit(2), old, 123L); cache.remember(source, commit(3), later, 123L);
        var projected = cache.find(source, commit(2));
        assertArrayEquals(JSON.writeValueAsBytes(old), JSON.writeValueAsBytes(projected), "all ordered lists and exact strings");
        assertFalse(InformationModel.canCite(cache.find(source, commit(1)), REPORTER, "z-public", 1, 200));
        assertTrue(InformationModel.canCite(projected, REPORTER, "z-public", 1, 200));
        assertFalse(InformationModel.canCite(projected, OUTSIDER, "a-private", 1, 200));
        assertFalse(InformationModel.canCite(projected, REPORTER, "future-public", 1, 300));
        assertTrue(InformationModel.canCite(cache.find(source, commit(3)), REPORTER, "future-public", 1, 300));
        var media = proposed(policy(), "story", MediaModel.TemplateKind.WIRE, 200);
        var ref = ref(old, REPORTER, "z-public", 1);
        var request = new MediaModel.SubmitRequest("story", 0, "标题", "正文", "来源", List.of(ref), 300, 300 + WRITE);
        var expected = MediaModel.submit(media, policy(), roles(), old, REPORTER, request, List.of());
        var actual = MediaModel.submit(media, policy(), roles(), projected, REPORTER, request, List.of());
        assertTrue(expected.accepted(), expected.reason());
        assertArrayEquals(JSON.writeValueAsBytes(expected), JSON.writeValueAsBytes(actual), "actual media output including draft material order");
        assertArrayEquals(JSON.writeValueAsBytes(MediaModel.view(media, roles(), old, EDITOR, 200)),
                JSON.writeValueAsBytes(MediaModel.view(media, roles(), projected, EDITOR, 200)));
        for (long cut : new long[]{200, 201, 2_000_000}) {
            assertArrayEquals(JSON.writeValueAsBytes(CognitionRecall.recall(old, REPORTER, cut, forgetfulPolicy().recallPolicy())),
                    JSON.writeValueAsBytes(CognitionRecall.recall(projected, REPORTER, cut, forgetfulPolicy().recallPolicy())));
        }
        var privateRef = ref(old, REPORTER, "a-private", 1);
        var privateRequest = new MediaModel.SubmitRequest("story", 0, "标题", "正文", "来源", List.of(privateRef), 300, 300 + WRITE);
        var privateOld = MediaModel.submit(media, policy(), roles(), old, REPORTER, privateRequest, List.of());
        var privateCached = MediaModel.submit(media, policy(), roles(), projected, REPORTER, privateRequest, List.of());
        assertFalse(privateCached.accepted()); assertArrayEquals(JSON.writeValueAsBytes(privateOld), JSON.writeValueAsBytes(privateCached));
    }
    @Test void timeMetadataIsExactAndLivesOnlyWithItsAdmittedProjection() throws Exception {
        Object source = new Object(); HistoricalInformationCache cache = cache();
        cache.remember(source, commit(1), information(), 0L);
        cache.remember(source, commit(2), information(), 0L);
        cache.remember(source, commit(3), information(), Long.MAX_VALUE);
        assertEquals(0L, cache.findTime(source, commit(1)));
        assertEquals(0L, cache.findTime(source, commit(2)), "same millisecond does not collapse positions");
        assertEquals(Long.MAX_VALUE, cache.findTime(source, commit(3)));
        assertNull(cache.findTime(source, commit(4)), "same source alone cannot create time proof");
        cache.remember(source, commit(1), null, 99L);
        assertNull(cache.findTime(source, commit(1)), "no projection means no retained time side table");
        assertEquals(2, entries(cache));
        cache.clear(); assertNull(cache.findTime(source, commit(2)));
    }

}
