package edu.cufe.auction.world;

import java.io.IOException;

/**
 * 独立分支被明确拒绝：携带稳定原因码，且<b>不写任何文件</b>（父档、子档、索引一律不变）。
 *
 * <p>拒绝码是契约的一部分，调用方按码处理，不要解析自由文本。已支持：
 * {@code BRANCH_POSITION_UNCONFIRMED}、{@code BRANCH_BARRIER_PENDING}、
 * {@code BRANCH_ATTEMPT_IN_FLIGHT}、{@code BRANCH_SESSION_OPEN}、
 * {@code BRANCH_IDENTITY_INVALID}、{@code BRANCH_IDENTITY_RESERVED}、
 * {@code BRANCH_IDENTITY_COLLISION}、{@code BRANCH_EXISTS}、
 * {@code BRANCH_RULES_MISMATCH}、{@code BRANCH_PARENT_UNAVAILABLE}、
 * {@code BRANCH_PARENT_IS_BRANCH}、{@code BRANCH_PARENT_PREFIX_MISMATCH}、
 * {@code BRANCH_PARENT_SCAN_UNREADABLE}、{@code BRANCH_PARENT_TAIL_BEFORE_BOUND}、
 * {@code BRANCH_DERIVATION_MISMATCH}、{@code BRANCH_ORIGIN_INCOMPLETE}、
 * {@code BRANCH_PUBLISH_FAILED}、{@code BRANCH_INHERITED_POSITION_NOT_EXPORTABLE}。</p>
 */
public final class BranchRejectedException extends IOException {

    private final String code;

    /**
     * @param code   稳定原因码
     * @param detail 人类可读说明；不得包含密钥或角色私源
     */
    public BranchRejectedException(String code, String detail) {
        super(code + ": " + detail);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("分支拒绝码不能为空");
        }
        this.code = code;
    }

    /**
     * @param code   稳定原因码
     * @param detail 说明
     * @param cause  原始失败
     */
    public BranchRejectedException(String code, String detail, Throwable cause) {
        super(code + ": " + detail, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("分支拒绝码不能为空");
        }
        this.code = code;
    }

    /** @return 稳定原因码 */
    public String code() {
        return code;
    }
}
