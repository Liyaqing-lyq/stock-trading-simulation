package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.List;

/** 基金业务地址绑定产品与收发双边任职，供可见地址、写信与实际投递共用。 */
final class FundOfficeContacts {
    private static final String PREFIX = "基金工作/";
    private FundOfficeContacts() { }

    static List<InterviewRun.ContactEntry> entries(RoleAccess.State access, String person, long now) {
        if (access == null) return List.of();
        List<InterviewRun.ContactEntry> result = new ArrayList<>();
        for (var mandate : access.mandates()) {
            String org = mandate.organizationPartyId(), product = mandate.productOwnerId();
            var team = access.appointments().stream().filter(a -> a.organizationPartyId().equals(org)
                    && a.activeAt(now) && switch (a.role()) {
                        case RESEARCHER, PORTFOLIO_MANAGER, RISK_OFFICER, EXECUTION_TRADER -> true;
                        default -> false;
                    }).toList();
            for (var me : team) {
                if (!me.personPartyId().equals(person)) continue;
                for (var target : team) {
                    if (person.equals(target.personPartyId())) continue;
                    var ids = java.util.stream.Stream.of(me.id(), target.id()).sorted().toList();
                    String channel = PREFIX + List.of(org, product, ids.get(0), ids.get(1)).stream()
                            .map(id -> id.length() + ":" + id).collect(java.util.stream.Collectors.joining("/"));
                    result.add(new InterviewRun.ContactEntry(channel, target.personPartyId(),
                            "本产品在岗联系人：" + target.role() + "（" + product + "）", "fund-mandate:" + org + ":" + product, 1));
                }
            }
        }
        return result;
    }

    static boolean permits(RoleAccess.State access, String from, String to, String channel, long now) {
        if (access == null || !channel.startsWith(PREFIX)) return true;
        return entries(access, from, now).stream().anyMatch(entry ->
                entry.targetPartyId().equals(to) && entry.channel().equals(channel));
    }
}
