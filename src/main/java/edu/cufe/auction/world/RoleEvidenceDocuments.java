package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.cufe.auction.world.composed.ComposedCompanyReport;
import edu.cufe.auction.world.composed.PrototypeCompanies;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario;
import edu.cufe.auction.world.composed.ComposedPharmaScenario;
import edu.cufe.auction.world.composed.HydroIndustryConfig;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * evidence-v1的开局资料：只从已登记人物、当时账本和可见业务配置生成。
 * 资料不是预算/任职，不携带客户私有账户、未来认证结果或预置选票。
 */
public final class RoleEvidenceDocuments {
    static final String SOURCE = "role-context/evidence-v1；冻结实验自述+唯一开局账本；截至0ms；非授权文件";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Map<String, List<String>> CANDIDATES_V1 = Map.of(
            ComposedIndustryScenario.RESOURCE, List.of(ComposedIndustryScenario.MANAGER),
            ComposedManufacturingThreeScenario.TECH, List.of(ComposedManufacturingThreeScenario.TECH_MANAGER),
            ComposedManufacturingThreeScenario.STRUCT, List.of(ComposedManufacturingThreeScenario.STRUCT_MANAGER),
            ComposedManufacturingThreeScenario.VERSA, List.of(ComposedManufacturingThreeScenario.VERSA_MANAGER),
            ComposedPharmaScenario.PHARMA, List.of(ComposedPharmaScenario.MANAGER_CANDIDATE),
            HydroIndustryConfig.COMPANY, List.of(HydroIndustryConfig.MANAGER),
            DigitalIndustryConfig.COMPANY, List.of(DigitalIndustryConfig.MANAGER_PERSON),
            AgedIndustryConfig.COMPANY, List.of(AgedIndustryConfig.MANAGER_PERSON));
    private RoleEvidenceDocuments() { }

    /** @param company 公司标识 @return 新版资料的稳定标识 */
    public static String documentId(String company) { return "role-evidence:" + company + ":1"; }

    /**
     * 新档时点0登记本公司董事及未来有效经理可读的资料；保留真实阅读耗时。
     * 候选自述是显式EXP初始条件，不宣称有不存在的工作履历或验证成绩。
     * @param world 唯一世界
     * @param candidates 已由开局工厂核验的候选人
     * @throws Exception 发布或持久化失败
     */
    public static void publish(WorldRuntime world, Map<String, List<String>> candidates) throws Exception {
        if (world.snapshot().timeMillis() != 0L
                || !edu.cufe.auction.world.RoleDeliberation.hasEvidence(world.profile().manifest().dataVersions().get("role-context"))) {
            throw new IllegalStateException("角色依据只能由evidence-v1新档开局发布");
        }
        if (!CANDIDATES_V1.equals(candidates))
            throw new IllegalArgumentException("evidence-v1候选配置与冻结版本不一致");
        var registry = world.openingRegistry().orElseThrow();
        var companies = new TreeSet<String>();
        registry.securities().forEach(s -> companies.add(s.issuerOrganizationId()));
        for (String company : companies) {
            var board = world.boardOfCompany(company).orElse(null);
            if (board == null) continue;
            world.scheduleOpeningRoleEvidence(company);
        }
    }

    /** 首次发布和恢复都从冻结配置及已复演开局账本重算，不信任日志携带的正文。 */
    static String openingBody(WorldRuntime world, String company) throws IOException {
        var board = world.boardOfCompany(company).orElseThrow();
        var body = JSON.createObjectNode();
        body.put("source", "EXP role-context/evidence-v1冻结开局声明及同一世界时点0账本；非现实履历、非未来预测");
        body.put("companyPartyId", company);
        body.put("asOfMillis", 0L);
        body.put("units", "金额为实验记账元（BigDecimal两位小数）；制造数量为件、物料为BOM单位；1期30实验日，1日86400000毫秒。");
        var identity = PrototypeCompanies.find(company);
        if (identity != null) body.put("business", identity.publicDescription());
        var dossiers = body.putArray("candidateDossiers");
        for (String person : new TreeSet<>(CANDIDATES_V1.getOrDefault(company, List.of()))) {
            var dossier = dossiers.addObject();
            dossier.put("personPartyId", person);
            dossier.put("provenance", "EXP冻结候选自述；仅身份登记可核，能力与意愿是新增场景初始声明，不是历史行为证据");
            dossier.put("interestDeclaration", "愿接受本公司有限任期经营岗位的讨论；未任命前无经营权");
            dossier.put("claimedFocus", identity == null ? "本公司经营与资金管理" : identity.business());
            dossier.put("priorEmploymentEvidence", "UNKNOWN：没有历史雇佣、经营业绩或独立能力测评，不得补造");
            dossier.put("conflict", board.board().charter().seatOf(person) == null
                    ? "本公司章程无该候选董事席位；其他关联利益未提供证明" : "本公司董事，涉及本人任免须回避");
            dossier.put("operatingObjective", "在实际资金、客户需求、交付能力和授权内制定计划；披露现金不足和亏损，允许拒单或停产；按期提交经营结果供董事独立考核");
        }
        body.put("candidateLimit", "未列自述的人物只有身份记录，不能据此推定适任；候选自述不等于独立验证，是否任命由各董事判断。");
        if (!"north".equals(company)) {
            var report = ComposedCompanyReport.of(world, company);
            var financial = body.putObject("openingFinancials");
            financial.set("assets", JSON.valueToTree(new TreeMap<>(report.assets())));
            financial.set("liabilities", JSON.valueToTree(new TreeMap<>(report.liabilities())));
            financial.set("equity", JSON.valueToTree(new TreeMap<>(report.equity())));
            financial.set("sources", JSON.valueToTree(report.sources()));
            financial.set("limitations", JSON.valueToTree(report.unsupported()));
        } else {
            body.put("financialEvidenceMissing", "银行本体资料仍须独立提供，不能把银行客户现金当作本行经营资金");
        }
        var run = world.operationsRun().orElse(null);
        if (run != null && run.company(company) != null) {
            var operations = (com.fasterxml.jackson.databind.node.ObjectNode)
                    CompanyOperationsDecisionTools.observation(run, board, company, 0L, world.snapshot().accounts());
            ManufacturingCustomerOffers.enrich(operations, run, company, 0L, world.snapshot().accounts());
            operations.remove(List.of("actionFields", "executionRule", "reports"));
            body.set("openingOperations", operations);
        } else if (run != null && run.digital() != null && company.equals(run.digital().companyPartyId())) {
            var digital = run.digital();
            body.set("openingOperations", DigitalDecisionTools.observation(digital, 0L,
                    world.snapshot().accounts().get(digital.config().model().companyAccountId()).cash(), List.of(), null));
        } else if (run != null && run.hydro() != null && company.equals(run.hydro().companyPartyId())) {
            var hydro = run.hydro();
            body.set("openingOperations", HydroDecisionTools.observation(hydro, board,
                    world.snapshot().accounts().get(hydro.config().model().companyAccountId()).cash(), List.of()));
        } else {
            body.put("operatingEvidenceLimit", "此文件仅含开局资金与业务简介；详细行业履约条件须另读取，不能据缺项编造预算或经营计划");
        }
        body.put("governanceBoundary", "无预置经理和预算。任命与预算各自走真实会议、材料、到会、提案、独立表决和实施；预算仅是承诺上限，不创造现金。该资料可作为会议材料引用，但不得声称全体已经读过或同意。");
        return JSON.writeValueAsString(body);
    }
}
