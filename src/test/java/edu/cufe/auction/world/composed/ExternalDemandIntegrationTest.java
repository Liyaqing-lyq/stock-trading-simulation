package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static edu.cufe.auction.world.CompanyOperationsRun.Action.*;
import static org.junit.jupiter.api.Assertions.*;

/** 外部需求集成机制测试；所有经营事实通过真实治理与经营入口产生。 */
class ExternalDemandIntegrationTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DAY = 86_400_000L;

    @Test void newOpeningAddsFiniteExternalCashAndPreservesControlJson() throws Exception {
        BigDecimal oldTotal;
        try (var old = ComposedIndustryScenario.openDeliberativeAutonomous(dir.resolve("control.journal"))) {
            oldTotal = total(old);
            assertFalse(JSON.valueToTree(old.operationsRun().orElseThrow()).has("externalDemand"));
            assertFalse(old.profile().manifest().dataVersions().containsKey("external-demand"));
        }
        try (var world = ComposedIndustryScenario.openOperatingAutonomous(dir.resolve("operating.journal"))) {
            assertEquals("v1", world.profile().manifest().dataVersions().get("external-demand"));
            assertEquals("deliberation-v2", world.profile().manifest().dataVersions().get("role-context"));
            assertEquals(oldTotal.add(new BigDecimal("90000.00")), total(world));
            assertEquals(new BigDecimal("90000.00"), world.snapshot().accounts().get("EXT-FINAL-DEMAND").cash());
            assertEquals(1, world.snapshot().pending().stream().filter(t -> t.kind().equals("EXTERNAL_DEMAND_SETTLE_V1")).count());
            assertFalse(world.autonomyRoleObservation("mfgtech-d1").toString().contains("EXT-FINAL-DEMAND"));
        }
    }

    @Test void noDeliveryRunsSystemChecksWithoutInventingSalesAndReopensExactly() throws Exception {
        Path journal = dir.resolve("no-delivery.journal");
        WorldRuntime.Snapshot snapshot;
        CompanyOperationsRun operations;
        try (var world = ComposedIndustryScenario.openOperatingAutonomous(journal)) {
            BigDecimal opening = total(world);
            world.advanceTo(4 * DAY + 1);
            operations = world.operationsRun().orElseThrow();
            assertTrue(operations.externalDemand().sales().isEmpty());
            assertTrue(operations.externalDemand().incomeDistributions().isEmpty());
            assertEquals(4 * DAY + 1, operations.externalDemand().watermark());
            assertEquals(opening, total(world));
            assertEquals(1, dailyTasks(world).size());
            assertEquals(5 * DAY + 1, dailyTasks(world).get(0).dueTimeMillis());
            snapshot = world.snapshot();
        }
        byte[] committed = Files.readAllBytes(journal);
        try (var world = ComposedIndustryScenario.openOperatingAutonomous(journal)) {
            assertEquals(snapshot, world.snapshot());
            assertEquals(operations, world.operationsRun().orElseThrow());
            world.advanceTo(snapshot.timeMillis());
            assertEquals(snapshot, world.snapshot());
        }
        assertArrayEquals(committed, Files.readAllBytes(journal));
    }

    @Test void realManufacturingTurnoverPaysCustomerAndCirculatesSourceIncomeAcrossPeriods() throws Exception {
        Path journal = dir.resolve("lifecycle.journal");
        BigDecimal openingTotal;
        CompanyOperationsRun firstSale;
        try (var world = ComposedIndustryScenario.openOperatingAutonomous(journal)) {
            openingTotal = total(world);
            OperationsFixture.grantBudgetAndAppoint(world, TECH, TECH_DIRECTORS, TECH_MANAGER, "ext-govern", List.of(0L, 1L));
            act(world, "ext-order", ORDER_MATERIAL, OperationsFixture.op().material("MAT-A", 40).build());
            var po = ledger(world).purchases().values().iterator().next();
            world.advanceTo(po.arrivalAt());
            act(world, "ext-batch", START_BATCH, OperationsFixture.op().product("P1", 1, 20).build());
            world.advanceTo(ledger(world).batches().values().iterator().next().completesAt());
            deliver(world, "sale-0", 10);
            var receivable = ledger(world).receivables().get("sale-0-ar");
            world.advanceTo(receivable.recognizedAt() + 3 * DAY - 1);
            assertTrue(world.operationsRun().orElseThrow().externalDemand().sales().isEmpty());
            long check = dailyTasks(world).get(0).dueTimeMillis();
            // 先让整日水电费用等既有任务结算，随后只跨越最终需求的日结时点。
            world.advanceTo(check - 1);
            var bankBefore = world.bankOf().orElseThrow();
            var actionsBefore = world.bankBook();
            BigDecimal customerBefore = world.snapshot().accounts().get(CUST_AUTO).cash();
            BigDecimal companyBefore = world.snapshot().accounts().get(TECH_CASH).cash();
            world.advanceTo(check);
            firstSale = world.operationsRun().orElseThrow();
            assertEquals(1, firstSale.externalDemand().sales().size());
            assertEquals(new BigDecimal("1250.00"), firstSale.externalDemand().sales().get(0).cashPaid());
            assertEquals(customerBefore.add(new BigDecimal("1250.00")), world.snapshot().accounts().get(CUST_AUTO).cash());
            assertEquals(companyBefore, world.snapshot().accounts().get(TECH_CASH).cash());
            assertEquals(receivable, ledger(world).receivables().get("sale-0-ar"));
            assertEquals(bankBefore, world.bankOf().orElseThrow());
            assertEquals(actionsBefore, world.bankBook());
            assertEquals(openingTotal, total(world));
        }
        try (var world = ComposedIndustryScenario.openOperatingAutonomous(journal)) {
            assertEquals(firstSale, world.operationsRun().orElseThrow());
            world.advanceTo(30 * DAY);
            assertEquals(1, world.operationsRun().orElseThrow().externalDemand().sales().size());
            BigDecimal sourceBefore = world.snapshot().accounts().get(SUP_METAL).cash();
            assertEquals(new BigDecimal("480.00"), sourceBefore);
            BigDecimal buyerBefore = world.snapshot().accounts().get(ExternalDemandOpening.ACCOUNT_ID).cash();
            world.advanceTo(30 * DAY + 1);
            var distributions = world.operationsRun().orElseThrow().externalDemand().incomeDistributions();
            assertEquals(6, distributions.size());
            var metal = distributions.stream().filter(r -> r.sourceAccountId().equals(SUP_METAL)).findFirst().orElseThrow();
            assertEquals(new BigDecimal("384.00"), metal.cashPaid());
            assertEquals(sourceBefore, metal.sourceCashBefore());
            BigDecimal distributed = distributions.stream().map(ExternalDemandRun.IncomeReceipt::cashPaid).reduce(new BigDecimal("0.00"), BigDecimal::add);
            assertEquals(buyerBefore.add(distributed), world.snapshot().accounts().get(ExternalDemandOpening.ACCOUNT_ID).cash());
            deliver(world, "sale-1", 10);
            var second = ledger(world).receivables().get("sale-1-ar");
            world.advanceTo(second.recognizedAt() + 4 * DAY);
            var run = world.operationsRun().orElseThrow().externalDemand();
            assertEquals(2, run.sales().size());
            assertEquals(1, run.sales().get(1).period());
            assertEquals(6, run.incomeDistributions().size());
            assertEquals(openingTotal, total(world));
        }
        try (var world = ComposedIndustryScenario.openOperatingAutonomous(journal)) {
            var run = world.operationsRun().orElseThrow().externalDemand();
            assertEquals(2, run.sales().size());
            assertEquals(6, run.incomeDistributions().size());
            assertEquals(openingTotal, total(world));
        }
    }

    @Test void originalSourceSavingsMustBeZeroBeforeRuntimeCanInitialize() throws Exception {
        BranchProfile profile;
        try (var world = ComposedIndustryScenario.openOperatingAutonomous(dir.resolve("trusted.journal"))) { profile = world.profile(); }
        for (String badAccount : List.of(SUP_METAL, ExternalDemandOpening.ACCOUNT_ID)) {
            List<CashLedger.OpeningAccount> changed = profile.openings().stream().map(a -> a.accountId().equals(badAccount)
                    ? new CashLedger.OpeningAccount(a.accountId(), a.ownerId(), a.cash().add(BigDecimal.ONE), a.source()) : a).toList();
            try (var world = ComposedWorldScenario.open(dir.resolve(badAccount + ".journal"), profile.manifest(), changed,
                    profile.registry(), profile.bankOpening(), profile.decisions(),
                    new ComposedWorldScenario.Extras(profile.companyCharters(), List.of(), ComposedManufacturingThreeScenario.readers()))) {
                var failure = assertThrows(IllegalArgumentException.class, () -> world.initializeCompanyOperations("bad",
                        ComposedManufacturingThreeScenario.companies(), ComposedIndustryScenario.resourceConfig(true),
                        BaselineIndustryConfig.hydro(), BaselineIndustryConfig.digital(), BaselineIndustryConfig.aged()));
                assertTrue(failure.getMessage().contains("原始账户禀赋或属主不符"), failure.getMessage());
                assertTrue(world.operationsRun().isEmpty());
            }
        }
    }

    @Test void oldConstructorsAndAllWithMethodsPreserveOnlyExplicitExternalTail() throws Exception {
        try (var world = ComposedIndustryScenario.openOperatingAutonomous(dir.resolve("with.journal"))) {
            var run = world.operationsRun().orElseThrow();
            var ext = run.externalDemand();
            assertSame(ext, run.withResource(run.resource()).externalDemand());
            assertSame(ext, run.withHydro(run.hydro()).externalDemand());
            assertSame(ext, run.withDigital(run.digital()).externalDemand());
            assertSame(ext, run.withAged(run.aged()).externalDemand());
            assertSame(ext, run.withLedger(TECH, run.company(TECH).ledger()).externalDemand());
            assertSame(ext, run.withReport(new CompanyOperationsRun.ReportEntry("r", "c", TECH, "TEST", false, "TEST", "", BigDecimal.ZERO, 0, 1)).externalDemand());
            var old = new CompanyOperationsRun(run.rulesVersion(), run.companies(), run.reports(), run.resource(), run.hydro(), run.digital(), run.aged());
            String json = JSON.writeValueAsString(old);
            assertFalse(json.contains("externalDemand"));
            assertNull(JSON.readValue(json, CompanyOperationsRun.class).externalDemand());
            assertEquals(run, JSON.readValue(JSON.writeValueAsString(run), CompanyOperationsRun.class));
        }
    }

    @Test void unknownOrImplicitExternalVersionIsRejected() throws Exception {
        try (var world = ComposedIndustryScenario.openDeliberativeAutonomous(dir.resolve("versions.journal"))) {
            var manifest = world.profile().manifest();
            var data = new TreeMap<>(manifest.dataVersions());
            data.put("external-demand", "v2");
            assertThrows(IllegalArgumentException.class, () -> new RunManifest(manifest.runId(), manifest.seed(), manifest.rulesVersion(), manifest.moduleVersions(), data, manifest.modelConfigVersion()));
            data.put("external-demand", "v1"); data.put("role-context", "evidence-v1");
            assertThrows(IllegalArgumentException.class, () -> new RunManifest(manifest.runId(), manifest.seed(), manifest.rulesVersion(), manifest.moduleVersions(), data, manifest.modelConfigVersion()));
        }
    }

    @Test void validHashCannotAuthorizeForgedCashReceiptConfigurationOrNextTask() throws Exception {
        Path source = dir.resolve("clean.journal");
        try (var world = ComposedIndustryScenario.openOperatingAutonomous(source)) { world.advanceTo(4 * DAY + 1); }
        byte[] clean = Files.readAllBytes(source);
        for (String change : List.of("cash", "receipt", "config", "task")) {
            Path target = dir.resolve("tampered-" + change + ".journal");
            Files.write(target, tamper(clean, change));
            Exception rejected = assertThrows(Exception.class, () -> ComposedIndustryScenario.openOperatingAutonomous(target));
            String messages = rejected.toString();
            for (Throwable cause = rejected.getCause(); cause != null; cause = cause.getCause()) messages += " " + cause;
            assertFalse(messages.contains("校验和"), messages);
            assertTrue(messages.contains("不一致") || messages.contains("重演") || messages.contains("改写"), messages);
        }
    }

    private static byte[] tamper(byte[] bytes, String change) throws Exception {
        List<ObjectNode> frames = new ArrayList<>();
        for (int offset = 0; offset < bytes.length;) {
            int header = ByteBuffer.wrap(bytes, offset, 4).getInt();
            int length = header & Integer.MAX_VALUE;
            byte[] payload = Arrays.copyOfRange(bytes, offset + 8, offset + 8 + length);
            if (header < 0) try (var gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) { payload = gzip.readAllBytes(); }
            frames.add((ObjectNode) JSON.readTree(payload));
            offset += 8 + length + 32;
        }
        ObjectNode frame = frames.stream().filter(f -> f.path("commandId").asText().equals("task:external-demand:day:4")).findFirst().orElseThrow();
        ObjectNode event = (ObjectNode) java.util.stream.StreamSupport.stream(frame.path("events").spliterator(), false)
                .filter(e -> e.path("type").asText().equals("STATE_SNAPSHOT_V1")).findFirst().orElseThrow();
        ObjectNode state = (ObjectNode) JSON.readTree(event.path("payload").asText());
        ObjectNode external = (ObjectNode) state.path("companyOperations").path("externalDemand");
        if (change.equals("cash") || change.equals("receipt")) {
            BigDecimal amount = change.equals("cash") ? new BigDecimal("1.00") : new BigDecimal("1.25");
            ObjectNode buyer = (ObjectNode) state.path("accounts").path(ExternalDemandOpening.ACCOUNT_ID);
            ObjectNode customer = (ObjectNode) state.path("accounts").path(CUST_AUTO);
            buyer.put("cash", buyer.path("cash").decimalValue().subtract(amount));
            customer.put("cash", customer.path("cash").decimalValue().add(amount));
        }
        if (change.equals("receipt")) external.withArray("sales").add(JSON.valueToTree(new ExternalDemandRun.SaleReceipt(
                "external-demand:sale:0", TECH, "invented", "invented", "auto-oem", TECH_CASH,
                ExternalDemandOpening.ACCOUNT_ID, CUST_AUTO, 1, new BigDecimal("1.25"), new BigDecimal("1.25"),
                new BigDecimal("1.00"), new BigDecimal("0.25"), DAY + 1, 4 * DAY + 1, 4 * DAY + 1, 0)));
        if (change.equals("config")) ((ObjectNode) external.path("config")).put("demandUnitsPerPeriod", 199);
        if (change.equals("task")) for (var task : state.withArray("pending")) if (task.path("kind").asText().equals("EXTERNAL_DEMAND_SETTLE_V1"))
            ((ObjectNode) task).put("dueTimeMillis", 5 * DAY + 2);
        event.put("payload", JSON.writeValueAsString(state));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (ObjectNode value : frames) {
            byte[] payload = JSON.writeValueAsBytes(value);
            result.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length).array());
            result.write(payload); result.write(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        return result.toByteArray();
    }

    private static List<PendingTask> dailyTasks(WorldRuntime world) {
        return world.snapshot().pending().stream().filter(t -> t.kind().equals(PendingTask.EXTERNAL_DEMAND_SETTLE_V1)).toList();
    }
    private static ManufacturingTemplateModel.State ledger(WorldRuntime world) { return world.operationsRun().orElseThrow().company(TECH).ledger(); }
    private static void act(WorldRuntime world, String id, CompanyOperationsRun.Action action, CompanyOperationsRun.Fields fields) throws Exception {
        long due = world.snapshot().timeMillis() + 2000;
        world.scheduleOperationsAction(id, id + "-task", due, TECH, TECH_MANAGER, action.name(), fields, 1000);
        world.advanceTo(due);
        var report = world.operationsRun().orElseThrow().report(id + "-task:finish");
        assertNotNull(report, id);
        assertTrue(report.accepted(), report.reason());
    }
    private static void deliver(WorldRuntime world, String id, long quantity) throws Exception {
        long now = world.snapshot().timeMillis();
        act(world, id, SIGN_CONTRACT, OperationsFixture.op().contract(id, "auto-oem", "P1", 1, quantity,
                new BigDecimal("100.00"), now + DAY, now + 10 * DAY).build());
        world.advanceTo(now + DAY);
        act(world, id + "-delivery", DELIVER, OperationsFixture.op().delivery(id, quantity, id + "-ar").build());
    }

    private static BigDecimal total(WorldRuntime world) {
        return world.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
    }
}
