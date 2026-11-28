package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 显式冻结思考策略的真实回环HTTP与旧字节回归；无真实提供方调用。 */
class FrozenThinkingProviderTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String POLICY = "deepseek-thinking-high-v1";
    @TempDir Path root;

    static DecisionModel.Route route(String policy) throws Exception {
        ObjectNode node = JSON.valueToTree(new DecisionModel.Route("trading", "key-1", "deepseek-flash", 7, 2500, 35000000, 8192, 4, "lifecycle-v1"));
        if (policy != null) node.put("providerPolicy", policy);
        return JSON.treeToValue(node, DecisionModel.Route.class);
    }
    static DecisionRun.Call call(DecisionModel.Route route) {
        String input = "{\"engineering\":{\"roleContextVersion\":\"deliberation-v5\"},\"observation\":\"原样观察\"}";
        return new DecisionRun.Call("run-1","action-1","attempt-1",1,1,"wake",List.of("role.wait"),input,
                new DecisionModel.ProviderPlan(route,input.getBytes(StandardCharsets.UTF_8).length));
    }
    static String response(String finish, String content, int tokens) throws Exception {
        var n=JSON.createObjectNode().put("id","request-safe").put("model","deepseek-flash");
        var c=n.putArray("choices").addObject().put("index",0).put("finish_reason",finish);
        c.putObject("message").put("role","assistant").put("content",content).put("reasoning_content","PRIVATE_REASONING_SENTINEL");
        n.putObject("usage").put("prompt_tokens",10).put("completion_tokens",tokens).put("total_tokens",10+tokens);
        return JSON.writeValueAsString(n);
    }
    LiveDecisionProvider.AuditedEvaluation send(DecisionRun.Call call,String response,int status,AtomicReference<String> captured) throws Exception {
        return send(call,response,status,captured,null);
    }
    LiveDecisionProvider.AuditedEvaluation send(DecisionRun.Call call,String response,int status,AtomicReference<String> captured,ProbeProviderEvidence evidence) throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        server.createContext("/chat/completions",x->{captured.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));var b=response.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);x.close();});
        server.start();
        try {var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions");
            var provider=new LiveDecisionProvider(slot->new DeepSeekClient(()->"sk-fixture-aaaaaaaaaaaaaaaa",uri));
            if(evidence==null) return provider.evaluateAudited(call,5000);
            var completion=evidence.evaluate(1,call,5000,provider);
            var provenance=JSON.readValue(Files.readAllBytes(root.resolve("provider-1.json")),LiveDecisionProvider.ProviderProvenance.class);
            return new LiveDecisionProvider.AuditedEvaluation(completion,provenance);
        } finally {server.stop(0);}
    }
    @Test void actualHttpUsesFrozenEnabledHighAndFullCompletionUsage() throws Exception {
        var call=call(route(POLICY)); var captured=new AtomicReference<String>();
        var audited=send(call,response("stop","{\"tool\":\"role.wait\"}",6000),200,captured);
        var request=JSON.readTree(captured.get());
        assertEquals("enabled",request.path("thinking").path("type").asText());assertEquals("high",request.path("reasoning_effort").asText());
        assertEquals(8192,request.path("max_tokens").asInt());assertEquals(call.inputText(),request.path("messages").get(1).path("content").asText());
        assertEquals(LiveDecisionProvider.PLANNING_SYSTEM_TEXT,request.path("messages").get(0).path("content").asText());
        assertTrue(audited.completion().businessReply());assertEquals(6000,audited.completion().reply().usage().outputTokens());assertNull(audited.completion().reply().usage().cost());
        var saved=JSON.valueToTree(audited);assertEquals("provider-provenance-v2",saved.path("provenance").path("schema").asText());
        assertEquals(POLICY,saved.path("provenance").path("invocation").path("providerPolicy").asText());
        assertEquals("stop",saved.path("provenance").path("invocation").path("finishReason").asText());assertFalse(saved.toString().contains("PRIVATE_REASONING_SENTINEL"));
    }
    @Test void oldHttpBodyAndV1ProvenanceBytesRemainExact() throws Exception {
        var call=call(route(null));var cap=new AtomicReference<String>();var a=send(call,response("stop","{}",2),200,cap);
        var expected=JSON.createObjectNode().put("model","deepseek-flash");var msgs=expected.putArray("messages");msgs.addObject().put("role","system").put("content",LiveDecisionProvider.PLANNING_SYSTEM_TEXT);msgs.addObject().put("role","user").put("content",call.inputText());expected.put("stream",false);expected.putObject("thinking").put("type","disabled");expected.putObject("response_format").put("type","json_object");expected.put("max_tokens",8192);
        assertEquals(JSON.writeValueAsString(expected),cap.get());assertFalse(JSON.writeValueAsString(a.provenance()).contains("invocation"));assertEquals("provider-provenance-v1",a.provenance().schema());
        assertFalse(JSON.writeValueAsString(route(null)).contains("providerPolicy"));
        assertEquals("{\"schema\":\"provider-provenance-v1\",\"runId\":\"run-1\",\"actionId\":\"action-1\",\"attemptId\":\"attempt-1\",\"attemptNo\":1,\"recoveryGeneration\":1,\"requestedModel\":\"deepseek-flash\",\"providerReportedModel\":null,\"requestId\":null,\"outcome\":\"UNKNOWN\",\"failureCode\":\"HTTP_TIMEOUT\"}",
                JSON.writeValueAsString(new LiveDecisionProvider.ProviderProvenance("provider-provenance-v1","run-1","action-1","attempt-1",1,1,"deepseek-flash",null,null,"UNKNOWN","HTTP_TIMEOUT")));
        assertFalse(JSON.writeValueAsString(new DeepSeekClient.Result(DeepSeekClient.Outcome.EMPTY,null,null,null,null)).contains("finishReason"));
    }
    @Test void incompleteAndUnknownFinishKeepAuthenticFailureAndUsage() throws Exception {
        for(String finish:List.of("length","content_filter","stop","unknown-finish")) {
            var a=send(call(route(POLICY)),response(finish,"",9000),200,new AtomicReference<>());
            assertFalse(a.completion().businessReply());assertEquals("",a.completion().reply().body());assertEquals(9000,a.completion().reply().usage().outputTokens());
            var inv=JSON.valueToTree(a.provenance()).path("invocation");
            if(finish.equals("unknown-finish")){assertEquals("HTTP_PROTOCOL",a.completion().failureCode());assertTrue(inv.path("finishReason").isNull());}
            else assertEquals(finish,inv.path("finishReason").asText());
        }
        var unknown=send(call(route(POLICY)),"{}",503,new AtomicReference<>());assertNull(unknown.completion().reply().usage());assertNull(unknown.provenance().requestId());
    }
    @Test void scenePersistenceFreezesPolicyAndOldBytesAndRejectsUnknown() throws Exception {
        try(var routes=SceneRoutes.open(root)) {
            byte[] old=Files.readAllBytes(root.resolve(SceneRoutes.FILE_NAME));assertFalse(new String(old,StandardCharsets.UTF_8).contains("providerPolicy"));
            var original=routes.view();var changed=new ArrayList<SceneRoutes.Route>();
            for(var r:original.routes()){ObjectNode n=JSON.valueToTree(r);n.put("providerPolicy",POLICY);changed.add(JSON.treeToValue(n,SceneRoutes.Route.class));}
            routes.replace(0,changed);
            try(var reopened=SceneRoutes.open(root)){assertEquals(changed,reopened.view().routes());}
            var map=WorldWebHost.class.getDeclaredMethod("decisionRoute",SceneRoutes.Route.class,long.class);map.setAccessible(true);
            var frozen=call((DecisionModel.Route)map.invoke(null,changed.get(2),1L));routes.replace(1,original.routes());
            assertEquals(POLICY,JSON.valueToTree(frozen).path("plan").path("route").path("providerPolicy").asText());
            var captured=new AtomicReference<String>();send(frozen,response("stop","{}",4),200,captured);
            assertEquals("enabled",JSON.readTree(captured.get()).path("thinking").path("type").asText());
        }
        assertThrows(Exception.class,()->route("unrecognized"));
    }
    @Test void newFactoryFreezesOnlyNewTimeoutAndManifestAndCrossOpenRejects() throws Exception {
        var method=ComposedIndustryScenario.class.getMethod("openThinkingInformedOperating",Path.class);
        Path newer=root.resolve("thinking.journal"), older=root.resolve("old.journal");ObjectNode oldConfig;long oldPosition;
        try(var old=ComposedIndustryScenario.openContextualInformedOperating(older)) {oldConfig=JSON.valueToTree(old.profile().decisions());oldPosition=old.snapshot().position();}
        try(var world=(WorldRuntime)method.invoke(null,newer)) {
            assertEquals("web-thinking-operating-v1",world.profile().manifest().runId());assertEquals(POLICY,world.profile().manifest().dataVersions().get("provider-policy"));
            var manifest=world.profile().manifest();
            var badData=new TreeMap<>(manifest.dataVersions());badData.put("provider-policy","unknown");
            assertThrows(IllegalArgumentException.class,()->new RunManifest(manifest.runId(),manifest.seed(),manifest.rulesVersion(),manifest.moduleVersions(),badData,manifest.modelConfigVersion()));
            badData.remove("provider-policy");assertThrows(IllegalArgumentException.class,()->new RunManifest(manifest.runId(),manifest.seed(),manifest.rulesVersion(),manifest.moduleVersions(),badData,manifest.modelConfigVersion()));
            assertThrows(IllegalArgumentException.class,()->new RunManifest("different-run",manifest.seed(),manifest.rulesVersion(),manifest.moduleVersions(),manifest.dataVersions(),manifest.modelConfigVersion()));
            var config=(ObjectNode)JSON.valueToTree(world.profile().decisions());assertEquals(60000,config.path("limits").path("wallTimeoutMillis").asLong());((ObjectNode)config.path("limits")).put("wallTimeoutMillis",oldConfig.path("limits").path("wallTimeoutMillis").asLong());assertEquals(oldConfig,config);
        }
        try(var world=(WorldRuntime)method.invoke(null,newer)){assertEquals(oldPosition,world.snapshot().position());}
        assertThrows(Exception.class,()->ComposedIndustryScenario.openContextualInformedOperating(newer));assertThrows(Exception.class,()->method.invoke(null,older));
    }
    @Test void toolCallsWithContentStayUnusableAndKeepSafeFinishAndUsage() throws Exception {
        var body=(ObjectNode)JSON.readTree(response("length","{\"tool\":\"role.wait\"}",8500));
        ((ObjectNode)body.path("choices").get(0).path("message")).putArray("tool_calls").addObject().put("id","call-safe");
        var audited=send(call(route(POLICY)),body.toString(),200,new AtomicReference<>());
        assertEquals("PROVIDER_TOOL_CALLS",audited.completion().failureCode());assertFalse(audited.completion().businessReply());assertEquals("",audited.completion().reply().body());
        assertEquals(8500,audited.completion().reply().usage().outputTokens());assertEquals("length",JSON.valueToTree(audited.provenance()).path("invocation").path("finishReason").asText());
        assertFalse(JSON.writeValueAsString(audited).contains("PRIVATE_REASONING_SENTINEL"));
    }
    @Test void explicitProbeModeUsesOnlyV2And8192AndRejectsSeedTampering() throws Exception {
        try(var routes=SceneRoutes.open(root)) {
            var newer=AutonomousLifecycleProbe.probeRoutes(routes.view(),"THINKING_INFORMED_OPERATING");
            assertEquals(5,newer.size());for(var r:newer){assertEquals(POLICY,r.providerPolicy());assertEquals(8192,r.maxOutputTokens());assertEquals(2500,r.maxCalls());assertEquals(35000000,r.maxTotalTokens());assertEquals(4,r.maxConcurrency());assertEquals("lifecycle-v1",r.budgetPolicy());}
            for(var r:AutonomousLifecycleProbe.probeRoutes(routes.view(),"CONTEXTUAL_INFORMED_OPERATING")){assertNull(r.providerPolicy());assertEquals(2048,r.maxOutputTokens());}
        }
        new AutonomousLifecycleProbe.Config("THINKING_INFORMED_OPERATING",20,1,86400000,3600000,null,"frozen-source");
        ProbeProviderEvidence.freezePolicy(root,true);var evidence=new ProbeProviderEvidence(root,true);
        send(call(route(POLICY)),response("stop","{}",5000),200,new AtomicReference<>(),evidence);
        var result=JSON.createObjectNode().put("httpCalls",1);result.set("providerProvenance",evidence.summary(1,0));
        assertFalse(evidence.stopRequested());ProbeProviderEvidence.validateSeed(root,result,true);
        assertThrows(Exception.class,()->ProbeProviderEvidence.validateSeed(root,result));
        var providerFile=root.resolve("provider-1.json");var original=Files.readAllBytes(providerFile);
        for(String field:List.of("providerPolicy","thinkingType","reasoningEffort","maxOutputTokens","finishReason")) {
            var raw=(ObjectNode)JSON.readTree(original);var invocation=(ObjectNode)raw.path("invocation");
            if(field.equals("maxOutputTokens")) invocation.put(field,2048);else invocation.put(field,"tampered");JSON.writeValue(providerFile.toFile(),raw);
            assertThrows(Exception.class,()->ProbeProviderEvidence.validateSeed(root,result,true),field);
        }
        Files.write(providerFile,original);
        var callOriginal=Files.readAllBytes(root.resolve("call-1.json"));
        var wrongContext=(ObjectNode)JSON.readTree(callOriginal);String wrongInput=wrongContext.path("inputText").asText().replace("deliberation-v5","deliberation-v4");wrongContext.put("inputText",wrongInput);
        JSON.writeValue(root.resolve("call-1.json").toFile(),wrongContext);assertThrows(Exception.class,()->ProbeProviderEvidence.validateSeed(root,result,true));Files.write(root.resolve("call-1.json"),callOriginal);
        var callFile=root.resolve("call-1.json");var frozen=(ObjectNode)JSON.readTree(callFile.toFile());((ObjectNode)frozen.path("plan").path("route")).remove("providerPolicy");JSON.writeValue(callFile.toFile(),frozen);
        assertThrows(Exception.class,()->ProbeProviderEvidence.validateSeed(root,result,true));
        assertFalse(Files.readString(providerFile).contains("PRIVATE_REASONING_SENTINEL"));assertFalse(Files.readString(root.resolve("reply-1.json")).contains("PRIVATE_REASONING_SENTINEL"));
    }
    @Test void technicalFailureStopsButTruthfulUnknownSeedRemainsExplicit() throws Exception {
        ProbeProviderEvidence.freezePolicy(root,true);var evidence=new ProbeProviderEvidence(root,true);
        var failure=send(call(route(POLICY)),"{}",503,new AtomicReference<>(),evidence);
        assertEquals("HTTP_SERVICE_UNAVAILABLE",failure.completion().failureCode());assertEquals("",failure.completion().reply().body());assertNull(failure.completion().reply().usage());
        assertTrue(evidence.stopRequested());assertEquals(ProbeProviderEvidence.TECHNICAL_FAILURE,evidence.stopReason());assertFalse(evidence.failed());
        var result=JSON.createObjectNode().put("httpCalls",1);result.set("providerProvenance",evidence.summary(1,0));ProbeProviderEvidence.validateSeed(root,result,true);
        assertNull(failure.provenance().requestId());assertNull(failure.provenance().invocation().finishReason());
    }
    @Test void successfulReplyWithUnknownIdentityStopsAndCannotResume() throws Exception {
        ProbeProviderEvidence.freezePolicy(root,true);var evidence=new ProbeProviderEvidence(root,true);
        var body=(ObjectNode)JSON.readTree(response("stop","{}",10));body.remove("id");
        var actual=send(call(route(POLICY)),body.toString(),200,new AtomicReference<>(),evidence);
        assertTrue(actual.completion().businessReply());assertTrue(evidence.stopRequested());assertEquals(ProbeProviderEvidence.UNKNOWN_EVIDENCE,evidence.stopReason());
        var result=JSON.createObjectNode().put("httpCalls",1);result.set("providerProvenance",evidence.summary(1,0));assertEquals(1,result.path("providerProvenance").path("missingIdentityCalls").asInt());
        assertThrows(Exception.class,()->ProbeProviderEvidence.validateSeed(root,result,true));
    }

    @Test void firstTechnicalFailureBlocksNewHttpSequenceAndDrainsAlreadyInFlight() throws Exception {
        var received=new java.util.concurrent.atomic.AtomicInteger();var issued=new java.util.concurrent.atomic.AtomicInteger();
        var bothArrived=new java.util.concurrent.CountDownLatch(2);var releaseSuccess=new java.util.concurrent.CountDownLatch(1);var stop=new java.util.concurrent.CountDownLatch(1);
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);var httpWorkers=java.util.concurrent.Executors.newCachedThreadPool();
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);server.setExecutor(httpWorkers);
        String success=response("stop","{}",1234);
        server.createContext("/chat/completions",x->{
            int number=received.incrementAndGet();x.getRequestBody().readAllBytes();bothArrived.countDown();
            try {if(!bothArrived.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("fixture arrivals");if(number==2)releaseSuccess.await(5,java.util.concurrent.TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            byte[] bytes=(number==1?"{}":success).getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(number==1?503:200,bytes.length);x.getResponseBody().write(bytes);x.close();
        });server.start();
        try {
            var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions");var provider=new LiveDecisionProvider(slot->new DeepSeekClient(()->"sk-fixture-aaaaaaaaaaaaaaaa",uri));
            var evidence=new ProbeProviderEvidence(root,true);ProbeProviderEvidence.freezePolicy(root,true);
            var config=new AutonomousLifecycleProbe.Config("THINKING_INFORMED_OPERATING",20,1,86400000,3600000,null,"source");
            var base=call(route(POLICY));var second=new DecisionRun.Call(base.runId(),"action-2","attempt-2",1,1,base.wakeReason(),base.allowedTools(),base.inputText(),base.plan());
            var first=workers.submit(()->AutonomousLifecycleProbe.evaluateEvidence(config,evidence,issued,base,5000,provider,stop::countDown));
            var inflight=workers.submit(()->AutonomousLifecycleProbe.evaluateEvidence(config,evidence,issued,second,5000,provider,stop::countDown));
            assertTrue(stop.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(2,received.get());assertEquals(2,issued.get());
            var blocked=AutonomousLifecycleProbe.evaluateEvidence(config,evidence,issued,base,5000,provider,stop::countDown);
            assertEquals("PROBE_EVIDENCE_STOP",blocked.failureCode());assertEquals(2,issued.get());assertEquals(2,received.get());assertFalse(Files.exists(root.resolve("call-3.json")));
            releaseSuccess.countDown();var completions=List.of(first.get(5,java.util.concurrent.TimeUnit.SECONDS),inflight.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,completions.stream().filter(LiveCompletionInbox.Completion::businessReply).count());assertEquals(1,completions.stream().filter(c->"HTTP_SERVICE_UNAVAILABLE".equals(c.failureCode())).count());
            assertEquals(1234,completions.stream().filter(LiveCompletionInbox.Completion::businessReply).findFirst().orElseThrow().reply().usage().outputTokens());
            assertEquals(ProbeProviderEvidence.TECHNICAL_FAILURE,evidence.stopReason());assertTrue(evidence.summary(2,0).path("evidenceComplete").asBoolean());
            var result=JSON.createObjectNode().put("httpCalls",2);result.set("providerProvenance",evidence.summary(2,0));ProbeProviderEvidence.validateSeed(root,result,true);
        } finally {releaseSuccess.countDown();workers.shutdownNow();server.stop(0);httpWorkers.shutdownNow();}
    }

    @Test void thinkingRouteConflictsAndHttpOverrunKeepsFullDeclaredAmount() throws Exception {
        var limits=new DecisionModel.Limits(3,60000,20000,2000,8192,4,2,60000);
        var cap=DecisionModel.Usage.unknownCost(100,1000000,500000);
        var config=new DecisionModel.Config("DEEPSEEK_HTTP","deepseek:thinking-test","utf8-plus-1024-v1",Map.of("wake",1L),Map.of(),limits,new DecisionModel.Budget(cap,Map.of("alice",cap)),DecisionModel.Profile.LIVE_HTTP_V1);
        var state=DecisionModel.beginSession(DecisionModel.open("run-1",7,config)).state();var frozen=call(route(POLICY));
        var request=new DecisionModel.OpenRequest("run-1","action-1","alice","wake",1000,0,1000,"role-decision-tools-v1",List.of("role.wait"),frozen.inputText(),frozen.plan());
        var opened=DecisionModel.openDecision(state,request,0,1000);assertEquals(DecisionModel.Verdict.ACCEPTED,opened.verdict());
        var other=new DecisionModel.OpenRequest("run-1","other","alice","wake",1000,0,1000,"role-decision-tools-v1",List.of("role.wait"),frozen.inputText(),new DecisionModel.ProviderPlan(route(null),frozen.plan().inputBytes()));
        var conflict=DecisionModel.openDecision(opened.state(),other,0,1000);assertEquals(DecisionModel.REASON_ROUTE_CONFLICT,conflict.reason());
        var attempt=opened.decision().lastAttempt();var actualCall=new DecisionRun.Call("run-1","action-1",attempt.attemptId(),attempt.attemptNo(),opened.state().recoveryGeneration(),"wake",List.of("role.wait"),frozen.inputText(),frozen.plan());
        var actual=send(actualCall,response("stop","{\"tool\":\"role.wait\"}",9000),200,new AtomicReference<>());
        var settled=DecisionModel.submitReply(opened.state(),actual.completion().reply());assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED,settled.decision().outcome());
        assertEquals(9000,settled.decision().lastAttempt().declaredUsage().outputTokens());
        assertEquals(8192,settled.state().runUsage().outputTokens());assertEquals(8192,DecisionModel.sceneUsage(settled.state(),"trading").outputTokens());assertTrue(DecisionModel.liveBudgetBlocked(settled.state()));
        var full=send(actualCall,response("stop","{\"tool\":\"role.wait\"}",6000),200,new AtomicReference<>());
        var normal=DecisionModel.submitReply(opened.state(),full.completion().reply());assertEquals(6000,normal.state().runUsage().outputTokens());assertEquals(6000,DecisionModel.sceneUsage(normal.state(),"trading").outputTokens());assertFalse(DecisionModel.liveBudgetBlocked(normal.state()));
        assertNull(settled.state().runUsage().cost());assertFalse(DecisionModel.toJson(settled.state()).contains("PRIVATE_REASONING_SENTINEL"));
        assertEquals(settled.state(),DecisionModel.fromJson(DecisionModel.toJson(settled.state())));
        assertThrows(IllegalArgumentException.class,()->new DeepSeekClient.Request("deepseek-flash","system","user",8192,60000,"unknown"));
    }

}
