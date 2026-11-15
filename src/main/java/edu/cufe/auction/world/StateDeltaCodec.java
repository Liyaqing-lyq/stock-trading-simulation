package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 显式 delta-v1 状态记录；精确十进制、确定性结构差量与前后状态摘要绑定。 */
final class StateDeltaCodec {
    static final String TYPE = "STATE_DELTA_V1";
    static final String VERSION = "delta-v1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private StateDeltaCodec() { }

    static String encode(String runId, long position, String previous, String next) throws IOException {
        JsonNode before = previous == null ? NullNode.instance : canonical(JSON.readTree(previous));
        JsonNode after = canonical(JSON.readTree(next));
        ObjectNode record = JSON.createObjectNode();
        record.put("version", VERSION); record.put("runId", runId);
        record.put("basePosition", position - 1); record.put("position", position);
        record.put("baseDigest", digest(before)); record.put("resultDigest", digest(after));
        record.set("patch", diff(before, after));
        return JSON.writeValueAsString(record);
    }

    static String apply(String runId, long position, String previous, String payload) throws IOException {
        try {
            JsonNode record = JSON.readTree(payload);
            JsonNode before = previous == null ? NullNode.instance : canonical(JSON.readTree(previous));
            if (!VERSION.equals(record.path("version").asText()) || !runId.equals(record.path("runId").asText())
                    || !record.path("position").isIntegralNumber() || record.path("position").longValue() != position
                    || !record.path("basePosition").isIntegralNumber() || record.path("basePosition").longValue() != position - 1
                    || (previous == null && position != 1)
                    || !digest(before).equals(record.path("baseDigest").asText()))
                throw new IOException("状态差量版本、运行或基准不匹配");
            JsonNode after = patch(before, record.get("patch"));
            String result = JSON.writeValueAsString(after);
            // 重建唯一规范记录，拒绝多余字段、重复/缺失操作、错误结果摘要及非规范操作形态。
            if (!JSON.readTree(encode(runId, position, previous, result)).equals(record))
                throw new IOException("状态差量结果摘要或规范操作不匹配");
            return result;
        } catch (RuntimeException invalid) { throw new IOException("状态差量结构不合法", invalid); }
    }

    private static JsonNode diff(JsonNode before, JsonNode after) {
        ObjectNode result = JSON.createObjectNode();
        if (before.isObject() && after.isObject()) {
            result.put("kind", "object"); ObjectNode changes = result.putObject("changes");
            ArrayNode removed = result.putArray("removed");
            TreeSet<String> names = new TreeSet<>(); before.fieldNames().forEachRemaining(names::add);
            after.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!after.has(name)) removed.add(name);
                else if (!before.has(name)) changes.set(name, replacement(after.get(name)));
                else { JsonNode change = diff(before.get(name), after.get(name)); if (!unchanged(change)) changes.set(name, change); }
            }
            return result;
        }
        if (before.isArray() && after.isArray()) {
            result.put("kind", "array"); result.put("length", after.size());
            ObjectNode changes = result.putObject("changes");
            for (int i = 0; i < after.size(); i++) {
                JsonNode change = i < before.size() ? diff(before.get(i), after.get(i)) : replacement(after.get(i));
                if (i >= before.size() || !unchanged(change)) changes.set(Integer.toString(i), change);
            }
            if (before.size() == after.size() && changes.isEmpty()) return JSON.createObjectNode().put("kind", "same");
            return result;
        }
        return before.toString().equals(after.toString()) ? JSON.createObjectNode().put("kind", "same") : replacement(after);
    }

    private static boolean unchanged(JsonNode change) {
        return "same".equals(change.path("kind").asText()) || ("object".equals(change.path("kind").asText())
                && change.path("changes").isEmpty() && change.path("removed").isEmpty());
    }
    private static ObjectNode replacement(JsonNode value) {
        ObjectNode result = JSON.createObjectNode().put("kind", "replace"); result.set("value", value); return result;
    }
    private static JsonNode patch(JsonNode before, JsonNode change) throws IOException {
        if (change == null || !change.isObject()) throw new IOException("缺少状态差量操作");
        switch (change.path("kind").asText()) {
            case "same": return before;
            case "replace":
                if (!change.has("value")) throw new IOException("缺少替换值");
                return change.get("value");
            case "object": {
                if (!before.isObject() || !change.path("changes").isObject() || !change.path("removed").isArray()) throw new IOException("对象差量类型不符");
                ObjectNode result = before.deepCopy();
                for (JsonNode name : change.get("removed")) {
                    if (!name.isTextual() || !result.has(name.textValue())) throw new IOException("删除字段不存在");
                    result.remove(name.textValue());
                }
                var fields = change.get("changes").fields();
                while (fields.hasNext()) { var entry = fields.next(); result.set(entry.getKey(), patch(result.path(entry.getKey()), entry.getValue())); }
                // 全部对象键规范排序；不会转换数值或字符串内容。
                ObjectNode sorted = JSON.createObjectNode(); TreeSet<String> names = new TreeSet<>(); result.fieldNames().forEachRemaining(names::add);
                for (String name : names) sorted.set(name, result.get(name));
                return sorted;
            }
            case "array": {
                if (!before.isArray() || !change.path("length").isInt() || !change.path("changes").isObject()) throw new IOException("数组差量类型不符");
                int length = change.get("length").intValue();
                // 一次增长必须有每个新元素的实体操作；先核对，避免恶意长度造成分配。
                if (length < 0 || (long) length > (long) before.size() + change.get("changes").size()) throw new IOException("数组差量长度不合法");
                ArrayNode result = JSON.createArrayNode();
                for (int i = 0; i < length; i++) {
                    JsonNode old = i < before.size() ? before.get(i) : MissingNode.getInstance();
                    JsonNode op = change.get("changes").get(Integer.toString(i));
                    if (old.isMissingNode() && op == null) throw new IOException("缺少新增数组元素");
                    result.add(op == null ? old : patch(old, op));
                }
                return result;
            }
            default: throw new IOException("未知状态差量操作");
        }
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode(); TreeSet<String> names = new TreeSet<>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) result.set(name, canonical(node.get(name))); return result;
        }
        if (node.isArray()) { ArrayNode result = JSON.createArrayNode(); for (JsonNode item : node) result.add(canonical(item)); return result; }
        return node;
    }
    private static String digest(JsonNode value) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
