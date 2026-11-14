package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.BankOpeningRequest;

import java.util.List;
import java.util.Map;

/**
 * 打开分支子档所需的"可信运行画像"：父运行当初用于开局的那一套清单、开局现金、登记与冻结配置。
 *
 * <p>为什么要显式传：父档首帧只冻结了清单、开局现金、登记与部分模块配置；媒体、采访、证据、
 * 社交、工程、群组与平台的冻结配置并<b>不在</b>首帧里（这是既有实现的边界，不因分支而改变）。
 * 因此子档必须由同一份可信画像打开，父前缀才会与画像逐字节对上；画像不一致会在
 * {@code value.equals(firstCommit.commandContent())} 处被拒绝。</p>
 *
 * <p>刻意<b>不</b>包含 live 完成槽：每个运行必须有自己的进程内陆完成槽，跨运行共用一个槽会
 * 让旧回执在内存派发层命中新运行。打开子档时一律新建。</p>
 *
 * @param manifest         父运行清单（子清单只允许换 runId）
 * @param openings         开局现金清单
 * @param registry         开局登记；现金档案为 null
 * @param manufacturing    制造经营配置；旧档为 null
 * @param charter          董事会章程；旧档为 null
 * @param roles            任职配置；旧档为 null
 * @param information      资料阅读配置；旧档为 null
 * @param decisions        冻结决定配置；旧档为 null
 * @param media            媒体配置；旧档为 null
 * @param interview        采访配置；旧档为 null
 * @param dialogue         逐轮访谈配置；旧档为 null
 * @param evidence         证据配置；旧档为 null
 * @param social           社交配置；旧档为 null
 * @param socialBlockMillis 社交屏蔽工时；无社交模块为 0
 * @param contacts         社交联系入口；无社交模块为空表
 * @param engineering      工程配置；旧档为 null
 * @param groups           群组配置；旧档为 null
 * @param platform         平台写帖配置；旧档为 null
 * @param feedLimit        平台目录上限；旧档为 0
 * @param readMillis       群/平台帖阅读耗时；旧档为 0
 * @param bankOpening      银行开局申请（冻结档案 + 历史禀赋 + 托管明细）；非银行档为 null
 * @param companyCharters  额外公司治理冻结章程（按公司标识排序）；无该模块为空表
 */
public record BranchProfile(RunManifest manifest, List<CashLedger.OpeningAccount> openings,
                            OpeningRegistry registry, ManufacturingRun.Config manufacturing,
                            BoardModel.Charter charter, RoleRun.Config roles,
                            InformationRun.Config information, DecisionModel.Config decisions,
                            MediaRun.Config media, InterviewRun.Config interview,
                            InterviewDialogue.Config dialogue, EvidenceRun.Config evidence,
                            SocialMessageModel.Config social, long socialBlockMillis,
                            List<InterviewRun.ContactEntry> contacts,
                            EngineeringRun.Config engineering, GroupModel.Config groups,
                            PlatformPostModel.Config platform, int feedLimit, long readMillis,
                            BankOpeningRequest bankOpening,
                            Map<String, BoardModel.Charter> companyCharters) {

    /** 画像摘要口径版本 v2：原来那 20 个字段，摘要键集与字节不变。 */
    public static final String VERSION = "branch-profile-v2";

    /**
     * 画像摘要口径版本 v3：在 v2 的 20 个键之后追加 {@code bankOpening}、{@code companyCharters}。
     *
     * <p>只有清单显式启用银行或额外公司治理时才使用；旧实验条件一律用 v2，互不冒用。</p>
     */
    public static final String V3_VERSION = "branch-profile-v3";

    private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature
                            .ORDER_MAP_ENTRIES_BY_KEYS);

    /**
     * v2 兼容构造：不冻结银行与额外公司治理配置，等价于原 20 参构造。
     *
     * @param manifest         父运行清单
     * @param openings         开局现金清单
     * @param registry         开局登记
     * @param manufacturing    制造经营配置
     * @param charter          董事会章程
     * @param roles            任职配置
     * @param information      资料阅读配置
     * @param decisions        冻结决定配置
     * @param media            媒体配置
     * @param interview        采访配置
     * @param dialogue         逐轮访谈配置
     * @param evidence         证据配置
     * @param social           社交配置
     * @param socialBlockMillis 社交屏蔽工时
     * @param contacts         社交联系入口
     * @param engineering      工程配置
     * @param groups           群组配置
     * @param platform         平台写帖配置
     * @param feedLimit        平台目录上限
     * @param readMillis       阅读耗时
     */
    public BranchProfile(RunManifest manifest, List<CashLedger.OpeningAccount> openings,
                         OpeningRegistry registry, ManufacturingRun.Config manufacturing,
                         BoardModel.Charter charter, RoleRun.Config roles,
                         InformationRun.Config information, DecisionModel.Config decisions,
                         MediaRun.Config media, InterviewRun.Config interview,
                         InterviewDialogue.Config dialogue, EvidenceRun.Config evidence,
                         SocialMessageModel.Config social, long socialBlockMillis,
                         List<InterviewRun.ContactEntry> contacts,
                         EngineeringRun.Config engineering, GroupModel.Config groups,
                         PlatformPostModel.Config platform, int feedLimit, long readMillis) {
        this(manifest, openings, registry, manufacturing, charter, roles, information, decisions,
                media, interview, dialogue, evidence, social, socialBlockMillis, contacts,
                engineering, groups, platform, feedLimit, readMillis, null, Map.of());
    }

    /** 冻结开局清单与两份组合配置，并校验"配置与清单能力成对"。 */
    public BranchProfile {
        if (manifest == null) {
            throw new IllegalArgumentException("运行画像必须带父清单");
        }
        java.util.List<CashLedger.OpeningAccount> ordered = new java.util.ArrayList<>(
                openings == null ? java.util.List.of() : openings);
        ordered.sort(java.util.Comparator.comparing(CashLedger.OpeningAccount::accountId));
        openings = java.util.List.copyOf(ordered);
        contacts = List.copyOf(contacts == null ? List.of() : contacts);
        if (openings.isEmpty()) {
            throw new IllegalArgumentException("运行画像必须带开局现金");
        }
        java.util.Map<String, BoardModel.Charter> charters =
                new java.util.TreeMap<>(companyCharters == null ? Map.of() : companyCharters);
        for (java.util.Map.Entry<String, BoardModel.Charter> entry : charters.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("额外公司章程必须带公司标识与冻结章程");
            }
            if (!entry.getKey().equals(entry.getValue().companyPartyId())) {
                throw new IllegalArgumentException("额外公司章程键与章程公司不一致："
                        + entry.getKey());
            }
        }
        companyCharters = java.util.Collections.unmodifiableMap(charters);
        // 能力与配置必须成对：漏带、或多带都在这里拒绝；不按版本号/schema 号猜模块。
        if (manifest.hasBank() != (bankOpening != null)) {
            throw new IllegalArgumentException("运行画像的银行模块与开局申请必须成对："
                    + manifest.rulesVersion());
        }
        if (manifest.hasCompanyGovernance() != !charters.isEmpty()) {
            throw new IllegalArgumentException("运行画像的额外公司治理模块与章程必须成对："
                    + manifest.rulesVersion());
        }
    }

    /**
     * 该画像实际使用的摘要口径版本。
     *
     * <p>只由冻结能力决定：有银行申请或额外章程 ⇒ v3；否则 v2（含全部旧档）。
     * 这样旧档摘要保持逐字节不变，新组合档不会被只有 20 键的旧口径冒充。</p>
     *
     * @return {@link #VERSION} 或 {@link #V3_VERSION}
     */
    public String version() {
        return bankOpening != null || !companyCharters.isEmpty() ? V3_VERSION : VERSION;
    }

    /**
     * 是否为已知的画像口径版本。
     *
     * <p>{@link BranchOrigin} 自身没有清单，只能认版本字符串；能力与版本的交叉校验由
     * 运行层在打开子档时用清单再验一次。</p>
     *
     * @param version 版本字符串
     * @return 已知版本返回 true
     */
    public static boolean knownVersion(String version) {
        return VERSION.equals(version) || V3_VERSION.equals(version);
    }

    /**
     * 完整画像内容（含全部已启用模块配置）规范化 JSON 的 SHA-256。
     *
     * <p>为什么需要它：父档首帧只冻结清单、开局现金、登记与部分模块配置；媒体、采访、对话、
     * 证据、社交、工程、群组、平台的冻结配置不在首帧里。分支不能靠"调用方说是同一个画像"
     * 自证，所以把<b>可信宿主手里那一整套配置</b>摘要进分支元数据；子档打开时用宿主的画像
     * 重算摘要并与分支元数据和运行索引三方比对。</p>
     *
     * <p>v2 的键集与顺序与旧实现逐字相同（前 20 个键）；v3 只在末尾追加两个键，
     * 因此旧分支的摘要字节不受影响。</p>
     *
     * @return 小写十六进制摘要
     */
    public String digest() {
        return digest(this);
    }

    /**
     * 计算任意画像的摘要。
     *
     * @param profile 画像
     * @return 小写十六进制摘要
     */
    public static String digest(BranchProfile profile) {
        java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("manifest", profile.manifest());
        content.put("openings", profile.openings());
        content.put("registry", profile.registry());
        content.put("manufacturing", profile.manufacturing());
        content.put("charter", profile.charter());
        content.put("roles", profile.roles());
        content.put("information", profile.information());
        content.put("decisions", profile.decisions());
        content.put("media", profile.media());
        content.put("interview", profile.interview());
        content.put("dialogue", profile.dialogue());
        content.put("evidence", profile.evidence());
        content.put("social", profile.social());
        content.put("socialBlockMillis", profile.socialBlockMillis());
        content.put("contacts", profile.contacts());
        content.put("engineering", profile.engineering());
        content.put("groups", profile.groups());
        content.put("platform", profile.platform());
        content.put("feedLimit", profile.feedLimit());
        content.put("readMillis", profile.readMillis());
        if (V3_VERSION.equals(profile.version())) {
            content.put("bankOpening", profile.bankOpening());
            content.put("companyCharters", profile.companyCharters());
        }
        try {
            String json = CANONICAL.writeValueAsString(content);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("分支画像无法规范化摘要", failure);
        }
    }
}
