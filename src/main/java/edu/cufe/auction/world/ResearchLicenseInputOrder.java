package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** 第十一版初次发行的许可投影使用 JDK Map.of，其十个固定键按哈希槽环的两个方向迭代。 */
final class ResearchLicenseInputOrder {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final String PATH = "/engineering/researchOperations/licensing";
    private static final List<String> RING = List.of("licensee", "terms", "fee", "contracts", "durationMillis",
            "approvedProjects", "companyAccount", "totals", "payerAccount", "actions");

    private ResearchLicenseInputOrder() { }

    /** 历史只提供键顺序。仍用重生成的逐字段原文拼接，调用者另行核对整份原文和摘要。 */
    static String reconstruct(String generated, String historical) throws IOException {
        if (!generated.contains("\"licensing\"")) return generated;
        var oldTerms = JSON.readTree(historical).at(PATH);
        if (!oldTerms.isObject() || oldTerms.size() != RING.size()) return generated;
        var order = new ArrayList<String>();
        oldTerms.fieldNames().forEachRemaining(order::add);
        if (!followsRing(order, 1) && !followsRing(order, -1)) return generated;

        try (var parser = JSON.getFactory().createParser(generated)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.START_OBJECT
                        || !PATH.equals(parser.getParsingContext().pathAsPointer().toString())) continue;
                int start = Math.toIntExact(parser.getTokenLocation().getCharOffset());
                var fields = new HashMap<String,String>();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String key = parser.currentName();
                    int fieldStart = Math.toIntExact(parser.getTokenLocation().getCharOffset());
                    parser.nextToken();
                    parser.skipChildren();
                    parser.finishToken();
                    fields.put(key, generated.substring(fieldStart, Math.toIntExact(parser.getCurrentLocation().getCharOffset())));
                }
                if (parser.currentToken() != JsonToken.END_OBJECT || fields.size() != order.size()
                        || !fields.keySet().containsAll(order)) return generated;
                int end = Math.toIntExact(parser.getCurrentLocation().getCharOffset());
                return generated.substring(0, start) + "{" + String.join(",", order.stream().map(fields::get).toList())
                        + "}" + generated.substring(end);
            }
        }
        return generated;
    }

    private static boolean followsRing(List<String> keys, int direction) {
        int first = RING.indexOf(keys.get(0));
        if (first < 0) return false;
        for (int i = 1; i < RING.size(); i++)
            if (!keys.get(i).equals(RING.get(Math.floorMod(first + direction * i, RING.size())))) return false;
        return true;
    }
}
