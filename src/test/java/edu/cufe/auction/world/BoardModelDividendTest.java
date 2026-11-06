package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.BoardModel.BudgetCategory;
import edu.cufe.auction.world.BoardModel.MeetingStatus;
import edu.cufe.auction.world.BoardModel.Topic;
import edu.cufe.auction.world.BoardModel.Vote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2：董事会现金分红授权纯核（测试先行）。
 *
 * <p>覆盖正式施工单 `docs/superpowers/plans/2026-11-06-dividend-authorization.md`：
 * 五席三票通过、两票否决、关联回避与法定人数、缺席关联者不强制到会、提案人非自动关联、
 * 空提案到期闭合、经济内容不可改与重审、并行与重复实施拒绝、授权引用绑定真实来源、
 * 旧 JSON 逐字往返、旧 5 参 State / 14 参 Meeting 源码兼容、严格 JSON 与伪造拒绝。</p>
 *
 * <p>正常链一律使用真实 {@link OpeningRegistry}、{@link BoardModel} 与 D0 的
 * {@link DividendModel.SchemeRequest}；不使用 passed 标志或仅重算哈希的方式伪造通过。
 * 本单只做授权纯核：不生成现金腿，不接 D3 世界批次。</p>
 */
@DisplayName("BoardModel：现金分红授权纯核")
class BoardModelDividendTest {

    private static final String COMPANY = "acme";
    private static final String SYMBOL = "ACME1";
    private static final String ISSUER_OWNER = "org-acme";
    private static final String COMPANY_ACCOUNT = "ACME-CASH";

    // ------------------------------------------------------------------
    // 正常授权链
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三票赞成通过并生成授权，引用可核验")
    void threeVotesAuthorizeAndVerify() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3", "s4", "s5"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));

        BoardModel.Meeting proposed = state.meeting("m1");
        assertNotNull(proposed.dividendProposal(), "受理后会议必须携带提案元数据");
        assertEquals("s1", proposed.dividendProposal().proposerSeatId());
        assertEquals(50L, proposed.dividendProposal().proposedAtMillis());
        assertNull(proposed.dividendResolution(), "表决前没有授权结果");

        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));
        state = ok(BoardModel.castVote(state, 220L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 230L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 240L, "m1", "s3", Vote.YES));
        state = ok(BoardModel.closeVoting(state, 300L, "m1"));
        assertEquals(MeetingStatus.ADOPTED, state.meeting("m1").status());

        state = ok(BoardModel.executeAdoptedDividendResolution(state, 400L, "m1"));
        BoardModel.Meeting implemented = state.meeting("m1");
        assertEquals(MeetingStatus.IMPLEMENTED, implemented.status());
        assertNotNull(implemented.dividendResolution());
        assertEquals(400L, implemented.dividendResolution().issuedAtMillis(),
                "issuedAt 只表示本次授权生成时点");
        assertFalse(implemented.dividendResolution().authorizationRef().isBlank());
        assertTrue(BoardModel.verifiesDividendAuthorization(state,
                        implemented.dividendResolution().authorizationRef(),
                        schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)),
                "合法授权必须可被同一请求核验");
    }

    @Test
    @DisplayName("两票赞成不通过，且不能再实施")
    void twoVotesRejectAndCannotExecute() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3", "s4", "s5"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));
        state = ok(BoardModel.castVote(state, 220L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 230L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.closeVoting(state, 300L, "m1"));

        assertEquals(MeetingStatus.REJECTED, state.meeting("m1").status());
        assertNull(state.meeting("m1").dividendResolution());
        BoardModel.Outcome execute = BoardModel.executeAdoptedDividendResolution(state, 400L, "m1");
        assertFalse(execute.accepted(), "未通过的表决不能生成授权");
        assertEquals(state, execute.state());
    }

    @Test
    @DisplayName("分红门槛不因议题而被降低：三票是下限，不能用预算门槛替代")
    void dividendThresholdIsNotWeakenedByBudgetRules() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3", "s4", "s5"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));
        state = ok(BoardModel.castVote(state, 220L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 230L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 240L, "m1", "s3", Vote.NO));
        state = ok(BoardModel.closeVoting(state, 300L, "m1"));

        assertEquals(MeetingStatus.REJECTED, state.meeting("m1").status(),
                "两票赞成加一票反对仍然是两票，不得通过");
    }

    // ------------------------------------------------------------------
    // 关联回避与法定人数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("关联且到会必须在回避名单中，否则提案被拒")
    void attendingRelatedSeatMustBeRecused() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        BoardModel.Outcome outcome = BoardModel.proposeDividendAuthorization(state, 50L, "m1",
                "s1", List.of("s2"), schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L));
        assertFalse(outcome.accepted(), "到会的关联席位未回避时必须拒绝");
        assertEquals(state, outcome.state());
    }

    @Test
    @DisplayName("关联但缺席者无需回避，也不必为回避先到会")
    void absentRelatedSeatNeedsNoRecusal() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        BoardModel.Outcome outcome = BoardModel.proposeDividendAuthorization(state, 50L, "m1",
                "s1", List.of("s4"), schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L));
        assertTrue(outcome.accepted(), "缺席的关联席位不要求出现在到会或回避名单中");
        assertTrue(outcome.state().meeting("m1").dividendProposal().relatedSeatIds().contains("s4"));
    }

    @Test
    @DisplayName("回避后有效到会不足三人只能延期")
    void recusalBelowQuorumAdjourns() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of("s2"));
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of("s2"),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));
        state = ok(BoardModel.closeVoting(state, 300L, "m1"));

        assertEquals(MeetingStatus.ADJOURNED_NO_QUORUM, state.meeting("m1").status(),
                "回避不得降低法定人数，剩余两人只能延期");
    }

    @Test
    @DisplayName("提案职责本身不构成关联，非关联提案人可以投票")
    void proposerMayVoteWhenNotRelated() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s3", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));
        state = ok(BoardModel.castVote(state, 220L, "m1", "s3", Vote.YES));
        state = ok(BoardModel.castVote(state, 230L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 240L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.closeVoting(state, 300L, "m1"));

        assertEquals(MeetingStatus.ADOPTED, state.meeting("m1").status(),
                "提案人未被声明关联时必须能投票");
    }

    @Test
    @DisplayName("非成员、回避者与重复投票都被拒绝")
    void invalidBallotsAreRejected() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of("s2"));
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of("s2"),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));

        assertFalse(BoardModel.castVote(state, 220L, "m1", "s9", Vote.YES).accepted(),
                "非章程席位不能投票");
        assertFalse(BoardModel.castVote(state, 220L, "m1", "s2", Vote.YES).accepted(),
                "已回避席位不能投票");

        BoardModel.State voted = ok(BoardModel.castVote(state, 230L, "m1", "s1", Vote.YES));
        BoardModel.Outcome again = BoardModel.castVote(voted, 240L, "m1", "s1", Vote.NO);
        assertFalse(again.accepted(), "同一席位不能重复投票");
        assertEquals(voted, again.state());
    }

    // ------------------------------------------------------------------
    // 提案、空提案与议题隔离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新会议允许先召集后提案；空提案到期形成明确否决")
    void emptyProposalClosesAsRejected() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        assertNull(state.meeting("m1").dividendProposal(), "刚召集时允许没有提案");

        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        BoardModel.Outcome voting = BoardModel.startVoting(state, 210L, "m1");
        assertFalse(voting.accepted(), "没有提案不得进入表决");

        state = ok(BoardModel.closeVoting(state, 300L, "m1"));
        assertEquals(MeetingStatus.REJECTED, state.meeting("m1").status(),
                "空提案到期必须形成明确终态");
    }

    @Test
    @DisplayName("旧议题不得携带分红元数据，分红议题也不得带预算行")
    void topicsDoNotLeakIntoEachOther() {
        BoardModel.State state = opened();
        state = ok(BoardModel.convene(state, 0L, new BoardModel.MeetingRequest("m-budget",
                Topic.BUDGET, "s1", 100L, 200L, 300L,
                List.of("s1", "s2", "s3"), List.of())));
        BoardModel.Outcome wrongTopic = BoardModel.proposeDividendAuthorization(state, 50L,
                "m-budget", "s1", List.of(), schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L));
        assertFalse(wrongTopic.accepted(), "预算会议不能受理分红提案");

        BoardModel.State dividend = dividendMeeting(opened(), "m-div",
                List.of("s1", "s2", "s3"), List.of());
        assertFalse(BoardModel.proposeBudget(dividend, 50L, "m-div",
                List.of(new BoardModel.BudgetLine(1L, BudgetCategory.PROCUREMENT,
                        new BigDecimal("500.00")))).accepted(), "分红会议不能受理预算行");
    }

    @Test
    @DisplayName("预算实施入口不实施分红，分红实施入口不实施预算")
    void executionEntriesAreSeparated() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));
        state = ok(BoardModel.castVote(state, 220L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 230L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 240L, "m1", "s3", Vote.YES));
        state = ok(BoardModel.closeVoting(state, 300L, "m1"));

        BoardModel.Outcome budgetEntry = BoardModel.executeAdoptedResolution(state, 400L, "m1");
        assertFalse(budgetEntry.accepted(), "预算实施入口不得实施分红决议");
        assertEquals(state, budgetEntry.state());
    }

    // ------------------------------------------------------------------
    // 经济内容、重审与重复实施
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一方案标识的经济内容不可改")
    void sameSchemeEconomicsAreImmutable() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));

        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));
        state = ok(BoardModel.closeVoting(state, 300L, "m1"));
        BoardModel.State second = ok(BoardModel.convene(state, 350L, new BoardModel.MeetingRequest("m2",
                Topic.DIVIDEND_AUTHORIZATION, "s1", 450L, 500L, 600L,
                List.of("s1", "s2", "s3"), List.of())));
        BoardModel.Outcome changed = BoardModel.proposeDividendAuthorization(second, 400L, "m2",
                "s1", List.of(), schemeRequest("S1", "1.20", 10_000L, 20_000L, 30_000L));
        assertFalse(changed.accepted(), "同 schemeId 改金额必须拒绝");
        assertEquals(second, changed.state());
    }

    @Test
    @DisplayName("否决后可在新会议按同经济内容重审")
    void rejectedProposalMayBeReconsidered() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));
        state = ok(BoardModel.castVote(state, 220L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.closeVoting(state, 300L, "m1"));
        assertEquals(MeetingStatus.REJECTED, state.meeting("m1").status());

        state = ok(BoardModel.convene(state, 350L, new BoardModel.MeetingRequest("m2",
                Topic.DIVIDEND_AUTHORIZATION, "s1", 450L, 500L, 600L,
                List.of("s1", "s2", "s3"), List.of())));
        BoardModel.Outcome again = BoardModel.proposeDividendAuthorization(state, 400L, "m2",
                "s1", List.of(), schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L));
        assertTrue(again.accepted(), "被否决后允许同经济内容在新会议重审");
    }

    @Test
    @DisplayName("同一方案不得并行提案，也不得重复实施")
    void parallelAndDuplicateImplementationAreRejected() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        BoardModel.State parallel = dividendMeeting(state, "m2", List.of("s1", "s2", "s3"), List.of());
        assertFalse(BoardModel.proposeDividendAuthorization(parallel, 60L, "m2", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)).accepted(),
                "仍有未终结提案时不得并行重审");

        BoardModel.State done = authorize(state, "m1", 300L, 400L);
        assertEquals(MeetingStatus.IMPLEMENTED, done.meeting("m1").status());
        assertFalse(BoardModel.executeAdoptedDividendResolution(done, 800L, "m1").accepted(),
                "同一方案授权只能实施一次");
        BoardModel.State next = ok(BoardModel.convene(done, 450L, new BoardModel.MeetingRequest("m3",
                Topic.DIVIDEND_AUTHORIZATION, "s1", 600L, 650L, 700L,
                List.of("s1", "s2", "s3"), List.of())));
        BoardModel.Outcome duplicate = BoardModel.proposeDividendAuthorization(next, 500L, "m3", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L));
        assertFalse(duplicate.accepted(), "已实施过的方案不再生成新授权提案");
        assertEquals(next, duplicate.state());
    }

    // ------------------------------------------------------------------
    // 时界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("授权实施不早于表决截止，也不晚于登记截止")
    void executionRespectsItsWindow() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        state = ok(BoardModel.freezeMaterials(state, 200L, "m1"));
        state = ok(BoardModel.startVoting(state, 210L, "m1"));
        state = ok(BoardModel.castVote(state, 220L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 230L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 240L, "m1", "s3", Vote.YES));
        state = ok(BoardModel.closeVoting(state, 300L, "m1"));

        assertFalse(BoardModel.executeAdoptedDividendResolution(state, 299L, "m1").accepted(),
                "表决未截止不得实施");
        assertFalse(BoardModel.executeAdoptedDividendResolution(state, 10_001L, "m1").accepted(),
                "越过登记截止不得再生成授权");
        assertTrue(BoardModel.executeAdoptedDividendResolution(state, 10_000L, "m1").accepted(),
                "登记截止当刻仍可授权");
    }

    @Test
    @DisplayName("会议表决截止必须不晚于方案登记截止")
    void meetingDeadlineMustNotPassRecordAt() {
        BoardModel.State state = opened();
        state = dividendMeeting(state, "m1", List.of("s1", "s2", "s3"), List.of());
        assertFalse(BoardModel.proposeDividendAuthorization(state, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 200L, 20_000L, 30_000L)).accepted(),
                "recordAt 早于会议 closesAt 时必须拒绝");
    }

    @Test
    @DisplayName("Long 最大时点仍可召集、提案与授权")
    void maximumMillisRemainLegal() {
        long max = Long.MAX_VALUE;
        BoardModel.State state = opened();
        state = ok(BoardModel.convene(state, max - 6, new BoardModel.MeetingRequest("m-max",
                Topic.DIVIDEND_AUTHORIZATION, "s1", max - 5, max - 4, max - 3,
                List.of("s1", "s2", "s3"), List.of())));
        state = ok(BoardModel.proposeDividendAuthorization(state, max - 5, "m-max", "s1", List.of(),
                schemeRequest("S1", "1.00", max - 2, max - 1, max)));
        state = ok(BoardModel.freezeMaterials(state, max - 4, "m-max"));
        state = ok(BoardModel.startVoting(state, max - 4, "m-max"));
        state = ok(BoardModel.castVote(state, max - 4, "m-max", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, max - 4, "m-max", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, max - 4, "m-max", "s3", Vote.YES));
        state = ok(BoardModel.closeVoting(state, max - 3, "m-max"));
        state = ok(BoardModel.executeAdoptedDividendResolution(state, max - 2, "m-max"));
        assertEquals(MeetingStatus.IMPLEMENTED, state.meeting("m-max").status());
    }

    // ------------------------------------------------------------------
    // 引用来源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("授权引用绑定实际来源：换提案、换引用、换票都不再核验通过")
    void referenceBindsRealSources() {
        BoardModel.State state = authorize(opened(), "m1", 300L, 400L);
        String ref = state.meeting("m1").dividendResolution().authorizationRef();

        assertFalse(BoardModel.verifiesDividendAuthorization(state, ref,
                        schemeRequest("S1", "2.00", 10_000L, 20_000L, 30_000L)),
                "经济内容不同不得通过");
        assertFalse(BoardModel.verifiesDividendAuthorization(state, ref,
                        schemeRequest("S2", "1.00", 10_000L, 20_000L, 30_000L)),
                "方案标识不同不得通过");
        assertFalse(BoardModel.verifiesDividendAuthorization(state, "board-dividend-v1:unknown",
                        schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)),
                "伪造引用不得通过");
    }

    @Test
    @DisplayName("选票插入顺序不影响引用，但换成不同投票人必须改变来源")
    void voteOrderDoesNotMatterButVotersDo() {
        BoardModel.State first = opened();
        first = dividendMeeting(first, "m1", List.of("s1", "s2", "s3", "s4"), List.of());
        first = ok(BoardModel.proposeDividendAuthorization(first, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        first = ok(BoardModel.freezeMaterials(first, 200L, "m1"));
        first = ok(BoardModel.startVoting(first, 210L, "m1"));
        first = ok(BoardModel.castVote(first, 220L, "m1", "s1", Vote.YES));
        first = ok(BoardModel.castVote(first, 230L, "m1", "s2", Vote.YES));
        first = ok(BoardModel.castVote(first, 240L, "m1", "s3", Vote.YES));
        first = ok(BoardModel.closeVoting(first, 300L, "m1"));
        first = ok(BoardModel.executeAdoptedDividendResolution(first, 400L, "m1"));

        BoardModel.State second = opened();
        second = dividendMeeting(second, "m1", List.of("s1", "s2", "s3", "s4"), List.of());
        second = ok(BoardModel.proposeDividendAuthorization(second, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        second = ok(BoardModel.freezeMaterials(second, 200L, "m1"));
        second = ok(BoardModel.startVoting(second, 210L, "m1"));
        second = ok(BoardModel.castVote(second, 230L, "m1", "s2", Vote.YES));
        second = ok(BoardModel.castVote(second, 240L, "m1", "s3", Vote.YES));
        second = ok(BoardModel.castVote(second, 220L, "m1", "s1", Vote.YES));
        second = ok(BoardModel.closeVoting(second, 300L, "m1"));
        second = ok(BoardModel.executeAdoptedDividendResolution(second, 400L, "m1"));

        assertEquals(first.meeting("m1").dividendResolution().authorizationRef(),
                second.meeting("m1").dividendResolution().authorizationRef(),
                "同一批席位在相同 castAt 投票时，插入顺序不应改变引用");

        BoardModel.State other = opened();
        other = dividendMeeting(other, "m1", List.of("s1", "s2", "s3", "s4"), List.of());
        other = ok(BoardModel.proposeDividendAuthorization(other, 50L, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        other = ok(BoardModel.freezeMaterials(other, 200L, "m1"));
        other = ok(BoardModel.startVoting(other, 210L, "m1"));
        other = ok(BoardModel.castVote(other, 220L, "m1", "s1", Vote.YES));
        other = ok(BoardModel.castVote(other, 230L, "m1", "s2", Vote.YES));
        other = ok(BoardModel.castVote(other, 240L, "m1", "s4", Vote.YES));
        other = ok(BoardModel.closeVoting(other, 300L, "m1"));
        other = ok(BoardModel.executeAdoptedDividendResolution(other, 400L, "m1"));

        assertNotEquals(first.meeting("m1").dividendResolution().authorizationRef(),
                other.meeting("m1").dividendResolution().authorizationRef(),
                "同为三票但投票人不同，不得伪装成同一来源");
    }

    @Test
    @DisplayName("已生成的合法许可在支付日之后仍可核验")
    void validAuthorizationSurvivesPastRecordAndPayDates() {
        BoardModel.State state = authorize(opened(), "m1", 300L, 400L);
        String ref = state.meeting("m1").dividendResolution().authorizationRef();
        DividendModel.SchemeRequest request =
                schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L);

        assertTrue(BoardModel.verifiesDividendAuthorization(state, ref, request));
        BoardModel.State later = ok(BoardModel.convene(state, 40_000L,
                new BoardModel.MeetingRequest("m-later", Topic.OPERATING_REVIEW, "s1",
                        40_100L, 40_200L, 40_300L, List.of("s1", "s2", "s3"), List.of())));
        assertTrue(BoardModel.verifiesDividendAuthorization(later, ref, request),
                "核验不得按当前支付日把旧许可误判过期");
    }

    // ------------------------------------------------------------------
    // JSON 与旧兼容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("旧 golden JSON 逐字往返，旧会议缺新字段仍合法")
    void legacyGoldensRoundTripByteForByte() throws IOException {
        for (String name : List.of("board-legacy-open.json", "board-legacy-budget.json",
                "board-legacy-review.json")) {
            String golden = resource(name);
            BoardModel.State parsed = BoardModel.fromJson(golden);
            assertEquals(golden, BoardModel.toJson(parsed), "旧 JSON 必须逐字往返：" + name);
        }
    }

    @Test
    @DisplayName("旧 5 参 State 与 14 参 Meeting 源码兼容，null 元数据不落盘")
    void legacyConstructorsRemainSourceCompatible() {
        BoardModel.Charter charter = charter();
        BoardModel.Meeting meeting = new BoardModel.Meeting("m-old", Topic.BUDGET, "s1",
                0L, 100L, 200L, 300L, MeetingStatus.CONVENED,
                List.of("s1", "s2", "s3"), List.of(), List.of(), List.of(), Map.of(), null);
        BoardModel.State state = new BoardModel.State(charter, Map.of("m-old", meeting),
                List.of(), List.of(), List.of());

        String json = BoardModel.toJson(state);
        assertFalse(json.contains("dividendProposal"), "null 可选元数据不得写入 JSON");
        assertFalse(json.contains("dividendResolution"));
        assertEquals(state, BoardModel.fromJson(json));
    }

    @Test
    @DisplayName("State 仍恰好五个分量，没有新增平行分红状态")
    void stateKeepsFiveComponents() {
        List<String> components = Arrays.stream(BoardModel.State.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertEquals(List.of("charter", "meetings", "budgets", "commitments", "reviews"),
                components);
    }

    @Test
    @DisplayName("严格 JSON：重复键、尾随、整数位浮点与 null 原语都拒绝")
    void jsonParsingStaysStrict() throws IOException {
        String golden = resource("board-legacy-open.json");

        String duplicated = golden.replace("\"budgets\":[]", "\"budgets\":[],\"budgets\":[]");
        assertNotEquals(golden, duplicated);
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(duplicated));

        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(golden + "{}"));

        String floatQuorum = golden.replace("\"quorum\":3", "\"quorum\":3.5");
        assertNotEquals(golden, floatQuorum);
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(floatQuorum));

        String nullQuorum = golden.replace("\"quorum\":3", "\"quorum\":null");
        assertNotEquals(golden, nullQuorum);
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(nullQuorum));
    }

    @Test
    @DisplayName("伪造通过、篡改材料与选票的 JSON 必须被构造校验拒绝")
    void forgedAdoptionIsRejected() throws IOException {
        BoardModel.State implemented = authorize(opened(), "m1", 300L, 400L);
        String json = BoardModel.toJson(implemented);

        String forgedVote = json.replaceFirst("\"vote\":\"YES\"", "\"vote\":\"NO\"");
        assertNotEquals(json, forgedVote);
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(forgedVote),
                "把一票赞成改成反对后仍标 ADOPTED/IMPLEMENTED，必须拒绝");

        String forgedMaterial = json.replaceFirst("\"sourceRef\":\"[^\"]*\"",
                "\"sourceRef\":\"forged-source\"");
        assertNotEquals(json, forgedMaterial);
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(forgedMaterial),
                "材料被改后授权引用不再匹配，必须拒绝");
    }

    // ------------------------------------------------------------------
    // 新旧事项交替
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分红授权不触碰预算、承诺与评审，且新事项保留全部旧历史")
    void dividendOperationsKeepBudgetHistory() {
        BoardModel.State state = budgetState(opened());
        BigDecimal capBefore = state.budget(1L, BudgetCategory.PROCUREMENT).cap();

        BoardModel.State authorized = authorize(state, "m1", 700L, 800L);
        assertEquals(MeetingStatus.IMPLEMENTED, authorized.meeting("m1").status());
        assertEquals(0, capBefore.compareTo(
                        authorized.budget(1L, BudgetCategory.PROCUREMENT).cap()),
                "分红授权不得改变预算上限");
        assertEquals(state.commitments(), authorized.commitments(), "不得改变承诺");
        assertEquals(state.reviews(), authorized.reviews(), "不得改变评审");

        BoardModel.State later = ok(BoardModel.convene(authorized, 900L,
                new BoardModel.MeetingRequest("m-budget-2", Topic.BUDGET, "s1",
                        1_000L, 1_100L, 1_200L, List.of("s1", "s2", "s3"), List.of())));
        later = ok(BoardModel.proposeBudget(later, 950L, "m-budget-2",
                List.of(new BoardModel.BudgetLine(1L, BudgetCategory.PROCUREMENT,
                        new BigDecimal("600.00")))));
        later = ok(BoardModel.freezeMaterials(later, 1_100L, "m-budget-2"));
        later = ok(BoardModel.startVoting(later, 1_110L, "m-budget-2"));
        later = ok(BoardModel.castVote(later, 1_120L, "m-budget-2", "s1", Vote.YES));
        later = ok(BoardModel.castVote(later, 1_130L, "m-budget-2", "s2", Vote.YES));
        later = ok(BoardModel.castVote(later, 1_140L, "m-budget-2", "s3", Vote.YES));
        later = ok(BoardModel.closeVoting(later, 1_200L, "m-budget-2"));
        later = ok(BoardModel.executeAdoptedResolution(later, 1_300L, "m-budget-2"));

        assertEquals(MeetingStatus.IMPLEMENTED, later.meeting("m-budget-2").status());
        assertEquals(MeetingStatus.IMPLEMENTED, later.meeting("m1").status(),
                "分红授权历史必须保留");
        assertNotNull(later.meeting("m1").dividendResolution());
        assertEquals(0, new BigDecimal("600.00").compareTo(
                later.budget(1L, BudgetCategory.PROCUREMENT).cap()));
    }

    @Test
    void invalidRawDividendRequestsAreRejectedWithoutChangingState() {
        BoardModel.State state = dividendMeeting(opened(), "m1", List.of("s1", "s2", "s3"), List.of());
        List<DividendModel.SchemeRequest> invalid = List.of(
                schemeRequest("zero", "0.00", 10_000, 20_000, 30_000),
                schemeRequest("precision", "0.005", 10_000, 20_000, 30_000),
                schemeRequest("timeline", "1.00", 10_000, 10_000, 30_000),
                new DividendModel.SchemeRequest("null-money", SYMBOL, ISSUER_OWNER, COMPANY_ACCOUNT,
                        null, 10_000, 20_000, 30_000));
        for (DividendModel.SchemeRequest raw : invalid) {
            BoardModel.Outcome result = BoardModel.proposeDividendAuthorization(state, 50, "m1", "s1", List.of(), raw);
            assertFalse(result.accepted());
            assertEquals(state, result.state());
        }
        BoardModel.State normalized = ok(BoardModel.proposeDividendAuthorization(state, 50, "m1", "s1", List.of(),
                schemeRequest("normalized", "1", 10_000, 20_000, 30_000)));
        assertEquals(new BigDecimal("1.00"), normalized.meeting("m1").dividendProposal().request().perShare());
    }

    @Test
    void unknownProposerAndRelatedSeatCannotEnterTheProposal() {
        BoardModel.State state = dividendMeeting(opened(), "m1", List.of("s1", "s2", "s3"), List.of());
        DividendModel.SchemeRequest request = schemeRequest("S1", "1.00", 10_000, 20_000, 30_000);
        BoardModel.Outcome proposer = BoardModel.proposeDividendAuthorization(state, 50, "m1", "outsider", List.of(), request);
        BoardModel.Outcome related = BoardModel.proposeDividendAuthorization(state, 50, "m1", "s1", List.of("outsider"), request);
        assertFalse(proposer.accepted());
        assertFalse(related.accepted());
        assertEquals(state, proposer.state());
        assertEquals(state, related.state());
    }

    @Test
    void existingCompleteReviewSurvivesLaterDividendAuthorization() throws IOException {
        BoardModel.State state = BoardModel.fromJson(resource("board-legacy-review.json"));
        BoardModel.State later = authorize(state, "m1", 1_600, 1_700);
        assertEquals(state.reviews(), later.reviews());
        assertEquals(state.meeting("m-review"), later.meeting("m-review"));
        assertEquals(new BigDecimal("66.00"), later.reviews().get(0).total());
        assertEquals(MeetingStatus.IMPLEMENTED, later.meeting("m1").status());
    }

    @Test
    void relatedFactsAreCopiedAndRemainImmutable() {
        BoardModel.State state = dividendMeeting(opened(), "m1", List.of("s1", "s2", "s3"), List.of());
        List<String> input = new ArrayList<>(List.of("s4"));
        state = ok(BoardModel.proposeDividendAuthorization(state, 50, "m1", "s1", input,
                schemeRequest("S1", "1.00", 10_000, 20_000, 30_000)));
        input.clear();
        BoardModel.DividendProposal proposal = state.meeting("m1").dividendProposal();
        assertEquals(List.of("s4"), proposal.relatedSeatIds());
        assertThrows(UnsupportedOperationException.class, () -> proposal.relatedSeatIds().clear());
    }

    @Test
    void completeResigningKeepsLegalAuthorizationUsable() throws Exception {
        BoardModel.State state = authorize(opened(), "m1", 300, 350);
        assertEquals(state, resign(state, meeting -> {}, 350));
    }

    @Test
    void recoveryRejectsNonChairEvenWithCompleteSignature() {
        assertThrows(IllegalArgumentException.class,
                () -> resign(authorized(), meeting -> meeting.put("convenerSeatId", "s2"), 350));
    }

    @Test
    void recoveryRejectsUnknownThirdVoterEvenWithCompleteSignature() {
        assertThrows(IllegalArgumentException.class, () -> resign(authorized(), meeting -> {
            meeting.withArray("attendeeSeatIds").set(2, new ObjectMapper().getNodeFactory().textNode("outsider"));
            ObjectNode ballots = (ObjectNode) meeting.get("ballots");
            ObjectNode ballot = (ObjectNode) ballots.remove("s3");
            ballot.put("seatId", "outsider");
            ballots.set("outsider", ballot);
        }, 350));
    }

    @Test
    void recoveryRejectsRecusalOutsideAttendance() {
        assertThrows(IllegalArgumentException.class, () -> resign(authorized(),
                meeting -> meeting.withArray("recusedSeatIds").add("outsider"), 350));
    }

    @Test
    void recoveryRejectsVoteOutsideWindowEvenWithCompleteSignature() {
        assertThrows(IllegalArgumentException.class, () -> resign(authorized(),
                meeting -> ((ObjectNode) meeting.get("ballots").get("s1")).put("castAt", 301), 350));
    }

    @Test
    void recoveryRejectsLateMaterialEvenWithCompleteSignature() {
        assertThrows(IllegalArgumentException.class, () -> resign(authorized(),
                meeting -> ((ObjectNode) meeting.get("materials").get(0)).put("asOf", 101), 350));
    }

    @Test
    void recoveryRejectsInvalidMeetingTimelineEvenWithCompleteSignature() {
        assertThrows(IllegalArgumentException.class, () -> resign(authorized(),
                meeting -> meeting.put("materialDeadline", 300), 350));
    }

    @Test
    void recoveryRejectsEarlyAuthorizationEvenWithCompleteSignature() {
        assertThrows(IllegalArgumentException.class, () -> resign(authorized(), meeting -> {}, 299));
    }

    @Test
    void recoveryRejectsLateAuthorizationEvenWithCompleteSignature() {
        assertThrows(IllegalArgumentException.class, () -> resign(authorized(), meeting -> {}, 10_001));
    }

    @Test
    void recoveryRejectsReviewedPhaseForDividend() throws Exception {
        ObjectNode root = new ObjectMapper().readValue(BoardModel.toJson(authorized()), ObjectNode.class);
        ObjectNode meeting = (ObjectNode) root.get("meetings").get("m1");
        meeting.put("status", "REVIEWED");
        meeting.remove("dividendResolution");
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(root.toString()));
    }

    @Test
    void recoveryRejectsVotesBeforeVoting() throws Exception {
        ObjectNode root = new ObjectMapper().readValue(BoardModel.toJson(authorized()), ObjectNode.class);
        ObjectNode meeting = (ObjectNode) root.get("meetings").get("m1");
        meeting.put("status", "MATERIALS_FROZEN");
        meeting.remove("dividendResolution");
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(root.toString()));
    }

    @Test
    void recoveryRejectsMissingRequiredDividendFields() throws Exception {
        BoardModel.State proposed = ok(BoardModel.proposeDividendAuthorization(
                dividendMeeting(opened(), "m1", List.of("s1", "s2", "s3"), List.of()),
                0, "m1", "s1", List.of(), schemeRequest("S1", "1.00", 10_000, 20_000, 30_000)));
        ObjectNode root = new ObjectMapper().readValue(BoardModel.toJson(proposed), ObjectNode.class);
        ((ObjectNode) root.get("meetings").get("m1").get("dividendProposal")).remove("proposedAtMillis");
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(root.toString()));
    }

    @Test
    void duplicateRelatedSeatIsBusinessRejection() {
        BoardModel.State state = dividendMeeting(opened(), "m1", List.of("s1", "s2", "s3"), List.of());
        BoardModel.Outcome outcome = BoardModel.proposeDividendAuthorization(state, 50, "m1", "s1",
                List.of("s4", "s4"), schemeRequest("S1", "1.00", 10_000, 20_000, 30_000));
        assertFalse(outcome.accepted());
        assertEquals(state, outcome.state());
    }

    @Test
    void recoveryRejectsDuplicateMaterialsEvenWithCompleteSignature() {
        assertThrows(IllegalArgumentException.class, () -> resign(authorized(),
                meeting -> meeting.withArray("materials").add(meeting.get("materials").get(0).deepCopy()), 350));
    }

    @Test
    void recoveryRejectsFailedBudgetPhaseForDividend() throws Exception {
        ObjectNode root = new ObjectMapper().readValue(BoardModel.toJson(authorized()), ObjectNode.class);
        ObjectNode meeting = (ObjectNode) root.get("meetings").get("m1");
        meeting.put("status", "IMPLEMENTATION_FAILED");
        meeting.put("failureReason", "FAILED");
        meeting.remove("dividendResolution");
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(root.toString()));
    }

    @Test
    void recoveryRejectsMissingProposalInAdjournedMeeting() throws Exception {
        BoardModel.State state = dividendMeeting(opened(), "m1", List.of("s1", "s2"), List.of());
        state = ok(BoardModel.proposeDividendAuthorization(state, 50, "m1", "s1", List.of(),
                schemeRequest("S1", "1.00", 10_000, 20_000, 30_000)));
        state = ok(BoardModel.freezeMaterials(state, 200, "m1"));
        state = ok(BoardModel.startVoting(state, 200, "m1"));
        state = ok(BoardModel.closeVoting(state, 300, "m1"));
        assertEquals(MeetingStatus.ADJOURNED_NO_QUORUM, state.meeting("m1").status());
        ObjectNode root = new ObjectMapper().readValue(BoardModel.toJson(state), ObjectNode.class);
        ((ObjectNode) root.get("meetings").get("m1")).remove("dividendProposal");
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(root.toString()));
    }

    @Test
    void recoveryRejectsActiveProposalForAlreadyAuthorizedScheme() throws Exception {
        ObjectNode root = new ObjectMapper().readValue(BoardModel.toJson(authorized()), ObjectNode.class);
        ObjectNode meetings = (ObjectNode) root.get("meetings");
        ObjectNode proposal = ((ObjectNode) meetings.get("m1")).deepCopy();
        proposal.put("meetingId", "m2");
        proposal.put("status", "CONVENED");
        proposal.set("ballots", new ObjectMapper().createObjectNode());
        proposal.remove("dividendResolution");
        meetings.set("m2", proposal);
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(root.toString()));
    }

    private static BoardModel.State authorized() {
        return authorize(opened(), "m1", 300, 350);
    }

    // 首稿能够完整重签非法来源。修复后也可在构造来源时提前拒绝，合法对照保持完全相等。
    private static BoardModel.State resign(BoardModel.State state, Consumer<ObjectNode> mutation,
                                            long issuedAt) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.readValue(BoardModel.toJson(state), ObjectNode.class);
        ObjectNode meeting = (ObjectNode) root.get("meetings").get("m1");
        mutation.accept(meeting);
        meeting.put("status", "ADOPTED");
        meeting.remove("dividendResolution");
        BoardModel.Meeting source;
        try {
            source = mapper.treeToValue(meeting, BoardModel.Meeting.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("授权来源无效", invalid);
        }
        Method signer = BoardModel.class.getDeclaredMethod("computeAuthorizationRef",
                BoardModel.Charter.class, BoardModel.Meeting.class, long.class);
        signer.setAccessible(true);
        String reference = (String) signer.invoke(null, state.charter(), source, issuedAt);
        meeting.put("status", "IMPLEMENTED");
        meeting.set("dividendResolution", mapper.valueToTree(new BoardModel.DividendResolution(issuedAt, reference)));
        return BoardModel.fromJson(root.toString());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static BoardModel.State opened() {
        return BoardModel.open(registry(), charter());
    }

    private static BoardModel.Charter charter() {
        List<BoardModel.Seat> seats = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            seats.add(new BoardModel.Seat("s" + index, "p" + index));
        }
        return new BoardModel.Charter(COMPANY, seats, "s1", 3);
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = new ArrayList<>();
        parties.add(new OpeningRegistry.Party(COMPANY,
                OpeningRegistry.PartyKind.ORGANIZATION, "Acme"));
        for (int index = 1; index <= 5; index++) {
            parties.add(new OpeningRegistry.Party("p" + index,
                    OpeningRegistry.PartyKind.PERSON, "Person " + index));
        }
        return new OpeningRegistry(parties, List.of(), List.of(), List.of());
    }

    private static DividendModel.SchemeRequest schemeRequest(String schemeId, String perShare,
                                                             long recordAt, long exAt, long payAt) {
        return new DividendModel.SchemeRequest(schemeId, SYMBOL, ISSUER_OWNER, COMPANY_ACCOUNT,
                new BigDecimal(perShare), recordAt, exAt, payAt);
    }

    private static BoardModel.State dividendMeeting(BoardModel.State state, String meetingId,
                                                    List<String> attendees, List<String> recused) {
        return ok(BoardModel.convene(state, 0L, new BoardModel.MeetingRequest(meetingId,
                Topic.DIVIDEND_AUTHORIZATION, "s1", 100L, 200L, 300L, attendees, recused)));
    }

    private static BoardModel.State authorize(BoardModel.State state, String meetingId,
                                              long closeAt, long executeAt) {
        BoardModel.State next = state;
        if (next.meeting(meetingId) == null) {
            next = ok(BoardModel.convene(next, closeAt - 300, new BoardModel.MeetingRequest(meetingId,
                    Topic.DIVIDEND_AUTHORIZATION, "s1", closeAt - 200, closeAt - 100, closeAt,
                    List.of("s1", "s2", "s3"), List.of())));
        }
        BoardModel.Meeting meeting = next.meeting(meetingId);
        if (meeting.dividendProposal() == null) {
            next = ok(BoardModel.proposeDividendAuthorization(next, meeting.createdAt() + 50, meetingId, "s1", List.of(),
                    schemeRequest("S1", "1.00", 10_000L, 20_000L, 30_000L)));
        }
        next = ok(BoardModel.addMaterial(next, meeting.createdAt() + 60, meetingId,
                new BoardModel.MaterialRef("mat-1", "evidence-1", 1L, meeting.createdAt() + 60)));
        next = ok(BoardModel.freezeMaterials(next, meeting.opensAt(), meetingId));
        next = ok(BoardModel.startVoting(next, meeting.opensAt() + 10, meetingId));
        next = ok(BoardModel.castVote(next, meeting.opensAt() + 20, meetingId, "s1", Vote.YES));
        next = ok(BoardModel.castVote(next, meeting.opensAt() + 30, meetingId, "s2", Vote.YES));
        next = ok(BoardModel.castVote(next, meeting.opensAt() + 40, meetingId, "s3", Vote.YES));
        next = ok(BoardModel.closeVoting(next, closeAt, meetingId));
        return ok(BoardModel.executeAdoptedDividendResolution(next, executeAt, meetingId));
    }
    private static BoardModel.State budgetState(BoardModel.State state) {
        BoardModel.State next = state;
        next = ok(BoardModel.convene(next, 0L, new BoardModel.MeetingRequest("m-budget",
                Topic.BUDGET, "s1", 100L, 200L, 300L, List.of("s1", "s2", "s3"), List.of())));
        next = ok(BoardModel.proposeBudget(next, 50L, "m-budget",
                List.of(new BoardModel.BudgetLine(1L, BudgetCategory.PROCUREMENT,
                        new BigDecimal("500.00")))));
        next = ok(BoardModel.freezeMaterials(next, 200L, "m-budget"));
        next = ok(BoardModel.startVoting(next, 210L, "m-budget"));
        next = ok(BoardModel.castVote(next, 220L, "m-budget", "s1", Vote.YES));
        next = ok(BoardModel.castVote(next, 230L, "m-budget", "s2", Vote.YES));
        next = ok(BoardModel.castVote(next, 240L, "m-budget", "s3", Vote.YES));
        next = ok(BoardModel.closeVoting(next, 300L, "m-budget"));
        return ok(BoardModel.executeAdoptedResolution(next, 300L, "m-budget"));
    }

    private static BoardModel.State ok(BoardModel.Outcome outcome) {
        assertTrue(outcome.accepted(), "夹具前提失败：" + outcome.reason());
        return outcome.state();
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = BoardModelDividendTest.class.getResourceAsStream("/world/" + name)) {
            assertNotNull(stream, "缺少测试资源：" + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
