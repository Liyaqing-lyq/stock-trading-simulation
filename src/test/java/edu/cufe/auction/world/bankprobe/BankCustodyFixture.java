package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.model.Side;
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
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.CustodyBook;
import edu.cufe.auction.world.bank.InterestAccrual;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B7b 托管成交档案的测试夹具（只走公开 API）。
 *
 * <p>两家银行（north 有准备金、south 可设零准备金）与三个托管经营人：</p>
 * <ul>
 *   <li>{@code cst}：north 结算，客户 acme（600）、firm（400），池存款 1000；</li>
 *   <li>{@code cst3}：north 结算，客户 dave（300），池存款 300（用于“跨池同行”）；</li>
 *   <li>{@code cst2}：south 结算，客户 co（500），池存款 500（用于“跨池跨行”）。</li>
 * </ul>
 *
 * <p>每个池的存款资产在开局就已存在（真实禀赋），客户明细由
 * {@link CustodyBook#declare} 按“每池资产 == 申报合计”登记，因此开局不发放任何新钱。
 * 证券只有一只 {@code B7B}，持仓记在客户自己的现金账户名下（唯一 {@code SecuritiesLedger}）。</p>
 */
public final class BankCustodyFixture {

    /** 运行标识。 */
    public static final String RUN_ID = "bank-custody-run";
    /** 一天。 */
    public static final long DAY = 86_400_000L;
    /** 起始模拟时点。 */
    public static final long T0 = 1_900_000_000_000L;
    /** 唯一证券代码。 */
    public static final String SYMBOL = "B7B";
    /** 发行组织（公司 party key）。 */
    public static final String ISSUER = "co";

    /** 池 cst 的结算银行。 */
    public static final String CST = "cst";
    /** 池 cst3 的结算银行（north）。 */
    public static final String CST3 = "cst3";
    /** 池 cst2 的结算银行（south）。 */
    public static final String CST2 = "cst2";

    private BankCustodyFixture() {
    }

    /** @return B7b 档案清单（schema19 + funding + custody-fill）。 */
    public static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("roles", 1);
        modules.put("information", 1);
        modules.put("banking", 1);
        modules.put("custody", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        modules.put("funding", 1);
        modules.put("custody-fill", 1);
        return new RunManifest(RUN_ID, 11L, RunManifest.RULES_BANKING_CUSTODY_EXPERIMENT_V1,
                modules, Map.of(), RunManifest.MODEL_NONE);
    }

    /**
     * 现金开局。
     *
     * @param northReserve north 准备金
     * @return 开局清单
     */
    public static List<CashLedger.OpeningAccount> openings(String northReserve) {
        List<CashLedger.OpeningAccount> list = new ArrayList<>();
        list.add(new CashLedger.OpeningAccount("rsv-north", "north-owner", money(northReserve),
                "b7b-opening-reserve"));
        list.add(new CashLedger.OpeningAccount("rsv-south", "south-owner",
                BigDecimal.ZERO.setScale(2), "b7b-opening-reserve"));
        list.add(new CashLedger.OpeningAccount("acme-cash", "acme-owner", money("1000.00"),
                "b7b-opening-personal"));
        list.add(new CashLedger.OpeningAccount("firm-cash", "firm-owner", money("400.00"),
                "b7b-opening-personal"));
        list.add(new CashLedger.OpeningAccount("co-cash", "co-owner", money("100.00"),
                "b7b-opening-company"));
        list.add(new CashLedger.OpeningAccount("dave-cash", "dave-owner", money("50.00"),
                "b7b-opening-personal"));
        return list;
    }

    /** @return 默认开局（north 准备金 1200）。 */
    public static List<CashLedger.OpeningAccount> openings() {
        return openings("1200.00");
    }

    /** @return 完整开局登记：两家银行、三家公司、三个托管组织、四个自然人 + 一只证券。 */
    public static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = new ArrayList<>();
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>();
        for (String[] row : new String[][]{
                {"north-org", "north-owner", "North Bank"},
                {"south-org", "south-owner", "South Bank"},
                {ISSUER, "co-owner", "Co Company"},
                {"cst-org", "cst-owner", "Custody One"},
                {"cst2-org", "cst2-owner", "Custody Two"},
                {"cst3-org", "cst3-owner", "Custody Three"}}) {
            parties.add(new OpeningRegistry.Party(row[0], OpeningRegistry.PartyKind.ORGANIZATION,
                    row[2]));
            owners.add(new OpeningRegistry.PropertyOwner(row[1],
                    OpeningRegistry.OwnerKind.ORGANIZATION, row[0]));
        }
        for (String person : List.of("acme", "firm", "dave", "bankmgr", "southmgr", "comgr")) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON,
                    person));
            owners.add(new OpeningRegistry.PropertyOwner(person + "-owner",
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYMBOL, ISSUER, 700L, money("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation("acme-cash", SYMBOL, 300L,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                new OpeningRegistry.ShareAllocation("firm-cash", SYMBOL, 200L,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                new OpeningRegistry.ShareAllocation("co-cash", SYMBOL, 100L,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                new OpeningRegistry.ShareAllocation("dave-cash", SYMBOL, 100L,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }

    /** @return 银行 profile：两家银行、四个客户主体、三个托管人与 co 的风险桶。 */
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
        parties.put("dave", new BankingProfile.PartyRef("dave", "dave-owner", "dave-cash",
                "dep-dave"));
        parties.put(ISSUER, new BankingProfile.PartyRef(ISSUER, "co-owner", "co-cash", "dep-co"));
        Map<String, BankingProfile.CustodyRef> custodians = new LinkedHashMap<>();
        custodians.put(CST, new BankingProfile.CustodyRef("cst-org", "cst-owner", "north",
                "pool-cst", BankRules.CREDIT_NON_ACCRUAL));
        custodians.put(CST3, new BankingProfile.CustodyRef("cst3-org", "cst3-owner", "north",
                "pool-cst3", BankRules.CREDIT_NON_ACCRUAL));
        custodians.put(CST2, new BankingProfile.CustodyRef("cst2-org", "cst2-owner", "south",
                "pool-cst2", BankRules.CREDIT_NON_ACCRUAL));
        Map<String, BankingProfile.RiskBucket> buckets = new LinkedHashMap<>();
        buckets.put(ISSUER, new BankingProfile.RiskBucket("book-co", null, "book-co",
                "impair-co"));
        return new BankingProfile(banks, parties, custodians, buckets, Map.of(),
                List.of("book-co"), List.of("impair-co"),
                List.of("dep-acme", "dep-firm", "dep-dave", "dep-co", "pool-cst", "pool-cst2",
                        "pool-cst3"), null, null, null);
    }

    /** @return 冻结档案：两家银行的存款 offer（2%、365 天）与完整门槛表（EXP 参考值）。 */
    public static BankConfig config() {
        Map<String, BankConfig.DepositOffer> offers = new LinkedHashMap<>();
        for (String bankKey : List.of("north", "south")) {
            offers.put(bankKey, new BankConfig.DepositOffer(bankKey, "OFF-" + bankKey,
                    InterestAccrual.rateU(new BigDecimal("0.02")), 365 * DAY,
                    BankRules.CREDIT_OFFER));
        }
        return new BankConfig.Builder(profile()).version(20).archiveId("B7B-CUSTODY-EXP")
                .label(RunManifest.RULES_BANKING_CUSTODY_EXPERIMENT_V1)
                .coverageRule("DECLARED_UNIVERSE_V1")
                .interest(BankConfig.Interest.act365()).depositOffers(offers)
                .groupMap(Map.of(ISSUER, "G-CO"))
                .thresholds(Map.of("cet1_min", money("0.01"), "cet1_buffer", money("0.01"),
                        "t1_min", money("0.01"), "tcr_min", money("0.01"),
                        "lev_min", money("0.01"), "single_name", money("9.90"),
                        "group_name", money("9.90"), "lcr_min", money("0.01"),
                        "maturity_gap_min", money("-0.90"),
                        "impair_cover_min", money("0.01")))
                .build();
    }

    /**
     * 历史禀赋：两家银行各有自有房产，三个池的存款真实存在（非应计），acme/firm 各有一笔零售存款。
     *
     * @param acmeDeposit      north 里 acme 的开局零售存款
     * @param firmSouthDeposit south 里 firm 的开局零售存款
     * @return 禀赋
     */
    public static BankOpening.DeclaredEndowment endowment(String acmeDeposit,
                                                          String firmSouthDeposit) {
        Map<String, BankOpening.BankBalance> banks = new LinkedHashMap<>();
        List<BankOpening.DepositEndowment> deposits = new ArrayList<>();
        Map<String, BigDecimal> north = new LinkedHashMap<>();
        north.put("PREMISES", money("1000.00"));
        north.put("pool-cst", money("1000.00").negate());
        north.put("pool-cst3", money("300.00").negate());
        if (money(acmeDeposit).signum() > 0) {
            north.put("dep-acme", money(acmeDeposit).negate());
            deposits.add(new BankOpening.DepositEndowment("north", "acme",
                    money(acmeDeposit), null));
        }
        Map<String, BigDecimal> south = new LinkedHashMap<>();
        south.put("PREMISES", money("1000.00"));
        south.put("pool-cst2", money("500.00").negate());
        if (money(firmSouthDeposit).signum() > 0) {
            south.put("dep-firm", money(firmSouthDeposit).negate());
            deposits.add(new BankOpening.DepositEndowment("south", "firm",
                    money(firmSouthDeposit), null));
        }
        // 托管池明细：每池存款资产 == 申报合计；明确非应计（与档案声明一致）。
        deposits.add(new BankOpening.DepositEndowment("north", CST, money("1000.00"),
                nonAccrual("1000.00")));
        deposits.add(new BankOpening.DepositEndowment("north", CST3, money("300.00"),
                nonAccrual("300.00")));
        deposits.add(new BankOpening.DepositEndowment("south", CST2, money("500.00"),
                nonAccrual("500.00")));
        banks.put("north", new BankOpening.BankBalance(north, "b7b-opening-capital"));
        banks.put("south", new BankOpening.BankBalance(south, "b7b-opening-capital"));
        return new BankOpening.DeclaredEndowment(banks, List.of(), deposits, List.of(),
                List.of(), Map.of(), List.of(), "b7b-opening-endowment");
    }

    /** @return 默认禀赋（acme 在 north 零售存款 1200，south 无零售存款）。 */
    public static BankOpening.DeclaredEndowment endowment() {
        return endowment("1200.00", "0.00");
    }

    /** @return 池的非应计条款（开局余额整体排除计息）。 */
    public static BankState.DepositAccount nonAccrual(String amount) {
        return new BankState.DepositAccount(null, 0L, 0L, 0L, BigInteger.ZERO, 0L, 0L, 0L,
                InterestAccrual.cents(money(amount)), "B7B_POOL_NON_ACCRUAL_OPENING",
                "B7B_POOL_NON_ACCRUAL");
    }

    /**
     * 托管开局声明：每池 客户 → 金额（合计必须等于该池已有存款）。
     *
     * @return 声明表
     */
    public static Map<String, Map<String, BigDecimal>> custodyDeclared() {
        Map<String, Map<String, BigDecimal>> declared = new LinkedHashMap<>();
        declared.put(CST, ordered("acme", "600.00", "firm", "400.00"));
        declared.put(CST3, ordered("dave", "300.00"));
        declared.put(CST2, ordered(ISSUER, "500.00"));
        return declared;
    }

    /** @return 托管来源声明。 */
    public static Map<String, String> custodyOrigins() {
        Map<String, String> origins = new LinkedHashMap<>();
        origins.put(CST, "b7b-opening-transfer:cst");
        origins.put(CST3, "b7b-opening-transfer:cst3");
        origins.put(CST2, "b7b-opening-transfer:cst2");
        return origins;
    }

    /** @return 银行开局申请（含托管历史明细）。 */
    public static BankOpeningRequest request() {
        return request("1200.00", "0.00");
    }

    /**
     * 指定禀赋的银行开局申请。
     *
     * @param acmeDeposit      acme 在 north 的零售存款
     * @param firmSouthDeposit firm 在 south 的零售存款
     * @return 申请
     */
    public static BankOpeningRequest request(String acmeDeposit, String firmSouthDeposit) {
        return BankOpeningRequest.of(config(), endowment(acmeDeposit, firmSouthDeposit),
                custodyDeclared(), custodyOrigins());
    }

    /** @return 任职开局：两家银行各有经理、公司 co 有经理；全部从 0 起无限期。 */
    public static RoleRun.Config roles() {
        List<RoleAccess.Appointment> appointments = new ArrayList<>();
        long end = 3650 * DAY;
        appointments.add(new RoleAccess.Appointment("a-mgr-north", "bankmgr", "north-org",
                RoleAccess.Role.COMPANY_MANAGER, 0L, end, null));
        appointments.add(new RoleAccess.Appointment("a-mgr-south", "southmgr", "south-org",
                RoleAccess.Role.COMPANY_MANAGER, 0L, end, null));
        appointments.add(new RoleAccess.Appointment("a-mgr-co", "comgr", ISSUER,
                RoleAccess.Role.COMPANY_MANAGER, 0L, end, null));
        return new RoleRun.Config(List.of(), appointments);
    }

    /** @return 资料阅读开局：六个自然人都是登记读者。 */
    public static InformationRun.Config information() {
        return new InformationRun.Config(
                List.of("acme", "firm", "dave", "bankmgr", "southmgr", "comgr"));
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
     * 指定禀赋的运行。
     *
     * @param file             运行日志
     * @param northReserve     north 准备金
     * @param acmeDeposit      acme 在 north 的零售存款
     * @param firmSouthDeposit firm 在 south 的零售存款
     * @return 世界运行
     * @throws Exception 打开失败
     */
    public static WorldRuntime open(Path file, String northReserve, String acmeDeposit,
                                    String firmSouthDeposit) throws Exception {
        return new WorldRuntime(file, manifest(), openings(northReserve), registry(), roles(),
                information(), request(acmeDeposit, firmSouthDeposit));
    }

    /** 纯核夹具：同一 profile/config/禀赋下的银行三态、现金账户与托管账。 */
    public record Core(Map<String, CashLedger.CashAccount> accounts, BankState bank,
                       BankConfig config, CustodyBook book) {
    }

    /**
     * 构建纯核夹具（不经 World 任务）。
     *
     * @param northReserve     north 准备金
     * @param acmeDeposit      acme 在 north 的零售存款
     * @param firmSouthDeposit firm 在 south 的零售存款
     * @return 纯核状态
     */
    public static Core core(String northReserve, String acmeDeposit, String firmSouthDeposit) {
        Map<String, CashLedger.CashAccount> accounts = new LinkedHashMap<>();
        for (CashLedger.OpeningAccount opening : openings(northReserve)) {
            accounts.put(opening.accountId(),
                    new CashLedger.CashAccount(opening.ownerId(), opening.cash(),
                            opening.source()));
        }
        BankOpening.Result opened = BankOpening.openDeclared(registry(), accounts, profile(),
                config(), endowment(acmeDeposit, firmSouthDeposit), RUN_ID);
        CustodyBook book = CustodyBook.declare(opened.state(), opened.accounts(), config(),
                custodyDeclared(), custodyOrigins());
        return new Core(opened.accounts(), opened.state(), config(), book);
    }

    /** @return 默认纯核夹具（north 准备金 1200、acme 零售存款 1200）。 */
    public static Core core() {
        return core("1200.00", "1200.00", "0.00");
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
     * 资金桶规范文本。
     *
     * @param bankKey   银行 key；现金传 null
     * @param holderKey 持有人/账户 key
     * @return 规范文本
     */
    public static String token(String bankKey, String holderKey) {
        return bankKey == null ? "CASH|" + holderKey : "DEPOSIT|" + bankKey + "|" + holderKey;
    }

    /**
     * 托管桶规范文本。
     *
     * @param custodian 托管人 key
     * @param client    客户 key
     * @return 规范文本
     */
    public static String custodyToken(String custodian, String client) {
        return "CUSTODY|" + custodian + "|" + client;
    }

    /**
     * 一次运行的可比较摘要（跨 JVM 逐项对比）。
     *
     * @param config     档案指纹
     * @param bank       银行三态摘要
     * @param accounts   现金账户摘要
     * @param exchange   订单/成交摘要
     * @param securities 持仓/预留摘要
     * @param custody    托管明细摘要
     * @param position   提交位置
     * @param timeMillis 模拟时点
     */
    public record Digests(String config, String bank, String accounts, String exchange,
                          String securities, String custody, long position, long timeMillis) {
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
        WorldRuntime.Snapshot snap = runtime.snapshot();
        StringBuilder accounts = new StringBuilder();
        for (Map.Entry<String, CashLedger.CashAccount> entry
                : new TreeMap<>(snap.accounts()).entrySet()) {
            accounts.append(entry.getKey()).append(':').append(entry.getValue().ownerId())
                    .append('=').append(entry.getValue().cash().toPlainString()).append(';');
        }
        StringBuilder exchange = new StringBuilder();
        if (snap.exchange().isPresent()) {
            edu.cufe.auction.world.WorldExchange.State state = snap.exchange().get();
            for (edu.cufe.auction.world.WorldExchange.Order order : state.orders()) {
                exchange.append(order.orderId()).append('/').append(order.accountId()).append('/')
                        .append(order.side()).append('/').append(order.status()).append('/')
                        .append(order.quantity()).append('/').append(order.filled()).append('/')
                        .append(order.fundingKey()).append(';');
            }
            for (edu.cufe.auction.world.WorldExchange.Trade trade : state.trades()) {
                exchange.append('T').append(trade.tradeId()).append('/').append(trade.price())
                        .append('/').append(trade.quantity()).append('/').append(trade.symbol())
                        .append(';');
            }
            exchange.append("next=").append(state.nextOrderId()).append(',')
                    .append(state.nextTradeId()).append(",closed=").append(state.closed());
        }
        StringBuilder securities = new StringBuilder();
        if (snap.securities().isPresent()) {
            edu.cufe.auction.world.SecuritiesLedger.State state = snap.securities().get();
            for (edu.cufe.auction.world.SecuritiesLedger.Lot lot : state.lots()) {
                securities.append(lot.lotId()).append('/').append(lot.accountId()).append('/')
                        .append(lot.symbol()).append('/').append(lot.remaining()).append(';');
            }
            for (edu.cufe.auction.world.SecuritiesLedger.SellReservation reservation
                    : state.reservations()) {
                long reserved = 0L;
                for (edu.cufe.auction.world.SecuritiesLedger.LotQuantity item
                        : reservation.allocations()) {
                    reserved += item.quantity();
                }
                securities.append('R').append(reservation.reservationId()).append('/')
                        .append(reserved).append(';');
            }
        }
        StringBuilder custody = new StringBuilder();
        for (Map.Entry<String, BigDecimal> entry : new TreeMap<>(bank.custodyDetails()).entrySet()) {
            custody.append(entry.getKey()).append('=').append(entry.getValue().toPlainString())
                    .append(';');
        }
        return new Digests(bank.configFingerprint(), bank.digest(), sha(accounts.toString()),
                sha(exchange.toString()), sha(securities.toString()), sha(custody.toString()),
                snap.position(), snap.timeMillis());
    }

    private static Map<String, BigDecimal> ordered(String... pairs) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], money(pairs[index + 1]));
        }
        return map;
    }

    private static String sha(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * 订单方向的可读名。
     *
     * @param side 方向
     * @return 文本
     */
    public static String sideName(Side side) {
        return side.name();
    }
}
