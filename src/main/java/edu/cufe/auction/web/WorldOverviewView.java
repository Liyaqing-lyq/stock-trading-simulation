package edu.cufe.auction.web;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.*;
import java.math.BigDecimal;
import java.util.*;

/** 本机世界首页的中文只读投影；显示名不修改身份、任职、历史或模型输入。 */
final class WorldOverviewView {
    private static final Map<String,String> NAMES = names();
    private WorldOverviewView() { }

    static String displayName(String id) { return NAMES.getOrDefault(id,id); }

    static Map<String,Object> of(WorldRuntime world) throws Exception {
        var snapshot=world.snapshot();
        if(!PrototypeCompanies.PROFILE.equals(world.profile().manifest().dataVersions().get("profile")))
            return Map.of("available",false);
        var registry=world.openingRegistry().orElseThrow();
        var names=new TreeMap<String,String>();
        registry.parties().forEach(p->names.put(p.partyId(),NAMES.getOrDefault(p.partyId(),p.name())));
        snapshot.accounts().forEach((id,a)->names.put(id,a.ownerId().equals("fund-a")?"机构投资账户":names.getOrDefault(a.ownerId(),a.ownerId())+"的账户"));
        var roles=new TreeMap<String,List<String>>();
        var managers=new TreeMap<String,List<String>>();
        boolean autonomous=world.profile().manifest().dataVersions().containsKey("autonomy-driver");
        for(var a:snapshot.roles().orElseThrow().access().appointments()) if(a.activeAt(snapshot.timeMillis())) {
            String role=switch(a.role().name()) {
                case "RESEARCHER" -> "研究员"; case "PORTFOLIO_MANAGER" -> "投资经理";
                case "RISK_OFFICER" -> "风控负责人"; case "EXECUTION_TRADER" -> "交易员";
                case "JOURNALIST" -> "记者"; case "EDITOR" -> "编辑"; case "COMPANY_MANAGER" -> "总经理";
                default -> "任职成员";
            };
            if (autonomous && a.role()==RoleAccess.Role.COMPANY_MANAGER)
                managers.computeIfAbsent(a.organizationPartyId(),k->new ArrayList<>()).add(a.personPartyId());
            roles.computeIfAbsent(a.personPartyId(),k->new ArrayList<>()).add(names.get(a.organizationPartyId())+" · "+role);
        }
        var companies=new ArrayList<Map<String,Object>>();
        for(var security:registry.securities()) {
            String id=security.issuerOrganizationId();var prototype=PrototypeCompanies.find(id);
            var board=world.boardOfCompany(id).orElse(null);
            if(board!=null)for(var seat:board.board().charter().seats())
                roles.computeIfAbsent(seat.partyId(),k->new ArrayList<>()).add(names.get(id)+" · 董事");
            var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("symbol",security.symbol());
            if (autonomous && board!=null) row.put("governance", Map.of(
                    "meetings",board.board().meetings().size(), "budgetLines",board.board().budgets().size(),
                    "managers",managers.getOrDefault(id,List.of())));
            row.put("name",names.get(id));row.put("business",prototype.business());
            row.put("prototype",prototype.prototype());row.put("boundary",prototype.boundary());
            row.put("referencePrice",security.initialReferencePrice().toPlainString());
            var trades=snapshot.exchange().orElseThrow().trades().stream().filter(t->t.symbol().equals(security.symbol())).toList();
            row.put("lastPrice",trades.isEmpty()?null:trades.get(trades.size()-1).price().toPlainString());
            row.put("lastTradeTime",trades.isEmpty()?null:trades.get(trades.size()-1).timeMillis());
            row.put("trades",trades.size());row.put("volume",trades.stream().mapToLong(t->t.quantity()).sum());
            if(id.equals("north")) {
                var report=BankCompanyReport.of(world.bankOf().orElseThrow(),snapshot.accounts(),id);
                var equity=new TreeMap<>(report.equity());equity.put("本报表累计损益",report.currentEarnings());
                row.put("statement",statement(report.assets(),report.contra(),report.liabilities(),equity,report.income(),report.expense()));
                row.put("cash",report.assets().getOrDefault("RESERVE",BigDecimal.ZERO).toPlainString());
                row.put("profit",report.currentEarnings().toPlainString());row.put("notes",report.unsupported());
            } else {
                var report=ComposedCompanyReport.of(world,id);
                row.put("statement",statement(report.assets(),Map.of(),report.liabilities(),report.equity(),report.income(),report.expense()));
                row.put("cash",report.financial().cash().toPlainString());row.put("profit",report.profit().toPlainString());
                row.put("notes",report.unsupported());
            }
            companies.add(row);
        }
        var people=new ArrayList<Map<String,Object>>();
        for(var party:registry.parties()) if(party.kind()==OpeningRegistry.PartyKind.PERSON) {
            var row=new LinkedHashMap<String,Object>();row.put("id",party.partyId());row.put("name",names.get(party.partyId()));
            row.put("roles",roles.getOrDefault(party.partyId(),List.of()));row.put("system",party.partyId().equals("research-system"));
            people.add(row);
        }
        var result = new LinkedHashMap<String,Object>(Map.of("available",true,"runId",world.profile().manifest().runId(),"position",Long.toString(snapshot.position()),
                "timeMillis",Long.toString(snapshot.timeMillis()),"companies",companies,"people",people,"names",names,
                "behavior", Map.of("enabled", ManufacturingBehaviorLoop.enabled(world), "decisions",
                        ManufacturingBehaviorLoop.enabled(world) ? ManufacturingBehaviorLoop.audit(world) : List.of())));
        if (autonomous) snapshot.decisions().ifPresent(d->result.put("modelAttempts",d.state().runUsage().attempts()));
        ConnectedWorldView.append(result,world,companies);
        return result;
    }

    private static Map<String,Object> statement(Map<String,BigDecimal> assets,Map<String,BigDecimal> contra,
            Map<String,BigDecimal> liabilities,Map<String,BigDecimal> equity,Map<String,BigDecimal> income,Map<String,BigDecimal> expense) {
        return Map.of("assets",money(assets),"contra",money(contra),"liabilities",money(liabilities),"equity",money(equity),
                "income",money(income),"expense",money(expense),"totalAssets",sum(assets).subtract(sum(contra)).toPlainString(),
                "totalLiabilities",sum(liabilities).toPlainString(),"totalEquity",sum(equity).toPlainString(),
                "profit",sum(income).subtract(sum(expense)).toPlainString());
    }
    private static BigDecimal sum(Map<String,BigDecimal> values){return values.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static Map<String,String> money(Map<String,BigDecimal> values){var result=new TreeMap<String,String>();values.forEach((k,v)->result.put(k,v.toPlainString()));return result;}
    private static Map<String,String> names() {
        var result=new TreeMap<String,String>();
        String[] ids={"alice","bob","carol","dave","erin","frank","grace","heidi","ivan","judy","kate","investor","nina","omar","rita","sam","bea","ed","research-system"};
        String[] names={"林知微","周明远","许清衡","陆行舟","沈砚舟","严承松","温知夏","唐亦禾","杜衡","方以安","苏以宁","顾行远","叶初宁","江子言","乔可言","何承安","乔闻笙","程闻青","研发试验系统"};
        for(int i=0;i<ids.length;i++)result.put(ids[i],names[i]);
        String[] prefixes={"aged-company-director-","digital-company-director-","hydro-director-","resource-director-","mfgtech-d","mfgstruct-d","mfgversa-d"};
        String[] boards={"陈景川,秦书瑶,郑明川,方静宜,梁修远","程景安,邱若岚,贺明哲,任嘉宁,罗思远","赵云舒,顾景山,林雅宁,孙启川,吴清和","孟时安,沈玉衡,韩望舒,蔡承泽,苏乐成","严子恒,江知遥,范景行,温书宁,蒋星澜","贺承宇,卢映秋,程砚清,宋亦川,许宁远","邵文景,唐静初,陆若衡,陈望宁,周序言"};
        for(int g=0;g<prefixes.length;g++){var group=boards[g].split(",");for(int i=0;i<5;i++)result.put(prefixes[g]+(i+1),group[i]);}
        String[] managers={"aged-manager","digital-manager","hydro-manager","resource-manager","mfgtech-mgr","mfgstruct-mgr","mfgversa-mgr"};
        String[] managerNames={"陈酌言","许云开","江澜清","秦砺川","顾光远","沈储安","林晶禾"};
        for(int i=0;i<managers.length;i++)result.put(managers[i],managerNames[i]);
        return Map.copyOf(result);
    }
}
