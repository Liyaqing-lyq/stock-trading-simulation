package edu.cufe.auction.world;

import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.InformationModel.ReadObservation;
import edu.cufe.auction.world.InformationModel.Status;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.RoleAccess.Appointment;
import edu.cufe.auction.world.RoleAccess.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 独立验收用的真实夹具：文档、阅读、任职全部走生产 API。 */
final class MediaEvidenceFixtures {

    static final String MEDIA = "media-a";
    static final String COMPANY = "company";
    static final String REPORTER = "reporter";
    static final String REPORTER2 = "reporter2";
    static final String REPORTER3 = "reporter3";
    static final String EDITOR = "editor";
    static final String OUTSIDER = "outsider";
    static final String CHAIR = "chair";
    static final String CFO = "cfo";

    static final long WRITE = 1_000L;
    static final long REVIEW = 2_000L;
    static final long PUBLISH = 3_000L;
    static final long DRAFT_READ = 100L;
    static final long PUBLIC_READ = 200L;
    static final long END = 10_000_000L;

    private MediaEvidenceFixtures() {
    }

    static OpeningRegistry registry() {
        return new OpeningRegistry(List.of(
                new Party(MEDIA, PartyKind.ORGANIZATION, "实验快讯社"),
                new Party(COMPANY, PartyKind.ORGANIZATION, "实验公司"),
                new Party(REPORTER, PartyKind.PERSON, "记者甲"),
                new Party(REPORTER2, PartyKind.PERSON, "记者乙"),
                new Party(REPORTER3, PartyKind.PERSON, "记者丙"),
                new Party(EDITOR, PartyKind.PERSON, "编辑"),
                new Party(OUTSIDER, PartyKind.PERSON, "无关读者"),
                new Party(CHAIR, PartyKind.PERSON, "董事长"),
                new Party(CFO, PartyKind.PERSON, "财务负责人")),
                List.of(), List.of(), List.of());
    }

    static RoleAccess.State roles() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-R1", REPORTER, MEDIA, Role.JOURNALIST, 0L, END, null),
                new Appointment("AP-E", EDITOR, MEDIA, Role.EDITOR, 0L, END, null)));
    }

    /** 同机构同岗位不能并存：上一任记者卸任后继任者才接任。 */
    static RoleAccess.State handoverRoles() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-R1", REPORTER, MEDIA, Role.JOURNALIST, 0L, 12_000L, null),
                new Appointment("AP-R2", REPORTER2, MEDIA, Role.JOURNALIST, 12_000L, END, null),
                new Appointment("AP-E", EDITOR, MEDIA, Role.EDITOR, 0L, END, null)));
    }

    /** 记者甲卸任、记者乙接任：用于继任者继承场景。 */
    static RoleAccess.State successorRoles() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-R1", REPORTER, MEDIA, Role.JOURNALIST, 0L, 5_000L, null),
                new Appointment("AP-R2", REPORTER2, MEDIA, Role.JOURNALIST, 5_000L, END, null),
                new Appointment("AP-E", EDITOR, MEDIA, Role.EDITOR, 0L, END, null)));
    }

    /** 三任记者首尾相接，用于同一真实根被两位分享者先后共享的场景。 */
    static RoleAccess.State successionRoles() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-R2", REPORTER2, MEDIA, Role.JOURNALIST, 0L, 1_500L, null),
                new Appointment("AP-R3", REPORTER3, MEDIA, Role.JOURNALIST, 1_500L, 2_500L, null),
                new Appointment("AP-R1", REPORTER, MEDIA, Role.JOURNALIST, 2_500L, END, null),
                new Appointment("AP-E", EDITOR, MEDIA, Role.EDITOR, 0L, END, null)));
    }

    static RoleAccess.State rolesEndingAt(long end) {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-R1", REPORTER, MEDIA, Role.JOURNALIST, 0L, end, null),
                new Appointment("AP-E", EDITOR, MEDIA, Role.EDITOR, 0L, end, null)));
    }

    static CognitionRecall.Policy recall() {
        return new CognitionRecall.Policy(1_000_000L, 1, 100, 1_000_000L);
    }

    static MediaModel.Policy policy() {
        return new MediaModel.Policy(MEDIA,
                Map.of(MediaModel.TemplateKind.WIRE, 1, MediaModel.TemplateKind.INVESTIGATIVE, 2),
                WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ, recall());
    }

    static MediaModel.Policy forgetfulPolicy() {
        return new MediaModel.Policy(MEDIA,
                Map.of(MediaModel.TemplateKind.WIRE, 1, MediaModel.TemplateKind.INVESTIGATIVE, 2),
                WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ,
                new CognitionRecall.Policy(1L, 1_000, 100, 1_000_000L));
    }

    static InformationModel.State information() {
        return InformationModel.open(registry(),
                List.of(REPORTER, REPORTER2, REPORTER3, EDITOR, OUTSIDER, CHAIR, CFO));
    }

    static InformationModel.State primary(InformationModel.State info, String docId,
                                          String publisher, long publishedAt) {
        return InformationModel.publish(info, docId, publisher, Audience.publicAudience(),
                new InformationModel.VersionDraft("公告 " + docId, "正文 " + docId, "公司公告",
                        publishedAt, 100L, List.of(docId)));
    }

    /** 非公开资料：组织内部文件（记者可读，外界不可读）。 */
    static InformationModel.State internal(InformationModel.State info, String docId, String publisher,
                                           long publishedAt) {
        return InformationModel.publish(info, docId, publisher, Audience.organization(MEDIA),
                new InformationModel.VersionDraft("内部 " + docId, "私证正文 " + docId, "",
                        publishedAt, 100L, List.of(docId)));
    }

    static InformationModel.State direct(InformationModel.State info, String docId, String publisher,
                                         String recipient, long publishedAt) {
        return InformationModel.publish(info, docId, publisher, Audience.direct(recipient),
                new InformationModel.VersionDraft("私信 " + docId, "私信正文 " + docId, "",
                        publishedAt, 100L, List.of(docId)));
    }

    static InformationModel.State read(InformationModel.State info, RoleAccess.State access,
                                       String person, String docId, long version, long start) {
        InformationModel.Outcome started = InformationModel.startRead(info, access, person, docId,
                version, start);
        if (started.status() != Status.STARTED) {
            throw new IllegalStateException("阅读未开始：" + person + " " + docId + " v" + version
                    + " -> " + started.status());
        }
        InformationModel.Reading reading = null;
        for (InformationModel.Reading candidate : started.state().active()) {
            if (candidate.personPartyId().equals(person)) {
                reading = candidate;
            }
        }
        InformationModel.Outcome done = InformationModel.completeRead(started.state(), access,
                person, reading.readId(), reading.dueAt());
        if (done.status() != Status.COMPLETED) {
            throw new IllegalStateException("阅读未完成：" + person + " -> " + done.status());
        }
        return done.state();
    }

    static RoleView.ReadingRef ref(InformationModel.State info, String person, String docId,
                                   long version) {
        for (ReadObservation observation : info.observations()) {
            if (observation.personPartyId().equals(person) && observation.docId().equals(docId)
                    && observation.version() == version) {
                return new RoleView.ReadingRef(person, docId, version,
                        ordinal(info, person, observation.readId()));
            }
        }
        throw new IllegalStateException("无真实阅读经历：" + person + " " + docId + " v" + version);
    }

    static int ordinal(InformationModel.State info, String person, String readId) {
        List<String> ids = new ArrayList<>();
        for (InformationModel.Reading reading : info.active()) {
            if (reading.personPartyId().equals(person)) {
                ids.add(reading.readId());
            }
        }
        for (InformationModel.ClosedReading closed : info.closed()) {
            if (closed.personPartyId().equals(person)) {
                ids.add(closed.readId());
            }
        }
        ids.sort(Comparator.comparingLong(MediaEvidenceFixtures::sequence)
                .thenComparing(Comparator.naturalOrder()));
        return ids.indexOf(readId) + 1;
    }

    private static long sequence(String readId) {
        if (readId == null || !readId.startsWith("READ-")) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(readId.substring("READ-".length()));
        } catch (NumberFormatException notNumeric) {
            return Long.MAX_VALUE;
        }
    }

    static MediaModel.State proposed(MediaModel.Policy policy, String storyId,
                                     MediaModel.TemplateKind kind, long now) {
        return MediaModel.propose(MediaModel.open(policy, registry()), policy, roles(), REPORTER,
                new MediaModel.ProposeRequest(storyId, kind, "选题 " + storyId, now)).state();
    }

    static MediaModel.Story story(MediaModel.State state, String storyId) {
        for (MediaModel.Story candidate : state.stories()) {
            if (candidate.storyId().equals(storyId)) {
                return candidate;
            }
        }
        return null;
    }

    static InformationModel.Document document(InformationModel.State info, String docId) {
        for (InformationModel.Document document : info.documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }

    static int docCount(InformationModel.State info) {
        return info.documents().size();
    }
}
