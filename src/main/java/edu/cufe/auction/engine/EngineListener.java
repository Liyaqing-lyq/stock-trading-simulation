package edu.cufe.auction.engine;

import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.Trade;

import java.util.List;

/**
 * 引擎事件监听：用于 CSV 落盘、GUI 刷新等旁路逻辑。
 *
 * <p>回调在**引擎锁外**触发，因此实现里可以安全地做 I/O；实现不得再回调引擎的
 * 变更方法（否则会在同一线程内重入锁，虽然不会死锁但语义混乱）。</p>
 */
public interface EngineListener {

    /**
     * 订单被受理后触发（无论是否成交）。
     *
     * @param order  受理的订单（终态见 order.getStatus()）
     * @param trades 本次受理产生的成交
     */
    default void onOrderAccepted(Order order, List<Trade> trades) {
    }

    /**
     * 订单被拒绝时触发。
     *
     * @param request 原始请求
     * @param reason  拒绝原因
     */
    default void onOrderRejected(OrderRequest request, String reason) {
    }

    /**
     * 撤单成功时触发。
     *
     * @param order 被撤销的订单
     */
    default void onOrderCancelled(Order order) {
    }
}
