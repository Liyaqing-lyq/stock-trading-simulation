package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.CompanyOpeningDocuments;

import java.util.List;
import java.util.Optional;

/** 已登记发行人的开局章程只认实际发表的首版；个人署发、读权和原始资料不受分组影响。 */
record IssuerCharterOrigins(MediaModel.SourceOrigins previous, OpeningRegistry registry,
                           InformationRun publications) implements MediaModel.SourceOrigins {
    @Override public boolean registeredSourceGroups() { return true; }

    @Override public boolean covers(String docId) { return previous.covers(docId); }

    @Override public Optional<List<MediaModel.Ref>> originsOf(InformationModel.State information,
            String docId, long version) {
        return previous.originsOf(information, docId, version);
    }

    @Override public String sourceGroup(InformationModel.Document document, long version) {
        if (!CompanyOpeningDocuments.isCharterDocument(document.docId())) return previous.sourceGroup(document, version);
        String issuer = CompanyOpeningDocuments.registeredIssuer(registry, document.docId());
        if (version == 1 && issuer != null) {
            var entry = publications.report("opening-note-task:" + issuer);
            if (entry != null && entry.accepted() && PendingTask.INFORMATION_PUBLISH_V1.equals(entry.kind())
                    && entry.commandId().equals("opening-note:" + issuer) && entry.subjectId().equals(document.docId())
                    && entry.timeMillis() == document.at(1).publishedAt()) {
                return "issuer:" + issuer;
            }
        }
        // 保留文号中的仿件与事后追加的普通版本没有独立的开局机构凭证。
        return null;
    }
}
