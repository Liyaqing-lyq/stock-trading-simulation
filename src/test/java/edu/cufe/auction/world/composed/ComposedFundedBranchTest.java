package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static edu.cufe.auction.world.composed.ComposedWorldScenario.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分支 + 显式资金工程动作的交叉验收：完整 S1（真实合同/经理/预算/非零银行存量）之上建真实分支，
 * 子档做真实 INVEST_WORK 付款、父档做不同的真实 ADVANCE 收款；两侧完整 30 组件状态、现金/银行/
 * 工程与分支元数据互不串档，各自关闭后由独立 JVM 重开并继续合法业务。
 *
 * <p>这里**不**伪造 WorldState、不改日志、不重读磁盘冒充内存：所有资金与工程事实都由公开入口
 * 真实发生，完整状态摘要由 {@link ComposedBranchBusinessProbe#digest(WorldRuntime)} 用生产
 * canonical 序列化整份 {@code WorldState} 并与当前已确认检查点核对。</p>
 */
class ComposedFundedBranchTest {

    private static final String BANK = ComposedWorldScenario.BANK;
    private static final String POLICY = "COMPANY_CHOICE_V1";
    private static final String ENG_A = "eng-a";

    /** 切点真实存量与派生金额，供后面逐项核对。 */
    private static BigDecimal depositAtCut;
    private static BigDecimal reserveAtCut;
    private static BigDecimal customerAtCut;
    private static BigDecimal opsAtCut;
    private static BigDecimal payableAtCut;
    private static int workOrdersAtCut;
    private static BigDecimal childDepositAfter;
    private static BigDecimal childOpsAfter;
    private static BigDecimal childReserveAfter;
    private static BigDecimal childPayableAfter;
    private static BigDecimal orderTotalAfter;
    private static BigDecimal orderLaborAfter;

    @Test
    void fundedActionInChildBranchKeepsParentLedgerIsolated() throws Exception {
        Path dir = Files.createTempDirectory("composed-funded-branch");
        Path world = dir.resolve("world.log");
        List<String> failures = ComposedWorldSelfTest.run(world);
        assertTrue(failures.isEmpty(), "完整资金链 S1 未通过：" + failures);

        Path childFile;
        Path sealedPrefix;
        String childShaAtCut;
        String parentDigest;
        String childDigest;
        BigDecimal depositAfterAdvance;
        BigDecimal customerAfterAdvance;

        try (WorldRuntime parent = ComposedWorldScenario.open(world)) {
            BranchProfile profile = parent.profile();
            long cut = parent.snapshot().position();
            BankState bank = bankOf(parent);
            depositAtCut = bank.depositOf(BANK, ENG_A);
            reserveAtCut = bank.gl(BANK, "RESERVE");
            customerAtCut = cashOf(parent, "ENG-A-CUS");
            opsAtCut = cashOf(parent, "ENG-A-OPS");
            payableAtCut = payableOf(parent);
            workOrdersAtCut = engineeringOf(parent).stateOf(ENG_A).workOrders().size();
            assertTrue(depositAtCut.signum() > 0, "切点必须有非零银行存量，实际 " + depositAtCut);

            WorldRuntime.BranchReceipt receipt = parent.createBranch("child-funded", cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "资金交叉分支");
            childFile = receipt.childFile();
            sealedPrefix = receipt.sealedPrefix();
            assertTrue(Files.isRegularFile(childFile), "子档必须已发布");
            childShaAtCut = ComposedBranchReopenProbe.sha256(childFile);

            // 父档做与子档不同的真实资金动作：客户预收 10.00 进公司存款桶
            long due = parent.snapshot().timeMillis() + 3;
            parent.scheduleFundedEngineeringAction("fb-adv", "fb-adv-t", due, ENG_A, "carol",
                    "ADVANCE", "A1", "", 0, 0, money("10.00"), 0L, POLICY,
                    "CASH|ENG-A-CUS", "DEPOSIT|" + BANK + "|" + ENG_A, 1L);
            parent.advanceTo(due);
            assertEquals("ACCEPTED", parent.taskCommitResult("fb-adv-t:finish"),
                    "父档真实预收必须被接受，实际 " + parent.taskCommitResult("fb-adv-t:finish"));
            BankState parentBank = bankOf(parent);
            depositAfterAdvance = depositAtCut.add(money("10.00"));
            customerAfterAdvance = customerAtCut.subtract(money("10.00"));
            assertEquals(0, parentBank.depositOf(BANK, ENG_A).compareTo(depositAfterAdvance),
                    "父档存款必须按真实预收增加：实际 " + parentBank.depositOf(BANK, ENG_A));
            assertEquals(0, cashOf(parent, "ENG-A-CUS").compareTo(customerAfterAdvance),
                    "父档客户现金必须按真实预收减少：实际 " + cashOf(parent, "ENG-A-CUS"));
            assertEquals(0, cashOf(parent, "ENG-A-OPS").compareTo(opsAtCut),
                    "父档未做投入，运营账户不得变化");
            assertEquals(0, payableOf(parent).compareTo(payableAtCut),
                    "父档预收只影响合同负债，不得改动供应商应付：实际 " + payableOf(parent));
            assertEquals(0, engineeringOf(parent).stateOf(ENG_A).workOrders().size()
                            - workOrdersAtCut,
                    "父档未做投入，不得新增工单");
            assertEquals(childShaAtCut, ComposedBranchReopenProbe.sha256(childFile),
                    "父档动作不得改写子档任何字节");
            parentDigest = ComposedBranchBusinessProbe.digest(parent);
        }
        String parentShaAfterAdvance = ComposedBranchReopenProbe.sha256(world);

        try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> sealedPrefix,
                parentProfile(world))) {
            assertEquals(depositAtCut, bankOf(child).depositOf(BANK, ENG_A),
                    "子档必须继承切点真实存款");
            assertEquals(customerAtCut, cashOf(child, "ENG-A-CUS"),
                    "子档必须继承切点客户现金，不得带上父档预收");

            BigDecimal total = investedTotal(child, 10);
            long due = child.snapshot().timeMillis() + 3;
            child.scheduleFundedEngineeringAction("fb-inv", "fb-inv-t", due, ENG_A, "carol",
                    "INVEST_WORK", "A1", "W-A1H", 0, 10, BigDecimal.ZERO, 0L, POLICY,
                    "DEPOSIT|" + BANK + "|" + ENG_A, "CASH|ENG-A-OPS", 1L);
            child.advanceTo(due);
            assertEquals("ACCEPTED", child.taskCommitResult("fb-inv-t:finish"),
                    "子档真实投入必须被接受，实际 " + child.taskCommitResult("fb-inv-t:finish"));

            BankState childBank = bankOf(child);
            BigDecimal childDeposit = childBank.depositOf(BANK, ENG_A);
            BigDecimal childOps = cashOf(child, "ENG-A-OPS");
            BigDecimal childReserve = childBank.gl(BANK, "RESERVE");
            BigDecimal moved = depositAtCut.subtract(childDeposit);
            System.out.println("[FUNDED-BRANCH] cut deposit=" + depositAtCut + " reserve="
                    + reserveAtCut + " ops=" + opsAtCut + " payable=" + payableAtCut
                    + " | child deposit=" + childDeposit + " reserve=" + childReserve
                    + " ops=" + childOps + " payable=" + payableOf(child));
            // 业务口径（EngineeringModel.investWorkInternal 1102-1152）：10 单位累计成本 400.00，
            // 其中 laborBp=6000 只把 240.00 作为人工现金实付；cost−labor=160.00 进供应商应付。
            var order = engineeringOf(child).stateOf(ENG_A).workOrders().get("W-A1H");
            assertNotNull(order, "真实投入必须冻结工单 W-A1H");
            assertEquals(0, order.totalCost().compareTo(money("400.00")),
                    "10 单位冻结的累计成本必须是 400.00：实际 " + order.totalCost());
            assertEquals(0, order.laborCost().compareTo(money("240.00")),
                    "laborBp=6000 的人工现金部分是 240.00：实际 " + order.laborCost());
            assertEquals(0, moved.compareTo(order.laborCost()),
                    "存款只按人工部分实付：moved=" + moved + " labor=" + order.laborCost());
            assertEquals(0, childOps.subtract(opsAtCut).compareTo(order.laborCost()),
                    "运营账户只收到人工部分：+" + childOps.subtract(opsAtCut));
            assertEquals(0, reserveAtCut.subtract(childReserve).compareTo(order.laborCost()),
                    "准备金只释放人工部分：-" + reserveAtCut.subtract(childReserve));
            BigDecimal payableDelta = payableOf(child).subtract(payableAtCut);
            assertEquals(0, payableDelta.compareTo(order.totalCost().subtract(order.laborCost())),
                    "未付分包部分必须进供应商应付：delta=" + payableDelta);
            assertEquals(0, payableDelta.compareTo(money("160.00")),
                    "本次新增应付必须是 160.00：实际 " + payableDelta);
            assertEquals(workOrdersAtCut + 1,
                    engineeringOf(child).stateOf(ENG_A).workOrders().size(),
                    "本批只应新增这一张真实工单");
            assertEquals(0, cashOf(child, "ENG-A-CUS").compareTo(customerAtCut),
                    "子档未做预收，客户现金不得变化");
            assertTrue(child.snapshot().engineering().orElseThrow().reports()
                            .containsKey("fb-inv-t:finish"),
                    "子档资金动作必须留下工程回报");
            childDepositAfter = childDeposit;
            childOpsAfter = childOps;
            childReserveAfter = childReserve;
            childPayableAfter = payableOf(child);
            orderTotalAfter = order.totalCost();
            orderLaborAfter = order.laborCost();
            childDigest = ComposedBranchBusinessProbe.digest(child);
        }
        assertNotEquals(childShaAtCut, ComposedBranchReopenProbe.sha256(childFile),
                "子档自己的动作必须改写子档日志");
        assertEquals(parentShaAfterAdvance, ComposedBranchReopenProbe.sha256(world),
                "子档动作不得改写父档任何字节");

        // 独立 JVM：两个真实进程只读重开，各自 30 组件摘要必须等于本进程值并可继续合法业务
        String output = runProbe(childFile, world, sealedPrefix);
        String childLine = lineOf(output, "CHILD");
        String parentLine = lineOf(output, "PARENT");
        assertEquals(childDigest, valueOf(childLine, "STATE_SHA"),
                "独立 JVM 子档完整状态必须相同：" + output);
        assertEquals(parentDigest, valueOf(parentLine, "STATE_SHA"),
                "独立 JVM 父档完整状态必须相同：" + output);
        assertTrue(childLine.contains("DEPOSIT=" + childDepositAfter.toPlainString()),
                "独立 JVM 子档存款不对：" + output);
        assertTrue(childLine.contains("ENG_A_OPS=" + childOpsAfter.toPlainString()),
                "独立 JVM 子档运营现金不对：" + output);
        assertTrue(childLine.contains("RESERVE=" + childReserveAfter.toPlainString()),
                "独立 JVM 子档准备金不对：" + output);
        assertTrue(childLine.contains("PAYABLE=" + childPayableAfter.toPlainString()),
                "独立 JVM 子档供应商应付不对：" + output);
        assertTrue(childLine.contains("ORDER_TOTAL=" + orderTotalAfter.toPlainString())
                        && childLine.contains("ORDER_LABOR=" + orderLaborAfter.toPlainString()),
                "独立 JVM 子档工单冻结成本不对：" + output);
        assertTrue(parentLine.contains("PAYABLE=" + payableAtCut.toPlainString()),
                "父档应付不得被子档投入串动：" + output);
        assertTrue(childLine.contains("FUND_REPORT=WORK_STARTED"),
                "子档资金回报必须是真实投入口径：" + output);
        assertTrue(parentLine.contains("FUND_REPORT=ADVANCE_RECEIVED"),
                "父档资金回报必须是真实预收口径：" + output);
        assertTrue(parentLine.contains("DEPOSIT=" + depositAfterAdvance.toPlainString()),
                "独立 JVM 父档存款不对：" + output);
        assertTrue(parentLine.contains("ENG_A_CUS=" + customerAfterAdvance.toPlainString()),
                "独立 JVM 父档客户现金不对：" + output);
        assertTrue(childLine.contains("CONTINUE=ACCEPTED"),
                "独立 JVM 子档重开后必须还能继续合法资金业务：" + output);
        assertTrue(parentLine.contains("CONTINUE=ACCEPTED"),
                "独立 JVM 父档重开后必须还能继续合法资金业务：" + output);
    }

    /** 金额两位（成本价四位口径），与生产 CashLedger 对金额的处理一致。 */
    private static BigDecimal money2(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 生产口径的投入总额：合同单位成本 × 单位数（金额两位，成本价四位）。 */
    private static BigDecimal investedTotal(WorldRuntime runtime, long units) throws Exception {
        var company = runtime.snapshot().engineering().orElseThrow().stateOf(ENG_A);
        var contract = company.contracts().get("A1");
        return money2(contract.unitCost().multiply(BigDecimal.valueOf(units)));
    }

    /** 探针行里的投入总额：合同单位成本（探针如实打印）× 本次投入 10 个单位。 */
    private static BigDecimal investedTotalFromLine(String line) {
        for (String token : line.split(" ")) {
            if (token.startsWith("UNIT_COST=")) {
                return money2(money(token.substring("UNIT_COST=".length()))
                        .multiply(BigDecimal.valueOf(10)));
            }
        }
        throw new AssertionError("探针未打印 UNIT_COST：" + line);
    }

    private static BankState bankOf(WorldRuntime runtime) throws Exception {
        return runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
    }

    private static BigDecimal cashOf(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    /** 工程子集（缺失即夹具失败）。 */
    private static edu.cufe.auction.world.EngineeringRun engineeringOf(WorldRuntime runtime) {
        return runtime.snapshot().engineering().orElseThrow();
    }

    /** 该公司供应商应付。 */
    private static BigDecimal payableOf(WorldRuntime runtime) {
        return engineeringOf(runtime).stateOf(ENG_A).payable();
    }

    private static BranchProfile parentProfile(Path world) throws Exception {
        try (WorldRuntime parent = ComposedWorldScenario.open(world)) {
            return parent.profile();
        }
    }

    private static String valueOf(String line, String key) {
        for (String token : line.split(" ")) {
            if (token.startsWith(key + "=")) {
                return token.substring(key.length() + 1);
            }
        }
        return "";
    }

    private static String lineOf(String output, String prefix) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix + " ")) {
                return line;
            }
        }
        throw new AssertionError("探针输出缺少 " + prefix + " 行：" + output);
    }

    /** 独立 JVM 探针：输出重定向到临时文件再 waitFor，超时只终止自有子进程。 */
    private static String runProbe(Path childFile, Path world, Path sealedPrefix) throws Exception {
        Path log = Files.createTempFile("composed-funded-branch-probe-", ".log");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                "edu.cufe.auction.world.composed.ComposedFundedBranchProbe",
                childFile.toString(), world.toString(), sealedPrefix.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(finished, "独立 JVM 探针超时（已终止自有子进程）：" + output);
        assertEquals(0, process.exitValue(), "独立 JVM 探针失败：" + output);
        return output.trim();
    }
}
