package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static edu.cufe.auction.world.CompanyOperationsRun.Action.*;
import static edu.cufe.auction.world.ManufacturingIntake.*;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class ManufacturingIntakeLifecycleTest {
    @TempDir Path dir;
    private static Migration plan(WorldRuntime w, String... companies) {
        return new Migration(w.snapshot().position(), ACTIVE, RUNOFF, List.of(companies));
    }
    private static CompanyOperationsRun.Company company(WorldRuntime w, String id) {
        return w.operationsRun().orElseThrow().company(id);
    }
    private static void schedule(WorldRuntime w, String id, CompanyOperationsRun.Action action,
                                 CompanyOperationsRun.Fields fields) throws Exception {
        w.openRoleSession(TECH_MANAGER).operationsAction(id, id + "-task", w.snapshot().timeMillis() + 2000,
                TECH, action, fields, 1000);
    }
    private static CompanyOperationsRun.Fields sale(WorldRuntime w, String id) {
        long now = w.snapshot().timeMillis();
        return OperationsFixture.op().contract(id, "auto-oem", "P1", 1, 1, new BigDecimal("100.00"),
                now + OperationsFixture.DAY, now + 10 * OperationsFixture.DAY).build();
    }
    private static void finish(WorldRuntime w, String id) throws Exception {
        w.advanceTo(w.snapshot().timeMillis() + 2000);
        var report = w.operationsRun().orElseThrow().report(id + "-task:finish");
        assertTrue(report.accepted(), report.toString());
    }
    private static void appoint(WorldRuntime w) throws Exception {
        OperationsFixture.grantBudgetAndAppoint(w, TECH, TECH_DIRECTORS, TECH_MANAGER, "terms", List.of(0L));
    }

    @Test void twoCompaniesMigrateTogetherAndExistingSaleCanDeliverAndCollectAfterReopen() throws Exception {
        Path file = dir.resolve("ledger.journal");
        WorldRuntime.Snapshot end;
        try (var w = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) {
            appoint(w);
            schedule(w, "sale", SIGN_CONTRACT, sale(w, "sale"));
            var blocked = plan(w, TECH, STRUCT);
            assertThrows(IllegalArgumentException.class, () -> w.migrateManufacturingIntake(blocked, true));
            assertEquals(blocked.expectedPosition(), w.snapshot().position());
            w.advanceTo(w.snapshot().timeMillis() + 1000);
            assertThrows(IllegalArgumentException.class, () -> w.migrateManufacturingIntake(plan(w, TECH, STRUCT), true));
            w.advanceTo(w.snapshot().timeMillis() + 1000);
            assertEquals("SIGNED", w.operationsRun().orElseThrow().report("sale-task:finish").reason());
            schedule(w, "order", ORDER_MATERIAL, OperationsFixture.op().material("MAT-A", 2).build());
            finish(w, "order");
            w.advanceTo(company(w, TECH).ledger().purchases().get("order:po").arrivalAt());
            schedule(w, "batch", START_BATCH, OperationsFixture.op().product("P1", 1, 1).build());
            finish(w, "batch");
            var before = w.snapshot();
            var assets = company(w, TECH).ledger();
            var untouched = company(w, VERSA);
            var migration = plan(w, TECH, STRUCT);
            assertEquals(before.position(), w.migrateManufacturingIntake(migration, false));
            assertEquals(before, w.snapshot());
            assertEquals(before.position() + 1, w.migrateManufacturingIntake(migration, true));
            assertEquals(assets, company(w, TECH).ledger());
            assertEquals(untouched, company(w, VERSA));
            assertEquals(RUNOFF, company(w, STRUCT).currentIntakeVersion());
            assertEquals(RUNOFF, company(w, TECH).currentIntakeVersion());
            assertEquals(before.pending(), w.snapshot().pending());
            assertEquals(before.accounts(), w.snapshot().accounts());
            assertEquals(before.securities(), w.snapshot().securities());
            assertThrows(IllegalArgumentException.class, () -> schedule(w, "second", SIGN_CONTRACT, sale(w, "second")));
            assertFalse(w.operationsActions(TECH).contains("SIGN_CONTRACT"));
            var view = w.engineeringRoleInput(TECH_MANAGER).at("/companyOperations/0");
            assertFalse(view.path("actionFields").has("SIGN_CONTRACT"));
            assertTrue(view.path("customerOffers").isEmpty());
            assertTrue(view.path("actionFields").has("DELIVER"));
            end = w.snapshot();
        }
        try (var w = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) {
            assertEquals(end, w.snapshot());
            w.advanceTo(company(w, TECH).ledger().batches().get("batch:batch").completesAt());
            schedule(w, "deliver", DELIVER, OperationsFixture.op().delivery("sale", 1, "invoice").build());
            finish(w, "deliver");
            var invoice = company(w, TECH).ledger().receivables().get("invoice");
            w.advanceTo(invoice.dueAt());
            var cash = w.snapshot().accounts().get(TECH_CASH).cash();
            schedule(w, "collect", COLLECT, OperationsFixture.op().receivable("invoice").build());
            finish(w, "collect");
            assertEquals(cash.add(invoice.outstanding()), w.snapshot().accounts().get(TECH_CASH).cash());
            assertEquals(ManufacturingTemplateModel.ReceivableStatus.COLLECTED,
                    company(w, TECH).ledger().receivables().get("invoice").status());
            assertTrue(ComposedCompanyReport.of(w, TECH).balanced());
            end = w.snapshot();
        }
        try (var w = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) { assertEquals(end, w.snapshot()); }
    }

    @Test void staleUnknownDuplicateAndMixedVersionsNeverChangeAnyCompany() throws Exception {
        Path file = dir.resolve("batch.journal");
        try (var w = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) {
            var both = plan(w, TECH, VERSA);
            assertThrows(IllegalArgumentException.class, () -> plan(w, TECH, TECH));
            assertThrows(IllegalArgumentException.class, () -> new Migration(both.expectedPosition(), ACTIVE, "unknown", both.companyIds()));
            assertThrows(IllegalArgumentException.class, () -> w.migrateManufacturingIntake(plan(w, TECH, "missing"), true));
            assertEquals(both.expectedPosition(), w.snapshot().position());
            w.migrateManufacturingIntake(plan(w, TECH), true);
            var before = w.snapshot();
            long bytes = Files.size(file);
            assertThrows(IllegalArgumentException.class, () -> w.migrateManufacturingIntake(both, true));
            assertThrows(IllegalArgumentException.class, () -> w.migrateManufacturingIntake(plan(w, VERSA, TECH), true));
            assertEquals(before, w.snapshot());
            assertEquals(bytes, Files.size(file));
            assertEquals(ACTIVE, company(w, VERSA).currentIntakeVersion());
        }
        try (var w = ComposedIndustryScenario.openIntegratedOperating(dir.resolve("old.journal"))) {
            var snapshot = w.snapshot();
            assertThrows(IllegalArgumentException.class, () -> w.migrateManufacturingIntake(plan(w, TECH), true));
            assertEquals(snapshot, w.snapshot());
        }
        assertThrows(Exception.class, () -> ComposedIndustryScenario.openIntegratedOperating(file));
    }

    @Test void outstandingInvoiceAndScheduledCollectionSurviveTheRulesChange() throws Exception {
        Path file = dir.resolve("invoice.journal");
        BigDecimal before, amount;
        long finishAt;
        try (var w = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) {
            appoint(w);
            schedule(w, "order", ORDER_MATERIAL, OperationsFixture.op().material("MAT-A", 2).build());
            finish(w, "order");
            w.advanceTo(company(w, TECH).ledger().purchases().get("order:po").arrivalAt());
            schedule(w, "batch", START_BATCH, OperationsFixture.op().product("P1", 1, 1).build());
            finish(w, "batch");
            w.advanceTo(company(w, TECH).ledger().batches().get("batch:batch").completesAt());
            schedule(w, "sale", SIGN_CONTRACT, sale(w, "sale"));
            finish(w, "sale");
            w.advanceTo(company(w, TECH).ledger().contracts().get("sale").promisedAt());
            schedule(w, "ship", DELIVER, OperationsFixture.op().delivery("sale", 1, "invoice").build());
            finish(w, "ship");
            var invoice = company(w, TECH).ledger().receivables().get("invoice");
            amount = invoice.outstanding();
            assertTrue(amount.signum() > 0);
            w.advanceTo(invoice.dueAt());
            before = w.snapshot().accounts().get(TECH_CASH).cash();
            finishAt = w.snapshot().timeMillis() + 2000;
            schedule(w, "receive", COLLECT, OperationsFixture.op().receivable("invoice").build());
            var pending = w.snapshot().pending();
            w.migrateManufacturingIntake(plan(w, TECH), true);
            assertEquals(invoice, company(w, TECH).ledger().receivables().get("invoice"));
            assertEquals(before, w.snapshot().accounts().get(TECH_CASH).cash());
            assertEquals(pending, w.snapshot().pending());
        }
        try (var w = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) {
            w.advanceTo(finishAt);
            assertTrue(w.operationsRun().orElseThrow().report("receive-task:finish").accepted());
            assertEquals(before.add(amount), w.snapshot().accounts().get(TECH_CASH).cash());
        }
    }

    @Test void onlyAffectedActiveManagersOpenDecisionBlocksMigration() throws Exception {
        try (var w = ComposedIndustryScenario.openManufacturingLifecycleOperating(dir.resolve("decision.journal"))) {
            appoint(w);
            PharmaDecisionToolTest.open(w, TECH_MANAGER, "pending-decision");
            var snapshot = w.snapshot();
            assertThrows(IllegalArgumentException.class, () -> w.migrateManufacturingIntake(plan(w, TECH, STRUCT), true));
            assertEquals(snapshot, w.snapshot());
            w.migrateManufacturingIntake(plan(w, STRUCT), true);
            assertEquals(ACTIVE, company(w, TECH).currentIntakeVersion());
        }
    }

    @Test void reopenRejectsAValidJournalWhoseMigrationEventOmitsOneActualTarget() throws Exception {
        Path original = dir.resolve("whole.journal"), altered = dir.resolve("incomplete.journal");
        try (var w = ComposedIndustryScenario.openManufacturingLifecycleOperating(original)) {
            w.migrateManufacturingIntake(plan(w, TECH, STRUCT), true);
        }
        try (var source = new WorldJournal(original, RunManifest.MANUFACTURING_LIFECYCLE_RUN_ID);
             var target = new WorldJournal(altered, RunManifest.MANUFACTURING_LIFECYCLE_RUN_ID)) {
            for (var frame : source.commits()) {
                var events = new java.util.ArrayList<>(frame.events());
                if (frame.commandId().startsWith("manufacturing-intake:")) {
                    for (int i = 0; i < events.size(); i++) {
                        var event = events.get(i);
                        if ("MANUFACTURING_INTAKE_MIGRATED_V1".equals(event.type())) {
                            var json = new com.fasterxml.jackson.databind.ObjectMapper();
                            var content = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(event.payload());
                            content.putArray("companyIds").add(TECH);
                            events.set(i, new WorldEvent(event.eventId(), event.type(), content.toString()));
                        }
                    }
                }
                target.commit(frame.commandId(), frame.commandContent(), frame.result(), events);
            }
        }
        var error = assertThrows(java.io.IOException.class, () -> ComposedIndustryScenario.openManufacturingLifecycleOperating(altered));
        assertTrue(error.getMessage().contains("迁移"), error.getMessage());
    }
}
