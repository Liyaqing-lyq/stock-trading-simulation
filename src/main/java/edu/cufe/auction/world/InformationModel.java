package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.RoleAccess.Action;
import edu.cufe.auction.world.RoleAccess.Decision;
import edu.cufe.auction.world.RoleAccess.Target;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * M4b.1 资料版本与实际阅读纯核：受众投影、固定版本、阅读工时与个人经历。
 *
 * <p><b>能力边界</b>：本类只回答「谁在什么时刻能看到哪些资料的哪一版、读完之后本人记得什么、
 * 能否引用」。它不执行订单、不判任职、不写世界日志、不投递观察、不调用模型、不联网、不读墙钟，
 * 也不包含媒体自主采编、群聊/调查/采访、六层记忆、遗忘或偏信。角色获得的只有
 * {@link ReadObservation} 这一份本人投影，拿不到 {@link Document}、{@link State} 或内部审计源。</p>
 *
 * <p><b>受众口径</b>：受众五种——{@link AudienceKind#PUBLIC 公开}、
 * {@link AudienceKind#ORGANIZATION 组织}、{@link AudienceKind#DIRECT 本人直投}、
 * {@link AudienceKind#GROUP 群组}、{@link AudienceKind#BOARD 董事会}。组织资料每次查询与每次到期复核都用<b>调用方传入的最新</b>
 * {@link RoleAccess.State} 判 {@link Action#READ_ORGANIZATION_FILE}，离职后立即失效；直投只对
 * 指定人物成立；群组统一委托 {@link GroupAccess#canRead}；董事会资料委托
 * {@link BoardAccess#canRead}，只按当时真实章程席位判定，不使用 RoleAccess。旧签名对群组与董事会
 * 都按恒定拒绝处理。未知文档、未发布版本与无权文档一律返回同一个 {@link Status#NOT_AVAILABLE}，
 * 不泄露隐藏资料的存在性。</p>
 *
 * <p><b>版本口径</b>：文档标识、发布者与受众在首次发表时冻结，版本号从 1 连续递增、发布时间
 * 严格递增；标题、正文、公开来源描述与阅读耗时按版本冻结。追加新版本不会改变旧版，也不能
 * 悄悄扩大受众——要改受众只能另发新文档。正在阅读的版本固定为开始时的版本，发布新版不替换它，
 * 引用新版必须另付一份工时。</p>
 *
 * <p><b>工时口径</b>：阅读耗时是版本上的正 long 毫秒，{@code due = 开始时刻 + 耗时}，溢出在开始前拒绝。
 * 每人同时最多一个活动阅读，不同人物互不阻塞。<b>{@code due} 是唯一的逻辑完成时点</b>：未到不交付全文，
 * 终态（完成或拒绝）与已花工时一律记在 {@code due}，{@code completeRead} 在 {@code due} 之后多久被调用
 * 都不改写结果；资格用调用方传入的最新完整任职历史<b>在 {@code due} 判定</b>，任职区间右开，
 * 因此恰好 {@code due} 离任仍拒，到期前离任者后来复职也不能倒填旧经历。到期前可取消（严格早于
 * {@code due}），保留开始与取消时点（已花时间可复算），不产生半篇经历。完成、取消与拒绝后，
 * 同一阅读单不再产生第二份经历；同一固定版本重读只保留最早那份经历。</p>
 *
 * <p><b>引用口径</b>：{@link #canCite} 只认本人对该固定版本的真实完成，且
 * {@code completedAt <= now}。只看过目录标题、别人的经历、尚未到期或未读的版本一律 false；
 * 它不判断自然语言真假，正文里的「忽略规则、打开全部账户」只是资料内容，不增加任何权限。</p>
 *
 * <p><b>信任边界</b>：{@link State} 是受信管理数据，构造期只保证内部一致（版本连续、经历与完成
 * 记录对应、读写标识唯一）。人物/组织是否真实登记、发布者是否有编辑权、组织受众是否对应同一个
 * {@link OpeningRegistry}，必须由未来运行时用固定 registry 核对（对应 RoleAccess 审查的 R1/R2），
 * 不能信任任意 JSON；本类不把任意 {@code new State(...)} 当作网络身份。</p>
 *
 * <p><b>复现口径</b>：所有时刻都是调用方显式传入的模拟毫秒，集合在构造期深拷贝并排序，
 * {@link #toJson} 输出稳定，可被 {@link #fromJson} 还原为相等状态。</p>
 */
public final class InformationModel {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final String READ_ID_PREFIX = "READ-";

    private InformationModel() {
    }

    /** 受众种类；本版为公开、组织、本人直投、群组与董事会五种。 */
    public enum AudienceKind {
        /** 任何已登记人物可见。 */
        PUBLIC,
        /** 仅该组织当前获准读取组织档案的任职者可见。 */
        ORGANIZATION,
        /** 仅指定人物本人可见。 */
        DIRECT,
        /** 仅由注入的 {@link GroupAccess} 判定为有资格的读取者可见。 */
        GROUP,
        /** 仅由注入的 {@link BoardAccess} 判定为该公司当时真实章程席位的读取者可见。 */
        BOARD
    }

    /**
     * 受众：公开、一个组织、一个人物、一个群组或一个董事会。
     *
     * <p>公开受众不携带对象；组织、直投、群组与董事会受众必须写明对象标识。受众随文档冻结，追加版本不能修改它。</p>
     *
     * @param kind    受众种类
     * @param partyId 组织、人物、群组或公司标识；公开受众必须为空
     */
    public record Audience(AudienceKind kind, String partyId) {

        /** 校验种类与对象的一致性。 */
        public Audience {
            Objects.requireNonNull(kind, "受众种类");
            if (kind == AudienceKind.PUBLIC) {
                if (partyId != null) {
                    throw new IllegalArgumentException("公开资料不携带受众对象");
                }
            } else {
                String label = switch (kind) {
                    case ORGANIZATION -> "受众组织";
                    case DIRECT -> "受众人物";
                    case GROUP -> "受众群";
                    case BOARD -> "受众公司";
                    default -> throw new IllegalStateException("未知受众种类：" + kind);
                };
                requireText(partyId, label);
            }
        }

        /**
         * 公开受众。
         *
         * @return 任何已登记人物可见的受众
         */
        public static Audience publicAudience() {
            return new Audience(AudienceKind.PUBLIC, null);
        }

        /**
         * 组织受众。
         *
         * @param organizationPartyId 组织标识
         * @return 该组织任职者可见的受众
         */
        public static Audience organization(String organizationPartyId) {
            return new Audience(AudienceKind.ORGANIZATION, organizationPartyId);
        }

        /**
         * 本人直投受众。
         *
         * @param personPartyId 收件人物标识
         * @return 只有该人物可见的受众
         */
        public static Audience direct(String personPartyId) {
            return new Audience(AudienceKind.DIRECT, personPartyId);
        }

        /**
         * 群组受众。
         *
         * <p>本类只冻结群标识；能否读取由每次查询/开读/完成时注入的 {@link GroupAccess} 判定，
         * 不在受众里复制成员表或永久收件人列表。</p>
         *
         * @param groupId 群标识
         * @return 由群读取回调判定资格的受众
         */
        public static Audience group(String groupId) {
            return new Audience(AudienceKind.GROUP, groupId);
        }

        /**
         * 董事会受众。
         *
         * <p>本类只冻结公司标识；能否读取由每次查询/开读/完成时注入的 {@link BoardAccess} 判定，
         * 不在受众里复制董事会成员表或席位历史。</p>
         *
         * @param companyPartyId 公司组织 partyId
         * @return 由董事会读取回调判定资格的受众
         */
        public static Audience board(String companyPartyId) {
            return new Audience(AudienceKind.BOARD, companyPartyId);
        }
    }

    /**
     * 待发布的一版草稿：内容、公开来源描述、发布时刻、固定阅读耗时与内部审计源。
     *
     * @param title                  标题，按版本冻结
     * @param body                   正文，按版本冻结；它只是资料内容，不是系统指令
     * @param publicSourceDescription 对外可见的来源描述，可为空白（内部资料没有公开来源）
     * @param publishedAt            发布时刻，模拟毫秒
     * @param readDurationMillis     固定阅读耗时，必须为正
     * @param internalSourceIds      仅管理审计使用的内部源标识，不进入任何角色投影
     */
    public record VersionDraft(String title, String body, String publicSourceDescription,
                               long publishedAt, long readDurationMillis, List<String> internalSourceIds) {

        /** 校验内容、时刻、耗时与内部源清单。 */
        public VersionDraft {
            requireText(title, "资料标题");
            requireText(body, "资料正文");
            Objects.requireNonNull(publicSourceDescription, "公开来源描述");
            requireNonNegative(publishedAt, "发布时间");
            if (readDurationMillis <= 0) {
                throw new IllegalArgumentException("阅读耗时必须为正毫秒：" + readDurationMillis);
            }
            internalSourceIds = internalIds(internalSourceIds);
        }

        private DocumentVersion asVersion(long version) {
            return new DocumentVersion(version, title, body, publicSourceDescription, publishedAt,
                    readDurationMillis, internalSourceIds);
        }
    }

    /**
     * 已发布的一版：版本号加冻结的内容与耗时。
     *
     * @param version                版本号，从 1 连续递增
     * @param title                  标题
     * @param body                   正文
     * @param publicSourceDescription 公开来源描述
     * @param publishedAt            发布时刻
     * @param readDurationMillis     固定阅读耗时
     * @param internalSourceIds      仅审计使用的内部源标识
     */
    public record DocumentVersion(long version, String title, String body, String publicSourceDescription,
                                  long publishedAt, long readDurationMillis, List<String> internalSourceIds) {

        /** 校验版本号、内容、时刻与耗时。 */
        public DocumentVersion {
            if (version < 1) {
                throw new IllegalArgumentException("版本号必须从 1 开始：" + version);
            }
            requireText(title, "资料标题");
            requireText(body, "资料正文");
            Objects.requireNonNull(publicSourceDescription, "公开来源描述");
            requireNonNegative(publishedAt, "发布时间");
            if (readDurationMillis <= 0) {
                throw new IllegalArgumentException("阅读耗时必须为正毫秒：" + readDurationMillis);
            }
            internalSourceIds = internalIds(internalSourceIds);
        }
    }

    /**
     * 一篇资料：标识、发布者、受众与按序追加的版本。
     *
     * @param docId            不透明文档标识，运行内唯一且不可复用
     * @param publisherPartyId 发布者人物，随文档冻结
     * @param audience         受众，随文档冻结
     * @param versions         从版本 1 开始连续递增、发布时间严格递增的版本表
     */
    public record Document(String docId, String publisherPartyId, Audience audience,
                           List<DocumentVersion> versions) {

        /** 校验标识、受众与版本连续性。 */
        public Document {
            requireText(docId, "资料标识");
            requireText(publisherPartyId, "发布者");
            Objects.requireNonNull(audience, "受众");
            List<DocumentVersion> list = new ArrayList<>(Objects.requireNonNull(versions, "资料版本"));
            if (list.isEmpty()) {
                throw new IllegalArgumentException("资料至少需要一个已发布版本：" + docId);
            }
            for (int index = 0; index < list.size(); index++) {
                DocumentVersion current = Objects.requireNonNull(list.get(index), "资料版本");
                if (current.version() != index + 1L) {
                    throw new IllegalArgumentException("版本号必须从 1 连续递增：" + docId + " -> "
                            + current.version());
                }
                if (index > 0 && current.publishedAt() <= list.get(index - 1).publishedAt()) {
                    throw new IllegalArgumentException("发布时间必须严格递增：" + docId);
                }
            }
            versions = List.copyOf(list);
        }

        /**
         * 取指定版本。
         *
         * @param version 版本号
         * @return 该版本；不存在返回空
         */
        public DocumentVersion at(long version) {
            for (DocumentVersion candidate : versions) {
                if (candidate.version() == version) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * 活动阅读：本人在某时刻开始读某文档的固定版本，{@code due} 之前不交付全文。
     *
     * @param readId        阅读标识，运行内唯一
     * @param personPartyId 阅读者
     * @param docId         资料标识
     * @param version       固定版本
     * @param startedAt     开始时刻
     * @param dueAt         应完成时刻，等于开始时刻加版本固定耗时
     */
    public record Reading(String readId, String personPartyId, String docId, long version,
                          long startedAt, long dueAt) {

        /** 校验标识与时间。 */
        public Reading {
            requireText(readId, "阅读标识");
            requireText(personPartyId, "阅读者");
            requireText(docId, "资料标识");
            if (version < 1) {
                throw new IllegalArgumentException("阅读版本必须从 1 开始：" + version);
            }
            requireNonNegative(startedAt, "开始时刻");
            if (dueAt <= startedAt) {
                throw new IllegalArgumentException("阅读应完成时刻必须晚于开始时刻：" + readId);
            }
        }
    }

    /** 阅读的终结原因。 */
    public enum CloseReason {
        /** 正常完成，产生本人经历。 */
        COMPLETED,
        /** 到期前取消，保留已花时间，不产生经历。 */
        CANCELLED,
        /** 到期复核无权限，明确拒绝，不产生经历。 */
        DENIED
    }

    /**
     * 已终结的阅读：保留开始、应完成与终结时点，用于复算已花工时。
     *
     * @param readId        阅读标识
     * @param personPartyId 阅读者
     * @param docId         资料标识
     * @param version       固定版本
     * @param startedAt     开始时刻
     * @param dueAt         应完成时刻
     * @param closedAt      终结时刻；完成与拒绝都等于 {@code dueAt}，取消严格早于 {@code dueAt}
     * @param reason        终结原因
     */
    public record ClosedReading(String readId, String personPartyId, String docId, long version,
                                long startedAt, long dueAt, long closedAt, CloseReason reason) {

        /** 校验时间与终结原因的一致性。 */
        public ClosedReading {
            requireText(readId, "阅读标识");
            requireText(personPartyId, "阅读者");
            requireText(docId, "资料标识");
            Objects.requireNonNull(reason, "终结原因");
            if (version < 1) {
                throw new IllegalArgumentException("阅读版本必须从 1 开始：" + version);
            }
            requireNonNegative(startedAt, "开始时刻");
            requireNonNegative(closedAt, "终结时刻");
            if (dueAt <= startedAt) {
                throw new IllegalArgumentException("阅读应完成时刻必须晚于开始时刻：" + readId);
            }
            if (closedAt < startedAt) {
                throw new IllegalArgumentException("终结时刻不能早于开始时刻：" + readId);
            }
            if (reason == CloseReason.COMPLETED && closedAt != dueAt) {
                throw new IllegalArgumentException("完成必须发生在固定应完成时刻：" + readId);
            }
            if (reason == CloseReason.CANCELLED && closedAt >= dueAt) {
                throw new IllegalArgumentException("取消必须严格早于应完成时刻：" + readId);
            }
            if (reason == CloseReason.DENIED && closedAt < dueAt) {
                throw new IllegalArgumentException("拒绝复核必须发生在到期之后：" + readId);
            }
        }
    }

    /**
     * 本人已完成的阅读经历：角色能得到的唯一全文来源。
     *
     * <p>不含内部审计源标识、全局数量、其他读者或原始 {@link Document}；内容按完成时的固定版本
     * 冻结，之后发布新版不会改变它。</p>
     *
     * @param readId                 产生该经历的阅读标识
     * @param personPartyId          读者本人
     * @param docId                  资料标识
     * @param version                所读固定版本
     * @param title                  当时标题
     * @param body                   当时正文
     * @param publicSourceDescription 当时公开来源描述
     * @param publishedAt            该版本的发布时刻
     * @param completedAt            实际完成时刻，等于阅读的 {@code due}
     */
    public record ReadObservation(String readId, String personPartyId, String docId, long version,
                                  String title, String body, String publicSourceDescription,
                                  long publishedAt, long completedAt) {

        /** 校验内容与时刻。 */
        public ReadObservation {
            requireText(readId, "阅读标识");
            requireText(personPartyId, "读者");
            requireText(docId, "资料标识");
            requireText(title, "资料标题");
            requireText(body, "资料正文");
            Objects.requireNonNull(publicSourceDescription, "公开来源描述");
            if (version < 1) {
                throw new IllegalArgumentException("阅读版本必须从 1 开始：" + version);
            }
            requireNonNegative(publishedAt, "发布时刻");
            requireNonNegative(completedAt, "完成时刻");
        }
    }

    /**
     * 目录条目：读者可见资料的索引，只含标题级信息。
     *
     * @param docId            资料标识
     * @param version          最新已发布版本
     * @param title            该版本标题
     * @param publisherPartyId 公开可见的发布者
     * @param publishedAt      该版本发布时刻
     */
    public record CatalogEntry(String docId, long version, String title, String publisherPartyId,
                               long publishedAt) {

        /** 校验字段。 */
        public CatalogEntry {
            requireText(docId, "资料标识");
            requireText(title, "资料标题");
            requireText(publisherPartyId, "发布者");
            if (version < 1) {
                throw new IllegalArgumentException("目录版本必须从 1 开始：" + version);
            }
            requireNonNegative(publishedAt, "发布时刻");
        }
    }

    /** 操作结果状态；一切语义拒绝都是显式结果，不是异常。 */
    public enum Status {
        /** 阅读已开始，工时已预留。 */
        STARTED,
        /** 阅读在固定应完成时刻完成，产生本人经历。 */
        COMPLETED,
        /** 到期前取消，已花时间保留，不产生经历。 */
        CANCELLED,
        /** 到期复核无权限，已记录拒绝终态并释放工时。 */
        DENIED,
        /** 尚未到期，不交付全文。 */
        NOT_DUE,
        /** 已到应完成时刻，只能完成复核，不再接受取消。 */
        EXPIRED,
        /** 本人已有活动阅读。 */
        BUSY,
        /** 未知文档/版本、尚未发布或无权阅读，三者同值。 */
        NOT_AVAILABLE,
        /** 该阅读单不是本人的活动阅读（未知、他人或已终结）。 */
        NOT_FOUND,
        /** 应完成时刻毫秒溢出，开始前拒绝。 */
        TIME_OVERFLOW
    }

    /**
     * 一次操作的结果：状态、调用方必须保留的新状态，以及完成时产生的本人经历。
     *
     * <p>即使 {@link Status#DENIED} 之类不是成功，只要状态有变化（例如释放工时），
     * {@code state} 也必须由未来运行时原子提交——丢弃它会让阅读工时空转。</p>
     *
     * @param status      结果状态
     * @param state       操作后的状态；被拒绝且无变化时等于输入状态
     * @param observation 只有 {@link Status#COMPLETED} 时非空
     */
    public record Outcome(Status status, State state, ReadObservation observation) {

        /** 校验状态与经历的对应关系。 */
        public Outcome {
            Objects.requireNonNull(status, "结果状态");
            Objects.requireNonNull(state, "结果状态快照");
            if ((status == Status.COMPLETED) != (observation != null)) {
                throw new IllegalArgumentException("只有完成结果才能携带本人经历：" + status);
            }
        }
    }

    /**
     * 信息状态：登记读者、资料、活动阅读、已终结阅读与个人经历。
     *
     * <p>构造期完成内部一致性校验（标识唯一、版本连续、阅读引用可解析、工时与版本耗时一致、
     * 经历必须有对应的完成记录且内容与版本冻结一致），因此 {@link #fromJson} 还原出的自相矛盾
     * 状态会被拒绝。身份真实性（人物/组织是否登记、发布者是否有编辑权）只在 {@link #open} 与
     * 未来运行时对照 {@link OpeningRegistry} 校验。</p>
     *
     * @param persons          已登记读者，按标识排序
     * @param documents        资料，按标识排序
     * @param active           活动阅读，按阅读标识排序
     * @param closed           已终结阅读，按阅读标识排序
     * @param observations     个人经历，按读者/资料/版本排序
     * @param nextReadSequence 下一个阅读序号，从 1 开始
     */
    public record State(List<String> persons, List<Document> documents, List<Reading> active,
                        List<ClosedReading> closed, List<ReadObservation> observations,
                        long nextReadSequence) {

        /** 深拷贝、排序并校验全部内部不变量。 */
        public State {
            persons = sortedText(persons, "登记读者");

            List<Document> documentList = sorted(documents, Comparator.comparing(Document::docId));
            Set<String> docIds = new LinkedHashSet<>();
            for (Document document : documentList) {
                if (!docIds.add(document.docId())) {
                    throw new IllegalArgumentException("资料标识重复：" + document.docId());
                }
                if (!persons.contains(document.publisherPartyId())) {
                    throw new IllegalArgumentException("发布者未登记：" + document.publisherPartyId());
                }
                if (document.audience().kind() == AudienceKind.DIRECT
                        && !persons.contains(document.audience().partyId())) {
                    throw new IllegalArgumentException("直投对象未登记：" + document.audience().partyId());
                }
            }

            List<Reading> activeList = sorted(active, Comparator.comparing(Reading::readId));
            List<ClosedReading> closedList = sorted(closed, Comparator.comparing(ClosedReading::readId));
            Set<String> readIds = new LinkedHashSet<>();
            Set<String> readingPersons = new LinkedHashSet<>();
            for (Reading reading : activeList) {
                requireReading(reading.readId(), reading.personPartyId(), reading.docId(), reading.version(),
                        reading.startedAt(), reading.dueAt(), documentList, persons);
                if (!readIds.add(reading.readId())) {
                    throw new IllegalArgumentException("阅读标识重复：" + reading.readId());
                }
                if (!readingPersons.add(reading.personPartyId())) {
                    throw new IllegalArgumentException("同一人物不能同时有两个活动阅读："
                            + reading.personPartyId());
                }
            }
            for (ClosedReading reading : closedList) {
                requireReading(reading.readId(), reading.personPartyId(), reading.docId(), reading.version(),
                        reading.startedAt(), reading.dueAt(), documentList, persons);
                if (!readIds.add(reading.readId())) {
                    throw new IllegalArgumentException("阅读标识重复：" + reading.readId());
                }
            }

            long highestGeneratedSequence = 0;
            for (String readId : readIds) {
                if (!readId.startsWith(READ_ID_PREFIX)) {
                    continue;
                }
                String digits = readId.substring(READ_ID_PREFIX.length());
                if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                try {
                    highestGeneratedSequence = Math.max(highestGeneratedSequence, Long.parseLong(digits));
                } catch (NumberFormatException overflow) {
                    throw new IllegalArgumentException("阅读标识序号超出范围：" + readId);
                }
            }
            if (nextReadSequence <= highestGeneratedSequence) {
                throw new IllegalArgumentException("下一个阅读序号必须大于已存在的自动编号："
                        + nextReadSequence + " <= " + highestGeneratedSequence);
            }

            List<ReadObservation> observationList = sorted(observations,
                    Comparator.comparing(ReadObservation::personPartyId)
                            .thenComparing(ReadObservation::docId)
                            .thenComparingLong(ReadObservation::version));
            Set<String> observationKeys = new LinkedHashSet<>();
            for (ReadObservation observation : observationList) {
                String key = observation.personPartyId() + '\u0000' + observation.docId()
                        + '\u0000' + observation.version();
                if (!observationKeys.add(key)) {
                    throw new IllegalArgumentException("同一人物同一版本只能有一份经历："
                            + observation.personPartyId() + " -> " + observation.docId()
                            + " v" + observation.version());
                }
                if (!persons.contains(observation.personPartyId())) {
                    throw new IllegalArgumentException("经历读者未登记：" + observation.personPartyId());
                }
                DocumentVersion frozen = versionOf(documentList, observation.docId(), observation.version());
                if (observation.completedAt() < frozen.publishedAt()) {
                    throw new IllegalArgumentException("经历完成时刻早于发布时刻：" + observation.readId());
                }
                if (!frozen.title().equals(observation.title())
                        || !frozen.body().equals(observation.body())
                        || !frozen.publicSourceDescription().equals(observation.publicSourceDescription())
                        || frozen.publishedAt() != observation.publishedAt()) {
                    throw new IllegalArgumentException("经历内容与所读版本不一致："
                            + observation.docId() + " v" + observation.version());
                }
                ClosedReading origin = findClosed(closedList, observation.readId());
                if (origin == null || origin.reason() != CloseReason.COMPLETED
                        || !origin.personPartyId().equals(observation.personPartyId())
                        || !origin.docId().equals(observation.docId())
                        || origin.version() != observation.version()
                        || origin.closedAt() != observation.completedAt()) {
                    throw new IllegalArgumentException("经历缺少对应的完成记录：" + observation.readId());
                }
            }
            for (ClosedReading reading : closedList) {
                if (reading.reason() == CloseReason.COMPLETED
                        && !hasObservation(observationList, reading.personPartyId(), reading.docId(),
                        reading.version())) {
                    throw new IllegalArgumentException("完成记录缺少本人经历：" + reading.readId());
                }
            }
            if (nextReadSequence < 1) {
                throw new IllegalArgumentException("下一个阅读序号必须为正：" + nextReadSequence);
            }

            documents = List.copyOf(documentList);
            active = List.copyOf(activeList);
            closed = List.copyOf(closedList);
            observations = List.copyOf(observationList);
        }
    }

    // ---------------------------------------------------------------- 对外操作

    /**
     * 开局：登记可以阅读资料的已登记人物。
     *
     * <p>只接受 {@link OpeningRegistry} 中种类为 {@link PartyKind#PERSON} 的身份；组织、产品与未登记
     * 标识一律拒绝。开局没有任何资料与经历。</p>
     *
     * <p>人物在世界开局时<b>一次登记</b>；本单元不提供运行中新增人物的公开入口（不做未定义的
     * 「动态加入」能力），中途出现的新人物由后续受信 Runtime 决定登记入口与授权，不能靠手工
     * {@code new State(...)} 冒充身份。</p>
     *
     * @param registry     开局登记
     * @param participants 参与阅读的人物标识清单
     * @return 空信息状态
     * @throws IllegalArgumentException 登记为空、人物未登记或不是人物、清单重复
     */
    public static State open(OpeningRegistry registry, List<String> participants) {
        if (registry == null) {
            throw new IllegalArgumentException("开局登记不能为空");
        }
        List<String> list = new ArrayList<>(Objects.requireNonNull(participants, "读者清单"));
        for (String partyId : list) {
            Party party = party(registry, partyId);
            if (party == null || party.kind() != PartyKind.PERSON) {
                throw new IllegalArgumentException("读者未登记或不是人物：" + partyId);
            }
        }
        return new State(list, List.of(), List.of(), List.of(), List.of(), 1);
    }

    /**
     * 首次发表一篇资料：标识、发布者与受众从此冻结，版本号为 1。
     *
     * <p>群组受众只要求在发表时写明非空群标识；成员资格不在发表时固化，读取时由注入的
     * {@link GroupAccess} 判定。公开、组织与直投的发表校验保持原口径。</p>
     *
     * @param state            当前信息状态
     * @param docId            新的不透明文档标识
     * @param publisherPartyId 已登记发布者
     * @param audience         受众
     * @param first            第一版内容
     * @return 追加后的新状态；输入状态不变
     * @throws IllegalArgumentException 标识重复、发布者或直投对象未登记、内容非法
     */
    public static State publish(State state, String docId, String publisherPartyId, Audience audience,
                                VersionDraft first) {
        requireState(state);
        requireText(docId, "资料标识");
        requireText(publisherPartyId, "发布者");
        Objects.requireNonNull(audience, "受众");
        Objects.requireNonNull(first, "第一版内容");
        if (findDocument(state.documents(), docId) != null) {
            throw new IllegalArgumentException("资料标识已存在，不能覆盖：" + docId);
        }
        if (!state.persons().contains(publisherPartyId)) {
            throw new IllegalArgumentException("发布者未登记：" + publisherPartyId);
        }
        if (audience.kind() == AudienceKind.DIRECT && !state.persons().contains(audience.partyId())) {
            throw new IllegalArgumentException("直投对象未登记：" + audience.partyId());
        }
        List<Document> documents = new ArrayList<>(state.documents());
        documents.add(new Document(docId, publisherPartyId, audience, List.of(first.asVersion(1))));
        return new State(state.persons(), documents, state.active(), state.closed(), state.observations(),
                state.nextReadSequence());
    }

    /**
     * 追加一版：版本号自动加一，发布者与受众沿用文档首次发表时的值。
     *
     * <p>不能通过本方法修改发布者或受众，也不能覆盖任何旧版；改受众必须另发新文档。</p>
     *
     * @param state 当前信息状态
     * @param docId 已有资料标识
     * @param next  新一版内容
     * @return 追加后的新状态；输入状态不变
     * @throws IllegalArgumentException 资料不存在、发布时间未递增或内容非法
     */
    public static State publishVersion(State state, String docId, VersionDraft next) {
        requireState(state);
        requireText(docId, "资料标识");
        Objects.requireNonNull(next, "新版本内容");
        Document document = findDocument(state.documents(), docId);
        if (document == null) {
            throw new IllegalArgumentException("资料不存在，不能追加版本：" + docId);
        }
        DocumentVersion last = document.versions().get(document.versions().size() - 1);
        if (next.publishedAt() <= last.publishedAt()) {
            throw new IllegalArgumentException("新版本发布时间必须晚于上一版：" + docId);
        }
        List<DocumentVersion> versions = new ArrayList<>(document.versions());
        versions.add(next.asVersion(last.version() + 1));
        List<Document> documents = new ArrayList<>(state.documents());
        documents.set(documents.indexOf(document),
                new Document(document.docId(), document.publisherPartyId(), document.audience(), versions));
        return new State(state.persons(), documents, state.active(), state.closed(), state.observations(),
                state.nextReadSequence());
    }

    /**
     * 目录：返回该人物此刻可见资料的最新已发布版本索引。
     *
     * <p>只含标识、版本、标题、公开发布者与发布时刻；不含正文、内部审计源、全局数量或隐藏版本。
     * 未登记人物得到空目录，与「没有可见资料」表现一致。群组资料把“最新可见版本”的发布时间
     * 交给 {@link GroupAccess}，因此 JOIN_TIME 一类策略看到的是真正要展示的版本。</p>
     *
     * @param state         当前信息状态
     * @param access        最新任职授权
     * @param personPartyId 查询人物
     * @param now           查询时刻，不可为负
     * @param groups        群组读取回调；null 视为 {@link GroupAccess#NONE}
     * @return 按资料标识排序的可见目录
     * @throws IllegalArgumentException 参数为空或时刻为负
     */
    public static List<CatalogEntry> catalog(State state, RoleAccess.State access, String personPartyId,
                                             long now, GroupAccess groups) {
        return catalog(state, access, personPartyId, now, groups, BoardAccess.NONE);
    }

    /**
     * 目录查询（董事会 + 群组组合回调）：董事会受众走 {@link BoardAccess}，群组受众走
     * {@link GroupAccess}；两个参数位不同，旧 5 参数调用与显式 null 仍解析到群组签名。
     *
     * @param state         当前信息状态
     * @param access        最新任职授权
     * @param personPartyId 查询人物
     * @param now           查询时刻
     * @param groups        群组读取回调；null 视为 {@link GroupAccess#NONE}
     * @param boards        董事会读取回调；null 视为 {@link BoardAccess#NONE}
     * @return 可见目录
     */
    public static List<CatalogEntry> catalog(State state, RoleAccess.State access, String personPartyId,
                                             long now, GroupAccess groups, BoardAccess boards) {
        requireState(state);
        requireAccess(access);
        requireText(personPartyId, "查询人物");
        requireNonNegative(now, "查询时刻");
        GroupAccess groupAccess = groups == null ? GroupAccess.NONE : groups;
        BoardAccess boardAccess = boards == null ? BoardAccess.NONE : boards;
        if (!state.persons().contains(personPartyId)) {
            return List.of();
        }
        List<CatalogEntry> entries = new ArrayList<>();
        for (Document document : state.documents()) {
            DocumentVersion latest = null;
            for (DocumentVersion version : document.versions()) {
                if (version.publishedAt() <= now) {
                    latest = version;
                }
            }
            if (latest == null
                    || !visible(document.audience(), access, personPartyId, now,
                    latest.publishedAt(), groupAccess, boardAccess)) {
                continue;
            }
            entries.add(new CatalogEntry(document.docId(), latest.version(), latest.title(),
                    document.publisherPartyId(), latest.publishedAt()));
        }
        return List.copyOf(entries);
    }

    /**
     * 旧签名目录查询：群组受众按 {@link GroupAccess#NONE} 处理。
     *
     * @param state         当前信息状态
     * @param access        最新任职授权
     * @param personPartyId 查询人物
     * @param now           查询时刻
     * @return 可见目录
     */
    public static List<CatalogEntry> catalog(State state, RoleAccess.State access, String personPartyId,
                                             long now) {
        return catalog(state, access, personPartyId, now, GroupAccess.NONE);
    }

    /**
     * 开始阅读一个明确版本：校验已发布与权限，预留本人工时。
     *
     * <p>未知文档、未发布版本与无权阅读返回同一个 {@link Status#NOT_AVAILABLE}；本人已有活动阅读
     * 返回 {@link Status#BUSY}；{@code due} 溢出返回 {@link Status#TIME_OVERFLOW}。任何拒绝都不改变状态。
     * 群组受众把该明确版本的发布时间与开始时刻一起交给 {@link GroupAccess}。</p>
     *
     * @param state         当前信息状态
     * @param access        最新任职授权
     * @param personPartyId 阅读者
     * @param docId         资料标识
     * @param version       明确版本号
     * @param now           开始时刻，不可为负
     * @param groups        群组读取回调；null 视为 {@link GroupAccess#NONE}
     * @return 结果；成功时新状态含一条活动阅读
     * @throws IllegalArgumentException 参数为空、时刻为负或版本号小于 1
     */
    public static Outcome startRead(State state, RoleAccess.State access, String personPartyId,
                                    String docId, long version, long now, GroupAccess groups) {
        return startRead(state, access, personPartyId, docId, version, now, groups, BoardAccess.NONE);
    }

    /**
     * 开始阅读（董事会 + 群组组合回调）：董事会受众走 {@link BoardAccess}，群组受众走
     * {@link GroupAccess}；参数个数与旧 7 参数签名不同，显式 null 仍解析到群组签名。
     *
     * @param state         当前信息状态
     * @param access        最新任职授权
     * @param personPartyId 阅读者
     * @param docId         资料标识
     * @param version       明确版本号
     * @param now           开始时刻
     * @param groups        群组读取回调；null 视为 {@link GroupAccess#NONE}
     * @param boards        董事会读取回调；null 视为 {@link BoardAccess#NONE}
     * @return 结果
     */
    public static Outcome startRead(State state, RoleAccess.State access, String personPartyId,
                                    String docId, long version, long now, GroupAccess groups,
                                    BoardAccess boards) {
        requireState(state);
        requireAccess(access);
        requireText(personPartyId, "阅读者");
        requireText(docId, "资料标识");
        requireNonNegative(now, "开始时刻");
        if (version < 1) {
            throw new IllegalArgumentException("阅读版本必须从 1 开始：" + version);
        }
        GroupAccess groupAccess = groups == null ? GroupAccess.NONE : groups;
        BoardAccess boardAccess = boards == null ? BoardAccess.NONE : boards;
        if (!state.persons().contains(personPartyId)) {
            return new Outcome(Status.NOT_AVAILABLE, state, null);
        }
        Document document = findDocument(state.documents(), docId);
        if (document == null) {
            return new Outcome(Status.NOT_AVAILABLE, state, null);
        }
        DocumentVersion target = document.at(version);
        if (target == null || target.publishedAt() > now) {
            return new Outcome(Status.NOT_AVAILABLE, state, null);
        }
        if (!visible(document.audience(), access, personPartyId, now,
                target.publishedAt(), groupAccess, boardAccess)) {
            return new Outcome(Status.NOT_AVAILABLE, state, null);
        }
        for (Reading reading : state.active()) {
            if (reading.personPartyId().equals(personPartyId)) {
                return new Outcome(Status.BUSY, state, null);
            }
        }
        long due = now + target.readDurationMillis();
        if (due < now) {
            return new Outcome(Status.TIME_OVERFLOW, state, null);
        }
        if (state.nextReadSequence() == Long.MAX_VALUE) {
            throw new IllegalArgumentException("阅读序号已耗尽");
        }
        String readId = READ_ID_PREFIX + state.nextReadSequence();
        List<Reading> active = new ArrayList<>(state.active());
        active.add(new Reading(readId, personPartyId, docId, version, now, due));
        State next = new State(state.persons(), state.documents(), active, state.closed(),
                state.observations(), state.nextReadSequence() + 1);
        return new Outcome(Status.STARTED, next, null);
    }

    /**
     * 旧签名开读：群组受众按 {@link GroupAccess#NONE} 处理。
     *
     * @param state         当前信息状态
     * @param access        最新任职授权
     * @param personPartyId 阅读者
     * @param docId         资料标识
     * @param version       明确版本号
     * @param now           开始时刻
     * @return 结果
     */
    public static Outcome startRead(State state, RoleAccess.State access, String personPartyId,
                                    String docId, long version, long now) {
        return startRead(state, access, personPartyId, docId, version, now, GroupAccess.NONE);
    }

    /**
     * 完成阅读：到期是唯一的逻辑完成时点，成功才把固定版本全文交成本人经历。
     *
     * <p>未到期返回 {@link Status#NOT_DUE} 且状态不变。到期后用调用方传入的<b>最新完整任职历史</b>
     * 按阅读的 {@code dueAt()} 复核一次：整个阅读期在任则 {@link Status#COMPLETED}；{@code due} 之前
     * （含恰好 {@code due}）已离任则 {@link Status#DENIED}。两种终态的 {@code closedAt} 都记
     * {@code due}——{@code now} 只表示 Runtime 何时提交，不延长已花工时，也不会把 after-due 离任
     * 误判成拒绝，更不会让后来复职者倒填旧经历。拒绝同样释放工时，调用方必须保留这个否定结果的状态变化。
     * 未知、他人或已终结的阅读单返回 {@link Status#NOT_FOUND}，因此不会产生第二份经历。</p>
     *
     * <p>重读同一固定版本时，本方法返回<b>最早那份</b>经历，其 {@code readId} 指向首次完成；
     * 未来 Runtime 应按本次任务的 {@code readId} 落阅读终态，经历则按
     * {@code (personPartyId, docId, version)} 去重，不能按返回经历的 {@code readId} 反推本次阅读单。</p>
     *
     * @param state         当前信息状态
     * @param access        最新任职授权
     * @param personPartyId 阅读者
     * @param readId        阅读标识
     * @param now           提交时刻（只决定调用何时发生，不改写终态与工时），不可为负
     * @param groups        群组读取回调；null 视为 {@link GroupAccess#NONE}
     * @return 结果；完成时携带本人经历
     * @throws IllegalArgumentException 参数为空、时刻为负或时刻早于阅读开始
     */
    public static Outcome completeRead(State state, RoleAccess.State access, String personPartyId,
                                       String readId, long now, GroupAccess groups) {
        return completeRead(state, access, personPartyId, readId, now, groups, BoardAccess.NONE);
    }

    /**
     * 完成阅读（董事会 + 群组组合回调）：董事会受众走 {@link BoardAccess}，群组受众走
     * {@link GroupAccess}；参数个数与旧 6 参数签名不同，显式 null 仍解析到群组签名。
     *
     * <p>与群组口径一致：资格在阅读单固定的 {@code due} 判定，而不是调用本方法的处理时刻；
     * 到期前失席会得到 {@link Status#DENIED} 并保留真实阅读经历，事后复职不能倒填。</p>
     *
     * @param state         当前信息状态
     * @param access        最新任职授权
     * @param personPartyId 阅读者
     * @param readId        阅读标识
     * @param now           提交时刻
     * @param groups        群组读取回调；null 视为 {@link GroupAccess#NONE}
     * @param boards        董事会读取回调；null 视为 {@link BoardAccess#NONE}
     * @return 结果
     */
    public static Outcome completeRead(State state, RoleAccess.State access, String personPartyId,
                                       String readId, long now, GroupAccess groups, BoardAccess boards) {
        requireState(state);
        requireAccess(access);
        requireText(personPartyId, "阅读者");
        requireText(readId, "阅读标识");
        requireNonNegative(now, "完成时刻");
        GroupAccess groupAccess = groups == null ? GroupAccess.NONE : groups;
        BoardAccess boardAccess = boards == null ? BoardAccess.NONE : boards;
        if (!state.persons().contains(personPartyId)) {
            return new Outcome(Status.NOT_FOUND, state, null);
        }
        Reading reading = findReading(state.active(), personPartyId, readId);
        if (reading == null) {
            return new Outcome(Status.NOT_FOUND, state, null);
        }
        if (now < reading.startedAt()) {
            throw new IllegalArgumentException("完成时刻不能早于阅读开始：" + readId);
        }
        if (now < reading.dueAt()) {
            return new Outcome(Status.NOT_DUE, state, null);
        }
        Document document = findDocument(state.documents(), reading.docId());
        DocumentVersion version = document.at(reading.version());

        List<Reading> active = new ArrayList<>(state.active());
        active.remove(reading);
        List<ClosedReading> closed = new ArrayList<>(state.closed());
        List<ReadObservation> observations = new ArrayList<>(state.observations());

        long completedAt = reading.dueAt();
        if (!visible(document.audience(), access, personPartyId, completedAt,
                version.publishedAt(), groupAccess, boardAccess)) {
            closed.add(new ClosedReading(reading.readId(), personPartyId, reading.docId(), reading.version(),
                    reading.startedAt(), reading.dueAt(), completedAt, CloseReason.DENIED));
            State denied = new State(state.persons(), state.documents(), active, closed, observations,
                    state.nextReadSequence());
            return new Outcome(Status.DENIED, denied, null);
        }

        closed.add(new ClosedReading(reading.readId(), personPartyId, reading.docId(), reading.version(),
                reading.startedAt(), reading.dueAt(), completedAt, CloseReason.COMPLETED));
        ReadObservation observation = observationOf(observations, personPartyId, reading.docId(),
                reading.version());
        if (observation == null) {
            observation = new ReadObservation(reading.readId(), personPartyId, reading.docId(),
                    reading.version(), version.title(), version.body(), version.publicSourceDescription(),
                    version.publishedAt(), reading.dueAt());
            observations.add(observation);
        }
        State completed = new State(state.persons(), state.documents(), active, closed, observations,
                state.nextReadSequence());
        return new Outcome(Status.COMPLETED, completed, observation);
    }

    /**
     * 旧签名完成阅读：群组受众按 {@link GroupAccess#NONE} 处理。
     *
     * @param state 当前信息状态
     * @param access 最新任职授权
     * @param personPartyId 阅读者
     * @param readId 阅读标识
     * @param now 提交时刻
     * @return 结果
     */
    public static Outcome completeRead(State state, RoleAccess.State access, String personPartyId,
                                       String readId, long now) {
        return completeRead(state, access, personPartyId, readId, now, GroupAccess.NONE);
    }

    /**
     * 取消未完成的阅读：保留开始与取消时点（已花时间可复算），释放工时，不产生任何正文经历。
     *
     * <p>取消必须严格早于 {@code due}；到达或超过应完成时刻返回 {@link Status#EXPIRED}，只能走完成复核，
     * 因此恢复/审计也不会看到「恰在 due 取消、已花全额工时却没有经历」的状态。</p>
     *
     * @param state         当前信息状态
     * @param personPartyId 阅读者
     * @param readId        阅读标识
     * @param now           取消时刻，不可为负且不得早于开始
     * @return 结果；成功时新状态含一条取消终态
     * @throws IllegalArgumentException 参数为空、时刻为负或时刻早于阅读开始
     */
    public static Outcome cancelRead(State state, String personPartyId, String readId, long now) {
        requireState(state);
        requireText(personPartyId, "阅读者");
        requireText(readId, "阅读标识");
        requireNonNegative(now, "取消时刻");
        if (!state.persons().contains(personPartyId)) {
            return new Outcome(Status.NOT_FOUND, state, null);
        }
        Reading reading = findReading(state.active(), personPartyId, readId);
        if (reading == null) {
            return new Outcome(Status.NOT_FOUND, state, null);
        }
        if (now < reading.startedAt()) {
            throw new IllegalArgumentException("取消时刻不能早于阅读开始：" + readId);
        }
        if (now >= reading.dueAt()) {
            return new Outcome(Status.EXPIRED, state, null);
        }
        List<Reading> active = new ArrayList<>(state.active());
        active.remove(reading);
        List<ClosedReading> closed = new ArrayList<>(state.closed());
        closed.add(new ClosedReading(reading.readId(), personPartyId, reading.docId(), reading.version(),
                reading.startedAt(), reading.dueAt(), now, CloseReason.CANCELLED));
        State next = new State(state.persons(), state.documents(), active, closed, state.observations(),
                state.nextReadSequence());
        return new Outcome(Status.CANCELLED, next, null);
    }

    /**
     * 本人经历：只返回该人物自己已完成的阅读，按读者/资料/版本排序。
     *
     * <p>未知人物返回空表，与「还没有读过」一致；不会返回别人的经历或资料原件。</p>
     *
     * @param state         当前信息状态
     * @param personPartyId 读者
     * @return 本人经历清单
     * @throws IllegalArgumentException 参数为空
     */
    public static List<ReadObservation> observations(State state, String personPartyId) {
        requireState(state);
        requireText(personPartyId, "读者");
        List<ReadObservation> mine = new ArrayList<>();
        for (ReadObservation observation : state.observations()) {
            if (observation.personPartyId().equals(personPartyId)) {
                mine.add(observation);
            }
        }
        return List.copyOf(mine);
    }

    /**
     * 引用判定：本人是否已在该时刻之前真正读完该资料的该固定版本。
     *
     * <p>只看过目录标题、借用别人的经历、完成时刻在未来或从未读过一律 false；它不判断自然语言
     * 内容是否真实，也不把正文里的指令变成权限。</p>
     *
     * @param state         当前信息状态
     * @param personPartyId 引用者
     * @param docId         资料标识
     * @param version       被引版本
     * @param now           引用时刻，不可为负
     * @return 可以引用返回 true
     * @throws IllegalArgumentException 参数为空、时刻为负或版本号小于 1
     */
    public static boolean canCite(State state, String personPartyId, String docId, long version, long now) {
        requireState(state);
        requireText(personPartyId, "引用者");
        requireText(docId, "资料标识");
        requireNonNegative(now, "引用时刻");
        if (version < 1) {
            throw new IllegalArgumentException("引用版本必须从 1 开始：" + version);
        }
        for (ReadObservation observation : state.observations()) {
            if (observation.personPartyId().equals(personPartyId)
                    && observation.docId().equals(docId)
                    && observation.version() == version
                    && observation.completedAt() <= now) {
                return true;
            }
        }
        return false;
    }

    /**
     * 序列化信息状态；集合顺序在构造期已固定，因此同一状态输出稳定。
     *
     * @param state 信息状态
     * @return JSON 文本
     * @throws IllegalArgumentException 状态为空
     */
    public static String toJson(State state) {
        requireState(state);
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("信息状态无法序列化", failure);
        }
    }

    /**
     * 从 JSON 还原信息状态；构造期校验仍生效，篡改后的版本断号、孤立经历或未登记发布者会被拒绝。
     *
     * @param json JSON 文本
     * @return 还原状态
     * @throws IllegalArgumentException JSON 为空或内容不满足内部不变量
     */
    public static State fromJson(String json) {
        requireText(json, "信息状态 JSON");
        try {
            return MAPPER.readValue(json, State.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("信息状态 JSON 无法还原", failure);
        }
    }

    // ---------------------------------------------------------------- 内部

    private static boolean visible(Audience audience, RoleAccess.State access, String personPartyId,
                                   long now, long docVersionPublishedAt, GroupAccess groups,
                                   BoardAccess boards) {
        switch (audience.kind()) {
            case PUBLIC:
                return true;
            case DIRECT:
                return audience.partyId().equals(personPartyId);
            case ORGANIZATION:
                return RoleAccess.authorize(access, personPartyId, Action.READ_ORGANIZATION_FILE,
                        Target.organization(audience.partyId()), now) == Decision.ALLOW;
            case GROUP:
                return groups.canRead(audience.partyId(), personPartyId, docVersionPublishedAt, now);
            case BOARD:
                return boards.canRead(audience.partyId(), personPartyId, now);
            default:
                throw new IllegalStateException("未知受众种类：" + audience.kind());
        }
    }

    private static void requireReading(String readId, String personPartyId, String docId, long version,
                                       long startedAt, long dueAt, List<Document> documents,
                                       List<String> persons) {
        if (!persons.contains(personPartyId)) {
            throw new IllegalArgumentException("读者未登记：" + personPartyId + "（" + readId + "）");
        }
        DocumentVersion frozen = versionOf(documents, docId, version);
        if (dueAt - startedAt != frozen.readDurationMillis()) {
            throw new IllegalArgumentException("阅读工时与版本固定耗时不一致：" + readId);
        }
    }

    private static DocumentVersion versionOf(List<Document> documents, String docId, long version) {
        Document document = findDocument(documents, docId);
        if (document == null) {
            throw new IllegalArgumentException("阅读引用了未登记资料：" + docId);
        }
        DocumentVersion frozen = document.at(version);
        if (frozen == null) {
            throw new IllegalArgumentException("阅读引用了未发布版本：" + docId + " v" + version);
        }
        return frozen;
    }

    /**
     * 管理只读：取某资料的确切已发布版本（用于运行层绑定发布正文，不进入任何角色投影）。
     *
     * @param state   信息状态
     * @param docId   资料标识
     * @param version 确切版本
     * @return 版本内容；资料或版本不存在返回空
     */
    static java.util.Optional<DocumentVersion> version(State state, String docId,
                                                       long version) {
        requireState(state);
        Document document = findDocument(state.documents(), docId);
        if (document == null) {
            return java.util.Optional.empty();
        }
        for (DocumentVersion candidate : document.versions()) {
            if (candidate.version() == version) {
                return java.util.Optional.of(candidate);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * 管理只读：资料当前的发布者与受众（发布前绑定校验用）。
     *
     * @param state 信息状态
     * @param docId 资料标识
     * @return 受众与发布者；不存在返回空
     */
    static java.util.Optional<Audience> audienceOf(State state, String docId) {
        requireState(state);
        Document document = findDocument(state.documents(), docId);
        return document == null ? java.util.Optional.empty()
                : java.util.Optional.of(document.audience());
    }

    private static Document findDocument(List<Document> documents, String docId) {
        for (Document document : documents) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }

    private static Reading findReading(List<Reading> active, String personPartyId, String readId) {
        for (Reading reading : active) {
            if (reading.readId().equals(readId) && reading.personPartyId().equals(personPartyId)) {
                return reading;
            }
        }
        return null;
    }

    private static ClosedReading findClosed(List<ClosedReading> closed, String readId) {
        for (ClosedReading reading : closed) {
            if (reading.readId().equals(readId)) {
                return reading;
            }
        }
        return null;
    }

    private static ReadObservation observationOf(List<ReadObservation> observations, String personPartyId,
                                                String docId, long version) {
        for (ReadObservation observation : observations) {
            if (observation.personPartyId().equals(personPartyId)
                    && observation.docId().equals(docId)
                    && observation.version() == version) {
                return observation;
            }
        }
        return null;
    }

    private static boolean hasObservation(List<ReadObservation> observations, String personPartyId,
                                          String docId, long version) {
        return observationOf(observations, personPartyId, docId, version) != null;
    }

    private static Party party(OpeningRegistry registry, String partyId) {
        for (Party party : registry.parties()) {
            if (party.partyId().equals(partyId)) {
                return party;
            }
        }
        return null;
    }

    private static List<String> internalIds(List<String> source) {
        List<String> copy = new ArrayList<>(Objects.requireNonNull(source, "内部源标识清单"));
        Set<String> distinct = new LinkedHashSet<>();
        for (String id : copy) {
            requireText(id, "内部源标识");
            distinct.add(id);
        }
        List<String> ordered = new ArrayList<>(distinct);
        ordered.sort(Comparator.naturalOrder());
        return List.copyOf(ordered);
    }

    private static List<String> sortedText(List<String> source, String field) {
        List<String> copy = new ArrayList<>(Objects.requireNonNull(source, field));
        for (String value : copy) {
            requireText(value, field);
        }
        copy.sort(Comparator.naturalOrder());
        Set<String> distinct = new LinkedHashSet<>(copy);
        if (distinct.size() != copy.size()) {
            throw new IllegalArgumentException(field + "不能重复");
        }
        return List.copyOf(copy);
    }

    private static <T> List<T> sorted(List<T> source, Comparator<T> order) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(source, "清单"));
        for (T item : copy) {
            Objects.requireNonNull(item, "清单元素");
        }
        copy.sort(order);
        return copy;
    }

    private static void requireState(State state) {
        if (state == null) {
            throw new IllegalArgumentException("信息状态不能为空");
        }
    }

    private static void requireAccess(RoleAccess.State access) {
        if (access == null) {
            throw new IllegalArgumentException("任职授权不能为空");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + "不能为负：" + value);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
