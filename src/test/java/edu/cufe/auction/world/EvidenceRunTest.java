package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EvidenceRun 的定向测试：配置冻结、按机构隔离、别名/共享、JSON 往返与拒绝。 */
class EvidenceRunTest {

    private static EvidenceRun run() {
        return EvidenceRun.open(new EvidenceRun.Config(1_000L), List.of("media-a", "media-b"));
    }

    @Test
    void openCreatesEmptyStatePerOrganization() {
        EvidenceRun run = run();
        assertEquals(1_000L, run.config().shareMillis());
        assertTrue(run.stateOf("media-a").aliases().isEmpty());
        assertTrue(run.stateOf("media-b").shares().isEmpty());
        assertTrue(run.stateOf("media-unknown").aliases().isEmpty(), "未知机构返回空状态");
        assertTrue(run.states().containsKey("media-a"));
    }

    @Test
    void withStateIsPerOrganizationAndDoesNotLeak() {
        MediaEvidence.AliasBinding alias = new MediaEvidence.AliasBinding("S1", 1,
                new MediaModel.Ref("D-PRIV", 1L), "一位业内人士");
        MediaEvidence.State next = new MediaEvidence.State(List.of(alias), List.of());
        EvidenceRun updated = run().withState("media-a", next);
        assertEquals("一位业内人士", MediaEvidence.labelOf(updated.stateOf("media-a"), "S1", 1,
                new MediaModel.Ref("D-PRIV", 1L)));
        assertTrue(updated.stateOf("media-b").aliases().isEmpty(), "另一机构不受影响");
        assertTrue(run().stateOf("media-a").aliases().isEmpty(), "原状态不变");
    }

    @Test
    void jsonRoundTripKeepsConfigAndAliases() {
        MediaEvidence.AliasBinding alias = new MediaEvidence.AliasBinding("S1", 2,
                new MediaModel.Ref("D-PRIV", 1L), "匿名信源");
        EvidenceRun run = run().withState("media-a",
                new MediaEvidence.State(List.of(alias), List.of()));
        String json = run.toJson();
        EvidenceRun restored = EvidenceRun.fromJson(json);
        assertEquals(run.config(), restored.config());
        assertEquals(1, restored.stateOf("media-a").aliases().size());
        assertEquals("匿名信源", MediaEvidence.labelOf(restored.stateOf("media-a"), "S1", 2,
                new MediaModel.Ref("D-PRIV", 1L)));
        assertFalse(restored.sharesByOrganization().isEmpty());
    }

    @Test
    void invalidConfigAndJsonAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceRun.Config(0L));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceRun.Config(-5L));
        assertThrows(IllegalArgumentException.class, () -> EvidenceRun.fromJson(""));
        assertThrows(IllegalArgumentException.class, () -> EvidenceRun.fromJson("{\"config\":{} }"));
        assertThrows(IllegalArgumentException.class, () -> EvidenceRun.fromJson("{oops}"));
    }
}
