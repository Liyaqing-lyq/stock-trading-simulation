package edu.cufe.auction.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.EngineeringModel.Config;
import edu.cufe.auction.world.EngineeringModel.FundedReport;
import edu.cufe.auction.world.EngineeringModel.Report;
import edu.cufe.auction.world.EngineeringModel.ReportContext;
import edu.cufe.auction.world.EngineeringModel.State;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 组合树六字段 {@link ReportContext} 在资金重载接入后的口径守卫。
 *
 * <p>接入经营资金腿时只允许新增显式资金桶路径，不得把组合树已有的三侧派生项
 * （{@code externalAssets}/{@code externalLiabilities}/{@code externalEquity}）丢掉。
 * 这两个用例分别固定旧 {@code report} 与新 {@code fundedReport} 对三侧派生项的并入方式：
 * 资产侧加派生资产、负债侧加派生负债、权益侧已含派生权益，且平衡式同时成立。</p>
 *
 * <p>它们不是业务链测试；业务链由 {@code BizFundsSelfTest} 与三个模型测试覆盖。</p>
 */
class CompositionFundsContextTest {

    private static final long UNIT_MILLIS = 1_000L;

    /** 旧现金报表：三侧派生项必须全部并入，不能只并入资产侧。 */
    @Test
    void reportKeepsThreeSidedExternalContext() {
        State state = opened();
        ReportContext context = new ReportContext(money("0"), money("0"), money("0"),
                money("100"), money("40"), money("60"));

        Report report = EngineeringModel.report(state, accounts(), context);

        assertEquals(money("1000"), report.companyCash(), "公司现金不因派生项变化");
        assertEquals(money("1060"), report.equity(), "权益须含派生权益 60");
        assertTrue(report.balanced(), "资产 1100 = 负债 40 + 权益 1060，世界现金守恒");
    }

    /** 带存款的派生报表：存款、派生资产、派生负债与派生权益各自只计一次。 */
    @Test
    void fundedReportKeepsExternalContextAndCountsDepositOnce() {
        State state = opened();
        ReportContext context = new ReportContext(money("0"), money("0"), money("0"),
                money("100"), money("40"), money("360"));

        FundedReport funded = EngineeringModel.fundedReport(state, accounts(), context,
                money("300"));

        assertEquals(money("1000"), funded.cash(), "现金仍只算真实现金");
        assertEquals(money("300"), funded.deposits(), "存款单列一次");
        assertEquals(money("1400"), funded.assets(), "资产 = 现金 1000 + 存款 300 + 派生 100");
        assertEquals(money("40"), funded.liabilities(), "负债 = 派生负债 40");
        assertEquals(money("1360"), funded.equity(), "权益 = 初始 1000 + 派生权益 360");
        assertTrue(funded.balanced(), "资产 1400 = 负债 40 + 权益 1360，世界现金守恒");
    }

    /**
     * 非零且自身配平的三项派生项（123 = 45 + 78）必须各计一次，不得漏计或重复。
     *
     * <p>123/45/78 是独立外部项（证券成本、借款本金、已实现损益一类），与存款无关：
     * 无存款时资产 = 现金 1000 + 123、负债 = 45、权益 = 初始 1000 + 78。</p>
     */
    @Test
    void fundedReportCountsEachExternalItemExactlyOnce() {
        State state = opened();
        ReportContext context = new ReportContext(money("0"), money("0"), money("0"),
                money("123"), money("45"), money("78"));

        FundedReport funded = EngineeringModel.fundedReport(state, accounts(), context, money("0"));

        assertEquals(money("0"), funded.deposits(), "无存款时存款列为 0");
        assertEquals(money("1123"), funded.assets(), "资产 = 现金 1000 + 派生资产 123（各一次）");
        assertEquals(money("45"), funded.liabilities(), "负债 = 派生负债 45");
        assertEquals(money("1078"), funded.equity(), "权益 = 初始 1000 + 派生权益 78");
        assertTrue(funded.balanced(), "1123 = 45 + 1078，三项派生项自身配平且未重复");
    }

    /**
     * 存款与派生项同时存在时各自加一次：存款 300 走 funds，派生权益同步 +300，仍配平。
     *
     * <p>这是「来源互斥」的正向口径：123 不包含那 300 存款，所以两边各自只加一次。</p>
     */
    @Test
    void fundedReportAddsDepositAndExternalItemsSeparately() {
        State state = opened();
        ReportContext context = new ReportContext(money("0"), money("0"), money("0"),
                money("123"), money("45"), money("378"));

        FundedReport funded = EngineeringModel.fundedReport(state, accounts(), context,
                money("300"));

        assertEquals(money("300"), funded.deposits(), "存款单列 300");
        assertEquals(money("1423"), funded.assets(), "资产 = 1000 + 300 + 123");
        assertEquals(money("45"), funded.liabilities(), "负债仍只有派生负债 45");
        assertEquals(money("1378"), funded.equity(), "权益 = 1000 + 378（含存款对应 300）");
        assertTrue(funded.balanced(), "1423 = 45 + 1378，存款与派生项各计一次");
    }

    /**
     * 同一笔存款既经 funds 计入、又塞进 {@code externalAssets} 时必然破坏平衡。
     *
     * <p>本类型不去重，所以这条负例证明「重复传入」不会被静默吸收：把 300 存款同时写进
     * 派生资产 123 + 300 = 423，资产变成 1723，而负债加权益仍是 1423，报表如实不平衡。
     * 由此 World 只能择一来源，不能两边都传。</p>
     */
    @Test
    void depositPassedTwiceBreaksBalanceInsteadOfBeingSilentlyDeduped() {
        State state = opened();
        ReportContext doubledContext = new ReportContext(money("0"), money("0"), money("0"),
                money("423"), money("45"), money("378"));

        FundedReport doubled = EngineeringModel.fundedReport(state, accounts(), doubledContext,
                money("300"));

        assertEquals(money("1723"), doubled.assets(), "派生资产按传入值照加，不去重");
        assertTrue(!doubled.balanced(), "存款双计必须表现为不平衡，而不是被悄悄吸收");
    }

    /** 开庭状态：公司现金 1000，其余账户只是世界现金的一部分。 */
    private static State opened() {
        return EngineeringModel.open(config(), accounts());
    }

    private static Config config() {
        return new Config("co-owner", "co-cash", "cust-cash", "sup-cash", "ops-cash",
                UNIT_MILLIS, 3000, "composition-funds-intake");
    }

    private static Map<String, CashAccount> accounts() {
        Map<String, CashAccount> map = new LinkedHashMap<>();
        map.put("co-cash", new CashAccount("co-owner", money("1000"), "intake"));
        map.put("cust-cash", new CashAccount("cust-owner", money("500"), "intake"));
        map.put("sup-cash", new CashAccount("sup-owner", money("200"), "intake"));
        map.put("ops-cash", new CashAccount("ops-owner", money("50"), "intake"));
        return Map.copyOf(map);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, java.math.RoundingMode.UNNECESSARY);
    }
}
