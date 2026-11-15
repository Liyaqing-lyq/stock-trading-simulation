package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.FundState;

import java.util.List;
import java.util.Objects;

/**
 * 同一世界里陈酿库存公司的唯一子账：公司身份、证券代码、冻结经营配置与纯核台账。
 *
 * <p><b>不复制现金</b>：余额只在世界 {@link FundState} 的 {@code CashLedger} 账户表里。纯核的
 * 每一次动作都接收账户表并返回候选账户表，适配层据此重建候选 {@link FundState}（银行与托管
 * 三态原样保留），因此这里没有第二份余额。</p>
 *
 * <p><b>身份</b>：纯核按 {@code model.managerPersonId()} 授权，因此本类把“当前经理”固定在冻结
 * 配置里——换经理必须换配置，不在运行中改写。人物任职与执行权限仍由世界层核验。</p>
 *
 * <p><b>冻结配置不含未来结果落入人物投影</b>：需求序列、渠道配额与底价属于冻结情景，只在
 * {@link AgedDecisionTools#observation} 里按“公开/已发生”裁剪后出现；未来各期需求一律不投影。</p>
 *
 * @param companyPartyId 公司组织标识
 * @param symbol         该公司证券代码
 * @param config         冻结运行配置
 * @param ledger         唯一纯核台账
 */
public record AgedCompanyRun(String companyPartyId, String symbol, Config config,
                             AgedInventoryModel.State ledger) {

    /** 校验身份、配置与台账三者绑定。 */
    public AgedCompanyRun {
        requireText(companyPartyId, "公司主体");
        requireText(symbol, "证券代码");
        Objects.requireNonNull(config, "冻结配置");
        Objects.requireNonNull(ledger, "陈酿台账");
        if (!companyPartyId.equals(config.companyPartyId())
                || !companyPartyId.equals(ledger.config().companyPartyId())) {
            throw new IllegalArgumentException("公司主体与配置或台账不一致：" + companyPartyId);
        }
    }

    /**
     * 在本世界开局现金上建立该公司子账（无历史库存）。
     *
     * @param config 冻结配置
     * @param funds  世界资金三态
     * @return 开局子账
     */
    public static AgedCompanyRun open(Config config, FundState funds) {
        return open(config, funds, List.of());
    }

    /**
     * 在本世界开局现金上建立该公司子账，可带来源有据的历史原料 lot。
     *
     * <p>历史 lot 会同时抬高开局存货与开局权益，不能与本期采购混为一谈。</p>
     *
     * @param config 冻结配置
     * @param funds  世界资金三态
     * @param seeds  开局原料 lot（可为空表）
     * @return 开局子账
     */
    public static AgedCompanyRun open(Config config, FundState funds, List<AgedInventoryModel.RawLot> seeds) {
        Objects.requireNonNull(config, "冻结配置");
        Objects.requireNonNull(funds, "资金三态");
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config.model(), funds.accounts(),
                seeds == null ? List.of() : seeds);
        if (!opened.accepted()) {
            throw new IllegalArgumentException("陈酿开局被拒：" + opened.reason());
        }
        return new AgedCompanyRun(config.companyPartyId(), config.symbol(), config, opened.state());
    }

    /**
     * 用候选台账替换，保留身份、证券与冻结配置。
     *
     * @param next 候选台账
     * @return 新子账
     */
    public AgedCompanyRun withLedger(AgedInventoryModel.State next) {
        return new AgedCompanyRun(companyPartyId, symbol, config,
                Objects.requireNonNull(next, "候选台账"));
    }

    /**
     * 可重演的开局配置：不预置经理预算、需求结果或成功标记。
     *
     * @param companyPartyId 公司组织标识
     * @param symbol         证券代码
     * @param model          纯核冻结配置（含当前经理）
     */
    public record Config(String companyPartyId, String symbol, AgedInventoryModel.Config model) {

        /** 校验身份与纯核配置一致。 */
        public Config {
            requireText(companyPartyId, "公司主体");
            requireText(symbol, "证券代码");
            Objects.requireNonNull(model, "纯核配置");
            if (!companyPartyId.equals(model.companyPartyId())) {
                throw new IllegalArgumentException("公司主体与纯核配置不一致：" + companyPartyId);
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
