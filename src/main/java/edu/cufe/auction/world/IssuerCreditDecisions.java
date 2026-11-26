package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankMetrics;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 登记发行人的经理贷款工具；条款、审批人、资金与可见贷款均从本次冻结输入派生。 */
public final class IssuerCreditDecisions {
    static final String REQUEST = "bank.loan.request", ACCEPT = "bank.loan.accept", APPROVE = "bank.loan.approve",
            DRAW = "bank.loan.draw", REPAY = "bank.loan.repay", INTEREST = "bank.loan.payInterest",
            DEPOSIT = "bank.cash.deposit", WITHDRAW = "bank.cash.withdraw";
    static final long DURATION = 60_000L;
    private static final List<String> BORROWER = List.of(REQUEST, ACCEPT, DRAW, REPAY, INTEREST, DEPOSIT, WITHDRAW);
    private static final ObjectMapper JSON = new ObjectMapper();

    private IssuerCreditDecisions() { }

    /** @param timings 本档工具工时配置；各动作占用一分钟世界工时 */
    public static void addTimings(Map<String, Long> timings) {
        for (String tool : BORROWER) timings.put(tool, DURATION);
        timings.put(APPROVE, DURATION);
    }

    record Offer(String bank, String borrower, String loanId, BigDecimal amount, long rateU, long tenorMs, String purpose) { }
    record Ref(String loanId) { }
    record Payment(String loanId, BigDecimal amount) { }
    record Cash(String bank, String borrower, BigDecimal amount) { }

    static boolean enabled(RunManifest manifest) {
        return "manager-loan-v1".equals(manifest.dataVersions().get("issuer-credit-decisions"));
    }

    static Set<String> managed(RoleAccess.State access, String person, long now) {
        var organizations = new TreeSet<String>();
        for (var office : access.appointments()) if (office.personPartyId().equals(person) && office.activeAt(now)
                && office.role() == RoleAccess.Role.COMPANY_MANAGER) organizations.add(office.organizationPartyId());
        return organizations;
    }

    static void addTools(List<String> tools, Set<String> managed, BankConfig config) {
        if (config.profile().banks().values().stream().anyMatch(bank -> managed.contains(bank.partyId()))) tools.add(APPROVE);
        if (managed.stream().anyMatch(config.profile().buckets()::containsKey)) tools.addAll(BORROWER);
    }

    static void append(ObjectNode engineering, Set<String> managed, BankRun.Decoded decoded,
                       BankActions.Book book, Map<String, CashLedger.CashAccount> accounts, WorldExchange.State exchange) {
        BankConfig config = decoded.config();
        BankState state = decoded.state();
        var visible = engineering.putObject("issuerCredit");
        visible.put("procedure", "各公司资金独立。借款经理自行拟定金额、用途、年利率rateU(1000000=100%)与期限tenorMs；申请后借款方须另外接受，银行经理才可按原条款审批，或等待。提款前没有放款；提款增加借款公司的银行存款，风险闸门可以拒绝。存取款在本公司现金和银行存款间划拨，还本与付息各从存款实扣。到期自动核算利息。");
        var borrowers = visible.putArray("managedBorrowers");
        var banks = visible.putArray("managedBanks");
        var contracts = visible.putArray("contracts");
        for (var entry : new java.util.TreeMap<>(config.profile().banks()).entrySet()) {
            String bank = entry.getKey();
            boolean bankManager = managed.contains(entry.getValue().partyId());
            if (bankManager) {
                var desk = banks.addObject().put("bank", bank).put("organization", entry.getValue().partyId())
                        .put("reserve", state.gl(bank, "RESERVE").toPlainString());
                var gate = BankMetrics.gate(state, accounts, config, bank);
                desk.put("riskStatus", gate.status()).set("riskActions", JSON.valueToTree(gate.actions()));
                var metrics = desk.putObject("riskMeasures");
                for (String metric : List.of("CET1R", "SINGLE", "GROUP", "LCR_EXP"))
                    metrics.set(metric, JSON.valueToTree(gate.metrics().get(metric)));
            }
            for (String borrower : managed) if (config.profile().buckets().containsKey(borrower)) {
                String account = config.chart().cashAccountOf(borrower);
                BigDecimal cash = accounts.get(account).cash().subtract(WorldExchange.frozenCash(exchange, account));
                borrowers.addObject().put("borrower", borrower).put("bank", bank).put("cashAccount", account)
                        .put("availableCash", cash.toPlainString()).put("deposit", state.depositOf(bank, borrower).toPlainString());
            }
            for (var loan : new java.util.TreeMap<>(book.loans()).values()) {
                if (!bank.equals(loan.bankKey()) || !bankManager && !managed.contains(loan.borrowerKey())) continue;
                var row = contracts.addObject().put("loanId", loan.loanId()).put("bank", bank)
                        .put("borrower", loan.borrowerKey()).put("amount", loan.amount().toPlainString())
                        .put("rateU", loan.rateU()).put("tenorMs", loan.tenorMs()).put("purpose", loan.purpose())
                        .put("status", loan.status().name()).put("borrowerAccepted", loan.accepted())
                        .put("bankApproved", loan.approvedBy() != null).put("principalDue", loan.drawn().toPlainString());
                if (loan.trancheId() != null) {
                    var tranche = state.loan(bank, loan.borrowerKey()).tranche(loan.trancheId());
                    if (tranche != null && tranche.terms() != null) {
                        var terms = tranche.terms();
                        row.put("maturityAt", terms.maturityMs()).put("overdue", terms.overdue())
                                .put("interestDue", terms.interestReceivable().toPlainString())
                                .put("interestPaid", BigDecimal.valueOf(terms.paidCents(), 2).toPlainString());
                    }
                }
            }
        }
    }

    static JsonNode contract(JsonNode credit, String loanId) {
        for (var row : credit.path("contracts")) if (loanId.equals(row.path("loanId").textValue())) return row;
        throw new IllegalArgumentException("本次观察没有该贷款");
    }

    static boolean observed(JsonNode rows, String bank, String borrower) {
        for (var row : rows) if (bank.equals(row.path("bank").textValue())
                && (borrower == null || borrower.equals(row.path("borrower").textValue()))) return true;
        return false;
    }

    static BankActions.Kind kind(String tool) {
        return switch (tool) {
            case REQUEST -> BankActions.Kind.LOAN_REQUEST;
            case ACCEPT -> BankActions.Kind.LOAN_ACCEPT;
            case APPROVE -> BankActions.Kind.LOAN_APPROVE;
            case DRAW -> BankActions.Kind.LOAN_DRAW;
            case REPAY -> BankActions.Kind.LOAN_REPAY;
            case INTEREST -> BankActions.Kind.LOAN_PAY_INTEREST;
            case DEPOSIT -> BankActions.Kind.DEPOSIT;
            case WITHDRAW -> BankActions.Kind.WITHDRAW;
            default -> throw new IllegalArgumentException("未知银行工具");
        };
    }
}
