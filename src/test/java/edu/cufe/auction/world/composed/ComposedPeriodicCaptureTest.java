package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankActions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定向周期语义：默认定期治理 + 采样不署名 + 两期冻结累计派生单期流量。
 *
 * <p>只使用旧档也存在的 API，因此同一份测试对 checkpoint-6（无自动排程、采样冒充主席署名）
 * 会真实失败；对本轮修复后的组合档应通过。断言全部基于真实待办、真实日志快照与正文，不读私有状态。</p>
 */
class ComposedPeriodicCaptureTest {

    private static final String NORTH = ComposedWorldScenario.BANK;
    private static final String CUFE = WorldWebScenario.COMPANY;
    private static final long DAY = 86_400_000L;

    /** 开局：额外治理公司自动拥有第 1 期期末采样待办（唯一）。 */
    @Test
    void openingSchedulesFirstPeriodCaptureOnce() throws Exception {
        Path dir = Files.createTempDirectory("periodic-default").resolve("world.log");
        try (WorldRuntime runtime = ComposedWorldScenario.open(dir)) {
            List<PendingTask> captures = new ArrayList<>();
            for (PendingTask task : runtime.snapshot().pending()) {
                if (PendingTask.PERIOD_CAPTURE_V1.equals(task.kind())
                        && task.taskId().endsWith(":" + NORTH + ":1")) {
                    captures.add(task);
                }
            }
            assertEquals(1, captures.size(), "第 1 期期末采样待办必须唯一，实际 "
                    + captures.size());
            long expected = PeriodicModel.PERIOD_MILLIS;
            assertEquals(expected, captures.get(0).dueTimeMillis(),
                    "第 1 期采样必须落在真实期界");
        }
    }

    /** 采样只冻结真值：不发布任何人物署名资料；经理 PREPARE 才发布，并把下一期任务派生出来。 */
    @Test
    void captureFreezesTruthWithoutPublishingMaterial() throws Exception {
        Path dir = Files.createTempDirectory("periodic-capture").resolve("world.log");
        try (WorldRuntime runtime = ComposedWorldScenario.open(dir)) {
            PeriodicModel.Policy policy = runtime.companyGovernance().orElseThrow()
                    .periodsOf(NORTH).policy();
            // 真实任免必须先存在（经理才能编制）：复用组合档同一条真实会议链。
            ComposedWorldSelfTest.appoint(runtime, runtime.snapshot().timeMillis(), NORTH, "erin",
                    "north-mgr", "north-mgr");
            long boundary = PeriodicModel.PERIOD_MILLIS;
            long opensAt = boundary + policy.materialLeadMillis();
            runtime.advanceTo(boundary);

            PeriodicModel.Snapshot snapshot = runtime.companyGovernance().orElseThrow()
                    .periodsOf(NORTH).snapshots().stream()
                    .filter(s -> s.period() == 1L).findFirst().orElse(null);
            assertNotNull(snapshot, "第 1 期必须真实采样（任务到期即可触发，不需手动排程）");
            assertFalse(snapshot.materialDocId().isBlank(), "采样必须冻结未来材料编号");
            String futureDoc = "periodic-material:" + NORTH + ":1";
            assertFalse(hasDocument(runtime, futureDoc),
                    "采样阶段不得发布任何材料（更不得借主席署名）：" + futureDoc);

            long next = runtime.snapshot().pending().stream()
                    .filter(task -> task.taskId().equals("periodic-capture:" + NORTH + ":2"))
                    .count();
            assertEquals(1L, next, "采样必须派生唯一一条第 2 期采样待办");

            // 经理真实 PREPARE 才发布董事材料，作者必须是经理本人。
            long prepareAt = opensAt + 2L;
            runtime.schedulePeriodicAction("pp-prepare-1-1", "pp-prepare-1-1-t", prepareAt,
                    NORTH, "erin", "PREPARE", 1L, "", 1L, "", 0L, "", List.of(), "", 1L);
            runtime.advanceTo(prepareAt);
            InformationModel.Document draft = newestDocument(runtime, "periodic-draft-doc:" + NORTH);
            assertNotNull(draft, "PREPARE 后必须有真实草稿资料");
            assertEquals("erin", draft.publisherPartyId(), "材料作者必须是真实经理，不是系统或主席");
        }
    }

    /** 真实非零跨期账：期 1 本期=累计；期 2 本期=累计2−累计1，且累计≠本期。 */
    @Test
    void periodFlowDerivedFromTwoFrozenCumulativePoints() throws Exception {
        Path dir = Files.createTempDirectory("periodic-flow").resolve("world.log");
        try (WorldRuntime runtime = ComposedWorldScenario.open(dir)) {
            PeriodicModel.Policy policy = runtime.companyGovernance().orElseThrow()
                    .periodsOf(NORTH).policy();
            long start = runtime.snapshot().timeMillis();
            ComposedWorldSelfTest.appoint(runtime, start, NORTH, "erin", "flow-north",
                    "flow-north");
            revokeA4(runtime, start);
            ComposedWorldSelfTest.appoint(runtime, start + 60L, CUFE, "dave", "flow-cufe",
                    "flow-cufe");
            loan(runtime, 10L * DAY, 40L * DAY);
            long lead = policy.materialLeadMillis();
            BigDecimal[] one = prepareAndRead(runtime, 1L, lead);
            assertNotNull(one, "第 1 期必须有可读正文");
            assertTrue(one[0].signum() != 0, "期 1 本期损益必须非零（真实计息），实际 " + one[0]);
            assertEquals(0, one[0].compareTo(one[1]),
                    "没有上一期冻结快照时，本期损益必须等于累计损益");

            long secondBoundary = PeriodicModel.PERIOD_MILLIS * 2L;
            runtime.advanceTo(secondBoundary);
            BigDecimal[] two = prepareAndRead(runtime, 2L, lead);
            assertNotNull(two, "第 2 期必须有可读正文");
            assertEquals(0, two[0].compareTo(two[1].subtract(one[1])),
                    "本期损益必须等于「本期累计 − 上一期冻结累计」：本期=" + two[0]
                            + " 本期累计=" + two[1] + " 上期累计=" + one[1]);
            assertTrue(two[0].signum() != 0, "期 2 有真实新增计息，本期必须非零");
            assertTrue(two[1].compareTo(two[0]) != 0, "累计不得等于本期");
        }
    }

    /** 期 2 无新业务：本期 0，但累计保持非零。 */
    @Test
    void periodFlowZeroWhenNoNewBusiness() throws Exception {
        Path dir = Files.createTempDirectory("periodic-flow-zero").resolve("world.log");
        try (WorldRuntime runtime = ComposedWorldScenario.open(dir)) {
            PeriodicModel.Policy policy = runtime.companyGovernance().orElseThrow()
                    .periodsOf(NORTH).policy();
            long start = runtime.snapshot().timeMillis();
            ComposedWorldSelfTest.appoint(runtime, start, NORTH, "erin", "zero-north",
                    "zero-north");
            revokeA4(runtime, start);
            ComposedWorldSelfTest.appoint(runtime, start + 60L, CUFE, "dave", "zero-cufe",
                    "zero-cufe");
            loan(runtime, 10L * DAY, 0L);
            long lead = policy.materialLeadMillis();
            BigDecimal[] one = prepareAndRead(runtime, 1L, lead);
            assertNotNull(one);
            assertTrue(one[1].signum() != 0, "期 1 累计必须非零（真实计息），实际 " + one[1]);

            runtime.advanceTo(PeriodicModel.PERIOD_MILLIS * 2L);
            BigDecimal[] two = prepareAndRead(runtime, 2L, lead);
            assertNotNull(two);
            assertEquals(0, two[0].signum(), "期 2 无新业务，本期损益必须为 0，实际 " + two[0]);
            assertEquals(0, two[1].compareTo(one[1]), "无新业务时累计不得变化");
            assertTrue(two[1].signum() != 0, "累计必须保持非零");
        }
    }

    /** 解除 dave 在 cufe 的旧兼任（A-4），否则无法再任 cufe 经理。 */
    private static void revokeA4(WorldRuntime runtime, long start) throws Exception {
        long due = runtime.snapshot().timeMillis() + 2;
        runtime.scheduleRevokeAppointment("rv-A-4", "rv-A-4-t", due, "A-4");
        runtime.advanceTo(due);
    }

    /** 一笔真实公司贷款 + 指定时点的真实计息（第一个时点必填，第二个可省略）。 */
    private static void loan(WorldRuntime runtime, long firstAccrual, long secondAccrual)
            throws Exception {
        WorldRoleSession dave = runtime.openRoleSession("dave");
        WorldRoleSession erin = runtime.openRoleSession("erin");
        long tenor = 300L * DAY;
        String purpose = "跨期计息";
        ComposedWorldSelfTest.loanAction(runtime, dave, "ln-req", BankActions.Kind.LOAN_REQUEST,
                "cufe", "200.00", 20_000L, tenor, purpose);
        ComposedWorldSelfTest.loanAction(runtime, dave, "ln-acc", BankActions.Kind.LOAN_ACCEPT,
                "cufe", "200.00", 20_000L, tenor, purpose);
        ComposedWorldSelfTest.loanAction(runtime, erin, "ln-app", BankActions.Kind.LOAN_APPROVE,
                "cufe", "200.00", 20_000L, tenor, purpose);
        ComposedWorldSelfTest.loanAction(runtime, dave, "ln-draw", BankActions.Kind.LOAN_DRAW,
                "cufe", "200.00", 20_000L, tenor, purpose);
        runtime.scheduleLoanAccrual("accr-p1", "accr-p1-t", "ln-req", firstAccrual);
        if (secondAccrual > 0L) {
            runtime.scheduleLoanAccrual("accr-p2", "accr-p2-t", "ln-req", secondAccrual);
        }
    }

    /**
     * 推进到第 period 期窗口，由经理 PREPARE，返回 [本期损益, 累计损益]。
     */
    private static BigDecimal[] prepareAndRead(WorldRuntime runtime, long period, long lead)
            throws Exception {
        long boundary = PeriodicModel.PERIOD_MILLIS * period;
        runtime.advanceTo(boundary);
        long prepareAt = boundary + lead + 2L;
        runtime.schedulePeriodicAction("pp-prepare-" + period + "-1",
                "pp-prepare-" + period + "-1-t", prepareAt, NORTH, "erin", "PREPARE", period,
                "", 1L, "", 0L, "", List.of(), "", 1L);
        runtime.advanceTo(prepareAt);
        InformationModel.Document doc = newestDocument(runtime,
                "periodic-draft-doc:" + NORTH + ":" + period);
        return doc == null ? null : parseFlow(bodyOf(doc));
    }

    /** 资料最新版本正文（公开访问器，不依赖包内工具）。 */
    private static String bodyOf(InformationModel.Document doc) {
        return doc.versions().get(doc.versions().size() - 1).body();
    }

    /** @return [本期损益, 累计损益] */
    private static BigDecimal[] parseFlow(String body) {
        return new BigDecimal[] {line(body, "本期损益="), line(body, "累计损益=")};
    }

    private static BigDecimal line(String body, String prefix) {
        for (String raw : body.split("\n")) {
            String text = raw.trim();
            if (text.startsWith(prefix)) {
                return new BigDecimal(text.substring(prefix.length()).trim());
            }
        }
        throw new IllegalStateException("正文缺少口径行：" + prefix);
    }

    private static boolean hasDocument(WorldRuntime runtime, String docId) {
        return runtime.snapshot().information().orElseThrow().information().documents().stream()
                .anyMatch(doc -> doc.docId().equals(docId));
    }

    private static InformationModel.Document newestDocument(WorldRuntime runtime, String prefix) {
        InformationModel.Document found = null;
        for (InformationModel.Document doc : runtime.snapshot().information().orElseThrow()
                .information().documents()) {
            if (doc.docId().startsWith(prefix)) {
                found = doc;
            }
        }
        return found;
    }
}
