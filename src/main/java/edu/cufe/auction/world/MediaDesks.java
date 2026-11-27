package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 八类实验采编机构。实际任职、读者选择、来源谱系和工时均由世界核执行。 */
public final class MediaDesks {
    private MediaDesks() { }

    /** 双时区试验中每版跨市场初稿或修订稿的内部编译核对工时。 */
    public static final long COMPARISON_MILLIS = 60_000L;
    private record Desk(String org, MediaModel.TemplateKind template, int sources, String author, String editor,
                        String name, String audience, String practice, long reading, long budget) { }

    private static final List<Desk> DESKS = List.of(
            new Desk("wire", MediaModel.TemplateKind.WIRE, 1, "rita", "ed", "通讯社", "跟踪当日变化的普通读者", "明确消息出处及更新时间。", 250, 240_000),
            new Desk("investigative", MediaModel.TemplateKind.INVESTIGATIVE, 2, "sam", "bea", "调查编辑部", "愿意核对多方材料的读者", "核对不同来源的矛盾并记录未获答复之处。", 2_000, 480_000),
            new Desk("markets-desk", MediaModel.TemplateKind.MARKETS, 1, "investor", "kate", "证券编辑部", "跟踪发行人披露与投资者关系的读者", "区分公司披露、采访和作者判断。", 750, 300_000),
            new Desk("vertical-desk", MediaModel.TemplateKind.VERTICAL, 2, "mfgtech-mgr", "mfgstruct-mgr", "行业价格资讯", "核对报价、订单和样本的行业读者", "说明样本、报价口径和资料日期。", 1_500, 360_000),
            new Desk("local-desk", MediaModel.TemplateKind.LOCAL, 1, "hydro-manager", "resource-manager", "区域商业报", "关心当地项目、用工和供货的读者", "注明现场或访谈范围及尚未核实的外地情况。", 600, 240_000),
            new Desk("cross-border-desk", MediaModel.TemplateKind.CROSS_BORDER, 2, "digital-manager", "aged-manager", "跨市场编辑部", "比较不同市场口径的读者", "说明两地来源、原始单位和发布时间。", 1_800, 420_000),
            new Desk("aggregator-desk", MediaModel.TemplateKind.AGGREGATOR, 1, "nina", "omar", "资讯终端", "通过标题和摘要筛选原文的读者", "汇编已公开的媒体成稿，保留原始出处，转发不增加独立来源。", 350, 210_000),
            new Desk("independent-desk", MediaModel.TemplateKind.INDEPENDENT, 1, "mfgversa-mgr", "erin", "独立作者工作室", "持续关注作者主题并可能反馈的读者", "区分个人意见、赞助关系和可核验材料，编辑独立审阅。", 900, 210_000));

    /** @param original 原开局主体 @return 增加六家机构的名册，沿用已经登记的人物 */
    public static OpeningRegistry register(OpeningRegistry original) {
        var parties = new ArrayList<>(original.parties());
        var owners = new ArrayList<>(original.owners());
        for (var desk : DESKS) {
            for (String person : List.of(desk.author(), desk.editor()))
                if (original.parties().stream().noneMatch(p -> p.partyId().equals(person) && p.kind() == OpeningRegistry.PartyKind.PERSON))
                    throw new IllegalArgumentException("媒体任职人未登记：" + person);
            if (original.parties().stream().anyMatch(p -> p.partyId().equals(desk.org()))) continue;
            parties.add(new OpeningRegistry.Party(desk.org(), OpeningRegistry.PartyKind.ORGANIZATION, desk.name()));
            owners.add(new OpeningRegistry.PropertyOwner(desk.org(), OpeningRegistry.OwnerKind.ORGANIZATION, desk.org()));
        }
        return new OpeningRegistry(parties, owners, original.securities(), original.allocations());
    }

    /** @param existing 两家原有媒体的原任职 @param end 新任职的到期时点 */
    public static void appoint(List<RoleAccess.Appointment> existing, long end) {
        for (var desk : DESKS) {
            if (existing.stream().anyMatch(a -> desk.org().equals(a.organizationPartyId()))) continue;
            existing.add(new RoleAccess.Appointment("journalist:" + desk.org(), desk.author(), desk.org(), RoleAccess.Role.JOURNALIST, 0, end, null));
            existing.add(new RoleAccess.Appointment("editor:" + desk.org(), desk.editor(), desk.org(), RoleAccess.Role.EDITOR, 0, end, null));
        }
    }

    /** @param base 原编辑流与回忆规则 @return 按社冻结的门槛、阅读成本及工时预算 */
    public static MediaRun.Config media(MediaRun.Config base) {
        return media(base, false);
    }

    /** @param base 原采编配置 @param compareClocks 是否对跨市场内稿另计编译核对工时 @return 各编辑部的冻结配置 */
    public static MediaRun.Config media(MediaRun.Config base, boolean compareClocks) {
        var policies = new ArrayList<MediaModel.Policy>();
        var budgets = new TreeMap<String, Long>();
        var timing = base.policyOf("wire");
        for (var desk : DESKS) {
            policies.add(new MediaModel.Policy(desk.org(), Map.of(desk.template(), desk.sources()), timing.writeMillis(),
                    timing.reviewMillis(), timing.publishMillis(), desk.reading(), desk.reading(), timing.recallPolicy(), MediaModel.EIGHT_DESKS,
                    compareClocks && desk.template() == MediaModel.TemplateKind.CROSS_BORDER ? COMPARISON_MILLIS : null));
            budgets.put(desk.org(), desk.budget());
        }
        return new MediaRun.Config(policies, budgets, base.proposeMillis(), base.correctionMillis(), base.cancelMillis());
    }

    /** @param base 原采访时限与联系入口 @return 每家机构独立记账的采访配置 */
    public static InterviewRun.Config interviews(InterviewRun.Config base) {
        var timing = base.policyOf("wire");
        var policies = DESKS.stream().map(d -> new InterviewModel.Policy(d.org(), timing.inviteTtlMillis(),
                timing.minLeadMillis(), timing.maxDurationMillis())).toList();
        return new InterviewRun.Config(policies, base.inviteMillis(), base.respondMillis(), base.cancelMillis(), base.endMillis(), base.contacts());
    }

    static String displayClock(long millis) {
        long day = Math.floorDiv(millis, RolePlanningContext.MILLIS_PER_DAY);
        long inDay = Math.floorMod(millis, RolePlanningContext.MILLIS_PER_DAY);
        return String.format(java.util.Locale.ROOT, "D%d %02d:%02d:%02d.%03d", day,
                inDay / 3_600_000, inDay / 60_000 % 60, inDay / 1_000 % 60, inDay % 1_000);
    }

    static void describe(ObjectNode objective, String org) {
        for (var desk : DESKS) if (desk.org().equals(org)) {
            objective.put("intendedAudience", desk.audience()).put("editorialPractice", desk.practice());
            objective.put("outsideInterests", "核对本人其他任职、公司候选身份及自有持仓；公开陈述利益关系。读稿与私人资料按各自权限。");
            return;
        }
    }
}
