package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.function.Function;

/** 已确认股利债权的本人视野与发行人履行原决议的有限权限。 */
final class DividendClaimDecisions {
    static final String IMPAIR = "dividend.claim.impair", RETRY = "issuer.dividend.retryPayment";
    record Impair(String claimId, String amount, String evidenceRef) { }
    record Retry(String schemeId) { }

    private DividendClaimDecisions() { }

    static boolean enabled(RunManifest manifest) {
        return EngineeringIssuerDividend.decisions(manifest)
                && RunManifest.DIVIDEND_CLAIMS_VERSION.equals(manifest.dataVersions().get(RunManifest.DIVIDEND_CLAIMS_POLICY));
    }

    static String toolForKind(String kind) {
        return switch (kind) {
            case PendingTask.GOVERNANCE_DIVIDEND_PROPOSAL_V1 -> RoleTradingView.TOOL_ISSUER_DIVIDEND_PROPOSE;
            case PendingTask.GOVERNANCE_DIVIDEND_IMPLEMENT_V1 -> RoleTradingView.TOOL_ISSUER_DIVIDEND_AUTHORIZE;
            case PendingTask.DIVIDEND_IMPAIR_V1 -> IMPAIR;
            case PendingTask.DIVIDEND_SETTLE_V1 -> RETRY;
            default -> null;
        };
    }

    static void append(ObjectNode view, String person, boolean chair, OpeningRegistry registry, RoleRun roles,
            Map<String,CashLedger.CashAccount> accounts, DividendModel.State dividends,
            Function<DividendModel.Scheme,WorldRuntime.DividendReport> failedPayment) {
        for (var scheme : dividends.schemes().values()) {
            var claims = DividendModel.claims(dividends, scheme.schemeId());
            if (claims.isEmpty()) continue;
            WorldRuntime.DividendReport failure = null;
            boolean checked = false;
            for (var claim : claims) {
                var owner = registry.owner(accounts.get(claim.accountId()).ownerId());
                if (owner.kind() != OpeningRegistry.OwnerKind.PERSON || !person.equals(owner.partyId())
                        || RoleAccess.canReadPersonalAccount(roles.access(), person, claim.accountId()) != RoleAccess.Decision.ALLOW) continue;
                if (!checked) { failure = failedPayment.apply(scheme); checked = true; }
                var item = view.withArray("ownClaims").addObject().put("claimId",claim.claimId()).put("schemeId",scheme.schemeId())
                        .put("symbol",scheme.symbol()).put("accountId",claim.accountId()).put("payAtMillis",scheme.payAtMillis())
                        .put("face",claim.face().toPlainString()).put("paid",claim.paid().toPlainString())
                        .put("impairment",claim.impairment().toPlainString()).put("netReceivable",DividendModel.holderNetReceivable(claim).toPlainString());
                if (failure != null && DividendModel.holderNetReceivable(claim).signum() > 0)
                    item.put("failureRef","dividend-report:"+failure.taskId()).put("paymentResult",failure.reason());
            }
            if (chair && scheme.phase() == DividendModel.Phase.EX_BOOKED) {
                if (!checked) failure = failedPayment.apply(scheme);
                if (failure != null) view.withArray("issuerArrears").addObject().put("schemeId",scheme.schemeId())
                        .put("authorizationRef",scheme.authorizationRef()).put("payAtMillis",scheme.payAtMillis())
                        .put("payable",DividendModel.companyPayable(dividends,scheme.schemeId()).toPlainString())
                        .put("paymentResult",failure.reason());
            }
        }
    }

    static JsonNode row(JsonNode view, String rows, String key, String id) {
        for (var row : view.path(rows)) if (id != null && id.equals(row.path(key).asText())) return row;
        return null;
    }
}
