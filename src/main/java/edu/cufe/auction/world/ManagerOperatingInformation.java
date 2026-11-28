package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;

/** manager-v1 当前经理公开经营信息；与开局资料及固定行为策略分开定版。 */
final class ManagerOperatingInformation {
    static final String VERSION = "manager-v1";
    private ManagerOperatingInformation() { }

    static void enrich(ObjectNode view, CompanyOperationsRun run, String company, long now,
                       Map<String, CashLedger.CashAccount> cash) {
        ManufacturingCustomerOffers.enrich(view, run, company, now, cash);
        view.put("operatingInformationVersion", VERSION);
        view.put("asOfMillis", now);
        view.put("units", "金额为实验记账元；产品数量为件，供应数量为对应物料BOM单位；时点与时长为模拟毫秒。");
        view.put("informationScope", "本公司当前公开报价及已发生台账；报价在执行时重新核验，未选择合同或经营动作，未保证付款。");
        for (var quote : view.path("visibleQuotes")) {
            ((ObjectNode) quote).put("leadTimeMillis", ManufacturingUpgrade.leadMillis(run, company))
                    .put("paymentTiming", "到货时按锁定单价支付；资金不足到货失败");
        }
        for (var offer : view.path("customerOffers")) {
            BigDecimal price = new BigDecimal(offer.path("unitPrice").asText());
            ((ObjectNode) offer).put("amount", price.multiply(BigDecimal.valueOf(offer.path("quantity").asLong())).toPlainString())
                    .put("basis", "当前有限客户报价；已扣除未付应收与未交合同承诺，并受本期剩余需求限制；后续报价和实际付款仍待当时核验");
        }
        view.putObject("contractTerms")
                .put("signingTimeline", "执行时须满足 promisedAt > now 且 expiresAt > promisedAt；两时点由提交的合同明确指定")
                .put("deliveryWindow", "promisedAt <= 实际交付执行时点 <= expiresAt")
                .put("paymentDueAfterDeliveryMillis", ManufacturingTemplateModel.PAYMENT_TERM_MILLIS)
                .put("paymentOutcome", "UNKNOWN")
                .put("futureCertificationOutcome", "UNKNOWN")
                .put("collectionRule", "到期后仍需经理发起COLLECT；客户当时资金不足可拒绝，不自动到账");
    }

    static void addOpeningReference(ObjectNode manager, String company) {
        manager.put("decisionMaterialDocId", RoleEvidenceDocuments.documentId(company));
        manager.put("decisionMaterialVersion", 1L);
        manager.put("decisionMaterialAsOfMillis", 0L);
        manager.put("decisionMaterialScope", "本人公司专属的时点0开局候选自述、财务与经营依据；需实际阅读，不是当前余额或当前报价，后续变化须另核实。");
    }
}
