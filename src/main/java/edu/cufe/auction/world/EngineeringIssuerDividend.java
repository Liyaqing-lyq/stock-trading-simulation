package edu.cufe.auction.world;

/** 工程发行人分红范围。债权、证券登记、董事表决与现金沿用各自领域模型。 */
final class EngineeringIssuerDividend {
    static final String ISSUER = "eng-a";
    private EngineeringIssuerDividend() { }
    static boolean enabled(RunManifest manifest) {
        return "engineering-eng-a-v1".equals(manifest.dataVersions().get("issuer-dividend"));
    }
    static String binding(EngineeringRun.CompanySpec spec, OpeningRegistry registry, DividendModel.SchemeRequest request) {
        var security = registry.security(request.symbol());
        if (security == null || !ISSUER.equals(security.issuerOrganizationId())) return "ISSUER_ORGANIZATION_MISMATCH";
        if (!spec.companyOwnerId().equals(request.issuerOwnerId())) return "ISSUER_OWNER_MISMATCH";
        if (!spec.companyAccountId().equals(request.companyAccountId())) return "COMPANY_ACCOUNT_MISMATCH";
        return null;
    }
}
