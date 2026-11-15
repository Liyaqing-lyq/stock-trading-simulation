package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 按订单、库存和资金约束经营的实验规则；只接收经理可见输入，不访问世界或客户私账。 */
public final class ManufacturingBehaviorPolicy {
    /** 冻结规则版本；旧季度不启用此规则。 */
    public static final String VERSION = "manufacturing-demand-v1";
    private static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;

    private ManufacturingBehaviorPolicy() { }

    /** 一次选择及其可检查的理由；WAIT 没有业务动作。 */
    public record Choice(String action, Map<String, Object> fields, String reason) {
        /** 冻结字段顺序。 */
        public Choice { fields = java.util.Collections.unmodifiableMap(new TreeMap<>(fields)); }
    }

    /** 根据当前可见事实选择一个动作；不按日期制造采购、收入或成交。 */
    public static Choice choose(JsonNode view, long now, String id) {
        if (view == null || view.isMissingNode() || !view.has("companyId")) return waitFor("没有当前经理权限");
        for (JsonNode ar : rows(view.path("receivables"), "receivableId")) {
            if (ar.path("status").asText().equals("OPEN") && ar.path("dueAt").asLong() <= now) {
                // 对失败回款每日只重试一次，不把失败误写为收到现金。
                boolean tried = rows(view.path("reports"), "commandId").stream().anyMatch(r ->
                        r.path("action").asText().equals("COLLECT")
                        && r.path("businessId").asText().equals(ar.path("receivableId").asText())
                        && r.path("timeMillis").asLong(-DAY) / DAY == now / DAY);
                if (!tried) return act("COLLECT", "到期应收优先回收；以实际付款回报为准",
                        "receivableId", ar.path("receivableId").asText());
            }
        }
        for (JsonNode c : rows(view.path("contracts"), "contractId")) {
            long remaining = c.path("quantity").asLong() - c.path("delivered").asLong();
            if (remaining <= 0 || c.path("released").asBoolean()) continue;
            String contract = c.path("contractId").asText();
            if (now > c.path("expiresAt").asLong()) return act("MARK_OVERDUE", "履约窗口已过，记录违约并释放承诺", "contractId", contract);
            String product = c.path("productId").asText();
            int gen = c.path("generation").asInt();
            JsonNode spec = rows(view.path("products"), "productId").stream().filter(p ->
                    p.path("productId").asText().equals(product) && p.path("generation").asInt() == gen).findFirst().orElseThrow();
            long finished = view.path("productsOnHand").path(product + ":" + gen).path("quantity").asLong();
            if (finished > 0 && now >= c.path("promisedAt").asLong()) return act("DELIVER", "已有成品且处于履约窗口，交付后形成应收",
                    "contractId", contract, "quantity", Math.min(remaining, finished), "receivableId", id + "-ar");
            long making = rows(view.path("batches"), "batchId").stream().filter(b ->
                    b.path("productId").asText().equals(product) && b.path("generation").asInt() == gen
                    && b.path("status").asText().equals("IN_PROGRESS")).mapToLong(b -> b.path("quantity").asLong()).sum();
            long need = remaining - finished - making;
            if (need <= 0) return waitFor("等待真实生产完工或合同交付窗口");
            long quantity = Math.min(need, view.path("capacityThisPeriod").asLong());
            if (quantity <= 0) return waitFor("本期产能已用尽，不能虚构完工");
            var bom = spec.path("bom");
            for (String material : names(bom)) {
                long required = Math.multiplyExact(quantity, bom.path(material).asLong());
                long stock = view.path("materials").path(material).path("quantity").asLong();
                long ordered = rows(view.path("purchases"), "purchaseOrderId").stream().filter(p ->
                        p.path("materialId").asText().equals(material) && p.path("status").asText().equals("ORDERED"))
                        .mapToLong(p -> p.path("quantity").asLong()).sum();
                long missing = required - stock - ordered;
                if (missing > 0) {
                    JsonNode quote = quote(view, material);
                    if (quote == null) return waitFor("缺少有效供应商报价");
                    BigDecimal outlay = money(quote, "price").multiply(BigDecimal.valueOf(missing));
                    BigDecimal labor = money(spec, "laborPerUnit").multiply(BigDecimal.valueOf(quantity));
                    if (money(view, "cash").subtract(committedPurchases(view)).subtract(money(view, "periodExpense"))
                            .compareTo(outlay.add(labor)) < 0) return waitFor("回款或现金不足，保留固定支出和已承诺采购资金");
                    if (outlay.compareTo(budget(view, "PROCUREMENT")) > 0 || missing > quote.path("remainingSupply").asLong())
                        return waitFor("采购预算或供应商容量不足");
                    return act("ORDER_MATERIAL", "仅补足已签合同的物料缺口，扣除库存和在途采购", "materialId", material, "quantity", missing);
                }
                quantity = Math.min(quantity, stock / bom.path(material).asLong());
            }
            if (quantity <= 0) return waitFor("等待已下单材料实际到货");
            BigDecimal labor = money(spec, "laborPerUnit").multiply(BigDecimal.valueOf(quantity));
            if (labor.compareTo(budget(view, "PRODUCTION_LABOR")) > 0
                    || labor.compareTo(money(view, "cash").subtract(committedPurchases(view)).subtract(money(view, "periodExpense"))) > 0)
                return waitFor("生产人工资金或授权不足");
            return act("START_BATCH", "按未交合同、实有材料和剩余产能投产", "productId", product, "generation", gen, "quantity", quantity);
        }
        String constraint = "没有客户提出可付款的新需求";
        for (JsonNode offer : rows(view.path("customerOffers"), "customerId")) {
            if (offer.path("quantity").asLong() <= 0) continue;
            for (JsonNode product : rows(view.path("products"), "productId")) {
                if (product.path("certRequired").asBoolean()) continue;
                BigDecimal materialCost = BigDecimal.ZERO;
                long quantity = Math.min(offer.path("quantity").asLong(), view.path("capacityThisPeriod").asLong());
                boolean quoted = true;
                for (String material : names(product.path("bom"))) {
                    JsonNode quote = quote(view, material);
                    if (quote == null) { quoted = false; break; }
                    long units = product.path("bom").path(material).asLong();
                    materialCost = materialCost.add(money(quote, "price").multiply(BigDecimal.valueOf(units)));
                    quantity = Math.min(quantity, quote.path("remainingSupply").asLong() / units);
                }
                if (!quoted) { constraint = "缺少有效采购报价，不能估算履约成本"; continue; }
                BigDecimal labor = money(product, "laborPerUnit"), cost = materialCost.add(labor);
                if (money(offer, "unitPrice").compareTo(cost) <= 0) { constraint = "客户报价不能覆盖增量成本，拒绝亏损新单"; continue; }
                BigDecimal available = money(view, "cash").subtract(money(view, "periodExpense")).subtract(committedPurchases(view));
                quantity = Math.min(quantity, affordable(available, cost));
                quantity = Math.min(quantity, affordable(budget(view, "PROCUREMENT"), materialCost));
                quantity = Math.min(quantity, affordable(budget(view, "PRODUCTION_LABOR"), labor));
                if (quantity <= 0) { constraint = "营运资金、产能、供货或董事会授权不足"; continue; }
                long promised = Math.addExact(now, DAY + product.path("cycleMillis").asLong() + DAY);
                return act("SIGN_CONTRACT", "客户有限需求与资金支持；报价覆盖增量成本，数量受营运资金、产能及预算约束",
                        "contractId", id + "-contract", "customerId", offer.path("customerId").asText(),
                        "productId", product.path("productId").asText(), "generation", product.path("generation").asInt(),
                        "quantity", quantity, "unitPrice", offer.path("unitPrice").asText(), "promisedAt", promised, "expiresAt", promised + 7 * DAY);
            }
        }
        return waitFor(constraint);
    }

    private static BigDecimal committedPurchases(JsonNode view) {
        return rows(view.path("purchases"), "purchaseOrderId").stream().filter(p -> p.path("status").asText().equals("ORDERED"))
                .map(p -> money(p, "unitPrice").multiply(BigDecimal.valueOf(p.path("quantity").asLong()))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static long affordable(BigDecimal cash, BigDecimal cost) {
        return cost.signum() == 0 ? Long.MAX_VALUE : cash.max(BigDecimal.ZERO).divide(cost, 0, RoundingMode.DOWN).longValueExact();
    }
    private static BigDecimal budget(JsonNode v, String name) {
        JsonNode n = v.path("approvedBudgetRemaining").path(name);
        return n.isMissingNode() || n.isNull() ? BigDecimal.ZERO : new BigDecimal(n.asText());
    }
    private static BigDecimal money(JsonNode v, String field) { return new BigDecimal(v.path(field).asText("0")); }
    private static JsonNode quote(JsonNode v, String material) {
        return rows(v.path("visibleQuotes"), "materialId").stream().filter(q -> q.path("materialId").asText().equals(material)).findFirst().orElse(null);
    }
    private static List<String> names(JsonNode node) { var names = new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add); names.sort(String::compareTo); return names; }
    private static List<JsonNode> rows(JsonNode node, String key) {
        var rows = new ArrayList<JsonNode>(); node.elements().forEachRemaining(rows::add); rows.sort(Comparator.comparing(n -> n.path(key).asText())); return rows;
    }
    private static Choice waitFor(String reason) { return new Choice("WAIT", Map.of(), reason); }
    private static Choice act(String action, String reason, Object... fields) {
        Map<String, Object> values = new TreeMap<>(); for (int i = 0; i < fields.length; i += 2) values.put((String) fields[i], fields[i + 1]);
        return new Choice(action, values, reason);
    }
}
