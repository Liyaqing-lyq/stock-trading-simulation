package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankRules;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.InterestAccrual;

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
 * B6 银行人物动作档案的测试夹具（只走公开 API）。
 *
 * <p>一次运行 = 两家银行（north 有准备金、south 开局零准备金）、一个公司组织（co）与其经理
 * （comgr）、两个自然人（acme、firm）、两家银行的经理（bankmgr、southmgr）与一个空托管池。
 * 准备金是 {@link CashLedger} 里的真实现金账户；存款只是银行负债。</p>
 */
public final class BankRoleFixture {

    /** 运行标识。 */
    public static final String RUN_ID = "bank-roles-run";
    /** 一天。 */
    public static final long DAY = 86_400_000L;
    /** 起始模拟时点。 */
    public static final long T0 = 1_900_000_000_000L;

    private BankRoleFixture() {
    }

    /** @return 银行人物动作档案清单（schema19 + roles/information）。 */
    public static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("banking", 1);
        modules.put("custody", 1);
        modules.put("roles", 1);
        modules.put("information", 1);
        return new RunManifest(RUN_ID, 7L, RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1,
                modules, Map.of(), RunManifest.MODEL_NONE);
    }

    /**
     * 现金开局：north 准备金 + 三个客户的现金。
     *
     * @param northReserve north 准备金
     * @return 开局清单
     */
    public static List<CashLedger.OpeningAccount> openings(String northReserve) {
        List<CashLedger.OpeningAccount> list = new ArrayList<>();
        list.add(new CashLedger.OpeningAccount("rsv-north", "north-owner", money(northReserve),
                "b6-opening-reserve"));
        list.add(new CashLedger.OpeningAccount("rsv-south", "south-owner",
                BigDecimal.ZERO.setScale(2), "b6-opening-reserve"));
        list.add(new CashLedger.OpeningAccount("acme-cash", "acme-owner", money("1000.00"),
                "b6-opening-personal"));
        list.add(new CashLedger.OpeningAccount("firm-cash", "firm-owner", money("400.00"),
                "b6-opening-personal"));
        list.add(new CashLedger.OpeningAccount("co-cash", "co-owner", money("100.00"),
                "b6-opening-company"));
        return list;
    }

    /** @return 默认开局（north 准备金 500） */
    public static List<CashLedger.OpeningAccount> openings() {
        return openings("500.00");
    }

    /** @return 完整开局登记：两家银行组织、公司组织、托管组织与五个自然人。 */
    public static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = new ArrayList<>();
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>();
        for (String[] row : new String[][]{
                {"north-org", "north-owner", "North Bank"},
                {"south-org", "south-owner", "South Bank"},
                {"co", "co-owner", "Co Company"},
                {"cst-org", "cst-owner", "Custody Operator"}}) {
            parties.add(new OpeningRegistry.Party(row[0], OpeningRegistry.PartyKind.ORGANIZATION,
                    row[2]));
            owners.add(new OpeningRegistry.PropertyOwner(row[1],
                    OpeningRegistry.OwnerKind.ORGANIZATION, row[0]));
        }
        for (String person : List.of("acme", "firm", "bankmgr", "southmgr", "comgr")) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON,
                    person));
            owners.add(new OpeningRegistry.PropertyOwner(person + "-owner",
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        return new OpeningRegistry(parties, owners, List.of(), List.of());
    }

    /** @return 银行 profile：两家银行、三个客户主体、一个托管人与 co 的风险桶。 */
    public static BankingProfile profile() {
        Map<String, BankingProfile.BankRef> banks = new LinkedHashMap<>();
        banks.put("north", new BankingProfile.BankRef("north", "north-org", "north-owner",
                "rsv-north"));
        banks.put("south", new BankingProfile.BankRef("south", "south-org", "south-owner",
                "rsv-south"));
        Map<String, BankingProfile.PartyRef> parties = new LinkedHashMap<>();
        parties.put("acme", new BankingProfile.PartyRef("acme", "acme-owner", "acme-cash",
                "dep-acme"));
        parties.put("firm", new BankingProfile.PartyRef("firm", "firm-owner", "firm-cash",
                "dep-firm"));
        parties.put("co", new BankingProfile.PartyRef("co", "co-owner", "co-cash", "dep-co"));
        Map<String, BankingProfile.CustodyRef> custodians = new LinkedHashMap<>();
        custodians.put("cst", new BankingProfile.CustodyRef("cst-org", "cst-owner", "north",
                "pool-cst", BankRules.CREDIT_NON_ACCRUAL));
        Map<String, BankingProfile.RiskBucket> buckets = new LinkedHashMap<>();
        buckets.put("co", new BankingProfile.RiskBucket("book-co", null, "book-co", "impair-co"));
        return new BankingProfile(banks, parties, custodians, buckets, Map.of(),
                List.of("book-co"), List.of("impair-co"),
                List.of("dep-acme", "dep-firm", "dep-co", "pool-cst"), null, null, null);
    }

    /** @return 冻结档案：两家银行的存款 offer（2%、365 天）与完整门槛表。 */
    public static BankConfig config() {
        Map<String, BankConfig.DepositOffer> offers = new LinkedHashMap<>();
        for (String bankKey : List.of("north", "south")) {
            offers.put(bankKey, new BankConfig.DepositOffer(bankKey, "OFF-" + bankKey,
                    InterestAccrual.rateU(new BigDecimal("0.02")), 365 * DAY,
                    BankRules.CREDIT_OFFER));
        }
        return new BankConfig.Builder(profile()).version(20).archiveId("B6-ROLES-EXP")
                .label("banking-roles-experiment-v1").coverageRule("DECLARED_UNIVERSE_V1")
                .interest(BankConfig.Interest.act365()).depositOffers(offers)
                .groupMap(Map.of("co", "G-CO"))
                .thresholds(Map.of("cet1_min", money("0.01"), "cet1_buffer", money("0.01"),
                        "t1_min", money("0.01"), "tcr_min", money("0.01"),
                        "lev_min", money("0.01"), "single_name", money("9.90"),
                        "group_name", money("9.90"), "lcr_min", money("0.01"),
                        "maturity_gap_min", money("-0.90"),
                        "impair_cover_min", money("0.01")))
                .build();
    }

    /**
     * 历史禀赋：north 自有房产 1000、acme 已有存款；south 自有房产 1000。
     *
     * @param acmeDeposit acme 的开局存款
     * @return 禀赋
     */
    public static BankOpening.DeclaredEndowment endowment(String acmeDeposit) {
        Map<String, BankOpening.BankBalance> banks = new LinkedHashMap<>();
        Map<String, BigDecimal> north = new LinkedHashMap<>();
        north.put("PREMISES", money("1000.00"));
        List<BankOpening.DepositEndowment> deposits = new ArrayList<>();
        if (money(acmeDeposit).signum() > 0) {
            north.put("dep-acme", money(acmeDeposit).negate());
            deposits.add(new BankOpening.DepositEndowment("north", "acme",
                    money(acmeDeposit), null));
        }
        banks.put("north", new BankOpening.BankBalance(north, "b6-opening-capital"));
        banks.put("south", new BankOpening.BankBalance(Map.of("PREMISES", money("1000.00")),
                "b6-opening-capital"));
        return new BankOpening.DeclaredEndowment(banks, List.of(), deposits, List.of(),
                List.of(), Map.of(), List.of(), "b6-opening-endowment");
    }

    /** @return 默认禀赋（acme 存款 300） */
    public static BankOpening.DeclaredEndowment endowment() {
        return endowment("300.00");
    }

    /** @return 银行开局申请（空池）。 */
    public static BankOpeningRequest request() {
        return BankOpeningRequest.of(config(), endowment(), Map.of(), Map.of());
    }

    /**
     * 任职开局：两家银行各有经理，公司 co 有经理；全部从 0 起无限期。
     *
     * @return 任职配置
     */
    public static RoleRun.Config roles() {
        List<RoleAccess.Appointment> appointments = new ArrayList<>();
        long end = 3650 * DAY;
        appointments.add(new RoleAccess.Appointment("a-mgr-north", "bankmgr", "north-org",
                RoleAccess.Role.COMPANY_MANAGER, 0L, end, null));
        appointments.add(new RoleAccess.Appointment("a-mgr-south", "southmgr", "south-org",
                RoleAccess.Role.COMPANY_MANAGER, 0L, end, null));
        appointments.add(new RoleAccess.Appointment("a-mgr-co", "comgr", "co",
                RoleAccess.Role.COMPANY_MANAGER, 0L, end, null));
        return new RoleRun.Config(List.of(), appointments);
    }

    /** @return 资料阅读开局：五个自然人都是登记读者。 */
    public static InformationRun.Config information() {
        return new InformationRun.Config(List.of("acme", "firm", "bankmgr", "southmgr", "comgr"));
    }

    /**
     * 默认运行。
     *
     * @param file 运行日志
     * @return 世界运行
     * @throws Exception 打开失败
     */
    public static WorldRuntime open(Path file) throws Exception {
        return new WorldRuntime(file, manifest(), openings(), registry(), roles(), information(),
                request());
    }

    /**
     * 零准备金变体（用于“同行可付/跨行不足”对照）。
     *
     * @param file 运行日志
     * @return 世界运行
     * @throws Exception 打开失败
     */
    public static WorldRuntime openZeroReserve(Path file) throws Exception {
        return new WorldRuntime(file, manifest(), openings("0.00"), registry(), roles(),
                information(), BankOpeningRequest.of(config(), endowment("100.00"), Map.of(),
                        Map.of()));
    }

    /** 一次运行的可比较摘要（跨 JVM 逐项对比）。 */
    public record Digests(String config, String bank, String accounts, String custody,
                          String audit, String loans, long position, long timeMillis) {
    }

    /**
     * 取当前摘要。
     *
     * @param runtime 运行
     * @return 摘要
     * @throws Exception 读取失败
     */
    public static Digests digests(WorldRuntime runtime) throws Exception {
        edu.cufe.auction.world.bank.BankRun bank = runtime.bankOf().orElseThrow();
        edu.cufe.auction.world.bank.BankRun.Audit audit = runtime.bankAudit().orElseThrow();
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
        StringBuilder loans = new StringBuilder();
        for (Map.Entry<String, edu.cufe.auction.world.bank.BankActions.Loan> entry
                : new TreeMap<>(runtime.bankBook().loans()).entrySet()) {
            edu.cufe.auction.world.bank.BankActions.Loan loan = entry.getValue();
            loans.append(entry.getKey()).append('/').append(loan.bankKey()).append('/')
                    .append(loan.borrowerKey()).append('/').append(loan.amount().toPlainString())
                    .append('/').append(loan.rateU()).append('/').append(loan.tenorMs())
                    .append('/').append(loan.status()).append('/').append(loan.accepted())
                    .append('/').append(loan.approvedBy()).append('/')
                    .append(loan.drawn().toPlainString()).append(';');
        }
        StringBuilder auditText = new StringBuilder();
        auditText.append("reserves=").append(audit.reserves())
                .append("|deposits=").append(audit.deposits())
                .append("|pools=").append(audit.custodyPools())
                .append("|beneficial=").append(audit.custodyBeneficial())
                .append("|backing=").append(audit.backing().toPlainString());
        return new Digests(bank.configFingerprint(), bank.digest(), sha(accounts.toString()),
                sha(custody.toString()), sha(auditText.toString()), sha(loans.toString()),
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
}
