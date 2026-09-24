package edu.cufe.auction.market;

import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.Trade;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 行情发布者：把成交与行情快照广播给所有监听者。
 *
 * <p>线程安全：监听者列表用 {@link CopyOnWriteArrayList}；单个监听者抛异常会被捕获，
 * 不影响撮合主流程，只累计到 {@link #getListenerErrorCount()}。</p>
 */
public final class MarketDataPublisher {

    private final List<MarketDataListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong listenerErrors = new AtomicLong();

    /**
     * 注册监听者。
     *
     * @param listener 监听者
     */
    public void subscribe(MarketDataListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 取消注册。
     *
     * @param listener 监听者
     */
    public void unsubscribe(MarketDataListener listener) {
        listeners.remove(listener);
    }

    /** @return 当前监听者数量 */
    public int getListenerCount() {
        return listeners.size();
    }

    /** @return 被捕获的监听者异常次数（用于诊断 GUI/记录器故障） */
    public long getListenerErrorCount() {
        return listenerErrors.get();
    }

    /**
     * 发布一笔成交。
     *
     * @param trade 成交记录
     */
    public void publishTrade(Trade trade) {
        for (MarketDataListener listener : listeners) {
            try {
                listener.onTrade(trade);
            } catch (RuntimeException ex) {
                listenerErrors.incrementAndGet();
                System.err.println("[MarketDataPublisher] 监听者处理成交时异常：" + ex);
            }
        }
    }

    /**
     * 发布行情快照。
     *
     * @param snapshot 行情快照
     */
    public void publishSnapshot(MarketSnapshot snapshot) {
        for (MarketDataListener listener : listeners) {
            try {
                listener.onMarketSnapshot(snapshot);
            } catch (RuntimeException ex) {
                listenerErrors.incrementAndGet();
                System.err.println("[MarketDataPublisher] 监听者处理行情时异常：" + ex);
            }
        }
    }
}
