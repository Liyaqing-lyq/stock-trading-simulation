package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorldJournalPagingTest {
    @TempDir Path dir;

    @Test void compressedFramesKeepOldPrefixAndScannerRecoveryAndChecksumRules() throws Exception {
        Path file=dir.resolve("mixed.journal");
        var event=new WorldEvent("e1","STATE_SNAPSHOT_V1","data".repeat(10_000));
        try(var journal=new WorldJournal(file,"mixed")) { journal.commit("old","old","ok",List.of(event)); }
        byte[] old=java.nio.file.Files.readAllBytes(file);
        try(var journal=new WorldJournal(file,"mixed",0,true)) {
            journal.commit("new","new","ok",List.of(new WorldEvent("e2","STATE_SNAPSHOT_V1",event.payload())));
            assertEquals(event.payload(),journal.commitAt(2).orElseThrow().events().get(0).payload());
        }
        byte[] all=java.nio.file.Files.readAllBytes(file);
        assertArrayEquals(old,java.util.Arrays.copyOf(all,old.length));
        assertTrue(all.length-old.length<old.length/10,"新帧实际压缩");
        assertEquals(2,WorldJournalScanner.scan(file).commits().size());
        try(var journal=new WorldJournal(file,"mixed")) {
            assertEquals(event.payload(),journal.commitAt(2).orElseThrow().events().get(0).payload());
        }
        byte[] broken=all.clone();broken[old.length+10]^=1;
        Path corrupt=dir.resolve("corrupt.journal");java.nio.file.Files.write(corrupt,broken);
        assertThrows(java.io.IOException.class,()->WorldJournalScanner.scan(corrupt));
        assertThrows(java.io.IOException.class,()->new WorldJournal(corrupt,"mixed"));
        Path half=dir.resolve("half.journal");java.nio.file.Files.write(half,java.util.Arrays.copyOf(all,all.length-2));
        assertTrue(WorldJournalScanner.scan(half).tailIncomplete());
        try(var journal=new WorldJournal(half,"mixed")) {assertEquals(1,journal.commitView().size());}
        assertArrayEquals(old,java.nio.file.Files.readAllBytes(half));
    }

    @Test void decompressionCannotExceedExistingFrameLimit() throws Exception {
        byte[] zip=JournalFrameCodec.encode(new byte[16*1024*1024+1]);
        assertThrows(java.io.IOException.class,()->JournalFrameCodec.decode(zip,zip.length|Integer.MIN_VALUE));
    }

    @Test void largeConfirmedHistoryFitsSmallHeapAndRetainsReplayAndDeduplication() throws Exception {
        Path log=dir.resolve("probe.log");
        String javaBin=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(javaBin,"-Xmx96m","-cp",System.getProperty("java.class.path"),
                Probe.class.getName(),dir.resolve("history.journal").toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(90,TimeUnit.SECONDS),"小堆历史验证超时");
            assertEquals(0,child.exitValue(),java.nio.file.Files.readString(log));
        } finally { if(child.isAlive())child.destroyForcibly(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path file=Path.of(args[0]);
            try(var journal=new WorldJournal(file,"large-history")) {
                for(int i=0;i<160;i++) journal.commit("c"+i,"content"+i,"ok",
                        List.of(new WorldEvent("e"+i,"STATE_SNAPSHOT_V1","x".repeat(700_000)+i)));
                var fixed=journal.commitView();
                if(fixed.size()!=160 || fixed.get(0).events().get(0).payload().length()!=700001)throw new AssertionError("历史丢失");
                var duplicate=journal.commit("c0","content0","ignored",List.of());
                if(duplicate.position()!=1 || !duplicate.result().equals("ok"))throw new AssertionError("旧命令未去重");
                journal.commit("last","last","ok",List.of());
                if(fixed.size()!=160 || journal.commitView().size()!=161)throw new AssertionError("视图长度不冻结");
                if(!journal.nonSnapshotCommits().get(0).events().isEmpty())throw new AssertionError("索引滞留快照");
            }
            try(var journal=new WorldJournal(file,"large-history")) {
                int seen=0;
                for(var batch:journal.commitView()) {
                    if(batch.position()!=++seen)throw new AssertionError("恢复位置断裂");
                    if(seen<=160 && !batch.events().get(0).payload().endsWith(Integer.toString(seen-1)))throw new AssertionError("恢复载荷变化");
                }
                if(seen!=161 || journal.commitsAfter(158,2).size()!=2)throw new AssertionError("恢复或分页不完整");
            }
        }
    }
}
