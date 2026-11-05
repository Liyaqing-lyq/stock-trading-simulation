package edu.cufe.auction.engine;

import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
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
     * 订单被受理后触发（无论是否成交），携带完整结果。
     *
     * <p><b>为什么有这一个重载</b>：{@link OrderResult#getReason()} 在“已受理但剩余量被业务规则取消”
     * （例如 {@code SELF_TRADE_PREVENTED}）时非空。只拿 {@link Order} 与成交列表的旧回调看不到该原因，
     * 落盘与审计会丢掉它。</p>
     *
     * <p>默认实现转调旧重载，因此只实现 {@code onOrderAccepted(Order, List)} 的监听者无需改动；
     * 引擎对每次受理**只调用本方法一次**，实现方不要在里面再触发一次旧回调，否则会重复落盘。</p>
     *
     * @param result 受理结果（受理后的业务取消原因见 {@link OrderResult#getReason()}）
     */
    default void onOrderAccepted(OrderResult result) {
        onOrderAccepted(result.getOrder(), result.getTrades());
    }

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
