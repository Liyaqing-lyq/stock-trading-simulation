package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.FundState;
import java.math.BigDecimal;
import java.util.Objects;

/** 同一世界中的水电物理与经营台账；不复制现金、证券或银行账。 */
public record HydroCompanyRun(Config config, HydroModel.State ledger) {
    /** 校验冻结配置。 */
    public HydroCompanyRun {
        Objects.requireNonNull(config); Objects.requireNonNull(ledger);
        if (!config.model().equals(ledger.config())) throw new IllegalArgumentException("水电配置分叉");
    }
    /** 从实际世界账户开局，不预置调度或经理。 */
    public static HydroCompanyRun open(Config config, FundState funds) {
        return new HydroCompanyRun(config, HydroModel.open(config.model(), funds.accounts()));
    }
    /** 只替换物理与经营状态。 */
    public HydroCompanyRun withLedger(HydroModel.State next) { return new HydroCompanyRun(config, next); }
    /** @return 已登记公司。 */
    public String companyPartyId() { return config.companyPartyId(); }
    /** 冻结的一座电站和检修报价；来水是有限外生情景，只有上帝可看完整未来序列。 */
    public record Config(String companyPartyId, String symbol, HydroModel.Config model,
                         long maintenanceMillis, BigDecimal maintenanceCost) {
        /** 身份、检修正时长及金额校验。 */
        public Config {
            if (companyPartyId == null || companyPartyId.isBlank() || symbol == null || symbol.isBlank()
                    || !companyPartyId.equals(model.companyOwnerId()) || maintenanceMillis <= 0
                    || maintenanceMillis % HydroModel.PHYSICS_TICK_MILLIS != 0) throw new IllegalArgumentException("水电开局非法");
            maintenanceCost = CashLedger.money(maintenanceCost);
            if (maintenanceCost.signum() <= 0) throw new IllegalArgumentException("检修报价非法");
        }
    }
}
