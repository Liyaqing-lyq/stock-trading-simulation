package edu.cufe.auction.web;

import edu.cufe.auction.world.ManufacturingFunding;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedCompanyReport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 制造经营网页入口：人物身份、任职与付款账户全部由同一确认帧解析。 */
final class ManufacturingWeb {
    private ManufacturingWeb() { }
    private static final List<String> ACTIONS = List.of("SIGN", "PURCHASE_ORDER", "PRODUCTION",
            "DELIVERY", "COLLECT", "PAY_BILL");

    record Command(long expectedPosition, String commandId, String taskId, long dueTimeMillis,
                   long durationMillis, String action, String businessId, String resultId,
                   long quantity, BigDecimal unitPrice, long promisedDeliveryAt, long period,
                   String fundSource) { }

    static Map<String, Object> view(WorldRuntime world, String person) throws java.io.IOException {
        var snapshot = world.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("position", Long.toString(snapshot.position()));
        result.put("timeMillis", Long.toString(snapshot.timeMillis()));
        boolean enabled = world.profile().manifest().hasManufacturingFunding();
        result.put("enabled", enabled);
        if (!enabled) {
            result.put("note", "当前档案未启用制造资金操作。旧档继续按原规则运行。");
            return result;
        }
        var ledger = snapshot.manufacturing().orElseThrow().ledger();
        String company = snapshot.governance().orElseThrow().board().charter().companyPartyId();
        boolean manager = person != null && world.isActiveManager(company, person, snapshot.timeMillis());
        result.put("companyId", company);
        result.put("actions", manager ? ACTIONS : List.of());
        result.put("note", person == null ? "真实经营结果与公司总账。到期回款仍可能因客户资金不足而失败。"
                : manager ? "按本人经理权限提交。采购与人工须有董事会预算；提交后等待世界推进。"
                : "本人没有制造公司的有效经理任职。任免与预算请通过公司治理流程完成。");
        if (manager) {
            List<Map<String, String>> funds = new ArrayList<>();
            funds.add(Map.of("token", ManufacturingFunding.cashToken(ledger.opening().companyAccountId()),
                    "label", "公司经营现金"));
            var profile = world.bankOf().orElseThrow().decode(snapshot.accounts()).config().profile();
            for (var party : new TreeMap<>(profile.parties()).entrySet()) {
                if (!party.getValue().ownerId().equals(ledger.opening().companyOwnerId())) continue;
                for (String bank : new TreeMap<>(profile.banks()).keySet()) {
                    funds.add(Map.of("token", "DEPOSIT|" + bank + "|" + party.getKey(),
                            "label", "公司银行存款 · " + bank));
                }
            }
            result.put("funds", funds);
            result.put("receipts", world.manufacturingReceiptsFor(person).stream().limit(30)
                    .map(ManufacturingWeb::receipt).toList());
        }
        if (person == null || manager) {
            result.put("inventory", Map.of("rawQuantity", Long.toString(ledger.rawMaterials().quantity()),
                    "rawCost", ledger.rawMaterials().amount().toPlainString(),
                    "finishedQuantity", Long.toString(ledger.finishedGoods().quantity()),
                    "finishedCost", ledger.finishedGoods().amount().toPlainString()));
            List<Map<String, String>> operations = new ArrayList<>();
            new TreeMap<>(ledger.purchaseOrders()).values().forEach(row -> operations.add(Map.of(
                    "kind", "采购", "id", row.purchaseOrderId(), "status", row.status().name(),
                    "quantity", Long.toString(row.quantity()), "amount", row.unitPrice().multiply(BigDecimal.valueOf(row.quantity())).toPlainString(),
                    "due", Long.toString(row.arrivalAt()))));
            new TreeMap<>(ledger.productionBatches()).values().forEach(row -> operations.add(Map.of(
                    "kind", "生产", "id", row.batchId(), "status", row.status().name(),
                    "quantity", Long.toString(row.quantity()), "amount", row.totalCost().toPlainString(),
                    "due", Long.toString(row.completesAt()))));
            new TreeMap<>(ledger.contracts()).values().forEach(row -> operations.add(Map.of(
                    "kind", "合同", "id", row.contractId(), "status", row.remainingQuantity() == 0 ? "DELIVERED" : "OPEN",
                    "quantity", Long.toString(row.remainingQuantity()), "amount", row.unitPrice().multiply(BigDecimal.valueOf(row.remainingQuantity())).toPlainString(),
                    "due", Long.toString(row.promisedDeliveryAt()))));
            new TreeMap<>(ledger.receivables()).values().forEach(row -> operations.add(Map.of(
                    "kind", "应收", "id", row.receivableId(), "status", row.status().name(),
                    "quantity", Long.toString(row.quantity()), "amount", row.outstanding().toPlainString(),
                    "due", Long.toString(row.dueAt()))));
            new TreeMap<>(ledger.expenseBills()).values().forEach(row -> operations.add(Map.of(
                    "kind", "费用", "id", row.billId(), "status", row.outstanding().signum() == 0 ? "PAID" : "UNPAID",
                    "quantity", "—", "amount", row.outstanding().toPlainString(), "due", Long.toString(row.closedAt()))));
            result.put("operations", operations);
        }
        if (person == null) {
            var report = ComposedCompanyReport.of(world, company);
            result.put("assets", report.totalAssets().toPlainString());
            result.put("liabilities", report.totalLiabilities().toPlainString());
            result.put("equity", report.totalEquity().toPlainString());
            result.put("profit", report.profit().toPlainString());
            result.put("reports", snapshot.manufacturing().orElseThrow().reports().values().stream()
                    .sorted(java.util.Comparator.comparingLong(edu.cufe.auction.world.ManufacturingRun.ReportEntry::position)
                            .reversed()).limit(30).map(row -> Map.of(
                            "businessId", row.businessId(), "accepted", row.accepted(),
                            "reason", row.reason(), "amount", row.amount().toPlainString(),
                            "timeMillis", Long.toString(row.timeMillis()))).toList());
        }
        return result;
    }

    private static Map<String, Object> receipt(edu.cufe.auction.world.ManufacturingRun.ReportEntry row) {
        return Map.of("businessId", row.businessId(), "accepted", row.accepted(), "reason", row.reason(),
                "amount", row.amount().toPlainString(), "timeMillis", Long.toString(row.timeMillis()));
    }

    static String schedule(WorldRuntime world, String person, Command command) throws Exception {
        var snapshot = world.snapshot();
        if (snapshot.position() != command.expectedPosition()) throw new WorldWebHost.PositionMismatch(snapshot.position());
        if (!world.profile().manifest().hasManufacturingFunding()) throw new IllegalStateException("当前档案未启用制造资金操作");
        String company = snapshot.governance().orElseThrow().board().charter().companyPartyId();
        if (!world.isActiveManager(company, person, snapshot.timeMillis())) throw new IllegalArgumentException("本人没有本公司经理权限");
        if (!ACTIONS.contains(command.action())) throw new IllegalArgumentException("未知制造动作");
        var opening = snapshot.manufacturing().orElseThrow().ledger().opening();
        String source = command.fundSource();
        String target = switch (command.action()) {
            case "PURCHASE_ORDER" -> ManufacturingFunding.cashToken(opening.supplierAccountId());
            case "PRODUCTION", "PAY_BILL" -> ManufacturingFunding.cashToken(opening.opsAccountId());
            default -> ManufacturingFunding.cashToken(opening.companyAccountId());
        };
        if (List.of("DELIVERY", "COLLECT").contains(command.action())) {
            source = ManufacturingFunding.cashToken(opening.customerAccountId());
        }
        return world.scheduleFundedManufacturing(command.commandId(), command.taskId(), command.dueTimeMillis(),
                person, command.action(), command.businessId(), command.resultId(), command.quantity(),
                command.unitPrice(), command.promisedDeliveryAt(), command.period(),
                ManufacturingFunding.POLICY_FROZEN_MAPPING_V1, source, target, command.durationMillis());
    }
}
