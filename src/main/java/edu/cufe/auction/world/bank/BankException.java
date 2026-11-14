package edu.cufe.auction.world.bank;

/**
 * 纯核内部拒绝信号：携带稳定原因码，不作为公共 API 的受检异常。
 */
final class BankException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    BankException(String reason) {
        super(reason);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }
}
