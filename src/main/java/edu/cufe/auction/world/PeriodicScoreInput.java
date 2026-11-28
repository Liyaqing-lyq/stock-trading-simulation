package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 新版董事评分工具依据本次派发时本人可见的期间、证据、维度和时点收件。 */
final class PeriodicScoreInput {
    static final String GUIDE = "periodic.score 的 companyPartyId、period 取本人董事席位及其期间。"
            + "metrics 填1至5项，每项恰含 dimension、score、sourceRef、asOf。"
            + "从该期 evidence 的一行复制 sourceRef 和整数 asOf，从它的 dimensions 选择一个 dimension。"
            + "score 自行填写0至100的 JSON 数字，最多两位小数；同一维度只填一次，缺乏依据的维度省略。理由填 rationale。";
    private static final ObjectMapper EXACT = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private PeriodicScoreInput() { }

    static boolean validMetrics(List<PeriodicActionPayload.PeriodicMetric> metrics) {
        if (metrics == null || metrics.isEmpty() || metrics.size() > BoardModel.Dimension.values().length) return false;
        var dimensions = new HashSet<BoardModel.Dimension>();
        for (var item : metrics) {
            if (item == null) return false;
            var score = item.score();
            if (score.signum() < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0
                    || score.stripTrailingZeros().scale() > 2) return false;
            try {
                if (!dimensions.add(BoardModel.Dimension.valueOf(item.dimension()))) return false;
            } catch (IllegalArgumentException unknown) {
                return false;
            }
        }
        return true;
    }

    static List<PeriodicActionPayload.PeriodicMetric> parse(DecisionModel.Decision decision,
            String company, long period) throws IOException {
        var attempts = decision.attempts();
        // 原始回执保留十进制精度，不能先经 double 四舍五入再做 0–100 的边界判断。
        JsonNode args = EXACT.readTree(attempts.get(attempts.size() - 1).replyBody()).path("args");
        JsonNode items = args.path("metrics");
        if (!items.isArray() || items.isEmpty() || items.size() > BoardModel.Dimension.values().length
                || !args.path("period").isIntegralNumber() || !args.path("period").canConvertToLong()) return List.of();
        JsonNode evidence = null;
        for (var office : EXACT.readTree(decision.inputText()).at("/engineering/directorships")) {
            if (!company.equals(office.path("companyPartyId").asText())) continue;
            for (var row : office.path("periodic"))
                if (row.path("period").longValue() == period) evidence = row.path("evidence");
        }
        if (evidence == null || !evidence.isArray()) return List.of();
        var result = new ArrayList<PeriodicActionPayload.PeriodicMetric>();
        for (var item : items) {
            var when = item.path("asOf");
            var score = item.path("score");
            if (!item.isObject() || item.size() != 4 || !item.path("dimension").isTextual()
                    || !item.path("sourceRef").isTextual() || !score.isNumber()
                    || !when.isIntegralNumber() || !when.canConvertToLong()) return List.of();
            String dimension = item.get("dimension").textValue(), ref = item.get("sourceRef").textValue();
            BigDecimal value = score.decimalValue();
            boolean matches = false;
            for (var source : evidence) {
                if (ref.equals(source.path("sourceRef").asText()) && source.path("asOf").longValue() == when.longValue()) {
                    for (var allowed : source.path("dimensions"))
                        if (dimension.equals(allowed.textValue())) matches = true;
                }
            }
            if (!matches) return List.of();
            result.add(new PeriodicActionPayload.PeriodicMetric(dimension, value, ref, when.longValue()));
        }
        return validMetrics(result) ? List.copyOf(result) : List.of();
    }
}
