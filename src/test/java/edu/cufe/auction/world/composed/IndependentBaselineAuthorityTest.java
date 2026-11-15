package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.AgedDecisionTools;
import edu.cufe.auction.world.AgedIndustryConfig;
import edu.cufe.auction.world.AgedOperations;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.DigitalDecisionTools;
import edu.cufe.auction.world.DigitalIndustryConfig;
import edu.cufe.auction.world.DigitalOperations;
import edu.cufe.auction.world.HydroDecisionTools;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.FundState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 独立审查补测：最终十公司开局、银行 owner 绑定、经理输入隔离与真实任免后的权限。 */
class IndependentBaselineAuthorityTest {

    private static final long DAY = 86_400_000L;
    private static final long PERIOD = 30 * DAY;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    private static BigDecimal total(WorldRuntime world) {
        return world.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void legacyProfilesStillOpenWithoutDigitalOrAgedSubsets() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.open(dir.resolve("legacy-v1.journal"))) {
            var run = world.operationsRun().orElseThrow();
            assertNull(run.digital(), "旧 industry-runnable-v1 不得获得数字服务子账");
            assertNull(run.aged(), "旧 industry-runnable-v1 不得获得陈酿子账");
            world.advanceTo(10_000L);
        }
        try (WorldRuntime world = ComposedIndustryScenario.openWithHydro(dir.resolve("legacy-hydro.journal"))) {
            var run = world.operationsRun().orElseThrow();
            assertNotNull(run.hydro());
            assertNull(run.digital());
            assertNull(run.aged());
            world.advanceTo(10_000L);
        }
    }

    @Test
    void openingRegistersTenIssuersConservedSharesAndRealBankOwners() throws Exception {
        BigDecimal opening;
        long position;
        try (WorldRuntime world = ComposedIndustryScenario.openBaseline(dir.resolve("audit.journal"))) {
            OpeningRegistry registry = world.openingRegistry().orElseThrow();
            assertEquals(10, registry.securities().size(), "最终档必须只有十家发行人");
            assertEquals(10, registry.securities().stream()
                    .map(OpeningRegistry.Security::issuerOrganizationId).distinct().count());
            for (OpeningRegistry.Security security : registry.securities()) {
                long sum = registry.allocations().stream()
                        .filter(a -> a.symbol().equals(security.symbol()))
                        .mapToLong(OpeningRegistry.ShareAllocation::quantity).sum();
                assertEquals(security.issuedShares(), sum, "股份守恒：" + security.symbol());
            }
            Map<String, String> ownerOf = new HashMap<>();
            world.snapshot().accounts().forEach((id, account) -> ownerOf.put(id, account.ownerId()));
            for (OpeningRegistry.ShareAllocation allocation : registry.allocations()) {
                assertTrue(ownerOf.containsKey(allocation.accountId()), "持股账户必须真实开户：" + allocation.accountId());
                assertNotNull(registry.owner(ownerOf.get(allocation.accountId())),
                        "持股账户属主必须登记为财产主体：" + allocation.accountId());
            }
            for (OpeningRegistry.Security security : registry.securities()) {
                var board = world.boardOfCompany(security.issuerOrganizationId());
                assertTrue(board.isPresent(), "发行人必须有董事会：" + security.issuerOrganizationId());
                assertEquals(5, board.orElseThrow().board().charter().seats().size());
            }

            // 银行档案的现金账户 owner 必须与现金权威一致；数字/陈酿每条业务资金腿都要有映射。
            var decoded = world.bankOf().orElseThrow().decode(world.snapshot().accounts());
            var profile = decoded.config().profile();
            List<String> businessAccounts = List.of(
                    DigitalIndustryConfig.COMPANY_ACCOUNT, DigitalIndustryConfig.OPS_ACCOUNT,
                    DigitalIndustryConfig.CUSTOMER_ACCOUNT, DigitalIndustryConfig.CONTRACTOR_ACCOUNT,
                    DigitalIndustryConfig.SPONSOR_ACCOUNT,
                    AgedIndustryConfig.COMPANY_ACCOUNT, AgedIndustryConfig.SUPPLIER_ACCOUNT,
                    AgedIndustryConfig.CONVERSION_ACCOUNT, AgedIndustryConfig.FIXED_ACCOUNT,
                    AgedIndustryConfig.TERMINAL_ACCOUNT, AgedIndustryConfig.CHANNEL_ACCOUNT);
            for (String accountId : businessAccounts) {
                String owner = world.snapshot().accounts().get(accountId).ownerId();
                boolean mapped = profile.parties().values().stream().anyMatch(ref ->
                        ref.ownerId().equals(owner) && accountId.equals(ref.cashAccountId()));
                assertTrue(mapped, "银行档案缺账户 owner 绑定：" + accountId + " -> " + owner);
            }

            // 开局没有经理预算、没有已任命的经理、没有已生效的注资决议。
            assertTrue(world.openRoleSession(DigitalIndustryConfig.MANAGER_PERSON).appointments().isEmpty());
            assertTrue(world.openRoleSession(AgedIndustryConfig.MANAGER_PERSON).appointments().isEmpty());
            assertNull(world.boardOfCompany(DigitalIndustryConfig.COMPANY).orElseThrow().board()
                    .budget(0L, BoardModel.BudgetCategory.PRODUCTION_LABOR));
            assertNull(world.boardOfCompany(AgedIndustryConfig.COMPANY).orElseThrow().board()
                    .budget(0L, BoardModel.BudgetCategory.PROCUREMENT));
            assertNull(world.operationsRun().orElseThrow().digital()
                    .mandateOf(DigitalIndustryConfig.MANDATE_CAPITAL));

            // 两期无人运行 + 恢复：世界现金总数不变。
            opening = total(world);
            world.advanceTo(2 * PERIOD + 1);
            assertEquals(0, opening.compareTo(total(world)), "无人运行后世界现金必须守恒");
            position = world.snapshot().position();
        }
        try (WorldRuntime restored = ComposedIndustryScenario.openBaseline(dir.resolve("audit.journal"))) {
            assertEquals(position, restored.snapshot().position());
            assertEquals(0, opening.compareTo(total(restored)), "恢复后世界现金必须守恒");
        }
    }

    @Test
    void managerInputsHideFutureDemandInflowAndFrozenSuccessFlags() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openBaseline(dir.resolve("views.journal"))) {
            var operations = world.operationsRun().orElseThrow();
            var digital = operations.digital();
            JsonNode digitalView = JSON.readTree(DigitalDecisionTools.observation(digital,
                    world.snapshot().timeMillis(),
                    world.snapshot().accounts().get(DigitalIndustryConfig.COMPANY_ACCOUNT).cash(),
                    List.of(), null).toString());
            for (String forbidden : List.of("pilotPasses", "validationPasses", "customerBudgetPerPeriod",
                    "approvals", "mandates", "evidence", "stageEvidence")) {
                assertFalse(digitalView.has(forbidden), "数字经理投影不得包含：" + forbidden);
            }
            assertFalse(digitalView.get("actionFields").has("COMPLETE_PILOT"));
            assertFalse(digitalView.get("actionFields").has("CLOSE_PERIOD"));
            assertFalse(digitalView.get("actionFields").has("INJECT_CAPITAL"));

            var aged = operations.aged();
            var bank = world.bankOf().orElseThrow().decode(world.snapshot().accounts());
            JsonNode agedView = JSON.readTree(AgedDecisionTools.observation(aged,
                    new FundState(world.snapshot().accounts(), bank.state(), bank.book()),
                    world.snapshot().timeMillis(), List.of(), null).toString());
            assertFalse(agedView.has("demand"), "陈酿经理投影不得暴露冻结未来需求");
            assertFalse(agedView.toString().contains("\"period\":9"), "远期需求探针不得进入投影");
            assertFalse(agedView.toString().contains("\"units\":999"), "远期需求数量不得进入投影");
            assertFalse(agedView.toString().contains(AgedIndustryConfig.TERMINAL_ACCOUNT));
            assertFalse(agedView.toString().contains(AgedIndustryConfig.CHANNEL_ACCOUNT));
            assertFalse(agedView.get("actionFields").has("SELL_THROUGH"));
            assertFalse(agedView.get("actionFields").has("CLOSE_PERIOD"));

            var hydro = operations.hydro();
            JsonNode hydroView = JSON.readTree(HydroDecisionTools.observation(hydro,
                    world.boardOfCompany(HydroIndustryConfig.COMPANY).orElseThrow(),
                    world.snapshot().accounts().get(hydro.config().model().companyAccountId()).cash(),
                    List.of()).toString());
            for (String forbidden : List.of("inflow", "series", "segments", "inflowSeries")) {
                assertFalse(hydroView.has(forbidden), "水电经理投影不得暴露未来来水：" + forbidden);
            }
            assertFalse(hydroView.toString().contains("INFLOW"), "水电投影不得带出来水情景");
            assertTrue(hydroView.has("contract"));
            assertTrue(hydroView.has("physicalAsOf"));
        }
    }

    @Test
    void systemActionsWrongDurationAndForgedCommandIdentityAreRejected() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openBaseline(dir.resolve("authority.journal"))) {
            var digitalCandidate = world.openRoleSession(DigitalIndustryConfig.MANAGER_PERSON);
            long due = world.snapshot().timeMillis() + 2000L;
            assertThrows(IllegalArgumentException.class, () -> digitalCandidate.digitalAction(
                    "no-appt", "no-appt-task", due, DigitalIndustryConfig.COMPANY,
                    new DigitalOperations.Request(DigitalOperations.Action.BEGIN_PILOT, Map.of()), 1000));
            var agedCandidate = world.openRoleSession(AgedIndustryConfig.MANAGER_PERSON);
            assertThrows(IllegalArgumentException.class, () -> agedCandidate.agedAction(
                    "no-appt", "no-appt-task", due, AgedIndustryConfig.COMPANY,
                    new AgedOperations.Request(AgedOperations.Action.ORDER_RAW,
                            Map.of("orderId", "raw", "units", "10")), 1000));

            OperationsFixture.grantBudgetAndAppoint(world, DigitalIndustryConfig.COMPANY,
                    BaselineIndustryConfig.directors(DigitalIndustryConfig.COMPANY),
                    DigitalIndustryConfig.MANAGER_PERSON, "auth", List.of(0L));
            var manager = world.openRoleSession(DigitalIndustryConfig.MANAGER_PERSON);
            assertTrue(manager.appointments().containsKey(DigitalIndustryConfig.COMPANY));
            long start = world.snapshot().timeMillis();
            long done = start + 2000L;

            assertThrows(IllegalArgumentException.class, () -> manager.digitalAction(
                    "wrong-hours", "wrong-hours-task", done, DigitalIndustryConfig.COMPANY,
                    new DigitalOperations.Request(DigitalOperations.Action.BEGIN_PILOT, Map.of()), 2000));
            assertThrows(IllegalArgumentException.class, () -> manager.digitalAction(
                    "forged-complete", "forged-complete-task", done, DigitalIndustryConfig.COMPANY,
                    new DigitalOperations.Request(DigitalOperations.Action.COMPLETE_PILOT,
                            Map.of("evidenceId", DigitalIndustryConfig.EVIDENCE_PILOT)), 1000));
            assertThrows(IllegalArgumentException.class, () -> manager.digitalAction(
                    "forged-close", "forged-close-task", done, DigitalIndustryConfig.COMPANY,
                    new DigitalOperations.Request(DigitalOperations.Action.CLOSE_PERIOD,
                            Map.of("period", "0")), 1000));
            assertThrows(IllegalArgumentException.class, () -> manager.digitalAction(
                    "forged-capital", "forged-capital-task", done, DigitalIndustryConfig.COMPANY,
                    new DigitalOperations.Request(DigitalOperations.Action.INJECT_CAPITAL,
                            Map.of("amountCents", "100", "mandateId",
                                    DigitalIndustryConfig.MANDATE_CAPITAL)), 1000));

            assertEquals("SCHEDULED:real-task", manager.digitalAction("real", "real-task", done,
                    DigitalIndustryConfig.COMPANY,
                    new DigitalOperations.Request(DigitalOperations.Action.BEGIN_PILOT, Map.of()), 1000));
            // 同一命令标识换到期：内容不同必须拒绝，不能静默当原命令。
            assertThrows(IllegalArgumentException.class, () -> manager.digitalAction("real",
                    "real-task", done + 1000L, DigitalIndustryConfig.COMPANY,
                    new DigitalOperations.Request(DigitalOperations.Action.BEGIN_PILOT, Map.of()), 1000));
            // 同一任务标识换命令：也不得复用。
            assertThrows(IllegalArgumentException.class, () -> manager.digitalAction("other",
                    "real-task", done, DigitalIndustryConfig.COMPANY,
                    new DigitalOperations.Request(DigitalOperations.Action.BEGIN_PILOT, Map.of()), 1000));

            world.advanceTo(done);
            var report = world.operationsRun().orElseThrow().report("real-task:finish");
            assertNotNull(report, "到期必须留下真实回报");
            assertTrue(report.accepted(), report.reason());
            assertFalse(world.operationsRun().orElseThrow().digital()
                    .ledger().stages().isEmpty());
        }
    }
}
