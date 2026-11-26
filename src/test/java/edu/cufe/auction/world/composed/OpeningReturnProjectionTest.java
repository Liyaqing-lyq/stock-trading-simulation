package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class OpeningReturnProjectionTest {
    @TempDir Path dir;
    private static final List<String> FUND_OWNERS = List.of("nina", OpenFundUnits.PRODUCT, OpenFundUnits.FOUNDER);

    /** 每个检验点重算从第 1 帧开始的完整流水，并确保两种读取均没有写盘。 */
    private static Map<String, WorldRuntime.OpeningReturn> compare(WorldRuntime w, Path file,
                                                                  List<String> owners) throws Exception {
        long size = Files.size(file);
        long cut = w.snapshot().position();
        var projected = w.openingReturns();
        var full = w.performances(owners, 1, cut);
        for (String owner : owners) {
            var expected = full.get(owner);
            var actual = projected.get(owner);
            assertNull(expected.error(), owner + "@" + cut);
            assertNull(actual.error(), owner + "@" + cut);
            var period = expected.value();
            assertEquals(new PerformanceModel.Summary(period.status(), period.returnRate(), period.index(),
                    period.maxDrawdown(), period.segments().size()), actual.value(), owner + "@" + cut);
        }
        assertEquals(size, Files.size(file));
        assertEquals(cut, w.snapshot().position());
        return projected;
    }

    @Test void prepaidSubscriptionsReservedUnitsAndPaidReceivablesFollowTheirOwnFinancialCut() throws Exception {
        Path file = dir.resolve("fund.journal");
        Map<String, WorldRuntime.OpeningReturn> finalSummary;
        try (var w = ComposedIndustryScenario.openOpenFundOperating(file)) {
            compare(w, file, FUND_OWNERS);
            w.requestFundUnits("nina", "buy", OpenFundUnits.Kind.SUBSCRIBE, new BigDecimal("120"), w.snapshot().position());
            assertEquals("120.00", w.openFundView("nina").get("pendingSubscription"));
            compare(w, file, FUND_OWNERS);
            w.advanceTo((long) w.openFundView("nina").get("nextCutoffMillis"));
            assertEquals("120.0000", w.openFundView("nina").get("ownUnits"));
            compare(w, file, FUND_OWNERS);
            w.requestFundUnits("nina", "redeem", OpenFundUnits.Kind.REDEEM, new BigDecimal("50"), w.snapshot().position());
            assertEquals("70.0000", w.openFundView("nina").get("availableUnits"));
            compare(w, file, FUND_OWNERS);
            w.advanceTo((long) w.openFundView("nina").get("nextCutoffMillis"));
            assertEquals("50.00", w.openFundView("nina").get("receivable"));
            compare(w, file, FUND_OWNERS);
            w.advanceTo((long) w.openFundView("nina").get("nextPaymentMillis"));
            assertEquals("0.00", w.openFundView("nina").get("receivable"));
            finalSummary = compare(w, file, FUND_OWNERS);
        }
        var before = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openOpenFundOperating(file)) {
            assertEquals(finalSummary, compare(w, file, FUND_OWNERS));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void registeredBookedAndPaidDividendsAgreeWithFullHistoryOnBothGenerations(boolean current) throws Exception {
        Path file = dir.resolve("cash.journal");
        List<String> owners = List.of("eng-a", "investor", "nina");
        Map<String, WorldRuntime.OpeningReturn> end;
        try (var w = current ? ComposedIndustryScenario.openTenCompanyAutonomy(file)
                : ComposedIndustryScenario.openOriginalTenCompanyAutonomy(file)) {
            long now = w.snapshot().timeMillis();
            ExDividendPriceBandTest.approve(w, now);
            compare(w, file, owners);
            w.advanceTo(now + 21000);
            assertEquals(DividendModel.Phase.EX_BOOKED, w.snapshot().dividends().orElseThrow().schemes().get("cash-scheme").phase());
            assertTrue(w.valuation("investor").dividendReceivable().signum() > 0);
            compare(w, file, owners);
            var balance = w.snapshot().accounts().get("INV").cash();
            w.advanceTo(now + 22000);
            assertEquals(DividendModel.Phase.SETTLED, w.snapshot().dividends().orElseThrow().schemes().get("cash-scheme").phase());
            assertEquals(0, w.valuation("investor").dividendReceivable().signum());
            assertTrue(w.snapshot().accounts().get("INV").cash().compareTo(balance) > 0);
            end = compare(w, file, owners);
        }
        var before = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            assertEquals(current ? RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID : RunManifest.ORIGINAL_TEN_COMPANY_RUN_ID,
                    w.profile().manifest().runId());
            assertEquals(end, compare(w, file, owners));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void inheritedPrepaymentIsConfirmedOnlyOnTheBranchThatKeepsTheOrder() throws Exception {
        Path file = dir.resolve("parent.journal");
        var repository = new BranchRepository(dir.resolve("runs"));
        WorldRuntime.BranchReceipt receipt;
        Map<String, WorldRuntime.OpeningReturn> ending;
        try (var parent = ComposedIndustryScenario.openOpenFundOperating(file)) {
            parent.requestFundUnits("nina", "subscription", OpenFundUnits.Kind.SUBSCRIBE,
                    new BigDecimal("75"),parent.snapshot().position());
            long cut = parent.snapshot().position(), next = (long) parent.openFundView("nina").get("nextCutoffMillis");
            compare(parent, file, FUND_OWNERS);
            receipt = parent.createBranch("fund-choice", cut, BranchOrigin.Kind.OPERATOR_BRANCH, "份额决定");
            byte[] sealed = Files.readAllBytes(receipt.sealedPrefix());
            try (var child = repository.open("fund-choice", parent.profile())) {
                assertEquals(cut + 1, child.snapshot().position());
                compare(child, receipt.childFile(), FUND_OWNERS);
                parent.cancelFundUnits("nina", "subscription", parent.snapshot().position());
                parent.advanceTo(next);
                child.advanceTo(next);
                assertEquals("0.0000",parent.openFundView("nina").get("ownUnits"));
                assertEquals("75.0000",child.openFundView("nina").get("ownUnits"));
                compare(parent, file, FUND_OWNERS);
                ending = compare(child, receipt.childFile(), FUND_OWNERS);
            }
            assertArrayEquals(sealed, Files.readAllBytes(receipt.sealedPrefix()));
        }
        byte[] bytes = Files.readAllBytes(receipt.childFile());
        try(var parent=ComposedIndustryScenario.openOpenFundOperating(file);
            var child=repository.open("fund-choice",parent.profile())) {
            assertEquals(ending,compare(child,receipt.childFile(),FUND_OWNERS));
        }
        assertArrayEquals(bytes,Files.readAllBytes(receipt.childFile()));
    }
}
