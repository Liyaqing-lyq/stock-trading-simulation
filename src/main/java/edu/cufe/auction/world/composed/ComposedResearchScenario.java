package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankingProfile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 工程、银行、托管与研发共享唯一账户和时间轴的实验场景；不是现实校准。 */
public final class ComposedResearchScenario {
    private ComposedResearchScenario() {
    }

    /** @return 独立新档清单；旧银行组合的模块表保持不变 */
    public static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>(ComposedWorldScenario.manifest().moduleVersions());
        modules.put("custody-fill", 1);
        modules.put("research", 1);
        modules.put("commercialization", 1);
        return new RunManifest("web-composed-research", 11L,
                RunManifest.RULES_COMPOSED_RESEARCH_V1, modules,
                Map.of("profile", "composed-research-v1"), RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return 同时冻结研发与制造资金腿的独立新档；不改变既有档案 */
    public static RunManifest fundedManifest() {
        Map<String, Integer> modules = new TreeMap<>(manifest().moduleVersions());
        modules.put("manufacturing-funding", 1);
        return new RunManifest("web-composed-research-funding", 11L,
                RunManifest.RULES_COMPOSED_RESEARCH_FUNDING_V1, modules,
                Map.of("profile", "composed-research-funding-v1"), RunManifest.LIVE_DECISIONS_V1);
    }

    /**
     * 打开研发与制造资金同档的世界；经理与预算仍须经过真实任免和会议。
     * @param journal 独立日志文件
     * @return 唯一权威运行
     * @throws Exception 开局或恢复验证失败
     */
    public static WorldRuntime openFunded(Path journal) throws Exception {
        return open(journal, fundedManifest());
    }

    /**
     * 打开显式启用制造经理模型工具的新场景，不迁移已有研发/资金场景。
     * @param journal 新场景日志
     * @return 资金与动作工具同档运行
     * @throws Exception 开局或恢复失败
     */
    public static WorldRuntime openManaged(Path journal) throws Exception {
        RunManifest base = fundedManifest();
        RunManifest manifest = new RunManifest("web-operating-manager", base.seed(), base.rulesVersion(),
                base.moduleVersions(), Map.of("profile", "operating-manager-v1"), base.modelConfigVersion());
        return open(journal, manifest, edu.cufe.auction.world.ResearchDecisionTools.config(
                edu.cufe.auction.world.ManufacturingDecisionTools.config(WorldWebScenario.groupsDecisionConfig())));
    }

    /** @param journal 独立 v2 经营档 @return 含经营条款与可用新业务编号的运行 @throws Exception 开局或恢复失败 */
    public static WorldRuntime openOperating(Path journal) throws Exception {
        RunManifest base = fundedManifest();
        RunManifest manifest = new RunManifest("web-operating-manager-v2", base.seed(), base.rulesVersion(),
                base.moduleVersions(), Map.of("profile", "operating-manager-v2", "operating-observation", "v2"), base.modelConfigVersion());
        return open(journal, manifest, edu.cufe.auction.world.ResearchDecisionTools.config(
                edu.cufe.auction.world.ManufacturingDecisionTools.config(WorldWebScenario.groupsDecisionConfig())));
    }

    /** @param journal 独立调试档 @return 按真实产品读取权限投影账户的 v3 运行 @throws Exception 开局或恢复失败 */
    public static WorldRuntime openDebug(Path journal) throws Exception {
        RunManifest base = fundedManifest();
        RunManifest manifest = new RunManifest("web-operating-manager-v3", base.seed(), base.rulesVersion(),
                base.moduleVersions(), Map.of("profile", "operating-manager-v3", "operating-observation", "v2",
                        "role-account-projection", "authorized-v1"), base.modelConfigVersion());
        return open(journal, manifest, edu.cufe.auction.world.ResearchDecisionTools.config(
                edu.cufe.auction.world.ManufacturingDecisionTools.config(WorldWebScenario.groupsDecisionConfig())));
    }

    /** @return 银行组合开局加有限研发承包方账户 */
    public static List<CashLedger.OpeningAccount> openings() {
        List<CashLedger.OpeningAccount> accounts = new ArrayList<>(ComposedWorldScenario.openings());
        accounts.add(new CashLedger.OpeningAccount(WorldWebScenario.RESEARCH_CONTRACTOR,
                "research-contractor", new BigDecimal("500.00"), "研发承包方有限历史禀赋 EXP"));
        return List.copyOf(accounts);
    }

    /** @return 同时包含银行发行与研发承包方/系统发布者的唯一身份登记 */
    public static OpeningRegistry registry() {
        OpeningRegistry base = ComposedWorldScenario.registry();
        List<OpeningRegistry.Party> parties = new ArrayList<>(base.parties());
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(base.owners());
        parties.add(new OpeningRegistry.Party("research-contractor",
                OpeningRegistry.PartyKind.ORGANIZATION, "研发承包方"));
        parties.add(new OpeningRegistry.Party(WorldWebScenario.RESEARCH_SYSTEM_PUBLISHER,
                OpeningRegistry.PartyKind.PERSON, "研发系统"));
        owners.add(new OpeningRegistry.PropertyOwner("research-contractor",
                OpeningRegistry.OwnerKind.ORGANIZATION, "research-contractor"));
        owners.add(new OpeningRegistry.PropertyOwner(WorldWebScenario.RESEARCH_SYSTEM_PUBLISHER,
                OpeningRegistry.OwnerKind.PERSON, WorldWebScenario.RESEARCH_SYSTEM_PUBLISHER));
        return new OpeningRegistry(parties, owners, base.securities(), base.allocations());
    }

    /**
     * 打开独立组合档；只有全新档案登记研发计划，不暗补经理、预算或资金。
     * @param journal 独立日志文件
     * @return 唯一权威运行
     * @throws Exception 开局或恢复验证失败
     */
    public static WorldRuntime open(Path journal) throws Exception {
        return open(journal, manifest());
    }

    private static WorldRuntime open(Path journal, RunManifest manifest) throws Exception {
        return open(journal, manifest, WorldWebScenario.groupsDecisionConfig());
    }

    private static WorldRuntime open(Path journal, RunManifest manifest,
            edu.cufe.auction.world.DecisionModel.Config decisions) throws Exception {
        boolean fresh = !Files.exists(journal) || Files.size(journal) == 0L;
        WorldRuntime world = ComposedWorldScenario.open(journal, manifest, openings(), registry(),
                bankOpening(), decisions);
        try {
            if (fresh) {
                world.initializeResearch("bootstrap", WorldWebScenario.ENGINE_A,
                        new ResearchModel.Config(WorldWebScenario.ENGINE_A, "ENG-A", BigDecimal.ZERO),
                        WorldWebScenario.researchProjects(), 1);
            }
            return world;
        } catch (Exception failure) {
            world.close();
            throw failure;
        }
    }

    /** @return 显式登记研发承包方的银行画像；没有替承包方凭空开存款或发放贷款 */
    public static BankOpeningRequest bankOpening() {
        BankingProfile base = ComposedWorldScenario.profile();
        Map<String, BankingProfile.PartyRef> parties = new TreeMap<>(base.parties());
        parties.put("research-contractor", new BankingProfile.PartyRef("research-contractor",
                "research-contractor", WorldWebScenario.RESEARCH_CONTRACTOR, null));
        BankingProfile profile = new BankingProfile(base.banks(), parties, base.custodians(),
                base.buckets(), base.groups(), base.loanAccounts(), base.llpAccounts(),
                base.depositAccounts(), base.providerKey(), base.subordinatedOwnerKey(),
                base.backgroundBorrowerKey());
        return BankOpeningRequest.of(ComposedWorldScenario.config(profile),
                ComposedWorldScenario.endowment(), Map.of(), Map.of());
    }
}
