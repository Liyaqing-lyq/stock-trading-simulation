package edu.cufe.auction.world.bank;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BankState/BankConfig 的 JSON 编解码与可信恢复。
 *
 * <p>只保存银行账簿，不保存现金；{@link CashLedger} 账户仍由 World 持有并传入。
 * 恢复时 chart 由可信 {@link BankConfig} 重建，JSON 自带的 runId/configFingerprint
 * 只用于一致性比较，不能作为信任根。自洽改写全部历史的攻击仍需 World 命令重演。</p>
 */
public final class BankPersistence {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private BankPersistence() {
    }

    /**
     * 序列化完整档案。
     *
     * @param config 档案
     * @return JSON
     */
    public static String writeConfig(BankConfig config) {
        Objects.requireNonNull(config, "config");
        try {
            return MAPPER.writeValueAsString(BankConfigSnapshot.of(config));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("CONFIG_WRITE_FAILED", failure);
        }
    }

    /**
     * 反序列化档案；是否信任该 JSON 由调用方决定。
     *
     * @param json JSON
     * @return 档案
     */
    public static BankConfig readConfig(String json) {
        Objects.requireNonNull(json, "json");
        BankConfigSnapshot snapshot;
        try {
            snapshot = MAPPER.readValue(json, BankConfigSnapshot.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("CONFIG_JSON_INVALID", failure);
        }
        try {
            return snapshot.toConfig();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("CONFIG_INVALID:" + failure.getMessage(), failure);
        }
    }

    /**
     * 序列化完整银行状态。
     *
     * @param state 状态
     * @param config 可信档案（用于校验指纹，不写入额外副本）
     * @return JSON
     */
    public static String writeState(BankState state, BankConfig config) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(config, "config");
        if (state.configFingerprint() == null
                || !state.configFingerprint().equals(config.fingerprint())) {
            throw new IllegalArgumentException("STATE_CONFIG_MISMATCH");
        }
        try {
            return MAPPER.writeValueAsString(BankStateSnapshot.of(state));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("STATE_WRITE_FAILED", failure);
        }
    }

    /**
     * 用可信档案与实际现金账户恢复状态。
     *
     * @param json JSON
     * @param trusted 可信档案
     * @param accounts 实际现金账户（现金唯一权威）
     * @param expectedRunId 期望运行标识
     * @return 恢复后的不可变状态
     */
    public static BankState readState(String json, BankConfig trusted,
                                      Map<String, CashLedger.CashAccount> accounts,
                                      String expectedRunId) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(trusted, "trusted");
        Objects.requireNonNull(accounts, "accounts");
        BankStateSnapshot snapshot;
        try {
            snapshot = MAPPER.readValue(json, BankStateSnapshot.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("STATE_JSON_INVALID", failure);
        }
        if (snapshot.runId() == null || snapshot.runId().isBlank()) {
            throw new IllegalArgumentException("STATE_RUN_MISSING");
        }
        if (expectedRunId == null || !snapshot.runId().equals(expectedRunId)) {
            throw new IllegalArgumentException("STATE_RUN_MISMATCH:" + snapshot.runId());
        }
        if (snapshot.configFingerprint() == null
                || !trusted.fingerprint().equals(snapshot.configFingerprint())) {
            throw new IllegalArgumentException("STATE_CONFIG_MISMATCH");
        }
        validateReferences(snapshot, trusted);
        BankState state;
        try {
            state = BankState.restore(snapshot.runId(), snapshot.configFingerprint(),
                    snapshot.glByBank(), snapshot.loans(), snapshot.facilities(),
                    snapshot.commitments(), snapshot.commitmentRecords(),
                    snapshot.depositAccounts(), snapshot.commands(), snapshot.origins(),
                    snapshot.providerProvisions(), snapshot.facilityAccounts(),
                    snapshot.collaterals(), snapshot.assignedClaims(), snapshot.resolutions(),
                    snapshot.purchases(), snapshot.consents(), snapshot.providerOpenings(),
                    snapshot.assetTransfers(), snapshot.backing(), trusted.chart());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("STATE_INVALID:" + failure.getMessage(), failure);
        }
        try {
            List<String> configProblems = BankArchiveValidator.validate(trusted, state, accounts);
            if (!configProblems.isEmpty()) {
                throw new IllegalArgumentException("STATE_CONFIG_INVALID:"
                        + configProblems.get(0));
            }
            List<String> problems = BankInvariants.check(state, accounts);
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("STATE_INVARIANT_INVALID:"
                        + problems.get(0));
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("STATE_INVALID:" + failure.getMessage(), failure);
        }
        BigDecimal cash = Money.zero();
        for (CashLedger.CashAccount account : accounts.values()) {
            cash = cash.add(account.cash());
        }
        if (Money.scale2(cash).compareTo(Money.scale2(state.backing())) != 0) {
            throw new IllegalArgumentException("STATE_BACKING_MISMATCH:"
                    + Money.text(cash) + "!=" + Money.text(state.backing()));
        }
        return state;
    }

    /**
     * 状态规范 JSON 的 SHA-256。
     *
     * @param state 状态
     * @param config 可信档案
     * @return 十六进制摘要
     */
    public static String digestState(BankState state, BankConfig config) {
        return digestText(writeState(state, config));
    }

    /**
     * 档案规范 JSON 的 SHA-256。
     *
     * @param config 档案
     * @return 十六进制摘要
     */
    public static String digestConfig(BankConfig config) {
        return digestText(writeConfig(config));
    }

    /**
     * 文本 SHA-256。
     *
     * @param text 文本
     * @return 十六进制摘要
     */
    public static String digestText(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte value : hash) {
                sb.append(String.format("%02x", value));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("缺少 SHA-256", failure);
        }
    }

    private static void validateReferences(BankStateSnapshot snapshot, BankConfig trusted) {
        BankChart chart = trusted.chart();
        for (Map.Entry<String, Map<String, BigDecimal>> bank : snapshot.glByBank().entrySet()) {
            if (!chart.isBank(bank.getKey())) {
                throw new IllegalArgumentException("STATE_UNKNOWN_BANK:" + bank.getKey());
            }
            for (String account : bank.getValue().keySet()) {
                if (!BankChart.RESERVE.equals(account) && !chart.glAccounts().contains(account)) {
                    throw new IllegalArgumentException("STATE_UNKNOWN_ACCOUNT:" + account);
                }
            }
        }
        for (String key : snapshot.loans().keySet()) {
            String bank = chart.bankOfLoanKey(key);
            String borrower = chart.borrowerOfLoanKey(key);
            if (bank == null || !chart.isBank(bank) || borrower == null
                    || !chart.hasBucket(borrower)) {
                throw new IllegalArgumentException("STATE_LOAN_REFERENCE:" + key);
            }
        }
        for (String key : snapshot.depositAccounts().keySet()) {
            String bank = chart.bankOfDepositTermsKey(key);
            String holder = chart.holderOfDepositTermsKey(key);
            if (bank == null || !chart.isBank(bank) || holder == null
                    || (!chart.hasParty(holder) && !chart.isCustodian(holder))) {
                throw new IllegalArgumentException("STATE_DEPOSIT_REFERENCE:" + key);
            }
        }
        for (String key : snapshot.facilityAccounts().keySet()) {
            String bank = chart.bankOfFacilityKey(key);
            if (bank == null || !chart.isBank(bank)
                    || chart.authorizationOfFacilityKey(key) == null) {
                throw new IllegalArgumentException("STATE_FACILITY_REFERENCE:" + key);
            }
        }
        for (String key : snapshot.commitmentRecords().keySet()) {
            String bank = chart.bankOfCommitmentKey(key);
            if (bank == null || !chart.isBank(bank)) {
                throw new IllegalArgumentException("STATE_COMMITMENT_REFERENCE:" + key);
            }
        }
        for (BankState.Collateral collateral : snapshot.collaterals().values()) {
            if (!chart.isBank(collateral.owner())) {
                throw new IllegalArgumentException("STATE_COLLATERAL_OWNER:"
                        + collateral.collateralId());
            }
        }
        for (BankState.AssignedClaim claim : snapshot.assignedClaims().values()) {
            if (!chart.isBank(claim.sellerBank()) || !chart.hasBucket(claim.borrower())
                    || (!chart.isBank(claim.holder()) && !chart.hasParty(claim.holder()))) {
                throw new IllegalArgumentException("STATE_CLAIM_REFERENCE:" + claim.claimId());
            }
        }
        // B4 处置许可的卖方是可信档案声明的独立处置权（既非银行也非普通主体），
        // 因此 COLLATERAL 这一种来源精确认可该身份；LOAN 许可与任何其他 authority 仍按旧规则拒绝。
        String resolutionAuthority = trusted.facilityTerms().enabled()
                ? trusted.facilityTerms().resolutionAuthority() : null;
        Set<String> collateralIds = new HashSet<>();
        for (BankState.Collateral collateral : snapshot.collaterals().values()) {
            collateralIds.add(collateral.collateralId());
        }
        for (BankState.PurchaseConsent purchase : snapshot.purchases().values()) {
            boolean sellerKnown = chart.isBank(purchase.seller())
                    || chart.hasParty(purchase.seller())
                    || (BankRules.SUBJECT_COLLATERAL.equals(purchase.subjectType())
                    && resolutionAuthority != null
                    && resolutionAuthority.equals(purchase.seller()));
            boolean buyerKnown = chart.isBank(purchase.buyer()) || chart.hasParty(purchase.buyer());
            boolean subjectKnown;
            if (BankRules.SUBJECT_LOAN.equals(purchase.subjectType())) {
                // 与 purchaseAccept 的受理口一致：贷款键必须落在可信银行下且带借款人；
                // 不要求期次仍在簿——受让后的历史贷款本来就已清空，那是合法历史。
                String bank = chart.bankOfLoanKey(purchase.subject());
                subjectKnown = bank != null && chart.isBank(bank)
                        && chart.borrowerOfLoanKey(purchase.subject()) != null;
            } else {
                // 押品许可的对象必须是本状态里真实存在的押品（含已 DISPOSED 的历史押品）。
                subjectKnown = collateralIds.contains(purchase.subject());
            }
            if (!sellerKnown || !buyerKnown || !subjectKnown) {
                throw new IllegalArgumentException("STATE_PURCHASE_REFERENCE:"
                        + purchase.purchaseId());
            }
        }
        for (BankState.DischargeConsent consent : snapshot.consents().values()) {
            if (!chart.isBank(consent.bank())
                    || !chart.glAccounts().contains(consent.account())) {
                throw new IllegalArgumentException("STATE_CONSENT_REFERENCE:"
                        + consent.consentId());
            }
        }
        for (BankState.ProviderOpening opening : snapshot.providerOpenings().values()) {
            if (chart.providerKey() != null && !chart.providerKey().equals(opening.provider())) {
                throw new IllegalArgumentException("STATE_PROVIDER_REFERENCE:"
                        + opening.provider());
            }
        }
        for (BankState.AssetTransfer transfer : snapshot.assetTransfers().values()) {
            if (!chart.isBank(transfer.fromOwner())
                    || (!chart.isBank(transfer.toOwner()) && !chart.hasParty(transfer.toOwner()))) {
                throw new IllegalArgumentException("STATE_TRANSFER_REFERENCE:"
                        + transfer.assetKey());
            }
        }
    }
}
