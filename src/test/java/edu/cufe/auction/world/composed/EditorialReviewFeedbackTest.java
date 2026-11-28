package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 在同一真实稿件的提交、退回、改投与发布帧分别核对五种身份。 */
class EditorialReviewFeedbackTest {
    @TempDir Path directory;
    static final String NOTE = "请复核第一稿的应收口径。" + "后续原始凭据仍需核实。".repeat(36);

    static WorldRoleSession.MediaCitation read(WorldRuntime world, String person, String id, long version) throws Exception {
        long at = world.snapshot().timeMillis() + 1;
        world.openRoleSession(person).startReading("read-"+at, "read-"+at, at, id, version);
        world.advanceTo(at);
        var pending = world.roleView(person).readings().stream().filter(r -> id.equals(r.ref().docId())).findFirst().orElseThrow();
        world.advanceTo(pending.dueAt());
        var ref = world.roleView(person).observations().stream()
                .map(RoleView.Experience::ref).filter(r -> id.equals(r.docId()) && r.version()==version)
                .reduce((a,b) -> b).orElseThrow();
        return new WorldRoleSession.MediaCitation(id, version, ref.index());
    }

    @FunctionalInterface interface Submit {
        String schedule(WorldRoleSession session, String command, String task, long due) throws Exception;
    }
    static MediaRun.Report work(WorldRuntime world, String person, String kind, Submit submit) throws Exception {
        long due = world.snapshot().timeMillis() + MediaRun.durationOf(world.snapshot().media().orElseThrow().config(),kind,"wire");
        String id = "desk-" + due;
        submit.schedule(world.openRoleSession(person), id, id, due);
        world.advanceTo(due);
        var report = world.snapshot().media().orElseThrow().reports().values().stream()
                .filter(r -> r.timeMillis()==due && r.personPartyId().equals(person) && r.kind().equals(kind))
                .findFirst().orElseThrow();
        assertTrue(report.accepted(),report.reason());
        return report;
    }
    static String propose(WorldRuntime world, String localId, String topic) throws Exception {
        return work(world,"rita",MediaRun.KIND_PROPOSE,(s,c,t,d)->s.proposeStory(c,t,d,
                new WorldRoleSession.MediaProposeDraft(localId,"WIRE",topic))).storyId();
    }
    static void submit(WorldRuntime world, WorldRoleSession.MediaSubmitDraft draft) throws Exception {
        work(world,"rita",MediaRun.KIND_SUBMIT,(s,c,t,d)->s.submitStory(c,t,d,draft));
    }
    static MediaRun.Report review(WorldRuntime world, String story, int revision, boolean approve, String note) throws Exception {
        read(world,"ed",row(world,"ed",story).draftDocId(),revision);
        return work(world,"ed",MediaRun.KIND_REVIEW,(s,c,t,d)->s.reviewStory(c,t,d,
                new WorldRoleSession.MediaReviewDraft(story,revision,approve,note)));
    }
    static RoleTradingView.MediaStory row(WorldRuntime world, String person, String story) throws Exception {
        return world.openRoleSession(person).view().media().stream().filter(r->r.storyId().equals(story)).findFirst().orElseThrow();
    }
    static JsonNode modelRow(WorldRuntime world, String person, String story) throws Exception {
        for (var item : world.autonomyRoleObservation(person).path("media"))
            if (story.equals(item.path("storyId").asText())) return item;
        throw new AssertionError("稿件不在本人输入：" + person);
    }
    private boolean listed(WorldRuntime world, String person, String id) throws Exception {
        return world.roleView(person).catalog().stream().anyMatch(e->id.equals(e.docId()));
    }
    @Test void reviewersAndCurrentAuthorReadEachReturnedRevisionPrivately() throws Exception {
        Path journal = directory.resolve("new.journal");
        String story, id;
        try (var world = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(journal)) {
            long at=world.snapshot().timeMillis()+1;
            world.scheduleAppointment("appoint-bob","appoint-bob",at,"bob-news","bob","wire",RoleAccess.Role.RESEARCHER,at,Long.MAX_VALUE);
            world.advanceTo(at);
            var ref=read(world,"rita","engineering-opportunity:A1",1);
            story=propose(world,"first","工程报价");
            submit(world,new WorldRoleSession.MediaSubmitDraft(story,0,"第一稿","价格须按原始证据列示。","业主条款",List.of(ref)));
            var confirmation = review(world,story,1,false,NOTE);
            long confirmedPosition = world.snapshot().position();
            String command = "desk-" + confirmation.timeMillis();
            world.openRoleSession("ed").reviewStory(command, command, confirmation.timeMillis(),
                    new WorldRoleSession.MediaReviewDraft(story,1,false,NOTE));
            assertEquals(confirmedPosition,world.snapshot().position());
            assertEquals(1,row(world,"rita",story).reviewNotes().size());
            var first=row(world,"rita",story).reviewNotes().get(0);
            id=first.docId();
            assertEquals(1,first.revisionNo()); assertEquals(1,first.version()); assertFalse(first.read());
            for (String person : List.of("rita","ed")) {
                assertTrue(listed(world,person,id));
                assertEquals(id,modelRow(world,person,story).at("/reviewNotes/0/docId").asText());
                assertFalse(world.autonomyRoleObservation(person).toString().contains(NOTE));
            }
            assertTrue(listed(world,"bob",row(world,"rita",story).draftDocId()));
            assertThrows(IllegalArgumentException.class,()->world.schedulePublishDocumentVersion("alter","alter",
                    world.snapshot().timeMillis()+1,id,"改写","改写旧意见","改写",1,List.of()));
            for (String person : List.of("bob","nina","dave")) {
                assertFalse(listed(world,person,id));
                assertFalse(world.autonomyRoleObservation(person).toString().contains(id));
                long due=world.snapshot().timeMillis()+1;
                world.openRoleSession(person).startReading("guess-"+person,"guess-"+person,due,id,1);
                world.advanceTo(due);
                assertTrue(world.roleView(person).readings().isEmpty());
            }
            long started=world.snapshot().timeMillis()+1;
            world.openRoleSession("rita").startReading("read-note","read-note",started,id,1);
            world.advanceTo(started);
            long due=world.roleView("rita").readings().get(0).dueAt();
            assertTrue(due-started>60_000);
            world.advanceTo(due-1);
            assertFalse(row(world,"rita",story).reviewNotes().get(0).read());
            assertFalse(world.autonomyRoleObservation("rita").toString().contains(NOTE));
            world.advanceTo(due);
            assertTrue(row(world,"rita",story).reviewNotes().get(0).read());
            assertTrue(world.autonomyRoleObservation("rita").toString().contains("请复核第一稿的应收口径"));
            assertFalse(row(world,"ed",story).reviewNotes().get(0).read());
            submit(world,new WorldRoleSession.MediaSubmitDraft(story,1,"第二稿","条件逐项列明。","原始条款",List.of(ref)));
            assertEquals(1,row(world,"ed",story).reviewNotes().size());
            review(world,story,2,false,"另附第二稿核对意见。");
            var notes = row(world,"ed",story).reviewNotes();
            assertEquals(List.of(1,2),notes.stream().map(RoleTradingView.ReviewNote::revisionNo).toList());
            assertNotEquals(id,notes.get(1).docId());
            submit(world,new WorldRoleSession.MediaSubmitDraft(story,2,"第三稿","最终稿按条款刊载。","原始条款",List.of(ref)));
            review(world,story,3,true,"批准时的内部说明。");
            work(world,"ed",MediaRun.KIND_PUBLISH,(s,c,t,d)->s.publishStory(c,t,d,new WorldRoleSession.MediaStoryDraft(story,3)));
            String pub=row(world,"ed",story).publicDocId();
            read(world,"dave",pub,1);
            String reader=world.autonomyRoleObservation("dave").toString();
            assertFalse(reader.contains(id)); assertFalse(reader.contains("另附第二稿")); assertFalse(reader.contains("批准时的内部说明"));
            String appointment=world.roleView("rita").appointments().stream().filter(a->"wire".equals(a.organizationPartyId())).findFirst().orElseThrow().appointmentId();
            long open=world.snapshot().timeMillis()+1;
            world.openRoleSession("rita").startReading("open-before-leaving","stop-on-removal",open,notes.get(1).docId(),1);
            world.advanceTo(open);
            long finish=world.roleView("rita").readings().get(0).dueAt();
            long end=open+1;
            world.scheduleRevokeAppointment("remove-author","remove-author",end,appointment);
            world.advanceTo(end);
            assertFalse(listed(world,"rita",id)); assertTrue(listed(world,"ed",id));
            world.advanceTo(finish);
            assertTrue(world.roleView("rita").observations().stream().noneMatch(e->e.ref().docId().equals(notes.get(1).docId())));
            assertTrue(world.roleView("rita").observations().stream().anyMatch(e->e.ref().docId().equals(id)));
            assertFalse(listed(world,"bob",notes.get(1).docId()));
        }
        byte[] bytes=Files.readAllBytes(journal);
        try (var world=ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(journal)) {
            assertFalse(listed(world,"rita",id));
            assertEquals(2,row(world,"ed",story).reviewNotes().size());
            assertFalse(world.autonomyRoleObservation("dave").toString().contains(id));
        }
        assertArrayEquals(bytes,Files.readAllBytes(journal));
    }

    @Test void oldReturnPersistsTextOnlyInTheEditorialRecord() throws Exception {
        Path journal=directory.resolve("old.journal");
        String story;
        JsonNode input;
        try (var world=ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(journal)) {
            var ref=read(world,"rita","engineering-opportunity:A1",1);
            story=propose(world,"old","旧版工程报价");
            submit(world,new WorldRoleSession.MediaSubmitDraft(story,0,"报价","按条款核对。","原始条款",List.of(ref)));
            review(world,story,1,false,NOTE);
            assertTrue(world.snapshot().media().orElseThrow().stateOf("wire").revisions().stream().anyMatch(r->NOTE.equals(r.note())));
            input=world.autonomyRoleObservation("rita");
            for(String person:List.of("rita","ed")) {
                assertNull(row(world,person,story).reviewNotes()); assertFalse(modelRow(world,person,story).has("reviewNotes"));
                assertFalse(world.autonomyRoleObservation(person).toString().contains(NOTE));
            }
        }
        byte[] bytes=Files.readAllBytes(journal);
        try(var world=ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(journal)) {
            assertEquals(input,world.autonomyRoleObservation("rita"));
        }
        assertArrayEquals(bytes,Files.readAllBytes(journal));
    }
}
