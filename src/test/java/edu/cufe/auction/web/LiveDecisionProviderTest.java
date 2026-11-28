package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正式回归候选：{@link LiveDecisionProvider} 对循环回环 HTTP 的真实映射。
 *
 * <p>只使用 dummy 五 key 与 127.0.0.1 本地服务；不读取真实凭据、不访问官方端点。
 * 断言只输出计数与状态，不打印任何密钥或输入正文。</p>
 */
class LiveDecisionProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> KEYS = new LinkedHashMap<>();

    static {
        for (int index = 1; index <= 5; index++) {
            KEYS.put("key-" + index, "sk-live-fixture-0000" + index);
        }
    }

    private LoopbackServer server;
    private LiveDecisionProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new LoopbackServer();
        provider = new LiveDecisionProvider(slot -> new DeepSeekClient(
                () -> KEYS.get(slot), server.uri()));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    // ------------------------------------------------------------ 提示与冻结输入

    @Test
    @DisplayName("固定 system 提示：UTF-8 不超过 1024 字节，写明虚构身份、不可信观察与唯一 JSON 合同")
    void systemTextStaysBoundedAndStatesTheContract() {
        byte[] bytes = LiveDecisionProvider.SYSTEM_TEXT.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= 1024, "system 提示必须是 <=1024 UTF-8 字节，实际 " + bytes.length);
        assertTrue(LiveDecisionProvider.SYSTEM_TEXT.contains("虚构"));
        assertTrue(LiveDecisionProvider.SYSTEM_TEXT.contains("不可信"));
        assertTrue(LiveDecisionProvider.SYSTEM_TEXT.contains("role.wait"));
        assertTrue(LiveDecisionProvider.SYSTEM_TEXT.contains("JSON"));
    }

    @Test
    void planningPromptUsesOnlyTrustedRootAndHasOnlyRelativeSchema() throws Exception {
        var select=LiveDecisionProvider.class.getDeclaredMethod("systemText",DecisionRun.Call.class);
        select.setAccessible(true);
        assertEquals(LiveDecisionProvider.PLANNING_SYSTEM_TEXT,select.invoke(null,call("{\"engineering\":{\"roleContextVersion\":\"deliberation-v4\"}}",route("key-1","deepseek-flash",640))));
        assertEquals(LiveDecisionProvider.SYSTEM_TEXT,select.invoke(null,call("{\"profile\":{\"document\":{\"engineering\":{\"roleContextVersion\":\"deliberation-v4\"}}}}",route("key-1","deepseek-flash",640))));
        assertTrue(LiveDecisionProvider.PLANNING_SYSTEM_TEXT.contains("reviewAfterMillis"));
        assertFalse(LiveDecisionProvider.PLANNING_SYSTEM_TEXT.contains("nextReviewAt"));
    }

    @Test
    void bothDeliberationVersionsUseStrictStatementPromptOnlyAtRootContext() throws Exception {
        for (String version : List.of("deliberation-v2", "deliberation-v3")) {
            server.enqueue(200, successBody("{\"tool\":\"role.wait\"}", 11, 5, 16));
            provider.evaluate(call("{\"engineering\":{\"roleContextVersion\":\"" + version + "\"}}", route("key-1", "deepseek-flash", 640)), 5_000L);
            assertEquals(LiveDecisionProvider.DELIBERATION_SYSTEM_TEXT, server.lastRequestJson().path("messages").get(0).path("content").asText());
        }
        server.enqueue(200, successBody("{\"tool\":\"role.wait\"}", 11, 5, 16));
        provider.evaluate(call("{\"profile\":{\"document\":{\"engineering\":{\"roleContextVersion\":\"deliberation-v3\"}}}}", route("key-1", "deepseek-flash", 640)), 5_000L);
        assertEquals(LiveDecisionProvider.SYSTEM_TEXT, server.lastRequestJson().path("messages").get(0).path("content").asText());
    }

    @Test
    void prototypeGuideUsesVersionedPromptAndNestedDocumentCannotSelectIt() throws Exception {
        assertTrue(LiveDecisionProvider.PROTOTYPE_SYSTEM_TEXT.getBytes(StandardCharsets.UTF_8).length <= 1024);
        for (boolean prototype : new boolean[]{true, false}) {
            server.enqueue(200, successBody("{\"tool\":\"role.wait\"}", 11, 5, 16));
            String input = prototype ? "{\"engineering\":{\"decisionGuideVersion\":\"prototype-v1\"}}"
                    : "{\"profile\":{\"document\":{\"engineering\":{\"decisionGuideVersion\":\"prototype-v1\"}}}}";
            provider.evaluate(call(input, route("key-1", "deepseek-flash", 640)), 5_000L);
            JsonNode request = server.lastRequestJson();
            assertEquals(prototype ? LiveDecisionProvider.PROTOTYPE_SYSTEM_TEXT : LiveDecisionProvider.SYSTEM_TEXT,
                    request.path("messages").get(0).path("content").asText());
        }
    }

    @Test
    void toolArgumentGuideUsesOnlyTheFrozenTopLevelRoleMarker() throws Exception {
        var old = JSON.createObjectNode();
        old.withObject("/engineering/researchNotes").put("version", "v2");
        var next = old.deepCopy();
        next.withObject("/engineering").put("toolArgumentGuide", "declared-fields-v1");
        var namedInDocument = old.deepCopy();
        namedInDocument.withObject("/profile/document/engineering").put("toolArgumentGuide", "declared-fields-v1");
        for (var input : List.of(old, next, namedInDocument)) {
            server.enqueue(200, successBody("{\"tool\":\"role.wait\"}", 11, 5, 16));
            provider.evaluate(call(input.toString(), route("key-1", "deepseek-flash", 640)), 5_000L);
            var messages = server.lastRequestJson().required("messages");
            assertEquals(input.toString(), messages.get(1).required("content").asText());
            String prompt = messages.get(0).required("content").textValue();
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8)));
            // 两份摘要来自保留的同输入真实模型对照系统消息。
            assertEquals(input.equals(next) ? "380ad4cddaf31851ba08526e434033b5e9d6fde8fa4191c049ddcdd0d85c1432"
                    : "70cf29801a4a639410ce2ff89407ccc4aa68601a301e3f671fe63d589ee5f3ec", digest);
        }
    }

    @Test
    @DisplayName("请求冻结：model、max_tokens、user 正文与 Call 完全一致，不读 SceneRoutes 当前配置")
    void requestFreezesRouteAndInputExactly() throws Exception {
        server.enqueue(200, successBody("{\"tool\":\"role.wait\"}", 11, 5, 16));
        DecisionRun.Call call = call("第一行观察\n第二行；含 \"引号\" 与 {JSON}", route("key-1",
                "deepseek-v4-pro", 640));
        LiveCompletionInbox.Completion completion = provider.evaluate(call, 5_000L);
        assertTrue(completion.businessReply());

        JsonNode request = server.lastRequestJson();
        assertEquals("deepseek-v4-pro", request.path("model").asText(), "model 取冻结路由");
        assertEquals(640, request.path("max_tokens").asInt(), "输出上限取冻结路由");
        assertEquals(false, request.path("stream").asBoolean());
        assertEquals("json_object", request.path("response_format").path("type").asText());
        assertEquals(2, request.path("messages").size());
        assertEquals(LiveDecisionProvider.SYSTEM_TEXT,
                request.path("messages").get(0).path("content").asText());
        assertEquals(call.inputText(), request.path("messages").get(1).path("content").asText(),
                "user 正文必须逐字等于 call.inputText");
    }

    // ------------------------------------------------------------ 成功与不完整结果

    @Test
    @DisplayName("COMPLETE：正文原样成为业务回执（含非法工具由世界后续判断），usage 与来源固定")
    void completePassesBodyThroughUnchanged() throws Exception {
        String content = "{\"tool\":\"role.execute\",\"args\":{\"symbol\":\"CUFE1\"}}";
        server.enqueue(200, successBody(content, 21, 9, 30));
        DecisionRun.Call call = call("观察", route("key-1", "deepseek-flash", 512));
        LiveCompletionInbox.Completion completion = provider.evaluate(call, 5_000L);

        assertTrue(completion.businessReply());
        assertNull(completion.failureCode());
        assertEquals(content, completion.reply().body(), "不得解析、纠正或改写模型正文");
        assertEquals("run-1", completion.reply().runId());
        assertEquals("attempt-1", completion.reply().attemptId());
        assertEquals(1, completion.reply().attemptNo());
        assertEquals(1, completion.reply().recoveryGeneration());
        assertNotNull(completion.reply().usage());
        assertEquals(1, completion.reply().usage().attempts());
        assertEquals(21, completion.reply().usage().inputTokens());
        assertEquals(9, completion.reply().usage().outputTokens());
        assertNull(completion.reply().usage().cost(), "费用未知，不得填 0.00");
        assertEquals("DEEPSEEK_HTTP", completion.reply().usageSource());
    }

    @Test
    @DisplayName("LENGTH/FILTERED/EMPTY/TOOL_CALLS：固定 PROVIDER_ 失败码、空正文、保留 usage")
    void incompleteOutcomesBecomeFixedProviderFailures() throws Exception {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("PROVIDER_LENGTH", body("assistant", "length", false, null, 7, 3));
        bodies.put("PROVIDER_FILTERED", body("assistant", "content_filter", false, null, 7, 3));
        bodies.put("PROVIDER_EMPTY", body("assistant", "stop", false, "", 7, 3));
        bodies.put("PROVIDER_TOOL_CALLS", toolCallsBody(7, 3));

        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            server.enqueue(200, entry.getValue());
            LiveCompletionInbox.Completion completion =
                    provider.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 5_000L);
            assertFalse(completion.businessReply(), entry.getKey());
            assertEquals(entry.getKey(), completion.failureCode());
            assertEquals("", completion.reply().body(), "技术失败不得携带正文");
            assertEquals("DEEPSEEK_HTTP", completion.reply().usageSource());
            assertNotNull(completion.reply().usage(), entry.getKey() + " 应保留真实 usage");
            assertEquals(7, completion.reply().usage().inputTokens());
            assertEquals(3, completion.reply().usage().outputTokens());
        }
    }

    // ------------------------------------------------------------ HTTP 失败映射

    @Test
    @DisplayName("HTTP 401/429/500：固定 HTTP_ 失败码，429/500 保留错误体里的已知 usage")
    void httpFailuresMapToFixedCodes() throws Exception {
        server.enqueue(401, "{\"error\":\"unauthorized\"}");
        LiveCompletionInbox.Completion auth =
                provider.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 5_000L);
        assertEquals("HTTP_AUTH", auth.failureCode());
        assertEquals("", auth.reply().body());
        assertNull(auth.reply().usage(), "401 无 usage 必须保持 null");

        server.enqueue(429, "{\"error\":\"rate\",\"usage\":"
                + "{\"prompt_tokens\":12,\"completion_tokens\":0,\"total_tokens\":12}}");
        LiveCompletionInbox.Completion limited =
                provider.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 5_000L);
        assertEquals("HTTP_RATE_LIMITED", limited.failureCode());
        assertNotNull(limited.reply().usage());
        assertEquals(12, limited.reply().usage().inputTokens());
        assertEquals(0, limited.reply().usage().outputTokens());

        server.enqueue(500, "{\"error\":\"boom\"}");
        LiveCompletionInbox.Completion unavailable =
                provider.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 5_000L);
        assertEquals("HTTP_SERVICE_UNAVAILABLE", unavailable.failureCode());
        assertNull(unavailable.reply().usage());
    }

    @Test
    @DisplayName("超时：HTTP_TIMEOUT，空正文，usage 为 null")
    void timeoutBecomesFixedFailure() throws Exception {
        server.enqueueSlow(200, successBody("{\"tool\":\"role.wait\"}", 1, 1, 2), 400L);
        LiveCompletionInbox.Completion completion =
                provider.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 60L);
        assertEquals("HTTP_TIMEOUT", completion.failureCode());
        assertEquals("", completion.reply().body());
        assertNull(completion.reply().usage());
    }

    @Test
    @DisplayName("响应缺少 usage：协议失败且 usage 保持 null，不伪造 0")
    void missingUsageStaysNull() throws Exception {
        server.enqueue(200, "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"{\\\"tool\\\":\\\"role.wait\\\"}\"},\"finish_reason\":\"stop\"}]}");
        LiveCompletionInbox.Completion completion =
                provider.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 5_000L);
        assertEquals("HTTP_PROTOCOL", completion.failureCode());
        assertEquals("", completion.reply().body());
        assertNull(completion.reply().usage(), "缺 usage 必须为 null，不得填 0");
    }

    // ------------------------------------------------------------ 秘密与身份

    @Test
    @DisplayName("秘密反射：响应正文回显本次 key 时整体拒绝，结果与回执都不含 key")
    void reflectedKeyIsRejectedAndNeverPropagated() throws Exception {
        String key = KEYS.get("key-1");
        server.enqueue(200, successBody("leaked " + key, 3, 2, 5));
        LiveCompletionInbox.Completion completion =
                provider.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 5_000L);
        assertEquals("HTTP_PROTOCOL", completion.failureCode());
        assertEquals("", completion.reply().body());
        assertFalse(completion.toString().contains(key));
        assertFalse(String.valueOf(completion.reply()).contains(key));
        assertFalse(String.valueOf(completion.reply().usage()).contains(key));
    }

    @Test
    @DisplayName("非法 key 形状在发出请求前被 DeepSeekClient 拒绝，本地服务收到 0 次请求")
    void malformedKeyNeverReachesNetwork() throws Exception {
        LiveDecisionProvider broken = new LiveDecisionProvider(slot ->
                new DeepSeekClient(() -> "sk-bad\nkey-value", server.uri()));
        LiveCompletionInbox.Completion completion =
                broken.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 5_000L);
        assertEquals("HTTP_AUTH", completion.failureCode());
        assertEquals(0, server.requestCount(), "凭据形状不合法时不得发出 HTTP 请求");
        assertFalse(completion.toString().contains("sk-bad"));
    }

    @Test
    @DisplayName("两个槽位各用各的 key：请求头只出现本次槽位的 key，其余四槽从不进入请求")
    void eachCallUsesOnlyItsOwnSlotKey() throws Exception {
        server.enqueue(200, successBody("{\"tool\":\"role.wait\"}", 4, 2, 6));
        provider.evaluate(call("观察一", route("key-1", "deepseek-flash", 512)), 5_000L);
        server.enqueue(200, successBody("{\"tool\":\"role.wait\"}", 4, 2, 6));
        provider.evaluate(call("观察二", route("key-2", "deepseek-flash", 512)), 5_000L);

        List<String> auth = server.authorizationHeaders();
        assertEquals(2, auth.size());
        assertEquals("Bearer " + KEYS.get("key-1"), auth.get(0));
        assertEquals("Bearer " + KEYS.get("key-2"), auth.get(1));

        String all = server.allRequestText();
        for (String slot : List.of("key-3", "key-4", "key-5")) {
            assertFalse(all.contains(KEYS.get(slot)), slot + " 的 key 不得出现在请求中");
        }
        assertFalse(server.allRequestText().contains(KEYS.get("key-1"))
                && server.requestText(1).contains(KEYS.get("key-1")),
                "第二次请求不得携带第一个槽位的 key");
    }

    @Test
    @DisplayName("来源固定：usageSource 只有 DEEPSEEK_HTTP，不携带 requestId 或回报 model")
    void usageSourceNeverCarriesProviderMetadata() throws Exception {
        server.enqueue(200, "{\"id\":\"req-abc\",\"model\":\"deepseek-flash\",\"choices\":["
                + "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + "\"{\\\"tool\\\":\\\"role.wait\\\"}\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}");
        LiveCompletionInbox.Completion completion =
                provider.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 5_000L);
        assertEquals("DEEPSEEK_HTTP", completion.reply().usageSource());
        String reply = String.valueOf(completion.reply());
        assertFalse(reply.contains("req-abc"), "不得把 requestId 放进来源或回执");
        assertFalse(reply.contains("deepseek-flash"), "不得把提供方回报 model 放进回执");
    }

    // ------------------------------------------------------------ 拒绝与边界

    @Test
    @DisplayName("参数非法：无冻结 plan、timeout 越界都在调用前拒绝，且不产生请求")
    void invalidArgumentsRejectedBeforeAnyCall() throws Exception {
        DecisionRun.Call noPlan = new DecisionRun.Call("run-1", "action-1", "attempt-1", 1, 1,
                "wake", List.of("role.wait"), "观察");
        assertThrows(IllegalArgumentException.class, () -> provider.evaluate(noPlan, 5_000L));
        DecisionRun.Call planned = call("观察", route("key-1", "deepseek-flash", 512));
        assertThrows(IllegalArgumentException.class, () -> provider.evaluate(planned, 0L));
        assertThrows(IllegalArgumentException.class, () -> provider.evaluate(planned, 60_001L));
        assertThrows(IllegalArgumentException.class, () -> provider.evaluate(null, 5_000L));
        assertEquals(0, server.requestCount(), "参数拒绝前不得发请求");
    }

    @Test
    @DisplayName("异常不外传自由文本：本地 RuntimeException 变成 LOCAL_PROVIDER_ERROR 且 usage 为 null")
    void localRuntimeFailureIsNormalized() throws Exception {
        LiveDecisionProvider failing = new LiveDecisionProvider(slot -> {
            throw new IllegalStateException("内部路径 /tmp/secret");
        });
        LiveCompletionInbox.Completion completion =
                failing.evaluate(call("观察", route("key-1", "deepseek-flash", 512)), 5_000L);
        assertEquals("LOCAL_PROVIDER_ERROR", completion.failureCode());
        assertEquals("", completion.reply().body());
        assertNull(completion.reply().usage());
        assertFalse(completion.toString().contains("内部路径"));
    }

    @Test
    @DisplayName("职责边界：provider 不持有 WorldRuntime、不创建线程池，只保留按槽位取客户的函数")
    void providerHoldsNoWorldAndNoExecutor() {
        for (Field field : LiveDecisionProvider.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("WorldRuntime"),
                    "provider 不得持有世界：" + field.getName());
            assertFalse(ExecutorService.class.isAssignableFrom(field.getType()),
                    "provider 不得持有线程池：" + field.getName());
            assertFalse(Thread.class.equals(field.getType()), "provider 不得持有线程");
        }
        for (var method : LiveDecisionProvider.class.getDeclaredMethods()) {
            assertFalse(method.getName().equals("start") || method.getName().equals("submit"),
                    "provider 不得提供运行入口：" + method.getName());
        }
    }

    @Test
    @DisplayName("不打印：一次真实调用期间 stdout/stderr 不产生任何输出（输入与凭据都不可入日志）")
    void evaluateWritesNothingToConsole() throws Exception {
        server.enqueue(200, successBody("{\"tool\":\"role.wait\"}", 2, 1, 3));
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            provider.evaluate(call("观察内容不应出现在日志", route("key-1", "deepseek-flash", 512)),
                    5_000L);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertEquals(0, out.size(), "provider 不得打印请求或响应");
        assertEquals(0, err.size(), "provider 不得打印错误细节");
    }

    @Test
    @DisplayName("预算关系：冻结输入字节 + 固定提示字节仍落在 inputBytes+1024 预留之内")
    void reservedBudgetCoversFixedPrompt() {
        String input = "观察材料：持仓 100 股，可用现金 1000.00，当前无未完成动作。";
        DecisionRun.Call call = call(input, route("key-1", "deepseek-flash", 512));
        int inputBytes = input.getBytes(StandardCharsets.UTF_8).length;
        int systemBytes = LiveDecisionProvider.SYSTEM_TEXT.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(inputBytes, call.plan().inputBytes(), "计划里的字节数必须对应真实输入");
        assertTrue(call.plan().reservedInputTokens() >= inputBytes + systemBytes,
                "预留必须覆盖准确输入加固定提示，实际预留 " + call.plan().reservedInputTokens());
    }

    @Test
    @DisplayName("构造面：只有 KeySlots 生产构造与包内注入构造，不暴露端点/URL 配置字段")
    void productionSurfaceHasNoEndpointConfiguration() {
        Constructor<?>[] constructors = LiveDecisionProvider.class.getDeclaredConstructors();
        assertEquals(2, constructors.length, "只允许生产构造与包内测试构造");
        boolean publicKeys = false;
        for (Constructor<?> constructor : constructors) {
            if (Modifier.isPublic(constructor.getModifiers())
                    && constructor.getParameterTypes().length == 1
                    && constructor.getParameterTypes()[0] == KeySlots.class) {
                publicKeys = true;
            }
        }
        assertTrue(publicKeys, "生产构造必须且只能接收 KeySlots");
        for (Field field : LiveDecisionProvider.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertFalse(field.getType() == URI.class, "不得持有可配置端点：" + field.getName());
            assertFalse(field.getType() == String.class, "不得持有 URL 文本：" + field.getName());
        }
    }


    @Test void auditedEvaluationSendsOnceAndPreservesLegacyRequestAndCompletion() throws Exception {
        String content = "{\"tool\":\"role.wait\",\"decision\":{\"basis\":\"verbatim body\"}}";
        var call = call("{\"engineering\":{\"roleContextVersion\":\"deliberation-v4\"}}", route("key-1", "deepseek-flash", 512));
        server.enqueue(200, successBody(content, 5, 2, 7));
        var audited = audited(call);
        assertEquals(1, server.requestCount());
        var firstRequest = server.lastRequestJson();
        var tree = JSON.valueToTree(audited);
        var provenance = tree.path("provenance");
        assertEquals("provider-provenance-v1", provenance.path("schema").asText());
        assertEquals("deepseek-flash", provenance.path("requestedModel").asText());
        assertEquals("deepseek-flash", provenance.path("providerReportedModel").asText());
        assertEquals("req-fixture", provenance.path("requestId").asText());
        assertEquals("COMPLETE", provenance.path("outcome").asText());
        assertTrue(provenance.path("failureCode").isNull());
        assertEquals("run-1", provenance.path("runId").asText());
        assertEquals("action-1", provenance.path("actionId").asText());
        assertEquals("attempt-1", provenance.path("attemptId").asText());
        assertEquals(1, provenance.path("attemptNo").asInt());
        assertEquals(1, provenance.path("recoveryGeneration").asInt());
        assertEquals(content, tree.path("completion").path("reply").path("body").asText());
        assertFalse(audited.toString().contains("verbatim body"));
        assertFalse(audited.toString().contains("req-fixture"));
        server.enqueue(200, successBody(content, 5, 2, 7));
        var legacy = provider.evaluate(call, 5_000);
        assertEquals(2, server.requestCount());
        assertEquals(JSON.writeValueAsString(legacy), JSON.writeValueAsString(tree.path("completion")));
        assertEquals(firstRequest, server.lastRequestJson());
        assertEquals(LiveDecisionProvider.PLANNING_SYSTEM_TEXT, firstRequest.path("messages").get(0).path("content").asText());
        assertFalse(firstRequest.toString().contains("provider-provenance"));
    }

    @Test void auditedHttpFailureHasUnknownProviderIdentityAndNoExtraSend() throws Exception {
        server.enqueue(503, "{\"id\":\"untrusted-error-id\",\"model\":\"untrusted-error-model\"}");
        var tree = JSON.valueToTree(audited(call("observation", route("key-1", "deepseek-flash", 512))));
        assertEquals(1, server.requestCount());
        assertEquals("HTTP_SERVICE_UNAVAILABLE", tree.path("completion").path("failureCode").asText());
        assertEquals("HTTP_SERVICE_UNAVAILABLE", tree.path("provenance").path("failureCode").asText());
        assertEquals("UNKNOWN", tree.path("provenance").path("outcome").asText());
        assertTrue(tree.path("provenance").path("providerReportedModel").isNull());
        assertTrue(tree.path("provenance").path("requestId").isNull());
        assertEquals("deepseek-flash", tree.path("provenance").path("requestedModel").asText());
    }

    @Test void auditedMetadataReusesClientFilteringForCredentialsAndInvalidIdentifiers() throws Exception {
        for (String unsafe : List.of(KEYS.get("key-1"), "bad id", "x".repeat(65))) {
            var body = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(successBody("{\"tool\":\"role.wait\"}", 5, 2, 7));
            body.put("id", unsafe); body.put("model", unsafe);
            server.enqueue(200, body.toString());
            var audited = audited(call("observation", route("key-1", "deepseek-flash", 512)));
            var tree = JSON.valueToTree(audited);
            assertTrue(tree.path("completion").path("failureCode").isNull());
            assertTrue(tree.path("provenance").path("providerReportedModel").isNull());
            assertTrue(tree.path("provenance").path("requestId").isNull());
            assertFalse(tree.toString().contains(unsafe));
            assertFalse(audited.toString().contains(unsafe));
        }
        assertEquals(3, server.requestCount());
    }

    @Test void auditedIncompleteResponseRetainsParsedOutcomeAndSanitizedIdentity() throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(body("assistant", "length", false, null, 7, 3));
        body.put("id", "req-length"); body.put("model", "provider-declared-model");
        server.enqueue(200, body.toString());
        var tree = JSON.valueToTree(audited(call("observation", route("key-1", "deepseek-flash", 512))));
        assertEquals(1, server.requestCount());
        assertEquals("LENGTH", tree.path("provenance").path("outcome").asText());
        assertEquals("PROVIDER_LENGTH", tree.path("completion").path("failureCode").asText());
        assertEquals("PROVIDER_LENGTH", tree.path("provenance").path("failureCode").asText());
        assertEquals("provider-declared-model", tree.path("provenance").path("providerReportedModel").asText());
        assertEquals("req-length", tree.path("provenance").path("requestId").asText());
    }

    private Object audited(DecisionRun.Call call) throws Exception {
        var method = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> LiveDecisionProvider.class.getDeclaredMethod("evaluateAudited", DecisionRun.Call.class, long.class));
        method.setAccessible(true);
        return method.invoke(provider, call, 5_000L);
    }


    @Test void probeSidecarRecordsSameCallAndReplyWithSeparateModelCounts(@org.junit.jupiter.api.io.TempDir java.nio.file.Path out) throws Exception {
        var evidence = evidence(out);
        var call = call("{\"engineering\":{\"roleContextVersion\":\"deliberation-v4\"}}", route("key-1", "deepseek-flash", 512));
        server.enqueue(200, successBody("{\"tool\":\"role.wait\"}", 5, 2, 7));
        var completion = record(evidence, call);
        assertEquals(1, server.requestCount());
        assertEquals(JSON.readTree(JSON.writeValueAsBytes(call)), JSON.readTree(out.resolve("call-1.json").toFile()));
        assertEquals(JSON.readTree(JSON.writeValueAsBytes(completion)), JSON.readTree(out.resolve("reply-1.json").toFile()));
        assertEquals(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(call), java.nio.file.Files.readString(out.resolve("call-1.json")));
        assertEquals(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(completion), java.nio.file.Files.readString(out.resolve("reply-1.json")));
        var sidecar = JSON.readTree(out.resolve("provider-1.json").toFile());
        assertEquals("provider-provenance-v1", sidecar.path("schema").asText());
        assertEquals("req-fixture", sidecar.path("requestId").asText());
        var summary = evidenceSummary(evidence);
        assertTrue(summary.path("evidenceComplete").asBoolean());
        assertEquals(1, summary.path("completedCalls").asInt());
        assertEquals(1, summary.path("recordedCalls").asInt());
        assertEquals(0, summary.path("missingIdentityCalls").asInt());
        assertEquals(1, summary.path("reportedModelCounts").path("deepseek-flash").asInt());
        assertEquals("UNKNOWN", summary.path("underlyingModelVersion").asText());
        assertFalse(server.lastRequestJson().toString().contains("req-fixture"));
    }

    @Test void probeSidecarWriteFailureCannotReplacePaidCompletion(@org.junit.jupiter.api.io.TempDir java.nio.file.Path out) throws Exception {
        var evidence = evidence(out);
        java.nio.file.Files.createDirectory(out.resolve("provider-1.json"));
        String body = "{\"tool\":\"role.wait\",\"decision\":{\"basis\":\"original paid reply\"}}";
        server.enqueue(200, successBody(body, 5, 2, 7));
        var completion = record(evidence, call("observation", route("key-1", "deepseek-flash", 512)));
        assertTrue(completion.businessReply());
        assertEquals(body, completion.reply().body());
        assertEquals(1, server.requestCount());
        assertEquals(JSON.readTree(JSON.writeValueAsBytes(completion)), JSON.readTree(out.resolve("reply-1.json").toFile()));
        var summary = evidenceSummary(evidence);
        assertFalse(summary.path("evidenceComplete").asBoolean());
        assertEquals("PROBE_EVIDENCE_WRITE_FAILED", summary.path("failureCode").asText());
        assertEquals(1, summary.path("completedCalls").asInt());
        assertEquals(0, summary.path("recordedCalls").asInt());
        assertEquals(true, evidence.getClass().getDeclaredMethod("failed").invoke(evidence));
    }

    @Test void probeExplicitUnknownIdentityIsCompleteButNeverVersionPinned(@org.junit.jupiter.api.io.TempDir java.nio.file.Path out) throws Exception {
        var evidence = evidence(out);
        server.enqueue(503, "{}");
        var completion = record(evidence, call("observation", route("key-1", "deepseek-flash", 512)));
        assertEquals("HTTP_SERVICE_UNAVAILABLE", completion.failureCode());
        var summary = evidenceSummary(evidence);
        assertTrue(summary.path("evidenceComplete").asBoolean());
        assertEquals(1, summary.path("missingIdentityCalls").asInt());
        assertEquals(0, summary.path("reportedModelCounts").size());
        assertEquals("UNKNOWN", summary.path("underlyingModelVersion").asText());
        assertTrue(JSON.readTree(out.resolve("provider-1.json").toFile()).path("providerReportedModel").isNull());
    }

    private Object evidence(java.nio.file.Path out) throws Exception {
        var type = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> Class.forName("edu.cufe.auction.web.ProbeProviderEvidence"));
        return type.getDeclaredConstructor(java.nio.file.Path.class).newInstance(out);
    }
    private LiveCompletionInbox.Completion record(Object evidence, DecisionRun.Call call) throws Exception {
        return (LiveCompletionInbox.Completion) evidence.getClass().getDeclaredMethod("evaluate", int.class, DecisionRun.Call.class, long.class, LiveDecisionProvider.class)
                .invoke(evidence, 1, call, 5_000L, provider);
    }
    private JsonNode evidenceSummary(Object evidence) throws Exception {
        return (JsonNode) evidence.getClass().getDeclaredMethod("summary", int.class, int.class).invoke(evidence, 1, 0);
    }

    // ------------------------------------------------------------ fixture

    private static DecisionModel.Route route(String slotId, String model, int maxOutputTokens) {
        return new DecisionModel.Route("trading", slotId, model, 7L, 5, 5_000,
                maxOutputTokens, 2);
    }

    private static DecisionRun.Call call(String input, DecisionModel.Route route) {
        DecisionModel.ProviderPlan plan = new DecisionModel.ProviderPlan(route,
                input.getBytes(StandardCharsets.UTF_8).length);
        return new DecisionRun.Call("run-1", "action-1", "attempt-1", 1, 1, "wake",
                List.of("role.wait", "role.startRead"), input, plan);
    }

    private static String successBody(String content, int prompt, int completion, int total)
            throws Exception {
        var root = JSON.createObjectNode();
        root.put("id", "req-fixture");
        root.put("model", "deepseek-flash");
        var choices = root.putArray("choices");
        var choice = choices.addObject();
        choice.put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", content);
        choice.put("finish_reason", "stop");
        root.putObject("usage").put("prompt_tokens", prompt)
                .put("completion_tokens", completion).put("total_tokens", total);
        return JSON.writeValueAsString(root);
    }

    private static String body(String role, String finish, boolean toolCalls, String content,
                               int prompt, int completion) throws Exception {
        var root = JSON.createObjectNode();
        var choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        var message = choice.putObject("message").put("role", role);
        if (content != null) {
            message.put("content", content);
        }
        if (toolCalls) {
            message.putArray("tool_calls");
        }
        choice.put("finish_reason", finish);
        root.putObject("usage").put("prompt_tokens", prompt)
                .put("completion_tokens", completion)
                .put("total_tokens", prompt + completion);
        return JSON.writeValueAsString(root);
    }

    private static String toolCallsBody(int prompt, int completion) throws Exception {
        var root = JSON.createObjectNode();
        var choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        var message = choice.putObject("message").put("role", "assistant");
        message.putArray("tool_calls").addObject().put("id", "call-1");
        choice.put("finish_reason", "tool_calls");
        root.putObject("usage").put("prompt_tokens", prompt)
                .put("completion_tokens", completion)
                .put("total_tokens", prompt + completion);
        return JSON.writeValueAsString(root);
    }

    /** 真实回环 HTTP 服务：按队列回放响应，记录请求头与正文。 */
    private static final class LoopbackServer implements AutoCloseable {

        private record Reply(int status, String body, long delayMillis) {
        }

        private final HttpServer server;
        private final Deque<Reply> replies = new ArrayDeque<>();
        private final List<String> bodies = new ArrayList<>();
        private final List<String> authorizations = new ArrayList<>();
        private final AtomicInteger counter = new AtomicInteger();

        private LoopbackServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/chat/completions", this::handle);
            server.setExecutor(null);
            server.start();
        }

        private synchronized void enqueue(int status, String body) {
            replies.add(new Reply(status, body, 0L));
        }

        private synchronized void enqueueSlow(int status, String body, long delay) {
            replies.add(new Reply(status, body, delay));
        }

        private void handle(HttpExchange exchange) throws java.io.IOException {
            byte[] request = exchange.getRequestBody().readAllBytes();
            String authorization;
            Reply reply;
            synchronized (this) {
                authorizations.add(String.valueOf(
                        exchange.getRequestHeaders().getFirst("Authorization")));
                bodies.add(new String(request, StandardCharsets.UTF_8));
                reply = replies.poll();
                counter.incrementAndGet();
            }
            if (reply == null) {
                reply = new Reply(500, "{\"error\":\"no queued reply\"}", 0L);
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            } catch (java.io.IOException ignored) {
                // 客户端超时后连接可能已关闭；测试只关心服务端确实收到过请求。
            }
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/chat/completions");
        }

        private synchronized int requestCount() {
            return counter.get();
        }

        private synchronized JsonNode lastRequestJson() throws Exception {
            return JSON.readTree(bodies.get(bodies.size() - 1));
        }

        private synchronized List<String> authorizationHeaders() {
            return List.copyOf(authorizations);
        }

        private synchronized String allRequestText() {
            return String.join("\n", bodies) + "\n" + String.join("\n", authorizations);
        }

        private synchronized String requestText(int index) {
            return bodies.get(index) + "\n" + authorizations.get(index);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
