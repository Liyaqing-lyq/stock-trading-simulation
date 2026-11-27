package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 从搜索时本人冻结的目录与阅读经历中查找资料。每次展示仍复核当前目录、版本与回忆。 */
final class PersonalLibrarySearch {
    static final String TOOL = "role.library.search";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMIT = 12;

    record Request(String query) {
        Request {
            if (query == null || query.isBlank() || query.length() > 120)
                throw new IllegalArgumentException("检索词应为1至120字");
        }
    }

    private PersonalLibrarySearch() { }

    static void append(ObjectNode engineering, String person, RoleTradingRun trading, DecisionRun decisions,
                       java.util.function.Supplier<RoleView> currentView) {
        ObjectNode library = engineering.putObject("librarySearch");
        library.put("contract", "可用role.library.search，自选query关键词（1至120字，空格分词且须全部匹配）。检索当次可见标题与自己已读、仍记得的正文。完成后最多展示12条按资料标识排序的结果，可缩小问题再查。查到标题后仍须另行阅读才可引用。");
        RoleTradingRun.Report last = latestCompleted(trading, person);
        if (last == null) return;
        var decision = DecisionModel.decision(decisions.state(), last.clientTaskId()).orElseThrow();
        try {
            var frozen = (ObjectNode) JSON.readTree(decision.inputText());
            RoleInputTables.expand(frozen);
            var result = library.putObject("lastResult");
            result.put("query", last.subjectId()).put("searchedAt", decision.cutoffTimeMillis())
                    .put("completedAt", last.timeMillis());
            appendMatches(result, last.subjectId(), frozen.path("profile"), currentView.get());
        } catch (IOException invalid) {
            throw new IllegalStateException("检索的冻结观察损坏", invalid);
        }
    }

    static RoleTradingRun.Report latestCompleted(RoleTradingRun trading, String person) {
        if (trading == null) return null;
        RoleTradingRun.Report last = null;
        for (var report : trading.reports().values()) {
            if (PendingTask.ROLE_LIBRARY_SEARCH_V1.equals(report.kind()) && person.equals(report.personPartyId())
                    && report.accepted() && (last == null || last.position() < report.position())) last = report;
        }
        return last;
    }

    private static void appendMatches(ObjectNode result, String query, JsonNode frozen, RoleView current) {
        List<String> terms = Arrays.asList(query.strip().toLowerCase(Locale.ROOT).split("\\s+"));
        var rows = result.putArray("matches");
        for (var entry : frozen.path("catalog")) {
            String id = entry.required("docId").textValue(), title = entry.required("title").textValue();
            long version = entry.required("version").longValue();
            if (current.catalog().stream().noneMatch(item -> item.docId().equals(id) && item.version() == version)) continue;
            String scope = "TITLE";
            if (!matches(title, terms)) {
                boolean read = false;
                for (JsonNode old : frozen.path("readExperiences")) {
                    if (id.equals(old.path("docId").asText()) && version == old.path("version").asLong()
                            && old.path("body").isTextual() && matches(title + " " + old.path("body").textValue(), terms)
                            && current.observations().stream().anyMatch(item -> item.ref().docId().equals(id)
                                    && item.ref().version() == version && item.body().equals(old.path("body").textValue()))) {
                        read = true;
                        break;
                    }
                }
                if (!read) continue;
                scope = "OWN_READING";
            }
            rows.addObject().put("docId", id).put("version", version).put("title", title).put("matchedIn", scope);
            if (rows.size() == LIMIT) return;
        }
    }

    private static boolean matches(String text, List<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        return terms.stream().allMatch(lower::contains);
    }
}
