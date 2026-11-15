package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 陈酿库存公司经理的真实观察与有限动作。
 *
 * <p><b>只看公开与已发生</b>：投影包含渠道公开配额与底价、当前寄售价、供应商公开报价（原料、
 * 转产、固定费）、产能与陈酿/货架参数，以及自己已经发生的订单、原料 lot、批次、寄售 lot、
 * 成交、报损与分渠道账。<b>不含</b>冻结的未来各期需求序列、终端消费者现金、任何外部账户标识，
 * 也不含世界合计现金。</p>
 *
 * <p><b>动作有限</b>：只列出经理可执行的动作字段；终端售出与关期由系统触发，不在这里打开。</p>
 *
 * <p><b>金额</b>一律用“整数分”文本（{@code 1200} 表示 12.00），数量为非负整数文本。</p>
 */
public final class AgedDecisionTools {

    private AgedDecisionTools() {
    }

    /** 陈酿经营工具。 */
    public static final String ACT = "aged.act";

    /** 管理工时；陈酿、到期与关期另有真实期间边界。 */
    public static final long ACTION_MILLIS = 1_000;

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
     * 模型与网页共享的严格字段解析：只接受本动作字段、纯文本值；系统动作在这里直接拒绝。
     *
     * @param draft 模型草稿
     * @return 已校验请求
     */
    public static AgedOperations.Request request(Draft draft) {
        if (draft == null || draft.action() == null) {
            throw new IllegalArgumentException("陈酿动作非法");
        }
        AgedOperations.Action action;
        try {
            action = AgedOperations.Action.valueOf(draft.action());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("陈酿动作非法：" + draft.action());
        }
        if (!AgedOperations.personExecutable(action)) {
            throw new IllegalArgumentException("该陈酿动作不由人物触发：" + action);
        }
        if (draft.arguments() == null || !draft.arguments().isObject()) {
            throw new IllegalArgumentException("陈酿参数必须为对象");
        }
        Map<String, String> values = new TreeMap<>();
        draft.arguments().fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()) {
                throw new IllegalArgumentException("陈酿参数必须为精确文本：" + field.getKey());
            }
            values.put(field.getKey(), field.getValue().asText());
        });
        return new AgedOperations.Request(action, values);
    }

    /**
     * 只投影本公司公开规则与已发生台账。
     *
     * @param run             公司子账
     * @param funds           当前世界资金三态（只用来读本公司现金与账簿）
     * @param now             当前时点
     * @param reports         本公司已发生的执行回报
     * @param remainingBudget 本期剩余预算（无预算时传 null）
     * @return 观察投影
     */
    public static JsonNode observation(AgedCompanyRun run, FundState funds, long now,
                                       List<CompanyOperationsRun.ReportEntry> reports,
                                       BigDecimal remainingBudget) {
        AgedInventoryModel.State ledger = run.ledger();
        AgedInventoryModel.Config model = ledger.config();
        AgedInventoryModel.Books books = AgedInventoryModel.books(ledger, funds.accounts());
        Map<String, Object> view = new TreeMap<>();
        view.put("companyId", run.companyPartyId());
        view.put("cash", funds.cash(model.companyAccountId()));
        view.put("reports", reports);
        view.put("period", model.periodOf(now));
        view.put("closedPeriods", ledger.closedPeriods());
        view.put("orders", ledger.orders());
        view.put("rawLots", ledger.rawLots());
        view.put("batches", ledger.batches());
        view.put("shipmentLots", ledger.shipmentLots());
        view.put("sales", ledger.sales());
        view.put("spoilage", ledger.spoilage());
        view.put("channelLedgers", ledger.channelLedgers());
        view.put("currentPrices", ledger.currentPrices());
        view.put("fixedAccrued", ledger.fixedAccrued());
        view.put("fixedPaid", ledger.fixedPaid());
        view.put("fixedPayable", ledger.fixedPayable());
        view.put("closes", ledger.closes());
        view.put("inventoryCost", books.inventoryCost());
        view.put("receivable", books.receivable());
        view.put("contractLiability", books.contractLiability());
        view.put("refundPayable", books.refundPayable());
        view.put("revenue", books.revenue());
        view.put("cogs", books.cogs());
        view.put("spoilageLoss", books.spoilageLoss());
        view.put("profit", books.profit());
        view.put("assets", books.assets());
        view.put("liabilities", books.liabilities());
        view.put("equity", books.equity());
        view.put("producedUnits", books.producedUnits());
        view.put("immatureUnits", books.immatureUnits());
        view.put("matureWarehouseUnits", books.matureWarehouseUnits());
        view.put("consignmentUnits", books.consignmentUnits());
        view.put("soldUnits", books.soldUnits());
        view.put("spoiledUnits", books.spoiledUnits());

        List<Map<String, Object>> channels = new ArrayList<>();
        for (AgedInventoryModel.ChannelSpec channel : model.channels()) {
            Map<String, Object> publicView = new TreeMap<>();
            publicView.put("channelId", channel.channelId());
            publicView.put("quotaPerPeriod", channel.quotaPerPeriod());
            publicView.put("priceFloor", channel.priceFloor());
            channels.add(publicView);
        }
        view.put("channels", channels);
        view.put("rawUnitCost", model.rawUnitCost());
        view.put("conversionPerUnit", model.conversionPerUnit());
        view.put("fixedPerPeriod", model.fixedPerPeriod());
        view.put("capacityPerPeriod", model.capacityPerPeriod());
        view.put("agingPeriods", model.agingPeriods());
        view.put("shelfPeriods", model.shelfPeriods());
        view.put("spoilageBp", model.spoilageBp());
        view.put("prepayBp", model.prepayBp());
        view.put("periodMillis", model.periodMillis());
        view.put("remainingBudget", remainingBudget);

        Map<String, String> actions = new TreeMap<>();
        actions.put("ORDER_RAW", "orderId, units");
        actions.put("RECEIVE_RAW", "orderId");
        actions.put("PRODUCE", "batchId, units");
        actions.put("SET_PRICE", "channelId, priceCents");
        actions.put("SHIP", "shipmentId, channelId, units");
        actions.put("COLLECT", "channelId, amountCents");
        actions.put("PAY_REFUND", "channelId, amountCents");
        actions.put("PAY_FIXED", "amountCents");
        view.put("actionFields", actions);

        view.put("rules", "arguments 所有值为文本，只填所选动作字段；金额字段用整数分文本"
                + "（priceCents=4000 表示 40.00），数量为非负整数。下单不付现，到货才付供应商；"
                + "投产按原料 lot FIFO 消耗并付转产费；批次需陈酿满 " + model.agingPeriods()
                + " 期才可发货，货架期满全额报废。发货是寄售：渠道按 " + model.prepayBp()
                + "/10000 预付，发货不确认收入，终端售出才确认；回款只冲应收且受渠道现金限制；"
                + "到期未售按报损/过期处理，未抵预收转退款应付。渠道未来需求与终端现金不公开，"
                + "不得推测；每期产能 " + model.capacityPerPeriod() + " 单位是累计上限。"
                + "终端售出与关期由系统触发，不由人物触发。");
        return JSON.valueToTree(view);
    }
}
