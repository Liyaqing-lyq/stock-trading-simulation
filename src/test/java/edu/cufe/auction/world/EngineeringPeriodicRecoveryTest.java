package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 恢复门：即使把批次帧的长度与 SHA 都重算成自洽，周期快照的自洽换值仍必须按原命令重演拒绝。
 */
class EngineeringPeriodicRecoveryTest {

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-periodic-recovery-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    /** 把第一个含 statementDigest 的批次改成另一位摘要，并重算长度与帧 SHA。 */
    private static void tamperStatementDigest(Path file) throws Exception {
        byte[] all = Files.readAllBytes(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        boolean done = false;
        while (offset < all.length) {
            int length = ByteBuffer.wrap(all, offset, 4).getInt();
            int complement = ByteBuffer.wrap(all, offset + 4, 4).getInt();
            assertEquals(~length, complement, "帧长度反码不一致");
            byte[] payload = Arrays.copyOfRange(all, offset + 8, offset + 8 + length);
            byte[] digest = Arrays.copyOfRange(all, offset + 8 + length,
                    offset + 8 + length + 32);
            if (!done) {
                String text = new String(payload, StandardCharsets.UTF_8);
                // 状态快照是作为事件 payload 的 JSON 字符串再嵌入提交 JSON，因此键被二次转义。
                String key = "\\\"statementDigest\\\":\\\"";
                int index = text.indexOf(key);
                if (index > 0) {
                    int start = index + key.length();
                    String flipped = (text.charAt(start) == '0' ? "1" : "0")
                            + text.substring(start + 1, start + 64);
                    payload = (text.substring(0, start) + flipped
                            + text.substring(start + 64)).getBytes(StandardCharsets.UTF_8);
                    digest = MessageDigest.getInstance("SHA-256").digest(payload);
                    done = true;
                }
            }
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length)
                    .array());
            out.write(payload);
            out.write(digest);
            offset += 8 + length + 32;
        }
        Files.write(file, out.toByteArray());
    }

    @Test
    void selfConsistentDigestSwapIsRejectedOnRecovery() throws Exception {
        Path file = journal("tamper");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                WorldWebScenario.engineeringConfig());
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
        world.close();
        tamperStatementDigest(file);
        assertThrows(Exception.class, () -> WorldWebScenario.engineeringRuntime(file,
                WorldWebScenario.engineeringConfig()));
    }
}
