package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 当期交付与实付流量、期末风险存量；仅收录有真实底账的正数量。 */
final class IssuerPeriodEvidence {
    private enum Kind {
        DELIVERED_REVENUE("OPERATING_COMMITMENT", "本期实际交付确认收入（元）"),
        ACCEPTED_WORK("OPERATING_COMMITMENT", "本期获客户验收工程量（单位）"),
        SELL_THROUGH("OPERATING_COMMITMENT", "本期渠道终售数量（件）"),
        DELIVERED_POWER("OPERATING_COMMITMENT", "本期实际交付电量（kWh）"),
        ORIGINATED_LOANS("OPERATING_COMMITMENT", "本期具名合同实际发放本金（元）"),
        RECEIVABLES("RISK", "期末未收贸易款（元）"),
        LOAN_EXPOSURE("RISK", "期末贷款账面本金（元）"),
        STAGE_SPENDING("LONG_TERM_INVESTMENT", "本期实际支付研发阶段费用（元）"),
        EXPANSION_SPENDING("LONG_TERM_INVESTMENT", "本期实际支付扩建款（元）");

        final String dimension;
        final String label;

        Kind(String dimension, String label) {
            this.dimension = dimension;
            this.label = label;
        }
    }

    private static final String PREFIX = "issuer-fact:";
    private final String company;
    private final long period;
    private final long cutoff;
    private final Map<Kind, BigDecimal> totals = new java.util.EnumMap<>(Kind.class);

    private IssuerPeriodEvidence(String company, long period, long cutoff) {
        this.company = company;
        this.period = period;
        this.cutoff = cutoff;
    }

    static String dimension(String ref) {
        if (!ref.startsWith(PREFIX)) return null;
        for (Kind kind : Kind.values()) {
            if (ref.startsWith(PREFIX + kind.name() + ":")) return kind.dimension;
        }
        return null;
    }

    static Map<String, String> collect(String company, long period, long cutoff,
            CompanyOperationsRun ops, CompanyOperationsRun prior, ResearchRun research,
            BankRun bank, BankActions.Book loans, Map<String, CashLedger.CashAccount> accounts) {
        IssuerPeriodEvidence facts = new IssuerPeriodEvidence(company, period, cutoff);
        if (research != null && company.equals(research.companyPartyId())) {
            for (var project : research.state().projects().values()) {
                for (var stage : project.stages()) {
                    facts.flow(Kind.STAGE_SPENDING, stage.beganAt(), stage.plan().cost());
                }
            }
        } else if (ops != null && ops.company(company) != null) {
            var ledger = ops.company(company).ledger();
            for (var bill : ledger.receivables().values()) {
                facts.flow(Kind.DELIVERED_REVENUE, bill.recognizedAt(), bill.amount());
                facts.add(Kind.RECEIVABLES, bill.outstanding());
            }
            for (var project : ledger.expansions().values()) {
                facts.flow(Kind.EXPANSION_SPENDING, project.startedAt(), project.capex());
            }
        } else if (ops != null && ops.resource() != null && company.equals(ops.resource().companyPartyId())) {
            var ledger = ops.resource().ledger();
            for (var bill : ledger.receivables().values()) {
                facts.flow(Kind.DELIVERED_REVENUE, bill.recognizedAt(), bill.amount());
                if (bill.status() == IntegratedResourcesModel.ReceivableStatus.OPEN)
                    facts.add(Kind.RECEIVABLES, bill.amount().subtract(bill.paid()));
            }
            for (var project : ledger.expansions().values()) {
                facts.flow(Kind.EXPANSION_SPENDING, project.startedAt(), project.capex());
            }
        } else if (ops != null && ops.digital() != null && company.equals(ops.digital().companyPartyId())) {
            var ledger = ops.digital().ledger();
            for (var bill : ledger.receivables().values()) {
                facts.flow(Kind.DELIVERED_REVENUE, bill.recognizedAt(), bill.revenue());
                if (bill.status() == DigitalServiceModel.ReceivableStatus.OPEN)
                    facts.add(Kind.RECEIVABLES, bill.outstanding());
            }
            for (var stage : ledger.stages().values()) {
                facts.flow(Kind.STAGE_SPENDING, stage.beganAt(), stage.cost());
            }
        } else if (ops != null && ops.aged() != null && company.equals(ops.aged().companyPartyId())) {
            var ledger = ops.aged().ledger();
            long before = prior == null ? 0 : prior.aged().ledger().sales().stream()
                    .mapToLong(AgedInventoryModel.Sale::units).sum();
            long sold = ledger.sales().stream().mapToLong(AgedInventoryModel.Sale::units).sum();
            facts.add(Kind.SELL_THROUGH, BigDecimal.valueOf(sold - before));
            for (var channel : ledger.channelLedgers()) facts.add(Kind.RECEIVABLES, channel.receivable());
        } else if (ops != null && ops.hydro() != null && company.equals(ops.hydro().companyPartyId())) {
            var ledger = ops.hydro().ledger();
            var report = HydroModel.report(ledger, accounts);
            BigDecimal before = prior == null ? BigDecimal.ZERO : prior.hydro().ledger().plant().deliveredKilowattHours();
            facts.add(Kind.DELIVERED_POWER, report.deliveredKilowattHours().subtract(before));
            facts.add(Kind.RECEIVABLES, report.receivable());
        } else if (bank != null) {
            BankState state = bank.decode(accounts).state();
            if (state.chart().isBank(company)) {
                for (String bucket : state.chart().loanAccounts()) {
                    facts.add(Kind.LOAN_EXPOSURE, state.gl(company, bucket));
                }
                if (loans != null) for (var loan : loans.loans().values()) {
                    if (!company.equals(loan.bankKey()) || loan.trancheId() == null) continue;
                    var tranche = state.loan(company, loan.borrowerKey()).tranche(loan.trancheId());
                    facts.flow(Kind.ORIGINATED_LOANS, tranche.terms().startMs(), loan.amount());
                }
            }
        }
        return facts.render();
    }

    static Map<String, String> engineering(String company, long period, EngineeringModel.State current, EngineeringModel.State previous) {
        var facts = new IssuerPeriodEvidence(company, period, Math.multiplyExact(period, PeriodicModel.PERIOD_MILLIS));
        for (var contract : current.contracts().values()) {
            var before = previous == null ? null : previous.contracts().get(contract.contractId());
            long units = contract.acceptedUnits() - (before == null ? 0 : before.acceptedUnits());
            facts.add(Kind.ACCEPTED_WORK, BigDecimal.valueOf(units));
        }
        for (var claim : current.claims().values()) {
            facts.add(Kind.RECEIVABLES, claim.arCurrent().add(claim.arRetention()));
        }
        return facts.render();
    }

    static String appendTo(String body, Map<String, String> facts) {
        if (facts.isEmpty()) return body;
        StringBuilder result = new StringBuilder(body).append("\n本期评分证据（按期末冻结账）\n");
        facts.forEach((ref, value) -> result.append(ref).append(" ").append(value).append('\n'));
        return result.toString();
    }

    static String reviewSource(String company, long period) {
        return "periodic-board-result:" + company + ":" + period;
    }

    /** 关期已保存的聚合结论成为公司董事可读材料；不写入任何人的阅读经历。 */
    static InformationRun publishReview(InformationRun information, PeriodicModel.CompanyPeriods periods,
            long period, long now) {
        var score = periods.scores().stream().filter(row -> row.period() == period).findFirst().orElseThrow();
        String source = reviewSource(score.companyPartyId(), period);
        var audience = InformationModel.Audience.board(score.companyPartyId());
        var info = information.information();
        String id = source;
        for (int suffix = 1; InformationModel.version(info, id, 1L).isPresent(); suffix++) {
            id = source + ":" + suffix;
        }
        var version = new InformationModel.VersionDraft("董事会考核结论 " + score.companyPartyId() + " 第" + period + "期",
                reviewBody(periods, score, now), "按章程归档的董事会考核结果", now, 1_000, java.util.List.of(source));
        return information.withInformation(InformationModel.publish(info, id, ResearchRuntime.SYSTEM_PUBLISHER, audience, version));
    }

    static String reviewDocument(InformationModel.State info, PeriodicModel.CompanyPeriods periods,
            PeriodicModel.Score score, long closedAt) {
        String source = reviewSource(score.companyPartyId(), score.period());
        var audience = InformationModel.Audience.board(score.companyPartyId());
        for (var doc : info.documents()) {
            if (!doc.publisherPartyId().equals(ResearchRuntime.SYSTEM_PUBLISHER) || !doc.audience().equals(audience)) continue;
            var first = doc.at(1L);
            if (first.publishedAt() == closedAt && first.internalSourceIds().equals(java.util.List.of(source))
                    && first.body().equals(reviewBody(periods, score, closedAt))) return doc.docId();
        }
        return null;
    }

    private static String reviewBody(PeriodicModel.CompanyPeriods periods, PeriodicModel.Score score, long now) {
        long period = score.period();
        long submitted = periods.ballots().stream().filter(b -> b.period() == period).count();
        var body = new StringBuilder("公司=").append(score.companyPartyId()).append(" 期间=").append(period)
                .append(" 采样截止=").append(Math.multiplyExact(period, PeriodicModel.PERIOD_MILLIS))
                .append(" 定稿时间=").append(now).append("\n结论=").append(score.status()).append(" 原因=").append(score.reason())
                .append(" 已投席数=").append(submitted).append(" 完整席数=").append(score.seatCount());
        if (score.total() != null) {
            body.append("\n加权总分=").append(score.total().toPlainString());
            for (var dimension : BoardModel.Dimension.values()) {
                body.append("\n").append(dimension).append(" 各完整席分数之和=")
                        .append(score.sums().get(dimension.ordinal()).toPlainString());
            }
        }
        return body.toString();
    }

    private void flow(Kind kind, long at, BigDecimal amount) {
        if (at > cutoff - PeriodicModel.PERIOD_MILLIS && at <= cutoff) add(kind, amount);
    }

    private void add(Kind kind, BigDecimal amount) {
        if (amount.signum() > 0) totals.merge(kind, amount, BigDecimal::add);
    }

    private Map<String, String> render() {
        Map<String, String> lines = new TreeMap<>();
        totals.forEach((kind, amount) -> lines.put(PREFIX + kind.name() + ":" + company + ":" + period,
                kind.label + "=" + amount.toPlainString()));
        return Collections.unmodifiableMap(lines);
    }
}
