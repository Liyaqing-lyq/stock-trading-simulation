package edu.cufe.auction.web;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.ValuationModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 本机回环 Web 传输层：只绑定 127.0.0.1，会话/同源/方法/内容类型/大小全部在此校验。
 *
 * <p>JSON 入口是严格的：拒绝重复键、尾随内容、null/非对象、未知字段；数字时间与位置只接受
 * 规范十进制字符串或严格 JSON 整数，浮点与溢出直接 400；{@code confirm} 必须是布尔 true。</p>
 *
 * <p>本类不持有任何世界状态；所有世界读写都投递给 {@link WorldWebHost} 的 writer 线程。</p>
 */
public final class WorldWebServer implements AutoCloseable {

    /** 单请求体上限 16 KiB。 */
    public static final int MAX_BODY_BYTES = 16 * 1024;

    private static final String COOKIE = "world_session";
    private static final String REQUEST_MARKER = "X-World-Request";
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; frame-ancestors 'none'";
    private static final Set<String> LOGIN_FIELDS = Set.of("bootstrap");
    private static final Set<String> PERSON_FIELDS = Set.of("personId");
    /** 管理端详情页：只读 GET，一个页面一个请求。 */
    private static final Set<String> DETAIL_PATHS = Set.of("/api/admin/company",
            "/api/admin/people", "/api/admin/information", "/api/admin/performance");
    private static final Set<String> LIVE_START_FIELDS = Set.of("expectedPosition", "expectedRevision", "persons");
    private static final Set<String> LIVE_CONTROL_FIELDS = Set.of("expectedPosition");
    private static final Set<String> LIVE_ACTIONS = Set.of("/api/admin/live/start", "/api/admin/live/stop",
            "/api/admin/live/pause", "/api/admin/live/resume");
    private static final Set<String> ADVANCE_FIELDS = Set.of("expectedPosition", "targetMillis");
    private static final Set<String> REVEAL_FIELDS = Set.of("confirm");
    private static final Set<String> ROUTE_REPLACE_FIELDS = Set.of("expectedRevision", "routes");
    private static final Set<String> ROUTE_FIELDS = Set.of("scene", "slotId", "model", "enabled",
            "maxCalls", "maxTotalTokens", "maxOutputTokens", "maxConcurrency");

    /** 严格读取器：重复键、尾随内容、浮点精度全部不放过。 */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final WorldWebHost host;
    private final KeySlots keys;
    private final SceneRoutes routes;
    private final int requestedPort;
    private final Path staticDir;
    private final Consumer<String> consoleSink;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<String> bootstrap;
    private final WebSessions sessions = new WebSessions();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "world-web-http");
        thread.setDaemon(true);
        return thread;
    });
    private HttpServer server;
    private int port = -1;

    /**
     * 构造（尚未监听），静态资源取自 classpath /web/。
     *
     * @param host      唯一 writer 宿主
     * @param keys      凭据槽位
     * @param routes    五场景路由配置
     * @param port      端口；0 表示由系统分配
     * @param bootstrap 本机管理员一次性引导凭据（不写日志、不进 DTO）
     */
    public WorldWebServer(WorldWebHost host, KeySlots keys, SceneRoutes routes, int port,
                          String bootstrap) {
        this(host, keys, routes, port, bootstrap, null, System.out::println);
    }

    /**
     * 构造（尚未监听）。
     *
     * @param host        唯一 writer 宿主
     * @param keys        凭据槽位
     * @param routes      五场景路由配置
     * @param port        端口；0 表示由系统分配
     * @param bootstrap   本机管理员一次性引导凭据（不写日志、不进 DTO）
     * @param staticDir   静态资源目录；null 表示从 classpath /web/ 读取
     * @param consoleSink 本机控制台输出（用于轮换后的新引导凭据）
     */
    public WorldWebServer(WorldWebHost host, KeySlots keys, SceneRoutes routes, int port,
                          String bootstrap, Path staticDir, Consumer<String> consoleSink) {
        if (host == null || keys == null || routes == null) {
            throw new IllegalArgumentException("Web 服务参数不合法");
        }
        if (bootstrap == null || bootstrap.isBlank()) {
            throw new IllegalArgumentException("引导凭据不能为空");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("端口不合法");
        }
        this.host = host;
        this.keys = keys;
        this.routes = routes;
        this.requestedPort = port;
        this.staticDir = staticDir;
        this.consoleSink = consoleSink == null ? System.out::println : consoleSink;
        this.bootstrap = new AtomicReference<>(bootstrap);
    }

    /**
     * 开始监听 127.0.0.1。
     *
     * @throws IOException 绑定失败
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), requestedPort), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
        port = server.getAddress().getPort();
    }

    /** @return 实际监听端口 */
    public int port() {
        return port;
    }

    /** @return 本机基地址（不含路径） */
    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** 停止监听；不关闭宿主。 */
    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- 路由

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"POST".equals(method)) {
                send(exchange, 405, error("METHOD_NOT_ALLOWED", "只支持 GET 与 POST"));
                return;
            }
            String hostHeader = exchange.getRequestHeaders().getFirst("Host");
            if (!hostAllowed(hostHeader)) {
                send(exchange, 403, error("HOST_NOT_ALLOWED", "只接受本机地址访问"));
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals("http://" + hostHeader)) {
                send(exchange, 403, error("ORIGIN_NOT_ALLOWED", "跨源请求被拒绝"));
                return;
            }
            if ("POST".equals(method)) {
                if (origin == null
                        || !"1".equals(exchange.getRequestHeaders().getFirst(REQUEST_MARKER))) {
                    send(exchange, 403, error("REQUEST_MARKER_REQUIRED", "缺少本机请求标记"));
                    return;
                }
            }
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && serveStatic(exchange, path)) {
                return;
            }
            if ("/api/session".equals(path) && "POST".equals(method)) {
                login(exchange);
                return;
            }
            if (!knownRoute(path, method)) {
                send(exchange, 404, error("NOT_FOUND", "没有这个接口"));
                return;
            }
            Optional<WebSessions.Session> session = resolveSession(exchange);
            if (session.isEmpty()) {
                send(exchange, 401, error("UNAUTHENTICATED", "会话不存在或已失效，请重新登录"));
                return;
            }
            if ("/api/session".equals(path) && "GET".equals(method)) {
                currentSession(exchange, session.get());
                return;
            }
            if ("/api/session/logout".equals(path) && "POST".equals(method)) {
                logout(exchange, session.get());
                return;
            }
            if ("/api/session/person".equals(path) && "POST".equals(method)) {
                switchPerson(exchange, session.get());
                return;
            }
            if ("/api/admin/view".equals(path) && "GET".equals(method)) {
                requireAdmin(exchange, session.get(), () -> adminView(exchange));
                return;
            }
            if (DETAIL_PATHS.contains(path) && "GET".equals(method)) {
                requireAdmin(exchange, session.get(), () -> adminDetails(exchange, path));
                return;
            }
            if ("/api/admin/keys".equals(path) && "GET".equals(method)) {
                requireAdmin(exchange, session.get(), () -> listKeys(exchange));
                return;
            }
            if ("/api/admin/routes".equals(path) && "GET".equals(method)) {
                requireAdmin(exchange, session.get(), () -> readRoutes(exchange));
                return;
            }
            if ("/api/admin/routes".equals(path) && "POST".equals(method)) {
                requireAdmin(exchange, session.get(), () -> replaceRoutes(exchange));
                return;
            }
            if ("/api/admin/live".equals(path) && "GET".equals(method)) {
                requireAdmin(exchange, session.get(), () -> send(exchange, 200,
                        mapper.writeValueAsString(liveState())));
                return;
            }
            if (LIVE_ACTIONS.contains(path) && "POST".equals(method)) {
                requireAdmin(exchange, session.get(), () -> liveAction(exchange, path));
                return;
            }
            if ("/api/admin/advance".equals(path) && "POST".equals(method)) {
                requireAdmin(exchange, session.get(), () -> advance(exchange));
                return;
            }
            if (path.startsWith("/api/admin/keys/") && path.endsWith("/reveal")
                    && "POST".equals(method)) {
                String slotId = path.substring("/api/admin/keys/".length(),
                        path.length() - "/reveal".length());
                requireAdmin(exchange, session.get(), () -> reveal(exchange, slotId));
                return;
            }
            if ("/api/me/view".equals(path) && "GET".equals(method)) {
                requirePerson(exchange, session.get(), () -> personView(exchange, session.get()));
                return;
            }
            send(exchange, 404, error("NOT_FOUND", "没有这个接口"));
        } catch (StopHandling stop) {
            return;
        } catch (WorldIoFailure worldIo) {
            send(exchange, 503, error("WORLD_IO", "本地账本读取失败，请稍后重试"));
        } catch (WorldWebHost.BusyException busy) {
            send(exchange, 503, error("BUSY", "本地写入队列已满，请稍后重试"));
        } catch (IOException failure) {
            send(exchange, 503, error("WORLD_IO", "本地账本读取失败，请稍后重试"));
        } catch (IllegalArgumentException invalid) {
            send(exchange, 400, error("INVALID", "请求参数不合法"));
        } catch (RuntimeException failure) {
            send(exchange, 500, error("INTERNAL", "服务内部错误"));
        } finally {
            exchange.close();
        }
    }

    private boolean hostAllowed(String hostHeader) {
        if (hostHeader == null || port <= 0) {
            return false;
        }
        String lower = hostHeader.toLowerCase(Locale.ROOT);
        return lower.equals("127.0.0.1:" + port) || lower.equals("localhost:" + port);
    }

    private boolean knownRoute(String path, String method) {
        if ("/api/session".equals(path) && "GET".equals(method)) {
            return true;
        }
        if ("/api/session/logout".equals(path) && "POST".equals(method)) {
            return true;
        }
        if ("/api/session/person".equals(path) && "POST".equals(method)) {
            return true;
        }
        if ("/api/admin/view".equals(path) && "GET".equals(method)) {
            return true;
        }
        if (DETAIL_PATHS.contains(path) && "GET".equals(method)) {
            return true;
        }
        if ("/api/admin/keys".equals(path) && "GET".equals(method)) {
            return true;
        }
        if ("/api/admin/routes".equals(path)
                && ("GET".equals(method) || "POST".equals(method))) {
            return true;
        }
        if (("/api/admin/live".equals(path) && "GET".equals(method))
                || (LIVE_ACTIONS.contains(path) && "POST".equals(method))) {
            return true;
        }
        if ("/api/admin/advance".equals(path) && "POST".equals(method)) {
            return true;
        }
        if (path.startsWith("/api/admin/keys/") && path.endsWith("/reveal")
                && "POST".equals(method)) {
            return true;
        }
        return "/api/me/view".equals(path) && "GET".equals(method);
    }

    private Optional<WebSessions.Session> resolveSession(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null || headers.isEmpty()) {
            return Optional.empty();
        }
        String token = null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.startsWith(COOKIE + "=")) {
                    continue;
                }
                String value = trimmed.substring(COOKIE.length() + 1);
                if (token != null) {
                    return Optional.empty();
                }
                token = value;
            }
        }
        if (token == null || token.length() < 32) {
            return Optional.empty();
        }
        return sessions.resolve(token);
    }

    private interface BlockingAction {
        void run() throws IOException, WorldWebHost.BusyException, WorldWebHost.PositionMismatch;
    }

    private void requireAdmin(HttpExchange exchange, WebSessions.Session session,
                              BlockingAction action) throws IOException {
        if (session.role() != WebSessions.Role.ADMIN) {
            send(exchange, 403, error("FORBIDDEN", "当前身份无权访问管理员接口"));
            return;
        }
        runAction(exchange, action);
    }

    private void requirePerson(HttpExchange exchange, WebSessions.Session session,
                               BlockingAction action) throws IOException {
        if (session.role() != WebSessions.Role.PERSON) {
            send(exchange, 403, error("FORBIDDEN", "当前身份无权访问个人接口"));
            return;
        }
        runAction(exchange, action);
    }

    private void runAction(HttpExchange exchange, BlockingAction action) throws IOException {
        try {
            action.run();
        } catch (WorldWebHost.PositionMismatch mismatch) {
            send(exchange, 409, "{\"error\":\"POSITION_MISMATCH\",\"message\":\"记录已变化，请刷新\","
                    + "\"position\":\"" + mismatch.position() + "\"}");
        } catch (WorldWebHost.BusyException busy) {
            send(exchange, 503, error("BUSY", "本地写入队列已满，请稍后重试"));
        }
    }

    // ---------------------------------------------------------------- 会话

    private void login(HttpExchange exchange) throws IOException {
        JsonNode body = jsonBody(exchange, LOGIN_FIELDS);
        JsonNode node = body.get("bootstrap");
        if (node == null || !node.isTextual()) {
            send(exchange, 400, error("INVALID", "引导凭据格式不合法"));
            return;
        }
        String candidate = node.textValue();
        String expected = bootstrap.get();
        if (expected == null || candidate.isEmpty()
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8))) {
            send(exchange, 401, error("UNAUTHENTICATED", "引导凭据错误或已被使用"));
            return;
        }
        if (!bootstrap.compareAndSet(expected, null)) {
            send(exchange, 401, error("UNAUTHENTICATED", "引导凭据错误或已被使用"));
            return;
        }
        // 新的管理员登录让此前所有个人/管理员会话立即失效
        sessions.clear();
        String token = sessions.create(WebSessions.Role.ADMIN, null);
        setCookie(exchange, token);
        String next = newBootstrap();
        bootstrap.set(next);
        consoleSink.accept("本机管理员引导凭据（已轮换）：" + next);
        send(exchange, 200, "{\"role\":\"ADMIN\"}");
    }

    private void currentSession(HttpExchange exchange, WebSessions.Session session)
            throws IOException {
        if (session.role() == WebSessions.Role.ADMIN) {
            send(exchange, 200, "{\"role\":\"ADMIN\"}");
            return;
        }
        send(exchange, 200, "{\"role\":\"PERSON\",\"personId\":\""
                + escape(session.personId()) + "\"}");
    }

    private void logout(HttpExchange exchange, WebSessions.Session session) throws IOException {
        sessions.invalidate(tokenOf(exchange));
        exchange.getResponseHeaders().add("Set-Cookie",
                COOKIE + "=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/");
        send(exchange, 200, "{\"role\":\"NONE\"}");
    }

    private void switchPerson(HttpExchange exchange, WebSessions.Session session)
            throws IOException, WorldWebHost.BusyException {
        if (session.role() != WebSessions.Role.ADMIN) {
            send(exchange, 403, error("FORBIDDEN", "只有管理员可以切换视角"));
            return;
        }
        JsonNode body = jsonBody(exchange, PERSON_FIELDS);
        JsonNode node = body.get("personId");
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            send(exchange, 400, error("INVALID", "人物标识不合法"));
            return;
        }
        String personId = node.textValue();
        try {
            host.roleView(personId);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            send(exchange, 404, error("PERSON_NOT_VISIBLE", "该人物在当前世界不可见"));
            return;
        } catch (Exception failure) {
            send(exchange, 503, error("WORLD_IO", "本地账本读取失败，请稍后重试"));
            return;
        }
        // 切换视角：旧管理员与其它旧会话一律失效，只保留新的人物会话
        sessions.clear();
        String token = sessions.create(WebSessions.Role.PERSON, personId);
        setCookie(exchange, token);
        send(exchange, 200, "{\"role\":\"PERSON\",\"personId\":\"" + escape(personId) + "\"}");
    }

    // ---------------------------------------------------------------- 端点

    private void adminView(HttpExchange exchange) throws IOException,
            WorldWebHost.BusyException {
        try {
            send(exchange, 200, mapper.writeValueAsString(host.adminView()));
        } catch (WorldWebHost.BusyException busy) {
            throw busy;
        } catch (Exception failure) {
            throw new WorldIoFailure();
        }
    }

    /** 管理端详情页：一个页面一个只读请求；读取失败统一对外 503。 */
    private void adminDetails(HttpExchange exchange, String path) throws IOException,
            WorldWebHost.BusyException {
        Object view;
        try {
            view = switch (path) {
                case "/api/admin/company" -> host.companyView();
                case "/api/admin/people" -> host.peopleView();
                case "/api/admin/information" -> host.informationView();
                case "/api/admin/performance" -> host.performanceView();
                default -> throw new IllegalArgumentException("未知的管理详情页");
            };
        } catch (WorldWebHost.BusyException busy) {
            throw busy;
        } catch (Exception failure) {
            throw new WorldIoFailure();
        }
        send(exchange, 200, mapper.writeValueAsString(view));
    }

    private void listKeys(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (KeySlots.SlotView view : keys.views()) {
            slots.add(Map.of("slotId", view.slotId(), "masked", view.masked(),
                    "configured", view.configured()));
        }
        send(exchange, 200, mapper.writeValueAsString(Map.of("slots", slots)));
    }

    private void reveal(HttpExchange exchange, String slotId) throws IOException {
        JsonNode body = jsonBody(exchange, REVEAL_FIELDS);
        JsonNode confirm = body.get("confirm");
        if (confirm == null || !confirm.isBoolean() || !confirm.booleanValue()) {
            send(exchange, 400, "{\"error\":\"CONFIRM_REQUIRED\",\"message\":\"需要布尔确认\"}");
            return;
        }
        String secret;
        try {
            secret = keys.credential(slotId);
        } catch (IllegalArgumentException unknown) {
            send(exchange, 404, error("UNKNOWN_SLOT", "没有这个槽位"));
            return;
        }
        send(exchange, 200, mapper.writeValueAsString(Map.of(
                "slotId", slotId, "secret", secret)));
    }

    /** GET /api/admin/routes：固定提供方/地址、未连接状态与五场景配置。 */
    private void readRoutes(HttpExchange exchange) throws IOException, WorldWebHost.BusyException {
        send(exchange, 200, routesJson());
    }

    /** POST /api/admin/routes：全量替换，成功后才发布 revision。 */
    private void replaceRoutes(HttpExchange exchange) throws IOException, WorldWebHost.BusyException {
        JsonNode body = jsonBody(exchange, ROUTE_REPLACE_FIELDS);
        JsonNode revisionNode = body.get("expectedRevision");
        Long expected = revisionNode != null && revisionNode.isTextual()
                ? canonicalLong(revisionNode.textValue()) : null;
        JsonNode array = body.get("routes");
        if (expected == null || array == null || !array.isArray()
                || array.size() != SceneRoutes.Scene.values().length) {
            send(exchange, 400, error("INVALID", "expectedRevision 或 routes 不合法"));
            return;
        }
        List<SceneRoutes.Route> parsed = new ArrayList<>();
        for (JsonNode node : array) {
            SceneRoutes.Route route = parseRoute(node);
            if (route == null) {
                send(exchange, 400, error("INVALID", "路由字段不合法或含未允许字段"));
                return;
            }
            parsed.add(route);
        }
        try {
            if (!"SCRIPTED".equals(liveState().phase())) host.replaceRoutes(expected, parsed);
            else routes.replace(expected, parsed);
            send(exchange, 200, routesJson());
        } catch (SceneRoutes.RevisionMismatch mismatch) {
            send(exchange, 409, "{\"error\":\"REVISION_MISMATCH\","
                    + "\"message\":\"配置已被他人修改，请刷新\",\"revision\":\""
                    + mismatch.currentRevision() + "\"}");
        } catch (IOException failure) {
            send(exchange, 503, error("ROUTES_IO", "场景路由写入失败，旧配置保持不变"));
        } catch (IllegalArgumentException invalid) {
            send(exchange, 400, error("INVALID", "路由取值不合法"));
        } catch (WorldWebHost.BusyException busy) {
            throw busy;
        } catch (Exception failure) {
            throw new WorldIoFailure();
        }
    }

    private String routesJson() throws IOException, WorldWebHost.BusyException {
        SceneRoutes.Snapshot snapshot = routes.view();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("revision", Long.toString(snapshot.revision()));
        root.put("provider", SceneRoutes.PROVIDER);
        root.put("baseUrl", SceneRoutes.BASE_URL);
        root.put("connected", !"SCRIPTED".equals(liveState().phase()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SceneRoutes.Route route : snapshot.routes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scene", route.scene().id());
            row.put("sceneLabel", route.scene().label());
            row.put("slotId", route.slotId());
            row.put("model", route.model());
            row.put("enabled", route.enabled());
            row.put("maxCalls", route.maxCalls());
            row.put("maxTotalTokens", route.maxTotalTokens());
            row.put("maxOutputTokens", route.maxOutputTokens());
            row.put("maxConcurrency", route.maxConcurrency());
            rows.add(row);
        }
        root.put("routes", rows);
        return mapper.writeValueAsString(root);
    }

    /** 严格解析单条路由：只允许契约字段，类型必须精确。 */
    private static SceneRoutes.Route parseRoute(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (var names = node.fieldNames(); names.hasNext(); ) {
            if (!ROUTE_FIELDS.contains(names.next())) {
                return null;
            }
        }
        JsonNode scene = node.get("scene");
        JsonNode slot = node.get("slotId");
        JsonNode model = node.get("model");
        JsonNode enabled = node.get("enabled");
        Integer calls = intField(node.get("maxCalls"));
        Integer total = intField(node.get("maxTotalTokens"));
        Integer output = intField(node.get("maxOutputTokens"));
        Integer concurrency = intField(node.get("maxConcurrency"));
        if (scene == null || !scene.isTextual() || slot == null || !slot.isTextual()
                || model == null || !model.isTextual() || enabled == null || !enabled.isBoolean()
                || calls == null || total == null || output == null || concurrency == null) {
            return null;
        }
        try {
            return new SceneRoutes.Route(SceneRoutes.Scene.fromId(scene.textValue()),
                    slot.textValue(), model.textValue(), enabled.booleanValue(),
                    calls, total, output, concurrency);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** 只接受严格 JSON 整数且在 int 范围内。 */
    private static Integer intField(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        return node.intValue();
    }

    /** 规范十进制字符串 → long；否则 null。 */
    private static Long canonicalLong(String text) {
        if (text == null || !text.matches("(0|[1-9][0-9]*)")) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /** 读取同一writer采样的模型状态；不把自由异常文本发送给浏览器。 */
    private WorldWebHost.LiveStatus liveState() throws IOException, WorldWebHost.BusyException {
        try {
            return host.liveStatus();
        } catch (WorldWebHost.BusyException busy) {
            throw busy;
        } catch (Exception failure) {
            throw new WorldIoFailure();
        }
    }

    /** 管理员明确发起单轮或控制处理，权限与同源检查已由统一路由完成。 */
    private void liveAction(HttpExchange exchange, String path)
            throws IOException, WorldWebHost.BusyException {
        boolean start = path.endsWith("/start");
        JsonNode body = jsonBody(exchange, start ? LIVE_START_FIELDS : LIVE_CONTROL_FIELDS);
        Long position = strictLong(body.get("expectedPosition"));
        Long revision = start ? strictLong(body.get("expectedRevision")) : 0L;
        if (position == null || position < 0 || revision == null || revision < 0) {
            send(exchange, 400, error("INVALID", "记录序号与配置版本必须是非负整数"));
            return;
        }
        List<String> persons = new ArrayList<>();
        if (start) {
            JsonNode array = body.get("persons");
            if (array == null || !array.isArray() || array.size() < 1 || array.size() > 4) {
                send(exchange, 400, error("INVALID", "请选择一到四位不同人物"));
                return;
            }
            for (JsonNode person : array) {
                if (!person.isTextual() || !WorldWebHost.LIVE_ROLE_PERSONS.contains(person.textValue())
                        || persons.contains(person.textValue())) {
                    send(exchange, 400, error("INVALID", "人物不在允许列表或被重复选择"));
                    return;
                }
                persons.add(person.textValue());
            }
        }
        try {
            switch (path) {
                case "/api/admin/live/start" -> host.startLiveRound(position, revision, persons);
                case "/api/admin/live/stop" -> host.stopLiveRound(position);
                case "/api/admin/live/pause" -> host.pauseLive(position);
                case "/api/admin/live/resume" -> host.resumeLive(position);
                default -> throw new IllegalArgumentException("未知操作");
            }
            send(exchange, 200, mapper.writeValueAsString(liveState()));
        } catch (WorldWebHost.PositionMismatch mismatch) {
            send(exchange, 409, error("POSITION_MISMATCH", "世界记录已变化，请刷新后重新选择操作"));
        } catch (SceneRoutes.RevisionMismatch mismatch) {
            send(exchange, 409, error("REVISION_MISMATCH", "场景配置已变化，请核对后再开始"));
        } catch (WorldWebHost.BusyException busy) {
            throw busy;
        } catch (IllegalArgumentException invalid) {
            send(exchange, 400, error("INVALID", "人物或操作参数不合法"));
        } catch (IllegalStateException unavailable) {
            send(exchange, 409, error("LIVE_UNAVAILABLE", "当前无法执行，请核对运行模式、暂停状态或在途请求"));
        } catch (Exception failure) {
            throw new WorldIoFailure();
        }
    }

    private void advance(HttpExchange exchange) throws IOException, WorldWebHost.BusyException {
        JsonNode body = jsonBody(exchange, ADVANCE_FIELDS);
        Long expected = strictLong(body.get("expectedPosition"));
        Long target = strictLong(body.get("targetMillis"));
        if (expected == null || target == null || expected < 0 || target < 0) {
            send(exchange, 400, error("INVALID", "记录序号或模拟时点格式不合法"));
            return;
        }
        try {
            long position = host.advance(expected, target);
            send(exchange, 200, "{\"position\":\"" + position + "\"}");
        } catch (WorldWebHost.PositionMismatch mismatch) {
            send(exchange, 409, "{\"error\":\"POSITION_MISMATCH\",\"message\":\"记录已变化，请刷新\","
                    + "\"position\":\"" + mismatch.position() + "\"}");
        } catch (WorldWebHost.BusyException busy) {
            throw busy;
        } catch (IllegalArgumentException invalid) {
            send(exchange, 400, error("INVALID", "目标模拟时点不能早于当前时点"));
        } catch (Exception failure) {
            throw new WorldIoFailure();
        }
    }

    private void personView(HttpExchange exchange, WebSessions.Session session)
            throws IOException, WorldWebHost.BusyException {
        try {
            RoleView view = host.roleView(session.personId());
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (RoleView.Account account : view.accounts()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("accountId", account.accountId());
                row.put("kind", account.kind().name());
                row.put("ownerId", account.ownerId());
                row.put("cash", plain(account.cash()));
                row.put("frozenCash", plain(account.frozenCash()));
                row.put("availableCash", plain(account.availableCash()));
                accounts.add(row);
            }
            List<Map<String, Object>> holdings = new ArrayList<>();
            for (RoleView.Holding holding : view.holdings()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("accountId", holding.accountId());
                row.put("symbol", holding.symbol());
                row.put("quantity", Long.toString(holding.quantity()));
                row.put("frozen", Long.toString(holding.frozen()));
                row.put("available", Long.toString(holding.available()));
                holdings.add(row);
            }
            List<Map<String, Object>> market = new ArrayList<>();
            for (RoleView.Market row : view.market()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("symbol", row.symbol());
                // 成交价与参考价分开：无成交时 lastPrice 保持 null，绝不用参考价冒充
                entry.put("lastPrice", plain(row.lastPrice()));
                entry.put("lastTradeQuantity", Long.toString(row.lastTradeQuantity()));
                entry.put("lastTradeAtMillis", Long.toString(row.lastTradeAtMillis()));
                entry.put("bestBid", plain(row.bestBid()));
                entry.put("bestAsk", plain(row.bestAsk()));
                ValuationModel.Price reference = row.reference();
                entry.put("referencePrice", reference == null ? null : plain(reference.price()));
                entry.put("referenceSource", reference == null ? null : reference.source().name());
                entry.put("referenceAtMillis", reference == null
                        || reference.asOfMillis() < 0 ? null : Long.toString(reference.asOfMillis()));
                entry.put("referenceAgeMillis", reference == null
                        || reference.ageMillis() < 0 ? null : Long.toString(reference.ageMillis()));
                market.add(entry);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("personPartyId", view.personPartyId());
            response.put("timeMillis", Long.toString(view.timeMillis()));
            response.put("tools", view.tools());
            response.put("accounts", accounts);
            response.put("holdings", holdings);
            response.put("market", market);
            response.put("appointments", view.appointments().size());
            response.put("catalog", view.catalog().size());
            response.put("readings", view.readings().size());
            response.put("observations", view.observations().size());
            response.put("permissions", view.permissions().size());
            send(exchange, 200, mapper.writeValueAsString(response));
        } catch (WorldWebHost.BusyException busy) {
            throw busy;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw invalid;
        } catch (Exception failure) {
            throw new WorldIoFailure();
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 世界读写失败（对外固定 503）。 */
    private static final class WorldIoFailure extends RuntimeException {
        WorldIoFailure() {
            super("WORLD_IO");
        }
    }

    private static String error(String code, String message) {
        return "{\"error\":\"" + code + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String plain(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * 严格解析长整数：只接受规范十进制字符串或严格 JSON 整数；浮点、指数、越界一律 null。
     */
    private static Long strictLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            if (!text.matches("-?(0|[1-9][0-9]*)")) {
                return null;
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException overflow) {
                return null;
            }
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        return null;
    }

    private JsonNode jsonBody(HttpExchange exchange, Set<String> allowed) throws IOException {
        requireJsonType(exchange);
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length) > MAX_BODY_BYTES) {
                    send(exchange, 413, error("BODY_TOO_LARGE", "请求体过大"));
                    throw new StopHandling();
                }
            } catch (NumberFormatException malformed) {
                send(exchange, 400, error("INVALID", "请求头不合法"));
                throw new StopHandling();
            }
        }
        byte[] body = readBounded(exchange.getRequestBody());
        if (body == null) {
            send(exchange, 413, error("BODY_TOO_LARGE", "请求体过大"));
            throw new StopHandling();
        }
        JsonNode parsed;
        try {
            parsed = STRICT.readTree(body);
        } catch (IOException malformed) {
            send(exchange, 400, error("INVALID", "请求体不是合法 JSON"));
            throw new StopHandling();
        }
        if (parsed == null || !parsed.isObject()) {
            send(exchange, 400, error("INVALID", "请求体必须是 JSON 对象"));
            throw new StopHandling();
        }
        var names = parsed.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!allowed.contains(field)) {
                send(exchange, 400, error("INVALID", "存在未知字段：" + field));
                throw new StopHandling();
            }
        }
        return parsed;
    }

    /** Content-Type 必须是 application/json，可带 charset 参数；其它一律 415。 */
    private void requireJsonType(HttpExchange exchange) throws IOException {
        String raw = exchange.getRequestHeaders().getFirst("Content-Type");
        boolean ok = raw != null;
        if (ok) {
            String[] parts = raw.split(";");
            ok = parts[0].trim().equalsIgnoreCase("application/json");
            for (int index = 1; ok && index < parts.length; index++) {
                String parameter = parts[index].trim().toLowerCase(Locale.ROOT);
                ok = parameter.startsWith("charset=");
            }
        }
        if (!ok) {
            send(exchange, 415, error("UNSUPPORTED_MEDIA_TYPE", "只接受 application/json"));
            throw new StopHandling();
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        try (input) {
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > MAX_BODY_BYTES) {
                    return null;
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private String tokenOf(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(COOKIE + "=")) {
                    return trimmed.substring(COOKIE.length() + 1);
                }
            }
        }
        return null;
    }

    private void setCookie(HttpExchange exchange, String token) {
        exchange.getResponseHeaders().add("Set-Cookie",
                COOKIE + "=" + token + "; HttpOnly; SameSite=Strict; Path=/");
    }

    private String newBootstrap() {
        byte[] material = new byte[24];
        random.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    private boolean serveStatic(HttpExchange exchange, String path) throws IOException {
        String name = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/app.js" -> "app.js";
            case "/style.css" -> "style.css";
            default -> null;
        };
        if (name == null) {
            return false;
        }
        byte[] bytes = staticDir == null ? classpathResource(name) : fileResource(name);
        if (bytes == null) {
            return false;
        }
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : "application/javascript; charset=utf-8";
        exchange.getResponseHeaders().set("Content-Type", type);
        securityHeaders(exchange);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        return true;
    }

    /** 打包形态：只读取 classpath 上 /web/ 下的白名单文件。 */
    private static byte[] classpathResource(String name) throws IOException {
        try (InputStream input = WorldWebServer.class.getResourceAsStream("/web/" + name)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    /** 测试形态：只读取给定目录下的白名单文件，拒绝路径穿越。 */
    private byte[] fileResource(String name) throws IOException {
        Path root = staticDir.toAbsolutePath().normalize();
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    private void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        securityHeaders(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /** 已发送响应后终止当前处理。 */
    private static final class StopHandling extends RuntimeException {
        StopHandling() {
            super("STOP");
        }
    }
}
