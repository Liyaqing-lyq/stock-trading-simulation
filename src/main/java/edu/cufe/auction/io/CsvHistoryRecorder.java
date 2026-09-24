package edu.cufe.auction.io;

import edu.cufe.auction.account.PerformanceRank;
import edu.cufe.auction.engine.EngineListener;
import edu.cufe.auction.market.MarketDataListener;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.Trade;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CSV 历史记录器：订单流水、成交流水、期末排行榜（对应评分项“数据持久化”）。
 *
 * <p>实现 {@link EngineListener} 与 {@link MarketDataListener}，挂到引擎与行情总线上
 * 即可自动落盘。写操作在内部锁上串行化，可被多个 agent 线程并发调用。</p>
 */
public final class CsvHistoryRecorder implements EngineListener, MarketDataListener, Closeable {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final String ORDER_HEADER =
            "时间,订单ID,agent,symbol,方向,类型,价格,数量,已成交,剩余,状态,动作,原因";
    private static final String TRADE_HEADER =
            "时间,成交ID,symbol,成交价,数量,买方订单,买方agent,卖方订单,卖方agent,成交金额";
    private static final String RANK_HEADER =
            "名次,agent,名称,初始权益,期末权益,收益率,已实现盈亏,最大回撤%,平仓笔数,胜率";

    private final Path directory;
    private final Path ordersFile;
    private final Path tradesFile;
    private final Path rankingFile;
    private final Object lock = new Object();

    /**
     * 打开记录器（自动建目录并写表头）。
     *
     * @param directory 输出目录
     * @throws IOException 建目录或写文件失败
     */
    public CsvHistoryRecorder(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        this.ordersFile = directory.resolve("orders.csv");
        this.tradesFile = directory.resolve("trades.csv");
        this.rankingFile = directory.resolve("ranking.csv");
        Files.writeString(ordersFile, ORDER_HEADER + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(tradesFile, TRADE_HEADER + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** @return 输出目录 */
    public Path getDirectory() {
        return directory;
    }

    @Override
    public void onOrderAccepted(Order order, List<Trade> trades) {
        append(ordersFile, orderRow(order, "NEW",
                order.getStatus().name()));
    }

    @Override
    public void onOrderRejected(OrderRequest request, String reason) {
        String row = String.join(",",
                now(), "-", request.getAgentId(), request.getSymbol(), request.getSide().name(),
                request.getType().name(),
                request.getPrice() == null ? "MARKET" : request.getPrice().toPlainString(),
                String.valueOf(request.getQuantity()), "0", String.valueOf(request.getQuantity()),
                "REJECTED", "SUBMIT", escape(reason));
        append(ordersFile, row);
    }

    @Override
    public void onOrderCancelled(Order order) {
        append(ordersFile, orderRow(order, "CANCEL", order.getStatus().name()));
    }

    @Override
    public void onTrade(Trade trade) {
        String row = String.join(",",
                format(trade.getTimestampMillis()), String.valueOf(trade.getTradeId()), trade.getSymbol(),
                trade.getPrice().toPlainString(), String.valueOf(trade.getQuantity()),
                String.valueOf(trade.getBuyOrderId()), trade.getBuyAgentId(),
                String.valueOf(trade.getSellOrderId()), trade.getSellAgentId(),
                trade.getNotional().toPlainString());
        append(tradesFile, row);
    }

    /**
     * 写期末排行榜。
     *
     * @param ranks 排行榜（按名次）
     * @throws IOException 写文件失败
     */
    public void writeRanking(List<PerformanceRank> ranks) throws IOException {
        StringBuilder sb = new StringBuilder(RANK_HEADER).append(System.lineSeparator());
        for (PerformanceRank r : ranks) {
            sb.append(String.join(",",
                    String.valueOf(r.getRank()), r.getAgentId(), escape(r.getDisplayName()),
                    r.getInitialBalance().toPlainString(), r.getEquity().toPlainString(),
                    r.getReturnRate().toPlainString(), r.getRealizedPnl().toPlainString(),
                    r.getMaxDrawdown().toPlainString(), String.valueOf(r.getClosedTradeCount()),
                    r.getWinRate().toPlainString())).append(System.lineSeparator());
        }
        synchronized (lock) {
            Files.writeString(rankingFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private String orderRow(Order order, String action, String status) {
        return String.join(",",
                now(), String.valueOf(order.getOrderId()), order.getAgentId(), order.getSymbol(),
                order.getSide().name(), order.getType().name(),
                order.getLimitPrice() == null ? "MARKET" : order.getLimitPrice().toPlainString(),
                String.valueOf(order.getQuantity()), String.valueOf(order.getFilledQuantity()),
                String.valueOf(order.getRemainingQuantity()), status, action, "");
    }

    private void append(Path file, String row) {
        try {
            synchronized (lock) {
                try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(row);
                    w.newLine();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("写 CSV 失败：" + file, ex);
        }
    }

    private static String now() {
        return format(System.currentTimeMillis());
    }

    private static String format(long epochMillis) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.contains(",") || raw.contains("\"")) {
            return '"' + raw.replace("\"", "\"\"") + '"';
        }
        return raw;
    }

    @Override
    public void close() {
        // 每次写入都即时 flush 并关闭文件句柄，这里无需额外动作
    }

    @Override
    public String toString() {
        return "CsvHistoryRecorder[" + directory.toAbsolutePath() + "]";
    }
}
