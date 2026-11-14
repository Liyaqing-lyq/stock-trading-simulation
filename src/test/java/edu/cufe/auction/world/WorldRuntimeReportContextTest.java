package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工程公司报表上下文（净外部资本/宣告/未付分红）的有界性与公司债务口径回归。
 *
 * <p>三条口径：</p>
 * <ol>
 *   <li>历史帧重生成的上下文只能由该帧已确认的事实决定；位置更大的资本事件不得改写它。</li>
 *   <li>公司应付分红是合同面值减已付；投资者侧减值只降低持有人净应收，不降低公司债务，
 *       真实回款只按原额转回一次，同一笔债务不重复计入。</li>
 *   <li>报表继续 A=L+E：历史/当前两口径都不许靠夹取或隐藏债务来配平。</li>
 * </ol>
 *
 * <p>夹具全部走真实公开入口：真实 {@link WorldRuntime}/{@link WorldJournal}、真实
 * {@link BoardModel} 分红授权链、真实 {@link DividendModel} 除息/减值/清偿，以及真实工程公司
 * 报表。历史帧的观察只用 {@code state} 字段与私有
 * {@code reportContextOf(WorldState, String)} 的<b>只读</b>反射；本类不修改任何状态。</p>
 */
@DisplayName("WorldRuntime：工程报表上下文的历史边界与公司债务口径")
class WorldRuntimeReportContextTest {

    private static final String ENGINE = WorldWebScenario.ENGINE_A;
    private static final String ENGINE_ACCOUNT = "ENG-A";
    private static final String COMPANY = WorldWebScenario.COMPANY;
    private static final String COMPANY_OWNER = "company";
    private static final String COMPANY_ACCOUNT = "CO";
    private static final String SYMBOL = WorldWebScenario.SYMBOL;
    private static final String INVESTOR = WorldWebScenario.INVESTOR;

    private static final long T0 = 1_000L;
    private static final long CLOSES = T0 + 20_000L;
    private static final long AUTH = CLOSES + 1L;
    private static final long RECORD = AUTH + 2L;
    private static final long EX = RECORD + 1_000L;
    private static final long PAY = EX + 1_000L;

    @Test
    @DisplayName("历史资本上下文不被未来资本事件改写；当前资本正确且重开一致")
    void historicalCapitalContextIgnoresLaterCapitalFlows() throws Exception {
        Path file = journal("historical-capital");
        try (WorldRuntime runtime = open(file)) {
            Object before = stateOf(runtime);
            EngineeringModel.ReportContext first = reportContext(runtime, before);
            assertEquals("0.00", first.netExternalCapital().toPlainString(), "开局没有外部资本");

            runtime.scheduleCapitalFlow("cap-1", "cap-1-task", T0, "fund-a", "ACC-FUND",
                    ENGINE_ACCOUNT, new BigDecimal("100.00"), 1L, "工程公司外部资本");
            runtime.advanceTo(T0 + 1L);

            assertEquals(first, reportContext(runtime, before),
                    "同一历史帧的资本上下文不得被之后确认的资本事件改写");
            assertEquals("100.00", reportContext(runtime, stateOf(runtime))
                    .netExternalCapital().toPlainString(), "当前帧必须计入真实入资");

            WorldRuntime.CompanyAudit audit = runtime.companyAudit(ENGINE).orElseThrow();
            assertEquals("5100.00", audit.companyCash().toPlainString());
            assertEquals("5100.00", audit.equity().toPlainString(), "入资只增权益、不进利润");
            assertEquals("0.00", audit.payable().toPlainString());
            assertTrue(audit.balanced(), "A=L+E 必须成立");
        }

        try (WorldRuntime reopened = open(file)) {
            Object base = stateOf(reopened);
            assertEquals("100.00", reportContext(reopened, base).netExternalCapital()
                    .toPlainString(), "重开后同一历史位置给出同一上下文");
            assertTrue(reopened.companyAudit(ENGINE).orElseThrow().balanced());

            reopened.scheduleCapitalFlow("cap-2", "cap-2-task", T0 + 10L, "fund-a", "ACC-FUND",
                    ENGINE_ACCOUNT, new BigDecimal("50.00"), 1L, "第二笔外部资本");
            reopened.advanceTo(T0 + 11L);

            assertEquals("100.00", reportContext(reopened, base).netExternalCapital()
                    .toPlainString(), "重开后的历史帧同样不得看到未来资本");
            assertEquals("150.00", reportContext(reopened, stateOf(reopened))
                    .netExternalCapital().toPlainString());
            assertEquals("5150.00",
                    reopened.companyAudit(ENGINE).orElseThrow().equity().toPlainString());
        }
    }

    @Test
    @DisplayName("既有分红链与非发行人工程账户隔离；非工程发行人债务回归")
    void holderImpairmentDoesNotReduceCompanyDividendPayable() throws Exception {
        Path file = journal("impairment-chain");
        try (WorldRuntime runtime = open(file)) {
            BigDecimal cashBefore = runtime.snapshot().accounts().get(COMPANY_ACCOUNT).cash();
            runDividendToEx(runtime);

            DividendModel.State exBooked = dividends(runtime);
            DividendModel.Claim claim = claimOf(exBooked, "INV");
            BigDecimal face = claim.face();
            BigDecimal totalFace = DividendModel.claims(exBooked, "S1").stream()
                    .map(DividendModel.Claim::face).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalPaid = DividendModel.claims(exBooked, "S1").stream()
                    .map(DividendModel.Claim::paid).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertTrue(face.signum() > 0, "除息必须产生真实非零债权");
            assertEquals(totalFace.subtract(totalPaid),
                    DividendModel.companyPayable(exBooked, "S1"),
                    "除息后公司应付等于全部合同未付额");

            runtime.scheduleDividendImpairment("imp-1", "imp-1-task", EX + 10L, INVESTOR,
                    claim.claimId(), new BigDecimal("50.00"), "持有人评估证据");
            runtime.advanceTo(EX + 10L);

            DividendModel.State impaired = dividends(runtime);
            DividendModel.Claim afterImpair = DividendModel.claim(impaired, claim.claimId());
            assertEquals("50.00", afterImpair.impairment().toPlainString());
            assertEquals(totalFace.subtract(totalPaid),
                    DividendModel.companyPayable(impaired, "S1"),
                    "投资者减值不降低公司应付分红");
            assertEquals(0, DividendModel.holderNetReceivable(afterImpair)
                    .compareTo(face.subtract(new BigDecimal("50.00"))), "只降低持有人净应收");
            assertEquals(cashBefore, runtime.snapshot().accounts().get(COMPANY_ACCOUNT).cash(),
                    "减值不动公司现金");

            // 工程公司不是该方案发行人：其报表上下文不得出现别家的分红债务。
            assertEquals("0.00", reportContext(runtime, stateOf(runtime))
                    .unpaidDividends().toPlainString(), "非发行人没有该分红债务");
            assertEquals("0.00", reportContext(runtime, stateOf(runtime))
                    .declaredDividends().toPlainString());
            assertTrue(runtime.companyAudit(ENGINE).orElseThrow().balanced());

            runtime.advanceTo(PAY);
            DividendModel.State settled = dividends(runtime);
            DividendModel.Claim paid = DividendModel.claim(settled, claim.claimId());
            assertEquals(face, paid.paid(), "整批清偿付满合同面值");
            assertEquals("0.00", paid.impairment().toPlainString(), "真实回款才转回减值");
            assertEquals("0.00", DividendModel.companyPayable(settled, "S1").toPlainString(),
                    "清偿后债务归零且只结一次");
            DividendModel.Payment payment = settled.payments().get(settled.payments().size() - 1);
            assertEquals(0, payment.amount().compareTo(totalFace.subtract(totalPaid)),
                    "公司按合同面值付款，不按减值后的净额");
            BigDecimal released = payment.impairmentReleased().get(claim.claimId());
            assertNotNull(released);
            assertEquals("50.00", released.toPlainString(), "减值转回按原额各记一次");
            assertEquals(0, cashBefore.subtract(payment.amount())
                    .compareTo(runtime.snapshot().accounts().get(COMPANY_ACCOUNT).cash()),
                    "公司现金只按真实付款减少一次");
        }
    }

    @Test
    @DisplayName("分支子档：父前缀历史资本不被子档未来资本改写")
    void branchChildKeepsParentPrefixCapitalContext() throws Exception {
        Path parentFile = journal("branch-parent");
        BranchProfile profile;
        WorldRuntime.BranchReceipt receipt;
        long cut;
        try (WorldRuntime parent = open(parentFile)) {
            parent.scheduleCapitalFlow("cap-parent", "cap-parent-task", T0, "fund-a", "ACC-FUND",
                    ENGINE_ACCOUNT, new BigDecimal("100.00"), 1L, "父档外部资本");
            parent.advanceTo(T0 + 1L);
            cut = parent.snapshot().position();
            profile = parent.profile();
            receipt = parent.createBranch("context-child", cut, BranchOrigin.Kind.OPERATOR_BRANCH,
                    "财务上下文分支");
            assertEquals(cut, parent.snapshot().position(), "建分支不推进父档");
        }

        try (WorldRuntime child = WorldRuntime.openBranch(receipt.childFile(),
                id -> parentFile, profile)) {
            Object childCut = stateOf(child);
            assertEquals("100.00", reportContext(child, childCut).netExternalCapital()
                    .toPlainString(), "子档历史位置只看到父前缀资本");

            child.scheduleCapitalFlow("cap-child", "cap-child-task", T0 + 20L, "fund-a",
                    "ACC-FUND", ENGINE_ACCOUNT, new BigDecimal("30.00"), 1L, "子档自有资本");
            child.advanceTo(T0 + 21L);

            assertEquals("100.00", reportContext(child, childCut).netExternalCapital()
                    .toPlainString(), "子档自己的后续资本同样不得改写父前缀历史帧");
            assertEquals("130.00", reportContext(child, stateOf(child)).netExternalCapital()
                    .toPlainString());
            assertEquals("5130.00", child.companyAudit(ENGINE).orElseThrow().equity()
                    .toPlainString(), "父前缀与子档资本各计一次，不丢不双计");
            assertTrue(child.companyAudit(ENGINE).orElseThrow().balanced());
        }
        try (WorldRuntime parent = open(parentFile)) {
            assertEquals(cut, parent.snapshot().position(), "父档不被子档资本污染");
            assertEquals("5100.00",
                    parent.companyAudit(ENGINE).orElseThrow().equity().toPlainString());
        }
    }

    // ------------------------------------------------------------------ 夹具

    private static Path journal(String name) throws Exception {
        return Files.createTempDirectory("report-context-" + name + "-").resolve("world.journal");
    }

    private static WorldRuntime open(Path file) throws Exception {
        return WorldWebScenario.engineeringRuntime(file, WorldWebScenario.engineeringConfig());
    }

    /** 真实董事会授权 → 登记 → 除息（与已交付分红链同一入口）。 */
    private static void runDividendToEx(WorldRuntime runtime) throws Exception {
        runtime.scheduleBoardMeeting("c-meeting", "t-meeting", T0, "s1", "m1",
                T0 + 5_000L, T0 + 10_000L, CLOSES, List.of("s1", "s2", "s3", "s4", "s5"),
                List.of(), BoardModel.Topic.DIVIDEND_AUTHORIZATION);
        runtime.scheduleDividendProposal("c-proposal", "t-proposal", T0 + 1L, "s1", "m1",
                List.of(), new DividendModel.SchemeRequest("S1", SYMBOL, COMPANY_OWNER,
                        COMPANY_ACCOUNT, new BigDecimal("1.00"), RECORD, EX, PAY));
        for (String seat : List.of("s1", "s2", "s3")) {
            runtime.scheduleBoardVote("c-vote-" + seat, "t-vote-" + seat, T0 + 10_100L, seat,
                    "m1", BoardModel.Vote.YES);
        }
        runtime.advanceTo(CLOSES);
        runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
        runtime.advanceTo(EX);
    }

    private static DividendModel.Claim claimOf(DividendModel.State state, String accountId) {
        return DividendModel.claims(state, "S1").stream()
                .filter(value -> value.accountId().equals(accountId)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少受益账户债权：" + accountId));
    }

    private static DividendModel.State dividends(WorldRuntime runtime) {
        return runtime.snapshot().dividends().orElseThrow();
    }

    /**
     * 只读反射：取当前装配的世界状态（私有嵌套记录），不修改任何字段。
     *
     * <p>{@code WorldState} 是 {@link WorldRuntime} 的私有嵌套类型，测试只能以 {@link Object}
     * 持有并按同一实例回传私有报表方法；不读取也不写入任何业务字段。</p>
     */
    private static Object stateOf(WorldRuntime runtime) throws Exception {
        Field field = WorldRuntime.class.getDeclaredField("state");
        field.setAccessible(true);
        return field.get(runtime);
    }

    /** 只读反射：按给定历史基准重算报表上下文。 */
    private static EngineeringModel.ReportContext reportContext(WorldRuntime runtime, Object base)
            throws Exception {
        Method target = null;
        for (Method candidate : WorldRuntime.class.getDeclaredMethods()) {
            if (candidate.getName().equals("reportContextOf")
                    && candidate.getParameterCount() == 2) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            throw new AssertionError("缺少 reportContextOf(WorldState, String)");
        }
        target.setAccessible(true);
        return (EngineeringModel.ReportContext) target.invoke(runtime, base, ENGINE);
    }
}
