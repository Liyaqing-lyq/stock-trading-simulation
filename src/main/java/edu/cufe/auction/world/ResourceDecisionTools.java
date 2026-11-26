package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.util.*;

/** 资源经理的真实观察与有限动作；不包含客户私有预算或其它公司台账。 */
public final class ResourceDecisionTools {
    private ResourceDecisionTools() { }
    /** 资源经营工具及实验管理工时。 */
    public static final String ACT = "resource.act";
    /** 管理工时；开采与发电作业另有完成时点。 */
    public static final long ACTION_MILLIS = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .registerModule(new SimpleModule().addSerializer(BigDecimal.class, ToStringSerializer.instance)
                    .addSerializer(Long.class, ToStringSerializer.instance).addSerializer(Long.TYPE, ToStringSerializer.instance));
    /** 公司和人物由观察任职确定，模型只能填写动作和业务参数。 */
    public record Draft(String action, JsonNode arguments) { }

    /** 显式扩充新档工具，保持有限的原预算。 */
    public static DecisionModel.Config config(DecisionModel.Config base) {
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool()); actions.put(ACT, ACTION_MILLIS);
        return new DecisionModel.Config(base.providerKind(), base.providerId(), base.pricingVersion(), base.thinkMillisByWake(),
                actions, base.limits(), base.budget(), base.profile());
    }

    /** 模型与网页共享精确字段解析；数量文本只允许非负整数，完工/关期不能自报。 */
    public static ResourceOperations.Request request(Draft draft) {
        var action = ResourceOperations.Action.valueOf(draft.action());
        if (ResourceOperations.systemOnly(action) || draft.arguments() == null || !draft.arguments().isObject()) throw new IllegalArgumentException("资源动作非法");
        Map<String, String> values = new TreeMap<>();
        draft.arguments().fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()) throw new IllegalArgumentException("资源参数必须为精确文本");
            values.put(field.getKey(), field.getValue().asText());
        });
        return new ResourceOperations.Request(action, values);
    }

    /** 只投影本公司可执行方案与已发生经营状态。 */
    public static JsonNode observation(ResourceCompanyRun run, BoardRun board, long now, BigDecimal cash, List<CompanyOperationsRun.ReportEntry> reports) {
        return observation(run, board, now, cash, reports, false);
    }

    /** 已启用停业的资源发行人同时展示停业决议、应付旧账和经理可办动作。 */
    public static JsonNode observation(ResourceCompanyRun run, BoardRun board, long now, BigDecimal cash,
                                       List<CompanyOperationsRun.ReportEntry> reports, boolean windDown) {
        var ledger = run.ledger(); var config = ledger.config();
        Map<String, Object> view = new TreeMap<>();
        view.put("companyId", run.companyPartyId()); view.put("cash", cash); view.put("reports", reports);
        view.put("mineCoal", ledger.mineCoal()); view.put("plantFuel", ledger.plantFuel());
        view.put("jobs", ledger.jobs()); view.put("receivables", ledger.receivables());
        view.put("periodUse", ledger.periodUse()); view.put("closedPeriods", ledger.closedPeriods());
        view.put("seams", config.seams()); view.put("remainingReservesKg", ledger.seamRemaining());
        view.put("coalPricePerKg", config.coalPricePerKg()); view.put("powerPricePerMwh", config.powerPricePerMwh());
        view.put("coalKgPerMwh", config.coalKgPerMwh()); view.put("conversionCostPerMwh", config.conversionCostPerMwh());
        view.put("mineCapacityKg", config.baseMineCapacityKgPerPeriod()); view.put("generatorCapacityMwh", config.generatorCapacityMwhPerPeriod());
        view.put("transportCapacityKg", config.transportCapacityKgPerPeriod());
        var category = BoardModel.BudgetCategory.PROCUREMENT;
        long period = now / IntegratedResourcesModel.PERIOD_MILLIS;
        view.put("remainingBudget", board.board().budget(period, category) == null ? null : BoardModel.availableBudget(board.board(), period, category));
        Map<String, String> actions = new TreeMap<>();
        actions.put("BEGIN_EXTRACTION", "jobId, seamId, quantityKg"); actions.put("SELL_COAL", "deliveryId, quantityKg, receivableId");
        actions.put("TRANSFER_TO_PLANT", "transferId, quantityKg"); actions.put("BEGIN_POWER", "jobId, mwh, receivableId");
        actions.put("ABORT_JOB", "jobId"); actions.put("COLLECT", "receivableId"); actions.put("WRITE_OFF", "receivableId");
        if (run.expansionOffer() != null) {
            var offer = run.expansionOffer();
            view.put("expansionOffer", Map.of("capex", offer.capex(), "buildMillis", offer.buildMillis(),
                    "rampPeriods", offer.rampPeriods(), "addCapacityKg", offer.addCapacityKg()));
            view.put("expansions", ledger.expansions()); view.put("residualAssets", ledger.residualAssets());
            view.put("mineCapacityKg", IntegratedResourcesModel.miningCapacityAt(ledger, period));
            actions.put("START_EXPANSION", "projectId"); actions.put("SELL_RESIDUAL", "projectId");
        }
        if (windDown) {
            view.put("cessation", run.cessation());
            view.put("cessationDecisions", board.board().meetings().values().stream().filter(m -> m.topic() == BoardModel.Topic.RESOURCE_CESSATION
                    && m.status() == BoardModel.MeetingStatus.ADOPTED).map(BoardModel.Meeting::meetingId).sorted().toList());
            view.put("expenseBills", ledger.bills());
            if (ledger.exited()) actions.keySet().retainAll(Set.of("COLLECT", "WRITE_OFF", "SELL_RESIDUAL"));
            else actions.put("EXIT_BUSINESS", "meetingId");
            actions.put("SETTLE_BILL", "period");
        }
        view.put("actionFields", actions);
        view.put("rules", "arguments所有值为文本，只填所选动作字段。工时1000毫秒，作业到期由系统完成。内部转移不产生集团外部收入或现金；交付不等于回款。客户容量、需求和现金有限，不能猜测接受或未来结果；可观望。");
        return JSON.valueToTree(view);
    }
}
