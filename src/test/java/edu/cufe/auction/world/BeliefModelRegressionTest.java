package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Root根据合同独立编写：重试、来源语义去重及习惯版本边界。 */
class BeliefModelRegressionTest {
    @TempDir Path directory;
    static final BeliefModel.Policy POLICY = new BeliefModel.Policy(200,8,10000,1000,200,2,100);
    static final CognitionRecall.Policy RECALL = new CognitionRecall.Policy(100000,1,100,100000);
    static final String PERSON="bob", PUBLISHER="alice", COMPANY="cufe";
    static final BeliefModel.Comparator GE=BeliefModel.Comparator.AT_LEAST;

    @Test void replayAtCapacityReturnsOriginalState() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("capacity"))) {
            f.document("E",PUBLISHER,1,"public-evidence"); f.read("E",2);
            var policy=new BeliefModel.Policy(1,1,10000,20,200,2,100);
            var request=f.request("B","E",100,null);
            var first=BeliefModel.form(f.state,f.info,f.claims(),policy,RECALL,5,request);
            assertTrue(first.accepted());
            var retry=BeliefModel.form(first.state(),f.info,f.claims(),policy,RECALL,5,request);
            assertEquals(first.state(),retry.state());
            assertNotEquals("LIMIT",retry.reason(),"幂等重试不得被自己占满的容量挡住");
        }
    }

    @Test void anotherPersonReadingTheSameVersionDoesNotReplaceMyEvidence() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("reader-isolation"))) {
            f.document("E",PUBLISHER,1,"public-evidence");f.read("E",2);
            var start=InformationModel.startRead(f.info,f.access,"carol","E",1,4);
            assertEquals(InformationModel.Status.STARTED,start.status());
            var active=start.state().active().stream().filter(r->r.personPartyId().equals("carol")).findFirst().orElseThrow();
            var completed=InformationModel.completeRead(start.state(),f.access,"carol",active.readId(),5);
            assertEquals(InformationModel.Status.COMPLETED,completed.status());f.info=completed.state();
            assertTrue(f.form("B","E",100,null,6).accepted(),"他人后读相同版本不得覆盖本人的首次引用");
        }
    }

    @Test void changingDocumentAndClaimIdCannotRepeatSameAdoption() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("semantic-duplicate"))) {
            f.source("S1","claim-1",100,"alice"); f.read("S1",2);
            f.source("S2","claim-2",100,"alice"); f.read("S2",4);
            var first=f.form("B1","S1",100,"claim-1",7);
            assertTrue(first.accepted());f.state=first.state();
            var repeated=f.form("B2","S2",100,"claim-2",8);
            assertFalse(repeated.accepted(),"相同作者/目标/比较/阈值的重复发布不能刷来源学习");
            assertEquals(f.state,repeated.state());
        }
    }

    @Test void adoptedPredictionMustUseItsDeclaredVerificationSource() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("verification-source"))) {
            f.source("S","claim",100,"carol"); f.read("S",2);
            var mismatch=f.form("B","S",100,"claim",5);
            assertFalse(mismatch.accepted(),"不能将来源的预测换成另一核验人再给原作者评分");
            assertTrue(mismatch.state().beliefs().isEmpty());
        }
    }

    @Test void viewingLaterDoesNotCreateUnrecordedHabitDecay() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("habit-query"))) {
            f.document("E",PUBLISHER,1,"public-evidence");f.read("E",2);
            assertTrue(f.formAndKeep("B1","E",100,null,5));
            assertTrue(f.formAndKeep("B2","E",110,null,6));
            f.fact("F1","fact-1",100,0,null);f.read("F1",101);f.evaluate("F1",102);
            f.fact("F2","fact-2",110,0,null);f.read("F2",111);f.evaluate("F2",112);
            int before=f.state.habitRevisions().size();
            assertEquals(1,BeliefModel.habitLevel(f.state,PERSON,BeliefModel.HabitKind.VERIFY_BEFORE_ACT,POLICY,112));
            var later=BeliefModel.project(f.state,POLICY,PERSON,1000,10);
            assertEquals(1,later.habits().stream().filter(h->h.kind()==BeliefModel.HabitKind.VERIFY_BEFORE_ACT).findFirst().orElseThrow().level());
            assertEquals(before,f.state.habitRevisions().size());
            assertTrue(f.formAndKeep("B3","E",1200,null,1000));
            f.fact("F3","fact-3",1200,1,null);f.read("F3",1201);f.evaluate("F3",1202);
            assertEquals(0,BeliefModel.habitLevel(f.state,PERSON,BeliefModel.HabitKind.VERIFY_BEFORE_ACT,POLICY,1202));
            assertTrue(f.state.habitRevisions().size()>before,"实际新核验才追加支持到期的版本");
        }
    }

    @Test void futureStateCannotBeQueriedAsAnEarlierCut() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("future-cut"))) {
            f.document("E",PUBLISHER,1,"public-evidence");f.read("E",2);
            assertTrue(f.formAndKeep("B","E",100,null,5));
            assertThrows(IllegalArgumentException.class,()->BeliefModel.project(f.state,POLICY,PERSON,4,10));
        }
    }

    @Test void nextVersionOfFormationEvidenceCannotValidateItself() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("self-document"))) {
            f.document("E",PUBLISHER,1,"public-evidence");f.read("E",2);
            assertTrue(f.formAndKeep("B","E",100,null,5));
            f.info=InformationModel.publishVersion(f.info,"E",new InformationModel.VersionDraft("更晚版本","自证反例","company-announcement",100,1,List.of()));
            f.facts.add(new BeliefModel.FactClaim("self-fact","E",2,COMPANY,"ORDERS_CONFIRMED",100,1,PUBLISHER,1,null));
            var start=InformationModel.startRead(f.info,f.access,PERSON,"E",2,101);
            assertEquals(InformationModel.Status.STARTED,start.status());
            var active=start.state().active().stream().filter(r->r.personPartyId().equals(PERSON)).findFirst().orElseThrow();
            f.info=InformationModel.completeRead(start.state(),f.access,PERSON,active.readId(),102).state();
            var result=BeliefModel.evaluate(f.state,f.info,f.claims(),POLICY,PERSON,"E",2,1000,102);
            assertEquals(f.state,result.state(),"同一证据文档换版本仍不能自证");
        }
    }

    @Test void laterLearnedReliabilityCannotBeUsedToFormAnEarlierBelief() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("backdated-form"))) {
            f.source("S1","claim-1",100,"alice");f.read("S1",2);
            f.source("S2","claim-2",110,"alice");f.read("S2",4);
            assertTrue(f.formAndKeep("B1","S1",100,"claim-1",7));
            f.fact("F1","fact-1",100,1,null);f.read("F1",101);f.evaluate("F1",102);
            assertThrows(IllegalArgumentException.class,()->f.form("B2","S2",110,"claim-2",8),
                    "新建过去判断不能使用102毫秒才学到的来源分数");
        }
    }

    @Test void futureFrozenPositionCannotEnterCurrentEvaluation() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("future-position"))) {
            f.document("E",PUBLISHER,1,"public-evidence");f.read("E",2);
            assertTrue(f.formAndKeep("B","E",100,null,5));
            f.fact("F","future-fact",100,1,null);f.read("F",101);
            var out=BeliefModel.evaluate(f.state,f.info,f.claims(),POLICY,PERSON,"F",1,0,102);
            assertEquals(f.state,out.state());
            var available=BeliefModel.evaluate(f.state,f.info,f.claims(),POLICY,PERSON,"F",1,1,102);
            assertEquals(1,available.appended(),"同一事实在已确认位置内才可使用");
        }
    }

    @Test void anUnprocessedOldReadCannotAppendAnEvaluationBehindCommittedLearning() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("backdated-evaluation"))) {
            f.document("E",PUBLISHER,1,"public-evidence");f.read("E",2);
            assertTrue(f.formAndKeep("B1","E",100,null,5));
            assertTrue(f.formAndKeep("B2","E",110,null,6));
            f.fact("F1","fact-1",100,0,null);f.read("F1",101);
            f.fact("F2","fact-2",110,0,null);f.read("F2",111);f.evaluate("F2",112);
            assertThrows(IllegalArgumentException.class,()->f.evaluate("F1",102));
        }
    }

    @Test void adoptingOwnForecastCannotCreateASelfTrustLoop() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("self-adoption"))) {
            f.document("S",PERSON,1,"own-public-source");
            f.sources.add(new BeliefModel.SourceClaim("self-claim","S",1,PERSON,COMPANY,
                    "ORDERS_CONFIRMED",GE,1,100,PUBLISHER));
            f.read("S",2);
            assertFalse(f.form("B","S",100,"self-claim",5).accepted());
        }
    }

    @Test void evidencePermutationIsTheSameFormationCommand() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("evidence-order"))) {
            f.document("E1",PUBLISHER,1,"source-1");f.read("E1",2);
            f.document("E2",PUBLISHER,1,"source-2");f.read("E2",4);
            var request=new BeliefModel.FormRequest("B",PERSON,COMPANY,GE,1,100,PUBLISHER,
                    List.of(f.ref("E2"),f.ref("E1")),null,"原始主观判断");
            var first=BeliefModel.form(f.state,f.info,f.claims(),POLICY,RECALL,7,request);
            assertTrue(first.accepted());
            var retry=new BeliefModel.FormRequest("B",PERSON,COMPANY,GE,1,100,PUBLISHER,
                    List.of(f.ref("E1"),f.ref("E2")),null,"原始主观判断");
            var again=BeliefModel.form(first.state(),f.info,f.claims(),POLICY,RECALL,7,retry);
            assertEquals(first.state(),again.state());
            assertNotEquals("ID_CONFLICT",again.reason());
            assertEquals("E1",first.state().beliefs().get(0).evidence().get(0).docId());
        }
    }

    @Test void projectedCollectionsCannotBeModifiedByTheirConsumer() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("immutable-projection"))) {
            f.document("E",PUBLISHER,1,"public-evidence");f.read("E",2);
            assertTrue(f.formAndKeep("B","E",100,null,5));
            var projected=BeliefModel.project(f.state,POLICY,PERSON,5,10);
            assertThrows(UnsupportedOperationException.class,()->projected.beliefs().clear());
            assertThrows(UnsupportedOperationException.class,()->projected.sources().clear());
            assertThrows(UnsupportedOperationException.class,()->projected.habits().clear());
            assertEquals(1,f.state.beliefs().size());
        }
    }

    @Test void publicProjectionRecordsCopyCallerOwnedLists() {
        var refs=new ArrayList<>(List.of(new RoleView.ReadingRef(PERSON,"E",1,1)));
        var view=new BeliefModel.BeliefView("B",COMPANY,"ORDERS_CONFIRMED",GE,1,5,100,
                true,"OPEN","MID","主观判断",refs,-1);
        refs.clear();
        assertEquals(1,view.evidence().size());
        var beliefs=new ArrayList<>(List.of(view));
        var sources=new ArrayList<>(List.of(new BeliefModel.SourceBand(PUBLISHER,"MID")));
        var habits=new ArrayList<>(List.of(new BeliefModel.HabitLevel(BeliefModel.HabitKind.VERIFY_BEFORE_ACT,0,-1)));
        var projection=new BeliefModel.Projection(beliefs,sources,habits,false);
        beliefs.clear();sources.clear();habits.clear();
        assertEquals(1,projection.beliefs().size());
        assertEquals(1,projection.sources().size());
        assertEquals(1,projection.habits().size());
    }

    @Test void correctionRecomputesSaturatedReliabilityFromEffectiveHistory() throws Exception {
        try(Fixture f=new Fixture(directory.resolve("saturated-correction"))) {
            f.source("S1","claim-1",100,"alice");f.read("S1",2);
            f.source("S2","claim-2",110,"alice");f.read("S2",4);
            assertTrue(f.formAndKeep("B1","S1",100,"claim-1",7));
            assertTrue(f.formAndKeep("B2","S2",110,"claim-2",8));
            f.fact("F1","fact-1",100,1,null);f.read("F1",101);f.evaluate("F1",102);
            f.fact("F2","fact-2",110,1,null);f.read("F2",111);f.evaluate("F2",112);
            assertEquals(1000,BeliefModel.competencePermille(f.state,PERSON,PUBLISHER,POLICY));
            f.fact("C1","correct-1",100,0,"fact-1");f.read("C1",121);f.evaluate("C1",122);
            assertEquals(500,BeliefModel.competencePermille(f.state,PERSON,PUBLISHER,POLICY));
            assertEquals(3,f.state.evaluations().size(),"旧评估保留，更正只追加");
            var before=f.state;f.read("C1",123);f.evaluate("C1",124);
            assertEquals(before,f.state,"重读相同更正不重复学习");
        }
    }

    static final class Fixture implements AutoCloseable {
        final WorldRuntime runtime;final RoleAccess.State access;
        InformationModel.State info;
        BeliefModel.State state=new BeliefModel.State(List.of(),List.of(),List.of(),1);
        final List<BeliefModel.SourceClaim> sources=new ArrayList<>();
        final List<BeliefModel.FactClaim> facts=new ArrayList<>();
        Fixture(Path dir) throws Exception {
            java.nio.file.Files.createDirectories(dir);
            runtime=WorldWebScenario.openCognition(dir.resolve("world.journal")).runtime();
            access=runtime.snapshot().roles().orElseThrow().access();
            info=runtime.snapshot().information().orElseThrow().information();
        }
        void document(String doc,String publisher,long at,String group) {
            info=InformationModel.publish(info,doc,publisher,InformationModel.Audience.publicAudience(),
                    new InformationModel.VersionDraft(doc,"纯核核验夹具原文",group,at,1,List.of("HIDDEN")));
        }
        void source(String doc,String claim,long target,String verifier) throws Exception {
            document(doc,PUBLISHER,1,"shared-public-source");
            sources.add(new BeliefModel.SourceClaim(claim,doc,1,PUBLISHER,COMPANY,
                    "ORDERS_CONFIRMED",GE,1,target,verifier));
        }
        void fact(String doc,String id,long asOf,long value,String previous) {
            long publishAt=Math.max(asOf,info.closed().stream().mapToLong(InformationModel.ClosedReading::closedAt).max().orElse(0)+1);
            document(doc,PUBLISHER,publishAt,"company-announcement");
            facts.add(new BeliefModel.FactClaim(id,doc,1,COMPANY,"ORDERS_CONFIRMED",asOf,value,PUBLISHER,1,previous));
        }
        void read(String doc,long at) {
            var started=InformationModel.startRead(info,access,PERSON,doc,1,at);
            assertEquals(InformationModel.Status.STARTED,started.status());info=started.state();
            var active=info.active().stream().filter(r->r.personPartyId().equals(PERSON)).findFirst().orElseThrow();
            var completed=InformationModel.completeRead(info,access,PERSON,active.readId(),at+1);
            assertEquals(InformationModel.Status.COMPLETED,completed.status());info=completed.state();
        }
        RoleView.ReadingRef ref(String doc) {
            var observation=info.observations().stream().filter(o->o.personPartyId().equals(PERSON)&&o.docId().equals(doc)).findFirst().orElseThrow();
            var ids=info.closed().stream().filter(r->r.personPartyId().equals(PERSON)).map(InformationModel.ClosedReading::readId).sorted(java.util.Comparator.comparingLong(s->Long.parseLong(s.substring(5)))).toList();
            return new RoleView.ReadingRef(PERSON,doc,1,ids.indexOf(observation.readId())+1);
        }
        BeliefModel.Claims claims(){return new BeliefModel.Claims(sources,facts);}
        BeliefModel.FormRequest request(String id,String doc,long target,String adopted) {
            return new BeliefModel.FormRequest(id,PERSON,COMPANY,GE,1,target,PUBLISHER,List.of(ref(doc)),adopted,"这是本人主观判断");
        }
        BeliefModel.Outcome form(String id,String doc,long target,String adopted,long now) {
            return BeliefModel.form(state,info,claims(),POLICY,RECALL,now,request(id,doc,target,adopted));
        }
        boolean formAndKeep(String id,String doc,long target,String adopted,long now) {
            var outcome=form(id,doc,target,adopted,now);state=outcome.state();return outcome.accepted();
        }
        void evaluate(String doc,long now){state=BeliefModel.evaluate(state,info,claims(),POLICY,PERSON,doc,1,1000,now).state();}
        public void close() throws Exception {runtime.close();}
    }
}
