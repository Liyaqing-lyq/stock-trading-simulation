package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;
import edu.cufe.auction.world.OpeningRegistry.Security;
import edu.cufe.auction.world.OpeningRegistry.ShareAllocation;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.RoleTradingModel.ChildRequest;
import edu.cufe.auction.world.RoleTradingModel.Citation;
import edu.cufe.auction.world.RoleTradingModel.ResearchRequest;
import edu.cufe.auction.world.RoleTradingModel.ResearchResult;
import edu.cufe.auction.world.RoleTradingModel.Result;
import edu.cufe.auction.world.RoleTradingModel.ReviewRequest;
import edu.cufe.auction.world.RoleTradingModel.State;
import edu.cufe.auction.world.RoleTradingModel.TargetRequest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 固定接口说明（供实现者与 Root 核对，不属于生产代码）：
 * 纯核签名以本测试的调用为准；模型只保存研究元数据/目标版本/风险审核/子单链接，
 * 现金、持仓、成交与额度一律由调用方传入的 WorldExchange/SecuritiesLedger 当前状态派生。
 */

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4c Task1 纯工作流测试：四岗分权、真实阅读证据、目标/审核/确认、绝对目标与额度、真实钱股边界。
 *
 * <p>本测试是施工接口的第一份固定调用方。它只使用既有纯核（RoleAccess、InformationModel、
 * WorldExchange、SecuritiesLedger）构造有限夹具，不启动 Runtime、不写日志、不调用模型。
 * {@link RoleTradingModel} 尚不存在时，编译失败本身就是「能力不存在」的 RED 证据；夹具本身
 * 必须能独立通过既有 API 的类型检查，不能用不存在的枚举或方法制造假 RED。</p>
 *
 * <p>时间线固定在 0–300000 模拟毫秒：研究源资料 0→1000 读完，研究报告 2000 发布并在
 * 2000→62000 被经理读完，目标 62000 建立、63000 审核、64000 确认，子单 65000 起，
 * 执行员任职 300000 到期，目标截止 200000，风控有效期 250000。</p>
 */
class RoleTradingModelTest {

    private static final String ORG = "ORG";
    private static final String ISSUER = "CO";
    private static final String PRODUCT = "FUND";
    private static final String OPP = "OPP";
    private static final String FUND_ACCOUNT = "fund-acc";
    private static final String OPP_ACCOUNT = "opp-acc";
    private static final String SYMBOL = "S";

    private static final String AMY = "amy";   // 研究员
    private static final String BOB = "bob";   // 经理
    private static final String CARA = "cara"; // 风控
    private static final String DAN = "dan";   // 执行员

    private static final String AMY_APPOINTMENT = "apt-amy";
    private static final String BOB_APPOINTMENT = "apt-bob";
    private static final String CARA_APPOINTMENT = "apt-cara";
    private static final String DAN_APPOINTMENT = "apt-dan";

    private static final long SOURCE_DUE = 1_000L;
    private static final long RESEARCH_AT = 2_000L;
    private static final long RESEARCH_DUE = RESEARCH_AT + RoleTradingModel.RESEARCH_READ_MILLIS;
    private static final long TARGET_AT = 62_000L;
    private static final long REVIEW_AT = 63_000L;
    private static final long CONFIRM_AT = 64_000L;
    private static final long CHILD_AT = 65_000L;
    private static final long DEADLINE = 200_000L;
    private static final long RISK_UNTIL = DEADLINE;
    private static final long DAN_ENDS = 300_000L;

    @Test
    void fourRolesRemainSeparatedAndCrossOrganizationIsDenied() {
        Fixture fixture = new Fixture();
        RoleAccess.State access = fixture.access;

        assertEquals(RoleAccess.Decision.ALLOW, RoleAccess.authorize(access, AMY,
                RoleAccess.Action.WRITE_RESEARCH,
                RoleAccess.Target.organization(ORG), 10_000L));
        assertEquals(RoleAccess.Decision.NOT_AUTHORIZED, RoleAccess.authorize(access, AMY,
                RoleAccess.Action.SET_PORTFOLIO_TARGET,
                RoleAccess.Target.product(ORG, PRODUCT), 10_000L));
        assertEquals(RoleAccess.Decision.NOT_AUTHORIZED, RoleAccess.authorize(access, AMY,
                RoleAccess.Action.EXECUTE_ORDER,
                RoleAccess.Target.account(ORG, PRODUCT, FUND_ACCOUNT), 10_000L));

        assertEquals(RoleAccess.Decision.NOT_AUTHORIZED, RoleAccess.authorize(access, BOB,
                RoleAccess.Action.EXECUTE_ORDER,
                RoleAccess.Target.account(ORG, PRODUCT, FUND_ACCOUNT), 10_000L));
        assertEquals(RoleAccess.Decision.ALLOW, RoleAccess.authorize(access, BOB,
                RoleAccess.Action.SET_PORTFOLIO_TARGET,
                RoleAccess.Target.product(ORG, PRODUCT), 10_000L));

        assertEquals(RoleAccess.Decision.NOT_AUTHORIZED, RoleAccess.authorize(access, DAN,
                RoleAccess.Action.SET_PORTFOLIO_TARGET,
                RoleAccess.Target.product(ORG, PRODUCT), 10_000L));
        assertEquals(RoleAccess.Decision.ALLOW, RoleAccess.authorize(access, DAN,
                RoleAccess.Action.EXECUTE_ORDER,
                RoleAccess.Target.account(ORG, PRODUCT, FUND_ACCOUNT), 10_000L));

        assertEquals(RoleAccess.Decision.NOT_AUTHORIZED, RoleAccess.authorize(access, CARA,
                RoleAccess.Action.WRITE_RESEARCH,
                RoleAccess.Target.organization(ORG), 10_000L));
        assertEquals(RoleAccess.Decision.NOT_AUTHORIZED, RoleAccess.authorize(access, DAN,
                RoleAccess.Action.EXECUTE_ORDER,
                RoleAccess.Target.account("OTHER-ORG", PRODUCT, FUND_ACCOUNT), 10_000L));
    }

    @Test
    void researchRequiresActuallyReadFixedVersionAndPublishesOnlyMetadataReference() {
        Fixture fixture = new Fixture(false, DAN_ENDS);
        RoleTradingModel.State trading = RoleTradingModel.open();
        InformationModel.State baselineInformation;

        ResearchRequest beforeReading = researchRequest("res-1", "note-1");
        ResearchResult unread = RoleTradingModel.submitResearch(trading, fixture.access,
                fixture.information, fixture.registry, beforeReading, RESEARCH_AT);
        assertFalse(unread.accepted(), "未实际读完源资料不能提交研究意见");
        assertEquals(RoleTradingModel.REASON_EVIDENCE_NOT_READ, unread.reason());
        baselineInformation = unread.information();
        assertEquals(0, unread.state().research().size());
        assertEquals(baselineInformation.documents().size(), fixture.information.documents().size(),
                "拒绝不得发表任何资料");

        fixture.information = fixture.startAndComplete(AMY, "source-1", 1L, 0L, SOURCE_DUE);
        ResearchResult accepted = RoleTradingModel.submitResearch(unread.state(), fixture.access,
                fixture.information, fixture.registry, beforeReading, RESEARCH_AT);
        assertTrue(accepted.accepted(), accepted.reason());
        assertEquals(1, accepted.state().research().size());

        RoleTradingModel.Research note = accepted.state().research().get(0);
        assertEquals("note-1", note.docId());
        assertEquals(1L, note.docVersion());
        assertEquals(AMY, note.authorPersonPartyId());
        assertEquals(ORG, note.organizationPartyId());
        assertEquals(PRODUCT, note.productOwnerId());
        assertEquals(SYMBOL, note.symbol());
        assertEquals(1, note.citations().size());

        InformationModel.Document report = document(accepted.information(), "note-1");
        assertNotNull(report, "研究报告正文只能作为 InformationModel 固定组织文档存在");
        assertEquals(InformationModel.AudienceKind.ORGANIZATION, report.audience().kind());
        assertEquals(ORG, report.audience().partyId());
        assertEquals(AMY, report.publisherPartyId());
        assertTrue(report.versions().get(0).body().contains("订单改善"));

        String json = RoleTradingModel.toJson(accepted.state());
        assertFalse(json.contains("订单改善"), "交易状态不能另存研究正文");
        assertFalse(json.contains("\"cash\""), "交易状态不能保存现金副本");
    }

    @Test
    void targetRequiresManagerToHaveReadTheResearchReport() {
        Fixture fixture = new Fixture();
        State trading = fixture.researched();
        InformationModel.State information = fixture.information;

        Result tooEarly = RoleTradingModel.setTarget(trading, fixture.access, information,
                fixture.securities, targetRequest("tgt-1", 0, 100, "10.00"), RESEARCH_DUE - 1);
        assertFalse(tooEarly.accepted(), "经理未读完研究报告不能设目标");
        assertEquals(RoleTradingModel.REASON_EVIDENCE_NOT_READ, tooEarly.reason());

        Result created = RoleTradingModel.setTarget(trading, fixture.access, information,
                fixture.securities, targetRequest("tgt-1", 0, 100, "10.00"), TARGET_AT);
        assertTrue(created.accepted(), created.reason());
        RoleTradingModel.Target target = RoleTradingModel.target(created.state(), "tgt-1");
        assertEquals(RoleTradingModel.Status.PENDING_REVIEW, target.status());
        assertEquals(1, target.versions().size());
        assertEquals(0L, target.versions().get(0).baseQuantity());

        Result duplicate = RoleTradingModel.setTarget(created.state(), fixture.access, information,
                fixture.securities, targetRequest("tgt-other", 0, 50, "10.00"), TARGET_AT);
        assertFalse(duplicate.accepted(), "同一账户/标的不能同时有两个在处理母任务");
        assertEquals(RoleTradingModel.REASON_DUPLICATE_TARGET, duplicate.reason());
    }

    @Test
    void childOrdersRequireRiskReviewAndManagerConfirmation() {
        Fixture fixture = new Fixture();
        State pending = fixture.targetPendingReview();
        ChildRequest request = child("child-1", 1, 1, 20, "10.00");

        Result beforeReview = RoleTradingModel.admitChild(pending, fixture.access,
                fixture.exchange, fixture.securities, request, CHILD_AT);
        assertFalse(beforeReview.accepted(), "未审核不能下子单");
        assertEquals(RoleTradingModel.REASON_REVIEW_REQUIRED, beforeReview.reason());

        Result reviewed = fixture.review(pending, true, 100, 40, "10.00", RISK_UNTIL);
        assertTrue(reviewed.accepted(), reviewed.reason());
        Result beforeConfirm = RoleTradingModel.admitChild(reviewed.state(), fixture.access,
                fixture.exchange, fixture.securities, request, CHILD_AT);
        assertFalse(beforeConfirm.accepted(), "未确认不能下子单");
        assertEquals(RoleTradingModel.REASON_NOT_CONFIRMED, beforeConfirm.reason());

        Result confirmed = RoleTradingModel.confirmTarget(reviewed.state(), fixture.access,
                "tgt-1", 1, 1, BOB, BOB_APPOINTMENT, CONFIRM_AT);
        assertTrue(confirmed.accepted(), confirmed.reason());
        assertEquals(RoleTradingModel.Status.ACTIVE,
                RoleTradingModel.target(confirmed.state(), "tgt-1").status());

        Result accepted = RoleTradingModel.admitChild(confirmed.state(), fixture.access,
                fixture.exchange, fixture.securities, request, CHILD_AT);
        assertTrue(accepted.accepted(), accepted.reason());
    }

    @Test
    void quotaCountsFilledAndLiveLeavesAcrossOneHundredShareTarget() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");

        active = fixture.order(active, "child-1", 40, "10.00", 66_000L);
        assertEquals(40L, RoleTradingModel.used(active, fixture.exchange, "tgt-1", Side.BUY));
        Result second = RoleTradingModel.admitChild(active, fixture.access, fixture.exchange,
                fixture.securities, child("child-2", 1, 1, 40, "10.00"), 67_000L);
        assertTrue(second.accepted(), second.reason());
        active = fixture.order(active, "child-2", 40, "10.00", 67_100L);
        assertEquals(80L, RoleTradingModel.used(active, fixture.exchange, "tgt-1", Side.BUY));

        Result over = RoleTradingModel.admitChild(active, fixture.access, fixture.exchange,
                fixture.securities, child("child-3", 1, 1, 30, "10.00"), 68_000L);
        assertFalse(over.accepted(), "40+40+30 超过绝对目标 100");
        assertEquals(RoleTradingModel.REASON_QUOTA_EXCEEDED, over.reason());

        fixture.opponentSells(10, "10.00", 69_000L);
        assertEquals(80L, RoleTradingModel.used(active, fixture.exchange, "tgt-1", Side.BUY),
                "部分成交后 filled 10 + leaves 30 + 40 仍占 80");

        Result fillGap = RoleTradingModel.admitChild(active, fixture.access, fixture.exchange,
                fixture.securities, child("child-4", 1, 1, 20, "10.00"), 70_000L);
        assertTrue(fillGap.accepted(), "部分成交释放的是 leaves 而非额度");
        active = fixture.order(active, "child-4", 20, "10.00", 70_100L);
        assertEquals(100L, RoleTradingModel.used(active, fixture.exchange, "tgt-1", Side.BUY));

        Result oneMore = RoleTradingModel.admitChild(active, fixture.access, fixture.exchange,
                fixture.securities, child("child-5", 1, 1, 1, "10.00"), 71_000L);
        assertFalse(oneMore.accepted());
        assertEquals(RoleTradingModel.REASON_QUOTA_EXCEEDED, oneMore.reason());
        assertTrue(fixture.totalCash().compareTo(new BigDecimal("1000.00")) == 0,
                "角色工作流不能自行改钱");
    }

    @Test
    void cancelKeepsQuotaUntilTheRealCancelIsApplied() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");
        active = fixture.order(active, "child-1", 40, "10.00", 66_000L);

        Result halted = RoleTradingModel.haltTarget(active, fixture.access, "halt-1", "tgt-1",
                1, RISK_UNTIL, "集中度超限", CARA, CARA_APPOINTMENT, 66_100L);
        assertTrue(halted.accepted(), halted.reason());

        Result blocked = RoleTradingModel.admitChild(halted.state(), fixture.access,
                fixture.exchange, fixture.securities, child("child-2", 1, 1, 10, "10.00"), 66_200L);
        assertFalse(blocked.accepted());
        assertEquals(RoleTradingModel.REASON_HALTED, blocked.reason());

        assertEquals(40L, RoleTradingModel.used(halted.state(), fixture.exchange, "tgt-1", Side.BUY),
                "暂停后、真实撤单提交前，挂单仍占额度");

        WorldExchange.Outcome cancelled = WorldExchange.cancel(fixture.exchange, fixture.accounts,
                fixture.securities, PRODUCT, fixture.lastOrderId, 66_300L);
        assertTrue(cancelled.accepted(), cancelled.reason());
        assertEquals(0L, RoleTradingModel.used(halted.state(), cancelled.exchange(), "tgt-1",
                Side.BUY), "撤单真实生效后才释放额度");
        assertEquals(40L, RoleTradingModel.used(halted.state(), fixture.exchange, "tgt-1", Side.BUY),
                "只看候选状态时旧挂单仍应占用");
    }

    @Test
    void updateRecordsNewBaseFromRealHoldingAndRecalculatesAbsoluteCapacity() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");

        active = fixture.order(active, "child-1", 40, "10.00", 66_000L);
        active = fixture.order(active, "child-2", 40, "10.00", 66_100L);
        fixture.opponentSells(30, "10.00", 66_200L); // child-1 成交 30，持仓 30
        Result child4 = RoleTradingModel.admitChild(active, fixture.access, fixture.exchange,
                fixture.securities, child("child-4", 1, 1, 20, "10.00"), 66_300L);
        assertTrue(child4.accepted(), child4.reason());
        active = fixture.order(active, "child-4", 20, "10.00", 66_400L);

        Result updating = RoleTradingModel.setTarget(active, fixture.access,
                fixture.information, fixture.securities,
                targetRequest("tgt-1", 1, 120, "10.00"), 66_500L);
        assertTrue(updating.accepted(), updating.reason());
        assertEquals(RoleTradingModel.Status.UPDATING,
                RoleTradingModel.target(updating.state(), "tgt-1").status());

        fixture.opponentSells(10, "10.00", 66_600L); // 撤单前仍可合法成交：持仓 40
        assertEquals(40L, RoleTradingModel.held(fixture.securities, FUND_ACCOUNT, SYMBOL));

        Result settledTooEarly = RoleTradingModel.settleUpdate(updating.state(), fixture.exchange,
                fixture.securities, "tgt-1", 66_700L);
        assertFalse(settledTooEarly.accepted(), "旧版本活动挂单未撤净不能进入新版本");
        assertEquals(RoleTradingModel.Status.UPDATING,
                RoleTradingModel.target(settledTooEarly.state(), "tgt-1").status());

        // 真实撤净旧版本全部活动子单（child-1/child-2/child-4 的剩余量）。
        for (long orderId : new ArrayList<>(fixture.liveOrderIds())) {
            WorldExchange.Outcome cancelled = WorldExchange.cancel(fixture.exchange, fixture.accounts,
                    fixture.securities, PRODUCT, orderId, 66_800L);
            assertTrue(cancelled.accepted(), cancelled.reason());
            fixture.exchange = cancelled.exchange();
            fixture.securities = cancelled.securities();
            fixture.accounts.clear();
            fixture.accounts.putAll(cancelled.accounts());
        }
        assertEquals(40L, RoleTradingModel.used(updating.state(), fixture.exchange, "tgt-1", Side.BUY));

        Result settled = RoleTradingModel.settleUpdate(updating.state(), fixture.exchange,
                fixture.securities, "tgt-1", 66_900L);
        assertTrue(settled.accepted(), settled.reason());
        RoleTradingModel.Target target = RoleTradingModel.target(settled.state(), "tgt-1");
        assertEquals(RoleTradingModel.Status.PENDING_REVIEW, target.status());
        assertEquals(2, target.versions().size());
        assertEquals(40L, target.versions().get(1).baseQuantity(), "新版本基点取撤净时真实持仓");
        assertEquals(80L, target.versions().get(1).absoluteTargetQuantity()
                - target.versions().get(1).baseQuantity());

        Result tooLarge = fixture.review(settled.state(), true, 81, 40, "10.00", RISK_UNTIL);
        assertFalse(tooLarge.accepted(), "风控批准量不得超过经理变动量 80");
        assertEquals(RoleTradingModel.REASON_INVALID_LIMITS, tooLarge.reason());

        Result reviewed = fixture.review(settled.state(), true, 80, 80, "10.00", RISK_UNTIL);
        assertTrue(reviewed.accepted(), reviewed.reason());
        Result confirmed = RoleTradingModel.confirmTarget(reviewed.state(), fixture.access,
                "tgt-1", 2, 1, BOB, BOB_APPOINTMENT, 67_000L);
        assertTrue(confirmed.accepted(), confirmed.reason());

        Result staleVersion = RoleTradingModel.admitChild(confirmed.state(), fixture.access,
                fixture.exchange, fixture.securities, child("child-old", 1, 1, 10, "10.00"), 67_100L);
        assertFalse(staleVersion.accepted());
        assertEquals(RoleTradingModel.REASON_STALE_VERSION, staleVersion.reason());

        Result eighty = RoleTradingModel.admitChild(confirmed.state(), fixture.access,
                fixture.exchange, fixture.securities, child("child-5", 2, 1, 80, "10.00"), 67_200L);
        assertTrue(eighty.accepted(), eighty.reason());
        Result eightyOne = RoleTradingModel.admitChild(confirmed.state(), fixture.access,
                fixture.exchange, fixture.securities, child("child-6", 2, 1, 81, "10.00"), 67_300L);
        assertFalse(eightyOne.accepted());
    }

    @Test
    void reverseTargetCountsOnlySellSideFromCurrentHolding() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");
        active = fixture.order(active, "child-1", 40, "10.00", 66_000L);
        fixture.opponentSells(40, "10.00", 66_100L); // 持仓 40、成交 40

        Result updating = RoleTradingModel.setTarget(active, fixture.access,
                fixture.information, fixture.securities,
                targetRequest("tgt-1", 1, 20, "10.00"), 66_200L);
        assertTrue(updating.accepted(), updating.reason());
        Result settled = RoleTradingModel.settleUpdate(updating.state(), fixture.exchange,
                fixture.securities, "tgt-1", 66_300L);
        assertTrue(settled.accepted(), settled.reason());
        RoleTradingModel.TargetVersion version =
                RoleTradingModel.target(settled.state(), "tgt-1").versions().get(1);
        assertEquals(40L, version.baseQuantity());
        assertEquals(20L, version.absoluteTargetQuantity());
        assertEquals(20L, Math.abs(version.baseQuantity() - version.absoluteTargetQuantity()));

        Result reviewed = fixture.review(settled.state(), true, 20, 20, "10.00", RISK_UNTIL);
        assertTrue(reviewed.accepted(), reviewed.reason());
        Result confirmed = RoleTradingModel.confirmTarget(reviewed.state(), fixture.access,
                "tgt-1", 2, 1, BOB, BOB_APPOINTMENT, 66_400L);
        assertTrue(confirmed.accepted(), confirmed.reason());

        assertEquals(0L, RoleTradingModel.used(confirmed.state(), fixture.exchange, "tgt-1",
                Side.SELL), "旧 BUY 成交不能算进 SELL 额度");
        Result sellAll = RoleTradingModel.admitChild(confirmed.state(), fixture.access,
                fixture.exchange, fixture.securities, child("child-sell", 2, 1, 20, "10.00"), 66_500L);
        assertTrue(sellAll.accepted(), sellAll.reason());
        assertEquals(Side.SELL, RoleTradingModel.target(confirmed.state(), "tgt-1").versions().get(1)
                .absoluteTargetQuantity() > sellAll.state().targets().get(0).versions().get(1)
                .baseQuantity() ? Side.BUY : Side.SELL);
        Result sellOneMore = RoleTradingModel.admitChild(confirmed.state(), fixture.access,
                fixture.exchange, fixture.securities,
                child("child-sell-2", 2, 1, 21, "10.00"), 66_600L);
        assertFalse(sellOneMore.accepted());
        assertEquals(RoleTradingModel.REASON_QUOTA_EXCEEDED, sellOneMore.reason());
    }

    @Test
    void deadlineAndAppointmentAreRightOpen() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");

        Result atDeadline = RoleTradingModel.admitChild(active, fixture.access, fixture.exchange,
                fixture.securities, child("child-deadline", 1, 1, 10, "10.00"), DEADLINE);
        assertFalse(atDeadline.accepted());
        assertEquals(RoleTradingModel.REASON_EXPIRED, atDeadline.reason());

        Result beforeDeadline = RoleTradingModel.admitChild(active, fixture.access,
                fixture.exchange, fixture.securities,
                child("child-before", 1, 1, 10, "10.00"), DEADLINE - 1);
        assertTrue(beforeDeadline.accepted(), beforeDeadline.reason());

        Fixture shortAppointment = new Fixture(true, 150_000L);
        State shortActive = shortAppointment.activeTarget(100, 40, "10.00");
        Result atAppointmentEnd = RoleTradingModel.admitChild(shortActive, shortAppointment.access,
                shortAppointment.exchange, shortAppointment.securities,
                child("child-end", 1, 1, 10, "10.00"), 150_000L);
        assertFalse(atAppointmentEnd.accepted(), "任职右开：到期当刻已无权");
        assertEquals(RoleTradingModel.REASON_NOT_AUTHORIZED, atAppointmentEnd.reason());

        Result expiredTarget = RoleTradingModel.expireTarget(active, "tgt-1", DEADLINE);
        assertTrue(expiredTarget.accepted(), expiredTarget.reason());
        assertEquals(RoleTradingModel.Status.EXPIRED,
                RoleTradingModel.target(expiredTarget.state(), "tgt-1").status());
    }

    @Test
    void linkingRejectsAnOrderThatDoesNotExist() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");
        Result linked = RoleTradingModel.linkOrder(active, fixture.access, fixture.exchange,
                child("ghost", 1, 1, 10, "10.00"), 424242);
        assertFalse(linked.accepted(), "未受理的订单号不得进入额度链接");
    }

    @Test
    void linkingRejectsTheOppositeSideEvenForTheSameAccountAndSymbol() {
        Fixture fixture = new Fixture(true, DAN_ENDS, 50);
        State active = fixture.activeTarget(100, 40, "10.00");
        assertEquals(50, RoleTradingModel.target(active, "tgt-1").current().baseQuantity());
        WorldExchange.Outcome sell = WorldExchange.submit(fixture.exchange, fixture.accounts,
                fixture.securities, "task-wrong-side", "wrong-side", PRODUCT, FUND_ACCOUNT, SYMBOL,
                Side.SELL, OrderType.LIMIT, money("10.00"), 10, CHILD_AT);
        assertTrue(sell.accepted(), sell.reason());
        Result linked = RoleTradingModel.linkOrder(active, fixture.access, sell.exchange(),
                child("wrong-side", 1, 1, 10, "10.00"), sell.orderId());
        assertFalse(linked.accepted(), "BUY目标不能把同账户的真实SELL订单当作子单");
    }

    @Test
    void linkingRejectsAnOrderFromAnotherTask() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");
        WorldExchange.Outcome actual = WorldExchange.submit(fixture.exchange, fixture.accounts,
                fixture.securities, "task-real", "real", PRODUCT, FUND_ACCOUNT, SYMBOL,
                Side.BUY, OrderType.LIMIT, money("10.00"), 10, CHILD_AT);
        assertTrue(actual.accepted());
        Result linked = RoleTradingModel.linkOrder(active, fixture.access, actual.exchange(),
                child("other-task", 1, 1, 10, "10.00"), actual.orderId());
        assertFalse(linked.accepted(), "子单任务必须与真实订单原任务相同");
    }

    @Test
    void linkingDoesNotGrantTheManagerExecutionAuthority() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");
        WorldExchange.Outcome actual = WorldExchange.submit(fixture.exchange, fixture.accounts,
                fixture.securities, "task-manager", "real", PRODUCT, FUND_ACCOUNT, SYMBOL,
                Side.BUY, OrderType.LIMIT, money("10.00"), 10, CHILD_AT);
        assertTrue(actual.accepted());
        ChildRequest manager = new ChildRequest("tgt-1", 1, 1, "manager", BOB, BOB_APPOINTMENT,
                10, money("10.00"), "task-manager");
        assertFalse(RoleTradingModel.linkOrder(active, fixture.access, actual.exchange(),
                manager, actual.orderId()).accepted());
    }

    @Test
    void childCannotUseATargetOrReviewFromTheFuture() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");
        assertFalse(RoleTradingModel.admitChild(active, fixture.access, fixture.exchange,
                fixture.securities, child("future-target", 1, 1, 10, "10.00"), TARGET_AT - 1).accepted());
        assertFalse(RoleTradingModel.admitChild(active, fixture.access, fixture.exchange,
                fixture.securities, child("future-review", 1, 1, 10, "10.00"), REVIEW_AT - 1).accepted());
    }

    @Test
    void fractionalPriceIsAParameterError() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChildRequest("tgt-1", 1, 1, "fractional", DAN, DAN_APPOINTMENT,
                        10, new BigDecimal("10.005"), "task-fractional"));
    }

    @Test
    void haltedJsonNeedsARealFutureHaltLimit() throws Exception {
        Fixture fixture = new Fixture();
        ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(
                RoleTradingModel.toJson(fixture.activeTarget(100, 40, "10.00")));
        ((ObjectNode) tree.path("targets").get(0)).put("status", "HALTED");
        assertThrows(IllegalArgumentException.class, () -> RoleTradingModel.fromJson(tree.toString()));
    }

    @Test
    void activeJsonCannotCarryAHaltLimit() throws Exception {
        Fixture fixture = new Fixture();
        ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(
                RoleTradingModel.toJson(fixture.activeTarget(100, 40, "10.00")));
        ((ObjectNode) tree.path("targets").get(0)).put("haltedUntilMillis", 190_000);
        assertThrows(IllegalArgumentException.class, () -> RoleTradingModel.fromJson(tree.toString()));
    }

    @Test
    void longQuantityKeepsPrecisionAndFiniteCashStillBlocksTheActualOrder() {
        Fixture fixture = new Fixture();
        State researched = fixture.researched();
        Result target = RoleTradingModel.setTarget(researched, fixture.access, fixture.information,
                fixture.securities, targetRequest("tgt-1", 0, Long.MAX_VALUE, "0.01"), TARGET_AT);
        assertTrue(target.accepted(), target.reason());
        Result reviewed = fixture.review(target.state(), true, Long.MAX_VALUE, Long.MAX_VALUE,
                "0.01", RISK_UNTIL);
        assertTrue(reviewed.accepted(), reviewed.reason());
        Result confirmed = RoleTradingModel.confirmTarget(reviewed.state(), fixture.access,
                "tgt-1", 1, 1, BOB, BOB_APPOINTMENT, CONFIRM_AT);
        assertTrue(confirmed.accepted(), confirmed.reason());
        Result admitted = RoleTradingModel.admitChild(confirmed.state(), fixture.access,
                fixture.exchange, fixture.securities,
                child("long-child", 1, 1, Long.MAX_VALUE, "0.01"), CHILD_AT);
        assertTrue(admitted.accepted(), admitted.reason());
        WorldExchange.Outcome actual = WorldExchange.submit(fixture.exchange, fixture.accounts,
                fixture.securities, "long-task", "long-command", PRODUCT, FUND_ACCOUNT, SYMBOL,
                Side.BUY, OrderType.LIMIT, money("0.01"), Long.MAX_VALUE, CHILD_AT);
        assertFalse(actual.accepted(), "真实有限现金仍须阻止巨额名义订单");
        assertEquals(WorldExchange.REASON_INSUFFICIENT_CASH, actual.reason());
        assertEquals(new BigDecimal("1000.00"), actual.accounts().get(FUND_ACCOUNT).cash());
        assertTrue(actual.exchange().orders().isEmpty());
        assertTrue(admitted.state().children().isEmpty(), "交易拒单不得建立子单链接");
    }

    @Test
    void cancelledTargetCannotReviveBesideAnotherCurrentTarget() {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");
        Result cancelled = RoleTradingModel.cancelTarget(active, fixture.access, "tgt-1", 1,
                BOB, BOB_APPOINTMENT, 65_000L);
        assertTrue(cancelled.accepted());
        Result other = RoleTradingModel.setTarget(cancelled.state(), fixture.access,
                fixture.information, fixture.securities,
                targetRequest("tgt-other", 0, 50, "10.00"), 66_000L);
        assertTrue(other.accepted(), other.reason());
        Result revived = RoleTradingModel.setTarget(other.state(), fixture.access,
                fixture.information, fixture.securities,
                targetRequest("tgt-1", 1, 100, "10.00"), 67_000L);
        assertFalse(revived.accepted(), "同一账户标的已有当前目标时不能复活旧目标");
        assertEquals(RoleTradingModel.REASON_DUPLICATE_TARGET, revived.reason());
    }

    @Test
    void loadingRejectsTwoCurrentTargetsForSameAccountAndSymbol() throws Exception {
        Fixture fixture = new Fixture();
        ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(
                RoleTradingModel.toJson(fixture.targetPendingReview()));
        ObjectNode other = ((ObjectNode) tree.path("targets").get(0)).deepCopy();
        other.put("targetId", "tgt-other");
        tree.withArray("targets").add(other);
        assertThrows(IllegalArgumentException.class, () -> RoleTradingModel.fromJson(tree.toString()));
    }

    @Test
    void loadingRejectsRiskQuantityAboveManagerChange() throws Exception {
        Fixture fixture = new Fixture();
        ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(
                RoleTradingModel.toJson(fixture.activeTarget(100, 40, "10.00")));
        ((ObjectNode) tree.path("reviews").get(0)).put("maxTotalQuantity", 101);
        assertThrows(IllegalArgumentException.class, () -> RoleTradingModel.fromJson(tree.toString()));
    }

    @Test
    void loadingRejectsSkippedRiskRevision() throws Exception {
        Fixture fixture = new Fixture();
        ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(
                RoleTradingModel.toJson(fixture.activeTarget(100, 40, "10.00")));
        ((ObjectNode) tree.path("reviews").get(0)).put("revision", 2);
        assertThrows(IllegalArgumentException.class, () -> RoleTradingModel.fromJson(tree.toString()));
    }

    @Test
    void serializationRoundTripsAndRejectsAForgedVersionGap() throws Exception {
        Fixture fixture = new Fixture();
        State active = fixture.activeTarget(100, 40, "10.00");
        active = fixture.order(active, "child-1", 40, "10.00", 66_000L);

        String json = RoleTradingModel.toJson(active);
        State restored = RoleTradingModel.fromJson(json);
        assertEquals(active, restored);
        assertEquals(40L, RoleTradingModel.used(restored, fixture.exchange, "tgt-1", Side.BUY));

        ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(json);
        ((ObjectNode) tree.path("targets").get(0).path("versions").get(0)).put("version", 3);
        String forged = tree.toString();
        assertNotEquals(json, forged);
        assertThrows(IllegalArgumentException.class, () -> RoleTradingModel.fromJson(forged),
                "版本断号必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> RoleTradingModel.fromJson("{\"research\":[]}"));
    }

    // ---------------------------------------------------------------- 夹具与辅助

    /** 有限夹具：一个产品账户 1000.00、外部卖方 100 股、四岗任职与一篇已读源资料。 */
    private static final class Fixture {

        final OpeningRegistry registry;
        final List<OpeningAccount> openings;
        final RoleAccess.State access;
        InformationModel.State information;
        SecuritiesLedger.State securities;
        WorldExchange.State exchange;
        final Map<String, CashLedger.CashAccount> accounts = new TreeMap<>();
        final long initialFundQuantity;
        long lastOrderId;

        Fixture() {
            this(true, DAN_ENDS);
        }

        Fixture(boolean readSource, long traderEndsAt) {
            this(readSource, traderEndsAt, 0);
        }

        Fixture(boolean readSource, long traderEndsAt, long initialFundQuantity) {
            this.initialFundQuantity = initialFundQuantity;
            List<Party> parties = List.of(
                    new Party(ORG, PartyKind.ORGANIZATION, "虚构基金"),
                    new Party(ISSUER, PartyKind.ORGANIZATION, "虚构发行公司"),
                    new Party(AMY, PartyKind.PERSON, "研究员"),
                    new Party(BOB, PartyKind.PERSON, "经理"),
                    new Party(CARA, PartyKind.PERSON, "风控"),
                    new Party(DAN, PartyKind.PERSON, "执行员"));
            List<PropertyOwner> owners = List.of(
                    new PropertyOwner(PRODUCT, OwnerKind.PRODUCT, null),
                    new PropertyOwner(AMY, OwnerKind.PERSON, AMY),
                    new PropertyOwner(OPP, OwnerKind.AGGREGATE, null));
            List<Security> securityList = List.of(new Security(SYMBOL, ISSUER, 100, new BigDecimal("10.00")));
            List<ShareAllocation> allocations = new ArrayList<>();
            allocations.add(new ShareAllocation(OPP_ACCOUNT, SYMBOL, 100 - initialFundQuantity,
                    new BigDecimal("10.0000"), ShareClass.FLOAT));
            if (initialFundQuantity > 0) {
                allocations.add(new ShareAllocation(FUND_ACCOUNT, SYMBOL, initialFundQuantity,
                        new BigDecimal("10.0000"), ShareClass.FLOAT));
            }
            registry = new OpeningRegistry(parties, owners, securityList, allocations);

            openings = List.of(
                    new OpeningAccount(FUND_ACCOUNT, PRODUCT, new BigDecimal("1000.00"), "历史禀赋"),
                    new OpeningAccount(OPP_ACCOUNT, OPP, new BigDecimal("0.00"), "历史禀赋"),
                    new OpeningAccount("amy-cash", AMY, new BigDecimal("0.00"), "历史禀赋"));
            registry.validateAgainst(openings);

            RoleAccess.State withAppointments = RoleAccess.open(registry, openings,
                    List.of(new RoleAccess.ProductMandate(ORG, PRODUCT, List.of(FUND_ACCOUNT))));
            withAppointments = RoleAccess.appoint(registry, withAppointments, AMY_APPOINTMENT, AMY,
                    ORG, RoleAccess.Role.RESEARCHER, 0L, 1_000_000L, 0L);
            withAppointments = RoleAccess.appoint(registry, withAppointments, BOB_APPOINTMENT, BOB,
                    ORG, RoleAccess.Role.PORTFOLIO_MANAGER, 0L, 1_000_000L, 0L);
            withAppointments = RoleAccess.appoint(registry, withAppointments, CARA_APPOINTMENT, CARA,
                    ORG, RoleAccess.Role.RISK_OFFICER, 0L, 1_000_000L, 0L);
            withAppointments = RoleAccess.appoint(registry, withAppointments, DAN_APPOINTMENT, DAN,
                    ORG, RoleAccess.Role.EXECUTION_TRADER, 0L, traderEndsAt, 0L);
            access = withAppointments;

            for (OpeningAccount opening : openings) {
                accounts.put(opening.accountId(),
                        new CashLedger.CashAccount(opening.ownerId(), opening.cash(), opening.source()));
            }
            securities = SecuritiesLedger.opening(registry);
            exchange = WorldExchange.opening();

            information = InformationModel.open(registry, List.of(AMY, BOB, CARA, DAN));
            information = InformationModel.publish(information, "source-1", AMY,
                    InformationModel.Audience.organization(ORG),
                    new InformationModel.VersionDraft("订单线索", "渠道反馈订单改善，金额待证实。",
                            "调研纪要", 0L, SOURCE_DUE, List.of("internal-1")));
            if (readSource) {
                information = startAndComplete(AMY, "source-1", 1L, 0L, SOURCE_DUE);
            }
        }

        InformationModel.State startAndComplete(String person, String docId, long version,
                                                long startAt, long dueAt) {
            InformationModel.Outcome started = InformationModel.startRead(information, access,
                    person, docId, version, startAt);
            assertEquals(InformationModel.Status.STARTED, started.status(), started.status().name());
            String readId = started.state().active().get(started.state().active().size() - 1).readId();
            InformationModel.Outcome completed = InformationModel.completeRead(started.state(),
                    access, person, readId, dueAt);
            assertEquals(InformationModel.Status.COMPLETED, completed.status(),
                    completed.status().name());
            return completed.state();
        }

        State researched() {
            ResearchResult result = RoleTradingModel.submitResearch(RoleTradingModel.open(), access,
                    information, registry, researchRequest("res-1", "note-1"), RESEARCH_AT);
            assertTrue(result.accepted(), result.reason());
            information = result.information();
            information = startAndComplete(BOB, "note-1", 1L, RESEARCH_AT, RESEARCH_DUE);
            return result.state();
        }

        State targetPendingReview() {
            Result result = RoleTradingModel.setTarget(researched(), access, information,
                    securities, targetRequest("tgt-1", 0, 100, "10.00"), TARGET_AT);
            assertTrue(result.accepted(), result.reason());
            return result.state();
        }

        State activeTarget(long absoluteQuantity, long maxChild, String priceLimit) {
            State pending = targetPendingReview();
            Result reviewed = RoleTradingModel.reviewTarget(pending, access, securities,
                    new ReviewRequest("tgt-1", 1, true, absoluteQuantity - initialFundQuantity, maxChild, money(priceLimit),
                            RISK_UNTIL, "实验任务输入", CARA, CARA_APPOINTMENT), REVIEW_AT);
            assertTrue(reviewed.accepted(), reviewed.reason());
            Result confirmed = RoleTradingModel.confirmTarget(reviewed.state(), access, "tgt-1", 1, 1,
                    BOB, BOB_APPOINTMENT, CONFIRM_AT);
            assertTrue(confirmed.accepted(), confirmed.reason());
            return confirmed.state();
        }

        Result review(State base, boolean approved, long maxTotal, long maxChild, String price,
                      long until) {
            long reviewedAt = Math.max(REVIEW_AT,
                    RoleTradingModel.target(base, "tgt-1").versions().get(
                            RoleTradingModel.target(base, "tgt-1").versions().size() - 1)
                            .createdAtMillis() + 1);
            return RoleTradingModel.reviewTarget(base, access, securities,
                    new ReviewRequest("tgt-1",
                            RoleTradingModel.target(base, "tgt-1").versions().size(),
                            approved, maxTotal, maxChild, money(price), until, "复核", CARA,
                            CARA_APPOINTMENT), reviewedAt);
        }

        /** 走真实 WorldExchange：角色子单受理后按真实回报链接；对手盘用同一纯核成交。 */
        State order(State trading, String childId, long quantity, String price, long now) {
            Result admitted = RoleTradingModel.admitChild(trading, access, exchange, securities,
                    child(childId, 1, 1, quantity, price), now);
            assertTrue(admitted.accepted(), admitted.reason());
            WorldExchange.Outcome outcome = WorldExchange.submit(exchange, accounts, securities,
                    "task-" + childId, "cmd-" + childId, PRODUCT, FUND_ACCOUNT, SYMBOL, Side.BUY,
                    OrderType.LIMIT, money(price), quantity, now);
            assertTrue(outcome.accepted(), outcome.reason());
            exchange = outcome.exchange();
            securities = outcome.securities();
            accounts.clear();
            accounts.putAll(outcome.accounts());
            lastOrderId = outcome.orderId();
            Result linked = RoleTradingModel.linkOrder(admitted.state(), access, outcome.exchange(),
                    child(childId, 1, 1, quantity, price), outcome.orderId());
            assertTrue(linked.accepted(), linked.reason());
            return linked.state();
        }

        void opponentSells(long quantity, String price, long now) {
            WorldExchange.Outcome outcome = WorldExchange.submit(exchange, accounts, securities,
                    "opp-task-" + now, "opp-cmd-" + now, OPP, OPP_ACCOUNT, SYMBOL, Side.SELL,
                    OrderType.LIMIT, money(price), quantity, now);
            assertTrue(outcome.accepted(), outcome.reason());
            exchange = outcome.exchange();
            securities = outcome.securities();
            accounts.clear();
            accounts.putAll(outcome.accounts());
        }

        List<Long> liveOrderIds() {
            List<Long> ids = new ArrayList<>();
            for (WorldExchange.Order order : exchange.orders()) {
                if (order.live()) {
                    ids.add(order.orderId());
                }
            }
            return ids;
        }

        BigDecimal totalCash() {
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (CashLedger.CashAccount account : accounts.values()) {
                total = total.add(account.cash());
            }
            return total;
        }
    }

    private static ResearchRequest researchRequest(String researchId, String docId) {
        return new ResearchRequest(researchId, ORG, PRODUCT, SYMBOL, AMY, AMY_APPOINTMENT, docId,
                "S 公司订单线索", "渠道反馈订单改善，金额待证实，交付需持续核对。",
                List.of(new Citation("source-1", 1L)));
    }

    private static TargetRequest targetRequest(String targetId, int expectedVersion,
                                               long absoluteTargetQuantity, String priceLimit) {
        return new TargetRequest(targetId, expectedVersion, ORG, PRODUCT, FUND_ACCOUNT, SYMBOL,
                absoluteTargetQuantity, money(priceLimit), DEADLINE, "res-1", BOB, BOB_APPOINTMENT);
    }

    private static ChildRequest child(String childId, int parentVersion, int riskRevision,
                                      long quantity, String price) {
        return new ChildRequest("tgt-1", parentVersion, riskRevision, childId, DAN, DAN_APPOINTMENT,
                quantity, money(price), "task-" + childId);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static InformationModel.Document document(InformationModel.State state, String docId) {
        for (InformationModel.Document document : state.documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }
}
