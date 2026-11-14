package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 世界日志的只读扫描器：按长度、长度反码、JSON 与 SHA-256 逐帧核对，不取写锁、不建文件、不截尾。
 *
 * <p>用途只有一个：给独立分支读一条<b>别人</b>的日志。父档正被其写入者使用时，本类只打开
 * {@link StandardOpenOption#READ} 通道，因此不加锁、不创建、不截断，也不改动父档一个字节；
 * 这与 {@link WorldJournal} 的构造语义（CREATE + WRITE + 文件锁 + 修不完整尾）刻意不同，
 * 不能互相替代。</p>
 *
 * <p>两条尾部语义必须分开：完整损坏（帧头、长度或校验和不符）在<b>任何</b>位置都抛
 * {@link CorruptFrameException}；只有<b>最后一个</b>帧不完整（字节不够）才记为
 * {@link Scan#tailIncomplete()}，由调用方决定是否接受——分支创建只关心切点之前的完整前缀。</p>
 */
public final class WorldJournalScanner {

    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int DIGEST_BYTES = 32;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorldJournalScanner() {
    }

    /**
     * 一帧在文件里的字节范围与解码结果。
     *
     * @param commit      解码后的批次
     * @param startOffset 帧首字节偏移
     * @param endOffset   帧末字节偏移（不含），即下一帧起点
     */
    public record Frame(WorldCommit commit, long startOffset, long endOffset) {

        /** 校验范围单调。 */
        public Frame {
            if (startOffset < 0 || endOffset <= startOffset) {
                throw new IllegalArgumentException("日志帧字节范围非法：" + startOffset + "-" + endOffset);
            }
        }
    }

    /**
     * 一次只读扫描的结果。
     *
     * @param file           被扫描文件的绝对路径
     * @param runId          首帧声明的运行标识；空文件为 null
     * @param base           位置基准（首帧位置减一）；空文件为 0
     * @param commits        按位置升序的已确认批次
     * @param frames         与 {@code commits} 一一对应的字节范围
     * @param confirmedBytes 最后一个完整帧的末尾偏移，也是前缀字节数
     * @param fileBytes      扫描时观察到的文件长度
     * @param tailIncomplete 末尾是否存在不足一帧的残余字节
     */
    public record Scan(Path file, String runId, long base, List<WorldCommit> commits,
                       List<Frame> frames, long confirmedBytes, long fileBytes,
                       boolean tailIncomplete) {

        /** 冻结列表。 */
        public Scan {
            commits = List.copyOf(commits);
            frames = List.copyOf(frames);
            if (commits.size() != frames.size()) {
                throw new IllegalArgumentException("批次与帧范围数量不一致");
            }
        }

        /** @return 是否读到任何完整帧 */
        public boolean isEmpty() {
            return commits.isEmpty();
        }

        /** @return 最后一个完整帧的确认位置；空文件为 0 */
        public long lastPosition() {
            return commits.isEmpty() ? 0L : commits.get(commits.size() - 1).position();
        }

        /** 是否完整读到指定位置。 @param position 期望位置 @return 存在该位置的完整帧 */
        public boolean hasPosition(long position) {
            if (position < 1) {
                return false;
            }
            for (WorldCommit commit : commits) {
                if (commit.position() == position) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 指定位置帧的末尾字节偏移；用于对父前缀做原始字节摘要。
         *
         * @param position 位置
         * @return 末尾偏移；没有该位置时为空
         */
        public Optional<Long> endOffsetOf(long position) {
            for (Frame frame : frames) {
                if (frame.commit().position() == position) {
                    return Optional.of(frame.endOffset());
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 帧内容不可信：帧头、长度、校验和、JSON 或位置连续性不合格。
     *
     * <p>与"末尾不完整"区分开：这是真实损坏，调用方必须拒绝，不得修补。</p>
     */
    public static final class CorruptFrameException extends IOException {
        private final long offset;

        /**
         * @param message 说明
         * @param offset  出错字节偏移
         */
        public CorruptFrameException(String message, long offset) {
            super(message + "（偏移 " + offset + "）");
            this.offset = offset;
        }

        /** @return 出错字节偏移 */
        public long offset() {
            return offset;
        }
    }

    /**
     * 从文件头扫描到末尾，末尾不完整帧记为 {@link Scan#tailIncomplete()}。
     *
     * @param file 日志文件；必须已存在
     * @return 扫描结果
     * @throws IOException 文件不可读或帧损坏
     */
    public static Scan scan(Path file) throws IOException {
        return scanTo(file, Long.MAX_VALUE);
    }

    /**
     * 扫描到指定位置为止的有界读取：读到该位置即停，之后的字节完全不解析。
     *
     * <p>父档正在追加时，"切点之后"的残余字节与后续帧都不影响已确认前缀；
     * 但切点及其之前的任何损坏都会抛 {@link CorruptFrameException}。</p>
     *
     * @param file          日志文件
     * @param stopAtPosition 读到该位置后停止；{@link Long#MAX_VALUE} 表示读到末尾
     * @return 扫描结果
     * @throws IOException 文件不可读或帧损坏
     */
    public static Scan scanTo(Path file, long stopAtPosition) throws IOException {
        if (stopAtPosition < 1) {
            throw new IllegalArgumentException("扫描截止位置必须为正：" + stopAtPosition);
        }
        Path absolute = file.toAbsolutePath();
        List<WorldCommit> commits = new ArrayList<>();
        List<Frame> frames = new ArrayList<>();
        String runId = null;
        long base = 0;
        long fileBytes;
        try (FileChannel channel = FileChannel.open(absolute, StandardOpenOption.READ)) {
            fileBytes = channel.size();
            long offset = 0;
            boolean stopped = false;
            while (offset < fileBytes && !stopped) {
                if (fileBytes - offset < 8) {
                    break; // 末尾残余，不构成完整帧
                }
                ByteBuffer header = ByteBuffer.allocate(8);
                readFully(channel, offset, header);
                header.flip();
                int length = header.getInt();
                if (header.getInt() != ~length || length <= 0 || length > MAX_BYTES) {
                    throw new CorruptFrameException("世界日志帧头损坏", offset);
                }
                if (fileBytes - offset - 8 < (long) length + DIGEST_BYTES) {
                    break; // 末尾帧不完整
                }
                ByteBuffer body = ByteBuffer.allocate(length + DIGEST_BYTES);
                readFully(channel, offset + 8, body);
                body.flip();
                byte[] payload = new byte[length];
                byte[] checksum = new byte[DIGEST_BYTES];
                body.get(payload).get(checksum);
                if (!MessageDigest.isEqual(checksum, digest(payload))) {
                    throw new CorruptFrameException("世界日志校验和不匹配", offset);
                }
                WorldCommit commit;
                try {
                    commit = MAPPER.readValue(payload, WorldCommit.class);
                } catch (IOException | RuntimeException invalid) {
                    throw new CorruptFrameException("世界日志帧 JSON 不合法：" + invalid.getMessage(),
                            offset);
                }
                if (runId == null) {
                    runId = commit.runId();
                    base = commit.position() - 1L;
                    if (base < 0) {
                        throw new CorruptFrameException("世界日志首帧位置非法", offset);
                    }
                } else if (!runId.equals(commit.runId())) {
                    throw new CorruptFrameException("世界日志运行标识在同一文件内改变", offset);
                }
                long expected = base + commits.size() + 1L;
                if (commit.position() != expected) {
                    throw new CorruptFrameException("世界日志提交位置不连续：期望 " + expected
                            + "，实际 " + commit.position(), offset);
                }
                long end = offset + 8L + length + DIGEST_BYTES;
                commits.add(commit);
                frames.add(new Frame(commit, offset, end));
                offset = end;
                stopped = commit.position() >= stopAtPosition;
            }
            boolean tail = !stopped && offset < fileBytes;
            return new Scan(absolute, runId, base, commits, frames, offset, fileBytes, tail);
        }
    }

    /**
     * 计算文件前 {@code endOffset} 个字节的 SHA-256；用于父档前缀原始字节摘要。
     *
     * <p>只读流式读取，不把整个父档读进内存，也不改动文件。</p>
     *
     * @param file      日志文件
     * @param endOffset 字节数，必须在 0..文件长度之间
     * @return 小写十六进制摘要
     * @throws IOException 文件不可读
     */
    public static String sha256Prefix(Path file, long endOffset) throws IOException {
        if (endOffset < 0) {
            throw new IllegalArgumentException("摘要字节数不能为负：" + endOffset);
        }
        MessageDigest sha = sha256();
        try (FileChannel channel = FileChannel.open(file.toAbsolutePath(), StandardOpenOption.READ)) {
            if (endOffset > channel.size()) {
                throw new IOException("摘要范围超过文件长度：" + endOffset + ">" + channel.size());
            }
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long offset = 0;
            while (offset < endOffset) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), endOffset - offset));
                int read = channel.read(buffer, offset);
                if (read < 0) {
                    throw new IOException("摘要读取时意外结束：偏移 " + offset);
                }
                buffer.flip();
                sha.update(buffer);
                offset += read;
            }
        }
        return hex(sha.digest());
    }

    /** @return 文件长度 */
    public static long size(Path file) throws IOException {
        return Files.size(file.toAbsolutePath());
    }

    private static void readFully(FileChannel channel, long position, ByteBuffer buffer)
            throws IOException {
        long offset = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                throw new CorruptFrameException("世界日志读取时意外结束", offset);
            }
            offset += read;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行环境缺少 SHA-256", impossible);
        }
    }

    private static byte[] digest(byte[] payload) {
        return sha256().digest(payload);
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
