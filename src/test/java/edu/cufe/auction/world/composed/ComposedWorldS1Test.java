package edu.cufe.auction.world.composed;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整 S1 的 JUnit 薄入口：与命令行入口共用 {@link ComposedWorldSelfTest#run(Path)} 的同一份流程。
 *
 * <p>断言的是那份流程实际收集到的失败集合，因此任何 FAIL 都会让测试失败；
 * 不存在「打印 FAIL 仍 exit 0」的第二套判定。</p>
 */
class ComposedWorldS1Test {

    /**
     * 真实世界完整 S1：任免 → 存贷 → 成交 → 工程实读/预算/SIGN/开工 → 群帖/读完 →
     * north 周期采样→编制→实读批准→授权→发布→关期 → 报表与同世界重开。
     *
     * @throws Exception 世界读写失败
     */
    @Test
    void composedWorldRunsCompleteS1() throws Exception {
        Path dir = Files.createTempDirectory("composed-s1-junit").resolve("world.log");
        List<String> failures = ComposedWorldSelfTest.run(dir);
        assertTrue(failures.isEmpty(), "组合档 S1 未通过：" + failures);
    }
}
