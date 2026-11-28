package edu.cufe.auction.world;

import java.util.List;
import java.util.Optional;

/** 分析可信采访话轮及实际送达的订阅信封。根文号只用于内部谱系核算。 */
final class InterviewSourceOrigins implements MediaModel.SourceOrigins {
    private final MediaModel.SourceOrigins previous;
    private final InterviewRun interviews;

    private InterviewSourceOrigins(MediaModel.SourceOrigins previous, InterviewRun interviews) {
        this.previous = previous;
        this.interviews = interviews;
    }

    static MediaModel.SourceOrigins wrap(MediaModel.SourceOrigins previous, InterviewRun interviews) {
        return new InterviewSourceOrigins(previous,interviews);
    }

    @Override public boolean registeredSourceGroups() { return previous.registeredSourceGroups(); }

    @Override public boolean covers(String id) {
        return id.startsWith(InterviewDialogue.DOC_PREFIX) || id.startsWith(MediaSubscriptions.PREFIX) || previous.covers(id);
    }

    @Override public Optional<List<MediaModel.Ref>> originsOf(InformationModel.State info,String id,long version) {
        if (!id.startsWith(InterviewDialogue.DOC_PREFIX) && !id.startsWith(MediaSubscriptions.PREFIX))
            return previous.originsOf(info,id,version);
        var document = document(info,id);
        if (document == null || version != 1 || document.at(version) == null) return Optional.empty();
        if (id.startsWith(MediaSubscriptions.PREFIX)) return deliverySource(info,document);
        if (!boundTurn(document,null)) return Optional.empty();
        return Optional.of(InterviewSourceBasis.recorded(document.at(version)).sources());
    }

    @Override public String sourceGroup(InformationModel.Document doc,long version) {
        if (doc.docId().startsWith(MediaSubscriptions.PREFIX)) return null; // 信封自身不能成为独立佐证
        if (!doc.docId().startsWith(InterviewDialogue.DOC_PREFIX)) return previous.sourceGroup(doc,version);
        if (version != 1 || doc.at(version) == null || !boundTurn(doc,null)) return null;
        return InterviewSourceBasis.recorded(doc.at(version)).kind() == InterviewSourceBasis.Kind.DIRECT_OBSERVATION
                ? previous.sourceGroup(doc,version) : null;
    }

    private boolean boundTurn(InformationModel.Document doc,String organization) {
        if (interviews == null || interviews.dialogues() == null || doc.audience().kind() != InformationModel.AudienceKind.DIRECT)
            return false;
        return interviews.dialogues().entrySet().stream().filter(e -> organization == null || organization.equals(e.getKey()))
                .flatMap(e -> e.getValue().turns().stream())
                .anyMatch(t -> t.docId().equals(doc.docId()) && t.speakerPartyId().equals(doc.publisherPartyId())
                        && t.docId().equals(InterviewDialogue.turnDocId(t.sessionId(),t.sequence())));
    }

    private static InformationModel.Document document(InformationModel.State info,String id) {
        return info.documents().stream().filter(d -> d.docId().equals(id)).findFirst().orElse(null);
    }

    private Optional<List<MediaModel.Ref>> deliverySource(InformationModel.State info,InformationModel.Document delivery) {
        if (delivery.audience().kind() != InformationModel.AudienceKind.DIRECT) return Optional.empty();
        var v = delivery.at(1);
        var tags = new java.util.HashMap<String,String>();
        for (String tag : v.internalSourceIds()) {
            int split = tag.indexOf('=');
            if (split < 1 || tags.put(tag.substring(0,split),tag.substring(split+1)) != null) return Optional.empty();
        }
        if (!tags.keySet().equals(java.util.Set.of("kind","source","version","organization"))) return Optional.empty();
        String kind = tags.get("kind"), id = tags.get("source"), org = tags.get("organization");
        long version = Long.parseLong(tags.get("version"));
        var original = document(info,id);
        if (original == null || original.at(version) == null || !original.publisherPartyId().equals(delivery.publisherPartyId())
                || original.at(version).publishedAt() > v.publishedAt()
                || !MediaSubscriptions.deliveryId(kind,org,id,version,delivery.audience().partyId()).equals(delivery.docId()))
            return Optional.empty();
        if ("INTERVIEW_SUPPLEMENT".equals(kind)) {
            if (!boundTurn(original,org) || version != 1 || !v.body().equals(original.at(version).body())) return Optional.empty();
        } else if (!"PUBLIC_NOTICE".equals(kind) || !id.startsWith(MediaModel.PUBLIC_PREFIX)
                || original.audience().kind() != InformationModel.AudienceKind.PUBLIC) return Optional.empty();
        return Optional.of(List.of(new MediaModel.Ref(id,version)));
    }
}
