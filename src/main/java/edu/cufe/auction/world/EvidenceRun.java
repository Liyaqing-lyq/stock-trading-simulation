package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 匿名私有证据的运行状态：一份冻结配置 + 每个媒体机构一份纯核证据状态。
 *
 * <p>只保存别名与身份共享绑定；不保存正文、时钟、阅读记录、来源账本或工时账本。
 * 工时仍只走 {@link MediaRun} 的机构预算。旧 schema 不使用本类（字段为 null 且不序列化）。</p>
 */
public record EvidenceRun(Config config,
                          @JsonInclude(JsonInclude.Include.NON_EMPTY)
                          Map<String, MediaEvidence.State> states) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /**
     * 本实验独立冻结的配置。
     *
     * @param shareMillis 一次身份共享占用的机构工时（正数，实验参数）
     */
    public record Config(long shareMillis) {

        /** 校验正时长。 */
        public Config {
            if (shareMillis <= 0) {
                throw new IllegalArgumentException("共享工时必须为正：" + shareMillis);
            }
        }
    }

    /** 冻结映射并校验组织标识。 */
    public EvidenceRun {
        Objects.requireNonNull(config, "证据配置");
        Map<String, MediaEvidence.State> copy = new TreeMap<>();
        if (states != null) {
            for (Map.Entry<String, MediaEvidence.State> entry : states.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException("证据状态组织标识不能为空");
                }
                copy.put(entry.getKey(),
                        entry.getValue() == null ? MediaEvidence.empty() : entry.getValue());
            }
        }
        states = java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * 开局：为每个媒体机构建空证据状态。
     *
     * @param config        冻结配置
     * @param organizations 媒体机构标识
     * @return 空证据运行状态
     */
    public static EvidenceRun open(Config config, List<String> organizations) {
        Objects.requireNonNull(config, "证据配置");
        Map<String, MediaEvidence.State> states = new LinkedHashMap<>();
        if (organizations != null) {
            for (String organization : organizations) {
                states.put(organization, MediaEvidence.empty());
            }
        }
        return new EvidenceRun(config, states);
    }

    /**
     * 某机构的证据状态；未知机构返回空状态，不泄露存在性。
     *
     * @param organizationPartyId 媒体机构
     * @return 证据状态（非 null）
     */
    public MediaEvidence.State stateOf(String organizationPartyId) {
        if (organizationPartyId == null) {
            return MediaEvidence.empty();
        }
        return states.getOrDefault(organizationPartyId, MediaEvidence.empty());
    }

    /**
     * 替换某机构的证据状态。
     *
     * @param organizationPartyId 媒体机构
     * @param next                新状态
     * @return 新运行状态
     */
    public EvidenceRun withState(String organizationPartyId, MediaEvidence.State next) {
        Map<String, MediaEvidence.State> copy = new LinkedHashMap<>(states);
        copy.put(organizationPartyId, next == null ? MediaEvidence.empty() : next);
        return new EvidenceRun(config, copy);
    }

    /**
     * 序列化；只含配置与各机构证据状态。
     *
     * @return JSON 文本
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception failure) {
            throw new IllegalStateException("证据状态序列化失败", failure);
        }
    }

    /**
     * 从 JSON 恢复；结构不合法一律拒绝。
     *
     * @param json JSON 文本
     * @return 恢复后的状态
     */
    public static EvidenceRun fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("证据状态 JSON 不能为空");
        }
        try {
            EvidenceRun run = MAPPER.readValue(json, EvidenceRun.class);
            if (run == null) {
                throw new IllegalArgumentException("证据状态 JSON 为空");
            }
            return run;
        } catch (IllegalArgumentException semantic) {
            throw semantic;
        } catch (Exception failure) {
            throw new IllegalArgumentException("证据状态 JSON 无法解析", failure);
        }
    }

    /**
     * 各机构共享绑定的扁平列表（管理员审计用）。
     *
     * @return 机构 → 共享列表
     */
    public Map<String, List<MediaEvidence.IdentityShare>> sharesByOrganization() {
        Map<String, List<MediaEvidence.IdentityShare>> result = new TreeMap<>();
        for (Map.Entry<String, MediaEvidence.State> entry : states.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue().shares()));
        }
        return result;
    }

    /**
     * 各机构别名的扁平列表（管理员审计用）。
     *
     * @return 机构 → 别名列表
     */
    public Map<String, List<MediaEvidence.AliasBinding>> aliasesByOrganization() {
        Map<String, List<MediaEvidence.AliasBinding>> result = new TreeMap<>();
        for (Map.Entry<String, MediaEvidence.State> entry : states.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue().aliases()));
        }
        return result;
    }
}
