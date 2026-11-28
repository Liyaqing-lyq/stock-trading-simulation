package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TargetDrivenProbeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODE = "TEN_COMPANY_AUTONOMY";
    @TempDir Path root;

    private static AutonomousLifecycleProbe.Config config(Path seed, long target, List<String> people) {
        return new AutonomousLifecycleProbe.Config(MODE,0,0,target,3_600_000L,
                seed == null ? null : seed.toString(),"frozen-source",people);
    }

    @Test void checkpointInFlightResumesSamePersonsClockWithoutSendingTheirDecisionAgain() throws Exception {
        Path first = root.resolve("first"), second = root.resolve("second"), cfg = root.resolve("config.json");
        var people = List.of("investor");
        var calls = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        http.createContext("/chat/completions", exchange -> {
            try {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                var visible = JSON.readTree(request.at("/messages/1/content").asText());
                int number = calls.incrementAndGet();
                if (number == 1) {
                    Files.writeString(first.resolve("CHECKPOINT"),"保存并可接续");
                    long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (System.nanoTime() < timeout) {
                        Path state = first.resolve("status.json");
                        if (Files.isRegularFile(state)) {
                            try {
                                var item = JSON.readTree(state.toFile());
                                if (item != null && "STOPPED".equals(item.path("reason").asText())) break;
                            } catch (java.io.IOException writing) { /* 状态文件可能正在更新 */ }
                        }
                        try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                }
                var answer = JSON.createObjectNode().put("tool","role.wait");
                answer.putObject("args");
                answer.putObject("decision").put("objective",visible.at("/engineering/deliberation/objectives/0/id").asText())
                        .put("basis","按个人约定的时刻重新查看").put("reviewAfterMillis",60_000);
                var body = JSON.createObjectNode().put("id","local-test-"+number).put("model","deepseek-flash");
                body.putArray("choices").addObject().put("index",0).put("finish_reason","stop")
                        .putObject("message").put("role","assistant").put("content",answer.toString());
                body.putObject("usage").put("prompt_tokens",70).put("completion_tokens",60).put("total_tokens",130);
                byte[] bytes = JSON.writeValueAsBytes(body);
                exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        http.start();
        try {
            var uri = URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/chat/completions");
            var provider = new LiveDecisionProvider(slot -> new DeepSeekClient(() -> "sk-fixture-aaaaaaaaaaaaaaaa",uri));
            JSON.writeValue(cfg.toFile(),config(null,30_000,people));
            AutonomousLifecycleProbe.run(first,cfg,() -> provider);
            var stopped = JSON.readTree(first.resolve("result.json").toFile());
            assertEquals("SAFE_CHECKPOINT",stopped.path("probeStop").asText(),
                    JSON.readTree(first.resolve("provider-1.json").toFile()).path("failureCode").asText());
            assertEquals(1,stopped.path("httpCalls").asInt());
            assertEquals(0,stopped.at("/live/completionSlots").asInt(-1));
            assertTrue(stopped.at("/providerProvenance/evidenceComplete").asBoolean());
            assertTrue(Files.isRegularFile(first.resolve(MeasuredLengthEvidence.FILE)));
            var other = config(first,30_000,List.of("bob"));
            assertThrows(IllegalArgumentException.class,() -> AutonomousLifecycleProbe.validateSeed(other));
            JSON.writeValue(cfg.toFile(),config(first,30_000,people));
            AutonomousLifecycleProbe.run(second,cfg,() -> provider);
            var reached = JSON.readTree(second.resolve("result.json").toFile());
            assertEquals("TARGET_REACHED",reached.path("probeStop").asText(),reached.path("autonomy").toString());
            assertEquals(0,reached.path("httpCalls").asInt(-1));
            assertEquals(30_000,reached.at("/autonomy/timeMillis").asLong());
            assertEquals(1,calls.get());
            assertDoesNotThrow(() -> AutonomousLifecycleProbe.validateSeed(config(second,31_000,people)));
            assertThrows(IllegalArgumentException.class,() -> AutonomousLifecycleProbe.validateSeed(config(second,30_000,people)));
        } finally { http.stop(0); }
    }

    @Test void roleInputTransitionsAreSealedWhileOnlyActualProviderCallsCount() throws Exception {
        Path out = root.resolve("audit"), cfg = root.resolve("params.json");
        var people = List.of("alice","bob");
        JSON.writeValue(cfg.toFile(),config(null,15_000,people));
        var paid = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        http.createContext("/chat/completions", exchange -> {
            try {
                var input = JSON.readTree(JSON.readTree(exchange.getRequestBody()).at("/messages/1/content").asText());
                String owner = input.path("person").asText();
                assertFalse(input.toString().contains(AutonomousLifecycleProbe.INPUT_EVENTS));
                paid.add(owner);
                var answer = JSON.createObjectNode().put("tool","role.wait");
                answer.putObject("decision").put("objective",input.at("/engineering/deliberation/objectives/0/id").asText())
                        .put("basis","等待本人复核").put("reviewAfterMillis",60_000);
                var body = JSON.createObjectNode().put("id","local-"+paid.size()).put("model","deepseek-flash");
                body.putArray("choices").addObject().put("index",0).put("finish_reason","stop")
                        .putObject("message").put("role","assistant").put("content",answer.toString());
                body.putObject("usage").put("prompt_tokens",40).put("completion_tokens",30).put("total_tokens",70);
                byte[] bytes = JSON.writeValueAsBytes(body);
                exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        http.start();
        try {
            var uri = URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/chat/completions");
            AutonomousLifecycleProbe.run(out,cfg,() -> {
                try (var w = edu.cufe.auction.world.composed.ComposedIndustryScenario.openTenCompanyAutonomy(out.resolve("world.journal"))) {
                    w.schedulePublishDocument("post","post-task",2,"private","dave",edu.cufe.auction.world.InformationModel.Audience.direct("alice"),
                            "长".repeat(92_000),"正文","情境",20,List.of());
                    w.advanceTo(3);
                    w.schedulePublishDocumentVersion("longer","longer-task",7_000,"private","长".repeat(94_000),"正文","情境",20,List.of());
                    w.schedulePublishDocumentVersion("smaller","smaller-task",12_000,"private","长".repeat(34_000),"正文","情境",20,List.of());
                } catch (Exception failure) { throw new java.io.IOException(failure); }
                return new LiveDecisionProvider(slot -> new DeepSeekClient(() -> "sk-fixture-aaaaaaaaaaaaaaaa",uri));
            });
            var result = JSON.readTree(out.resolve("result.json").toFile());
            assertEquals("TARGET_REACHED",result.path("probeStop").asText());
            assertEquals(List.of("bob","alice"),paid);
            assertEquals(paid.size(),result.path("httpCalls").intValue());
            var events = new java.util.ArrayList<JsonNode>();
            for (String line : Files.readAllLines(out.resolve(AutonomousLifecycleProbe.INPUT_EVENTS))) events.add(JSON.readTree(line));
            assertEquals(events.size(),result.at("/localInputAudit/events").intValue());
            assertEquals(0,result.at("/localInputAudit/providerCalls").intValue());
            assertTrue(events.stream().allMatch(row -> row.path("person").asText().equals("alice") && row.size()==6));
            assertEquals("BLOCKED",events.get(0).path("transition").asText());
            assertTrue(events.stream().anyMatch(row -> "SIZE_CHANGED".equals(row.path("transition").asText())
                    && row.path("timeMillis").asLong()==7_000));
            assertEquals("RECOVERED",events.get(events.size()-1).path("transition").asText());
            assertEquals(12_000,events.get(events.size()-1).path("timeMillis").asLong());
            var world = Files.copy(out.resolve("world.journal"),root.resolve("restored.journal"));
            String snapshot = Files.readString(out.resolve(MeasuredLengthEvidence.FILE));
            Path eventsFile = out.resolve(AutonomousLifecycleProbe.INPUT_EVENTS);
            var bytes = Files.readAllBytes(eventsFile);
            Files.writeString(eventsFile,"{}",java.nio.file.StandardOpenOption.APPEND);
            try (var w=edu.cufe.auction.world.composed.ComposedIndustryScenario.openTenCompanyAutonomy(world)) {
                var policy=new MeasuredLengthPolicy(() -> w.snapshot().decisions().orElseThrow());
                assertThrows(IllegalArgumentException.class,() -> policy.restore(out));
                Files.write(eventsFile,bytes);
                assertDoesNotThrow(() -> policy.restore(out));
            }
            assertEquals(snapshot,Files.readString(out.resolve(MeasuredLengthEvidence.FILE)));
        } finally { http.stop(0); }
    }

    @Test void operatorStopBeforeAdmissionDoesNotSendOrBecomeAnAutomaticContinuation() throws Exception {
        Path out = root.resolve("stopped"), cfg = root.resolve("params.json");
        var people = List.of("investor");
        JSON.writeValue(cfg.toFile(),config(null,30_000,people));
        AutonomousLifecycleProbe.run(out,cfg,() -> {
            Files.writeString(out.resolve("STOP"),"停止");
            return new LiveDecisionProvider(slot -> { throw new AssertionError("收到 STOP 后不得调用提供方"); });
        });
        var result = JSON.readTree(out.resolve("result.json").toFile());
        assertEquals("OPERATOR_STOP",result.path("probeStop").asText());
        assertEquals(0,result.path("httpCalls").asInt(-1));
        assertThrows(IllegalArgumentException.class,() -> AutonomousLifecycleProbe.validateSeed(config(out,30_000,people)));
    }

    @Test void targetModeRequiresExplicitBothZeroAndNormalTenCompany() {
        assertTrue(config(null,90L*86_400_000,null).runUntilTarget());
        assertTrue(config(null,180L*86_400_000,null).callLimit()>3000);
        assertThrows(IllegalArgumentException.class,() -> new AutonomousLifecycleProbe.Config(MODE,1,0,1,3_600_000,null,"s"));
        assertThrows(IllegalArgumentException.class,() -> new AutonomousLifecycleProbe.Config("PERSON_PAUSE",0,0,1,3_600_000,null,"s"));
    }
}
