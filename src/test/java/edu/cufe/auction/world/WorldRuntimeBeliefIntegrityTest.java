package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 独立核对正常请求的重复语义与有合法帧摘要的伪造历史。 */
class WorldRuntimeBeliefIntegrityTest {
    @Test
    void unauthorizedReportCannotBeRewrittenAsAccepted() throws Exception {
        Path dir = directory();
        Path source = dir.resolve("world.journal");
        try (var world = WorldWebScenario.openBelief(source).runtime()) {
            world.openRoleSession("bob").reportOrders("report", "report-task", 10,
                    new WorldRoleSession.ReportDraft(WorldWebScenario.COMPANY, 100));
            world.advanceTo(11);
            assertTrue(world.snapshot().trading().orElseThrow().reports().values().stream()
                    .anyMatch(report -> report.reason().equals("NOT_AUTHORIZED") && !report.accepted()));
        }
        int[] changed = {0};
        Path forged = rewrite(source, dir.resolve("forged.journal"), root -> {
            for (var report : root.path("trading").path("reports")) {
                if (report.path("kind").asText().equals(PendingTask.ROLE_TRADING_COMPANY_REPORT_V1)) {
                    ((ObjectNode) report).put("accepted", true).put("reason", "SCHEDULED");
                    changed[0]++;
                }
            }
        });
        assertTrue(changed[0] > 0);
        assertThrows(IOException.class, () -> {
            try (var ignored = WorldWebScenario.openBelief(forged).runtime()) { }
        }, "恢复不能把实际无权发布的业务拒绝改成成功受理");
    }

    @Test
    void acceptedReportCannotLoseItsPendingCapture() throws Exception {
        missingFactTaskIsRejected(11, PendingTask.ROLE_TRADING_COMPANY_FACT_CAPTURE_V1);
    }

    @Test
    void capturedFactCannotLoseItsPendingPublication() throws Exception {
        missingFactTaskIsRejected(100, PendingTask.INFORMATION_BELIEF_PUBLISH_FACT_V1);
    }

    private void missingFactTaskIsRejected(long end, String kind) throws Exception {
        Path dir = directory();
        Path source = dir.resolve("world.journal");
        try (var world = WorldWebScenario.openBelief(source).runtime()) {
            world.openRoleSession("alice").reportOrders("report", "report-task", 10,
                    new WorldRoleSession.ReportDraft(WorldWebScenario.COMPANY, 100));
            world.advanceTo(end);
        }
        int[] changed = {0};
        Path forged = rewrite(source, dir.resolve("forged.journal"), root -> {
            var tasks = root.path("pending").elements();
            while (tasks.hasNext()) {
                if (tasks.next().path("kind").asText().equals(kind)) {
                    tasks.remove();
                    changed[0]++;
                }
            }
        });
        assertTrue(changed[0] > 0);
        assertThrows(IOException.class, () -> {
            try (var ignored = WorldWebScenario.openBelief(forged).runtime()) { }
        }, "已受理报告和已捕获事实不得静默丢失后续任务");
    }

    @Test
    void extraAuditCopyCannotReplaceThePreviouslyScheduledForecast() throws Exception {
        Path dir = directory();
        Path source = dir.resolve("world.journal");
        try (var world = WorldWebScenario.openBelief(source).runtime()) {
            world.openRoleSession("bob").publishForecast("publish", "publish-task", 10,
                    new WorldRoleSession.ForecastDraft("forecast", "document", "预测", "至少一单",
                            "本人预测", 1, WorldWebScenario.COMPANY, BeliefModel.Comparator.AT_LEAST, 1, 100));
            world.advanceTo(11);
        }
        try (var world = WorldWebScenario.openBelief(source).runtime()) {
            assertEquals(1, world.beliefAudit("bob").orElseThrow().sourceClaims().get(0).threshold());
        }
        int[] copies = {0};
        var json = new ObjectMapper();
        Path forged = rewriteAll(source, dir.resolve("forged.journal"), (type, root) -> {
            if (type.equals("STATE_SNAPSHOT_V1")) {
                for (var claim : root.path("beliefs").path("sourceClaims")) {
                    ((ObjectNode) claim).put("threshold", 2);
                }
            } else if (type.equals("BELIEF_TASK_V1")) {
                try {
                    var payload = (ObjectNode) json.readTree(root.path("payload").asText());
                    var draft = (ObjectNode) json.readTree(payload.path("body").asText());
                    draft.put("threshold", 2);
                    payload.put("body", json.writeValueAsString(draft));
                    root.put("payload", json.writeValueAsString(payload));
                    copies[0]++;
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
        });
        assertEquals(1, copies[0]);
        assertThrows(IOException.class, () -> {
            try (var ignored = WorldWebScenario.openBelief(forged).runtime()) { }
        }, "审计副本必须绑定上一帧真实待办，不能与伪造快照互相证明");
    }

    @Test
    void rejectedDuplicateForecastIsBusinessFailureAndDoesNotStopWorld() throws Exception {
        Path path = directory().resolve("world.journal");
        try (var world = WorldWebScenario.openBelief(path).runtime()) {
            var draft = new WorldRoleSession.ForecastDraft("forecast", "document", "预测", "至少一单",
                    "本人预测", 1, WorldWebScenario.COMPANY, BeliefModel.Comparator.AT_LEAST, 1, 100);
            var person = world.openRoleSession("bob");
            person.publishForecast("first", "first-task", 10, draft);
            person.publishForecast("second", "second-task", 11, draft);
            assertDoesNotThrow(() -> world.advanceTo(12), "重复发布应有失败回报，不能使提交校验停机");
            assertEquals(1, world.beliefAudit("bob").orElseThrow().sourceClaims().size());
        }
        try (var world = WorldWebScenario.openBelief(path).runtime()) {
            assertEquals(1, world.beliefAudit("bob").orElseThrow().sourceClaims().size());
        }
    }

    @Test
    void reportCapturesSameMillisecondContractsButNotNextMillisecondAfterRecovery() throws Exception {
        Path path = directory().resolve("world.journal");
        long capturePosition;
        try (var world = WorldWebScenario.openBelief(path).runtime()) {
            world.scheduleSalesContract("contract-at-cut", "contract-at-cut-task", 100, "company",
                    "contract-1", 1, new java.math.BigDecimal("10.00"), 1000);
            world.scheduleSalesContract("contract-after-cut", "contract-after-cut-task", 101, "company",
                    "contract-2", 1, new java.math.BigDecimal("10.00"), 1000);
            world.openRoleSession(WorldWebScenario.CHAIR).reportOrders("report", "report-task", 10,
                    new WorldRoleSession.ReportDraft(WorldWebScenario.COMPANY, 100));
            world.advanceTo(100);
            assertEquals(1, world.snapshot().manufacturing().orElseThrow().ledger().contracts().size());
            assertTrue(world.beliefAudit("alice").orElseThrow().factClaims().isEmpty(), "捕获不等于已发布");
            capturePosition = world.snapshot().position();
        }
        try (var world = WorldWebScenario.openBelief(path).runtime()) {
            world.advanceTo(101);
            assertEquals(2, world.snapshot().manufacturing().orElseThrow().ledger().contracts().size());
            var fact = world.beliefAudit("alice").orElseThrow().factClaims().get(0);
            assertEquals(1, fact.value(), "下一毫秒实际签下的第二份合同不得混入截止事实");
            assertEquals(100, fact.asOfMillis());
            assertEquals(capturePosition, fact.frozenPosition());
        }
    }

    @Test
    void duplicateReportsCannotStopWorldOrPublishTwoInitialFacts() throws Exception {
        Path path = directory().resolve("world.journal");
        try (var world = WorldWebScenario.openBelief(path).runtime()) {
            var chair = world.openRoleSession(WorldWebScenario.CHAIR);
            var draft = new WorldRoleSession.ReportDraft(WorldWebScenario.COMPANY, 100);
            chair.reportOrders("first", "first-task", 10, draft);
            chair.reportOrders("second", "second-task", 11, draft);
            assertDoesNotThrow(() -> world.advanceTo(102), "重复业务请求必须明确拒绝，不能中断世界");
            var audit = world.beliefAudit(WorldWebScenario.CHAIR).orElseThrow();
            assertEquals(1, audit.factClaims().size());
            assertEquals(0, audit.factClaims().get(0).value());
            assertDoesNotThrow(() -> world.advanceTo(200));
        }
        try (var restored = WorldWebScenario.openBelief(path).runtime()) {
            assertEquals(1, restored.beliefAudit(WorldWebScenario.CHAIR).orElseThrow().factClaims().size());
        }
    }

    @Test
    void capturedValueMustBeRecomputedFromLedgerEvenWithValidFrameHashes() throws Exception {
        Path dir = directory();
        Path source = dir.resolve("world.journal");
        try (var world = WorldWebScenario.openBelief(source).runtime()) {
            world.openRoleSession(WorldWebScenario.CHAIR).reportOrders("report", "report-task", 10,
                    new WorldRoleSession.ReportDraft(WorldWebScenario.COMPANY, 100));
            world.advanceTo(100);
        }
        try (var unchanged = WorldWebScenario.openBelief(source).runtime()) {
            assertEquals(100, unchanged.snapshot().timeMillis(), "未篡改的捕获中间状态应可恢复");
        }
        int[] changed = {0};
        Path forged = rewrite(source, dir.resolve("forged.journal"), root -> {
            for (var captured : root.path("beliefs").path("captured")) {
                ((ObjectNode) captured).put("value", 999);
                changed[0]++;
            }
        });
        assertTrue(changed[0] > 0, "夹具必须真正修改已捕获事实");
        assertThrows(IOException.class, () -> {
            try (var ignored = WorldWebScenario.openBelief(forged).runtime()) { }
        }, "摘要合法不代表捕获值来自真实台账");
    }

    @Test
    void formedConfidenceCannotBeRewrittenConsistentlyAcrossSnapshots() throws Exception {
        Path dir = directory();
        Path source = dir.resolve("world.journal");
        try (var world = WorldWebScenario.openBelief(source).runtime()) {
            world.openRoleSession("bob").publishForecast("publish", "publish-task", 10,
                    new WorldRoleSession.ForecastDraft("forecast", "document", "预测", "至少一单",
                            "本人预测", 1, WorldWebScenario.COMPANY, BeliefModel.Comparator.AT_LEAST, 1, 100));
            world.advanceTo(11);
            world.scheduleStartReading("read", "read-task", 12, "alice", "document", 1);
            world.advanceTo(14);
            var ref = world.roleView("alice").observations().get(0).ref();
            world.openRoleSession("alice").assertBelief("assert", "assert-task", 20,
                    new WorldRoleSession.BeliefDraft("belief", WorldWebScenario.COMPANY,
                            BeliefModel.Comparator.AT_LEAST, 1, 100, "forecast", List.of(ref), "采用已读预测"));
            world.advanceTo(21);
            assertEquals(500, world.beliefAudit("alice").orElseThrow().state().beliefs().get(0).confidencePermille());
        }
        try (var unchanged = WorldWebScenario.openBelief(source).runtime()) {
            assertEquals(500, unchanged.beliefAudit("alice").orElseThrow().state().beliefs().get(0).confidencePermille());
        }
        int[] changed = {0};
        Path forged = rewrite(source, dir.resolve("forged.journal"), root -> {
            for (var belief : root.path("beliefs").path("beliefs").path("beliefs")) {
                ((ObjectNode) belief).put("confidencePermille", 999);
                changed[0]++;
            }
        });
        assertTrue(changed[0] > 0, "夹具必须真正修改形成时置信度");
        assertThrows(IOException.class, () -> {
            try (var ignored = WorldWebScenario.openBelief(forged).runtime()) { }
        }, "从第一次出现起一致伪造也必须被前帧重算拒绝");
    }

    private static Path directory() throws IOException {
        Path root = Path.of("target", "belief-integrity-tests");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "case-");
    }

    private static Path rewrite(Path source, Path target, Consumer<ObjectNode> change) throws IOException {
        return rewriteAll(source, target, (type, root) -> {
            if (type.equals("STATE_SNAPSHOT_V1")) change.accept(root);
        });
    }

    private static Path rewriteAll(Path source, Path target,
                                  java.util.function.BiConsumer<String, ObjectNode> change) throws IOException {
        var json = new ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        json.setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        String runId = WorldWebScenario.beliefManifest().runId();
        try (var original = new WorldJournal(source, runId); var rewritten = new WorldJournal(target, runId)) {
            for (var commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                for (var event : commit.events()) {
                    if (event.type().equals("STATE_SNAPSHOT_V1") || event.type().equals("BELIEF_TASK_V1")) {
                        var root = (ObjectNode) json.readTree(event.payload());
                        var before = root.deepCopy();
                        change.accept(event.type(), root);
                        events.add(root.equals(before) ? event
                                : new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(root)));
                    } else events.add(event);
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
        return target;
    }
}
