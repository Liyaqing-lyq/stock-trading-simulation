package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.EngineeringRun;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.InterviewRun;
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
 * {@code composed-v3} 托管成交实验开局：真实组合开局 + 一家托管人 {@code cst} 与两个客户。
 *
 * <p>只走公开 API 与真实开局：现有 15+ 现金账户、股份与银行实收资本逐字沿用
 * {@link ComposedWorldScenario}；新增的 1000 托管池由 north 的历史房产 1000 对价
 * （EXP 历史禀赋，A = L + E 不变），客户明细 {@code investor=400 / eng-a=600} 合计等于池资产，
 * 不发放任何新钱。{@code composed-v1/v2} 用同一份真实开局时仍拒绝 CUSTODY 资金腿。</p>
 */
public final class ComposedCustodyScenario {

    /** 托管人 key。 */
    public static final String CST = "cst";
    /** 托管人组织 party。 */
    public static final String CST_ORG = "cst-org";
    /** 托管人 owner。 */
    public static final String CST_OWNER = "cst-owner";
    /** 托管池账户 key。 */
    public static final String POOL = "pool-cst";
    /** 卖方客户（投资方）：托管桶 {@code CUSTODY|cst|investor}。 */
    public static final String CLIENT_SELLER = "investor";
    /** 卖方现金/证券账户。 */
    public static final String SELLER_ACCOUNT = "INV";
    /** 卖方账户 owner。 */
    public static final String SELLER_OWNER = WorldWebScenario.INVESTOR;
    /** 买方客户（工程 A）：托管桶 {@code CUSTODY|cst|eng-a}。 */
    public static final String CLIENT_BUYER = "eng-a";
    /** 买方现金/证券账户。 */
    public static final String BUYER_ACCOUNT = "ENG-A";
    /** 买方账户 owner。 */
    public static final String BUYER_OWNER = "eng-a";
    /** 证券代码。 */
    public static final String SYMBOL = "CUFE-A";
    /** 池资产/客户明细合计。 */
    public static final String POOL_AMOUNT = "1000.00";

    private ComposedCustodyScenario() {
    }

    /**
     * 组合清单。
     *
     * @param rulesVersion {@code composed-v2} 或 {@code composed-v3}
     * @return 清单
     */
    public static RunManifest manifest(String rulesVersion) {
        Map<String, Integer> modules = new TreeMap<>(
                WorldWebScenario.groupsManifest().moduleVersions());
        modules.put("banking", 1);
        modules.put("custody", 1);
        modules.put("funding", 1);
        modules.put("company-governance", 1);
        modules.put("command-intent", 1);
        if (RunManifest.RULES_COMPOSED_BANK_WORLD_V3.equals(rulesVersion)) {
            modules.put("custody-fill", 1);
        }
        return new RunManifest("composed-custody", 11L, rulesVersion, modules,
                Map.of("profile", "composed-custody-v3"), RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return 组合托管清单（{@code composed-v3}）。 */
    public static RunManifest manifest() {
        return manifest(RunManifest.RULES_COMPOSED_BANK_WORLD_V3);
    }

    /** @return 现金开局：组合原 17 账户逐字不变，另加托管机构自己的 0 现金账户（真实主体）。 */
    public static List<CashLedger.OpeningAccount> openings() {
        List<CashLedger.OpeningAccount> list = new ArrayList<>(ComposedWorldScenario.openings());
        list.add(new CashLedger.OpeningAccount("CST-CASH", CST_OWNER,
                BigDecimal.ZERO.setScale(2), "组合托管机构自有现金账户（历史 0）"));
        return list;
    }

    /** @return 开局登记：组合原登记 + 托管组织/owner。 */
    public static OpeningRegistry registry() {
        OpeningRegistry base = ComposedWorldScenario.registry();
        List<OpeningRegistry.Party> parties = new ArrayList<>(base.parties());
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(base.owners());
        parties.add(new OpeningRegistry.Party(CST_ORG, OpeningRegistry.PartyKind.ORGANIZATION,
                "组合托管人（实验）"));
        owners.add(new OpeningRegistry.PropertyOwner(CST_OWNER,
                OpeningRegistry.OwnerKind.ORGANIZATION, CST_ORG));
        return new OpeningRegistry(parties, owners, base.securities(), base.allocations());
    }

    /** @return 银行 profile：组合原 profile + 托管人 cst（north 结算、非应计池）。 */
    public static BankingProfile profile() {
        BankingProfile base = ComposedWorldScenario.profile();
        Map<String, BankingProfile.CustodyRef> custodians =
                new LinkedHashMap<>(base.custodians());
        custodians.put(CST, new BankingProfile.CustodyRef(CST_ORG, CST_OWNER,
                ComposedWorldScenario.BANK, POOL, BankRules.CREDIT_NON_ACCRUAL));
        List<String> deposits = new ArrayList<>(base.depositAccounts());
        deposits.add(POOL);
        return new BankingProfile(base.banks(), base.parties(), custodians, base.buckets(),
                base.groups(), base.loanAccounts(), base.llpAccounts(), deposits,
                base.providerKey(), base.subordinatedOwnerKey(), base.backgroundBorrowerKey());
    }

    /** @return 冻结档案（组合原阈值与报价 + cst 池）。 */
    public static BankConfig config() {
        BankingProfile profile = profile();
        BankConfig base = ComposedWorldScenario.config();
        return new BankConfig.Builder(profile).version(24)
                .archiveId("COMPOSED-CUSTODY-EXP")
                .label(RunManifest.RULES_COMPOSED_BANK_WORLD_V3)
                .coverageRule("DECLARED_UNIVERSE_V1")
                .interest(BankConfig.Interest.act365())
                .depositOffers(base.depositOffers())
                .groupMap(Map.of("cufe", "G-CUFE", "eng-a", "G-ENGA", "eng-b", "G-ENGB"))
                .thresholds(base.thresholds())
                .build();
    }

    /** @return 非应计池条款（开局余额整体排除计息）。 */
    public static BankState.DepositAccount nonAccrual(String amount) {
        return new BankState.DepositAccount(null, 0L, 0L, 0L, BigInteger.ZERO, 0L, 0L, 0L,
                InterestAccrual.cents(money(amount)), "COMPOSED_CUSTODY_POOL_NON_ACCRUAL_OPENING",
                "COMPOSED_CUSTODY_POOL_NON_ACCRUAL");
    }

    /**
     * 历史禀赋：north 房产 1000 对价托管池 1000，客户明细合计等于池资产。
     *
     * @return 禀赋
     */
    public static BankOpening.DeclaredEndowment endowment() {
        Map<String, BankOpening.BankBalance> banks = new LinkedHashMap<>();
        Map<String, BigDecimal> north = new LinkedHashMap<>();
        north.put("PREMISES", money(POOL_AMOUNT));
        north.put(POOL, money(POOL_AMOUNT).negate());
        banks.put(ComposedWorldScenario.BANK,
                new BankOpening.BankBalance(north, "EXP-组合档托管房产-历史禀赋"));
        List<BankOpening.DepositEndowment> deposits = new ArrayList<>();
        deposits.add(new BankOpening.DepositEndowment(ComposedWorldScenario.BANK, CST,
                money(POOL_AMOUNT), nonAccrual(POOL_AMOUNT)));
        return new BankOpening.DeclaredEndowment(banks, List.of(), deposits, List.of(),
                List.of(), Map.of(), List.of(), "composed-custody-opening-endowment");
    }

    /** @return 托管开局声明：池 1000 = investor 400 + eng-a 600。 */
    public static Map<String, Map<String, BigDecimal>> custodyDeclared() {
        Map<String, Map<String, BigDecimal>> declared = new LinkedHashMap<>();
        Map<String, BigDecimal> details = new LinkedHashMap<>();
        details.put(CLIENT_SELLER, money("400.00"));
        details.put(CLIENT_BUYER, money("600.00"));
        declared.put(CST, details);
        return declared;
    }

    /** @return 托管来源声明。 */
    public static Map<String, String> custodyOrigins() {
        return Map.of(CST, "composed-v3-opening-transfer:cst");
    }

    /** @return 银行开局申请（含真实托管明细）。 */
    public static BankOpeningRequest bankOpening() {
        return BankOpeningRequest.of(config(), endowment(), custodyDeclared(), custodyOrigins());
    }

    /**
     * 与 {@link ComposedWorldScenario#open(Path)} 同一套真实配置，只替换清单、登记与银行开局。
     *
     * @param journal 运行日志
     * @param rulesVersion {@code composed-v2} 或 {@code composed-v3}
     * @return 运行
     * @throws Exception 打开失败
     */
    public static WorldRuntime open(Path journal, String rulesVersion) throws Exception {
        long end = 30L * 86_400_000L;
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("A-1", "alice", WorldWebScenario.COMPANY,
                        RoleAccess.Role.RESEARCHER, 0L, end, null),
                new RoleAccess.Appointment("A-2", "bob", WorldWebScenario.COMPANY,
                        RoleAccess.Role.PORTFOLIO_MANAGER, 0L, end, null),
                new RoleAccess.Appointment("A-3", "carol", WorldWebScenario.COMPANY,
                        RoleAccess.Role.RISK_OFFICER, 0L, end, null),
                new RoleAccess.Appointment("A-4", "dave", WorldWebScenario.COMPANY,
                        RoleAccess.Role.EXECUTION_TRADER, 0L, end, null)));
        for (int i = 0; i < WorldWebScenario.MEDIA_PERSONS.size(); i++) {
            appointments.add(new RoleAccess.Appointment("MEDIA-" + i,
                    WorldWebScenario.MEDIA_PERSONS.get(i),
                    i < 2 ? "wire" : "investigative",
                    i % 2 == 0 ? RoleAccess.Role.JOURNALIST : RoleAccess.Role.EDITOR,
                    0L, end, null));
        }
        List<String> readers = new ArrayList<>(List.of("alice", "bob", "carol", "dave", "erin",
                WorldWebScenario.INVESTOR));
        readers.addAll(WorldWebScenario.MEDIA_PERSONS);
        readers.addAll(WorldWebScenario.SOCIAL_PERSONS);
        InformationRun.Config info = new InformationRun.Config(readers,
                WorldWebScenario.COGNITION_POLICY,
                new edu.cufe.auction.world.BeliefModel.Policy(200, 8, end, 20, 200, 3, end));
        List<InterviewRun.ContactEntry> contacts =
                new ArrayList<>(WorldWebScenario.interviewConfig().contacts());
        for (String person : WorldWebScenario.SOCIAL_PERSONS) {
            contacts.add(new InterviewRun.ContactEntry("私信", person,
                    "公开联系说明：可一对一私人消息", WorldWebScenario.SOCIAL_CONTACT_DOC, 1L));
        }
        return new WorldRuntime(journal, manifest(rulesVersion), openings(), registry(),
                new edu.cufe.auction.world.ManufacturingRun.Config(
                        new edu.cufe.auction.world.ManufacturingModel.Opening("company", "CO",
                                "SUP", "CUS", "OPS"), WorldWebScenario.SYMBOL),
                ComposedWorldScenario.cufeCharter(),
                new RoleRun.Config(List.of(new RoleAccess.ProductMandate(
                        WorldWebScenario.COMPANY, "fund-a", List.of("ACC-FUND"))), appointments),
                info,
                WorldWebScenario.groupsDecisionConfig(), null,
                WorldWebScenario.mediaConfig(), WorldWebScenario.interviewConfig(),
                WorldWebScenario.dialogueConfig(), WorldWebScenario.evidenceConfig(),
                WorldWebScenario.socialConfig(), WorldWebScenario.SOCIAL_BLOCK_MILLIS,
                contacts, WorldWebScenario.engineeringConfig(),
                WorldWebScenario.groupsConfig(), WorldWebScenario.groupsPlatformConfig(),
                WorldWebScenario.GROUPS_FEED_LIMIT, WorldWebScenario.GROUPS_READ_MILLIS,
                ComposedWorldScenario.charters(), bankOpening());
    }

    /** @return 默认组合托管运行（{@code composed-v3}）。 */
    public static WorldRuntime open(Path journal) throws Exception {
        return open(journal, RunManifest.RULES_COMPOSED_BANK_WORLD_V3);
    }

    /**
     * 金额（2 位小数）。
     *
     * @param value 文本
     * @return 金额
     */
    public static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }
}
