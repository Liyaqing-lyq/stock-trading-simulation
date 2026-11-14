package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 托管资金入口：一次托管存取/划转 = 一条合法客户指令 + 由它派生的银行腿。
 *
 * <p>支持四类向量：{@code CASH|account → CUSTODY}、{@code CUSTODY → CASH|DEPOSIT}、
 * 同一托管经营人下客户之间 {@code CUSTODY → CUSTODY}、跨托管经营人（含跨银行）
 * {@code CUSTODY → CUSTODY}。收款客户不签支出；出款侧托管余额只由真实付款客户的 owner
 * 授权（付款 key 必须是已声明主体且 owner 与客户一致）。</p>
 *
 * <p>银行腿不是外部可拼的命令：它由这条客户指令派生 —— 托管出款时 payer 是托管经营人，
 * 池存款科目是档案声明的 {@code liabilityId}，跨经营人时就是两条池存款负债的真实对开并
 * 走已验的 {@link BankModel} 付款（跨行受准备金限制）。同经营人内部划转只改客户明细，
 * 池存款与准备金都不动。银行腿、明细账、现金账户在同一个候选里前进：任一腿或组合校验
 * 失败就整批放弃，只追加一条拒绝幂等回执，不留经济半笔变化。</p>
 *
 * <p>冻结只按出款桶算：托管来源用客户明细减该桶合法冻结，别家托管份额、别的经营人、
 * 同一 owner 的现金或存款都不补足。池层（{@code DEPOSIT}）冻结无法证明独占归属，遇到即
 * 整笔明确拒绝，不用 {@link CashFreeze#none()} 掩盖，订单映射仍归 World。股票成交仍等
 * {@code WorldExchange} 真实 fill 桥，本类不造证券副账。</p>
 */
public final class CustodyPort {

    /** 内部派生腿标识前缀：外部命令不可构造，占用即拒绝。 */
    static final String LEG_PREFIX = "custody.leg#";

    private CustodyPort() {
    }

    /**
     * 一次托管付款的结果；拒绝时银行/现金/明细的经济字段都保持入参原样。
     *
     * @param accepted 是否接受
     * @param reason   结果码
     * @param receipt  回执（拒绝回执同样落账）
     * @param bank     候选银行状态
     * @param accounts 候选现金账户
     * @param book     候选明细账
     */
    public record Result(boolean accepted, String reason, String receipt, BankState bank,
                         Map<String, CashLedger.CashAccount> accounts, CustodyBook book) {
    }

    /**
     * 提交一次托管付款。
     *
     * @param bank     当前银行状态（须已绑定 run 与档案指纹）
     * @param accounts 现金账户（唯一现金权威）
     * @param book     托管明细账（须与同一 run/档案绑定）
     * @param config   冻结档案
     * @param ctx      运行上下文
     * @param payment  付款请求：付款人档案 key、源桶、目标桶、金额
     * @param frozen   受信冻结快照；无冻结必须显式传 {@link CashFreeze#none()}
     * @return 结果；同标识同完整请求重放返回当前三态与首次回执
     */
    public static Result pay(BankState bank, Map<String, CashLedger.CashAccount> accounts,
                             CustodyBook book, BankConfig config, BankContext ctx,
                             BankPayment payment, CashFreeze frozen) {
        Objects.requireNonNull(bank, "银行状态不能为空");
        Objects.requireNonNull(payment, "付款请求不能为空");
        Objects.requireNonNull(frozen, "受信冻结快照不能为空；无冻结请显式传 CashFreeze.none()");
        Objects.requireNonNull(book, "托管明细账不能为空");
        Objects.requireNonNull(config, "档案不能为空");
        Objects.requireNonNull(ctx, "运行上下文不能为空");
        Map<String, CashLedger.CashAccount> cash =
                accounts == null ? Map.of() : accounts;
        Map<String, String> body = bodyOf(payment);
        if (book.runId() == null || bank.runId() == null) {
            return reject(Reasons.NO_ACTIVE_RUN, bank, cash, book, null, body, null);
        }
        if (!identityHolds(bank, book, config, ctx)) {
            return reject(Reasons.STATE_FINGERPRINT_MISMATCH + ":custody", bank, cash, book,
                    null, body, null);
        }
        // 当前三态必须先自洽：否则一条旧的成功回执会掩盖被篡改的明细/错行/应计池。
        // 这类前置拒绝不消费 commandId，修好当前账后同一 id 仍可正常处理。
        List<String> current = book.validate(bank, cash, config);
        if (!current.isEmpty()) {
            return reject(current.get(0), bank, cash, book, null, body, null);
        }
        BankState.CommandRecord previous = book.command(payment.paymentId());
        if (previous != null) {
            if (!previous.body().equals(canonicalBody(body))) {
                return new Result(false, Reasons.COMMAND_ID_CONFLICT, previous.receipt(), bank,
                        cash, book);
            }
            String reason = previous.accepted() ? "ACCEPTED" : reasonOf(previous.receipt());
            return new Result(previous.accepted(), reason, previous.receipt(), bank, cash, book);
        }
        Map<String, String> receipt = new TreeMap<>();
        try {
            return apply(bank, cash, book, config, ctx, payment, frozen, body, receipt);
        } catch (BankException failure) {
            return reject(failure.reason(), bank, cash, book, payment.paymentId(), body, receipt);
        } catch (IllegalArgumentException invalid) {
            String reason = Reasons.INVALID_STATE_TRANSITION + ":"
                    + (invalid.getMessage() == null ? invalid.getClass().getSimpleName()
                    : invalid.getMessage());
            return reject(reason, bank, cash, book, payment.paymentId(), body, receipt);
        }
    }

    /** 当前三态与运行上下文是否互相绑定（银行、明细账、档案、ctx 的 run 与指纹一致）。 */
    private static boolean identityHolds(BankState bank, CustodyBook book, BankConfig config,
                                         BankContext ctx) {
        String fingerprint = config.fingerprint();
        String profile = config.profile().fingerprint();
        return book.runId().equals(ctx.runId())
                && book.configFingerprint().equals(ctx.configFingerprint())
                && book.configFingerprint().equals(fingerprint)
                && book.chart().profile().fingerprint().equals(profile)
                && bank.runId().equals(ctx.runId())
                && bank.configFingerprint().equals(ctx.configFingerprint())
                && bank.chart().profile().fingerprint().equals(profile);
    }

    private static Result apply(BankState bank, Map<String, CashLedger.CashAccount> accounts,
                                CustodyBook book, BankConfig config, BankContext ctx,
                                BankPayment payment, CashFreeze frozen,
                                Map<String, String> body, Map<String, String> receipt) {
        BankChart chart = book.chart();
        Fund source = payment.source();
        Fund destination = payment.destination();
        BigDecimal amount = Money.business(payment.amount());
        if (source.token().equals(destination.token())) {
            throw new BankException(Reasons.PAYMENT_SAME_BUCKET + ":" + source.token());
        }
        boolean custodySource = source.kind() == Fund.Kind.CUSTODY;
        boolean custodyTarget = destination.kind() == Fund.Kind.CUSTODY;
        if (!custodySource && !custodyTarget) {
            throw new BankException(Reasons.CUSTODY_FUND_REQUIRED + ":" + source.token());
        }
        Ref debit = custodySource
                ? custodyRef(chart, (Fund.Custody) source, payment.payerKey(), accounts, true)
                : null;
        Ref credit = custodyTarget
                ? custodyRef(chart, (Fund.Custody) destination, payment.payerKey(), accounts, false)
                : null;
        if (debit != null && frozen.frozenOf(debit.pool()).signum() > 0) {
            receipt.put("bucket", debit.pool().token());
            receipt.put("frozen", Money.text(frozen.frozenOf(debit.pool())));
            throw new BankException(Reasons.CUSTODY_POOL_FREEZE + ":" + debit.custodian());
        }
        if (credit != null && frozen.frozenOf(credit.pool()).signum() > 0) {
            receipt.put("bucket", credit.pool().token());
            receipt.put("frozen", Money.text(frozen.frozenOf(credit.pool())));
            throw new BankException(Reasons.CUSTODY_POOL_FREEZE + ":" + credit.custodian());
        }
        if (debit != null) {
            BigDecimal balance = book.detailOf(debit.custodian(), debit.client());
            BigDecimal held = frozen.frozenOf(source);
            if (held.compareTo(balance) > 0) {
                receipt.put("bucket", source.token());
                receipt.put("balance", Money.text(balance));
                receipt.put("frozen", Money.text(held));
                throw new BankException(Reasons.PAYMENT_FREEZE_EXCEEDS_BALANCE + ":"
                        + source.token());
            }
            BigDecimal available = balance.subtract(held);
            if (amount.compareTo(available) > 0) {
                receipt.put("bucket", source.token());
                receipt.put("balance", Money.text(balance));
                receipt.put("frozen", Money.text(held));
                receipt.put("available", Money.text(available));
                throw new BankException(Reasons.PAYER_BALANCE_INSUFFICIENT + ":"
                        + source.token());
            }
        }
        boolean bookOnly = debit != null && credit != null
                && debit.custodian().equals(credit.custodian());
        Fund legSource = debit == null ? source : debit.pool();
        Fund legTarget = credit == null ? destination : credit.pool();
        String legPayer = debit == null ? payment.payerKey() : debit.custodian();
        BankState candidateBank = bank;
        Map<String, CashLedger.CashAccount> candidateAccounts = accounts;
        if (!bookOnly) {
            String legId = LEG_PREFIX + payment.paymentId();
            if (bank.command(legId) != null) {
                throw new BankException(Reasons.CUSTODY_LEG_CONFLICT + ":" + legId);
            }
            Map<String, String> legBody = new LinkedHashMap<>();
            legBody.put("payer", legPayer);
            legBody.put("src", legSource.token());
            legBody.put("dst", legTarget.token());
            legBody.put("amount", amount.toPlainString());
            BankCommand leg = new BankCommand(legId, BankCommand.Kind.PAYMENT, legBody, legPayer);
            BankModel.Outcome outcome = BankModel.apply(bank, accounts, config, ctx, leg,
                    freezeForLeg(frozen, custodySource ? source : null));
            if (!outcome.accepted()) {
                merge(receipt, outcome.receipt());
                receipt.put("leg", legId);
                throw new BankException(outcome.reason());
            }
            candidateBank = outcome.state();
            candidateAccounts = outcome.accounts();
            receipt.put("leg", legId);
        } else {
            receipt.put("leg", "book-only");
        }
        Map<String, BigDecimal> next = new TreeMap<>(book.details());
        if (debit != null) {
            BigDecimal left = book.detailOf(debit.custodian(), debit.client()).subtract(amount);
            if (left.signum() < 0) {
                throw new BankException(Reasons.CUSTODY_DETAIL_INVALID + ":negative:"
                        + debit.custodian() + "|" + debit.client());
            }
            putDetail(next, debit, left);
        }
        if (credit != null) {
            putDetail(next, credit, book.detailOf(credit.custodian(), credit.client()).add(amount));
        }
        CustodyBook candidateBook = book.withDetails(next);
        List<String> problems = candidateBook.validate(candidateBank, candidateAccounts, config);
        if (!problems.isEmpty()) {
            throw new BankException(problems.get(0));
        }
        receipt.put("payer", payment.payerKey());
        receipt.put("src", source.token());
        receipt.put("dst", destination.token());
        receipt.put("amount", Money.text(amount));
        if (credit != null) {
            receipt.put("beneficial", Money.text(
                    candidateBook.detailOf(credit.custodian(), credit.client())));
            receipt.put("pool", Money.text(candidateBook.poolAssetOf(candidateBank,
                    credit.custodian())));
        }
        if (debit != null) {
            receipt.put("beneficial", Money.text(
                    candidateBook.detailOf(debit.custodian(), debit.client())));
            receipt.put("pool", Money.text(candidateBook.poolAssetOf(candidateBank,
                    debit.custodian())));
        }
        String text = receipt(true, "ACCEPTED", receipt);
        CustodyBook recorded = candidateBook.withCommand(payment.paymentId(),
                new BankState.CommandRecord(canonicalBody(body), text, true));
        return new Result(true, "ACCEPTED", text, candidateBank, candidateAccounts, recorded);
    }

    /** 解析后的托管坐标：经营人、客户与池存款桶。 */
    private record Ref(String custodian, String client, Fund.Deposit pool) {
    }

    private static Ref custodyRef(BankChart chart, Fund.Custody custody, String payer,
                                  Map<String, CashLedger.CashAccount> accounts, boolean debit) {
        BankingProfile.CustodyRef ref =
                chart.profile().custodians().get(custody.custodianKey());
        if (ref == null) {
            throw new BankException(Reasons.CUSTODY_UNKNOWN_CUSTODIAN + ":"
                    + custody.custodianKey());
        }
        BankingProfile.PartyRef client = chart.profile().parties().get(custody.clientKey());
        if (client == null) {
            throw new BankException(Reasons.CUSTODY_UNKNOWN_CLIENT + ":" + custody.clientKey());
        }
        CashLedger.CashAccount account = accounts.get(client.cashAccountId());
        if (account == null || !account.ownerId().equals(client.ownerId())) {
            throw new BankException(Reasons.CUSTODY_UNKNOWN_CLIENT + ":account:"
                    + custody.clientKey());
        }
        if (client.ownerId().equals(ref.ownerId())) {
            throw new BankException(Reasons.CUSTODY_DETAIL_INVALID + ":self:"
                    + custody.token());
        }
        if (debit) {
            if (!chart.hasParty(payer)
                    || !chart.ownerIdOf(payer).equals(client.ownerId())) {
                throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":owner:"
                        + custody.token());
            }
        }
        return new Ref(custody.custodianKey(), custody.clientKey(),
                new Fund.Deposit(ref.bankId(), custody.custodianKey()));
    }

    private static void putDetail(Map<String, BigDecimal> details, Ref ref, BigDecimal amount) {
        String key = CustodyBook.detailKey(ref.custodian(), ref.client());
        if (amount.signum() == 0) {
            details.remove(key);
        } else {
            details.put(key, Money.scale2(amount));
        }
    }

    /**
     * 派生腿的冻结快照：客户托管桶已被本入口核过并从快照里移除，池层冻结为其他来源
     * 保留（同桶非零冻结已在上面明确拒绝），其余桶原样传递，绝不静默置空。
     */
    private static CashFreeze freezeForLeg(CashFreeze frozen, Fund custodySource) {
        Map<String, BigDecimal> kept = new TreeMap<>(frozen.frozenByBucket());
        if (custodySource != null) {
            kept.remove(custodySource.token());
        }
        kept.entrySet().removeIf(entry -> entry.getValue().signum() == 0);
        return new CashFreeze(kept);
    }

    private static Map<String, String> bodyOf(BankPayment payment) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("payer", payment.payerKey());
        body.put("src", payment.source().token());
        body.put("dst", payment.destination().token());
        body.put("amount", Money.canonical(payment.amount().toPlainString()));
        return body;
    }

    private static String canonicalBody(Map<String, String> body) {
        StringBuilder sb = new StringBuilder("CUSTODY_PAYMENT");
        new TreeMap<>(body).forEach((key, value) ->
                sb.append('\n').append(key).append('=').append(value));
        return sb.toString();
    }

    private static String receipt(boolean accepted, String reason, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("accepted=").append(accepted).append(";reason=").append(reason);
        new TreeMap<>(fields).forEach((key, value) ->
                sb.append(';').append(key).append('=').append(value));
        return sb.toString();
    }

    private static String reasonOf(String receipt) {
        for (String part : receipt.split(";")) {
            if (part.startsWith("reason=")) {
                return part.substring("reason=".length());
            }
        }
        return "UNKNOWN";
    }

    /** 把派生腿回执里的字段并入本次回执（保留 balance/frozen/available 等证据）。 */
    private static void merge(Map<String, String> receipt, String legReceipt) {
        if (legReceipt == null) {
            return;
        }
        for (String part : legReceipt.split(";")) {
            int split = part.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = part.substring(0, split);
            if ("accepted".equals(key) || "reason".equals(key)) {
                continue;
            }
            receipt.putIfAbsent(key, part.substring(split + 1));
        }
    }

    private static Result reject(String reason, BankState bank,
                                 Map<String, CashLedger.CashAccount> accounts, CustodyBook book,
                                 String paymentId, Map<String, String> body,
                                 Map<String, String> fields) {
        Map<String, String> receipt = fields == null ? new TreeMap<>() : new TreeMap<>(fields);
        String text = receipt(false, reason, receipt);
        CustodyBook recorded = book;
        if (paymentId != null && book.runId() != null) {
            // 拒绝回执只在明细账上留下幂等记录：池存款、明细、现金都保持入参原样。
            recorded = book.withCommand(paymentId,
                    new BankState.CommandRecord(canonicalBody(body), text, false));
        }
        return new Result(false, reason, text, bank, accounts, recorded);
    }
}
