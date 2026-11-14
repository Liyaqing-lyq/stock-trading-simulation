package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 周期人物动作参数：准备、评分、批准、授权、发布共用一份不可变载荷。
 *
 * <p>只承载意图与显式参数；公司、席位、经理身份、实读与回忆一律由运行层按真实世界状态复核，
 * 模型不能自授。</p>
 *
 * @param commandId         原始命令标识
 * @param companyPartyId    公司
 * @param personPartyId     动作人物
 * @param action            PREPARE / SCORE / APPROVE / AUTHORIZE / PUBLISH
 * @param period            期间序号
 * @param draftId           目标草稿；准备时为空
 * @param draftVersion      目标草稿版本
 * @param publisherPersonId 授权指定的发布人
 * @param expiresAt         授权失效时刻（右开）
 * @param authorizationId   被消费的授权；发布时为空则取该草稿唯一未消费授权
 * @param metrics           评分五维输入
 * @param note              更正说明；首版必须为空
 */
public record PeriodicActionPayload(String commandId, String companyPartyId, String personPartyId,
                                    String action, long period, String draftId, long draftVersion,
                                    String publisherPersonId, long expiresAt,
                                    String authorizationId, List<PeriodicMetric> metrics,
                                    String note) {

    /** 单维评分输入：维度、分数、真实证据引用与时点。 */
    public record PeriodicMetric(String dimension, BigDecimal score, String sourceRef, long asOf) {

        /** 校验字段。 */
        public PeriodicMetric {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("评分维度不能为空");
            }
            Objects.requireNonNull(score, "评分");
            if (sourceRef == null || sourceRef.isBlank()) {
                throw new IllegalArgumentException("评分证据引用不能为空");
            }
            if (asOf < 0) {
                throw new IllegalArgumentException("证据时点不能为负：" + asOf);
            }
        }
    }

    /** 归一化可选字符串并校验必填字段。 */
    public PeriodicActionPayload {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("周期动作命令不能为空");
        }
        if (companyPartyId == null || companyPartyId.isBlank()) {
            throw new IllegalArgumentException("周期动作公司不能为空");
        }
        if (personPartyId == null || personPartyId.isBlank()) {
            throw new IllegalArgumentException("周期动作人物不能为空");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("周期动作种类不能为空");
        }
        if (period < 1) {
            throw new IllegalArgumentException("周期期间必须从 1 开始：" + period);
        }
        draftId = draftId == null ? "" : draftId;
        publisherPersonId = publisherPersonId == null ? "" : publisherPersonId;
        authorizationId = authorizationId == null ? "" : authorizationId;
        note = note == null ? "" : note;
        metrics = List.copyOf(metrics == null ? List.of() : new ArrayList<>(metrics));
    }
}
