package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.SecuritiesLedger;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankContext;
import edu.cufe.auction.world.bank.CashFreeze;
import edu.cufe.auction.world.bank.CustodyBook;
import edu.cufe.auction.world.bank.CustodyPort;
import edu.cufe.auction.world.bank.BankPayment;
import edu.cufe.auction.world.bank.Fund;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B7b 托管出金冻结探针：托管受益权出金（{@link CustodyPort#pay} 与
 * {@link BankActions} 的 B7b 付款路径）必须守与订单成交同一份活动冻结。
 *
 * <p>冻结快照取自真实 {@link WorldExchange#freezeSnapshot}（不是手工构造的空表），
 * 因此验证的是运行层真实派生量：有活动买单时出金被拒，撤单释放后才能出金。</p>
 */
public final class BankCustodyPortFreezeProbe {

    private static final long T = BankCustodyFixture.T0;
    private static final String CST = BankCustodyFixture.CST;
    private static int pass;
    private static int fail;

    private BankCustodyPortFreezeProbe() {
    }

    /**
     * 入口。
     *
     * @param args 忽略
     */
    public static void main(String[] args) {
        try {
            run();
        } catch (RuntimeException failure) {
            failure.printStackTrace();
            fail++;
        }
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void run() {
        BankCustodyFixture.Core core = BankCustodyFixture.core("1200.00", "1200.00", "0.00");
        Map<String, CashLedger.CashAccount> accounts = new LinkedHashMap<>(core.accounts());
        CustodyBook book = core.book();
        BankContext ctx = new BankContext(BankCustodyFixture.RUN_ID,
                core.config().fingerprint(), 1L, T);
        SecuritiesLedger.State securities =
                SecuritiesLedger.opening(BankCustodyFixture.registry());
        WorldExchange.FundOutcome order = WorldExchange.submit(WorldExchange.opening(), accounts,
                securities, new WorldExchange.FundContext(core.bank(), core.config(), ctx), book,
                "t-freeze", "c-freeze", "acme-owner", "acme-cash", BankCustodyFixture.SYMBOL,
                Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 30L,
                Fund.parse(BankCustodyFixture.custodyToken(CST, "acme")), T + 1000L);
        check("活动买单被接受（30 股 @10 = 冻结 300）", order.accepted(), order.reason());
        CashFreeze frozen = WorldExchange.freezeSnapshot(order.exchange());
        check("真实冻结快照含 300",
                frozen.frozenOf(Fund.parse(BankCustodyFixture.custodyToken(CST, "acme")))
                        .compareTo(BankCustodyFixture.money("300.00")) == 0,
                String.valueOf(frozen.frozenByBucket()));

        // 1) 托管出金 400：可用 600 − 300 = 300 → 拒。
        CustodyPort.Result over = CustodyPort.pay(order.bank(), order.accounts(), order.custody(),
                core.config(), ctx, new BankPayment("p-over", "acme",
                        Fund.parse(BankCustodyFixture.custodyToken(CST, "acme")),
                        Fund.parse(BankCustodyFixture.token(null, "acme-cash")),
                        BankCustodyFixture.money("400.00")), frozen);
        check("冻结 300 时托管出金 400 被拒（可用 300）", !over.accepted(), over.reason());
        check("拒绝码 PAYER_BALANCE_INSUFFICIENT",
                over.reason().startsWith("PAYER_BALANCE_INSUFFICIENT"), over.reason());
        check("拒绝不改明细", over.book().detailOf(CST, "acme")
                .compareTo(BankCustodyFixture.money("600.00")) == 0,
                over.book().detailOf(CST, "acme").toPlainString());

        // 2) 出金 200 ≤ 300 → 成；明细 400、池 800、现金 1200。
        CustodyPort.Result ok = CustodyPort.pay(order.bank(), order.accounts(), order.custody(),
                core.config(), ctx, new BankPayment("p-ok", "acme",
                        Fund.parse(BankCustodyFixture.custodyToken(CST, "acme")),
                        Fund.parse(BankCustodyFixture.token(null, "acme-cash")),
                        BankCustodyFixture.money("200.00")), frozen);
        check("冻结 300 时托管出金 200 成功", ok.accepted(), ok.reason());
        check("出金后明细 600→400", ok.book().detailOf(CST, "acme")
                .compareTo(BankCustodyFixture.money("400.00")) == 0,
                ok.book().detailOf(CST, "acme").toPlainString());
        check("出金后池 1000→800", ok.bank().depositOf("north", CST)
                .compareTo(BankCustodyFixture.money("800.00")) == 0,
                ok.bank().depositOf("north", CST).toPlainString());
        check("出金后 acme 现金 1000→1200", ok.accounts().get("acme-cash").cash()
                .compareTo(BankCustodyFixture.money("1200.00")) == 0,
                ok.accounts().get("acme-cash").cash().toPlainString());

        // 3) 撤单释放后再出金 400（= 剩余明细）→ 成。
        WorldExchange.Outcome cancelled = WorldExchange.cancel(order.exchange(), order.accounts(),
                order.securities(), "acme-owner", order.orderId(), T + 2000L);
        check("撤单被接受", cancelled.accepted(), cancelled.reason());
        CashFreeze released = WorldExchange.freezeSnapshot(cancelled.exchange());
        CustodyPort.Result after = CustodyPort.pay(ok.bank(), ok.accounts(), ok.book(),
                core.config(), ctx, new BankPayment("p-after", "acme",
                        Fund.parse(BankCustodyFixture.custodyToken(CST, "acme")),
                        Fund.parse(BankCustodyFixture.token(null, "acme-cash")),
                        BankCustodyFixture.money("400.00")), released);
        check("撤单后可出金 400", after.accepted(), after.reason());
        check("全部出金后明细为 0", after.book().detailOf(CST, "acme").signum() == 0,
                after.book().detailOf(CST, "acme").toPlainString());
        check("池 800→400", after.bank().depositOf("north", CST)
                .compareTo(BankCustodyFixture.money("400.00")) == 0,
                after.bank().depositOf("north", CST).toPlainString());

        // 4) 银行支付路径：B7a 档（custodyTrading=false）仍拒绝；B7b 档走同一出口。
        BankActions.Request request = new BankActions.Request("pay-c", BankActions.Kind.PAY,
                "north", "acme", BankCustodyFixture.custodyToken(CST, "acme"),
                BankCustodyFixture.token(null, "acme-cash"), BankCustodyFixture.money("100.00"),
                0L, 0L, "", "", 0L);
        BankActions.Outcome oldProfile = BankActions.apply(core.bank(), accounts, book,
                BankActions.Book.empty(), core.config(), ctx, request,
                BankActions.Actor.customer("acme", "acme-owner"), CashFreeze.none(), false);
        check("B7a 档托管付款仍被拒", !oldProfile.accepted(), oldProfile.reason());
        check("拒绝码 FUND_CUSTODY_UNSUPPORTED",
                oldProfile.reason().startsWith("FUND_CUSTODY_UNSUPPORTED"), oldProfile.reason());
        BankActions.Outcome newProfile = BankActions.apply(core.bank(), accounts, book,
                BankActions.Book.empty(), core.config(), ctx, request,
                BankActions.Actor.customer("acme", "acme-owner"), CashFreeze.none(), true);
        check("B7b 档托管付款成功", newProfile.accepted(), newProfile.reason());
        check("B7b 档付款后明细 500", newProfile.book().detailOf(CST, "acme")
                .compareTo(BankCustodyFixture.money("500.00")) == 0,
                newProfile.book().detailOf(CST, "acme").toPlainString());
        BigDecimal cash = newProfile.accounts().get("acme-cash").cash();
        check("B7b 档付款后现金 1100", cash.compareTo(BankCustodyFixture.money("1100.00")) == 0,
                cash.toPlainString());
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " :: " + detail);
        }
    }
}
