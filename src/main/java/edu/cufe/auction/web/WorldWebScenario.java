package edu.cufe.auction.web;

import edu.cufe.auction.world.BeliefModel;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.CognitionRecall;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.InterviewModel;
import edu.cufe.auction.world.InterviewRun;
import edu.cufe.auction.world.InterviewDialogue;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.MediaModel;
import edu.cufe.auction.world.MediaRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RoleTradingView;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * 正式工作台的实验世界工厂：内部调试档案，schema 9 + 现金分红模块。
 *
 * <p>边界：这是**实验夹具**，不是真实公司或真实市场数据。它只创建真实的世界状态
 * （开局现金、发行登记、董事会、四岗任职、阅读者），不凭界面名称生成任何交易、新闻或持仓。
 * 开局现金有限且只写一次；重复启动同一日志不会重复注入资金。</p>
 */
public final class WorldWebScenario {

    /** 实验档案的运行标识（与 manifest 中完全一致）。 */
    public static final String RUN_ID = "web-workbench";
    /** 实验档案标识，明确写进 manifest 元数据。 */
    public static final String PROFILE = "web-workbench-internal-debug-v1";
    /** live 档案运行标识（独立目录，与旧档案并存，不迁移）。 */
    public static final String LIVE_RUN_ID = "web-live";
    /** live 档案标识。 */
    public static final String LIVE_PROFILE = "web-live-http-v1";
    /** 回忆实验档案运行标识（独立日志，与 live/scripted 并存，不迁移）。 */
    public static final String COGNITION_RUN_ID = "web-cognition";
    /** 回忆实验档案标识。 */
    public static final String COGNITION_PROFILE = "web-cognition-recall-v1";
    /**
     * 冻结的回忆实验参数：14 模拟日半衰期、阈值 100‰、最多 8 条、正文 12000 UTF-16 字符。
     *
     * <p><b>实验值，未经调优</b>；不是"现实最佳"。修改即改变实验，必须新开日志。</p>
     */
    public static final CognitionRecall.Policy COGNITION_POLICY =
            new CognitionRecall.Policy(14L * 24 * 60 * 60 * 1000, 100, 8, 12_000);
    /** 唯一证券代码。 */
    public static final String SYMBOL = "CUFE1";
    /** 公司组织主体。 */
    public static final String COMPANY = "cufe";
    /** 投资者人物。 */
    public static final String INVESTOR = "investor";
    /** 董事会主席人物。 */
    public static final String CHAIR = "alice";

    private WorldWebScenario() {
    }

    /**
     * 打开（或按同一开局继续）实验世界。
     *
     * @param journal 世界日志
     * @return 运行时与 manifest 的运行标识
     * @throws Exception 创建失败
     */
    public static WorldWebHost.Opened open(Path journal) throws Exception {
        RunManifest manifest = manifest();
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings(), registry(),
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(), roles(), information(),
                decisions());
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    /** @return 实验档案的 manifest（schema 9、分红模块、脚本决定、内部调试标记） */
    public static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions")) {
            modules.put(name, 1);
        }
        modules.put("dividend", 1);
        return new RunManifest(RUN_ID, 11L, RunManifest.RULES_DIVIDEND_WORLD_EXPERIMENT_V1,
                modules, Map.of("profile", PROFILE), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static List<CashLedger.OpeningAccount> openings() {
        return List.of(
                new CashLedger.OpeningAccount("ACC-FUND", "fund-a", money("1000.00"),
                        "实验开局历史禀赋"),
                new CashLedger.OpeningAccount("INV", INVESTOR, money("0.00"), "实验开局历史禀赋"),
                new CashLedger.OpeningAccount("CO", "company", money("1000.00"), "实验开局历史禀赋"),
                new CashLedger.OpeningAccount("SUP", "supplier", money("100.00"), "实验开局历史禀赋"),
                new CashLedger.OpeningAccount("CUS", "customer", money("2000.00"),
                        "实验开局历史禀赋"),
                new CashLedger.OpeningAccount("OPS", "ops", money("0.00"), "实验开局历史禀赋"),
                new CashLedger.OpeningAccount("LOCK", "locked", money("0.00"), "实验开局历史禀赋"));
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(new OpeningRegistry.Party(COMPANY, OpeningRegistry.PartyKind.ORGANIZATION,
                                "中央财经制造公司（实验）"),
                        new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "甲"),
                        new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "乙"),
                        new OpeningRegistry.Party("carol", OpeningRegistry.PartyKind.PERSON, "丙"),
                        new OpeningRegistry.Party("dave", OpeningRegistry.PartyKind.PERSON, "丁"),
                        new OpeningRegistry.Party("erin", OpeningRegistry.PartyKind.PERSON, "戊"),
                        new OpeningRegistry.Party(INVESTOR, OpeningRegistry.PartyKind.PERSON,
                                "外部投资者")),
                List.of(new OpeningRegistry.PropertyOwner("fund-a",
                                OpeningRegistry.OwnerKind.PRODUCT, null),
                        new OpeningRegistry.PropertyOwner(INVESTOR,
                                OpeningRegistry.OwnerKind.PERSON, INVESTOR),
                        new OpeningRegistry.PropertyOwner("alice",
                                OpeningRegistry.OwnerKind.PERSON, "alice"),
                        new OpeningRegistry.PropertyOwner("bob",
                                OpeningRegistry.OwnerKind.PERSON, "bob"),
                        new OpeningRegistry.PropertyOwner("carol",
                                OpeningRegistry.OwnerKind.PERSON, "carol"),
                        new OpeningRegistry.PropertyOwner("dave",
                                OpeningRegistry.OwnerKind.PERSON, "dave"),
                        new OpeningRegistry.PropertyOwner("erin",
                                OpeningRegistry.OwnerKind.PERSON, "erin"),
                        new OpeningRegistry.PropertyOwner("company",
                                OpeningRegistry.OwnerKind.ORGANIZATION, COMPANY),
                        new OpeningRegistry.PropertyOwner("supplier",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("customer",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("ops",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("locked",
                                OpeningRegistry.OwnerKind.AGGREGATE, null)),
                List.of(new OpeningRegistry.Security(SYMBOL, COMPANY, 200L, money("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("INV", SYMBOL, 100L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("ACC-FUND", SYMBOL, 20L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("LOCK", SYMBOL, 60L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.LOCKED),
                        new OpeningRegistry.ShareAllocation("CO", SYMBOL, 20L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.TREASURY)));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter(COMPANY, List.of(
                new BoardModel.Seat("s1", CHAIR), new BoardModel.Seat("s2", "bob"),
                new BoardModel.Seat("s3", "carol"), new BoardModel.Seat("s4", "dave"),
                new BoardModel.Seat("s5", "erin")), "s1", BoardModel.QUORUM);
    }

    /** 四岗真实任职 + 产品管理授权（研究/组合/风控/执行）。 */
    private static RoleRun.Config roles() {
        long end = 30L * 24 * 60 * 60 * 1000;
        return new RoleRun.Config(
                List.of(new RoleAccess.ProductMandate(COMPANY, "fund-a", List.of("ACC-FUND"))),
                List.of(new RoleAccess.Appointment("A-1", "alice", COMPANY,
                                RoleAccess.Role.RESEARCHER, 0L, end, null),
                        new RoleAccess.Appointment("A-2", "bob", COMPANY,
                                RoleAccess.Role.PORTFOLIO_MANAGER, 0L, end, null),
                        new RoleAccess.Appointment("A-3", "carol", COMPANY,
                                RoleAccess.Role.RISK_OFFICER, 0L, end, null),
                        new RoleAccess.Appointment("A-4", "dave", COMPANY,
                                RoleAccess.Role.EXECUTION_TRADER, 0L, end, null)));
    }

    /** 阅读者集合：五席董事 + 主席 + 投资者都在允许集合内。 */
    private static InformationRun.Config information() {
        return new InformationRun.Config(List.of("alice", "bob", "carol", "dave", "erin",
                INVESTOR));
    }

    /**
     * 脚本决定配置：预算为 0、无脚本、无真实提供方。
     *
     * <p>本阶段不接 runner，因此不创建任何决定会话；界面必须显示脚本运行/未连接。</p>
     */
    private static DecisionModel.Config decisions() {
        DecisionModel.Usage zero = new DecisionModel.Usage(0, 0, 0, money("0.00"));
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        for (String person : List.of("alice", "bob", "carol", "dave", "erin")) {
            caps.put(person, zero);
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:web-workbench",
                "scripted-v1", Map.of("wake", 1L), Map.of(),
                new DecisionModel.Limits(1, 1_000, 1_000, 200, 200, 1, 1, 1_000),
                new DecisionModel.Budget(zero, caps));
    }

    /**
     * 打开（或按同一开局继续）回忆实验世界：schema11、真实 HTTP 模型、同一经济开局，
     * 额外冻结回忆实验参数。
     *
     * <p>与 live 档案完全并列：独立日志、独立 manifest，不迁移任何旧日志。参数是实验值，
     * 未经调优，修改参数必须新开日志。</p>
     *
     * @param journal 回忆实验日志（独立于 world-live.journal）
     * @return 运行时与 manifest 的运行标识
     * @throws Exception 创建失败
     */
    public static WorldWebHost.Opened openCognition(Path journal) throws Exception {
        RunManifest manifest = cognitionManifest();
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings(), registry(),
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(), roles(), cognitionInformation(),
                liveConfig());
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    /** @return 回忆实验档案的 manifest（schema11、live 决定 + cognition-recall:1） */
    public static RunManifest cognitionManifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions",
                "dividend", "live-decisions", "cognition-recall")) {
            modules.put(name, 1);
        }
        return new RunManifest(COGNITION_RUN_ID, 11L,
                RunManifest.RULES_COGNITION_RECALL_EXPERIMENT_V1, modules,
                Map.of("profile", COGNITION_PROFILE), RunManifest.LIVE_DECISIONS_V1);
    }

    /**
     * 打开独立判断与学习实验档案；不迁移旧世界，不自动发出模型请求。
     *
     * @param journal 独立日志
     * @return 运行与真实标识
     * @throws Exception 初始化或恢复失败
     */
    public static WorldWebHost.Opened openBelief(Path journal) throws Exception {
        RunManifest manifest = beliefManifest();
        InformationRun.Config info = new InformationRun.Config(information().readers(),
                COGNITION_POLICY, new edu.cufe.auction.world.BeliefModel.Policy(
                        200, 8, 30L * 86_400_000L, 20, 200, 3, 30L * 86_400_000L));
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings(), registry(),
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(), roles(), info, beliefConfig());
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    private static DecisionModel.Config beliefConfig() {
        DecisionModel.Config base = liveConfig();
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        for (String tool : List.of(RoleTradingView.TOOL_BELIEF_ASSERT,
                RoleTradingView.TOOL_FORECAST_PUBLISH, RoleTradingView.TOOL_COMPANY_REPORT_ORDERS)) {
            actions.put(tool, 2_000L);
        }
        return new DecisionModel.Config(base.providerKind(), base.providerId(), base.pricingVersion(),
                base.thinkMillisByWake(), actions, base.limits(), base.budget(), base.profile());
    }

    /** @return 独立schema12判断实验档案，参数未经现实行为校准 */
    public static RunManifest beliefManifest() {
        Map<String, Integer> modules = new TreeMap<>(cognitionManifest().moduleVersions());
        modules.put("belief", 1);
        return new RunManifest("web-belief", 11L, "cognition-belief-experiment-v1", modules,
                Map.of("profile", "web-cognition-belief-v1"), RunManifest.LIVE_DECISIONS_V1);
    }

    /** 回忆档案的资料配置：同一批登记读者 + 冻结实验参数。 */
    private static InformationRun.Config cognitionInformation() {
        return new InformationRun.Config(information().readers(), COGNITION_POLICY);
    }

    /**
     * 打开（或按同一开局继续）live 实验世界：schema10、真实 HTTP 模型、与经济开局完全相同。
     *
     * @param journal 世界日志（独立于旧 scripted 档案）
     * @return 运行时与 manifest 的运行标识
     * @throws Exception 创建失败
     */
    public static WorldWebHost.Opened openLive(Path journal) throws Exception {
        RunManifest manifest = liveManifest();
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings(), registry(),
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(), roles(), information(),
                liveConfig());
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    /** @return live 档案 manifest（schema10、live 决定模块、真实 HTTP 模型版本） */
    public static RunManifest liveManifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions",
                "dividend", "live-decisions")) {
            modules.put(name, 1);
        }
        return new RunManifest(LIVE_RUN_ID, 11L,
                RunManifest.RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1, modules,
                Map.of("profile", LIVE_PROFILE), RunManifest.LIVE_DECISIONS_V1);
    }

    /**
     * live 冻结配置：全部为内部实验限制，费用未知，只按 token 预算。
     *
     * @return 决定配置
     */
    public static DecisionModel.Config liveConfig() {
        Map<String, Long> think = Map.of("wake", 1_000L);
        Map<String, Long> actions = new TreeMap<>();
        for (String tool : List.of(RoleTradingView.TOOL_READING_START,
                RoleTradingView.TOOL_READING_CANCEL, RoleTradingView.TOOL_CITE,
                RoleTradingView.TOOL_RESEARCH_SUBMIT, RoleTradingView.TOOL_TARGET_SET,
                RoleTradingView.TOOL_TARGET_REVIEW, RoleTradingView.TOOL_TARGET_HALT,
                RoleTradingView.TOOL_TARGET_CONFIRM, RoleTradingView.TOOL_TARGET_CANCEL,
                RoleTradingView.TOOL_CHILD_SUBMIT, RoleTradingView.TOOL_CHILD_CANCEL)) {
            actions.put(tool, 2_000L);
        }
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        for (String person : WorldWebHost.LIVE_ROLE_PERSONS) {
            caps.put(person, DecisionModel.Usage.unknownCost(25, 250_000, 50_000));
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_DEEPSEEK_HTTP, "deepseek:api",
                DecisionModel.ESTIMATE_VERSION, think, actions,
                new DecisionModel.Limits(1, 32_768, 65_536, 60_000, 8_192, 4, 2, 15_000),
                new DecisionModel.Budget(DecisionModel.Usage.unknownCost(100, 1_000_000, 200_000),
                        caps),
                DecisionModel.Profile.LIVE_HTTP_V1);
    }

    /** 媒体新档案的明确登记人物，旧档案不添加任职或预算。 */
    public static final List<String> MEDIA_PERSONS = List.of("rita", "ed", "sam", "bea");

    /**
     * 打开独立采编实验档案：两个媒体组织、各自独立记者与编辑，不生成虚构新闻。
     *
     * @param journal 独立 world-media.journal
     * @return 权威运行时与实际运行标识
     * @throws Exception 开局或恢复失败
     */
    public static WorldWebHost.Opened openMedia(Path journal) throws Exception {
        OpeningRegistry base = registry();
        List<OpeningRegistry.Party> parties = new ArrayList<>(base.parties());
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(base.owners());
        for (String organization : List.of("wire", "investigative")) {
            parties.add(new OpeningRegistry.Party(organization,
                    OpeningRegistry.PartyKind.ORGANIZATION,
                    organization.equals("wire") ? "快讯社（实验）" : "调查社（实验）"));
            owners.add(new OpeningRegistry.PropertyOwner(organization,
                    OpeningRegistry.OwnerKind.ORGANIZATION, organization));
        }
        Map<String, String> names = Map.of("rita", "快讯记者", "ed", "快讯编辑",
                "sam", "调查记者", "bea", "调查编辑");
        for (String person : MEDIA_PERSONS) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON,
                    names.get(person)));
            owners.add(new OpeningRegistry.PropertyOwner(person,
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        OpeningRegistry registry = new OpeningRegistry(parties, owners, base.securities(),
                base.allocations());
        List<RoleAccess.Appointment> appointments = new ArrayList<>(roles().appointments());
        long end = 30L * 86_400_000L;
        for (int i = 0; i < MEDIA_PERSONS.size(); i++) {
            appointments.add(new RoleAccess.Appointment("MEDIA-" + i, MEDIA_PERSONS.get(i),
                    i < 2 ? "wire" : "investigative",
                    i % 2 == 0 ? RoleAccess.Role.JOURNALIST : RoleAccess.Role.EDITOR,
                    0L, end, null));
        }
        List<String> readers = new ArrayList<>(information().readers());
        readers.addAll(MEDIA_PERSONS);
        InformationRun.Config info = new InformationRun.Config(readers, COGNITION_POLICY,
                new edu.cufe.auction.world.BeliefModel.Policy(
                        200, 8, end, 20, 200, 3, end));
        RunManifest manifest = mediaManifest();
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings(), registry,
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(),
                new RoleRun.Config(roles().mandates(), appointments), info, mediaDecisionConfig(),
                mediaConfig());
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    /** @return schema15 逐轮访谈档案；与既有档案并存，不隐式迁移 */
    public static RunManifest dialogueManifest() {
        Map<String, Integer> modules = new TreeMap<>(interviewManifest().moduleVersions());
        modules.put("dialogue", 1);
        return new RunManifest("web-dialogue", 11L,
                RunManifest.RULES_INTERVIEW_DIALOGUE_EXPERIMENT_V1, modules,
                Map.of("profile", "web-interview-dialogue-v1"),
                RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return 逐轮访谈实验配置（模拟毫秒，不是现实常数） */
    public static InterviewDialogue.Config dialogueConfig() {
        return new InterviewDialogue.Config(8, 60_000L, 1_000L, COGNITION_POLICY);
    }

    /**
     * 打开 schema15 逐轮访谈档案：真实排程发布模拟联系资料，不预写、不代读。
     *
     * @param journal 日志文件
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldWebHost.Opened openInterviewDialogue(Path journal) throws Exception {
        OpeningRegistry base = registry();
        List<OpeningRegistry.Party> parties = new ArrayList<>(base.parties());
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(base.owners());
        for (String organization : List.of("wire", "investigative")) {
            parties.add(new OpeningRegistry.Party(organization,
                    OpeningRegistry.PartyKind.ORGANIZATION,
                    organization.equals("wire") ? "快讯社（实验）" : "调查社（实验）"));
            owners.add(new OpeningRegistry.PropertyOwner(organization,
                    OpeningRegistry.OwnerKind.ORGANIZATION, organization));
        }
        Map<String, String> names = Map.of("rita", "快讯记者", "ed", "快讯编辑",
                "sam", "调查记者", "bea", "调查编辑");
        for (String person : MEDIA_PERSONS) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON,
                    names.get(person)));
            owners.add(new OpeningRegistry.PropertyOwner(person,
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        OpeningRegistry registry = new OpeningRegistry(parties, owners, base.securities(),
                base.allocations());
        List<RoleAccess.Appointment> appointments = new ArrayList<>(roles().appointments());
        long end = 30L * 86_400_000L;
        for (int i = 0; i < MEDIA_PERSONS.size(); i++) {
            appointments.add(new RoleAccess.Appointment("MEDIA-" + i, MEDIA_PERSONS.get(i),
                    i < 2 ? "wire" : "investigative",
                    i % 2 == 0 ? RoleAccess.Role.JOURNALIST : RoleAccess.Role.EDITOR,
                    0L, end, null));
        }
        List<String> readers = new ArrayList<>(information().readers());
        readers.addAll(MEDIA_PERSONS);
        InformationRun.Config info = new InformationRun.Config(readers, COGNITION_POLICY,
                new BeliefModel.Policy(200, 8, end, 20, 200, 3, end));
        RunManifest manifest = dialogueManifest();
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings(), registry,
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(),
                new RoleRun.Config(roles().mandates(), appointments), info,
                dialogueDecisionConfig(), null, mediaConfig(), interviewConfig(),
                dialogueConfig());
        runtime.schedulePublishDocument("dialogue-contact-publish", "dialogue-contact-task",
                1_000L, INTERVIEW_CONTACT_DOC, INVESTOR,
                InformationModel.Audience.publicAudience(),
                "模拟公开联系说明（实验）", "公开模拟联系说明：工作时段可预约采访。",
                "实验模拟，不是现实联系方式", 5_000L, List.of());
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    /** @return schema14 双边采访预约协调档案；与既有档案并存，不隐式迁移 */
    public static RunManifest interviewManifest() {
        Map<String, Integer> modules = new TreeMap<>(mediaManifest().moduleVersions());
        modules.put("interview", 1);
        return new RunManifest("web-interview", 11L,
                RunManifest.RULES_INTERVIEW_COORDINATION_EXPERIMENT_V1, modules,
                Map.of("profile", "web-interview-coordination-v1"),
                RunManifest.LIVE_DECISIONS_V1);
    }

    /**
     * 公开模拟联系说明的资料标识：先冻结，等真实发布与阅读后才成为可达入口。
     */
    public static final String INTERVIEW_CONTACT_DOC = "contact:investor-public";

    /** @return 采访冻结配置：两机构、四个动作时长、一条有出处的联系入口 */
    public static InterviewRun.Config interviewConfig() {
        List<InterviewModel.Policy> policies = List.of(
                new InterviewModel.Policy("wire", 3_600_000L, 60_000L, 1_800_000L),
                new InterviewModel.Policy("investigative", 3_600_000L, 60_000L, 1_800_000L));
        List<InterviewRun.ContactEntry> contacts = List.of(new InterviewRun.ContactEntry(
                "公开投关热线", INVESTOR, "公开模拟联系说明：工作时段可预约采访",
                INTERVIEW_CONTACT_DOC, 1L));
        return new InterviewRun.Config(policies, 1_000L, 1_000L, 500L, 500L, contacts);
    }

    /**
     * 打开独立采访协调实验档案：两家媒体、同一群人物，不生成虚构采访正文。
     *
     * @param journal 日志文件
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldWebHost.Opened openInterview(Path journal) throws Exception {
        OpeningRegistry base = registry();
        List<OpeningRegistry.Party> parties = new ArrayList<>(base.parties());
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(base.owners());
        for (String organization : List.of("wire", "investigative")) {
            parties.add(new OpeningRegistry.Party(organization,
                    OpeningRegistry.PartyKind.ORGANIZATION,
                    organization.equals("wire") ? "快讯社（实验）" : "调查社（实验）"));
            owners.add(new OpeningRegistry.PropertyOwner(organization,
                    OpeningRegistry.OwnerKind.ORGANIZATION, organization));
        }
        Map<String, String> names = Map.of("rita", "快讯记者", "ed", "快讯编辑",
                "sam", "调查记者", "bea", "调查编辑");
        for (String person : MEDIA_PERSONS) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON,
                    names.get(person)));
            owners.add(new OpeningRegistry.PropertyOwner(person,
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        OpeningRegistry registry = new OpeningRegistry(parties, owners, base.securities(),
                base.allocations());
        List<RoleAccess.Appointment> appointments = new ArrayList<>(roles().appointments());
        long end = 30L * 86_400_000L;
        for (int i = 0; i < MEDIA_PERSONS.size(); i++) {
            appointments.add(new RoleAccess.Appointment("MEDIA-" + i, MEDIA_PERSONS.get(i),
                    i < 2 ? "wire" : "investigative",
                    i % 2 == 0 ? RoleAccess.Role.JOURNALIST : RoleAccess.Role.EDITOR,
                    0L, end, null));
        }
        List<String> readers = new ArrayList<>(information().readers());
        readers.addAll(MEDIA_PERSONS);
        InformationRun.Config info = new InformationRun.Config(readers, COGNITION_POLICY,
                new edu.cufe.auction.world.BeliefModel.Policy(
                        200, 8, end, 20, 200, 3, end));
        RunManifest manifest = interviewManifest();
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings(), registry,
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(),
                new RoleRun.Config(roles().mandates(), appointments), info,
                interviewDecisionConfig(), null, mediaConfig(), interviewConfig());
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    /** @return schema13独立采编档案；与既有档案并存，不隐式迁移 */
    public static RunManifest mediaManifest() {
        Map<String, Integer> modules = new TreeMap<>(beliefManifest().moduleVersions());
        modules.put("media", 1);
        return new RunManifest("web-media", 11L, RunManifest.RULES_MEDIA_EDITORIAL_EXPERIMENT_V1,
                modules, Map.of("profile", "web-media-editorial-v1"), RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return 未调优的实验配置，每机构总工时240000毫秒，不是现实媒体日报制度 */
    public static MediaRun.Config mediaConfig() {
        List<MediaModel.Policy> policies = List.of(
                new MediaModel.Policy("wire", Map.of(MediaModel.TemplateKind.WIRE, 1),
                        10_000L, 3_000L, 2_000L, 500L, 250L, COGNITION_POLICY),
                new MediaModel.Policy("investigative", Map.of(MediaModel.TemplateKind.INVESTIGATIVE, 2),
                        10_000L, 3_000L, 2_000L, 500L, 250L, COGNITION_POLICY));
        return new MediaRun.Config(policies, Map.of("wire", 240_000L, "investigative", 240_000L),
                1_000L, 1_000L, 250L);
    }

    /** @return 采访工具的决定配置：在媒体配置之上补四个采访工具的动作时长与人物额度 */
    public static DecisionModel.Config interviewDecisionConfig() {
        DecisionModel.Config base = mediaDecisionConfig();
        InterviewRun.Config interview = interviewConfig();
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(RoleTradingView.TOOL_INTERVIEW_INVITE, interview.inviteMillis());
        actions.put(RoleTradingView.TOOL_INTERVIEW_RESPOND, interview.respondMillis());
        actions.put(RoleTradingView.TOOL_INTERVIEW_CANCEL, interview.cancelMillis());
        actions.put(RoleTradingView.TOOL_INTERVIEW_END, interview.endMillis());
        Map<String, DecisionModel.Usage> caps = new TreeMap<>(base.budget().personCap());
        caps.put(INVESTOR, DecisionModel.Usage.unknownCost(25, 250_000, 50_000));
        return new DecisionModel.Config(base.providerKind(), base.providerId(),
                base.pricingVersion(), base.thinkMillisByWake(), actions, base.limits(),
                new DecisionModel.Budget(base.budget().runCap(), caps), base.profile());
    }

    /** @return schema15 决定配置：在采访配置上补逐轮发言时长，旧 14 不出现 */
    public static DecisionModel.Config dialogueDecisionConfig() {
        DecisionModel.Config base = interviewDecisionConfig();
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(RoleTradingView.TOOL_INTERVIEW_SPEAK, dialogueConfig().speakMillis());
        return new DecisionModel.Config(base.providerKind(), base.providerId(),
                base.pricingVersion(), base.thinkMillisByWake(), actions, base.limits(),
                base.budget(), base.profile());
    }

    /** @return 既有角色与新增采编人物的有界模型配置，启动不调用提供方 */
    public static DecisionModel.Config mediaDecisionConfig() {
        DecisionModel.Config base = beliefConfig();
        MediaRun.Config media = mediaConfig();
        MediaModel.Policy policy = media.policies().get(0);
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(RoleTradingView.TOOL_MEDIA_PROPOSE, media.proposeMillis());
        actions.put(RoleTradingView.TOOL_MEDIA_SUBMIT, policy.writeMillis());
        actions.put(RoleTradingView.TOOL_MEDIA_REVIEW, policy.reviewMillis());
        actions.put(RoleTradingView.TOOL_MEDIA_PUBLISH, policy.publishMillis());
        actions.put(RoleTradingView.TOOL_MEDIA_CORRECTION, media.correctionMillis());
        actions.put(RoleTradingView.TOOL_MEDIA_CANCEL, media.cancelMillis());
        Map<String, DecisionModel.Usage> caps = new TreeMap<>(base.budget().personCap());
        for (String person : MEDIA_PERSONS) {
            caps.put(person, DecisionModel.Usage.unknownCost(25, 250_000, 50_000));
        }
        return new DecisionModel.Config(base.providerKind(), base.providerId(), base.pricingVersion(),
                base.thinkMillisByWake(), actions, base.limits(),
                new DecisionModel.Budget(base.budget().runCap(), caps), base.profile());
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
