package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 确定性实验开局：三个银行（厚/薄资本）、若干公众现金与一笔聚合贷款。
 *
 * <p>与 {@link OpeningRegistry} 绑定：银行主体必须是已登记组织；开局同时把运行身份与
 * 档案指纹写入状态，之后 apply 必须与之匹配。数字是历史实验禀赋（有来源、账平），
 * 不是校准结论。</p>
 */
public final class BankOpening {

    /** 开局结果：账簿 + 现金账户。
     * @param state state 参数
     * @param accounts accounts 参数
     */
public record Result(BankState state, Map<String, CashLedger.CashAccount> accounts) {
    }

    private BankOpening() {
    }

    /**
     * 标准开局（A 准备金 3700、实收 11800），并绑定 run 与档案。
     *
     * @param registry 开局登记（校验主体种类）
     * @param runId 运行标识
     * @param config 冻结档案
     * @return 开局结果
     */
    public static Result standard(OpeningRegistry registry, String runId, BankConfig config) {
        return open(registry, new BigDecimal("3700.00"), runId, config);
    }

    /**
     * 指定 A 行准备金的开局（用于流动性压力夹具；实收按账平调整）。
     *
     * @param registry 开局登记
     * @param reserveA A 行准备金
     * @param runId 运行标识
     * @param config 冻结档案
     * @return 开局结果
     */
    public static Result open(OpeningRegistry registry, BigDecimal reserveA, String runId,
                              BankConfig config) {
        Map<String, CashLedger.CashAccount> accounts = new LinkedHashMap<>();
        for (String bank : BankChart.BANKS) {
            requireOrganization(registry, bank);
        }
        for (String person : List.of("CUST-X", "CUST-Y", "ALICE", "PAYER", "BIG", "BG-DEP",
                "CO-C", "CO-D", "CO-E", "AGG", "SUPPLIER")) {
            requireOwner(registry, person);
        }
        requireOwner(registry, BankRules.PROVIDER);

        BankState.Draft draft = BankState.empty().draft();
        cash(accounts, "BANK-A", reserveA);
        cash(accounts, "BANK-B", new BigDecimal("1600.00"));
        cash(accounts, "BANK-C", new BigDecimal("200.00"));
        cash(accounts, "ALICE", new BigDecimal("1000.00"));
        cash(accounts, "PAYER", new BigDecimal("300.00"));
        cash(accounts, "BIG", new BigDecimal("6000.00"));
        cash(accounts, BankRules.PROVIDER, new BigDecimal("800.00"));
        for (String zero : List.of("CUST-X", "CUST-Y", "BG-DEP", "CO-C", "CO-D", "CO-E", "AGG",
                "SUPPLIER")) {
            cash(accounts, zero, Money.zero());
        }

        bank(draft, "BANK-A", reserveA, new String[][]{
                {"LOAN.BG", "6000.00"}, {"LLP.BG", "-600.00"}, {"PREMISES", "10000.00"},
                {"LOAN.AGG", "500.00"}, {"LLP.AGG", "-50.00"},
                {"DEPOSIT.BG", "-6500.00"}, {"DEPOSIT.X", "-500.00"},
                {"DEPOSIT.AGG", "-450.00"}, {"SUBT2", "-300.00"}});
        bank(draft, "BANK-B", new BigDecimal("1600.00"), new String[][]{
                {"LOAN.BG", "3200.00"}, {"LLP.BG", "-320.00"},
                {"DEPOSIT.BG", "-2800.00"}, {"DEPOSIT.Y", "-300.00"}});
        bank(draft, "BANK-C", new BigDecimal("200.00"), new String[][]{
                {"LOAN.BG", "2000.00"}, {"LOAN.E", "300.00"},
                {"DEPOSIT.BG", "-2120.00"}, {"DEPOSIT.E", "-300.00"}});

        draft.putLoan("BANK-A", "AGG", new BankState.Loan(new BigDecimal("500.00"),
                new BigDecimal("500.00"), Money.zero()));
        draft.putLoan("BANK-C", "CO-E", new BankState.Loan(new BigDecimal("300.00"),
                new BigDecimal("300.00"), Money.zero()));
        draft.putOrigin("OPEN-ALICE-CASH-1", new BankState.Origin("ALICE", "BANK-A",
                new BigDecimal("1000.00"), "legacy-cash-1", false));
        BigDecimal backing = Money.zero();
        for (CashLedger.CashAccount account : accounts.values()) {
            backing = backing.add(account.cash());
        }
        draft.backing(backing);
        openingDepositPolicy(draft, config);
        openingCollateral(draft, config);
        if (config.facilityEnabled()) {
            providerOpening(draft, accounts, "opening_declared_provider_cash");
        }
        draft.bindRun(runId, config.fingerprint());
        BankState state = draft.freeze();
        List<String> problems = BankArchiveValidator.validateActivation(config, state, accounts);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("ACTIVATION_INVALID:" + problems.get(0));
        }
        return new Result(state, Map.copyOf(accounts));
    }

    /**
     * 启用利息档案时，为开局既有非零存款建显式非应计政策（excluded=开局余额，原因可查询）。
     *
     * <p>旧 NONE 档案保持旧行为：不建政策记录（该档案本身明确无息）。</p>
     *
     * @param draft 开局草稿
     * @param config 冻结档案
     */
    private static void openingDepositPolicy(BankState.Draft draft, BankConfig config) {
        if (!config.interestEnabled()) {
            return;
        }
        for (String bank : BankChart.BANKS) {
            for (Map.Entry<String, String> entry : BankChart.DEPOSIT_TAG.entrySet()) {
                BigDecimal balance = draft.gl(bank, "DEPOSIT." + entry.getValue()).negate();
                if (balance.signum() <= 0) {
                    continue;
                }
                draft.putDepositAccount(bank, entry.getKey(), new BankState.DepositAccount(null,
                        0L, 0L, 0L, java.math.BigInteger.ZERO, 0L, 0L, 0L,
                        InterestAccrual.cents(balance), "opening_balance_before_offer",
                        "opening_balance_before_offer"));
            }
        }
    }

    /**
     * 启用 B3/B4 档案时把档案估值簿落成真实押品记录；底层资产不足则拒绝开局（不造无证资产）。
     *
     * @param draft 开局草稿
     * @param config 冻结档案
     */
    private static void openingCollateral(BankState.Draft draft, BankConfig config) {
        if (!config.facilityEnabled()) {
            return;
        }
        for (BankConfig.CollateralDecl decl : config.facilityTerms().collaterals()) {
            BigDecimal book;
            if ("PREMISES".equals(decl.kind())) {
                book = draft.gl(decl.ownerBank(), "PREMISES");
            } else {
                int split = decl.assetKey().indexOf('|');
                String borrower = decl.assetKey().substring(split + 1);
                if (draft.loan(decl.ownerBank(), borrower).tranches().isEmpty()) {
                    throw new IllegalArgumentException(Reasons.COLLATERAL_ASSET_UNKNOWN + ":"
                            + decl.assetKey());
                }
                book = draft.loan(decl.ownerBank(), borrower).onbook();
            }
            if (book.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_ASSET_UNKNOWN + ":"
                        + decl.assetKey());
            }
            if (decl.appraisedValue().compareTo(book) > 0) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_OVER_BOOK + ":"
                        + decl.collateralId());
            }
            draft.putCollateral(new BankState.Collateral(decl.collateralId(), decl.ownerBank(),
                    decl.kind(), decl.assetKey(), book, decl.appraisedValue(), decl.haircutU(),
                    null, BankRules.COLLATERAL_FREE));
        }
    }

    /**
     * 冻结提供者开局事实：现金 + 既有债权 = 初始资本，来源可查；后续不得用当前值反推。
     *
     * @param draft 开局草稿
     * @param accounts 现金账户
     * @param source 来源说明
     */
    private static void providerOpening(BankState.Draft draft,
                                        Map<String, CashLedger.CashAccount> accounts,
                                        String source) {
        CashLedger.CashAccount account = accounts.get(BankChart.cashAccount(BankRules.PROVIDER));
        BigDecimal cash = account == null ? Money.zero() : account.cash();
        BigDecimal claims = Money.zero();
        for (BankState.FacilityAccount facility : draft.facilityAccounts().values()) {
            if (facility.provider().equals(BankRules.PROVIDER)) {
                claims = claims.add(facility.nominal());
            }
        }
        draft.putProviderOpening(new BankState.ProviderOpening(BankRules.PROVIDER, cash, claims,
                Money.scale2(cash.add(claims)), source));
    }

    /**
     * 清算 850 例夹具（EXP，非校准）：仅用于复现已接受参考算例的 Java 版本。
     *
     * <p>资产 = 贷款 800 + 准备金 50；负债 = 存款 400 + 已提设施 250 + 次级 100；权益 100。
     * 提供者自有现金 800 只在其现金账户，银行账簿只记名义债务。历史禀赋，来源标注为实验。</p>
     *
     * @param registry 开局登记
     * @param runId 运行标识
     * @param config 冻结档案（必须启用 B3/B4）
     * @return 开局结果
     */
    public static Result resolution850(OpeningRegistry registry, String runId,
                                       BankConfig config) {
        if (!config.facilityEnabled()) {
            throw new IllegalArgumentException(Reasons.FACILITY_PROFILE_INVALID
                    + ":resolution850");
        }
        requireOrganization(registry, "BANK-C");
        for (String owner : List.of("BG-DEP", "BIG", "ALICE")) {
            requireOwner(registry, owner);
        }
        requireOwner(registry, BankRules.PROVIDER);
        requireOwner(registry, BankChart.SUBORDINATED_OWNER);

        Map<String, CashLedger.CashAccount> accounts = new LinkedHashMap<>();
        cash(accounts, "BANK-C", new BigDecimal("50.00"));
        cash(accounts, "BG-DEP", Money.zero());
        cash(accounts, "BIG", new BigDecimal("6000.00"));
        cash(accounts, "ALICE", new BigDecimal("1000.00"));
        cash(accounts, BankRules.PROVIDER, new BigDecimal("550.00"));
        cash(accounts, BankChart.SUBORDINATED_OWNER, Money.zero());

        BankState.Draft draft = BankState.empty().draft();
        draft.setGl("BANK-C", BankChart.RESERVE, new BigDecimal("50.00"));
        draft.setGl("BANK-C", "LOAN.C", new BigDecimal("800.00"));
        draft.setGl("BANK-C", BankChart.depositAccount("BG-DEP"), new BigDecimal("-400.00"));
        draft.setGl("BANK-C", BankChart.FACILITY_DEBT, new BigDecimal("-250.00"));
        draft.setGl("BANK-C", "SUBT2", new BigDecimal("-100.00"));
        draft.setGl("BANK-C", "PAIDIN", new BigDecimal("-100.00"));
        draft.putLoan("BANK-C", "CO-C", new BankState.Loan(new BigDecimal("800.00"),
                new BigDecimal("800.00"), Money.zero()));
        long tenor = 365L * InterestAccrual.DAY_MS;
        draft.putFacilityAccount(new BankState.FacilityAccount("BANK-C", "F-C-1",
                BankRules.PROVIDER, new BigDecimal("250.00"), new BigDecimal("250.00"),
                new BigDecimal("250.00"), Money.zero(), Money.zero(), 0L, tenor, 0L, tenor,
                tenor, java.math.BigInteger.ZERO, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                BankRules.COLLATERAL_UNSECURED, java.util.Set.of(), BankRules.FACILITY_OPEN));
        draft.putDepositAccount("BANK-C", "BG-DEP", new BankState.DepositAccount(null, 0L, 0L, 0L,
                java.math.BigInteger.ZERO, 0L, 0L, 0L, InterestAccrual.cents(
                        new BigDecimal("400.00")), "opening_balance_before_offer",
                "opening_balance_before_offer"));
        BigDecimal backing = Money.zero();
        for (CashLedger.CashAccount account : accounts.values()) {
            backing = backing.add(account.cash());
        }
        draft.backing(backing);
        providerOpening(draft, accounts,
                "verified_resolution_850: prior draw 250 already disbursed");
        draft.bindRun(runId, config.fingerprint());
        BankState state = draft.freeze();
        List<String> problems = BankInvariants.check(state, accounts);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("RESOLUTION850_INVALID:" + problems.get(0));
        }
        return new Result(state, Map.copyOf(accounts));
    }

    /**
     * 单行开局非现金资产负债：科目 → 金额；资本来源用于平衡差额。
     *
     * @param gl 科目 → 金额
     * @param capitalSource 差额资本来源（可为 null）
     */
    public record BankBalance(Map<String, BigDecimal> gl, String capitalSource) {
        /** 复制科目表。 */
        public BankBalance {
            gl = Map.copyOf(Objects.requireNonNull(gl, "gl"));
        }
    }

    /**
     * 贷款历史禀赋：银行 key、借款人 key 与期次。
     *
     * @param bank 银行 key
     * @param borrower 借款人 key
     * @param loan 贷款期次
     */
    public record LoanEndowment(String bank, String borrower, BankState.Loan loan) {
        /** 校验必填。 */
        public LoanEndowment {
            Objects.requireNonNull(bank, "bank");
            Objects.requireNonNull(borrower, "borrower");
            Objects.requireNonNull(loan, "loan");
        }
    }

    /**
     * 存款历史禀赋：银行 key、持有人 key、余额与冻结条款（可为 null）。
     *
     * @param bank 银行 key
     * @param holder 持有人 key
     * @param balance 存款余额
     * @param terms 冻结条款（可为 null）
     */
    public record DepositEndowment(String bank, String holder, BigDecimal balance,
                                   BankState.DepositAccount terms) {
        /** 校验余额非负。 */
        public DepositEndowment {
            Objects.requireNonNull(bank, "bank");
            Objects.requireNonNull(holder, "holder");
            balance = Money.scale2(Objects.requireNonNull(balance, "balance"));
            if (balance.signum() < 0) {
                throw new IllegalArgumentException("存款余额不能为负");
            }
        }
    }

    /**
     * 真实世界开局的显式非现金资产负债禀赋。
     *
     * @param banks 银行 key → 非现金科目
     * @param loans 贷款历史禀赋
     * @param deposits 存款历史禀赋
     * @param facilities 设施历史禀赋
     * @param collaterals 押品历史禀赋
     * @param origins 来源记录
     * @param providerOpenings 提供者冻结初始资本
     * @param source 开局来源
     */
    public record DeclaredEndowment(Map<String, BankBalance> banks,
                                    List<LoanEndowment> loans,
                                    List<DepositEndowment> deposits,
                                    List<BankState.FacilityAccount> facilities,
                                    List<BankState.Collateral> collaterals,
                                    Map<String, BankState.Origin> origins,
                                    List<BankState.ProviderOpening> providerOpenings,
                                    String source) {
        /**
         * 旧七参构造：无提供者冻结资本。
         *
         * @param banks 银行 key → 非现金科目
         * @param loans 贷款历史禀赋
         * @param deposits 存款历史禀赋
         * @param facilities 设施历史禀赋
         * @param collaterals 押品历史禀赋
         * @param origins 来源记录
         * @param source 开局来源
         */
        public DeclaredEndowment(Map<String, BankBalance> banks,
                                 List<LoanEndowment> loans,
                                 List<DepositEndowment> deposits,
                                 List<BankState.FacilityAccount> facilities,
                                 List<BankState.Collateral> collaterals,
                                 Map<String, BankState.Origin> origins,
                                 String source) {
            this(banks, loans, deposits, facilities, collaterals, origins, List.of(), source);
        }

        /** 复制集合并校验来源。 */
        public DeclaredEndowment {
            banks = Map.copyOf(Objects.requireNonNull(banks, "banks"));
            loans = List.copyOf(Objects.requireNonNull(loans, "loans"));
            deposits = List.copyOf(Objects.requireNonNull(deposits, "deposits"));
            facilities = List.copyOf(Objects.requireNonNull(facilities, "facilities"));
            collaterals = List.copyOf(Objects.requireNonNull(collaterals, "collaterals"));
            origins = Map.copyOf(Objects.requireNonNull(origins, "origins"));
            providerOpenings = List.copyOf(Objects.requireNonNull(providerOpenings,
                    "providerOpenings"));
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("开局来源不能为空");
            }
        }
    }

    /**
     * 按真实登记与显式非现金禀赋开局：不调用参考 {@link #standard}，不生成第二套现金。
     *
     * <p>传入的 {@code accounts} 是唯一现金权威，本方法只读不改；银行 RESERVE 必须等于
     * 其准备金现金账户。差额资本只有在 {@code capitalSource} 明确时才写入 PAIDIN，
     * 并留下 Origin；否则拒绝。任何未知科目、未知主体、账户属主不符都拒绝。</p>
     *
     * @param registry 开局登记
     * @param accounts 真实现金账户（唯一现金权威）
     * @param profile 冻结档案
     * @param config 冻结规则（必须与档案指纹一致）
     * @param endowment 显式非现金资产负债
     * @param runId 运行标识
     * @return 开局结果
     */
    public static Result openDeclared(OpeningRegistry registry,
                                      Map<String, CashLedger.CashAccount> accounts,
                                      BankingProfile profile, BankConfig config,
                                      DeclaredEndowment endowment, String runId) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(endowment, "endowment");
        if (!profile.fingerprint().equals(config.profile().fingerprint())) {
            throw new IllegalArgumentException("OPENING_PROFILE_MISMATCH");
        }
        BankChart chart = BankChart.of(profile);
        validateDeclared(registry, accounts, chart);
        BankState.Draft draft = BankState.empty(chart).draft();
        for (String bank : chart.banks()) {
            BankBalance balance = endowment.banks().get(bank);
            if (balance == null) {
                throw new IllegalArgumentException("OPENING_BANK_MISSING:" + bank);
            }
            Map<String, BigDecimal> gl = new LinkedHashMap<>(balance.gl());
            CashLedger.CashAccount reserve = accounts.get(chart.reserveAccountOf(bank));
            BigDecimal reserveCash = reserve == null ? Money.zero() : reserve.cash();
            BigDecimal declared = gl.remove(BankChart.RESERVE);
            if (declared != null && declared.compareTo(reserveCash) != 0) {
                throw new IllegalArgumentException("OPENING_RESERVE_MISMATCH:" + bank);
            }
            draft.setGl(bank, BankChart.RESERVE, reserveCash);
            BigDecimal sum = reserveCash;
            for (Map.Entry<String, BigDecimal> entry : gl.entrySet()) {
                if (!chart.glAccounts().contains(entry.getKey())) {
                    throw new IllegalArgumentException("OPENING_ACCOUNT_UNKNOWN:"
                            + entry.getKey());
                }
                BigDecimal value = Money.scale2(entry.getValue());
                draft.setGl(bank, entry.getKey(), value);
                sum = sum.add(value);
            }
            if (sum.signum() != 0) {
                if (balance.capitalSource() == null || balance.capitalSource().isBlank()) {
                    throw new IllegalArgumentException("OPENING_UNBALANCED:" + bank);
                }
                if (gl.containsKey("PAIDIN")) {
                    throw new IllegalArgumentException("OPENING_CAPITAL_CONFLICT:" + bank);
                }
                draft.setGl(bank, "PAIDIN", sum.negate());
                draft.putOrigin("OPENING-CAPITAL:" + bank, new BankState.Origin(
                        profile.banks().get(bank).ownerId(), bank, sum.abs(),
                        balance.capitalSource(), false));
            } else if (balance.capitalSource() != null
                    && !balance.capitalSource().isBlank()) {
                draft.putOrigin("OPENING-CAPITAL:" + bank, new BankState.Origin(
                        profile.banks().get(bank).ownerId(), bank,
                        draft.gl(bank, "PAIDIN").negate(), balance.capitalSource(), false));
            }
        }
        Set<String> loanKeys = new HashSet<>();
        for (LoanEndowment loan : endowment.loans()) {
            if (!chart.isBank(loan.bank()) || !chart.hasBucket(loan.borrower())) {
                throw new IllegalArgumentException("OPENING_LOAN_UNKNOWN:"
                        + loan.bank() + "|" + loan.borrower());
            }
            if (!loanKeys.add(chart.loanKeyOf(loan.bank(), loan.borrower()))) {
                throw new IllegalArgumentException("OPENING_LOAN_DUPLICATE:"
                        + loan.bank() + "|" + loan.borrower());
            }
            draft.putLoan(loan.bank(), loan.borrower(), loan.loan());
        }
        Set<String> depositKeys = new HashSet<>();
        for (DepositEndowment deposit : endowment.deposits()) {
            if (!chart.isBank(deposit.bank())) {
                throw new IllegalArgumentException("OPENING_DEPOSIT_BANK:" + deposit.bank());
            }
            String account = chart.depositAccountOf(deposit.holder());
            draft.setGl(deposit.bank(), account, deposit.balance().negate());
            if (!depositKeys.add(chart.depositTermsKeyOf(deposit.bank(), deposit.holder()))) {
                throw new IllegalArgumentException("OPENING_DEPOSIT_DUPLICATE:"
                        + deposit.bank() + "|" + deposit.holder());
            }
            if (deposit.terms() != null) {
                draft.putDepositAccount(deposit.bank(), deposit.holder(), deposit.terms());
            }
        }
        for (BankState.FacilityAccount facility : endowment.facilities()) {
            if (!chart.isBank(facility.bank())
                    || (chart.providerKey() != null
                    && !chart.providerKey().equals(facility.provider()))) {
                throw new IllegalArgumentException("OPENING_FACILITY_UNKNOWN:"
                        + facility.bank() + "|" + facility.provider());
            }
            draft.putFacilityAccount(facility);
        }
        if (endowment.collaterals().isEmpty()) {
            openingCollateral(draft, config);
        } else {
            for (BankState.Collateral collateral : endowment.collaterals()) {
                if (!chart.isBank(collateral.owner())) {
                    throw new IllegalArgumentException("OPENING_COLLATERAL_BANK:"
                            + collateral.owner());
                }
                draft.putCollateral(collateral);
            }
        }
        endowment.origins().forEach(draft::putOrigin);
        for (BankState.ProviderOpening opening : endowment.providerOpenings()) {
            draft.putProviderOpening(opening);
        }
        BigDecimal backing = Money.zero();
        for (CashLedger.CashAccount account : accounts.values()) {
            backing = backing.add(account.cash());
        }
        draft.backing(backing);
        draft.bindRun(runId, config.fingerprint());
        BankState state = draft.freeze();
        List<String> problems = BankInvariants.check(state, accounts);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("OPENING_INVARIANT:" + problems.get(0));
        }
        List<String> activation = BankArchiveValidator.validateActivation(config, state, accounts);
        if (!activation.isEmpty()) {
            throw new IllegalArgumentException("ACTIVATION_INVALID:" + activation.get(0));
        }
        for (DepositEndowment deposit : endowment.deposits()) {
            if (state.gl(deposit.bank(), chart.depositAccountOf(deposit.holder()))
                    .compareTo(deposit.balance().negate()) != 0) {
                throw new IllegalArgumentException("OPENING_DEPOSIT_MISMATCH:"
                        + deposit.bank() + "|" + deposit.holder());
            }
        }
        return new Result(state, Map.copyOf(accounts));
    }

    /**
     * AGG 提升本轮未实现：明确 fail-closed，不静默改写真实主体或历史。
     *
     * @param bankId 银行 key
     * @param targetParty 目标公司 key
     * @param profile 冻结档案
     * @return 不返回
     */
    public static Result promoteAggregate(String bankId, String targetParty,
                                          BankingProfile profile) {
        throw new UnsupportedOperationException("PROMOTION_UNSUPPORTED");
    }

    private static void validateDeclared(OpeningRegistry registry,
                                         Map<String, CashLedger.CashAccount> accounts,
                                         BankChart chart) {
        Set<String> owners = new HashSet<>();
        for (Map.Entry<String, BankingProfile.BankRef> entry : chart.profile().banks().entrySet()) {
            BankingProfile.BankRef bank = entry.getValue();
            OpeningRegistry.PropertyOwner owner = registry.owner(bank.ownerId());
            if (owner == null || owner.kind() != OpeningRegistry.OwnerKind.ORGANIZATION
                    || !owner.partyId().equals(bank.partyId())) {
                throw new IllegalArgumentException("OPENING_BANK_ORGANIZATION:"
                        + entry.getKey());
            }
            CashLedger.CashAccount reserve = accounts.get(bank.reserveAccountId());
            if (reserve == null || !reserve.ownerId().equals(bank.ownerId())) {
                throw new IllegalArgumentException("OPENING_RESERVE_OWNER:" + entry.getKey());
            }
            if (!owners.add(bank.ownerId())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_OWNER:" + bank.ownerId());
            }
        }
        Set<String> bankOwners = new HashSet<>(owners);
        Set<String> partyOwners = new HashSet<>();
        for (Map.Entry<String, BankingProfile.PartyRef> entry : chart.profile().parties().entrySet()) {
            BankingProfile.PartyRef party = entry.getValue();
            OpeningRegistry.PropertyOwner owner = registry.owner(party.ownerId());
            if (owner == null) {
                throw new IllegalArgumentException("OPENING_PARTY_REGISTRY:" + entry.getKey());
            }
            String declaredKind = party.ownerKind() == null ? null : party.ownerKind().name();
            if (declaredKind == null) {
                // 旧口径：只接受真实自然人/组织，partyId 必须与 registry 一致。
                if (owner.partyId() == null || !owner.partyId().equals(party.partyId())) {
                    throw new IllegalArgumentException("OPENING_PARTY_REGISTRY:" + entry.getKey());
                }
            } else if ("PRODUCT".equals(declaredKind)) {
                if (owner.kind() != OpeningRegistry.OwnerKind.PRODUCT
                        || owner.partyId() != null || party.partyId() != null) {
                    throw new IllegalArgumentException("OPENING_PARTY_KIND:" + entry.getKey());
                }
            } else if ("AGGREGATE".equals(declaredKind)) {
                if (owner.kind() != OpeningRegistry.OwnerKind.AGGREGATE
                        || owner.partyId() != null || party.partyId() != null) {
                    throw new IllegalArgumentException("OPENING_PARTY_KIND:" + entry.getKey());
                }
            } else {
                throw new IllegalArgumentException("OPENING_PARTY_KIND:" + entry.getKey());
            }
            if (bankOwners.contains(party.ownerId())) {
                throw new IllegalArgumentException("OPENING_PARTY_AS_BANK:" + entry.getKey());
            }
            CashLedger.CashAccount cash = accounts.get(party.cashAccountId());
            if (cash == null || !cash.ownerId().equals(party.ownerId())) {
                throw new IllegalArgumentException("OPENING_PARTY_CASH:" + entry.getKey());
            }
            if (!partyOwners.add(party.ownerId())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_OWNER:" + party.ownerId());
            }
        }
        for (Map.Entry<String, BankingProfile.CustodyRef> entry : chart.profile().custodians().entrySet()) {
            BankingProfile.CustodyRef custody = entry.getValue();
            OpeningRegistry.PropertyOwner owner = registry.owner(custody.ownerId());
            if (owner == null || !owner.partyId().equals(custody.partyId())) {
                throw new IllegalArgumentException("OPENING_CUSTODY_REGISTRY:" + entry.getKey());
            }
            if (!chart.isBank(custody.bankId())) {
                throw new IllegalArgumentException("OPENING_CUSTODY_BANK:" + entry.getKey());
            }
            if (!owners.add(custody.ownerId())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_OWNER:" + custody.ownerId());
            }
            if (partyOwners.contains(custody.ownerId()) || bankOwners.contains(custody.ownerId())) {
                throw new IllegalArgumentException("OPENING_PARTY_AS_CUSTODIAN:" + entry.getKey());
            }
        }
        Set<String> allowed = new HashSet<>(owners);
        allowed.addAll(partyOwners);
        for (CashLedger.CashAccount account : accounts.values()) {
            if (!allowed.contains(account.ownerId())) {
                throw new IllegalArgumentException("OPENING_UNKNOWN_CASH_OWNER:"
                        + account.ownerId());
            }
        }
    }

    private static void bank(BankState.Draft draft, String bank, BigDecimal reserve,
                             String[][] entries) {
        draft.setGl(bank, BankChart.RESERVE, reserve);
        BigDecimal sum = reserve;
        for (String[] entry : entries) {
            BigDecimal value = new BigDecimal(entry[1]);
            draft.setGl(bank, entry[0], value);
            sum = sum.add(value);
        }
        draft.setGl(bank, "PAIDIN", sum.negate());
    }

    private static void cash(Map<String, CashLedger.CashAccount> accounts, String owner,
                             BigDecimal amount) {
        String id = BankChart.BANKS.contains(owner)
                ? BankChart.reserveAccount(owner) : BankChart.cashAccount(owner);
        accounts.put(id, new CashLedger.CashAccount(owner, Money.scale2(amount),
                "实验开局历史禀赋"));
    }

    private static void requireOrganization(OpeningRegistry registry, String ownerId) {
        OpeningRegistry.PropertyOwner owner = registry.owner(ownerId);
        if (owner == null || owner.kind() != OpeningRegistry.OwnerKind.ORGANIZATION) {
            throw new IllegalArgumentException("银行主体必须是已登记组织：" + ownerId);
        }
    }

    private static void requireOwner(OpeningRegistry registry, String ownerId) {
        if (registry.owner(ownerId) == null) {
            throw new IllegalArgumentException("未登记的开局主体：" + ownerId);
        }
    }
}
