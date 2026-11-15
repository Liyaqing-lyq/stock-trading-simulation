package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 模型回执走真实经营权限、工时、物量与现金；不调用外部服务。 */
public class ManufacturingOperationsDecisionTest {
    @TempDir Path dir;
    static final String COMPANY = ComposedManufacturingThreeScenario.TECH;
    static final String MANAGER = ComposedManufacturingThreeScenario.TECH_MANAGER;

    @Test void actualManagerDecisionHasBeginFinishAndRestores() throws Exception {
        Path journal = dir.resolve("manager.journal");
        long finish;
        try (var world = ComposedManufacturingThreeScenario.openAutonomous(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, COMPANY, ComposedManufacturingThreeScenario.TECH_DIRECTORS,
                    MANAGER, "grant", List.of(0L));
            var call = PharmaDecisionToolTest.open(world, MANAGER, "buy");
            assertTrue(call.allowedTools().contains(CompanyOperationsDecisionTools.ACT));
            var view = world.engineeringRoleInput(MANAGER).path("companyOperations");
            assertEquals(1, view.size());
            assertEquals(COMPANY, view.get(0).path("companyId").asText());
            assertFalse(view.toString().contains("certPlans"));
            assertFalse(view.toString().contains("demandBudget"));
            assertFalse(view.toString().contains("mfg-struct"));
            String material = view.get(0).path("visibleQuotes").get(0).path("materialId").asText();
            finish = PharmaDecisionToolTest.reply(world, call, CompanyOperationsDecisionTools.ACT,
                    Map.of("companyId", COMPANY, "action", "ORDER_MATERIAL", "fields", Map.of("materialId", material, "quantity", "1")));
            world.advanceTo(finish - 500);
            assertTrue(world.operationsRun().orElseThrow().company(COMPANY).ledger().purchases().isEmpty());
        }
        try (var world = ComposedManufacturingThreeScenario.openAutonomous(journal)) {
            world.advanceTo(finish);
            var purchases = world.operationsRun().orElseThrow().company(COMPANY).ledger().purchases();
            assertEquals(1, purchases.size());
            world.advanceTo(purchases.values().iterator().next().arrivalAt());
            assertTrue(world.snapshot().accounts().get(ComposedManufacturingThreeScenario.TECH_CASH).cash().compareTo(new BigDecimal("12000.00")) < 0);
            assertTrue(ComposedCompanyReport.of(world, COMPANY).balanced());
        }
        try (var world = ComposedManufacturingThreeScenario.openAutonomous(journal)) {
            assertEquals(1, world.operationsRun().orElseThrow().company(COMPANY).ledger().purchases().size());
        }
    }

    @Test void openingDirectorsHaveBudgetToolsAndNoManagerAuthority() throws Exception {
        try (var world = ComposedManufacturingThreeScenario.openAutonomous(dir.resolve("director.journal"))) {
            var call = PharmaDecisionToolTest.open(world, ComposedManufacturingThreeScenario.TECH_DIRECTORS.get(0), "director");
            assertTrue(call.allowedTools().contains(RoleTradingView.TOOL_BOARD_PROPOSE_BUDGET));
            assertFalse(call.allowedTools().contains(CompanyOperationsDecisionTools.ACT));
            assertTrue(world.engineeringRoleInput(MANAGER).path("companyOperations").isEmpty());
        }
        try (var world = ComposedManufacturingThreeScenario.openRunnable(dir.resolve("old.journal"))) {
            OperationsFixture.grantBudgetAndAppoint(world, COMPANY, ComposedManufacturingThreeScenario.TECH_DIRECTORS,
                    MANAGER, "old-grant", List.of(0L));
            assertFalse(world.openRoleSession(MANAGER).view().tools().contains(CompanyOperationsDecisionTools.ACT));
            assertTrue(world.engineeringRoleInput(MANAGER).path("companyOperations").isMissingNode());
        }
    }

    @Test void threeActualJvmsRebuildDecisionInputAndContinue() throws Exception {
        java.util.Set<String> pids = new java.util.HashSet<>();
        for (String phase : List.of("write", "finish", "read")) {
            Path output = dir.resolve(phase + ".log");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    ManufacturingOperationsDecisionTest.class.getName(), dir.toString(), phase)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!child.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); fail("独立JVM超时"); }
            String result = java.nio.file.Files.readString(output);
            assertEquals(0, child.exitValue(), result);
            pids.add(result.lines().filter(line -> line.startsWith("OPS_MODEL_JVM_OK ")).findFirst().orElseThrow());
        }
        assertEquals(3, pids.size());
    }

    /** 三独立进程只通过公共命令写、续写和恢复同一档案。 */
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]);
        Path journal = folder.resolve("model-jvm.journal");
        try (var world = ComposedManufacturingThreeScenario.openAutonomous(journal)) {
            if (args[1].equals("write")) {
                OperationsFixture.grantBudgetAndAppoint(world, COMPANY, ComposedManufacturingThreeScenario.TECH_DIRECTORS,
                        MANAGER, "jvm-grant", List.of(0L));
                var call = PharmaDecisionToolTest.open(world, MANAGER, "jvm-buy");
                var view = world.engineeringRoleInput(MANAGER).path("companyOperations").get(0);
                long finish = PharmaDecisionToolTest.reply(world, call, CompanyOperationsDecisionTools.ACT,
                        Map.of("companyId", COMPANY, "action", "ORDER_MATERIAL", "fields", Map.of(
                                "materialId", view.path("visibleQuotes").get(0).path("materialId").asText(), "quantity", "2")));
                world.advanceTo(finish - 500);
                java.nio.file.Files.writeString(folder.resolve("finish.txt"), Long.toString(finish));
            } else if (args[1].equals("finish")) {
                world.advanceTo(Long.parseLong(java.nio.file.Files.readString(folder.resolve("finish.txt"))));
                world.advanceTo(world.operationsRun().orElseThrow().company(COMPANY).ledger().purchases().values().iterator().next().arrivalAt());
                var call = PharmaDecisionToolTest.open(world, MANAGER, "jvm-after-purchase");
                assertTrue(call.inputText().contains("jvm-buy"));
                String body = new ObjectMapper().writeValueAsString(Map.of("tool", DecisionModel.TOOL_WAIT));
                var response = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                        call.recoveryGeneration(), body, DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:operations");
                assertEquals("QUEUED", world.offerDecisionCallback(response));
                assertEquals(1, world.drainDecisionCallbacks());
                assertEquals(DecisionModel.Outcome.WAIT, DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(), call.actionId()).orElseThrow().outcome());
                java.nio.file.Files.writeString(folder.resolve("cash.txt"), world.snapshot().accounts().get(ComposedManufacturingThreeScenario.TECH_CASH).cash().toPlainString());
            } else {
                assertEquals(java.nio.file.Files.readString(folder.resolve("cash.txt")), world.snapshot().accounts().get(ComposedManufacturingThreeScenario.TECH_CASH).cash().toPlainString());
                assertTrue(ComposedCompanyReport.of(world, COMPANY).balanced());
            }
        }
        System.out.println("OPS_MODEL_JVM_OK " + ProcessHandle.current().pid());
    }

    @Test void foreignCompanyAndFractionAreRejectedAsModelActions() throws Exception {
        try (var world = ComposedManufacturingThreeScenario.openAutonomous(dir.resolve("reject.journal"))) {
            OperationsFixture.grantBudgetAndAppoint(world, COMPANY, ComposedManufacturingThreeScenario.TECH_DIRECTORS,
                    MANAGER, "grant", List.of(0L));
            for (String id : List.of("foreign", "fraction")) {
                var call = PharmaDecisionToolTest.open(world, MANAGER, id);
                String company = id.equals("foreign") ? ComposedManufacturingThreeScenario.STRUCT : COMPANY;
                String body = new ObjectMapper().writeValueAsString(Map.of("tool", CompanyOperationsDecisionTools.ACT,
                        "args", Map.of("companyId", company, "action", "ORDER_MATERIAL",
                                "fields", Map.of("materialId", "raw", "quantity", id.equals("fraction") ? "1.5" : "1"))));
                var response = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                        call.recoveryGeneration(), body, DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:operations-reject");
                assertEquals("QUEUED", world.offerDecisionCallback(response));
                assertEquals(1, world.drainDecisionCallbacks());
                assertNull(world.snapshot().decisions().orElseThrow().actionRef(id));
                assertTrue(world.operationsRun().orElseThrow().company(COMPANY).ledger().purchases().isEmpty());
            }
        }
    }

    @Test void fieldsRejectIdentityFractionsAndSystemCompletion() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String bad : List.of("{\"materialId\":\"x\",\"quantity\":1.5}",
                "{\"materialId\":\"x\",\"quantity\":1,\"personPartyId\":\"other\"}",
                "{\"materialId\":\"x\",\"quantity\":\"9223372036854775808\"}")) {
            assertThrows(Exception.class, () -> CompanyOperationsDecisionTools.fields(json.readTree(bad), CompanyOperationsRun.Action.ORDER_MATERIAL));
        }
        assertThrows(Exception.class, () -> CompanyOperationsDecisionTools.fields(json.readTree("{\"period\":0}"), CompanyOperationsRun.Action.CLOSE_PERIOD));
    }
}
