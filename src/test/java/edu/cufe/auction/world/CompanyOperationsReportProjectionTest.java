package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 原 ObjectMapper 与经营组件的兼容证据，不伪造不存在的旧规则经营世界。 */
public class CompanyOperationsReportProjectionTest {
    @TempDir Path dir;

    @Test void originalMapperRetainsReportIterationOrderAcrossProcesses() throws Exception {
        Set<String> pids = new HashSet<>();
        for (String phase : List.of("write", "read")) {
            Path output = dir.resolve(phase + ".log");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    CompanyOperationsReportProjectionTest.class.getName(), dir.toString(), phase)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!child.waitFor(30, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("组件恢复进程超时"); }
            String result = Files.readString(output);
            assertEquals(0, child.exitValue(), result);
            pids.add(result.strip());
        }
        assertEquals(2, pids.size());
    }

    /** 两个进程通过原 mapper 序列化组件，验证非规范存储分支不替换原迭代顺序。 */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]); ObjectMapper mapper = new ObjectMapper();
        CompanyOperationsRun run;
        if (args[1].equals("write")) {
            var reports = new LinkedHashMap<String, CompanyOperationsRun.ReportEntry>();
            // 组件合同允许任意已存映射次序；此处故意与 position 和 taskId 排序均不同。
            for (var report : List.of(report("z-first", 30), report("a-second", 10), report("m-third", 20)))
                reports.put(report.taskId(), report);
            run = new CompanyOperationsRun(CompanyOperationsRun.RULES, List.of(), reports);
            Files.writeString(dir.resolve("component.json"), mapper.writeValueAsString(run));
            Files.writeString(dir.resolve("reports.json"), mapper.writeValueAsString(run.reports().values()));
        } else run = mapper.readValue(Files.readString(dir.resolve("component.json")), CompanyOperationsRun.class);
        var projected = CompanyOperationsDecisionTools.reports(run, "company", false);
        assertEquals(List.of("z-first", "a-second", "m-third"), projected.stream().map(CompanyOperationsRun.ReportEntry::taskId).toList());
        assertEquals(Files.readString(dir.resolve("reports.json")), mapper.writeValueAsString(projected));
        assertTrue(CompanyOperationsDecisionTools.reports(run, "foreign", false).isEmpty());
        System.out.println("LEGACY_REPORT_COMPONENT_OK " + ProcessHandle.current().pid());
    }
    private static CompanyOperationsRun.ReportEntry report(String id, long position) {
        return new CompanyOperationsRun.ReportEntry(id, id, "company", "ORDER_MATERIAL", true,
                "ORDERED", id + ":po", BigDecimal.ZERO, 1000, position);
    }
}
