package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单进程世界的追加式批次日志。每帧为长度、长度反码、JSON 和 SHA-256；
 * 强制落盘后才确认成功，同时持有文件锁阻止第二写入者。
 *
 * <p>仅恢复时移除不完整尾帧；完整损坏、格式版本或运行不匹配均拒绝打开。
 * I/O 失败后本实例停止写入，须关闭并恢复以消除提交结果不确定性。
 * 本层不代替领域状态校验，也不直接修改旧撮合引擎。</p>
 */
public final class WorldJournal implements AutoCloseable {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int DIGEST_BYTES = 32;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String runId;
    /**
     * 位置基准：本文件首帧的确认位置减一。
     *
     * <p>普通运行恒为 0，此时位置等于下标加一，旧档语义与 JSON 逐字节不变。分支子档取
     * 父运行切点位置 p，因此首帧位置为 p+1；{@code position <= base} 属于父档前缀引用，
     * 不由本文件承载。</p>
     */
    private final long base;
    /** 本日志文件的绝对路径；只用只读访问，不参与位置计算。 */
    private final Path file;
    private final FileChannel channel;
    private final List<WorldCommit> history = new ArrayList<>();
    /** 与 {@link #history} 一一对应的帧末字节偏移；用于对"已确认前缀"做原始字节摘要。 */
    private final List<Long> frameEnds = new ArrayList<>();
    private final Map<String, WorldCommit> commands = new LinkedHashMap<>();
    private final Set<String> eventIds = new HashSet<>();
    private final FileLock fileLock;
    private boolean failed;

    /**
     * 打开并核验既有日志；不会截断完整历史。
     * @param file 当前运行专用日志文件
     * @param runId 当前运行标识
     * @throws IOException 文件占用、损坏、运行不匹配或读取失败
     */
    public WorldJournal(Path file, String runId) throws IOException {
        this(file, runId, 0L);
    }

    /**
     * 按显式位置基准打开日志：{@code base} 是首帧位置减一。
     *
     * <p>只用于独立分支子档：父运行已占用 {@code 1..base} 的确认位置，子档首帧必须写
     * {@code base+1}，两段不重叠、也不做平移。缺省基准 0 与旧档行为完全一致，
     * 不改变任何既有 JSON 字段。</p>
     *
     * @param file  当前运行专用日志文件
     * @param runId 当前运行标识
     * @param base  位置基准，必须非负
     * @throws IOException 文件占用、损坏、运行不匹配或读取失败
     * @throws IllegalArgumentException 基准为负或历史首帧位置与基准不符
     */
    public WorldJournal(Path file, String runId, long base) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("当前线程已中断；由上层处理取消后在有效工作线程恢复日志");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("运行标识不能为空");
        }
        if (base < 0) {
            throw new IllegalArgumentException("日志位置基准不能为负：" + base);
        }
        this.runId = runId;
        this.base = base;
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        this.file = absolute;
        channel = FileChannel.open(absolute, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            fileLock = channel.tryLock();
            if (fileLock == null) {
                throw new IOException("世界日志已有写入者");
            }
            recover();
        } catch (IOException | RuntimeException failure) {
            channel.close();
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("世界日志已有写入者", failure);
            }
            throw failure;
        }
    }

    /**
     * 持久提交完整批次。同标识同内容返回原结果，不追加；异内容明确拒绝。
     * @param commandId 幂等命令标识
     * @param commandContent 规范化命令原文
     * @param result 实际回报
     * @param events 有序领域事件
     * @return 已持久确认的批次
     * @throws IOException 写失败或实例已关闭/停止写入；不得据此确认动作成功
     */
    public synchronized WorldCommit commit(String commandId, String commandContent, String result,
                                           List<WorldEvent> events) throws IOException {
        requireWritable();
        WorldCommit previous = commands.get(commandId);
        if (previous != null) {
            if (!previous.commandContent().equals(commandContent)) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return previous;
        }
        WorldCommit batch = new WorldCommit(1, base + history.size() + 1L, runId,
                commandId, commandContent, result, events);
        validateNewEvents(batch);
        byte[] payload = mapper.writeValueAsBytes(batch);
        if (payload.length > MAX_BYTES) {
            throw new IllegalArgumentException("世界提交批次超过 16 MiB");
        }
        ByteBuffer frame = ByteBuffer.allocate(8 + payload.length + DIGEST_BYTES);
        frame.putInt(payload.length).putInt(~payload.length).put(payload).put(digest(payload)).flip();
        try {
            channel.position(channel.size());
            while (frame.hasRemaining()) {
                channel.write(frame);
            }
            channel.force(true);
        } catch (IOException failure) {
            failed = true;
            throw failure;
        }
        remember(batch, channel.position());
        return batch;
    }

    /** @return 已确认批次的不可变历史快照，不包含不完整尾帧 */
    public synchronized List<WorldCommit> commits() {
        return List.copyOf(history);
    }

    /**
     * 读取一条已确认批次；越界返回空。
     *
     * <p>只读访问器：不复制整段历史，供固定位置的历史回看使用。</p>
     *
     * @param position 从 1 开始的确认位置
     * @return 该位置批次；未确认时为空
     */
    public synchronized java.util.Optional<WorldCommit> commitAt(long position) {
        long index = position - base;
        if (index < 1 || index > history.size()) {
            return java.util.Optional.empty();
        }
        WorldCommit candidate = history.get((int) (index - 1));
        return candidate.position() == position
                ? java.util.Optional.of(candidate) : java.util.Optional.empty();
    }

    /**
     * 按位置升序读取一段已确认批次，只复制被选中的条目。
     *
     * @param afterPosition 只返回位置严格大于此值的批次；0 表示从头开始
     * @param limit         本次最多返回条数，必须为正
     * @return 不可变批次列表，可能不足 limit
     */
    public synchronized List<WorldCommit> commitsAfter(long afterPosition, int limit) {
        if (afterPosition < 0) {
            throw new IllegalArgumentException("起始位置不能为负：" + afterPosition);
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("分页条数必须为正：" + limit);
        }
        int start = (int) Math.max(0, Math.min(afterPosition - base, (long) history.size()));
        List<WorldCommit> page = new ArrayList<>();
        for (int index = start; index < history.size() && page.size() < limit; index++) {
            WorldCommit commit = history.get(index);
            if (commit.position() > afterPosition) {
                page.add(commit);
            }
        }
        return List.copyOf(page);
    }

    /**
     * 位置基准：本文件首帧确认位置减一；普通运行为 0。
     *
     * @return 位置基准
     */
    public long base() {
        return base;
    }

    /**
     * 本日志文件的绝对路径；只读访问器，供独立分支的父前缀扫描与来源记录使用。
     *
     * @return 绝对路径
     */
    public Path file() {
        return file;
    }

    /** 关闭日志并释放文件锁；不会删除日志。 */
    @Override
    public synchronized void close() throws IOException {
        if (fileLock.isValid()) {
            fileLock.release();
        }
        channel.close();
    }

    private void recover() throws IOException {
        long confirmed = 0;
        while (confirmed < channel.size()) {
            channel.position(confirmed);
            if (channel.size() - confirmed < 8) {
                truncateTail(confirmed);
                break;
            }
            ByteBuffer header = ByteBuffer.allocate(8);
            readFully(header);
            header.flip();
            int length = header.getInt();
            if (header.getInt() != ~length || length <= 0 || length > MAX_BYTES) {
                throw new IOException("世界日志帧头损坏，位置 " + confirmed);
            }
            if (channel.size() - channel.position() < (long) length + DIGEST_BYTES) {
                truncateTail(confirmed);
                break;
            }
            ByteBuffer body = ByteBuffer.allocate(length + DIGEST_BYTES);
            readFully(body);
            body.flip();
            byte[] payload = new byte[length];
            byte[] checksum = new byte[DIGEST_BYTES];
            body.get(payload).get(checksum);
            if (!MessageDigest.isEqual(checksum, digest(payload))) {
                throw new IOException("世界日志校验和不匹配，位置 " + confirmed);
            }
            try {
                WorldCommit batch = mapper.readValue(payload, WorldCommit.class);
                if (!runId.equals(batch.runId()) || batch.position() != base + history.size() + 1L
                        || commands.containsKey(batch.commandId())) {
                    throw new IOException("世界日志运行、提交位置或命令标识不匹配");
                }
                validateNewEvents(batch);
                remember(batch, channel.position());
            } catch (IllegalArgumentException failure) {
                throw new IOException("世界日志事件不合法", failure);
            }
            confirmed = channel.position();
        }
    }

    private void readFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("世界日志读取时意外结束");
            }
        }
    }

    private void truncateTail(long confirmed) throws IOException {
        channel.truncate(confirmed);
        channel.force(true);
    }

    private void requireWritable() throws IOException {
        if (failed || !channel.isOpen()) {
            throw new IOException("世界日志已关闭或写入失败，必须恢复后再提交");
        }
    }

    private void validateNewEvents(WorldCommit batch) {
        Set<String> newIds = new HashSet<>();
        for (WorldEvent event : batch.events()) {
            if (eventIds.contains(event.eventId()) || !newIds.add(event.eventId())) {
                throw new IllegalArgumentException("重复世界事件标识：" + event.eventId());
            }
        }
    }

    private void remember(WorldCommit batch, long endOffset) {
        history.add(batch);
        frameEnds.add(endOffset);
        commands.put(batch.commandId(), batch);
        batch.events().forEach(event -> eventIds.add(event.eventId()));
    }

    /**
     * 指定确认位置的帧末字节偏移；用于对已确认前缀做原始字节摘要。
     *
     * @param position 确认位置
     * @return 帧末字节偏移；没有该位置返回 -1
     */
    public synchronized long endOffsetOf(long position) {
        long index = position - base;
        if (index < 1 || index > frameEnds.size()) {
            return -1L;
        }
        return frameEnds.get((int) (index - 1));
    }

    /**
     * 对本文件前 {@code endOffset} 个字节计算 SHA-256。
     *
     * <p>必须经由本实例自己的通道读取：Windows 上本实例已对整个文件持有排他锁，同进程的
     * 第二个句柄（或另一个进程）读取会被拒绝，因此父档前缀摘要只能由写入者自己给出。</p>
     *
     * @param endOffset 字节数，必须落在 0..当前文件长度
     * @return 小写十六进制摘要
     * @throws IOException 读取失败或范围越界
     */
    public synchronized String sha256Prefix(long endOffset) throws IOException {
        requireWritable();
        if (endOffset < 0 || endOffset > channel.size()) {
            throw new IOException("摘要范围越界：" + endOffset + "/" + channel.size());
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行环境缺少 SHA-256", impossible);
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
        return java.util.HexFormat.of().formatHex(sha.digest());
    }

    /**
     * 把本文件前 {@code endOffset} 个字节原样导出到一个新文件（封存父前缀）。
     *
     * <p>只经本实例自己的通道读取（Windows 上本实例持有排他锁，别的句柄读不了），
     * 目标必须不存在（{@code CREATE_NEW}），写入后强制落盘。导出的是<b>已确认字节</b>，
     * 因此不会带上任何未提交的半尾。</p>
     *
     * @param target    封存件路径，必须不存在
     * @param endOffset 截止字节数（第 p 帧末）
     * @return 实际导出字节数
     * @throws IOException 读取、创建或写入失败
     */
    public synchronized long exportPrefix(Path target, long endOffset) throws IOException {
        requireWritable();
        if (endOffset < 1 || endOffset > channel.size()) {
            throw new IOException("封存范围越界：" + endOffset + "/" + channel.size());
        }
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            long offset = 0;
            while (offset < endOffset) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), endOffset - offset));
                int read = channel.read(buffer, offset);
                if (read < 0) {
                    throw new IOException("封存读取时意外结束：偏移 " + offset);
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    out.write(buffer);
                }
                offset += read;
            }
            out.force(true);
        }
        return endOffset;
    }

    private byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行环境缺少 SHA-256", impossible);
        }
    }
}
