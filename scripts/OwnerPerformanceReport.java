import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.PerformanceModel;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.SettlementCash;
import edu.cufe.auction.world.ValuationModel;
import edu.cufe.auction.world.WorldJournalScanner;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;

/** 从已存在的封卷副本读取开局和截止财产权，调用运行本身的资金及绩效投影。 */
public final class OwnerPerformanceReport {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new SimpleModule()
            .addSerializer(BigDecimal.class, new JsonSerializer<BigDecimal>() {
                @Override public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider)
                        throws IOException { generator.writeString(value.toPlainString()); }
            }));

    private record Cut(long position, long time, Map<String, CashLedger.CashAccount> accounts,
                       Map<String, ValuationModel.Value> values, Map<String, SettlementCash.Amounts> settlements) { }

    private static WorldRuntime reopen(String runId, Path file) throws Exception {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) throw new IOException("缺少已封存的非空世界日志");
        return switch (runId) {
            case RunManifest.EDITORIAL_SOURCE_RUN_ID -> ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file);
            case RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID -> ComposedIndustryScenario.openTenCompanyAutonomy(file);
            case RunManifest.UNPRICED_REFERENCE_RUN_ID -> ComposedIndustryScenario.openUnpricedReferenceOperating(file, null);
            case RunManifest.OWNER_BANK_VALUATION_RUN_ID -> ComposedIndustryScenario.openOwnerBankValuationOperating(file, null);
            default -> throw new IllegalArgumentException("未登记此运行的严开入口：" + runId);
        };
    }

    private static Cut readCut(WorldRuntime world) throws IOException {
        var snapshot = world.snapshot();
        var values = new TreeMap<String, ValuationModel.Value>();
        for (var owner : world.openingRegistry().orElseThrow().owners())
            values.put(owner.ownerId(), world.valuation(owner.ownerId()));
        return new Cut(snapshot.position(), snapshot.timeMillis(), snapshot.accounts(), values,
                world.cashSettlementsAt(snapshot.position()));
    }

    private static BigDecimal cashTotal(Cut cut) {
        return cut.accounts().values().stream().map(CashLedger.CashAccount::cash)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    private static void accounts(ObjectNode out, Cut start, Cut end) {
        if (!start.accounts().keySet().equals(end.accounts().keySet()))
            throw new IllegalStateException("两截面现金账户清单不同");
        var rows = out.putArray("accounts");
        for (var id : new java.util.TreeSet<>(end.accounts().keySet())) {
            var opening = start.accounts().get(id);
            var closing = end.accounts().get(id);
            if (!opening.ownerId().equals(closing.ownerId())) throw new IllegalStateException("账户权属改变：" + id);
            var row = rows.addObject().put("accountId", id).put("ownerId", closing.ownerId())
                    .put("openingCash", opening.cash().toPlainString()).put("cutoffCash", closing.cash().toPlainString())
                    .put("cashChange", closing.cash().subtract(opening.cash()).toPlainString());
            row.set("openingSettlement", JSON.valueToTree(start.settlements().get(id)));
            row.set("cutoffSettlement", JSON.valueToTree(end.settlements().get(id)));
        }
        out.put("openingTotalCash", cashTotal(start).toPlainString());
        out.put("cutoffTotalCash", cashTotal(end).toPlainString());
        out.put("totalCashChange", cashTotal(end).subtract(cashTotal(start)).toPlainString());
    }

    private static void owners(ObjectNode out, WorldRuntime world, Cut start, Cut end) {
        var returns = world.openingReturns();
        if (!returns.keySet().equals(start.values().keySet()) || !returns.keySet().equals(end.values().keySet()))
            throw new IllegalStateException("首末估值与已登记财产主体不符");
        var rows = out.putArray("owners");
        var statuses = new TreeMap<String, Integer>();
        for (OpeningRegistry.PropertyOwner owner : world.openingRegistry().orElseThrow().owners()) {
            String id = owner.ownerId();
            var result = returns.get(id);
            var row = rows.addObject().put("ownerId", id).put("kind", owner.kind().name()).put("partyId", owner.partyId());
            var opening = start.values().get(id);
            var closing = end.values().get(id);
            row.set("openingValuation", JSON.valueToTree(opening));
            row.set("cutoffValuation", JSON.valueToTree(closing));
            row.put("cashChange", closing.cash().subtract(opening.cash()).toPlainString());
            if (opening.netWorth() == null || closing.netWorth() == null) row.putNull("netWorthChange");
            else row.put("netWorthChange", closing.netWorth().subtract(opening.netWorth()).toPlainString());
            PerformanceModel.Summary summary = result.value();
            row.set("performance", JSON.valueToTree(summary));
            row.put("performanceError", result.error());
            statuses.merge(summary == null ? "CALCULATION_ERROR" : summary.status().name(), 1, Integer::sum);
        }
        out.set("performanceStatuses", JSON.valueToTree(statuses));
    }

    /** @param args 可丢弃的完整封卷副本、仅首帧的临时文件、全新报告路径 */
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]), first = Path.of(args[1]), output = Path.of(args[2]);
        String id;
        try (var stream = Files.newOutputStream(first, StandardOpenOption.CREATE_NEW)) {
            var prefix = WorldJournalScanner.readPrefix(file, 1, stream);
            if (prefix.first() == null || prefix.first().position() != 1 || !"__open__".equals(prefix.first().commandId()))
                throw new IOException("本报告需要从第一帧开始的独立开局");
            id = prefix.first().runId();
        }
        Cut start;
        try (var world = reopen(id, first)) { start = readCut(world); }
        try (var world = reopen(id, file)) {
            var end = readCut(world);
            var report = JSON.createObjectNode().put("schema", 1).put("runId", id)
                    .put("startPosition", start.position()).put("startTimeMillis", start.time())
                    .put("endPosition", end.position()).put("endTimeMillis", end.time());
            report.put("valuationBasis", "冻结世界当前账户和权益的原生估值。无价和机构经营范围不全时保留状态及空净值。");
            report.put("performanceBasis", "从第一帧至截止确认帧，按流前净值与上一帧流后净值链接。净资产差额包含资本流。收益和最大回撤是比值，指数起点为1。历史估值不完整时全期结果为空。");
            report.put("cashBasis", "现金为账户余额，交易冻结已包含在内。结算明细为空表示本档无该账户的原生结算分桶。");
            accounts(report, start, end);
            owners(report, world, start, end);
            try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) { JSON.writeValue(stream, report); }
        }
    }
}
