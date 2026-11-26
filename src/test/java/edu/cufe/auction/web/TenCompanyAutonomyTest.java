package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.EngineeringCustomerPolicy;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TenCompanyAutonomyTest {
    @TempDir Path dir;
    static final String MODE = "TEN_COMPANY_AUTONOMY", SYSTEM = WorldWebScenario.RESEARCH_SYSTEM_PUBLISHER;
    static final Path DRAFT = Path.of("src/main/resources/world/opening-copies-example.json");

    record Person(String id, String displayName, String privatePlan, List<String> ownAccounts,
                  List<String> scenes, List<String> governanceCompanies, boolean canWake, int inputUtf8Bytes) { }

    static List<Person> roster(WorldRuntime w, WorldWebHost.LiveCapabilities caps) throws Exception {
        var people = new ArrayList<Person>();
        var access = w.snapshot().roles().orElseThrow().access();
        for (var party : w.openingRegistry().orElseThrow().parties()) {
            if (party.kind() != OpeningRegistry.PartyKind.PERSON) continue;
            String id = party.partyId();
            var own = access.personAccounts().stream().filter(a -> a.personPartyId().equals(id))
                    .map(a -> a.accountId()).sorted().toList();
            var scenes = new TreeSet<String>();
            var governance = new TreeSet<String>();
            for (var row : caps.persons()) if (row.personPartyId().equals(id)) scenes.addAll(row.scenes());
            for (var row : caps.governancePersons()) if (row.personPartyId().equals(id)) {
                scenes.addAll(row.scenes());
                governance.addAll(row.governanceCompanies());
            }
            var input = w.autonomyRoleObservation(id);
            String plan = null;
            for (var goal : input.at("/engineering/deliberation/objectives")) if (goal.has("personalPlan")) {
                assertEquals("person:" + id, goal.path("id").asText());
                plan = goal.at("/personalPlan/sourceId").asText();
            }
            if (!scenes.isEmpty()) assertTrue(w.autonomyInputLimitViolation(id).isEmpty(),id + " 的开局观察超过输入限制");
            people.add(new Person(id, party.name(), plan, own, List.copyOf(scenes), List.copyOf(governance), !scenes.isEmpty(),
                    input.toString().getBytes(StandardCharsets.UTF_8).length));
        }
        return List.copyOf(people);
    }

    @Test void normalOpeningHasExactlyTenIssuersNoCopyAndAllRealPeopleHaveTheirOwnPlan() throws Exception {
        var file = dir.resolve("ten.journal");
        var ref = new AtomicReference<WorldRuntime>();
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> {
                 var w = AutonomousLifecycleProbe.openWorld(MODE,file);
                 ref.set(w);
                 return new WorldWebHost.Opened(w, w.profile().manifest().runId());
             }, routes, (call,timeout) -> { throw new AssertionError("离线开局不应派发模型调用"); })) {
            var caps = host.liveCapabilities();
            var rows = host.call(() -> roster(ref.get(),caps));
            assertEquals(62,rows.size());
            assertEquals(61,rows.stream().filter(p -> p.privatePlan() != null).count());
            assertEquals(61,rows.stream().filter(Person::canWake).count());
            assertTrue(rows.stream().filter(p -> !p.id().equals(SYSTEM)).allMatch(p -> ("household-plans-v1/"+p.id()).equals(p.privatePlan())));
            var system = rows.stream().filter(p -> p.id().equals(SYSTEM)).findFirst().orElseThrow();
            assertNull(system.privatePlan());
            assertTrue(system.ownAccounts().isEmpty());
            assertFalse(system.canWake());
            var accounts = rows.stream().flatMap(p -> p.ownAccounts().stream()).sorted().toList();
            assertEquals(List.of("HOUSE-NINA","HOUSE-OMAR","INV"),accounts);
            var admin = new ObjectMapper().valueToTree(host.worldOverview());
            var table = admin.path("companies");
            assertEquals(10,table.size(),table.toString());
            var seen = new TreeSet<String>();
            for (var company : table) {
                assertTrue(company.path("symbol").isTextual());
                assertTrue(seen.add(company.path("symbol").asText()));
            }
            assertFalse(admin.toString().contains("independent-engineering"));
            var position = host.call(() -> ref.get().snapshot().position());
            long revision = routes.view().revision();
            for (var scene : List.of("research", "governance", "trading")) {
                assertThrows(Exception.class, () -> host.startLiveRound(position,revision,List.of(SYSTEM),scene,null));
            }
            assertEquals(position,host.call(() -> ref.get().snapshot().position()));
            assertTrue(admin.path("people").toString().contains("工程客户代表（查阅委托与收付"));
            assertTrue(host.autonomyStatus().available());
            assertFalse(host.autonomyStatus().running());
        }
        byte[] before = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            var data = w.profile().manifest().dataVersions();
            assertEquals(RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID,w.profile().manifest().runId());
            assertEquals("ten-issuers-v1",data.get("opening-population"));
            assertEquals("long-horizon-v1",data.get("model-budget"));
            assertEquals("published-visible-v1",data.get("board-material-source"));
            var caps = w.snapshot().decisions().orElseThrow().state().config().budget().personCap();
            assertEquals(61,caps.size());
            assertFalse(caps.containsKey(SYSTEM));
            assertTrue(caps.containsKey(EngineeringCustomerPolicy.REPRESENTATIVE));
            assertFalse(data.keySet().stream().anyMatch(s -> s.startsWith("opening-copies")));
            assertEquals("owner-claims-v1",data.get("owner-bank-valuation"));
            assertEquals("confirmed-person-v1",data.get("person-decision-pause"));
            assertEquals("bank-resolution-halt-v1",data.get("issuer-failure"));
            assertEquals("declared-fields-v1",data.get("tool-argument-guide"));
            assertEquals("edition-visible-v1",data.get("group-readable-refs"));
            assertTrue(w.openingRegistry().orElseThrow().securities().stream().allMatch(s -> s.initialReferencePrice()!=null));
            var profile = w.bankOf().orElseThrow().decode(w.snapshot().accounts()).config().profile();
            assertFalse(profile.parties().containsKey("independent-reader"));
            assertTrue(profile.custodians().containsKey("cst"));
            assertTrue(w.snapshot().accounts().containsKey("CST-CASH"));
            assertNotNull(w.valuation("investor").bankClaims());
            assertNotNull(w.valuation("fund-a").bankClaims());
        }
        assertArrayEquals(before,Files.readAllBytes(file));
        assertThrows(Exception.class,() -> ComposedIndustryScenario.openPersonPauseOperating(file,DRAFT));
        assertArrayEquals(before,Files.readAllBytes(file));
    }

    @Test void noSystemDecisionCanBeOpenedEvenThroughTheUnderlyingAdminCommand() throws Exception {
        var file = dir.resolve("direct.journal");
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            w.beginDecisionSession("system-rejected");
            long before = w.snapshot().position();
            var route = new DecisionModel.Route("research","key-1","deepseek-flash",1,25,1000000,2048,1);
            assertThrows(IllegalArgumentException.class,() -> w.openDecisions("reject",List.of(
                    new DecisionRun.Request(SYSTEM,"forged-actor","wake",route))));
            assertEquals(before,w.snapshot().position());
            assertTrue(w.snapshot().decisions().orElseThrow().state().decisions().isEmpty());
        }
    }

    @Test void customerRepresentativeCanUseTheSameMandateThroughManualAndTargetedAutomaticRouting() throws Exception {
        var mapper = new ObjectMapper();
        var rep = EngineeringCustomerPolicy.REPRESENTATIVE;
        for (boolean automatic : List.of(false,true)) {
            var work = Files.createDirectory(dir.resolve(automatic ? "automatic" : "manual"));
            var received = new CopyOnWriteArrayList<DecisionRun.Call>();
            var current = new AtomicReference<WorldRuntime>();
            try (var routes = SceneRoutes.open(work)) {
                routes.replace(0, AutonomousLifecycleProbe.probeRoutes(routes.view(),MODE));
                try (var host = new WorldWebHost(() -> {
                    var w = ComposedIndustryScenario.openTenCompanyAutonomy(work.resolve("world.journal"));
                    current.set(w);
                    return new WorldWebHost.Opened(w,w.profile().manifest().runId());
                },routes,(call,timeout) -> {
                    received.add(call);
                    var response = mapper.createObjectNode().put("tool","role.wait");
                    response.putObject("args");
                    response.putObject("decision").put("objective","person:" + rep)
                            .put("basis","等待实际交接材料").put("reviewAfterMillis",86_400_000L);
                    return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),
                            call.attemptNo(),call.recoveryGeneration(),response.toString(),DecisionModel.Usage.unknownCost(1,40,12),"fixture"));
                })) {
                    var caps = host.liveCapabilities();
                    var who = caps.persons().stream().filter(p->p.personPartyId().equals(rep)).findFirst().orElseThrow();
                    assertEquals("trading",who.sceneId());
                    assertTrue(who.governanceCompanies().isEmpty());
                    long pos = Long.parseLong(caps.position()), now = Long.parseLong(caps.timeMillis());
                    if (automatic) {
                        assertThrows(Exception.class,() -> host.startAutonomy(pos,routes.view().revision(),now+30000,3_600_000,Set.of(SYSTEM)));
                        host.startAutonomy(pos,routes.view().revision(),now+30000,3_600_000,Set.of(rep));
                    } else host.startLiveRound(pos,routes.view().revision(),List.of(rep),"trading",null);
                    long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                    while ((received.isEmpty() || (automatic ? host.autonomyStatus().running() : !host.liveStatus().phase().equals("IDLE")))
                            && System.nanoTime()<deadline) Thread.sleep(10);
                    assertEquals(1,received.size());
                    assertFalse(host.autonomyStatus().running());
                    assertEquals("IDLE",host.liveStatus().phase());
                    var call = received.get(0);
                    var decision = host.call(() -> DecisionModel.decision(current.get().snapshot().decisions().orElseThrow().state(),
                            call.actionId()).orElseThrow());
                    assertEquals(DecisionModel.Outcome.WAIT,decision.outcome());
                    assertEquals("trading",call.plan().route().sceneId());
                    var input = mapper.readTree(call.inputText());
                    assertEquals(rep,input.path("person").asText());
                    assertEquals(rep,input.at("/engineering/customerMandate/representative").asText());
                    for (var tool : input.path("tools")) {
                        String name = tool.path("name").asText();
                        assertFalse(name.startsWith("bank.") || name.startsWith("personal.order.") || name.startsWith("engineering."),name);
                    }
                }
            }
        }
    }

    @Test void archivedCopyRunKeepsItsOwnTwoExtraIdentitiesAndCannotBeReopenedAsTheStandardPopulation() throws Exception {
        var file = dir.resolve("old.journal");
        try (var w = ComposedIndustryScenario.openPersonPauseOperating(file,DRAFT)) {
            assertTrue(w.snapshot().accounts().containsKey("COPY-COMPANY-independent-engineering"));
            assertTrue(w.snapshot().accounts().containsKey("COPY-PERSON-independent-reader"));
        }
        var old = Files.readAllBytes(file);
        assertThrows(Exception.class,() -> ComposedIndustryScenario.openTenCompanyAutonomy(file));
        try (var w = ComposedIndustryScenario.openPersonPauseOperating(file,null)) {
            assertEquals("validated-draft-v1",w.profile().manifest().dataVersions().get("opening-copies"));
            assertFalse(w.profile().manifest().dataVersions().containsKey("opening-population"));
        }
        assertArrayEquals(old,Files.readAllBytes(file));
    }
}
