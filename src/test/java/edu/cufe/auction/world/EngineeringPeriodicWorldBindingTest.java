package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 18c 周期治理 · World 接线检查点：状态字段缺省、真实 Access 绑定与人物/God 投影隔离。
 *
 * <p>只验证已接线的部分。capture/评分/批准/发布链尚在实施中，本类不声明它们通过。</p>
 */
class EngineeringPeriodicWorldBindingTest {

    private static final long DAY = EngineeringModel.DAY_MILLIS;
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String PERSON = "dave";

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-periodic-world");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    @Test
    void periodicStateDefaultsEmptyForOldArchives() {
        EngineeringRun run = new EngineeringRun(WorldWebScenario.engineeringConfig(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        assertNotNull(run.periodic(), "缺省周期状态不能是 null");
        assertTrue(run.periodic().companies().isEmpty(), "旧构造路径没有周期状态");
        assertFalse(run.periodicOf(A).isPresent(), "未初始化公司没有周期");
        EngineeringRun same = run.withPeriodic(run.periodic());
        assertEquals(run.periodic(), same.periodic(), "空状态往返不变");
    }

    @Test
    void accessBindsRealCharterSeatManagerReadingAndRecall() throws Exception {
        Path file = journal("access");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                WorldWebScenario.engineeringConfig())) {
            assertEquals("ea1", new PeriodicWorldAccess(world).seatOf(A, "alice", 0L),
                    "章程席位来自真实 charter");
            assertEquals("ea4", new PeriodicWorldAccess(world).seatOf(A, PERSON, 0L));
            assertEquals(null, new PeriodicWorldAccess(world).seatOf(A, "rita", 0L),
                    "别家公司的人物不是本公司席位");
            assertFalse(new PeriodicWorldAccess(world).isManager(A, PERSON, 0L),
                    "未任命前不是经理");
            assertTrue(new PeriodicWorldAccess(world).canRead(A, "alice", 0L),
                    "席位持有者可读 BOARD");

            governance(world);
            assertTrue(world.isActiveManager(A, PERSON, world.snapshot().timeMillis()),
                    "真实任命后才是经理");
            assertTrue(new PeriodicWorldAccess(world).isManager(A, PERSON,
                    world.snapshot().timeMillis()));
            // 经理身份不等于获得 BOARD 读取权：dave 恰好也是席位，换成非席位经理场景由后续回合覆盖。
            assertTrue(new PeriodicWorldAccess(world).canRead(A, PERSON, world.snapshot()
                    .timeMillis()), "dave 本身是 ea4 席位，按 charter 可读");

            // 真实阅读后 hasRead 才为真，且回忆资格不能凭空为真
            long readAt = 10_000L;
            world.scheduleStartReading("read-b", "read-b-task", readAt, "alice", "b-material", 1L);
            world.advanceTo(readAt + 200L);
            PeriodicWorldAccess access = new PeriodicWorldAccess(world);
            long now = world.snapshot().timeMillis();
            assertTrue(access.hasRead("alice", "b-material", 1L, now), "实读完成后 hasRead 为真");
            assertFalse(access.hasRead("rita", "b-material", 1L, now),
                    "没读过的人没有 hasRead");
            assertFalse(access.hasRead("alice", "b-material", 2L, now), "版本必须精确匹配");
            boolean recalled = access.recallEligible("alice", "b-material", 1L, now);
            if (recalled) {
                assertTrue(access.hasRead("alice", "b-material", 1L, now),
                        "回忆资格不得脱离真实阅读");
            }
            assertFalse(access.recallEligible("rita", "b-material", 1L, now),
                    "没读过的人不可能有回忆资格");
        }
    }

    @Test
    void roleProjectionHidesGodFields() throws Exception {
        Path file = journal("projection");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                WorldWebScenario.engineeringConfig())) {
            // 开局即冻结周期政策，但还没有任何期末记录：投影不能含 position/摘要/God 审计。
            Map<String, Object> view = world.periodicRoleView(PERSON, A);
            assertFalse(view.isEmpty(), "开局应能看到冻结政策下的公司周期视图");
            assertFalse(view.containsKey("position"));
            assertTrue(((List<?>) view.get("periods")).isEmpty(), "未采样前没有期间记录");
            assertFalse(world.periodicGodView(A).isEmpty());
        }
    }

    /** 真实预算 + 任免治理：dave 自 700 起任 ENG-A 经理。 */
    private static void governance(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-mat", "pub-mat-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        String[] persons = {"alice", "bob", "carol", "dave", "erin"};
        for (int i = 0; i < persons.length; i++) {
            world.scheduleStartReading("read-mat-" + persons[i],
                    "read-mat-" + persons[i] + "-task", 20L + i * 2L, persons[i],
                    "b-material", 1L);
            world.scheduleStartReading("read-appt-" + persons[i],
                    "read-appt-" + persons[i] + "-task", 60L + i * 2L, persons[i],
                    "appt-material", 1L);
        }
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L, A, "alice",
                "budget-meeting", 150L, 200L, 300L);
        for (int i = 0; i < persons.length; i++) {
            world.scheduleEngineeringAttend("budget-a-" + i, "budget-a-" + i + "-task",
                    110L + i, A, persons[i], "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L, A, "alice",
                "budget-meeting", "m1", "b-material", 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L, A, "alice",
                "budget-meeting", List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                new BigDecimal("10000.00")),
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal("10000.00"))));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("budget-v-" + i, "budget-v-" + i + "-task",
                    220L + i, A, persons[i], "budget-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringVote("budget-v-4", "budget-v-4-task", 224L, A, "erin",
                "budget-meeting", BoardModel.Vote.NO);
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L, A, "alice",
                "budget-meeting");
        world.scheduleEngineeringAppointmentConvene("mgr-c", "mgr-c-task", 400L, A, "alice",
                "mgr-meeting", 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("mgr-a-" + i, "mgr-a-" + i + "-task",
                    410L + i, A, persons[i], "mgr-meeting", false);
        }
        world.scheduleEngineeringMaterial("mgr-m", "mgr-m-task", 430L, A, "alice",
                "mgr-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("mgr-p", "mgr-p-task", 440L, A, "alice",
                "mgr-meeting", List.of(),
                new BoardModel.AppointmentRequest("mgr-appoint", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, 900L * DAY));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("mgr-v-" + i, "mgr-v-" + i + "-task",
                    520L + i, A, persons[i], "mgr-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("mgr-i", "mgr-i-task", 620L, A, "alice",
                "mgr-meeting");
        world.advanceTo(700L);
    }
}
