package edu.cufe.auction.web;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class CompactWorldCommitFaultTest {
 @Test void actualOversizedDeltaStopsAndExposesSafeFaultWithoutPublishingCandidate(@TempDir Path dir) throws Exception {
  try(var routes=SceneRoutes.open(dir);var host=new WorldWebHost(()->{
   var world=ComposedIndustryScenario.openCompactAccountableAutonomous(dir.resolve("world.journal"));
   var before=world.snapshot();
   assertThrows(WorldJournal.FrameTooLargeException.class,()->world.scheduleTransfer("huge","huge-task-"+"x".repeat(4*1024*1024),86400000L,"fund-a","ACC-FUND","AGED-FOUNDER",new BigDecimal("1.00"),0));
   assertEquals(before,world.snapshot());assertTrue(world.isPaused());
   return new WorldWebHost.Opened(world,world.profile().manifest().runId());
  },routes,(call,timeout)->{throw new AssertionError("provider must not be called");})) {
   assertEquals("FAULT",host.liveStatus().phase());assertEquals("COMMIT_FRAME_TOO_LARGE",host.liveStatus().fault());
  }
  try(var world=ComposedIndustryScenario.openCompactAccountableAutonomous(dir.resolve("world.journal"))) {assertFalse(world.isPaused());assertTrue(world.snapshot().pending().stream().noneMatch(t->t.taskId().startsWith("huge-task-")));}
 }
}
