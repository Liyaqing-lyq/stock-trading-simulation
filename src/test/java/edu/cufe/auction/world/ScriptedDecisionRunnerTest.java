package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.RoleAccess.Role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4d Task2–3：scripted 决定提供方与 runner（测试先行）。
 *
 * <p>脚本只提供回执正文、声明用量与真实墙钟延迟，<b>不提供</b>人物、任职、尝试标识、
 * 恢复代次或模拟时间；这些只由程序按冻结状态绑定。延迟只影响墙钟等待，不能推进模拟时间。</p>
 */
@DisplayName("ScriptedDecisionRunner：脚本提供方与有界队列")
class ScriptedDecisionRunnerTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-scripted-runner";
    private static final String SYM = "CUFE1";
    private static final long DAY = 86_400_000L;
    private static final long END = 30 * DAY;
    private static final long THINK = 1_000L;
    private static final long ACTION = 2_000L;

    private static final String ORG_F = "ORG-F";
    private static final String ORG_G = "ORG-G";
    private static final String PRODUCT = "fund-a";
    private static final String PRODUCT_G = "fund-b";
    private static final String ACC_FUND = "ACC-FUND";
    private static final String ACC_FUND_G = "ACC-FUND2";
    private static final String ACC_INV = "INV";
    private static final String INV = "investor";

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";

    private static final String WAKE = "SCHEDULED";
    private static final String WAIT_BODY = "{\"tool\":\"role.wait\"}";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("回执身份只取自派发调用，脚本不能提供尝试标识或代次")
    void runnerBindsIdentityFromTheCall() throws IOException {
        try (WorldRuntime rt = open("identity.journal", config(4, 2, 60_000L, 2));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of("r-a",
                     new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 60, 20),
                             "scripted:runner-v1", 0L)))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(new DecisionRun.Request(ALICE, "r-a", WAKE)));

            DecisionRun.Call call = rt.dispatchDecision("r-a");
            DecisionModel.Reply reply = runner.replyFor(call);
            assertEquals(call.runId(), reply.runId());
            assertEquals(call.actionId(), reply.actionId());
            assertEquals(call.attemptId(), reply.attemptId());
            assertEquals(call.attemptNo(), reply.attemptNo());
            assertEquals(call.recoveryGeneration(), reply.recoveryGeneration());
            assertEquals(WAIT_BODY, reply.body());
            assertEquals(usage(1, 60, 20), reply.usage());
            assertEquals("scripted:runner-v1", reply.usageSource());

            assertEquals("QUEUED", runner.deliver("r-a"));
            assertEquals(1, rt.drainDecisionCallbacks());
            DecisionModel.Decision recorded = DecisionModel.decision(
                    rt.snapshot().decisions().orElseThrow().state(), "r-a").orElseThrow();
            assertEquals(DecisionModel.Status.RECORDED, recorded.status());
            assertEquals(call.attemptId(), recorded.lastAttempt().attemptId(),
                    "持久身份的来源只能是程序");
            assertEquals(usage(1, 60, 20), recorded.lastAttempt().settled());
        }
    }

    @Test
    @DisplayName("脚本延迟只影响墙钟：延迟与立即返回得到相同钱股、世界时间与下一轮输入")
    void scriptDelayOnlyAffectsWallClock() throws Exception {
        Snapshot immediate = runScenario("immediate.journal", 0L);
        Snapshot delayed = runScenario("delayed.journal", 40L);
        assertEquals(immediate.timeMillis(), delayed.timeMillis(), "模拟时间不得被延迟推进");
        assertEquals(0L, delayed.timeMillis());
        assertEquals(immediate.accounts(), delayed.accounts());
        assertEquals(immediate.securities(), delayed.securities());
        assertEquals(immediate.exchange(), delayed.exchange());
        assertEquals(immediate.nextInput(), delayed.nextInput());
    }

    private record Snapshot(long timeMillis, Map<String, CashLedger.CashAccount> accounts,
                            java.util.Optional<SecuritiesLedger.State> securities,
                            java.util.Optional<WorldExchange.State> exchange, String nextInput) {
    }

    private Snapshot runScenario(String journal, long delayMillis) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (WorldRuntime rt = open(journal, config(4, 2, 60_000L, 2));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of(
                     "d-a", new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 60, 20),
                             "scripted:runner-v1", delayMillis),
                     "d-b", new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 40, 10),
                             "scripted:runner-v1", delayMillis)), executor)) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(new DecisionRun.Request(ALICE, "d-a", WAKE),
                    new DecisionRun.Request(BOB, "d-b", WAKE)));
            runner.deliverAsync("d-a");
            runner.deliverAsync("d-b");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (rt.queuedDecisionCallbacks() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals(2, rt.drainDecisionCallbacks(), "两条脚本回执都必须应用");
            rt.openDecisions("d-2", List.of(new DecisionRun.Request(ALICE, "e-a", WAKE)));
            return new Snapshot(rt.snapshot().timeMillis(), rt.snapshot().accounts(),
                    rt.snapshot().securities(), rt.snapshot().exchange(),
                    DecisionModel.decision(rt.snapshot().decisions().orElseThrow().state(), "e-a")
                            .orElseThrow().inputText());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("脚本内容非法按技术失败有界重试，不得变成观望")
    void scriptFailureIsTechnicalNotWait() throws IOException {
        try (WorldRuntime rt = open("failure.journal", config(4, 2, 60_000L, 2));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of("f-a",
                     new ScriptedDecisionRunner.ScriptedReply("{\"tool\":", usage(1, 50, 10),
                             "scripted:runner-v1", 0L)))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(new DecisionRun.Request(ALICE, "f-a", WAKE)));
            assertEquals("QUEUED", runner.deliver("f-a"));
            assertEquals(1, rt.drainDecisionCallbacks());

            DecisionModel.Decision failed = DecisionModel.decision(
                    rt.snapshot().decisions().orElseThrow().state(), "f-a").orElseThrow();
            assertNotEquals(DecisionModel.Outcome.WAIT, failed.outcome(), "技术失败不得记成观望");
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, failed.outcome());
            assertEquals(2, failed.attempts().size(), "按原观察有界重试");
            assertEquals("{\"tool\":", failed.attempts().get(0).replyBody(), "原正文必须保留");
            assertEquals(usage(1, 50, 10), failed.attempts().get(0).settled(),
                    "已知脚本用量按声明结算");
            assertNull(pendingOrNull(rt, "f-a"), "失败不得排业务任务");
        }
    }

    @Test
    @DisplayName("队列容量满时 runner 明确返回 QUEUE_FULL 且不静默丢弃")
    void queueFullIsExplicitFromRunner() throws IOException {
        try (WorldRuntime rt = open("queue.journal", config(1, 2, 60_000L, 2));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of(
                     "q-a", new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 30, 10),
                             "scripted:runner-v1", 0L),
                     "q-b", new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 30, 10),
                             "scripted:runner-v1", 0L)))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(new DecisionRun.Request(ALICE, "q-a", WAKE),
                    new DecisionRun.Request(BOB, "q-b", WAKE)));
            assertEquals("QUEUED", runner.deliver("q-a"));
            assertEquals("QUEUE_FULL", runner.deliver("q-b"), "满队列必须明确返回");
            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals("QUEUED", runner.deliver("q-b"), "腾出名额后可以重投");
            assertEquals(1, rt.drainDecisionCallbacks());
        }
    }

    @Test
    @DisplayName("真实墙钟超时按预留结算一次，脚本迟到不再生效")
    void wallTimeoutSettlesOnceAndLateReplyDoesNotApply() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try (WorldRuntime rt = open("timeout.journal", config(4, 2, 5L, 2));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of("t-a",
                     new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 30, 10),
                             "scripted:runner-v1", 60L)), executor)) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(new DecisionRun.Request(ALICE, "t-a", WAKE)));
            runner.deliverAsync("t-a");
            Thread.sleep(20L);
            assertEquals(1, rt.expireDecisionTimeouts(), "真实墙钟超时必须结算一次");
            assertEquals(0L, rt.snapshot().timeMillis(), "墙钟不推进模拟时间");

            Thread.sleep(80L);
            rt.drainDecisionCallbacks();
            DecisionModel.Decision decision = DecisionModel.decision(
                    rt.snapshot().decisions().orElseThrow().state(), "t-a").orElseThrow();
            assertNotEquals(DecisionModel.Outcome.WAIT, decision.outcome(),
                    "超时不能被写成人物观望");
            // 冻结纯核合同规定：OPEN 且已有多条尝试时 outcome 必须等于最后尝试结果
            // （DecisionModel.validateDecision）。迟到回执不得改写已结算尝试，这一点由下一条断言保证。
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decision.outcome());
            assertEquals(2, decision.attempts().size());
            assertEquals(usage(1, 120, 60), decision.attempts().get(0).settled(),
                    "未知用量按冻结预留结算");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("尝试次数用尽后明确停在不重试，而不是替换成观望")
    void boundedRetryStopsWithoutBecomingWait() throws IOException {
        try (WorldRuntime rt = open("retry.journal", config(4, 2, 60_000L, 1));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of("b-a",
                     new ScriptedDecisionRunner.ScriptedReply("{\"tool\":", usage(1, 50, 10),
                             "scripted:runner-v1", 0L)))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(new DecisionRun.Request(ALICE, "b-a", WAKE)));
            assertEquals("QUEUED", runner.deliver("b-a"));
            assertEquals(1, rt.drainDecisionCallbacks());

            DecisionModel.Decision decision = DecisionModel.decision(
                    rt.snapshot().decisions().orElseThrow().state(), "b-a").orElseThrow();
            assertEquals(DecisionModel.Status.EXPIRED, decision.status(), "用尽即停，不无限重试");
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decision.outcome());
            assertNotEquals(DecisionModel.Outcome.WAIT, decision.outcome());
            assertEquals(1, decision.attempts().size());
            long position = rt.snapshot().position();
            assertEquals(0, rt.drainDecisionCallbacks(), "没有新候选可应用");
            assertEquals(position, rt.snapshot().position());
        }
    }

    @Test
    @DisplayName("close 之后异步派发明确拒绝，重复 close 安全")
    void closeStopsAsyncDispatch() throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        WorldRuntime rt = open("close.journal", config(4, 2, 60_000L, 2));
        ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of("c-a",
                new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 30, 10),
                        "scripted:runner-v1", 0L)), executor);
        rt.beginDecisionSession("open-1");
        rt.openDecisions("d-1", List.of(new DecisionRun.Request(ALICE, "c-a", WAKE)));
        runner.close();
        assertThrows(IllegalStateException.class, () -> runner.deliverAsync("c-a"),
                "关闭后不得再派发");
        runner.close();
        executor.shutdownNow();
        rt.close();
    }

    @Test
    void duplicateAsyncDispatchSchedulesOnlyOnePhysicalInvocation() throws IOException {
        List<Runnable> tasks = new ArrayList<>();
        try (WorldRuntime rt = open("duplicate-async.journal", config(4, 2, 60000, 2));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of("once",
                     new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 20, 5), "scripted:test", 0)),
                     tasks::add)) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(new DecisionRun.Request(ALICE, "once", WAKE)));
            runner.deliverAsync("once");
            runner.deliverAsync("once");
            assertEquals(1, tasks.size(), "同一尝试只可排一个实际调用");
            tasks.get(0).run();
            assertEquals(1, runner.executedCount());
            assertEquals(1, rt.drainDecisionCallbacks());
        }
    }

    @Test
    void synchronousResendCannotExecuteAnAlreadyScheduledAsyncAttempt() throws IOException {
        List<Runnable> tasks = new ArrayList<>();
        try (WorldRuntime rt = open("sync-during-async.journal", config(4, 2, 60000, 2));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of("once",
                     new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 20, 5), "scripted:test", 0)),
                     tasks::add)) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(new DecisionRun.Request(ALICE, "once", WAKE)));
            runner.deliverAsync("once");
            assertEquals("IN_FLIGHT", runner.deliver("once"));
            assertEquals(0, rt.queuedDecisionCallbacks());
            tasks.get(0).run();
            assertEquals(1, rt.queuedDecisionCallbacks());
            assertEquals(1, runner.executedCount());
        }
    }

    @Test
    void closingAnOwnedPoolAllowsItsJvmToExit() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path output = tempDir.resolve("owned-runner-child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), OwnedRunnerExitProbe.class.getName(),
                tempDir.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean exited;
        try {
            exited = child.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor();
        }
        String log = java.nio.file.Files.readString(output);
        assertTrue(log.contains("CHILD_COMPLETED"), log);
        assertTrue(exited, "已关闭runner仍有线程阻止JVM退出\n" + log);
        assertEquals(0, child.exitValue());
    }

    public static final class OwnedRunnerExitProbe {
        public static void main(String[] args) throws Exception {
            ScriptedDecisionRunnerTest fixture = new ScriptedDecisionRunnerTest();
            fixture.tempDir = Path.of(args[0]);
            try (WorldRuntime rt = fixture.open("owned-child.journal", config(4, 2, 60000, 2));
                 ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of("once",
                         new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 20, 5), "scripted:test", 0)), 1)) {
                rt.beginDecisionSession("session");
                rt.openDecisions("observe", List.of(new DecisionRun.Request(ALICE, "once", WAKE)));
                runner.deliverAsync("once");
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (rt.queuedDecisionCallbacks() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
                if (rt.drainDecisionCallbacks() != 1) throw new AssertionError("缺少回执");
            }
            System.out.println("CHILD_COMPLETED");
        }
    }

    @Test
    @DisplayName("异步队列满时保留结果，宿主用 retryPending 重投且不重复执行脚本")
    void queueFullRetainsOutcomeForExplicitRetry() throws IOException {
        List<Runnable> tasks = new ArrayList<>();
        try (WorldRuntime rt = open("queue-retain.journal", config(1, 2, 60_000L, 2));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of(
                     "q-a", new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 30, 10),
                             "scripted:test", 0L),
                     "q-b", new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 30, 10),
                             "scripted:test", 0L)), tasks::add)) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(new DecisionRun.Request(ALICE, "q-a", WAKE),
                    new DecisionRun.Request(BOB, "q-b", WAKE)));
            runner.deliverAsync("q-a");
            runner.deliverAsync("q-b");
            assertEquals(2, tasks.size(), "两个不同尝试各排一次");
            tasks.get(0).run();
            tasks.get(1).run();
            assertEquals(1, runner.pendingDeliveries(), "满队列结果必须保留可重投");
            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals(1, runner.retryPending(), "宿主显式重投成功");
            assertEquals(0, runner.pendingDeliveries());
            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals(2, runner.executedCount(), "重投只重用同一份结果，不重复执行脚本");
        }
    }

    @Test
    @DisplayName("执行器拒绝提交时不占死尝试身份，之后仍能正常排一次")
    void rejectedSubmissionReleasesTheAttemptClaim() throws IOException {
        java.util.concurrent.atomic.AtomicBoolean reject =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        List<Runnable> tasks = new ArrayList<>();
        try (WorldRuntime rt = open("reject.journal", config(4, 2, 60_000L, 2));
             ScriptedDecisionRunner runner = new ScriptedDecisionRunner(rt, Map.of("r-a",
                     new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY, usage(1, 20, 5),
                             "scripted:test", 0L)),
                     runnable -> {
                         if (reject.get()) {
                             throw new java.util.concurrent.RejectedExecutionException("busy");
                         }
                         tasks.add(runnable);
                     })) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(new DecisionRun.Request(ALICE, "r-a", WAKE)));
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> runner.deliverAsync("r-a"), "执行器拒绝必须原样抛出");
            assertEquals(0, runner.executedCount());
            reject.set(false);
            runner.deliverAsync("r-a");
            assertEquals(1, tasks.size(), "拒绝不得把尝试身份占死");
            tasks.get(0).run();
            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals(1, runner.executedCount());
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static PendingTask pendingOrNull(WorldRuntime rt, String fragment) {
        for (PendingTask task : rt.snapshot().pending()) {
            if (task.taskId().contains(fragment)) {
                return task;
            }
        }
        return null;
    }

    private WorldRuntime open(String name, DecisionModel.Config config) throws IOException {
        return new WorldRuntime(tempDir.resolve(name), manifest(), OPENINGS, registry(), MFG,
                charter(), roles(), info(), config);
    }

    private static DecisionModel.Usage usage(long calls, long inputTokens, long outputTokens) {
        return new DecisionModel.Usage(calls, inputTokens, outputTokens, new BigDecimal("0.00"));
    }

    private static DecisionModel.Config config(int queueCapacity, int maxInFlight,
                                               long wallTimeoutMillis, int maxAttempts) {
        Map<String, Long> action = new TreeMap<>();
        for (String tool : List.of(RoleTradingView.TOOL_READING_START,
                RoleTradingView.TOOL_READING_CANCEL, RoleTradingView.TOOL_RESEARCH_SUBMIT,
                RoleTradingView.TOOL_TARGET_SET, RoleTradingView.TOOL_TARGET_REVIEW,
                RoleTradingView.TOOL_TARGET_HALT, RoleTradingView.TOOL_TARGET_CONFIRM,
                RoleTradingView.TOOL_TARGET_CANCEL, RoleTradingView.TOOL_CHILD_SUBMIT,
                RoleTradingView.TOOL_CHILD_CANCEL)) {
            action.put(tool, ACTION);
        }
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        for (String person : List.of(ALICE, BOB, CAROL, DAVE, ERIN)) {
            caps.put(person, usage(20, 2_400, 1_200));
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted-runner-v1",
                "scripted-zero-v1", Map.of(WAKE, THINK), action,
                new DecisionModel.Limits(maxAttempts, 20_000, 4_000, 120, 60, queueCapacity,
                        maxInFlight, wallTimeoutMillis),
                new DecisionModel.Budget(usage(100, 12_000, 6_000), caps));
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions")) {
            modules.put(name, 1);
        }
        return new RunManifest(RUN, SEED, RunManifest.RULES_DECISION_EXPERIMENT_V1, modules,
                Map.of("fixture", "scripted-decisions-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount(ACC_FUND, PRODUCT, new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount(ACC_FUND_G, PRODUCT_G, new BigDecimal("500.00"), "实验开局历史禀赋"),
            new OpeningAccount(ACC_INV, INV, new BigDecimal("0.00"), "实验开局历史禀赋"),
            new OpeningAccount("CO", "company", new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
            new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));

    private static RoleRun.Config roles() {
        List<RoleAccess.ProductMandate> mandates = List.of(
                new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND)),
                new RoleAccess.ProductMandate(ORG_G, PRODUCT_G, List.of(ACC_FUND_G)));
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("A-1", ALICE, ORG_F, Role.RESEARCHER, 0L, END, null),
                new RoleAccess.Appointment("A-2", BOB, ORG_F, Role.PORTFOLIO_MANAGER, 0L, END,
                        null),
                new RoleAccess.Appointment("A-2G", BOB, ORG_G, Role.PORTFOLIO_MANAGER, 0L, END,
                        null),
                new RoleAccess.Appointment("A-3", CAROL, ORG_F, Role.RISK_OFFICER, 0L, END, null),
                new RoleAccess.Appointment("A-4", DAVE, ORG_F, Role.EXECUTION_TRADER, 0L, END,
                        null)));
        return new RoleRun.Config(mandates, appointments);
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

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(ORG_F, PartyKind.ORGANIZATION, "中央财经资管"),
                new OpeningRegistry.Party(ORG_G, PartyKind.ORGANIZATION, "外部机构"),
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(PRODUCT, OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner(PRODUCT_G, OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner("orgf", OwnerKind.ORGANIZATION, ORG_F),
                new OpeningRegistry.PropertyOwner("orgg", OwnerKind.ORGANIZATION, ORG_G),
                new OpeningRegistry.PropertyOwner(ALICE, OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV, OwnerKind.PERSON, INV),
                new OpeningRegistry.PropertyOwner("company", OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops", OwnerKind.AGGREGATE, null));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYM, "cufe", 200L, new BigDecimal("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
