package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已结算决定的完整分支链（Root 候选 3 口径）。
 *
 * <p>用真实 {@code openEngineering} 夹具（scripted/live 决定 + 本地完成槽，0 provider）在父运行里
 * 产生并结算一条决定，然后在该切点创建分支（父 sessionOpen 仍为 true，按新口径允许），验证：
 * 子档继承同一决定历史、不重问、父 runId 旧回包被拒、子档新的合法动作能真实执行，
 * 且父档与封存件在这一过程中不变、费用只在父档结算一次。</p>
 */
@DisplayName("分支：已结算决定的继承与后续动作")
class BranchDecisionChainTest {

    private static final String ALICE = "alice";

    @Test
    @DisplayName("父已结算决定 → 子派生继承 → 不重问 → 旧回包拒 → 子新动作执行")
    void settledDecisionChain() throws Exception {
        Path root = Path.of("target", "branch-decision-chain");
        Files.createDirectories(root);
        Path journal = Files.createTempDirectory(root, "settled-")
                .resolve("parent.journal");

        WorldRuntime.BranchReceipt receipt;
        BranchProfile profile;
        long cut;
        String parentRunId;
        String parentSealSha;
        long parentPositionAfter;
        DecisionModel.Usage parentUsage;
        try (WorldRuntime parent = WorldWebScenario.openEngineering(journal).runtime()) {
            assertEquals("SESSION_OPEN:1", parent.beginDecisionSession("open-1"));
            assertEquals("DECIDING:1", parent.openDecisions("d-1", List.of(request("a-1"))));
            WorldRuntime.DispatchResult dispatch = parent.dispatchLiveDecision("d-1", "a-1");
            assertEquals("DISPATCHED", dispatch.verdict());
            assertTrue(parent.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(
                    reply(dispatch.call(), "{\"tool\":\"role.wait\"}"))) != null);
            assertEquals(1, parent.drainDecisionCallbacks());

            DecisionModel.Decision decided = decision(parent, "a-1");
            assertEquals(DecisionModel.Status.RECORDED, decided.status());
            assertTrue(decided.lastAttempt().settledDone(), "父决定必须已结算");
            DecisionModel.State state = parent.snapshot().decisions().orElseThrow().state();
            assertTrue(state.sessionOpen(), "父会话仍开着（本轮不要求关闭）");
            parentUsage = state.runUsage();
            cut = parent.snapshot().position();
            profile = parent.profile();
            parentRunId = parent.branchOrigin().isPresent() ? "?"
                    : parent.snapshot().decisions().orElseThrow().state().runId();

            receipt = parent.createBranch("child-settled", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "已结算决定切点");
            assertEquals(cut + 1L, receipt.firstPosition());
            parentSealSha = receipt.parentPrefixSha256();
            assertEquals(sha256File(receipt.sealedPrefix()), parentSealSha,
                    "封存件摘要必须等于创建回执里的父前缀摘要");

            try (WorldRuntime child = openChild(receipt, profile)) {
                DecisionModel.State childState = child.snapshot().decisions().orElseThrow().state();
                assertFalse(childState.sessionOpen(), "子档必须 sessionOpen=false（不自动开闸）");
                assertEquals(1, childState.decisions().size(), "必须继承同一条决定");
                DecisionModel.Decision inherited = childState.decisions().get(0);
                assertEquals("a-1", inherited.actionId());
                assertEquals(DecisionModel.Status.RECORDED, inherited.status());
                assertEquals(1, inherited.attempts().size(), "尝试必须原样继承，不重派");
                assertTrue(inherited.lastAttempt().settledDone());
                assertEquals(decided.lastAttempt().replyBody(),
                        inherited.lastAttempt().replyBody(), "回执正文必须原样保留");
                assertEquals(0, childState.runUsage().attempts() - parentUsage.attempts(),
                        "费用/用量不得在子档重复结算");

                // 不重问：同一 actionId 再次请求必须是幂等/冲突，不得新增决定或重派尝试
                child.beginDecisionSession("child-open");
                String reopen = child.openDecisions("child-reopen", List.of(request("a-1")));
                DecisionModel.State afterReopen =
                        child.snapshot().decisions().orElseThrow().state();
                assertEquals(1, afterReopen.decisions().size(),
                        "已结算 actionId 不得新增第二条决定（返回 " + reopen + "）");
                assertEquals(1, afterReopen.decisions().get(0).attempts().size(),
                        "已结算 actionId 不得重派尝试（返回 " + reopen + "）");
                assertEquals(0, afterReopen.runUsage().attempts() - parentUsage.attempts(),
                        "重问不得再次计费（返回 " + reopen + "）");

                // 旧回包：父 runId 的回执必须被拒
                DecisionModel.Reply stale = new DecisionModel.Reply(parentRunId, "a-1",
                        inherited.lastAttempt().attemptId(), 1, 1, "{}", null, "");
                assertEquals("REJECTED:" + DecisionModel.REASON_RUN_MISMATCH,
                        child.acceptDecisionReply("child-stale", stale),
                        "父运行回执必须按运行身份被拒");

                // 子合法新动作：真实开决定 → 派发 → 本地完成 → 记录
                assertEquals("DECIDING:1",
                        child.openDecisions("child-new", List.of(request("a-2"))));
                WorldRuntime.DispatchResult childDispatch =
                        child.dispatchLiveDecision("child-new", "a-2");
                assertEquals("DISPATCHED", childDispatch.verdict());
                assertTrue(child.offerLiveDecisionCompletion(
                        LiveCompletionInbox.Completion.business(
                                reply(childDispatch.call(), "{\"tool\":\"role.wait\"}"))) != null);
                assertEquals(1, child.drainDecisionCallbacks());
                DecisionModel.Decision childDecided = decision(child, "a-2");
                assertEquals(DecisionModel.Status.RECORDED, childDecided.status(),
                        "子档新动作必须真实执行并结算");
                assertTrue(childDecided.runId().equals(childState.runId()),
                        "子决定的 runId 必须是子运行");
                assertNotEquals(parentRunId, childDecided.runId());
                assertEquals(2, child.snapshot().decisions().orElseThrow().state()
                        .decisions().size(), "子档应同时保有继承决定与新决定");
            }

            parentPositionAfter = parent.snapshot().position();
            assertEquals(cut, parentPositionAfter, "子档工作不得改变父档位置");
            assertEquals(parentSealSha, sha256File(receipt.sealedPrefix()),
                    "封存件不得被子档工作改动");
        }
    }

    @Test
    @DisplayName("切点有 OPEN 决定：无论会话开关都拒（真实可达）")
    void openDecisionCutIsRejected() throws Exception {
        Path root = Path.of("target", "branch-decision-chain");
        Files.createDirectories(root);
        Path journal = Files.createTempDirectory(root, "open-").resolve("parent.journal");
        try (WorldRuntime parent = WorldWebScenario.openEngineering(journal).runtime()) {
            parent.beginDecisionSession("open-1");
            assertEquals("DECIDING:1", parent.openDecisions("d-1", List.of(request("a-1"))));
            long cut = parent.snapshot().position();
            BranchRejectedException rejected = assertThrows(BranchRejectedException.class,
                    () -> parent.createBranch("child-open-cut", cut,
                            BranchOrigin.Kind.OPERATOR_BRANCH, "OPEN 决定切点"));
            assertEquals(WorldRuntime.BRANCH_BARRIER_PENDING, rejected.code(),
                    "未解决决定必须在屏障处被拒；实际 " + rejected.code());
        }
    }

    @Test
    @DisplayName("切点有未结算尝试：无论会话开关都拒（真实可达）")
    void unsettledAttemptCutIsRejected() throws Exception {
        Path root = Path.of("target", "branch-decision-chain");
        Files.createDirectories(root);
        Path journal = Files.createTempDirectory(root, "unsettled-").resolve("parent.journal");
        try (WorldRuntime parent = WorldWebScenario.openEngineering(journal).runtime()) {
            parent.beginDecisionSession("open-1");
            parent.openDecisions("d-1", List.of(request("a-1")));
            assertEquals("DISPATCHED", parent.dispatchLiveDecision("d-1", "a-1").verdict());
            assertFalse(decision(parent, "a-1").lastAttempt().settledDone());
            long cut = parent.snapshot().position();
            BranchRejectedException rejected = assertThrows(BranchRejectedException.class,
                    () -> parent.createBranch("child-unsettled", cut,
                            BranchOrigin.Kind.OPERATOR_BRANCH, "未结算尝试切点"));
            assertTrue(WorldRuntime.BRANCH_BARRIER_PENDING.equals(rejected.code())
                            || WorldRuntime.BRANCH_ATTEMPT_IN_FLIGHT.equals(rejected.code()),
                    "未结算尝试必须拒；实际 " + rejected.code());
            assertFalse(Files.exists(journal.getParent().resolve("runs")
                    .resolve("child-unsettled.journal")), "拒绝不得落盘");
        }
    }

    @Test
    @DisplayName("模型防御：recover 形态（sessionOpen=false + 未结算 + 决定仍 OPEN）先命中屏障")
    void modelDefenseOnRecoveredState() throws Exception {
        Path root = Path.of("target", "branch-decision-chain");
        Files.createDirectories(root);
        Path journal = Files.createTempDirectory(root, "model-").resolve("parent.journal");
        try (WorldRuntime parent = WorldWebScenario.openEngineering(journal).runtime()) {
            parent.beginDecisionSession("open-1");
            parent.openDecisions("d-1", List.of(request("a-1")));
            parent.dispatchLiveDecision("d-1", "a-1");
            long cut = parent.snapshot().position();
            DecisionModel.State recovered = DecisionModel.recover(
                    parent.snapshot().decisions().orElseThrow().state());
            assertFalse(recovered.sessionOpen());
            assertFalse(recovered.decisions().get(0).lastAttempt().settledDone());
            // 该形态里决定仍是 OPEN，所以屏障先拒；未结算尝试是第二道门。
            // "无 OPEN 决定但尝试未结算"的持久化组合在本实现里不可达，合同已写明，不伪造记录充数。
            BranchRejectedException rejected = assertThrows(BranchRejectedException.class,
                    () -> WorldRuntime.requireSplittableDecisions(recovered, cut));
            assertEquals(WorldRuntime.BRANCH_BARRIER_PENDING, rejected.code(),
                    "recover 形态里 OPEN 决定先于未结算尝试被拒；实际 " + rejected.code());
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static WorldRuntime openChild(WorldRuntime.BranchReceipt receipt,
                                          BranchProfile profile) throws Exception {
        return WorldRuntime.openBranch(receipt.childFile(),
                id -> id.equals(receipt.parentRunId()) ? receipt.sealedPrefix() : null,
                profile, receipt.profileDigest());
    }

    private static DecisionRun.Request request(String actionId) {
        return new DecisionRun.Request(ALICE, actionId, "wake",
                new DecisionModel.Route("research", "key-1", "deepseek-flash", 1L,
                        20, 1_000_000, 1_024, 2));
    }

    private static DecisionModel.Reply reply(DecisionRun.Call call, String body) {
        return new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 10, 5), "local:probe");
    }

    private static DecisionModel.Decision decision(WorldRuntime runtime, String actionId) {
        return DecisionModel.decision(runtime.snapshot().decisions().orElseThrow().state(),
                actionId).orElseThrow();
    }

    private static String sha256File(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String pathSha(Path file) throws Exception {
        return sha256File(file);
    }

    private static void assertSameBytes(byte[] expected, byte[] actual, String message) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            assertEquals(java.util.HexFormat.of().formatHex(digest.digest(expected)),
                    java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(actual)),
                    message);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
