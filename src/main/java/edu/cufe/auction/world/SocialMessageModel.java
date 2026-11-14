package edu.cufe.auction.world;

import edu.cufe.auction.world.CognitionRecall.Policy;
import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.InformationModel.CloseReason;
import edu.cufe.auction.world.InformationModel.ClosedReading;
import edu.cufe.auction.world.InformationModel.Document;
import edu.cufe.auction.world.InformationModel.ReadObservation;
import edu.cufe.auction.world.InformationModel.VersionDraft;
import edu.cufe.auction.world.InterviewRun.ContactEntry;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * M4j.1 一对一消息纯核：写信工时、可达判定、投递与屏蔽，正文唯一权威仍是
 * {@link InformationModel} 的 DIRECT 文档。
 *
 * <p><b>能力边界</b>：本类只回答「本人此刻能否给这个人写一封一对一消息、写完何时可投递、
 * 投递后收件人看到什么信封、本人能否回复」。它不写世界日志、不排程、不读墙钟、不联网、
 * 不产生资金，也不实现群组、平台曝光、结构化转发或证据引用。所有消息都是
 * {@link #ORIGINAL_STATEMENT 未经核实的个人陈述}，正文里出现的人名或指令不构成联系授权，
 * 也不授予任何工具权限。</p>
 *
 * <p><b>正文口径</b>：社交 {@link State} 只保存元数据、屏蔽事实与正文摘要（SHA-256）。
 * 正文实体只在两处：投递前在调用方的 {@link PendingPayload}（运行桥的排程 payload，纯核不持久化），
 * 投递后只由 {@link InformationModel#publish} 产生的 DIRECT 文档保存。{@link #deliver} 用摘要
 * 核对 payload，因此「换信后投递」会被 {@link DeliverStatus#PAYLOAD_MISMATCH} 拒绝。</p>
 *
 * <p><b>可达口径</b>：只接受 DIRECT 一对一入口，来自 (a) 受信 {@link ContactEntry} 指向的
 * {@code docId/version} 本人已实际读完且当前可回忆，或 (b) 本人已实际读完且当前可回忆的
 * 已送达来信。未读目录、已遗忘、被屏蔽、未登记与普通任意文档都不构成入口；未知与已被屏蔽
 * 对发送者统一 {@link BeginStatus#UNREACHABLE}，outbox 失败统一
 * {@link OutboxStatus#UNDELIVERED}，不泄露屏蔽或对象存在性。</p>
 *
 * <p><b>工时口径</b>：写信用 {@link Config#sendMillis()} 正工时，占用区间
 * {@code [startedAt, completedAt)}；同一人未完成写信前不能再写，不同人互不阻塞。投递时刻
 * {@code dueAt = 实际完成时刻 + Config.deliveryMillis()}，{@code deliveryMillis >= 1}，
 * 不允许同刻回跳；{@code dueAt} 之前投递是 {@link DeliverStatus#NOT_DUE}。投递本身不占用
 * 任何人注意力，也不自动阅读。</p>
 *
 * <p><b>原子与幂等</b>：{@link #deliver} 成功时同时返回新的社交状态与
 * {@link InformationModel.State}；任何失败都返回输入的 Information 实例（不半改）。
 * {@code (caller, commandId)} 决定确定性的 {@link #messageId}，同内容重放返回
 * {@link BeginStatus#REPLAYED}/{@link DeliverStatus#REPLAYED} 与当前状态，不回滚新记录；
 * 同 commandId 异内容返回 {@link BeginStatus#COMMAND_CONFLICT}。所有时刻都是调用方显式传入的
 * 模拟毫秒，加法溢出拒绝，集合深拷贝并固定顺序。</p>
 */
public final class SocialMessageModel {

    /** 消息正文的固定口径：作者个人陈述，未经核实，不是世界真值。 */
    public static final String ORIGINAL_STATEMENT = "未经核实的个人陈述";

    /**
     * 投递命令的系统主体：投递由排程器发起，不取人物身份；该标识保留，不得作为人物标识使用。
     */
    public static final String DELIVERY_CALLER = "system:delivery";

    private static final String MESSAGE_PREFIX = "MSG-";
    private static final String DOC_PREFIX = "DOC-";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SocialMessageModel() {
    }

    /**
     * 模块配置：写信、投递等待与投递后阅读三类工时。
     *
     * @param sendMillis        写信正工时，占用发送者注意力
     * @param deliveryMillis    写完到可投递的最小等待，必须为正（不允许同刻回跳）
     * @param readDurationMillis 收件人读完一封来信所需的固定阅读工时，必须为正
     */
    public record Config(long sendMillis, long deliveryMillis, long readDurationMillis) {

        /** 校验三类工时都为正。 */
        public Config {
            if (sendMillis < 1) {
                throw new IllegalArgumentException("写信工时必须为正毫秒：" + sendMillis);
            }
            if (deliveryMillis < 1) {
                throw new IllegalArgumentException("投递等待必须为正毫秒：" + deliveryMillis);
            }
            if (readDurationMillis < 1) {
                throw new IllegalArgumentException("阅读工时必须为正毫秒：" + readDurationMillis);
            }
        }
    }

    /**
     * 受信上下文：开局登记、回忆参数与明确的联系入口集合。
     *
     * <p>联系入口只从调用方传入的受信清单读取；正文里出现人名不会自动生成入口。</p>
     *
     * @param registry 开局登记，用于判定双方是否为已登记普通人物
     * @param recall   回忆参数，用于判定入口文档/来信是否仍然可回忆
     * @param config   写信、投递等待与阅读三类受信工时
     * @param contacts 明确的联系入口，深拷贝并按目标/渠道/资料排序
     */
    public record Context(OpeningRegistry registry, Policy recall, Config config,
                          List<ContactEntry> contacts) {

        /** 深拷贝并排序联系入口。 */
        public Context {
            Objects.requireNonNull(registry, "开局登记");
            Objects.requireNonNull(recall, "回忆参数");
            Objects.requireNonNull(config, "模块配置");
            for (Party party : registry.parties()) {
                if (DELIVERY_CALLER.equals(party.partyId())) {
                    throw new IllegalArgumentException("开局登记不得把保留系统标识登记为人物："
                            + DELIVERY_CALLER);
                }
            }
            List<ContactEntry> copies = new ArrayList<>(Objects.requireNonNull(contacts, "联系入口"));
            for (ContactEntry entry : copies) {
                Objects.requireNonNull(entry, "联系入口");
            }
            copies.sort(Comparator.comparing(ContactEntry::targetPartyId)
                    .thenComparing(ContactEntry::channel)
                    .thenComparing(ContactEntry::docId)
                    .thenComparingLong(ContactEntry::version));
            contacts = List.copyOf(copies);
        }
    }

    /** 写信中的草稿：正文已被摘要冻结，实体只在调用方 payload 里。 */
    public record Draft(String messageId, String commandId, String from, String to, String channel,
                        String contentDigest, long startedAt, long completedAt) {

        /** 校验标识、摘要与工时区间。 */
        public Draft {
            requireText(messageId, "消息标识");
            requireText(commandId, "命令标识");
            requireText(from, "发送者");
            requireText(to, "收件人");
            requireText(channel, "联系渠道");
            requireText(contentDigest, "正文摘要");
            requireNonNegative(startedAt, "开始写信时刻");
            if (completedAt <= startedAt) {
                throw new IllegalArgumentException("完成写信时刻必须晚于开始：" + messageId);
            }
        }
    }

    /** 消息终态或进行态。 */
    public enum MessageStatus {
        /** 已写完，等待投递。 */
        QUEUED,
        /** 已投递：DIRECT 文档与元数据同时成立。 */
        DELIVERED,
        /** 投递复核时任一方已屏蔽，未发布。 */
        BLOCKED,
        /** 投递复核时目标不再合法（未登记/不在读者表），未发布。 */
        UNREACHABLE,
        /** 写完复核时可达已消失，终止且未发布。 */
        ABORTED
    }

    /**
     * 一条消息的元数据：不重复正文，只保存冻结摘要与时间。
     *
     * @param messageId     确定性消息标识
     * @param commandId     调用方命令标识
     * @param from          发送者
     * @param to            收件人
     * @param channel       联系渠道
     * @param contentDigest 标题/正文/收发方的 SHA-256 摘要
     * @param docId         派生文档标识，投递成功后才真实存在
     * @param sentAt        实际写完时刻
     * @param dueAt         计划可投递时刻；{@link MessageStatus#ABORTED} 时等于 {@code sentAt}
     * @param status        当前状态
     * @param statusAt      {@link MessageStatus#QUEUED} 时等于 {@code sentAt}，否则为终态时刻
     */
    public record Message(String messageId, String commandId, String from, String to, String channel,
                          String contentDigest, String docId, long sentAt, long dueAt,
                          MessageStatus status, long statusAt) {

        /** 校验标识、摘要、文档派生与时间单调。 */
        public Message {
            requireText(messageId, "消息标识");
            requireText(commandId, "命令标识");
            requireText(from, "发送者");
            requireText(to, "收件人");
            requireText(channel, "联系渠道");
            requireText(contentDigest, "正文摘要");
            requireText(docId, "文档标识");
            Objects.requireNonNull(status, "消息状态");
            requireNonNegative(sentAt, "写完时刻");
            requireNonNegative(dueAt, "计划投递时刻");
            requireNonNegative(statusAt, "状态时刻");
            if (!docId.equals(docIdFor(messageId))) {
                throw new IllegalArgumentException("文档标识必须由消息标识派生：" + messageId);
            }
            if (status == MessageStatus.ABORTED) {
                if (dueAt < sentAt || statusAt < sentAt) {
                    throw new IllegalArgumentException("终止消息的时刻不能倒退：" + messageId);
                }
            } else if (dueAt <= sentAt) {
                throw new IllegalArgumentException("计划投递时刻必须晚于写完时刻：" + messageId);
            } else if (status == MessageStatus.QUEUED) {
                if (statusAt != sentAt) {
                    throw new IllegalArgumentException("排队消息的状态时刻必须等于写完时刻：" + messageId);
                }
            } else if (statusAt < dueAt) {
                throw new IllegalArgumentException("终态不能早于计划投递时刻：" + messageId);
            }
        }
    }

    /** 屏蔽事实：本人对目标的通用偏好，不分渠道，只影响以后的投递。 */
    public record Block(String ownerPartyId, String targetPartyId, long since, String commandId) {

        /** 校验字段。 */
        public Block {
            requireText(ownerPartyId, "屏蔽发起人");
            requireText(targetPartyId, "屏蔽目标");
            requireNonNegative(since, "屏蔽时刻");
            requireText(commandId, "命令标识");
            if (ownerPartyId.equals(targetPartyId)) {
                throw new IllegalArgumentException("不能屏蔽自己：" + ownerPartyId);
            }
        }
    }

    /** 命令种类：一条命令只属于一种动作，跨种类复用同一 commandId 视为冲突。 */
    public enum CommandKind {
        /** 开始写信。 */
        SEND,
        /** 写完。 */
        FINISH,
        /** 投递（系统排程动作）。 */
        DELIVER,
        /** 屏蔽。 */
        BLOCK,
        /** 解除屏蔽。 */
        UNBLOCK
    }

    /**
     * 已归档的命令裁决：每个 mutation 只产生一次业务裁决，重放只复述它。
     *
     * <p>{@code (caller, commandId)} 是唯一键；{@code subject} 是被操作对象（消息标识或目标人物），
     * {@code result} 是当次状态的枚举名。{@link #at()} 是命令被世界时钟接受的真实时刻，不得补过去。</p>
     *
     * @param caller        调用者（投递固定为 {@link SocialMessageModel#DELIVERY_CALLER}）
     * @param commandId     命令标识
     * @param kind          命令种类
     * @param subject       被操作对象
     * @param contentDigest 内容摘要；只有开始写信与投递携带，其余为空串
     * @param result        当次业务裁决的枚举名
     * @param at            命令被接受的时刻
     */
    public record Command(String caller, String commandId, CommandKind kind, String subject,
                          String contentDigest, String result, long at) {

        /** 校验字段与内容摘要的携带规则。 */
        public Command {
            requireText(caller, "命令调用者");
            requireText(commandId, "命令标识");
            Objects.requireNonNull(kind, "命令种类");
            requireText(subject, "命令对象");
            requireText(result, "命令结果");
            requireNonNegative(at, "命令时刻");
            Objects.requireNonNull(contentDigest, "命令内容摘要");
            if (kind == CommandKind.SEND || kind == CommandKind.DELIVER) {
                requireText(contentDigest, "命令内容摘要");
            } else if (!contentDigest.isEmpty()) {
                throw new IllegalArgumentException("该命令种类不携带内容摘要：" + kind);
            }
        }
    }

    /**
     * 社交状态：写信中的草稿、消息元数据、屏蔽事实、命令裁决表与世界时钟水位。
     *
     * <p>不含正文、余额、阅读记录或他人收件人；构造期校验标识唯一、文档派生、排序与时间水位。</p>
     *
     * @param drafts   写信中的草稿，按消息标识排序
     * @param messages 消息元数据，按消息标识排序
     * @param blocks   屏蔽事实，按发起人/目标排序
     * @param commands 命令裁决，按调用者/命令标识排序
     * @param clock    全局单调时间水位：任何已记录事实都不晚于它
     */
    public record State(List<Draft> drafts, List<Message> messages, List<Block> blocks,
                        List<Command> commands, long clock) {

        /** 深拷贝、排序并校验内部不变量。 */
        public State {
            requireNonNegative(clock, "时间水位");
            List<Draft> draftList = sorted(drafts, Comparator.comparing(Draft::messageId));
            Set<String> draftIds = new LinkedHashSet<>();
            for (Draft draft : draftList) {
                if (!draftIds.add(draft.messageId())) {
                    throw new IllegalArgumentException("草稿标识重复：" + draft.messageId());
                }
                if (draft.startedAt() > clock) {
                    throw new IllegalArgumentException("草稿开始时刻晚于时间水位：" + draft.messageId());
                }
            }
            List<Message> messageList = sorted(messages, Comparator.comparing(Message::messageId));
            Set<String> messageIds = new LinkedHashSet<>();
            for (Message message : messageList) {
                if (!messageIds.add(message.messageId())) {
                    throw new IllegalArgumentException("消息标识重复：" + message.messageId());
                }
                if (draftIds.contains(message.messageId())) {
                    throw new IllegalArgumentException("同一消息不能同时是草稿与已写消息："
                            + message.messageId());
                }
                if (message.sentAt() > clock) {
                    throw new IllegalArgumentException("消息写完时刻晚于时间水位：" + message.messageId());
                }
            }
            List<Block> blockList = sorted(blocks, Comparator.comparing(Block::ownerPartyId)
                    .thenComparing(Block::targetPartyId));
            Set<String> pairs = new LinkedHashSet<>();
            for (Block block : blockList) {
                if (!pairs.add(block.ownerPartyId() + "\u0000" + block.targetPartyId())) {
                    throw new IllegalArgumentException("重复屏蔽：" + block.ownerPartyId()
                            + " -> " + block.targetPartyId());
                }
                if (block.since() > clock) {
                    throw new IllegalArgumentException("屏蔽时刻晚于时间水位：" + block.ownerPartyId());
                }
            }
            List<Command> commandList = sorted(commands, Comparator.comparing(Command::caller)
                    .thenComparing(Command::commandId));
            Set<String> keys = new LinkedHashSet<>();
            for (Command command : commandList) {
                if (!keys.add(command.caller() + "\u0000" + command.commandId())) {
                    throw new IllegalArgumentException("命令标识重复：" + command.caller() + " / "
                            + command.commandId());
                }
                if (command.at() > clock) {
                    throw new IllegalArgumentException("命令时刻晚于时间水位：" + command.commandId());
                }
            }
            for (Draft draft : draftList) {
                requireSendRecord(commandList, draft.from(), draft.messageId(),
                        draft.contentDigest());
            }
            for (Message message : messageList) {
                requireSendRecord(commandList, message.from(), message.messageId(),
                        message.contentDigest());
            }
            drafts = List.copyOf(draftList);
            messages = List.copyOf(messageList);
            blocks = List.copyOf(blockList);
            commands = List.copyOf(commandList);
        }
    }

    /**
     * 待投递 payload：正文实体唯一允许的投递前位置，由运行桥持久化在排程任务里。
     *
     * @param messageId 消息标识
     * @param docId     派生文档标识
     * @param from      发送者
     * @param to        收件人
     * @param channel   联系渠道
     * @param title     信封标题
     * @param body      正文；未经核实的个人陈述
     */
    public record PendingPayload(String messageId, String docId, String from, String to, String channel,
                                 String title, String body) {

        /** 校验字段与文档派生。 */
        public PendingPayload {
            requireText(messageId, "消息标识");
            requireText(docId, "文档标识");
            requireText(from, "发送者");
            requireText(to, "收件人");
            requireText(channel, "联系渠道");
            requireText(title, "标题");
            requireText(body, "正文");
            if (!docId.equals(docIdFor(messageId))) {
                throw new IllegalArgumentException("文档标识必须由消息标识派生：" + messageId);
            }
        }
    }

    /** 收件箱信封：无正文、无标题副本、不含他人收件人。 */
    public record EnvelopeView(String messageId, String docId, long version, String from,
                               String channel, long deliveredAt) {

        /** 校验字段。 */
        public EnvelopeView {
            requireText(messageId, "消息标识");
            requireText(docId, "文档标识");
            requireText(from, "发送者");
            requireText(channel, "联系渠道");
            if (version < 1) {
                throw new IllegalArgumentException("信封版本必须从 1 开始：" + version);
            }
            requireNonNegative(deliveredAt, "投递时刻");
        }
    }

    /** 发件箱投递结果；失败一律 {@link OutboxStatus#UNDELIVERED}，不暴露屏蔽或目标存在性。 */
    public enum OutboxStatus {
        /** 已写完等待投递。 */
        QUEUED,
        /** 已真实投递。 */
        DELIVERED,
        /** 未投递：屏蔽、目标失效或终止，对外不区分。 */
        UNDELIVERED
    }

    /** 发件箱条目：本人已写消息的投递结果，无正文、无屏蔽原因。 */
    public record OutboxView(String messageId, String to, String channel, long sentAt, long dueAt,
                             OutboxStatus status) {

        /** 校验字段。 */
        public OutboxView {
            requireText(messageId, "消息标识");
            requireText(to, "收件人");
            requireText(channel, "联系渠道");
            requireNonNegative(sentAt, "写完时刻");
            requireNonNegative(dueAt, "计划投递时刻");
            Objects.requireNonNull(status, "发件箱状态");
        }
    }

    /** 开始写信的结果状态。 */
    public enum BeginStatus {
        /** 新草稿已建立。 */
        STARTED,
        /** 保留常量（v2 兼容）：v3 起正常入口不会返回，重放返回原裁决 STARTED。 */
        REPLAYED,
        /** 无可达入口、被屏蔽、未知目标或非普通人物，统一拒绝。 */
        UNREACHABLE,
        /** 本人已有未完成写信。 */
        BUSY,
        /** 同 commandId 异内容或跨种类/对象复用。 */
        COMMAND_CONFLICT,
        /** 写信工时加法溢出。 */
        TIME_OVERFLOW,
        /** 新动作时刻早于世界时钟水位。 */
        TIME_REVERSED
    }

    /**
     * 开始写信的结果。
     *
     * @param status  结果状态
     * @param state   操作后的社交状态；被拒绝且无变化时等于输入状态
     * @param payload 只有 {@link BeginStatus#STARTED}/{@link BeginStatus#REPLAYED} 时非空
     */
    public record BeginOutcome(BeginStatus status, State state, PendingPayload payload) {

        /** 校验状态与 payload 的对应关系。 */
        public BeginOutcome {
            Objects.requireNonNull(status, "结果状态");
            Objects.requireNonNull(state, "社交状态");
            boolean carries = status == BeginStatus.STARTED || status == BeginStatus.REPLAYED;
            if (carries != (payload != null)) {
                throw new IllegalArgumentException("只有开始/重放结果才能携带待投递 payload：" + status);
            }
        }
    }

    /** 写完的结果状态。 */
    public enum FinishStatus {
        /** 已写完，等待投递。 */
        QUEUED,
        /** 该消息已有结果，重放返回当前状态。 */
        REPLAYED,
        /** 尚未到达开始+写信工时。 */
        NOT_DUE,
        /** 不是本人的未完成草稿。 */
        NOT_FOUND,
        /** 完成时可达已消失，终止且不发布。 */
        ABORTED,
        /** 同 commandId 跨种类或改投别的消息。 */
        COMMAND_CONFLICT,
        /** 投递等待加法溢出，状态不变。 */
        TIME_OVERFLOW,
        /** 新动作时刻早于世界时钟水位。 */
        TIME_REVERSED
    }

    /**
     * 写完的结果。
     *
     * @param status 结果状态
     * @param state  操作后的社交状态；被拒绝且无变化时等于输入状态
     */
    public record FinishOutcome(FinishStatus status, State state) {

        /** 校验字段。 */
        public FinishOutcome {
            Objects.requireNonNull(status, "结果状态");
            Objects.requireNonNull(state, "社交状态");
        }
    }

    /** 投递的结果状态。 */
    public enum DeliverStatus {
        /** 已投递：DIRECT 文档与元数据同时成立。 */
        DELIVERED,
        /** 已投递消息的重放，返回当前状态与同一 Information 实例。 */
        REPLAYED,
        /** 尚未到达计划投递时刻。 */
        NOT_DUE,
        /** 未知消息标识。 */
        UNKNOWN,
        /** payload 与冻结摘要/元数据不一致（换信）。 */
        PAYLOAD_MISMATCH,
        /** 派生文档标识已存在，内部不一致，拒绝且不改两侧。 */
        DOC_CONFLICT,
        /** 投递复核时任一方已屏蔽，未发布。 */
        BLOCKED,
        /** 该消息在写完复核时已终止，永不投递。 */
        ABORTED,
        /** 投递复核时目标不再合法，未发布。 */
        UNREACHABLE,
        /** 同 commandId 换 payload 重放。 */
        COMMAND_CONFLICT,
        /** 新动作时刻早于世界时钟水位。 */
        TIME_REVERSED
    }

    /**
     * 投递的结果。
     *
     * @param status      结果状态
     * @param social      投递后的社交状态
     * @param information 投递后的信息状态；未发布时是输入同一实例
     * @param message     被处理的消息；{@link DeliverStatus#UNKNOWN} 与
     *                    {@link DeliverStatus#COMMAND_CONFLICT}（原命令未解析到消息）时可为空
     */
    public record DeliverOutcome(DeliverStatus status, State social,
                                 InformationModel.State information, Message message) {

        /** 校验状态与消息的对应关系。 */
        public DeliverOutcome {
            Objects.requireNonNull(status, "结果状态");
            Objects.requireNonNull(social, "社交状态");
            Objects.requireNonNull(information, "信息状态");
            boolean mayBeEmpty = status == DeliverStatus.UNKNOWN
                    || status == DeliverStatus.COMMAND_CONFLICT;
            if (!mayBeEmpty && message == null) {
                throw new IllegalArgumentException("该投递结果必须携带消息元数据：" + status);
            }
        }
    }

    /** 屏蔽的结果状态。 */
    public enum BlockStatus {
        /** 新屏蔽已记录。 */
        BLOCKED,
        /** 此前已屏蔽，返回当前状态。 */
        ALREADY_BLOCKED,
        /** 不能屏蔽自己。 */
        SELF,
        /** 新动作时刻早于世界时钟水位。 */
        TIME_REVERSED,
        /** 同 commandId 跨种类/对象复用或异内容。 */
        COMMAND_CONFLICT,
        /** 调用者不是已登记人物，不写任何记录。 */
        UNKNOWN_CALLER
    }

    /**
     * 屏蔽的结果。
     *
     * @param status 结果状态
     * @param state  操作后的社交状态
     */
    public record BlockOutcome(BlockStatus status, State state) {

        /** 校验字段。 */
        public BlockOutcome {
            Objects.requireNonNull(status, "结果状态");
            Objects.requireNonNull(state, "社交状态");
        }
    }

    /** 解除屏蔽的结果状态。 */
    public enum UnblockStatus {
        /** 已解除。 */
        UNBLOCKED,
        /** 此前未屏蔽。 */
        NOT_BLOCKED,
        /** 不能对自己解除屏蔽。 */
        SELF,
        /** 新动作时刻早于世界时钟水位。 */
        TIME_REVERSED,
        /** 同 commandId 跨种类/对象复用或异内容。 */
        COMMAND_CONFLICT,
        /** 调用者不是已登记人物，不写任何记录。 */
        UNKNOWN_CALLER
    }

    /**
     * 解除屏蔽的结果。
     *
     * @param status 结果状态
     * @param state  操作后的社交状态
     */
    public record UnblockOutcome(UnblockStatus status, State state) {

        /** 校验字段。 */
        public UnblockOutcome {
            Objects.requireNonNull(status, "结果状态");
            Objects.requireNonNull(state, "社交状态");
        }
    }

    // ---------------------------------------------------------------- 对外操作

    /**
     * 空社交状态。
     *
     * @return 没有草稿、消息或屏蔽的状态
     */
    public static State open() {
        return new State(List.of(), List.of(), List.of(), List.of(), 0L);
    }

    /**
     * 消息标识命名空间：同一人物同一 commandId 恒定，不同人物/命令靠长度前缀互不碰撞。
     *
     * @param caller    调用者
     * @param commandId 命令标识
     * @return 确定性消息标识
     */
    public static String messageId(String caller, String commandId) {
        requireText(caller, "发送者");
        requireText(commandId, "命令标识");
        return MESSAGE_PREFIX + caller.length() + ":" + caller + ":" + commandId.length() + ":"
                + commandId;
    }

    /**
     * 派生文档标识；投递成功前该文档不存在，猜它读也只会得到不可用。
     *
     * @param messageId 消息标识
     * @return 文档标识
     */
    public static String docIdFor(String messageId) {
        requireText(messageId, "消息标识");
        return DOC_PREFIX + messageId;
    }

    /**
     * 可达判定：双方已登记且在读者表、互不屏蔽，且有实际读完且当前可回忆的入口。
     *
     * @param social  社交状态
     * @param info    信息状态
     * @param context 受信上下文
     * @param from    发送者
     * @param to      收件人
     * @param channel 联系渠道
     * @param now     判定时刻
     * @return 可以据此写信返回 true
     */
    public static boolean reachable(State social, InformationModel.State info, Context context,
                                    String from, String to, String channel, long now) {
        requireState(social);
        requireInfo(info);
        requireContext(context);
        requireText(from, "发送者");
        requireText(to, "收件人");
        requireText(channel, "联系渠道");
        requireNonNegative(now, "判定时刻");
        return reachableInternal(social, info, context, from, to, channel, now);
    }

    /**
     * 开始写信：验证可达、单焦点、命令一致与工时，保留摘要并返回唯一待投递 payload。
     *
     * @param social    当前社交状态
     * @param info      当前信息状态
     * @param context   受信上下文
     * @param caller    发送者本人
     * @param commandId 命令标识
     * @param to        收件人
     * @param channel   联系渠道，必须与受信入口渠道一致
     * @param title     信封标题
     * @param body      正文；未经核实的个人陈述
     * @param now       开始时刻
     * @return 结果；成功时新状态含一条草稿
     */
    public static BeginOutcome beginSend(State social, InformationModel.State info, Context context,
                                         String caller, String commandId, String to, String channel,
                                         String title, String body, long now) {
        requireState(social);
        requireInfo(info);
        requireContext(context);
        requireText(caller, "发送者");
        requireText(commandId, "命令标识");
        requireText(to, "收件人");
        requireText(channel, "联系渠道");
        requireText(title, "标题");
        requireText(body, "正文");
        requireNonNegative(now, "开始写信时刻");

        String messageId = messageId(caller, commandId);
        String digest = digest(messageId, caller, to, channel, title, body);
        PendingPayload payload = new PendingPayload(messageId, docIdFor(messageId), caller, to,
                channel, title, body);

        Command recorded = command(social, caller, commandId);
        if (recorded != null) {
            if (recorded.kind() != CommandKind.SEND || !recorded.subject().equals(messageId)
                    || !recorded.contentDigest().equals(digest)) {
                return new BeginOutcome(BeginStatus.COMMAND_CONFLICT, social, null);
            }
            BeginStatus replay = BeginStatus.valueOf(recorded.result());
            return new BeginOutcome(replay, social,
                    replay == BeginStatus.STARTED ? payload : null);
        }
        if (!registeredPerson(context.registry(), caller)) {
            // 未知调用者不写任何记录：State 不能被用来探测某个 caller 是否存在
            return new BeginOutcome(BeginStatus.UNREACHABLE, social, null);
        }
        if (now < social.clock()) {
            return new BeginOutcome(BeginStatus.TIME_REVERSED, social, null);
        }
        for (Draft other : social.drafts()) {
            if (other.from().equals(caller)) {
                return new BeginOutcome(BeginStatus.BUSY, archive(social, caller, commandId,
                        CommandKind.SEND, messageId, digest, BeginStatus.BUSY, now), null);
            }
        }
        if (!reachableInternal(social, info, context, caller, to, channel, now)) {
            return new BeginOutcome(BeginStatus.UNREACHABLE, archive(social, caller, commandId,
                    CommandKind.SEND, messageId, digest, BeginStatus.UNREACHABLE, now), null);
        }
        long completedAt = now + context.config().sendMillis();
        if (completedAt < now) {
            return new BeginOutcome(BeginStatus.TIME_OVERFLOW, archive(social, caller, commandId,
                    CommandKind.SEND, messageId, digest, BeginStatus.TIME_OVERFLOW, now), null);
        }
        List<Draft> drafts = new ArrayList<>(social.drafts());
        drafts.add(new Draft(messageId, commandId, caller, to, channel, digest, now, completedAt));
        List<Command> commands = new ArrayList<>(social.commands());
        commands.add(new Command(caller, commandId, CommandKind.SEND, messageId, digest,
                BeginStatus.STARTED.name(), now));
        State next = new State(drafts, social.messages(), social.blocks(), commands, now);
        return new BeginOutcome(BeginStatus.STARTED, next, payload);
    }

    /**
     * 写完：不得早于开始+写信工时；完成时重新核可达，失败终止且不发布。
     *
     * <p>每条命令只裁决一次：同 {@code (caller, commandId)} 重放返回当时的裁决与<b>当前</b>状态；
     * 同 commandId 跨种类/对象或改投别的消息是 {@link FinishStatus#COMMAND_CONFLICT}。
     * 需要重试必须换 commandId。</p>
     *
     * @param social    当前社交状态
     * @param info      当前信息状态
     * @param context   受信上下文
     * @param caller    发送者本人
     * @param commandId 命令标识
     * @param messageId 消息标识
     * @param now       写完时刻
     * @return 结果；成功时草稿转为 {@link MessageStatus#QUEUED} 元数据
     */
    public static FinishOutcome finishSend(State social, InformationModel.State info, Context context,
                                           String caller, String commandId, String messageId,
                                           long now) {
        requireState(social);
        requireInfo(info);
        requireContext(context);
        requireText(caller, "发送者");
        requireText(commandId, "命令标识");
        requireText(messageId, "消息标识");
        requireNonNegative(now, "写完时刻");

        Command recorded = command(social, caller, commandId);
        if (recorded != null) {
            if (recorded.kind() != CommandKind.FINISH || !recorded.subject().equals(messageId)) {
                return new FinishOutcome(FinishStatus.COMMAND_CONFLICT, social);
            }
            return new FinishOutcome(FinishStatus.valueOf(recorded.result()), social);
        }
        if (!registeredPerson(context.registry(), caller)) {
            // 身份门先于命令表：未登记调用者不归档，避免留下可探测的私有记录
            return new FinishOutcome(FinishStatus.NOT_FOUND, social);
        }
        if (now < social.clock()) {
            return new FinishOutcome(FinishStatus.TIME_REVERSED, social);
        }
        Message existing = message(social, messageId);
        if (existing != null) {
            FinishStatus answer = existing.from().equals(caller) ? FinishStatus.REPLAYED
                    : FinishStatus.NOT_FOUND;
            return new FinishOutcome(answer, archive(social, caller, commandId,
                    CommandKind.FINISH, messageId, "", answer, now));
        }
        Draft draft = draft(social, messageId);
        if (draft == null || !draft.from().equals(caller)) {
            return new FinishOutcome(FinishStatus.NOT_FOUND, archive(social, caller, commandId,
                    CommandKind.FINISH, messageId, "", FinishStatus.NOT_FOUND, now));
        }
        if (now < draft.completedAt()) {
            return new FinishOutcome(FinishStatus.NOT_DUE, social);
        }
        List<Draft> drafts = new ArrayList<>(social.drafts());
        drafts.remove(draft);
        List<Message> messages = new ArrayList<>(social.messages());
        if (!reachableInternal(social, info, context, caller, draft.to(), draft.channel(), now)) {
            messages.add(new Message(messageId, draft.commandId(), draft.from(), draft.to(),
                    draft.channel(), draft.contentDigest(), docIdFor(messageId), now, now,
                    MessageStatus.ABORTED, now));
            State next = archive(new State(drafts, messages, social.blocks(), social.commands(),
                    now), caller, commandId, CommandKind.FINISH, messageId, "",
                    FinishStatus.ABORTED, now);
            return new FinishOutcome(FinishStatus.ABORTED, next);
        }
        long dueAt = now + context.config().deliveryMillis();
        if (dueAt < now) {
            return new FinishOutcome(FinishStatus.TIME_OVERFLOW, archive(social, caller, commandId,
                    CommandKind.FINISH, messageId, "", FinishStatus.TIME_OVERFLOW, now));
        }
        messages.add(new Message(messageId, draft.commandId(), draft.from(), draft.to(),
                draft.channel(), draft.contentDigest(), docIdFor(messageId), now, dueAt,
                MessageStatus.QUEUED, now));
        State next = archive(new State(drafts, messages, social.blocks(), social.commands(),
                now), caller, commandId, CommandKind.FINISH, messageId, "",
                FinishStatus.QUEUED, now);
        return new FinishOutcome(FinishStatus.QUEUED, next);
    }

    /**
     * 投递：不早于 {@code dueAt}，用摘要核对 payload，再核双方屏蔽与目标合法。
     *
     * <p>成功时同时返回新的信息状态（DIRECT 文档）与 {@link MessageStatus#DELIVERED} 元数据；
     * 任何失败都不发布文档，且返回输入的 Information 实例。投递是系统排程动作，
     * 调用者为 {@link #DELIVERY_CALLER}，命令结果是 DELIVERED/BLOCKED/UNREACHABLE 之一并归档；
     * 同 commandId 换 payload 是 {@link DeliverStatus#COMMAND_CONFLICT}，需要新尝试请换 commandId。
     * {@code NOT_DUE} 与 {@code PAYLOAD_MISMATCH}/{@code DOC_CONFLICT} 不归档、不改状态：
     * 它们不是业务裁决，同一消息在到期后用正确 payload 仍可投递。</p>
     *
     * @param social    当前社交状态
     * @param info      当前信息状态
     * @param context   受信上下文
     * @param commandId 排程命令标识
     * @param payload   调用方排程 payload，必须与冻结摘要一致
     * @param now       投递时刻
     * @return 结果
     */
    public static DeliverOutcome deliver(State social, InformationModel.State info, Context context,
                                         String commandId, PendingPayload payload, long now) {
        requireState(social);
        requireInfo(info);
        requireContext(context);
        requireText(commandId, "命令标识");
        Objects.requireNonNull(payload, "待投递 payload");
        requireNonNegative(now, "投递时刻");

        String payloadDigest = digest(payload.messageId(), payload.from(), payload.to(),
                payload.channel(), payload.title(), payload.body());
        Command recorded = command(social, DELIVERY_CALLER, commandId);
        if (recorded != null) {
            if (recorded.kind() != CommandKind.DELIVER
                    || !recorded.subject().equals(payload.messageId())
                    || !recorded.contentDigest().equals(payloadDigest)) {
                // 冲突时可能根本没有对应消息（原命令得到的是 UNKNOWN），因此元数据允许为空
                return new DeliverOutcome(DeliverStatus.COMMAND_CONFLICT, social, info,
                        message(social, payload.messageId()));
            }
            return new DeliverOutcome(DeliverStatus.valueOf(recorded.result()), social, info,
                    message(social, recorded.subject()));
        }
        Message message = message(social, payload.messageId());
        if (message == null) {
            // UNKNOWN 也是已登记投递主体的业务答复：归档后同 ID 不能换 payload 重试
            return new DeliverOutcome(DeliverStatus.UNKNOWN, archive(social, DELIVERY_CALLER,
                    commandId, CommandKind.DELIVER, payload.messageId(), payloadDigest,
                    DeliverStatus.UNKNOWN, now), info, null);
        }
        if (now < social.clock()) {
            return new DeliverOutcome(DeliverStatus.TIME_REVERSED, social, info, message);
        }
        if (!consistent(message, payload)) {
            return new DeliverOutcome(DeliverStatus.PAYLOAD_MISMATCH, social, info, message);
        }
        switch (message.status()) {
            case DELIVERED:
                return new DeliverOutcome(DeliverStatus.REPLAYED, archive(social, DELIVERY_CALLER,
                        commandId, CommandKind.DELIVER, message.messageId(), payloadDigest,
                        DeliverStatus.REPLAYED, now), info, message);
            case BLOCKED:
                return new DeliverOutcome(DeliverStatus.BLOCKED, archive(social, DELIVERY_CALLER,
                        commandId, CommandKind.DELIVER, message.messageId(), payloadDigest,
                        DeliverStatus.BLOCKED, now), info, message);
            case UNREACHABLE:
                return new DeliverOutcome(DeliverStatus.UNREACHABLE, archive(social,
                        DELIVERY_CALLER, commandId, CommandKind.DELIVER, message.messageId(),
                        payloadDigest, DeliverStatus.UNREACHABLE, now), info, message);
            case ABORTED:
                return new DeliverOutcome(DeliverStatus.ABORTED, archive(social, DELIVERY_CALLER,
                        commandId, CommandKind.DELIVER, message.messageId(), payloadDigest,
                        DeliverStatus.ABORTED, now), info, message);
            case QUEUED:
                break;
            default:
                throw new IllegalStateException("未知消息状态：" + message.status());
        }
        if (now < message.dueAt()) {
            return new DeliverOutcome(DeliverStatus.NOT_DUE, social, info, message);
        }
        if (blocked(social, message.from(), message.to())
                || blocked(social, message.to(), message.from())) {
            Message settled = resettle(message, MessageStatus.BLOCKED, now);
            State next = archive(withMessage(social, settled), DELIVERY_CALLER, commandId,
                    CommandKind.DELIVER, message.messageId(), payloadDigest,
                    DeliverStatus.BLOCKED, now);
            return new DeliverOutcome(DeliverStatus.BLOCKED, next, info, settled);
        }
        if (!registeredPerson(context.registry(), message.to())
                || !registeredPerson(context.registry(), message.from())
                || !info.persons().contains(message.to())
                || !info.persons().contains(message.from())) {
            Message settled = resettle(message, MessageStatus.UNREACHABLE, now);
            State next = archive(withMessage(social, settled), DELIVERY_CALLER, commandId,
                    CommandKind.DELIVER, message.messageId(), payloadDigest,
                    DeliverStatus.UNREACHABLE, now);
            return new DeliverOutcome(DeliverStatus.UNREACHABLE, next, info, settled);
        }
        if (findDocument(info.documents(), message.docId()) != null) {
            return new DeliverOutcome(DeliverStatus.DOC_CONFLICT, social, info, message);
        }
        InformationModel.State published = InformationModel.publish(info, message.docId(),
                message.from(), Audience.direct(message.to()),
                new VersionDraft(payload.title(), payload.body(), ORIGINAL_STATEMENT, now,
                        context.config().readDurationMillis(), List.of()));
        Message settled = resettle(message, MessageStatus.DELIVERED, now);
        State next = archive(withMessage(social, settled), DELIVERY_CALLER, commandId,
                CommandKind.DELIVER, message.messageId(), payloadDigest,
                DeliverStatus.DELIVERED, now);
        return new DeliverOutcome(DeliverStatus.DELIVERED, next, published, settled);
    }

    /**
     * 屏蔽：只改本人对目标的偏好，不校验目标存在性，因此不泄露未知对象。
     *
     * <p>命令裁决归档：同 {@code (caller, commandId)} 重放返回当时的 BlockStatus 与<b>当前</b>状态，
     * 不会重新写入已解除的屏蔽；同 commandId 换 target 或跨种类是
     * {@link BlockStatus#COMMAND_CONFLICT}。调用者必须是已登记人物，否则
     * {@link BlockStatus#UNKNOWN_CALLER}，且不写任何记录。</p>
     *
     * @param social    当前社交状态
     * @param registry  开局登记，用于确认调用者是已登记人物
     * @param caller    屏蔽发起人本人
     * @param commandId 命令标识
     * @param target    被屏蔽目标
     * @param now       屏蔽时刻
     * @return 结果；已屏蔽时返回当前状态
     */
    public static BlockOutcome block(State social, OpeningRegistry registry, String caller,
                                     String commandId, String target, long now) {
        requireState(social);
        Objects.requireNonNull(registry, "开局登记");
        requireText(caller, "屏蔽发起人");
        requireText(commandId, "命令标识");
        requireText(target, "屏蔽目标");
        requireNonNegative(now, "屏蔽时刻");

        Command recorded = command(social, caller, commandId);
        if (recorded != null) {
            if (recorded.kind() != CommandKind.BLOCK || !recorded.subject().equals(target)) {
                return new BlockOutcome(BlockStatus.COMMAND_CONFLICT, social);
            }
            return new BlockOutcome(BlockStatus.valueOf(recorded.result()), social);
        }
        if (!registeredPerson(registry, caller)) {
            return new BlockOutcome(BlockStatus.UNKNOWN_CALLER, social);
        }
        if (now < social.clock()) {
            return new BlockOutcome(BlockStatus.TIME_REVERSED, social);
        }
        if (caller.equals(target)) {
            return new BlockOutcome(BlockStatus.SELF, archive(social, caller, commandId,
                    CommandKind.BLOCK, target, "", BlockStatus.SELF, now));
        }
        Block existing = block(social, caller, target);
        if (existing != null) {
            return new BlockOutcome(BlockStatus.ALREADY_BLOCKED, archive(social, caller, commandId,
                    CommandKind.BLOCK, target, "", BlockStatus.ALREADY_BLOCKED, now));
        }
        List<Block> blocks = new ArrayList<>(social.blocks());
        blocks.add(new Block(caller, target, now, commandId));
        State next = archive(new State(social.drafts(), social.messages(), blocks,
                social.commands(), now), caller, commandId, CommandKind.BLOCK, target,
                "", BlockStatus.BLOCKED, now);
        return new BlockOutcome(BlockStatus.BLOCKED, next);
    }

    /**
     * 解除屏蔽：未屏蔽时幂等返回当前状态；重放不会让已解除的屏蔽复活。
     *
     * @param social    当前社交状态
     * @param registry  开局登记，用于确认调用者是已登记人物
     * @param caller    屏蔽发起人本人
     * @param commandId 命令标识
     * @param target    被屏蔽目标
     * @param now       解除时刻
     * @return 结果
     */
    public static UnblockOutcome unblock(State social, OpeningRegistry registry, String caller,
                                         String commandId, String target, long now) {
        requireState(social);
        Objects.requireNonNull(registry, "开局登记");
        requireText(caller, "解除发起人");
        requireText(commandId, "命令标识");
        requireText(target, "解除目标");
        requireNonNegative(now, "解除时刻");

        Command recorded = command(social, caller, commandId);
        if (recorded != null) {
            if (recorded.kind() != CommandKind.UNBLOCK || !recorded.subject().equals(target)) {
                return new UnblockOutcome(UnblockStatus.COMMAND_CONFLICT, social);
            }
            return new UnblockOutcome(UnblockStatus.valueOf(recorded.result()), social);
        }
        if (!registeredPerson(registry, caller)) {
            return new UnblockOutcome(UnblockStatus.UNKNOWN_CALLER, social);
        }
        if (now < social.clock()) {
            return new UnblockOutcome(UnblockStatus.TIME_REVERSED, social);
        }
        if (caller.equals(target)) {
            return new UnblockOutcome(UnblockStatus.SELF, archive(social, caller, commandId,
                    CommandKind.UNBLOCK, target, "", UnblockStatus.SELF, now));
        }
        Block existing = block(social, caller, target);
        if (existing == null) {
            return new UnblockOutcome(UnblockStatus.NOT_BLOCKED, archive(social, caller, commandId,
                    CommandKind.UNBLOCK, target, "", UnblockStatus.NOT_BLOCKED, now));
        }
        List<Block> blocks = new ArrayList<>(social.blocks());
        blocks.remove(existing);
        State next = archive(new State(social.drafts(), social.messages(), blocks,
                social.commands(), now), caller, commandId, CommandKind.UNBLOCK, target,
                "", UnblockStatus.UNBLOCKED, now);
        return new UnblockOutcome(UnblockStatus.UNBLOCKED, next);
    }

    /**
     * 收件箱：只返回本人已真实投递的信封元数据，不含正文或他人收件人。
     *
     * @param social 社交状态
     * @param person 收件人本人
     * @return 按消息标识排序的信封；未登记/无来信返回空表
     */
    public static List<EnvelopeView> inbox(State social, String person) {
        requireState(social);
        requireText(person, "收件人");
        List<EnvelopeView> views = new ArrayList<>();
        for (Message message : social.messages()) {
            if (message.status() == MessageStatus.DELIVERED && message.to().equals(person)) {
                views.add(new EnvelopeView(message.messageId(), message.docId(), 1L,
                        message.from(), message.channel(), message.statusAt()));
            }
        }
        return List.copyOf(views);
    }

    /**
     * 发件箱：本人已写消息的投递结果；失败统一 {@link OutboxStatus#UNDELIVERED}。
     *
     * @param social 社交状态
     * @param person 发送者本人
     * @return 按消息标识排序的发件箱条目
     */
    public static List<OutboxView> outbox(State social, String person) {
        requireState(social);
        requireText(person, "发送者");
        List<OutboxView> views = new ArrayList<>();
        for (Message message : social.messages()) {
            if (!message.from().equals(person)) {
                continue;
            }
            OutboxStatus status;
            switch (message.status()) {
                case QUEUED:
                    status = OutboxStatus.QUEUED;
                    break;
                case DELIVERED:
                    status = OutboxStatus.DELIVERED;
                    break;
                default:
                    status = OutboxStatus.UNDELIVERED;
                    break;
            }
            views.add(new OutboxView(message.messageId(), message.to(), message.channel(),
                    message.sentAt(), message.dueAt(), status));
        }
        return List.copyOf(views);
    }

    // ---------------------------------------------------------------- 内部

    private static boolean reachableInternal(State social, InformationModel.State info,
                                             Context context, String from, String to, String channel,
                                             long now) {
        if (from.equals(to)) {
            return false;
        }
        if (!registeredPerson(context.registry(), from) || !registeredPerson(context.registry(), to)) {
            return false;
        }
        if (!info.persons().contains(from) || !info.persons().contains(to)) {
            return false;
        }
        if (blocked(social, from, to) || blocked(social, to, from)) {
            return false;
        }
        for (ContactEntry entry : context.contacts()) {
            if (entry.targetPartyId().equals(to) && entry.channel().equals(channel)
                    && recallable(info, from, entry.docId(), entry.version(), now,
                            context.recall())) {
                return true;
            }
        }
        for (Message message : social.messages()) {
            if (message.status() == MessageStatus.DELIVERED
                    && message.to().equals(from)
                    && message.from().equals(to)
                    && message.channel().equals(channel)
                    && recallable(info, from, message.docId(), 1L, now, context.recall())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定本人对某固定版本的实际完成阅读此刻是否仍可回忆（只读，与纯核内部同口径）。
     *
     * <p>必须有真实经历，且最近一次 COMPLETED 阅读的衰减分数不低于阈值；只看目录、
     * 取消/拒绝阅读、未来完成均不算，也不受输入窗口 maxItems/maxContentChars 影响。
     * 供运行层与 Web 投影复用，不改变任何记账/命令。</p>
     *
     * @param info    信息状态
     * @param person  人物
     * @param docId   资料
     * @param version 固定版本
     * @param now     判定时点
     * @param policy  回忆参数
     * @return 仍可回忆返回 true
     */
    public static boolean recallable(InformationModel.State info, String person, String docId,
                                     long version, long now, Policy policy) {
        boolean observed = false;
        for (ReadObservation observation : info.observations()) {
            if (observation.personPartyId().equals(person)
                    && observation.docId().equals(docId)
                    && observation.version() == version
                    && observation.completedAt() <= now) {
                observed = true;
                break;
            }
        }
        if (!observed) {
            return false;
        }
        ClosedReading last = null;
        for (ClosedReading closed : info.closed()) {
            if (!closed.personPartyId().equals(person)
                    || !closed.docId().equals(docId)
                    || closed.version() != version
                    || closed.reason() != CloseReason.COMPLETED
                    || closed.closedAt() > now) {
                continue;
            }
            if (last == null || closed.closedAt() > last.closedAt()
                    || (closed.closedAt() == last.closedAt()
                            && closed.readId().compareTo(last.readId()) < 0)) {
                last = closed;
            }
        }
        if (last == null) {
            return false;
        }
        return CognitionRecall.scoreOf(last.closedAt(), now, policy.halfLifeMillis())
                >= policy.thresholdPermille();
    }

    /**
     * 本人合法联系地址（只读）：仍可回忆的可信入口 + 实际读过且仍可回忆的来信发件地址。
     *
     * <p>按真实回忆资格（衰减阈值）判定，**不按输入窗口 maxItems 截断**，也**不**参考
     * 对方是否屏蔽本人——避免通过地址列表泄漏对方偏好。同一 (目标, 渠道) 只保留一条，
     * 可信入口优先。</p>
     *
     * @param social  社交状态
     * @param info    信息状态
     * @param context 受信上下文（含回忆参数与入口清单）
     * @param person  人物
     * @param now     判定时点
     * @return 地址列表（按目标/渠道/资料排序）
     */
    public static List<ContactEntry> recallableContacts(State social, InformationModel.State info,
                                                        Context context, String person, long now) {
        java.util.Map<String, ContactEntry> merged = new java.util.TreeMap<>();
        for (ContactEntry entry : context.contacts()) {
            if (entry.targetPartyId().equals(person) || !info.persons().contains(person)
                    || !info.persons().contains(entry.targetPartyId())) {
                continue;
            }
            if (recallable(info, person, entry.docId(), entry.version(), now, context.recall())) {
                merged.putIfAbsent(entry.targetPartyId() + '\u0000' + entry.channel(), entry);
            }
        }
        for (Message message : social.messages()) {
            if (message.status() != MessageStatus.DELIVERED || !message.to().equals(person)) {
                continue;
            }
            if (recallable(info, person, message.docId(), 1L, now, context.recall())) {
                merged.putIfAbsent(message.from() + '\u0000' + message.channel(),
                        new ContactEntry(message.channel(), message.from(),
                                "已读来信的发件地址", message.docId(), 1L));
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * 仅本人可用的正文查询（只读）：要求本人是实际收件者、消息已送达、本人真实完成阅读，
     * 且此刻仍通过回忆衰减阈值。
     *
     * <p>无资格、未知消息或已遗忘一律返回空；不产生阅读、不推进世界、不改变输入窗口。
     * 正文取自真实完成阅读记录，不读管理员全文表。</p>
     *
     * @param social    社交状态
     * @param info      信息状态
     * @param context   受信上下文
     * @param person    请求者
     * @param messageId 消息标识
     * @param now       查询时点
     * @return 正文；无资格为空
     */
    public static java.util.Optional<String> recalledBody(State social, InformationModel.State info,
                                                          Context context, String person,
                                                          String messageId, long now) {
        Message message = message(social, messageId);
        if (message == null || !message.to().equals(person)
                || message.status() != MessageStatus.DELIVERED) {
            return java.util.Optional.empty();
        }
        if (!recallable(info, person, message.docId(), 1L, now, context.recall())) {
            return java.util.Optional.empty();
        }
        for (ReadObservation observation : info.observations()) {
            if (observation.personPartyId().equals(person)
                    && observation.docId().equals(message.docId())
                    && observation.version() == 1L
                    && observation.completedAt() <= now) {
                return java.util.Optional.of(observation.body());
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean consistent(Message message, PendingPayload payload) {
        if (!message.messageId().equals(payload.messageId())
                || !message.from().equals(payload.from())
                || !message.to().equals(payload.to())
                || !message.channel().equals(payload.channel())
                || !message.docId().equals(payload.docId())) {
            return false;
        }
        return message.contentDigest().equals(digest(message.messageId(), message.from(),
                message.to(), message.channel(), payload.title(), payload.body()));
    }

    private static Message resettle(Message message, MessageStatus status, long now) {
        return new Message(message.messageId(), message.commandId(), message.from(), message.to(),
                message.channel(), message.contentDigest(), message.docId(), message.sentAt(),
                message.dueAt(), status, now);
    }

    private static State withMessage(State social, Message replaced) {
        List<Message> messages = new ArrayList<>(social.messages());
        messages.set(messages.indexOf(message(social, replaced.messageId())), replaced);
        return new State(social.drafts(), messages, social.blocks(), social.commands(),
                social.clock());
    }

    /** 归档一条命令裁决并把世界时钟水位推到 {@code now}；调用方必须已核 {@code now >= clock}。 */
    private static State archive(State social, String caller, String commandId, CommandKind kind,
                                 String subject, String contentDigest, Enum<?> result, long now) {
        List<Command> commands = new ArrayList<>(social.commands());
        commands.add(new Command(caller, commandId, kind, subject, contentDigest, result.name(),
                now));
        return new State(social.drafts(), social.messages(), social.blocks(), commands, now);
    }

    /** 取 {@code (caller, commandId)} 的既有裁决；不存在返回 null。 */
    private static Command command(State social, String caller, String commandId) {
        for (Command command : social.commands()) {
            if (command.caller().equals(caller) && command.commandId().equals(commandId)) {
                return command;
            }
        }
        return null;
    }

    /**
     * State 不变量：每条草稿/消息都必须有产生它的原 SEND 命令记录（同调用者、同消息标识、同摘要）。
     *
     * <p>本纯核无旧社交档，不提供「恢复时缺命令就直接当已开始」的静默兼容；缺记录的状态是非法状态，
     * 构造期明确拒绝，绝不凭空补一条命令。</p>
     */
    private static void requireSendRecord(List<Command> commands, String caller, String messageId,
                                          String digest) {
        for (Command command : commands) {
            if (command.kind() == CommandKind.SEND
                    && command.caller().equals(caller)
                    && command.subject().equals(messageId)
                    && command.contentDigest().equals(digest)) {
                return;
            }
        }
        throw new IllegalArgumentException("非法社交状态：缺少产生该草稿/消息的原 SEND 命令："
                + messageId);
    }

    private static Message message(State social, String messageId) {
        for (Message message : social.messages()) {
            if (message.messageId().equals(messageId)) {
                return message;
            }
        }
        return null;
    }

    private static Draft draft(State social, String messageId) {
        for (Draft draft : social.drafts()) {
            if (draft.messageId().equals(messageId)) {
                return draft;
            }
        }
        return null;
    }

    private static Block block(State social, String owner, String target) {
        for (Block block : social.blocks()) {
            if (block.ownerPartyId().equals(owner) && block.targetPartyId().equals(target)) {
                return block;
            }
        }
        return null;
    }

    private static boolean blocked(State social, String owner, String target) {
        return block(social, owner, target) != null;
    }

    private static boolean registeredPerson(OpeningRegistry registry, String partyId) {
        if (DELIVERY_CALLER.equals(partyId)) {
            return false;
        }
        for (Party party : registry.parties()) {
            if (party.partyId().equals(partyId)) {
                return party.kind() == PartyKind.PERSON;
            }
        }
        return false;
    }

    private static Document findDocument(List<Document> documents, String docId) {
        for (Document document : documents) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }

    /** 摘要：消息标识/收发方/渠道/标题/正文的长度前缀 SHA-256。 */
    private static String digest(String messageId, String from, String to, String channel,
                                 String title, String body) {
        String canonical = lengthPrefix(messageId) + lengthPrefix(from) + lengthPrefix(to)
                + lengthPrefix(channel) + lengthPrefix(title) + lengthPrefix(body);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(canonical.getBytes(StandardCharsets.UTF_8));
            char[] text = new char[hash.length * 2];
            for (int index = 0; index < hash.length; index++) {
                int value = hash[index] & 0xff;
                text[index * 2] = HEX[value >>> 4];
                text[index * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(text);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }

    private static String lengthPrefix(String value) {
        return value.length() + ":" + value;
    }

    private static <T> List<T> sorted(List<T> source, Comparator<T> order) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(source, "集合不能为空"));
        copy.sort(order);
        return copy;
    }

    private static void requireState(State social) {
        Objects.requireNonNull(social, "社交状态");
    }

    private static void requireInfo(InformationModel.State info) {
        Objects.requireNonNull(info, "信息状态");
    }

    private static void requireContext(Context context) {
        Objects.requireNonNull(context, "受信上下文");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + "不能为负：" + value);
        }
    }
}
