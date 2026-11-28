package edu.cufe.auction.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 人物工作台的资料检索页，以首次浏览的目录及当前读权共同限定可见版本。每人只保留最近一次检索。 */
public final class PersonalLibraryPages {
    private record Search(String run, String query, String token, CatalogDiscovery.Index index, Set<String> cursors) {}
    private final Map<String,Search> searches = new HashMap<>();

    /** 读取一页目录。检索不登记阅读，首次查找不传 token 和 after，翻页须使用该人物原检索的凭据。 */
    public synchronized Map<String,Object> page(String run, RoleView current, String query, String token, String after) {
        var request = new CatalogDiscovery.Request(query,null);
        String person = current.personPartyId();
        Search search;
        if (token == null) {
            if (after != null) throw new IllegalArgumentException("翻页需要本次资料检索的凭据");
            search = new Search(run,query,UUID.randomUUID().toString(),CatalogDiscovery.Index.from(current),new HashSet<>());
            searches.put(person,search);
        } else {
            search=searches.get(person);
            if (search==null || !search.token().equals(token) || !search.run().equals(run) || !search.query().equals(query)
                    || after != null && !search.cursors().contains(after))
                throw new IllegalArgumentException("资料检索已失效，请重新查找");
        }
        var page=CatalogDiscovery.find(request,0L,current.timeMillis(),after==null ? "" : after,search.index(),current);
        var rows = new java.util.ArrayList<Map<String,Object>>();
        for (var hit : page.hits()) {
            var row = new LinkedHashMap<String,Object>();
            row.put("docId",hit.docId()); row.put("version",Long.toString(hit.version())); row.put("title",hit.title());
            row.put("matchedIn",hit.matchedIn());
            current.readings().stream().filter(r -> r.ref().docId().equals(hit.docId()) && r.ref().version()==hit.version())
                    .findFirst().ifPresent(r -> row.put("readingDueMillis",Long.toString(r.dueAt())));
            current.observations().stream().filter(r -> r.ref().docId().equals(hit.docId()) && r.ref().version()==hit.version())
                    .findFirst().ifPresent(r -> {
                        row.put("body",r.body()); row.put("completedAtMillis",Long.toString(r.recall()==null ? r.completedAt() : r.recall().lastReadAt()));
                    });
            rows.add(row);
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("enabled",true); result.put("rows",List.copyOf(rows)); result.put("token",search.token());
        if (page.hasMore()) {
            String next=page.hits().get(page.hits().size()-1).docId();
            search.cursors().add(next); result.put("next",next);
        }
        return result;
    }
}
