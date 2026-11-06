package edu.cufe.auction.app;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RoleTradingModel;
import edu.cufe.auction.world.RoleTradingView;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.ScriptedDecisionRunner;
import edu.cufe.auction.world.SecuritiesLedger;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M4d 三进程演示：{@code prepare} 留下未决定屏障，{@code full} 接续并重放已记录业务，
 * 第三次 {@code full} 证明原请求与回执都只生效一次。
 *
 * <p>三个进程共用同一个日志文件，必须按 {@code prepare → full → full} 顺序、各自独立 JVM 运行。
 * 每次 {@code full} 都显式 {@code beginDecisionSession}，因此都会落一个代次审计批次；
 * 演示分别打印"接续批次"与"业务重放"的位置，绝不用跳过 begin 来伪造位置不变。
 * 经济效果只有一次真实成交：外部卖单 20 股 + 决定子单 20 股，现金 1000.00 → 800.00。</p>
 */
public final class WorldDecisionDemo {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-decision-demo";
    private static final String SYM = "CUFE1";
    private static final long DAY = 86_400_000L;
    private static final long END = 30 * DAY;
    private static final long THINK = 1_000L;
    private static final long ACTION = 2_000L;
    private static final long READ_MILLIS = 5_000L;

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
    private static final String SRC_DOC = "src-1";
    private static final String WAIT_BODY = "{\"tool\":\"role.wait\"}";
    private static final String CHILD_BODY = "{\"tool\":\"role.child.submit\",\"args\":{"
            + "\"parentId\":\"tgt-1\",\"parentVersion\":1,\"riskRevision\":1,"
            + "\"childId\":\"c-1\",\"quantity\":20,\"limitPrice\":\"10.00\"}}";

    private WorldDecisionDemo() {
    }

    /**
     * 演示入口。
     *
     * @param args {@code prepare|full <journal>}
     * @throws Exception 运行或持久化失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：WorldDecisionDemo prepare|full <journal>");
        }
        String mode = args[0];
        Path journal = Path.of(args[1]);
        switch (mode) {
            case "prepare" -> prepare(journal);
            case "full" -> full(journal);
            default -> throw new IllegalArgumentException("未知模式：" + mode);
        }
    }

    private static void prepare(Path journal) throws IOException {
        if (Files.exists(journal)) {
            throw new IllegalArgumentException("演示日志必须从不存在开始：" + journal);
        }
        try (WorldRuntime runtime = open(journal)) {
            runtime.beginDecisionSession("demo-begin-prepare");
            establishTradableTarget(runtime);
            printConfig(runtime);
            say("[demo] prepare position=" + runtime.snapshot().position()
                    + " time=" + runtime.snapshot().timeMillis());
            String opened = runtime.openDecisions("demo-open-prepare", List.of(
                    new DecisionRun.Request(DAVE, "demo-child", WAKE),
                    new DecisionRun.Request(ALICE, "demo-wait", WAKE)));
            DecisionRun.Barrier barrier = runtime.pendingBarrier().orElseThrow();
            say("[demo] prepare opened=" + opened
                    + " barrier=" + barrier.timeMillis() + " actions=" + barrier.actionIds());
            say("[demo] prepare 留下未决定屏障，等待下一进程显式接续");
        }
    }

    private static void full(Path journal) throws IOException {
        if (!Files.exists(journal)) {
            throw new IllegalArgumentException("演示日志不存在，必须先运行 prepare：" + journal);
        }
        try (WorldRuntime runtime = open(journal)) {
            long before = runtime.snapshot().position();
            String session = runtime.beginDecisionSession("demo-begin-full-"
                    + runtime.snapshot().position());
            long afterBegin = runtime.snapshot().position();
            say("[demo] begin=" + session + " 接续批次 position "
                    + before + " -> " + afterBegin + "（代次审计批次，不是业务重放）");
            printUsage(runtime);

            String replay;
            DecisionModel.Decision child = DecisionModel.decision(state(runtime), "demo-child")
                    .orElseThrow();
            if (child.status() == DecisionModel.Status.OPEN) {
                ScriptedDecisionRunner runner = new ScriptedDecisionRunner(runtime, Map.of(
                        "demo-child", new ScriptedDecisionRunner.ScriptedReply(CHILD_BODY,
                                usage(1, 100, 40), "scripted:demo-v1", 0L),
                        "demo-wait", new ScriptedDecisionRunner.ScriptedReply(WAIT_BODY,
                                usage(1, 20, 5), "scripted:demo-v1", 0L)));
                say("[demo] deliver child=" + runner.deliver("demo-child")
                        + " wait=" + runner.deliver("demo-wait")
                        + " drain=" + runtime.drainDecisionCallbacks());
                long due = DecisionModel.actionDueMillis(state(runtime), "demo-child");
                long replayStart = runtime.snapshot().position();
                runtime.advanceTo(due);
                say("[demo] 业务重放 position " + replayStart + " -> "
                        + runtime.snapshot().position() + "（同一批决定动作完成）");
                replay = runtime.openDecisions("demo-open-prepare",
                        List.of(new DecisionRun.Request(DAVE, "demo-child", WAKE),
                                new DecisionRun.Request(ALICE, "demo-wait", WAKE)));
                say("[demo] 原宿主请求重发 -> " + replay
                        + "（已记录决定不重问、不重投影）");
            } else {
                DecisionModel.Reply recorded = recordedReceipt(runtime, child);
                long position = runtime.snapshot().position();
                String archived = runtime.acceptDecisionReply("demo-resend-" + position, recorded);
                say("[demo] 重发已记录回执 -> " + archived + "，position " + position + " -> "
                        + runtime.snapshot().position()
                        + "（旧代次只归档一个审计批次，不结算、不改钱股）");
                replay = runtime.openDecisions("demo-open-prepare", List.of(
                        new DecisionRun.Request(DAVE, "demo-child", WAKE),
                        new DecisionRun.Request(ALICE, "demo-wait", WAKE)));
                say("[demo] 原宿主请求重发 -> " + replay
                        + "（已记录决定不重问、不重投影）");
                runtime.advanceTo(runtime.snapshot().timeMillis());
            }
            printEconomy(runtime);
        }
    }


    /** 从已记录决定重建一份"重放回执"：正文与声明用量都取原记录，不新造内容。 */
    private static DecisionModel.Reply recordedReceipt(WorldRuntime runtime,
                                                       DecisionModel.Decision decision) {
        DecisionModel.Attempt attempt = decision.lastAttempt();
        return new DecisionModel.Reply(state(runtime).runId(), decision.actionId(),
                attempt.attemptId(), attempt.attemptNo(), attempt.recoveryGeneration(),
                attempt.replyBody(), attempt.declaredUsage(), attempt.usageSource());
    }


    /** 立即刷新的演示输出：Maven 管道下不刷新会丢最后几行。 */
    private static void say(String text) {
        System.out.println(text);
        System.out.flush();
    }

    private static DecisionModel.State state(WorldRuntime runtime) {
        return runtime.snapshot().decisions().orElseThrow().state();
    }

    private static void printConfig(WorldRuntime runtime) {
        DecisionModel.Config config = state(runtime).config();
        say("[demo] provider=" + config.providerKind() + "/" + config.providerId()
                + " pricing=" + config.pricingVersion());
        say("[demo] limits=" + config.limits());
        say("[demo] budget.run=" + config.budget().runCap()
                + " think=" + config.thinkMillisByWake() + " cost=0.00");
    }

    private static void printUsage(WorldRuntime runtime) {
        DecisionModel.State model = state(runtime);
        say("[demo] generation=" + model.recoveryGeneration()
                + " usage.run=" + model.runUsage() + " usage.dave="
                + model.personUsage().getOrDefault(DAVE, DecisionModel.Usage.zero())
                + " headroom.dave=" + DecisionModel.headroom(model, DAVE));
    }

    private static void printEconomy(WorldRuntime runtime) {
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        WorldExchange.State exchange = snapshot.exchange().orElseThrow();
        SecuritiesLedger.State securities = snapshot.securities().orElseThrow();
        say("[demo] position=" + snapshot.position()
                + " time=" + snapshot.timeMillis()
                + " orders=" + exchange.orders().size()
                + " trades=" + exchange.trades().size()
                + " fundCash=" + snapshot.accounts().get(ACC_FUND).cash()
                + " fundHeld=" + RoleTradingModel.held(securities, ACC_FUND, SYM)
                + " reports=" + snapshot.trading().orElseThrow().reports().size());
        long cents = snapshot.accounts().get(ACC_FUND).cash().movePointRight(2).longValueExact();
        long held = RoleTradingModel.held(securities, ACC_FUND, SYM);
        boolean once = exchange.trades().size() == 1 && cents == 800_00L && held == 20L;
        say("[demo] 一次性经济效果=" + once + "（期望现金 800.00、持仓 20、成交 1；"
                + "实际 " + snapshot.accounts().get(ACC_FUND).cash() + "、" + held + "、"
                + exchange.trades().size() + "）");
    }

    /** 建立可交易目标并放一张外部卖单：与测试夹具同一套真实链路，不用 Mock 账。 */
    private static void establishTradableTarget(WorldRuntime runtime) throws IOException {
        runtime.schedulePublishDocument("c-src", "t-src", 500L, SRC_DOC, ALICE,
                InformationModel.Audience.organization(ORG_F), "订单线索",
                "渠道反馈订单改善，金额待证实。", "调研纪要", READ_MILLIS,
                List.of("internal-src"));
        runtime.advanceTo(500L);
        runtime.openRoleSession(ALICE).startReading("c-r1", "t-r1", 1_000L, SRC_DOC, 1L);
        runtime.advanceTo(1_000L);
        runtime.advanceTo(1_000L + READ_MILLIS);
        runtime.openRoleSession(ALICE).submitResearch("c-res", "t-res", 62_000L,
                new WorldRoleSession.ResearchDraft("res-1", ORG_F, PRODUCT, SYM, "note-1",
                        "S 公司订单线索", "渠道反馈订单改善，交付需持续核对。",
                        List.of(new WorldRoleSession.Citation(SRC_DOC, 1L))));
        runtime.advanceTo(62_000L);
        runtime.openRoleSession(BOB).startReading("c-r2", "t-r2", 63_000L, "note-1", 1L);
        runtime.advanceTo(63_000L);
        runtime.advanceTo(63_000L + READ_MILLIS);
        runtime.openRoleSession(BOB).setTarget("c-tgt", "t-tgt", 124_000L,
                new WorldRoleSession.TargetDraft("tgt-1", 0, ORG_F, PRODUCT, ACC_FUND, SYM,
                        100L, price("10.00"), 3 * DAY, "res-1"));
        runtime.advanceTo(124_000L);
        runtime.openRoleSession(CAROL).reviewTarget("c-rev", "t-rev", 125_000L,
                new WorldRoleSession.ReviewDraft("tgt-1", 1, true, 100L, 40L, price("10.00"),
                        3 * DAY, "实验任务输入"));
        runtime.advanceTo(125_000L);
        runtime.openRoleSession(BOB).confirmTarget("c-cfm", "t-cfm", 126_000L, "tgt-1", 1, 1);
        runtime.advanceTo(126_000L);
        runtime.scheduleOrder("o-sell", "ot-sell", 126_500L, INV, ACC_INV, SYM, Side.SELL,
                OrderType.LIMIT, price("10.00"), 20L);
        runtime.advanceTo(126_500L);
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static DecisionModel.Usage usage(long calls, long input, long output) {
        return new DecisionModel.Usage(calls, input, output, new BigDecimal("0.00"));
    }

    private static DecisionModel.Config decisionConfig() {
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
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted-demo-v1",
                "scripted-zero-v1", Map.of(WAKE, THINK), action,
                new DecisionModel.Limits(3, 20_000, 4_000, 120, 60, 4, 2, 60_000L),
                new DecisionModel.Budget(usage(100, 12_000, 6_000), caps));
    }

    private static WorldRuntime open(Path file) throws IOException {
        return new WorldRuntime(file, manifest(), OPENINGS, registry(), MFG, charter(), roles(),
                info(), decisionConfig());
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions")) {
            modules.put(name, 1);
        }
        return new RunManifest(RUN, SEED, RunManifest.RULES_DECISION_EXPERIMENT_V1, modules,
                Map.of("fixture", "decision-demo-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
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
        return new RoleRun.Config(List.of(
                new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND)),
                new RoleAccess.ProductMandate(ORG_G, PRODUCT_G, List.of(ACC_FUND_G))),
                List.of(
                        new RoleAccess.Appointment("A-1", ALICE, ORG_F, RoleAccess.Role.RESEARCHER,
                                0L, END, null),
                        new RoleAccess.Appointment("A-2", BOB, ORG_F,
                                RoleAccess.Role.PORTFOLIO_MANAGER, 0L, END, null),
                        new RoleAccess.Appointment("A-2G", BOB, ORG_G,
                                RoleAccess.Role.PORTFOLIO_MANAGER, 0L, END, null),
                        new RoleAccess.Appointment("A-3", CAROL, ORG_F,
                                RoleAccess.Role.RISK_OFFICER, 0L, END, null),
                        new RoleAccess.Appointment("A-4", DAVE, ORG_F,
                                RoleAccess.Role.EXECUTION_TRADER, 0L, END, null)));
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
                new OpeningRegistry.Party(ORG_F, OpeningRegistry.PartyKind.ORGANIZATION,
                        "中央财经资管"),
                new OpeningRegistry.Party(ORG_G, OpeningRegistry.PartyKind.ORGANIZATION, "外部机构"),
                new OpeningRegistry.Party("cufe", OpeningRegistry.PartyKind.ORGANIZATION,
                        "中央财经制造公司"),
                new OpeningRegistry.Party(ALICE, OpeningRegistry.PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, OpeningRegistry.PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, OpeningRegistry.PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, OpeningRegistry.PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, OpeningRegistry.PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV, OpeningRegistry.PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(PRODUCT, OpeningRegistry.OwnerKind.PRODUCT,
                        null),
                new OpeningRegistry.PropertyOwner(PRODUCT_G, OpeningRegistry.OwnerKind.PRODUCT,
                        null),
                new OpeningRegistry.PropertyOwner("orgf", OpeningRegistry.OwnerKind.ORGANIZATION,
                        ORG_F),
                new OpeningRegistry.PropertyOwner("orgg", OpeningRegistry.OwnerKind.ORGANIZATION,
                        ORG_G),
                new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OpeningRegistry.OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV, OpeningRegistry.OwnerKind.PERSON, INV),
                new OpeningRegistry.PropertyOwner("company",
                        OpeningRegistry.OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("customer", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE, null));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYM, "cufe", 200L, new BigDecimal("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
