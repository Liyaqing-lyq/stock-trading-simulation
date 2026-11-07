package edu.cufe.auction.world;

import java.util.*;
import edu.cufe.auction.world.RoleAccess.*;
import edu.cufe.auction.world.OpeningRegistry.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/** Root独立正常入口回归：真实任职与阅读，机构、时间、授权与回忆边界。 */
class MediaModelBoundaryTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static OpeningRegistry registry() {
        var parties = new ArrayList<Party>();
        for (String id : List.of("a", "b", "fund")) parties.add(new Party(id, PartyKind.ORGANIZATION, id));
        for (String id : List.of("writer", "editor", "other", "researcher", "source")) parties.add(new Party(id, PartyKind.PERSON, id));
        return new OpeningRegistry(parties, List.of(), List.of(), List.of());
    }
    static RoleAccess.State roles(long writerEnd, long editorEnd) {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
            new Appointment("aw", "writer", "a", Role.JOURNALIST, 0, writerEnd, null),
            new Appointment("bw", "writer", "b", Role.JOURNALIST, 0, 100000, null),
            new Appointment("ae", "editor", "a", Role.EDITOR, 0, editorEnd, null),
            new Appointment("be", "other", "b", Role.EDITOR, 0, 100000, null),
            new Appointment("fr", "researcher", "fund", Role.RESEARCHER, 0, 100000, null)));
    }
    static MediaModel.Policy policy(String org) {
        return new MediaModel.Policy(org, Map.of(MediaModel.TemplateKind.WIRE, 1), 10, 10, 10, 1, 1,
            new CognitionRecall.Policy(100, 1, 100, 100000));
    }
    static String draftId(String org,String story) {
        var reg=new OpeningRegistry(List.of(new Party(org,PartyKind.ORGANIZATION,org),new Party("writer",PartyKind.PERSON,"writer")),List.of(),List.of(),List.of());
        var access=new RoleAccess.State(List.of(),List.of(),List.of(),List.of(new Appointment("writer-role","writer",org,Role.JOURNALIST,0,100000,null)));
        var p=policy(org);var proposed=MediaModel.propose(MediaModel.open(p,reg),p,access,"writer",new MediaModel.ProposeRequest(story,MediaModel.TemplateKind.WIRE,"Topic",0));
        check(proposed.accepted(),proposed.reason());return proposed.state().stories().get(0).draftDocId();
    }
    static InformationModel.State read(InformationModel.State info, RoleAccess.State access,
                                        String person, String doc, long at) {
        var start = InformationModel.startRead(info, access, person, doc, 1, at);
        check(start.status() == InformationModel.Status.STARTED, "start " + start.status());
        var r = start.state().active().stream().filter(x -> x.personPartyId().equals(person)).findFirst().orElseThrow();
        var end = InformationModel.completeRead(start.state(), access, person, r.readId(), r.dueAt());
        check(end.status() == InformationModel.Status.COMPLETED, "read " + end.status());
        return end.state();
    }
    static class Fixture {
        RoleAccess.State access = roles(100000,100000);
        MediaModel.Policy policy;
        MediaModel.State media;
        InformationModel.State info = InformationModel.open(registry(), List.of("writer","editor","other","researcher","source"));
        Fixture() { this(policy("a")); }
        Fixture(MediaModel.Policy frozen) {
            policy=frozen; media=MediaModel.open(policy,registry());
            info = InformationModel.publish(info,"evidence","source",InformationModel.Audience.publicAudience(),
                new InformationModel.VersionDraft("Evidence","Public source body","Unverified source text",0,1,List.of("evidence")));
            info = read(info,access,"writer","evidence",1);
            var proposal = MediaModel.propose(media,policy,access,"writer",new MediaModel.ProposeRequest("story",MediaModel.TemplateKind.WIRE,"Secret unpublished topic",3));
            check(proposal.accepted(),proposal.reason()); media=proposal.state();
        }
        MediaModel.BridgeOutcome submit(MediaModel.Policy p, RoleAccess.State roles) {
            return MediaModel.submit(media,p,roles,info,"writer",new MediaModel.SubmitRequest("story",0,"Draft","Private draft body","Source",
                List.of(new RoleView.ReadingRef("writer","evidence",1,1)),10,20), List.of());
        }
        void submitted() { var o=submit(policy,access); check(o.accepted(),o.reason()); media=o.state();info=o.information(); }
        void editorRead() { info=read(info,access,"editor",media.stories().get(0).draftDocId(),21); }
        MediaModel.Outcome review(RoleAccess.State roles) { return MediaModel.review(media,policy,roles,info,"editor",new MediaModel.ReviewRequest("story",1,true,"",30,40)); }
        void approved() { submitted();editorRead();var o=review(access);check(o.accepted(),o.reason());media=o.state(); }
    }
    @Test
    @DisplayName("control full normal pipeline")
    void boundary01() {var f=new Fixture(); f.approved();var p=MediaModel.publish(f.media,f.policy,f.access,f.info,"editor",new MediaModel.PublishRequest("story",1,50,60));check(p.accepted(),p.reason());}

    @Test
    void successorCannotProbePredecessorStoryThroughSubmissionErrors() {
        var f = new Fixture();
        var access = RoleAccess.revoke(f.access, "aw", 5);
        access = RoleAccess.appoint(registry(), access, "successor", "other", "a",
                Role.JOURNALIST, 5, 100000, 5);
        for (String id : List.of("missing", "story")) {
            for (int revision : List.of(0, 7)) {
                var result = MediaModel.submit(f.media, f.policy, access, f.info, "other",
                        new MediaModel.SubmitRequest(id, revision, "T", "B", "",
                                List.of(), 10, 20), List.of());
                check(MediaModel.REASON_NOT_AUTHORIZED.equals(result.reason()),
                        "submission exposes predecessor story: " + result.reason());
                var correction = MediaModel.startCorrection(f.media, f.policy, access, "other",
                        new MediaModel.CorrectionRequest(id, Math.max(1, revision), 20));
                check(MediaModel.REASON_NOT_AUTHORIZED.equals(correction.reason()),
                        "correction exposes predecessor story: " + correction.reason());
            }
        }
    }

    @Test
    void sourceDescriptionRemainsUnverifiedThroughPublication() {
        var f = new Fixture();
        f.approved();
        var published = MediaModel.publish(f.media, f.policy, f.access, f.info, "editor",
                new MediaModel.PublishRequest("story", 1, 50, 60));
        check(published.accepted(), published.reason());
        var story = published.state().stories().get(0);
        for (String docId : List.of(story.draftDocId(), story.publicDocId())) {
            var doc = published.information().documents().stream()
                    .filter(item -> item.docId().equals(docId)).findFirst().orElseThrow();
            check(doc.versions().get(0).publicSourceDescription()
                    .equals("作者来源描述（自由文字，未经程序核验）：Source"),
                    "free source description must remain visibly unverified");
        }
    }

    @Test
    @DisplayName("unpublished topic hidden from other media")
    void boundary02() {var f=new Fixture();check(MediaModel.view(f.media,f.access,f.info,"other",4).isEmpty(),"foreign editor saw unpublished topic");}

    @Test
    @DisplayName("unpublished topic hidden from fund researcher")
    void boundary03() {var f=new Fixture();check(MediaModel.view(f.media,f.access,f.info,"researcher",4).isEmpty(),"foreign researcher saw unpublished topic");}

    @Test
    @DisplayName("organization cannot change on submit")
    void boundary04() {var f=new Fixture();var o=f.submit(policy("b"),f.access);check(!o.accepted(),"A story accepted with B policy");check(o.state().equals(f.media)&&o.information().equals(f.info),"rejection mutated state");}

    @Test
    @DisplayName("writer authority checked at completion")
    void boundary05() {var f=new Fixture();var o=f.submit(f.policy,roles(15,100000));check(!o.accepted(),"writer expired during work accepted");}

    @Test
    @DisplayName("editor review authority checked at completion")
    void boundary06() {var f=new Fixture();f.submitted();f.editorRead();var o=f.review(roles(100000,35));check(!o.accepted(),"reviewer expired during work accepted");}

    @Test
    @DisplayName("editor publish authority checked at completion")
    void boundary07() {var f=new Fixture();f.approved();var o=MediaModel.publish(f.media,f.policy,roles(100000,55),f.info,"editor",new MediaModel.PublishRequest("story",1,50,60));check(!o.accepted(),"publisher expired during work accepted");}

    @Test
    @DisplayName("forgotten draft cannot bypass recall in view")
    void boundary08() {var f=new Fixture();f.submitted();f.editorRead();check(CognitionRecall.recall(f.info,"editor",5000,f.policy.recallPolicy()).selected().isEmpty(),"control must be forgotten");var views=MediaModel.view(f.media,f.access,f.info,"editor",5000);check(views.stream().allMatch(v->v.draftBody()==null),"forgotten body returned");}

    @Test
    @DisplayName("departed author has no current unpublished workflow")
    void boundary09() {var f=new Fixture();check(MediaModel.view(f.media,roles(5,100000),f.info,"writer",6).isEmpty(),"former author got current workflow after appointment end");}

    @Test
    @DisplayName("template removal is rejected without exception")
    void boundary10() {var f=new Fixture();var p=new MediaModel.Policy("a",Map.of(MediaModel.TemplateKind.INVESTIGATIVE,2),10,10,10,1,1,f.policy.recallPolicy());var o=f.submit(p,f.access);check(!o.accepted(),"policy change accepted");check(o.state().equals(f.media),"rejection mutated state");}

    @Test
    @DisplayName("revocation during review prevents completion")
    void boundary11() {var f=new Fixture();f.submitted();f.editorRead();var o=f.review(RoleAccess.revoke(f.access,"ae",35));check(!o.accepted(),"revoked editor approved");}

    @Test
    @DisplayName("forgotten draft cannot be approved")
    void boundary12() {var f=new Fixture();f.submitted();f.editorRead();check(CognitionRecall.recall(f.info,"editor",5010,f.policy.recallPolicy()).selected().isEmpty(),"control must be forgotten");var o=MediaModel.review(f.media,f.policy,f.access,f.info,"editor",new MediaModel.ReviewRequest("story",1,true,"",5000,5010));check(!o.accepted(),"forgotten draft approved");}

    @Test
    @DisplayName("unselected draft cannot bypass recall budget")
    void boundary13() {var p=new MediaModel.Policy("a",Map.of(MediaModel.TemplateKind.WIRE,1),10,10,10,1,1,new CognitionRecall.Policy(100,1,1,100000));var f=new Fixture(p);f.submitted();f.editorRead();f.info=read(f.info,f.access,"editor","evidence",23);var recall=CognitionRecall.recall(f.info,"editor",25,p.recallPolicy());check(recall.selected().size()==1&&recall.selected().get(0).observation().docId().equals("evidence"),"control must select newer evidence only");check(MediaModel.view(f.media,f.access,f.info,"editor",25).stream().allMatch(v->v.draftBody()==null),"budget bypass returned draft body");}

    @Test
    @DisplayName("unauthorized workflow does not reveal existence")
    void boundary14() {var f=new Fixture();var existing=MediaModel.review(f.media,f.policy,f.access,f.info,"other",new MediaModel.ReviewRequest("story",1,true,"",30,40));var unknown=MediaModel.review(f.media,f.policy,f.access,f.info,"other",new MediaModel.ReviewRequest("guess",1,true,"",30,40));check(!existing.accepted()&&!unknown.accepted()&&existing.reason().equals(unknown.reason()),"different responses disclose private story existence: "+existing.reason()+"/"+unknown.reason());}

    @Test
    @DisplayName("view cannot expose future workflow")
    void boundary15() {var f=new Fixture();try {check(MediaModel.view(f.media,f.access,f.info,"editor",2).isEmpty(),"future topic returned before creation");}catch(IllegalArgumentException validRejection){}}

    @Test
    @DisplayName("another story cannot backdate current state")
    void boundary16() {var f=new Fixture();f.submitted();var o=MediaModel.propose(f.media,f.policy,f.access,"writer",new MediaModel.ProposeRequest("backdated",MediaModel.TemplateKind.WIRE,"Past insertion",5));check(!o.accepted(),"backdated story inserted after state at 20");}

    @Test
    @DisplayName("independent actors may finish overlapping work in time order")
    void boundary17() {var f=new Fixture();f.submitted();f.editorRead();var proposed=MediaModel.propose(f.media,f.policy,f.access,"writer",new MediaModel.ProposeRequest("second",MediaModel.TemplateKind.WIRE,"Parallel topic",23));check(proposed.accepted(),proposed.reason());var reviewed=MediaModel.review(proposed.state(),f.policy,f.access,f.info,"editor",new MediaModel.ReviewRequest("story",1,true,"",24,34));check(reviewed.accepted(),reviewed.reason());var submitted=MediaModel.submit(reviewed.state(),f.policy,f.access,f.info,"writer",new MediaModel.SubmitRequest("second",0,"Second draft","Second body","Public source",List.of(new RoleView.ReadingRef("writer","evidence",1,1)),25,35), List.of());check(submitted.accepted(),"unrelated editor completion must not invalidate earlier writer start: "+submitted.reason());}

    @Test
    @DisplayName("same story ID in different media has distinct document ID")
    void boundary18() { check(!draftId("a","story").equals(draftId("b","story")),"cross-media document collision"); }

    @Test
    @DisplayName("opaque organization and story IDs cannot collide at delimiter")
    void boundary19() { check(!draftId("a","b:story").equals(draftId("a:b","story")),"delimiter collision"); }

}
