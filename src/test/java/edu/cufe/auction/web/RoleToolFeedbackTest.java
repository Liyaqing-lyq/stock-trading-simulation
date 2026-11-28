package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoleToolFeedbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    private static String rejected(WorldRuntime world, String id, String tool, ObjectNode args) throws Exception {
        return rejected(world, "nina", id, tool, args);
    }

    private static String rejected(WorldRuntime world, String actor, String id, String tool, ObjectNode args) throws Exception {
        var call = AutonomousIssuerDividendTest.call(world, actor, id);
        assertTrue(call.allowedTools().contains(tool));
        assertNull(AutonomousIssuerDividendTest.reply(world, call, actor, tool, args));
        var feedback = world.autonomyRoleObservation(actor).at("/engineering/deliberation/lastActionResult");
        assertEquals("TECHNICAL_FAILED", feedback.required("outcome").asText());
        return feedback.required("protocolReason").asText();
    }

    @Test void approvalAndConsentRequireAnExplicitJsonBoolean() throws Exception {
        try (var world = ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(dir.resolve("choices.journal"))) {
            world.advanceTo(34_200_000);
            record Choice(String actor, String tool, String field) {}
            for (var choice : List.of(new Choice("nina", "interview.respond", "accept"), new Choice("nina", "group.respond", "accept"),
                    new Choice("omar", "media.story.review", "approve"), new Choice("carol", "role.target.review", "approved"))) {
                var invalid = List.of(JSON.createObjectNode(), JSON.createObjectNode().putNull(choice.field()),
                        JSON.createObjectNode().put(choice.field(), 2), JSON.createObjectNode().put(choice.field(), "false"));
                for (int i = 0; i < invalid.size(); i++) {
                    assertEquals("TOOL_ARGS_INVALID:参数 " + choice.field() + " 须明确填写布尔值 true 或 false",
                            rejected(world, choice.actor(), choice.tool() + "-" + i, choice.tool(), invalid.get(i)));
                }
            }
        }
    }

    @Test void publicPostMenusDescribeTheSameCitationFieldsTheyAccept() throws Exception {
        try (var world = ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(dir.resolve("sources.journal"))) {
            world.advanceTo(34_200_000);
            var tools = world.autonomyRoleObservation("nina").required("tools");
            var expected = new java.util.HashSet<>(List.of("group.post", "group.correct", "platform.post", "platform.correct"));
            for (var tool : tools) {
                if (expected.remove(tool.required("name").asText())) {
                    assertEquals("string", tool.at("/arrayItems/cites/docId").asText());
                    assertEquals("long", tool.at("/arrayItems/cites/version").asText());
                    assertTrue(tool.required("citationSource").asText().contains("空数组"));
                }
            }
            assertTrue(expected.isEmpty());
        }
    }

    @Test void anEarlierWorldKeepsItsOriginalReceipt() throws Exception {
        Path journal = dir.resolve("old.journal");
        var args = JSON.createObjectNode().put("toPartyId", "omar").put("channel", "私信")
                .put("title", "核对").put("body", "核对原件").put("body_notes", "");
        try (var world = ComposedIndustryScenario.openAggregatorEvidenceTenCompanyStudy(journal)) {
            world.advanceTo(32_400_000);
            assertTrue(rejected(world, "old", "social.send", args).contains("WorldRoleSession$SocialSendDraft"));
        }
        try (var world = ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(journal)) {
            assertTrue(world.autonomyRoleObservation("nina")
                    .at("/engineering/deliberation/lastActionResult/protocolReason").asText().contains("WorldRoleSession$SocialSendDraft"));
        }
    }

    @Test void anInvalidValueIdentifiesTheFieldAndPreservesDomainErrors() throws Exception {
        try (var world = ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(dir.resolve("values.journal"))) {
            world.advanceTo(32_400_000);
            var args = JSON.createObjectNode().put("requestId", "example").put("accept", false).put("startMillis", 0);
            assertEquals("TOOL_ARGS_INVALID:拒绝邀请须显式填写 startMillis=-1", rejected(world, "start", "interview.respond", args));
            args.put("startMillis", "不是毫秒");
            assertEquals("TOOL_ARGS_INVALID:参数 startMillis 的类型或取值不符合本次工具契约",
                    rejected(world, "type", "interview.respond", args));
        }
    }

    @Test void anUnknownMessageFieldNamesTheDeclaredFieldsWithoutTheJavaClass() throws Exception {
        Path journal = dir.resolve("unknown-field.journal");
        var args = JSON.createObjectNode().put("toPartyId", "omar").put("channel", "私信")
                .put("title", "核对").put("body", "想核对原件。");
        args.putArray("cites");
        String reason;
        try (var world = ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(journal)) {
            world.advanceTo(32_400_000);
            reason = rejected(world, "unknown", "social.send", args);
            assertEquals("TOOL_ARGS_INVALID:未声明字段 cites，可用字段为 body、channel、title、toPartyId", reason);
        }
        try (var world = ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(journal)) {
            assertEquals(reason, world.autonomyRoleObservation("nina")
                    .at("/engineering/deliberation/lastActionResult/protocolReason").asText());
        }
    }
}
