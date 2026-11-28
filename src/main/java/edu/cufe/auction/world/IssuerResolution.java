package edu.cufe.auction.world;

/** 独立银行处置实验的公开交易与债权口径。 */
public final class IssuerResolution {
    /** 当前独立实验档的未恢复交易状态。 */
    public static final String HALTED = "ISSUER_RESOLUTION_HALTED";
    /** 在开局即向所有人物展示的规则。 */
    public static final String RULE = "实验规则：银行账面净资产为负、准备金耗尽且尚欠存款，或到期应急债务超过准备金时，独立处置官可立案。立案时撤销该行股票余单、暂停新申报和新贷款，保留股东及债权人登记。可分配准备金依次偿付存款、应急债及次级债，同级按比例分配。";
    private IssuerResolution() { }

    /**
     * @param issuer 股票发行人
     * @param listing 证券登记状态
     * @param trading 交易状态
     * @param lending 受影响的新增贷款业务
     * @param proceeding 独立处置程序状态
     * @param openedAtMillis 已立案的时点
     * @param rule 自开局公开的规则
     */
    public record Status(String issuer, String listing, String trading, String lending, String proceeding,
                         Long openedAtMillis, String rule) { }
    record Halt(String issuer, String symbol, java.util.List<Long> cancelledOrderIds) { }

    static boolean newCredit(edu.cufe.auction.world.bank.BankActions.Kind kind) {
        return switch (kind) {
            case LOAN_REQUEST, LOAN_ACCEPT, LOAN_APPROVE, LOAN_DRAW -> true;
            default -> false;
        };
    }
}
