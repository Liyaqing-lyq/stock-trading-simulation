package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 世界人物决定的不可变纯核：冻结观察输入、提交屏障、尝试身份、预算预留与结算。
 *
 * <p>本类只做纯计算，不做 I/O、不持有线程或队列、不写日志，也不持有任何钱股。经济效果由运行桥
 * 在同一已确认批次内交给人物会话和原世界账；本类产出的只是候选状态与可分类结果。</p>
 *
 * <p><b>时间口径</b>：决定点由调用方按世界时点给出；提交屏障等于决定点加按唤醒原因冻结的思考耗时，
 * 严格晚于决定点。动作耗时由工具合同决定，故动作到期等于屏障加工具耗时，与模型输出无关。任何加法
 * 溢出都拒绝，不回绕。</p>
 *
 * <p><b>预算口径</b>：每次尝试先按冻结的单次上限预留，取得回执后按声明用量结算，差额自动释放。
 * 未知用量的失败按预留保守结算一次；实验费用与人物资产完全分开，当前 scripted 提供方费用恒 0.00 元。</p>
 *
 * <p><b>恢复口径</b>：尝试绑定运行、动作、尝试与恢复代次。重开后必须先 {@link #beginSession} 才能派发或
 * 接受结果；旧代次未消费的尝试只能由可信宿主调用 {@link #resumeDecision} 保守结算并按原输入重试，
 * 旧回执本身不能触发重试。已记录决定不再重问。</p>
 */
public final class DecisionModel {

    /** 本单唯一启用的提供方种类。 */
    public static final String PROVIDER_SCRIPTED = "SCRIPTED";
    /** 唯一不排业务动作的内建工具。 */
    public static final String TOOL_WAIT = "role.wait";

    /** 当前 session 未开启。 */
    public static final String REASON_SESSION_NOT_OPEN = "SESSION_NOT_OPEN";
    /** session 已开启。 */
    public static final String REASON_SESSION_ALREADY_OPEN = "SESSION_ALREADY_OPEN";
    /** 回执或请求不属于本次运行。 */
    public static final String REASON_RUN_MISMATCH = "RUN_MISMATCH";
    /** 同一动作标识对应不同冻结输入。 */
    public static final String REASON_ACTION_ID_CONFLICT = "ACTION_ID_CONFLICT";
    /** 唤醒原因没有冻结的思考耗时。 */
    public static final String REASON_UNKNOWN_WAKE_REASON = "UNKNOWN_WAKE_REASON";
    /** 观察截止晚于已确认位置或时点。 */
    public static final String REASON_FUTURE_OBSERVATION = "FUTURE_OBSERVATION";
    /** 输入超过冻结上限。 */
    public static final String REASON_INPUT_TOO_LARGE = "INPUT_TOO_LARGE";
    /** 回执超过冻结上限。 */
    public static final String REASON_REPLY_TOO_LARGE = "REPLY_TOO_LARGE";
    /** 工具不在冻结动作耗时合同内。 */
    public static final String REASON_UNKNOWN_TOOL = "UNKNOWN_TOOL";
    /** 时间加法溢出。 */
    public static final String REASON_TIME_OVERFLOW = "TIME_OVERFLOW";
    /** 预算不足，未发起调用。 */
    public static final String REASON_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    /** 已消费的尝试身份被不同正文或用量复用。 */
    public static final String REASON_IDENTITY_REUSE = "IDENTITY_REUSE";
    /** 决定已记录。 */
    public static final String REASON_ALREADY_RECORDED = "ALREADY_RECORDED";
    /** 决定已过期。 */
    public static final String REASON_ALREADY_EXPIRED = "ALREADY_EXPIRED";
    /** 尝试已结算。 */
    public static final String REASON_ALREADY_SETTLED = "ALREADY_SETTLED";
    /** 最后未结算尝试不属于旧代次。 */
    public static final String REASON_NOT_STALE = "NOT_STALE";
    /** 回执声明的代次与尝试不一致。 */
    public static final String REASON_GENERATION_MISMATCH = "GENERATION_MISMATCH";
    /** 提供方声明的用量超过预留。 */
    public static final String REASON_USAGE_EXCEEDS_RESERVATION = "USAGE_EXCEEDS_RESERVATION";
    /** 提供方声明的调用次数不是 1。 */
    public static final String REASON_USAGE_ATTEMPTS_INVALID = "USAGE_ATTEMPTS_INVALID";
    /** 提供方声明的费用不是 0.00。 */
    public static final String REASON_USAGE_COST_INVALID = "USAGE_COST_INVALID";
    /** 未知用量按预留保守结算。 */
    public static final String REASON_UNKNOWN_USAGE = "UNKNOWN_USAGE";
    /** 重试预算不足。 */
    public static final String REASON_NO_RETRY_BUDGET = "NO_RETRY_BUDGET";
    /** 回执外壳不是允许的 JSON 对象。 */
    public static final String REASON_BAD_REPLY_SHELL = "BAD_REPLY_SHELL";
    /** 观望回执携带参数。 */
    public static final String REASON_WAIT_WITH_ARGS = "WAIT_WITH_ARGS";

    /** 静态工具参数（DTO）校验失败：属技术失败，不是观望。 */
    public static final String REASON_TOOL_ARGS_INVALID = "TOOL_ARGS_INVALID";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private static final String ATTEMPT_PREFIX = "ATT-";
    private static final String DIGEST_DOMAIN = "decision-input-v1";

    private DecisionModel() {
    }

    /** 决定的状态；到期与预算耗尽都落 {@link #EXPIRED}。 */
    public enum Status {
        /** 尚未取得可用回执，可以继续尝试。 */
        OPEN,
        /** 已记录决定；WAIT 不再排动作，INTENT 排一次业务动作。 */
        RECORDED,
        /** 不再产生尝试（重试用尽、技术失败或预算耗尽）。 */
        EXPIRED
    }

    /** 决定的分类结果。 */
    public enum Outcome {
        /** 尚无回执。 */
        NONE,
        /** 模型明确观望。 */
        WAIT,
        /** 模型给出可排程的业务意图。 */
        INTENT,
        /** 技术失败：外壳、工具、用量、超时或溢出。 */
        TECHNICAL_FAILED,
        /** 预算不足，未发起调用。 */
        BUDGET_EXHAUSTED
    }

    /** 纯核对外裁决。 */
    public enum Verdict {
        /** 已产生候选状态。 */
        ACCEPTED,
        /** 同一请求或同一回执重复，状态不变。 */
        DUPLICATE,
        /** 旧代次未消费的尝试，只归档不结算。 */
        STALE_GENERATION,
        /** 未知动作或尝试。 */
        UNKNOWN,
        /** 静态或状态冲突拒绝。 */
        REJECTED,
        /** 预算不足，未发起调用。 */
        BUDGET_EXHAUSTED
    }

    /** 用量与费用；费用单位元，固定两位小数。 */
    public record Usage(long attempts, long inputTokens, long outputTokens, BigDecimal cost) {

        /** 校验非负并规范费用到两位小数。 */
        public Usage {
            if (attempts < 0 || inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("用量不能为负：" + attempts + "/" + inputTokens
                        + "/" + outputTokens);
            }
            Objects.requireNonNull(cost, "费用");
            if (cost.signum() < 0) {
                throw new IllegalArgumentException("费用不能为负：" + cost);
            }
            try {
                cost = cost.setScale(2, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException notTwoDecimals) {
                throw new IllegalArgumentException("费用必须精确到分：" + cost, notTwoDecimals);
            }
        }

        /**
         * 逐项相加。
         *
         * @param other 另一份用量
         * @return 相加结果
         * @throws ArithmeticException 计数溢出
         */
        public Usage plus(Usage other) {
            return new Usage(Math.addExact(attempts, other.attempts),
                    Math.addExact(inputTokens, other.inputTokens),
                    Math.addExact(outputTokens, other.outputTokens), cost.add(other.cost));
        }

        /**
         * 逐项相减；结果不得为负。
         *
         * @param other 被减去的用量
         * @return 差额
         * @throws IllegalArgumentException 任一维度不足
         */
        public Usage minus(Usage other) {
            if (!covers(other)) {
                throw new IllegalArgumentException("用量差额为负");
            }
            return new Usage(attempts - other.attempts, inputTokens - other.inputTokens,
                    outputTokens - other.outputTokens, cost.subtract(other.cost));
        }

        /**
         * 是否逐项不小于另一份用量。
         *
         * @param other 另一份用量
         * @return 每一项都足够时返回 true
         */
        public boolean covers(Usage other) {
            return attempts >= other.attempts && inputTokens >= other.inputTokens
                    && outputTokens >= other.outputTokens && cost.compareTo(other.cost) >= 0;
        }

        /** @return 零用量，费用 0.00 */
        public static Usage zero() {
            return new Usage(0, 0, 0, new BigDecimal("0.00"));
        }
    }

    /** 冻结的运行限制。 */
    public record Limits(int maxAttemptsPerDecision, long maxInputChars, long maxReplyChars,
                         long perCallInputTokens, long perCallOutputTokens, int queueCapacity,
                         int maxInFlight, long wallTimeoutMillis) {

        /** 校验限制为正或非负。 */
        public Limits {
            if (maxAttemptsPerDecision < 1) {
                throw new IllegalArgumentException("每次决定至少允许一次尝试");
            }
            if (maxInputChars < 1 || maxReplyChars < 1) {
                throw new IllegalArgumentException("输入与回执上限必须为正");
            }
            if (perCallInputTokens < 0 || perCallOutputTokens < 0) {
                throw new IllegalArgumentException("单次 token 上限不能为负");
            }
            if (queueCapacity < 1 || maxInFlight < 1 || wallTimeoutMillis < 1) {
                throw new IllegalArgumentException("队列、并发与墙钟超时必须为正");
            }
        }
    }

    /** 冻结的预算：运行上限与逐人上限。 */
    public record Budget(Usage runCap, Map<String, Usage> personCap) {

        /** 冻结逐人上限。 */
        public Budget {
            Objects.requireNonNull(runCap, "运行预算");
            Map<String, Usage> caps = new TreeMap<>();
            if (personCap != null) {
                personCap.forEach((person, cap) -> {
                    if (person == null || person.isBlank()) {
                        throw new IllegalArgumentException("预算人物不能为空");
                    }
                    caps.put(person, Objects.requireNonNull(cap, "个人预算"));
                });
            }
            personCap = Collections.unmodifiableMap(caps);
        }
    }

    /** 冻结配置；由 {@link #open} 一次绑定，后续转换只读 {@link State#config()}。 */
    public record Config(String providerKind, String providerId, String pricingVersion,
                         Map<String, Long> thinkMillisByWake,
                         Map<String, Long> actionMillisByTool,
                         Limits limits, Budget budget) {

        /** 只接受 scripted 提供方与正耗时。 */
        public Config {
            if (!PROVIDER_SCRIPTED.equals(providerKind)) {
                throw new IllegalArgumentException("当前只支持 " + PROVIDER_SCRIPTED + " 提供方，收到 "
                        + providerKind);
            }
            requireText(providerId, "提供方标识");
            requireText(pricingVersion, "计价版本");
            Objects.requireNonNull(limits, "限制");
            Objects.requireNonNull(budget, "预算");
            if (budget.runCap().cost().signum() != 0
                    || budget.personCap().values().stream().anyMatch(cap -> cap.cost().signum() != 0)) {
                throw new IllegalArgumentException("脚本费用预算必须为0.00");
            }

            Map<String, Long> think = new TreeMap<>();
            if (thinkMillisByWake == null || thinkMillisByWake.isEmpty()) {
                throw new IllegalArgumentException("必须冻结按唤醒原因的思考耗时");
            }
            thinkMillisByWake.forEach((wake, millis) -> {
                requireText(wake, "唤醒原因");
                if (millis == null || millis < 1) {
                    throw new IllegalArgumentException("思考耗时必须为正：" + wake + "=" + millis);
                }
                think.put(wake, millis);
            });
            thinkMillisByWake = Collections.unmodifiableMap(think);

            Map<String, Long> action = new TreeMap<>();
            if (actionMillisByTool != null) {
                actionMillisByTool.forEach((tool, millis) -> {
                    requireText(tool, "工具");
                    if (TOOL_WAIT.equals(tool)) {
                        throw new IllegalArgumentException("观望工具不排业务动作，不能配置动作耗时");
                    }
                    if (millis == null || millis < 1) {
                        throw new IllegalArgumentException("动作耗时必须为正：" + tool + "=" + millis);
                    }
                    action.put(tool, millis);
                });
            }
            actionMillisByTool = Collections.unmodifiableMap(action);
        }
    }

    /** 一次调用尝试；未返回时正文与来源为空、结算为零。 */
    public record Attempt(String attemptId, int attemptNo, long recoveryGeneration, String inputDigest,
                          Usage reserved, Usage settled, Usage declaredUsage, boolean replyReceived,
                          String replyBody, String usageSource,
                          Outcome outcome, String reason) {

        /** 校验身份、预留与结算的一致性。 */
        public Attempt {
            requireText(attemptId, "尝试标识");
            if (attemptNo < 1) {
                throw new IllegalArgumentException("尝试序号从 1 开始：" + attemptNo);
            }
            if (recoveryGeneration < 1) {
                throw new IllegalArgumentException("恢复代次从 1 开始：" + recoveryGeneration);
            }
            requireDigest(inputDigest);
            Objects.requireNonNull(reserved, "预留");
            Objects.requireNonNull(settled, "结算");
            if (reserved.attempts() != 1L) {
                throw new IllegalArgumentException("每条尝试只预留一次调用");
            }
            replyBody = replyBody == null ? "" : replyBody;
            usageSource = usageSource == null ? "" : usageSource;
            reason = reason == null ? "" : reason;
            if (settled.attempts() == 0L) {
                if (outcome != Outcome.NONE) {
                    throw new IllegalArgumentException("未结算尝试不能带结果：" + outcome);
                }
                if (!settled.equals(Usage.zero()) || declaredUsage != null || replyReceived
                        || !replyBody.isEmpty() || !usageSource.isEmpty() || !reason.isEmpty()) {
                    throw new IllegalArgumentException("未结算尝试不能带回执正文");
                }
            } else {
                if (settled.attempts() != 1L) {
                    throw new IllegalArgumentException("已结算尝试只能声明一次调用");
                }
                if (outcome == Outcome.NONE) {
                    throw new IllegalArgumentException("已结算尝试必须带结果");
                }
                if (!replyReceived && (declaredUsage != null || !replyBody.isEmpty()
                        || !usageSource.isEmpty() || outcome != Outcome.TECHNICAL_FAILED)) {
                    throw new IllegalArgumentException("未收到回执只能记录无声明用量的技术失败");
                }
            }
        }

        /** @return 是否已经结算 */
        public boolean settledDone() {
            return settled.attempts() == 1L;
        }
    }

    /** 一个已冻结输入的决定；尝试按序号连续追加。 */
    public record Decision(String runId, String actionId, String personPartyId, String wakeReason,
                           long decisionAtMillis, long barrierAtMillis,
                           long cutoffPosition, long cutoffTimeMillis,
                           String toolContractVersion, List<String> allowedTools,
                           String inputText, String inputDigest,
                           Status status, Outcome outcome, String recordedTool,
                           List<Attempt> attempts) {

        /** 校验身份、摘要、尝试连续性与终态。 */
        public Decision {
            requireText(runId, "运行标识");
            requireText(actionId, "动作标识");
            requireText(personPartyId, "人物");
            requireText(wakeReason, "唤醒原因");
            requireText(toolContractVersion, "工具合同版本");
            if (decisionAtMillis < 0) {
                throw new IllegalArgumentException("决定点不能为负");
            }
            if (barrierAtMillis <= decisionAtMillis) {
                throw new IllegalArgumentException("提交屏障必须晚于决定点");
            }
            if (cutoffPosition < 0 || cutoffTimeMillis < 0) {
                throw new IllegalArgumentException("观察截止不能为负");
            }
            if (cutoffTimeMillis > decisionAtMillis) {
                throw new IllegalArgumentException("观察截止不能晚于决定点");
            }
            requireText(inputText, "决定输入");
            requireDigest(inputDigest);

            List<String> tools = normalizeTools(allowedTools);
            allowedTools = tools;
            if (!inputDigest.equals(canonicalDigest(toolContractVersion, tools, inputText))) {
                throw new IllegalArgumentException("输入摘要与冻结输入不一致：" + actionId);
            }

            Objects.requireNonNull(status, "状态");
            Objects.requireNonNull(outcome, "结果");
            recordedTool = recordedTool == null ? "" : recordedTool;
            if (!recordedTool.isEmpty() && !tools.contains(recordedTool)) {
                throw new IllegalArgumentException("已记录工具不在允许集合内：" + recordedTool);
            }
            if (outcome == Outcome.INTENT && TOOL_WAIT.equals(recordedTool)) {
                throw new IllegalArgumentException("观望不能记录为业务工具");
            }

            List<Attempt> copy = List.copyOf(attempts);
            Set<String> ids = new LinkedHashSet<>();
            for (int index = 0; index < copy.size(); index++) {
                Attempt attempt = copy.get(index);
                if (attempt.attemptNo() != index + 1) {
                    throw new IllegalArgumentException("尝试序号不连续：" + actionId);
                }
                if (!ids.add(attempt.attemptId())) {
                    throw new IllegalArgumentException("尝试标识重复：" + attempt.attemptId());
                }
                if (!attempt.inputDigest().equals(inputDigest)) {
                    throw new IllegalArgumentException("尝试输入摘要与决定不一致：" + actionId);
                }
            }
            attempts = copy;

            switch (status) {
                case OPEN -> {
                    if (copy.isEmpty()) {
                        throw new IllegalArgumentException("可派发决定必须有尝试：" + actionId);
                    }
                    if (copy.get(copy.size() - 1).settledDone()) {
                        throw new IllegalArgumentException("OPEN 决定最后尝试必须未结算：" + actionId);
                    }
                }
                case RECORDED -> {
                    if (copy.isEmpty() || !copy.get(copy.size() - 1).settledDone()) {
                        throw new IllegalArgumentException("已记录决定必须有已结算尝试：" + actionId);
                    }
                    if (outcome != Outcome.WAIT && outcome != Outcome.INTENT) {
                        throw new IllegalArgumentException("已记录决定结果非法：" + outcome);
                    }
                    if (outcome == Outcome.INTENT ? recordedTool.isEmpty() : !recordedTool.isEmpty()) {
                        throw new IllegalArgumentException("已记录工具与结果不一致：" + actionId);
                    }
                    if (outcome == Outcome.INTENT) {
                        String bodyTool = bodyTool(lastAttemptOf(copy));
                        if (!recordedTool.equals(bodyTool)) {
                            throw new IllegalArgumentException("已记录工具与回执正文不一致：" + actionId);
                        }
                    }
                }
                case EXPIRED -> {
                    if (!copy.isEmpty() && !copy.get(copy.size() - 1).settledDone()) {
                        throw new IllegalArgumentException("过期决定不能有未结算尝试：" + actionId);
                    }
                    if (outcome != Outcome.TECHNICAL_FAILED && outcome != Outcome.BUDGET_EXHAUSTED) {
                        throw new IllegalArgumentException("过期决定结果非法：" + outcome);
                    }
                    if (!recordedTool.isEmpty()) {
                        throw new IllegalArgumentException("过期决定不能记录工具");
                    }
                }
                default -> throw new IllegalArgumentException("未知状态：" + status);
            }
        }

        /** @return 最后一条尝试 */
        public Attempt lastAttempt() {
            return attempts.get(attempts.size() - 1);
        }
    }

    /** 运行状态：冻结配置、决定、累计用量与已用尝试序号。 */
    public record State(String runId, long seed, Config config, long recoveryGeneration,
                        boolean sessionOpen, List<Decision> decisions, Usage runUsage,
                        Map<String, Usage> personUsage, long nextAttemptSequence) {

        /** 校验运行身份、累计用量与序号。 */
        public State {
            requireText(runId, "运行标识");
            Objects.requireNonNull(config, "配置");
            if (recoveryGeneration < 0) {
                throw new IllegalArgumentException("恢复代次不能为负");
            }
            if (recoveryGeneration == 0 && (sessionOpen || !decisions.isEmpty())) {
                throw new IllegalArgumentException("尚未接续的零代次不能有决定或开放会话");
            }
            List<Decision> copy = List.copyOf(decisions);
            Set<String> actionIds = new LinkedHashSet<>();
            Set<String> attemptIds = new LinkedHashSet<>();
            for (Decision decision : copy) {
                if (!decision.runId().equals(runId)) {
                    throw new IllegalArgumentException("决定不属于本运行：" + decision.actionId());
                }
                if (!actionIds.add(decision.actionId())) {
                    throw new IllegalArgumentException("动作标识重复：" + decision.actionId());
                }
                validateDecision(config, decision, recoveryGeneration);
                for (Attempt attempt : decision.attempts()) {
                    long sequence = sequenceOf(attempt.attemptId());
                    if (!attempt.attemptId().equals(ATTEMPT_PREFIX + sequence)
                            || !attemptIds.add(attempt.attemptId()) || sequence >= nextAttemptSequence) {
                        throw new IllegalArgumentException("尝试序号未纳入计数：" + attempt.attemptId());
                    }
                }
            }
            decisions = copy;

            Map<String, Usage> computedPerson = new TreeMap<>();
            Usage computedRun = Usage.zero();
            for (Decision decision : copy) {
                for (Attempt attempt : decision.attempts()) {
                    if (!attempt.settledDone()) {
                        continue;
                    }
                    computedRun = computedRun.plus(attempt.settled());
                    computedPerson.merge(decision.personPartyId(), attempt.settled(), Usage::plus);
                }
            }
            Objects.requireNonNull(runUsage, "运行用量");
            if (!computedRun.equals(runUsage)) {
                throw new IllegalArgumentException("运行累计用量与尝试结算不一致");
            }
            Map<String, Usage> given = new TreeMap<>();
            if (personUsage != null) {
                personUsage.forEach((person, usage) -> {
                    requireText(person, "用量人物");
                    given.put(person, Objects.requireNonNull(usage, "个人用量"));
                });
            }
            if (!given.equals(computedPerson)) {
                throw new IllegalArgumentException("逐人累计用量与尝试结算不一致");
            }
            personUsage = Collections.unmodifiableMap(given);
            if (nextAttemptSequence < 1) {
                throw new IllegalArgumentException("下一尝试序号必须为正");
            }
            if (nextAttemptSequence != Math.addExact((long) attemptIds.size(), 1L)) {
                throw new IllegalArgumentException("尝试序号必须全局连续且不重复");
            }
            validateBudget(config, copy, computedRun, computedPerson);
        }
    }

    private static void validateDecision(Config config, Decision decision, long generation) {
        Long thinkMillis = config.thinkMillisByWake().get(decision.wakeReason());
        if (thinkMillis == null || decision.inputText().length() > config.limits().maxInputChars()
                || decision.attempts().size() > config.limits().maxAttemptsPerDecision()) {
            throw new IllegalArgumentException("决定不符合冻结的原因、输入或尝试限制");
        }
        try {
            if (Math.addExact(decision.decisionAtMillis(), thinkMillis) != decision.barrierAtMillis()) {
                throw new IllegalArgumentException("决定屏障与冻结思考耗时不一致");
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("决定屏障溢出", overflow);
        }
        for (String tool : decision.allowedTools()) {
            if (!TOOL_WAIT.equals(tool) && !config.actionMillisByTool().containsKey(tool)) {
                throw new IllegalArgumentException("决定包含未配置工具");
            }
        }
        long previousGeneration = 0;
        for (int index = 0; index < decision.attempts().size(); index++) {
            Attempt attempt = decision.attempts().get(index);
            if (attempt.recoveryGeneration() > generation
                    || attempt.recoveryGeneration() < previousGeneration
                    || !attempt.reserved().equals(reservation(config))) {
                throw new IllegalArgumentException("尝试代次或预留与冻结配置不一致");
            }
            previousGeneration = attempt.recoveryGeneration();
            if (index < decision.attempts().size() - 1
                    && (!attempt.settledDone() || attempt.outcome() != Outcome.TECHNICAL_FAILED)) {
                throw new IllegalArgumentException("新尝试只能接续已结算的技术失败");
            }
            if (!attempt.settledDone()) {
                continue;
            }
            Usage expectedUsage;
            try {
                expectedUsage = settleUsage(config, attempt.declaredUsage());
            } catch (ReplyFailure invalidUsage) {
                if (attempt.outcome() != Outcome.TECHNICAL_FAILED) {
                    throw new IllegalArgumentException("非法或未知用量不能形成已记录决定");
                }
                expectedUsage = attempt.reserved();
            }
            if (!expectedUsage.equals(attempt.settled())) {
                throw new IllegalArgumentException("尝试结算与原声明或保守预留不一致");
            }
            if (attempt.outcome() == Outcome.TECHNICAL_FAILED) {
                requireText(attempt.reason(), "技术失败原因");
                continue;
            }
            if ((attempt.outcome() != Outcome.WAIT && attempt.outcome() != Outcome.INTENT)
                    || !attempt.reason().isEmpty()
                    || attempt.replyBody().length() > config.limits().maxReplyChars()) {
                throw new IllegalArgumentException("已记录回执结果或长度不合法");
            }
            try {
                Parsed parsed = parse(config, decision.allowedTools(), attempt.replyBody());
                Outcome expectedOutcome = parsed.abstain() ? Outcome.WAIT : Outcome.INTENT;
                if (attempt.outcome() != expectedOutcome) {
                    throw new IllegalArgumentException("回执正文与尝试结果不一致");
                }
                if (!parsed.abstain()) {
                    actionDue(decision, config, parsed.tool());
                    if (!parsed.tool().equals(decision.recordedTool())) {
                        throw new IllegalArgumentException("回执工具与决定不一致");
                    }
                }
            } catch (ReplyFailure | ArithmeticException invalidBody) {
                throw new IllegalArgumentException("已记录回执违反冻结工具合同", invalidBody);
            }
        }
        if (decision.attempts().isEmpty()) {
            if (decision.status() != Status.EXPIRED || decision.outcome() != Outcome.BUDGET_EXHAUSTED) {
                throw new IllegalArgumentException("零尝试只允许预算耗尽终态");
            }
            return;
        }
        Outcome expectedOutcome = decision.lastAttempt().outcome();
        if (decision.status() == Status.OPEN && decision.attempts().size() > 1) {
            expectedOutcome = Outcome.TECHNICAL_FAILED;
        }
        if (decision.outcome() != expectedOutcome
                || (decision.status() != Status.RECORDED && !decision.recordedTool().isEmpty())) {
            throw new IllegalArgumentException("决定与最后尝试结果不一致");
        }
    }

    private static void validateBudget(Config config, List<Decision> decisions, Usage usedRun,
                                       Map<String, Usage> usedPerson) {
        Usage outstandingRun = Usage.zero();
        Map<String, Usage> outstandingPerson = new TreeMap<>();
        for (Decision decision : decisions) {
            for (Attempt attempt : decision.attempts()) {
                if (!attempt.settledDone()) {
                    outstandingRun = outstandingRun.plus(attempt.reserved());
                    outstandingPerson.merge(decision.personPartyId(), attempt.reserved(), Usage::plus);
                }
            }
        }
        if (!config.budget().runCap().covers(usedRun.plus(outstandingRun))) {
            throw new IllegalArgumentException("运行已用与预留超过预算");
        }
        for (Decision decision : decisions) {
            String person = decision.personPartyId();
            Usage used = usedPerson.getOrDefault(person, Usage.zero());
            Usage reserved = outstandingPerson.getOrDefault(person, Usage.zero());
            if (!config.budget().personCap().getOrDefault(person, Usage.zero()).covers(used.plus(reserved))) {
                throw new IllegalArgumentException("人物已用与预留超过预算");
            }
        }
    }

    /** 开决定的请求；字段全部由可信宿主填充，模型不可见。 */
    public record OpenRequest(String runId, String actionId, String personPartyId, String wakeReason,
                              long decisionAtMillis, long cutoffPosition, long cutoffTimeMillis,
                              String toolContractVersion, List<String> allowedTools,
                              String inputText) {

        /** 校验静态字段。 */
        public OpenRequest {
            requireText(runId, "运行标识");
            requireText(actionId, "动作标识");
            requireText(personPartyId, "人物");
            requireText(wakeReason, "唤醒原因");
            requireText(toolContractVersion, "工具合同版本");
            requireText(inputText, "决定输入");
            if (decisionAtMillis < 0 || cutoffPosition < 0 || cutoffTimeMillis < 0) {
                throw new IllegalArgumentException("决定点与观察截止不能为负");
            }
            normalizeTools(allowedTools);
        }
    }

    /** 提供方回执；尝试身份必须与实际尝试逐项一致。 */
    public record Reply(String runId, String actionId, String attemptId, int attemptNo,
                        long recoveryGeneration, String body, Usage usage, String usageSource) {

        /** 校验字段形态。 */
        public Reply {
            requireText(runId, "运行标识");
            requireText(actionId, "动作标识");
            requireText(attemptId, "尝试标识");
            if (attemptNo < 1) {
                throw new IllegalArgumentException("尝试序号从 1 开始：" + attemptNo);
            }
            if (recoveryGeneration < 1) {
                throw new IllegalArgumentException("恢复代次从 1 开始：" + recoveryGeneration);
            }
            body = body == null ? "" : body;
            usageSource = usageSource == null ? "" : usageSource;
        }
    }

    /** 纯核结果；拒绝时 state 原样返回。 */
    public record Result(Verdict verdict, String reason, State state, Decision decision) {
    }

    // ------------------------------------------------------------------
    // 开局与接续
    // ------------------------------------------------------------------

    /**
     * 开一次运行：冻结配置，处于"尚未接续"状态。
     *
     * @param runId  运行标识
     * @param seed   公平排序种子
     * @param config 冻结配置
     * @return 未接续的初始状态
     */
    public static State open(String runId, long seed, Config config) {
        return new State(runId, seed, config, 0L, false, List.of(), Usage.zero(), Map.of(), 1L);
    }

    /**
     * 重新装载后回到待接续状态；代次不变，也不清除任何已确认决定。
     *
     * @param state 已确认状态
     * @return 关闭 session 的状态
     */
    public static State recover(State state) {
        return new State(state.runId(), state.seed(), state.config(), state.recoveryGeneration(),
                false, state.decisions(), state.runUsage(), state.personUsage(),
                state.nextAttemptSequence());
    }

    /**
     * 开启新恢复代次；只有开启后才能派发与接受结果。
     *
     * @param state 当前状态
     * @return 新代次状态
     */
    public static Result beginSession(State state) {
        if (state.sessionOpen()) {
            return reject(Verdict.REJECTED, REASON_SESSION_ALREADY_OPEN, state);
        }
        State next = new State(state.runId(), state.seed(), state.config(),
                Math.addExact(state.recoveryGeneration(), 1L), true, state.decisions(),
                state.runUsage(), state.personUsage(), state.nextAttemptSequence());
        return accept(next, null);
    }

    // ------------------------------------------------------------------
    // 决定与回执
    // ------------------------------------------------------------------

    /**
     * 调用前开一个决定：冻结观察截止、完整输入、工具合同、预算预留与最早提交屏障。
     *
     * @param state                 当前状态
     * @param request               可信宿主组装的请求
     * @param confirmedPosition     已确认世界位置
     * @param confirmedTimeMillis   已确认世界时点
     * @return 开决定结果；预算不足时产生零尝试的过期决定
     */
    public static Result openDecision(State state, OpenRequest request, long confirmedPosition,
                                      long confirmedTimeMillis) {
        if (!state.sessionOpen()) {
            return reject(Verdict.REJECTED, REASON_SESSION_NOT_OPEN, state);
        }
        if (!state.runId().equals(request.runId())) {
            return reject(Verdict.REJECTED, REASON_RUN_MISMATCH, state);
        }
        Decision existing = find(state, request.actionId());
        if (existing != null) {
            if (sameFrozenInput(existing, request)) {
                return new Result(Verdict.DUPLICATE, "", state, existing);
            }
            return reject(Verdict.REJECTED, REASON_ACTION_ID_CONFLICT, state);
        }

        Config config = state.config();
        Long think = config.thinkMillisByWake().get(request.wakeReason());
        if (think == null) {
            return reject(Verdict.REJECTED, REASON_UNKNOWN_WAKE_REASON, state);
        }
        if (request.decisionAtMillis() > confirmedTimeMillis
                || request.cutoffTimeMillis() > request.decisionAtMillis()
                || request.cutoffTimeMillis() > confirmedTimeMillis
                || request.cutoffPosition() > confirmedPosition) {
            return reject(Verdict.REJECTED, REASON_FUTURE_OBSERVATION, state);
        }
        if (request.inputText().length() > config.limits().maxInputChars()) {
            return reject(Verdict.REJECTED, REASON_INPUT_TOO_LARGE, state);
        }
        List<String> tools = normalizeTools(request.allowedTools());
        for (String tool : tools) {
            if (!TOOL_WAIT.equals(tool) && !config.actionMillisByTool().containsKey(tool)) {
                return reject(Verdict.REJECTED, REASON_UNKNOWN_TOOL, state);
            }
        }
        long barrier;
        try {
            barrier = Math.addExact(request.decisionAtMillis(), think);
        } catch (ArithmeticException overflow) {
            return reject(Verdict.REJECTED, REASON_TIME_OVERFLOW, state);
        }
        String digest = canonicalDigest(request.toolContractVersion(), tools, request.inputText());

        Usage reserved = reservation(config);
        if (!headroom(state, request.personPartyId()).covers(reserved)) {
            Decision blocked = new Decision(state.runId(), request.actionId(),
                    request.personPartyId(), request.wakeReason(), request.decisionAtMillis(),
                    barrier, request.cutoffPosition(), request.cutoffTimeMillis(),
                    request.toolContractVersion(), tools, request.inputText(), digest,
                    Status.EXPIRED, Outcome.BUDGET_EXHAUSTED, "", List.of());
            State next = append(state, blocked);
            return new Result(Verdict.BUDGET_EXHAUSTED, REASON_BUDGET_EXHAUSTED, next, blocked);
        }

        Attempt attempt = new Attempt(ATTEMPT_PREFIX + state.nextAttemptSequence(), 1,
                state.recoveryGeneration(), digest, reserved, Usage.zero(), null, false,
                "", "", Outcome.NONE, "");
        Decision decision = new Decision(state.runId(), request.actionId(), request.personPartyId(),
                request.wakeReason(), request.decisionAtMillis(), barrier,
                request.cutoffPosition(), request.cutoffTimeMillis(),
                request.toolContractVersion(), tools, request.inputText(), digest,
                Status.OPEN, Outcome.NONE, "", List.of(attempt));
        State next = append(state, decision, Math.addExact(state.nextAttemptSequence(), 1L));
        return accept(next, decision);
    }

    /**
     * 接受一次提供方回执：先结算，再按结果记录决定或进入有界重试。
     *
     * @param state 当前状态
     * @param reply 提供方回执
     * @return 处理结果
     */
    public static Result submitReply(State state, Reply reply) {
        if (!state.sessionOpen()) {
            return reject(Verdict.REJECTED, REASON_SESSION_NOT_OPEN, state);
        }
        if (!state.runId().equals(reply.runId())) {
            return reject(Verdict.REJECTED, REASON_RUN_MISMATCH, state);
        }
        Decision decision = find(state, reply.actionId());
        if (decision == null) {
            return reject(Verdict.UNKNOWN, "UNKNOWN_DECISION", state);
        }
        Attempt attempt = findAttempt(decision, reply.attemptId());
        if (attempt == null) {
            return reject(Verdict.UNKNOWN, "UNKNOWN_ATTEMPT", state);
        }
        if (attempt.attemptNo() != reply.attemptNo()) {
            return reject(Verdict.UNKNOWN, "UNKNOWN_ATTEMPT", state);
        }
        if (reply.recoveryGeneration() != attempt.recoveryGeneration()) {
            return reject(Verdict.REJECTED, REASON_GENERATION_MISMATCH, state);
        }
        if (attempt.settledDone()) {
            boolean same = attempt.replyReceived() && attempt.replyBody().equals(reply.body())
                    && Objects.equals(attempt.declaredUsage(), reply.usage())
                    && attempt.usageSource().equals(reply.usageSource());
            return same
                    ? new Result(Verdict.DUPLICATE, "", state, decision)
                    : reject(Verdict.REJECTED, REASON_IDENTITY_REUSE, state);
        }
        if (decision.status() != Status.OPEN) {
            return reject(Verdict.REJECTED, decision.status() == Status.RECORDED
                    ? REASON_ALREADY_RECORDED : REASON_ALREADY_EXPIRED, state);
        }
        if (attempt.recoveryGeneration() != state.recoveryGeneration()) {
            return new Result(Verdict.STALE_GENERATION, "OLD_GENERATION", state, decision);
        }

        Config config = state.config();
        if (reply.body().length() > config.limits().maxReplyChars()) {
            return settleTechnical(state, decision, attempt, reply, REASON_REPLY_TOO_LARGE, true);
        }
        Outcome outcome;
        String recordedTool;
        try {
            Parsed parsed = parse(config, decision.allowedTools(), reply.body());
            outcome = parsed.abstain() ? Outcome.WAIT : Outcome.INTENT;
            recordedTool = parsed.abstain() ? "" : parsed.tool();
            if (!parsed.abstain()) {
                // 记录前先验证动作到期可计算且不溢出；溢出按技术失败处理，不留下无到期时间的记录。
                actionDue(decision, config, parsed.tool());
            }
        } catch (ReplyFailure failure) {
            return settleTechnical(state, decision, attempt, reply, failure.reason, true);
        } catch (ArithmeticException overflow) {
            return settleTechnical(state, decision, attempt, reply, REASON_TIME_OVERFLOW, true);
        }
        Usage settled;
        try {
            settled = settleUsage(config, reply.usage());
        } catch (ReplyFailure failure) {
            return settleTechnical(state, decision, attempt, reply, failure.reason, true);
        }

        Attempt done = new Attempt(attempt.attemptId(), attempt.attemptNo(),
                attempt.recoveryGeneration(), attempt.inputDigest(), attempt.reserved(), settled,
                reply.usage(), true, reply.body(), reply.usageSource(), outcome, "");
        Decision recorded = new Decision(decision.runId(), decision.actionId(),
                decision.personPartyId(), decision.wakeReason(), decision.decisionAtMillis(),
                decision.barrierAtMillis(), decision.cutoffPosition(), decision.cutoffTimeMillis(),
                decision.toolContractVersion(), decision.allowedTools(), decision.inputText(),
                decision.inputDigest(), Status.RECORDED, outcome, recordedTool,
                replaceAttempt(decision.attempts(), done));
        State next = append(state, recorded);
        return accept(next, recorded);
    }

    /**
     * 记录一次调用失败（无回执）：按预留保守结算，并在允许时用原输入与屏障重试。
     *
     * @param state             当前状态
     * @param actionId          动作标识
     * @param attemptNo         尝试序号
     * @param attemptId         尝试标识
     * @param recoveryGeneration 回执所属代次
     * @param reason            失败原因
     * @return 处理结果
     */
    public static Result failAttempt(State state, String actionId, int attemptNo, String attemptId,
                                     long recoveryGeneration, String reason) {
        if (!state.sessionOpen()) {
            return reject(Verdict.REJECTED, REASON_SESSION_NOT_OPEN, state);
        }
        Decision decision = find(state, actionId);
        if (decision == null) {
            return reject(Verdict.UNKNOWN, "UNKNOWN_DECISION", state);
        }
        Attempt attempt = findAttempt(decision, attemptId);
        if (attempt == null || attempt.attemptNo() != attemptNo) {
            return reject(Verdict.UNKNOWN, "UNKNOWN_ATTEMPT", state);
        }
        if (decision.status() == Status.RECORDED) {
            return reject(Verdict.REJECTED, REASON_ALREADY_RECORDED, state);
        }
        if (decision.status() == Status.EXPIRED) {
            return reject(Verdict.REJECTED, REASON_ALREADY_EXPIRED, state);
        }
        if (attempt.settledDone()) {
            return reject(Verdict.REJECTED, REASON_ALREADY_SETTLED, state);
        }
        if (attempt.recoveryGeneration() != state.recoveryGeneration()) {
            return new Result(Verdict.STALE_GENERATION, "OLD_GENERATION", state, decision);
        }
        if (attempt.recoveryGeneration() != recoveryGeneration) {
            return reject(Verdict.REJECTED, REASON_GENERATION_MISMATCH, state);
        }
        String detail = reason == null ? "" : reason;
        return settleTechnical(state, decision, attempt, null,
                REASON_UNKNOWN_USAGE + ":" + detail, false);
    }

    /**
     * 可信宿主以原未消费状态记录一次**已知用量**的技术失败：保留原回执正文、来源与声明用量，
     * 按原输入与屏障有界重试。用于工具参数等静态合同在解析期就失败、但提供方已真实回执的场合。
     *
     * @param state  当前状态
     * @param reply  原提供方回执（正文、声明用量与来源）
     * @param reason 技术失败原因
     * @return 处理结果；同一回执再送回为 {@link Verdict#DUPLICATE}
     */
    public static Result rejectReply(State state, Reply reply, String reason) {
        if (!state.sessionOpen()) {
            return reject(Verdict.REJECTED, REASON_SESSION_NOT_OPEN, state);
        }
        if (!state.runId().equals(reply.runId())) {
            return reject(Verdict.REJECTED, REASON_RUN_MISMATCH, state);
        }
        Decision decision = find(state, reply.actionId());
        if (decision == null) {
            return reject(Verdict.UNKNOWN, "UNKNOWN_DECISION", state);
        }
        Attempt attempt = findAttempt(decision, reply.attemptId());
        if (attempt == null || attempt.attemptNo() != reply.attemptNo()) {
            return reject(Verdict.UNKNOWN, "UNKNOWN_ATTEMPT", state);
        }
        if (reply.recoveryGeneration() != attempt.recoveryGeneration()) {
            return reject(Verdict.REJECTED, REASON_GENERATION_MISMATCH, state);
        }
        if (attempt.settledDone()) {
            boolean same = attempt.replyReceived() && attempt.replyBody().equals(reply.body())
                    && Objects.equals(attempt.declaredUsage(), reply.usage())
                    && attempt.usageSource().equals(reply.usageSource());
            return same
                    ? new Result(Verdict.DUPLICATE, "", state, decision)
                    : reject(Verdict.REJECTED, REASON_IDENTITY_REUSE, state);
        }
        if (decision.status() != Status.OPEN) {
            return reject(Verdict.REJECTED, decision.status() == Status.RECORDED
                    ? REASON_ALREADY_RECORDED : REASON_ALREADY_EXPIRED, state);
        }
        if (attempt.recoveryGeneration() != state.recoveryGeneration()) {
            return new Result(Verdict.STALE_GENERATION, "OLD_GENERATION", state, decision);
        }
        String detail = reason == null || reason.isBlank() ? REASON_TOOL_ARGS_INVALID : reason;
        return settleTechnical(state, decision, attempt, reply, detail, true);
    }

    /**
     * 可信宿主在新代次接续一个旧代次未结算的尝试：保守结算旧尝试，并按原输入与屏障有界重试。
     *
     * @param state    当前状态
     * @param actionId 动作标识
     * @return 接续结果
     */
    public static Result resumeDecision(State state, String actionId) {
        if (!state.sessionOpen()) {
            return reject(Verdict.REJECTED, REASON_SESSION_NOT_OPEN, state);
        }
        Decision decision = find(state, actionId);
        if (decision == null) {
            return reject(Verdict.UNKNOWN, "UNKNOWN_DECISION", state);
        }
        if (decision.status() == Status.RECORDED) {
            return reject(Verdict.REJECTED, REASON_ALREADY_RECORDED, state);
        }
        if (decision.status() == Status.EXPIRED) {
            return reject(Verdict.REJECTED, REASON_ALREADY_EXPIRED, state);
        }
        Attempt last = decision.lastAttempt();
        if (last.settledDone()) {
            return reject(Verdict.REJECTED, REASON_ALREADY_SETTLED, state);
        }
        if (last.recoveryGeneration() == state.recoveryGeneration()) {
            return reject(Verdict.REJECTED, REASON_NOT_STALE, state);
        }
        return settleTechnical(state, decision, last, null, REASON_UNKNOWN_USAGE
                + ":STALE_GENERATION_RESUME", false);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 按动作标识取决定。
     *
     * @param state    当前状态
     * @param actionId 动作标识
     * @return 决定；不存在时为空
     */
    public static Optional<Decision> decision(State state, String actionId) {
        return Optional.ofNullable(find(state, actionId));
    }

    /**
     * 最早未解决屏障；同刻按运行种子与动作标识稳定排序。
     *
     * @param state 当前状态
     * @return 最早屏障所属决定
     */
    public static Optional<Decision> earliestBarrier(State state) {
        Comparator<Decision> order = Comparator.comparingLong(Decision::barrierAtMillis)
                .thenComparing(decision -> WorldScheduler.fairOrder(state.seed(),
                        decision.actionId()))
                .thenComparing(Decision::actionId);
        return state.decisions().stream()
                .filter(decision -> decision.status() == Status.OPEN)
                .min(order);
    }

    /**
     * 已记录业务意图的动作到期：屏障加冻结的工具耗时。
     *
     * @param state    当前状态
     * @param actionId 动作标识
     * @return 动作到期模拟毫秒
     * @throws IllegalStateException    决定尚未记录为业务意图
     * @throws IllegalArgumentException 工具缺少冻结耗时
     * @throws ArithmeticException      时间加法溢出
     */
    public static long actionDueMillis(State state, String actionId) {
        Decision decision = find(state, actionId);
        if (decision == null || decision.status() != Status.RECORDED
                || decision.outcome() != Outcome.INTENT) {
            throw new IllegalStateException("决定尚未记录为业务意图：" + actionId);
        }
        return actionDue(decision, state.config(), decision.recordedTool());
    }

    /**
     * 记录的业务意图回执正文；INTENT 恢复时据此取得原动作全部参数。
     *
     * @param decision 已记录决定
     * @return 原始回执正文
     * @throws IllegalStateException 决定不是已记录的 INTENT
     */
    public static String recordedReplyBody(Decision decision) {
        if (decision.status() != Status.RECORDED || decision.outcome() != Outcome.INTENT) {
            throw new IllegalStateException("决定没有已记录的业务意图：" + decision.actionId());
        }
        return decision.lastAttempt().replyBody();
    }

    /**
     * 该人物剩余可用预算：运行上限与个人上限逐项取较小值，只扣未结算尝试的预留。
     *
     * @param state          当前状态
     * @param personPartyId  人物
     * @return 剩余额度；未登记个人上限时视为零
     */
    public static Usage headroom(State state, String personPartyId) {
        Usage runCap = state.config().budget().runCap();
        Usage personCap = state.config().budget().personCap().getOrDefault(personPartyId,
                Usage.zero());
        Usage outstandingRun = Usage.zero();
        Usage outstandingPerson = Usage.zero();
        for (Decision decision : state.decisions()) {
            for (Attempt attempt : decision.attempts()) {
                if (attempt.settledDone()) {
                    continue;
                }
                outstandingRun = outstandingRun.plus(attempt.reserved());
                if (decision.personPartyId().equals(personPartyId)) {
                    outstandingPerson = outstandingPerson.plus(attempt.reserved());
                }
            }
        }
        Usage runLeft = subtract(runCap, state.runUsage().plus(outstandingRun));
        Usage personLeft = subtract(personCap, state.personUsage()
                .getOrDefault(personPartyId, Usage.zero()).plus(outstandingPerson));
        return min(runLeft, personLeft);
    }

    /**
     * 预演一次结算后的剩余额度：把待结算金额计入已用，并排除该尝试的未结算预留。
     *
     * @param state             结算前状态
     * @param personPartyId     人物
     * @param settled           本次结算用量
     * @param excludedAttemptId 即将结算的尝试
     * @return 结算后的剩余额度
     */
    private static Usage headroomAfter(State state, String personPartyId, Usage settled,
                                       String excludedAttemptId) {
        Usage runCap = state.config().budget().runCap();
        Usage personCap = state.config().budget().personCap().getOrDefault(personPartyId,
                Usage.zero());
        Usage runUsed = state.runUsage().plus(settled);
        Usage personUsed = state.personUsage().getOrDefault(personPartyId, Usage.zero())
                .plus(settled);
        Usage outstandingRun = Usage.zero();
        Usage outstandingPerson = Usage.zero();
        for (Decision decision : state.decisions()) {
            for (Attempt attempt : decision.attempts()) {
                if (attempt.settledDone() || attempt.attemptId().equals(excludedAttemptId)) {
                    continue;
                }
                outstandingRun = outstandingRun.plus(attempt.reserved());
                if (decision.personPartyId().equals(personPartyId)) {
                    outstandingPerson = outstandingPerson.plus(attempt.reserved());
                }
            }
        }
        return min(subtract(runCap, runUsed.plus(outstandingRun)),
                subtract(personCap, personUsed.plus(outstandingPerson)));
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    /**
     * 序列化完整状态；读回时由记录构造与交叉校验共同验证语义。
     *
     * @param state 当前状态
     * @return JSON 文本
     */
    public static String toJson(State state) {
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("无法序列化决定状态", failure);
        }
    }

    /**
     * 从 JSON 还原状态；身份、摘要、尝试连续性、用量累计与终态都会被验证。
     *
     * @param json JSON 文本
     * @return 还原状态
     * @throws IllegalArgumentException JSON 无法解析或语义不一致
     */
    public static State fromJson(String json) {
        requireText(json, "决定状态JSON");
        try {
            return MAPPER.readValue(json, State.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("无效决定状态JSON", failure);
        }
    }

    // ------------------------------------------------------------------
    // 内部：状态转移
    // ------------------------------------------------------------------

    private static Result accept(State state, Decision decision) {
        return new Result(Verdict.ACCEPTED, "", state, decision);
    }

    private static Result reject(Verdict verdict, String reason, State state) {
        return new Result(verdict, reason, state, null);
    }

    private static State append(State state, Decision decision) {
        return append(state, decision, state.nextAttemptSequence());
    }

    private static State append(State state, Decision decision, long nextSequence) {
        List<Decision> decisions = new ArrayList<>(state.decisions());
        boolean replaced = false;
        for (int index = 0; index < decisions.size(); index++) {
            if (decisions.get(index).actionId().equals(decision.actionId())) {
                decisions.set(index, decision);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            decisions.add(decision);
        }
        return buildState(state, decisions, nextSequence);
    }

    private static State buildState(State base, List<Decision> decisions, long nextSequence) {
        Usage run = Usage.zero();
        Map<String, Usage> person = new TreeMap<>();
        for (Decision decision : decisions) {
            for (Attempt attempt : decision.attempts()) {
                if (!attempt.settledDone()) {
                    continue;
                }
                run = run.plus(attempt.settled());
                person.merge(decision.personPartyId(), attempt.settled(), Usage::plus);
            }
        }
        return new State(base.runId(), base.seed(), base.config(), base.recoveryGeneration(),
                base.sessionOpen(), decisions, run,
                Collections.unmodifiableMap(new TreeMap<>(person)), nextSequence);
    }

    /**
     * 保守结算一次尝试，并按剩余尝试次数与预算决定重试或过期。
     *
     * @param state     当前状态
     * @param decision  目标决定
     * @param attempt   未结算尝试
     * @param reply     回执；未知用量时为 null
     * @param reason    结算原因
     * @param useDeclared 是否采用回执声明的实际用量
     * @return 处理结果
     */
    private static Result settleTechnical(State state, Decision decision, Attempt attempt,
                                          Reply reply, String reason, boolean useDeclared) {
        Usage settled = attempt.reserved();
        String body = reply == null ? "" : reply.body();
        String source = reply == null ? "" : reply.usageSource();
        if (useDeclared && reply != null) {
            try {
                settled = settleUsage(state.config(), reply.usage());
            } catch (ReplyFailure failure) {
                settled = attempt.reserved();
                reason = failure.reason;
            }
        }
        boolean canRetry = decision.attempts().size() < state.config().limits().maxAttemptsPerDecision();
        Usage reserved = reservation(state.config());
        boolean budget = canRetry && headroomAfter(state, decision.personPartyId(), settled,
                attempt.attemptId()).covers(reserved);
        if (canRetry && !budget) {
            reason += ":" + REASON_NO_RETRY_BUDGET;
        }
        Attempt done = new Attempt(attempt.attemptId(), attempt.attemptNo(),
                attempt.recoveryGeneration(), attempt.inputDigest(), attempt.reserved(), settled,
                reply == null ? null : reply.usage(), reply != null,
                body, source, Outcome.TECHNICAL_FAILED, reason);

        List<Attempt> attempts = replaceAttempt(decision.attempts(), done);
        if (!budget) {
            Decision expired = withAttempts(decision, attempts, Status.EXPIRED,
                    Outcome.TECHNICAL_FAILED, "");
            State next = append(state, expired);
            return new Result(Verdict.ACCEPTED, canRetry ? REASON_NO_RETRY_BUDGET : "", next, expired);
        }
        long sequence = state.nextAttemptSequence();
        Attempt retry = new Attempt(ATTEMPT_PREFIX + sequence, attempts.size() + 1,
                state.recoveryGeneration(), decision.inputDigest(), reserved, Usage.zero(), null, false, "",
                "", Outcome.NONE, "");
        attempts.add(retry);
        Decision reopened = withAttempts(decision, attempts, Status.OPEN,
                Outcome.TECHNICAL_FAILED, "");
        State next = buildState(state, replaceDecision(state.decisions(), reopened),
                Math.addExact(sequence, 1L));
        return accept(next, reopened);
    }

    private static Decision withAttempts(Decision decision, List<Attempt> attempts, Status status,
                                         Outcome outcome, String recordedTool) {
        return new Decision(decision.runId(), decision.actionId(), decision.personPartyId(),
                decision.wakeReason(), decision.decisionAtMillis(), decision.barrierAtMillis(),
                decision.cutoffPosition(), decision.cutoffTimeMillis(),
                decision.toolContractVersion(), decision.allowedTools(), decision.inputText(),
                decision.inputDigest(), status, outcome, recordedTool, attempts);
    }

    private static List<Decision> replaceDecision(List<Decision> decisions, Decision decision) {
        List<Decision> copy = new ArrayList<>(decisions);
        for (int index = 0; index < copy.size(); index++) {
            if (copy.get(index).actionId().equals(decision.actionId())) {
                copy.set(index, decision);
                return copy;
            }
        }
        copy.add(decision);
        return copy;
    }

    private static List<Attempt> replaceAttempt(List<Attempt> attempts, Attempt replacement) {
        List<Attempt> copy = new ArrayList<>(attempts);
        for (int index = 0; index < copy.size(); index++) {
            if (copy.get(index).attemptId().equals(replacement.attemptId())) {
                copy.set(index, replacement);
                return copy;
            }
        }
        throw new IllegalStateException("尝试不存在：" + replacement.attemptId());
    }

    // ------------------------------------------------------------------
    // 内部：校验与计算
    // ------------------------------------------------------------------

    /** 取出已记录正文里的工具名；正文不合法时返回 null，由调用方按不一致处理。 */
    private static String bodyTool(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node != null && node.isObject() && node.get("tool") != null
                    && node.get("tool").isTextual()) {
                return node.get("tool").asText();
            }
        } catch (JsonProcessingException malformed) {
            return null;
        }
        return null;
    }

    /** @return 最后一条尝试的回执正文；无尝试时为空串 */
    private static String lastAttemptOf(List<Attempt> attempts) {
        return attempts.isEmpty() ? "" : attempts.get(attempts.size() - 1).replyBody();
    }

    private static Decision find(State state, String actionId) {
        for (Decision decision : state.decisions()) {
            if (decision.actionId().equals(actionId)) {
                return decision;
            }
        }
        return null;
    }

    private static Attempt findAttempt(Decision decision, String attemptId) {
        for (Attempt attempt : decision.attempts()) {
            if (attempt.attemptId().equals(attemptId)) {
                return attempt;
            }
        }
        return null;
    }

    private static boolean sameFrozenInput(Decision decision, OpenRequest request) {
        return decision.personPartyId().equals(request.personPartyId())
                && decision.wakeReason().equals(request.wakeReason())
                && decision.decisionAtMillis() == request.decisionAtMillis()
                && decision.cutoffPosition() == request.cutoffPosition()
                && decision.cutoffTimeMillis() == request.cutoffTimeMillis()
                && decision.toolContractVersion().equals(request.toolContractVersion())
                && decision.allowedTools().equals(normalizeTools(request.allowedTools()))
                && decision.inputText().equals(request.inputText());
    }

    private static Usage reservation(Config config) {
        return new Usage(1, config.limits().perCallInputTokens(),
                config.limits().perCallOutputTokens(), new BigDecimal("0.00"));
    }

    private static Usage settleUsage(Config config, Usage usage) {
        if (usage == null) {
            throw new ReplyFailure(REASON_UNKNOWN_USAGE);
        }
        if (usage.attempts() != 1L) {
            throw new ReplyFailure(REASON_USAGE_ATTEMPTS_INVALID);
        }
        if (usage.cost().signum() != 0) {
            throw new ReplyFailure(REASON_USAGE_COST_INVALID);
        }
        if (usage.inputTokens() > config.limits().perCallInputTokens()
                || usage.outputTokens() > config.limits().perCallOutputTokens()) {
            throw new ReplyFailure(REASON_USAGE_EXCEEDS_RESERVATION);
        }
        return usage;
    }

    private static Usage subtract(Usage cap, Usage used) {
        long attempts = Math.max(0L, cap.attempts() - used.attempts());
        long input = Math.max(0L, cap.inputTokens() - used.inputTokens());
        long output = Math.max(0L, cap.outputTokens() - used.outputTokens());
        BigDecimal cost = cap.cost().subtract(used.cost());
        if (cost.signum() < 0) {
            cost = new BigDecimal("0.00");
        }
        return new Usage(attempts, input, output, cost);
    }

    private static Usage min(Usage left, Usage right) {
        return new Usage(Math.min(left.attempts(), right.attempts()),
                Math.min(left.inputTokens(), right.inputTokens()),
                Math.min(left.outputTokens(), right.outputTokens()),
                left.cost().min(right.cost()));
    }

    private static long actionDue(Decision decision, Config config, String tool) {
        Long millis = config.actionMillisByTool().get(tool);
        if (millis == null) {
            throw new IllegalArgumentException("工具缺少冻结动作耗时：" + tool);
        }
        if (TOOL_WAIT.equals(tool)) {
            throw new IllegalArgumentException("观望没有业务动作到期");
        }
        return Math.addExact(decision.barrierAtMillis(), millis);
    }

    private static Parsed parse(Config config, List<String> allowedTools, String body) {
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (JsonProcessingException failure) {
            throw new ReplyFailure(REASON_BAD_REPLY_SHELL);
        }
        if (node == null || !node.isObject()) {
            throw new ReplyFailure(REASON_BAD_REPLY_SHELL);
        }
        Set<String> fields = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!fields.contains("tool") || !node.get("tool").isTextual()) {
            throw new ReplyFailure(REASON_BAD_REPLY_SHELL);
        }
        String tool = node.get("tool").asText();
        if (tool.isBlank() || !allowedTools.contains(tool)) {
            throw new ReplyFailure(REASON_UNKNOWN_TOOL);
        }
        if (TOOL_WAIT.equals(tool)) {
            if (fields.size() != 1) {
                throw new ReplyFailure(REASON_WAIT_WITH_ARGS);
            }
            return new Parsed(tool, true);
        }
        if (!fields.equals(Set.of("tool", "args"))) {
            throw new ReplyFailure(REASON_BAD_REPLY_SHELL);
        }
        if (!node.get("args").isObject()) {
            throw new ReplyFailure(REASON_BAD_REPLY_SHELL);
        }
        if (!config.actionMillisByTool().containsKey(tool)) {
            throw new ReplyFailure(REASON_UNKNOWN_TOOL);
        }
        return new Parsed(tool, false);
    }

    private static List<String> normalizeTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("允许工具不能为空");
        }
        TreeMap<String, Boolean> sorted = new TreeMap<>();
        for (String tool : tools) {
            requireText(tool, "工具");
            sorted.put(tool, Boolean.TRUE);
        }
        List<String> ordered = new ArrayList<>(sorted.keySet());
        if (!ordered.contains(TOOL_WAIT)) {
            throw new IllegalArgumentException("允许工具必须包含 " + TOOL_WAIT);
        }
        return List.copyOf(ordered);
    }

    private static String canonicalDigest(String toolContractVersion, List<String> tools,
                                          String inputText) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("domain", DIGEST_DOMAIN);
        node.put("toolContractVersion", toolContractVersion);
        ArrayNode array = node.putArray("allowedTools");
        tools.forEach(array::add);
        node.put("inputText", inputText);
        String canonical;
        try {
            canonical = MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("无法生成规范输入编码", failure);
        }
        return sha256(canonical);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少 SHA-256", impossible);
        }
    }

    private static long sequenceOf(String attemptId) {
        if (attemptId == null || !attemptId.startsWith(ATTEMPT_PREFIX)) {
            throw new IllegalArgumentException("尝试标识格式非法：" + attemptId);
        }
        try {
            long value = Long.parseLong(attemptId.substring(ATTEMPT_PREFIX.length()));
            if (value < 1) {
                throw new IllegalArgumentException("尝试序号必须从 1 开始：" + attemptId);
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("尝试标识格式非法：" + attemptId, failure);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    private static void requireDigest(String digest) {
        requireText(digest, "输入摘要");
        if (digest.length() != 64) {
            throw new IllegalArgumentException("输入摘要必须是 64 位十六进制：" + digest);
        }
    }

    /** 解析后的回执意图。 */
    private record Parsed(String tool, boolean abstain) {
    }

    /** 回执校验失败，按技术失败处理。 */
    private static final class ReplyFailure extends RuntimeException {
        private final String reason;

        private ReplyFailure(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }
}
