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
    private final FileChannel channel;
    private final List<WorldCommit> history = new ArrayList<>();
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
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("当前线程已中断；由上层处理取消后在有效工作线程恢复日志");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("运行标识不能为空");
        }
        this.runId = runId;
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
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
        WorldCommit batch = new WorldCommit(1, history.size() + 1L, runId,
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
        remember(batch);
        return batch;
    }

    /** @return 已确认批次的不可变历史快照，不包含不完整尾帧 */
    public synchronized List<WorldCommit> commits() {
        return List.copyOf(history);
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
                if (!runId.equals(batch.runId()) || batch.position() != history.size() + 1L
                        || commands.containsKey(batch.commandId())) {
                    throw new IOException("世界日志运行、提交位置或命令标识不匹配");
                }
                validateNewEvents(batch);
                remember(batch);
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

    private void remember(WorldCommit batch) {
        history.add(batch);
        commands.put(batch.commandId(), batch);
        batch.events().forEach(event -> eventIds.add(event.eventId()));
    }

    private byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行环境缺少 SHA-256", impossible);
        }
    }
}
