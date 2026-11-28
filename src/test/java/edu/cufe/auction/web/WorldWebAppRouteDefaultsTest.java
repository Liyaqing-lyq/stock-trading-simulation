package edu.cufe.auction.web;

import edu.cufe.auction.app.WorldWebApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 真实入口初始化路由：已有 revision 0 仍是用户配置，新模式只能初始化缺失文件。 */
class WorldWebAppRouteDefaultsTest {
    @TempDir Path root;

    @Test void existingRevisionZeroLegacyFileRemainsByteForByteUnchangedInBothNewModes() throws Exception {
        for (String mode : new String[]{"world-accountable", "world-operating", "world-planning", "world-planning-operating", "world-informed-operating", "world-contextual-informed-operating", "world-thinking-informed-operating"}) {
            Path work = Files.createDirectory(root.resolve(mode));
            try (var routes = SceneRoutes.open(work)) { assertEquals(0, routes.view().revision()); }
            byte[] before = Files.readAllBytes(work.resolve(SceneRoutes.FILE_NAME));
            initialize(work, mode);
            assertArrayEquals(before, Files.readAllBytes(work.resolve(SceneRoutes.FILE_NAME)), mode);
            try (var routes = SceneRoutes.open(work)) {
                assertEquals(0, routes.view().revision());
                assertTrue(routes.view().routes().stream().allMatch(r -> r.budgetPolicy() == null && r.maxCalls() == 5));
            }
        }
    }

    @Test void freshExplicitModesReceiveFiniteDisabledLifecycleDefaults() throws Exception {
        for (String mode : new String[]{"world-accountable", "world-operating", "world-planning", "world-planning-operating", "world-informed-operating", "world-contextual-informed-operating"}) {
            Path work = Files.createDirectory(root.resolve(mode));
            initialize(work, mode);
            try (var routes = SceneRoutes.open(work)) {
                for (var route : routes.view().routes()) {
                    assertFalse(route.enabled());
                    assertEquals("lifecycle-v1", route.budgetPolicy());
                    assertEquals(2500, route.maxCalls());
                    assertEquals(35_000_000, route.maxTotalTokens());
                    assertEquals(2048, route.maxOutputTokens());
                    assertEquals(4, route.maxConcurrency());
                }
            }
        }
    }

    @Test void informedEntryCreatesItsOwnVersionedJournal() throws Exception {
        Path work = Files.createDirectory(root.resolve("informed-manifest"));
        initialize(work, "world-informed-operating");
        Path journal = work.resolve("world-informed-operating-v1.journal");
        assertTrue(Files.isRegularFile(journal));
        assertFalse(Files.exists(work.resolve("world-planning-operating-v4.journal")));
        try (var world = edu.cufe.auction.world.composed.ComposedIndustryScenario.openInformedOperating(journal)) {
            assertEquals("web-informed-operating-v1", world.profile().manifest().runId());
        }
    }

    @Test void contextualEntryCreatesSeparateV5Journal() throws Exception {
        Path work=Files.createDirectory(root.resolve("contextual-manifest"));initialize(work,"world-contextual-informed-operating");
        Path journal=work.resolve("world-contextual-informed-operating-v2.journal");assertTrue(Files.isRegularFile(journal));
        assertFalse(Files.exists(work.resolve("world-informed-operating-v1.journal")));
        try(var world=edu.cufe.auction.world.composed.ComposedIndustryScenario.openContextualInformedOperating(journal)){
            assertEquals("web-informed-operating-v2",world.profile().manifest().runId());
            assertEquals("deliberation-v5",world.profile().manifest().dataVersions().get("role-context"));
        }
    }

    @Test void personalMarketModeKeepsExistingFundedArchiveAndRoutesAndOpensItsOwnJournal() throws Exception {
        Path work=Files.createDirectory(root.resolve("personal-market"));
        initialize(work,"world-thinking-informed-operating");
        Path funded=work.resolve("world-thinking-informed-operating-v2.journal"), config=work.resolve(SceneRoutes.FILE_NAME);
        byte[] fundedBytes=Files.readAllBytes(funded), routes=Files.readAllBytes(config);
        initialize(work,"world-personal-market-operating");
        assertArrayEquals(fundedBytes,Files.readAllBytes(funded));
        assertArrayEquals(routes,Files.readAllBytes(config));
        try(var world=AutonomousLifecycleProbe.openWorld("PERSONAL_MARKET_OPERATING",work.resolve("world-personal-market-operating-v1.journal"))) {
            assertEquals(edu.cufe.auction.world.PersonalMarket.RUN_ID,world.profile().manifest().runId());
        }
        Path fresh=Files.createDirectory(root.resolve("personal-fresh"));
        initialize(fresh,"world-personal-market-operating");
        assertFalse(Files.exists(fresh.resolve("world-thinking-informed-operating-v2.journal")));
        try(var sceneRoutes=SceneRoutes.open(fresh)) {
            assertTrue(sceneRoutes.view().routes().stream().allMatch(r->!r.enabled()
                    && "deepseek-thinking-high-v1".equals(r.providerPolicy()) && r.maxOutputTokens()==8192));
        }
    }

    @Test void thinkingEntryHasSeparateJournalExplicitPolicyAndSixtySecondLimit() throws Exception {
        Path work=Files.createDirectory(root.resolve("thinking-manifest"));initialize(work,"world-thinking-informed-operating");
        Path journal=work.resolve("world-thinking-informed-operating-v2.journal");assertTrue(Files.isRegularFile(journal));
        assertFalse(Files.exists(work.resolve("world-contextual-informed-operating-v2.journal")));
        try(var routes=SceneRoutes.open(work)) {
            for(var route:routes.view().routes()) {
                assertFalse(route.enabled());assertEquals("lifecycle-v1",route.budgetPolicy());assertEquals("deepseek-thinking-high-v1",route.providerPolicy());
                assertEquals(2500,route.maxCalls());assertEquals(35000000,route.maxTotalTokens());assertEquals(8192,route.maxOutputTokens());assertEquals(4,route.maxConcurrency());
            }
        }
        try(var world=AutonomousLifecycleProbe.openWorld("FUNDED_THINKING_OPERATING",journal)) {
            assertEquals("web-thinking-operating-v2",world.profile().manifest().runId());assertEquals("deepseek-thinking-high-v1",world.profile().manifest().dataVersions().get("provider-policy"));
            assertEquals(60000,world.profile().decisions().limits().wallTimeoutMillis());assertEquals(2,world.profile().decisions().limits().maxInFlight());
        }
    }

    @Test void thinkingEntryReopensExistingV1JournalWithoutChangingItsContract() throws Exception {
        Path work=Files.createDirectory(root.resolve("legacy-thinking"));
        Path journal=work.resolve("world-thinking-informed-operating-v1.journal");
        try(var old=AutonomousLifecycleProbe.openWorld("THINKING_INFORMED_OPERATING",journal)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey("customer-contract-policy"));
        }
        byte[] before=Files.readAllBytes(journal);
        initialize(work,"world-thinking-informed-operating");
        assertArrayEquals(before,Files.readAllBytes(journal));
        assertFalse(Files.exists(work.resolve("world-thinking-informed-operating-v2.journal")));
    }

    @Test void ordinaryModeKeepsLegacyDefaults() throws Exception {
        Path work = Files.createDirectory(root.resolve("scripted"));
        initialize(work, "scripted");
        try (var routes = SceneRoutes.open(work)) {
            assertEquals(0, routes.view().revision());
            for (var route : routes.view().routes()) {
                assertNull(route.budgetPolicy()); assertFalse(route.enabled());
                assertEquals(5, route.maxCalls()); assertEquals(8192, route.maxTotalTokens());
                assertEquals(512, route.maxOutputTokens()); assertEquals(1, route.maxConcurrency());
            }
        }
    }

    @Test void standardFeedbackOpeningMatchesLongStudyRoutesAndKeepsOperatorSettings() throws Exception {
        Path work = Files.createDirectory(root.resolve("feedback"));
        String mode = "world-ten-company-autonomy";
        initialize(work, mode);
        try (var routes = SceneRoutes.open(work)) {
            var probe = AutonomousLifecycleProbe.probeRoutes(routes.view(), "TEN_COMPANY_PERSONAL_NEWS_STUDY");
            assertEquals(AutonomousLifecycleProbe.probeRoutes(routes.view(), "TEN_COMPANY_FEEDBACK_STUDY"), probe);
            assertEquals(probe.size(), routes.view().routes().size());
            for (int i = 0; i < probe.size(); i++) {
                var web = routes.view().routes().get(i);
                var expected = probe.get(i);
                assertFalse(web.enabled());
                assertTrue(expected.enabled());
                assertEquals(new SceneRoutes.Route(expected.scene(), expected.slotId(), expected.model(),
                        false, expected.maxCalls(), expected.maxTotalTokens(), expected.maxOutputTokens(),
                        expected.maxConcurrency(), expected.budgetPolicy(), expected.providerPolicy()), web);
            }
            var changed = new java.util.ArrayList<>(routes.view().routes());
            var first = changed.get(0);
            changed.set(0, new SceneRoutes.Route(first.scene(), first.slotId(), SceneRoutes.MODEL_PRO,
                    false, 7, 20_000L, 512, 1));
            routes.replace(routes.view().revision(), changed);
        }
        byte[] configured = Files.readAllBytes(work.resolve(SceneRoutes.FILE_NAME));
        initialize(work, mode);
        assertArrayEquals(configured, Files.readAllBytes(work.resolve(SceneRoutes.FILE_NAME)));
    }

    @Test void oldStandardJournalAndSeparateProductsKeepTheirOwnRouteDefaults() throws Exception {
        Path prior = Files.createDirectory(root.resolve("legacy-ordinary"));
        Path journal = prior.resolve("world-ten-company-autonomy-v9.journal");
        try (var ignored = edu.cufe.auction.world.composed.ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(journal)) {
            assertEquals("web-ten-company-autonomy-v9", ignored.profile().manifest().runId());
        }
        byte[] original = Files.readAllBytes(journal);
        for (String mode : java.util.List.of("world-ten-company-autonomy", "world-open-fund", "world-manufacturing-upgrade")) {
            Path work = mode.equals("world-ten-company-autonomy") ? prior : Files.createDirectory(root.resolve(mode));
            initialize(work, mode);
            try (var routes = SceneRoutes.open(work)) {
                for (var route : routes.view().routes()) {
                    assertFalse(route.enabled());
                    assertEquals("lifecycle-v1", route.budgetPolicy());
                    assertEquals(2500, route.maxCalls());
                    assertEquals(35_000_000L, route.maxTotalTokens());
                }
            }
        }
        assertArrayEquals(original, Files.readAllBytes(journal));
    }

    private void initialize(Path work, String mode) throws Exception {
        Path fakeKeys = work.resolve("test-only-fake-keys.txt");
        Files.writeString(fakeKeys, String.join("\n", "sk-fake-aaaaaaaaaaaaaaaa", "sk-fake-bbbbbbbbbbbbbbbb", "sk-fake-cccccccccccccccc", "sk-fake-dddddddddddddddd", "sk-fake-eeeeeeeeeeeeeeee"));
        // Occupy the loopback port so real startup terminates through its own cleanup path.
        // Autonomy is never started, and the only keys are local fake test fixtures.
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var failure = assertThrows(java.io.IOException.class, () -> WorldWebApp.main(new String[]{work.toString(), fakeKeys.toString(), Integer.toString(occupied.getLocalPort()), mode}));
            assertEquals("Web 启动失败：端口不可用", failure.getMessage());
        }
    }
}
