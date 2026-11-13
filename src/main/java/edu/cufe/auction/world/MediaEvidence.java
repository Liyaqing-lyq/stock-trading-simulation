package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 采访私有证据的纯核：保存每稿每版的展示别名，以及"谁把哪份真实根身份、经哪份文档、给谁看"
 * 的最小绑定。
 *
 * <p>本类不持有时钟、工時、阅读记录或正文：共享时刻取共享文档该版本的 {@code publishedAt}，
 * 真实身份取根文档的 {@code publisherPartyId}，读到与否取 {@link InformationModel} 的观察记录。
 * 匿名别名只用于展示，不参与来源去重，也不得进入任何公开投影。</p>
 *
 * <p>它只处理"证据状态"这一分量；{@link MediaModel.State} 的形状与行为不变。</p>
 */
public final class MediaEvidence {

    /** 成功。 */
    public static final String REASON_OK = "OK";
    /** 分享者当前没有该媒体组织的采访权限。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 分享者没有真实读完该根（或版本不一致）。 */
    public static final String REASON_EVIDENCE_NOT_READ = "EVIDENCE_NOT_READ";
    /** 分享者当前已想不起该根。 */
    public static final String REASON_NOT_RECALLABLE = "NOT_RECALLABLE";
    /** 相同标识已存在且内容不同。 */
    public static final String REASON_ID_TAKEN = "ID_TAKEN";
    /** 根、接收者或媒体组织不合法。 */
    public static final String REASON_STATE = "STATE";

    private static final String DOC_PREFIX = "identity-share:";

    /**
     * 判断标识是否属于身份共享文档命名空间。
     *
     * @param docId 资料标识
     * @return 属于返回 true
     */
    public static boolean isShareDocument(String docId) {
        return docId != null && docId.startsWith(DOC_PREFIX);
    }

    private MediaEvidence() {
    }

    /**
     * 一条展示别名：同一稿同一版里每个真实根各自一条。
     *
     * @param storyId     稿件
     * @param revisionNo  提交次数
     * @param root        真实根（必须来自本版证据展开结果）
     * @param publicLabel 公开可见的标签文本
     */
    public record AliasBinding(String storyId, int revisionNo, MediaModel.Ref root,
                               String publicLabel) {

        /** 校验字段；标签非空，根非空。 */
        public AliasBinding {
            requireText(storyId, "稿件");
            Objects.requireNonNull(root, "别名根");
            requireText(publicLabel, "公开标签");
            if (revisionNo < 1) {
                throw new IllegalArgumentException("别名必须绑定到已提交版本：" + revisionNo);
            }
        }
    }

    /**
     * 身份共享的最小绑定；不含时间与身份字符串。
     *
     * @param shareId           共享标识（由分享者命名空间派生）
     * @param root              被共享的真实根
     * @param recipientPartyId  真实接收者
     * @param sharingDocId      实际承载身份说明的 DIRECT 文档
     * @param sharingVersion    该文档版本
     */
    public record IdentityShare(String shareId, MediaModel.Ref root, String recipientPartyId,
                                String sharingDocId, long sharingVersion) {

        /** 校验字段。 */
        public IdentityShare {
            requireText(shareId, "共享标识");
            Objects.requireNonNull(root, "共享根");
            requireText(recipientPartyId, "接收者");
            requireText(sharingDocId, "共享文档");
            if (sharingVersion < 1) {
                throw new IllegalArgumentException("共享文档版本必须从 1 开始：" + sharingVersion);
            }
        }
    }

    /**
     * 证据状态：只有别名与共享两类事实。
     *
     * @param aliases 别名，按稿/版本/根排序
     * @param shares  身份共享，按标识排序
     */
    public record State(List<AliasBinding> aliases, List<IdentityShare> shares) {

        /** 冻结并排序。 */
        public State {
            List<AliasBinding> aliasList = new ArrayList<>(aliases == null ? List.of() : aliases);
            aliasList.sort(java.util.Comparator.comparing(AliasBinding::storyId)
                    .thenComparingInt(AliasBinding::revisionNo)
                    .thenComparing(binding -> binding.root().docId())
                    .thenComparingLong(binding -> binding.root().version()));
            List<IdentityShare> shareList = new ArrayList<>(shares == null ? List.of() : shares);
            shareList.sort(java.util.Comparator.comparing(IdentityShare::shareId));
            Set<String> ids = new LinkedHashSet<>();
            for (IdentityShare share : shareList) {
                if (!ids.add(share.shareId())) {
                    throw new IllegalArgumentException("重复共享标识：" + share.shareId());
                }
            }
            aliases = List.copyOf(aliasList);
            shares = List.copyOf(shareList);
        }
    }

    /**
     * 共享请求。
     *
     * @param clientCommandId 分享者本侧的客户端标识
     * @param root            要共享的真实根
     * @param recipientPartyId 真实接收者
     * @param note            可选补充说明；身份本身由根文档 publisher 派生
     */
    public record ShareRequest(String clientCommandId, MediaModel.Ref root,
                               String recipientPartyId, String note, long nowMillis) {

        /** 校验字段。 */
        public ShareRequest {
            requireText(clientCommandId, "客户端命令标识");
            Objects.requireNonNull(root, "共享根");
            requireText(recipientPartyId, "接收者");
            if (note == null) {
                note = "";
            }
            if (nowMillis < 0) {
                throw new IllegalArgumentException("共享时刻不能为负：" + nowMillis);
            }
        }
    }

    /**
     * 共享结果：证据与信息状态同批返回。
     *
     * @param accepted    是否成功
     * @param reason      原因码
     * @param evidence    共享后的证据状态
     * @param information 共享后的信息状态
     */
    public record ShareOutcome(boolean accepted, String reason, State evidence,
                               InformationModel.State information) {
    }

    /**
     * 空证据状态。
     *
     * @return 无别名、无共享
     */
    public static State empty() {
        return new State(List.of(), List.of());
    }

    /**
     * 由分享者与客户端标识派生共享标识（长度前缀避免分隔符碰撞）。
     *
     * @param sharer          分享者
     * @param clientCommandId 客户端命令标识
     * @return 确定性标识
     */
    public static String shareId(String sharer, String clientCommandId) {
        requireText(sharer, "分享者");
        requireText(clientCommandId, "客户端命令标识");
        return sharer.length() + ":" + sharer + ":" + clientCommandId;
    }

    /**
     * 把本版别名追加到证据状态；同一 (稿, 版本, 根) 重复绑定视为替换。
     *
     * @param state      现有证据状态
     * @param storyId    稿件
     * @param revisionNo 本次提交次数
     * @param roots      本次证据展开出的全部根
     * @param aliases    待绑定别名
     * @return 追加后的状态
     * @throws IllegalArgumentException 别名不属于本版根、标签为空或版本不符
     */
    static State appendAliases(State state, String storyId, int revisionNo, List<MediaModel.Ref> roots,
                               List<AliasBinding> aliases) {
        State base = state == null ? empty() : state;
        if (aliases == null || aliases.isEmpty()) {
            return base;
        }
        List<AliasBinding> merged = new ArrayList<>(base.aliases());
        for (AliasBinding alias : aliases) {
            if (!alias.storyId().equals(storyId) || alias.revisionNo() != revisionNo) {
                throw new IllegalArgumentException("别名必须绑定本次提交：" + alias.storyId());
            }
            if (!roots.contains(alias.root())) {
                throw new IllegalArgumentException("别名根不在本版证据内：" + alias.root());
            }
            merged.removeIf(existing -> existing.storyId().equals(alias.storyId())
                    && existing.revisionNo() == alias.revisionNo()
                    && existing.root().equals(alias.root()));
            merged.add(alias);
        }
        return new State(merged, base.shares());
    }

    /**
     * 查询某个根在本稿本版的展示标签。
     *
     * @param state      证据状态
     * @param storyId    稿件
     * @param revisionNo 提交次数
     * @param root       真实根
     * @return 标签；未绑定返回 null
     */
    public static String labelOf(State state, String storyId, int revisionNo, MediaModel.Ref root) {
        if (state == null) {
            return null;
        }
        for (AliasBinding alias : state.aliases()) {
            if (alias.storyId().equals(storyId) && alias.revisionNo() == revisionNo
                    && alias.root().equals(root)) {
                return alias.publicLabel();
            }
        }
        return null;
    }

    /**
     * 某根的全部身份共享（管理审计用）。
     *
     * @param state 证据状态
     * @param root  真实根
     * @return 共享列表
     */
    public static List<IdentityShare> sharesOf(State state, MediaModel.Ref root) {
        List<IdentityShare> found = new ArrayList<>();
        if (state == null) {
            return found;
        }
        for (IdentityShare share : state.shares()) {
            if (share.root().equals(root)) {
                found.add(share);
            }
        }
        return List.copyOf(found);
    }

    /**
     * 记录一次身份共享，并按既有信息模型真实发布一份通用标题的 DIRECT 文档。
     *
     * <p>身份说明由根文档的 {@code publisherPartyId} 派生，不接受请求自报；分享者必须当前仍有
     * 该媒体组织的采访权限、真实读完该根且仍能回忆；未执笔正文，故不复制正文与阅读账。</p>
     *
     * @param evidence    现有证据状态
     * @param media       当前媒体状态（用于确认组织与冻结策略一致）
     * @param policy      冻结策略
     * @param access      当前授权
     * @param information 当前信息状态
     * @param sharer      分享者（受信上下文）
     * @param request     共享请求
     * @return 证据与信息的新状态；拒绝时两者与入参一致
     */
    public static ShareOutcome share(State evidence, MediaModel.State media, MediaModel.Policy policy,
                                     RoleAccess.State access, InformationModel.State information,
                                     String sharer, ShareRequest request) {
        Objects.requireNonNull(media, "媒体状态");
        Objects.requireNonNull(policy, "冻结策略");
        Objects.requireNonNull(access, "授权状态");
        Objects.requireNonNull(information, "信息状态");
        Objects.requireNonNull(request, "共享请求");
        requireText(sharer, "分享者");
        State base = evidence == null ? empty() : evidence;
        String shareId = shareId(sharer, request.clientCommandId());
        String expectedBody = derivedBody(information, base, request);
        for (IdentityShare existing : base.shares()) {
            if (!existing.shareId().equals(shareId)) {
                continue;
            }
            if (!existing.root().equals(request.root())
                    || !existing.recipientPartyId().equals(request.recipientPartyId())) {
                return reject(base, information, REASON_ID_TAKEN);
            }
            InformationModel.Document shared = documentOf(information, existing.sharingDocId());
            InformationModel.DocumentVersion version = shared == null ? null
                    : shared.at(existing.sharingVersion());
            if (version == null || expectedBody == null
                    || !shared.publisherPartyId().equals(sharer)
                    || !version.body().equals(expectedBody)) {
                return reject(base, information, REASON_ID_TAKEN);
            }
            // 同命令同内容：nowMillis 只是重试时点，不参与内容比较
            return new ShareOutcome(true, REASON_OK, base, information);
        }
        if (documentOf(information, DOC_PREFIX + shareId) != null) {
            // 已有同标识文档但无绑定：拒绝（STATE），不覆盖别人的旧文档、也不写 v2
            return reject(base, information, REASON_STATE);
        }
        if (!media.policy().equals(policy)) {
            return reject(base, information, REASON_STATE);
        }
        if (!information.persons().contains(request.recipientPartyId())
                || request.recipientPartyId().equals(sharer)) {
            return reject(base, information, REASON_STATE);
        }
        if (RoleAccess.authorize(access, sharer, RoleAccess.Action.WRITE_STORY,
                RoleAccess.Target.organization(policy.mediaOrganizationPartyId()),
                request.nowMillis()) != RoleAccess.Decision.ALLOW) {
            return reject(base, information, REASON_NOT_AUTHORIZED);
        }
        InformationModel.Document rootDoc = documentOf(information, request.root().docId());
        InformationModel.DocumentVersion version = rootDoc == null ? null
                : rootDoc.at(request.root().version());
        if (version == null) {
            return reject(base, information, REASON_STATE);
        }
        if (!InformationModel.canCite(information, sharer, request.root().docId(),
                request.root().version(), request.nowMillis())) {
            return reject(base, information, REASON_EVIDENCE_NOT_READ);
        }
        if (!recalled(information, sharer, request.root(), request.nowMillis(), policy)) {
            return reject(base, information, REASON_NOT_RECALLABLE);
        }
        if (expectedBody == null) {
            // 来源锚无法解析成真实根（缺绑定/成环/时间不合法）：直接拒绝，不写错误身份
            return reject(base, information, REASON_STATE);
        }
        String body = expectedBody;
        String docId = DOC_PREFIX + shareId;
        InformationModel.State next;
        try {
            next = InformationModel.publish(information, docId, sharer,
                    InformationModel.Audience.direct(request.recipientPartyId()),
                    new InformationModel.VersionDraft("身份共享材料", body, "",
                            request.nowMillis(), policy.draftReadMillis(),
                            List.of(request.root().docId() + "@" + request.root().version())));
        } catch (IllegalArgumentException rejected) {
            return reject(base, information, REASON_STATE);
        }
        List<IdentityShare> shares = new ArrayList<>(base.shares());
        shares.add(new IdentityShare(shareId, request.root(), request.recipientPartyId(), docId, 1));
        return new ShareOutcome(true, REASON_OK, new State(base.aliases(), shares), next);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 共享文档的派生正文：身份必须来自<b>解析后的真实根</b>的 publisher；note 只是附加说明。
     * 请求里的根可以是一份中转的共享文档，但正文不能因此把中间人写成来源。
     */
    private static String derivedBody(InformationModel.State information,
                                      MediaEvidence.State shares,
                                      MediaEvidence.ShareRequest request) {
        InformationModel.Document rootDoc = documentOf(information, request.root().docId());
        if (rootDoc == null || rootDoc.at(request.root().version()) == null) {
            return null;
        }
        MediaModel.Ref canonical;
        try {
            canonical = MediaModel.canonicalRoot(shares, information, request.root());
        } catch (IllegalArgumentException brokenChain) {
            return null;
        }
        InformationModel.Document canonicalDoc = documentOf(information, canonical.docId());
        if (canonicalDoc == null || canonicalDoc.at(canonical.version()) == null) {
            return null;
        }
        return "身份来源：" + canonicalDoc.publisherPartyId()
                + (request.note().isBlank() ? "" : "；说明：" + request.note());
    }

    private static boolean recalled(InformationModel.State information, String person,
                                    MediaModel.Ref root, long now, MediaModel.Policy policy) {
        CognitionRecall.Result result = CognitionRecall.recall(information, person, now,
                policy.recallPolicy());
        for (CognitionRecall.Item item : result.selected()) {
            if (item.observation().docId().equals(root.docId())
                    && item.observation().version() == root.version()) {
                return true;
            }
        }
        return false;
    }

    private static InformationModel.Document documentOf(InformationModel.State information,
                                                        String docId) {
        for (InformationModel.Document document : information.documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }

    private static ShareOutcome reject(State evidence, InformationModel.State information,
                                       String reason) {
        return new ShareOutcome(false, reason, evidence, information);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
