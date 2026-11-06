package edu.cufe.auction.world;

/**
 * 可序列化的待办任务：世界里“已经排定、尚未执行”的最小单位。
 *
 * <p>与 M1.1 的内存 {@link Runnable} 不同，本类型只承载数据：到期时间、固定阶段、
 * 动作种类与动作参数。执行器由运行层按 {@link #kind()} 解析，因此进程重启后可以直接
 * 从日志重建待办，不需要重新构造闭包，也不会重跑已确认的动作。</p>
 *
 * <p>当前支持五类任务：现金转账与三类证券清算夹具动作（预留卖出、撤销预留、同批清算成交）
 * 都落在 COMPLETION 阶段；转账回报投递落在 DELIVERY 阶段。未登记的 kind 在构造期即被拒绝。</p>
 *
 * @param taskId        运行内唯一任务标识，不复用
 * @param dueTimeMillis 到期的模拟毫秒，必须为正
 * @param phase         到期时执行的固定阶段
 * @param kind          任务种类，见常量
 * @param payload       任务参数，规范化 JSON 文本
 */
public record PendingTask(String taskId, long dueTimeMillis, WorldScheduler.Phase phase,
                          String kind, String payload) {

    /** 现金转账任务：在 COMPLETION 阶段结算资金。 */
    public static final String CASH_TRANSFER_V1 = "CASH_TRANSFER_V1";

    /** 转账回报投递任务：在 DELIVERY 阶段把回执交给收件人。 */
    public static final String TRANSFER_NOTICE_V1 = "TRANSFER_NOTICE_V1";

    /** 预留卖出：在 COMPLETION 阶段按最新持仓冻结可卖批次。 */
    public static final String SELL_RESERVE_V1 = "SELL_RESERVE_V1";

    /** 撤销预留：在 COMPLETION 阶段只释放未成交的冻结。 */
    public static final String SELL_CANCEL_V1 = "SELL_CANCEL_V1";

    /** 同批清算成交：受信的系统夹具，在 COMPLETION 阶段一次提交现金、股份与预留。 */
    public static final String FILL_SETTLE_V1 = "FILL_SETTLE_V1";

    /** 校验必需字段与 kind/phase 对应关系。 */
    public PendingTask {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务标识不能为空");
        }
        if (dueTimeMillis <= 0) {
            throw new IllegalArgumentException("任务到期时间必须为正：" + dueTimeMillis);
        }
        if (phase == null || kind == null || payload == null) {
            throw new IllegalArgumentException("任务阶段、种类与参数不能为空");
        }
        switch (kind) {
            case CASH_TRANSFER_V1, SELL_RESERVE_V1, SELL_CANCEL_V1, FILL_SETTLE_V1 -> {
                if (phase != WorldScheduler.Phase.COMPLETION) {
                    throw new IllegalArgumentException(kind + " 必须落在 COMPLETION 阶段，收到 " + phase);
                }
            }
            case TRANSFER_NOTICE_V1 -> {
                if (phase != WorldScheduler.Phase.DELIVERY) {
                    throw new IllegalArgumentException(kind + " 必须落在 DELIVERY 阶段，收到 " + phase);
                }
            }
            default -> throw new IllegalArgumentException("未启用的任务种类：" + kind);
        }
    }
}
