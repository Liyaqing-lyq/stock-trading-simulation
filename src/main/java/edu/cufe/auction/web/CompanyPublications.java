package edu.cufe.auction.web;

import edu.cufe.auction.world.*;
import java.util.*;

/** 管理员公司页只沿登记的披露、资料版本及本人阅读证据连接记录。 */
final class CompanyPublications {
    private CompanyPublications() { }

    private record Report(String company, PeriodicModel.Release release, String publicDoc,
                          InformationModel.DocumentVersion version) { }

    private static List<Report> reports(WorldRuntime world, InformationModel.State information,
                                        Collection<String> companies) {
        List<Report> result = new ArrayList<>();
        for (String company : companies) {
            world.periodicOf(company).ifPresent(periods -> {
                for (var release : periods.releases()) {
                    String id = world.periodicPublicDocumentId(company, release.period());
                    var version = WorldWebHost.publicVersionOf(information, id, release);
                    if (version != null) result.add(new Report(company, release, id, version));
                }
            });
        }
        result.sort(Comparator.comparingLong((Report r) -> r.version().publishedAt()).reversed()
                .thenComparing(Report::publicDoc));
        return result;
    }

    /** 个人可见公开版本的目录及本人已读凭据，正文只取本人仍可回忆的版本。 */
    static List<Map<String, Object>> personalReports(WorldRuntime world, WorldRuntime.Snapshot snapshot,
                                                      RoleView view) {
        var info = snapshot.information().map(InformationRun::information).orElse(null);
        if (info == null) return List.of();
        Set<String> visible = new HashSet<>();
        for (var entry : view.catalog()) visible.add(entry.docId());
        Set<String> companies = new TreeSet<>();
        world.companyGovernance().ifPresent(extra -> companies.addAll(extra.companies()));
        snapshot.engineering().map(EngineeringRun::periodic).ifPresent(periods ->
                periods.companies().forEach(p -> companies.add(p.companyPartyId())));
        record Version(String docId, long version) { }
        Map<Version, Long> lastRead = new HashMap<>();
        for (var read : info.closed()) {
            if (read.personPartyId().equals(view.personPartyId()) && visible.contains(read.docId())
                    && read.reason() == InformationModel.CloseReason.COMPLETED
                    && read.closedAt() <= view.timeMillis()) {
                lastRead.merge(new Version(read.docId(), read.version()), read.closedAt(), Math::max);
            }
        }
        Map<Version, String> remembered = new HashMap<>();
        for (var read : view.observations()) {
            remembered.put(new Version(read.ref().docId(), read.ref().version()), read.body());
        }
        var registry = world.openingRegistry().orElse(null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var report : reports(world, info, companies)) {
            if (!visible.contains(report.publicDoc())) continue;
            var version = report.version();
            var key = new Version(report.publicDoc(), version.version());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("companyId", report.company());
            row.put("companyName", WorldWebHost.displayName(registry, report.company()));
            row.put("period", report.release().period());
            row.put("docId", report.publicDoc());
            row.put("version", version.version());
            row.put("title", version.title());
            row.put("publisherPersonId", report.release().publisherPersonId());
            row.put("publisherName", WorldWebHost.displayName(registry, report.release().publisherPersonId()));
            row.put("publishedAt", Long.toString(version.publishedAt()));
            row.put("readDurationMillis", Long.toString(version.readDurationMillis()));
            if (lastRead.containsKey(key)) row.put("lastReadAt", Long.toString(lastRead.get(key)));
            if (remembered.containsKey(key)) row.put("body", remembered.get(key));
            rows.add(row);
        }
        return rows;
    }

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
        record Source(String company,String kind,String doc,long version,String publisher) { }
        List<Source> sources = new ArrayList<>();
        if (beliefs != null) {
            for (var fact : beliefs.factClaims()) sources.add(new Source(fact.companyId(),"订单公告",fact.docId(),fact.version(),null));
            for (var claim : beliefs.sourceClaims()) sources.add(new Source(claim.companyId(),"公开预测",claim.docId(),claim.version(),null));
        }
        for (var report : reports(world, information, companies)) {
            sources.add(new Source(report.company(), "定期披露", report.publicDoc(),
                    report.version().version(), report.release().publisherPersonId()));
        }
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
                    "docId",doc.docId(),"version",source.version(),"publisher",source.publisher() == null ? doc.publisherPartyId() : source.publisher(),
                    "time",content.publishedAt(),"title",content.title(),"body",content.body(),"reads",reads,"beliefs",linked));
        }
        for (var rows : result.values()) rows.sort(Comparator.<Map<String,Object>>comparingLong(r->(long)r.get("time")).reversed()
                .thenComparing(r->(String)r.get("docId")));
        return result;
    }
}
