package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.InterviewRun;
import edu.cufe.auction.world.RoleEvidenceDocuments;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.SocialMessageModel;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.TECH;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.TECH_DIRECTORS;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.TECH_MANAGER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核验客户资金的思考经营新档（{@code web-thinking-operating-v2}）的主席工作联系入口。
 *
 * <p>只验证真实规则：入口来自本公司当前真实章程，未真实读完前不可用；经理实读后能私信主席，
 * 主席收到并实际读完后能按原有回信入口回复；陌生人、未读与被屏蔽都不可达；严格重开保持同一状态。
 * 不调用模型、不发送外部信息。</p>
 */
class FundedChairContactTest {

    /** 冻结的主席工作联系渠道名（规格，不由生产代码反向读取）。 */
    private static final String CHANNEL = "董事会工作联系";
    /** 角色依据版本的冻结阅读耗时。 */
    private static final long ROLE_READ = 10_000L;
    private static final String TECH_CHAIR = TECH_DIRECTORS.get(0);

    @TempDir
    Path dir;

    private WorldRuntime open(Path journal) throws Exception {
        return ComposedIndustryScenario.openFundedThinkingOperating(journal);
    }

    /** 每家有真实章程的发行公司都必须有一条主席入口；无章程公司不得造席位。 */
    @Test
    void fundedOpeningRegistersACharterChairEntryForEveryRealBoard() throws Exception {
        try (WorldRuntime world = open(dir.resolve("opening.journal"))) {
            assertEquals(RunManifest.FUNDED_THINKING_RUN_ID, world.profile().manifest().runId());
            Set<String> issuers = new TreeSet<>();
            world.openingRegistry().orElseThrow().securities()
                    .forEach(security -> issuers.add(security.issuerOrganizationId()));
            int boards = 0;
            for (String company : issuers) {
                var board = world.boardOfCompany(company);
                InterviewRun.ContactEntry entry = contactFor(world, company);
                if (board.isPresent()) {
                    boards++;
                    String chair = chairOf(board.orElseThrow().board().charter());
                    assertNotNull(entry, "有真实章程的发行公司必须有主席工作联系入口：" + company);
                    assertEquals(chair, entry.targetPartyId(), "入口必须指向当前章程主席：" + company);
                    assertEquals(CHANNEL, entry.channel());
                    assertEquals(RoleEvidenceDocuments.documentId(company), entry.docId());
                    assertEquals(1L, entry.version());
                } else {
                    assertNull(entry, "没有真实章程的发行公司不得造主席席位：" + company);
                }
            }
            assertTrue(boards >= 10, "覆盖应包含工程与全部真实治理公司：" + boards);
            assertTrue(world.boardOfCompany(WorldWebScenario.ENGINE_A).isPresent(),
                    "工程公司 eng-a 有真实章程");
            assertNotNull(contactFor(world, WorldWebScenario.ENGINE_A), "工程公司主席入口必须登记");
            assertNull(contactFor(world, "cufe"), "cufe 在本档没有真实章程，不得造席位");
        }
    }

    /** 经理实读→私信主席→主席读到→回信；未读/陌生人/屏蔽不可达；严格重开。 */
    @Test
    void managerReadsCharterThenReachesChairAndChairCanReply() throws Exception {
        Path journal = dir.resolve("flow.journal");
        WorldRuntime.Snapshot beforeReopen;
        String messageId;
        try (WorldRuntime world = open(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, TECH, TECH_DIRECTORS, TECH_MANAGER,
                    "contact-flow", List.of(0L));
            String doc = RoleEvidenceDocuments.documentId(TECH);

            // 未读之前入口不可用：联系人列表没有它，直接写信也被拒绝。
            assertNull(contactOrNull(world.socialView(TECH_MANAGER).contacts(), doc),
                    "真实读完之前主席入口不可用");
            assertEquals("UNREACHABLE", attemptSend(world, TECH_MANAGER, "premature",
                    TECH_CHAIR, CHANNEL), "未读前不得可达");
            assertEquals("UNREACHABLE", attemptSend(world, TECH_MANAGER, "stranger",
                    ComposedManufacturingThreeScenario.STRUCT_DIRECTORS.get(0), CHANNEL),
                    "没有入口的已登记陌生人不可达");
            assertEquals("UNREACHABLE", attemptSend(world, TECH_MANAGER, "unknown",
                    "unregistered-stranger", CHANNEL), "未登记人物不可达");

            // 经理真实读完本公司角色依据。
            read(world, TECH_MANAGER, doc, 1L);
            InterviewRun.ContactEntry entry = contactOrNull(
                    world.socialView(TECH_MANAGER).contacts(), doc);
            assertNotNull(entry, "读完本公司材料后必须出现主席入口");
            assertEquals(TECH_CHAIR, entry.targetPartyId());
            assertEquals(CHANNEL, entry.channel());

            // 正文与登记必须来自同一章程。
            BoardModel.Charter charter = world.boardOfCompany(TECH).orElseThrow()
                    .board().charter();
            JsonNode body = observationBody(world, TECH_MANAGER, doc);
            JsonNode contact = body.path("charterChairContact");
            assertEquals(charter.chairSeatId(), contact.path("seatId").asText());
            assertEquals(entry.targetPartyId(), contact.path("personPartyId").asText());
            assertEquals(entry.channel(), contact.path("channel").asText());
            assertEquals(entry.docId(), contact.path("docId").asText());
            assertEquals(entry.version(), contact.path("version").asLong());

            // 写一封真实私信并在真实投递时点送达。
            long sendAt = world.snapshot().timeMillis();
            long sendDue = sendAt + WorldWebScenario.SOCIAL_SEND_MILLIS;
            assertEquals("SCHEDULED:chair-send-task", send(world, TECH_MANAGER, "chair-send",
                    TECH_CHAIR, entry.channel(), sendDue));
            world.advanceTo(sendDue);
            SocialMessageModel.Message message = message(world, TECH_MANAGER, TECH_CHAIR);
            assertEquals(SocialMessageModel.MessageStatus.QUEUED, message.status());
            world.advanceTo(message.dueAt());
            message = message(world, TECH_MANAGER, TECH_CHAIR);
            assertEquals(SocialMessageModel.MessageStatus.DELIVERED, message.status());
            messageId = message.messageId();
            assertTrue(world.socialView(TECH_CHAIR).inbox().stream()
                    .anyMatch(envelope -> envelope.messageId().equals(messageId)),
                    "主席必须真实收到信封");

            // 主席真实读完来信后，按原有规则获得回信入口并回信。
            read(world, TECH_CHAIR, message.docId(), 1L);
            assertTrue(world.socialView(TECH_CHAIR).read(messageId), "主席必须真实读完来信");
            InterviewRun.ContactEntry replyEntry = contactForTarget(
                    world.socialView(TECH_CHAIR).contacts(), TECH_MANAGER);
            assertNotNull(replyEntry, "主席读到来信后必须有现实回信入口");
            long replyAt = world.snapshot().timeMillis();
            long replyDue = replyAt + WorldWebScenario.SOCIAL_SEND_MILLIS;
            assertEquals("SCHEDULED:chair-reply-task", send(world, TECH_CHAIR, "chair-reply",
                    TECH_MANAGER, replyEntry.channel(), replyDue));
            world.advanceTo(replyDue);
            SocialMessageModel.Message reply = message(world, TECH_CHAIR, TECH_MANAGER);
            world.advanceTo(reply.dueAt());
            reply = message(world, TECH_CHAIR, TECH_MANAGER);
            assertEquals(SocialMessageModel.MessageStatus.DELIVERED, reply.status());
            assertEquals(TECH_MANAGER, reply.to());
            beforeReopen = world.snapshot();
        }
        // 严格重开：状态逐字一致、入口与消息仍在，且重开不写日志。
        byte[] bytes = Files.readAllBytes(journal);
        try (WorldRuntime world = open(journal)) {
            assertEquals(beforeReopen, world.snapshot());
            assertNotNull(contactFor(world, TECH), "重开后主席入口仍由同一章程登记");
            assertNotNull(world.snapshot().social().orElseThrow().state().messages().stream()
                    .filter(item -> item.messageId().equals(messageId)).findFirst().orElse(null));
        }
        assertArrayEquals(bytes, Files.readAllBytes(journal));
    }

    /** 屏蔽在到期后才生效；此后即使读过材料也不可达，重开仍保持屏蔽。 */
    @Test
    void blockedChairStaysUnreachableAfterReadingAndReopen() throws Exception {
        Path journal = dir.resolve("blocked.journal");
        try (WorldRuntime world = open(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, TECH, TECH_DIRECTORS, TECH_MANAGER,
                    "contact-block", List.of(0L));
            String doc = RoleEvidenceDocuments.documentId(TECH);
            read(world, TECH_MANAGER, doc, 1L);
            assertNotNull(contactOrNull(world.socialView(TECH_MANAGER).contacts(), doc));

            long blockDue = world.snapshot().timeMillis() + WorldWebScenario.SOCIAL_BLOCK_MILLIS;
            assertEquals("SCHEDULED:block-task", world.openRoleSession(TECH_CHAIR)
                    .blockPerson("block", "block-task", blockDue, TECH_MANAGER));
            world.advanceTo(blockDue);
            assertEquals("UNREACHABLE", attemptSend(world, TECH_MANAGER, "after-block",
                    TECH_CHAIR, CHANNEL), "屏蔽生效后不得可达");
        }
        try (WorldRuntime world = open(journal)) {
            assertEquals("UNREACHABLE", attemptSend(world, TECH_MANAGER, "after-reopen",
                    TECH_CHAIR, CHANNEL), "重开后屏蔽仍有效");
        }
    }

    /** 旧思考经营档（v1）不获得新渠道，联系入口与冻结来源逐字一致。 */
    @Test
    void olderThinkingRunKeepsItsOriginalContactChannels() throws Exception {
        try (WorldRuntime world = ComposedIndustryScenario.openThinkingInformedOperating(
                dir.resolve("old.journal"))) {
            assertEquals("web-thinking-operating-v1", world.profile().manifest().runId());
            List<InterviewRun.ContactEntry> expected = new ArrayList<>(
                    WorldWebScenario.interviewConfig().contacts());
            for (String person : WorldWebScenario.SOCIAL_PERSONS) {
                expected.add(new InterviewRun.ContactEntry("私信", person,
                        "公开联系说明：可一对一私人消息", WorldWebScenario.SOCIAL_CONTACT_DOC,
                        1L));
            }
            expected.sort(java.util.Comparator
                    .comparing(InterviewRun.ContactEntry::targetPartyId)
                    .thenComparing(InterviewRun.ContactEntry::channel)
                    .thenComparing(InterviewRun.ContactEntry::docId)
                    .thenComparingLong(InterviewRun.ContactEntry::version));
            assertEquals(expected, world.socialRun().contacts());
            for (InterviewRun.ContactEntry entry : world.socialRun().contacts()) {
                assertFalse(CHANNEL.equals(entry.channel()), "旧档不得出现新工作联系渠道");
            }
            assertNull(contactFor(world, TECH), "旧档不得登记章程主席入口");
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static InterviewRun.ContactEntry contactFor(WorldRuntime world, String company)
            throws Exception {
        return contactOrNull(world.socialRun().contacts(),
                RoleEvidenceDocuments.documentId(company));
    }

    private static InterviewRun.ContactEntry contactOrNull(
            List<InterviewRun.ContactEntry> contacts, String docId) {
        for (InterviewRun.ContactEntry entry : contacts) {
            if (entry.docId().equals(docId)) {
                return entry;
            }
        }
        return null;
    }

    private static InterviewRun.ContactEntry contactForTarget(
            List<InterviewRun.ContactEntry> contacts, String person) {
        for (InterviewRun.ContactEntry entry : contacts) {
            if (entry.targetPartyId().equals(person)) {
                return entry;
            }
        }
        return null;
    }

    private static String chairOf(BoardModel.Charter charter) {
        for (BoardModel.Seat seat : charter.seats()) {
            if (seat.seatId().equals(charter.chairSeatId())) {
                return seat.partyId();
            }
        }
        throw new AssertionError("章程主席席位不在席位表中：" + charter.chairSeatId());
    }

    private static void read(WorldRuntime world, String person, String docId, long version)
            throws Exception {
        long now = world.snapshot().timeMillis();
        String tag = person + "-" + docId + "-" + now;
        world.scheduleStartReading("read-" + tag, "read-task-" + tag, now + 1L, person,
                docId, version);
        world.advanceTo(now + 1L + ROLE_READ);
        assertNotNull(observation(world, person, docId), "没有真实读完资料：" + docId);
    }

    private static InformationModel.ReadObservation observation(WorldRuntime world, String person,
                                                                 String docId) {
        InformationModel.State info = world.snapshot().information().orElseThrow().information();
        for (InformationModel.ReadObservation item : InformationModel.observations(info, person)) {
            if (item.docId().equals(docId)) {
                return item;
            }
        }
        return null;
    }

    private static String attemptSend(WorldRuntime world, String person, String command,
                                      String to, String channel) throws Exception {
        long now = world.snapshot().timeMillis();
        return send(world, person, command, to, channel,
                now + WorldWebScenario.SOCIAL_SEND_MILLIS);
    }

    private static String send(WorldRuntime world, String person, String command, String to,
                               String channel, long dueMillis) throws Exception {
        return world.openRoleSession(person).sendMessage(command, command + "-task", dueMillis,
                new WorldRoleSession.SocialSendDraft(to, channel, "经营预算工作联系",
                        "未经核实的个人陈述：拟向董事会说明经营预算需求，请安排会议材料。"));
    }

    private static SocialMessageModel.Message message(WorldRuntime world, String from, String to) {
        List<SocialMessageModel.Message> found = new ArrayList<>();
        for (SocialMessageModel.Message item : world.snapshot().social().orElseThrow()
                .state().messages()) {
            if (item.from().equals(from) && item.to().equals(to)) {
                found.add(item);
            }
        }
        assertEquals(1, found.size(), "找不到唯一消息：" + from + " -> " + to);
        return found.get(0);
    }

    private static JsonNode observationBody(WorldRuntime world, String person, String docId)
            throws Exception {
        InformationModel.ReadObservation item = observation(world, person, docId);
        assertNotNull(item);
        return new ObjectMapper().readTree(item.body());
    }
}
