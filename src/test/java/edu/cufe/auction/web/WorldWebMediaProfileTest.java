package edu.cufe.auction.web;

import java.nio.file.*;
import java.util.*;

/** Root: actual new/old profile and disabled scene checks, no provider requests. */
class WorldWebMediaProfileTest {
    static int checks;
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    @org.junit.jupiter.api.Test
    void newAndLegacyProfilesAdvertiseActualCapabilities() throws Exception {
        Path root = Path.of(System.getProperty("web.media.test.root", "target/web-media-tests"));
        Files.createDirectories(root);
        Path dir = Files.createTempDirectory(root, "newAndLegacyProfilesAdvertiseActualCapabilities-");
        LiveDecisionProvider noCalls = new LiveDecisionProvider(slot -> {
            throw new AssertionError("Unexpected provider call");
        });
        try (var routes = SceneRoutes.open(dir.resolve("media-routes"));
             var host = new WorldWebHost(() -> WorldWebScenario.openMedia(
                     dir.resolve("media.journal")), routes, noCalls)) {
            var caps = host.liveCapabilities();
            check(caps.mediaAvailable(), "new profile has media");
            check(caps.supportedScenes().equals(List.of("research", "trading", "media")),
                    "new profile advertises only three connected scenes");
            check(caps.persons().size() == 8, "four trading and four media persons");
            var audit = host.mediaAudit();
            check(audit.available() && audit.organizations().total() == 2, "two actual media organizations");
            check(audit.organizations().rows().stream().allMatch(row ->
                    row.get("totalMillis").equals("240000") && row.get("reservedMillis").equals("0")),
                    "organization budgets use exact strings and actual zero reservation");
            check(audit.stories().total() == 0 && audit.works().total() == 0,
                    "opening invents neither articles nor work");
            check(host.personPage("rita").media() != null, "new profile includes individual media projection");
            check(host.liveCapabilities().position().equals(caps.position()), "all read-only queries preserve cut");
            for (String person : WorldWebScenario.MEDIA_PERSONS) {
                check(host.roleView(person).appointments().size() == 1,
                        "one real appointment for " + person);
            }
            check(host.liveStatus().personBudgets().size() == 8, "all person budgets visible");
            host.startLiveRound(Long.parseLong(caps.position()), routes.view().revision(),
                    List.of("rita", "ed", "sam", "bea"));
            check(host.liveStatus().decisions().isEmpty(), "disabled media starts no decision");
            check(host.liveStatus().skips().size() == 4, "disabled media reports four skips");
        }
        try (var routes = SceneRoutes.open(dir.resolve("old-routes"));
             var host = new WorldWebHost(() -> WorldWebScenario.openBelief(
                     dir.resolve("belief.journal")), routes, noCalls)) {
            var caps = host.liveCapabilities();
            check(!caps.mediaAvailable() && caps.persons().size() == 4,
                    "old profile retains only four original persons");
            check(!host.mediaAudit().available(), "old media audit says unavailable");
            check(host.personPage("alice").media() == null, "old personal projection omits media");
            check(!caps.supportedScenes().contains("media"), "old profile excludes media execution");
            boolean rejected = false;
            try { host.startLiveRound(Long.parseLong(caps.position()), routes.view().revision(),
                    List.of("rita")); } catch (IllegalArgumentException ex) { rejected = true; }
            check(rejected, "old profile rejects media actor");
            check(host.liveCapabilities().position().equals(caps.position()), "old rejection writes nothing");
        }
        System.out.println("TOTAL PASS " + checks);
    }
}
