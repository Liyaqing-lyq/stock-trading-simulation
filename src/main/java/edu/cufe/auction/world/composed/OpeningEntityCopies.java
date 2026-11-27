package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.BankPersistence;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 新世界开局的具名个人、公司或两者配置，身份、产权和业务状态均由正常世界入口新建。 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record OpeningEntityCopies(Investor investor, Company company) {
    /** 开局清单规则键。 */
    public static final String KEY = "opening-copies";
    /** 已校验的订单、报价和董事席位映射版。 */
    public static final String VERSION = "validated-draft-v1";
    /** 原开局入口单独创建一种主体的版本。 */
    public static final String INDEPENDENT = "independent-draft-v2";
    private static final String DRAFT="opening-copies-draft", SHA="opening-copies-sha256";
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 同类个人交易者的新身份和显示名。 */
    public record Investor(String source, String id, String name) {
        /** 校验来源和新身份。 */
        public Investor { text(source); text(id); text(name); if(source.equals(id)) throw new IllegalArgumentException("投资者必须使用新ID"); }
    }

    /** 一家未上市工程公司的配置及在本实验额外明确登记的客户邀请；报价不产生已签约合同。 */
    public record Company(String source, String id, String name, List<String> directors,
                          String customer, String sourceQuote, String invitationId) {
        /** 校验五席独立身份、来源、实名客户及本次新邀请编号。 */
        public Company {
            text(source); text(id); text(name);
            directors = List.copyOf(directors);
            if (source.equals(id) || directors.size()!=5 || new HashSet<>(directors).size()!=5)
                throw new IllegalArgumentException("公司需新ID及五个不同董事");
            directors.forEach(OpeningEntityCopies::text);
            if (customer != null || sourceQuote != null || invitationId != null) {
                text(customer); text(sourceQuote); text(invitationId);
                if (!EngineeringCustomerPolicy.CUSTOMER.equals(customer) || sourceQuote.equals(invitationId))
                    throw new IllegalArgumentException("另发邀请需要在册客户与新的机会编号");
            }
        }
    }

    /** 每个提供的副本分别绑定已登记来源，至少需要一种。 */
    public OpeningEntityCopies {
        if (investor == null && company == null) throw new IllegalArgumentException("至少需要投资者或工程公司配置");
        if (investor != null && company != null && investor.id().equals(company.id())) throw new IllegalArgumentException("公司与投资者ID冲突");
    }

    private static void text(String s) {
        if (s==null || s.isBlank() || !s.equals(s.strip())) throw new IllegalArgumentException("配置标识或名称不能为空或带首尾空格");
    }

    /** @return 新个人的独立现金及证券账户；未配置个人时为空 */
    public String investorAccount() { return investor == null ? null : "COPY-PERSON-" + investor.id(); }
    /** @return 新公司的独立经营账户；未配置公司时为空 */
    public String companyAccount() { return company == null ? null : "COPY-COMPANY-" + company.id(); }
    /** @return 本次开局是否使用已知的配置副本政策 */
    public static boolean enabled(Map<String,String> data) {
        return VERSION.equals(data.get(KEY)) || INDEPENDENT.equals(data.get(KEY));
    }
    private String version() { return investor != null && company != null ? VERSION : INDEPENDENT; }

    private OpeningEntityCopies forRun(String runId) {
        if (INDEPENDENT.equals(version()) && !RunManifest.OPENING_COPIES_RUN_ID.equals(runId))
            throw new IllegalArgumentException("独立复制仅适用于配置副本开局");
        return this;
    }

    private String canonical() {
        try { return JSON.writeValueAsString(this); }
        catch (IOException e) { throw new IllegalStateException("配置不能序列化",e); }
    }

    /** 把本次校验的草稿、规范文本摘要与政策版本写入即将冻结的开局清单。 */
    public void freeze(Map<String,String> data) {
        String body=canonical();
        data.put(KEY,version()); data.put(DRAFT,body); data.put(SHA,BankPersistence.digestText(body));
    }

    /** 验证清单冻结的草稿、摘要与规范化内容。 */
    public static OpeningEntityCopies fromManifest(Map<String,String> data) {
        String body=data.get(DRAFT);
        if (!enabled(data) || body==null || !BankPersistence.digestText(body).equals(data.get(SHA)))
            throw new IllegalArgumentException("开局副本摘要或版本不符");
        try {
            var plan=JSON.readValue(body,OpeningEntityCopies.class);
            if (plan==null || !plan.version().equals(data.get(KEY)) || !plan.canonical().equals(body)) throw new IllegalArgumentException("开局副本内容未规范化");
            return plan;
        } catch(IOException e) { throw new IllegalArgumentException("开局副本草稿无法读取",e); }
    }

    /** 验证冻结配置的主体组合、摘要及其所属运行政策。 */
    public static OpeningEntityCopies fromManifest(Map<String,String> data, String policyRunId) {
        return fromManifest(data).forRun(policyRunId);
    }

    static OpeningEntityCopies forOpening(Path journal,Path draft,String expectedRunId) throws IOException {
        if(!Files.exists(journal) || Files.size(journal)==0) {
            var plan=JSON.readValue(Files.readAllBytes(draft),OpeningEntityCopies.class);
            if(plan==null) throw new IOException("缺少开局配置副本");
            return plan.forRun(expectedRunId);
        }
        var first=WorldJournalScanner.scanTo(journal,1);
        if(first.isEmpty() || !"__open__".equals(first.commits().get(0).commandId()))
            throw new IOException("缺少完整的配置副本开局帧");
        if (!expectedRunId.equals(first.runId()))
            throw new IllegalArgumentException("存档属于其他运行，无法应用配置副本");
        var manifest=JSON.readTree(first.commits().get(0).commandContent()).required("manifest");
        return fromManifest(JSON.convertValue(manifest.required("dataVersions"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}), expectedRunId);
    }

    OpeningRegistry register(List<CashLedger.OpeningAccount> accounts,OpeningRegistry registry) {
        var parties=new ArrayList<>(registry.parties()); var owners=new ArrayList<>(registry.owners());
        if (investor != null) {
            boolean sourceOwnsAccount=accounts.stream().map(a->registry.owner(a.ownerId())).filter(Objects::nonNull)
                    .anyMatch(o->o.kind()==OpeningRegistry.OwnerKind.PERSON && investor.source().equals(o.partyId()));
            if(!sourceOwnsAccount) throw new IllegalArgumentException("来源投资者没有已登记的本人账户");
            parties.add(new OpeningRegistry.Party(investor.id(),OpeningRegistry.PartyKind.PERSON,investor.name()));
            owners.add(new OpeningRegistry.PropertyOwner(investor.id(),OpeningRegistry.OwnerKind.PERSON,investor.id()));
        }
        if (company != null) {
            parties.add(new OpeningRegistry.Party(company.id(),OpeningRegistry.PartyKind.ORGANIZATION,company.name()));
            owners.add(new OpeningRegistry.PropertyOwner(company.id(),OpeningRegistry.OwnerKind.ORGANIZATION,company.id()));
        }
        var target=new OpeningRegistry(parties,owners,registry.securities(),registry.allocations());
        if (company != null) for (var id:company.directors()) {
            if(target.parties().stream().noneMatch(p->p.partyId().equals(id)&&p.kind()==OpeningRegistry.PartyKind.PERSON))
                throw new IllegalArgumentException("新公司董事未登记为自然人："+id);
        }
        if (investor != null) accounts.add(new CashLedger.OpeningAccount(investorAccount(),investor.id(),BigDecimal.ZERO.setScale(2),"配置副本，自有现金开局为零"));
        if (company != null) accounts.add(new CashLedger.OpeningAccount(companyAccount(),company.id(),BigDecimal.ZERO.setScale(2),"未上市配置副本，开局无实收资本"));
        target.validateAgainst(accounts);
        return target;
    }

    EngineeringRun.Config engineering(EngineeringRun.Config original) {
        if (company == null) return original;
        var source=original.specOf(company.source());
        if(source==null || !EngineeringCustomerPolicy.MANDATE.contractorPartyId().equals(source.companyPartyId()))
            throw new IllegalArgumentException("来源不属于本次登记的工程业务模板");
        List<EngineeringRun.ContractPlan> invitations=List.of();
        if (company.invitationId()!=null) {
            var quote=source.contracts().stream().filter(c->c.contractId().equals(company.sourceQuote())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("来源报价不存在"));
            if(original.companies().stream().flatMap(c->c.contracts().stream()).anyMatch(p->p.contractId().equals(company.invitationId())))
                throw new IllegalArgumentException("新客户邀请编号已使用");
            invitations=List.of(new EngineeringRun.ContractPlan(company.invitationId(),quote.unitPrice(),quote.unitCost(),
                    quote.advanceBp(),quote.retentionBp(),quote.netDays(),quote.retentionNetDays(),quote.trancheUnits()));
        }
        var seats=new ArrayList<BoardModel.Seat>();
        for(int i=0;i<company.directors().size();i++) seats.add(new BoardModel.Seat(company.id()+"-seat-"+i,company.directors().get(i)));
        var board=new BoardModel.Charter(company.id(),seats,seats.get(0).seatId(),source.charter().quorum());
        var companies=new ArrayList<>(original.companies());
        companies.add(new EngineeringRun.CompanySpec(company.id(),company.id(),companyAccount(),source.customerAccountId(),
                source.supplierAccountId(),source.opsAccountId(),source.unitMillis(),source.laborBp(),board,invitations,source.customerPool(),source.companyPartyId()));
        return new EngineeringRun.Config(original.paramSource(),companies,original.customerEvents(),original.customerMandate());
    }
}
