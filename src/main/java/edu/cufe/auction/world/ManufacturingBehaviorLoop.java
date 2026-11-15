package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Web 与实验共用的持续经营入口。决策笔记、业务待办和回报全部进入原世界日志。 */
public final class ManufacturingBehaviorLoop {
    /** 六小时检查一次状态；不是采购或成交配额。 */
    public static final long INTERVAL = 6 * 3_600_000L;
    private static final String COMPANY = ComposedManufacturingThreeScenario.TECH;
    private static final String PERSON = ComposedManufacturingThreeScenario.TECH_MANAGER;
    private static final String PREFIX = "behavior-v1:";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ManufacturingBehaviorLoop() { }

    /** 只有显式新档启用；旧实验与普通推进保持原义。 */
    public static boolean enabled(WorldRuntime world) {
        return ManufacturingBehaviorPolicy.VERSION.equals(world.profile().manifest().dataVersions().get("behavior-policy"));
    }

    /** 冻结经理本人可见输入和选择；不存客户私账，不记录模型调用。 */
    public record Decision(String version, String id, String person, String company, long observedAt,
                           JsonNode observation, ManufacturingBehaviorPolicy.Choice choice) { }

    /** 持续推进到请求时点，途中反复观察；中断后从已提交笔记及任务标识续行。 */
    public static void advanceTo(WorldRuntime world, long target) throws IOException {
        if (!enabled(world)) throw new IllegalArgumentException("此档未冻结行为规则");
        if (target < world.snapshot().timeMillis()) throw new IllegalArgumentException("不能倒退");
        if (world.pendingBarrier().isPresent()) throw new IllegalStateException("须先完成已有模型决定");
        while (world.snapshot().timeMillis() < target) {
            long now = world.snapshot().timeMillis();
            long slot = now / INTERVAL;
            long end = Math.min(target, Math.multiplyExact(slot + 1, INTERVAL));
            String id = PREFIX + COMPANY + ":" + slot;
            String noteTask = id + ":note";
            // 恢复时先完成已经持久排程的笔记；不能用新的输入覆盖旧选择。
            if (world.snapshot().pending().stream().anyMatch(t -> t.taskId().equals(noteTask)))
                world.advanceTo(Math.min(end, now + 1));
            Decision decision = find(world, id);
            if (decision == null && !world.snapshot().usedTaskIds().contains(noteTask)) {
                var input = world.engineeringRoleInput(PERSON).path("companyOperations");
                JsonNode own = null;
                for (var item : input) if (item.path("companyId").asText().equals(COMPANY)) own = item;
                // 无任命时不代替董事会授予权限。
                if (own != null) {
                    now = world.snapshot().timeMillis();
                    decision = new Decision(ManufacturingBehaviorPolicy.VERSION, id, PERSON, COMPANY, now,
                            own, ManufacturingBehaviorPolicy.choose(own, now, id));
                    world.recordManufacturingDecision(decision);
                    world.advanceTo(now + 1);
                }
            }
            if (decision != null && !decision.choice().action().equals("WAIT")) {
                String actionId = id + ":act";
                if (!world.snapshot().usedTaskIds().contains(actionId + ":begin")
                        && !world.snapshot().usedTaskIds().contains(actionId)) {
                    var action = CompanyOperationsRun.Action.valueOf(decision.choice().action());
                    var fields = CompanyOperationsDecisionTools.fields(JSON.valueToTree(decision.choice().fields()), action);
                    long due = world.snapshot().timeMillis() + 1_001;
                    world.openRoleSession(PERSON).operationsAction(actionId, actionId, due, COMPANY, action, fields, 1_000);
                }
            }
            world.advanceTo(end);
            if (world.snapshot().timeMillis() != end) throw new IllegalStateException("推进被决定屏障停止");
        }
    }

    /** 上帝视角的真实决策及对应业务回报；普通人物权限仍由原资料系统决定。 */
    public static List<Map<String, Object>> audit(WorldRuntime world) throws IOException {
        var rows = new ArrayList<Map<String, Object>>();
        for (var doc : world.snapshot().information().orElseThrow().information().documents()) {
            if (!doc.docId().startsWith(PREFIX)) continue;
            Decision d = find(world, doc.docId());
            var reports = world.operationsRun().orElseThrow().reports().values().stream()
                    .filter(r -> r.commandId().equals(d.id() + ":act")).toList();
            String limitingFactor = d.choice().action().equals("WAIT")
                    && d.choice().reason().equals("营运资金、产能、供货或董事会授权不足")
                    && d.observation().path("capacityThisPeriod").asLong() == 0
                    ? "本期剩余产能为 0 件，等待下一期；不继续接单或虚构产能" : d.choice().reason();
            rows.add(Map.of("timeMillis", d.observedAt(), "company", d.company(), "person", d.person(),
                    "action", d.choice().action(), "reason", d.choice().reason(), "fields", d.choice().fields(), "results", reports,
                    "limitingFactor", limitingFactor));
        }
        rows.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("timeMillis")).longValue()));
        return List.copyOf(rows);
    }

    private static Decision find(WorldRuntime world, String id) throws IOException {
        var doc = world.snapshot().information().orElseThrow().information().documents().stream()
                .filter(d -> d.docId().equals(id)).findFirst().orElse(null);
        if (doc == null) return null;
        Decision d = JSON.readValue(doc.at(1).body(), Decision.class);
        if (!PERSON.equals(doc.publisherPartyId()) || !InformationModel.Audience.direct(PERSON).equals(doc.audience())
                || doc.versions().size() != 1 || !ManufacturingBehaviorPolicy.VERSION.equals(d.version())
                || !id.equals(d.id()) || !PERSON.equals(d.person()) || !COMPANY.equals(d.company()))
            throw new IllegalStateException("经营决策笔记身份或版本冲突");
        var expected = ManufacturingBehaviorPolicy.choose(d.observation(), d.observedAt(), d.id());
        if (!JSON.writeValueAsString(expected).equals(JSON.writeValueAsString(d.choice())))
            throw new IllegalStateException("经营决策与冻结输入不一致");
        return d;
    }
}
