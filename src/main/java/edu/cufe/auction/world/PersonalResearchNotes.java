package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** 本人自愿记录的判断；每次读取均按当时的回忆与固定版本权限投影。 */
final class PersonalResearchNotes {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONTRACT = "可在任意决定的顶层另附privateNote，省略沿用、null撤回。新笔记替换本人上一份："
            + "{judgment:本人当前判断,reviewCondition:什么情况会让本人重新评估,evidence:[{docId,version}]}。"
            + "两个摘要各1至240字，仅引用下列本人仍有权查看且能回忆的1至3个不同固定版本。笔记不授权、不公开、不改变事实。";

    private PersonalResearchNotes() { }

    static void append(ObjectNode engineering, String person, DecisionRun decisions, CognitionRecall.Result recall,
                       java.util.function.Predicate<InformationModel.ReadObservation> visible, boolean revisions) {
        ObjectNode section = engineering.putObject("researchNotes");
        section.put("version", revisions ? "v2" : "v1").put("contract", CONTRACT);
        if (revisions) section.put("revisionContract",
                "若在修正当前判断，可在privateNote内加revises（当前actionId）和changeReason（修正原因）。"
                + "任意行动或等待可在顶层附basedOnNote，引用本次输入的当前判断。");
        var evidence = section.putArray("citableReadings");
        for (var item : recall.selected()) {
            var read = item.observation();
            if (visible.test(read)) evidence.addObject().put("docId", read.docId()).put("version", read.version());
        }
        if (decisions == null) return;
        String predecessor = null;
        var history = decisions.state().decisions();
        for (int i = history.size() - 1; i >= 0; i--) {
            var decision = history.get(i);
            if (!person.equals(decision.personPartyId()) || decision.status() != DecisionModel.Status.RECORDED) continue;
            var attempts = decision.attempts();
            var accepted = attempts.get(attempts.size() - 1);
            JsonNode note;
            try {
                note = JSON.readTree(accepted.replyBody()).get("privateNote");
            } catch (IOException invalid) {
                throw new IllegalStateException("已记录决定的回执无效", invalid);
            }
            if (note == null) continue;
            if (note.isNull() || !referencesAllowed(note.path("evidence"), evidence)) return;
            if (predecessor != null && !predecessor.equals(decision.actionId())) return;
            section.putObject(predecessor == null ? "current" : "previous")
                    .put("actionId", decision.actionId()).put("observedAt", decision.cutoffTimeMillis())
                    .set("note", note);
            if (!revisions || predecessor != null || !note.has("revises")) return;
            predecessor = note.get("revises").textValue();
        }
    }

    static void validateAndRemove(ObjectNode action, String frozenInput) {
        if (!action.has("privateNote") && !action.has("basedOnNote")) return;
        JsonNode section;
        try {
            section = JSON.readTree(frozenInput).at("/engineering/researchNotes");
        } catch (IOException invalid) {
            throw new IllegalArgumentException("无有效的笔记合同", invalid);
        }
        boolean revisions = "v2".equals(section.path("version").textValue());
        if (!revisions && !"v1".equals(section.path("version").textValue())) return;
        String currentId = section.path("current").path("actionId").textValue();
        if (revisions && action.has("basedOnNote")) {
            JsonNode basis = action.remove("basedOnNote");
            if (currentId == null || !currentId.equals(basis.textValue()))
                throw new IllegalArgumentException("只能引用本次观察中本人当前判断的编号");
        }
        if (!action.has("privateNote")) return;
        JsonNode note = action.remove("privateNote");
        boolean revision = revisions && note.has("revises");
        if (revision && (currentId == null || !currentId.equals(note.path("revises").textValue())
                || !summary(note.path("changeReason"))))
            throw new IllegalArgumentException("修正须关联本人当前判断并写明原因");
        if (!note.isNull() && (!note.isObject() || note.size() != (revision ? 5 : 3)
                || !summary(note.path("judgment")) || !summary(note.path("reviewCondition"))
                || !referencesAllowed(note.path("evidence"), section.path("citableReadings"))))
            throw new IllegalArgumentException("私有笔记须引用本人可见、可回忆的固定版本");
    }

    private static boolean summary(JsonNode text) {
        return text.isTextual() && !text.textValue().isBlank() && text.textValue().length() <= 240;
    }

    private static boolean referencesAllowed(JsonNode references, JsonNode available) {
        if (!references.isArray() || references.isEmpty() || references.size() > 3) return false;
        Set<JsonNode> seen = new HashSet<>();
        for (var ref : references) {
            if (!ref.isObject() || ref.size() != 2 || !ref.path("docId").isTextual()
                    || !ref.path("version").isIntegralNumber() || !ref.path("version").canConvertToLong()
                    || !seen.add(ref)) return false;
            boolean found = false;
            for (var allowed : available) {
                if (allowed.path("docId").equals(ref.get("docId"))
                        && allowed.path("version").asLong() == ref.get("version").longValue()) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
