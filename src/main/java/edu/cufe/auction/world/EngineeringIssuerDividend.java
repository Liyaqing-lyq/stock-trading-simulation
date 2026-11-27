package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;

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

    static String frozenMeetingProblem(JsonNode input, String seat, String meeting, boolean propose) {
        for (var director : input.at("/engineering/directorships")) {
            if (!ISSUER.equals(director.path("companyPartyId").asText()) || !seat.equals(director.path("seatId").asText())) continue;
            for (var row : director.path("meetings")) {
                if (!meeting.equals(row.path("meetingId").asText())) continue;
                if (!BoardModel.Topic.DIVIDEND_AUTHORIZATION.name().equals(row.path("topic").asText())) return BoardModel.REASON_WRONG_TOPIC;
                if (propose) {
                    if (!BoardModel.MeetingStatus.CONVENED.name().equals(row.path("status").asText())) return BoardModel.REASON_MATERIALS_FROZEN;
                    return row.has("dividendRequest") ? BoardModel.REASON_PROPOSAL_EXISTS : null;
                }
                return BoardModel.MeetingStatus.ADOPTED.name().equals(row.path("status").asText()) && row.has("dividendRequest")
                        ? null : BoardModel.REASON_NOT_ADOPTED;
            }
        }
        return BoardModel.REASON_UNKNOWN_MEETING;
    }
    static String binding(EngineeringRun.CompanySpec spec, OpeningRegistry registry, DividendModel.SchemeRequest request) {
        var security = registry.security(request.symbol());
        if (security == null || !ISSUER.equals(security.issuerOrganizationId())) return "ISSUER_ORGANIZATION_MISMATCH";
        if (!spec.companyOwnerId().equals(request.issuerOwnerId())) return "ISSUER_OWNER_MISMATCH";
        if (!spec.companyAccountId().equals(request.companyAccountId())) return "COMPANY_ACCOUNT_MISMATCH";
        return null;
    }
}
