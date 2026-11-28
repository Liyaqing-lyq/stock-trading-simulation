package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.BankingProfile;
import java.math.BigDecimal;
import java.util.*;

/** 外部需求 v1 的可信开局常量与历史禀赋证明；不接受人物或日志自填参数。 */
public final class ExternalDemandOpening {
    /** 有限最终需求现金账户。 */
    public static final String ACCOUNT_ID = "EXT-FINAL-DEMAND";
    /** 与公司、客户、银行独立的外部财产主体。 */
    public static final String OWNER_ID = "external-final-demand";
    /** EXP 历史储蓄；不是营业收入或无限补贴。 */
    public static final BigDecimal INITIAL_CASH = new BigDecimal("90000.00");
    private static final Map<String, String> SOURCES = Map.of(
            "EXT-SUP-METAL", "sup-metal", "EXT-SUP-RESIN", "sup-resin",
            "EXT-SUP-CHEM", "sup-chem", "MFG-TECH-OPS", "mfg-tech-ops",
            "MFG-STRUCT-OPS", "mfg-struct-ops", "MFG-VERSA-OPS", "mfg-versa-ops");
    private ExternalDemandOpening() { }

    /** 只有新清单显式声明 v1 时启用。 */
    public static boolean enabled(RunManifest manifest) {
        return "v1".equals(manifest.dataVersions().get("external-demand"));
    }

    /** 返回固定来源、二百件、二万元、三日加工的 EXP 规则。 */
    public static ExternalDemandRun.Config config() {
        return ExternalDemandRun.Config.defaults(ACCOUNT_ID, OWNER_ID, SOURCES.keySet());
    }

    /** 在唯一现金开局清单中登记有限储蓄。 */
    public static CashLedger.OpeningAccount account() {
        return new CashLedger.OpeningAccount(ACCOUNT_ID, OWNER_ID, INITIAL_CASH,
                "EXP 外部最终需求聚合主体的有限历史储蓄；不代表收入、现实校准或自动补足");
    }

    /** 验证原始开局、登记、银行路由及实际经营账户映射后创建零进度。 */
    public static ExternalDemandRun open(CompanyOperationsRun operations,
            List<CashLedger.OpeningAccount> openings, OpeningRegistry registry,
            BankingProfile profile, Map<String, CashLedger.CashAccount> accounts) {
        Map<String, CashLedger.OpeningAccount> original = new HashMap<>();
        for (var account : openings) {
            if (original.put(account.accountId(), account) != null)
                throw new IllegalArgumentException("外部需求开局账户重复");
        }
        requireOpening(original, ACCOUNT_ID, OWNER_ID, INITIAL_CASH);
        for (var source : SOURCES.entrySet())
            requireOpening(original, source.getKey(), source.getValue(), new BigDecimal("0.00"));
        var owner = registry.owner(OWNER_ID);
        if (owner == null || owner.kind() != OpeningRegistry.OwnerKind.ORGANIZATION
                || !OWNER_ID.equals(owner.partyId())
                || registry.parties().stream().noneMatch(p -> p.partyId().equals(OWNER_ID)
                        && p.kind() == OpeningRegistry.PartyKind.ORGANIZATION))
            throw new IllegalArgumentException("最终需求财产主体未独立登记");
        Map<String, String> expected = new HashMap<>(SOURCES); expected.put(ACCOUNT_ID, OWNER_ID);
        for (var entry : expected.entrySet()) {
            var party = profile.parties().get(entry.getValue());
            if (party == null || !party.ownerId().equals(entry.getValue())
                    || !party.cashAccountId().equals(entry.getKey()) || party.depositGl() != null
                    || profile.banks().values().stream().anyMatch(b -> b.ownerId().equals(entry.getValue()))
                    || profile.custodians().values().stream().anyMatch(c -> c.ownerId().equals(entry.getValue())))
                throw new IllegalArgumentException("外部需求资金来源必须是无存款路由的独立外部账户：" + entry.getKey());
        }
        ExternalDemandRun run = ExternalDemandRun.opening(config());
        // 纯核在零时点只复核实际经营账户映射，不产生转售或收入分配。
        run.settle(operations, accounts, 0);
        return run;
    }

    private static void requireOpening(Map<String, CashLedger.OpeningAccount> original,
            String id, String owner, BigDecimal amount) {
        var account = original.get(id);
        if (account == null || !owner.equals(account.ownerId()) || account.cash().compareTo(amount) != 0)
            throw new IllegalArgumentException("外部需求原始账户禀赋或属主不符：" + id);
    }
}
