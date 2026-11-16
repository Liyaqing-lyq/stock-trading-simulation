package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 真实经营报告和决定输入跨独立进程恢复；不调用外部模型。 */
public class OperatingReportReplayTest {
    @TempDir Path dir;

    @ParameterizedTest
    @CsvSource({"hydro,true", "manufacturing,true", "digital,true", "aged,true", "resource,true",
            "hydro,false", "manufacturing,false", "digital,false", "aged,false", "resource,false"})
    void exactDecisionInputSurvivesIndependentJvm(String industry, boolean delta) throws Exception {
        replayCase(industry, Boolean.toString(delta));
    }

    @Test void explicitPositionVersionKeepsExactViewAcrossProcesses() throws Exception {
        replayCase("manufacturing", "informed");
    }

    private void replayCase(String industry, String storage) throws Exception {
        Path folder = dir.resolve(industry + "-" + storage);
        Files.createDirectories(folder);
        Set<String> pids = new HashSet<>();
        byte[] originalJournal = null;
        for (String phase : List.of("write", "read")) {
            Path log = folder.resolve(phase + ".log");
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx768m", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
            String security = System.getProperty("java.security.properties");
            if (security != null) command.add("-Djava.security.properties=" + security);
            command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    OperatingReportReplayTest.class.getName(), folder.toString(), industry, storage, phase));
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("独立恢复进程超时：" + industry); }
            String output = Files.readString(log);
            assertEquals(0, child.exitValue(), output);
            pids.add(output.lines().filter(line -> line.startsWith("REPORT_REPLAY_OK ")).findFirst().orElseThrow());
            if (phase.equals("write")) originalJournal = Files.readAllBytes(folder.resolve("world.journal"));
        }
        assertEquals(2, pids.size());
        assertArrayEquals(originalJournal, Files.readAllBytes(folder.resolve("world.journal")), "恢复不得重写历史");
    }

    /** 两个进程仅通过日志和原始输入文本交接。 */
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]); String industry = args[1]; boolean informed = args[2].equals("informed");
        boolean delta = informed || Boolean.parseBoolean(args[2]);
        String company = company(industry), person = person(industry);
        try (WorldRuntime world = informed ? ComposedIndustryScenario.openInformedOperating(folder.resolve("world.journal"))
                : delta ? ComposedIndustryScenario.openPlanningOperating(folder.resolve("world.journal"))
                : ComposedIndustryScenario.openAccountableAutonomous(folder.resolve("world.journal"))) {
            assertEquals(delta, "delta-v1".equals(world.profile().manifest().dataVersions().get("state-storage")));
            if (args[3].equals("write")) {
                OperationsFixture.grantBudgetAndAppoint(world, company, directors(industry), person, "reports", List.of(0L));
                if (industry.equals("hydro")) {
                    world.advanceTo(60_000); world.advanceTo(120_000); world.advanceTo(180_000);
                } else {
                    operate(world, industry, "z-first"); operate(world, industry, "a-second");
                }
                var reports = world.operationsRun().orElseThrow().reports().values().stream()
                        .filter(r -> company.equals(r.companyPartyId())).toList();
                assertTrue(reports.size() >= 2);
                assertEquals(reports.size(), reports.stream().map(CompanyOperationsRun.ReportEntry::position).distinct().count());
                assertNotEquals(reports.stream().map(CompanyOperationsRun.ReportEntry::taskId).toList(),
                        reports.stream().map(CompanyOperationsRun.ReportEntry::taskId).sorted().toList(), "夹具必须区分提交顺序与键排序");
                var call = RolePlanningTest.act(world, person, "frozen-report-input", "role.wait", null);
                Files.writeString(folder.resolve("input.txt"), call.inputText());
                Files.writeString(folder.resolve("view.txt"), world.autonomyRoleObservation(person).toString());
            } else {
                var decision = world.snapshot().decisions().orElseThrow().state().decisions().stream()
                        .filter(d -> d.actionId().equals("frozen-report-input")).findFirst().orElseThrow();
                assertEquals(Files.readString(folder.resolve("input.txt")), decision.inputText());
                String expectedView = Files.readString(folder.resolve("view.txt"));
                if (!informed) {
                    // 旧实时投影继续使用当前 map 次序：规范快照重开后是键序，不偷偷改成新合同。
                    JsonNode expected = new ObjectMapper().readTree(expectedView);
                    JsonNode engineering = expected.path("engineering");
                    ArrayNode reports = (ArrayNode) (industry.equals("manufacturing")
                            ? engineering.path("companyOperations").get(0).path("reports")
                            : engineering.path(industry + "Operations").path("reports"));
                    List<JsonNode> ordered = new ArrayList<>(); reports.forEach(ordered::add);
                    ordered.sort(Comparator.comparing(row -> row.path("taskId").asText()));
                    reports.removeAll(); ordered.forEach(reports::add); expectedView = expected.toString();
                }
                assertEquals(expectedView, world.autonomyRoleObservation(person).toString());
            }
        }
        System.out.println("REPORT_REPLAY_OK " + ProcessHandle.current().pid());
    }

    private static void operate(WorldRuntime world, String industry, String id) throws Exception {
        long due = world.snapshot().timeMillis() + 2000;
        String person = person(industry), company = company(industry);
        switch (industry) {
            case "manufacturing" -> world.scheduleOperationsAction(id, id + "-task", due, company, person,
                    "ORDER_MATERIAL", OperationsFixture.op().material("MAT-A", 1).build(), 1000);
            case "digital" -> world.openRoleSession(person).digitalAction(id, id + "-task", due, company,
                    new DigitalOperations.Request(DigitalOperations.Action.BEGIN_PILOT, Map.of()), 1000);
            case "aged" -> world.openRoleSession(person).agedAction(id, id + "-task", due, company,
                    new AgedOperations.Request(AgedOperations.Action.ORDER_RAW, Map.of("orderId", id, "units", "1")), 1000);
            case "resource" -> world.openRoleSession(person).resourceAction(id, id + "-task", due, company,
                    new ResourceOperations.Request(ResourceOperations.Action.BEGIN_EXTRACTION,
                            Map.of("jobId", id, "seamId", "resource-seam-a", "quantityKg", "1")), 1000);
            default -> throw new IllegalArgumentException(industry);
        }
        world.advanceTo(due);
        assertNotNull(world.operationsRun().orElseThrow().report(id + "-task:finish"));
    }

    private static String company(String industry) {
        return switch (industry) {
            case "hydro" -> HydroIndustryConfig.COMPANY;
            case "manufacturing" -> ComposedManufacturingThreeScenario.TECH;
            case "digital" -> DigitalIndustryConfig.COMPANY;
            case "aged" -> AgedIndustryConfig.COMPANY;
            case "resource" -> ComposedIndustryScenario.RESOURCE;
            default -> throw new IllegalArgumentException(industry);
        };
    }
    private static String person(String industry) {
        return switch (industry) {
            case "hydro" -> HydroIndustryConfig.MANAGER;
            case "manufacturing" -> ComposedManufacturingThreeScenario.TECH_MANAGER;
            case "digital" -> DigitalIndustryConfig.MANAGER_PERSON;
            case "aged" -> AgedIndustryConfig.MANAGER_PERSON;
            case "resource" -> ComposedIndustryScenario.MANAGER;
            default -> throw new IllegalArgumentException(industry);
        };
    }
    private static List<String> directors(String industry) {
        return switch (industry) {
            case "hydro" -> HydroIndustryConfig.DIRECTORS;
            case "manufacturing" -> ComposedManufacturingThreeScenario.TECH_DIRECTORS;
            case "digital", "aged" -> BaselineIndustryConfig.directors(company(industry));
            case "resource" -> ComposedIndustryScenario.DIRECTORS;
            default -> throw new IllegalArgumentException(industry);
        };
    }
}
