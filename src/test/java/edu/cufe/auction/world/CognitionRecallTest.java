package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.InformationModel.ReadObservation;
import edu.cufe.auction.world.InformationModel.Reading;
import edu.cufe.auction.world.InformationModel.State;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;
import edu.cufe.auction.world.RoleAccess.Role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4f 合同测试：只回忆真实 {@link InformationModel} 已完成阅读产生的经历。
 *
 * <p>所有 State 都由真实 publish/startRead/completeRead/cancelRead 生成，不构造平行玩具状态。</p>
 */
class CognitionRecallTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String ORG = "ORG-1";
    private static final String PUBLISHER = "alice";
    private static final long HALF_LIFE = 1_000L;
    private static final long END = 30L * 24 * 60 * 60 * 1000;
    private static final String SOURCE = "公开来源";

    // ---------------------------------------------------------------- 真实经历过滤

    @Test
    @DisplayName("只含本人已完成且不晚于 now 的经历：未完成/取消/他人/未来均不计数")
    void onlyOwnCompletedExperiencesWithinNow() {
        State state = base();
        state = publish(state, "D-DONE", "已完成", "正文", 0L, 100L);
        state = publish(state, "D-OTHER", "他人", "正文", 0L, 100L);
        state = publish(state, "D-CANCEL", "取消", "正文", 0L, 100L);
        state = publish(state, "D-OPEN", "未完成", "正文", 0L, 100L);

        state = read(state, ALICE, "D-DONE", 0L);
        state = read(state, BOB, "D-OTHER", 0L);

        InformationModel.Outcome cancel = InformationModel.startRead(state, access(), ALICE,
                "D-CANCEL", 1L, 200L);
        assertEquals(InformationModel.Status.STARTED, cancel.status());
        Reading cancelReading = readingOf(cancel.state(), ALICE);
        state = InformationModel.cancelRead(cancel.state(), ALICE, cancelReading.readId(), 250L)
                .state();

        InformationModel.Outcome open = InformationModel.startRead(state, access(), ALICE,
                "D-OPEN", 1L, 300L);
        assertEquals(InformationModel.Status.STARTED, open.status());
        state = open.state();

        CognitionRecall.Result result = CognitionRecall.recall(state, ALICE, 1_000L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(1, result.selected().size());
        assertEquals("D-DONE", result.selected().get(0).observation().docId());
        assertEquals(1, result.eligibleCount());
        assertEquals(0, result.forgottenCount());
        assertFalse(result.budgetTruncated());

        CognitionRecall.Result bob = CognitionRecall.recall(state, BOB, 1_000L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(1, bob.selected().size());
        assertEquals("D-OTHER", bob.selected().get(0).observation().docId());
    }

    @Test
    @DisplayName("未来完成不影响本人可见计数、截断与排序")
    void futureCompletionDoesNotAffectCounts() {
        State state = base();
        state = publish(state, "D1", "未来", "正文", 0L, 100L);
        state = read(state, ALICE, "D1", 0L);

        CognitionRecall.Result early = CognitionRecall.recall(state, ALICE, 50L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertTrue(early.selected().isEmpty());
        assertEquals(0, early.eligibleCount());
        assertEquals(0, early.forgottenCount());
        assertFalse(early.budgetTruncated());

        CognitionRecall.Result due = CognitionRecall.recall(state, ALICE, 100L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(1, due.eligibleCount());
        assertEquals(1_000, due.selected().get(0).score());
    }

    // ---------------------------------------------------------------- 半衰期

    @Test
    @DisplayName("半衰期按完整区间整除 2，到 0 停止；边界与极值不用浮点")
    void halfLifeAndExtremes() {
        State state = base();
        state = publish(state, "D1", "半衰期", "正文", 0L, 1L);
        state = read(state, ALICE, "D1", 0L); // completedAt = 1
        long completed = 1L;

        assertEquals(1_000, scoreAt(state, completed));
        assertEquals(1_000, scoreAt(state, completed + HALF_LIFE - 1));
        assertEquals(500, scoreAt(state, completed + HALF_LIFE));
        assertEquals(250, scoreAt(state, completed + 2 * HALF_LIFE));
        assertEquals(1, scoreAt(state, completed + 9 * HALF_LIFE));

        CognitionRecall.Result zero = CognitionRecall.recall(state, ALICE,
                completed + 10 * HALF_LIFE, policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertTrue(zero.selected().isEmpty());
        assertEquals(0, zero.eligibleCount());
        assertEquals(1, zero.forgottenCount());

        CognitionRecall.Result huge = CognitionRecall.recall(state, ALICE, Long.MAX_VALUE,
                policy(Long.MAX_VALUE, 1, 10, 1_000_000L));
        assertEquals(1_000, huge.selected().get(0).score(), "elapsed < halfLife 不得溢出");

        CognitionRecall.Result tiny = CognitionRecall.recall(state, ALICE, Long.MAX_VALUE,
                policy(1L, 1, 10, 1_000_000L));
        assertEquals(0, tiny.eligibleCount());
        assertEquals(1, tiny.forgottenCount());
    }

    @Test
    @DisplayName("阈值先于预算：被阈值遗忘的条目不占 item/正文预算")
    void thresholdFiltersBeforeBudget() {
        State state = base();
        state = publish(state, "D-OLD", "旧", "正文", 0L, HALF_LIFE);
        state = publish(state, "D-NEW", "新", "正文", 0L, HALF_LIFE);
        state = read(state, ALICE, "D-OLD", 0L); // due = HALF_LIFE
        state = read(state, ALICE, "D-NEW", HALF_LIFE); // due = 2*HALF_LIFE
        long now = 2 * HALF_LIFE;

        CognitionRecall.Result strict = CognitionRecall.recall(state, ALICE, now,
                policy(HALF_LIFE, 600, 10, 1_000_000L));
        assertEquals(1, strict.eligibleCount());
        assertEquals(1, strict.forgottenCount());
        assertEquals(1, strict.selected().size());
        assertEquals("D-NEW", strict.selected().get(0).observation().docId());
        assertFalse(strict.budgetTruncated());

        CognitionRecall.Result loose = CognitionRecall.recall(state, ALICE, now,
                policy(HALF_LIFE, 500, 10, 1_000_000L));
        assertEquals(2, loose.eligibleCount());
        assertEquals(0, loose.forgottenCount());
    }

    @Test
    @DisplayName("排序稳定：分数降序，再 completedAt 降序；分数主导时间")
    void stableOrdering() {
        State state = base();
        state = publish(state, "D-A", "A", "正文", 0L, 1L);
        state = publish(state, "D-B", "B", "正文", 0L, 499L);
        state = publish(state, "D-C", "C", "正文", 0L, 1L);
        state = read(state, ALICE, "D-A", 0L); // due 1
        state = read(state, ALICE, "D-B", 1L); // due 500
        state = read(state, ALICE, "D-C", 500L); // due 501

        CognitionRecall.Result result = CognitionRecall.recall(state, ALICE, 1_000L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(List.of("D-C", "D-B", "D-A"), docIds(result));
        assertTrue(result.selected().stream().allMatch(item -> item.score() == 1_000));

        CognitionRecall.Result later = CognitionRecall.recall(state, ALICE, 1_001L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(List.of("D-C", "D-B", "D-A"), docIds(later));
        assertEquals(500, later.selected().get(2).score(), "D-A 满一个半衰期后降为 500");
    }

    // ---------------------------------------------------------------- 预算

    @Test
    @DisplayName("正文预算按 UTF-16 字符整条装入；超预算跳过并可继续选更短条目")
    void contentBudgetSkipsWholeItems() {
        State state = base();
        // 成本 = title + body + source：C=18, B=10, A=14；完成时刻 C=1、B=2、A=3；
        // 同分时按 completedAt 降序得到 A、B、C。
        state = publish(state, "C", "CCCC", "CCCCCCCCCC", 0L, 1L);
        state = publish(state, "B", "BBBB", "BB", 0L, 1L);
        state = publish(state, "A", "AAAA", "AAAAAA", 0L, 1L);
        state = read(state, ALICE, "C", 0L); // due 1
        state = read(state, ALICE, "B", 1L); // due 2
        state = read(state, ALICE, "A", 2L); // due 3
        long now = 3L;

        assertEquals(18, cost(state, "C"));
        assertEquals(10, cost(state, "B"));
        assertEquals(14, cost(state, "A"));

        CognitionRecall.Result tight = CognitionRecall.recall(state, ALICE, now,
                policy(HALF_LIFE, 1, 10, 10L));
        assertEquals(List.of("B"), docIds(tight));
        assertTrue(tight.budgetTruncated());
        assertEquals(10, tight.contentChars());
        assertEquals(3, tight.eligibleCount());

        CognitionRecall.Result exact = CognitionRecall.recall(state, ALICE, now,
                policy(HALF_LIFE, 1, 10, 28L));
        assertEquals(List.of("A", "B"), docIds(exact));
        assertTrue(exact.budgetTruncated());
        assertEquals(24, exact.contentChars());

        CognitionRecall.Result all = CognitionRecall.recall(state, ALICE, now,
                policy(HALF_LIFE, 1, 10, 42L));
        assertEquals(List.of("A", "B", "C"), docIds(all));
        assertFalse(all.budgetTruncated());
        assertEquals(42, all.contentChars());

        CognitionRecall.Result items = CognitionRecall.recall(state, ALICE, now,
                policy(HALF_LIFE, 1, 2, 100L));
        assertEquals(List.of("A", "B"), docIds(items));
        assertTrue(items.budgetTruncated());
        assertEquals(3, items.eligibleCount());
    }

    // ---------------------------------------------------------------- 同源/重读

    @Test
    @DisplayName("不按字符串猜同源：内容相同的两篇都保留；重读同一版本不凭空多出经历")
    void noStringDedupeOrFabricatedMemory() {
        State state = base();
        state = publish(state, "D1", "同名", "同文", 0L, 1L);
        state = publish(state, "D2", "同名", "同文", 0L, 1L);
        state = read(state, ALICE, "D1", 0L); // due 1
        state = read(state, ALICE, "D2", 1L); // due 2

        CognitionRecall.Result two = CognitionRecall.recall(state, ALICE, 2L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(2, two.selected().size(), "相同正文不得被当成同一来源合并");
        assertEquals("D1", two.selected().get(1).observation().docId());

        int observationsBefore = state.observations().size();
        state = read(state, ALICE, "D1", 2L); // 重读同版本
        assertEquals(observationsBefore, state.observations().size(),
                "InformationModel 已按 person/doc/version 去重经历");
        assertEquals(2, state.closed().stream()
                .filter(closed -> closed.readId() != null && closed.docId().equals("D1"))
                .count(), "重读仍是新的阅读终态，但不新增经历");

        CognitionRecall.Result again = CognitionRecall.recall(state, ALICE, 3L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(2, again.selected().size());
    }

    // ---------------------------------------------------------------- 不可变/权限

    @Test
    @DisplayName("离职后已读经历仍可回忆；输入 State 与原始审计不被修改")
    void leftRoleRetainsExperienceAndStateUnchanged() {
        State state = base();
        state = publish(state, "D1", "留任与离职", "正文", 0L, 1L);
        state = read(state, ALICE, "D1", 0L);
        List<ReadObservation> before = List.copyOf(state.observations());
        List<InformationModel.ClosedReading> closedBefore = List.copyOf(state.closed());

        // 新任职状态：不再有 alice 的任职；纯核回忆不读取 RoleAccess，因此已读经历保留。
        RoleAccess.State retired = RoleAccess.open(registry(),
                List.of(new OpeningAccount("ACC-A", ALICE, BigDecimal.ZERO, "开局")),
                List.of());
        assertTrue(retired.appointments().isEmpty());

        CognitionRecall.Result result = CognitionRecall.recall(state, ALICE, 1L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(1, result.selected().size());
        assertEquals(before, state.observations(), "输入状态不得被回忆修改");
        assertEquals(closedBefore, state.closed());
    }

    @Test
    @DisplayName("结果不可变，且只暴露 observation/score 与五项计数，不含来源谱系")
    void immutableResultWithoutHiddenSource() {
        State state = base();
        state = publish(state, "D1", "不可变", "正文", 0L, 1L);
        state = read(state, ALICE, "D1", 0L);

        CognitionRecall.Result result = CognitionRecall.recall(state, ALICE, 1L,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(List.of("observation", "score", "lastReadId", "lastReadAt"),
                componentNames(CognitionRecall.Item.class));
        assertEquals(List.of("selected", "eligibleCount", "forgottenCount",
                        "budgetTruncated", "contentChars"),
                componentNames(CognitionRecall.Result.class));
        assertEquals(List.of("halfLifeMillis", "thresholdPermille", "maxItems",
                        "maxContentChars"),
                componentNames(CognitionRecall.Policy.class));
        assertThrows(UnsupportedOperationException.class,
                () -> result.selected().add(result.selected().get(0)));
        assertEquals(result.selected().get(0).observation(),
                state.observations().get(0), "必须保留原 ReadObservation");
    }

    // ---------------------------------------------------------------- 参数

    @Test
    @DisplayName("Policy 与调用参数有界校验，不提供默认现实值")
    void policyValidation() {
        assertThrows(IllegalArgumentException.class, () -> policy(0L, 1, 1, 1L));
        assertThrows(IllegalArgumentException.class, () -> policy(-1L, 1, 1, 1L));
        assertThrows(IllegalArgumentException.class, () -> policy(1L, 0, 1, 1L));
        assertThrows(IllegalArgumentException.class, () -> policy(1L, 1_001, 1, 1L));
        assertThrows(IllegalArgumentException.class, () -> policy(1L, 1, 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> policy(1L, 1, 101, 1L));
        assertThrows(IllegalArgumentException.class, () -> policy(1L, 1, 1, 0L));
        assertThrows(IllegalArgumentException.class, () -> policy(1L, 1, 1, 1_000_001L));

        State state = base();
        assertThrows(IllegalArgumentException.class,
                () -> CognitionRecall.recall(null, ALICE, 0L, policy(1L, 1, 1, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> CognitionRecall.recall(state, " ", 0L, policy(1L, 1, 1, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> CognitionRecall.recall(state, ALICE, -1L, policy(1L, 1, 1, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> CognitionRecall.recall(state, ALICE, 0L, null));
    }

    // ---------------------------------------------------------------- 重读

    @Test
    @DisplayName("遗忘后重读未完成仍忘；完成后恢复 1000，随后继续衰减")
    void reReadAfterForgettingRestoresRecall() {
        State state = base();
        state = publish(state, "D1", "重读", "正文", 0L, 100L);
        state = read(state, ALICE, "D1", 0L); // READ-1, completedAt=100
        List<ReadObservation> before = List.copyOf(state.observations());
        long firstCompleted = 100L;
        long half = 1_000L;

        CognitionRecall.Result forgotten = CognitionRecall.recall(state, ALICE,
                firstCompleted + 10 * half, policy(half, 1, 10, 1_000_000L));
        assertEquals(0, forgotten.eligibleCount());
        assertEquals(1, forgotten.forgottenCount());

        InformationModel.Outcome started = InformationModel.startRead(state, access(), ALICE,
                "D1", 1L, firstCompleted + 10 * half);
        assertEquals(InformationModel.Status.STARTED, started.status());
        state = started.state();
        long due = readingOf(state, ALICE).dueAt();

        CognitionRecall.Result during = CognitionRecall.recall(state, ALICE, due - 1,
                policy(half, 1, 10, 1_000_000L));
        assertEquals(0, during.eligibleCount(), "未完成的重读不刷新");
        assertEquals(1, during.forgottenCount());

        InformationModel.Outcome done = InformationModel.completeRead(state, access(), ALICE,
                readingOf(state, ALICE).readId(), due);
        assertEquals(InformationModel.Status.COMPLETED, done.status());
        state = done.state();
        assertEquals(before, state.observations(), "首次 Observation 必须原样保留");
        assertEquals(2, state.closed().size());

        CognitionRecall.Result restored = CognitionRecall.recall(state, ALICE, due,
                policy(half, 1, 10, 1_000_000L));
        assertEquals(1, restored.selected().size());
        assertEquals(1_000, restored.selected().get(0).score());
        assertEquals(due, restored.selected().get(0).lastReadAt());
        assertEquals("READ-2", restored.selected().get(0).lastReadId());
        assertEquals(before.get(0), restored.selected().get(0).observation());

        CognitionRecall.Result decayed = CognitionRecall.recall(state, ALICE, due + half,
                policy(half, 1, 10, 1_000_000L));
        assertEquals(500, decayed.selected().get(0).score());
    }

    @Test
    @DisplayName("未来重读不刷新早期 cutoff：只取 <= now 的最后 COMPLETED")
    void futureReReadDoesNotAffectEarlierCutoff() {
        State state = base();
        state = publish(state, "D1", "未来重读", "正文", 0L, 100L);
        state = read(state, ALICE, "D1", 0L); // READ-1, completedAt=100

        InformationModel.Outcome started = InformationModel.startRead(state, access(), ALICE,
                "D1", 1L, 1_000L);
        state = started.state();
        long due = readingOf(state, ALICE).dueAt();
        state = InformationModel.completeRead(state, access(), ALICE,
                readingOf(state, ALICE).readId(), due).state();

        CognitionRecall.Result early = CognitionRecall.recall(state, ALICE, 500L,
                policy(10_000L, 1, 10, 1_000_000L));
        assertEquals(1, early.selected().size());
        assertEquals(100L, early.selected().get(0).lastReadAt());
        assertEquals("READ-1", early.selected().get(0).lastReadId());

        CognitionRecall.Result after = CognitionRecall.recall(state, ALICE, due,
                policy(10_000L, 1, 10, 1_000_000L));
        assertEquals(due, after.selected().get(0).lastReadAt());
        assertEquals("READ-2", after.selected().get(0).lastReadId());
    }

    @Test
    @DisplayName("CANCELLED 与 DENIED 不刷新最近完成时间")
    void cancelAndDeniedDoNotRefresh() {
        State state = base();
        state = publishOrg(state, "D1", "组织", "正文", 0L, 100L);
        state = readWithAccess(state, ALICE, "D1", 0L, orgAccess()); // READ-1 100

        InformationModel.Outcome cancelled = InformationModel.startRead(state, orgAccess(),
                ALICE, "D1", 1L, 200L);
        state = cancelled.state();
        state = InformationModel.cancelRead(state, ALICE, readingOf(state, ALICE).readId(),
                250L).state();

        InformationModel.Outcome deniedStart = InformationModel.startRead(state, orgAccess(),
                ALICE, "D1", 1L, 400L);
        state = deniedStart.state();
        long due = readingOf(state, ALICE).dueAt();
        InformationModel.Outcome denied = InformationModel.completeRead(state, access(), ALICE,
                readingOf(state, ALICE).readId(), due);
        assertEquals(InformationModel.Status.DENIED, denied.status());
        state = denied.state();

        CognitionRecall.Result result = CognitionRecall.recall(state, ALICE, 1_000L,
                policy(10_000L, 1, 10, 1_000_000L));
        assertEquals(1, result.selected().size());
        assertEquals(100L, result.selected().get(0).lastReadAt());
        assertEquals("READ-1", result.selected().get(0).lastReadId());
        assertEquals(3, state.closed().size());
    }

    @Test
    @DisplayName("回忆本身不刷新 lastReadAt，也不修改 State")
    void recallDoesNotRefresh() {
        State state = base();
        state = publish(state, "D1", "不刷新", "正文", 0L, 100L);
        state = read(state, ALICE, "D1", 0L);
        List<ReadObservation> observations = List.copyOf(state.observations());
        List<InformationModel.ClosedReading> closed = List.copyOf(state.closed());

        CognitionRecall.Result first = CognitionRecall.recall(state, ALICE, 50_000L,
                policy(100_000L, 1, 10, 1_000_000L));
        CognitionRecall.Result second = CognitionRecall.recall(state, ALICE, 90_000L,
                policy(100_000L, 1, 10, 1_000_000L));
        assertEquals(100L, first.selected().get(0).lastReadAt());
        assertEquals(100L, second.selected().get(0).lastReadAt());
        assertEquals(observations, state.observations());
        assertEquals(closed, state.closed());
    }

    // ---------------------------------------------------------------- 夹具

    private static State base() {
        return InformationModel.open(registry(), List.of(ALICE, BOB));
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(new Party(ALICE, PartyKind.PERSON, "甲"),
                        new Party(BOB, PartyKind.PERSON, "乙"),
                        new Party(ORG, PartyKind.ORGANIZATION, "组织")),
                List.of(new PropertyOwner(ALICE, OwnerKind.PERSON, ALICE),
                        new PropertyOwner(BOB, OwnerKind.PERSON, BOB)),
                List.of(), List.of());
    }

    private static RoleAccess.State access() {
        return RoleAccess.open(registry(),
                List.of(new OpeningAccount("ACC-A", ALICE, BigDecimal.ZERO, "开局")),
                List.of());
    }

    private static State publish(State state, String docId, String title, String body,
                                 long publishedAt, long readMillis) {
        return InformationModel.publish(state, docId, PUBLISHER,
                InformationModel.Audience.publicAudience(),
                new InformationModel.VersionDraft(title, body, SOURCE, publishedAt, readMillis,
                        List.of()));
    }

    private static State read(State state, String person, String docId, long startAt) {
        InformationModel.Outcome started = InformationModel.startRead(state, access(), person,
                docId, 1L, startAt);
        assertEquals(InformationModel.Status.STARTED, started.status(),
                started.status().name());
        Reading reading = readingOf(started.state(), person);
        InformationModel.Outcome done = InformationModel.completeRead(started.state(), access(),
                person, reading.readId(), reading.dueAt());
        assertEquals(InformationModel.Status.COMPLETED, done.status(), done.status().name());
        return done.state();
    }

    private static RoleAccess.State orgAccess() {
        return RoleAccess.appoint(registry(), access(), "A-1", ALICE, ORG,
                Role.RESEARCHER, 0L, END, 0L);
    }

    private static State publishOrg(State state, String docId, String title, String body,
                                    long publishedAt, long readMillis) {
        return InformationModel.publish(state, docId, PUBLISHER,
                InformationModel.Audience.organization(ORG),
                new InformationModel.VersionDraft(title, body, SOURCE, publishedAt, readMillis,
                        List.of()));
    }

    private static State readWithAccess(State state, String person, String docId, long startAt,
                                        RoleAccess.State access) {
        InformationModel.Outcome started = InformationModel.startRead(state, access, person,
                docId, 1L, startAt);
        assertEquals(InformationModel.Status.STARTED, started.status(), started.status().name());
        Reading reading = readingOf(started.state(), person);
        InformationModel.Outcome done = InformationModel.completeRead(started.state(), access,
                person, reading.readId(), reading.dueAt());
        assertEquals(InformationModel.Status.COMPLETED, done.status(), done.status().name());
        return done.state();
    }

    private static Reading readingOf(State state, String person) {
        for (Reading reading : state.active()) {
            if (reading.personPartyId().equals(person)) {
                return reading;
            }
        }
        throw new AssertionError("找不到活动阅读：" + person);
    }

    private static long cost(State state, String docId) {
        for (ReadObservation observation : state.observations()) {
            if (observation.docId().equals(docId)) {
                return (long) observation.title().length() + observation.body().length()
                        + observation.publicSourceDescription().length();
            }
        }
        throw new AssertionError("找不到经历：" + docId);
    }

    private static int scoreAt(State state, long now) {
        CognitionRecall.Result result = CognitionRecall.recall(state, ALICE, now,
                policy(HALF_LIFE, 1, 10, 1_000_000L));
        assertEquals(1, result.selected().size(), "now=" + now);
        return result.selected().get(0).score();
    }

    private static CognitionRecall.Policy policy(long halfLife, int threshold, int maxItems,
                                                 long maxContentChars) {
        return new CognitionRecall.Policy(halfLife, threshold, maxItems, maxContentChars);
    }

    private static List<String> docIds(CognitionRecall.Result result) {
        List<String> ids = new ArrayList<>();
        for (CognitionRecall.Item item : result.selected()) {
            ids.add(item.observation().docId());
        }
        return ids;
    }

    private static List<String> componentNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (java.lang.reflect.RecordComponent part : type.getRecordComponents()) {
            names.add(part.getName());
        }
        return names;
    }
}
