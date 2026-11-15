package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 陈酿库存公司世界命令的明确业务载荷与纯核入口。
 *
 * <p><b>边界</b>：人物身份、任职、预算与工时由世界层核验；载荷只带业务参数，不接受自报主体、
 * 付款账户或“成功”标记。现金从世界 {@link FundState} 的账户表现算，纯核返回候选账户表，
 * 适配层再用同一次候选的银行与托管三态重建 {@link FundState}，因此没有第二本现金账。</p>
 *
 * <p><b>命令幂等</b>：陈酿纯核没有命令日志（只有按业务标识的去重），所以“同一 commandId 只执行
 * 一次”必须由世界的 {@code CommandOutcome} 保证；本类不补造第二份日志。业务标识
 * （{@code orderId}/{@code batchId}/{@code shipmentId}/{@code saleId}）由纯核自己拒绝复用。</p>
 *
 * <p><b>系统动作</b>：终端售出与关期不是人物决定——售出由世界的终端需求日程触发，关期在期末
 * 由系统执行，两者都不出现在经理工具字段表里。</p>
 */
public final class AgedOperations {

    private AgedOperations() {
    }

    /** 纯核已经实现的陈酿动作；未实现的（如扩产、渠道新增）不在此列。 */
    public enum Action {
        /** 下原料采购单（不付现）。 */
        ORDER_RAW,
        /** 采购到货：付供应商并建原料 lot。 */
        RECEIVE_RAW,
        /** 投产：原料 FIFO、付转产费、建批次。 */
        PRODUCE,
        /** 调整渠道寄售价（只影响未来发货）。 */
        SET_PRICE,
        /** 发货（寄售），渠道按比例预付。 */
        SHIP,
        /** 终端售出并确认收入（系统）。 */
        SELL_THROUGH,
        /** 渠道回款。 */
        COLLECT,
        /** 退还未抵扣预收。 */
        PAY_REFUND,
        /** 支付未付固定费。 */
        PAY_FIXED,
        /** 期末关账：应计固定费、报损与过期（系统）。 */
        CLOSE_PERIOD
    }

    /** 终端售出与关期不由人物触发。 */
    public static boolean systemOnly(Action action) {
        return action == Action.SELL_THROUGH || action == Action.CLOSE_PERIOD;
    }

    /** 经理可以在自己任职内决定并承担工时的动作。 */
    public static boolean personExecutable(Action action) {
        return !systemOnly(action);
    }

    /**
     * 持久化的明确请求：字段名按动作精确匹配，整数与整数分不经过浮点。
     *
     * @param action    经营动作
     * @param arguments 该动作的全部且仅有的字段
     */
    public record Request(Action action, Map<String, String> arguments) {

        /** 拒绝缺字段、多余字段、空值、非整数与溢出数量；冻结字段顺序。 */
        public Request {
            Objects.requireNonNull(action, "陈酿动作");
            Objects.requireNonNull(arguments, "陈酿字段");
            Set<String> expected = switch (action) {
                case ORDER_RAW -> Set.of("orderId", "units");
                case RECEIVE_RAW -> Set.of("orderId");
                case PRODUCE -> Set.of("batchId", "units");
                case SET_PRICE -> Set.of("channelId", "priceCents");
                case SHIP -> Set.of("shipmentId", "channelId", "units");
                case SELL_THROUGH -> Set.of("saleId", "channelId", "units");
                case COLLECT, PAY_REFUND -> Set.of("channelId", "amountCents");
                case PAY_FIXED -> Set.of("amountCents");
                case CLOSE_PERIOD -> Set.of();
            };
            if (!arguments.keySet().equals(expected)) {
                throw new IllegalArgumentException("陈酿动作字段不符：" + action + " / " + expected);
            }
            Set<String> integers = switch (action) {
                case ORDER_RAW, PRODUCE, SHIP, SELL_THROUGH -> Set.of("units");
                case SET_PRICE -> Set.of("priceCents");
                case COLLECT, PAY_REFUND, PAY_FIXED -> Set.of("amountCents");
                default -> Set.of();
            };
            for (Map.Entry<String, String> entry : arguments.entrySet()) {
                String value = entry.getValue();
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("陈酿字段不能为空：" + entry.getKey());
                }
                if (integers.contains(entry.getKey())) {
                    if (!value.matches("0|[1-9][0-9]*")) {
                        throw new IllegalArgumentException("陈酿数值必须为精确非负整数："
                                + entry.getKey());
                    }
                    try {
                        Long.parseLong(value);
                    } catch (NumberFormatException overflow) {
                        throw new IllegalArgumentException("陈酿数值超出 long：" + entry.getKey());
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
         * 金额字段统一用“整数分”文本表示。
         *
         * @param name 金额字段名
         * @return 两位小数金额
         */
        BigDecimal cents(String name) {
            return BigDecimal.valueOf(integer(name), 2);
        }
    }

    /**
     * 已由世界核验身份与来源后的纯候选计算；不自行提交、不建立预算。
     *
     * @param run     公司子账（提供冻结配置与当前台账）
     * @param funds   当前世界资金三态
     * @param now     世界时点
     * @param request 已解析的业务载荷
     * @return 候选结果
     */
    static Outcome apply(AgedCompanyRun run, FundState funds, long now, Request request) {
        return apply(run,funds,now,request,AgedInventoryModel.FinancialContext.none());
    }

    private static Outcome apply(AgedCompanyRun run, FundState funds, long now, Request request, AgedInventoryModel.FinancialContext context) {
        Objects.requireNonNull(run, "陈酿公司");
        Objects.requireNonNull(funds, "资金三态");
        Objects.requireNonNull(request, "陈酿动作");
        AgedInventoryModel.State state = run.ledger();
        String actor = state.config().managerPersonId();
        Map<String, CashLedger.CashAccount> accounts = funds.accounts();
        AgedInventoryModel.Outcome outcome = switch (request.action()) {
            case ORDER_RAW -> AgedInventoryModel.orderRaw(state, actor,
                    request.integer("units"), request.field("orderId"), now, accounts, context);
            case RECEIVE_RAW -> AgedInventoryModel.receiveOrder(state, actor,
                    request.field("orderId"), now, accounts, context);
            case PRODUCE -> AgedInventoryModel.produce(state, actor,
                    request.integer("units"), request.field("batchId"), now, accounts, context);
            case SET_PRICE -> AgedInventoryModel.setPrice(state, actor,
                    request.field("channelId"), request.cents("priceCents"), now, accounts, context);
            case SHIP -> AgedInventoryModel.ship(state, actor, request.field("channelId"),
                    request.integer("units"), request.field("shipmentId"), now, accounts, context);
            case SELL_THROUGH -> AgedInventoryModel.sellThrough(state,
                    request.field("channelId"), request.integer("units"),
                    request.field("saleId"), now, accounts, context);
            case COLLECT -> AgedInventoryModel.collect(state, actor, request.field("channelId"),
                    request.cents("amountCents"), now, accounts, context);
            case PAY_REFUND -> AgedInventoryModel.payRefund(state, actor,
                    request.field("channelId"), request.cents("amountCents"), now, accounts, context);
            case PAY_FIXED -> AgedInventoryModel.payFixed(state, actor,
                    request.cents("amountCents"), now, accounts, context);
            case CLOSE_PERIOD -> AgedInventoryModel.closePeriod(state, now, accounts, accounts.get(state.config().companyAccountId()).cash(), false, context);
        };
        FundState next = outcome.accepted()
                ? new FundState(outcome.accounts(), funds.bank(), funds.book())
                : funds;
        return new Outcome(outcome.accepted(), outcome.reason(), outcome.state(), next);
    }

    /** 按冻结账户给出唯一付款方向；无付款动作返回公司账户。 */
    public static String source(AgedCompanyRun run, Request request) {
        var c=run.config().model(); var channel=c.channel(request.field("channelId"));
        return switch(request.action()) {
            case SHIP, COLLECT -> channel == null ? c.companyAccountId() : channel.accountId();
            case SELL_THROUGH -> c.terminalAccountId();
            default -> c.companyAccountId();
        };
    }
    /** 按冻结账户给出收款方。 */
    public static String target(AgedCompanyRun run, Request request) {
        var c=run.config().model(); var channel=c.channel(request.field("channelId"));
        return switch(request.action()) {
            case RECEIVE_RAW -> c.supplierAccountId();
            case PRODUCE -> c.conversionAccountId();
            case PAY_FIXED, CLOSE_PERIOD -> c.fixedAccountId();
            case PAY_REFUND, SELL_THROUGH -> channel == null ? c.companyAccountId() : channel.accountId();
            default -> c.companyAccountId();
        };
    }
    /** 世界资金桥：候选与实际资金腿同提交，冻结金额不可花费；命令去重由世界日志保证。 */
    static Outcome apply(AgedCompanyRun run, FundState funds, FundPayer payer, long now, String commandId, Request request) {
        return apply(run,funds,payer,now,commandId,request,AgedInventoryModel.FinancialContext.none());
    }

    static Outcome apply(AgedCompanyRun run, FundState funds, FundPayer payer, long now, String commandId, Request request, AgedInventoryModel.FinancialContext context) {
        boolean moneyAction = switch(request.action()) {
            case RECEIVE_RAW, PRODUCE, SHIP, SELL_THROUGH, COLLECT, PAY_REFUND, PAY_FIXED, CLOSE_PERIOD -> true;
            default -> false;
        };
        if (moneyAction && (payer == null || !payer.source().equals(new edu.cufe.auction.world.bank.Fund.Cash(source(run,request)))
                || !payer.target().equals(new edu.cufe.auction.world.bank.Fund.Cash(target(run,request)))))
            throw new IllegalArgumentException("陈酿资金桶不符");
        if (moneyAction && payer.hasRecord(commandId,funds)) return new Outcome(false,"FUND_RECORD_DIVERGED",run.ledger(),funds);
        BigDecimal available=moneyAction ? payer.available(funds) : BigDecimal.ZERO;
        Outcome result;
        if (request.action()==Action.CLOSE_PERIOD) {
            var close=AgedInventoryModel.closePeriod(run.ledger(),now,funds.accounts(),available,true,context);
            result=new Outcome(close.accepted(),close.reason(),close.state(),new FundState(close.accounts(),funds.bank(),funds.book()));
        } else {
            Request bounded=request;
            if (request.action()==Action.COLLECT || request.action()==Action.PAY_REFUND || request.action()==Action.PAY_FIXED) {
                var args=new TreeMap<>(request.arguments());
                args.put("amountCents",request.cents("amountCents").min(available).movePointRight(2).toBigIntegerExact().toString());
                bounded=new Request(request.action(),args);
            }
            // 纯核经理字段是内部授权能力；世界层已验证真实现任人物，换任不会改冻结配置。
            result=apply(run,funds,now,bounded,context);
        }
        if (!result.accepted()) return new Outcome(false,result.reason(),run.ledger(),funds);
        FundState next=funds;
        BigDecimal moved=moneyAction ? funds.accounts().get(source(run,request)).cash().subtract(result.funds().accounts().get(source(run,request)).cash()) : BigDecimal.ZERO;
        if (moved.signum()>0) {
            var payment=payer.pay(commandId,moved,funds);
            if (!payment.accepted()) return new Outcome(false,payment.reason(),run.ledger(),funds);
            next=payment.funds();
        }
        if (!next.accounts().equals(result.funds().accounts())) throw new IllegalStateException("陈酿纯候选与实际付款不一致");
        return new Outcome(true,result.reason(),result.state(),next);
    }

    /**
     * 系统子任务的到期时点；不适用时返回 -1。
     *
     * @param state  当前台账
     * @param action 动作
     * @return 到期毫秒或 -1
     */
    public static long dueAt(AgedInventoryModel.State state, Action action) {
        Objects.requireNonNull(state, "台账");
        Objects.requireNonNull(action, "动作");
        if (action != Action.CLOSE_PERIOD) {
            return -1L;
        }
        return state.config().periodEnd(state.closedPeriods());
    }

    /**
     * 适配层候选结果：拒绝时台账与资金三态都是入参原样。
     *
     * @param accepted 业务是否成功
     * @param reason   结果码
     * @param state    候选台账
     * @param funds    候选资金三态（银行与托管三态不变）
     */
    public record Outcome(boolean accepted, String reason, AgedInventoryModel.State state,
                          FundState funds) {
    }
}
