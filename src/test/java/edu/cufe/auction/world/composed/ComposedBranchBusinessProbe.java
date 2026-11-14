package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankState;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 独立 JVM 重开探针（真实非零银行存量）：只读打开子档与父档，打印银行三态、现金、章程与分支元数据。
 *
 * <p>供 {@code ComposedBranchProfileTest} 用 ProcessBuilder 调用：父档写者已关闭、父子各自做过
 * 不同的真实资金动作之后，另一个进程里仍必须读到各自完整的 BankRun/现金/额外公司治理/分支元数据，
 * 且两者的数值互不串档。</p>
 */
public final class ComposedBranchBusinessProbe {

    private static final String BANK = ComposedWorldScenario.BANK;
    private static final String CUFE = "cufe";

    private ComposedBranchBusinessProbe() {
    }

    /**
     * 规范化完整内存权威状态摘要；测试专用反射只读，不遗漏任何状态字段。
     * 同时核对当前已确认检查点，防止只比较未加载的磁盘副本。
     *
     * @param runtime 世界运行
     * @return 完整状态摘要
     * @throws Exception 读取、序列化或已确认状态不一致
     */
    public static String digest(WorldRuntime runtime) throws Exception {
        var field = WorldRuntime.class.getDeclaredField("state");
        field.setAccessible(true);
        var method = WorldRuntime.class.getDeclaredMethod("stateCanonical");
        method.setAccessible(true);
        ObjectMapper mapper = (ObjectMapper) method.invoke(runtime);
        String json = mapper.writeValueAsString(field.get(runtime));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!digest.equals(runtime.checkpoint(runtime.snapshot().position()).stateDigest())) {
            throw new AssertionError("内存完整状态与当前已确认检查点不一致");
        }
        return digest;
    }

    /**
     * @param args args[0]=子档路径，args[1]=父档日志路径，args[2]=封存前缀路径
     * @throws Exception 打开失败
     */
    public static void main(String[] args) throws Exception {
        Path childFile = Path.of(args[0]);
        Path parentFile = Path.of(args[1]);
        Path sealedPrefix = Path.of(args[2]);
        BranchProfile profile = parentProfile(parentFile);
        try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> sealedPrefix,
                profile)) {
            System.out.println(describe("CHILD", child, profile));
        }
        try (WorldRuntime parent = ComposedWorldScenario.open(parentFile)) {
            System.out.println(describe("PARENT", parent, parent.profile()));
        }
    }

    /** 一行可解析的指纹：位置、银行三态、CO 现金、章程、分支元数据。 */
    private static String describe(String label, WorldRuntime runtime, BranchProfile profile)
            throws Exception {
        BankState bank = runtime.bankOf().orElseThrow()
                .decode(runtime.snapshot().accounts()).state();
        StringBuilder out = new StringBuilder(label);
        out.append(" POSITION=").append(runtime.snapshot().position());
        out.append(" DEPOSIT=").append(bank.depositOf(BANK, CUFE).toPlainString());
        out.append(" LOAN=").append(bank.loan(BANK, CUFE).contract().toPlainString());
        out.append(" RESERVE=").append(bank.gl(BANK, "RESERVE").toPlainString());
        out.append(" CASH=").append(runtime.snapshot().accounts().get("CO").cash()
                .toPlainString());
        out.append(" INV=").append(runtime.snapshot().accounts().get("INV").cash()
                .toPlainString());
        out.append(" BANK=").append(runtime.bankOf().isPresent());
        out.append(" BANK_RUN=").append(runtime.bankOf().orElseThrow().runId());
        out.append(" LOANS=").append(runtime.bankBook().loans().size());
        out.append(" GOVERNANCE=").append(runtime.companyGovernance().isPresent());
        out.append(" CUFE_CHARTER=").append(runtime.snapshot().governance().orElseThrow()
                .board().charter().seats().size());
        out.append(" PROFILE=").append(profile.version());
        out.append(" PROFILE_SHA=").append(profile.digest());
        out.append(" STATE_SHA=").append(digest(runtime));
        if (runtime.branchOrigin().isPresent()) {
            out.append(" CHILD_RUN=").append(runtime.branchOrigin().orElseThrow().childRunId());
            out.append(" PARENT_RUN=").append(runtime.branchOrigin().orElseThrow().parentRunId());
            out.append(" PARENT_POSITION=")
                    .append(runtime.branchOrigin().orElseThrow().parentPosition());
        }
        return out.toString();
    }

    /** 用与父档同一份可信画像打开父档并取 profile（只读取画像，不做任何写入）。 */
    private static BranchProfile parentProfile(Path parentFile) throws Exception {
        try (WorldRuntime parent = ComposedWorldScenario.open(parentFile)) {
            return parent.profile();
        }
    }
}
