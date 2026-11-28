package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 用正式开局的真实预约、出行和已读原件追溯采访话轮。 */
public class InterviewSourceBasisTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String A1 = "engineering-opportunity:A1", CHARTER = "company-governance:eng-a:1";
    @TempDir Path directory;

    static void read(WorldRuntime w, String person, String doc, String command) throws Exception {
        long due = w.snapshot().timeMillis() + 1000;
        w.openRoleSession(person).startReading(command, command, due, doc, 1);
        w.advanceTo(due);
        w.advanceTo(w.roleView(person).readings().get(0).dueAt());
        assertTrue(InformationModel.canCite(info(w),person,doc,1,w.snapshot().timeMillis()));
    }

    private static InformationModel.State info(WorldRuntime w) throws Exception {
        return w.snapshot().information().orElseThrow().information();
    }

    /** 普通双人现场预约，答者读过两份公开原件和首问，无预填的本人决定。 */
    public static String readyAnswer(WorldRuntime w) throws Exception {
        w.advanceTo(34_200_000);
        read(w,"investor",A1,"read-opportunity");
        read(w,"investor",CHARTER,"read-charter");
        long slot = w.snapshot().timeMillis() + 360_000;
        String request = OnsiteVisitTest.book(w,slot);
        OnsiteVisitTest.act(w,"investor",request,"HOST_CONSENT");
        OnsiteVisitTest.act(w,"rita",request,"DEPART");
        w.advanceTo(slot);
        assertEquals("ONSITE",OnsiteVisitTest.row(w).stage());
        boolean modern=w.autonomyRoleObservation("rita").at("/interview/activeTurn/sourceBasisEnabled").asBoolean();
        if (modern) {
            long due = slot + 1000;
            w.openRoleSession("rita").speakInterview("initial-question","initial-question",due,
                    new WorldRoleSession.DialogueSpeakDraft(request,"采访提问","哪些事实可查证？",1000));
            w.advanceTo(due);
        } else {
            var action=PersonalMarketTest.decide(w,"rita","legacy-question","interview.speak",args(request)
                    .put("title","采访提问").put("body","哪些事实可查证？"));
            assertNotNull(action);
            w.advanceTo(action.dueMillis());
        }
        String question = InterviewDialogue.turnDocId("SESSION-" + request,1);
        read(w,"investor",question,"read-question");
        assertEquals("interview.speak",w.autonomyRoleObservation("investor").at("/interview/activeTurn/nextTurnTool").asText());
        if (modern) assertTrue(w.snapshot().decisions().orElseThrow().state().decisions().isEmpty());
        return request;
    }

    static ObjectNode args(String request) {
        return JSON.createObjectNode().put("requestId",request).put("title","答复").put("body","这是本人这次提供的说明。").put("readDurationMillis",1000);
    }

    private static ObjectNode sources(String... docs) {
        var basis=JSON.createObjectNode().put("kind","READ_SOURCE");
        var array=basis.putArray("sources");
        for (String doc:docs) array.addObject().put("docId",doc).put("version",1);
        return basis;
    }

    static void answer(WorldRuntime w,String command,ObjectNode args,boolean expected,String reason) throws Exception {
        var result=PersonalMarketTest.decide(w,"investor",command,"interview.speak",args);
        assertNotNull(result);
        w.advanceTo(result.dueMillis());
        var receipt=w.interviewRun().coordinations().get(result.taskId());
        assertNotNull(receipt);
        assertEquals(expected,receipt.accepted(),receipt.toString());
        if (reason!=null) assertEquals(reason,receipt.reason());
    }

    private static JsonNode row(WorldRuntime w,String person,int index) throws Exception {
        return w.autonomyRoleObservation(person).at("/interview/sessions/0/turns/"+index);
    }

    @Test void declaredRootsAreActualReadVersionsAndASpeakerReleaseOnlyDeliversThatTurn() throws Exception {
        Path file=directory.resolve("sources.journal");String turn,delivery,request;
        try(var w=ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file)) {
            request=readyAnswer(w);
            var speech=w.autonomyRoleObservation("investor").required("tools");
            assertTrue(speech.toString().contains("sourceBasis"));
            assertEquals("UNVERIFIED",row(w,"investor",0).required("sourceKind").asText());
            var invalid=args(request);invalid.set("sourceBasis",sources("engineering-opportunity:A2"));
            answer(w,"unread",invalid,false,"INTERVIEW_SOURCE_UNAVAILABLE");
            var version=args(request);var wrong=sources(A1);
            ((ObjectNode)wrong.required("sources").required(0)).put("version",2);
            version.set("sourceBasis",wrong);answer(w,"wrong-version",version,false,"INTERVIEW_SOURCE_UNAVAILABLE");
            assertTrue(row(w,"rita",1).isMissingNode());
            var args=args(request);args.set("sourceBasis",sources(CHARTER,A1,A1));
            answer(w,"verified",args,true,null);
            turn=InterviewDialogue.turnDocId("SESSION-"+request,2);
            assertFalse(row(w,"rita",1).has("sourceKind"));
            assertEquals("READ_SOURCE",row(w,"investor",1).required("sourceKind").asText());
            read(w,"rita",turn,"read-answer");
            assertEquals("READ_SOURCE",row(w,"rita",1).required("sourceKind").asText());
            assertTrue(w.autonomyRoleObservation("sam").at("/interview/sessions").isEmpty());
            var info=info(w);var origins=InterviewSourceOrigins.wrap(MediaModel.SourceOrigins.NONE,w.interviewRun());
            assertEquals(List.of(new MediaModel.Ref(CHARTER,1),new MediaModel.Ref(A1,1)),origins.originsOf(info,turn,1).orElseThrow());
            assertEquals(List.of(),origins.originsOf(info,InterviewDialogue.turnDocId("SESSION-"+request,1),1).orElseThrow());
            var original=info.documents().stream().filter(d->d.docId().equals(turn)).findFirst().orElseThrow();
            assertEquals("发言者转述其已读材料",original.at(1).publicSourceDescription());
            assertNull(origins.sourceGroup(original,1));
            var end=w.autonomyRoleObservation("rita").at("/interview/sessions/0/scheduledEndMillis").longValue();
            w.advanceTo(end);
            MediaSubscriptionsTest.act(w,"nina","join",MediaSubscriptions.JOIN,"wire");
            MediaSubscriptionsTest.act(w,"investor","release",MediaSubscriptions.RELEASE,turn);
            delivery=MediaSubscriptionsTest.menu(w,"nina").required("deliveries").required(0).required("docId").asText();
            assertTrue(MediaSubscriptionsTest.menu(w,"sam").required("deliveries").isEmpty());
            var envelope=info(w).documents().stream().filter(d->d.docId().equals(delivery)).findFirst().orElseThrow();
            assertEquals(List.of(new MediaModel.Ref(turn,1)),origins.originsOf(info(w),delivery,1).orElseThrow());
            assertNull(origins.sourceGroup(envelope,1));
            assertTrue(envelope.at(1).publicSourceDescription().contains("转述其已读材料"));
            assertFalse(envelope.at(1).internalSourceIds().toString().contains(CHARTER));
            assertFalse(InformationModel.canCite(info(w),"nina",turn,1,w.snapshot().timeMillis()));
        }
        byte[] bytes=Files.readAllBytes(file);
        try(var restored=ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file)) {
            var origins=InterviewSourceOrigins.wrap(MediaModel.SourceOrigins.NONE,restored.interviewRun());
            assertEquals(List.of(new MediaModel.Ref(turn,1)),origins.originsOf(info(restored),delivery,1).orElseThrow());
            assertEquals(2,origins.originsOf(info(restored),turn,1).orElseThrow().size());
        }
        assertArrayEquals(bytes,Files.readAllBytes(file));
    }

    @Test void ordinaryModelAndLocalRoleKeepTheSameDeclaredOriginsThroughEachSubscriberEnvelope() throws Exception {
        Path parent=directory.resolve("ready.journal");String request;
        try(var w=ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(parent)) { request=readyAnswer(w); }
        List<List<MediaModel.Ref>> sets=List.of(List.of(),List.of(new MediaModel.Ref(A1,1)),
                List.of(new MediaModel.Ref(CHARTER,1),new MediaModel.Ref(A1,1)));
        for (var refs:sets) for (boolean model:List.of(false,true)) {
            var file=directory.resolve("envelope-"+refs.size()+"-"+model+".journal");
            Files.copy(parent,file);
            String turn=InterviewDialogue.turnDocId("SESSION-"+request,2), delivered;
            var basis=refs.isEmpty()?InterviewSourceBasis.UNVERIFIED:new InterviewSourceBasis(InterviewSourceBasis.Kind.READ_SOURCE,refs);
            try(var w=ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file)) {
                var args=args(request);
                if (model) {
                    args.set("sourceBasis",JSON.valueToTree(basis));
                    answer(w,"answer",args,true,null);
                } else {
                    long due=w.snapshot().timeMillis()+1000;
                    w.openRoleSession("investor").speakInterview("answer","answer",due,new WorldRoleSession.DialogueSpeakDraft(
                            request,args.required("title").asText(),args.required("body").asText(),1000),basis);
                    w.advanceTo(due);
                }
                assertEquals(basis.kind().name(),row(w,"investor",1).required("sourceKind").asText());
                long end=w.autonomyRoleObservation("investor").at("/interview/sessions/0/scheduledEndMillis").longValue();
                w.advanceTo(end);
                MediaSubscriptionsTest.act(w,"nina","join-sample",MediaSubscriptions.JOIN,"wire");
                MediaSubscriptionsTest.act(w,"investor","share-sample",MediaSubscriptions.RELEASE,turn);
                assertEquals(1,MediaSubscriptionsTest.menu(w,"nina").required("deliveries").size());
                delivered=MediaSubscriptionsTest.menu(w,"nina").required("deliveries").required(0).required("docId").asText();
                read(w,"nina",delivered,"read-my-copy");
                assertFalse(InformationModel.canCite(info(w),"nina",turn,1,w.snapshot().timeMillis()));
            }
            var before=Files.readAllBytes(file);
            try(var w=ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file)) {
                var information=info(w); var origin=InterviewSourceOrigins.wrap(MediaModel.SourceOrigins.NONE,w.interviewRun());
                assertEquals(List.of(new MediaModel.Ref(turn,1)),origin.originsOf(information,delivered,1).orElseThrow());
                assertEquals(refs,origin.originsOf(information,turn,1).orElseThrow());
                var envelope=information.documents().stream().filter(d->d.docId().equals(delivered)).findFirst().orElseThrow();
                assertNull(origin.sourceGroup(envelope,1));
                assertTrue(envelope.at(1).publicSourceDescription().contains(basis.description()));
                assertTrue(MediaSubscriptionsTest.menu(w,"sam").required("deliveries").isEmpty());
            }
            assertArrayEquals(before,Files.readAllBytes(file));
        }
    }

    @Test void directObservationAndUnverifiedAreDistinguishableAndHistoricalSpeechIsUnchanged() throws Exception {
        for (String kind:List.of("DIRECT_OBSERVATION","UNVERIFIED","legacy")) {
            Path file=directory.resolve(kind+".journal"); boolean legacy="legacy".equals(kind);
            try(var w=legacy?ComposedIndustryScenario.openExactDecimalTenCompanyStudy(file):ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file)) {
                String request=readyAnswer(w);var args=args(request);
                if ("DIRECT_OBSERVATION".equals(kind)) args.putObject("sourceBasis").put("kind",kind);
                answer(w,"speaker",args,true,null);
                String id=InterviewDialogue.turnDocId("SESSION-"+request,2);
                var doc=info(w).documents().stream().filter(d->d.docId().equals(id)).findFirst().orElseThrow();
                if (legacy) {
                    assertTrue(doc.at(1).internalSourceIds().isEmpty());assertEquals("",doc.at(1).publicSourceDescription());
                    assertFalse(row(w,"investor",1).has("sourceKind"));
                } else {
                    var origins=InterviewSourceOrigins.wrap(MediaModel.SourceOrigins.NONE,w.interviewRun());
                    assertEquals(List.of(),origins.originsOf(info(w),id,1).orElseThrow());
                    assertEquals("DIRECT_OBSERVATION".equals(kind)?"person:investor":null,origins.sourceGroup(doc,1));
                    assertEquals(kind,row(w,"investor",1).required("sourceKind").asText());
                }
            }
            var bytes=Files.readAllBytes(file);
            try(var restored=legacy?ComposedIndustryScenario.openExactDecimalTenCompanyStudy(file):ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file)) {
                assertEquals(2,restored.autonomyRoleObservation("rita").at("/interview/sessions/0/turns").size());
            }
            assertArrayEquals(bytes,Files.readAllBytes(file));
        }
    }
}
