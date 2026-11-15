package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.util.*;

/** 三制造经理的有限经营工具；身份由观察会话绑定，未来情景不进入模型输入。 */
public final class CompanyOperationsDecisionTools {
    private CompanyOperationsDecisionTools() { }
    /** 经理经营工具。 */
    public static final String ACT = "operations.act";
    /** 实验管理工时；到货、生产和认证周期另计。 */
    public static final long ACTION_MILLIS = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .registerModule(new SimpleModule().addSerializer(BigDecimal.class, ToStringSerializer.instance)
                    .addSerializer(Long.class, ToStringSerializer.instance)
                    .addSerializer(Long.TYPE, ToStringSerializer.instance));

    /** 模型只选择本公司、动作及业务字段，不接受人物、任职或观察位置。 */
    public record Draft(String companyId, String action, JsonNode fields) { }

    /** 在显式新档中增加工具与实际开局人物的有限调用预算。 */
    public static DecisionModel.Config config(DecisionModel.Config base, List<String> people) {
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(ACT, ACTION_MILLIS);
        Map<String, DecisionModel.Usage> caps = new TreeMap<>(base.budget().personCap());
        for (String person : people) caps.putIfAbsent(person, DecisionModel.Usage.unknownCost(20, 200_000, 40_000));
        return new DecisionModel.Config(base.providerKind(), base.providerId(), base.pricingVersion(),
                base.thinkMillisByWake(), actions, base.limits(),
                new DecisionModel.Budget(base.budget().runCap(), caps), base.profile());
    }

    /** 网页和模型共用严格字段解析：金额文本、精确整数、动作字段白名单。 */
    public static CompanyOperationsRun.Fields fields(JsonNode node, CompanyOperationsRun.Action action) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (node == null || !node.isObject() || action.completionOnly()) throw new IllegalArgumentException("经营动作或字段非法");
        Map<String, Class<?>> types = new HashMap<>();
        for (var field : CompanyOperationsRun.Fields.class.getRecordComponents()) types.put(field.getName(), field.getType());
        var normalized = JSON.createObjectNode();
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            Class<?> type = types.get(name);
            JsonNode value = node.get(name);
            if (type == null || value.isNull()) throw new IllegalArgumentException("未知或空的经营字段：" + name);
            if (type == String.class) {
                if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("字段必须是文本：" + name);
                normalized.put(name, value.asText());
            } else if (type == BigDecimal.class) {
                if (!value.isTextual() || !value.asText().matches("[0-9]+(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("金额必须是最多两位小数的文本：" + name);
                normalized.put(name, value.asText());
            } else if (type == Boolean.class) {
                if (!value.isBoolean()) throw new IllegalArgumentException("字段必须是布尔值：" + name);
                normalized.put(name, value.booleanValue());
            } else {
                if (!(value.isIntegralNumber() && value.canConvertToLong())
                        && !(value.isTextual() && value.asText().matches("[0-9]+"))) throw new IllegalArgumentException("数量和时点必须是精确整数：" + name);
                long number = Long.parseLong(value.asText());
                if (type == Integer.class) normalized.put(name, Math.toIntExact(number));
                else normalized.put(name, number);
            }
        }
        return JSON.treeToValue(normalized, CompanyOperationsRun.Fields.class).require(action);
    }

    /** 本公司公开方案和真实已发生台账；不输出客户私有预算、认证计划或扩产失败种子。 */
    static JsonNode observation(CompanyOperationsRun run, BoardRun board, String company, long now,
            Map<String, CashLedger.CashAccount> accounts) {
        Map<String, Object> view = new TreeMap<>(CompanyOperationsRuntime.observation(run, board, company, now));
        var ledger = run.company(company).ledger();
        view.put("cash", accounts.get(run.company(company).cashAccountId()).cash());
        view.put("materials", ledger.materials());
        view.put("productsOnHand", ledger.products());
        view.put("purchases", ledger.purchases());
        view.put("batches", ledger.batches());
        view.put("contracts", ledger.contracts());
        view.put("receivables", ledger.receivables());
        view.put("trials", ledger.trials());
        view.put("expansions", ledger.expansions());
        view.put("reports", run.reports().values().stream().filter(r -> company.equals(r.companyPartyId())).toList());
        Map<String, String> fields = new TreeMap<>();
        fields.put("ORDER_MATERIAL", "materialId:string, quantity:long");
        fields.put("START_BATCH", "productId:string, generation:int, quantity:long");
        fields.put("REQUEST_CERT", "trialId:string, customerId:string, productId:string, generation:int, round:int, evidenceId:string(本人真实阅读标识)");
        fields.put("SIGN_CONTRACT", "contractId:string, customerId:string, productId:string, generation:int, quantity:long, unitPrice:decimal-string, promisedAt:long, expiresAt:long");
        fields.put("DELIVER", "contractId:string, quantity:long, receivableId:string");
        for (String action : List.of("COLLECT", "WRITE_OFF_RECEIVABLE")) fields.put(action, "receivableId:string");
        for (String action : List.of("MARK_OVERDUE", "CANCEL_CONTRACT")) fields.put(action, "contractId:string");
        fields.put("START_EXPANSION", "projectId:string, capex:decimal-string, buildMillis:long, rampPeriods:int, addCapacity:long(严格沿已给报价)");
        fields.put("SELL_RESIDUAL", "projectId:string, price:decimal-string");
        view.put("actionFields", fields);
        view.put("executionRule", "fields仅填写所选动作字段；时点为绝对模拟毫秒。管理动作需1000毫秒；报价、到货和完工按实际执行时点判断。拒绝保留回报，可观望，不得猜测未来结果。");
        return JSON.valueToTree(view);
    }
}
