package edu.cufe.auction.web;

import edu.cufe.auction.world.BeliefModel;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.CognitionRecall;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.EngineeringRun;
import edu.cufe.auction.world.EvidenceRun;
import edu.cufe.auction.world.GroupModel;
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
import edu.cufe.auction.world.PlatformPostModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RoleTradingView;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.SocialMessageModel;
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

    /** schema16 私有证据档的运行标识（独立日志，不迁移旧档）。 */
    public static final String EVIDENCE_RUN_ID = "web-evidence";
    /** 新档里唯一一份模拟私证：由投资者向记者定向发布，开局不代读。 */
    public static final String EVIDENCE_PRIVATE_DOC = "evidence:investor-note";
    /** 共享冻结工时（实验参数，未按现实校准）。 */
    public static final long EVIDENCE_SHARE_MILLIS = 1_000L;

    /** @return schema16 匿名私有证据档；与 schema13–15 并存，不隐式迁移 */
    public static RunManifest evidenceManifest() {
        Map<String, Integer> modules = new TreeMap<>(dialogueManifest().moduleVersions());
        modules.put("evidence", 1);
        return new RunManifest(EVIDENCE_RUN_ID, 11L,
                RunManifest.RULES_MEDIA_EVIDENCE_EXPERIMENT_V1, modules,
                Map.of("profile", "web-media-evidence-v1"), RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return 证据档冻结配置：只有一次共享的机构工时（正数，实验参数） */
    public static EvidenceRun.Config evidenceConfig() {
        return new EvidenceRun.Config(EVIDENCE_SHARE_MILLIS);
    }

    /** @return 证据档决定配置：在逐轮访谈工具之上补身份共享动作时长 */
    public static DecisionModel.Config evidenceDecisionConfig() {
        DecisionModel.Config base = dialogueDecisionConfig();
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(RoleTradingView.TOOL_MEDIA_EVIDENCE_SHARE, EVIDENCE_SHARE_MILLIS);
        return new DecisionModel.Config(base.providerKind(), base.providerId(),
                base.pricingVersion(), base.thinkMillisByWake(), actions, base.limits(),
                base.budget(), base.profile());
    }

    /**
     * 打开 schema16 匿名私有证据档：同一经济开局与两社人物，开局只发布一份<b>定向私证</b>，
     * 不代读、不成稿、不共享——一切都必须由真实会话动作发生。
     *
     * @param journal 独立日志文件
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldWebHost.Opened openEvidence(Path journal) throws Exception {
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
        RunManifest manifest = evidenceManifest();
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings(), registry,
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(),
                new RoleRun.Config(roles().mandates(), appointments), info,
                evidenceDecisionConfig(), null, mediaConfig(), interviewConfig(),
                dialogueConfig(), evidenceConfig());
        runtime.schedulePublishDocument("evidence-private-publish", "evidence-private-task",
                1_000L, EVIDENCE_PRIVATE_DOC, INVESTOR,
                InformationModel.Audience.direct("rita"),
                "模拟投资者手记（仅记者可见）",
                "模拟定向材料：本次采访前的私人说明，未对外发表。",
                "实验模拟，不是现实人物或真实信息", 5_000L, List.of());
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    /** schema17 社交档的运行标识（独立日志，不迁移旧档）。 */
    public static final String SOCIAL_RUN_ID = "web-social";
    /** 社交档唯一的公开联系入口：两个普通人物都必须真实读完才可互发。 */
    public static final String SOCIAL_CONTACT_DOC = "social:contact-intro";
    /** 社交档两个普通非媒体人物。 */
    public static final List<String> SOCIAL_PERSONS = List.of("nina", "omar");
    /** 18b 董事/经理动作的冻结耗时：同时就是人物焦点的占用长度。 */
    public static final long ENGINEERING_ACTION_MILLIS = 1_000L;

    /** 社交冻结工时（实验参数，未按现实校准）。 */
    public static final long SOCIAL_SEND_MILLIS = 1_000L;
    public static final long SOCIAL_DELIVERY_MILLIS = 2_000L;
    public static final long SOCIAL_READ_MILLIS = 500L;
    public static final long SOCIAL_BLOCK_MILLIS = 250L;

    /** @return schema17 一对一消息档案；与 13–16 并存，不隐式迁移 */
    public static RunManifest socialManifest() {
        Map<String, Integer> modules = new TreeMap<>(evidenceManifest().moduleVersions());
        modules.put("social", 1);
        return new RunManifest(SOCIAL_RUN_ID, 11L, RunManifest.RULES_SOCIAL_EXPERIMENT_V1,
                modules, Map.of("profile", "web-social-messaging-v1"),
                RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return 社交档冻结配置：写信/投递等待/阅读三类正工时 */
    public static SocialMessageModel.Config socialConfig() {
        return new SocialMessageModel.Config(SOCIAL_SEND_MILLIS, SOCIAL_DELIVERY_MILLIS,
                SOCIAL_READ_MILLIS);
    }

    /** @return 社交档决定配置：在证据工具之上补三个社交动作时长 */
    public static DecisionModel.Config socialDecisionConfig() {
        DecisionModel.Config base = evidenceDecisionConfig();
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(RoleTradingView.TOOL_SOCIAL_SEND, SOCIAL_SEND_MILLIS);
        actions.put(RoleTradingView.TOOL_SOCIAL_BLOCK, SOCIAL_BLOCK_MILLIS);
        actions.put(RoleTradingView.TOOL_SOCIAL_UNBLOCK, SOCIAL_BLOCK_MILLIS);
        Map<String, DecisionModel.Usage> caps = new TreeMap<>(base.budget().personCap());
        for (String person : SOCIAL_PERSONS) {
            caps.put(person, DecisionModel.Usage.unknownCost(25, 250_000, 50_000));
        }
        return new DecisionModel.Config(base.providerKind(), base.providerId(),
                base.pricingVersion(), base.thinkMillisByWake(), actions, base.limits(),
                new DecisionModel.Budget(base.budget().runCap(), caps), base.profile());
    }

    /**
     * schema18b 决定配置：在 schema17 社交档之上登记 v6 董事/经理工具的真实动作耗时。
     *
     * <p>工时即这些动作占用人物焦点的长度；工具名只在 18 档出现，v1–v5 输入与配置不变。</p>
     *
     * @return 冻结决定配置
     */
    public static DecisionModel.Config engineeringDecisionConfig() {
        DecisionModel.Config base = socialDecisionConfig();
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        for (String tool : List.of(RoleTradingView.TOOL_BOARD_CONVENE,
                RoleTradingView.TOOL_BOARD_ATTEND, RoleTradingView.TOOL_BOARD_MATERIAL,
                RoleTradingView.TOOL_BOARD_VOTE, RoleTradingView.TOOL_BOARD_PROPOSE_BUDGET,
                RoleTradingView.TOOL_BOARD_PROPOSE_APPOINTMENT,
                RoleTradingView.TOOL_BOARD_PROPOSE_WAIVER,
                RoleTradingView.TOOL_BOARD_IMPLEMENT, RoleTradingView.TOOL_ENGINEERING_SIGN,
                RoleTradingView.TOOL_ENGINEERING_START_WORK,
                RoleTradingView.TOOL_ENGINEERING_INVOICE,
                RoleTradingView.TOOL_ENGINEERING_TERMINATE,
                RoleTradingView.TOOL_ENGINEERING_PAY_SUPPLIER,
                RoleTradingView.TOOL_ENGINEERING_IMPAIR,
                RoleTradingView.TOOL_ENGINEERING_WRITE_OFF,
                RoleTradingView.TOOL_ENGINEERING_RECOVER,
                RoleTradingView.TOOL_ENGINEERING_REQUEST_ACCEPTANCE,
                RoleTradingView.TOOL_ENGINEERING_REQUEST_PAYMENT,
                RoleTradingView.TOOL_ENGINEERING_REQUEST_RETENTION,
                RoleTradingView.TOOL_ENGINEERING_WAIVE,
                RoleTradingView.TOOL_PERIODIC_PREPARE,
                RoleTradingView.TOOL_PERIODIC_SCORE,
                RoleTradingView.TOOL_PERIODIC_APPROVE,
                RoleTradingView.TOOL_PERIODIC_AUTHORIZE,
                RoleTradingView.TOOL_PERIODIC_PUBLISH)) {
            actions.put(tool, ENGINEERING_ACTION_MILLIS);
        }
        Map<String, DecisionModel.Usage> caps = new TreeMap<>(base.budget().personCap());
        // 章程公司董事/经理也要能发起真实 v6 决定：给该公司每个席位补齐内部实验额度。
        // 只补已有席位的预算，不新增角色、不改冻结的任期与章程。
        for (BoardModel.Seat seat : charter().seats()) {
            caps.putIfAbsent(seat.partyId(), DecisionModel.Usage.unknownCost(25, 250_000, 50_000));
        }
        return new DecisionModel.Config(base.providerKind(), base.providerId(),
                base.pricingVersion(), base.thinkMillisByWake(), actions, base.limits(),
                new DecisionModel.Budget(base.budget().runCap(), caps), base.profile());
    }

    /**
     * 18b 的 scripted 决定配置：与 live 档同一工具表，但没有 live profile，
     * 供确定性 provider 桩与现有决策测试链使用（不产生任何真实调用）。
     *
     * @return scripted 决定配置
     */
    public static DecisionModel.Config engineeringScriptedDecisionConfig() {
        DecisionModel.Config base = engineeringDecisionConfig();
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, base.providerId(),
                base.pricingVersion(), base.thinkMillisByWake(), base.actionMillisByTool(),
                base.limits(), scriptedBudget(base.budget()));
    }

    /** 脚本档预算：保留次数/token 上限，费用改为已知 0.00（不产生真实计费）。 */
    private static DecisionModel.Budget scriptedBudget(DecisionModel.Budget base) {
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        base.personCap().forEach((person, cap) -> caps.put(person, zeroCost(cap)));
        return new DecisionModel.Budget(zeroCost(base.runCap()), caps);
    }

    private static DecisionModel.Usage zeroCost(DecisionModel.Usage usage) {
        return new DecisionModel.Usage(usage.attempts(), usage.inputTokens(),
                usage.outputTokens(), new BigDecimal("0.00"));
    }

    /**
     * 打开 schema17 一对一消息档：继承已启用的媒体/采访/私证能力与真实联系入口，
     * 只额外加两名普通非媒体人物与一份公开联系说明；开局不代读、不发消息。
     *
     * @param journal 独立日志文件
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldWebHost.Opened openSocial(Path journal) throws Exception {
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
        Map<String, String> names = new TreeMap<>(Map.of("rita", "快讯记者", "ed", "快讯编辑",
                "sam", "调查记者", "bea", "调查编辑"));
        for (String person : MEDIA_PERSONS) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON,
                    names.get(person)));
            owners.add(new OpeningRegistry.PropertyOwner(person,
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        names.putAll(Map.of("nina", "普通职员甲", "omar", "普通职员乙"));
        for (String person : SOCIAL_PERSONS) {
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
        readers.addAll(SOCIAL_PERSONS);
        InformationRun.Config info = new InformationRun.Config(readers, COGNITION_POLICY,
                new BeliefModel.Policy(200, 8, end, 20, 200, 3, end));
        List<InterviewRun.ContactEntry> contacts = new ArrayList<>(interviewConfig().contacts());
        for (String person : SOCIAL_PERSONS) {
            contacts.add(new InterviewRun.ContactEntry("私信", person,
                    "公开联系说明：可一对一私人消息", SOCIAL_CONTACT_DOC, 1L));
        }
        RunManifest manifest = socialManifest();
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings(), registry,
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(),
                new RoleRun.Config(roles().mandates(), appointments), info,
                socialDecisionConfig(), null, mediaConfig(), interviewConfig(), dialogueConfig(),
                evidenceConfig(), socialConfig(), SOCIAL_BLOCK_MILLIS, contacts);
        runtime.schedulePublishDocument("social-contact-publish", "social-contact-task", 1_000L,
                SOCIAL_CONTACT_DOC, INVESTOR, InformationModel.Audience.publicAudience(),
                "公开联系说明（实验）", "公开模拟联系说明：工作时段可一对一私人消息。",
                "实验模拟，不是现实联系方式", SOCIAL_READ_MILLIS, List.of());
        runtime.schedulePublishDocument("evidence-private-publish", "evidence-private-task",
                1_000L, EVIDENCE_PRIVATE_DOC, INVESTOR,
                InformationModel.Audience.direct("rita"),
                "模拟投资者手记（仅记者可见）",
                "模拟定向材料：本次采访前的私人说明，未对外发表。",
                "实验模拟，不是现实人物或真实信息", 5_000L, List.of());
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

    /** schema18 工程档运行标识（独立日志，不迁移 17 档）。 */
    public static final String ENGINEERING_RUN_ID = "web-engineering";
    /** schema18 工程档标识，实验夹具而非真实公司。 */
    public static final String ENGINEERING_PROFILE = "web-engineering-v1";
    /**
     * 章程公司（制造）的开局治理说明文档标识：只由冻结章程与开局任职派生，
     * 供董事会在真实任免会议中作为材料阅读；不是真实公司披露。
     */
    public static final String GOVERNANCE_NOTE_DOC = "company-governance:" + COMPANY + ":1";
    /** 治理说明的阅读耗时（模拟毫秒，实验值）。 */
    public static final long GOVERNANCE_NOTE_READ_MILLIS = 1_000L;
    /** 工程公司 A：至少两张冻结合同共享同一经营现金账户。 */
    public static final String ENGINE_A = "eng-a";
    /** 工程公司 B。 */
    public static final String ENGINE_B = "eng-b";

    /** @return schema18 工程档：社交全链模块 + engineering 模块，脚本不发起提供方调用 */
    public static RunManifest engineeringManifest() {
        Map<String, Integer> modules = new TreeMap<>(socialManifest().moduleVersions());
        modules.put("engineering", 1);
        return new RunManifest(ENGINEERING_RUN_ID, 11L,
                RunManifest.RULES_ENGINEERING_WORLD_EXPERIMENT_V1, modules,
                Map.of("profile", ENGINEERING_PROFILE), RunManifest.LIVE_DECISIONS_V1);
    }

    /**
     * 工程档账户：制造业原账户保持不变，两个工程公司各自唯一经营账户与独立客户/供应商/作业账户。
     *
     * @return 开局现金清单（新增账户只出现在本档，旧档字节不变）
     */
    public static List<CashLedger.OpeningAccount> engineeringOpenings() {
        List<CashLedger.OpeningAccount> list = new ArrayList<>(openings());
        list.add(new CashLedger.OpeningAccount("ENG-A", "eng-a", money("5000.00"),
                "工程A开局历史禀赋"));
        list.add(new CashLedger.OpeningAccount("ENG-A-CUS", "eng-a-cus", money("20000.00"),
                "工程A客户预算"));
        list.add(new CashLedger.OpeningAccount("ENG-A-SUP", "eng-a-sup", money("0.00"),
                "工程A供应商聚合"));
        list.add(new CashLedger.OpeningAccount("ENG-A-OPS", "eng-a-ops", money("0.00"),
                "工程A作业方聚合"));
        list.add(new CashLedger.OpeningAccount("ENG-B", "eng-b", money("4000.00"),
                "工程B开局历史禀赋"));
        list.add(new CashLedger.OpeningAccount("ENG-B-CUS", "eng-b-cus", money("15000.00"),
                "工程B客户预算"));
        list.add(new CashLedger.OpeningAccount("ENG-B-SUP", "eng-b-sup", money("0.00"),
                "工程B供应商聚合"));
        list.add(new CashLedger.OpeningAccount("ENG-B-OPS", "eng-b-ops", money("0.00"),
                "工程B作业方聚合"));
        return list;
    }

    /**
     * 工程档登记：在社交档人物与媒体组织之上加两个工程公司与各自账户 owner。
     *
     * <p>两个工程公司各有五席董事会；席位人物可跨公司复用，但同一公司内一人一席。</p>
     *
     * @return 开局登记（含证券与开局分配）
     */
    public static OpeningRegistry engineeringRegistry() {
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
        Map<String, String> names = new TreeMap<>(Map.of("rita", "快讯记者", "ed", "快讯编辑",
                "sam", "调查记者", "bea", "调查编辑"));
        for (String person : MEDIA_PERSONS) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON,
                    names.get(person)));
            owners.add(new OpeningRegistry.PropertyOwner(person,
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        for (String person : SOCIAL_PERSONS) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON,
                    person.equals("nina") ? "普通职员甲" : "普通职员乙"));
            owners.add(new OpeningRegistry.PropertyOwner(person,
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        for (String company : List.of(ENGINE_A, ENGINE_B)) {
            parties.add(new OpeningRegistry.Party(company,
                    OpeningRegistry.PartyKind.ORGANIZATION,
                    company.equals(ENGINE_A) ? "工程公司A（实验）" : "工程公司B（实验）"));
            owners.add(new OpeningRegistry.PropertyOwner(company,
                    OpeningRegistry.OwnerKind.ORGANIZATION, company));
            for (String suffix : List.of("-cus", "-sup", "-ops")) {
                owners.add(new OpeningRegistry.PropertyOwner(company + suffix,
                        OpeningRegistry.OwnerKind.AGGREGATE, null));
            }
        }
        List<OpeningRegistry.Security> securities = new ArrayList<>(base.securities());
        securities.add(new OpeningRegistry.Security("CUFE-A", ENGINE_A, 1_000L,
                money("10.00")));
        securities.add(new OpeningRegistry.Security("CUFE-B", ENGINE_B, 1_000L,
                money("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations =
                new ArrayList<>(base.allocations());
        allocations.add(new OpeningRegistry.ShareAllocation("INV", "CUFE-A", 600L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        allocations.add(new OpeningRegistry.ShareAllocation("ENG-A", "CUFE-A", 400L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.TREASURY));
        allocations.add(new OpeningRegistry.ShareAllocation("INV", "CUFE-B", 600L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        allocations.add(new OpeningRegistry.ShareAllocation("ENG-B", "CUFE-B", 400L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.TREASURY));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }

    /** @return 单公司五席章程；主席固定第一席 */
    private static BoardModel.Charter engineeringCharter(String company, String prefix,
                                                         List<String> persons) {
        List<BoardModel.Seat> seats = new ArrayList<>();
        for (int i = 0; i < persons.size(); i++) {
            seats.add(new BoardModel.Seat(prefix + (i + 1), persons.get(i)));
        }
        return new BoardModel.Charter(company, seats, prefix + "1", BoardModel.QUORUM);
    }

    /** @return 单张冻结合同计划（阶段单位以 List 保存，保证配置值相等） */
    private static EngineeringRun.ContractPlan engineeringContract(String contractId,
            String unitPrice, String unitCost, long[] tranches) {
        List<Long> units = new ArrayList<>(tranches.length);
        for (long unitsPerTranche : tranches) {
            units.add(unitsPerTranche);
        }
        return new EngineeringRun.ContractPlan(contractId, money(unitPrice),
                new BigDecimal(unitCost).setScale(4), 1000, 1000, 30, 60, units);
    }

    /** @return schema18 工程配置：A 两张合同共享 A 现金，B 一张；每公司唯一模板与章程 */
    public static EngineeringRun.Config engineeringConfig() {
        List<EngineeringRun.CompanySpec> companies = List.of(
                new EngineeringRun.CompanySpec(ENGINE_A, "eng-a", "ENG-A", "ENG-A-CUS",
                        "ENG-A-SUP", "ENG-A-OPS", 60_000L, 6_000,
                        engineeringCharter(ENGINE_A, "ea",
                                List.of("alice", "bob", "carol", "dave", "erin")),
                        List.of(engineeringContract("A1", "100.00", "40.00",
                                        new long[]{10, 20, 30}),
                                engineeringContract("A2", "50.00", "20.00",
                                        new long[]{40}))),
                new EngineeringRun.CompanySpec(ENGINE_B, "eng-b", "ENG-B", "ENG-B-CUS",
                        "ENG-B-SUP", "ENG-B-OPS", 60_000L, 5_000,
                        engineeringCharter(ENGINE_B, "eb",
                                List.of("rita", "ed", "sam", "bea", "nina")),
                        List.of(engineeringContract("B1", "200.00", "100.00",
                                new long[]{5, 5}))));
        return new EngineeringRun.Config("schema18 实验参数，未按现实校准", companies);
    }

    /**
     * 打开 schema18 工程档：同一 WorldRuntime 打开同一 WorldJournal，保留媒体/采访/对话/证据/社交，
     * 额外按公司持有工程经营与董事会；开局不移动现金、不代签新合同、不发起模型调用。
     *
     * @param journal 独立日志文件
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldWebHost.Opened openGroups(Path journal) throws Exception {
        boolean freshArchive = !java.nio.file.Files.exists(journal)
                || java.nio.file.Files.size(journal) == 0L;
        WorldRuntime runtime = groupsRuntime(journal);
        if (freshArchive) {
            // 与工程档同一开局公开说明/机会链；已有档重开不新增，不虚构经理、不放宽 BOARD 发文
            publishOpeningDocuments(runtime);
        }
        return new WorldWebHost.Opened(runtime, groupsManifest().runId());
    }

    public static WorldWebHost.Opened openEngineering(Path journal) throws Exception {
        // 是否为新档必须在打开日志之前确定：已存在的日志（哪怕世界时点仍是 0）不算新档，
        // 重开不得偷偷新增开局说明。相同新档重开时，原已排程命令仍在命令表里，保持幂等。
        boolean freshArchive = !java.nio.file.Files.exists(journal)
                || java.nio.file.Files.size(journal) == 0L;
        RunManifest manifest = engineeringManifest();
        WorldRuntime runtime = engineeringRuntime(journal, engineeringConfig(),
                engineeringOpenings());
        if (freshArchive) {
            publishOpeningDocuments(runtime);
        }
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    /**
     * 新空档的开局公开发表：公司治理说明 + 冻结工程机会。只在新档调用；已有档重开不新增。
     *
     * <p>工程档与群组档共用同一实现，保证两类档的公开说明/机会资料一致；旧 social 等档不调用。
     * 仍走普通公开发布入口，不改变 BOARD/周期授权，也不虚构经理。</p>
     */
    private static void publishOpeningDocuments(WorldRuntime runtime) throws Exception {
        runtime.schedulePublishDocument("gov-note:" + COMPANY, "gov-note-task:" + COMPANY,
                150L, GOVERNANCE_NOTE_DOC, INVESTOR,
                InformationModel.Audience.publicAudience(),
                "制造公司开局治理说明（模拟冻结配置）", governanceNoteBody(),
                "开局冻结章程与任免规则（模拟，不是真实披露）",
                GOVERNANCE_NOTE_READ_MILLIS, List.of());
        for (EngineeringRun.CompanySpec spec : engineeringConfig().companies()) {
            for (EngineeringRun.ContractPlan plan : spec.contracts()) {
                runtime.schedulePublishDocument("opp:" + spec.companyPartyId() + ":"
                        + plan.contractId(), "opp-task:" + spec.companyPartyId() + ":"
                        + plan.contractId(), 100L,
                        "engineering-opportunity:" + plan.contractId(), INVESTOR,
                        InformationModel.Audience.publicAudience(),
                        "工程机会 " + plan.contractId(),
                        opportunityBody(spec, plan), "冻结工程机会", 1_000L,
                        List.of());
            }
        }
    }

    /**
     * 开局治理说明正文：只由冻结章程派生，公开字段仅限公司标识、席位/法定人数、
     * 开局经理空缺与既有任免规则。
     *
     * <p>不含开局机构任职列表、私预算、账户余额、隐藏评分或任何候选人资历/业绩；
     * 明确标注是模拟冻结配置。这是公开说明，任何人可从自己的目录取用并阅读。</p>
     *
     * @return 说明正文
     */
    private static String governanceNoteBody() {
        BoardModel.Charter charter = charter();
        StringBuilder seats = new StringBuilder();
        for (BoardModel.Seat seat : charter.seats()) {
            if (seats.length() > 0) {
                seats.append('、');
            }
            seats.append(seat.seatId()).append(':').append(seat.partyId());
            if (seat.seatId().equals(charter.chairSeatId())) {
                seats.append("(主席)");
            }
        }
        return "模拟冻结配置（开局夹具，不是真实公司披露）\n"
                + "公司=" + COMPANY + "\n"
                + "章程席位=" + seats + "\n"
                + "法定人数=" + charter.quorum() + "\n"
                + "公司经理=空缺（开局没有 COMPANY_MANAGER 任职，需董事会任免）\n"
                + "任免规则（已接受机制）=主席召集任免会议→董事到会并读材料→表决→实施后按请求的"
                + "生效时点写入真实任职；不自动任命，也不产生无限期角色。\n";
    }

    /**
     * 工程机会资料的正文：只写冻结配置里已有的真实条款，读者能据此核对自己将签什么。
     *
     * <p>数量单位、单价、单位成本、预收/质保比例与付款期限全部取自 {@link EngineeringRun.ContractPlan}
     * 与所属 {@link EngineeringRun.CompanySpec} 的实际字段，格式与结构化输入一致（单价两位小数）。
     * 正文不写客户期间预算、客户现金或未来客户回应计划，避免把 God 才能算的私有事实当成经理已知。</p>
     *
     * @param spec 所属工程公司（只用到公司标识与所有者标识）
     * @param plan 该公司的冻结合同计划
     * @return 供实际阅读的中文正文
     */
    private static String opportunityBody(EngineeringRun.CompanySpec spec,
            EngineeringRun.ContractPlan plan) {
        StringBuilder units = new StringBuilder();
        for (int i = 0; i < plan.trancheUnits().size(); i++) {
            if (i > 0) {
                units.append('、');
            }
            units.append('第').append(i + 1).append("阶段 ").append(plan.trancheUnits().get(i))
                    .append(" 件");
        }
        return "实验预置合同机会（来源：冻结工程配置，不是真实客户要约）\n"
                + "所属公司：" + spec.companyPartyId() + "（所有者 " + spec.companyOwnerId() + "）\n"
                + "合同标识：" + plan.contractId() + "\n"
                + "数量单位：" + units + "\n"
                + "单价：" + fixed(plan.unitPrice()) + " 元/单位\n"
                + "单位成本：" + plan.unitCost().toPlainString() + " 元/单位\n"
                + "预收比例：" + basisPoints(plan.advanceBp()) + "；质保扣留比例："
                + basisPoints(plan.retentionBp()) + "\n"
                + "付款期：" + plan.netDays() + " 天；质保期：" + plan.retentionNetDays() + " 天\n"
                + "合法动作前提：本人须先实际阅读本资料才能签约；开工按阶段数量报工；"
                + "完工后须客户真实验收回应才能开票；开票到期后才可请求付款。\n"
                + "本资料不含客户预算、客户现金或未来回应计划，不得据此推测。";
    }

    /** 金额两位数（与结构化输入的金额口径一致）。 */
    private static String fixed(java.math.BigDecimal value) {
        return value == null ? "0.00"
                : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** 万分比转百分比文本（2000 → 20%）。 */
    private static String basisPoints(int value) {
        return java.math.BigDecimal.valueOf(value, 2).stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * 工程档真实构造接缝：同一套登记/配置，只替换工程配置，供受控测试使用。
     *
     * @param journal     独立日志
     * @param engineering 工程冻结配置
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldRuntime engineeringRuntime(Path journal, EngineeringRun.Config engineering)
            throws Exception {
        return engineeringRuntime(journal, engineering, engineeringOpenings());
    }

    /**
     * 工程档真实构造接缝：可替换开局账户，用于验证非法初始金额在写盘前被拒绝。
     *
     * @param journal     独立日志
     * @param engineering 工程冻结配置
     * @param openings    开局现金清单
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldRuntime engineeringRuntime(Path journal, EngineeringRun.Config engineering,
            List<CashLedger.OpeningAccount> openings) throws Exception {
        return engineeringRuntime(journal, engineering, openings, engineeringDecisionConfig());
    }

    /**
     * 受控测试接缝：18 档可替换决定配置（scripted 桩或 live 由配置自证，不注入凭据）。
     *
     * @param journal     独立日志
     * @param engineering 工程冻结配置
     * @param decision    决定配置；scripted 档必须无 live profile
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldRuntime engineeringRuntime(Path journal, EngineeringRun.Config engineering,
            DecisionModel.Config decision) throws Exception {
        return engineeringRuntime(journal, engineering, engineeringOpenings(), decision);
    }

    /**
     * 受控测试接缝：同时替换开局账户与决定配置。
     *
     * @param journal     独立日志
     * @param engineering 工程冻结配置
     * @param openings    开局现金清单
     * @param decision    决定配置
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldRuntime engineeringRuntime(Path journal, EngineeringRun.Config engineering,
            List<CashLedger.OpeningAccount> openings, DecisionModel.Config decision)
            throws Exception {
        return engineeringRuntime(journal, engineering, openings, decision, List.of());
    }

    /**
     * 受控测试接缝：在原任职表上追加开局即有效的任职（用于章程公司经理等真实岗位）。
     *
     * @param journal     独立日志
     * @param engineering 工程冻结配置
     * @param extra       追加任职（同样写入开局配置，重开必须一致）
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldRuntime engineeringRuntimeWithAppointments(Path journal,
            EngineeringRun.Config engineering, List<RoleAccess.Appointment> extra)
            throws Exception {
        return engineeringRuntime(journal, engineering, engineeringOpenings(),
                engineeringDecisionConfig(), extra);
    }

    private static WorldRuntime engineeringRuntime(Path journal, EngineeringRun.Config engineering,
            List<CashLedger.OpeningAccount> openings, DecisionModel.Config decision,
            List<RoleAccess.Appointment> extra) throws Exception {
        OpeningRegistry registry = engineeringRegistry();
        List<RoleAccess.Appointment> appointments = new ArrayList<>(roles().appointments());
        appointments.addAll(extra);
        long end = 30L * 86_400_000L;
        for (int i = 0; i < MEDIA_PERSONS.size(); i++) {
            appointments.add(new RoleAccess.Appointment("MEDIA-" + i, MEDIA_PERSONS.get(i),
                    i < 2 ? "wire" : "investigative",
                    i % 2 == 0 ? RoleAccess.Role.JOURNALIST : RoleAccess.Role.EDITOR,
                    0L, end, null));
        }
        List<String> readers = new ArrayList<>(information().readers());
        readers.addAll(MEDIA_PERSONS);
        readers.addAll(SOCIAL_PERSONS);
        InformationRun.Config info = new InformationRun.Config(readers, COGNITION_POLICY,
                new BeliefModel.Policy(200, 8, end, 20, 200, 3, end));
        List<InterviewRun.ContactEntry> contacts = new ArrayList<>(interviewConfig().contacts());
        for (String person : SOCIAL_PERSONS) {
            contacts.add(new InterviewRun.ContactEntry("私信", person,
                    "公开联系说明：可一对一私人消息", SOCIAL_CONTACT_DOC, 1L));
        }
        return new WorldRuntime(journal, engineeringManifest(), openings, registry,
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(),
                new RoleRun.Config(roles().mandates(), appointments), info,
                decision, null, mediaConfig(), interviewConfig(),
                dialogueConfig(), evidenceConfig(), socialConfig(), SOCIAL_BLOCK_MILLIS,
                contacts, engineering);
    }

    /** schema20 群组档运行标识（与工程档分开，旧档字节不变）。 */
    public static final String GROUPS_RUN_ID = "web-groups";
    /** 预置公开群。 */
    public static final String GROUPS_OPEN = "group:open";
    /** 预置仅邀请群。 */
    public static final String GROUPS_INVITE_ONLY = "group:invite-only";
    /** 群组加入工时。 */
    public static final long GROUPS_JOIN_MILLIS = 10L;
    /** 群组邀请工时。 */
    public static final long GROUPS_INVITE_MILLIS = 10L;
    /** 群组处理邀请工时。 */
    public static final long GROUPS_RESPOND_MILLIS = 10L;
    /** 群组退出工时。 */
    public static final long GROUPS_LEAVE_MILLIS = 10L;
    /** 群组发帖工时。 */
    public static final long GROUPS_POST_MILLIS = 20L;
    /** 群/平台完成到发布的等待。 */
    public static final long GROUPS_DELIVERY_MILLIS = 5L;
    /** 群/平台帖固定阅读耗时。 */
    public static final long GROUPS_READ_MILLIS = 10L;
    /** 平台写帖工时。 */
    public static final long GROUPS_PLATFORM_POST_MILLIS = 15L;
    /** 平台目录上限。 */
    public static final int GROUPS_FEED_LIMIT = 20;

    /**
     * 群组冻结配置：两个三人群，初始成员是明确的开局禀赋。
     *
     * <p>公开群 {@code group:open}：alice 为群主，bob、carol 为初始成员；
     * 仅邀请群 {@code group:invite-only}：bob 为群主，alice 为初始成员，carol 是外人。
     * 运行期不建群、不踢人、不改历史策略。</p>
     *
     * @return 冻结群组配置
     */
    public static GroupModel.Config groupsConfig() {
        return new GroupModel.Config(List.of(
                new GroupModel.Group(GROUPS_OPEN, "alice", GroupModel.JoinPolicy.OPEN,
                        GroupModel.HistoryPolicy.JOIN_TIME, 0L, List.of("bob", "carol")),
                new GroupModel.Group(GROUPS_INVITE_ONLY, "bob", GroupModel.JoinPolicy.INVITE_ONLY,
                        GroupModel.HistoryPolicy.JOIN_TIME, 0L, List.of("alice"))),
                GROUPS_JOIN_MILLIS, GROUPS_INVITE_MILLIS, GROUPS_RESPOND_MILLIS,
                GROUPS_LEAVE_MILLIS, GROUPS_POST_MILLIS, GROUPS_DELIVERY_MILLIS);
    }

    /** @return 平台写帖冻结配置 */
    public static PlatformPostModel.Config groupsPlatformConfig() {
        return new PlatformPostModel.Config(GROUPS_PLATFORM_POST_MILLIS, GROUPS_DELIVERY_MILLIS);
    }

    /** @return schema20 群组档：工程全链模块 + groups + publication */
    public static RunManifest groupsManifest() {
        Map<String, Integer> modules = new TreeMap<>(engineeringManifest().moduleVersions());
        modules.put("groups", 1);
        modules.put("publication", 1);
        return new RunManifest(GROUPS_RUN_ID, 11L,
                RunManifest.RULES_GROUPS_PLATFORM_EXPERIMENT_V1, modules,
                Map.of("profile", ENGINEERING_PROFILE), RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return 群组工具的冻结工时与群组/平台配置逐项一致 */
    public static DecisionModel.Config groupsDecisionConfig() {
        DecisionModel.Config base = engineeringDecisionConfig();
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(RoleTradingView.TOOL_GROUP_JOIN, GROUPS_JOIN_MILLIS);
        actions.put(RoleTradingView.TOOL_GROUP_INVITE, GROUPS_INVITE_MILLIS);
        actions.put(RoleTradingView.TOOL_GROUP_RESPOND, GROUPS_RESPOND_MILLIS);
        actions.put(RoleTradingView.TOOL_GROUP_LEAVE, GROUPS_LEAVE_MILLIS);
        actions.put(RoleTradingView.TOOL_GROUP_POST, GROUPS_POST_MILLIS);
        actions.put(RoleTradingView.TOOL_GROUP_CORRECT, GROUPS_POST_MILLIS);
        actions.put(RoleTradingView.TOOL_PLATFORM_POST, GROUPS_PLATFORM_POST_MILLIS);
        actions.put(RoleTradingView.TOOL_PLATFORM_CORRECT, GROUPS_PLATFORM_POST_MILLIS);
        return new DecisionModel.Config(base.providerKind(), base.providerId(),
                base.pricingVersion(), base.thinkMillisByWake(), actions, base.limits(),
                base.budget(), base.profile());
    }

    /**
     * 群组档真实构造接缝：同一套登记/任期/阅读，换 schema20 清单与群组配置。
     *
     * @param journal 独立日志
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldRuntime groupsRuntime(Path journal) throws Exception {
        return groupsRuntime(journal, groupsConfig(), groupsPlatformConfig(), GROUPS_FEED_LIMIT,
                GROUPS_READ_MILLIS);
    }

    /**
     * 群组档受控接缝：可替换群组冻结配置（例如把记者加入初始成员），其余登记/任期不变。
     *
     * @param journal 独立日志
     * @param groups  群组冻结配置
     * @return 已打开的世界
     * @throws Exception 打开失败
     */
    public static WorldRuntime groupsRuntime(Path journal, GroupModel.Config groups)
            throws Exception {
        return groupsRuntime(journal, groups, groupsPlatformConfig(), GROUPS_FEED_LIMIT,
                GROUPS_READ_MILLIS);
    }

    private static WorldRuntime groupsRuntime(Path journal, GroupModel.Config groups,
            PlatformPostModel.Config platform, int feedLimit, long readMillis) throws Exception {
        OpeningRegistry registry = engineeringRegistry();
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
        readers.addAll(SOCIAL_PERSONS);
        InformationRun.Config info = new InformationRun.Config(readers, COGNITION_POLICY,
                new BeliefModel.Policy(200, 8, end, 20, 200, 3, end));
        List<InterviewRun.ContactEntry> contacts = new ArrayList<>(interviewConfig().contacts());
        for (String person : SOCIAL_PERSONS) {
            contacts.add(new InterviewRun.ContactEntry("私信", person,
                    "公开联系说明：可一对一私人消息", SOCIAL_CONTACT_DOC, 1L));
        }
        return new WorldRuntime(journal, groupsManifest(), engineeringOpenings(), registry,
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), SYMBOL), charter(),
                new RoleRun.Config(roles().mandates(), appointments), info,
                groupsDecisionConfig(), null, mediaConfig(), interviewConfig(),
                dialogueConfig(), evidenceConfig(), socialConfig(), SOCIAL_BLOCK_MILLIS,
                contacts, engineeringConfig(), groups, platform,
                feedLimit, readMillis);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
