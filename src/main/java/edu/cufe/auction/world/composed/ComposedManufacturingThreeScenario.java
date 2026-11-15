package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.CompanyOperationsRun;
import edu.cufe.auction.world.ManufacturingTemplateModel;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankingProfile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 三类制造公司组合档（{@code composed-manufacturing-three-v1}，EXP）：在既有 pharma Debug 组合
 * （制造、工程、银行、托管、研发、群平台）之上显式追加<b>三家真正独立的制造公司</b>。
 *
 * <p><b>不是改名</b>：cufe 工厂、north 治理公司、pharma 研发公司与 eng-a 工程公司全部原样保留；
 * 三家公司各有自己的财产主体、唯一经营账户、以 {@code MT1/MS1/MV1} 计的有限发行与持股、五席真实
 * 章程与周期状态，以及一名<b>非董事</b>经理候选人。开局<b>不预置经理、不预批预算</b>、不创建任何
 * 采购/批次/认证/扩产：一切都要走真实任免、真实会议与真实焦点工时。</p>
 *
 * <p><b>三份差异（沿已冻结的三模板，不新造纯核）</b></p>
 * <ul>
 *   <li>{@code mfg-tech}：两代产品与客户认证——代际 2 必须认证，认证计划按客户/产品/代际/轮次
 *       冻结，第一轮 FAIL、第二轮 PASS；旧代库存与已签合同不因换代消失。</li>
 *   <li>{@code mfg-struct}：产品客户结构与扩产——两个产品共用材料，供应商每期容量与客户预算
 *       都紧于产能；扩产先付款形成 CIP，建成后逐期爬坡。</li>
 *   <li>{@code mfg-versa}：双产品与用途验证——成熟产品免认证，新用途产品需要认证且只在
 *       第 2–3 期可签约。</li>
 * </ul>
 *
 * <p><b>金额口径</b>：三家公司的历史资本是注明的 EXP 假设，与工程/银行/制造旧账互不相干；
 * 所有现金账户都在唯一 {@link CashLedger} 账里，本类不复制任何余额。</p>
 */
public final class ComposedManufacturingThreeScenario {

    /** 运行标识（与 pharma 档分开）。 */
    public static final String RUN_ID = "web-composed-manufacturing-three";
    /** 数据版本 profile 值。 */
    public static final String PROFILE = RunManifest.RULES_COMPOSED_MANUFACTURING_THREE_V1;

    /** 公司一：技术换代 / 客户认证。 */
    public static final String TECH = "mfg-tech";
    /** 公司一财产主体。 */
    public static final String TECH_OWNER = "mfg-tech-owner";
    /** 公司一经营账户。 */
    public static final String TECH_CASH = "MFG-TECH-CASH";
    /** 公司一运营/人工账户。 */
    public static final String TECH_OPS = "MFG-TECH-OPS";
    /** 公司一运营账户财产主体（与经营账户分开登记，避免同一 owner 两个现金账户）。 */
    public static final String TECH_OPS_OWNER = "mfg-tech-ops";
    /** 公司一证券代码。 */
    public static final String TECH_SYMBOL = "MT1";
    /** 公司一发起人。 */
    public static final String TECH_FOUNDER = "mfg-tech-founder";
    /** 公司一发起人持股账户。 */
    public static final String TECH_SEC = "MFG-TECH-FOUNDER-SEC";

    /** 公司二：产品客户结构 / 扩产。 */
    public static final String STRUCT = "mfg-struct";
    /** 公司二财产主体。 */
    public static final String STRUCT_OWNER = "mfg-struct-owner";
    /** 公司二经营账户。 */
    public static final String STRUCT_CASH = "MFG-STRUCT-CASH";
    /** 公司二运营/人工账户。 */
    public static final String STRUCT_OPS = "MFG-STRUCT-OPS";
    /** 公司二运营账户财产主体。 */
    public static final String STRUCT_OPS_OWNER = "mfg-struct-ops";
    /** 公司二证券代码。 */
    public static final String STRUCT_SYMBOL = "MS1";
    /** 公司二发起人。 */
    public static final String STRUCT_FOUNDER = "mfg-struct-founder";
    /** 公司二发起人持股账户。 */
    public static final String STRUCT_SEC = "MFG-STRUCT-FOUNDER-SEC";

    /** 公司三：多产品 / 新用途验证。 */
    public static final String VERSA = "mfg-versa";
    /** 公司三财产主体。 */
    public static final String VERSA_OWNER = "mfg-versa-owner";
    /** 公司三经营账户。 */
    public static final String VERSA_CASH = "MFG-VERSA-CASH";
    /** 公司三运营/人工账户。 */
    public static final String VERSA_OPS = "MFG-VERSA-OPS";
    /** 公司三运营账户财产主体。 */
    public static final String VERSA_OPS_OWNER = "mfg-versa-ops";
    /** 公司三证券代码。 */
    public static final String VERSA_SYMBOL = "MV1";
    /** 公司三发起人。 */
    public static final String VERSA_FOUNDER = "mfg-versa-founder";
    /** 公司三发起人持股账户。 */
    public static final String VERSA_SEC = "MFG-VERSA-FOUNDER-SEC";

    /** 认证方账户（真实外部对手）。 */
    public static final String CERTIFIER = "EXT-CERTIFIER";
    /** 建设承包方账户。 */
    public static final String CONTRACTOR = "EXT-CONTRACTOR";
    /** 残余资产处置买方账户。 */
    public static final String SALVAGE = "EXT-SALVAGE";
    /** 金属供应商结算账户。 */
    public static final String SUP_METAL = "EXT-SUP-METAL";
    /** 树脂供应商结算账户。 */
    public static final String SUP_RESIN = "EXT-SUP-RESIN";
    /** 化工供应商结算账户。 */
    public static final String SUP_CHEM = "EXT-SUP-CHEM";
    /** 整车客户结算账户。 */
    public static final String CUST_AUTO = "EXT-CUST-AUTO";
    /** 建筑客户结算账户。 */
    public static final String CUST_BUILD = "EXT-CUST-BUILD";
    /** 公用事业客户历史经营积累 EXP（开局有限现金10000，不自动补足）。 */
    public static final String CUST_UTIL = "EXT-CUST-UTIL";
    /** 公用事业新用途客户结算账户（只在新用途需求窗口内可签约）。 */
    public static final String CUST_UTIL_NEW = "EXT-CUST-UTIL-NEW";

    /** 三家公司董事（各五席，稳定前缀 personId，与既有组合档人物不重名）。 */
    public static final List<String> TECH_DIRECTORS = List.of("mfgtech-d1", "mfgtech-d2",
            "mfgtech-d3", "mfgtech-d4", "mfgtech-d5");
    /** 公司二董事。 */
    public static final List<String> STRUCT_DIRECTORS = List.of("mfgstruct-d1", "mfgstruct-d2",
            "mfgstruct-d3", "mfgstruct-d4", "mfgstruct-d5");
    /** 公司三董事。 */
    public static final List<String> VERSA_DIRECTORS = List.of("mfgversa-d1", "mfgversa-d2",
            "mfgversa-d3", "mfgversa-d4", "mfgversa-d5");
    /** 公司一经理候选人（非董事；开局没有任职）。 */
    public static final String TECH_MANAGER = "mfgtech-mgr";
    /** 公司二经理候选人（非董事；开局没有任职）。 */
    public static final String STRUCT_MANAGER = "mfgstruct-mgr";
    /** 公司三经理候选人（非董事；开局没有任职）。 */
    public static final String VERSA_MANAGER = "mfgversa-mgr";

    /** 每家公司历史资本（EXP，注明假设，不是现实校准）。 */
    public static final String TECH_CAPITAL = "12000.00";
    /** 公司二历史资本。 */
    public static final String STRUCT_CAPITAL = "9000.00";
    /** 公司三历史资本。 */
    public static final String VERSA_CAPITAL = "7000.00";
    /** 股初始参考价/成本（EXP）。 */
    public static final String SHARE_PRICE = "10.00";

    /** 一天（模拟毫秒）。 */
    private static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;

    private ComposedManufacturingThreeScenario() {
    }

    /** @return pharma 档全部模块 + 显式 {@code company-operations}；资料版本三元组显式绑定 */
    public static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>(
                ComposedPharmaScenario.manifest().moduleVersions());
        modules.put("company-operations", 1);
        return new RunManifest(RUN_ID, 13L, RunManifest.RULES_COMPOSED_MANUFACTURING_THREE_V1,
                modules,
                Map.of(RunManifest.DATA_PROFILE, PROFILE,
                        RunManifest.DATA_OPERATIONS, RunManifest.OPERATIONS_VERSION,
                        RunManifest.DATA_TEMPLATES, RunManifest.TEMPLATES_VERSION,
                        RunManifest.DATA_COMPANIES,
                        String.join(",", List.of(STRUCT, TECH, VERSA))),
                RunManifest.LIVE_DECISIONS_V1);
    }

    /** @return pharma 原现金 + 三家经营账户 + 运营/持股账户 + 外部对手方账户 */
    public static List<CashLedger.OpeningAccount> openings() {
        List<CashLedger.OpeningAccount> accounts =
                new ArrayList<>(ComposedPharmaScenario.openings());
        accounts.add(new CashLedger.OpeningAccount(TECH_CASH, TECH_OWNER, money(TECH_CAPITAL),
                "三制造公司历史资本 EXP（公司一：技术换代/客户认证）"));
        accounts.add(new CashLedger.OpeningAccount(TECH_OPS, TECH_OPS_OWNER, money("0.00"),
                "公司一运营/人工账户（只由经营动作支付）"));
        accounts.add(new CashLedger.OpeningAccount(TECH_SEC, TECH_FOUNDER, money("0.00"),
                "公司一发起人持股账户"));
        accounts.add(new CashLedger.OpeningAccount(STRUCT_CASH, STRUCT_OWNER, money(STRUCT_CAPITAL),
                "三制造公司历史资本 EXP（公司二：产品结构/扩产）"));
        accounts.add(new CashLedger.OpeningAccount(STRUCT_OPS, STRUCT_OPS_OWNER, money("0.00"),
                "公司二运营/人工账户（只由经营动作支付）"));
        accounts.add(new CashLedger.OpeningAccount(STRUCT_SEC, STRUCT_FOUNDER, money("0.00"),
                "公司二发起人持股账户"));
        accounts.add(new CashLedger.OpeningAccount(VERSA_CASH, VERSA_OWNER, money(VERSA_CAPITAL),
                "三制造公司历史资本 EXP（公司三：多产品/用途验证）"));
        accounts.add(new CashLedger.OpeningAccount(VERSA_OPS, VERSA_OPS_OWNER, money("0.00"),
                "公司三运营/人工账户（只由经营动作支付）"));
        accounts.add(new CashLedger.OpeningAccount(VERSA_SEC, VERSA_FOUNDER, money("0.00"),
                "公司三发起人持股账户"));
        accounts.add(new CashLedger.OpeningAccount(CERTIFIER, "certifier-agency", money("0.00"),
                "外部认证方账户（只收认证费）"));
        accounts.add(new CashLedger.OpeningAccount(CONTRACTOR, "builder-co", money("0.00"),
                "外部建设承包方账户（只收扩产款）"));
        accounts.add(new CashLedger.OpeningAccount(SALVAGE, "scrap-dealer", money("0.00"),
                "外部残余资产买方账户（有限付款能力）"));
        accounts.add(new CashLedger.OpeningAccount(SUP_METAL, "sup-metal", money("0.00"),
                "金属材料供应商结算账户"));
        accounts.add(new CashLedger.OpeningAccount(SUP_RESIN, "sup-resin", money("0.00"),
                "树脂材料供应商结算账户"));
        accounts.add(new CashLedger.OpeningAccount(SUP_CHEM, "sup-chem", money("0.00"),
                "化工材料供应商结算账户"));
        accounts.add(new CashLedger.OpeningAccount(CUST_AUTO, "auto-oem", money("10000.00"),
                "整车客户历史经营积累 EXP（开局有限现金10000，不自动补足）"));
        accounts.add(new CashLedger.OpeningAccount(CUST_BUILD, "builder-a", money("10000.00"),
                "建筑客户历史经营积累 EXP（开局有限现金10000，不自动补足）"));
        accounts.add(new CashLedger.OpeningAccount(CUST_UTIL, "utility-x", money("10000.00"),
                "公用事业客户历史经营积累 EXP（开局有限现金10000，不自动补足）"));
        accounts.add(new CashLedger.OpeningAccount(CUST_UTIL_NEW, "utility-new", money("10000.00"),
                "公用事业新用途客户历史经营积累 EXP（开局有限现金10000，不自动补足）"));
        return List.copyOf(accounts);
    }

    /** @return pharma 原登记 + 三家组织/主体/发起人 + 十五名董事 + 三名经理候选人 + 三只股票 */
    public static OpeningRegistry registry() {
        OpeningRegistry base = ComposedPharmaScenario.registry();
        List<OpeningRegistry.Party> parties = new ArrayList<>(base.parties());
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(base.owners());
        List<OpeningRegistry.Security> securities = new ArrayList<>(base.securities());
        List<OpeningRegistry.ShareAllocation> allocations = new ArrayList<>(base.allocations());
        Set<String> existing = new LinkedHashSet<>();
        for (OpeningRegistry.Party party : base.parties()) {
            existing.add(party.partyId());
        }
        for (String[] company : new String[][]{
                {TECH, TECH_OWNER, TECH_FOUNDER, "三制造公司一：技术换代/客户认证"},
                {STRUCT, STRUCT_OWNER, STRUCT_FOUNDER, "三制造公司二：产品结构/扩产"},
                {VERSA, VERSA_OWNER, VERSA_FOUNDER, "三制造公司三：多产品/用途验证"}}) {
            if (existing.add(company[0])) {
                parties.add(new OpeningRegistry.Party(company[0],
                        OpeningRegistry.PartyKind.ORGANIZATION, company[3]));
            }
            if (existing.add(company[1])) {
                parties.add(new OpeningRegistry.Party(company[1],
                        OpeningRegistry.PartyKind.ORGANIZATION, company[3] + " 财产主体"));
                owners.add(new OpeningRegistry.PropertyOwner(company[1],
                        OpeningRegistry.OwnerKind.ORGANIZATION, company[0]));
            }
            String opsOwner = company[0] + "-ops";
            if (existing.add(opsOwner)) {
                parties.add(new OpeningRegistry.Party(opsOwner,
                        OpeningRegistry.PartyKind.ORGANIZATION, company[3] + " 运营主体"));
                owners.add(new OpeningRegistry.PropertyOwner(opsOwner,
                        OpeningRegistry.OwnerKind.ORGANIZATION, opsOwner));
            }
            if (existing.add(company[2])) {
                parties.add(new OpeningRegistry.Party(company[2],
                        OpeningRegistry.PartyKind.ORGANIZATION, company[3] + " 发起人"));
                owners.add(new OpeningRegistry.PropertyOwner(company[2],
                        OpeningRegistry.OwnerKind.ORGANIZATION, company[2]));
            }
        }
        for (String[] external : new String[][]{
                {"certifier-agency", "外部认证方"}, {"builder-co", "外部建设承包方"},
                {"scrap-dealer", "外部残值买方"}, {"sup-metal", "金属材料供应商"},
                {"sup-resin", "树脂材料供应商"}, {"sup-chem", "化工材料供应商"},
                {"auto-oem", "整车客户"}, {"builder-a", "建筑客户"},
                {"utility-x", "公用事业客户"}, {"utility-new", "公用事业新用途客户"}}) {
            if (existing.add(external[0])) {
                parties.add(new OpeningRegistry.Party(external[0],
                        OpeningRegistry.PartyKind.ORGANIZATION, external[1]));
                owners.add(new OpeningRegistry.PropertyOwner(external[0],
                        OpeningRegistry.OwnerKind.ORGANIZATION, external[0]));
            }
        }
        for (List<String> directors : List.of(TECH_DIRECTORS, STRUCT_DIRECTORS, VERSA_DIRECTORS)) {
            for (String director : directors) {
                if (existing.add(director)) {
                    parties.add(new OpeningRegistry.Party(director,
                            OpeningRegistry.PartyKind.PERSON, "三制造公司董事 " + director));
                }
            }
        }
        for (String manager : List.of(TECH_MANAGER, STRUCT_MANAGER, VERSA_MANAGER)) {
            if (existing.add(manager)) {
                parties.add(new OpeningRegistry.Party(manager, OpeningRegistry.PartyKind.PERSON,
                        "三制造公司经理候选人 " + manager));
            }
        }
        securities.add(new OpeningRegistry.Security(TECH_SYMBOL, TECH, 300L, money(SHARE_PRICE)));
        securities.add(new OpeningRegistry.Security(STRUCT_SYMBOL, STRUCT, 200L,
                money(SHARE_PRICE)));
        securities.add(new OpeningRegistry.Security(VERSA_SYMBOL, VERSA, 150L,
                money(SHARE_PRICE)));
        allocations.add(new OpeningRegistry.ShareAllocation(TECH_SEC, TECH_SYMBOL, 300L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        allocations.add(new OpeningRegistry.ShareAllocation(STRUCT_SEC, STRUCT_SYMBOL, 200L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        allocations.add(new OpeningRegistry.ShareAllocation(VERSA_SEC, VERSA_SYMBOL, 150L,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }

    /** @return 银行档案在原组合之上显式登记三家公司与全部外部对手（无存款科目） */
    public static BankOpeningRequest bankOpening() {
        BankingProfile base = ComposedPharmaScenario.bankOpening().config().profile();
        Map<String, BankingProfile.PartyRef> parties = new TreeMap<>(base.parties());
        parties.put("mfg-tech", new BankingProfile.PartyRef(TECH, TECH_OWNER, TECH_CASH, null));
        parties.put("mfg-tech-ops", new BankingProfile.PartyRef(TECH_OPS_OWNER, TECH_OPS_OWNER,
                TECH_OPS, null));
        parties.put("mfg-tech-founder", new BankingProfile.PartyRef(TECH_FOUNDER, TECH_FOUNDER,
                TECH_SEC, null));
        parties.put("mfg-struct", new BankingProfile.PartyRef(STRUCT, STRUCT_OWNER, STRUCT_CASH,
                null));
        parties.put("mfg-struct-ops", new BankingProfile.PartyRef(STRUCT_OPS_OWNER,
                STRUCT_OPS_OWNER, STRUCT_OPS, null));
        parties.put("mfg-struct-founder", new BankingProfile.PartyRef(STRUCT_FOUNDER,
                STRUCT_FOUNDER, STRUCT_SEC, null));
        parties.put("mfg-versa", new BankingProfile.PartyRef(VERSA, VERSA_OWNER, VERSA_CASH, null));
        parties.put("mfg-versa-ops", new BankingProfile.PartyRef(VERSA_OPS_OWNER,
                VERSA_OPS_OWNER, VERSA_OPS, null));
        parties.put("mfg-versa-founder", new BankingProfile.PartyRef(VERSA_FOUNDER, VERSA_FOUNDER,
                VERSA_SEC, null));
        parties.put("certifier-agency", new BankingProfile.PartyRef("certifier-agency",
                "certifier-agency", CERTIFIER, null));
        parties.put("builder-co", new BankingProfile.PartyRef("builder-co", "builder-co",
                CONTRACTOR, null));
        parties.put("scrap-dealer", new BankingProfile.PartyRef("scrap-dealer", "scrap-dealer",
                SALVAGE, null));
        parties.put("sup-metal", new BankingProfile.PartyRef("sup-metal", "sup-metal", SUP_METAL,
                null));
        parties.put("sup-resin", new BankingProfile.PartyRef("sup-resin", "sup-resin", SUP_RESIN,
                null));
        parties.put("sup-chem", new BankingProfile.PartyRef("sup-chem", "sup-chem", SUP_CHEM,
                null));
        parties.put("auto-oem", new BankingProfile.PartyRef("auto-oem", "auto-oem", CUST_AUTO,
                null));
        parties.put("builder-a", new BankingProfile.PartyRef("builder-a", "builder-a", CUST_BUILD,
                null));
        parties.put("utility-x", new BankingProfile.PartyRef("utility-x", "utility-x", CUST_UTIL,
                null));
        parties.put("utility-new", new BankingProfile.PartyRef("utility-new", "utility-new",
                CUST_UTIL_NEW, null));
        BankingProfile profile = new BankingProfile(base.banks(), parties, base.custodians(),
                base.buckets(), base.groups(), base.loanAccounts(), base.llpAccounts(),
                base.depositAccounts(), base.providerKey(), base.subordinatedOwnerKey(),
                base.backgroundBorrowerKey());
        BankOpening.DeclaredEndowment endowment = ComposedWorldScenario.endowment();
        return BankOpeningRequest.of(ComposedWorldScenario.config(profile), endowment, Map.of(),
                Map.of());
    }

    /** @return north + pharma + 三家公司章程（各五席；开局没有经理任职、没有预算） */
    public static Map<String, BoardModel.Charter> charters() {
        Map<String, BoardModel.Charter> charters = new TreeMap<>(ComposedPharmaScenario.charters());
        charters.put(TECH, charter(TECH, TECH_DIRECTORS, "t"));
        charters.put(STRUCT, charter(STRUCT, STRUCT_DIRECTORS, "u"));
        charters.put(VERSA, charter(VERSA, VERSA_DIRECTORS, "v"));
        return Map.copyOf(charters);
    }

    private static BoardModel.Charter charter(String company, List<String> directors,
                                              String seatPrefix) {
        List<BoardModel.Seat> seats = new ArrayList<>();
        for (int i = 0; i < directors.size(); i++) {
            seats.add(new BoardModel.Seat(seatPrefix + (i + 1), directors.get(i)));
        }
        return new BoardModel.Charter(company, seats, seatPrefix + "1", BoardModel.QUORUM);
    }

    /** @return 三家公司董事与经理候选人（都必须能真实读到本公司材料） */
    public static List<String> readers() {
        List<String> readers = new ArrayList<>(ComposedPharmaScenario.readers());
        readers.addAll(TECH_DIRECTORS);
        readers.addAll(STRUCT_DIRECTORS);
        readers.addAll(VERSA_DIRECTORS);
        readers.add(TECH_MANAGER);
        readers.add(STRUCT_MANAGER);
        readers.add(VERSA_MANAGER);
        return List.copyOf(readers);
    }

    /** @return 三家公司的纯核开局配置（模板规格 + 账户绑定） */
    public static List<CompanyOperationsRun.Config> companies() {
        List<CompanyOperationsRun.Config> configs = new ArrayList<>();
        configs.add(new CompanyOperationsRun.Config(TECH, TECH_SYMBOL, techSpec(),
                new ManufacturingTemplateModel.Accounts(TECH_OWNER, TECH_CASH, TECH_OPS, CERTIFIER,
                        CONTRACTOR, SALVAGE, Map.of("auto-oem", CUST_AUTO),
                        Map.of("sup-metal", SUP_METAL))));
        configs.add(new CompanyOperationsRun.Config(STRUCT, STRUCT_SYMBOL, structSpec(),
                new ManufacturingTemplateModel.Accounts(STRUCT_OWNER, STRUCT_CASH, STRUCT_OPS,
                        CERTIFIER, CONTRACTOR, SALVAGE, Map.of("builder-a", CUST_BUILD),
                        Map.of("sup-resin", SUP_RESIN)),
                new CompanyOperationsRun.ExpansionOffer(money("2000.00"), 30L * DAY, 3, 50L, false)));
        configs.add(new CompanyOperationsRun.Config(VERSA, VERSA_SYMBOL, versaSpec(),
                new ManufacturingTemplateModel.Accounts(VERSA_OWNER, VERSA_CASH, VERSA_OPS,
                        CERTIFIER, CONTRACTOR, SALVAGE,
                        Map.of("utility-x", CUST_UTIL, "utility-new", CUST_UTIL_NEW),
                        Map.of("sup-chem", SUP_CHEM))));
        return List.copyOf(configs);
    }

    /**
     * 公司一：技术换代 + 客户认证。
     *
     * <p>代际 1 免认证、代际 2 必须认证；认证计划第一轮 FAIL、第二轮 PASS（同一客户/产品/代际
     * 不重抽）。基础产能 20 件/期，扩产 3000.00 / 60 日 / 爬坡 2 期 / +40 件。</p>
     */
    public static ManufacturingTemplateModel.TemplateSpec techSpec() {
        List<ManufacturingTemplateModel.ProductSpec> products = List.of(
                new ManufacturingTemplateModel.ProductSpec("P1", 1, Map.of("MAT-A", 2L),
                        money("4.00"), 3L * DAY, false),
                new ManufacturingTemplateModel.ProductSpec("P1", 2, Map.of("MAT-A", 2L,
                        "MAT-B", 1L), money("5.00"), 3L * DAY, true));
        List<ManufacturingTemplateModel.CustomerSpec> customers = List.of(
                new ManufacturingTemplateModel.CustomerSpec("auto-oem", 30L, null,
                        money("4000.00")));
        List<ManufacturingTemplateModel.MaterialQuote> quotes = List.of(
                new ManufacturingTemplateModel.MaterialQuote("MAT-A", "sup-metal", 0L, 0L,
                        3650L * DAY, money("12.00"), 60L),
                new ManufacturingTemplateModel.MaterialQuote("MAT-B", "sup-metal", 0L, 0L,
                        3650L * DAY, money("18.00"), 40L));
        List<ManufacturingTemplateModel.CertPlan> plans = List.of(
                new ManufacturingTemplateModel.CertPlan("auto-oem", "P1", 2, 1, false, 20L * DAY),
                new ManufacturingTemplateModel.CertPlan("auto-oem", "P1", 2, 2, true, 40L * DAY));
        return new ManufacturingTemplateModel.TemplateSpec("tech-generation", products, customers,
                quotes, plans, 20L, money("160.00"), money("50.00"), money("20.00"),
                money("200.00"), DAY, 90L * DAY, money("0.30"), 30L * DAY);
    }

    /**
     * 公司二：产品客户结构 + 扩产。
     *
     * <p>两个产品共用树脂材料；供应商每期只给 6 件、客户每期预算 1000.00，两者都紧于产能，
     * 因此在产能之前就会先触发供应/预算约束。基础产能 100，扩产 2000.00 / 30 日 / 爬坡 3 期 /
     * +50 件。</p>
     */
    public static ManufacturingTemplateModel.TemplateSpec structSpec() {
        List<ManufacturingTemplateModel.ProductSpec> products = List.of(
                new ManufacturingTemplateModel.ProductSpec("P1", 1, Map.of("MAT-C", 2L),
                        money("6.00"), 4L * DAY, false),
                new ManufacturingTemplateModel.ProductSpec("P2", 1, Map.of("MAT-C", 1L,
                        "MAT-D", 2L), money("8.00"), 5L * DAY, false));
        List<ManufacturingTemplateModel.CustomerSpec> customers = List.of(
                new ManufacturingTemplateModel.CustomerSpec("builder-a", 50L, null,
                        money("1000.00")));
        List<ManufacturingTemplateModel.MaterialQuote> quotes = List.of(
                new ManufacturingTemplateModel.MaterialQuote("MAT-C", "sup-resin", 0L, 0L,
                        3650L * DAY, money("20.00"), 6L),
                new ManufacturingTemplateModel.MaterialQuote("MAT-D", "sup-resin", 0L, 0L,
                        3650L * DAY, money("8.00"), 10L));
        return new ManufacturingTemplateModel.TemplateSpec("struct-expansion", products, customers,
                quotes, List.of(), 100L, money("120.00"), money("80.00"), money("25.00"),
                money("0.00"), 0L, 0L, money("0.40"), 30L * DAY);
    }

    /**
     * 公司三：双产品 + 新用途验证。
     *
     * <p>成熟产品免认证；新用途产品需要认证且需求窗口只在第 2–3 期。基础产能 30 件/期。</p>
     */
    public static ManufacturingTemplateModel.TemplateSpec versaSpec() {
        List<ManufacturingTemplateModel.ProductSpec> products = List.of(
                new ManufacturingTemplateModel.ProductSpec("MAT-P", 1, Map.of("MAT-E", 1L),
                        money("3.00"), 2L * DAY, false),
                new ManufacturingTemplateModel.ProductSpec("NEW-P", 1, Map.of("MAT-E", 1L,
                        "MAT-F", 1L), money("5.00"), 3L * DAY, true));
        List<ManufacturingTemplateModel.CustomerSpec> customers = List.of(
                new ManufacturingTemplateModel.CustomerSpec("utility-x", 20L, null,
                        money("2000.00")),
                new ManufacturingTemplateModel.CustomerSpec("utility-new", 10L, Set.of(2L, 3L),
                        money("900.00")));
        List<ManufacturingTemplateModel.MaterialQuote> quotes = List.of(
                new ManufacturingTemplateModel.MaterialQuote("MAT-E", "sup-chem", 0L, 0L,
                        3650L * DAY, money("15.00"), 50L),
                new ManufacturingTemplateModel.MaterialQuote("MAT-F", "sup-chem", 0L, 0L,
                        3650L * DAY, money("25.00"), 20L));
        List<ManufacturingTemplateModel.CertPlan> plans = List.of(
                new ManufacturingTemplateModel.CertPlan("utility-new", "NEW-P", 1, 1, true,
                        25L * DAY));
        return new ManufacturingTemplateModel.TemplateSpec("versa-newuse", products, customers,
                quotes, plans, 30L, money("90.00"), money("30.00"), money("10.00"),
                money("150.00"), 12L * 3_600_000L, 60L * DAY, money("0.25"), 30L * DAY);
    }

    /**
     * 打开三制造公司组合档：首次创建时显式初始化 pharma 研发与三家公司经营；不预置经理或预算。
     *
     * @param journal 独立日志文件
     * @return 唯一权威运行
     * @throws Exception 开局或恢复验证失败
     */
    public static WorldRuntime open(Path journal) throws Exception {
        return open(journal, 0);
    }

    /**
     * 新可运行档：真实开局资料、额外公司治理投影及零余额银行存款户，旧档不迁移。
     * @param journal 独立日志
     * @return 唯一世界运行器
     * @throws Exception 开局或恢复失败
     */
    public static WorldRuntime openRunnable(Path journal) throws Exception {
        return open(journal, 1);
    }

    /** 显式新档：三制造模型经营工具和实际董事/经理预算；不迁移旧档。 */
    public static WorldRuntime openAutonomous(Path journal) throws Exception {
        return open(journal, 2);
    }

    private static WorldRuntime open(Path journal, int version) throws Exception {
        boolean runnable = version > 0;
        RunManifest original = manifest();
        Map<String, String> data = new TreeMap<>(original.dataVersions());
        var decisions = runnable ? ComposedPharmaScenario.debugDecisions() : WorldWebScenario.groupsDecisionConfig();
        if (version == 2) decisions = edu.cufe.auction.world.CompanyOperationsDecisionTools.config(decisions, readers());
        BankOpeningRequest banking = bankOpening();
        if (runnable) {
            data.put("profile", "manufacturing-runnable-v" + version);
            if (version == 2) data.put("operations-decision", "v1");
            data.put("operating-observation", "v2");
            data.put("role-account-projection", "authorized-v1");
            data.put("company-decision", "v1");
            data.put("company-introduction", "v1");
            var base = banking.config().profile();
            Map<String, BankingProfile.PartyRef> parties = new TreeMap<>(base.parties());
            List<String> deposits = new ArrayList<>(base.depositAccounts());
            for (String company : List.of(TECH, STRUCT, VERSA, ComposedPharmaScenario.PHARMA)) {
                var party = parties.get(company);
                String account = "DEPOSIT-" + company;
                parties.put(company, new BankingProfile.PartyRef(party.partyId(), party.ownerId(),
                        party.cashAccountId(), account));
                deposits.add(account);
            }
            var profile = new BankingProfile(base.banks(), parties, base.custodians(), base.buckets(),
                    base.groups(), base.loanAccounts(), base.llpAccounts(), deposits, base.providerKey(),
                    base.subordinatedOwnerKey(), base.backgroundBorrowerKey());
            banking = BankOpeningRequest.of(ComposedWorldScenario.config(profile),
                    ComposedWorldScenario.endowment(), Map.of(), Map.of());
        }
        RunManifest selected = runnable ? new RunManifest("web-manufacturing-runnable-v" + version, original.seed(),
                original.rulesVersion(), original.moduleVersions(), data, original.modelConfigVersion()) : original;
        boolean fresh = !Files.exists(journal) || Files.size(journal) == 0L;
        WorldRuntime world = ComposedWorldScenario.open(journal, selected, openings(), registry(),
                banking, decisions, extras());
        try {
            if (fresh) {
                world.initializeResearch("bootstrap", ComposedPharmaScenario.PHARMA,
                        new ResearchModel.Config(ComposedPharmaScenario.PHARMA_OWNER,
                                ComposedPharmaScenario.PHARMA_CASH, BigDecimal.ZERO),
                        WorldWebScenario.researchProjects(), 1);
                world.initializeCompanyOperations("bootstrap", companies());
                if (runnable) CompanyOpeningDocuments.publish(world, Map.of(
                        TECH, List.of(TECH_MANAGER), STRUCT, List.of(STRUCT_MANAGER),
                        VERSA, List.of(VERSA_MANAGER), ComposedPharmaScenario.PHARMA,
                        List.of(ComposedPharmaScenario.MANAGER_CANDIDATE)));
            }
            return world;
        } catch (Exception failure) {
            world.close();
            throw failure;
        }
    }

    /** @return 显式扩展：四份章程与全部读者；不追加任何初始经理任职 */
    static ComposedWorldScenario.Extras extras() {
        Map<String, BoardModel.Charter> charters = new LinkedHashMap<>(charters());
        return new ComposedWorldScenario.Extras(charters, List.of(), readers());
    }

    /** 金额（2 位小数）。 */
    private static BigDecimal money(String value) {
        return ComposedWorldScenario.money(value);
    }
}
