package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 工程发行人分红范围。债权、证券登记、董事表决与现金沿用各自领域模型。 */
final class EngineeringIssuerDividend {
    static final String ISSUER = "eng-a";
    private EngineeringIssuerDividend() { }
    static boolean enabled(RunManifest manifest) {
        return "engineering-eng-a-v1".equals(manifest.dataVersions().get("issuer-dividend"));
    }
    static boolean decisions(RunManifest manifest) {
        return enabled(manifest) && RunManifest.ISSUER_DECISIONS_VERSION.equals(manifest.dataVersions().get(RunManifest.ISSUER_DECISIONS_POLICY));
    }
    static boolean nativeDecision(String kind) {
        return PendingTask.GOVERNANCE_DIVIDEND_PROPOSAL_V1.equals(kind)
                || PendingTask.GOVERNANCE_DIVIDEND_IMPLEMENT_V1.equals(kind);
    }
    record Convene(String companyPartyId, String meetingId, long materialDeadline, long opensAt, long closesAt) { }
    record Propose(String companyPartyId, String meetingId, String schemeId, String perShare,
                   long recordAtMillis, long exAtMillis, long payAtMillis) { }
    record Authorize(String companyPartyId, String meetingId) { }

    static void requireTimes(JsonNode args, String... names) {
        for (String name : names) {
            JsonNode value = args.path(name);
            if (!value.isIntegralNumber() || !value.canConvertToLong())
                throw new IllegalArgumentException(name + " 须为有符号 64 位整数毫秒");
        }
    }

    /** 本公司董事按本人观察截止点选择已公告且仍可提请更正的方案。 */
    static void projectAnnouncements(ObjectNode director, DividendModel.State state, InformationModel.State information,
                                     long now, EngineeringRun.CompanySpec issuer, OpeningRegistry registry) {
        var rows = director.putArray("amendableAnnouncements");
        for (var scheme : state.schemes().values()) {
            if (scheme.phase() != DividendModel.Phase.AUTHORIZED || now >= scheme.recordAtMillis()) continue;
            if (binding(issuer, registry, scheme.symbol(), scheme.issuerOwnerId(), scheme.companyAccountId()) != null) continue;
            var id = scheme.schemeId();
            var version = DividendModel.versionOf(state, id);
            var docId = "dividend-public:" + DividendModel.rootSchemeId(state, id);
            for (var document : information.documents()) {
                if (!docId.equals(document.docId()) || document.audience().kind() != InformationModel.AudienceKind.PUBLIC) continue;
                var published = document.at(version);
                if (published != null && published.publishedAt() <= now)
                    rows.addObject().put("schemeId", id).put("expectedVersion", version).put("docId", docId);
                break;
            }
        }
    }

    /** 将本次会议拟更改的原方案与待表决的新条款明确分开。 */
    static void projectAmendment(ObjectNode meeting, DividendModel.SchemeRequest terms) {
        var amendment = terms.amendment();
        if (amendment == null) return;
        String operation = amendment.operation();
        var row = meeting.putObject("dividendAmendment").put("schemeId", amendment.currentSchemeId())
                .put("expectedVersion", amendment.expectedVersion()).put("operation", operation)
                .put("reason", amendment.reason());
        if ("CANCEL".equals(operation)) {
            row.set("proposedAtMillis", meeting.remove("dividendRequest").required("proposedAtMillis"));
        }
    }

    static String frozenMeetingProblem(JsonNode input, String seat, String meeting, boolean propose) {
        for (var director : input.at("/engineering/directorships")) {
            if (!ISSUER.equals(director.path("companyPartyId").asText()) || !seat.equals(director.path("seatId").asText())) continue;
            for (var row : director.path("meetings")) {
                if (!meeting.equals(row.path("meetingId").asText())) continue;
                if (!BoardModel.Topic.DIVIDEND_AUTHORIZATION.name().equals(row.path("topic").asText())) return BoardModel.REASON_WRONG_TOPIC;
                boolean hasProposal = row.has("dividendRequest") || row.has("dividendAmendment");
                if (propose) {
                    if (!BoardModel.MeetingStatus.CONVENED.name().equals(row.path("status").asText())) return BoardModel.REASON_MATERIALS_FROZEN;
                    return hasProposal ? BoardModel.REASON_PROPOSAL_EXISTS : null;
                }
                return BoardModel.MeetingStatus.ADOPTED.name().equals(row.path("status").asText()) && hasProposal
                        ? null : BoardModel.REASON_NOT_ADOPTED;
            }
        }
        return BoardModel.REASON_UNKNOWN_MEETING;
    }
    static String binding(EngineeringRun.CompanySpec spec, OpeningRegistry registry, DividendModel.SchemeRequest request) {
        return binding(spec, registry, request.symbol(), request.issuerOwnerId(), request.companyAccountId());
    }
    private static String binding(EngineeringRun.CompanySpec spec, OpeningRegistry registry,
                                  String symbol, String owner, String account) {
        var security = registry.security(symbol);
        if (security == null || !ISSUER.equals(security.issuerOrganizationId())) return "ISSUER_ORGANIZATION_MISMATCH";
        if (!spec.companyOwnerId().equals(owner)) return "ISSUER_OWNER_MISMATCH";
        if (!spec.companyAccountId().equals(account)) return "COMPANY_ACCOUNT_MISMATCH";
        return null;
    }
}
