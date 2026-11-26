package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;

/** 工程实验档中，已到会董事可把单条非定向广场动态并入其临近的开票或自选复查。 */
final class BoardWaitBroadcast {
    private static final long MAX_WAIT_MILLIS = 30_000L;

    private BoardWaitBroadcast() { }

    static boolean defer(JsonNode previous, JsonNode facts, JsonNode input, long last, long review) {
        long now = input.path("time").asLong();
        if (previous == null || now < last || now >= review || review - last > MAX_WAIT_MILLIS) return false;
        JsonNode statement = input.at("/engineering/deliberation/recentDecisions/0");
        JsonNode objectives = input.at("/engineering/deliberation/objectives");
        JsonNode directors = input.at("/engineering/directorships");
        if (!"WAIT".equals(statement.path("outcome").asText()) || !"role.wait".equals(statement.path("tool").asText())
                || statement.path("observedAt").asLong(-1) != last || objectives.size() != 1 || directors.size() != 1) return false;
        JsonNode director = directors.get(0);
        String company = director.path("companyPartyId").asText();
        if (!("director:" + company).equals(objectives.get(0).path("id").asText())
                || !objectives.get(0).path("id").equals(statement.at("/ownStatement/objective"))
                || !quietDirector(input, director, last, now)) return false;
        return onlyNewPlatformPost(previous, facts);
    }

    // 只在本人最近一次有效 WAIT 明确选择后合并；到期、自己或联系人的帖子、媒体成稿、更正等仍及时处理。
    static boolean deferChosenPosts(JsonNode previous, JsonNode facts, JsonNode input, long last, long review) {
        JsonNode decision = input.at("/engineering/deliberation/recentDecisions/0");
        long now = input.path("time").asLong();
        if (previous == null || now < last || now >= review
                || !"WAIT".equals(decision.path("outcome").asText())
                || !"role.wait".equals(decision.path("tool").asText())
                || decision.path("observedAt").asLong(-1) != last
                || decision.path("derivedReviewAt").asLong(-1) != review
                || !"ON_REVIEW".equals(decision.at("/ownStatement/publicPostWake").asText())) return false;

        JsonNode oldCatalog = previous.at("/profile/catalog"), catalog = facts.at("/profile/catalog");
        JsonNode oldFeed = previous.at("/groups/feed"), feed = facts.at("/groups/feed");
        if (!oldCatalog.isArray() || !catalog.isArray() || !oldFeed.isArray() || !feed.isArray()
                || catalog.size() <= oldCatalog.size() || feed.size() < oldFeed.size()) return false;
        var oldIds = new HashSet<String>();
        oldCatalog.forEach(row -> oldIds.add(row.path("docId").asText()));
        var contacts = new HashSet<String>();
        contacts.add(facts.path("person").asText());
        facts.at("/social/contacts").forEach(row -> contacts.add(row.path("targetPartyId").asText()));
        var added = new java.util.HashMap<String, JsonNode>();
        var restored = ((ObjectNode) facts).deepCopy();
        var retainedCatalog = restored.withObject("/profile").putArray("catalog");
        for (var row : catalog) {
            String id = row.path("docId").asText();
            if (oldIds.contains(id)) retainedCatalog.add(row);
            else added.put(id, row);
        }
        // 旧目录条目仍须一字不变，新目录条目须全部在可见 feed 内证明是陌生人的首版广场帖。
        int retained = 0;
        for (var row : feed) {
            JsonNode entry = added.remove(row.path("docId").asText());
            if (entry != null) {
                if (!"PLATFORM_POST".equals(row.path("kind").asText()) || row.path("version").asLong() != 1
                        || !row.path("version").equals(entry.path("version")) || !row.path("title").equals(entry.path("title"))
                        || row.path("publisher").asText().isEmpty() || contacts.contains(row.path("publisher").asText()))
                    return false;
            } else {
                if (retained >= oldFeed.size()) return false;
                var old = (ObjectNode) oldFeed.get(retained++).deepCopy();
                var current = (ObjectNode) row.deepCopy();
                old.remove("rank"); current.remove("rank");
                if (!old.equals(current)) return false;
            }
        }
        if (!added.isEmpty()) return false;
        restored.withObject("/groups").set("feed", oldFeed);
        return previous.equals(restored);
    }

    private static boolean quietDirector(JsonNode input, JsonNode director, long last, long now) {
        // 交易账户、其他任职、已跟进的非会议资料或公开判断各有其自己的信息需求。
        for (String path : List.of("/appointments", "/profile/accounts", "/profile/holdings", "/profile/activeReadings",
                "/research", "/targets", "/children", "/cognition/beliefs", "/cognition/claims", "/cognition/sourceReliability",
                "/interview/invites", "/interview/sessions", "/groups/memberships", "/groups/invitations",
                "/groups/myPosts", "/groups/myPlatformPosts", "/engineering/managerships", "/engineering/authorizedProducts"))
            if (!input.at(path).isEmpty()) return false;
        var materialIds = new HashSet<String>();
        boolean waitingForVote = false;
        for (JsonNode meeting : director.path("meetings")) {
            if ("VOTING".equals(meeting.path("status").asText()) || "ADOPTED".equals(meeting.path("status").asText())) return false;
            if (!"BEFORE_OPEN".equals(meeting.path("window").asText()) || !"CONVENED".equals(meeting.path("status").asText())) continue;
            long opens = meeting.path("opensAt").asLong(-1);
            if (waitingForVote || opens <= now || opens - last > MAX_WAIT_MILLIS
                    || !meeting.path("attended").asBoolean() || meeting.path("recused").asBoolean(true)
                    || !meeting.path("proposalPresent").asBoolean() || meeting.path("materials").isEmpty()) return false;
            for (JsonNode material : meeting.path("materials")) {
                if (!material.path("read").asBoolean() || !material.path("recalled").asBoolean()) return false;
                materialIds.add(material.path("docId").asText());
            }
            waitingForVote = true;
        }
        if (!waitingForVote) return false;
        materialIds.add(director.path("decisionMaterialDocId").asText());
        for (JsonNode read : input.at("/profile/readExperiences"))
            if (!materialIds.contains(read.path("docId").asText())) return false;
        return true;
    }

    private static boolean onlyNewPlatformPost(JsonNode previous, JsonNode facts) {
        JsonNode before = previous.at("/groups/feed"), after = facts.at("/groups/feed");
        JsonNode catalog = facts.at("/profile/catalog"), oldCatalog = previous.at("/profile/catalog");
        if (after.size() != before.size() + 1 || catalog.size() != oldCatalog.size() + 1) return false;
        JsonNode post = after.get(0);
        if (!"PLATFORM_POST".equals(post.path("kind").asText())) return false;
        String id = post.path("docId").asText(), publisher = post.path("publisher").asText();
        if (id.isEmpty() || publisher.isEmpty() || publisher.equals(facts.path("person").asText())) return false;
        for (JsonNode contact : facts.at("/social/contacts"))
            if (publisher.equals(contact.path("targetPartyId").asText())) return false;
        for (int i = 0; i < before.size(); i++) {
            var old = (ObjectNode) before.get(i).deepCopy();
            var retained = (ObjectNode) after.get(i+1).deepCopy();
            if (id.equals(old.path("docId").asText())) return false;
            old.remove("rank"); retained.remove("rank");
            if (!old.equals(retained)) return false;
        }
        var restored = ((ObjectNode) facts).deepCopy();
        var rows = restored.withArray("/profile/catalog");
        boolean matched = false;
        for (int i=0; i<rows.size(); i++) {
            JsonNode row = rows.get(i);
            if (row.path("docId").asText().equals(id) && row.path("version").equals(post.path("version"))
                    && row.path("title").equals(post.path("title"))) { rows.remove(i); matched = true; break; }
        }
        if (!matched) return false;
        restored.withObject("/groups").set("feed", before);
        return previous.equals(restored);
    }
}
