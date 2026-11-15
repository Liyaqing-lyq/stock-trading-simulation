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

/** 数字/陈酿模型动作、三JVM、分支与保持原命令的自洽订单篡改。 */
public class BaselineIndustryRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    @Test void threeProcessesRestoreWholeWorldAndIsolatedBranch() throws Exception {
        Set<String> pids = new HashSet<>();
        for (String phase : List.of("write", "finish", "branch")) {
            Path output = dir.resolve(phase + ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    BaselineIndustryRecoveryTest.class.getName(), dir.toString(), phase)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(75, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("十公司进程超时：" + phase); }
            String result = Files.readString(output); assertEquals(0, process.exitValue(), result);
            pids.add(result.lines().filter(line -> line.startsWith("BASELINE_JVM_OK ")).findFirst().orElseThrow());
        }
        assertEquals(3, pids.size()); assertTrue(Files.exists(dir.resolve("branch-ok")));
    }

    /** 三个独立进程使用真实世界命令和记录的模型回执；没有调用项目外部模型。 */
    public static void main(String[] args) throws Exception {
        Path folder=Path.of(args[0]),journal=folder.resolve("baseline.journal"),expected=folder.resolve("snapshot.json");
        try(var world=ComposedIndustryScenario.openBaseline(journal)) {
            if(args[1].equals("write")) {
                BaselineIndustryWorldTest.appoint(world,DigitalIndustryConfig.COMPANY,DigitalIndustryConfig.MANAGER_PERSON,"jvm-digital",List.of(0L));
                BaselineIndustryWorldTest.appoint(world,AgedIndustryConfig.COMPANY,AgedIndustryConfig.MANAGER_PERSON,"jvm-aged",List.of(0L,1L));
                var digitalCall=PharmaDecisionToolTest.open(world,DigitalIndustryConfig.MANAGER_PERSON,"digital-model");
                long digitalFinish=PharmaDecisionToolTest.reply(world,digitalCall,DigitalDecisionTools.ACT,Map.of("action","BEGIN_PILOT","arguments",Map.of()));
                world.advanceTo(digitalFinish);
                var agedCall=PharmaDecisionToolTest.open(world,AgedIndustryConfig.MANAGER_PERSON,"aged-model");
                long finish=PharmaDecisionToolTest.reply(world,agedCall,AgedDecisionTools.ACT,Map.of("action","ORDER_RAW","arguments",Map.of("orderId","raw-model","units","50")));
                world.advanceTo(finish-500);Files.writeString(folder.resolve("finish.txt"),Long.toString(finish));
            } else {
                assertEquals(JSON.readTree(Files.readString(expected)),JSON.readTree(JSON.writeValueAsString(ManufacturingRecoveryTest.snapshotTree(world))),"治理、资金与全部业务状态必须恢复");
                if(args[1].equals("finish")) {
                    world.advanceTo(Long.parseLong(Files.readString(folder.resolve("finish.txt"))));
                    assertEquals(50,world.operationsRun().orElseThrow().aged().ledger().orders().get(0).units());
                    world.advanceTo(world.operationsRun().orElseThrow().digital().ledger().stageOf(DigitalServiceModel.Stage.PILOT).completesAt());
                    assertEquals(DigitalServiceModel.StageStatus.PASSED,world.operationsRun().orElseThrow().digital().ledger().stageOf(DigitalServiceModel.Stage.PILOT).status());
                } else {
                    var branch=world.createBranch("baseline-child",world.snapshot().position(),BranchOrigin.Kind.OPERATOR_BRANCH,"十公司独立分支验证");var profile=world.profile();world.close();byte[] parent=Files.readAllBytes(journal);
                    try(var child=WorldRuntime.openBranch(branch.childFile(),id->branch.sealedPrefix(),profile)) {
                        BaselineIndustryWorldTest.digital(child,"child-validation",DigitalOperations.Action.BEGIN_VALIDATION);
                        assertEquals(new java.math.BigDecimal("16500.00"),BaselineIndustryWorldTest.cash(child,DigitalIndustryConfig.COMPANY_ACCOUNT));
                    }
                    try(var child=WorldRuntime.openBranch(branch.childFile(),id->branch.sealedPrefix(),profile)) {
                        assertEquals(new java.math.BigDecimal("16500.00"),BaselineIndustryWorldTest.cash(child,DigitalIndustryConfig.COMPANY_ACCOUNT));assertTrue(ComposedCompanyReport.of(child,DigitalIndustryConfig.COMPANY).balanced());
                    }
                    assertArrayEquals(parent,Files.readAllBytes(journal));Files.writeString(folder.resolve("branch-ok"),"verified");System.out.println("BASELINE_JVM_OK "+ProcessHandle.current().pid());return;
                }
            }
            Files.writeString(expected,JSON.writeValueAsString(ManufacturingRecoveryTest.snapshotTree(world)));
        }
        System.out.println("BASELINE_JVM_OK "+ProcessHandle.current().pid());
    }

    @Test void selfConsistentUnpaidOrderCannotRewriteItsOriginalCommand() throws Exception {
        Path journal=dir.resolve("tamper.journal");
        try(var world=ComposedIndustryScenario.openBaseline(journal)) {
            BaselineIndustryWorldTest.appoint(world,AgedIndustryConfig.COMPANY,AgedIndustryConfig.MANAGER_PERSON,"tamper-aged",List.of(0L));
            BaselineIndustryWorldTest.aged(world,"order",AgedOperations.Action.ORDER_RAW,"orderId","raw-original","units","50");
        }
        try(var world=ComposedIndustryScenario.openBaseline(journal)){assertEquals(50,world.operationsRun().orElseThrow().aged().ledger().orders().get(0).units());}
        byte[] bytes=Files.readAllBytes(journal);List<byte[]> frames=new ArrayList<>();
        for(int offset=0;offset<bytes.length;){int size=ByteBuffer.wrap(bytes,offset,4).getInt();frames.add(Arrays.copyOfRange(bytes,offset+8,offset+8+size));offset+=8+size+32;}
        int last=frames.size()-1;ObjectNode frame=(ObjectNode)JSON.readTree(frames.get(last));String original=frame.path("commandContent").asText();int changes=0;
        for(var event:frame.withArray("events")) if("STATE_SNAPSHOT_V1".equals(event.path("type").asText())) {
            ObjectNode snapshot=(ObjectNode)JSON.readTree(event.path("payload").asText());
            ObjectNode order=(ObjectNode)snapshot.path("companyOperations").path("aged").path("ledger").path("orders").get(0);
            assertEquals(50,order.path("units").asLong());order.put("units",51);
            ((ObjectNode)event).put("payload",JSON.writeValueAsString(snapshot));changes++;
        }
        assertEquals(1,changes);assertEquals(original,frame.path("commandContent").asText());frames.set(last,JSON.writeValueAsBytes(frame));ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] payload:frames){out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length).array());out.write(payload);out.write(MessageDigest.getInstance("SHA-256").digest(payload));}
        Files.write(journal,out.toByteArray());Exception rejected=assertThrows(Exception.class,()->ComposedIndustryScenario.openBaseline(journal));String message=String.valueOf(rejected.getMessage());
        assertFalse(message.contains("校验和"),message);assertFalse(message.contains("不可解析"),message);assertTrue(message.contains("不一致")||message.contains("重演")||message.contains("不得"),message);
    }
}
