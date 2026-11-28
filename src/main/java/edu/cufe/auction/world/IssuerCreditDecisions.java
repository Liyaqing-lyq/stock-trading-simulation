package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.composed.BankLoanPromotionOpening;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankMetrics;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.Fund;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 按冻结观察中的本人财产、基金委托或公司任职列出银行账户和贷款工具。 */
public final class IssuerCreditDecisions {
    static final String REQUEST = "bank.loan.request", ACCEPT = "bank.loan.accept", APPROVE = "bank.loan.approve",
            DRAW = "bank.loan.draw", REPAY = "bank.loan.repay", INTEREST = "bank.loan.payInterest",
            DEPOSIT = "bank.cash.deposit", WITHDRAW = "bank.cash.withdraw";
    static final String CRISIS = "bank.crisis.act", MOVE = "bank.cash.move";
    static final String IMPAIR = "bank.loan.impair", WRITE_OFF = "bank.loan.writeOff";

    /** 新档银行经理贷款损失决定使用既有银行动作工时。 */
    public static void addLossTimings(Map<String, Long> timings) {
        timings.put(IMPAIR, DURATION);
        timings.put(WRITE_OFF, DURATION);
    }

    /** 本档是否已登记经理办理贷款损失及追收政策。 */
    public static boolean lossEnabled(RunManifest manifest) {
        return RunManifest.BANK_LOAN_LOSS_VERSION.equals(manifest.dataVersions().get(RunManifest.BANK_LOAN_LOSS_POLICY));
    }

    /** 本行已提款合同、真实拨备和可处置金额。拨备按借款人科目计量，核销限定具名期次。 */
    public static List<Map<String,String>> lossExposures(BankState state, BankActions.Book book,
                                                         Set<String> banks, Set<String> managed, String person) {
        var loans = new java.util.TreeMap<>(book.loans());
        boolean disclosed = BankLoanPromotionOpening.POOL.equals(state.chart().backgroundBorrowerKey());
        var historic = disclosed ? BankLoanPromotionOpening.certificate() : null;
        if (disclosed) loans.putIfAbsent(historic.contract().loanId(), historic.contract());
        var rows = new java.util.ArrayList<Map<String,String>>();
        for (var c : loans.values()) {
            String bank = c.bankKey(), borrower = c.borrowerKey();
            if (!banks.contains(bank) || c.trancheId() == null) continue;
            var tranche = state.loan(bank, borrower).tranche(c.trancheId());
            String bucket = borrower;
            if (tranche == null && historic != null && historic.contract().loanId().equals(c.loanId())) {
                tranche = historic.obligation(state);
                bucket = BankLoanPromotionOpening.POOL;
            }
            if (tranche == null || tranche.contract().signum() <= 0) continue;
            var provision = state.gl(bank, state.chart().llpFor(bucket)).negate();
            var onbook = state.gl(bank, state.chart().bucketFor(bucket));
            var row = new java.util.LinkedHashMap<String,String>();
            row.put("loanId", c.loanId()); row.put("bank", bank); row.put("borrower", borrower);
            row.put("tranche", tranche.trancheId()); row.put("contractPrincipal", tranche.contract().toPlainString());
            row.put("onbook", tranche.onbook().toPlainString()); row.put("writtenOff", tranche.writtenOff().toPlainString());
            row.put("portfolioAllowance", provision.toPlainString()); row.put("portfolioOnbook", onbook.toPlainString());
            boolean canAct = !person.equals(borrower) && !managed.contains(borrower) && tranche.onbook().signum() > 0;
            row.put("maxImpair", (canAct ? onbook.subtract(provision).max(BigDecimal.ZERO) : BigDecimal.ZERO).toPlainString());
            row.put("maxWriteOff", (canAct ? tranche.onbook().min(provision) : BigDecimal.ZERO).toPlainString());
            if (tranche.terms() != null) {
                row.put("maturityAt", Long.toString(tranche.terms().maturityMs()));
                row.put("overdue", Boolean.toString(tranche.terms().overdue()));
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    /** 经理工具的共同记账与金额口径。 */
    public static final String LOSS_TERMS = "按本人对信用风险的判断，用 loanId 选择已提款合同。impair 在借款人组合计提拨备，writeOff 仅冲指定期次及已有拨备。观察中的上限在执行时复核。核销保留合同和追收权，两项均不付款；借款人还款从本行存款实际扣款，优先冲该合同备查债权。";
    record Crisis(String bank,String kind,String lineId,BigDecimal amount) { }
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
    record Move(String bank, String customer, String sourceToken, String payeeToken, BigDecimal amount) { }
    record OwnerCash(String customer, String appointment, boolean canBorrow) { }

    /** 本人/产品同名账户划转与既有银行动作采用相同工时。 */
    public static void addOwnerTimings(Map<String, Long> timings) { timings.put(MOVE, DURATION); }

    static boolean ownerEnabled(RunManifest manifest) {
        return "owner-cash-v1".equals(manifest.dataVersions().get("owner-bank-decisions"));
    }

    static List<OwnerCash> owners(RoleAccess.State access, BankConfig config, String person, long at) {
        var profile = config.profile();
        var result = new java.util.ArrayList<OwnerCash>();
        for (var entry : new java.util.TreeMap<>(profile.parties()).entrySet()) {
            String customer = entry.getKey();
            var ref = entry.getValue();
            if (ref.depositGl() == null) continue;
            if (customer.equals(person) && person.equals(ref.partyId())) {
                result.add(new OwnerCash(customer, null, profile.buckets().containsKey(customer)));
            } else {
                var office = FundInternalCash.mandate(access, profile, person, customer, at);
                if (office != null) result.add(new OwnerCash(customer, office.id(), false));
            }
        }
        return List.copyOf(result);
    }

    static void addOwnerTools(List<String> tools, List<OwnerCash> owners) {
        if (owners.isEmpty()) return;
        var available = owners.stream().anyMatch(OwnerCash::canBorrow) ? BORROWER : List.of(DEPOSIT, WITHDRAW);
        for (String tool : available) if (!tools.contains(tool)) tools.add(tool);
        tools.add(MOVE);
    }

    private static void appendOwners(ObjectNode visible, List<OwnerCash> owners, BankRun.Decoded decoded,
                                     Map<String, CashLedger.CashAccount> accounts, edu.cufe.auction.world.bank.CashFreeze frozen) {
        var rows = visible.putArray("ownerAccounts");
        var profile = decoded.config().profile();
        for (var owner : owners) {
            String customer = owner.customer();
            String account = profile.parties().get(customer).cashAccountId();
            for (String bank : new TreeSet<>(profile.banks().keySet())) {
                var row = rows.addObject().put("customer", customer).put("bank", bank)
                        .put("authority", owner.appointment() == null ? "PERSON" : "PORTFOLIO_MANAGER")
                        .put("canBorrow", owner.canBorrow());
                if (owner.appointment() != null) row.put("appointmentId", owner.appointment());
                var buckets = row.putObject("buckets");
                var cash = new Fund.Cash(account);
                var deposit = new Fund.Deposit(bank, customer);
                buckets.put(cash.token(), accounts.get(account).cash().subtract(frozen.frozenOf(cash)).toPlainString());
                buckets.put(deposit.token(), decoded.state().depositOf(bank, customer).subtract(frozen.frozenOf(deposit)).toPlainString());
                for (var custodian : new TreeSet<>(profile.custodians().keySet())) {
                    if (!bank.equals(profile.custodians().get(custodian).bankId())) continue;
                    var detail = new Fund.Custody(custodian, customer);
                    buckets.put(detail.token(), decoded.book().detailOf(custodian, customer).subtract(frozen.frozenOf(detail)).toPlainString());
                }
            }
        }
        visible.put("ownerProcedure", "bank.cash.deposit 与 withdraw 的 borrower 填本人或受托产品的 customer。bank.cash.move 的两端须来自同一行 buckets；各数为余额扣除该账户买单冻结后的元数。仅 canBorrow 的本人可申请贷款，接受后由独立银行经理按合同审批，提款才形成存款与债务。");
    }

    static JsonNode observedOwner(JsonNode credit, String bank, String customer) {
        if (bank == null || customer == null) return null;
        for (var row : credit.path("ownerAccounts"))
            if (bank.equals(row.path("bank").asText()) && customer.equals(row.path("customer").asText())) return row;
        return null;
    }

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
        if (edu.cufe.auction.world.bank.BankCrisis.PROVIDER.equals(config.profile().providerKey())
                && config.profile().banks().keySet().stream().anyMatch(bank -> !crisisActions(managed,bank).isEmpty())) tools.add(CRISIS);
    }

    private static List<String> crisisActions(Set<String> managed,String bank) {
        return java.util.Arrays.stream(BankActions.Kind.values()).filter(k -> {
            String office = edu.cufe.auction.world.bank.BankCrisis.office(k,bank);
            return office != null && edu.cufe.auction.world.bank.BankCrisis.eligible(managed,office,bank);
        }).map(Enum::name).toList();
    }

    static void append(ObjectNode engineering, Set<String> managed, List<OwnerCash> owners, BankRun.Decoded decoded,
                       BankActions.Book book, Map<String, CashLedger.CashAccount> accounts, edu.cufe.auction.world.bank.CashFreeze frozen,
                       RunManifest manifest, String person) {
        BankConfig config = decoded.config();
        BankState state = decoded.state();
        var visible = engineering.putObject("issuerCredit");
        if (edu.cufe.auction.world.bank.BankCrisis.PROVIDER.equals(config.profile().providerKey())) {
            for (String bank : config.profile().banks().keySet()) {
                var actions=crisisActions(managed,bank);
                if (actions.isEmpty()) continue;
                var office=visible.withArray("crisisOffices").addObject().put("bank",bank);
                office.set("actions",JSON.valueToTree(actions));
                if (RunManifest.CRISIS_ARGUMENTS_VERSION.equals(manifest.dataVersions().get(RunManifest.CRISIS_ARGUMENTS_POLICY)))
                    describeCrisisArguments(office,actions);
                office.put("terms","EXP 参考当前已核报表及现金，自主决定是否采取行动或等待。应急额度3日内可提，单笔30日免息无担保。处置顺序依次为存款、应急融资和次级债务。");
                if (actions.contains("FACILITY_AUTHORIZE")) {
                    var id=config.chart().cashAccountOf(config.profile().providerKey());
                    office.put("providerAvailable",accounts.get(id).cash().subtract(frozen.frozenOf(new Fund.Cash(id))).toPlainString());
                }
                if (actions.contains("RESOLUTION_OPEN") || actions.contains("FACILITY_DRAW"))
                    office.put("reserve",accounts.get(config.chart().reserveAccountOf(bank)).cash().toPlainString())
                         .put("bookEquity",state.bookEquity(bank).toPlainString()).put("resolutionOpen",state.resolution(bank)!=null);
                var lines=office.putArray("lines");
                for (var f : state.facilityAccountsOf(bank).values())
                    lines.addObject().put("lineId",f.authorizationId()).put("limit",f.limit().toPlainString())
                        .put("drawn",f.drawnCumulative().toPlainString()).put("outstanding",f.nominal().toPlainString())
                        .put("authorizationExpiryMs",f.authorizationExpiryMs()).put("maturityAt",f.facilityMaturityMs());
            }
        }
        visible.put("procedure", "各公司资金独立。借款经理自行拟定金额、用途、年利率rateU(1000000=100%)与期限tenorMs；申请后借款方须另外接受，银行经理才可按原条款审批，或等待。提款前没有放款；提款增加借款公司的银行存款，风险闸门可以拒绝。存取款在本公司现金和银行存款间划拨，还本与付息各从存款实扣。到期自动核算利息。");
        if (!owners.isEmpty()) {
            if (managed.isEmpty()) visible.remove("procedure");
            appendOwners(visible, owners, decoded, accounts, frozen);
        }
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
                BigDecimal cash = accounts.get(account).cash().subtract(frozen.frozenOf(new Fund.Cash(account)));
                borrowers.addObject().put("borrower", borrower).put("bank", bank).put("cashAccount", account)
                        .put("availableCash", cash.toPlainString()).put("deposit", state.depositOf(bank, borrower).toPlainString());
            }
            var contractsAtBank = new java.util.TreeMap<>(book.loans());
            boolean disclosed = BankLoanPromotionOpening.POOL.equals(state.chart().backgroundBorrowerKey());
            var historic = disclosed ? BankLoanPromotionOpening.certificate() : null;
            if (disclosed) contractsAtBank.putIfAbsent(historic.contract().loanId(), historic.contract());
            for (var loan : contractsAtBank.values()) {
                if (!bank.equals(loan.bankKey()) || !bankManager && !managed.contains(loan.borrowerKey())
                        && owners.stream().noneMatch(owner -> owner.canBorrow() && owner.customer().equals(loan.borrowerKey()))) continue;
                var row = contracts.addObject().put("loanId", loan.loanId()).put("bank", bank)
                        .put("borrower", loan.borrowerKey()).put("amount", loan.amount().toPlainString())
                        .put("rateU", loan.rateU()).put("tenorMs", loan.tenorMs()).put("purpose", loan.purpose())
                        .put("status", loan.status().name()).put("borrowerAccepted", loan.accepted())
                        .put("bankApproved", loan.approvedBy() != null).put("principalDue", loan.drawn().toPlainString());
                if (loan.trancheId() != null) {
                    var tranche = state.loan(bank, loan.borrowerKey()).tranche(loan.trancheId());
                    if (disclosed && historic.contract().loanId().equals(loan.loanId())) {
                        row.put("classification", tranche == null ? "AGGREGATE" : "DETAILED");
                        tranche = historic.obligation(state);
                        row.put("sourceDigest", historic.originDigest()).put("sourcePosition", BankLoanPromotionOpening.sourcePosition())
                                .put("sourceNote","独立实验开局引入的已签约历史贷款；审批和提款为来源实验的受控操作。");
                    }
                    if (tranche != null && tranche.terms() != null) {
                        var terms = tranche.terms();
                        row.put("maturityAt", terms.maturityMs()).put("overdue", terms.overdue())
                                .put("interestDue", terms.interestReceivable().toPlainString())
                                .put("interestPaid", BigDecimal.valueOf(terms.paidCents(), 2).toPlainString());
                    }
                }
            }
        }
        if (lossEnabled(manifest) && !banks.isEmpty()) {
            var bankKeys = new TreeSet<String>();
            banks.forEach(row -> bankKeys.add(row.path("bank").asText()));
            visible.put("lossProcedure", LOSS_TERMS);
            visible.set("lossExposures", JSON.valueToTree(lossExposures(state, book, bankKeys, managed, person)));
        }
    }

    private static void describeCrisisArguments(ObjectNode office, List<String> actions) {
        var rules = office.putObject("actionArguments");
        for (var action : actions) {
            var fields = rules.putObject(action);
            switch (BankActions.Kind.valueOf(action)) {
                case FACILITY_AUTHORIZE -> fields
                        .put("lineId","由出资方自拟本行未用的新编号，须非空且不得含 |。可在没有借款申请、lines 为空时授权。")
                        .put("amount","拟授权的额度上限，授权时不划款，也不要求额度低于当前 providerAvailable；提款时另核可用资金。");
                case FACILITY_DRAW -> fields
                        .put("lineId","引用本行 lines 中出资方已授权的 lineId；提款须在该额度有效期及融资到期前完成。")
                        .put("amount","本次拟提金额不得超过该行 limit 减累计 drawn，且出资方须有足够可付资金。");
                case FACILITY_PAY -> fields
                        .put("lineId","引用本行 lines 中已提款、尚有应还本金的 lineId。")
                        .put("amount","本次归还的本金不得超过该额度 outstanding，也不得超过本行可支付的准备金。");
                case RESOLUTION_OPEN, RESOLUTION_DISTRIBUTE -> {
                    fields.put("lineId","省略或填 null。").put("amount","省略或填 null。");
                    fields.put("condition",action.equals("RESOLUTION_OPEN")
                            ? "账面权益为负、准备金耗尽且仍有存款，或到期应急融资应付额超过准备金时可申请立案。"
                            : "已立案且存款冻结不再阻碍分配时，依在账债权次序分配实际可用资金。");
                }
                default -> throw new IllegalArgumentException("未登记的危机动作：" + action);
            }
        }
        if (actions.contains("FACILITY_AUTHORIZE") || actions.contains("FACILITY_DRAW"))
            office.put("amountFormat","金额以元计，须为正数、可精确到分且小于 1000000000000000；资金与授权在实际执行时再核。");
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
            case IMPAIR -> BankActions.Kind.LOAN_IMPAIR;
            case WRITE_OFF -> BankActions.Kind.LOAN_WRITE_OFF;
            case DRAW -> BankActions.Kind.LOAN_DRAW;
            case REPAY -> BankActions.Kind.LOAN_REPAY;
            case INTEREST -> BankActions.Kind.LOAN_PAY_INTEREST;
            case DEPOSIT -> BankActions.Kind.DEPOSIT;
            case WITHDRAW -> BankActions.Kind.WITHDRAW;
            case MOVE -> BankActions.Kind.PAY;
            default -> throw new IllegalArgumentException("未知银行工具");
        };
    }
}
