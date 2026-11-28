package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 本人信箱的有限首页和按原权限查询的信封，查阅信封不会读取正文。 */
final class SocialMessageHistory {
    private static final Comparator<SocialMessageModel.EnvelopeView> RECEIVED =
            Comparator.comparingLong(SocialMessageModel.EnvelopeView::deliveredAt).reversed()
                    .thenComparing(SocialMessageModel.EnvelopeView::messageId);
    private static final Comparator<SocialMessageModel.OutboxView> SENT =
            Comparator.comparingLong(SocialMessageModel.OutboxView::sentAt).reversed()
                    .thenComparing(SocialMessageModel.OutboxView::messageId);

    private SocialMessageHistory() { }

    static ParticipantHistory.Selection select(RoleTradingView observed, ParticipantHistory.Request request) {
        Map<String,Long> times = new HashMap<>();
        if (observed.social() != null) {
            for (var mail : observed.social().inbox()) times.put(mail.messageId(), mail.deliveredAt());
            for (var mail : observed.social().outbox()) times.merge(mail.messageId(), mail.sentAt(), Math::max);
        }
        var ordered = times.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).toList();
        return ParticipantHistory.selectIds(request, ordered);
    }

    static Set<String> preview(ObjectNode input, ObjectNode output, RoleTradingView view) {
        var mail = view.social();
        Set<String> selected = new HashSet<>();
        var last = input.at("/engineering/historyLookup/lastResult");
        if ("messages".equals(last.path("section").asText()))
            for (JsonNode id : last.path("ids")) selected.add(id.asText());

        mail.inbox().stream().sorted(RECEIVED).limit(ParticipantHistory.PAGE_SIZE)
                .forEach(row -> selected.add(row.messageId()));
        mail.inbox().stream().filter(row -> !mail.read(row.messageId())).max(RECEIVED)
                .ifPresent(row -> selected.add(row.messageId()));
        mail.outbox().stream().sorted(SENT).limit(ParticipantHistory.PAGE_SIZE)
                .forEach(row -> selected.add(row.messageId()));
        for (var row : mail.outbox()) {
            if (row.status() == SocialMessageModel.OutboxStatus.QUEUED) selected.add(row.messageId());
        }
        var remembered = view.profile().observations().stream().map(row -> row.ref().docId())
                .collect(Collectors.toSet());
        for (var row : view.profile().readings()) remembered.add(row.ref().docId());
        long unread = 0;
        for (var row : mail.inbox()) {
            if (remembered.contains(row.docId())) selected.add(row.messageId());
            if (!mail.read(row.messageId())) unread++;
        }
        output.putObject("mailbox").put("received", mail.inbox().size()).put("unread", unread)
                .put("sent", mail.outbox().size()).put("contract",
                        "收、发各列最近4封，另列最早未读、在读、仍能引用的来信和待投件。role.history.lookup 的 messages 按本人信封编号或分页查往来；正文需实际阅读。");
        return selected;
    }
}
