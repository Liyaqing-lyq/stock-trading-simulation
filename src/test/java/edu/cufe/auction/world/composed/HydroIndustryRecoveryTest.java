package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static edu.cufe.auction.world.composed.HydroIndustryConfig.*;
import static org.junit.jupiter.api.Assertions.*;

/** 水电实际模型指令、三JVM、分支与自洽水量篡改来源验证。 */
public class HydroIndustryRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    @Test void threeProcessesRestoreWholeWorldAndIsolatedBranch() throws Exception {
        Set<String> pids = new HashSet<>();
        for (String phase : List.of("write", "finish", "branch")) {
            Path output = dir.resolve(phase + ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    HydroIndustryRecoveryTest.class.getName(), dir.toString(), phase)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("水电进程超时：" + phase); }
            String result = Files.readString(output); assertEquals(0, process.exitValue(), result);
            pids.add(result.lines().filter(line -> line.startsWith("HYDRO_JVM_OK ")).findFirst().orElseThrow());
        }
        assertEquals(3, pids.size()); assertTrue(Files.exists(dir.resolve("branch-ok")));
    }

    /** 独立进程只调用真实命令，不改内核状态。 */
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]), journal = folder.resolve("resource.journal"), expected = folder.resolve("snapshot.json");
        try (var world = ComposedIndustryScenario.openWithHydro(journal)) {
            if (args[1].equals("write")) {
                OperationsFixture.grantBudgetAndAppoint(world, COMPANY, DIRECTORS, MANAGER, "jvm", List.of(0L));
                var call = PharmaDecisionToolTest.open(world, MANAGER, "mine-model");
                long finish = PharmaDecisionToolTest.reply(world, call, HydroDecisionTools.ACT,
                        Map.of("action", "DISPATCH_FLOW", "arguments", Map.of("cubicMetresPerHour", "600")));
                world.advanceTo(finish - 500); Files.writeString(folder.resolve("finish.txt"), Long.toString(finish));
            } else {
                assertEquals(JSON.readTree(Files.readString(expected)), JSON.readTree(JSON.writeValueAsString(ManufacturingRecoveryTest.snapshotTree(world))), "全快照、治理及银行必须逐字段恢复");
                if (args[1].equals("finish")) {
                    world.advanceTo(Long.parseLong(Files.readString(folder.resolve("finish.txt"))));
                    world.advanceTo(world.snapshot().timeMillis()+3600000);
                    assertTrue(HydroIndustryWorldTest.report(world).generatedKilowattHours().signum()>0);
                } else {
                    var branch = world.createBranch("resource-child", world.snapshot().position(), BranchOrigin.Kind.OPERATOR_BRANCH, "水电世界分支验证");
                    var profile = world.profile(); world.close(); byte[] parent = Files.readAllBytes(journal);
                    try (var child = WorldRuntime.openBranch(branch.childFile(), id -> branch.sealedPrefix(), profile)) {
                        HydroIndustryWorldTest.act(child,"child-maint",new HydroOperations.Request(HydroOperations.Action.MAINTENANCE,Map.of()));
                        assertEquals("60.00",HydroIndustryWorldTest.report(child).maintenanceCost().toPlainString());
                    }
                    try (var child = WorldRuntime.openBranch(branch.childFile(), id -> branch.sealedPrefix(), profile)) {
                        assertEquals("60.00",HydroIndustryWorldTest.report(child).maintenanceCost().toPlainString());
                    }
                    assertArrayEquals(parent, Files.readAllBytes(journal)); Files.writeString(folder.resolve("branch-ok"), "verified");
                    System.out.println("HYDRO_JVM_OK " + ProcessHandle.current().pid()); return;
                }
            }
            Files.writeString(expected, JSON.writeValueAsString(ManufacturingRecoveryTest.snapshotTree(world)));
        }
        System.out.println("HYDRO_JVM_OK " + ProcessHandle.current().pid());
    }

    @Test void selfConsistentWaterEditCannotRewriteOriginalSettlement() throws Exception {
        Path journal=dir.resolve("tamper.journal");
        try(var world=ComposedIndustryScenario.openWithHydro(journal)){world.advanceTo(3600000);}
        try(var world=ComposedIndustryScenario.openWithHydro(journal)){assertEquals(600,HydroIndustryWorldTest.report(world).inflowCubicMetres());}
        byte[] bytes=Files.readAllBytes(journal); List<byte[]> frames=new ArrayList<>();
        for(int offset=0;offset<bytes.length;) {
            int size=ByteBuffer.wrap(bytes,offset,4).getInt();frames.add(Arrays.copyOfRange(bytes,offset+8,offset+8+size));offset+=8+size+32;
        }
        int last=frames.size()-1; ObjectNode frame=(ObjectNode)JSON.readTree(frames.get(last));
        assertTrue(frame.path("commandId").asText().startsWith("hydro-settle:"));
        String original=frame.path("commandContent").asText();int changes=0;
        for(var event:frame.withArray("events")) if("STATE_SNAPSHOT_V1".equals(event.path("type").asText())) {
            ObjectNode snapshot=(ObjectNode)JSON.readTree(event.path("payload").asText());
            ObjectNode plant=(ObjectNode)snapshot.path("companyOperations").path("hydro").path("ledger").path("plant");
            plant.put("storageCubicMetres",plant.path("storageCubicMetres").asLong()+1);
            plant.put("inflowCubicMetres",plant.path("inflowCubicMetres").asLong()+1);
            ((ObjectNode)event).put("payload",JSON.writeValueAsString(snapshot));changes++;
        }
        assertEquals(1,changes);assertEquals(original,frame.path("commandContent").asText());
        frames.set(last,JSON.writeValueAsBytes(frame));ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] payload:frames){out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length).array());out.write(payload);out.write(MessageDigest.getInstance("SHA-256").digest(payload));}
        Files.write(journal,out.toByteArray());
        Exception rejected=assertThrows(Exception.class,()->ComposedIndustryScenario.openWithHydro(journal));
        String message=String.valueOf(rejected.getMessage());
        assertFalse(message.contains("校验和"),message);assertFalse(message.contains("不可解析"),message);
        assertTrue(message.contains("不一致")||message.contains("重演")||message.contains("不得"),message);
    }
}
