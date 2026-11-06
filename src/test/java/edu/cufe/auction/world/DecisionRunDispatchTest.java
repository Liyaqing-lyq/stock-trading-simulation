package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2b 正式合同：DecisionRun 的持久派发事实、旧构造与 JSON 形状。
 *
 * <p>本类只测纯数据层，不启动世界、不写日志、不联网。派发语义由
 * {@link WorldRuntimeLiveDecisionTest} 覆盖。</p>
 */
class DecisionRunDispatchTest {

    private static final String RUN = "dispatch-run";
    private static final String ATTEMPT = "ATT-1";
    private static final String ACTION = "a-1";
    private static final String PLAN_DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String INPUT_DIGEST =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------------------------------------------------------------- 旧构造/JSON

    @Test
    @DisplayName("旧四参构造 dispatches 为 null，JSON 不出现 dispatches 键，所有 with 方法保持 null")
    void legacyFourArgKeepsNullAndOmitsJson() throws Exception {
        DecisionRun run = new DecisionRun(state(), Map.of(), Map.of(), List.of());
        assertNull(run.dispatches(), "旧构造必须是 null，不是空 map");

        String json = JSON.writeValueAsString(run);
        assertFalse(json.contains("dispatches"), "旧 JSON 不得出现 dispatches 键");
        assertNull(JSON.readValue(json, DecisionRun.class).dispatches());

        assertNull(run.withState(state()).dispatches());
        assertNull(run.withBinding(new DecisionRun.Binding(ACTION, "alice", "SCHEDULED",
                Map.of())).dispatches());
        assertNull(run.withActionRef(new DecisionRun.ActionRef(ACTION, "task-1",
                DecisionModel.TOOL_WAIT, 1_000L)).dispatches());
        assertNull(run.withArchive(new DecisionRun.Archive(ACTION, ATTEMPT, 1, 1L, "",
                null, "", "STALE_GENERATION", "OLD_GENERATION")).dispatches());
    }

    @Test
    @DisplayName("新 live 开局是空 map；withState/withBinding/withActionRef/withArchive 原样保持")
    void liveOpeningStartsEmptyAndKeepsMap() {
        DecisionRun opening = DecisionRun.opening(RUN, 7L, config());
        assertTrue(opening.dispatches() != null, "新 live 开局必须是空 map 而不是 null");
        assertTrue(opening.dispatches().isEmpty());

        DecisionRun bound = opening.withBinding(new DecisionRun.Binding(ACTION, "alice",
                "SCHEDULED", Map.of()));
        assertEquals(opening.dispatches(), bound.dispatches());
        DecisionRun referenced = bound.withActionRef(new DecisionRun.ActionRef(ACTION, "task-1",
                DecisionModel.TOOL_WAIT, 1_000L));
        assertEquals(opening.dispatches(), referenced.dispatches());
        DecisionRun archived = referenced.withArchive(new DecisionRun.Archive(ACTION, ATTEMPT,
                1, 1L, "", null, "", "STALE_GENERATION", "OLD_GENERATION"));
        assertEquals(opening.dispatches(), archived.dispatches());
        assertTrue(archived.withState(opening.state()).dispatches().isEmpty());
    }

    // ---------------------------------------------------------------- Dispatch 事实

    @Test
    @DisplayName("Dispatch 构造校验非空身份、正序号、非负时间与 64 位小写十六进制摘要")
    void dispatchValidatesIdentityAndDigests() {
        assertEquals(ATTEMPT, dispatch().attemptId());
        assertThrows(IllegalArgumentException.class, () -> new DecisionRun.Dispatch(
                "", ACTION, 1, 1L, PLAN_DIGEST, INPUT_DIGEST, 1L, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRun.Dispatch(
                ATTEMPT, " ", 1, 1L, PLAN_DIGEST, INPUT_DIGEST, 1L, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRun.Dispatch(
                ATTEMPT, ACTION, 0, 1L, PLAN_DIGEST, INPUT_DIGEST, 1L, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRun.Dispatch(
                ATTEMPT, ACTION, 1, 0L, PLAN_DIGEST, INPUT_DIGEST, 1L, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRun.Dispatch(
                ATTEMPT, ACTION, 1, 1L, PLAN_DIGEST, INPUT_DIGEST, -1L, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRun.Dispatch(
                ATTEMPT, ACTION, 1, 1L, PLAN_DIGEST, INPUT_DIGEST, 1L, -1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRun.Dispatch(
                ATTEMPT, ACTION, 1, 1L, PLAN_DIGEST, INPUT_DIGEST, 1L, 0L, -1L));
        for (String bad : List.of("abc", PLAN_DIGEST.toUpperCase(),
                PLAN_DIGEST.substring(1) + "g", PLAN_DIGEST.substring(0, 63))) {
            assertThrows(IllegalArgumentException.class, () -> new DecisionRun.Dispatch(
                    ATTEMPT, ACTION, 1, 1L, bad, INPUT_DIGEST, 1L, 0L, 1L),
                    "非法计划摘要必须拒绝：" + bad);
            assertThrows(IllegalArgumentException.class, () -> new DecisionRun.Dispatch(
                    ATTEMPT, ACTION, 1, 1L, PLAN_DIGEST, bad, 1L, 0L, 1L),
                    "非法输入摘要必须拒绝：" + bad);
        }
    }

    @Test
    @DisplayName("withDispatch 同 attempt 同事实幂等；同 attempt 异事实拒绝且不改原 map")
    void withDispatchIsIdempotentAndRejectsRewrite() {
        DecisionRun run = DecisionRun.opening(RUN, 7L, config());
        DecisionRun once = run.withDispatch(dispatch());
        DecisionRun twice = once.withDispatch(dispatch());
        assertEquals(once, twice);
        assertEquals(1, twice.dispatches().size());
        assertEquals(dispatch(), twice.dispatch(ATTEMPT), "按 attemptId 可检索");
        assertNull(twice.dispatch("ATT-2"));

        DecisionRun.Dispatch conflicting = new DecisionRun.Dispatch(ATTEMPT, ACTION, 1, 1L,
                fingerprint("other-plan"), INPUT_DIGEST, 1L, 0L, 1L);
        assertThrows(IllegalArgumentException.class, () -> once.withDispatch(conflicting),
                "同一 attempt 不得改写派发事实");
        assertEquals(1, once.dispatches().size());
        assertEquals(dispatch(), once.dispatch(ATTEMPT));
        assertEquals(once.dispatches(), once.withState(once.state()).dispatches());
        assertEquals(once.dispatches(), once.withBinding(new DecisionRun.Binding(ACTION,
                "alice", "SCHEDULED", Map.of())).dispatches());
        assertEquals(once.dispatches(), once.withActionRef(new DecisionRun.ActionRef(ACTION,
                "task-1", DecisionModel.TOOL_WAIT, 1_000L)).dispatches());
        assertEquals(once.dispatches(), once.withArchive(new DecisionRun.Archive(ACTION,
                ATTEMPT, 1, 1L, "", null, "", "STALE_GENERATION", "OLD_GENERATION"))
                .dispatches());
    }

    @Test
    @DisplayName("dispatches 参与 JSON 往返，旧 null 与新空 map 都不伪装成对方")
    void dispatchesJsonRoundTrip() throws Exception {
        DecisionRun live = DecisionRun.opening(RUN, 7L, config()).withDispatch(dispatch());
        String json = JSON.writeValueAsString(live);
        assertTrue(json.contains("\"dispatches\""), "新 live 必须持久 dispatch map");
        assertTrue(json.contains("\"planDigest\":\"" + PLAN_DIGEST + "\""));
        DecisionRun back = JSON.readValue(json, DecisionRun.class);
        assertEquals(live, back);
        assertEquals(dispatch(), back.dispatch(ATTEMPT));

        DecisionRun empty = JSON.readValue(
                JSON.writeValueAsString(DecisionRun.opening(RUN, 7L, config())),
                DecisionRun.class);
        assertTrue(empty.dispatches() != null && empty.dispatches().isEmpty());
        assertFalse(empty.dispatches() == null, "空 map 不能被读成旧 null");
    }

    // ---------------------------------------------------------------- Request / Call

    @Test
    @DisplayName("Request/Call 旧构造保持旧 JSON 形状；新字段只在给出时出现")
    void requestAndCallKeepOldConstructors() throws Exception {
        DecisionRun.Request legacyRequest = new DecisionRun.Request("alice", ACTION, "SCHEDULED");
        assertNull(legacyRequest.route());
        String legacyRequestJson = JSON.writeValueAsString(legacyRequest);
        assertFalse(legacyRequestJson.contains("route"), "旧请求 JSON 不得出现 route 键");
        assertNull(JSON.readValue(legacyRequestJson, DecisionRun.Request.class).route());

        DecisionRun.Request liveRequest = new DecisionRun.Request("alice", ACTION, "SCHEDULED",
                route("research", "key-1", 3));
        assertTrue(JSON.writeValueAsString(liveRequest).contains("\"route\""));
        assertEquals(liveRequest, JSON.readValue(JSON.writeValueAsString(liveRequest),
                DecisionRun.Request.class));

        DecisionRun.Call legacyCall = new DecisionRun.Call(RUN, ACTION, ATTEMPT, 1, 1L,
                "SCHEDULED", List.of(DecisionModel.TOOL_WAIT), "{\"observe\":true}");
        assertNull(legacyCall.plan());
        String legacyCallJson = JSON.writeValueAsString(legacyCall);
        assertFalse(legacyCallJson.contains("plan"), "旧 Call JSON 不得出现 plan 键");

        DecisionModel.ProviderPlan plan = plan("research", 3);
        DecisionRun.Call liveCall = new DecisionRun.Call(RUN, ACTION, ATTEMPT, 1, 1L,
                "SCHEDULED", List.of(DecisionModel.TOOL_WAIT), "{\"observe\":true}", plan);
        assertEquals(plan, liveCall.plan());
        assertTrue(JSON.writeValueAsString(liveCall).contains("\"plan\""));
        assertEquals(liveCall, JSON.readValue(JSON.writeValueAsString(liveCall),
                DecisionRun.Call.class));
    }

    // ---------------------------------------------------------------- 夹具

    private static DecisionRun.Dispatch dispatch() {
        return new DecisionRun.Dispatch(ATTEMPT, ACTION, 1, 1L, PLAN_DIGEST, INPUT_DIGEST,
                3L, 4_000L, 1_700_000_000_000L);
    }

    private static DecisionModel.State state() {
        return DecisionModel.open(RUN, 7L, config());
    }

    private static DecisionModel.Config config() {
        DecisionModel.Usage cap = DecisionModel.Usage.unknownCost(100, 5_000_000, 2_000_000);
        return new DecisionModel.Config(DecisionModel.PROVIDER_DEEPSEEK_HTTP, "deepseek:dispatch-test",
                DecisionModel.ESTIMATE_VERSION, Map.of("SCHEDULED", 1_000L), Map.of(),
                new DecisionModel.Limits(3, 20_000, 4_000, 60_000, 4_096, 4, 2, 60_000L),
                new DecisionModel.Budget(cap, Map.of("alice", cap)),
                DecisionModel.Profile.LIVE_HTTP_V1);
    }

    private static DecisionModel.Route route(String scene, String slot, long revision) {
        return new DecisionModel.Route(scene, slot, "deepseek-flash", revision, 20, 1_000_000,
                1_024, 2);
    }

    private static DecisionModel.ProviderPlan plan(String scene, long revision) {
        return new DecisionModel.ProviderPlan(route(scene, "key-1", revision), 16L);
    }

    private static String fingerprint(String seed) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < 64; index++) {
            out.append(Character.forDigit((seed.hashCode() + index) & 0xF, 16));
        }
        return out.toString();
    }
}
