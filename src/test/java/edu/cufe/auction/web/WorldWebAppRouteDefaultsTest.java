package edu.cufe.auction.web;

import edu.cufe.auction.app.WorldWebApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 真实入口初始化路由：已有 revision 0 仍是用户配置，新模式只能初始化缺失文件。 */
class WorldWebAppRouteDefaultsTest {
    @TempDir Path root;

    @Test void existingRevisionZeroLegacyFileRemainsByteForByteUnchangedInBothNewModes() throws Exception {
        for (String mode : new String[]{"world-accountable", "world-operating"}) {
            Path work = Files.createDirectory(root.resolve(mode));
            try (var routes = SceneRoutes.open(work)) { assertEquals(0, routes.view().revision()); }
            byte[] before = Files.readAllBytes(work.resolve(SceneRoutes.FILE_NAME));
            initialize(work, mode);
            assertArrayEquals(before, Files.readAllBytes(work.resolve(SceneRoutes.FILE_NAME)), mode);
            try (var routes = SceneRoutes.open(work)) {
                assertEquals(0, routes.view().revision());
                assertTrue(routes.view().routes().stream().allMatch(r -> r.budgetPolicy() == null && r.maxCalls() == 5));
            }
        }
    }

    @Test void freshExplicitModesReceiveFiniteDisabledLifecycleDefaults() throws Exception {
        for (String mode : new String[]{"world-accountable", "world-operating"}) {
            Path work = Files.createDirectory(root.resolve(mode));
            initialize(work, mode);
            try (var routes = SceneRoutes.open(work)) {
                for (var route : routes.view().routes()) {
                    assertFalse(route.enabled());
                    assertEquals("lifecycle-v1", route.budgetPolicy());
                    assertEquals(2500, route.maxCalls());
                    assertEquals(35_000_000, route.maxTotalTokens());
                    assertEquals(2048, route.maxOutputTokens());
                    assertEquals(4, route.maxConcurrency());
                }
            }
        }
    }

    @Test void ordinaryModeKeepsLegacyDefaults() throws Exception {
        Path work = Files.createDirectory(root.resolve("scripted"));
        initialize(work, "scripted");
        try (var routes = SceneRoutes.open(work)) {
            assertEquals(0, routes.view().revision());
            for (var route : routes.view().routes()) {
                assertNull(route.budgetPolicy()); assertFalse(route.enabled());
                assertEquals(5, route.maxCalls()); assertEquals(8192, route.maxTotalTokens());
                assertEquals(512, route.maxOutputTokens()); assertEquals(1, route.maxConcurrency());
            }
        }
    }

    private void initialize(Path work, String mode) throws Exception {
        Path fakeKeys = work.resolve("test-only-fake-keys.txt");
        Files.writeString(fakeKeys, String.join("\n", "sk-fake-aaaaaaaaaaaaaaaa", "sk-fake-bbbbbbbbbbbbbbbb", "sk-fake-cccccccccccccccc", "sk-fake-dddddddddddddddd", "sk-fake-eeeeeeeeeeeeeeee"));
        // Occupy the loopback port so real startup terminates through its own cleanup path.
        // Autonomy is never started, and the only keys are local fake test fixtures.
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var failure = assertThrows(java.io.IOException.class, () -> WorldWebApp.main(new String[]{work.toString(), fakeKeys.toString(), Integer.toString(occupied.getLocalPort()), mode}));
            assertEquals("Web 启动失败：端口不可用", failure.getMessage());
        }
    }
}
