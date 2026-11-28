package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 普通人物输入中的有限目录和同一确认位置的后续检索页。目录和检索均不登记阅读。 */
final class CatalogDiscovery {
    private static final int PREVIEW = 24;
    private static final int PAGE = 12;
    private static final int TITLE_CHARS = 120;

    record Request(String query, String cursor) {
        Request {
            if (query == null || query.length() > 120 || cursor != null && cursor.isBlank())
                throw new IllegalArgumentException("检索词最多120字，可留空；续页须使用本人上次取得的游标");
        }
    }

    record Index(List<InformationModel.CatalogEntry> catalog, List<RoleView.Experience> observations) {
        static Index from(RoleView role) { return new Index(role.catalog(), role.observations()); }
    }

    record Hit(String docId, long version, String title, String matchedIn) { }
    record Page(String query, long anchorPosition, long searchedAt, String afterDocId,
                List<Hit> hits, boolean hasMore) {
        Page { hits = List.copyOf(hits); }
    }

    private CatalogDiscovery() { }

    static String title(String full) {
        if (full.codePointCount(0, full.length()) <= TITLE_CHARS) return full;
        return full.substring(0, full.offsetByCodePoints(0, TITLE_CHARS)) + "…";
    }

    static List<InformationModel.CatalogEntry> preview(List<InformationModel.CatalogEntry> entries) {
        return entries.stream().sorted(Comparator.comparingLong(InformationModel.CatalogEntry::publishedAt).reversed()
                .thenComparing(InformationModel.CatalogEntry::docId)).limit(PREVIEW).toList();
    }

    static void describe(ObjectNode library, int total) {
        library.put("catalogPolicy", RunManifest.DISCOVERY_CATALOG_VERSION).put("totalVisible", total)
                .put("previewLimit", PREVIEW).put("pageSize", PAGE)
                .put("contract", "profile.catalog列出最近可见的最多24项，标题显示前120个字符。"
                        + "role.library.search的query可留空浏览全部，也可输入最长120字，空格分词，匹配完整标题、文号或本人已读且仍记得的正文。"
                        + "结果按文号排序，每页最多12项。继续看同一截止帧请保持query并传上页nextCursor；省略cursor会重新检索当前目录。"
                        + "点名的docId、version可用role.reading.start阅读；找到目录不等于已经读过。");
    }

    static Page find(Request request, long position, long time, String after, Index original, RoleView current) {
        List<String> terms = request.query().isBlank() ? List.of()
                : List.of(request.query().strip().toLowerCase(Locale.ROOT).split("\\s+"));
        Map<String, Long> authorized = versions(current);
        List<Hit> hits = new ArrayList<>();
        for (var doc : original.catalog().stream().sorted(Comparator.comparing(InformationModel.CatalogEntry::docId)).toList()) {
            if (doc.docId().compareTo(after) <= 0 || !Long.valueOf(doc.version()).equals(authorized.get(doc.docId()))) continue;
            String scope = "TITLE";
            if (!matches(doc.docId() + " " + doc.title(), terms)) {
                boolean recalled = original.observations().stream().anyMatch(exp -> exp.ref().docId().equals(doc.docId())
                        && exp.ref().version() == doc.version() && matches(doc.title() + " " + exp.body(), terms)
                        && current.observations().stream().anyMatch(x -> x.ref().docId().equals(doc.docId())
                                && x.ref().version() == doc.version() && x.body().equals(exp.body())));
                if (!recalled) continue;
                scope = "OWN_READING";
            }
            if (hits.size() == PAGE) return new Page(request.query(), position, time, after, hits, true);
            hits.add(new Hit(doc.docId(), doc.version(), title(doc.title()), scope));
        }
        return new Page(request.query(), position, time, after, hits, false);
    }

    static void show(ObjectNode library, Page page, RoleTradingRun.Report report, RoleView current) {
        Map<String,Long> authorized = versions(current);
        var result = library.putObject("lastResult");
        result.put("query",page.query()).put("searchedAt",page.searchedAt()).put("anchorPosition",page.anchorPosition())
                .put("completedAt",report.timeMillis());
        var matches = result.putArray("matches");
        for (Hit hit : page.hits()) {
            if (!Long.valueOf(hit.version()).equals(authorized.get(hit.docId()))) continue;
            if (hit.matchedIn().equals("OWN_READING") && current.observations().stream().noneMatch(x ->
                    x.ref().docId().equals(hit.docId()) && x.ref().version()==hit.version())) continue;
            matches.addObject().put("docId",hit.docId()).put("version",hit.version())
                    .put("title",hit.title()).put("matchedIn",hit.matchedIn());
        }
        if (page.hasMore()) result.put("nextCursor",report.clientTaskId());
    }

    private static Map<String,Long> versions(RoleView view) {
        Map<String,Long> authorized = new HashMap<>();
        for (var doc : view.catalog()) authorized.put(doc.docId(),doc.version());
        return authorized;
    }

    private static boolean matches(String text, List<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        return terms.stream().allMatch(lower::contains);
    }
}
