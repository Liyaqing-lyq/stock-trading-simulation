package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.util.*;

/** 同一已确认世界的即时网页数据，不依赖离线季度附件，也不补造价格或事件。 */
final class ConnectedWorldView {
    private static final long DAY=86_400_000L;
    private static final ObjectMapper JSON=new ObjectMapper();
    private ConnectedWorldView() { }
    private static com.fasterxml.jackson.databind.JsonNode read(String text){try{return JSON.readTree(text);}catch(Exception e){return JSON.createObjectNode();}}
    static void append(Map<String,Object> result,WorldRuntime w,List<Map<String,Object>> companies)throws Exception{
        if(!ConnectedWorldLoop.enabled(w))return;
        var s=w.snapshot();var activity=ConnectedWorldLoop.audit(w);
        var people=new TreeMap<String,Object>();for(var a:activity)people.put((String)a.get("person"),a);
        var news=new ArrayList<Map<String,Object>>();
        for(var d:s.information().orElseThrow().information().documents())if(d.audience().kind()==InformationModel.AudienceKind.PUBLIC&&d.docId().startsWith("connected-news:")){
            var v=d.at(1);news.add(Map.of("docId",d.docId(),"title",v.title(),"body",v.body(),"publisher",d.publisherPartyId(),"publishedAt",v.publishedAt()));
        }
        news.sort(Comparator.comparingLong(n->((Number)n.get("publishedAt")).longValue()));
        var orders=new ArrayList<Map<String,Object>>();
        for(var o:s.exchange().orElseThrow().orders()) {
            var row = new LinkedHashMap<String,Object>();
            row.put("symbol",o.symbol()); row.put("side",o.side()); row.put("type",o.type());
            row.put("quantity",o.quantity()); row.put("filled",o.filled());
            row.put("price",o.limitPrice()==null ? null : o.limitPrice().toPlainString());
            row.put("status",o.status()); row.put("accountId",o.accountId());
            orders.add(row);
        }
        long reads=s.information().orElseThrow().information().observations().size();
        var detail=new TreeMap<String,Object>();int days=(int)(s.timeMillis()/DAY)+1;
        for(var company:companies){String id=(String)company.get("id"),symbol=(String)company.get("symbol");
            var cashByDay=new TreeMap<Integer,BigDecimal>();
            for(var n:news){var f=read((String)n.get("body"));if(f.path("company").asText().equals(id))cashByDay.put((int)(((Number)n.get("publishedAt")).longValue()/DAY)+1,new BigDecimal(f.path("cash").asText()));}
            BigDecimal cash=new BigDecimal((String)company.get("cash")),opening=w.profile().openings().stream().filter(a->a.ownerId().equals(id)).map(CashLedger.OpeningAccount::cash).reduce(BigDecimal.ZERO,BigDecimal::add),lastCash=opening,last=null;
            cashByDay.put(days,cash);
            var rows=new ArrayList<Map<String,Object>>();
            for(int day=1;day<=days;day++){
                final int d=day;var trades=s.exchange().orElseThrow().trades().stream().filter(t->t.symbol().equals(symbol)&&t.timeMillis()/DAY+1==d).toList();
                if(!trades.isEmpty())last=trades.get(trades.size()-1).price();if(cashByDay.containsKey(day))lastCash=cashByDay.get(day);
                var row=new LinkedHashMap<String,Object>();row.put("day",day);row.put("lastPrice",last==null?null:last.toPlainString());row.put("close",trades.isEmpty()?null:last.toPlainString());row.put("cash",lastCash.toPlainString());row.put("trades",trades.size());row.put("volume",trades.stream().mapToLong(WorldExchange.Trade::quantity).sum());row.put("turnover",trades.stream().map(t->t.price().multiply(BigDecimal.valueOf(t.quantity()))).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());rows.add(row);
            }
            detail.put(id,Map.of("days",rows,"openingCash",opening.toPlainString(),"cashChange",cash.subtract(opening).toPlainString(),"periods",List.of()));
        }
        result.put("liveDetails",detail);
        var readableNews=new ArrayList<Map<String,Object>>();
        for(var n:news){var f=read((String)n.get("body"));var row=new LinkedHashMap<>(n);
            row.put("body",f.path("name").asText()+"披露：截至第"+(f.path("asOf").asLong()/DAY)+"日，现金 / 准备金 "+f.path("cash").asText()+" 元，累计损益 "+f.path("profit").asText()+" 元，净资产 "+f.path("equity").asText()+" 元。\n"+f.path("basis").asText());readableNews.add(row);}
        for(var d:s.information().orElseThrow().information().documents())if(d.audience().kind()==InformationModel.AudienceKind.PUBLIC&&d.docId().startsWith(PlatformPostModel.DOC_PREFIX)){
            var v=d.at(1);var f=read(v.body());
            if(f.has("originDoc"))readableNews.add(Map.of("docId",d.docId(),"title",v.title(),"body","报道转述："+f.path("name").asText()+"的累计损益为 "+f.path("profit").asText()+" 元，现金为 "+f.path("cash").asText()+" 元。"+f.path("basis").asText(),"publisher",d.publisherPartyId(),"publishedAt",v.publishedAt()));
        }
        result.put("connected",Map.of("enabled",true,"activities",activity,"news",readableNews,"peopleActivity",people,"orders",orders,
                "linkCounts",Map.of("publications",news.size(),"readings",reads,"research",s.trading().orElseThrow().trading().research().size(),"orders",orders.size(),"trades",s.exchange().orElseThrow().trades().size())));
    }
}
