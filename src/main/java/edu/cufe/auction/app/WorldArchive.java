package edu.cufe.auction.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.WorldJournalScanner;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** 本机管理员只读历史入口，在没有原业务模块时也能检查和保存原始日志前缀。 */
final class WorldArchive {
    private static final ObjectMapper JSON = new ObjectMapper();

    private WorldArchive() { }

    static JsonNode run(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5 || !Set.of("inspect", "show", "export").contains(args[1])
                || ("export".equals(args[1]) ? args.length != 5 : args.length > 4)) {
            throw new IllegalArgumentException("用法：WorldWebApp --archive inspect|show 日志 [位置|end] 或 --archive export 日志 位置|end 新文件");
        }
        Path journal = Path.of(args[2]);
        long position = args.length == 3 || args[3].equals("end") ? Long.MAX_VALUE : Long.parseLong(args[3]);
        Path output = args.length == 5 ? Path.of(args[4]).toAbsolutePath() : null;
        Path staging = output == null ? null : Files.createTempFile(output.getParent(), ".world-archive-", ".tmp");
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            WorldJournalScanner.Prefix prefix;
            try (var stream = new DigestOutputStream(staging == null ? OutputStream.nullOutputStream()
                    : Files.newOutputStream(staging), sha)) {
                prefix = WorldJournalScanner.readPrefix(journal, position, stream);
            }
            if (prefix.last() == null || position != Long.MAX_VALUE && prefix.last().position() != position)
                throw new IOException("指定位置没有完整日志帧：" + position);
            var first = prefix.first();
            if (!Set.of("__open__", "__branch__").contains(first.commandId())) throw new IOException("首帧缺少运行开局或分支声明");
            var opening = JSON.readTree(first.commandContent());
            var manifest = opening.required("manifest");
            if (!first.runId().equals(manifest.required("runId").textValue())) throw new IOException("运行清单与日志标识不一致");
            var result = JSON.createObjectNode().put("mode", "READ_ONLY_ARCHIVE").put("verification", "FRAME_INTEGRITY_ONLY")
                    .put("runId", first.runId()).put("firstPosition", first.position()).put("position", prefix.last().position())
                    .put("confirmedBytes", prefix.confirmedBytes()).put("sourceBytesAtOpen", prefix.fileBytes())
                    .put("tailIncompleteAtScanEnd", prefix.tailIncomplete()).put("sha256", HexFormat.of().formatHex(sha.digest()));
            result.set("manifest", manifest);
            if (opening.has("branch")) result.set("branch", opening.get("branch"));
            result.put("commandId", prefix.last().commandId());
            if (args[1].equals("show")) result.set("commit", JSON.valueToTree(prefix.last()));
            if (staging != null) Files.move(staging, output);
            return result;
        } finally {
            if (staging != null) Files.deleteIfExists(staging);
        }
    }
}
