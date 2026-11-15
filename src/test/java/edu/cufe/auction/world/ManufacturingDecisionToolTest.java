package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.ComposedResearchScenario;
import edu.cufe.auction.world.composed.ComposedWorldSelfTest;
import edu.cufe.auction.world.composed.ManufacturingFundingChainTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 确定性模型回执走真实决定、屏障、资金、日志和恢复；不发送外部模型请求。 */
class ManufacturingDecisionToolTest {
    @TempDir Path dir;
    private static final long DAY = 86_400_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void managerDecisionBuysAndProducesAfterRecovery() throws Exception {
        Path journal = dir.resolve("manager.journal");
        long arrival;
        try (WorldRuntime world = ComposedResearchScenario.openDebug(journal)) {
            assertFalse(world.openRoleSession("erin").view().tools().contains(ManufacturingDecisionTools.ACT));
            ComposedWorldSelfTest.appoint(world, 10, "cufe", "erin", "mfg-manager", "mfg-material");
            ManufacturingFundingChainTest.budgetMeeting(world, "cufe", "mfg-budget", "10000.00", "10000.00");
            DecisionRun.Call call = open(world, "purchase");
            assertTrue(call.allowedTools().contains(ManufacturingDecisionTools.ACT));
            assertTrue(call.inputText().contains("approvedBudgetCaps"));
            assertTrue(call.inputText().contains("rawUnitPrice"));
            assertTrue(call.inputText().contains("contractUnitPriceCap"));
            assertTrue(call.inputText().contains("manager-po-1"), "真实调用不得被迫编造业务编号");
            assertTrue(call.inputText().contains("fundSource"), "模型必须收到真实工具参数合同");
            assertFalse(call.inputText().contains("initialWorldCash"));
            assertFalse(call.inputText().contains("customerAccountId"));
            long finish = reply(world, call, "PURCHASE_ORDER", "AI-PO", "CASH|CO");
            world.advanceTo(finish - 500);
            assertEquals(new BigDecimal("1000.00"), world.snapshot().accounts().get("CO").cash());
            arrival = finish + DAY;
        }
        try (WorldRuntime world = ComposedResearchScenario.openDebug(journal)) {
            world.advanceTo(arrival);
            assertEquals(10, world.snapshot().manufacturing().orElseThrow().ledger().rawMaterials().quantity());
            assertEquals(new BigDecimal("500.00"), world.snapshot().accounts().get("CO").cash());
            assertTrue(world.manufacturingReceiptsFor("erin").stream().anyMatch(r -> "PURCHASE_ORDER_PLACED".equals(r.reason())));
            DecisionRun.Call call = open(world, "produce");
            long finish = reply(world, call, "PRODUCTION", "AI-BATCH", "CASH|CO");
            world.advanceTo(finish + DAY);
            assertEquals(10, world.snapshot().manufacturing().orElseThrow().ledger().finishedGoods().quantity());
            assertEquals(new BigDecimal("200.00"), world.snapshot().accounts().get("CO").cash());
        }
        try (WorldRuntime world = ComposedResearchScenario.openDebug(journal)) {
            assertEquals(10, world.snapshot().manufacturing().orElseThrow().ledger().finishedGoods().quantity());
        }
    }

    @Test
    void oldFundedScenarioDoesNotAcquireNewModelTools() throws Exception {
        try (WorldRuntime world = ComposedResearchScenario.openFunded(dir.resolve("old.journal"))) {
            ComposedWorldSelfTest.appoint(world, 10, "cufe", "erin", "old-manager", "old-material");
            assertFalse(world.openRoleSession("erin").view().tools().contains(ManufacturingDecisionTools.ACT));
        }
    }

    @Test
    void modelCannotSpendTheSuppliersAccount() throws Exception {
        try (WorldRuntime world = ComposedResearchScenario.openDebug(dir.resolve("wrong-fund.journal"))) {
            ComposedWorldSelfTest.appoint(world, 10, "cufe", "erin", "manager", "material");
            ManufacturingFundingChainTest.budgetMeeting(world, "cufe", "budget", "10000.00", "10000.00");
            Map<String, CashLedger.CashAccount> before = world.snapshot().accounts();
            var call = open(world, "wrong-fund");
            long finish = reply(world, call, "PURCHASE_ORDER", "WRONG-FUND-PO", "CASH|SUP");
            world.advanceTo(finish);
            assertEquals(before, world.snapshot().accounts());
            assertFalse(world.snapshot().manufacturing().orElseThrow().ledger().purchaseOrders().containsKey("WRONG-FUND-PO"));
            assertTrue(world.manufacturingReceiptsFor("erin").stream().anyMatch(row -> !row.accepted()
                    && row.reason().equals("FUND_ACCOUNT_MISMATCH:SUP")), "应因付款账户而拒绝，不能另找公司现金补付");
        }
    }

    @Test
    void revokedObservedManagerCannotFinishTheModelAction() throws Exception {
        Path file = dir.resolve("revoked.journal");
        try (WorldRuntime world = ComposedResearchScenario.openDebug(file)) {
            ComposedWorldSelfTest.appoint(world, 10, "cufe", "erin", "manager", "material");
            ManufacturingFundingChainTest.budgetMeeting(world, "cufe", "budget", "10000.00", "10000.00");
            String appointment = world.openRoleSession("erin").appointments().get("cufe");
            var call = open(world, "revoked");
            long finish = reply(world, call, "PURCHASE_ORDER", "DENIED-PO", "CASH|CO");
            world.advanceTo(finish - 500);
            world.scheduleRevokeAppointment("revoke", "revoke-task", finish - 100, appointment);
            world.advanceTo(finish);
            assertTrue(world.snapshot().manufacturing().orElseThrow().reports().values().stream()
                    .anyMatch(row -> row.businessId().equals("DENIED-PO") && row.reason().equals("NOT_AUTHORIZED")));
            assertFalse(world.snapshot().manufacturing().orElseThrow().ledger().purchaseOrders().containsKey("DENIED-PO"));
            assertEquals(new BigDecimal("1000.00"), world.snapshot().accounts().get("CO").cash());
            assertTrue(world.manufacturingReceiptsFor("erin").isEmpty());
        }
        try (WorldRuntime recovered = ComposedResearchScenario.openDebug(file)) {
            assertEquals(new BigDecimal("1000.00"), recovered.snapshot().accounts().get("CO").cash());
        }
    }

    @Test
    void changingPendingAndActionCopyCannotRewriteTheOriginalModelDecision() throws Exception {
        Path original = dir.resolve("original.journal");
        try (WorldRuntime world = ComposedResearchScenario.openDebug(original)) {
            ComposedWorldSelfTest.appoint(world, 10, "cufe", "erin", "manager", "material");
            ManufacturingFundingChainTest.budgetMeeting(world, "cufe", "budget", "10000.00", "10000.00");
            reply(world, open(world, "original"), "PURCHASE_ORDER", "ORIGINAL-PO", "CASH|CO");
        }
        int frame = JournalTamper.frames(original).size() - 1;
        Path control = java.nio.file.Files.copy(original, dir.resolve("control.journal"));
        JournalTamper.rewrite(control, frame, json -> json + " ");
        try (WorldRuntime world = ComposedResearchScenario.openDebug(control)) {
            assertTrue(world.snapshot().pending().stream().anyMatch(t -> t.kind().equals(PendingTask.MANUFACTURING_FUND_BEGIN_V1)));
        }
        Path attack = java.nio.file.Files.copy(original, dir.resolve("attack.journal"));
        JournalTamper.rewrite(attack, frame, json -> {
            try {
                var batch = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(json);
                String originalReply = batch.get("commandContent").asText();
                int changed = 0;
                for (var event : batch.withArray("events")) {
                    if (!"STATE_SNAPSHOT_V1".equals(event.path("type").asText())) continue;
                    var state = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(event.get("payload").asText());
                    String decisions = state.path("decisions").toString();
                    for (var task : state.withArray("pending")) {
                        if (!task.path("kind").asText().equals(PendingTask.MANUFACTURING_FUND_BEGIN_V1)) continue;
                        var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(task.get("payload").asText());
                        var request = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(wrapper.get("payload").asText());
                        request.put("quantity", 11);
                        wrapper.put("payload", JSON.writeValueAsString(request));
                        String forged = JSON.writeValueAsString(wrapper);
                        ((com.fasterxml.jackson.databind.node.ObjectNode) task).put("payload", forged);
                        ((com.fasterxml.jackson.databind.node.ObjectNode) state.get("commandContents"))
                                .put(request.get("commandId").asText(), forged);
                        changed++;
                    }
                    assertEquals(decisions, state.path("decisions").toString());
                    ((com.fasterxml.jackson.databind.node.ObjectNode) event).put("payload", JSON.writeValueAsString(state));
                }
                assertEquals(1, changed);
                assertEquals(originalReply, batch.get("commandContent").asText());
                return JSON.writeValueAsString(batch);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
        Exception failure = assertThrows(Exception.class, () -> {
            try (WorldRuntime ignored = ComposedResearchScenario.openDebug(attack)) { }
        });
        assertFalse(failure.getMessage().contains("JSON"), "应由原决定与状态不一致拒绝：" + failure);
    }

    @Test
    void companyManagerDoesNotInheritFundAccountVisibility() throws Exception {
        try (WorldRuntime world = ComposedResearchScenario.openDebug(dir.resolve("visibility.journal"))) {
            ComposedWorldSelfTest.appoint(world, 10, "cufe", "erin", "manager", "material");
            assertTrue(world.openRoleSession("alice").view().profile().accounts().stream()
                    .anyMatch(a -> a.accountId().equals("ACC-FUND")), "已授权研究员仍能读产品账户");
            var profile = world.openRoleSession("erin").view().profile();
            assertTrue(profile.accounts().stream().noneMatch(a -> a.accountId().equals("ACC-FUND")),
                    "公司经理不是基金四岗，不应继承产品余额");
            assertTrue(profile.holdings().stream().noneMatch(h -> h.accountId().equals("ACC-FUND")));
            var input = JSON.readTree(open(world, "visibility").inputText());
            assertTrue(input.path("profile").path("accounts").isEmpty());
            assertTrue(input.path("profile").path("holdings").isEmpty());
        }
    }

    static DecisionRun.Call open(WorldRuntime world, String id) throws Exception {
        world.beginDecisionSession("session-" + id);
        world.openDecisions("open-" + id, List.of(new DecisionRun.Request("erin", id, "wake",
                new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20, 1_000_000, 1_024, 1))));
        return world.dispatchLiveDecision("dispatch-" + id, id).call();
    }

    static long reply(WorldRuntime world, DecisionRun.Call call, String action, String business, String source) throws Exception {
        String body = JSON.writeValueAsString(Map.of("tool", ManufacturingDecisionTools.ACT, "args", Map.of(
                "action", action, "businessId", business, "resultId", "", "quantity", 10,
                "unitPrice", "0.00", "promisedDeliveryAt", 0, "period", 0, "fundSource", source)));
        var answer = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                call.recoveryGeneration(), body, DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:manufacturing");
        assertEquals("QUEUED", world.offerDecisionCallback(answer));
        assertEquals(1, world.drainDecisionCallbacks());
        var ref = world.snapshot().decisions().orElseThrow().actionRef(call.actionId());
        assertNotNull(ref, "制造模型回执必须进入真实待办");
        assertEquals(PendingTask.MANUFACTURING_FUND_BEGIN_V1, world.snapshot().pending().stream()
                .filter(task -> task.taskId().equals(ref.taskId())).findFirst().orElseThrow().kind());
        return ref.dueMillis() + ManufacturingDecisionTools.ACTION_MILLIS;
    }

    @Test
    void threeProcessesContinuePendingDecisionAndIsolateTheBranch() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (int step = 0; step < 3; step++) {
            Path log = dir.resolve("process-" + step + ".log");
            Process process = new ProcessBuilder(javaExecutable, "-Xmx768m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-cp", cp, ManufacturingDecisionReopenProbe.class.getName(), dir.toString(), Integer.toString(step))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean done = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) process.destroyForcibly();
            assertTrue(done, "制造模型工具子进程超时");
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(log));
            assertTrue(java.nio.file.Files.readString(log).contains("MANUFACTURING_DECISION_JVM_OK"));
        }
    }
}
