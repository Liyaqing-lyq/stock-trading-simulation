package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.util.*;

/** 水电经理只能看到自家已发生状态、公开物理约束和合同，不看未来来水。 */
public final class HydroDecisionTools {
    private HydroDecisionTools() { }
    /** 模型动作及15分钟实验管理工时。 */
    public static final String ACT = "hydro.act";
    /** 调度动作也需要真实工作；检修停机另有冻结时长。 */
    public static final long ACTION_MILLIS = 15 * HydroModel.PHYSICS_TICK_MILLIS;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .registerModule(new SimpleModule().addSerializer(BigDecimal.class, ToStringSerializer.instance)
                    .addSerializer(Long.class, ToStringSerializer.instance).addSerializer(Long.TYPE, ToStringSerializer.instance));
    /** 身份与公司由真实任职决定。 */
    public record Draft(String action, JsonNode arguments) { }
    /** 仅给显式新档加入工具，不改调用额度。 */
    public static DecisionModel.Config config(DecisionModel.Config base) {
        Map<String,Long> actions = new TreeMap<>(base.actionMillisByTool()); actions.put(ACT, ACTION_MILLIS);
        return new DecisionModel.Config(base.providerKind(), base.providerId(), base.pricingVersion(), base.thinkMillisByWake(), actions, base.limits(), base.budget(), base.profile());
    }
    /** 网页与模型共用严格解析；系统结算不得自报。 */
    public static HydroOperations.Request request(Draft draft) {
        var action = HydroOperations.Action.valueOf(draft.action());
        if (HydroOperations.systemOnly(action) || draft.arguments() == null || !draft.arguments().isObject()) throw new IllegalArgumentException("水电动作非法");
        Map<String,String> fields = new TreeMap<>();
        draft.arguments().fields().forEachRemaining(f -> {
            if (!f.getValue().isTextual()) throw new IllegalArgumentException("水电参数必须为文本");
            fields.put(f.getKey(), f.getValue().asText());
        });
        return new HydroOperations.Request(action, fields);
    }
    /** 同一个实际物理截止点的观察，不暴露冻结来水数组。 */
    public static JsonNode observation(HydroCompanyRun run, BoardRun board, BigDecimal cash, List<CompanyOperationsRun.ReportEntry> reports) {
        var config = run.config(); var ledger = run.ledger(); long period = ledger.watermark() / IntegratedResourcesModel.PERIOD_MILLIS;
        Map<String,Object> view = new TreeMap<>();
        view.put("companyId", run.companyPartyId()); view.put("cash", cash); view.put("physicalAsOf", ledger.watermark());
        view.put("physical", config.model().physical()); view.put("contract", config.model().contract()); view.put("terms", config.model().terms());
        view.put("plant", ledger.plant()); view.put("reports", reports);
        view.put("maintenanceOffer", Map.of("cost", config.maintenanceCost(), "durationMillis", config.maintenanceMillis()));
        view.put("remainingBudget", board.board().budget(period, BoardModel.BudgetCategory.PROCUREMENT) == null ? null
                : BoardModel.availableBudget(board.board(), period, BoardModel.BudgetCategory.PROCUREMENT));
        view.put("actionFields", Map.of("DISPATCH_FLOW", "cubicMetresPerHour", "DISPATCH_OUTPUT", "outputKw", "MAINTENANCE", "", "COLLECT", "amount"));
        view.put("rules", "参数全为文本；工作15分钟后生效。生态及指定下泄优先，发电受实际来水、库容、装机和购电合同约束。来水不等于可发电量，收入不等于回款；未来来水未知。可观望。");
        return JSON.valueToTree(view);
    }
}
