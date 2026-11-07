package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4i.2a 采访预约协调运行桥的定向测试：正常链、门任务、预算预留、恢复。
 *
 * <p>全部从正常入口构造：发布公开模拟联系说明 → 记者真实读完 → 邀请 → 对方接受 → 到点开始 →
 * 提前结束；不手改 snapshot，不伪造事件。</p>
 */
class InterviewRuntimeBridgeTest {

    private static final String CONTACT_DOC = WorldWebScenario.INTERVIEW_CONTACT_DOC;

    /** 发布并阅读联系说明，返回记者可以发起邀请的时点。 */
    private static long prepareContact(WorldRuntime runtime) throws Exception {
        return prepareContact(runtime, "rita");
    }

    /** 指定人物真实读完公开联系说明。 */
    private static long prepareContact(WorldRuntime runtime, String reader) throws Exception {
        long now = runtime.snapshot().timeMillis();
        long publish = now + 1_000L;
        runtime.schedulePublishDocument("cmd-contact", "task-contact", publish, CONTACT_DOC,
                "investor", InformationModel.Audience.publicAudience(), "公开联系说明",
                "模拟：工作时段可通过公开投关热线预约采访", "公司公开资料", 1_000L, List.of());
        runtime.advanceTo(publish);
        long startRead = publish + 500L;
        runtime.openRoleSession(reader).startReading("cmd-read-" + reader, "task-read-" + reader,
                startRead, CONTACT_DOC, 1L);
        runtime.advanceTo(startRead);
        long readDue = startRead + 1_000L;
        runtime.advanceTo(readDue);
        return readDue;
    }

    /** 让另一个人也真实读完同一份公开联系说明（同一文档不重复发布）。 */
    private static long readContact(WorldRuntime runtime, String reader) throws Exception {
        long now = runtime.snapshot().timeMillis();
        long startRead = now + 500L;
        runtime.openRoleSession(reader).startReading("cmd-read2-" + reader, "task-read2-" + reader,
                startRead, CONTACT_DOC, 1L);
        runtime.advanceTo(startRead);
        long readDue = startRead + 1_000L;
        runtime.advanceTo(readDue);
        return readDue;
    }

    @Test
    void fullChainInviteAcceptStartEarlyEnd(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("interview.journal"))
                .runtime()) {
            long base = prepareContact(runtime);
            assertTrue(runtime.interviewView("rita").contacts().stream()
                    .anyMatch(contact -> contact.targetPartyId().equals("investor")),
                    "记者真实读完联系说明后应能看见可达入口");

            long inviteDue = base + 1_000L;
            long start = base + 300_000L;
            runtime.openRoleSession("rita").inviteInterview("cmd-invite", "task-invite",
                    inviteDue, new WorldRoleSession.InterviewInviteDraft("req-1", "investor",
                            "关于近期订单的采访", start - 60_000L, start + 3_120_000L, 120_000L));
            runtime.advanceTo(inviteDue);

            var view = runtime.interviewView("investor");
            assertEquals(1, view.invites().size());
            assertEquals("PROPOSED", view.invites().get(0).status());
            String requestId = view.invites().get(0).requestId();

            long respondDue = inviteDue + 1_000L;
            runtime.openRoleSession("investor").respondInterview("cmd-respond", "task-respond",
                    respondDue, new WorldRoleSession.InterviewRespondDraft(requestId, true, start));
            runtime.advanceTo(respondDue);

            var accepted = runtime.interviewAudit();
            assertEquals("ACCEPTED", accepted.invites().get(0).status());
            long reserved = accepted.budgets().stream()
                    .filter(budget -> budget.organizationPartyId().equals("wire"))
                    .findFirst().orElseThrow().reservedMillis();
            assertEquals(120_000L, reserved, "接受后必须保留完整预约区间");

            runtime.advanceTo(start);
            assertEquals("ACTIVE", runtime.interviewAudit().invites().get(0).status());
            assertEquals(120_000L, runtime.interviewAudit().budgets().stream()
                    .filter(budget -> budget.organizationPartyId().equals("wire"))
                    .findFirst().orElseThrow().reservedMillis(),
                    "会话进行中仍保留完整区间，不提前释放已消耗部分");

            long endScheduleAt = start + 4_500L;
            runtime.advanceTo(endScheduleAt);
            long endDue = endScheduleAt + 500L;
            runtime.openRoleSession("rita").endInterview("cmd-end", "task-end",
                    endDue, new WorldRoleSession.InterviewRequestDraft(requestId));
            runtime.advanceTo(endDue);

            var audit = runtime.interviewAudit();
            assertEquals("ENDED_EARLY", audit.invites().get(0).status());
            assertEquals(0L, audit.budgets().stream()
                    .filter(budget -> budget.organizationPartyId().equals("wire"))
                    .findFirst().orElseThrow().reservedMillis(),
                    "结束后整条预留移除");
            var sessionWork = audit.works().stream()
                    .filter(work -> work.kind().equals(MediaRun.KIND_INTERVIEW_SESSION))
                    .findFirst().orElse(null);
            assertNotNull(sessionWork, "必须有会话结算工时记录");
            assertEquals(5_000L, sessionWork.chargedMillis(), "提前结束只计实际区间");
        }
    }

    @Test
    void crossOrganizationDoubleBookingIsRejected(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("double.journal"))
                .runtime()) {
            long base = prepareContact(runtime);
            long samReady = readContact(runtime, "sam");
            long start = base + 300_000L;

            long samDue = samReady + 1_000L;
            runtime.openRoleSession("sam").inviteInterview("cmd-sam-invite", "task-sam-invite",
                    samDue, new WorldRoleSession.InterviewInviteDraft("req-inv", "investor",
                            "调查社同一时段", start - 60_000L, start + 3_120_000L, 120_000L));
            runtime.advanceTo(samDue);
            String samRequest = runtime.interviewView("investor").invites().get(0).requestId();
            long samRespond = samDue + 1_000L;
            runtime.openRoleSession("investor").respondInterview("cmd-sam-respond",
                    "task-sam-respond", samRespond,
                    new WorldRoleSession.InterviewRespondDraft(samRequest, true, start));
            runtime.advanceTo(samRespond);
            assertEquals("ACCEPTED", runtime.interviewAudit().invites().get(0).status());

            long ritaDue = samRespond + 1_000L;
            runtime.openRoleSession("rita").inviteInterview("cmd-rita-invite", "task-rita-invite",
                    ritaDue, new WorldRoleSession.InterviewInviteDraft("req-wire", "investor",
                            "快讯社同一时段", start - 60_000L, start + 3_120_000L, 120_000L));
            runtime.advanceTo(ritaDue);
            String ritaRequestCandidate = null;
            for (var invite : runtime.interviewView("investor").invites()) {
                if (invite.fromPartyId().equals("rita")) {
                    ritaRequestCandidate = invite.requestId();
                }
            }
            assertNotNull(ritaRequestCandidate);
            final String ritaRequest = ritaRequestCandidate;
            long ritaRespond = ritaDue + 1_000L;
            runtime.openRoleSession("investor").respondInterview("cmd-rita-respond",
                    "task-rita-respond", ritaRespond,
                    new WorldRoleSession.InterviewRespondDraft(ritaRequest, true, start));
            runtime.advanceTo(ritaRespond);

            String status = runtime.interviewAudit().invites().stream()
                    .filter(invite -> invite.requestId().equals(ritaRequest))
                    .findFirst().orElseThrow().status();
            assertEquals("PROPOSED", status, "跨社会话占用必须让第二个接受 CONFLICT，不能双约成功");
        }
    }

    @Test
    void bookingHoldsBudgetUntilSettlement(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("budget.journal"))
                .runtime()) {
            long base = prepareContact(runtime);
            long start = base + 300_000L;
            long duration = 200_000L;

            long inviteDue = base + 1_000L;
            runtime.openRoleSession("rita").inviteInterview("cmd-b1", "task-b1", inviteDue,
                    new WorldRoleSession.InterviewInviteDraft("req-b1", "investor", "长会话",
                            start - 60_000L, start + 4_000_000L, duration));
            runtime.advanceTo(inviteDue);
            String requestId = runtime.interviewView("investor").invites().get(0).requestId();
            long respondDue = inviteDue + 1_000L;
            runtime.openRoleSession("investor").respondInterview("cmd-b2", "task-b2", respondDue,
                    new WorldRoleSession.InterviewRespondDraft(requestId, true, start));
            runtime.advanceTo(respondDue);

            long reserved = runtime.interviewAudit().budgets().stream()
                    .filter(budget -> budget.organizationPartyId().equals("wire"))
                    .findFirst().orElseThrow().reservedMillis();
            assertEquals(duration, reserved);

            long midway = start + duration / 2;
            runtime.advanceTo(midway);
            long midwayReserved = runtime.interviewAudit().budgets().stream()
                    .filter(budget -> budget.organizationPartyId().equals("wire"))
                    .findFirst().orElseThrow().reservedMillis();
            assertEquals(duration, midwayReserved,
                    "中途不得把已消耗未结算的工时重新释放成可用额度");
        }
    }

    @Test
    void missedBookingReleasesReservation(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("miss.journal"))
                .runtime()) {
            long base = prepareContact(runtime);
            long start = base + 300_000L;
            long inviteDue = base + 1_000L;
            runtime.openRoleSession("rita").inviteInterview("cmd-m1", "task-m1", inviteDue,
                    new WorldRoleSession.InterviewInviteDraft("req-m1", "investor", "未赴约",
                            start - 60_000L, start + 3_120_000L, 120_000L));
            runtime.advanceTo(inviteDue);
            String requestId = runtime.interviewView("investor").invites().get(0).requestId();
            long respondDue = inviteDue + 1_000L;
            runtime.openRoleSession("investor").respondInterview("cmd-m2", "task-m2", respondDue,
                    new WorldRoleSession.InterviewRespondDraft(requestId, true, start));
            runtime.advanceTo(respondDue);

            long scheduledEnd = start + 120_000L;
            runtime.advanceTo(scheduledEnd);
            var audit = runtime.interviewAudit();
            assertEquals("COMPLETED", audit.invites().get(0).status());
            assertEquals(0L, audit.budgets().stream()
                    .filter(budget -> budget.organizationPartyId().equals("wire"))
                    .findFirst().orElseThrow().reservedMillis());
            assertEquals(1, audit.sessions().size());
            assertEquals(scheduledEnd, audit.sessions().get(0).endedAtMillis(),
                    "正常结束必须严格截在预约终点");
            var sessionWork = audit.works().stream()
                    .filter(work -> work.kind().equals(MediaRun.KIND_INTERVIEW_SESSION))
                    .findFirst().orElseThrow();
            assertEquals(120_000L, sessionWork.chargedMillis());
        }
    }

    @Test
    void startGateFailsWhenJournalistRevoked(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("revoke.journal"))
                .runtime()) {
            long base = prepareContact(runtime);
            long start = base + 300_000L;
            long inviteDue = base + 1_000L;
            runtime.openRoleSession("rita").inviteInterview("cmd-rv1", "task-rv1", inviteDue,
                    new WorldRoleSession.InterviewInviteDraft("req-rv1", "investor", "撤职",
                            start - 60_000L, start + 3_120_000L, 120_000L));
            runtime.advanceTo(inviteDue);
            String requestId = runtime.interviewView("investor").invites().get(0).requestId();
            long respondDue = inviteDue + 1_000L;
            runtime.openRoleSession("investor").respondInterview("cmd-rv2", "task-rv2", respondDue,
                    new WorldRoleSession.InterviewRespondDraft(requestId, true, start));
            runtime.advanceTo(respondDue);

            long revokeDue = respondDue + 1_000L;
            runtime.scheduleRevokeAppointment("cmd-rv3", "task-rv3", revokeDue, "MEDIA-0");
            runtime.advanceTo(revokeDue);
            runtime.advanceTo(start + 1L);

            var audit = runtime.interviewAudit();
            assertEquals("MISSED", audit.invites().get(0).status(),
                    "记者撤职后开始门必须失败，不能凭已同意预约伪造会话");
            assertTrue(audit.sessions().isEmpty());
        }
    }

    @Test
    void plainPersonCanRespondCancelAndEndWithoutAppointment(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("plain.journal"))
                .runtime()) {
            long base = prepareContact(runtime);
            long start = base + 300_000L;
            long inviteDue = base + 1_000L;
            runtime.openRoleSession("rita").inviteInterview("cmd-p1", "task-p1", inviteDue,
                    new WorldRoleSession.InterviewInviteDraft("req-p1", "investor", "普通受访者",
                            start - 60_000L, start + 3_120_000L, 120_000L));
            runtime.advanceTo(inviteDue);
            String requestId = runtime.interviewView("investor").invites().get(0).requestId();

            long cancelDue = inviteDue + 500L;
            runtime.openRoleSession("investor").cancelInterview("cmd-p2", "task-p2", cancelDue,
                    new WorldRoleSession.InterviewRequestDraft(requestId));
            runtime.advanceTo(cancelDue);
            assertEquals("CANCELED", runtime.interviewAudit().invites().get(0).status());
            assertFalse(runtime.interviewAudit().works().stream()
                    .anyMatch(work -> work.personPartyId().equals("investor")),
                    "受访者没有机构工时账，不得伪造组织计费");
        }
    }

    @Test
    void reopeningJournalRebuildsSameInterviewState(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("reopen.journal");
        String before;
        long base;
        try (WorldRuntime runtime = WorldWebScenario.openInterview(journal).runtime()) {
            base = prepareContact(runtime);
            long inviteDue = base + 1_000L;
            runtime.openRoleSession("rita").inviteInterview("cmd-r1", "task-r1", inviteDue,
                    new WorldRoleSession.InterviewInviteDraft("req-r1", "investor", "恢复",
                            base + 240_000L, base + 3_120_000L, 120_000L));
            runtime.advanceTo(inviteDue);
            before = runtime.interviewRun().toString();
        }
        try (WorldRuntime reopened = WorldWebScenario.openInterview(journal).runtime()) {
            assertEquals(base + 1_000L, reopened.snapshot().timeMillis());
            assertEquals(before, reopened.interviewRun().toString(),
                    "重开必须逐字段重建同一采访状态");
            assertEquals("PROPOSED", reopened.interviewAudit().invites().get(0).status());
        }
    }

    /** 会话进行中，参与人再排媒体动作必须被真实门拒绝。 */
    @Test
    void activeSessionBlocksMediaWork(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("focus-media.journal"))
                .runtime()) {
            long start = activeSession(runtime, "focus-media");
            long now = runtime.snapshot().timeMillis();
            long due = now + 1_000L;
            IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> runtime.openRoleSession("rita")
                            .proposeStory("cmd-focus-media", "task-focus-media", due,
                                    new WorldRoleSession.MediaProposeDraft("story-focus", "WIRE",
                                            "会话期间不得写稿")));
            assertTrue(failure.getMessage().contains("采访会话"),
                    "拒绝原因必须指向正在进行的采访会话，实际：" + failure.getMessage());
            assertTrue(now >= start);
        }
    }

    /** 会话进行中，参与人再开始阅读也必须被真实门拒绝。 */
    @Test
    void activeSessionBlocksReading(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("focus-read.journal"))
                .runtime()) {
            activeSession(runtime, "focus-read");
            long now = runtime.snapshot().timeMillis();
            long due = now + 1_000L;
            IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> runtime.openRoleSession("rita")
                            .startReading("cmd-focus-read", "task-focus-read", due,
                                    WorldWebScenario.INTERVIEW_CONTACT_DOC, 1L));
            assertTrue(failure.getMessage().contains("采访会话"),
                    "拒绝原因必须指向正在进行的采访会话，实际：" + failure.getMessage());
        }
    }

    /** 走到 ACTIVE 的公共前置：发布/阅读联系说明、邀请、接受、到点开始。 */
    private static long activeSession(WorldRuntime runtime, String prefix) throws Exception {
        long base = prepareContact(runtime);
        long start = base + 300_000L;
        long inviteDue = base + 1_000L;
        runtime.openRoleSession("rita").inviteInterview("cmd-i-" + prefix, "task-i-" + prefix,
                inviteDue, new WorldRoleSession.InterviewInviteDraft("req-" + prefix, "investor",
                        "会话占用", start - 60_000L, start + 3_600_000L, 120_000L));
        runtime.advanceTo(inviteDue);
        String requestId = runtime.interviewView("investor").invites().get(0).requestId();
        long respondDue = inviteDue + 1_000L;
        runtime.openRoleSession("investor").respondInterview("cmd-r-" + prefix, "task-r-" + prefix,
                respondDue, new WorldRoleSession.InterviewRespondDraft(requestId, true, start));
        runtime.advanceTo(respondDue);
        runtime.advanceTo(start);
        assertEquals("ACTIVE", runtime.interviewAudit().invites().get(0).status());
        return start;
    }

    /** 采访工具只在 schema14 出现；普通受访者可用回应/取消/结束，但不能发起邀请。 */
    @Test
    void interviewToolsOnlyExistInSchema14(@TempDir Path dir) throws Exception {
        try (WorldRuntime interview = WorldWebScenario.openInterview(dir.resolve("tools14.journal"))
                .runtime()) {
            var journalist = interview.openRoleSession("rita").view().tools();
            assertTrue(journalist.contains(RoleTradingView.TOOL_INTERVIEW_INVITE));
            assertTrue(journalist.contains(RoleTradingView.TOOL_INTERVIEW_RESPOND));
            var plain = interview.openRoleSession("investor").view().tools();
            assertTrue(plain.contains(RoleTradingView.TOOL_INTERVIEW_RESPOND));
            assertTrue(plain.contains(RoleTradingView.TOOL_INTERVIEW_CANCEL));
            assertTrue(plain.contains(RoleTradingView.TOOL_INTERVIEW_END));
            assertFalse(plain.contains(RoleTradingView.TOOL_INTERVIEW_INVITE),
                    "普通受访者不得拿到发起邀请的工具");
        }
        try (WorldRuntime media = WorldWebScenario.openMedia(dir.resolve("tools13.journal"))
                .runtime()) {
            assertFalse(media.openRoleSession("rita").view().tools()
                    .contains(RoleTradingView.TOOL_INTERVIEW_INVITE),
                    "schema13 档案不得出现采访工具");
        }
    }
}
