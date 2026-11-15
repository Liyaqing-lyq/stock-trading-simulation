package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import edu.cufe.auction.world.bank.FundState;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static edu.cufe.auction.world.PureCompanyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 独立审查补测（陈酿）：到期有限付款、拒绝原子性、退款到期链、产能累计。
 * 只新增测试，不改作者实现。
 */
class IndependentAgedBoundaryTest {

    private static final long P = AgedIndustryConfig.PERIOD_MILLIS;

    /** 公司现金很小、其余对手账户为零的世界。 */
    private static Map<String, CashLedger.CashAccount> thin(String companyCash) {
        Map<String, CashLedger.CashAccount> accounts = new LinkedHashMap<>();
        accounts.put(AgedIndustryConfig.COMPANY_ACCOUNT, account(AgedIndustryConfig.COMPANY, companyCash));
        accounts.put(AgedIndustryConfig.SUPPLIER_ACCOUNT, account(AgedIndustryConfig.SUPPLIER, "0.00"));
        accounts.put(AgedIndustryConfig.CONVERSION_ACCOUNT, account(AgedIndustryConfig.CONVERSION, "0.00"));
        accounts.put(AgedIndustryConfig.FIXED_ACCOUNT, account(AgedIndustryConfig.FIXED, "0.00"));
        accounts.put(AgedIndustryConfig.TERMINAL_ACCOUNT, account(AgedIndustryConfig.TERMINAL, "0.00"));
        accounts.put(AgedIndustryConfig.CHANNEL_ACCOUNT, account(AgedIndustryConfig.CHANNEL, "0.00"));
        return Map.copyOf(accounts);
    }

    private static AgedCompanyRun open(Map<String, CashLedger.CashAccount> accounts) {
        FundState funds = funds(accounts);
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(
                AgedIndustryConfig.modelConfig(), funds.accounts(), java.util.List.of());
        assertTrue(opened.accepted(), opened.reason());
        return AgedCompanyRun.open(AgedIndustryConfig.runConfig(), funds);
    }

    /** 一次命令执行器：接受才提交候选。 */
    private static final class Step {
        private AgedCompanyRun run;
        private FundState funds;

        private Step(AgedCompanyRun run, FundState funds) {
            this.run = run;
            this.funds = funds;
        }

        private AgedOperations.Outcome apply(AgedOperations.Action action, Map<String, String> args,
                                             FundPayer payer, long now, String commandId) {
            AgedOperations.Outcome outcome = AgedOperations.apply(run, funds, payer, now, commandId,
                    new AgedOperations.Request(action, args));
            if (outcome.accepted()) {
                run = run.withLedger(outcome.state());
                funds = outcome.funds();
            }
            return outcome;
        }

        private BigDecimal at(String accountId) {
            return funds.cash(accountId);
        }
    }

    @Test
    void closePeriodPaysOnlyAvailableCashAndCarriesTheRestAsPayable() {
        Map<String, CashLedger.CashAccount> accounts = thin("50.00");
        AgedCompanyRun run = open(accounts);
        Step step = new Step(run, funds(accounts));

        AgedOperations.Outcome c0 = step.apply(AgedOperations.Action.CLOSE_PERIOD, Map.of(),
                payer(AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.FIXED_ACCOUNT), P, "c0");
        assertTrue(c0.accepted(), c0.reason());
        assertEquals(money("100.00"), c0.state().fixedAccrued());
        assertEquals(money("50.00"), c0.state().fixedPaid());
        assertEquals(money("50.00"), c0.state().fixedPayable());
        assertEquals(money("0.00"), step.at(AgedIndustryConfig.COMPANY_ACCOUNT));
        assertEquals(money("50.00"), step.at(AgedIndustryConfig.FIXED_ACCOUNT));
        assertNull(AgedInventoryModel.validate(c0.state(), c0.funds().accounts()));

        AgedOperations.Outcome c1 = step.apply(AgedOperations.Action.CLOSE_PERIOD, Map.of(),
                payer(AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.FIXED_ACCOUNT), 2 * P, "c1");
        assertTrue(c1.accepted(), c1.reason());
        assertEquals(money("200.00"), c1.state().fixedAccrued());
        assertEquals(money("50.00"), c1.state().fixedPaid());
        assertEquals(money("150.00"), c1.state().fixedPayable());
        assertNull(AgedInventoryModel.validate(c1.state(), c1.funds().accounts()));

        // 没有现金时 PAY_FIXED 整体拒绝：现金、应付、台账都不变。
        AgedInventoryModel.State before = step.run.ledger();
        FundState fundsBefore = step.funds;
        AgedOperations.Outcome pay = step.apply(AgedOperations.Action.PAY_FIXED,
                Map.of("amountCents", "1000"),
                payer(AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.FIXED_ACCOUNT), 2 * P,
                "pay-fixed");
        assertFalse(pay.accepted());
        assertEquals(AgedInventoryModel.CASH_SHORT, pay.reason());
        assertEquals(before, pay.state());
        assertEquals(fundsBefore, pay.funds());
    }

    @Test
    void producedCapacityIsCumulativePerPeriodAndWholeRequestIsRejected() {
        Map<String, CashLedger.CashAccount> accounts = thin("5000.00");
        Step step = new Step(open(accounts), funds(accounts));
        FundPayer coToSupplier = payer(AgedIndustryConfig.COMPANY_ACCOUNT,
                AgedIndustryConfig.SUPPLIER_ACCOUNT);
        FundPayer coToConversion = payer(AgedIndustryConfig.COMPANY_ACCOUNT,
                AgedIndustryConfig.CONVERSION_ACCOUNT);

        // 期 0 下单 100，期 1 到货（到货期才付款）。
        assertTrue(step.apply(AgedOperations.Action.ORDER_RAW,
                Map.of("orderId", "raw-100", "units", "100"), coToSupplier, 0L, "order").accepted());
        assertTrue(step.apply(AgedOperations.Action.CLOSE_PERIOD, Map.of(),
                payer(AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.FIXED_ACCOUNT), P, "c0")
                .accepted());
        assertTrue(step.apply(AgedOperations.Action.RECEIVE_RAW, Map.of("orderId", "raw-100"),
                coToSupplier, P, "receive").accepted());
        assertEquals(100L, step.run.ledger().rawLots().get(0).remaining());

        assertTrue(step.apply(AgedOperations.Action.PRODUCE,
                Map.of("batchId", "b1", "units", "60"), coToConversion, P, "p1").accepted());
        assertEquals(40L, step.run.ledger().rawLots().get(0).remaining());
        AgedInventoryModel.State before = step.run.ledger();
        FundState fundsBefore = step.funds;
        AgedOperations.Outcome over = step.apply(AgedOperations.Action.PRODUCE,
                Map.of("batchId", "b2", "units", "50"), coToConversion, P, "p2");
        assertFalse(over.accepted());
        assertEquals(AgedInventoryModel.CAPACITY_EXCEEDED, over.reason());
        // 整笔拒绝：原料不被消耗，也不新建批次。
        assertEquals(before, over.state());
        assertEquals(fundsBefore, over.funds());
        assertEquals(1, step.run.ledger().batches().size());
        assertEquals(40L, step.run.ledger().rawLots().get(0).remaining());
    }

    @Test
    void expiredConsignmentMovesUnearnedPrepaidToRefundPayableAndRefundsOnlyRealCash() {
        Map<String, CashLedger.CashAccount> accounts = thin("20000.00");
        // 渠道与终端要有真实结算资金。
        Map<String, CashLedger.CashAccount> rich = new LinkedHashMap<>(accounts);
        rich.put(AgedIndustryConfig.CHANNEL_ACCOUNT, account(AgedIndustryConfig.CHANNEL, "20000.00"));
        rich.put(AgedIndustryConfig.TERMINAL_ACCOUNT, account(AgedIndustryConfig.TERMINAL, "20000.00"));
        rich = Map.copyOf(rich);
        Step step = new Step(open(rich), funds(rich));
        FundPayer coToSupplier = payer(AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.SUPPLIER_ACCOUNT);
        FundPayer coToConversion = payer(AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.CONVERSION_ACCOUNT);
        FundPayer channelToCo = payer(AgedIndustryConfig.CHANNEL_ACCOUNT, AgedIndustryConfig.COMPANY_ACCOUNT);
        FundPayer coToChannel = payer(AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.CHANNEL_ACCOUNT);
        FundPayer coToFixed = payer(AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.FIXED_ACCOUNT);

        // 期 0 下单 10 → 期 1 到货 → 期 1 投产（成熟期 = 3）。
        assertTrue(step.apply(AgedOperations.Action.ORDER_RAW,
                Map.of("orderId", "raw-10", "units", "10"), coToSupplier, 0L, "order").accepted());
        close(step, coToFixed, P, "c0");
        assertTrue(step.apply(AgedOperations.Action.RECEIVE_RAW, Map.of("orderId", "raw-10"),
                coToSupplier, P, "receive").accepted());
        assertTrue(step.apply(AgedOperations.Action.PRODUCE,
                Map.of("batchId", "b1", "units", "10"), coToConversion, P, "produce").accepted());
        close(step, coToFixed, 2 * P, "c1");
        close(step, coToFixed, 3 * P, "c2");

        BigDecimal companyBeforeShip = step.at(AgedIndustryConfig.COMPANY_ACCOUNT);
        // 期 3 发货：寄售价 40.00、预收 20% = 80.00，真实从渠道收到。
        assertTrue(step.apply(AgedOperations.Action.SHIP,
                Map.of("shipmentId", "s1", "channelId", AgedIndustryConfig.CHANNEL, "units", "10"),
                channelToCo, 3 * P, "ship").accepted());
        assertEquals(0, companyBeforeShip.add(money("80.00"))
                .compareTo(step.at(AgedIndustryConfig.COMPANY_ACCOUNT)));
        AgedInventoryModel.ChannelLedger ledger = step.run.ledger().channelLedgers().get(0);
        assertEquals(money("80.00"), ledger.contractLiability());

        // 货架期届满（成熟期 3 + 货架 3 = 6）：期 6 关期全额过期，未抵预收转退款应付。
        close(step, coToFixed, 4 * P, "c3");
        close(step, coToFixed, 5 * P, "c4");
        close(step, coToFixed, 6 * P, "c5");
        close(step, coToFixed, 7 * P, "c6");
        assertEquals(0L, step.run.ledger().shipmentLots().get(0).remaining());
        ledger = step.run.ledger().channelLedgers().get(0);
        assertEquals(money("80.00"), ledger.refundPayable());
        assertEquals(money("0.00"), ledger.contractLiability());
        assertEquals(money("0.00"), ledger.refundPaid(), "退款尚未支付");
        assertNull(AgedInventoryModel.validate(step.run.ledger(), step.funds.accounts()));

        // 部分退款：只付真实现金，余下保留应付。
        assertTrue(step.apply(AgedOperations.Action.PAY_REFUND,
                Map.of("channelId", AgedIndustryConfig.CHANNEL, "amountCents", "3000"),
                coToChannel, 7 * P, "refund-30").accepted());
        ledger = step.run.ledger().channelLedgers().get(0);
        assertEquals(money("50.00"), ledger.refundPayable());
        assertEquals(money("30.00"), ledger.refundPaid());

        // 请求超过剩余义务：被夹到剩余 50.00，不会多付。
        assertTrue(step.apply(AgedOperations.Action.PAY_REFUND,
                Map.of("channelId", AgedIndustryConfig.CHANNEL, "amountCents", "10000"),
                coToChannel, 7 * P, "refund-clamp").accepted());
        ledger = step.run.ledger().channelLedgers().get(0);
        assertEquals(money("0.00"), ledger.refundPayable());
        assertEquals(money("80.00"), ledger.refundPaid());
        AgedOperations.Outcome again = step.apply(AgedOperations.Action.PAY_REFUND,
                Map.of("channelId", AgedIndustryConfig.CHANNEL, "amountCents", "1"),
                coToChannel, 7 * P, "refund-empty");
        assertFalse(again.accepted());
        assertEquals(AgedInventoryModel.NOTHING_TO_REFUND, again.reason());
        assertNull(AgedInventoryModel.validate(step.run.ledger(), step.funds.accounts()));
    }

    @Test
    void collectWithoutReceivableIsRejectedAndOrderCannotBeReceivedEarly() {
        Map<String, CashLedger.CashAccount> base = new LinkedHashMap<>(thin("20000.00"));
        base.put(AgedIndustryConfig.CHANNEL_ACCOUNT, account(AgedIndustryConfig.CHANNEL, "100.00"));
        Map<String, CashLedger.CashAccount> accounts = Map.copyOf(base);
        Step step = new Step(open(accounts), funds(accounts));
        FundPayer coToSupplier = payer(AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.SUPPLIER_ACCOUNT);

        AgedOperations.Outcome collect = step.apply(AgedOperations.Action.COLLECT,
                Map.of("channelId", AgedIndustryConfig.CHANNEL, "amountCents", "100"),
                payer(AgedIndustryConfig.CHANNEL_ACCOUNT, AgedIndustryConfig.COMPANY_ACCOUNT), 0L, "collect");
        assertFalse(collect.accepted());
        assertEquals(AgedInventoryModel.NOTHING_TO_COLLECT, collect.reason());

        assertTrue(step.apply(AgedOperations.Action.ORDER_RAW,
                Map.of("orderId", "raw-early", "units", "10"), coToSupplier, 0L, "order").accepted());
        AgedOperations.Outcome early = step.apply(AgedOperations.Action.RECEIVE_RAW,
                Map.of("orderId", "raw-early"), coToSupplier, 10L, "receive-early");
        assertFalse(early.accepted());
        assertEquals(AgedInventoryModel.PERIOD_NOT_ENDED, early.reason());
        assertEquals(1, step.run.ledger().orders().size());
    }

    private static void close(Step step, FundPayer payer, long now, String id) {
        AgedOperations.Outcome outcome = step.apply(AgedOperations.Action.CLOSE_PERIOD, Map.of(),
                payer, now, id);
        assertTrue(outcome.accepted(), outcome.reason());
        assertNull(AgedInventoryModel.validate(outcome.state(), outcome.funds().accounts()),
                "关期后不变量必须成立");
    }
}
