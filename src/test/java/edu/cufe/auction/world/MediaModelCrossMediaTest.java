package edu.cufe.auction.world;

import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.RoleAccess.Appointment;
import edu.cufe.auction.world.RoleAccess.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 两家媒体的真实采编与阅读：来源谱系不因换发布者而增加独立组数。 */
class MediaModelCrossMediaTest {
    private static OpeningRegistry registry() {
        var parties = new ArrayList<Party>();
        for (String id : List.of("a", "b")) {
            parties.add(new Party(id, PartyKind.ORGANIZATION, id));
        }
        for (String id : List.of("wa", "ea", "wb", "eb", "source")) {
            parties.add(new Party(id, PartyKind.PERSON, id));
        }
        return new OpeningRegistry(parties, List.of(), List.of(), List.of());
    }

    private static RoleAccess.State roles() {
        var appointments = new ArrayList<Appointment>();
        for (String org : List.of("a", "b")) {
            appointments.add(new Appointment("w" + org, "w" + org, org,
                    Role.JOURNALIST, 0, 100000, null));
            appointments.add(new Appointment("e" + org, "e" + org, org,
                    Role.EDITOR, 0, 100000, null));
        }
        return new RoleAccess.State(List.of(), List.of(), List.of(), appointments);
    }

    private static MediaModel.Policy policy(String org) {
        return new MediaModel.Policy(org, Map.of(MediaModel.TemplateKind.WIRE, 1,
                MediaModel.TemplateKind.INVESTIGATIVE, 2), 1, 1, 1, 1, 1,
                new CognitionRecall.Policy(100000, 1, 100, 100000));
    }

    private static InformationModel.State read(InformationModel.State info, String who,
                                                String doc, long at) {
        var start = InformationModel.startRead(info, roles(), who, doc, 1, at);
        assertEquals(InformationModel.Status.STARTED, start.status());
        var reading = start.state().active().stream()
                .filter(item -> item.personPartyId().equals(who)).findFirst().orElseThrow();
        var end = InformationModel.completeRead(start.state(), roles(), who,
                reading.readId(), reading.dueAt());
        assertEquals(InformationModel.Status.COMPLETED, end.status());
        return end.state();
    }

    private record Published(MediaModel.State media, InformationModel.State information,
                             String doc) { }

    private static Published publish(MediaModel.State media, InformationModel.State info,
                                     String org, String story, RoleView.ReadingRef ref,
                                     long at, List<MediaModel.State> context) {
        var policy = policy(org);
        String writer = "w" + org;
        String editor = "e" + org;
        var proposed = MediaModel.propose(media, policy, roles(), writer,
                new MediaModel.ProposeRequest(story, MediaModel.TemplateKind.WIRE, "选题", at));
        assertTrue(proposed.accepted(), proposed.reason());
        var submitted = MediaModel.submit(proposed.state(), policy, roles(), info, writer,
                new MediaModel.SubmitRequest(story, 0, "报道", "转载公开资料", "所引公开资料",
                        List.of(ref), at + 1, at + 2), context);
        assertTrue(submitted.accepted(), submitted.reason());
        info = read(submitted.information(), editor,
                submitted.state().stories().get(0).draftDocId(), at + 3);
        var reviewed = MediaModel.review(submitted.state(), policy, roles(), info, editor,
                new MediaModel.ReviewRequest(story, 1, true, "", at + 5, at + 6));
        assertTrue(reviewed.accepted(), reviewed.reason());
        var result = MediaModel.publish(reviewed.state(), policy, roles(), info, editor,
                new MediaModel.PublishRequest(story, 1, at + 7, at + 8));
        assertTrue(result.accepted(), result.reason());
        return new Published(result.state(), result.information(),
                result.state().stories().get(0).publicDocId());
    }

    private static Published firstArticle() {
        var info = InformationModel.open(registry(), List.of("wa", "ea", "wb", "eb", "source"));
        info = InformationModel.publish(info, "original", "source",
                InformationModel.Audience.publicAudience(), new InformationModel.VersionDraft(
                        "原始资料", "同一条原始资料", "公开原文", 0, 1, List.of("original")));
        info = read(info, "wa", "original", 1);
        return publish(MediaModel.open(policy("a"), registry()), info, "a", "first",
                new RoleView.ReadingRef("wa", "original", 1, 1), 10, List.of());
    }

    @Test
    void twoMediaRelaysStillHaveOneOriginalSourceGroup() {
        var a = firstArticle();
        var info = read(a.information(), "wb", a.doc(), 20);
        var b = publish(MediaModel.open(policy("b"), registry()), info, "b", "relay",
                new RoleView.ReadingRef("wb", a.doc(), 1, 1), 30, List.of(a.media()));
        assertEquals(List.of(new MediaModel.Ref("original", 1)),
                b.media().lineage().get(0).roots());
        info = read(b.information(), "wa", b.doc(), 40);
        var proposed = MediaModel.propose(a.media(), policy("a"), roles(), "wa",
                new MediaModel.ProposeRequest("investigation", MediaModel.TemplateKind.INVESTIGATIVE,
                        "是否已获双源核实", 42));
        assertTrue(proposed.accepted(), proposed.reason());
        var result = MediaModel.submit(proposed.state(), policy("a"), roles(), info, "wa",
                new MediaModel.SubmitRequest("investigation", 0, "调查", "原文与转载仍同源", "两篇资料",
                        List.of(new RoleView.ReadingRef("wa", "original", 1, 1),
                                new RoleView.ReadingRef("wa", b.doc(), 1, 2)), 43, 44), List.of(b.media()));
        assertFalse(result.accepted());
        assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, result.reason());
        assertSame(proposed.state(), result.state());
        assertSame(info, result.information());
    }

    private static MediaModel.BridgeOutcome submitRelay(Published first,
                                                       List<MediaModel.State> context) {
        var info = read(first.information(), "wb", first.doc(), 20);
        var proposed = MediaModel.propose(MediaModel.open(policy("b"), registry()), policy("b"),
                roles(), "wb", new MediaModel.ProposeRequest("relay", MediaModel.TemplateKind.WIRE,
                        "转载", 30));
        return MediaModel.submit(proposed.state(), policy("b"), roles(), info, "wb",
                new MediaModel.SubmitRequest("relay", 0, "转载", "同一条资料", "引用首发",
                        List.of(new RoleView.ReadingRef("wb", first.doc(), 1, 1)), 31, 32), context);
    }

    @Test
    void relayedSourceAndDifferentOriginalPublisherRemainIndependent() {
        var a = firstArticle();
        var info = InformationModel.publish(a.information(), "second-original", "eb",
                InformationModel.Audience.publicAudience(), new InformationModel.VersionDraft(
                        "另一原始资料", "不同发布者的原始资料", "公开原文", 19, 1,
                        List.of("second-original")));
        info = read(info, "wb", a.doc(), 20);
        info = read(info, "wb", "second-original", 22);
        var proposed = MediaModel.propose(MediaModel.open(policy("b"), registry()), policy("b"),
                roles(), "wb", new MediaModel.ProposeRequest("investigation",
                        MediaModel.TemplateKind.INVESTIGATIVE, "双源调查", 30));
        var result = MediaModel.submit(proposed.state(), policy("b"), roles(), info, "wb",
                new MediaModel.SubmitRequest("investigation", 0, "调查", "两份独立来源", "公开证据",
                        List.of(new RoleView.ReadingRef("wb", a.doc(), 1, 1),
                                new RoleView.ReadingRef("wb", "second-original", 1, 2)), 31, 32),
                List.of(a.media()));
        assertTrue(result.accepted(), result.reason());
        assertEquals(2, result.state().stories().get(0).sourceGroups());
        assertEquals(List.of(new MediaModel.Ref("original", 1),
                new MediaModel.Ref("second-original", 1)), result.state().stories().get(0).roots());
    }

    @Test
    void missingMediaContextCannotInventAnOriginalPublisher() {
        var result = submitRelay(firstArticle(), List.of());
        assertFalse(result.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, result.reason());
    }

    @Test
    void duplicateMediaContextIsRejected() {
        var first = firstArticle();
        var result = submitRelay(first, List.of(first.media(), first.media()));
        assertFalse(result.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, result.reason());
    }
}
