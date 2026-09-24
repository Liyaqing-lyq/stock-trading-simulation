package edu.cufe.auction.engine;

import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;

/**
 * 下单/撤单网关：agent 与 GUI 只依赖这个接口，不直接接触 {@link MatchingEngine} 内部结构。
 *
 * <p>实现必须是线程安全的，允许被多个 agent 线程并发调用。</p>
 */
public interface OrderGateway {

    /**
     * 提交订单。
     *
     * @param request 下单请求
     * @return 受理结果（含成交明细或拒绝原因）
     */
    OrderResult submit(OrderRequest request);

    /**
     * 撤销挂单。
     *
     * @param orderId 订单 id
     * @return 撤销成功返回 true；订单不存在或已终结返回 false
     */
    boolean cancel(long orderId);
}
