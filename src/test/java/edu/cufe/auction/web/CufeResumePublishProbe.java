package edu.cufe.auction.web;

import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.WorldRuntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 真跨进程续跑探针（章程公司周期链）：新 JVM 打开同一默认 engineering 日志，
 * 用已落盘的经理任职与本人未消费授权完成一次真实发布。
 *
 * <p>只走真实运行入口：恢复日志 → 读本人 {@code periodicRoleView} 取得授权绑定 →
 * 排程 PUBLISH（BEGIN/FINISH）→ 推进 → 打印公开版本证据。不 mock、不反射、不读 god 字段。</p>
 */
public final class CufeResumePublishProbe {

    private CufeResumePublishProbe() {
    }

    /**
     * 入口。
     *
     * @param args {@code <journal> <publisherPersonId>}
     * @throws Exception 运行失败
     */
    public static void main(String[] args) throws Exception {
        Path journal = Path.of(args[0]);
        String publisher = args[1];
        String company = WorldWebScenario.COMPANY;
        boolean groups = args.length > 2 && args[2].equals("groups");
        try (WorldRuntime world = groups
                ? WorldWebScenario.openGroups(journal).runtime()
                : WorldWebScenario.openEngineering(journal).runtime()) {
            WorldRuntime.Snapshot snapshot = world.snapshot();
            long now = snapshot.timeMillis();
            boolean managerActive = world.isActiveManager(company, "dave", now);
            long period = 1L;
            Map<String, Object> view = world.periodicRoleView(publisher, company);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) view
                    .getOrDefault("periods", List.of());
            Map<String, Object> grant = null;
            for (Map<String, Object> row : rows) {
                Object periodValue = row.get("period");
                if (!(periodValue instanceof Number number) || number.longValue() != period) {
                    continue;
                }
                Object pending = row.get("pendingAuthorization");
                if (pending instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    grant = typed;
                }
            }
            if (grant == null) {
                throw new IllegalStateException("新进程没有看到本人可执行授权");
            }
            String authorizationId = String.valueOf(grant.get("authorizationId"));
            String draftId = String.valueOf(grant.get("draftId"));
            String draftVersion = String.valueOf(grant.get("draftVersion"));
            long due = now + 1_000L;
            String receipt = world.schedulePeriodicAction("probe-publish-" + authorizationId,
                    "probe-publish-task-" + authorizationId, due, company, publisher, "PUBLISH",
                    period, draftId, Long.parseLong(draftVersion), "", 0L, authorizationId,
                    List.of(), "", 1L);
            world.advanceTo(due);
            String publicDocId = "company-statement:" + company + ":" + period;
            String body = null;
            long version = -1L;
            for (var information : world.snapshot().information().stream().toList()) {
                for (var document : information.information().documents()) {
                    if (!document.docId().equals(publicDocId)) {
                        continue;
                    }
                    for (var candidate : document.versions()) {
                        body = candidate.body();
                        version = candidate.version();
                    }
                }
            }
            boolean grantGone = true;
            Map<String, Object> after = world.periodicRoleView(publisher, company);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> afterRows = (List<Map<String, Object>>) after
                    .getOrDefault("periods", List.of());
            for (Map<String, Object> row : afterRows) {
                Object periodValue = row.get("period");
                if (periodValue instanceof Number number && number.longValue() == period) {
                    grantGone = row.get("pendingAuthorization") == null;
                }
            }
            System.out.println("MANAGER=" + managerActive);
            System.out.println("AUTHORIZATION=" + authorizationId);
            System.out.println("RECEIPT=" + receipt);
            System.out.println("VERSION=" + version);
            System.out.println("BODY_DIGEST=" + sha256(body == null ? "" : body));
            System.out.println("GRANT_GONE=" + grantGone);
            System.out.println("PERIOD_ONE=" + (version > 0));
            PeriodicModel.CompanyPeriods periods = world.periodicOf(company).orElse(null);
            System.out.println("RELEASES=" + (periods == null ? -1
                    : periods.releases().stream().filter(r -> r.period() == period).count()));
        }
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
