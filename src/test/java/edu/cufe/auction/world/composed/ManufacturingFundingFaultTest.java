package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.ManufacturingFunding;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 制造五段资金腿的持久化边界：真实 WRITE/FORCE 失败只允许“整笔没有发生”或“整笔已确认”，
 * 且同一待办恢复后只完成一次；跨三个真实 JVM 重开续写并做一条真实分支业务。
 */
class ManufacturingFundingFaultTest {

    private static final String POLICY = ManufacturingFunding.POLICY_FROZEN_MAPPING_V1;
    private static final long DAY = 86_400_000L;

    @TempDir
    Path tmp;

    /** 到货完成后采购单终态（用于判定“完整后态”）。 */
    private static final String BASE_RECEIVED = "|PO-force:RECEIVED";

    /** WRITE 失败：帧未落地，内存保持付款前；重开后可继续，且到货付款只发生一次。 */
    @Test
    void writeFaultKeepsPreStateAndResumesOnce() throws Exception {
        runFault(FundedProbes.FailingChannel.Mode.WRITE, "write");
    }

    /** FORCE 失败：完整帧可能已可见，但不得半应用；恢复后只完成一次。 */
    @Test
    void forceFaultOnlyAllowsOneCompleteFrame() throws Exception {
        runFault(FundedProbes.FailingChannel.Mode.FORCE, "force");
    }

    /** 三个真实进程：建链 → 重开续写 + 真实分支 → 只读重开比对。 */
    @Test
    void threeProcessesResumeAndBranchTheFundedChain() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        for (int step = 0; step < 3; step++) {
            Path log = tmp.resolve("jvm").resolve("step-" + step + ".out");
            Files.createDirectories(log.getParent());
            Process process = new ProcessBuilder(java, "-cp", cp,
                    ManufacturingFundingReopenProbe.class.getName(),
                    tmp.resolve("jvm").resolve("world.log").toString(),
                    Integer.toString(step),
                    tmp.resolve("jvm").resolve("digest-" + (step - 1)).toString(),
                    tmp.resolve("jvm").resolve("digest-" + step).toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            assertTrue(finished, "独立进程超时：step=" + step);
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("MFG_FUND_REOPEN_OK step=" + step),
                    "独立进程没有完成声明：" + Files.readString(log));
        }
        String digest1 = Files.readString(tmp.resolve("jvm").resolve("digest-1")).trim();
        String digest2 = Files.readString(tmp.resolve("jvm").resolve("digest-2")).trim();
        assertTrue(digest1.startsWith("step1:") && digest2.startsWith("step2:"),
                "三进程摘要标记不符：" + digest1 + " / " + digest2);
        assertEquals(digest1.substring(digest1.indexOf(':') + 1),
                digest2.substring(digest2.indexOf(':') + 1),
                "只读重开不得改变世界文件任何字节");
    }

    /**
     * 真实注入一次通道失败，比较付款前/失败后/重开后三个状态。
     */
    private void runFault(FundedProbes.FailingChannel.Mode mode, String tag) throws Exception {
        Path dir = tmp.resolve(tag);
        Files.createDirectories(dir);
        Path world = dir.resolve("world.log");
        Map<String, String> before;
        long receiptAt;
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(world)) {
            ManufacturingFundingChainTest.budgetMeeting(runtime,
                    edu.cufe.auction.web.WorldWebScenario.COMPANY, tag, "10000.00", "10000.00");
            long orderDue = 10 * DAY;
            runtime.scheduleFundedManufacturing(tag + "-po", tag + "-po-t", orderDue,
                    ManufacturingFundingWorld.MANAGER, "PURCHASE_ORDER", "PO-" + tag, "", 10L,
                    null, 0L, -1L, POLICY, "CASH|CO", "CASH|SUP", 1L);
            runtime.advanceTo(orderDue);
            receiptAt = orderDue + DAY;
            runtime.advanceTo(receiptAt - 1);
            before = components(runtime);
        }
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(world)) {
            assertEquals(before, components(runtime), "重开必须等于付款前状态");
            inject(runtime, mode);
            IOException failure = assertThrows(IOException.class, () -> runtime.advanceTo(receiptAt));
            assertTrue(failure.getMessage() != null, "故障必须带明确原因");
            assertEquals(before, components(runtime), "故障后内存不得半应用");
        }
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(world)) {
            Map<String, String> reopened = components(runtime);
            boolean pre = before.equals(reopened);
            boolean post = BASE_RECEIVED.equals(reopened.get("orders"))
                    && reopened.get("cash") != null;
            assertTrue(pre || post, tag + " 重开必须回到完整前态或完整后态：" + reopened);
            runtime.advanceTo(receiptAt);
            Map<String, String> resumed = components(runtime);
            runtime.advanceTo(receiptAt);
            assertEquals(resumed, components(runtime), "同一时点再推进不得重复付款");
            assertEquals(new BigDecimal("600.00"), runtime.snapshot().accounts().get("SUP").cash(),
                    "货款只付一次（开局 100 + 500）");
            assertEquals(new BigDecimal("500.00"), runtime.snapshot().accounts().get("CO").cash(),
                    "公司现金只扣一次");
        }
    }

    /** 记录现金、制造台账与提交位置：用于证明“整笔”原子性。 */
    private static Map<String, String> components(WorldRuntime runtime) {
        return Map.of(
                "position", Long.toString(runtime.snapshot().position()),
                "cash", runtime.snapshot().accounts().values().stream()
                        .map(a -> a.ownerId() + "=" + a.cash().toPlainString())
                        .sorted().reduce("", (a, b) -> a + "|" + b),
                "receivables", runtime.snapshot().manufacturing().orElseThrow().ledger()
                        .receivables().size() + "/" + runtime.snapshot().manufacturing().orElseThrow()
                        .ledger().rawMaterials().quantity(),
                "orders", runtime.snapshot().manufacturing().orElseThrow().ledger()
                        .purchaseOrders().entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue().status())
                        .sorted().reduce("", (a, b) -> a + "|" + b));
    }

    /** 把日志通道换成可控失败通道（只影响本进程内写入）。 */
    private static void inject(WorldRuntime runtime, FundedProbes.FailingChannel.Mode mode)
            throws Exception {
        java.lang.reflect.Field journalField = WorldRuntime.class.getDeclaredField("journal");
        journalField.setAccessible(true);
        Object journal = journalField.get(runtime);
        java.lang.reflect.Field channelField = journal.getClass().getDeclaredField("channel");
        channelField.setAccessible(true);
        FileChannel real = (FileChannel) channelField.get(journal);
        channelField.set(journal, new FundedProbes.FailingChannel(real, mode, 0));
    }
}
