package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 唯一可信来源适配器：把平台帖、群更正版接到已接受的 {@link GroupMediaBridge.PlatformPostOrigins}。
 *
 * <p><b>只查询、不存储</b>：本类不复制来源图，也不缓存闭包。群帖 v1 由 {@code GroupMediaBridge} 内部
 * 直接读 {@link GroupModel.Post#originRefs()}；本类只回答两类此前没有提供者的版本：平台帖全部版本，
 * 以及群帖第 2 版起的更正版本。媒体成稿仍由已接受的 {@link MediaModel.Lineage} 负责。</p>
 *
 * <p><b>必须绑定本次提交的 Info</b>：每次作答都用调用方传入的 {@link InformationModel.State} 构造
 * {@link PublicationBinding.PublishedDocument}，核对文档、版本、发布者、受众、实际发布时刻与内容摘要；
 * 不缓存、不接受构造时的旧快照。{@code information == null}、文档缺失、任一不符都返回空。</p>
 *
 * <p><b>终止记录不遮挡</b>：同一版本可能保留多条终止记录与一条有效记录；这里只认 PUBLISHED 且与
 * Info 绑定成立的那一条。旧两参入口没有 Info，恒为空，不得被当作可信路径。</p>
 *
 * <p><b>边界</b>：本类无状态、无 I/O、不读墙钟、不写世界；world 在装配
 * {@code GroupMediaBridge.sourceOrigins(groups, new PostOrigins(groups, platform))} 时构造一次。</p>
 */
public final class PostOrigins implements GroupMediaBridge.PlatformPostOrigins {

    private final GroupModel.State groups;
    private final PlatformPostModel.State platform;

    /**
     * 构造适配器。
     *
     * @param groups   当前群状态；不可为 null
     * @param platform 当前平台状态；不可为 null
     */
    public PostOrigins(GroupModel.State groups, PlatformPostModel.State platform) {
        this.groups = Objects.requireNonNull(groups, "群状态");
        this.platform = Objects.requireNonNull(platform, "平台状态");
    }

    /**
     * 是否由本适配器接管该文档。
     *
     * @param docId 文档标识
     * @return 平台文档空间内返回 true；群文档由桥自身覆盖
     */
    @Override
    public boolean covers(String docId) {
        return docId != null && docId.startsWith(PlatformPostModel.DOC_PREFIX);
    }

    /**
     * 旧两参入口：没有本次信息状态，恒为空（fail closed）。
     *
     * @param docId   文档
     * @param version 版本
     * @return 恒为空
     */
    @Override
    public Optional<List<GroupModel.Ref>> originsOf(String docId, long version) {
        return Optional.empty();
    }

    /**
     * 某版本的声明来源，绑定本次提交的真实信息状态。
     *
     * @param information 本次提交的信息状态；null 视为不可作答
     * @param docId       文档
     * @param version     版本
     * @return 空表示不可作答；空列表表示明确原创
     */
    @Override
    public Optional<List<GroupModel.Ref>> originsOf(InformationModel.State information, String docId,
                                                    long version) {
        if (information == null || docId == null || version < 1) {
            return Optional.empty();
        }
        if (covers(docId)) {
            for (PlatformPostModel.Post post : platform.posts()) {
                if (!post.docId().equals(docId) || post.version() != version
                        || post.status() != PlatformPostModel.PostStatus.PUBLISHED) {
                    continue;
                }
                PublicationBinding.PublishedDocument doc =
                        PublicationBinding.fromInformation(information, docId, version)
                                .orElse(null);
                if (doc == null || !PublicationBinding.OK.equals(
                        PublicationBinding.verify(post, doc))) {
                    return Optional.empty();
                }
                return Optional.of(convert(post.originRefs()));
            }
            return Optional.empty();
        }
        if (!GroupMediaBridge.isGroupDocument(docId) || version < 2) {
            // 群帖 v1 与媒体成稿不归本适配器；桥/Media 有自己的已验实现
            return Optional.empty();
        }
        for (GroupModel.PostVersion corrected : groups.postVersions()) {
            if (!corrected.docId().equals(docId) || corrected.version() != version
                    || corrected.status() != GroupModel.PostStatus.PUBLISHED) {
                continue;
            }
            PublicationBinding.PublishedDocument doc =
                    PublicationBinding.fromInformation(information, docId, version).orElse(null);
            if (doc == null || !PublicationBinding.OK.equals(
                    PublicationBinding.verify(corrected, doc))) {
                return Optional.empty();
            }
            return Optional.of(passThrough(corrected.originRefs()));
        }
        return Optional.empty();
    }

    private static List<GroupModel.Ref> convert(List<PlatformPostModel.Ref> refs) {
        List<GroupModel.Ref> converted = new ArrayList<>();
        for (PlatformPostModel.Ref ref : refs) {
            converted.add(new GroupModel.Ref(ref.docId(), ref.version()));
        }
        return List.copyOf(converted);
    }

    private static List<GroupModel.Ref> passThrough(List<GroupModel.Ref> refs) {
        return List.copyOf(refs);
    }
}
