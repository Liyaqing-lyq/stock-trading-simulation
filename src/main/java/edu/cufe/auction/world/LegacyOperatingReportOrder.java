package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

/** 旧规范存储没有保存 map 迭代序或重开位置；仅校验并恢复可能的旧报告数组表示。 */
final class LegacyOperatingReportOrder {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final Set<String> ALL_PATHS = Set.of("hydroOperations", "resourceOperations", "digitalOperations", "agedOperations", "companyOperations");
    private LegacyOperatingReportOrder() { }

    /** 只允许实际仍从 map.values() 投影的路径；已由信息合同固定排序的制造数组不参与提示或cut。 */
    static Set<String> affectedPaths(RunManifest manifest) {
        Set<String> paths = new HashSet<>(ALL_PATHS);
        String behavior = manifest.dataVersions().get("behavior-policy");
        if (ManufacturingBehaviorPolicy.VERSION.equals(behavior) || ConnectedWorldLoop.VERSION.equals(behavior)
                || ManagerOperatingInformation.VERSION.equals(manifest.dataVersions().get("operating-information")))
            paths.remove("companyOperations");
        return Set.copyOf(paths);
    }

    /**
     * 历史 taskId 只提示排列；每行全文仍取可信重生成文本，非报告字节完全不变。
     * 所有数组必须符合同一个可能重开位置：此前报告按键排序，此后按提交位置追加。
     */
    static String reconstruct(String generated, String historical, long cutoffPosition, Set<String> affectedPaths) throws IOException {
        List<Projection> projections = projections(generated, historical, cutoffPosition, affectedPaths);
        requireCommonCut(projections);
        Map<String, Projection> changed = new HashMap<>();
        for (Projection projection : projections)
            if (!projection.generatedIds().equals(projection.historicalIds())) changed.put(projection.path(), projection);
        if (changed.isEmpty()) return generated;

        // String parser 的 token offset 是 Java 字符位置；只拼接这些真实数组 token 范围。
        List<Replacement> replacements = new ArrayList<>();
        try (JsonParser parser = JSON.getFactory().createParser(generated)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.START_ARRAY) continue;
                Projection projection = changed.get(parser.getParsingContext().pathAsPointer().toString());
                if (projection == null) continue;
                int start = Math.toIntExact(parser.getTokenLocation().getCharOffset());
                Map<String, String> trustedRows = new HashMap<>(); int index = 0;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT || index >= projection.generatedIds().size()) throw invalid();
                    int rowStart = Math.toIntExact(parser.getTokenLocation().getCharOffset());
                    parser.skipChildren();
                    int rowEnd = Math.toIntExact(parser.getCurrentLocation().getCharOffset());
                    trustedRows.put(projection.generatedIds().get(index++), generated.substring(rowStart, rowEnd));
                }
                if (index != projection.generatedIds().size()) throw invalid();
                int end = Math.toIntExact(parser.getCurrentLocation().getCharOffset());
                String reordered = "[" + String.join(",", projection.historicalIds().stream().map(trustedRows::get).toList()) + "]";
                replacements.add(new Replacement(start, end, reordered));
            }
        }
        if (replacements.size() != changed.size()) throw invalid();
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        StringBuilder result = new StringBuilder(generated);
        for (Replacement replacement : replacements) result.replace(replacement.start(), replacement.end(), replacement.text());
        return result.toString();
    }

    /** 同一开决定批次的所有人物共用同一 previous，连字节已相等的输入也必须参加共同cut校验。 */
    static void requireCommonBatch(List<InputPair> inputs, long cutoffPosition, Set<String> affectedPaths) throws IOException {
        List<Projection> all = new ArrayList<>();
        for (InputPair input : inputs) all.addAll(projections(input.generated(), input.historical(), cutoffPosition, affectedPaths));
        requireCommonCut(all);
    }
    record InputPair(String generated, String historical) { }

    private static List<Projection> projections(String generated, String historical, long cutoffPosition, Set<String> affectedPaths) throws IOException {
        if (affectedPaths == null || !ALL_PATHS.containsAll(affectedPaths)) throw invalid();
        JsonNode expected = JSON.readTree(generated).path("engineering");
        JsonNode stored = JSON.readTree(historical).path("engineering");
        if (expected.isMissingNode() && stored.isMissingNode()) return List.of();
        if (!expected.isObject() || !stored.isObject()) throw invalid();
        List<Projection> projections = new ArrayList<>();
        for (String industry : List.of("hydroOperations", "resourceOperations", "digitalOperations", "agedOperations")) {
            if (!affectedPaths.contains(industry) || (!expected.has(industry) && !stored.has(industry))) continue;
            if (!expected.path(industry).isObject() || !stored.path(industry).isObject()) throw invalid();
            projections.add(projection("/engineering/" + industry + "/reports",
                    expected.path(industry).path("reports"), stored.path(industry).path("reports"), cutoffPosition));
        }
        if (affectedPaths.contains("companyOperations") && (expected.has("companyOperations") || stored.has("companyOperations"))) {
            JsonNode companies = expected.path("companyOperations"), oldCompanies = stored.path("companyOperations");
            if (!companies.isArray() || !oldCompanies.isArray() || companies.size() != oldCompanies.size()) throw invalid();
            for (int i = 0; i < companies.size(); i++) {
                if (!companies.get(i).isObject() || !oldCompanies.get(i).isObject()) throw invalid();
                projections.add(projection("/engineering/companyOperations/" + i + "/reports",
                        companies.get(i).path("reports"), oldCompanies.get(i).path("reports"), cutoffPosition));
            }
        }
        return projections;
    }
    private static void requireCommonCut(List<Projection> projections) throws IOException {
        TreeSet<Long> possibleCuts = new TreeSet<>(); possibleCuts.add(0L);
        for (Projection projection : projections) for (Row row : projection.chronological()) possibleCuts.add(row.position());
        if (possibleCuts.stream().noneMatch(cut -> projections.stream().allMatch(p -> p.orderAt(cut).equals(p.historicalIds()))))
            throw invalid();
    }

    private static Projection projection(String path, JsonNode generated, JsonNode historical, long cutoff) throws IOException {
        if (!generated.isArray() || !historical.isArray() || generated.size() != historical.size()) throw invalid();
        List<String> generatedIds = ids(generated), historicalIds = ids(historical);
        if (!new HashSet<>(generatedIds).equals(new HashSet<>(historicalIds))) throw invalid();
        List<Row> chronological = new ArrayList<>(); Set<Long> positions = new HashSet<>();
        for (JsonNode row : generated) {
            long position;
            try { position = Long.parseLong(row.path("position").asText()); }
            catch (NumberFormatException malformed) { throw invalid(); }
            if (position <= 0 || position > cutoff || !positions.add(position)) throw invalid();
            chronological.add(new Row(row.path("taskId").textValue(), position));
        }
        chronological.sort(Comparator.comparingLong(Row::position));
        return new Projection(path, generatedIds, historicalIds, List.copyOf(chronological));
    }
    private static List<String> ids(JsonNode array) throws IOException {
        List<String> ids = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (JsonNode row : array) {
            if (!row.isObject() || !row.path("taskId").isTextual()) throw invalid();
            String id = row.path("taskId").textValue();
            if (id.isBlank() || !unique.add(id)) throw invalid();
            ids.add(id);
        }
        return List.copyOf(ids);
    }
    private static IOException invalid() { return new IOException("旧经营报告数组不是可验证的历史表示"); }
    private record Row(String id, long position) { }
    private record Replacement(int start, int end, String text) { }
    private record Projection(String path, List<String> generatedIds, List<String> historicalIds, List<Row> chronological) {
        List<String> orderAt(long cut) {
            List<String> order = new ArrayList<>();
            chronological.stream().filter(row -> row.position() <= cut).map(Row::id).sorted().forEach(order::add);
            chronological.stream().filter(row -> row.position() > cut).map(Row::id).forEach(order::add);
            return order;
        }
    }
}
