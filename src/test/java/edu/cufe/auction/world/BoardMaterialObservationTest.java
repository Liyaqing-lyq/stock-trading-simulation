package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.BaselineIndustryConfig;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;

import static edu.cufe.auction.world.WorldRuntimeHistoricalInformationCacheTest.field;
import static org.junit.jupiter.api.Assertions.*;

class BoardMaterialObservationTest {
    @TempDir Path dir;
    private static final String CHAIR = BaselineIndustryConfig.directors(AgedIndustryConfig.COMPANY).get(0);

    private static boolean visible(WorldRuntime world, long position, long max, String person,
                                   long version, long time) throws Exception {
        var method = WorldRuntime.class.getDeclaredMethod("materialVisibleAt", long.class, long.class,
                String.class, String.class, long.class, long.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(world, position, max, person, "quote", version, time, "ask");
    }

    private static WorldRuntimeHistoricalInformationCacheTest.CountingSource countReads(WorldRuntime world) throws Exception {
        var source = new WorldRuntimeHistoricalInformationCacheTest.CountingSource((CommitSource) field("commitSource").get(world));
        field("commitSource").set(world, source);
        return source;
    }

    @Test void decoderValidReplacementCannotBecomeTrustedThroughAColdLookup() throws Exception {
        try (var original = ComposedIndustryScenario.openTenCompanyAutonomy(dir.resolve("original.journal"));
             var other = ComposedIndustryScenario.openTenCompanyAutonomy(dir.resolve("other.journal"))) {
            for (var world : List.of(original, other)) {
                world.schedulePublishDocument("publish", "publish-t", 100, "quote", "investor",
                        InformationModel.Audience.direct(world == original ? "frank" : CHAIR), "报价", "50", "报价单", 10, List.of());
                world.advanceTo(500);
            }
            long position = original.snapshot().position();
            assertEquals(position, other.snapshot().position());
            var originalSource = (CommitSource) field("commitSource").get(original);
            var alternate = countReads(other);
            for (long at : List.of(500L, 499L)) {
                if (at == 499L) WorldRuntimeHistoricalInformationCacheTest.projectionCache(original).clear();
                field("commitSource").set(original, alternate);
                var savedCursor = field("deltaReadCursor").get(original);
                for (int i = 0; i < 2; i++) {
                    alternate.reads.clear();
                    assertTrue(visible(original, position, position, CHAIR, 1, at));
                    assertTrue(alternate.reads.contains(1L), "替代目标的二次查询仍须回退；缺失的原指纹不能补签");
                    assertSame(savedCursor, field("deltaReadCursor").get(original));
                }
                field("commitSource").set(original, originalSource);
                assertFalse(visible(original, position, position, CHAIR, 1, at), "原运行的私人文件未授权给该董事");
            }
        }
    }

    @Test void exactDirectorCatalogProvesFirstUseAndOlderOrMissingVersionsStillUseTheirOwnFrame() throws Exception {
        Path file = dir.resolve("materials.journal");
        long cutoff;
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            world.schedulePublishDocument("publish", "publish-t", 100, "quote", "investor",
                    InformationModel.Audience.direct(CHAIR), "报价", "50", "报价单", 10, List.of());
            world.schedulePublishDocumentVersion("revise", "revise-t", 200, "quote", "新报价", "48", "新版", 10, List.of());
            world.advanceTo(500);
            world.beginDecisionSession("session");
            cutoff = world.snapshot().position();
            world.openDecisions("open", List.of(new DecisionRun.Request(CHAIR, "ask", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 25, 1_000_000, 2048, 1))));
            var source = countReads(world);
            var original = source.delegate.commitAt(cutoff).orElseThrow();
            var changed = new WorldCommit(original.schemaVersion(), original.position(), original.runId(),
                    original.commandId(), original.commandContent(), original.result() + "-changed", original.events());
            source.changed.put(cutoff, changed);
            assertInstanceOf(IOException.class, assertThrows(InvocationTargetException.class,
                    () -> visible(world, cutoff, cutoff, CHAIR, 2, 500)).getCause(), "同位换帧不能用原目录");
            source.changed.clear();
            source.reads.clear();
            assertTrue(visible(world, cutoff, cutoff, CHAIR, 2, 500));
            assertEquals(List.of(cutoff), source.reads, "首次采用本人已确认截止目录");
            source.reads.clear();
            assertTrue(visible(world, cutoff, cutoff, CHAIR, 1, 500));
            assertTrue(source.reads.contains(1L), "目录仅列最新版，已发布的旧版本须从历史核验");
            field("deltaReadCursor").set(world, null);
            source.reads.clear();
            assertTrue(visible(world, cutoff, cutoff, CHAIR, 1, 500));
            assertEquals(List.of(cutoff), source.reads, "已核旧版不再重建前缀");
            source.changed.put(cutoff, changed);
            assertInstanceOf(IOException.class, assertThrows(InvocationTargetException.class,
                    () -> visible(world, cutoff, cutoff, CHAIR, 1, 500)).getCause(), "同位换帧不能用原可见结论");
            source.changed.clear();
            assertFalse(visible(world, cutoff, cutoff, "frank", 2, 500));
            assertFalse(visible(world, cutoff, cutoff, CHAIR, 2, 150));
            assertFalse(visible(world, cutoff, cutoff, CHAIR, 3, 500));
            source.reads.clear();
            assertFalse(visible(world, cutoff, cutoff - 1, CHAIR, 2, 500));
            assertTrue(source.reads.isEmpty());
            source.missing.add(cutoff);
            assertFalse(visible(world, cutoff, cutoff, CHAIR, 2, 500));
            assertFalse(visible(world, cutoff, cutoff, CHAIR, 1, 499));
            source.missing.clear();
            assertTrue(visible(world, cutoff, cutoff, CHAIR, 1, 499), "缺失帧不能缓存成拒绝");
        }
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            var source = countReads(world);
            field("deltaReadCursor").set(world, null);
            assertTrue(visible(world, cutoff, cutoff, CHAIR, 1, 500));
            assertTrue(source.reads.contains(1L), "新实例须重新取得旧版本的历史根据");
        }
    }
}
