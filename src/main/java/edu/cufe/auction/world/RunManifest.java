package edu.cufe.auction.world;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 扩展世界的运行清单：把运行、规则、模块、资料与模型版本在开局第一个提交里固定下来。
 *
 * <p>当前启用八种明确组合：纯现金、现金 + 开局登记、现金 + 开局登记 + 证券清算夹具、
 * 现金 + 开局登记 + 证券台账 + 世界订单撮合、现金 + 登记 + 证券 + 撮合 + 制造经营、
 * 现金 + 登记 + 证券 + 撮合 + 制造 + 董事会治理、在此基础上再加任职与资料阅读的
 * {@code informed-stock-company-experiment-v1}，以及再加四岗交易工作流的
 * {@code role-trading-experiment-v1}。模块表与规则档案一一对应（现金
 * {@code {cash:1, scheduler:1}}、登记再加 {@code registry:1}、证券再加 {@code securities:1}、
 * 撮合再加 {@code matching:1}、制造再加 {@code manufacturing:1}、治理再加 {@code governance:1}、
 * 任职与阅读再加 {@code roles:1, information:1}、四岗交易再加 {@code trading:1}），
 * 模型版本必须是 {@link #MODEL_NONE}。任何未启用的能力在构造期就被拒绝，避免运行到一半才发现
 * 缺少实现。</p>
 *
 * @param runId              运行标识；同一日志文件只能对应同一运行
 * @param seed               调度公平顺序种子
 * @param rulesVersion       规则档案版本
 * @param moduleVersions     已启用模块及版本
 * @param dataVersions       资料版本表；现金子集为空表（诚实标空，不填假值）
 * @param modelConfigVersion 模型配置版本；现金子集为 {@link #MODEL_NONE}
 */
public record RunManifest(String runId, long seed, String rulesVersion,
                          Map<String, Integer> moduleVersions,
                          Map<String, String> dataVersions,
                          String modelConfigVersion) {

    /** 现金子集规则档案版本（旧组合，保持完全兼容）。 */
    public static final String RULES_CASH_WORLD_V1 = "cash-world-v1";

    /** 现金 + 开局登记规则档案版本（M2.2 新增的明确组合）。 */
    public static final String RULES_CASH_REGISTRY_WORLD_V1 = "cash-registry-world-v1";

    /** 现金 + 开局登记 + 证券清算夹具（M2.3 新增的明确组合，仍不含撮合与角色交易授权）。 */
    public static final String RULES_SECURITIES_CLEARING_EXPERIMENT_V1 =
            "securities-clearing-experiment-v1";

    /** 现金 + 开局登记 + 证券台账 + 世界订单撮合（M2.4b 新增的明确组合）。 */
    public static final String RULES_SECURITIES_MATCHING_EXPERIMENT_V1 =
            "securities-matching-experiment-v1";

    /** 现金 + 登记 + 证券 + 撮合 + 制造经营（M3a.2 新增的明确组合，model=none）。 */
    public static final String RULES_STOCK_COMPANY_EXPERIMENT_V1 = "stock-company-experiment-v1";

    /** 现金 + 登记 + 证券 + 撮合 + 制造 + 治理（M3b.2 新增的明确组合，model=none）。 */
    public static final String RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1 =
            "governed-stock-company-experiment-v1";

    /** 在治理档案之上再加任职授权与资料阅读（M4a.2/M4b.2 新增的明确组合，model=none）。 */
    public static final String RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1 =
            "informed-stock-company-experiment-v1";

    /** 在任职与阅读档案之上再加四岗交易工作流（M4c 新增的明确组合，model=none）。 */
    public static final String RULES_ROLE_TRADING_EXPERIMENT_V1 = "role-trading-experiment-v1";

    /** 在四岗交易档案之上再加决定屏障与尝试（M4d 新增的明确组合，scripted 模型）。 */
    public static final String RULES_DECISION_EXPERIMENT_V1 = "decision-experiment-v1";

    /** 在决定档案之上再加现金分红世界桥（M3d D3b 新增的明确组合，scripted 模型）。 */
    public static final String RULES_DIVIDEND_WORLD_EXPERIMENT_V1 = "dividend-world-experiment-v1";

    /** B2b：在分红档案之上接入真实模型持久派发（schema10，live 模型配置）。 */
    public static final String RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1 =
            "live-decision-world-experiment-v1";

    /** M4f：在 live 决定档案之上接入已有经历的有界回忆（schema11，仍为 live 模型配置）。 */
    public static final String RULES_COGNITION_RECALL_EXPERIMENT_V1 =
            "cognition-recall-experiment-v1";

    /** M4g.2：在回忆档案之上接入主观信念、来源可靠性与慢习惯（schema12，仍为 live 模型配置）。 */
    public static final String RULES_COGNITION_BELIEF_EXPERIMENT_V1 =
            "cognition-belief-experiment-v1";

    /** M4h.2：在信念档案之上接入双人采编运行桥与组织工时预算（schema13）。 */
    public static final String RULES_MEDIA_EDITORIAL_EXPERIMENT_V1 =
            "media-editorial-experiment-v1";

    /** live 决定档案唯一允许的模型配置版本；与真实 HTTP 提供方严格成对。 */
    public static final String LIVE_DECISIONS_V1 = "live-decisions-v1";

    /** 未接入模型时使用的模型配置版本。 */
    public static final String MODEL_NONE = "none";

    /** 决定档案唯一允许的模型配置版本：具体 scripted 提供方，不接网络。 */
    public static final String SCRIPTED_DECISIONS_V1 = "scripted-decisions-v1";

    /**
     * 是否启用开局登记模块。
     *
     * @return 模块表包含 registry 时返回 true
     */
    public boolean hasRegistry() {
        return moduleVersions.containsKey("registry");
    }

    /**
     * 是否启用证券清算夹具模块。
     *
     * @return 模块表包含 securities 时返回 true
     */
    public boolean hasSecurities() {
        return moduleVersions.containsKey("securities");
    }

    /**
     * 是否启用世界订单撮合模块。
     *
     * @return 模块表包含 matching 时返回 true
     */
    public boolean hasMatching() {
        return moduleVersions.containsKey("matching");
    }

    /**
     * 是否启用制造经营模块。
     *
     * @return 模块表包含 manufacturing 时返回 true
     */
    public boolean hasManufacturing() {
        return moduleVersions.containsKey("manufacturing");
    }

    /**
     * 是否启用董事会治理模块。
     *
     * @return 模块表包含 governance 时返回 true
     */
    public boolean hasGovernance() {
        return moduleVersions.containsKey("governance");
    }

    /**
     * 是否启用任职授权模块。
     *
     * @return 模块表包含 roles 时返回 true
     */
    public boolean hasRoles() {
        return moduleVersions.containsKey("roles");
    }

    /**
     * 是否启用资料阅读模块。
     *
     * @return 模块表包含 information 时返回 true
     */
    public boolean hasInformation() {
        return moduleVersions.containsKey("information");
    }

    /**
     * 是否启用四岗交易模块。
     *
     * @return 模块表包含 trading 时返回 true
     */
    public boolean hasTrading() {
        return moduleVersions.containsKey("trading");
    }

    /**
     * 是否启用决定屏障与尝试模块。
     *
     * @return 模块表包含 decisions 时返回 true
     */
    public boolean hasDecisions() {
        return moduleVersions.containsKey("decisions");
    }

    /**
     * 是否启用现金分红世界桥模块。
     *
     * @return 模块表包含 dividend 时返回 true
     */
    public boolean hasDividend() {
        return moduleVersions.containsKey("dividend");
    }

    /**
     * 是否启用真实模型持久派发。
     *
     * @return 模块表包含 live-decisions 时返回 true
     */
    public boolean hasLiveDecisions() {
        return moduleVersions.containsKey("live-decisions");
    }

    /**
     * 是否启用已有经历的有界回忆。
     *
     * @return 模块表包含 cognition-recall 时返回 true
     */
    public boolean hasCognitionRecall() {
        return moduleVersions.containsKey("cognition-recall");
    }

    /**
     * 是否启用主观信念与慢习惯子集。
     *
     * @return 模块表包含 belief 时返回 true
     */
    public boolean hasBelief() {
        return moduleVersions.containsKey("belief");
    }

    /**
     * 是否启用媒体采编运行桥子集。
     *
     * @return 模块表包含 media 时返回 true
     */
    public boolean hasMedia() {
        return moduleVersions.containsKey("media");
    }

    /** 校验并冻结清单；不可变、可稳定序列化。 */
    public RunManifest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("运行标识不能为空");
        }
        if (!RULES_CASH_WORLD_V1.equals(rulesVersion)
                && !RULES_CASH_REGISTRY_WORLD_V1.equals(rulesVersion)
                && !RULES_SECURITIES_CLEARING_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_SECURITIES_MATCHING_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_ROLE_TRADING_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_DECISION_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_DIVIDEND_WORLD_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_COGNITION_RECALL_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_COGNITION_BELIEF_EXPERIMENT_V1.equals(rulesVersion)
                && !RULES_MEDIA_EDITORIAL_EXPERIMENT_V1.equals(rulesVersion)) {
            throw new IllegalArgumentException("当前只支持规则档案 " + RULES_CASH_WORLD_V1
                    + "、" + RULES_CASH_REGISTRY_WORLD_V1 + "、"
                    + RULES_SECURITIES_CLEARING_EXPERIMENT_V1 + "、"
                    + RULES_SECURITIES_MATCHING_EXPERIMENT_V1 + "、"
                    + RULES_STOCK_COMPANY_EXPERIMENT_V1 + "、"
                    + RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1 + "、"
                    + RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1 + "、"
                    + RULES_ROLE_TRADING_EXPERIMENT_V1 + " 或 "
                    + RULES_DECISION_EXPERIMENT_V1 + "，收到 " + rulesVersion);
        }
        Map<String, Integer> modules = new TreeMap<>();
        if (moduleVersions != null) {
            moduleVersions.forEach((name, version) -> {
                if (name == null || name.isBlank() || version == null || version <= 0) {
                    throw new IllegalArgumentException("模块名或版本非法：" + name + "=" + version);
                }
                modules.put(name, version);
            });
        }
        Map<String, Integer> expected = new TreeMap<>();
        expected.put("cash", 1);
        expected.put("scheduler", 1);
        boolean withMedia = RULES_MEDIA_EDITORIAL_EXPERIMENT_V1.equals(rulesVersion);
        boolean withBelief = withMedia || RULES_COGNITION_BELIEF_EXPERIMENT_V1.equals(rulesVersion);
        boolean withCognition = withBelief
                || RULES_COGNITION_RECALL_EXPERIMENT_V1.equals(rulesVersion);
        boolean withLive = withCognition
                || RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1.equals(rulesVersion);
        boolean withDividend = withLive
                || RULES_DIVIDEND_WORLD_EXPERIMENT_V1.equals(rulesVersion);
        boolean withTrading = withLive
                || RULES_ROLE_TRADING_EXPERIMENT_V1.equals(rulesVersion)
                || RULES_DECISION_EXPERIMENT_V1.equals(rulesVersion)
                || withDividend;
        boolean withRoles = withLive
                || RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                || withTrading;
        boolean withGovernance = withLive
                || RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                || withRoles;
        boolean withManufacturing = withLive
                || RULES_STOCK_COMPANY_EXPERIMENT_V1.equals(rulesVersion)
                || withGovernance;
        boolean withMatching = withLive
                || RULES_SECURITIES_MATCHING_EXPERIMENT_V1.equals(rulesVersion)
                || withManufacturing;
        boolean withSecurities = withLive
                || RULES_SECURITIES_CLEARING_EXPERIMENT_V1.equals(rulesVersion)
                || withMatching;
        boolean withRegistry = withLive
                || RULES_CASH_REGISTRY_WORLD_V1.equals(rulesVersion)
                || withSecurities;
        if (withRegistry) {
            expected.put("registry", 1);
        }
        if (withSecurities) {
            expected.put("securities", 1);
        }
        if (withMatching) {
            expected.put("matching", 1);
        }
        if (withManufacturing) {
            expected.put("manufacturing", 1);
        }
        if (withGovernance) {
            expected.put("governance", 1);
        }
        if (withRoles) {
            expected.put("information", 1);
            expected.put("roles", 1);
        }
        if (withTrading) {
            expected.put("trading", 1);
        }
        if (RULES_DECISION_EXPERIMENT_V1.equals(rulesVersion) || withDividend) {
            expected.put("decisions", 1);
        }
        if (withDividend) {
            expected.put("dividend", 1);
        }
        if (withLive) {
            expected.put("live-decisions", 1);
        }
        if (withCognition) {
            expected.put("cognition-recall", 1);
        }
        if (withBelief) {
            expected.put("belief", 1);
        }
        if (withMedia) {
            expected.put("media", 1);
        }
        if (!expected.equals(modules)) {
            throw new IllegalArgumentException("规则档案 " + rulesVersion + " 需要模块 "
                    + expected + "，收到 " + modules);
        }
        moduleVersions = Collections.unmodifiableMap(modules);

        Map<String, String> data = new TreeMap<>();
        if (dataVersions != null) {
            dataVersions.forEach((name, version) -> {
                if (name == null || name.isBlank() || version == null || version.isBlank()) {
                    throw new IllegalArgumentException("资料版本非法：" + name + "=" + version);
                }
                data.put(name, version);
            });
        }
        dataVersions = Collections.unmodifiableMap(data);

        if (withLive) {
            if (!LIVE_DECISIONS_V1.equals(modelConfigVersion)) {
                throw new IllegalArgumentException("live 决定档案的模型配置版本必须是 "
                        + LIVE_DECISIONS_V1 + "，收到 " + modelConfigVersion);
            }
        } else if (RULES_DECISION_EXPERIMENT_V1.equals(rulesVersion)
                || RULES_DIVIDEND_WORLD_EXPERIMENT_V1.equals(rulesVersion)) {
            if (!SCRIPTED_DECISIONS_V1.equals(modelConfigVersion)) {
                throw new IllegalArgumentException("决定档案的模型配置版本必须是 "
                        + SCRIPTED_DECISIONS_V1 + "，收到 " + modelConfigVersion);
            }
        } else if (!MODEL_NONE.equals(modelConfigVersion)) {
            throw new IllegalArgumentException("当前未接入模型，模型配置版本必须是 "
                    + MODEL_NONE + "，收到 " + modelConfigVersion);
        }
    }
}
