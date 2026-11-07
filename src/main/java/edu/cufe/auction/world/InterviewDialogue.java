package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * M4i.2b 逐轮访谈纯核：只决定谁在第几轮说了什么、何时应结束。
 *
 * <p><b>能力边界</b>：正文写入既有 {@link InformationModel} 的直投文档；会话开始/结束与发言
 * 许可沿用 {@link InterviewModel}；阅读与回忆沿用 {@link InformationModel} 与
 * {@link CognitionRecall}。本类不读系统时钟、不写世界日志、不记预算/工时/阅读，也不调用
 * {@code WorldRuntime}。它不声称能从 JSON 恢复出可信世界：{@code fromJson} 只保证结构自洽，
 * 运行桥必须从真实命令、pending 与文档重放，再用 {@link #validate} 对照当时会话。</p>
 *
 * <p><b>实验规则</b>：同一会话第一轮由邀请发起记者开始，其后严格交替；每条真实人物命令只产生
 * 一轮。{@code maxTurns}/{@code replyDeadlineMillis}/{@code speakMillis} 都是实验参数。</p>
 */
public final class InterviewDialogue {

    /** 成功。 */
    public static final String REASON_OK = "OK";
    /** 未知会话、非参与者或他人会话。 */
    public static final String REASON_UNKNOWN = "UNKNOWN";
    /** 会话已结束、对话已关闭或状态不允许。 */
    public static final String REASON_STATE = "STATE";
    /** 发言许可被现有采访核拒绝。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 已达轮次上限。 */
    public static final String REASON_LIMIT = "LIMIT";
    /** 上一轮未实际读完或不可回忆。 */
    public static final String REASON_NOT_READ = "NOT_READ";
    /** 发送时间不在会话内或未严格晚于上一轮。 */
    public static final String REASON_WINDOW = "WINDOW";
    /** 时长加法溢出。 */
    public static final String REASON_TIME_OVERFLOW = "TIME_OVERFLOW";
    /** 同一命令/轮次标识被不同参数复用。 */
    public static final String REASON_DUPLICATE = "DUPLICATE";

    private static final String DOC_PREFIX = "dialogue-turn:";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private InterviewDialogue() {
    }

    /**
     * 冻结的实验参数。
     *
     * @param maxTurns             最大轮次
     * @param replyDeadlineMillis  对方回复期限
     * @param speakMillis          单轮发送耗时
     * @param recallPolicy         回忆参数
     */
    public record Config(int maxTurns, long replyDeadlineMillis, long speakMillis,
                         CognitionRecall.Policy recallPolicy) {

        /** 校验正参数与非空回忆策略。 */
        public Config {
            if (maxTurns < 1 || maxTurns > 100) {
                throw new IllegalArgumentException("最大轮次必须在 1..100：" + maxTurns);
            }
            if (replyDeadlineMillis < 1 || speakMillis < 1) {
                throw new IllegalArgumentException("回复期限与发言时长必须为正");
            }
            Objects.requireNonNull(recallPolicy, "回忆参数");
        }
    }

    /**
     * 一轮：正文在指定 DIRECT 文档的固定版本里。
     *
     * @param turnId          轮次标识
     * @param sessionId       会话标识
     * @param sequence        轮次序号
     * @param speakerPartyId  发言者
     * @param docId           轮次文档
     * @param docVersion      文档版本
     */
    public record Turn(String turnId, String sessionId, int sequence,
                       String speakerPartyId, String docId, long docVersion) {

        /** 校验字段。 */
        public Turn {
            requireText(turnId, "轮次标识");
            requireText(sessionId, "会话标识");
            requireText(speakerPartyId, "发言者");
            requireText(docId, "轮次文档");
            if (sequence < 1 || docVersion != 1) {
                throw new IllegalArgumentException("轮次序号或版本非法：" + turnId);
            }
        }
    }

    /** 对话结束原因；只保存不能从会话终态推断的部分。 */
    public enum CloseReason {
        /** 达到最大轮次。 */
        TURN_LIMIT,
        /** 对方在回复期限内没有回复。 */
        DEADLINE_NO_REPLY,
        /** 预约时长自然走完（真实会话 CloseKind.COMPLETED）。 */
        SCHEDULE_END,
        /** 一方主动提前结束。 */
        CLOSED_BY_PARTY
    }

    /**
     * 对话关闭记录；会话实际结束仍以 {@link InterviewModel.Session} 为准。
     *
     * @param sessionId 会话
     * @param reason    关闭原因
     */
    public record DialogueClose(String sessionId, CloseReason reason) {

        /** 校验字段。 */
        public DialogueClose {
            requireText(sessionId, "关闭会话");
            Objects.requireNonNull(reason, "关闭原因");
        }
    }

    /**
     * 对话状态：只保存轮次与关闭原因。
     *
     * @param turns  轮次
     * @param closes 关闭记录
     */
    public record State(List<Turn> turns, List<DialogueClose> closes) {

        /** 深拷贝、排序并做内部结构校验。 */
        public State {
            List<Turn> turnList = new ArrayList<>();
            for (Turn turn : Objects.requireNonNullElse(turns, List.<Turn>of())) {
                turnList.add(Objects.requireNonNull(turn, "轮次"));
            }
            turnList.sort(Comparator.comparing(Turn::sessionId).thenComparingInt(Turn::sequence));
            Set<String> turnIds = new LinkedHashSet<>();
            Set<String> slots = new LinkedHashSet<>();
            for (Turn turn : turnList) {
                if (!turnIds.add(turn.turnId())) {
                    throw new IllegalArgumentException("轮次标识重复：" + turn.turnId());
                }
                if (!slots.add(turn.sessionId() + '\u0000' + turn.sequence())) {
                    throw new IllegalArgumentException("同一会话轮次序号重复：" + turn.sessionId()
                            + " #" + turn.sequence());
                }
            }
            List<DialogueClose> closeList = new ArrayList<>();
            for (DialogueClose close : Objects.requireNonNullElse(closes, List.<DialogueClose>of())) {
                closeList.add(Objects.requireNonNull(close, "关闭记录"));
            }
            closeList.sort(Comparator.comparing(DialogueClose::sessionId));
            Set<String> closed = new LinkedHashSet<>();
            for (DialogueClose close : closeList) {
                if (!closed.add(close.sessionId())) {
                    throw new IllegalArgumentException("同一会话只能关闭一次：" + close.sessionId());
                }
            }
            turns = List.copyOf(turnList);
            closes = List.copyOf(closeList);
        }

        /**
         * 该会话的轮次。
         *
         * @param sessionId 会话
         * @return 按序号升序的轮次
         */
        public List<Turn> turnsOf(String sessionId) {
            List<Turn> list = new ArrayList<>();
            for (Turn turn : turns) {
                if (turn.sessionId().equals(sessionId)) {
                    list.add(turn);
                }
            }
            return List.copyOf(list);
        }

        /**
         * 该会话的关闭记录。
         *
         * @param sessionId 会话
         * @return 已关闭返回记录；否则为空
         */
        public Optional<DialogueClose> closeOf(String sessionId) {
            for (DialogueClose close : closes) {
                if (close.sessionId().equals(sessionId)) {
                    return Optional.of(close);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 一次发送请求；文档标识与序号由程序派生，不信任调用方。
     *
     * @param turnId            轮次标识
     * @param sessionId         会话标识
     * @param title             轮次标题
     * @param body              轮次正文
     * @param readDurationMillis 本轮正文的固定阅读时长
     * @param startedAtMillis   开始发送时刻
     */
    public record SpeakRequest(String turnId, String sessionId, String title, String body,
                               long readDurationMillis, long startedAtMillis) {

        /** 校验字段。 */
        public SpeakRequest {
            requireText(turnId, "轮次标识");
            requireText(sessionId, "会话标识");
            requireText(title, "轮次标题");
            requireText(body, "轮次正文");
            if (readDurationMillis < 1 || startedAtMillis < 0) {
                throw new IllegalArgumentException("阅读时长必须为正、开始时刻不得为负");
            }
        }
    }

    /**
     * 发送结果；被拒绝时两个状态都与入参同一实例。
     *
     * @param accepted    是否成功
     * @param reason      结果码
     * @param dialogue    操作后的对话状态
     * @param information 操作后的信息状态
     */
    public record Outcome(boolean accepted, String reason, State dialogue,
                          InformationModel.State information) {
    }

    /**
     * 登记关闭结果。
     *
     * @param accepted 是否成功
     * @param reason   结果码
     * @param dialogue 操作后的对话状态
     */
    public record CloseOutcome(boolean accepted, String reason, State dialogue) {
    }

    /**
     * 空对话状态。
     *
     * @return 不含轮次与关闭记录的状态
     */
    public static State open() {
        return new State(List.of(), List.of());
    }

    /**
     * 发送一轮：校验会话、许可、顺序、真实阅读与时间后，写一条直投文档。
     *
     * @param dialogue    当前对话状态
     * @param information 当前信息状态
     * @param interviews  当前采访状态（真实会话语义）
     * @param context     当前受信上下文（只用于 {@link InterviewModel#canSpeak}）
     * @param config      冻结实验参数
     * @param speaker     真实命令主体（必须是会话双方之一）
     * @param request     发送请求
     * @param now         模拟完成时刻
     * @return 结果；成功时同时返回新的对话与信息状态
     */
    public static Outcome send(State dialogue, InformationModel.State information,
                               InterviewModel.State interviews, InterviewModel.Context context,
                               Config config, String speaker, SpeakRequest request, long now) {
        requireAll(dialogue, information, interviews, context, config, request);
        requireText(speaker, "发言者");
        if (now < 0) {
            return reject(dialogue, information, REASON_WINDOW);
        }
        InterviewModel.Session session = sessionOf(interviews, request.sessionId());
        if (session == null) {
            return reject(dialogue, information, REASON_UNKNOWN);
        }
        InterviewModel.Invite invite = inviteOf(interviews, session.requestId());
        if (invite == null || !participant(invite, speaker)) {
            return reject(dialogue, information, REASON_UNKNOWN);
        }
        List<Turn> turns = dialogue.turnsOf(session.sessionId());
        Turn existing = turnOf(dialogue, request.turnId());
        if (existing != null) {
            return sameSpeak(existing, session.sessionId(), speaker, request, information, now,
                    config)
                    ? new Outcome(true, REASON_OK, dialogue, information)
                    : reject(dialogue, information, REASON_DUPLICATE);
        }
        if (dialogue.closeOf(session.sessionId()).isPresent()) {
            return reject(dialogue, information, REASON_STATE);
        }
        if (!session.open()) {
            return reject(dialogue, information, REASON_STATE);
        }
        if (turns.size() >= config.maxTurns()) {
            return reject(dialogue, information, REASON_LIMIT);
        }
        if (!InterviewModel.canSpeak(interviews, context, speaker, session.sessionId(), now).allowed()) {
            return reject(dialogue, information, REASON_NOT_AUTHORIZED);
        }
        if (deadlineReached(turns, information, session, config, request.startedAtMillis())
                || deadlineReached(turns, information, session, config, now)) {
            return reject(dialogue, information, REASON_STATE);
        }
        String other = otherOf(invite, speaker);
        int sequence = turns.size() + 1;
        if (sequence == 1) {
            if (!speaker.equals(invite.fromPartyId())) {
                return reject(dialogue, information, REASON_STATE);
            }
        } else {
            Turn previous = turns.get(turns.size() - 1);
            if (previous.speakerPartyId().equals(speaker)) {
                return reject(dialogue, information, REASON_STATE);
            }
            if (!previous.speakerPartyId().equals(other)) {
                return reject(dialogue, information, REASON_STATE);
            }
            if (!readAndRecalled(information, speaker, previous, config, now)) {
                return reject(dialogue, information, REASON_NOT_READ);
            }
        }
        long startedAt = request.startedAtMillis();
        if (now < startedAt || now - startedAt != config.speakMillis()
                || startedAt < session.startedAtMillis()) {
            return reject(dialogue, information, REASON_WINDOW);
        }
        long previousPublished = sequence == 1 ? session.startedAtMillis()
                : publishedAt(information, turns.get(turns.size() - 1));
        if (previousPublished < 0 || now <= previousPublished) {
            return reject(dialogue, information, REASON_WINDOW);
        }
        String docId = turnDocId(session.sessionId(), sequence);
        if (documentOf(information, docId) != null) {
            return reject(dialogue, information, REASON_DUPLICATE);
        }
        InformationModel.State next;
        try {
            next = InformationModel.publish(information, docId, speaker,
                    InformationModel.Audience.direct(other),
                    new InformationModel.VersionDraft(request.title(), request.body(), "",
                            now, request.readDurationMillis(), List.of()));
        } catch (IllegalArgumentException rejected) {
            return reject(dialogue, information, REASON_STATE);
        }
        List<Turn> all = new ArrayList<>(dialogue.turns());
        all.add(new Turn(request.turnId(), session.sessionId(), sequence, speaker, docId, 1));
        State candidate = new State(all, dialogue.closes());
        try {
            validate(candidate, interviews, next, config);
        } catch (IllegalArgumentException inconsistent) {
            return reject(dialogue, information, REASON_STATE);
        }
        return new Outcome(true, REASON_OK, candidate, next);
    }

    /**
     * 计算应结束原因；不修改状态。
     *
     * @param dialogue    对话状态
     * @param information 信息状态（取上一轮文档发布时间）
     * @param interviews  采访状态
     * @param config      冻结参数
     * @param sessionId   会话
     * @param now         当前模拟时刻
     * @return 应结束原因；仍在进行且未到期为空
     */
    public static Optional<CloseReason> dueReason(State dialogue, InformationModel.State information,
                                                  InterviewModel.State interviews, Config config,
                                                  String sessionId, long now) {
        requireAll(dialogue, information, interviews, config);
        requireText(sessionId, "会话标识");
        InterviewModel.Session session = sessionOf(interviews, sessionId);
        if (session == null || dialogue.closeOf(sessionId).isPresent()) {
            return Optional.empty();
        }
        List<Turn> turns = dialogue.turnsOf(sessionId);
        long reference = session.open() ? now : session.endedAtMillis();
        if (turns.size() >= config.maxTurns()) {
            return Optional.of(CloseReason.TURN_LIMIT);
        }
        if (deadlineReached(turns, information, session, config, reference)) {
            return Optional.of(CloseReason.DEADLINE_NO_REPLY);
        }
        if (!session.open() && session.close() == InterviewModel.CloseKind.COMPLETED) {
            return Optional.of(CloseReason.SCHEDULE_END);
        }
        return session.open() ? Optional.empty() : Optional.of(CloseReason.CLOSED_BY_PARTY);
    }

    /**
     * 在真实会话已结束后登记关闭原因；会话仍 ACTIVE 时拒绝。
     *
     * @param dialogue  对话状态
     * @param information 信息状态（期限从上一轮文档发布时间算）
     * @param interviews 采访状态
     * @param config    冻结参数
     * @param sessionId 会话
     * @param now       当前模拟时刻（只用于校验非负）
     * @return 结果
     */
    public static CloseOutcome close(State dialogue, InformationModel.State information,
                                     InterviewModel.State interviews, Config config,
                                     String sessionId, long now) {
        requireAll(dialogue, information, interviews, config);
        requireText(sessionId, "会话标识");
        if (now < 0) {
            return new CloseOutcome(false, REASON_WINDOW, dialogue);
        }
        InterviewModel.Session session = sessionOf(interviews, sessionId);
        if (session == null) {
            return new CloseOutcome(false, REASON_UNKNOWN, dialogue);
        }
        if (session.open()) {
            return new CloseOutcome(false, REASON_STATE, dialogue);
        }
        CloseReason reason = reasonAtEnd(dialogue.turnsOf(sessionId), information, session, config);
        Optional<DialogueClose> existing = dialogue.closeOf(sessionId);
        if (existing.isPresent()) {
            return existing.get().reason() == reason
                    ? new CloseOutcome(true, REASON_OK, dialogue)
                    : new CloseOutcome(false, REASON_STATE, dialogue);
        }
        List<DialogueClose> closes = new ArrayList<>(dialogue.closes());
        closes.add(new DialogueClose(sessionId, reason));
        State candidate = new State(dialogue.turns(), closes);
        try {
            validateStructure(candidate, interviews, config);
        } catch (IllegalArgumentException inconsistent) {
            return new CloseOutcome(false, REASON_STATE, dialogue);
        }
        return new CloseOutcome(true, REASON_OK, candidate);
    }

    /**
     * 只读判断是否还能再发一轮。
     *
     * @param dialogue  对话状态
     * @param interviews 采访状态
     * @param context   受信上下文
     * @param config    冻结参数
     * @param sessionId 会话
     * @param person    发言者
     * @param now       当前时刻
     * @return 可发送返回 true
     */
    public static boolean canSend(State dialogue, InterviewModel.State interviews,
                                  InterviewModel.Context context, Config config, String sessionId,
                                  String person, long now) {
        if (dialogue == null || interviews == null || context == null || config == null
                || sessionId == null || person == null || now < 0) {
            return false;
        }
        InterviewModel.Session session = sessionOf(interviews, sessionId);
        if (session == null || !session.open() || dialogue.closeOf(sessionId).isPresent()) {
            return false;
        }
        InterviewModel.Invite invite = inviteOf(interviews, session.requestId());
        if (invite == null || !participant(invite, person)) {
            return false;
        }
        List<Turn> turns = dialogue.turnsOf(sessionId);
        if (turns.size() >= config.maxTurns()) {
            return false;
        }
        if (turns.isEmpty()) {
            if (!person.equals(invite.fromPartyId())) {
                return false;
            }
        } else if (turns.get(turns.size() - 1).speakerPartyId().equals(person)) {
            return false;
        }
        return InterviewModel.canSpeak(interviews, context, person, sessionId, now).allowed();
    }

    /**
     * 跨状态逐轮核验：文档、发布者、受众、顺序、交替、时间与会话终态。
     *
     * @param dialogue    对话状态
     * @param interviews  采访状态
     * @param information 信息状态
     * @param config      冻结参数
     * @throws IllegalArgumentException 任一关系不一致
     */
    public static void validate(State dialogue, InterviewModel.State interviews,
                                InformationModel.State information, Config config) {
        requireAll(dialogue, interviews, information, config);
        validateStructure(dialogue, interviews, config);
        for (DialogueClose close : dialogue.closes()) {
            InterviewModel.Session session = sessionOf(interviews, close.sessionId());
            if (session == null || session.open()) {
                throw new IllegalArgumentException("关闭记录没有已结束的真实会话："
                        + close.sessionId());
            }
        }
        for (Turn turn : dialogue.turns()) {
            InterviewModel.Session session = sessionOf(interviews, turn.sessionId());
            if (session == null) {
                throw new IllegalArgumentException("轮次引用未知会话：" + turn.sessionId());
            }
            InterviewModel.Invite invite = inviteOf(interviews, session.requestId());
            if (invite == null || !participant(invite, turn.speakerPartyId())) {
                throw new IllegalArgumentException("轮次发言者不是会话参与者：" + turn.turnId());
            }
            if (!turn.docId().equals(turnDocId(turn.sessionId(), turn.sequence()))) {
                throw new IllegalArgumentException("轮次文档标识不是程序派生值：" + turn.turnId());
            }
            InformationModel.Document document = documentOf(information, turn.docId());
            if (document == null || document.at(turn.docVersion()) == null) {
                throw new IllegalArgumentException("轮次缺少对应文档版本：" + turn.turnId());
            }
            String listener = otherOf(invite, turn.speakerPartyId());
            if (!document.publisherPartyId().equals(turn.speakerPartyId())
                    || document.audience().kind() != InformationModel.AudienceKind.DIRECT
                    || !document.audience().partyId().equals(listener)) {
                throw new IllegalArgumentException("轮次文档的发布者或受众不符：" + turn.turnId());
            }
        }
        for (InterviewModel.Session session : interviews.sessions()) {
            List<Turn> turns = dialogue.turnsOf(session.sessionId());
            InterviewModel.Invite invite = inviteOf(interviews, session.requestId());
            if (invite == null) {
                throw new IllegalArgumentException("会话缺少对应邀请：" + session.sessionId());
            }
            if (turns.size() > config.maxTurns()) {
                throw new IllegalArgumentException("轮数超过冻结上限：" + session.sessionId());
            }
            long previous = session.startedAtMillis();
            for (int index = 0; index < turns.size(); index++) {
                Turn turn = turns.get(index);
                if (turn.sequence() != index + 1) {
                    throw new IllegalArgumentException("轮次序号不连续：" + turn.sessionId());
                }
                String expected = index == 0 ? invite.fromPartyId()
                        : (turns.get(index - 1).speakerPartyId().equals(invite.fromPartyId())
                        ? invite.toPartyId() : invite.fromPartyId());
                if (!turn.speakerPartyId().equals(expected)) {
                    throw new IllegalArgumentException(index == 0
                            ? "首轮必须由邀请发起者发言：" + turn.turnId()
                            : "轮次未严格交替：" + turn.turnId());
                }
                long published = publishedAt(information, turn);
                if (published < 0 || published <= previous || published >= invite.scheduledEnd()) {
                    throw new IllegalArgumentException("轮次发布时间不在会话有效区间："
                            + turn.turnId());
                }
                if (!session.open() && published > session.endedAtMillis()) {
                    throw new IllegalArgumentException("轮次发布时间晚于会话结束："
                            + turn.turnId());
                }
                previous = published;
            }
        }
        for (DialogueClose close : dialogue.closes()) {
            InterviewModel.Session session = sessionOf(interviews, close.sessionId());
            CloseReason expected = reasonAtEnd(dialogue.turnsOf(close.sessionId()), information,
                    session, config);
            if (close.reason() != expected) {
                throw new IllegalArgumentException("关闭原因与真实事实不符：" + close.sessionId());
            }
        }
    }

    /**
     * 序列化；输出稳定。
     *
     * @param state 对话状态
     * @return JSON 文本
     */
    public static String toJson(State state) {
        Objects.requireNonNull(state, "对话状态");
        try {
            return MAPPER.writeValueAsString(state);
        } catch (Exception failure) {
            throw new IllegalStateException("对话状态无法序列化", failure);
        }
    }

    /**
     * 从 JSON 还原；只保证内部结构自洽，不证明世界真实性。
     *
     * @param json JSON 文本
     * @return 还原状态
     * @throws IllegalArgumentException 文本非法或结构不一致
     */
    public static State fromJson(String json) {
        requireText(json, "对话状态 JSON");
        try {
            State state = MAPPER.readValue(json, State.class);
            if (state == null) {
                throw new IllegalArgumentException("对话状态 JSON 为空");
            }
            return state;
        } catch (IllegalArgumentException semantic) {
            throw semantic;
        } catch (Exception failure) {
            throw new IllegalArgumentException("对话状态 JSON 无法解析", failure);
        }
    }

    // ---------------------------------------------------------------- 内部

    private static boolean sameSpeak(Turn existing, String sessionId, String speaker,
                                     SpeakRequest request, InformationModel.State information,
                                     long now, Config config) {
        if (!existing.sessionId().equals(sessionId)
                || !existing.speakerPartyId().equals(speaker)
                || !existing.docId().equals(turnDocId(sessionId, existing.sequence()))) {
            return false;
        }
        InformationModel.Document document = documentOf(information, existing.docId());
        if (document == null) {
            return false;
        }
        InformationModel.DocumentVersion version = document.at(existing.docVersion());
        return version != null
                && version.title().equals(request.title())
                && version.body().equals(request.body())
                && version.readDurationMillis() == request.readDurationMillis()
                && version.publishedAt() == now
                && now >= request.startedAtMillis()
                && now - request.startedAtMillis() == config.speakMillis();
    }

    /** 已结束会话的原因：轮数 > 期限 > 预约自然到点 > 一方提前结束。 */
    private static CloseReason reasonAtEnd(List<Turn> turns, InformationModel.State information,
                                            InterviewModel.Session session, Config config) {
        if (turns.size() >= config.maxTurns()) {
            return CloseReason.TURN_LIMIT;
        }
        if (deadlineReached(turns, information, session, config, session.endedAtMillis())) {
            return CloseReason.DEADLINE_NO_REPLY;
        }
        return session.close() == InterviewModel.CloseKind.COMPLETED
                ? CloseReason.SCHEDULE_END : CloseReason.CLOSED_BY_PARTY;
    }

    /** 在给定时刻是否已到回复期限；基线首轮为会话开始，其后为最后真实文档发布时间。 */
    private static boolean deadlineReached(List<Turn> turns, InformationModel.State information,
                                           InterviewModel.Session session, Config config,
                                           long at) {
        long baseline = turns.isEmpty() ? session.startedAtMillis()
                : publishedAt(information, turns.get(turns.size() - 1));
        return baseline >= 0 && at >= baseline
                && at - baseline >= config.replyDeadlineMillis();
    }

    private static void validateStructure(State dialogue, InterviewModel.State interviews,
                                          Config config) {
        for (DialogueClose close : dialogue.closes()) {
            List<Turn> turns = dialogue.turnsOf(close.sessionId());
            if (close.reason() == CloseReason.TURN_LIMIT && turns.size() < config.maxTurns()) {
                throw new IllegalArgumentException("未达轮次上限却记 TURN_LIMIT："
                        + close.sessionId());
            }
        }
        for (Turn turn : dialogue.turns()) {
            if (sessionOf(interviews, turn.sessionId()) == null) {
                throw new IllegalArgumentException("轮次引用未知会话：" + turn.sessionId());
            }
        }
    }

    private static boolean readAndRecalled(InformationModel.State information, String person,
                                           Turn previous, Config config, long now) {
        if (!InformationModel.canCite(information, person, previous.docId(),
                previous.docVersion(), now)) {
            return false;
        }
        for (CognitionRecall.Item item : CognitionRecall.recall(information, person, now,
                config.recallPolicy()).selected()) {
            if (item.observation().docId().equals(previous.docId())
                    && item.observation().version() == previous.docVersion()) {
                return true;
            }
        }
        return false;
    }

    private static long publishedAt(InformationModel.State information, Turn turn) {
        InformationModel.Document document = documentOf(information, turn.docId());
        if (document == null) {
            return -1L;
        }
        InformationModel.DocumentVersion version = document.at(turn.docVersion());
        return version == null ? -1L : version.publishedAt();
    }

    private static String otherOf(InterviewModel.Invite invite, String speaker) {
        if (invite.fromPartyId().equals(speaker)) {
            return invite.toPartyId();
        }
        if (invite.toPartyId().equals(speaker)) {
            return invite.fromPartyId();
        }
        return "";
    }

    private static boolean participant(InterviewModel.Invite invite, String person) {
        return invite.fromPartyId().equals(person) || invite.toPartyId().equals(person);
    }

    private static Turn turnOf(State dialogue, String turnId) {
        for (Turn turn : dialogue.turns()) {
            if (turn.turnId().equals(turnId)) {
                return turn;
            }
        }
        return null;
    }

    private static InterviewModel.Session sessionOf(InterviewModel.State interviews,
                                                    String sessionId) {
        for (InterviewModel.Session session : interviews.sessions()) {
            if (session.sessionId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    private static InterviewModel.Invite inviteOf(InterviewModel.State interviews, String requestId) {
        for (InterviewModel.Invite invite : interviews.invites()) {
            if (invite.requestId().equals(requestId)) {
                return invite;
            }
        }
        return null;
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

    /** 派生轮次文档标识：长度前缀，会话标识含分隔符也不会碰撞。 */
    static String turnDocId(String sessionId, int sequence) {
        return DOC_PREFIX + sessionId.length() + ":" + sessionId + ":" + sequence;
    }

    private static Outcome reject(State dialogue, InformationModel.State information, String reason) {
        return new Outcome(false, reason, dialogue, information);
    }

    private static void requireAll(Object... values) {
        for (Object value : values) {
            Objects.requireNonNull(value, "参数不能为空");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
