package edu.cufe.auction.world;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 扩展世界的进程内模拟时序：单写入者同步推进，独立事件使用运行种子确定公平顺序。
 *
 * <p>创建本对象的线程是唯一排程/推进线程；异步结果须交给该线程复核，不能自行排程。
 * 其他线程只能请求暂停、读取当前时间与暂停标志。本类不使用墙钟，不负责持久化。动作须通过权威提交层变更经济状态；
 * 动作失败会暂停并抛出异常，已开始的动作不自动重试，防止重复副作用。</p>
 */
public final class WorldScheduler {

    /** 同一模拟毫秒的固定阶段；声明顺序就是执行顺序。 */
    public enum Phase {
        /** 到期规则、资格和结算。 */
        RULES,
        /** 已排程公开信息和消息投递。 */
        DELIVERY,
        /** 此前已启动动作完成并复核授权。 */
        COMPLETION,
        /** 新观察和角色唤醒。 */
        OBSERVATION
    }

    private record Event(String id, long time, Phase phase, String fairOrder, Runnable action) {}

    private final long seed;
    private final Thread writerThread = Thread.currentThread();
    private final PriorityQueue<Event> pending = new PriorityQueue<>(Comparator
            .comparingLong(Event::time)
            .thenComparing(Event::phase)
            .thenComparing(Event::fairOrder)
            .thenComparing(Event::id));
    private final Set<String> eventIds = new HashSet<>();
    private volatile long now;
    private int completedPhase = -1;
    private boolean advancing;
    private volatile boolean paused;

    /** @param seed 本次运行的公平顺序种子，事件标识也必须稳定 */
    public WorldScheduler(long seed) {
        this.seed = seed;
    }

    /**
     * 安排事件。运行中只能由当前写入线程调用；同刻仅能新增尚未进入的后续阶段。
     *
     * @param eventId 运行内唯一且稳定的事件标识
     * @param timeMillis 非负模拟毫秒
     * @param phase 固定阶段
     * @param action 到期执行的动作
     * @throws IllegalArgumentException 重复标识、过去时间或已经进入/完成的阶段
     */
    public void schedule(String eventId, long timeMillis, Phase phase, Runnable action) {
        requireWriter();
        if (paused) {
            throw new IllegalStateException("世界已暂停，不接受新排程；恢复后须重新复核");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("事件标识不能为空");
        }
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(action, "action");
        if (timeMillis < now || (timeMillis == now && phase.ordinal() <= completedPhase)) {
            throw new IllegalArgumentException("不能将事件安排到过去或已开始的阶段");
        }
        if (!eventIds.add(eventId)) {
            throw new IllegalArgumentException("重复事件标识：" + eventId);
        }
        pending.add(new Event(eventId, timeMillis, phase, fairOrder(eventId), action));
    }

    /**
     * 安排本次动作产生的新消息，最早下一模拟毫秒投递。
     *
     * @param eventId 唯一投递标识
     * @param channelDelayMillis 渠道延迟，非负；0 仍须等待 1 毫秒
     * @param action 投递提交动作，投递不等于阅读
     * @throws ArithmeticException 投递时间超出 long 范围
     */
    public void scheduleDelivery(String eventId, long channelDelayMillis, Runnable action) {
        requireWriter();
        if (channelDelayMillis < 0) {
            throw new IllegalArgumentException("渠道延迟不能为负");
        }
        schedule(eventId, Math.addExact(now, Math.max(1L, channelDelayMillis)), Phase.DELIVERY, action);
    }

    /**
     * 同步执行到指定时点（含）；暂停时保留队列和当前时间。不能递归推进。
     *
     * @param inclusiveMillis 目标模拟毫秒，不得早于当前时间
     * @throws RuntimeException 动作异常，暂停并向调用者报告；不自动重试该动作
     */
    public void runUntil(long inclusiveMillis) {
        requireWriter();
        if (advancing) {
            throw new IllegalStateException("世界时间不能递归推进");
        }
        if (inclusiveMillis < now) {
            throw new IllegalArgumentException("世界时间不能倒退");
        }
        advancing = true;
        try {
            while (!paused && !pending.isEmpty() && pending.peek().time() <= inclusiveMillis) {
                Event event = pending.remove();
                if (event.time() != now) {
                    completedPhase = -1;
                }
                now = event.time();
                completedPhase = event.phase().ordinal();
                event.action().run();
            }
            if (!paused) {
                now = inclusiveMillis;
                completedPhase = Phase.OBSERVATION.ordinal();
            }
        } catch (RuntimeException | Error failure) {
            paused = true;
            throw failure;
        } finally {
            advancing = false;
        }
    }

    /** 请求在当前动作结束后暂停；不撤销已经合法提交的动作结果。 */
    public void pause() {
        paused = true;
    }

    /** 恢复推进许可；失败动作的重建由上层可靠提交协议处理。 */
    public void resume() {
        requireWriter();
        paused = false;
    }

    /** @return 当前模拟毫秒，与墙钟无关 */
    public long nowMillis() {
        return now;
    }

    /** @return 尚未开始执行的事件数；仅世界写入线程可查询队列 */
    public int pendingCount() {
        requireWriter();
        return pending.size();
    }

    /** @return 是否暂停 */
    public boolean isPaused() {
        return paused;
    }

    private void requireWriter() {
        if (Thread.currentThread() != writerThread) {
            throw new IllegalStateException("只有创建调度器的世界写入线程可以排程、恢复或推进");
        }
    }

    static String fairOrder(long seed, String eventId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((seed + ":" + eventId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行环境缺少 SHA-256", impossible);
        }
    }

    private String fairOrder(String eventId) {
        return fairOrder(seed, eventId);
    }
}
