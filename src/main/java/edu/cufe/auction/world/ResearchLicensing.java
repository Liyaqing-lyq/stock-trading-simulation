package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 药企成果的有限自用许可。合同只登记权利和履约，资金保存在双方原账户。 */
public final class ResearchLicensing {
    /** 经理可接受要约、交付已签许可或收取已到期款项。 */
    public static final String TOOL = "research.license";
    /** 本版每个获批项目一份、一次性固定对价的 EXP 要约。 */
    public static final BigDecimal FEE = new BigDecimal("300.00");
    private static final long DAY = 86_400_000L;
    private ResearchLicensing() { }

    /** @param manifest 当前或分支继承的运行清单 @return 是否启用已批准项目的自用许可合同 */
    public static boolean enabled(RunManifest manifest) {
        return RunManifest.APPROVED_USE_CONTRACT.equals(manifest.dataVersions().get(RunManifest.PHARMA_LICENSING));
    }

    /** 本人选择的办理阶段及已登记项目。 */
    public record Request(String action, String projectId) {
        public Request {
            if (action == null || !Set.of("SIGN", "DELIVER", "COLLECT").contains(action)
                    || projectId == null || projectId.isBlank()) throw new IllegalArgumentException("许可办理参数无效");
        }
    }

    /** 确认的签约、交付和实收时点，未发生的时点保留为空。 */
    public record Contract(String projectId, long signedAt, Long deliveredAt, Long paidAt) {
        public Contract {
            if (projectId == null || projectId.isBlank() || signedAt < 0 || deliveredAt != null
                    && deliveredAt < Math.addExact(signedAt, DAY) || paidAt != null
                    && (deliveredAt == null || paidAt < Math.addExact(deliveredAt, 30 * DAY)))
                throw new IllegalArgumentException("许可时点不符合原条款");
        }
        /** @return 当前合同最早可交付的模拟时点 */
        public long deliverableAt() { return Math.addExact(signedAt, DAY); }
        /** @return 收款到期时点，未交付时为空 */
        public Long dueAt() { return deliveredAt == null ? null : Math.addExact(deliveredAt, 30 * DAY); }
    }

    /** 按同一组已签合同复算的公司收入、应收、实际收款和客户应付。 */
    public record Totals(int signedContracts, int deliveredContracts, BigDecimal revenue,
                         BigDecimal receivable, BigDecimal cashReceived, BigDecimal customerPayable) { }

    /** @param contracts 截面已有合同 @return 该截面的累计权责和现金，未签约为零 */
    public static Totals totals(Map<String, Contract> contracts) {
        var entries = contracts == null ? java.util.List.<Contract>of() : contracts.values();
        int delivered = 0, paid = 0;
        for (Contract c : entries) {
            if (c.deliveredAt() != null) delivered++;
            if (c.paidAt() != null) paid++;
        }
        var earned = FEE.multiply(BigDecimal.valueOf(delivered));
        var received = FEE.multiply(BigDecimal.valueOf(paid));
        var unpaid = earned.subtract(received);
        return new Totals(entries.size(), delivered, earned, unpaid, received, unpaid);
    }

    /** @param state 该研发公司的已确认账本 @return 许可合同条款、履约时点及累计结算 */
    public static Map<String,Object> terms(ResearchModel.State state) {
        var contracts = new TreeMap<String, Object>();
        if (state.licenses() != null) for (Contract c : state.licenses().values()) {
            var row = new TreeMap<String,Object>();
            row.put("projectId", c.projectId()); row.put("signedAt", c.signedAt());
            row.put("deliverableAt", c.deliverableAt()); row.put("deliveredAt", c.deliveredAt());
            row.put("dueAt", c.dueAt()); row.put("paidAt", c.paidAt());
            contracts.put(c.projectId(), row);
        }
        var approved = state.projects().values().stream().filter(p -> p.status() == ResearchModel.ProjectStatus.APPROVED)
                .map(ResearchModel.Project::projectId).sorted().toList();
        return new TreeMap<>(Map.of("licensee", ExternalDemandOpening.OWNER_ID, "payerAccount", ExternalDemandOpening.ACCOUNT_ID,
                "companyAccount", state.config().companyAccountId(), "fee", FEE.toPlainString(), "contracts", contracts,
                "approvedProjects", approved, "actions", java.util.List.of("SIGN","DELIVER","COLLECT"),
                "durationMillis", ResearchDecisionTools.ACTION_MILLIS,
                "totals", totals(state.licenses()), "terms", "EXP 外部最终需求部门按公开固定要约仅为自己购买获批成果的非独占使用权。每个获批项目限一份，经理可 SIGN 或等待。签约不确认收入。满一个模拟日可 DELIVER，交付才确认一次性许可收入与客户应付。交付满30模拟日可 COLLECT，客户必须实际足额付款，不能付款时债权保留。没有销售分成。"));
    }

    static ResearchRuntime.Applied apply(ResearchRun run, BoardRun board, Request request, long now,
            BigDecimal transferable, Map<String,CashLedger.CashAccount> accounts) {
        var state = run.state();
        var project = state.projects().get(request.projectId());
        boolean approved = project != null && project.status() == ResearchModel.ProjectStatus.APPROVED;
        var previous = state.licenses() == null ? null : state.licenses().get(request.projectId());
        String reason = switch (request.action()) {
            case "SIGN" -> !approved ? "COMMERCIAL_APPROVAL_REQUIRED" : previous != null ? "LICENSE_ALREADY_SIGNED" : null;
            case "DELIVER" -> previous == null ? "LICENSE_NOT_SIGNED" : previous.deliveredAt() != null ? "LICENSE_ALREADY_DELIVERED"
                    : now < previous.deliverableAt() ? "LICENSE_DELIVERY_NOT_DUE" : null;
            case "COLLECT" -> previous == null || previous.deliveredAt() == null ? "LICENSE_NOT_DELIVERED"
                    : previous.paidAt() != null ? "LICENSE_ALREADY_PAID" : now < previous.dueAt() ? "LICENSE_PAYMENT_NOT_DUE"
                    : transferable.compareTo(FEE) < 0 ? "LICENSE_CUSTOMER_INSUFFICIENT_FUNDS" : null;
            default -> throw new IllegalArgumentException("未知许可动作");
        };
        if (reason != null) return ResearchRuntime.Applied.rejected(run, board, null, accounts, reason);
        var next = new TreeMap<String,Contract>();
        if (state.licenses() != null) next.putAll(state.licenses());
        Contract updated;
        if ("SIGN".equals(request.action())) updated = new Contract(request.projectId(), now, null, null);
        else if ("DELIVER".equals(request.action())) updated = new Contract(request.projectId(), previous.signedAt(), now, null);
        else {
            var transfer = CashLedger.computeTransfer(accounts, ExternalDemandOpening.OWNER_ID,
                    ExternalDemandOpening.ACCOUNT_ID, state.config().companyAccountId(), FEE);
            if (!transfer.accepted()) return ResearchRuntime.Applied.rejected(run, board, null, accounts, transfer.reason());
            accounts = transfer.accounts();
            updated = new Contract(request.projectId(), previous.signedAt(), previous.deliveredAt(), now);
        }
        next.put(request.projectId(), updated);
        return new ResearchRuntime.Applied(run.withState(new ResearchModel.State(state.config(), state.initialEquity(),
                state.initialWorldCash(), now, state.projects(), state.commandContents(), state.commandOutcomes(), next)),
                board, null, accounts, true, "LICENSE_" + switch (request.action()) {
                    case "SIGN" -> "SIGNED"; case "DELIVER" -> "DELIVERED"; default -> "PAID";
                });
    }
}
