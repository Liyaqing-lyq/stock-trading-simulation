package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Web人物页任职交接：流程权限改变，已读经历不克隆也不凭空消失。 */
class WorldWebMediaPermissionTest {
    @Test
    void appointmentHandoffChangesWorkflowButNeverClonesMemory() throws Exception {
        Path root = Path.of(System.getProperty("web.media.test.root", "target/web-media-tests"));
        Files.createDirectories(root);
        Path dir = Files.createTempDirectory(root, "handoff-");
        AtomicReference<WorldRuntime> runtime = new AtomicReference<>();
        ObjectMapper json = new ObjectMapper();
        try (SceneRoutes routes = SceneRoutes.open(dir);
             WorldWebHost host = new WorldWebHost(() -> {
                 var opened = MediaWebFixture.prepare(dir.resolve("world.journal"));
                 runtime.set(opened.runtime()); return opened;
             }, routes, new LiveDecisionProvider(slot -> {
                 throw new AssertionError("No provider call permitted");
             }))) {
            var reporter = json.valueToTree(host.personPage("sam").media());
            assertEquals(1, reporter.path("stories").path("total").asInt());
            assertEquals(2, reporter.path("documents").path("total").asInt(), "作者只实际读了两份来源");
            assertFalse(reporter.toString().contains("第二版正文"), "写稿不自动生成阅读经历");
            var outsider = json.valueToTree(host.personPage("rita").media());
            assertEquals(0, outsider.path("stories").path("total").asInt());
            assertEquals(0, outsider.path("documents").path("total").asInt());
            var before = host.personPage("bea").view().observations();
            assertFalse(before.isEmpty(), "编辑已经实际读过稿件");
            host.call(() -> {
                var world = runtime.get(); long now = world.snapshot().timeMillis();
                world.scheduleRevokeAppointment("revoke-editor", "revoke-editor-task", now + 1, "MEDIA-3");
                world.advanceTo(now + 1);
                world.scheduleAppointment("new-editor", "new-editor-task", now + 2, "replacement-editor",
                        "rita", "investigative", RoleAccess.Role.EDITOR, now + 2, now + 100000);
                world.advanceTo(now + 2); return null;
            });
            var retired = host.personPage("bea");
            assertEquals(before, retired.view().observations(), "离职不抹去本人实际记住的经历");
            assertEquals(0, json.valueToTree(retired.media()).path("stories").path("total").asInt());
            assertTrue(host.liveCapabilities().persons().stream().noneMatch(p -> p.personPartyId().equals("bea")),
                    "失效任职不能继续在模型轮次中运行");
            var successor = json.valueToTree(host.personPage("rita").media());
            assertEquals(1, successor.path("stories").path("total").asInt(), "继任编辑按授权看本机构流程");
            assertEquals(0, successor.path("documents").path("total").asInt(), "前任阅读正文不会复制给继任者");
            assertFalse(successor.toString().contains("第二版正文"));
        }
    }
}
