package edu.cufe.auction.world;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 帧长度高位标记无损gzip；旧正长度帧原样读取，解压后仍受16MiB上限约束。 */
final class JournalFrameCodec {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private JournalFrameCodec() { }

    static byte[] encode(byte[] json) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(bytes)) { zip.write(json); }
        return bytes.toByteArray();
    }

    static byte[] decode(byte[] payload, int headerLength) throws IOException {
        if (headerLength >= 0) return payload;
        try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            byte[] json = zip.readNBytes(MAX_BYTES + 1);
            if (json.length > MAX_BYTES) throw new IOException("世界日志解压批次超过16MiB");
            return json;
        }
    }
}
