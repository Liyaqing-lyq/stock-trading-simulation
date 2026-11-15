package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.ComposedCompanyReport;
import edu.cufe.auction.world.composed.ComposedResearchScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 三个独立进程分别完成研发、批准和真实子世界续跑，不注入世界状态。 */
public final class ComposedResearchReopenProbe {
    private ComposedResearchReopenProbe() { }

    /** @param args 工作目录及步骤编号；@throws Exception 任一持久化或核对失败 */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int step = Integer.parseInt(args[1]);
        boolean funded = args.length > 2 && Boolean.parseBoolean(args[2]);
        try (WorldRuntime parent = funded ? ComposedResearchScenario.openFunded(dir.resolve("world.journal"))
                : ComposedResearchScenario.open(dir.resolve("world.journal"))) {
            if (step == 0) {
                ResearchCommercialWorldTest.buildCompletedProject(parent);
                assertEquals(ResearchModel.ProjectStatus.COMPLETED,
                        parent.researchRun().orElseThrow().state().projects().get("p1").status());
                if (funded) {
                    edu.cufe.auction.world.composed.ComposedWorldSelfTest.appoint(parent,
                            parent.snapshot().timeMillis() + 10, "cufe", "erin", "mfg-manager", "mfg-material");
                    edu.cufe.auction.world.composed.ManufacturingFundingChainTest.budgetMeeting(parent,
                            "cufe", "mfg-budget", "10000.00", "10000.00");
                    long now = parent.snapshot().timeMillis();
                    parent.openRoleSession("erin").bankAction("deposit", "deposit-task", now + 100, 1,
                            new WorldRoleSession.BankActionDraft("deposit", edu.cufe.auction.world.bank.BankActions.Kind.DEPOSIT,
                                    "north", "cufe", new java.math.BigDecimal("800.00"), 0, 0, "", "", "", ""));
                    parent.advanceTo(now + 100);
                    parent.scheduleFundedManufacturing("jvm-po", "jvm-po-task", now + 200, "erin",
                            "PURCHASE_ORDER", "JVM-PO", "", 10, null, 0, -1,
                            ManufacturingFunding.POLICY_FROZEN_MAPPING_V1, "DEPOSIT|north|cufe", "CASH|SUP", 1);
                    parent.advanceTo(now + 200);
                }
            } else if (step == 1) {
                long docsAt = parent.snapshot().timeMillis();
                ResearchCommercialWorldTest.readEvidence(parent, docsAt,
                        List.of("alice", "bob", "carol", "dave"));
                long meeting = docsAt + 10_000;
                ResearchCommercialWorldTest.runMeeting(parent, meeting, "alice",
                        List.of("alice", "bob", "carol"));
                long due = meeting + 5_200;
                parent.openRoleSession("dave").approveCommercialization("approve", "approve-task",
                        due, "eng-a", "p1", "cm-1", 1_000);
                parent.advanceTo(due);
                assertEquals(ResearchModel.ProjectStatus.APPROVED,
                        parent.researchRun().orElseThrow().state().projects().get("p1").status());
                var receipt = parent.createBranch("research-jvm-child", parent.snapshot().position(),
                        BranchOrigin.Kind.OPERATOR_BRANCH, "新进程验证研发银行组合分支");
                Files.write(dir.resolve("branch.txt"), List.of(receipt.childFile().toString(),
                        receipt.sealedPrefix().toString()));
            } else if (step == 2) {
                var paths = Files.readAllLines(dir.resolve("branch.txt"));
                long position = parent.snapshot().position();
                var research = parent.researchRun().orElseThrow();
                var bank = parent.bankOf().orElseThrow();
                var manufacturing = parent.snapshot().manufacturing().orElseThrow();
                var cash = parent.snapshot().accounts();
                try (WorldRuntime child = WorldRuntime.openBranch(Path.of(paths.get(0)),
                        id -> Path.of(paths.get(1)), parent.profile())) {
                    assertEquals(research, child.researchRun().orElseThrow());
                    assertEquals(bank, child.bankOf().orElseThrow());
                    child.advanceTo(30L * 86_400_000);
                    assertTrue(child.researchRun().orElseThrow().state().projects().get("p1")
                            .amortized().signum() > 0);
                    var report = ComposedCompanyReport.of(child, "eng-a");
                    assertEquals(report.totalAssets(), report.totalLiabilities().add(report.totalEquity()));
                    if (funded) {
                        assertEquals(10, child.snapshot().manufacturing().orElseThrow().ledger().rawMaterials().quantity());
                        assertEquals(new java.math.BigDecimal("300.00"), child.bankOf().orElseThrow()
                                .decode(child.snapshot().accounts()).state().depositOf("north", "cufe"));
                        assertEquals(new java.math.BigDecimal("600.00"), child.snapshot().accounts().get("SUP").cash());
                        var manufacturingReport = ComposedCompanyReport.of(child, "cufe");
                        assertEquals(manufacturingReport.totalAssets(), manufacturingReport.totalLiabilities()
                                .add(manufacturingReport.totalEquity()));
                    }
                }
                assertEquals(position, parent.snapshot().position());
                assertEquals(research, parent.researchRun().orElseThrow());
                assertEquals(bank, parent.bankOf().orElseThrow());
                assertEquals(manufacturing, parent.snapshot().manufacturing().orElseThrow());
                assertEquals(cash, parent.snapshot().accounts());
            } else throw new IllegalArgumentException("未知步骤");
            System.out.println("RESEARCH_COMPOSED_JVM_OK step=" + step);
        }
    }
}
