package edu.cufe.auction.world;

import java.util.Objects;

/** 同一世界中的资源公司经营子账；现金、银行与证券仍由世界持有。 */
public record ResourceCompanyRun(String companyPartyId, String symbol, IntegratedResourcesModel.State ledger,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ExpansionOffer expansionOffer,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Cessation cessation,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) java.util.List<ResourcePrimaryEquity.Subscription> subscriptions) {
    /** 自确认时点起停止生产，并保留原契约、资产及责任。 */
    public record Cessation(String meetingId, long timeMillis) {
        public Cessation {
            if (meetingId == null || meetingId.isBlank() || timeMillis < 0) throw new IllegalArgumentException("停业决议或时点无效");
        }
    }
    /** 保留原扩产档案构造。 */
    public ResourceCompanyRun(String companyPartyId, String symbol, IntegratedResourcesModel.State ledger, ExpansionOffer expansionOffer) {
        this(companyPartyId, symbol, ledger, expansionOffer, null, java.util.List.of());
    }
    /** 保留原资源档案构造。 */
    public ResourceCompanyRun(String companyPartyId, String symbol, IntegratedResourcesModel.State ledger) { this(companyPartyId, symbol, ledger, null); }
    /** 校验主体与唯一台账。 */
    public ResourceCompanyRun {
        if (companyPartyId == null || companyPartyId.isBlank() || symbol == null || symbol.isBlank()) throw new IllegalArgumentException("资源公司身份为空");
        Objects.requireNonNull(ledger, "资源台账");
        subscriptions = subscriptions == null ? java.util.List.of() : java.util.List.copyOf(subscriptions);
        if (!subscriptions.isEmpty()) {
            long count = subscriptions.stream().mapToLong(ResourcePrimaryEquity.Subscription::shares).reduce(0L, Math::addExact);
            if (count > ResourcePrimaryEquity.MAX_SHARES || !ledger.capitalContributions().equals(ResourcePrimaryEquity.PRICE.multiply(java.math.BigDecimal.valueOf(count))))
                throw new IllegalArgumentException("已认购股份、实缴资本与上限不符");
        }
    }
    /** 从真实世界开局账户建立有限资源与历史设备资产。 */
    public static ResourceCompanyRun open(Config config, edu.cufe.auction.world.bank.FundState funds) {
        return new ResourceCompanyRun(config.companyPartyId(), config.symbol(), IntegratedResourcesModel.open(config.model(), funds), config.expansionOffer());
    }
    /** 唯一台账替换，保留公司与证券绑定。 */
    public ResourceCompanyRun withLedger(IntegratedResourcesModel.State next) {
        return new ResourceCompanyRun(companyPartyId, symbol, next, expansionOffer, cessation, subscriptions);
    }
    /** 可重演开局配置；不预置经理或董事预算。 */
    public record Config(String companyPartyId, String symbol, IntegratedResourcesModel.Config model,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ExpansionOffer expansionOffer) {
        /** 原档案没有扩产方案。 */
        public Config(String companyPartyId, String symbol, IntegratedResourcesModel.Config model) { this(companyPartyId, symbol, model, null); }
    }
    /** 冻结项目报价与技术许可；董事预算仍须在世界中真实通过，技术许可不代替董事授权。 */
    public record ExpansionOffer(java.math.BigDecimal capex, long buildMillis, int rampPeriods,
            long addCapacityKg, String permitRef, boolean failed) {
        /** 不允许零成本、零时滞或溢出能力。 */
        public ExpansionOffer {
            capex = CashLedger.money(capex);
            if (capex.signum() <= 0 || buildMillis <= 0 || rampPeriods <= 0 || addCapacityKg <= 0
                    || permitRef == null || permitRef.isBlank()) throw new IllegalArgumentException("资源扩产方案非法");
        }
    }
}
