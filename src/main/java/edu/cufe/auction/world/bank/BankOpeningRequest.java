package edu.cufe.auction.world.bank;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 世界开局的银行申请：冻结档案 + 历史资产禀赋 + 可选托管历史明细。
 *
 * <p>它是开局第一帧里被冻结的那份声明，恢复时用它**重新执行**已接受的
 * {@link BankOpening#openDeclared} 与 {@link CustodyBook} 开局，再和第一份快照比对；
 * 因此换一套档案、换一份禀赋、改一个来源都会在恢复期被拒绝，而不是只比 JSON 结构。</p>
 *
 * <p>开局只做“把真实登记与真实现金账户绑起来”：准备金仍是 {@code accounts} 里的现金账户，
 * 存款只以银行负债存在，托管受益权只以明细存在。任何没有来源的余额都会在
 * {@link BankOpening} / {@link CustodyBook} 里被拒绝，本类不发放资金。</p>
 *
 * @param configJson       冻结档案规范 JSON
 * @param endowmentJson    历史非现金禀赋规范 JSON
 * @param custodyDeclared  托管历史明细：经营人 → 客户 → 金额（空表表示空池开局）
 * @param custodyOrigins   托管经营人 → 历史来源说明（空池开局自动写入明确来源）
 */
public record BankOpeningRequest(String configJson, String endowmentJson,
                                 Map<String, Map<String, BigDecimal>> custodyDeclared,
                                 Map<String, String> custodyOrigins) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** 校验非空并冻结集合。 */
    public BankOpeningRequest {
        Objects.requireNonNull(configJson, "档案 JSON");
        Objects.requireNonNull(endowmentJson, "禀赋 JSON");
        Map<String, Map<String, BigDecimal>> declared = new TreeMap<>();
        if (custodyDeclared != null) {
            custodyDeclared.forEach((custodian, details) ->
                    declared.put(custodian, Map.copyOf(new TreeMap<>(details))));
        }
        custodyDeclared = Map.copyOf(declared);
        custodyOrigins = custodyOrigins == null ? Map.of()
                : Map.copyOf(new TreeMap<>(custodyOrigins));
        if (!custodyDeclared.isEmpty() && custodyOrigins.isEmpty()) {
            throw new IllegalArgumentException(Reasons.CUSTODY_PROFILE_INVALID + ":origins");
        }
    }

    /**
     * 由冻结档案与历史禀赋构造开局申请。
     *
     * @param config     冻结档案
     * @param endowment  历史非现金禀赋（可为空禀赋）
     * @param declared   托管历史明细；空表表示空池
     * @param origins    托管来源说明；空池可传空表
     * @return 开局申请
     */
    public static BankOpeningRequest of(BankConfig config,
                                        BankOpening.DeclaredEndowment endowment,
                                        Map<String, Map<String, BigDecimal>> declared,
                                        Map<String, String> origins) {
        Objects.requireNonNull(config, "档案");
        Objects.requireNonNull(endowment, "禀赋");
        try {
            return new BankOpeningRequest(BankPersistence.writeConfig(config),
                    MAPPER.writeValueAsString(endowment),
                    declared == null ? Map.of() : declared,
                    origins == null ? Map.of() : origins);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("ENDOWMENT_WRITE_FAILED", failure);
        }
    }

    /**
     * 从开局帧里的规范 JSON 恢复申请。
     *
     * @param json 规范 JSON
     * @return 开局申请
     */
    public static BankOpeningRequest fromJson(String json) {
        Objects.requireNonNull(json, "开局申请 JSON");
        try {
            BankOpeningRequest request = MAPPER.readValue(json, BankOpeningRequest.class);
            // 重新走一遍校验（记录反序列化不会调用紧凑构造器之外的口径检查）
            return new BankOpeningRequest(request.configJson(), request.endowmentJson(),
                    request.custodyDeclared(), request.custodyOrigins());
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("BANK_OPENING_JSON_INVALID", failure);
        }
    }

    /**
     * 规范 JSON：开局第一帧冻结的就是这份文本。
     *
     * @return 规范 JSON
     */
    @JsonIgnore
    public String canonical() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("BANK_OPENING_WRITE_FAILED", failure);
        }
    }

    /**
     * 冻结档案（每次从 JSON 重新构造，避免共享可变实例）。
     *
     * @return 档案
     */
    @JsonIgnore
    public BankConfig config() {
        return BankPersistence.readConfig(configJson);
    }

    /**
     * 历史非现金禀赋。
     *
     * @return 禀赋
     */
    @JsonIgnore
    public BankOpening.DeclaredEndowment endowment() {
        try {
            return MAPPER.readValue(endowmentJson, BankOpening.DeclaredEndowment.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("ENDOWMENT_JSON_INVALID", failure);
        }
    }

    /**
     * 真实开局：调用已接受的 {@link BankOpening#openDeclared} 与托管开局，返回世界侧唯一三态。
     *
     * @param registry 完整开局登记
     * @param accounts 现金账户（唯一现金权威；准备金必须已在此表）
     * @param runId    运行标识
     * @return 银行三态
     * @throws IllegalArgumentException 登记/账户/档案/禀赋/托管不成立
     */
    public BankRun open(OpeningRegistry registry, Map<String, CashLedger.CashAccount> accounts,
                        String runId) {
        Objects.requireNonNull(registry, "开局登记");
        Objects.requireNonNull(accounts, "现金账户");
        BankConfig config = config();
        if (!config.profile().fingerprint().equals(profileFingerprint())) {
            throw new IllegalArgumentException(Reasons.STATE_FINGERPRINT_MISMATCH + ":profile");
        }
        BankOpening.Result result = BankOpening.openDeclared(registry, accounts,
                config.profile(), config, endowment(), runId);
        if (!result.accounts().equals(accounts)) {
            // 开局不得改写现金：准备金只从已有账户同步，不能凭空生钱
            throw new IllegalArgumentException("BANK_OPENING_CASH_CHANGED");
        }
        CustodyBook book = custodyDeclared.isEmpty()
                ? CustodyBook.openEmpty(result.state(), accounts, config)
                : CustodyBook.declare(result.state(), accounts, config, custodyDeclared,
                custodyOrigins);
        return BankRun.of(config, result.state(), book);
    }

    /**
     * 档案内银行 profile 指纹（用于开局帧与快照比较）。
     *
     * @return profile 指纹
     */
    @JsonIgnore
    public String profileFingerprint() {
        return config().profile().fingerprint();
    }
}
