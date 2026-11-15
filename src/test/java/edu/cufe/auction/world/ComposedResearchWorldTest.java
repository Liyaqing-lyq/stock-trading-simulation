package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.ComposedCompanyReport;
import edu.cufe.auction.world.composed.ComposedResearchScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证研发真正进入银行组合档：原意、唯一账、批准、摊销、恢复与真实分支。 */
class ComposedResearchWorldTest {
    @TempDir Path dir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void researchAndBankShareOneWorldWithoutDuplicatedEquity(boolean funded) throws Exception {
        Path file = dir.resolve("combined.journal");
        ResearchRun finalResearch;
        BranchProfile profile;
        Path childFile;
        Path prefixFile;
        try (WorldRuntime world = open(file, funded)) {
            assertTrue(world.profile().manifest().hasBank());
            assertTrue(world.profile().manifest().hasCommandIntent());
            assertTrue(world.profile().manifest().hasCustodyFill());
            assertEquals(new BigDecimal("9000.00"),
                    ComposedCompanyReport.of(world, "eng-a").totalEquity(),
                    "冻结开局为现金5000及400股、每股成本10的证券；不只有研发模型现金");
            var bank = world.bankOf().orElseThrow();
            long docsAt = ResearchCommercialWorldTest.buildCompletedProject(world);
            assertEquals(bank, world.bankOf().orElseThrow(), "研发现金付费不能无因改变银行三账");
            var completed = ComposedCompanyReport.of(world, "eng-a");
            assertEquals(new BigDecimal("4100.00"), completed.assets().get("经营现金"));
            assertEquals(new BigDecimal("4000.00"), completed.assets().get("证券持仓成本"));
            assertEquals(new BigDecimal("700.00"), completed.assets().get("研发在研资产"));
            assertEquals(new BigDecimal("8800.00"), completed.totalAssets());
            assertEquals(new BigDecimal("8800.00"), completed.totalEquity());
            assertEquals(new BigDecimal("-200.00"), completed.profit());
            assertTrue(world.engineeringStatement("eng-a").orElseThrow().balanced());
            assertTrue(world.companyAudit("eng-a").isPresent());

            ResearchCommercialWorldTest.readEvidence(world, docsAt,
                    List.of("alice", "bob", "carol", "dave"));
            long meetingAt = docsAt + 10_000;
            ResearchCommercialWorldTest.runMeeting(world, meetingAt, "alice",
                    List.of("alice", "bob", "carol"));
            long due = meetingAt + 5_200;
            world.openRoleSession("dave").approveCommercialization("approve", "approve-task",
                    due, "eng-a", "p1", "cm-1", 1_000);
            world.advanceTo(due);
            assertEquals(ResearchModel.ProjectStatus.APPROVED,
                    world.researchRun().orElseThrow().state().projects().get("p1").status());
            world.advanceTo(30L * 86_400_000);
            finalResearch = world.researchRun().orElseThrow();
            assertTrue(finalResearch.state().projects().get("p1").amortized().signum() > 0);
            var report = ComposedCompanyReport.of(world, "eng-a");
            assertEquals(report.totalAssets(), report.totalLiabilities().add(report.totalEquity()));
            assertTrue(report.profit().compareTo(new BigDecimal("-200.00")) < 0);
            assertTrue(world.engineeringStatement("eng-a").orElseThrow().balanced());
            profile = world.profile();
            var receipt = world.createBranch("research-child", world.snapshot().position(),
                    BranchOrigin.Kind.OPERATOR_BRANCH, "研发与银行组合分支隔离");
            childFile = receipt.childFile();
            prefixFile = receipt.sealedPrefix();
        }
        try (WorldRuntime world = open(file, funded)) {
            assertEquals(finalResearch, world.researchRun().orElseThrow());
            assertNotNull(world.bankOf().orElseThrow());
        }
        try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> prefixFile, profile)) {
            assertEquals(finalResearch, child.researchRun().orElseThrow());
            child.advanceTo(30L * 86_400_000 + 1);
            assertEquals("research-child", child.profile().manifest().runId());
        }
        try (WorldRuntime parent = open(file, funded)) {
            assertEquals(30L * 86_400_000, parent.snapshot().timeMillis());
            assertEquals(finalResearch, parent.researchRun().orElseThrow());
        }
    }

    private static WorldRuntime open(Path file, boolean funded) throws Exception {
        return funded ? ComposedResearchScenario.openFunded(file) : ComposedResearchScenario.open(file);
    }
}
