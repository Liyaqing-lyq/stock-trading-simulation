package edu.cufe.auction.world;

import java.util.Objects;

/**
 * 独立分支首帧携带的派生来源：从哪一条已验父运行、哪一个确认位置派生，以及这次派生的策略。
 *
 * <p>这里记录的父前缀 SHA-256 是<b>父档原始字节</b>的摘要（{@code [0, 第 p 帧末]}），只用来说明
 * "读到的就是当初派生时的那一段"，<b>不是</b>有效性依据：有效性来自用同一校验链重放父前缀后
 * 重新执行的 {@code derive} 与子档首帧逐字段比较。摘要对不上说明父档被截断或替换，必须拒绝；
 * 摘要本身正确但父档语义不合法（例如被重签过），同样在重放期被拒绝。</p>
 *
 * <p>确定性口径：两次创建同一父位置的子档，{@code derive(父状态, 子清单, 策略)} 必须逐字节相同；
 * 但首帧整体必然不同，因为 {@code createdAtEpochMillis} 与 {@code note} 是元数据。</p>
 *
 * @param parentRunId            父运行标识
 * @param parentPosition         切点位置 p；子档首帧位置必须是 p+1
 * @param parentPrefixSha256     父档 {@code [0, 第 p 帧末]} 原始字节的 SHA-256，小写十六进制
 * @param parentManifestDigest   父运行清单规范化 JSON 的 SHA-256，小写十六进制
 * @param childRunId             子运行标识；必须与子清单、子档首帧的其余身份一致
 * @param kind                   分支种类（God 接管 / 操作员派生）
 * @param note                   人类可读注记；只进元数据，不参与派生
 * @param createdAtEpochMillis   创建墙钟毫秒；只进元数据，不参与派生
 * @param policy                 派生策略（种子保持与权益基准）
 * @param parentPrefixBytes      封存父前缀的精确字节数；封存件长度必须等于它
 * @param profileVersion         可信画像摘要口径版本；当前只支持 {@link BranchProfile#VERSION}
 * @param profileDigest          完整可信画像（含全部已启用模块配置）规范化 JSON 的 SHA-256
 */
public record BranchOrigin(String parentRunId, long parentPosition, String parentPrefixSha256,
                           String parentManifestDigest, String childRunId, Kind kind, String note,
                           long createdAtEpochMillis, Policy policy, long parentPrefixBytes,
                           String profileVersion, String profileDigest) {

    /** 分支种类。 */
    public enum Kind {
        /** God 查看秘密后的人工接管：子档页面必须标注"受干预"，父档标签不变。 */
        GOD_TAKEOVER,
        /** 操作员在已验边界上派生的一条平行实验。 */
        OPERATOR_BRANCH
    }

    /**
     * 派生策略：只允许"同一实验条件的平行实验"，不允许悄悄换种子或重置权益。
     *
     * @param seed     公平排序种子；必须与父运行清单完全一致
     * @param baseline 权益基准口径；当前唯一支持 {@link #BASELINE_INHERITED_OPENING_EQUITY}
     */
    public record Policy(long seed, String baseline) {

        /**
         * 唯一支持的权益基准：初始权益沿用父运行开局，不因资本流、分红或业务盈亏重置。
         */
        public static final String BASELINE_INHERITED_OPENING_EQUITY = "inherited-opening-equity";

        /** 校验基准口径。 */
        public Policy {
            if (!BASELINE_INHERITED_OPENING_EQUITY.equals(baseline)) {
                throw new IllegalArgumentException("不支持的分支权益基准：" + baseline);
            }
        }

        /**
         * 按继承口径构造策略。
         *
         * @param seed 父运行种子
         * @return 策略
         */
        public static Policy inherited(long seed) {
            return new Policy(seed, BASELINE_INHERITED_OPENING_EQUITY);
        }
    }

    /** 校验身份与摘要形状；不做业务判定。 */
    public BranchOrigin {
        if (parentRunId == null || parentRunId.isBlank()) {
            throw new IllegalArgumentException("分支父运行标识不能为空");
        }
        if (parentPosition < 1) {
            throw new IllegalArgumentException("分支切点位置必须从 1 开始：" + parentPosition);
        }
        if (parentPrefixSha256 == null || !parentPrefixSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("父前缀摘要必须是 64 位小写十六进制");
        }
        if (parentManifestDigest == null || !parentManifestDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("父清单摘要必须是 64 位小写十六进制");
        }
        if (childRunId == null || childRunId.isBlank()) {
            throw new IllegalArgumentException("分支子运行标识不能为空");
        }
        if (childRunId.equals(parentRunId)) {
            throw new IllegalArgumentException("分支子运行不能复用父标识：" + childRunId);
        }
        Objects.requireNonNull(kind, "kind");
        note = note == null ? "" : note;
        if (createdAtEpochMillis < 0) {
            throw new IllegalArgumentException("分支创建时刻不能为负：" + createdAtEpochMillis);
        }
        Objects.requireNonNull(policy, "policy");
        if (parentPrefixBytes < 1) {
            throw new IllegalArgumentException("封存父前缀字节数必须为正：" + parentPrefixBytes);
        }
        if (!BranchProfile.VERSION.equals(profileVersion)) {
            throw new IllegalArgumentException("不支持的分支画像摘要口径：" + profileVersion);
        }
        if (profileDigest == null || !profileDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("画像摘要必须是 64 位小写十六进制");
        }
    }
}
