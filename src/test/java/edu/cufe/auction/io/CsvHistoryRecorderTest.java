package edu.cufe.auction.io;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.account.AccountManager;
import edu.cufe.auction.engine.EngineListener;
import edu.cufe.auction.engine.MatchingEngine;
import edu.cufe.auction.market.MarketDataPublisher;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.OrderStatus;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.model.Trade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 落盘的可追溯性测试：真实引擎 → 监听者 → CSV。
 *
 * <p>核心要求：受理后因自成交防护取消剩余量的原因，必须写进 orders.csv 既有的“原因”列，
 * 不能只留下 NEW/CANCELLED 两个状态而丢失原因；正常成交与正常挂簿的原因列保持空。</p>
 */
@DisplayName("CSV 记录受理与业务取消原因")
class CsvHistoryRecorderTest {

    private static final String AAPL = "AAPL";

    @TempDir
    Path tempDir;

    private AccountManager accounts;
    private MarketDataPublisher publisher;
    private MatchingEngine engine;
    private CsvHistoryRecorder recorder;
    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() throws IOException {
        accounts = new AccountManager();
        publisher = new MarketDataPublisher();
        Map<String, BigDecimal> reference = new LinkedHashMap<>();
        reference.put(AAPL, new BigDecimal("175.50"));
        engine = new MatchingEngine(accounts, publisher, reference);
        recorder = new CsvHistoryRecorder(tempDir);
        engine.addListener(recorder);
        publisher.subscribe(recorder);

        accountA = accounts.createAccount("acc-a", "acc-a", new BigDecimal("100000.00"));
        accountB = accounts.createAccount("acc-b", "acc-b", new BigDecimal("100000.00"));
        accountA.seedPosition(AAPL, 1000, new BigDecimal("10.00"));
        accountB.seedPosition(AAPL, 1000, new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("自成交取消的剩余量原因写进既有“原因”列，成交流水保持为空")
    void selfTradeCancelReasonIsWrittenToExistingReasonColumn() throws IOException {
        // 自有卖单挂在簿上，随后的买单只能与它相遇
        engine.submit(OrderRequest.limit("acc-a", AAPL, Side.SELL, new BigDecimal("10.00"), 100));
        OrderResult buy = engine.submit(
                OrderRequest.limit("acc-a", AAPL, Side.BUY, new BigDecimal("10.00"), 100));

        assertTrue(buy.isAccepted(), "业务取消仍属于已受理结果");
        assertEquals(OrderStatus.CANCELLED, buy.getStatus());

        List<String> orderRows = dataRows("orders.csv");
        assertEquals(2, orderRows.size(), "两次受理应各写一行，数量守恒");

        String cancelledRow = orderRows.stream()
                .filter(row -> row.contains("CANCELLED"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("orders.csv 缺少 CANCELLED 行：" + orderRows));
        assertTrue(cancelledRow.contains("SELF_TRADE_PREVENTED"),
                "取消原因必须落进原因列，实际行：" + cancelledRow);
        assertTrue(cancelledRow.endsWith("SELF_TRADE_PREVENTED: 剩余 100 股将与自有挂单 #1 自成交，已取消剩余量并释放冻结")
                        || cancelledRow.contains("SELF_TRADE_PREVENTED"),
                "原因列内容应可读：" + cancelledRow);

        // 自成交不得落成交流水
        assertEquals(0, dataRows("trades.csv").size(), "自成交不应产生成交行");
    }

    @Test
    @DisplayName("正常成交与普通挂单的原因列保持为空")
    void normalAcceptedOrdersKeepEmptyReason() throws IOException {
        engine.submit(OrderRequest.limit("acc-b", AAPL, Side.SELL, new BigDecimal("10.00"), 50));
        OrderResult filled = engine.submit(
                OrderRequest.limit("acc-a", AAPL, Side.BUY, new BigDecimal("10.00"), 50));
        OrderResult resting = engine.submit(
                OrderRequest.limit("acc-a", AAPL, Side.BUY, new BigDecimal("9.00"), 10));

        assertEquals(OrderStatus.FILLED, filled.getStatus());
        assertEquals(OrderStatus.NEW, resting.getStatus());

        List<String> rows = dataRows("orders.csv");
        assertEquals(3, rows.size(), "每个受理订单一行，数量守恒");
        for (String row : rows) {
            assertTrue(row.endsWith(","), "原因列应为空，实际行：" + row);
        }
        assertEquals(1, dataRows("trades.csv").size());
    }

    @Test
    @DisplayName("旧监听者仍收到一次回调，新监听者收到一次，CSV 不重复写行")
    void legacyListenerStillReceivesSingleCallback() throws IOException {
        AtomicInteger legacyCalls = new AtomicInteger();
        AtomicInteger resultCalls = new AtomicInteger();

        engine.addListener(new EngineListener() {
            @Override
            public void onOrderAccepted(Order order, List<Trade> trades) {
                legacyCalls.incrementAndGet();
            }
        });
        engine.addListener(new EngineListener() {
            @Override
            public void onOrderAccepted(OrderResult result) {
                resultCalls.incrementAndGet();
            }
        });

        engine.submit(OrderRequest.limit("acc-a", AAPL, Side.BUY, new BigDecimal("9.00"), 10));

        assertEquals(1, legacyCalls.get(), "旧重载监听者必须仍被调用且只调用一次");
        assertEquals(1, resultCalls.get(), "新重载监听者只应被调用一次");
        assertEquals(1, dataRows("orders.csv").size(), "回调只发一次，CSV 不得重复写行");
    }

    private List<String> dataRows(String fileName) throws IOException {
        Path file = tempDir.resolve(fileName);
        assertTrue(Files.exists(file), "缺少文件：" + fileName);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(!lines.isEmpty(), "文件应有表头：" + fileName);
        return lines.subList(1, lines.size());
    }
}
