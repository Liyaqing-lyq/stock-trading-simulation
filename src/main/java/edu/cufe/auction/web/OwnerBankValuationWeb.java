package edu.cufe.auction.web;

import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.SettlementCash;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从同一确认帧读取本人及获授权产品的财产，各主体分别核算。 */
final class OwnerBankValuationWeb {
    private OwnerBankValuationWeb() { }

    static Map<String,String> settlement(SettlementCash.Amounts amount) {
        return Map.of("tradingAvailable",amount.tradingAvailable().toPlainString(),
                "orderFrozen",amount.orderFrozen().toPlainString(),
                "unsettledSaleCash",amount.unsettledSaleCash().toPlainString(),
                "withdrawableCash",amount.withdrawableCash().toPlainString(),
                "transactionFees",amount.transactionFees().toPlainString());
    }

    static final String BASIS = "净资产按同一时点计入现金、持仓、股息净应收、主体存款及应收利息、托管受益额，扣除未偿贷款本金及应付利息。持仓缺价时净资产留空。";

    static Map<String,Map<String,String>> portfolioReturns(WorldRuntime world, RoleView view) {
        if (view.portfolios() == null || view.portfolios().isEmpty()
                || !RunManifest.usesCurrentTenCompany(world.profile().manifest().policyRunId())) return Map.of();
        var confirmed = world.openingReturns();
        var rows = new LinkedHashMap<String,Map<String,String>>();
        for (var portfolio : view.portfolios()) {
            var result = confirmed.get(portfolio.ownerId()).value();
            var row = new LinkedHashMap<String,String>();
            row.put("status", result == null ? "UNAVAILABLE" : result.status().name());
            if (result != null && result.returnRate() != null) {
                row.put("returnRate",result.returnRate().toPlainString());
                row.put("index",result.index().toPlainString());
                row.put("maxDrawdown",result.maxDrawdown().toPlainString());
            }
            rows.put(portfolio.ownerId(),row);
        }
        return rows;
    }

    static String basis(WorldRuntime world) {
        return world.hasOpenFund() ? BASIS + " 开放基金持有人加计份额市值、申购预付款和赎回应收；产品扣除预收款和已确认的应付赎回款。" : BASIS;
    }

    /** 只对有完整估值、可计算收益的个人及产品按同一期间排序，再由调用方分页。 */
    static void rankReturns(List<Map<String, Object>> rows) {
        for (var row : rows) row.put("periodReturnRank", null);
        var eligible = rows.stream().filter(row -> ("PERSON".equals(row.get("kind")) || "PRODUCT".equals(row.get("kind")))
                && "COMPLETE".equals(row.get("valuationStatus")) && "COMPLETE".equals(row.get("performanceStatus"))
                && row.get("returnRate") != null && row.get("netWorth") != null
                && new BigDecimal(row.get("netWorth").toString()).signum() > 0)
                .sorted(Comparator.comparing((Map<String,Object> row) -> new BigDecimal(row.get("returnRate").toString())).reversed()).toList();
        BigDecimal previous = null;
        int rank = 0;
        for (int i = 0; i < eligible.size(); i++) {
            var row = eligible.get(i);
            var rate = new BigDecimal(row.get("returnRate").toString());
            if (previous == null || previous.compareTo(rate) != 0) rank = i + 1;
            row.put("periodReturnRank", Integer.toString(rank));
            previous = rate;
        }
    }

    static boolean enabled(WorldRuntime world) {
        return "owner-claims-v1".equals(world.profile().manifest().dataVersions().get("owner-bank-valuation"));
    }
}
