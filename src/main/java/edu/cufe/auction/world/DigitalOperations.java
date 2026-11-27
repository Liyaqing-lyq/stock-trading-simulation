package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 数字技术服务公司世界命令的明确业务载荷与纯核入口。
 *
 * <p><b>边界</b>：人物身份、任职、预算与工时由世界层核验；载荷只带业务参数，不接受自报主体、
 * 付款账户、审批结果或“成功”标记。本类不保存现金，现金从 {@link FundState} 每次显式传入，
 * 付款只走调用方绑定的 {@link FundPayer}。</p>
 *
 * <p><b>三类动作</b>：{@link #personExecutable} 可由经理决定；{@link #systemOnly} 只能由世界的
 * 到期子任务触发（阶段完成、关期）；{@link #governanceOnly} 只能由世界在取得真实治理决议后触发
 * （外部注资）。系统与治理动作都不出现在经理的工具字段表里。</p>
 *
 * <p><b>完成结果不来自人物</b>：阶段成败在 {@link DigitalServiceModel.Config} 冻结，完成动作只带
 * 一条世界已冻结的观察引用；引用不在 {@link DigitalCompanyRun.Config#stageEvidence()} 里就拒绝。</p>
 */
public final class DigitalOperations {

    private DigitalOperations() {
    }

    /** 适配层结果码：阶段观察引用不在冻结情景里。 */
    public static final String UNKNOWN_EVIDENCE = "UNKNOWN_EVIDENCE";

    /** 适配层结果码：注资缺少世界已作出的治理决议。 */
    public static final String NO_MANDATE = "NO_MANDATE";

    /** 纯核已经实现的数字服务动作；未实现的（如许可费、分成）不在此列。 */
    public enum Action {
        /** 开工 PILOT（付承包方、当期费用化）。 */
        BEGIN_PILOT,
        /** 开工 VALIDATION（付承包方、先计入在研）。 */
        BEGIN_VALIDATION,
        /** 到期完成 PILOT（系统）。 */
        COMPLETE_PILOT,
        /** 到期完成 VALIDATION（系统）。 */
        COMPLETE_VALIDATION,
        /** 记录商业化批准（只登记授权事实）。 */
        APPROVE_COMMERCIAL,
        /** 签署付费服务合同并收预收。 */
        SIGN_CONTRACT,
        /** 按实际服务量交付并确认收入。 */
        DELIVER,
        /** 终止合同，释放未交付承诺。 */
        TERMINATE,
        /** 退还未赚预收。 */
        REFUND,
        /** 收取应收。 */
        COLLECT,
        /** 逾期核销坏账。 */
        WRITE_OFF,
        /** 期末关账（系统）。 */
        CLOSE_PERIOD,
        /** 外部资本注入（世界治理）。 */
        INJECT_CAPITAL,
        /** 在任经理落实已通过的停业决议。 */
        EXIT_BUSINESS
    }

    /** 到期完成与关期不由人物触发。 */
    public static boolean systemOnly(Action action) {
        return action == Action.COMPLETE_PILOT || action == Action.COMPLETE_VALIDATION
                || action == Action.CLOSE_PERIOD;
    }

    /** 重大资本动作只能由世界在真实治理决议之后触发。 */
    public static boolean governanceOnly(Action action) {
        return action == Action.INJECT_CAPITAL;
    }

    /** 经理可以在自己任职内决定并承担工时的动作。 */
    public static boolean personExecutable(Action action) {
        return !systemOnly(action) && !governanceOnly(action);
    }

    /**
     * 持久化的明确请求：字段名按动作精确匹配，整数不经过浮点。
     *
     * @param action    经营动作
     * @param arguments 该动作的全部且仅有的字段
     */
    public record Request(Action action, Map<String, String> arguments) {

        /** 拒绝缺字段、多余字段、空值、非整数与溢出数量；冻结字段顺序。 */
        public Request {
            Objects.requireNonNull(action, "数字服务动作");
            Objects.requireNonNull(arguments, "数字服务字段");
            Set<String> expected = switch (action) {
                case BEGIN_PILOT, BEGIN_VALIDATION -> Set.of();
                case COMPLETE_PILOT, COMPLETE_VALIDATION -> Set.of("evidenceId");
                case APPROVE_COMMERCIAL -> Set.of("approvalId");
                case SIGN_CONTRACT -> Set.of("contractId", "quantityUnits", "prepaidCents");
                case DELIVER -> Set.of("contractId", "units", "receivableId");
                case TERMINATE -> Set.of("contractId");
                case EXIT_BUSINESS -> Set.of("meetingId");
                case REFUND -> Set.of("contractId", "refundCents");
                case COLLECT, WRITE_OFF -> Set.of("receivableId");
                case CLOSE_PERIOD -> Set.of("period");
                case INJECT_CAPITAL -> Set.of("amountCents", "mandateId");
            };
            if (!arguments.keySet().equals(expected)) {
                throw new IllegalArgumentException("数字服务动作字段不符：" + action + " / " + expected);
            }
            Set<String> integers = switch (action) {
                case SIGN_CONTRACT -> Set.of("quantityUnits", "prepaidCents");
                case DELIVER -> Set.of("units");
                case REFUND -> Set.of("refundCents");
                case CLOSE_PERIOD -> Set.of("period");
                case INJECT_CAPITAL -> Set.of("amountCents");
                default -> Set.of();
            };
            for (Map.Entry<String, String> entry : arguments.entrySet()) {
                String value = entry.getValue();
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("数字服务字段不能为空：" + entry.getKey());
                }
                if (integers.contains(entry.getKey())) {
                    if (!value.matches("0|[1-9][0-9]*")) {
                        throw new IllegalArgumentException("数字服务数值必须为精确非负整数："
                                + entry.getKey());
                    }
                    try {
                        Long.parseLong(value);
                    } catch (NumberFormatException overflow) {
                        throw new IllegalArgumentException("数字服务数值超出 long：" + entry.getKey());
                    }
                }
            }
            arguments = Collections.unmodifiableMap(new TreeMap<>(arguments));
        }

        /** @param name 字段名 @return 文本值（缺失为 null） */
        String field(String name) {
            return arguments.get(name);
        }

        /**
         * @param name 整数或整数分字段名
         * @return 精确 long
         */
        long integer(String name) {
            String value = field(name);
            if (value == null) {
                throw new IllegalArgumentException("缺少字段：" + name);
            }
            return Long.parseLong(value);
        }

        /**
         * 金额字段统一用“整数分”文本表示，避免任何浮点往返。
         *
         * @param name 金额字段名
         * @return 两位小数金额
         */
        BigDecimal cents(String name) {
            return BigDecimal.valueOf(integer(name), 2);
        }
    }

    /**
     * 已由世界核验身份与来源后的纯候选计算；不自行提交、不建立预算、不改银行账。
     *
     * @param run       公司子账（提供冻结配置与当前台账）
     * @param funds     当前世界资金三态
     * @param payer     该动作对应的资金腿；不动钱的动作传 null
     * @param now       世界时点
     * @param commandId 世界命令标识（同时是资金腿幂等标识）
     * @param request   已解析的业务载荷
     * @return 候选结果
     */
    static Outcome apply(DigitalCompanyRun run, FundState funds, FundPayer payer, long now,
                         String commandId, Request request) {
        Objects.requireNonNull(run, "数字服务公司");
        Objects.requireNonNull(funds, "资金三态");
        Objects.requireNonNull(request, "数字服务动作");
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("命令标识不能为空");
        }
        DigitalServiceModel.State state = run.ledger();
        String owner = state.config().companyOwnerId();
        if (run.cessation() != null && newBusiness(request.action())) {
            return new Outcome(false, "DIGITAL_INTAKE_CLOSED", state, funds);
        }
        return switch (request.action()) {
            case EXIT_BUSINESS -> new Outcome(false, "DIGITAL_CESSATION_REQUIRES_BOARD", state, funds);
            case BEGIN_PILOT -> mapped(DigitalServiceModel.beginStage(state, now, owner, commandId,
                    DigitalServiceModel.Stage.PILOT, funds, payer));
            case BEGIN_VALIDATION -> mapped(DigitalServiceModel.beginStage(state, now, owner,
                    commandId, DigitalServiceModel.Stage.VALIDATION, funds, payer));
            case COMPLETE_PILOT -> complete(run, now, commandId, DigitalServiceModel.Stage.PILOT,
                    request, funds);
            case COMPLETE_VALIDATION -> complete(run, now, commandId,
                    DigitalServiceModel.Stage.VALIDATION, request, funds);
            case APPROVE_COMMERCIAL -> mapped(DigitalServiceModel.approveCommercialization(state,
                    now, owner, commandId, request.field("approvalId"), funds));
            case SIGN_CONTRACT -> mapped(DigitalServiceModel.signServiceContract(state, now, owner,
                    commandId, request.field("contractId"), request.integer("quantityUnits"),
                    request.cents("prepaidCents"), funds, payer));
            case DELIVER -> mapped(DigitalServiceModel.deliverService(state, now, owner, commandId,
                    request.field("contractId"), request.integer("units"),
                    request.field("receivableId"), funds, payer));
            case TERMINATE -> mapped(DigitalServiceModel.terminateContract(state, now, owner,
                    commandId, request.field("contractId"), funds));
            case REFUND -> mapped(DigitalServiceModel.refund(state, now, owner, commandId,
                    request.field("contractId"), request.cents("refundCents"), funds, payer));
            case COLLECT -> mapped(DigitalServiceModel.collect(state, now, owner, commandId,
                    request.field("receivableId"), funds, payer));
            case WRITE_OFF -> mapped(DigitalServiceModel.writeOff(state, now, owner, commandId,
                    request.field("receivableId"), funds));
            case CLOSE_PERIOD -> mapped(DigitalServiceModel.closePeriod(state, now, owner, commandId,
                    request.integer("period"), funds, payer));
            case INJECT_CAPITAL -> capital(run, now, commandId, request, funds, payer);
        };
    }

    /**
     * 系统子任务的到期时点；不适用时返回 -1。
     *
     * @param state  当前台账
     * @param action 动作
     * @return 到期毫秒或 -1
     */
    public static long dueAt(DigitalServiceModel.State state, Action action) {
        Objects.requireNonNull(state, "台账");
        Objects.requireNonNull(action, "动作");
        return switch (action) {
            case COMPLETE_PILOT -> stageDue(state, DigitalServiceModel.Stage.PILOT);
            case COMPLETE_VALIDATION -> stageDue(state, DigitalServiceModel.Stage.VALIDATION);
            case CLOSE_PERIOD -> Math.multiplyExact(state.closedPeriods() + 1,
                    DigitalServiceModel.PERIOD_MILLIS);
            default -> -1L;
        };
    }

    static boolean newBusiness(Action action) {
        return action == Action.BEGIN_PILOT || action == Action.BEGIN_VALIDATION
                || action == Action.APPROVE_COMMERCIAL || action == Action.SIGN_CONTRACT;
    }

    private static Outcome complete(DigitalCompanyRun run, long now, String commandId,
                                    DigitalServiceModel.Stage stage, Request request,
                                    FundState funds) {
        DigitalServiceModel.Evidence evidence = run.evidenceOf(stage, request.field("evidenceId"));
        if (evidence == null) {
            return new Outcome(false, UNKNOWN_EVIDENCE, run.ledger(), funds);
        }
        if (run.config().evidenceAtCompletion()) {
            var completed = run.ledger().stageOf(stage);
            if (completed == null) return new Outcome(false, DigitalServiceModel.UNKNOWN_STAGE, run.ledger(), funds);
            // 这是冻结EXP试验的实际完工回报，绝非在开局伪造外部已观察证据。
            evidence = new DigitalServiceModel.Evidence(evidence.evidenceId(),
                    evidence.reference()+"/completion/"+completed.completesAt(), completed.completesAt());
        }
        return mapped(DigitalServiceModel.completeStage(run.ledger(), now,
                run.ledger().config().companyOwnerId(), commandId, stage, evidence, funds));
    }

    private static Outcome capital(DigitalCompanyRun run, long now, String commandId,
                                   Request request, FundState funds, FundPayer payer) {
        DigitalCompanyRun.Mandate mandate = run.mandateOf(request.field("mandateId"));
        if (mandate == null
                || mandate.kind() != DigitalCompanyRun.Mandate.Kind.CAPITAL_INJECTION
                || mandate.decidedAt() > now) {
            return new Outcome(false, NO_MANDATE, run.ledger(), funds);
        }
        return mapped(DigitalServiceModel.injectCapital(run.ledger(), now,
                run.ledger().config().companyOwnerId(), commandId,
                request.cents("amountCents"), funds, payer));
    }

    private static long stageDue(DigitalServiceModel.State state, DigitalServiceModel.Stage stage) {
        DigitalServiceModel.StageRecord record = state.stageOf(stage);
        return record == null ? -1L : record.completesAt();
    }

    private static Outcome mapped(DigitalServiceModel.Outcome outcome) {
        return new Outcome(outcome.accepted(), outcome.reason(), outcome.state(), outcome.funds());
    }

    /**
     * 适配层候选结果：拒绝时台账与资金三态都是入参原样。
     *
     * @param accepted 业务是否成功
     * @param reason   结果码（纯核码或适配层码）
     * @param state    候选台账
     * @param funds    候选资金三态
     */
    public record Outcome(boolean accepted, String reason, DigitalServiceModel.State state,
                          FundState funds) {
    }
}
