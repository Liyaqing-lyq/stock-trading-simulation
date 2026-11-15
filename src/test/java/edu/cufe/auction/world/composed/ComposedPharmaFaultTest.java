package edu.cufe.auction.world.composed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.WorldRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 独立研发公司的写入故障与跨进程边界：真实 WRITE/FORCE 失败只允许「整笔没有发生」或
 * 「整笔已确认」，恢复后同一待办只完成一次；资金、公司董事会预算与研究状态必须同批原子。
 *
 * <p>另含三个真实 JVM 的连续重开，以及一个真实分支的父子隔离（父档字节不变、子档自成一档）。</p>
 */
class ComposedPharmaFaultTest {

    private static final String PHARMA = ComposedPharmaScenario.PHARMA;
    private static final String CASH = ComposedPharmaScenario.PHARMA_CASH;
    private static final String CONTRACTOR = "RESEARCH-CONTRACTOR";
    private static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    /** WRITE 失败：帧未落地，内存保持付款前；重开后可续写，且阶段付款只发生一次。 */
    @Test
    void writeFaultKeepsPreStateAndResumesOnce() throws Exception {
        runFault(edu.cufe.auction.world.composed.FundedProbes.FailingChannel.Mode.WRITE,
                "write");
    }

    /** FORCE 失败：完整帧可能已可见，但不得半应用；恢复后只完成一次。 */
    @Test
    void forceFaultOnlyAllowsOneCompleteFrame() throws Exception {
        runFault(edu.cufe.auction.world.composed.FundedProbes.FailingChannel.Mode.FORCE,
                "force");
    }

    /** 三个真实进程：建链 → 重开续写并再摊一期 → 只读重开比对且字节不变。 */
    @Test
    void threeProcessesReopenAndContinueApprovedChain() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Path work = dir.resolve("jvm");
        Files.createDirectories(work);
        String[] steps = {"build", "continue", "readonly"};
        for (int step = 0; step < steps.length; step++) {
            Path log = work.resolve("step-" + step + ".out");
            Process process = new ProcessBuilder(java, "-cp", cp,
                    ComposedPharmaReopenProbe.class.getName(), work.resolve("world.log").toString(),
                    steps[step], work.resolve("digest-" + (step - 1)).toString(),
                    work.resolve("digest-" + step).toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            assertTrue(finished, "独立进程超时：step=" + steps[step]);
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("PHARMA_REOPEN_OK step=" + steps[step]), output);
        }
        String step1 = Files.readString(work.resolve("digest-1")).trim();
        String step2 = Files.readString(work.resolve("digest-2")).trim();
        assertTrue(step1.startsWith("step1:") && step2.startsWith("step2:"),
                "三进程摘要标记不符：" + step1 + " / " + step2);
        // 只比较逐分量摘要：step2 的首行是文件 SHA（已在探针内核对前后不变）。
        String components1 = step1.substring(step1.indexOf('\n') + 1);
        String components2 = step2.substring(step2.indexOf('\n') + 1);
        assertEquals(components1, components2, "只读重开不得改变任何分量");
    }

    /** 真实分支：子档自成一档，父档字节与数值一字不变。 */
    @Test
    void branchIsolationKeepsParentUntouched() throws Exception {
        Path world = dir.resolve("branch-parent.log");
        ComposedPharmaWorldTest.buildApprovedChain(world);
        String parentSha = sha256(world);
        Path childFile;
        Path sealedPrefix;
        BranchProfile profile;
        try (WorldRuntime parent = ComposedPharmaScenario.open(world)) {
            profile = parent.profile();
            WorldRuntime.BranchReceipt receipt = parent.createBranch("pharma-child",
                    parent.snapshot().position(), BranchOrigin.Kind.OPERATOR_BRANCH,
                    "独立研发公司分支");
            childFile = receipt.childFile();
            sealedPrefix = receipt.sealedPrefix();
        }
        try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> sealedPrefix,
                profile)) {
            long parentPosition = child.branchOrigin().orElseThrow().parentPosition();
            long childPosition = child.snapshot().position();
            // 子档真实推进一期：只改子档自己的摊销与周期，不动父档
            child.advanceTo(90L * DAY);
            assertTrue(child.snapshot().position() > childPosition);
            assertTrue(child.researchRun().orElseThrow().state().projects().get("p1")
                    .amortized().signum() > 0);
            assertTrue(ComposedCompanyReport.of(child, PHARMA).balanced());
            assertEquals(parentPosition + 1, childPosition, "子档首帧必须接在父边界之后");
        }
        assertEquals(parentSha, sha256(world), "子档写入不得改变父档任何字节");
        try (WorldRuntime parent = ComposedPharmaScenario.open(world)) {
            assertEquals(new BigDecimal("1100.00"),
                    parent.snapshot().accounts().get(CASH).cash(), "父档现金不受子档影响");
            assertTrue(ComposedCompanyReport.of(parent, PHARMA).balanced());
        }
    }

    // ------------------------------------------------------------------ 故障注入

    private void runFault(edu.cufe.auction.world.composed.FundedProbes.FailingChannel.Mode mode,
            String tag) throws Exception {
        Path dirPath = dir.resolve(tag);
        Files.createDirectories(dirPath);
        Path source = dirPath.resolve("source.log");
        if (!Files.exists(source)) {
            try (WorldRuntime world = ComposedPharmaScenario.open(source)) {
                ComposedPharmaWorldTest.grantBudgetAndAppointManager(world);
                world.scheduleResearchRegister("fl-reg", "fl-reg-task", 13_001L, PHARMA, "ivan",
                        "p1", 1_000L);
                world.advanceTo(13_001L);
            }
        }
        byte[] clean = Files.readAllBytes(source);
        Path control = dirPath.resolve("control.log");
        Path attacked = dirPath.resolve("attacked.log");
        Files.write(control, clean);
        Files.write(attacked, clean);
        long due = 15_001L;
        long barrier = 14_001L;

        Map<String, String> controlAfter;
        try (WorldRuntime runtime = ComposedPharmaScenario.open(control)) {
            runtime.scheduleResearchBegin(tag + "-b0", tag + "-b0-task", due, PHARMA, "ivan",
                    "p1", ResearchModel.Stage.RESEARCH, 1_000L);
            runtime.advanceTo(barrier);
            runtime.advanceTo(due);
            controlAfter = components(runtime);
            assertEquals(new BigDecimal("1800.00"),
                    runtime.snapshot().accounts().get(CASH).cash(), "对照副本只付一次 200");
            assertEquals(new BigDecimal("700.00"),
                    runtime.snapshot().accounts().get(CONTRACTOR).cash());
        }

        Map<String, String> before;
        Map<String, String> afterFailure;
        String failure;
        try (WorldRuntime runtime = ComposedPharmaScenario.open(attacked)) {
            runtime.scheduleResearchBegin(tag + "-b0", tag + "-b0-task", due, PHARMA, "ivan",
                    "p1", ResearchModel.Stage.RESEARCH, 1_000L);
            runtime.advanceTo(barrier);
            before = components(runtime);
            inject(runtime, mode);
            String caught = "NONE";
            try {
                runtime.advanceTo(due);
            } catch (IOException | UncheckedIOException expected) {
                caught = String.valueOf(expected.getMessage());
            }
            failure = caught;
            afterFailure = components(runtime);
        }
        assertTrue(!"NONE".equals(failure), tag + " 必须真实失败");
        assertEquals(before, afterFailure, tag + " 故障后内存不得半应用（资金/预算/研发都不得写一半）");

        try (WorldRuntime runtime = ComposedPharmaScenario.open(attacked)) {
            Map<String, String> reopened = components(runtime);
            boolean pre = before.equals(reopened);
            boolean post = controlAfter.equals(reopened);
            assertTrue(pre || post, tag + " 重开只允许完整前态或完整后态");
            long positionBefore = runtime.snapshot().position();
            PendingTask finish = pendingOf(runtime, "research-result:" + PHARMA + ":p1:RESEARCH");
            runtime.advanceTo(due);
            Map<String, String> resumed = components(runtime);
            runtime.advanceTo(due);
            assertEquals(resumed, components(runtime), tag + " 同一时点再推进不得重复付款");
            assertEquals(new BigDecimal("1800.00"),
                    runtime.snapshot().accounts().get(CASH).cash(),
                    tag + " 阶段付款只发生一次");
            assertEquals(new BigDecimal("700.00"),
                    runtime.snapshot().accounts().get(CONTRACTOR).cash());
            // 资金、公司预算与研究状态在同一批里推进：三者必须同时等于对照后态
            assertEquals(controlAfter.get("research"), resumed.get("research"),
                    tag + " 研发状态必须与对照一致");
            assertEquals(controlAfter.get("companyGovernance"), resumed.get("companyGovernance"),
                    tag + " 额外公司董事会预算必须与对照一致");
            assertTrue(runtime.snapshot().position() >= positionBefore);
            assertTrue(finish != null || resumed.equals(controlAfter));
        }
    }

    private static PendingTask pendingOf(WorldRuntime runtime, String taskId) {
        for (PendingTask task : runtime.snapshot().pending()) {
            if (task.taskId().equals(taskId)) {
                return task;
            }
        }
        return null;
    }

    private static void inject(WorldRuntime runtime,
            edu.cufe.auction.world.composed.FundedProbes.FailingChannel.Mode mode)
            throws Exception {
        var journalField = WorldRuntime.class.getDeclaredField("journal");
        journalField.setAccessible(true);
        Object journal = journalField.get(runtime);
        var channelField = journal.getClass().getDeclaredField("channel");
        channelField.setAccessible(true);
        FileChannel real = (FileChannel) channelField.get(journal);
        channelField.set(journal, new edu.cufe.auction.world.composed.FundedProbes.FailingChannel(
                real, mode, 0));
    }

    private static Map<String, String> components(WorldRuntime runtime) throws Exception {
        return FundedProbes.components(runtime);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }

    /** 供探针复用的世界摘要（只读）。 */
    static List<String> describe(WorldRuntime runtime) throws Exception {
        var project = runtime.researchRun().orElseThrow().state().projects().get("p1");
        var report = ResearchModel.report(runtime.researchRun().orElseThrow().state(),
                runtime.snapshot().accounts());
        return List.of("CASH=" + runtime.snapshot().accounts().get(CASH).cash().toPlainString(),
                "PROJECT=" + project.status(),
                "AMORTIZED=" + project.amortized().toPlainString(),
                "EQUITY=" + report.equity().toPlainString(),
                "BALANCED=" + ComposedCompanyReport.of(runtime, PHARMA).balanced());
    }
}
