package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数字服务公司经理的真实观察与有限动作。
 *
 * <p><b>只看公开与已发生</b>：投影包含本公司公开报价（服务单价、单位服务成本、容量、客户当期公开
 * 需求上限）与自己已经发生的阶段、合同、应收、账单、关期数；<b>不含</b>客户每期私有预算、
 * 阶段冻结成败、授权引用表和任何账户标识。</p>
 *
 * <p><b>动作有限</b>：只列出经理可执行的动作字段；到期完工与关期由系统触发，外部注资由世界在
 * 取得真实治理决议后触发，两者都不在这里打开。模型只能填动作与业务参数，公司和人物由观察任职
 * 确定。</p>
 *
 * <p><b>金额</b>一律用“整数分”文本（{@code 4000} 表示 40.00），数量为非负整数文本，避免任何
 * 浮点往返。</p>
 */
public final class DigitalDecisionTools {

    private DigitalDecisionTools() {
    }

    /** 本档数字服务董事会可表决的停业条款。 */
    public static final String CESSATION_TERMS = "DIGITAL_CESSATION：主席可召集数字服务停业会议，五席中至少四席同意，再由在任经理执行。"
            + "执行后停止新试点、验证、商用准入和新签约。已有阶段按原期结束，原合同可交付、终止、退款、收款，欠款和股份保留。";

    /** 数字服务经营工具。 */
    public static final String ACT = "digital.act";

    /** 管理工时；阶段完成与关期另有真实到期时点。 */
    public static final long ACTION_MILLIS = 1_000;

    /** 同一期间各合同共用的公司资源与公开需求，金额只使用可支配现金及获批预算。 */
    static JsonNode deliveryRoom(DigitalServiceModel.State ledger, long now, BigDecimal freeCash,
                                 BigDecimal remainingBudget) {
        long period = now / DigitalServiceModel.PERIOD_MILLIS;
        long delivered = ledger.deliveredIn(period);
        long capacity = ledger.config().serviceCapacityUnitsPerPeriod() - delivered;
        long demand = ledger.config().customerDemandUnitsPerPeriod() - delivered;
        long uncommittedDemand = Math.max(0, demand - ledger.reservedIn(period));
        BigDecimal unitCost = ledger.config().serviceUnitCost();
        long covered = capacity;
        if (unitCost.signum() > 0) {
            BigDecimal spending = freeCash.min(remainingBudget == null ? BigDecimal.ZERO : remainingBudget);
            covered = spending.max(BigDecimal.ZERO).divideToIntegralValue(unitCost)
                    .min(BigDecimal.valueOf(capacity)).longValueExact();
        }
        return JSON.createObjectNode().put("freeCompanyCash", freeCash.toPlainString())
                .put("remainingCapacityUnits", Long.toString(capacity))
                .put("cashBudgetCapacityUnits", Long.toString(covered))
                .put("uncommittedCurrentDemandUnits", Long.toString(uncommittedDemand))
                .put("scope", "各合同共用以上额度。可按合同未交量分批履约，每批单独确认收入和应收。"
                        + "往期合同还受本期未占用需求约束，每次执行按当时合同、预算、资金和客户条件重新核对。");
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .registerModule(new SimpleModule()
                    .addSerializer(BigDecimal.class, ToStringSerializer.instance)
                    .addSerializer(Long.class, ToStringSerializer.instance)
                    .addSerializer(Long.TYPE, ToStringSerializer.instance));

    /** 公司和人物由观察任职确定，模型只能填写动作和业务参数。 */
    public record Draft(String action, JsonNode arguments) {
    }

    /**
     * 显式扩充新档工具，保持有限的原预算。
     *
     * @param base 原决定配置
     * @return 追加了 {@link #ACT} 的新配置
     */
    public static DecisionModel.Config config(DecisionModel.Config base) {
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(ACT, ACTION_MILLIS);
        return new DecisionModel.Config(base.providerKind(), base.providerId(),
                base.pricingVersion(), base.thinkMillisByWake(), actions, base.limits(),
                base.budget(), base.profile());
    }

    /**
     * 模型与网页共享的严格字段解析：只接受本动作字段、纯文本值；系统与治理动作在这里直接拒绝。
     *
     * @param draft 模型草稿
     * @return 已校验请求
     */
    public static DigitalOperations.Request request(Draft draft) {
        if (draft == null || draft.action() == null) {
            throw new IllegalArgumentException("数字服务动作非法");
        }
        DigitalOperations.Action action;
        try {
            action = DigitalOperations.Action.valueOf(draft.action());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("数字服务动作非法：" + draft.action());
        }
        if (!DigitalOperations.personExecutable(action)) {
            throw new IllegalArgumentException("该数字服务动作不由人物触发：" + action);
        }
        if (draft.arguments() == null || !draft.arguments().isObject()) {
            throw new IllegalArgumentException("数字服务参数必须为对象");
        }
        Map<String, String> values = new TreeMap<>();
        draft.arguments().fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()) {
                throw new IllegalArgumentException("数字服务参数必须为精确文本："
                        + field.getKey());
            }
            values.put(field.getKey(), field.getValue().asText());
        });
        return new DigitalOperations.Request(action, values);
    }

    /**
     * 只投影本公司公开规则与已发生台账。
     *
     * @param run             公司子账
     * @param now             当前时点
     * @param cash            公司现金（同批从世界现算）
     * @param reports         本公司已发生的执行回报
     * @param remainingBudget 本期剩余预算（无预算时传 null）
     * @return 观察投影
     */
    public static JsonNode observation(DigitalCompanyRun run, long now, BigDecimal cash,
                                       List<CompanyOperationsRun.ReportEntry> reports,
                                       BigDecimal remainingBudget) {
        return observation(run, now, cash, reports, remainingBudget, null, false);
    }

    /** 为启用停业条款的本公司经理显示真实的已通过决议、执行记录及可用动作。 */
    public static JsonNode observation(DigitalCompanyRun run, long now, BigDecimal cash,
                                       List<CompanyOperationsRun.ReportEntry> reports, BigDecimal remainingBudget,
                                       BoardModel.State board, boolean cessationEnabled) {
        DigitalServiceModel.State ledger = run.ledger();
        DigitalServiceModel.Config model = ledger.config();
        Map<String, Object> view = new TreeMap<>();
        view.put("companyId", run.companyPartyId());
        view.put("cash", cash);
        view.put("reports", reports);
        view.put("period", now / DigitalServiceModel.PERIOD_MILLIS);
        view.put("closedPeriods", ledger.closedPeriods());
        view.put("stages", ledger.stages());
        view.put("researchFailed", ledger.researchFailed());
        view.put("commercialApproved", ledger.commercialApproved());
        view.put("commercialApprovalId", ledger.commercialApprovalId());
        view.put("platformNet", ledger.platformNet());
        view.put("workInProgress", ledger.workInProgress());
        view.put("pilotExpense", ledger.pilotExpense());
        view.put("amortized", ledger.amortized());
        view.put("impairment", ledger.impairment());
        view.put("badDebtLoss", ledger.badDebtLoss());
        view.put("fixedExpenses", ledger.fixedExpenses());
        view.put("capitalContributions", ledger.capitalContributions());
        view.put("contracts", ledger.contracts());
        view.put("receivables", ledger.receivables());
        view.put("bills", ledger.bills());
        view.put("servicePricePerUnit", model.servicePricePerUnit());
        view.put("serviceUnitCost", model.serviceUnitCost());
        view.put("customerDemandUnitsPerPeriod", model.customerDemandUnitsPerPeriod());
        view.put("serviceCapacityUnitsPerPeriod", model.serviceCapacityUnitsPerPeriod());
        view.put("remainingBudget", remainingBudget);
        view.put("commercialPermit", model.approvals().keySet().stream().sorted().findFirst().orElse(""));
        view.put("stageQuotes", Map.of("PILOT",Map.of("cost",model.pilotCost(),"durationMillis",model.pilotDurationMillis()),"VALIDATION",Map.of("cost",model.validationCost(),"durationMillis",model.validationDurationMillis())));

        Map<String, String> actions = new TreeMap<>();
        actions.put("BEGIN_PILOT", "");
        actions.put("BEGIN_VALIDATION", "");
        actions.put("APPROVE_COMMERCIAL", "approvalId");
        actions.put("SIGN_CONTRACT", "contractId, quantityUnits, prepaidCents");
        actions.put("DELIVER", "contractId, units, receivableId");
        actions.put("TERMINATE", "contractId");
        actions.put("REFUND", "contractId, refundCents");
        actions.put("COLLECT", "receivableId");
        actions.put("WRITE_OFF", "receivableId");
        if (cessationEnabled) {
            Map<String, BigDecimal> unearned = new TreeMap<>();
            ledger.contracts().forEach((id, contract) -> unearned.put(id, contract.unearned()));
            view.put("unearnedByContract", unearned);
            view.put("cessationTerms", CESSATION_TERMS);
            view.put("cessation", run.cessation());
            view.put("cessationDecisions", board.meetings().values().stream()
                    .filter(m -> m.topic() == BoardModel.Topic.DIGITAL_CESSATION && m.status() == BoardModel.MeetingStatus.ADOPTED)
                    .map(BoardModel.Meeting::meetingId).sorted().toList());
            if (run.cessation() == null) actions.put("EXIT_BUSINESS", "meetingId");
            else actions.keySet().removeIf(name -> DigitalOperations.newBusiness(DigitalOperations.Action.valueOf(name)));
        }
        view.put("actionFields", actions);

        view.put("rules", "arguments 所有值为文本，只填所选动作字段；金额字段用整数分文本"
                + "（prepaidCents=4000 表示 40.00），数量为非负整数。PILOT 开工即费用化；"
                + "VALIDATION 先计入在研，通过后转平台资产并按期摊销，失败转减值且不得重试；"
                + "阶段成败在完成时由冻结情景给出，经理不得自报。签约只收预收、形成退款义务，"
                + "不确认收入；交付才确认收入与服务成本，未收部分转应收；终止只释放未交付承诺，"
                + "未赚预收仍须退。收款只冲应收。客户每期预算私有且不公开，签约与交付可能因超出"
                + "预算被拒；交付还受当期服务容量限制。关期由系统在期末执行，注资由世界在取得"
                + "真实治理决议后执行，两者都不由人物触发。");
        return JSON.valueToTree(view);
    }
}
