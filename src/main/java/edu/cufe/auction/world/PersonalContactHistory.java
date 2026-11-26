package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** 本人对具名交往对象的可修订判断，依据仍可引用的亲历记录。 */
final class PersonalContactHistory {
    static final String POLICY = "personal-contact-history", VERSION = "answered-contacts-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private PersonalContactHistory() { }

    static void append(ObjectNode engineering, String person, DecisionRun decisions) {
        var section = engineering.putObject("contactHistory").put("version", VERSION);
        section.put("contract", "可在决定顶层附 contactAssessment：{personPartyId,docId,version,judgment}。从 namedReadings 选择本人仍能引用的对方原话，写下你对以后何时找此人的判断（1至240字）。同人新评语替换旧评语，judgment:null 撤回。私信或采访此人可在顶层附 basedOnContactAssessment 引用该评语的 actionId，实际联系仍需渠道与工时。");
        var remembered = section.putArray("assessments");
        var readings = engineering.at("/researchNotes/citableReadings");
        if (decisions == null || readings.isEmpty()) return;
        var history = decisions.state().decisions();
        Set<String> seen = new HashSet<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            var decision = history.get(i);
            if (!person.equals(decision.personPartyId()) || decision.status() != DecisionModel.Status.RECORDED) continue;
            try {
                var assessment = JSON.readTree(decision.lastAttempt().replyBody()).get("contactAssessment");
                if (assessment == null || !seen.add(assessment.required("personPartyId").textValue())) continue;
                if (assessment.path("judgment").isTextual() && contains(readings, assessment.path("docId").asText(), assessment.path("version").asLong(), null))
                    remembered.addObject().put("actionId", decision.actionId()).put("observedAt", decision.cutoffTimeMillis())
                            .set("assessment", assessment);
            } catch (IOException invalid) {
                throw new IllegalStateException("已记录决定的回执无效", invalid);
            }
        }
    }

    /** 只从已经按本人裁剪的来信、采访轮次、具名预测中关联实际发言者。 */
    static void project(ObjectNode input) {
        if (!(input.at("/engineering/contactHistory") instanceof ObjectNode section)) return;
        var named = section.putArray("namedReadings");
        var readings = input.at("/engineering/researchNotes/citableReadings");
        String self = input.required("person").textValue();
        for (var mail : input.at("/social/inbox"))
            add(named, readings, self, mail.path("from").asText(), mail.path("docId").asText(), mail.path("version").asLong(), "DIRECT_MESSAGE");
        for (var session : input.at("/interview/sessions"))
            for (var turn : session.path("turns"))
                add(named, readings, self, turn.path("speakerPartyId").asText(), turn.path("docId").asText(), turn.path("docVersion").asLong(), "INTERVIEW_TURN");
        for (var claim : input.at("/cognition/claims"))
            if ("SOURCE".equals(claim.path("kind").asText()))
                add(named, readings, self, claim.path("authorPartyId").asText(), claim.path("docId").asText(), claim.path("version").asLong(), "NAMED_FORECAST");
    }

    private static void add(ArrayNode named, JsonNode readings, String self, String peer, String doc, long version, String kind) {
        if (!peer.isBlank() && !self.equals(peer) && contains(readings, doc, version, null) && !contains(named, doc, version, peer))
            named.addObject().put("personPartyId", peer).put("docId", doc).put("version", version).put("kind", kind);
    }

    private static boolean contains(JsonNode rows, String doc, long version, String peer) {
        for (var row : rows)
            if (doc.equals(row.path("docId").asText()) && version == row.path("version").asLong()
                    && (peer == null || peer.equals(row.path("personPartyId").textValue()))) return true;
        return false;
    }

    static void validateAndRemove(ObjectNode action, String frozenInput) {
        if (!action.has("contactAssessment") && !action.has("basedOnContactAssessment")) return;
        JsonNode section;
        try { section = JSON.readTree(frozenInput).at("/engineering/contactHistory"); }
        catch (IOException invalid) { throw new IllegalArgumentException("联系经历输入无效", invalid); }
        if (!VERSION.equals(section.path("version").asText())) return;
        if (action.has("basedOnContactAssessment")) {
            var basis = action.remove("basedOnContactAssessment");
            String tool = action.path("tool").asText();
            var target = action.at("/args/toPartyId");
            boolean matches = false;
            for (var prior : section.path("assessments")) {
                if (basis.equals(prior.get("actionId")) && target.equals(prior.at("/assessment/personPartyId"))) matches = true;
            }
            if (!(RoleTradingView.TOOL_SOCIAL_SEND.equals(tool) || RoleTradingView.TOOL_INTERVIEW_INVITE.equals(tool)) || !matches)
                throw new IllegalArgumentException("私信或采访只能引用本人对本次联系对象的现行评语");
        }
        if (!action.has("contactAssessment")) return;
        var note = action.remove("contactAssessment");
        var peer = note.path("personPartyId");
        var doc = note.path("docId");
        var version = note.path("version");
        var judgment = note.path("judgment");
        if (!note.isObject() || note.size() != 4 || !peer.isTextual() || !doc.isTextual()
                || !version.isIntegralNumber() || !version.canConvertToLong()
                || !contains(section.path("namedReadings"), doc.textValue(), version.longValue(), peer.textValue())
                || !(judgment.isNull() || judgment.isTextual() && !judgment.textValue().isBlank() && judgment.textValue().length() <= 240))
            throw new IllegalArgumentException("联系人评语须归给本人读过该固定版本的实际发言者");
    }
}
