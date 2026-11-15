package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** 互通世界的有界规则人物：只接受本人可见资料、公开行情与本人资金，不读取他人决定。 */
public final class ConnectedMarketPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ConnectedMarketPolicy() { }
    /** 业务选择及可核对理由；字段顺序固定。 */
    public record Choice(String action, Map<String,Object> fields, String reason) {
        /** 冻结动作字段。 */
        public Choice { fields = Collections.unmodifiableMap(new TreeMap<>(fields)); }
    }
    /** 基于本人已读快报形成保守的经营敏感估值；公开开局价格是先验，并非共同真值。 */
    public static BigDecimal estimate(JsonNode report) {
        BigDecimal profit = dec(report,"profit"), equity = dec(report,"equity");
        BigDecimal initial = equity.subtract(profit).max(BigDecimal.ONE);
        BigDecimal adjustment = profit.divide(initial,8,RoundingMode.HALF_UP).multiply(new BigDecimal("1.5"))
                .max(new BigDecimal("-0.35")).min(new BigDecimal("0.35"));
        return dec(report,"reference").multiply(BigDecimal.ONE.add(adjustment)).setScale(2,RoundingMode.HALF_UP);
    }
    /** 从固定观察选择下一步；读、研、目标、风控、执行分别由真实角色完成。 */
    public static Choice choose(JsonNode input, String person, long now, String id) {
        JsonNode v=input.path("role"), p=v.path("profile");
        if(!p.path("readings").isEmpty())return waitFor("正在阅读，全文尚未形成可用证据");
        if(person.equals("carol"))for(JsonNode t:v.path("targets")) {
            if(t.path("status").asText().equals("PENDING_REVIEW")&&t.path("review").isNull()) {
                JsonNode c=t.path("current"); BigDecimal price=dec(c,"priceLimit");long quantity=c.path("changeQuantity").asLong();
                JsonNode account=account(p,t.path("accountId").asText());
                long affordable=dec(account,"availableCash").multiply(new BigDecimal("0.8")).divide(price,0,RoundingMode.DOWN).longValue();
                long limit=c.path("side").asText().equals("BUY")?Math.min(quantity,affordable):Math.min(quantity,holding(p,t.path("symbol").asText()).path("available").asLong());
                return act("REVIEW","风控独立核对可用资金和可卖持仓，保留20%现金，不加杠杆",
                        "targetId",t.path("targetId").asText(),"version",c.path("version").asInt(),"approved",limit>0,
                        "quantity",Math.max(0,limit),"child",Math.max(0,Math.min(limit,Math.max(1,quantity/3))),"price",price.toPlainString(),"until",c.path("deadlineMillis").asLong()-1);
            }
        }
        if(person.equals("dave"))for(JsonNode t:v.path("targets")) {
            JsonNode review=t.path("review"), c=t.path("current");
            if(t.path("status").asText().equals("ACTIVE")&&t.path("remaining").asLong()>0&&t.path("leaves").asLong()==0) {
                return act("CHILD","交易员只执行已确认且风控有效的剩余目标；能否成交取决于真实对手报价",
                        "targetId",t.path("targetId").asText(),"version",c.path("version").asInt(),"revision",review.path("revision").asInt(),
                        "quantity",Math.min(t.path("remaining").asLong(),review.path("maxChild").asLong()),"price",c.path("priceLimit").asText(),"symbol",t.path("symbol").asText());
            }
        }
        if(person.equals("bob")) {
            for(JsonNode t:v.path("targets"))if(t.path("status").asText().equals("PENDING_REVIEW")&&t.path("review").path("approved").asBoolean())
                return act("CONFIRM","经理确认风控已通过的当前目标版本","targetId",t.path("targetId").asText(),"version",t.path("current").path("version").asInt(),"revision",t.path("review").path("revision").asInt());
            for(JsonNode r:v.path("research")) {
                String symbol=r.path("symbol").asText(), research=r.path("researchId").asText();
                if(rows(v.path("targets")).stream().anyMatch(t->t.path("targetId").asText().equals("target:"+research)))continue;
                if(rows(v.path("targets")).stream().anyMatch(t->t.path("symbol").asText().equals(symbol)&&Set.of("ACTIVE","PENDING_REVIEW","UPDATING").contains(t.path("status").asText())))continue;
                if(!r.path("read").asBoolean())return read(r.path("docId").asText(),"投资经理先阅读本机构研究，不能跳过研究直接拿公司私账");
                JsonNode report=parse(r.path("body").asText());if(!report.has("fair"))continue;
                BigDecimal fair=dec(report,"fair"), nav=nav(input,"ACC-FUND"), price=fair.multiply(new BigDecimal("0.99")).setScale(2,RoundingMode.HALF_UP);
                long held=holding(p,symbol).path("quantity").asLong();
                // 盈利改善使目标权重提高；亏损降为2%，其余为7%。都是本机构事前规则。
                BigDecimal weight=dec(report,"profit").signum()<0?new BigDecimal("0.02"):dec(report,"profit").signum()>0?new BigDecimal("0.09"):new BigDecimal("0.07");
                long target=nav.multiply(weight).divide(fair,0,RoundingMode.DOWN).longValue();
                if(target==held)continue;
                if(target<held)price=fair.multiply(new BigDecimal("1.01")).setScale(2,RoundingMode.HALF_UP);
                return act("TARGET","已读研究改变组合目标；数量由组合净值和风险权重决定，买卖方向由真实持仓差决定",
                        "targetId","target:"+research,"researchId",research,"symbol",symbol,"quantity",target,"price",price.toPlainString(),"deadline",now+3*86_400_000L,"sourceDoc",r.path("docId").asText());
            }
        }
        if(person.equals("alice")) {
            for(JsonNode e:rows(p.path("observations"))) {
                String doc=e.path("ref").path("docId").asText(); if(!doc.startsWith("connected-news:"))continue;
                String research="research:"+doc;
                if(rows(v.path("research")).stream().anyMatch(r->research.equals(r.path("researchId").asText())))continue;
                JsonNode fact=parse(e.path("body").asText());if(!fact.has("equity"))continue;
                var body=JSON.createObjectNode();body.put("sourceDoc",doc);body.put("symbol",fact.path("symbol").asText());
                body.put("fair",estimate(fact).toPlainString());body.put("profit",fact.path("profit").asText());
                body.put("thesis","公开经理快报为未审计陈述。以开局参考价为先验，有限反映已入账损益；不是可兑现的确定收益。固定费用期末结转，尚须后续回款验证。");
                return act("RESEARCH","研究员完成原文阅读后才形成估值，保留原始消息引用",
                        "researchId",research,"docId",research,"sourceDoc",doc,"symbol",fact.path("symbol").asText(),"body",body.toString(),"title","经营研究 · "+fact.path("name").asText());
            }
        }
        if(Set.of("investor","nina","omar").contains(person)) {
            String accountId=person.equals("investor")?"INV":person.equals("nina")?"HOUSE-NINA":"HOUSE-OMAR";
            BigDecimal available=dec(account(p,accountId),"availableCash"), nav=nav(input,accountId);
            for(JsonNode listing:input.path("listings")) {
                String symbol=listing.path("symbol").asText();JsonNode h=holding(p,symbol), market=market(p,symbol);
                if(input.path("ownActiveSymbols").toString().contains("\""+symbol+"\""))continue;
                JsonNode fact=latestFact(p,symbol);BigDecimal ref=dec(listing,"reference"), mark=market.path("lastPrice").isNull()?ref:dec(market,"lastPrice");
                long held=h.path("quantity").asLong(), sellable=h.path("available").asLong();
                BigDecimal cap=person.equals("omar")?new BigDecimal("0.10"):new BigDecimal("0.20");
                long desired=nav.multiply(cap).divide(mark,0,RoundingMode.DOWN).longValue();
                boolean cashNeed=person.equals("omar")&&available.compareTo(nav.multiply(new BigDecimal("0.55")))<0;
                boolean loss=fact.has("profit")&&dec(fact,"profit").signum()<0;
                boolean expensive=market.hasNonNull("bestBid")&&dec(market,"bestBid").compareTo(ref.multiply(new BigDecimal("1.08")))>0;
                long amount=held>desired?held-desired:cashNeed?Math.max(0,nav.multiply(new BigDecimal("0.55")).subtract(available).divide(mark,0,RoundingMode.UP).longValue()):loss?held/3:expensive?held/4:0;
                if(amount>0&&sellable>0) {
                    BigDecimal floor=ref.multiply(person.equals("omar")?new BigDecimal("0.94"):new BigDecimal("0.98"));
                    BigDecimal price=market.hasNonNull("bestBid")&&dec(market,"bestBid").compareTo(floor)>=0?dec(market,"bestBid"):floor;
                    return act("ORDER",held>desired?"单一持仓过于集中，按本人资产上限减仓":cashNeed?"本人现金缓冲不足，卖出一部分真实库存恢复流动性":loss?"已读亏损消息，降低风险敞口":"公开买价高于本人保留估值，兑现部分收益",
                            "account",accountId,"symbol",symbol,"side","SELL","quantity",Math.min(Math.min(amount,sellable),Math.max(1,held/4)),"price",price.setScale(2,RoundingMode.HALF_UP).toPlainString(),"sourceDoc",fact.path("sourceDoc").asText());
                }
                if(fact.has("profit")&&available.compareTo(nav.multiply(new BigDecimal("0.35")))>0&&held<desired) {
                    boolean growth=person.equals("investor")&&dec(fact,"profit").signum()>0;
                    BigDecimal limit=ref.multiply(growth?new BigDecimal("1.03"):new BigDecimal("0.95")).setScale(2,RoundingMode.HALF_UP);
                    if(!growth&&(!market.hasNonNull("bestAsk")||dec(market,"bestAsk").compareTo(limit)>0))continue;
                    long q=available.subtract(nav.multiply(new BigDecimal("0.35"))).divide(limit,0,RoundingMode.DOWN).longValue();
                    if(q>0)return act("ORDER",growth?"已读经营改善消息，保留现金后有限增持":"可见卖价低于本人保守估值，用闲置现金买入",
                            "account",accountId,"symbol",symbol,"side","BUY","quantity",Math.min(q,Math.max(1,(desired-held)/3)),"price",limit.toPlainString(),"sourceDoc",fact.path("sourceDoc").asText());
                }
            }
        }
        if(person.equals("bea"))for(JsonNode e:p.path("observations")) {
            String doc=e.path("ref").path("docId").asText(); if(!doc.startsWith("connected-news:"))continue;
            if(input.path("sharedSources").toString().contains("\""+doc+"\""))continue;
            var fact=parse(e.path("body").asText());
            var article=(com.fasterxml.jackson.databind.node.ObjectNode)fact.deepCopy();article.put("originDoc",doc);article.put("basis","记者引用一份实际读过的公司快报，不是第二份独立证据；仍待履约和回款验证。");
            return act("POST","记者实际读到经理快报后公开转述；保留单一来源，不能假称独立核实",
                    "sourceDoc",doc,"title","经营观察 · "+fact.path("name").asText(),"body",article.toString());
        }
        if(Set.of("alice","investor","nina","omar","bea").contains(person)) {
            Set<String> read=new HashSet<>();for(JsonNode e:p.path("observations"))read.add(e.path("ref").path("docId").asText());
            if(person.equals("nina"))for(var c:p.path("catalog"))if(c.path("docId").asText().startsWith(PlatformPostModel.DOC_PREFIX)&&!read.contains(c.path("docId").asText()))
                return read(c.path("docId").asText(),"本人习惯先看公开报道；转述仍保留原公司来源，不能算双重验证");
            Map<String,JsonNode> latest=new TreeMap<>();Map<String,Long> seen=new TreeMap<>();
            for(JsonNode c:p.path("catalog")){String doc=c.path("docId").asText();if(!doc.startsWith("connected-news:"))continue;String company=doc.split(":")[1];
                if(read.contains(doc))seen.merge(company,c.path("publishedAt").asLong(),Math::max);
                JsonNode previous=latest.get(company);if(!read.contains(doc)&&(previous==null||c.path("publishedAt").asLong()>previous.path("publishedAt").asLong()))latest.put(company,c);}
            var next=latest.entrySet().stream().min(Comparator.<Map.Entry<String,JsonNode>>comparingLong(e->seen.getOrDefault(e.getKey(),-1L)).thenComparing(Map.Entry::getKey));
            if(next.isPresent())return read(next.get().getValue().path("docId").asText(),"优先补读最久未了解公司的最新快报；注意力有限，实际读完才知道正文");
        }
        return waitFor("当前信息、现金与持仓不足以支持新的动作；保留观察，不凑交易次数");
    }
    private static JsonNode latestFact(JsonNode p,String symbol){JsonNode found=JSON.createObjectNode();long at=-1;for(JsonNode e:p.path("observations")){JsonNode f=parse(e.path("body").asText());if(f.path("symbol").asText().equals(symbol)&&f.has("equity")&&e.path("publishedAt").asLong()>at){found=f.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)found).put("sourceDoc",e.path("ref").path("docId").asText());at=e.path("publishedAt").asLong();}}return found;}
    private static BigDecimal nav(JsonNode i,String account){var p=i.path("role").path("profile");BigDecimal n=dec(account(p,account),"cash");for(JsonNode h:p.path("holdings"))if(h.path("accountId").asText().equals(account)){var m=market(p,h.path("symbol").asText());BigDecimal price=m.hasNonNull("lastPrice")?dec(m,"lastPrice"):rows(i.path("listings")).stream().filter(l->l.path("symbol").asText().equals(h.path("symbol").asText())).map(l->dec(l,"reference")).findFirst().orElse(BigDecimal.ZERO);n=n.add(price.multiply(BigDecimal.valueOf(h.path("quantity").asLong())));}return n;}
    private static JsonNode account(JsonNode p,String id){return rows(p.path("accounts")).stream().filter(a->a.path("accountId").asText().equals(id)).findFirst().orElse(JSON.createObjectNode());}
    private static JsonNode holding(JsonNode p,String s){return rows(p.path("holdings")).stream().filter(h->h.path("symbol").asText().equals(s)).findFirst().orElse(JSON.createObjectNode());}
    private static JsonNode market(JsonNode p,String s){return rows(p.path("market")).stream().filter(m->m.path("symbol").asText().equals(s)).findFirst().orElse(JSON.createObjectNode());}
    static JsonNode parse(String s){try{return JSON.readTree(s);}catch(Exception e){return JSON.createObjectNode();}}
    static BigDecimal dec(JsonNode n,String key){return new BigDecimal(n.path(key).asText("0"));}
    static List<JsonNode> rows(JsonNode n){var r=new ArrayList<JsonNode>();n.elements().forEachRemaining(r::add);return r;}
    static Choice act(String action,String reason,Object...kv){Map<String,Object>f=new TreeMap<>();for(int i=0;i<kv.length;i+=2)f.put((String)kv[i],kv[i+1]);return new Choice(action,f,reason);}
    static Choice read(String doc,String reason){return act("READ",reason,"docId",doc);}
    static Choice waitFor(String reason){return new Choice("WAIT",Map.of(),reason);}
}
