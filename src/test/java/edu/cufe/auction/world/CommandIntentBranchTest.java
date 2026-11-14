package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import java.nio.file.*;
import java.util.*;

/** 新原意档与真实分支交叉：继承待办、完成发布、独立写入、重开幂等与父档隔离。 */
class CommandIntentBranchTest {
    @org.junit.jupiter.api.io.TempDir
    Path dir;

    @org.junit.jupiter.api.Test
    void webEntryReopensWithoutRepeatingOpeningDocuments() throws Exception {
        Path journal = dir.resolve("web-intent.journal");
        long position;
        try (WorldRuntime world = WorldWebScenario.openCommandIntent(journal).runtime()) {
            org.junit.jupiter.api.Assertions.assertEquals("web-intent", world.profile().manifest().runId());
            position = world.snapshot().position();
            org.junit.jupiter.api.Assertions.assertTrue(position > 1);
            org.junit.jupiter.api.Assertions.assertFalse(world.snapshot().pending().isEmpty());
        }
        try (WorldRuntime world = WorldWebScenario.openCommandIntent(journal).runtime()) {
            org.junit.jupiter.api.Assertions.assertEquals(position, world.snapshot().position());
        }
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> WorldWebScenario.openGroups(journal));
    }
    @org.junit.jupiter.api.Test
    void newIntentSurvivesBranchAndReopen() throws Exception {
        Files.createDirectories(dir);
        Path parentFile = dir.resolve("parent.journal");
        BranchProfile profile;
        WorldRuntime.BranchReceipt receipt;
        long cut;
        try (WorldRuntime parent = WorldWebScenario.commandIntentRuntime(parentFile)) {
            parent.schedulePublishDocument("pub-parent", "task-parent", 100L, "doc:parent",
                    WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                    "Parent", "original", "synthetic", 1000L, List.of());
            cut = parent.snapshot().position();
            if (!parent.branchSupport(cut).supported()) throw new AssertionError("cut rejected");
            profile = parent.profile();
            receipt = parent.createBranch("intent-child", cut, BranchOrigin.Kind.OPERATOR_BRANCH, "intent probe");
            if (parent.snapshot().position() != cut) throw new AssertionError("parent changed");
        }
        byte[] before = Files.readAllBytes(parentFile);
        long end;
        try (WorldRuntime child = WorldRuntime.openBranch(receipt.childFile(), id -> parentFile, profile)) {
            child.advanceTo(100L);
            child.schedulePublishDocument("pub-child", "task-child", 200L, "doc:child",
                    WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                    "Child", "only child", "synthetic", 1000L, List.of());
            child.advanceTo(200L);
            if (child.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(doc -> doc.docId().equals("doc:parent") || doc.docId().equals("doc:child"))
                    .count() != 2) throw new AssertionError("publication must actually complete");
            end = child.snapshot().position();
        }
        try (WorldRuntime child = WorldRuntime.openBranch(receipt.childFile(), id -> parentFile, profile)) {
            if (child.snapshot().position() != end || child.snapshot().timeMillis() != 200L)
                throw new AssertionError("child recovery");
            child.schedulePublishDocument("pub-child", "task-child", 200L, "doc:child",
                    WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                    "Child", "only child", "synthetic", 1000L, List.of());
            if (child.snapshot().position() != end) throw new AssertionError("retry changed child");
        }
        if (!Arrays.equals(before, Files.readAllBytes(parentFile))) throw new AssertionError("parent bytes changed");
        try (WorldRuntime parent = WorldWebScenario.commandIntentRuntime(parentFile)) {
            if (parent.snapshot().position() != cut || parent.snapshot().timeMillis() != 0L)
                throw new AssertionError("parent recovery");
            if (parent.snapshot().information().orElseThrow().information().documents().stream()
                    .anyMatch(doc -> doc.docId().equals("doc:parent") || doc.docId().equals("doc:child")))
                throw new AssertionError("child publication leaked into parent");
        }
    }
}
