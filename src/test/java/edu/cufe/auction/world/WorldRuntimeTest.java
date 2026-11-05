package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.CashLedger.OpeningAccount;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1.3a：现金子集的可恢复运行。
 *
 * <p>覆盖：清单与开局绑定（含 seed / dataVersions）、待办跨重启、现金效果/后续回报/时钟/
 * 状态快照同批提交、真实线程中断导致的提交失败、半帧截断、幂等排程、同刻公平顺序、
 * 能力与过去时间拒绝、快照只读、单写入线程、暂停期拒绝、内部标识碰撞、回报时点溢出，
 * 以及用独立 WorldJournal 重建的篡改日志在恢复时必须被拒。</p>
 */
@DisplayName("WorldRuntime：现金子集的可恢复运行")
class WorldRuntimeTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-1";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 清单 / 恢复绑定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("运行清单或开局现金不一致时拒绝续跑（含 seed 与 dataVersions）")
    void manifestMismatchRejectsResume() throws IOException {
        Path file = tempDir.resolve("manifest.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
        }

        // 清单字段在写入任何记录之前就被拒绝，不会留下半条运行
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                "cash-world-v2", modules(), Map.of(), "none"));
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                "cash-world-v1", Map.of("cash", 2, "scheduler", 1), Map.of(), "none"));

        // 有效清单但 seed / dataVersions 与持久记录不同 → 真实续跑拒绝
        assertThrows(IOException.class, () -> new WorldRuntime(file,
                manifest(RUN, SEED + 1, Map.of()), openings()).close());
        assertThrows(IOException.class, () -> new WorldRuntime(file,
                manifest(RUN, SEED, Map.of("research-pack", "2026-11-05")), openings()).close());

        // 开局现金与运行标识不一致 → 拒绝
        assertThrows(IOException.class, () -> new WorldRuntime(file, manifest(RUN, SEED, Map.of()),
                List.of(new OpeningAccount("A", "alice", new BigDecimal("999.00"), "实验开局历史禀赋"),
                        new OpeningAccount("B", "bob", new BigDecimal("1.00"), "实验开局历史禀赋")))
                .close());
        assertThrows(IOException.class, () -> new WorldRuntime(file, manifest("run-2", SEED, Map.of()),
                openings()).close());
    }

    @Test
    @DisplayName("转账待办跨重启保留，重开不付款，到点只付一次")
    void pendingTransferSurvivesRestartWithoutExecution() throws IOException {
        Path file = tempDir.resolve("pending.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
        }
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            assertEquals(0L, runtime.snapshot().timeMillis());
            assertEquals(1, runtime.snapshot().pending().size());
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(0, cash(runtime, "B").signum());

            runtime.advanceTo(20);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("900.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("100.00")));
            assertTrue(runtime.snapshot().pending().isEmpty());
            assertEquals(1, runtime.snapshot().delivered().size());
        }
    }

    // ------------------------------------------------------------------
    // 同批提交
    // ------------------------------------------------------------------

    @Test
    @DisplayName("现金效果、完成标记、后续回报待办与状态快照落在同一提交位置")
    void cashEffectAndFollowUpShareCommit() throws IOException {
        Path file = tempDir.resolve("batch.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
            runtime.advanceTo(20);
        }

        WorldCommit completion = null;
        long cashEvents = 0;
        try (WorldJournal journal = new WorldJournal(file, RUN)) {
            for (WorldCommit commit : journal.commits()) {
                if ("task:t1".equals(commit.commandId())) {
                    completion = commit;
                }
                cashEvents += commit.events().stream()
                        .filter(event -> "CASH_TRANSFERRED_V1".equals(event.type())).count();
            }
        }
        assertTrue(completion != null, "缺少 task:t1 完成批次");
        assertEquals(3L, completion.position(), "open=1、排程=2、完成=3");
        List<String> types = completion.events().stream().map(WorldEvent::type).toList();
        assertTrue(types.contains("CASH_TRANSFERRED_V1"), "同批应含现金效果：" + types);
        assertTrue(types.contains("TASK_SCHEDULED_V1"), "同批应含后续回报待办：" + types);
        assertTrue(types.contains("TASK_COMPLETED_V1"), "同批应含完成标记：" + types);
        assertTrue(types.contains("STATE_SNAPSHOT_V1"), "同批必须含状态快照：" + types);
        assertEquals(1L, cashEvents, "现金效果只应出现一次");
    }

    // ------------------------------------------------------------------
    // 幂等 / 公平顺序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同命令重发先查原回报：重启后与执行后都不再付款")
    void duplicateScheduleDoesNotPayTwiceAfterRestart() throws IOException {
        Path file = tempDir.resolve("duplicate.journal");
        String first;
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            first = runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B",
                    new BigDecimal("100.00"), 0);
        }
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            long positionBefore = runtime.snapshot().position();
            assertEquals(first, runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B",
                    new BigDecimal("100.00"), 0), "重发应返回原回报");
            assertEquals(positionBefore, runtime.snapshot().position(), "重发不得新增提交");
            assertEquals(1, runtime.snapshot().pending().size());
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleTransfer(
                    "c1", "t1", 12, "alice", "A", "B", new BigDecimal("100.00"), 0),
                    "同标识不同内容必须拒绝");

            runtime.advanceTo(20);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("900.00")));
            assertEquals(first, runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B",
                    new BigDecimal("100.00"), 0), "执行后重发仍返回原回报");
            runtime.advanceTo(40);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("900.00")), "不得二次付款");
        }
    }

    @Test
    @DisplayName("同种子下同刻任务顺序不随排程插入顺序改变")
    void sameSeedTaskOrderIgnoresScheduleInsertionOrder() throws IOException {
        List<String> firstOrder = deliverOrder(tempDir.resolve("order-a.journal"), true);
        List<String> secondOrder = deliverOrder(tempDir.resolve("order-b.journal"), false);
        assertEquals(firstOrder, secondOrder, "公平顺序必须由种子与任务标识决定");
    }

    private List<String> deliverOrder(Path file, boolean reverse) throws IOException {
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            List<String> ids = new ArrayList<>(List.of("t1", "t2", "t3"));
            if (reverse) {
                java.util.Collections.reverse(ids);
            }
            for (String id : ids) {
                runtime.scheduleTransfer("c-" + id, id, 10, "alice", "A", "B",
                        new BigDecimal("10.00"), 0);
            }
            runtime.advanceTo(30);
            return runtime.snapshot().delivered().stream().map(WorldRuntime.Notice::taskId).toList();
        }
    }

    // ------------------------------------------------------------------
    // 写入线程 / 暂停 / 提交失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("只有创建线程可以排程与推进，其它线程被拒绝且快照不变")
    void writerThreadIsEnforced() throws Exception {
        Path file = tempDir.resolve("writer.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            long position = runtime.snapshot().position();
            AtomicReference<Throwable> scheduleError = new AtomicReference<>();
            AtomicReference<Throwable> advanceError = new AtomicReference<>();

            Thread other = new Thread(() -> {
                try {
                    runtime.scheduleTransfer("cx", "tx", 10, "alice", "A", "B",
                            new BigDecimal("1.00"), 0);
                } catch (Throwable failure) {
                    scheduleError.set(failure);
                }
                try {
                    runtime.advanceTo(20);
                } catch (Throwable failure) {
                    advanceError.set(failure);
                }
            }, "not-the-writer");
            other.start();
            other.join(5000);

            assertInstanceOf(IllegalStateException.class, scheduleError.get(),
                    "非创建线程排程必须被拒绝");
            assertInstanceOf(IllegalStateException.class, advanceError.get(),
                    "非创建线程推进必须被拒绝");
            assertEquals(position, runtime.snapshot().position(), "被拒绝的调用不得改变快照");
            assertEquals(0L, runtime.snapshot().timeMillis());
            assertTrue(runtime.snapshot().pending().isEmpty());
        }
    }

    @Test
    @DisplayName("提交 I/O 失败：现金与待办不变、运行暂停、恢复后仅付一次；暂停期不再接受排程")
    void commitFailureLeavesCashAndPendingUnchanged() throws IOException {
        Path file = tempDir.resolve("failure.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
            long position = runtime.snapshot().position();

            try {
                Thread.currentThread().interrupt();
                assertThrows(IOException.class, () -> runtime.advanceTo(20),
                        "线程中断应使真实文件写入失败");
            } finally {
                Thread.interrupted();
            }

            assertTrue(runtime.isPaused(), "提交失败必须暂停运行");
            assertEquals(position, runtime.snapshot().position(), "失败批次不得发布");
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
            assertEquals(1, runtime.snapshot().pending().size(), "未确认的任务必须仍在待办");
            assertThrows(IllegalStateException.class, () -> runtime.scheduleTransfer(
                    "c2", "t2", 30, "alice", "A", "B", new BigDecimal("1.00"), 0),
                    "暂停期间不得接受新排程");
        }

        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            assertEquals(1, runtime.snapshot().pending().size());
            runtime.advanceTo(20);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("900.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("100.00")));
        }
    }

    // ------------------------------------------------------------------
    // 半帧截断
    // ------------------------------------------------------------------

    @Test
    @DisplayName("截断未确认完成帧的中间字节：旧资金与旧待办保留，重开仍执行一次")
    void truncatedCompletionKeepsOriginalPendingTask() throws IOException {
        Path file = tempDir.resolve("truncate.journal");
        long boundary;
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
            boundary = Files.size(file);
            runtime.advanceTo(20);
            assertTrue(Files.size(file) > boundary + 20, "完成帧必须有足够字节可截半");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(boundary + 20);
            channel.force(true);
        }
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")),
                    "未确认的完成帧不得生效");
            assertEquals(1, runtime.snapshot().pending().size(), "待办必须保留");
            runtime.advanceTo(20);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("900.00")));
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("100.00")));
        }
    }

    // ------------------------------------------------------------------
    // 时钟 / 回报时点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("时钟与回报跨重启保留，回报时点精确为完成+延迟（0 延迟按 +1）")
    void clockAndNoticeSurviveRestart() throws IOException {
        Path file = tempDir.resolve("clock.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
            runtime.scheduleTransfer("c2", "t2", 10, "alice", "A", "B", new BigDecimal("10.00"), 5);
            runtime.advanceTo(40);
            assertEquals(40L, runtime.snapshot().timeMillis());

            Map<String, Long> deliveredAt = new TreeMap<>();
            runtime.snapshot().delivered()
                    .forEach(notice -> deliveredAt.put(notice.taskId(), notice.deliveredAtMillis()));
            assertEquals(11L, deliveredAt.get("t1"), "0 延迟回报应落在完成 +1 毫秒");
            assertEquals(15L, deliveredAt.get("t2"), "5 毫秒延迟回报应落在完成 +5 毫秒");
            assertEquals(2, deliveredAt.size());
        }
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            assertEquals(40L, runtime.snapshot().timeMillis(), "时钟不得倒退");
            assertEquals(2, runtime.snapshot().delivered().size());
            assertTrue(runtime.snapshot().pending().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleTransfer(
                    "c3", "t3", 40, "alice", "A", "B", new BigDecimal("1.00"), 0),
                    "到期时间必须晚于当前时点");
        }
    }

    @Test
    @DisplayName("回报时点溢出在排程阶段拒绝，不写批、不改快照")
    void overflowInReceiptDueRejectedBeforeCommit() throws IOException {
        Path file = tempDir.resolve("overflow.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleTransfer(
                    "c1", "t1", Long.MAX_VALUE - 5, "alice", "A", "B",
                    new BigDecimal("1.00"), 10));
            assertEquals(position, runtime.snapshot().position(), "溢出组合不得提交");
            assertTrue(runtime.snapshot().pending().isEmpty());
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")));
        }
    }

    // ------------------------------------------------------------------
    // 内部标识碰撞
    // ------------------------------------------------------------------

    @Test
    @DisplayName("外部任务名与内部回报名冲突时两种到期顺序都能完整结束，合计 101 且各一回报")
    void internalNoticeIdCollisionBothOrders() throws IOException {
        assertCollisionRun(tempDir.resolve("collision-a.journal"), true);
        assertCollisionRun(tempDir.resolve("collision-b.journal"), false);
    }

    private void assertCollisionRun(Path file, boolean sourceFirst) throws IOException {
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            if (sourceFirst) {
                runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
                runtime.scheduleTransfer("c2", "notice:t1", 20, "alice", "A", "B", new BigDecimal("1.00"), 0);
            } else {
                runtime.scheduleTransfer("c2", "notice:t1", 10, "alice", "A", "B", new BigDecimal("1.00"), 0);
                runtime.scheduleTransfer("c1", "t1", 20, "alice", "A", "B", new BigDecimal("100.00"), 0);
            }
            runtime.advanceTo(40);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("899.00")), "合计应转出 101");
            assertEquals(0, cash(runtime, "B").compareTo(new BigDecimal("101.00")));
            assertEquals(2, runtime.snapshot().delivered().size(), "两笔转账各一封回报");
            assertEquals(List.of("notice:t1", "t1"),
                    runtime.snapshot().delivered().stream()
                            .map(WorldRuntime.Notice::taskId).sorted().toList(),
                    "回报必须指回各自的原任务");
            assertTrue(runtime.snapshot().pending().isEmpty());
        }
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.advanceTo(80);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("899.00")), "重启后不得重付");
            assertEquals(2, runtime.snapshot().delivered().size());
        }
    }

    @Test
    @DisplayName("连续三层内部标识碰撞也不会卡死")
    void internalNoticeIdThirdLevelCollision() throws IOException {
        Path file = tempDir.resolve("collision-c.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c0", "notice:notice:t1", 30, "alice", "A", "B",
                    new BigDecimal("1.00"), 0);
            runtime.scheduleTransfer("c1", "notice:t1", 20, "alice", "A", "B",
                    new BigDecimal("1.00"), 0);
            runtime.scheduleTransfer("c2", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
            runtime.advanceTo(60);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("898.00")), "三笔合计 102");
            assertEquals(3, runtime.snapshot().delivered().size());
            assertTrue(runtime.snapshot().pending().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // 能力 / 参数 / 快照
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未启用能力与过去时间在提交前拒绝")
    void unsupportedCapabilityAndPastTimeRejectedWithoutCommit() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                "cash-world-v1", Map.of("cash", 1, "bank", 1), Map.of(), "none"));
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                "cash-world-v1", modules(), Map.of(), "deepseek-v1"));
        assertThrows(IllegalArgumentException.class, () -> new PendingTask("x1", 10,
                WorldScheduler.Phase.COMPLETION, "BANK_LOAN_V1", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new PendingTask("x2", 10,
                WorldScheduler.Phase.DELIVERY, "CASH_TRANSFER_V1", "{}"),
                "转账任务必须落在 COMPLETION 阶段");

        Path file = tempDir.resolve("reject.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleTransfer(
                    "c1", "t1", 0, "alice", "A", "B", new BigDecimal("1.00"), 0));
            assertEquals(position, runtime.snapshot().position(), "被拒绝的排程不得提交");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleTransfer(
                    "c2", "t2", 5, "alice", "A", "B", new BigDecimal("-1.00"), 0));
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleTransfer(
                    "c3", "t3", 5, "bob", "A", "B", new BigDecimal("1.00"), 0),
                    "非财产主体不能动用该账户");
            assertEquals(position, runtime.snapshot().position());
        }
    }

    @Test
    @DisplayName("快照只读，运行日志只允许一个写入者")
    void snapshotIsImmutableAndLogHasSingleWriter() throws IOException {
        Path file = tempDir.resolve("immutable.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
            WorldRuntime.Snapshot snapshot = runtime.snapshot();
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.accounts().put("X", snapshot.accounts().get("A")));
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.pending().add(snapshot.pending().get(0)));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.usedTaskIds().add("zz"));
            assertThrows(IOException.class, () -> new WorldRuntime(file, manifest(RUN, SEED, Map.of()),
                    openings()));
        }
    }

    // ------------------------------------------------------------------
    // 篡改日志：恢复只校验、不执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("篡改开局快照余额（commandContent 不变）时恢复拒绝")
    void tamperedOpeningSnapshotRejected() throws IOException {
        Path source = tempDir.resolve("genesis-src.journal");
        try (WorldRuntime runtime = new WorldRuntime(source, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
        }
        Path target = tempDir.resolve("genesis-tampered.journal");
        rewriteWithSnapshot(source, target, 1, payload -> {
            Map<String, Object> state = readMap(payload);
            Map<String, Object> accounts = asMap(state.get("accounts"));
            asMap(accounts.get("A")).put("cash", new BigDecimal("999.00"));
            return writeJson(state);
        });
        assertThrows(IOException.class, () -> new WorldRuntime(target, manifest(RUN, SEED, Map.of()),
                openings()).close(), "开局快照必须与期望开局资产一致");
    }

    @Test
    @DisplayName("篡改快照阶段名或让待办早于快照时点时恢复拒绝")
    void tamperedPhaseAndPendingRejected() throws IOException {
        Path source = tempDir.resolve("state-src.journal");
        try (WorldRuntime runtime = new WorldRuntime(source, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
        }

        Path badPhase = tempDir.resolve("state-bad-phase.journal");
        rewriteWithSnapshot(source, badPhase, 2, payload -> {
            Map<String, Object> state = readMap(payload);
            state.put("lastPhase", "NOT_A_PHASE");
            return writeJson(state);
        });
        assertThrows(IOException.class, () -> new WorldRuntime(badPhase, manifest(RUN, SEED, Map.of()),
                openings()).close(), "非法阶段名必须拒绝");

        Path pendingTooEarly = tempDir.resolve("state-pending-early.journal");
        rewriteWithSnapshot(source, pendingTooEarly, 2, payload -> {
            Map<String, Object> state = readMap(payload);
            state.put("timeMillis", 20L);
            return writeJson(state);
        });
        assertThrows(IOException.class, () -> new WorldRuntime(pendingTooEarly,
                manifest(RUN, SEED, Map.of()), openings()).close(),
                "待办到期早于快照时点必须拒绝");
    }

    @Test
    void sameTimeCannotRestorePastPhasePendingTask() throws IOException {
        Path source = tempDir.resolve("phase-pending-source.journal");
        try (WorldRuntime runtime = new WorldRuntime(source, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
        }
        Path target = tempDir.resolve("phase-pending-target.journal");
        rewriteWithSnapshot(source, target, 2, payload -> {
            Map<String, Object> state = readMap(payload);
            state.put("timeMillis", 10L);
            state.put("lastPhase", WorldScheduler.Phase.OBSERVATION.name());
            return writeJson(state);
        });
        assertThrows(IOException.class, () -> new WorldRuntime(target, manifest(RUN, SEED, Map.of()), openings()).close(),
                "观察阶段结束后，不能恢复同刻较早完成阶段的待办");
    }

    @Test
    void sameTimePhaseCannotMoveBackwardsOnResume() throws IOException {
        Path file = tempDir.resolve("phase-rollback.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
            runtime.advanceTo(10);
        }
        try (WorldJournal journal = new WorldJournal(file, RUN)) {
            WorldCommit last = journal.commits().get(journal.commits().size() - 1);
            String payload = last.events().stream().filter(e -> "STATE_SNAPSHOT_V1".equals(e.type()))
                    .findFirst().orElseThrow().payload();
            Map<String, Object> state = readMap(payload);
            state.put("position", last.position() + 1);
            state.put("lastPhase", WorldScheduler.Phase.RULES.name());
            journal.commit("fixture-phase-rollback", "{}", "fixture", List.of(
                    new WorldEvent("state:" + (last.position() + 1), "STATE_SNAPSHOT_V1", writeJson(state))));
        }
        assertThrows(IOException.class, () -> new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings()).close(),
                "同刻后续快照不能从完成阶段退回规则阶段");
    }

    // ------------------------------------------------------------------
    // 开局登记（M2.2）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("带登记的运行绑定开局分配，重启不重发资产、不重复发行")
    void registryRunBindsOpeningAndRestoresWithoutReissue() throws IOException {
        Path file = tempDir.resolve("registry.journal");
        OpeningRegistry registry = registry();
        try (WorldRuntime runtime = new WorldRuntime(file, registryManifest(RUN, SEED),
                registryOpenings(), registry)) {
            assertEquals(1L, runtime.snapshot().position(), "开局只写一个批次");
            assertEquals(1000L, runtime.openingRegistry().orElseThrow()
                    .securities().get(0).issuedShares());
            assertEquals(3, runtime.openingRegistry().orElseThrow().allocations().size());
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")),
                    "登记股份不改变现金余额");
        }
        try (WorldRuntime reopened = new WorldRuntime(file, registryManifest(RUN, SEED),
                registryOpenings(), registry)) {
            assertEquals(1L, reopened.snapshot().position(), "重启不得新增开局批次");
            assertEquals(registry.allocations(), reopened.openingRegistry().orElseThrow().allocations());
            assertEquals(0, cash(reopened, "A").compareTo(new BigDecimal("1000.00")));
        }
    }

    @Test
    @DisplayName("有效登记内容变化拒绝续跑；新旧 profile 互开也拒绝")
    void registryMismatchAndCrossProfileRejected() throws IOException {
        Path file = tempDir.resolve("registry-mismatch.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, registryManifest(RUN, SEED),
                registryOpenings(), registry())) {
            assertEquals(1L, runtime.snapshot().position());
        }

        OpeningRegistry changed = new OpeningRegistry(registryParties(), registryOwners(),
                List.of(new OpeningRegistry.Security("ACME1", "acme", 1000, new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", "ACME1", 599,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("B", "ACME1", 301,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("EXT", "ACME1", 100,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT)));
        assertThrows(IOException.class, () -> new WorldRuntime(file, registryManifest(RUN, SEED),
                registryOpenings(), changed).close(), "同一 run 换登记必须拒绝");

        assertThrows(IOException.class, () -> new WorldRuntime(file, manifest(RUN, SEED, Map.of()),
                registryOpenings()).close(), "旧 profile 打开新日志必须拒绝");
    }

    @Test
    @DisplayName("旧 cash-only 日志在新代码下继续可推进，登记视图为空")
    void cashOnlyLogStillRunsWithEmptyRegistry() throws IOException {
        Path file = tempDir.resolve("cash-only.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            runtime.scheduleTransfer("c1", "t1", 10, "alice", "A", "B", new BigDecimal("100.00"), 0);
        }
        try (WorldRuntime runtime = new WorldRuntime(file, manifest(RUN, SEED, Map.of()), openings())) {
            assertTrue(runtime.openingRegistry().isEmpty(), "现金模式没有开局登记");
            runtime.advanceTo(20);
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("900.00")));
        }
        assertThrows(IOException.class, () -> new WorldRuntime(file, registryManifest(RUN, SEED),
                registryOpenings(), registry()).close(), "新 profile 打开旧日志必须拒绝");
    }

    @Test
    @DisplayName("清单与登记必须成对：缺登记或旧清单带登记都在写盘前拒绝")
    void registryProfileRequiresRegistryAndViceVersa() {
        Path without = tempDir.resolve("missing-registry.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(without,
                registryManifest(RUN, SEED), registryOpenings()));
        assertFalse(Files.exists(without), "拒绝时不得创建日志");

        Path extra = tempDir.resolve("extra-registry.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(extra,
                manifest(RUN, SEED, Map.of()), openings(), registry()));
        assertFalse(Files.exists(extra), "拒绝时不得创建日志");
    }

    @Test
    @DisplayName("登记与现金开局不符（持股账户不存在）在创建日志文件之前拒绝")
    void invalidRegistryRejectedBeforeFileCreated() {
        Path file = tempDir.resolve("invalid-registry.journal");
        OpeningRegistry foreignAccount = new OpeningRegistry(registryParties(), registryOwners(),
                List.of(new OpeningRegistry.Security("ACME1", "acme", 1000, new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", "ACME1", 600,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("B", "ACME1", 300,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("GHOST", "ACME1", 100,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT)));
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(file,
                registryManifest(RUN, SEED), registryOpenings(), foreignAccount));
        assertFalse(Files.exists(file), "登记校验失败不得留下日志文件");

        assertThrows(IllegalArgumentException.class, () -> new OpeningRegistry(
                registryParties(), registryOwners(),
                List.of(new OpeningRegistry.Security("ACME1", "acme", 1000,
                        new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", "ACME1", 999,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT))),
                "少发行在构造登记时就拒绝");
    }

    @Test
    @DisplayName("新增人物与零现金账户但没有股份分配，不改变发行与原持有人")
    void newOwnerWithZeroCashDoesNotChangeIssuance() throws IOException {
        Path file = tempDir.resolve("new-owner.journal");
        List<OpeningAccount> withCarol = new ArrayList<>(registryOpenings());
        withCarol.add(new OpeningAccount("C", "carol", new BigDecimal("0.00"), "实验开局历史禀赋"));
        try (WorldRuntime runtime = new WorldRuntime(file, registryManifest(RUN, SEED), withCarol,
                registryWithCarol())) {
            OpeningRegistry view = runtime.openingRegistry().orElseThrow();
            assertEquals(1000L, view.securities().get(0).issuedShares(), "发行总数不变");
            assertEquals(3, view.allocations().size(), "新账户没有自动获得股份");
            assertEquals(0, cash(runtime, "C").signum(), "新账户没有自动获得现金");
        }
    }

    @Test
    @DisplayName("产品资金不因人物姓名或岗位自报而获得权限")
    void productFundsNotReachableByPersonOrJobTitle() throws IOException {
        Path file = tempDir.resolve("product-funds.journal");
        try (WorldRuntime runtime = new WorldRuntime(file, registryManifest(RUN, SEED),
                registryOpenings(), registry())) {
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleTransfer(
                    "c1", "t1", 10, "alice", "FUND", "A", new BigDecimal("1.00"), 0),
                    "人物不能动用产品账户");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleTransfer(
                    "c2", "t2", 10, "alice.manager", "FUND", "A", new BigDecimal("1.00"), 0),
                    "岗位名称自报不获得权限");
            String outcome = runtime.scheduleTransfer("c3", "t3", 10, "prod-1", "FUND", "A",
                    new BigDecimal("1.00"), 0);
            assertTrue(outcome.startsWith("SCHEDULED:"), "产品主体自身可以排程");
        }
    }

    @Test
    @DisplayName("同一账户混合分类登记可开局，换插入顺序重开内容不变")
    void mixedClassificationOpeningRestores() throws IOException {
        Path file = tempDir.resolve("mixed-class.journal");
        OpeningRegistry forward = mixedRegistry(false);
        try (WorldRuntime runtime = new WorldRuntime(file, registryManifest(RUN, SEED),
                registryOpenings(), forward)) {
            assertEquals(1L, runtime.snapshot().position());
            assertEquals(2, runtime.openingRegistry().orElseThrow().allocations().size());
            assertEquals(0, cash(runtime, "A").compareTo(new BigDecimal("1000.00")),
                    "分类分的是股份，不是现金");
        }
        OpeningRegistry reversed = mixedRegistry(true);
        try (WorldRuntime reopened = new WorldRuntime(file, registryManifest(RUN, SEED),
                registryOpenings(), reversed)) {
            assertEquals(1L, reopened.snapshot().position(), "换插入顺序不得改变开局内容");
            assertEquals(forward.allocations(),
                    reopened.openingRegistry().orElseThrow().allocations());
            assertEquals(1000L, reopened.openingRegistry().orElseThrow()
                    .securities().get(0).issuedShares(), "总股份不变");
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void rewriteWithSnapshot(Path source, Path target, int position, UnaryOperator<String> mutate)
            throws IOException {
        try (WorldJournal from = new WorldJournal(source, RUN);
             WorldJournal to = new WorldJournal(target, RUN)) {
            for (WorldCommit commit : from.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    String payload = commit.position() == position
                            && "STATE_SNAPSHOT_V1".equals(event.type())
                            ? mutate.apply(event.payload()) : event.payload();
                    events.add(new WorldEvent(event.eventId(), event.type(), payload));
                }
                to.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String payload) {
        try {
            return JSON.readValue(payload, Map.class);
        } catch (IOException failure) {
            throw new IllegalStateException("无法解析状态快照", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException failure) {
            throw new IllegalStateException("无法写出状态快照", failure);
        }
    }

    private static OpeningRegistry mixedRegistry(boolean reverse) {
        List<OpeningRegistry.ShareAllocation> allocations = new ArrayList<>(List.of(
                new OpeningRegistry.ShareAllocation("A", "ACME1", 600,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                new OpeningRegistry.ShareAllocation("A", "ACME1", 400,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.LOCKED)));
        if (reverse) {
            java.util.Collections.reverse(allocations);
        }
        return new OpeningRegistry(registryParties(), registryOwners(),
                List.of(new OpeningRegistry.Security("ACME1", "acme", 1000,
                        new BigDecimal("10.00"))), allocations);
    }

    private static RunManifest registryManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("registry", 1);
        modules.put("scheduler", 1);
        return new RunManifest(runId, seed, RunManifest.RULES_CASH_REGISTRY_WORLD_V1, modules,
                Map.of(), RunManifest.MODEL_NONE);
    }

    private static List<OpeningAccount> registryOpenings() {
        return List.of(
                new OpeningAccount("A", "alice", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("B", "bob", new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("EXT", "ext-org", new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("TREAS", "acme", new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("FUND", "prod-1", new BigDecimal("0.00"), "实验开局历史禀赋"));
    }

    private static List<OpeningRegistry.Party> registryParties() {
        return List.of(
                new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "Alice"),
                new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "Bob"),
                new OpeningRegistry.Party("acme", OpeningRegistry.PartyKind.ORGANIZATION, "Acme 制造"),
                new OpeningRegistry.Party("ext-org", OpeningRegistry.PartyKind.ORGANIZATION, "外部持有人"));
    }

    private static List<OpeningRegistry.PropertyOwner> registryOwners() {
        return List.of(
                new OpeningRegistry.PropertyOwner("alice", OpeningRegistry.OwnerKind.PERSON, "alice"),
                new OpeningRegistry.PropertyOwner("bob", OpeningRegistry.OwnerKind.PERSON, "bob"),
                new OpeningRegistry.PropertyOwner("acme", OpeningRegistry.OwnerKind.ORGANIZATION, "acme"),
                new OpeningRegistry.PropertyOwner("ext-org",
                        OpeningRegistry.OwnerKind.ORGANIZATION, "ext-org"),
                new OpeningRegistry.PropertyOwner("prod-1", OpeningRegistry.OwnerKind.PRODUCT, null));
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(registryParties(), registryOwners(),
                List.of(new OpeningRegistry.Security("ACME1", "acme", 1000,
                        new BigDecimal("10.00"))),
                List.of(
                        new OpeningRegistry.ShareAllocation("A", "ACME1", 600,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("B", "ACME1", 300,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("EXT", "ACME1", 100,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT)));
    }

    private static OpeningRegistry registryWithCarol() {
        List<OpeningRegistry.Party> parties = new ArrayList<>(registryParties());
        parties.add(new OpeningRegistry.Party("carol", OpeningRegistry.PartyKind.PERSON, "Carol"));
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(registryOwners());
        owners.add(new OpeningRegistry.PropertyOwner("carol",
                OpeningRegistry.OwnerKind.PERSON, "carol"));
        OpeningRegistry base = registry();
        return new OpeningRegistry(parties, owners, base.securities(), base.allocations());
    }

    private RunManifest manifest(String runId, long seed, Map<String, String> dataVersions) {
        return new RunManifest(runId, seed, "cash-world-v1", modules(), dataVersions, "none");
    }

    private static Map<String, Integer> modules() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        return modules;
    }

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount("A", "alice", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("B", "bob", new BigDecimal("0.00"), "实验开局历史禀赋"));
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }
}
