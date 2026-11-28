package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.GroupModel;
import edu.cufe.auction.world.GroupsRun;
import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.IssuerCreditDecisionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupAndPlatformArgumentsTest {
    @TempDir Path dir;

    private static WorldRuntime open(Path file, boolean current) throws Exception {
        return current ? ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(file)
                : ComposedIndustryScenario.openAggregatorEvidenceTenCompanyStudy(file);
    }

    private static void publish(WorldRuntime w, String id, String tool, ObjectNode args) throws Exception {
        var ref = decide(w, "alice", id, tool, args);
        assertNotNull(ref);
        w.advanceTo(ref.dueMillis());
        for (String kind : List.of(GroupsRun.KIND_FINISH, GroupsRun.KIND_PUBLISH)) {
            var pending = w.snapshot().pending().stream().filter(t -> t.kind().equals(kind)).findFirst().orElseThrow();
            w.advanceTo(pending.dueTimeMillis());
        }
        assertEquals("APPLIED", view(w,"alice").at("/engineering/deliberation/lastActionResult/execution").asText());
    }

    @Test void onlyAnExplicitChoiceCanResolveAnInvitation() throws Exception {
        for (boolean current : List.of(false, true)) {
            Path seed = dir.resolve("invite-" + current + ".journal");
            String invite;
            try (var w = open(seed, current)) {
                w.advanceTo(34_200_000);
                applied(w, "bob", "invite", "group.invite", JSON.createObjectNode()
                        .put("groupId", "group:invite-only").put("inviteePartyId", "dave"));
                invite = view(w, "dave").at("/groups/invitations/0/inviteId").textValue();
                assertNotNull(invite);
            }
            for (String choice : List.of("missing", "string", "yes", "no")) {
                var branch = Files.copy(seed, dir.resolve(current + "-" + choice + ".journal"));
                var args = JSON.createObjectNode().put("inviteId", invite);
                if (choice.equals("string")) args.put("accept", "false");
                if (choice.equals("yes") || choice.equals("no")) args.put("accept", choice.equals("yes"));
                boolean invalid = current && (choice.equals("missing") || choice.equals("string"));
                try (var w = open(branch, current)) {
                    if (invalid) assertNull(decide(w, "dave", "respond", "group.respond", args));
                    else applied(w, "dave", "respond", "group.respond", args);
                }
                try (var w = open(branch, current)) {
                    var status = w.groupsRun().state().invitations().get(0).status();
                    assertEquals(invalid ? GroupModel.InvitationStatus.PENDING : choice.equals("yes")
                            ? GroupModel.InvitationStatus.ACCEPTED : GroupModel.InvitationStatus.DECLINED, status);
                    boolean joined = false;
                    for (var row : view(w, "dave").at("/groups/memberships"))
                        if ("group:invite-only".equals(row.path("groupId").asText())) joined = true;
                    assertEquals(choice.equals("yes"), joined);
                }
            }
        }
    }

    @Test void optionalReferencesMayBeOmittedOrEmptyAndMalformedUpdatesLeaveNoVersion() throws Exception {
        for (String section : List.of("group", "platform")) {
            var file = dir.resolve(section + ".journal");
            String list = "/groups/" + (section.equals("group") ? "myPosts" : "myPlatformPosts");
            String doc;
            try (var w = open(file, true)) {
                w.advanceTo(34_200_000);
                ObjectNode post = JSON.createObjectNode().put("title", "初稿").put("body", "可核对正文");
                if (section.equals("group")) post.put("groupId", "group:open");
                var badCites = post.deepCopy(); badCites.putObject("cites").put("docId", "假引用");
                assertNull(decide(w,"alice","object",section+".post",badCites));
                assertNull(decide(w,"alice","unknown",section+".post",post.deepCopy().put("publisher","bob")));
                assertNull(decide(w,"alice","wrong-type",section+".post",post.deepCopy().put("body",42)));
                assertTrue(view(w,"alice").at(list).isEmpty());
                publish(w,"publish",section+".post",post);
                doc = view(w,"alice").at(list + "/0/docId").textValue();
                assertNotNull(doc);
                var correction = JSON.createObjectNode().put("docId",doc).put("basedOnVersion",1)
                        .put("title","更正").put("body","更正后的正文");
                var nested = correction.deepCopy();
                nested.putArray("cites").addObject().put("docId",doc).put("version",1).put("someone","bob");
                assertNull(decide(w,"alice","nested",section+".correct",nested));
                assertNull(decide(w,"alice","base-type",section+".correct",correction.deepCopy().put("basedOnVersion","1")));
                correction.putArray("cites");
                publish(w,"correct",section+".correct",correction);
            }
            try (var w = open(file, true)) {
                boolean second = false;
                for (JsonNode entry : view(w,"alice").at(list)) {
                    assertEquals(doc, entry.path("docId").asText());
                    assertTrue(entry.path("version").asInt() <= 2);
                    if (entry.path("version").asInt() == 2 && "PUBLISHED".equals(entry.path("status").asText())) second = true;
                }
                assertTrue(second);
            }
        }
    }

    @Test void unknownFieldsCannotJoinLeaveOrSendAnInvitationAndOldPostsKeepTheirRecordedSources() throws Exception {
        var file = dir.resolve("membership.journal");
        try (var w = open(file, true)) {
            w.advanceTo(34_200_000);
            var group = JSON.createObjectNode().put("groupId", "group:open");
            for (String action : List.of("join","leave","invite")) {
                var args = group.deepCopy().put("by","alice");
                if (action.equals("invite")) args.put("inviteePartyId","dave");
                assertNull(decide(w,action.equals("join") ? "dave" : "alice","extra-"+action,"group."+action,args));
            }
            applied(w,"dave","join","group.join",group);
            applied(w,"dave","leave","group.leave",group);
        }
        var legacy = dir.resolve("old-platform.journal");
        try (var w = open(legacy, false)) {
            w.advanceTo(34_200_000);
            var post = JSON.createObjectNode().put("title","旧稿").put("body","旧版原有正文");
            post.putObject("cites").put("docId","旧版忽略");
            publish(w,"legacy","platform.post",post);
        }
        try (var w = open(legacy, false)) {
            var posts = w.groupsRun().publication().platform().posts();
            assertEquals(1, posts.size());
            assertTrue(posts.get(0).declaredRefs().isEmpty());
        }
    }
}
