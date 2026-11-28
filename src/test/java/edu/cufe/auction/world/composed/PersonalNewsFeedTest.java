package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.world.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** 在同一组真正发表的平台原件上对照本人的阅读、现职、探索位与付诸阅读的版本。 */
class PersonalNewsFeedTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    private String post(WorldRuntime w, String author, String title) throws Exception {
        var platform = w.groupsRun().platformConfig();
        long due = w.snapshot().timeMillis() + platform.postMillis();
        String key = "news-" + due;
        assertEquals("SCHEDULED:" + key, w.openRoleSession(author).postToPlatform(key, key, due,
                new WorldRoleSession.PlatformPostDraft(title, "正文单独读取", List.of())));
        w.advanceTo(due + platform.deliveryMillis());
        return w.personalNewsFeed(author).stream().filter(e -> title.equals(e.title())).findFirst().orElseThrow().docId();
    }

    private void read(WorldRuntime w, String actor, String doc) throws Exception {
        long start = w.snapshot().timeMillis() + 1;
        w.openRoleSession(actor).startReading("read-" + start, "reading-" + start, start, doc, 1);
        w.advanceTo(start);
        var reading = w.roleView(actor).readings().stream().filter(r -> doc.equals(r.ref().docId())).findFirst().orElseThrow();
        w.advanceTo(reading.dueAt());
        assertTrue(w.roleView(actor).observations().stream().anyMatch(r -> doc.equals(r.ref().docId())));
    }

    private JsonNode feed(WorldRuntime w, String person) throws Exception {
        return w.autonomyRoleObservation(person).required("groups").required("feed");
    }

    @Test void twoReadersGetDifferentConfirmedTitlesButNoExtraTextAndReopenExactly() throws Exception {
        Path file = directory.resolve("news.journal");
        String ritaRead, edRead, newest, unfamiliar, newestRita;
        List<PublicationRun.FeedEntry> investor, nina;
        try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            ritaRead = post(w, "rita", "清晨记录");
            edRead = post(w, "ed", "午间记录");
            read(w, "investor", ritaRead);
            read(w, "nina", edRead);
            unfamiliar = post(w, "ed", "早间报价");
            post(w, "rita", "报价一");
            post(w, "rita", "报价二");
            post(w, "rita", "报价三");
            newestRita = post(w, "rita", "报价四");
            newest = post(w, "ed", "HYDRO 例行资料");
            long at = w.snapshot().timeMillis() + 1;
            w.schedulePublishDocument("private", "private-task", at, "sealed-ranking", "ed",
                    InformationModel.Audience.direct("ed"), "HYDRO 私人记录", "秘密正文", "私信", 1000, List.of());
            w.advanceTo(at);

            investor = w.personalNewsFeed("investor");
            nina = w.personalNewsFeed("nina");
            assertEquals(newestRita, investor.get(0).docId());
            assertEquals(newest, investor.get(3).docId());
            assertEquals("发现本人未读过的发布者", investor.get(3).rankingReason());
            assertEquals(newest, nina.get(0).docId());
            assertEquals(unfamiliar, nina.get(1).docId());
            assertEquals(edRead, nina.get(nina.size()-1).docId());
            assertEquals("本人已读这一版", nina.get(nina.size()-1).rankingReason());
            assertEquals(ritaRead, investor.get(investor.size()-1).docId());
            assertEquals("标题涉及本人任职机构", w.personalNewsFeed("hydro-director-1").get(0).rankingReason());
            assertEquals("按公开时间排列", w.personalNewsFeed("hydro-manager").get(0).rankingReason());
            assertTrue(w.roleView("nina").observations().stream().noneMatch(r -> newest.equals(r.ref().docId())));
            assertTrue(w.roleView("investor").catalog().stream().noneMatch(c -> c.docId().equals("sealed-ranking")));
            assertFalse(feed(w, "investor").toString().contains("正文"));
            assertFalse(feed(w, "nina").toString().contains("sealed-ranking"));
            assertTrue(w.groupsRun().publication().frames().isEmpty());
            var route = new DecisionModel.Route("media", "key-1", "deepseek-flash", 1, 60, 1_000_000, 1024, 2);
            w.beginDecisionSession("session-news");
            w.openDecisions("open-news", List.of(new DecisionRun.Request("investor", "news-I", "wake", route),
                    new DecisionRun.Request("nina", "news-N", "wake", route)));
            for (String id : List.of("news-I", "news-N")) {
                var dispatch = w.dispatchLiveDecision("render-" + id, id);
                var call = dispatch.call();
                assertNotNull(call, dispatch.toString());
                var frame = w.groupsRun().publication().frames().stream().filter(f -> id.equals(f.actionId())).findFirst().orElseThrow();
                var expected = id.equals("news-I") ? investor : nina;
                assertEquals(expected, frame.entries());
                JsonNode input = JSON.readTree(call.inputText()).at("/groups/feed");
                for (var item : expected) {
                    var row = input.get(item.rank()-1);
                    assertEquals(item.docId(), row.required("docId").asText());
                    assertEquals(item.version(), row.required("version").longValue());
                    assertEquals(item.rankingReason(), row.required("rankingReason").asText());
                    assertFalse(row.has("body"));
                }
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        try (var host = new WorldWebHost(() -> new WorldWebHost.Opened(
                ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file), "web-ten-company-autonomy-v11"))) {
            host.awaitOpening();
            for (var person : List.of("investor", "nina")) {
                var expected = person.equals("investor") ? investor : nina;
                var web = host.groupPage(person).feed();
                assertEquals(expected.stream().map(PublicationRun.FeedEntry::docId).toList(), web.stream().map(WorldWebHost.GroupFeedView::docId).toList());
                assertEquals(expected.stream().map(PublicationRun.FeedEntry::rankingReason).toList(), web.stream().map(WorldWebHost.GroupFeedView::rankingReason).toList());
            }
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void onlyOwnCompletedQueryAndPublishedCorrectionsAffectRank() throws Exception {
        Path file = directory.resolve("queries.journal");
        try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            String target = post(w, "rita", "物价 月报");
            String other = post(w, "ed", "另一则");
            var call = PharmaDecisionToolTest.open(w, "nina", "search-news");
            var reply = JSON.createObjectNode().put("tool", "role.library.search");
            reply.putObject("args").put("query", "月报 物价");
            reply.putObject("decision").put("objective", JSON.readTree(call.inputText())
                            .at("/engineering/deliberation/objectives/0/id").asText())
                    .put("basis", "核对本人公开目录").put("reviewAfterMillis", 60000);
            assertEquals("QUEUED", w.offerDecisionCallback(new DecisionModel.Reply(call.runId(), call.actionId(),
                    call.attemptId(), call.attemptNo(), call.recoveryGeneration(), reply.toString(),
                    DecisionModel.Usage.unknownCost(1, 50, 30), "fixture")));
            assertEquals(1, w.drainDecisionCallbacks());
            long due = DecisionModel.actionDueMillis(w.snapshot().decisions().orElseThrow().state(), call.actionId());
            w.advanceTo(due - 1);
            assertEquals(other, w.personalNewsFeed("nina").get(0).docId());
            w.advanceTo(due);
            var matched = w.personalNewsFeed("nina").get(0);
            assertEquals(target, matched.docId());
            assertEquals("标题匹配本人最近的检索", matched.rankingReason());
            assertEquals(other, w.personalNewsFeed("investor").get(0).docId());
            read(w, "nina", target);
            assertEquals(other, w.personalNewsFeed("nina").get(0).docId());
            var platform = w.groupsRun().platformConfig();
            long changed = w.snapshot().timeMillis() + platform.postMillis();
            assertEquals("SCHEDULED:fix", w.openRoleSession("rita").correctPlatformPost("fix", "fix", changed,
                    new WorldRoleSession.PlatformCorrectDraft(target, 1, "更正", "第二版正文", List.of())));
            w.advanceTo(changed + platform.deliveryMillis() - 1);
            assertEquals(1, w.personalNewsFeed("nina").stream().filter(e -> target.equals(e.docId())).findFirst().orElseThrow().version());
            w.advanceTo(changed + platform.deliveryMillis());
            var corrected = w.personalNewsFeed("nina").get(0);
            assertEquals(target, corrected.docId());
            assertEquals(2, corrected.version());
            assertEquals("本人读过旧版，已有新版", corrected.rankingReason());
            assertTrue(w.roleView("nina").observations().stream().noneMatch(r -> target.equals(r.ref().docId()) && r.ref().version() == 2));
        }
        try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            assertEquals(2, w.personalNewsFeed("nina").get(0).version());
        }
    }

    @Test void previousWorldKeepsChronologicalBytesWithoutRankingMetadata() throws Exception {
        Path file = directory.resolve("old.journal");
        String first, second;
        try (var w = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(file)) {
            first = post(w, "rita", "先发表");
            read(w, "investor", first);
            second = post(w, "ed", "后发表");
            var call = PharmaDecisionToolTest.open(w, "investor", "old");
            var rows = JSON.readTree(call.inputText()).at("/groups/feed");
            assertEquals(second, rows.get(0).required("docId").asText());
            assertEquals(first, rows.get(1).required("docId").asText());
            assertFalse(call.inputText().contains("rankingReason"));
            assertFalse(PublicationRun.toJson(w.groupsRun().publication()).contains("rankingReason"));
        }
        byte[] bytes = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(file)) {
            assertFalse(feed(w, "investor").toString().contains("rankingReason"));
            assertEquals(w.personalNewsFeed("investor"), w.personalNewsFeed("nina"));
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }
}
