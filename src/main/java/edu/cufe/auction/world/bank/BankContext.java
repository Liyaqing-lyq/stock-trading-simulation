package edu.cufe.auction.world.bank;

/**
 * 一次纯核调用绑定的运行上下文。
 *
 * @param runId             运行标识
 * @param configFingerprint 冻结档案指纹
 * @param position          当前世界提交位置（只读，不写）
 * @param atMillis          模拟时点
 */
public record BankContext(String runId, String configFingerprint, long position, long atMillis) {
    /** 校验必要字段。 */
    public BankContext {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("运行标识不能为空");
        }
        if (configFingerprint == null || configFingerprint.isBlank()) {
            throw new IllegalArgumentException("档案指纹不能为空");
        }
        if (position < 0 || atMillis < 0) {
            throw new IllegalArgumentException("位置与时点不能为负");
        }
    }
}
