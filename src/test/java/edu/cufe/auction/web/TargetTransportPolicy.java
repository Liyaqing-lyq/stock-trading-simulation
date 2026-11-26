package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.LiveCompletionInbox;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 正式目标试验的短暂传输失败记录。按派发序号更新，网络到达顺序不改变连续故障判断。 */
final class TargetTransportPolicy {
    static final String VERSION = "target-transient-eight-v1";
    static final String STOP = "PROBE_CONSECUTIVE_TRANSPORT_FAILURES";
    static final int LIMIT = 8;
    private static final Set<String> CODES = Set.of("HTTP_NETWORK","HTTP_TIMEOUT","HTTP_RATE_LIMITED","HTTP_SERVICE_UNAVAILABLE");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final int initial;
    private int consecutive, nextIndex = 1;
    private boolean tripped;
    private final Map<Integer,Boolean> replies = new TreeMap<>();
    private final Map<String,Integer> failures = new TreeMap<>();

    TargetTransportPolicy(int initial) {
        if (initial < 0 || initial >= LIMIT) throw new IllegalArgumentException("停点没有可续行的连续失败计数");
        this.initial = initial;
        this.consecutive = initial;
    }

    static int inherited(Path seed) throws IOException {
        if (seed == null) return 0;
        var count = JSON.readTree(seed.resolve("result.json").toFile()).at("/providerProvenance/transport/consecutive");
        if (!count.isIntegralNumber() || !count.canConvertToInt()) throw new IllegalArgumentException("缺少连续传输失败记录");
        return count.intValue();
    }

    static boolean transientFailure(LiveDecisionProvider.ProviderProvenance provenance, LiveCompletionInbox.Completion completion) {
        var code = completion.failureCode();
        return !completion.businessReply() && code != null && CODES.contains(code)
                && code.equals(provenance.failureCode()) && "UNKNOWN".equals(provenance.outcome())
                && completion.reply().body().isEmpty() && provenance.requestId() == null && provenance.providerReportedModel() == null
                && provenance.invocation() != null && provenance.invocation().finishReason() == null;
    }

    void record(int index, boolean transientFailure, String code) {
        if (transientFailure) failures.merge(code,1,Integer::sum);
        replies.put(index,transientFailure);
        Boolean value;
        while ((value = replies.remove(nextIndex)) != null) {
            nextIndex++;
            consecutive = value ? consecutive + 1 : 0;
            if (consecutive >= LIMIT) tripped = true;
        }
    }

    boolean tripped() { return tripped; }
    ObjectNode summary() {
        var row = JSON.createObjectNode().put("policy",VERSION).put("initialConsecutive",initial)
                .put("consecutive",consecutive).put("stopThreshold",LIMIT).put("thresholdReached",tripped);
        row.set("failuresByCode",JSON.valueToTree(failures));
        return row;
    }
}
