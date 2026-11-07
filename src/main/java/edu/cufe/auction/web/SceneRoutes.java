package edu.cufe.auction.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 五场景路由配置：固定场景 → 槽位/模型/预算，持久化在运行目录的非秘密 JSON。
 *
 * <p>安全与语义边界：</p>
 * <ul>
 *   <li>只保存场景、槽位 ID、模型名、启用状态与预算整数；不保存凭据、key 文件路径、
 *       提示词或输出正文，也不保存任意地址（provider 与 baseUrl 是固定常量）。</li>
 *   <li>配置不是世界经济状态；它属于运行目录，重启后仍可见，但不进入 WorldJournal。</li>
 *   <li>这里的预算只是配置值；本类不发起请求，也不声称已经约束任何真实调用；
 *       未来每次 attempt 必须自行冻结当时的配置，改 route 不影响已启动的请求。</li>
 *   <li>写入采用同目录唯一临时文件 + 原子替换；失败不发布半配置，只清理自己的临时文件。</li>
 * </ul>
 */
public final class SceneRoutes implements AutoCloseable {

    /** 固定提供方。 */
    public static final String PROVIDER = "deepseek";
    /** 固定地址。 */
    public static final String BASE_URL = "https://api.deepseek.com";
    /** 允许的快速模型。 */
    public static final String MODEL_FLASH = "deepseek-flash";
    /** 允许的推理模型。 */
    public static final String MODEL_PRO = "deepseek-v4-pro";

    /** 配置文件。 */
    public static final String FILE_NAME = "scene-routes.json";
    /** 文件大小上限 64 KiB。 */
    public static final int MAX_BYTES = 64 * 1024;

    /** 固定五场景（顺序即展示顺序）。 */
    public enum Scene {
        GOVERNANCE("governance", "公司治理"),
        RESEARCH("research", "机构研究"),
        TRADING("trading", "交易执行"),
        MEDIA("media", "媒体社交"),
        EVALUATION("evaluation", "评估实验");

        private final String id;
        private final String label;

        Scene(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** @return 配置中的稳定标识 */
        public String id() {
            return id;
        }

        /** @return 中文展示名 */
        public String label() {
            return label;
        }

        /**
         * 由配置标识解析场景。
         *
         * @param id 稳定标识
         * @return 场景
         * @throws IllegalArgumentException 未知标识
         */
        public static Scene fromId(String id) {
            for (Scene scene : values()) {
                if (scene.id.equals(id)) {
                    return scene;
                }
            }
            throw new IllegalArgumentException("未知场景");
        }
    }

    /**
     * 单场景路由。
     *
     * @param scene           场景
     * @param slotId          固定槽位 key-1..key-5
     * @param model           deepseek-flash 或 deepseek-v4-pro
     * @param enabled         是否启用
     * @param maxCalls        调用上限 1..100
     * @param maxTotalTokens  总 token 上限 256..1000000
     * @param maxOutputTokens 单次输出上限 128..8192 且不超过总上限
     * @param maxConcurrency  并发上限 1..4 且不超过调用上限
     */
    public record Route(Scene scene, String slotId, String model, boolean enabled,
                        int maxCalls, int maxTotalTokens, int maxOutputTokens,
                        int maxConcurrency) {

        /** 逐项校验；不合法时抛 IllegalArgumentException（文案不回显输入）。 */
        public Route {
            Objects.requireNonNull(scene, "场景");
            if (slotId == null || !slotId.matches("key-[1-5]")) {
                throw new IllegalArgumentException("槽位必须是 key-1 到 key-5");
            }
            if (!MODEL_FLASH.equals(model) && !MODEL_PRO.equals(model)) {
                throw new IllegalArgumentException("模型不在允许列表");
            }
            if (maxCalls < 1 || maxCalls > 100) {
                throw new IllegalArgumentException("调用上限超出范围");
            }
            if (maxTotalTokens < 256 || maxTotalTokens > 1_000_000) {
                throw new IllegalArgumentException("总 token 上限超出范围");
            }
            if (maxOutputTokens < 128 || maxOutputTokens > 8192
                    || maxOutputTokens > maxTotalTokens) {
                throw new IllegalArgumentException("输出 token 上限超出范围");
            }
            if (maxConcurrency < 1 || maxConcurrency > 4 || maxConcurrency > maxCalls) {
                throw new IllegalArgumentException("并发上限超出范围");
            }
        }
    }

    /**
     * 不可变配置快照。
     *
     * @param revision 修订号
     * @param routes   五个场景的路由（固定顺序）
     */
    public record Snapshot(long revision, List<Route> routes) {

        /** 冻结并校验恰好五场景。 */
        public Snapshot {
            if (revision < 0) {
                throw new IllegalArgumentException("修订号不能为负");
            }
            List<Route> copy = new ArrayList<>(Objects.requireNonNull(routes, "路由"));
            if (copy.size() != Scene.values().length) {
                throw new IllegalArgumentException("必须恰好五条路由");
            }
            for (Route route : copy) {
                if (route == null) {
                    throw new IllegalArgumentException("路由不能为空");
                }
            }
            copy.sort(Comparator.comparing(Route::scene));
            for (int index = 0; index < copy.size(); index++) {
                if (copy.get(index).scene() != Scene.values()[index]) {
                    throw new IllegalArgumentException("每个场景必须且只能出现一次");
                }
            }
            routes = List.copyOf(copy);
        }
    }

    /** 修订号冲突。 */
    public static final class RevisionMismatch extends Exception {
        private final long currentRevision;

        RevisionMismatch(long currentRevision) {
            super("REVISION_MISMATCH");
            this.currentRevision = currentRevision;
        }

        /** @return 当前真实修订号 */
        public long currentRevision() {
            return currentRevision;
        }
    }

    /** 持久化形态：只含非秘密字段。 */
    private record RouteDto(String scene, String slotId, String model, boolean enabled,
                            int maxCalls, int maxTotalTokens, int maxOutputTokens,
                            int maxConcurrency) {
    }

    private record Persisted(long revision, List<RouteDto> routes) {
    }

    private final Path directory;
    private final Path file;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private Snapshot snapshot;

    private SceneRoutes(Path directory, ObjectMapper mapper, Snapshot snapshot) {
        this.directory = directory;
        this.file = directory.resolve(FILE_NAME);
        this.mapper = mapper;
        this.snapshot = snapshot;
    }

    /**
     * 打开或初建配置：文件不存在时写入默认五路（revision 0）。
     *
     * @param directory 运行目录（必须已存在或可创建）
     * @return 配置实例
     * @throws IOException 目录/文件不可用或内容不合法
     */
    public static SceneRoutes open(Path directory) throws IOException {
        Objects.requireNonNull(directory, "运行目录");
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        ObjectMapper mapper = strictMapper();
        Path file = root.resolve(FILE_NAME);
        SceneRoutes routes = new SceneRoutes(root, mapper, defaultSnapshot());
        if (!Files.exists(file)) {
            routes.persist(routes.snapshot);
            return routes;
        }
        byte[] bytes = readBounded(file);
        Persisted persisted;
        try {
            persisted = mapper.readValue(bytes, Persisted.class);
        } catch (IOException invalid) {
            throw new IOException("场景路由配置不合法");
        }
        if (persisted == null || persisted.routes() == null) {
            throw new IOException("场景路由配置不合法");
        }
        List<Route> parsed = new ArrayList<>();
        for (RouteDto dto : persisted.routes()) {
            if (dto == null) {
                throw new IOException("场景路由配置不合法");
            }
            try {
                parsed.add(new Route(Scene.fromId(dto.scene()), dto.slotId(), dto.model(),
                        dto.enabled(), dto.maxCalls(), dto.maxTotalTokens(),
                        dto.maxOutputTokens(), dto.maxConcurrency()));
            } catch (RuntimeException invalid) {
                throw new IOException("场景路由配置不合法");
            }
        }
        try {
            routes.snapshot = new Snapshot(persisted.revision(), parsed);
        } catch (RuntimeException invalid) {
            throw new IOException("场景路由配置不合法");
        }
        return routes;
    }

    /** @return 当前不可变快照 */
    public Snapshot view() {
        synchronized (lock) {
            return snapshot;
        }
    }

    /** @return 配置所在目录 */
    public Path directory() {
        return directory;
    }

    /**
     * 全量替换：必须恰好每场景一次；revision 相同才可能成功。
     *
     * @param expectedRevision 调用方看到的修订号
     * @param routes           五条路由
     * @throws RevisionMismatch      修订号已被他人推进
     * @throws IOException           持久化失败（此时不发布新配置）
     * @throws IllegalArgumentException 路由不合法
     */
    public void replace(long expectedRevision, List<Route> routes) throws IOException,
            RevisionMismatch {
        Snapshot candidate = new Snapshot(expectedRevision + 1, routes);
        synchronized (lock) {
            if (expectedRevision != snapshot.revision()) {
                throw new RevisionMismatch(snapshot.revision());
            }
            persist(candidate);
            snapshot = candidate;
        }
    }

    @Override
    public void close() {
        // 无外部资源
    }

    private static Snapshot defaultSnapshot() {
        List<Route> routes = new ArrayList<>();
        for (Scene scene : Scene.values()) {
            routes.add(new Route(scene, "key-" + (scene.ordinal() + 1), MODEL_FLASH, false,
                    5, 8192, 512, 1));
        }
        return new Snapshot(0L, routes);
    }

    private void persist(Snapshot target) throws IOException {
        List<RouteDto> dtos = new ArrayList<>();
        for (Route route : target.routes()) {
            dtos.add(new RouteDto(route.scene().id(), route.slotId(), route.model(),
                    route.enabled(), route.maxCalls(), route.maxTotalTokens(),
                    route.maxOutputTokens(), route.maxConcurrency()));
        }
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(new Persisted(target.revision(), dtos));
        } catch (IOException impossible) {
            throw new IOException("场景路由无法序列化");
        }
        Path temp = directory.resolve("." + FILE_NAME + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                replaceAtomically(temp);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // 契约要求原子替换；不支持就失败保持旧配置，不做非原子降级
                throw new IOException("文件系统不支持原子替换，拒绝降级写入");
            }
            forceDirectory();
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 只清理自己的临时文件；清理失败不掩盖原始错误
            }
            throw new IOException("场景路由写入失败");
        }
    }

    /** 短时共享占用只重试原子替换，最多六次；持续拒绝或中断保持旧配置。 */
    private void replaceAtomically(Path temp) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException denied) {
                if (attempt == 5) {
                    throw denied;
                }
                try {
                    Thread.sleep(20L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("场景路由写入已中断");
                }
            }
        }
    }

    /** 目录项落盘（Windows 不允许对目录通道 force，失败不影响已成功替换）。 */
    private void forceDirectory() {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 文件本身已 force；目录项持久化属于尽力而为
        }
    }

    private static byte[] readBounded(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("场景路由配置文件超过 64 KiB");
            }
            return bytes;
        }
    }

    private static ObjectMapper strictMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        return new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
    }
}
