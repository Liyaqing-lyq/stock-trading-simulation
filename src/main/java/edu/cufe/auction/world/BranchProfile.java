package edu.cufe.auction.world;

import java.util.List;

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
                            PlatformPostModel.Config platform, int feedLimit, long readMillis) {

    /** 画像摘要口径版本；改变参与摘要的字段集合时必须升版，旧分支会因版本不支持而被拒。 */
    public static final String VERSION = "branch-profile-v2";

    private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature
                            .ORDER_MAP_ENTRIES_BY_KEYS);

    /** 冻结开局清单（按账户标识规范化排序，使画像摘要与清单顺序无关）。 */
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
    }

    /**
     * 完整画像内容（含全部已启用模块配置）规范化 JSON 的 SHA-256。
     *
     * <p>为什么需要它：父档首帧只冻结清单、开局现金、登记与部分模块配置；媒体、采访、对话、
     * 证据、社交、工程、群组、平台的冻结配置不在首帧里。分支不能靠"调用方说是同一个画像"
     * 自证，所以把<b>可信宿主手里那一整套配置</b>摘要进分支元数据；子档打开时用宿主的画像
     * 重算摘要并与分支元数据和运行索引三方比对。</p>
     *
     * <p>口径由 {@link #VERSION} 绑定；改了参与摘要的字段必须升版，否则旧分支会被误判。</p>
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
