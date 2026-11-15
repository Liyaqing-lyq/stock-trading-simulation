package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 注入意图核对事件调度，不将脚本中的选择称为真实模型自主行为。 */
class WorldEventAutonomyTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();

    @Test void readingCompletionAndTwoCompanyMeetingsWakeOnlyAffectedPeopleBeforeDeadline() throws Exception {
        var counts = new ConcurrentHashMap<String, AtomicInteger>();
        try (var routes = SceneRoutes.open(dir)) {
            routes.replace(routes.view().revision(), routes.view().routes().stream().map(r ->
                    new SceneRoutes.Route(r.scene(), r.slotId(), r.model(), true, 100, 1_000_000, 1024, 4)).toList());
            try (var host = new WorldWebHost(() -> {
                var world = ComposedIndustryScenario.openEventAutonomous(dir.resolve("events.journal"));
                return new WorldWebHost.Opened(world, world.profile().manifest().runId());
            }, routes, (call, timeout) -> {
                var input = assertDoesNotThrow(() -> json.readTree(call.inputText()));
                String person = input.path("person").asText();
                counts.computeIfAbsent(person, k -> new AtomicInteger()).incrementAndGet();
                String tool = "role.wait";
                var args = json.createObjectNode();
                for (var director : input.path("engineering").path("directorships")) {
                    String company = director.path("companyPartyId").asText();
                    if (!List.of("mfg-struct", "mfg-tech").contains(company)) continue;
                    assertTrue(director.path("managerVacant").asBoolean());
                    assertTrue(director.path("authorizedBudgets").isEmpty());
                    if (director.path("chair").asBoolean() && director.path("meetings").isEmpty()) {
                        if (input.path("profile").path("readExperiences").isEmpty()) {
                            var doc = input.path("profile").path("catalog").get(0);
                            tool = "role.reading.start";
                            args.put("docId", doc.path("docId").asText()).put("version", doc.path("version").asLong());
                        } else {
                            tool = "board.convene";
                            long now = input.path("time").asLong();
                            assertTrue(now < 60_000L, "完成阅读后不能等一小时才获得下一次机会");
                            args.put("companyPartyId", company).put("meetingId", "meeting-" + company)
                                    .put("topic", "BUDGET").put("materialDeadline", Long.toString(now + 100_000))
                                    .put("opensAt", Long.toString(now + 120_000)).put("closesAt", Long.toString(now + 240_000));
                        }
                        break;
                    }
                    for (var meeting : director.path("meetings")) {
                        if (!meeting.path("attended").asBoolean()) {
                            tool = "board.attend";
                            args.put("companyPartyId", company).put("meetingId", meeting.path("meetingId").asText());
                            break;
                        }
                    }
                }
                var reply = json.createObjectNode().put("tool", tool);
                if (!"role.wait".equals(tool)) reply.set("args", args);
                return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(), call.actionId(),
                        call.attemptId(), call.attemptNo(), call.recoveryGeneration(), reply.toString(),
                        DecisionModel.Usage.unknownCost(1, 10, 20), "TEST"));
            })) {
                var initial = host.autonomyStatus();
                host.startAutonomy(Long.parseLong(initial.position()), routes.view().revision(), 180_000L, 3_600_000L);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (host.autonomyStatus().running() && System.nanoTime() < deadline) Thread.sleep(25);
                var status = host.autonomyStatus();
                assertEquals("TARGET_REACHED", status.reason(), host.liveStatus().decisions().toString());
                assertEquals(0, status.failures());
                assertEquals(10, status.companies());
                assertEquals(status.persons(), counts.size());
                assertEquals(1, counts.get("mfgversa-d1").get(), "别家会议不能无故唤醒第三家制造公司的董事");
                for (String company : List.of("mfg-struct", "mfg-tech")) {
                    Map<String, Object> meeting = host.engineeringView(company).meetings().rows().get(0);
                    assertEquals(5, meeting.get("attendees").toString().split(",").length, meeting.toString());
                    assertEquals("MATERIALS_FROZEN", meeting.get("status"));
                    assertEquals("", meeting.get("ballots"), "调度不得代投票或自动通过议案");
                }
            }
        }
        try (var recovered = ComposedIndustryScenario.openEventAutonomous(dir.resolve("events.journal"))) {
            assertEquals(180_000L, recovered.snapshot().timeMillis());
            assertEquals("role-events-v2", recovered.profile().manifest().dataVersions().get("autonomy-driver"));
        }
    }

    @Test void unchangedRolesAreNotPolledEachHourAndRestartDoesNotRepeatPaidObservation() throws Exception {
        var calls = new AtomicInteger();
        for (int restart = 0; restart < 2; restart++) {
            try (var routes = SceneRoutes.open(dir)) {
                if (restart == 0) routes.replace(routes.view().revision(), routes.view().routes().stream().map(r ->
                        new SceneRoutes.Route(r.scene(), r.slotId(), r.model(), true, 100, 1_000_000, 1024, 4)).toList());
                try (var host = new WorldWebHost(() -> {
                    var world = ComposedIndustryScenario.openEventAutonomous(dir.resolve("idle.journal"));
                    return new WorldWebHost.Opened(world, world.profile().manifest().runId());
                }, routes, (call, timeout) -> {
                    calls.incrementAndGet();
                    return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(), call.actionId(),
                            call.attemptId(), call.attemptNo(), call.recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                            DecisionModel.Usage.unknownCost(1, 10, 6), "TEST"));
                })) {
                    var before = host.autonomyStatus();
                    assertEquals("NOT_STARTED", before.reason());
                    host.startAutonomy(Long.parseLong(before.position()), routes.view().revision(),
                            (restart + 2L) * 3_600_000L, 3_600_000L);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (host.autonomyStatus().running() && System.nanoTime() < deadline) Thread.sleep(25);
                    assertEquals("TARGET_REACHED", host.autonomyStatus().reason());
                    assertEquals(host.autonomyStatus().persons(), calls.get(), "两小时空闲及重启续跑不能重复调用全部人物");
                    assertEquals(restart == 0 ? calls.get() : 0, host.autonomyStatus().calls());
                }
            }
        }
    }
}
