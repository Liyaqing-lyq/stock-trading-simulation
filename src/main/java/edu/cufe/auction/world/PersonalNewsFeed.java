package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 用本人可回忆的阅读、最近一次已完成的检索和现职匹配已公开标题；不读取候选正文。 */
final class PersonalNewsFeed {
    private final Map<String, Long> readVersions = new HashMap<>();
    private final Set<String> knownPublishers = new HashSet<>();
    private final Set<String> officeTerms = new HashSet<>();
    private final Set<String> subscribedArticles = new HashSet<>();
    private final List<String> searchTerms;

    PersonalNewsFeed(RoleView reader, OpeningRegistry registry, RoleTradingRun trading,
                     List<MediaModel.State> outlets, long position, Set<String> directorCompanies) {
        Map<String, String> authors = new HashMap<>();
        for (var title : reader.catalog()) authors.put(title.docId(), title.publisherPartyId());
        for (var reading : reader.observations()) {
            String doc = reading.ref().docId();
            readVersions.merge(doc, reading.ref().version(), Math::max);
            if (authors.containsKey(doc)) knownPublishers.add(authors.get(doc));
        }
        Set<String> offices = new HashSet<>(directorCompanies);
        for (var appointment : reader.appointments()) offices.add(appointment.organizationPartyId());
        for (var party : registry.parties()) if (offices.contains(party.partyId())) {
            officeTerms.add(party.partyId().toLowerCase(Locale.ROOT));
            officeTerms.add(party.name().toLowerCase(Locale.ROOT));
        }
        for (var security : registry.securities())
            if (offices.contains(security.issuerOrganizationId())) officeTerms.add(security.symbol().toLowerCase(Locale.ROOT));
        var search = PersonalLibrarySearch.latestCompleted(trading, reader.personPartyId());
        searchTerms = search == null || search.subjectId().isBlank() ? List.of() : List.of(search.subjectId().strip().toLowerCase(Locale.ROOT).split("\\s+"));
        for (var outlet : outlets) {
            if (outlet.stories().isEmpty() || !MediaSubscriptions.active(trading, reader.personPartyId(),
                    outlet.policy().mediaOrganizationPartyId(), position)) continue;
            for (var story : outlet.stories()) if (story.publicVersion() > 0) subscribedArticles.add(story.publicDocId());
        }
    }

    private enum Match {
        UPDATED("本人读过旧版，已有新版"), SUBSCRIBED("本人订阅了刊发此稿的媒体"),
        SEARCH("标题匹配本人最近的检索"), OFFICE("标题涉及本人任职机构"),
        KNOWN("本人读过该发布者的资料"), RECENT("按公开时间排列"), READ("本人已读这一版");
        final String reason;
        Match(String reason) { this.reason = reason; }
    }

    private Match match(PublicationRun.FeedEntry entry) {
        Long read = readVersions.get(entry.docId());
        if (read != null) return read == entry.version() ? Match.READ : Match.UPDATED;
        if (entry.kind() == PublicationRun.FeedKind.MEDIA_STORY && subscribedArticles.contains(entry.docId()))
            return Match.SUBSCRIBED;
        String title = entry.title().toLowerCase(Locale.ROOT);
        if (!searchTerms.isEmpty() && searchTerms.stream().allMatch(title::contains)) return Match.SEARCH;
        if (officeTerms.stream().anyMatch(title::contains)) return Match.OFFICE;
        return knownPublishers.contains(entry.publisherPartyId()) ? Match.KNOWN : Match.RECENT;
    }

    private record Candidate(PublicationRun.FeedEntry entry, Match match) {}

    List<PublicationRun.FeedEntry> rank(List<PublicationRun.FeedEntry> chronological, int limit, int explorationSlot) {
        var candidates = new ArrayList<Candidate>();
        Candidate unfamiliar = null;
        for (var entry : chronological) {
            var candidate = new Candidate(entry, match(entry));
            candidates.add(candidate);
            if (unfamiliar == null && isUnfamiliar(entry)) unfamiliar = candidate;
        }
        // 输入已按公开时间与文号排好；稳定排序使同类始终保留该顺序。
        candidates.sort(Comparator.comparing(Candidate::match));
        int visible = Math.min(limit, candidates.size());
        int slot = Math.min(explorationSlot, visible);
        boolean reserved = false;
        if (slot > 0 && unfamiliar != null
                && candidates.subList(0, slot).stream().noneMatch(c -> isUnfamiliar(c.entry()))) {
            int index = candidates.indexOf(unfamiliar);
            candidates.remove(index);
            candidates.add(slot - 1, unfamiliar);
            reserved = true;
        }
        var selected = new ArrayList<PublicationRun.FeedEntry>(visible);
        for (int i = 0; i < visible; i++) {
            var candidate = candidates.get(i);
            var entry = candidate.entry();
            String reason = reserved && i + 1 == slot ? "发现本人未读过的发布者" : candidate.match().reason;
            selected.add(new PublicationRun.FeedEntry(i + 1, entry.docId(), entry.version(), entry.title(),
                    entry.publisherPartyId(), entry.publishedAt(), entry.kind(), reason));
        }
        return List.copyOf(selected);
    }

    private boolean isUnfamiliar(PublicationRun.FeedEntry entry) {
        return !knownPublishers.isEmpty() && !knownPublishers.contains(entry.publisherPartyId())
                && !readVersions.containsKey(entry.docId());
    }
}
