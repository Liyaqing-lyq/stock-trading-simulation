package edu.cufe.auction.web;

import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.ValuationModel;
import edu.cufe.auction.world.WorldRuntime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 由同一帧的本人账户、持仓、估值及静态发行登记派生两种持仓比例。 */
final class PersonalHoldingWeights {
    private PersonalHoldingWeights() { }

    static Map<RoleView.Holding, Map<String, String>> holdingWeights(WorldRuntime world, RoleView view) throws java.io.IOException {
        return holdingWeights(world,view,world.snapshot().position());
    }

    static Map<RoleView.Holding, Map<String, String>> holdingWeights(WorldRuntime world, RoleView view, long position) throws java.io.IOException {
        if (view.portfolios() == null) return Map.of();
        var registry = world.openingRegistry().orElseThrow();
        var accounts = new HashMap<String, RoleView.Account>();
        for (var account : view.accounts()) accounts.put(account.accountId(), account);
        var portfolios = new HashMap<String, RoleView.OwnerPortfolio>();
        for (var owner : view.portfolios()) portfolios.put(owner.ownerId(), owner);
        var prices = new HashMap<String, ValuationModel.Price>();
        for (var market : view.market()) prices.put(market.symbol(), market.reference());
        var rows = new LinkedHashMap<RoleView.Holding, Map<String, String>>();
        for (var holding : view.holdings()) {
            var account = accounts.get(holding.accountId());
            if (account != null) rows.put(holding, percentagesAt(holding, registry.security(holding.symbol()) == null ? null : world.issuedSharesAt(holding.symbol(),position),
                    prices.get(holding.symbol()), portfolios.get(account.ownerId())));
        }
        return rows;
    }

    static Map<String, String> percentages(RoleView.Holding holding, OpeningRegistry.Security security,
                                            ValuationModel.Price reference, RoleView.OwnerPortfolio owner) {
        return percentagesAt(holding, security == null ? null : security.issuedShares(), reference, owner);
    }

    private static Map<String,String> percentagesAt(RoleView.Holding holding, Long issued, ValuationModel.Price reference, RoleView.OwnerPortfolio owner) {
        String weight = null;
        var quantity = BigDecimal.valueOf(holding.quantity());
        if (owner != null && owner.valuationStatus() == ValuationModel.Status.COMPLETE && owner.netWorth() != null
                && reference != null && reference.price() != null) {
            var nav = new BigDecimal(owner.netWorth());
            if (nav.signum() > 0) weight = percent(quantity.multiply(reference.price()), nav);
        }
        var fields = new LinkedHashMap<String, String>();
        fields.put("ownerNavPercent", weight);
        fields.put("issuedSharePercent", issued == null ? null : issuedSharePercent(holding.quantity(), issued));
        return fields;
    }

    static String issuedSharePercent(long quantity, long issued) {
        return percent(BigDecimal.valueOf(quantity),BigDecimal.valueOf(issued));
    }

    private static String percent(BigDecimal amount, BigDecimal base) {
        return amount.movePointRight(2).divide(base, 4, RoundingMode.HALF_UP).toPlainString();
    }
}
