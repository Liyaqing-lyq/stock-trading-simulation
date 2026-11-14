package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * BankState 的 Jackson 友好快照：只含持久字段，不含运行期 chart。
 *
 * <p>所有 map 在构造时按 key 排序并防御性复制；恢复必须由可信 {@link BankConfig}
 * 重建 {@link BankChart}，不能把本快照的指纹当作信任根。</p>
 *
 * @param runId 运行标识
 * @param configFingerprint 配置指纹
 * @param glByBank 各行总账
 * @param loans 贷款
 * @param facilities 旧设施
 * @param commitments 旧承诺汇总
 * @param commitmentRecords 明细承诺
 * @param depositAccounts 存款条款
 * @param commands 命令回执
 * @param origins 来源
 * @param providerProvisions 提供者准备
 * @param facilityAccounts 设施账户
 * @param collaterals 押品
 * @param assignedClaims 受让债权
 * @param resolutions 处置
 * @param purchases 买方许可
 * @param consents 解除许可
 * @param providerOpenings 提供者开局
 * @param assetTransfers 资产移交
 * @param backing 现金背书
 */
public record BankStateSnapshot(
        String runId,
        String configFingerprint,
        Map<String, Map<String, BigDecimal>> glByBank,
        Map<String, BankState.Loan> loans,
        Map<String, BankState.Facility> facilities,
        Map<String, BigDecimal> commitments,
        Map<String, BankState.Commitment> commitmentRecords,
        Map<String, BankState.DepositAccount> depositAccounts,
        Map<String, BankState.CommandRecord> commands,
        Map<String, BankState.Origin> origins,
        Map<String, BigDecimal> providerProvisions,
        Map<String, BankState.FacilityAccount> facilityAccounts,
        Map<String, BankState.Collateral> collaterals,
        Map<String, BankState.AssignedClaim> assignedClaims,
        Map<String, BankState.Resolution> resolutions,
        Map<String, BankState.PurchaseConsent> purchases,
        Map<String, BankState.DischargeConsent> consents,
        Map<String, BankState.ProviderOpening> providerOpenings,
        Map<String, BankState.AssetTransfer> assetTransfers,
        BigDecimal backing) {

    /** 校验非空并做确定性排序复制。 */
    public BankStateSnapshot {
        Objects.requireNonNull(glByBank, "glByBank");
        Objects.requireNonNull(loans, "loans");
        Objects.requireNonNull(facilities, "facilities");
        Objects.requireNonNull(commitments, "commitments");
        Objects.requireNonNull(commitmentRecords, "commitmentRecords");
        Objects.requireNonNull(depositAccounts, "depositAccounts");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(origins, "origins");
        Objects.requireNonNull(providerProvisions, "providerProvisions");
        Objects.requireNonNull(facilityAccounts, "facilityAccounts");
        Objects.requireNonNull(collaterals, "collaterals");
        Objects.requireNonNull(assignedClaims, "assignedClaims");
        Objects.requireNonNull(resolutions, "resolutions");
        Objects.requireNonNull(purchases, "purchases");
        Objects.requireNonNull(consents, "consents");
        Objects.requireNonNull(providerOpenings, "providerOpenings");
        Objects.requireNonNull(assetTransfers, "assetTransfers");
        Objects.requireNonNull(backing, "backing");
        Map<String, Map<String, BigDecimal>> gl = new TreeMap<>();
        glByBank.forEach((bank, book) -> gl.put(bank,
                Collections.unmodifiableMap(new TreeMap<>(book))));
        glByBank = Collections.unmodifiableMap(gl);
        loans = sorted(loans);
        facilities = sorted(facilities);
        commitments = sorted(commitments);
        commitmentRecords = sorted(commitmentRecords);
        depositAccounts = sorted(depositAccounts);
        commands = sorted(commands);
        origins = sorted(origins);
        providerProvisions = sorted(providerProvisions);
        facilityAccounts = sorted(facilityAccounts);
        collaterals = sorted(collaterals);
        assignedClaims = sorted(assignedClaims);
        resolutions = sorted(resolutions);
        purchases = sorted(purchases);
        consents = sorted(consents);
        providerOpenings = sorted(providerOpenings);
        assetTransfers = sorted(assetTransfers);
    }

    /**
     * 从不可变状态生成快照。
     *
     * @param state 状态
     * @return 快照
     */
    public static BankStateSnapshot of(BankState state) {
        Objects.requireNonNull(state, "state");
        return new BankStateSnapshot(state.runId(), state.configFingerprint(),
                state.glByBank(), state.loans(), state.facilities(), state.commitments(),
                state.commitmentRecords(), state.depositAccounts(), state.commands(),
                state.origins(), state.providerProvisions(), state.facilityAccounts(),
                state.collaterals(), state.assignedClaims(), state.resolutions(),
                state.purchases(), state.consents(), state.providerOpenings(),
                state.assetTransfers(), state.backing());
    }

    private static <K, V> Map<K, V> sorted(Map<K, V> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }
}
