package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 新的可选管理员能力对没有阅读模块的旧档也应返回未启用。 */
class WorldRuntimeRecallCompatibilityTest {
    @Test
    void cashOnlyProfileHasNoRecallAuditAndDoesNotChangeState() throws Exception {
        Path root = Path.of("target", "recall-compatibility-tests");
        Files.createDirectories(root);
        Path dir = Files.createTempDirectory(root, "cash-");
        var manifest = new RunManifest("old-cash-recall-query", 1,
                RunManifest.RULES_CASH_WORLD_V1, Map.of("cash", 1, "scheduler", 1), Map.of(), "none");
        try (var world = new WorldRuntime(dir.resolve("world.journal"), manifest,
                List.of(new CashLedger.OpeningAccount("A", "alice", new BigDecimal("1000.00"), "实验开局")))) {
            var before = world.snapshot();
            assertTrue(world.recallAudit("alice").isEmpty());
            assertEquals(before, world.snapshot());
        }
    }
}
