package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/** 有限外部客户用自身资金形成报价；向经理只公布可承诺数量，不泄露客户余额或未来结果。 */
final class ManufacturingCustomerOffers {
    private ManufacturingCustomerOffers() { }

    static void enrich(ObjectNode view, CompanyOperationsRun run, String company, long now,
                       Map<String, CashLedger.CashAccount> cash) {
        var ledger = run.company(company).ledger();
        var spec = ledger.spec();
        view.put("cash", cash.get(ledger.accounts().companyAccountId()).cash().toPlainString());
        view.set("reports", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(run.reports().values().stream()
                .filter(r -> company.equals(r.companyPartyId()))
                .sorted(Comparator.comparingLong(CompanyOperationsRun.ReportEntry::timeMillis)
                        .thenComparing(CompanyOperationsRun.ReportEntry::commandId)).toList()));
        long period = now / ManufacturingTemplateModel.PERIOD_MILLIS;
        view.put("periodExpense", spec.periodExpense().toPlainString());
        view.put("depreciationPerPeriod", spec.depreciationPerPeriod().toPlainString());
        for (var node : view.path("products")) {
            var product = spec.product(node.path("productId").asText(), node.path("generation").asInt());
            ((ObjectNode) node).put("cycleMillis", product.cycleMillis());
        }
        // 与纯核相同的最新可见报价及供应商累计容量口径。
        var latest = new TreeMap<String, ManufacturingTemplateModel.MaterialQuote>();
        spec.quotes().stream().filter(q -> q.visibleAt(now)).sorted(Comparator.comparingLong(ManufacturingTemplateModel.MaterialQuote::asOf))
                .forEach(q -> latest.put(q.materialId(), q));
        var quotes = view.putArray("visibleQuotes");
        for (var q : latest.values()) {
            long used = ledger.purchases().values().stream().filter(p -> p.supplierId().equals(q.supplierId())
                    && p.orderedAt() / ManufacturingTemplateModel.PERIOD_MILLIS == period).mapToLong(ManufacturingTemplateModel.PurchaseOrder::quantity).sum();
            quotes.addObject().put("materialId", q.materialId()).put("supplierId", q.supplierId())
                    .put("price", q.price().toPlainString()).put("validTo", q.validTo())
                    .put("remainingSupply", Math.max(0, q.supplyCapPerPeriod() - used));
        }
        var offers = view.putArray("customerOffers");
        for (var customer : spec.customers()) {
            String account = ledger.accounts().customerAccounts().get(customer.customerId());
            BigDecimal obligations = BigDecimal.ZERO;
            for (var other : run.companies()) {
                for (var c : other.ledger().contracts().values()) {
                    if (account.equals(other.ledger().accounts().customerAccounts().get(c.customerId())))
                        obligations = obligations.add(c.unitPrice().multiply(BigDecimal.valueOf(c.reserved())));
                }
                for (var ar : other.ledger().receivables().values()) {
                    if (account.equals(other.ledger().accounts().customerAccounts().get(ar.customerId())))
                        obligations = obligations.add(ar.outstanding());
                }
            }
            long occupied = 0;
            BigDecimal spent = BigDecimal.ZERO;
            for (var c : ledger.contracts().values()) if (c.customerId().equals(customer.customerId()) && c.period() == period) {
                long committed = c.delivered() + c.reserved();
                occupied += committed;
                spent = spent.add(c.unitPrice().multiply(BigDecimal.valueOf(committed)));
            }
            BigDecimal available = cash.get(account).cash().subtract(obligations).min(customer.budgetPerPeriod().subtract(spent)).max(BigDecimal.ZERO);
            long quantity = customer.allows(period) ? Math.min(Math.max(0, customer.demandPerPeriod() - occupied),
                    available.divide(spec.priceCap(), 0, RoundingMode.DOWN).longValueExact()) : 0;
            offers.addObject().put("customerId", customer.customerId()).put("quantity", quantity)
                    .put("unitPrice", spec.priceCap().toPlainString())
                    .put("basis", "EXP有限客户报价；已扣除未付货款与未交合同承诺；本版本无外生补款");
        }
    }
}
