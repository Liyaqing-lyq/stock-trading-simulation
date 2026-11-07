package edu.cufe.auction.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.cufe.auction.world.LiveCompletionInbox.AttemptKey;
import edu.cufe.auction.world.LiveCompletionInbox.Completion;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2c1 合同测试：有界完成槽的首次登记、截止竞争、超时/迟到与容量治理。
 *
 * <p>不启动 HTTP、不读世界、不使用真实密钥；时间全部由可控双时钟给出。</p>
 */
class LiveCompletionInboxTest {

    private static final String RUN = "run-1";
    private static final long WALL_TIMEOUT_MILLIS = 1_000L;
    private static final long DEADLINE_NANOS = 1_000L * 1_000_000L;

    // ---------------------------------------------------------------- 基本路径

    @Test
    @DisplayName("及时登记第一次即固定：claim 为 REPLY，arrival 为登记时点，结算后释放槽")
    void timelyRegisterClaimsReply() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(2, clock::nano, clock::epoch);
        AttemptKey key = key("a-1");
        assertEquals(LiveCompletionInbox.OpenResult.OPENED,
                inbox.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS));

        clock.advanceMillis(999L);
        assertEquals(LiveCompletionInbox.RegisterResult.REGISTERED,
                inbox.register(key, business(key, "{\"tool\":\"role.wait\"}")));

        List<LiveCompletionInbox.Claim> claims = inbox.claimDue(1);
        assertEquals(1, claims.size());
        LiveCompletionInbox.Claim claim = claims.get(0);
        assertEquals(LiveCompletionInbox.Kind.REPLY, claim.kind());
        assertEquals(999L * 1_000_000L, claim.arrivalNanos());
        assertEquals(clock.epoch(), claim.arrivalEpochMillis());
        assertNotNull(claim.candidate());
        assertEquals(LiveCompletionInbox.AckResult.SETTLED, inbox.settle(claim));
        assertEquals(0, inbox.size());
        inbox.close();
    }

    @Test
    @DisplayName("writer 晚处理不把及时结果变 late：登记后时钟越过截止仍给 REPLY")
    void writerDelayKeepsTimelyReply() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-2");
        inbox.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);
        clock.advanceMillis(999L);
        inbox.register(key, business(key, "{\"tool\":\"role.wait\"}"));

        clock.advanceMillis(60_000L);
        List<LiveCompletionInbox.Claim> claims = inbox.claimDue(1);
        assertEquals(1, claims.size());
        assertEquals(LiveCompletionInbox.Kind.REPLY, claims.get(0).kind());
        assertEquals(999L * 1_000_000L, claims.get(0).arrivalNanos());
        inbox.settle(claims.get(0));
        inbox.close();
    }

    @Test
    @DisplayName("到期边界按 >=deadline 算 late：先给 TIMEOUT，超时持久后才给 LATE")
    void lateCandidateIsTimeoutThenLate() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-3");
        inbox.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);
        clock.advanceMillis(1_000L);
        assertEquals(LiveCompletionInbox.RegisterResult.REGISTERED,
                inbox.register(key, business(key, "{\"tool\":\"role.wait\"}")));

        List<LiveCompletionInbox.Claim> first = inbox.claimDue(1);
        assertEquals(1, first.size());
        assertEquals(LiveCompletionInbox.Kind.TIMEOUT, first.get(0).kind(),
                "晚到候选不能直接变成 LATE");
        assertNull(first.get(0).candidate());
        assertEquals(LiveCompletionInbox.AckResult.SETTLED, inbox.settle(first.get(0)));
        assertEquals(1, inbox.size(), "超时槽保留等待迟到结果");

        List<LiveCompletionInbox.Claim> second = inbox.claimDue(1);
        assertEquals(1, second.size());
        assertEquals(LiveCompletionInbox.Kind.LATE, second.get(0).kind());
        assertEquals(1_000L * 1_000_000L, second.get(0).arrivalNanos());
        assertNotNull(second.get(0).candidate());
        assertEquals(LiveCompletionInbox.AckResult.SETTLED, inbox.settle(second.get(0)));
        assertEquals(0, inbox.size());
        inbox.close();
    }

    @Test
    @DisplayName("TIMEOUT 已 claim 未结算时仍可登记晚结果，但已有决定不变")
    void registerDuringTimeoutClaimDoesNotChangeIt() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-4");
        inbox.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);
        clock.advanceMillis(2_000L);

        LiveCompletionInbox.Claim timeout = inbox.claimDue(1).get(0);
        assertEquals(LiveCompletionInbox.Kind.TIMEOUT, timeout.kind());
        assertEquals(LiveCompletionInbox.RegisterResult.REGISTERED,
                inbox.register(key, failure(key, "PROVIDER_TIMEOUT")));
        assertTrue(inbox.claimDue(1).isEmpty(), "已有未结算 claim 时不重复发 claim");

        assertEquals(LiveCompletionInbox.AckResult.SETTLED, inbox.settle(timeout));
        LiveCompletionInbox.Claim late = inbox.claimDue(1).get(0);
        assertEquals(LiveCompletionInbox.Kind.LATE, late.kind());
        assertEquals("PROVIDER_TIMEOUT", late.candidate().failureCode());
        inbox.settle(late);
        inbox.close();
    }

    @Test
    @DisplayName("重投不刷新首次时间：重复 register 为 DUPLICATE，claim 仍用第一次 arrival")
    void duplicateRegisterKeepsFirstArrival() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-5");
        inbox.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);
        clock.advanceMillis(100L);
        inbox.register(key, business(key, "{\"tool\":\"role.wait\"}"));
        clock.advanceMillis(500L);
        assertEquals(LiveCompletionInbox.RegisterResult.DUPLICATE,
                inbox.register(key, business(key, "{\"tool\":\"role.target.set\",\"args\":{}}")));

        LiveCompletionInbox.Claim claim = inbox.claimDue(1).get(0);
        assertEquals(100L * 1_000_000L, claim.arrivalNanos());
        assertTrue(claim.candidate().reply().body().contains("role.wait"));
        inbox.settle(claim);
        inbox.close();
    }

    @Test
    @DisplayName("提交失败 retry 保持同一 claim 与同一类型，不因晚到候选改判 LATE")
    void retryKeepsSameClaimKind() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-6");
        inbox.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);
        clock.advanceMillis(2_000L);

        LiveCompletionInbox.Claim timeout = inbox.claimDue(1).get(0);
        inbox.register(key, business(key, "{\"tool\":\"role.wait\"}"));
        assertEquals(LiveCompletionInbox.AckResult.RETRYING, inbox.retry(timeout));

        clock.advanceMillis(100L);
        List<LiveCompletionInbox.Claim> again = inbox.claimDue(1);
        assertEquals(1, again.size());
        assertSame(timeout, again.get(0), "重试必须返回同一 claim 实例");
        assertEquals(LiveCompletionInbox.Kind.TIMEOUT, again.get(0).kind());

        inbox.settle(again.get(0));
        assertEquals(LiveCompletionInbox.Kind.LATE, inbox.claimDue(1).get(0).kind());
        inbox.close();
    }

    // ---------------------------------------------------------------- 边界与容量

    @Test
    @DisplayName("未授权/身份不一致/关闭：稳定拒绝，不产生新槽")
    void unauthorizedIdentityMismatchAndClosed() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-7");
        assertEquals(LiveCompletionInbox.RegisterResult.UNKNOWN,
                inbox.register(key, business(key, "{\"tool\":\"role.wait\"}")));

        inbox.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);
        AttemptKey other = new AttemptKey(RUN, "a-other", "ATT-9", 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> inbox.register(key, business(other, "{\"tool\":\"role.wait\"}")),
                "完成候选身份必须与 key 一致");
        assertEquals(0, inbox.claimDue(1).size(), "身份错误不得写入候选");

        clock.advanceMillis(2_000L);
        LiveCompletionInbox.Claim claim = inbox.claimDue(1).get(0);
        inbox.close();
        assertEquals(LiveCompletionInbox.OpenResult.CLOSED,
                inbox.open(key("a-8"), 0L, clock.epoch(), WALL_TIMEOUT_MILLIS));
        assertEquals(LiveCompletionInbox.RegisterResult.CLOSED,
                inbox.register(key, business(key, "{\"tool\":\"role.wait\"}")));
        assertEquals(LiveCompletionInbox.AckResult.CLOSED, inbox.settle(claim));
        assertTrue(inbox.claimDue(1).isEmpty());
        assertEquals(0, inbox.size());
    }

    @Test
    @DisplayName("容量满拒绝 open 且不改状态；同一身份重复 open 不更新截止")
    void capacityAndDuplicateOpen() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(2, clock::nano, clock::epoch);
        AttemptKey first = key("a-9");
        AttemptKey second = key("a-10");
        AttemptKey third = key("a-11");
        assertEquals(LiveCompletionInbox.OpenResult.OPENED,
                inbox.open(first, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS));
        assertEquals(LiveCompletionInbox.OpenResult.OPENED,
                inbox.open(second, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS));
        assertEquals(LiveCompletionInbox.OpenResult.FULL,
                inbox.open(third, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS));
        assertEquals(2, inbox.size());

        clock.advanceMillis(500L);
        assertEquals(LiveCompletionInbox.OpenResult.DUPLICATE,
                inbox.open(first, 0L, clock.epoch(), 5_000L), "重复 open 不得改截止");
        clock.advanceMillis(499L);
        assertTrue(inbox.claimDue(2).isEmpty(), "原截止之前不超时");
        clock.advanceMillis(1L);
        List<LiveCompletionInbox.Claim> claims = inbox.claimDue(2);
        assertEquals(2, claims.size());
        assertTrue(claims.stream().allMatch(c -> c.kind() == LiveCompletionInbox.Kind.TIMEOUT));
        inbox.close();
    }

    @Test
    @DisplayName("超时未收到网络结果的槽继续占容量，直到迟到结果结算才释放")
    void timeoutSlotHoldsCapacityUntilLateSettles() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey late = key("a-12");
        AttemptKey next = key("a-13");
        inbox.open(late, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);
        clock.advanceMillis(2_000L);
        LiveCompletionInbox.Claim timeout = inbox.claimDue(1).get(0);
        inbox.settle(timeout);
        assertEquals(1, inbox.size(), "超时槽仍占容量");
        assertEquals(LiveCompletionInbox.OpenResult.FULL,
                inbox.open(next, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS),
                "物理 HTTP 未结束前不能放容量");

        inbox.register(late, business(late, "{\"tool\":\"role.wait\"}"));
        LiveCompletionInbox.Claim lateClaim = inbox.claimDue(1).get(0);
        assertEquals(LiveCompletionInbox.Kind.LATE, lateClaim.kind());
        inbox.settle(lateClaim);
        assertEquals(0, inbox.size());
        assertEquals(LiveCompletionInbox.OpenResult.OPENED,
                inbox.open(next, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS));
        inbox.close();
    }

    @Test
    @DisplayName("claim 只能由本 inbox 确认：跨 inbox 的 claim 拒绝，重复确认是 STALE")
    void claimOwnershipAndStaleAck() {
        Clock clock = new Clock();
        LiveCompletionInbox first = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        LiveCompletionInbox second = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-14");
        first.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);
        second.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);
        clock.advanceMillis(2_000L);
        LiveCompletionInbox.Claim foreign = second.claimDue(1).get(0);
        assertThrows(IllegalArgumentException.class, () -> first.settle(foreign),
                "不能拿别的 inbox 的 claim 确认本槽");

        LiveCompletionInbox.Claim own = first.claimDue(1).get(0);
        assertEquals(LiveCompletionInbox.AckResult.SETTLED, first.settle(own));
        assertEquals(LiveCompletionInbox.AckResult.STALE_CLAIM, first.settle(own));
        first.close();
        second.close();
    }

    @Test
    @DisplayName("Completion 二选一：业务回执非空，技术失败空正文且固定码，不伪造 WAIT")
    void completionShapeIsExplicit() {
        AttemptKey key = key("a-15");
        assertThrows(IllegalArgumentException.class,
                () -> Completion.business(reply(key, " ")), "业务回执必须有正文");
        assertThrows(IllegalArgumentException.class,
                () -> Completion.failure("PROVIDER_LENGTH", reply(key, "{\"tool\":\"role.wait\"}")),
                "技术失败不得携带正文");
        assertThrows(IllegalArgumentException.class,
                () -> Completion.failure("provider_length", reply(key, "")));
        Completion failure = Completion.failure("PROVIDER_LENGTH", reply(key, ""));
        assertFalse(failure.businessReply());
        assertEquals("PROVIDER_LENGTH", failure.failureCode());
        assertTrue(Completion.business(reply(key, "{\"tool\":\"role.wait\"}")).businessReply());
    }

    @Test
    @DisplayName("AttemptKey 可从 Call 取；两个真实线程竞争时只有一个首次 claim")
    void concurrentRegisterAndClaimHaveSingleWinner() throws Exception {
        AttemptKey key = key("a-16");
        DecisionRun.Call call = new DecisionRun.Call(RUN, key.actionId(), key.attemptId(),
                key.attemptNo(), key.recoveryGeneration(), "SCHEDULED",
                List.of(DecisionModel.TOOL_WAIT), "{\"observe\":true}");
        assertEquals(key, AttemptKey.of(call));

        for (int round = 0; round < 40; round++) {
            Clock clock = new Clock();
            LongSupplier racingNanos = () -> "register".equals(Thread.currentThread().getName())
                    ? DEADLINE_NANOS - 1 : DEADLINE_NANOS;
            LiveCompletionInbox racing = new LiveCompletionInbox(1, racingNanos, clock::epoch);
            racing.open(key, 0L, clock.epoch(), WALL_TIMEOUT_MILLIS);

            CyclicBarrier barrier = new CyclicBarrier(2);
            LiveCompletionInbox.RegisterResult[] registered = new LiveCompletionInbox.RegisterResult[1];
            LiveCompletionInbox.Claim[] claimed = new LiveCompletionInbox.Claim[1];
            Thread worker = new Thread(() -> {
                try {
                    barrier.await();
                    registered[0] = racing.register(key,
                            business(key, "{\"tool\":\"role.wait\"}"));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }, "register");
            Thread writer = new Thread(() -> {
                try {
                    barrier.await();
                    List<LiveCompletionInbox.Claim> claims = racing.claimDue(1);
                    claimed[0] = claims.isEmpty() ? null : claims.get(0);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }, "writer");
            worker.start();
            writer.start();
            worker.join();
            writer.join();

            assertEquals(LiveCompletionInbox.RegisterResult.REGISTERED, registered[0]);
            assertNotNull(claimed[0], "到期或及时候选必须给出唯一 claim");
            assertFalse(claimed[0].kind() == LiveCompletionInbox.Kind.LATE,
                    "首次 claim 不能直接是 LATE");
            racing.settle(claimed[0]);
            racing.close();
        }
    }

    @Test
    @DisplayName("System.nanoTime 合法负值：dispatch 与 arrival 为负也能及时结算")
    void negativeNanoDispatchAndArrivalAreAccepted() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-negative");
        long dispatch = -5_000_000_000L;
        assertEquals(LiveCompletionInbox.OpenResult.OPENED,
                inbox.open(key, dispatch, clock.epoch(), WALL_TIMEOUT_MILLIS));

        clock.setNanos(dispatch + 999_000_000L);
        assertEquals(LiveCompletionInbox.RegisterResult.REGISTERED,
                inbox.register(key, business(key, "{\"tool\":\"role.wait\"}")));
        LiveCompletionInbox.Claim claim = inbox.claimDue(1).get(0);
        assertEquals(LiveCompletionInbox.Kind.REPLY, claim.kind());
        assertEquals(dispatch + 999_000_000L, claim.arrivalNanos());
        inbox.settle(claim);
        inbox.close();
    }

    @Test
    @DisplayName("纳秒跨 Long.MAX→MIN 回绕：按差值判定仍是及时 REPLY")
    void timelyAcrossLongWrap() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-wrap");
        long dispatch = Long.MAX_VALUE - 499_999_999L;
        long arrival = Long.MIN_VALUE + 499_999_999L; // 回绕后经过 999_999_999ns
        assertEquals(LiveCompletionInbox.OpenResult.OPENED,
                inbox.open(key, dispatch, clock.epoch(), WALL_TIMEOUT_MILLIS));

        clock.setNanos(arrival);
        inbox.register(key, business(key, "{\"tool\":\"role.wait\"}"));
        LiveCompletionInbox.Claim claim = inbox.claimDue(1).get(0);
        assertEquals(LiveCompletionInbox.Kind.REPLY, claim.kind());
        assertEquals(arrival, claim.arrivalNanos());
        inbox.settle(claim);
        inbox.close();
    }

    @Test
    @DisplayName("回绕后恰好 1000ms：整点算 late，先 TIMEOUT 再 LATE")
    void exactDeadlineAcrossLongWrapIsTimeoutThenLate() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-wrap-edge");
        long dispatch = Long.MAX_VALUE - 499_999_999L;
        long arrival = Long.MIN_VALUE + 500_000_000L; // 恰好 1_000_000_000ns
        inbox.open(key, dispatch, clock.epoch(), WALL_TIMEOUT_MILLIS);

        clock.setNanos(arrival);
        inbox.register(key, business(key, "{\"tool\":\"role.wait\"}"));
        LiveCompletionInbox.Claim timeout = inbox.claimDue(1).get(0);
        assertEquals(LiveCompletionInbox.Kind.TIMEOUT, timeout.kind());
        inbox.settle(timeout);
        LiveCompletionInbox.Claim late = inbox.claimDue(1).get(0);
        assertEquals(LiveCompletionInbox.Kind.LATE, late.kind());
        assertEquals(arrival, late.arrivalNanos());
        inbox.settle(late);
        inbox.close();
    }

    @Test
    @DisplayName("回绕 + writer 延迟 + retry：仍保持同一 REPLY claim")
    void writerDelayAndRetryAcrossWrapStayReply() {
        Clock clock = new Clock();
        LiveCompletionInbox inbox = new LiveCompletionInbox(1, clock::nano, clock::epoch);
        AttemptKey key = key("a-wrap-retry");
        long dispatch = Long.MAX_VALUE - 499_999_999L;
        long arrival = Long.MIN_VALUE + 100_000_000L;
        inbox.open(key, dispatch, clock.epoch(), WALL_TIMEOUT_MILLIS);
        clock.setNanos(arrival);
        inbox.register(key, business(key, "{\"tool\":\"role.wait\"}"));

        clock.setNanos(arrival + 60_000_000_000L); // writer 60s 后才处理
        LiveCompletionInbox.Claim claim = inbox.claimDue(1).get(0);
        assertEquals(LiveCompletionInbox.Kind.REPLY, claim.kind());
        assertEquals(arrival, claim.arrivalNanos());
        assertEquals(LiveCompletionInbox.AckResult.RETRYING, inbox.retry(claim));
        assertSame(claim, inbox.claimDue(1).get(0));
        assertEquals(LiveCompletionInbox.AckResult.SETTLED, inbox.settle(claim));
        inbox.close();
    }

    // ---------------------------------------------------------------- 夹具

    private static AttemptKey key(String actionId) {
        return new AttemptKey(RUN, actionId, "ATT-" + actionId.hashCode(), 1, 1);
    }

    private static Completion business(AttemptKey key, String body) {
        return Completion.business(reply(key, body));
    }

    private static Completion failure(AttemptKey key, String code) {
        return Completion.failure(code, reply(key, ""));
    }

    private static DecisionModel.Reply reply(AttemptKey key, String body) {
        return new DecisionModel.Reply(key.runId(), key.actionId(), key.attemptId(),
                key.attemptNo(), key.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 10, 5), "provider:test");
    }

    /** 可控双时钟：纳秒与墙钟一起前进。 */
    private static final class Clock {
        private final AtomicLong nanos = new AtomicLong(0L);
        private final AtomicLong epoch = new AtomicLong(1_700_000_000_000L);

        long nano() {
            return nanos.get();
        }

        long epoch() {
            return epoch.get();
        }

        void advanceMillis(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
            epoch.addAndGet(millis);
        }

        void setNanos(long value) {
            nanos.set(value);
        }
    }
}
