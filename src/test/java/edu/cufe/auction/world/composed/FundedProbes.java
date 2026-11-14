package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工程资金切片的真实探针（故障、语义篡改、三 JVM 恢复）。
 *
 * <p>用法：</p>
 * <pre>
 *   fault   WRITE|FORCE &lt;dir&gt;              故障前复制两份同字节副本，一份正常执行、一份真实注入
 *   tamper  &lt;dir&gt;                          按真实帧格式精准改首个资金完成帧并重封 SHA-256
 *   recover s1|two|three &lt;dir&gt; &lt;digest&gt;   三个独立 JVM 的相邻权威状态逐分量比较
 *   attack  &lt;dir&gt;                          同进程跑完整 S1 后依次执行上述三个探针
 * </pre>
 *
 * <p>探针只在测试作用域，生产代码不知道它的存在，也没有失败注入开关。目录级别的
 * {@link #faultProbe(String, Path, Path)}、{@link #tamperProbe(Path, Path)} 可被 JUnit 直接调用。</p>
 */
public final class FundedProbes {

    private static final String POLICY = "COMPANY_CHOICE_V1";

    private FundedProbes() {
    }

    /**
     * 入口。
     *
     * @param args 模式与参数
     * @throws Exception 打开/运行失败
     */
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if ("fault".equals(mode)) {
            fault(args[1], Path.of(args[2]));
        } else if ("tamper".equals(mode)) {
            tamper(Path.of(args[1]));
        } else if ("recover".equals(mode)) {
            recover(args[1], Path.of(args[2]), Path.of(args[3]));
        } else if ("attack".equals(mode)) {
            attack(Path.of(args[1]));
        } else {
            throw new IllegalArgumentException("未知模式：" + mode);
        }
    }

    // ------------------------------------------------------------------ 故障注入

    /** 真实 WRITE/FORCE 失败：故障前两份同字节副本，对照与攻击执行同一命令。 */
    private static void fault(String faultMode, Path dir) throws Exception {
        Path source = dir.resolve("world.log");
        if (!Files.exists(source)) {
            System.out.println("RESULT=BAD（缺少 S1 世界：" + source + "）");
            System.exit(1);
        }
        FaultResult result = faultProbe(faultMode, source, dir);
        System.out.println("MODE=" + faultMode);
        System.out.println("FAILURE=" + result.failure());
        System.out.println("MEMORY_INTACT=" + result.memoryIntact());
        System.out.println("REOPEN_PRE=" + result.reopenPre());
        System.out.println("REOPEN_POST=" + result.reopenPost());
        System.out.println("REOPEN_STABLE=" + result.reopenStable());
        System.out.println("COMPONENTS_EQUAL=" + result.componentsEqual());
        System.out.println("SAME_CONTENT_RETRY_SAME_RESULT="
                + result.sameContentRetrySameResult());
        System.out.println("SAME_CONTENT_RETRY_STATE_UNCHANGED="
                + result.sameContentRetryStateUnchanged());
        System.out.println("DIFFERENT_OBSERVATION_CONFLICT="
                + result.differentObservationConflict());
        System.out.println("RESUME_COMPLETED_ONCE=" + result.resumeCompletedOnce());
        System.out.println("RESULT=" + (result.ok() ? "OK" : "BAD"));
        if (!result.ok()) {
            System.exit(1);
        }
    }

    /**
     * 一次真实提交故障的完整对照探针。
     *
     * <p>两份副本都在注入之前从同一份干净字节复制，并各自先完成 BEGIN 屏障（同一命令、同一内容），
     * 然后才让其中一份在 <b>真正付款的 FINISH 帧</b> 提交时失败。判定要求：攻击副本内存仍是
     * 付款提交前；重开只允许是完整前态或完整一次后态；连续再重开稳定；逐分量比较相等，
     * 而不只是金额或 position。</p>
     *
     * <p>重试边界分开验证：①同内容正例——在观察时点不变、尚未推进前，用完全相同的
     * due/task/id/buckets/amount 再排一次，必须返回同一结果且 position/state 不变，然后只完成一次；
     * ②异内容负例——完成后再用同一 commandId、新 observedAt 排程，必须内容冲突且不付款；
     * ③不确定恢复——重开攻击副本后只恢复其原有持久待办到原 finish（没有待办则同一时点空推进），
     * 再在同一 finish 边界推进一次，与无故障对照的完整结果比较，且付款帧只出现一次。</p>
     *
     * @param mode   {@code WRITE} 或 {@code FORCE}
     * @param source 干净 S1 世界文件
     * @param dir    探针目录
     * @return 结构化结果
     * @throws Exception 读写失败
     */
    static FaultResult faultProbe(String mode, Path source, Path dir) throws Exception {
        Files.createDirectories(dir);
        byte[] clean = Files.readAllBytes(source);
        Path control = dir.resolve("fault-control-" + mode + ".log");
        Path attacked = dir.resolve("fault-attacked-" + mode + ".log");
        Files.write(control, clean);
        Files.write(attacked, clean);
        String tag = "fault-" + mode;
        String finishTask = tag + "-t:finish";

        Map<String, String> controlAfter;
        boolean sameContentRetrySameResult;
        boolean sameContentRetryStateUnchanged;
        long controlDue;
        try (WorldRuntime runtime = ComposedWorldScenario.open(control)) {
            controlDue = runtime.snapshot().timeMillis() + 3;
            String first = scheduleFaultCommand(runtime, mode, controlDue);
            Map<String, String> afterFirst = components(runtime);
            long positionAfterFirst = runtime.snapshot().position();
            String second = scheduleFaultCommand(runtime, mode, controlDue);
            sameContentRetrySameResult = first.equals(second);
            sameContentRetryStateUnchanged = components(runtime).equals(afterFirst)
                    && runtime.snapshot().position() == positionAfterFirst;
            runtime.advanceTo(controlDue - 1);
            runtime.advanceTo(controlDue);
            controlAfter = components(runtime);
            sameContentRetryStateUnchanged = sameContentRetryStateUnchanged
                    && countCommits(runtime, "task:" + finishTask) == 1;
        }
        // 异内容负例：完成后再用同一 commandId 但新的 observedAt 排程 → 内容冲突、不付款。
        boolean differentObservationConflict;
        try (WorldRuntime runtime = ComposedWorldScenario.open(control)) {
            if (!components(runtime).equals(controlAfter)) {
                throw new IllegalStateException("对照副本重开与关前权威状态不一致");
            }
            differentObservationConflict = false;
            try {
                scheduleFaultCommandAtNewObservation(runtime, mode);
            } catch (IllegalArgumentException expected) {
                differentObservationConflict = expected.getMessage() != null
                        && expected.getMessage().contains("同一命令标识不能对应不同内容");
            }
            differentObservationConflict = differentObservationConflict
                    && components(runtime).equals(controlAfter);
        }

        Map<String, String> before;
        Map<String, String> afterFailure;
        String failure = "NONE";
        try (WorldRuntime runtime = ComposedWorldScenario.open(attacked)) {
            long due = runtime.snapshot().timeMillis() + 3;
            if (due != controlDue) {
                throw new IllegalStateException("两份副本的起始时点不同："
                        + due + " vs " + controlDue);
            }
            scheduleFaultCommand(runtime, mode, due);
            runtime.advanceTo(due - 1);
            before = components(runtime);
            inject(runtime, "WRITE".equals(mode) ? FailingChannel.Mode.WRITE
                    : FailingChannel.Mode.FORCE);
            try {
                runtime.advanceTo(due);
            } catch (IOException | UncheckedIOException expected) {
                failure = String.valueOf(expected.getMessage());
            }
            afterFailure = components(runtime);
        }

        Map<String, String> reopened;
        Map<String, String> resumed;
        Map<String, String> resumedAgain;
        boolean resumeCompletedOnce;
        try (WorldRuntime runtime = ComposedWorldScenario.open(attacked)) {
            reopened = components(runtime);
            long positionBefore = runtime.snapshot().position();
            PendingTask pending = pendingOf(runtime, finishTask);
            long finishDue = pending == null ? runtime.snapshot().timeMillis()
                    : pending.dueTimeMillis();
            runtime.advanceTo(finishDue);
            resumed = components(runtime);
            runtime.advanceTo(finishDue);
            resumedAgain = components(runtime);
            long positionAfter = runtime.snapshot().position();
            boolean singleFrame = pending == null ? positionAfter == positionBefore
                    : positionAfter == positionBefore + 1;
            resumeCompletedOnce = resumed.equals(controlAfter)
                    && resumedAgain.equals(controlAfter) && singleFrame
                    && countCommits(runtime, "task:" + finishTask) == 1;
        }
        // 恢复后的后态再完整重开一次：必须与恢复推进后的状态逐分量相等。
        Map<String, String> reopenedAgain;
        try (WorldRuntime runtime = ComposedWorldScenario.open(attacked)) {
            reopenedAgain = components(runtime);
        }
        boolean memoryIntact = before.equals(afterFailure);
        boolean reopenPre = before.equals(reopened);
        boolean reopenPost = controlAfter.equals(reopened);
        boolean stable = resumedAgain.equals(reopenedAgain);
        boolean componentsEqual = reopenPre || reopenPost;
        boolean ok = !"NONE".equals(failure) && memoryIntact && componentsEqual && stable
                && sameContentRetrySameResult && sameContentRetryStateUnchanged
                && differentObservationConflict && resumeCompletedOnce;
        return new FaultResult(mode, failure, memoryIntact, reopenPre, reopenPost, stable,
                componentsEqual, sameContentRetrySameResult, sameContentRetryStateUnchanged,
                differentObservationConflict, resumeCompletedOnce, ok);
    }

    /** 用原观察时点的完全相同参数排程一次故障命令，返回本次排程结果。 */
    private static String scheduleFaultCommand(WorldRuntime runtime, String mode, long due)
            throws Exception {
        String tag = "fault-" + mode;
        return runtime.scheduleFundedEngineeringAction(tag, tag + "-t", due, "eng-a", "carol",
                "ADVANCE", "A1", "", 0, 0, new BigDecimal("10.00"), 0L, POLICY,
                "CASH|ENG-A-CUS", "DEPOSIT|" + ComposedWorldScenario.BANK + "|eng-a", 1L);
    }

    /** 完成后用同一 commandId、新的 observedAt 再排一次：这是异内容冲突负例，不是幂等成功。 */
    private static void scheduleFaultCommandAtNewObservation(WorldRuntime runtime, String mode)
            throws Exception {
        long due = runtime.snapshot().timeMillis() + 3;
        scheduleFaultCommand(runtime, mode, due);
    }

    /** 当前待办里的指定任务。 */
    private static PendingTask pendingOf(WorldRuntime runtime, String taskId) {
        for (PendingTask task : runtime.snapshot().pending()) {
            if (task.taskId().equals(taskId)) {
                return task;
            }
        }
        return null;
    }

    /** 该命令标识在已确认日志中出现的次数（用于证明付款帧只发生一次）。 */
    private static int countCommits(WorldRuntime runtime, String commandId) throws Exception {
        java.lang.reflect.Field journalField = WorldRuntime.class.getDeclaredField("journal");
        journalField.setAccessible(true);
        edu.cufe.auction.world.WorldJournal journal =
                (edu.cufe.auction.world.WorldJournal) journalField.get(runtime);
        int count = 0;
        for (edu.cufe.auction.world.WorldCommit commit : journal.commits()) {
            if (commit.commandId().equals(commandId)) {
                count++;
            }
        }
        return count;
    }

    private static void inject(WorldRuntime runtime, FailingChannel.Mode mode) throws Exception {
        java.lang.reflect.Field journalField = WorldRuntime.class.getDeclaredField("journal");
        journalField.setAccessible(true);
        Object journal = journalField.get(runtime);
        java.lang.reflect.Field channelField = journal.getClass().getDeclaredField("channel");
        channelField.setAccessible(true);
        FileChannel real = (FileChannel) channelField.get(journal);
        channelField.set(journal, new FailingChannel(real, mode, 0));
    }

    /** 故障探针的结论。 */
    record FaultResult(String mode, String failure, boolean memoryIntact, boolean reopenPre,
                       boolean reopenPost, boolean reopenStable, boolean componentsEqual,
                       boolean sameContentRetrySameResult,
                       boolean sameContentRetryStateUnchanged,
                       boolean differentObservationConflict, boolean resumeCompletedOnce,
                       boolean ok) {
    }

    // ------------------------------------------------------------------ 语义篡改

    /** 按真实帧格式改首个资金完成帧的命令原文并重封 SHA-256；对照必须能开，攻击必须被领域重演拒绝。 */
    private static void tamper(Path dir) throws Exception {
        Path source = dir.resolve("world.log");
        TamperResult result = tamperProbe(source, dir);
        System.out.println("TAMPER_FRAME_POSITION=" + result.framePosition());
        System.out.println("TAMPER_HITS=" + result.hits());
        System.out.println("CONTROL=" + result.control());
        System.out.println("ATTACKED=" + result.attacked());
        System.out.println("REJECTED_BY_DOMAIN=" + result.domainRejection());
        System.out.println("RESULT=" + (result.ok() ? "OK" : "BAD"));
        if (!result.ok()) {
            System.exit(1);
        }
    }

    /**
     * 语义篡改探针：复用 WorldJournal 真实帧编码（长度 / 反码 / JSON / SHA-256），
     * 只改首个资金完成帧 {@code task:fund-adv-t:finish} 的命令原文里的源桶 token，
     * 其它字节与冻结字段一律不动，然后重算校验和。恢复期必须由“原任务命令原文”校验拒绝，
     * 不能是校验和/JSON 损坏。
     *
     * @param source 干净 S1 世界文件
     * @param dir    探针目录
     * @return 结构化结果
     * @throws Exception 读写失败
     */
    static TamperResult tamperProbe(Path source, Path dir) throws Exception {
        Files.createDirectories(dir);
        byte[] clean = Files.readAllBytes(source);
        Path controlFile = dir.resolve("semantic-control.log");
        Files.write(controlFile, clean);
        String control;
        try (WorldRuntime runtime = ComposedWorldScenario.open(controlFile)) {
            control = "OPENED:" + stateDigest(runtime);
        } catch (IOException | RuntimeException rejected) {
            control = "REJECTED:" + firstLine(rejected.getMessage());
        }

        Tampered tampered = tamperFirstFundedFrame(clean, "task:fund-adv-t:finish",
                "CASH|ENG-A-CUS", "CASH|ENG-A-SUP");
        Path attackedFile = dir.resolve("semantic-attacked.log");
        Files.write(attackedFile, tampered.bytes());
        String attacked;
        String message = "";
        try (WorldRuntime runtime = ComposedWorldScenario.open(attackedFile)) {
            attacked = "OPENED";
        } catch (IOException | RuntimeException rejected) {
            message = String.valueOf(rejected.getMessage());
            attacked = "REJECTED:" + firstLine(message);
        }
        boolean domain = message.contains("原任务")
                || message.contains("命令原文")
                || message.contains("命令内容")
                || message.contains("待办不一致");
        boolean notCorrupt = !message.contains("校验和") && !message.contains("帧头")
                && !message.contains("事件不合法");
        boolean ok = control.startsWith("OPENED") && tampered.hits() == 1
                && attacked.startsWith("REJECTED") && domain && notCorrupt;
        return new TamperResult(tampered.framePosition(), tampered.hits(), control, attacked,
                domain && notCorrupt, ok);
    }

    /** 语义篡改结论。 */
    record TamperResult(long framePosition, int hits, String control, String attacked,
                         boolean domainRejection, boolean ok) {
    }

    /**
     * 在真实帧里定位指定命令标识，只替换其 {@code commandContent} 字符串里的一个等长 token，
     * 再用同一 SHA-256 重新封装该帧（长度不变、其它帧与字段逐字不变）。
     */
    private static Tampered tamperFirstFundedFrame(byte[] raw, String commandId,
            String from, String to) throws Exception {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("篡改 token 必须等长");
        }
        byte[] fromBytes = from.getBytes(StandardCharsets.UTF_8);
        byte[] toBytes = to.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        int frameIndex = 0;
        while (offset + 8 <= raw.length) {
            int length = ByteBuffer.wrap(raw, offset, 4).getInt();
            int inverse = ByteBuffer.wrap(raw, offset + 4, 4).getInt();
            if (inverse != ~length || length <= 0 || offset + 8 + length + 32 > raw.length) {
                throw new IOException("世界日志帧头损坏，位置 " + offset);
            }
            byte[] payload = new byte[length];
            System.arraycopy(raw, offset + 8, payload, 0, length);
            String text = new String(payload, StandardCharsets.UTF_8);
            if (text.contains("\"commandId\":\"" + commandId + "\"")) {
                byte[] mutated = replaceCommandContent(payload, fromBytes, toBytes);
                if (mutated != null) {
                    byte[] frame = new byte[8 + length + 32];
                    ByteBuffer.wrap(frame, 0, 4).putInt(length);
                    ByteBuffer.wrap(frame, 4, 4).putInt(~length);
                    System.arraycopy(mutated, 0, frame, 8, length);
                    byte[] sha = MessageDigest.getInstance("SHA-256").digest(mutated);
                    System.arraycopy(sha, 0, frame, 8 + length, 32);
                    byte[] out = raw.clone();
                    System.arraycopy(frame, 0, out, offset, frame.length);
                    return new Tampered(out, frameIndex, 1);
                }
                return new Tampered(raw, frameIndex, 0);
            }
            offset += 8 + length + 32;
            frameIndex++;
        }
        return new Tampered(raw, -1, 0);
    }

    /** 仅改 commandContent 字符串值内的目标 token；返回 null 表示没有可改的 token。 */
    private static byte[] replaceCommandContent(byte[] payload, byte[] from, byte[] to) {
        String text = new String(payload, StandardCharsets.UTF_8);
        int key = text.indexOf("\"commandContent\":\"");
        if (key < 0) {
            return null;
        }
        int start = key + "\"commandContent\":\"".length();
        int end = start;
        boolean escaped = false;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            }
            end++;
        }
        int index = indexOf(payload, from, start, end);
        if (index < 0) {
            return null;
        }
        byte[] out = payload.clone();
        System.arraycopy(to, 0, out, index, to.length);
        return out;
    }

    private static int indexOf(byte[] data, byte[] needle, int from, int to) {
        outer:
        for (int i = from; i + needle.length <= to; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private record Tampered(byte[] bytes, int framePosition, int hits) {
    }

    // ------------------------------------------------------------------ 三进程恢复

    /** 三个独立 JVM 的连续链：s1 建好世界，two 重开并加一笔，three 完整重开逐分量比较。 */
    private static void recover(String phase, Path dir, Path digestFile) throws Exception {
        Path world = dir.resolve("world.log");
        if ("s1".equals(phase)) {
            List<String> failures = ComposedWorldSelfTest.run(world);
            if (!failures.isEmpty()) {
                System.out.println("RESULT=BAD（S1 失败：" + failures + "）");
                System.exit(1);
            }
            try (WorldRuntime runtime = ComposedWorldScenario.open(world)) {
                writeDigest(digestFile, components(runtime), stateDigest(runtime));
                System.out.println("STATE_SHA=" + stateDigest(runtime));
            }
            System.out.println("RESULT=OK");
            return;
        }
        if ("two".equals(phase)) {
            Map<String, String> expected = readDigest(digestFile);
            try (WorldRuntime runtime = ComposedWorldScenario.open(world)) {
                Map<String, String> reopened = components(runtime);
                boolean same = expected.equals(reopened);
                System.out.println("ADJACENT_MATCH=" + same);
                if (!same) {
                    System.out.println("RESULT=BAD（重开与上一 JVM 不一致）");
                    System.exit(1);
                }
                long due = runtime.snapshot().timeMillis() + 3;
                runtime.scheduleFundedEngineeringAction("rec-inv", "rec-inv-t", due,
                        "eng-a", "carol", "INVEST_WORK", "A1", "W-A1H", 0, 10,
                        BigDecimal.ZERO, 0L, POLICY, "DEPOSIT|north|eng-a",
                        "CASH|ENG-A-OPS", 1L);
                runtime.advanceTo(due);
                String result = runtime.taskCommitResult("rec-inv-t:finish");
                System.out.println("INV2=" + result);
                if (!"ACCEPTED".equals(result)) {
                    System.out.println("RESULT=BAD");
                    System.exit(1);
                }
                writeDigest(digestFile, components(runtime), stateDigest(runtime));
                System.out.println("STATE_SHA=" + stateDigest(runtime));
            }
            System.out.println("RESULT=OK");
            return;
        }
        Map<String, String> expected = readDigest(digestFile);
        try (WorldRuntime runtime = ComposedWorldScenario.open(world)) {
            Map<String, String> actual = components(runtime);
            List<String> diff = new ArrayList<>();
            for (String key : expected.keySet()) {
                if (!expected.get(key).equals(actual.get(key))) {
                    diff.add(key);
                }
            }
            System.out.println("COMPONENTS_MATCH=" + diff.isEmpty() + " diff=" + diff);
            System.out.println("STATE_SHA=" + stateDigest(runtime));
            if (!diff.isEmpty()) {
                System.out.println("RESULT=BAD");
                System.exit(1);
            }
        }
        System.out.println("RESULT=OK");
    }

    /** 同进程攻击组：先跑完整 S1 建好世界，再依次跑故障与语义篡改探针。 */
    private static void attack(Path dir) throws Exception {
        Files.createDirectories(dir);
        List<String> failures = ComposedWorldSelfTest.run(dir.resolve("world.log"));
        if (!failures.isEmpty()) {
            System.out.println("S1_FAIL=" + failures);
            System.out.println("RESULT=BAD");
            System.exit(1);
        }
        Path source = dir.resolve("world.log");
        FaultResult write = faultProbe("WRITE", source, dir);
        FaultResult force = faultProbe("FORCE", source, dir);
        TamperResult tamper = tamperProbe(source, dir);
        System.out.println("FAULT_WRITE ok=" + write.ok() + " failure=" + write.failure()
                + " memory=" + write.memoryIntact() + " pre=" + write.reopenPre()
                + " post=" + write.reopenPost() + " stable=" + write.reopenStable()
                + " components=" + write.componentsEqual()
                + " sameContent=" + write.sameContentRetrySameResult()
                + " retryState=" + write.sameContentRetryStateUnchanged()
                + " differentObsConflict=" + write.differentObservationConflict()
                + " resumeOnce=" + write.resumeCompletedOnce());
        System.out.println("FAULT_FORCE ok=" + force.ok() + " failure=" + force.failure()
                + " memory=" + force.memoryIntact() + " pre=" + force.reopenPre()
                + " post=" + force.reopenPost() + " stable=" + force.reopenStable()
                + " components=" + force.componentsEqual()
                + " sameContent=" + force.sameContentRetrySameResult()
                + " retryState=" + force.sameContentRetryStateUnchanged()
                + " differentObsConflict=" + force.differentObservationConflict()
                + " resumeOnce=" + force.resumeCompletedOnce());
        System.out.println("SEMANTIC_TAMPER ok=" + tamper.ok() + " hits=" + tamper.hits()
                + " control=" + tamper.control() + " attacked=" + tamper.attacked());
        boolean ok = write.ok() && force.ok() && tamper.ok();
        System.out.println("RESULT=" + (ok ? "OK" : "BAD"));
        if (!ok) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 权威摘要

    /** 当前权威 WorldState 的规范 JSON（与磁盘 STATE_SNAPSHOT 同一 writer）。 */
    static String stateJson(WorldRuntime runtime) throws Exception {
        Object state = authority(runtime);
        ObjectMapper mapper = canonical(runtime);
        return mapper.writeValueAsString(state);
    }

    /** 当前权威 WorldState 的 SHA-256（完整 JSON，不只金额/position）。 */
    static String stateDigest(WorldRuntime runtime) throws Exception {
        return sha256(stateJson(runtime).getBytes(StandardCharsets.UTF_8));
    }

    /** 逐分量摘要：每个 WorldState 记录分量单独规范序列化后取 SHA-256。 */
    static Map<String, String> components(WorldRuntime runtime) throws Exception {
        Object state = authority(runtime);
        ObjectMapper mapper = canonical(runtime);
        Map<String, String> map = new LinkedHashMap<>();
        for (java.lang.reflect.RecordComponent component
                : state.getClass().getRecordComponents()) {
            java.lang.reflect.Method accessor = component.getAccessor();
            accessor.setAccessible(true);
            Object value = accessor.invoke(state);
            String json = value == null ? "null" : mapper.writeValueAsString(value);
            map.put(component.getName(), sha256(json.getBytes(StandardCharsets.UTF_8)));
        }
        return map;
    }

    private static Object authority(WorldRuntime runtime) throws Exception {
        java.lang.reflect.Field field = WorldRuntime.class.getDeclaredField("state");
        field.setAccessible(true);
        return field.get(runtime);
    }

    private static ObjectMapper canonical(WorldRuntime runtime) throws Exception {
        java.lang.reflect.Method method = WorldRuntime.class.getDeclaredMethod("stateCanonical");
        method.setAccessible(true);
        return (ObjectMapper) method.invoke(runtime);
    }

    static String digestOf(Map<String, String> components) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(components).entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sha256(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeDigest(Path file, Map<String, String> components, String fullDigest)
            throws Exception {
        StringBuilder builder = new StringBuilder();
        builder.append("STATE_SHA=").append(fullDigest).append('\n');
        for (Map.Entry<String, String> entry : new TreeMap<>(components).entrySet()) {
            builder.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
        Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> readDigest(Path file) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("STATE_SHA=")) {
                continue;
            }
            int space = line.indexOf(' ');
            map.put(line.substring(0, space), line.substring(space + 1));
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("摘要文件没有分量：" + file);
        }
        return map;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return java.util.HexFormat.of().formatHex(digest);
    }

    /** 人类阅读用的短摘要（金额/position/合同进度），不作为跨 JVM 判据。 */
    static String shortDigest(WorldRuntime runtime) {
        WorldRuntime.Snapshot snap = runtime.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("pos=").append(snap.position()).append(";t=").append(snap.timeMillis());
        for (Map.Entry<String, CashLedger.CashAccount> entry
                : new TreeMap<>(snap.accounts()).entrySet()) {
            sb.append(';').append(entry.getKey()).append('=')
                    .append(entry.getValue().cash().toPlainString());
        }
        BankRun run = runtime.bankOf().orElseThrow();
        BankState bank = run.decode(snap.accounts()).state();
        sb.append(";reserve=").append(bank.gl(ComposedWorldScenario.BANK, "RESERVE").toPlainString());
        sb.append(";dep=").append(bank.depositOf(ComposedWorldScenario.BANK, "eng-a").toPlainString());
        return sb.toString();
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "null";
        }
        int idx = text.indexOf('\n');
        return idx < 0 ? text : text.substring(0, idx);
    }

    /** 测试专用信道：在指定次数后让 write 或 force 抛 IOException。 */
    static final class FailingChannel extends FileChannel {

        /** 注入位置。 */
        enum Mode {
            /** 写正文时失败（帧未落地）。 */ WRITE,
            /** 写完后 force 失败（字节可能已落地但未确认）。 */ FORCE
        }

        private final FileChannel delegate;
        private final Mode mode;
        private final AtomicInteger armAfter;

        FailingChannel(FileChannel delegate, Mode mode, int armAfter) {
            this.delegate = delegate;
            this.mode = mode;
            this.armAfter = new AtomicInteger(armAfter);
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
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            if (mode == Mode.WRITE && armAfter.getAndDecrement() <= 0) {
                throw new IOException("INJECTED_WRITE_FAILURE");
            }
            return delegate.write(srcs, offset, length);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            if (mode == Mode.WRITE && armAfter.getAndDecrement() <= 0) {
                throw new IOException("INJECTED_WRITE_FAILURE");
            }
            return delegate.write(src, position);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (mode == Mode.WRITE && armAfter.getAndDecrement() <= 0) {
                throw new IOException("INJECTED_WRITE_FAILURE");
            }
            return delegate.write(src);
        }

        @Override
        public void force(boolean metaData) throws IOException {
            if (mode == Mode.FORCE && armAfter.getAndDecrement() <= 0) {
                throw new IOException("INJECTED_FORCE_FAILURE");
            }
            delegate.force(metaData);
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
        public MappedByteBuffer map(MapMode mode2, long position, long size) throws IOException {
            return delegate.map(mode2, position, size);
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
