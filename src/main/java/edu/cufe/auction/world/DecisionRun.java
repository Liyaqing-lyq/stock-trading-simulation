package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 决定子集：唯一世界日志里的不可变运行包装。
 *
 * <p>本类型承载四样东西，缺一不可：</p>
 * <ul>
 *   <li>{@link #state()}：纯核 {@link DecisionModel.State}（冻结配置、决定、尝试、预算）；</li>
 *   <li>{@link #bindings()}：决定打开时冻结的人物与观察任职，供完成期按原绑定复核；</li>
 *   <li>{@link #actionRefs()}：每个已记录业务意图对应的内部任务标识、工具与到期；</li>
 *   <li>{@link #archives()}：旧代次或重复回执的只读归档，不改变预算、决定与经济状态。</li>
 * </ul>
 *
 * <p><b>不保存</b>现金、持仓、订单或成交：这些只存在于同批的账户、证券与撮合子集里。</p>
 *
 * @param state      纯核决定状态（不可变）
 * @param bindings   动作标识 → 打开时冻结的人物与任职
 * @param actionRefs 动作标识 → 业务任务引用
 * @param archives   旧回执归档（只增不改）
 */
public record DecisionRun(DecisionModel.State state, Map<String, Binding> bindings,
                          Map<String, ActionRef> actionRefs, List<Archive> archives) {

    /** 工具合同版本；输入摘要把它与规范化输入一起冻结。 */
    public static final String TOOL_CONTRACT_VERSION = "role-decision-tools-v1";

    /** 决定动作内部命令前缀。 */
    private static final String PREFIX_COMMAND = "dec";

    /** 决定动作内部任务前缀。 */
    private static final String PREFIX_TASK = "decT";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 校验索引一致性与冻结集合。 */
    public DecisionRun {
        Objects.requireNonNull(state, "决定状态");
        Map<String, Binding> frozenBindings = new TreeMap<>();
        if (bindings != null) {
            bindings.forEach((actionId, binding) -> {
                Objects.requireNonNull(binding, "绑定");
                if (!actionId.equals(binding.actionId())) {
                    throw new IllegalArgumentException("绑定索引键与动作标识不一致：" + actionId);
                }
                frozenBindings.put(actionId, binding);
            });
        }
        bindings = Collections.unmodifiableMap(frozenBindings);
        Map<String, ActionRef> frozenRefs = new TreeMap<>();
        if (actionRefs != null) {
            actionRefs.forEach((actionId, ref) -> {
                Objects.requireNonNull(ref, "动作引用");
                if (!actionId.equals(ref.actionId())) {
                    throw new IllegalArgumentException("动作引用索引键与动作标识不一致：" + actionId);
                }
                frozenRefs.put(actionId, ref);
            });
        }
        actionRefs = Collections.unmodifiableMap(frozenRefs);
        archives = List.copyOf(archives == null ? List.of() : archives);
    }

    /**
     * 空决定子集。
     *
     * @param runId  运行标识
     * @param seed   公平顺序种子
     * @param config 冻结配置
     * @return 尚未接续、没有任何决定的子集
     */
    public static DecisionRun opening(String runId, long seed, DecisionModel.Config config) {
        return new DecisionRun(DecisionModel.open(runId, seed, config), Map.of(), Map.of(),
                List.of());
    }

    /**
     * 用候选纯核状态替换；绑定、引用与归档保持不变。
     *
     * @param next 候选状态
     * @return 替换后的子集
     */
    public DecisionRun withState(DecisionModel.State next) {
        return new DecisionRun(next, bindings, actionRefs, archives);
    }

    /**
     * 追加一份观察绑定；同动作只允许同内容。
     *
     * @param binding 绑定
     * @return 追加后的子集
     */
    public DecisionRun withBinding(Binding binding) {
        Binding existing = bindings.get(binding.actionId());
        if (existing != null) {
            if (!existing.equals(binding)) {
                throw new IllegalArgumentException("同一动作的观察绑定不得改写：" + binding.actionId());
            }
            return this;
        }
        Map<String, Binding> next = new TreeMap<>(bindings);
        next.put(binding.actionId(), binding);
        return new DecisionRun(state, next, actionRefs, archives);
    }

    /**
     * 追加一条业务任务引用；同动作只允许同内容。
     *
     * @param ref 引用
     * @return 追加后的子集
     */
    public DecisionRun withActionRef(ActionRef ref) {
        ActionRef existing = actionRefs.get(ref.actionId());
        if (existing != null) {
            if (!existing.equals(ref)) {
                throw new IllegalArgumentException("同一动作的任务引用不得改写：" + ref.actionId());
            }
            return this;
        }
        Map<String, ActionRef> next = new TreeMap<>(actionRefs);
        next.put(ref.actionId(), ref);
        return new DecisionRun(state, bindings, next, archives);
    }

    /**
     * 追加一条旧回执归档；同标识同内容幂等。
     *
     * @param archive 归档
     * @return 追加后的子集
     */
    public DecisionRun withArchive(Archive archive) {
        Objects.requireNonNull(archive, "归档");
        for (Archive existing : archives) {
            if (existing.sameKey(archive)) {
                if (existing.equals(archive)) {
                    return this;
                }
                throw new IllegalArgumentException("同一回执身份的归档内容不得改写："
                        + archive.actionId() + "/" + archive.attemptId());
            }
        }
        List<Archive> next = new ArrayList<>(archives);
        next.add(archive);
        return new DecisionRun(state, bindings, actionRefs, next);
    }

    /**
     * 归档里是否已有同一身份同一内容的记录。
     *
     * @param key 待查归档
     * @return 已归档返回 true
     */
    public boolean archived(Archive key) {
        for (Archive existing : archives) {
            if (existing.sameKey(key)) {
                return existing.equals(key);
            }
        }
        return false;
    }

    /** @return 该动作的观察绑定；不存在返回 null */
    public Binding binding(String actionId) {
        return bindings.get(actionId);
    }

    /** @return 该动作的业务任务引用；不存在返回 null */
    public ActionRef actionRef(String actionId) {
        return actionRefs.get(actionId);
    }

    /**
     * 动作内部命令标识：长度编码，前后都带长度边界，{@code foo} 的后缀不会撞上 {@code foo-2}。
     *
     * @param actionId 动作标识
     * @return 内部命令标识
     */
    public static String actionCommandId(String actionId) {
        requireText(actionId, "动作标识");
        return PREFIX_COMMAND + ":" + actionId.length() + ":" + actionId + ":"
                + actionId.length();
    }

    /**
     * 动作内部任务标识基名：长度编码，且保留原动作标识便于审计。
     *
     * @param actionId 动作标识
     * @return 内部任务标识基名
     */
    public static String actionTaskId(String actionId) {
        requireText(actionId, "动作标识");
        return PREFIX_TASK + ":" + actionId.length() + ":" + actionId + ":"
                + actionId.length();
    }

    /**
     * 由一份已确认世界投影生成规范化输入正文。
     *
     * <p>只包含本人获权资产、市场、目录、本人已读全文、授权研究/目标/子单/回报、本人未完成动作
     * 摘要与可用工具合同；不含观察截止位置、尝试标识、恢复代次、全局订单号或经历序号。</p>
     *
     * @param view                本次观察视图
     * @param wakeReason          唤醒原因
     * @param cutoffTimeMillis    观察截止时点
     * @param pending             本人尚未完成的业务动作（动作标识、工具、到期）
     * @param tools               允许返回的工具（含 {@code role.wait}）
     * @param config              冻结配置
     * @return 规范 JSON 正文
     */
    public static String inputText(RoleTradingView view, String wakeReason, long cutoffTimeMillis,
                                   List<PendingAction> pending, List<String> tools,
                                   DecisionModel.Config config) {
        Objects.requireNonNull(view, "观察视图");
        Objects.requireNonNull(config, "冻结配置");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("toolContractVersion", TOOL_CONTRACT_VERSION);
        root.put("person", view.personPartyId());
        root.put("wakeReason", wakeReason);
        root.put("observedAt", cutoffTimeMillis);
        root.put("time", view.timeMillis());

        ArrayNode appointments = root.putArray("appointments");
        for (RoleTradingView.Appointment appointment
                : sorted(view.appointments(), RoleTradingView.Appointment::appointmentId)) {
            ObjectNode node = appointments.addObject();
            node.put("appointmentId", appointment.appointmentId());
            node.put("organizationPartyId", appointment.organizationPartyId());
            node.put("role", appointment.role().name());
            node.put("endsAt", appointment.endsAt());
        }

        ObjectNode profile = root.putObject("profile");
        ArrayNode accounts = profile.putArray("accounts");
        for (RoleView.Account account : sorted(view.profile().accounts(), RoleView.Account::accountId)) {
            ObjectNode node = accounts.addObject();
            node.put("accountId", account.accountId());
            node.put("kind", account.kind().name());
            node.put("cash", money(account.cash()));
            node.put("frozenCash", money(account.frozenCash()));
            node.put("availableCash", money(account.availableCash()));
        }
        ArrayNode holdings = profile.putArray("holdings");
        for (RoleView.Holding holding : sorted(view.profile().holdings(), DecisionRun::holdingKey)) {
            ObjectNode node = holdings.addObject();
            node.put("accountId", holding.accountId());
            node.put("symbol", holding.symbol());
            node.put("quantity", holding.quantity());
            node.put("frozen", holding.frozen());
            node.put("available", holding.available());
        }
        ArrayNode market = profile.putArray("market");
        for (RoleView.Market quote : sorted(view.profile().market(), RoleView.Market::symbol)) {
            ObjectNode node = market.addObject();
            node.put("symbol", quote.symbol());
            node.put("lastPrice", quote.lastPrice() == null ? null : money(quote.lastPrice()));
            node.put("lastTradeQuantity", quote.lastTradeQuantity());
            node.put("lastTradeAtMillis", quote.lastTradeAtMillis());
            node.put("bestBid", quote.bestBid() == null ? null : money(quote.bestBid()));
            node.put("bestAsk", quote.bestAsk() == null ? null : money(quote.bestAsk()));
        }
        ArrayNode catalog = profile.putArray("catalog");
        for (InformationModel.CatalogEntry entry
                : sorted(view.profile().catalog(), InformationModel.CatalogEntry::docId)) {
            ObjectNode node = catalog.addObject();
            node.put("docId", entry.docId());
            node.put("version", entry.version());
            node.put("title", entry.title());
        }
        ArrayNode observations = profile.putArray("readExperiences");
        for (RoleView.Experience experience
                : sorted(view.profile().observations(),
                item -> item.ref().docId() + "#" + item.ref().version())) {
            ObjectNode node = observations.addObject();
            node.put("docId", experience.ref().docId());
            node.put("version", experience.ref().version());
            node.put("title", experience.title());
            node.put("body", experience.body());
            node.put("source", experience.publicSourceDescription());
            node.put("publishedAt", experience.publishedAt());
            node.put("completedAt", experience.completedAt());
        }
        ArrayNode readings = profile.putArray("activeReadings");
        for (RoleView.Reading reading
                : sorted(view.profile().readings(), item -> item.ref().docId())) {
            ObjectNode node = readings.addObject();
            node.put("docId", reading.ref().docId());
            node.put("version", reading.ref().version());
            node.put("index", reading.ref().index());
            node.put("dueAt", reading.dueAt());
        }

        ArrayNode research = root.putArray("research");
        for (RoleTradingView.ResearchView note : sorted(view.research(),
                RoleTradingView.ResearchView::researchId)) {
            ObjectNode node = research.addObject();
            node.put("researchId", note.researchId());
            node.put("organizationPartyId", note.organizationPartyId());
            node.put("productOwnerId", note.productOwnerId());
            node.put("symbol", note.symbol());
            node.put("docId", note.docId());
            node.put("docVersion", note.docVersion());
            node.put("title", note.title());
            node.put("read", note.read());
            if (note.read() && note.body() != null) {
                node.put("body", note.body());
            }
        }

        ArrayNode targets = root.putArray("targets");
        for (RoleTradingView.TargetView target
                : sorted(view.targets(), RoleTradingView.TargetView::targetId)) {
            ObjectNode node = targets.addObject();
            node.put("targetId", target.targetId());
            node.put("organizationPartyId", target.organizationPartyId());
            node.put("productOwnerId", target.productOwnerId());
            node.put("accountId", target.accountId());
            node.put("symbol", target.symbol());
            node.put("status", target.status().name());
            node.put("version", target.current().version());
            node.put("absoluteTarget", target.current().absoluteTarget());
            node.put("baseQuantity", target.current().baseQuantity());
            node.put("changeQuantity", target.current().changeQuantity());
            node.put("side", target.current().side() == null ? null
                    : target.current().side().name());
            node.put("priceLimit", money(target.current().priceLimit()));
            node.put("deadlineMillis", target.current().deadlineMillis());
            node.put("filled", target.filled());
            node.put("leaves", target.leaves());
            node.put("remaining", target.remaining());
        }

        ArrayNode children = root.putArray("children");
        for (RoleTradingView.ChildView child : sorted(view.children(),
                item -> item.ref().childId())) {
            ObjectNode node = children.addObject();
            node.put("parentId", child.ref().parentId());
            node.put("childId", child.ref().childId());
            node.put("parentVersion", child.parentVersion());
            node.put("quantity", child.quantity());
            node.put("filled", child.filled());
            node.put("leaves", child.leaves());
            node.put("limitPrice", money(child.limitPrice()));
            node.put("status", child.status());
        }

        ArrayNode notices = root.putArray("notices");
        for (RoleTradingView.Notice notice : sorted(view.notices(),
                item -> item.clientTaskId() + "#" + item.timeMillis())) {
            ObjectNode node = notices.addObject();
            node.put("clientTaskId", notice.clientTaskId());
            node.put("kind", notice.kind());
            node.put("accepted", notice.accepted());
            node.put("reason", notice.reason());
            node.put("timeMillis", notice.timeMillis());
        }

        ArrayNode pendingActions = root.putArray("pendingActions");
        List<PendingAction> ordered = new ArrayList<>(pending == null ? List.of() : pending);
        ordered.sort((left, right) -> {
            int byDue = Long.compare(left.dueMillis(), right.dueMillis());
            return byDue != 0 ? byDue : left.actionId().compareTo(right.actionId());
        });
        for (PendingAction action : ordered) {
            ObjectNode node = pendingActions.addObject();
            node.put("actionId", action.actionId());
            node.put("tool", action.tool());
            node.put("dueMillis", action.dueMillis());
            try {
                node.set("args", MAPPER.readTree(action.argsJson()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalArgumentException("未完成动作的业务参数无效", failure);
            }
        }

        ArrayNode toolNodes = root.putArray("tools");
        for (String tool : new TreeSet<>(tools)) {
            ObjectNode node = toolNodes.addObject();
            node.put("name", tool);
            Long millis = config.actionMillisByTool().get(tool);
            node.put("actionMillis", millis == null ? 0L : millis);
            ArrayNode parameters = node.putArray("parameters");
            for (String[] field : toolParameters(tool)) {
                ObjectNode parameter = parameters.addObject();
                parameter.put("name", field[0]);
                parameter.put("type", field[1]);
            }
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("无法生成决定输入", failure);
        }
    }

    /** 一次开决定的请求；字段全部由可信宿主填充，模型不可见。 */
    public record Request(String personPartyId, String actionId, String wakeReason) {

        /** 校验静态字段。 */
        public Request {
            requireText(personPartyId, "人物");
            requireText(actionId, "动作标识");
            requireText(wakeReason, "唤醒原因");
        }
    }

    /** 一次派发描述：只含程序绑定字段与冻结输入，不含人物、任职或命令标识。 */
    public record Call(String runId, String actionId, String attemptId, int attemptNo,
                       long recoveryGeneration, String wakeReason, List<String> allowedTools,
                       String inputText) {

        /** 冻结集合。 */
        public Call {
            requireText(runId, "运行标识");
            requireText(actionId, "动作标识");
            requireText(attemptId, "尝试标识");
            requireText(wakeReason, "唤醒原因");
            requireText(inputText, "决定输入");
            allowedTools = List.copyOf(allowedTools);
        }
    }

    /** 最早未解决屏障：时间与同刻全部动作标识（稳定排序）。 */
    public record Barrier(long timeMillis, List<String> actionIds) {

        /** 校验字段。 */
        public Barrier {
            if (timeMillis < 0) {
                throw new IllegalArgumentException("屏障时间不能为负");
            }
            actionIds = List.copyOf(actionIds);
        }
    }

    /** 本人尚未完成的业务动作摘要；不含内部任务标识。 */
    public record PendingAction(String actionId, String tool, long dueMillis, String argsJson) {
        /** 校验字段。 */
        public PendingAction {
            requireText(actionId, "动作标识");
            requireText(tool, "工具");
            requireText(argsJson, "原业务参数");
            if (dueMillis <= 0) {
                throw new IllegalArgumentException("动作到期必须为正：" + dueMillis);
            }
        }
    }

    /**
     * 工具参数合同：字段名与类型名，用于让模型知道每个工具能返回什么。
     *
     * @param tool 工具名
     * @return 有序字段列表；未知工具返回空表
     */
    public static List<String[]> toolParameters(String tool) {
        if (tool == null) {
            return List.of();
        }
        return switch (tool) {
            case RoleTradingView.TOOL_READING_START -> List.of(
                    new String[]{"docId", "string"}, new String[]{"version", "long"});
            case RoleTradingView.TOOL_READING_CANCEL -> List.of(
                    new String[]{"docId", "string"}, new String[]{"version", "long"},
                    new String[]{"index", "long"});
            case RoleTradingView.TOOL_RESEARCH_SUBMIT -> List.of(
                    new String[]{"researchId", "string"},
                    new String[]{"organizationPartyId", "string"},
                    new String[]{"productOwnerId", "string"}, new String[]{"symbol", "string"},
                    new String[]{"docId", "string"}, new String[]{"title", "string"},
                    new String[]{"body", "string"}, new String[]{"citations", "array"});
            case RoleTradingView.TOOL_TARGET_SET -> List.of(
                    new String[]{"targetId", "string"}, new String[]{"expectedVersion", "int"},
                    new String[]{"organizationPartyId", "string"},
                    new String[]{"productOwnerId", "string"},
                    new String[]{"accountId", "string"}, new String[]{"symbol", "string"},
                    new String[]{"absoluteTargetQuantity", "long"},
                    new String[]{"priceLimit", "decimal"}, new String[]{"deadlineMillis", "long"},
                    new String[]{"sourceResearchId", "string"});
            case RoleTradingView.TOOL_TARGET_REVIEW -> List.of(
                    new String[]{"targetId", "string"}, new String[]{"expectedVersion", "int"},
                    new String[]{"approved", "boolean"},
                    new String[]{"maxTotalQuantity", "long"},
                    new String[]{"maxChildQuantity", "long"},
                    new String[]{"priceLimit", "decimal"},
                    new String[]{"validUntilMillis", "long"}, new String[]{"reason", "string"});
            case RoleTradingView.TOOL_TARGET_HALT -> List.of(
                    new String[]{"haltId", "string"}, new String[]{"targetId", "string"},
                    new String[]{"expectedVersion", "int"}, new String[]{"untilMillis", "long"},
                    new String[]{"reason", "string"});
            case RoleTradingView.TOOL_TARGET_CONFIRM -> List.of(
                    new String[]{"targetId", "string"}, new String[]{"expectedVersion", "int"},
                    new String[]{"riskRevision", "int"});
            case RoleTradingView.TOOL_TARGET_CANCEL -> List.of(
                    new String[]{"targetId", "string"}, new String[]{"expectedVersion", "int"});
            case RoleTradingView.TOOL_CHILD_SUBMIT -> List.of(
                    new String[]{"parentId", "string"}, new String[]{"parentVersion", "int"},
                    new String[]{"riskRevision", "int"}, new String[]{"childId", "string"},
                    new String[]{"quantity", "long"}, new String[]{"limitPrice", "decimal"});
            case RoleTradingView.TOOL_CHILD_CANCEL -> List.of(
                    new String[]{"parentId", "string"}, new String[]{"childId", "string"});
            case DecisionModel.TOOL_WAIT -> List.of();
            default -> List.of();
        };
    }

    /** 决定打开时冻结的人物与观察任职。 */
    public record Binding(String actionId, String personPartyId, String wakeReason,
                          Map<String, String> appointments) {

        /** 校验字段并冻结映射。 */
        public Binding {
            requireText(actionId, "动作标识");
            requireText(personPartyId, "人物");
            requireText(wakeReason, "唤醒原因");
            appointments = Collections.unmodifiableMap(new TreeMap<>(
                    appointments == null ? Map.of() : appointments));
        }

        /** @return 组织 → 任职标识（不可变，已排序） */
        @Override
        public Map<String, String> appointments() {
            return appointments;
        }
    }

    /** 已记录业务意图对应的内部任务引用。 */
    public record ActionRef(String actionId, String taskId, String tool, long dueMillis) {

        /** 校验字段。 */
        public ActionRef {
            requireText(actionId, "动作标识");
            requireText(taskId, "任务标识");
            requireText(tool, "工具");
            if (dueMillis <= 0) {
                throw new IllegalArgumentException("动作到期必须为正：" + dueMillis);
            }
        }
    }

    /** 旧代次或重复回执的只读归档；不参与预算结算。 */
    public record Archive(String actionId, String attemptId, int attemptNo, long generation,
                          String body, DecisionModel.Usage usage, String usageSource,
                          String verdict, String reason) {

        /** 校验身份字段。 */
        public Archive {
            requireText(actionId, "动作标识");
            requireText(attemptId, "尝试标识");
            if (attemptNo < 1 || generation < 1) {
                throw new IllegalArgumentException("归档身份必须从 1 开始");
            }
            body = body == null ? "" : body;
            usageSource = usageSource == null ? "" : usageSource;
            verdict = verdict == null ? "" : verdict;
            reason = reason == null ? "" : reason;
        }

        /**
         * 身份是否相同（不含结算结果）。
         *
         * @param other 另一条归档
         * @return 身份字段全等返回 true
         */
        public boolean sameKey(Archive other) {
            return other != null && actionId.equals(other.actionId)
                    && attemptId.equals(other.attemptId) && attemptNo == other.attemptNo
                    && generation == other.generation;
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static <T> List<T> sorted(List<T> items, java.util.function.Function<T, String> key) {
        List<T> copy = new ArrayList<>(items);
        copy.sort((left, right) -> key.apply(left).compareTo(key.apply(right)));
        return copy;
    }

    /** 持仓排序键：账户 + 代码。 */
    private static String holdingKey(RoleView.Holding holding) {
        return holding.accountId() + "#" + holding.symbol();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }

    /** 供恢复期复核使用的动作任务 JSON 重组键。 */
    static Map<String, String> sortedAppointments(Map<String, String> appointments) {
        return new LinkedHashMap<>(new TreeMap<>(appointments));
    }
}
