package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.HydroModel;
import edu.cufe.auction.world.HydroOperations;
import edu.cufe.auction.world.HydroCompanyRun;
import edu.cufe.auction.world.ResourceCompanyRun;
import edu.cufe.auction.world.WorldJournal;
import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static edu.cufe.auction.world.composed.HydroIndustryConfig.*;
import static org.junit.jupiter.api.Assertions.*;

/** 三段有限年景的普通组合世界；时间推进、经营、合同和恢复都走实际入口。 */
class HydroHorizonTest {
    @TempDir Path directory;
    private static final long DAY = 86_400_000L;
    private static final List<Long> RATES = List.of(600L,180L,900L,400L,480L,120L,760L,320L,720L,240L,1000L,500L);

    private static HydroModel.Report checkWater(WorldRuntime w, long days) {
        var hydro = w.operationsRun().orElseThrow().hydro();
        var r = HydroIndustryWorldTest.report(w);
        long expected = 0;
        for (int i=0; i<RATES.size(); i++)
            expected += Math.max(0, Math.min(90, days - 90L*i)) * 24 * RATES.get(i);
        assertTrue(w.snapshot().timeMillis() >= days * DAY && w.snapshot().timeMillis() < days * DAY + HydroModel.PHYSICS_TICK_MILLIS);
        assertEquals(days * DAY, hydro.ledger().watermark());
        assertEquals(expected, r.inflowCubicMetres());
        assertEquals(expected, r.storageCubicMetres() - hydro.config().model().physical().initialStorageCubicMetres()
                + r.turbineCubicMetres() + r.mandatoryReleaseCubicMetres() + r.overflowSpillCubicMetres());
        assertTrue(ComposedCompanyReport.of(w, COMPANY).balanced());
        return r;
    }

    private static void assertEnds(WorldRuntime w, long day) {
        var last = w.operationsRun().orElseThrow().hydro();
        var error = assertThrows(IllegalStateException.class, () -> w.advanceTo((day + 1) * DAY));
        assertEquals("水电来水情景已结束，须提供新版本后继续", error.getMessage());
        assertEquals(last, w.operationsRun().orElseThrow().hydro());
        // 同日 +1ms 的外部需求结算可以先完成，水电尚未进入下一物理分钟。
        assertTrue(w.snapshot().timeMillis() < day * DAY + HydroModel.PHYSICS_TICK_MILLIS);
    }

    @Test void previousOrdinaryWorldStopsAtTheOriginalLastMinute() throws Exception {
        Path file = directory.resolve("old.journal");
        try (var w = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(file)) {
            w.advanceTo(360 * DAY);
            checkWater(w, 360);
            assertEnds(w, 360);
        }
        byte[] bytes = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(file)) {
            checkWater(w, 360);
            assertEnds(w, 360);
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void frozenOpeningRejectsMissingAndSubstitutedRainfallBeforeWriting() throws Exception {
        Path original = directory.resolve("right.journal");
        var correct = BaselineIndustryConfig.extendedHydro();
        var model = correct.model();
        var segments = new ArrayList<>(model.inflow().segments());
        var startOfSecondYear = segments.get(4);
        segments.set(4, new HydroModel.InflowSegment(startOfSecondYear.fromMillis(), startOfSecondYear.untilMillis(), 481));
        var changed = new HydroCompanyRun.Config(correct.companyPartyId(),correct.symbol(),new HydroModel.Config(
                model.companyOwnerId(), model.companyAccountId(), model.buyerAccountId(), model.contractorAccountId(),
                model.physical(),new HydroModel.InflowSeries(segments),model.contract(),model.terms()),
                correct.maintenanceMillis(),correct.maintenanceCost());
        ResourceCompanyRun.Config resource;
        String runId;
        try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(original)) {
            var company = w.operationsRun().orElseThrow().resource();
            resource = new ResourceCompanyRun.Config(company.companyPartyId(), company.symbol(),
                    company.ledger().config(), company.expansionOffer());
            runId = w.profile().manifest().runId();
        }
        try (var journal = new WorldJournal(original, runId)) {
            var opening = journal.commits().stream().filter(row -> row.commandId().equals("operations-open:bootstrap")).findFirst().orElseThrow();
            long end = journal.endOffsetOf(opening.position()-1);
            int i = 0;
            for (var wrong : Arrays.asList(null, BaselineIndustryConfig.hydro(), changed)) {
                Path partial = directory.resolve("candidate-" + i++ + ".journal");
                journal.exportPrefix(partial, end);
                var prefix = Files.readAllBytes(partial);
                try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(partial)) {
                    var ex = assertThrows(Exception.class, () -> w.initializeCompanyOperations("bootstrap",
                            ComposedManufacturingThreeScenario.companies(),resource,wrong,
                            BaselineIndustryConfig.digital(),BaselineIndustryConfig.aged()));
                    assertTrue(ex.getMessage().contains(wrong == null ? "缺少水电" : "冻结的 1080 日"), ex.getMessage());
                }
                assertArrayEquals(prefix,Files.readAllBytes(partial));
            }
        }
    }

    @Test void extendedOrdinaryWorldKeepsWaterAfterTheContractAndStillHasAFiniteEnd() throws Exception {
        var old = BaselineIndustryConfig.hydro().model();
        Path file = directory.resolve("extended.journal");
        HydroModel.Report firstYear, end;
        try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            var model = w.operationsRun().orElseThrow().hydro().config().model();
            assertEquals(old.inflow().segments(), model.inflow().segments().subList(0,4));
            assertEquals(12, model.inflow().segments().size());
            assertEquals(1080*DAY, model.inflow().endsAtMillis());
            assertEquals(old.physical(), model.physical());
            assertEquals(old.contract(), model.contract());
            assertEquals(old.terms(), model.terms());
            var p = w.autonomyRoleObservation(DIRECTORS.get(0));
            assertFalse(p.toString().contains("cubicMetresPerHour"));
            IssuerPeriodSectorFactsTest.govern(w, COMPANY, DIRECTORS, MANAGER);
            HydroIndustryWorldTest.act(w, "dispatch", new HydroOperations.Request(HydroOperations.Action.DISPATCH_FLOW,
                    Map.of("cubicMetresPerHour", "600")));
            w.advanceTo(360 * DAY);
            firstYear = checkWater(w, 360);
            assertTrue(firstYear.revenue().signum() > 0);
            w.advanceTo(361 * DAY);
            var day361 = checkWater(w, 361);
            assertTrue(day361.generatedKilowattHours().compareTo(firstYear.generatedKilowattHours()) > 0);
            assertEquals(firstYear.deliveredKilowattHours(), day361.deliveredKilowattHours());
            assertEquals(firstYear.revenue(), day361.revenue());
        }
        try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            checkWater(w, 361);
            for (long days : List.of(616L,1080L)) {
                w.advanceTo(days*DAY);
                var after = checkWater(w, days);
                assertEquals(firstYear.revenue(), after.revenue());
                assertEquals(firstYear.deliveredKilowattHours(), after.deliveredKilowattHours());
                assertTrue(after.generatedKilowattHours().compareTo(firstYear.generatedKilowattHours())>0);
            }
            end = HydroIndustryWorldTest.report(w);
            assertEnds(w, 1080);
        }
        byte[] bytes = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            assertEquals(end, checkWater(w, 1080));
            assertEnds(w, 1080);
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }
}
