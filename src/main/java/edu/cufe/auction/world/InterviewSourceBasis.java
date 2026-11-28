package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 发言者对这段话的来源声明。引用原件仅存入该文档的内部谱系，不授权听者阅读。 */
public record InterviewSourceBasis(Kind kind, List<MediaModel.Ref> sources) {
    /** 当事人自述、已读转述或尚未核实。 */
    public enum Kind { DIRECT_OBSERVATION, READ_SOURCE, UNVERIFIED }

    static final InterviewSourceBasis UNVERIFIED = new InterviewSourceBasis(Kind.UNVERIFIED, List.of());
    private static final String KIND = "interview-basis=", SOURCE = "interview-source=";

    /** 合并重复原件，固定版本；只有转述可以并且必须提供来源。 */
    public InterviewSourceBasis {
        Objects.requireNonNull(kind, "请选择采访来源声明");
        sources = (sources == null ? List.<MediaModel.Ref>of() : sources).stream()
                .distinct().sorted(Comparator.comparing(MediaModel.Ref::docId).thenComparingLong(MediaModel.Ref::version)).toList();
        if ((kind == Kind.READ_SOURCE) == sources.isEmpty())
            throw new IllegalArgumentException("READ_SOURCE 须附至少一份本人读完的原件，其他声明请省略来源或填空数组");
    }

    static InterviewSourceBasis parse(JsonNode node) {
        if (node == null || !node.isObject() || !node.path("kind").isTextual()
                || node.size() != (node.has("sources") ? 2 : 1))
            throw new IllegalArgumentException("sourceBasis 只接受 kind 与可选的 sources");
        Kind kind = Kind.valueOf(node.get("kind").textValue());
        List<MediaModel.Ref> refs = new ArrayList<>();
        if (node.has("sources")) {
            if (!node.get("sources").isArray()) throw new IllegalArgumentException("sources 须为原件数组");
            for (JsonNode ref : node.get("sources")) {
                if (!ref.isObject() || ref.size() != 2 || !ref.path("docId").isTextual()
                        || !ref.path("version").isIntegralNumber() || !ref.path("version").canConvertToLong())
                    throw new IllegalArgumentException("每个采访来源须含原件 docId 与整数 version");
                refs.add(new MediaModel.Ref(ref.get("docId").textValue(),ref.get("version").longValue()));
            }
        }
        return new InterviewSourceBasis(kind,refs);
    }

    String description() {
        return switch (kind) {
            case DIRECT_OBSERVATION -> "发言者自述亲身观察";
            case READ_SOURCE -> "发言者转述其已读材料";
            case UNVERIFIED -> "发言者未声明已核实来源";
        };
    }

    List<String> tags() {
        List<String> tags = new ArrayList<>();
        tags.add(KIND + kind.name());
        for (var source : sources) tags.add(SOURCE + source.version() + ":" + source.docId());
        return List.copyOf(tags);
    }

    static InterviewSourceBasis recorded(InformationModel.DocumentVersion version) {
        Kind kind = null;
        List<MediaModel.Ref> refs = new ArrayList<>();
        for (String tag : version.internalSourceIds()) {
            if (tag.startsWith(KIND) && kind == null) kind = Kind.valueOf(tag.substring(KIND.length()));
            else if (tag.startsWith(SOURCE)) {
                int split = tag.indexOf(':',SOURCE.length());
                if (split < 0) throw new IllegalArgumentException("采访文档来源版本不完整");
                refs.add(new MediaModel.Ref(tag.substring(split+1),Long.parseLong(tag.substring(SOURCE.length(),split))));
            } else throw new IllegalArgumentException("采访文档来源标签不符");
        }
        if (kind == null || refs.stream().distinct().count() != refs.size())
            throw new IllegalArgumentException("采访文档缺少唯一的来源声明");
        var basis = new InterviewSourceBasis(kind,refs);
        if (!basis.description().equals(version.publicSourceDescription()))
            throw new IllegalArgumentException("采访文档的来源说明不符");
        return basis;
    }

    record Speech(String requestId, String title, String body, long readDurationMillis,
                  InterviewSourceBasis sourceBasis) {
        Speech(WorldRoleSession.DialogueSpeakDraft draft, InterviewSourceBasis basis) {
            this(draft.requestId(),draft.title(),draft.body(),draft.readDurationMillis(),Objects.requireNonNull(basis));
        }
    }
}
