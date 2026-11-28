package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResearchReaderFundingTest {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DAY = 86_400_000L;

    private Path journal(String name) throws Exception {
        Path dir = Path.of(System.getProperty("research.info.dir",temporary.toString()));
        Files.createDirectories(dir);
        return dir.resolve(name+".journal");
    }

    private static JsonNode input(WorldRuntime world, Path file, String person, String id) throws Exception {
        var call=PharmaDecisionToolTest.open(world,person,id);
        var observed=JSON.readTree(call.inputText());
        Files.writeString(file.resolveSibling(file.getFileName()+"-"+id+"-input.json"),call.inputText());
        var body=JSON.createObjectNode().put("tool",DecisionModel.TOOL_WAIT);body.putObject("args");
        body.putObject("decision").put("objective",observed.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText())
                .put("basis","人工编写的角色观察验收回调。").put("reviewAfterMillis",60000);
        world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),
                call.attemptId(),call.attemptNo(),call.recoveryGeneration(),body.toString(),DecisionModel.Usage.unknownCost(1,100,20),"fixture:research-information")));
        world.drainDecisionCallbacks();
        return observed;
    }
    private static JsonNode view(JsonNode input){return input.path("engineering").path("researchOperations");}
    private static void start(WorldRuntime world,String project,long due) throws Exception {
        world.openRoleSession("kate").beginResearch("begin-"+project+"-"+due,"begin-task-"+project+"-"+due,due,"pharma",project,ResearchModel.Stage.RESEARCH,1000);
        world.advanceTo(due);
    }
    private static void appointAndRegister(WorldRuntime world,String project) throws Exception {
        ComposedPharmaWorldTest.grantBudgetAndAppointManager(world,"kate");
        world.openRoleSession("kate").registerResearch("register","reg-task",14000,"pharma",project,1000);
        world.advanceTo(14000);
    }

    @Test void paidStageAndActualAvailableCompanyCashArePrivateToTheIncumbent() throws Exception {
        Path file=journal("cash");WorldRuntime.Snapshot finalState;
        try(var world=ComposedIndustryScenario.openCompanyInformationOperating(file)){
            assertTrue(view(world.autonomyRoleObservation("kate")).isMissingNode());
            appointAndRegister(world,"p1");
            world.scheduleOrder("hold","hold-task",15000,"pharma-owner","RND-PHARMA","MT1",Side.BUY,OrderType.LIMIT,new BigDecimal("1.00"),1950);
            world.advanceTo(15000);
            JsonNode funds=view(input(world,file,"kate","cash-locked")).path("funds");
            var keys = new ArrayList<String>();
            funds.fieldNames().forEachRemaining(keys::add);
            assertEquals(List.of("availableCash","companyCash","frozenCash"),keys);
            assertEquals("2000.00",funds.path("companyCash").asText());
            assertEquals("1950.00",funds.path("frozenCash").asText());assertEquals("50.00",funds.path("availableCash").asText());
            assertTrue(view(input(world,file,"frank","chair")).isMissingNode());
            assertTrue(view(world.autonomyRoleObservation("digital-manager")).isMissingNode());
            assertTrue(view(world.autonomyRoleObservation("investor")).isMissingNode());
            long now=world.snapshot().timeMillis();
            start(world,"p1",now+2000);
            assertTrue(view(world.autonomyRoleObservation("kate")).path("registeredProjects").get(0).path("stagePayments").isEmpty());
            long cancel=world.snapshot().timeMillis()+1000;
            world.scheduleCancelOrder("release","release-task",cancel,"pharma-owner",1);world.advanceTo(cancel);
            start(world,"p1",cancel+2000);
            var paid=view(input(world,file,"kate","paid"));
            assertEquals("1800.00",paid.path("funds").path("availableCash").asText());
            assertEquals("0.00",paid.path("funds").path("frozenCash").asText());
            var payments=paid.path("registeredProjects").get(0).path("stagePayments");
            assertEquals(1,payments.size());
            keys.clear();payments.get(0).fieldNames().forEachRemaining(keys::add);
            assertEquals(List.of("amount","paidAt","stage"),keys);
            assertEquals("RESEARCH",payments.get(0).path("stage").asText());
            assertEquals("200.00",payments.get(0).path("amount").asText());assertEquals(cancel+2000,payments.get(0).path("paidAt").asLong());
            finalState=world.snapshot();
        }
        try(var w=ComposedIndustryScenario.openCompanyInformationOperating(file)){assertEquals(finalState,w.snapshot());}
    }

    @Test void unreadPassedAndFailedEvidenceHaveNeutralReferencesAndMetadata() throws Exception {
        for(String project:List.of("p1","p2")){
            Path file=journal("evidence-"+project);WorldRuntime.Snapshot finalState;
            try(var world=ComposedIndustryScenario.openCompanyInformationOperating(file)){
                appointAndRegister(world,project);start(world,project,16000);
                String id="research:pharma:"+project+":RESEARCH:report";
                long due=16000+45*DAY;
                var live=world.snapshot().pending().stream().filter(t->t.taskId().contains("research-result:pharma:"+project)).findFirst().orElseThrow();
                assertFalse(live.payload().contains("SUCCESS")||live.payload().contains("FAILURE"));
                var before=input(world,file,"kate","before-result");assertFalse(before.toString().contains(id));
                world.advanceTo(due);
                var stage=world.researchRun().orElseThrow().state().projects().get(project).stages().get(0);
                assertEquals(id,stage.evidence().reference());
                var unread=input(world,file,"kate","unread");
                String text=unread.toString();
                assertTrue(text.contains(id));assertFalse(text.contains(":SUCCESS")||text.contains(":FAILURE"));
                assertFalse(text.contains("结果=SUCCESS")||text.contains("结果=FAILURE"));
                var row=view(unread).path("registeredProjects").get(0);
                assertEquals("RESULT_RECORDED",row.path("stageStatus").asText());
                assertFalse(row.has("evidenceDocId"));assertFalse(row.has("remainingCommitment"));
                assertFalse(row.has("nextStage"));
                var chair=input(world,file,"frank","unread-chair");assertTrue(chair.toString().contains(id));
                assertFalse(chair.toString().contains(":SUCCESS")||chair.toString().contains(":FAILURE"));
                assertFalse(input(world,file,"digital-manager","outside").toString().contains(id));
                if (project.equals("p1")) {
                    long interrupted = world.snapshot().timeMillis() + 1000;
                    world.openRoleSession("kate").startReading("interrupted-read","interrupted-read-task",interrupted,id,1);
                    world.scheduleRevokeAppointment("revoke","revoke-task",interrupted+500,"mgr-kate");
                    world.advanceTo(interrupted+1500);
                    var revoked = input(world,file,"kate","revoked");
                    assertTrue(view(revoked).isMissingNode());
                    assertFalse(revoked.path("profile").path("catalog").toString().contains(id));
                    assertFalse(revoked.toString().contains("结果=SUCCESS"));
                    assertFalse(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                            "kate",id,1,world.snapshot().timeMillis()));
                    ConnectedWorldBootstrap.grantBudgetAndAppoint(world,"pharma",ComposedPharmaScenario.DIRECTORS,
                            "kate","restore-manager",List.of(2L));
                    var restored = input(world,file,"kate","restored-unread");
                    assertTrue(restored.path("profile").path("catalog").toString().contains(id));
                    assertEquals("RESULT_RECORDED",view(restored).path("registeredProjects").get(0).path("stageStatus").asText());
                    assertFalse(restored.toString().contains("结果=SUCCESS"));
                }
                long at=world.snapshot().timeMillis()+1000;
                world.openRoleSession("kate").startReading("read-result","read-result-task",at,id,1);world.advanceTo(at+1500);
                var read=input(world,file,"kate","read");
                var known=view(read).path("registeredProjects").get(0);
                String outcome=project.equals("p1")?"SUCCESS":"FAILURE";
                assertEquals(project.equals("p1")?"COMPLETED":"FAILED",known.path("stageStatus").asText());
                assertEquals(id,known.path("evidenceDocId").asText());
                assertEquals(project.equals("p1")?"700.00":"0.00",known.path("remainingCommitment").asText());
                assertTrue(read.toString().contains("结果="+outcome));
                finalState=world.snapshot();
            }
            try(var w=ComposedIndustryScenario.openCompanyInformationOperating(file)){assertEquals(finalState,w.snapshot());}
        }
    }

    @Test void previousEngineeringArchiveKeepsOriginalIdentifiersAndObservationShape() throws Exception {
        Path file=journal("legacy");
        try(var w=ComposedIndustryScenario.openEngineeringCustomerOperating(file)){
            appointAndRegister(w,"p2");start(w,"p2",16000);w.advanceTo(16000+45*DAY);
            var ledger=w.researchRun().orElseThrow().state().projects().get("p2").stages().get(0);
            assertEquals("research:pharma:p2:RESEARCH:FAILURE",ledger.evidence().reference());
            var r=view(w.autonomyRoleObservation("kate"));assertFalse(r.has("funds"));assertTrue(r.path("registeredProjects").get(0).has("remainingCommitment"));
        }
        byte[] raw=Files.readAllBytes(file);try(var w=ComposedIndustryScenario.openEngineeringCustomerOperating(file)){assertNotNull(w.researchRun().orElseThrow());}assertArrayEquals(raw,Files.readAllBytes(file));
    }
}
