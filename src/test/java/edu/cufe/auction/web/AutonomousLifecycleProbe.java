package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 冻结参数的真实跨期探针：没有预读、经营脚本或规则替代，保存全部原输入与回执。 */
public final class AutonomousLifecycleProbe {
    static final String INPUT_EVENTS = "local-input-events.jsonl";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** @param mode ACCOUNTABLE/OPERATING保留v3；PLANNING/PLANNING_OPERATING为v4治理/有来源需求实验；INFORMED_OPERATING显式启用经理经营信息与来源审计；seed只接续已完整结束的同版本档。 */
    public record Config(String mode, int maxHttpCalls, int wallMinutes, long targetMillis,
                         long stepMillis, String seed, String sourceCommit,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         java.util.List<String> persons) {
        /** 保留早期探针的原配置和逐字序列化。 */
        public Config(String mode, int maxHttpCalls, int wallMinutes, long targetMillis, long stepMillis,
                      String seed, String sourceCommit) {
            this(mode, maxHttpCalls, wallMinutes, targetMillis, stepMillis, seed, sourceCommit, null);
        }
        /** 约束试跑边界，额度是上限，不是调用承诺或经营验收。 */
        public Config {
            if (persons != null) {
                persons = java.util.List.copyOf(persons);
                if (persons.isEmpty() || persons.stream().anyMatch(String::isBlank)
                        || persons.stream().distinct().count() != persons.size())
                    throw new IllegalArgumentException("专项人物名单须非空且没有重复");
            }

            if (!java.util.Set.of("ACCOUNTABLE", "OPERATING", "PLANNING", "PLANNING_OPERATING", "INFORMED_OPERATING", "CONTEXTUAL_INFORMED_OPERATING", "THINKING_INFORMED_OPERATING", "FUNDED_THINKING_OPERATING", "PERSONAL_MARKET_OPERATING", "ENGINEERING_CUSTOMER_OPERATING", "COMPANY_INFORMATION_OPERATING", "FORWARD_BUDGET_OPERATING", "AGED_ORDER_OPERATING", "BOARD_PROCEDURE_OPERATING", "ISSUER_REPORT_OPERATING", "ROLE_TABLE_OPERATING", "HYDRO_SETTLEMENT_OPERATING", "PERSONAL_LIMIT_OPERATING", "CONTINUING_ROLES_OPERATING", "ISSUER_CREDIT_OPERATING", "ISSUER_CREDIT_DECISIONS_OPERATING", "PERSONAL_PROFILES_OPERATING", "FUND_MANDATE_OPERATING", "FUND_OFFICE_CONTACTS_OPERATING", "FUND_OFFICE_ROUTING_OPERATING", "MEDIA_DESKS_OPERATING", "MARKET_SESSIONS_OPERATING", "INTEGRATED_OPERATING", "MANUFACTURING_LIFECYCLE_OPERATING", "BANK_CRISIS_OPERATING", "PRICE_BAND_OPERATING", "ISSUER_DIVIDEND_OPERATING", "LOAN_PROMOTION_OPERATING", "BASELINE_PARTS_OPERATING", "OPENING_COPIES", "OWNER_BANK_VALUATION", "UNPRICED_REFERENCE", "ISSUER_RESOLUTION", "PERSON_PAUSE", "TEN_COMPANY_AUTONOMY", "TEN_COMPANY_STUDY", "TEN_COMPANY_GOVERNANCE_STUDY", "TEN_COMPANY_EDITORIAL_STUDY", "TEN_COMPANY_PERSONAL_STUDY", "TEN_COMPANY_INTERVIEW_STUDY", "TEN_COMPANY_MEETING_STUDY", "TEN_COMPANY_DIGITAL_WIND_DOWN_STUDY", "TEN_COMPANY_FEEDBACK_STUDY", "TEN_COMPANY_PERSONAL_NEWS_STUDY", "TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY", "OPEN_FUND").contains(mode)
                    || !(java.util.Set.of("TEN_COMPANY_AUTONOMY","TEN_COMPANY_STUDY","TEN_COMPANY_GOVERNANCE_STUDY","TEN_COMPANY_EDITORIAL_STUDY","TEN_COMPANY_PERSONAL_STUDY","TEN_COMPANY_INTERVIEW_STUDY","TEN_COMPANY_MEETING_STUDY","TEN_COMPANY_DIGITAL_WIND_DOWN_STUDY", "TEN_COMPANY_FEEDBACK_STUDY", "TEN_COMPANY_PERSONAL_NEWS_STUDY", "TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY").contains(mode) && maxHttpCalls == 0 && wallMinutes == 0)
                    && (maxHttpCalls < 1 || maxHttpCalls > 3000 || wallMinutes < 1 || wallMinutes > 60)
                    || targetMillis <= 0 || targetMillis > 180L * 86400000
                    || stepMillis != 3_600_000
                    || sourceCommit == null || sourceCommit.isBlank()) throw new IllegalArgumentException("无效试跑参数");
        }
        /** 正常十公司以双零显式选择运行至世界目标，无历史试跑调用数/墙钟切段。 */
        boolean runUntilTarget() { return maxHttpCalls == 0 && wallMinutes == 0; }
        int callLimit() { return runUntilTarget() ? Integer.MAX_VALUE : maxHttpCalls; }
    }
    /** @param args 独立新输出目录、五槽凭据文件、冻结配置JSON路径 */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("需要输出目录、凭据文件和参数文件");
        run(Path.of(args[0]), Path.of(args[2]), () -> new LiveDecisionProvider(KeySlots.load(Path.of(args[1]))));
    }

    @FunctionalInterface
    interface ProviderFactory { LiveDecisionProvider open() throws java.io.IOException; }

    static void run(Path out, Path parameterFile, ProviderFactory providers) throws Exception {
        Config config = JSON.readValue(parameterFile.toFile(), Config.class);
        Path seed = config.seed() == null ? null : validateSeed(config);
        // 初建草稿与本次参数放在一起；接续以种子首帧冻结的草稿为准。
        byte[] draft = java.util.Set.of("OPENING_COPIES","OWNER_BANK_VALUATION", "UNPRICED_REFERENCE", "ISSUER_RESOLUTION", "PERSON_PAUSE").contains(config.mode()) && seed == null
                ? Files.readAllBytes(parameterFile.resolveSibling("opening-copies.json")) : null;
        Files.createDirectory(out);
        if (draft != null) Files.write(out.resolve("opening-copies.json"), draft);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("config.json").toFile(), config);
        Files.writeString(out.resolve("system-prompt.txt"), systemPrompt(config));
        boolean informed = informedMode(config.mode());
        boolean thinking = thinkingMode(config.mode());
        boolean measured = usesMeasuredLength(config.mode());
        var transport = config.runUntilTarget()
                ? new TargetTransportPolicy(TargetTransportPolicy.frozenVersion(seed),TargetTransportPolicy.inherited(seed)) : null;
        if (informed) ProbeProviderEvidence.freezePolicy(out,thinking,measured,transport == null ? null : transport.version);
        if (seed != null) {
            Files.copy(seed.resolve("world.journal"), out.resolve("world.journal"));
            Files.copy(seed.resolve(SceneRoutes.FILE_NAME), out.resolve(SceneRoutes.FILE_NAME));
        }
        var provider = providers.open();
        var issued = new AtomicInteger();
        var openWorld = new java.util.concurrent.atomic.AtomicReference<edu.cufe.auction.world.WorldRuntime>();
        var lengthPolicy = measured ? new MeasuredLengthPolicy(() -> openWorld.get().snapshot().decisions().orElseThrow()) : null;
        var evidence = informed ? new ProbeProviderEvidence(out, thinking, lengthPolicy,transport) : null;
        var activeHost = new java.util.concurrent.atomic.AtomicReference<WorldWebHost>();
        try (var routes = SceneRoutes.open(out)) {
            if (seed == null && routes.view().revision() == 0) routes.replace(0, probeRoutes(routes.view(), config.mode()));
            try (var host = new WorldWebHost(() -> {
                var world = openWorld(config.mode(), out.resolve("world.journal"));
                String targetRun = switch (config.mode()) {
                    case "TEN_COMPANY_MEETING_STUDY" -> edu.cufe.auction.world.RunManifest.MEETING_TEN_COMPANY_RUN_ID;
                    case "TEN_COMPANY_DIGITAL_WIND_DOWN_STUDY" -> edu.cufe.auction.world.RunManifest.DIGITAL_WIND_DOWN_RUN_ID;
                    case "TEN_COMPANY_PERSONAL_NEWS_STUDY" -> edu.cufe.auction.world.RunManifest.PERSONAL_NEWS_RUN_ID;
                    case "TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY" -> edu.cufe.auction.world.RunManifest.INTERVIEW_AUTONOMY_RUN_ID;
                    case "TEN_COMPANY_FEEDBACK_STUDY" -> edu.cufe.auction.world.RunManifest.EDITORIAL_FEEDBACK_RUN_ID;
                    case "TEN_COMPANY_INTERVIEW_STUDY" -> edu.cufe.auction.world.RunManifest.INTERVIEW_TEN_COMPANY_RUN_ID;
                    case "TEN_COMPANY_PERSONAL_STUDY" -> edu.cufe.auction.world.RunManifest.PERSONAL_TEN_COMPANY_RUN_ID;
                    case "TEN_COMPANY_EDITORIAL_STUDY" -> edu.cufe.auction.world.RunManifest.READABLE_DRAFT_TEN_COMPANY_RUN_ID;
                    case "TEN_COMPANY_GOVERNANCE_STUDY" -> edu.cufe.auction.world.RunManifest.GOVERNANCE_TEN_COMPANY_RUN_ID;
                    case "TEN_COMPANY_STUDY" -> edu.cufe.auction.world.RunManifest.STUDY_TEN_COMPANY_AUTONOMY_RUN_ID;
                    default -> edu.cufe.auction.world.RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID;
                };
                if (config.runUntilTarget() && !targetRun.equals(world.profile().manifest().runId())) {
                    world.close();
                    throw new IllegalArgumentException("目标运行与指定十公司实验版本不符");
                }
                openWorld.set(world);
                return new WorldWebHost.Opened(world, world.profile().manifest().runId());
            }, routes, (call, timeout) -> {
                if (evidence != null) return evaluateEvidence(config, evidence, issued, call, timeout, provider, () -> {
                    try { activeHost.get().stopAutonomy(); }
                    catch (Exception stopFailure) { System.err.println("PROBE_STOP_REQUEST_FAILED"); }
                });
                int index = issued.incrementAndGet();
                if (index > config.callLimit()) return limitFailure(call, "PROBE_CALL_LIMIT");
                try {
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("call-" + index + ".json").toFile(), call);
                    var completion = provider.evaluate(call, timeout);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("reply-" + index + ".json").toFile(), completion);
                    System.out.println("completed=" + index + " action=" + call.actionId());
                    return completion;
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            })) {
                activeHost.set(host);
                var inputEvents = new java.util.concurrent.atomic.AtomicLong();
                if (config.runUntilTarget()) {
                    Path path = Files.createFile(out.resolve(INPUT_EVENTS));
                    host.observeInputBlocks(event -> {
                        Files.writeString(path,JSON.writeValueAsString(event) + "\n",java.nio.file.StandardOpenOption.APPEND);
                        inputEvents.incrementAndGet();
                    });
                }
                var initial = host.autonomyStatus();
                if (lengthPolicy != null && seed != null) {
                    lengthPolicy.restore(seed);
                }
                if (!config.runUntilTarget() || !(Files.exists(out.resolve("STOP")) || Files.exists(out.resolve("CHECKPOINT"))))
                    host.startAutonomy(Long.parseLong(initial.position()), routes.view().revision(), config.targetMillis(),
                            config.stepMillis(), config.persons() == null ? java.util.Set.of() : java.util.Set.copyOf(config.persons()));
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(config.wallMinutes());
                while (host.autonomyStatus().running() && issued.get() < config.callLimit()
                        && (config.runUntilTarget() || System.nanoTime() < deadline) && !Files.exists(out.resolve("STOP"))
                        && !(config.runUntilTarget() && Files.exists(out.resolve("CHECKPOINT")))
                        && (evidence == null || !(thinking ? evidence.stopRequested() : evidence.failed()))) {
                    Thread.sleep(500);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("status.json").toFile(), host.autonomyStatus());
                }
                var last = host.autonomyStatus();
                String stop;
                if (config.runUntilTarget() && Files.exists(out.resolve("STOP"))) stop = "OPERATOR_STOP";
                else if (config.runUntilTarget() && "TARGET_REACHED".equals(last.reason())) stop = "TARGET_REACHED";
                else if (config.runUntilTarget() && Files.exists(out.resolve("CHECKPOINT")) && (last.running() || "NOT_STARTED".equals(last.reason())))
                    stop = "SAFE_CHECKPOINT";
                else if (!last.running()) stop = "RUNTIME_STOP";
                else if (Files.exists(out.resolve("STOP"))) stop = "OPERATOR_STOP";
                else stop = issued.get() >= config.callLimit() ? "HTTP_CALL_LIMIT" : "WALL_CLOCK_LIMIT";
                if (host.autonomyStatus().running()) host.stopAutonomy();
                if (config.runUntilTarget()) JSON.writeValue(out.resolve("status.json").toFile(), host.autonomyStatus());
                long drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while ((config.runUntilTarget() ? !host.liveQuiescent() : host.liveStatus().physicalInFlight() > 0)
                        && System.nanoTime() < drain) Thread.sleep(100);
                if (config.runUntilTarget() && !host.liveQuiescent()) stop = "UNSETTLED_REQUESTS";
                if (lengthPolicy != null) evidence.settleMeasuredLengths(null, 1000);
                if (evidence != null && (thinking ? evidence.stopRequested() : evidence.failed())) {
                    stop = evidence.stopReason();
                    System.err.println(stop);
                }
                var result = JSON.createObjectNode().put("probeStop", stop)
                        .put("httpCalls", Math.min(issued.get(), config.callLimit())).put("costStatus", "UNKNOWN");
                if (evidence != null) result.set("providerProvenance",
                        evidence.summary(Math.min(issued.get(), config.callLimit()), host.liveStatus().physicalInFlight()));
                if (config.runUntilTarget()) result.putObject("localInputAudit").put("file",INPUT_EVENTS)
                        .put("events",inputEvents.get()).put("scope","segment").put("providerCalls",0);
                result.set("config", JSON.valueToTree(config));
                result.set("autonomy", JSON.valueToTree(host.autonomyStatus()));
                result.set("live", JSON.valueToTree(host.liveStatus()));
                var companies = result.putObject("companyGovernance");
                for (String company : host.liveCapabilities().governancePersons().stream()
                        .flatMap(p -> p.governanceCompanies().stream()).distinct().sorted().toList())
                    companies.set(company, JSON.valueToTree(host.engineeringView(company)));
                if (config.mode().startsWith("PLANNING") || informed) {
                    // End-of-run auditor output only; never fed into role observations or model requests.
                    result.set("operations", JSON.valueToTree(host.operationsView(null)));
                    result.set("admin", JSON.valueToTree(host.adminView()));
                }
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("result.json").toFile(), result);
                System.out.println("finished=" + host.autonomyStatus().reason() + " http=" + Math.min(issued.get(), config.callLimit()));
            }
        }
        if (measured) MeasuredLengthEvidence.seal(out, seed);
    }

    /** 先检查证据停止边界再分配真实调用序号；已入途结果始终原样返回。 */
    static LiveCompletionInbox.Completion evaluateEvidence(Config config, ProbeProviderEvidence evidence,
            AtomicInteger issued, edu.cufe.auction.world.DecisionRun.Call call, long timeout,
            LiveDecisionProvider provider, Runnable stopScheduling) {
        boolean thinking = thinkingMode(config.mode());
        if (thinking ? evidence.stopRequested() : evidence.failed()) return limitFailure(call, "PROBE_EVIDENCE_STOP");
        int index = evidence.allocate(issued, call.actionId(), timeout);
        if (index == 0) return limitFailure(call, "PROBE_EVIDENCE_STOP");
        if (index > config.callLimit()) return limitFailure(call, "PROBE_CALL_LIMIT");
        var completion = evidence.evaluate(index, call, timeout, provider);
        // The host registers this immutable completion only after evaluate returns. Never
        // wait for its writer here: the evidence flag already blocks further paid HTTP,
        // and the outer probe loop still owns the blocking stop and drain.
        if (thinking && evidence.stopRequested()) java.util.concurrent.CompletableFuture.runAsync(stopScheduling)
                .exceptionally(failure -> { System.err.println("PROBE_STOP_REQUEST_FAILED"); return null; });
        System.out.println("completed=" + index + " action=" + call.actionId());
        return completion;
    }

    private static LiveCompletionInbox.Completion limitFailure(edu.cufe.auction.world.DecisionRun.Call call, String code) {
        return LiveCompletionInbox.Completion.failure(code, new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), "", null, "PROBE_LIMIT"));
    }

    static edu.cufe.auction.world.WorldRuntime openWorld(String mode, Path journal) throws Exception {
        return switch (mode) {
            case "MEDIA_DESKS_OPERATING" -> ComposedIndustryScenario.openMediaDesksOperating(journal);
            case "MARKET_SESSIONS_OPERATING" -> ComposedIndustryScenario.openMarketSessionsOperating(journal);
            case "INTEGRATED_OPERATING" -> ComposedIndustryScenario.openIntegratedOperating(journal);
            case "PRICE_BAND_OPERATING" -> ComposedIndustryScenario.openPriceBandOperating(journal);
            case "ISSUER_DIVIDEND_OPERATING" -> ComposedIndustryScenario.openIssuerDividendOperating(journal);
            case "LOAN_PROMOTION_OPERATING" -> ComposedIndustryScenario.openLoanPromotionOperating(journal);
            case "BASELINE_PARTS_OPERATING" -> ComposedIndustryScenario.openBaselinePartsOperating(journal);
            case "UNPRICED_REFERENCE" -> ComposedIndustryScenario.openUnpricedReferenceOperating(journal, journal.resolveSibling("opening-copies.json"));
            case "OPEN_FUND" -> ComposedIndustryScenario.openOpenFundOperating(journal);
            case "TEN_COMPANY_MEETING_STUDY" -> ComposedIndustryScenario.openMeetingTenCompanyStudy(journal);
            case "TEN_COMPANY_DIGITAL_WIND_DOWN_STUDY" -> ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(journal);
            case "TEN_COMPANY_PERSONAL_NEWS_STUDY" -> ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(journal);
            case "TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY" -> ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(journal);
            case "TEN_COMPANY_FEEDBACK_STUDY" -> ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(journal);
            case "TEN_COMPANY_INTERVIEW_STUDY" -> ComposedIndustryScenario.openInterviewTenCompanyStudy(journal);
            case "TEN_COMPANY_PERSONAL_STUDY" -> ComposedIndustryScenario.openPersonalTenCompanyStudy(journal);
            case "TEN_COMPANY_EDITORIAL_STUDY" -> ComposedIndustryScenario.openEditorialTenCompanyStudy(journal);
            case "TEN_COMPANY_GOVERNANCE_STUDY" -> ComposedIndustryScenario.openCurrentTenCompanyStudy(journal);
            case "TEN_COMPANY_STUDY" -> ComposedIndustryScenario.openStudyTenCompanyAutonomy(journal);
            case "TEN_COMPANY_AUTONOMY" -> ComposedIndustryScenario.openTenCompanyAutonomy(journal);
            case "PERSON_PAUSE" -> ComposedIndustryScenario.openPersonPauseOperating(journal, journal.resolveSibling("opening-copies.json"));
            case "ISSUER_RESOLUTION" -> ComposedIndustryScenario.openIssuerResolutionOperating(journal, journal.resolveSibling("opening-copies.json"));
            case "OWNER_BANK_VALUATION" -> ComposedIndustryScenario.openOwnerBankValuationOperating(
                    journal, journal.resolveSibling("opening-copies.json"));
            case "OPENING_COPIES" -> ComposedIndustryScenario.openOpeningCopiesOperating(
                    journal, journal.resolveSibling("opening-copies.json"));
            case "BANK_CRISIS_OPERATING" -> ComposedIndustryScenario.openBankCrisisOperating(journal);
            case "MANUFACTURING_LIFECYCLE_OPERATING" -> ComposedIndustryScenario.openManufacturingLifecycleOperating(journal);
            case "FUND_OFFICE_ROUTING_OPERATING" -> ComposedIndustryScenario.openFundOfficeRoutingOperating(journal);
            case "FUND_OFFICE_CONTACTS_OPERATING" -> ComposedIndustryScenario.openFundOfficeContactsOperating(journal);
            case "FUND_MANDATE_OPERATING" -> ComposedIndustryScenario.openFundMandateOperating(journal);
            case "PERSONAL_PROFILES_OPERATING" -> ComposedIndustryScenario.openPersonalProfilesOperating(journal);
            case "ISSUER_CREDIT_DECISIONS_OPERATING" -> ComposedIndustryScenario.openIssuerCreditDecisionsOperating(journal);
            case "ISSUER_CREDIT_OPERATING" -> ComposedIndustryScenario.openIssuerCreditOperating(journal);
            case "CONTINUING_ROLES_OPERATING" -> ComposedIndustryScenario.openContinuingRolesOperating(journal);
            case "PERSONAL_LIMIT_OPERATING" -> ComposedIndustryScenario.openPersonalLimitOperating(journal);
            case "HYDRO_SETTLEMENT_OPERATING" -> ComposedIndustryScenario.openHydroSettlementOperating(journal);
            case "ROLE_TABLE_OPERATING" -> ComposedIndustryScenario.openRoleTableOperating(journal);
            case "ISSUER_REPORT_OPERATING" -> ComposedIndustryScenario.openIssuerReportOperating(journal);
            case "BOARD_PROCEDURE_OPERATING" -> ComposedIndustryScenario.openBoardProcedureOperating(journal);
            case "AGED_ORDER_OPERATING" -> ComposedIndustryScenario.openAgedOrderOperating(journal);
            case "FORWARD_BUDGET_OPERATING" -> ComposedIndustryScenario.openForwardBudgetOperating(journal);
            case "COMPANY_INFORMATION_OPERATING" -> ComposedIndustryScenario.openCompanyInformationOperating(journal);
            case "ENGINEERING_CUSTOMER_OPERATING" -> ComposedIndustryScenario.openEngineeringCustomerOperating(journal);
            case "PERSONAL_MARKET_OPERATING" -> ComposedIndustryScenario.openPersonalMarketOperating(journal);
            case "FUNDED_THINKING_OPERATING" -> ComposedIndustryScenario.openFundedThinkingOperating(journal);
            case "THINKING_INFORMED_OPERATING" -> ComposedIndustryScenario.openThinkingInformedOperating(journal);
            case "CONTEXTUAL_INFORMED_OPERATING" -> ComposedIndustryScenario.openContextualInformedOperating(journal);
            case "INFORMED_OPERATING" -> ComposedIndustryScenario.openInformedOperating(journal);
            case "PLANNING_OPERATING" -> ComposedIndustryScenario.openPlanningOperating(journal);
            case "PLANNING" -> ComposedIndustryScenario.openPlanningAutonomous(journal);
            case "OPERATING" -> ComposedIndustryScenario.openOperatingAutonomous(journal);
            default -> ComposedIndustryScenario.openAccountableAutonomous(journal);
        };
    }

    private static boolean informedMode(String mode) {
        return mode.equals("INFORMED_OPERATING") || mode.equals("CONTEXTUAL_INFORMED_OPERATING") || thinkingMode(mode);
    }

    static boolean usesMeasuredLength(String mode) {
        return "ENGINEERING_CUSTOMER_OPERATING".equals(mode) || "COMPANY_INFORMATION_OPERATING".equals(mode) || "FORWARD_BUDGET_OPERATING".equals(mode) || "AGED_ORDER_OPERATING".equals(mode) || "BOARD_PROCEDURE_OPERATING".equals(mode) || "ISSUER_REPORT_OPERATING".equals(mode) || "ROLE_TABLE_OPERATING".equals(mode) || "HYDRO_SETTLEMENT_OPERATING".equals(mode) || "PERSONAL_LIMIT_OPERATING".equals(mode) || "CONTINUING_ROLES_OPERATING".equals(mode) || "ISSUER_CREDIT_OPERATING".equals(mode) || "ISSUER_CREDIT_DECISIONS_OPERATING".equals(mode) || "PERSONAL_PROFILES_OPERATING".equals(mode) || "FUND_MANDATE_OPERATING".equals(mode) || "FUND_OFFICE_CONTACTS_OPERATING".equals(mode) || "FUND_OFFICE_ROUTING_OPERATING".equals(mode)
                || java.util.Set.of("MEDIA_DESKS_OPERATING", "MARKET_SESSIONS_OPERATING", "INTEGRATED_OPERATING", "MANUFACTURING_LIFECYCLE_OPERATING", "BANK_CRISIS_OPERATING", "PRICE_BAND_OPERATING", "ISSUER_DIVIDEND_OPERATING", "LOAN_PROMOTION_OPERATING", "BASELINE_PARTS_OPERATING", "OPENING_COPIES", "OWNER_BANK_VALUATION", "UNPRICED_REFERENCE", "ISSUER_RESOLUTION", "PERSON_PAUSE", "TEN_COMPANY_AUTONOMY", "TEN_COMPANY_STUDY", "TEN_COMPANY_GOVERNANCE_STUDY", "TEN_COMPANY_EDITORIAL_STUDY", "TEN_COMPANY_PERSONAL_STUDY", "TEN_COMPANY_INTERVIEW_STUDY", "TEN_COMPANY_MEETING_STUDY", "TEN_COMPANY_DIGITAL_WIND_DOWN_STUDY", "TEN_COMPANY_FEEDBACK_STUDY", "TEN_COMPANY_PERSONAL_NEWS_STUDY", "TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY", "OPEN_FUND").contains(mode);
    }

    static boolean thinkingMode(String mode) { return mode.equals("THINKING_INFORMED_OPERATING") || mode.equals("FUNDED_THINKING_OPERATING") || mode.equals("PERSONAL_MARKET_OPERATING") || usesMeasuredLength(mode); }

    static java.util.List<SceneRoutes.Route> probeRoutes(SceneRoutes.Snapshot original, String mode) {
        boolean thinking = thinkingMode(mode);
        boolean longStudy = java.util.Set.of("TEN_COMPANY_AUTONOMY", "TEN_COMPANY_STUDY", "TEN_COMPANY_GOVERNANCE_STUDY", "TEN_COMPANY_EDITORIAL_STUDY", "TEN_COMPANY_PERSONAL_STUDY", "TEN_COMPANY_INTERVIEW_STUDY", "TEN_COMPANY_MEETING_STUDY", "TEN_COMPANY_DIGITAL_WIND_DOWN_STUDY", "TEN_COMPANY_FEEDBACK_STUDY", "TEN_COMPANY_PERSONAL_NEWS_STUDY", "TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY", "OPEN_FUND").contains(mode);
        String policy = longStudy ? DecisionModel.Route.LONG_HORIZON : "lifecycle-v1";
        return original.routes().stream().map(r -> new SceneRoutes.Route(r.scene(), r.slotId(), "deepseek-flash", true,
                longStudy ? DecisionModel.Route.callCeiling(policy) : 2500,
                longStudy ? DecisionModel.Route.tokenCeiling(policy) : 35_000_000, thinking ? 8192 : 2048, 4, policy,
                thinking ? DeepSeekClient.THINKING_HIGH_POLICY : null)).toList();
    }

    static String systemPrompt(Config config) {
        if (java.util.Set.of("OPENING_COPIES","OWNER_BANK_VALUATION", "UNPRICED_REFERENCE", "ISSUER_RESOLUTION", "PERSON_PAUSE", "TEN_COMPANY_AUTONOMY", "TEN_COMPANY_STUDY", "TEN_COMPANY_GOVERNANCE_STUDY", "TEN_COMPANY_EDITORIAL_STUDY", "TEN_COMPANY_PERSONAL_STUDY", "TEN_COMPANY_INTERVIEW_STUDY", "TEN_COMPANY_MEETING_STUDY", "TEN_COMPANY_DIGITAL_WIND_DOWN_STUDY", "TEN_COMPANY_FEEDBACK_STUDY", "TEN_COMPANY_PERSONAL_NEWS_STUDY", "TEN_COMPANY_INTERVIEW_AUTONOMY_STUDY", "OPEN_FUND").contains(config.mode())) return LiveDecisionProvider.DECLARED_FIELDS_SYSTEM_TEXT;
        if (java.util.Set.of("INTEGRATED_OPERATING", "MANUFACTURING_LIFECYCLE_OPERATING", "BANK_CRISIS_OPERATING", "PRICE_BAND_OPERATING", "ISSUER_DIVIDEND_OPERATING", "LOAN_PROMOTION_OPERATING", "BASELINE_PARTS_OPERATING").contains(config.mode()) || "PERSONAL_PROFILES_OPERATING".equals(config.mode()) || "FUND_MANDATE_OPERATING".equals(config.mode()) || "FUND_OFFICE_CONTACTS_OPERATING".equals(config.mode()) || "FUND_OFFICE_ROUTING_OPERATING".equals(config.mode())) return LiveDecisionProvider.PERSONAL_PROFILE_SYSTEM_TEXT;
        if (java.util.Set.of("MEDIA_DESKS_OPERATING", "MARKET_SESSIONS_OPERATING").contains(config.mode()) || "CONTINUING_ROLES_OPERATING".equals(config.mode()) || "ISSUER_CREDIT_OPERATING".equals(config.mode()) || "ISSUER_CREDIT_DECISIONS_OPERATING".equals(config.mode())) return LiveDecisionProvider.RESEARCH_NOTE_SYSTEM_TEXT;
        return (config.mode().startsWith("PLANNING") || informedMode(config.mode())) ? LiveDecisionProvider.PLANNING_SYSTEM_TEXT
                : LiveDecisionProvider.DELIBERATION_SYSTEM_TEXT;
    }

    static Path validateSeed(Config requested) {
        Path seed = Path.of(requested.seed());
        try {
            for (String name : new String[]{"config.json", "result.json", "system-prompt.txt",
                    "world.journal", SceneRoutes.FILE_NAME}) {
                if (!Files.isRegularFile(seed.resolve(name)))
                    throw new IllegalArgumentException("种子缺少完整冻结文件：" + name);
            }
            var seedConfig = JSON.readTree(Files.readString(seed.resolve("config.json")));
            var result = JSON.readTree(Files.readString(seed.resolve("result.json")));
            if (seedConfig == null || !seedConfig.isObject() || result == null || !result.isObject())
                throw new IllegalArgumentException("种子冻结配置不合法");
            Config frozen = JSON.treeToValue(seedConfig, Config.class);
            if (!seedConfig.equals(JSON.readTree(JSON.writeValueAsBytes(frozen))) || !seedConfig.equals(result.path("config")))
                throw new IllegalArgumentException("种子 config.json 与 result.config 必须完整且一致");
            if (requested.runUntilTarget()) {
                String reason = result.path("probeStop").asText();
                boolean checkpoint = "SAFE_CHECKPOINT".equals(reason) && requested.targetMillis() >= frozen.targetMillis();
                boolean extension = "TARGET_REACHED".equals(reason) && requested.targetMillis() > frozen.targetMillis();
                if (!frozen.runUntilTarget() || !(checkpoint || extension)
                        || !java.util.Objects.equals(frozen.persons(), requested.persons())
                        || !result.at("/live/fault").isNull() || result.at("/live/completionSlots").asInt(-1) != 0)
                    throw new IllegalArgumentException("目标运行仅从同范围的已结清安全停点或达标世界接续");
            }
            if (!requested.mode().equals(frozen.mode()) || !requested.sourceCommit().equals(frozen.sourceCommit())
                    || !systemPrompt(requested).equals(
                            Files.readString(seed.resolve("system-prompt.txt"))))
                throw new IllegalArgumentException("种子的模式、源码和系统提示词必须与当前试跑完全相同");
            var inFlight = result.path("live").path("physicalInFlight");
            if (!inFlight.isIntegralNumber() || !inFlight.canConvertToInt() || inFlight.intValue() != 0)
                throw new IllegalArgumentException("种子尚有未结算的物理请求");
            try (var paths = Files.list(seed)) {
                for (Path call : paths.filter(p -> p.getFileName().toString().matches("call-[0-9]+[.]json")).toList())
                    if (!Files.isRegularFile(seed.resolve(call.getFileName().toString().replace("call-", "reply-"))))
                        throw new IllegalArgumentException("种子存在结果未知的请求");
            }
            if (informedMode(requested.mode())) ProbeProviderEvidence.validateSeed(seed, result, thinkingMode(requested.mode()),
                    usesMeasuredLength(requested.mode()));
            if (thinkingMode(requested.mode())) {
                try (var routes = SceneRoutes.open(seed)) {
                    if (!routes.view().routes().equals(probeRoutes(routes.view(), requested.mode())))
                        throw new IllegalArgumentException("种子的思考路由不符合冻结策略");
                }
            }
            return seed;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("种子冻结文件不可读或格式不合法", invalid);
        }
    }

}
