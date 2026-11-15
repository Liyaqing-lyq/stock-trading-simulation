package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankingProfile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 独立研发公司组合档（{@code composed-pharma-v1}，EXP）：在既有制造、工程、银行、托管与
 * 分阶段研发之上，把研发接到<b>一家真正独立的研发公司</b>（pharma）。
 *
 * <p><b>为什么不是改名</b>：{@link ComposedResearchScenario} 把研发挂工程公司 eng-a 上，用的是
 * eng-a 的现金账户、工程董事会与工程初始权益。本档新增的 pharma 有自己的财产主体
 * （{@code pharma-owner}）、唯一经营账户（{@code RND-PHARMA}）、以 {@code PH1} 200 股
 * 10.00 对价的有限发行与持股、五席真实章程、{@code CompanyGovernanceRun} 里唯一的董事会与周期
 * 状态；开局<b>不预置经理、不预批预算</b>，一切都要走真实会议。</p>
 *
 * <p><b>现金口径</b>：pharma 的 2,000.00 是<b>注明的 EXP 历史资本</b>，与工程公司、银行、
 * 制造账户互不相干；研发付款只从 {@code RND-PHARMA} 付给有限承包方。股价与资本都是实验假设，
 * 不宣称现实估值或会计准则。</p>
 *
 * <p><b>旧档不变</b>：{@code composed-research-v1/funding-v1} 的工厂、模块表、开局与行为逐字保留；
 * 本档只通过 {@link ComposedWorldScenario.Extras} 追加自己的公司与董事。</p>
 */
public final class ComposedPharmaScenario {

    /** 运行标识（与工程研发档、银行组合档分开）。 */
    public static final String RUN_ID = "web-composed-pharma";
    /** 研发公司真实组织。 */
    public static final String PHARMA = "pharma";
    /** 研发公司财产主体（唯一经营账户属主）。 */
    public static final String PHARMA_OWNER = "pharma-owner";
    /** 研发公司唯一经营现金账户。 */
    public static final String PHARMA_CASH = "RND-PHARMA";
    /** 研发公司发起人（持股方）。 */
    public static final String PHARMA_FOUNDER = "pharma-founder";
    /** 发起人证券/持股账户（现金 0，只持 PH1）。 */
    public static final String PHARMA_SEC = "PHARMA-FOUNDER-SEC";
    /** 研发公司股份代码。 */
    public static final String PH1 = "PH1";
    /** 研发公司历史资本（EXP，与工程/制造/银行现金互不相干）。 */
    public static final String CAPITAL = "2000.00";
    /** 研发公司股初始参考价/成本（EXP）。 */
    public static final String SHARE_PRICE = "10.00";
    /** 研发公司董事（与 north 董事、制造董事是不同人物，便于验证跨公司隔离）。 */
    public static final List<String> DIRECTORS = List.of("frank", "grace", "heidi", "ivan", "judy");
    /** 研发公司经理候选人（非董事；开局没有任职，必须经真实任免会议产生）。 */
    public static final String MANAGER_CANDIDATE = "kate";

    private ComposedPharmaScenario() {
    }

    /** @return 研发+制造资金+银行组合全部模块 + 显式 {@code pharma} 的独立新档 */
    public static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>(
                ComposedResearchScenario.fundedManifest().moduleVersions());
        modules.put("pharma", 1);
        return new RunManifest(RUN_ID, 11L, RunManifest.RULES_COMPOSED_PHARMA_V1, modules,
                Map.of("profile", "composed-pharma-v1"), RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return 组合档原现金 + 承包方 500 + pharma 历史资本 2000 + 发起人持股账户 0 */
    public static List<CashLedger.OpeningAccount> openings() {
        List<CashLedger.OpeningAccount> accounts =
                new ArrayList<>(ComposedResearchScenario.openings());
        accounts.add(new CashLedger.OpeningAccount(PHARMA_CASH, PHARMA_OWNER,
                made("2000.00"), "组合档独立研发公司历史资本 EXP（不复制工程现金/初始权益）"));
        accounts.add(new CashLedger.OpeningAccount(PHARMA_SEC, PHARMA_FOUNDER, made("0.00"),
                "组合档独立研发公司发起人持股账户"));
        return List.copyOf(accounts);
    }

    /** @return 组合档原登记 + pharma 组织/财产主体/发起人 + 五名董事 + PH1 200 股 */
    public static OpeningRegistry registry() {
        OpeningRegistry base = ComposedResearchScenario.registry();
        List<OpeningRegistry.Party> parties = new ArrayList<>(base.parties());
        parties.add(new OpeningRegistry.Party(PHARMA, OpeningRegistry.PartyKind.ORGANIZATION,
                "独立研发公司（实验）"));
        parties.add(new OpeningRegistry.Party(PHARMA_FOUNDER,
                OpeningRegistry.PartyKind.ORGANIZATION, "独立研发公司发起人（实验）"));
        for (String director : DIRECTORS) {
            parties.add(new OpeningRegistry.Party(director, OpeningRegistry.PartyKind.PERSON,
                    "研发公司董事 " + director));
        }
        parties.add(new OpeningRegistry.Party(MANAGER_CANDIDATE, OpeningRegistry.PartyKind.PERSON,
                "研发公司经理候选人 kate"));
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(base.owners());
        owners.add(new OpeningRegistry.PropertyOwner(PHARMA_OWNER,
                OpeningRegistry.OwnerKind.ORGANIZATION, PHARMA));
        owners.add(new OpeningRegistry.PropertyOwner(PHARMA_FOUNDER,
                OpeningRegistry.OwnerKind.ORGANIZATION, PHARMA_FOUNDER));
        List<OpeningRegistry.Security> securities = new ArrayList<>(base.securities());
        securities.add(new OpeningRegistry.Security(PH1, PHARMA, 200L, made(SHARE_PRICE)));
        List<OpeningRegistry.ShareAllocation> allocations = new ArrayList<>(base.allocations());
        allocations.add(new OpeningRegistry.ShareAllocation(PHARMA_SEC, PH1, 200L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }

    /** @return 银行档案在原组合之上显式登记研发公司（无存款科目；不替它开存款或贷款） */
    public static BankOpeningRequest bankOpening() {
        BankingProfile base = ComposedResearchScenario.bankOpening().config().profile();
        Map<String, BankingProfile.PartyRef> parties = new TreeMap<>(base.parties());
        parties.put("pharma", new BankingProfile.PartyRef(PHARMA, PHARMA_OWNER, PHARMA_CASH,
                null));
        parties.put("pharma-founder", new BankingProfile.PartyRef(PHARMA_FOUNDER, PHARMA_FOUNDER,
                PHARMA_SEC, null));
        BankingProfile profile = new BankingProfile(base.banks(), parties, base.custodians(),
                base.buckets(), base.groups(), base.loanAccounts(), base.llpAccounts(),
                base.depositAccounts(), base.providerKey(), base.subordinatedOwnerKey(),
                base.backgroundBorrowerKey());
        BankOpening.DeclaredEndowment endowment = ComposedWorldScenario.endowment();
        return BankOpeningRequest.of(ComposedWorldScenario.config(profile), endowment, Map.of(),
                Map.of());
    }

    /** @return north + pharma 两份真实章程；pharma 主席 frank，开局没有经理任职 */
    public static Map<String, BoardModel.Charter> charters() {
        Map<String, BoardModel.Charter> charters =
                new TreeMap<>(ComposedWorldScenario.charters());
        charters.put(PHARMA, pharmaCharter());
        return Map.copyOf(charters);
    }

    /** @return 研发公司五席章程（frank 主席）；不预置任何经理或预算 */
    public static BoardModel.Charter pharmaCharter() {
        List<BoardModel.Seat> seats = new ArrayList<>();
        for (int i = 0; i < DIRECTORS.size(); i++) {
            seats.add(new BoardModel.Seat("p" + (i + 1), DIRECTORS.get(i)));
        }
        return new BoardModel.Charter(PHARMA, seats, "p1", BoardModel.QUORUM);
    }

    /** @return 研发公司董事会开会需要的资料读者（董事本人必须能真实读到材料） */
    public static List<String> readers() {
        List<String> readers = new ArrayList<>(DIRECTORS);
        readers.add(MANAGER_CANDIDATE);
        return List.copyOf(readers);
    }

    /**
     * 打开独立研发公司组合档：只有全新档案登记研发计划；不预置经理、预算、试点或完成态。
     *
     * @param journal 独立日志文件
     * @return 唯一权威运行
     * @throws Exception 开局或恢复验证失败
     */
    public static WorldRuntime open(Path journal) throws Exception {
        return open(journal, manifest(), WorldWebScenario.groupsDecisionConfig());
    }

    /** 打开显式网页调试档：经营工具、本人账户过滤及独立公司治理观察同时冻结。 */
    public static WorldRuntime openDebug(Path journal) throws Exception {
        RunManifest original = manifest();
        RunManifest debug = new RunManifest("web-pharma-debug-v1", original.seed(),
                original.rulesVersion(), original.moduleVersions(),
                Map.of("profile", "pharma-debug-v1", "operating-observation", "v2",
                        "role-account-projection", "authorized-v1", "company-decision", "v1"),
                original.modelConfigVersion());
        return open(journal, debug, debugDecisions());
    }

    /**
     * 新开局同时提供真实可读的公司章程与候选资料；不会自动任命、读完或批准预算。
     * @param journal 独立日志
     * @return 带公开开局资料的唯一世界
     * @throws Exception 开局或恢复失败
     */
    public static WorldRuntime openRunnable(Path journal) throws Exception {
        RunManifest original = manifest();
        RunManifest ready = new RunManifest("web-pharma-runnable-v1", original.seed(),
                original.rulesVersion(), original.moduleVersions(),
                Map.of("profile", "pharma-runnable-v1", "operating-observation", "v2",
                        "role-account-projection", "authorized-v1", "company-decision", "v1",
                        "company-introduction", "v1"), original.modelConfigVersion());
        boolean fresh = !Files.exists(journal) || Files.size(journal) == 0L;
        WorldRuntime world = open(journal, ready, debugDecisions());
        try {
            if (fresh) CompanyOpeningDocuments.publish(world, Map.of(PHARMA, List.of(MANAGER_CANDIDATE)));
            return world;
        } catch (Exception failure) {
            world.close();
            throw failure;
        }
    }

    static edu.cufe.auction.world.DecisionModel.Config debugDecisions() {
        var tools = edu.cufe.auction.world.ResearchDecisionTools.config(
                edu.cufe.auction.world.ManufacturingDecisionTools.config(
                        WorldWebScenario.groupsDecisionConfig()));
        Map<String, edu.cufe.auction.world.DecisionModel.Usage> caps = new TreeMap<>(tools.budget().personCap());
        for (String person : readers()) {
            caps.put(person, edu.cufe.auction.world.DecisionModel.Usage.unknownCost(20, 200_000, 40_000));
        }
        Map<String, Long> actions = new TreeMap<>(tools.actionMillisByTool());
        actions.put(edu.cufe.auction.world.ResearchDecisionTools.BOARD, 1_000L);
        var decisions = new edu.cufe.auction.world.DecisionModel.Config(tools.providerKind(),
                tools.providerId(), tools.pricingVersion(), tools.thinkMillisByWake(),
                actions, tools.limits(),
                new edu.cufe.auction.world.DecisionModel.Budget(tools.budget().runCap(), caps), tools.profile());
        return decisions;
    }

    private static WorldRuntime open(Path journal, RunManifest manifest,
            edu.cufe.auction.world.DecisionModel.Config decisions) throws Exception {
        boolean fresh = !Files.exists(journal) || Files.size(journal) == 0L;
        WorldRuntime world = ComposedWorldScenario.open(journal, manifest, openings(), registry(),
                bankOpening(), decisions, extras());
        try {
            if (fresh) {
                world.initializeResearch("bootstrap", PHARMA,
                        new ResearchModel.Config(PHARMA_OWNER, PHARMA_CASH, BigDecimal.ZERO),
                        WorldWebScenario.researchProjects(), 1);
            }
            return world;
        } catch (Exception failure) {
            world.close();
            throw failure;
        }
    }

    /** @return 显式扩展：两份章程、五名董事读者；不追加任何初始经理任职 */
    static ComposedWorldScenario.Extras extras() {
        Map<String, BoardModel.Charter> charters = new LinkedHashMap<>(charters());
        return new ComposedWorldScenario.Extras(charters, List.of(), readers());
    }

    /** 金额（2 位小数）。 */
    private static BigDecimal made(String value) {
        return ComposedWorldScenario.money(value);
    }
}
