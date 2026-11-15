package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.*;
import edu.cufe.auction.world.composed.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import static edu.cufe.auction.world.ConnectedMarketPolicy.*;

/** 同一世界的持续经营、信息、人物和市场唤醒；所有动作走原权威执行器与日志。 */
public final class ConnectedWorldLoop {
    /** 独立新档的冻结规则；旧制造样本和旧季度都不迁移。 */
    public static final String VERSION="connected-world-v1";
    /** 六小时是人物检查状态的间隔，不是成交或生产配额。 */
    public static final long INTERVAL=6*3_600_000L;
    /** 私有决策空间，不能由普通资料接口伪造。 */
    public static final String PREFIX="connected-note:";
    private static final ObjectMapper JSON=new ObjectMapper();
    private ConnectedWorldLoop() { }
    /** 既定公司任职候选，真正权限必须由有效任命产生。 */
    public static Map<String,String> managers(){return new TreeMap<>(Map.of("mfg-tech","mfgtech-mgr","mfg-struct","mfgstruct-mgr","mfg-versa","mfgversa-mgr","resources","resource-manager","hydro","hydro-manager","digital-company","digital-manager","aged-company","aged-manager","pharma","kate","eng-a","erin","north","erin"));}
    /** 显式版本门禁。 */
    public static boolean enabled(WorldRuntime w){return VERSION.equals(w.profile().manifest().dataVersions().get("behavior-policy"));}
    /** 私有冻结观察与业务决定；不把God真值交给普通角色。 */
    public record Decision(String version,String id,String actor,String person,String company,long observedAt,JsonNode observation,Choice choice) { }
    /** 冻结观察摘要；完整输入从该命令前缀真实复演重建核对，不在每帧重复复制整份资料目录。 */
    public static JsonNode inputReceipt(JsonNode input) throws IOException {
        try {
            byte[] bytes=JSON.writeValueAsBytes(canonical(input));
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            return JSON.createObjectNode().put("inputSha256",hash).put("source","本人权限投影；原命令前缀复演核对输入及选择");
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static JsonNode canonical(JsonNode n){
        if(n.isObject()){var o=JSON.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(String key:keys)o.set(key,canonical(n.get(key)));return o;}
        if(n.isArray()){var a=JSON.createArrayNode();n.forEach(v->a.add(canonical(v)));return a;}return n;
    }
    /** 本人输入：公开证券资料、授权账户、真实已读正文及本人经营资料。 */
    public static JsonNode observation(WorldRuntime w,String actor) throws IOException {
        String person=managers().getOrDefault(actor,actor);String company=managers().containsKey(actor)?actor:"";
        var root=JSON.createObjectNode();var role=w.openRoleSession(person).view();ObjectNode roleJson=JSON.valueToTree(role);
        roleJson.retain("profile","appointments","research","targets");
        ObjectNode profile=(ObjectNode)roleJson.path("profile");profile.retain("accounts","holdings","market","catalog","observations","readings");
        for(String field:List.of("catalog","observations")){
            var filtered=JSON.createArrayNode();for(var item:profile.path(field)){
                String doc=field.equals("catalog")?item.path("docId").asText():item.path("ref").path("docId").asText();
                if(doc.startsWith("connected-news:")||doc.startsWith("research:connected-news:")||doc.startsWith(PlatformPostModel.DOC_PREFIX)||doc.startsWith("engineering-opportunity:"))filtered.add(item);
            }profile.set(field,filtered);
        }
        root.set("role",roleJson);
        var listings=root.putArray("listings");
        for(var s:w.openingRegistry().orElseThrow().securities())listings.addObject().put("symbol",s.symbol()).put("company",s.issuerOrganizationId()).put("reference",s.initialReferencePrice().toPlainString());
        if(!company.isEmpty()) {
            ObjectNode operating=((ObjectNode)w.engineeringRoleInput(person)).deepCopy();
            operating.retain("managerships","companyOperations","hydroOperations","resourceOperations","digitalOperations","agedOperations","researchOperations");
            // 经理只需要本次业务状态；董事会历史和工具说明不是每次选择的输入。
            var managerViews=JSON.createArrayNode();for(var m:operating.path("managerships"))if(m.path("companyPartyId").asText().equals(company)){
                ObjectNode own=m.deepCopy();own.remove("periodic");managerViews.add(own);}
            operating.set("managerships",managerViews);
            root.set("operating",operating);
            boolean appointed=role.appointments().stream().anyMatch(a->a.organizationPartyId().equals(company)&&a.role()==RoleAccess.Role.COMPANY_MANAGER);
            if(appointed) {
                var s=w.openingRegistry().orElseThrow().securities().stream().filter(x->x.issuerOrganizationId().equals(company)).findFirst().orElseThrow();
                var fact=root.putObject("financial");fact.put("company",company).put("name",PrototypeCompanies.find(company).name()).put("symbol",s.symbol()).put("reference",s.initialReferencePrice().toPlainString()).put("shares",s.issuedShares()).put("asOf",w.snapshot().timeMillis());
                if(company.equals("north")){var r=BankCompanyReport.of(w.bankOf().orElseThrow(),w.snapshot().accounts(),company);fact.put("cash",r.assets().getOrDefault("RESERVE",BigDecimal.ZERO).toPlainString()).put("equity",r.totalEquity().toPlainString()).put("profit",r.currentEarnings().toPlainString());}
                else{var r=ComposedCompanyReport.of(w,company);fact.put("cash",r.financial().cash().toPlainString()).put("equity",r.totalEquity().toPlainString()).put("profit",r.profit().toPlainString());}
                fact.put("basis","经理自愿发布的未审计经营快报；与实际账一致但不是审计财报。固定费用按期结转；金额和估值参数仍为实验规则。");
                if(company.equals("pharma")) {
                    var projects=root.putArray("ownProjects");for(var p:w.researchRun().orElseThrow().state().projects().values()){
                        var item=projects.addObject().put("projectId",p.projectId());item.set("stages",JSON.valueToTree(p.stages()));
                    }
                }
            }
        }
        var active=root.putArray("ownActiveSymbols");var orders=root.putArray("ownOrders");
        Set<String> owned=new HashSet<>();role.profile().accounts().stream().filter(a->a.kind()==RoleView.AccountKind.PERSONAL).forEach(a->owned.add(a.accountId()));
        for(var o:w.snapshot().exchange().orElseThrow().orders())if(owned.contains(o.accountId())&&o.status()==WorldExchange.OrderStatus.ACTIVE){active.add(o.symbol());orders.addObject().put("commandId",o.commandId()).put("symbol",o.symbol()).put("timeMillis",o.acceptedAtMillis());}
        var shared=root.putArray("sharedSources");
        for(var d:w.snapshot().information().orElseThrow().information().documents())if(d.docId().startsWith(PREFIX)&&d.publisherPartyId().equals(person)){
            JsonNode choice=parse(d.at(1).body()).path("choice");if(choice.path("action").asText().equals("POST"))shared.add(choice.path("fields").path("sourceDoc").asText());
        }
        // 报告的Map遍历顺序不属于规则输入；恢复前后固定排序。
        normalizeReports(root);
        return JSON.readTree(JSON.writeValueAsString(root));
    }
    private static void normalizeReports(JsonNode n){if(n.isObject()){n.fields().forEachRemaining(e->{if(e.getKey().equals("reports")&&e.getValue().isArray()){var list=rows(e.getValue());list.sort(Comparator.comparing(x->x.path("commandId").asText()));((ObjectNode)n).set(e.getKey(),JSON.valueToTree(list));}else normalizeReports(e.getValue());});}else if(n.isArray())n.forEach(ConnectedWorldLoop::normalizeReports);}
    /** 基于已冻结本人输入做选择，复演时使用相同规则。 */
    public static Choice choose(JsonNode input,String actor,long now,String id){
        if(!managers().containsKey(actor)) {
            for(var o:input.path("ownOrders"))if(now-o.path("timeMillis").asLong()>=86_400_000L)return act("CANCEL","挂单一天仍未成交，释放冻结资金或股份后重新判断","commandId",o.path("commandId").asText(),"symbol",o.path("symbol").asText());
            return ConnectedMarketPolicy.choose(input,actor,now,id);
        }
        if(!input.has("financial"))return waitFor("董事会尚未授权本人管理该公司");
        // 每日最后一次注意力窗口用于公开快报；经营与披露不共享隐形并行工时。
        if(now/INTERVAL%4==3)return act("PUBLISH","经理公开本公司当前账面事实；其他人物须实际阅读才能使用","docId","connected-news:"+actor+":"+now/86_400_000L,"body",input.path("financial").toString(),"title",input.path("financial").path("name").asText()+" · 经营快报");
        var op=input.path("operating");
        if(actor.startsWith("mfg-"))for(var c:op.path("companyOperations"))if(c.path("companyId").asText().equals(actor)) {var choice=ManufacturingBehaviorPolicy.choose(c,now,id);return new Choice("MFG:"+choice.action(),choice.fields(),choice.reason());}
        if(Set.of("resources","hydro","digital-company","aged-company").contains(actor)) {var c=IndustryBehaviorPolicy.choose(op,actor,now,id);return new Choice(c.action().equals("WAIT")?"WAIT":"INDUSTRY:"+c.action(),new TreeMap<>(c.fields()),c.reason());}
        if(actor.equals("eng-a"))return engineering(input,now,id);
        if(actor.equals("pharma")) {
            var spec=op.path("researchOperations").path("projects");if(spec.isEmpty())return waitFor("没有已配置的研发方案");
            if(input.path("ownProjects").isEmpty())return act("RESEARCH_REGISTER","按真实董事预算立项；成功与销售收入仍未知","projectId",spec.get(0).path("projectId").asText());
            for(var p:input.path("ownProjects")){if(p.path("stages").get(0).path("status").asText().equals("PLANNED"))return act("RESEARCH_BEGIN","立项完成，按阶段预算开展真实耗时的研究","projectId",p.path("projectId").asText(),"stage","RESEARCH");}
            return waitFor("研发试验尚在执行或等待证据和后续审批；不会提前创造营业收入");
        }
        return waitFor("银行按已存在的存贷款与结算义务计息；未收到真实融资需求，不凭空放贷");
    }
    private static Choice engineering(JsonNode input,long now,String id){
        JsonNode p=input.path("role").path("profile"),op=input.path("operating");
        for(var m:op.path("managerships"))if(m.path("companyPartyId").asText().equals("eng-a")){
            if(m.path("contracts").isEmpty()){
                if(!m.path("opportunities").isEmpty())return act("ENGINEER:SIGN","读过客户机会且报价高于单位成本后签约","contract",m.path("opportunities").get(0).path("contractId").asText());
                for(var c:p.path("catalog"))if(c.path("docId").asText().startsWith("engineering-opportunity:"))return read(c.path("docId").asText(),"工程经理先阅读客户公开合同机会");
            }
            for(var c:m.path("contracts")){
                String contract=c.path("contractId").asText();
                if(c.path("completedUnits").asLong()==0&&m.path("workOrders").isEmpty())return act("ENGINEER:START_WORK","合同已签且尚未施工，按第一段工程量投入真实成本与工时","contract",contract,"units",10L);
                for(var stage:m.path("customer").path("stages"))if(stage.path("contractId").asText().equals(contract)&&!stage.path("invoiced").asBoolean()&&c.path("acceptedUnits").asLong()>0)
                    return act("ENGINEER:INVOICE","已验收工程段才能开票确认应收","contract",contract,"tranche",stage.path("trancheIndex").asInt());
            }
        }
        return waitFor("等待真实施工、客户验收与付款窗口，不用时钟直接补收入");
    }
    /** 推进时在每个注意力时点唤醒全部角色；中断由原日志中的笔记和命令标识恢复。 */
    public static void advanceTo(WorldRuntime w,long target) throws IOException {
        if(!enabled(w)||target<w.snapshot().timeMillis())throw new IllegalArgumentException("规则档或目标时点不匹配");
        if(w.pendingBarrier().isPresent())throw new IllegalStateException("先完成已有模型决定");
        var actors=new ArrayList<>(managers().keySet());actors.addAll(List.of("alice","bob","carol","dave","investor","nina","omar","bea"));
        while(w.snapshot().timeMillis()<target){
            var pendingNote=w.snapshot().pending().stream().filter(t->t.taskId().startsWith(PREFIX)&&t.taskId().endsWith(":note")).min(Comparator.comparingLong(PendingTask::dueTimeMillis));
            if(pendingNote.isPresent()){w.advanceTo(Math.min(target,pendingNote.get().dueTimeMillis()));continue;}
            // 恢复窗口：笔记已提交但动作未排程时，先兑现该确切决定，不能换成新输入。
            for(var doc:w.snapshot().information().orElseThrow().information().documents())if(doc.docId().startsWith(PREFIX)){
                var saved=JSON.readValue(doc.at(1).body(),Decision.class);if(!saved.choice().action().endsWith("WAIT"))dispatch(w,saved);
            }
            long now=w.snapshot().timeMillis(),slot=now/INTERVAL,end=Math.min(target,(slot+1)*INTERVAL);
            for(String actor:actors){
                if((actor.equals("nina")&&slot%2!=0)||(actor.equals("omar")&&slot%4!=0))continue;
                String id=PREFIX+actor+":"+slot,task=id+":note";
                Decision d=find(w,id);
                if(d==null&&!w.snapshot().usedTaskIds().contains(task)){
                    JsonNode input=observation(w,actor);Choice choice=choose(input,actor,now,id);
                    if((choice.action().equals("WAIT")||choice.action().equals("MFG:WAIT"))&&slot%4!=0)continue;
                    d=new Decision(VERSION,id,actor,managers().getOrDefault(actor,actor),managers().containsKey(actor)?actor:"",now,inputReceipt(input),choice);
                    w.recordConnectedDecision(d);
                }
                if(d!=null&&!d.choice().action().endsWith("WAIT"))dispatch(w,d);
            }
            w.advanceTo(end);
            if(w.snapshot().timeMillis()!=end)throw new IllegalStateException("推进停在未完成的决定屏障");
        }
    }
    private static Decision find(WorldRuntime w,String id)throws IOException{for(var d:w.snapshot().information().orElseThrow().information().documents())if(d.docId().equals(id))return JSON.readValue(d.at(1).body(),Decision.class);return null;}
    private static void dispatch(WorldRuntime w,Decision d)throws IOException{
        String id=d.id()+":act",roleId=WorldRuntime.roleTaskId(d.person(),id);
        if(w.snapshot().usedTaskIds().contains(id)||w.snapshot().usedTaskIds().contains(id+":begin")
                ||w.snapshot().usedTaskIds().contains(roleId)||w.snapshot().usedTaskIds().contains(roleId+":begin"))return;
        String action=d.choice().action();JsonNode f=JSON.valueToTree(d.choice().fields());var s=w.openRoleSession(d.person());long now=w.snapshot().timeMillis(),due=now+1_001;
        if(action.startsWith("MFG:")){var a=CompanyOperationsRun.Action.valueOf(action.substring(4));s.operationsAction(id,id,due,d.company(),a,CompanyOperationsDecisionTools.fields(f,a),1000);return;}
        if(action.startsWith("INDUSTRY:")){Map<String,String> fields=new TreeMap<>();f.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue().asText()));String a=action.substring(9);switch(d.company()){
            case "resources"->s.resourceAction(id,id,due,d.company(),new ResourceOperations.Request(ResourceOperations.Action.valueOf(a),fields),1000);
            case "hydro"->s.hydroAction(id,id,now+HydroDecisionTools.ACTION_MILLIS+1,d.company(),new HydroOperations.Request(HydroOperations.Action.valueOf(a),fields),HydroDecisionTools.ACTION_MILLIS);
            case "digital-company"->s.digitalAction(id,id,due,d.company(),new DigitalOperations.Request(DigitalOperations.Action.valueOf(a),fields),1000);
            case "aged-company"->s.agedAction(id,id,due,d.company(),new AgedOperations.Request(AgedOperations.Action.valueOf(a),fields),1000);
            default->throw new IllegalArgumentException("未知经营公司");}return;}
        if(action.startsWith("ENGINEER:")){w.scheduleEngineeringAction(id,id,due,d.company(),d.person(),action.substring(9),f.path("contract").asText(),id+"-work",f.path("tranche").asInt(),f.path("units").asLong(),BigDecimal.ZERO,"",now/PeriodicModel.PERIOD_MILLIS,1000);return;}
        switch(action){
            case "PUBLISH"->w.schedulePublishDocument(id,id,due,f.path("docId").asText(),d.person(),InformationModel.Audience.publicAudience(),f.path("title").asText(),f.path("body").asText(),"经理未审计经营快报",60_000,List.of());
            case "READ"->s.startReading(id,id,now+1,f.path("docId").asText(),1);
            case "RESEARCH"->s.submitResearch(id,id,due,new WorldRoleSession.ResearchDraft(f.path("researchId").asText(),"cufe","fund-a",f.path("symbol").asText(),f.path("docId").asText(),f.path("title").asText(),f.path("body").asText(),List.of(new WorldRoleSession.Citation(f.path("sourceDoc").asText(),1))));
            case "TARGET"->s.setTarget(id,id,due,new WorldRoleSession.TargetDraft(f.path("targetId").asText(),0,"cufe","fund-a","ACC-FUND",f.path("symbol").asText(),f.path("quantity").asLong(),dec(f,"price"),f.path("deadline").asLong(),f.path("researchId").asText()));
            case "REVIEW"->s.reviewTarget(id,id,due,new WorldRoleSession.ReviewDraft(f.path("targetId").asText(),f.path("version").asInt(),f.path("approved").asBoolean(),f.path("quantity").asLong(),f.path("child").asLong(),dec(f,"price"),f.path("until").asLong(),d.choice().reason()));
            case "CONFIRM"->s.confirmTarget(id,id,due,f.path("targetId").asText(),f.path("version").asInt(),f.path("revision").asInt());
            case "CHILD"->s.submitChild(id,id,due,new WorldRoleSession.ChildDraft(f.path("targetId").asText(),f.path("version").asInt(),f.path("revision").asInt(),id,f.path("quantity").asLong(),dec(f,"price")));
            case "ORDER"->w.scheduleOrder(id,id,due,d.person(),f.path("account").asText(),f.path("symbol").asText(),Side.valueOf(f.path("side").asText()),OrderType.LIMIT,dec(f,"price"),f.path("quantity").asLong());
            case "CANCEL"->{for(var o:w.snapshot().exchange().orElseThrow().orders())if(o.commandId().equals(f.path("commandId").asText()))w.scheduleCancelOrder(id,id,due,d.person(),o.orderId());}
            case "POST"->s.postToPlatform(id,id,now+edu.cufe.auction.web.WorldWebScenario.GROUPS_PLATFORM_POST_MILLIS,new WorldRoleSession.PlatformPostDraft(f.path("title").asText(),f.path("body").asText(),List.of(new WorldRoleSession.Citation(f.path("sourceDoc").asText(),1))));
            case "RESEARCH_REGISTER"->s.registerResearch(id,id,due,d.company(),f.path("projectId").asText(),1000);
            case "RESEARCH_BEGIN"->s.beginResearch(id,id,due,d.company(),f.path("projectId").asText(),ResearchModel.Stage.valueOf(f.path("stage").asText()),1000);
            default->throw new IllegalArgumentException("未知互通动作："+action);
        }
    }
    /** 上帝审计只读：真实笔记加原执行回报，不把已排程写成成交。 */
    public static List<Map<String,Object>> audit(WorldRuntime w)throws IOException{
        var receipts=new HashMap<String,JsonNode>();
        for(Object reports:List.of(w.operationsRun().orElseThrow().reports(),w.snapshot().trading().orElseThrow().reports(),
                w.snapshot().information().orElseThrow().reports(),w.snapshot().engineering().orElseThrow().reports(),w.groupsRun().reports())){
            JsonNode tree=JSON.valueToTree(reports);for(var r:tree){if(r.has("taskId"))receipts.put(r.path("taskId").asText(),r);if(r.has("commandId"))receipts.put(r.path("commandId").asText(),r);if(r.has("clientCommandId"))receipts.put(r.path("clientCommandId").asText(),r);}
        }
        for(var r:w.snapshot().exchange().orElseThrow().reports().values())receipts.put(r.commandId(),JSON.valueToTree(r));
        var rows=new ArrayList<Map<String,Object>>();for(var doc:w.snapshot().information().orElseThrow().information().documents())if(doc.docId().startsWith(PREFIX)){
            Decision d=JSON.readValue(doc.at(1).body(),Decision.class);var f=d.choice().fields();String id=d.id()+":act";
            String status="等待执行回报";JsonNode receipt=receipts.get(id);String roleId=WorldRuntime.roleTaskId(d.person(),id);
            for(String key:List.of(id+":finish",roleId,roleId+":finish"))if(receipts.containsKey(key))receipt=receipts.get(key);
            if(receipt!=null)status=receipt.path("accepted").asBoolean()?"已执行":"未执行："+receipt.path("reason").asText();
            if(d.choice().action().endsWith("WAIT"))status="等待";
            if(d.choice().action().equals("READ")){
                boolean read=w.snapshot().information().orElseThrow().information().observations().stream().anyMatch(o->o.personPartyId().equals(d.person())&&o.docId().equals(f.get("docId"))&&o.completedAt()>d.observedAt());
                if(read)status="已读完";
            }
            if(d.choice().action().startsWith("RESEARCH_")){
                var outcomes=w.researchRun().orElseThrow().state().commandOutcomes();String outcome=outcomes.get(id);if(outcome==null)outcome=outcomes.get(id+":finish");if(outcome!=null)status=outcome;
            }
            rows.add(Map.of("timeMillis",d.observedAt(),"person",d.person(),"company",d.company(),"action",d.choice().action(),"reason",d.choice().reason(),"details",f,"sourceDoc",f.getOrDefault("sourceDoc",f.getOrDefault("docId","")),"status",status));
        }rows.sort(Comparator.comparingLong(r->((Number)r.get("timeMillis")).longValue()));return List.copyOf(rows);
    }
}
