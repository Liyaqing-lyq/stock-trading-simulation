package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 冻结的实验规则常量：豁免目录、背景排除、enforcement profile、指标名。
 */
public final class BankRules {
    /** 核心 required 十项（下限，不可裁剪）。 */
    public static final List<String> CORE_REQUIRED = List.of(
            "CET1R", "T1R", "TCR", "LEV", "SINGLE", "GROUP", "OFFBS",
            "LCR_EXP", "MATURITY_GAP", "IMPAIR_COVER");

    /** 绑定指标：不得被 accepted_na 自豁免。 */
    public static final List<String> BINDING = List.of("CET1R", "T1R", "TCR", "LEV");

    /** non_maturing 目录：科目 → 允许的类型（总账符号类别）。 */
    public static final Map<String, String> NON_MATURING_KIND = Map.ofEntries(
            Map.entry("PREMISES", "ASSET"),
            Map.entry("LLP.BG", "CONTRA"), Map.entry("LLP.C", "CONTRA"),
            Map.entry("LLP.D", "CONTRA"), Map.entry("LLP.AGG", "CONTRA"),
            Map.entry("LLP.E", "CONTRA"),
            Map.entry("PAIDIN", "EQUITY"), Map.entry("RETAINED", "EQUITY"),
            Map.entry("AT1", "EQUITY"),
            Map.entry("SUBT2", "LIAB"));

    /** 逐科目理由（冻结文本）。 */
    public static final Map<String, String> NON_MATURING_REASON = Map.ofEntries(
            Map.entry("PREMISES", "real_asset_no_contractual_maturity"),
            Map.entry("LLP.BG", "valuation_contra_follows_loan"),
            Map.entry("LLP.C", "valuation_contra_follows_loan"),
            Map.entry("LLP.D", "valuation_contra_follows_loan"),
            Map.entry("LLP.AGG", "valuation_contra_follows_loan"),
            Map.entry("LLP.E", "valuation_contra_follows_loan"),
            Map.entry("PAIDIN", "equity_no_maturity"),
            Map.entry("RETAINED", "equity_no_maturity"),
            Map.entry("AT1", "equity_no_maturity"),
            Map.entry("SUBT2", "subordinated_debt_without_modelled_maturity_gap"));

    /** 背景聚合排除只允许这些主体与冻结理由。 */
    public static final Map<String, String> BACKGROUND_MEMBERS =
            Map.of("AGG", "aggregate_background_book_no_single_name");

    /** 触发动作全集（enforcement 必须逐项覆盖）。 */
    public static final List<String> ENFORCEMENT_ACTIONS = List.of(
            "RESOLUTION_PLAN", "CAPITAL_PLAN+DIVIDEND_RESTRICTED", "T1_SHORTFALL",
            "TCR_SHORTFALL", "LEVERAGE_LIMIT", "SINGLE_NAME_LIMIT", "GROUP_LIMIT",
            "LIQUIDITY_MEASURES", "MATURITY_MISMATCH", "IMPAIR_COVER_LOW");

    /** EXP-1 冻结 profile。 */
    public static final Map<String, String> FROZEN_ENFORCEMENT = Map.ofEntries(
            Map.entry("RESOLUTION_PLAN", "blocking"),
            Map.entry("CAPITAL_PLAN+DIVIDEND_RESTRICTED", "blocking"),
            Map.entry("T1_SHORTFALL", "blocking"),
            Map.entry("TCR_SHORTFALL", "blocking"),
            Map.entry("LEVERAGE_LIMIT", "blocking"),
            Map.entry("SINGLE_NAME_LIMIT", "blocking"),
            Map.entry("GROUP_LIMIT", "blocking"),
            Map.entry("LIQUIDITY_MEASURES", "blocking"),
            Map.entry("MATURITY_MISMATCH", "advisory"),
            Map.entry("IMPAIR_COVER_LOW", "advisory"));

    /** LCR 分类允许值。 */
    public static final List<String> LCR_CLASSES = List.of("DEMAND", "TERM30", "NON_RUNOFF");

    /** 冻结的救助提供者主体。 */
    public static final String PROVIDER = "FACILITY";

    /** 利息 profile：旧档语义（不计息，头寸无条款）。 */
    public static final String INTEREST_NONE = "NONE";

    /** 利息 profile：ACT/365 毫秒时轴 + 1e6 定标费率（新开立必须完整条款）。 */
    public static final String INTEREST_ACT365 = "ACT365_MS_U1E6";

    /** 银行产生的存款按 offer 计息。 */
    public static final String CREDIT_OFFER = "OFFER";

    /** 银行产生的存款明确非应计。 */
    public static final String CREDIT_NON_ACCRUAL = "NON_ACCRUAL";

    /** 旧汇总与明细承诺各自独立声明来源。 */
    public static final String LEGACY_SEPARATE = "SEPARATE_DECLARED";

    /** 旧汇总承诺来源关闭，只认明细承诺。 */
    public static final String LEGACY_DISABLED = "DISABLED";

    /** 旧档案：无 B3/B4 设施条款（保留旧记忆账语义）。 */
    public static final String FACILITY_NONE = "NONE";

    /** 启用 B3/B4 设施条款的实验档案。 */
    public static final String FACILITY_EXP = "EXP-B3B4";

    /** 冻结的独立处置权主体（不得是任何银行）。 */
    public static final String RESOLUTION_AUTHORITY = "RESOLUTION-AUTH";

    /** 押品类别目录。 */
    public static final List<String> COLLATERAL_KINDS = List.of("PREMISES", "LOAN");

    /** 押品政策：必须有足额押品。 */
    public static final String COLLATERAL_SECURED = "SECURED";

    /** 押品政策：明确无担保（仍受额度与授权约束）。 */
    public static final String COLLATERAL_UNSECURED = "UNSECURED";

    /** 押品状态：在库未占用。 */
    public static final String COLLATERAL_FREE = "FREE";

    /** 押品状态：已被唯一设施占用。 */
    public static final String COLLATERAL_PLEDGED = "PLEDGED";

    /** 押品状态：已处置。 */
    public static final String COLLATERAL_DISPOSED = "DISPOSED";

    /** 设施状态：已授权，可提款/未结清。 */
    public static final String FACILITY_OPEN = "OPEN";

    /** 设施状态：本金与利息全部清偿。 */
    public static final String FACILITY_CLOSED_REPAID = "CLOSED_REPAID";

    /** 设施状态：备查豁免后结清。 */
    public static final String FACILITY_CLOSED_WITH_LOSS = "CLOSED_WITH_LOSS";

    /** 设施状态：经独立处置权解除。 */
    public static final String FACILITY_CLOSED_DISCHARGED = "CLOSED_DISCHARGED";

    /** 设施状态：已违约未解决。 */
    public static final String FACILITY_DEFAULT_STATUS = "UNRESOLVED_DEFAULT";

    /** 设施状态：已到期且本/息未清。 */
    public static final String FACILITY_PAST_DUE = "PAST_DUE";

    /** 押品状态：底层资产已被还清/核销，担保自然耗尽。 */
    public static final String COLLATERAL_EXHAUSTED = "EXHAUSTED";

    /** 买方许可对象：整笔贷款。 */
    public static final String SUBJECT_LOAN = "LOAN";

    /** 买方许可对象：单项押品。 */
    public static final String SUBJECT_COLLATERAL = "COLLATERAL";

    /** 买方许可：未消耗。 */
    public static final String PURCHASE_OPEN = "OPEN";

    /** 买方许可：已消耗（一次用途）。 */
    public static final String PURCHASE_CONSUMED = "CONSUMED";

    /** 解除许可：未使用。 */
    public static final String CONSENT_OPEN = "OPEN";

    /** 解除许可：已使用（一次用途）。 */
    public static final String CONSENT_USED = "USED";

    /** 处置流程：未开启。 */
    public static final String RESOLUTION_NONE = "NONE";

    /** 处置流程：独立处置权已开启。 */
    public static final String RESOLUTION_ACTIVE = "ACTIVE";

    /** 债权转让：有效。 */
    public static final String CLAIM_ACTIVE = "ACTIVE";

    /** 债权转让：已全额结算/豁免。 */
    public static final String CLAIM_SETTLED = "SETTLED";

    /** 金额表示上限。 */
    public static final BigDecimal LIMIT = Money.LIMIT;

    private BankRules() {
    }

    /**
     * 目录声明的科目类型。
     *
     * @param name 科目
     * @return 类型（未知返回 null）
     */
    public static String catalogKind(String name) {
        return NON_MATURING_KIND.get(name);
    }

    /**
     * 科目是否落在某个清算阶梯级的通配模式内（尾部 {@code *} 为前缀匹配）。
     *
     * @param account  科目
     * @param patterns 该级模式
     * @return 匹配返回 true
     */
    public static boolean inTier(String account, java.util.List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.endsWith("*")) {
                if (account.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (pattern.equals(account)) {
                return true;
            }
        }
        return false;
    }
}
