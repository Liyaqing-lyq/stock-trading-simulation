package edu.cufe.auction.world.pharmaaudit;

import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedPharmaScenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 独审探针（不是测试）：打印组4每条拒绝路径的真实结果码与聚合评分实际数值，
 * 供把断言从「猜的 COMPLETE」改成「有证据的 INCOMPLETE/席位/分数」。
 */
public final class PharmaScoreAuditProbe {

    private static final String PHARMA = ComposedPharmaScenario.PHARMA;
    private static final List<String> DIRECTORS = List.of("frank", "grace", "heidi");

    private PharmaScoreAuditProbe() {
    }

    /**
     * @param args 日志路径
     * @throws Exception 打开或排程失败
     */
    public static void main(String[] args) throws Exception {
        Path journal = Path.of(args[0]);
        Files.deleteIfExists(journal);
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            PharmaAuditHarness.approveBudgetAndAppoint(world, "kate");
            world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
            var snapshot = world.periodicOf(PHARMA).orElseThrow().snapshots().get(0);
            var meeting = world.boardOfCompany(PHARMA).orElseThrow().board()
                    .meeting(snapshot.meetingId());
            List<String> attendees = List.of("frank", "grace", "heidi", "judy");
            for (int i = 0; i < attendees.size(); i++) {
                world.scheduleEngineeringAttend("pr-att-" + i, "pr-att-t-" + i,
                        snapshot.capturedAt() + 10L + i, PHARMA, attendees.get(i),
                        snapshot.meetingId(), false);
            }
            world.advanceTo(meeting.opensAt() + 1_000L);
            System.out.println("PREPARE=" + PharmaAuditHarness.tool(world, "kate", "d-prepare",
                    "periodic.prepare", Map.of("companyPartyId", PHARMA, "period", 1)));
            var draft = world.periodicOf(PHARMA).orElseThrow().drafts().get(0);
            System.out.println("DRAFT_DOC=" + draft.docId() + " SNAPSHOT_DOC="
                    + snapshot.materialDocId() + " VERSION=" + draft.version());
            long start = world.snapshot().timeMillis();
            for (int i = 0; i < DIRECTORS.size(); i++) {
                world.scheduleStartReading("d-read-" + i, "d-read-t-" + i, start + 100L + i,
                        DIRECTORS.get(i), draft.docId(), draft.version());
            }
            world.advanceTo(start + 2_000L);

            System.out.println("UNREAD_JUDY=" + score(world, "judy", "d-judy",
                    metrics("CASH_SOLVENCY", 80, "account:RND-PHARMA", snapshot.capturedAt())));
            System.out.println("BALLOTS_AFTER_UNREAD="
                    + world.periodicOf(PHARMA).orElseThrow().ballots().size());
            System.out.println("FOREIGN_ENG_A=" + score(world, "heidi", "d-heidi-enga",
                    metrics("CASH_SOLVENCY", 80, "account:ENG-A", snapshot.capturedAt())));
            System.out.println("FOREIGN_BANK=" + score(world, "heidi", "d-heidi-bank",
                    metrics("CASH_SOLVENCY", 80, "account:RSV-NORTH", snapshot.capturedAt())));
            System.out.println("FUTURE=" + score(world, "frank", "d-frank-future",
                    metrics("CASH_SOLVENCY", 80, "account:RND-PHARMA",
                            snapshot.capturedAt() + 1L)));
            System.out.println("BALLOTS_AFTER_ATTACKS="
                    + world.periodicOf(PHARMA).orElseThrow().ballots().size());
            System.out.println("SCORES_BEFORE_CLOSE="
                    + world.periodicOf(PHARMA).orElseThrow().scores().size());

            for (String director : DIRECTORS) {
                System.out.println("SCORE_" + director + "=" + score(world, director,
                        "d-score-" + director, metrics("CASH_SOLVENCY", 90,
                                "account:RND-PHARMA", snapshot.capturedAt())));
            }
            var periods = world.periodicOf(PHARMA).orElseThrow();
            System.out.println("BALLOTS=" + periods.ballots().size());
            for (PeriodicModel.Ballot ballot : periods.ballots()) {
                System.out.println("BALLOT " + ballot.personPartyId() + " seat=" + ballot.seatId()
                        + " status=" + ballot.reviewStatus() + " metrics=" + ballot.metrics());
            }
            for (String director : DIRECTORS) {
                System.out.println("APPROVE_" + director + "=" + PharmaAuditHarness.tool(world,
                        director, "d-approve-" + director, "periodic.approve",
                        Map.of("companyPartyId", PHARMA, "period", 1,
                                "draftId", draft.draftId(), "draftVersion", draft.version())));
            }
            world.advanceTo(meeting.closesAt() + 10L);
            var closed = world.periodicOf(PHARMA).orElseThrow();
            System.out.println("SCORES=" + closed.scores().size());
            for (PeriodicModel.Score score : closed.scores()) {
                System.out.println("SCORE_ROW seats=" + score.seatCount() + " status="
                        + score.status() + " reason=" + score.reason() + " sums=" + score.sums()
                        + " total=" + score.total());
            }
            System.out.println("LATE_APPROVE_JUDY=" + PharmaAuditHarness.tool(world, "judy",
                    "d-approve-late", "periodic.approve", Map.of("companyPartyId", PHARMA,
                            "period", 1, "draftId", draft.draftId(), "draftVersion",
                            draft.version())));
            System.out.println("APPROVALS_AFTER_LATE="
                    + world.periodicOf(PHARMA).orElseThrow().approvals().size());
            System.out.println("AUTHORIZE=" + PharmaAuditHarness.tool(world, "kate", "d-authorize",
                    "periodic.authorize", Map.of("companyPartyId", PHARMA, "period", 1,
                            "draftId", draft.draftId(), "draftVersion", draft.version(),
                            "publisherPersonId", "frank",
                            "expiresAt", world.snapshot().timeMillis() + 86_400_000L)));
            System.out.println("AUTHORIZATIONS="
                    + world.periodicOf(PHARMA).orElseThrow().authorizations().size());
            System.out.println("FOREIGN_PUBLISH_NORTH=" + PharmaAuditHarness.tool(world, "frank",
                    "d-publish-north", "periodic.publish", Map.of("companyPartyId", "north",
                            "period", 1, "draftId", draft.draftId(), "draftVersion",
                            draft.version())));
            System.out.println("NORTH_RELEASES="
                    + world.periodicOf("north").orElseThrow().releases().size());
            System.out.println("PHARMA_RELEASES="
                    + world.periodicOf(PHARMA).orElseThrow().releases().size());
            System.out.println("PERIODIC_PUBLIC_DOCS="
                    + world.snapshot().information().orElseThrow().information().documents()
                    .stream().map(doc -> doc.docId()).filter(id -> id.startsWith("periodic-public"))
                    .toList());
            System.out.println("REAL_PUBLISH=" + PharmaAuditHarness.tool(world, "frank",
                    "d-publish-own", "periodic.publish", Map.of("companyPartyId", PHARMA,
                            "period", 1, "draftId", draft.draftId(), "draftVersion",
                            draft.version())));
            System.out.println("PHARMA_RELEASES_AFTER="
                    + world.periodicOf(PHARMA).orElseThrow().releases().size());
            System.out.println("PERIODIC_PUBLIC_DOCS_AFTER="
                    + world.snapshot().information().orElseThrow().information().documents()
                    .stream().map(doc -> doc.docId()).filter(id -> id.startsWith("periodic-public"))
                    .toList());
        }
        System.out.println("PROBE_OK");
    }

    private static String score(WorldRuntime world, String person, String id,
            List<Map<String, Object>> metrics) throws Exception {
        return PharmaAuditHarness.tool(world, person, id, "periodic.score",
                Map.of("companyPartyId", PHARMA, "period", 1, "metrics", metrics));
    }

    private static List<Map<String, Object>> metrics(String dimension, int score,
            String sourceRef, long asOf) {
        return List.of(Map.of("dimension", dimension, "score", score,
                "sourceRef", sourceRef, "asOf", asOf));
    }
}
