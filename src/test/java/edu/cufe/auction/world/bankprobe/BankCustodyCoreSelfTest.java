package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.SecuritiesLedger;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.bank.BankContext;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.CashFreeze;
import edu.cufe.auction.world.bank.CustodyBook;
import edu.cufe.auction.world.bank.Fund;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B7b 纯核自测：托管受益权成交的三线一致、同池/跨池/跨行资金腿、冻结与释放、
 * 池桶禁止、所有权错配、整批回滚。
 *
 * <p>只调用公开 API（{@code WorldExchange.submit} 的托管重载 + {@code CustodyBook}），
 * 不经 World 任务；World 级用例在 {@code BankCustodyWorldSelfTest}。</p>
 */
public final class BankCustodyCoreSelfTest {

    private static final long T0 = BankCustodyFixture.T0;
    private static final String CST = BankCustodyFixture.CST;
    private static final String CST2 = BankCustodyFixture.CST2;
    private static final String CST3 = BankCustodyFixture.CST3;
    private static int pass;
    private static int fail;

    private BankCustodyCoreSelfTest() {
    }

    /**
     * 入口。
     *
     * @param args 忽略
     */
    public static void main(String[] args) {
        try {
            samePoolBookOnly();
            crossPoolSameBank();
            crossPoolCrossBank();
            crossBankReserveInsufficientRollsBackWholePlan();
            custodyToCash();
            custodyToDeposit();
            freezeReleasedByOwnFillAndBlocksNextOrder();
            poolBucketCannotFundOrder();
            foreignCustodyShareRejected();
            custodianCannotBeClient();
            secondMakerFailureRollsBackEverything();
            triStateStaysConsistentAfterManyTrades();
        } catch (RuntimeException failure) {
            failure.printStackTrace();
            fail++;
        }
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    /** 同池客户之间：只改明细，池存款与准备金都不动（不虚构外部资金）。 */
    private static void samePoolBookOnly() {
        Scenario s = Scenario.open("1200.00");
        s.sell("firm", "firm-cash", "firm", 100L, "10.00", "t-s-firm");
        BigDecimal poolBefore = s.pool("north", BankCustodyFixture.CST);
        BigDecimal reserveBefore = s.cash("rsv-north");
        WorldExchange.FundOutcome buy = s.buy("acme", "acme-cash", "acme", 30L, "10.00",
                "t-b-acme");
        check("同池成交被接受", buy.accepted(), buy.reason());
        check("同池成交一笔", buy.tradeIds().size() == 1, "trades=" + buy.tradeIds());
        check("买方明细 600→300", eq(s.detail(buy, BankCustodyFixture.CST, "acme"), "300.00"),
                s.detail(buy, BankCustodyFixture.CST, "acme"));
        check("卖方明细 400→700", eq(s.detail(buy, BankCustodyFixture.CST, "firm"), "700.00"),
                s.detail(buy, BankCustodyFixture.CST, "firm"));
        check("池存款不变", eq(s.poolOf(buy, "north", BankCustodyFixture.CST), poolBefore), poolBefore);
        check("准备金不变", eq(s.cashOf(buy, "rsv-north"), reserveBefore), reserveBefore);
        check("同池三线仍一致", s.validate(buy).isEmpty(), s.validate(buy).toString());
    }

    /** 跨池同行（cst→cst3，都在 north）：两条池存款负债对开，不需要准备金。 */
    private static void crossPoolSameBank() {
        Scenario s = Scenario.open("1200.00");
        s.sell("dave", "dave-cash", "dave", 100L, "10.00", "t-s-dave");
        BigDecimal reserveBefore = s.cash("rsv-north");
        WorldExchange.FundOutcome buy = s.buy("acme", "acme-cash", "acme", 30L, "10.00",
                "t-b-acme");
        check("跨池同行成交被接受", buy.accepted(), buy.reason());
        check("cst 池 1000→700", eq(s.poolOf(buy, "north", BankCustodyFixture.CST), "700.00"),
                s.poolOf(buy, "north", BankCustodyFixture.CST));
        check("cst3 池 300→600", eq(s.poolOf(buy, "north", BankCustodyFixture.CST3), "600.00"),
                s.poolOf(buy, "north", BankCustodyFixture.CST3));
        check("同行不动准备金", eq(s.cashOf(buy, "rsv-north"), reserveBefore), reserveBefore);
        check("跨池同行三线一致", s.validate(buy).isEmpty(), s.validate(buy).toString());
    }

    /** 跨池跨行（cst@north→cst2@south）：真实准备金移动。 */
    private static void crossPoolCrossBank() {
        Scenario s = Scenario.open("1200.00");
        s.sell("co", "co-cash", "co", 100L, "10.00", "t-s-co");
        WorldExchange.FundOutcome buy = s.buy("acme", "acme-cash", "acme", 40L, "10.00",
                "t-b-acme");
        check("跨行成交被接受", buy.accepted(), buy.reason());
        check("north 池 1000→600", eq(s.poolOf(buy, "north", BankCustodyFixture.CST), "600.00"),
                s.poolOf(buy, "north", BankCustodyFixture.CST));
        check("south 池 500→900", eq(s.poolOf(buy, "south", BankCustodyFixture.CST2), "900.00"),
                s.poolOf(buy, "south", BankCustodyFixture.CST2));
        check("north 准备金 1200→800", eq(s.cashOf(buy, "rsv-north"), "800.00"),
                s.cashOf(buy, "rsv-north"));
        check("south 准备金 0→400", eq(s.cashOf(buy, "rsv-south"), "400.00"),
                s.cashOf(buy, "rsv-south"));
        check("客户明细对称", eq(s.detail(buy, BankCustodyFixture.CST2, "co"), "900.00"),
                s.detail(buy, BankCustodyFixture.CST2, "co"));
        check("跨行三线一致", s.validate(buy).isEmpty(), s.validate(buy).toString());
    }

    /** 第二腿准备金不足：整个 submit 拒绝，maker 剩余与簿都不变（不跳 FIFO、不暗补）。 */
    private static void crossBankReserveInsufficientRollsBackWholePlan() {
        Scenario s = Scenario.open("0.00");
        s.sell("co", "co-cash", "co", 100L, "10.00", "t-s-co");
        int ordersBefore = s.exchange.orders().size();
        WorldExchange.State exchangeBefore = s.exchange;
        Map<String, CashLedger.CashAccount> accountsBefore = new LinkedHashMap<>(s.accounts);
        WorldExchange.FundOutcome buy = s.buy("acme", "acme-cash", "acme", 40L, "10.00",
                "t-b-acme");
        check("准备金不足被拒", !buy.accepted(), buy.reason());
        check("拒绝码是 RESERVE_INSUFFICIENT",
                buy.reason().startsWith(WorldExchange.REASON_RESERVE_INSUFFICIENT), buy.reason());
        check("拒绝后订单数不变", buy.exchange().orders().size() == ordersBefore,
                "orders=" + buy.exchange().orders().size());
        check("拒绝后交易状态逐字不变", buy.exchange().equals(exchangeBefore), "state 变了");
        check("拒绝后现金逐字不变", buy.accounts().equals(accountsBefore), "accounts 变了");
        check("拒绝后托管账未动（无候选账、原账不变）", buy.custody() == null
                && s.book.detailOf(CST, "acme").compareTo(new BigDecimal("600.00")) == 0,
                "book 变了");
    }

    /** 托管→现金：池存款出账、客户明细下降、收款人现金增加。 */
    private static void custodyToCash() {
        Scenario s = Scenario.open("1200.00");
        s = s.with(s.sellCash("firm", "firm-cash", 100L, "10.00", "s-firm"));
        WorldExchange.FundOutcome buy = s.buy("acme", "acme-cash", "acme", 20L, "10.00",
                "t-b-acme");
        check("托管→现金被接受", buy.accepted(), buy.reason());
        check("池 1000→800", eq(s.poolOf(buy, "north", BankCustodyFixture.CST), "800.00"),
                s.poolOf(buy, "north", BankCustodyFixture.CST));
        check("north 准备金 1200→1000", eq(s.cashOf(buy, "rsv-north"), "1000.00"),
                s.cashOf(buy, "rsv-north"));
        check("卖方现金 400→600", eq(s.cashOf(buy, "firm-cash"), "600.00"),
                s.cashOf(buy, "firm-cash"));
        check("买方明细 600→400", eq(s.detail(buy, BankCustodyFixture.CST, "acme"), "400.00"),
                s.detail(buy, BankCustodyFixture.CST, "acme"));
        check("托管→现金三线一致", s.validate(buy).isEmpty(), s.validate(buy).toString());
    }

    /** 现金→托管：买方付现金进池，卖方明细上升。 */
    private static void custodyToDeposit() {
        Scenario s = Scenario.open("1200.00");
        s.sell("acme", "acme-cash", "acme", 100L, "10.00", "t-s-acme");
        WorldExchange.FundOutcome buy = s.buyCash("firm", "firm-cash", 20L, "10.00", "t-b-firm");
        check("现金→托管被接受", buy.accepted(), buy.reason());
        check("池 1000→1200", eq(s.poolOf(buy, "north", BankCustodyFixture.CST), "1200.00"),
                s.poolOf(buy, "north", BankCustodyFixture.CST));
        check("north 准备金 1200→1400", eq(s.cashOf(buy, "rsv-north"), "1400.00"),
                s.cashOf(buy, "rsv-north"));
        check("买方现金 400→200", eq(s.cashOf(buy, "firm-cash"), "200.00"),
                s.cashOf(buy, "firm-cash"));
        check("卖方明细 600→800", eq(s.detail(buy, BankCustodyFixture.CST, "acme"), "800.00"),
                s.detail(buy, BankCustodyFixture.CST, "acme"));
        check("现金→托管三线一致", s.validate(buy).isEmpty(), s.validate(buy).toString());
    }

    /** 自己的限价冻结在成交时释放；剩余冻结不可被第二笔订单花掉。 */
    private static void freezeReleasedByOwnFillAndBlocksNextOrder() {
        Scenario s = Scenario.open("1200.00");
        s = s.with(s.sell("firm", "firm-cash", "firm", 10L, "10.00", "s-firm"));
        // acme 用 50 股 @10（冻 500）去买：只成交 10 股，剩 40 股冻结 400。
        WorldExchange.FundOutcome first = s.buy("acme", "acme-cash", "acme", 50L, "10.00",
                "b-acme-1");
        check("首单部分成交", first.accepted() && first.tradeIds().size() == 1, first.reason());
        Fund bucket = Fund.parse(BankCustodyFixture.custodyToken(BankCustodyFixture.CST, "acme"));
        BigDecimal frozen = WorldExchange.frozenIn(first.exchange(), bucket);
        check("剩余 40 股冻结 400", eq(frozen, "400.00"), frozen.toPlainString());
        check("成交后明细 600→500", eq(s.detail(first, BankCustodyFixture.CST, "acme"), "500.00"),
                s.detail(first, BankCustodyFixture.CST, "acme"));
        check("可用 = 500 − 400 = 100", eq(WorldExchange.freeIn(first.exchange(), first.accounts(),
                first.bank(), bucket, first.custody()), "100.00"),
                WorldExchange.freeIn(first.exchange(), first.accounts(), first.bank(), bucket,
                        first.custody()).toPlainString());
        // 第二笔 20 股 @10 需要 200，可用只有 100 → 拒绝。
        WorldExchange.FundOutcome second = s.buy("acme", "acme-cash", "acme", 20L, "10.00",
                "b-acme-2");
        check("冻结中的钱不能给第二笔订单", !second.accepted(), second.reason());
        check("第二笔拒绝码 INSUFFICIENT_CASH",
                WorldExchange.REASON_INSUFFICIENT_CASH.equals(second.reason()), second.reason());
        // 撤单释放全部冻结。
        WorldExchange.Outcome cancel = WorldExchange.cancel(second.exchange(), second.accounts(),
                second.securities(), "acme-owner", first.orderId(), T0 + 1000L);
        check("撤单被接受", cancel.accepted(), cancel.reason());
        check("撤单后冻结归零",
                WorldExchange.frozenIn(cancel.exchange(), bucket).signum() == 0,
                WorldExchange.frozenIn(cancel.exchange(), bucket).toPlainString());
    }

    /** 池存款桶不能作为订单资金桶（托管人有账户 ≠ 可支配客户的钱）。 */
    private static void poolBucketCannotFundOrder() {
        Scenario s = Scenario.open("1200.00");
        s.sell("firm", "firm-cash", "firm", 100L, "10.00", "t-s-firm");
        String poolToken = BankCustodyFixture.token("north", BankCustodyFixture.CST);
        WorldExchange.FundOutcome buy = s.buyWithToken("acme", "acme-cash", "acme", 10L, "10.00",
                "t-b-pool", poolToken);
        check("池存款桶下单被拒", !buy.accepted(), buy.reason());
        check("拒绝码 POOL_BUCKET_NOT_ORDER_FUNDABLE",
                buy.reason().startsWith(WorldExchange.REASON_POOL_BUCKET_NOT_ORDER_FUNDABLE),
                buy.reason());
    }

    /** 用别人的托管份额下单：拒绝，且不动任何账。 */
    private static void foreignCustodyShareRejected() {
        Scenario s = Scenario.open("1200.00");
        s.sell("firm", "firm-cash", "firm", 100L, "10.00", "t-s-firm");
        WorldExchange.FundOutcome buy = s.buyWithToken("acme", "acme-cash", "acme", 10L, "10.00",
                "t-b-foreign", BankCustodyFixture.custodyToken(BankCustodyFixture.CST, "firm"));
        check("跨客户托管份额被拒", !buy.accepted(), buy.reason());
        check("拒绝码 FUND_OWNER_MISMATCH",
                buy.reason().startsWith(WorldExchange.REASON_FUND_OWNER_MISMATCH), buy.reason());
    }

    /** 托管人不能用池分享自己的受益权（客户 ≠ 托管人）。 */
    private static void custodianCannotBeClient() {
        Scenario s = Scenario.open("1200.00");
        s.sell("firm", "firm-cash", "firm", 100L, "10.00", "t-s-firm");
        WorldExchange.FundOutcome buy = s.buyWithToken("firm", "firm-cash", "firm", 10L, "10.00",
                "t-b-self", BankCustodyFixture.custodyToken(BankCustodyFixture.CST, "north"));
        check("未登记客户被拒", !buy.accepted(), buy.reason());
        check("拒绝码 UNKNOWN_ACCOUNT",
                buy.reason().startsWith(WorldExchange.REASON_UNKNOWN_ACCOUNT), buy.reason());
    }

    /** 两 maker：第二笔资金腿失败 → 第一笔也不入账（整批回滚、不跳 FIFO）。 */
    private static void secondMakerFailureRollsBackEverything() {
        Scenario s = Scenario.open("0.00");
        // maker1：firm 在 cst（同池，book-only）；maker2：dave 在 cst3（同行，仍是 book-only）。
        // 为制造第二腿失败，改用 cross-bank：maker2 = co@cst2（south 准备金 0）。
        s.sell("firm", "firm-cash", "firm", 30L, "10.00", "t-s-firm");
        s.sell("co", "co-cash", "co", 30L, "10.00", "t-s-co");
        int ordersBefore = s.exchange.orders().size();
        WorldExchange.State before = s.exchange;
        WorldExchange.FundOutcome buy = s.buy("acme", "acme-cash", "acme", 60L, "10.00",
                "t-b-acme");
        check("跨行第二腿失败整单拒绝", !buy.accepted(), buy.reason());
        check("拒绝码 RESERVE_INSUFFICIENT",
                buy.reason().startsWith(WorldExchange.REASON_RESERVE_INSUFFICIENT), buy.reason());
        check("无任何成交", buy.tradeIds().isEmpty(), "trades=" + buy.tradeIds());
        check("订单表未新增", buy.exchange().orders().size() == ordersBefore,
                "orders=" + buy.exchange().orders().size());
        check("状态逐字不变（不跳 FIFO）", buy.exchange().equals(before), "state 变了");
        check("明细未动（第一笔也没落账）",
                s.book.detailOf(BankCustodyFixture.CST, "firm").compareTo(new BigDecimal("400.00"))
                        == 0,
                s.book.detailOf(BankCustodyFixture.CST, "firm").toPlainString());
    }

    /** 多笔成交后三线仍一致，托管净值为零，池计息口径没被静默降级。 */
    private static void triStateStaysConsistentAfterManyTrades() {
        Scenario s = Scenario.open("1200.00");
        s.sell("firm", "firm-cash", "firm", 100L, "10.00", "t-s-firm");
        s.sell("dave", "dave-cash", "dave", 50L, "10.00", "t-s-dave");
        s.sell("co", "co-cash", "co", 50L, "10.00", "t-s-co");
        WorldExchange.FundOutcome one = s.buy("acme", "acme-cash", "acme", 10L, "10.00",
                "t-b-1");
        check("第一笔成交", one.accepted(), one.reason());
        Scenario next = s.with(one);
        WorldExchange.FundOutcome two = next.buy("acme", "acme-cash", "acme", 5L, "10.00",
                "t-b-2");
        check("第二笔成交", two.accepted(), two.reason());
        Scenario third = next.with(two);
        WorldExchange.FundOutcome three = third.buy("acme", "acme-cash", "acme", 5L, "10.00",
                "t-b-3");
        check("第三笔成交", three.accepted(), three.reason());
        Scenario done = third.with(three);
        check("三笔后组合校验通过", done.validate().isEmpty(), done.validate().toString());
        check("cst 池净资产为 0",
                done.book.navOf(done.bank, BankCustodyFixture.CST).signum() == 0,
                done.book.navOf(done.bank, BankCustodyFixture.CST).toPlainString());
        check("cst3 池净资产为 0",
                done.book.navOf(done.bank, BankCustodyFixture.CST3).signum() == 0,
                done.book.navOf(done.bank, BankCustodyFixture.CST3).toPlainString());
        check("cst2 池净资产为 0",
                done.book.navOf(done.bank, BankCustodyFixture.CST2).signum() == 0,
                done.book.navOf(done.bank, BankCustodyFixture.CST2).toPlainString());
        BankState.DepositAccount terms = done.bank.depositAccount("north", BankCustodyFixture.CST);
        check("池仍是非应计", terms != null && terms.nonAccrual(), String.valueOf(terms));
        check("每池资产等于明细合计",
                eq(done.book.poolAssetOf(done.bank, BankCustodyFixture.CST),
                        done.book.poolLiabilityOf(BankCustodyFixture.CST)),
                done.book.poolAssetOf(done.bank, BankCustodyFixture.CST).toPlainString() + " vs "
                        + done.book.poolLiabilityOf(BankCustodyFixture.CST).toPlainString());
    }

    // ---------------------------------------------------------------- 夹具

    /** 一份可变场景：账户、银行、托管账、订单/证券状态。 */
    private static final class Scenario {
        Map<String, CashLedger.CashAccount> accounts;
        BankState bank;
        CustodyBook book;
        WorldExchange.State exchange;
        SecuritiesLedger.State securities;

        private Scenario(Map<String, CashLedger.CashAccount> accounts, BankState bank,
                         CustodyBook book, WorldExchange.State exchange,
                         SecuritiesLedger.State securities) {
            this.accounts = accounts;
            this.bank = bank;
            this.book = book;
            this.exchange = exchange;
            this.securities = securities;
        }

        WorldExchange.FundContext funds() {
            return new WorldExchange.FundContext(bank, BankCustodyFixture.config(),
                    new BankContext(BankCustodyFixture.RUN_ID,
                            BankCustodyFixture.config().fingerprint(), 1L, T0));
        }

        static Scenario open(String northReserve) {
            BankCustodyFixture.Core core = BankCustodyFixture.core(northReserve, "1200.00", "0.00");
            return new Scenario(new LinkedHashMap<>(core.accounts()), core.bank(), core.book(),
                    WorldExchange.opening(),
                    SecuritiesLedger.opening(BankCustodyFixture.registry()));
        }

        Scenario with(WorldExchange.FundOutcome outcome) {
            if (!outcome.accepted()) {
                throw new IllegalStateException("夹具步骤被拒：" + outcome.reason());
            }
            accounts = new LinkedHashMap<>(outcome.accounts());
            bank = outcome.bank();
            book = outcome.custody();
            exchange = outcome.exchange();
            securities = outcome.securities();
            return this;
        }

        /** 挂一张托管卖单（收款桶 = 该客户的托管桶）。 */
        WorldExchange.FundOutcome sell(String holder, String accountId, String client, long qty,
                                       String price, String taskId) {
            WorldExchange.FundOutcome outcome = submit(holder, accountId, Side.SELL, qty, price,
                    taskId, BankCustodyFixture.custodyToken(custOf(client), client));
            check("卖单挂簿 " + taskId, outcome.accepted(), outcome.reason());
            if (outcome.accepted()) {
                with(outcome);
            }
            return outcome;
        }

        /** 卖单收款进现金账户（托管→现金）。 */
        WorldExchange.FundOutcome sellCash(String holder, String accountId, long qty,
                                           String price, String taskId) {
            WorldExchange.FundOutcome outcome = submit(holder, accountId, Side.SELL, qty, price,
                    taskId, BankCustodyFixture.token(null, accountId));
            check("卖单挂簿 " + taskId, outcome.accepted(), outcome.reason());
            if (outcome.accepted()) {
                with(outcome);
            }
            return outcome;
        }

        /** 客户所属托管池：dave→cst3、co→cst2，其余→cst。 */
        private static String custOf(String client) {
            if ("dave".equals(client)) {
                return CST3;
            }
            if ("co".equals(client)) {
                return CST2;
            }
            return CST;
        }

        WorldExchange.FundOutcome buy(String holder, String accountId, String client, long qty,
                                      String price, String taskId) {
            return submit(holder, accountId, Side.BUY, qty, price, taskId,
                    BankCustodyFixture.custodyToken(custOf(client), client));
        }

        WorldExchange.FundOutcome buyCash(String holder, String accountId, long qty, String price,
                                          String taskId) {
            return submit(holder, accountId, Side.BUY, qty, price, taskId,
                    BankCustodyFixture.token(null, accountId));
        }

        WorldExchange.FundOutcome buyFrom(Scenario base, String holder, String accountId,
                                          String client, long qty, String price,
                                          String taskId) {
            return base.buy(holder, accountId, client, qty, price, taskId);
        }

        WorldExchange.FundOutcome buyWithToken(String holder, String accountId, String client,
                                               long qty, String price, String taskId,
                                               String token) {
            return submit(holder, accountId, Side.BUY, qty, price, taskId, token);
        }

        private WorldExchange.FundOutcome submit(String holder, String accountId, Side side,
                                                 long qty, String price, String taskId,
                                                 String token) {
            long now = T0 + 1000L;
            WorldExchange.FundOutcome outcome = WorldExchange.submit(exchange, accounts,
                    securities, funds(), book, taskId, "c-" + taskId, holder + "-owner",
                    accountId, BankCustodyFixture.SYMBOL, side, OrderType.LIMIT,
                    BankCustodyFixture.money(price), qty, Fund.parse(token), now);
            if (outcome.accepted()) {
                // 成交后把候选写回场景，供本测试的后续步骤继续（World 层由任务/批次完成）。
                accounts = new LinkedHashMap<>(outcome.accounts());
                bank = outcome.bank();
                book = outcome.custody();
                exchange = outcome.exchange();
                securities = outcome.securities();
            }
            return outcome;
        }

        BigDecimal cash(String accountId) {
            return accounts.get(accountId).cash();
        }

        BigDecimal cashOf(WorldExchange.FundOutcome outcome, String accountId) {
            return outcome.accounts().get(accountId).cash();
        }

        BigDecimal pool(String bankKey, String custodian) {
            return bank.depositOf(bankKey, custodian);
        }

        BigDecimal poolOf(WorldExchange.FundOutcome outcome, String bankKey, String custodian) {
            return outcome.bank().depositOf(bankKey, custodian);
        }

        BigDecimal detail(WorldExchange.FundOutcome outcome, String custodian, String client) {
            return outcome.custody().detailOf(custodian, client);
        }

        List<String> validate() {
            return book.validate(bank, accounts, BankCustodyFixture.config());
        }

        List<String> validate(WorldExchange.FundOutcome outcome) {
            return outcome.custody().validate(outcome.bank(), outcome.accounts(),
                    BankCustodyFixture.config());
        }
    }

    // ---------------------------------------------------------------- 断言

    private static boolean eq(BigDecimal value, String expected) {
        return value != null && value.compareTo(new BigDecimal(expected)) == 0;
    }

    private static boolean eq(BigDecimal value, BigDecimal expected) {
        return value != null && expected != null && value.compareTo(expected) == 0;
    }

    private static void check(String name, boolean ok, Object detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " :: " + String.valueOf(detail));
        }
    }

    private static String fmt(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    private static String fmt(String value) {
        return value;
    }

    private static String fmt(Object value) {
        return String.valueOf(value);
    }

    private static BigDecimal money(String value) {
        return BankCustodyFixture.money(value);
    }

    private static <T> T unused(T value) {
        return value;
    }
}
