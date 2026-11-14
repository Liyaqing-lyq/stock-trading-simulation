package edu.cufe.auction.world.bank;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 世界档案里唯一的银行三态：冻结档案、银行账簿与托管明细账，外加它们的运行绑定。
 *
 * <p>本类型只保存**编码后的规范 JSON**（档案、账簿、托管各一份），因此 Jackson 不会去反射
 * {@link BankState} 的私有字段：读写都走 {@link BankPersistence} 与 {@link CustodyBook}
 * 自己的恢复入口。解码需要当前现金账户（现金唯一权威），所以解码与校验都要显式传入账户表。</p>
 *
 * <p>它不是第二本现金账：准备金仍只来自现金账户，存款仍是银行负债；本类型只是把三态放在
 * WorldState 里随同一批次提交与恢复。</p>
 *
 * @param runId             运行标识
 * @param configFingerprint 冻结档案指纹
 * @param profileFingerprint 档案内银行 profile 指纹
 * @param configJson        冻结档案规范 JSON
 * @param stateJson         银行账簿规范 JSON
 * @param custodyJson       托管明细账规范 JSON
 */
public record BankRun(String runId, String configFingerprint, String profileFingerprint,
                      String configJson, String stateJson, String custodyJson) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** 校验必要字段非空。 */
    public BankRun {
        Objects.requireNonNull(runId, "运行标识");
        Objects.requireNonNull(configFingerprint, "档案指纹");
        Objects.requireNonNull(profileFingerprint, "银行 profile 指纹");
        Objects.requireNonNull(configJson, "档案 JSON");
        Objects.requireNonNull(stateJson, "账簿 JSON");
        Objects.requireNonNull(custodyJson, "托管 JSON");
        if (runId.isBlank() || configFingerprint.isBlank() || profileFingerprint.isBlank()) {
            throw new IllegalArgumentException("银行三态的绑定标识不能为空白");
        }
    }

    /**
     * 从已接受的开局结果编码三态。
     *
     * @param config 冻结档案
     * @param state  银行账簿
     * @param book   托管明细账
     * @return 世界档案里的银行三态
     */
    public static BankRun of(BankConfig config, BankState state, CustodyBook book) {
        Objects.requireNonNull(config, "档案");
        Objects.requireNonNull(state, "账簿");
        Objects.requireNonNull(book, "托管明细账");
        if (!config.fingerprint().equals(state.configFingerprint())) {
            throw new IllegalArgumentException(Reasons.STATE_FINGERPRINT_MISMATCH + ":config");
        }
        if (!state.runId().equals(book.runId())) {
            throw new IllegalArgumentException(Reasons.STATE_FINGERPRINT_MISMATCH + ":run");
        }
        if (!book.configFingerprint().equals(config.fingerprint())) {
            throw new IllegalArgumentException(Reasons.STATE_FINGERPRINT_MISMATCH + ":custody-config");
        }
        return new BankRun(state.runId(), config.fingerprint(),
                config.profile().fingerprint(), BankPersistence.writeConfig(config),
                BankPersistence.writeState(state, config), writeCustody(book));
    }

    /**
     * 解码并校验三态：运行/档案/profile 绑定 + 现金后备 + 银行不变量 + 托管组合校验。
     *
     * @param accounts 当前现金账户（唯一现金权威）
     * @return 解码后的三态
     * @throws IllegalArgumentException 任一绑定或经济口径不成立
     */
    public Decoded decode(Map<String, CashLedger.CashAccount> accounts) {
        Objects.requireNonNull(accounts, "现金账户");
        BankConfig config = BankPersistence.readConfig(configJson);
        if (!config.fingerprint().equals(configFingerprint)) {
            throw new IllegalArgumentException(Reasons.STATE_FINGERPRINT_MISMATCH + ":config-json");
        }
        if (!config.profile().fingerprint().equals(profileFingerprint)) {
            throw new IllegalArgumentException(Reasons.STATE_FINGERPRINT_MISMATCH + ":profile");
        }
        BankState state = BankPersistence.readState(stateJson, config, accounts, runId);
        CustodyBook book = readCustody(config, state, accounts);
        return new Decoded(config, state, book);
    }

    /**
     * 校验三态；不返回解码结果，供提交/恢复时的组合校验使用。
     *
     * @param accounts 当前现金账户
     */
    public void validate(Map<String, CashLedger.CashAccount> accounts) {
        decode(accounts);
    }

    /**
     * 规范文本：运行绑定 + 三份规范 JSON，用于“该批次是否改过银行”的比较与审计摘要。
     *
     * @return 规范文本
     */
    @JsonIgnore
    public String canonical() {
        return "bank.run=" + runId + "\nbank.config=" + configFingerprint
                + "\nbank.profile=" + profileFingerprint + "\nbank.state=" + stateJson
                + "\nbank.custody=" + custodyJson;
    }

    /**
     * 三态摘要（SHA-256 十六进制）。
     *
     * @return 摘要
     */
    @JsonIgnore
    public String digest() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte value : hash) {
                sb.append(String.format("%02x", value));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 运行环境缺少 SHA-256", impossible);
        }
    }

    /**
     * 经济内容是否与另一份三态完全相同（不含世界侧的持久位置）。
     *
     * @param other 另一份三态
     * @return 相同返回 true
     */
    public boolean sameAs(BankRun other) {
        return other != null && canonical().equals(other.canonical());
    }

    /**
     * 解码后的三态。
     *
     * @param config 冻结档案
     * @param state  银行账簿
     * @param book   托管明细账
     */
    public record Decoded(BankConfig config, BankState state, CustodyBook book) {
    }

    /** 托管明细账的规范 JSON：只含运行绑定、明细、来源与命令回执。 */
    private record CustodySnapshot(String runId, String configFingerprint,
                                   Map<String, BigDecimal> details, Map<String, String> origins,
                                   Map<String, BankState.CommandRecord> commands) {
    }

    private static String writeCustody(CustodyBook book) {
        CustodySnapshot snapshot = new CustodySnapshot(book.runId(), book.configFingerprint(),
                new TreeMap<>(book.details()), new TreeMap<>(book.origins()),
                new TreeMap<>(book.commands()));
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("CUSTODY_WRITE_FAILED", failure);
        }
    }

    private CustodyBook readCustody(BankConfig config, BankState state,
                                   Map<String, CashLedger.CashAccount> accounts) {
        CustodySnapshot snapshot;
        try {
            snapshot = MAPPER.readValue(custodyJson, CustodySnapshot.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("CUSTODY_JSON_INVALID", failure);
        }
        if (snapshot.runId() == null) {
            throw new IllegalArgumentException(Reasons.NO_ACTIVE_RUN);
        }
        return CustodyBook.restoreChecked(snapshot.runId(), state, accounts,
                snapshot.details() == null ? Map.of() : snapshot.details(),
                snapshot.origins() == null ? Map.of() : snapshot.origins(),
                snapshot.commands() == null ? Map.of() : snapshot.commands(), config);
    }

    /**
     * 供只读审计使用的银行摘要视图。
     *
     * @param accounts 当前现金账户
     * @return 摘要视图
     */
    public Audit audit(Map<String, CashLedger.CashAccount> accounts) {
        Decoded decoded = decode(accounts);
        Map<String, BigDecimal> reserves = new TreeMap<>();
        Map<String, BigDecimal> deposits = new TreeMap<>();
        for (String bankKey : decoded.config().chart().banks()) {
            String reserveAccount = decoded.config().chart().reserveAccountOf(bankKey);
            reserves.put(bankKey, accounts.get(reserveAccount) == null ? Money.zero()
                    : accounts.get(reserveAccount).cash());
            deposits.put(bankKey, sumDeposits(decoded, bankKey));
        }
        Map<String, BigDecimal> pools = new TreeMap<>();
        Map<String, BigDecimal> beneficial = new TreeMap<>();
        for (String custodian : decoded.config().profile().custodians().keySet()) {
            pools.put(custodian, decoded.book().poolAssetOf(decoded.state(), custodian));
            beneficial.put(custodian, sumBeneficial(decoded.book(), custodian));
        }
        return new Audit(runId, configFingerprint, digest(), reserves, deposits, pools,
                beneficial, decoded.state().backing());
    }

    /**
     * 只读审计视图：每行都属于同一批次快照，不是可改余额。
     *
     * @param runId             运行标识
     * @param configFingerprint 档案指纹
     * @param digest            三态摘要
     * @param reserves          银行 key → 准备金现金
     * @param deposits          银行 key → 该行存款负债合计
     * @param custodyPools      托管经营人 → 池存款资产
     * @param custodyBeneficial 托管经营人 → 客户明细合计
     * @param backing           结算背书
     */
    public record Audit(String runId, String configFingerprint, String digest,
                        Map<String, BigDecimal> reserves, Map<String, BigDecimal> deposits,
                        Map<String, BigDecimal> custodyPools,
                        Map<String, BigDecimal> custodyBeneficial, BigDecimal backing) {
    }

    private static BigDecimal sumDeposits(Decoded decoded, String bankKey) {
        BigDecimal total = Money.zero();
        BankChart chart = decoded.config().chart();
        for (String account : chart.liabilities()) {
            if (chart.isDepositAccount(account)) {
                total = total.add(decoded.state().gl(bankKey, account).negate());
            }
        }
        return Money.scale2(total);
    }

    private static BigDecimal sumBeneficial(CustodyBook book, String custodian) {
        BigDecimal total = Money.zero();
        for (Map.Entry<String, BigDecimal> entry : new TreeMap<>(book.details()).entrySet()) {
            if (entry.getKey().startsWith(custodian + CustodyBook.KEY_SEPARATOR)) {
                total = total.add(entry.getValue());
            }
        }
        return Money.scale2(total);
    }

    /**
     * 单个主体的资金投影（God 审计与本人视图共用同一派生，不做第二本账）。
     *
     * @param accounts 当前现金账户
     * @param ownerId  真实财产主体
     * @return 该主体的现金/存款/托管受益权
     */
    public Projection project(Map<String, CashLedger.CashAccount> accounts, String ownerId) {
        Decoded decoded = decode(accounts);
        BigDecimal cash = Money.zero();
        for (CashLedger.CashAccount account : accounts.values()) {
            if (account.ownerId().equals(ownerId)) {
                cash = cash.add(account.cash());
            }
        }
        BigDecimal deposits = Money.zero();
        BigDecimal beneficial = Money.zero();
        for (Map.Entry<String, BankingProfile.PartyRef> entry
                : new TreeMap<>(decoded.config().profile().parties()).entrySet()) {
            if (!entry.getValue().ownerId().equals(ownerId)) {
                continue;
            }
            if (entry.getValue().depositGl() != null) {
                for (String bankKey : decoded.config().chart().banks()) {
                    deposits = deposits.add(decoded.state().depositOf(bankKey, entry.getKey()));
                }
            }
            for (String custodian : decoded.config().profile().custodians().keySet()) {
                beneficial = beneficial.add(decoded.book().detailOf(custodian, entry.getKey()));
            }
        }
        return new Projection(ownerId, Money.scale2(cash), Money.scale2(deposits),
                Money.scale2(beneficial));
    }

    /**
     * 单个主体的资金投影（只读派生）。
     *
     * @param ownerId    真实财产主体
     * @param cash       真实现金合计
     * @param deposits   全部银行存款合计
     * @param beneficial 客户托管受益权合计
     */
    public record Projection(String ownerId, BigDecimal cash, BigDecimal deposits,
                             BigDecimal beneficial) {
    }

    /**
     * 托管明细合计（供审计与本人视图核对，不做账）。
     *
     * @param custodian 托管经营人 key
     * @return 客户明细合计
     */
    public BigDecimal custodyDetailTotal(String custodian) {
        BigDecimal total = Money.zero();
        for (Map.Entry<String, BigDecimal> entry : new TreeMap<>(custodyDetails()).entrySet()) {
            if (entry.getKey().startsWith(custodian + CustodyBook.KEY_SEPARATOR)) {
                total = total.add(entry.getValue());
            }
        }
        return Money.scale2(total);
    }

    /**
     * 托管明细原始映射（只读）。
     *
     * @return 明细键 → 金额
     */
    @JsonIgnore
    public Map<String, BigDecimal> custodyDetails() {
        try {
            CustodySnapshot snapshot = MAPPER.readValue(custodyJson, CustodySnapshot.class);
            return snapshot.details() == null ? Map.of() : Map.copyOf(snapshot.details());
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("CUSTODY_JSON_INVALID", failure);
        }
    }

    /**
     * 命令回执（只读；用于审计某笔命令是否在本档案里落过账）。
     *
     * @param commandId 命令标识
     * @return 回执或 null
     */
    @JsonIgnore
    public BankState.CommandRecord command(String commandId) {
        try {
            CustodySnapshot snapshot = MAPPER.readValue(custodyJson, CustodySnapshot.class);
            Map<String, BankState.CommandRecord> custodyCommands =
                    snapshot.commands() == null ? Map.of() : snapshot.commands();
            if (custodyCommands.containsKey(commandId)) {
                return custodyCommands.get(commandId);
            }
            BankStateSnapshot stateSnapshot = MAPPER.readValue(stateJson,
                    BankStateSnapshot.class);
            return stateSnapshot.commands().get(commandId);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("BANK_JSON_INVALID", failure);
        }
    }

    /**
     * 只读投影用：本档案声明的银行 key。
     *
     * @return 银行 key 列表
     */
    @JsonIgnore
    public List<String> banks() {
        return BankPersistence.readConfig(configJson).chart().banks();
    }
}
