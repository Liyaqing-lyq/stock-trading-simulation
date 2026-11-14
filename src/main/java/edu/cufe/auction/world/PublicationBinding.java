package edu.cufe.auction.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 发布绑定校验：把"世界真实发布的这一帧"与帖子/平台版本的冻结元数据对上。
 *
 * <p><b>信任方向</b>：本类只接受两样东西——调用方从唯一 {@link InformationModel} 读出的
 * {@link PublishedDocument} 视图，以及本模块持有的版本记录。它从不接受调用方传入 roots、也不反推
 * 直接来源；roots 与 declaredRefs 只从记录里取。因此模型或上层代码不能靠"自己填一份来源"绕过校验。</p>
 *
 * <p><b>边界</b>：纯核没有标题/正文，{@code fromJson} 也不会凭空看见 InformationModel；这些检查只
 * 在真实发布帧（同一世界提交内）由 world/adapter 调用。本类不写状态、不读墙钟、不做 I/O。</p>
 */
public final class PublicationBinding {

    /** 绑定通过。 */
    public static final String OK = "OK";
    /** 文档标识不符。 */
    public static final String DOC_MISMATCH = "DOC_MISMATCH";
    /** 版本号不符。 */
    public static final String VERSION_MISMATCH = "VERSION_MISMATCH";
    /** 发布者不是记录作者。 */
    public static final String PUBLISHER_MISMATCH = "PUBLISHER_MISMATCH";
    /** 受众种类或对象不是记录要求的受众。 */
    public static final String AUDIENCE_MISMATCH = "AUDIENCE_MISMATCH";
    /** 发布时刻不是记录冻结的计划时刻。 */
    public static final String TIME_MISMATCH = "TIME_MISMATCH";
    /** 用记录的直接来源与真实正文复算的摘要不等于记录摘要。 */
    public static final String DIGEST_MISMATCH = "DIGEST_MISMATCH";

    private PublicationBinding() {
    }

    /**
     * 世界真实发布的一版资料视图；由调用方从 InformationModel 读出，不由模型提供。
     *
     * @param docId            文档
     * @param version          版本
     * @param publisherPartyId 发布者
     * @param audienceKind     受众种类名
     * @param audiencePartyId  受众对象；PUBLIC 为空串
     * @param title            标题
     * @param body             正文
     * @param publishedAt      真实发布时刻
     */
    public record PublishedDocument(String docId, long version, String publisherPartyId,
                                    String audienceKind, String audiencePartyId, String title,
                                    String body, long publishedAt) {

        /** 校验字段。 */
        public PublishedDocument {
            Objects.requireNonNull(docId, "文档标识");
            Objects.requireNonNull(publisherPartyId, "发布者");
            Objects.requireNonNull(audienceKind, "受众种类");
            audiencePartyId = audiencePartyId == null ? "" : audiencePartyId;
            Objects.requireNonNull(title, "标题");
            Objects.requireNonNull(body, "正文");
            if (version < 1) {
                throw new IllegalArgumentException("已发布版本必须从 1 开始");
            }
            if (publishedAt < 0) {
                throw new IllegalArgumentException("发布时刻不能为负");
            }
        }
    }

    /**
     * 从唯一信息状态构造发布视图。
     *
     * @param information 信息状态
     * @param docId       文档
     * @param version     版本
     * @return 视图；文档或版本不存在时为空
     */
    public static Optional<PublishedDocument> fromInformation(InformationModel.State information,
                                                              String docId, long version) {
        Objects.requireNonNull(information, "信息状态");
        Objects.requireNonNull(docId, "文档标识");
        for (InformationModel.Document document : information.documents()) {
            if (!document.docId().equals(docId)) {
                continue;
            }
            for (InformationModel.DocumentVersion candidate : document.versions()) {
                if (candidate.version() != version) {
                    continue;
                }
                return Optional.of(new PublishedDocument(docId, version,
                        document.publisherPartyId(), document.audience().kind().name(),
                        document.audience().partyId(), candidate.title(), candidate.body(),
                        candidate.publishedAt()));
            }
        }
        return Optional.empty();
    }

    /**
     * 校验群帖首版与真实发布一致。
     *
     * @param post 群帖元数据
     * @param doc  真实发布视图
     * @return 结果码
     */
    public static String verify(GroupModel.Post post, PublishedDocument doc) {
        Objects.requireNonNull(post, "帖子");
        if (!post.docId().equals(doc.docId())) {
            return DOC_MISMATCH;
        }
        if (doc.version() != 1) {
            return VERSION_MISMATCH;
        }
        if (!"GROUP".equals(doc.audienceKind())
                || !post.groupId().equals(doc.audiencePartyId())) {
            return AUDIENCE_MISMATCH;
        }
        if (!post.author().equals(doc.publisherPartyId())) {
            return PUBLISHER_MISMATCH;
        }
        if (doc.publishedAt() != post.publishedAt()) {
            return TIME_MISMATCH;
        }
        String recomputed = GroupModel.postDigest(doc.publisherPartyId(), post.groupId(),
                doc.title(), doc.body(), post.declaredRefs());
        return recomputed.equals(post.contentDigest()) ? OK : DIGEST_MISMATCH;
    }

    /**
     * 校验群更正版本与真实发布一致。
     *
     * @param version 更正元数据
     * @param doc     真实发布视图
     * @return 结果码
     */
    public static String verify(GroupModel.PostVersion version, PublishedDocument doc) {
        Objects.requireNonNull(version, "更正版本");
        if (!version.docId().equals(doc.docId())) {
            return DOC_MISMATCH;
        }
        if (doc.version() != version.version()) {
            return VERSION_MISMATCH;
        }
        if (!"GROUP".equals(doc.audienceKind())
                || !version.groupId().equals(doc.audiencePartyId())) {
            return AUDIENCE_MISMATCH;
        }
        if (!version.author().equals(doc.publisherPartyId())) {
            return PUBLISHER_MISMATCH;
        }
        if (doc.publishedAt() != version.publishedAt()) {
            return TIME_MISMATCH;
        }
        String recomputed = GroupModel.versionDigest("POST_VERSION", doc.publisherPartyId(),
                version.groupId(), version.version(), version.basedOnVersion(), doc.title(),
                doc.body(), version.declaredRefs());
        return recomputed.equals(version.contentDigest()) ? OK : DIGEST_MISMATCH;
    }

    /**
     * 校验平台帖/更正与真实发布一致。
     *
     * @param post 平台版本记录
     * @param doc  真实发布视图
     * @return 结果码
     */
    public static String verify(PlatformPostModel.Post post, PublishedDocument doc) {
        Objects.requireNonNull(post, "平台版本");
        if (!post.docId().equals(doc.docId())) {
            return DOC_MISMATCH;
        }
        if (doc.version() != post.version()) {
            return VERSION_MISMATCH;
        }
        if (!"PUBLIC".equals(doc.audienceKind()) || !doc.audiencePartyId().isEmpty()) {
            return AUDIENCE_MISMATCH;
        }
        if (!post.author().equals(doc.publisherPartyId())) {
            return PUBLISHER_MISMATCH;
        }
        if (doc.publishedAt() != post.publishedAt()) {
            return TIME_MISMATCH;
        }
        List<PlatformPostModel.Ref> declared = post.declaredRefs();
        String recomputed = post.version() == 1
                ? GroupModel.platformDigest(doc.publisherPartyId(), doc.title(), doc.body(),
                        toGroupRefs(declared))
                : GroupModel.versionDigest("PLATFORM", doc.publisherPartyId(), "",
                        post.version(), post.basedOnVersion(), doc.title(), doc.body(),
                        toGroupRefs(declared));
        return recomputed.equals(post.contentDigest()) ? OK : DIGEST_MISMATCH;
    }

    private static List<GroupModel.Ref> toGroupRefs(List<PlatformPostModel.Ref> refs) {
        java.util.ArrayList<GroupModel.Ref> converted = new java.util.ArrayList<>();
        for (PlatformPostModel.Ref ref : refs == null ? List.<PlatformPostModel.Ref>of() : refs) {
            converted.add(new GroupModel.Ref(ref.docId(), ref.version()));
        }
        return converted;
    }
}
