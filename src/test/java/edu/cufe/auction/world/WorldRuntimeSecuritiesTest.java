package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.SecuritiesLedger.ActionReport;
import edu.cufe.auction.world.SecuritiesLedger.Lot;
import edu.cufe.auction.world.SecuritiesLedger.SellReservation;
import edu.cufe.auction.world.SecuritiesLedger.State;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2.3：持仓批次、冻结与同批清算夹具在可恢复运行中的接入。
 *
 * <p>覆盖 Plan 的字面场景（发行 200，A/B 各 100 股与 1000 现金）、同日回补后不可再卖、
 * 冻结不双算、预留/成交/撤销的数字、第三方公司现金不随二级成交增加、同一受益主体不能自成交、
 * 产品主体独立结算、资金或股份不足不留单边状态、命令幂等与重启不重复、真实中断与半帧截断、
 * 旧档案继续可读且新证券接口明确未启用、同种子执行顺序与排程入队顺序无关。</p>
 */
@DisplayName("WorldRuntime：持仓批次与同批清算夹具")
class WorldRuntimeSecuritiesTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-sec";
    private static final String SYM = "ACME1";
    private static final long DAY = SecuritiesLedger.DAY_MILLIS;
    private static final BigDecimal PX = new BigDecimal("10.00");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 字面场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("旧股卖出后旧底仓回补：当日只剩 40 可卖，冻结撤销回 40，次实验日 100")
    void sameDayLockAndNextDayRelease() throws IOException {
        Path file = tempDir.resolve("same-day.journal");
        try (WorldRuntime runtime = open(file)) {
            assertEquals("SCHEDULED:sell-a",
                    runtime.scheduleReserveSell("c1", "sell-a", 10, "alice", "A", SYM, "res-a", 60));
            runtime.scheduleFixtureFill("c2", "fill-a", 20, "res-a", "B", PX, 60);
            runtime.scheduleReserveSell("c3", "sell-b", 30, "bob", "B", SYM, "res-b", 60);
            runtime.scheduleFixtureFill("c4", "fill-b", 40, "res-b", "A", PX, 60);
            runtime.advanceTo(100);

            assertTrue(report(runtime, "fill-a").accepted(), "A 旧股 60 应正常成交");
            assertTrue(report(runtime, "fill-b").accepted(), "B 用旧底仓 60 回补应正常成交");
            assertEquals(100L, held(runtime, "A"), "A 总持股 100（40 旧 + 60 当日买入）");
            assertEquals(100L, held(runtime, "B"));
            assertEquals(40L, available(runtime, "A"), "当日买入的 60 不能今天就卖");
            assertEquals(40L, available(runtime, "B"), "B 只应剩旧底仓余量");

            // B 试图卖出刚买到的 60：当日只有 40 合格
            runtime.scheduleReserveSell("c5", "sell-b2", 200, "bob", "B", SYM, "res-b2", 60);
            runtime.advanceTo(300);
            assertFalse(report(runtime, "sell-b2").accepted());
            assertEquals(SecuritiesLedger.REASON_INSUFFICIENT_SHARES, report(runtime, "sell-b2").reason());
            assertEquals(40L, available(runtime, "B"));

            // 冻结 30 → 可用 10；撤销 → 40
            runtime.scheduleReserveSell("c6", "sell-a2", 400, "alice", "A", SYM, "res-a2", 30);
            runtime.advanceTo(500);
            assertEquals(10L, available(runtime, "A"));
            assertEquals(30L, reserved(runtime, "res-a2"));
            runtime.scheduleCancelSell("c7", "cancel-a2", 600, "alice", "res-a2");
            runtime.advanceTo(700);
            assertEquals(40L, available(runtime, "A"), "撤销只释放未成交的冻结");
            assertTrue(securities(runtime).reservations().isEmpty());

            runtime.advanceTo(DAY);
            assertEquals(1L, SecuritiesLedger.day(runtime.snapshot().timeMillis()));
            assertEquals(100L, available(runtime, "A"), "次实验日全部合格");
            assertEquals(100L, available(runtime, "B"));

            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, cash(runtime, "CO").compareTo(new BigDecimal("1000.00")),
                    "第三方公司的现金不因二级成交增加");
            assertEquals(0, totalCash(runtime).compareTo(new BigDecimal("6000.00")), "现金总量守恒");
            assertEquals(200L, held(runtime, "A") + held(runtime, "B"), "股份总量守恒");
        }
    }

    @Test
    @DisplayName("预留 80 成交 30：剩余 70、冻结 50、可用 20；撤销后可用 70，金额为价格×数量")
    void reserveFillCancelNumbers() throws IOException {
        Path file = tempDir.resolve("numbers.journal");
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A", SYM, "res-1", 80);
            runtime.scheduleFixtureFill("c2", "fill-1", 20, "res-1", "B",
                    new BigDecimal("10.37"), 30);
            runtime.advanceTo(100);

            assertTrue(report(runtime, "fill-1").accepted(), report(runtime, "fill-1").reason());
            assertEquals(70L, held(runtime, "A"));
            assertEquals(50L, reserved(runtime, "res-1"));
            assertEquals(20L, available(runtime, "A"));
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1311.10")), "1000 + 10.37×30");
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("688.90")));
            assertEquals(0, totalCash(runtime).compareTo(new BigDecimal("6000.00")));
            assertEquals(200L, held(runtime, "A") + held(runtime, "B"));
            assertEquals(0, cash(runtime, "CO").compareTo(new BigDecimal("1000.00")));

            runtime.scheduleCancelSell("c3", "cancel-1", 200, "alice", "res-1");
            runtime.advanceTo(300);
            assertTrue(report(runtime, "cancel-1").accepted(), report(runtime, "cancel-1").reason());
            assertEquals(70L, available(runtime, "A"), "撤销后全部剩余可卖");
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1311.10")), "撤单不动现金");
        }
        assertEquals(1L, countSnapshotsInBatch(file, "task:fill-1"), "每批只能有一个状态快照");
        List<String> types = batchTypes(file, "task:fill-1");
        assertTrue(types.contains("SECURITIES_CASH_TRANSFERRED_V1"), "同批必须含证券现金效果：" + types);
        assertFalse(types.contains("CASH_TRANSFERRED_V1"), "不能复用普通转账的不同载荷类型");
        assertTrue(types.contains("SECURITIES_FILLED_V1"), "同批必须含股份与预留变化：" + types);
        assertTrue(types.contains("TASK_COMPLETED_V1"), "同批必须含待办完成：" + types);
        assertTrue(types.contains("STATE_SNAPSHOT_V1"), "同批必须含全量状态：" + types);
        try (WorldJournal journal = new WorldJournal(file, RUN)) {
            WorldCommit fill = journal.commits().stream()
                    .filter(commit -> "task:fill-1".equals(commit.commandId())).findFirst().orElseThrow();
            WorldEvent cash = fill.events().stream()
                    .filter(event -> "SECURITIES_CASH_TRANSFERRED_V1".equals(event.type()))
                    .findFirst().orElseThrow();
            Map<String, Object> effect = readMap(cash.payload());
            assertEquals("B", effect.get("buyerAccountId"), "现金付款方");
            assertEquals("A", effect.get("sellerAccountId"), "现金收款方");
            assertEquals(0, new BigDecimal(effect.get("amount").toString())
                    .compareTo(new BigDecimal("311.10")), "现金效果必须包含实际金额");
        }
    }

    // ------------------------------------------------------------------
    // 主体与拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一受益主体的两个账户不能自成交；不同产品主体可独立结算")
    void sameBeneficiaryRejectedAndProductsSettle() throws IOException {
        Path own = tempDir.resolve("self-trade.journal");
        try (WorldRuntime runtime = open(own)) {
            runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A", SYM, "res-1", 10);
            runtime.scheduleFixtureFill("c2", "fill-self", 20, "res-1", "A2", PX, 10);
            runtime.advanceTo(100);

            assertFalse(report(runtime, "fill-self").accepted());
            assertEquals(WorldRuntime.REASON_SELF_TRADE_PREVENTED, report(runtime, "fill-self").reason());
            assertEquals(100L, held(runtime, "A"), "自成交被拒后卖方股份不变");
            assertEquals(0L, held(runtime, "A2"), "买方没有凭空得到股份");
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, cash(runtime, "A2").compareTo(new BigDecimal("1000.00")));
            assertEquals(10L, reserved(runtime, "res-1"), "拒绝不消耗预留");
        }

        Path products = tempDir.resolve("products.journal");
        try (WorldRuntime runtime = open(products, registry300())) {
            runtime.scheduleReserveSell("c1", "sell-p", 10, "prod-1", "P1", SYM, "res-p", 20);
            runtime.scheduleFixtureFill("c2", "fill-p", 20, "res-p", "P2", PX, 20);
            runtime.advanceTo(100);

            assertTrue(report(runtime, "fill-p").accepted(), report(runtime, "fill-p").reason());
            assertEquals(30L, held(runtime, "P1"));
            assertEquals(70L, held(runtime, "P2"), "不同产品的财产主体分别结算（自有 50 + 买入 20）");
            assertEquals(0, cash(runtime, "P1").compareTo(new BigDecimal("1200.00")));
            assertEquals(0, cash(runtime, "P2").compareTo(new BigDecimal("800.00")));
        }
    }

    @Test
    @DisplayName("买方现金不足或预留不足都不留单边状态")
    void insufficientResourcesLeaveNoOneSidedState() throws IOException {
        Path file = tempDir.resolve("insufficient.journal");
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A", SYM, "res-1", 100);
            runtime.scheduleFixtureFill("c2", "fill-poor", 20, "res-1", "POOR", PX, 100);
            runtime.advanceTo(100);

            assertFalse(report(runtime, "fill-poor").accepted());
            assertEquals(WorldRuntime.REASON_INSUFFICIENT_CASH, report(runtime, "fill-poor").reason());
            assertEquals(100L, held(runtime, "A"), "钱不足不得扣股");
            assertEquals(100L, reserved(runtime, "res-1"), "钱不足不得消耗预留");
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, cash(runtime, "POOR").signum(), "钱不足不得只加收款方或扣付款方");
            assertEquals(200L, held(runtime, "A") + held(runtime, "B") + held(runtime, "POOR"),
                    "股份总量守恒");

            runtime.scheduleFixtureFill("c3", "fill-60", 200, "res-1", "B", PX, 60);
            runtime.advanceTo(300);
            assertTrue(report(runtime, "fill-60").accepted(), report(runtime, "fill-60").reason());
            assertEquals(40L, held(runtime, "A"));
            assertEquals(40L, reserved(runtime, "res-1"));

            runtime.scheduleFixtureFill("c4", "fill-over", 400, "res-1", "B", PX, 60);
            runtime.advanceTo(500);
            assertFalse(report(runtime, "fill-over").accepted());
            assertEquals(SecuritiesLedger.REASON_FILL_EXCEEDS_RESERVATION,
                    report(runtime, "fill-over").reason());
            assertEquals(40L, held(runtime, "A"), "超量成交不得扣股");
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("400.00")));
            assertEquals(160L, held(runtime, "B"));
        }
    }

    @Test
    @DisplayName("未知与越权的排程在提交前拒绝，不写批次也不改状态")
    void unauthorizedSchedulingRejectedBeforeCommit() throws IOException {
        Path file = tempDir.resolve("reject.journal");
        try (WorldRuntime runtime = open(file)) {
            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleReserveSell(
                    "c1", "sell-1", 10, "bob", "A", SYM, "res-1", 10), "非属主不能冻结他人账户");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleReserveSell(
                    "c2", "sell-2", 10, "alice", "A", "NOPE", "res-2", 10), "未知证券");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleReserveSell(
                    "c3", "sell-3", 10, "alice", "A", SYM, "res-3", 0), "数量必须为正");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleReserveSell(
                    "c4", "sell-4", 0, "alice", "A", SYM, "res-4", 10), "到期必须晚于当前时点");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleFixtureFill(
                    "c5", "fill-1", 10, "res-1", "B", new BigDecimal("10.001"), 10),
                    "价格必须精确到分");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleFixtureFill(
                    "c6", "fill-2", 10, "res-1", "B", PX, -1), "数量必须为正");
            assertEquals(position, runtime.snapshot().position(), "被拒绝的排程不得提交");
            assertTrue(runtime.snapshot().pending().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // 幂等 / 重启
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同命令重发先取原回报，重启后不重复付款或转股")
    void duplicateCommandAndRestartDoNotPayTwice() throws IOException {
        Path file = tempDir.resolve("duplicate.journal");
        String first;
        try (WorldRuntime runtime = open(file)) {
            first = runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A", SYM, "res-1", 60);
            runtime.scheduleFixtureFill("c2", "fill-1", 20, "res-1", "B", PX, 60);
            runtime.advanceTo(100);
        }
        try (WorldRuntime runtime = open(file)) {
            long position = runtime.snapshot().position();
            assertEquals(first, runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A",
                    SYM, "res-1", 60), "重发应返回原 SCHEDULED 回报");
            assertEquals(position, runtime.snapshot().position(), "重发不得新增提交");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleReserveSell(
                    "c1", "sell-1", 10, "alice", "A", SYM, "res-1", 61), "同标识不同内容必须拒绝");

            runtime.advanceTo(200);
            assertEquals(40L, held(runtime, "A"), "重启后不得再次转股");
            assertEquals(160L, held(runtime, "B"));
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1600.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("400.00")));
            assertEquals(1, countReports(runtime, "fill-1"), "同一任务只有一个报告");
        }
    }

    // ------------------------------------------------------------------
    // 失败与截断
    //
    // 这里有两条不同的故障点，不要混为一谈：
    //   * reserve*：中断/截断落在**预留完成帧**，证明“未确认的预留不生效”；
    //   * fill*   ：先推进到 10 毫秒让预留真正生效，再中断/截断落在**成交完成帧**，
    //               证明“已生效的冻结与旧钱旧股一起保留，恢复后只成交一次”。
    // 此前两条用例在预留到期前注入故障，却按成交命名，实际覆盖的是前一种；本轮已更正。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("实际中断预留提交：钱、股、待办都保留，恢复后只预留一次")
    void interruptDuringReserveKeepsEverythingAndCompletesOnce() throws IOException {
        Path file = tempDir.resolve("interrupt-reserve.journal");
        long position;
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A", SYM, "res-1", 60);
            runtime.scheduleFixtureFill("c2", "fill-1", 20, "res-1", "B", PX, 60);
            position = runtime.snapshot().position();
            try {
                Thread.currentThread().interrupt();
                assertThrows(IOException.class, () -> runtime.advanceTo(30),
                        "线程中断应使真实文件写入失败");
            } finally {
                Thread.interrupted();
            }
            assertTrue(runtime.isPaused(), "提交失败必须暂停运行");
            assertEquals(position, runtime.snapshot().position(), "失败批次不得发布");
            assertEquals(100L, held(runtime, "A"));
            assertTrue(securities(runtime).reservations().isEmpty(), "未确认的预留帧不得生效");
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(2, runtime.snapshot().pending().size(), "未确认的任务必须仍在待办");
            assertThrows(IllegalStateException.class, () -> runtime.scheduleReserveSell(
                    "c3", "sell-2", 40, "alice", "A", SYM, "res-2", 10), "暂停期间不得接受新排程");
        }
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(30);
            assertEquals(40L, held(runtime, "A"));
            assertEquals(160L, held(runtime, "B"));
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1600.00")));
            assertEquals(1, countReports(runtime, "fill-1"));
        }
    }

    @Test
    @DisplayName("截断未确认的预留完成帧：旧钱旧股保留，重开只预留一次")
    void truncatedReserveCompletionKeepsOriginalState() throws IOException {
        Path file = tempDir.resolve("truncate-reserve.journal");
        long boundary;
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A", SYM, "res-1", 60);
            runtime.scheduleFixtureFill("c2", "fill-1", 20, "res-1", "B", PX, 60);
            boundary = Files.size(file);
            runtime.advanceTo(30);
            assertTrue(Files.size(file) > boundary + 20, "预留完成帧必须有足够字节可截半");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(boundary + 20);
            channel.force(true);
        }
        try (WorldRuntime runtime = open(file)) {
            assertEquals(100L, held(runtime, "A"), "未确认的预留帧不得生效");
            assertTrue(securities(runtime).reservations().isEmpty(), "未确认的预留帧不得生效");
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(2, runtime.snapshot().pending().size());
            runtime.advanceTo(30);
            assertEquals(40L, held(runtime, "A"));
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1600.00")));
            assertEquals(1, countReports(runtime, "fill-1"));
        }
    }

    @Test
    @DisplayName("实际中断成交提交（预留已生效）：钱、股、冻结、待办保留，恢复后只成交一次")
    void interruptDuringFillKeepsEverythingAndCompletesOnce() throws IOException {
        Path file = tempDir.resolve("interrupt-fill.journal");
        long position;
        try (WorldRuntime runtime = openAB(file)) {
            runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A", SYM, "res-1", 60);
            runtime.scheduleFixtureFill("c2", "fill-1", 20, "res-1", "B", PX, 60);
            runtime.advanceTo(10);

            // 先证明故障点确实在成交帧上：预留已生效、待办只剩成交、钱和股都还没动
            assertEquals(60L, reserved(runtime, "res-1"), "预留必须已生效才算覆盖成交故障");
            assertTrue(report(runtime, "sell-1").accepted(), "预留任务已经成功执行过");
            assertFalse(securities(runtime).reports().containsKey("fill-1"), "此时还没有成交结果");
            assertEquals(100L, held(runtime, "A"));
            assertEquals(100L, held(runtime, "B"));
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, totalCash(runtime).compareTo(new BigDecimal("2000.00")));
            assertEquals(1, runtime.snapshot().pending().size(), "待办只剩成交任务");
            assertEquals("fill-1", runtime.snapshot().pending().get(0).taskId());
            assertEquals(PendingTask.FILL_SETTLE_V1, runtime.snapshot().pending().get(0).kind());

            position = runtime.snapshot().position();
            try {
                Thread.currentThread().interrupt();
                assertThrows(IOException.class, () -> runtime.advanceTo(30),
                        "线程中断应使成交帧真实写入失败");
            } finally {
                Thread.interrupted();
            }
            assertTrue(runtime.isPaused(), "提交失败必须暂停运行");
            assertEquals(position, runtime.snapshot().position(), "失败批次不得发布");
            assertEquals(60L, reserved(runtime, "res-1"), "已确认的活动预留必须保留");
            assertEquals(100L, held(runtime, "A"));
            assertEquals(100L, held(runtime, "B"));
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, totalCash(runtime).compareTo(new BigDecimal("2000.00")), "总现金守恒");
            assertEquals(1, runtime.snapshot().pending().size(), "未确认的成交任务必须仍在待办");
            assertThrows(IllegalStateException.class, () -> runtime.scheduleFixtureFill(
                    "c3", "fill-2", 40, "res-1", "B", PX, 10), "暂停期间不得接受新排程");
        }
        try (WorldRuntime runtime = openAB(file)) {
            runtime.advanceTo(30);
            assertEquals(40L, held(runtime, "A"));
            assertEquals(160L, held(runtime, "B"));
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1600.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("400.00")));
            assertTrue(securities(runtime).reservations().isEmpty(), "成交消耗后预留移除");
            assertEquals(1, countReports(runtime, "fill-1"));
            assertEquals(0, totalCash(runtime).compareTo(new BigDecimal("2000.00")), "总现金守恒");
            assertEquals(200L, held(runtime, "A") + held(runtime, "B"), "总股守恒");
            runtime.advanceTo(60);
        }
        try (WorldRuntime runtime = openAB(file)) {
            runtime.advanceTo(120);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1600.00")), "重开不得重复付款");
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("400.00")));
            assertEquals(160L, held(runtime, "B"), "重开不得重复转股");
            assertEquals(1, countReports(runtime, "fill-1"));
            assertEquals(0, totalCash(runtime).compareTo(new BigDecimal("2000.00")));
        }
    }

    @Test
    @DisplayName("截断未确认的成交完成帧（预留已生效）：旧钱旧股旧冻结保留，重开只成交一次")
    void truncatedFillCompletionKeepsOriginalState() throws IOException {
        Path file = tempDir.resolve("truncate-fill.journal");
        long boundary;
        try (WorldRuntime runtime = openAB(file)) {
            runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A", SYM, "res-1", 60);
            runtime.scheduleFixtureFill("c2", "fill-1", 20, "res-1", "B", PX, 60);
            runtime.advanceTo(10);

            // 同上：先确认活动预留 60、待办只剩成交，再让截断落在成交完成帧上
            assertEquals(60L, reserved(runtime, "res-1"));
            assertTrue(report(runtime, "sell-1").accepted(), "预留任务已经成功执行过");
            assertFalse(securities(runtime).reports().containsKey("fill-1"), "此时还没有成交结果");
            assertEquals(100L, held(runtime, "A"));
            assertEquals(1, runtime.snapshot().pending().size());
            assertEquals("fill-1", runtime.snapshot().pending().get(0).taskId());

            boundary = Files.size(file);
            runtime.advanceTo(30);
            assertTrue(Files.size(file) > boundary + 20, "成交完成帧必须有足够字节可截半");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(boundary + 20);
            channel.force(true);
        }
        try (WorldRuntime runtime = openAB(file)) {
            assertEquals(100L, held(runtime, "A"), "未确认的成交帧不得生效");
            assertEquals(100L, held(runtime, "B"));
            assertEquals(60L, reserved(runtime, "res-1"), "已确认的活动预留必须保留");
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, totalCash(runtime).compareTo(new BigDecimal("2000.00")), "总现金守恒");
            assertEquals(1, runtime.snapshot().pending().size(), "原成交待办保留");
            assertEquals("fill-1", runtime.snapshot().pending().get(0).taskId());

            runtime.advanceTo(30);
            assertEquals(40L, held(runtime, "A"));
            assertEquals(160L, held(runtime, "B"));
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1600.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("400.00")));
            assertTrue(securities(runtime).reservations().isEmpty(), "成交消耗后预留移除");
            assertEquals(1, countReports(runtime, "fill-1"));
            assertEquals(0, totalCash(runtime).compareTo(new BigDecimal("2000.00")), "总现金守恒");
            assertEquals(200L, held(runtime, "A") + held(runtime, "B"), "总股守恒");
        }
        try (WorldRuntime runtime = openAB(file)) {
            runtime.advanceTo(120);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1600.00")), "重开不得重复付款");
            assertEquals(160L, held(runtime, "B"), "重开不得重复转股");
            assertEquals(1, countReports(runtime, "fill-1"));
            assertEquals(0, totalCash(runtime).compareTo(new BigDecimal("2000.00")));
            assertEquals(200L, held(runtime, "A") + held(runtime, "B"));
        }
    }

    // ------------------------------------------------------------------
    // 兼容 / 清单 / 顺序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("篡改证券状态（总股数不守恒、批次结果丢失、旧结果被删除）时恢复拒绝")
    void tamperedSecuritiesStateRejected() throws IOException {
        Path source = tempDir.resolve("tamper-src.journal");
        long last;
        long fillPosition;
        try (WorldRuntime runtime = open(source)) {
            runtime.scheduleReserveSell("c1", "sell-1", 10, "alice", "A", SYM, "res-1", 60);
            runtime.scheduleFixtureFill("c2", "fill-1", 20, "res-1", "B", PX, 60);
            runtime.advanceTo(30);
            last = runtime.snapshot().position();
        }
        fillPosition = positionOf(source, "task:fill-1");

        Path broken = tempDir.resolve("tamper-conservation.journal");
        rewriteWithSnapshot(source, broken, last, payload -> {
            Map<String, Object> state = readMap(payload);
            Map<String, Object> securities = asMap(state.get("securities"));
            List<Object> lots = asList(securities.get("lots"));
            Map<String, Object> buyerLot = asMap(lots.get(lots.size() - 1));
            assertEquals("lot-fill:fill-1", buyerLot.get("lotId"), "最后一个批次应为买入批次");
            buyerLot.put("remaining", 59);
            return writeJson(state);
        });
        IOException notConserved = assertThrows(IOException.class, () -> open(broken).close(),
                "买入批次少 1 股使总股数与已发行不符，必须拒绝");
        assertTrue(notConserved.getMessage().contains("总股数"), notConserved.getMessage());

        Path reportLost = tempDir.resolve("tamper-report-in-batch.journal");
        rewriteWithSnapshot(source, reportLost, fillPosition, payload -> {
            Map<String, Object> state = readMap(payload);
            asMap(asMap(state.get("securities")).get("reports")).remove("fill-1");
            return writeJson(state);
        });
        IOException inconsistent = assertThrows(IOException.class, () -> open(reportLost).close(),
                "成交批次的报告不能在状态快照里丢失");
        assertTrue(inconsistent.getMessage().contains("证券结果与状态快照不一致"),
                inconsistent.getMessage());

        Path reportRemoved = tempDir.resolve("tamper-report-later.journal");
        rewriteWithSnapshot(source, reportRemoved, last, payload -> {
            Map<String, Object> state = readMap(payload);
            asMap(asMap(state.get("securities")).get("reports")).remove("fill-1");
            return writeJson(state);
        });
        IOException rewritten = assertThrows(IOException.class, () -> open(reportRemoved).close(),
                "已记录的业务结果不能在后续快照里被删除");
        assertTrue(rewritten.getMessage().contains("旧证券结果"), rewritten.getMessage());
    }

    @Test
    @DisplayName("旧现金与旧登记档案继续可推进，新证券接口明确未启用；旧 JSON 不含 securities 键")
    void legacyProfilesKeepWorkingWithoutSecurities() throws IOException {
        Path cashFile = tempDir.resolve("cash-only.journal");
        try (WorldRuntime runtime = new WorldRuntime(cashFile, cashManifest(RUN, SEED), openings())) {
            assertTrue(runtime.snapshot().securities().isEmpty(), "现金档案没有证券状态");
            assertThrows(IllegalStateException.class, () -> runtime.scheduleReserveSell(
                    "c1", "sell-1", 10, "alice", "A", SYM, "res-1", 10), "证券接口不得在现金档案启用");
            runtime.scheduleTransfer("c2", "t1", 10, "alice", "A", "B",
                    new BigDecimal("100.00"), 0);
        }
        try (WorldRuntime runtime = new WorldRuntime(cashFile, cashManifest(RUN, SEED), openings())) {
            assertTrue(runtime.snapshot().securities().isEmpty());
            runtime.advanceTo(20);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("900.00")));
        }
        assertFalse(snapshotPayload(cashFile, 1).contains("securities"),
                "旧档案写出的状态 JSON 必须省略可选证券字段，不能写 null：" + snapshotPayload(cashFile, 1));

        Path registryFile = tempDir.resolve("registry-only.journal");
        try (WorldRuntime runtime = new WorldRuntime(registryFile, registryManifest(RUN, SEED),
                openings(), registry200())) {
            assertTrue(runtime.snapshot().securities().isEmpty());
            assertThrows(IllegalStateException.class, () -> runtime.scheduleFixtureFill(
                    "c1", "fill-1", 10, "res-1", "B", PX, 10));
            assertEquals(2, runtime.openingRegistry().orElseThrow().allocations().size(),
                    "开局登记仍然只是开局分配");
            runtime.scheduleTransfer("c2", "t2", 10, "alice", "A", "B",
                    new BigDecimal("100.00"), 0);
        }
        try (WorldRuntime runtime = new WorldRuntime(registryFile, registryManifest(RUN, SEED),
                openings(), registry200())) {
            runtime.advanceTo(20);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("900.00")),
                    "旧登记档案在证券代码下仍可推进现金");
            assertTrue(runtime.snapshot().securities().isEmpty());
            assertEquals(1, runtime.snapshot().delivered().size());
        }
        assertFalse(snapshotPayload(registryFile, 1).contains("securities"),
                "登记档案的状态 JSON 也必须省略可选证券字段");

        assertThrows(IOException.class, () -> new WorldRuntime(cashFile, securitiesManifest(RUN, SEED),
                openings(), registry200()).close(), "证券档案不得打开旧现金日志");
        assertThrows(IOException.class, () -> new WorldRuntime(registryFile, securitiesManifest(RUN, SEED),
                openings(), registry200()).close(), "证券档案不得打开旧登记日志");
    }

    @Test
    @DisplayName("证券档案要求精确模块组合与开局登记，缺模块或未知模块都在写盘前拒绝")
    void manifestRequiresExactSecuritiesCombination() {
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_SECURITIES_CLEARING_EXPERIMENT_V1,
                Map.of("cash", 1, "scheduler", 1, "registry", 1), Map.of(), "none"),
                "缺少 securities 模块必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_SECURITIES_CLEARING_EXPERIMENT_V1,
                Map.of("cash", 1, "scheduler", 1, "securities", 1), Map.of(), "none"),
                "证券档案必须带 registry 模块");
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_SECURITIES_CLEARING_EXPERIMENT_V1,
                Map.of("cash", 1, "scheduler", 1, "registry", 1, "securities", 1, "bank", 1),
                Map.of(), "none"), "未启用模块不得混入");
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_CASH_WORLD_V1,
                Map.of("cash", 1, "scheduler", 1, "securities", 1), Map.of(), "none"),
                "旧档案不得声称启用证券模块");

        Path file = tempDir.resolve("no-registry.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(file,
                securitiesManifest(RUN, SEED), openings()), "证券档案必须提供开局登记");
        assertFalse(Files.exists(file), "拒绝时不得创建日志");

        assertThrows(IllegalArgumentException.class, () -> new PendingTask("x1", 10,
                WorldScheduler.Phase.DELIVERY, PendingTask.SELL_RESERVE_V1, "{}"),
                "预留任务必须落在 COMPLETION 阶段");
        assertThrows(IllegalArgumentException.class, () -> new PendingTask("x2", 10,
                WorldScheduler.Phase.COMPLETION, "ORDER_MATCH_V1", "{}"),
                "订单撮合仍未启用");
    }

    @Test
    @DisplayName("同种子下同刻任务的执行顺序不随排程入队顺序改变")
    void sameSeedIgnoresScheduleInsertionOrder() throws IOException {
        State forward = executionOrder(tempDir.resolve("order-a.journal"), false);
        State reversed = executionOrder(tempDir.resolve("order-b.journal"), true);
        assertEquals(forward.lots(), reversed.lots(), "批次状态必须由种子与任务标识决定");
        assertEquals(forward.reservations(), reversed.reservations(), "预留分配必须一致");
    }

    private State executionOrder(Path file, boolean reverse) throws IOException {
        try (WorldRuntime runtime = open(file)) {
            List<String> tasks = new ArrayList<>(List.of("sell-1", "sell-2"));
            if (reverse) {
                Collections.reverse(tasks);
            }
            for (String taskId : tasks) {
                long quantity = "sell-1".equals(taskId) ? 20 : 30;
                runtime.scheduleReserveSell("c-" + taskId, taskId, 100, "alice", "A", SYM,
                        "res-" + taskId, quantity);
            }
            runtime.advanceTo(200);
            return securities(runtime);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private WorldRuntime open(Path file) throws IOException {
        return open(file, registry200());
    }

    private WorldRuntime open(Path file, OpeningRegistry registry) throws IOException {
        return new WorldRuntime(file, securitiesManifest(RUN, SEED), openings(), registry);
    }

    /** 只用 A/B 两个 1000 元账户的开局，使总现金恰为 2000，便于守恒断言。 */
    private WorldRuntime open(Path file, OpeningRegistry registry, List<OpeningAccount> opening)
            throws IOException {
        return new WorldRuntime(file, securitiesManifest(RUN, SEED), opening, registry);
    }

    /** 字面场景专用运行：A/B 各 100 股、各 1000 元。 */
    private WorldRuntime openAB(Path file) throws IOException {
        return open(file, registry200(), openingsAB());
    }

    private static State securities(WorldRuntime runtime) {
        return runtime.snapshot().securities().orElseThrow(() -> new AssertionError("缺少证券状态"));
    }

    private static ActionReport report(WorldRuntime runtime, String taskId) {
        ActionReport found = securities(runtime).reports().get(taskId);
        assertTrue(found != null, "缺少任务报告：" + taskId + "，已有 " + securities(runtime).reports().keySet());
        return found;
    }

    private static long countReports(WorldRuntime runtime, String taskId) {
        return securities(runtime).reports().containsKey(taskId) ? 1 : 0;
    }

    private static long held(WorldRuntime runtime, String accountId) {
        long total = 0L;
        for (Lot lot : securities(runtime).lots()) {
            if (lot.accountId().equals(accountId) && lot.symbol().equals(SYM)) {
                total = Math.addExact(total, lot.remaining());
            }
        }
        return total;
    }

    private static long available(WorldRuntime runtime, String accountId) {
        long day = SecuritiesLedger.day(runtime.snapshot().timeMillis());
        return SecuritiesLedger.available(securities(runtime), accountId, SYM, day);
    }

    private static long reserved(WorldRuntime runtime, String reservationId) {
        for (SellReservation reservation : securities(runtime).reservations()) {
            if (reservation.reservationId().equals(reservationId)) {
                return reservation.quantity();
            }
        }
        throw new AssertionError("缺少活动预留：" + reservationId);
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    private static BigDecimal totalCash(WorldRuntime runtime) {
        BigDecimal total = BigDecimal.ZERO;
        for (CashLedger.CashAccount account : runtime.snapshot().accounts().values()) {
            total = total.add(account.cash());
        }
        return total;
    }

    private static List<String> batchTypes(Path file, String commandId) throws IOException {
        try (WorldJournal journal = new WorldJournal(file, RUN)) {
            for (WorldCommit commit : journal.commits()) {
                if (commandId.equals(commit.commandId())) {
                    return commit.events().stream().map(WorldEvent::type).toList();
                }
            }
        }
        throw new AssertionError("缺少批次：" + commandId);
    }

    private static long countSnapshotsInBatch(Path file, String commandId) throws IOException {
        return batchTypes(file, commandId).stream().filter("STATE_SNAPSHOT_V1"::equals).count();
    }

    private static String snapshotPayload(Path file, long position) throws IOException {
        try (WorldJournal journal = new WorldJournal(file, RUN)) {
            for (WorldCommit commit : journal.commits()) {
                if (commit.position() == position) {
                    for (WorldEvent event : commit.events()) {
                        if ("STATE_SNAPSHOT_V1".equals(event.type())) {
                            return event.payload();
                        }
                    }
                }
            }
        }
        throw new AssertionError("缺少位置 " + position + " 的状态快照");
    }

    /** @return 指定命令批次的位置 */
    private static long positionOf(Path file, String commandId) throws IOException {
        try (WorldJournal journal = new WorldJournal(file, RUN)) {
            for (WorldCommit commit : journal.commits()) {
                if (commandId.equals(commit.commandId())) {
                    return commit.position();
                }
            }
        }
        throw new AssertionError("缺少批次：" + commandId);
    }

    /** 逐帧复制日志，只改写指定位置的状态快照；用于证明恢复校验不是死代码。 */
    private void rewriteWithSnapshot(Path source, Path target, long position,
                                     UnaryOperator<String> mutate) throws IOException {
        try (WorldJournal from = new WorldJournal(source, RUN);
             WorldJournal to = new WorldJournal(target, RUN)) {
            for (WorldCommit commit : from.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    String payload = commit.position() == position
                            && "STATE_SNAPSHOT_V1".equals(event.type())
                            ? mutate.apply(event.payload()) : event.payload();
                    events.add(new WorldEvent(event.eventId(), event.type(), payload));
                }
                to.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String payload) {
        try {
            return JSON.readValue(payload, Map.class);
        } catch (IOException failure) {
            throw new IllegalStateException("无法解析状态快照", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException failure) {
            throw new IllegalStateException("无法写出状态快照", failure);
        }
    }

    private static RunManifest securitiesManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("registry", 1);
        modules.put("scheduler", 1);
        modules.put("securities", 1);
        return new RunManifest(runId, seed,
                RunManifest.RULES_SECURITIES_CLEARING_EXPERIMENT_V1, modules, Map.of(),
                RunManifest.MODEL_NONE);
    }

    private static RunManifest registryManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("registry", 1);
        modules.put("scheduler", 1);
        return new RunManifest(runId, seed, RunManifest.RULES_CASH_REGISTRY_WORLD_V1, modules,
                Map.of(), RunManifest.MODEL_NONE);
    }

    private static RunManifest cashManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        return new RunManifest(runId, seed, RunManifest.RULES_CASH_WORLD_V1, modules, Map.of(),
                RunManifest.MODEL_NONE);
    }

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount("A", "alice", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("B", "bob", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("CO", "acme", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("A2", "alice", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("P1", "prod-1", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("P2", "prod-2", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("POOR", "ext-org", new BigDecimal("0.00"), "实验开局历史禀赋"));
    }

    /** 只有字面场景的两名持有人，总现金 2000。 */
    private static List<OpeningAccount> openingsAB() {
        return List.of(
                new OpeningAccount("A", "alice", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("B", "bob", new BigDecimal("1000.00"), "实验开局历史禀赋"));
    }

    private static List<OpeningRegistry.Party> parties() {
        return List.of(
                new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "Alice"),
                new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "Bob"),
                new OpeningRegistry.Party("acme", OpeningRegistry.PartyKind.ORGANIZATION, "Acme 制造"),
                new OpeningRegistry.Party("ext-org", OpeningRegistry.PartyKind.ORGANIZATION, "外部持有人"));
    }

    private static List<OpeningRegistry.PropertyOwner> owners() {
        return List.of(
                new OpeningRegistry.PropertyOwner("alice", OpeningRegistry.OwnerKind.PERSON, "alice"),
                new OpeningRegistry.PropertyOwner("bob", OpeningRegistry.OwnerKind.PERSON, "bob"),
                new OpeningRegistry.PropertyOwner("acme", OpeningRegistry.OwnerKind.ORGANIZATION, "acme"),
                new OpeningRegistry.PropertyOwner("ext-org",
                        OpeningRegistry.OwnerKind.ORGANIZATION, "ext-org"),
                new OpeningRegistry.PropertyOwner("prod-1", OpeningRegistry.OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner("prod-2", OpeningRegistry.OwnerKind.PRODUCT, null));
    }

    /** 字面场景：发行 200，A/B 各 100 股。 */
    private static OpeningRegistry registry200() {
        return new OpeningRegistry(parties(), owners(),
                List.of(new OpeningRegistry.Security(SYM, "acme", 200, new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", SYM, 100,
                                new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("B", SYM, 100,
                                new BigDecimal("10.0000"), ShareClass.FLOAT)));
    }

    /** 产品主体结算用：发行 300，A/B 各 100，产品 P1/P2 各 50。 */
    private static OpeningRegistry registry300() {
        return new OpeningRegistry(parties(), owners(),
                List.of(new OpeningRegistry.Security(SYM, "acme", 300, new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", SYM, 100,
                                new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("B", SYM, 100,
                                new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("P1", SYM, 50,
                                new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("P2", SYM, 50,
                                new BigDecimal("10.0000"), ShareClass.FLOAT)));
    }
}
