package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 十项指标与触发闸门（公式与参考核算一致）。
 *
 * <p>零分母与负分母返回独立状态，不当作 0 或通过；绑定指标不得被 accepted_na 吞掉。</p>
 */
public final class BankMetrics {

    /** 单指标结果。
     * @param name name 参数
     * @param num num 参数
     * @param den den 参数
     * @param value value 参数
     * @param state state 参数
     */
public record Metric(String name, BigDecimal num, BigDecimal den, BigDecimal value,
                         String state) {
    }

    /** 闸门结果：状态、动作、全部指标。
     * @param status status 参数
     * @param actions actions 参数
     * @param metrics metrics 参数
     */
public record Gate(String status, List<String> actions, Map<String, Metric> metrics) {
    }

    private BankMetrics() {
    }

    /**

     * 银行状态。
     * @param state    银行状态
     * @param accounts 现金账户
     * @param config   档案
     * @param bank     银行
     * @return 全部 required 指标

     */
    public static Map<String, Metric> all(BankState state,
                                          Map<String, CashLedger.CashAccount> accounts,
                                          BankConfig config, String bank) {
        Map<String, Metric> metrics = new LinkedHashMap<>();
        Capital cap = capital(state, bank);
        BigDecimal rwa = rwa(state, accounts, config, bank);
        metrics.put("CET1R", ratio("CET1R", cap.cet1(), rwa, "NA_ZERO_RWA"));
        metrics.put("T1R", ratio("T1R", cap.t1(), rwa, "NA_ZERO_RWA"));
        metrics.put("TCR", ratio("TCR", cap.tcr(), rwa, "NA_ZERO_RWA"));
        metrics.put("LEV", ratio("LEV", cap.t1(), exposure(state, accounts, config, bank),
                "NA_ZERO_EXPOSURE"));
        metrics.put("SINGLE", ratio("SINGLE", single(state, bank), cap.t1(), "NA_ZERO_CAPITAL"));
        metrics.put("GROUP", ratio("GROUP", group(state, config, bank), cap.t1(),
                "NA_ZERO_CAPITAL"));
        metrics.put("OFFBS", offbs(state, config, bank));
        metrics.put("LCR_EXP", lcr(state, accounts, config, bank));
        metrics.put("MATURITY_GAP", maturity(state, accounts, config, bank));
        metrics.put("IMPAIR_COVER", impairCover(state, bank));
        return metrics;
    }

    /**
     * 触发闸门：按 required 逐项；非可算且不可接受即 BLOCKED，否则给出动作。
     *
     * @param state    银行状态
     * @param accounts 现金账户
     * @param config   档案
     * @param bank     银行
     * @return 闸门结果
     */
    public static Gate gate(BankState state, Map<String, CashLedger.CashAccount> accounts,
                            BankConfig config, String bank) {
        Map<String, Metric> metrics = all(state, accounts, config, bank);
        List<String> blocked = new ArrayList<>();
        List<String> required = config.required();
        for (String name : List.of("CET1R", "T1R", "TCR", "LEV", "SINGLE", "GROUP", "OFFBS",
                "LCR_EXP", "MATURITY_GAP", "IMPAIR_COVER")) {
            if (!required.contains(name)) {
                blocked.add("REQUIRED_BELOW_SCHEMA_FLOOR:" + name);
            }
        }
        if (!blocked.isEmpty()) {
            return new Gate("BLOCKED", blocked, metrics);
        }
        for (String name : required) {
            Metric metric = metrics.get(name);
            if (metric == null) {
                blocked.add("UNKNOWN_METRIC:" + name);
                continue;
            }
            if (!"COMPUTED".equals(metric.state())
                    && !config.acceptedNa().getOrDefault(name, List.of()).contains(metric.state())) {
                blocked.add(name + ":" + metric.state());
            }
        }
        if (!blocked.isEmpty()) {
            return new Gate("BLOCKED", blocked, metrics);
        }
        Map<String, BigDecimal> th = config.thresholds();
        List<String> actions = new ArrayList<>();
        if (below(metrics, "CET1R", th.get("cet1_min"))) {
            actions.add("RESOLUTION_PLAN");
        } else if (below(metrics, "CET1R",
                th.get("cet1_min").add(th.get("cet1_buffer")))) {
            actions.add("CAPITAL_PLAN+DIVIDEND_RESTRICTED");
        }
        if (below(metrics, "T1R", th.get("t1_min"))) {
            actions.add("T1_SHORTFALL");
        }
        if (below(metrics, "TCR", th.get("tcr_min"))) {
            actions.add("TCR_SHORTFALL");
        }
        if (below(metrics, "LEV", th.get("lev_min"))) {
            actions.add("LEVERAGE_LIMIT");
        }
        if (above(metrics, "SINGLE", th.get("single_name"))) {
            actions.add("SINGLE_NAME_LIMIT");
        }
        if (above(metrics, "GROUP", th.get("group_name"))) {
            actions.add("GROUP_LIMIT");
        }
        if (below(metrics, "LCR_EXP", th.get("lcr_min"))) {
            actions.add("LIQUIDITY_MEASURES");
        }
        if (below(metrics, "MATURITY_GAP", th.get("maturity_gap_min"))) {
            actions.add("MATURITY_MISMATCH");
        }
        if (below(metrics, "IMPAIR_COVER", th.get("impair_cover_min"))) {
            actions.add("IMPAIR_COVER_LOW");
        }
        return actions.isEmpty() ? new Gate("NORMAL", List.of(), metrics)
                : new Gate("BREACH", actions, metrics);
    }

    /** 指标有可比值且低于阈值；N/A（value=null）不参与动作判定，也不默认通过闸门。 */
    private static boolean below(Map<String, Metric> metrics, String name, BigDecimal limit) {
        Metric metric = metrics.get(name);
        return metric != null && metric.value() != null && limit != null
                && metric.value().compareTo(limit) < 0;
    }

    /** 指标有可比值且高于阈值。 */
    private static boolean above(Map<String, Metric> metrics, String name, BigDecimal limit) {
        Metric metric = metrics.get(name);
        return metric != null && metric.value() != null && limit != null
                && metric.value().compareTo(limit) > 0;
    }

    /**

     * 状态。
     * @param state     状态
     * @param accounts  现金
     * @param config    档案
     * @param bank      银行
     * @return 阻断动作（未知 enforcement 值按 blocking 处理）

     */
    public static List<String> blockingActions(BankState state,
                                               Map<String, CashLedger.CashAccount> accounts,
                                               BankConfig config, String bank) {
        Gate gate = gate(state, accounts, config, bank);
        if ("BLOCKED".equals(gate.status())) {
            return List.of("BLOCKED");
        }
        List<String> blocking = new ArrayList<>();
        for (String action : gate.actions()) {
            String value = config.enforcement().get(action);
            if (value == null || !"advisory".equals(value)) {
                blocking.add(action);
            }
        }
        return blocking;
    }

    /** 资本分层。
     * @param equityBase equityBase 参数
     * @param retained retained 参数
     * @param pnl pnl 参数
     * @param cet1 cet1 参数
     * @param at1 at1 参数
     * @param t1 t1 参数
     * @param t2 t2 参数
     * @param tcr tcr 参数
     */
public record Capital(BigDecimal equityBase, BigDecimal retained, BigDecimal pnl,
                          BigDecimal cet1, BigDecimal at1, BigDecimal t1, BigDecimal t2,
                          BigDecimal tcr) {
    }

    /**

     * 状态。
     * @param state 状态
     * @param bank  银行
     * @return 资本分层

     */
    public static Capital capital(BankState state, String bank) {
        BigDecimal equityBase = state.gl(bank, "PAIDIN").negate();
        BigDecimal retained = state.gl(bank, "RETAINED").negate();
        BigDecimal income = Money.zero();
        for (String name : state.chart().income()) {
            income = income.add(state.gl(bank, name));
        }
        BigDecimal expense = Money.zero();
        for (String name : state.chart().expense()) {
            expense = expense.add(state.gl(bank, name));
        }
        BigDecimal pnl = income.negate().subtract(expense);
        BigDecimal cet1 = Money.scale2(equityBase.add(retained).add(pnl));
        BigDecimal at1 = state.gl(bank, "AT1").negate();
        BigDecimal t2 = state.gl(bank, "SUBT2").negate();
        return new Capital(equityBase, retained, pnl, cet1, at1,
                Money.scale2(cet1.add(at1)), t2, Money.scale2(cet1.add(at1).add(t2)));
    }

    /**
     * 风险加权资产（准备金现金 × 权重 + 贷款本金 × 权重 + 承诺 × CCF × 权重）。
     *
     * @param state 银行状态
     * @param accounts 现金账户
     * @param config 冻结档案
     * @param bank 银行
     * @return 风险加权资产
     */
    public static BigDecimal rwa(BankState state, Map<String, CashLedger.CashAccount> accounts,
                                 BankConfig config, String bank) {
        BigDecimal total = Money.zero();
        BankConfig.Spec reserveWeight = config.riskWeights().get("RESERVE");
        if (reserveWeight == null) {
            throw new BankException("MISSING_RW:RESERVE");
        }
        total = total.add(reserve(state.chart(), accounts, bank).multiply(reserveWeight.value()));
        for (String asset : state.chart().assets()) {
            BigDecimal value = state.gl(bank, asset);
            BankConfig.Spec weight = config.riskWeights().get(asset);
            if (weight == null) {
                throw new BankException("MISSING_RW:" + asset);
            }
            total = total.add(value.multiply(weight.value()));
        }
        total = total.add(offBalance(state, bank).multiply(config.ccf().value())
                .multiply(config.commitmentWeight().value()));
        return Money.scale2(total);
    }

    /** 表外口径：旧 SET_COMMITMENT 汇总 + 明细承诺未提额度（两者分账，不双计）。 */
    private static BigDecimal offBalance(BankState state, String bank) {
        return Money.scale2(state.commitment(bank).add(state.undrawnCommitments(bank)));
    }

    /**
     * 杠杆敞口：准备金现金 + 净自有资产 + 承诺 × CCF。
     *
     * @param state 银行状态
     * @param accounts 现金账户
     * @param config 冻结档案
     * @param bank 银行
     * @return 杠杆敞口
     */
    public static BigDecimal exposure(BankState state,
                                      Map<String, CashLedger.CashAccount> accounts,
                                      BankConfig config, String bank) {
        BigDecimal total = reserve(state.chart(), accounts, bank);
        for (String asset : state.chart().assets()) {
            total = total.add(state.gl(bank, asset));
        }
        for (String contra : state.chart().contra()) {
            total = total.add(state.gl(bank, contra));
        }
        total = total.add(offBalance(state, bank).multiply(config.ccf().value()));
        return Money.scale2(total);
    }

    private static BigDecimal reserve(BankChart chart,
                                      Map<String, CashLedger.CashAccount> accounts,
                                      String bank) {
        CashLedger.CashAccount account = accounts.get(chart.reserveAccountOf(bank));
        return account == null ? Money.zero() : account.cash();
    }

    private static BigDecimal single(BankState state, String bank) {
        BigDecimal best = Money.zero();
        for (String bucket : state.chart().bucketValues()) {
            BigDecimal value = state.gl(bank, bucket);
            if (value.compareTo(best) > 0) {
                best = value;
            }
        }
        return Money.scale2(best);
    }

    private static BigDecimal group(BankState state, BankConfig config, String bank) {
        Map<String, BigDecimal> groups = new TreeMap<>();
        for (Map.Entry<String, String> entry : config.groupMap().entrySet()) {
            if (entry.getValue().startsWith("@")) {
                continue;
            }
            String bucket = state.chart().bucketFor(entry.getKey());
            if (bucket == null) {
                throw new BankException("UNKNOWN_LOAN_MAPPING:" + entry.getKey());
            }
            groups.merge(entry.getValue(), state.gl(bank, bucket), BigDecimal::add);
        }
        BigDecimal best = Money.zero();
        for (BigDecimal value : groups.values()) {
            if (value.compareTo(best) > 0) {
                best = value;
            }
        }
        return Money.scale2(best);
    }

    private static Metric offbs(BankState state, BankConfig config, String bank) {
        BigDecimal commitment = offBalance(state, bank);
        if (commitment.signum() == 0) {
            return new Metric("OFFBS", Money.zero(), null, null, "NA_NO_OFFBALANCE");
        }
        BigDecimal value = Money.scale2(commitment.multiply(config.ccf().value()));
        return new Metric("OFFBS", value, null, value, "COMPUTED");
    }

    private static Metric lcr(BankState state, Map<String, CashLedger.CashAccount> accounts,
                              BankConfig config, String bank) {
        BigDecimal hqla = Money.zero();
        for (String name : config.hqla()) {
            if ("RESERVE".equals(name)) {
                hqla = hqla.add(reserve(state.chart(), accounts, bank));
            } else {
                hqla = hqla.add(state.gl(bank, name));
            }
        }
        BigDecimal demand = Money.zero();
        BigDecimal term = Money.zero();
        for (Map.Entry<String, String> entry : config.lcrClass().entrySet()) {
            String name = entry.getKey();
            BigDecimal balance = name.equals("BORROW.FAC")
                    ? state.gl(bank, name).negate() : state.gl(bank, name).negate();
            if ("DEMAND".equals(entry.getValue())) {
                demand = demand.add(balance);
            } else if ("TERM30".equals(entry.getValue())) {
                term = term.add(balance);
            }
        }
        BigDecimal stress = Money.scale2(demand.multiply(config.runoffDemand().value())
                .add(term)
                .add(offBalance(state, bank).multiply(config.ccf().value())));
        return ratio("LCR_EXP", hqla, stress, "NA_NO_OUTFLOW");
    }

    private static Metric maturity(BankState state, Map<String, CashLedger.CashAccount> accounts,
                                   BankConfig config, String bank) {
        Map<String, BigDecimal> inflow = new TreeMap<>();
        Map<String, BigDecimal> outflow = new TreeMap<>();
        config.buckets().forEach(bucket -> {
            inflow.put(bucket, Money.zero());
            outflow.put(bucket, Money.zero());
        });
        for (Map.Entry<String, Map<String, BigDecimal>> entry : config.maturity().entrySet()) {
            String name = entry.getKey();
            BigDecimal value = "RESERVE".equals(name) ? reserve(state.chart(), accounts, bank)
                    : state.gl(bank, name);
            if (value.signum() == 0) {
                continue;
            }
            boolean isAsset = "RESERVE".equals(name) || state.chart().assets().contains(name);
            boolean isLiability = state.chart().liabilities().contains(name);
            for (Map.Entry<String, BigDecimal> schedule : entry.getValue().entrySet()) {
                if (!config.buckets().contains(schedule.getKey())) {
                    throw new BankException("UNKNOWN_BUCKET:" + name);
                }
                BigDecimal part = value.multiply(schedule.getValue());
                if (isAsset) {
                    inflow.merge(schedule.getKey(), part, BigDecimal::add);
                } else if (isLiability) {
                    outflow.merge(schedule.getKey(), part.negate(), BigDecimal::add);
                }
            }
        }
        BigDecimal liabilities = Money.zero();
        for (BigDecimal value : outflow.values()) {
            liabilities = liabilities.add(value);
        }
        if (liabilities.signum() == 0) {
            return new Metric("MATURITY_GAP", Money.zero(), null, null, "NA_NO_LIABILITIES");
        }
        BigDecimal near = inflow.get("1D").add(inflow.get("30D"))
                .subtract(outflow.get("1D")).subtract(outflow.get("30D"));
        return ratio("MATURITY_GAP", near, liabilities, "NA_NO_LIABILITIES");
    }

    private static Metric impairCover(BankState state, String bank) {
        BigDecimal gross = Money.zero();
        for (String loan : state.chart().loanAccounts()) {
            gross = gross.add(state.gl(bank, loan));
        }
        BigDecimal provision = Money.zero();
        for (String contra : state.chart().contra()) {
            provision = provision.add(state.gl(bank, contra).negate());
        }
        return ratio("IMPAIR_COVER", provision, gross, "NA_NO_LOANS");
    }

    /**

     * 指标名。
     * @param name     指标名
     * @param num      分子
     * @param den      分母
     * @param zeroState 零分母状态
     * @return 指标结果

     */
    public static Metric ratio(String name, BigDecimal num, BigDecimal den, String zeroState) {
        if (den.signum() < 0) {
            return new Metric(name, Money.scale2(num), Money.scale2(den), null,
                    "NA_NEGATIVE_DENOMINATOR");
        }
        if (den.signum() == 0) {
            return new Metric(name, Money.scale2(num), Money.scale2(den), null, zeroState);
        }
        BigDecimal value = num.divide(den, 6, RoundingMode.HALF_UP);
        return new Metric(name, Money.scale2(num), Money.scale2(den), value, "COMPUTED");
    }
}
