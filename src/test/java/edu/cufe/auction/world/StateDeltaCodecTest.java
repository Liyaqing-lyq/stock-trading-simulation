package edu.cufe.auction.world;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import static org.junit.jupiter.api.Assertions.*;
class StateDeltaCodecTest {
 @Test void exactDecimalAndStringsAndArrayEdits() throws Exception {
  String before="{\"cash\":12345678901234567890.123400,\"input\":\"中文\\n原文\",\"a\":[1,2,3]}";
  String after="{\"cash\":12345678901234567890.123401,\"input\":\"中文\\n原文\",\"a\":[1,4],\"new\":0.00}";
  String first=StateDeltaCodec.encode("r",1,null,before);
  String restored=StateDeltaCodec.apply("r",1,null,first);
  assertTrue(restored.contains("12345678901234567890.123400"));
  String delta=StateDeltaCodec.encode("r",2,restored,after);
  String result=StateDeltaCodec.apply("r",2,restored,delta);
  assertTrue(result.contains("12345678901234567890.123401")); assertTrue(result.contains("0.00"));
  String scaleOnly=after.replace("0.00}","0.000}");
  assertTrue(StateDeltaCodec.apply("r",3,result,StateDeltaCodec.encode("r",3,result,scaleOnly)).contains("0.000"));
  assertThrows(IOException.class,()->StateDeltaCodec.apply("other",2,restored,delta));
  assertThrows(IOException.class,()->StateDeltaCodec.apply("r",3,restored,delta));
  assertThrows(IOException.class,()->StateDeltaCodec.apply("r",2,result,delta));
  assertThrows(IOException.class,()->StateDeltaCodec.apply("r",2,restored,delta.replace("123401","123402")));
 }
 @Test void compactOperatingVersionIsExplicitAndLegacyFactoryRejectsIt(@TempDir Path dir) throws Exception {
  Path file=dir.resolve("world.journal");
  try(var world=ComposedIndustryScenario.openCompactOperatingAutonomous(file)) {assertEquals("v1",world.profile().manifest().dataVersions().get("external-demand"));world.advanceTo(10);}
  try(var world=ComposedIndustryScenario.openCompactOperatingAutonomous(file)) {assertEquals(10,world.snapshot().timeMillis());}
  assertThrows(IOException.class,()->ComposedIndustryScenario.openOperatingAutonomous(file));
 }
 @Test void compactRuntimeReopensAndHistoryAndAppend(@TempDir Path dir) throws Exception {
  Path file=dir.resolve("world.journal"); WorldRuntime.Snapshot saved;
  try(var world=ComposedIndustryScenario.openCompactAccountableAutonomous(file)) {
   assertEquals("delta-v1",world.profile().manifest().dataVersions().get("state-storage"));
   var initial=world.snapshot();world.advanceTo(10);saved=world.snapshot();assertEquals(initial,world.historyFrame(initial.position()).snapshot());assertEquals(saved,world.historyFrame(saved.position()).snapshot());
   assertFalse(world.branchSupport(saved.position()).supported());
  }
  try(var world=ComposedIndustryScenario.openCompactAccountableAutonomous(file)) { assertEquals(saved,world.snapshot());world.advanceTo(11);assertEquals(11,world.snapshot().timeMillis()); }
 }
}
