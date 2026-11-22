package edu.cufe.auction.web;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.*;
import java.math.BigDecimal;
import java.util.*;

/** 本机世界首页的中文只读投影；显示名不修改身份、任职、历史或模型输入。 */
final class WorldOverviewView {
    private static final long DAY=86_400_000L;
    private static final int RECENT_ORDERS=40;
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
        var operationIds = new HashSet<String>();
        snapshot.companyOperations().ifPresent(run -> {
            operationIds.addAll(run.companyIds());
            if (run.resource()!=null) operationIds.add(run.resource().companyPartyId());
            if (run.hydro()!=null) operationIds.add(run.hydro().companyPartyId());
            if (run.digital()!=null) operationIds.add(run.digital().companyPartyId());
            if (run.aged()!=null) operationIds.add(run.aged().companyPartyId());
        });
        var publications = CompanyPublications.of(world,snapshot.information().map(InformationRun::information).orElse(null),
                registry.securities().stream().map(OpeningRegistry.Security::issuerOrganizationId).toList());
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
            if (operationIds.contains(id)) row.put("operationsPage","operations");
            else if (world.researchRun().filter(run->run.companyPartyId().equals(id)).isPresent()) row.put("operationsPage","research");
            else if (world.engineeringStateOf(id).isPresent()) row.put("operationsPage","engineering");
            else if (id.equals("north") && world.bankOf().isPresent()) row.put("operationsPage","bank");
            row.put("publications",publications.getOrDefault(id,List.of()));
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
        if (autonomous && snapshot.exchange().isPresent()) {
            // 自主档同样只有一个已确认快照：成交与委托都由 snapshot.exchange 一次性派生，不重开世界。
            result.put("marketOrders", marketOrders(snapshot, companies));
            if (!result.containsKey("liveDetails")) result.put("liveDetails", marketDetails(snapshot, companies));
        }
        return result;
    }

    /**
     * 从同一快照的成交明细派生逐日行情：撮合日 = floor(time/DAY)+1。
     * 收盘价只取当日末笔成交；无成交日 close 为空、lastPrice 仅延续上一笔成交价。
     * 未知逐日现金写 null，不补零，也不把开局参考价写成收盘价。
     */
    private static Map<String,Object> marketDetails(WorldRuntime.Snapshot snapshot,List<Map<String,Object>> companies) {
        var bySymbol=new HashMap<String,Map<Integer,DayTrades>>();
        for(var trade:snapshot.exchange().orElseThrow().trades())
            bySymbol.computeIfAbsent(trade.symbol(),k->new TreeMap<>())
                    .computeIfAbsent((int)(trade.timeMillis()/DAY)+1,k->new DayTrades()).add(trade);
        int days=(int)(snapshot.timeMillis()/DAY)+1;
        var result=new LinkedHashMap<String,Object>();
        for(var company:companies) {
            String id=(String)company.get("id"),symbol=(String)company.get("symbol");
            var byDay=bySymbol.getOrDefault(symbol,Map.of());
            BigDecimal carried=null;
            var rows=new ArrayList<Map<String,Object>>(days);
            for(int day=1;day<=days;day++) {
                var tradeDay=byDay.get(day);
                if(tradeDay!=null) carried=tradeDay.close;
                var row=new LinkedHashMap<String,Object>();
                row.put("day",day);
                row.put("lastPrice",carried==null?null:carried.toPlainString());
                row.put("close",tradeDay==null?null:tradeDay.close.toPlainString());
                row.put("trades",tradeDay==null?0L:tradeDay.count);
                row.put("volume",tradeDay==null?0L:tradeDay.volume);
                row.put("turnover",(tradeDay==null?BigDecimal.ZERO.setScale(2):tradeDay.turnover).toPlainString());
                row.put("cash",null);
                rows.add(row);
            }
            var entry=new LinkedHashMap<String,Object>();
            entry.put("days",rows);entry.put("openingCash",null);entry.put("cashChange",null);
            entry.put("periods",List.of());
            result.put(id,entry);
        }
        return result;
    }

    /**
     * 从同一快照的订单表按证券派生最近 {@value #RECENT_ORDERS} 条委托，并保留总数。
     * 市价单 limitPrice 为 null，原样保留为空，不伪造价格；只进管理员首页投影。
     */
    private static Map<String,Object> marketOrders(WorldRuntime.Snapshot snapshot,List<Map<String,Object>> companies) {
        var bySymbol=new HashMap<String,OrderRollup>();
        for(var order:snapshot.exchange().orElseThrow().orders()) {
            var row=new LinkedHashMap<String,Object>();
            row.put("orderId",order.orderId());row.put("symbol",order.symbol());
            row.put("accountId",order.accountId());row.put("side",order.side().name());
            row.put("type",order.type().name());
            row.put("price",order.limitPrice()==null?null:order.limitPrice().toPlainString());
            row.put("quantity",order.quantity());row.put("filled",order.filled());
            row.put("status",order.status().name());row.put("acceptedAtMillis",order.acceptedAtMillis());
            var rollup=bySymbol.computeIfAbsent(order.symbol(),k->new OrderRollup());
            rollup.total++;rollup.rows.add(row);
            if(rollup.rows.size()>RECENT_ORDERS) rollup.rows.remove(0);
        }
        var result=new LinkedHashMap<String,Object>();
        for(var company:companies) {
            var rollup=bySymbol.get((String)company.get("symbol"));
            var entry=new LinkedHashMap<String,Object>();
            entry.put("total",rollup==null?0:rollup.total);
            entry.put("rows",rollup==null?List.of():rollup.rows);
            result.put((String)company.get("id"),entry);
        }
        return result;
    }

    private static final class DayTrades {
        private long count,volume,closeMillis=-1L;
        private BigDecimal turnover=BigDecimal.ZERO.setScale(2),close;
        private void add(WorldExchange.Trade trade) {
            count++;volume+=trade.quantity();turnover=turnover.add(trade.amount());
            if(trade.timeMillis()>=closeMillis){closeMillis=trade.timeMillis();close=trade.price();}
        }
    }

    private static final class OrderRollup {
        private int total;
        private final List<Map<String,Object>> rows=new ArrayList<>();
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
