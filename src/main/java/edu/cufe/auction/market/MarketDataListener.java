package edu.cufe.auction.market;

import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.Trade;

/**
 * 行情监听者：GUI 面板、CSV 记录器、AI agent 都通过它接收推送。
 *
 * <p>回调在**引擎锁外**的调用线程中执行，实现必须做到：快速返回、内部自建队列、
 * 需要更新 UI 时用 {@code Platform.runLater()} 切回 JavaFX 线程。
 * 抛出异常不会影响撮合（发布者会捕获并计入错误统计）。</p>
 */
public interface MarketDataListener {

    /**
     * 收到一笔成交。
     *
     * @param trade 成交记录
     */
    default void onTrade(Trade trade) {
    }

    /**
     * 收到行情快照（每次订单受理/撤单后推送一次）。
     *
     * @param snapshot 行情快照
     */
    default void onMarketSnapshot(MarketSnapshot snapshot) {
    }
}
