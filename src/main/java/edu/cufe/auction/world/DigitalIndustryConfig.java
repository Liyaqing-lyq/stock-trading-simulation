package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.DigitalCompanyRun.Mandate;
import edu.cufe.auction.world.DigitalServiceModel.Approval;
import edu.cufe.auction.world.DigitalServiceModel.Evidence;
import edu.cufe.auction.world.DigitalServiceModel.Stage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数字技术服务公司的有限 EXP 开局档案：账户、人物、冻结配置、阶段观察引用与治理决议。
 *
 * <p><b>只是一份可重演的开局</b>：本类不建世界、不调度、不提交，也不替世界决定任职。它给出的
 * 五个账户与两个人名可以直接用于最终显式 profile，其余（任职、预算、工时）仍由世界层建立。</p>
 *
 * <p><b>参数是 EXP</b>：单价、成本、需求、预算、容量、阶段成本与工期都是实验值，未做现实校准，
 * 不得当作真实行业数据引用。</p>
 *
 * <p><b>未来结果不外泄</b>：{@code pilotPasses}/{@code validationPasses} 只在
 * {@link #modelConfig()} 里（服务端冻结），阶段观察引用只在 {@link #evidence()} 里；人物名单
 * （{@link #people()}）只带身份与职责标签，不含任何业务结果。</p>
 */
public final class DigitalIndustryConfig {

    private DigitalIndustryConfig() {
    }

    /** 公司组织标识。 */
    public static final String COMPANY = "digital-company";
    /** 公司证券代码。 */
    public static final String SYMBOL = "DIGITAL";
    /** 运营收款主体。 */
    public static final String OPS = "digital-ops";
    /** 服务客户。 */
    public static final String CUSTOMER = "digital-customer";
    /** 研究承包方。 */
    public static final String CONTRACTOR = "digital-lab";
    /** 外部注资方。 */
    public static final String SPONSOR = "digital-sponsor";
    /** 公司现金账户。 */
    public static final String COMPANY_ACCOUNT = "digital-company-cash";
    /** 运营账户。 */
    public static final String OPS_ACCOUNT = "digital-ops-cash";
    /** 客户结算账户。 */
    public static final String CUSTOMER_ACCOUNT = "digital-customer-cash";
    /** 承包方账户。 */
    public static final String CONTRACTOR_ACCOUNT = "digital-lab-cash";
    /** 注资方账户。 */
    public static final String SPONSOR_ACCOUNT = "digital-sponsor-cash";
    /** 经理人物（世界开局任职到本公司）。 */
    public static final String MANAGER_PERSON = "digital-manager";
    /** 治理签署人（冻结决议的记录人，不是运行中的审批流）。 */
    public static final String CHAIR_PERSON = "digital-chair";
    /** 商业化批准引用（与纯核配置里的键一致）。 */
    public static final String APPROVAL_COMMERCIAL = "AP-DIGITAL-COMMERCIAL-1";
    /** PILOT 完成观察引用。 */
    public static final String EVIDENCE_PILOT = "digital-pilot-report";
    /** VALIDATION 完成观察引用。 */
    public static final String EVIDENCE_VALIDATION = "digital-validation-report";
    /** 注资治理决议引用。 */
    public static final String MANDATE_CAPITAL = "GV-DIGITAL-CAPITAL-1";

    private static final long DAY = DigitalServiceModel.DAY_MILLIS;

    /**
     * 可直接交给世界开局现金子账的开局账户清单（带历史来源）。
     *
     * @return 有来源的开局账户
     */
    public static List<OpeningAccount> openings() {
        List<OpeningAccount> list = new ArrayList<>();
        list.add(new OpeningAccount(COMPANY_ACCOUNT, COMPANY, new BigDecimal("20000.00"), "EXP-DIGITAL-1 开局资本"));
        list.add(new OpeningAccount(OPS_ACCOUNT, OPS, new BigDecimal("0.00"), "EXP-DIGITAL-1 运营收款"));
        list.add(new OpeningAccount(CUSTOMER_ACCOUNT, CUSTOMER, new BigDecimal("12000.00"), "EXP-DIGITAL-1 客户结算资金"));
        list.add(new OpeningAccount(CONTRACTOR_ACCOUNT, CONTRACTOR, new BigDecimal("0.00"), "EXP-DIGITAL-1 承包方"));
        list.add(new OpeningAccount(SPONSOR_ACCOUNT, SPONSOR, new BigDecimal("50000.00"), "EXP-DIGITAL-1 外部注资方"));
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
     * 人物名单：只有身份与职责标签，不含任何业务结果或预算。
     *
     * @param personId    人物标识
     * @param displayName 显示名
     * @param role        职责标签
     */
    public record Person(String personId, String displayName, String role) {
    }

    /**
     * 开局人物。
     *
     * @return 经理与治理签署人
     */
    public static List<Person> people() {
        return List.of(new Person(MANAGER_PERSON, "数字服务公司经理", "经营与关期前的日常决定"),
                new Person(CHAIR_PERSON, "数字服务公司董事长", "开局前已作出的注资决议签署人"));
    }

    /**
     * 纯核冻结配置（EXP）。
     *
     * @return 数字服务纯核配置
     */
    public static DigitalServiceModel.Config modelConfig() {
        Map<String, Approval> approvals = new LinkedHashMap<>();
        approvals.put(APPROVAL_COMMERCIAL, new Approval(APPROVAL_COMMERCIAL,
                DigitalServiceModel.APPROVAL_COMMERCIAL, 0L));
        return new DigitalServiceModel.Config(COMPANY, COMPANY_ACCOUNT, OPS_ACCOUNT,
                CUSTOMER, CUSTOMER_ACCOUNT, CONTRACTOR, CONTRACTOR_ACCOUNT,
                SPONSOR, SPONSOR_ACCOUNT,
                new BigDecimal("100.0000"), new BigDecimal("20.00"),
                300L, new BigDecimal("40000.00"), 200L,
                5L * DAY, new BigDecimal("1000.00"),
                10L * DAY, new BigDecimal("2500.00"),
                true, true,
                new BigDecimal("300.00"), new BigDecimal("250.00"),
                approvals);
    }

    /**
     * 阶段完成的冻结观察引用（世界在到期子任务里按标识取用）。
     *
     * @return 阶段到引用表的映射
     */
    public static Map<Stage, Map<String, Evidence>> evidence() {
        Map<Stage, Map<String, Evidence>> evidence = new LinkedHashMap<>();
        evidence.put(Stage.PILOT, Map.of(EVIDENCE_PILOT,
                new Evidence(EVIDENCE_PILOT, "digital-lab/pilot-report", 3L * DAY)));
        evidence.put(Stage.VALIDATION, Map.of(EVIDENCE_VALIDATION,
                new Evidence(EVIDENCE_VALIDATION, "digital-lab/validation-report", 14L * DAY)));
        return Map.copyOf(evidence);
    }

    /**
     * 开局前已经作出的治理决议（EXP 事实，不实现董事会流程）。
     *
     * @return 决议引用到决议
     */
    public static Map<String, Mandate> mandates() {
        return Map.of(MANDATE_CAPITAL, new Mandate(MANDATE_CAPITAL,
                Mandate.Kind.CAPITAL_INJECTION, 0L));
    }

    /**
     * 公司子账的完整冻结配置。
     *
     * @return 可直接用于 {@link DigitalCompanyRun#open}
     */
    public static DigitalCompanyRun.Config runConfig() {
        return new DigitalCompanyRun.Config(COMPANY, SYMBOL, modelConfig(), evidence(), mandates());
    }
}
