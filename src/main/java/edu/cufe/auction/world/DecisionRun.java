package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;
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
                          Map<String, ActionRef> actionRefs, List<Archive> archives,
                          @JsonInclude(JsonInclude.Include.NON_NULL)
                          Map<String, Dispatch> dispatches) {

    /**
     * 旧四参构造：没有派发映射，保持 schema1..9 的 JSON 形状。
     *
     * @param state      纯核决定状态
     * @param bindings   观察绑定
     * @param actionRefs 任务引用
     * @param archives   旧回执归档
     */
    public DecisionRun(DecisionModel.State state, Map<String, Binding> bindings,
                       Map<String, ActionRef> actionRefs, List<Archive> archives) {
        this(state, bindings, actionRefs, archives, null);
    }

    /** 工具合同版本；输入摘要把它与规范化输入一起冻结。 */
    public static final String TOOL_CONTRACT_VERSION = "role-decision-tools-v1";

    /** 信念档案的工具合同版本（新增三个认知工具）。 */
    public static final String TOOL_CONTRACT_VERSION_V2 = "role-decision-tools-v2";

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
        Map<String, Dispatch> frozenDispatches = null;
        if (dispatches != null) {
            frozenDispatches = new TreeMap<>();
            for (Map.Entry<String, Dispatch> entry : dispatches.entrySet()) {
                Dispatch dispatch = Objects.requireNonNull(entry.getValue(), "派发事实");
                if (!entry.getKey().equals(dispatch.attemptId())) {
                    throw new IllegalArgumentException("派发索引键与尝试标识不一致："
                            + entry.getKey());
                }
                frozenDispatches.put(entry.getKey(), dispatch);
            }
            frozenDispatches = Collections.unmodifiableMap(frozenDispatches);
        }
        dispatches = frozenDispatches;
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
                List.of(), config.live() ? Map.of() : null);
    }

    /**
     * 用候选纯核状态替换；绑定、引用与归档保持不变。
     *
     * @param next 候选状态
     * @return 替换后的子集
     */
    public DecisionRun withState(DecisionModel.State next) {
        return new DecisionRun(next, bindings, actionRefs, archives, dispatches);
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
        return new DecisionRun(state, next, actionRefs, archives, dispatches);
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
        return new DecisionRun(state, bindings, next, archives, dispatches);
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
        return new DecisionRun(state, bindings, actionRefs, next, dispatches);
    }

    /**
     * 追加一条持久派发事实；同一尝试同一事实幂等，异事实拒绝。
     *
     * @param dispatch 派发事实
     * @return 追加后的子集
     */
    public DecisionRun withDispatch(Dispatch dispatch) {
        Objects.requireNonNull(dispatch, "派发事实");
        Dispatch existing = dispatch(dispatch.attemptId());
        if (existing != null) {
            if (!existing.equals(dispatch)) {
                throw new IllegalArgumentException("同一尝试的派发事实不得改写："
                        + dispatch.attemptId());
            }
            return this;
        }
        Map<String, Dispatch> next = dispatches == null
                ? new TreeMap<>() : new TreeMap<>(dispatches);
        next.put(dispatch.attemptId(), dispatch);
        return new DecisionRun(state, bindings, actionRefs, archives, next);
    }

    /**
     * 按尝试标识取派发事实。
     *
     * @param attemptId 尝试标识
     * @return 派发事实；没有返回 null
     */
    public Dispatch dispatch(String attemptId) {
        return dispatches == null ? null : dispatches.get(attemptId);
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
        return inputText(view, wakeReason, cutoffTimeMillis, pending, tools, config,
                TOOL_CONTRACT_VERSION);
    }

    /**
     * 显式工具合同版本的输入：信念档案使用 v2，旧档案仍 v1（逐字不变）。
     *
     * @param view                本次观察视图
     * @param wakeReason          唤醒原因
     * @param cutoffTimeMillis    观察截止时点
     * @param pending             本人尚未完成的业务动作
     * @param tools               允许返回的工具
     * @param config              冻结配置
     * @param toolContractVersion 工具合同版本
     * @return 规范 JSON 正文
     */
    public static String inputText(RoleTradingView view, String wakeReason, long cutoffTimeMillis,
                                   List<PendingAction> pending, List<String> tools,
                                   DecisionModel.Config config, String toolContractVersion) {
        Objects.requireNonNull(view, "观察视图");
        Objects.requireNonNull(config, "冻结配置");
        Objects.requireNonNull(toolContractVersion, "工具合同版本");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("toolContractVersion", toolContractVersion);
        root.put("person", view.personPartyId());
        root.put("wakeReason", wakeReason);
        root.put("observedAt", cutoffTimeMillis);
        root.put("time", view.timeMillis());
        RoleView.RecallMetadata recall = view.profile().recall();
        if (recall != null) {
            // 只告诉模型"本次回忆是否被预算截断"，不给实验校准参数
            root.put("recallTruncated", recall.budgetTruncated());
        }

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
            if (experience.recall() != null) {
                ObjectNode origin = node.putObject("lastReadRef");
                origin.put("docId", experience.recall().lastReadRef().docId());
                origin.put("version", experience.recall().lastReadRef().version());
                origin.put("index", experience.recall().lastReadRef().index());
                node.put("lastReadAt", experience.recall().lastReadAt());
            }
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

        RoleView.Cognition cognition = view.profile().cognition();
        if (cognition != null) {
            ObjectNode node = root.putObject("cognition");
            ArrayNode beliefs = node.putArray("beliefs");
            for (RoleView.BeliefCard card : cognition.beliefs()) {
                ObjectNode item = beliefs.addObject();
                item.put("beliefId", card.beliefId());
                item.put("companyId", card.companyId());
                item.put("metric", card.metric());
                item.put("comparator", card.comparator().name());
                item.put("threshold", card.threshold());
                item.put("formedAt", card.formedAtMillis());
                item.put("targetAsOf", card.targetAsOfMillis());
                item.put("status", card.status());
                item.put("confidenceBand", card.confidenceBand());
                item.put("rationale", card.rationale());
                ArrayNode refs = item.putArray("evidence");
                for (RoleView.ReadingRef ref : card.evidence()) {
                    ObjectNode entry = refs.addObject();
                    entry.put("docId", ref.docId());
                    entry.put("version", ref.version());
                    entry.put("index", ref.index());
                }
                item.put("adoptedClaimId", card.adoptedClaimId());
                item.put("sourcePartyId", card.sourcePartyId());
                item.put("verificationPublisher", card.verificationPublisherPartyId());
                item.put("evaluatedAt", card.evaluatedAtMillis());
                item.put("factValue", card.factValue());
                item.put("evaluatedFactId", card.evaluatedFactId());
            }
            ArrayNode sources = node.putArray("sourceReliability");
            for (RoleView.SourceBand band : cognition.sources()) {
                ObjectNode item = sources.addObject();
                item.put("sourcePartyId", band.sourcePartyId());
                item.put("band", band.competenceBand());
            }
            ArrayNode habits = node.putArray("habits");
            for (RoleView.HabitCard habit : cognition.habits()) {
                ObjectNode item = habits.addObject();
                item.put("kind", habit.kind().name());
                item.put("level", habit.level());
                item.put("updatedAt", habit.updatedAtMillis());
            }
            ArrayNode claims = node.putArray("claims");
            for (RoleView.VisibleClaim claim : cognition.claims()) {
                ObjectNode item = claims.addObject();
                item.put("claimId", claim.claimId());
                item.put("kind", claim.kind());
                item.put("docId", claim.docId());
                item.put("version", claim.version());
                item.put("companyId", claim.companyId());
                item.put("metric", claim.metric());
                if (claim.comparator() != null) {
                    item.put("comparator", claim.comparator().name());
                }
                if (claim.threshold() >= 0) {
                    item.put("threshold", claim.threshold());
                }
                if (claim.targetAsOfMillis() >= 0) {
                    item.put("targetAsOf", claim.targetAsOfMillis());
                }
                item.put("authorPartyId", claim.authorPartyId());
                if (claim.value() >= 0) {
                    item.put("value", claim.value());
                }
                if (claim.asOfMillis() >= 0) {
                    item.put("asOf", claim.asOfMillis());
                }
            }
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
            if (recall != null) {
                // 新档显式区分"引用资格"与"当前正文可得性"，不给已忘内容留旁路
                node.put("bodyAvailable", note.body() != null);
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

        if (recall != null) {
            // 新档：当前准确权限，只含本人可见范围的状态，不含策略或内部标识
            ArrayNode permissionNodes = root.putArray("permissions");
            for (RoleView.Permission permission : sorted(view.profile().permissions(),
                    item -> item.action().name() + "|" + item.scope())) {
                ObjectNode node = permissionNodes.addObject();
                node.put("action", permission.action().name());
                node.put("scope", permission.scope());
                node.put("decision", permission.decision().name());
            }
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("无法生成决定输入", failure);
        }
    }

    /** 一次开决定的请求；字段全部由可信宿主填充，模型不可见。 */
    public record Request(String personPartyId, String actionId, String wakeReason,
                          @JsonInclude(JsonInclude.Include.NON_NULL)
                          DecisionModel.Route route) {

        /** 旧三参构造：没有场景路由（schema1..9）。 */
        public Request(String personPartyId, String actionId, String wakeReason) {
            this(personPartyId, actionId, wakeReason, null);
        }

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
                       String inputText,
                       @JsonInclude(JsonInclude.Include.NON_NULL)
                       DecisionModel.ProviderPlan plan) {

        /** 旧八参构造：没有冻结计划（schema1..9）。 */
        public Call(String runId, String actionId, String attemptId, int attemptNo,
                    long recoveryGeneration, String wakeReason, List<String> allowedTools,
                    String inputText) {
            this(runId, actionId, attemptId, attemptNo, recoveryGeneration, wakeReason,
                    allowedTools, inputText, null);
        }

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
            case RoleTradingView.TOOL_BELIEF_ASSERT -> List.of(
                    new String[]{"beliefId", "string"}, new String[]{"companyId", "string"},
                    new String[]{"comparator", "string"}, new String[]{"threshold", "long"},
                    new String[]{"targetAsOfMillis", "long"},
                    new String[]{"adoptedClaimId", "string|null"},
                    new String[]{"evidence", "array"}, new String[]{"rationale", "string|null"});
            case RoleTradingView.TOOL_FORECAST_PUBLISH -> List.of(
                    new String[]{"claimId", "string"}, new String[]{"docId", "string"},
                    new String[]{"title", "string"}, new String[]{"body", "string"},
                    new String[]{"publicSourceDescription", "string"},
                    new String[]{"readDurationMillis", "long"}, new String[]{"companyId", "string"},
                    new String[]{"comparator", "string"}, new String[]{"threshold", "long"},
                    new String[]{"targetAsOfMillis", "long"});
            case RoleTradingView.TOOL_COMPANY_REPORT_ORDERS -> List.of(
                    new String[]{"companyId", "string"}, new String[]{"asOfMillis", "long"});
            case RoleTradingView.TOOL_MEDIA_PROPOSE -> List.of(
                    new String[]{"storyId", "string"}, new String[]{"template", "string"},
                    new String[]{"topic", "string"});
            case RoleTradingView.TOOL_MEDIA_SUBMIT -> List.of(
                    new String[]{"storyId", "string"}, new String[]{"expectedRevision", "int"},
                    new String[]{"title", "string"}, new String[]{"body", "string"},
                    new String[]{"publicSourceDescription", "string"},
                    new String[]{"evidence", "array"});
            case RoleTradingView.TOOL_MEDIA_REVIEW -> List.of(
                    new String[]{"storyId", "string"}, new String[]{"expectedRevision", "int"},
                    new String[]{"approve", "boolean"}, new String[]{"note", "string"});
            case RoleTradingView.TOOL_MEDIA_PUBLISH -> List.of(
                    new String[]{"storyId", "string"}, new String[]{"expectedRevision", "int"});
            case RoleTradingView.TOOL_MEDIA_CORRECTION -> List.of(
                    new String[]{"storyId", "string"}, new String[]{"expectedRevision", "int"});
            case RoleTradingView.TOOL_MEDIA_CANCEL -> List.of();
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

    /**
     * 一次已持久派发的授权事实：先于网络返回、永不删除，重启后仍阻止同一尝试再次授权。
     *
     * @param attemptId              尝试标识（同时是索引键）
     * @param actionId               动作标识
     * @param attemptNo              尝试序号
     * @param recoveryGeneration     恢复代次
     * @param planDigest             冻结计划的稳定序列化摘要
     * @param inputDigest            冻结输入摘要
     * @param dispatchPosition       授权批次的已确认提交位置
     * @param dispatchTimeMillis     授权批次的世界时间
     * @param authorizedAtEpochMillis 宿主许可墙钟（只是审计点，不是提供方物理起点）
     */
    public record Dispatch(String attemptId, String actionId, int attemptNo,
                           long recoveryGeneration, String planDigest, String inputDigest,
                           long dispatchPosition, long dispatchTimeMillis,
                           long authorizedAtEpochMillis) {

        private static final java.util.regex.Pattern DIGEST =
                java.util.regex.Pattern.compile("[0-9a-f]{64}");

        /** 校验身份、序号、位置时间与 64 位小写十六进制摘要。 */
        public Dispatch {
            requireText(attemptId, "尝试标识");
            requireText(actionId, "动作标识");
            if (attemptNo < 1) {
                throw new IllegalArgumentException("派发尝试序号从 1 开始：" + attemptNo);
            }
            if (recoveryGeneration < 1) {
                throw new IllegalArgumentException("派发恢复代次从 1 开始：" + recoveryGeneration);
            }
            if (planDigest == null || !DIGEST.matcher(planDigest).matches()) {
                throw new IllegalArgumentException("计划摘要必须是 64 位小写十六进制");
            }
            if (inputDigest == null || !DIGEST.matcher(inputDigest).matches()) {
                throw new IllegalArgumentException("输入摘要必须是 64 位小写十六进制");
            }
            if (dispatchPosition < 1) {
                throw new IllegalArgumentException("派发提交位置必须为正：" + dispatchPosition);
            }
            if (dispatchTimeMillis < 0 || authorizedAtEpochMillis < 0) {
                throw new IllegalArgumentException("派发时间不能为负");
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
