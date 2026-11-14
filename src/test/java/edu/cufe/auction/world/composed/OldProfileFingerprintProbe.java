package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.bank.BankingProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧银行档案口径探针：只用旧四参 PartyRef/BankRef 构造档案，打印 canonical 与 fingerprint。
 *
 * <p>用于“旧 profile 规范文本与指纹不变”的定向回归：改动前后各跑一次并逐字比较。</p>
 */
public final class OldProfileFingerprintProbe {

    private OldProfileFingerprintProbe() {
    }

    /** @param args 忽略 */
    public static void main(String[] args) {
        Map<String, BankingProfile.BankRef> banks = new LinkedHashMap<>();
        banks.put("north", new BankingProfile.BankRef("north", "north", "north-owner",
                "RSV-NORTH"));
        Map<String, BankingProfile.PartyRef> parties = new LinkedHashMap<>();
        parties.put("acme", new BankingProfile.PartyRef("acme", "acme-owner", "acme-cash",
                "dep-acme"));
        parties.put("firm", new BankingProfile.PartyRef("firm", "firm-owner", "firm-cash", null));
        Map<String, BankingProfile.RiskBucket> buckets = new LinkedHashMap<>();
        buckets.put("acme", new BankingProfile.RiskBucket("book-acme", null, "book-acme",
                "impair-acme"));
        BankingProfile profile = new BankingProfile(banks, parties, Map.of(), buckets, Map.of(),
                List.of("book-acme"), List.of("impair-acme"), List.of("dep-acme"),
                null, null, null);
        System.out.println("CANONICAL-BEGIN");
        System.out.println(profile.canonical());
        System.out.println("CANONICAL-END");
        System.out.println("FINGERPRINT=" + profile.fingerprint());
    }
}
