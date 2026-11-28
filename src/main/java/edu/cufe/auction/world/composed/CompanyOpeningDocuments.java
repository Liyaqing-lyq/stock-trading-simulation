package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleEvidenceDocuments;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.ResourcePrimaryEquity;
import edu.cufe.auction.world.WorldRuntime;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** 新开局的公开治理材料；记录实验配置，不替董事阅读、任命或授权。 */
public final class CompanyOpeningDocuments {
    /** 每份材料的真实阅读耗时。 */
    public static final long READ_MILLIS = 1_000L;

    private CompanyOpeningDocuments() { }

    /**
     * 为实际发行且有章程的公司发布开局资料。仅新档工厂可调用，旧档不补写。
     *
     * @param world 唯一世界，必须仍在开局时点
     * @param extraCandidates 明确登记的非董事候选人，不自动产生任职
     * @throws Exception 排程、发布或持久化失败
     */
    public static void publish(WorldRuntime world, Map<String, List<String>> extraCandidates)
            throws Exception {
        if (world.snapshot().timeMillis() != 0L) {
            throw new IllegalStateException("开局材料只能在新世界时点0登记");
        }
        OpeningRegistry registry = world.openingRegistry().orElseThrow();
        Map<String, OpeningRegistry.Party> parties = new TreeMap<>();
        registry.parties().forEach(party -> parties.put(party.partyId(), party));
        TreeSet<String> companies = new TreeSet<>();
        registry.securities().forEach(security -> companies.add(security.issuerOrganizationId()));
        var data=world.profile().manifest().dataVersions();
        if (OpeningEntityCopies.enabled(data)) {
            var copy = OpeningEntityCopies.fromManifest(data).company();
            if (copy != null) companies.add(copy.id());
        }
        // 先验证全部候选主体，再排程，避免无效配置留下半份开局材料。
        for (var entry : extraCandidates.entrySet()) {
            if (!companies.contains(entry.getKey()) || world.boardOfCompany(entry.getKey()).isEmpty()) {
                throw new IllegalArgumentException("候选人资料未绑定真实发行公司");
            }
            for (String person : entry.getValue()) {
                if (!parties.containsKey(person)
                        || parties.get(person).kind() != OpeningRegistry.PartyKind.PERSON) {
                    throw new IllegalArgumentException("候选人必须是已登记自然人");
                }
            }
        }
        for (String company : companies) {
            var board = world.boardOfCompany(company).orElse(null);
            if (board == null) continue;
            var version = charterVersion(world, company, parties, registry, board.board().charter(),
                    extraCandidates.getOrDefault(company, List.of()));
            world.schedulePublishDocument("opening-note:" + company, "opening-note-task:" + company,
                    version.publishedAt(), documentId(company), WorldWebScenario.INVESTOR,
                    InformationModel.Audience.publicAudience(), version.title(), version.body(),
                    version.publicSourceDescription(), version.readDurationMillis(), version.internalSourceIds());
        }
        if (PrototypeCompanies.PROFILE.equals(world.profile().manifest().dataVersions().get("profile"))) {
            for (var spec : world.snapshot().engineering().orElseThrow().config().companies()) {
                for (var plan : spec.contracts()) {
                    if (RunManifest.usesEngineeringCustomerRules(world.profile().manifest().policyRunId())) {
                        world.scheduleOpeningEngineeringOpportunity(plan.contractId());
                        continue;
                    }
                    String doc = "engineering-opportunity:" + plan.contractId();
                    String body = "EXP虚构工程机会：" + plan.contractId() + "；每单位价格=" + plan.unitPrice()
                            + "；单位成本=" + plan.unitCost() + "；分段工作量=" + plan.trancheUnits()
                            + "；作业单位毫秒=" + spec.unitMillis() + "；验收后账期天数=" + plan.netDays()
                            + "。机会不是合同；经理须实际阅读与签约，客户验收、开票与回款分别执行。";
                    world.schedulePublishDocument("opening-" + doc, "opening-task-" + doc, 1L, doc,
                            WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                            "栖构工程：" + plan.contractId() + "机会（EXP）", body, "冻结合同机会，未签约", READ_MILLIS, List.of());
                }
            }
        }
        if (edu.cufe.auction.world.RoleDeliberation.hasEvidence(world.profile().manifest().dataVersions().get("role-context"))) {
            edu.cufe.auction.world.RoleEvidenceDocuments.publish(world, extraCandidates);
        }
        if (RunManifest.usesFundMandate(world.profile().manifest().policyRunId())) FundMandateDocument.publish(world);
        if (RunManifest.usesPersonalProfiles(world.profile().manifest().policyRunId())
                || RunManifest.usesMediaDesks(world.profile().manifest().policyRunId())) {
            world.schedulePublishDocument("opening-personal-contacts", "opening-personal-contacts-task", 1L,
                    WorldWebScenario.SOCIAL_CONTACT_DOC, WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                    "持有人公开联系说明（EXP）", "登记的一对一私信地址：" + String.join("、", WorldWebScenario.SOCIAL_PERSONS),
                    "实验开局公开通讯录，仅提供联系渠道", WorldWebScenario.SOCIAL_READ_MILLIS, List.of());
            var hotline = WorldWebScenario.interviewConfig().contacts().get(0);
            world.schedulePublishDocument("opening-holder-interview", "opening-holder-interview-task", 1L,
                    hotline.docId(), hotline.targetPartyId(), InformationModel.Audience.publicAudience(),
                    "公开采访预约说明（EXP）", hotline.targetPartyId() + "：" + hotline.channel() + "。" + hotline.description(),
                    "实验开局公开渠道，具体采访需本人接受", WorldWebScenario.SOCIAL_READ_MILLIS, List.of());
        }
        world.advanceTo(1L);
    }

    /** 是否属于开局章程的保留文号空间；名称本身不证明原件归属。 */
    public static boolean isCharterDocument(String doc) {
        return doc != null && doc.startsWith("company-governance:");
    }

    /** 只识别实际登记的发行人和其确切章程文号。 */
    public static String registeredIssuer(OpeningRegistry registry, String doc) {
        if (registry == null || !isCharterDocument(doc)) return null;
        for (var security : registry.securities()) {
            String issuer = security.issuerOrganizationId();
            if (documentId(issuer).equals(doc)) return issuer;
        }
        return null;
    }

    /** 从开局实际董事会、人物及冻结候选重建发行人章程的首版；普通投稿不能指定来源。 */
    public static InformationModel.VersionDraft registeredVersion(WorldRuntime world, String issuer) {
        var registry = world.openingRegistry().orElseThrow();
        var parties = new TreeMap<String, OpeningRegistry.Party>();
        registry.parties().forEach(party -> parties.put(party.partyId(), party));
        var charter = world.boardOfCompany(issuer).orElseThrow().board().charter();
        return charterVersion(world, issuer, parties, registry, charter,
                RoleEvidenceDocuments.openingCandidates(issuer));
    }

    private static InformationModel.VersionDraft charterVersion(WorldRuntime world, String company,
            Map<String, OpeningRegistry.Party> parties, OpeningRegistry registry, BoardModel.Charter charter,
            List<String> extraCandidates) {
        var data = world.profile().manifest().dataVersions();
        StringBuilder body = new StringBuilder("实验开局公开资料 EXP；不是真实公司披露。\n");
        if (PrototypeCompanies.PROFILE.equals(world.profile().manifest().dataVersions().get("profile"))) {
            var identity = PrototypeCompanies.find(company);
            if (identity != null) body.append(identity.publicDescription()).append('\n');
        }
        body.append("公司=").append(company).append("（").append(parties.get(company).name()).append("）\n");
        if (RunManifest.usesCompanyInformationRules(world.profile().manifest().policyRunId())) {
            for (var security : registry.securities()) {
                if (company.equals(security.issuerOrganizationId())) {
                    body.append("开局证券登记（实验时点 0）：代码=").append(security.symbol())
                            .append("，已发行股数=").append(security.issuedShares())
                            .append("，每股开局参考估值=").append(security.initialReferencePrice() == null
                                    ? "缺少行情依据" : security.initialReferencePrice().toPlainString() + " 元")
                            .append("。报价和成交另见交易所记录。\n");
                }
            }
        }
        if (ResourcePrimaryEquity.ISSUER.equals(company) && ResourcePrimaryEquity.VERSION.equals(data.get(ResourcePrimaryEquity.POLICY)))
            body.append(ResourcePrimaryEquity.TERMS).append('\n');
        if (RunManifest.ISSUER_DECISIONS_VERSION.equals(data.get(RunManifest.ISSUER_DECISIONS_POLICY))) {
            if (world.engineeringDividendIssuer().filter(company::equals).isPresent()) {
                body.append("现金分红议题由本公司主席召集，董事可提交含每股金额、登记、除息和付款时点的方案。")
                        .append("表决通过后由主席办理授权，付款取自公司自有账户。\n");
                if (RunManifest.DIVIDEND_AMENDMENT_VERSION.equals(data.get(RunManifest.DIVIDEND_AMENDMENT_POLICY))) {
                    body.append("登记前，董事可另行提请撤销或更正已公告的方案。新议案须经表决和主席签发，")
                            .append("新公告在签发后的下一模拟毫秒发表，须早于原登记及新登记。除息安排在交易日当天 09:30 开市前。\n");
                }
                if (RunManifest.DIVIDEND_CLAIMS_VERSION.equals(data.get(RunManifest.DIVIDEND_CLAIMS_POLICY)))
                    body.append("原定付款日未能结清时，在任主席可按同一已授权方案重试全额付款，收款人、原债额和公司付款账户均依原案。个人持有人可依本人的未付凭据评估账上应收，公司的合同应付仍按原额。基金产品的持仓不构成四岗个人处分其债权的授权。\n");
            } else {
                body.append("本开局未启用本公司的现金分红议题。\n");
            }
        }
        TreeSet<String> candidates = new TreeSet<>(extraCandidates);
        body.append("章程席位：\n");
        for (BoardModel.Seat seat : charter.seats()) {
            body.append(seat.seatId()).append("=").append(seat.partyId())
                    .append("（").append(parties.get(seat.partyId()).name()).append("）")
                    .append(seat.seatId().equals(charter.chairSeatId()) ? " 主席" : "").append('\n');
            candidates.add(seat.partyId());
        }
        body.append("法定人数=").append(charter.quorum()).append('\n');
        body.append("可供讨论的已登记候选人（不代表适任或已任职）：\n");
        for (String person : candidates) {
            body.append(person).append("（").append(parties.get(person).name()).append("）\n");
        }
        body.append("任免需主席召集、真实到会、材料实读、提案、表决和实施；任职有明确起止时点。\n")
                .append("经理候选人若同时是董事，相关任免表决须回避。\n")
                .append("预算需董事会独立讨论与批准，经营只能使用实际可用资金。\n")
                .append("本文件不是预算授权、任职证明或过去经历；不得把候选人列表当作已经发生的决定。\n");
        String source = RunManifest.EDITORIAL_SOURCE_VERSION.equals(data.get(RunManifest.EDITORIAL_SOURCE_POLICY))
                ? "开局章程的机构来源：" + parties.get(company).name() + "（" + company
                    + "）；开局证券数字来自实验统一登记；登载不等于本人读过"
                : "来自实验开局登记和章程；发布不等于本人读过";
        return new InformationModel.VersionDraft(parties.get(company).name() + "：开局章程与候选人（EXP）",
                body.toString(), source, 1L, READ_MILLIS, List.of());
    }

    /** @param company 公司组织标识 @return 开局公开文档标识 */
    public static String documentId(String company) {
        return "company-governance:" + company + ":1";
    }
}
