package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TargetTransportProbeTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode run(String name, Path seed, long target, List<String> people, LiveDecisionProvider provider) throws Exception {
        return run("TEN_COMPANY_AUTONOMY",name,seed,target,people,provider);
    }

    private JsonNode run(String mode, String name, Path seed, long target, List<String> people, LiveDecisionProvider provider) throws Exception {
        var cfg = new AutonomousLifecycleProbe.Config(mode,0,0,target,3_600_000L,
                seed == null ? null : seed.toString(),"transport-fixture",people);
        var params = root.resolve(name+".json");
        JSON.writeValue(params.toFile(),cfg);
        var out = root.resolve(name);
        AutonomousLifecycleProbe.run(out,params,() -> provider);
        return JSON.readTree(out.resolve("result.json").toFile());
    }

    @Test void oneUnmeteredHttp429AllowsOtherPersonAndPreservesOriginalAttemptAcrossRestart() throws Exception {
        var http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        var ordered = new CopyOnWriteArrayList<String>();
        http.createContext("/chat/completions", exchange -> {
            try {
                var visible = JSON.readTree(JSON.readTree(exchange.getRequestBody()).at("/messages/1/content").asText());
                String actor = visible.path("person").asText();
                ordered.add(actor);
                if ("alice".equals(actor)) {
                    exchange.sendResponseHeaders(429,-1);
                    return;
                }
                var answer = JSON.createObjectNode().put("tool","role.wait");
                answer.putObject("decision").put("objective",visible.at("/engineering/deliberation/objectives/0/id").asText())
                        .put("basis","等待本人复核").put("reviewAfterMillis",60_000);
                var body = JSON.createObjectNode().put("id","local-success").put("model","deepseek-flash");
                body.putArray("choices").addObject().put("index",0).put("finish_reason","stop")
                        .putObject("message").put("role","assistant").put("content",answer.toString());
                body.putObject("usage").put("prompt_tokens",25).put("completion_tokens",20).put("total_tokens",45);
                byte[] bytes = JSON.writeValueAsBytes(body);
                exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        http.start();
        try {
            var uri=URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/chat/completions");
            var provider=new LiveDecisionProvider(slot -> new DeepSeekClient(() -> "sk-fixture-aaaaaaaaaaaaaaaa",uri));
            var people=List.of("alice","bob");
            var first=run("first",null,100,people,provider);
            assertEquals("TARGET_REACHED",first.path("probeStop").asText());
            assertEquals(List.of("alice","bob"),ordered);
            assertEquals(ordered.size(),first.path("httpCalls").asInt());
            assertEquals(ordered.stream().filter("alice"::equals).count(),first.at("/providerProvenance/transport/failuresByCode/HTTP_RATE_LIMITED").asLong());
            var folder=root.resolve("first");
            int found=0;
            for(int i=1;i<=ordered.size();i++) {
                var raw=JSON.readTree(folder.resolve("provider-"+i+".json").toFile());
                if (!"HTTP_RATE_LIMITED".equals(raw.path("failureCode").asText())) continue;
                assertTrue(raw.get("providerReportedModel").isNull() && raw.get("requestId").isNull());
                var receipt=JSON.readTree(folder.resolve("reply-"+i+".json").toFile());
                assertFalse(receipt.path("businessReply").asBoolean());
                assertTrue(receipt.at("/reply/usage").isNull());
                assertTrue(receipt.at("/reply/body").asText().isEmpty());
                found++;
            }
            assertTrue(found>0);
            var frozenPolicy=folder.resolve(ProbeProviderEvidence.POLICY_FILE);
            var original=Files.readAllBytes(frozenPolicy);
            ProbeProviderEvidence.freezePolicy(folder,true,true);
            assertThrows(IllegalArgumentException.class,() -> run("wrong-policy",folder,200,people,provider));
            Files.write(frozenPolicy,original);
            int prior=ordered.size();
            var second=run("second",folder,200,people,provider);
            assertEquals("TARGET_REACHED",second.path("probeStop").asText());
            assertEquals(0,second.path("httpCalls").asInt(-1));
            assertEquals(prior,ordered.size());
            try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(root.resolve("second/world.journal"))) {
                var decisions=w.snapshot().decisions().orElseThrow().state().decisions();
                var failed=decisions.stream().filter(d -> "alice".equals(d.personPartyId())).findFirst().orElseThrow();
                assertEquals("TECHNICAL_FAILED",failed.outcome().name());
                assertEquals("UNKNOWN_USAGE",failed.lastAttempt().reason());
                assertEquals(failed.lastAttempt().reserved(),failed.lastAttempt().settled());
                assertNull(failed.lastAttempt().declaredUsage());
                assertEquals("WAIT",decisions.stream().filter(d -> "bob".equals(d.personPartyId())).findFirst().orElseThrow().outcome().name());
            }
        } finally { http.stop(0); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String finish, String body,
                                boolean declareModel, boolean toolCall) throws java.io.IOException {
        var response=JSON.createObjectNode().put("id","local-measured");
        if (declareModel) response.put("model","deepseek-flash");
        var choice=response.putArray("choices").addObject().put("index",0).put("finish_reason",finish);
        var message=choice.putObject("message").put("role","assistant").put("content",body);
        if (toolCall) message.putArray("tool_calls").addObject().put("id","local-tool");
        int outputTokens="length".equals(finish) ? 8192 : 456;
        response.putObject("usage").put("prompt_tokens",8900).put("completion_tokens",outputTokens).put("total_tokens",8900+outputTokens);
        byte[] bytes=JSON.writeValueAsBytes(response);
        exchange.sendResponseHeaders(200,bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @ParameterizedTest @ValueSource(strings={"EMPTY","V6_EMPTY","V7_EMPTY","V8_EMPTY","V9_EMPTY","V10_EMPTY","V11_EMPTY","V12_EMPTY", "V13_EMPTY", "V14_EMPTY", "V15_EMPTY", "V16_EMPTY", "V17_EMPTY", "V18_EMPTY", "V19_EMPTY", "V20_EMPTY","FILTERED","TOOL_CALLS","MISSING_MODEL","MISSING_FINISH","MIXED_TOOL","SUCCESS_WITHOUT_MODEL"})
    void measuredNonBusinessReceiptNeedsAttributableIdentityUsageAndFinish(String sample) throws Exception {
        String variant = List.of("V6_EMPTY", "V7_EMPTY", "V8_EMPTY", "V9_EMPTY", "V10_EMPTY", "V11_EMPTY", "V12_EMPTY", "V13_EMPTY", "V14_EMPTY", "V15_EMPTY", "V16_EMPTY", "V17_EMPTY", "V18_EMPTY", "V19_EMPTY", "V20_EMPTY").contains(sample) ? "EMPTY" : sample;
        var http=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        var actors=new CopyOnWriteArrayList<String>();
        http.createContext("/chat/completions",exchange->{
            try {
                var input=JSON.readTree(JSON.readTree(exchange.getRequestBody()).at("/messages/1/content").asText());
                String person=input.path("person").asText(); actors.add(person);
                String finish="stop",body="";
                if (person.equals("bob") || variant.equals("SUCCESS_WITHOUT_MODEL")) {
                    var answer=JSON.createObjectNode().put("tool","role.wait");
                    answer.putObject("decision").put("objective",input.at("/engineering/deliberation/objectives/0/id").asText())
                            .put("basis","等待本人复核").put("reviewAfterMillis",60_000);
                    body=answer.toString();
                } else finish=switch(variant) {
                    case "FILTERED" -> "content_filter";
                    case "TOOL_CALLS" -> "tool_calls";
                    case "MISSING_FINISH" -> null;
                    default -> "stop";
                };
                boolean tool=person.equals("alice") && (variant.equals("TOOL_CALLS") || variant.equals("MIXED_TOOL"));
                respond(exchange,finish,body,!variant.equals("MISSING_MODEL") && !variant.equals("SUCCESS_WITHOUT_MODEL"),tool);
            } finally { exchange.close(); }
        });
        http.start();
        try {
            var uri=URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/chat/completions");
            var provider=new LiveDecisionProvider(slot->new DeepSeekClient(()->"sk-fixture-aaaaaaaaaaaaaaaa",uri));
            var people=List.of("alice","bob");
            String mode=switch(sample) {
                case "V8_EMPTY" -> "TEN_COMPANY_MEETING_STUDY";
                case "V9_EMPTY" -> "TEN_COMPANY_DIGITAL_WIND_DOWN_STUDY";
                case "V10_EMPTY" -> "TEN_COMPANY_FEEDBACK_STUDY";
                case "V11_EMPTY" -> "TEN_COMPANY_PERSONAL_NEWS_STUDY";
                case "V12_EMPTY" -> "TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY";
                case "V13_EMPTY" -> "TEN_COMPANY_ISSUER_DECISIONS_STUDY";
                case "V14_EMPTY" -> "TEN_COMPANY_MEDIA_WITHDRAWAL_STUDY";
                case "V15_EMPTY" -> "TEN_COMPANY_LOAN_LOSS_STUDY";
                case "V16_EMPTY" -> "TEN_COMPANY_PERSONAL_BELIEF_STUDY";
                case "V17_EMPTY" -> "TEN_COMPANY_INTERVIEW_TIME_STUDY";
                case "V18_EMPTY" -> "TEN_COMPANY_DIVIDEND_AMENDMENT_STUDY";
                case "V20_EMPTY" -> "TEN_COMPANY_CRISIS_ARGUMENTS_STUDY";
                case "V19_EMPTY" -> "TEN_COMPANY_ONSITE_DECISION_CLOCK_STUDY";
                case "V7_EMPTY" -> "TEN_COMPANY_INTERVIEW_STUDY";
                case "V6_EMPTY" -> "TEN_COMPANY_PERSONAL_STUDY";
                case "EMPTY" -> "TEN_COMPANY_GOVERNANCE_STUDY";
                case "FILTERED" -> "TEN_COMPANY_STUDY";
                case "TOOL_CALLS" -> "TEN_COMPANY_EDITORIAL_STUDY";
                default -> "TEN_COMPANY_AUTONOMY";
            };
            // 十四版两名在岗基金职员在周一 09:00 开始接受普通邀请。
            long shiftStart = (sample.equals("V14_EMPTY") || sample.equals("V15_EMPTY") || sample.equals("V16_EMPTY") || sample.equals("V17_EMPTY") || sample.equals("V18_EMPTY") || sample.equals("V19_EMPTY") || sample.equals("V20_EMPTY")) ? 9L * 3_600_000L : 0L;
            var first=run(mode,"first",null,shiftStart+100,people,provider);
            if (!List.of("EMPTY","FILTERED","TOOL_CALLS").contains(variant)) {
                assertTrue(first.path("probeStop").asText().startsWith("PROBE_PROVIDER_"));
                assertEquals(List.of("alice"),actors);
                return;
            }
            String code="PROVIDER_"+variant;
            assertEquals("TARGET_REACHED",first.path("probeStop").asText());
            assertEquals(List.of("alice","bob"),actors);
            assertEquals(1,first.at("/providerProvenance/transport/failuresByCode/"+code).asInt());
            assertEquals(0,first.at("/providerProvenance/transport/consecutive").asInt());
            var seed=root.resolve("first");
            assertThrows(IllegalArgumentException.class,()->run(mode.equals("TEN_COMPANY_GOVERNANCE_STUDY") ? "TEN_COMPANY_STUDY" : "TEN_COMPANY_GOVERNANCE_STUDY",
                    "wrong-mode",seed,shiftStart+200,people,provider));
            var second=run(mode,"second",seed,shiftStart+200,people,provider);
            String runId=switch(sample) {
                case "V8_EMPTY" -> edu.cufe.auction.world.RunManifest.MEETING_TEN_COMPANY_RUN_ID;
                case "V9_EMPTY" -> edu.cufe.auction.world.RunManifest.DIGITAL_WIND_DOWN_RUN_ID;
                case "V10_EMPTY" -> edu.cufe.auction.world.RunManifest.EDITORIAL_FEEDBACK_RUN_ID;
                case "V11_EMPTY" -> edu.cufe.auction.world.RunManifest.PERSONAL_NEWS_RUN_ID;
                case "V12_EMPTY" -> edu.cufe.auction.world.RunManifest.INTERVIEW_AUTONOMY_RUN_ID;
                case "V13_EMPTY" -> edu.cufe.auction.world.RunManifest.ISSUER_DECISIONS_RUN_ID;
                case "V14_EMPTY" -> edu.cufe.auction.world.RunManifest.MEDIA_WITHDRAWAL_RUN_ID;
                case "V15_EMPTY" -> edu.cufe.auction.world.RunManifest.BANK_LOAN_LOSS_RUN_ID;
                case "V16_EMPTY" -> edu.cufe.auction.world.RunManifest.PERSONAL_BELIEF_RUN_ID;
                case "V17_EMPTY" -> edu.cufe.auction.world.RunManifest.INTERVIEW_TIME_RUN_ID;
                case "V18_EMPTY" -> edu.cufe.auction.world.RunManifest.DIVIDEND_AMENDMENT_RUN_ID;
                case "V20_EMPTY" -> edu.cufe.auction.world.RunManifest.CRISIS_ARGUMENTS_STUDY_RUN_ID;
                case "V19_EMPTY" -> edu.cufe.auction.world.RunManifest.ONSITE_DECISION_CLOCK_RUN_ID;
                case "V7_EMPTY" -> edu.cufe.auction.world.RunManifest.INTERVIEW_TEN_COMPANY_RUN_ID;
                case "V6_EMPTY" -> edu.cufe.auction.world.RunManifest.PERSONAL_TEN_COMPANY_RUN_ID;
                case "EMPTY" -> edu.cufe.auction.world.RunManifest.GOVERNANCE_TEN_COMPANY_RUN_ID;
                case "FILTERED" -> edu.cufe.auction.world.RunManifest.STUDY_TEN_COMPANY_AUTONOMY_RUN_ID;
                case "TOOL_CALLS" -> edu.cufe.auction.world.RunManifest.READABLE_DRAFT_TEN_COMPANY_RUN_ID;
                default -> edu.cufe.auction.world.RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID;
            };
            assertEquals(runId,second.at("/live/runId").asText());
            assertEquals("TARGET_REACHED",second.path("probeStop").asText());
            assertEquals(0,second.path("httpCalls").asInt());
            assertEquals(List.of("alice","bob"),actors);
            try (var world=AutonomousLifecycleProbe.openWorld(mode,root.resolve("second/world.journal"))) {
                var decisions=world.snapshot().decisions().orElseThrow();
                var alice=decisions.state().decisions().stream().filter(d->d.personPartyId().equals("alice")).findFirst().orElseThrow();
                var attempt=alice.lastAttempt();
                assertEquals("TECHNICAL_FAILED",alice.outcome().name()); assertEquals("",alice.recordedTool());
                assertNull(decisions.actionRef(alice.actionId()));
                assertTrue(attempt.replyReceived()); assertEquals("",attempt.replyBody()); assertEquals(code,attempt.reason());
                assertEquals(8900,attempt.declaredUsage().inputTokens()); assertEquals(456,attempt.declaredUsage().outputTokens());
                assertEquals(attempt.declaredUsage(),attempt.settled());
                assertEquals("WAIT",decisions.state().decisions().stream().filter(d->d.personPartyId().equals("bob")).findFirst().orElseThrow().outcome().name());
            }
            var policyFile=seed.resolve(ProbeProviderEvidence.POLICY_FILE);
            var policyBytes=Files.readAllBytes(policyFile);
            try {
                ProbeProviderEvidence.freezePolicy(seed,true,true,TargetTransportPolicy.LEGACY_VERSION);
                assertThrows(IllegalArgumentException.class,()->ProbeProviderEvidence.validateSeed(seed,first,true,true));
            } finally { Files.write(policyFile,policyBytes); }
        } finally { http.stop(0); }
    }

    @Test void authStopsEvenWithOtherCallsAvailable() throws Exception {
        var http=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        http.createContext("/chat/completions",exchange -> {
            try { exchange.sendResponseHeaders(401,-1); } finally { exchange.close(); }
        });
        http.start();
        try {
            var uri=URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/chat/completions");
            var provider=new LiveDecisionProvider(slot -> new DeepSeekClient(() -> "sk-fixture-aaaaaaaaaaaaaaaa",uri));
            var result=run("unauthorized",null,2_000,List.of("bob"),provider);
            assertEquals("PROBE_PROVIDER_TECHNICAL_FAILURE:HTTP_AUTH",result.path("probeStop").asText());
            assertEquals(1,result.path("httpCalls").asInt());
            assertThrows(IllegalArgumentException.class,() -> run("wrong",root.resolve("unauthorized"),3_000,List.of("bob"),provider));
        } finally { http.stop(0); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void consecutiveUnusableResponsesStopBeforeTheWorldTarget(boolean mixMeasuredAndTransport) throws Exception {
        Path out=root.resolve("outage"), cfg=root.resolve("outage.json");
        var people=List.of("alice","bob");
        JSON.writeValue(cfg.toFile(),new AutonomousLifecycleProbe.Config("TEN_COMPANY_AUTONOMY",0,0,90_000L,
                3_600_000L,null,"transport-fixture",people));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var http=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        http.createContext("/chat/completions", exchange -> {
            int index=count.incrementAndGet();
            try {
                if (mixMeasuredAndTransport && index%4==0) respond(exchange,"length","",true,false);
                else if (mixMeasuredAndTransport && index%2==0) respond(exchange,"stop","",true,false);
                else exchange.sendResponseHeaders(503,-1);
            } finally { exchange.close(); }
        });
        http.start();
        try {
            var uri=URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/chat/completions");
            AutonomousLifecycleProbe.run(out,cfg,() -> {
                try (var w=ComposedIndustryScenario.openTenCompanyAutonomy(out.resolve("world.journal"))) {
                    for (int n=1;n<=6;n++) {
                        String id="observable-change-"+n;
                        w.schedulePublishDocument(id,id+"-work",10_000L*n,id,"dave",
                                edu.cufe.auction.world.InformationModel.Audience.publicAudience(),"报表 "+n,"受控新增资料","测试",10,List.of());
                    }
                } catch (Exception failure) { throw new java.io.IOException(failure); }
                return new LiveDecisionProvider(slot -> new DeepSeekClient(() -> "sk-fixture-aaaaaaaaaaaaaaaa",uri));
            });
            var result=JSON.readTree(out.resolve("result.json").toFile());
            assertEquals(TargetTransportPolicy.STOP,result.path("probeStop").asText());
            assertEquals(8,count.get());
            if (mixMeasuredAndTransport) {
                assertEquals(2,result.at("/providerProvenance/transport/failuresByCode/PROVIDER_EMPTY").asInt());
                assertEquals(2,result.at("/providerProvenance/transport/failuresByCode/PROVIDER_LENGTH").asInt());
                assertEquals(4,result.at("/providerProvenance/transport/failuresByCode/HTTP_SERVICE_UNAVAILABLE").asInt());
            }
            assertEquals(8,result.at("/providerProvenance/transport/consecutive").asInt());
            assertTrue(result.at("/autonomy/timeMillis").asLong()<90_000);
        } finally { http.stop(0); }
    }

    @Test void persistentOutageUsesDispatchOrderAndCarriesPriorConsecutiveFailures() {
        var policy=new TargetTransportPolicy(5);
        policy.record(2,true,"HTTP_RATE_LIMITED");
        policy.record(3,true,"HTTP_TIMEOUT");
        assertFalse(policy.tripped());
        policy.record(1,true,"HTTP_NETWORK");
        assertTrue(policy.tripped());
        assertEquals(8,policy.summary().path("consecutive").asInt());
        var recovered=new TargetTransportPolicy(6);
        recovered.record(2,false,null);
        recovered.record(1,true,"HTTP_SERVICE_UNAVAILABLE");
        recovered.record(3,true,"HTTP_RATE_LIMITED");
        assertEquals(1,recovered.summary().path("consecutive").asInt());
        assertFalse(recovered.tripped());
    }
}
