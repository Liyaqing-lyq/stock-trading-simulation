package edu.cufe.auction.world;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 扩展世界的运行清单：把运行、规则、模块、资料与模型版本在开局第一个提交里固定下来。
 *
 * <p>当前只启用现金子集，因此 {@code rulesVersion} 必须是 {@link #RULES_CASH_WORLD_V1}，
 * 模块表必须是 {@code {cash:1, scheduler:1}}，模型版本必须是 {@link #MODEL_NONE}。
 * 任何未启用的能力在构造期就被拒绝，避免运行到一半才发现缺少实现。</p>
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

    /** 未接入模型时使用的模型配置版本。 */
    public static final String MODEL_NONE = "none";

    /**
     * 是否启用开局登记模块。
     *
     * @return 模块表包含 registry 时返回 true
     */
    public boolean hasRegistry() {
        return moduleVersions.containsKey("registry");
    }

    /** 校验并冻结清单；不可变、可稳定序列化。 */
    public RunManifest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("运行标识不能为空");
        }
        if (!RULES_CASH_WORLD_V1.equals(rulesVersion)
                && !RULES_CASH_REGISTRY_WORLD_V1.equals(rulesVersion)) {
            throw new IllegalArgumentException("当前只支持规则档案 " + RULES_CASH_WORLD_V1
                    + " 或 " + RULES_CASH_REGISTRY_WORLD_V1 + "，收到 " + rulesVersion);
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
        if (RULES_CASH_REGISTRY_WORLD_V1.equals(rulesVersion)) {
            expected.put("registry", 1);
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

        if (!MODEL_NONE.equals(modelConfigVersion)) {
            throw new IllegalArgumentException("当前未接入模型，模型配置版本必须是 "
                    + MODEL_NONE + "，收到 " + modelConfigVersion);
        }
    }
}
