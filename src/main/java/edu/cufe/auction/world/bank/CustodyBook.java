package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 托管明细账：唯一可写的客户受益权余额，以及它与银行池存款的一致性检查。
 *
 * <p>三线口径（EXP，非现实合规）：银行存款债权的法定持有人是托管经营人，池存款记在
 * 银行总账的 {@link BankingProfile.CustodyRef#liabilityId()}；托管经营人对每个客户的
 * 明细负债只存在本账里，客户受益权由明细派生，不再存第二份可改余额。于是对每个经营人
 * 都有 {@code 池存款资产 − Σ客户明细 = 0}：经营人的托管业务净资产为零，客户财富只被
 * 计一次（明细），池存款是经营人的资产而不是客户的资产。</p>
 *
 * <p>本类不含现金也不含证券：现金唯一权威仍是 {@code CashLedger}，证券仍是
 * {@code SecuritiesLedger}（本切片不操作股票）。金额一律两位小数，不进银行总账；银行的
 * 存款利息条款与池无关 —— 池必须明确非应计（档案未提供计息 offer，或 offer 明确
 * {@link BankRules#CREDIT_NON_ACCRUAL}），否则拒绝而不是把已计息账户静默降成非应计。</p>
 *
 * <p>{@link #validate} 是组合校验：银行侧 {@link BankInvariants} 加本账的池资产/明细相等、
 * 身份与现金归属、非应计口径。普通 {@link BankModel} 命令没有本账参数，因此它能单独通过
 * 而池被提空；接入世界的每一类银行命令候选都必须调用本组合校验，不能声称纯银行模型
 * 已经阻止了所有外部调用。</p>
 */
public final class CustodyBook {

    /** 明细键的分隔符；档案 key 自身不得包含它。 */
    public static final String KEY_SEPARATOR = "|";

    /** 空池开局时记录在案的来源标记。 */
    public static final String ORIGIN_EMPTY_POOL = "OPENING_EMPTY_POOL";

    private final String runId;
    private final String configFingerprint;
    private final Map<String, BigDecimal> details;
    private final Map<String, String> origins;
    private final Map<String, BankState.CommandRecord> commands;
    private final BankChart chart;

    private CustodyBook(String runId, String configFingerprint,
                        Map<String, BigDecimal> details, Map<String, String> origins,
                        Map<String, BankState.CommandRecord> commands, BankChart chart) {
        this.runId = runId;
        this.configFingerprint = configFingerprint;
        this.details = details;
        this.origins = origins;
        this.commands = commands;
        this.chart = chart;
    }

    /**
     * 未绑定运行的空白账：任何付款都会以 {@link Reasons#NO_ACTIVE_RUN} 拒绝。
     *
     * @return 空白账（参考账表、无明细）
     */
    public static CustodyBook empty() {
        return new CustodyBook(null, null, Map.of(), Map.of(), Map.of(), BankChart.reference());
    }

    /**
     * 空池开局：要求每个托管经营人的池存款真实为零，不复制资产、不预置客户明细。
     *
     * @param bank     已绑定运行的银行状态
     * @param accounts 现金账户（唯一现金权威）
     * @param config   冻结档案
     * @return 已绑定运行的空账
     * @throws IllegalArgumentException 任一池存款非零或档案/口径不满足
     */
    public static CustodyBook openEmpty(BankState bank, Map<String, CashLedger.CashAccount> accounts,
                                        BankConfig config) {
        Objects.requireNonNull(bank, "bank");
        Objects.requireNonNull(config, "config");
        for (Map.Entry<String, BankingProfile.CustodyRef> entry
                : new TreeMap<>(config.profile().custodians()).entrySet()) {
            BankingProfile.CustodyRef ref = entry.getValue();
            BigDecimal asset = Money.scale2(bank.depositOf(ref.bankId(), entry.getKey()));
            if (asset.signum() != 0) {
                throw new IllegalArgumentException(Reasons.CUSTODY_OPENING_NOT_EMPTY + ":"
                        + entry.getKey() + ":asset=" + Money.text(asset));
            }
            for (String other : config.chart().banks()) {
                if (other.equals(ref.bankId())) {
                    continue;
                }
                BigDecimal foreign = Money.scale2(bank.depositOf(other, entry.getKey()));
                if (foreign.signum() != 0) {
                    throw new IllegalArgumentException(Reasons.CUSTODY_BANK_MISMATCH + ":"
                            + entry.getKey() + ":bank=" + other + ":asset="
                            + Money.text(foreign));
                }
                if (bank.depositAccount(other, entry.getKey()) != null) {
                    throw new IllegalArgumentException(Reasons.CUSTODY_BANK_MISMATCH + ":terms:"
                            + entry.getKey() + ":bank=" + other);
                }
            }
        }
        Map<String, String> origins = new TreeMap<>();
        for (String custodian : config.profile().custodians().keySet()) {
            origins.put(custodian, ORIGIN_EMPTY_POOL);
        }
        CustodyBook book = new CustodyBook(bank.runId(), config.fingerprint(), Map.of(),
                Map.copyOf(origins), Map.of(), config.chart());
        List<String> problems = book.validate(bank, accounts == null ? Map.of() : accounts, config);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(problems.get(0));
        }
        return book;
    }

    /**
     * 既有池开局：只用明确申报的历史客户明细，且每个池的合计必须精确等于该池已有存款。
     *
     * <p>不做资产/现金复制，不按余额自动均分，不给未申报的池分配余额；非空池必须带非空
     * 来源声明。申报表里出现未知托管经营人或未知客户一律拒绝。</p>
     *
     * @param bank     已绑定运行的银行状态
     * @param accounts 现金账户
     * @param config   冻结档案
     * @param declared 托管经营人 key → 客户 key → 明细金额（可为空表）
     * @param origins  托管经营人 key → 历史来源说明
     * @return 已绑定运行的明细账
     * @throws IllegalArgumentException 任一池的申报合计与池存款不等、缺来源或身份非法
     */
    public static CustodyBook declare(BankState bank, Map<String, CashLedger.CashAccount> accounts,
                                      BankConfig config,
                                      Map<String, Map<String, BigDecimal>> declared,
                                      Map<String, String> origins) {
        Objects.requireNonNull(bank, "bank");
        Objects.requireNonNull(config, "config");
        Map<String, Map<String, BigDecimal>> wanted =
                declared == null ? Map.of() : declared;
        Map<String, String> wantedOrigins = origins == null ? Map.of() : origins;
        Map<String, BigDecimal> details = new TreeMap<>();
        for (Map.Entry<String, Map<String, BigDecimal>> pool : wanted.entrySet()) {
            String custodian = pool.getKey();
            if (!config.profile().custodians().containsKey(custodian)) {
                throw new IllegalArgumentException(
                        Reasons.CUSTODY_UNKNOWN_CUSTODIAN + ":" + custodian);
            }
            Map<String, BigDecimal> clients = pool.getValue() == null ? Map.of() : pool.getValue();
            for (Map.Entry<String, BigDecimal> client : clients.entrySet()) {
                if (client.getValue() == null) {
                    throw new IllegalArgumentException(Reasons.CUSTODY_DETAIL_INVALID + ":null:"
                            + client.getKey());
                }
                BigDecimal amount = money(client.getValue(), client.getKey());
                if (amount.signum() == 0) {
                    continue;
                }
                details.put(detailKey(custodian, client.getKey()), amount);
            }
        }
        Map<String, String> kept = new TreeMap<>();
        for (Map.Entry<String, String> origin : wantedOrigins.entrySet()) {
            if (!config.profile().custodians().containsKey(origin.getKey())) {
                throw new IllegalArgumentException(
                        Reasons.CUSTODY_UNKNOWN_CUSTODIAN + ":" + origin.getKey());
            }
            if (origin.getValue() == null || origin.getValue().isBlank()) {
                continue;
            }
            kept.put(origin.getKey(), origin.getValue());
        }
        for (String custodian : config.profile().custodians().keySet()) {
            BankingProfile.CustodyRef ref = config.profile().custodians().get(custodian);
            BigDecimal asset = Money.scale2(bank.depositOf(ref.bankId(), custodian));
            BigDecimal liability = Money.scale2(sumOf(details.entrySet().stream()
                    .filter(entry -> custodianKey(entry.getKey()).equals(custodian))
                    .map(Map.Entry::getValue).toList()));
            String origin = kept.get(custodian);
            if (origin == null || origin.isBlank()) {
                if (asset.signum() != 0 || liability.signum() != 0) {
                    throw new IllegalArgumentException(
                            Reasons.CUSTODY_ORIGIN_MISSING + ":" + custodian);
                }
                kept.put(custodian, ORIGIN_EMPTY_POOL);
            }
            if (asset.compareTo(liability) != 0) {
                throw new IllegalArgumentException(Reasons.CUSTODY_POOL_MISMATCH + ":" + custodian
                        + ":asset=" + Money.text(asset) + ":declared=" + Money.text(liability));
            }
        }
        CustodyBook book = new CustodyBook(bank.runId(), config.fingerprint(), Map.copyOf(details),
                Map.copyOf(kept), Map.of(), config.chart());
        List<String> problems = book.validate(bank, accounts == null ? Map.of() : accounts, config);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(problems.get(0));
        }
        return book;
    }

    /**
     * 结构恢复（**不做经济校验**）：只核 runId 非空、指纹等于可信档案、明细/来源/回执的
     * 键与金额结构。它不知道银行状态与现金账户，因此**不能**证明池资产等于客户明细，
     * 也**不能**证明 runId 属于当前运行。
     *
     * <p>恢复路径必须改用 {@link #restoreChecked}；只有在需要先把结构解析出来、稍后由调用方
     * 自己核经济口径时才使用本方法，并明确知道它返回的账可能是非法账。</p>
     *
     * @param runId             运行标识
     * @param configFingerprint 档案指纹（须等于可信档案指纹）
     * @param details           明细键 → 金额
     * @param origins           托管经营人 → 来源说明
     * @param commands          命令回执
     * @param trusted           可信档案（提供账表与身份）
     * @return 结构合法的明细账（经济口径未核）
     * @throws IllegalArgumentException 结构非法或与可信档案不符
     */
    public static CustodyBook restoreStructural(String runId, String configFingerprint,
                                      Map<String, BigDecimal> details,
                                      Map<String, String> origins,
                                      Map<String, BankState.CommandRecord> commands,
                                      BankConfig trusted) {
        Objects.requireNonNull(trusted, "trusted");
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException(Reasons.NO_ACTIVE_RUN);
        }
        if (configFingerprint == null || !trusted.fingerprint().equals(configFingerprint)) {
            throw new IllegalArgumentException(Reasons.STATE_FINGERPRINT_MISMATCH + ":custody");
        }
        BankChart chart = trusted.chart();
        Map<String, BigDecimal> keptDetails = new TreeMap<>();
        if (details != null) {
            for (Map.Entry<String, BigDecimal> entry : details.entrySet()) {
                String key = entry.getKey();
                String custodian = custodianKey(key);
                String client = clientKey(key);
                if (!chart.isCustodian(custodian)) {
                    throw new IllegalArgumentException(
                            Reasons.CUSTODY_UNKNOWN_CUSTODIAN + ":" + custodian);
                }
                if (!chart.hasParty(client)) {
                    throw new IllegalArgumentException(Reasons.CUSTODY_UNKNOWN_CLIENT + ":"
                            + client);
                }
                BigDecimal amount = money(entry.getValue(), key);
                if (amount.signum() < 0) {
                    throw new IllegalArgumentException(Reasons.CUSTODY_DETAIL_INVALID + ":negative:"
                            + key);
                }
                if (amount.signum() != 0) {
                    keptDetails.put(detailKey(custodian, client), amount);
                }
            }
        }
        Map<String, String> keptOrigins = new TreeMap<>();
        if (origins != null) {
            for (Map.Entry<String, String> entry : origins.entrySet()) {
                if (!chart.isCustodian(entry.getKey())) {
                    throw new IllegalArgumentException(
                            Reasons.CUSTODY_UNKNOWN_CUSTODIAN + ":" + entry.getKey());
                }
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    keptOrigins.put(entry.getKey(), entry.getValue());
                }
            }
        }
        Map<String, BankState.CommandRecord> keptCommands = new TreeMap<>();
        if (commands != null) {
            for (Map.Entry<String, BankState.CommandRecord> entry : commands.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException(Reasons.CUSTODY_DETAIL_INVALID
                            + ":command");
                }
                if (entry.getValue() == null || entry.getValue().body() == null
                        || entry.getValue().receipt() == null) {
                    throw new IllegalArgumentException(Reasons.CUSTODY_DETAIL_INVALID
                            + ":receipt:" + entry.getKey());
                }
                keptCommands.put(entry.getKey(), entry.getValue());
            }
        }
        return new CustodyBook(runId, configFingerprint, Map.copyOf(keptDetails),
                Map.copyOf(keptOrigins), Map.copyOf(keptCommands), chart);
    }

    /**
     * 完整三态恢复入口：可信档案 + 银行状态 + 现金账户 + 明细/来源/回执，返回前调用
     * {@link #validate}，因此返回的账一定是合法账；runId/指纹必须与银行状态一致。
     *
     * <p>这是作者跨 JVM 与未来 World codec 的唯一恢复入口：任何结构上合法但经济上非法的
     * 账（篡改明细、错 run、错档案、错结算行、已有应计池）都在这里被拒，而不是等到下一次
     * 付款才被发现。</p>
     *
     * @param runId     期望运行标识（须等于银行状态的运行标识）
     * @param bank      已恢复的银行状态（可信 config 重建）
     * @param accounts  现金账户（唯一现金权威）
     * @param details   明细键 → 金额
     * @param origins   托管经营人 → 来源说明
     * @param commands  命令回执
     * @param trusted   可信档案
     * @return 通过组合校验的明细账
     * @throws IllegalArgumentException runId/指纹不符或组合校验不通过（首个原因）
     */
    public static CustodyBook restoreChecked(String runId, BankState bank,
                                             Map<String, CashLedger.CashAccount> accounts,
                                             Map<String, BigDecimal> details,
                                             Map<String, String> origins,
                                             Map<String, BankState.CommandRecord> commands,
                                             BankConfig trusted) {
        Objects.requireNonNull(bank, "bank");
        Objects.requireNonNull(trusted, "trusted");
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException(Reasons.NO_ACTIVE_RUN);
        }
        if (!runId.equals(bank.runId())) {
            throw new IllegalArgumentException(Reasons.STATE_FINGERPRINT_MISMATCH
                    + ":custody:run");
        }
        if (!trusted.fingerprint().equals(bank.configFingerprint())) {
            throw new IllegalArgumentException(Reasons.STATE_FINGERPRINT_MISMATCH
                    + ":custody:config");
        }
        CustodyBook book = restoreStructural(runId, bank.configFingerprint(), details, origins,
                commands, trusted);
        List<String> problems = book.validate(bank, accounts, trusted);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(problems.get(0));
        }
        return book;
    }

    /**
     * 运行标识（未开局为 null）。
     *
     * @return 运行标识
     */
    public String runId() {
        return runId;
    }

    /**
     * 绑定的档案指纹（未开局为 null）。
     *
     * @return 档案指纹
     */
    public String configFingerprint() {
        return configFingerprint;
    }

    /**
     * 明细键 → 金额（只含非零项，键为 {@code 托管经营人|客户}）。
     *
     * @return 只读明细
     */
    public Map<String, BigDecimal> details() {
        return details;
    }

    /**
     * 托管经营人 → 该池的历史来源声明。
     *
     * @return 只读来源
     */
    public Map<String, String> origins() {
        return origins;
    }

    /**
     * 命令标识 → 回执（含拒绝回执）。
     *
     * @return 只读回执表
     */
    public Map<String, BankState.CommandRecord> commands() {
        return commands;
    }

    /**
     * 账表（由可信档案重建，不接受 JSON 自报）。
     *
     * @return 账表
     */
    public BankChart chart() {
        return chart;
    }

    /**
     * 某客户在某托管经营人处的受益权（等于明细负债；不存在即零）。
     *
     * @param custodian 托管经营人 key
     * @param client    客户 key
     * @return 受益权金额
     */
    public BigDecimal detailOf(String custodian, String client) {
        BigDecimal amount = details.get(detailKey(custodian, client));
        return amount == null ? Money.zero() : amount;
    }

    /**
     * 某托管经营人的客户明细负债合计（= 该池的客户受益权总额）。
     *
     * @param custodian 托管经营人 key
     * @return 合计
     */
    public BigDecimal poolLiabilityOf(String custodian) {
        BigDecimal total = Money.zero();
        for (Map.Entry<String, BigDecimal> entry : details.entrySet()) {
            if (custodianKey(entry.getKey()).equals(custodian)) {
                total = total.add(entry.getValue());
            }
        }
        return Money.scale2(total);
    }

    /**
     * 某托管经营人的池存款资产：该经营人在其结算银行账簿上的存款债权。
     *
     * @param bank      银行状态
     * @param custodian 托管经营人 key
     * @return 池存款余额；经营人未在档案声明时抛出未知账户拒绝
     */
    public BigDecimal poolAssetOf(BankState bank, String custodian) {
        BankingProfile.CustodyRef custody = chart.profile().custodians().get(custodian);
        if (custody == null) {
            throw new BankException(Reasons.CUSTODY_UNKNOWN_CUSTODIAN + ":" + custodian);
        }
        return Money.scale2(bank.depositOf(custody.bankId(), custodian));
    }

    /**
     * 托管经营人的池净资产（资产减客户明细负债）；成立口径下恒为零。
     *
     * @param bank      银行状态
     * @param custodian 托管经营人 key
     * @return 净资产
     */
    public BigDecimal navOf(BankState bank, String custodian) {
        return Money.scale2(poolAssetOf(bank, custodian).subtract(poolLiabilityOf(custodian)));
    }

    /**
     * 命令回执（无记录返回 null）。
     *
     * @param commandId 命令标识
     * @return 回执或 null
     */
    public BankState.CommandRecord command(String commandId) {
        return commands.get(commandId);
    }

    /**
     * 组合校验：银行不变量 + 池存款与每客户明细相等 + 身份/现金/档案 + 非应计口径。
     *
     * @param bank     银行状态
     * @param accounts 现金账户
     * @param config   冻结档案
     * @return 违反项（空表示通过）
     */
    public List<String> validate(BankState bank, Map<String, CashLedger.CashAccount> accounts,
                                 BankConfig config) {
        Objects.requireNonNull(bank, "bank");
        Objects.requireNonNull(config, "config");
        Map<String, CashLedger.CashAccount> cash =
                accounts == null ? Map.of() : accounts;
        List<String> problems = new ArrayList<>();
        if (!chart.profile().fingerprint().equals(config.profile().fingerprint())) {
            problems.add(Reasons.CUSTODY_PROFILE_INVALID + ":profile");
            return problems;
        }
        if (runId == null) {
            problems.add(Reasons.NO_ACTIVE_RUN);
            return problems;
        }
        if (!runId.equals(bank.runId()) || !configFingerprint.equals(bank.configFingerprint())
                || !configFingerprint.equals(config.fingerprint())) {
            problems.add(Reasons.STATE_FINGERPRINT_MISMATCH + ":custody");
            return problems;
        }
        for (String problem : BankInvariants.check(bank, cash)) {
            problems.add("BANK:" + problem);
        }
        for (Map.Entry<String, BankingProfile.CustodyRef> entry
                : new TreeMap<>(chart.profile().custodians()).entrySet()) {
            String custodian = entry.getKey();
            BankingProfile.CustodyRef ref = entry.getValue();
            if (!chart.isBank(ref.bankId())) {
                problems.add(Reasons.CUSTODY_PROFILE_INVALID + ":bank:" + custodian);
            }
            if (!chart.isDepositAccount(ref.liabilityId())) {
                problems.add(Reasons.CUSTODY_PROFILE_INVALID + ":liability:" + custodian);
            }
            if (chart.hasParty(custodian)) {
                problems.add(Reasons.CUSTODY_PROFILE_INVALID + ":duplicate-identity:" + custodian);
            }
        }
        BigDecimal[] liability = new BigDecimal[chart.profile().custodians().size()];
        java.util.Arrays.fill(liability, Money.zero());
        List<String> custodians = new ArrayList<>(chart.profile().custodians().keySet());
        custodians.sort(String::compareTo);
        for (Map.Entry<String, BigDecimal> entry : new TreeMap<>(details).entrySet()) {
            String key = entry.getKey();
            String custodian = custodianKey(key);
            String client = clientKey(key);
            BankingProfile.CustodyRef ref = chart.profile().custodians().get(custodian);
            if (ref == null) {
                problems.add(Reasons.CUSTODY_UNKNOWN_CUSTODIAN + ":" + key);
                continue;
            }
            BankingProfile.PartyRef party = chart.profile().parties().get(client);
            if (party == null) {
                problems.add(Reasons.CUSTODY_UNKNOWN_CLIENT + ":" + key);
                continue;
            }
            CashLedger.CashAccount account = cash.get(party.cashAccountId());
            if (account == null || !account.ownerId().equals(party.ownerId())) {
                problems.add(Reasons.CUSTODY_UNKNOWN_CLIENT + ":account:" + key);
            }
            if (party.ownerId().equals(ref.ownerId())) {
                problems.add(Reasons.CUSTODY_DETAIL_INVALID + ":self:" + key);
            }
            BigDecimal amount = entry.getValue();
            if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
                problems.add(Reasons.CUSTODY_DETAIL_INVALID + ":amount:" + key);
                continue;
            }
            liability[custodians.indexOf(custodian)] =
                    liability[custodians.indexOf(custodian)].add(amount);
        }
        for (int index = 0; index < custodians.size(); index++) {
            String custodian = custodians.get(index);
            BankingProfile.CustodyRef ref = chart.profile().custodians().get(custodian);
            if (ref.creditsAccrue()) {
                problems.add(Reasons.CUSTODY_POOL_ACCRUAL + ":declared:" + custodian);
            }
            BigDecimal asset = Money.scale2(bank.depositOf(ref.bankId(), custodian));
            BigDecimal owed = Money.scale2(liability[index]);
            for (String other : chart.banks()) {
                if (other.equals(ref.bankId())) {
                    continue;
                }
                BigDecimal foreign = Money.scale2(bank.depositOf(other, custodian));
                if (foreign.signum() != 0) {
                    problems.add(Reasons.CUSTODY_BANK_MISMATCH + ":" + custodian + ":bank=" + other
                            + ":asset=" + Money.text(foreign));
                }
                if (bank.depositAccount(other, custodian) != null) {
                    problems.add(Reasons.CUSTODY_BANK_MISMATCH + ":terms:" + custodian
                            + ":bank=" + other);
                }
            }
            if (asset.compareTo(owed) != 0) {
                problems.add(Reasons.CUSTODY_POOL_MISMATCH + ":" + custodian + ":asset="
                        + Money.text(asset) + ":liability=" + Money.text(owed));
            }
            String origin = origins.get(custodian);
            if (origin == null || origin.isBlank()) {
                problems.add(Reasons.CUSTODY_ORIGIN_MISSING + ":" + custodian);
            }
            if (config.interestEnabled() && asset.signum() != 0) {
                BankState.DepositAccount terms = bank.depositAccount(ref.bankId(), custodian);
                if (terms == null || !terms.nonAccrual()) {
                    problems.add(Reasons.CUSTODY_POOL_ACCRUAL + ":terms:" + custodian);
                }
            }
        }
        return problems;
    }

    /**
     * 明细键：{@code 托管经营人|客户}。
     *
     * @param custodian 托管经营人 key
     * @param client    客户 key
     * @return 规范键
     */
    static String detailKey(String custodian, String client) {
        if (custodian == null || custodian.isBlank() || client == null || client.isBlank()) {
            throw new BankException(Reasons.CUSTODY_DETAIL_INVALID + ":empty");
        }
        if (custodian.contains(KEY_SEPARATOR) || client.contains(KEY_SEPARATOR)) {
            throw new BankException(Reasons.CUSTODY_DETAIL_INVALID + ":separator");
        }
        return custodian + KEY_SEPARATOR + client;
    }

    private static String custodianKey(String key) {
        if (key == null) {
            throw new BankException(Reasons.CUSTODY_DETAIL_INVALID + ":null");
        }
        int split = key.indexOf(KEY_SEPARATOR);
        if (split <= 0) {
            throw new BankException(Reasons.CUSTODY_DETAIL_INVALID + ":key:" + key);
        }
        return key.substring(0, split);
    }

    private static String clientKey(String key) {
        int split = key.indexOf(KEY_SEPARATOR);
        if (split <= 0 || split == key.length() - 1) {
            throw new BankException(Reasons.CUSTODY_DETAIL_INVALID + ":key:" + key);
        }
        String client = key.substring(split + 1);
        if (client.contains(KEY_SEPARATOR)) {
            throw new BankException(Reasons.CUSTODY_DETAIL_INVALID + ":key:" + key);
        }
        return client;
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(Reasons.CUSTODY_DETAIL_INVALID + ":null:" + field);
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    Reasons.CUSTODY_DETAIL_INVALID + ":precision:" + field, failure);
        }
    }

    private static BigDecimal sumOf(List<BigDecimal> values) {
        BigDecimal total = Money.zero();
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total;
    }

    /**
     * 返回替换明细后的新账（其余字段不变）；仅供同包的托管入口使用。
     *
     * @param next 新明细
     * @return 新账
     */
    CustodyBook withDetails(Map<String, BigDecimal> next) {
        Map<String, BigDecimal> copy = new TreeMap<>();
        next.forEach((key, value) -> {
            if (value != null && value.signum() > 0) {
                copy.put(key, money(value, key));
            }
        });
        return new CustodyBook(runId, configFingerprint, Map.copyOf(copy), origins, commands, chart);
    }

    /**
     * 返回追加一条回执后的新账；仅供同包的托管入口使用。
     *
     * @param commandId 命令标识
     * @param record    回执
     * @return 新账
     */
    CustodyBook withCommand(String commandId, BankState.CommandRecord record) {
        Map<String, BankState.CommandRecord> copy = new TreeMap<>(commands);
        copy.put(commandId, record);
        return new CustodyBook(runId, configFingerprint, details, origins, Map.copyOf(copy), chart);
    }

    /**
     * 明细键集合（排序后，用于差异与审计输出）。
     *
     * @return 明细键
     */
    public Set<String> detailKeys() {
        return java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(details.keySet()));
    }

    /**
     * 与另一份账的明细差异文本（审计/回执用）。
     *
     * @param other 另一份账（可为 null，表示全零基线）
     * @return 变更描述（键 → {@code 旧->新}）；无变更返回空表
     */
    public Map<String, String> deltaOf(CustodyBook other) {
        Map<String, String> delta = new LinkedHashMap<>();
        Set<String> keys = new java.util.TreeSet<>(details.keySet());
        if (other != null) {
            keys.addAll(other.details.keySet());
        }
        for (String key : keys) {
            BigDecimal before = other == null ? Money.zero() : other.detailOfKey(key);
            BigDecimal after = detailOfKey(key);
            if (before.compareTo(after) != 0) {
                delta.put(key, Money.text(before) + "->" + Money.text(after));
            }
        }
        return delta;
    }

    private BigDecimal detailOfKey(String key) {
        BigDecimal amount = details.get(key);
        return amount == null ? Money.zero() : amount;
    }

    /**
     * 规范化文本：绑定信息 + 排序明细 + 来源，用于摘要比较。
     *
     * @return 稳定文本
     */
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        sb.append("custody.run=").append(runId).append('\n');
        sb.append("custody.config=").append(configFingerprint).append('\n');
        new TreeMap<>(details).forEach((key, value) ->
                sb.append("custody.detail=").append(key).append('=').append(value.toPlainString())
                        .append('\n'));
        new TreeMap<>(origins).forEach((key, value) ->
                sb.append("custody.origin=").append(key).append('=').append(value).append('\n'));
        return sb.toString();
    }
}
