package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** 已发帖和本人已投递订阅件的文号版次；检索本身不读取原文。 */
final class PublicationRoleHistory {
    private PublicationRoleHistory() { }

    record Entry(String docId,long version,long at) {
        String key() { return docId+"@"+version; }
    }
    record Index(List<Entry> rows) { }
    private static final Comparator<Entry> RECENT = Comparator.comparingLong(Entry::at).reversed().thenComparing(Entry::key);

    static Index index(GroupsRun groups,InformationModel.State info,String person) {
        var rows = new ArrayList<Entry>();
        if (groups != null) {
            for (var post : groups.state().posts())
                if (person.equals(post.author())) rows.add(new Entry(post.docId(),1,post.publishedAt()));
            for (var post : groups.state().postVersions())
                if (person.equals(post.author())) rows.add(new Entry(post.docId(),post.version(),post.publishedAt()));
            for (var post : groups.publication().platform().posts())
                if (person.equals(post.author())) rows.add(new Entry(post.docId(),post.version(),post.publishedAt()));
        }
        if (info != null) for (var receipt : MediaSubscriptions.deliveries(info,person))
            rows.add(new Entry((String)receipt.get("docId"),(Long)receipt.get("version"),(Long)receipt.get("deliveredAt")));
        rows.sort(RECENT);
        return new Index(List.copyOf(rows));
    }

    static ParticipantHistory.Selection select(Index index,ParticipantHistory.Request request) {
        var rows = index.rows();
        if (request.id() != null) {
            var match = rows.stream().filter(r -> request.id().equals(r.key())).findFirst();
            if (match.isEmpty()) match = rows.stream().filter(r -> request.id().equals(r.docId())).findFirst();
            return new ParticipantHistory.Selection(match.stream().map(Entry::key).toList(),null,List.of());
        }
        return ParticipantHistory.selectIds(request,rows.stream().map(Entry::key).distinct().toList());
    }

    private static Entry entry(JsonNode row) {
        return new Entry(row.path("docId").asText(),row.path("version").asLong(1),
                row.has("deliveredAt") ? row.path("deliveredAt").asLong() : row.path("publishedAt").asLong());
    }

    static void project(ObjectNode root) {
        if (!(root.at("/engineering/historyLookup") instanceof ObjectNode info)) return;
        ObjectNode last = info.get("lastResult") instanceof ObjectNode result
                && "publications".equals(result.path("section").asText()) ? result : null;
        var selected = new HashSet<String>();
        if (last != null) for (var id : last.path("ids")) selected.add(id.asText());
        var visibleKeys = new HashSet<String>();
        var visibleDocs = new HashSet<String>();
        var counts = info.putObject("publicationCounts");
        for (String path : List.of("/groups/myPosts","/groups/myPlatformPosts","/engineering/mediaSubscriptions/deliveries")) {
            if (!(root.at(path) instanceof ArrayNode rows)) continue;
            var completed = new ArrayList<Entry>();
            for (var row : rows) {
                var ref = entry(row);
                if (last != null) { visibleKeys.add(ref.key()); visibleDocs.add(ref.docId()); }
                if (!"QUEUED".equals(row.path("status").asText())) completed.add(ref);
            }
            var keep = new HashSet<>(selected);
            completed.stream().sorted(RECENT).limit(ParticipantHistory.PAGE_SIZE).map(Entry::key).forEach(keep::add);
            counts.put(path.substring(path.lastIndexOf('/')+1),completed.size());
            // 留住未投递事项、该栏最近四件和本人刚刚付出工时查回的版本。
            var kept = rows.arrayNode();
            for (var row : rows)
                if ("QUEUED".equals(row.path("status").asText()) || keep.contains(entry(row).key())) kept.add(row);
            rows.removeAll().addAll(kept);
        }
        if (last == null) return;
        var ids = last.path("ids");
        var retained = last.putArray("ids");
        for (var id : ids) if (visibleKeys.contains(id.asText())) retained.add(id.asText());
        if (!visibleKeys.contains(last.path("id").asText()) && !visibleDocs.contains(last.path("id").asText())) last.remove("id");
        for (var name : List.of("before","nextBefore"))
            if (!visibleKeys.contains(last.path(name).asText())) last.remove(name);
    }
}
