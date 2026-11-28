package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
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

    /** 只读已通过完整恢复校验的提交，保留绩效核算使用的字段。它不用于确认或恢复世界。 */
    record ReturnFields(long position, String digest, Map<String,JsonNode> fields) {
        private static final List<String> KEYS = List.of("accounts","securities","exchange","dividends","bank","timeMillis");

        static ReturnFields next(WorldCommit commit, ReturnFields previous, boolean openFund) throws IOException {
            var event = commit.events().stream().filter(e -> TYPE.equals(e.type())).findFirst()
                    .orElseThrow(() -> new IOException("已确认帧缺少状态差量"));
            JsonNode record = JSON.readTree(event.payload());
            long priorPosition = previous == null ? 0 : previous.position;
            String base = previous == null ? StateDeltaCodec.digest(NullNode.instance) : previous.digest;
            if (commit.position() != priorPosition + 1 || record.path("position").asLong() != commit.position()
                    || record.path("basePosition").asLong(-1) != priorPosition || !base.equals(record.path("baseDigest").asText())
                    || !commit.runId().equals(record.path("runId").asText()) || !VERSION.equals(record.path("version").asText()))
                throw new IOException("收益汇总的确认前缀不连续");
            var fields = previous == null ? new HashMap<String,JsonNode>() : new HashMap<>(previous.fields);
            JsonNode operation = record.path("patch");
            for (String key : KEYS) update(fields, key, child(operation,key));
            if (openFund) update(fields, "fundUnits", child(child(operation,"trading"),"fundUnits"));
            return new ReturnFields(commit.position(), record.path("resultDigest").asText(), Map.copyOf(fields));
        }

        private static void update(Map<String,JsonNode> fields, String key, JsonNode change) throws IOException {
            if (change != null) fields.put(key, patch(fields.getOrDefault(key, NullNode.instance), change));
        }

        private static JsonNode child(JsonNode operation, String key) throws IOException {
            if (operation == null) return null;
            return switch (operation.path("kind").asText()) {
                case "same" -> null;
                case "replace" -> replacement(operation.path("value").has(key)
                        ? operation.path("value").get(key) : NullNode.instance);
                case "object" -> {
                    var changed = operation.path("changes").get(key);
                    if (changed == null) {
                        for (var removed : operation.path("removed"))
                            if (key.equals(removed.asText())) { changed = replacement(NullNode.instance); break; }
                    }
                    yield changed;
                }
                default -> throw new IOException("经济字段不在对象差量中");
            };
        }

        boolean changed(String name, ReturnFields previous) {
            return previous == null || fields.get(name) != previous.fields.get(name);
        }
    }

    static String encode(String runId, long position, String previous, String next) throws IOException {
        JsonNode before = previous == null ? NullNode.instance : canonical(JSON.readTree(previous));
        JsonNode after = canonical(JSON.readTree(next));
        return encodeCanonical(runId, position, before, after, digest(before));
    }

    /** 沿用该世界的规范写法，已归档的角色原文无需先拼入两个完整状态字符串。 */
    static String encodeState(String runId, long position, ObjectMapper writer, Object previous, Object next)
            throws IOException {
        JsonNode before = stateTree(writer, previous);
        return encodeCanonical(runId, position, before, stateTree(writer, next), digest(before));
    }

    private static JsonNode stateTree(ObjectMapper writer, Object state) throws IOException {
        if (state == null) return NullNode.instance;
        try (var tokens = new TokenBuffer(writer, false)) {
            writer.writeValue(tokens, state);
            try (var parser = tokens.asParser()) {
                return canonical(JSON.readTree(parser));
            }
        }
    }

    private static String encodeCanonical(String runId, long position, JsonNode before,
                                          JsonNode after, String baseDigest) throws IOException {
        ObjectNode record = JSON.createObjectNode();
        record.put("version", VERSION); record.put("runId", runId);
        record.put("basePosition", position - 1); record.put("position", position);
        record.put("baseDigest", baseDigest); record.put("resultDigest", digest(after));
        record.set("patch", diff(before, after));
        return JSON.writeValueAsString(record);
    }

    static String apply(String runId, long position, String previous, String payload) throws IOException {
        return JSON.writeValueAsString(checkedTree(runId, position, previous, payload));
    }

    /** 已逐项校验的结果树可直接供强类型快照读取，避免再次转成整份文本。 */
    static JsonNode checkedTree(String runId, long position, String previous, String payload) throws IOException {
        try {
            JsonNode record = JSON.readTree(payload);
            JsonNode before = previous == null ? NullNode.instance : canonical(JSON.readTree(previous));
            String baseDigest = digest(before);
            if (!VERSION.equals(record.path("version").asText()) || !runId.equals(record.path("runId").asText())
                    || !record.path("position").isIntegralNumber() || record.path("position").longValue() != position
                    || !record.path("basePosition").isIntegralNumber() || record.path("basePosition").longValue() != position - 1
                    || (previous == null && position != 1)
                    || !baseDigest.equals(record.path("baseDigest").asText()))
                throw new IOException("状态差量版本、运行或基准不匹配");
            JsonNode after = patch(before, record.get("patch"));
            // 重建唯一规范记录，拒绝多余字段、重复/缺失操作、错误结果摘要及非规范操作形态。
            if (!JSON.readTree(encodeCanonical(runId, position, before, canonical(after), baseDigest)).equals(record))
                throw new IOException("状态差量结果摘要或规范操作不匹配");
            return after;
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
        // 数字逐字比较以保留金额的小数位；日常未变的文本不需反复转义序列化。
        boolean same = before == after || (!before.isNumber() && before.equals(after))
                || before.toString().equals(after.toString());
        return same ? JSON.createObjectNode().put("kind", "same") : replacement(after);
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
                // 后续递归逐层新建被修改的容器，未变的子树只读共享。
                ObjectNode result = JSON.createObjectNode().setAll((ObjectNode) before);
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var bytes = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                JSON.writeValue(bytes, value);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
