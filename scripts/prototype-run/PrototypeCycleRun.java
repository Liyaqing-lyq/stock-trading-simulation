import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.*;
import edu.cufe.auction.web.*;
import edu.cufe.auction.model.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;

/**
 * 可复算的季度集成实验。规则经理、规则机构、独立有限库存持有人与可选真实模型分列。
 * 只调用唯一Java运行入口；不写余额、不指定成交结果、不注入现实未来。
 */
public final class PrototypeCycleRun {
    static final long DAY = 86_400_000L, HOUR = 3_600_000L;
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
        .addSerializer(Set.class,new JsonSerializer<Set>() {
            @Override public void serialize(Set value,com.fasterxml.jackson.core.JsonGenerator out,SerializerProvider provider) throws java.io.IOException {
                var sorted=new ArrayList<Object>(value);sorted.sort(Comparator.comparing(Object::toString));
                provider.defaultSerializeValue(sorted,out);
            }
        }));
    final Path output;
    final List<Object> activity = new ArrayList<>();
    final Map<String,String> managers = new LinkedHashMap<>();
    final Map<String,String> targets = new TreeMap<>();
    final Map<String,BigDecimal> limits = new TreeMap<>();
    final Map<String,BigDecimal> initialEquity = new TreeMap<>();
    BigDecimal openingCash;
    int seq;
    WorldRuntime w;

    PrototypeCycleRun(Path output) { this.output = output; }

    /** 参数：全新输出目录、1至90实验日、可选五Key文件；不覆盖旧实验。 */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int days = args.length > 1 ? Integer.parseInt(args[1]) : 90;
        if (days < 1 || days > 90) throw new IllegalArgumentException("实验日必须1至90");
        if (Files.exists(dir.resolve("world-industry-prototype-v1.journal")))
            throw new IllegalArgumentException("输出目录已含世界，禁止重做覆盖");
        Files.createDirectories(dir);
        new PrototypeCycleRun(dir).run(days, args.length > 2 ? Path.of(args[2]) : null);
    }

    void run(int days, Path keys) throws Exception {
        Path journal = output.resolve("world-industry-prototype-v1.journal");
        JsonNode end;
        try (var world = ComposedIndustryScenario.openPrototype(journal)) {
            w = world; openingCash = totalCash();
            save("policy.json", Map.of("profile", PrototypeCompanies.PROFILE, "days", days,
                "scenario", "EXP；连续实验日，不拟合现实节假日；09:30至15:00提交股票订单，其余时间经营可推进",
                "governance", "规则初始化；实际会议、阅读、表决、回避、任免和每项预算核验",
                "operations", "规则经理：有限规模采购、生产、合同和收款；不能完成的长周期保留在途",
                "institution", "规则四岗每30日读公开累计报表；亏损不加仓，其余增持现有仓位10%，每笔至多2股且不超过总额度，每天至多1股；限价随公开损益调整；不补造成交",
                "holder", "独立库存策略：持仓高于初始80%才每日卖至多2股，限价按已读公开损益调整参考价后减1%；只读自身库存和公开材料，不读基金目标或买单",
                "live", keys == null ? "未启用" : "最多3次真实研究岗位调用；实际工具结果单列，规则交易不冒充自主模型",
                "openingCash", openingCash.toPlainString()));
            bootstrap();
            if (keys != null) for (int i=1;i<=3;i++) live(keys,"llm-research-"+i);
            establishPortfolio(0);
            save("opening.json", snapshot());
            for (int day=1;day<=days;day++) {
                long base=(day-1)*DAY;
                w.advanceTo(base+7*HOUR);
                if(day==31 || day==61) establishPortfolio((day-1)/30);
                business(day);
                w.advanceTo(base+9*HOUR+HOUR/2);
                market(day);
                w.advanceTo(base+15*HOUR);
                cancelRemaining(day);
                w.advanceTo(day*DAY);
                if(w.snapshot().timeMillis()!=day*DAY)throw new IllegalStateException("时钟被屏障钳制，不能计作完成日");
                verify();
                save(String.format("day-%02d.json",day),snapshot());
                save("activity.json",activity);
                System.out.println("DAY="+day+" POSITION="+w.snapshot().position()+" TRADES="+w.snapshot().exchange().orElseThrow().trades().size()+" CASH="+totalCash());
            }
            end=snapshot(); save("final.json",end);
        }
        try (var restored=ComposedIndustryScenario.openPrototype(journal)) {
            w=restored; var actual=snapshot();
            save("recovered.json",actual);
            if (!end.equals(actual)) throw new IllegalStateException("恢复结果不同");
            verify(); save("recovery.json",Map.of("equal",true,"position",w.snapshot().position(),"timeMillis",w.snapshot().timeMillis()));
        }
    }

    void bootstrap() throws Exception {
        managers.put("mfg-tech",ComposedManufacturingThreeScenario.TECH_MANAGER);
        managers.put("mfg-struct",ComposedManufacturingThreeScenario.STRUCT_MANAGER);
        managers.put("mfg-versa",ComposedManufacturingThreeScenario.VERSA_MANAGER);
        managers.put("resources",ComposedIndustryScenario.MANAGER);
        managers.put("hydro",HydroIndustryConfig.MANAGER);
        managers.put("digital-company",DigitalIndustryConfig.MANAGER_PERSON);
        managers.put("aged-company",AgedIndustryConfig.MANAGER_PERSON);
        managers.put("pharma",ComposedPharmaScenario.MANAGER_CANDIDATE);
        managers.put("eng-a","erin"); managers.put("north","erin");
        for (var entry: managers.entrySet()) {
            var board=w.boardOfCompany(entry.getKey()).orElseThrow();
            var directors=board.board().charter().seats().stream().map(BoardModel.Seat::partyId).toList();
            PrototypeGovernance.grantBudgetAndAppoint(w,entry.getKey(),directors,entry.getValue(),"init-"+entry.getKey(),List.of(0L,1L,2L));
            note("RULE_GOVERNANCE",entry.getKey(),"真实会议预算与任命",w.engineeringRoleInput(entry.getValue()));
        }
    }

    void live(Path keys,String id) throws Exception {
        w.beginDecisionSession("session-"+id);
        w.openDecisions("open-"+id,List.of(new DecisionRun.Request("alice",id,"wake",
            new DecisionModel.Route("research","key-2","deepseek-flash",1,3,150_000,1024,1))));
        var dispatch=w.dispatchLiveDecision("dispatch-"+id,id);
        if(dispatch.call()!=null) {
            var completion=new LiveDecisionProvider(KeySlots.load(keys)).evaluate(dispatch.call(),60_000);
            w.offerLiveDecisionCompletion(completion); w.drainDecisionCallbacks();
        }
        var decision=DecisionModel.decision(w.snapshot().decisions().orElseThrow().state(),id).orElseThrow();
        w.advanceTo(Math.max(w.snapshot().timeMillis(),decision.barrierAtMillis())+600_000);
        note("LIVE_MODEL","alice",id,DecisionModel.decision(w.snapshot().decisions().orElseThrow().state(),id).orElseThrow());
        save("activity.json",activity);
    }

    void establishPortfolio(int period) throws Exception {
        targets.clear();
        for(var security:w.openingRegistry().orElseThrow().securities()) {
            String sym=security.symbol(), company=security.issuerOrganizationId(), id="portfolio-"+period+"-"+sym;
            BigDecimal equity, profit;
            if(company.equals("north")) {var report=BankCompanyReport.of(w.bankOf().orElseThrow(),w.snapshot().accounts(),"north");equity=report.totalEquity();profit=report.currentEarnings();}
            else {var report=ComposedCompanyReport.of(w,company);equity=report.totalEquity();profit=report.profit();}
            initialEquity.putIfAbsent(sym,equity);
            BigDecimal ratio=profit.divide(initialEquity.get(sym),8,RoundingMode.HALF_UP);
            BigDecimal fair=security.initialReferencePrice().multiply(BigDecimal.ONE.add(ratio)).max(new BigDecimal("0.10")).setScale(2,RoundingMode.HALF_UP);
            String doc="finance-"+period+"-"+company;
            String body=PrototypeCompanies.find(company).publicDescription()+"\n规则经理主动公开的实验累计报表：时点="+w.snapshot().timeMillis()+"；权益="+equity+"；累计损益="+profit+"；数据来自本公司的已入账事实。不是现实财报。";
            long published=next();w.schedulePublishDocument(doc,doc,published,doc,managers.get(company),InformationModel.Audience.publicAudience(),"经营情况 "+PrototypeCompanies.find(company).name(),body,"累计权益与损益",1000,List.of());w.advanceTo(published);
            read(WorldWebScenario.INVESTOR,doc,id+"-holder");
            limits.put(sym,fair);
            read("alice",doc,id+"-source");
            var alice=w.openRoleSession("alice"); long t=next();
            alice.submitResearch(id+"-research",id+"-research",t,new WorldRoleSession.ResearchDraft(id,"cufe","fund-a",sym,id+"-note",
                "规则研究："+PrototypeCompanies.find(security.issuerOrganizationId()).name(),
                "已读公司公开经营机制。EXP组合按十公司分散配置，每30日审阅实际累计损益；亏损暂不加仓，否则目标较现持仓增加10%，小额分日执行。限价按公开累计损益相对期初权益调整参考价；不假定经营成功，不依赖现实原型。",List.of(new WorldRoleSession.Citation(doc,1))));
            w.advanceTo(t); read("bob",id+"-note",id+"-manager");
            long initial=held("ACC-FUND",sym), extra=Math.max(1,initial/10);
            if(profit.signum()<0) {note("RULE_PORTFOLIO","fund-a","累计亏损，暂不加仓："+sym,Map.of("profit",profit,"read",doc));continue;}
            targets.put(sym,id);
            t=next(); w.openRoleSession("bob").setTarget(id+"-target",id+"-target",t,new WorldRoleSession.TargetDraft(id,0,"cufe","fund-a","ACC-FUND",sym,initial+extra,fair,(period+1)*30*DAY,id));w.advanceTo(t);
            t=next();w.openRoleSession("carol").reviewTarget(id+"-risk",id+"-risk",t,new WorldRoleSession.ReviewDraft(id,1,true,extra,Math.min(2,extra),fair,(period+1)*30*DAY-1,"规则限额：不加杠杆、最多2股一笔、不得超过目标增量"));w.advanceTo(t);
            t=next();w.openRoleSession("bob").confirmTarget(id+"-confirm",id+"-confirm",t,id,1,1);w.advanceTo(t);
        }
        note("RULE_FOUR_ROLES","fund-a","研究→目标→审核→确认",w.snapshot().trading().orElseThrow());
    }

    void read(String person,String doc,String id) throws Exception {
        long t=next(); w.openRoleSession(person).startReading(id,id,t,doc,1);w.advanceTo(t+120_000);
        if(!w.canCite(person,doc,1)) throw new IllegalStateException("未读完："+doc+" / "+person);
    }

    void market(int day) throws Exception {
        // 两种策略独立生成；个人不观察基金的目标或订单。撮合只能由真实订单相交产生。
        for(var s:w.openingRegistry().orElseThrow().securities()) {
            long opening=w.openingRegistry().orElseThrow().allocations().stream().filter(a->a.accountId().equals("INV")&&a.symbol().equals(s.symbol())).mapToLong(OpeningRegistry.ShareAllocation::quantity).sum();
            long excess=held("INV",s.symbol())-opening*80/100;
            if(excess>0) {
                String id="holder-"+day+"-"+s.symbol();long t=next();
                w.scheduleOrder(id,id,t,WorldWebScenario.INVESTOR,"INV",s.symbol(),Side.SELL,OrderType.LIMIT,limits.get(s.symbol()).multiply(new BigDecimal("0.99")).setScale(2,RoundingMode.HALF_UP),Math.min(2,excess));w.advanceTo(t);
            }
        }
        for(var s:w.openingRegistry().orElseThrow().securities()) {
            String targetId=targets.get(s.symbol());
            if(targetId==null)continue;
            var target=w.snapshot().trading().orElseThrow().trading().targets().stream().filter(x->x.targetId().equals(targetId)).findFirst().orElseThrow();
            if(held("ACC-FUND",s.symbol())>=target.current().absoluteTargetQuantity())continue;
            String id="fund-"+day+"-"+s.symbol();long t=next();
            w.openRoleSession("dave").submitChild(id,id,t,new WorldRoleSession.ChildDraft(targetId,1,1,id,1,limits.get(s.symbol())));w.advanceTo(t);
        }
        note("RULE_MARKET","all","第"+day+"日独立订单",w.snapshot().exchange().orElseThrow().reports());
    }

    void cancelRemaining(int day) throws Exception {
        for(var order:List.copyOf(w.snapshot().exchange().orElseThrow().orders())) if(order.status()==WorldExchange.OrderStatus.ACTIVE) {
            String id="close-"+day+"-"+order.orderId();long t=next();
            String owner=w.snapshot().accounts().get(order.accountId()).ownerId();
            w.scheduleCancelOrder(id,id,t,owner,order.orderId());w.advanceTo(t);
        }
    }

    void business(int day) throws Exception {
        int local=(day-1)%30+1, period=(day-1)/30;
        if(local==1) {
            mfg("mfg-tech","ORDER_MATERIAL","materialId","MAT-A","quantity",10);
            mfg("mfg-struct","ORDER_MATERIAL","materialId","MAT-C","quantity",6);
            mfg("mfg-versa","ORDER_MATERIAL","materialId","MAT-E","quantity",6);
            op("aged-company","ORDER_RAW","orderId","aged-raw-"+period,"units","20");
            op("resources","BEGIN_EXTRACTION","jobId","mine-"+period,"seamId","resource-seam-a","quantityKg","600");
            if(period>0) {
                op("aged-company","RECEIVE_RAW","orderId","aged-raw-"+(period-1));
                op("aged-company","PRODUCE","batchId","aged-batch-"+(period-1),"units","20");
            }
        }
        if(day==1) {
            op("digital-company","BEGIN_PILOT");
            op("hydro","DISPATCH_FLOW","cubicMetresPerHour","600");
            long t=next();w.openRoleSession(managers.get("pharma")).registerResearch("rnd-register","rnd-register",t,"pharma","p1",1000);w.advanceTo(t);
            note("RULE_MANAGER","pharma","研发立项回报",Map.of("result",w.taskCommitResult("rnd-register:finish")));
            if(!w.researchRun().orElseThrow().state().projects().containsKey("p1"))throw new IllegalStateException("研发开局立项失败："+w.taskCommitResult("rnd-register:finish"));
            t=next();w.openRoleSession(managers.get("pharma")).beginResearch("rnd-begin","rnd-begin",t,"pharma","p1",ResearchModel.Stage.RESEARCH,1000);w.advanceTo(t);
            t=next();w.openRoleSession(managers.get("aged-company")).bankAction("aged-deposit","aged-deposit",t,1000,
                new WorldRoleSession.BankActionDraft("aged-deposit",edu.cufe.auction.world.bank.BankActions.Kind.DEPOSIT,"north","aged-company",new BigDecimal("100.00"),0,0,"","","",""));w.advanceTo(t);
            read("erin","engineering-opportunity:A1","erin-opportunity");
            engineer("SIGN",0);
            engineer("START_WORK",10);
        }
        if(local==3) {
            mfg("mfg-tech","START_BATCH","productId","P1","generation",1,"quantity",5);
            mfg("mfg-struct","START_BATCH","productId","P1","generation",1,"quantity",3);
            mfg("mfg-versa","START_BATCH","productId","MAT-P","generation",1,"quantity",6);
        }
        if(local==8) {
            sellManufactured("mfg-tech","P1","auto-oem",5,"80.00");
            sellManufactured("mfg-struct","P1","builder-a",3,"80.00");
            sellManufactured("mfg-versa","MAT-P","utility-x",6,"50.00");
        }
        if(day==7)op("digital-company","BEGIN_VALIDATION");
        if(local==7) {
            op("resources","SELL_COAL","deliveryId","coal-sale-"+period,"quantityKg","200","receivableId","coal-ar-"+period);
            op("resources","TRANSFER_TO_PLANT","transferId","coal-power-"+period,"quantityKg","400");
            op("resources","BEGIN_POWER","jobId","power-"+period,"mwh","2","receivableId","power-ar-"+period);
        }
        if(day>=2) {
            var report=HydroModel.report(w.operationsRun().orElseThrow().hydro().ledger(),w.snapshot().accounts());
            if(report.receivable().signum()>0)op("hydro","COLLECT","amount",report.receivable().min(new BigDecimal("500.00")).toPlainString());
            var after=HydroModel.report(w.operationsRun().orElseThrow().hydro().ledger(),w.snapshot().accounts());
            op("hydro","DISPATCH_FLOW","cubicMetresPerHour",after.receivable().compareTo(new BigDecimal("2000.00"))>0 ? "0" : "600");
        }
        if(local==26) {
            for(String c:List.of("mfg-tech","mfg-struct","mfg-versa"))mfg(c,"COLLECT","receivableId",c+"-ar-"+period);
        }
        if(local==24) {op("resources","COLLECT","receivableId","coal-ar-"+period);op("resources","COLLECT","receivableId","power-ar-"+period);}
        if(day==18)op("digital-company","APPROVE_COMMERCIAL","approvalId",DigitalIndustryConfig.APPROVAL_COMMERCIAL);
        if(day==19 || day==49 || day==79) {
            String id="digital-"+period;
            op("digital-company","SIGN_CONTRACT","contractId",id,"quantityUnits","20","prepaidCents","40000");
            op("digital-company","DELIVER","contractId",id,"units","20","receivableId",id+"-ar");
        }
        if(day==29) {
            note("BOUNDARY_PROBE","digital-company","到期前收款应被拒，下一到期窗口再收",Map.of("receivableId","digital-0-ar"));
            op("digital-company","COLLECT","receivableId","digital-0-ar");
        }
        if(day==36 || day==66)op("digital-company","COLLECT","receivableId","digital-"+(period-1)+"-ar");
        if(day==48) {
            var project=w.researchRun().orElseThrow().state().projects().get("p1");
            if(project!=null && project.stages().get(0).status()==ResearchModel.StageStatus.COMPLETED) {
                long t=next();w.openRoleSession(managers.get("pharma")).beginResearch("rnd-development","rnd-development",t,"pharma","p1",ResearchModel.Stage.DEVELOPMENT,1000);w.advanceTo(t);
            } else note("RULE_MANAGER","pharma","未立项或初始研究未通过，不擅自开启下一阶段",project==null ? Map.of("reason","PROJECT_NOT_REGISTERED") : project);
        }
        if(day==81)engineer("INVOICE",0);
    }

    void sellManufactured(String company,String product,String customer,int quantity,String price) throws Exception {
        int period=(int)(w.snapshot().timeMillis()/(30*DAY));
        mfg(company,"SIGN_CONTRACT","contractId",company+"-contract-"+period,"customerId",customer,"productId",product,"generation",1,"quantity",quantity,"unitPrice",price,"promisedAt",w.snapshot().timeMillis()+4000,"expiresAt",w.snapshot().timeMillis()+DAY);
        mfg(company,"DELIVER","contractId",company+"-contract-"+period,"quantity",quantity,"receivableId",company+"-ar-"+period);
    }

    void engineer(String action,long units) throws Exception {
        String id="engineer-"+action;long t=next();
        w.scheduleEngineeringAction(id,id,t,"eng-a",managers.get("eng-a"),action,"A1","work-1",0,units,BigDecimal.ZERO,"",0,1000);w.advanceTo(t);
        note("RULE_MANAGER","eng-a",action,w.snapshot().engineering().orElseThrow().reports());
    }

    void mfg(String company,String action,Object... fields) throws Exception {
        Map<String,Object> map=new TreeMap<>();for(int i=0;i<fields.length;i+=2)map.put((String)fields[i],fields[i+1]);
        String id="op-"+(++seq);long t=next();
        w.openRoleSession(managers.get(company)).operationsAction(id,id,t,company,CompanyOperationsRun.Action.valueOf(action),JSON.convertValue(map,CompanyOperationsRun.Fields.class),1000);w.advanceTo(t);
        note("RULE_MANAGER",company,action,w.operationsRun().orElseThrow().reports());
    }

    void op(String company,String action,String... fields) throws Exception {
        Map<String,String> map=new TreeMap<>();for(int i=0;i<fields.length;i+=2)map.put(fields[i],fields[i+1]);
        String id="op-"+(++seq);long duration=company.equals("hydro") ? HydroDecisionTools.ACTION_MILLIS : 1000;long t=w.snapshot().timeMillis()+duration+1000;var session=w.openRoleSession(managers.get(company));
        switch(company) {
            case "digital-company" -> session.digitalAction(id,id,t,company,new DigitalOperations.Request(DigitalOperations.Action.valueOf(action),map),1000);
            case "aged-company" -> session.agedAction(id,id,t,company,new AgedOperations.Request(AgedOperations.Action.valueOf(action),map),1000);
            case "hydro" -> session.hydroAction(id,id,t,company,new HydroOperations.Request(HydroOperations.Action.valueOf(action),map),duration);
            case "resources" -> session.resourceAction(id,id,t,company,new ResourceOperations.Request(ResourceOperations.Action.valueOf(action),map),1000);
            default -> throw new IllegalArgumentException(company);
        }
        w.advanceTo(t);note("RULE_MANAGER",company,action,w.operationsRun().orElseThrow().reports());
    }

    long next() { return w.snapshot().timeMillis()+2000; }
    long held(String account,String symbol) {return w.snapshot().securities().orElseThrow().lots().stream().filter(l->l.accountId().equals(account)&&l.symbol().equals(symbol)).mapToLong(SecuritiesLedger.Lot::remaining).sum();}
    BigDecimal totalCash() {return w.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash).reduce(BigDecimal.ZERO,BigDecimal::add);}

    void verify() {
        if(totalCash().compareTo(openingCash)!=0)throw new IllegalStateException("世界现金不守恒");
        if(!BankCompanyReport.of(w.bankOf().orElseThrow(),w.snapshot().accounts(),"north").balanced())throw new IllegalStateException("银行报表不平");
        for(var s:w.openingRegistry().orElseThrow().securities()) {
            long shares=w.snapshot().securities().orElseThrow().lots().stream().filter(l->l.symbol().equals(s.symbol())).mapToLong(SecuritiesLedger.Lot::remaining).sum();
            if(shares!=s.issuedShares())throw new IllegalStateException("股份不守恒："+s.symbol());
        }
        for(var c:PrototypeCompanies.all()) if(!c.companyId().equals("north") && !ComposedCompanyReport.of(w,c.companyId()).balanced())throw new IllegalStateException("报表不平："+c.companyId());
    }

    JsonNode snapshot() throws Exception {
        ObjectNode out=JSON.createObjectNode();var snapshot=w.snapshot();
        for(var component:WorldRuntime.Snapshot.class.getRecordComponents()) {
            Object value=component.getAccessor().invoke(snapshot);
            if(value instanceof Optional<?> optional)value=optional.orElse(null);
            out.set(component.getName(),JSON.valueToTree(value));
        }
        out.set("researchRun",JSON.valueToTree(w.researchRun().orElseThrow()));
        ArrayNode reports=out.putArray("companyReports");
        for(var c:PrototypeCompanies.all()) {ObjectNode row=JSON.valueToTree(c.companyId().equals("north") ? BankCompanyReport.of(w.bankOf().orElseThrow(),w.snapshot().accounts(),"north") : ComposedCompanyReport.of(w,c.companyId()));row.put("name",c.name());row.put("companyPartyId",c.companyId());reports.add(row);}
        out.set("bank",JSON.valueToTree(w.bankOf().orElseThrow()));
        out.set("registry",JSON.valueToTree(w.openingRegistry().orElseThrow()));
        out.put("totalCash",totalCash().toPlainString());
        return out;
    }

    void note(String driver,String actor,String action,Object result) {activity.add(Map.of("driver",driver,"actor",actor,"action",action,"timeMillis",w.snapshot().timeMillis(),"position",w.snapshot().position(),"result",JSON.valueToTree(result)));}
    void save(String name,Object value) throws Exception {Files.writeString(output.resolve(name),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
}
