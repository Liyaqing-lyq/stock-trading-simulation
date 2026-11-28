package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NonSnapshotCommitSourceTest {
    @TempDir Path dir;
    static List<WorldEvent> events(String id) {return List.of(new WorldEvent("domain-"+id,"GROUP_TASK_V1","domain"),new WorldEvent("snapshot-"+id,"STATE_SNAPSHOT_V1","undecodable historical state"),new WorldEvent("delta-"+id,StateDeltaCodec.TYPE,"undecodable delta"));}
    @Test void journalAndSourcesSelectExactBoundariesAndStripOnlyStateEvents() throws Exception {
        WorldCommit parent;
        try(var journal=new WorldJournal(dir.resolve("parent.journal"),"parent")) {
            parent=journal.commit("parent-command","command","ok",events("parent"));
            assertEquals(WorldJournal.withoutSnapshot(parent),journal.nonSnapshotCommitAt(1).orElseThrow());
            var source=new JournalCommitSource(journal);
            assertEquals(journal.nonSnapshotCommitAt(1),source.nonSnapshotCommitAt(1));
            for(long p:List.of(Long.MIN_VALUE,-1L,0L,2L,Long.MAX_VALUE))assertTrue(source.nonSnapshotCommitAt(p).isEmpty(),"boundary "+p);
        }
        var list=new ListCommitSource(List.of(parent));assertEquals(WorldJournal.withoutSnapshot(parent),list.nonSnapshotCommitAt(1).orElseThrow());
        for(long p:List.of(Long.MIN_VALUE,-1L,0L,2L,Long.MAX_VALUE,4_294_967_297L))assertTrue(list.nonSnapshotCommitAt(p).isEmpty(),"list boundary "+p);
        try(var own=new WorldJournal(dir.resolve("child.journal"),"child",1)) {
            var child=own.commit("child-command","command","ok",events("child"));var branch=new BranchCommitSource(List.of(parent),own);
            assertEquals(WorldJournal.withoutSnapshot(parent),branch.nonSnapshotCommitAt(1).orElseThrow());
            assertEquals(WorldJournal.withoutSnapshot(child),branch.nonSnapshotCommitAt(2).orElseThrow());
            assertEquals("parent",branch.nonSnapshotCommitAt(1).orElseThrow().runId());assertEquals("child",branch.nonSnapshotCommitAt(2).orElseThrow().runId());
            for(long p:List.of(Long.MIN_VALUE,-1L,0L,3L,Long.MAX_VALUE))assertTrue(branch.nonSnapshotCommitAt(p).isEmpty(),"branch boundary "+p);
            assertEquals(1,branch.nonSnapshotCommitAt(1).orElseThrow().events().size());
        }
    }
    @Test void indexedLookupNeverMaterializesHistoricalFramesAndCorruptArchivesStillFail() throws Exception {
        var file=dir.resolve("bounded.journal");
        try(var journal=new WorldJournal(file,"bounded")) {
            for(int i=0;i<8;i++)journal.commit("c"+i,"body"+i,"ok",events(""+i));
            var cache=WorldJournal.class.getDeclaredField("recent");cache.setAccessible(true);((java.util.Map<?,?>)cache.get(journal)).clear();
            assertEquals(1,journal.nonSnapshotCommitAt(1).orElseThrow().events().size());
            assertTrue(((java.util.Map<?,?>)cache.get(journal)).isEmpty(),"索引查找不得触碰完整帧缓存");
        }
        var bytes=java.nio.file.Files.readAllBytes(file);bytes[20]^=1;java.nio.file.Files.write(file,bytes);
        assertThrows(java.io.IOException.class,()->new WorldJournal(file,"bounded"));
        assertThrows(java.io.IOException.class,()->WorldJournalScanner.scan(file));
    }
}
