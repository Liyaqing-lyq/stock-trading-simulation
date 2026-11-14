package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 群/平台帖 → 媒体同源根消费桥：把已接受的 {@link GroupModel} 与 {@link InformationModel}
 * 接缝装配成 {@link MediaModel} 能消费的两个受信回调。
 *
 * <p><b>能力边界</b>：本类不执行订单、不写世界日志、不建待办、不读墙钟、不碰现金、不调用模型，
 * 也不保存任何状态。它不复制群成员表、正文、阅读账或第二份来源图：成员资格只问
 * {@link GroupModel#canRead}，正文与阅读只问 {@link InformationModel}，规范根只问
 * {@link GroupModel.Post#originRefs()} 与外部 {@link PlatformPostOrigins}。</p>
 *
 * <p><b>同源口径</b>：一条原始事实被群转或平台转后，转述版本本身不是新的独立来源。
 * {@link #sourceOrigins} 返回的解析器把转述版本映射到声明来源；{@link MediaModel} 再展开到规范闭包，
 * 因此同一条事实的所有转述只计一个来源组。谱系未知（元数据缺失）、成环、未来根与伪标识一律拒绝。</p>
 *
 * <p><b>私密边界</b>：原始根可以是私密资料；它只进入媒体状态内部的 {@code Story.roots}
 * 与版本化 {@code Lineage}，不进入任何人物/公开投影。转述者公开的是自己的新陈述，读者不会
 * 因转发获得原始身份、原文或引用权。</p>
 */
public final class GroupMediaBridge {

    /** 帖子文档标识空间，按 {@link GroupModel#postId} / {@link GroupModel#docIdFor} 的确定性拼法推导。 */
    private static final String DOC_PREFIX = docPrefix();

    private GroupMediaBridge() {
    }

    /**
     * 群读资格回调：直接委托 {@link GroupModel#canRead}，供
     * {@link InformationModel#catalog}、{@link InformationModel#startRead}、
     * {@link InformationModel#completeRead} 的 {@link GroupAccess} 重载使用。
     *
     * <p>不缓存、不复制成员段；每次判定都读同一份群状态，因此 JOIN_TIME / ALL_HISTORY 与
     * 退出后的拒绝沿已接受规则，不会因为桥接而放宽。</p>
     *
     * @param groups 当前群状态；不可为 null
     * @return 受信读取资格回调
     */
    public static GroupAccess reader(GroupModel.State groups) {
        Objects.requireNonNull(groups, "群状态");
        return (groupId, personPartyId, docVersionPublishedAt, at) ->
                GroupModel.canRead(groups, groupId, personPartyId, docVersionPublishedAt, at);
    }

    /**
     * 平台帖与更正版的来源：{@link GroupModel} 只记录首版帖子元数据，平台帖与同帖更正版必须由
     * 后继唯一世界从真实版本谱系提供。
     *
     * <p>本接口刻意很小：世界只需回答「这一版声明了哪些来源」。多层展开、成环与时间校验仍由
     * {@link MediaModel} 完成，因此不存在第二份完整来源图。</p>
     */
    public interface PlatformPostOrigins {

        /** 不接管任何文档；缺平台帖模型时使用。 */
        PlatformPostOrigins NONE = new PlatformPostOrigins() {
            @Override
            public boolean covers(String docId) {
                return false;
            }

            @Override
            public Optional<List<GroupModel.Ref>> originsOf(InformationModel.State information,
                                                           String docId, long version) {
                return Optional.empty();
            }
        };

        /**
         * 是否由本解析器接管该文档（平台帖，或群帖的更正版本）。
         *
         * @param docId 资料标识
         * @return 接管返回 true
         */
        boolean covers(String docId);

        /**
         * 某版本的声明来源，必须绑定本次提交的真实 InformationModel 状态。
         *
         * <p>实现要核对文档/版本/发布者/受众/实际发布时刻，以及可核的内容摘要；任一不符或
         * {@code information == null} 都必须返回空，不得把构造时缓存的旧快照当作事实。</p>
         *
         * @param information 本次提交的信息状态
         * @param docId       资料标识
         * @param version     版本
         * @return 空 {@link Optional} 表示谱系未知（调用方必须拒绝）；空列表表示独立陈述
         */
        Optional<List<GroupModel.Ref>> originsOf(InformationModel.State information, String docId,
                                                 long version);

        /**
         * 旧两参入口：没有本次信息状态，默认 fail closed，不能被当作可信来源。
         *
         * @param docId   资料标识
         * @param version 版本
         * @return 恒为空；实现不得把它覆盖成可信路径
         */
        default Optional<List<GroupModel.Ref>> originsOf(String docId, long version) {
            return Optional.empty();
        }
    }

    /**
     * 把群状态与平台帖来源装配成 {@link MediaModel.SourceOrigins}。
     *
     * <p>群帖首版（{@code version == 1}）取 {@link GroupModel.Post#originRefs()}：原始帖为空列表，
     * 表示它自身就是独立陈述；转述帖是 `GroupModel` 已展开的规范根。群帖更正版（{@code version > 1}）
     * 与平台帖一律问 {@code platform}，<b>不继承</b>首版根，缺失即拒绝。</p>
     *
     * <p><b>事实源门</b>：群帖只有在 {@link GroupModel.PostStatus#PUBLISHED} 且与信息状态中的真实文档
     * 一致时才被当作来源——文档发布者必须是帖作者、受众必须是该帖所属群（{@code GROUP} + 群标识）、
     * 首版版本存在且其发布时间等于 {@link GroupModel.Post#publishedAt()}（`GroupModel.publishPost`
     * 的冻结语义就是「实际发布时刻 = 计划 publishAt」）。任一不符返回空 {@link Optional}（拒绝）：
     * 排程中（QUEUED/ABORTED）的帖、伪造发布者/群/时点的文档都不能成为事实源。</p>
     *
     * <p>更正版（{@code version > 1}）的正文由唯一世界另发，其后来的发布时间<b>不</b>与首版相等；
     * 本桥只要求首版绑定成立，再把该版本的来源交给 {@code platform}，不自行推断更正版的时点。</p>
     *
     * <p>已知标识但状态里没有对应帖子（伪造的群帖文档）返回空 {@link Optional}，同样拒绝。</p>
     *
     * @param groups   当前群状态；不可为 null
     * @param platform 平台帖/更正版来源；null 视为 {@link PlatformPostOrigins#NONE}
     * @return 受信来源解析
     */
    public static MediaModel.SourceOrigins sourceOrigins(GroupModel.State groups,
                                                         PlatformPostOrigins platform) {
        Objects.requireNonNull(groups, "群状态");
        PlatformPostOrigins external = platform == null ? PlatformPostOrigins.NONE : platform;
        return new MediaModel.SourceOrigins() {
            @Override
            public boolean covers(String docId) {
                return isGroupDocument(docId) || external.covers(docId);
            }

            @Override
            public Optional<List<MediaModel.Ref>> originsOf(InformationModel.State information,
                                                            String docId, long version) {
                if (version < 1) {
                    return Optional.empty();
                }
                if (!isGroupDocument(docId)) {
                    return convert(external.originsOf(information, docId, version));
                }
                GroupModel.Post post = postOf(groups, docId);
                if (post == null || post.status() != GroupModel.PostStatus.PUBLISHED
                        || !boundToDocument(information, post)) {
                    // 排程中/已中止的帖、伪造发布者或群、首版时点不符：都不当事实源
                    return Optional.empty();
                }
                if (version == 1) {
                    return Optional.of(convertRefs(post.originRefs()));
                }
                return convert(external.originsOf(information, docId, version));
            }
        };
    }

    /**
     * 群帖与信息文档的最小绑定：发布者、受众群、首版存在且首版发布时刻等于帖的计划发布时刻。
     *
     * @param information 本次提交使用的信息状态
     * @param post        已发布帖
     * @return 一致返回 true
     */
    private static boolean boundToDocument(InformationModel.State information,
                                          GroupModel.Post post) {
        if (information == null) {
            return false;
        }
        InformationModel.Document document = null;
        for (InformationModel.Document candidate : information.documents()) {
            if (candidate.docId().equals(post.docId())) {
                document = candidate;
                break;
            }
        }
        if (document == null || !document.publisherPartyId().equals(post.author())
                || document.audience().kind() != InformationModel.AudienceKind.GROUP
                || !post.groupId().equals(document.audience().partyId())) {
            return false;
        }
        InformationModel.DocumentVersion first = document.at(1);
        return first != null && first.publishedAt() == post.publishedAt();
    }

    /**
     * 判断标识是否落在群帖文档空间。
     *
     * @param docId 资料标识
     * @return 属于返回 true
     */
    public static boolean isGroupDocument(String docId) {
        return docId != null && docId.startsWith(DOC_PREFIX);
    }

    // ------------------------------------------------------------------ 内部

    private static GroupModel.Post postOf(GroupModel.State groups, String docId) {
        for (GroupModel.Post post : groups.posts()) {
            if (post.docId().equals(docId)) {
                return post;
            }
        }
        return null;
    }

    private static Optional<List<MediaModel.Ref>> convert(Optional<List<GroupModel.Ref>> refs) {
        if (refs == null || refs.isEmpty()) {
            // Optional.empty 是「谱系未知」；Optional.of(List.of()) 是「明确原创」，必须原样保留差别
            return Optional.empty();
        }
        return Optional.of(convertRefs(refs.get()));
    }

    private static List<MediaModel.Ref> convertRefs(List<GroupModel.Ref> refs) {
        List<MediaModel.Ref> converted = new ArrayList<>();
        for (GroupModel.Ref ref : refs == null ? List.<GroupModel.Ref>of() : refs) {
            converted.add(new MediaModel.Ref(ref.docId(), ref.version()));
        }
        return List.copyOf(converted);
    }

    /**
     * 由冻结的确定性拼法推导群帖文档前缀：{@code docIdFor(postId)} 是「前缀 + 帖子标识」，
     * 因此前缀 = 拼接结果去掉帖子标识。类内常量为 private，本桥不改已接受底座。
     */
    private static String docPrefix() {
        String samplePost = GroupModel.postId("p", "c");
        String sampleDoc = GroupModel.docIdFor(samplePost);
        return sampleDoc.substring(0, sampleDoc.length() - samplePost.length());
    }
}
