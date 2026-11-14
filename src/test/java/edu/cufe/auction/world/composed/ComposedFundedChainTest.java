package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.EngineeringModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工程显式资金链的永久 JUnit：完整 S1（预收→投入→真实完工→客户验收→开票→到期回款→
 * 质保释放→坏账追索）之上，直接调用 {@link FundedProbes} 的真实故障与语义篡改探针。
 *
 * <p>S1 夹具只建一次；每个探针各自从同一份干净字节复制副本，互不影响。</p>
 */
class ComposedFundedChainTest {

    private static Path dir;

    /** 真实建立一次完整资金链世界（失败即所有探针都没有可信夹具）。 */
    @BeforeAll
    static void buildFundedWorld() throws Exception {
        dir = Files.createTempDirectory("composed-funded-chain").resolve("world");
        Files.createDirectories(dir);
        List<String> failures = ComposedWorldSelfTest.run(dir.resolve("world.log"));
        assertTrue(failures.isEmpty(), "完整资金链 S1 未通过：" + failures);
    }

    /** 真实世界里的资金链结果必须精确落账，且同一笔存款/收入只出现一次。 */
    @Test
    void fundedChainPostsExactAmountsOnce() throws Exception {
        try (WorldRuntime runtime = ComposedWorldScenario.open(dir.resolve("world.log"))) {
            EngineeringModel.Claim claim = runtime.snapshot().engineering().orElseThrow()
                    .stateOf("eng-a").claims().get("A1");
            assertEquals(0, claim.memo().signum(), "追索后备查应清零");
            assertEquals(0, claim.contractLiability().signum(), "预收应已被验收冲减");
            assertEquals(0, claim.contractAsset().signum(), "合同资产应全部开票");
            assertEquals(new BigDecimal("1000.00"), claim.arCurrent(), "核销后应收余额");
            assertEquals(new BigDecimal("3000.00"), claim.revenue(), "验收确认收入只发生两次");
            assertEquals(new BigDecimal("1200.00"), claim.cogs(), "两次验收对应成本");
            assertEquals(new BigDecimal("1000.00"), claim.impairmentLoss(), "减值损失");
            assertEquals(new BigDecimal("1000.00"), claim.recoveryIncome(), "追索收回收益");
            assertEquals(new BigDecimal("2140.00"), runtime.composedFinancialPosition("ENG-A")
                    .bankDeposits(), "存款只按银行账列一次");
            assertEquals("RETENTION_COLLECTED", reason(runtime, "fund-ret-t:finish"));
            assertEquals("COLLECTED", reason(runtime, "fund-collect-t:finish"));
            assertEquals("RECOVERED", reason(runtime, "fund-rec-t:finish"));
            assertEquals("RECOVERY_EXCEEDS_MEMO",
                    runtime.taskCommitResult("fund-rec-nomemo-t:finish"));
            assertEquals("NOT_DUE", runtime.taskCommitResult("fund-ret-early-t:finish"));
        }
    }

    /** WRITE 故障：内存保持付款提交前，重开完整前态，连续重开稳定，同命令不再付款。 */
    @Test
    void writeFaultKeepsCompletePreState() throws Exception {
        FundedProbes.FaultResult result = FundedProbes.faultProbe("WRITE", dir.resolve("world.log"),
                dir.resolve("probe-write"));
        assertTrue(result.ok(), "WRITE 故障探针未通过：" + result);
        assertTrue(result.memoryIntact(), "故障后内存被改动");
        assertTrue(result.reopenPre() || result.reopenPost(), "重开既不是完整前态也不是完整后态");
        assertTrue(result.reopenStable(), "连续重开不稳定");
        assertTrue(result.sameContentRetrySameResult(), "同内容重试返回结果不同");
        assertTrue(result.sameContentRetryStateUnchanged(), "同内容重试改动了状态或 position");
        assertTrue(result.differentObservationConflict(), "新观察时点未被内容冲突拒绝");
        assertTrue(result.resumeCompletedOnce(), "重开后恢复待办没有恰好完成一次付款");
    }

    /** FORCE 故障：允许完整帧可见一次，但不得半应用；逐分量比较且同命令不再付款。 */
    @Test
    void forceFaultAllowsOnlyOneCompleteFrame() throws Exception {
        FundedProbes.FaultResult result = FundedProbes.faultProbe("FORCE", dir.resolve("world.log"),
                dir.resolve("probe-force"));
        assertTrue(result.ok(), "FORCE 故障探针未通过：" + result);
        assertTrue(result.memoryIntact(), "故障后内存被改动");
        assertTrue(result.reopenPre() || result.reopenPost(), "重开既不是完整前态也不是完整后态");
        assertTrue(result.reopenStable(), "连续重开不稳定");
        assertTrue(result.sameContentRetrySameResult(), "同内容重试返回结果不同");
        assertTrue(result.sameContentRetryStateUnchanged(), "同内容重试改动了状态或 position");
        assertTrue(result.differentObservationConflict(), "新观察时点未被内容冲突拒绝");
        assertTrue(result.resumeCompletedOnce(), "重开后恢复待办没有恰好完成一次付款");
    }

    /** 语义篡改：对照能开，攻击必须由原任务命令原文拒绝，不能是校验和/JSON 损坏。 */
    @Test
    void semanticTamperRejectedByOriginalCommand() throws Exception {
        FundedProbes.TamperResult result = FundedProbes.tamperProbe(dir.resolve("world.log"),
                dir.resolve("probe-tamper"));
        assertTrue(result.ok(), "语义篡改探针未通过：" + result);
        assertTrue(result.control().startsWith("OPENED"), "干净对照打不开：" + result.control());
        assertTrue(result.attacked().startsWith("REJECTED"), "篡改帧被接受：" + result.attacked());
        assertTrue(result.domainRejection(), "拒绝原因不是原命令/领域重演：" + result.attacked());
    }

    private static String reason(WorldRuntime runtime, String taskId) {
        return runtime.snapshot().engineering().orElseThrow().reports().get(taskId).reason();
    }
}
