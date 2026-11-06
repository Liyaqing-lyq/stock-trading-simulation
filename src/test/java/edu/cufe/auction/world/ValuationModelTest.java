package edu.cufe.auction.world;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1：统一账户估值与价格来源（测试先行）。
 *
 * <p>覆盖正式施工单 `docs/superpowers/plans/2026-11-06-valuation-performance.md`：
 * 现金只计一次、冻结与 LOCKED 计入、TREASURY 作为持有数量保留、严格截止与同刻成交号、
 * 开局参考、缺价不出部分净值、组织主体不支持完整净资产、分红应收/应付复用 D0、
 * 未知主体与孤儿引用拒绝、金额精度与极值、集合深不可变。</p>
 *
 * <p>价格与成交流水来自真实 {@link WorldExchange}（含真实撮合产生的 Trade），
 * 持仓来自真实 {@link SecuritiesLedger}，分红来自真实 {@link DividendModel} 全阶段调用。
 * 需要精确控制的算术场景另用不可变 {@link ValuationModel.PriceBook} 合成价格，
 * 合成价不冒称真实行情。D0 的 settle 只确认元数据，测试里的现金证据是明确夹具输入，
 * 不代表已接入世界真实付款。</p>
 */
@DisplayName("ValuationModel：统一估值与价格来源")
class ValuationModelTest {

    private static final String SYM = "ACME1";
    private static final String SYM2 = "ACME2";
    private static final String GHOST = "GHOST";
    private static final String ISSUER = "acme";

    // ------------------------------------------------------------------
    // 价格来源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("价格取截止前最后一笔成交，同刻按成交号最大")
    void pricesTakeLatestTradeByTimeThenTradeId() {
        Fixture fixture = fixtureWithTrades();
        ValuationModel.PriceBook book = ValuationModel.prices(7L, 1_500L,
                fixture.exchange(), fixture.registry(), List.of(SYM));

        ValuationModel.Price price = ValuationModel.priceOf(book, SYM);
        assertEquals(0, price.price().compareTo(new BigDecimal("9.50")),
                "同毫秒两笔成交必须取成交号更大的那笔（9.50），不是第一笔 10.00");
        assertEquals(ValuationModel.PriceSource.TRADE, price.source());
        assertEquals(1_000L, price.asOfMillis());
        assertEquals(500L, price.ageMillis(), "陈旧毫秒恰为截止减来源时间");
        assertEquals(7L, book.position());
        assertEquals(1_500L, book.atMillis());
    }

    @Test
    @DisplayName("截止之后的成交不得泄漏")
    void pricesIgnoreTradesAfterCutoff() {
        Fixture fixture = fixtureWithTrades();

        ValuationModel.Price early = ValuationModel.priceOf(
                ValuationModel.prices(1L, 1_500L, fixture.exchange(), fixture.registry(), List.of(SYM)), SYM);
        assertFalse(early.price().compareTo(new BigDecimal("11.00")) == 0, "未来价 11.00 不得出现在 1500 截止");

        ValuationModel.Price later = ValuationModel.priceOf(
                ValuationModel.prices(2L, 2_500L, fixture.exchange(), fixture.registry(), List.of(SYM)), SYM);
        assertEquals(0, later.price().compareTo(new BigDecimal("11.00")));
        assertEquals(2_000L, later.asOfMillis());
    }

    @Test
    @DisplayName("从未成交时使用开局参考价，来源与时间单列")
    void pricesFallBackToOpeningReference() {
        Fixture fixture = fixtureWithTrades();
        ValuationModel.Price price = ValuationModel.priceOf(
                ValuationModel.prices(1L, 500L, fixture.exchange(), fixture.registry(), List.of(SYM)), SYM);

        assertEquals(0, price.price().compareTo(new BigDecimal("10.00")));
        assertEquals(ValuationModel.PriceSource.OPENING_REFERENCE, price.source());
        assertEquals(0L, price.asOfMillis());
        assertEquals(500L, price.ageMillis());
    }

    @Test
    @DisplayName("无成交且无参考时为 MISSING，价格与时间均为空值而非 0")
    void pricesReportMissingForUnknownSymbol() {
        Fixture fixture = fixtureWithTrades();
        ValuationModel.Price price = ValuationModel.priceOf(
                ValuationModel.prices(1L, 1_500L, fixture.exchange(), fixture.registry(), List.of(GHOST)), GHOST);

        assertEquals(ValuationModel.PriceSource.MISSING, price.source());
        assertNull(price.price(), "缺价不得转为 0");
        assertEquals(-1L, price.asOfMillis());
        assertEquals(-1L, price.ageMillis());
    }

    @Test
    @DisplayName("未启用撮合的快照不能捏造成交")
    void nullExchangeCannotInventTrades() {
        ValuationModel.PriceBook book = ValuationModel.prices(1L, 1_500L, null,
                registry(), List.of(SYM, GHOST));

        assertEquals(ValuationModel.PriceSource.OPENING_REFERENCE,
                ValuationModel.priceOf(book, SYM).source());
        assertEquals(ValuationModel.PriceSource.MISSING,
                ValuationModel.priceOf(book, GHOST).source());
    }

    @Test
    @DisplayName("零价格是明确的估值，不等同缺价")
    void zeroPriceIsAValueNotMissing() {
        ValuationModel.Price zero = new ValuationModel.Price(SYM, new BigDecimal("0.00"),
                ValuationModel.PriceSource.TRADE, 100L, 0L);
        assertEquals(ValuationModel.PriceSource.TRADE, zero.source());
        assertEquals(0, zero.price().signum());

        assertThrows(IllegalArgumentException.class, () -> new ValuationModel.Price(SYM,
                new BigDecimal("-0.01"), ValuationModel.PriceSource.TRADE, 100L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ValuationModel.Price(SYM,
                new BigDecimal("10.00"), ValuationModel.PriceSource.MISSING, -1L, -1L));
    }

    @Test
    void priceBookChecksEveryQuoteTimeAndAgeAgainstItsCutoff() {
        for (ValuationModel.Price price : List.of(
                new ValuationModel.Price(SYM, new BigDecimal("10.00"), ValuationModel.PriceSource.TRADE, 400, 0),
                new ValuationModel.Price(SYM, new BigDecimal("10.00"), ValuationModel.PriceSource.TRADE, 200, 0))) {
            assertThrows(IllegalArgumentException.class, () -> new ValuationModel.PriceBook(1, 300, Map.of(SYM, price)));
        }
        assertThrows(IllegalArgumentException.class, () -> new ValuationModel.PriceBook(1, Long.MAX_VALUE,
                Map.of(SYM, new ValuationModel.Price(SYM, new BigDecimal("10.00"),
                        ValuationModel.PriceSource.TRADE, Long.MAX_VALUE, 1))));
        ValuationModel.PriceBook legal = new ValuationModel.PriceBook(1, Long.MAX_VALUE,
                Map.of(SYM, new ValuationModel.Price(SYM, new BigDecimal("10.00"),
                        ValuationModel.PriceSource.OPENING_REFERENCE, 0, Long.MAX_VALUE)));
        assertEquals(Long.MAX_VALUE, legal.prices().get(SYM).ageMillis());
    }

    @Test
    void openingReferenceCannotPretendToBeObservedLaterThanOpening() {
        assertThrows(IllegalArgumentException.class, () -> new ValuationModel.PriceBook(1, 300,
                Map.of(SYM, new ValuationModel.Price(SYM, new BigDecimal("10.00"),
                        ValuationModel.PriceSource.OPENING_REFERENCE, 200, 100))));
    }

    @Test
    void accountAndSymbolPairsCannotAliasThroughASeparatorCharacter() {
        String firstAccount = "A\u0000B";
        String secondSymbol = "B\u0000C";
        OpeningRegistry base = registry();
        OpeningRegistry registry = new OpeningRegistry(base.parties(), base.owners(),
                List.of(new OpeningRegistry.Security("C", ISSUER, 2, new BigDecimal("10.00")),
                        new OpeningRegistry.Security(secondSymbol, ISSUER, 3, new BigDecimal("20.00"))),
                List.of(new OpeningRegistry.ShareAllocation(firstAccount, "C", 2, new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("A", secondSymbol, 3, new BigDecimal("20.0000"), ShareClass.FLOAT)));
        Map<String, CashAccount> accounts = Map.of(firstAccount, new CashAccount("alice", new BigDecimal("0.00"), "开局"),
                "A", new CashAccount("alice", new BigDecimal("0.00"), "开局"));
        ValuationModel.Value value = ValuationModel.value("alice", 1, 100, registry, accounts,
                SecuritiesLedger.opening(registry), DividendModel.empty(),
                ValuationModel.prices(1, 100, null, registry, List.of("C", secondSymbol)));
        assertEquals(2, value.lines().size());
        assertEquals(new BigDecimal("80.00"), value.holdings());
    }

    // ------------------------------------------------------------------
    // 账户估值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("现金只计一次：冻结额已包含在余额中")
    void cashCountsFrozenExactlyOnce() {
        Fixture fixture = openingFixture();
        SecuritiesLedger.State reserved = SecuritiesLedger.reserve(
                fixture.securities(), "res-value", "A", SYM, 30L, 0L).state();

        ValuationModel.Value value = ValuationModel.value("alice", 3L, 2_500L, fixture.registry(),
                fixture.accounts(), reserved, DividendModel.empty(),
                valuationBook(3L, 2_500L, "11.00", "5.00"));

        assertEquals(0, value.cash().compareTo(new BigDecimal("1000.00")), "现金不得再加冻结");
        assertEquals(0, value.holdings().compareTo(new BigDecimal("1600.00")),
                "100 股 11.00 与 100 股 5.00 全部计入，冻结的 30 股不扣减");
        assertEquals(0, value.netWorth().compareTo(new BigDecimal("2600.00")));
        assertEquals(ValuationModel.Status.COMPLETE, value.status());
    }

    @Test
    @DisplayName("多账户聚合，LOCKED 批次计入持有")
    void aggregatesAccountsAndIncludesLocked() {
        Fixture fixture = openingFixture();
        OpeningRegistry expandedRegistry = withAllocation(fixture.registry(),
                new OpeningRegistry.ShareAllocation("A2", SYM, 50L, new BigDecimal("10.0000"), ShareClass.LOCKED));
        SecuritiesLedger.State securities = SecuritiesLedger.opening(expandedRegistry);

        Map<String, CashAccount> accounts = new LinkedHashMap<>(fixture.accounts());
        accounts.put("A2", new CashAccount("alice", new BigDecimal("500.00"), "夹具第二账户"));

        ValuationModel.Value value = ValuationModel.value("alice", 3L, 2_500L, expandedRegistry,
                accounts, securities, DividendModel.empty(),
                valuationBook(3L, 2_500L, "11.00", "5.00"));

        assertEquals(0, value.cash().compareTo(new BigDecimal("1500.00")), "同一主体两个账户必须聚合");
        assertEquals(0, value.holdings().compareTo(new BigDecimal("2150.00")),
                "含额外 50 股 LOCKED：100×11 + 100×5 + 50×11 = 2150");
        assertEquals(150L, value.lines().stream().filter(line -> line.symbol().equals(SYM))
                        .mapToLong(ValuationModel.HoldingLine::quantity).sum(),
                "同一标的的流通与锁定批次都进入明细且数量合计 150");
    }

    @Test
    @DisplayName("组织主体不支持完整净资产，但保留明细与缺价标的")
    void organizationHasNoCompleteNetWorth() {
        Fixture fixture = openingFixture();
        OpeningRegistry expandedRegistry = withAllocation(fixture.registry(),
                new OpeningRegistry.ShareAllocation("ACME-CASH", GHOST, 10L, new BigDecimal("1.0000"), ShareClass.FLOAT));
        SecuritiesLedger.State securities = SecuritiesLedger.opening(expandedRegistry);

        ValuationModel.Value value = ValuationModel.value("org-acme", 3L, 2_500L, expandedRegistry,
                fixture.accounts(), securities, DividendModel.empty(),
                valuationBook(3L, 2_500L, "11.00", "5.00"));

        assertEquals(ValuationModel.Status.UNSUPPORTED_COMPANY_SCOPE, value.status(),
                "组织状态优先于缺价");
        assertNull(value.netWorth(), "组织不得给出完整净资产");
        assertTrue(value.missingSymbols().contains(GHOST), "仍须列出缺价标的");
        assertTrue(value.lines().stream().anyMatch(line -> line.quantity() == 100L
                        && line.symbol().equals(SYM)),
                "库存股作为明确持有数量保留在明细里");
    }

    @Test
    @DisplayName("缺价时持仓与净资产为空，绝不输出部分净值")
    void missingPriceBlanksHoldingsAndNetWorth() {
        Fixture fixture = openingFixture();
        OpeningRegistry expandedRegistry = withAllocation(fixture.registry(),
                new OpeningRegistry.ShareAllocation("A", GHOST, 10L, new BigDecimal("1.0000"), ShareClass.FLOAT));
        SecuritiesLedger.State securities = SecuritiesLedger.opening(expandedRegistry);

        ValuationModel.Value value = ValuationModel.value("alice", 3L, 2_500L, expandedRegistry,
                fixture.accounts(), securities, DividendModel.empty(),
                valuationBook(3L, 2_500L, "11.00", "5.00"));

        assertEquals(ValuationModel.Status.MISSING_PRICE, value.status());
        assertNull(value.holdings(), "缺价时不得输出部分持仓市值");
        assertNull(value.netWorth(), "缺价时不得输出部分净值");
        assertTrue(value.missingSymbols().contains(GHOST));
        assertEquals(0, value.cash().compareTo(new BigDecimal("1000.00")), "已知现金仍可读");
    }

    @Test
    @DisplayName("空持仓不因无行情变成缺价")
    void emptyPortfolioIsNotMissingPrice() {
        Fixture fixture = openingFixture();
        ValuationModel.Value value = ValuationModel.value("bob", 3L, 2_500L, fixture.registry(),
                fixture.accounts(), fixture.securities(), DividendModel.empty(),
                valuationBook(3L, 2_500L, "11.00", "5.00"));

        assertEquals(ValuationModel.Status.COMPLETE, value.status());
        assertEquals(0, value.holdings().signum());
        assertEquals(0, value.netWorth().compareTo(new BigDecimal("5000.00")));
        assertTrue(value.missingSymbols().isEmpty());
    }

    // ------------------------------------------------------------------
    // 分红应收与应付（复用 D0）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("除息后应收计入净值，市值按夹具价下降不被当成亏损")
    void dividendReceivableOffsetsExPriceInFixture() {
        DividendFixture dividend = exBookedDividend();

        ValuationModel.Value value = ValuationModel.value("alice", 7L, 2_500L, dividend.registry(),
                dividend.accounts(), dividend.securities(), dividend.dividends(),
                valuationBook(7L, 2_500L, "9.00", "5.00"));

        assertEquals(0, value.holdings().compareTo(new BigDecimal("900.00")), "夹具价 9.00 × 100 股");
        assertEquals(0, value.dividendReceivable().compareTo(new BigDecimal("100.00")));
        assertEquals(0, value.netWorth().compareTo(new BigDecimal("1000.00")),
                "900 市值 + 100 应收 = 1000，与除息前一致");
    }

    @Test
    @DisplayName("减值只减净应收，公司应付仍是合同未付额")
    void impairmentReducesReceivableNotCompanyPayable() {
        DividendFixture dividend = impairedDividend("40.00");

        ValuationModel.Value investor = ValuationModel.value("alice", 8L, 2_500L, dividend.registry(),
                dividend.accounts(), dividend.securities(), dividend.dividends(),
                valuationBook(8L, 2_500L, "9.00", "5.00"));
        assertEquals(0, investor.dividendReceivable().compareTo(new BigDecimal("60.00")));
        assertEquals(0, investor.dividendPayable().signum());

        ValuationModel.Value company = ValuationModel.value("org-acme", 8L, 2_500L, dividend.registry(),
                dividend.accounts(), dividend.securities(), dividend.dividends(),
                valuationBook(8L, 2_500L, "9.00", "5.00"));
        assertEquals(0, company.dividendPayable().compareTo(new BigDecimal("100.00")),
                "公司合同应付不得因投资者减值而减少");
    }

    @Test
    @DisplayName("清偿后应收归零，现金由账户表体现，不重复计收益")
    void settledDividendLeavesNoReceivable() {
        DividendFixture dividend = settledDividend();
        Map<String, CashAccount> accounts = new LinkedHashMap<>(dividend.accounts());
        accounts.put("A", new CashAccount("alice", new BigDecimal("100.00"), "夹具现金"));
        accounts.put("ACME-CASH", new CashAccount("org-acme", new BigDecimal("900.00"), "夹具公司实际出款后"));

        ValuationModel.Value value = ValuationModel.value("alice", 12L, 3_500L, dividend.registry(),
                accounts, dividend.securities(), dividend.dividends(),
                valuationBook(12L, 3_500L, "9.00", "5.00"));

        assertEquals(0, value.dividendReceivable().signum(), "已付清后不得再有应收");
        assertEquals(0, value.netWorth().compareTo(new BigDecimal("1000.00")),
                "900 市值 + 100 现金 = 1000，只发生资产形态变化");
    }

    // ------------------------------------------------------------------
    // 边界与拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未知财产主体拒绝，不静默当零")
    void unknownOwnerIsRejected() {
        Fixture fixture = openingFixture();
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.value("nobody", 3L, 2_500L,
                fixture.registry(), fixture.accounts(), fixture.securities(), DividendModel.empty(),
                valuationBook(3L, 2_500L, "11.00", "5.00")));
    }

    @Test
    @DisplayName("孤儿引用拒绝：现金账户、持仓账户、债权账户都必须能映射到登记主体")
    void orphanReferencesAreRejected() {
        Fixture fixture = openingFixture();

        Map<String, CashAccount> orphanCash = new LinkedHashMap<>(fixture.accounts());
        orphanCash.put("X", new CashAccount("ghost-owner", new BigDecimal("1.00"), "孤儿"));
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.value("alice", 3L, 2_500L,
                fixture.registry(), orphanCash, fixture.securities(), DividendModel.empty(),
                valuationBook(3L, 2_500L, "11.00", "5.00")));

        List<SecuritiesLedger.Lot> lots = new ArrayList<>(fixture.securities().lots());
        lots.add(new SecuritiesLedger.Lot("lot-orphan", "NOBODY", SYM, 1L, 0L, 0L, 96L,
                new BigDecimal("10.0000"), ShareClass.FLOAT));
        SecuritiesLedger.State orphanHolding = new SecuritiesLedger.State(lots, List.of(), Set.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.value("alice", 3L, 2_500L,
                fixture.registry(), fixture.accounts(), orphanHolding, DividendModel.empty(),
                valuationBook(3L, 2_500L, "11.00", "5.00")));
    }

    @Test
    @DisplayName("价格簿与估值截止不一致时拒绝，不混用两个快照")
    void bookCutoffMustMatchValuationCutoff() {
        Fixture fixture = openingFixture();
        ValuationModel.PriceBook book = valuationBook(3L, 2_500L, "11.00", "5.00");

        assertThrows(IllegalArgumentException.class, () -> ValuationModel.value("alice", 3L, 2_400L,
                fixture.registry(), fixture.accounts(), fixture.securities(), DividendModel.empty(), book));
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.value("alice", 4L, 2_500L,
                fixture.registry(), fixture.accounts(), fixture.securities(), DividendModel.empty(), book));
    }

    @Test
    @DisplayName("分红历史晚于估值截止时拒绝")
    void dividendsAfterCutoffAreRejected() {
        DividendFixture dividend = settledDividend();
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.value("alice", 9L, 2_500L,
                dividend.registry(), dividend.accounts(), dividend.securities(), dividend.dividends(),
                valuationBook(9L, 2_500L, "9.00", "5.00")));
    }

    @Test
    void registrationAloneDoesNotIncreaseValuedAssets() {
        DividendFixture fixture = exBookedDividend();
        DividendModel.State state = DividendModel.propose(DividendModel.empty(), new DividendModel.Event("p", 1, 1_000),
                new DividendModel.SchemeRequest("registered", SYM, "org-acme", "ACME-CASH",
                        new BigDecimal("1.00"), 1_000, 2_000, 3_000)).state();
        state = DividendModel.authorize(state, new DividendModel.Event("a", 2, 1_000), "registered", "board").state();
        state = DividendModel.register(state, new DividendModel.Event("r", 3, 1_000), "registered", fixture.securities()).state();
        ValuationModel.Value value = ValuationModel.value("alice", 3, 1_000, fixture.registry(), fixture.accounts(),
                fixture.securities(), state, valuationBook(3, 1_000, "10.00", "5.00"));
        assertEquals(new BigDecimal("0.00"), value.dividendReceivable());
        assertEquals(new BigDecimal("1000.00"), value.netWorth());
    }

    @Test
    void dividendIssuerAndPaymentAccountMustMapToTheSameRegisteredOrganization() {
        DividendFixture fixture = exBookedDividend();
        DividendModel.State state = DividendModel.propose(DividendModel.empty(), new DividendModel.Event("p", 1, 1_000),
                new DividendModel.SchemeRequest("bad-owner", SYM, "alice", "ACME-CASH",
                        new BigDecimal("1.00"), 1_000, 2_000, 3_000)).state();
        DividendModel.State inconsistent = state;
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.value("alice", 3, 1_000,
                fixture.registry(), fixture.accounts(), fixture.securities(), inconsistent, valuationBook(3, 1_000, "10.00", "5.00")));
    }

    @Test
    void futureAcquisitionsCannotBePresentedAsEarlierHoldings() {
        Fixture fixture = fixtureWithTrades();
        ValuationModel.Value current = ValuationModel.value("bob", 7, 2_500, fixture.registry(),
                fixture.accounts(), fixture.securities(), DividendModel.empty(), valuationBook(7, 2_500, "11.00", "5.00"));
        assertEquals(new BigDecimal("5025.00"), current.netWorth());
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.value("bob", 7, 1_500, fixture.registry(),
                fixture.accounts(), fixture.securities(), DividendModel.empty(), valuationBook(7, 1_500, "9.50", "5.00")));
    }

    // ------------------------------------------------------------------
    // 精度、极值与不可变
    // ------------------------------------------------------------------

    @Test
    @DisplayName("净值公式自洽且金额精确 2 位")
    void netWorthFormulaAndScale() {
        Fixture fixture = openingFixture();
        ValuationModel.Value value = ValuationModel.value("alice", 3L, 2_500L, fixture.registry(),
                fixture.accounts(), fixture.securities(), DividendModel.empty(),
                valuationBook(3L, 2_500L, "11.00", "5.00"));

        BigDecimal expected = value.cash().add(value.holdings())
                .add(value.dividendReceivable()).subtract(value.dividendPayable());
        assertEquals(0, value.netWorth().compareTo(expected));
        assertEquals(2, value.cash().scale());
        assertEquals(2, value.holdings().scale());
        assertEquals(2, value.netWorth().scale());
    }

    @Test
    @DisplayName("巨额金额仍用精确 BigDecimal 乘数量")
    void hugeAmountsStayExact() {
        long quantity = 123_456_789L;
        BigDecimal price = new BigDecimal("999999999.99");
        OpeningRegistry base = registry();
        OpeningRegistry registry = new OpeningRegistry(base.parties(), base.owners(),
                List.of(new OpeningRegistry.Security(SYM, ISSUER, quantity, new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", SYM, quantity,
                        new BigDecimal("10.0000"), ShareClass.FLOAT)));
        SecuritiesLedger.State securities = SecuritiesLedger.opening(registry);

        ValuationModel.Value value = ValuationModel.value("alice", 1L, 1_000L, registry,
                Map.of("A", new CashAccount("alice", new BigDecimal("0.00"), "夹具")),
                securities, DividendModel.empty(),
                new ValuationModel.PriceBook(1L, 1_000L, Map.of(SYM, new ValuationModel.Price(SYM,
                        price, ValuationModel.PriceSource.TRADE, 0L, 1_000L))));

        BigDecimal expected = price.multiply(BigDecimal.valueOf(quantity));
        assertEquals(0, value.holdings().compareTo(expected));
        assertEquals(2, value.holdings().scale());
    }

    @Test
    @DisplayName("结果集合不可变，且不修改输入模型")
    void resultsAreImmutableAndInputsUntouched() {
        Fixture fixture = openingFixture();
        Map<String, CashAccount> accounts = new LinkedHashMap<>(fixture.accounts());
        SecuritiesLedger.State securities = fixture.securities();
        DividendModel.State dividends = DividendModel.empty();

        ValuationModel.Value value = ValuationModel.value("alice", 3L, 2_500L, fixture.registry(),
                accounts, securities, dividends, valuationBook(3L, 2_500L, "11.00", "5.00"));

        assertThrows(UnsupportedOperationException.class, () -> value.lines().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.missingSymbols().add("X"));
        assertEquals(new LinkedHashMap<>(fixture.accounts()), accounts, "现金表不得被改写");
        assertEquals(fixture.securities(), securities, "证券账不得被改写");
        assertEquals(DividendModel.empty(), dividends, "分红状态不得被改写");
        assertNotNull(value.ownerId());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private record Fixture(OpeningRegistry registry, Map<String, CashAccount> accounts,
                           SecuritiesLedger.State securities, WorldExchange.State exchange) {
    }

    private record DividendFixture(OpeningRegistry registry, Map<String, CashAccount> accounts,
                                   SecuritiesLedger.State securities,
                                   DividendModel.State dividends, long lastPosition) {
    }

    private static ValuationModel.PriceBook valuationBook(long position, long atMillis,
                                                          String price, String price2) {
        return new ValuationModel.PriceBook(position, atMillis, Map.of(
                SYM, new ValuationModel.Price(SYM, new BigDecimal(price),
                        ValuationModel.PriceSource.TRADE, atMillis - 100L, 100L),
                SYM2, new ValuationModel.Price(SYM2, new BigDecimal(price2),
                        ValuationModel.PriceSource.TRADE, atMillis - 100L, 100L)));
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "Alice"),
                        new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "Bob"),
                        new OpeningRegistry.Party("acme", OpeningRegistry.PartyKind.ORGANIZATION, "Acme")),
                List.of(new OpeningRegistry.PropertyOwner("alice",
                                OpeningRegistry.OwnerKind.PERSON, "alice"),
                        new OpeningRegistry.PropertyOwner("bob",
                                OpeningRegistry.OwnerKind.PERSON, "bob"),
                        new OpeningRegistry.PropertyOwner("org-acme",
                                OpeningRegistry.OwnerKind.ORGANIZATION, "acme")),
                List.of(new OpeningRegistry.Security(SYM, ISSUER, 200L, new BigDecimal("10.00")),
                        new OpeningRegistry.Security(SYM2, ISSUER, 100L, new BigDecimal("5.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", SYM, 100L,
                                new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("ACME-CASH", SYM, 100L,
                                new BigDecimal("10.0000"), ShareClass.TREASURY),
                        new OpeningRegistry.ShareAllocation("A", SYM2, 100L,
                                new BigDecimal("5.0000"), ShareClass.FLOAT)));
    }

    private static Map<String, CashAccount> accounts() {
        Map<String, CashAccount> accounts = new LinkedHashMap<>();
        accounts.put("A", new CashAccount("alice", new BigDecimal("1000.00"), "开局"));
        accounts.put("B", new CashAccount("bob", new BigDecimal("5000.00"), "开局"));
        accounts.put("ACME-CASH", new CashAccount("org-acme", new BigDecimal("2000.00"), "开局"));
        return accounts;
    }

    private static Fixture openingFixture() {
        OpeningRegistry registry = registry();
        return new Fixture(registry, accounts(), SecuritiesLedger.opening(registry), WorldExchange.opening());
    }

    /** 独立开局增加有限历史禀赋，并同步发行登记，避免把活动世界批次凭空追加。 */
    private static OpeningRegistry withAllocation(OpeningRegistry base, OpeningRegistry.ShareAllocation extra) {
        Map<String, OpeningRegistry.Security> securities = new LinkedHashMap<>();
        for (OpeningRegistry.Security security : base.securities()) securities.put(security.symbol(), security);
        OpeningRegistry.Security original = securities.get(extra.symbol());
        long issued = extra.quantity();
        if (original != null) issued = Math.addExact(issued, original.issuedShares());
        securities.put(extra.symbol(), new OpeningRegistry.Security(extra.symbol(), ISSUER, issued,
                extra.costPrice().setScale(2)));
        List<OpeningRegistry.ShareAllocation> allocations = new ArrayList<>(base.allocations());
        allocations.add(extra);
        return new OpeningRegistry(base.parties(), base.owners(), new ArrayList<>(securities.values()), allocations);
    }

    @Test
    void realUnfilledBuyReservationIsIncludedInTotalCashOnlyOnce() {
        Fixture fixture = openingFixture();
        WorldExchange.Outcome pending = WorldExchange.submit(fixture.exchange(), fixture.accounts(), fixture.securities(),
                "pending-buy", "pending-buy-command", "alice", "A", SYM, Side.BUY, OrderType.LIMIT,
                new BigDecimal("8.00"), 25L, 100L);
        assertTrue(pending.accepted());
        assertEquals(new BigDecimal("200.00"), WorldExchange.frozenCash(pending.exchange(), "A"));
        ValuationModel.Value value = ValuationModel.value("alice", 3, 2_500, fixture.registry(),
                pending.accounts(), pending.securities(), DividendModel.empty(), valuationBook(3, 2_500, "11.00", "5.00"));
        assertEquals(new BigDecimal("1000.00"), value.cash());
        assertEquals(new BigDecimal("2600.00"), value.netWorth());
    }

    /** 真实撮合产生三笔成交：t=1000 两笔（10.00、9.50），t=2000 一笔（11.00）。 */
    private static Fixture fixtureWithTrades() {
        OpeningRegistry registry = registry();
        SecuritiesLedger.State securities = SecuritiesLedger.opening(registry);
        Map<String, CashAccount> accounts = accounts();
        WorldExchange.State exchange = WorldExchange.opening();

        WorldExchange.Outcome first = match(securities, accounts, exchange,
                "t1", "10.00", 10L, 1_000L);
        WorldExchange.Outcome second = match(first.securities(), first.accounts(), first.exchange(),
                "t2", "9.50", 10L, 1_000L);
        WorldExchange.Outcome third = match(second.securities(), second.accounts(), second.exchange(),
                "t3", "11.00", 10L, 2_000L);
        return new Fixture(registry, third.accounts(), third.securities(), third.exchange());
    }

    private static WorldExchange.Outcome match(SecuritiesLedger.State securities,
                                               Map<String, CashAccount> accounts,
                                               WorldExchange.State exchange,
                                               String task, String price, long quantity, long atMillis) {
        WorldExchange.Outcome sell = WorldExchange.submit(exchange, accounts, securities,
                task + "-sell", task + "-c1", "alice", "A", SYM, Side.SELL, OrderType.LIMIT,
                new BigDecimal(price), quantity, atMillis);
        assertTrue(sell.accepted(), "夹具前提：卖单必须受理：" + sell.reason());
        WorldExchange.Outcome buy = WorldExchange.submit(sell.exchange(), sell.accounts(), sell.securities(),
                task + "-buy", task + "-c2", "bob", "B", SYM, Side.BUY, OrderType.LIMIT,
                new BigDecimal(price), quantity, atMillis);
        assertTrue(buy.accepted(), "夹具前提：买单必须受理：" + buy.reason());
        assertFalse(buy.tradeIds().isEmpty(), "夹具前提：必须产生真实成交");
        return buy;
    }

    private static DividendFixture exBookedDividend() {
        return dividendFixture("ex", "0.00", false, null);
    }

    private static DividendFixture impairedDividend(String impairment) {
        return dividendFixture("impair", impairment, false, null);
    }

    private static DividendFixture settledDividend() {
        return dividendFixture("settle", "0.00", true, "fixture-cash-1");
    }

    /**
     * 真实 D0 全阶段：提案、授权、登记、除息、（可选）减值、清偿。
     * 现金证据是夹具输入，不代表世界真实付款。
     */
    private static DividendFixture dividendFixture(String mode, String impairment,
                                                   boolean settle, String cashEvidence) {
        OpeningRegistry base = registry();
        OpeningRegistry registry = new OpeningRegistry(base.parties(), base.owners(),
                List.of(base.security(SYM)), base.allocations().stream()
                        .filter(allocation -> allocation.symbol().equals(SYM)).toList());
        SecuritiesLedger.State securities = SecuritiesLedger.opening(registry);
        Map<String, CashAccount> accounts = Map.of(
                "A", new CashAccount("alice", new BigDecimal("0.00"), "夹具"),
                "ACME-CASH", new CashAccount("org-acme", new BigDecimal("1000.00"), "夹具"));

        DividendModel.State state = DividendModel.empty();
        state = DividendModel.propose(state, new DividendModel.Event("d1", 1L, 1_000L),
                new DividendModel.SchemeRequest("S1", SYM, "org-acme", "ACME-CASH",
                        new BigDecimal("1.00"), 1_000L, 2_000L, 3_000L)).state();
        state = DividendModel.authorize(state, new DividendModel.Event("d2", 2L, 1_000L),
                "S1", "board-ref").state();
        state = DividendModel.register(state, new DividendModel.Event("d3", 3L, 1_000L), "S1", securities).state();
        state = DividendModel.bookEx(state, new DividendModel.Event("d4", 4L, 2_000L), "S1").state();

        long position = 4L;
        if (!"0.00".equals(impairment)) {
            String claimId = DividendModel.claims(state, "S1").get(0).claimId();
            state = DividendModel.impair(state, new DividendModel.Event("d5", 5L, 2_500L),
                    claimId, new BigDecimal(impairment), "评估证据").state();
            position = 5L;
        }
        if (settle) {
            Map<String, BigDecimal> perClaim = new LinkedHashMap<>();
            for (DividendModel.Claim claim : DividendModel.claims(state, "S1")) {
                perClaim.put(claim.claimId(), DividendModel.faceUnpaid(claim));
            }
            BigDecimal amount = BigDecimal.ZERO.setScale(2);
            for (BigDecimal part : perClaim.values()) {
                amount = amount.add(part);
            }
            state = DividendModel.settle(state, new DividendModel.Event("d6", position + 1, 3_000L),
                    "S1", cashEvidence, amount, perClaim).state();
            position = position + 1;
        }
        return new DividendFixture(registry, accounts, securities, state, position);
    }
}
