package edu.cufe.auction.world;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import static org.junit.jupiter.api.Assertions.*;
class CompactWorldPersistenceTest {
 @Test void cumulativeRuntimeStateBeyondOldBoundAndLegacyCausalFailure(@TempDir Path dir) throws Exception {
  Path old=dir.resolve("legacy.journal"), compact=dir.resolve("compact.journal");
  WorldRuntime.Snapshot saved; long legacyPosition=0; int failedAt=-1; String from,to,owner;
  long started=System.nanoTime();
  try(var legacy=ComposedIndustryScenario.openAccountableAutonomous(old);
      var world=ComposedIndustryScenario.openCompactAccountableAutonomous(compact)) {
   var accounts=world.snapshot().accounts();
   from=accounts.entrySet().stream().filter(e->e.getKey().equals("ACC-FUND")).findFirst().orElseThrow().getKey();
   to=accounts.entrySet().stream().filter(e->e.getKey().equals("AGED-FOUNDER")).findFirst().orElseThrow().getKey();owner=accounts.get(from).ownerId();
   for(int i=0;i<120;i++) {
    String id="large-"+i+"-"+"x".repeat(40000);String task="large-task-"+i;
    String outcome=world.scheduleTransfer(id,task,86400000L,owner,from,to,new BigDecimal("1.00"),0);
    if(failedAt<0) {
     var previous=legacy.snapshot();
     try { assertEquals(outcome,legacy.scheduleTransfer(id,task,86400000L,owner,from,to,new BigDecimal("1.00"),0)); }
     catch(WorldJournal.FrameTooLargeException bound) {
      failedAt=i;legacyPosition=previous.position();assertTrue(bound.actualBytes()>16*1024*1024);
      assertEquals(previous,legacy.snapshot());assertTrue(legacy.isPaused());assertEquals("COMMIT_FRAME_TOO_LARGE",legacy.commitFailureCode());
      System.out.println("LEGACY_OVERSIZE actualBytes="+bound.actualBytes()+" index="+i+" confirmedPosition="+legacyPosition);
     }
    }
   }
   assertTrue(failedAt>=0,"fixture must hit actual old frame bound");
   saved=world.snapshot();assertEquals(120,saved.pending().stream().filter(t->t.taskId().startsWith("large-task-")).count());
   assertEquals(legacy.snapshot().accounts(),saved.accounts());
   IOException checkpoint=assertThrows(IOException.class,()->world.checkpoint(saved.position()));assertEquals("COMPACT_CHECKPOINT_TOO_LARGE",checkpoint.getMessage());
   var field=WorldRuntime.class.getDeclaredField("state");field.setAccessible(true);
   int bytes=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(field.get(world)).length;
   assertTrue(bytes>16*1024*1024);System.out.println("COMPACT_STATE bytes="+bytes+" position="+saved.position()+" elapsedMs="+(System.nanoTime()-started)/1000000);
  }
  long recoveryStart=System.nanoTime();
  try(var world=ComposedIndustryScenario.openCompactAccountableAutonomous(compact)) {
   assertEquals(saved,world.snapshot());long before=world.snapshot().position();
   assertEquals("SCHEDULED:large-task-119",world.scheduleTransfer("large-119-"+"x".repeat(40000),"large-task-119",86400000L,owner,from,to,new BigDecimal("1.00"),0));
   assertEquals(before,world.snapshot().position());world.advanceTo(world.snapshot().timeMillis()+1);assertEquals(before+1,world.snapshot().position());
   System.out.println("COMPACT_RECOVERY elapsedMs="+(System.nanoTime()-recoveryStart)/1000000+" diskBytes="+Files.size(compact));
  }
  long max=0;int count=0;
  try(DataInputStream input=new DataInputStream(Files.newInputStream(compact))) {
   while(input.available()>0){int header=input.readInt();assertEquals(~header,input.readInt());int size=header&Integer.MAX_VALUE;byte[] payload=input.readNBytes(size);assertEquals(32,input.readNBytes(32).length);byte[] decoded=JournalFrameCodec.decode(payload,header);max=Math.max(max,decoded.length);assertTrue(decoded.length<=16*1024*1024);count++;}
  }
  System.out.println("COMPACT_FRAMES count="+count+" maxDecodedBytes="+max+" directory="+dir);
 }
}
