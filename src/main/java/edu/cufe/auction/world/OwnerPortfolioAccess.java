package edu.cufe.auction.world;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 完整财产主体估值的权限判定，共用于本人决策输入和网页持仓。 */
public final class OwnerPortfolioAccess {
    private OwnerPortfolioAccess() { }

    /**
     * 按登记身份与当期本人账户权限检查整主体口径，部分账户任职授权只能查看具名账户的持仓。
     * @param accounts 同一截止的权威现金账户
     * @param registry 财产主体登记
     * @param person 当前人物标识
     * @param visible 经角色权限裁剪的账户
     * @param selected 需要取所属主体净资产的账户
     * @return 人物已获准查看该财产主体的全部账户
     */
    public static boolean canView(Map<String, CashLedger.CashAccount> accounts, OpeningRegistry registry, String person,
                                  List<RoleView.Account> visible, RoleView.Account selected) {
        var actual = accounts.get(selected.accountId());
        var owner = registry.owner(selected.ownerId());
        if (!visible.contains(selected) || actual == null || !actual.ownerId().equals(selected.ownerId()) || owner == null)
            return false;
        boolean eligible = switch (selected.kind()) {
            case PERSONAL -> owner.kind() == OpeningRegistry.OwnerKind.PERSON && person.equals(owner.partyId());
            case PRODUCT -> owner.kind() == OpeningRegistry.OwnerKind.PRODUCT;
        };
        if (!eligible) return false;
        var allowedAccounts = visible.stream()
                .filter(row -> row.kind() == selected.kind() && row.ownerId().equals(selected.ownerId()))
                .map(RoleView.Account::accountId).collect(Collectors.toSet());
        return accounts.entrySet().stream().filter(row -> row.getValue().ownerId().equals(selected.ownerId()))
                .allMatch(row -> allowedAccounts.contains(row.getKey()));
    }
}
