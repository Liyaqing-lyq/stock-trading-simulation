package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运行目录索引：{@code runs/index.json}，只描述"有哪些运行、从哪条父运行派生"，不保存业务账。
 *
 * <p>它只是运行管理元数据，不是世界状态的第二份真值：任何金额、持仓、待办都只能来自各自的
 * 日志。写入一律"临时文件 + 原子替换"，因此不会留下半截索引；读取逐条校验，坏条目直接拒绝，
 * 不静默跳过。</p>
 */
public final class BranchRunIndex {

    /** 索引文件名。 */
    public static final String FILE_NAME = "index.json";
    /** 索引格式版本。 */
    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private BranchRunIndex() {
    }

    /**
     * 一条运行索引项。
     *
     * @param runId         运行标识
     * @param parentRunId   父运行标识；普通开局为空串
     * @param parentPosition 派生切点位置；普通开局为 0
     * @param file          日志文件绝对路径
     * @param kind          分支种类；普通开局为空串
     * @param createdAtEpochMillis 创建墙钟毫秒；普通开局为 0
     * @param note          人类可读注记
     * @param sealedPrefix  封存父前缀绝对路径；普通开局为空串
     * @param prefixBytes   封存父前缀字节数；普通开局为 0
     * @param profileDigest 可信画像摘要；普通开局为空串
     * @param profileVersion 画像摘要口径版本；普通开局为空串
     */
    public record Entry(String runId, String parentRunId, long parentPosition, String file,
                        String kind, long createdAtEpochMillis, String note, String sealedPrefix,
                        long prefixBytes, String profileDigest, String profileVersion) {

        /** 校验必填字段。 */
        public Entry {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("索引运行标识不能为空");
            }
            if (file == null || file.isBlank()) {
                throw new IllegalArgumentException("索引日志路径不能为空");
            }
            parentRunId = parentRunId == null ? "" : parentRunId;
            kind = kind == null ? "" : kind;
            note = note == null ? "" : note;
            sealedPrefix = sealedPrefix == null ? "" : sealedPrefix;
            profileDigest = profileDigest == null ? "" : profileDigest;
            profileVersion = profileVersion == null ? "" : profileVersion;
            if (parentPosition < 0 || createdAtEpochMillis < 0 || prefixBytes < 0) {
                throw new IllegalArgumentException("索引位置、创建时刻与封存字节数不能为负");
            }
        }

        /**
         * 由分支来源构造索引项。
         *
         * @param origin    分支来源
         * @param file      子档日志绝对路径
         * @param sealFile  封存父前缀绝对路径
         * @return 索引项
         */
        public static Entry of(BranchOrigin origin, Path file, Path sealFile) {
            return new Entry(origin.childRunId(), origin.parentRunId(), origin.parentPosition(),
                    file.toAbsolutePath().toString(), origin.kind().name(),
                    origin.createdAtEpochMillis(), origin.note(),
                    sealFile.toAbsolutePath().toString(), origin.parentPrefixBytes(),
                    origin.profileDigest(), origin.profileVersion());
        }
    }

    /**
     * 读取索引；文件不存在视为空索引。
     *
     * @param runsDir 运行目录
     * @return 不可变索引项列表，按文件内顺序
     * @throws IOException 文件不可读、格式版本不支持或条目非法
     */
    @SuppressWarnings("unchecked")
    public static List<Entry> read(Path runsDir) throws IOException {
        Path file = runsDir.resolve(FILE_NAME).toAbsolutePath();
        if (!Files.exists(file)) {
            return List.of();
        }
        Map<String, Object> root;
        try {
            root = MAPPER.readValue(Files.readAllBytes(file), Map.class);
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("运行索引不是合法 JSON：" + file, invalid);
        }
        Object version = root.get("version");
        if (!(version instanceof Integer) || ((Integer) version) != VERSION) {
            throw new IOException("运行索引版本不支持：" + version);
        }
        Object entries = root.get("entries");
        if (!(entries instanceof List)) {
            throw new IOException("运行索引缺少 entries 列表");
        }
        List<Entry> parsed = new ArrayList<>();
        for (Object raw : (List<Object>) entries) {
            if (!(raw instanceof Map)) {
                throw new IOException("运行索引条目不是对象");
            }
            Map<String, Object> node = (Map<String, Object>) raw;
            try {
                parsed.add(new Entry(text(node.get("runId"), "runId"),
                        text(node.get("parentRunId"), "parentRunId"),
                        number(node.get("parentPosition"), "parentPosition"),
                        text(node.get("file"), "file"),
                        text(node.get("kind"), "kind"),
                        number(node.get("createdAtEpochMillis"), "createdAtEpochMillis"),
                        text(node.get("note"), "note"),
                        text(node.get("sealedPrefix"), "sealedPrefix"),
                        number(node.get("prefixBytes"), "prefixBytes"),
                        text(node.get("profileDigest"), "profileDigest"),
                        text(node.get("profileVersion"), "profileVersion")));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("运行索引条目非法：" + invalid.getMessage(), invalid);
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * 追加一条索引项并原子替换文件；同标识已存在且内容相同则不重复追加，内容不同则拒绝。
     *
     * @param runsDir 运行目录；不存在时创建
     * @param entry   索引项
     * @throws IOException 读取失败或写入失败
     */
    public static void append(Path runsDir, Entry entry) throws IOException {
        Objects.requireNonNull(runsDir, "runsDir");
        Objects.requireNonNull(entry, "entry");
        Path dir = runsDir.toAbsolutePath();
        Files.createDirectories(dir);
        List<Entry> existing = new ArrayList<>(read(dir));
        for (Entry current : existing) {
            if (current.runId().equals(entry.runId())) {
                if (current.equals(entry)) {
                    return;
                }
                throw new IOException("运行索引已存在不同内容的同名标识：" + entry.runId());
            }
        }
        existing.add(entry);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("entries", existing);
        byte[] payload = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        Path temp = dir.resolve(".index-" + ProcessHandle.current().pid() + "-"
                + System.nanoTime() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(payload));
                channel.force(true);
            }
            Path target = dir.resolve(FILE_NAME);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String text(Object value, String field) throws IOException {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new IOException("运行索引字段类型非法：" + field);
        }
        return (String) value;
    }

    private static long number(Object value, String field) throws IOException {
        if (value == null) {
            return 0L;
        }
        if (!(value instanceof Number)) {
            throw new IOException("运行索引字段类型非法：" + field);
        }
        return ((Number) value).longValue();
    }
}
