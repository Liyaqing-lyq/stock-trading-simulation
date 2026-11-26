package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.FundState;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 资源公司世界命令的明确业务载荷。
 * 人物身份、任职、预算和工时由世界层核验，载荷不接受自报人物、付款账户或成功结果。
 * 本类只把已核验命令交给唯一纯核，不保存现金，不替世界建立授权。
 */
public final class ResourceOperations {
    private ResourceOperations() { }

    /** 已实现的资源经营动作；到期完成类由世界排程绑定原始作业。 */
    public enum Action {
        BEGIN_EXTRACTION, FINISH_EXTRACTION, SELL_COAL, TRANSFER_TO_PLANT,
        BEGIN_POWER, FINISH_POWER, ABORT_JOB, COLLECT, WRITE_OFF, CLOSE_PERIOD, START_EXPANSION, COMPLETE_EXPANSION, SELL_RESIDUAL,
        EXIT_BUSINESS, SETTLE_BILL, SUBSCRIBE_SHARES
    }

    static boolean windDown(Action action) {
        return action == Action.EXIT_BUSINESS || action == Action.SETTLE_BILL;
    }

    /** 自动完工与关期不由人物触发。 */
    public static boolean systemOnly(Action action) {
        return action == Action.FINISH_EXTRACTION || action == Action.FINISH_POWER || action == Action.CLOSE_PERIOD || action == Action.COMPLETE_EXPANSION;
    }

    /**
     * 持久化的明确请求；字段名按动作精确匹配，整数不经过浮点转换。
     * @param action 经营动作
     * @param arguments 该动作的全部且仅有的字段
     */
    public record Request(Action action, Map<String, String> arguments) {
        /** 拒绝缺字段、多余字段、空值、非整数与溢出数量，冻结字段顺序。 */
        public Request {
            Objects.requireNonNull(action, "资源动作");
            Objects.requireNonNull(arguments, "资源字段");
            Set<String> expected = switch (action) {
                case BEGIN_EXTRACTION -> Set.of("jobId", "seamId", "quantityKg");
                case FINISH_EXTRACTION, FINISH_POWER, ABORT_JOB -> Set.of("jobId");
                case SELL_COAL -> Set.of("deliveryId", "quantityKg", "receivableId");
                case TRANSFER_TO_PLANT -> Set.of("transferId", "quantityKg");
                case BEGIN_POWER -> Set.of("jobId", "mwh", "receivableId");
                case COLLECT, WRITE_OFF -> Set.of("receivableId");
                case CLOSE_PERIOD, SETTLE_BILL -> Set.of("period");
                case EXIT_BUSINESS -> Set.of("meetingId");
                case SUBSCRIBE_SHARES -> Set.of("meetingId", "shares");
                case START_EXPANSION, COMPLETE_EXPANSION, SELL_RESIDUAL -> Set.of("projectId");
            };
            if (!arguments.keySet().equals(expected)) {
                throw new IllegalArgumentException("资源动作字段不符：" + action + " / " + expected);
            }
            for (var entry : arguments.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    throw new IllegalArgumentException("资源字段不能为空：" + entry.getKey());
                }
                if (Set.of("quantityKg", "mwh", "period", "shares").contains(entry.getKey())) {
                    if (!entry.getValue().matches("0|[1-9][0-9]*")) {
                        throw new IllegalArgumentException("资源数量必须为精确非负整数");
                    }
                    Long.parseLong(entry.getValue());
                }
            }
            arguments = Collections.unmodifiableMap(new TreeMap<>(arguments));
        }

        String field(String name) { return arguments.get(name); }
        long integer(String name) { return Long.parseLong(field(name)); }
    }

    /** 已由 WorldRuntime 核验身份与来源后的纯候选计算，不自行提交或建立预算。 */
    static IntegratedResourcesModel.Outcome apply(IntegratedResourcesModel.State state,
            FundState funds, FundPayer payer, long now, String commandId, Request request) {
        return apply(state, funds, payer, now, commandId, request, null);
    }

    static IntegratedResourcesModel.Outcome apply(IntegratedResourcesModel.State state,
            FundState funds, FundPayer payer, long now, String commandId, Request request,
            ResourceCompanyRun.ExpansionOffer offer) {
        String owner = state.config().companyOwnerId();
        if (Set.of(Action.START_EXPANSION, Action.COMPLETE_EXPANSION, Action.SELL_RESIDUAL).contains(request.action()) && offer == null)
            return new IntegratedResourcesModel.Outcome(false, "EXPANSION_NOT_ENABLED", state, funds);
        return switch (request.action()) {
            case START_EXPANSION -> IntegratedResourcesModel.startExpansion(state, now, owner, commandId,
                    request.field("projectId"), offer.capex(), offer.buildMillis(), offer.rampPeriods(),
                    offer.addCapacityKg(), offer.permitRef(), funds, payer);
            case COMPLETE_EXPANSION -> IntegratedResourcesModel.completeExpansion(state, now, owner, commandId,
                    request.field("projectId"), offer.failed(), funds);
            case SELL_RESIDUAL -> IntegratedResourcesModel.sellResidual(state, now, owner, commandId,
                    request.field("projectId"), state.residualAssets().getOrDefault(request.field("projectId"), java.math.BigDecimal.ZERO), funds, payer);
            case BEGIN_EXTRACTION -> IntegratedResourcesModel.beginExtraction(state, now, owner,
                    commandId, request.field("jobId"), request.field("seamId"),
                    request.integer("quantityKg"), funds, payer);
            case FINISH_EXTRACTION -> IntegratedResourcesModel.finishExtraction(state, now, owner,
                    commandId, request.field("jobId"), funds);
            case SELL_COAL -> IntegratedResourcesModel.sellCoal(state, now, owner, commandId,
                    request.field("deliveryId"), request.integer("quantityKg"),
                    request.field("receivableId"), funds);
            case TRANSFER_TO_PLANT -> IntegratedResourcesModel.transferToPlant(state, now, owner,
                    commandId, request.field("transferId"), request.integer("quantityKg"), funds);
            case BEGIN_POWER -> IntegratedResourcesModel.beginPowerDelivery(state, now, owner,
                    commandId, request.field("jobId"), request.integer("mwh"),
                    request.field("receivableId"), funds, payer);
            case FINISH_POWER -> IntegratedResourcesModel.finishPowerDelivery(state, now, owner,
                    commandId, request.field("jobId"), funds);
            case ABORT_JOB -> IntegratedResourcesModel.abortJob(state, now, owner, commandId,
                    request.field("jobId"), funds);
            case COLLECT -> IntegratedResourcesModel.collect(state, now, owner, commandId,
                    request.field("receivableId"), funds, payer);
            case WRITE_OFF -> IntegratedResourcesModel.writeOff(state, now, owner, commandId,
                    request.field("receivableId"), funds);
            case EXIT_BUSINESS, SETTLE_BILL, SUBSCRIBE_SHARES -> new IntegratedResourcesModel.Outcome(false, "RESOURCE_WIND_DOWN_NOT_ENABLED", state, funds);
            case CLOSE_PERIOD -> IntegratedResourcesModel.closePeriod(state, now, owner, commandId,
                    request.integer("period"), funds, payer);
        };
    }
}
