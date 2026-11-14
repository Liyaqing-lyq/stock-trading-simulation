package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankRules;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.InterestAccrual;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code banking-roles-experiment-v1} 的合法托管开局（Web 托管夹具的真实 BankOpeningRequest 契约）。
 *
 * <p>局面：准备金 3300.00、托管池存款负债 300.00、实收资本 3000.00；池内 cust-a 120.00 +
 * cust-b 180.00。角色档只带 cash/scheduler/registry/banking/custody/roles/information 七个模块，
 * 不补 manufacturing/governance；托管人存款必须显式声明为整户非应计，否则核心按
 * {@code CUSTODY_POOL_ACCRUAL} 拒绝，不能靠调低利息开关绕过。</p>
 *
 * <p>断言三本账同时成立：现金账 Σ=3300（未被改写）；银行账 RESERVE 3300 = 存款 300 + 权益 3000；
 * 托管账池 300 = 120 + 180，且两位客户各自的投影只看到自己那一份。</p>
 */
class BankRolesCustodyOpeningTest {

    private static final String RUN_ID = "bank-roles-custody-run";
    private static final String BANK = "north";
    private static final String CUSTODIAN = "custodian";
    private static final String CLIENT_A = "cust-a";
    private static final String CLIENT_B = "cust-b";

    @Test
    void legalRolesBankOpensWithPerClientCustodyAndThreeLedgers() throws Exception {
        Path dir = Files.createTempDirectory("bank-roles-custody");
        Path journal = dir.resolve("custody.journal");

        BankRun.Audit opened;
        try (WorldRuntime runtime = open(journal)) {
            assertEquals(1L, runtime.snapshot().position(), "开局只写第一帧");
            BankRun bank = runtime.bankOf().orElseThrow();
            opened = runtime.bankAudit().orElseThrow();

            Map<String, CashLedger.CashAccount> accounts = runtime.snapshot().accounts();
            assertEquals(3, accounts.size(), "只应有准备金与两位客户现金账户");
            assertEquals("north-owner", accounts.get("RSV-NORTH").ownerId());
            assertEquals(money("3300.00"), accounts.get("RSV-NORTH").cash());
            assertEquals(money("0.00"), accounts.get("CUST-A").cash());
            assertEquals(money("0.00"), accounts.get("CUST-B").cash());
            BigDecimal cashTotal = accounts.values().stream().map(CashLedger.CashAccount::cash)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(money("3300.00"), cashTotal.setScale(2, RoundingMode.UNNECESSARY),
                    "现金账合计必须等于开局声明，不得因托管明细发新钱");

            // 银行账：A = L + E，且三侧数字就是真实开局禀赋
            BankState state = bank.decode(accounts).state();
            BigDecimal assets = state.gl(BANK, "RESERVE");
            for (String account : state.chart().assets()) {
                assets = assets.add(state.gl(BANK, account));
            }
            BigDecimal liabilities = BigDecimal.ZERO;
            for (String account : state.chart().liabilities()) {
                liabilities = liabilities.subtract(state.gl(BANK, account));
            }
            BigDecimal equity = BigDecimal.ZERO;
            for (String account : state.chart().equity()) {
                equity = equity.subtract(state.gl(BANK, account));
            }
            assertEquals(money("3300.00"), assets.setScale(2, RoundingMode.UNNECESSARY),
                    "资产 = 准备金 3300（+ 真实其他资产科目）");
            assertEquals(money("300.00"), liabilities.setScale(2, RoundingMode.UNNECESSARY),
                    "负债 = 托管池存款 300");
            assertEquals(money("3000.00"), equity.setScale(2, RoundingMode.UNNECESSARY),
                    "权益 = 实收资本 3000");
            assertEquals(assets.setScale(2, RoundingMode.UNNECESSARY),
                    liabilities.add(equity).setScale(2, RoundingMode.UNNECESSARY),
                    "银行账必须 A = L + E");

            // 托管账：池 300 = 客户明细 120 + 180
            assertEquals(money("300.00"), opened.custodyPools().get(CUSTODIAN));
            assertEquals(money("300.00"), opened.custodyBeneficial().get(CUSTODIAN));
            Map<String, BigDecimal> details = bank.custodyDetails();
            assertEquals(2, details.size(), "只应有两个客户明细");
            assertEquals(money("120.00"), details.get(CUSTODIAN + "|" + CLIENT_A));
            assertEquals(money("180.00"), details.get(CUSTODIAN + "|" + CLIENT_B));
            assertEquals(money("3300.00"), opened.backing(),
                    "银行现金背书合计等于现金账合计");

            // 两个客户各自只看到自己的受益权，金额非零
            BankRun.Projection a = runtime.bankProjection(CLIENT_A).orElseThrow();
            BankRun.Projection b = runtime.bankProjection(CLIENT_B).orElseThrow();
            assertEquals(money("0.00"), a.cash());
            assertEquals(money("0.00"), a.deposits());
            assertEquals(money("120.00"), a.beneficial(), "客户甲只看到自己的 120");
            assertEquals(money("180.00"), b.beneficial(), "客户乙只看到自己的 180");
        }

        // 重开同一日志：账与投影逐项一致（真实持久化，不是内存假象）
        try (WorldRuntime reopened = open(journal)) {
            BankRun.Audit again = reopened.bankAudit().orElseThrow();
            assertEquals(opened.reserves(), again.reserves());
            assertEquals(opened.deposits(), again.deposits());
            assertEquals(opened.custodyPools(), again.custodyPools());
            assertEquals(opened.custodyBeneficial(), again.custodyBeneficial());
            assertEquals(opened.backing(), again.backing());
            assertEquals(money("120.00"),
                    reopened.bankProjection(CLIENT_A).orElseThrow().beneficial());
            assertEquals(money("180.00"),
                    reopened.bankProjection(CLIENT_B).orElseThrow().beneficial());
            assertEquals(1L, reopened.snapshot().position(),
                    "未写入新批次时重开位置保持 1（账与投影一致即可）");
        }
    }

    /** 七个模块的银行角色档 + 真实托管池禀赋（含整户非应计存款条款）。 */
    private static WorldRuntime open(Path journal) throws Exception {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "information", "roles",
                "banking", "custody")) {
            modules.put(name, 1);
        }
        RunManifest manifest = new RunManifest(RUN_ID, 19L,
                RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1, modules,
                Map.of("profile", "bank-roles-custody"), RunManifest.MODEL_NONE);
        List<CashLedger.OpeningAccount> openings = List.of(
                new CashLedger.OpeningAccount("RSV-NORTH", "north-owner", money("3300.00"),
                        "EXP 准备金历史禀赋"),
                new CashLedger.OpeningAccount("CUST-A", CLIENT_A, money("0.00"), "客户甲现金"),
                new CashLedger.OpeningAccount("CUST-B", CLIENT_B, money("0.00"), "客户乙现金"));
        OpeningRegistry registry = new OpeningRegistry(
                List.of(new OpeningRegistry.Party(BANK, OpeningRegistry.PartyKind.ORGANIZATION,
                                "North Bank（实验）"),
                        new OpeningRegistry.Party(CUSTODIAN,
                                OpeningRegistry.PartyKind.ORGANIZATION, "托管人（实验）"),
                        new OpeningRegistry.Party(CLIENT_A, OpeningRegistry.PartyKind.PERSON, "客户甲"),
                        new OpeningRegistry.Party(CLIENT_B, OpeningRegistry.PartyKind.PERSON, "客户乙")),
                List.of(new OpeningRegistry.PropertyOwner("north-owner",
                                OpeningRegistry.OwnerKind.ORGANIZATION, BANK),
                        new OpeningRegistry.PropertyOwner("custodian-owner",
                                OpeningRegistry.OwnerKind.ORGANIZATION, CUSTODIAN),
                        new OpeningRegistry.PropertyOwner(CLIENT_A,
                                OpeningRegistry.OwnerKind.PERSON, CLIENT_A),
                        new OpeningRegistry.PropertyOwner(CLIENT_B,
                                OpeningRegistry.OwnerKind.PERSON, CLIENT_B)),
                List.of(), List.of());
        BankingProfile profile = new BankingProfile(
                Map.of(BANK, new BankingProfile.BankRef(BANK, BANK, "north-owner", "RSV-NORTH")),
                Map.of(CLIENT_A, new BankingProfile.PartyRef(CLIENT_A, CLIENT_A, "CUST-A", null),
                        CLIENT_B, new BankingProfile.PartyRef(CLIENT_B, CLIENT_B, "CUST-B", null)),
                Map.of(CUSTODIAN, new BankingProfile.CustodyRef(CUSTODIAN, "custodian-owner",
                        BANK, "dep-cust", BankRules.CREDIT_NON_ACCRUAL)),
                Map.of(), Map.of(), List.of(), List.of(), List.of("dep-cust"), null, null, null);
        BankConfig config = new BankConfig.Builder(profile).version(19)
                .archiveId("CUSTODY-WEB-EXP").label(RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1)
                .coverageRule("DECLARED_UNIVERSE_V1")
                .interest(BankConfig.Interest.act365())
                .depositOffers(Map.of(BANK, new BankConfig.DepositOffer(BANK, "OFF-north",
                        InterestAccrual.rateU(new BigDecimal("0.02")), 365L * 86_400_000L,
                        BankRules.CREDIT_OFFER)))
                .groupMap(Map.of())
                .thresholds(Map.of("cet1_min", money("0.01"), "cet1_buffer", money("0.01"),
                        "t1_min", money("0.01"), "tcr_min", money("0.01"),
                        "lev_min", money("0.01"), "single_name", money("9.90"),
                        "group_name", money("9.90"), "lcr_min", money("0.01"),
                        "maturity_gap_min", money("-0.90"),
                        "impair_cover_min", money("0.01")))
                .build();
        // 托管池存款必须是整户明确非应计：offerId=null、rate=0、maturity=start、无累计。
        BankState.DepositAccount nonAccrual = new BankState.DepositAccount(null, 0L, 0L, 0L,
                BigInteger.ZERO, 0L, 0L, 0L, 30_000L, "EXP 托管池整户非应计",
                "EXP 托管池整户非应计");
        BankOpening.DeclaredEndowment endowment = new BankOpening.DeclaredEndowment(
                Map.of(BANK, new BankOpening.BankBalance(
                        Map.of("dep-cust", money("-300.00")), "EXP 托管池存款历史禀赋")),
                List.of(),
                List.of(new BankOpening.DepositEndowment(BANK, CUSTODIAN, money("300.00"),
                        nonAccrual)),
                List.of(), List.of(), Map.of(), List.of(), "custody-web-opening-endowment");
        BankOpeningRequest request = BankOpeningRequest.of(config, endowment,
                Map.of(CUSTODIAN, Map.of(CLIENT_A, money("120.00"), CLIENT_B, money("180.00"))),
                Map.of(CUSTODIAN, "EXP 托管客户明细历史来源"));
        RoleRun.Config roles = new RoleRun.Config(List.of(), List.of());
        InformationRun.Config information = new InformationRun.Config(List.of(CLIENT_A, CLIENT_B));
        return new WorldRuntime(journal, manifest, openings, registry, roles, information, request);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }
}
