package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankState;

import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * 分支 + 显式资金工程的独立 JVM 探针：父/子各自只读重开，打印现金/银行/工程/完整状态摘要，
 * 然后各做一次**新的合法资金动作**，证明重开后仍能继续真实业务。
 *
 * <p>只使用公开入口与生产 canonical 摘要（{@link ComposedBranchBusinessProbe#digest(WorldRuntime)}
 * 反射读取整份 {@code WorldState} 并与当前 checkpoint 核对），不伪造状态、不改日志。</p>
 */
public final class ComposedFundedBranchProbe {

    private static final String BANK = ComposedWorldScenario.BANK;
    private static final String ENG_A = "eng-a";
    private static final String POLICY = "COMPANY_CHOICE_V1";

    private ComposedFundedBranchProbe() {
    }

    /**
     * @param args args[0]=子档，args[1]=父档，args[2]=封存父前缀
     * @throws Exception 打开或继续失败
     */
    public static void main(String[] args) throws Exception {
        Path childFile = Path.of(args[0]);
        Path parentFile = Path.of(args[1]);
        Path sealedPrefix = Path.of(args[2]);
        BranchProfile profile = parentProfile(parentFile);
        try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> sealedPrefix,
                profile)) {
            System.out.println(describe("CHILD", child, "fb-inv-t:finish"));
        }
        try (WorldRuntime parent = ComposedWorldScenario.open(parentFile)) {
            System.out.println(describe("PARENT", parent, "fb-adv-t:finish"));
        }
    }

    private static String describe(String label, WorldRuntime runtime, String fundedTask)
            throws Exception {
        BankState bank = runtime.bankOf().orElseThrow()
                .decode(runtime.snapshot().accounts()).state();
        var company = runtime.snapshot().engineering().orElseThrow().stateOf(ENG_A);
        BigDecimal unitCost = company.contracts().get("A1").unitCost();
        String report = runtime.snapshot().engineering().orElseThrow().reports()
                .containsKey(fundedTask)
                ? runtime.snapshot().engineering().orElseThrow().reports().get(fundedTask).reason()
                : "MISSING";
        String digest = ComposedBranchBusinessProbe.digest(runtime);
        StringBuilder out = new StringBuilder(label);
        out.append(" POSITION=").append(runtime.snapshot().position());
        out.append(" DEPOSIT=").append(bank.depositOf(BANK, ENG_A).toPlainString());
        out.append(" RESERVE=").append(bank.gl(BANK, "RESERVE").toPlainString());
        out.append(" ENG_A_CUS=").append(cash(runtime, "ENG-A-CUS"));
        out.append(" ENG_A_OPS=").append(cash(runtime, "ENG-A-OPS"));
        out.append(" UNIT_COST=").append(unitCost.toPlainString());
        out.append(" PAYABLE=").append(company.payable().toPlainString());
        var order = company.workOrders().get("W-A1H");
        out.append(" ORDER_TOTAL=").append(order == null ? "MISSING"
                : order.totalCost().toPlainString());
        out.append(" ORDER_LABOR=").append(order == null ? "MISSING"
                : order.laborCost().toPlainString());
        out.append(" FUND_REPORT=").append(report);
        out.append(" STATE_SHA=").append(digest);

        // 继续合法业务：同一个真实口子再做一次小额预收（新命令、新待办，不是重放旧命令）
        long due = runtime.snapshot().timeMillis() + 3;
        runtime.scheduleFundedEngineeringAction("probe-cont", "probe-cont-t", due, ENG_A,
                "carol", "ADVANCE", "A1", "", 0, 0, new BigDecimal("1.00"), 0L, POLICY,
                "CASH|ENG-A-CUS", "DEPOSIT|" + BANK + "|" + ENG_A, 1L);
        runtime.advanceTo(due);
        out.append(" CONTINUE=").append(runtime.taskCommitResult("probe-cont-t:finish"));
        out.append(" DEPOSIT_AFTER_CONT=")
                .append(runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts())
                        .state().depositOf(BANK, ENG_A).toPlainString());
        return out.toString();
    }

    private static String cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash().toPlainString();
    }

    private static BranchProfile parentProfile(Path parentFile) throws Exception {
        try (WorldRuntime parent = ComposedWorldScenario.open(parentFile)) {
            return parent.profile();
        }
    }
}
