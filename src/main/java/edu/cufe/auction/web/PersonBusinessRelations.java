package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.CompanyOperationsRun;
import edu.cufe.auction.world.RoleDeliberation;
import edu.cufe.auction.world.RoleEvidenceDocuments;
import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.WorldRuntime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 个人关系页只连接当前职务可查询的业务记录和本人仍可回忆的有版本资料。 */
public final class PersonBusinessRelations {
    private static final ObjectMapper JSON = new ObjectMapper();
    private PersonBusinessRelations() { }

    /** 单条本人可见关系；数额和毫秒以十进制文本传输，依据指向实际单据或本人明细。 */
    public record Edge(String from, String layer, String relation, String to, String quantity,
                       String unit, String timeMillis, String basis) { }

    static List<Edge> of(WorldRuntime world, WorldRuntime.Snapshot snapshot, RoleView view) throws java.io.IOException {
        var rows = new ArrayList<Edge>();
        var bank = world.bankOf().orElse(null);
        if (bank != null) {
            var decoded = bank.decode(snapshot.accounts());
            var owners = new java.util.TreeSet<String>();
            for (var account : view.accounts()) owners.add(account.ownerId());
            for (var owner : owners) for (var item : WorldWebHost.personalDepositRows(owner, decoded.config(), decoded.state())) {
                String amount = item.get("amount").toString(), party = item.get("partyKey").toString(), at = Long.toString(view.timeMillis());
                if (new java.math.BigDecimal(amount).signum() > 0) rows.add(new Edge(party, "资金", "持有存款", item.get("bankKey").toString(),
                        amount, "元", at, "本人获授权的银行存款余额 · " + party + " / " + item.get("bankKey")));
            }
            for (var item : WorldWebHost.personalCustodyRows(owners, decoded.config().profile(), decoded.book()))
                rows.add(new Edge(item.get("partyKey").toString(), "资金", "持有托管受益权", item.get("custodian").toString(), item.get("amount").toString(),
                        "元", Long.toString(view.timeMillis()), "本人获授权的托管明细 · " + item.get("partyKey") + " / " + item.get("custodian")));
        }
        var run = world.operationsRun().orElse(null);
        if (run != null) for (var company : run.companies()) {
            String id = company.companyPartyId();
            if (RoleDeliberation.hasEvidence(world.profile().manifest().dataVersions().get("role-context"))) {
                String doc = RoleEvidenceDocuments.documentId(id);
                var read = view.observations().stream().filter(r -> doc.equals(r.ref().docId()) && r.ref().version() == 1)
                        .max(Comparator.comparingLong(RoleView.Experience::completedAt)).orElse(null);
                if (read != null) {
                    var body = JSON.readTree(read.body());
                    for (var quote : body.at("/openingOperations/visibleQuotes")) {
                        rows.add(new Edge(quote.path("supplierId").asText(), "经营", "材料报价", id,
                                quote.path("price").asText(), "元/BOM单位", body.get("asOfMillis").asText(),
                                doc + " v1 · " + quote.path("materialId").asText() + " · 本人读完于 " + read.completedAt() + " 毫秒"));
                    }
                }
            }
            if (!world.isActiveManager(id, view.personPartyId(), view.timeMillis())) continue;
            var ledger = company.ledger();
            for (var purchase : ledger.purchases().values())
                rows.add(new Edge(id, "经营", "下达采购单", purchase.supplierId(), Long.toString(purchase.quantity()),
                        "BOM单位", Long.toString(purchase.orderedAt()), "采购单 " + purchase.purchaseOrderId() + " · " + purchase.materialId()));
            for (var contract : ledger.contracts().values())
                rows.add(new Edge(contract.customerId(), "经营", "订购产品", id, Long.toString(contract.quantity()),
                        "件", Long.toString(contract.signedAt()), "销售合同 " + contract.contractId() + " · " + contract.productId()));
            for (var invoice : ledger.receivables().values())
                rows.add(new Edge(id, "经营", "交付产品", invoice.customerId(), Long.toString(invoice.quantity()), "件",
                        Long.toString(invoice.recognizedAt()), "交付应收单 " + invoice.receivableId() + " · 合同 " + invoice.contractId()));
            for (var report : run.reports().values()) {
                if (!report.companyPartyId().equals(id) || !report.accepted() || report.amount().signum() <= 0) continue;
                String source = "经营回报 " + report.taskId() + " · 单号 " + report.businessId();
                if ("RECEIVE_MATERIAL".equals(report.action())) {
                    var purchase = ledger.purchases().get(report.businessId());
                    if (purchase != null) rows.add(payment(id, "支付采购款", purchase.supplierId(), report, source));
                } else if ("COLLECT".equals(report.action())) {
                    var invoice = ledger.receivables().get(report.businessId());
                    if (invoice != null) rows.add(payment(invoice.customerId(), "支付客户货款", id, report, source));
                }
            }
        }
        rows.sort(Comparator.comparingLong((Edge row) -> Long.parseLong(row.timeMillis())).reversed()
                .thenComparing(Edge::basis).thenComparing(Edge::from).thenComparing(Edge::to));
        return List.copyOf(rows);
    }

    private static Edge payment(String from, String relation, String to, CompanyOperationsRun.ReportEntry report, String source) {
        return new Edge(from, "资金", relation, to, report.amount().toPlainString(), "元", Long.toString(report.timeMillis()), source);
    }
}
