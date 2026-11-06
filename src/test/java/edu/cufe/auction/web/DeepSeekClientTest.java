package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正式适配器合同：全部走真实回环 HTTP，不使用 mock。
 *
 * <p>夹具原则：响应体一律由 Jackson 从普通 Java 值构造（{@link #baseOk()} + 单字段破坏），
 * 不再手工拼接 JSON 字符串。每条反例之前都先跑同一个合法基底（{@code /ok}）作为对照，
 * 因此反例失败只可能来自被破坏的那个字段，而不是夹具本身是非法 JSON。</p>
 *
 * <p>覆盖：请求构造、响应判定、严格类型与边界、传输与安全。本适配器只完成传输协议，
 * 不把 JSON 正文解释成任何经济动作，也不接触 WorldRuntime。</p>
 */
class DeepSeekClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "sk-testkey-aaaaaaaaaaaa";
    private static final String OTHER_KEY = "sk-testkey-bbbbbbbbbbbb";

    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Map<String, List<String>> authHeaders = new ConcurrentHashMap<>();
    private final Map<String, List<String>> bodies = new ConcurrentHashMap<>();

    private ExecutorService pool;
    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        hits.clear();
        authHeaders.clear();
        bodies.clear();
        pool = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(pool);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            pool = null;
        }
    }

    // ---------------------------------------------------------------- 合法路径

    @Test
    @DisplayName("完整响应：单 choice、assistant、stop、非空白正文，用量与元数据齐全")
    void completeResponse() throws Exception {
        DeepSeekClient.Result result = client("/ok", KEY).send(request());

        assertEquals(DeepSeekClient.Outcome.COMPLETE, result.outcome());
        assertEquals("{\"answer\":42}", result.content());
        assertEquals(21, result.usage().promptTokens());
        assertEquals(9, result.usage().completionTokens());
        assertEquals(30, result.usage().totalTokens());
        assertEquals(5, result.usage().cacheHitTokens());
        assertEquals(16, result.usage().cacheMissTokens());
        assertEquals("chatcmpl-abc123", result.requestId());
        assertEquals("deepseek-flash", result.model());
        assertEquals(1, hits("/ok"), "一次调用只发一个请求");
    }

    @Test
    @DisplayName("请求体：system+user、stream=false、thinking 关闭、JSON 模式、max_tokens 只限输出")
    void requestShape() throws Exception {
        client("/ok", KEY).send(request());
        JsonNode sent = JSON.readTree(bodies("/ok").get(0));

        assertEquals("deepseek-flash", sent.path("model").asText());
        assertFalse(sent.path("stream").booleanValue());
        assertEquals("disabled", sent.path("thinking").path("type").asText());
        assertEquals("json_object", sent.path("response_format").path("type").asText());
        assertEquals(512, sent.path("max_tokens").asInt());
        assertEquals(2, sent.path("messages").size());
        assertEquals("system", sent.path("messages").get(0).path("role").asText());
        assertEquals("user", sent.path("messages").get(1).path("role").asText());
        assertFalse(bodies("/ok").get(0).contains(KEY), "请求体不得包含凭据");
        assertEquals("Bearer " + KEY, authHeaders("/ok").get(0));
    }

    @Test
    @DisplayName("未知新 metadata 兼容，不影响判定")
    void unknownMetadataTolerated() throws Exception {
        controlIsValid();
        DeepSeekClient.Result result = client("/unknownmeta", KEY).send(request());
        assertEquals(DeepSeekClient.Outcome.COMPLETE, result.outcome());
        assertEquals("{\"answer\":42}", result.content());
    }

    @Test
    @DisplayName("不同槽位只更换 Authorization 头，请求体保持一致")
    void slotKeyOnlyInHeader() throws Exception {
        client("/ok", KEY).send(request());
        client("/ok", OTHER_KEY).send(request());
        assertEquals(2, bodies("/ok").size());
        assertEquals(bodies("/ok").get(0), bodies("/ok").get(1));
        assertEquals("Bearer " + OTHER_KEY, authHeaders("/ok").get(1));
        assertFalse(bodies("/ok").get(1).contains(OTHER_KEY));
    }

    // ---------------------------------------------------------------- 响应判定

    @Test
    @DisplayName("空白正文（官方 JSON 失败可能只有空格）不算完整，也不携带内容")
    void blankContentIsNotComplete() throws Exception {
        controlIsValid();
        DeepSeekClient.Result result = client("/blank", KEY).send(request());
        assertEquals(DeepSeekClient.Outcome.EMPTY, result.outcome());
        assertNull(result.content());
        assertNotNull(result.usage(), "空白正文仍应保留已知用量");
    }

    @Test
    @DisplayName("length 与 tool_calls 不执行动作，但保留已知用量")
    void truncatedAndToolCallsKeepUsage() throws Exception {
        controlIsValid();
        DeepSeekClient.Result length = client("/length", KEY).send(request());
        assertEquals(DeepSeekClient.Outcome.LENGTH, length.outcome());
        assertNull(length.content());
        assertEquals(30, length.usage().totalTokens());

        DeepSeekClient.Result tools = client("/tools", KEY).send(request());
        assertEquals(DeepSeekClient.Outcome.TOOL_CALLS, tools.outcome());
        assertNull(tools.content());
        assertEquals(30, tools.usage().totalTokens());

        DeepSeekClient.Result filtered = client("/filtered", KEY).send(request());
        assertEquals(DeepSeekClient.Outcome.FILTERED, filtered.outcome());
        assertNull(filtered.content());
    }

    // ---------------------------------------------------------------- 用量

    @Test
    @DisplayName("缺 usage 拒绝，不伪造 0")
    void missingUsageRejected() throws Exception {
        assertProtocol("/nousage");
    }

    @Test
    @DisplayName("小数、字符串、布尔、超 int 的 usage 全部拒绝")
    void malformedUsageRejected() throws Exception {
        assertProtocol("/usagestr");
        assertProtocol("/usagebool");
        assertProtocol("/usagebig");
        assertProtocol("/frac");
    }

    @Test
    @DisplayName("total 必须等于 prompt+completion")
    void totalMismatchRejected() throws Exception {
        assertProtocol("/total");
    }

    @Test
    @DisplayName("缓存计数：缺失为 null 合法；单个已知值不得超过 prompt")
    void cacheCountsBounds() throws Exception {
        controlIsValid();
        DeepSeekClient.Result partial = client("/cachepartial", KEY).send(request());
        assertEquals(5, partial.usage().cacheHitTokens());
        assertNull(partial.usage().cacheMissTokens(), "缺失的缓存未命中必须保持 null");

        DeepSeekClient.Result none = client("/cachenone", KEY).send(request());
        assertNull(none.usage().cacheHitTokens());
        assertNull(none.usage().cacheMissTokens());

        assertProtocol("/cachehit");
        assertProtocol("/cachesum");
    }

    @Test
    @DisplayName("缓存计数存在但类型错误或超出 int 范围必须协议失败，不得当成缺失")
    void cacheCountsTypeStrict() throws Exception {
        assertProtocol("/cachestring");
        assertProtocol("/cachefloat");
        assertProtocol("/cachebool");
        assertProtocol("/cachebig");
    }

    // ---------------------------------------------------------------- 结构严格性

    @Test
    @DisplayName("choices/index：必须恰好一条且 index=0，超 int 的 index 不得截断为 0")
    void choiceIndexStrict() throws Exception {
        assertProtocol("/two");
        assertProtocol("/index1");
        assertProtocol("/indexbig");
        assertProtocol("/indexfloat");
    }

    @Test
    @DisplayName("role、finish_reason、content 类型必须严格")
    void messageShapeStrict() throws Exception {
        assertProtocol("/role");
        assertProtocol("/weird");
        assertProtocol("/numcontent");
    }

    @Test
    @DisplayName("重复键与尾随 JSON 拒绝")
    void jsonStrictness() throws Exception {
        assertProtocol("/dup");
        assertProtocol("/trailing");
    }

    // ---------------------------------------------------------------- 传输

    @Test
    @DisplayName("302 不跟随：命中重定向端点即失败，目标端点零次命中")
    void redirectNotFollowed() throws Exception {
        assertEquals(DeepSeekClient.Failure.Code.REQUEST_REJECTED, failure("/redirect", KEY).code());
        assertEquals(0, hits("/target"), "绝不能跟随重定向");
        assertEquals(1, hits("/redirect"));
    }

    @Test
    @DisplayName("401/403 认证失败、429 限流、5xx 服务失败，且不回显响应体")
    void httpFailureMapping() throws Exception {
        DeepSeekClient.Failure auth = failure("/auth", KEY);
        assertEquals(DeepSeekClient.Failure.Code.AUTH, auth.code());
        assertFalse(auth.getMessage().contains("invalid_api_key"));
        assertFalse(auth.toString().contains(KEY));

        DeepSeekClient.Failure limited = failure("/limited", KEY);
        assertEquals(DeepSeekClient.Failure.Code.RATE_LIMITED, limited.code());
        assertNotNull(limited.usageOrNull(), "限流响应里的已知用量应保留");

        assertEquals(DeepSeekClient.Failure.Code.SERVICE_UNAVAILABLE,
                failure("/server", KEY).code());
    }

    @Test
    @DisplayName("响应超过 1 MiB 被有界取消，不挂住")
    void oversizedResponseCancelled() throws Exception {
        long start = System.currentTimeMillis();
        assertEquals(DeepSeekClient.Failure.Code.PROTOCOL, failure("/big", KEY).code());
        assertTrue(System.currentTimeMillis() - start < 10_000);
    }

    @Test
    @DisplayName("期限覆盖响应头与完整正文：只回头部后卡住也必须超时")
    void deadlineCoversHeadersAndBody() throws Exception {
        long start = System.currentTimeMillis();
        assertEquals(DeepSeekClient.Failure.Code.TIMEOUT, failure("/stall", KEY).code());
        assertTrue(System.currentTimeMillis() - start < 6_000);

        long headerStart = System.currentTimeMillis();
        assertEquals(DeepSeekClient.Failure.Code.TIMEOUT, failure("/slowheaders", KEY).code());
        assertTrue(System.currentTimeMillis() - headerStart < 6_000);
    }

    @Test
    @DisplayName("不自动重试：一次调用只发一次请求")
    void noAutomaticRetry() throws Exception {
        assertEquals(DeepSeekClient.Failure.Code.SERVICE_UNAVAILABLE, failure("/server", KEY).code());
        assertEquals(1, hits("/server"));
        assertEquals(DeepSeekClient.Failure.Code.RATE_LIMITED, failure("/limited", KEY).code());
        assertEquals(1, hits("/limited"));
    }

    // ---------------------------------------------------------------- 凭据与参数

    @Test
    @DisplayName("凭据形状与来源：CRLF、短值、空白一律 AUTH，且不发请求、不带 cause")
    void credentialShape() throws Exception {
        assertEquals(DeepSeekClient.Failure.Code.AUTH,
                failure("/ok", "sk-aaaaaaaaaaaaaaaa\r\nX: y").code());
        assertEquals(0, hits("/ok"), "非法凭据不得进入请求");
        assertEquals(DeepSeekClient.Failure.Code.AUTH, failure("/ok", "sk-short").code());
        assertEquals(DeepSeekClient.Failure.Code.AUTH, failure("/ok", "   ").code());

        DeepSeekClient throwing = new DeepSeekClient(() -> {
            throw new IllegalStateException("supplier down");
        }, URI.create(base + "/ok"));
        DeepSeekClient.Failure failure = assertThrows(DeepSeekClient.Failure.class,
                () -> throwing.send(request()));
        assertEquals(DeepSeekClient.Failure.Code.AUTH, failure.code());
        assertNull(failure.getCause(), "不得携带 cause");
        assertEquals(0, hits("/ok"));
    }

    @Test
    @DisplayName("响应 metadata 反射凭据时丢弃；正文含凭据整条拒绝并保留用量")
    void credentialReflection() throws Exception {
        controlIsValid();
        DeepSeekClient.Result idLeak = client("/secretid", KEY).send(request());
        assertNull(idLeak.requestId());
        DeepSeekClient.Result modelLeak = client("/secretmodel", KEY).send(request());
        assertNull(modelLeak.model());

        String rendered = idLeak.toString() + modelLeak.toString();
        assertFalse(rendered.contains("sk-"));
        assertFalse(rendered.contains("chatcmpl"), "toString 不输出提供方可控 id 文本");
        assertFalse(rendered.contains("deepseek"), "toString 不输出 model 文本");

        DeepSeekClient.Failure contentLeak = failure("/secretcontent", KEY);
        assertEquals(DeepSeekClient.Failure.Code.PROTOCOL, contentLeak.code());
        assertNotNull(contentLeak.usageOrNull(), "拒绝时仍保留已知用量");
        assertFalse(contentLeak.toString().contains("sk-"));
    }

    @Test
    @DisplayName("非法参数与超限请求在发送前拒绝，服务端零命中")
    void invalidParamsRejectedBeforeSend() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient.Request("gpt-4o", "s", "u", 512, 1_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient.Request("deepseek-flash", "s", "u", 127, 1_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient.Request("deepseek-flash", "s", "u", 8_193, 1_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient.Request("deepseek-flash", "s", "u", 512, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient.Request("deepseek-flash", "s", "u", 512, 60_001L));

        String huge = "x".repeat(70_000);
        assertThrows(RuntimeException.class, () -> client("/ok", KEY)
                .send(new DeepSeekClient.Request("deepseek-flash", huge, "u", 512, 2_000L)));
        assertEquals(0, hits("/ok"), "超限请求不得发出");
    }

    @Test
    @DisplayName("测试端点只允许纯回环：userinfo/query/fragment 与非回环一律拒绝")
    void endpointShape() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient(() -> KEY,
                        URI.create("http://user:pw@127.0.0.1:18080/chat/completions")),
                "userinfo 不得出现在端点里");
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient(() -> KEY,
                        URI.create("http://127.0.0.1:18080/chat/completions?x=1")),
                "query 不得出现在端点里");
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient(() -> KEY,
                        URI.create("http://127.0.0.1:18080/chat/completions#frag")),
                "fragment 不得出现在端点里");
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient(() -> KEY,
                        URI.create("https://api.deepseek.com.evil.test/chat/completions")),
                "非官方主机不得作为测试端点");
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekClient(() -> KEY,
                        URI.create("http://192.0.2.10:18080/chat/completions")),
                "非回环地址不得作为测试端点");
    }

    // ---------------------------------------------------------------- 夹具

    /**
     * 合法基底：单 choice、index0、assistant、非空白正文、stop、完整 usage。
     *
     * @return 可继续单字段修改的响应对象
     */
    private static ObjectNode baseOk() {
        ObjectNode root = JSON.createObjectNode();
        root.put("id", "chatcmpl-abc123");
        root.put("object", "chat.completion");
        root.put("created", 1_700_000_000L);
        root.put("model", "deepseek-flash");
        root.put("system_fingerprint", "fp-unknown-new-field");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", "{\"answer\":42}");
        choice.put("finish_reason", "stop");
        choice.putNull("logprobs");
        root.set("usage", baseUsage());
        return root;
    }

    /** @return 与合法基底一致的用量对象 */
    private static ObjectNode baseUsage() {
        ObjectNode usage = JSON.createObjectNode();
        usage.put("prompt_tokens", 21);
        usage.put("completion_tokens", 9);
        usage.put("total_tokens", 30);
        usage.put("prompt_cache_hit_tokens", 5);
        usage.put("prompt_cache_miss_tokens", 16);
        return usage;
    }

    private static ObjectNode baseChoice(ObjectNode root) {
        return (ObjectNode) root.withArray("choices").get(0);
    }

    private static ObjectNode baseMessage(ObjectNode root) {
        return (ObjectNode) baseChoice(root).get("message");
    }

    /**
     * 按路径生成响应：全部是从 {@link #baseOk()} 出发的单字段破坏。
     *
     * @param path 端点
     * @return 响应对象；特殊端点返回 null
     */
    private static ObjectNode responseFor(String path) {
        ObjectNode root;
        switch (path) {
            case "/ok":
            case "/unknownmeta":
                return baseOk();
            case "/blank":
                root = baseOk();
                baseMessage(root).put("content", "   \t ");
                return root;
            case "/length":
                root = baseOk();
                baseChoice(root).put("finish_reason", "length");
                return root;
            case "/filtered":
                root = baseOk();
                baseChoice(root).put("finish_reason", "content_filter");
                return root;
            case "/tools":
                root = baseOk();
                baseMessage(root).putNull("content");
                baseMessage(root).putArray("tool_calls").addObject().put("id", "c1");
                baseChoice(root).put("finish_reason", "tool_calls");
                return root;
            case "/nousage":
                root = baseOk();
                root.remove("usage");
                return root;
            case "/frac":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("prompt_tokens", 21.5);
                return root;
            case "/usagestr":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("prompt_tokens", "21");
                return root;
            case "/usagebool":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("completion_tokens", true);
                return root;
            case "/usagebig":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("total_tokens", 4_294_967_296L);
                return root;
            case "/total":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("total_tokens", 31);
                return root;
            case "/cachepartial":
                root = baseOk();
                ((ObjectNode) root.get("usage")).remove("prompt_cache_miss_tokens");
                return root;
            case "/cachenone":
                root = baseOk();
                ObjectNode noneUsage = (ObjectNode) root.get("usage");
                noneUsage.remove("prompt_cache_hit_tokens");
                noneUsage.remove("prompt_cache_miss_tokens");
                return root;
            case "/cachehit":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("prompt_cache_hit_tokens", 99);
                return root;
            case "/cachesum":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("prompt_cache_hit_tokens", 7);
                return root;
            case "/cachestring":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("prompt_cache_hit_tokens", "5");
                return root;
            case "/cachefloat":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("prompt_cache_hit_tokens", 5.5);
                return root;
            case "/cachebool":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("prompt_cache_miss_tokens", true);
                return root;
            case "/cachebig":
                root = baseOk();
                ((ObjectNode) root.get("usage")).put("prompt_cache_hit_tokens", 4_294_967_296L);
                return root;
            case "/two":
                root = baseOk();
                ArrayNode choices = (ArrayNode) root.get("choices");
                ObjectNode second = choices.addObject();
                second.put("index", 1);
                ObjectNode secondMessage = second.putObject("message");
                secondMessage.put("role", "assistant");
                secondMessage.put("content", "{}");
                second.put("finish_reason", "stop");
                return root;
            case "/index1":
                root = baseOk();
                baseChoice(root).put("index", 1);
                return root;
            case "/indexbig":
                root = baseOk();
                baseChoice(root).put("index", 4_294_967_296L);
                return root;
            case "/indexfloat":
                root = baseOk();
                baseChoice(root).put("index", 0.0);
                return root;
            case "/role":
                root = baseOk();
                baseMessage(root).put("role", "user");
                return root;
            case "/weird":
                root = baseOk();
                baseChoice(root).put("finish_reason", "weird_reason");
                return root;
            case "/numcontent":
                root = baseOk();
                baseMessage(root).put("content", 42);
                return root;
            case "/secretid":
                root = baseOk();
                root.put("id", KEY);
                return root;
            case "/secretmodel":
                root = baseOk();
                root.put("model", KEY);
                return root;
            case "/secretcontent":
                root = baseOk();
                baseMessage(root).put("content", "leak " + KEY + " here");
                return root;
            default:
                return null;
        }
    }

    /**
     * 语法级反例：先序列化合法基底，再只做一处文本级破坏（重复键 / 追加尾随内容）。
     *
     * @param path 端点
     * @return 原始响应文本；非语法反例返回 null
     */
    private static String rawFor(String path) throws IOException {
        String legal = JSON.writeValueAsString(baseOk());
        return switch (path) {
            case "/dup" -> legal.replace("\"model\":\"deepseek-flash\"",
                    "\"model\":\"deepseek-flash\",\"model\":\"deepseek-flash\"");
            case "/trailing" -> legal + " {}";
            default -> null;
        };
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        authHeaders.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>())
                .add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        bodies.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>())
                .add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        switch (path) {
            case "/redirect" -> {
                exchange.getResponseHeaders().add("Location", base + "/target");
                reply(exchange, 302, "{}");
            }
            case "/target" -> reply(exchange, 200, JSON.writeValueAsString(baseOk()));
            case "/auth" -> reply(exchange, 401,
                    "{\"error\":{\"message\":\"invalid_api_key\"}}");
            case "/limited" -> reply(exchange, 429,
                    "{\"error\":{\"message\":\"slow down\"},\"usage\":"
                            + JSON.writeValueAsString(baseUsage()) + "}");
            case "/server" -> reply(exchange, 503,
                    "{\"error\":{\"message\":\"upstream\"},\"usage\":"
                            + JSON.writeValueAsString(baseUsage()) + "}");
            case "/big" -> streamLarge(exchange, 2 * 1024 * 1024);
            case "/stall" -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("{\"id\":\"slow\"".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    sleep(3_000L);
                }
            }
            case "/slowheaders" -> {
                sleep(2_500L);
                reply(exchange, 200, JSON.writeValueAsString(baseOk()));
            }
            default -> {
                String raw = rawFor(path);
                if (raw != null) {
                    reply(exchange, 200, raw);
                } else {
                    ObjectNode node = responseFor(path);
                    if (node == null) {
                        reply(exchange, 404, "{}");
                    } else {
                        reply(exchange, 200, JSON.writeValueAsString(node));
                    }
                }
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void streamLarge(HttpExchange exchange, int total) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            byte[] chunk = new byte[64 * 1024];
            java.util.Arrays.fill(chunk, (byte) 'a');
            for (int sent = 0; sent < total; sent += chunk.length) {
                out.write(chunk);
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------------------------------------------------------------- 工具

    private DeepSeekClient client(String path, String key) {
        return new DeepSeekClient(() -> key, URI.create(base + path));
    }

    private static DeepSeekClient.Request request() {
        return new DeepSeekClient.Request("deepseek-flash", "只输出 JSON。",
                "{\"question\":\"1+1\"}", 512, 2_000L);
    }

    /** 合法对照：同一基底必须判定为完整，证明后续反例不是夹具语法问题。 */
    private void controlIsValid() throws Exception {
        assertEquals(DeepSeekClient.Outcome.COMPLETE,
                client("/ok", KEY).send(request()).outcome());
    }

    /** 反例：先跑合法对照，再断言该路径协议失败。 */
    private void assertProtocol(String path) throws Exception {
        controlIsValid();
        assertEquals(DeepSeekClient.Failure.Code.PROTOCOL, failure(path, KEY).code(), path);
    }

    private DeepSeekClient.Failure failure(String path, String key) throws Exception {
        return assertThrows(DeepSeekClient.Failure.class, () -> client(path, key).send(request()));
    }

    private int hits(String path) {
        AtomicInteger counter = hits.get(path);
        return counter == null ? 0 : counter.get();
    }

    private List<String> authHeaders(String path) {
        return authHeaders.getOrDefault(path, List.of());
    }

    private List<String> bodies(String path) {
        return bodies.getOrDefault(path, List.of());
    }
}
