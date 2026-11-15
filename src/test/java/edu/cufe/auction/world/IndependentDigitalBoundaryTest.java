package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import edu.cufe.auction.world.bank.FundState;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static edu.cufe.auction.world.PureCompanyTestSupport.account;
import static edu.cufe.auction.world.PureCompanyTestSupport.funds;
import static edu.cufe.auction.world.PureCompanyTestSupport.money;
import static edu.cufe.auction.world.PureCompanyTestSupport.payer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 独立审查补测（数字服务）：跨期需求/容量、预收退款边界、期末有限付款、证据时点。
 * 只新增测试，不改作者实现。
 */
class IndependentDigitalBoundaryTest {

    private static final long DAY = DigitalServiceModel.DAY_MILLIS;
    private static final long PERIOD = DigitalServiceModel.PERIOD_MILLIS;
    private static final String CO = DigitalIndustryConfig.COMPANY_ACCOUNT;
    private static final String OPS = DigitalIndustryConfig.OPS_ACCOUNT;
    private static final String CUS = DigitalIndustryConfig.CUSTOMER_ACCOUNT;
    private static final String LAB = DigitalIndustryConfig.CONTRACTOR_ACCOUNT;
    private static final String SPON = DigitalIndustryConfig.SPONSOR_ACCOUNT;

    /** 一次命令执行器：接受才提交候选。 */
    private static final class Book {
        private DigitalCompanyRun run;
        private FundState funds;
        private final FundPayer coToLab = payer(CO, LAB);
        private final FundPayer coToOps = payer(CO, OPS);
        private final FundPayer customerToCo = payer(CUS, CO);
        private final FundPayer coToCustomer = payer(CO, CUS);

        private Book() {
            this(funds(DigitalIndustryConfig.accounts()), DigitalIndustryConfig.runConfig());
        }

        private Book(FundState funds, DigitalCompanyRun.Config config) {
            this.funds = funds;
            this.run = DigitalCompanyRun.open(config, funds);
        }

        private DigitalOperations.Outcome apply(DigitalOperations.Action action,
                                                Map<String, String> args, FundPayer leg,
                                                long now, String commandId) {
            DigitalOperations.Outcome outcome = DigitalOperations.apply(run, funds, leg, now,
                    commandId, new DigitalOperations.Request(action, args));
            if (outcome.accepted()) {
                run = run.withLedger(outcome.state());
                funds = outcome.funds();
            }
            return outcome;
        }

        private BigDecimal at(String accountId) {
            return funds.cash(accountId);
        }

        /** 跑到 VALIDATION 通过、已登记商业化批准。 */
        private void contractReady() {
            assertTrue(apply(DigitalOperations.Action.BEGIN_PILOT, Map.of(), coToLab, 0L, "p-begin").accepted());
            assertTrue(apply(DigitalOperations.Action.COMPLETE_PILOT,
                    Map.of("evidenceId", DigitalIndustryConfig.EVIDENCE_PILOT), null, 5 * DAY, "p-done")
                    .accepted());
            assertTrue(apply(DigitalOperations.Action.BEGIN_VALIDATION, Map.of(), coToLab, 5 * DAY, "v-begin")
                    .accepted());
            assertTrue(apply(DigitalOperations.Action.COMPLETE_VALIDATION,
                    Map.of("evidenceId", DigitalIndustryConfig.EVIDENCE_VALIDATION), null, 15 * DAY, "v-done")
                    .accepted());
            assertTrue(apply(DigitalOperations.Action.APPROVE_COMMERCIAL,
                    Map.of("approvalId", DigitalIndustryConfig.APPROVAL_COMMERCIAL), null, 15 * DAY, "approve")
                    .accepted());
        }
    }

    @Test
    void crossPeriodDeliveryIsBoundByTheCurrentPeriodDemandAndCapacity() {
        Book book = new Book();
        book.contractReady();

        // 期 0：签 300（客户当期需求上限 300），交付 200（当期容量上限 200）。
        assertTrue(book.apply(DigitalOperations.Action.SIGN_CONTRACT,
                Map.of("contractId", "CT-1", "quantityUnits", "300", "prepaidCents", "0"),
                book.customerToCo, 15 * DAY, "s1").accepted());
        assertTrue(book.apply(DigitalOperations.Action.DELIVER,
                Map.of("contractId", "CT-1", "units", "200", "receivableId", "AR-1"),
                book.coToOps, 16 * DAY, "d1").accepted());
        assertTrue(book.apply(DigitalOperations.Action.CLOSE_PERIOD, Map.of("period", "0"),
                book.coToOps, 30 * DAY, "c0").accepted());

        // 期 1：跨期交付 100 重新占用当期需求与容量。
        assertTrue(book.apply(DigitalOperations.Action.DELIVER,
                Map.of("contractId", "CT-1", "units", "100", "receivableId", "AR-2"),
                book.coToOps, 31 * DAY, "d2").accepted());
        // 期内再签 200：当期已交付 100 + 新承诺 200 = 300，不超客户需求。
        assertTrue(book.apply(DigitalOperations.Action.SIGN_CONTRACT,
                Map.of("contractId", "CT-2", "quantityUnits", "200", "prepaidCents", "0"),
                book.customerToCo, 32 * DAY, "s2").accepted());
        assertTrue(book.apply(DigitalOperations.Action.DELIVER,
                Map.of("contractId", "CT-2", "units", "100", "receivableId", "AR-3"),
                book.coToOps, 33 * DAY, "d3").accepted());
        // 当期已交付 200 = 容量；再交付 1 必须整笔拒绝。
        DigitalOperations.Outcome over = book.apply(DigitalOperations.Action.DELIVER,
                Map.of("contractId", "CT-2", "units", "1", "receivableId", "AR-4"),
                book.coToOps, 34 * DAY, "d4");
        assertFalse(over.accepted());
        assertEquals(DigitalServiceModel.SERVICE_CAPACITY, over.reason());
        assertFalse(book.run.ledger().receivables().containsKey("AR-4"));
        assertTrue(DigitalServiceModel.validate(book.run.ledger(), book.funds).balanced());

        // 期 2：容量按期间重置，同一旧合同可以继续交付。
        assertTrue(book.apply(DigitalOperations.Action.CLOSE_PERIOD, Map.of("period", "1"),
                book.coToOps, 60 * DAY, "c1").accepted());
        assertTrue(book.apply(DigitalOperations.Action.DELIVER,
                Map.of("contractId", "CT-2", "units", "1", "receivableId", "AR-5"),
                book.coToOps, 61 * DAY, "d5").accepted());
        assertTrue(DigitalServiceModel.validate(book.run.ledger(), book.funds).balanced());
    }

    @Test
    void refundAndCollectRespectTerminationDueDateAndRealCash() {
        Book book = new Book();
        book.contractReady();

        assertTrue(book.apply(DigitalOperations.Action.SIGN_CONTRACT,
                Map.of("contractId", "CT-1", "quantityUnits", "100", "prepaidCents", "400000"),
                book.customerToCo, 15 * DAY, "sign").accepted());
        assertEquals(money("20500.00"), book.at(CO));

        DigitalOperations.Outcome beforeTerminate = book.apply(DigitalOperations.Action.REFUND,
                Map.of("contractId", "CT-1", "refundCents", "100000"), book.coToCustomer,
                15 * DAY, "refund-early");
        assertFalse(beforeTerminate.accepted());
        assertEquals(DigitalServiceModel.CONTRACT_NOT_TERMINATED, beforeTerminate.reason());

        assertTrue(book.apply(DigitalOperations.Action.DELIVER,
                Map.of("contractId", "CT-1", "units", "40", "receivableId", "AR-1"),
                book.coToOps, 16 * DAY, "deliver").accepted());

        // 应收 2400 未到期不能收款/核销；到期后只能收一次。
        DigitalOperations.Outcome earlyCollect = book.apply(DigitalOperations.Action.COLLECT,
                Map.of("receivableId", "AR-1"), book.customerToCo, 16 * DAY, "collect-early");
        assertFalse(earlyCollect.accepted());
        assertEquals(DigitalServiceModel.RECEIVABLE_NOT_DUE, earlyCollect.reason());
        DigitalOperations.Outcome earlyWriteOff = book.apply(DigitalOperations.Action.WRITE_OFF,
                Map.of("receivableId", "AR-1"), null, 16 * DAY, "writeoff-early");
        assertFalse(earlyWriteOff.accepted());
        assertEquals(DigitalServiceModel.BAD_DEBT_NOT_DUE, earlyWriteOff.reason());

        assertTrue(book.apply(DigitalOperations.Action.TERMINATE,
                Map.of("contractId", "CT-1"), null, 17 * DAY, "terminate").accepted());

        // 未赚预收 2400：超退拒绝，部分退款真实现金。
        DigitalOperations.Outcome tooMuch = book.apply(DigitalOperations.Action.REFUND,
                Map.of("contractId", "CT-1", "refundCents", "300000"), book.coToCustomer,
                17 * DAY, "refund-over");
        assertFalse(tooMuch.accepted());
        assertEquals(DigitalServiceModel.REFUND_EXCEEDS_LIABILITY, tooMuch.reason());
        assertTrue(book.apply(DigitalOperations.Action.REFUND,
                Map.of("contractId", "CT-1", "refundCents", "100000"), book.coToCustomer,
                17 * DAY, "refund-part").accepted());
        assertEquals(money("1400.00"), book.run.ledger().contracts().get("CT-1").unearned());
        DigitalOperations.Outcome stillTooMuch = book.apply(DigitalOperations.Action.REFUND,
                Map.of("contractId", "CT-1", "refundCents", "200000"), book.coToCustomer,
                17 * DAY, "refund-over-2");
        assertFalse(stillTooMuch.accepted());
        assertEquals(DigitalServiceModel.REFUND_EXCEEDS_LIABILITY, stillTooMuch.reason());

        // 应收 2400 到期后只能收一次。
        assertTrue(book.apply(DigitalOperations.Action.CLOSE_PERIOD, Map.of("period", "0"),
                book.coToOps, 30 * DAY, "c0").accepted());
        assertTrue(book.apply(DigitalOperations.Action.COLLECT,
                Map.of("receivableId", "AR-1"), book.customerToCo, 31 * DAY, "collect").accepted());
        DigitalOperations.Outcome doubleCollect = book.apply(DigitalOperations.Action.COLLECT,
                Map.of("receivableId", "AR-1"), book.customerToCo, 31 * DAY, "collect-again");
        assertFalse(doubleCollect.accepted());
        assertEquals(DigitalServiceModel.RECEIVABLE_CLOSED, doubleCollect.reason());

        // 第二份全额预付合同：交付 10 → 应收 1000；逾期后核销只冲资产，不倒退收入。
        assertTrue(book.apply(DigitalOperations.Action.SIGN_CONTRACT,
                Map.of("contractId", "CT-2", "quantityUnits", "10", "prepaidCents", "0"),
                book.customerToCo, 31 * DAY, "sign-2").accepted());
        assertTrue(book.apply(DigitalOperations.Action.DELIVER,
                Map.of("contractId", "CT-2", "units", "10", "receivableId", "AR-2"),
                book.coToOps, 31 * DAY, "deliver-2").accepted());
        assertTrue(book.apply(DigitalOperations.Action.WRITE_OFF,
                Map.of("receivableId", "AR-2"), null, 47 * DAY, "writeoff").accepted());
        assertEquals(DigitalServiceModel.ReceivableStatus.WRITTEN_OFF,
                book.run.ledger().receivables().get("AR-2").status());
        assertTrue(book.run.ledger().badDebtLoss().compareTo(money("1000.00")) == 0);
        assertTrue(DigitalServiceModel.validate(book.run.ledger(), book.funds).balanced());
    }

    @Test
    void closePeriodPaysOnlyAvailableCashAndCarriesPayables() {
        Map<String, CashLedger.CashAccount> accounts = new LinkedHashMap<>();
        accounts.put(CO, account(DigitalIndustryConfig.COMPANY, "100.00"));
        accounts.put(OPS, account(DigitalIndustryConfig.OPS, "0.00"));
        accounts.put(CUS, account(DigitalIndustryConfig.CUSTOMER, "0.00"));
        accounts.put(LAB, account(DigitalIndustryConfig.CONTRACTOR, "0.00"));
        accounts.put(SPON, account(DigitalIndustryConfig.SPONSOR, "0.00"));
        Book book = new Book(funds(Map.copyOf(accounts)), DigitalIndustryConfig.runConfig());

        assertTrue(book.apply(DigitalOperations.Action.CLOSE_PERIOD, Map.of("period", "0"),
                book.coToOps, 30 * DAY, "c0").accepted());
        assertEquals(money("300.00"), book.run.ledger().bills().get(0L).amount());
        assertEquals(money("100.00"), book.run.ledger().bills().get(0L).paid());
        assertEquals(money("0.00"), book.at(CO));
        assertEquals(money("100.00"), book.at(OPS));

        assertTrue(book.apply(DigitalOperations.Action.CLOSE_PERIOD, Map.of("period", "1"),
                book.coToOps, 60 * DAY, "c1").accepted());
        assertEquals(money("300.00"), book.run.ledger().bills().get(1L).amount());
        assertEquals(money("0.00"), book.run.ledger().bills().get(1L).paid());
        DigitalServiceModel.Report report = DigitalServiceModel.validate(book.run.ledger(), book.funds);
        assertEquals(money("0.00"), report.assets());
        assertEquals(money("500.00"), report.liabilities());
        assertEquals(money("-500.00"), report.equity());
        assertTrue(report.balanced());
    }

    @Test
    void stageCompletionNeedsTheDueTimeAndAFrozenEvidenceThatAlreadyExists() {
        // 完成时点取冻结观察：证据 observedAt 在开工前也不阻断，仍以真实完工时点为准。
        Book completion = new Book(funds(DigitalIndustryConfig.accounts()),
                new DigitalCompanyRun.Config(DigitalIndustryConfig.COMPANY,
                        DigitalIndustryConfig.SYMBOL, DigitalIndustryConfig.modelConfig(),
                        DigitalIndustryConfig.evidence(), Map.of(), true));
        assertTrue(completion.apply(DigitalOperations.Action.BEGIN_PILOT, Map.of(), completion.coToLab,
                0L, "begin").accepted());
        DigitalOperations.Outcome early = completion.apply(DigitalOperations.Action.COMPLETE_PILOT,
                Map.of("evidenceId", DigitalIndustryConfig.EVIDENCE_PILOT), null, 5 * DAY - 1, "early");
        assertFalse(early.accepted());
        assertEquals(DigitalServiceModel.STAGE_NOT_DUE, early.reason());
        DigitalOperations.Outcome forged = completion.apply(DigitalOperations.Action.COMPLETE_PILOT,
                Map.of("evidenceId", "made-up"), null, 5 * DAY, "forged");
        assertFalse(forged.accepted());
        assertEquals(DigitalOperations.UNKNOWN_EVIDENCE, forged.reason());
        assertTrue(completion.apply(DigitalOperations.Action.COMPLETE_PILOT,
                Map.of("evidenceId", DigitalIndustryConfig.EVIDENCE_PILOT), null, 5 * DAY, "done")
                .accepted());
        assertEquals(DigitalServiceModel.StageStatus.PASSED,
                completion.run.ledger().stageOf(DigitalServiceModel.Stage.PILOT).status());
        assertEquals(DigitalIndustryConfig.EVIDENCE_PILOT,
                completion.run.ledger().stageOf(DigitalServiceModel.Stage.PILOT).evidenceId());

        // 未启用“完成时点取观察”的旧档：证据观察时点晚于当前时点必须拒绝。
        Map<DigitalServiceModel.Stage, Map<String, DigitalServiceModel.Evidence>> futureEvidence =
                Map.of(DigitalServiceModel.Stage.PILOT, Map.of("future-report",
                        new DigitalServiceModel.Evidence("future-report", "lab/future", 6 * DAY)));
        Book fixedEvidence = new Book(funds(DigitalIndustryConfig.accounts()),
                new DigitalCompanyRun.Config(DigitalIndustryConfig.COMPANY,
                        DigitalIndustryConfig.SYMBOL, DigitalIndustryConfig.modelConfig(),
                        futureEvidence, Map.of(), false));
        assertTrue(fixedEvidence.apply(DigitalOperations.Action.BEGIN_PILOT, Map.of(),
                fixedEvidence.coToLab, 0L, "begin").accepted());
        DigitalOperations.Outcome tooEarly = fixedEvidence.apply(DigitalOperations.Action.COMPLETE_PILOT,
                Map.of("evidenceId", "future-report"), null, 5 * DAY, "too-early");
        assertFalse(tooEarly.accepted());
        assertEquals(DigitalServiceModel.EVIDENCE_INVALID, tooEarly.reason());
        assertTrue(fixedEvidence.apply(DigitalOperations.Action.COMPLETE_PILOT,
                Map.of("evidenceId", "future-report"), null, 6 * DAY, "ok").accepted());
    }
}
