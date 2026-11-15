package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.*;
import edu.cufe.auction.web.WorldWebScenario;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 显式行业组合实验档：资源与已有公司共享唯一资金、调度、角色和日志。所有数值均为EXP。 */
public final class ComposedIndustryScenario {
    private ComposedIndustryScenario() { }
    /** 资源公司的组织、经营账户与经理候选人。 */
    public static final String RESOURCE = "resources", CASH = "RES-CASH", MANAGER = "resource-manager";
    /** 独立五席董事；没有预置经理。 */
    public static final List<String> DIRECTORS = List.of("resource-director-1", "resource-director-2",
            "resource-director-3", "resource-director-4", "resource-director-5");
    private static final String[][] ACCOUNTS = {
            {CASH, RESOURCE, "10000.00"}, {"RES-OPS", "resource-ops", "0.00"},
            {"RES-COAL-CUSTOMER", "resource-customer", "10000.00"}, {"RES-GRID", "resource-grid", "10000.00"},
            {"RES-CONVERTER", "resource-converter", "0.00"}, {"RES-CONTRACTOR", "resource-contractor", "0.00"},
            {"RES-DISPOSAL", "resource-disposal", "2000.00"}, {"RES-SPONSOR", "resource-sponsor", "10000.00"},
            {"RES-FOUNDER", "resource-founder", "0.00"}};

    /** @return 有界外部现金和历史资本；没有自动补足。 */
    public static List<CashLedger.OpeningAccount> openings() {
        List<CashLedger.OpeningAccount> result = new ArrayList<>(ComposedManufacturingThreeScenario.openings());
        for (String[] row : ACCOUNTS) result.add(new CashLedger.OpeningAccount(row[0], row[1], money(row[2]), "EXP资源公司/外部部门历史禀赋"));
        return List.copyOf(result);
    }

    /** @return 真实组织、人物、发行股份和持有者。 */
    public static OpeningRegistry registry() {
        var base = ComposedManufacturingThreeScenario.registry();
        var parties = new ArrayList<>(base.parties());
        var owners = new ArrayList<>(base.owners());
        for (String[] row : ACCOUNTS) {
            parties.add(new OpeningRegistry.Party(row[1], OpeningRegistry.PartyKind.ORGANIZATION, row[1]));
            owners.add(new OpeningRegistry.PropertyOwner(row[1], OpeningRegistry.OwnerKind.ORGANIZATION, row[1]));
        }
        List<String> people = new ArrayList<>(DIRECTORS); people.add(MANAGER);
        for (String person : people) parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON, person));
        var securities = new ArrayList<>(base.securities());
        securities.add(new OpeningRegistry.Security("RESOURCE", RESOURCE, 1000L, money("10.00")));
        var allocations = new ArrayList<>(base.allocations());
        allocations.add(new OpeningRegistry.ShareAllocation("RES-FOUNDER", "RESOURCE", 1000L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }

    /** @return 冻结资源、运输、发电、有限需求与真实对手账户。 */
    public static ResourceCompanyRun.Config resourceConfig() { return resourceConfig(false); }

    /** 新资源档提供固定技术许可与报价，不预批董事预算。 */
    public static ResourceCompanyRun.Config resourceConfig(boolean expansion) {
        var model = new IntegratedResourcesModel.Config(RESOURCE, CASH, "RES-OPS",
                "resource-customer", "RES-COAL-CUSTOMER", "resource-grid", "RES-GRID",
                "resource-converter", "RES-CONVERTER", "resource-contractor", "RES-CONTRACTOR",
                "resource-disposal", "RES-DISPOSAL", "resource-sponsor", "RES-SPONSOR",
                money("20000.00"), money("2.00"), money("3.00"), money("800.00"), 200L, money("50.00"),
                1000L, 5L, 1000L, 600L, money("1800.00"), 5L, money("4000.00"),
                money("100.00"), money("50.00"), new BigDecimal("0.25"),
                List.of(new IntegratedResourcesModel.Seam("resource-seam-a", 10000L, money("1.00")),
                        new IntegratedResourcesModel.Seam("resource-seam-b", 5000L, money("1.50"))), expansion
                        ? Map.of("resource-permit", new IntegratedResourcesModel.Approval("resource-permit", IntegratedResourcesModel.APPROVAL_CAPEX, 0)) : Map.of());
        return new ResourceCompanyRun.Config(RESOURCE, "RESOURCE", model, expansion ? new ResourceCompanyRun.ExpansionOffer(
                money("2000.00"), 30 * IntegratedResourcesModel.DAY_MILLIS, 3, 600, "resource-permit", false) : null);
    }

    /** @return 新行业档冻结版本；旧三制造档不获得任何资源状态。 */
    public static RunManifest manifest() { return manifest(false); }

    /** 扩产档显式版本冻结，原行业档不迁移。 */
    public static RunManifest manifest(boolean expansion) { return manifest(expansion, false); }
    private static RunManifest manifest(boolean expansion, boolean hydro) { return manifest(expansion,hydro,false); }
    private static RunManifest manifest(boolean expansion, boolean hydro, boolean baseline) {
        RunManifest base = ComposedManufacturingThreeScenario.manifest();
        Map<String, String> data = new TreeMap<>(base.dataVersions());
        data.put("profile", hydro ? "industry-hydro-v1" : expansion ? "industry-runnable-v2" : "industry-runnable-v1");
        if (hydro) { data.put("hydro-operations","v1"); data.put("hydro-decision","v1"); data.put("hydro-company",HydroIndustryConfig.COMPANY); }
        if (expansion) data.put("resource-expansion", "v1");
        data.put("operating-observation", "v2"); data.put("role-account-projection", "authorized-v1");
        data.put("company-decision", "v1"); data.put("company-introduction", "v1"); data.put("operations-decision", "v1");
        data.put("resource-operations", "v1"); data.put("resource-decision", "v1"); data.put("resource-company", RESOURCE); data.put("research-company", "pharma");
        Map<String,Integer> modules=new TreeMap<>(base.moduleVersions());
        if(baseline) {
            data.put("profile",BaselineIndustryConfig.PROFILE);
            for(String kind:List.of("digital","aged")) {data.put(kind+"-operations","v1");data.put(kind+"-decision","v1");data.put(kind+"-company",kind+"-company");}
            modules.keySet().removeAll(List.of("manufacturing","governance","dividend","manufacturing-funding"));
        }
        return new RunManifest(baseline ? "web-industry-baseline-v1" : hydro ? "web-industry-hydro-v1" : expansion ? "web-industry-v2" : "web-industry-v1", base.seed(), base.rulesVersion(), modules, data, base.modelConfigVersion());
    }

    /** 从同一冻结开局建立或恢复；不生成预算、任命或经营成功结果。 */
    public static WorldRuntime open(Path journal) throws Exception { return open(journal, false); }

    /** 新扩产预览入口；没有经理或预算捷径。 */
    public static WorldRuntime openExpanded(Path journal) throws Exception { return open(journal, true); }

    private static WorldRuntime open(Path journal, boolean expansion) throws Exception { return open(journal, expansion, false); }

    /** 水电加入同一行业世界；仍为增量调试拓扑，不等于最终十家公司。 */
    public static WorldRuntime openWithHydro(Path journal) throws Exception { return open(journal, true, true); }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro) throws Exception { return open(journal,expansion,hydro,false); }

    /** 最终十公司世界：三个制造、工程、银行、研发、资源、数字、陈酿与水电，共享唯一日志。 */
    public static WorldRuntime openBaseline(Path journal) throws Exception { return open(journal,true,true,true); }

    /** 以A股经营机制为参考的虚构十公司新档；旧档不改名。 */
    public static WorldRuntime openPrototype(Path journal) throws Exception {
        return open(journal, true, true, true, true);
    }

    /** 独立行为整改实验档；显式冻结规则，不改变旧季度或自动授予经理权限。 */
    public static WorldRuntime openBehavior(Path journal) throws Exception {
        return open(journal, true, true, true, true, true);
    }

    /** 显式互通新档，所有人物与经营在同一世界推进；原实验文件不迁移。 */
    public static WorldRuntime openConnected(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, true);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline) throws Exception {
        return open(journal, expansion, hydro, baseline, false);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, false);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, false);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, connected, false);
    }

    /** 新模型运行档：显式冻结组合公司工具修订，不改变旧档案的回执重演。 */
    public static WorldRuntime openAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected, boolean autonomous) throws Exception {
        return open(journal, expansion, hydro, baseline, prototype, behavior, connected, autonomous, false);
    }

    /** 事件唤醒实验新档；旧小时窗口档保留原输入与重演。 */
    public static WorldRuntime openEventAutonomous(Path journal) throws Exception {
        return open(journal, true, true, true, true, false, false, true, true);
    }

    private static WorldRuntime open(Path journal, boolean expansion, boolean hydro, boolean baseline, boolean prototype, boolean behavior, boolean connected, boolean autonomous, boolean eventDriven) throws Exception {
        var original = ComposedManufacturingThreeScenario.bankOpening().config().profile();
        Map<String, BankingProfile.PartyRef> parties = new TreeMap<>(original.parties());
        List<String> deposits = new ArrayList<>(original.depositAccounts());
        for (String[] row : ACCOUNTS) parties.put(row[1], new BankingProfile.PartyRef(row[1], row[1], row[0], null));
        for (String company : List.of(ComposedManufacturingThreeScenario.TECH, ComposedManufacturingThreeScenario.STRUCT,
                ComposedManufacturingThreeScenario.VERSA, ComposedPharmaScenario.PHARMA, RESOURCE)) {
            var old = parties.get(company); String account = "DEPOSIT-" + company;
            parties.put(company, new BankingProfile.PartyRef(company, old.ownerId(), old.cashAccountId(), account)); deposits.add(account);
        }
        if (hydro) {
            for (var account : HydroIndustryConfig.ACCOUNTS) {
                String deposit = account.ownerId().equals(HydroIndustryConfig.COMPANY) ? "DEPOSIT-" + account.ownerId() : null;
                parties.put(account.ownerId(), new BankingProfile.PartyRef(account.ownerId(), account.ownerId(), account.accountId(), deposit));
                if (deposit != null) deposits.add(deposit);
            }
        }
        if(baseline) {
            var accounts=BaselineIndustryConfig.openings(new ArrayList<>(openings()));
            Set<String> activeAccounts=new HashSet<>();accounts.forEach(a->activeAccounts.add(a.accountId()));
            HydroIndustryConfig.ACCOUNTS.forEach(a->activeAccounts.add(a.accountId()));
            parties.entrySet().removeIf(e->!activeAccounts.contains(e.getValue().cashAccountId()));
            for(var a:accounts) if(parties.values().stream().noneMatch(ref->ref.ownerId().equals(a.ownerId())) && original.banks().values().stream().noneMatch(ref->ref.ownerId().equals(a.ownerId()))) {
                String deposit=List.of(DigitalIndustryConfig.COMPANY,AgedIndustryConfig.COMPANY).contains(a.ownerId()) ? "DEPOSIT-"+a.ownerId():null;
                parties.put(a.ownerId(),new BankingProfile.PartyRef(a.ownerId(),a.ownerId(),a.accountId(),deposit));
                if(deposit!=null) deposits.add(deposit);
            }
        }
        var buckets=new TreeMap<>(original.buckets());
        if(baseline) {
            buckets.keySet().retainAll(parties.keySet());
            deposits.removeIf(d->parties.values().stream().noneMatch(ref->d.equals(ref.depositGl())) && original.custodians().values().stream().noneMatch(ref->d.equals(ref.liabilityId())));
        }
        if(connected) {
            parties.put("nina",new BankingProfile.PartyRef("nina","nina","HOUSE-NINA",null));
            parties.put("omar",new BankingProfile.PartyRef("omar","omar","HOUSE-OMAR",null));
        }
        var profile = new BankingProfile(original.banks(), parties, original.custodians(), buckets, original.groups(),
                baseline ? buckets.values().stream().map(BankingProfile.RiskBucket::loanGl).distinct().sorted().toList() : original.loanAccounts(),
                baseline ? buckets.values().stream().map(BankingProfile.RiskBucket::llpGl).distinct().sorted().toList() : original.llpAccounts(), deposits, original.providerKey(), original.subordinatedOwnerKey(), original.backgroundBorrowerKey());
        var banking = BankOpeningRequest.of(ComposedWorldScenario.config(profile), ComposedWorldScenario.endowment(), Map.of(), Map.of());
        var charters = new TreeMap<>(ComposedManufacturingThreeScenario.charters());
        List<BoardModel.Seat> seats = new ArrayList<>();
        for (int i = 0; i < 5; i++) seats.add(new BoardModel.Seat("resource-seat-" + i, DIRECTORS.get(i)));
        charters.put(RESOURCE, new BoardModel.Charter(RESOURCE, seats, "resource-seat-0", BoardModel.QUORUM));
        List<String> readers = new ArrayList<>(ComposedManufacturingThreeScenario.readers()); readers.addAll(DIRECTORS); readers.add(MANAGER);
        if (hydro) {
            var hydroSeats = new ArrayList<BoardModel.Seat>();
            for (int i=0;i<5;i++) hydroSeats.add(new BoardModel.Seat("hydro-seat-"+i,HydroIndustryConfig.DIRECTORS.get(i)));
            charters.put(HydroIndustryConfig.COMPANY,new BoardModel.Charter(HydroIndustryConfig.COMPANY,hydroSeats,"hydro-seat-0",BoardModel.QUORUM));
            readers.addAll(HydroIndustryConfig.DIRECTORS); readers.add(HydroIndustryConfig.MANAGER);
        }
        if(baseline) for(String company:List.of(DigitalIndustryConfig.COMPANY,AgedIndustryConfig.COMPANY)) {
            var people=BaselineIndustryConfig.directors(company);var companySeats=new ArrayList<BoardModel.Seat>();
            for(int i=0;i<5;i++) companySeats.add(new BoardModel.Seat(company+"-seat-"+i,people.get(i)));
            charters.put(company,new BoardModel.Charter(company,companySeats,company+"-seat-0",BoardModel.QUORUM));
            readers.addAll(people);readers.add(company.equals(DigitalIndustryConfig.COMPANY)?DigitalIndustryConfig.MANAGER_PERSON:AgedIndustryConfig.MANAGER_PERSON);
        }
        var decisions = ResourceDecisionTools.config(CompanyOperationsDecisionTools.config(ComposedPharmaScenario.debugDecisions(), readers));
        if (hydro) decisions = HydroDecisionTools.config(decisions);
        if(baseline) decisions=AgedDecisionTools.config(DigitalDecisionTools.config(decisions));
        if(prototype) decisions=PrototypeCompanies.decisions(decisions);
        var openingAccounts = new ArrayList<>(openings());
        if (hydro) openingAccounts.addAll(HydroIndustryConfig.ACCOUNTS);
        var openingRegistry = hydro ? hydroRegistry(registry()) : registry();
        if(baseline) { openingAccounts=new ArrayList<>(BaselineIndustryConfig.openings(openingAccounts));openingRegistry=BaselineIndustryConfig.registry(openingRegistry,openingAccounts); }
        if(connected) { openingAccounts=new ArrayList<>(ConnectedWorldOpening.accounts(openingAccounts));openingRegistry=ConnectedWorldOpening.registry(openingRegistry); }
        boolean fresh = !Files.exists(journal) || Files.size(journal) == 0;
        var runManifest = manifest(expansion, hydro, baseline);
        if (prototype) {
            openingRegistry = PrototypeCompanies.rename(openingRegistry);
            var data = new TreeMap<>(runManifest.dataVersions());
            data.put("profile", PrototypeCompanies.PROFILE);
            data.put("company-identities", "prototype-v1");
            data.put("journal-codec", "gzip-frame-v1");
            data.put("role-decision-guide", "v1");
            runManifest = new RunManifest("web-industry-prototype-v1", runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        if (behavior) {
            var data = new TreeMap<>(runManifest.dataVersions());
            data.put("behavior-policy", ManufacturingBehaviorPolicy.VERSION);
            runManifest = new RunManifest("web-industry-behavior-v1", runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        if (connected) {
            var data=new TreeMap<>(runManifest.dataVersions());data.put("behavior-policy",ConnectedWorldLoop.VERSION);
            runManifest=new RunManifest("web-connected-world-v1",runManifest.seed(),runManifest.rulesVersion(),runManifest.moduleVersions(),data,runManifest.modelConfigVersion());
        }
        if (autonomous) {
            var data = new TreeMap<>(runManifest.dataVersions());
            data.put("composed-governance-intent", "v2");
            data.put("autonomy-driver", eventDriven ? "role-events-v2" : "role-windows-v1");
            runManifest = new RunManifest(eventDriven ? "web-autonomous-world-v2" : "web-autonomous-world-v1", runManifest.seed(), runManifest.rulesVersion(),
                    runManifest.moduleVersions(), data, runManifest.modelConfigVersion());
        }
        WorldRuntime world = ComposedWorldScenario.open(journal, runManifest, openingAccounts, openingRegistry, banking, decisions,
                new ComposedWorldScenario.Extras(charters, List.of(), readers));
        try {
            if (fresh) {
                world.initializeResearch("bootstrap", ComposedPharmaScenario.PHARMA,
                        new ResearchModel.Config(ComposedPharmaScenario.PHARMA_OWNER, ComposedPharmaScenario.PHARMA_CASH, BigDecimal.ZERO),
                        prototype ? PrototypeCompanies.researchProjects() : WorldWebScenario.researchProjects(), 1);
                world.initializeCompanyOperations("bootstrap", ComposedManufacturingThreeScenario.companies(), resourceConfig(expansion), baseline ? BaselineIndustryConfig.hydro() : hydro ? HydroIndustryConfig.config() : null, baseline ? BaselineIndustryConfig.digital():null, baseline ? BaselineIndustryConfig.aged():null);
                Map<String,List<String>> candidates = new TreeMap<>(Map.of(RESOURCE, List.of(MANAGER), ComposedManufacturingThreeScenario.TECH,
                        List.of(ComposedManufacturingThreeScenario.TECH_MANAGER), ComposedManufacturingThreeScenario.STRUCT,
                        List.of(ComposedManufacturingThreeScenario.STRUCT_MANAGER), ComposedManufacturingThreeScenario.VERSA,
                        List.of(ComposedManufacturingThreeScenario.VERSA_MANAGER), ComposedPharmaScenario.PHARMA, List.of(ComposedPharmaScenario.MANAGER_CANDIDATE)));
                if (hydro) candidates.put(HydroIndustryConfig.COMPANY,List.of(HydroIndustryConfig.MANAGER));
                if(baseline) { candidates.put(DigitalIndustryConfig.COMPANY,List.of(DigitalIndustryConfig.MANAGER_PERSON));candidates.put(AgedIndustryConfig.COMPANY,List.of(AgedIndustryConfig.MANAGER_PERSON)); }
                CompanyOpeningDocuments.publish(world,candidates);
            }
            return world;
        } catch (Exception failure) { world.close(); throw failure; }
    }

    private static OpeningRegistry hydroRegistry(OpeningRegistry base) {
        var parties=new ArrayList<>(base.parties()); var owners=new ArrayList<>(base.owners());
        for(var account:HydroIndustryConfig.ACCOUNTS) {
            parties.add(new OpeningRegistry.Party(account.ownerId(),OpeningRegistry.PartyKind.ORGANIZATION,account.ownerId()));
            owners.add(new OpeningRegistry.PropertyOwner(account.ownerId(),OpeningRegistry.OwnerKind.ORGANIZATION,account.ownerId()));
        }
        var people=new ArrayList<>(HydroIndustryConfig.DIRECTORS);people.add(HydroIndustryConfig.MANAGER);
        people.forEach(id->parties.add(new OpeningRegistry.Party(id,OpeningRegistry.PartyKind.PERSON,id)));
        var securities=new ArrayList<>(base.securities());
        securities.add(new OpeningRegistry.Security("HY1",HydroIndustryConfig.COMPANY,1000,money("10.00")));
        var allocations=new ArrayList<>(base.allocations());
        allocations.add(new OpeningRegistry.ShareAllocation("HY-FOUNDER","HY1",1000,new BigDecimal("10.0000"),OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties,owners,securities,allocations);
    }

    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
}
