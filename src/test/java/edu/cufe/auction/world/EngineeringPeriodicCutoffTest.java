package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期末截止确定性：period=1 覆盖 {@code [0,30d)}，capturedAt=30d 的采样帧必须晚于旧期间制造关期、
 * 早于 30d 当刻的新期规则/投递/完成/观察。因此 30d 当刻的现金流/发布属于期 2；
 * 只在 30d 之前发生的业务属于期 1。制造每期只关一次，三家公司在同一关期后各采样一次。
 */
class EngineeringPeriodicCutoffTest {

    private static final long PERIOD = PeriodicModel.PERIOD_MILLIS;
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String C = WorldWebScenario.COMPANY;
    private static final String ACCOUNT = "ENG-A";
    private static final BigDecimal OPENING = new BigDecimal("5000.00");

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-periodic-cutoff-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static WorldRuntime open(Path file) throws Exception {
        return WorldWebScenario.engineeringRuntime(file, WorldWebScenario.engineeringConfig());
    }

    private static BigDecimal cash(String statement) {
        for (String line : statement.split("\n")) {
            if (line.startsWith("现金=")) {
                return new BigDecimal(line.substring("现金=".length()));
            }
        }
        throw new AssertionError("报表缺少现金行：" + statement);
    }

    private static void capital(WorldRuntime world, String command, long due, String amount)
            throws Exception {
        world.scheduleCapitalFlow(command, command + "-task", due, "fund-a", "ACC-FUND", ACCOUNT,
                new BigDecimal(amount), 0L, "期界资金");
    }

    @Test
    void sameMillisecondBusinessBelongsToNextPeriod() throws Exception {
        Path file = journal("boundary");
        WorldRuntime world = open(file);
        // T 前没有流入：期 1 报表必须停在开局 5000。
        capital(world, "cap-at", PERIOD, "100.00");
        capital(world, "cap-after", PERIOD + 1L, "200.00");
        world.advanceTo(PERIOD + 5L);

        PeriodicModel.Snapshot first = world.periodicOf(A).orElseThrow().snapshots().get(0);
        assertEquals(PERIOD, first.capturedAt());
        String body1 = world.periodicStatementForTest(A, 1L);
        assertEquals(0, cash(body1).compareTo(OPENING),
                "期 1 只含截止前业务，30d 当刻的 +100 不得倒填：" + body1);
        assertEquals(0, world.snapshot().accounts().get(ACCOUNT).cash()
                        .compareTo(new BigDecimal("5300.00")),
                "现账应含 30d 的 +100");

        // 制造关期在采样之前且每期只关一次；三家公司各采样一次。
        assertEquals(1, world.snapshot().manufacturing().orElseThrow().ledger().closedPeriods());
        long mfgCloses = world.snapshot().manufacturing().orElseThrow().reports().values().stream()
                .filter(entry -> PendingTask.MANUFACTURING_PERIOD_CLOSE_V1.equals(entry.kind()))
                .count();
        assertEquals(1, mfgCloses, "制造期 0 只关一次");
        long captures = world.snapshot().engineering().orElseThrow().reports().values().stream()
                .filter(entry -> PendingTask.PERIOD_CAPTURE_V1.equals(entry.kind()))
                .count();
        assertEquals(3, captures, "两家工程公司加章程公司各采样一次");

        world.advanceTo(PERIOD * 2L + 5L);
        String body2 = world.periodicStatementForTest(A, 2L);
        assertEquals(0, cash(body2).compareTo(new BigDecimal("5300.00")),
                "期 2 必须同时含 30d 的 +100 与 30d+1ms 的 +200：" + body2);
        assertEquals(2, world.periodicOf(A).orElseThrow().snapshots().size());
        assertFalse(body1.equals(body2), "两期报表正文不能相同");

        // 跨 JVM 重演：采样必须能按期末帧逐字复算，重试或倒填都会在这里失败。
        world.close();
        try (WorldRuntime reopened = open(file)) {
            assertEquals(body1, reopened.periodicStatementForTest(A, 1L));
            assertEquals(body2, reopened.periodicStatementForTest(A, 2L));
        }
    }

    @Test
    void beforeBoundaryBelongsToOldPeriodAndSameTimePublishStaysOutside() throws Exception {
        Path file = journal("before");
        WorldRuntime world = open(file);
        // T-1 的合法资本属于期 1；T 当刻的发布走 COMPLETION，晚于采样。
        capital(world, "cap-before", PERIOD - 1L, "50.00");
        capital(world, "cap-at", PERIOD, "100.00");
        capital(world, "cap-after", PERIOD + 1L, "200.00");
        world.schedulePublishDocument("pub-at-boundary", "pub-at-boundary-task", PERIOD, "t-news",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "期末公告", "期末当刻发布", "实验材料", 10L, List.of());
        world.advanceTo(PERIOD + 5L);

        PeriodicModel.Snapshot first = world.periodicOf(A).orElseThrow().snapshots().get(0);
        String body1 = world.periodicStatementForTest(A, 1L);
        assertEquals(0, cash(body1).compareTo(new BigDecimal("5050.00")),
                "期 1 只含 T-1 的 +50：" + body1);
        assertEquals(0, world.snapshot().accounts().get(ACCOUNT).cash()
                        .compareTo(new BigDecimal("5350.00")),
                "现账含 T 与 T+1 的两笔");

        // T 当刻的发布确实执行了（同一毫秒批次内），但它不是期 1 的冻结证据。
        InformationModel.DocumentVersion news = InformationModel.version(
                world.snapshot().information().orElseThrow().information(), "t-news", 1L)
                .orElseThrow();
        assertEquals(PERIOD, news.publishedAt(), "发布真实发生在 T");
        assertNotNull(world.periodicStatementForTest(C, 1L));
        for (String ref : first.evidenceRefs()) {
            assertTrue(ref.startsWith("account:") || ref.startsWith("contract:")
                            || ref.startsWith("work:") || ref.startsWith("invoice:")
                            || ref.startsWith("disclosure:")
                            || ref.startsWith("periodic-source:"),
                    "期 1 证据只允许冻结类型：" + ref);
            assertFalse(ref.contains("t-news"), "T 当刻发布的文档不得进期 1 证据");
        }
        assertFalse(first.statementDigest().isBlank());
        assertFalse(first.basisDigest().isBlank());

        world.advanceTo(PERIOD * 2L + 5L);
        String body2 = world.periodicStatementForTest(A, 2L);
        assertEquals(0, cash(body2).compareTo(new BigDecimal("5350.00")),
                "期 2 含全部三笔：" + body2);
        world.close();
    }
}
