package edu.cufe.auction.world.pharmaaudit;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.ResearchDecisionTools;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedPharmaScenario;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 独立药企 Debug 档的三进程探针：每个进程都是真正的独立 JVM（打印自身 PID），
 * 依次「建链 → 恢复并完成阶段付款 → 再恢复并推进下一阶段」。
 *
 * <p>用法：{@code <journal> <build|continue|finish> <digestIn> <digestOut>}。每阶段结束时把
 * 真实读数写进摘要文件；下一阶段先逐分量核对，再推进。摘要刻意分成「公司自身账户」
 * （RND-PHARMA）与「非本公司账户」（ENG-A），验证两家公司账户互不影响。</p>
 */
public final class PharmaDebugCrossJvmProbe {

    private static final String PHARMA = ComposedPharmaScenario.PHARMA;
    private static final String CASH = ComposedPharmaScenario.PHARMA_CASH;
    private static final String ENG_A = "ENG-A";

    private PharmaDebugCrossJvmProbe() {
    }

    /**
     * @param args 日志、阶段、输入摘要、输出摘要
     * @throws Exception 打开、推进或比对失败
     */
    public static void main(String[] args) throws Exception {
        Path journal = Path.of(args[0]);
        String step = args[1];
        Path digestIn = Path.of(args[2]);
        Path digestOut = Path.of(args[3]);
        System.out.println("PID " + ProcessHandle.current().pid());
        if ("build".equals(step)) {
            try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
                PharmaAuditHarness.approveBudgetAndAppoint(world, "kate");
                require(world.isActiveManager(PHARMA, "kate", world.snapshot().timeMillis()),
                        "真实任免必须产生有效经理");
                String registered = PharmaAuditHarness.tool(world, "kate", "jvm-register",
                        ResearchDecisionTools.ACT,
                        Map.of("action", "REGISTER", "projectId", "p1", "stage", "",
                                "meetingId", ""));
                require(!"".equals(registered), "立项必须产生真实完成回执");
                long finish = PharmaAuditHarness.reply(world,
                        PharmaAuditHarness.open(world, "kate", "jvm-begin"),
                        ResearchDecisionTools.ACT, Map.of("action", "BEGIN", "projectId", "p1",
                                "stage", "RESEARCH", "meetingId", ""));
                world.advanceTo(finish - 500L);
                Map<String, String> digest = digest(world);
                digest.put("researchFinishDue", Long.toString(finish));
                write(digestOut, digest);
                System.out.println("PHARMA_JVM_OK step=" + step + " pid="
                        + ProcessHandle.current().pid());
            }
            return;
        }
        Map<String, String> expected = read(digestIn);
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            Map<String, String> actual = digest(world);
            for (String key : expected.keySet()) {
                if ("researchFinishDue".equals(key) || "journalSha".equals(key)) {
                    continue;
                }
                require(expected.get(key).equals(actual.get(key)),
                        step + " 恢复后分量不一致：" + key + " expect=" + expected.get(key)
                                + " actual=" + actual.get(key));
            }
            if ("continue".equals(step)) {
                long finish = Long.parseLong(expected.get("researchFinishDue"));
                world.advanceTo(finish);
                world.advanceTo(finish + PharmaAuditHarness.DAY);
                require(ResearchModel.StageStatus.COMPLETED.equals(
                        world.researchRun().orElseThrow().state().projects().get("p1")
                                .stages().get(0).status()), "第一阶段必须真实完成");
                write(digestOut, digest(world));
                System.out.println("PHARMA_JVM_OK step=" + step + " pid="
                        + ProcessHandle.current().pid());
                return;
            }
            if ("finish".equals(step)) {
                long finish = PharmaAuditHarness.reply(world,
                        PharmaAuditHarness.open(world, "kate", "jvm-begin-2"),
                        ResearchDecisionTools.ACT, Map.of("action", "BEGIN", "projectId", "p1",
                                "stage", "DEVELOPMENT", "meetingId", ""));
                world.advanceTo(finish);
                world.advanceTo(finish + PharmaAuditHarness.DAY);
                require(ResearchModel.StageStatus.COMPLETED.equals(
                        world.researchRun().orElseThrow().state().projects().get("p1")
                                .stages().get(1).status()), "第二阶段必须真实完成");
                var branch = world.createBranch("pharma-debug-child", world.snapshot().position(),
                        edu.cufe.auction.world.BranchOrigin.Kind.OPERATOR_BRANCH, "独立药企分支继续注册阶段");
                var parentBefore = world.snapshot();
                var parentProfile = world.profile();
                world.close(); // Windows 独占日志锁关闭后才能核对文件字节。
                byte[] parentBytes = Files.readAllBytes(journal);
                try (WorldRuntime child = WorldRuntime.openBranch(branch.childFile(),
                        id -> branch.sealedPrefix(), parentProfile)) {
                    long childFinish = PharmaAuditHarness.reply(child,
                            PharmaAuditHarness.open(child, "kate", "jvm-child-registration"),
                            ResearchDecisionTools.ACT, Map.of("action", "BEGIN", "projectId", "p1",
                                    "stage", "REGISTRATION", "meetingId", ""));
                    child.advanceTo(childFinish);
                    require("1100.00".equals(money(child.snapshot().accounts().get(CASH).cash())),
                            "子世界注册阶段付款400必须落在自身公司账户");
                }
                require(java.util.Arrays.equals(parentBytes, Files.readAllBytes(journal)), "父日志字节保持不变");
                Map<String, String> finalDigest;
                try (WorldRuntime parent = ComposedPharmaScenario.openDebug(journal)) {
                    require(parentBefore.equals(parent.snapshot()), "子世界不得改父世界状态");
                    finalDigest = digest(parent);
                }
                finalDigest.put("branchParentUnchanged", "true");
                finalDigest.put("branchCash", "1100.00");
                write(digestOut, finalDigest);
                System.out.println("PHARMA_JVM_OK step=" + step + " pid="
                        + ProcessHandle.current().pid());
                return;
            }
        }
        throw new IllegalArgumentException("未知阶段：" + step);
    }

    /** 真实读数摘要：公司自身账户、非本公司账户、治理、待办与研究状态 + 角色输入摘要。 */
    static Map<String, String> digest(WorldRuntime world) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("pharmaCash", money(world.snapshot().accounts().get(CASH).cash()));
        map.put("engACash", money(world.snapshot().accounts().get(ENG_A).cash()));
        map.put("contractorCash", money(world.snapshot().accounts()
                .get("RESEARCH-CONTRACTOR").cash()));
        map.put("pharmaBudget", world.boardOfCompany(PHARMA).orElseThrow().board().budgets()
                .toString());
        map.put("managerActive", Boolean.toString(
                world.isActiveManager(PHARMA, "kate", world.snapshot().timeMillis())));
        map.put("charterCompany", world.boardOfCompany(PHARMA).orElseThrow()
                .board().charter().companyPartyId());
        map.put("researchCompany", world.researchCompanyId());
        List<String> pending = new ArrayList<>();
        for (PendingTask task : world.snapshot().pending()) {
            pending.add(task.taskId() + "@" + task.dueTimeMillis() + "#" + task.kind());
        }
        pending.sort(String::compareTo);
        map.put("pending", String.join("|", pending));
        map.put("projectStages", world.researchRun().orElseThrow().state().projects()
                .get("p1").stages().toString());
        map.put("projectStatus", world.researchRun().orElseThrow().state().projects()
                .get("p1").status().name());
        map.put("kateInput", sha256(json(world.engineeringRoleInput("kate"))));
        map.put("frankInput", sha256(json(world.engineeringRoleInput("frank"))));
        return map;
    }

    private static String money(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    private static String json(JsonNode node) {
        return node == null ? "null" : node.toString();
    }

    private static String sha256(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void write(Path file, Map<String, String> digest) throws Exception {
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(digest).forEach((key, value) ->
                builder.append(key).append(' ').append(value).append('\n'));
        Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> read(Path file) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space > 0) {
                map.put(line.substring(0, space), line.substring(space + 1));
            }
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("摘要文件没有分量：" + file);
        }
        return map;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            System.out.println("PHARMA_JVM_BAD " + message);
            throw new IllegalStateException(message);
        }
    }
}
