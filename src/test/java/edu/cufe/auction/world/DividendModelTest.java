package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.SecuritiesLedger.Lot;
import edu.cufe.auction.world.SecuritiesLedger.State;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
 * M3d D0：现金分红权利与应收纯核（测试先行）。
 *
 * <p>覆盖正式施工单 `docs/superpowers/plans/2026-11-06-dividend-core.md` 的验收组：
 * 毫秒时点与状态转换、真实库存取权利（冻结/锁定/当日买入/库存股）、登记零应付、
 * 除息一次确认、整批全额清偿与拒绝族、减值不减债与付款转回、完整事件 ID 幂等与重放、
 * 金额精度与极值、方案/账户引用隔离、JSON 严格往返与伪造拒绝。</p>
 *
 * <p>本测试只使用真实 {@link OpeningRegistry} 与 {@link SecuritiesLedger} 构建证券账，
 * 不 Mock 批次与冻结；不涉及运行时、现金账户、价格参考、NAV/TWR 或 I/O。</p>
 *
 * <p>失败原因码的具体字符串由实现确定，本测试只断言"接受/拒绝 + 旧状态不变 +
 * 幂等回报位置与原因一致"，避免把未裁决的词汇写死。</p>
 */
@DisplayName("DividendModel：分红权利、确认与清偿纯核")
class DividendModelTest {

    private static final String SYM = "ACME1";
    private static final String ISSUER = "acme";
    private static final String COMPANY_ACC = "ACME-CASH";

    // ------------------------------------------------------------------
    // 正式验收组：状态转换与时点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完整流程：提案→授权→登记→除息确认→整批清偿")
    void fullFlowReachesSettled() {
        DividendModel.State state = DividendModel.empty();

        DividendModel.Result proposed = DividendModel.propose(state,
                event("e1", 1, 1_000), request("S1", "0.10", 1_000, 2_000, 3_000));
        assertTrue(proposed.accepted(), "提案应被接受");
        assertEquals(DividendModel.Phase.PROPOSED, DividendModel.scheme(proposed.state(), "S1").phase());

        DividendModel.Result authorized = DividendModel.authorize(proposed.state(),
                event("e2", 2, 1_000), "S1", "board-resolution-7");
        assertTrue(authorized.accepted(), "登记前授权应被接受");
        assertEquals(DividendModel.Phase.AUTHORIZED, DividendModel.scheme(authorized.state(), "S1").phase());
        assertEquals("board-resolution-7",
                DividendModel.scheme(authorized.state(), "S1").authorizationRef(),
                "D0 只保存宿主授权引用，不自行判定真实性");

        DividendModel.Result registered = DividendModel.register(authorized.state(),
                event("e3", 3, 1_000), "S1", holdings());
        assertTrue(registered.accepted(), "登记应被接受");
        assertEquals(DividendModel.Phase.REGISTERED, DividendModel.scheme(registered.state(), "S1").phase());

        DividendModel.Result booked = DividendModel.bookEx(registered.state(),
                event("e4", 4, 2_000), "S1");
        assertTrue(booked.accepted(), "除息确认应被接受");
        assertEquals(DividendModel.Phase.EX_BOOKED, DividendModel.scheme(booked.state(), "S1").phase());

        Map<String, BigDecimal> perClaim = fullPayment(booked.state(), "S1");
        DividendModel.Result settled = DividendModel.settle(booked.state(),
                event("e5", 5, 3_000), "S1", "cash-batch-1", total(perClaim), perClaim);
        assertTrue(settled.accepted(), "整批全额清偿应被接受");

        DividendModel.State done = settled.state();
        assertEquals(DividendModel.Phase.SETTLED, DividendModel.scheme(done, "S1").phase());
        assertEquals(0, DividendModel.companyPayable(done, "S1").signum(), "清偿后公司应付归零");
        for (DividendModel.Claim claim : DividendModel.claims(done, "S1")) {
            assertEquals(0, DividendModel.faceUnpaid(claim).signum(), "每个 claim 必须全额付清");
            assertEquals(0, DividendModel.holderNetReceivable(claim).signum(), "净应收必须归零");
            assertEquals(0, claim.impairment().signum(), "清偿后不得残留减值");
        }
    }

    @Test
    @DisplayName("时点规则：登记不早于 recordAt、除息不早于 exAt、清偿不早于 payAt")
    void timeBoundariesAreEnforced() {
        DividendModel.State authorized = authorized("S1", "0.10", 1_000, 2_000, 3_000);

        DividendModel.Result early = DividendModel.register(authorized,
                event("e3", 3, 999), "S1", holdings());
        assertFalse(early.accepted(), "recordAt 之前不得登记");

        DividendModel.State registered = registered("S1", "0.10", 1_000, 2_000, 3_000);
        DividendModel.Result earlyEx = DividendModel.bookEx(registered,
                event("e4", 4, 1_999), "S1");
        assertFalse(earlyEx.accepted(), "exAt 之前不得确认除息");

        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        Map<String, BigDecimal> perClaim = fullPayment(booked, "S1");
        DividendModel.Result earlyPay = DividendModel.settle(booked,
                event("e5", 5, 2_999), "S1", "cash-batch-1", total(perClaim), perClaim);
        assertFalse(earlyPay.accepted(), "payAt 之前不得清偿");
        assertEquals(booked, earlyPay.state(), "拒绝时保留旧状态");
    }

    @Test
    @DisplayName("时点构造：recordAt 必须严格早于 exAt，exAt 不晚于 payAt，负值拒绝")
    void timelineShapeIsValidated() {
        DividendModel.State empty = DividendModel.empty();

        assertFalse(DividendModel.propose(empty, event("x1", 1, 1_000),
                request("S1", "0.10", 1_000, 1_000, 3_000)).accepted(),
                "recordAt 与 exAt 相同必须拒绝");
        assertFalse(DividendModel.propose(empty, event("x2", 1, 1_000),
                request("S1", "0.10", 1_000, 3_000, 2_000)).accepted(),
                "exAt 晚于 payAt 必须拒绝");
        assertFalse(DividendModel.propose(empty, event("x3", 1, 1_000),
                request("S1", "0.10", -1, 2_000, 3_000)).accepted(),
                "负时点必须拒绝");
    }

    @Test
    @DisplayName("Long 最大时点合法：不因日期换算或加法溢出被拒")
    void maximumMillisAreUsable() {
        DividendModel.Result result = DividendModel.propose(DividendModel.empty(),
                event("e1", 1, Long.MAX_VALUE - 2),
                request("S1", "0.10", Long.MAX_VALUE - 2, Long.MAX_VALUE - 1, Long.MAX_VALUE));
        assertTrue(result.accepted(), "Long 最大值是合法实验时点");
    }

    @Test
    @DisplayName("世界位置严格递增、模拟时点不倒退，位置允许有空档")
    void positionsIncreaseAndTimeNeverGoesBackwards() {
        DividendModel.State after = DividendModel.propose(DividendModel.empty(),
                event("e1", 10, 1_000), request("S1", "0.10", 1_000, 2_000, 3_000)).state();

        DividendModel.Result samePosition = DividendModel.propose(after,
                event("e2", 10, 1_001), request("S2", "0.20", 1_000, 2_000, 3_000));
        assertFalse(samePosition.accepted(), "位置必须严格递增");

        DividendModel.Result backwards = DividendModel.propose(after,
                event("e3", 11, 999), request("S3", "0.20", 1_000, 2_000, 3_000));
        assertFalse(backwards.accepted(), "模拟时点不得倒退");

        DividendModel.Result gap = DividendModel.propose(after,
                event("e4", 99, 1_500), request("S4", "0.20", 2_000, 3_000, 4_000));
        assertTrue(gap.accepted(), "位置允许有间隔，只要求严格递增");
    }

    @Test
    @DisplayName("登记后不得取消；登记前取消后不得再授权或登记")
    void cancelOnlyBeforeRegistration() {
        DividendModel.State registered = registered("S1", "0.10", 1_000, 2_000, 3_000);
        DividendModel.Result late = DividendModel.cancel(registered,
                event("e9", 9, 2_500), "S1", "撤销");
        assertFalse(late.accepted(), "登记后不得取消，变更须走新方案 ID");

        DividendModel.State proposed = DividendModel.empty();
        DividendModel.Result p = DividendModel.propose(proposed,
                event("c1", 1, 100), request("S9", "0.10", 5_000, 6_000, 7_000));
        DividendModel.Result c = DividendModel.cancel(p.state(), event("c2", 2, 200), "S9", "撤回");
        assertTrue(c.accepted(), "登记前允许取消");
        assertEquals(DividendModel.Phase.CANCELLED, DividendModel.scheme(c.state(), "S9").phase());

        assertFalse(DividendModel.authorize(c.state(), event("c3", 3, 300), "S9", "ref")
                .accepted(), "已取消方案不得再授权");
        assertFalse(DividendModel.register(c.state(), event("c4", 4, 5_000), "S9", holdings())
                .accepted(), "已取消方案不得登记");
    }

    @Test
    @DisplayName("已清偿方案不得重新登记、除息或再次清偿")
    void settledSchemeIsTerminal() {
        DividendModel.State settled = settled("S1", "0.10", 1_000, 2_000, 3_000);

        assertFalse(DividendModel.register(settled, event("z1", 30, 4_000), "S1", holdings())
                .accepted(), "已清偿不得重新登记");
        assertFalse(DividendModel.bookEx(settled, event("z2", 31, 4_000), "S1")
                .accepted(), "已清偿不得重新除息");
        Map<String, BigDecimal> perClaim = fullPayment(settled, "S1");
        assertFalse(DividendModel.settle(settled, event("z3", 32, 4_000), "S1",
                "cash-batch-2", total(perClaim), perClaim).accepted(), "已清偿不得再次清偿");
    }

    // ------------------------------------------------------------------
    // 登记：真实库存、零应付、不可重取
    // ------------------------------------------------------------------

    @Test
    @DisplayName("登记只固定归属：claims 为空，公司应付与投资者应收均为 0.00")
    void registrationCreatesNoObligation() {
        DividendModel.State registered = registered("S1", "0.10", 1_000, 2_000, 3_000);

        assertTrue(DividendModel.claims(registered, "S1").isEmpty(), "登记阶段不得生成 claims");
        assertEquals(0, DividendModel.companyPayable(registered, "S1").signum(),
                "登记时公司应付必须为 0.00，不得提前确认义务");
        assertEquals(0, DividendModel.declaredTotal(registered, "S1").compareTo(new BigDecimal("25.00")),
                "declaredTotal 只表示已固定的权利面值");
        assertEquals(DividendModel.Phase.REGISTERED, DividendModel.scheme(registered, "S1").phase());
        assertNotNull(DividendModel.snapshot(registered, "S1"));
    }

    @Test
    @DisplayName("权利取自实际剩余库存：含冻结、锁定与当日买入不可卖批次")
    void registrationIncludesFrozenLockedAndTodayBoughtLots() {
        DividendModel.State registered = registered("S1", "0.10", 1_000, 2_000, 3_000);
        DividendModel.RightsSnapshot snapshot = DividendModel.snapshot(registered, "S1");

        assertEquals(250L, snapshot.totalQuantity(), "应为 250 股：A100 + B60 + C50 + D40");
        assertEquals(0, snapshot.totalAmount().compareTo(new BigDecimal("25.00")));
        assertEquals(100L, quantityOf(snapshot, "A"), "A 有 30 股被卖单冻结，仍按 100 股计入");
        assertEquals(60L, quantityOf(snapshot, "B"), "B 卖出 40 股后剩 60 股");
        assertEquals(50L, quantityOf(snapshot, "C"), "LOCKED 批次计入权利");
        assertEquals(40L, quantityOf(snapshot, "D"), "当日买入、次日才可卖的批次计入权利");
    }

    @Test
    @DisplayName("库存股排除在权利之外")
    void treasuryLotsAreExcluded() {
        DividendModel.State registered = registered("S1", "0.10", 1_000, 2_000, 3_000);
        DividendModel.RightsSnapshot snapshot = DividendModel.snapshot(registered, "S1");
        assertNull(entryOf(snapshot, "ACME"), "库存股持有人不得进入登记快照");
        assertEquals(250L, snapshot.totalQuantity());
    }

    @Test
    @DisplayName("重复登记不重新取后来持仓：拒绝且保留首次快照")
    void duplicateRegistrationKeepsFirstSnapshot() {
        DividendModel.State registered = registered("S1", "0.10", 1_000, 2_000, 3_000);
        DividendModel.RightsSnapshot first = DividendModel.snapshot(registered, "S1");

        DividendModel.Result again = DividendModel.register(registered,
                event("e9", 9, 1_500), "S1", emptyHoldings());
        assertFalse(again.accepted(), "同一方案不得重复登记");
        assertEquals(first, DividendModel.snapshot(again.state(), "S1"),
                "不得用后来的（空）持仓覆盖登记快照");
    }

    @Test
    @DisplayName("登记后转卖不转移既有权利：claims 只按登记快照生成")
    void claimsFollowSnapshotNotLaterHoldings() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        // 登记之后 A 已全部卖出（只体现在证券账里，D0 不再读取持仓）
        SecuritiesLedger.State soldOut = SecuritiesLedger.fill(
                SecuritiesLedger.reserve(SecuritiesLedger.opening(registry()),
                        "res-all", "A", SYM, 100L, 0L).state(),
                "task-all", "res-all", "B", new BigDecimal("10.00"), 100L, 0L).state();
        assertEquals(0L, SecuritiesLedger.available(soldOut, "A", SYM, 1L), "夹具前提：A 已清仓");

        assertEquals(100L, quantityOf(DividendModel.snapshot(booked, "S1"), "A"),
                "权利仍按登记快照的 100 股");
        assertEquals(0, faceOf(booked, "S1", "A").compareTo(new BigDecimal("10.00")));
    }

    @Test
    @DisplayName("除息确认一次生成 claims，面值为登记股数乘每股金额")
    void bookExCreatesClaimsOnce() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);

        assertEquals(4, DividendModel.claims(booked, "S1").size());
        assertEquals(0, faceOf(booked, "S1", "A").compareTo(new BigDecimal("10.00")));
        assertEquals(0, faceOf(booked, "S1", "B").compareTo(new BigDecimal("6.00")));
        assertEquals(0, faceOf(booked, "S1", "C").compareTo(new BigDecimal("5.00")));
        assertEquals(0, faceOf(booked, "S1", "D").compareTo(new BigDecimal("4.00")));
        assertEquals(0, DividendModel.companyPayable(booked, "S1").compareTo(new BigDecimal("25.00")),
                "除息确认后公司应付才等于全部合同未付额");

        DividendModel.Result again = DividendModel.bookEx(booked, event("e9", 9, 2_500), "S1");
        assertFalse(again.accepted(), "同一方案只能确认一次");
        assertEquals(booked, again.state(), "重复确认不得改变已确认 claims");
    }

    @Test
    @DisplayName("declaredTotal 固定不变，companyPayable 随付款递减")
    void declaredTotalStaysFixedWhilePayableFalls() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        DividendModel.State settled = settled("S1", "0.10", 1_000, 2_000, 3_000);

        assertEquals(0, DividendModel.declaredTotal(settled, "S1").compareTo(new BigDecimal("25.00")),
                "清偿后已固定权利面值不变");
        assertEquals(0, DividendModel.companyPayable(settled, "S1").signum(), "应付已结清");
        assertEquals(0, DividendModel.declaredTotal(booked, "S1").compareTo(new BigDecimal("25.00")));
    }

    // ------------------------------------------------------------------
    // 清偿：整批全额与拒绝族
    // ------------------------------------------------------------------

    @Test
    @DisplayName("清偿必须带非空现金证据")
    void settlementRequiresCashEvidence() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        Map<String, BigDecimal> perClaim = fullPayment(booked, "S1");

        DividendModel.Result blank = DividendModel.settle(booked, event("p1", 10, 3_000),
                "S1", "  ", total(perClaim), perClaim);
        assertFalse(blank.accepted(), "空白证据必须拒绝");
        assertEquals(booked, blank.state(), "拒绝不得改变已付");

        DividendModel.Result missing = DividendModel.settle(booked, event("p2", 11, 3_000),
                "S1", null, total(perClaim), perClaim);
        assertFalse(missing.accepted(), "缺失证据必须拒绝");
    }

    @Test
    @DisplayName("清偿明细必须覆盖全部未付权益，缺一条即拒绝")
    void settlementRequiresFullCoverage() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        Map<String, BigDecimal> partial = new LinkedHashMap<>(fullPayment(booked, "S1"));
        String dropped = DividendModel.claims(booked, "S1").get(0).claimId();
        partial.remove(dropped);

        DividendModel.Result result = DividendModel.settle(booked, event("p1", 10, 3_000),
                "S1", "cash-batch-1", total(partial), partial);
        assertFalse(result.accepted(), "漏掉任一未付 claim 必须拒绝");
        assertEquals(booked, result.state(), "拒绝后已付金额不变");
    }

    @Test
    @DisplayName("首轮拒绝部分金额、超付与跨方案明细")
    void settlementRejectsPartialOverpayAndForeignClaims() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        Map<String, BigDecimal> full = fullPayment(booked, "S1");

        Map<String, BigDecimal> half = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : full.entrySet()) {
            half.put(entry.getKey(), entry.getValue().divide(new BigDecimal("2")));
        }
        assertFalse(DividendModel.settle(booked, event("q1", 10, 3_000), "S1",
                "cash-batch-1", total(half), half).accepted(), "部分付款必须拒绝");

        Map<String, BigDecimal> over = new LinkedHashMap<>(full);
        String firstClaim = over.keySet().iterator().next();
        over.put(firstClaim, over.get(firstClaim).add(new BigDecimal("0.01")));
        assertFalse(DividendModel.settle(booked, event("q2", 11, 3_000), "S1",
                "cash-batch-1", total(over), over).accepted(), "超付必须拒绝");

        DividendModel.State other = exBooked("S2", "0.20", 1_000, 2_000, 3_000);
        String foreign = DividendModel.claims(other, "S2").get(0).claimId();
        Map<String, BigDecimal> mixed = new LinkedHashMap<>(full);
        mixed.put(foreign, new BigDecimal("1.00"));
        assertFalse(DividendModel.settle(booked, event("q3", 12, 3_000), "S1",
                "cash-batch-1", total(mixed), mixed).accepted(), "跨方案 claim 必须拒绝");
    }

    // ------------------------------------------------------------------
    // 减值：不减债、上限、清偿转回
    // ------------------------------------------------------------------

    @Test
    @DisplayName("减值只降低投资者净应收，不减合同面值、已付或公司应付")
    void impairmentNeverReducesCompanyDebt() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        String claimA = claimIdOf(booked, "S1", "A");

        DividendModel.Result impaired = DividendModel.impair(booked, event("i1", 10, 2_500),
                claimA, new BigDecimal("4.00"), "评估证据-1");
        assertTrue(impaired.accepted());
        DividendModel.Claim claim = DividendModel.claim(impaired.state(), claimA);

        assertEquals(0, claim.face().compareTo(new BigDecimal("10.00")), "合同面值不变");
        assertEquals(0, claim.paid().signum(), "已付不变");
        assertEquals(0, DividendModel.faceUnpaid(claim).compareTo(new BigDecimal("10.00")),
                "未付面值不减");
        assertEquals(0, DividendModel.holderNetReceivable(claim).compareTo(new BigDecimal("6.00")),
                "净应收 = 面值 − 已付 − 减值");
        assertEquals(0, DividendModel.companyPayable(impaired.state(), "S1")
                        .compareTo(new BigDecimal("25.00")),
                "公司应付不因投资者减值而减少");
    }

    @Test
    @DisplayName("减值累计不超过未付面值，且必须带证据")
    void impairmentIsCappedAndNeedsEvidence() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        String claimA = claimIdOf(booked, "S1", "A");

        assertFalse(DividendModel.impair(booked, event("i0", 10, 2_500), claimA,
                new BigDecimal("10.01"), "证据").accepted(), "减值不得超过未付面值");
        assertFalse(DividendModel.impair(booked, event("i1", 10, 2_500), claimA,
                new BigDecimal("1.00"), " ").accepted(), "减值必须带证据");

        DividendModel.State full = DividendModel.impair(booked, event("i2", 10, 2_500), claimA,
                new BigDecimal("10.00"), "全额减值证据").state();
        assertFalse(DividendModel.impair(full, event("i3", 11, 2_600), claimA,
                new BigDecimal("0.01"), "再加一笔").accepted(), "累计不得超过未付面值");
    }

    @Test
    @DisplayName("整批清偿清除对应减值并保留转回额")
    void settlementReleasesImpairmentAndRecordsReversal() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        String claimA = claimIdOf(booked, "S1", "A");
        DividendModel.State impaired = DividendModel.impair(booked, event("i1", 10, 2_500),
                claimA, new BigDecimal("4.00"), "评估证据-1").state();

        Map<String, BigDecimal> perClaim = fullPayment(impaired, "S1");
        DividendModel.Result settled = DividendModel.settle(impaired, event("p1", 11, 3_000),
                "S1", "cash-batch-1", total(perClaim), perClaim);
        assertTrue(settled.accepted());

        DividendModel.Claim claim = DividendModel.claim(settled.state(), claimA);
        assertEquals(0, claim.impairment().signum(), "真实付款后减值应清除");
        assertEquals(0, claim.paid().compareTo(new BigDecimal("10.00")), "按面值全额收款");
        assertEquals(1, settled.state().payments().size());
        DividendModel.Payment payment = settled.state().payments().get(0);
        assertEquals(0, payment.impairmentReleased().get(claimA).compareTo(new BigDecimal("4.00")),
                "清偿记录必须保留转回额供后续投资损益核算");
    }

    // ------------------------------------------------------------------
    // 幂等、重放与事件 ID
    // ------------------------------------------------------------------

    @Test
    @DisplayName("相同事件 ID 与完整请求重放返回原回报且不追加")
    void replayReturnsOriginalReceiptWithoutAppending() {
        DividendModel.State authorized = authorized("S1", "0.10", 1_000, 2_000, 3_000);
        DividendModel.State registered = DividendModel.register(authorized,
                event("e3", 3, 1_000), "S1", holdings()).state();

        DividendModel.Result replay = DividendModel.authorize(registered,
                event("e2", 2, 1_000), "S1", "board-resolution-7");

        assertTrue(replay.accepted(), "重放应返回原接受回报");
        assertEquals(2, replay.position(), "回报位置应为原始事件位置");
        assertEquals(DividendModel.Phase.REGISTERED,
                DividendModel.scheme(replay.state(), "S1").phase(),
                "重放不得把已登记方案退回授权态");
        assertEquals(registered, replay.state(), "重放不得追加或改写状态");
    }

    @Test
    @DisplayName("同一事件 ID 携带不同内容必须拒绝并保留旧状态")
    void sameEventIdWithDifferentPayloadIsRejected() {
        DividendModel.State state = DividendModel.propose(DividendModel.empty(),
                event("e1", 1, 1_000), request("S1", "0.10", 1_000, 2_000, 3_000)).state();

        DividendModel.Result conflicting = DividendModel.propose(state,
                event("e1", 1, 1_000), request("S1", "0.20", 1_000, 2_000, 3_000));
        assertFalse(conflicting.accepted(), "同 ID 异内容必须拒绝");
        assertEquals(state, conflicting.state(), "拒绝时保留完整旧状态");
        assertEquals(0, DividendModel.scheme(conflicting.state(), "S1").perShare()
                .compareTo(new BigDecimal("0.10")), "旧方案金额不得被改写");
    }

    // ------------------------------------------------------------------
    // 标识、精度与极值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("派生 claim 标识无歧义：schemeId 含分隔符不得与另一方案别名碰撞")
    void claimIdsCannotCollideAcrossSchemes() {
        SecuritiesLedger.State shared = new SecuritiesLedger.State(
                List.of(lot("l1", "c", 100L), lot("l2", "b:c", 50L)),
                List.of(), Set.of(), Map.of());

        DividendModel.State state = DividendModel.empty();
        state = DividendModel.propose(state, event("c1", 1, 1_000),
                new DividendModel.SchemeRequest("a:b", SYM, ISSUER, COMPANY_ACC,
                        new BigDecimal("0.10"), 1_000, 2_000, 3_000)).state();
        state = DividendModel.authorize(state, event("c2", 2, 1_000), "a:b", "ref").state();
        state = DividendModel.register(state, event("c3", 3, 1_000), "a:b", shared).state();
        state = DividendModel.propose(state, event("c4", 4, 1_000),
                new DividendModel.SchemeRequest("a", SYM, ISSUER, COMPANY_ACC,
                        new BigDecimal("0.10"), 1_000, 2_000, 3_000)).state();
        state = DividendModel.authorize(state, event("c5", 5, 1_000), "a", "ref").state();
        state = DividendModel.register(state, event("c6", 6, 1_000), "a", shared).state();
        state = DividendModel.bookEx(state, event("c7", 7, 2_000), "a:b").state();
        state = DividendModel.bookEx(state, event("c8", 8, 2_000), "a").state();

        assertEquals(4, state.claims().size(), "两个方案各 2 条 claim，标识必须互不覆盖");
        assertEquals(4, state.claims().keySet().stream().distinct().count());
    }

    @Test
    @DisplayName("金额精度：精确值规范到 2 位，超过 2 位小数拒绝，不使用分转 long")
    void amountPrecisionIsExact() {
        DividendModel.State empty = DividendModel.empty();

        assertTrue(DividendModel.propose(empty, event("p1", 1, 1_000),
                request("S1", "1", 1_000, 2_000, 3_000)).accepted(), "1 应规范为 1.00");
        DividendModel.Scheme scheme = DividendModel.scheme(
                DividendModel.propose(empty, event("p2", 1, 1_000),
                        request("S2", "0.1", 1_000, 2_000, 3_000)).state(), "S2");
        assertEquals(2, scheme.perShare().scale(), "金额必须保持 2 位小数");
        assertEquals(0, scheme.perShare().compareTo(new BigDecimal("0.10")));

        assertFalse(DividendModel.propose(empty, event("p3", 1, 1_000),
                request("S3", "0.005", 1_000, 2_000, 3_000)).accepted(),
                "超过 2 位小数的每股金额必须拒绝，不得舍入");
        assertFalse(DividendModel.propose(empty, event("p4", 1, 1_000),
                request("S4", "0.00", 1_000, 2_000, 3_000)).accepted(), "零股息必须拒绝");
    }

    @Test
    @DisplayName("极大金额仍用精确 BigDecimal 乘数量，不因 long 分溢出被误拒")
    void hugeAmountsStayExact() {
        long quantity = 123_456_789L;
        BigDecimal perShare = new BigDecimal("999999999.99");
        SecuritiesLedger.State huge = new SecuritiesLedger.State(
                List.of(new SecuritiesLedger.Lot("lot-huge", "A", SYM, quantity, 0L, 0L, 0L,
                        new BigDecimal("999999999.9900"), ShareClass.FLOAT)),
                List.of(), Set.of(), Map.of());

        DividendModel.State state = DividendModel.propose(DividendModel.empty(),
                event("h1", 1, 1_000), new DividendModel.SchemeRequest("SH", SYM, ISSUER,
                        COMPANY_ACC, perShare, 1_000, 2_000, 3_000)).state();
        state = DividendModel.authorize(state, event("h2", 2, 1_000), "SH", "ref").state();
        state = DividendModel.register(state, event("h3", 3, 1_000), "SH", huge).state();
        state = DividendModel.bookEx(state, event("h4", 4, 2_000), "SH").state();

        BigDecimal expected = perShare.multiply(BigDecimal.valueOf(quantity));
        BigDecimal actual = DividendModel.claims(state, "SH").get(0).face();
        assertEquals(0, actual.compareTo(expected), "面值必须精确等于 每股金额 × 股数");
        assertEquals(2, actual.scale(), "结果仍为 2 位小数");
        assertTrue(actual.compareTo(new BigDecimal("123456788998765432.11")) == 0,
                "手算极值：999999999.99 × 123456789 = 123456788998765432.11");
    }

    // ------------------------------------------------------------------
    // JSON：严格往返与伪造拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合法状态精确往返")
    void jsonRoundTripIsExact() {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        String json = DividendModel.toJson(booked);
        assertEquals(booked, DividendModel.fromJson(json), "合法状态必须精确往返");
        assertEquals(DividendModel.empty(), DividendModel.fromJson(DividendModel.toJson(DividendModel.empty())),
                "空状态也必须精确往返");
    }

    @Test
    @DisplayName("JSON 形状与空状态一致：字段名固定后可被本用例守住")
    void jsonShapeMatchesDeclaredFields() {
        String valid = "{\"schemes\":{},\"snapshots\":{},\"claims\":{},\"payments\":[],"
                + "\"impairments\":[],\"receipts\":{},\"lastPosition\":0,\"lastAtMillis\":0}";
        assertEquals(DividendModel.empty(), DividendModel.fromJson(valid),
                "空状态的 JSON 形状必须与本用例一致；改字段需同步更新测试");
    }

    @Test
    @DisplayName("严格解析：重复键、尾随内容与整数位浮点均拒绝")
    void jsonParsingIsStrict() {
        String valid = "{\"schemes\":{},\"snapshots\":{},\"claims\":{},\"payments\":[],"
                + "\"impairments\":[],\"receipts\":{},\"lastPosition\":0,\"lastAtMillis\":0}";

        String duplicated = "{\"schemes\":{},\"snapshots\":{},\"claims\":{},\"payments\":[],"
                + "\"impairments\":[],\"receipts\":{},\"lastPosition\":0,\"lastPosition\":1,"
                + "\"lastAtMillis\":0}";
        assertThrows(IllegalArgumentException.class, () -> DividendModel.fromJson(duplicated),
                "重复键必须拒绝，不能静默取最后一个");

        assertThrows(IllegalArgumentException.class,
                () -> DividendModel.fromJson(valid + "{}"), "尾随内容必须拒绝");

        String floatPosition = valid.replace("\"lastPosition\":0", "\"lastPosition\":1.5");
        assertFalse(floatPosition.equals(valid), "形状变化需同步本用例");
        assertThrows(IllegalArgumentException.class,
                () -> DividendModel.fromJson(floatPosition), "整数字段不得接受浮点截断");

        assertThrows(IllegalArgumentException.class,
                () -> DividendModel.fromJson("{\"a\":1,\"a\":2}"), "重复键检测必须在绑定前生效");
    }

    @Test
    @DisplayName("伪造已付或改写面值的 JSON 必须被构造校验拒绝")
    void jsonRejectsForgedPaidAndFace() {
        DividendModel.State settled = settled("S1", "0.10", 1_000, 2_000, 3_000);
        String json = DividendModel.toJson(settled);

        String forgedPaid = json.replace("\"paid\":10.00", "\"paid\":5.00");
        assertFalse(forgedPaid.equals(json), "形状变化需同步本用例");
        assertThrows(IllegalArgumentException.class, () -> DividendModel.fromJson(forgedPaid),
                "已付金额与付款事件不一致必须拒绝");

        String forgedFace = json.replace("\"face\":10.00", "\"face\":99.00");
        assertFalse(forgedFace.equals(json), "形状变化需同步本用例");
        assertThrows(IllegalArgumentException.class, () -> DividendModel.fromJson(forgedFace),
                "面值与每股金额×股数不一致必须拒绝");
    }

    @Test
    void replayedRegistrationKeepsItsOriginalRightsWhenTheHostLedgerHasMovedOn() {
        DividendModel.State registered = registered("S1", "0.10", 1_000, 2_000, 3_000);
        SecuritiesLedger.State later = SecuritiesLedger.reserve(holdings(), "later-reserve", "B", SYM, 10, 1_100).state();
        later = SecuritiesLedger.fill(later, "later-fill", "later-reserve", "E", new BigDecimal("10.00"), 10, 1_101).state();
        DividendModel.Result replay = DividendModel.register(registered, event("e3", 3, 1_000), "S1", later);
        assertTrue(replay.accepted());
        assertEquals(3, replay.position());
        assertEquals(registered, replay.state(), "已确认登记重放先查原回报，后来证券账不改原权益");
    }

    @Test
    void fixedCutoffsRejectLateRegistrationAndLateExBooking() {
        DividendModel.State authorized = authorized("S1", "0.10", 1_000, 2_000, 3_000);
        assertFalse(DividendModel.register(authorized, event("late-register", 3, 1_001), "S1", holdings()).accepted());
        DividendModel.State registered = registered("S1", "0.10", 1_000, 2_000, 3_000);
        assertFalse(DividendModel.bookEx(registered, event("late-ex", 4, 2_001), "S1").accepted());
    }

    @Test
    void completeEventEnvelopeIsPartOfReplayIdentity() {
        DividendModel.State state = authorized("S1", "0.10", 1_000, 2_000, 3_000);
        for (DividendModel.Event changed : List.of(event("e2", 22, 1_000), event("e2", 2, 1_001))) {
            DividendModel.Result result = DividendModel.authorize(state, changed, "S1", "board-resolution-7");
            assertFalse(result.accepted(), "原事件位置及时点都不能改写");
            assertEquals(state, result.state());
        }
    }

    @Test
    void paymentAndImpairmentEvidenceMustMatchOriginalRequests() throws Exception {
        DividendModel.State booked = exBooked("S1", "0.10", 1_000, 2_000, 3_000);
        String claimId = claimIdOf(booked, "S1", "A");
        DividendModel.State impaired = DividendModel.impair(booked, event("imp", 5, 2_100), claimId,
                new BigDecimal("4.00"), "original-credit-evidence").state();
        Map<String, BigDecimal> perClaim = fullPayment(impaired, "S1");
        DividendModel.State done = DividendModel.settle(impaired, event("pay", 6, 3_000), "S1",
                "original-cash-evidence", total(perClaim), perClaim).state();
        ObjectNode root = jsonState(done);
        ((ObjectNode) root.get("payments").get(0)).put("cashEvidenceId", "forged-cash-evidence");
        rejectsState(root);
        root = jsonState(done);
        ((ObjectNode) root.get("impairments").get(0)).put("evidenceRef", "forged-credit-evidence");
        rejectsState(root);
    }

    @Test
    void registeredSnapshotMustMatchPerShareBeforeClaimsExist() throws Exception {
        DividendModel.State state = registered("S1", "0.10", 1_000, 2_000, 3_000);
        ObjectNode root = jsonState(state);
        ObjectNode snapshot = (ObjectNode) root.get("snapshots").get("S1");
        ((ObjectNode) snapshot.get("entries").get(0)).put("face", "11.00");
        snapshot.put("totalAmount", "26.00");
        rejectsState(root);
    }

    @Test
    void orphanRightsSnapshotCannotBeRestored() throws Exception {
        ObjectNode root = jsonState(registered("S1", "0.10", 1_000, 2_000, 3_000));
        ObjectNode snapshots = (ObjectNode) root.get("snapshots");
        ObjectNode orphan = ((ObjectNode) snapshots.get("S1")).deepCopy();
        orphan.put("schemeId", "ghost");
        snapshots.set("ghost", orphan);
        rejectsState(root);
    }

    @Test
    void everyAcceptedReceiptMustAdvanceItsOriginalLegalPhase() throws Exception {
        ObjectNode root = jsonState(authorized("S1", "0.10", 1_000, 2_000, 3_000));
        ObjectNode receipts = (ObjectNode) root.get("receipts");
        ObjectNode duplicate = ((ObjectNode) receipts.get("e2")).deepCopy();
        duplicate.put("eventId", "duplicate-auth");
        duplicate.put("position", 3);
        receipts.set("duplicate-auth", duplicate);
        root.put("lastPosition", 3);
        rejectsState(root);
    }

    @Test
    void cancellationCannotCarryLaterAuthorizationReceipt() throws Exception {
        DividendModel.State proposed = DividendModel.propose(DividendModel.empty(), event("e1", 1, 900),
                request("S1", "0.10", 1_000, 2_000, 3_000)).state();
        DividendModel.State cancelled = DividendModel.cancel(proposed, event("cancel", 2, 950), "S1", "withdrawn").state();
        ObjectNode root = jsonState(cancelled);
        ObjectNode receipts = (ObjectNode) root.get("receipts");
        ObjectNode authorization = ((ObjectNode) receipts.get("cancel")).deepCopy();
        authorization.put("eventId", "after-cancel");
        authorization.put("position", 3);
        authorization.put("action", "AUTHORIZE");
        authorization.put("requestJson", "{\"schemeId\":\"S1\",\"authorizationRef\":\"forged\"}");
        receipts.set("after-cancel", authorization);
        ((ObjectNode) root.get("schemes").get("S1")).put("authorizationRef", "forged");
        root.put("lastPosition", 3);
        rejectsState(root);
    }

    @Test
    void acceptedHistoryRejectsLateAuthorizationAndEarlyPaymentEvenWhenBalancesMatch() throws Exception {
        ObjectNode root = jsonState(authorized("S1", "0.10", 1_000, 2_000, 3_000));
        ((ObjectNode) root.get("receipts").get("e2")).put("atMillis", 1_001);
        root.put("lastAtMillis", 1_001);
        rejectsState(root);
        root = jsonState(settled("S1", "0.10", 1_000, 2_000, 3_000));
        ((ObjectNode) root.get("payments").get(0)).put("atMillis", 2_500);
        ((ObjectNode) root.get("receipts").get("e5")).put("atMillis", 2_500);
        root.put("lastAtMillis", 2_500);
        rejectsState(root);
    }

    @Test
    void acceptedReceiptCannotInventARejectionReasonOrMissingAmountHistory() throws Exception {
        ObjectNode root = jsonState(authorized("S1", "0.10", 1_000, 2_000, 3_000));
        ((ObjectNode) root.get("receipts").get("e2")).put("reason", "DENIED");
        rejectsState(root);
        root = jsonState(exBooked("S1", "0.10", 1_000, 2_000, 3_000));
        ObjectNode fake = ((ObjectNode) root.get("receipts").get("e4")).deepCopy();
        fake.put("eventId", "missing-impairment");
        fake.put("position", 5);
        fake.put("action", "IMPAIR");
        fake.put("requestJson", "{\"claimId\":\"2:S1:A\",\"amount\":1.00,\"evidenceRef\":\"evidence\"}");
        ((ObjectNode) root.get("receipts")).set("missing-impairment", fake);
        root.put("lastPosition", 5);
        rejectsState(root);
    }

    @Test
    void nullStateAndNullPrimitiveFieldsCannotBecomeAValidEmptyState() {
        assertThrows(IllegalArgumentException.class, () -> DividendModel.fromJson("null"));
        String empty = DividendModel.toJson(DividendModel.empty());
        assertThrows(IllegalArgumentException.class,
                () -> DividendModel.fromJson(empty.replace("\"lastPosition\":0", "\"lastPosition\":null")));
    }

    @Test
    void paymentConstructorNormalizesAllMoneyFields() {
        DividendModel.Payment payment = new DividendModel.Payment("pay", 1, "S", 1, "cash",
                new BigDecimal("1"), Map.of("claim", new BigDecimal("1")), Map.of("claim", BigDecimal.ZERO));
        assertEquals(new BigDecimal("1.00"), payment.perClaim().get("claim"));
        assertEquals(new BigDecimal("0.00"), payment.impairmentReleased().get("claim"));
    }

    @Test
    void neverAppliedRegistrationCannotBeReplayedAsAccepted() throws Exception {
        ObjectNode root = jsonState(authorized("S1", "0.10", 1_000, 2_000, 3_000));
        ObjectNode receipt = ((ObjectNode) root.get("receipts").get("e2")).deepCopy();
        receipt.put("eventId", "phantom-register");
        receipt.put("position", 3);
        receipt.put("action", "REGISTER");
        receipt.put("requestJson", "{\"schemeId\":\"S1\"}");
        ((ObjectNode) root.get("receipts")).set("phantom-register", receipt);
        root.put("lastPosition", 3);
        rejectsState(root);
    }

    @Test
    void paymentHistoryCannotBeRestoredWithAnUnsettledSchemePhase() throws Exception {
        ObjectNode root = jsonState(settled("S1", "0.10", 1_000, 2_000, 3_000));
        ((ObjectNode) root.get("schemes").get("S1")).put("phase", "EX_BOOKED");
        rejectsState(root);
    }

    private static ObjectNode jsonState(DividendModel.State state) throws Exception {
        return (ObjectNode) new ObjectMapper().readTree(DividendModel.toJson(state));
    }

    private static void rejectsState(ObjectNode node) {
        assertThrows(IllegalArgumentException.class, () -> DividendModel.fromJson(node.toString()));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static DividendModel.Event event(String eventId, long position, long atMillis) {
        return new DividendModel.Event(eventId, position, atMillis);
    }

    private static DividendModel.SchemeRequest request(String schemeId, String perShare,
                                                       long recordAt, long exAt, long payAt) {
        return new DividendModel.SchemeRequest(schemeId, SYM, ISSUER, COMPANY_ACC,
                new BigDecimal(perShare), recordAt, exAt, payAt);
    }

    private static DividendModel.State authorized(String schemeId, String perShare,
                                                  long recordAt, long exAt, long payAt) {
        DividendModel.State state = DividendModel.propose(DividendModel.empty(),
                event("e1", 1, recordAt), request(schemeId, perShare, recordAt, exAt, payAt)).state();
        return DividendModel.authorize(state, event("e2", 2, recordAt), schemeId, "board-resolution-7").state();
    }

    private static DividendModel.State registered(String schemeId, String perShare,
                                                  long recordAt, long exAt, long payAt) {
        DividendModel.State state = authorized(schemeId, perShare, recordAt, exAt, payAt);
        return DividendModel.register(state, event("e3", 3, recordAt), schemeId, holdings()).state();
    }

    private static DividendModel.State exBooked(String schemeId, String perShare,
                                                long recordAt, long exAt, long payAt) {
        DividendModel.State state = registered(schemeId, perShare, recordAt, exAt, payAt);
        return DividendModel.bookEx(state, event("e4", 4, exAt), schemeId).state();
    }

    private static DividendModel.State settled(String schemeId, String perShare,
                                               long recordAt, long exAt, long payAt) {
        DividendModel.State state = exBooked(schemeId, perShare, recordAt, exAt, payAt);
        Map<String, BigDecimal> perClaim = fullPayment(state, schemeId);
        return DividendModel.settle(state, event("e5", 5, payAt), schemeId,
                "cash-batch-1", total(perClaim), perClaim).state();
    }

    private static Map<String, BigDecimal> fullPayment(DividendModel.State state, String schemeId) {
        Map<String, BigDecimal> perClaim = new LinkedHashMap<>();
        for (DividendModel.Claim claim : DividendModel.claims(state, schemeId)) {
            perClaim.put(claim.claimId(), DividendModel.faceUnpaid(claim));
        }
        return perClaim;
    }

    private static BigDecimal total(Map<String, BigDecimal> perClaim) {
        BigDecimal sum = new BigDecimal("0.00");
        for (BigDecimal amount : perClaim.values()) {
            sum = sum.add(amount);
        }
        return sum;
    }

    private static String claimIdOf(DividendModel.State state, String schemeId, String accountId) {
        for (DividendModel.Claim claim : DividendModel.claims(state, schemeId)) {
            if (claim.accountId().equals(accountId)) {
                return claim.claimId();
            }
        }
        throw new AssertionError("找不到账户 " + accountId + " 的 claim");
    }

    private static BigDecimal faceOf(DividendModel.State state, String schemeId, String accountId) {
        return DividendModel.claim(state, claimIdOf(state, schemeId, accountId)).face();
    }

    private static long quantityOf(DividendModel.RightsSnapshot snapshot, String accountId) {
        DividendModel.RightsEntry entry = entryOf(snapshot, accountId);
        assertNotNull(entry, "快照缺少账户 " + accountId);
        return entry.quantity();
    }

    private static DividendModel.RightsEntry entryOf(DividendModel.RightsSnapshot snapshot,
                                                    String accountId) {
        for (DividendModel.RightsEntry entry : snapshot.entries()) {
            if (entry.accountId().equals(accountId)) {
                return entry;
            }
        }
        return null;
    }

    private static SecuritiesLedger.Lot lot(String lotId, String accountId, long quantity) {
        return new SecuritiesLedger.Lot(lotId, accountId, SYM, quantity, 0L, 0L, 0L,
                new BigDecimal("10.0000"), ShareClass.FLOAT);
    }

    private static SecuritiesLedger.State emptyHoldings() {
        return new SecuritiesLedger.State(List.of(), List.of(), Set.of(), Map.of());
    }

    /**
     * 有限库存夹具：A 100 股（其中 30 股被真实卖单预留冻结）、B 60 股、
     * B 当日卖出 40 股给 D（D 的批次次日才可卖）、C 50 股 LOCKED、ACME 100 股 TREASURY。
     */
    private static SecuritiesLedger.State holdings() {
        SecuritiesLedger.State base = SecuritiesLedger.opening(registry());
        SecuritiesLedger.State reservedA = SecuritiesLedger.reserve(
                base, "res-A", "A", SYM, 30L, 0L).state();
        SecuritiesLedger.State reservedB = SecuritiesLedger.reserve(
                reservedA, "res-B", "B", SYM, 40L, 0L).state();
        SecuritiesLedger.FillOutcome filled = SecuritiesLedger.fill(
                reservedB, "task-d", "res-B", "D", new BigDecimal("10.00"), 40L, 0L);
        assertTrue(filled.accepted(), "夹具前提：当日卖出 40 股给 D 必须成功");
        return filled.state();
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "Alice"),
                        new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "Bob"),
                        new OpeningRegistry.Party("carol", OpeningRegistry.PartyKind.PERSON, "Carol"),
                        new OpeningRegistry.Party("acme", OpeningRegistry.PartyKind.ORGANIZATION, "Acme")),
                List.of(new OpeningRegistry.PropertyOwner("alice",
                                OpeningRegistry.OwnerKind.PERSON, "alice"),
                        new OpeningRegistry.PropertyOwner("bob",
                                OpeningRegistry.OwnerKind.PERSON, "bob"),
                        new OpeningRegistry.PropertyOwner("carol",
                                OpeningRegistry.OwnerKind.PERSON, "carol"),
                        new OpeningRegistry.PropertyOwner("acme",
                                OpeningRegistry.OwnerKind.ORGANIZATION, "acme")),
                List.of(new OpeningRegistry.Security(SYM, ISSUER, 350L, new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", SYM, 100L,
                                new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("B", SYM, 100L,
                                new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("C", SYM, 50L,
                                new BigDecimal("10.0000"), ShareClass.LOCKED),
                        new OpeningRegistry.ShareAllocation("ACME", SYM, 100L,
                                new BigDecimal("10.0000"), ShareClass.TREASURY)));
    }
}
