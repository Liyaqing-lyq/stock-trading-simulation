package edu.cufe.auction.world;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 自愿试刊按接受时的真实名单派送。公开原件保留原受众，授权附页仅交当时的订户。 */
final class MediaSubscriptions {
    static final String JOIN = "media.subscription.join", LEAVE = "media.subscription.leave",
            RELEASE = "media.subscription.releaseTurn";
    static final String JOIN_TASK = "MEDIA_SUBSCRIBE_V1", LEAVE_TASK = "MEDIA_UNSUBSCRIBE_V1",
            RELEASE_TASK = "MEDIA_RELEASE_OWN_TURN_V1";
    static final String PREFIX = "media-delivery:";
    static final long DURATION = 1000L;
    static final Set<String> TASKS = Set.of(JOIN_TASK, LEAVE_TASK, RELEASE_TASK);

    private MediaSubscriptions() { }

    record Target(String target) {
        Target {
            if (target == null || target.isBlank() || target.length() > 2048)
                throw new IllegalArgumentException("请选择本人清单中的媒体或话轮标识");
        }
    }

    static String task(String action) {
        return switch (action) {
            case "JOIN", JOIN -> JOIN_TASK;
            case "LEAVE", LEAVE -> LEAVE_TASK;
            case "RELEASE_TURN", RELEASE -> RELEASE_TASK;
            default -> throw new IllegalArgumentException("未知订阅动作");
        };
    }

    static boolean active(RoleTradingRun trading, String person, String organization, long cutoffPosition) {
        RoleTradingRun.Report latest = null;
        for (var row : trading.reports().values()) {
            if (row.accepted() && (JOIN_TASK.equals(row.kind()) || LEAVE_TASK.equals(row.kind()))
                    && person.equals(row.personPartyId()) && organization.equals(row.subjectId())
                    && row.position() <= cutoffPosition && (latest == null || latest.position() < row.position()))
                latest = row;
        }
        return latest != null && JOIN_TASK.equals(latest.kind());
    }

    private record Turn(String key, String organization, InformationModel.Document source, long version) { }

    static boolean canRelease(InterviewRun interviews, InformationModel.State info, RoleTradingRun trading, String person) {
        return !ownTurns(interviews, info, trading, person).isEmpty();
    }

    static boolean listed(MediaRun media, InterviewRun interviews, InformationModel.State info, RoleTradingRun trading,
                          String person, String kind, String target) {
        return RELEASE_TASK.equals(kind)
                ? ownTurns(interviews, info, trading, person).stream().anyMatch(t -> t.key().equals(target))
                : media.config().policyOf(target) != null;
    }

    private static List<Turn> ownTurns(InterviewRun interviews, InformationModel.State info,
                                        RoleTradingRun trading, String person) {
        if (interviews == null || interviews.dialogues() == null
                || interviews.dialogues().values().stream().noneMatch(d -> d.turns().stream()
                        .anyMatch(t -> person.equals(t.speakerPartyId())))) return List.of();
        Set<String> released = new HashSet<>();
        for (var r : trading.reports().values()) {
            if (RELEASE_TASK.equals(r.kind()) && r.accepted() && person.equals(r.personPartyId()))
                released.add(r.subjectId());
        }
        var result = new ArrayList<Turn>();
        for (var org : interviews.states()) {
            String id = org.policy().mediaOrganizationPartyId();
            var dialogue = interviews.dialogues().get(id);
            if (dialogue == null) continue;
            var ended = new HashSet<String>();
            for (var session : org.sessions()) if (session.close() != null) ended.add(session.sessionId());
            for (var t : dialogue.turns()) {
                if (!person.equals(t.speakerPartyId()) || !ended.contains(t.sessionId()) || released.contains(t.docId())) continue;
                var doc = document(info, t.docId());
                if (doc != null && person.equals(doc.publisherPartyId()) && doc.audience().kind() == InformationModel.AudienceKind.DIRECT
                        && doc.at(t.docVersion()) != null)
                    result.add(new Turn(t.docId(), id, doc, t.docVersion()));
            }
        }
        return result;
    }

    private static InformationModel.Document document(InformationModel.State info, String id) {
        for (var d : info.documents()) if (d.docId().equals(id)) return d;
        return null;
    }

    record Result(boolean accepted, String reason, InformationModel.State information) { }

    static Result act(MediaRun media, InterviewRun interviews, RoleTradingRun trading, InformationModel.State info,
                      String actor, String kind, String target, long beforePosition, long now) {
        if (!info.persons().contains(actor)) return new Result(false, "NOT_AUTHORIZED", info);
        if (!RELEASE_TASK.equals(kind)) {
            if (media.config().policyOf(target) == null) return new Result(false, "UNKNOWN_OUTLET", info);
            if (active(trading, actor, target, beforePosition) == JOIN_TASK.equals(kind))
                return new Result(false, JOIN_TASK.equals(kind) ? "ALREADY_SUBSCRIBED" : "NOT_SUBSCRIBED", info);
            return new Result(true, JOIN_TASK.equals(kind) ? "SUBSCRIBED" : "UNSUBSCRIBED", info);
        }
        for (Turn turn : ownTurns(interviews, info, trading, actor)) {
            if (turn.key().equals(target)) {
                return new Result(true, "TURN_RELEASED", deliver(info, trading, beforePosition, turn.organization(),
                        turn.source(), turn.version(), now, true));
            }
        }
        return new Result(false, "OWN_UNRELEASED_CLOSED_TURN_REQUIRED", info);
    }

    static InformationModel.State publicIssue(InformationModel.State info, RoleTradingRun trading, long position,
                                              String organization, MediaModel.Story story, long now) {
        var source = Objects.requireNonNull(document(info, story.publicDocId()));
        return deliver(info, trading, position, organization, source, story.publicVersion(), now, false);
    }

    private static InformationModel.State deliver(InformationModel.State info, RoleTradingRun trading, long position,
            String organization, InformationModel.Document source, long version, long now, boolean supplement) {
        var original = source.at(version);
        String kind = supplement ? "INTERVIEW_SUPPLEMENT" : "PUBLIC_NOTICE";
        String body = supplement ? original.body() : "本社已刊发《" + original.title() + "》。公开原件可在个人资料目录按标识和版本阅读。";
        String prefix = supplement ? "订阅采访附页 · " : "订阅到刊 · ";
        var provenance = List.of("organization=" + organization, "kind=" + kind, "source=" + source.docId(), "version=" + version);
        var latest = new TreeMap<String,RoleTradingRun.Report>();
        for (var r : trading.reports().values())
            if (r.accepted() && (JOIN_TASK.equals(r.kind()) || LEAVE_TASK.equals(r.kind()))
                    && organization.equals(r.subjectId()) && r.position() <= position)
                latest.merge(r.personPartyId(), r, (a,b) -> a.position() > b.position() ? a : b);
        for (var r : latest.values()) {
            if (!JOIN_TASK.equals(r.kind())) continue;
            String reader = r.personPartyId();
            String id = PREFIX + digest(List.of(kind, organization, source.docId(), String.valueOf(version), reader));
            info = InformationModel.publish(info, id, source.publisherPartyId(), InformationModel.Audience.direct(reader),
                    new InformationModel.VersionDraft(prefix + original.title(), body,
                            supplement ? "发言者本人授权本社向当期订户署名送达" : "所订媒体公开发刊通知", now,
                            supplement ? original.readDurationMillis() : 1L, provenance));
        }
        return info;
    }

    private static String digest(List<String> parts) {
        try {
            var d = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                d.update((p.length() + ":").getBytes(StandardCharsets.US_ASCII));
                d.update(p.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** 原订阅信封投影：仅本人开局或此后实际获送的通知及署名附页，不呈现正文。 */
    static List<Map<String,Object>> deliveries(InformationModel.State info,String person) {
        List<Map<String,Object>> deliveries = new ArrayList<>();
        for (var doc : info.documents()) {
            if (!doc.docId().startsWith(PREFIX) || !InformationModel.Audience.direct(person).equals(doc.audience())) continue;
            var v = doc.at(1);
            var tags = new TreeMap<String,String>();
            for (String tag : v.internalSourceIds()) {
                int split = tag.indexOf('=');
                if (split > 0) tags.put(tag.substring(0,split),tag.substring(split+1));
            }
            boolean notice = "PUBLIC_NOTICE".equals(tags.get("kind"));
            Map<String,Object> item = new TreeMap<>(Map.of("organization",tags.get("organization"),"kind",tags.get("kind"),
                    "docId",notice ? tags.get("source") : doc.docId(),"version",notice ? Long.parseLong(tags.get("version")) : 1L,
                    "title",v.title(),"deliveredAt",v.publishedAt()));
            if (!notice) item.put("speakerPartyId",doc.publisherPartyId());
            deliveries.add(Map.copyOf(item));
        }
        deliveries.sort(Comparator.<Map<String,Object>>comparingLong(r -> (Long) r.get("deliveredAt")).reversed()
                .thenComparing(r -> (String) r.get("docId")));
        return List.copyOf(deliveries);
    }

    static Map<String,Object> view(MediaRun media, InterviewRun interviews, RoleTradingRun trading, InformationModel.State info,
                                   String person, long position) {
        List<Map<String,Object>> offers = new ArrayList<>(), turns = new ArrayList<>();
        for (var policy : media.config().policies()) {
            String org = policy.mediaOrganizationPartyId();
            offers.add(Map.of("organization", org, "subscribed", active(trading, person, org, position)));
        }
        for (var turn : ownTurns(interviews,info,trading,person))
            turns.add(Map.of("turnId",turn.key(),"organization",turn.organization(),"title",turn.source().at(turn.version()).title()));
        return Map.of("offers",List.copyOf(offers),"deliveries",deliveries(info,person),"releasableTurns",List.copyOf(turns),
                "terms","免费试刊。订退与本人授权各用1000模拟毫秒。生效后送达本社新刊标题和公开读址，或另送发言者本人授权的采访原话。退订停止以后送达，已送达不撤回。阅读正文另计工时。");
    }
}
