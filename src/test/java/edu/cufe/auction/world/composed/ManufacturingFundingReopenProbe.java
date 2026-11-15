package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.ManufacturingFunding;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * 三真实 JVM 制造资金续写探针：step0 建链、step1 重开续写并做一条真实分支业务、step2 只读重开比对。
 *
 * <p>每个 step 都是独立进程；同一日志只由 arg 指定，不共享内存。</p>
 */
public final class ManufacturingFundingReopenProbe {

    private static final String POLICY = ManufacturingFunding.POLICY_FROZEN_MAPPING_V1;
    private static final long DAY = 86_400_000L;

    private ManufacturingFundingReopenProbe() {
    }

    /**
     * 探针入口。
     *
     * @param args [世界日志, step, 期望摘要文件, 输出摘要文件]
     * @throws Exception 任一断言失败或读写失败
     */
    public static void main(String[] args) throws Exception {
        Path world = Path.of(args[0]);
        int step = Integer.parseInt(args[1]);
        Path digestIn = Path.of(args[2]);
        Path digestOut = Path.of(args[3]);
        switch (step) {
            case 0 -> {
                try (WorldRuntime runtime = ManufacturingFundingWorld.open(world)) {
                    ManufacturingFundingChainTest.budgetMeeting(runtime,
                            WorldWebScenario.COMPANY, "x0", "10000.00", "10000.00");
                    long orderDue = 10 * DAY;
                    runtime.scheduleFundedManufacturing("x-po", "x-po-t", orderDue,
                            ManufacturingFundingWorld.MANAGER, "PURCHASE_ORDER", "PO-X", "", 10L,
                            null, 0L, -1L, POLICY, "CASH|CO", "CASH|SUP", 1L);
                    runtime.advanceTo(orderDue);
                    runtime.advanceTo(orderDue + DAY);
                    long prodDue = orderDue + DAY + 100;
                    runtime.scheduleFundedManufacturing("x-prod", "x-prod-t", prodDue,
                            ManufacturingFundingWorld.MANAGER, "PRODUCTION", "B-X", "", 10L, null,
                            0L, -1L, POLICY, "CASH|CO", "CASH|OPS", 1L);
                    runtime.advanceTo(prodDue);
                    runtime.advanceTo(prodDue + DAY);
                    long signDue = prodDue + DAY + 100;
                    long promised = 20 * DAY;
                    runtime.scheduleFundedManufacturing("x-sign", "x-sign-t", signDue,
                            ManufacturingFundingWorld.MANAGER, "SIGN", "S-X", "", 10L,
                            new BigDecimal("130.00"), promised, -1L, POLICY, "", "", 1L);
                    runtime.advanceTo(signDue);
                    runtime.scheduleFundedManufacturing("x-dlv", "x-dlv-t", promised,
                            ManufacturingFundingWorld.MANAGER, "DELIVERY", "S-X", "R-X", 10L, null,
                            0L, -1L, POLICY, "CASH|CUS", "CASH|CO", 1L);
                    runtime.advanceTo(promised);
                    runtime.advanceTo(ManufacturingModel.PERIOD_MILLIS);
                    require("PERIOD_CLOSED".equals(ManufacturingFundingChainTest.reason(runtime,
                            "mfg-close:0")), "第 0 期关期失败");
                    require(cash(runtime, "CO").compareTo(new BigDecimal("0.00")) == 0,
                            "关期后公司现金应为 0，实际 " + cash(runtime, "CO"));
                    require(runtime.manufacturingStatement().orElseThrow().payableOutstanding()
                            .compareTo(new BigDecimal("50.00")) == 0, "应付应为 50");
                }
                write(digestOut, "step0:" + sha256(world));
            }
            case 1 -> {
                String expected = Files.readString(digestIn, StandardCharsets.UTF_8).trim();
                BranchProfile profile;
                Path childFile;
                Path sealed;
                long billAt;
                try (WorldRuntime runtime = ManufacturingFundingWorld.open(world)) {
                    require(expected.startsWith("step0:"), "前置摘要不是 step0：" + expected);
                    require(cash(runtime, "CO").compareTo(new BigDecimal("0.00")) == 0,
                            "重开后公司现金应为 0，实际 " + cash(runtime, "CO"));
                    require(runtime.manufacturingStatement().orElseThrow().payableOutstanding()
                            .compareTo(new BigDecimal("50.00")) == 0, "重开后应付应为 50");
                    profile = runtime.profile();
                    long collectAt = 35 * DAY;
                    runtime.scheduleFundedManufacturing("x-collect", "x-collect-t", collectAt,
                            ManufacturingFundingWorld.MANAGER, "COLLECT", "R-X", "", 0L, null, 0L,
                            -1L, POLICY, "CASH|CUS", "CASH|CO", 1L);
                    runtime.advanceTo(collectAt);
                    require("PAYMENT_RECEIVED".equals(ManufacturingFundingChainTest.reason(runtime,
                            "x-collect-t:finish")), "重开续写回款失败");
                    long cut = runtime.snapshot().position();
                    WorldRuntime.BranchReceipt receipt = runtime.createBranch("child-mfg-funding", cut,
                            BranchOrigin.Kind.OPERATOR_BRANCH, "制造资金分支");
                    childFile = receipt.childFile();
                    sealed = receipt.sealedPrefix();
                    billAt = collectAt + 100;
                    runtime.scheduleFundedManufacturing("x-bill", "x-bill-t", billAt,
                            ManufacturingFundingWorld.MANAGER, "PAY_BILL", "", "", 0L, null, 0L, 0L,
                            POLICY, "CASH|CO", "CASH|OPS", 1L);
                    runtime.advanceTo(billAt);
                    require("EXPENSE_BILL_PAID".equals(ManufacturingFundingChainTest.reason(runtime,
                            "x-bill-t:finish")), "父档欠款支付失败");
                    require(runtime.manufacturingStatement().orElseThrow().payableOutstanding()
                            .compareTo(new BigDecimal("0.00")) == 0, "父档应付应结清");
                }
                try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> sealed, profile)) {
                    // 子分支继承切点真实应付，独立支付一次，不复制父档后续业务。
                    require(child.manufacturingStatement().orElseThrow().payableOutstanding()
                            .compareTo(new BigDecimal("50.00")) == 0, "子档必须继承切点应付 50");
                    long due = child.snapshot().timeMillis() + 100;
                    child.scheduleFundedManufacturing("c-bill", "c-bill-t", due,
                            ManufacturingFundingWorld.MANAGER, "PAY_BILL", "", "", 0L, null, 0L, 0L,
                            POLICY, "CASH|CO", "CASH|OPS", 1L);
                    child.advanceTo(due);
                    require("EXPENSE_BILL_PAID".equals(ManufacturingFundingChainTest.reason(child,
                            "c-bill-t:finish")), "子档欠款支付失败");
                    require(child.manufacturingStatement().orElseThrow().payableOutstanding()
                            .compareTo(new BigDecimal("0.00")) == 0, "子档应付应结清");
                    require(cash(child, "OPS").compareTo(new BigDecimal("550.00")) == 0,
                            "子档人力账户应为 550，实际 " + cash(child, "OPS"));
                }
                write(digestOut, "step1:" + sha256(world));
            }
            case 2 -> {
                String expected = Files.readString(digestIn, StandardCharsets.UTF_8).trim();
                try (WorldRuntime runtime = ManufacturingFundingWorld.open(world)) {
                    require(expected.startsWith("step1:"), "前置摘要不是 step1：" + expected);
                    require(cash(runtime, "CO").compareTo(new BigDecimal("1250.00")) == 0,
                            "只读重开后公司现金应为 1250，实际 " + cash(runtime, "CO"));
                    require(cash(runtime, "OPS").compareTo(new BigDecimal("550.00")) == 0,
                            "只读重开后人力账户应为 550，实际 " + cash(runtime, "OPS"));
                    require(runtime.manufacturingStatement().orElseThrow().payableOutstanding()
                            .compareTo(new BigDecimal("0.00")) == 0, "只读重开后应付应为 0");
                }
                write(digestOut, "step2:" + sha256(world));
            }
            default -> throw new IllegalArgumentException("未知 step：" + step);
        }
        System.out.println("MFG_FUND_REOPEN_OK step=" + step);
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    private static void write(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 供测试比对日志字节。 */
    static String sha256(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes));
    }
}
