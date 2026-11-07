package edu.cufe.auction.world;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3b：现金分红世界桥（测试先行）。
 *
 * <p>覆盖正式施工单 `docs/superpowers/plans/2026-11-06-dividend-runtime.md`：
 * schema9 新档案与 dividend 模块、旧快照/旧构造兼容、公告发布与阅读边界、真实授权链、
 * 登记/除息/付款同一批、资金不足失败与重试、减值与转回、同截止估值、跨 owner 转账拒绝、
 * 恢复期伪造拒绝。</p>
 *
 * <p>所有状态推进都通过真实公开入口与 {@code advanceTo}；不使用私有探针，也不篡改快照来制造
 * 现金或股份。</p>
 */
@DisplayName("WorldRuntime：现金分红世界桥")
class WorldRuntimeDividendTest {

    private static final String RUN = "dividend-runtime";
    private static final long SEED = 11L;
    private static final String SYM = "CUFE1";
    private static final String COMPANY_PARTY = "cufe";
    private static final String COMPANY_OWNER = "company";
    private static final String COMPANY_ACC = "CO";
    private static final String INV_PARTY = "investor";
    private static final String ACC_INV = "INV";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";
    private static final String RULES_DIVIDEND = "dividend-world-experiment-v1";

    private static final long T0 = 1_000L;
    private static final long MATERIAL = T0 + 5_000L;
    private static final long OPENS = T0 + 10_000L;
    private static final long CLOSES = T0 + 20_000L;
    private static final long AUTH = CLOSES + 1L;
    private static final long PUBLISH = AUTH + 1L;
    private static final long RECORD = AUTH + 2L;
    private static final long EX = RECORD + 1_000L;
    private static final long PAY = EX + 1_000L;

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 档案、兼容与开局
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新档案开局带空分红状态，并沿用原九参配置")
    void dividendProfileStartsWithEmptyDividendState() throws IOException {
        try (WorldRuntime runtime = open("open.journal")) {
            assertTrue(runtime.snapshot().dividends().isPresent(), "新档案必须暴露 D0 状态");
            DividendModel.State dividends = runtime.snapshot().dividends().orElseThrow();
            assertTrue(dividends.schemes().isEmpty(), "开局没有方案");
            assertTrue(dividends.claims().isEmpty());
            assertTrue(runtime.dividendReports().isEmpty(), "开局没有分红报告");
        }
    }

    @Test
    @DisplayName("旧档案不暴露分红状态，新命令一律拒绝")
    void legacyProfileStaysWithoutDividend() throws IOException {
        try (WorldRuntime runtime = openLegacy("legacy.journal")) {
            assertTrue(runtime.snapshot().dividends().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> runtime.dividendReports());
            assertThrows(IllegalArgumentException.class, () -> runtime.valuation(INV_PARTY));
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleDividendAuthorization(
                    "c-legacy", "t-legacy", 10L, "s1", "m-legacy"));
        }
    }

    @Test
    @DisplayName("旧 15 参快照与旧 6 参市场构造仍可调用")
    void legacyConstructorsRemainSourceCompatible() {
        WorldRuntime.Snapshot snapshot = new WorldRuntime.Snapshot(0L, 0L, "OPEN",
                Map.of("A", new CashAccount("o", new BigDecimal("0.00"), "夹具")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), List.of(), java.util.Set.of());
        assertTrue(snapshot.dividends().isEmpty(), "旧构造默认没有分红状态");

        RoleView.Market market = new RoleView.Market(SYM, new BigDecimal("10.00"), 1L, 0L,
                new BigDecimal("9.99"), new BigDecimal("10.01"));
        assertEquals(null, market.reference(), "旧构造默认没有除息参考");
    }

    @Test
    @DisplayName("开局拒绝发行人自持本股流通股，库存股合法")
    void selfFloatHoldingIsRejectedButTreasuryIsAllowed() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> open("self-float.journal", registryWithCompanyFloat()).close());
        try (WorldRuntime runtime = open("self-treasury.journal", registryWithTreasury())) {
            assertNotNull(runtime.snapshot().securities().orElseThrow());
        }
    }

    // ------------------------------------------------------------------
    // 会议、授权与公告
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完整真实链：召集→提案→三票→自动关会→授权→次毫秒公告→登记→除息→付款")
    void fullChainRunsThroughPayment() throws IOException {
        try (WorldRuntime runtime = open("full.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);

            runtime.advanceTo(CLOSES);
            assertEquals(BoardModel.MeetingStatus.ADOPTED,
                    meeting(runtime, "m1").status(), "三票应通过");

            runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
            runtime.advanceTo(AUTH);
            assertEquals(BoardModel.MeetingStatus.IMPLEMENTED,
                    meeting(runtime, "m1").status(), "授权批应实施董事会决议");
            assertEquals(DividendModel.Phase.AUTHORIZED, scheme(runtime, "S1").phase());

            runtime.advanceTo(PUBLISH);
            assertTrue(published(runtime, "dividend-public:S1"), "授权后下一个模拟毫秒必须实际发表");

            runtime.advanceTo(RECORD);
            assertEquals(DividendModel.Phase.REGISTERED, scheme(runtime, "S1").phase());

            runtime.advanceTo(EX);
            assertEquals(DividendModel.Phase.EX_BOOKED, scheme(runtime, "S1").phase());
            assertEquals(1, DividendModel.claims(dividends(runtime), "S1").size());

            runtime.advanceTo(PAY);
            assertEquals(DividendModel.Phase.SETTLED, scheme(runtime, "S1").phase());
            assertEquals(0, cash(runtime, ACC_INV).compareTo(new BigDecimal("200.00")),
                    "200 股 × 1.00 应真实到账");
            assertEquals(0, cash(runtime, COMPANY_ACC).compareTo(new BigDecimal("800.00")));
            assertTrue(runtime.dividendReports().stream()
                    .anyMatch(report -> report.kind().contains("DIVIDEND") && report.accepted()));
        }
    }

    @Test
    @DisplayName("最紧合法时序：close=t、auth=t+1、publish=t+2、record=t+3")
    void tightestWindowIsAccepted() throws IOException {
        try (WorldRuntime runtime = open("tight.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", CLOSES + 3, CLOSES + 1_000,
                    CLOSES + 2_000, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runtime.advanceTo(CLOSES);

            runtime.scheduleDividendAuthorization("c-auth", "t-auth", CLOSES + 1, "s1", "m1");
            runtime.advanceTo(CLOSES + 1);
            assertEquals(DividendModel.Phase.AUTHORIZED, scheme(runtime, "S1").phase());
            runtime.advanceTo(CLOSES + 2);
            assertTrue(published(runtime, "dividend-public:S1"));
            runtime.advanceTo(CLOSES + 3);
            assertEquals(DividendModel.Phase.REGISTERED, scheme(runtime, "S1").phase());
        }
    }

    @Test
    @DisplayName("授权早于关会或迟于可公告窗口都拒绝")
    void authorizationWindowIsEnforced() throws IOException {
        try (WorldRuntime runtime = open("window.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runtime.scheduleDividendAuthorization("c-early", "t-early", CLOSES, "s1", "m1");
            runtime.scheduleDividendAuthorization("c-late", "t-late", RECORD - 1, "s1", "m1");
            runtime.advanceTo(RECORD - 1);
            assertEquals(DividendModel.Phase.PROPOSED, scheme(runtime, "S1").phase());
            assertEquals(BoardModel.MeetingStatus.ADOPTED, meeting(runtime, "m1").status());
            for (String taskId : List.of("t-early", "t-late")) {
                assertTrue(runtime.dividendReports().stream().anyMatch(report -> report.taskId().equals(taskId)
                        && !report.accepted()), "到期拒绝必须持久报告：" + taskId);
            }
        }
    }

    @Test
    @DisplayName("否决后同经济重审复用原 PROPOSED，不重复 propose")
    void rejectedMeetingIsReReviewedWithoutSecondProposal() throws IOException {
        try (WorldRuntime runtime = open("rereview.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", 100_000, 110_000, 120_000, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2"), OPENS + 100);
            runtime.advanceTo(CLOSES);
            assertEquals(BoardModel.MeetingStatus.REJECTED, meeting(runtime, "m1").status());

            scheduleMeeting(runtime, "m2", CLOSES + 10, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            runtime.scheduleDividendProposal("c-prop2", "t-prop2", CLOSES + 11, "s1", "m2",
                    List.of(), schemeRequest("S1", "1.00", 100_000, 110_000, 120_000));
            runtime.advanceTo(CLOSES + 11);

            assertNotNull(meeting(runtime, "m2").dividendProposal());
            assertEquals(DividendModel.Phase.PROPOSED, scheme(runtime, "S1").phase());
            long proposes = dividends(runtime).receipts().values().stream()
                    .filter(receipt -> "PROPOSE".equals(receipt.action())).count();
            assertEquals(1L, proposes, "同一方案只在 D0 受理一次");
        }
    }

    @Test
    @DisplayName("关联到会者回避后不足三人只能延期")
    void recusalBelowQuorumAdjourns() throws IOException {
        try (WorldRuntime runtime = open("adjourn.journal")) {
            runtime.scheduleBoardMeeting("c-convene", "t-convene", T0, "s1", "m1",
                    MATERIAL, OPENS, CLOSES, List.of("s1", "s2", "s3"), List.of("s2"),
                    BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            runtime.scheduleDividendProposal("c-prop", "t-prop", T0 + 1, "s1", "m1",
                    List.of("s2"), schemeRequest("S1", "1.00", RECORD, EX, PAY));
            vote(runtime, "m1", List.of("s1"), OPENS + 100);
            runtime.advanceTo(CLOSES);
            assertEquals(BoardModel.MeetingStatus.ADJOURNED_NO_QUORUM,
                    meeting(runtime, "m1").status());
        }
    }

    @Test
    @DisplayName("同命令同内容返回原 memo，异内容复用拒绝")
    void commandMemoIsIdempotent() throws IOException {
        try (WorldRuntime runtime = open("memo.journal")) {
            String first = runtime.scheduleBoardMeeting("c-convene", "t-convene", T0, "s1", "m1",
                    MATERIAL, OPENS, CLOSES, List.of("s1", "s2", "s3"), List.of(),
                    BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            String replay = runtime.scheduleBoardMeeting("c-convene", "t-convene", T0, "s1", "m1",
                    MATERIAL, OPENS, CLOSES, List.of("s1", "s2", "s3"), List.of(),
                    BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            assertEquals(first, replay);
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleBoardMeeting(
                    "c-convene", "t-convene", T0 + 1, "s1", "m1", MATERIAL, OPENS, CLOSES,
                    List.of("s1", "s2", "s3"), List.of(), BoardModel.Topic.DIVIDEND_AUTHORIZATION));
        }
    }

    // ------------------------------------------------------------------
    // 登记、除息与估值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("登记计入冻结与 LOCKED，排除库存股")
    void registrationCountsFrozenAndLockedShares() throws IOException {
        try (WorldRuntime runtime = open("register.journal", registryWithLocked())) {
            runtime.scheduleOrder("c-freeze", "t-freeze", T0 + 10, INV_PARTY, ACC_INV,
                    SYM, Side.SELL, OrderType.LIMIT, new BigDecimal("10.00"), 30L);
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 20);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToRegistration(runtime);

            assertTrue(DividendModel.claims(dividends(runtime), "S1").isEmpty());
            assertEquals(200L, DividendModel.snapshot(dividends(runtime), "S1").entries().stream()
                            .mapToLong(DividendModel.RightsEntry::quantity).sum(),
                    "150 流通（含冻结 30）+ 50 LOCKED 全部计入");
            runtime.advanceTo(EX);
            assertEquals(200, DividendModel.claims(dividends(runtime), "S1").get(0).quantity());
        }
    }

    @Test
    @DisplayName("登记后卖出不转移既有权利")
    void resaleAfterRegistrationKeepsOriginalRight() throws IOException {
        try (WorldRuntime runtime = open("resale.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToRegistration(runtime);
            long registered = DividendModel.snapshot(dividends(runtime), "S1").entries().stream()
                    .mapToLong(DividendModel.RightsEntry::quantity).sum();

            runtime.scheduleOrder("c-sell", "t-sell", RECORD + 10, INV_PARTY, ACC_INV,
                    SYM, Side.SELL, OrderType.LIMIT, new BigDecimal("10.00"), 100L);
            runtime.scheduleOrder("c-buy", "t-buy", RECORD + 11, "customer", "CUS",
                    SYM, Side.BUY, OrderType.LIMIT, new BigDecimal("10.00"), 100L);
            runtime.advanceTo(RECORD + 12);

            assertEquals(registered,
                    DividendModel.snapshot(dividends(runtime), "S1").entries().stream()
                            .mapToLong(DividendModel.RightsEntry::quantity).sum(),
                    "登记后转卖不改变已固定权利");
            runtime.advanceTo(PAY);
            assertEquals(0, cash(runtime, ACC_INV).compareTo(new BigDecimal("1200.00")),
                    "红利仍付给登记持有人");
            assertEquals(200, DividendModel.claims(dividends(runtime), "S1").get(0).quantity());
        }
    }

    @Test
    @DisplayName("除息参考与应收组合回除息前价值")
    void exReferenceAndReceivableSumToPreExValue() throws IOException {
        try (WorldRuntime runtime = open("valuation.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToEx(runtime);

            ValuationModel.Value value = runtime.valuation(INV_PARTY);
            assertEquals(ValuationModel.PriceSource.EX_DIVIDEND_REFERENCE,
                    market(runtime, ALICE).reference().source());
            assertEquals(0, value.holdings().compareTo(new BigDecimal("1800.00")),
                    "200 股 × 9.00");
            assertEquals(0, value.dividendReceivable().compareTo(new BigDecimal("200.00")));
            assertEquals(0, value.netWorth().compareTo(new BigDecimal("2000.00")));
        }
    }

    @Test
    @DisplayName("payAt 等于 exAt 时先除息再付款")
    void paymentAtExDateBooksExFirst() throws IOException {
        try (WorldRuntime runtime = open("same-time.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, EX, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToRegistration(runtime);
            runtime.advanceTo(EX);

            assertEquals(DividendModel.Phase.SETTLED, scheme(runtime, "S1").phase(),
                    "同刻必须先 EX_BOOKED 再付款");
            assertEquals(0, cash(runtime, ACC_INV).compareTo(new BigDecimal("200.00")));
        }
    }

    // ------------------------------------------------------------------
    // 付款、重试与减值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("公司现金不足时整批不付，D0 保持 EX_BOOKED 并有失败报告")
    void insufficientCashFailsWholeBatch() throws IOException {
        try (WorldRuntime runtime = open("short.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "6.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToEx(runtime);
            runtime.advanceTo(PAY);

            assertEquals(DividendModel.Phase.EX_BOOKED, scheme(runtime, "S1").phase(),
                    "资金不足不得推进到已清偿");
            assertEquals(0, cash(runtime, ACC_INV).signum(), "整批不付");
            assertEquals(0, cash(runtime, COMPANY_ACC).compareTo(new BigDecimal("1000.00")));
            assertTrue(runtime.dividendReports().stream()
                    .anyMatch(report -> !report.accepted() && "S1".equals(report.schemeId())));
        }
    }

    @Test
    @DisplayName("仍不足时新命令继续失败，旧命令重放不产生第二次付款")
    void retryKeepsFailingWhileCashShort() throws IOException {
        try (WorldRuntime runtime = open("retry.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "6.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToEx(runtime);
            runtime.advanceTo(PAY);

            String memo = runtime.scheduleDividendSettlement("c-retry", "t-retry", PAY + 10,
                    COMPANY_OWNER, "S1");
            runtime.advanceTo(PAY + 10);
            assertEquals(memo, runtime.scheduleDividendSettlement("c-retry", "t-retry", PAY + 10,
                    COMPANY_OWNER, "S1"), "旧命令重放返回原 memo");
            assertEquals(0, cash(runtime, ACC_INV).signum(), "重放不得付款");
            assertEquals(DividendModel.Phase.EX_BOOKED, scheme(runtime, "S1").phase());
        }
    }

    @Test
    @DisplayName("减值只降投资人净应收，足额付款后转回")
    void impairmentReducesInvestorOnlyAndPaymentReversesIt() throws IOException {
        try (WorldRuntime runtime = open("impair.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToEx(runtime);

            DividendModel.Claim claim = DividendModel.claims(dividends(runtime), "S1").get(0);
            runtime.scheduleDividendImpairment("c-imp", "t-imp", EX + 10, INV_PARTY,
                    claim.claimId(), new BigDecimal("50.00"), "评估证据");
            runtime.advanceTo(EX + 10);

            ValuationModel.Value impaired = runtime.valuation(INV_PARTY);
            assertEquals(0, impaired.dividendReceivable().compareTo(new BigDecimal("150.00")));
            assertEquals(0, DividendModel.companyPayable(dividends(runtime), "S1")
                    .compareTo(new BigDecimal("200.00")), "公司合同债务不变");

            runtime.advanceTo(PAY);
            assertEquals(0, cash(runtime, ACC_INV).compareTo(new BigDecimal("200.00")),
                    "足额付款按面值支付，不按净应收少付");
            assertEquals(0, DividendModel.claim(dividends(runtime), claim.claimId())
                    .impairment().signum(), "付款后减值转回");
        }
    }

    @Test
    @DisplayName("减值必须由原 claim 账户 owner 发起")
    void impairmentRequiresHolderOwner() throws IOException {
        try (WorldRuntime runtime = open("impair-actor.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToEx(runtime);

            String claimId = DividendModel.claims(dividends(runtime), "S1").get(0).claimId();
            runtime.scheduleDividendImpairment("c-imp", "t-imp", EX + 10,
                    COMPANY_OWNER, claimId, new BigDecimal("50.00"), "证据");
            runtime.advanceTo(EX + 10);
            assertEquals(0, DividendModel.claim(dividends(runtime), claimId).impairment().signum());
            assertTrue(runtime.dividendReports().stream().anyMatch(report -> report.taskId().equals("t-imp")
                    && !report.accepted() && WorldRuntime.REASON_NOT_AUTHORIZED.equals(report.reason())));
        }
    }

    // ------------------------------------------------------------------
    // 分类、旧行为与恢复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新档案拒绝跨 owner 无分类转账，旧档案保持原行为")
    void crossOwnerTransferIsRejectedInDividendProfile() throws IOException {
        try (WorldRuntime runtime = open("transfer.journal")) {
            runtime.scheduleTransfer("c-tr", "t-tr", T0, "supplier", "SUP", ACC_INV,
                    new BigDecimal("100.00"), 1L);
            runtime.advanceTo(T0 + 1);
            assertEquals(0, cash(runtime, ACC_INV).signum());
            assertEquals(new BigDecimal("100.00"), cash(runtime, "SUP"));
            assertTrue(runtime.snapshot().delivered().stream().anyMatch(notice -> notice.taskId().equals("t-tr")
                    && !notice.accepted() && "TRANSFER_UNCLASSIFIED".equals(notice.reason())));
        }
        try (WorldRuntime legacy = openLegacy("legacy-transfer.journal")) {
            legacy.scheduleTransfer("c-tr", "t-tr", T0, "supplier", "SUP", ACC_INV,
                    new BigDecimal("100.00"), 1L);
            legacy.advanceTo(T0 + 1);
            assertEquals(new BigDecimal("100.00"), cash(legacy, ACC_INV));
        }
    }

    @Test
    @DisplayName("恢复期重签伪造：登记股数、现金证据与未知任务都拒绝，合法控制先通过")
    void restoreRejectsForgedDividendFacts() throws IOException {
        Path source = tempDir.resolve("source.journal");
        try (WorldRuntime runtime = open(source)) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToSettled(runtime);
        }

        try (WorldRuntime legal = open(source)) {
            assertEquals(DividendModel.Phase.SETTLED, scheme(legal, "S1").phase(),
                    "合法控制必须先通过");
        }

        Path forgedQuantity = tamperSnapshots(source, "forged-quantity.journal",
                root -> root.path("dividends").path("claims").size() > 0,
                root -> firstClaim(root).put("quantity", 999));
        assertThrows(IOException.class, () -> open(forgedQuantity).close());

        Path forgedEvidence = tamperSnapshots(source, "forged-evidence.journal",
                root -> root.path("dividends").path("payments").size() > 0,
                root -> ((ObjectNode) root.path("dividends").path("payments").get(0))
                        .put("cashEvidenceId", "forged-evidence"));
        assertThrows(IOException.class, () -> open(forgedEvidence).close());
    }

    @Test
    void dividendProfileRequiresChairInInformationReadersBeforeCreatingJournal() throws IOException {
        List<String> readers = List.of(BOB, CAROL, DAVE, ERIN);
        InformationRun.Config info = new InformationRun.Config(readers);
        try (WorldRuntime legacy = new WorldRuntime(tempDir.resolve("legacy-no-chair.journal"),
                legacyManifest(), openings(), registry(), MFG, charter(), roles(), info, decisions(readers))) {
            assertFalse(legacy.snapshot().information().orElseThrow().information().persons().contains(ALICE));
        }
        Path rejected = tempDir.resolve("no-chair.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(rejected, manifest(),
                openings(), registry(), MFG, charter(), roles(), info, decisions(readers)).close());
        assertFalse(Files.exists(rejected));
    }

    @Test
    void publicAnnouncementBecomesReadableOnlyAfterActualReading() throws IOException {
        try (WorldRuntime runtime = open("announcement-reading.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
            runtime.advanceTo(AUTH);
            assertFalse(published(runtime, "dividend-public:S1"));
            runtime.advanceTo(PUBLISH);
            InformationModel.Document document = runtime.snapshot().information().orElseThrow().information()
                    .documents().stream().filter(doc -> doc.docId().equals("dividend-public:S1")).findFirst().orElseThrow();
            assertEquals(ALICE, document.publisherPartyId());
            assertEquals(InformationModel.AudienceKind.PUBLIC, document.audience().kind());
            assertEquals(60_000, document.versions().get(0).readDurationMillis());
            assertTrue(runtime.roleView(ALICE).catalog().stream().anyMatch(entry -> entry.docId().equals(document.docId())));
            assertTrue(runtime.roleView(ALICE).observations().isEmpty());
            runtime.scheduleStartReading("c-read-announcement", "t-read-announcement", PUBLISH + 1,
                    ALICE, document.docId(), 1);
            runtime.advanceTo(PUBLISH + 60_000);
            assertTrue(runtime.roleView(ALICE).observations().isEmpty());
            runtime.advanceTo(PUBLISH + 60_001);
            assertEquals(document.versions().get(0).body(), runtime.roleView(ALICE).observations().get(0).body());
            assertEquals(DividendModel.Phase.SETTLED, scheme(runtime, "S1").phase());
        }
    }

    @Test
    void reservedAnnouncementSpaceCannotBeOccupiedByGenericPublication() throws IOException {
        try (WorldRuntime runtime = open("protected-doc.journal")) {
            assertThrows(IllegalArgumentException.class, () -> runtime.schedulePublishDocument(
                    "c-publish", "t-publish", T0, "dividend-public:S1", ALICE,
                    InformationModel.Audience.publicAudience(), "标题", "正文", "来源", 1, List.of()));
        }
        try (WorldRuntime legacy = openLegacy("legacy-protected-doc.journal")) {
            legacy.schedulePublishDocument("c-publish", "t-publish", T0, "dividend-public:S1", ALICE,
                    InformationModel.Audience.publicAudience(), "标题", "正文", "来源", 1, List.of());
            legacy.advanceTo(T0);
            assertTrue(published(legacy, "dividend-public:S1"));
        }
    }

    @Test
    void sameDayBoughtSharesReceiveRightsDespiteBeingUnsellable() throws IOException {
        try (WorldRuntime runtime = open("bought-rights.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runtime.scheduleOrder("c-sell", "t-sell", T0 + 10, INV_PARTY, ACC_INV,
                    SYM, Side.SELL, OrderType.LIMIT, new BigDecimal("10.00"), 20);
            runtime.scheduleOrder("c-buy", "t-buy", T0 + 11, "customer", "CUS",
                    SYM, Side.BUY, OrderType.LIMIT, new BigDecimal("10.00"), 20);
            runToEx(runtime);
            assertEquals(0, SecuritiesLedger.available(runtime.snapshot().securities().orElseThrow(),
                    "CUS", SYM, SecuritiesLedger.day(EX)));
            DividendModel.Claim claim = DividendModel.claims(dividends(runtime), "S1").stream()
                    .filter(entry -> entry.accountId().equals("CUS")).findFirst().orElseThrow();
            assertEquals(20, claim.quantity());
            assertEquals(new BigDecimal("20.00"), claim.face());
            assertEquals(new BigDecimal("2000.00"), runtime.valuation("customer").netWorth());
        }
    }

    @Test
    void allTreasuryRegistrationReportsNoHoldersAndNeverAttemptsPayment() throws IOException {
        OpeningRegistry allTreasury = registry(List.of(new OpeningRegistry.ShareAllocation(
                COMPANY_ACC, SYM, 200, new BigDecimal("10.0000"), ShareClass.TREASURY)));
        try (WorldRuntime runtime = open("no-holders.journal", allTreasury)) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToSettled(runtime);
            assertEquals(DividendModel.Phase.AUTHORIZED, scheme(runtime, "S1").phase());
            assertTrue(runtime.dividendReports().stream().anyMatch(report -> !report.accepted()
                    && DividendModel.REASON_NO_HOLDERS.equals(report.reason())));
            assertTrue(dividends(runtime).claims().isEmpty());
            assertTrue(dividends(runtime).payments().isEmpty());
            assertEquals(new BigDecimal("1000.00"), cash(runtime, COMPANY_ACC));
        }
    }

    @Test
    void paymentEvidenceReferencesAnActualConfirmedCashEvent() throws IOException {
        Path source = completedSource("payment-proof.journal");
        List<DividendModel.Payment> payments;
        try (WorldRuntime runtime = open(source)) {
            payments = dividends(runtime).payments();
            long position = runtime.snapshot().position();
            assertThrows(UnsupportedOperationException.class, () -> runtime.dividendReports().clear());
            runtime.valuation(INV_PARTY);
            assertEquals(position, runtime.snapshot().position());
        }
        try (WorldJournal journal = new WorldJournal(source, RUN)) {
            for (DividendModel.Payment payment : payments) {
                assertTrue(journal.commits().stream().flatMap(commit -> commit.events().stream())
                        .anyMatch(event -> event.eventId().equals(payment.cashEvidenceId())
                                && event.type().equals("DIVIDEND_CASH_PAYMENT_V1")));
            }
        }
    }

    @Test
    void reportForUnknownTaskCannotBeRestoredWithValidFrameHashes() throws IOException {
        Path source = completedSource("unknown-report-source.journal");
        Path forged = tamperEvents(source, "unknown-report.journal", "DIVIDEND_REPORT_V1",
                report -> report.put("taskId", "missing-original-task"));
        assertThrows(IOException.class, () -> open(forged).close());
    }

    @Test
    void falseFailedPaymentReportCannotOverrideActualSuccessfulState() throws IOException {
        Path source = completedSource("failed-report-source.journal");
        boolean[] changed = {false};
        Path forged = tamperEvents(source, "failed-report.journal", "DIVIDEND_REPORT_V1", report -> {
            if (report.path("kind").asText().equals("DIVIDEND_SETTLE_V1")) {
                report.put("accepted", false);
                report.put("reason", "INSUFFICIENT_CASH");
                changed[0] = true;
            }
        });
        assertTrue(changed[0], "必须找到真实付款报告再篡改");
        assertThrows(IOException.class, () -> open(forged).close());
    }

    @Test
    void failedPaymentCanBeRetriedAfterRealManufacturingCollection() throws IOException {
        long day = ManufacturingModel.DAY_MILLIS;
        try (WorldRuntime runtime = open("real-replenishment.journal")) {
            runtime.scheduleBoardMeeting("c-budget", "t-budget", 10, "s1", "budget", 20, 30, 40,
                    List.of("s1", "s2", "s3"), List.of());
            runtime.scheduleBudgetProposal("c-budget-proposal", "t-budget-proposal", 11, "s1", "budget",
                    List.of(new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT, new BigDecimal("500.00")),
                            new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR, new BigDecimal("300.00"))));
            vote(runtime, "budget", List.of("s1", "s2", "s3"), 31);
            runtime.scheduleBudgetImplementation("c-budget-implement", "t-budget-implement", 41, "s1", "budget");
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "6.00", day, 2 * day, 25 * day, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
            runtime.scheduleSalesContract("c-contract", "t-contract", 16 * day, COMPANY_OWNER, "contract", 10,
                    new BigDecimal("120.00"), 20 * day);
            runtime.schedulePurchaseOrder("c-purchase", "t-purchase", 17 * day, COMPANY_OWNER, "purchase", 10);
            runtime.scheduleProduction("c-production", "t-production", 18 * day + 1, COMPANY_OWNER, "production", 10);
            runtime.scheduleDelivery("c-delivery", "t-delivery", 20 * day, COMPANY_OWNER, "contract", 10, "receivable");
            runtime.advanceTo(25 * day);
            assertEquals(DividendModel.Phase.EX_BOOKED, scheme(runtime, "S1").phase());
            assertEquals(new BigDecimal("0.00"), cash(runtime, ACC_INV));
            runtime.advanceTo(35 * day);
            BigDecimal beforeRetry = cash(runtime, COMPANY_ACC);
            assertEquals(new BigDecimal("1200.00"), beforeRetry);
            String memo = runtime.scheduleDividendSettlement("c-retry-real", "t-retry-real", 35 * day + 1,
                    COMPANY_OWNER, "S1");
            runtime.advanceTo(35 * day + 1);
            assertEquals(DividendModel.Phase.SETTLED, scheme(runtime, "S1").phase());
            assertEquals(new BigDecimal("1200.00"), cash(runtime, ACC_INV));
            assertEquals(beforeRetry.subtract(new BigDecimal("1200.00")), cash(runtime, COMPANY_ACC));
            long position = runtime.snapshot().position();
            assertEquals(memo, runtime.scheduleDividendSettlement("c-retry-real", "t-retry-real", 35 * day + 1,
                    COMPANY_OWNER, "S1"));
            assertEquals(position, runtime.snapshot().position());
        }
    }

    @Test
    void periodSolvencyIncludesUnpaidDividendFace() throws IOException {
        long day = ManufacturingModel.DAY_MILLIS;
        try (WorldRuntime runtime = open("solvency-dividend.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "6.00", day, 2 * day, 25 * day, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
            runtime.advanceTo(30 * day + 1);
            assertEquals(new BigDecimal("1200.00"), DividendModel.companyPayable(dividends(runtime), "S1"));
            assertEquals(new BigDecimal("750.00"), cash(runtime, COMPANY_ACC));
            assertEquals(new BigDecimal("62.50"), runtime.snapshot().governance().orElseThrow()
                    .evidence(0).orElseThrow().cashSolvency());
        }
    }

    private Path completedSource(String name) throws IOException {
        Path source = tempDir.resolve(name);
        try (WorldRuntime runtime = open(source)) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToEx(runtime);
            runtime.advanceTo(EX + 1);
            runtime.advanceTo(PAY);
        }
        try (WorldRuntime legal = open(source)) {
            assertEquals(DividendModel.Phase.SETTLED, scheme(legal, "S1").phase());
        }
        return source;
    }

    @Test
    void registrationMustUseTheConfirmedWorldSecurities() throws IOException {
        rejectDividendPrefix("REGISTER", (previous, current, root, events) -> {
            OpeningRegistry actual = registry();
            OpeningRegistry alternative = new OpeningRegistry(actual.parties(), actual.owners(),
                    actual.securities(), List.of(new OpeningRegistry.ShareAllocation("CUS", SYM,
                    200L, new BigDecimal("10.0000"), ShareClass.FLOAT)));
            DividendModel.Result result = DividendModel.register(previous.dividends(),
                    current.event(), "S1", SecuritiesLedger.opening(alternative));
            assertTrue(result.accepted());
            assertEquals(result.state(), DividendModel.fromJson(DividendModel.toJson(result.state())));
            root.set("dividends", dividendJson().valueToTree(result.state()));
        });
    }

    @Test
    void paymentMustReferenceTheActualSameBatchCashEvidence() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) -> {
            Map<String, BigDecimal> claims = new TreeMap<>();
            BigDecimal total = new BigDecimal("0.00");
            for (DividendModel.Claim claim : DividendModel.claims(previous.dividends(), "S1")) {
                BigDecimal amount = DividendModel.faceUnpaid(claim);
                claims.put(claim.claimId(), amount);
                total = total.add(amount);
            }
            DividendModel.Result result = DividendModel.settle(previous.dividends(), current.event(),
                    "S1", "nonexistent-cash-event", total, claims);
            assertTrue(result.accepted());
            assertEquals(result.state(), DividendModel.fromJson(DividendModel.toJson(result.state())));
            root.set("dividends", dividendJson().valueToTree(result.state()));
        });
    }

    @Test
    void conservedCashWithTheWrongDividendRecipientCannotBeRestored() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) -> {
            ObjectNode investor = (ObjectNode) root.path("accounts").path(ACC_INV);
            ObjectNode supplier = (ObjectNode) root.path("accounts").path("SUP");
            investor.put("cash", investor.path("cash").decimalValue().subtract(new BigDecimal("1.00")));
            supplier.put("cash", supplier.path("cash").decimalValue().add(new BigDecimal("1.00")));
        });
    }

    @Test
    void settledStateRequiresTheActualCashEvent() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) ->
                assertTrue(events.removeIf(event -> event.type().equals("DIVIDEND_CASH_PAYMENT_V1"))));
    }

    @Test
    void dividendTransitionRequiresItsOriginalReport() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) ->
                assertTrue(events.removeIf(event -> event.type().equals("DIVIDEND_REPORT_V1"))));
    }

    @Test
    void duplicateDividendReportWithDistinctEventIdCannotBeRestored() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) -> {
            WorldEvent report = events.stream().filter(event -> event.type().equals("DIVIDEND_REPORT_V1"))
                    .findFirst().orElseThrow();
            events.add(new WorldEvent("duplicate-report", report.type(), report.payload()));
        });
    }

    @Test
    void publishedDividendBodyMustMatchTheAuthorizedEconomics() throws IOException {
        rejectDividendPrefix("PUBLICATION", (previous, current, root, events) -> {
            ObjectNode version = (ObjectNode) dividendDocument(root).path("versions").get(0);
            version.put("body", version.path("body").asText() + " forged per-share 9.00");
        });
    }

    @Test
    void publishedDividendAuthorMustMatchTheActualAuthorizedTask() throws IOException {
        rejectDividendPrefix("PUBLICATION", (previous, current, root, events) ->
                dividendDocument(root).put("publisherPartyId", BOB));
    }

    @Test
    void twoDistinctImpairmentsOfTheSameClaimAreBothRecorded() throws IOException {
        try (WorldRuntime runtime = open("two-impairments.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToEx(runtime);
            String claimId = DividendModel.claims(dividends(runtime), "S1").get(0).claimId();
            runtime.scheduleDividendImpairment("c-imp-1", "t-imp-1", EX + 10, INV_PARTY,
                    claimId, new BigDecimal("10.00"), "evidence-1");
            runtime.scheduleDividendImpairment("c-imp-2", "t-imp-2", EX + 20, INV_PARTY,
                    claimId, new BigDecimal("15.00"), "evidence-2");
            runtime.advanceTo(EX + 20);
            assertEquals(new BigDecimal("25.00"), DividendModel.claim(dividends(runtime), claimId).impairment());
            assertEquals(2, dividends(runtime).impairments().size());
        }
    }

    @Test
    void dividendAuthorizationRequiresTheActualChairSeat() throws IOException {
        try (WorldRuntime runtime = open("wrong-authorizer.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runtime.scheduleDividendAuthorization("c-wrong-auth", "t-wrong-auth", AUTH, "s2", "m1");
            runtime.advanceTo(AUTH);
            assertEquals(DividendModel.Phase.PROPOSED, scheme(runtime, "S1").phase());
            assertTrue(runtime.dividendReports().stream().anyMatch(report -> report.taskId().equals("t-wrong-auth")
                    && !report.accepted() && WorldRuntime.REASON_NOT_AUTHORIZED.equals(report.reason())));
        }
    }

    @Test
    void explicitSettlementRequiresTheCompanyOwnerAtCompletion() throws IOException {
        try (WorldRuntime runtime = open("wrong-settler.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToEx(runtime);
            runtime.scheduleDividendSettlement("c-wrong-pay", "t-wrong-pay", EX + 1, INV_PARTY, "S1");
            runtime.advanceTo(EX + 1);
            assertEquals(DividendModel.Phase.EX_BOOKED, scheme(runtime, "S1").phase());
            assertEquals(new BigDecimal("1000.00"), cash(runtime, COMPANY_ACC));
            assertTrue(runtime.dividendReports().stream().anyMatch(report -> report.taskId().equals("t-wrong-pay")
                    && !report.accepted() && WorldRuntime.REASON_NOT_AUTHORIZED.equals(report.reason())));
        }
    }

    @Test
    void explicitSettlementReportPreservesTheOriginalCommandAndKind() throws IOException {
        try (WorldRuntime runtime = open("explicit-settlement-report.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            scheduleProposal(runtime, "m1", "S1", "1.00", RECORD, EX, PAY, T0 + 1);
            vote(runtime, "m1", List.of("s1", "s2", "s3"), OPENS + 100);
            runToEx(runtime);
            runtime.scheduleDividendSettlement("c-real-pay", "t-real-pay", PAY + 1, COMPANY_OWNER, "S1");
            runtime.advanceTo(PAY + 1);
            WorldRuntime.DividendReport report = runtime.dividendReports().stream()
                    .filter(item -> item.taskId().equals("t-real-pay")).findFirst().orElseThrow();
            assertEquals("c-real-pay", report.commandId());
            assertEquals("DIVIDEND_SETTLE_V1", report.kind());
            assertFalse(report.accepted());
            assertEquals(new BigDecimal("200.00"), cash(runtime, ACC_INV));
        }
    }

    private record DividendFrame(WorldCommit commit, ObjectNode root, DividendModel.State dividends) {
        DividendModel.Event event() {
            DividendModel.Receipt receipt = dividends.receipts().values().stream()
                    .filter(item -> item.position() == commit.position()).findFirst().orElseThrow();
            return new DividendModel.Event(receipt.eventId(), receipt.position(), receipt.atMillis());
        }
    }

    @Test
    void cashEvidenceLegMustMatchTheActualPayment() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) ->
                changeDividendEvent(events, "DIVIDEND_CASH_PAYMENT_V1", proof ->
                        ((ObjectNode) proof.path("legs").get(0)).put("amount", "0.01")));
    }

    @Test
    void cashEvidenceRejectsUnknownFields() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) ->
                changeDividendEvent(events, "DIVIDEND_CASH_PAYMENT_V1", proof -> proof.put("unknown", true)));
    }

    @Test
    void ordinaryClockBatchCannotCarryAnOrphanDividendReport() throws IOException {
        rejectDividendPrefix("CLOCK_AFTER_EX", (previous, current, root, events) -> {
            var report = new WorldRuntime.DividendReport("t-auth", "c-auth",
                    "GOVERNANCE_DIVIDEND_IMPLEMENT_V1", "m1", "S1", current.commit().position(),
                    root.path("timeMillis").asLong(), true, "AUTHORIZED");
            events.add(new WorldEvent("orphan-report", "DIVIDEND_REPORT_V1",
                    dividendJson().writeValueAsString(report)));
        });
    }

    @Test
    void ordinaryClockBatchCannotCarryAnOrphanDividendCashEvent() throws IOException {
        rejectDividendPrefix("CLOCK_AFTER_EX", (previous, current, root, events) -> {
            DividendModel.Claim claim = DividendModel.claims(current.dividends(), "S1").get(0);
            Map<String, Object> proof = Map.of("schemeId", "S1", "companyAccountId", COMPANY_ACC,
                    "total", "200.00", "legs", List.of(Map.of("claimId", claim.claimId(),
                            "accountId", ACC_INV, "amount", "200.00")));
            events.add(new WorldEvent("orphan-cash", "DIVIDEND_CASH_PAYMENT_V1",
                    dividendJson().writeValueAsString(proof)));
        });
    }

    @Test
    void authorizationMustScheduleTheActualPublicAnnouncement() throws IOException {
        rejectDividendPrefix("AUTHORIZE", (previous, current, root, events) ->
                removeDividendChild(root, events, PendingTask.INFORMATION_PUBLISH_V1));
    }

    @Test
    void authorizationMustScheduleTheActualPayment() throws IOException {
        rejectDividendPrefix("AUTHORIZE", (previous, current, root, events) ->
                removeDividendChild(root, events, "DIVIDEND_SETTLE_V1"));
    }

    @Test
    void successfulPublicationRequiresTheActualDocument() throws IOException {
        rejectDividendPrefix("PUBLICATION", (previous, current, root, events) -> {
            var information = (ObjectNode) root.path("information").path("information");
            assertFalse(information.path("documents").isEmpty());
            information.set("documents", dividendJson().createArrayNode());
        });
    }

    @Test
    void ordinaryVersionPublishingCannotModifyReservedDividendDocuments() throws IOException {
        Path source = completedSource("version-guard.journal");
        try (WorldRuntime runtime = open(source)) {
            assertThrows(IllegalArgumentException.class, () -> runtime.schedulePublishDocumentVersion(
                    "c-forged-version", "t-forged-version", PAY + 1, "dividend-public:S1",
                    "伪造公告", "每股9.00", "公司董事会", 60_000L, List.of()));
        }
    }

    @Test
    void proposalCannotNameADifferentIssuerOwner() throws IOException {
        rejectWrongDividendIssuer(new DividendModel.SchemeRequest("S1", SYM, "customer", COMPANY_ACC,
                new BigDecimal("1.00"), RECORD, EX, PAY));
    }

    @Test
    void proposalCannotUseADifferentCompanyCashAccount() throws IOException {
        rejectWrongDividendIssuer(new DividendModel.SchemeRequest("S1", SYM, COMPANY_OWNER, "CUS",
                new BigDecimal("1.00"), RECORD, EX, PAY));
    }

    @Test
    void proposalCannotNameADifferentSecurity() throws IOException {
        rejectWrongDividendIssuer(new DividendModel.SchemeRequest("S1", "OTHER", COMPANY_OWNER, COMPANY_ACC,
                new BigDecimal("1.00"), RECORD, EX, PAY));
    }

    @Test
    void scheduledDividendPayloadRejectsUnknownFieldsDuringRestore() throws IOException {
        rejectDividendPrefix("PROPOSAL_SCHEDULE", (previous, current, root, events) -> {
            var pending = root.withArray("pending");
            ObjectMapper json = dividendJson();
            boolean changed = false;
            for (var entry : pending) {
                if (entry.path("kind").asText().equals("GOVERNANCE_DIVIDEND_PROPOSAL_V1")) {
                    ObjectNode payload = (ObjectNode) json.readTree(entry.path("payload").asText());
                    payload.put("unknownDividendField", true);
                    ((ObjectNode) entry).put("payload", json.writeValueAsString(payload));
                    String id = entry.path("taskId").asText();
                    for (int index = 0; index < events.size(); index++) {
                        WorldEvent event = events.get(index);
                        if (event.eventId().equals("task-scheduled:" + id)) {
                            events.set(index, new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(entry)));
                        }
                    }
                    changed = true;
                }
            }
            assertTrue(changed);
        });
    }

    @Test
    void oldProfileCannotRestoreANewDividendCashEvent() throws IOException {
        String name = "legacy-forged-dividend-source.journal";
        try (WorldRuntime runtime = openLegacy(name)) {
            runtime.advanceTo(1000);
        }
        Path forged = tempDir.resolve("legacy-forged-dividend.journal");
        try (WorldJournal source = new WorldJournal(tempDir.resolve(name), RUN);
             WorldJournal target = new WorldJournal(forged, RUN)) {
            for (WorldCommit commit : source.commits()) {
                List<WorldEvent> events = new ArrayList<>(commit.events());
                if (commit.position() == source.commits().size()) {
                    events.add(new WorldEvent("forged-old-dividend-cash", "DIVIDEND_CASH_PAYMENT_V1", "{}"));
                }
                target.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
        assertThrows(IOException.class, () -> new WorldRuntime(forged, legacyManifest(), openings(), registry(),
                MFG, charter(), roles(), information(), decisions()).close());
    }

    private void rejectWrongDividendIssuer(DividendModel.SchemeRequest request) throws IOException {
        try (WorldRuntime runtime = open("wrong-company.journal")) {
            scheduleMeeting(runtime, "m1", T0, BoardModel.Topic.DIVIDEND_AUTHORIZATION);
            runtime.scheduleDividendProposal("c-invalid-issuer", "t-invalid-issuer", T0 + 1,
                    "s1", "m1", List.of(), request);
            runtime.advanceTo(T0 + 1);
            assertTrue(dividends(runtime).schemes().isEmpty());
            assertTrue(meeting(runtime, "m1").dividendProposal() == null);
            assertTrue(runtime.dividendReports().stream().anyMatch(report -> report.taskId().equals("t-invalid-issuer")
                    && !report.accepted()));
            assertEquals(new BigDecimal("1000.00"), cash(runtime, COMPANY_ACC));
        }
    }

    @Test
    void completedDividendTaskPayloadMustEqualTheOriginalTask() throws IOException {
        rejectDividendPrefix("REGISTER", (previous, current, root, events) -> {
            for (int index = 0; index < events.size(); index++) {
                WorldEvent event = events.get(index);
                if (event.type().equals("TASK_COMPLETED_V1")) {
                    events.set(index, new WorldEvent(event.eventId(), event.type(), "{}"));
                    return;
                }
            }
            throw new AssertionError("实际完成事件不存在");
        });
    }

    @Test
    void dividendBatchTimeMustEqualTheOriginalTaskDue() throws IOException {
        rejectDividendPrefix("REGISTER", (previous, current, root, events) ->
                root.put("timeMillis", root.path("timeMillis").asLong() + 1));
    }

    @Test
    void dividendBatchPhaseMustEqualTheOriginalTaskPhase() throws IOException {
        rejectDividendPrefix("REGISTER", (previous, current, root, events) ->
                root.put("lastPhase", "OBSERVATION"));
    }

    @Test
    void dividendMeetingMustScheduleItsActualBoardOpening() throws IOException {
        rejectDividendPrefix("MEETING", (previous, current, root, events) ->
                removeDividendChild(root, events, PendingTask.BOARD_OPEN_V1));
    }

    @Test
    void dividendMeetingGovernanceEventMustMatchTheActualOutcome() throws IOException {
        rejectDividendPrefix("MEETING", (previous, current, root, events) ->
                changeDividendEvent(events, "GOVERNANCE_REPORT_V1", report -> report.put("accepted", false)));
    }

    @Test
    void dividendPublicationTitleMustMatchTheAuthorizedTask() throws IOException {
        rejectDividendPrefix("PUBLICATION", (previous, current, root, events) ->
                ((ObjectNode) dividendDocument(root).path("versions").get(0)).put("title", "伪造标题"));
    }

    @Test
    void dividendPublicationSourceMustMatchTheAuthorizedTask() throws IOException {
        rejectDividendPrefix("PUBLICATION", (previous, current, root, events) ->
                ((ObjectNode) dividendDocument(root).path("versions").get(0)).put("publicSourceDescription", "伪造来源"));
    }

    @Test
    void dividendPublicationTimeMustEqualActualPublicationDue() throws IOException {
        rejectDividendPrefix("PUBLICATION", (previous, current, root, events) ->
                ((ObjectNode) dividendDocument(root).path("versions").get(0)).put("publishedAt", AUTH));
    }

    @Test
    void dividendPublicationCannotGainAnUnconfirmedSecondVersion() throws IOException {
        rejectDividendPrefix("PUBLICATION", (previous, current, root, events) -> {
            var versions = dividendDocument(root).withArray("versions");
            ObjectNode extra = ((ObjectNode) versions.get(0)).deepCopy();
            extra.put("version", 2L);
            versions.add(extra);
        });
    }

    @Test
    void confirmedDividendPublicationCannotDisappearInALaterClock() throws IOException {
        rejectDividendPrefix("CLOCK_AFTER_EX", (previous, current, root, events) ->
                ((ObjectNode) root.path("information").path("information"))
                        .set("documents", dividendJson().createArrayNode()));
    }

    @Test
    void dividendReportSchemeMustMatchTheActualTask() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) ->
                changeDividendEvent(events, "DIVIDEND_REPORT_V1", report -> report.put("schemeId", "other-scheme")));
    }

    @Test
    void dividendReportMeetingMustMatchTheActualTask() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) ->
                changeDividendEvent(events, "DIVIDEND_REPORT_V1", report -> report.put("meetingId", "other-meeting")));
    }

    @Test
    void dividendReportEventIdentityMustMatchTheActualTask() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) ->
                changeDividendEventIdentity(events, "DIVIDEND_REPORT_V1", "different-report-identity"));
    }

    @Test
    void dividendCashEventIdentityMustMatchTheActualPaymentEvidence() throws IOException {
        rejectDividendPrefix("SETTLE", (previous, current, root, events) ->
                changeDividendEventIdentity(events, "DIVIDEND_CASH_PAYMENT_V1", "different-cash-identity"));
    }

    @Test
    void dividendOriginalRequestCannotOmitItsRecordTime() throws IOException {
        rejectDividendPrefix("PROPOSAL_SCHEDULE", (previous, current, root, events) -> {
            ObjectMapper json = dividendJson();
            for (var entry : root.withArray("pending")) {
                if (!entry.path("kind").asText().equals("GOVERNANCE_DIVIDEND_PROPOSAL_V1")) continue;
                ObjectNode payload = (ObjectNode) json.readTree(entry.path("payload").asText());
                assertTrue(((ObjectNode) payload.path("request")).remove("recordAtMillis") != null);
                ((ObjectNode) entry).put("payload", json.writeValueAsString(payload));
                String taskId = entry.path("taskId").asText();
                for (int index = 0; index < events.size(); index++) {
                    WorldEvent event = events.get(index);
                    if (event.eventId().equals("task-scheduled:" + taskId)) {
                        events.set(index, new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(entry)));
                    }
                }
                return;
            }
            throw new AssertionError("原排程请求不存在");
        });
    }

    @Test
    void confirmedDividendPendingCannotBeRemovedByAnOrdinaryClock() throws IOException {
        rejectDividendPrefix("CLOCK_AFTER_EX", (previous, current, root, events) -> {
            var remaining = dividendJson().createArrayNode();
            int removed = 0;
            for (var task : root.withArray("pending")) {
                if (task.path("kind").asText().equals("DIVIDEND_SETTLE_V1")) removed++;
                else remaining.add(task);
            }
            assertEquals(1, removed);
            root.set("pending", remaining);
        });
    }

    @Test
    void confirmedDividendPendingCannotBeRewrittenByAnOrdinaryClock() throws IOException {
        rejectDividendPrefix("CLOCK_AFTER_EX", (previous, current, root, events) -> {
            ObjectMapper json = dividendJson();
            for (var task : root.withArray("pending")) {
                if (!task.path("kind").asText().equals("DIVIDEND_SETTLE_V1")) continue;
                ObjectNode payload = (ObjectNode) json.readTree(task.path("payload").asText());
                payload.put("seatId", ALICE);
                ((ObjectNode) task).put("payload", json.writeValueAsString(payload));
                return;
            }
            throw new AssertionError("实际付款待办不存在");
        });
    }

    private static void changeDividendEventIdentity(List<WorldEvent> events, String type, String eventId) {
        for (int index = 0; index < events.size(); index++) {
            WorldEvent event = events.get(index);
            if (event.type().equals(type)) {
                events.set(index, new WorldEvent(eventId, event.type(), event.payload()));
                return;
            }
        }
        throw new AssertionError("实际事件不存在 " + type);
    }

    private static void changeDividendEvent(List<WorldEvent> events, String type, Consumer<ObjectNode> change)
            throws IOException {
        ObjectMapper json = dividendJson();
        for (int index = 0; index < events.size(); index++) {
            WorldEvent event = events.get(index);
            if (event.type().equals(type)) {
                ObjectNode value = (ObjectNode) json.readTree(event.payload());
                change.accept(value);
                events.set(index, new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(value)));
                return;
            }
        }
        throw new AssertionError("实际事件不存在 " + type);
    }

    private static void removeDividendChild(ObjectNode root, List<WorldEvent> events, String kind)
            throws IOException {
        var remaining = dividendJson().createArrayNode();
        List<String> removed = new ArrayList<>();
        for (var entry : root.withArray("pending")) {
            if (entry.path("kind").asText().equals(kind)) removed.add(entry.path("taskId").asText());
            else remaining.add(entry);
        }
        assertEquals(1, removed.size());
        root.set("pending", remaining);
        assertTrue(events.removeIf(event -> removed.stream()
                .anyMatch(id -> event.eventId().equals("task-scheduled:" + id))));
    }

    @FunctionalInterface
    private interface DividendMutation {
        void change(DividendFrame previous, DividendFrame current, ObjectNode root,
                    List<WorldEvent> events) throws IOException;
    }

    private static ObjectMapper dividendJson() {
        return new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    }

    private static ObjectNode dividendDocument(ObjectNode root) {
        for (var document : root.path("information").path("information").path("documents")) {
            if (document.path("docId").asText().equals("dividend-public:S1")) {
                return (ObjectNode) document;
            }
        }
        throw new AssertionError("真实公告不存在");
    }

    private void rejectDividendPrefix(String action, DividendMutation mutation) throws IOException {
        Path source = completedSource("source.journal");
        List<DividendFrame> frames = new ArrayList<>();
        ObjectMapper json = dividendJson();
        int end = -1;
        try (WorldJournal journal = new WorldJournal(source, RUN)) {
            for (WorldCommit commit : journal.commits()) {
                WorldEvent snapshot = commit.events().stream().filter(event -> event.type().equals("STATE_SNAPSHOT_V1"))
                        .findFirst().orElseThrow();
                ObjectNode root = (ObjectNode) json.readTree(snapshot.payload());
                DividendModel.State d0 = DividendModel.fromJson(root.path("dividends").toString());
                frames.add(new DividendFrame(commit, root, d0));
                boolean matches;
                if (action.equals("PUBLICATION")) {
                    matches = !root.path("information").path("information").path("documents").isEmpty();
                } else if (action.equals("CLOCK_AFTER_EX")) {
                    matches = commit.commandId().startsWith("clock:") && d0.schemes().values().stream()
                            .anyMatch(scheme -> scheme.phase() == DividendModel.Phase.EX_BOOKED);
                } else if (action.equals("PROPOSAL_SCHEDULE")) {
                    matches = commit.events().stream().anyMatch(event -> event.type().equals("TASK_SCHEDULED_V1")
                            && event.payload().contains("GOVERNANCE_DIVIDEND_PROPOSAL_V1"));
                } else if (action.equals("MEETING")) {
                    matches = commit.events().stream().anyMatch(event -> event.type().equals("GOVERNANCE_REPORT_V1")
                            && event.payload().contains("DIVIDEND_MEETING_V1"));
                } else {
                    matches = d0.receipts().values().stream().anyMatch(receipt -> receipt.position() == commit.position()
                            && receipt.action().equals(action));
                }
                if (matches) {
                    end = frames.size() - 1;
                    break;
                }
            }
        }
        assertTrue(end > 0, "必须找到真实动作确认前缀");
        Path legal = tempDir.resolve("legal-prefix.journal");
        rewriteDividendPrefix(frames, legal, null);
        try (WorldRuntime runtime = open(legal)) {
            assertEquals(frames.get(end).commit().position(), runtime.snapshot().position());
        }
        Path forged = tempDir.resolve("forged-prefix.journal");
        rewriteDividendPrefix(frames, forged, mutation);
        assertThrows(IOException.class, () -> open(forged).close());
    }

    private void rewriteDividendPrefix(List<DividendFrame> frames, Path target, DividendMutation mutation)
            throws IOException {
        try (WorldJournal journal = new WorldJournal(target, RUN)) {
            for (int index = 0; index < frames.size(); index++) {
                DividendFrame frame = frames.get(index);
                List<WorldEvent> events = new ArrayList<>(frame.commit().events());
                if (mutation != null && index == frames.size() - 1) {
                    ObjectNode root = frame.root().deepCopy();
                    mutation.change(frames.get(index - 1), frame, root, events);
                    for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                        WorldEvent event = events.get(eventIndex);
                        if (event.type().equals("STATE_SNAPSHOT_V1")) {
                            events.set(eventIndex, new WorldEvent(event.eventId(), event.type(),
                                    dividendJson().writeValueAsString(root)));
                        }
                    }
                }
                journal.commit(frame.commit().commandId(), frame.commit().commandContent(), frame.commit().result(), events);
            }
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private WorldRuntime open(String name) throws IOException {
        return open(tempDir.resolve(name), registry());
    }

    private WorldRuntime open(String name, OpeningRegistry registry) throws IOException {
        return open(tempDir.resolve(name), registry);
    }

    private WorldRuntime open(Path file) throws IOException {
        return open(file, registry());
    }

    private WorldRuntime open(Path file, OpeningRegistry registry) throws IOException {
        return new WorldRuntime(file, manifest(), openings(), registry, MFG, charter(), roles(),
                information(), decisions());
    }

    private WorldRuntime openLegacy(String name) throws IOException {
        return new WorldRuntime(tempDir.resolve(name), legacyManifest(), openings(), registry(),
                MFG, charter(), roles(), information(), decisions());
    }

    private static void scheduleMeeting(WorldRuntime runtime, String meetingId, long at,
                                        BoardModel.Topic topic) throws IOException {
        runtime.scheduleBoardMeeting("c-convene-" + meetingId, "t-convene-" + meetingId, at, "s1",
                meetingId, at + 5_000, at + 10_000, at + 20_000,
                List.of("s1", "s2", "s3"), List.of(), topic);
    }

    private static void scheduleProposal(WorldRuntime runtime, String meetingId, String schemeId,
                                         String perShare, long recordAt, long exAt, long payAt,
                                         long at) throws IOException {
        runtime.scheduleDividendProposal("c-prop-" + meetingId, "t-prop-" + meetingId, at, "s1",
                meetingId, List.of(), schemeRequest(schemeId, perShare, recordAt, exAt, payAt));
    }

    private static void vote(WorldRuntime runtime, String meetingId, List<String> yes, long at)
            throws IOException {
        for (String seat : List.of("s1", "s2", "s3", "s4", "s5")) {
            BoardModel.Vote choice = yes.contains(seat) ? BoardModel.Vote.YES : BoardModel.Vote.NO;
            runtime.scheduleBoardVote("c-vote-" + meetingId + "-" + seat,
                    "t-vote-" + meetingId + "-" + seat, at, seat, meetingId, choice);
        }
    }

    private static void runToRegistration(WorldRuntime runtime) throws IOException {
        runtime.advanceTo(CLOSES);
        runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
        runtime.advanceTo(RECORD);
    }

    private static void runToEx(WorldRuntime runtime) throws IOException {
        runToRegistration(runtime);
        runtime.advanceTo(EX);
    }

    private static void runToSettled(WorldRuntime runtime) throws IOException {
        runToEx(runtime);
        runtime.advanceTo(PAY);
    }

    private static DividendModel.SchemeRequest schemeRequest(String schemeId, String perShare,
                                                             long recordAt, long exAt, long payAt) {
        return new DividendModel.SchemeRequest(schemeId, SYM, COMPANY_OWNER, COMPANY_ACC,
                new BigDecimal(perShare), recordAt, exAt, payAt);
    }

    private static DividendModel.State dividends(WorldRuntime runtime) {
        return runtime.snapshot().dividends()
                .orElseThrow(() -> new AssertionError("缺少分红状态"));
    }

    private static DividendModel.Scheme scheme(WorldRuntime runtime, String schemeId) {
        return DividendModel.scheme(dividends(runtime), schemeId);
    }

    private static BoardModel.Meeting meeting(WorldRuntime runtime, String meetingId) {
        return runtime.snapshot().governance().orElseThrow().board().meeting(meetingId);
    }

    private static RoleView.Market market(WorldRuntime runtime, String person) {
        for (RoleView.Market entry : runtime.roleView(person).market()) {
            if (entry.symbol().equals(SYM)) {
                return entry;
            }
        }
        throw new AssertionError("缺少行情行：" + SYM);
    }

    private static boolean published(WorldRuntime runtime, String docId) {
        for (InformationModel.Document document : runtime.snapshot().information().orElseThrow()
                .information().documents()) {
            if (document.docId().equals(docId)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    private static ObjectNode firstClaim(ObjectNode root) {
        return (ObjectNode) root.path("dividends").path("claims").elements().next();
    }

    private RunManifest manifest() {
        Map<String, Integer> modules = modules();
        modules.put("dividend", 1);
        return new RunManifest(RUN, SEED, RULES_DIVIDEND, modules,
                Map.of("fixture", "dividend-runtime-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private RunManifest legacyManifest() {
        return new RunManifest(RUN, SEED, RunManifest.RULES_DECISION_EXPERIMENT_V1, modules(),
                Map.of("fixture", "decision-runtime-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static Map<String, Integer> modules() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions")) {
            modules.put(name, 1);
        }
        return modules;
    }

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening(COMPANY_OWNER, COMPANY_ACC, "SUP", "CUS", "OPS"), SYM);

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount(ACC_INV, INV_PARTY, new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount(COMPANY_ACC, COMPANY_OWNER, new BigDecimal("1000.00"),
                        "实验开局历史禀赋"),
                new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
                new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
                new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter(COMPANY_PARTY, List.of(
                new BoardModel.Seat("s1", ALICE), new BoardModel.Seat("s2", BOB),
                new BoardModel.Seat("s3", CAROL), new BoardModel.Seat("s4", DAVE),
                new BoardModel.Seat("s5", ERIN)), "s1", BoardModel.QUORUM);
    }

    private static RoleRun.Config roles() {
        return new RoleRun.Config(List.of(), List.of());
    }

    private static InformationRun.Config information() {
        return new InformationRun.Config(List.of(ALICE, BOB, CAROL, DAVE, ERIN));
    }

    private static DecisionModel.Config decisions() {
        return decisions(List.of(ALICE, BOB, CAROL, DAVE, ERIN));
    }

    private static DecisionModel.Config decisions(List<String> readers) {
        DecisionModel.Usage zero = new DecisionModel.Usage(0, 0, 0, new BigDecimal("0.00"));
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        for (String person : readers) {
            caps.put(person, zero);
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:dividend-v1",
                "scripted-v1", Map.of("wake", 1L), Map.of(),
                new DecisionModel.Limits(2, 20_000, 20_000, 2_000, 2_000, 4, 2, 60_000),
                new DecisionModel.Budget(zero, caps));
    }

    private static OpeningRegistry registry() {
        return registry(List.of(new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                new BigDecimal("10.0000"), ShareClass.FLOAT)));
    }

    private static OpeningRegistry registryWithLocked() {
        return registry(List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 150L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT),
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 50L,
                        new BigDecimal("10.0000"), ShareClass.LOCKED)));
    }

    private static OpeningRegistry registryWithTreasury() {
        return registry(List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 100L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT),
                new OpeningRegistry.ShareAllocation(COMPANY_ACC, SYM, 100L,
                        new BigDecimal("10.0000"), ShareClass.TREASURY)));
    }

    private static OpeningRegistry registryWithCompanyFloat() {
        return registry(List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 100L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT),
                new OpeningRegistry.ShareAllocation(COMPANY_ACC, SYM, 100L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT)));
    }

    private static OpeningRegistry registry(List<OpeningRegistry.ShareAllocation> allocations) {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(COMPANY_PARTY, PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV_PARTY, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OpeningRegistry.OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV_PARTY, OpeningRegistry.OwnerKind.PERSON,
                        INV_PARTY),
                new OpeningRegistry.PropertyOwner(COMPANY_OWNER,
                        OpeningRegistry.OwnerKind.ORGANIZATION, COMPANY_PARTY),
                new OpeningRegistry.PropertyOwner("supplier", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("customer", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE, null));
        return new OpeningRegistry(parties, owners,
                List.of(new OpeningRegistry.Security(SYM, COMPANY_PARTY, 200L,
                        new BigDecimal("10.00"))),
                allocations);
    }

    /** 完整重写每个状态快照的 JSON（WorldJournal 重算帧摘要），用于恢复期篡改反证。 */
    private Path tamperSnapshots(Path source, String name, Predicate<ObjectNode> when,
                                 Consumer<ObjectNode> change) throws IOException {
        return tamperEvents(source, name, "STATE_SNAPSHOT_V1", root -> {
            if (when == null || when.test(root)) {
                change.accept(root);
            }
        });
    }

    private Path tamperEvents(Path source, String name, String type, Consumer<ObjectNode> change) throws IOException {
        Path target = tempDir.resolve(name);
        ObjectMapper json = new ObjectMapper();
        try (WorldJournal original = new WorldJournal(source, RUN);
             WorldJournal rewritten = new WorldJournal(target, RUN)) {
            for (WorldCommit commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if (type.equals(event.type())) {
                        ObjectNode root = (ObjectNode) json.readTree(event.payload());
                        change.accept(root);
                        events.add(new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(root)));
                    } else {
                        events.add(event);
                    }
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(),
                        events);
            }
        }
        return target;
    }
}
