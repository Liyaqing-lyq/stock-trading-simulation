package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.WorldRuntime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 右开截止：批准窗口是 [opensAt, closesAt)。
 *
 * <p>约束：本期**没有现存授权**、批准人都已对这次草稿真实读完；因此
 * {@code APPROVALS_CLOSED / 未实读 / 焦点} 都不可能冒充「窗口已关」。
 * 同一时刻还到期一条系统关期任务（RULES 先于 COMPLETION），所以这里同时验证同刻调度顺序下
 * 批准仍得到明确的窗口原因。</p>
 */
class ComposedPeriodicDeadlineTest {

    private static final String NORTH = ComposedWorldScenario.BANK;

    @Test
    void approveIsClosedAtClosesAtAndAfter() throws Exception {
        Path dir = Files.createTempDirectory("periodic-deadline").resolve("world.log");
        try (WorldRuntime runtime = ComposedWorldScenario.open(dir)) {
            PeriodicModel.Policy policy = runtime.companyGovernance().orElseThrow()
                    .periodsOf(NORTH).policy();
            ComposedWorldSelfTest.appoint(runtime, runtime.snapshot().timeMillis(), NORTH, "erin",
                    "dl-north", "dl-north");

            long boundary = PeriodicModel.PERIOD_MILLIS;
            long opensAt = boundary + policy.materialLeadMillis();
            long closesAt = opensAt + policy.reviewWindowMillis();
            runtime.advanceTo(boundary);

            long prepareAt = opensAt + 2L;
            runtime.schedulePeriodicAction("pp-prepare-1-1", "pp-prepare-1-1-t", prepareAt,
                    NORTH, "erin", "PREPARE", 1L, "", 1L, "", 0L, "", List.of(), "", 1L);
            runtime.advanceTo(prepareAt);
            InformationModel.Document draft = newestDraft(runtime);
            assertNotNull(draft, "PREPARE 后必须有真实草稿");
            String draftId = periods(runtime).drafts().stream()
                    .filter(d -> d.period() == 1L).findFirst().orElseThrow().draftId();
            String draftDoc = draft.docId();

            String[] readers = {"alice", "bob", "carol"};
            for (String person : readers) {
                ComposedWorldSelfTest.readToFinish(runtime, person, "dl-" + person, draftDoc, 1L,
                        0L);
            }

            // 前提：本期没有任何授权，也没有任何批准。
            assertEquals(0, periods(runtime).approvals().size(), "前置：不得已有批准");
            assertEquals(0, periods(runtime).authorizations().size(), "前置：不得已有授权");

            long insideAt = closesAt - 2L;
            approve(runtime, "alice", draftId, insideAt);
            approve(runtime, "bob", draftId, closesAt);
            approve(runtime, "carol", draftId, closesAt + 1L);

            runtime.advanceTo(insideAt);
            String inside = finish(runtime, "alice");
            runtime.advanceTo(closesAt);          // 同刻：先 RULES 关期，再 COMPLETION 批准
            String atEdge = finish(runtime, "bob");
            runtime.advanceTo(closesAt + 1L);
            String after = finish(runtime, "carol");

            assertEquals("ACCEPTED", inside, "closesAt−2 的批准必须被接受，实际 finish=" + inside
                    + " begin=" + begin(runtime, "alice"));
            assertEquals(PeriodicModel.REASON_REVIEW_WINDOW_CLOSED, atEdge,
                    "closesAt 当刻的批准必须给出窗口原因，实际 finish=" + atEdge
                            + " begin=" + begin(runtime, "bob"));
            assertEquals(PeriodicModel.REASON_REVIEW_WINDOW_CLOSED, after,
                    "closesAt+1 的批准必须给出窗口原因，实际 finish=" + after
                            + " begin=" + begin(runtime, "carol"));
            assertEquals(1, periods(runtime).approvals().size(),
                    "窗口外批准不得增加批准数，实际 " + periods(runtime).approvals().size());
            assertTrue(periods(runtime).authorizations().isEmpty(),
                    "本世界不应出现授权，实际 " + periods(runtime).authorizations().size());
        }
    }

    private static void approve(WorldRuntime runtime, String person, String draftId, long due)
            throws Exception {
        runtime.schedulePeriodicAction("pp-approve-" + person + "-1",
                "pp-approve-" + person + "-1-t", due, NORTH, person, "APPROVE", 1L, draftId, 1L,
                "", 0L, "", List.of(), "", 1L);
    }

    private static String finish(WorldRuntime runtime, String person) {
        return runtime.taskCommitResult("pp-approve-" + person + "-1-t" + ":finish");
    }

    private static String begin(WorldRuntime runtime, String person) {
        return runtime.taskCommitResult("pp-approve-" + person + "-1-t");
    }

    private static PeriodicModel.CompanyPeriods periods(WorldRuntime runtime) {
        return runtime.companyGovernance().orElseThrow().periodsOf(NORTH);
    }

    private static InformationModel.Document newestDraft(WorldRuntime runtime) {
        InformationModel.Document found = null;
        for (InformationModel.Document doc : runtime.snapshot().information().orElseThrow()
                .information().documents()) {
            if (doc.docId().startsWith("periodic-draft-doc:" + NORTH)) {
                found = doc;
            }
        }
        return found;
    }
}
