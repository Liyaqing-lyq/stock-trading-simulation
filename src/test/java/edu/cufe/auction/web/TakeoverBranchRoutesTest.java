package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 从真实父设置派生、实际启动子 JVM，并在停启前后核验两个接管档各自的配置。 */
class TakeoverBranchRoutesTest {
    @TempDir Path work;

    private List<SceneRoutes.Route> customRoutes(SceneRoutes.Snapshot initial, int change, boolean enabled) {
        var rows = new ArrayList<SceneRoutes.Route>();
        for (var row : initial.routes()) {
            int i = row.scene().ordinal();
            rows.add(new SceneRoutes.Route(row.scene(), "key-" + ((i+2)%5+1),
                    i%2==0 ? SceneRoutes.MODEL_PRO : SceneRoutes.MODEL_FLASH, enabled && i%2==0,
                    40+i+change, 100_000L+change+i, 700+i, 2, DecisionModel.Route.LONG_HORIZON,
                    i%2==0 ? "deepseek-thinking-high-v1" : null));
        }
        return rows;
    }

    @Test void bothMarkedEntriesFreezeAtCreationAndOnlyNewChildSettingsStartDisabled() throws Exception {
        Path journal = work.resolve("world.journal");
        var repo = new BranchRepository(work.resolve("runs"));
        var parent = new AtomicReference<WorldRuntime>();
        Path keys = work.resolve("dummy-keys.txt");
        Files.write(keys,List.of("sk-local-aaaaaaaaaaaaaaaa","sk-local-bbbbbbbbbbbbbbbb","sk-local-cccccccccccccccc",
                "sk-local-dddddddddddddddd","sk-local-eeeeeeeeeeeeeeee"));
        try (var routes=SceneRoutes.open(work);
             var host=new WorldWebHost(() -> {
                 var world=ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(journal);
                 parent.set(world);
                 return new WorldWebHost.Opened(world,world.profile().manifest().runId());
             },routes,(call,timeout)->{throw new AssertionError("未授权派发模型");});
             var launcher=new RunLauncher(new RunLauncher.Config(work.toAbsolutePath(),repo.runsDir(),keys.toAbsolutePath(),
                     18370,18379,2,30000,System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"))),
                     host::branchProfile)) {
            var initial=routes.view();
            host.replaceRoutes(initial.revision(),customRoutes(initial,0,true));
            var source=routes.view();
            long cut=host.runIdentity().position();
            var own=host.takeoverCut("rita",cut,false,repo);
            var explicit=host.createBranch("second-child",cut,BranchOrigin.Kind.GOD_TAKEOVER,"人工试验");
            var ordinary=host.createBranch("operator-child",cut,BranchOrigin.Kind.OPERATOR_BRANCH,"普通派生");
            host.replaceRoutes(source.revision(),customRoutes(initial,7,true));
            assertEquals(own,host.takeoverCut("rita",cut,false,repo));
            assertEquals(cut,host.runIdentity().position());
            var profile=host.branchProfile();
            try(var op=repo.open(ordinary.childRunId(),profile)){
                assertNull(op.branchOrigin().orElseThrow().takeoverRoutes());
            }
            List<DecisionModel.Route> first=null;
            for(String child:List.of(own.runId(),explicit.childRunId())) {
                try (var archived=repo.open(child,profile)) {
                    var origin=archived.branchOrigin().orElseThrow();
                    assertEquals(parent.get().snapshot().accounts(),archived.snapshot().accounts());
                    assertEquals(parent.get().snapshot().securities(),archived.snapshot().securities());
                    assertEquals(parent.get().snapshot().decisions().orElseThrow().state().decisions(),
                            archived.snapshot().decisions().orElseThrow().state().decisions());
                    assertEquals(cut,origin.parentPosition());
                    assertEquals(BranchOrigin.Kind.GOD_TAKEOVER,origin.kind());
                    var frozen=origin.takeoverRoutes();
                    assertEquals(5,frozen.size());
                    assertTrue(frozen.stream().allMatch(r->r.revision()==source.revision()));
                    if(first==null) first=frozen; else assertEquals(first,frozen);
                    var json=new ObjectMapper();
                    ObjectNode damaged=json.valueToTree(origin);
                    ((com.fasterxml.jackson.databind.node.ArrayNode)damaged.required("takeoverRoutes")).remove(2);
                    assertThrows(IllegalArgumentException.class,()->json.convertValue(damaged,BranchOrigin.class));
                    ObjectNode malformed=json.valueToTree(origin);
                    ((ObjectNode)malformed.at("/takeoverRoutes/0")).put("maxCalls",0);
                    assertThrows(IllegalArgumentException.class,()->json.convertValue(malformed,BranchOrigin.class));
                }
                assertEquals("RUNNING",launcher.start(child).status());
                assertEquals("STOPPED",launcher.stop(child).status());
                Path folder=work.resolve("run-state").resolve(child);
                try(var saved=SceneRoutes.open(folder)) {
                    assertEquals(0,saved.view().revision());
                    assertEquals(customRoutes(initial,0,false),saved.view().routes());
                }
            }
            Path local=work.resolve("run-state").resolve(own.runId());
            Path sibling=work.resolve("run-state").resolve(explicit.childRunId()).resolve(SceneRoutes.FILE_NAME);
            byte[] other=Files.readAllBytes(sibling);
            try(var saved=SceneRoutes.open(local)){
                saved.replace(saved.view().revision(),customRoutes(initial,12,true));
            }
            byte[] configured=Files.readAllBytes(local.resolve(SceneRoutes.FILE_NAME));
            assertEquals("RUNNING",launcher.start(own.runId()).status());
            assertEquals("STOPPED",launcher.stop(own.runId()).status());
            assertArrayEquals(configured,Files.readAllBytes(local.resolve(SceneRoutes.FILE_NAME)));
            assertArrayEquals(other,Files.readAllBytes(sibling));
            assertEquals(customRoutes(initial,7,true),routes.view().routes());
            verifyDamagedOriginIsRejected(explicit, cut, profile, repo);
            assertArrayEquals(configured,Files.readAllBytes(local.resolve(SceneRoutes.FILE_NAME)));
            assertArrayEquals(other,Files.readAllBytes(sibling));
        }
    }

    private void verifyDamagedOriginIsRejected(WorldRuntime.BranchReceipt receipt, long cut, BranchProfile profile,
                                               BranchRepository repo) throws Exception {
        var frame=WorldJournalScanner.readPrefix(receipt.childFile(),cut+1,null).first();
        var json=new ObjectMapper();
        var entries=json.readTree(repo.runsDir().resolve(BranchRunIndex.FILE_NAME).toFile()).required("entries");
        for(var entry:entries) {
            assertEquals("GOD_TAKEOVER".equals(entry.required("kind").asText()),entry.has("takeoverRoutesSha256"));
        }
        byte[] archived=Files.readAllBytes(receipt.childFile());
        for (String fault:List.of("missing-revision","string-number","removed-routes","changed-routes")) {
            var content=(ObjectNode)json.readTree(frame.commandContent());
            for (var raw:content.at("/branch/takeoverRoutes")) {
                ObjectNode row=(ObjectNode)raw;
                if (fault.equals("missing-revision")) row.remove("revision");
                if (fault.equals("string-number")) row.put("maxCalls",row.required("maxCalls").asText());
                if (fault.equals("changed-routes")) row.put("maxCalls",row.required("maxCalls").asInt()+1);
            }
            if (fault.equals("removed-routes")) ((ObjectNode)content.required("branch")).remove("takeoverRoutes");
            var events=new ArrayList<WorldEvent>();
            for(var e:frame.events()) events.add(e.type().equals("BRANCH_OPEN_V1")
                    ? new WorldEvent(e.eventId(),e.type(),content.required("branch").toString()) : e);
            Path copy=work.resolve(fault+".journal");
            try(var writer=new WorldJournal(copy,receipt.childRunId(),cut)) {
                writer.commit(frame.commandId(),content.toString(),frame.result(),events);
            }
            byte[] before=Files.readAllBytes(copy);
            BranchRejectedException refused;
            if (fault.equals("removed-routes") || fault.equals("changed-routes")) {
                try {
                    Files.write(receipt.childFile(),before);
                    refused=assertThrows(BranchRejectedException.class,()->repo.open(receipt.childRunId(),profile));
                    assertArrayEquals(before,Files.readAllBytes(receipt.childFile()));
                    assertTrue(refused.getMessage().contains("运行索引登记不一致"));
                } finally {
                    Files.write(receipt.childFile(),archived);
                }
            } else {
                refused=assertThrows(BranchRejectedException.class,()->WorldRuntime.openBranch(copy,
                        ignored->receipt.sealedPrefix(),profile));
            }
            assertEquals(WorldRuntime.BRANCH_CHILD_INVALID,refused.code());
            assertArrayEquals(before,Files.readAllBytes(copy));
        }
    }
}
