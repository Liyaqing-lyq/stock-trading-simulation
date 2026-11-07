package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.LiveCompletionInbox.AttemptKey;
import edu.cufe.auction.world.LiveCompletionInbox.Completion;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.RoleAccess.Role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2c 完成登记集成合同：Runtime 自动拥有完成槽，live 所有结算只能经 inbox pump。
 *
 * <p>不发 HTTP、不读真实密钥；时间由注入的可控双时钟给出，journal 只写测试目录。</p>
 */
class WorldRuntimeLiveCompletionTest {

    private static final String RUN = "live-completion-run";
    private static final long SEED = 11L;
    private static final long DAY = 86_400_000L;
    private static final long END = 30 * DAY;
    private static final long THINK = 1_000L;
    private static final long ACTION = 2_000L;
    private static final String WAKE = "SCHEDULED";

    private static final String ORG_F = "ORG-F";
    private static final String PRODUCT = "fund-a";
    private static final String ACC_FUND = "ACC-FUND";
    private static final String ACC_INV = "INV";
    private static final String INV = "investor";
    private static final String SYM = "CUFE1";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true)).build();

    private static final Path BASE = Path.of("target", "live-completion-tests");

    // ---------------------------------------------------------------- 1. 及时

    @Test
    @DisplayName("及时完成后 writer 延迟 60s 仍是业务回执，不因处理晚变 late")
    void timelyCompletionSurvivesWriterDelay() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("timely.journal");
        try (WorldRuntime rt = openLive(file, liveConfig(2, 1_000L), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            assertEquals("DISPATCHED", dispatched.verdict());

            clock.advanceMillis(999L);
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    rt.offerLiveDecisionCompletion(business(dispatched.call(),
                            "{\"tool\":\"role.wait\"}", 10, 5)));
            clock.advanceMillis(60_000L);

            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals(DecisionModel.Status.RECORDED, decision(rt, "a-1").status());
            assertEquals(DecisionModel.Outcome.WAIT, decision(rt, "a-1").outcome());
            assertTrue(decision(rt, "a-1").lastAttempt().settledDone());
            assertEquals(DecisionModel.Usage.unknownCost(1, 10, 5),
                    decision(rt, "a-1").lastAttempt().settled());
            assertTrue(rt.snapshot().pending().stream()
                    .noneMatch(task -> task.taskId().contains("a-1")));
        }
    }

    // ---------------------------------------------------------------- 2. late

    @Test
    @DisplayName("drain 前已到期的晚候选：先 TIMEOUT，再仅审计 LATE，绝不排业务")
    void lateBeforeDrainTimesOutThenAudits() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("late.journal");
        try (WorldRuntime rt = openLive(file, liveConfig(2, 1_000L), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            clock.advanceMillis(2_000L);
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    rt.offerLiveDecisionCompletion(business(dispatched.call(),
                            "{\"tool\":\"role.wait\"}", 10, 5)));

            assertEquals(1, rt.drainDecisionCallbacks(), "第一轮只处理 TIMEOUT");
            assertEquals(2, decision(rt, "a-1").attempts().size(),
                    "超时按既有有界重试语义生成新 attempt");
            assertEquals(DecisionModel.Status.OPEN, decision(rt, "a-1").status());
            assertNull(rt.snapshot().decisions().orElseThrow().actionRef("a-1"));
            assertTrue(rt.snapshot().pending().stream()
                    .noneMatch(task -> task.taskId().contains("a-1")));
            int auditsAfterTimeout = rt.decisionAudits().size();

            assertEquals(1, rt.drainDecisionCallbacks(), "统一 pump 处理 LATE（审计）");
            assertEquals(auditsAfterTimeout + 1, rt.decisionAudits().size());
            assertEquals(2, decision(rt, "a-1").attempts().size());
            assertNull(rt.snapshot().decisions().orElseThrow().actionRef("a-1"));
        }
    }

    // ---------------------------------------------------------------- 3. race

    @Test
    @DisplayName("timeout 与 register 真实两线程竞争：只产生一种首次结算")
    void timeoutAndRegisterRaceHasSingleWinner() throws Exception {
        for (int round = 0; round < 20; round++) {
            Clock clock = new Clock();
            java.util.concurrent.atomic.AtomicInteger reads =
                    new java.util.concurrent.atomic.AtomicInteger();
            LongSupplier racing = () -> {
                int index = reads.getAndIncrement();
                if (index == 0) {
                    return 0L; // dispatch 采样：授权时点
                }
                return "worker".equals(Thread.currentThread().getName())
                        ? 999_000_000L : 1_000_000_000L;
            };
            Path file = dataDir().resolve("race-" + round + ".journal");
            try (WorldRuntime rt = openLive(file, liveConfig(1, 1_000L, 1), clock,
                    new LiveCompletionInbox(1, racing, clock::epoch))) {
                rt.beginDecisionSession("s-1");
                rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
                DecisionRun.Call current = rt.dispatchLiveDecision("d-1", "a-1").call();
                java.util.concurrent.CountDownLatch start =
                        new java.util.concurrent.CountDownLatch(1);
                Throwable[] workerFailure = new Throwable[1];
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        rt.offerLiveDecisionCompletion(business(current,
                                "{\"tool\":\"role.wait\"}", 10, 5));
                    } catch (Throwable failure) {
                        workerFailure[0] = failure;
                    }
                }, "worker");
                worker.start();
                start.countDown();
                rt.drainDecisionCallbacks();
                worker.join();
                assertNull(workerFailure[0]);
                DecisionModel.Decision decision = decision(rt, "a-1");
                assertEquals(1, decision.attempts().size(), "maxAttempts=1 不得生成重试");
                if (decision.status() == DecisionModel.Status.RECORDED) {
                    assertEquals(DecisionModel.Outcome.WAIT, decision.outcome());
                    assertTrue(decision.lastAttempt().settledDone());
                    assertTrue(decision.lastAttempt().reason().isEmpty());
                } else {
                    assertEquals(DecisionModel.Status.EXPIRED, decision.status());
                    assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decision.outcome());
                    assertTrue(decision.lastAttempt().reason().endsWith("TIMEOUT"),
                            decision.lastAttempt().reason());
                    assertTrue(decision.lastAttempt().settledDone());
                    assertNull(rt.snapshot().decisions().orElseThrow().actionRef("a-1"));
                }
            }
        }
    }

    // ---------------------------------------------------------------- 4. dup/unknown

    @Test
    @DisplayName("重复与未知完成不刷新时间：第二次 DUPLICATE，未知 key UNKNOWN")
    void duplicateAndUnknownDoNotRefresh() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("dup.journal");
        try (WorldRuntime rt = openLive(file, liveConfig(2, 60_000L), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            clock.advanceMillis(500L);
            Completion first = business(dispatched.call(), "{\"tool\":\"role.wait\"}", 10, 5);
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    rt.offerLiveDecisionCompletion(first));
            clock.advanceMillis(100L);
            assertEquals(WorldRuntime.CompletionOffer.DUPLICATE,
                    rt.offerLiveDecisionCompletion(first));

            AttemptKey unknown = new AttemptKey(RUN, "never-dispatched", "ATT-999", 1, 1);
            DecisionModel.Reply ghost = new DecisionModel.Reply(RUN, unknown.actionId(),
                    unknown.attemptId(), 1, 1, "{\"tool\":\"role.wait\"}",
                    DecisionModel.Usage.unknownCost(1, 1, 1), "provider:test");
            assertEquals(WorldRuntime.CompletionOffer.UNKNOWN,
                    rt.offerLiveDecisionCompletion(Completion.business(ghost)));

            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals(500L * 1_000_000L,
                    decision(rt, "a-1").lastAttempt().settled().inputTokens() == 10
                            ? 500L * 1_000_000L : 0L);
            assertEquals(DecisionModel.Status.RECORDED, decision(rt, "a-1").status());
        }
    }

    // ---------------------------------------------------------------- 5. 技术失败

    @Test
    @DisplayName("已知 usage 技术失败：空正文固定码，保留声明/来源，不伪造 WAIT、不排业务")
    void knownUsageFailureIsExplicit() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("provider-failed.journal");
        DecisionModel.Usage declared = DecisionModel.Usage.unknownCost(1, 37, 9);
        try (WorldRuntime rt = openLive(file, liveConfig(2, 60_000L), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            clock.advanceMillis(500L);
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    rt.offerLiveDecisionCompletion(failure(dispatched.call(),
                            "PROVIDER_LENGTH", declared, "provider:test")));

            assertEquals(1, rt.drainDecisionCallbacks());
            DecisionModel.Decision decision = decision(rt, "a-1");
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decision.outcome());
            assertEquals("PROVIDER_LENGTH", decision.attempts().get(0).reason());
            assertEquals(declared, decision.attempts().get(0).declaredUsage());
            assertEquals(declared, decision.attempts().get(0).settled());
            assertTrue(decision.attempts().get(0).replyBody().isEmpty());
            assertNull(rt.snapshot().decisions().orElseThrow().actionRef("a-1"));
        }
        // 恢复：新事件必须能重算，usage/来源一致。
        try (WorldRuntime rt = openLive(file, liveConfig(2, 60_000L), clock, 2)) {
            DecisionModel.Decision decision = decision(rt, "a-1");
            assertEquals("PROVIDER_LENGTH", decision.attempts().get(0).reason());
            assertEquals(declared, decision.attempts().get(0).declaredUsage());
        }
        List<WorldCommit> commits = commits(file);
        assertTrue(hasEvent(commits, "DECISION_PROVIDER_FAILED_V1"));
    }

    @Test
    @DisplayName("无 usage 技术失败按预留保守结算，不写成 0 或 WAIT")
    void nullUsageFailureIsConservative() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("provider-failed-null.journal");
        try (WorldRuntime rt = openLive(file, liveConfig(2, 60_000L), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            DecisionModel.Usage reserved = decision(rt, "a-1").lastAttempt().reserved();
            clock.advanceMillis(500L);
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    rt.offerLiveDecisionCompletion(failure(dispatched.call(),
                            "PROVIDER_NETWORK", null, "provider:none")));
            assertEquals(1, rt.drainDecisionCallbacks());
            DecisionModel.Decision decision = decision(rt, "a-1");
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decision.outcome());
            assertEquals(reserved, decision.attempts().get(0).settled());
            assertNull(decision.attempts().get(0).declaredUsage());
        }
    }

    // ---------------------------------------------------------------- 6. IO 保留

    @Test
    @DisplayName("pump 提交 IO 失败：暂停、状态不动、claim 保留可重试")
    void ioFailureKeepsClaim() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("io.journal");
        WorldRuntime rt = openLive(file, liveConfig(2, 60_000L), clock, 2);
        try {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            clock.advanceMillis(500L);
            rt.offerLiveDecisionCompletion(business(dispatched.call(),
                    "{\"tool\":\"role.wait\"}", 10, 5));
            long position = rt.snapshot().position();
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, rt::drainDecisionCallbacks);
            } finally {
                Thread.interrupted();
            }
            assertTrue(rt.isPaused());
            assertEquals(position, rt.snapshot().position());
            assertEquals(DecisionModel.Status.OPEN, decision(rt, "a-1").status());
            assertFalse(decision(rt, "a-1").lastAttempt().settledDone());
            assertEquals(WorldRuntime.CompletionOffer.DUPLICATE,
                    rt.offerLiveDecisionCompletion(business(dispatched.call(),
                            "{\"tool\":\"role.wait\"}", 10, 5)), "失败候选不得被删除或重登记");
            assertEquals(0, rt.drainDecisionCallbacks(), "暂停期间同 claim 只重试不结算");
        } finally {
            quietClose(rt);
        }
    }

    // ---------------------------------------------------------------- 7. 容量

    @Test
    @DisplayName("超时槽占容量：新 dispatch 在提交前被拒，迟到审计后才放行")
    void timeoutSlotHoldsCapacityAndBlocksDispatch() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("capacity.journal");
        try (WorldRuntime rt = openLive(file, liveConfig(1, 1_000L), clock, 1)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research"),
                    request(BOB, "b-1", "research")));
            WorldRuntime.DispatchResult first = rt.dispatchLiveDecision("d-1", "a-1");
            assertEquals("DISPATCHED", first.verdict());
            clock.advanceMillis(2_000L);
            assertEquals(1, rt.expireDecisionTimeouts());
            long position = rt.snapshot().position();
            assertEquals("REJECTED", rt.dispatchLiveDecision("d-2", "b-1").verdict());
            assertEquals(position, rt.snapshot().position(), "容量拒绝不得写盘");
            assertEquals(1, rt.snapshot().decisions().orElseThrow()
                    .dispatch(first.call().attemptId()) == null ? 0 : 1);

            clock.advanceMillis(1_000L);
            rt.offerLiveDecisionCompletion(business(first.call(),
                    "{\"tool\":\"role.wait\"}", 10, 5));
            assertEquals(1, rt.drainDecisionCallbacks(), "迟到只审计");
            assertEquals("DISPATCHED", rt.dispatchLiveDecision("d-3", "b-1").verdict());
        }
    }

    // ---------------------------------------------------------------- 8. 直入口

    @Test
    @DisplayName("live 直接 accept/fail 一律拒绝，状态不前进")
    void directLiveEntriesRejected() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("direct.journal");
        try (WorldRuntime rt = openLive(file, liveConfig(2, 60_000L), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            DecisionModel.Attempt attempt = decision(rt, "a-1").lastAttempt();
            DecisionModel.Reply reply = new DecisionModel.Reply(RUN, "a-1",
                    attempt.attemptId(), attempt.attemptNo(), attempt.recoveryGeneration(),
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 10, 5),
                    "provider:test");
            long position = rt.snapshot().position();
            IllegalStateException accept = assertThrows(IllegalStateException.class,
                    () -> rt.acceptDecisionReply("r-1", reply));
            assertTrue(accept.getMessage().contains("LIVE_COMPLETION_REQUIRED"));
            IllegalStateException fail = assertThrows(IllegalStateException.class,
                    () -> rt.failDecisionAttempt("a-1", attempt.attemptNo(),
                            attempt.attemptId(), attempt.recoveryGeneration(), "TIMEOUT"));
            assertTrue(fail.getMessage().contains("LIVE_COMPLETION_REQUIRED"));
            assertThrows(IllegalStateException.class,
                    () -> rt.offerDecisionFailure(new WorldRuntime.DecisionFailure("a-1",
                            attempt.attemptNo(), attempt.attemptId(),
                            attempt.recoveryGeneration(), "X")));
            assertEquals(position, rt.snapshot().position());
            assertEquals(DecisionModel.Status.OPEN, decision(rt, "a-1").status());
            assertNotNull(dispatched.call());
        }
    }

    // ---------------------------------------------------------------- 9. 重启

    @Test
    @DisplayName("重启后 inbox 为空、不自动请求；begin 保守结算旧代次")
    void restartHasNoSlotsAndBeginsConservatively() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("restart.journal");
        String oldAttempt;
        try (WorldRuntime rt = openLive(file, liveConfig(2, 60_000L), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            oldAttempt = rt.dispatchLiveDecision("d-1", "a-1").call().attemptId();
        }
        try (WorldRuntime rt = openLive(file, liveConfig(2, 60_000L), clock, 2)) {
            assertThrows(IllegalStateException.class,
                    () -> rt.dispatchLiveDecision("d-before", "a-1"),
                    "重启后未 begin 不得派发");
            rt.beginDecisionSession("s-2");
            DecisionModel.Decision decision = decision(rt, "a-1");
            assertEquals(2, decision.attempts().size());
            assertEquals(oldAttempt, decision.attempts().get(0).attemptId());
            assertEquals(oldAttempt, decision.attempts().get(0).attemptId());
            assertEquals("DISPATCHED", rt.dispatchLiveDecision("d-new", "a-1").verdict(),
                    "新 attempt 需要新的显式派发");
        }
    }

    // ---------------------------------------------------------------- 10. 新事件篡改

    @Test
    @DisplayName("DECISION_PROVIDER_FAILED_V1 完整重签：改 reason/usage 必须拒绝，合法对照可恢复")
    void providerFailedEventTamperRejected() throws Exception {
        Clock clock = new Clock();
        Path source = dataDir().resolve("tamper-source.journal");
        try (WorldRuntime rt = openLive(source, liveConfig(2, 60_000L), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            clock.advanceMillis(500L);
            rt.offerLiveDecisionCompletion(failure(dispatched.call(), "PROVIDER_LENGTH",
                    DecisionModel.Usage.unknownCost(1, 37, 9), "provider:test"));
            assertEquals(1, rt.drainDecisionCallbacks());
        }
        Path control = rewrite("control.journal", source, commit -> { });
        try (WorldRuntime ignored = openLive(control, liveConfig(2, 60_000L), clock, 2)) {
            assertNotNull(ignored.snapshot().decisions());
        }
        Path reasonTamper = rewrite("reason.journal", source, commit -> {
            JsonNode event = decisionEvent(commit, "DECISION_PROVIDER_FAILED_V1");
            if (event == null) {
                return;
            }
            try {
                ObjectNode payload = (ObjectNode) JSON.readTree(event.path("payload").asText());
                payload.put("failureCode", "PROVIDER_OTHER");
                String text = JSON.writeValueAsString(payload);
                commit.put("commandContent", text);
                ((ObjectNode) event).put("payload", text);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThrows(IOException.class, () -> openLive(reasonTamper,
                liveConfig(2, 60_000L), clock, 2).close());

        Path usageTamper = rewrite("usage.journal", source, commit -> {
            JsonNode event = decisionEvent(commit, "DECISION_PROVIDER_FAILED_V1");
            if (event == null) {
                return;
            }
            try {
                ObjectNode payload = (ObjectNode) JSON.readTree(event.path("payload").asText());
                ObjectNode reply = (ObjectNode) payload.path("reply");
                ((ObjectNode) reply.path("usage")).put("inputTokens", 999);
                ((ObjectNode) event).put("payload", JSON.writeValueAsString(payload));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThrows(IOException.class, () -> openLive(usageTamper,
                liveConfig(2, 60_000L), clock, 2).close());
    }

    @Test
    @DisplayName("两槽 pump 首项 IO 失败：其余已 claim 项也必须 retry，不能永久卡住")
    void multiClaimIoFailureRetriesAllClaimed() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("multi-io.journal");
        LiveCompletionInbox inbox = new LiveCompletionInbox(2, clock::nano, clock::epoch);
        WorldRuntime rt = openLive(file, liveConfig(2, 60_000L, 3), clock, inbox);
        try {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research"),
                    request(BOB, "b-1", "research")));
            WorldRuntime.DispatchResult a = rt.dispatchLiveDecision("d-a", "a-1");
            WorldRuntime.DispatchResult b = rt.dispatchLiveDecision("d-b", "b-1");
            clock.advanceMillis(500L);
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    rt.offerLiveDecisionCompletion(business(a.call(),
                            "{\"tool\":\"role.wait\"}", 10, 5)));
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    rt.offerLiveDecisionCompletion(business(b.call(),
                            "{\"tool\":\"role.wait\"}", 10, 5)));
            long position = rt.snapshot().position();
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, rt::drainDecisionCallbacks);
            } finally {
                Thread.interrupted();
            }
            assertTrue(rt.isPaused());
            assertEquals(2, inbox.retryPendingCount(),
                    "本次已 claim 的两项都必须可重试");
            assertEquals(position, rt.snapshot().position());
            assertEquals(0, rt.drainDecisionCallbacks());
            assertEquals(2, inbox.retryPendingCount(), "暂停期间不得丢失重试");
        } finally {
            quietClose(rt);
        }
    }

    @Test
    @DisplayName("公开 live accept 不先全拒：未知/未派发回到原拒绝码，active 才 ISE")
    void publicAcceptPreservesOriginalRejections() throws Exception {
        Clock clock = new Clock();
        try (WorldRuntime rt = openLive(dataDir().resolve("accept-reject.journal"),
                liveConfig(2, 60_000L, 3), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research"),
                    request(BOB, "b-1", "research")));
            rt.dispatchLiveDecision("d-a", "a-1");
            long position = rt.snapshot().position();

            DecisionModel.Reply unknown = new DecisionModel.Reply(RUN, "nope", "ATT-999", 1, 1,
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 10, 5),
                    "provider:test");
            assertEquals("REJECTED:UNKNOWN_DECISION", rt.acceptDecisionReply("u", unknown));
            assertEquals(position, rt.snapshot().position());

            DecisionModel.Attempt b = decision(rt, "b-1").lastAttempt();
            DecisionModel.Reply undispatched = new DecisionModel.Reply(RUN, "b-1",
                    b.attemptId(), b.attemptNo(), b.recoveryGeneration(),
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 10, 5),
                    "provider:test");
            assertEquals("REJECTED:NO_DISPATCH",
                    rt.acceptDecisionReply("x", undispatched));
            assertEquals(position, rt.snapshot().position());

            DecisionModel.Attempt a = decision(rt, "a-1").lastAttempt();
            DecisionModel.Reply active = new DecisionModel.Reply(RUN, "a-1", a.attemptId(),
                    a.attemptNo(), a.recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                    DecisionModel.Usage.unknownCost(1, 10, 5), "provider:test");
            IllegalStateException required = assertThrows(IllegalStateException.class,
                    () -> rt.acceptDecisionReply("y", active));
            assertTrue(required.getMessage().contains("LIVE_COMPLETION_REQUIRED"));
            assertEquals(position, rt.snapshot().position());
        }
    }

    @Test
    @DisplayName("结算后的公开重复/改正文仍走原审计：DUPLICATE/IDENTITY_REUSE 且经济与任务不变")
    void publicAcceptAllowsAuditAfterSettle() throws Exception {
        Clock clock = new Clock();
        try (WorldRuntime rt = openLive(dataDir().resolve("accept-audit.journal"),
                liveConfig(2, 60_000L, 3), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            clock.advanceMillis(500L);
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    rt.offerLiveDecisionCompletion(business(dispatched.call(),
                            "{\"tool\":\"role.wait\"}", 10, 5)));
            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals(DecisionModel.Status.RECORDED, decision(rt, "a-1").status());

            DecisionModel.Attempt settled = decision(rt, "a-1").lastAttempt();
            DecisionModel.Reply same = new DecisionModel.Reply(RUN, "a-1", settled.attemptId(),
                    settled.attemptNo(), settled.recoveryGeneration(),
                    settled.replyBody(), settled.declaredUsage(), settled.usageSource());
            long position = rt.snapshot().position();
            int audits = rt.decisionAudits().size();
            assertEquals("DUPLICATE", rt.acceptDecisionReply("dup", same));
            assertEquals(position, rt.snapshot().position());
            assertEquals(audits, rt.decisionAudits().size());

            DecisionRun before = rt.snapshot().decisions().orElseThrow();
            Map<String, BigDecimal> cashBefore = cashOf(rt);
            DecisionModel.Reply changed = new DecisionModel.Reply(RUN, "a-1",
                    settled.attemptId(), settled.attemptNo(), settled.recoveryGeneration(),
                    "{\"tool\":\"role.wait\",\"args\":{\"x\":1}}", settled.declaredUsage(),
                    settled.usageSource());
            assertEquals("REJECTED:IDENTITY_REUSE", rt.acceptDecisionReply("changed", changed));
            assertEquals(audits + 1, rt.decisionAudits().size());
            assertEquals(before, rt.snapshot().decisions().orElseThrow(),
                    "审计批次不得改变决定子集");
            assertEquals(cashBefore, cashOf(rt), "审计批次不得改经济");
        }
    }

    @Test
    @DisplayName("重启后旧代次：已派发可归档审计，未派发保持 NO_DISPATCH 不归档")
    void staleAuditAfterRestartNeedsDispatch() throws Exception {
        Clock clock = new Clock();
        Path file = dataDir().resolve("stale-audit.journal");
        try (WorldRuntime rt = openLive(file, liveConfig(2, 60_000L, 3), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research"),
                    request(BOB, "b-1", "research")));
            rt.dispatchLiveDecision("d-a", "a-1");
        }
        try (WorldRuntime rt = openLive(file, liveConfig(2, 60_000L, 3), clock, 2)) {
            int audits = rt.decisionAudits().size();
            rt.beginDecisionSession("s-2");
            DecisionModel.Attempt oldA = decision(rt, "a-1").attempts().get(0);
            DecisionModel.Reply staleA = new DecisionModel.Reply(RUN, "a-1",
                    oldA.attemptId(), oldA.attemptNo(), oldA.recoveryGeneration(),
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 10, 5),
                    "provider:test");
            assertEquals("STALE_GENERATION", rt.acceptDecisionReply("stale-a", staleA));
            assertEquals(audits + 1, rt.decisionAudits().size());
            assertEquals(1, rt.snapshot().decisions().orElseThrow().archives().size());

            DecisionModel.Attempt oldB = decision(rt, "b-1").attempts().get(0);
            DecisionModel.Reply staleB = new DecisionModel.Reply(RUN, "b-1",
                    oldB.attemptId(), oldB.attemptNo(), oldB.recoveryGeneration(),
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 10, 5),
                    "provider:test");
            assertEquals("REJECTED:NO_DISPATCH", rt.acceptDecisionReply("stale-b", staleB));
            assertEquals(1, rt.snapshot().decisions().orElseThrow().archives().size(),
                    "未派发旧回执不得归档");
        }
    }

    @Test
    @DisplayName("live 旧 callback 入口委托 business completion；failure 入口明确拒绝")
    void offerCallbackDelegatesInLive() throws Exception {
        Clock clock = new Clock();
        try (WorldRuntime rt = openLive(dataDir().resolve("callback.journal"),
                liveConfig(2, 60_000L, 3), clock, 2)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(request(ALICE, "a-1", "research")));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-1", "a-1");
            clock.advanceMillis(500L);
            DecisionModel.Reply reply = new DecisionModel.Reply(RUN, "a-1",
                    dispatched.call().attemptId(), dispatched.call().attemptNo(),
                    dispatched.call().recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                    DecisionModel.Usage.unknownCost(1, 10, 5), "provider:test");
            assertEquals("QUEUED", rt.offerDecisionCallback(reply));
            assertEquals("DUPLICATE", rt.offerDecisionCallback(reply));
            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals(DecisionModel.Status.RECORDED, decision(rt, "a-1").status());
            assertThrows(IllegalStateException.class, () -> rt.offerDecisionFailure(
                    new WorldRuntime.DecisionFailure("a-1", 1,
                            dispatched.call().attemptId(),
                            dispatched.call().recoveryGeneration(), "X")));
        }
    }

    @Test
    @DisplayName("宿主只读完成槽计数区分逻辑结算与物理返回，未归档迟到前保持占额")
    void liveCompletionSlotsRemainOccupiedUntilLateAck() throws Exception {
        Clock clock = new Clock();
        try (WorldRuntime rt = openLive(dataDir().resolve("slot-observer.journal"),
                liveConfig(1, 1_000L, 1), clock, 1)) {
            assertEquals(0, rt.liveCompletionSlots());
            rt.beginDecisionSession("session");
            rt.openDecisions("open", List.of(request(ALICE, "a", "research")));
            DecisionRun.Call call = rt.dispatchLiveDecision("dispatch", "a").call();
            assertEquals(1, rt.liveCompletionSlots());
            clock.advanceMillis(1_000L);
            assertEquals(1, rt.expireDecisionTimeouts());
            assertEquals(0, rt.outstandingLiveDispatches());
            assertEquals(1, rt.liveCompletionSlots(), "超时记账不释放还未返回的物理完成槽");
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    rt.offerLiveDecisionCompletion(business(call, "{\"tool\":\"role.wait\"}", 10, 5)));
            assertEquals(1, rt.liveCompletionSlots(), "登记但尚未归档仍占槽");
            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals(0, rt.liveCompletionSlots());
            assertEquals(DecisionModel.Status.EXPIRED, decision(rt, "a").status());
            assertNull(rt.snapshot().decisions().orElseThrow().actionRef("a"));
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static Path dataDir() throws IOException {
        Files.createDirectories(BASE);
        Path dir = BASE.resolve("case-" + System.nanoTime());
        Files.createDirectories(dir);
        return dir;
    }

    private static WorldRuntime openLive(Path file, DecisionModel.Config config, Clock clock,
                                         int capacity) throws IOException {
        return openLive(file, config, clock,
                new LiveCompletionInbox(capacity, clock::nano, clock::epoch));
    }

    private static WorldRuntime openLive(Path file, DecisionModel.Config config, Clock clock,
                                         LiveCompletionInbox inbox) throws IOException {
        return new WorldRuntime(file, liveManifest(), OPENINGS, registry(), MFG, charter(),
                roles(), info(), config, inbox);
    }

    private static DecisionModel.Decision decision(WorldRuntime rt, String actionId) {
        return DecisionModel.decision(rt.snapshot().decisions().orElseThrow().state(), actionId)
                .orElseThrow();
    }

    private static Map<String, BigDecimal> cashOf(WorldRuntime rt) {
        Map<String, BigDecimal> cash = new TreeMap<>();
        rt.snapshot().accounts().forEach((id, account) -> cash.put(id, account.cash()));
        return cash;
    }

    private static void quietClose(WorldRuntime rt) {
        try {
            rt.close();
        } catch (RuntimeException ignored) {
            // 中断已关闭通道
        }
    }

    private static DecisionRun.Request request(String person, String actionId, String scene) {
        return new DecisionRun.Request(person, actionId, WAKE, new DecisionModel.Route(scene,
                "key-1", "deepseek-flash", 1L, 20, 1_000_000, 1_024, 2));
    }

    private static Completion business(DecisionRun.Call call, String body, long input,
                                       long output) {
        return Completion.business(new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, input, output), "provider:test"));
    }

    private static Completion failure(DecisionRun.Call call, String code,
                                      DecisionModel.Usage usage, String source) {
        return Completion.failure(code, new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), "", usage, source));
    }

    private static DecisionModel.Config liveConfig(int maxInFlight, long wallTimeoutMillis) {
        return liveConfig(maxInFlight, wallTimeoutMillis, 3);
    }

    private static DecisionModel.Config liveConfig(int maxInFlight, long wallTimeoutMillis,
                                                   int maxAttempts) {
        DecisionModel.Usage cap = DecisionModel.Usage.unknownCost(500, 20_000_000, 5_000_000);
        Map<String, DecisionModel.Usage> persons = new TreeMap<>();
        for (String person : List.of(ALICE, BOB, CAROL, DAVE, ERIN)) {
            persons.put(person, DecisionModel.Usage.unknownCost(100, 4_000_000, 1_000_000));
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_DEEPSEEK_HTTP,
                "deepseek:completion-test", DecisionModel.ESTIMATE_VERSION,
                Map.of(WAKE, THINK), actionMillis(),
                new DecisionModel.Limits(maxAttempts, 20_000, 4_000, 60_000, 4_096, 4,
                        maxInFlight, wallTimeoutMillis),
                new DecisionModel.Budget(cap, persons), DecisionModel.Profile.LIVE_HTTP_V1);
    }

    private static Map<String, Long> actionMillis() {
        Map<String, Long> action = new TreeMap<>();
        for (String tool : List.of(RoleTradingView.TOOL_READING_START,
                RoleTradingView.TOOL_READING_CANCEL, RoleTradingView.TOOL_RESEARCH_SUBMIT,
                RoleTradingView.TOOL_TARGET_SET, RoleTradingView.TOOL_TARGET_REVIEW,
                RoleTradingView.TOOL_TARGET_HALT, RoleTradingView.TOOL_TARGET_CONFIRM,
                RoleTradingView.TOOL_TARGET_CANCEL, RoleTradingView.TOOL_CHILD_SUBMIT,
                RoleTradingView.TOOL_CHILD_CANCEL)) {
            action.put(tool, ACTION);
        }
        return action;
    }

    private static Map<String, Integer> schemaNineModules() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions",
                "dividend")) {
            modules.put(name, 1);
        }
        return modules;
    }

    private static Map<String, Integer> liveModules() {
        Map<String, Integer> modules = schemaNineModules();
        modules.put("live-decisions", 1);
        return modules;
    }

    private static RunManifest liveManifest() {
        return new RunManifest(RUN, SEED,
                RunManifest.RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1, liveModules(),
                Map.of("fixture", "live-completion-v1"), RunManifest.LIVE_DECISIONS_V1);
    }

    private static RoleRun.Config roles() {
        return new RoleRun.Config(
                List.of(new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND))),
                List.of(new RoleAccess.Appointment("A-1", ALICE, ORG_F, Role.RESEARCHER, 0L,
                                END, null),
                        new RoleAccess.Appointment("A-2", BOB, ORG_F, Role.PORTFOLIO_MANAGER, 0L,
                                END, null),
                        new RoleAccess.Appointment("A-3", CAROL, ORG_F, Role.RISK_OFFICER, 0L,
                                END, null),
                        new RoleAccess.Appointment("A-4", DAVE, ORG_F, Role.EXECUTION_TRADER, 0L,
                                END, null)));
    }

    private static InformationRun.Config info() {
        return new InformationRun.Config(List.of(ALICE, BOB, CAROL, DAVE, ERIN));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter("cufe", List.of(
                new BoardModel.Seat("s1", ALICE), new BoardModel.Seat("s2", BOB),
                new BoardModel.Seat("s3", CAROL), new BoardModel.Seat("s4", DAVE),
                new BoardModel.Seat("s5", ERIN)), "s1", BoardModel.QUORUM);
    }

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount(ACC_FUND, PRODUCT, new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount(ACC_INV, INV, new BigDecimal("0.00"), "实验开局历史禀赋"),
            new OpeningAccount("CO", "company", new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
            new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(ORG_F, PartyKind.ORGANIZATION, "中央财经资管"),
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(PRODUCT, OpeningRegistry.OwnerKind.PRODUCT,
                        null),
                new OpeningRegistry.PropertyOwner("orgf", OpeningRegistry.OwnerKind.ORGANIZATION,
                        ORG_F),
                new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OpeningRegistry.OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV, OpeningRegistry.OwnerKind.PERSON, INV),
                new OpeningRegistry.PropertyOwner("company",
                        OpeningRegistry.OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier",
                        OpeningRegistry.OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer",
                        OpeningRegistry.OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE,
                        null));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYM, "cufe", 200L, new BigDecimal("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }

    private static List<WorldCommit> commits(Path file) throws IOException {
        try (WorldJournal reader = new WorldJournal(file, RUN)) {
            return reader.commits();
        }
    }

    private static boolean hasEvent(List<WorldCommit> commits, String type) {
        for (WorldCommit commit : commits) {
            for (WorldEvent event : commit.events()) {
                if (event.type().equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Path rewrite(String name, Path source,
                                java.util.function.Consumer<ObjectNode> change)
            throws Exception {
        Path destination = source.getParent().resolve(name);
        try (DataInputStream input = new DataInputStream(Files.newInputStream(source));
             DataOutputStream output = new DataOutputStream(Files.newOutputStream(destination))) {
            while (input.available() > 0) {
                int length = input.readInt();
                assertEquals(~length, input.readInt());
                byte[] payload = input.readNBytes(length);
                assertEquals(32, input.readNBytes(32).length);
                ObjectNode commit = (ObjectNode) JSON.readTree(payload);
                change.accept(commit);
                byte[] rewritten = JSON.writeValueAsBytes(commit);
                output.writeInt(rewritten.length);
                output.writeInt(~rewritten.length);
                output.write(rewritten);
                output.write(MessageDigest.getInstance("SHA-256").digest(rewritten));
            }
        }
        return destination;
    }

    private static JsonNode decisionEvent(ObjectNode commit, String type) {
        for (JsonNode event : commit.withArray("events")) {
            if (type.equals(event.path("type").asText())) {
                return event;
            }
        }
        return null;
    }

    /** 可控双时钟。 */
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
    }
}
