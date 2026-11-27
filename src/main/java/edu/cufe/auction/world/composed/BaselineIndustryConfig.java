package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.util.*;

/** 最终十家公司实验开局增量。全部数值为EXP，不声称真实公司校准或预先作出的董事决议。 */
public final class BaselineIndustryConfig {
    private BaselineIndustryConfig() { }
    /** 明确档案名；旧增量档不迁移。 */
    public static final String PROFILE="industry-baseline-v1";
    /** 数字、陈酿独立五席董事，经理只是候选人。 */
    public static List<String> directors(String company) {
        return java.util.stream.IntStream.rangeClosed(1,5).mapToObj(i->company+"-director-"+i).toList();
    }
    /** 数字服务固定技术准入，不冒充董事商业化或注资决议；每次新增投入仍需真实预算。 */
    public static DigitalCompanyRun.Config digital() {
        var c=DigitalIndustryConfig.runConfig();
        return new DigitalCompanyRun.Config(c.companyPartyId(),c.symbol(),c.model(),c.stageEvidence(),Map.of(),true);
    }
    /** 陈酿需求只有明确六期场景，删除适配层测试用的远期隐私探针。 */
    public static AgedCompanyRun.Config aged() {
        var c=AgedIndustryConfig.modelConfig();
        var model=new AgedInventoryModel.Config(c.companyPartyId(),c.managerPersonId(),c.companyAccountId(),c.supplierAccountId(),c.conversionAccountId(),c.fixedAccountId(),c.terminalAccountId(),c.rawUnitCost(),c.conversionPerUnit(),c.fixedPerPeriod(),c.periodMillis(),c.capacityPerPeriod(),c.agingPeriods(),c.shelfPeriods(),c.spoilageBp(),c.prepayBp(),c.channels(),c.demand().stream().filter(d->d.period()<=5).toList(),c.expLabel());
        return new AgedCompanyRun.Config(AgedIndustryConfig.COMPANY,AgedIndustryConfig.SYMBOL,model);
    }
    /** 一年有限来水，用于含陈酿成熟与到期损失的长周期调试；一年后停机要求新情景。 */
    public static HydroCompanyRun.Config hydro() {
        var c=HydroIndustryConfig.config();var m=c.model();long day=86_400_000L;
        var series=new HydroModel.InflowSeries(List.of(new HydroModel.InflowSegment(0,90*day,600),new HydroModel.InflowSegment(90*day,180*day,180),new HydroModel.InflowSegment(180*day,270*day,900),new HydroModel.InflowSegment(270*day,360*day,400)));
        return new HydroCompanyRun.Config(c.companyPartyId(),c.symbol(),new HydroModel.Config(m.companyOwnerId(),m.companyAccountId(),m.buyerAccountId(),m.contractorAccountId(),m.physical(),series,new HydroModel.SalesContract(80,new BigDecimal("0.40"),360*day),m.terms()),c.maintenanceMillis(),c.maintenanceCost());
    }

    /** 普通十公司长周期实验的有限来水。前 360 日沿用原路径，其后各有四段偏干、偏湿季节；
     * 每段 90 日，单位为 m³/h。原购电方只对第一年的合同电量承担义务。
     * @return 共 1080 日的 EXP 情景，物理参数、购电合同及费用沿用原开局
     */
    public static HydroCompanyRun.Config extendedHydro() {
        var original = hydro();
        var model = original.model();
        var segments = new ArrayList<>(model.inflow().segments());
        long start = model.inflow().endsAtMillis();
        long season = 90L * 86_400_000L;
        for (long flow : new long[]{480, 120, 760, 320, 720, 240, 1000, 500}) {
            segments.add(new HydroModel.InflowSegment(start, start + season, flow));
            start += season;
        }
        var extended = new HydroModel.Config(model.companyOwnerId(), model.companyAccountId(),
                model.buyerAccountId(), model.contractorAccountId(), model.physical(),
                new HydroModel.InflowSeries(segments), model.contract(), model.terms());
        return new HydroCompanyRun.Config(original.companyPartyId(), original.symbol(), extended,
                original.maintenanceMillis(), original.maintenanceCost());
    }
    /** 从增量档删除原单制造和第二工程账户，再加入独立新公司与有限投资者现金。 */
    public static List<CashLedger.OpeningAccount> openings(List<CashLedger.OpeningAccount> base) {
        var result=new ArrayList<CashLedger.OpeningAccount>();
        for(var account:base) {
            if(List.of("CO","SUP","CUS","OPS","LOCK").contains(account.accountId()) || account.accountId().startsWith("ENG-B")) continue;
            if(List.of("INV","ACC-FUND").contains(account.accountId())) result.add(new CashLedger.OpeningAccount(account.accountId(),account.ownerId(),new BigDecimal("10000.00"),"EXP最终十公司：有限初始投资资金"));
            else result.add(account);
        }
        result.addAll(DigitalIndustryConfig.openings());result.addAll(AgedIndustryConfig.openings());
        result.add(new CashLedger.OpeningAccount("DIGITAL-FOUNDER","digital-founder",new BigDecimal("0.00"),"EXP历史持股，无额外现金"));
        result.add(new CashLedger.OpeningAccount("AGED-FOUNDER","aged-founder",new BigDecimal("0.00"),"EXP历史持股，无额外现金"));
        return List.copyOf(result);
    }
    /** 仅保留十发行人；股份从既有持有人向两个可交易账户分配，发行总量不变。 */
    public static OpeningRegistry registry(OpeningRegistry base,List<CashLedger.OpeningAccount> accounts) {
        Set<String> accountIds=new HashSet<>();accounts.forEach(a->accountIds.add(a.accountId()));
        var parties=new ArrayList<>(base.parties());var owners=new ArrayList<>(base.owners());
        parties.replaceAll(x->x.partyId().equals("cufe") ? new OpeningRegistry.Party(x.partyId(),x.kind(),"综合资产管理机构（EXP）"):x);
        Set<String> ownerIds=new HashSet<>();owners.forEach(o->ownerIds.add(o.ownerId()));
        Set<String> partyIds=new HashSet<>();parties.forEach(o->partyIds.add(o.partyId()));
        for(var a:accounts) if(ownerIds.add(a.ownerId())) {
            if(partyIds.add(a.ownerId())) parties.add(new OpeningRegistry.Party(a.ownerId(),OpeningRegistry.PartyKind.ORGANIZATION,a.ownerId()));
            owners.add(new OpeningRegistry.PropertyOwner(a.ownerId(),OpeningRegistry.OwnerKind.ORGANIZATION,a.ownerId()));
        }
        for(String company:List.of(DigitalIndustryConfig.COMPANY,AgedIndustryConfig.COMPANY)) {
            var people=new ArrayList<>(directors(company));people.add(company.equals(DigitalIndustryConfig.COMPANY)?DigitalIndustryConfig.MANAGER_PERSON:AgedIndustryConfig.MANAGER_PERSON);
            for(String person:people) if(partyIds.add(person)) parties.add(new OpeningRegistry.Party(person,OpeningRegistry.PartyKind.PERSON,person));
        }
        var securities=new ArrayList<>(base.securities().stream().filter(x->!List.of("cufe","eng-b").contains(x.issuerOrganizationId())).toList());
        securities.add(new OpeningRegistry.Security(DigitalIndustryConfig.SYMBOL,DigitalIndustryConfig.COMPANY,1000,new BigDecimal("10.00")));
        securities.add(new OpeningRegistry.Security(AgedIndustryConfig.SYMBOL,AgedIndustryConfig.COMPANY,1000,new BigDecimal("10.00")));
        Set<String> symbols=new HashSet<>();securities.forEach(x->symbols.add(x.symbol()));
        var allocations=new ArrayList<>(base.allocations().stream().filter(x->symbols.contains(x.symbol()) && accountIds.contains(x.accountId())).toList());
        allocations.add(new OpeningRegistry.ShareAllocation("DIGITAL-FOUNDER",DigitalIndustryConfig.SYMBOL,1000,new BigDecimal("10.0000"),OpeningRegistry.ShareClass.FLOAT));
        allocations.add(new OpeningRegistry.ShareAllocation("AGED-FOUNDER",AgedIndustryConfig.SYMBOL,1000,new BigDecimal("10.0000"),OpeningRegistry.ShareClass.FLOAT));
        for(var security:securities) {
            var source=allocations.stream().filter(x->x.symbol().equals(security.symbol()) && x.classification()==OpeningRegistry.ShareClass.FLOAT).max(Comparator.comparingLong(OpeningRegistry.ShareAllocation::quantity)).orElseThrow();
            long share=source.quantity()/10;long transferred=source.accountId().equals("INV") ? share:2*share;
            allocations.remove(source);allocations.add(new OpeningRegistry.ShareAllocation(source.accountId(),source.symbol(),source.quantity()-transferred,source.costPrice(),source.classification()));
            if(!source.accountId().equals("INV")) allocations.add(new OpeningRegistry.ShareAllocation("INV",source.symbol(),share,source.costPrice(),OpeningRegistry.ShareClass.FLOAT));
            allocations.add(new OpeningRegistry.ShareAllocation("ACC-FUND",source.symbol(),share,source.costPrice(),OpeningRegistry.ShareClass.FLOAT));
        }
        return new OpeningRegistry(parties,owners,securities,allocations);
    }
}
