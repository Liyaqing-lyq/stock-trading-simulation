package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 候选档案校验：所有问题都以 reasons 返回，不抛业务异常。
 *
 * <p>覆盖按“已声明宇宙”判定（含准备金现金，不看当前余额）；豁免绑定科目/类型/主体/理由。</p>
 */
public final class BankArchiveValidator {
    private BankArchiveValidator() {
    }

    /**

     * 候选档案。
     * @param config 候选档案
     * @param state 当前（或空）账簿，用于候选自算
     * @param accounts 现金账户，用于准备金指标
     * @return 原因列表（空表示可启用）

     */
    public static List<String> validate(BankConfig config, BankState state,
                                        Map<String, CashLedger.CashAccount> accounts) {
        return run(config, state, accounts, false);
    }

    /**
     * 首次激活/新 run 校验：声明齐全 + required 指标在当前账簿上可算或已声明可接受 N/A。
     *
     * <p>逐命令入口只用 {@link #validate}；即使银行进入困难状态（负分母等），
     * 计息、还本与付息仍必须可执行，闸门只拦新增信用。</p>
     *
     * @param config 候选档案
     * @param state 当前（或空）账簿
     * @param accounts 现金账户
     * @return 原因列表（空表示可激活）
     */
    public static List<String> validateActivation(BankConfig config, BankState state,
                                                  Map<String, CashLedger.CashAccount> accounts) {
        return run(config, state, accounts, true);
    }

    private static List<String> run(BankConfig config, BankState state,
                                    Map<String, CashLedger.CashAccount> accounts,
                                    boolean includeMetrics) {
        Set<String> reasons = new LinkedHashSet<>();
        try {
            check(config, state, accounts, reasons, includeMetrics);
        } catch (RuntimeException failure) {
            reasons.add("VALIDATION_ERROR:" + failure.getClass().getSimpleName() + ":"
                    + failure.getMessage());
        }
        return new ArrayList<>(reasons);
    }

    private static void check(BankConfig c, BankState state,
                              Map<String, CashLedger.CashAccount> accounts, Set<String> r,
                              boolean includeMetrics) {
        List<String> required = c.required();
        for (String name : BankRules.CORE_REQUIRED) {
            if (!required.contains(name)) {
                r.add("REQUIRED_BELOW_SCHEMA_FLOOR:" + name);
            }
        }
        for (String name : required) {
            if (!BankRules.CORE_REQUIRED.contains(name)) {
                r.add("UNKNOWN_METRIC:" + name);
            }
        }
        for (String name : BankRules.CORE_REQUIRED) {
            if (!c.implemented().contains(name)) {
                r.add("UNIMPLEMENTED:" + name);
            }
        }
        for (String name : BankRules.BINDING) {
            if (c.acceptedNa().containsKey(name)) {
                r.add("NA_SELF_EXEMPTION:" + name);
            }
        }
        spec(c.ccf(), "ccf", r);
        spec(c.commitmentWeight(), "commitment_weight", r);
        spec(c.runoffDemand(), "runoff_demand", r);

        if (c.interest() == null) {
            r.add("MISSING:interest");
        } else {
            String profile = c.interest().profile();
            if (!BankRules.INTEREST_NONE.equals(profile)
                    && !BankRules.INTEREST_ACT365.equals(profile)) {
                r.add(Reasons.INTEREST_PROFILE_INVALID + ":" + profile);
            } else if (BankRules.INTEREST_ACT365.equals(profile)
                    && (c.interest().rateExponent() != InterestAccrual.RATE_EXPONENT
                    || c.interest().dayBasisDays() != InterestAccrual.DAY_BASIS_DAYS)) {
                r.add(Reasons.INTEREST_PROFILE_INVALID + ":basis");
            }
            if (!c.interestEnabled()) {
                for (Map.Entry<String, BankState.Loan> entry : state.loans().entrySet()) {
                    for (BankState.Tranche tranche : entry.getValue().tranches()) {
                        if (tranche.terms() != null) {
                            r.add("INTEREST_TERMS_WITHOUT_PROFILE:" + entry.getKey());
                            break;
                        }
                    }
                }
                for (String key : state.depositAccounts().keySet()) {
                    r.add("INTEREST_TERMS_WITHOUT_PROFILE:" + key);
                }
                for (String key : state.commitmentRecords().keySet()) {
                    r.add("COMMITMENT_WITHOUT_PROFILE:" + key);
                }
                for (String bank : c.depositOffers().keySet()) {
                    r.add(Reasons.OFFER_WITHOUT_PROFILE + ":" + bank);
                }
            } else {
                for (String bank : c.chart().banks()) {
                    BankConfig.DepositOffer offer = c.depositOffer(bank);
                    if (offer == null) {
                        r.add(Reasons.OFFER_MISSING + ":" + bank);
                    } else if (!bank.equals(offer.bank())) {
                        r.add(Reasons.OFFER_UNKNOWN + ":" + bank);
                    }
                }
                for (String bank : c.depositOffers().keySet()) {
                    if (!c.chart().isBank(bank)) {
                        r.add(Reasons.OFFER_UNKNOWN + ":bank:" + bank);
                    }
                }
            }
            if (!BankRules.LEGACY_SEPARATE.equals(c.legacyCommitmentAttribution())
                    && !BankRules.LEGACY_DISABLED.equals(c.legacyCommitmentAttribution())) {
                r.add(Reasons.LEGACY_COMMITMENT_INVALID + ":"
                        + c.legacyCommitmentAttribution());
            }
        }

        List<String> weightAccounts = new ArrayList<>();
        weightAccounts.add(BankChart.RESERVE);
        weightAccounts.addAll(c.chart().assets());
        for (String asset : weightAccounts) {
            BankConfig.Spec weight = c.riskWeights().get(asset);
            if (weight == null) {
                r.add("MISSING_RW:" + asset);
                continue;
            }
            if (weight.value().signum() < 0 || weight.value().compareTo(BigDecimal.ONE) > 0) {
                r.add("OUT_OF_RANGE:" + asset);
            }
            if (weight.value().signum() == 0 && !weight.allowZero()) {
                r.add("ZERO_NOT_ALLOWED:" + asset);
            }
            if (weight.min() != null && weight.value().compareTo(weight.min()) < 0) {
                r.add("OUT_OF_RANGE:" + asset);
            }
        }
        for (String name : c.riskWeights().keySet()) {
            if (!weightAccounts.contains(name)) {
                r.add("UNKNOWN_RW_ACCOUNT:" + name);
            }
        }

        thresholds(c, r);

        if (c.hqla().isEmpty()) {
            r.add("MISSING:hqla");
        }
        for (String name : c.hqla()) {
            if (!BankChart.RESERVE.equals(name) && !c.chart().assets().contains(name)) {
                r.add("HQLA_NOT_AN_ASSET:" + name);
            }
        }

        for (String account : c.chart().universe()) {
            if (c.nonMaturing().contains(account)) {
                continue;
            }
            Map<String, BigDecimal> schedule = c.maturity().get(account);
            if (schedule == null) {
                r.add("COVERAGE_MISSING:maturity:" + account);
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> part : schedule.entrySet()) {
                if (!c.buckets().contains(part.getKey())) {
                    r.add("UNKNOWN_BUCKET:" + account + ":" + part.getKey());
                }
                sum = sum.add(part.getValue());
            }
            if (sum.compareTo(BigDecimal.ONE) != 0) {
                r.add("MATURITY_SCHEDULE_NOT_NORMALISED:" + account);
            }
        }

        for (String name : c.nonMaturing()) {
            String catalogKind = c.chart().nonMaturingCandidates().contains(name)
                    ? kindOf(c, name) : null;
            if (catalogKind == null) {
                r.add("NON_MATURING_NOT_ALLOWED:" + name);
                continue;
            }
            if (!c.chart().glAccounts().contains(name)) {
                r.add("NON_MATURING_UNKNOWN_ACCOUNT:" + name);
                continue;
            }
            if (!catalogKind.equals(kindOf(c, name))) {
                r.add("NON_MATURING_TYPE_INELIGIBLE:" + name);
            }
            String reason = c.nonMaturingReasons().get(name);
            String expected = BankRules.NON_MATURING_REASON.get(name);
            if (expected == null) {
                expected = reason;
            }
            if (reason == null || reason.isBlank()) {
                r.add("NON_MATURING_REASON_MISSING:" + name);
            } else if (!reason.equals(expected)) {
                r.add("NON_MATURING_REASON_MISMATCH:" + name);
            }
        }
        for (String name : c.nonMaturingReasons().keySet()) {
            if (!c.nonMaturing().contains(name)) {
                r.add("NON_MATURING_REASON_ORPHAN:" + name);
            }
        }

        for (String liability : c.chart().liabilities()) {
            if (!c.chart().isDepositAccount(liability) && !liability.equals("BORROW.FAC")) {
                continue;
            }
            String classification = c.lcrClass().get(liability);
            if (classification == null) {
                r.add("COVERAGE_MISSING:lcr:" + liability);
            } else if (!BankRules.LCR_CLASSES.contains(classification)) {
                r.add("CLASS_INVALID:" + liability + ":" + classification);
            }
        }

        for (String borrower : c.chart().borrowerKeys()) {
            if (!c.groupMap().containsKey(borrower)) {
                r.add("GROUP_COVERAGE_MISSING:" + borrower);
            }
        }
        Set<String> excluded = new TreeSet<>();
        for (Map.Entry<String, String> entry : c.groupMap().entrySet()) {
            String member = entry.getKey();
            String group = entry.getValue();
            if (!c.chart().hasBucket(member)) {
                r.add("UNKNOWN_GROUP_MEMBER:" + member);
            }
            if (group == null || group.isBlank()) {
                r.add("GROUP_NAME_INVALID:" + member);
            } else if (group.startsWith("@")) {
                if (!"@BACKGROUND_EXCLUDED".equals(group)) {
                    r.add("GROUP_NAME_INVALID:" + member + ":" + group);
                }
                excluded.add(member);
            }
        }
        for (String member : excluded) {
            String expected = BankRules.BACKGROUND_MEMBERS.get(member);
            if (expected == null) {
                r.add("BACKGROUND_EXCLUSION_NOT_ALLOWED:" + member);
                continue;
            }
            String reason = c.groupExclusionReasons().get(member);
            if (reason == null || reason.isBlank()) {
                r.add("BACKGROUND_EXCLUSION_REASON_MISSING:" + member);
            } else if (!reason.equals(expected)) {
                r.add("BACKGROUND_EXCLUSION_REASON_MISMATCH:" + member);
            }
        }
        for (String member : c.groupExclusionReasons().keySet()) {
            if (!excluded.contains(member)) {
                r.add("BACKGROUND_EXCLUSION_REASON_ORPHAN:" + member);
            }
        }

        if (c.facilityEnabled()) {
            for (String account : c.chart().liabilities()) {
                boolean covered = false;
                for (List<String> tier : c.facilityTerms().claimTiers()) {
                    if (BankRules.inTier(account, tier)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    r.add(Reasons.TIER_COVERAGE_MISSING + ":" + account);
                }
            }
            for (BankState.FacilityAccount facility : state.facilityAccounts().values()) {
                if (state.providerOpening(facility.provider()) == null) {
                    r.add(Reasons.PROVIDER_CAPITAL_MISSING + ":" + facility.provider());
                }
            }
        } else if (!state.facilityAccounts().isEmpty() || !state.collaterals().isEmpty()
                || !state.providerOpenings().isEmpty()) {
            r.add(Reasons.FACILITY_PROFILE_INVALID + ":state-without-profile");
        }

        if (!"EXP-1".equals(c.enforcementProfile())) {
            r.add("ENFORCEMENT_PROFILE_UNKNOWN:" + c.enforcementProfile());
        }
        for (String action : BankRules.ENFORCEMENT_ACTIONS) {
            String value = c.enforcement().get(action);
            if (value == null) {
                r.add("ENFORCEMENT_MISSING:" + action);
            } else if (!"blocking".equals(value) && !"advisory".equals(value)) {
                r.add("ENFORCEMENT_INVALID:" + action + ":" + value);
            } else if (!value.equals(BankRules.FROZEN_ENFORCEMENT.get(action))) {
                r.add("ENFORCEMENT_PROFILE_CHANGED:" + action);
            }
        }

        for (String bank : c.chart().banks()) {
            if (state.gl(bank, "PAIDIN").signum() == 0) {
                continue;
            }
            BigDecimal declaredAt1 = state.gl(bank, "AT1").negate();
            if (declaredAt1.compareTo(c.at1()) != 0) {
                r.add("AT1_UNDECLARED:" + bank + ":" + Money.text(declaredAt1)
                        + "!=" + Money.text(c.at1()));
            }
        }

        if (!r.isEmpty() || !includeMetrics) {
            return;
        }
        for (String bank : c.chart().banks()) {
            if (state.gl(bank, "PAIDIN").signum() == 0) {
                continue;
            }
            Map<String, BankMetrics.Metric> metrics = BankMetrics.all(state, accounts, c, bank);
            for (String name : required) {
                BankMetrics.Metric metric = metrics.get(name);
                if (metric == null) {
                    r.add("UNKNOWN_METRIC:" + name);
                    continue;
                }
                if ("COMPUTED".equals(metric.state())) {
                    continue;
                }
                if ("NA_NEGATIVE_DENOMINATOR".equals(metric.state())
                        || metric.state().startsWith("NA_ZERO_")) {
                    continue; // 困难/空账簿是运行结果，不是档案缺陷
                }
                if (c.acceptedNa().getOrDefault(name, List.of()).contains(metric.state())) {
                    continue;
                }
                r.add("NO_COMPUTABLE_RESULT:" + bank + ":" + name + ":" + metric.state());
            }
        }
    }

    private static void spec(BankConfig.Spec spec, String name, Set<String> reasons) {
        if (spec == null) {
            reasons.add("MISSING:" + name);
            return;
        }
        if (spec.value().signum() < 0) {
            reasons.add("OUT_OF_RANGE:" + name);
        }
        if (spec.value().signum() == 0 && !spec.allowZero()) {
            reasons.add("ZERO_NOT_ALLOWED:" + name);
        }
        if (spec.min() != null && spec.value().compareTo(spec.min()) < 0) {
            reasons.add("OUT_OF_RANGE:" + name);
        }
        if (spec.max() != null && spec.value().compareTo(spec.max()) > 0) {
            reasons.add("OUT_OF_RANGE:" + name);
        }
    }

    private static void thresholds(BankConfig c, Set<String> reasons) {
        Map<String, BigDecimal> th = c.thresholds();
        for (String name : List.of("cet1_min", "cet1_buffer", "t1_min", "tcr_min", "lev_min",
                "single_name", "group_name", "lcr_min", "maturity_gap_min",
                "impair_cover_min")) {
            if (!th.containsKey(name)) {
                reasons.add("MISSING:" + name);
            }
        }
        if (th.containsKey("cet1_min") && th.get("cet1_min").signum() <= 0) {
            reasons.add("OUT_OF_RANGE:cet1_min");
        }
        if (th.containsKey("single_name")
                && (th.get("single_name").signum() <= 0
                || th.get("single_name").compareTo(BigDecimal.TEN) > 0)) {
            reasons.add("OUT_OF_RANGE:single_name");
        }
        if (th.containsKey("group_name")
                && (th.get("group_name").signum() <= 0
                || th.get("group_name").compareTo(BigDecimal.TEN) > 0)) {
            reasons.add("OUT_OF_RANGE:group_name");
        }
        if (th.containsKey("impair_cover_min")
                && (th.get("impair_cover_min").signum() <= 0
                || th.get("impair_cover_min").compareTo(BigDecimal.ONE) > 0)) {
            reasons.add("OUT_OF_RANGE:impair_cover_min");
        }
        if (th.containsKey("maturity_gap_min")
                && (th.get("maturity_gap_min").compareTo(BigDecimal.ONE.negate()) < 0
                || th.get("maturity_gap_min").signum() >= 0)) {
            reasons.add("OUT_OF_RANGE:maturity_gap_min");
        }
    }

    private static String kindOf(BankConfig c, String account) {
        if (c.chart().assets().contains(account)) {
            return "ASSET";
        }
        if (c.chart().contra().contains(account)) {
            return "CONTRA";
        }
        if (c.chart().liabilities().contains(account)) {
            return "LIAB";
        }
        if (c.chart().equity().contains(account)) {
            return "EQUITY";
        }
        if (c.chart().income().contains(account)) {
            return "INCOME";
        }
        if (c.chart().expense().contains(account)) {
            return "EXPENSE";
        }
        return null;
    }
}
