package edu.cufe.auction.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cufe.auction.web.WorldWebScenario;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * schema23 商业化批准的真实写故障：部分 WRITE 与完整写后 FORCE 失败。
 *
 * <p>沿用研发独审的可替换 FileChannel 方案（测试反射换信道，生产无开关）：内存不发布、重开按前缀
 * 丢弃半帧或按完整帧确认，重做后批准只发生一次。</p>
 *
 * <p><b>口径更正</b>：PARTIAL 用例先注入再推进到 FINISH，首个提交实际是 BEGIN 屏障帧（与独审
 * round1 指出的标签一致）；因此它证明的是“屏障帧半写不发布”。FINISH 帧与期末摊销帧的精确
 * PARTIAL/FORCE 证据由独审按目标 commandId 注入给出，本类不重复编造。</p>
 */
class ResearchCommercialFaultTest {

    private static final String A = WorldWebScenario.ENGINE_A;
    private static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    @Test
    void partialWriteOnApprovalBarrierKeepsNoHalfStateThenRedoOnce() throws Exception {
        Path file = dir.resolve("partial.journal");
        WorldRuntime world = preparedWithMeeting(file);
        long due = world.snapshot().timeMillis() + 2_000L;
        WorldRoleSession manager = world.openRoleSession("dave");
        manager.approveCommercialization("ap-w", "ap-w-task", due, A, "p1", "cm-1", 1_000L);
        inject(world, false);
        assertThrows(IOException.class, () -> world.advanceTo(due));
        assertTrue(project(world, "p1").approvalRef() == null, "未确认的半帧不得留下批准");
        long position = world.snapshot().position();
        world.close();

        try (WorldRuntime recovered = WorldWebScenario.openResearchCommercial(file).runtime()) {
            assertTrue(project(recovered, "p1").approvalRef() == null, "恢复后仍无批准事实");
            assertEquals(position, recovered.snapshot().position(), "位置保持已确认前缀");
            long retryDue = recovered.snapshot().timeMillis() + 3_000L;
            recovered.openRoleSession("dave").approveCommercialization("ap-w2", "ap-w2-task",
                    retryDue, A, "p1", "cm-1", 1_000L);
            recovered.advanceTo(retryDue);
            assertEquals(ResearchModel.ProjectStatus.APPROVED,
                    project(recovered, "p1").status(), "重做后批准一次");
            assertEquals(0, recovered.snapshot().accounts().get("ENG-A").cash()
                    .compareTo(new BigDecimal("4100.00")), "付款不受影响");
        }
    }

    @Test
    void forceFailureOnCompleteFrameConfirmsOnceAndRetryDoesNotDoubleCount() throws Exception {
        Path file = dir.resolve("force.journal");
        WorldRuntime world = preparedWithMeeting(file);
        long due = world.snapshot().timeMillis() + 2_000L;
        WorldRoleSession manager = world.openRoleSession("dave");
        manager.approveCommercialization("ap-f", "ap-f-task", due, A, "p1", "cm-1", 1_000L);
        // 先让 BEGIN 屏障正常落盘，再注入 FORCE 失败，确保失败的是批准完成帧本身。
        world.advanceTo(due - 1_000L);
        inject(world, true);
        assertThrows(IOException.class, () -> world.advanceTo(due));
        world.close();

        try (WorldRuntime recovered = WorldWebScenario.openResearchCommercial(file).runtime()) {
            assertEquals(ResearchModel.ProjectStatus.APPROVED,
                    project(recovered, "p1").status(), "完整帧在恢复时确认");
            long approvedAt = project(recovered, "p1").approvedAt();
            assertTrue(approvedAt > 0, "可用起点已记录");
            long position = recovered.snapshot().position();
            WorldRoleSession again = recovered.openRoleSession("dave");
            again.approveCommercialization("ap-f2", "ap-f2-task", approvedAt + 2_000L, A, "p1",
                    "cm-1", 1_000L);
            recovered.advanceTo(approvedAt + 2_000L);
            assertTrue(recovered.snapshot().position() >= position + 2,
                    "重试也要有真实屏障与完成两帧");
            assertEquals(approvedAt, project(recovered, "p1").approvedAt(), "不得改写可用起点");
            assertEquals(0, recovered.snapshot().accounts().get("ENG-A").cash()
                    .compareTo(new BigDecimal("4100.00")), "付款只发生一次");
            assertTrue(recovered.taskResultForTest("ap-f2-task")
                    .contains(ResearchModel.REASON_ALREADY_APPROVED),
                    recovered.taskResultForTest("ap-f2-task"));
        }
    }

    // ------------------------------------------------------------------ 夹具与故障注入

    private static WorldRuntime preparedWithMeeting(Path file) throws Exception {
        WorldRuntime world = WorldWebScenario.openResearchCommercial(file).runtime();
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        List<String> voters = List.of("alice", "bob", "carol", "dave", "erin");
        int index = 0;
        for (String reader : voters) {
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
            index++;
        }
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L, A, "alice",
                "budget-meeting", 150L, 200L, 300L);
        for (int i = 0; i < 5; i++) {
            world.scheduleEngineeringAttend("budget-a-" + i, "budget-a-" + i + "-task",
                    110L + i, A, voters.get(i), "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L, A, "alice",
                "budget-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L, A, "alice",
                "budget-meeting", List.of(new BoardModel.BudgetLine(0,
                        BoardModel.BudgetCategory.PROCUREMENT, new BigDecimal("10000.00"))));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("budget-v-" + i, "budget-v-" + i + "-task",
                    220L + i, A, voters.get(i), "budget-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L, A, "alice",
                "budget-meeting");
        world.scheduleEngineeringAppointmentConvene("appt-c", "appt-c-task", 400L, A, "alice",
                "appt-meeting", 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("appt-a-" + i, "appt-a-" + i + "-task", 410L + i, A,
                    voters.get(i), "appt-meeting", false);
        }
        world.scheduleEngineeringMaterial("appt-m", "appt-m-task", 430L, A, "alice",
                "appt-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("appt-p", "appt-p-task", 440L, A, "alice",
                "appt-meeting", List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, 90L * DAY));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("appt-v-" + i, "appt-v-" + i + "-task", 520L + i, A,
                    voters.get(i), "appt-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("appt-i", "appt-i-task", 620L, A, "alice",
                "appt-meeting");
        world.advanceTo(1_000L);

        world.scheduleResearchRegister("reg-p1", "reg-p1-task", 3_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(3_001L);
        for (int i = 0; i < 3; i++) {
            ResearchModel.Stage stage = ResearchModel.Stage.values()[i];
            long finish = world.snapshot().timeMillis() + 2_000L;
            world.scheduleResearchBegin("b-" + i, "b-task-" + i, finish, A, "dave", "p1", stage,
                    1_000L);
            world.advanceTo(finish);
            world.advanceTo(finish + DAY);
        }
        long now = world.snapshot().timeMillis();
        for (String reader : List.of("alice", "bob", "carol", "dave")) {
            for (int i = 0; i < 3; i++) {
                String doc = "research:eng-a:p1:" + ResearchModel.Stage.values()[i].name()
                        + ":SUCCESS";
                world.scheduleStartReading("rd-" + reader + "-" + i,
                        "rd-task-" + reader + "-" + i, now + 2_000L + i * 1_100L, reader, doc,
                        1L);
            }
        }
        world.advanceTo(now + 2_000L + 3L * 1_100L + 500L);

        long at = world.snapshot().timeMillis() + 1_000L;
        world.scheduleCommercializationConvene("cv-1", "cv-1-task", at, A, "alice", "cm-1",
                at + 2_500L, at + 2_700L, at + 3_500L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("at-" + i, "at-task-" + i, at + 50L + i, A,
                    voters.get(i), "cm-1", false);
            world.scheduleEngineeringMaterial("mat-" + i, "mat-task-" + i, at + 100L + i, A,
                    "alice", "cm-1", "m" + (i + 1),
                    "research:eng-a:p1:" + ResearchModel.Stage.values()[i].name() + ":SUCCESS",
                    1L);
        }
        List<BoardModel.MaterialRef> refs = List.of(
                new BoardModel.MaterialRef("m1", "research:eng-a:p1:RESEARCH:SUCCESS", 1L, at),
                new BoardModel.MaterialRef("m2", "research:eng-a:p1:DEVELOPMENT:SUCCESS", 1L, at),
                new BoardModel.MaterialRef("m3", "research:eng-a:p1:REGISTRATION:SUCCESS", 1L, at));
        world.scheduleCommercializationProposal("pp-1", "pp-1-task", at + 2_000L, A, "alice",
                "cm-1", List.of(), new BoardModel.CommercializationRequest(A, "p1", refs),
                1_000L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("vote-" + i, "vote-task-" + i, at + 3_000L + i, A,
                    voters.get(i), "cm-1", BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCommercializationImplement("impl-1", "impl-1-task", at + 3_600L, A,
                "alice", "cm-1");
        world.advanceTo(at + 3_600L);
        assertNotNull(world.researchBudgetForTest(A).meetings().get("cm-1")
                .commercializationResolution(), "夹具必须先签发真实授权");
        return world;
    }

    /** 反射替换日志信道：PARTIAL 只放 20 字节后抛写失败；FORCE 允许写完再让 force 抛错。 */
    private static void inject(WorldRuntime runtime, boolean forceMode) throws Exception {
        Field journalField = WorldRuntime.class.getDeclaredField("journal");
        journalField.setAccessible(true);
        Object journal = journalField.get(runtime);
        Field channelField = journal.getClass().getDeclaredField("channel");
        channelField.setAccessible(true);
        FileChannel real = (FileChannel) channelField.get(journal);
        channelField.set(journal, new FailingChannel(real, forceMode, 20));
    }

    private static ResearchModel.Project project(WorldRuntime world, String projectId) {
        return world.researchRun().orElseThrow().state().projects().get(projectId);
    }

    private static final class FailingChannel extends FileChannel {
        private final FileChannel delegate;
        private final boolean forceMode;
        private int budget;

        FailingChannel(FileChannel delegate, boolean forceMode, int budget) {
            this.delegate = delegate;
            this.forceMode = forceMode;
            this.budget = budget;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (forceMode) {
                return delegate.write(src);
            }
            if (budget <= 0) {
                throw new IOException("INJECTED_PARTIAL_WRITE");
            }
            int allow = Math.min(budget, src.remaining());
            ByteBuffer slice = src.duplicate();
            slice.limit(slice.position() + allow);
            int written = delegate.write(slice);
            src.position(src.position() + written);
            budget -= written;
            return written;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return delegate.write(srcs, offset, length);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            return delegate.write(src, position);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            if (forceMode) {
                throw new IOException("INJECTED_FORCE_FAILURE");
            }
            delegate.force(metaData);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target)
                throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count)
                throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }
}
