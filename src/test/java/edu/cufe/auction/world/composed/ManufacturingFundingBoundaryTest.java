package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.ManufacturingFunding;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.WorldExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 制造五段资金腿的确定性边界：活动订单冻结、错误主体、任职撤权交集。
 *
 * <p>三条都走真实入口与真实提交，不注入状态：冻结由真实世界买单产生，主体错误由真实命令
 * 里的桶 token 产生，撤权由真实任职撤销任务产生。</p>
 */
class ManufacturingFundingBoundaryTest {

    private static final String POLICY = ManufacturingFunding.POLICY_FROZEN_MAPPING_V1;
    private static final long DAY = 86_400_000L;

    @TempDir
    Path tmp;

    /**
     * 客户现金被活动买单冻结：到期回款必须只标 {@code OVERDUE} 且不动钱；
     * 撤单释放冻结后同一应收可真实收回。
     */
    @Test
    void frozenCustomerCashOnlyMarksOverdueAndCollectsAfterRelease() throws Exception {
        Path log = tmp.resolve("freeze").resolve("world.log");
        Files.createDirectories(log.getParent());
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(log)) {
            ManufacturingFundingChainTest.budgetMeeting(runtime, edu.cufe.auction.web.WorldWebScenario.COMPANY,
                    "fz", "10000.00", "10000.00");
            // 先把公司现金变成可交付的成品与应收：采购 → 到货 → 投产 → 完工 → 签约 → 交付。
            long orderDue = 10 * DAY;
            runtime.scheduleFundedManufacturing("fz-po", "fz-po-t", orderDue,
                    ManufacturingFundingWorld.MANAGER, "PURCHASE_ORDER", "PO-F", "", 10L, null, 0L,
                    -1L, POLICY, "CASH|CO", "CASH|SUP", 1L);
            runtime.advanceTo(orderDue);
            runtime.advanceTo(orderDue + DAY);
            long prodDue = orderDue + DAY + 100;
            runtime.scheduleFundedManufacturing("fz-prod", "fz-prod-t", prodDue,
                    ManufacturingFundingWorld.MANAGER, "PRODUCTION", "B-F", "", 10L, null, 0L,
                    -1L, POLICY, "CASH|CO", "CASH|OPS", 1L);
            runtime.advanceTo(prodDue);
            runtime.advanceTo(prodDue + DAY);
            long signDue = prodDue + DAY + 100;
            long promised = 20 * DAY;
            runtime.scheduleFundedManufacturing("fz-sign", "fz-sign-t", signDue,
                    ManufacturingFundingWorld.MANAGER, "SIGN", "S-F", "", 10L,
                    new BigDecimal("130.00"), promised, -1L, POLICY, "", "", 1L);
            runtime.advanceTo(signDue);
            runtime.scheduleFundedManufacturing("fz-dlv", "fz-dlv-t", promised,
                    ManufacturingFundingWorld.MANAGER, "DELIVERY", "S-F", "R-F", 10L, null, 0L,
                    -1L, POLICY, "CASH|CUS", "CASH|CO", 1L);
            runtime.advanceTo(promised);
            assertEquals("DELIVERED", ManufacturingFundingChainTest.reason(runtime,
                    "fz-dlv-t:finish"), "交付应成功");

            // 客户现金被真实买单冻结 1300：可用额只剩 700，到期回款必须只标 OVERDUE。
            long collectDue = promised + ManufacturingModel.RECEIVABLE_TERM_MILLIS;
            long orderAt = collectDue - 100;
            runtime.scheduleOrder("fz-buy", "fz-buy-t", orderAt, "customer", "CUS",
                    edu.cufe.auction.web.WorldWebScenario.SYMBOL, edu.cufe.auction.model.Side.BUY,
                    edu.cufe.auction.model.OrderType.LIMIT, new BigDecimal("1.00"), 1300L,
                    "CASH|CUS");
            runtime.advanceTo(orderAt);
            BigDecimal frozenCus = runtime.snapshot().accounts().get("CUS").cash();
            BigDecimal beforeCo = runtime.snapshot().accounts().get("CO").cash();
            runtime.advanceTo(collectDue);
            assertEquals("OVERDUE", ManufacturingFundingChainTest.reason(runtime,
                    "mfg-collect:R-F"), "冻结后到期回款只能标 OVERDUE");
            assertEquals(ManufacturingModel.ReceivableStatus.OVERDUE,
                    runtime.snapshot().manufacturing().orElseThrow().ledger().receivables()
                            .get("R-F").status(), "应收状态必须是 OVERDUE");
            assertEquals(frozenCus, runtime.snapshot().accounts().get("CUS").cash(),
                    "冻结不得被回款花掉");
            assertEquals(beforeCo, runtime.snapshot().accounts().get("CO").cash(),
                    "逾期回款不得给公司记收入");

            // 撤单释放冻结后，同一应收用新的显式资金动作真实收回。
            long cancelAt = collectDue + 100;
            runtime.scheduleCancelOrder("fz-cancel", "fz-cancel-t", cancelAt, "customer", 1L);
            runtime.advanceTo(cancelAt);
            long retryAt = cancelAt + 100;
            runtime.scheduleFundedManufacturing("fz-collect", "fz-collect-t", retryAt,
                    ManufacturingFundingWorld.MANAGER, "COLLECT", "R-F", "", 0L, null, 0L, -1L,
                    POLICY, "CASH|CUS", "CASH|CO", 1L);
            runtime.advanceTo(retryAt);
            assertEquals("PAYMENT_RECEIVED", ManufacturingFundingChainTest.reason(runtime,
                    "fz-collect-t:finish"), "释放冻结后回款必须成功");
            assertEquals(ManufacturingModel.ReceivableStatus.COLLECTED,
                    runtime.snapshot().manufacturing().orElseThrow().ledger().receivables()
                            .get("R-F").status(), "回款后应收收清");
        }
    }

    /**
     * 错误主体：资金桶 token 指向别的账户/别的对手方时必须整笔拒绝，不动任何钱。
     */
    @Test
    void wrongPartyOrWrongCounterpartyBucketIsRejectedWithoutMovingMoney() throws Exception {
        Path log = tmp.resolve("party").resolve("world.log");
        Files.createDirectories(log.getParent());
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(log)) {
            ManufacturingFundingChainTest.budgetMeeting(runtime, edu.cufe.auction.web.WorldWebScenario.COMPANY,
                    "wp", "10000.00", "10000.00");
            BigDecimal co = runtime.snapshot().accounts().get("CO").cash();
            BigDecimal sup = runtime.snapshot().accounts().get("SUP").cash();
            // 公司出款桶写成客户账户：必须被账户/主体映射拒绝。
            long due = DAY + 100;
            runtime.scheduleFundedManufacturing("wp-po", "wp-po-t", due,
                    ManufacturingFundingWorld.MANAGER, "PURCHASE_ORDER", "PO-WP", "", 10L, null, 0L,
                    -1L, POLICY, "CASH|CUS", "CASH|SUP", 1L);
            runtime.advanceTo(due);
            String reason = ManufacturingFundingChainTest.reason(runtime, "wp-po-t:finish");
            assertNotNull(reason, "错误主体必须有拒绝结果");
            assertTrue(reason.contains("MISMATCH") || reason.contains("UNAVAILABLE"),
                    "错误主体必须由资金路由拒绝：" + reason);
            assertEquals(co, runtime.snapshot().accounts().get("CO").cash(), "拒绝不得动公司现金");
            assertEquals(sup, runtime.snapshot().accounts().get("SUP").cash(), "拒绝不得动对手方");

            // 收款桶写成人力账户：对手方固定账户校验必须拒绝。
            long due2 = due + 100;
            runtime.scheduleFundedManufacturing("wp-po2", "wp-po2-t", due2,
                    ManufacturingFundingWorld.MANAGER, "PURCHASE_ORDER", "PO-WP2", "", 10L, null, 0L,
                    -1L, POLICY, "CASH|CO", "CASH|OPS", 1L);
            runtime.advanceTo(due2);
            String reason2 = ManufacturingFundingChainTest.reason(runtime, "wp-po2-t:finish");
            assertTrue(reason2.contains("PAYEE_MISMATCH") || reason2.contains("NOT_AUTHORIZED"),
                    "错误对手方桶必须被拒绝：" + reason2);
            assertEquals(co, runtime.snapshot().accounts().get("CO").cash(), "拒绝不得动公司现金");
        }
    }

    /**
     * 任职撤权交集：观察时点与 BEGIN 时点仍是经理，完成时点已撤权 → 完成帧必须拒绝且不动钱。
     */
    @Test
    void revokedAppointmentBetweenBeginAndFinishRejectsTheMoneyLeg() throws Exception {
        Path log = tmp.resolve("revoke").resolve("world.log");
        Files.createDirectories(log.getParent());
        try (WorldRuntime runtime = ManufacturingFundingWorld.open(log)) {
            ManufacturingFundingChainTest.budgetMeeting(runtime, edu.cufe.auction.web.WorldWebScenario.COMPANY,
                    "rv", "10000.00", "10000.00");
            long orderDue = 10 * DAY;
            runtime.scheduleFundedManufacturing("rv-po", "rv-po-t", orderDue,
                    ManufacturingFundingWorld.MANAGER, "PURCHASE_ORDER", "PO-RV", "", 10L, null, 0L,
                    -1L, POLICY, "CASH|CO", "CASH|SUP", 1L);
            runtime.advanceTo(orderDue);
            runtime.advanceTo(orderDue + DAY);
            BigDecimal co = runtime.snapshot().accounts().get("CO").cash();
            BigDecimal ops = runtime.snapshot().accounts().get("OPS").cash();
            long finish = orderDue + DAY + 2000;
            // 正工时 100：BEGIN 在 finish-100 复核任职，完成帧在 finish 再查一次。
            runtime.scheduleFundedManufacturing("rv-prod", "rv-prod-t", finish,
                    ManufacturingFundingWorld.MANAGER, "PRODUCTION", "B-RV", "", 10L, null, 0L,
                    -1L, POLICY, "CASH|CO", "CASH|OPS", 100L);
            long revokeAt = finish - 50;
            runtime.scheduleRevokeAppointment("rv-revoke", "rv-revoke-t", revokeAt,
                    ManufacturingFundingWorld.MANAGER_APPOINTMENT);
            runtime.advanceTo(finish);
            assertEquals("NOT_AUTHORIZED", ManufacturingFundingChainTest.reason(runtime,
                    "rv-prod-t:finish"), "撤权后完成帧必须拒绝");
            assertEquals(co, runtime.snapshot().accounts().get("CO").cash(), "拒绝不得动公司现金");
            assertEquals(ops, runtime.snapshot().accounts().get("OPS").cash(), "拒绝不得付人工");
            assertTrue(!runtime.snapshot().manufacturing().orElseThrow().ledger()
                    .productionBatches().containsKey("B-RV"), "拒绝不得留下在制批次");
        }
    }

}
