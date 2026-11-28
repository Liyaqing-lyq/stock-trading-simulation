package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Host/HTTP/完成槽：已收回执不能因writer侧stop等待而越过接收截止。 */
class ThinkingProviderHostReceiptTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void paidTechnicalRepliesArriveBeforeDeadlineWhileRealStopWaitsForHeldWriter() throws Exception {
        var nanos = new AtomicLong();
        var registrations = new CountDownLatch(2);
        var requests = new CountDownLatch(2);
        var releaseHttp = new CountDownLatch(1);
        var writerHeld = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var stopEntered = new CountDownLatch(2);
        var stopFinished = new CountDownLatch(2);
        var httpCalls = new AtomicInteger();
        var issued = new AtomicInteger();
        var calls = new ConcurrentLinkedQueue<DecisionRun.Call>();
        var hostRef = new AtomicReference<WorldWebHost>();
        var runtimeRef = new AtomicReference<WorldRuntime>();
        var control = Executors.newSingleThreadExecutor();
        var httpWorkers = Executors.newCachedThreadPool();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        server.setExecutor(httpWorkers);
        server.createContext("/chat/completions", exchange -> {
            httpCalls.incrementAndGet(); exchange.getRequestBody().readAllBytes(); requests.countDown();
            try { if (!releaseHttp.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("HTTP fixture release timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":11,\"total_tokens\":18}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        Path journal=dir.resolve("world.journal");
        List<DecisionModel.Decision> confirmed;
        List<WorldRuntime.DecisionAudit> confirmedAudits;
        try (var routes=SceneRoutes.open(dir)) {
            routes.replace(0,AutonomousLifecycleProbe.probeRoutes(routes.view(),"THINKING_INFORMED_OPERATING"));
            var evidence=new ProbeProviderEvidence(dir,true);ProbeProviderEvidence.freezePolicy(dir,true);
            var config=new AutonomousLifecycleProbe.Config("THINKING_INFORMED_OPERATING",20,1,86400000,3600000,null,"fixture-source");
            URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions");
            var provider=new LiveDecisionProvider(slot->new DeepSeekClient(()->"sk-fixture-aaaaaaaaaaaaaaaa",uri));
            try (var host=new WorldWebHost(()->{
                var runtime=openWithClock(journal,nanos,registrations);runtimeRef.set(runtime);
                return new WorldWebHost.Opened(runtime,runtime.profile().manifest().runId());
            },routes,(call,timeout)->{
                calls.add(call);
                return AutonomousLifecycleProbe.evaluateEvidence(config,evidence,issued,call,timeout,provider,()->{
                    stopEntered.countDown();
                    try { hostRef.get().stopAutonomy(); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    finally { stopFinished.countDown(); }
                });
            })) {
                hostRef.set(host);
                var status=host.autonomyStatus();host.startAutonomy(Long.parseLong(status.position()),routes.view().revision(),86400000,3600000);
                assertTrue(requests.await(10,TimeUnit.SECONDS),"Two real HTTP calls must already be in flight");
                Future<?> held=control.submit(()->{try {host.call(()->{writerHeld.countDown();if(!releaseWriter.await(10,TimeUnit.SECONDS))throw new IllegalStateException("writer release timed out");return null;});}catch(Exception failure){throw new IllegalStateException(failure);}});
                assertTrue(writerHeld.await(5,TimeUnit.SECONDS));
                nanos.set(TimeUnit.MILLISECONDS.toNanos(59_000));releaseHttp.countDown();
                assertTrue(stopEntered.await(5,TimeUnit.SECONDS),"Both paid HTTP replies are received and real stop callbacks have entered before D");
                assertTrue(evidence.stopRequested());assertEquals(2,httpCalls.get());assertEquals(2,issued.get());
                // Exercise the same immediate HTTP gate while writer-side stop is still blocked.
                var blocked=AutonomousLifecycleProbe.evaluateEvidence(config,evidence,issued,calls.peek(),5000,provider,()->fail("a stopped callback must not schedule another stop"));
                assertEquals("PROBE_EVIDENCE_STOP",blocked.failureCode());assertEquals(2,issued.get());assertEquals(2,httpCalls.get());
                boolean registeredBeforeDeadline=registrations.await(2,TimeUnit.SECONDS);
                // D=60000ms from injected dispatch clock0; release the actual writer strictly after D.
                nanos.set(TimeUnit.MILLISECONDS.toNanos(61_000));releaseWriter.countDown();held.get(5,TimeUnit.SECONDS);
                assertTrue(stopFinished.await(5,TimeUnit.SECONDS));
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(host.liveStatus().physicalInFlight()>0 && System.nanoTime()<end) Thread.onSpinWait();
                host.call(()->{runtimeRef.get().drainDecisionCallbacks();return null;});
                assertEquals(0,host.liveStatus().physicalInFlight());assertFalse(host.autonomyStatus().running());
                confirmed=host.call(()->runtimeRef.get().snapshot().decisions().orElseThrow().state().decisions());
                confirmedAudits=host.call(()->runtimeRef.get().decisionAudits());
                var paidCalls=List.of(JSON.readValue(dir.resolve("call-1.json").toFile(),DecisionRun.Call.class),
                        JSON.readValue(dir.resolve("call-2.json").toFile(),DecisionRun.Call.class));
                var paidIdentities=paidCalls.stream().map(DecisionRun.Call::attemptId).collect(java.util.stream.Collectors.toSet());
                assertEquals(2,paidIdentities.size());
                for(var call:paidCalls) {
                    var decision=confirmed.stream().filter(d->d.actionId().equals(call.actionId())).findFirst().orElseThrow();
                    var original=decision.attempts().stream().filter(a->a.attemptId().equals(call.attemptId())).findFirst().orElseThrow();
                    assertEquals(DecisionModel.Usage.unknownCost(1,7,11),original.declaredUsage(),"Paid declaration must be retained on the original timely attempt");
                    assertEquals(DecisionModel.Usage.unknownCost(1,7,11),original.settled());
                    assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED,original.outcome());assertEquals("HTTP_SERVICE_UNAVAILABLE",original.reason());
                    assertTrue(confirmedAudits.stream().anyMatch(a->a.attemptId().equals(call.attemptId())&&a.kind().equals("REPLY")&&a.outcome().equals("TECHNICAL_FAILED:HTTP_SERVICE_UNAVAILABLE")&&a.declaredUsage()!=null&&a.receivedAtEpochMillis()==159_000L));
                    assertTrue(confirmedAudits.stream().noneMatch(a->a.attemptId().equals(call.attemptId())&&(a.kind().equals("TIMEOUT")||a.kind().equals("REJECTED_IDENTITY_REUSE"))));
                }
                // A writer pump may dispatch free guard-only callbacks before its queued stop.
                // They are not paid calls and must retain their own unknown-usage classification.
                for(var call:calls) if(!paidIdentities.contains(call.attemptId())) {
                    var decision=confirmed.stream().filter(d->d.actionId().equals(call.actionId())).findFirst().orElseThrow();
                    var guard=decision.attempts().stream().filter(a->a.attemptId().equals(call.attemptId())).findFirst().orElseThrow();
                    assertTrue(guard.settledDone());assertNull(guard.declaredUsage());assertEquals(guard.reserved(),guard.settled());
                    assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED,guard.outcome());assertEquals("UNKNOWN_USAGE",guard.reason());
                    assertTrue(confirmedAudits.stream().anyMatch(a->a.attemptId().equals(call.attemptId())&&a.outcome().equals("TECHNICAL_FAILED:PROBE_EVIDENCE_STOP")));
                }
                assertTrue(registeredBeforeDeadline,"Host inbox must receive results while writer is held, before D");
                assertEquals(2,httpCalls.get());assertEquals(2,issued.get());assertFalse(Files.exists(dir.resolve("call-3.json")));
                assertTrue(evidence.summary(2,0).path("evidenceComplete").asBoolean());
            }
            try(var reopened=ComposedIndustryScenario.openThinkingInformedOperating(journal)) {
                assertEquals(confirmed,reopened.snapshot().decisions().orElseThrow().state().decisions());
                assertEquals(confirmedAudits,reopened.decisionAudits());
            }
        } finally {releaseHttp.countDown();releaseWriter.countDown();server.stop(0);httpWorkers.shutdownNow();control.shutdownNow();}
    }

    private static WorldRuntime openWithClock(Path journal,AtomicLong nanos,CountDownLatch registrations) throws Exception {
        BranchProfile p;
        try(var initial=ComposedIndustryScenario.openThinkingInformedOperating(journal)){p=initial.profile();}
        var constructor=LiveCompletionInbox.class.getDeclaredConstructor(int.class,LongSupplier.class,LongSupplier.class);constructor.setAccessible(true);
        LongSupplier clock=()->{
            long value=nanos.get();
            if(Thread.currentThread().getName().equals("world-web-live-worker"))registrations.countDown();
            return value;
        };
        var inbox=constructor.newInstance(2,clock,(LongSupplier)()->100_000+TimeUnit.NANOSECONDS.toMillis(nanos.get()));
        return new WorldRuntime(journal,p.manifest(),p.openings(),p.registry(),p.manufacturing(),p.charter(),p.roles(),p.information(),p.decisions(),inbox,p.media(),p.interview(),p.dialogue(),p.evidence(),p.social(),p.socialBlockMillis(),p.contacts(),p.engineering(),p.groups(),p.platform(),p.feedLimit(),p.readMillis(),p.companyCharters(),p.bankOpening(),p.manufacturingFunding());
    }
}
