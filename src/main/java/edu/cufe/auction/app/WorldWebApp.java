package edu.cufe.auction.app;

import edu.cufe.auction.web.KeySlots;
import edu.cufe.auction.web.LiveDecisionProvider;
import edu.cufe.auction.web.RunLauncher;
import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.web.WorldWebServer;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldJournalScanner;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;

/**
 * 本机世界工作台入口：唯一 writer 世界 + 受限回环 HTTP。
 *
 * <p>启动顺序刻意先初始化世界再监听：工厂失败时不会留下"半活"的端口。
 * 凭据只加载到服务端内存，用于场景调用及遮罩/显式查看，控制台只打印一次性管理员引导凭据，
 * 绝不打印 API key。启动不调用模型；只有管理员显式开始后，已启用场景才可发送有界请求。</p>
 *
 * <p>用法：{@code WorldWebApp [工作目录] [key 文件] [端口] [live|scripted|cognition|belief|media|engineering|composed|custody|research|world|world-manager|world-debug|world-ready|world-manufacturing|world-industry|world-informed-operating|world-thinking-informed-operating|world-personal-market-operating|world-engineering-customer-operating|world-company-information-operating|world-continuing-roles-operating]}；默认
 * {@code logs/web-workbench}、{@code C:/Users/JXGU/api-key/deepseek/java-stock-trade.txt}、
 * 端口 18080（被占用则明确失败，不会杀掉已有服务）；默认live用独立world-live.journal，scripted仍用world.journal，
 * 显式cognition使用world-cognition.journal与冻结的回忆实验策略，belief使用独立world-belief.journal
 * 并启用判断、核验与来源学习实验，engineering使用独立world-engineering.journal按现有工程场景与
 * 冻结工程配置启动，composed使用独立world-composed.journal按组合档（含一家真实银行）开局
 * ，research使用独立world-research.journal，在银行组合档上加入阶段研发与商业化
 * （全部模型场景默认关闭，未自动调用模型）。</p>
 */
public final class WorldWebApp {

    /** 默认工作目录。 */
    public static final String DEFAULT_WORK_DIR = "logs/web-workbench";
    /** 默认凭据文件（用户指定的本机文件，不入库）。 */
    public static final String DEFAULT_KEY_FILE =
            "C:/Users/JXGU/api-key/deepseek/java-stock-trade.txt";
    /** 默认端口。 */
    public static final int DEFAULT_PORT = 18080;
    /** 本机分支子进程端口区间（与既有 18080/18081 错开；被占用就跳过，不杀进程）。 */
    public static final int BRANCH_PORT_FROM = 18100;
    /** 分支子进程端口区间上界。 */
    public static final int BRANCH_PORT_TO = 18109;
    /** 同时运行的本机分支子进程上限。 */
    public static final int MAX_BRANCH_RUNS = 2;
    /** 分支子进程启动健康核验超时（毫秒）。 */
    public static final long BRANCH_START_TIMEOUT_MILLIS = 30_000L;

    private WorldWebApp() {
    }

    /**
     * 启动并阻塞，直至进程结束或启动失败。本机管理员可在原模块不可用时用
     * {@code --archive inspect|show 日志 [位置|end]} 查看档案，或用
     * {@code --archive export 日志 位置|end 新文件} 导出原始前缀；此模式不加载密钥或启动世界。
     *
     * @param args 工作目录 / key 文件 / 端口 / live、scripted、cognition、belief、media、interview、dialogue、evidence、social、engineering、groups、intent、composed、custody、research、world、world-manager、world-debug、world-ready、world-manufacturing、world-industry、world-hydro、world-baseline、world-prototype、world-behavior、world-connected、world-autonomous、world-events、world-accountable、world-operating、world-planning、world-planning-operating、world-informed-operating、world-thinking-informed-operating 、world-personal-market-operating、world-engineering-customer-operating 、world-company-information-operating 或 world-continuing-roles-operating，均可省略
     * @throws Exception 启动失败（凭据、目录、端口、世界）
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--archive".equals(args[0])) {
            System.out.println(WorldArchive.run(args));
            return;
        }
        Path workDir = Path.of(args.length > 0 ? args[0] : DEFAULT_WORK_DIR);
        Path keyFile = Path.of(args.length > 1 ? args[1] : DEFAULT_KEY_FILE);
        int port = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_PORT;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 0 到 65535 之间");
        }

        String mode = args.length > 3 ? args[3] : "live";
        boolean fundRoutingMode = mode.equals("world-fund-office-routing-operating");
        boolean fundContactsMode = mode.equals("world-fund-office-contacts-operating");
        boolean fundMandateMode = mode.equals("world-fund-mandate-operating");
        boolean personalProfilesMode = mode.equals("world-personal-profiles-operating");
        boolean manufacturingUpgradeMode = mode.equals("world-manufacturing-upgrade");
        boolean openFundMode = mode.equals("world-open-fund");
        boolean tenCompanyMode = mode.equals("world-ten-company-autonomy") || openFundMode || manufacturingUpgradeMode;
        boolean personPauseMode = mode.equals("world-person-pause");
        boolean issuerResolutionMode = mode.equals("world-issuer-resolution");
        boolean unpricedReferenceMode = mode.equals("world-unpriced-reference");
        boolean ownerBankValuationMode = mode.equals("world-owner-bank-valuation");
        boolean openingCopiesMode = mode.equals("world-opening-copies");
        boolean baselinePartsMode = mode.equals("world-baseline-parts-operating");
        boolean priceBandMode = mode.equals("world-price-band-operating");
        boolean issuerDividendMode = mode.equals("world-issuer-dividend-operating");
        boolean loanPromotionMode = mode.equals("world-loan-promotion-operating");
        boolean bankCrisisMode = mode.equals("world-bank-crisis-operating");
        boolean manufacturingLifecycleMode = mode.equals("world-manufacturing-lifecycle-operating");
        boolean integratedMode = mode.equals("world-integrated-operating");
        boolean mediaDesksMode = mode.equals("world-media-desks-operating");
        boolean sessionsMode = mode.equals("world-market-sessions-operating");
        boolean creditDecisionsMode = mode.equals("world-issuer-credit-decisions-operating");
        boolean issuerCreditMode = mode.equals("world-issuer-credit-operating") || creditDecisionsMode;
        boolean continuingRolesMode = mode.equals("world-continuing-roles-operating") || issuerCreditMode || fundMandateMode || fundContactsMode || fundRoutingMode || ownerBankValuationMode || unpricedReferenceMode || issuerResolutionMode || tenCompanyMode || personPauseMode || openingCopiesMode || baselinePartsMode || priceBandMode || issuerDividendMode || loanPromotionMode || bankCrisisMode || manufacturingLifecycleMode || integratedMode || personalProfilesMode || mediaDesksMode || sessionsMode;
        boolean personalLimitMode = mode.equals("world-personal-limit-operating") || continuingRolesMode;
        boolean hydroSettlementMode = mode.equals("world-hydro-settlement-operating") || personalLimitMode;
        boolean tableMode = mode.equals("world-role-table-operating") || hydroSettlementMode;
        boolean issuerReportMode = mode.equals("world-issuer-report-operating") || tableMode;
        boolean boardProcedureMode = mode.equals("world-board-procedure-operating") || issuerReportMode;
        boolean agedOrderMode = mode.equals("world-aged-order-operating") || boardProcedureMode;
        boolean forwardBudgetMode = mode.equals("world-forward-budget-operating") || agedOrderMode;
        boolean companyInformationMode = mode.equals("world-company-information-operating") || forwardBudgetMode;
        boolean engineeringCustomerMode = mode.equals("world-engineering-customer-operating") || companyInformationMode;
        boolean personalMarketMode = mode.equals("world-personal-market-operating") || engineeringCustomerMode;
        boolean thinkingInformedOperatingMode = mode.equals("world-thinking-informed-operating") || personalMarketMode;
        boolean contextualInformedOperatingMode = mode.equals("world-contextual-informed-operating");
        boolean informedOperatingMode = mode.equals("world-informed-operating");
        boolean planningMode = mode.equals("world-planning");
        boolean planningOperatingMode = mode.equals("world-planning-operating");
        boolean operatingMode = mode.equals("world-operating");
        boolean accountableMode = mode.equals("world-accountable");
        boolean autonomousMode = mode.equals("world-autonomous") || mode.equals("world-events")
                || operatingMode || accountableMode || planningMode || planningOperatingMode || informedOperatingMode || contextualInformedOperatingMode || thinkingInformedOperatingMode;
        if (!mode.equals("live") && !mode.equals("scripted") && !mode.equals("cognition")
                && !mode.equals("belief") && !mode.equals("media") && !mode.equals("interview")
                && !mode.equals("dialogue") && !mode.equals("evidence")
                && !mode.equals("social") && !mode.equals("engineering")
                && !mode.equals("groups") && !mode.equals("intent")
                && !mode.equals("composed") && !mode.equals("custody") && !mode.equals("research") && !mode.equals("world") && !mode.equals("world-manager") && !mode.equals("world-debug") && !mode.equals("world-ready") && !mode.equals("world-manufacturing") && !mode.equals("world-industry") && !mode.equals("world-hydro") && !mode.equals("world-baseline") && !(mode.equals("world-prototype") || mode.equals("world-behavior") || (mode.equals("world-connected") || autonomousMode)) && !mode.equals("world-behavior")) {
            throw new IllegalArgumentException(
                    "运行模式只能是live、scripted、cognition、belief、media、interview、dialogue、"
                            + "evidence、social、engineering、groups、intent、composed、custody、research、world、world-manager、world-debug、world-ready、world-manufacturing、world-industry、world-hydro、world-baseline、world-prototype、world-behavior、world-connected、world-autonomous、world-events、world-accountable、world-operating、world-planning、world-planning-operating、world-informed-operating、world-contextual-informed-operating、world-thinking-informed-operating 、world-personal-market-operating、world-engineering-customer-operating 、world-company-information-operating、world-continuing-roles-operating 、world-issuer-credit-operating、world-issuer-credit-decisions-operating、world-integrated-operating、world-manufacturing-lifecycle-operating、world-bank-crisis-operating、world-owner-bank-valuation、world-ten-company-autonomy、world-open-fund、world-manufacturing-upgrade、world-person-pause、world-issuer-resolution、world-unpriced-reference、world-opening-copies、world-baseline-parts-operating、world-price-band-operating、world-issuer-dividend-operating、world-loan-promotion-operating、world-media-desks-operating、world-market-sessions-operating 、world-personal-profiles-operating 、world-fund-mandate-operating 、world-fund-office-routing-operating 或 world-fund-office-contacts-operating");
        }
        boolean worldMode = mode.equals("world");
        boolean managerMode = mode.equals("world-manager");
        boolean debugMode = mode.equals("world-debug");
        boolean readyMode = mode.equals("world-ready");
        boolean manufacturingMode = mode.equals("world-manufacturing");
        boolean industryMode = mode.equals("world-industry");
        boolean baselineMode = mode.equals("world-baseline");
        boolean hydroMode = mode.equals("world-hydro");
        boolean research = mode.equals("research");
        boolean groups = mode.equals("groups");
        boolean composed = mode.equals("composed");
        boolean custody = mode.equals("custody");
        boolean intent = mode.equals("intent");
        boolean live = mode.equals("live");
        boolean cognition = mode.equals("cognition");
        boolean belief = mode.equals("belief");
        boolean media = mode.equals("media");
        boolean interview = mode.equals("interview");
        boolean dialogue = mode.equals("dialogue");
        boolean evidence = mode.equals("evidence");
        boolean social = mode.equals("social");
        boolean engineering = mode.equals("engineering");

        Files.createDirectories(workDir);

        KeySlots keys;
        try {
            keys = KeySlots.load(keyFile);
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("无法加载本机凭据文件：" + failure.getMessage());
            System.err.println("请确认文件恰好五行、每行形如 sk-...，且当前用户可读。");
            throw new IOException("Web 启动失败：无法加载本机凭据");
        }

        boolean routesAbsent = !Files.exists(workDir.resolve(SceneRoutes.FILE_NAME));
        SceneRoutes routes = SceneRoutes.open(workDir);
        if (thinkingInformedOperatingMode && routesAbsent) {
            routes.replace(0, routes.view().routes().stream().map(r -> new SceneRoutes.Route(
                    r.scene(), r.slotId(), r.model(), false, 2500, 35_000_000, 8192, 4, "lifecycle-v1",
                    "deepseek-thinking-high-v1")).toList());
        }
        if ((operatingMode || accountableMode || planningMode || planningOperatingMode || informedOperatingMode || contextualInformedOperatingMode) && routesAbsent) {
            routes.replace(0, routes.view().routes().stream().map(r -> new SceneRoutes.Route(
                    r.scene(), r.slotId(), r.model(), false, 2500, 35_000_000, 2048, 4, "lifecycle-v1")).toList());
        }
        WorldWebHost host = (mode.equals("world-prototype") || mode.equals("world-behavior") || (mode.equals("world-connected") || autonomousMode))
                ? new WorldWebHost(() -> {
                    var runtime=sessionsMode ? ComposedIndustryScenario.openMarketSessionsOperating(workDir.resolve("world-market-sessions-operating-v1.journal"))
                            : manufacturingUpgradeMode ? ComposedIndustryScenario.openManufacturingUpgradeOperating(workDir.resolve("world-manufacturing-upgrade-v1.journal"))
                            : openFundMode ? ComposedIndustryScenario.openOpenFundOperating(workDir.resolve("world-open-fund-v1.journal"))
                            : tenCompanyMode ? ComposedIndustryScenario.openStudyTenCompanyAutonomy(tenCompanyJournal(workDir))
                            : personPauseMode ? ComposedIndustryScenario.openPersonPauseOperating(workDir.resolve("world-person-pause-v1.journal"), workDir.resolve("opening-copies.json"))
                            : issuerResolutionMode ? ComposedIndustryScenario.openIssuerResolutionOperating(workDir.resolve("world-issuer-resolution-v1.journal"), workDir.resolve("opening-copies.json"))
                            : unpricedReferenceMode ? ComposedIndustryScenario.openUnpricedReferenceOperating(workDir.resolve("world-unpriced-reference-v1.journal"), workDir.resolve("opening-copies.json"))
                            : ownerBankValuationMode ? ComposedIndustryScenario.openOwnerBankValuationOperating(workDir.resolve("world-owner-bank-valuation-v1.journal"), workDir.resolve("opening-copies.json"))
                            : openingCopiesMode ? ComposedIndustryScenario.openOpeningCopiesOperating(workDir.resolve("world-opening-copies-v1.journal"), workDir.resolve("opening-copies.json"))
                            : baselinePartsMode ? ComposedIndustryScenario.openBaselinePartsOperating(workDir.resolve("world-baseline-parts-operating-v1.journal"))
                            : priceBandMode ? ComposedIndustryScenario.openPriceBandOperating(workDir.resolve("world-price-band-operating-v1.journal"))
                            : issuerDividendMode ? ComposedIndustryScenario.openIssuerDividendOperating(workDir.resolve("world-issuer-dividend-operating-v1.journal"))
                            : loanPromotionMode ? ComposedIndustryScenario.openLoanPromotionOperating(workDir.resolve("world-loan-promotion-operating-v1.journal"))
                            : bankCrisisMode ? ComposedIndustryScenario.openBankCrisisOperating(workDir.resolve("world-bank-crisis-operating-v1.journal"))
                            : manufacturingLifecycleMode ? ComposedIndustryScenario.openManufacturingLifecycleOperating(workDir.resolve("world-manufacturing-lifecycle-operating-v1.journal"))
                            : integratedMode ? ComposedIndustryScenario.openIntegratedOperating(workDir.resolve("world-integrated-operating-v1.journal"))
                            : mediaDesksMode ? ComposedIndustryScenario.openMediaDesksOperating(workDir.resolve("world-media-desks-operating-v1.journal"))
                            : fundRoutingMode ? ComposedIndustryScenario.openFundOfficeRoutingOperating(workDir.resolve("world-fund-office-routing-operating-v1.journal"))
                            : fundContactsMode ? ComposedIndustryScenario.openFundOfficeContactsOperating(workDir.resolve("world-fund-office-contacts-operating-v1.journal"))
                            : fundMandateMode ? ComposedIndustryScenario.openFundMandateOperating(workDir.resolve("world-fund-mandate-operating-v1.journal"))
                            : personalProfilesMode ? ComposedIndustryScenario.openPersonalProfilesOperating(workDir.resolve("world-personal-profiles-operating-v1.journal"))
                            : creditDecisionsMode ? ComposedIndustryScenario.openIssuerCreditDecisionsOperating(workDir.resolve("world-issuer-credit-decisions-operating-v1.journal"))
                            : issuerCreditMode ? ComposedIndustryScenario.openIssuerCreditOperating(workDir.resolve("world-issuer-credit-operating-v1.journal"))
                            : continuingRolesMode ? ComposedIndustryScenario.openContinuingRolesOperating(workDir.resolve("world-continuing-roles-operating-v1.journal"))
                            : personalLimitMode ? ComposedIndustryScenario.openPersonalLimitOperating(workDir.resolve("world-personal-limit-operating-v1.journal"))
                            : hydroSettlementMode ? ComposedIndustryScenario.openHydroSettlementOperating(workDir.resolve("world-hydro-settlement-operating-v1.journal"))
                            : tableMode ? ComposedIndustryScenario.openRoleTableOperating(workDir.resolve("world-role-table-operating-v1.journal"))
                            : issuerReportMode ? ComposedIndustryScenario.openIssuerReportOperating(workDir.resolve("world-issuer-report-operating-v1.journal"))
                            : boardProcedureMode ? ComposedIndustryScenario.openBoardProcedureOperating(workDir.resolve("world-board-procedure-operating-v1.journal"))
                            : agedOrderMode ? ComposedIndustryScenario.openAgedOrderOperating(workDir.resolve("world-aged-order-operating-v1.journal"))
                            : forwardBudgetMode ? ComposedIndustryScenario.openForwardBudgetOperating(workDir.resolve("world-forward-budget-operating-v1.journal"))
                            : companyInformationMode ? ComposedIndustryScenario.openCompanyInformationOperating(workDir.resolve("world-company-information-operating-v1.journal"))
                            : engineeringCustomerMode ? ComposedIndustryScenario.openEngineeringCustomerOperating(workDir.resolve("world-engineering-customer-operating-v1.journal"))
                            : personalMarketMode ? ComposedIndustryScenario.openPersonalMarketOperating(workDir.resolve("world-personal-market-operating-v1.journal"))
                            : thinkingInformedOperatingMode ? openThinkingOperating(workDir)
                            : contextualInformedOperatingMode ? edu.cufe.auction.world.composed.ComposedIndustryScenario.openContextualInformedOperating(workDir.resolve("world-contextual-informed-operating-v2.journal"))
                            : informedOperatingMode ? edu.cufe.auction.world.composed.ComposedIndustryScenario.openInformedOperating(workDir.resolve("world-informed-operating-v1.journal"))
                            : planningOperatingMode ? edu.cufe.auction.world.composed.ComposedIndustryScenario.openPlanningOperating(workDir.resolve("world-planning-operating-v4.journal"))
                            : planningMode ? edu.cufe.auction.world.composed.ComposedIndustryScenario.openPlanningAutonomous(workDir.resolve("world-planning-v4.journal"))
                            : operatingMode ? edu.cufe.auction.world.composed.ComposedIndustryScenario.openOperatingAutonomous(workDir.resolve("world-operating-v1.journal"))
                            : accountableMode ? edu.cufe.auction.world.composed.ComposedIndustryScenario.openAccountableAutonomous(workDir.resolve("world-accountable-v3.journal"))
                            : autonomousMode ? (mode.equals("world-events") ? edu.cufe.auction.world.composed.ComposedIndustryScenario.openEventAutonomous(workDir.resolve("world-autonomous-v2.journal")) : edu.cufe.auction.world.composed.ComposedIndustryScenario.openAutonomous(workDir.resolve("world-autonomous-v1.journal"))) : mode.equals("world-connected") ? edu.cufe.auction.world.composed.ComposedIndustryScenario.openConnected(workDir.resolve("world-connected-v1.journal")) : mode.equals("world-behavior") ? edu.cufe.auction.world.composed.ComposedIndustryScenario.openBehavior(workDir.resolve("world-industry-behavior-v1.journal")) : edu.cufe.auction.world.composed.ComposedIndustryScenario.openPrototype(workDir.resolve("world-industry-prototype-v1.journal"));
                    return new WorldWebHost.Opened(runtime,runtime.profile().manifest().runId());
                },routes,new LiveDecisionProvider(keys))
                : baselineMode
                ? new WorldWebHost(() -> {
                    var runtime=edu.cufe.auction.world.composed.ComposedIndustryScenario.openBaseline(workDir.resolve("world-industry-baseline-v1.journal"));
                    return new WorldWebHost.Opened(runtime,runtime.profile().manifest().runId());
                },routes,new LiveDecisionProvider(keys))
                : hydroMode
                ? new WorldWebHost(() -> {
                    var runtime = edu.cufe.auction.world.composed.ComposedIndustryScenario.openWithHydro(workDir.resolve("world-industry-hydro-v1.journal"));
                    return new WorldWebHost.Opened(runtime,runtime.profile().manifest().runId());
                }, routes,new LiveDecisionProvider(keys))
                : industryMode
                ? new WorldWebHost(() -> {
                    var runtime = edu.cufe.auction.world.composed.ComposedIndustryScenario.open(workDir.resolve("world-industry-v1.journal"));
                    return new WorldWebHost.Opened(runtime, runtime.profile().manifest().runId());
                }, routes, new LiveDecisionProvider(keys))
                : manufacturingMode
                ? new WorldWebHost(() -> {
                    var runtime = edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.openRunnable(
                            workDir.resolve("world-manufacturing-v1.journal"));
                    return new WorldWebHost.Opened(runtime, runtime.profile().manifest().runId());
                }, routes, new LiveDecisionProvider(keys))
                : readyMode
                ? new WorldWebHost(() -> {
                    var runtime = edu.cufe.auction.world.composed.ComposedPharmaScenario.openRunnable(
                            workDir.resolve("world-ready-v1.journal"));
                    return new WorldWebHost.Opened(runtime, runtime.profile().manifest().runId());
                }, routes, new LiveDecisionProvider(keys))
                : debugMode
                ? new WorldWebHost(() -> {
                    var runtime = edu.cufe.auction.world.composed.ComposedPharmaScenario.openDebug(
                            workDir.resolve("world-debug-v1.journal"));
                    return new WorldWebHost.Opened(runtime, runtime.profile().manifest().runId());
                }, routes, new LiveDecisionProvider(keys))
                : managerMode
                ? new WorldWebHost(() -> {
                    var runtime = edu.cufe.auction.world.composed.ComposedResearchScenario.openDebug(
                            workDir.resolve("world-manager-v3.journal"));
                    return new WorldWebHost.Opened(runtime, runtime.profile().manifest().runId());
                }, routes, new LiveDecisionProvider(keys))
                : worldMode
                ? new WorldWebHost(() -> {
                    var runtime = edu.cufe.auction.world.composed.ComposedResearchScenario.openFunded(
                            workDir.resolve("world-operating.journal"));
                    return new WorldWebHost.Opened(runtime,
                            edu.cufe.auction.world.composed.ComposedResearchScenario.fundedManifest().runId());
                }, routes, new LiveDecisionProvider(keys))
                : research
                ? new WorldWebHost(() -> {
                    var runtime = edu.cufe.auction.world.composed.ComposedResearchScenario.open(
                            workDir.resolve("world-research.journal"));
                    return new WorldWebHost.Opened(runtime,
                            edu.cufe.auction.world.composed.ComposedResearchScenario.manifest().runId());
                }, routes, new LiveDecisionProvider(keys))
                : custody
                ? new WorldWebHost(() -> {
                    var runtime = edu.cufe.auction.world.composed.ComposedCustodyScenario.open(
                            workDir.resolve("world-custody.journal"));
                    return new WorldWebHost.Opened(runtime,
                            edu.cufe.auction.world.composed.ComposedCustodyScenario.manifest().runId());
                }, routes, new LiveDecisionProvider(keys))
                : composed
                ? new WorldWebHost(() -> WorldWebScenario.openComposed(
                        workDir.resolve("world-composed.journal")),
                        routes, new LiveDecisionProvider(keys))
                : intent
                ? new WorldWebHost(() -> WorldWebScenario.openCommandIntent(
                        workDir.resolve("world-intent.journal")),
                        routes, new LiveDecisionProvider(keys))
                : groups
                ? new WorldWebHost(() -> WorldWebScenario.openGroups(
                        workDir.resolve("world-groups.journal")),
                        routes, new LiveDecisionProvider(keys))
                : engineering
                ? new WorldWebHost(() -> WorldWebScenario.openEngineering(
                        workDir.resolve("world-engineering.journal")),
                        routes, new LiveDecisionProvider(keys))
                : social
                ? new WorldWebHost(() -> WorldWebScenario.openSocial(
                        workDir.resolve("world-social.journal")),
                        routes, new LiveDecisionProvider(keys))
                : evidence
                ? new WorldWebHost(() -> WorldWebScenario.openEvidence(
                        workDir.resolve("world-evidence.journal")),
                        routes, new LiveDecisionProvider(keys))
                : dialogue
                ? new WorldWebHost(() -> WorldWebScenario.openInterviewDialogue(
                        workDir.resolve("world-dialogue.journal")),
                        routes, new LiveDecisionProvider(keys))
                : interview
                ? new WorldWebHost(() -> WorldWebScenario.openInterview(
                        workDir.resolve("world-interview.journal")),
                        routes, new LiveDecisionProvider(keys))
                : media
                ? new WorldWebHost(() -> WorldWebScenario.openMedia(
                        workDir.resolve("world-media.journal")),
                        routes, new LiveDecisionProvider(keys))
                : belief
                ? new WorldWebHost(() -> WorldWebScenario.openBelief(
                        workDir.resolve("world-belief.journal")),
                        routes, new LiveDecisionProvider(keys))
                : cognition
                ? new WorldWebHost(() -> WorldWebScenario.openCognition(
                        workDir.resolve("world-cognition.journal")),
                        routes, new LiveDecisionProvider(keys))
                : live
                ? new WorldWebHost(() -> WorldWebScenario.openLive(workDir.resolve("world-live.journal")),
                        routes, new LiveDecisionProvider(keys))
                : new WorldWebHost(() -> WorldWebScenario.open(workDir.resolve("world.journal")));

        try {
            // 先确认世界真的能打开（失败即退出，不监听端口）
            host.awaitOpening();
            host.adminSnapshot();
        } catch (Exception failure) {
            System.err.println("世界初始化失败，未启动 Web 服务：" + failure.getMessage());
            host.close();
            routes.close();
            throw new IOException("Web 启动失败：" + failure.getMessage(), failure);
        }

        String bootstrap = newBootstrap();
        RunLauncher launcher = new RunLauncher(new RunLauncher.Config(workDir.toAbsolutePath(),
                workDir.toAbsolutePath().resolve("runs"), keyFile.toAbsolutePath(),
                BRANCH_PORT_FROM, BRANCH_PORT_TO, MAX_BRANCH_RUNS, BRANCH_START_TIMEOUT_MILLIS,
                childClasspath()), host::branchProfile);
        WorldWebServer server = new WorldWebServer(host, keys, routes, port, bootstrap, null,
                System.out::println, host.runId(), null);
        server.attachLauncher(launcher);
        Path experimentReport = workDir.resolve("experiment-report.json");
        if (Files.isRegularFile(experimentReport)) {
            if (Files.size(experimentReport) > 2 * 1024 * 1024) throw new IOException("实验报告超过2MiB");
            server.attachExperimentReport(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Files.readString(experimentReport)));
        }
        if ((mode.equals("world-prototype") || mode.equals("world-behavior") || (mode.equals("world-connected") || autonomousMode))) server.enableLocalAccess();
        try {
            server.start();
        } catch (IOException failure) {
            System.err.println("端口 " + port + " 监听失败（可能已被占用）：" + failure.getMessage());
            System.err.println("未终止任何已有进程；请换端口或先自行释放。");
            launcher.close();
            server.close();
            host.close();
            routes.close();
            throw new IOException("Web 启动失败：端口不可用");
        }

        System.out.println("本机世界工作台已启动：" + server.baseUrl());
        System.out.println("工作目录：" + workDir.toAbsolutePath().normalize());
        System.out.println("场景配置：" + workDir.resolve(SceneRoutes.FILE_NAME).toAbsolutePath()
                .normalize());
        if ((mode.equals("world-prototype") || mode.equals("world-behavior") || (mode.equals("world-connected") || autonomousMode))) System.out.println("本机直达：打开上方网址即可使用，无需登录码。");
        else System.out.println("本机管理员一次性引导凭据（仅本次启动有效）：" + bootstrap);
        System.out.println("分支运行管理：本机端口 " + BRANCH_PORT_FROM + "-" + BRANCH_PORT_TO
                + "，最多 " + MAX_BRANCH_RUNS + " 个；创建不等于启动，页面按真实状态显示。");
        System.out.println(autonomousMode ? "模式：持续模型角色调试；自主世界尚未验收。服务端运行不依赖网页，失败与预算限制保留。" : mode.equals("world-connected") ? "模式：互通世界；经营、公开信息、真实阅读、独立人物、四岗与订单共同推进，实验规则尚待现实校准。" : mode.equals("world-behavior") ? "模式：制造持续经营整改实验；规则不调用模型，不补资金或成交；尚未完成整体行为验收。" : mode.equals("world-prototype") ? "模式：十个虚构公司及A股机制原型；季度实验档，模型场景默认关闭。" : baselineMode ? "模式：最终十公司基线实验档；经理和预算须真实决议，模型默认关闭。参数未作现实校准。" : hydroMode ? "模式：水电/资源/三制造同世界增量调试；不是最终十公司基线，模型默认关闭。" : industryMode
                ? "模式：资源及三制造同世界经营调试（world-industry-v1.journal）；真实任职、预算、作业与资金，模型默认关闭。"
                : manufacturingMode
                ? "模式：三制造产品经营与扩产（world-manufacturing-v1.journal）；开局资料、任职、预算和经营均按真实过程，模型默认关闭。"
                : readyMode
                ? "模式：带公开开局资料的经营调试（world-ready-v1.journal）；任职与预算须经真实决议，模型默认关闭。"
                : debugMode
                ? "模式：独立药企经营调试（world-debug-v1.journal）；全部模型场景默认关闭。"
                : managerMode
                ? "模式：经营经理工具实验（独立 world-manager-v3.journal）；有界模型动作沿真实经理权限、预算和资金执行，场景默认关闭。"
                : worldMode
                ? "模式：经营组合实验（独立 world-operating.journal）；研发、制造、工程与银行同账，模型默认关闭。"
                : research
                ? "模式：银行与研发组合实验（独立 world-research.journal）；全部模型场景默认关闭。"
                : custody
                ? "模式：银行托管组合实验（composed-v3；独立 world-custody.journal）；"
                        + "开局禀赋与参数均为实验假设，全部模型场景默认关闭。"
                : composed
                ? "模式：组合档（schema24，含一家真实银行 north；独立 world-composed.journal）；"
                        + "未自动调用模型。全部场景默认关闭，请在页面核对场景配置并明确开始一轮。"
                : intent
                ? "模式：完整指令原意实验（独立 world-intent.journal）；未自动调用模型。"
                        + "全部场景默认关闭，请在页面核对场景配置并明确开始一轮。"
                : groups
                ? "模式：群组与平台实验（schema20，独立 world-groups.journal）；未自动调用模型。"
                        + "全部场景默认关闭，请在页面核对场景配置并明确开始一轮。"
                : engineering
                ? "模式：工程经营实验（schema18，独立 world-engineering.journal）；未自动调用模型。"
                        + "全部场景默认关闭，请在页面核对场景配置并明确开始一轮。"
                : social
                ? "模式：一对一消息实验（schema17，独立 world-social.journal）；未自动调用模型。"
                        + "请在页面核对场景配置并明确开始一轮。"
                : evidence
                ? "模式：证据与共享实验（schema16，独立 world-evidence.journal）；未自动调用模型。"
                : dialogue
                ? "模式：逐轮采访实验（schema15，独立 world-dialogue.journal）；未自动调用模型。"
                : interview
                ? "模式：采访预约实验（schema14，独立 world-interview.journal）；未自动调用模型。"
                : media
                ? "模式：采编实验（schema13，独立 world-media.journal）；未自动调用模型。"
                : belief
                ? "模式：判断与学习实验（schema12，独立 world-belief.journal）；未自动调用模型。"
                        + "请在页面核对场景配置并明确开始一轮。"
                : cognition
                ? "模式：回忆实验（schema11，独立 world-cognition.journal）；未自动调用模型。"
                        + "请在页面核对场景配置并明确开始一轮。"
                : live ? "模式：真实模型实验；未自动调用。请在页面核对场景配置并明确开始一轮。"
                : "模式：脚本观察；未连接真实模型执行器。");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            launcher.close();
            server.close();
            host.close();
            routes.close();
            stopped.countDown();
        }, "world-web-shutdown"));
        stopped.await();
    }

    /**
     * 子进程 classpath：优先 surefire 给出的完整测试 classpath，其次本进程 java.class.path，
     * 并补上本类的代码位置（目录形态）。子进程只跑固定主类，不接受调用方指定 classpath。
     *
     * @return 平台分隔的 classpath 字符串
     */
    static String childClasspath() {
        String explicit = System.getProperty("surefire.test.class.path");
        String base = explicit != null && !explicit.isBlank() ? explicit
                : System.getProperty("java.class.path", "");
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (!base.isBlank()) {
            parts.add(base);
        }
        try {
            var source = WorldWebApp.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path location = Path.of(source.getLocation().toURI());
                if (Files.isDirectory(location) && !base.contains(location.toString())) {
                    parts.add(location.toString());
                }
            }
        } catch (Exception ignored) {
            // 取不到代码位置时只用已有 classpath；子进程启动失败会给出真实原因
        }
        return String.join(java.io.File.pathSeparator, parts);
    }

    static Path tenCompanyJournal(Path workDir) throws IOException {
        Path current = workDir.resolve("world-ten-company-autonomy-v3.journal");
        for (String id : java.util.List.of(RunManifest.STUDY_TEN_COMPANY_AUTONOMY_RUN_ID,
                RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID, RunManifest.ORIGINAL_TEN_COMPANY_RUN_ID)) {
            Path file = workDir.resolve("world-" + id.substring("web-".length()) + ".journal");
            if (!Files.exists(file)) continue;
            if (!Files.isRegularFile(file)) throw new IOException("十公司日志路径不是文件：" + file);
            if (Files.size(file) == 0) continue;
            var first = WorldJournalScanner.readPrefix(file, 1, null).first();
            if (first == null || !id.equals(first.runId()))
                throw new IOException("十公司日志文件名和存档版本不符：" + file);
            return file;
        }
        return current;
    }

    private static WorldRuntime openThinkingOperating(Path workDir) throws Exception {
        Path legacy = workDir.resolve("world-thinking-informed-operating-v1.journal");
        Path funded = workDir.resolve("world-thinking-informed-operating-v2.journal");
        if (Files.exists(legacy) && Files.size(legacy) > 0) {
            if (Files.exists(funded)) throw new IOException("工作目录内存在两版思考经营档案，请分别打开");
            return ComposedIndustryScenario.openThinkingInformedOperating(legacy);
        }
        return ComposedIndustryScenario.openFundedThinkingOperating(funded);
    }

    private static String newBootstrap() {
        byte[] material = new byte[24];
        new SecureRandom().nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }
}
