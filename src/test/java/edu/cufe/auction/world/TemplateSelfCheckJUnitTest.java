package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 把作者 84 项自检纳入 JUnit 的薄入口：只调用一次 main，并验证 static 计数每次重置。
 */
class TemplateSelfCheckJUnitTest {

    /** 同一 JVM 两次调用都应回到 84，而不是累加成 168。 */
    @Test
    void selfCheckRunsAndResetsCount() {
        TemplateSelfCheck.main(new String[0]);
        assertEquals(84, TemplateSelfCheck.checks());
        TemplateSelfCheck.main(new String[0]);
        assertEquals(84, TemplateSelfCheck.checks());
    }
}
