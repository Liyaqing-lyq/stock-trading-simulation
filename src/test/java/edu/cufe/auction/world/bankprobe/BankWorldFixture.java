package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankRules;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.BankingProfile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B5 银行 World 的测试夹具与摘要工具（只走公开 API）。
 *
 * <p>一次运行 = 一家银行（north，结算行）、登记组织 + 两个自然人主体、一个托管经营人（空池开局）。
 * 准备金是 {@link CashLedger} 里的真实现金账户；开局存款是银行负债的历史禀赋。</p>
 */
public final class BankWorldFixture {

    /** 运行标识（同一日志只能对应同一运行）。 */
    public static final String RUN_ID = "bank-b5-run";
    /** 银行 key。 */
    public static final String BANK = "north";
    /** 托管经营人 key。 */
    public static final String CUSTODIAN = "cst";

    private BankWorldFixture() {
    }

    /** @return 银行档案的规则版本清单（cash/scheduler/registry/banking/custody）。 */
    public static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("banking", 1);
        modules.put("custody", 1);
        return new RunManifest(RUN_ID, 7L, RunManifest.RULES_BANKING_WORLD_EXPERIMENT_V1,
                modules, Map.of(), RunManifest.MODEL_NONE);
    }

    /** @return 不带银行的旧 schema18 风格清单（用于“另一个群组不含 bank”的对照）。 */
    public static RunManifest legacyManifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        return new RunManifest("legacy-run", 7L,
                RunManifest.RULES_CASH_REGISTRY_WORLD_V1, modules, Map.of(),
                RunManifest.MODEL_NONE);
    }

    /**
     * 现金开局清单：准备金 500 + 客户 1000 + 公司 400（全部带历史来源）。
     *
     * @param reserveOwner 准备金账户所有者（负例用别人的 owner）
     * @return 开局清单
     */
    public static List<CashLedger.OpeningAccount> openings(String reserveOwner) {
        List<CashLedger.OpeningAccount> list = new ArrayList<>();
        list.add(new CashLedger.OpeningAccount("rsv-north", reserveOwner, money("500.00"),
                "b5-opening-reserve"));
        list.add(new CashLedger.OpeningAccount("acme-cash", "acme-owner", money("1000.00"),
                "b5-opening-customer"));
        list.add(new CashLedger.OpeningAccount("firm-cash", "firm-owner", money("400.00"),
                "b5-opening-firm"));
        return list;
    }

    /** @return 默认准备金归属的开局清单 */
    public static List<CashLedger.OpeningAccount> openings() {
        return openings("north-owner");
    }

    /**
     * 完整开局登记：银行与人名主体 + 托管经营人组织。
     *
     * @param bankIsOrganization 负例可把银行 party 登记成 PERSON
     * @param reserveOwner       准备金账户 owner（必须与银行 owner 一致）
     * @return 登记
     */
    public static OpeningRegistry registry(boolean bankIsOrganization, String reserveOwner) {
        List<OpeningRegistry.Party> parties = new ArrayList<>();
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>();
        parties.add(new OpeningRegistry.Party("north-org",
                bankIsOrganization ? OpeningRegistry.PartyKind.ORGANIZATION
                        : OpeningRegistry.PartyKind.PERSON, "North Bank"));
        owners.add(new OpeningRegistry.PropertyOwner(reserveOwner,
                bankIsOrganization ? OpeningRegistry.OwnerKind.ORGANIZATION
                        : OpeningRegistry.OwnerKind.PERSON, "north-org"));
        parties.add(new OpeningRegistry.Party("cst-org", OpeningRegistry.PartyKind.ORGANIZATION,
                "Custody Operator"));
        owners.add(new OpeningRegistry.PropertyOwner("cst-owner",
                OpeningRegistry.OwnerKind.ORGANIZATION, "cst-org"));
        for (String person : List.of("acme", "firm")) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON,
                    person));
            owners.add(new OpeningRegistry.PropertyOwner(person + "-owner",
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        return new OpeningRegistry(parties, owners, List.of(), List.of());
    }

    /** @return 默认登记 */
    public static OpeningRegistry registry() {
        return registry(true, "north-owner");
    }

    /**
     * 冻结档案：单行、无息（明确非应计由档案口径声明），profiles 含客户/公司/托管。
     *
     * @param withCustodian 是否声明托管经营人
     * @return 档案
     */
    public static BankConfig config(boolean withCustodian) {
        return new BankConfig.Builder(profile(withCustodian)).version(19)
                .archiveId("B5-WORLD-EXP").label("banking-world-experiment-v1")
                .coverageRule("DECLARED_UNIVERSE_V1").build();
    }

    /** @return 默认（含托管）档案 */
    public static BankConfig config() {
        return config(true);
    }

    /**
     * 银行 profile：一行、客户/公司/托管三个主体。
     *
     * @param withCustodian 是否含托管经营人
     * @return profile
     */
    public static BankingProfile profile(boolean withCustodian) {
        Map<String, BankingProfile.BankRef> banks = new LinkedHashMap<>();
        banks.put(BANK, new BankingProfile.BankRef(BANK, "north-org", "north-owner",
                "rsv-north"));
        Map<String, BankingProfile.PartyRef> parties = new LinkedHashMap<>();
        parties.put("acme", new BankingProfile.PartyRef("acme", "acme-owner", "acme-cash",
                "dep-acme"));
        parties.put("firm", new BankingProfile.PartyRef("firm", "firm-owner", "firm-cash",
                "dep-firm"));
        Map<String, BankingProfile.CustodyRef> custodians = new LinkedHashMap<>();
        List<String> deposits = new ArrayList<>(List.of("dep-acme", "dep-firm"));
        if (withCustodian) {
            custodians.put(CUSTODIAN, new BankingProfile.CustodyRef("cst-org", "cst-owner", BANK,
                    "pool-cst", BankRules.CREDIT_NON_ACCRUAL));
            deposits.add("pool-cst");
        }
        return new BankingProfile(banks, parties, custodians, Map.of(), Map.of(),
                List.of("book-none"), List.of("impair-none"), List.copyOf(deposits),
                null, null, null);
    }

    /**
     * 历史非现金禀赋：已有客户存款 300、固定资产 1000（准备金来自现金账户）。
     *
     * @param deposit 已有客户存款
     * @param pool    已有托管池存款（0 表示空池）
     * @return 禀赋
     */
    public static BankOpening.DeclaredEndowment endowment(String deposit, String pool) {
        Map<String, BigDecimal> gl = new LinkedHashMap<>();
        gl.put("PREMISES", money("1000.00"));
        gl.put("dep-acme", money(deposit).negate());
        if (money(pool).signum() > 0) {
            gl.put("pool-cst", money(pool).negate());
        }
        Map<String, BankOpening.BankBalance> banks = new LinkedHashMap<>();
        banks.put(BANK, new BankOpening.BankBalance(gl, "b5-opening-capital"));
        List<BankOpening.DepositEndowment> deposits = new ArrayList<>();
        deposits.add(new BankOpening.DepositEndowment(BANK, "acme", money(deposit), null));
        if (money(pool).signum() > 0) {
            deposits.add(new BankOpening.DepositEndowment(BANK, CUSTODIAN, money(pool), null));
        }
        return new BankOpening.DeclaredEndowment(banks, List.of(), deposits, List.of(),
                List.of(), Map.of(), List.of(), "b5-opening-endowment");
    }

    /** @return 默认禀赋（客户存款 300、空池） */
    public static BankOpening.DeclaredEndowment endowment() {
        return endowment("300.00", "0.00");
    }

    /**
     * 开局申请：默认空池；带池时用申报明细对账。
     *
     * @param config     冻结档案
     * @param endowment  历史禀赋
     * @param poolDeclared 托管池申报明细（空表 = 空池开局）
     * @return 开局申请
     */
    public static BankOpeningRequest request(BankConfig config,
                                             BankOpening.DeclaredEndowment endowment,
                                             Map<String, Map<String, BigDecimal>> poolDeclared,
                                             Map<String, String> poolOrigins) {
        return BankOpeningRequest.of(config, endowment, poolDeclared, poolOrigins);
    }

    /** @return 默认开局申请（空池） */
    public static BankOpeningRequest request() {
        return request(config(), endowment(), Map.of(), Map.of());
    }

    /**
     * 真实开局。
     *
     * @param file 运行日志
     * @return 世界运行（已打开并校验）
     * @throws Exception 打开失败
     */
    public static WorldRuntime open(Path file) throws Exception {
        return new WorldRuntime(file, manifest(), openings(), registry(), request());
    }

    /** 一次运行的可比较摘要（跨 JVM 逐项对比）。 */
    public record Digests(String config, String bank, String accounts, String custody,
                          String audit, long position, long timeMillis) {
    }

    /**
     * 取当前摘要。
     *
     * @param runtime 运行
     * @return 摘要
     * @throws Exception 审计失败
     */
    public static Digests digests(WorldRuntime runtime) throws Exception {
        BankRun bank = runtime.bankOf().orElseThrow(() -> new IllegalStateException("缺少银行"));
        BankRun.Audit audit = runtime.bankAudit().orElseThrow();
        StringBuilder accounts = new StringBuilder();
        for (Map.Entry<String, CashLedger.CashAccount> entry
                : new TreeMap<>(runtime.snapshot().accounts()).entrySet()) {
            accounts.append(entry.getKey()).append(':').append(entry.getValue().ownerId())
                    .append('=').append(entry.getValue().cash().toPlainString()).append(';');
        }
        StringBuilder custody = new StringBuilder();
        for (Map.Entry<String, BigDecimal> entry : new TreeMap<>(bank.custodyDetails()).entrySet()) {
            custody.append(entry.getKey()).append('=').append(entry.getValue().toPlainString())
                    .append(';');
        }
        StringBuilder auditText = new StringBuilder();
        auditText.append("reserves=").append(audit.reserves())
                .append("|deposits=").append(audit.deposits())
                .append("|pools=").append(audit.custodyPools())
                .append("|beneficial=").append(audit.custodyBeneficial())
                .append("|backing=").append(audit.backing().toPlainString());
        return new Digests(bank.configFingerprint(), bank.digest(), sha(accounts.toString()),
                sha(custody.toString()), sha(auditText.toString()),
                runtime.snapshot().position(), runtime.snapshot().timeMillis());
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * 金额。
     *
     * @param value 文本
     * @return 两位小数
     */
    public static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * 银行账簿（解码后）供负例断言使用。
     *
     * @param runtime 运行
     * @return 账簿
     * @throws Exception 解码失败
     */
    public static BankState stateOf(WorldRuntime runtime) throws Exception {
        return runtime.bankOf().orElseThrow().decode(runtime.snapshot().accounts()).state();
    }
}
