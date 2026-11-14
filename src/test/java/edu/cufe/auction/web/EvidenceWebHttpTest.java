package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema16 证据档的完整 Web 链（真实 HTTP + Host + 进程内脚本提供方）。
 *
 * <p><b>口径</b>：这是流程接线验证，不是官方模型自主行为、也不是模型网络传输测试
 * （0 次真实 provider 调用）。所有 storyId / 版本 / 草稿与公开文档标识都取自
 * {@link DecisionRun.Call#inputText()}（{@code media[]}、{@code profile.catalog}、
 * {@code profile.readExperiences}），脚本里没有内部命名常量；只有选题文案、标签文本与
 * clientCommandId 是夹具自定的。</p>
 *
 * <p><b>异步失败</b>：脚本在 worker 线程跑，断言异常会被 WorldRuntime 的通用 catch 吞成
 * 本地失败码；因此这里显式捕获 Throwable 记录到 {@link ScriptedEvidence#errors()} 并在测试
 * 主线程断言为空，不修改生产的异常处理。</p>
 */
class EvidenceWebHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RECIPIENT = Pattern.compile("共享给\\s*[:：]\\s*([A-Za-z0-9_-]+)");

    @Test
    void fullEvidenceChainOverRealHttp() throws Exception {
        try (Harness harness = new Harness()) {
            Client admin = harness.admin();
            admin.saveRoutes();
            admin.advance(1_200L);   // 私证 + 工作说明由真实发布任务落地

            String[] script = {"rita", "rita", "rita", "rita", "ed", "ed", "ed",
                "rita", "rita", "ed", "ed", "ed",
                "sam", "sam", "sam", "sam", "bea", "bea", "bea",
                "rita", "rita", "rita", "investor", "investor", "investor"};
            for (String person : script) {
                admin.round(person);
                admin.advance(admin.timeMillis() + 16_000L);
            }
            JsonNode mediaDbg = admin.get("/api/admin/media");
            for (JsonNode report : mediaDbg.path("reports").path("rows")) {
                System.out.println("MEDIA_REPORT " + report.path("kind").asText()
                        + " accepted=" + report.path("accepted").asText()
                        + " reason=" + report.path("reason").asText());
            }
            assertTrue(harness.provider.errors().isEmpty(),
                    "脚本异步断言失败必须回传主线程：" + harness.provider.errors());
            StringBuilder brief = new StringBuilder();
            JsonNode decisions = admin.get("/api/admin/live").path("decisions");
            for (JsonNode decision : decisions) {
                assertTrue(java.util.Set.of("WAIT", "INTENT").contains(decision.path("outcome").asText()),
                        "全链不得用技术失败冒充主动等待：" + decision.path("reason"));
                brief.append(decision.path("personPartyId").asText()).append(':')
                        .append(decision.path("status").asText()).append('/')
                        .append(decision.path("outcome").asText()).append('/')
                        .append(decision.path("reason").asText()).append(" | ");
            }
            System.out.println("DECISIONS " + brief);

            JsonNode audit = admin.get("/api/admin/evidence");
            System.out.println("EVIDENCE roots=" + audit.path("roots").path("total")
                    + " aliases=" + audit.path("aliases").path("total")
                    + " shares=" + audit.path("shares").path("total"));
            assertTrue(audit.path("available").asBoolean(), "evidence available");
            assertTrue(audit.path("roots").path("total").asInt() >= 1, "real root");
            assertEquals(3, audit.path("aliases").path("total").asInt(),
                    "两条已发表别名 + 一条未发表重投");
            JsonNode publishedAliases = MAPPER.createArrayNode();
            JsonNode unpublished = null;
            for (JsonNode row : audit.path("aliases").path("rows")) {
                if (row.path("published").asBoolean()) {
                    ((com.fasterxml.jackson.databind.node.ArrayNode) publishedAliases).add(row);
                    assertFalse(row.path("publicDocId").asText("").isBlank(), "真实公开文档");
                    assertNotEquals("0", row.path("publicVersion").asText());
                } else {
                    unpublished = row;
                }
            }
            assertEquals(2, publishedAliases.size(), "两篇报道各一条公开别名");
            assertNotNull(unpublished, "未发表的重投必须出现在审计里");
            assertEquals("未发表标签", unpublished.path("publicLabel").asText());
            assertEquals("", unpublished.path("publicDocId").asText(),
                    "未发表不得凭空有公开文档");
            assertEquals("", unpublished.path("publicVersion").asText(),
                    "未发表不得给出公开版本");

            assertEquals(1, audit.path("shares").path("total").asInt(), "one real share");
            JsonNode share = audit.path("shares").path("rows").get(0);
            assertEquals("investor", share.path("recipientPartyId").asText());
            assertTrue(share.path("recipientRead").asBoolean(),
                    "收件人真实读完共享说明（按可回忆阅读）");

            // 他社记者真实读到公开稿并按公共目录投稿：第二条别名不存在（公共转载不继承匿名标签）
            assertTrue(harness.provider.sawPublicDocs >= 2, "他社必须从真实目录读到两篇公开稿");

            // 人物投影：只给本人可回忆的公开标签，且不含私根标识
            JsonNode switched = admin.post("/api/session/person", Map.of("personId", "sam"));
            assertEquals("sam", switched.path("personId").asText());
            assertEquals(403, admin.getStatus("/api/admin/evidence"),
                    "人物不得读管理员证据审计");
            JsonNode mine = admin.get("/api/me/evidence");
            String labels = mine.path("labels").path("rows").toString();
            System.out.println("PERSON_LABELS " + labels);
            assertEquals(2, mine.path("labels").path("total").asInt(),
                    "他社普通阅读者应看到自己实际读过的两篇公开版标签");
            assertFalse(labels.contains("labelledRootDocId"),
                    "投影不得包含私根字段：" + labels);
            assertFalse(labels.contains(harness.provider.privateDocId),
                    "投影不得泄露匿名私证标识：" + labels);
            assertEquals(0, mine.path("shares").path("total").asInt(),
                    "sam 不是共享收件人，不得看到别人的共享");

            Client anonymous = new Client(harness.baseUrl());
            assertNotEquals(200, anonymous.getStatus("/api/admin/evidence"),
                    "匿名必须被拒");
        }
    }

    /** 第二遍：同一真实链，收件人视角（单 bootstrap 只能切一次身份，故独立跑一遍）。 */
    @Test
    void recipientLearnsSourceAfterRealRead() throws Exception {
        try (Harness harness = new Harness()) {
            Client admin = harness.admin();
            runRounds(admin);
            assertTrue(harness.provider.errors().isEmpty(),
                    "脚本异步断言失败必须回传主线程：" + harness.provider.errors());
            JsonNode audit = admin.get("/api/admin/evidence");
            assertEquals(1, audit.path("shares").path("total").asInt(), "one real share");
            assertTrue(audit.path("shares").path("rows").get(0).path("recipientRead").asBoolean(),
                    "接收者已真实读完");

            JsonNode switched = admin.post("/api/session/person", Map.of("personId", "investor"));
            assertEquals("investor", switched.path("personId").asText());
            JsonNode investor = admin.get("/api/me/evidence");
            System.out.println("PERSON_SHARES " + investor.path("shares"));
            assertEquals(1, investor.path("shares").path("total").asInt(),
                    "收件人看到自己读过的共享");
            assertFalse(investor.path("shares").path("rows").get(0).path("body")
                    .asText("").isBlank(), "读后展示说明正文");
            JsonNode shared = investor.path("shares").path("rows").get(0);
            assertTrue(shared.path("body").asText().contains("身份来源：investor"),
                    "读后得知共享说明实际披露的身份");
            assertFalse(shared.has("sharedRootDocId"), "知道来源身份不等于读取私证编号");
            assertFalse(shared.has("sharedRootVersion"), "不额外披露未读私证版本");
            assertFalse(investor.toString().contains(harness.provider.privateDocId),
                    "整个本人证据响应不带原始私证编号");
            assertEquals(0, investor.path("labels").path("total").asInt(),
                    "未读公开稿时不显示公开标签");
        }
    }

    private static void runRounds(Client admin) throws Exception {
        admin.saveRoutes();
        admin.advance(1_200L);
        String[] script = {"rita", "rita", "rita", "rita", "ed", "ed", "ed",
            "rita", "rita", "ed", "ed", "ed",
            "sam", "sam", "sam", "sam", "bea", "bea", "bea",
            "rita", "rita", "rita", "investor", "investor", "investor"};
        for (String person : script) {
            admin.round(person);
            admin.advance(admin.timeMillis() + 16_000L);
        }
    }

    /** 独立 Host+Server+随机端口；close 释放全部资源。 */
    static final class Harness implements AutoCloseable {
        final Path dir;
        final SceneRoutes routes;
        final WorldWebHost host;
        final WorldWebServer server;
        final ScriptedEvidence provider = new ScriptedEvidence();
        final String bootstrap = "fixture-bootstrap-" + System.nanoTime();

        Harness() throws Exception {
            Path parent = Files.createDirectories(Path.of("target", "evidence-web-test-tmp"));
            dir = Files.createTempDirectory(parent, "evidence-web-");
            Path keys = dir.resolve("keys.txt");
            Files.writeString(keys, "sk-aaaaaaaaaaaaaaaa1\nsk-aaaaaaaaaaaaaaaa2\n"
                    + "sk-aaaaaaaaaaaaaaaa3\nsk-aaaaaaaaaaaaaaaa4\nsk-aaaaaaaaaaaaaaaa5\n",
                    StandardCharsets.UTF_8);
            KeySlots slots = KeySlots.load(keys);
            routes = SceneRoutes.open(dir);
            host = new WorldWebHost(() -> {
                WorldWebHost.Opened opened =
                        WorldWebScenario.openEvidence(dir.resolve("world-evidence.journal"));
                // 夹具自己的真实发布任务：给记者一份工作说明，交代可以共享给谁；
                // 与私证本身分开标注，不替代任何真实阅读。
                opened.runtime().schedulePublishDocument("web-note-publish", "web-note-task",
                        1_200L, "web:work-note", "rita",
                        InformationModel.Audience.direct("rita"),
                        "工作说明（实验夹具）",
                        "本说明由实验夹具发布：允许把匿名私证共享给:investor。",
                        "实验模拟，不是现实工作流程", 500L, List.of());
                return opened;
            }, routes, provider);
            try {
                host.adminSnapshot();
            } catch (Exception failure) {
                failure.printStackTrace();
                throw failure;
            }
            server = new WorldWebServer(host, slots, routes, 0, bootstrap, null, text -> { });
            server.start();
        }

        String baseUrl() {
            return server.baseUrl();
        }

        Client admin() throws Exception {
            Client client = new Client(baseUrl());
            JsonNode login = client.post("/api/session", Map.of("bootstrap", bootstrap));
            assertTrue(login.path("error").isMissingNode(), "admin login must succeed");
            return client;
        }

        @Override
        public void close() throws Exception {
            server.close();
            host.close();
            routes.close();
        }
    }

    /** 进程内脚本：只读真实输入；标识缺就抛，异常记录后由主线程断言。 */
    static final class ScriptedEvidence implements WorldWebHost.DecisionProvider {
        private final Map<String, Integer> round = new ConcurrentHashMap<>();
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();
        private volatile String privateDocId = "";
        private volatile long privateDocVersion = -1L;
        private volatile String recipient = "";
        private volatile int sawPublicDocs = 0;

        List<Throwable> errors() {
            return errors;
        }

        @Override
        public LiveCompletionInbox.Completion evaluate(DecisionRun.Call call, long timeoutMillis) {
            try {
                return build(call);
            } catch (Throwable failure) {
                errors.add(failure);
                System.out.println("PROVIDER_ERROR " + failure);
                failure.printStackTrace();
                DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                        call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                        "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 10, 10),
                        "SCRIPTED_LOOPBACK");
                return LiveCompletionInbox.Completion.failure("SCRIPT_ASSERTION", reply);
            }
        }

        private LiveCompletionInbox.Completion build(DecisionRun.Call call) throws Exception {
            JsonNode input = MAPPER.readTree(call.inputText());
            String person = input.path("person").asText("");
            if (person.isBlank()) {
                throw new AssertionError("input.person required");
            }
            int step = round.merge(person, 1, Integer::sum);
            JsonNode media = input.path("media");
            JsonNode catalog = input.path("profile").path("catalog");
            String tool;
            String args;
            switch (person) {
                case "rita" -> {
                    if (step == 1) {
                        JsonNode doc = byTitle(catalog, "手记");
                        privateDocId = doc.path("docId").asText();
                        privateDocVersion = doc.path("version").asLong();
                        tool = "role.reading.start";
                        args = readArgs(doc);
                    } else if (step == 2) {
                        JsonNode note = byTitle(catalog, "工作说明");
                        tool = "role.reading.start";
                        args = readArgs(note);
                    } else if (step == 3) {
                        tool = "media.story.propose";
                        args = "{\"storyId\":\"story-1\",\"template\":\"WIRE\","
                                + "\"topic\":\"定向材料核查\"}";
                    } else if (step == 4) {
                        JsonNode story = ownStory(media, "DRAFT");
                        tool = "media.story.submit";
                        args = "{\"storyId\":\"" + story.path("storyId").asText()
                                + "\",\"expectedRevision\":" + story.path("revisionNo").asInt()
                                + ",\"title\":\"核查报道\",\"body\":\"基于定向材料的报道正文。\""
                                + ",\"publicSourceDescription\":\"模拟定向材料\""
                                + ",\"evidence\":[{\"docId\":\"" + privateDocId
                                + "\",\"version\":" + privateDocVersion + ",\"index\":"
                                + indexOf(input, privateDocId, privateDocVersion) + "}]"
                                + ",\"aliases\":[{\"rootDocId\":\"" + privateDocId
                                + "\",\"rootVersion\":" + privateDocVersion
                                + ",\"label\":\"受访者A\"}]}";
                    } else if (step == 5) {
                        tool = "media.story.propose";
                        args = "{\"storyId\":\"story-2\",\"template\":\"WIRE\","
                                + "\"topic\":\"第二篇报道\"}";
                    } else if (step == 6) {
                        JsonNode story = ownStory(media, "DRAFT");
                        JsonNode note = byTitle(catalog, "工作说明");
                        tool = "media.story.submit";
                        args = "{\"storyId\":\"" + story.path("storyId").asText()
                                + "\",\"expectedRevision\":" + story.path("revisionNo").asInt()
                                + ",\"title\":\"第二篇报道\",\"body\":\"另一份公开报道。\""
                                + ",\"publicSourceDescription\":\"工作说明\""
                                + ",\"evidence\":[{\"docId\":\"" + note.path("docId").asText()
                                + "\",\"version\":" + note.path("version").asLong()
                                + ",\"index\":" + indexOf(input, note.path("docId").asText(),
                                        note.path("version").asLong()) + "}]"
                                + ",\"aliases\":[{\"rootDocId\":\"" + note.path("docId").asText()
                                + "\",\"rootVersion\":" + note.path("version").asLong()
                                + ",\"label\":\"工作说明\"}]}";
                    } else if (step == 7) {
                        tool = "media.evidence.share";
                        if (recipient.isBlank()) {
                            recipient = recipientOf(input);
                        }
                        if (recipient.isBlank() || privateDocId.isBlank()) {
                            throw new AssertionError("共享缺少真实根或接收者");
                        }
                        args = "{\"rootDocId\":\"" + privateDocId
                                + "\",\"rootVersion\":" + privateDocVersion
                                + ",\"recipientPartyId\":\"" + recipient + "\""
                                + ",\"note\":\"供你核对的来源说明\"}";
                    } else if (step == 8) {
                        tool = "media.story.propose";
                        args = "{\"storyId\":\"story-4\",\"template\":\"WIRE\","
                                + "\"topic\":\"未发表的改稿\"}";
                    } else {
                        // 未发表的重投：带别名提交但不发布，公开标签不得冒充已公开
                        JsonNode story = ownStory(media, "DRAFT");
                        tool = "media.story.submit";
                        args = "{\"storyId\":\"" + story.path("storyId").asText()
                                + "\",\"expectedRevision\":" + story.path("revisionNo").asInt()
                                + ",\"title\":\"未发表的重投\",\"body\":\"尚未发表的版本。\""
                                + ",\"publicSourceDescription\":\"模拟定向材料\""
                                + ",\"evidence\":[{\"docId\":\"" + privateDocId
                                + "\",\"version\":" + privateDocVersion + ",\"index\":"
                                + indexOf(input, privateDocId, privateDocVersion) + "}]"
                                + ",\"aliases\":[{\"rootDocId\":\"" + privateDocId
                                + "\",\"rootVersion\":" + privateDocVersion
                                + ",\"label\":\"未发表标签\"}]}";
                    }
                }
                case "ed" -> {
                    boolean publishing = step == 3 || step == 6;
                    boolean reading = step == 1 || step == 4;
                    JsonNode story = publishing ? publishable(media) : reviewable(media);
                    int revision = story.path("revisionNo").asInt(1);
                    if (publishing) {
                        tool = "media.story.publish";
                        args = "{\"storyId\":\"" + story.path("storyId").asText()
                                + "\",\"expectedRevision\":" + revision + "}";
                    } else if (reading) {
                        tool = "role.reading.start";
                        args = "{\"docId\":\"" + story.path("draftDocId").asText()
                                + "\",\"version\":" + story.path("draftVersion").asLong() + "}";
                    } else {
                        tool = "media.story.review";
                        args = "{\"storyId\":\"" + story.path("storyId").asText()
                                + "\",\"expectedRevision\":" + revision
                                + ",\"approve\":true,\"note\":\"可发布\"}";
                    }
                }
                case "sam" -> {
                    if (step == 1 || step == 2) {
                        JsonNode doc = byTitle(catalog, step == 1 ? "核查报道" : "第二篇报道");
                        sawPublicDocs++;
                        tool = "role.reading.start";
                        args = readArgs(doc);
                    } else if (step == 3) {
                        tool = "media.story.propose";
                        args = "{\"storyId\":\"story-3\",\"template\":\"INVESTIGATIVE\","
                                + "\"topic\":\"公共转载\"}";
                    } else {
                        JsonNode first = byTitle(catalog, "核查报道");
                        JsonNode second = byTitle(catalog, "第二篇报道");
                        JsonNode story = ownStory(media, "DRAFT");
                        tool = "media.story.submit";
                        args = "{\"storyId\":\"" + story.path("storyId").asText()
                                + "\",\"expectedRevision\":" + story.path("revisionNo").asInt()
                                + ",\"title\":\"转载与延伸\",\"body\":\"引用两条已公开报道。\""
                                + ",\"publicSourceDescription\":\"已公开报道\""
                                + ",\"evidence\":[{\"docId\":\"" + first.path("docId").asText()
                                + "\",\"version\":" + first.path("version").asLong()
                                + ",\"index\":" + indexOf(input, first.path("docId").asText(),
                                        first.path("version").asLong())
                                + "},{\"docId\":\"" + second.path("docId").asText()
                                + "\",\"version\":" + second.path("version").asLong()
                                + ",\"index\":" + indexOf(input, second.path("docId").asText(),
                                        second.path("version").asLong()) + "}]}";
                    }
                }
                case "bea" -> {
                    JsonNode story = step == 3 ? publishable(media) : reviewable(media);
                    int revision = story.path("revisionNo").asInt(1);
                    if (step == 3) {
                        tool = "media.story.publish";
                        args = "{\"storyId\":\"" + story.path("storyId").asText()
                                + "\",\"expectedRevision\":" + revision + "}";
                    } else if (step == 1) {
                        tool = "role.reading.start";
                        args = "{\"docId\":\"" + story.path("draftDocId").asText()
                                + "\",\"version\":" + story.path("draftVersion").asLong() + "}";
                    } else {
                        tool = "media.story.review";
                        args = "{\"storyId\":\"" + story.path("storyId").asText()
                                + "\",\"expectedRevision\":" + revision
                                + ",\"approve\":true,\"note\":\"可发布\"}";
                    }
                }
                default -> {
                    // 收件人：只读真实共享说明（identity-share 文档由真实 share 产生）
                    JsonNode doc = shareDoc(catalog, step);
                    if (doc == null) {
                        tool = DecisionModel.TOOL_WAIT;
                        args = null;
                    } else {
                        tool = "role.reading.start";
                        args = readArgs(doc);
                    }
                }
            }
            String body = "{\"tool\":\"" + tool + "\""
                    + (args == null ? "" : ",\"args\":" + args) + "}";
            System.out.println("SCRIPT " + person + "#" + step + " -> " + tool);
            DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                    call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                    DecisionModel.Usage.unknownCost(1, 120, 40), "SCRIPTED_LOOPBACK");
            return LiveCompletionInbox.Completion.business(reply);
        }

        /** 本人在该文档上的真实阅读序号（来自输入里的 lastReadRef，不猜顺序）。 */
        private static long indexOf(JsonNode input, String docId, long version) {
            for (JsonNode experience : input.path("profile").path("readExperiences")) {
                JsonNode origin = experience.path("lastReadRef");
                if (docId.equals(origin.path("docId").asText(""))
                        && version == origin.path("version").asLong(-1)) {
                    long index = origin.path("index").asLong(-1);
                    if (index >= 1) {
                        return index;
                    }
                }
            }
            throw new AssertionError("输入里没有该资料的真实阅读序号：" + docId + " v" + version);
        }

        /** 从真实已读说明正文解析可共享对象（夹具说明里的「共享给:」）。 */
        private static String recipientOf(JsonNode input) {
            for (JsonNode experience : input.path("profile").path("readExperiences")) {
                Matcher matcher = RECIPIENT.matcher(experience.path("body").asText(""));
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            throw new AssertionError("已读说明里没有可共享对象");
        }

        private static JsonNode byTitle(JsonNode catalog, String needle) {
            for (JsonNode doc : catalog) {
                if (doc.path("title").asText("").contains(needle)) {
                    return doc;
                }
            }
            throw new AssertionError("input.profile.catalog 缺少标题含「" + needle + "」的资料，实际="
                    + catalog);
        }

        private static JsonNode ownStory(JsonNode media, String status) {
            for (JsonNode story : media) {
                if (story.path("own").asBoolean(false)
                        && status.equals(story.path("status").asText())) {
                    return story;
                }
            }
            throw new AssertionError("input.media 缺少本人 " + status + " 稿件，实际=" + media);
        }

        private static JsonNode reviewable(JsonNode media) {
            for (JsonNode story : media) {
                if (story.path("canReview").asBoolean(false)) {
                    return story;
                }
            }
            System.out.println("REVIEW_MISS " + media);
            throw new AssertionError("input.media 缺少待审稿件，实际=" + media);
        }

        /** 已批准、等待本人发布的稿件（来自真实 input.media）。 */
        private static JsonNode publishable(JsonNode media) {
            for (JsonNode story : media) {
                if (story.path("canPublish").asBoolean(false)) {
                    return story;
                }
            }
            throw new AssertionError("input.media 缺少可发布稿件，实际=" + media);
        }

        /** 目录里的共享说明（真实 share 生成的 identity-share 文档）。 */
        private static JsonNode shareDoc(JsonNode catalog, int step) {
            if (step > 1) {
                return null;
            }
            for (JsonNode doc : catalog) {
                if (doc.path("docId").asText("").startsWith("identity-share:")) {
                    return doc;
                }
            }
            throw new AssertionError("收件人目录缺少真实共享说明，实际=" + catalog);
        }

        /** 目录里第 n 份（1 起）可读资料；越界返回 null。 */
        private static JsonNode catalogEntry(JsonNode catalog, int n) {
            int seen = 0;
            for (JsonNode doc : catalog) {
                if (doc.path("version").asLong(0) < 1) {
                    continue;
                }
                seen += 1;
                if (seen == n) {
                    return doc;
                }
            }
            return null;
        }

        private static JsonNode lastDoc(JsonNode catalog) {
            JsonNode last = null;
            for (JsonNode doc : catalog) {
                if (doc.path("version").asLong(0) >= 1) {
                    last = doc;
                }
            }
            if (last == null) {
                throw new AssertionError("input.profile.catalog 为空");
            }
            return last;
        }

        private static String readArgs(JsonNode doc) {
            return "{\"docId\":\"" + doc.path("docId").asText()
                    + "\",\"version\":" + doc.path("version").asLong() + "}";
        }
    }

    /** 极简 HTTP 客户端：真实 loopback + world_session cookie。 */
    static final class Client {
        private final String base;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        private String cookie = "";

        Client(String base) {
            this.base = base;
        }

        void saveRoutes() throws Exception {
            JsonNode view = get("/api/admin/routes");
            System.out.println("ROUTES_GET " + view);
            long revision = view.path("revision").asLong();
            List<Map<String, Object>> routes = new ArrayList<>();
            for (JsonNode route : view.path("routes")) {
                String scene = route.path("scene").asText();
                boolean media = "media".equals(scene);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("scene", scene);
                row.put("slotId", route.path("slotId").asText());
                row.put("model", route.path("model").asText());
                row.put("enabled", media);
                row.put("maxCalls", media ? 80 : 1);
                row.put("maxTotalTokens", 1_000_000);
                row.put("maxOutputTokens", 8192);
                row.put("maxConcurrency", 1);
                routes.add(row);
            }
            JsonNode saved = post("/api/admin/routes",
                    Map.of("expectedRevision", Long.toString(revision), "routes", routes));
            assertTrue(saved.path("error").isMissingNode(), "routes save must succeed: " + saved);
        }

        void round(String person) throws Exception {
            long position = get("/api/admin/view").path("position").asLong();
            long revision = get("/api/admin/routes").path("revision").asLong();
            JsonNode result = post("/api/admin/live/start",
                    Map.of("expectedPosition", position, "expectedRevision",
                            Long.toString(revision), "persons", List.of(person)));
            assertTrue(result.path("error").isMissingNode(),
                    "live start for " + person + " failed: " + result);
            for (int i = 0; i < 200; i += 1) {
                Thread.sleep(20L);
                if (get("/api/admin/live").path("inFlight").asInt(0) == 0) {
                    break;
                }
            }
            for (int settle = 0; settle < 10; settle += 1) {
                JsonNode status = get("/api/admin/live");
                if (status.path("completionSlots").asInt(0) == 0
                        && status.path("physicalInFlight").asInt(0) == 0) {
                    break;
                }
                Thread.sleep(40L);
                advance(timeMillis() + 2_000L);
            }
            for (JsonNode decision : get("/api/admin/live").path("decisions")) {
                if ("TECHNICAL_FAILED".equals(decision.path("outcome").asText())) {
                    System.out.println("TECH_FAIL " + decision.path("personPartyId").asText()
                            + " " + decision.path("reason").asText());
                }
            }
        }

        void advance(long targetMillis) throws Exception {
            long position = get("/api/admin/view").path("position").asLong();
            JsonNode result = post("/api/admin/advance",
                    Map.of("expectedPosition", position, "targetMillis", targetMillis));
            assertTrue(result.path("error").isMissingNode(),
                    "advance to " + targetMillis + " must not fail: " + result);
        }

        long timeMillis() throws Exception {
            return Long.parseLong(get("/api/admin/view").path("timeMillis").asText());
        }

        JsonNode post(String path, Object body) throws Exception {
            return send("POST", path, body);
        }

        JsonNode get(String path) throws Exception {
            return send("GET", path, null);
        }

        int getStatus(String path) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .GET().timeout(Duration.ofSeconds(10));
            if (!cookie.isEmpty()) {
                builder.header("Cookie", cookie);
            }
            return client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode();
        }

        private JsonNode send(String method, String path, Object body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20));
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json");
                builder.header("X-World-Request", "1");
                builder.header("Origin", base);
                builder.POST(HttpRequest.BodyPublishers.ofString(
                        body == null ? "{}" : MAPPER.writeValueAsString(body),
                        StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            if (!cookie.isEmpty()) {
                builder.header("Cookie", cookie);
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String setCookie = response.headers().firstValue("Set-Cookie").orElse("");
            if (setCookie.startsWith("world_session=") && setCookie.length() > 20) {
                cookie = setCookie.split(";")[0];
            }
            if (response.statusCode() >= 400) {
                return MAPPER.readTree("{\"error\":\"" + response.statusCode() + "\","
                        + "\"body\":" + MAPPER.writeValueAsString(response.body()) + "}");
            }
            String text = response.body();
            return text == null || text.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(text);
        }
    }
}
