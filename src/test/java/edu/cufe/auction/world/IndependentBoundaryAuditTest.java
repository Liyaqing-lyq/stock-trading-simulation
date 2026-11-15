package edu.cufe.auction.world;

import edu.cufe.auction.world.IntegratedResourcesModel.JobKind;
import edu.cufe.auction.world.IntegratedResourcesModel.JobStatus;
import edu.cufe.auction.world.IntegratedResourcesModel.Outcome;
import edu.cufe.auction.world.IntegratedResourcesModel.Seam;
import edu.cufe.auction.world.IntegratedResourcesModel.State;
import edu.cufe.auction.world.bank.FundState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static edu.cufe.auction.world.PureCompanyTestSupport.account;
import static edu.cufe.auction.world.PureCompanyTestSupport.funds;
import static edu.cufe.auction.world.PureCompanyTestSupport.money;
import static edu.cufe.auction.world.PureCompanyTestSupport.payer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 独立审查：状态构造合法性与公共 API 的 actor / owner / 审批边界。
 *
 * <p>只证明两件事：非法构造在构造期就被拒（不会带着坏参数进入账）；合法构造下，
 * 错主体、错资金腿、错授权种类、未到期/未获批的调用都返回稳定结果码且不改状态。</p>
 */
class IndependentBoundaryAuditTest {

    private static final long DAY = IntegratedResourcesModel.DAY_MILLIS;
    private static final String CO = "company";

    private static Map<String, CashLedger.CashAccount> accounts() {
        Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
        map.put("CO", account(CO, "50000.00"));
        map.put("OPS", account("ops", "0.00"));
        map.put("CUS", account("customer", "20000.00"));
        map.put("GRID", account("grid", "20000.00"));
        map.put("CONV", account("converter", "0.00"));
        map.put("CON", account("contractor", "0.00"));
        map.put("BUY", account("buyer", "3000.00"));
        map.put("SPON", account("sponsor", "1000.00"));
        return map;
    }

    private static IntegratedResourcesModel.Config resConfig(Map<String, IntegratedResourcesModel.Approval> approvals) {
        return new IntegratedResourcesModel.Config(CO, "CO", "OPS", "CUS", "CUS", "GRID", "GRID",
                "CONV", "CONV", "CON", "CON", "BUY", "BUY", "SPON", "SPON", money("20000.00"),
                new BigDecimal("0.06"), new BigDecimal("0.10"), money("80.00"), 400L, money("5.00"),
                400_000L, 200L, 500_000L, 200_000L, money("100000.00"), 100L, money("20000.00"),
                money("1000.00"), money("500.00"), new BigDecimal("0.25"),
                List.of(new Seam("SEAM-A", 2_000_000L, new BigDecimal("0.03"))), approvals);
    }

    private static final Map<String, IntegratedResourcesModel.Approval> RES_APPROVALS = Map.of(
            "AP-CAPEX", new IntegratedResourcesModel.Approval("AP-CAPEX",
                    IntegratedResourcesModel.APPROVAL_CAPEX, 0L),
            "AP-EXIT", new IntegratedResourcesModel.Approval("AP-EXIT",
                    IntegratedResourcesModel.APPROVAL_EXIT, 0L),
            "AP-FUTURE", new IntegratedResourcesModel.Approval("AP-FUTURE",
                    IntegratedResourcesModel.APPROVAL_CAPEX, 100L * DAY),
            "AP-COMM", new IntegratedResourcesModel.Approval("AP-COMM",
                    IntegratedResourcesModel.APPROVAL_COMMERCIAL, 0L));

    // ------------------------------------------------- 1 构造合法性

    @Test
    void illegalConfigsAndRecordsAreRejectedAtConstruction() {
        // 账户角色共用、负储量、负批准时点、非法种类都必须当场拒绝。
        assertThrows(IllegalArgumentException.class, () -> new IntegratedResourcesModel.Config(CO,
                "CO", "CO", "CUS", "CUS", "GRID", "GRID", "CONV", "CONV", "CON", "CON", "BUY",
                "BUY", "SPON", "SPON", money("20000.00"), new BigDecimal("0.06"),
                new BigDecimal("0.10"), money("80.00"), 400L, money("5.00"), 400_000L, 200L,
                500_000L, 200_000L, money("100000.00"), 100L, money("20000.00"), money("1000.00"),
                money("500.00"), new BigDecimal("0.25"),
                List.of(new Seam("SEAM-A", 1000L, new BigDecimal("0.03"))), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Seam("SEAM-A", -1L, new BigDecimal("0.03")));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegratedResourcesModel.Approval("AP", IntegratedResourcesModel.APPROVAL_CAPEX, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegratedResourcesModel.Approval("AP", "UNKNOWN_KIND", 0L));

        // 作业必须正耗时：due == began 在类型层就被拒。
        assertThrows(IllegalArgumentException.class, () -> new IntegratedResourcesModel.Job("J1",
                JobKind.EXTRACT, "SEAM-A", 10L, 0L, 0L, money("0.00"), money("0.30"),
                "", "", 100L, 100L, 0L, -1L, money("0.00"), JobStatus.RUNNING, -1L));
        // 应收/账单不得超额。
        assertThrows(IllegalArgumentException.class, () -> new IntegratedResourcesModel.Receivable(
                "AR", "CUS", "COAL_KG", 10L, money("10.00"), money("5.00"), 0L, 100L,
                money("11.00"), IntegratedResourcesModel.ReceivableStatus.OPEN));
        assertThrows(IllegalArgumentException.class, () -> new IntegratedResourcesModel.ExpenseBill(
                0L, money("10.00"), money("11.00")));
        // 数字：已冲减预收 + 已退款不得超过预收。
        assertThrows(IllegalArgumentException.class, () -> new DigitalServiceModel.Contract("C1",
                "CUS", 10L, 0L, new BigDecimal("1.0000"), money("10.00"), money("8.00"),
                money("3.00"), 0L, 0L, DigitalServiceModel.ContractStatus.ACTIVE, -1L));
        // 数字：阶段必须正耗时。
        assertThrows(IllegalArgumentException.class, () -> new DigitalServiceModel.StageRecord(
                DigitalServiceModel.Stage.PILOT, money("10.00"), false, 100L, 100L,
                DigitalServiceModel.StageStatus.RUNNING, ""));
    }

    // ------------------------------------------------- 2 资源：actor / owner / 审批

    @Test
    void resourceActorOwnerAndApprovalBoundaries() {
        FundState funds = funds(accounts());
        State state = IntegratedResourcesModel.open(resConfig(RES_APPROVALS), funds);
        PureCompanyTestSupport.Payer coToOps = payer("CO", "OPS");

        // 错主体：非公司主体发起一律 NOT_AUTHORIZED。
        assertEquals("NOT_AUTHORIZED", IntegratedResourcesModel.beginExtraction(state, 0L,
                "intruder", "c1", "E1", "SEAM-A", 1000L, funds, coToOps).reason());
        assertEquals("NOT_AUTHORIZED", IntegratedResourcesModel.closePeriod(state, 0L,
                "intruder", "c2", 0L, funds, coToOps).reason());
        assertEquals("NOT_AUTHORIZED", IntegratedResourcesModel.exitBusiness(state, 0L,
                "intruder", "c3", "AP-EXIT", funds).reason());

        // 资金腿主体与开局冻结映射不符：拒绝且不动钱。
        assertEquals("FUND_OWNER_MISMATCH", IntegratedResourcesModel.beginExtraction(state, 0L, CO,
                "c4", "E1", "SEAM-A", 1000L, funds, payer("GRID", "OPS")).reason());
        assertEquals(money("50000.00"), funds.cash("CO"));

        // 授权种类错 / 未到时点：拒绝。
        assertEquals("NO_APPROVAL", IntegratedResourcesModel.startExpansion(state, 0L, CO, "c5",
                "X1", money("1000.00"), 10L * DAY, 2, 1000L, "", funds, payer("CO", "CON"))
                .reason());
        assertEquals("APPROVAL_INVALID", IntegratedResourcesModel.startExpansion(state, 0L, CO,
                "c6", "X1", money("1000.00"), 10L * DAY, 2, 1000L, "AP-EXIT", funds,
                payer("CO", "CON")).reason());
        assertEquals("APPROVAL_INVALID", IntegratedResourcesModel.startExpansion(state, 0L, CO,
                "c7", "X1", money("1000.00"), 10L * DAY, 2, 1000L, "AP-FUTURE", funds,
                payer("CO", "CON")).reason());
        assertEquals("APPROVAL_INVALID", IntegratedResourcesModel.exitBusiness(state, 0L, CO,
                "c8", "AP-COMM", funds).reason());
        assertNull(state.runningJob());

        // long 上界：mwh × 单位耗煤溢出必须在付款前受控拒绝。
        long huge = Long.MAX_VALUE / 400L + 1L;
        assertEquals("QUANTITY_OVERFLOW", IntegratedResourcesModel.beginPowerDelivery(state, 0L,
                CO, "c9", "P0", huge, "AR-P", funds, payer("CO", "CONV")).reason());
        assertEquals(money("0.00"), funds.cash("CONV"));
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    // ------------------------------------------------- 3 数字：阶段 / 审批 / 合同边界

    @Test
    void digitalStageApprovalAndContractBoundaries() {
        DigitalServiceModel.Config config = new DigitalServiceModel.Config(CO, "CO", "OPS", "CUS",
                "CUS", "CON", "CON", "SPON", "SPON", new BigDecimal("1.0000"), money("0.60"),
                300L, money("300.00"), 200L, 5L * DAY, money("50.00"), 10L * DAY, money("100.00"),
                true, true, money("10.00"), money("25.00"),
                Map.of("AP-COMM", new DigitalServiceModel.Approval("AP-COMM",
                        DigitalServiceModel.APPROVAL_COMMERCIAL, 0L)));
        FundState funds = funds(accounts());
        DigitalServiceModel.State state = DigitalServiceModel.open(config, funds);
        PureCompanyTestSupport.Payer coToCon = payer("CO", "CON");

        // 未验证不得签约；签约也不得绕过商业化批准。
        assertEquals("NOT_VALIDATED", DigitalServiceModel.signServiceContract(state, 0L, CO,
                "s1", "C1", 10L, money("0.00"), funds, payer("CUS", "CO")).reason());
        // 未知授权引用。
        assertEquals("APPROVAL_INVALID", DigitalServiceModel.approveCommercialization(state, 0L,
                CO, "s2", "AP-NONE", funds).reason());
        // 未到期 / 跳级。
        var begun = DigitalServiceModel.beginStage(state, 0L, CO, "p1",
                DigitalServiceModel.Stage.PILOT, funds, coToCon);
        assertEquals("STAGE_BEGUN", begun.reason());
        state = begun.state();
        funds = begun.funds();
        var early = DigitalServiceModel.completeStage(state, DAY,
                CO, "p1c", DigitalServiceModel.Stage.PILOT,
                new DigitalServiceModel.Evidence("ev", "log", 0L), funds);
        assertEquals("STAGE_NOT_DUE", early.reason());
        assertEquals("STAGE_ORDER", DigitalServiceModel.beginStage(state, 0L, CO, "v0",
                DigitalServiceModel.Stage.VALIDATION, funds, coToCon).reason());
        // 证据时点越界（晚于现在 → 不是已观察到的证据）。
        var badEvidence = DigitalServiceModel.completeStage(state, 5L * DAY, CO, "p1c",
                DigitalServiceModel.Stage.PILOT,
                new DigitalServiceModel.Evidence("ev", "log", 5L * DAY + 1L), funds);
        assertEquals("EVIDENCE_INVALID", badEvidence.reason());

        // 合同状态边界：终止只对 ACTIVE 生效，退款只对 TERMINATED 生效。
        assertEquals("UNKNOWN_CONTRACT", DigitalServiceModel.terminateContract(state, 0L, CO,
                "t1", "C9", funds).reason());
        assertEquals("UNKNOWN_CONTRACT", DigitalServiceModel.refund(state, 0L, CO, "r1", "C9",
                money("1.00"), funds, payer("CO", "CUS")).reason());
    }
}
