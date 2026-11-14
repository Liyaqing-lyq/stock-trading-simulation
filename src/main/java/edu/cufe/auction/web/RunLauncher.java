package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.BranchRunIndex;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 本机受控分支启动器：只认运行索引里已登记的分支子档，只管理自己创建的 {@link Process}。
 *
 * <p>边界（刻意写死，不给调用方留自由度）：</p>
 * <ol>
 *   <li>对外入参只有 {@code runId}；主类、classpath、工作目录、运行目录、key 文件路径、
 *       端口区间与并发上限全部来自服务端 {@link Config}，不接受用户路径或任意命令；</li>
 *   <li>端口只在固定小区间里逐个试绑，被陌生服务占用就跳过；全被占用时明确报
 *       {@code PORT_TAKEN}，<b>不杀、不接管</b>任何已有进程；</li>
 *   <li>bootstrap 与 handoff 凭据只经 stdin 交给子进程并留在内存，不进 argv、不进日志、
 *       不进任何对外 DTO；</li>
 *   <li>启动成功要健康核验：子进程回报的 runId、父来源、切点与首帧位置必须与索引一致，
 *       否则停掉自己的进程并记真实原因；</li>
 *   <li>父进程结束时关闭自有子进程，<b>不删</b>运行资料；子档下次启动从封存件恢复。</li>
 * </ol>
 *
 * <p>本类不实现守护、重启、跨机与端口复用，也不读取子进程日志里的任何凭据。</p>
 */
public final class RunLauncher implements AutoCloseable {

    /** 本机子进程入口（固定；不接受外部覆盖）。 */
    public static final String CHILD_MAIN_CLASS = "edu.cufe.auction.web.WorldBranchApp";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HANDOFF_PATH = "/api/session/handoff";
    private static final int MAX_CHILD_OUTPUT = 8 * 1024;
    /** 关闭子进程时的单段等待上限：先等它读到 EOF 自行收尾，再考虑终止。 */
    private static final long STOP_GRACE_MILLIS = 5_000L;

    /**
     * 服务端受信配置。
     *
     * @param workDir             工作目录（与世界日志同级）
     * @param runsDir             运行目录（{@code workDir/runs}）
     * @param keyFile             key 文件路径；只传路径，值仅子进程内存
     * @param portFrom            端口区间下界（含）
     * @param portTo              端口区间上界（含）
     * @param maxRuns             同时运行的子进程上限
     * @param startupTimeoutMillis 启动健康检查超时
     * @param classpath           子进程 classpath（由宿主进程给出）
     */
    public record Config(Path workDir, Path runsDir, Path keyFile, int portFrom, int portTo,
                         int maxRuns, long startupTimeoutMillis, String classpath) {

        /** 校验配置边界。 */
        public Config {
            if (workDir == null || runsDir == null || keyFile == null) {
                throw new IllegalArgumentException("启动器配置缺少目录或 key 文件路径");
            }
            if (portFrom < 1024 || portTo > 65535 || portFrom > portTo) {
                throw new IllegalArgumentException("端口区间不合法：" + portFrom + "-" + portTo);
            }
            if (maxRuns < 1 || maxRuns > 4) {
                throw new IllegalArgumentException("并发上限必须在 1 到 4 之间");
            }
            if (startupTimeoutMillis < 1_000L || startupTimeoutMillis > 120_000L) {
                throw new IllegalArgumentException("启动超时必须在 1 到 120 秒之间");
            }
            if (classpath == null || classpath.isBlank()) {
                throw new IllegalArgumentException("子进程 classpath 不能为空");
            }
        }
    }

    /**
     * God 页面可见的一条运行状态；不含 bootstrap/handoff/画像等任何内部凭据。
     *
     * @param runId          子运行标识
     * @param parentRunId    父运行标识
     * @param parentPosition 父切点位置
     * @param kind           分支种类
     * @param note           注记
     * @param firstPosition  子首帧位置
     * @param port           端口；未启动为 0
     * @param status         CREATED/STARTING/RUNNING/STOPPED/EXITED/FAILED
     * @param reason         真实原因；正常为空
     * @param managed        是否由本进程管理
     * @param pid            本进程自有子进程的操作系统 PID；未管理为 0
     */
    public record View(String runId, String parentRunId, long parentPosition, String kind,
                       String note, long firstPosition, int port, String status, String reason,
                       boolean managed, long pid) {
    }

    /** 启动/停止/进入被明确拒绝：携带稳定原因码。 */
    public static final class Rejected extends Exception {

        /** 稳定原因码。 */
        private final String code;

        /**
         * 构造一条拒绝。
         *
         * @param code   稳定原因码
         * @param detail 说明
         */
        public Rejected(String code, String detail) {
            super(detail);
            this.code = code;
        }

        /**
         * 读取稳定原因码。
         *
         * @return 稳定原因码
         */
        public String code() {
            return code;
        }
    }

    /**
     * 进入子运行的结果。
     *
     * @param location  本地固定跳转地址（由服务端端口生成，不接受客户端传入）
     * @param setCookie 子进程签发的会话 cookie 原文，由浏览器直接存
     */
    public record Entry(String location, String setCookie) {
    }

    /** 本进程管理的一条子运行。 */
    private static final class Managed {
        final String runId;
        final String parentRunId;
        final long parentPosition;
        final long firstPosition;
        final String kind;
        final String note;
        Process process;
        int port;
        String status = "CREATED";
        String reason = "";
        String handoff;
        /** 父进程这一端的子 stdin：保持打开；关闭它就等于告诉子进程"父已退出"。 */
        OutputStream stdin;
        /** 是否是本启动器主动关闭（stop/close/启动失败）；主动关闭的退出不算异常 EXITED。 */
        boolean closing;
        /** 用户已登记停止意图：清理完成前仍占并发额度，但不再接受 start 提交或失败改写。 */
        boolean cancelled;

        Managed(BranchRunIndex.Entry entry) {
            this.runId = entry.runId();
            this.parentRunId = entry.parentRunId();
            this.parentPosition = entry.parentPosition();
            this.firstPosition = entry.parentPosition() + 1L;
            this.kind = entry.kind();
            this.note = entry.note();
        }

        boolean active() {
            return "STARTING".equals(status) || "RUNNING".equals(status);
        }
    }

    /** 父运行画像来源：允许抛出受检异常，因为它要跨 writer 队列取一次真实画像。 */
    public interface ProfileSource {
        /**
         * 读取父运行可信画像（在宿主的 writer 任务里取，返回的画像不可被并发修改）。
         *
         * @return 父运行可信画像
         * @throws Exception 运行不可读
         */
        BranchProfile get() throws Exception;
    }

    private final Config config;
    private final ProfileSource profileSource;
    /**
     * 测试接缝：{@link #start(String)} 在锁外做健康核验之前调用一次，默认 {@code null}（生产不设）。
     *
     * <p>只用于确定性回归：把 start 停在"子进程已起、尚未提交 RUNNING"的窗口里，让测试在窗口内
     * 调 stop 或外部结束，而不是靠轮询/sleep 撞概率。不参与任何生产判断。</p>
     */
    Runnable beforeHealthCheck;
    /**
     * 测试接缝：{@link #stop(String)} 在自有进程清理完、写最终状态之前调用一次，默认 {@code null}。
     *
     * <p>只用于确定性回归：把 stop 停在"进程已清理、状态尚未提交"的窗口里，让并发的 start 失败收尾
     * 正好落在这个窗口内。生产不设。</p>
     */
    Runnable afterStopCleanup;
    private final Object lock = new Object();
    private final Map<String, Managed> managed = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * 构造启动器。
     *
     * @param config        服务端受信配置
     * @param profileSource 父运行可信画像来源（同一进程内的唯一 writer 宿主）
     */
    public RunLauncher(Config config, ProfileSource profileSource) {
        if (config == null || profileSource == null) {
            throw new IllegalArgumentException("启动器参数不合法");
        }
        this.config = config;
        this.profileSource = profileSource;
    }

    /**
     * 读取服务端配置。
     *
     * @return 服务端配置（只读）
     */
    public Config config() {
        return config;
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 当前运行清单：父索引里的全部子档 + 本进程自有进程状态。
     *
     * <p>不由本进程管理的条目一律显示 {@code CREATED} 并给出真实说明，不猜测它是否在别处运行。</p>
     *
     * @return 不可变视图列表，按索引顺序
     * @throws IOException 索引不可读
     */
    public List<View> list() throws IOException {
        List<View> views = new ArrayList<>();
        synchronized (lock) {
            for (BranchRunIndex.Entry entry : repository().runs()) {
                Managed own = managed.get(entry.runId());
                if (own == null) {
                    views.add(new View(entry.runId(), entry.parentRunId(), entry.parentPosition(),
                            entry.kind(), entry.note(), entry.parentPosition() + 1L, 0, "CREATED",
                            "本进程未管理该运行（未启动，或由另一次进程启动）", false, 0L));
                    continue;
                }
                refresh(own);
                views.add(view(own));
            }
        }
        return List.copyOf(views);
    }

    /**
     * 当前正在运行的子进程数量。
     *
     * @return STARTING 与 RUNNING 都计入的数量
     */
    public int activeCount() {
        synchronized (lock) {
            int count = 0;
            for (Managed own : managed.values()) {
                refresh(own);
                if (own.active()) {
                    count++;
                }
            }
            return count;
        }
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 启动一条已登记的分支子档。
     *
     * @param runId 子运行标识（必须已在运行索引内登记）
     * @return 启动后的真实状态
     * @throws Rejected 标识未登记、已在运行、并发上限或端口不可用
     * @throws IOException 索引不可读或凭据/画像读取失败
     */
    public View start(String runId) throws Rejected, IOException {
        BranchRunIndex.Entry entry = requireEntry(runId);
        BranchProfile profile;
        try {
            profile = profileSource.get();
        } catch (Exception failure) {
            throw new Rejected("PROFILE_UNAVAILABLE", "父运行画像不可读，无法核对子档");
        }
        if (profile == null) {
            throw new Rejected("PROFILE_UNAVAILABLE", "父运行画像不可读，无法核对子档");
        }
        String digest = profile.digest();
        if (!entry.profileDigest().equals(digest)) {
            throw new Rejected("PROFILE_MISMATCH",
                    "父运行画像摘要与索引不一致：子档不能由这张配置打开");
        }

        Managed own;
        Rejected launchFailure = null;
        synchronized (lock) {
            Managed existing = managed.get(runId);
            if (existing != null) {
                refresh(existing);
            }
            if (existing != null && existing.active()) {
                throw new Rejected("ALREADY_RUNNING", "该运行已在本进程中运行");
            }
            if (activeCount() >= config.maxRuns()) {
                throw new Rejected("LAUNCH_LIMIT",
                        "最多同时运行 " + config.maxRuns() + " 个本机子运行");
            }
            int port = allocatePort();
            if (port < 0) {
                throw new Rejected("PORT_TAKEN", "端口区间 " + config.portFrom() + "-"
                        + config.portTo() + " 全部被占用；未终止任何已有进程");
            }
            own = new Managed(entry);
            own.port = port;
            own.status = "STARTING";
            own.reason = "";
            own.handoff = newToken();
            String bootstrap = newToken();
            managed.put(runId, own);
            try {
                own.process = launch(own, bootstrap, profile, digest);
            } catch (IOException | RuntimeException failure) {
                own.closing = true;
                own.reason = "无法创建子进程：" + failure.getMessage();
                launchFailure = new Rejected("START_FAILED", own.reason);
            }
            if (launchFailure == null) {
                drain(own.process);
            }
        }
        if (launchFailure != null) {
            // 启动中途失败也先在锁外回收自有进程；回收前保留 STARTING 占用名额。
            destroy(own);
            synchronized (lock) {
                if ("STARTING".equals(own.status)) {
                    own.status = "FAILED";
                }
            }
            throw launchFailure;
        }

        // 健康核验在锁外进行：期间状态查询仍可读到 STARTING 的真实状态。
        Runnable gate = beforeHealthCheck;
        if (gate != null) {
            // 测试接缝：生产恒为 null，只在确定性回归里把 start 停在"进程已起、尚未提交"的窗口。
            gate.run();
        }
        try {
            verifyHealthy(own);
            synchronized (lock) {
                // 提交 RUNNING 前在同一把锁里复核：条目还是不是自己、有没有被 stop、进程还活着。
                // 否则 stop 先写 STOPPED、健康的 start 再把条目改回 RUNNING，就是一条假状态。
                if (managed.get(runId) != own) {
                    throw new Rejected("START_CANCELLED",
                            "启动过程中该运行已被替换或移除，不回写 RUNNING");
                }
                if (!own.process.isAlive()) {
                    refresh(own);
                    throw new Rejected("CHILD_EXITED", "子进程在健康核验后已退出");
                }
                if ("STOPPED".equals(own.status) || "EXITED".equals(own.status)
                        || "FAILED".equals(own.status) || own.cancelled) {
                    throw new Rejected("START_CANCELLED",
                            "启动过程中该运行已被停止，不回写 RUNNING");
                }
                own.status = "RUNNING";
                own.reason = "";
            }
        } catch (Rejected failure) {
            destroy(own);
            synchronized (lock) {
                // 只有"还是这一代 + 仍是 STARTING + 用户没登记停止"才写 FAILED；
                // 否则会覆盖 stop 的 STOPPED、或改掉新一代条目。
                if (managed.get(runId) == own && "STARTING".equals(own.status)
                        && !own.cancelled) {
                    own.status = "FAILED";
                    own.reason = failure.getMessage();
                }
            }
            throw failure;
        }
        synchronized (lock) {
            return view(own);
        }
    }

    /**
     * 停止一条由本进程启动的子运行。
     *
     * @param runId 子运行标识
     * @return 停止后的真实状态
     * @throws Rejected 未登记或不是本进程创建
     */
    public View stop(String runId) throws Rejected {
        Managed own;
        synchronized (lock) {
            own = managed.get(runId);
        }
        if (own == null) {
            throw new Rejected("NOT_OWNED", "该运行不是本进程启动的；不会按端口终止陌生进程");
        }
        // 先按真实存活定一次：如果是外部结束的，要保持 EXITED，不能因为用户点了"停止"变成 STOPPED。
        // 同时**在锁内登记停止意图**：清理完成前状态仍是 STARTING/RUNNING（继续占并发额度），
        // 但并发的 start 既不能再提交 RUNNING，也不能把自己的失败改写成 FAILED。
        synchronized (lock) {
            refresh(own);
            if (own.active() && !"EXITED".equals(own.status) && !"FAILED".equals(own.status)) {
                own.cancelled = true;
            }
        }
        // 等待放在锁外：先关 stdin 让子进程自己收尾，期间其它查询仍可读真实状态。
        if (own.process != null) {
            destroy(own);
        }
        Runnable gate = afterStopCleanup;
        if (gate != null) {
            // 测试接缝：生产恒为 null；确定性回归用它在"清理完、未提交"的窗口里触发并发行为。
            gate.run();
        }
        synchronized (lock) {
            refresh(own);
            // 用户已登记的停止优先：异常退出保持 EXITED，用户停止保持 STOPPED，
            // 只有原本还在跑（或启动中）的条目变成 STOPPED。
            if (own.cancelled || (!"FAILED".equals(own.status) && !"EXITED".equals(own.status))) {
                own.status = "STOPPED";
                own.reason = "";
            }
            return view(own);
        }
    }

    /**
     * 进入一条正在运行的子运行：用父持有的 handoff 凭据换一个新的子会话 cookie。
     *
     * <p>每次进入都重新换取，因此子进程内退出登录后可以再次进入；凭据失效时明确拒绝，
     * 不伪造跳转。</p>
     *
     * @param runId 子运行标识
     * @return 本地固定跳转地址与 {@code Set-Cookie} 原文
     * @throws Rejected 未运行、凭据失效或子进程拒绝
     */
    public Entry enter(String runId) throws Rejected {
        Managed own;
        synchronized (lock) {
            own = managed.get(runId);
            if (own != null) {
                refresh(own);
            }
            if (own == null || !own.active() || own.cancelled) {
                throw new Rejected("RUN_NOT_RUNNING", "该运行没有在运行中");
            }
        }
        HttpResponse<String> response;
        try {
            response = handoff(own, false);
        } catch (IOException interrupted) {
            throw new Rejected("HANDOFF_UNAVAILABLE", "子进程没有响应：" + interrupted.getMessage());
        }
        if (response.statusCode() == 401) {
            throw new Rejected("HANDOFF_EXPIRED", "子进程已不再接受这次启动的凭据，请重新启动该运行");
        }
        if (response.statusCode() != 200) {
            throw new Rejected("HANDOFF_REJECTED", "子进程拒绝换发会话（" + response.statusCode() + "）");
        }
        Optional<String> cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(WorldWebServer.cookieNameFor(runId) + "="))
                .findFirst();
        if (cookie.isEmpty()) {
            throw new Rejected("HANDOFF_REJECTED", "子进程没有签发本次运行的会话 cookie");
        }
        return new Entry("http://127.0.0.1:" + own.port + "/", cookie.get());
    }

    /** 关闭：只停自己启动的子进程，不删除任何运行资料。 */
    @Override
    public void close() {
        List<Managed> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(managed.values());
        }
        for (Managed own : snapshot) {
            if (own.process == null) {
                continue;
            }
            // 锁外等待：先关 stdin 让子进程读到 EOF 自行收尾，再按需终止自己的进程。
            destroy(own);
            synchronized (lock) {
                if (!"FAILED".equals(own.status)) {
                    own.status = "STOPPED";
                    own.reason = "父进程结束，已关闭自有子进程";
                }
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private BranchRunIndex.Entry requireEntry(String runId) throws Rejected, IOException {
        if (runId == null || runId.isBlank()) {
            throw new Rejected("RUN_NOT_FOUND", "运行标识为空");
        }
        Optional<BranchRunIndex.Entry> entry = repository().entry(runId);
        return entry.orElseThrow(() -> new Rejected("RUN_NOT_FOUND",
                "运行索引没有登记该子档：" + runId));
    }

    private edu.cufe.auction.world.BranchRepository repository() {
        return new edu.cufe.auction.world.BranchRepository(config.runsDir());
    }

    private View view(Managed own) {
        return new View(own.runId, own.parentRunId, own.parentPosition, own.kind, own.note,
                own.firstPosition, own.port, own.status, own.reason, true,
                own.process == null ? 0L : own.process.pid());
    }

    /**
     * 按自有 {@link Process} 的真实存活状态刷新条目（只在 {@code lock} 内调用）。
     *
     * <p>为什么需要它：子进程可能在我们不知情时退出（崩溃、被外部结束）。如果只看
     * {@code Managed.status}，清单会一直报 RUNNING、"进入"会给一个必然失败的按钮、并发额度被
     * 一条死进程占着、再次 start 还会 ALREADY_RUNNING——这就是"半活假运行"。这里不做任何定时
     * 监督，只在每次查询或动作前即时核对一次：进程没了就转 {@code EXITED}，冻结真实退出码与
     * 输出尾部，并释放 stdin 与 tail 引用。</p>
     *
     * @param own 本进程管理的条目
     */
    private void refresh(Managed own) {
        if (own.process == null) {
            return;
        }
        if (own.closing) {
            // 是我们自己关掉的（stop/close/启动失败）：退出原因由调用方决定，不判异常 EXITED。
            return;
        }
        if (!"STARTING".equals(own.status) && !"RUNNING".equals(own.status)) {
            return;
        }
        if (own.process.isAlive()) {
            return;
        }
        int code;
        try {
            code = own.process.exitValue();
        } catch (IllegalThreadStateException stillAlive) {
            return;
        }
        String tail = tailOf(own.process);
        closeStdin(own);
        processTail.remove(own.process.pid());
        own.status = "EXITED";
        own.reason = "子进程已退出（退出码 " + code + "）" + (tail.isEmpty() ? "" : "：" + tail);
    }

    private String newToken() {
        byte[] material = new byte[32];
        random.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /** 在固定区间内取一个空闲且未被本进程占用的端口；全部不可用返回 -1。 */
    private int allocatePort() {
        for (int port = config.portFrom(); port <= config.portTo(); port++) {
            if (portInUse(port)) {
                continue;
            }
            if (bindable(port)) {
                return port;
            }
        }
        return -1;
    }

    private boolean portInUse(int port) {
        for (Managed own : managed.values()) {
            if (own.port == port && own.active()) {
                return true;
            }
        }
        return false;
    }

    /** 只探测能否绑定本机回环；成功即释放，不长期占用。 */
    private static boolean bindable(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            socket.setReuseAddress(false);
            return true;
        } catch (IOException taken) {
            return false;
        }
    }

    private Process launch(Managed own, String bootstrap, BranchProfile profile, String digest)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(config.classpath());
        command.add(CHILD_MAIN_CLASS);
        command.add("--run");
        command.add(own.runId);
        command.add("--dir");
        command.add(config.workDir().toAbsolutePath().toString());
        command.add("--port");
        command.add(Integer.toString(own.port));
        command.add("--keys");
        command.add(config.keyFile().toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(config.workDir().toAbsolutePath().toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        own.process = process; // 从创建成功起即归属本次启动，后续写管道失败也能回收。

        Map<String, Object> handoff = new LinkedHashMap<>();
        handoff.put("runId", own.runId);
        handoff.put("bootstrap", bootstrap);
        handoff.put("handoff", own.handoff);
        handoff.put("profileDigest", digest);
        // 画像按“原样 JSON 文本”传递：经 JsonNode 中转会把小数读成 double，摘要就变了。
        handoff.put("profile", MAPPER.writeValueAsString(profile));
        byte[] payload = MAPPER.writeValueAsBytes(handoff);
        // 配置写完后**不关闭** stdin：这条管道就是父→子的存活信号。父正常 stop 或进程意外退出时，
        // 操作系统关闭写端 → 子进程读到 EOF → 自行收尾（不再需要任何控制协议或监督框架）。
        OutputStream stdin = process.getOutputStream();
        own.stdin = stdin;
        stdin.write(payload);
        stdin.write('\n');
        stdin.flush();
        return process;
    }

    /** Windows 上优先 javaw：不产生额外控制台窗口；子进程输出只被读空，不打印。 */
    private static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path javaw = bin.resolve("javaw.exe");
        if (Files.isRegularFile(javaw)) {
            return javaw.toAbsolutePath().toString();
        }
        Path java = bin.resolve("java.exe");
        if (Files.isRegularFile(java)) {
            return java.toAbsolutePath().toString();
        }
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** 持续读空子进程输出（否则管道写满会卡住子进程），只保留有界尾部用于失败说明。 */
    private void drain(Process process) {
        StringBuilder tail = new StringBuilder();
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try (InputStream input = process.getInputStream()) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    synchronized (tail) {
                        tail.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        if (tail.length() > MAX_CHILD_OUTPUT) {
                            tail.delete(0, tail.length() - MAX_CHILD_OUTPUT);
                        }
                    }
                }
            } catch (IOException ignored) {
                // 子进程已退出
            }
        }, "branch-child-drain-" + process.pid());
        reader.setDaemon(true);
        reader.start();
        processTail.put(process.pid(), tail);
    }

    private final Map<Long, StringBuilder> processTail = new java.util.concurrent.ConcurrentHashMap<>();

    private String tailOf(Process process) {
        StringBuilder tail = processTail.get(process.pid());
        if (tail == null) {
            return "";
        }
        synchronized (tail) {
            String text = tail.toString().strip();
            return text.length() > 400 ? text.substring(text.length() - 400) : text;
        }
    }

    /** 健康核验：身份、父来源、切点、首帧位置全部与索引一致才算启动成功。 */
    private void verifyHealthy(Managed own) throws Rejected {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                config.startupTimeoutMillis());
        String lastFailure = "子进程没有在超时内就绪";
        while (System.nanoTime() < deadline) {
            if (!own.process.isAlive()) {
                String tail = tailOf(own.process);
                throw new Rejected("CHILD_EXITED", "子进程提前退出（退出码 "
                        + own.process.exitValue() + "）" + (tail.isEmpty() ? "" : "：" + tail));
            }
            try {
                HttpResponse<String> response = handoff(own, true);
                if (response.statusCode() == 200) {
                    JsonNode body = MAPPER.readTree(response.body());
                    String identity = checkIdentity(own, body);
                    if (identity == null) {
                        return;
                    }
                    lastFailure = identity;
                } else {
                    lastFailure = "子进程健康接口返回 " + response.statusCode();
                }
            } catch (IOException | RuntimeException notReady) {
                lastFailure = "子进程还没有就绪：" + notReady.getMessage();
            }
            sleep(200L);
        }
        throw new Rejected("START_TIMEOUT", lastFailure);
    }

    /** @return null 表示身份全部相符；否则返回真实差异说明 */
    private String checkIdentity(Managed own, JsonNode body) {
        String runId = body.path("runId").asText("");
        String parentRunId = body.path("parentRunId").asText("");
        long parentPosition = body.path("parentPosition").asLong(-1L);
        long position = body.path("position").asLong(-1L);
        if (!own.runId.equals(runId)) {
            return "子进程回报的运行标识不符：" + runId;
        }
        if (!own.parentRunId.equals(parentRunId)) {
            return "子进程回报的父运行不符：" + parentRunId;
        }
        if (parentPosition != own.parentPosition) {
            return "子进程回报的切点不符：" + parentPosition + "（期望 " + own.parentPosition + "）";
        }
        // 只要求"不早于本运行首帧"：子运行合法推进过自己的世界后再重启，位置本来就会大于首帧，
        // 强行要求相等会把正常恢复判成失败。
        if (position < own.firstPosition) {
            return "子进程确认位置早于本运行首帧：" + position + "（首帧 " + own.firstPosition + "）";
        }
        return null;
    }

    private HttpResponse<String> handoff(Managed own, boolean probe) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", own.handoff);
        body.put("probe", probe);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + own.port + HANDOFF_PATH))
                .timeout(Duration.ofMillis(probe ? 1_500L : 4_000L))
                .header("Content-Type", "application/json")
                .header("Origin", "http://127.0.0.1:" + own.port)
                .header("X-World-Request", "1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body)))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("健康检查被中断", interrupted);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭一条自有子运行：先关父这一端的 stdin（子进程读到 EOF 会自己收尾），
     * 限定等待它自行退出；只有超时才终止自己的 {@link Process}。从不按端口杀别的进程。
     */
    private void destroy(Managed own) {
        Process process = own.process;
        if (process == null) {
            return;
        }
        own.closing = true;
        closeStdin(own);
        try {
            if (!process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        processTail.remove(process.pid());
    }

    /** 关闭父这一端的子 stdin；重复调用安全。 */
    private static void closeStdin(Managed own) {
        OutputStream stdin = own.stdin;
        if (stdin == null) {
            return;
        }
        own.stdin = null;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 子进程已退出时管道可能已断开
        }
    }

    /** 供子进程校验 handoff 凭据的常量时间比较；避免在多个调用点各写一份。 */
    static boolean tokenMatches(String expected, String candidate) {
        if (expected == null || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** 供测试与子进程共用的规范化画像读取（缺字段即失败，不给默认值）。 */
    static BranchProfile readProfile(JsonNode node) throws IOException {
        if (node == null) {
            throw new IOException("缺少可信画像");
        }
        try {
            if (node.isTextual()) {
                return MAPPER.readValue(node.textValue(), BranchProfile.class);
            }
            if (node.isObject()) {
                return MAPPER.treeToValue(node, BranchProfile.class);
            }
            throw new IOException("可信画像不是 JSON 文本或对象");
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("可信画像无法解析", invalid);
        }
    }
}
