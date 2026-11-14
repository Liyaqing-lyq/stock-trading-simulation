package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.bank.BankChart;
import edu.cufe.auction.world.bank.BankMetrics;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.Money;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 银行公司报表：只从真实 {@link BankState} 总账与 {@link BankChart} 科目分节产生。
 *
 * <p><b>资产</b> = 准备金现金 + 资产科目（贷款/房产/应收利息）+ 减值准备（负资产）；
 * <b>负债</b> = 存款 + 设施债务 + 应付利息；<b>已记权益</b> = 实收 + 留存 + AT1 + 处置专用权益。</p>
 *
 * <p><b>账面权益</b> = 已记权益 + 未结转<b>累计</b>损益。世界只在期末结转损益，平日的计息、减值、
 * 核销、回收都先落在收入/费用科目，因此 {@code totalEquity()} 必须包含
 * {@link #currentEarnings()}，否则合法计息帧就会出现 A−L−E = 未结转累计损益。资本监管指标
 * （CET1/T1/TCR）只是指标，<b>不并入</b>账面权益。</p>
 *
 * <p><b>{@code currentEarnings} 是累计口径</b>：它是总账收入/费用科目的未结转累计余额，
 * <b>不是单期损益</b>。单期流量必须由两期真实冻结快照的累计余额相减派生；在拿到两期冻结数据前，
 * 不得把它标成「当期」。</p>
 *
 * <p><b>准备金与减值准备不双算</b>：{@code RESERVE} 是唯一现金权威（CashLedger 账户），
 * 只在这里加一次；贷款准备在 contra 分节按负值合计一次。</p>
 *
 * <p><b>缺项不填 0</b>：世界路径未接的能力列在 {@link #unsupported()}，不伪装成“已支持且为 0”。</p>
 */
public record BankCompanyReport(String bank,
                                Map<String, BigDecimal> assets,
                                Map<String, BigDecimal> contra,
                                Map<String, BigDecimal> liabilities,
                                Map<String, BigDecimal> equity,
                                BigDecimal currentEarnings,
                                Map<String, BigDecimal> income,
                                Map<String, BigDecimal> expense,
                                Map<String, BigDecimal> metrics,
                                List<String> unsupported) {

    /**
     * 校验并冻结各分节。
     *
     * <p><b>迭代序必须是声明序</b>：{@code Map.copyOf} 的迭代序由 JVM 内部盐值决定，
     * 同一份真实账在不同 JVM 会得到不同的科目行序，进而让期末正文/摘要随进程变化。
     * 这里按调用方（{@link BankChart} 的科目清单）给出的<b>声明序</b>冻结为只读
     * {@link java.util.Collections#unmodifiableMap}，不排序、不失真，也不依赖哈希。</p>
     */
    public BankCompanyReport {
        assets = freeze(assets);
        contra = freeze(contra);
        liabilities = freeze(liabilities);
        equity = freeze(equity);
        income = freeze(income);
        expense = freeze(expense);
        metrics = freeze(metrics);
        unsupported = List.copyOf(unsupported);
        currentEarnings = Money.scale2(currentEarnings);
    }

    /** 按传入顺序冻结：保留声明序，拒绝后续改写。 */
    private static Map<String, BigDecimal> freeze(Map<String, BigDecimal> section) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(section));
    }

    /**
     * 从真实银行运行与现金账户构造报表。
     *
     * @param run      银行运行
     * @param accounts 唯一现金账
     * @param bank     银行 key
     * @return 公司口径报表
     */
    public static BankCompanyReport of(BankRun run, Map<String, CashLedger.CashAccount> accounts,
            String bank) {
        BankRun.Decoded decoded = run.decode(accounts);
        BankState state = decoded.state();
        BankChart chart = state.chart();
        if (!chart.isBank(bank)) {
            throw new IllegalArgumentException("未知银行 key：" + bank);
        }
        Map<String, BigDecimal> assets = new LinkedHashMap<>();
        assets.put(BankChart.RESERVE, money(state.gl(bank, BankChart.RESERVE)));
        for (String account : chart.assets()) {
            assets.put(account, money(state.gl(bank, account)));
        }
        Map<String, BigDecimal> contra = new LinkedHashMap<>();
        for (String account : chart.contra()) {
            contra.put(account, money(state.gl(bank, account)));
        }
        Map<String, BigDecimal> liabilities = new LinkedHashMap<>();
        for (String account : chart.liabilities()) {
            liabilities.put(account, money(state.gl(bank, account).negate()));
        }
        Map<String, BigDecimal> equity = new LinkedHashMap<>();
        for (String account : chart.equity()) {
            equity.put(account, money(state.gl(bank, account).negate()));
        }
        Map<String, BigDecimal> income = new LinkedHashMap<>();
        for (String account : chart.income()) {
            income.put(account, money(state.gl(bank, account).negate()));
        }
        Map<String, BigDecimal> expense = new LinkedHashMap<>();
        for (String account : chart.expense()) {
            expense.put(account, money(state.gl(bank, account)));
        }
        BankMetrics.Capital capital = BankMetrics.capital(state, bank);
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        metrics.put("cet1", capital.cet1());
        metrics.put("t1", capital.t1());
        metrics.put("tcr", capital.tcr());
        List<String> unsupported = List.of(
                "组合世界未暴露减值/核销/豁免/回收命令：报表对本状态仍按真实 GL 出数，但不得据此"
                        + "声称世界已支持这些动作",
                "世界路径未接：银行处置/清偿瀑布",
                "世界路径未接：托管客户受益权成交（custody 模块存在不等于能力启用）",
                "未覆盖：监管口径（流动性/NSFR/多币种/存款保险），本报表只是 EXP 实验账");
        return new BankCompanyReport(bank, assets, contra, liabilities, equity,
                pnl(income, expense), income, expense, metrics, unsupported);
    }

    /** @return 资产合计（含减值准备，准备为负） */
    public BigDecimal totalAssets() {
        BigDecimal sum = Money.zero();
        for (BigDecimal value : assets.values()) {
            sum = sum.add(value);
        }
        for (BigDecimal value : contra.values()) {
            sum = sum.add(value);
        }
        return Money.scale2(sum);
    }

    /** @return 负债合计（展示为正） */
    public BigDecimal totalLiabilities() {
        BigDecimal sum = Money.zero();
        for (BigDecimal value : liabilities.values()) {
            sum = sum.add(value);
        }
        return Money.scale2(sum);
    }

    /** @return 已记权益合计（不含未结转当期损益） */
    public BigDecimal bookedEquity() {
        BigDecimal sum = Money.zero();
        for (BigDecimal value : equity.values()) {
            sum = sum.add(value);
        }
        return Money.scale2(sum);
    }

    /** @return 账面权益 = 已记权益 + 未结转累计损益 */
    public BigDecimal totalEquity() {
        return Money.scale2(bookedEquity().add(currentEarnings));
    }

    /**
     * @return 未结转<b>累计</b>损益（收入 − 费用）；世界期末结转前它是账面权益的一部分，
     *         不是单期损益
     */
    public BigDecimal pnl() {
        return currentEarnings;
    }

    /** @return 资产 = 负债 + 账面权益（分位 0.01） */
    public boolean balanced() {
        return totalAssets().subtract(totalLiabilities()).subtract(totalEquity())
                .abs().compareTo(new BigDecimal("0.01")) <= 0;
    }

    private static BigDecimal pnl(Map<String, BigDecimal> income,
            Map<String, BigDecimal> expense) {
        BigDecimal sum = Money.zero();
        for (BigDecimal value : income.values()) {
            sum = sum.add(value);
        }
        for (BigDecimal value : expense.values()) {
            sum = sum.subtract(value);
        }
        return Money.scale2(sum);
    }

    private static BigDecimal money(BigDecimal value) {
        return Money.scale2(value);
    }
}
