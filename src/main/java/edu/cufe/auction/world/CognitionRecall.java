package edu.cufe.auction.world;

import edu.cufe.auction.world.InformationModel.CloseReason;
import edu.cufe.auction.world.InformationModel.ClosedReading;
import edu.cufe.auction.world.InformationModel.ReadObservation;
import edu.cufe.auction.world.InformationModel.State;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * M4f 已有经历的有界回忆纯核。
 *
 * <p>本类只读取真实 {@link InformationModel.State}、人物标识、模拟时点与实验
 * {@link Policy}；不拥有第二份文档或经历状态，不写 JSON、不接世界、不修改输入。
 * 候选只包括<b>本人已完成阅读</b>且资料发布时刻与完成时刻都不晚于查询时点的经历；
 * 目录、未完成/取消/拒绝阅读、他人经历均不进入。离职后的已读经历仍然保留。</p>
 *
 * <p><b>分数</b>：从最近一次实际完成阅读起，每经过一个完整 {@code halfLifeMillis}，
 * 将起始分数 1000 整除 2，降到 0 即停止；只用非负 long 差值，不用浮点或移位。
 * 先按阈值过滤，再按分数降序、{@code lastReadAt} 降序、
 * docId/version/readId 升序稳定排序。</p>
 *
 * <p><b>预算</b>：整条经历装入，不静默截断正文。单条正文成本为
 * {@code title.length + body.length + publicSourceDescription.length}（UTF-16 字符），
 * 以 long 求和；超出单条预算时跳过并继续尝试后续更短条目。条数或正文预算不足都会标记
 * {@link Result#budgetTruncated()}。该预算只约束回忆正文，不代表完整模型输入 JSON/token 预算已实现。</p>
 *
 * <p><b>不输出</b>：Document、{@code internalSourceIds}、其哈希或隐藏独立源数量；结果只含原
 * {@link ReadObservation} 与分数。</p>
 */
public final class CognitionRecall {

    /** 分数起点。 */
    public static final int FULL_SCORE = 1_000;

    private CognitionRecall() {
    }

    /**
     * 回忆参数：明确实验值，不提供“现实最佳”默认值。
     *
     * @param halfLifeMillis    正半衰期（模拟毫秒）
     * @param thresholdPermille 可回忆阈值 1..1000（分数低于它视为已遗忘）
     * @param maxItems          最多返回条数 1..100
     * @param maxContentChars   回忆正文总预算 1..1000000（UTF-16 字符）
     */
    public record Policy(long halfLifeMillis, int thresholdPermille, int maxItems,
                         long maxContentChars) {

        /** 校验参数范围。 */
        public Policy {
            if (halfLifeMillis < 1) {
                throw new IllegalArgumentException("半衰期必须为正毫秒：" + halfLifeMillis);
            }
            if (thresholdPermille < 1 || thresholdPermille > FULL_SCORE) {
                throw new IllegalArgumentException("阈值必须在 1..1000：" + thresholdPermille);
            }
            if (maxItems < 1 || maxItems > 100) {
                throw new IllegalArgumentException("条数预算必须在 1..100：" + maxItems);
            }
            if (maxContentChars < 1 || maxContentChars > 1_000_000L) {
                throw new IllegalArgumentException("正文预算必须在 1..1000000："
                        + maxContentChars);
            }
        }
    }

    /**
     * 一条被选中的回忆：原始经历、衰减分数与最近一次实际完成阅读。
     *
     * @param observation 原 ReadObservation（正文/引用/公开来源描述保持原样）
     * @param score       0..1000 的衰减分数
     * @param lastReadId  该人物/doc/version 最近一次 COMPLETED 阅读标识
     * @param lastReadAt  该次实际完成的模拟时刻
     */
    public record Item(ReadObservation observation, int score, String lastReadId,
                       long lastReadAt) {

        /** 校验字段。 */
        public Item {
            Objects.requireNonNull(observation, "经历");
            if (score < 0 || score > FULL_SCORE) {
                throw new IllegalArgumentException("分数必须在 0..1000：" + score);
            }
            if (lastReadId == null || lastReadId.isBlank()) {
                throw new IllegalArgumentException("最近阅读标识不能为空");
            }
            if (lastReadAt < observation.completedAt()) {
                throw new IllegalArgumentException("最近阅读时刻不能早于首次完成");
            }
        }
    }

    /**
     * 不可变回忆结果。
     *
     * @param selected        按稳定顺序选中的条目
     * @param eligibleCount   本人截至时点通过阈值的经历数（含被预算截断的）
     * @param forgottenCount  本人截至时点低于阈值的经历数
     * @param budgetTruncated 是否因条数或正文预算未装入全部 eligible
     * @param contentChars    selected 的正文成本总和（UTF-16 字符）
     */
    public record Result(List<Item> selected, int eligibleCount, int forgottenCount,
                         boolean budgetTruncated, long contentChars) {

        /** 冻结列表并校验计数。 */
        public Result {
            selected = List.copyOf(Objects.requireNonNull(selected, "选中条目"));
            if (eligibleCount < 0 || forgottenCount < 0 || contentChars < 0) {
                throw new IllegalArgumentException("计数不能为负");
            }
            if (eligibleCount < selected.size()) {
                throw new IllegalArgumentException("eligibleCount 不能小于 selected");
            }
            long sum = 0;
            for (Item item : selected) {
                sum += cost(item.observation());
            }
            if (sum != contentChars) {
                throw new IllegalArgumentException("contentChars 与选中条目成本不一致");
            }
        }
    }

    /**
     * 回忆本人已完成阅读的经历。
     *
     * @param state         真实信息状态
     * @param personPartyId 查询人物
     * @param nowMillis     查询模拟时点，不可为负
     * @param policy        实验参数
     * @return 不可变回忆结果；未登记人物返回空结果
     * @throws IllegalArgumentException 参数为空、人物空白、时点为负或 Policy 非法
     */
    public static Result recall(State state, String personPartyId, long nowMillis, Policy policy) {
        if (state == null) {
            throw new IllegalArgumentException("信息状态不能为空");
        }
        if (policy == null) {
            throw new IllegalArgumentException("回忆参数不能为空");
        }
        if (personPartyId == null || personPartyId.isBlank()) {
            throw new IllegalArgumentException("人物不能为空");
        }
        if (nowMillis < 0) {
            throw new IllegalArgumentException("查询时点不能为负：" + nowMillis);
        }
        if (!state.persons().contains(personPartyId)) {
            return new Result(List.of(), 0, 0, false, 0);
        }

        List<Item> eligible = new ArrayList<>();
        int forgotten = 0;
        for (ReadObservation observation : state.observations()) {
            if (!observation.personPartyId().equals(personPartyId)) {
                continue;
            }
            if (observation.publishedAt() > nowMillis || observation.completedAt() > nowMillis) {
                // 未来条目完全不可见：不参与计数、截断或排序
                continue;
            }
            ClosedReading lastRead = lastCompleted(state, observation, nowMillis);
            long lastReadAt = lastRead == null ? observation.completedAt() : lastRead.closedAt();
            String lastReadId = lastRead == null ? observation.readId() : lastRead.readId();
            int score = scoreOf(lastReadAt, nowMillis, policy.halfLifeMillis());
            if (score < policy.thresholdPermille()) {
                forgotten++;
                continue;
            }
            eligible.add(new Item(observation, score, lastReadId, lastReadAt));
        }
        eligible.sort(CognitionRecall::compare);

        List<Item> selected = new ArrayList<>();
        long contentChars = 0;
        boolean truncated = false;
        for (Item item : eligible) {
            if (selected.size() >= policy.maxItems()) {
                truncated = true;
                continue;
            }
            long itemCost = cost(item.observation());
            if (contentChars + itemCost > policy.maxContentChars()) {
                truncated = true;
                continue;
            }
            selected.add(item);
            contentChars += itemCost;
        }
        return new Result(selected, eligible.size(), forgotten, truncated, contentChars);
    }

    /**
     * 取该人物/doc/version 最近一条实际 COMPLETED 且 {@code closedAt<=now} 的阅读。
     *
     * <p>未来重读、CANCELLED 与 DENIED 都不刷新；并列完成时刻取 readId 字典序最小，
     * 保证同一 State 的稳定选择。</p>
     */
    private static ClosedReading lastCompleted(State state, ReadObservation observation,
                                               long nowMillis) {
        ClosedReading last = null;
        for (ClosedReading closed : state.closed()) {
            if (!closed.personPartyId().equals(observation.personPartyId())
                    || !closed.docId().equals(observation.docId())
                    || closed.version() != observation.version()
                    || closed.reason() != CloseReason.COMPLETED
                    || closed.closedAt() > nowMillis) {
                continue;
            }
            if (last == null || closed.closedAt() > last.closedAt()
                    || (closed.closedAt() == last.closedAt()
                            && closed.readId().compareTo(last.readId()) < 0)) {
                last = closed;
            }
        }
        return last;
    }

    /**
     * 衰减分数：每完整半衰期整除 2，到 0 停止。
     *
     * @param completedAt    完成时刻（不晚于 now）
     * @param now            查询时刻
     * @param halfLifeMillis 正半衰期
     * @return 0..1000
     */
    static int scoreOf(long completedAt, long now, long halfLifeMillis) {
        long elapsed = now - completedAt;
        int score = FULL_SCORE;
        while (elapsed >= halfLifeMillis && score > 0) {
            elapsed -= halfLifeMillis;
            score /= 2;
        }
        return score;
    }

    /** 单条正文成本（UTF-16 字符）。 */
    static long cost(ReadObservation observation) {
        return (long) observation.title().length() + observation.body().length()
                + observation.publicSourceDescription().length();
    }

    /** 稳定顺序：分数降序、最近完成时刻降序、docId/version/readId 升序。 */
    private static int compare(Item left, Item right) {
        int byScore = Integer.compare(right.score(), left.score());
        if (byScore != 0) {
            return byScore;
        }
        int byTime = Long.compare(right.lastReadAt(), left.lastReadAt());
        if (byTime != 0) {
            return byTime;
        }
        int byDoc = left.observation().docId().compareTo(right.observation().docId());
        if (byDoc != 0) {
            return byDoc;
        }
        int byVersion = Long.compare(left.observation().version(),
                right.observation().version());
        if (byVersion != 0) {
            return byVersion;
        }
        return left.lastReadId().compareTo(right.lastReadId());
    }
}
