package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.OpeningRegistry;
import java.util.List;

/** A股机制参考与虚构身份。现实映射仅供上帝审计，角色材料只取虚构介绍。 */
public final class PrototypeCompanies {
    /** 显式新档；既有十公司档不改名、不补写材料。 */
    public static final String PROFILE = "industry-prototype-v1";
    private PrototypeCompanies() { }

    /** 虚构公司介绍与仅供管理员查看的原型来源。金额、规模和情景仍为EXP。 */
    public record Company(String companyId, String name, String business, String mechanism,
                          String boundary, String prototype, String stockCode, String source) {
        /** 角色可读的公开经营说明；不包含原型、现实代码和链接。 */
        public String publicDescription() {
            return "虚构公司：" + name + "。主营：" + business + "。\n经营机制：" + mechanism
                    + "。\n约束：" + boundary + "。\n规模、金额、合同、客户与事件均为EXP实验设定；不是现实业绩或投资建议。";
        }
    }

    /** 十个既定参考；不复制现实股价、财务规模或未来事件。 */
    public static List<Company> all() {
        return List.of(
            new Company("mfg-tech", "澄曜光联", "数据通信光互联组件",
                "技术代际、客户认证、元件采购、产能与交付回款；P1为光互联组件，代际1与2分别核验",
                "认证、生产、供应数量与客户预算分别约束；研发完成不等于客户认证，交付不等于现金到账",
                "中际旭创", "300308", "https://static.cninfo.com.cn/finalpage/2026-03-31/1225056459.PDF"),
            new Company("aged-company", "岚醴酒业", "需要熟成的酒类产品",
                "原料采购、生产、熟成、渠道寄售、终端售出与回款",
                "新产库存十天内不会绕过熟成期；渠道接货不自动确认为终端需求，固定费用不足形成应付",
                "贵州茅台", "600519", "https://big5.sse.com.cn/site/cht/www.sse.com.cn/disclosure/listedinfo/announcement/c/new/2026-04-17/600519_20260417_9QS4.pdf"),
            new Company("mfg-struct", "衡储能源", "动力与储能电池模组",
                "P1动力模组与P2储能模组共用产能，材料组合、客户需求、利用率、扩产和爬坡分别结算",
                "原料供给和客户预算有限；扩产先付款后建设、投产再爬坡，不直接增加销量",
                "宁德时代", "300750", "https://www.catl.com/uploads/1/file/public/202603/20260310105829_c5p2l3q9ll.pdf"),
            new Company("eng-a", "栖构工程", "建筑装饰与工程交付",
                "合同机会、签约、分段履约、客户验收、收入确认及应收回收",
                "合同总额不是现金；未验收和逾期款分别保留，不能借新订单抹掉坏账",
                "金螳螂", "002081", "https://static.cninfo.com.cn/finalpage/2026-04-29/1225235268.PDF"),
            new Company("north", "澜衡银行", "存款、结算与有限授信",
                "存贷款、利息、准备金、资本、信用损失和支付分别记账",
                "存款是银行负债，贷款是资产；客户资产不属于银行，资金或资本不足可拒绝业务",
                "招商银行", "600036", "https://www1.hkexnews.hk/listedco/listconews/sehk/2026/0327/2026032703330_c.pdf"),
            new Company("resources", "朔川资源", "资源开采与一体化发电",
                "开采、外售、内部物料转运、发电、收款、扩产和爬坡",
                "内部转运抵销、不虚增收入；产能、物量、客户现金及在建投入有限",
                "中国神华", "601088", "https://www1.hkexnews.hk/listedco/listconews/sehk/2026/0330/2026033004060_c.pdf"),
            new Company("digital-company", "云岫数智", "营销与产业数字化服务",
                "试点、客户验证、技术准入、合同交付、预收与退款",
                "技术发布不等于客户验证或收入；技术准入不是董事批准，未交付预收需承担退款义务",
                "天娱数科", "002354", "https://static.cninfo.com.cn/finalpage/2026-04-21/1225128616.PDF"),
            new Company("mfg-versa", "砺晶新材", "超硬材料与新用途产品",
                "MAT-P成熟工业材料与NEW-P新用途材料共用产能，分别受材料、认证和有限需求约束",
                "新用途验证有耗时且存在需求窗口，不能把研发预期直接变成销量",
                "黄河旋风", "600172", "https://pdf.dfcfw.com/pdf/H2_AN202604221821464567_1.pdf?t=1777034684210"),
            new Company("hydro", "清峦水电", "水库调度与水力发电",
                "来水、库容、放水、机组可用率、购电合同与检修",
                "水量守恒，检修占用机组，外部购电现金有限；仅有360实验日水文情景，不无限外推",
                "长江电力", "600900", "https://www.cypc.com.cn/cypc/attachDir/2026/05/2026051815345898336.pdf"),
            new Company("pharma", "循证医药", "创新药物研发",
                "研发预算、试验、证据审阅、继续或终止、在研资产与内部商业化批准",
                "试验结果与内部批准分离，失败不补造成功；尚无许可里程碑与商业销售，模拟批准不代表药监批准",
                "恒瑞医药", "600276", "https://static.cninfo.com.cn/finalpage/2026-03-26/1225032585.PDF")
        );
    }

    /** 按稳定公司标识查虚构介绍；未知公司返回null。 */
    public static Company find(String companyId) {
        return all().stream().filter(c -> c.companyId().equals(companyId)).findFirst().orElse(null);
    }

    /** 只替换新档案的显示身份，真实权属、股份、账户、标识完全保留。 */
    public static OpeningRegistry rename(OpeningRegistry registry) {
        var parties = registry.parties().stream().map(p -> {
            var company = find(p.partyId());
            return company == null ? p : new OpeningRegistry.Party(p.partyId(), p.kind(), company.name());
        }).toList();
        return new OpeningRegistry(parties, registry.owners(), registry.securities(), registry.allocations());
    }
    /** 新档研发阶段保留长耗时；均为EXP，不把三日调试夹具解释为现实药审。 */
    public static List<edu.cufe.auction.world.ResearchModel.ProjectSpec> researchProjects() {
        return edu.cufe.auction.web.WorldWebScenario.researchProjects().stream().map(p ->
                new edu.cufe.auction.world.ResearchModel.ProjectSpec(p.projectId(), p.budget(), p.contractorAccountId(), p.amortizationDays(),
                        p.stages().stream().map(stage -> new edu.cufe.auction.world.ResearchModel.StagePlan(stage.stage(), stage.cost(),
                                switch (stage.stage()) {
                                    case RESEARCH -> 45L * 86_400_000L;
                                    case DEVELOPMENT -> 180L * 86_400_000L;
                                    case REGISTRATION -> 360L * 86_400_000L;
                                }, stage.evidenceRequirement())).toList())).toList();
    }

    /** 工程使用一实验日一个作业单位；客户验收和收款仍按独立原合同推进。 */
    public static edu.cufe.auction.world.EngineeringRun.Config engineering() {
        var base = ComposedWorldScenario.baselineEngineering();
        return new edu.cufe.auction.world.EngineeringRun.Config("EXP虚构工程：一日作业单位，非现实工期校准", base.companies().stream().map(c ->
                new edu.cufe.auction.world.EngineeringRun.CompanySpec(c.companyPartyId(), c.companyOwnerId(), c.companyAccountId(),
                        c.customerAccountId(), c.supplierAccountId(), c.opsAccountId(), 86_400_000L, c.laborBp(), c.charter(), c.contracts(), c.customerPool())).toList(), base.customerEvents());
    }

    /** 有界十日模型预算，不改变任职、经营现金或提供方单场景上限。 */
    public static edu.cufe.auction.world.DecisionModel.Config decisions(edu.cufe.auction.world.DecisionModel.Config base) {
        var caps = new java.util.TreeMap<String, edu.cufe.auction.world.DecisionModel.Usage>();
        base.budget().personCap().keySet().forEach(person -> caps.put(person,
                edu.cufe.auction.world.DecisionModel.Usage.unknownCost(80, 800_000, 80_000)));
        return new edu.cufe.auction.world.DecisionModel.Config(base.providerKind(), base.providerId(), base.pricingVersion(),
                base.thinkMillisByWake(), base.actionMillisByTool(), base.limits(),
                new edu.cufe.auction.world.DecisionModel.Budget(edu.cufe.auction.world.DecisionModel.Usage.unknownCost(240, 2_000_000, 240_000), caps), base.profile());
    }

}
