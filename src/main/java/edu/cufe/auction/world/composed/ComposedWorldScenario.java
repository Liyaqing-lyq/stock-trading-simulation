package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.EngineeringRun;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 组合档（{@code composed-v1}，schema24）的显式开局：现有群组/工程/周期/历史世界 +
 * 一家真实银行（north）。
 *
 * <p><b>不重复给钱</b>：现有 15 个现金账户与全部股份逐字不变；只新增
 * {@code RSV-NORTH} 3000（EXP 历史禀赋，同一批由银行 PAIDIN 3000 对价）与
 * {@code BANK-FOUNDER-SEC} 0 现金账户（持 BNK1 全部 200 股，成本 15，银行无库存股）。
 * 总现金 = 原有的 48,100 + 3,000 = 51,100；银行 A = L + E：3000 = 0 + 3000。</p>
 *
 * <p><b>治理</b>：north 不是工程公司，也不是制造公司；它只通过
 * {@code CompanyGovernanceRun} 拥有真实章程与董事会。开局<b>不预置经理任职</b>，
 * 经理必须由真实会议（召集→到会→材料实读→提案→表决→实施）产生。</p>
 */
public final class ComposedWorldScenario {

    /** 组合档运行标识。 */
    public static final String RUN_ID = "web-composed";
    /** 银行 key。 */
    public static final String BANK = "north";
    /** 银行真实组织。 */
    public static final String BANK_PARTY = "north";
    /** 银行财产主体（准备金账户属主）。 */
    public static final String BANK_OWNER = "north-owner";
    /** 准备金现金账户。 */
    public static final String RESERVE = "RSV-NORTH";
    /** 银行股份代码。 */
    public static final String BNK1 = "BNK1";
    /** 银行创始人财产主体。 */
    public static final String FOUNDER = "bank-founder";
    /** 创始人证券账户（现金 0）。 */
    public static final String FOUNDER_SEC = "BANK-FOUNDER-SEC";
    /** 银行初始实收资本（EXP）。 */
    public static final String CAPITAL = "3000.00";
    /** 银行股初始参考价/成本（EXP）。 */
    public static final String SHARE_PRICE = "15.00";

    private ComposedWorldScenario() {
    }

    /** @return 组合档清单：群组 20 档模块 + banking/custody/funding/company-governance + command-intent */
    public static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>(
                WorldWebScenario.groupsManifest().moduleVersions());
        modules.put("banking", 1);
        modules.put("custody", 1);
        modules.put("funding", 1);
        modules.put("company-governance", 1);
        modules.put("command-intent", 1);
        return new RunManifest(RUN_ID, 11L, RunManifest.RULES_COMPOSED_BANK_WORLD_V2,
                modules, Map.of("profile", "composed-bank-v2"), RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return 现有 15 账户不变 + 准备金 3000 + 创始人证券账户 0 */
    public static List<CashLedger.OpeningAccount> openings() {
        List<CashLedger.OpeningAccount> list = new ArrayList<>(
                WorldWebScenario.engineeringOpenings());
        list.add(new CashLedger.OpeningAccount(RESERVE, BANK_OWNER, money(CAPITAL),
                "组合档银行实收资本 EXP 历史禀赋"));
        list.add(new CashLedger.OpeningAccount(FOUNDER_SEC, FOUNDER, money("0.00"),
                "组合档银行创始人证券账户"));
        return list;
    }

    /** @return 现有登记 + north 组织/owner + 创始人组织/owner + BNK1 */
    public static OpeningRegistry registry() {
        OpeningRegistry base = WorldWebScenario.engineeringRegistry();
        List<OpeningRegistry.Party> parties = new ArrayList<>(base.parties());
        parties.add(new OpeningRegistry.Party(BANK_PARTY,
                OpeningRegistry.PartyKind.ORGANIZATION, "North Bank（实验）"));
        parties.add(new OpeningRegistry.Party(FOUNDER,
                OpeningRegistry.PartyKind.ORGANIZATION, "North Bank 创始人（实验）"));
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(base.owners());
        owners.add(new OpeningRegistry.PropertyOwner(BANK_OWNER,
                OpeningRegistry.OwnerKind.ORGANIZATION, BANK_PARTY));
        owners.add(new OpeningRegistry.PropertyOwner(FOUNDER,
                OpeningRegistry.OwnerKind.ORGANIZATION, FOUNDER));
        List<OpeningRegistry.Security> securities = new ArrayList<>(base.securities());
        securities.add(new OpeningRegistry.Security(BNK1, BANK_PARTY, 200L,
                money(SHARE_PRICE)));
        List<OpeningRegistry.ShareAllocation> allocations = new ArrayList<>(base.allocations());
        allocations.add(new OpeningRegistry.ShareAllocation(FOUNDER_SEC, BNK1, 200L,
                new BigDecimal("15.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }

    /** @return 银行档案：north 一家银行、三个可存款公司主体、无托管人（托管成交未接） */
    public static BankingProfile profile() {
        Map<String, BankingProfile.BankRef> banks = new LinkedHashMap<>();
        banks.put(BANK, new BankingProfile.BankRef(BANK, BANK_PARTY, BANK_OWNER, RESERVE));
        Map<String, BankingProfile.PartyRef> parties = new LinkedHashMap<>();
        parties.put("cufe", new BankingProfile.PartyRef("cufe", "company", "CO", "dep-cufe"));
        parties.put("eng-a", new BankingProfile.PartyRef("eng-a", "eng-a", "ENG-A", "dep-eng-a"));
        parties.put("eng-b", new BankingProfile.PartyRef("eng-b", "eng-b", "ENG-B", "dep-eng-b"));
        parties.put("investor", new BankingProfile.PartyRef(WorldWebScenario.INVESTOR,
                WorldWebScenario.INVESTOR, "INV", null));
        parties.put("fund-a", new BankingProfile.PartyRef(null, "fund-a", "ACC-FUND", null,
                BankingProfile.PartyKind.PRODUCT));
        parties.put("supplier", new BankingProfile.PartyRef(null, "supplier", "SUP", null,
                BankingProfile.PartyKind.AGGREGATE));
        parties.put("customer", new BankingProfile.PartyRef(null, "customer", "CUS", null,
                BankingProfile.PartyKind.AGGREGATE));
        parties.put("ops", new BankingProfile.PartyRef(null, "ops", "OPS", null,
                BankingProfile.PartyKind.AGGREGATE));
        parties.put("locked", new BankingProfile.PartyRef(null, "locked", "LOCK", null,
                BankingProfile.PartyKind.AGGREGATE));
        for (String company : List.of("eng-a", "eng-b")) {
            for (String suffix : List.of("-cus", "-sup", "-ops")) {
                // 组合档 v2：工程公司的客户/供应商/作业方主体显式开通存款科目，
                // 这样显式资金动作可以合法选它们的存款桶（每主体一个科目，仍只在该主体名下）。
                parties.put(company + suffix, new BankingProfile.PartyRef(null,
                        company + suffix, company.toUpperCase().replace("-", "-") + suffix
                                .toUpperCase(), "dep-" + company + suffix,
                        BankingProfile.PartyKind.AGGREGATE));
            }
        }
        parties.put("bank-founder", new BankingProfile.PartyRef(FOUNDER, FOUNDER, FOUNDER_SEC,
                null));
        Map<String, BankingProfile.RiskBucket> buckets = new LinkedHashMap<>();
        List<String> loans = new ArrayList<>();
        List<String> llps = new ArrayList<>();
        for (String party : List.of("cufe", "eng-a", "eng-b")) {
            buckets.put(party, new BankingProfile.RiskBucket("book-" + party, null,
                    "book-" + party, "impair-" + party));
            loans.add("book-" + party);
            llps.add("impair-" + party);
        }
        List<String> deposits = new ArrayList<>(List.of("dep-cufe", "dep-eng-a", "dep-eng-b"));
        for (String company : List.of("eng-a", "eng-b")) {
            for (String suffix : List.of("-cus", "-sup", "-ops")) {
                deposits.add("dep-" + company + suffix);
            }
        }
        return new BankingProfile(banks, parties, Map.of(), buckets, Map.of(),
                List.copyOf(loans), List.copyOf(llps), List.copyOf(deposits), null, null, null);
    }

    /** @return 冻结规则（EXP：2% 存款报价、ACT365、完整门槛表；不声称监管合规） */
    public static BankConfig config() {
        Map<String, BankConfig.DepositOffer> offers = new LinkedHashMap<>();
        offers.put(BANK, new BankConfig.DepositOffer(BANK, "OFF-" + BANK,
                InterestAccrual.rateU(new BigDecimal("0.02")), 365L * 86_400_000L,
                BankRules.CREDIT_OFFER));
        return new BankConfig.Builder(profile()).version(24).archiveId("COMPOSED-BANK-EXP")
                .label(RunManifest.RULES_COMPOSED_BANK_WORLD_V2)
                .coverageRule("DECLARED_UNIVERSE_V1")
                .interest(BankConfig.Interest.act365()).depositOffers(offers)
                .groupMap(Map.of("cufe", "G-CUFE", "eng-a", "G-ENGA", "eng-b", "G-ENGB"))
                .thresholds(Map.of("cet1_min", money("0.01"), "cet1_buffer", money("0.01"),
                        "t1_min", money("0.01"), "tcr_min", money("0.01"),
                        "lev_min", money("0.01"), "single_name", money("9.90"),
                        "group_name", money("9.90"), "lcr_min", money("0.01"),
                        "maturity_gap_min", money("-0.90"),
                        "impair_cover_min", money("0.01")))
                .build();
    }

    /** @return 历史禀赋：只有 north 的实收资本来源，无存款、无贷款、无房产 */
    public static BankOpening.DeclaredEndowment endowment() {
        Map<String, BankOpening.BankBalance> banks = new LinkedHashMap<>();
        banks.put(BANK, new BankOpening.BankBalance(Map.of(),
                "EXP-组合档银行实收资本-历史禀赋"));
        return new BankOpening.DeclaredEndowment(banks, List.of(), List.of(), List.of(),
                List.of(), Map.of(), List.of(), "composed-bank-opening-endowment");
    }

    /** @return 银行开局申请（空托管池，不启用托管成交） */
    public static BankOpeningRequest bankOpening() {
        return BankOpeningRequest.of(config(), endowment(), Map.of(), Map.of());
    }

    /** @return north 五席章程：主席 alice；开局没有经理任职 */
    public static Map<String, edu.cufe.auction.world.BoardModel.Charter> charters() {
        List<edu.cufe.auction.world.BoardModel.Seat> seats = List.of(
                new edu.cufe.auction.world.BoardModel.Seat("n1", "alice"),
                new edu.cufe.auction.world.BoardModel.Seat("n2", "bob"),
                new edu.cufe.auction.world.BoardModel.Seat("n3", "carol"),
                new edu.cufe.auction.world.BoardModel.Seat("n4", "dave"),
                new edu.cufe.auction.world.BoardModel.Seat("n5", "erin"));
        return Map.of(BANK, new edu.cufe.auction.world.BoardModel.Charter(BANK, seats,
                "n1", edu.cufe.auction.world.BoardModel.QUORUM));
    }

    /** @return 组合档完整运行（同一日志重开必须给同一申请） */
    public static WorldRuntime open(Path journal) throws Exception {
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
        List<edu.cufe.auction.world.InterviewRun.ContactEntry> contacts =
                new ArrayList<>(WorldWebScenario.interviewConfig().contacts());
        for (String person : WorldWebScenario.SOCIAL_PERSONS) {
            contacts.add(new edu.cufe.auction.world.InterviewRun.ContactEntry("私信", person,
                    "公开联系说明：可一对一私人消息", WorldWebScenario.SOCIAL_CONTACT_DOC, 1L));
        }
        return new WorldRuntime(journal, manifest(), openings(), registry(),
                new edu.cufe.auction.world.ManufacturingRun.Config(
                        new edu.cufe.auction.world.ManufacturingModel.Opening("company", "CO",
                                "SUP", "CUS", "OPS"), WorldWebScenario.SYMBOL),
                cufeCharter(),
                new RoleRun.Config(List.of(new RoleAccess.ProductMandate(
                        WorldWebScenario.COMPANY, "fund-a", List.of("ACC-FUND"))), appointments),
                info,
                WorldWebScenario.groupsDecisionConfig(), null,
                WorldWebScenario.mediaConfig(), WorldWebScenario.interviewConfig(),
                WorldWebScenario.dialogueConfig(), WorldWebScenario.evidenceConfig(),
                WorldWebScenario.socialConfig(), WorldWebScenario.SOCIAL_BLOCK_MILLIS,
                contacts, engineeringConfigWithAcceptance(),
                WorldWebScenario.groupsConfig(), WorldWebScenario.groupsPlatformConfig(),
                WorldWebScenario.GROUPS_FEED_LIMIT, WorldWebScenario.GROUPS_READ_MILLIS,
                charters(), bankOpening());
    }

    /**
     * 组合档工程配置：在既有公司/合同之上，冻结 eng-a 的真实客户验收计划。
     *
     * <p>没有冻结客户计划时，工程完工后永远无法验收、开票与回款（世界不会替客户点头）。
     * 验收单位必须等于合同该期次的真实阶段单位：A1 的冻结阶段是 10/20/30，因此第 80 个
     * 实验日验收第 1 期 10 单位，第 150 个实验日验收第 2 期 20 单位。旧夹具写 40 单位
     * 与任何阶段都不相等，会被纯核以 {@code TRANCH_MISMATCH} 拒绝，看起来像“验收计划不生效”，
     * 实际是夹具与合同阶段对不上；这里按真实合同修正，不放宽任何验收规则。</p>
     *
     * @return 带冻结客户验收计划的工程配置
     */
    public static EngineeringRun.Config engineeringConfigWithAcceptance() {
        EngineeringRun.Config base = WorldWebScenario.engineeringConfig();
        java.util.Map<String, java.util.List<EngineeringRun.CustomerEventPlan>> events =
                new java.util.LinkedHashMap<>();
        events.put("eng-a", java.util.List.of(
                new EngineeringRun.CustomerEventPlan(
                        "exp-accept-a1", "eng-a", "A1", 0, "ACCEPT", 80L * 86_400_000L, 10L,
                        money("0.00"), "", "exp-accept-a1-ref"),
                new EngineeringRun.CustomerEventPlan(
                        "exp-accept-a1-t2", "eng-a", "A1", 1, "ACCEPT", 150L * 86_400_000L, 20L,
                        money("0.00"), "", "exp-accept-a1-t2-ref")));
        return new EngineeringRun.Config(base.paramSource(), base.companies(), events);
    }

    /** @return 制造业章程（cufe 五席，与群组档一致） */
    public static edu.cufe.auction.world.BoardModel.Charter cufeCharter() {
        List<edu.cufe.auction.world.BoardModel.Seat> seats = List.of(
                new edu.cufe.auction.world.BoardModel.Seat("s1", "alice"),
                new edu.cufe.auction.world.BoardModel.Seat("s2", "bob"),
                new edu.cufe.auction.world.BoardModel.Seat("s3", "carol"),
                new edu.cufe.auction.world.BoardModel.Seat("s4", "dave"),
                new edu.cufe.auction.world.BoardModel.Seat("s5", "erin"));
        return new edu.cufe.auction.world.BoardModel.Charter(WorldWebScenario.COMPANY, seats,
                "s1", edu.cufe.auction.world.BoardModel.QUORUM);
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
