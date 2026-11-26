package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 工程客户的实验授权及复核规则。客户使用原始账户，两份机会共担同一个现金余额。 */
public final class EngineeringCustomerPolicy {
    /** 独立工程档的运行标识。 */
    public static final String RUN_ID = "web-engineering-customer-operating-v1";
    /** 工程客户规则的冻结版本。 */
    public static final String KEY = "engineering-customer-policy", VERSION = "obligation-review-v1";
    /** 委托机构、实名代表以及其在原始账户表中的财产主体。 */
    public static final String CUSTOMER = "eng-a-customer", REPRESENTATIVE = "eng-a-customer-representative";
    /** 原工程客户的财产主体标识，沿用其银行登记。 */
    public static final String OWNER = "eng-a-cus";
    static final String ACCOUNT = "ENG-A-CUS", COMPANY = "eng-a";
    static final String PREFIX = "engineering-opportunity:", REVIEW = "engineering-review:";
    static final long REVIEW_MILLIS = 86_400_000L;

    /**
     * 业主在运行开局冻结的授权关系。
     * @param organizationPartyId 委托机构
     * @param representativePartyId 代理本委托的自然人
     * @param contractorPartyId 受邀签约的工程企业
     * @param accountId 客户付款账户
     */
    public record Mandate(String organizationPartyId, String representativePartyId, String contractorPartyId, String accountId) { }
    /** 本实验档唯一的工程客户委托，不带转账、证券交易或公司任职授权。 */
    public static final Mandate MANDATE = new Mandate(CUSTOMER, REPRESENTATIVE, COMPANY, ACCOUNT);

    private EngineeringCustomerPolicy() { }

    /** 原工程客户财产主体改为实名机构，保留账户、期初余额及权益。 */
    public static OpeningRegistry register(OpeningRegistry registry) {
        var parties = new ArrayList<>(registry.parties());
        parties.add(new OpeningRegistry.Party(CUSTOMER, OpeningRegistry.PartyKind.ORGANIZATION, "EXP 栖构项目业主"));
        parties.add(new OpeningRegistry.Party(REPRESENTATIVE, OpeningRegistry.PartyKind.PERSON, "EXP 栖构项目业主授权代表"));
        var owners = new ArrayList<>(registry.owners());
        if (!owners.remove(new OpeningRegistry.PropertyOwner(OWNER, OpeningRegistry.OwnerKind.AGGREGATE, null)))
            throw new IllegalArgumentException("工程客户原始财产主体不匹配");
        owners.add(new OpeningRegistry.PropertyOwner(OWNER, OpeningRegistry.OwnerKind.ORGANIZATION, CUSTOMER));
        return new OpeningRegistry(parties, owners, registry.securities(), registry.allocations());
    }

    static void validate(OpeningRegistry registry, EngineeringRun.Config config,
                         Map<String,CashLedger.CashAccount> accounts, List<String> readers, String copiedCompany) {
        var spec = config.specOf(COMPANY);
        if (!MANDATE.equals(config.customerMandate()) || config.companies().size() != (copiedCompany == null ? 1 : 2) || spec == null
                || copiedCompany != null && (config.specOf(copiedCompany)==null || !ACCOUNT.equals(config.specOf(copiedCompany).customerAccountId()))
                || !List.of("A1", "A2").equals(spec.contracts().stream().map(EngineeringRun.ContractPlan::contractId).sorted().toList())
                || !ACCOUNT.equals(spec.customerAccountId()) || !OWNER.equals(accounts.get(ACCOUNT).ownerId())
                || !new OpeningRegistry.PropertyOwner(OWNER, OpeningRegistry.OwnerKind.ORGANIZATION, CUSTOMER).equals(registry.owner(OWNER))
                || !readers.contains(REPRESENTATIVE) || registry.parties().stream().noneMatch(p -> REPRESENTATIVE.equals(p.partyId())
                        && p.kind() == OpeningRegistry.PartyKind.PERSON)
                || !config.customerEvents().isEmpty())
            throw new IllegalArgumentException("机构代表、工程客户账户或申请复核规则与冻结档不符");
    }

    static void customerView(ObjectMapper mapper, ObjectNode node, EngineeringRun run,
                             Map<String,CashLedger.CashAccount> accounts, BigDecimal transferable) {
        var mandate = run.config().customerMandate();
        node.put("organization", mandate.organizationPartyId()).put("representative", mandate.representativePartyId())
                .put("contractor", mandate.contractorPartyId()).put("account", mandate.accountId());
        node.put("cash", accounts.get(ACCOUNT).cash().toPlainString());
        node.put("freeCash", transferable.toPlainString());
        node.set("requests", mapper.valueToTree(run.customer().requestsOf(COMPANY)));
        node.set("responses", mapper.valueToTree(run.customer().responsesOf(COMPANY)));
        node.set("payments", mapper.valueToTree(run.customer().paymentsOf(COMPANY)));
        appendInvoices(node, run, COMPANY);
        var additionalCompanies = run.config().companies().stream()
                .filter(spec -> !COMPANY.equals(spec.companyPartyId()) && !spec.contracts().isEmpty()).toList();
        if (!additionalCompanies.isEmpty()) {
            var invited = node.putArray("additionalInvitations");
            for (var spec : additionalCompanies) {
                var item=invited.addObject().put("contractor",spec.companyPartyId()).put("account",spec.customerAccountId());
                item.set("opportunities",mapper.valueToTree(spec.contracts().stream().map(EngineeringRun.ContractPlan::contractId).toList()));
                item.set("requests",mapper.valueToTree(run.customer().requestsOf(spec.companyPartyId())));
                item.set("responses",mapper.valueToTree(run.customer().responsesOf(spec.companyPartyId())));
                item.set("payments",mapper.valueToTree(run.customer().paymentsOf(spec.companyPartyId())));
                appendInvoices(item, run, spec.companyPartyId());
            }
        }
    }

    private static void appendInvoices(ObjectNode node, EngineeringRun run, String company) {
        var invoices = node.putArray("invoices");
        new java.util.TreeMap<>(run.stateOf(company).contracts()).forEach((id, contract) -> {
            for (var invoice : contract.invoices()) {
                var row = invoices.addObject().put("contractId", id).put("trancheIndex", invoice.trancheIndex());
                row.put("dueAt", invoice.dueAt()).put("currentOutstanding", invoice.currentOutstanding().toPlainString());
                row.put("retentionDueAt", invoice.retentionDueAt()).put("retentionOutstanding", invoice.retentionOutstanding().toPlainString());
            }
        });
    }
}
