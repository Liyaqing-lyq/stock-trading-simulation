package edu.cufe.auction.world;

import java.util.List;

/** 虚构的发行人财报交易限制。依据已登记稿件、本人实读及正式披露记录判定。 */
final class MaterialTrading {
    static final String REASON = "ISSUER_REPORT_AWAITING_DISCLOSURE";
    static final String RULE = "实验制度：本人制成或读完某发行人尚未公开的正式财报稿后，须等该发行人同一期相同正文正式披露，才能再为自己或基金申报该证券。已有挂单可以撤销。";

    private MaterialTrading() { }

    record Restriction(String symbol, String issuer, long period, String document, long version) { }

    static List<Restriction> restrictions(PeriodicModel.CompanyPeriods company, InformationModel.State info,
                                          String person, String symbol) {
        return company.drafts().stream().filter(draft -> company.releases().stream().noneMatch(
                release -> release.period() == draft.period() && release.bodyDigest().equals(draft.bodyDigest())))
                .filter(draft -> draft.preparedBy().equals(person) || info.observations().stream().anyMatch(
                        read -> read.personPartyId().equals(person) && read.docId().equals(draft.docId())
                                && read.version() == draft.version()))
                .map(draft -> new Restriction(symbol, company.companyPartyId(), draft.period(), draft.docId(), draft.version()))
                .toList();
    }
}
