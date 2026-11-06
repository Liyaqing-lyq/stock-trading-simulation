package edu.cufe.auction.app;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 制造经营接入唯一世界日志的正式可复跑演示：不需要测试框架，也不写课程 {@code data} 目录。
 *
 * <p>用法：{@code WorldManufacturingDemo <dir> [prepare|full]}。{@code dir} 省略时使用
 * {@code logs/world-manufacturing-demo}（课程验收会整体清理 {@code data}，世界存档放在其外）；
 * 模式省略时按 {@code full} 恢复。</p>
 *
 * <ul>
 *   <li>{@code prepare}：在 16 日签约、17 日采购、18 日投产、20 日交付，并排好额外 5 件 COD
 *       与第 0 期账单付款；推进到 20 日交付后退出。磁盘上留下未收应收 1200、强制期末任务、
 *       额外采购待办与已排应付。</li>
 *   <li>{@code full}：恢复同一日志，推进到 35 日 + 1 毫秒，自动完成 30 日强制关期、35 日回款、
 *       已确认 50 元应付，得到公司现金 1150 / 设备 900 / 利润 50 / 权益 2050，世界现金 3600。
 *       随后以完全相同的命令重发并再次推进，验证不多付款、不多记费用。</li>
 * </ul>
 *
 * <p>这是 {@code model=none} 的确定性管理夹具：没有董事会、没有角色授权、没有新世界 GUI，
 * 也不调用真实模型。经营数字全部由纯核与唯一世界日志实际算出，没有伪造经营数或按股价改权益。</p>
 */
public final class WorldManufacturingDemo {

    private static final String RUN = "world-manufacturing-demo";
    private static final long SEED = 20261106L;
    private static final String SYMBOL = "CUFE1";
    private static final long DAY = ManufacturingModel.DAY_MILLIS;
    private static final String OWNER = "company";
    private static final String COMPANY_ACCOUNT = "CO";
    private static final String SUPPLIER = "SUP";
    private static final String CUSTOMER = "CUS";
    private static final String OPS = "OPS";
    private static final String INVESTOR = "INV";
    private static final ManufacturingRun.Config CONFIG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening(OWNER, COMPANY_ACCOUNT, SUPPLIER, CUSTOMER, OPS), SYMBOL);

    private WorldManufacturingDemo() {
    }

    /**
     * 运行演示。
     *
     * @param args 可选目录与模式（{@code prepare} / {@code full}）
     * @throws Exception 日志不可用或世界状态校验失败
     */
    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Path.of(args[0])
                : Path.of("logs", "world-manufacturing-demo");
        String mode = args.length > 1 ? args[1] : "full";
        if (!"prepare".equals(mode) && !"full".equals(mode)) {
            System.out.println("[WorldManufacturingDemo] 用法: WorldManufacturingDemo <dir> "
                    + "[prepare|full]");
            return;
        }
        Files.createDirectories(dir);
        Path file = dir.resolve("company.journal");
        System.out.println("[WorldManufacturingDemo] mode=" + mode + " journal=" + file);

        try (WorldRuntime runtime = new WorldRuntime(file, manifest(), openings(), registry(),
                CONFIG)) {
            if (fresh(runtime)) {
                schedulePlan(runtime);
            }
            if ("prepare".equals(mode)) {
                runtime.advanceTo(20 * DAY);
                System.out.println("[WorldManufacturingDemo] prepare 退出于 time="
                        + runtime.snapshot().timeMillis() + "，应收/强制期末/后续待办已持久");
            } else {
                runtime.advanceTo(35 * DAY + 1);
                // 同一进程内重发同一条外部命令：返回原 SCHEDULED，不重复付款
                System.out.println("[WorldManufacturingDemo] replay "
                        + runtime.schedulePayExpenseBill("demo:pay-bill-0", "demo-pay-bill-0",
                                35 * DAY + 1, OWNER, 0L));
                long position = runtime.snapshot().position();
                runtime.advanceTo(35 * DAY + 1);
                System.out.println("[WorldManufacturingDemo] replay position " + position + " -> "
                        + runtime.snapshot().position());
            }
            report(runtime, mode);
        }
    }

    /** 只在全新日志上排一次计划；已有运行只恢复并推进，重发同命令走幂等路径。 */
    private static boolean fresh(WorldRuntime runtime) {
        ManufacturingRun run = runtime.snapshot().manufacturing().orElseThrow();
        return runtime.snapshot().timeMillis() == 0L && run.reports().isEmpty()
                && run.ledger().purchaseOrders().isEmpty();
    }

    private static void schedulePlan(WorldRuntime runtime) throws Exception {
        System.out.println("[WorldManufacturingDemo] " + runtime.scheduleSalesContract(
                "demo:contract-1", "demo-contract-1", 16 * DAY, OWNER, "demo-contract-1", 10,
                money("120.00"), 20 * DAY));
        System.out.println("[WorldManufacturingDemo] " + runtime.schedulePurchaseOrder(
                "demo:po-1", "demo-po-1", 17 * DAY, OWNER, "demo-po-1", 10));
        System.out.println("[WorldManufacturingDemo] " + runtime.scheduleProduction(
                "demo:batch-1", "demo-batch-1", 18 * DAY + 1, OWNER, "demo-batch-1", 10));
        System.out.println("[WorldManufacturingDemo] " + runtime.scheduleDelivery(
                "demo:delivery-1", "demo-delivery-1", 20 * DAY, OWNER, "demo-contract-1", 10,
                "demo-ar-1"));
        // 额外 5 件 COD：公司只剩 200，到期付款 250 必然失败，资产必须零变化
        System.out.println("[WorldManufacturingDemo] " + runtime.schedulePurchaseOrder(
                "demo:po-extra", "demo-po-extra", 20 * DAY + 1, OWNER, "demo-po-extra", 5));
        System.out.println("[WorldManufacturingDemo] " + runtime.schedulePayExpenseBill(
                "demo:pay-bill-0", "demo-pay-bill-0", 35 * DAY + 1, OWNER, 0L));
    }

    private static void report(WorldRuntime runtime, String mode) {
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        ManufacturingRun run = snapshot.manufacturing().orElseThrow();
        ManufacturingModel.Report book = ManufacturingModel.report(run.ledger(), snapshot.accounts());
        System.out.println("[WorldManufacturingDemo] mode=" + mode
                + " time=" + snapshot.timeMillis()
                + " position=" + snapshot.position()
                + " pending=" + snapshot.pending().size()
                + " reports=" + run.reports().size()
                + " closedPeriods=" + run.ledger().closedPeriods()
                + " paused=" + runtime.isPaused());
        System.out.println("[WorldManufacturingDemo] 公司表 现金=" + book.companyCash()
                + " 原料=" + book.rawQuantity() + "/" + book.rawAmount()
                + " 在制=" + book.wipQuantity() + "/" + book.wipAmount()
                + " 产成品=" + book.finishedQuantity() + "/" + book.finishedAmount()
                + " 应收=" + book.receivableOutstanding()
                + " 设备净值=" + book.equipmentNet()
                + " 应付=" + book.payableOutstanding());
        System.out.println("[WorldManufacturingDemo] 利润表 收入=" + book.revenue()
                + " 成本=" + book.costOfGoodsSold()
                + " 期间费用=" + book.periodExpense()
                + " 折旧=" + book.depreciation()
                + " 累计利润=" + book.cumulativeProfit());
        System.out.println("[WorldManufacturingDemo] 平衡 资产=" + book.totalAssets()
                + " 负债=" + book.totalLiabilities()
                + " 权益=" + book.equity()
                + " 世界现金=" + book.worldCash()
                + " 平衡=" + book.balanced());
        Map<String, BigDecimal> cash = new TreeMap<>();
        snapshot.accounts().forEach((id, account) -> cash.put(id, account.cash()));
        System.out.println("[WorldManufacturingDemo] 账户现金=" + cash);
        for (ManufacturingRun.ReportEntry entry : run.reports().values()) {
            System.out.println("[WorldManufacturingDemo] report " + entry.taskId()
                    + " kind=" + entry.kind()
                    + " accepted=" + entry.accepted()
                    + " reason=" + entry.reason()
                    + " business=" + entry.businessId()
                    + " amount=" + entry.amount()
                    + " time=" + entry.timeMillis()
                    + " at=" + entry.position());
        }
        for (edu.cufe.auction.world.PendingTask task : snapshot.pending()) {
            System.out.println("[WorldManufacturingDemo] pending " + task.taskId()
                    + " kind=" + task.kind() + " due=" + task.dueTimeMillis()
                    + " phase=" + task.phase());
        }
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        modules.put("manufacturing", 1);
        return new RunManifest(RUN, SEED, RunManifest.RULES_STOCK_COMPANY_EXPERIMENT_V1, modules,
                Map.of("fixture", "world-manufacturing-demo-v1"), RunManifest.MODEL_NONE);
    }

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount(COMPANY_ACCOUNT, OWNER, money("1000.00"), "演示开局历史禀赋"),
                new OpeningAccount(SUPPLIER, "supplier", money("100.00"), "演示开局历史禀赋"),
                new OpeningAccount(CUSTOMER, "customer", money("2000.00"), "演示开局历史禀赋"),
                new OpeningAccount(OPS, "ops", money("0.00"), "演示开局历史禀赋"),
                new OpeningAccount(INVESTOR, "investor", money("500.00"), "演示开局历史禀赋"));
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party("cufe", OpeningRegistry.PartyKind.ORGANIZATION,
                        "中央财经制造公司"),
                new OpeningRegistry.Party("investor", OpeningRegistry.PartyKind.PERSON, "投资者"));
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>();
        owners.add(new OpeningRegistry.PropertyOwner(OWNER,
                OpeningRegistry.OwnerKind.ORGANIZATION, "cufe"));
        owners.add(new OpeningRegistry.PropertyOwner("supplier",
                OpeningRegistry.OwnerKind.AGGREGATE, null));
        owners.add(new OpeningRegistry.PropertyOwner("customer",
                OpeningRegistry.OwnerKind.AGGREGATE, null));
        owners.add(new OpeningRegistry.PropertyOwner("ops",
                OpeningRegistry.OwnerKind.AGGREGATE, null));
        owners.add(new OpeningRegistry.PropertyOwner("investor",
                OpeningRegistry.OwnerKind.PERSON, "investor"));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYMBOL, "cufe", 200, money("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(INVESTOR, SYMBOL, 200,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
