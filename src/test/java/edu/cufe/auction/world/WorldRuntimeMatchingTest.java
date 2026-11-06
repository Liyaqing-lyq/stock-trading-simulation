package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.SecuritiesLedger.Lot;
import edu.cufe.auction.world.SecuritiesLedger.SellReservation;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2.4b：世界订单在唯一世界日志中的可靠撮合。
 *
 * <p>覆盖被动价/改善/T+1、买卖两侧市价预算与整单拒绝、FIFO 与重启后的新单排尾、
 * 多证券隔离、同主体跨账户自成交、部分成交后的派生冻结与普通转账保护、越权撤单与收盘释放、
 * 同命令重发、同刻乱序、真实成交帧写失败/半帧恢复、档案与序列化边界。</p>
 */
@DisplayName("WorldRuntime：世界订单与可靠撮合")
class WorldRuntimeMatchingTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-match";
    private static final String SYM = "ACME1";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("卖 10×100 遇买限 10.20×60：60@10 成交，A 1600/B 400、股 40/160、剩卖 40 与预留 40")
    void restingAskFillsThroughRuntimeAndSurvivesRestart() throws IOException {
        Path file = tempDir.resolve("rest.journal");
        long position;
        try (WorldRuntime runtime = open(file)) {
            assertEquals("SCHEDULED:sell-1", runtime.scheduleOrder("c1", "sell-1", 10, "alice",
                    "A", SYM, Side.SELL, OrderType.LIMIT, bd("10.00"), 100));
            runtime.scheduleOrder("c2", "buy-1", 20, "bob", "B", SYM, Side.BUY,
                    OrderType.LIMIT, bd("10.20"), 60);
            runtime.advanceTo(30);

            assertTrue(report(runtime, "sell-1").accepted(), report(runtime, "sell-1").reason());
            assertTrue(report(runtime, "buy-1").accepted(), report(runtime, "buy-1").reason());
            assertEquals(PendingTask.EXCHANGE_ORDER_V1, report(runtime, "buy-1").kind());
            assertEquals(1, report(runtime, "buy-1").tradeIds().size());
            assertEquals(0, cash(runtime, "A").compareTo(bd("1600.00")));
            assertEquals(0, cash(runtime, "B").compareTo(bd("400.00")));
            assertEquals(40L, held(runtime, "A"));
            assertEquals(160L, held(runtime, "B"));
            assertEquals(0, WorldExchange.frozenCash(exchange(runtime), "B").signum(),
                    "买方已全成交，无剩余冻结");

            WorldExchange.Order sell = liveSell(runtime, "A");
            assertEquals(WorldExchange.OrderStatus.ACTIVE, sell.status());
            assertEquals(40L, sell.remaining());
            assertEquals(40L, reservation(runtime, "order-res:" + sell.orderId()),
                    "活动卖单与预留一一对应且数量等于剩余");
            assertEquals(0L, SecuritiesLedger.available(securities(runtime), "A", SYM, 0L),
                    "剩余 40 已被活动卖单冻结，不再重复计入可卖量");

            WorldExchange.Trade trade = exchange(runtime).trades().get(0);
            assertEquals(0, trade.price().compareTo(bd("10.00")));
            assertEquals(60L, trade.quantity());
            assertEquals(sell.orderId(), trade.sellOrderId());
            position = runtime.snapshot().position();
        }
        try (WorldRuntime runtime = open(file)) {
            assertEquals(position, runtime.snapshot().position());
            assertEquals(1, exchange(runtime).trades().size(), "恢复不得重放成交");
            runtime.advanceTo(40);
            assertEquals(1, exchange(runtime).trades().size());
            assertEquals(0, cash(runtime, "A").compareTo(bd("1600.00")));
        }
    }

    @Test
    @DisplayName("市价买扫 50@10/100@11 花 995 剩 5；1200 现金买 200 因初始冻结 2000 整单拒绝")
    void marketOrdersUseBestPricesAndRejectOversizedFreeze() throws IOException {
        Path file = tempDir.resolve("market.journal");
        try (WorldRuntime runtime = open(file,
                registry(share("A", SYM, 50), share("B", SYM, 100)), openings())) {
            runtime.scheduleOrder("c1", "sell-a", 10, "alice", "A", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 50);
            runtime.scheduleOrder("c2", "sell-b", 10, "bob", "B", SYM, Side.SELL,
                    OrderType.LIMIT, bd("11.00"), 100);
            runtime.scheduleOrder("c3", "buy-c", 20, "carol", "C", SYM, Side.BUY,
                    OrderType.MARKET, null, 100);
            runtime.advanceTo(30);

            assertTrue(report(runtime, "buy-c").accepted(), report(runtime, "buy-c").reason());
            assertEquals(2, report(runtime, "buy-c").tradeIds().size());
            assertEquals(0, cash(runtime, "C").compareTo(bd("5.00")), "50×10 + 45×11 = 995");
            WorldExchange.Order market = exchange(runtime).order(report(runtime, "buy-c").orderId());
            assertEquals(WorldExchange.OrderStatus.CANCELLED, market.status());
            assertEquals(5L, market.remaining(), "未成交 5 股立即撤销");

            runtime.scheduleOrder("c4", "buy-f", 40, "frank", "F", SYM, Side.BUY,
                    OrderType.MARKET, null, 200);
            long orders = exchange(runtime).orders().size();
            runtime.advanceTo(50);
            WorldExchange.OrderReport rejected = report(runtime, "buy-f");
            assertFalse(rejected.accepted());
            assertEquals(WorldExchange.REASON_INSUFFICIENT_CASH, rejected.reason(),
                    "卖一 10×200=2000 超过 1200，应整单拒绝");
            assertNull(rejected.orderId());
            assertTrue(rejected.tradeIds().isEmpty());
            assertEquals(orders, exchange(runtime).orders().size(), "拒绝不得新增订单");
            assertEquals(0, cash(runtime, "F").compareTo(bd("1200.00")));
        }
    }

    @Test
    @DisplayName("重启后订单号不回退；同价新单 D 排在 A/B/C 之后，FIFO 不被跳过")
    void restartKeepsSequencesAndFifoQueueTail() throws IOException {
        Path file = tempDir.resolve("fifo.journal");
        try (WorldRuntime runtime = open(file,
                registry(share("A", SYM, 100), share("B", SYM, 100),
                        share("C", SYM, 100), share("D", SYM, 100)), openings())) {
            runtime.scheduleOrder("c1", "s-a", 10, "alice", "A", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 100);
            runtime.scheduleOrder("c2", "s-b", 20, "bob", "B", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 100);
            runtime.scheduleOrder("c3", "s-c", 30, "carol", "C", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 100);
            runtime.advanceTo(40);
            assertEquals(3, exchange(runtime).orders().size());
        }
        try (WorldRuntime runtime = open(file,
                registry(share("A", SYM, 100), share("B", SYM, 100),
                        share("C", SYM, 100), share("D", SYM, 100)), openings())) {
            long maxBefore = exchange(runtime).orders().stream()
                    .mapToLong(WorldExchange.Order::orderId).max().orElseThrow();
            runtime.scheduleOrder("c4", "s-d", 50, "dave", "D", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 100);
            runtime.scheduleOrder("c5", "buy-e", 60, "erin", "E", SYM, Side.BUY,
                    OrderType.LIMIT, bd("10.20"), 250);
            runtime.advanceTo(70);

            List<WorldExchange.Trade> trades = exchange(runtime).trades();
            assertEquals(3, trades.size());
            assertEquals(orderId(runtime, "s-a"), trades.get(0).sellOrderId());
            assertEquals(orderId(runtime, "s-b"), trades.get(1).sellOrderId());
            assertEquals(orderId(runtime, "s-c"), trades.get(2).sellOrderId());
            assertEquals(50L, trades.get(2).quantity(), "C 只成交前 50 股");
            WorldExchange.Order d = exchange(runtime).order(orderId(runtime, "s-d"));
            assertEquals(WorldExchange.OrderStatus.ACTIVE, d.status(), "D 排尾，未被插队");
            assertEquals(100L, d.remaining());
            assertTrue(d.orderId() > maxBefore, "重启后订单号继续递增");
            assertTrue(d.sequence() > exchange(runtime).order(orderId(runtime, "s-c")).sequence());
            assertEquals(0, cash(runtime, "E").compareTo(bd("2500.00")));
        }
    }

    @Test
    @DisplayName("部分成交剩余冻结 408/可用 92 阻止普通转账；撤单释放后 500 可付")
    void partialFillFreezeBlocksTransferThenCancelReleases() throws IOException {
        Path file = tempDir.resolve("freeze.journal");
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleOrder("c1", "sell-1", 10, "alice", "A", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 60);
            runtime.scheduleOrder("c2", "buy-d", 20, "dave", "D", SYM, Side.BUY,
                    OrderType.LIMIT, bd("10.20"), 100);
            runtime.advanceTo(30);

            WorldExchange.Order buy = exchange(runtime).order(orderId(runtime, "buy-d"));
            assertEquals(WorldExchange.OrderStatus.ACTIVE, buy.status());
            assertEquals(40L, buy.remaining());
            assertEquals(0, cash(runtime, "D").compareTo(bd("500.00")));
            assertEquals(0, WorldExchange.frozenCash(exchange(runtime), "D").compareTo(bd("408.00")));
            assertEquals(0, WorldExchange.freeCash(exchange(runtime),
                    runtime.snapshot().accounts(), "D").compareTo(bd("92.00")));

            runtime.scheduleTransfer("c3", "pay-blocked", 40, "dave", "D", "A",
                    bd("100.00"), 0);
            runtime.advanceTo(50);
            assertFalse(runtime.snapshot().delivered().get(0).accepted());
            assertEquals(WorldRuntime.REASON_INSUFFICIENT_CASH,
                    runtime.snapshot().delivered().get(0).reason(), "普通转账不得花买单冻结");
            assertEquals(0, cash(runtime, "D").compareTo(bd("500.00")));

            runtime.scheduleCancelOrder("c4", "cancel-d", 60, "dave", buy.orderId());
            runtime.advanceTo(70);
            assertTrue(report(runtime, "cancel-d").accepted(), report(runtime, "cancel-d").reason());
            assertEquals(0, WorldExchange.frozenCash(exchange(runtime), "D").signum());
            assertEquals(0, WorldExchange.freeCash(exchange(runtime),
                    runtime.snapshot().accounts(), "D").compareTo(bd("500.00")));

            runtime.scheduleTransfer("c5", "pay-ok", 80, "dave", "D", "A", bd("500.00"), 0);
            runtime.advanceTo(100);
            assertEquals(0, cash(runtime, "D").signum());
            assertEquals(0, cash(runtime, "A").compareTo(bd("2100.00")));
        }
    }

    @Test
    @DisplayName("越权撤单失败；收盘撤销全簿并释放冻结，此前已排的后续订单也被拒绝")
    void unauthorizedCancelAndCloseReleaseEverythingAndRejectLaterOrders() throws IOException {
        Path file = tempDir.resolve("close.journal");
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleOrder("c1", "sell-1", 10, "alice", "A", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 100);
            runtime.scheduleOrder("c2", "buy-b", 20, "bob", "B", SYM, Side.BUY,
                    OrderType.LIMIT, bd("9.00"), 50);
            runtime.advanceTo(30);
            long sellOrder = orderId(runtime, "sell-1");

            runtime.scheduleCancelOrder("c3", "cancel-x", 35, "bob", sellOrder);
            runtime.advanceTo(40);
            assertFalse(report(runtime, "cancel-x").accepted());
            assertEquals(WorldExchange.REASON_NOT_AUTHORIZED, report(runtime, "cancel-x").reason());
            assertEquals(WorldExchange.OrderStatus.ACTIVE,
                    exchange(runtime).order(sellOrder).status(), "越权撤单不得改变挂单");

            runtime.scheduleCloseMarket("c4", "close-1", 50);
            runtime.scheduleOrder("c5", "after-close", 60, "carol", "C", SYM, Side.BUY,
                    OrderType.LIMIT, bd("9.50"), 10);
            runtime.advanceTo(70);

            assertTrue(report(runtime, "close-1").accepted(), report(runtime, "close-1").reason());
            assertEquals(2, report(runtime, "close-1").cancelledOrderIds().size(),
                    "收盘应撤销两张活动挂单");
            assertTrue(securities(runtime).reservations().isEmpty(), "卖单预留全部释放");
            assertEquals(0, WorldExchange.frozenCash(exchange(runtime), "B").signum(),
                    "买单冻结随收盘释放");
            assertEquals(2, exchange(runtime).orders().size(), "收盘后新申报不得新增订单");
            assertFalse(report(runtime, "after-close").accepted());
            assertEquals(WorldExchange.REASON_MARKET_CLOSED, report(runtime, "after-close").reason());
            assertNull(report(runtime, "after-close").orderId());
            assertEquals(0, cash(runtime, "A").compareTo(bd("1000.00")));
            assertEquals(0, cash(runtime, "B").compareTo(bd("1000.00")));
        }
        try (WorldRuntime runtime = open(file)) {
            assertTrue(exchange(runtime).closed(), "收盘状态必须持久");
            runtime.scheduleOrder("c6", "after-restart", 80, "carol", "C", SYM, Side.BUY,
                    OrderType.LIMIT, bd("9.50"), 10);
            runtime.advanceTo(90);
            assertFalse(report(runtime, "after-restart").accepted());
            assertEquals(WorldExchange.REASON_MARKET_CLOSED, report(runtime, "after-restart").reason());
        }
    }

    @Test
    @DisplayName("同命令重发返回原 SCHEDULED 且不新增提交；同标识异内容拒绝")
    void duplicateOrderCommandReplaysWithoutNewCommit() throws IOException {
        Path file = tempDir.resolve("duplicate.journal");
        String first;
        try (WorldRuntime runtime = open(file)) {
            first = runtime.scheduleOrder("c1", "sell-1", 10, "alice", "A", SYM,
                    Side.SELL, OrderType.LIMIT, bd("10.00"), 100);
            long position = runtime.snapshot().position();
            assertEquals(first, runtime.scheduleOrder("c1", "sell-1", 10, "alice", "A", SYM,
                    Side.SELL, OrderType.LIMIT, bd("10.00"), 100));
            assertEquals(position, runtime.snapshot().position(), "重发不得新增提交");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleOrder(
                    "c1", "sell-1", 10, "alice", "A", SYM, Side.SELL, OrderType.LIMIT,
                    bd("10.00"), 101), "同标识不同内容必须拒绝");
            runtime.advanceTo(20);
        }
        try (WorldRuntime runtime = open(file)) {
            long position = runtime.snapshot().position();
            assertEquals(first, runtime.scheduleOrder("c1", "sell-1", 10, "alice", "A", SYM,
                    Side.SELL, OrderType.LIMIT, bd("10.00"), 100), "重启后重发仍返回原回报");
            assertEquals(position, runtime.snapshot().position());
            assertEquals(1, exchange(runtime).orders().size(), "不得重复建单");
        }
    }

    @Test
    @DisplayName("同种子下同刻订单的结果与排程入队顺序无关")
    void sameSeedMakesSameInstantOrderIndependentOfInsertionOrder() throws IOException {
        WorldExchange.State forward = sameInstant(tempDir.resolve("same-a.journal"), false);
        WorldExchange.State reversed = sameInstant(tempDir.resolve("same-b.journal"), true);
        assertEquals(forward, reversed, "同种子、同时点必须得到同一订单/成交/序号");
        assertEquals(forward.reports().keySet(), reversed.reports().keySet());
    }

    private WorldExchange.State sameInstant(Path file, boolean reverse) throws IOException {
        try (WorldRuntime runtime = open(file)) {
            List<String> tasks = new ArrayList<>(List.of("s-ask", "b-bid"));
            if (reverse) {
                Collections.reverse(tasks);
            }
            for (String taskId : tasks) {
                if ("s-ask".equals(taskId)) {
                    runtime.scheduleOrder("c-" + taskId, taskId, 10, "alice", "A", SYM,
                            Side.SELL, OrderType.LIMIT, bd("10.00"), 100);
                } else {
                    runtime.scheduleOrder("c-" + taskId, taskId, 10, "bob", "B", SYM,
                            Side.BUY, OrderType.LIMIT, bd("10.20"), 60);
                }
            }
            runtime.advanceTo(20);
            return exchange(runtime);
        }
    }

    @Test
    @DisplayName("已有挂单与预留后真正成交帧写失败：钱/股/簿/冻结/待办保持旧版，恢复只成交一次")
    void interruptDuringOrderFillKeepsEverythingAndCompletesOnce() throws IOException {
        Path file = tempDir.resolve("interrupt-fill.journal");
        long position;
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleOrder("c1", "sell-1", 10, "alice", "A", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 100);
            runtime.scheduleOrder("c2", "buy-1", 20, "bob", "B", SYM, Side.BUY,
                    OrderType.LIMIT, bd("10.20"), 60);
            runtime.advanceTo(10);

            // 故障点必须是成交帧：卖单已挂簿并冻结，买单仍在待办，钱股未动
            assertEquals(100L, reservation(runtime, "order-res:1"));
            assertTrue(report(runtime, "sell-1").accepted());
            assertEquals(1, exchange(runtime).orders().size());
            assertTrue(exchange(runtime).trades().isEmpty());
            assertEquals(0, cash(runtime, "A").compareTo(bd("1000.00")));
            assertEquals(1, runtime.snapshot().pending().size());
            assertEquals("buy-1", runtime.snapshot().pending().get(0).taskId());
            position = runtime.snapshot().position();

            try {
                Thread.currentThread().interrupt();
                assertThrows(IOException.class, () -> runtime.advanceTo(30),
                        "线程中断应使成交帧真实写入失败");
            } finally {
                Thread.interrupted();
            }
            assertTrue(runtime.isPaused());
            assertEquals(position, runtime.snapshot().position(), "失败批次不得发布");
            assertEquals(1, exchange(runtime).orders().size(), "不得出现半成品买单");
            assertTrue(exchange(runtime).trades().isEmpty(), "不得留下单边成交");
            assertEquals(100L, reservation(runtime, "order-res:1"));
            assertEquals(0, cash(runtime, "A").compareTo(bd("1000.00")));
            assertEquals(0, cash(runtime, "B").compareTo(bd("1000.00")));
            assertEquals(1, runtime.snapshot().pending().size(), "未确认的买单任务仍在待办");
            assertThrows(IllegalStateException.class, () -> runtime.scheduleOrder(
                    "c3", "sell-2", 40, "alice", "A", SYM, Side.SELL, OrderType.LIMIT,
                    bd("9.50"), 10), "暂停期间不得接受新排程");
        }
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(30);
            assertEquals(1, exchange(runtime).trades().size(), "恢复后只成交一次");
            assertEquals(0, cash(runtime, "A").compareTo(bd("1600.00")));
            assertEquals(0, cash(runtime, "B").compareTo(bd("400.00")));
            assertEquals(40L, held(runtime, "A"));
            assertEquals(160L, held(runtime, "B"));
            assertEquals(40L, reservation(runtime, "order-res:1"));
            assertEquals(1, reportCount(runtime, "buy-1"));
            runtime.advanceTo(60);
        }
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(90);
            assertEquals(1, exchange(runtime).trades().size(), "重开不得重复成交");
            assertEquals(0, cash(runtime, "A").compareTo(bd("1600.00")));
            assertEquals(1, reportCount(runtime, "buy-1"));
        }
    }

    @Test
    @DisplayName("截断未确认的成交完成帧：旧簿/冻结保留，恢复后只成交一次")
    void truncatedOrderFillFrameRecoversAndCompletesOnce() throws IOException {
        Path file = tempDir.resolve("truncate-fill.journal");
        long boundary;
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleOrder("c1", "sell-1", 10, "alice", "A", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 100);
            runtime.scheduleOrder("c2", "buy-1", 20, "bob", "B", SYM, Side.BUY,
                    OrderType.LIMIT, bd("10.20"), 60);
            runtime.advanceTo(10);
            boundary = Files.size(file);
            runtime.advanceTo(30);
            assertTrue(Files.size(file) > boundary + 20, "成交完成帧必须有足够字节可截半");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(boundary + 20);
            channel.force(true);
        }
        try (WorldRuntime runtime = open(file)) {
            assertEquals(1, exchange(runtime).orders().size(), "未确认成交帧不得建买单");
            assertTrue(exchange(runtime).trades().isEmpty());
            assertEquals(100L, reservation(runtime, "order-res:1"));
            assertEquals(0, cash(runtime, "B").compareTo(bd("1000.00")));
            assertEquals(1, runtime.snapshot().pending().size());

            runtime.advanceTo(30);
            assertEquals(1, exchange(runtime).trades().size());
            assertEquals(0, cash(runtime, "A").compareTo(bd("1600.00")));
            assertEquals(0, cash(runtime, "B").compareTo(bd("400.00")));
            assertEquals(40L, reservation(runtime, "order-res:1"));
        }
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(60);
            assertEquals(1, exchange(runtime).trades().size(), "重开不得重复成交");
            assertEquals(0, cash(runtime, "B").compareTo(bd("400.00")));
        }
    }

    @Test
    @DisplayName("非法价格/市价带价/未知证券/越权/过去时点在提交前拒绝，不写批次")
    void schedulingValidationRejectsBeforeCommit() throws IOException {
        Path file = tempDir.resolve("validation.journal");
        try (WorldRuntime runtime = open(file)) {
            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleOrder(
                    "c1", "x1", 10, "alice", "A", SYM, Side.BUY, OrderType.LIMIT,
                    bd("10.001"), 10), "限价必须精确到分");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleOrder(
                    "c2", "x2", 10, "alice", "A", SYM, Side.BUY, OrderType.MARKET,
                    bd("10.00"), 10), "市价单不得携带限价");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleOrder(
                    "c3", "x3", 10, "alice", "A", "NOPE", Side.BUY, OrderType.LIMIT,
                    bd("10.00"), 10), "未知证券");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleOrder(
                    "c4", "x4", 10, "bob", "A", SYM, Side.SELL, OrderType.LIMIT,
                    bd("10.00"), 10), "越权使用他人账户");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleOrder(
                    "c5", "x5", 10, "alice", "A", SYM, Side.SELL, OrderType.LIMIT,
                    bd("10.00"), 0), "数量必须为正");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleOrder(
                    "c6", "x6", 0, "alice", "A", SYM, Side.SELL, OrderType.LIMIT,
                    bd("10.00"), 10), "到期必须晚于当前时点");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleOrder(
                    "c7", "x7", 10, "alice", "A", SYM, null, OrderType.LIMIT,
                    bd("10.00"), 10), "方向不能为空");
            assertEquals(position, runtime.snapshot().position());
            assertTrue(runtime.snapshot().pending().isEmpty());
        }
    }

    @Test
    @DisplayName("无对手方市价单到期业务拒绝：不建单、不动现金、待办清除")
    void marketOrderWithoutCounterpartyRejectedAtDueTime() throws IOException {
        Path file = tempDir.resolve("no-liquidity.journal");
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleOrder("c1", "buy-c", 10, "carol", "C", SYM, Side.BUY,
                    OrderType.MARKET, null, 10);
            runtime.advanceTo(20);
            WorldExchange.OrderReport report = report(runtime, "buy-c");
            assertFalse(report.accepted());
            assertEquals(WorldExchange.REASON_NO_LIQUIDITY, report.reason());
            assertNull(report.orderId());
            assertTrue(exchange(runtime).orders().isEmpty());
            assertTrue(exchange(runtime).trades().isEmpty());
            assertEquals(0, cash(runtime, "C").compareTo(bd("1000.00")));
            assertTrue(runtime.snapshot().pending().isEmpty());
        }
    }

    @Test
    @DisplayName("档案必须精确五个模块；旧档案无 exchange 键，清算夹具与撮合接口互不开放")
    void manifestProfilesAndInterfacesAreIsolated() throws IOException {
        Map<String, Integer> exact = new TreeMap<>();
        exact.put("cash", 1);
        exact.put("scheduler", 1);
        exact.put("registry", 1);
        exact.put("securities", 1);
        exact.put("matching", 1);
        assertNotNull(new RunManifest(RUN, SEED,
                RunManifest.RULES_SECURITIES_MATCHING_EXPERIMENT_V1, exact, Map.of(), "none"));

        Map<String, Integer> missing = new TreeMap<>(exact);
        missing.remove("matching");
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_SECURITIES_MATCHING_EXPERIMENT_V1, missing, Map.of(), "none"));
        Map<String, Integer> extra = new TreeMap<>(exact);
        extra.put("bank", 1);
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_SECURITIES_MATCHING_EXPERIMENT_V1, extra, Map.of(), "none"));
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_SECURITIES_CLEARING_EXPERIMENT_V1, exact, Map.of(), "none"));

        Path noRegistry = tempDir.resolve("no-registry.journal");
        assertThrows(IllegalArgumentException.class,
                () -> new WorldRuntime(noRegistry, matchingManifest(RUN, SEED), openings()));
        assertFalse(Files.exists(noRegistry), "拒绝时不得创建日志");

        Path matching = tempDir.resolve("matching.journal");
        try (WorldRuntime runtime = open(matching)) {
            assertTrue(runtime.snapshot().exchange().isPresent());
            assertTrue(runtime.snapshot().securities().isPresent());
            assertThrows(IllegalStateException.class, () -> runtime.scheduleReserveSell(
                    "c1", "r1", 10, "alice", "A", SYM, "res-1", 10),
                    "撮合档案禁用清算夹具预留");
            assertThrows(IllegalStateException.class, () -> runtime.scheduleCancelSell(
                    "c2", "r2", 10, "alice", "res-1"), "撮合档案禁用清算夹具撤销");
            assertThrows(IllegalStateException.class, () -> runtime.scheduleFixtureFill(
                    "c3", "f1", 10, "res-1", "B", bd("10.00"), 10),
                    "撮合档案禁用绕过订单簿的夹具成交");
        }
        assertTrue(snapshotPayload(matching, 1).contains("\"exchange\""));
        assertTrue(snapshotPayload(matching, 1).contains("\"securities\""));

        Path clearing = tempDir.resolve("clearing.journal");
        try (WorldRuntime runtime = new WorldRuntime(clearing,
                securitiesManifest(RUN, SEED), openings(), registry200())) {
            assertThrows(IllegalStateException.class, () -> runtime.scheduleOrder(
                    "c1", "o1", 10, "alice", "A", SYM, Side.BUY, OrderType.LIMIT,
                    bd("10.00"), 10), "清算档案不得启用订单接口");
            assertThrows(IllegalStateException.class, () -> runtime.scheduleCancelOrder(
                    "c2", "o2", 10, "alice", 1L));
            assertThrows(IllegalStateException.class, () -> runtime.scheduleCloseMarket(
                    "c3", "o3", 10));
        }
        assertFalse(snapshotPayload(clearing, 1).contains("\"exchange\""));

        Path cashFile = tempDir.resolve("cash.journal");
        try (WorldRuntime runtime = new WorldRuntime(cashFile, cashManifest(RUN, SEED), openings())) {
            assertTrue(runtime.snapshot().exchange().isEmpty());
        }
        assertFalse(snapshotPayload(cashFile, 1).contains("\"exchange\""));
        assertFalse(snapshotPayload(cashFile, 1).contains("\"securities\""));

        assertThrows(IOException.class, () -> new WorldRuntime(matching,
                securitiesManifest(RUN, SEED), openings(), registry200()).close(),
                "清算档案不得打开撮合日志");

        assertThrows(IllegalArgumentException.class, () -> new PendingTask("x1", 10,
                WorldScheduler.Phase.DELIVERY, PendingTask.EXCHANGE_ORDER_V1, "{}"),
                "订单任务必须落在 COMPLETION");
    }

    @Test
    @DisplayName("篡改成交数量或删除成交时恢复明确拒绝")
    void tamperedExchangeStateRejected() throws IOException {
        Path source = tempDir.resolve("tamper-src.journal");
        try (WorldRuntime runtime = open(source)) {
            runtime.scheduleOrder("c1", "sell-1", 10, "alice", "A", SYM, Side.SELL,
                    OrderType.LIMIT, bd("10.00"), 100);
            runtime.scheduleOrder("c2", "buy-1", 20, "bob", "B", SYM, Side.BUY,
                    OrderType.LIMIT, bd("10.20"), 60);
            runtime.advanceTo(30);
        }
        long fillPosition = positionOf(source, "task:buy-1");

        Path quantity = tempDir.resolve("tamper-quantity.journal");
        rewriteWithSnapshot(source, quantity, fillPosition, payload -> {
            Map<String, Object> state = readMap(payload);
            List<Object> trades = asList(asMap(state.get("exchange")).get("trades"));
            asMap(trades.get(0)).put("quantity", 61);
            return writeJson(state);
        });
        IOException mismatch = assertThrows(IOException.class, () -> open(quantity).close(),
                "成交明细与订单累计量不一致必须拒绝");
        assertTrue(mismatch.getMessage().contains("成交"), mismatch.getMessage());

        Path removed = tempDir.resolve("tamper-removed.journal");
        rewriteWithSnapshot(source, removed, fillPosition, payload -> {
            Map<String, Object> state = readMap(payload);
            Map<String, Object> exchange = asMap(state.get("exchange"));
            List<Object> trades = asList(exchange.get("trades"));
            trades.clear();
            return writeJson(state);
        });
        IOException lost = assertThrows(IOException.class, () -> open(removed).close(),
                "删除成交但订单已成交必须拒绝");
        assertTrue(lost.getMessage().contains("成交"), lost.getMessage());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private WorldRuntime open(Path file) throws IOException {
        return open(file, registry200(), openings());
    }

    private WorldRuntime open(Path file, OpeningRegistry registry,
                              List<OpeningAccount> opening) throws IOException {
        return new WorldRuntime(file, matchingManifest(RUN, SEED), opening, registry);
    }

    private static WorldExchange.State exchange(WorldRuntime runtime) {
        return runtime.snapshot().exchange().orElseThrow(() -> new AssertionError("缺少交易状态"));
    }

    private static SecuritiesLedger.State securities(WorldRuntime runtime) {
        return runtime.snapshot().securities().orElseThrow(() -> new AssertionError("缺少证券状态"));
    }

    private static WorldExchange.OrderReport report(WorldRuntime runtime, String taskId) {
        WorldExchange.OrderReport found = exchange(runtime).reports().get(taskId);
        assertTrue(found != null, "缺少订单结果：" + taskId + "，已有 "
                + exchange(runtime).reports().keySet());
        return found;
    }

    private static int reportCount(WorldRuntime runtime, String taskId) {
        return exchange(runtime).reports().containsKey(taskId) ? 1 : 0;
    }

    private static long orderId(WorldRuntime runtime, String taskId) {
        return report(runtime, taskId).orderId();
    }

    private static WorldExchange.Order liveSell(WorldRuntime runtime, String accountId) {
        return exchange(runtime).orders().stream()
                .filter(order -> order.accountId().equals(accountId) && order.live())
                .findFirst().orElseThrow(() -> new AssertionError("缺少活动订单：" + accountId));
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

    private static long reservation(WorldRuntime runtime, String reservationId) {
        SellReservation found = SecuritiesLedger.reservation(securities(runtime), reservationId);
        assertNotNull(found, "缺少活动预留：" + reservationId);
        return found.quantity();
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
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

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private record Share(String account, String symbol, long quantity) {
    }

    private static Share share(String account, String symbol, long quantity) {
        return new Share(account, symbol, quantity);
    }

    private static OpeningRegistry registry(Share... shares) {
        Map<String, Long> issued = new LinkedHashMap<>();
        List<OpeningRegistry.ShareAllocation> allocations = new ArrayList<>();
        for (Share share : shares) {
            issued.merge(share.symbol(), share.quantity(), Math::addExact);
            allocations.add(new OpeningRegistry.ShareAllocation(share.account(), share.symbol(),
                    share.quantity(), bd("10.0000"), ShareClass.FLOAT));
        }
        List<OpeningRegistry.Security> securities = new ArrayList<>();
        issued.forEach((symbol, quantity) -> securities.add(
                new OpeningRegistry.Security(symbol, "acme", quantity, bd("10.00"))));
        return new OpeningRegistry(parties(), owners(), securities, allocations);
    }

    private static OpeningRegistry registry200() {
        return registry(share("A", SYM, 100), share("B", SYM, 100));
    }

    private static List<OpeningRegistry.Party> parties() {
        return List.of(
                new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "Alice"),
                new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "Bob"),
                new OpeningRegistry.Party("carol", OpeningRegistry.PartyKind.PERSON, "Carol"),
                new OpeningRegistry.Party("dave", OpeningRegistry.PartyKind.PERSON, "Dave"),
                new OpeningRegistry.Party("erin", OpeningRegistry.PartyKind.PERSON, "Erin"),
                new OpeningRegistry.Party("frank", OpeningRegistry.PartyKind.PERSON, "Frank"),
                new OpeningRegistry.Party("acme", OpeningRegistry.PartyKind.ORGANIZATION, "Acme"));
    }

    private static List<OpeningRegistry.PropertyOwner> owners() {
        return List.of(
                new OpeningRegistry.PropertyOwner("alice", OpeningRegistry.OwnerKind.PERSON, "alice"),
                new OpeningRegistry.PropertyOwner("bob", OpeningRegistry.OwnerKind.PERSON, "bob"),
                new OpeningRegistry.PropertyOwner("carol", OpeningRegistry.OwnerKind.PERSON, "carol"),
                new OpeningRegistry.PropertyOwner("dave", OpeningRegistry.OwnerKind.PERSON, "dave"),
                new OpeningRegistry.PropertyOwner("erin", OpeningRegistry.OwnerKind.PERSON, "erin"),
                new OpeningRegistry.PropertyOwner("frank", OpeningRegistry.OwnerKind.PERSON, "frank"),
                new OpeningRegistry.PropertyOwner("acme", OpeningRegistry.OwnerKind.ORGANIZATION, "acme"));
    }

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount("A", "alice", bd("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("B", "bob", bd("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("C", "carol", bd("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("D", "dave", bd("1100.00"), "实验开局历史禀赋"),
                new OpeningAccount("E", "erin", bd("5000.00"), "实验开局历史禀赋"),
                new OpeningAccount("F", "frank", bd("1200.00"), "实验开局历史禀赋"));
    }

    private static RunManifest matchingManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        return new RunManifest(runId, seed,
                RunManifest.RULES_SECURITIES_MATCHING_EXPERIMENT_V1, modules, Map.of(),
                RunManifest.MODEL_NONE);
    }

    private static RunManifest securitiesManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        return new RunManifest(runId, seed,
                RunManifest.RULES_SECURITIES_CLEARING_EXPERIMENT_V1, modules, Map.of(),
                RunManifest.MODEL_NONE);
    }

    private static RunManifest cashManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        return new RunManifest(runId, seed, RunManifest.RULES_CASH_WORLD_V1, modules, Map.of(),
                RunManifest.MODEL_NONE);
    }
}
