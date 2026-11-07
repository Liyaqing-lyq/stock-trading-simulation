package edu.cufe.auction.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import com.sun.nio.file.ExtendedOpenOption;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 五场景路由配置：默认值、保存/重开、并发冲突、真实写失败与严格 JSON。
 */
class SceneRoutesTest {

    private static Path work(String name) throws IOException {
        Path dir = Path.of("target", "web-test", "routes", name + "-" + System.nanoTime());
        Files.createDirectories(dir);
        return dir;
    }

    private static List<SceneRoutes.Route> defaultRoutes(int calls, String model) {
        List<SceneRoutes.Route> routes = new ArrayList<>();
        for (SceneRoutes.Scene scene : SceneRoutes.Scene.values()) {
            routes.add(new SceneRoutes.Route(scene, "key-" + (scene.ordinal() + 1), model, false,
                    calls, 8192, 512, 1));
        }
        return routes;
    }

    @Test
    @DisplayName("初次打开写入默认五路：revision 0、顺序固定、默认关闭、文件无秘密与地址")
    void createsDefaults() throws Exception {
        Path dir = work("defaults");
        try (SceneRoutes routes = SceneRoutes.open(dir)) {
            SceneRoutes.Snapshot snapshot = routes.view();
            assertEquals(0L, snapshot.revision());
            assertEquals(5, snapshot.routes().size());
            for (int index = 0; index < 5; index++) {
                SceneRoutes.Route route = snapshot.routes().get(index);
                assertEquals(SceneRoutes.Scene.values()[index], route.scene());
                assertEquals("key-" + (index + 1), route.slotId());
                assertEquals(SceneRoutes.MODEL_FLASH, route.model());
                assertFalse(route.enabled());
                assertEquals(5, route.maxCalls());
                assertEquals(8192, route.maxTotalTokens());
                assertEquals(512, route.maxOutputTokens());
                assertEquals(1, route.maxConcurrency());
            }
        }
        String text = Files.readString(dir.resolve(SceneRoutes.FILE_NAME));
        assertFalse(text.contains("sk-"));
        assertFalse(text.contains("apiKey"));
        assertFalse(text.contains("https://"));
        assertFalse(text.contains("\"label\""));
        assertTrue(text.contains("\"revision\":0"));
    }

    @Test
    @DisplayName("替换后 revision 加一，重开保持，旧 revision 冲突")
    void replacePersistsAndReopens() throws Exception {
        Path dir = work("replace");
        try (SceneRoutes routes = SceneRoutes.open(dir)) {
            routes.replace(0L, defaultRoutes(9, SceneRoutes.MODEL_PRO));
            assertEquals(1L, routes.view().revision());
            assertEquals(SceneRoutes.MODEL_PRO, routes.view().routes().get(0).model());
            assertEquals(9, routes.view().routes().get(0).maxCalls());
        }
        try (SceneRoutes reopened = SceneRoutes.open(dir)) {
            assertEquals(1L, reopened.view().revision());
            assertEquals(9, reopened.view().routes().get(0).maxCalls());
            SceneRoutes.RevisionMismatch mismatch = assertThrows(
                    SceneRoutes.RevisionMismatch.class,
                    () -> reopened.replace(0L, defaultRoutes(5, SceneRoutes.MODEL_FLASH)));
            assertEquals(1L, mismatch.currentRevision());
            assertEquals(1L, reopened.view().revision());
        }
    }

    @Test
    @DisplayName("非法路由拒绝且 revision 与文件不变")
    void rejectsInvalidRoutes() throws Exception {
        Path dir = work("invalid");
        try (SceneRoutes routes = SceneRoutes.open(dir)) {
            byte[] before = Files.readAllBytes(dir.resolve(SceneRoutes.FILE_NAME));

            assertThrows(IllegalArgumentException.class,
                    () -> routes.replace(0L, defaultRoutes(5, "gpt-4o")));
            assertThrows(IllegalArgumentException.class,
                    () -> routes.replace(0L, defaultRoutes(0, SceneRoutes.MODEL_FLASH)));
            assertThrows(IllegalArgumentException.class,
                    () -> routes.replace(0L, defaultRoutes(101, SceneRoutes.MODEL_FLASH)));
            assertThrows(IllegalArgumentException.class, () -> routes.replace(0L, List.of()));
            List<SceneRoutes.Route> duplicate = new ArrayList<>(defaultRoutes(5,
                    SceneRoutes.MODEL_FLASH));
            duplicate.set(1, duplicate.get(0));
            assertThrows(IllegalArgumentException.class, () -> routes.replace(0L, duplicate));

            assertEquals(0L, routes.view().revision());
            assertArrayEqualsBytes(before,
                    Files.readAllBytes(dir.resolve(SceneRoutes.FILE_NAME)));
        }
    }

    @Test
    @DisplayName("预算边界与槽位/模型枚举逐项校验")
    void budgetBounds() {
        assertThrows(IllegalArgumentException.class, () -> new SceneRoutes.Route(
                SceneRoutes.Scene.GOVERNANCE, "key-6", SceneRoutes.MODEL_FLASH, false,
                5, 8192, 512, 1));
        assertThrows(IllegalArgumentException.class, () -> new SceneRoutes.Route(
                SceneRoutes.Scene.GOVERNANCE, "key-1", SceneRoutes.MODEL_FLASH, false,
                5, 255, 128, 1));
        assertThrows(IllegalArgumentException.class, () -> new SceneRoutes.Route(
                SceneRoutes.Scene.GOVERNANCE, "key-1", SceneRoutes.MODEL_FLASH, false,
                5, 8192, 8193, 1));
        assertThrows(IllegalArgumentException.class, () -> new SceneRoutes.Route(
                SceneRoutes.Scene.GOVERNANCE, "key-1", SceneRoutes.MODEL_FLASH, false,
                2, 8192, 512, 3));
        assertNotNull(new SceneRoutes.Route(SceneRoutes.Scene.EVALUATION, "key-5",
                SceneRoutes.MODEL_PRO, true, 100, 1_000_000, 8192, 4));
    }

    @Test
    @DisplayName("配置文件严格 JSON：未知字段、重复键、尾随、浮点整数、null 原语全部拒绝")
    void strictFileJson() throws Exception {
        Path base = work("strict");
        String valid = "{\"revision\":0,\"routes\":["
                + "{\"scene\":\"governance\",\"slotId\":\"key-1\",\"model\":\"deepseek-flash\","
                + "\"enabled\":false,\"maxCalls\":5,\"maxTotalTokens\":8192,"
                + "\"maxOutputTokens\":512,\"maxConcurrency\":1}"
                + "] }";
        assertThrows(IOException.class, () -> writeAndOpen(base.resolve("short"), valid));
        assertThrows(IOException.class, () -> writeAndOpen(base.resolve("unknown"),
                valid.replace("\"maxCalls\":5", "\"maxCalls\":5,\"extra\":1")));
        assertThrows(IOException.class, () -> writeAndOpen(base.resolve("dup"),
                valid.replace("\"maxCalls\":5", "\"maxCalls\":5,\"maxCalls\":6")));
        assertThrows(IOException.class, () -> writeAndOpen(base.resolve("trailing"),
                valid + " {}"));
        assertThrows(IOException.class, () -> writeAndOpen(base.resolve("float"),
                valid.replace("\"maxCalls\":5", "\"maxCalls\":5.5")));
        assertThrows(IOException.class, () -> writeAndOpen(base.resolve("null"),
                "{\"revision\":0,\"routes\":null}"));
        assertThrows(IOException.class, () -> writeAndOpen(base.resolve("oversize"),
                "{\"revision\":0,\"routes\":[" + " ".repeat(SceneRoutes.MAX_BYTES) + "]}"));
    }

    @Test
    @DisplayName("真实写失败：目标文件只读时抛 IOException，revision 与旧内容保持")
    void writeFailureKeepsOldState() throws Exception {
        Path dir = work("readonly");
        try (SceneRoutes routes = SceneRoutes.open(dir)) {
            Path file = dir.resolve(SceneRoutes.FILE_NAME);
            byte[] before = Files.readAllBytes(file);
            boolean locked = file.toFile().setWritable(false);
            try {
                assertTrue(locked, "需要能设置只读");
                assertThrows(IOException.class,
                        () -> routes.replace(0L, defaultRoutes(7, SceneRoutes.MODEL_PRO)));
                assertEquals(0L, routes.view().revision());
                assertArrayEqualsBytes(before, Files.readAllBytes(file));
            } finally {
                file.toFile().setWritable(true);
            }
        }
    }

    @Test
    @DisplayName("并发替换：同一 revision 只有一个成功")
    void concurrentReplace() throws Exception {
        Path dir = work("concurrent");
        try (SceneRoutes routes = SceneRoutes.open(dir)) {
            List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
            Thread first = new Thread(() -> tryReplace(routes, errors));
            Thread second = new Thread(() -> tryReplace(routes, errors));
            first.start();
            second.start();
            first.join(5_000L);
            second.join(5_000L);
            assertEquals(1L, routes.view().revision());
            assertEquals(1, errors.size(), String.valueOf(errors));
            assertTrue(errors.get(0) instanceof SceneRoutes.RevisionMismatch);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 短时禁止删除共享：原子替换受阻后恢复，配置只提交一次")
    void transientSharingDenialRecovers() throws Exception {
        Path dir = work("transient-sharing");
        try (SceneRoutes routes = SceneRoutes.open(dir)) {
            Path file = dir.resolve(SceneRoutes.FILE_NAME);
            var release = Executors.newSingleThreadScheduledExecutor();
            try (FileChannel held = FileChannel.open(file, StandardOpenOption.READ,
                    ExtendedOpenOption.NOSHARE_DELETE)) {
                Path probe = dir.resolve("probe.tmp");
                Files.writeString(probe, "probe");
                assertThrows(java.nio.file.AccessDeniedException.class, () -> Files.move(
                        probe, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING));
                Files.delete(probe);
                var closed = release.schedule(() -> { held.close(); return true; },
                        75, TimeUnit.MILLISECONDS);
                routes.replace(0L, defaultRoutes(9, SceneRoutes.MODEL_PRO));
                assertTrue(closed.get(2, TimeUnit.SECONDS));
                assertEquals(1L, routes.view().revision());
                try (SceneRoutes reopened = SceneRoutes.open(dir)) {
                    assertEquals(routes.view(), reopened.view());
                }
                try (var files = Files.list(dir)) {
                    assertEquals(List.of(file), files.toList());
                }
            } finally {
                release.shutdownNow();
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 持续禁止删除共享：有界失败且文件、revision不变，无临时残留")
    void permanentSharingDenialKeepsOldState() throws Exception {
        Path dir = work("permanent-sharing");
        try (SceneRoutes routes = SceneRoutes.open(dir)) {
            Path file = dir.resolve(SceneRoutes.FILE_NAME);
            byte[] before = Files.readAllBytes(file);
            try (FileChannel held = FileChannel.open(file, StandardOpenOption.READ,
                    ExtendedOpenOption.NOSHARE_DELETE)) {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                        assertThrows(IOException.class, () -> routes.replace(0L,
                                defaultRoutes(9, SceneRoutes.MODEL_PRO))));
                assertEquals(0L, routes.view().revision());
                assertArrayEqualsBytes(before, Files.readAllBytes(file));
                try (var files = Files.list(dir)) {
                    assertEquals(List.of(file), files.toList());
                }
            }
        }
    }

    private static void tryReplace(SceneRoutes routes, List<Throwable> errors) {
        try {
            routes.replace(0L, defaultRoutes(6, SceneRoutes.MODEL_FLASH));
        } catch (Throwable failure) {
            errors.add(failure);
        }
    }

    private static void writeAndOpen(Path dir, String text) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SceneRoutes.FILE_NAME), text, StandardCharsets.UTF_8);
        SceneRoutes.open(dir);
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual[index], "字节 " + index);
        }
    }
}
