package edu.cufe.auction.world;

/**
 * 周期期末采样任务参数（系统日程，不对应任何人物）。
 *
 * @param commandId   内部命令标识
 * @param companyPartyId 公司
 * @param period      期间序号，1 基
 * @param policyDigest 开局冻结政策摘要，必须与状态内政策一致
 */
public record PeriodicCapturePayload(String commandId, String companyPartyId, long period,
                                     String policyDigest) {

    /** 校验必填字段与期间。 */
    public PeriodicCapturePayload {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("周期采样命令不能为空");
        }
        if (companyPartyId == null || companyPartyId.isBlank()) {
            throw new IllegalArgumentException("周期采样公司不能为空");
        }
        if (period < 1) {
            throw new IllegalArgumentException("周期期间必须从 1 开始：" + period);
        }
        if (policyDigest == null || policyDigest.isBlank()) {
            throw new IllegalArgumentException("周期政策摘要不能为空");
        }
    }
}
