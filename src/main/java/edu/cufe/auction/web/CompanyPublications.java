package edu.cufe.auction.web;

import edu.cufe.auction.world.*;
import java.util.*;

/** 管理员公司页只沿登记的披露、资料版本及本人阅读证据连接记录。 */
final class CompanyPublications {
    private CompanyPublications() { }

    static Map<String,List<Map<String,Object>>> of(WorldRuntime world, InformationModel.State information,
                                                   Collection<String> companies) {
        Map<String,List<Map<String,Object>>> result = new HashMap<>();
        if (information == null) return result;
        var docs = new HashMap<String,InformationModel.Document>();
        for (var doc : information.documents())
            if (doc.audience().kind() == InformationModel.AudienceKind.PUBLIC) docs.put(doc.docId(),doc);
        var byDocument = new HashMap<String,List<InformationModel.ReadObservation>>();
        for (var read : information.observations())
            if (docs.containsKey(read.docId())) byDocument.computeIfAbsent(read.docId(),k->new ArrayList<>()).add(read);
        var beliefs = world.beliefRun().orElse(null);
        Map<String,List<BeliefModel.Belief>> byEvidence = new HashMap<>();
        if (beliefs != null) {
            for (var belief : beliefs.beliefs().beliefs())
                for (var doc : belief.evidence().stream().map(RoleView.ReadingRef::docId).distinct().toList())
                    byEvidence.computeIfAbsent(doc,k->new ArrayList<>()).add(belief);
        }
        record Source(String company,String kind,String doc,long version) { }
        List<Source> sources = new ArrayList<>();
        if (beliefs != null) {
            for (var fact : beliefs.factClaims()) sources.add(new Source(fact.companyId(),"订单公告",fact.docId(),fact.version()));
            for (var claim : beliefs.sourceClaims()) sources.add(new Source(claim.companyId(),"公开预测",claim.docId(),claim.version()));
        }
        for (String company : companies)
            world.periodicOf(company).ifPresent(periods -> periods.releases().forEach(release ->
                sources.add(new Source(company,"定期披露",release.docId(),release.version()))));
        for (var source : sources) {
            var doc = docs.get(source.doc());
            if (doc == null) continue;
            var content = doc.at(source.version());
            if (content == null) continue;
            var reads = byDocument.getOrDefault(doc.docId(),List.of()).stream().filter(r->r.version()==source.version())
                    .map(r->Map.of("person",r.personPartyId(),"at",Long.toString(r.completedAt()))).toList();
            var linked = byEvidence.getOrDefault(doc.docId(),List.of()).stream().filter(b->b.companyId().equals(source.company())
                    && b.evidence().stream().anyMatch(ref->ref.docId().equals(doc.docId()) && ref.version()==source.version()))
                    .map(b->Map.of("person",b.personPartyId(),"id",b.beliefId(),"at",Long.toString(b.formedAtMillis()),"rationale",Objects.toString(b.rationale(),""))).toList();
            result.computeIfAbsent(source.company(),k->new ArrayList<>()).add(Map.of("kind",source.kind(),
                    "docId",doc.docId(),"version",source.version(),"publisher",doc.publisherPartyId(),
                    "time",content.publishedAt(),"title",content.title(),"body",content.body(),"reads",reads,"beliefs",linked));
        }
        for (var rows : result.values()) rows.sort(Comparator.<Map<String,Object>>comparingLong(r->(long)r.get("time")).reversed()
                .thenComparing(r->(String)r.get("docId")));
        return result;
    }
}
