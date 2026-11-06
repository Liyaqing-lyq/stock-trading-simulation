package edu.cufe.auction.app;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RoleTradingRun;
import edu.cufe.auction.world.RoleTradingView;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 四岗交易运行桥的正式可复跑演示：三个独立 JVM，不用测试框架，也不写课程 {@code data} 目录。
 *
 * <p>用法：{@code WorldRoleTradingDemo <dir> [prepare|full]}；{@code dir} 省略时使用
 * {@code logs/world-role-trading-demo}，模式省略按 {@code full}。</p>
 *
 * <ul>
 *   <li>{@code prepare}：研究员真实读完源资料并发表组织报告、经理读完报告设目标、风控审核、
 *       经理确认、执行员子单部分成交后暂停——退出时磁盘上留下活动工作流、活动子单与待办。</li>
 *   <li>第 1 次 {@code full}：恢复、暂停到期回待审、重新审核确认、按真实持仓更新目标、
 *       系统撤旧单、撤净后重建基点，再下新子单成交并打印真实现金/股份/额度。</li>
 *   <li>第 2 次 {@code full}：同一日志再次启动，重发最后一条命令并推进——位置与成交都不重复。</li>
 * </ul>
 *
 * <p>所有数字由唯一世界日志、纯核与真实撮合算出；现金、股份、成交、额度全部现读，不写死示例值。</p>
 */
public final class WorldRoleTradingDemo {

    private static final String RUN = "world-role-trading-demo";
    private static final long SEED = 20261106L;
    private static final String SYMBOL = "CUFE1";
    private static final long MINUTE = 60_000L;
    private static final long DAY = ManufacturingModel.DAY_MILLIS;

    private static final String ORG = "ORG-F";
    private static final String PRODUCT = "fund-a";
    private static final String FUND = "FUND";
    private static final String INVESTOR = "INV";
    private static final String INVESTOR_PARTY = "investor";

    private static final String AMY = "amy";
    private static final String BOB = "bob";
    private static final String CARA = "cara";
    private static final String DAN = "dan";

    private static final String TARGET = "tgt-demo";
    private static final String RESEARCH = "res-demo";
    private static final String SRC_DOC = "src-demo";
    private static final String NOTE_DOC = "note-demo";

    private static final long DEADLINE = 5 * DAY;

    private WorldRoleTradingDemo() {
    }

    /**
     * 入口。
     *
     * @param args {@code <dir> [prepare|full]}
     * @throws Exception 运行失败
     */
    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Path.of(args[0]) : Path.of("logs", "world-role-trading-demo");
        String mode = args.length > 1 ? args[1] : "full";
        if (!"prepare".equals(mode) && !"full".equals(mode)) {
            System.out.println("[WorldRoleTradingDemo] 用法: <dir> [prepare|full]");
            return;
        }
        Files.createDirectories(dir);
        Path file = dir.resolve("trading.journal");
        System.out.println("[WorldRoleTradingDemo] mode=" + mode + " journal=" + file);

        try (WorldRuntime runtime = new WorldRuntime(file, manifest(), openings(), registry(),
                MFG, charter(), roles(), info())) {
            long positionBefore = runtime.snapshot().position();
            int tradesBefore = runtime.snapshot().exchange().orElseThrow().trades().size();
            prepare(runtime);
            if ("full".equals(mode)) {
                full(runtime);
            }
            System.out.println("[WorldRoleTradingDemo] position " + positionBefore + " -> "
                    + runtime.snapshot().position() + " trades " + tradesBefore + " -> "
                    + runtime.snapshot().exchange().orElseThrow().trades().size());
            report(runtime, mode);
        }
    }

    private static void prepare(WorldRuntime runtime) throws Exception {
        long t0 = DAY;
        runtime.schedulePublishDocument("c-src", "t-src", t0 + MINUTE, SRC_DOC, AMY,
                InformationModel.Audience.organization(ORG), "渠道订单线索",
                "渠道反馈订单改善，金额待核实；交付节奏仍需逐单确认。", "调研纪要", 60_000L,
                List.of("internal-src"));
        advance(runtime, t0 + MINUTE);

        session(runtime, AMY).startReading("c-r1", "t-r1", t0 + 2 * MINUTE, SRC_DOC, 1L);
        advance(runtime, t0 + 2 * MINUTE);
        advance(runtime, t0 + 62 * MINUTE);

        session(runtime, AMY).submitResearch("c-res", "t-res", t0 + 63 * MINUTE,
                new WorldRoleSession.ResearchDraft(RESEARCH, ORG, PRODUCT, SYMBOL, NOTE_DOC,
                        "S 公司订单线索", "订单改善但金额待证实，建议小步建仓。",
                        List.of(new WorldRoleSession.Citation(SRC_DOC, 1L))));
        advance(runtime, t0 + 63 * MINUTE);

        session(runtime, BOB).startReading("c-r2", "t-r2", t0 + 64 * MINUTE, NOTE_DOC, 1L);
        advance(runtime, t0 + 64 * MINUTE);
        advance(runtime, t0 + 124 * MINUTE);

        session(runtime, BOB).setTarget("c-tgt", "t-tgt", t0 + 125 * MINUTE,
                new WorldRoleSession.TargetDraft(TARGET, 0, ORG, PRODUCT, FUND, SYMBOL, 100L,
                        money("10.00"), t0 + DEADLINE, RESEARCH));
        advance(runtime, t0 + 125 * MINUTE);

        session(runtime, CARA).reviewTarget("c-rev", "t-rev", t0 + 126 * MINUTE,
                new WorldRoleSession.ReviewDraft(TARGET, 1, true, 100L, 40L, money("10.00"),
                        t0 + 4 * DAY, "实验任务输入"));
        advance(runtime, t0 + 126 * MINUTE);
        session(runtime, BOB).confirmTarget("c-cfm", "t-cfm", t0 + 127 * MINUTE, TARGET, 1, 1);
        advance(runtime, t0 + 127 * MINUTE);

        runtime.scheduleOrder("c-opp", "t-opp", t0 + 128 * MINUTE, INVESTOR_PARTY, INVESTOR,
                SYMBOL, edu.cufe.auction.model.Side.SELL, edu.cufe.auction.model.OrderType.LIMIT,
                money("10.00"), 20L);
        advance(runtime, t0 + 128 * MINUTE);
        session(runtime, DAN).submitChild("c-child1", "t-child1", t0 + 129 * MINUTE,
                new WorldRoleSession.ChildDraft(TARGET, 1, 1, "ch1", 40L, money("10.00")));
        advance(runtime, t0 + 129 * MINUTE);

        // 风控暂停：停止新单，系统在下一个 RULES 真实撤单
        session(runtime, CARA).haltTarget("c-halt", "t-halt", t0 + 130 * MINUTE,
                new WorldRoleSession.HaltDraft("halt-demo", TARGET, 1, t0 + 2 * DAY, "复盘暂停"));
        advance(runtime, t0 + 130 * MINUTE);
    }

    private static void full(WorldRuntime runtime) throws Exception {
        long resume = 3 * DAY;
        advance(runtime, DAY + 130 * MINUTE + 1);
        advance(runtime, resume);
        session(runtime, CARA).reviewTarget("c-rev2", "t-rev2", resume + MINUTE,
                new WorldRoleSession.ReviewDraft(TARGET, 1, true, 100L, 40L,
                        money("10.00"), 5 * DAY, "恢复复核"));
        advance(runtime, resume + MINUTE);
        session(runtime, BOB).confirmTarget("c-cfm2", "t-cfm2", resume + 2 * MINUTE,
                TARGET, 1, 2);
        advance(runtime, resume + 2 * MINUTE);

        session(runtime, BOB).setTarget("c-up", "t-up", resume + 3 * MINUTE,
                new WorldRoleSession.TargetDraft(TARGET, 1, ORG, PRODUCT, FUND, SYMBOL, 60L,
                        money("10.00"), 6 * DAY, RESEARCH));
        advance(runtime, resume + 3 * MINUTE + 1);
        session(runtime, CARA).reviewTarget("c-rev3", "t-rev3", resume + 4 * MINUTE,
                new WorldRoleSession.ReviewDraft(TARGET, 2, true, 40L, 40L,
                        money("9.00"), 5 * DAY, "执行新基点"));
        advance(runtime, resume + 4 * MINUTE);
        session(runtime, BOB).confirmTarget("c-cfm3", "t-cfm3", resume + 5 * MINUTE,
                TARGET, 2, 1);
        advance(runtime, resume + 5 * MINUTE);
        runtime.scheduleOrder("c-opp2", "t-opp2", resume + 6 * MINUTE, INVESTOR_PARTY,
                INVESTOR, SYMBOL, edu.cufe.auction.model.Side.SELL,
                edu.cufe.auction.model.OrderType.LIMIT, money("9.00"), 40L);
        advance(runtime, resume + 6 * MINUTE);
        String result = session(runtime, DAN).submitChild("c-child2", "t-child2", resume + 7 * MINUTE,
                new WorldRoleSession.ChildDraft(TARGET, 2, 1, "ch2", 40L, money("9.00")));
        advance(runtime, resume + 7 * MINUTE);
        System.out.println("[WorldRoleTradingDemo] final-command=" + result);
    }

    /** 固定计划重发时仅向前推进，完整原命令仍逐条走正常幂等入口。 */
    private static void advance(WorldRuntime runtime, long target) throws Exception {
        if (runtime.snapshot().timeMillis() < target) {
            runtime.advanceTo(target);
        }
    }

    private static WorldRoleSession session(WorldRuntime runtime, String person) throws Exception {
        return runtime.openRoleSession(person);
    }

    private static void report(WorldRuntime runtime, String mode) throws Exception {
        System.out.println("[WorldRoleTradingDemo] mode=" + mode
                + " time=" + runtime.snapshot().timeMillis()
                + " position=" + runtime.snapshot().position());
        for (Map.Entry<String, CashLedger.CashAccount> entry
                : runtime.snapshot().accounts().entrySet()) {
            BigDecimal cash = entry.getValue().cash();
            long frozen = 0L;
            if (runtime.snapshot().exchange().isPresent()) {
                frozen = WorldExchange.frozenCash(runtime.snapshot().exchange().orElseThrow(),
                        entry.getKey()).movePointRight(2).longValueExact();
            }
            System.out.println("  account " + entry.getKey() + " owner="
                    + entry.getValue().ownerId() + " cash=" + cash + " frozenCents=" + frozen);
        }
        long held = 0L;
        if (runtime.snapshot().securities().isPresent()) {
            held = edu.cufe.auction.world.RoleTradingModel.held(
                    runtime.snapshot().securities().orElseThrow(), FUND, SYMBOL);
        }
        System.out.println("  fund holding " + held + " " + SYMBOL);
        if (runtime.snapshot().exchange().isPresent()) {
            WorldExchange.State exchange = runtime.snapshot().exchange().orElseThrow();
            System.out.println("  exchange orders=" + exchange.orders().size()
                    + " trades=" + exchange.trades().size());
        }
        RoleTradingRun run = runtime.snapshot().trading().orElseThrow();
        for (RoleTradingView.TargetView target : session(runtime, BOB).view().targets()) {
            System.out.println("  target " + target.targetId() + " status=" + target.status()
                    + " version=" + target.current().version()
                    + " base=" + target.current().baseQuantity()
                    + " change=" + target.current().changeQuantity()
                    + " filled=" + target.filled() + " leaves=" + target.leaves()
                    + " remaining=" + target.remaining()
                    + " side=" + target.current().side()
                    + " review=" + (target.review() == null ? "-" : target.review().revision()
                    + "/" + target.review().approved()));
        }
        for (RoleTradingView.ChildView child : session(runtime, DAN).view().children()) {
            System.out.println("  child " + child.ref().childId() + " status=" + child.status()
                    + " qty=" + child.quantity() + " filled=" + child.filled()
                    + " leaves=" + child.leaves());
        }
        System.out.println("  reports=" + run.reports().size() + " pending="
                + runtime.snapshot().pending().size());
        for (RoleTradingView.Notice notice : session(runtime, DAN).view().notices()) {
            System.out.println("  notice " + notice.clientTaskId() + " " + notice.kind() + " "
                    + (notice.accepted() ? "OK" : notice.reason()));
        }
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading")) {
            modules.put(name, 1);
        }
        return new RunManifest(RUN, SEED, RunManifest.RULES_ROLE_TRADING_EXPERIMENT_V1, modules,
                Map.of("fixture", "role-trading-demo-v1"), RunManifest.MODEL_NONE);
    }

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYMBOL);

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount(FUND, PRODUCT, new BigDecimal("1000.00"), "演示开局历史禀赋"),
                new OpeningAccount(INVESTOR, INVESTOR_PARTY, new BigDecimal("0.00"), "演示开局历史禀赋"),
                new OpeningAccount("CO", "company", new BigDecimal("1000.00"), "演示开局历史禀赋"),
                new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "演示开局历史禀赋"),
                new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "演示开局历史禀赋"),
                new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "演示开局历史禀赋"));
    }

    private static RoleRun.Config roles() {
        long end = 60 * DAY;
        List<RoleAccess.ProductMandate> mandates = List.of(
                new RoleAccess.ProductMandate(ORG, PRODUCT, List.of(FUND)));
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("A-1", AMY, ORG, RoleAccess.Role.RESEARCHER, 0L, end, null),
                new RoleAccess.Appointment("A-2", BOB, ORG, RoleAccess.Role.PORTFOLIO_MANAGER, 0L,
                        end, null),
                new RoleAccess.Appointment("A-3", CARA, ORG, RoleAccess.Role.RISK_OFFICER, 0L, end,
                        null),
                new RoleAccess.Appointment("A-4", DAN, ORG, RoleAccess.Role.EXECUTION_TRADER, 0L,
                        end, null)));
        return new RoleRun.Config(mandates, appointments);
    }

    private static InformationRun.Config info() {
        return new InformationRun.Config(List.of(AMY, BOB, CARA, DAN));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter("cufe", List.of(
                new BoardModel.Seat("s1", AMY), new BoardModel.Seat("s2", BOB),
                new BoardModel.Seat("s3", CARA), new BoardModel.Seat("s4", DAN),
                new BoardModel.Seat("s5", "eve")), "s1", BoardModel.QUORUM);
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(ORG, OpeningRegistry.PartyKind.ORGANIZATION, "中央财经资管"),
                new OpeningRegistry.Party("cufe", OpeningRegistry.PartyKind.ORGANIZATION,
                        "中央财经制造公司"),
                new OpeningRegistry.Party(AMY, OpeningRegistry.PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, OpeningRegistry.PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CARA, OpeningRegistry.PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAN, OpeningRegistry.PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party("eve", OpeningRegistry.PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INVESTOR_PARTY, OpeningRegistry.PartyKind.PERSON,
                        "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(PRODUCT, OpeningRegistry.OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner("orgf", OpeningRegistry.OwnerKind.ORGANIZATION,
                        ORG),
                new OpeningRegistry.PropertyOwner(AMY, OpeningRegistry.OwnerKind.PERSON, AMY),
                new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CARA, OpeningRegistry.OwnerKind.PERSON, CARA),
                new OpeningRegistry.PropertyOwner(DAN, OpeningRegistry.OwnerKind.PERSON, DAN),
                new OpeningRegistry.PropertyOwner(INVESTOR_PARTY,
                        OpeningRegistry.OwnerKind.PERSON, INVESTOR_PARTY),
                new OpeningRegistry.PropertyOwner("company",
                        OpeningRegistry.OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier",
                        OpeningRegistry.OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer",
                        OpeningRegistry.OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE, null));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYMBOL, "cufe", 200L, new BigDecimal("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(INVESTOR, SYMBOL, 200L,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
