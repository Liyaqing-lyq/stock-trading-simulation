package edu.cufe.auction.app;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 任职与实际阅读接入唯一世界日志的正式可复跑演示：不需要测试框架，也不写课程 {@code data} 目录。
 *
 * <p>用法：{@code WorldRoleReadingDemo <dir> [prepare|full]}。{@code dir} 省略时使用
 * {@code logs/world-role-reading-demo}；模式省略时按 {@code full} 恢复。</p>
 *
 * <ul>
 *   <li>{@code prepare}：第 1 日发表组织资料与公共资料，第 1 日 10:00 由研究员开始阅读 90 分钟
 *       组织资料，随后退出——磁盘上留下活动阅读与唯一自动完成待办。</li>
 *   <li>{@code full}：恢复同一日志，到期完成 v1（即使中途发表了 v2）、让继任者读到一半被撤销而
 *       DENIED、保留研究员已完成的旧经历、新人不克隆经历；末段以完全相同的命令重发并再推进，
 *       验证不重复经历。</li>
 * </ul>
 *
 * <p>这是 {@code model=none} 的确定性夹具：没有真实模型、没有网络、没有角色下单/改目标/风险暂停
 * 工具，也没有新世界 GUI。所有数字由唯一世界日志与纯核实际算出，现金总额直接读账户表，
 * 不硬编码任何“示例余额”。</p>
 */
public final class WorldRoleReadingDemo {

    private static final String RUN = "world-role-reading-demo";
    private static final long SEED = 20261106L;
    private static final String SYMBOL = "CUFE1";
    private static final long DAY = ManufacturingModel.DAY_MILLIS;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    private static final String OWNER = "company";
    private static final String COMPANY_ACCOUNT = "CO";
    private static final String SUPPLIER = "SUP";
    private static final String CUSTOMER = "CUS";
    private static final String OPS = "OPS";
    private static final String INVESTOR = "INV";
    private static final String FUND_ORG = "ORG-F";
    private static final String FUND_PRODUCT = "fund-a";
    private static final String FUND_ACCOUNT = "FUND";
    private static final long APPOINTMENT_END = 30 * DAY;
    /** 研究员的任期在第 1 日 3 小时结束（不含），恰好由继任者接任。 */
    private static final long RESEARCHER_END = 1 * DAY + 3 * HOUR;

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening(OWNER, COMPANY_ACCOUNT, SUPPLIER, CUSTOMER, OPS), SYMBOL);

    private static final List<String> READERS = List.of("alice", "bob", "carol", "dave",
            "erin", "grace");

    private static final RoleRun.Config ROLES = new RoleRun.Config(
            List.of(new RoleAccess.ProductMandate(FUND_ORG, FUND_PRODUCT,
                    List.of(FUND_ACCOUNT))),
            List.of(new RoleAccess.Appointment("demo-res", "alice", FUND_ORG,
                            RoleAccess.Role.RESEARCHER, 0L, RESEARCHER_END, null),
                    new RoleAccess.Appointment("demo-pm", "bob", FUND_ORG,
                            RoleAccess.Role.PORTFOLIO_MANAGER, 0L, APPOINTMENT_END, null),
                    new RoleAccess.Appointment("demo-risk", "carol", FUND_ORG,
                            RoleAccess.Role.RISK_OFFICER, 0L, APPOINTMENT_END, null),
                    new RoleAccess.Appointment("demo-trader", "dave", FUND_ORG,
                            RoleAccess.Role.EXECUTION_TRADER, 0L, APPOINTMENT_END, null)));

    private static final InformationRun.Config INFO = new InformationRun.Config(READERS);

    private WorldRoleReadingDemo() {
    }

    /**
     * 运行演示。
     *
     * @param args 可选目录与模式（{@code prepare} / {@code full}）
     * @throws Exception 日志不可用或世界状态校验失败
     */
    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Path.of(args[0])
                : Path.of("logs", "world-role-reading-demo");
        String mode = args.length > 1 ? args[1] : "full";
        if (!"prepare".equals(mode) && !"full".equals(mode)) {
            System.out.println("[WorldRoleReadingDemo] 用法: WorldRoleReadingDemo <dir> "
                    + "[prepare|full]");
            return;
        }
        Files.createDirectories(dir);
        Path file = dir.resolve("company.journal");
        System.out.println("[WorldRoleReadingDemo] mode=" + mode + " journal=" + file);

        try (WorldRuntime runtime = new WorldRuntime(file, manifest(), openings(), registry(),
                MFG, charter(), ROLES, INFO)) {
            long publishOrg = 1 * DAY;
            long publishPublic = 1 * DAY + MINUTE;
            long startFirst = 1 * DAY + HOUR;
            long firstDue = startFirst + 90 * MINUTE;
            long secondPublish = 1 * DAY + 2 * HOUR;
            long appointAt = 1 * DAY + 3 * HOUR;
            long secondStart = 1 * DAY + 3 * HOUR + MINUTE;
            long revokeAt = 1 * DAY + 3 * HOUR + 30 * MINUTE;
            long secondDue = secondStart + 90 * MINUTE;
            long end = 1 * DAY + 5 * HOUR;

            schedulePlan(runtime, publishOrg, publishPublic, startFirst, secondPublish,
                    appointAt, secondStart, revokeAt);
            if ("prepare".equals(mode)) {
                advance(runtime, startFirst);
                System.out.println("[WorldRoleReadingDemo] prepare 退出于 time="
                        + runtime.snapshot().timeMillis() + "，活动阅读与自动完成待办已持久");
                report(runtime, mode, firstDue, secondDue);
                return;
            }
            advance(runtime, firstDue);
            advance(runtime, end);
            // 完全相同的外部命令重发：返回原 SCHEDULED，不重复经历
            String replay = runtime.schedulePublishDocumentVersion("demo:publish-v2",
                    "demo-t-publish-v2", secondPublish, "demo-policy", "组织资料 v2",
                    "第二版正文（不替代已完成的 v1）", "演示公开来源", 90 * MINUTE,
                    List.of("demo-source-v2"));
            long before = runtime.snapshot().position();
            advance(runtime, end);
            System.out.println("[WorldRoleReadingDemo] replay " + replay + " position "
                    + before + " -> " + runtime.snapshot().position());
            report(runtime, mode, firstDue, secondDue);
        }
    }

    /** 只向前推进：重复运行 full 时不会因时间倒退而失败。 */
    private static void advance(WorldRuntime runtime, long target) throws Exception {
        if (runtime.snapshot().timeMillis() < target) {
            runtime.advanceTo(target);
        }
    }

    /** 只排一次计划；已有运行重发同一命令走幂等路径。 */
    private static void schedulePlan(WorldRuntime runtime, long publishOrg, long publishPublic,
                                     long startFirst, long secondPublish, long appointAt,
                                     long secondStart, long revokeAt) throws Exception {
        System.out.println("[WorldRoleReadingDemo] " + runtime.schedulePublishDocument(
                "demo:publish-org", "demo-t-publish-org", publishOrg, "demo-policy", "grace",
                InformationModel.Audience.organization(FUND_ORG), "组织资料 v1",
                "第一版正文：岗位不得越权下单，正文只是资料。", "演示公开来源", 90 * MINUTE,
                List.of("demo-source-v1")));
        System.out.println("[WorldRoleReadingDemo] " + runtime.schedulePublishDocument(
                "demo:publish-public", "demo-t-publish-public", publishPublic, "demo-public",
                "grace", InformationModel.Audience.publicAudience(), "公共资料",
                "公共资料正文", "演示公开来源", 60 * MINUTE, List.of("demo-source-public")));
        System.out.println("[WorldRoleReadingDemo] " + runtime.scheduleStartReading(
                "demo:read-1", "demo-t-read-1", startFirst, "alice", "demo-policy", 1));
        System.out.println("[WorldRoleReadingDemo] " + runtime.schedulePublishDocumentVersion(
                "demo:publish-v2", "demo-t-publish-v2", secondPublish, "demo-policy",
                "组织资料 v2", "第二版正文（不替代已完成的 v1）", "演示公开来源", 90 * MINUTE,
                List.of("demo-source-v2")));
        System.out.println("[WorldRoleReadingDemo] " + runtime.scheduleAppointment(
                "demo:appoint-erin", "demo-t-appoint-erin", appointAt, "demo-res-2", "erin",
                FUND_ORG, RoleAccess.Role.RESEARCHER, appointAt, APPOINTMENT_END));
        System.out.println("[WorldRoleReadingDemo] " + runtime.scheduleStartReading(
                "demo:read-2", "demo-t-read-2", secondStart, "erin", "demo-policy", 2));
        System.out.println("[WorldRoleReadingDemo] " + runtime.scheduleRevokeAppointment(
                "demo:revoke-erin", "demo-t-revoke-erin", revokeAt, "demo-res-2"));
    }

    private static void report(WorldRuntime runtime, String mode, long firstDue, long secondDue) {
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        InformationModel.State information = snapshot.information().orElseThrow().information();
        RoleRun roles = snapshot.roles().orElseThrow();
        BigDecimal worldCash = BigDecimal.ZERO;
        Map<String, BigDecimal> cash = new TreeMap<>();
        snapshot.accounts().forEach((id, account) -> cash.put(id, account.cash()));
        for (BigDecimal value : cash.values()) {
            worldCash = worldCash.add(value);
        }
        int denied = 0;
        for (InformationModel.ClosedReading closed : information.closed()) {
            if (closed.reason() == InformationModel.CloseReason.DENIED) {
                denied++;
            }
        }
        System.out.println("[WorldRoleReadingDemo] mode=" + mode
                + " time=" + snapshot.timeMillis()
                + " position=" + snapshot.position()
                + " pending=" + snapshot.pending().size()
                + " infoReports=" + snapshot.information().orElseThrow().reports().size()
                + " roleReports=" + roles.reports().size()
                + " paused=" + runtime.isPaused());
        System.out.println("[WorldRoleReadingDemo] 文档=" + information.documents().size()
                + " 版本=" + versionCount(information)
                + " 活动阅读=" + information.active().size()
                + " 终态=" + information.closed().size()
                + " 其中DENIED=" + denied
                + " 经历=" + information.observations().size());
        RoleView alice = runtime.roleView("alice");
        RoleView erin = runtime.roleView("erin");
        System.out.println("[WorldRoleReadingDemo] alice 任职=" + alice.appointments().size()
                + " 经历=" + alice.observations().size()
                + " 可见账户=" + accountIds(alice)
                + " 工具=" + alice.tools());
        for (RoleView.Experience observation : alice.observations()) {
            System.out.println("[WorldRoleReadingDemo]   alice 读过 " + observation.ref().docId()
                    + " v" + observation.ref().version()
                    + " 本人第" + observation.ref().index() + "次"
                    + " 完成=" + observation.completedAt()
                    + " 正文长度=" + observation.body().length());
        }
        System.out.println("[WorldRoleReadingDemo] erin 任职=" + erin.appointments().size()
                + " 经历=" + erin.observations().size() + "（新人不克隆前任经历）");
        System.out.println("[WorldRoleReadingDemo] 引用 alice v1="
                + runtime.canCite("alice", "demo-policy", 1)
                + " v2=" + runtime.canCite("alice", "demo-policy", 2)
                + " erin v2=" + runtime.canCite("erin", "demo-policy", 2));
        System.out.println("[WorldRoleReadingDemo] 世界现金=" + worldCash
                + "（账户表实际合计，非硬编码） 账户=" + cash.size());
        System.out.println("[WorldRoleReadingDemo] 产品账户 " + FUND_ACCOUNT + " 现金="
                + cash.get(FUND_ACCOUNT) + "（四岗共用同一账户，不复制资金）");
        System.out.println("[WorldRoleReadingDemo] firstDue=" + firstDue
                + " secondDue=" + secondDue + " 完成/拒绝时点均取任务到期");
        for (InformationRun.Report entry : snapshot.information().orElseThrow().reports()
                .values()) {
            System.out.println("[WorldRoleReadingDemo] infoReport " + entry.taskId()
                    + " kind=" + entry.kind() + " accepted=" + entry.accepted()
                    + " reason=" + entry.reason() + " subject=" + entry.subjectId()
                    + " reader=" + entry.readerPartyId() + " at=" + entry.position());
        }
        for (RoleRun.Report entry : roles.reports().values()) {
            System.out.println("[WorldRoleReadingDemo] roleReport " + entry.taskId()
                    + " kind=" + entry.kind() + " accepted=" + entry.accepted()
                    + " reason=" + entry.reason() + " subject=" + entry.subjectId()
                    + " at=" + entry.position());
        }
    }

    private static int versionCount(InformationModel.State information) {
        int count = 0;
        for (InformationModel.Document document : information.documents()) {
            count += document.versions().size();
        }
        return count;
    }

    private static List<String> accountIds(RoleView view) {
        List<String> ids = new ArrayList<>();
        view.accounts().forEach(account -> ids.add(account.accountId()));
        return ids;
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        modules.put("manufacturing", 1);
        modules.put("governance", 1);
        modules.put("roles", 1);
        modules.put("information", 1);
        return new RunManifest(RUN, SEED, RunManifest.RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1,
                modules, Map.of("fixture", "world-role-reading-demo-v1", "model", "none"),
                RunManifest.MODEL_NONE);
    }

    private static List<OpeningAccount> openings() {
        List<OpeningAccount> openings = new ArrayList<>(List.of(
                new OpeningAccount(COMPANY_ACCOUNT, OWNER, money("1000.00"), "演示开局历史禀赋"),
                new OpeningAccount(SUPPLIER, "supplier", money("100.00"), "演示开局历史禀赋"),
                new OpeningAccount(CUSTOMER, "customer", money("2000.00"), "演示开局历史禀赋"),
                new OpeningAccount(OPS, "ops", money("0.00"), "演示开局历史禀赋"),
                new OpeningAccount(INVESTOR, "investor", money("500.00"), "演示开局历史禀赋"),
                new OpeningAccount(FUND_ACCOUNT, FUND_PRODUCT, money("600.00"), "演示开局历史禀赋"),
                new OpeningAccount("ORG-CASH", "orgf", money("300.00"), "演示开局历史禀赋")));
        Map<String, String> personal = new LinkedHashMap<>();
        personal.put("ACC-ALICE", "alice");
        personal.put("ACC-BOB", "bob");
        personal.put("ACC-CAROL", "carol");
        personal.put("ACC-DAVE", "dave");
        personal.put("ACC-ERIN", "erin");
        personal.put("ACC-GRACE", "grace");
        personal.forEach((accountId, owner) -> openings.add(new OpeningAccount(accountId, owner,
                money("100.00"), "演示开局历史禀赋")));
        return openings;
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter("cufe", List.of(
                new BoardModel.Seat("s1", "alice"), new BoardModel.Seat("s2", "bob"),
                new BoardModel.Seat("s3", "carol"), new BoardModel.Seat("s4", "dave"),
                new BoardModel.Seat("s5", "erin")), "s1", BoardModel.QUORUM);
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = new ArrayList<>(List.of(
                new OpeningRegistry.Party("cufe", OpeningRegistry.PartyKind.ORGANIZATION,
                        "中央财经制造公司"),
                new OpeningRegistry.Party("ORG-F", OpeningRegistry.PartyKind.ORGANIZATION,
                        "中央财经资管"),
                new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "研究员"),
                new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "基金经理"),
                new OpeningRegistry.Party("carol", OpeningRegistry.PartyKind.PERSON, "风控"),
                new OpeningRegistry.Party("dave", OpeningRegistry.PartyKind.PERSON, "执行交易员"),
                new OpeningRegistry.Party("erin", OpeningRegistry.PartyKind.PERSON, "继任研究员"),
                new OpeningRegistry.Party("grace", OpeningRegistry.PartyKind.PERSON, "资料发布者"),
                new OpeningRegistry.Party("investor", OpeningRegistry.PartyKind.PERSON, "投资者")));
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>();
        owners.add(new OpeningRegistry.PropertyOwner(OWNER,
                OpeningRegistry.OwnerKind.ORGANIZATION, "cufe"));
        owners.add(new OpeningRegistry.PropertyOwner("orgf",
                OpeningRegistry.OwnerKind.ORGANIZATION, "ORG-F"));
        owners.add(new OpeningRegistry.PropertyOwner(FUND_PRODUCT,
                OpeningRegistry.OwnerKind.PRODUCT, null));
        owners.add(new OpeningRegistry.PropertyOwner("supplier",
                OpeningRegistry.OwnerKind.AGGREGATE, null));
        owners.add(new OpeningRegistry.PropertyOwner("customer",
                OpeningRegistry.OwnerKind.AGGREGATE, null));
        owners.add(new OpeningRegistry.PropertyOwner("ops",
                OpeningRegistry.OwnerKind.AGGREGATE, null));
        owners.add(new OpeningRegistry.PropertyOwner("investor",
                OpeningRegistry.OwnerKind.PERSON, "investor"));
        for (String person : List.of("alice", "bob", "carol", "dave", "erin", "grace")) {
            owners.add(new OpeningRegistry.PropertyOwner(person,
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYMBOL, "cufe", 200, money("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(INVESTOR, SYMBOL, 200,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
