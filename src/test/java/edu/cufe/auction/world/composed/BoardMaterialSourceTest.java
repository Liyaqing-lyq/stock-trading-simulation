package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardMaterialSourceTest {
    private static final String COMPANY = AgedIndustryConfig.COMPANY;
    private static final String CHAIR = BaselineIndustryConfig.directors(COMPANY).get(0);
    private static final String DENIED = "MATERIAL_SOURCE_UNAVAILABLE";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test void ordinaryAndCommercialMeetingsAcceptOnlyAnExistingReadableVersion() throws Exception {
        Path file = directory.resolve("material.journal");
        WorldRuntime.Snapshot end;
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            String publicDoc = "supplier-quote", privateDoc = "receipt-for-pharma";
            world.schedulePublishDocument("publish", "publish-t", 100, publicDoc, "investor",
                    InformationModel.Audience.publicAudience(), "原报价", "50元", "原报价单", 10, List.of());
            world.schedulePublishDocument("private", "private-t", 100, privateDoc, "investor",
                    InformationModel.Audience.direct("frank"), "私人报价", "49元", "私人报价单", 10, List.of());
            world.schedulePublishDocumentVersion("revise", "revise-t", 3_000, publicDoc, "修订报价", "48元", "修订报价单", 10, List.of());
            world.scheduleCompanyBoardConvene("budget", "budget-t", 500, COMPANY, CHAIR, "budget",
                    200_000, 220_000, 250_000, false);
            world.scheduleCompanyBoardAttend("attend", "attend-t", 600, COMPANY, CHAIR, "budget");
            world.scheduleCommercializationConvene("research", "research-t", 500, "pharma", "frank",
                    "research", 200_000, 220_000, 250_000);
            world.scheduleEngineeringAttend("research-chair", "research-chair-t", 600, "pharma", "frank", "research", false);
            world.scheduleEngineeringConvene("engineering", "engineering-t", 500, "eng-a", "alice",
                    "engineering", 200_000, 220_000, 250_000);
            world.scheduleEngineeringAttend("engineering-chair", "engineering-chair-t", 600,
                    "eng-a", "alice", "engineering", false);
            material(world, 1_100, COMPANY, CHAIR, "budget", "missing", "absent", 1);
            material(world, 1_200, COMPANY, CHAIR, "budget", "private-guess", privateDoc, 1);
            material(world, 1_300, COMPANY, CHAIR, "budget", "future", publicDoc, 2);
            material(world, 1_400, "pharma", "frank", "research", "own", privateDoc, 1);
            material(world, 1_500, "pharma", "frank", "research", "fake-stage", "research:pharma:p1:RESEARCH:SUCCESS", 1);
            world.scheduleEngineeringMaterial("wrong-issuer", "wrong-issuer-t", 1_600, "eng-a", "alice",
                    "engineering", "wrong-issuer", privateDoc, 1);
            material(world, 3_100, COMPANY, CHAIR, "budget", "v1", publicDoc, 1);
            material(world, 3_200, COMPANY, CHAIR, "budget", "v2", publicDoc, 2);
            world.scheduleEngineeringMaterial("engineering-v2", "engineering-v2-t", 3_300, "eng-a", "alice",
                    "engineering", "engineering-v2", publicDoc, 2);
            world.advanceTo(4_000);
            for (String id : List.of("missing", "private-guess", "future", "fake-stage", "wrong-issuer"))
                assertEquals(DENIED, report(world, id).reason(), id);
            for (String id : List.of("v1", "v2", "own", "engineering-v2")) assertTrue(report(world, id).accepted(), id);
            assertEquals(List.of(1L, 2L), world.boardOfCompany(COMPANY).orElseThrow().board().meeting("budget")
                    .materials().stream().map(BoardModel.MaterialRef::version).toList());
            assertEquals(List.of(privateDoc), world.boardOfCompany("pharma").orElseThrow().board().meeting("research")
                    .materials().stream().map(BoardModel.MaterialRef::sourceRef).toList());
            end = world.snapshot();
        }
        byte[] sealed = Files.readAllBytes(file);
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            assertEquals(end, world.snapshot());
        }
        assertArrayEquals(sealed, Files.readAllBytes(file));
    }

    @Test void replyCannotCiteAVersionFirstPublishedAfterItsOwnObservation() throws Exception {
        Path file = directory.resolve("during-deliberation.journal");
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            w.schedulePublishDocument("pub", "pub-t", 100, "quote", "investor", InformationModel.Audience.publicAudience(),
                    "报价", "初版", "客户", 10, List.of());
            w.scheduleCompanyBoardConvene("convene", "convene-t", 200, COMPANY, CHAIR, "budget", 180_000, 200_000, 240_000, false);
            w.scheduleCompanyBoardAttend("attend", "attend-t", 300, COMPANY, CHAIR, "budget");
            w.advanceTo(1_000);
            w.beginDecisionSession("session-guessed");
            w.openDecisions("ask-guessed", List.of(new DecisionRun.Request(CHAIR, "guessed-revision", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 25, 1_000_000, 2048, 1))));
            var call = w.dispatchLiveDecision("dispatch-guessed", "guessed-revision").call();
            assertNotNull(call);
            w.schedulePublishDocumentVersion("rev", "rev-t", 1_001, "quote", "修正报价", "新版", "客户更正", 10, List.of());
            w.advanceTo(1_001);
            var input = JSON.readTree(call.inputText());
            var body = JSON.createObjectNode().put("tool", "board.addMaterial");
            body.putObject("args").put("companyPartyId", COMPANY).put("meetingId", "budget")
                    .put("materialId", "guessed").put("sourceRef", "quote").put("version", 2);
            body.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                    .put("basis", "受控测试此时才发布的版本").put("reviewAfterMillis", 60_000);
            var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                    call.recoveryGeneration(), body.toString(), DecisionModel.Usage.unknownCost(1, 10, 5), "scripted:test");
            assertEquals("QUEUED", w.offerDecisionCallback(reply));
            assertEquals(1, w.drainDecisionCallbacks());
            w.advanceTo(65_000);
            assertNull(w.snapshot().decisions().orElseThrow().actionRef(call.actionId()));
            assertTrue(w.boardOfCompany(COMPANY).orElseThrow().board().meeting("budget").materials().isEmpty());
            assertTrue(w.autonomyRoleObservation(CHAIR).toString().contains(DENIED));
            var args = JSON.createObjectNode().put("companyPartyId", COMPANY).put("meetingId", "budget")
                    .put("materialId", "published").put("sourceRef", "quote").put("version", 2);
            var action = IssuerCreditDecisionTest.decide(w, CHAIR, "actual-revision", "board.addMaterial", args);
            assertNotNull(action);
            w.advanceTo(action.dueMillis() + 1_000);
            assertEquals(List.of("published"), w.boardOfCompany(COMPANY).orElseThrow().board().meeting("budget")
                    .materials().stream().map(BoardModel.MaterialRef::materialId).toList(),
                    () -> w.snapshot().engineering().orElseThrow().reports().get(action.taskId() + ":finish") + " " + action);
        }
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            assertEquals(1, w.boardOfCompany(COMPANY).orElseThrow().board().meeting("budget").materials().size());
        }
    }

    @Test void groupHistoryAndExitApplyToTheExactVersionOfTheAgendaSource() throws Exception {
        Path file = directory.resolve("group-membership.journal");
        String group = WorldWebScenario.GROUPS_OPEN;
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            w.scheduleEngineeringConvene("convene", "convene-t", 100, "eng-a", "alice", "board", 20_000, 24_000, 28_000);
            w.scheduleEngineeringAttend("bob-att", "bob-att-t", 200, "eng-a", "bob", "board", false);
            w.advanceTo(500);
            var bob = w.openRoleSession("bob");
            bob.leaveGroup("leave", "leave-t", 510, new WorldRoleSession.GroupLeaveDraft(group));
            w.advanceTo(700);
            var alice = w.openRoleSession("alice");
            alice.postToGroup("quote", "quote-t", 720, new WorldRoleSession.GroupPostDraft(group, "原报价", "初版", List.of()));
            w.advanceTo(900);
            String doc = w.groupsRun().state().posts().get(0).docId();
            bob.joinGroup("join", "join-t", 910, new WorldRoleSession.GroupJoinDraft(group));
            w.advanceTo(1_100);
            alice.correctGroupPost("rev", "rev-t", 1_120, new WorldRoleSession.GroupCorrectDraft(doc, 1, "新版", "更新", List.of()));
            w.advanceTo(1_300);
            w.scheduleEngineeringMaterial("pre-join", "pre-join-t", 1_500, "eng-a", "bob", "board", "prior", doc, 1);
            w.scheduleEngineeringMaterial("in-group", "in-group-t", 1_600, "eng-a", "bob", "board", "usable", doc, 2);
            w.advanceTo(1_700);
            assertEquals(DENIED, report(w, "pre-join").reason());
            assertTrue(report(w, "in-group").accepted());
            bob.leaveGroup("exit", "exit-t", 1_710, new WorldRoleSession.GroupLeaveDraft(group));
            w.advanceTo(2_100);
            w.scheduleEngineeringMaterial("after-exit", "after-exit-t", 2_200, "eng-a", "bob", "board", "after", doc, 2);
            w.advanceTo(2_300);
            assertEquals(DENIED, report(w, "after-exit").reason());
        }
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            var materials = w.boardOfCompany("eng-a").orElseThrow().board().meeting("board").materials();
            assertEquals(List.of(2L), materials.stream().map(BoardModel.MaterialRef::version).toList());
        }
    }

    @Test void previousRunRetainsUnresolvedHistoricalReferenceOnReopen() throws Exception {
        Path file = directory.resolve("historical.journal");
        try (var w = ComposedIndustryScenario.openBaselinePartsOperating(file)) {
            w.scheduleCompanyBoardConvene("convene", "convene-t", 100, COMPANY, CHAIR, "budget", 4_000, 5_000, 6_000, false);
            w.scheduleCompanyBoardAttend("att", "att-t", 200, COMPANY, CHAIR, "budget");
            material(w, 1_100, COMPANY, CHAIR, "budget", "former", "never-published", 1);
            w.advanceTo(1_100);
            assertTrue(report(w, "former").accepted());
        }
        byte[] sealed = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openBaselinePartsOperating(file)) {
            assertEquals("never-published", w.boardOfCompany(COMPANY).orElseThrow().board()
                    .meeting("budget").materials().get(0).sourceRef());
        }
        assertArrayEquals(sealed, Files.readAllBytes(file));
    }

    private static void material(WorldRuntime w, long at, String company, String who,
            String meeting, String id, String doc, long version) throws Exception {
        w.scheduleCompanyBoardMaterial(id, id + "-t", at, company, who, meeting, id, doc, version);
    }

    private static EngineeringRun.Report report(WorldRuntime w, String id) {
        return w.snapshot().engineering().orElseThrow().reports().get(id + "-t:finish");
    }
}
