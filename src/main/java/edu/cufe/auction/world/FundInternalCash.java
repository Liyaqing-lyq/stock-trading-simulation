package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankingProfile;

/** 固定资本产品的经理只可在该产品已委托的现金账户、自己的存款和客户托管明细之间划转。 */
final class FundInternalCash {
    private FundInternalCash() { }

    static RoleAccess.Appointment manager(RoleAccess.State access, BankingProfile profile, String person,
                                          BankActions.Request request, long now) {
        String product = request.customerKey();
        if (!isProduct(profile, product)) return null;
        if (request.kind() == BankActions.Kind.PAY) {
            if (!profile.ownsCashBucket(product, request.sourceToken()) || !profile.ownsCashBucket(product, request.payeeToken())) return null;
        } else if (request.kind() != BankActions.Kind.DEPOSIT && request.kind() != BankActions.Kind.WITHDRAW) return null;
        return mandate(access, profile, person, product, now);
    }

    static RoleAccess.Appointment mandate(RoleAccess.State access, BankingProfile profile, String person,
                                          String product, long now) {
        var ref = profile.parties().get(product);
        if (ref == null || ref.ownerKind() != BankingProfile.PartyKind.PRODUCT) return null;
        for (var mandate : access.mandates()) {
            if (!mandate.productOwnerId().equals(ref.ownerId()) || !mandate.accountIds().contains(ref.cashAccountId())) continue;
            for (var job : access.appointments()) {
                if (job.personPartyId().equals(person) && job.organizationPartyId().equals(mandate.organizationPartyId())
                        && job.role() == RoleAccess.Role.PORTFOLIO_MANAGER && job.activeAt(now)) return job;
            }
        }
        return null;
    }

    static boolean isProduct(BankingProfile profile, String key) {
        var ref = profile.parties().get(key);
        return ref != null && ref.ownerKind() == BankingProfile.PartyKind.PRODUCT;
    }

    static boolean externalProductPayment(BankingProfile profile, BankActions.Request request) {
        if (request.kind() != BankActions.Kind.PAY) return false;
        return profile.parties().entrySet().stream().anyMatch(entry ->
                entry.getValue().ownerKind() == BankingProfile.PartyKind.PRODUCT
                && !entry.getKey().equals(request.customerKey()) && profile.ownsCashBucket(entry.getKey(), request.payeeToken()));
    }
}
