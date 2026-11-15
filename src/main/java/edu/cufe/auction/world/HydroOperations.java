package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.Fund;
import edu.cufe.auction.world.bank.FundState;
import java.math.BigDecimal;
import java.util.*;

/** 水电动作及同帧资金桥。纯核计算水量与候选金额，世界资金端口决定实际可付款额。 */
public final class HydroOperations {
    private HydroOperations() { }
    /** 结算只能由世界时钟触发，其余动作由经理真实工作完成后执行。 */
    public enum Action { DISPATCH_FLOW, DISPATCH_OUTPUT, MAINTENANCE, COLLECT, SETTLE }
    /** @return 是否为系统动作。 */
    public static boolean systemOnly(Action action) { return action == Action.SETTLE; }
    /** 精确字段，不接受自报身份、工时、成本、未来来水或成功结果。 */
    public record Request(Action action, Map<String,String> arguments) {
        /** 对字段和数值实施严格校验。 */
        public Request {
            Objects.requireNonNull(action); Objects.requireNonNull(arguments);
            Set<String> fields = switch (action) {
                case DISPATCH_FLOW -> Set.of("cubicMetresPerHour");
                case DISPATCH_OUTPUT -> Set.of("outputKw");
                case COLLECT -> Set.of("amount");
                case SETTLE, MAINTENANCE -> Set.of();
            };
            if (!fields.equals(arguments.keySet())) throw new IllegalArgumentException("水电动作字段不符");
            arguments.forEach((key,value) -> {
                if (value == null || !(key.equals("amount") ? value.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,2})?") : value.matches("0|[1-9][0-9]*")))
                    throw new IllegalArgumentException("水电数值须为精确文本");
                if (key.equals("amount")) { if (new BigDecimal(value).signum() <= 0) throw new IllegalArgumentException("回款额必须为正"); }
                else Long.parseLong(value);
            });
            arguments = Collections.unmodifiableMap(new TreeMap<>(arguments));
        }
    }
    /** 业务与资金候选必须一起提交或一起丢弃。 */
    public record Applied(HydroCompanyRun run, FundState funds, boolean accepted, String reason, BigDecimal moved) { }
    /** 从冻结账户推导付款方向，不接受人物填入收付款方。 */
    public static String source(HydroCompanyRun run, Action action) {
        return action == Action.COLLECT ? run.config().model().buyerAccountId() : run.config().model().companyAccountId();
    }
    /** @return 冻结收款账户。 */
    public static String target(HydroCompanyRun run, Action action) {
        return action == Action.COLLECT ? run.config().model().companyAccountId() : run.config().model().contractorAccountId();
    }
    /** 唯一纯核候选加实际资金端口；付款拒绝不留下物理或经营效果。 */
    public static Applied apply(HydroCompanyRun run, FundState funds, FundPayer payer,
                                long now, String commandId, Request request) {
        var state = run.ledger(); var model = run.config().model(); var action = request.action();
        boolean funded = action == Action.SETTLE || action == Action.MAINTENANCE || action == Action.COLLECT;
        BigDecimal available = BigDecimal.ZERO;
        if (funded) {
            if (payer == null || !payer.source().equals(new Fund.Cash(source(run, action)))
                    || !payer.target().equals(new Fund.Cash(target(run, action)))) throw new IllegalArgumentException("水电资金桶不符");
            if (payer.hasRecord(commandId, funds) && !state.commandContents().containsKey(commandId))
                return rejected(run, funds, "FUND_RECORD_DIVERGED");
            available = payer.available(funds);
        }
        if (action == Action.MAINTENANCE && available.compareTo(run.config().maintenanceCost()) < 0)
            return rejected(run, funds, "INSUFFICIENT_AVAILABLE_CASH");
        if (action == Action.COLLECT && available.signum() == 0) return rejected(run, funds, "BUYER_CASH_SHORT");
        HydroModel.Outcome result = switch (action) {
            case SETTLE -> HydroModel.advance(state, commandId, model.companyOwnerId(), now, funds.accounts(), available);
            case DISPATCH_FLOW -> HydroModel.dispatch(state, commandId, model.companyOwnerId(),
                    Long.parseLong(request.arguments().get("cubicMetresPerHour")), now, funds.accounts());
            case DISPATCH_OUTPUT -> HydroModel.dispatchOutput(state, commandId, model.companyOwnerId(),
                    Long.parseLong(request.arguments().get("outputKw")), now, funds.accounts());
            case MAINTENANCE -> HydroModel.beginMaintenance(state, commandId, model.companyOwnerId(),
                    run.config().maintenanceMillis(), run.config().maintenanceCost(), now, funds.accounts());
            case COLLECT -> HydroModel.collectReceivable(state, commandId, model.companyOwnerId(),
                    new BigDecimal(request.arguments().get("amount")).min(available), now, funds.accounts());
        };
        if (!result.accepted()) return new Applied(run.withLedger(result.state()), funds, false, result.reason(), BigDecimal.ZERO.setScale(2));
        BigDecimal moved = funded ? funds.accounts().get(source(run, action)).cash().subtract(result.accounts().get(source(run, action)).cash()) : BigDecimal.ZERO.setScale(2);
        FundState nextFunds = funds;
        if (moved.signum() > 0) {
            var payment = payer.pay(commandId, moved, funds);
            if (!payment.accepted()) return rejected(run, funds, payment.reason());
            nextFunds = payment.funds();
        }
        if (!nextFunds.accounts().equals(result.accounts())) throw new IllegalStateException("水电候选与实际资金腿不一致");
        return new Applied(run.withLedger(result.state()), nextFunds, true, result.reason(), moved);
    }
    private static Applied rejected(HydroCompanyRun run, FundState funds, String reason) {
        return new Applied(run, funds, false, reason, BigDecimal.ZERO.setScale(2));
    }
}
