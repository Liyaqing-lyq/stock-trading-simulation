package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.Fund;
import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 业务付款的资金腿接缝（v2）：一次经营付款从“某一种真实资金”付出，收款方也是显式资金桶。
 *
 * <p>与 v1 的关键差别：<b>资金三态不进对象</b>。每次结算都把当前
 * {@link FundState} 显式传进来、把候选 {@link FundState} 返回来，所以</p>
 * <ul>
 *   <li>同一个 payer 连续付两笔时，第二笔只能用调用方递进来的新三态，不可能退回旧快照；</li>
 *   <li>拒绝时返回的三态仍带资金层回执，但经济字段与入参相同；调用方要么整体提交要么整体丢弃；</li>
 *   <li>付款方/收款方的真实主体由业务层从当前三态自行派生（现金看账户 owner，存款/托管看档案），
 *       不采信任何自报的 owner 字段。</li>
 * </ul>
 *
 * <p>本接口只覆盖“一条腿一次调用”；N 条腿由调用方把上一次结果折叠进去顺序调用，任一腿失败即丢弃
 * 整次业务候选。这里不建批量平台，也不持有历史账。</p>
 */
public interface FundPayer {

    /**
     * 出款资金桶。
     *
     * @return 资金桶
     */
    Fund source();

    /**
     * 收款资金桶。
     *
     * @return 资金桶
     */
    Fund target();

    /**
     * 在当前资金三态上完成一次付款。
     *
     * @param commandId 业务命令标识（同时是资金腿的幂等标识）
     * @param amount    付款金额（正数、两位小数）
     * @param funds     当前资金三态（显式传入，不得复用旧结果）
     * @return 候选结果；拒绝时经济字段与入参相同
     */
    Result pay(String commandId, BigDecimal amount, FundState funds);

    /**
     * 资金层是否已有该标识的记录（成功或拒绝都算）。
     *
     * <p>业务层用它发现“资金已记账、业务未记账”的丢失候选，避免把零移动当新付款。</p>
     *
     * @param commandId 业务命令标识
     * @param funds     当前资金三态
     * @return 有记录返回 true
     */
    boolean hasRecord(String commandId, FundState funds);

    /**
     * 资金层是否已有该标识的**成功**记录。
     *
     * <p>业务层用它发现“业务记成功、资金缺记录”的分叉，不允许把这种状态当成功重放。</p>
     *
     * @param commandId 业务命令标识
     * @param funds     当前资金三态
     * @return 有成功记录返回 true
     */
    boolean hasAcceptedRecord(String commandId, FundState funds);

    /**
     * 出款资金桶的可用额：该桶当前余额减去该桶已有的合法冻结。
     *
     * <p>业务层的“够不够钱”判断（是否记 `FAILED_NO_CASH`、是否标 `OVERDUE`、期末实际付多少）
     * 必须和资金层用同一个数，否则会出现业务层认为有钱、资金层拒绝的自我矛盾。冻结只由
     * payer 构造期绑定的那份受信快照提供，因此这里不另传冻结参数——同一份快照只有一个来源，
     * 不会被第二处声明放大。与 {@link #pay} 一样，可用额严格按出款桶自身计算：现金与别家银行
     * 存款不能互相补足，同一主体的总资产也不是付款权。</p>
     *
     * @param funds 当前资金三态（显式传入，不得复用旧结果）
     * @return 可用额（非负、两位小数）
     */
    BigDecimal available(FundState funds);

    /**
     * 一次资金腿的结果。
     *
     * @param accepted 是否完成
     * @param reason   结果码（业务拒绝与资金拒绝都是稳定文本）
     * @param funds    候选资金三态；拒绝时经济字段与入参相同，只可能多一条拒绝回执
     */
    record Result(boolean accepted, String reason, FundState funds) {

        /** 校验结果码与三态。 */
        public Result {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("资金腿结果码不能为空");
            }
            Objects.requireNonNull(funds, "资金三态");
        }
    }
}
