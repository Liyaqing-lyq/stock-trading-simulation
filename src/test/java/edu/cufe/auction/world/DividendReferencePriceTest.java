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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a：除息参考价接缝（测试先行）。
 *
 * <p>覆盖正式施工单 `docs/superpowers/plans/2026-11-06-dividend-financial-seams.md`：
 * 原五参入口保持原始价、新六参入口按已确认 D0 方案扣除每股股息、来源与时点、注册阶段不扣、
 * 多方案叠加、已付款只扣一次、成交在除息前/同刻/之后的区别、零价合法与负参考缺价、
 * 未知标缺价、未来状态与映射错误拒绝、Long 边界、真实成交链不被改写。</p>
 *
 * <p>D0 状态一律用真实 {@link DividendModel} 全阶段调用生成（提案/授权/登记/除息/清偿/减值），
 * 不手造无历史的 claims；成交流水来自真实 {@link WorldExchange} 撮合。</p>
 */
@DisplayName("ValuationModel：除息参考价接缝")
class DividendReferencePriceTest {

    private static final String SYM = "ACME1";
    private static final String GHOST = "GHOST";
    private static final String ISSUER = "acme";
    private static final String ISSUER_OWNER = "org-acme";
    private static final String COMPANY_ACCOUNT = "ACME-CASH";

    // ------------------------------------------------------------------
    // 原入口与阶段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("原五参入口保持原始价，不受 D0 状态影响")
    void fiveArgPricesStayRaw() {
        ValuationModel.PriceBook raw = ValuationModel.prices(1L, 2_500L, WorldExchange.opening(),
                registry(), List.of(SYM));
        ValuationModel.Price price = ValuationModel.priceOf(raw, SYM);
        assertEquals(0, price.price().compareTo(new BigDecimal("10.00")));
        assertEquals(ValuationModel.PriceSource.OPENING_REFERENCE, price.source());
        assertEquals(0L, price.asOfMillis());
    }

    @Test
    @DisplayName("登记阶段不扣：只有 EX_BOOKED 之后才产生除息参考")
    void registeredDoesNotDeduct() {
        DividendModel.State registered = registered("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);
        ValuationModel.Price price = priceAt(registered, 4L, 2_500L);
        assertEquals(ValuationModel.PriceSource.OPENING_REFERENCE, price.source());
        assertEquals(0, price.price().compareTo(new BigDecimal("10.00")));
    }

    @Test
    @DisplayName("除息确认后 10.00 扣 1.00 得 EX_DIVIDEND_REFERENCE 9.00")
    void exBookedDeductsPerShare() {
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);
        ValuationModel.Price price = priceAt(booked, 5L, 2_500L);

        assertEquals(ValuationModel.PriceSource.EX_DIVIDEND_REFERENCE, price.source());
        assertEquals(0, price.price().compareTo(new BigDecimal("9.00")));
        assertEquals(2_000L, price.asOfMillis(), "来源时点取参与扣减的除息时刻");
        assertEquals(500L, price.ageMillis());
    }

    @Test
    @DisplayName("已清偿方案只扣一次")
    void settledStillDeductsOnce() {
        DividendModel.State settled = settled("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);
        ValuationModel.Price price = priceAt(settled, 6L, 3_500L);
        assertEquals(0, price.price().compareTo(new BigDecimal("9.00")));
        assertEquals(ValuationModel.PriceSource.EX_DIVIDEND_REFERENCE, price.source());
    }

    // ------------------------------------------------------------------
    // 多方案
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一除息时刻的多个方案各自计入")
    void sameExTimeSchemesStack() {
        DividendModel.State both = bookTwoSchemes("1.00", 2_000, "0.50", 2_000);

        ValuationModel.Price price = priceAt(both, 9L, 2_500L);
        assertEquals(0, price.price().compareTo(new BigDecimal("8.50")));
        assertEquals(2_000L, price.asOfMillis());
    }

    @Test
    @DisplayName("不同除息时刻叠加，来源时点取最大者")
    void differentExTimesStackAndAsOfIsLatest() {
        DividendModel.State both = bookTwoSchemes("0.50", 1_500, "1.00", 2_000);

        ValuationModel.Price price = priceAt(both, 9L, 2_500L);
        assertEquals(0, price.price().compareTo(new BigDecimal("8.50")));
        assertEquals(2_000L, price.asOfMillis());
        assertEquals(500L, price.ageMillis());
    }

    @Test
    void tradeBetweenTwoExDatesOnlyDeductsLaterDividend() {
        DividendModel.State both = bookTwoSchemes("0.50", 1_500, "1.00", 2_000);
        Fixture fixture = withTrade("9.50", 1_750);
        ValuationModel.Price price = priceAt(both, fixture.exchange(), 9, 2_500);
        assertEquals(new BigDecimal("8.50"), price.price());
        assertEquals(2_000, price.asOfMillis());
    }

    @Test
    void proposedAuthorizedAndCancelledSchemesNeverAdjustPrices() {
        DividendModel.State proposed = ok(DividendModel.propose(DividendModel.empty(),
                event("propose", 1, 500), request("S1", "1.00", 1_000, 2_000, 3_000)));
        DividendModel.State authorized = ok(DividendModel.authorize(proposed,
                event("authorize", 2, 600), "S1", "board-ref"));
        DividendModel.State cancelled = ok(DividendModel.cancel(authorized,
                event("cancel", 3, 700), "S1", "cancelled-before-record"));
        for (DividendModel.State state : List.of(proposed, authorized, cancelled)) {
            ValuationModel.Price price = priceAt(state, 4, 2_500);
            assertEquals(new BigDecimal("10.00"), price.price());
            assertEquals(ValuationModel.PriceSource.OPENING_REFERENCE, price.source());
        }
    }

    @Test
    void sixArgPricesRejectNullDividendState() {
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.prices(0, 0,
                WorldExchange.opening(), registry(), List.of(SYM), null));
    }

    // ------------------------------------------------------------------
    // 与实际成交的关系
    // ------------------------------------------------------------------

    @Test
    @DisplayName("除息前的真实成交作为基数扣减")
    void tradeBeforeExIsAdjusted() {
        Fixture fixture = withTrade("10.00", 1_500L);
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);

        ValuationModel.Price price = priceAt(booked, fixture.exchange(), 5L, 2_500L);
        assertEquals(ValuationModel.PriceSource.EX_DIVIDEND_REFERENCE, price.source());
        assertEquals(0, price.price().compareTo(new BigDecimal("9.00")));
        assertEquals(2_000L, price.asOfMillis());
    }

    @Test
    @DisplayName("成交恰好发生在除息时刻按除息后成交处理，不再扣")
    void tradeAtExIsNotAdjusted() {
        Fixture fixture = withTrade("9.00", 2_000L);
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);

        ValuationModel.Price price = priceAt(booked, fixture.exchange(), 5L, 2_500L);
        assertEquals(ValuationModel.PriceSource.TRADE, price.source());
        assertEquals(0, price.price().compareTo(new BigDecimal("9.00")), "不得再扣一次");
        assertEquals(2_000L, price.asOfMillis());
    }

    @Test
    @DisplayName("除息之后的成交不扣")
    void tradeAfterExIsNotAdjusted() {
        Fixture fixture = withTrade("8.00", 2_500L);
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);

        ValuationModel.Price price = priceAt(booked, fixture.exchange(), 5L, 3_000L);
        assertEquals(ValuationModel.PriceSource.TRADE, price.source());
        assertEquals(0, price.price().compareTo(new BigDecimal("8.00")));
        assertEquals(2_500L, price.asOfMillis());
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("扣减后恰好为零是合法估值")
    void zeroAfterDeductionIsValid() {
        Fixture fixture = withTrade("1.00", 1_500L);
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);

        ValuationModel.Price price = priceAt(booked, fixture.exchange(), 5L, 2_500L);
        assertEquals(ValuationModel.PriceSource.EX_DIVIDEND_REFERENCE, price.source());
        assertEquals(0, price.price().signum());
        assertEquals(2, price.price().scale());
    }

    @Test
    @DisplayName("扣减后为负价视为缺价，不返回负数")
    void negativeAfterDeductionIsMissing() {
        Fixture fixture = withTrade("0.50", 1_500L);
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);

        ValuationModel.Price price = priceAt(booked, fixture.exchange(), 5L, 2_500L);
        assertEquals(ValuationModel.PriceSource.MISSING, price.source());
        assertNull(price.price());
        assertEquals(-1L, price.asOfMillis());
    }

    @Test
    @DisplayName("未知标的仍是缺价")
    void unknownSymbolStaysMissing() {
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);
        ValuationModel.Price price = ValuationModel.priceOf(ValuationModel.prices(5L, 2_500L,
                WorldExchange.opening(), registry(), List.of(GHOST), booked), GHOST);
        assertEquals(ValuationModel.PriceSource.MISSING, price.source());
    }

    @Test
    @DisplayName("D0 历史晚于查询截止时拒绝")
    void futureDividendStateIsRejected() {
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.prices(2L, 2_500L,
                WorldExchange.opening(), registry(), List.of(SYM), booked), "位置早于 D0 最后确认位置");
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.prices(5L, 1_500L,
                WorldExchange.opening(), registry(), List.of(SYM), booked), "时点早于 D0 最后确认时点");
    }

    @Test
    @DisplayName("发行财产主体与证券发行组织不一致时拒绝")
    void issuerMappingMismatchIsRejected() {
        OpeningRegistry registry = registryWithSecondOrganization();
        DividendModel.State state = DividendModel.empty();
        state = ok(DividendModel.propose(state, new DividendModel.Event("p1", 1L, 1_000L),
                new DividendModel.SchemeRequest("S1", SYM, "org-other", COMPANY_ACCOUNT,
                        new BigDecimal("1.00"), 1_000L, 2_000L, 3_000L)));

        DividendModel.State broken = state;
        assertThrows(IllegalArgumentException.class, () -> ValuationModel.prices(2L, 2_500L,
                WorldExchange.opening(), registry, List.of(SYM), broken));
    }

    @Test
    @DisplayName("Long 最大时点仍可计算除息参考")
    void maximumMillisRemainLegal() {
        long max = Long.MAX_VALUE;
        DividendModel.State booked = exBooked("S1", "1.00", max - 2, max - 1, max, 1L);
        ValuationModel.Price price = priceAt(booked, 6L, max);
        assertEquals(0, price.price().compareTo(new BigDecimal("9.00")));
        assertEquals(max - 1, price.asOfMillis());
        assertEquals(1L, price.ageMillis());
    }

    @Test
    @DisplayName("计算除息参考不改写真实成交链")
    void tradesAreUntouched() {
        Fixture fixture = withTrade("10.00", 1_500L);
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);
        List<WorldExchange.Trade> before = List.copyOf(fixture.exchange().trades());

        priceAt(booked, fixture.exchange(), 5L, 2_500L);
        assertEquals(before, fixture.exchange().trades());
    }

    // ------------------------------------------------------------------
    // 与其他纯核组合
    // ------------------------------------------------------------------

    @Test
    @DisplayName("除息价格与同一 D0 应收组合，净值仍为 1000")
    void priceAndReceivableComposeToUnchangedNetWorth() {
        DividendModel.State booked = exBooked("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);
        ValuationModel.PriceBook book = ValuationModel.prices(5L, 2_500L,
                WorldExchange.opening(), registry(), List.of(SYM), booked);

        ValuationModel.Value value = ValuationModel.value("alice", 5L, 2_500L, registry(),
                accounts(), securities(), booked, book);

        assertEquals(0, value.holdings().compareTo(new BigDecimal("900.00")));
        assertEquals(0, value.dividendReceivable().compareTo(new BigDecimal("100.00")));
        assertEquals(0, value.netWorth().compareTo(new BigDecimal("1000.00")));
    }

    @Test
    @DisplayName("真实付款只改变资产形态：现金增加、应收归零、净值不变")
    void realPaymentOnlyChangesAssetForm() {
        DividendModel.State settled = settled("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L);
        CashLedger.TransferOutcome paid = CashLedger.computeTransfer(accounts(), "org-acme",
                COMPANY_ACCOUNT, "A", new BigDecimal("100.00"));
        assertTrue(paid.accepted(), "夹具前提：公司向持有人真实转账");

        ValuationModel.PriceBook book = ValuationModel.prices(6L, 3_500L,
                WorldExchange.opening(), registry(), List.of(SYM), settled);
        ValuationModel.Value value = ValuationModel.value("alice", 6L, 3_500L, registry(),
                paid.accounts(), securities(), settled, book);

        assertEquals(0, value.cash().compareTo(new BigDecimal("100.00")));
        assertEquals(0, value.dividendReceivable().signum());
        assertEquals(0, value.netWorth().compareTo(new BigDecimal("1000.00")));
    }

    @Test
    @DisplayName("持有人减值降低其净值，但不减少公司合同负债")
    void impairmentReducesInvestorOnly() {
        DividendModel.State impaired = impaired("S1", "1.00", 1_000L, 2_000L, 3_000L, 1L, "40.00");
        ValuationModel.PriceBook book = ValuationModel.prices(6L, 2_500L,
                WorldExchange.opening(), registry(), List.of(SYM), impaired);

        ValuationModel.Value investor = ValuationModel.value("alice", 6L, 2_500L, registry(),
                accounts(), securities(), impaired, book);
        assertEquals(0, investor.dividendReceivable().compareTo(new BigDecimal("60.00")));
        assertEquals(0, investor.netWorth().compareTo(new BigDecimal("960.00")));

        assertEquals(0, DividendModel.companyPayable(impaired, "S1")
                .compareTo(new BigDecimal("100.00")), "公司合同负债不因投资者减值减少");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private record Fixture(WorldExchange.State exchange) {
    }

    private static ValuationModel.Price priceAt(DividendModel.State dividends, long position,
                                                long cutoffMillis) {
        return priceAt(dividends, WorldExchange.opening(), position, cutoffMillis);
    }

    private static ValuationModel.Price priceAt(DividendModel.State dividends,
                                                WorldExchange.State exchange,
                                                long position, long cutoffMillis) {
        return ValuationModel.priceOf(ValuationModel.prices(position, cutoffMillis, exchange,
                registry(), List.of(SYM), dividends), SYM);
    }

    private static OpeningRegistry registry() {
        return registry(false);
    }

    private static OpeningRegistry registryWithSecondOrganization() {
        return registry(true);
    }

    private static OpeningRegistry registry(boolean withOtherOrganization) {
        List<OpeningRegistry.Party> parties = new ArrayList<>();
        parties.add(new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "Alice"));
        parties.add(new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "Bob"));
        parties.add(new OpeningRegistry.Party(ISSUER, OpeningRegistry.PartyKind.ORGANIZATION, "Acme"));
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>();
        owners.add(new OpeningRegistry.PropertyOwner("alice", OpeningRegistry.OwnerKind.PERSON, "alice"));
        owners.add(new OpeningRegistry.PropertyOwner("bob", OpeningRegistry.OwnerKind.PERSON, "bob"));
        owners.add(new OpeningRegistry.PropertyOwner(ISSUER_OWNER,
                OpeningRegistry.OwnerKind.ORGANIZATION, ISSUER));
        if (withOtherOrganization) {
            parties.add(new OpeningRegistry.Party("other",
                    OpeningRegistry.PartyKind.ORGANIZATION, "Other"));
            owners.add(new OpeningRegistry.PropertyOwner("org-other",
                    OpeningRegistry.OwnerKind.ORGANIZATION, "other"));
        }
        return new OpeningRegistry(parties, owners,
                List.of(new OpeningRegistry.Security(SYM, ISSUER, 200L, new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", SYM, 100L,
                                new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation(COMPANY_ACCOUNT, SYM, 100L,
                                new BigDecimal("10.0000"), ShareClass.TREASURY)));
    }

    private static Map<String, CashAccount> accounts() {
        Map<String, CashAccount> accounts = new LinkedHashMap<>();
        accounts.put("A", new CashAccount("alice", new BigDecimal("0.00"), "夹具"));
        accounts.put("B", new CashAccount("bob", new BigDecimal("5000.00"), "夹具"));
        accounts.put(COMPANY_ACCOUNT, new CashAccount(ISSUER_OWNER, new BigDecimal("1000.00"), "夹具"));
        return accounts;
    }

    private static SecuritiesLedger.State securities() {
        return SecuritiesLedger.opening(registry());
    }

    private static Fixture withTrade(String price, long atMillis) {
        OpeningRegistry registry = registry();
        SecuritiesLedger.State securities = SecuritiesLedger.opening(registry);
        Map<String, CashAccount> accounts = accounts();
        WorldExchange.State exchange = WorldExchange.opening();

        WorldExchange.Outcome sell = WorldExchange.submit(exchange, accounts, securities,
                "t-sell", "t-c1", "alice", "A", SYM, Side.SELL, OrderType.LIMIT,
                new BigDecimal(price), 10L, atMillis);
        assertTrue(sell.accepted(), "夹具前提：卖单受理");
        WorldExchange.Outcome buy = WorldExchange.submit(sell.exchange(), sell.accounts(),
                sell.securities(), "t-buy", "t-c2", "bob", "B", SYM, Side.BUY,
                OrderType.LIMIT, new BigDecimal(price), 10L, atMillis);
        assertTrue(buy.accepted(), "夹具前提：买单受理");
        assertFalse(buy.tradeIds().isEmpty(), "夹具前提：必须产生真实成交");
        return new Fixture(buy.exchange());
    }

    // ------------------------------------------------------------------
    // D0 真实阶段
    // ------------------------------------------------------------------

    private static DividendModel.State registered(String id, String perShare, long recordAt,
                                                  long exAt, long payAt, long position) {
        DividendModel.State state = DividendModel.empty();
        state = ok(DividendModel.propose(state, event("p-" + id, position, recordAt),
                request(id, perShare, recordAt, exAt, payAt)));
        state = ok(DividendModel.authorize(state, event("a-" + id, position + 1, recordAt),
                id, "board-ref"));
        return ok(DividendModel.register(state, event("r-" + id, position + 2, recordAt),
                id, securities()));
    }

    private static DividendModel.State exBooked(String id, String perShare, long recordAt,
                                               long exAt, long payAt, long position) {
        DividendModel.State state = registered(id, perShare, recordAt, exAt, payAt, position);
        return ok(DividendModel.bookEx(state, event("b-" + id, position + 3, exAt), id));
    }

    private static DividendModel.State bookTwoSchemes(String firstAmount, long firstEx,
                                                       String secondAmount, long secondEx) {
        DividendModel.State state = DividendModel.empty();
        state = ok(DividendModel.propose(state, event("p-S1", 1, 1_000),
                request("S1", firstAmount, 1_000, firstEx, 3_000)));
        state = ok(DividendModel.authorize(state, event("a-S1", 2, 1_000), "S1", "board-1"));
        state = ok(DividendModel.propose(state, event("p-S2", 3, 1_000),
                request("S2", secondAmount, 1_000, secondEx, 3_000)));
        state = ok(DividendModel.authorize(state, event("a-S2", 4, 1_000), "S2", "board-2"));
        state = ok(DividendModel.register(state, event("r-S1", 5, 1_000), "S1", securities()));
        state = ok(DividendModel.register(state, event("r-S2", 6, 1_000), "S2", securities()));
        state = ok(DividendModel.bookEx(state, event("b-S1", 7, firstEx), "S1"));
        return ok(DividendModel.bookEx(state, event("b-S2", 8, secondEx), "S2"));
    }

    private static DividendModel.State settled(String id, String perShare, long recordAt,
                                              long exAt, long payAt, long position) {
        DividendModel.State state = exBooked(id, perShare, recordAt, exAt, payAt, position);
        Map<String, BigDecimal> perClaim = new LinkedHashMap<>();
        for (DividendModel.Claim claim : DividendModel.claims(state, id)) {
            perClaim.put(claim.claimId(), DividendModel.faceUnpaid(claim));
        }
        BigDecimal amount = BigDecimal.ZERO.setScale(2);
        for (BigDecimal part : perClaim.values()) {
            amount = amount.add(part);
        }
        return ok(DividendModel.settle(state, event("s-" + id, position + 4, payAt),
                id, "fixture-cash-1", amount, perClaim));
    }

    private static DividendModel.State impaired(String id, String perShare, long recordAt,
                                               long exAt, long payAt, long position,
                                               String impairment) {
        DividendModel.State state = exBooked(id, perShare, recordAt, exAt, payAt, position);
        String claimId = DividendModel.claims(state, id).get(0).claimId();
        return ok(DividendModel.impair(state, event("i-" + id, position + 4, exAt + 500),
                claimId, new BigDecimal(impairment), "评估证据"));
    }

    private static DividendModel.Event event(String id, long position, long atMillis) {
        return new DividendModel.Event(id, position, atMillis);
    }

    private static DividendModel.SchemeRequest request(String id, String perShare, long recordAt,
                                                       long exAt, long payAt) {
        return new DividendModel.SchemeRequest(id, SYM, ISSUER_OWNER, COMPANY_ACCOUNT,
                new BigDecimal(perShare), recordAt, exAt, payAt);
    }

    private static DividendModel.State ok(DividendModel.Result result) {
        assertTrue(result.accepted(), "夹具前提：D0 阶段失败 " + result.reason());
        return result.state();
    }
}
