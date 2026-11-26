package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.*;
import edu.cufe.auction.web.WorldWebScenario;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 显式行业组合实验档：资源与已有公司共享唯一资金、调度、角色和日志。所有数值均为EXP。 */
public final class ComposedIndustryScenario {
    private ComposedIndustryScenario() { }
    /** 资源公司的组织、经营账户与经理候选人。 */
    public static final String RESOURCE = "resources", CASH = "RES-CASH", MANAGER = "resource-manager";
    /** 独立五席董事；没有预置经理。 */
    public static final List<String> DIRECTORS = List.of("resource-director-1", "resource-director-2",
            "resource-director-3", "resource-director-4", "resource-director-5");
    private static final String[][] ACCOUNTS = {
            {CASH, RESOURCE, "10000.00"}, {"RES-OPS", "resource-ops", "0.00"},
            {"RES-COAL-CUSTOMER", "resource-customer", "10000.00"}, {"RES-GRID", "resource-grid", "10000.00"},
            {"RES-CONVERTER", "resource-converter", "0.00"}, {"RES-CONTRACTOR", "resource-contractor", "0.00"},
            {"RES-DISPOSAL", "resource-disposal", "2000.00"}, {"RES-SPONSOR", "resource-sponsor", "10000.00"},
            {"RES-FOUNDER", "resource-founder", "0.00"}};

    /** @return 有界外部现金和历史资本；没有自动补足。 */
    public static List<CashLedger.OpeningAccount> openings() {
        List<CashLedger.OpeningAccount> result = new ArrayList<>(ComposedManufacturingThreeScenario.openings());
        for (String[] row : ACCOUNTS) result.add(new CashLedger.OpeningAccount(row[0], row[1], money(row[2]), "EXP资源公司/外部部门历史禀赋"));
        return List.copyOf(result);
    }

    /** @return 真实组织、人物、发行股份和持有者。 */
    public static OpeningRegistry registry() {
        var base = ComposedManufacturingThreeScenario.registry();
        var parties = new ArrayList<>(base.parties());
        var owners = new ArrayList<>(base.owners());
        for (String[] row : ACCOUNTS) {
            parties.add(new OpeningRegistry.Party(row[1], OpeningRegistry.PartyKind.ORGANIZATION, row[1]));
            owners.add(new OpeningRegistry.PropertyOwner(row[1], OpeningRegistry.OwnerKind.ORGANIZATION, row[1]));
        }
        List<String> people = new ArrayList<>(DIRECTORS); people.add(MANAGER);
        for (String person : people) parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON, person));
        var securities = new ArrayList<>(base.securities());
        securities.add(new OpeningRegistry.Security("RESOURCE", RESOURCE, 1000L, money("10.00")));
        var allocations = new ArrayList<>(base.allocations());
        allocations.add(new OpeningRegistry.ShareAllocation("RES-FOUNDER", "RESOURCE", 1000L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }

    /** @return 冻结资源、运输、发电、有限需求与真实对手账户。 */
    public static ResourceCompanyRun.Config resourceConfig() { return resourceConfig(false); }

    /** 新资源档提供固定技术许可与报价，不预批董事预算。 */
    public static ResourceCompanyRun.Config resourceConfig(boolean expansion) {
        var model = new IntegratedResourcesModel.Config(RESOURCE, CASH, "RES-OPS",
                "resource-customer", "RES-COAL-CUSTOMER", "resource-grid", "RES-GRID",
                "resource-converter", "RES-CONVERTER", "resource-contractor", "RES-CONTRACTOR",
                "resource-disposal", "RES-DISPOSAL", "resource-sponsor", "RES-SPONSOR",
                money("20000.00"), money("2.00"), money("3.00"), money("800.00"), 200L, money("50.00"),
                1000L, 5L, 1000L, 600L, money("1800.00"), 5L, money("4000.00"),
                money("100.00"), money("50.00"), new BigDecimal("0.25"),
                List.of(new IntegratedResourcesModel.Seam("resource-seam-a", 10000L, money("1.00")),
                        new IntegratedResourcesModel.Seam("resource-seam-b", 5000L, money("1.50"))), expansion
                        ? Map.of("resource-permit", new IntegratedResourcesModel.Approval("resource-permit", IntegratedResourcesModel.APPROVAL_CAPEX, 0)) : Map.of());
        return new ResourceCompanyRun.Config(RESOURCE, "RESOURCE", model, expansion ? new ResourceCompanyRun.ExpansionOffer(
                money("2000.00"), 30 * IntegratedResourcesModel.DAY_MILLIS, 3, 600, "resource-permit", false) : null);
    }

    /** @return 新行业档冻结版本；旧三制造档不获得任何资源状态。 */
    public static RunManifest manifest() { return manifest(false); }

    /** 扩产档显式版本冻结，原行业档不迁移。 */
    public static RunManifest manifest(boolean expansion) { return manifest(expansion, false); }
    private static RunManifest manifest(boolean expansion, boolean hydro) { return manifest(expansion,hydro,false); }
    private static RunManifest manifest(boolean expansion, boolean hydro, boolean baseline) {
        RunManifest base = ComposedManufacturingThreeScenario.manifest();
        Map<String, String> data = new TreeMap<>(base.dataVersions());
        data.put("profile", hydro ? "industry-hydro-v1" : expansion ? "industry-runnable-v2" : "industry-runnable-v1");
        if (hydro) { data.put("hydro-operations","v1"); data.put("hydro-decision","v1"); data.put("hydro-company",HydroIndustryConfig.COMPANY); }
        if (expansion) data.put("resource-expansion", "v1");
        data.put("operating-observation", "v2"); data.put("role-account-projection", "authorized-v1");
        data.put("company-decision", "v1"); data.put("company-introduction", "v1"); data.put("operations-decision", "v1");
        data.put("resource-operations", "v1"); data.put("resource-decision", "v1"); data.put("resource-company", RESOURCE); data.put("research-company", "pharma");
        Map<String,Integer> modules=new TreeMap<>(base.moduleVersions());
        if(baseline) {
            data.put("profile",BaselineIndustryConfig.PROFILE);
            for(String kind:List.of("digital","aged")) {data.put(kind+"-operations","v1");data.put(kind+"-decision","v1");data.put(kind+"-company",kind+"-company");}
            modules.keySet().removeAll(List.of("manufacturing","governance","dividend","manufacturing-funding"));
        }
        return new RunManifest(baseline ? "web-industry-baseline-v1" : hydro ? "web-industry-hydro-v1" : expansion ? "web-industry-v2" : "web-industry-v1", base.seed(), base.rulesVersion(), modules, data, base.modelConfigVersion());
    }

    /** 从同一冻结开局建立或恢复；不生成预算、任命或经营成功结果。 */
    public static WorldRuntime open(Path journal) throws Exception { return open(journal, false); }

    /** 新扩产预览入口；没有经理或预算捷径。 */
    public static WorldRuntime openExpanded(Path journal) throws Exception { return open(journal, true); }

    private static WorldRuntime open(Path journal, boolean expansion) throws Exception { return open(journal, expansion, false); }

    /** 水电加入同一行业世界；仍为增量调试拓扑，不等于最终十家公司。 */
    public static WorldRuntime openWithHydro(Path journal) throws Exception { return open(journal, true, true); }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro) throws Exception { return open(journal,expansion,hydro,false); }

    /** 最终十公司世界：三个制造、工程、银行、研发、资源、数字、陈酿与水电，共享唯一日志。 */
    public static WorldRuntime openBaseline(Path journal) throws Exception { return open(journal,true,true,true); }

    /** 以A股经营机制为参考的虚构十公司新档；旧档不改名。 */
    public static WorldRuntime openPrototype(Path journal) throws Exception {
        return open(journal, true, true, true, true);
    }

    /** 独立行为整改实验档；显式冻结规则，不改变旧季度或自动授予经理权限。 */
    public static WorldRuntime openBehavior(Path journal) throws Exception {
        return open(journal, true, true, true, true, true);
    }

    /** 显式互通新档，所有人物与经营在同一世界推进；原实验文件不迁移。 */
    public static WorldRuntime openConnected(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, true);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline) throws Exception {
        return open(journal, expansion, hydro, baseline, false);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, false);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, false);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, connected, false);
    }

    /** 新模型运行档：显式冻结组合公司工具修订，不改变旧档案的回执重演。 */
    public static WorldRuntime openAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected, boolean autonomous) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, connected, autonomous, false);
    }

    /** 事件唤醒实验新档；旧小时窗口档保留原输入与重演。 */
    public static WorldRuntime openEventAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true);
    }

    /** 角色依据对照新档；authorityOnly只调整权限语义，不追加经营材料。 */
    public static WorldRuntime openEvidenceAutonomous(Path journal, boolean authorityOnly) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                authorityOnly ? "authority-v1" : "evidence-v1");
    }

    /** 显式决策依据与接续实验档；旧依据版本保持冻结。 */
    public static WorldRuntime openDeliberativeAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.VERSION);
    }

    /** 权威本人动作反馈与单人故障隔离的新自主档；旧v2工厂不变。 */
    public static WorldRuntime openAccountableAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.ACCOUNTABLE_VERSION);
    }

    /** 有限外部需求的新自主经营档案。 */
    public static WorldRuntime openOperatingAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.ACCOUNTABLE_VERSION, true);
    }

    /** 相对复查与实际时延规划新档，显式使用增量存储。 */
    public static WorldRuntime openPlanningAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.PLANNING_VERSION, false, true);
    }

    /** 规划合同加有限外部需求，显式使用增量存储。 */
    public static WorldRuntime openPlanningOperating(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.PLANNING_VERSION, true, true);
    }

    /** 经理当前经营信息新档；冻结v4规划、有限需求与增量存储，不启用固定行为策略。 */
    public static WorldRuntime openInformedOperating(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.PLANNING_VERSION, true, true, true);
    }

    /** 显式v5治理结果上下文新档；旧informed工厂及其日志保持冻结。 */
    public static WorldRuntime openContextualInformedOperating(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.CONTEXTUAL_VERSION, true, true, true);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected, boolean autonomous, boolean eventDriven) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, connected, autonomous, eventDriven, null);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected, boolean autonomous, boolean eventDriven, String roleContext) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, connected, autonomous, eventDriven, roleContext, false);
    }

    /** v3 自主世界增量档；每笔提交只保留状态变化，旧工厂与档案不迁移。 */
    public static WorldRuntime openCompactAccountableAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.ACCOUNTABLE_VERSION, false, true);
    }

    /** v3 外部需求世界增量档；与普通增量档使用独立运行身份。 */
    public static WorldRuntime openCompactOperatingAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.ACCOUNTABLE_VERSION, true, true);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected, boolean autonomous, boolean eventDriven, String roleContext, boolean operating) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, connected, autonomous, eventDriven, roleContext, operating, false);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected, boolean autonomous, boolean eventDriven, String roleContext, boolean operating, boolean compact) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, connected, autonomous, eventDriven, roleContext, operating, compact, false);
    }

    /** 显式思考经营新档；只增加调用策略和60秒墙钟，保留v5经营与角色合同。
     * @param journal 新档日志路径
     * @return 已严格恢复或创建的世界
     * @throws Exception 开局或冻结合同不匹配
     */
    public static WorldRuntime openThinkingInformedOperating(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.CONTEXTUAL_VERSION, true, true, true, "web-thinking-operating-v1");
    }

    /** 打开在签约时复核制造客户可承诺现金的思考经营档。
     * @param journal 新版运行日志
     * @return 严格恢复或新建的世界
     * @throws Exception 档案与冻结规则不符或开局失败
     */
    public static WorldRuntime openFundedThinkingOperating(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.CONTEXTUAL_VERSION, true, true, true, RunManifest.FUNDED_THINKING_RUN_ID);
    }

    /**
     * 打开继承制造资金约束并允许本人限价交易的独立经营档。
     * @param journal 新版运行日志
     * @return 严格恢复或新建的世界
     * @throws Exception 档案与冻结规则不符或开局失败
     */
    public static WorldRuntime openPersonalMarketOperating(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.CONTEXTUAL_VERSION, true, true, true, PersonalMarket.RUN_ID);
    }

    /**
     * 打开按经理申请与工程客户到期义务复核的经营档。
     * @param journal 新版工程经营日志
     * @return 新建或严格重开的世界
     * @throws Exception 开局、日志或版本核验失败
     */
    public static WorldRuntime openEngineeringCustomerOperating(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.CONTEXTUAL_VERSION, true, true, true, EngineeringCustomerPolicy.RUN_ID);
    }

    /** 打开在各发行人开局公开资料中披露证券登记事实的经营档。
     * @param journal 世界日志路径
     * @return 新建或严格恢复的世界
     * @throws Exception 开局或冻结合同不匹配
     */
    public static WorldRuntime openCompanyInformationOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.COMPANY_INFORMATION_RUN_ID);
    }

    /** 打开董事与在任经理可查询跨期预算的经营档。
     * @param journal 世界日志路径
     * @return 新建或严格恢复的世界
     * @throws Exception 开局或冻结合同不匹配
     */
    public static WorldRuntime openForwardBudgetOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.FORWARD_BUDGET_RUN_ID);
    }

    /**
     * 打开在本公司经营资料中列示陈酿原料交期与付款时点的经营档。
     * @param journal 世界日志路径
     * @return 新建或严格恢复的世界
     * @throws Exception 日志或冻结规则不匹配
     */
    public static WorldRuntime openAgedOrderOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.AGED_ORDER_RUN_ID);
    }

    /**
     * 打开在同一董事输入中共享相同议事规则的经营档。
     * @param journal 世界日志路径
     * @return 新建或严格恢复的世界
     * @throws Exception 运行档或日志不相容
     */
    public static WorldRuntime openBoardProcedureOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.BOARD_PROCEDURE_RUN_ID);
    }

    /**
     * 打开发行人署名、按已批准正文逐版披露财报的经营世界。
     * @param journal 世界日志路径
     * @return 新建或严格恢复的世界
     * @throws Exception 运行档或日志不相容
     */
    public static WorldRuntime openIssuerReportOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.ISSUER_REPORT_RUN_ID);
    }

    /**
     * 打开在超限时用有列名的表展示本人目录、行情及持仓的经营世界。
     * @param journal 世界日志路径
     * @return 新建或严格恢复的世界
     * @throws Exception 运行档或日志不相容
     */
    public static WorldRuntime openRoleTableOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.ROLE_TABLE_RUN_ID);
    }

    /** 开启保留角色紧凑表格并区分水电已结物量与电费的新经营档。 */
    public static WorldRuntime openHydroSettlementOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.HYDRO_SETTLEMENT_RUN_ID);
    }

    /**
     * 开启继承水电结算视图并向个人交易者说明限价申报资格的经营档。
     * @param journal 世界日志路径
     * @return 新建或严格恢复的世界
     * @throws Exception 运行档或日志不相容
     */
    public static WorldRuntime openPersonalLimitOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.PERSONAL_LIMIT_RUN_ID);
    }

    /**
     * 打开具有跨期研究笔记、两社采编资料和自选复查时机的十公司档。
     * @param journal 独立世界日志
     * @return 新建或严格恢复的世界
     * @throws Exception 运行档或日志不相容
     */
    public static WorldRuntime openContinuingRolesOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.CONTINUING_ROLES_RUN_ID);
    }

    /**
     * 开启含家庭现金安排与个人初始自述的实验档。
     * @param journal 新建或同版本存档路径
     * @return 已初始化或严格恢复的世界
     * @throws Exception 初始化或存档不相容
     */
    public static WorldRuntime openPersonalProfilesOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.PERSONAL_PROFILES_RUN_ID);
    }

    /** 打开或恢复带有机构受托账册与四岗工作流程的个人经营档。 */
    public static WorldRuntime openFundMandateOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.FUND_MANDATE_RUN_ID);
    }

    /** 打开或恢复按双方在岗任职复核联系地址的基金档。 */
    public static WorldRuntime openFundOfficeContactsOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.FUND_CONTACTS_RUN_ID);
    }

    /**
     * 在基金工作地址档基础上，按实际任职选择机构决策场景。
     * @param journal 新世界或同版本的日志
     * @return 已初始化或严格恢复的世界
     * @throws Exception 初始化或校验失败
     */
    public static WorldRuntime openFundOfficeRoutingOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.FUND_ROUTING_RUN_ID);
    }

    /** @param journal 独立八媒体档路径 @return 启用八类采编合同的世界 @throws Exception 无法建立或恢复时抛出 */
    public static WorldRuntime openMediaDesksOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.MEDIA_DESKS_RUN_ID);
    }

    /** 打开使用同一开局的个人、机构、媒体、授信和时段规则；不启动模型调度。 */
    public static WorldRuntime openIntegratedOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.INTEGRATED_RUN_ID);
    }

    /**
     * 打开允许受控切换三家制造公司收单规则的组合经营档。
     * @param journal 新世界或同版本的日志
     * @return 已初始化或严格恢复的世界
     * @throws Exception 初始化或校验失败
     */
    public static WorldRuntime openManufacturingLifecycleOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.MANUFACTURING_LIFECYCLE_RUN_ID);
    }

    /**
     * 打开组合经营世界中的银行危机实验档。
     * @param journal 本档独立日志
     * @return 已建立或恢复的世界
     * @throws Exception 建立或恢复失败
     */
    public static WorldRuntime openBankCrisisOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.BANK_CRISIS_RUN_ID);
    }

    /** @param journal 独立交易时段实验档 @return 严格恢复或首次建立的交易世界 @throws Exception 建立或恢复失败 */
    public static WorldRuntime openMarketSessionsOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, MarketSessions.RUN_ID);
    }

    /**
     * 开启为各非银行发行人单独核算贷款和减值的经营实验档。
     * @param journal 新世界或同版本的日志
     * @return 已初始化或严格恢复的世界
     * @throws Exception 初始化或校验失败
     */
    public static WorldRuntime openIssuerCreditOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.ISSUER_CREDIT_RUN_ID);
    }

    /**
     * 开启向银行和借款人经理提供模型贷款工具的独立实验档。
     * @param journal 同版本的存档或新建路径
     * @return 已初始化或严格恢复的世界
     * @throws Exception 初始化、恢复或校验失败
     */
    public static WorldRuntime openIssuerCreditDecisionsOperating(Path journal) throws Exception {
        return openCompanyInformationOperating(journal, RunManifest.CREDIT_DECISIONS_RUN_ID);
    }

    private static WorldRuntime openCompanyInformationOperating(Path journal, String runId) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true,
                edu.cufe.auction.world.RoleDeliberation.CONTEXTUAL_VERSION, true, true, true,
                runId);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected, boolean autonomous, boolean eventDriven, String roleContext, boolean operating, boolean compact, boolean informedOperating) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, connected, autonomous,
                eventDriven, roleContext, operating, compact, informedOperating, null);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected, boolean autonomous, boolean eventDriven, String roleContext, boolean operating, boolean compact, boolean informedOperating, String thinkingRunId) throws Exception {
        boolean thinking = thinkingRunId != null;
        boolean separateInvestorAccounts = connected || RunManifest.usesContinuingRoles(thinkingRunId);
        var original = ComposedManufacturingThreeScenario.bankOpening().config().profile();
        Map<String, BankingProfile.PartyRef> parties = new TreeMap<>(original.parties());
        List<String> deposits = new ArrayList<>(original.depositAccounts());
        for (String[] row : ACCOUNTS) parties.put(row[1], new BankingProfile.PartyRef(row[1], row[1], row[0], null));
        for (String company : List.of(ComposedManufacturingThreeScenario.TECH, ComposedManufacturingThreeScenario.STRUCT,
                ComposedManufacturingThreeScenario.VERSA, ComposedPharmaScenario.PHARMA, RESOURCE)) {
            var old = parties.get(company); String account = "DEPOSIT-" + company;
            parties.put(company, new BankingProfile.PartyRef(company, old.ownerId(), old.cashAccountId(), account)); deposits.add(account);
        }
        if (hydro) {
            for (var account : HydroIndustryConfig.ACCOUNTS) {
                String deposit = account.ownerId().equals(HydroIndustryConfig.COMPANY) ? "DEPOSIT-" + account.ownerId() : null;
                parties.put(account.ownerId(), new BankingProfile.PartyRef(account.ownerId(), account.ownerId(), account.accountId(), deposit));
                if (deposit != null) deposits.add(deposit);
            }
        }
        if(baseline) {
            var accounts=BaselineIndustryConfig.openings(new ArrayList<>(openings()));
            Set<String> activeAccounts=new HashSet<>();accounts.forEach(a->activeAccounts.add(a.accountId()));
            HydroIndustryConfig.ACCOUNTS.forEach(a->activeAccounts.add(a.accountId()));
            parties.entrySet().removeIf(e->!activeAccounts.contains(e.getValue().cashAccountId()));
            for(var a:accounts) if(parties.values().stream().noneMatch(ref->ref.ownerId().equals(a.ownerId())) && original.banks().values().stream().noneMatch(ref->ref.ownerId().equals(a.ownerId()))) {
                String deposit=List.of(DigitalIndustryConfig.COMPANY,AgedIndustryConfig.COMPANY).contains(a.ownerId()) ? "DEPOSIT-"+a.ownerId():null;
                parties.put(a.ownerId(),new BankingProfile.PartyRef(a.ownerId(),a.ownerId(),a.accountId(),deposit));
                if(deposit!=null) deposits.add(deposit);
            }
        }
        var buckets=new TreeMap<>(original.buckets());
        if(baseline) {
            buckets.keySet().retainAll(parties.keySet());
            deposits.removeIf(d->parties.values().stream().noneMatch(ref->d.equals(ref.depositGl())) && original.custodians().values().stream().noneMatch(ref->d.equals(ref.liabilityId())));
        }
        if (separateInvestorAccounts) {
            parties.put("nina",new BankingProfile.PartyRef("nina","nina","HOUSE-NINA",null));
            parties.put("omar",new BankingProfile.PartyRef("omar","omar","HOUSE-OMAR",null));
        }
        if (operating) parties.put(ExternalDemandOpening.OWNER_ID, new BankingProfile.PartyRef(
                ExternalDemandOpening.OWNER_ID, ExternalDemandOpening.OWNER_ID, ExternalDemandOpening.ACCOUNT_ID, null));
        if (RunManifest.usesEngineeringCustomerRules(thinkingRunId)) {
            var customer = parties.get(EngineeringCustomerPolicy.OWNER);
            parties.put(customer.ownerId(), new BankingProfile.PartyRef(EngineeringCustomerPolicy.CUSTOMER,
                    customer.ownerId(), customer.cashAccountId(), customer.depositGl()));
        }
        if (RunManifest.usesIssuerCredit(thinkingRunId)) {
            for (String issuer : List.of(ComposedManufacturingThreeScenario.TECH, ComposedManufacturingThreeScenario.STRUCT,
                    ComposedManufacturingThreeScenario.VERSA, ComposedPharmaScenario.PHARMA, RESOURCE,
                    HydroIndustryConfig.COMPANY, DigitalIndustryConfig.COMPANY, AgedIndustryConfig.COMPANY)) {
                buckets.put(issuer, new BankingProfile.RiskBucket("book-" + issuer, "issuer-" + issuer,
                        "book-" + issuer, "impair-" + issuer));
            }
        }
        boolean crisis = RunManifest.BANK_CRISIS_RUN_ID.equals(thinkingRunId);
        if (crisis) {
            parties.put(BankCrisis.PROVIDER, new BankingProfile.PartyRef(BankCrisis.PROVIDER,BankCrisis.PROVIDER,BankCrisisOpening.PROVIDER_CASH,null));
            parties.put(BankCrisis.AUTHORITY, new BankingProfile.PartyRef(BankCrisis.AUTHORITY,BankCrisis.AUTHORITY,BankCrisisOpening.AUTHORITY_CASH,null));
        }
        var profile = new BankingProfile(original.banks(), parties, original.custodians(), buckets, original.groups(),
                baseline ? buckets.values().stream().map(BankingProfile.RiskBucket::loanGl).distinct().sorted().toList() : original.loanAccounts(),
                baseline ? buckets.values().stream().map(BankingProfile.RiskBucket::llpGl).distinct().sorted().toList() : original.llpAccounts(), deposits, crisis ? BankCrisis.PROVIDER : original.providerKey(), original.subordinatedOwnerKey(), original.backgroundBorrowerKey());
        var banking = BankOpeningRequest.of(ComposedWorldScenario.config(profile, RunManifest.usesIssuerCredit(thinkingRunId)), crisis ? BankCrisisOpening.endowment() : ComposedWorldScenario.endowment(), Map.of(), Map.of());
        var charters = new TreeMap<>(ComposedManufacturingThreeScenario.charters());
        List<BoardModel.Seat> seats = new ArrayList<>();
        for (int i = 0; i < 5; i++) seats.add(new BoardModel.Seat("resource-seat-" + i, DIRECTORS.get(i)));
        charters.put(RESOURCE, new BoardModel.Charter(RESOURCE, seats, "resource-seat-0", BoardModel.QUORUM));
        List<String> readers = new ArrayList<>(ComposedManufacturingThreeScenario.readers()); readers.addAll(DIRECTORS); readers.add(MANAGER);
        if (hydro) {
            var hydroSeats = new ArrayList<BoardModel.Seat>();
            for (int i=0;i<5;i++) hydroSeats.add(new BoardModel.Seat("hydro-seat-"+i,HydroIndustryConfig.DIRECTORS.get(i)));
            charters.put(HydroIndustryConfig.COMPANY,new BoardModel.Charter(HydroIndustryConfig.COMPANY,hydroSeats,"hydro-seat-0",BoardModel.QUORUM));
            readers.addAll(HydroIndustryConfig.DIRECTORS); readers.add(HydroIndustryConfig.MANAGER);
        }
        if(baseline) for(String company:List.of(DigitalIndustryConfig.COMPANY,AgedIndustryConfig.COMPANY)) {
            var people=BaselineIndustryConfig.directors(company);var companySeats=new ArrayList<BoardModel.Seat>();
            for(int i=0;i<5;i++) companySeats.add(new BoardModel.Seat(company+"-seat-"+i,people.get(i)));
            charters.put(company,new BoardModel.Charter(company,companySeats,company+"-seat-0",BoardModel.QUORUM));
            readers.addAll(people);readers.add(company.equals(DigitalIndustryConfig.COMPANY)?DigitalIndustryConfig.MANAGER_PERSON:AgedIndustryConfig.MANAGER_PERSON);
        }
        var decisions = ResourceDecisionTools.config(CompanyOperationsDecisionTools.config(ComposedPharmaScenario.debugDecisions(), readers));
        if (hydro) decisions = HydroDecisionTools.config(decisions);
        if(baseline) decisions=AgedDecisionTools.config(DigitalDecisionTools.config(decisions));
        if(prototype) decisions=PrototypeCompanies.decisions(decisions);
        if(edu.cufe.auction.world.RoleDeliberation.isDeliberative(roleContext))
            decisions=edu.cufe.auction.world.RoleDeliberation.lifecycleBudget(decisions);
        if (thinking) {
            var limits = decisions.limits();
            var timings = new TreeMap<>(decisions.actionMillisByTool());
            if (RunManifest.usesCreditDecisions(thinkingRunId)) IssuerCreditDecisions.addTimings(timings);
            if (crisis) timings.put("bank.crisis.act",60_000L);
            if (RunManifest.usesPersonalProfiles(thinkingRunId)) timings.put("role.library.search", 1000L);
            if (RunManifest.usesPersonalMarketRules(thinkingRunId)) {
                timings.put(PersonalMarket.SUBMIT, timings.get(RoleTradingView.TOOL_CHILD_SUBMIT));
                timings.put(PersonalMarket.CANCEL, timings.get(RoleTradingView.TOOL_CHILD_CANCEL));
            }
            decisions = new DecisionModel.Config(decisions.providerKind(), decisions.providerId(), decisions.pricingVersion(),
                    decisions.thinkMillisByWake(), timings,
                    new DecisionModel.Limits(limits.maxAttemptsPerDecision(), limits.maxInputChars(), limits.maxReplyChars(),
                            limits.perCallInputTokens(), limits.perCallOutputTokens(), limits.queueCapacity(), limits.maxInFlight(), 60_000L),
                    decisions.budget(), decisions.profile());
        }
        var openingAccounts = new ArrayList<>(openings());
        if (hydro) openingAccounts.addAll(HydroIndustryConfig.ACCOUNTS);
        var openingRegistry = hydro ? hydroRegistry(registry()) : registry();
        if(baseline) { openingAccounts=new ArrayList<>(BaselineIndustryConfig.openings(openingAccounts));openingRegistry=BaselineIndustryConfig.registry(openingRegistry,openingAccounts); }
        if (separateInvestorAccounts) {
            openingAccounts = new ArrayList<>(ConnectedWorldOpening.accounts(openingAccounts));
            openingRegistry = ConnectedWorldOpening.registry(openingRegistry);
        }
        if (operating) {
            openingAccounts.add(ExternalDemandOpening.account());
            var externalParties = new ArrayList<>(openingRegistry.parties());
            externalParties.add(new OpeningRegistry.Party(ExternalDemandOpening.OWNER_ID, OpeningRegistry.PartyKind.ORGANIZATION, "EXP 外部最终需求聚合主体"));
            var externalOwners = new ArrayList<>(openingRegistry.owners());
            externalOwners.add(new OpeningRegistry.PropertyOwner(ExternalDemandOpening.OWNER_ID, OpeningRegistry.OwnerKind.ORGANIZATION, ExternalDemandOpening.OWNER_ID));
            openingRegistry = new OpeningRegistry(externalParties, externalOwners, openingRegistry.securities(), openingRegistry.allocations());
        }
        if (RunManifest.usesEngineeringCustomerRules(thinkingRunId)) openingRegistry = EngineeringCustomerPolicy.register(openingRegistry);
        if (RunManifest.usesMediaDesks(thinkingRunId)) openingRegistry = MediaDesks.register(openingRegistry);
        if (crisis) openingRegistry = BankCrisisOpening.allocate(openingAccounts, openingRegistry);
        boolean fresh = !Files.exists(journal) || Files.size(journal) == 0;
        var runManifest = manifest(expansion, hydro, baseline);
        if (prototype) {
            openingRegistry = PrototypeCompanies.rename(openingRegistry);
            var data = new TreeMap<>(runManifest.dataVersions());
            data.put("profile", PrototypeCompanies.PROFILE);
            data.put("company-identities", "prototype-v1");
            data.put("journal-codec", "gzip-frame-v1");
            data.put("role-decision-guide", "v1");
            runManifest = new RunManifest("web-industry-prototype-v1", runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        if (behavior) {
            var data = new TreeMap<>(runManifest.dataVersions());
            data.put("behavior-policy", ManufacturingBehaviorPolicy.VERSION);
            runManifest = new RunManifest("web-industry-behavior-v1", runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        if (connected) {
            var data=new TreeMap<>(runManifest.dataVersions());data.put("behavior-policy",ConnectedWorldLoop.VERSION);
            runManifest=new RunManifest("web-connected-world-v1",runManifest.seed(),runManifest.rulesVersion(),runManifest.moduleVersions(),data,runManifest.modelConfigVersion());
        }
        if (autonomous) {
            var data = new TreeMap<>(runManifest.dataVersions());
            data.put("composed-governance-intent", "v2");
            data.put("autonomy-driver", eventDriven ? "role-events-v2" : "role-windows-v1");
            runManifest = new RunManifest(eventDriven ? "web-autonomous-world-v2" : "web-autonomous-world-v1", runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        if (roleContext != null) {
            var data = new TreeMap<>(runManifest.dataVersions());
            data.put("role-context", roleContext);
            runManifest = new RunManifest("web-autonomous-" + roleContext, runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        if (operating) {
            var data = new TreeMap<>(runManifest.dataVersions());
            data.put("external-demand", "v1");
            runManifest = new RunManifest("web-operating-autonomous-v1", runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        if (compact) {
            var data = new TreeMap<>(runManifest.dataVersions()); data.put("state-storage", "delta-v1");
            runManifest = new RunManifest(edu.cufe.auction.world.RoleDeliberation.PLANNING_VERSION.equals(roleContext)
                    ? (operating ? "web-planning-operating-v4" : "web-planning-autonomous-v4")
                    : (operating ? "web-compact-operating-v1" : "web-compact-accountable-v1"),
                    runManifest.seed(), runManifest.rulesVersion(), runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        if (informedOperating) {
            var data = new TreeMap<>(runManifest.dataVersions()); data.put("operating-information", "manager-v1");
            data.put("operating-report-order", "position-v1");
            data.put("role-input-policy", "bounded-history-v1");
            data.put("dispatch-policy", "local-input-v1");
            runManifest = new RunManifest(edu.cufe.auction.world.RoleDeliberation.CONTEXTUAL_VERSION.equals(roleContext)
                    ? "web-informed-operating-v2" : "web-informed-operating-v1", runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        if (thinking) {
            var data = new TreeMap<>(runManifest.dataVersions()); data.put("provider-policy", "deepseek-thinking-high-v1");
            if (RunManifest.usesPersonalMarketRules(thinkingRunId)) {
                data.put(PersonalMarket.POLICY, PersonalMarket.VERSION);
                data.put("belief-company-scope", "manufacturing-orders-v1");
                data.put("research-manager-view", "project-state-v1");
            }
            if (RunManifest.usesPersonalProfiles(thinkingRunId)) data.putAll(RunManifest.personalProfilePolicies());
            if (RunManifest.usesFundOfficeRouting(thinkingRunId)) data.put("fund-office-routing", "active-appointments-v1");
            if (RunManifest.usesFundContacts(thinkingRunId)) data.put("fund-office-contacts", "appointment-pair-v1");
            if (RunManifest.usesFundMandate(thinkingRunId)) {
                data.put("fund-mandate-document", "opening-register-v1");
                data.put("wait-empty-args", "allow-empty-object-v1");
            }
            if (RunManifest.usesMediaDesks(thinkingRunId)) data.put("media-template-catalog", MediaModel.EIGHT_DESKS);
            if (RunManifest.BANK_CRISIS_RUN_ID.equals(thinkingRunId)) {
                data.put("bank-crisis-authority", "finite-provider-v1");
                data.put("role-sellable-holdings", "settled-lots-v1");
            }
            if (RunManifest.usesManufacturingLifecycle(thinkingRunId)) {
                data.put(RunManifest.MANUFACTURING_LIFECYCLE_KEY, RunManifest.MANUFACTURING_LIFECYCLE_VERSION);
                data.put("fund-capital-policy", RunManifest.FIXED_FUND_CAPITAL);
                data.put("role-market-valuation", RunManifest.ROLE_MARKET_VALUATION);
            }
            if (RunManifest.usesMarketSessions(thinkingRunId)) data.put(MarketSessions.KEY, MarketSessions.VERSION);
            if (RunManifest.usesCreditDecisions(thinkingRunId)) data.put("issuer-credit-decisions", "manager-loan-v1");
            if (RunManifest.usesIssuerCredit(thinkingRunId)) data.put("issuer-credit", "separate-loan-ledgers-v1");
            if (RunManifest.usesContinuingRoles(thinkingRunId)) {
                data.putAll(RunManifest.CONTINUING_ROLE_POLICIES);
            }
            if (RunManifest.usesPersonalLimitRules(thinkingRunId)) {
                data.put(PersonalMarket.LIMIT_RULES, PersonalMarket.LIMIT_RULES_VERSION);
            }
            if (RunManifest.usesHydroSettlementView(thinkingRunId)) {
                data.put(HydroDecisionTools.SETTLEMENT_POLICY, HydroDecisionTools.SETTLEMENT_VERSION);
            }
            if (RunManifest.usesRoleTables(thinkingRunId)) {
                data.put(edu.cufe.auction.world.RoleInputTables.POLICY, edu.cufe.auction.world.RoleInputTables.VERSION);
            }
            if (RunManifest.usesIssuerReportRules(thinkingRunId)) {
                data.put("issuer-period-reports", "versioned-v1");
            }
            if (RunManifest.usesBoardProcedureRules(thinkingRunId)) {
                data.put("board-procedure-view", "shared-rules-v1");
                data.put("manager-term-clock", "registered-boundaries-v1");
            }
            if (RunManifest.usesAgedOrderRules(thinkingRunId)) {
                data.put("aged-order-terms", "next-period-v1");
            }
            if (RunManifest.usesForwardBudgetRules(thinkingRunId)) {
                data.put("forward-budget-view", "period-window-v1");
                data.put("institutional-objectives", "product-office-v1");
                data.put("catalog-read-receipts", "visible-version-v1");
            }
            if (RunManifest.usesCompanyInformationRules(thinkingRunId)) {
                data.put("listing-disclosure", "opening-register-v1");
                data.put("research-evidence", "reader-gated-v1");
                data.put("research-cash-view", "available-v1");
            }
            if (RunManifest.usesEngineeringCustomerRules(thinkingRunId)) {
                data.put(EngineeringCustomerPolicy.KEY, EngineeringCustomerPolicy.VERSION);
                data.put("digital-delivery-view", "shared-period-limits-v1");
                data.put("research-funding", "free-cash-v1");
            }
            if (RunManifest.usesFundedOperatingRules(thinkingRunId)) {
                data.put("customer-contract-policy", RunManifest.FUNDED_CONTRACT_POLICY);
                data.put("board-tool-scope", "charter-chair-v1");
            }
            runManifest = new RunManifest(thinkingRunId, runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        WorldRuntime world = ComposedWorldScenario.open(journal, runManifest, openingAccounts, openingRegistry, banking, decisions,
                new ComposedWorldScenario.Extras(charters, List.of(), readers));
        try {
            if (fresh) {
                world.initializeResearch("bootstrap", ComposedPharmaScenario.PHARMA,
                        new ResearchModel.Config(ComposedPharmaScenario.PHARMA_OWNER, ComposedPharmaScenario.PHARMA_CASH, BigDecimal.ZERO),
                        prototype ? PrototypeCompanies.researchProjects() : WorldWebScenario.researchProjects(), 1);
                world.initializeCompanyOperations("bootstrap", ComposedManufacturingThreeScenario.companies(), resourceConfig(expansion), baseline ? BaselineIndustryConfig.hydro() : hydro ? HydroIndustryConfig.config() : null, baseline ? BaselineIndustryConfig.digital():null, baseline ? BaselineIndustryConfig.aged():null);
                Map<String,List<String>> candidates = new TreeMap<>(Map.of(RESOURCE, List.of(MANAGER), ComposedManufacturingThreeScenario.TECH,
                        List.of(ComposedManufacturingThreeScenario.TECH_MANAGER), ComposedManufacturingThreeScenario.STRUCT,
                        List.of(ComposedManufacturingThreeScenario.STRUCT_MANAGER), ComposedManufacturingThreeScenario.VERSA,
                        List.of(ComposedManufacturingThreeScenario.VERSA_MANAGER), ComposedPharmaScenario.PHARMA, List.of(ComposedPharmaScenario.MANAGER_CANDIDATE)));
                if (hydro) candidates.put(HydroIndustryConfig.COMPANY,List.of(HydroIndustryConfig.MANAGER));
                if(baseline) { candidates.put(DigitalIndustryConfig.COMPANY,List.of(DigitalIndustryConfig.MANAGER_PERSON));candidates.put(AgedIndustryConfig.COMPANY,List.of(AgedIndustryConfig.MANAGER_PERSON)); }
                CompanyOpeningDocuments.publish(world,candidates);
            }
            return world;
        } catch (Exception failure) { world.close(); throw failure; }
    }

    private static OpeningRegistry hydroRegistry(OpeningRegistry base) {
        var parties=new ArrayList<>(base.parties()); var owners=new ArrayList<>(base.owners());
        for(var account:HydroIndustryConfig.ACCOUNTS) {
            parties.add(new OpeningRegistry.Party(account.ownerId(),OpeningRegistry.PartyKind.ORGANIZATION,account.ownerId()));
            owners.add(new OpeningRegistry.PropertyOwner(account.ownerId(),OpeningRegistry.OwnerKind.ORGANIZATION,account.ownerId()));
        }
        var people=new ArrayList<>(HydroIndustryConfig.DIRECTORS);people.add(HydroIndustryConfig.MANAGER);
        people.forEach(id->parties.add(new OpeningRegistry.Party(id,OpeningRegistry.PartyKind.PERSON,id)));
        var securities=new ArrayList<>(base.securities());
        securities.add(new OpeningRegistry.Security("HY1",HydroIndustryConfig.COMPANY,1000,money("10.00")));
        var allocations=new ArrayList<>(base.allocations());
        allocations.add(new OpeningRegistry.ShareAllocation("HY-FOUNDER","HY1",1000,new BigDecimal("10.0000"),OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties,owners,securities,allocations);
    }

    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
}
