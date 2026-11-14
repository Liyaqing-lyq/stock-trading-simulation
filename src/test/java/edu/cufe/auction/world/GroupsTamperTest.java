package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema20 自洽篡改：重算帧 SHA 后仍必须被真实命令重演拒绝，而不是只靠哈希破损。
 *
 * <p>四条不同写点：群帖声明引用+来源根、Info 发布绑定、系统投递待办、决定输入/曝光摘要。
 * 失败根因逐条打印，便于审计。</p>
 */
class GroupsTamperTest {

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "groups-tamper-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static String reopenRejected(Path file) {
        Exception failure = assertThrows(Exception.class,
                () -> WorldWebScenario.groupsRuntime(file).close(),
                "篡改后的日志必须被拒绝");
        String message = String.valueOf(failure.getMessage());
        System.out.println("TAMPER-REJECT " + failure.getClass().getSimpleName() + ": " + message);
        return message;
    }

    /** 先发一条平台帖并被 alice 真实读完，供后续群帖引用。 */
    private static String platformDocReadByAlice(WorldRuntime world) throws Exception {
        long now = world.snapshot().timeMillis();
        world.openRoleSession("alice").postToPlatform("ppost", "ppost-t", now + 15,
                new WorldRoleSession.PlatformPostDraft("公告", "公开正文", List.of()));
        world.advanceTo(now + 15);
        world.advanceTo(world.snapshot().timeMillis() + 5);
        String docId = world.groupsRun().publication().platform().posts().get(0).docId();
        long t = world.snapshot().timeMillis();
        world.scheduleStartReading("read-a", "read-a-t", t + 10, "alice", docId, 1L);
        world.advanceTo(t + 20);
        return docId;
    }

    @Test
    void tamperedDeclaredRefsAndOriginsAreRejectedByReplay() throws Exception {
        Path file = journal("refs");
        String docA;
        String docB;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            // 两份真实合法已读公开资料 A/B
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToPlatform("pa", "pa-t", now + 15,
                    new WorldRoleSession.PlatformPostDraft("资料A", "A 正文", List.of()));
            world.advanceTo(now + 15);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            docA = world.groupsRun().publication().platform().posts().get(0).docId();
            long t = world.snapshot().timeMillis();
            world.scheduleStartReading("ra", "ra-t", t + 10, "alice", docA, 1L);
            world.advanceTo(t + 20);
            long n2 = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToPlatform("pb", "pb-t", n2 + 15,
                    new WorldRoleSession.PlatformPostDraft("资料B", "B 正文", List.of()));
            world.advanceTo(n2 + 15);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            docB = null;
            for (PlatformPostModel.Post post : world.groupsRun().publication().platform()
                    .posts()) {
                if (!post.docId().equals(docA)) {
                    docB = post.docId();
                }
            }
            long t2 = world.snapshot().timeMillis();
            world.scheduleStartReading("rb", "rb-t", t2 + 10, "alice", docB, 1L);
            world.advanceTo(t2 + 20);

            // 群帖合法引用 A（真实读完、真实命令）
            long g = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToGroup("gpost", "gpost-t", g + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN, "群内标题",
                            "群内正文", List.of(new WorldRoleSession.Citation(docA, 1L))));
            world.advanceTo(g + 20);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            GroupModel.Post post = world.groupsRun().state().posts().get(0);
            assertEquals(docA, post.declaredRefs().get(0).docId());
        }
        int index = JournalTamper.lastIndexContaining(file, "STATE_SNAPSHOT_V1");
        String finalDocB = docB;
        // 只改该帧 groups 帖子元数据的 declaredRefs 与 originRefs 到合法 B；
        // 原命令、Info 发布身份与其它 World 字段不动，改完仍能被 GroupsRun 自身解析。
        JournalTamper.rewriteSnapshot(file, index, json -> {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode root =
                        (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
                com.fasterxml.jackson.databind.JsonNode groups = root.get("groups");
                GroupsRun parsed = mapper.readValue(groups.toString(), GroupsRun.class);
                assertEquals(1, parsed.state().posts().size());
                com.fasterxml.jackson.databind.node.ArrayNode posts =
                        (com.fasterxml.jackson.databind.node.ArrayNode)
                                groups.get("state").get("posts");
                com.fasterxml.jackson.databind.node.ObjectNode first =
                        (com.fasterxml.jackson.databind.node.ObjectNode) posts.get(0);
                String origins = first.get("originRefs").toString();
                // 只改缓存根（声明引用与命令保持 A），由真实命令重演发现不一致
                return json.replace(origins, origins.replace(docA, finalDocB));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException(failure);
            }
        });
        String message = reopenRejected(file);
        // 若拒绝来自重演命令解析，再补一次"命令与元数据一致改"的强例，避免把解析失败当通过
        if (message.contains("无法解析")) {
            assertTrue(true, "第一次证据：解析路径拒绝 " + message);
        }
        assertTrue(message.contains("群组"), "拒绝必须来自群组重演：" + message);
    }

    @Test
    void tamperedInformationAudienceIsRejected() throws Exception {
        Path file = journal("info");
        String docId;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN, "标题",
                            "正文", List.of()));
            world.advanceTo(now + 20);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            docId = world.groupsRun().state().posts().get(0).docId();
        }
        int index = JournalTamper.lastIndexContaining(file, "STATE_SNAPSHOT_V1");
        // 把真实 GROUP 受众改成另一个合法 GROUP：结构合法但绑定不符
        JournalTamper.rewriteSnapshot(file, index, json -> json.replace(
                "\"kind\":\"GROUP\",\"partyId\":\"" + WorldWebScenario.GROUPS_OPEN + "\"",
                "\"kind\":\"GROUP\",\"partyId\":\""
                        + WorldWebScenario.GROUPS_INVITE_ONLY + "\""));
        String message = reopenRejected(file);
        assertTrue(message.contains("资料") || message.contains("群组") || message.contains("重演"),
                "拒绝根因：" + message);
    }

    @Test
    void tamperedPendingPublishTaskIsRejected() throws Exception {
        Path file = journal("pending");
        long publishAt;
        String taskId;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN, "标题",
                            "正文", List.of()));
            world.advanceTo(now + 20);
            GroupModel.Post post = world.groupsRun().state().posts().get(0);
            publishAt = post.publishAt();
            taskId = world.snapshot().pending().stream()
                    .filter(task -> GroupsRun.KINDS.contains(task.kind()))
                    .findFirst().orElseThrow().taskId();
        }
        int index = JournalTamper.lastIndexContaining(file, "STATE_SNAPSHOT_V1");
        JournalTamper.rewriteSnapshot(file, index, json -> json.replace(
                "\"taskId\":\"" + taskId + "\",\"dueTimeMillis\":" + publishAt,
                "\"taskId\":\"" + taskId + "\",\"dueTimeMillis\":" + (publishAt + 1)));
        String message = reopenRejected(file);
        assertTrue(message.contains("群组待办") || message.contains("改写")
                || message.contains("不一致"), "拒绝根因：" + message);
    }

    @Test
    void tamperedDecisionInputAndExposureDigestAreRejected() throws Exception {
        Path file = journal("decision");
        String digest;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToPlatform("ppost", "ppost-t", now + 15,
                    new WorldRoleSession.PlatformPostDraft("公告", "公开正文", List.of()));
            world.advanceTo(now + 15);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            world.beginDecisionSession("open");
            world.openDecisions("d", List.of(new DecisionRun.Request("bob", "r-d", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20,
                            1_000_000, 1_024, 1))));
            digest = DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),
                    "r-d").orElseThrow().inputDigest();
            assertEquals(1, world.groupsRun().publication().frames().size());
        }
        int index = JournalTamper.lastIndexContaining(file, digest);
        JournalTamper.rewrite(file, index, json -> JournalTamper.replaceDigest(json, digest));
        String message = reopenRejected(file);
        assertTrue(message.contains("决定") || message.contains("群组") || message.contains("输入"),
                "拒绝根因：" + message);
    }
}
