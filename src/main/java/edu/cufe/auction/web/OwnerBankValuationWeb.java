package edu.cufe.auction.web;

import edu.cufe.auction.world.WorldRuntime;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 从同一确认帧读取本人及获授权产品的财产，各主体分别核算。 */
final class OwnerBankValuationWeb {
    private OwnerBankValuationWeb() { }

    static final String BASIS = "净资产按同一时点计入现金、持仓、股息净应收、主体存款及应收利息、托管受益额，扣除未偿贷款本金及应付利息。持仓缺价时净资产留空。";

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
