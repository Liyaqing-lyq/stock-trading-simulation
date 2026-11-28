package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DecisionEnvelopeGuideTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test void previousOpeningRetainsTheExactReplyContractSentToBea() throws Exception {
        try (var world = ComposedIndustryScenario.openEditorialTenCompanyStudy(directory.resolve("v5.journal"))) {
            var input = world.autonomyRoleObservation("bea");
            assertTrue(input.at("/engineering/decisionEnvelopeGuide").isMissingNode());
            String contract = input.at("/engineering/deliberation/replyContract").textValue();
            // v5 全员校准原始 call-81 的 replyContract UTF-8 SHA-256。
            assertEquals("7a06b107dd078a6941e659f3063a9b19992f42f0382f151a0e184f6c5b91467c",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contract.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test void currentGuideMatchesTheActualReplyParserAtItsBoundaries() throws Exception {
        try (var world = ComposedIndustryScenario.openPersonalTenCompanyStudy(directory.resolve("v6.journal"))) {
            world.beginDecisionSession("typed-session");
            var route = new DecisionModel.Route("media", "key-1", "deepseek-flash", 1, 60, 1_000_000, 1024, 1, "lifecycle-v1", null);
            world.openDecisions("typed-open", List.of(new DecisionRun.Request("bea", "typed-bea", "wake", route)));
            var call = world.dispatchLiveDecision("typed-dispatch", "typed-bea").call();
            assertNotNull(call);
            var state = world.snapshot().decisions().orElseThrow().state();
            var input = JSON.readTree(call.inputText());
            String contract = input.at("/engineering/deliberation/replyContract").textValue();
            assertEquals(RunManifest.TYPED_REVIEW_FIELDS, input.at("/engineering/decisionEnvelopeGuide").textValue());
            assertEquals("等待通常省略args，也接受空对象{}。完整字段见engineering.deliberation.replyContract。",
                    input.at("/engineering/decisionGuide/waitContract").textValue());
            assertEquals(call.inputText().indexOf("字段名不得重复"), call.inputText().lastIndexOf("字段名不得重复"));
            for (String phrase : List.of("顶层decision是对象", "engineering.deliberation.objectives", "含非空白字符",
                    "1至600个UTF-16单元", "JSON整数", "1至2592000000毫秒", "decision.publicPostWake", "IMMEDIATE或ON_REVIEW",
                    "空对象{}", "字段名不得重复", "最多" + state.config().limits().maxReplyChars() + "个UTF-16单元")) {
                assertTrue(contract.contains(phrase), phrase);
            }

            var reply = JSON.createObjectNode().put("tool", "role.wait");
            var fields = reply.putObject("decision");
            fields.put("objective", input.at("/engineering/deliberation/objectives/0/id").textValue());
            String fullBasis = "🌱".repeat(300);
            fields.put("basis", fullBasis).put("reviewAfterMillis", RolePlanningContext.MAX_REVIEW_AFTER_MILLIS);
            assertEquals(DecisionModel.Outcome.WAIT, parse(state, call, reply.toString()).outcome());
            fields.put("publicPostWake", "ON_REVIEW");
            reply.putObject("args");
            assertEquals(DecisionModel.Outcome.WAIT, parse(state, call, reply.toString()).outcome());
            fields.put("basis", fullBasis + "甲");
            assertEquals("BAD_DELIBERATION", parse(state, call, reply.toString()).lastAttempt().reason());
            fields.put("basis", "  ");
            assertEquals("BAD_DELIBERATION", parse(state, call, reply.toString()).lastAttempt().reason());
            fields.put("basis", "查阅本人资料").put("reviewAfterMillis", 1);

            // 复现原始 #81：查资料时附等待专用字段。只移除该字段，同一冻结观察可受理查询。
            reply.put("tool", "role.library.search");
            ((ObjectNode) reply.required("args")).put("query", "桥");
            assertEquals("BAD_DELIBERATION", parse(state, call, reply.toString()).lastAttempt().reason());
            fields.remove("publicPostWake");
            assertEquals(DecisionModel.Outcome.INTENT, parse(state, call, reply.toString()).outcome());
            reply.put("publicPostWake", "IMMEDIATE");
            assertEquals("BAD_REPLY_SHELL", parse(state, call, reply.toString()).lastAttempt().reason());
            reply.remove("publicPostWake");
            assertEquals("BAD_REPLY_SHELL", parse(state, call, reply + " {}").lastAttempt().reason());
            assertEquals("BAD_REPLY_SHELL", parse(state, call,
                    reply.toString().replace("\"tool\":", "\"tool\":\"role.wait\",\"tool\":")).lastAttempt().reason());
        }
    }

    private DecisionModel.Decision parse(DecisionModel.State state, DecisionRun.Call call, String body) {
        var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                call.recoveryGeneration(), body, DecisionModel.Usage.unknownCost(1, 60, 20), "typed-field-fixture");
        return DecisionModel.submitReply(state, reply).decision();
    }
}
