package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** 真实旧 writer 样本与真实历史输入校验边界；篡改先重算决定/尝试摘要。 */
class LegacyOperatingReplayValidationTest {
    @TempDir Path dir;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PERSON = ComposedManufacturingThreeScenario.TECH_MANAGER;

    @Test void original75WriterRestartThenDecisionReopensWithoutChangingInputOrJournal() throws Exception {
        for (String mode : List.of("delta", "snapshot")) {
            Path journal = dir.resolve(mode + ".journal");
            try (var resource = getClass().getResourceAsStream("/operating-replay-75f17f4/" + mode + ".journal.gz");
                 var input = new GZIPInputStream(Objects.requireNonNull(resource))) { Files.copy(input, journal); }
            String original;
            try (var input = getClass().getResourceAsStream("/operating-replay-75f17f4/" + mode + "-input.txt")) {
                original = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
            }
            byte[] bytes = Files.readAllBytes(journal);
            try (var world = mode.equals("delta") ? ComposedIndustryScenario.openPlanningOperating(journal)
                    : ComposedIndustryScenario.openAccountableAutonomous(journal)) {
                var decision = world.snapshot().decisions().orElseThrow().state().decisions().stream()
                        .filter(d -> d.actionId().equals("after-old-restart")).findFirst().orElseThrow();
                assertEquals(original, decision.inputText()); assertEquals(174, world.snapshot().position());
            }
            assertArrayEquals(bytes, Files.readAllBytes(journal));
        }
    }

    @Test void realHistoricalValidatorAcceptsOnlyReachableLegacyOrderAndRejectsRehashedTampering() throws Exception {
        try (var world = ComposedIndustryScenario.openPlanningOperating(dir.resolve("legacy.journal"))) {
            Fixture fixture = fixture(world);
            check(world, fixture, fixture.decision());
            check(world, fixture, changed(fixture.decision(), n -> reorder(n, 1, 0, 2)));
            check(world, fixture, changed(fixture.decision(), n -> reorder(n, 1, 2, 0)));
            reject(world, fixture, n -> reorder(n, 2, 0, 1));
            reject(world, fixture, n -> ((ObjectNode) reports(n).get(0)).put("amount", "999.00"));
            reject(world, fixture, n -> ((ObjectNode) reports(n).get(0)).put("reason", "FORGED"));
            reject(world, fixture, n -> ((ObjectNode) reports(n).get(0)).put("position", "999"));
            reject(world, fixture, n -> reports(n).remove(0));
            reject(world, fixture, n -> reports(n).set(1, reports(n).get(0).deepCopy()));
            reject(world, fixture, n -> ((ObjectNode) reports(n).get(0)).put("taskId", "foreign-report"));
            reject(world, fixture, n -> ((ObjectNode) n.path("engineering").path("companyOperations").get(0)).putObject("reports"));
            reject(world, fixture, n -> n.put("wakeReason", "forged-wake"));
            reject(world, fixture, n -> ((ObjectNode) n.path("engineering").path("decisionGuide")).put("purpose", "forged-basis"));
        }
    }

    @Test void explicitPositionVersionUsesDirectStrictComparisonAndStrictManifestPairing() throws Exception {
        try (var world = ComposedIndustryScenario.openInformedOperating(dir.resolve("new.journal"))) {
            assertEquals("position-v1", world.profile().manifest().dataVersions().get("operating-report-order"));
            Fixture fixture = fixture(world); check(world, fixture, fixture.decision());
            reject(world, fixture, n -> reorder(n, 1, 2, 0));
            var m = world.profile().manifest();
            for (String key : List.of("operating-report-order", "operating-information", "role-context", "state-storage")) {
                var data = new TreeMap<>(m.dataVersions()); data.put(key, "invalid");
                assertThrows(IllegalArgumentException.class, () -> new RunManifest(m.runId(), m.seed(), m.rulesVersion(), m.moduleVersions(), data, m.modelConfigVersion()));
            }
            var missing = new TreeMap<>(m.dataVersions()); missing.remove("operating-information");
            assertThrows(IllegalArgumentException.class, () -> new RunManifest(m.runId(), m.seed(), m.rulesVersion(), m.moduleVersions(), missing, m.modelConfigVersion()));
        }
    }


    @Test void realTwoPersonBatchRejectsIndividuallyReachableButIncompatibleRestartCuts() throws Exception {
        try (var world = ComposedIndustryScenario.openPlanningOperating(dir.resolve("batch.journal"))) {
            String companyA=ComposedManufacturingThreeScenario.TECH, companyB=ComposedManufacturingThreeScenario.STRUCT;
            String personA=PERSON, personB=ComposedManufacturingThreeScenario.STRUCT_MANAGER;
            OperationsFixture.grantBudgetAndAppoint(world,companyA,ComposedManufacturingThreeScenario.TECH_DIRECTORS,personA,"batch-A",List.of(0L));
            OperationsFixture.grantBudgetAndAppoint(world,companyB,ComposedManufacturingThreeScenario.STRUCT_DIRECTORS,personB,"batch-B",List.of(0L));
            for (String id:List.of("z-A","z-B","a-A","a-B")) {
                boolean a=id.endsWith("A"); String company=a?companyA:companyB,person=a?personA:personB;
                String material=world.engineeringRoleInput(person).path("companyOperations").get(0).path("visibleQuotes").get(0).path("materialId").asText();
                long due=world.snapshot().timeMillis()+2000;
                world.scheduleOperationsAction(id,id+"-task",due,company,person,"ORDER_MATERIAL",OperationsFixture.op().material(material,1).build(),1000);
                world.advanceTo(due); assertTrue(world.operationsRun().orElseThrow().report(id+"-task:finish").accepted());
            }
            world.beginDecisionSession("batch-session");
            Field stateField=WorldRuntime.class.getDeclaredField("state");stateField.setAccessible(true);
            Object livePrevious=stateField.get(world);
            Method writer=WorldRuntime.class.getDeclaredMethod("stateCanonical");writer.setAccessible(true);
            String canonical=((ObjectMapper)writer.invoke(world)).writeValueAsString(livePrevious);
            Object reopenedPrevious=JSON.readValue(canonical,livePrevious.getClass());
            assertEquals(livePrevious,reopenedPrevious,"只模拟真实规范writer的map表示变化");
            var route=new DecisionModel.Route("governance","key-1","deepseek-flash",1,20,1_000_000,1024,1);
            world.openDecisions("two-person-open",List.of(new DecisionRun.Request(personA,"person-A","wake",route),new DecisionRun.Request(personB,"person-B","wake",route)));
            var run=world.snapshot().decisions().orElseThrow();
            var a=run.state().decisions().stream().filter(d->d.actionId().equals("person-A")).findFirst().orElseThrow();
            var b=run.state().decisions().stream().filter(d->d.actionId().equals("person-B")).findFirst().orElseThrow();
            Field journalField=WorldRuntime.class.getDeclaredField("journal");journalField.setAccessible(true);
            var commit=((WorldJournal)journalField.get(world)).commitAt(world.snapshot().position()).orElseThrow();
            Fixture fixture=new Fixture(reopenedPrevious,stateField.get(world),commit,run,b);
            checkFrame(world,fixture,b); // 两人连续写表示，共同cut=0。
            var reversedB=changed(b,n->reorder(n,1,0));
            checkInput(world,fixture,a);checkInput(world,fixture,reversedB);
            assertThrows(IOException.class,()->checkFrame(world,fixture,reversedB));
        }
    }

    private Fixture fixture(WorldRuntime world) throws Exception {
        OperationsFixture.grantBudgetAndAppoint(world, ComposedManufacturingThreeScenario.TECH,
                ComposedManufacturingThreeScenario.TECH_DIRECTORS, PERSON, "boundary", List.of(0L));
        for (String id : List.of("z-first", "a-second", "m-third")) {
            long due = world.snapshot().timeMillis() + 2000;
            world.scheduleOperationsAction(id, id + "-task", due, ComposedManufacturingThreeScenario.TECH, PERSON,
                    "ORDER_MATERIAL", OperationsFixture.op().material("MAT-A", 1).build(), 1000);
            world.advanceTo(due);
            assertTrue(world.operationsRun().orElseThrow().report(id + "-task:finish").accepted());
        }
        world.beginDecisionSession("boundary-session");
        Field state = WorldRuntime.class.getDeclaredField("state"); state.setAccessible(true);
        Object previous = state.get(world); long cutoff = world.snapshot().position();
        world.openDecisions("boundary-open", List.of(new DecisionRun.Request(PERSON, "boundary-decision", "wake",
                new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1, 20, 1_000_000, 1024, 1))));
        var run = world.snapshot().decisions().orElseThrow();
        var decision = run.state().decisions().stream().filter(d -> d.actionId().equals("boundary-decision")).findFirst().orElseThrow();
        assertEquals(cutoff, decision.cutoffPosition());
        Field journalField = WorldRuntime.class.getDeclaredField("journal"); journalField.setAccessible(true);
        WorldJournal journal = (WorldJournal) journalField.get(world);
        return new Fixture(previous, state.get(world), journal.commitAt(world.snapshot().position()).orElseThrow(), run, decision);
    }
    private record Fixture(Object previous, Object candidate, WorldCommit commit, DecisionRun run, DecisionModel.Decision decision) { }
    private static ArrayNode reports(ObjectNode input) { return (ArrayNode) input.path("engineering").path("companyOperations").get(0).path("reports"); }
    private static void reorder(ObjectNode input, int... indices) {
        ArrayNode rows = reports(input), original = rows.deepCopy(); rows.removeAll();
        for (int index : indices) rows.add(original.get(index));
    }
    private DecisionModel.Decision changed(DecisionModel.Decision original, Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode input = (ObjectNode) JSON.readTree(original.inputText()); mutation.accept(input);
        String text = JSON.writeValueAsString(input);
        Method digest = DecisionModel.class.getDeclaredMethod("canonicalDigest", String.class, List.class, String.class);
        digest.setAccessible(true); String hash = (String) digest.invoke(null, original.toolContractVersion(), original.allowedTools(), text);
        ObjectNode node = JSON.valueToTree(original); node.put("inputText", text).put("inputDigest", hash);
        for (JsonNode attempt : node.path("attempts")) ((ObjectNode) attempt).put("inputDigest", hash);
        if (node.path("plan").isObject()) {
            ((ObjectNode) node.path("plan")).put("inputBytes", text.getBytes(StandardCharsets.UTF_8).length);
            var plan = JSON.treeToValue(node.path("plan"), DecisionModel.ProviderPlan.class);
            JsonNode reserved = JSON.valueToTree(DecisionModel.Usage.unknownCost(1, plan.reservedInputTokens(), plan.route().maxOutputTokens()));
            for (JsonNode attempt : node.path("attempts")) ((ObjectNode) attempt).set("reserved", reserved.deepCopy());
        }
        return JSON.treeToValue(node, DecisionModel.Decision.class);
    }
    private void reject(WorldRuntime world, Fixture fixture, Consumer<ObjectNode> mutation) throws Exception {
        DecisionModel.Decision changed = changed(fixture.decision(), mutation);
        assertThrows(IOException.class, () -> checkInput(world, fixture, changed));
        assertThrows(IOException.class, () -> checkFrame(world, fixture, changed));
    }
    private void check(WorldRuntime world, Fixture fixture, DecisionModel.Decision decision) throws Exception {
        checkInput(world, fixture, decision); checkFrame(world, fixture, decision);
    }
    private void checkInput(WorldRuntime world, Fixture fixture, DecisionModel.Decision decision) throws Exception {
        Method validator = Arrays.stream(WorldRuntime.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("validateReopenedInput")).findFirst().orElseThrow();
        validator.setAccessible(true);
        try { validator.invoke(world, fixture.previous(), fixture.run(), decision, fixture.run().binding(decision.actionId())); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private void checkFrame(WorldRuntime world, Fixture fixture, DecisionModel.Decision decision) throws Exception {
        var original = fixture.run().state();
        var decisions = original.decisions().stream().map(d -> d.actionId().equals(decision.actionId()) ? decision : d).toList();
        var state = new DecisionModel.State(original.runId(), original.seed(), original.config(), original.recoveryGeneration(),
                original.sessionOpen(), decisions, original.runUsage(), original.personUsage(), original.nextAttemptSequence());
        DecisionRun run = fixture.run().withState(state);
        Class<?> type = fixture.candidate().getClass(); var components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length]; Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType(); Method accessor = components[i].getAccessor(); accessor.setAccessible(true);
            values[i] = components[i].getName().equals("decisions") ? run : accessor.invoke(fixture.candidate());
        }
        Constructor<?> constructor = type.getDeclaredConstructor(types); constructor.setAccessible(true);
        Object candidate = constructor.newInstance(values);
        Method validator = Arrays.stream(WorldRuntime.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("validateDecisionFrame")).findFirst().orElseThrow();
        validator.setAccessible(true);
        try { validator.invoke(world, fixture.commit(), candidate, fixture.previous()); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }
}
