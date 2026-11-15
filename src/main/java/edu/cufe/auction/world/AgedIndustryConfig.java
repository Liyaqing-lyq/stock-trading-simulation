package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.CashLedger.OpeningAccount;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 陈酿库存公司的有限 EXP 开局档案：账户、人物、渠道、冻结需求与纯核配置。
 *
 * <p><b>只是一份可重演的开局</b>：本类不建世界、不调度、不提交，也不替世界决定任职。账户与
 * 人物可直接用于最终显式 profile，任职与预算仍由世界层建立。</p>
 *
 * <p><b>参数是 EXP</b>：原料价、转产费、固定费、产能、陈酿期、货架期、报损与预付比例、渠道
 * 底价与配额、需求序列都是实验值，未做现实校准。</p>
 *
 * <p><b>未来需求不外泄</b>：{@link #demand()} 是冻结情景（服务端），只在终端售出时按当期生效；
 * 经理投影（{@link AgedDecisionTools#observation}）不含它，也不含终端现金。</p>
 */
public final class AgedIndustryConfig {

    private AgedIndustryConfig() {
    }

    /** 公司组织标识。 */
    public static final String COMPANY = "aged-company";
    /** 公司证券代码。 */
    public static final String SYMBOL = "AGED";
    /** 原料供应商。 */
    public static final String SUPPLIER = "aged-supplier";
    /** 转产费收款主体。 */
    public static final String CONVERSION = "aged-conversion";
    /** 固定费收款主体。 */
    public static final String FIXED = "aged-fixed";
    /** 终端消费者聚合主体。 */
    public static final String TERMINAL = "aged-terminal";
    /** 唯一渠道。 */
    public static final String CHANNEL = "AGED-CH";
    /** 公司现金账户。 */
    public static final String COMPANY_ACCOUNT = "aged-company-cash";
    /** 供应商账户。 */
    public static final String SUPPLIER_ACCOUNT = "aged-supplier-cash";
    /** 转产账户。 */
    public static final String CONVERSION_ACCOUNT = "aged-conversion-cash";
    /** 固定费账户。 */
    public static final String FIXED_ACCOUNT = "aged-fixed-cash";
    /** 终端账户。 */
    public static final String TERMINAL_ACCOUNT = "aged-terminal-cash";
    /** 渠道账户。 */
    public static final String CHANNEL_ACCOUNT = "aged-channel-cash";
    /** 经理人物（纯核按此授权）。 */
    public static final String MANAGER_PERSON = "aged-manager";

    /** 每期毫秒数（30 日班）。 */
    public static final long PERIOD_MILLIS = 30L * 86_400_000L;

    /**
     * 可直接交给世界开局现金子账的开局账户清单（带历史来源）。
     *
     * @return 有来源的开局账户
     */
    public static List<OpeningAccount> openings() {
        List<OpeningAccount> list = new ArrayList<>();
        list.add(new OpeningAccount(COMPANY_ACCOUNT, COMPANY, new BigDecimal("20000.00"), "EXP-AGED-2 开局资本"));
        list.add(new OpeningAccount(CHANNEL_ACCOUNT, CHANNEL, new BigDecimal("20000.00"), "EXP-AGED-2 渠道结算资金"));
        list.add(new OpeningAccount(TERMINAL_ACCOUNT, TERMINAL, new BigDecimal("20000.00"), "EXP-AGED-2 终端消费资金"));
        list.add(new OpeningAccount(SUPPLIER_ACCOUNT, SUPPLIER, new BigDecimal("0.00"), "EXP-AGED-2 原料供应商"));
        list.add(new OpeningAccount(CONVERSION_ACCOUNT, CONVERSION, new BigDecimal("0.00"), "EXP-AGED-2 转产服务"));
        list.add(new OpeningAccount(FIXED_ACCOUNT, FIXED, new BigDecimal("0.00"), "EXP-AGED-2 固定费用收款"));
        return List.copyOf(list);
    }

    /**
     * 同一批开局账户的现金权威视图（构造 {@code FundState} 用）。
     *
     * @return 账户标识到现金账户
     */
    public static Map<String, CashAccount> accounts() {
        Map<String, CashAccount> accounts = new LinkedHashMap<>();
        for (OpeningAccount opening : openings()) {
            accounts.put(opening.accountId(),
                    new CashAccount(opening.ownerId(), opening.cash(), opening.source()));
        }
        return Map.copyOf(accounts);
    }

    /**
     * 人物名单：只有身份与职责标签，不含任何业务结果。
     *
     * @param personId    人物标识
     * @param displayName 显示名
     * @param role        职责标签
     */
    public record Person(String personId, String displayName, String role) {
    }

    /**
     * 开局人物；纯核只授权一名经理，其它角色只能观察。
     *
     * @return 经理
     */
    public static List<Person> people() {
        return List.of(new Person(MANAGER_PERSON, "陈酿公司经理", "采购、投产、调价、发货与回款决定"));
    }

    /**
     * 冻结需求序列（服务端情景，不进入经理投影）。
     *
     * @return 需求条目
     */
    public static List<AgedInventoryModel.DemandQuote> demand() {
        List<AgedInventoryModel.DemandQuote> list = new ArrayList<>();
        for (long period = 2L; period <= 5L; period++) {
            list.add(new AgedInventoryModel.DemandQuote(period, CHANNEL, 60L));
        }
        // 远未来条目只用于验证“不投影未来需求”，不参与任何开局动作。
        list.add(new AgedInventoryModel.DemandQuote(9L, CHANNEL, 999L));
        return List.copyOf(list);
    }

    /**
     * 纯核冻结配置（EXP）。
     *
     * @return 陈酿纯核配置
     */
    public static AgedInventoryModel.Config modelConfig() {
        List<AgedInventoryModel.ChannelSpec> channels = List.of(
                new AgedInventoryModel.ChannelSpec(CHANNEL, CHANNEL_ACCOUNT, 100L,
                        new BigDecimal("40.00"), new BigDecimal("30.00")));
        return new AgedInventoryModel.Config(COMPANY, MANAGER_PERSON, COMPANY_ACCOUNT,
                SUPPLIER_ACCOUNT, CONVERSION_ACCOUNT, FIXED_ACCOUNT, TERMINAL_ACCOUNT,
                new BigDecimal("10.00"), new BigDecimal("5.00"), new BigDecimal("100.00"),
                PERIOD_MILLIS, 100L, 2L, 3L, 0, 2000, channels, demand(),
                AgedInventoryModel.EXP_LABEL);
    }

    /**
     * 公司子账的完整冻结配置。
     *
     * @return 可直接用于 {@link AgedCompanyRun#open}
     */
    public static AgedCompanyRun.Config runConfig() {
        return new AgedCompanyRun.Config(COMPANY, SYMBOL, modelConfig());
    }
}
