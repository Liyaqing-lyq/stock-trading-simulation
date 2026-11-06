package edu.cufe.auction.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧档案配置闸：schema 9 的合法开局配上新 live profile 的决策配置，必须在接触/创建 journal 前被拒绝。
 *
 * <p>同一个完整合法开局先用 scripted 配置打开作为对照，证明拒绝不是缺模块或夹具不完整。</p>
 */
class WorldRuntimeLiveProfileGuardTest {

    private static final String RUN = "live-guard-run";

    @Test
    @DisplayName("对照：同一 schema9 开局用 scripted 配置可以真正建档案")
    void scriptedControlActuallyOpens() throws Exception {
        Path file = dir("control").resolve("world.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(), openings(), registry(),
                manufacturing(), charter(), roles(), information(), scriptedConfig())) {
            assertTrue(runtime.snapshot().decisions().isPresent());
        }
        assertTrue(Files.exists(file), "合法对照必须真的创建 journal");
    }

    @Test
    @DisplayName("live profile 配置在创建 journal 前被拒绝")
    void liveProfileRejectedBeforeJournal() throws Exception {
        Path file = dir("live").resolve("world.journal");
        assertThrows(RuntimeException.class, () -> new WorldRuntime(file, manifest(), openings(),
                registry(), manufacturing(), charter(), roles(), information(), liveConfig()));
        assertFalse(Files.exists(file), "拒绝必须发生在创建 journal 之前");
    }

    @Test
    @DisplayName("已存在的旧档案也不能被 live 配置接管")
    void existingJournalNotUpgraded() throws Exception {
        Path file = dir("existing").resolve("world.journal");
        try (WorldRuntime ignored = new WorldRuntime(file, manifest(), openings(), registry(),
                manufacturing(), charter(), roles(), information(), scriptedConfig())) {
            assertTrue(Files.exists(file));
        }
        assertThrows(RuntimeException.class, () -> new WorldRuntime(file, manifest(), openings(),
                registry(), manufacturing(), charter(), roles(), information(), liveConfig()));
    }

    @Test
    @DisplayName("旧 Runtime 的正常、复用和旧代次回执均不得写入 UNKNOWN 新格式")
    void unknownCostCannotEnterLegacyJournal() throws Exception {
        for (String mode : List.of("pending", "duplicate", "stale")) {
            Path file = dir("cost-" + mode).resolve("world.journal");
            var cap = new DecisionModel.Usage(5, 1_000, 500, money("0.00"));
            var config = new DecisionModel.Config("SCRIPTED", "old", "free", Map.of("wake", 1L),
                    Map.of(RoleTradingView.TOOL_READING_START, 1L,
                            RoleTradingView.TOOL_READING_CANCEL, 1L,
                            RoleTradingView.TOOL_RESEARCH_SUBMIT, 1L),
                    new DecisionModel.Limits(1, 100_000, 1_000, 200, 100, 4, 1, 1_000),
                    new DecisionModel.Budget(cap, Map.of("alice", cap)));
            try (WorldRuntime runtime = new WorldRuntime(file, manifest(), openings(), registry(),
                    manufacturing(), charter(), roles(), information(), config)) {
                runtime.beginDecisionSession("begin");
                runtime.openDecisions("open", List.of(new DecisionRun.Request("alice", "action", "wake")));
                var attempt = runtime.snapshot().decisions().orElseThrow().state()
                        .decisions().get(0).lastAttempt();
                var good = new DecisionModel.Reply(RUN, "action", attempt.attemptId(), 1,
                        attempt.recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                        new DecisionModel.Usage(1, 20, 10, money("0.00")), "scripted");
                if (mode.equals("duplicate")) {
                    runtime.acceptDecisionReply("control", good);
                    assertEquals(DecisionModel.Outcome.WAIT, runtime.snapshot().decisions()
                            .orElseThrow().state().decisions().get(0).outcome());
                } else if (mode.equals("stale")) {
                    runtime.beginDecisionSession("next-generation");
                    assertTrue(runtime.snapshot().decisions().orElseThrow().state()
                            .recoveryGeneration() > attempt.recoveryGeneration());
                }
                var before = runtime.snapshot();
                var unknown = new DecisionModel.Reply(RUN, "action", attempt.attemptId(), 1,
                        attempt.recoveryGeneration(), good.body(),
                        DecisionModel.Usage.unknownCost(1, 20, 10), "provider");
                assertEquals("REJECTED:USAGE_COST_INVALID", runtime.acceptDecisionReply("bad", unknown));
                assertEquals(before, runtime.snapshot());
            }
            assertFalse(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1)
                    .contains("costStatus"));
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static Path dir(String name) throws Exception {
        Path dir = Path.of("target", "live-decision-test", name + "-" + System.nanoTime());
        Files.createDirectories(dir);
        return dir;
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions")) {
            modules.put(name, 1);
        }
        modules.put("dividend", 1);
        return new RunManifest(RUN, 11L, RunManifest.RULES_DIVIDEND_WORLD_EXPERIMENT_V1, modules,
                Map.of("profile", "live-guard-test"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static ManufacturingRun.Config manufacturing() {
        return new ManufacturingRun.Config(
                new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), "CUFE1");
    }

    private static List<CashLedger.OpeningAccount> openings() {
        return List.of(
                new CashLedger.OpeningAccount("ACC-FUND", "fund-a", money("1000.00"), "回放开局"),
                new CashLedger.OpeningAccount("INV", "investor", money("0.00"), "回放开局"),
                new CashLedger.OpeningAccount("CO", "company", money("1000.00"), "回放开局"),
                new CashLedger.OpeningAccount("SUP", "supplier", money("100.00"), "回放开局"),
                new CashLedger.OpeningAccount("CUS", "customer", money("2000.00"), "回放开局"),
                new CashLedger.OpeningAccount("OPS", "ops", money("0.00"), "回放开局"),
                new CashLedger.OpeningAccount("LOCK", "locked", money("0.00"), "回放开局"));
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(new OpeningRegistry.Party("cufe", OpeningRegistry.PartyKind.ORGANIZATION,
                                "中央财经制造公司（测试）"),
                        new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "甲"),
                        new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "乙"),
                        new OpeningRegistry.Party("carol", OpeningRegistry.PartyKind.PERSON, "丙"),
                        new OpeningRegistry.Party("dave", OpeningRegistry.PartyKind.PERSON, "丁"),
                        new OpeningRegistry.Party("erin", OpeningRegistry.PartyKind.PERSON, "戊"),
                        new OpeningRegistry.Party("investor", OpeningRegistry.PartyKind.PERSON,
                                "外部投资者")),
                List.of(new OpeningRegistry.PropertyOwner("fund-a",
                                OpeningRegistry.OwnerKind.PRODUCT, null),
                        new OpeningRegistry.PropertyOwner("investor",
                                OpeningRegistry.OwnerKind.PERSON, "investor"),
                        new OpeningRegistry.PropertyOwner("alice",
                                OpeningRegistry.OwnerKind.PERSON, "alice"),
                        new OpeningRegistry.PropertyOwner("bob",
                                OpeningRegistry.OwnerKind.PERSON, "bob"),
                        new OpeningRegistry.PropertyOwner("carol",
                                OpeningRegistry.OwnerKind.PERSON, "carol"),
                        new OpeningRegistry.PropertyOwner("dave",
                                OpeningRegistry.OwnerKind.PERSON, "dave"),
                        new OpeningRegistry.PropertyOwner("erin",
                                OpeningRegistry.OwnerKind.PERSON, "erin"),
                        new OpeningRegistry.PropertyOwner("company",
                                OpeningRegistry.OwnerKind.ORGANIZATION, "cufe"),
                        new OpeningRegistry.PropertyOwner("supplier",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("customer",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("ops",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("locked",
                                OpeningRegistry.OwnerKind.AGGREGATE, null)),
                List.of(new OpeningRegistry.Security("CUFE1", "cufe", 200L, money("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("INV", "CUFE1", 100L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("ACC-FUND", "CUFE1", 20L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("LOCK", "CUFE1", 60L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.LOCKED),
                        new OpeningRegistry.ShareAllocation("CO", "CUFE1", 20L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.TREASURY)));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter("cufe", List.of(
                new BoardModel.Seat("s1", "alice"), new BoardModel.Seat("s2", "bob"),
                new BoardModel.Seat("s3", "carol"), new BoardModel.Seat("s4", "dave"),
                new BoardModel.Seat("s5", "erin")), "s1", BoardModel.QUORUM);
    }

    private static RoleRun.Config roles() {
        long end = 30L * 24 * 60 * 60 * 1000;
        return new RoleRun.Config(
                List.of(new RoleAccess.ProductMandate("cufe", "fund-a", List.of("ACC-FUND"))),
                List.of(new RoleAccess.Appointment("A-1", "alice", "cufe",
                                RoleAccess.Role.RESEARCHER, 0L, end, null),
                        new RoleAccess.Appointment("A-2", "bob", "cufe",
                                RoleAccess.Role.PORTFOLIO_MANAGER, 0L, end, null),
                        new RoleAccess.Appointment("A-3", "carol", "cufe",
                                RoleAccess.Role.RISK_OFFICER, 0L, end, null),
                        new RoleAccess.Appointment("A-4", "dave", "cufe",
                                RoleAccess.Role.EXECUTION_TRADER, 0L, end, null)));
    }

    private static InformationRun.Config information() {
        return new InformationRun.Config(List.of("alice", "bob", "carol", "dave", "erin",
                "investor"));
    }

    private static DecisionModel.Limits limits() {
        return new DecisionModel.Limits(1, 1_000, 1_000, 200, 100, 1, 1, 1_000);
    }

    private static DecisionModel.Config scriptedConfig() {
        DecisionModel.Usage zero = new DecisionModel.Usage(0, 0, 0, money("0.00"));
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:guard",
                "scripted-v1", Map.of("wake", 1L), Map.of(), limits(),
                new DecisionModel.Budget(zero, Map.of("alice", zero)));
    }

    private static DecisionModel.Config liveConfig() {
        DecisionModel.Usage cap = DecisionModel.Usage.unknownCost(1, 1_000, 500);
        return new DecisionModel.Config("DEEPSEEK_HTTP", "deepseek:guard", "pricing-2026-11-07",
                Map.of("wake", 1L), Map.of(), limits(),
                new DecisionModel.Budget(cap, Map.of("alice", cap)),
                DecisionModel.Profile.LIVE_HTTP_V1);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
