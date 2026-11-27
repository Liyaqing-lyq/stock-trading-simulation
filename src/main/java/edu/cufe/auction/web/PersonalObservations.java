package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.BusinessReceipts;
import edu.cufe.auction.world.WorldRuntime;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;

/** 本人页面的乐观并发凭据。只比较获准观察的业务内容，世界审计序号留在服务端。 */
final class PersonalObservations {
    enum Page { GROUPS, ENGINEERING, MANUFACTURING, RESEARCH, OPERATIONS, NEWSLETTERS }

    private record Key(String person, Page page) {}
    private record Seen(JsonNode facts, long token) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<WorldRuntime, Map<Key, Seen>> WORLDS = new WeakHashMap<>();
    private PersonalObservations() {}

    static boolean enabled(WorldRuntime world, String person) {
        return person != null && BusinessReceipts.enabled(world.profile().manifest());
    }

    static ObjectNode page(WorldRuntime world, String person, Page scope, Object source) throws IOException {
        ObjectNode view = JSON.valueToTree(source);
        scrub(view);
        var facts = JSON.createObjectNode();
        facts.set("page", view.deepCopy());
        if (scope == Page.ENGINEERING || scope == Page.GROUPS || scope == Page.NEWSLETTERS) {
            var role = world.roleView(person);
            facts.set("catalog", JSON.valueToTree(role.catalog()));
            facts.set("readings", JSON.valueToTree(role.readings()));
        }
        long token = token(world, new Key(person, scope), facts);
        view.put("observationToken", Long.toString(token));
        return view;
    }

    private static synchronized long token(WorldRuntime world, Key key, JsonNode facts) {
        var table = WORLDS.computeIfAbsent(world, ignored -> new HashMap<>());
        var previous = table.get(key);
        if (previous != null && previous.facts().equals(facts)) return previous.token();
        long token;
        do { token = RANDOM.nextLong(Long.MAX_VALUE); }
        while (token <= (1L << 53) || containsToken(table, token));
        table.put(key, new Seen(facts, token));
        return token;
    }

    private static boolean containsToken(Map<Key, Seen> table, long token) {
        return table.values().stream().anyMatch(row -> row.token() == token);
    }

    static synchronized Page issuedPage(WorldRuntime world, String person, long token, Page... allowed) {
        var table = WORLDS.get(world);
        if (table == null) return null;
        for (var page : allowed) {
            var seen = table.get(new Key(person, page));
            if (seen != null && seen.token() == token) return page;
        }
        return null;
    }

    static synchronized void consume(WorldRuntime world, String person, Page page) {
        var table = WORLDS.get(world);
        if (table != null) table.remove(new Key(person, page));
    }

    static void scrub(JsonNode node) {
        if (node instanceof ObjectNode object)
            object.remove(List.of("position", "cutoffPosition", "resultPosition", "capturedAtMillis",
                    "retainedMinPosition", "retainedMaxPosition"));
        if (node.isContainerNode()) node.forEach(PersonalObservations::scrub);
    }
}
