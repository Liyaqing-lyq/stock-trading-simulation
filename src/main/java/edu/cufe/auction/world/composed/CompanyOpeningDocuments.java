package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RunManifest;
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
        if (OpeningEntityCopies.VERSION.equals(data.get(OpeningEntityCopies.KEY)))
            companies.add(OpeningEntityCopies.fromManifest(data).company().id());
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
            BoardModel.Charter charter = board.board().charter();
            StringBuilder body = new StringBuilder("实验开局公开资料 EXP；不是真实公司披露。\n");
            if (PrototypeCompanies.PROFILE.equals(world.profile().manifest().dataVersions().get("profile"))) {
                var identity = PrototypeCompanies.find(company);
                if (identity != null) body.append(identity.publicDescription()).append('\n');
            }
            body.append("公司=").append(company).append("（").append(parties.get(company).name()).append("）\n");
            if (RunManifest.usesCompanyInformationRules(world.profile().manifest().runId())) {
                for (var security : registry.securities()) {
                    if (company.equals(security.issuerOrganizationId())) {
                        body.append("开局证券登记（实验时点 0）：代码=").append(security.symbol())
                                .append("，已发行股数=").append(security.issuedShares())
                                .append("，每股开局参考估值=").append(security.initialReferencePrice().toPlainString())
                                .append(" 元。报价和成交另见交易所记录。\n");
                    }
                }
            }
            TreeSet<String> candidates = new TreeSet<>(extraCandidates.getOrDefault(company, List.of()));
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
            world.schedulePublishDocument("opening-note:" + company, "opening-note-task:" + company,
                    1L, documentId(company), WorldWebScenario.INVESTOR,
                    InformationModel.Audience.publicAudience(),
                    parties.get(company).name() + "：开局章程与候选人（EXP）", body.toString(),
                    "来自实验开局登记和章程；发布不等于本人读过", READ_MILLIS, List.of());
        }
        if (PrototypeCompanies.PROFILE.equals(world.profile().manifest().dataVersions().get("profile"))) {
            for (var spec : world.snapshot().engineering().orElseThrow().config().companies()) {
                for (var plan : spec.contracts()) {
                    if (RunManifest.usesEngineeringCustomerRules(world.profile().manifest().runId())) {
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
        if (RunManifest.usesFundMandate(world.profile().manifest().runId())) FundMandateDocument.publish(world);
        if (RunManifest.usesPersonalProfiles(world.profile().manifest().runId())
                || RunManifest.usesMediaDesks(world.profile().manifest().runId())) {
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

    /** @param company 公司组织标识 @return 开局公开文档标识 */
    public static String documentId(String company) {
        return "company-governance:" + company + ":1";
    }
}
