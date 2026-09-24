package edu.cufe.auction.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 JDK 11+ {@link HttpClient} 的 OpenAI 兼容 Chat Completions 客户端（默认指向 DeepSeek）。
 *
 * <p>安全机制（对应规格 5.2 “Safety Mechanisms”）：</p>
 * <ul>
 *   <li><b>限流</b>：两次请求之间至少间隔 {@code minIntervalMillis}；</li>
 *   <li><b>超时</b>：连接/请求超时可控，避免单次调用挂死模拟；</li>
 *   <li><b>强制 JSON</b>：请求体带 {@code response_format=json_object}，降低解析失败率。</li>
 * </ul>
 */
public final class DeepSeekClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final long minIntervalMillis;
    private final HttpClient httpClient;
    private final AtomicLong lastCallAt = new AtomicLong(0L);

    /**
     * 构造客户端。
     *
     * @param endpoint          Chat Completions 地址
     * @param apiKey            API Key（从环境变量读取，勿写入仓库）
     * @param model             模型名
     * @param minIntervalMillis 最小请求间隔（毫秒）
     * @param timeoutMillis     请求超时（毫秒）
     */
    public DeepSeekClient(String endpoint, String apiKey, String model,
                          long minIntervalMillis, long timeoutMillis) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("LLM API Key 为空：请设置环境变量 LLM_API_KEY");
        }
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.minIntervalMillis = minIntervalMillis;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
        this.requestTimeout = Duration.ofMillis(timeoutMillis);
    }

    private final Duration requestTimeout;

    @Override
    public String complete(String systemPrompt, String userPrompt) throws IOException {
        throttle();

        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.3);
        root.put("max_tokens", 300);
        root.put("stream", false);
        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_object");
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(root), java.nio.charset.StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException ex) {
            throw new IOException("构造 LLM 请求失败：" + ex.getMessage(), ex);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM 请求被中断", ex);
        }
        if (response.statusCode() != 200) {
            throw new IOException("LLM 返回 HTTP " + response.statusCode() + "：" + brief(response.body()));
        }
        try {
            JsonNode node = MAPPER.readTree(response.body());
            JsonNode choices = node.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IOException("LLM 响应缺少 choices：" + brief(response.body()));
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IOException("LLM 返回内容为空：" + brief(response.body()));
            }
            return content;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("解析 LLM 响应失败：" + ex.getMessage(), ex);
        }
    }

    private void throttle() {
        long now = System.currentTimeMillis();
        long last = lastCallAt.get();
        long wait = minIntervalMillis - (now - last);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallAt.set(System.currentTimeMillis());
    }

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    @Override
    public String toString() {
        return String.format("DeepSeekClient[model=%s endpoint=%s 限流=%dms]",
                model, endpoint, minIntervalMillis);
    }
}
