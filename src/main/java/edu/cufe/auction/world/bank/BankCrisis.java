package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 实验出资人与处置办公室的权限、授信条款和处置触发条件。 */
public final class BankCrisis {
    /** 只持有从资源出资人原禀赋划入的独立资金。 */
    public static final String PROVIDER = "bank-rescue-fund";
    /** 无权提供或提取救助资金，独立判断处置。 */
    public static final String AUTHORITY = "bank-resolution-office";
    private static final long DAY = 86_400_000L;
    private BankCrisis() { }

    /** @return 该动作需要的实际组织任职，普通银行动作返回空 */
    public static String office(BankActions.Kind kind, String bank) {
        return switch (kind) {
            case FACILITY_AUTHORIZE -> PROVIDER;
            case FACILITY_DRAW, FACILITY_PAY -> bank;
            case RESOLUTION_OPEN, RESOLUTION_DISTRIBUTE -> AUTHORITY;
            default -> null;
        };
    }

    /** @return 同一人在出资、处置及借款银行三方同时任职时不能执行该项。 */
    public static boolean eligible(java.util.Set<String> managed,String office,String bank) {
        return managed.contains(office) && java.util.stream.Stream.of(PROVIDER,AUTHORITY,bank)
                .filter(managed::contains).count() == 1;
    }

    /** @return 动作是否以零金额登记程序决定 */
    public static boolean procedural(BankActions.Kind kind) {
        return kind == BankActions.Kind.RESOLUTION_OPEN || kind == BankActions.Kind.RESOLUTION_DISTRIBUTE;
    }

    static BankActions.Outcome apply(BankState state, Map<String,CashLedger.CashAccount> cash,
            CustodyBook custody, BankActions.Book loans, BankConfig config, BankContext context,
            BankActions.Request request, BankActions.Actor actor, CashFreeze frozen) {
        String bank = request.bankKey();
        var office = office(request.kind(), bank);
        boolean bankOfficer = office.equals(bank);
        if (!PROVIDER.equals(config.profile().providerKey()) || !AUTHORITY.equals(config.facilityTerms().resolutionAuthority())
                || !office.equals(request.customerKey()) || (bankOfficer ? !actor.bankManager() || !bank.equals(actor.managedBank())
                : actor.bankManager() || !office.equals(actor.partyKey()) || !office.equals(actor.ownerId())))
            return new BankActions.Outcome(false,Reasons.ACTOR_NOT_AUTHORIZED,state,cash,custody,loans,"");
        var body = new LinkedHashMap<String,String>();
        body.put("bank", bank);
        if (procedural(request.kind())) {
            if (request.kind() == BankActions.Kind.RESOLUTION_OPEN) body.put("authority", AUTHORITY);
            if (request.kind() == BankActions.Kind.RESOLUTION_DISTRIBUTE && frozen.frozenByBucket().entrySet().stream()
                    .anyMatch(e -> e.getValue().signum() > 0 && e.getKey().startsWith("DEPOSIT|" + bank + "|")))
                return new BankActions.Outcome(false,"WAIT_FROZEN_DEPOSITS",state,cash,custody,loans,"");
            // EXP 处置条件：账面资不抵债，或准备金用尽且仍有客户存款未付。
            boolean hasDeposits = config.profile().depositAccounts().stream().anyMatch(gl -> state.gl(bank,gl).signum() < 0);
            var reserve = cash.get(config.chart().reserveAccountOf(bank)).cash();
            var due = state.facilityAccountsOf(bank).values().stream()
                    .filter(f -> f.facilityMaturityMs() <= context.atMillis())
                    .map(f -> f.nominal().add(f.interestPayable())).reduce(BigDecimal.ZERO,BigDecimal::add);
            if (request.kind() == BankActions.Kind.RESOLUTION_OPEN && state.bookEquity(bank).signum() >= 0
                    && !(reserve.signum() == 0 && hasDeposits) && due.compareTo(reserve) <= 0)
                return new BankActions.Outcome(false,"RESOLUTION_TRIGGER_ABSENT",state,cash,custody,loans,"");
        } else {
            body.put("provider",PROVIDER); body.put("authorization",request.loanId());
            if (request.kind() == BankActions.Kind.FACILITY_AUTHORIZE) {
                body.put("limit",Money.text(request.amount())); body.put("rate","0");
                body.put("loanTenorMs",Long.toString(30*DAY));
                body.put("authorizationExpiryMs",Long.toString(Math.addExact(context.atMillis(),3*DAY)));
                body.put("facilityMaturityMs",Long.toString(Math.addExact(context.atMillis(),33*DAY)));
                body.put("collateralPolicy",BankRules.COLLATERAL_UNSECURED);
            } else body.put("amount",Money.text(request.amount()));
        }
        var result = BankModel.apply(state,cash,config,context,new BankCommand(request.requestId(),
                BankCommand.Kind.valueOf(request.kind().name()),body,office),frozen);
        return new BankActions.Outcome(result.accepted(),result.reason(),result.state(),result.accounts(),custody,loans,result.receipt());
    }
}
