package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * schema18 周期写入与披露的世界侧纯函数集合：期末采样、窗口关闭、披露截止与五个人物动作。
 *
 * <p>所有方法只读入参帧（工程子集、治理董事会、任职、资料、账户）并返回候选结果，不写日志、
 * 不持第二份账、不产生并发；正常执行与逐帧重演调用同一实现。</p>
 *
 * <p><b>公司域</b>：章程公司（制造）复用原 GovernanceRun 的唯一董事会与报表；工程公司使用各自
 * 冻结董事会与经营状态。两类公司共用同一份 {@link PeriodicModel.State}，但会议、材料、证据、
 * 草稿与发布都按公司隔离。</p>
 *
 * <p><b>截止语义</b>：采样任务落在期末整点的 OBSERVATION 阶段，晚于同刻 RULES（制造关期）与
 * COMPLETION（业务结算），因此摘要只含截止时刻已经结算的事实；紧邻 +1ms 的变化一定归入下一期。
 * 不重试、不倒填。</p>
 */
final class PeriodicWorldEngine {

    /** 材料提前冻结时长：采样后 1 模拟日开会。 */
    static final long MATERIAL_LEAD_MILLIS = 86_400_000L;
    /** 评分窗口：开会后 10 模拟日。 */
    static final long REVIEW_WINDOW_MILLIS = 10L * 86_400_000L;
    /** 披露窗口：期末后 15 模拟日，晚于评分窗口。 */
    static final long DISCLOSURE_WINDOW_MILLIS = 15L * 86_400_000L;
    /** 材料固定阅读耗时：1 模拟小时。 */
    static final long MATERIAL_READ_MILLIS = 3_600_000L;
    /** 冻结政策标识。 */
    static final String POLICY_ID = "periodic-governance-v1";
    /** 长期投入机制缺失的真实标注，不虚构来源。 */
    static final String LONG_TERM_PLAN_REF = "none:long-term-investment-mechanism-not-implemented";

    /** 制造关期尚未完成；schema18 下不应出现，出现即拒绝而不是倒填。 */
    static final String REASON_MANUFACTURING_NOT_CLOSED = "MANUFACTURING_NOT_CLOSED";
    /** 快照位置缺帧或报表正文无法重算。 */
    static final String REASON_STATEMENT_UNAVAILABLE = "STATEMENT_UNAVAILABLE";
    /** 公开正文与被批准私稿不一致。 */
    static final String REASON_BODY_MISMATCH = "BODY_MISMATCH";
    /** 材料稿件已存在且内容不同。 */
    static final String REASON_MATERIAL_EXISTS = "MATERIAL_EXISTS";
    /** 会议不在可冻结材料的状态。 */
    static final String REASON_MEETING_STATE = "MEETING_STATE";
    /** 证据类型与维度不符，或不属于该期冻结引用。 */
    static final String REASON_EVIDENCE_NOT_ELIGIBLE = "EVIDENCE_NOT_ELIGIBLE";
    /** 该维度在本期没有合格机制/事实，必须保留缺项。 */
    static final String REASON_DIMENSION_NOT_AVAILABLE = "DIMENSION_NOT_AVAILABLE";

    private PeriodicWorldEngine() {
    }

    /** 一次候选结果：工程、治理、资料与本批系统子任务。 */
    record Applied(EngineeringRun run, BoardRun governance, InformationRun information,
                   boolean accepted, String reason, List<PendingTask> children) {

        static Applied rejected(EngineeringRun run, BoardRun governance, InformationRun info,
                                String reason) {
            return new Applied(run, governance, info, false, reason, List.of());
        }

        static Applied accepted(EngineeringRun run, BoardRun governance, InformationRun info,
                                List<PendingTask> children) {
            return new Applied(run, governance, info, true, PeriodicModel.REASON_OK, children);
        }
    }

    /** 冻结的周期政策（单一来源；开局写入状态后不再变）。 */
    static PeriodicModel.Policy policy() {
        return PeriodicModel.Policy.of(POLICY_ID, MATERIAL_LEAD_MILLIS, REVIEW_WINDOW_MILLIS,
                DISCLOSURE_WINDOW_MILLIS, LONG_TERM_PLAN_REF);
    }

    /** 某引用可作为哪些维度的证据；空表表示只是材料来源、不可用于评分。 */
    static List<String> dimensionsFor(String ref) {
        if (ref == null) {
            return List.of();
        }
        if (ref.startsWith("contract:")) {
            return List.of("OPERATING_COMMITMENT", "RISK");
        }
        if (ref.startsWith("work:")) {
            return List.of("OPERATING_COMMITMENT");
        }
        if (ref.startsWith("invoice:")) {
            return List.of("OPERATING_COMMITMENT", "RISK");
        }
        if (ref.startsWith("account:")) {
            return List.of("CASH_SOLVENCY");
        }
        if (ref.startsWith("disclosure:")) {
            return List.of("DISCLOSURE");
        }
        if (ref.startsWith("longterm:")) {
            return List.of("LONG_TERM_INVESTMENT");
        }
        return List.of();
    }

    /** 某公司的真实董事会：章程公司用治理董事会，工程公司用其冻结董事会。 */
    static BoardRun boardOf(EngineeringRun run, BoardRun governance, String company) {
        if (governance != null && governance.board().charter().companyPartyId().equals(company)) {
            return governance;
        }
        return run == null ? null : run.boardOf(company);
    }

    /** 是否章程公司（制造）。 */
    static boolean isCharterCompany(BoardRun governance, String company) {
        return governance != null
                && governance.board().charter().companyPartyId().equals(company);
    }

    /** 替换某公司董事会：章程公司写治理子集，工程公司写工程子集。 */
    private static Applied withBoard(Applied base, EngineeringRun run, BoardRun governance,
            String company, BoardRun next) {
        if (isCharterCompany(governance, company)) {
            return new Applied(run, next, base.information(), base.accepted(), base.reason(),
                    base.children());
        }
        return new Applied(run.withBoard(company, next), governance, base.information(),
                base.accepted(), base.reason(), base.children());
    }

    /** 期末采样：真实报表正文、基准、证据引用与会议日程。 */
    static Applied capture(EngineeringRun run, BoardRun governance,
                           Map<String, CashLedger.CashAccount> accounts,
                           long manufacturingClosedPeriods, PeriodicCapturePayload payload,
                           String statementBody, String basisBody, List<String> baseRefs,
                           long now, long position) {
        String company = payload.companyPartyId();
        Optional<PeriodicModel.CompanyPeriods> found = run.periodicOf(company);
        if (found.isEmpty()) {
            return Applied.rejected(run, governance, null, PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        PeriodicModel.CompanyPeriods periods = found.get();
        if (!periods.policy().digest().equals(payload.policyDigest())) {
            return Applied.rejected(run, governance, null, "POLICY_MISMATCH");
        }
        long capturedAt;
        try {
            capturedAt = Math.multiplyExact(payload.period(), PeriodicModel.PERIOD_MILLIS);
        } catch (ArithmeticException overflow) {
            return Applied.rejected(run, governance, null, PeriodicModel.REASON_PERIOD_OVERFLOW);
        }
        if (now < capturedAt) {
            return Applied.rejected(run, governance, null, PeriodicModel.REASON_NOT_DUE);
        }
        for (PeriodicModel.Snapshot snapshot : periods.snapshots()) {
            if (snapshot.period() == payload.period()) {
                return Applied.accepted(run, governance, null, List.of());
            }
        }
        if (manufacturingClosedPeriods < payload.period()) {
            // 制造关期与采样同刻但分阶段：关期在 RULES，采样在 OBSERVATION。仍未关期说明
            // 世界结构不完整，直接拒绝，绝不用重试把期后事件倒填进期末摘要。
            return Applied.rejected(run, governance, null, REASON_MANUFACTURING_NOT_CLOSED);
        }
        BoardRun board = boardOf(run, governance, company);
        if (board == null) {
            return Applied.rejected(run, governance, null, PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        String meetingId = meetingId(company, payload.period());
        if (board.board().meeting(meetingId) != null) {
            return Applied.rejected(run, governance, null,
                    PeriodicModel.REASON_PERIOD_ALREADY_CAPTURED);
        }
        long opensAt = capturedAt + periods.policy().materialLeadMillis();
        long closesAt = opensAt + periods.policy().reviewWindowMillis();
        if (now >= closesAt) {
            return Applied.rejected(run, governance, null,
                    PeriodicModel.REASON_REVIEW_WINDOW_CLOSED);
        }
        BoardModel.Outcome convened = BoardModel.conveneByCharter(board.board(), now, meetingId,
                opensAt, opensAt, closesAt);
        if (!convened.accepted()) {
            return Applied.rejected(run, governance, null, convened.reason());
        }
        BoardRun nextBoard = new BoardRun(convened.state(), board.reports(),
                board.periodEvidence());
        String materialSource = materialSourceRef(company, payload.period());
        Set<String> refs = new TreeSet<>(baseRefs);
        refs.add(materialSource);
        refs.addAll(disclosureRefs(periods, company, payload.period(), capturedAt));
        PeriodicModel.Snapshot snapshot = new PeriodicModel.Snapshot(company, payload.period(),
                meetingId, capturedAt, position, sha256(basisBody), sha256(statementBody),
                materialDocId(company, payload.period()), 1L, materialSource, List.copyOf(refs));
        PeriodicModel.Outcome captured = PeriodicModel.capture(run.periodic(), snapshot);
        if (!captured.accepted()) {
            return Applied.rejected(run, governance, null, captured.reason());
        }
        EngineeringRun updatedRun = isCharterCompany(governance, company) ? run
                : run.withBoard(company, nextBoard);
        BoardRun updatedGovernance = isCharterCompany(governance, company) ? nextBoard
                : governance;
        EngineeringRun withPeriodic = updatedRun.withPeriodic(captured.state());
        List<PendingTask> children = new ArrayList<>();
        children.add(new PendingTask("periodic-close:" + company + ":" + payload.period(),
                closesAt, WorldScheduler.Phase.RULES, PendingTask.PERIOD_CLOSE_V1,
                json(new PeriodicCapturePayload(systemCommand("periodic-close", company,
                        payload.period()), company, payload.period(),
                        periods.policy().digest()))));
        long deadline = capturedAt + periods.policy().disclosureWindowMillis();
        children.add(new PendingTask("periodic-deadline:" + company + ":" + payload.period(),
                deadline, WorldScheduler.Phase.RULES, PendingTask.PERIOD_DISCLOSURE_DEADLINE_V1,
                json(new PeriodicCapturePayload(systemCommand("periodic-deadline", company,
                        payload.period()), company, payload.period(),
                        periods.policy().digest()))));
        long nextDue;
        try {
            nextDue = Math.multiplyExact(payload.period() + 1L, PeriodicModel.PERIOD_MILLIS);
        } catch (ArithmeticException overflow) {
            nextDue = -1L;
        }
        if (nextDue > 0) {
            children.add(captureTask(company, payload.period() + 1L, nextDue,
                    "periodic-capture:" + company + ":" + (payload.period() + 1L)));
        }
        return new Applied(withPeriodic, updatedGovernance, null, true,
                PeriodicModel.REASON_OK, children);
    }

    /** 评分窗口关闭：只聚合已合法提交的完整席，不抹除任何旧票。 */
    static Applied close(EngineeringRun run, BoardRun governance, PeriodicCapturePayload payload,
                         long now) {
        String company = payload.companyPartyId();
        Optional<PeriodicModel.CompanyPeriods> found = run.periodicOf(company);
        if (found.isEmpty()) {
            return Applied.rejected(run, governance, null, PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        PeriodicModel.CompanyPeriods periods = found.get();
        BoardRun board = boardOf(run, governance, company);
        PeriodicModel.Snapshot snapshot = snapshotOf(periods, payload.period());
        if (board == null || snapshot == null) {
            return Applied.rejected(run, governance, null, PeriodicModel.REASON_NOT_CAPTURED);
        }
        BoardModel.Meeting meeting = board.board().meeting(snapshot.meetingId());
        PeriodicModel.Outcome closed = PeriodicModel.closeReview(run.periodic(), company,
                payload.period(), meeting, now);
        if (!closed.accepted()) {
            return Applied.rejected(run, governance, null, closed.reason());
        }
        return Applied.accepted(run.withPeriodic(closed.state()), governance, null, List.of());
    }

    /** 披露截止：未发布才记 MISSED；与评分是否缺失解耦，迟发不倒填。 */
    static Applied deadline(EngineeringRun run, BoardRun governance, PeriodicCapturePayload payload,
                            long now) {
        String company = payload.companyPartyId();
        Optional<PeriodicModel.CompanyPeriods> found = run.periodicOf(company);
        if (found.isEmpty()) {
            return Applied.rejected(run, governance, null, PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        PeriodicModel.CompanyPeriods periods = found.get();
        for (PeriodicModel.Release release : periods.releases()) {
            if (release.period() == payload.period()) {
                return Applied.accepted(run, governance, null, List.of());
            }
        }
        PeriodicModel.Outcome missed = PeriodicModel.markMissed(run.periodic(), company,
                payload.period(), now);
        if (!missed.accepted() && !PeriodicModel.REASON_MISSED_EXISTS.equals(missed.reason())) {
            return Applied.rejected(run, governance, null, missed.reason());
        }
        return Applied.accepted(run.withPeriodic(missed.state()), governance, null, List.of());
    }

    /** 五个人物动作的唯一入口。 */
    static Applied action(EngineeringRun run, BoardRun governance, RoleRun roles,
                          InformationRun information, CognitionRecall.Policy recall,
                          PeriodicActionPayload payload, String statementBody, long now,
                          long position) {
        return switch (payload.action()) {
            case "ATTEND" -> attend(run, governance, roles, information, recall, payload, now,
                    position);
            case "PREPARE" -> prepare(run, governance, roles, information, recall, payload,
                    statementBody, now, position);
            case "SCORE" -> score(run, governance, roles, information, recall, payload, now,
                    position);
            case "APPROVE" -> approve(run, governance, roles, information, recall, payload, now,
                    position);
            case "AUTHORIZE" -> authorize(run, governance, roles, information, recall, payload,
                    now, position);
            case "PUBLISH" -> publish(run, governance, roles, information, recall, payload, now,
                    position);
            default -> Applied.rejected(run, governance, information, "UNKNOWN_ACTION");
        };
    }

    private static Applied attend(EngineeringRun run, BoardRun governance, RoleRun roles,
                                  InformationRun information, CognitionRecall.Policy recall,
                                  PeriodicActionPayload payload, long now, long position) {
        String company = payload.companyPartyId();
        Optional<PeriodicModel.CompanyPeriods> found = run.periodicOf(company);
        if (found.isEmpty()) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        PeriodicModel.Snapshot snapshot = snapshotOf(found.get(), payload.period());
        BoardRun board = boardOf(run, governance, company);
        if (snapshot == null || board == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_NOT_CAPTURED);
        }
        PeriodicWorldAccess access = access(run, governance, company, information, recall, roles,
                snapshot.meetingId());
        String seat = access.seatOf(company, payload.personPartyId(), now);
        if (seat == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_NOT_SEAT_HOLDER);
        }
        boolean recused = "RECUSE".equals(payload.note());
        BoardModel.Outcome outcome = BoardModel.attend(board.board(), now, snapshot.meetingId(),
                seat, recused);
        if (!outcome.accepted()) {
            return Applied.rejected(run, governance, information, outcome.reason());
        }
        BoardRun nextBoard = new BoardRun(outcome.state(), board.reports(),
                board.periodEvidence());
        if (isCharterCompany(governance, company)) {
            return Applied.accepted(run, nextBoard, information, List.of());
        }
        return Applied.accepted(run.withBoard(company, nextBoard), governance, information,
                List.of());
    }

    private static Applied prepare(EngineeringRun run, BoardRun governance, RoleRun roles,
                                   InformationRun information, CognitionRecall.Policy recall,
                                   PeriodicActionPayload payload, String statementBody, long now,
                                   long position) {
        String company = payload.companyPartyId();
        Optional<PeriodicModel.CompanyPeriods> found = run.periodicOf(company);
        if (found.isEmpty()) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        PeriodicModel.CompanyPeriods periods = found.get();
        PeriodicModel.Snapshot snapshot = snapshotOf(periods, payload.period());
        BoardRun board = boardOf(run, governance, company);
        if (snapshot == null || board == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_NOT_CAPTURED);
        }
        if (statementBody == null || statementBody.isBlank()
                || !sha256(statementBody).equals(snapshot.statementDigest())) {
            return Applied.rejected(run, governance, information,
                    REASON_STATEMENT_UNAVAILABLE);
        }
        BoardModel.Meeting meeting = board.board().meeting(snapshot.meetingId());
        if (meeting == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_NOT_MEETING);
        }
        if (meeting.status() != BoardModel.MeetingStatus.CONVENED
                && meeting.status() != BoardModel.MeetingStatus.MATERIALS_FROZEN) {
            return Applied.rejected(run, governance, information, REASON_MEETING_STATE);
        }
        int index = 1;
        for (PeriodicModel.Draft draft : periods.drafts()) {
            if (draft.period() == payload.period()) {
                index++;
            }
        }
        String docId = draftDocId(company, payload.period(), index);
        String body = payload.note().isBlank() ? statementBody
                : statementBody + "\n更正说明：" + payload.note();
        String title = "周期材料 " + company + " 第" + payload.period() + "期";
        InformationModel.State info = information.information();
        if (InformationModel.version(info, docId, 1L).isPresent()) {
            return Applied.rejected(run, governance, information, REASON_MATERIAL_EXISTS);
        }
        InformationModel.VersionDraft version = new InformationModel.VersionDraft(title, body, "",
                now, MATERIAL_READ_MILLIS, List.of(snapshot.materialSourceRef()));
        InformationModel.State published = InformationModel.publish(info, docId,
                payload.personPartyId(), InformationModel.Audience.board(company), version);
        BoardRun nextBoard = board;
        if (meeting.status() == BoardModel.MeetingStatus.CONVENED) {
            BoardModel.Outcome frozen = BoardModel.freezePeriodicMaterials(board.board(), now,
                    meeting.meetingId(), docId, snapshot.materialSourceRef(), 1L);
            if (!frozen.accepted()) {
                return Applied.rejected(run, governance, information, frozen.reason());
            }
            nextBoard = new BoardRun(frozen.state(), board.reports(), board.periodEvidence());
        }
        PeriodicModel.Draft draft = new PeriodicModel.Draft(draftId(company, payload.period(),
                index), company, payload.period(), docId, 1L, sha256(body),
                payload.personPartyId(), now, position, snapshot.position(),
                snapshot.basisDigest());
        PeriodicModel.Outcome prepared = PeriodicModel.prepareDraft(run.periodic(), draft,
                access(run, governance, company, information, recall, roles,
                        meeting.meetingId()), now);
        if (!prepared.accepted()) {
            return Applied.rejected(run, governance, information, prepared.reason());
        }
        EngineeringRun updatedRun = isCharterCompany(governance, company) ? run
                : run.withBoard(company, nextBoard).withPeriodic(prepared.state());
        EngineeringRun withPeriodic = isCharterCompany(governance, company)
                ? run.withPeriodic(prepared.state()) : updatedRun;
        BoardRun updatedGovernance = isCharterCompany(governance, company) ? nextBoard
                : governance;
        return new Applied(withPeriodic, updatedGovernance,
                new InformationRun(published, information.reports()), true,
                PeriodicModel.REASON_OK, List.of());
    }

    private static Applied score(EngineeringRun run, BoardRun governance, RoleRun roles,
                                 InformationRun information, CognitionRecall.Policy recall,
                                 PeriodicActionPayload payload, long now, long position) {
        String company = payload.companyPartyId();
        Optional<PeriodicModel.CompanyPeriods> found = run.periodicOf(company);
        if (found.isEmpty() || information == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        PeriodicModel.CompanyPeriods periods = found.get();
        PeriodicModel.Snapshot snapshot = snapshotOf(periods, payload.period());
        BoardRun board = boardOf(run, governance, company);
        if (snapshot == null || board == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_NOT_CAPTURED);
        }
        BoardModel.Meeting meeting = board.board().meeting(snapshot.meetingId());
        PeriodicWorldAccess access = access(run, governance, company, information, recall, roles,
                snapshot.meetingId());
        String seat = access.seatOf(company, payload.personPartyId(), now);
        String readId = lastCompletedReadId(information.information(), payload.personPartyId(),
                snapshot.materialDocId(), snapshot.materialVersion(), now);
        if (readId.isBlank()) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_NOT_READ_EXACT_VERSION);
        }
        List<BoardModel.EvidenceMetric> metrics = new ArrayList<>();
        for (PeriodicActionPayload.PeriodicMetric metric : payload.metrics()) {
            BoardModel.Dimension dimension;
            try {
                dimension = BoardModel.Dimension.valueOf(metric.dimension());
            } catch (IllegalArgumentException unknown) {
                return Applied.rejected(run, governance, information, "UNKNOWN_DIMENSION");
            }
            String problem = dimensionProblem(dimension, metric.sourceRef(), metric.asOf(),
                    snapshot, periods);
            if (problem != null) {
                return Applied.rejected(run, governance, information, problem);
            }
            metrics.add(new BoardModel.EvidenceMetric(dimension, metric.score(),
                    metric.sourceRef(), metric.asOf()));
        }
        PeriodicModel.Ballot ballot = new PeriodicModel.Ballot(company, payload.period(),
                snapshot.meetingId(), seat == null ? "" : seat, payload.personPartyId(), now,
                position, readId, snapshot.materialDocId(), snapshot.materialVersion(), metrics,
                payload.note());
        PeriodicModel.Outcome submitted = PeriodicModel.submitBallot(run.periodic(), ballot,
                meeting, access, now);
        if (!submitted.accepted()) {
            return Applied.rejected(run, governance, information, submitted.reason());
        }
        return Applied.accepted(run.withPeriodic(submitted.state()), governance, information,
                List.of());
    }

    /**
     * 维度证据绑定：只接受本期冻结集合里、类型与该维相符的真实引用。
     *
     * <p>长期投入机制在本政策里明确为 {@code none}，因此该维永远缺项；披露质量只接受截止前
     * 已成熟的真实发布/错过事实。普通合同、工单、账户与私有材料来源都不能冒充这两维。</p>
     */
    private static String dimensionProblem(BoardModel.Dimension dimension, String ref, long asOf,
                                           PeriodicModel.Snapshot snapshot,
                                           PeriodicModel.CompanyPeriods periods) {
        if (!snapshot.evidenceRefs().contains(ref) || ref.startsWith("periodic-source:")) {
            return REASON_EVIDENCE_NOT_ELIGIBLE;
        }
        return switch (dimension) {
            case OPERATING_COMMITMENT -> ref.startsWith("contract:") || ref.startsWith("work:")
                    || ref.startsWith("invoice:") ? null : REASON_EVIDENCE_NOT_ELIGIBLE;
            case CASH_SOLVENCY -> ref.startsWith("account:") ? null
                    : REASON_EVIDENCE_NOT_ELIGIBLE;
            case RISK -> ref.startsWith("contract:") || ref.startsWith("invoice:") ? null
                    : REASON_EVIDENCE_NOT_ELIGIBLE;
            case DISCLOSURE -> disclosureMatches(ref, asOf, periods, snapshot.companyPartyId())
                    ? null : REASON_EVIDENCE_NOT_ELIGIBLE;
            case LONG_TERM_INVESTMENT -> ref.startsWith("longterm:")
                    ? null : REASON_DIMENSION_NOT_AVAILABLE;
        };
    }

    /** 披露维只认截止前真实的发布/错过事实，且时点必须逐字一致。 */
    private static boolean disclosureMatches(String ref, long asOf,
                                             PeriodicModel.CompanyPeriods periods,
                                             String company) {
        String prefix = "disclosure:" + company + ":";
        if (!ref.startsWith(prefix)) {
            return false;
        }
        String rest = ref.substring(prefix.length());
        if (rest.endsWith(":missed")) {
            String periodText = rest.substring(0, rest.length() - ":missed".length());
            try {
                long period = Long.parseLong(periodText);
                for (PeriodicModel.Missed marker : periods.missed()) {
                    if (marker.period() == period && marker.missedAt() == asOf) {
                        return true;
                    }
                }
            } catch (NumberFormatException invalid) {
                return false;
            }
            return false;
        }
        int releaseIndex = rest.lastIndexOf(":release:");
        if (releaseIndex <= 0) {
            return false;
        }
        try {
            long period = Long.parseLong(rest.substring(0, releaseIndex));
            long version = Long.parseLong(rest.substring(releaseIndex + ":release:".length()));
            long order = 1L;
            for (PeriodicModel.Release release : periods.releases()) {
                if (release.period() != period) {
                    continue;
                }
                if (order == version && release.publishedAt() == asOf) {
                    return true;
                }
                order++;
            }
        } catch (NumberFormatException invalid) {
            return false;
        }
        return false;
    }

    private static Applied approve(EngineeringRun run, BoardRun governance, RoleRun roles,
                                   InformationRun information, CognitionRecall.Policy recall,
                                   PeriodicActionPayload payload, long now, long position) {
        String company = payload.companyPartyId();
        Optional<PeriodicModel.CompanyPeriods> found = run.periodicOf(company);
        if (found.isEmpty() || information == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        PeriodicModel.CompanyPeriods periods = found.get();
        PeriodicModel.Draft draft = draftOf(periods, payload);
        if (draft == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_UNKNOWN_DRAFT);
        }
        // 回避按该草稿所属周期会议判定：会议关闭后仍沿用同一 meeting 的 recusedSeats，
        // 不被同公司另一场并行会议的回避误伤，也不随窗口结束失效。
        PeriodicModel.Snapshot snapshot = snapshotOf(periods, draft.period());
        PeriodicWorldAccess access = access(run, governance, company, information, recall, roles,
                snapshot == null ? null : snapshot.meetingId());
        String seat = access.seatOf(company, payload.personPartyId(), now);
        PeriodicModel.Approval approval = new PeriodicModel.Approval(company, payload.period(),
                draft.draftId(), draft.version(), draft.bodyDigest(), payload.personPartyId(),
                seat == null ? "" : seat, now, position);
        PeriodicModel.Outcome approved = PeriodicModel.approve(run.periodic(), approval, access,
                now);
        if (!approved.accepted()) {
            return Applied.rejected(run, governance, information, approved.reason());
        }
        return Applied.accepted(run.withPeriodic(approved.state()), governance, information,
                List.of());
    }

    private static Applied authorize(EngineeringRun run, BoardRun governance, RoleRun roles,
                                     InformationRun information, CognitionRecall.Policy recall,
                                     PeriodicActionPayload payload, long now, long position) {
        String company = payload.companyPartyId();
        Optional<PeriodicModel.CompanyPeriods> found = run.periodicOf(company);
        if (found.isEmpty()) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        PeriodicModel.CompanyPeriods periods = found.get();
        PeriodicModel.Draft draft = draftOf(periods, payload);
        if (draft == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_UNKNOWN_DRAFT);
        }
        Set<String> approvers = new TreeSet<>();
        for (PeriodicModel.Approval approval : periods.approvals()) {
            if (approval.draftId().equals(draft.draftId())
                    && approval.draftVersion() == draft.version()) {
                approvers.add(approval.approverPersonId());
            }
        }
        if (approvers.size() < PeriodicModel.MIN_COMPLETE_SEATS) {
            // 不足三名合法批准人不得签发：按业务拒绝返回，不构造非法授权对象抛异常。
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_DRAFT_NOT_APPROVED);
        }
        String authorizationId = payload.authorizationId().isBlank()
                ? "periodic-auth:" + company + ":" + payload.period() + ":"
                        + shortHash(draft.draftId() + "#" + draft.version() + "#"
                        + payload.publisherPersonId() + "#" + payload.expiresAt())
                : payload.authorizationId();
        PeriodicModel.Authorization authorization = new PeriodicModel.Authorization(
                authorizationId, company, payload.period(), draft.draftId(), draft.version(),
                draft.bodyDigest(), new ArrayList<>(approvers), payload.publisherPersonId(),
                PeriodicModel.PURPOSE_DISCLOSE, now, payload.expiresAt(), 0L);
        PeriodicWorldAccess access = access(run, governance, company, information, recall, roles,
                snapshotOf(periods, draft.period()) == null ? null
                        : snapshotOf(periods, draft.period()).meetingId());
        PeriodicModel.Outcome issued = PeriodicModel.authorize(run.periodic(), authorization,
                access, now);
        if (!issued.accepted()) {
            return Applied.rejected(run, governance, information, issued.reason());
        }
        return Applied.accepted(run.withPeriodic(issued.state()), governance, information,
                List.of());
    }

    private static Applied publish(EngineeringRun run, BoardRun governance, RoleRun roles,
                                   InformationRun information, CognitionRecall.Policy recall,
                                   PeriodicActionPayload payload, long now, long position) {
        String company = payload.companyPartyId();
        Optional<PeriodicModel.CompanyPeriods> found = run.periodicOf(company);
        if (found.isEmpty() || information == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_UNKNOWN_COMPANY);
        }
        PeriodicModel.CompanyPeriods periods = found.get();
        PeriodicModel.Draft draft = draftOf(periods, payload);
        if (draft == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_UNKNOWN_DRAFT);
        }
        Optional<InformationModel.DocumentVersion> material = InformationModel.version(
                information.information(), draft.docId(), draft.version());
        if (material.isEmpty() || !sha256(material.get().body()).equals(draft.bodyDigest())) {
            return Applied.rejected(run, governance, information, REASON_BODY_MISMATCH);
        }
        PeriodicModel.Authorization authorization = authorizationOf(periods, payload, draft);
        if (authorization == null) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_APPROVAL_MISSING);
        }
        long deadline;
        try {
            deadline = Math.addExact(Math.multiplyExact(payload.period(),
                    periods.policy().periodMillis()), periods.policy().disclosureWindowMillis());
        } catch (ArithmeticException overflow) {
            return Applied.rejected(run, governance, information,
                    PeriodicModel.REASON_PERIOD_OVERFLOW);
        }
        boolean late = now >= deadline;
        PeriodicModel.Release release = new PeriodicModel.Release(
                authorization.authorizationId(), company, payload.period(), draft.draftId(),
                draft.version(), draft.docId(), draft.version(), draft.bodyDigest(),
                payload.personPartyId(), now, late);
        PeriodicModel.Outcome published = PeriodicModel.publish(run.periodic(), release,
                access(run, governance, company, information, recall, roles, null), now);
        if (!published.accepted()) {
            return Applied.rejected(run, governance, information, published.reason());
        }
        long publicVersion = 1L;
        for (PeriodicModel.Release existing : periods.releases()) {
            if (existing.period() == payload.period()) {
                publicVersion++;
            }
        }
        String publicDocId = publicDocId(company, payload.period());
        InformationModel.State info = information.information();
        InformationModel.VersionDraft version = new InformationModel.VersionDraft(
                "周期财报 " + company + " 第" + payload.period() + "期 v" + publicVersion,
                material.get().body(), "", now, MATERIAL_READ_MILLIS,
                List.of(draft.docId() + "@" + draft.version()));
        InformationModel.State nextInfo;
        try {
            nextInfo = InformationModel.version(info, publicDocId, 1L).isPresent()
                    ? InformationModel.publishVersion(info, publicDocId, version)
                    : InformationModel.publish(info, publicDocId, payload.personPartyId(),
                            InformationModel.Audience.publicAudience(), version);
        } catch (IllegalArgumentException invalid) {
            return Applied.rejected(run, governance, information, "PUBLIC_PUBLISH_REJECTED");
        }
        long storedVersion = InformationModel.version(nextInfo, publicDocId, publicVersion)
                .map(InformationModel.DocumentVersion::version).orElse(-1L);
        if (storedVersion != publicVersion) {
            return Applied.rejected(run, governance, information, "PUBLIC_VERSION_MISMATCH");
        }
        return new Applied(run.withPeriodic(published.state()), governance,
                new InformationRun(nextInfo, information.reports()), true,
                PeriodicModel.REASON_OK, List.of());
    }

    // ---------- 只读派生（断言与调用方共用） ----------

    /** 工程公司期末真实报表正文：只由该帧工程状态与唯一账户表现算。 */
    static String statementText(EngineeringRun run, Map<String, CashLedger.CashAccount> accounts,
                                String company, long period, long capturedAt) {
        EngineeringRun.CompanySpec spec = run.config().specOf(company);
        EngineeringModel.State state = run.stateOf(company);
        if (spec == null || state == null) {
            return "";
        }
        CashLedger.CashAccount account = accounts.get(spec.companyAccountId());
        BigDecimal cash = account == null ? BigDecimal.ZERO : account.cash();
        BigDecimal asset = BigDecimal.ZERO;
        BigDecimal arCurrent = BigDecimal.ZERO;
        BigDecimal arRetention = BigDecimal.ZERO;
        BigDecimal allowance = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        for (EngineeringModel.Claim claim : state.claims().values()) {
            asset = asset.add(claim.contractAsset());
            arCurrent = arCurrent.add(claim.arCurrent());
            arRetention = arRetention.add(claim.arRetention());
            allowance = allowance.add(claim.allowance());
            revenue = revenue.add(claim.revenue());
            cogs = cogs.add(claim.cogs());
        }
        return "周期报表\n公司=" + company + "\n期间=" + period + "\n截至=" + capturedAt
                + "\n现金=" + money(cash) + "\n合同资产=" + money(asset) + "\n应收="
                + money(arCurrent) + "\n质保应收=" + money(arRetention) + "\n减值="
                + money(allowance) + "\n应付=" + money(state.payable()) + "\n累计收入="
                + money(revenue) + "\n累计成本=" + money(cogs) + "\n合同数="
                + state.contracts().size();
    }

    /** 制造公司期末真实报表正文：只由该帧制造台账与账户表现算。 */
    static String manufacturingStatement(ManufacturingRun run,
                                         Map<String, CashLedger.CashAccount> accounts,
                                         DividendModel.State dividends, String company,
                                         long period, long capturedAt) {
        if (run == null) {
            return "";
        }
        ManufacturingModel.Report report = ManufacturingModel.report(run.ledger(), accounts,
                dividendPosition(dividends));
        return "周期报表\n公司=" + company + "\n期间=" + period + "\n截至=" + capturedAt
                + "\n现金=" + money(report.companyCash()) + "\n原材料="
                + money(report.rawAmount()) + "\n在制=" + money(report.wipAmount()) + "\n产成品="
                + money(report.finishedAmount()) + "\n应收="
                + money(report.receivableOutstanding()) + "\n设备净值="
                + money(report.equipmentNet()) + "\n应付="
                + money(report.payableOutstanding()) + "\n累计收入="
                + money(report.revenue()) + "\n累计成本=" + money(report.costOfGoodsSold())
                + "\n期间费用=" + money(report.periodExpense()) + "\n累计折旧="
                + money(report.depreciation());
    }

    /** 制造公司期间基准正文：初始权益与初始世界现金来自冻结台账。 */
    static String manufacturingBasis(ManufacturingRun run, String company, long period,
                                     long capturedAt) {
        if (run == null) {
            return "";
        }
        return "期间基准\n公司=" + company + "\n期间=" + period + "\n起点="
                + (capturedAt - PeriodicModel.PERIOD_MILLIS) + "\n初始权益="
                + money(run.ledger().initialEquity()) + "\n初始世界现金="
                + money(run.ledger().initialWorldCash());
    }

    /** 期间基准正文（工程公司）：仅由开局冻结基准与期间起点构成。 */
    static String basisText(EngineeringRun run, String company, long period, long capturedAt) {
        EngineeringModel.State state = run.stateOf(company);
        if (state == null) {
            return "";
        }
        long start = capturedAt - PeriodicModel.PERIOD_MILLIS;
        return "期间基准\n公司=" + company + "\n期间=" + period + "\n起点=" + start
                + "\n初始权益=" + money(state.initialEquity()) + "\n初始世界现金="
                + money(state.initialWorldCash());
    }

    /** 工程公司本期允许引用的真实证据（不含披露维，披露引用由采样按真实事实追加）。 */
    static List<String> evidenceRefs(EngineeringRun run,
                                     Map<String, CashLedger.CashAccount> accounts,
                                     String company) {
        EngineeringRun.CompanySpec spec = run.config().specOf(company);
        EngineeringModel.State state = run.stateOf(company);
        Set<String> refs = new TreeSet<>();
        if (spec != null) {
            refs.add("account:" + spec.companyAccountId());
        }
        if (state != null) {
            for (EngineeringModel.Contract contract : state.contracts().values()) {
                refs.add("contract:" + contract.contractId());
                for (int index = 0; index < contract.invoices().size(); index++) {
                    refs.add("invoice:" + contract.contractId() + ":" + index);
                }
            }
            for (EngineeringModel.WorkOrder order : state.workOrders().values()) {
                refs.add("work:" + order.workId());
            }
        }
        return List.copyOf(refs);
    }

    /** 披露维唯一合格引用：截止前真实的发布版本或错过事实。 */
    private static List<String> disclosureRefs(PeriodicModel.CompanyPeriods periods,
                                               String company, long period, long capturedAt) {
        Set<String> refs = new TreeSet<>();
        for (PeriodicModel.Release release : periods.releases()) {
            if (release.period() >= period || release.publishedAt() > capturedAt) {
                continue;
            }
            long order = 1L;
            for (PeriodicModel.Release each : periods.releases()) {
                if (each.period() == release.period()) {
                    if (each.equals(release)) {
                        refs.add("disclosure:" + company + ":" + release.period() + ":release:"
                                + order);
                    }
                    order++;
                }
            }
        }
        for (PeriodicModel.Missed marker : periods.missed()) {
            if (marker.period() < period && marker.missedAt() <= capturedAt) {
                refs.add("disclosure:" + company + ":" + marker.period() + ":missed");
            }
        }
        return List.copyOf(refs);
    }

    static String meetingId(String company, long period) {
        return "periodic-meeting:" + company + ":" + period;
    }

    static String materialDocId(String company, long period) {
        return "periodic-material:" + company + ":" + period;
    }

    static String materialSourceRef(String company, long period) {
        return "periodic-source:" + company + ":" + period;
    }

    static String publicDocId(String company, long period) {
        return "company-statement:" + company + ":" + period;
    }

    static String draftDocId(String company, long period, int index) {
        return index <= 1 ? materialDocId(company, period)
                : materialDocId(company, period) + ":r" + index;
    }

    static String draftId(String company, long period, int index) {
        return "periodic-draft:" + company + ":" + period + ":" + index;
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("缺少 SHA-256", missing);
        }
    }

    // ---------- 内部 ----------

    private static ManufacturingModel.DividendPosition dividendPosition(
            DividendModel.State dividends) {
        if (dividends == null) {
            return ManufacturingModel.DividendPosition.none();
        }
        BigDecimal declared = BigDecimal.ZERO;
        BigDecimal unpaid = BigDecimal.ZERO;
        for (DividendModel.Scheme scheme : dividends.schemes().values()) {
            if (scheme.phase() != DividendModel.Phase.EX_BOOKED
                    && scheme.phase() != DividendModel.Phase.SETTLED) {
                continue;
            }
            for (DividendModel.Claim claim : DividendModel.claims(dividends, scheme.schemeId())) {
                declared = declared.add(claim.face());
                unpaid = unpaid.add(DividendModel.faceUnpaid(claim));
            }
        }
        return new ManufacturingModel.DividendPosition(CashLedger.money(declared),
                CashLedger.money(unpaid));
    }

    private static PeriodicWorldAccess access(EngineeringRun run, BoardRun governance,
                                              String company, InformationRun information,
                                              CognitionRecall.Policy recall, RoleRun roles,
                                              String meetingFilter) {
        java.util.Map<String, BoardModel.Charter> charters = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<BoardModel.Meeting>> meetings = new java.util.LinkedHashMap<>();
        if (run != null) {
            for (String each : run.companyIds()) {
                BoardRun board = run.boardOf(each);
                if (board == null) {
                    continue;
                }
                charters.put(each, board.board().charter());
                List<BoardModel.Meeting> rows = new ArrayList<>(board.board().meetings().values());
                rows.sort(Comparator.comparing(BoardModel.Meeting::meetingId));
                meetings.put(each, List.copyOf(rows));
            }
        }
        if (governance != null) {
            String charterCompany = governance.board().charter().companyPartyId();
            charters.putIfAbsent(charterCompany, governance.board().charter());
            List<BoardModel.Meeting> rows = new ArrayList<>(governance.board().meetings().values());
            rows.sort(Comparator.comparing(BoardModel.Meeting::meetingId));
            meetings.putIfAbsent(charterCompany, List.copyOf(rows));
        }
        BoardRun selected = boardOf(run, governance, company);
        List<BoardModel.Meeting> selectedMeetings = selected == null ? List.of()
                : new ArrayList<>(selected.board().meetings().values());
        selectedMeetings.sort(Comparator.comparing(BoardModel.Meeting::meetingId));
        return PeriodicWorldAccess.onFrame(company,
                selected == null ? null : selected.board().charter(), selectedMeetings,
                information == null ? null : information.information(), recall,
                roles == null ? null : roles.access(), meetingFilter);
    }

    private static PeriodicModel.Snapshot snapshotOf(PeriodicModel.CompanyPeriods periods,
                                                     long period) {
        for (PeriodicModel.Snapshot snapshot : periods.snapshots()) {
            if (snapshot.period() == period) {
                return snapshot;
            }
        }
        return null;
    }

    private static PeriodicModel.Draft draftOf(PeriodicModel.CompanyPeriods periods,
                                               PeriodicActionPayload payload) {
        if (!payload.draftId().isBlank()) {
            for (PeriodicModel.Draft draft : periods.drafts()) {
                if (draft.draftId().equals(payload.draftId())
                        && (payload.draftVersion() <= 0
                        || draft.version() == payload.draftVersion())) {
                    return draft;
                }
            }
            return null;
        }
        PeriodicModel.Draft latest = null;
        for (PeriodicModel.Draft draft : periods.drafts()) {
            if (draft.period() != payload.period()) {
                continue;
            }
            if (latest == null || draft.draftId().compareTo(latest.draftId()) > 0) {
                latest = draft;
            }
        }
        return latest;
    }

    private static PeriodicModel.Authorization authorizationOf(
            PeriodicModel.CompanyPeriods periods, PeriodicActionPayload payload,
            PeriodicModel.Draft draft) {
        for (PeriodicModel.Authorization authorization : periods.authorizations()) {
            if (!payload.authorizationId().isBlank()) {
                if (authorization.authorizationId().equals(payload.authorizationId())) {
                    return authorization;
                }
                continue;
            }
            if (authorization.draftId().equals(draft.draftId())
                    && authorization.draftVersion() == draft.version()
                    && authorization.consumedAt() == 0) {
                return authorization;
            }
        }
        return null;
    }

    private static String lastCompletedReadId(InformationModel.State info, String person,
                                              String docId, long version, long at) {
        String found = "";
        long latest = -1L;
        for (InformationModel.ClosedReading closed : info.closed()) {
            if (closed.personPartyId().equals(person)
                    && closed.docId().equals(docId)
                    && closed.version() == version
                    && closed.reason() == InformationModel.CloseReason.COMPLETED
                    && closed.closedAt() <= at
                    && closed.closedAt() > latest) {
                latest = closed.closedAt();
                found = closed.readId();
            }
        }
        return found;
    }

    private static PendingTask captureTask(String company, long period, long due, String taskId) {
        PeriodicCapturePayload payload = new PeriodicCapturePayload(
                systemCommand("periodic-capture", company, period), company, period,
                policy().digest());
        return new PendingTask(taskId, due, WorldScheduler.Phase.PERIOD_BOUNDARY_CAPTURE,
                PendingTask.PERIOD_CAPTURE_V1, json(payload));
    }

    private static String systemCommand(String kind, String company, long period) {
        return "system:" + kind + ":" + company + ":" + period;
    }

    private static String shortHash(String text) {
        return sha256(text).substring(0, 16);
    }

    private static String money(BigDecimal value) {
        return CashLedger.money(value).toPlainString();
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("周期任务参数无法序列化", failure);
        }
    }
}
