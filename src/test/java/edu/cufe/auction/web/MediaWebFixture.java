package edu.cufe.auction.web;

import edu.cufe.auction.world.*;
import java.nio.file.*;
import java.util.*;

/** 媒体Web测试的真实采编/阅读夹具；所有资料明确为模拟，未连接供应商。 */
final class MediaWebFixture {
    static long now(WorldRuntime world) { return world.snapshot().timeMillis(); }
    static int serial;
    static String id() { return "fixture-" + (++serial); }
    static void read(WorldRuntime world, String person, String document, long version, long duration) throws Exception {
        long start = now(world) + 1;
        world.openRoleSession(person).startReading(id(), id(), start, document, version);
        world.advanceTo(start + duration);
    }
    static List<WorldRoleSession.MediaCitation> references(WorldRuntime world) {
        return world.roleView("sam").observations().stream()
                .filter(item -> item.ref().docId().startsWith("fixture-source-"))
                .map(item -> new WorldRoleSession.MediaCitation(item.ref().docId(),
                        item.ref().version(), item.ref().index())).toList();
    }
    static MediaModel.Story story(WorldRuntime world) {
        return world.snapshot().media().orElseThrow().stateOf("investigative").stories().get(0);
    }
    static WorldWebHost.Opened prepare(Path journal) throws Exception {
        var opened = WorldWebScenario.openMedia(journal);
        var world = opened.runtime();
        for (int i = 1; i <= 2; i++) {
            long due = now(world) + 1;
            String document = "fixture-source-" + i;
            world.schedulePublishDocument(id(), id(), due, document, i == 1 ? "alice" : "bob",
                    InformationModel.Audience.publicAudience(), "模拟公开资料 " + i,
                    "用于功能验证的公开资料，来自独立登记发布者 " + i,
                    "内部模拟夹具，非真实市场消息", 10L, List.of(document));
            world.advanceTo(due);
            read(world, "sam", document, 1, 10L);
        }
        var refs = references(world);
        if (refs.size() != 2) throw new AssertionError("two actual source readings required");
        long due = now(world) + 1000;
        world.openRoleSession("sam").proposeStory(id(), id(), due,
                new WorldRoleSession.MediaProposeDraft("browser-story", "INVESTIGATIVE", "订单变化核验（模拟）"));
        world.advanceTo(due);
        String storyId = story(world).storyId();
        due = now(world) + 10000;
        world.openRoleSession("sam").submitStory(id(), id(), due,
                new WorldRoleSession.MediaSubmitDraft(storyId, 0, "两份资料核验（模拟）",
                        "第一版正文；<img src=x onerror=alert(1)> 仅用于验证文字显示。",
                        "两名独立发布者的公开资料", refs));
        world.advanceTo(due);
        String draftId = story(world).draftDocId();
        read(world, "bea", draftId, 1, 500L);
        due = now(world) + 3000;
        world.openRoleSession("bea").reviewStory(id(), id(), due,
                new WorldRoleSession.MediaReviewDraft(storyId, 1, true, ""));
        world.advanceTo(due);
        due = now(world) + 2000;
        world.openRoleSession("bea").publishStory(id(), id(), due,
                new WorldRoleSession.MediaStoryDraft(storyId, 1));
        world.advanceTo(due);
        read(world, "investor", story(world).publicDocId(), 1, 250L);
        due = now(world) + 1000;
        world.openRoleSession("sam").startCorrection(id(), id(), due,
                new WorldRoleSession.MediaStoryDraft(storyId, 1));
        world.advanceTo(due);
        due = now(world) + 10000;
        world.openRoleSession("sam").submitStory(id(), id(), due,
                new WorldRoleSession.MediaSubmitDraft(storyId, 1, "更正后的核验（模拟）",
                        "第二版正文补充口径；原读者没有自动读到新版。", "补充说明后再次独立审阅", refs));
        world.advanceTo(due);
        read(world, "bea", draftId, 2, 500L);
        due = now(world) + 3000;
        world.openRoleSession("bea").reviewStory(id(), id(), due,
                new WorldRoleSession.MediaReviewDraft(storyId, 2, true, ""));
        world.advanceTo(due);
        due = now(world) + 2000;
        world.openRoleSession("bea").publishStory(id(), id(), due,
                new WorldRoleSession.MediaStoryDraft(storyId, 2));
        world.advanceTo(due);
        if (story(world).publicVersion() != 2) throw new AssertionError("correction not published");
        return opened;
    }
}
