package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static edu.cufe.auction.world.composed.EditorialReviewFeedbackTest.*;
import static org.junit.jupiter.api.Assertions.*;

class WithdrawalSourceIsolationTest {
    @TempDir Path dir;

    @Test void withdrawingThePublicArticleProtectsItsPrivateSourceAndBlocksAnAlreadyApprovedCorrection() throws Exception {
        Path path = dir.resolve("wire.journal");
        String privateId = "restricted-orig-398", privateBody = "仅交给记者的原始核验记录", article;
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(path)) {
            long at = w.snapshot().timeMillis() + 1;
            w.schedulePublishDocument("supply","supply",at,privateId,"investor",InformationModel.Audience.direct("rita"),
                    "单独答复",privateBody,"直接提供",1000,List.of());
            w.advanceTo(at);
            var evidence = read(w,"rita",privateId,1);
            var alias = new WorldRoleSession.AliasDraft(privateId,1,"一名消息人士");
            String id = propose(w,"source-isolation","核实供货情况");
            submit(w,new WorldRoleSession.MediaSubmitDraft(id,0,"一项供货核实","公开的核验结果。","受访者说法",List.of(evidence),List.of(alias)));
            review(w,id,1,true,"已核稿。");
            work(w,"ed",MediaRun.KIND_PUBLISH,(s,c,t,d) -> s.publishStory(c,t,d,new WorldRoleSession.MediaStoryDraft(id,1)));
            article = row(w,"ed",id).publicDocId();
            work(w,"rita",MediaRun.KIND_CORRECTION,(s,c,t,d) -> s.startCorrection(c,t,d,new WorldRoleSession.MediaStoryDraft(id,1)));
            submit(w,new WorldRoleSession.MediaSubmitDraft(id,1,"修订中的供货消息","拟更新内容。","受访者说法",List.of(evidence),List.of(alias)));
            review(w,id,2,true,"已核第二稿。");
            long now = w.snapshot().timeMillis(), cost = row(w,"ed",id).withdrawWorkMillis();
            var priorApproval = w.openRoleSession("ed");
            w.openRoleSession("ed").withdrawStory("withdraw","withdraw",now+cost,
                    new WorldRoleSession.MediaWithdrawalDraft(id,1,MediaModel.WithdrawalReason.QUOTATION_PERMISSION));
            assertThrows(IllegalArgumentException.class, () -> priorApproval.publishStory("overlap","overlap",
                    now+cost,new WorldRoleSession.MediaStoryDraft(id,2)));
            w.advanceTo(now+cost);
            priorApproval.publishStory("stale","stale",now+2*cost,new WorldRoleSession.MediaStoryDraft(id,2));
            w.advanceTo(now+2*cost);
            var desk = w.snapshot().media().orElseThrow();
            assertTrue(desk.reports().values().stream().anyMatch(r -> MediaRun.KIND_WITHDRAW.equals(r.kind()) && r.accepted()));
            assertTrue(desk.reports().values().stream().anyMatch(r -> r.kind().equals(MediaRun.KIND_PUBLISH)
                    && !r.accepted() && r.reason().equals(MediaModel.REASON_STATE)));
            var published = row(w,"ed",id);
            assertEquals("WITHDRAWN",published.status());assertEquals(2,published.publicVersion());
            assertEquals(2,published.revisionNo());
            read(w,"dave",article,2);
            var reader = w.roleView("dave");
            var observed = reader.observations().stream().filter(e -> e.ref().docId().equals(article)).findFirst().orElseThrow();
            assertTrue(observed.body().contains("引述授权存在问题"));assertTrue(observed.body().contains("ed"));
            assertFalse(observed.body().contains(privateBody));assertFalse(observed.body().contains(privateId));
            assertFalse(w.canCite("dave",privateId,1));assertTrue(w.canCite("dave",article,2));
            assertFalse(reader.catalog().stream().anyMatch(d -> privateId.equals(d.docId())));
            var publicRoot = desk.stateOf("wire").lineage().stream().filter(l -> l.docId().equals(article) && l.version()==2).findFirst().orElseThrow();
            assertEquals(1, publicRoot.roots().size());
            String signed = publicRoot.roots().get(0).docId();
            assertFalse(signed.contains(privateId));
            assertThrows(IllegalArgumentException.class,()->w.schedulePublishDocumentVersion("fake","fake",
                    w.snapshot().timeMillis()+1,signed,"伪造签发","伪造","伪造",1000,List.of()));
        }
        byte[] initial = Files.readAllBytes(path);
        try (var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(path)) {
            assertTrue(w.canCite("dave",article,2));assertFalse(w.canCite("dave",privateId,1));
        }
        assertArrayEquals(initial,Files.readAllBytes(path));
    }
}
