package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.security.*;
import java.util.*;

/** 仅作加速的精确历史信息投影；硬限制条数及保留的序列化字节，不保留世界/提交对象图。 */
final class HistoricalInformationCache {
    static final int MAX_ENTRIES = 64;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private record Key(Object sourceIdentity, String runId, long position) { }
    private record Entry(byte[] commitDigest, byte[] information, long frameTimeMillis) { }
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final ObjectMapper writer;
    private final ObjectMapper reader;
    private int bytes;

    /** sourceIdentity 由运行时独占，只能绑定同一不可变事实源生命周期。 */
    HistoricalInformationCache(ObjectMapper writer, ObjectMapper reader) {
        this.writer = writer;
        this.reader = reader;
    }

    /** 只接受调用方已完成持久确认及原命令核验的帧；普通缓存故障不改变业务结果。 */
    void remember(Object sourceIdentity, WorldCommit commit, InformationModel.State information, long frameTimeMillis) {
        Key key = new Key(sourceIdentity, commit.runId(), commit.position());
        remove(key);
        if (information == null) return;
        try {
            LimitedBytes output = new LimitedBytes();
            writer.writeValue(output, information);
            byte[] payload = output.toByteArray();
            byte[] identity = fingerprint(commit);
            while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES || bytes + payload.length > MAX_BYTES)) {
                remove(entries.keySet().iterator().next());
            }
            entries.put(key, new Entry(identity, payload, frameTimeMillis));
            bytes += payload.length;
        } catch (IOException | RuntimeException optionalFailure) {
            remove(key);
        }
    }

    /** 仍由调用方读取真实目标批次；任一身份或反序列化失败均淘汰并返回未命中。 */
    InformationModel.State find(Object sourceIdentity, WorldCommit commit) {
        Key key = new Key(sourceIdentity, commit.runId(), commit.position());
        Entry entry = entries.get(key);
        if (entry == null) return null;
        try {
            if (!MessageDigest.isEqual(entry.commitDigest(), fingerprint(commit))) {
                remove(key);
                return null;
            }
            return reader.readValue(entry.information(), InformationModel.State.class);
        } catch (IOException | RuntimeException optionalFailure) {
            remove(key);
            return null;
        }
    }

    /** 复用已核验帧的固定时点；真实目标完整身份失配或普通缓存故障均为未命中。 */
    Long findTime(Object sourceIdentity, WorldCommit commit) {
        Key key = new Key(sourceIdentity, commit.runId(), commit.position());
        Entry entry = entries.get(key);
        if (entry == null) return null;
        try {
            if (!MessageDigest.isEqual(entry.commitDigest(), fingerprint(commit))) {
                remove(key);
                return null;
            }
            return entry.frameTimeMillis();
        } catch (IOException | RuntimeException optionalFailure) {
            remove(key);
            return null;
        }
    }

    private void remove(Key key) {
        Entry removed = entries.remove(key);
        if (removed != null) bytes -= removed.information().length;
    }

    /** 释放全部可丢弃投影；不影响任何权威世界状态。 */
    void clear() { entries.clear(); bytes = 0; }

    /** 完整批次身份：固定宽度数值与带长度的 UTF-16 原文，无分隔歧义，事件保持原顺序。 */
    private static byte[] fingerprint(WorldCommit commit) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DataOutputStream out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            out.writeInt(commit.schemaVersion()); out.writeLong(commit.position());
            text(out, commit.runId()); text(out, commit.commandId()); text(out, commit.commandContent()); text(out, commit.result());
            out.writeInt(commit.events().size());
            for (WorldEvent event : commit.events()) {
                text(out, event.eventId()); text(out, event.type()); text(out, event.payload());
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length());
        for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
    }

    /** 超大资料在写出过程中停止分配，而非先构造一个无界 byte[] 再检查。 */
    private static final class LimitedBytes extends OutputStream {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        @Override public void write(int value) throws IOException {
            if (output.size() == MAX_BYTES) throw new IOException("历史信息投影超过缓存字节上限");
            output.write(value);
        }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if ((long) output.size() + length > MAX_BYTES) throw new IOException("历史信息投影超过缓存字节上限");
            output.write(value, offset, length);
        }
        byte[] toByteArray() { return output.toByteArray(); }
    }
}
