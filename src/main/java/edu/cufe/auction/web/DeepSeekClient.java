package edu.cufe.auction.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * DeepSeek Chat Completions 最小适配器（非流式、单次请求、不重试）。
 *
 * <p>安全边界：凭据只在构造时以 {@link Supplier} 形式注入并只在请求头里使用；
 * {@link Request}/{@link Result}/{@link Failure}/{@code toString()} 都不包含凭据、
 * 输入正文或输出正文。失败时不回显响应体、响应头或 URL。</p>
 *
 * <p>协议边界（按 2026-11-07 官方文档）：POST /chat/completions，
 * {@code messages} 只含 system 与 user，{@code stream=false}，
 * {@code thinking={type:disabled}}（默认会思考），
 * {@code response_format={type:json_object}}，{@code max_tokens} 只限制输出。</p>
 *
 * <p>本类不接触 WorldRuntime/DecisionModel，也不解释 execute 动作。</p>
 */
public final class DeepSeekClient {

    /** 生产端点（固定，不可配置）。 */
    public static final URI PRODUCTION_ENDPOINT =
            URI.create("https://api.deepseek.com/chat/completions");
    /** 允许的模型。 */
    public static final String MODEL_FLASH = "deepseek-flash";
    /** 允许的推理模型。 */
    public static final String MODEL_PRO = "deepseek-v4-pro";
    /** 请求体上限 64 KiB。 */
    public static final int MAX_REQUEST_BYTES = 64 * 1024;
    /** 响应体上限 1 MiB。 */
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    /** 输出 token 下限。 */
    public static final int MIN_OUTPUT_TOKENS = 128;
    /** 输出 token 上限。 */
    public static final int MAX_OUTPUT_TOKENS = 8192;
    /** 超时上限（毫秒）。 */
    public static final long MAX_TIMEOUT_MILLIS = 60_000L;

    /** 凭据形状：与 KeySlots 一致，且在构造请求前固定校验。 */
    private static final java.util.regex.Pattern KEY_SHAPE =
            java.util.regex.Pattern.compile("sk-[A-Za-z0-9_-]{16,128}");

    private final Supplier<String> keySupplier;
    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json;

    /**
     * 生产构造：固定官方端点，绝不跟随重定向。
     *
     * @param keySupplier 返回当前凭据（只在请求头使用，不做任何缓存或日志）
     */
    public DeepSeekClient(Supplier<String> keySupplier) {
        this(keySupplier, PRODUCTION_ENDPOINT);
    }

    /**
     * 同包测试构造：只允许回环 http 端点。
     *
     * @param keySupplier 凭据来源
     * @param endpoint    回环地址（http://127.0.0.1:port/... 或 http://localhost:port/...）
     */
    DeepSeekClient(Supplier<String> keySupplier, URI endpoint) {
        Objects.requireNonNull(keySupplier, "凭据来源");
        Objects.requireNonNull(endpoint, "端点");
        this.keySupplier = keySupplier;
        this.endpoint = requireEndpoint(endpoint);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.json = strictMapper();
    }

    // ---------------------------------------------------------------- 类型

    /** 一次请求的输入（toString 只给形状，不含正文）。 */
    public record Request(String model, String systemText, String userText,
                          int maxOutputTokens, long timeoutMillis) {

        public Request {
            if (!MODEL_FLASH.equals(model) && !MODEL_PRO.equals(model)) {
                throw new IllegalArgumentException("模型不在允许列表");
            }
            requireText(systemText, "system 文本");
            requireText(userText, "user 文本");
            if (maxOutputTokens < MIN_OUTPUT_TOKENS || maxOutputTokens > MAX_OUTPUT_TOKENS) {
                throw new IllegalArgumentException("输出 token 上限超出范围");
            }
            if (timeoutMillis <= 0 || timeoutMillis > MAX_TIMEOUT_MILLIS) {
                throw new IllegalArgumentException("超时必须是正数且不超过 60 秒");
            }
        }

        /** @return 不含正文与凭据的摘要 */
        @Override
        public String toString() {
            return "Request[model=" + model + ", systemChars=" + systemText.length()
                    + ", userChars=" + userText.length() + ", maxOutputTokens=" + maxOutputTokens
                    + ", timeoutMillis=" + timeoutMillis + "]";
        }
    }

    /** 用量；缓存命中/未命中可能缺失，缺失为 null，绝不填 0。 */
    public record Usage(int promptTokens, int completionTokens, int totalTokens,
                        Integer cacheHitTokens, Integer cacheMissTokens) {

        public Usage {
            if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
                throw new IllegalArgumentException("用量不能为负");
            }
            if (totalTokens != promptTokens + completionTokens) {
                throw new IllegalArgumentException("总量必须等于输入加输出");
            }
            if (cacheHitTokens != null && cacheHitTokens < 0) {
                throw new IllegalArgumentException("缓存命中不能为负");
            }
            if (cacheMissTokens != null && cacheMissTokens < 0) {
                throw new IllegalArgumentException("缓存未命中不能为负");
            }
            if (cacheHitTokens != null && cacheHitTokens > promptTokens) {
                throw new IllegalArgumentException("缓存命中不能超过输入");
            }
            if (cacheMissTokens != null && cacheMissTokens > promptTokens) {
                throw new IllegalArgumentException("缓存未命中不能超过输入");
            }
            if (cacheHitTokens != null && cacheMissTokens != null
                    && cacheHitTokens + cacheMissTokens != promptTokens) {
                throw new IllegalArgumentException("缓存命中与未命中之和必须等于输入");
            }
        }

        @Override
        public String toString() {
            return "Usage[prompt=" + promptTokens + ", completion=" + completionTokens
                    + ", total=" + totalTokens + ", cacheHit="
                    + (cacheHitTokens == null ? "unknown" : cacheHitTokens) + ", cacheMiss="
                    + (cacheMissTokens == null ? "unknown" : cacheMissTokens) + "]";
        }
    }

    /** 效果分类：只有 STOP 且内容非空才是可用结果。 */
    public enum Outcome {
        /** finish_reason=stop 且 content 非空。 */
        COMPLETE,
        /** finish_reason=length：内容被截断，不采用。 */
        LENGTH,
        /** finish_reason=content_filter。 */
        FILTERED,
        /** content 缺失或为空。 */
        EMPTY,
        /** 返回了 tool_calls。 */
        TOOL_CALLS
    }

    /**
     * 结果；只有 {@link Outcome#COMPLETE} 时 content 非空。
     *
     * @param outcome   效果分类
     * @param content   正文；非 COMPLETE 时为 null
     * @param usage     用量；协议未给时为 null（不伪造 0）
     * @param requestId 提供方请求标识（有界，已过滤可疑字符）
     * @param model     提供方回报的模型名（有界）
     */
    public record Result(Outcome outcome, String content, Usage usage, String requestId,
                         String model) {

        public Result {
            Objects.requireNonNull(outcome, "效果");
            if (outcome == Outcome.COMPLETE) {
                if (content == null || content.isBlank()) {
                    throw new IllegalArgumentException("完整结果必须有非空白内容");
                }
            } else if (content != null) {
                throw new IllegalArgumentException("非完整结果不得携带内容");
            }
        }

        /**
         * @return 不含正文与凭据的摘要；提供方可控的 id/model 只报告是否存在
         */
        @Override
        public String toString() {
            return "Result[outcome=" + outcome + ", contentChars="
                    + (content == null ? 0 : content.length()) + ", usage=" + usage
                    + ", requestId=" + (requestId == null ? "none" : "present")
                    + ", model=" + (model == null ? "none" : "present") + "]";
        }
    }

    /** 固定失败分类。 */
    public static final class Failure extends Exception {

        /** 固定失败码。 */
        public enum Code {
            /** 401/403。 */
            AUTH,
            /** 429。 */
            RATE_LIMITED,
            /** 5xx。 */
            SERVICE_UNAVAILABLE,
            /** 其它 4xx（含 3xx 未跟随）。 */
            REQUEST_REJECTED,
            /** 超时（含总期限与连接）。 */
            TIMEOUT,
            /** 网络/IO 失败。 */
            NETWORK,
            /** 响应不符合协议（含超限、重复键、类型错误）。 */
            PROTOCOL
        }

        private final Code code;
        private final Usage usage;

        Failure(Code code, String message, Usage usage) {
            super(message == null ? code.name() : message);
            this.code = code;
            this.usage = usage;
        }

        /** @return 固定失败码 */
        public Code code() {
            return code;
        }

        /** @return 错误响应里已知的用量；未知为 null */
        public Usage usageOrNull() {
            return usage;
        }

        @Override
        public String toString() {
            return "Failure[code=" + code + ", usage=" + usage + "]";
        }
    }

    // ---------------------------------------------------------------- 调用

    /**
     * 发送一次请求；不重试、不跟随重定向。
     *
     * @param request 请求
     * @return 结果（可能是不完整效果，携带已知 usage）
     * @throws Failure 失败（固定分类）
     */
    public Result send(Request request) throws Failure {
        Objects.requireNonNull(request, "请求");
        String key;
        try {
            key = keySupplier.get();
        } catch (RuntimeException supplierFailure) {
            // 凭据来源自身故障也归为认证失败，且不携带 cause（避免把内部细节或凭据带出）
            throw new Failure(Failure.Code.AUTH, "凭据不可用", null);
        }
        if (key == null || !KEY_SHAPE.matcher(key).matches()) {
            // 形状不合法的值（含 CR/LF、空白、非 sk- 前缀）在构造请求前就拒绝，
            // 不让 HttpRequest 抛 IllegalArgumentException 把头部内容回显出去
            throw new Failure(Failure.Code.AUTH, "凭据不可用", null);
        }
        byte[] body = requestBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(request.timeoutMillis()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        CompletableFuture<HttpResponse<byte[]>> future =
                http.sendAsync(httpRequest, info -> new BoundedBody(MAX_RESPONSE_BYTES));
        HttpResponse<byte[]> response;
        try {
            response = future.get(request.timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            future.cancel(true);
            throw new Failure(Failure.Code.TIMEOUT, "请求超时", null);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure(Failure.Code.NETWORK, "请求被中断", null);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException) {
                throw new Failure(Failure.Code.TIMEOUT, "请求超时", null);
            }
            if (cause instanceof BodyTooLarge) {
                throw new Failure(Failure.Code.PROTOCOL, "响应超过上限", null);
            }
            throw new Failure(Failure.Code.NETWORK, "网络请求失败", null);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new Failure(Failure.Code.AUTH, "认证失败", null);
        }
        if (status == 429) {
            throw new Failure(Failure.Code.RATE_LIMITED, "被限流", usageOrNull(response));
        }
        if (status >= 500) {
            throw new Failure(Failure.Code.SERVICE_UNAVAILABLE, "提供方服务失败",
                    usageOrNull(response));
        }
        if (status >= 300) {
            throw new Failure(Failure.Code.REQUEST_REJECTED, "请求被拒绝", usageOrNull(response));
        }
        if (status != 200) {
            throw new Failure(Failure.Code.REQUEST_REJECTED, "请求被拒绝", usageOrNull(response));
        }
        return parse(response.body(), key);
    }

    // ---------------------------------------------------------------- 内部

    private byte[] requestBody(Request request) throws Failure {
        var root = json.createObjectNode();
        root.put("model", request.model());
        var messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemText());
        messages.addObject().put("role", "user").put("content", request.userText());
        root.put("stream", false);
        root.putObject("thinking").put("type", "disabled");
        root.putObject("response_format").put("type", "json_object");
        root.put("max_tokens", request.maxOutputTokens());
        byte[] body;
        try {
            body = json.writeValueAsBytes(root);
        } catch (IOException impossible) {
            throw new Failure(Failure.Code.PROTOCOL, "请求无法序列化", null);
        }
        if (body.length > MAX_REQUEST_BYTES) {
            throw new Failure(Failure.Code.REQUEST_REJECTED, "请求超过 64 KiB", null);
        }
        return body;
    }

    private Result parse(byte[] payload, String key) throws Failure {
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (IOException malformed) {
            throw new Failure(Failure.Code.PROTOCOL, "响应不是合法 JSON", null);
        }
        if (root == null || !root.isObject()) {
            throw new Failure(Failure.Code.PROTOCOL, "响应根不是对象", null);
        }
        Usage usage;
        try {
            usage = readUsage(root.get("usage"));
        } catch (IllegalArgumentException invalid) {
            throw new Failure(Failure.Code.PROTOCOL, "用量字段不合法", null);
        }
        if (usage == null) {
            throw new Failure(Failure.Code.PROTOCOL, "响应缺少用量", null);
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() != 1) {
            throw new Failure(Failure.Code.PROTOCOL, "choices 必须恰好一条", usage);
        }
        JsonNode choice = choices.get(0);
        if (!choice.isObject() || !isZero(choice.get("index"))) {
            throw new Failure(Failure.Code.PROTOCOL, "choice 结构不合法", usage);
        }
        JsonNode message = choice.get("message");
        if (message == null || !message.isObject()) {
            throw new Failure(Failure.Code.PROTOCOL, "缺少 message", usage);
        }
        JsonNode role = message.get("role");
        if (role == null || !role.isTextual() || !"assistant".equals(role.textValue())) {
            throw new Failure(Failure.Code.PROTOCOL, "message.role 不是 assistant", usage);
        }
        if (message.has("tool_calls") && !message.get("tool_calls").isNull()) {
            return new Result(Outcome.TOOL_CALLS, null, usage,
                    requestIdOf(root, key), modelOf(root, key));
        }
        JsonNode content = message.get("content");
        String text = null;
        if (content != null && content.isTextual()) {
            text = content.textValue();
        } else if (content != null && !content.isNull()) {
            throw new Failure(Failure.Code.PROTOCOL, "content 类型不合法", usage);
        }
        JsonNode finish = choice.get("finish_reason");
        String reason = finish == null || finish.isNull() ? null
                : finish.isTextual() ? finish.textValue() : "\u0000";
        if ("\u0000".equals(reason)) {
            throw new Failure(Failure.Code.PROTOCOL, "finish_reason 类型不合法", usage);
        }
        if ("length".equals(reason)) {
            return new Result(Outcome.LENGTH, null, usage, requestIdOf(root, key), modelOf(root, key));
        }
        if ("content_filter".equals(reason)) {
            return new Result(Outcome.FILTERED, null, usage, requestIdOf(root, key), modelOf(root, key));
        }
        if (reason == null) {
            return new Result(Outcome.EMPTY, null, usage, requestIdOf(root, key), modelOf(root, key));
        }
        if (!"stop".equals(reason)) {
            throw new Failure(Failure.Code.PROTOCOL, "未知的 finish_reason", usage);
        }
        if (text == null || text.isBlank()) {
            return new Result(Outcome.EMPTY, null, usage, requestIdOf(root, key), modelOf(root, key));
        }
        if (text.contains(key)) {
            // 已知密钥出现在正文里：整体拒绝，避免把秘密送进后续世界归档
            throw new Failure(Failure.Code.PROTOCOL, "响应包含凭据", usage);
        }
        return new Result(Outcome.COMPLETE, text, usage, requestIdOf(root, key), modelOf(root, key));
    }

    /** 错误响应里尽力读取用量；任何不合法都视为未知。 */
    private Usage usageOrNull(HttpResponse<byte[]> response) {
        try {
            JsonNode root = json.readTree(response.body());
            return root != null && root.isObject() ? readUsage(root.get("usage")) : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Usage readUsage(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("用量不是对象");
        }
        Integer prompt = requiredIntField(node.get("prompt_tokens"));
        Integer completion = requiredIntField(node.get("completion_tokens"));
        Integer total = requiredIntField(node.get("total_tokens"));
        Integer hit = optionalIntField(node.get("prompt_cache_hit_tokens"));
        Integer miss = optionalIntField(node.get("prompt_cache_miss_tokens"));
        return new Usage(prompt, completion, total, hit, miss);
    }

    /** 必需整数字段：缺失或类型不对都算不合法（不能当成 0 或缺失）。 */
    private static Integer requiredIntField(JsonNode node) {
        Integer value = intField(node);
        if (value == null) {
            throw new IllegalArgumentException("用量字段不是合法整数");
        }
        return value;
    }

    /**
     * 可选缓存计数：字段缺失或显式 null 才算未知；
     * 存在但不是 int 范围整数时抛不合法，绝不静默当作缺失。
     */
    private static Integer optionalIntField(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Integer value = intField(node);
        if (value == null) {
            throw new IllegalArgumentException("缓存计数字段不是合法整数");
        }
        return value;
    }

    private static Integer intField(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        return node.intValue();
    }

    private static boolean isZero(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt()
                && node.intValue() == 0;
    }

    private static String requestIdOf(JsonNode root, String key) {
        return bounded(root.get("id"), key);
    }

    private static String modelOf(JsonNode root, String key) {
        return bounded(root.get("model"), key);
    }

    /**
     * 只保留短、可打印、无空白且不含本次凭据的标识；否则丢弃为 null。
     * 提供方对 id/model 有完全控制权，这里不信任其"元数据"性质。
     */
    private static String bounded(JsonNode node, String key) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.textValue();
        if (text == null || text.isEmpty() || text.length() > 64 || text.contains(key)) {
            return null;
        }
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.'
                    || ch == ':';
            if (!ok) {
                return null;
            }
        }
        return text;
    }

    private static URI requireEndpoint(URI endpoint) {
        if (endpoint.getUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            // 端点里不允许出现用户信息、查询串或片段，避免把秘密或额外参数混进请求行
            throw new IllegalArgumentException("端点不得包含用户信息、查询串或片段");
        }
        String scheme = endpoint.getScheme();
        String host = endpoint.getHost();
        String path = endpoint.getPath();
        if (endpoint.equals(PRODUCTION_ENDPOINT)) {
            return endpoint;
        }
        boolean loopback = "http".equalsIgnoreCase(scheme) && host != null
                && ("127.0.0.1".equals(host) || "localhost".equals(host))
                && path != null && path.startsWith("/");
        if (!loopback) {
            throw new IllegalArgumentException("测试端点只允许回环 http 地址");
        }
        return endpoint;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (value.length() > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException(label + "过长");
        }
    }

    private static ObjectMapper strictMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        return new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 有界响应体订阅者：超过上限立即取消订阅并失败，绝不无限缓冲。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int total;

        BoundedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription next) {
            this.subscription = next;
            next.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                int size = item.remaining();
                if (total + size > limit) {
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    body.completeExceptionally(new BodyTooLarge());
                    return;
                }
                byte[] chunk = new byte[size];
                item.get(chunk);
                buffer.write(chunk, 0, size);
                total += size;
            }
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(buffer.toByteArray());
        }
    }

    /** 响应超限（内部使用）。 */
    private static final class BodyTooLarge extends IOException {
        BodyTooLarge() {
            super("响应超过上限");
        }
    }
}
