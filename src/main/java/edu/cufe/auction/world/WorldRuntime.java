package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.ManufacturingRun.ReportEntry;
import edu.cufe.auction.world.SecuritiesLedger.ActionReport;
import edu.cufe.auction.world.SecuritiesLedger.SellReservation;
import edu.cufe.auction.world.WorldExchange.OrderReport;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 可恢复的世界运行：单一日志、可序列化待办、现金子集。
 *
 * <p><b>提交边界</b>：每个命令/任务形成一个批次，批次里同时保存领域事件与**完整不可变子集状态**
 * （时钟、现金账户、待办、已投递回报、已用任务标识、命令回报）。只有
 * {@link WorldJournal#commit} 强制落盘成功后，运行才发布新状态；失败则保留旧快照、
 * 保留待办并暂停运行。</p>
 *
 * <p><b>恢复</b>：只解码并校验已提交状态，不重跑任务处理、不询问模型、不重新发放开局。
 * 恢复出来的是“待办数据”，执行器由 {@link #advanceTo(long)} 按 kind 解析。</p>
 *
 * <p><b>能力边界</b>：当前只启用现金转账、回报投递，以及可选的开局登记、证券清算夹具
 * （批次、卖出冻结、撤销、同批清算成交）、世界订单撮合（限价/市价、T+1 实验日、撤单、收盘）、
 * 制造经营与董事会治理（仅受信管理夹具）。撮合档案下现金冻结由活动买限价单派生、卖单预留由
 * 批次台账唯一维护；仍没有角色交易授权、费用、涨跌停、手数或真实交易日历。规则、模块、模型
 * 版本在构造期校验，未启用能力在提交前即被拒绝。快照是管理端只读视图，不面向不可信角色。</p>
 */
public final class WorldRuntime implements AutoCloseable {

    private static final String OPEN_COMMAND = "__open__";
    private static final String SNAPSHOT_TYPE = "STATE_SNAPSHOT_V1";
    /** 纯现金/登记档案的世界状态版本（保持原样）。 */
    private static final int STATE_SCHEMA = 1;
    /** 含完整证券子集状态的世界状态版本（M2.3）。 */
    private static final int STATE_SCHEMA_SECURITIES = 2;
    /** 含完整世界订单/成交子集状态的世界状态版本（M2.4b）。 */
    private static final int STATE_SCHEMA_EXCHANGE = 3;
    /** 含完整制造经营子集状态的世界状态版本（M3a.2）。 */
    private static final int STATE_SCHEMA_MANUFACTURING = 4;
    /** 含完整董事会治理子集状态的世界状态版本（M3b.2）。 */
    private static final int STATE_SCHEMA_GOVERNANCE = 5;
    /** 含任职授权与资料阅读子集状态的世界状态版本（M4a.2/M4b.2）。 */
    private static final int STATE_SCHEMA_ROLE_READING = 6;
    /** 含四岗交易工作流子集状态的世界状态版本（M4c）。 */
    private static final int STATE_SCHEMA_ROLE_TRADING = 7;
    /** 含决定屏障与尝试子集状态的世界状态版本（M4d）。 */
    private static final int STATE_SCHEMA_DECISIONS = 8;
    /** 未实施有效预算时拒绝采购/投产。 */
    public static final String REASON_BUDGET_NOT_SET = BoardModel.REASON_BUDGET_NOT_SET;
    /** 预算可用额不足，候选业务整体丢弃。 */
    public static final String REASON_BUDGET_EXCEEDED = BoardModel.REASON_BUDGET_EXCEEDED;
    /** 预算行指向已关期间：不得事后授权或实施。 */
    public static final String REASON_BUDGET_PERIOD_CLOSED = "BUDGET_PERIOD_CLOSED";
    /** 显式座位不是该会议到会且未回避的成员。 */
    public static final String REASON_NOT_ELIGIBLE_SEAT = "NOT_ELIGIBLE_SEAT";

    /** 公司经营账户不能通过普通转账绕过经营分录。 */
    public static final String REASON_COMPANY_LEDGER_FORBIDDEN = "COMPANY_LEDGER_FORBIDDEN";
    /** 公司证券申报必须走经营分录，本档案不接受直接下单。 */
    public static final String REASON_COMPANY_TRADING_FORBIDDEN = "COMPANY_TRADING_FORBIDDEN";

    /** 系统自有动作（如强制关期）的报告命令前缀，与外部原 command 区分。 */
    private static final String SYSTEM_COMMAND_PREFIX = "system:";

    /** 金额零值，保持两位小数口径。 */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** 同一受益主体的两个账户不能通过清算夹具自成交。 */
    public static final String REASON_SELF_TRADE_PREVENTED = "SELF_TRADE_PREVENTED";
    /** 买方可用现金不足。 */
    public static final String REASON_INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    /** 调用方不是该账户的财产主体。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 账户或证券未登记。 */
    public static final String REASON_UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RunManifest manifest;
    private final OpeningRegistry registry;
    /** 制造开局配置；只有 stock-company-experiment-v1 档案非空。 */
    private final ManufacturingRun.Config manufacturingConfig;
    /** 董事会章程；只有 governed-stock-company-experiment-v1 档案非空。 */
    private final BoardModel.Charter charter;
    /** 任职开局配置；只有 informed-stock-company-experiment-v1 档案非空。 */
    private final RoleRun.Config roleConfig;
    /** 资料阅读开局配置；只有 informed-stock-company-experiment-v1 档案非空。 */
    private final InformationRun.Config informationConfig;
    /** 冻结的决定配置；只有 decision-experiment-v1 档案非空。 */
    private final DecisionModel.Config decisionConfig;
    /** 有界回调队列：异步线程只 offer，不读写世界状态。 */
    private final java.util.concurrent.ArrayBlockingQueue<DecisionCandidate> decisionQueue;
    /** 进程本地派发/应用门闸；构造后一律关闭，必须显式 beginDecisionSession。 */
    private boolean decisionGateOpen;
    /** 已派发但未结算的尝试 → 宿主派发许可的墙钟标记（只算等待与超时，不进世界时间）。 */
    private final Map<String, DispatchMark> decisionInFlight = new TreeMap<>();
    /** 严格 DTO 映射：未知字段、尾随内容与浮点截断一律失败。 */
    private final ObjectMapper decisionMapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    /** 规范化后的开局现金清单（按账户标识排序），供逐批权限来源校验复用。 */
    private final List<OpeningAccount> orderedOpenings;
    private final long seed;
    private final WorldJournal journal;
    private final Thread writerThread = Thread.currentThread();
    private WorldState state;
    private volatile boolean paused;
    private boolean closed;

    /**
     * 已投递的业务回报（只读管理视图）。
     *
     * @param noticeId          回报标识
     * @param taskId            产生该回报的任务
     * @param commandId         原始命令
     * @param accepted          转账是否成功
     * @param reason            结果代码
     * @param fromId            付款账户
     * @param toId              收款账户
     * @param amount            金额
     * @param deliveredAtMillis 投递的模拟毫秒
     */
    public record Notice(String noticeId, String taskId, String commandId, boolean accepted,
                         String reason, String fromId, String toId, BigDecimal amount,
                         long deliveredAtMillis) {
    }

    /**
     * 管理端只读快照：同一完整提交位置的全部子集状态。
     *
     * <p>{@code securities} 是同一截止位置的证券子集（批次、预留、业务结果），只对启用了证券清算
     * 夹具的运行存在；{@code openingRegistry()} 仍然只表示开局分配，不是交易后的实时持仓。</p>
     *
     * @param position    最后完整提交位置
     * @param timeMillis  当前模拟毫秒
     * @param lastPhase   最后完成的阶段名
     * @param accounts    现金账户（不可变）
     * @param securities  证券子集；未启用时为空
     * @param exchange    世界订单/成交子集；仅撮合档案存在
     * @param manufacturing 制造经营子集；仅 stock-company-experiment-v1 存在
     * @param governance    董事会治理子集；仅 governed-stock-company-experiment-v1 存在
     * @param roles        任职授权子集；仅 informed-stock-company-experiment-v1 存在
     * @param information  资料阅读子集；仅 informed-stock-company-experiment-v1 存在
     * @param trading      四岗交易子集；仅 role-trading-experiment-v1 存在
     * @param decisions    决定子集；仅 decision-experiment-v1 存在
     * @param pending     未完成待办（不可变）
     * @param delivered   已投递回报（不可变）
     * @param usedTaskIds 已用任务标识（不可变）
     */
    public record Snapshot(long position, long timeMillis, String lastPhase,
                           Map<String, CashLedger.CashAccount> accounts,
                           Optional<SecuritiesLedger.State> securities,
                           Optional<WorldExchange.State> exchange,
                           Optional<ManufacturingRun> manufacturing,
                           Optional<BoardRun> governance,
                           Optional<RoleRun> roles,
                           Optional<InformationRun> information,
                           Optional<RoleTradingRun> trading,
                           Optional<DecisionRun> decisions,
                           List<PendingTask> pending, List<Notice> delivered,
                           Set<String> usedTaskIds) {
        /** 防御性冻结所有集合。 */
        public Snapshot {
            accounts = Collections.unmodifiableMap(new TreeMap<>(accounts));
            securities = Objects.requireNonNull(securities, "securities");
            exchange = Objects.requireNonNull(exchange, "exchange");
            manufacturing = Objects.requireNonNull(manufacturing, "manufacturing");
            governance = Objects.requireNonNull(governance, "governance");
            roles = Objects.requireNonNull(roles, "roles");
            information = Objects.requireNonNull(information, "information");
            trading = Objects.requireNonNull(trading, "trading");
            decisions = Objects.requireNonNull(decisions, "decisions");
            pending = List.copyOf(pending);
            delivered = List.copyOf(delivered);
            usedTaskIds = Collections.unmodifiableSet(new LinkedHashSet<>(usedTaskIds));
        }
    }

    /** 排程命令的规范化内容，用于幂等比对。 */
    private record ScheduleCommand(String commandId, String taskId, long dueTimeMillis,
                                   String actorOwnerId, String fromId, String toId,
                                   BigDecimal amount, long receiptDelayMillis) {
    }

    /** 转账任务参数；回报到期时点在排程阶段算好并持久化，完成时不再做可能溢出的加法。 */
    private record TransferPayload(String commandId, String actorOwnerId, String fromId, String toId,
                                   BigDecimal amount, long receiptDelayMillis, long noticeDueMillis) {
    }

    /** 回报投递任务参数；sourceTaskId 指向产生回报的原任务。 */
    private record NoticePayload(String noticeId, String sourceTaskId, String commandId,
                                 String fromId, String toId, BigDecimal amount, boolean accepted,
                                 String reason) {
    }

    /** 预留卖出的规范化命令内容（幂等比对用）。 */
    private record ReserveCommand(String commandId, String taskId, long dueTimeMillis,
                                  String actorOwnerId, String accountId, String symbol,
                                  String reservationId, long quantity) {
    }

    /** 预留卖出任务参数。 */
    private record ReservePayload(String commandId, String actorOwnerId, String accountId,
                                  String symbol, String reservationId, long quantity) {
    }

    /** 撤销预留的规范化命令内容。 */
    private record CancelCommand(String commandId, String taskId, long dueTimeMillis,
                                 String actorOwnerId, String reservationId) {
    }

    /** 撤销预留任务参数。 */
    private record CancelPayload(String commandId, String actorOwnerId, String reservationId) {
    }

    /** 系统清算夹具成交的规范化命令内容。 */
    private record FillCommand(String commandId, String taskId, long dueTimeMillis,
                               String reservationId, String buyerAccountId, BigDecimal price,
                               long quantity) {
    }

    /** 系统清算夹具成交任务参数。 */
    private record FillPayload(String commandId, String reservationId, String buyerAccountId,
                               BigDecimal price, long quantity) {
    }

    /** 世界订单的规范化命令内容（幂等比对用）。 */
    private record OrderCommand(String commandId, String taskId, long dueTimeMillis,
                                String actorOwnerId, String accountId, String symbol,
                                Side side, OrderType type, BigDecimal price, long quantity) {
    }

    /** 世界订单任务参数。 */
    private record OrderPayload(String commandId, String actorOwnerId, String accountId,
                                String symbol, Side side, OrderType type, BigDecimal price,
                                long quantity) {
    }

    /** 撤单命令内容。 */
    private record CancelOrderCommand(String commandId, String taskId, long dueTimeMillis,
                                      String actorOwnerId, long orderId) {
    }

    /** 撤单任务参数。 */
    private record CancelOrderPayload(String commandId, String actorOwnerId, long orderId) {
    }

    /** 收盘命令内容。 */
    private record CloseMarketCommand(String commandId, String taskId, long dueTimeMillis) {
    }

    /** 收盘任务参数。 */
    private record CloseMarketPayload(String commandId) {
    }

    /** 外部制造命令的规范化内容（幂等比对用）；六类命令共用一种形状。 */
    private record ManufacturingCommand(String commandId, String taskId, long dueTimeMillis,
                                        String actorOwnerId, String businessId, String resultId,
                                        long quantity, BigDecimal unitPrice,
                                        long promisedDeliveryAt, long period) {
    }

    /** 制造任务参数；到期只按已确认内容执行，不再向调用方取值。 */
    private record ManufacturingPayload(String commandId, String actorOwnerId, String businessId,
                                        String resultId, long quantity, BigDecimal unitPrice,
                                        long promisedDeliveryAt, long period) {
    }

    /** 自动子任务参数：只带父命令与关联业务标识（或期间）。 */
    private record ManufacturingChildPayload(String commandId, String businessId, long period) {
    }

    /** 任职任务的规范内容与任务参数；撤销动作只填 commandId 与 appointmentId。 */
    private record AppointmentPayload(String commandId, String appointmentId, String personPartyId,
                                      String organizationPartyId, String role, long startsAt,
                                      long endsAt) {
    }

    /** 资料发表任务的规范内容与任务参数；追加版本时发布者与受众保留空串。 */
    private record PublishPayload(String commandId, String docId, String publisherPartyId,
                                  String audienceKind, String audiencePartyId, String title,
                                  String body, String publicSourceDescription,
                                  long readDurationMillis, List<String> internalSourceIds) {
    }

    /** 阅读任务的规范内容与任务参数；取消动作按本人范围引用（资料/版本/本人序号）解析。 */
    private record ReadingPayload(String commandId, String personPartyId, String docId, long version,
                                  String readId, long refIndex) {
    }

    /** 外部治理命令的规范内容与任务参数；无效字段保持空串/0，便于稳定序列化。 */
    private record GovernancePayload(String commandId, String seatId, String meetingId,
                                     long materialDeadline, long opensAt, long closesAt,
                                     List<String> attendeeSeatIds, List<String> recusedSeatIds,
                                     String materialId, String sourceRef, long version,
                                     List<BoardModel.BudgetLine> lines, String vote) {
    }

    /** 完整不可变子集状态；每批提交保存一份，恢复逐批校验后取最后一份。 */
    private record WorldState(int schemaVersion, String runId, long position, long timeMillis,
                              String lastPhase, Map<String, CashLedger.CashAccount> accounts,
                              List<PendingTask> pending, List<Notice> delivered,
                              Set<String> usedTaskIds, Map<String, String> commandContents,
                              Map<String, String> commandOutcomes,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              SecuritiesLedger.State securities,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              WorldExchange.State exchange,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              ManufacturingRun manufacturing,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              BoardRun governance,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              RoleRun roles,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              InformationRun information,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              RoleTradingRun trading,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              DecisionRun decisions) {

        WorldState {
            accounts = Collections.unmodifiableMap(new TreeMap<>(accounts));
            pending = List.copyOf(pending);
            delivered = List.copyOf(delivered);
            usedTaskIds = Collections.unmodifiableSet(new LinkedHashSet<>(usedTaskIds));
            commandContents = Collections.unmodifiableMap(new TreeMap<>(commandContents));
            commandOutcomes = Collections.unmodifiableMap(new TreeMap<>(commandOutcomes));
        }
    }

    /**
     * 打开或恢复一次现金子集运行。
     *
     * @param file     运行专用日志（本类独占）
     * @param manifest 运行清单
     * @param openings 开局现金清单，必须带历史来源
     * @throws IOException 日志不可用、清单或开局与持久记录不一致
     */
    public WorldRuntime(Path file, RunManifest manifest, List<OpeningAccount> openings)
            throws IOException {
        this(file, manifest, openings, null, null);
    }

    /**
     * 打开或恢复一次带开局登记的运行。
     *
     * <p>登记与清单必须成对：registry 清单缺登记、或现金清单多带登记，都在创建任何文件之前拒绝。
     * 登记先与现有现金账户对齐（属主可解析、持股账户存在、库存股归发行组织），再写入第一批。</p>
     *
     * @param file     运行日志（本类独占）
     * @param manifest 运行清单
     * @param openings 现金开局清单
     * @param registry 开局登记；现金模式为 null
     * @throws IOException 日志不可用、清单/开局/登记与持久记录不一致
     */
    public WorldRuntime(Path file, RunManifest manifest, List<OpeningAccount> openings,
                        OpeningRegistry registry) throws IOException {
        this(file, manifest, openings, registry, null, null);
    }

    /**
     * 打开或恢复一次公司经营档案（{@code stock-company-experiment-v1}）。
     *
     * <p>配置与模块必须成对：manufacturing 清单缺配置、或旧档案多带配置都在创建任何文件之前拒绝。
     * 开局还要在写盘前确认：公司财产主体登记为 ORGANIZATION、其组织就是证券发行人、
     * 公司只有一个经营现金账户（含零余额）、公司没有持股/库存股等未建模资产。
     * 配置会绑进第一批开局内容，换一套配置重开同一日志会在恢复期被拒绝。</p>
     *
     * @param file          运行日志（本类独占）
     * @param manifest      运行清单
     * @param openings      现金开局清单
     * @param registry      开局登记，制造档案必需
     * @param manufacturing 制造开局配置；旧档案为 null
     * @throws IOException 日志不可用、清单/开局/登记/配置与持久记录不一致
     */
    public WorldRuntime(Path file, RunManifest manifest, List<OpeningAccount> openings,
                        OpeningRegistry registry, ManufacturingRun.Config manufacturing)
            throws IOException {
        this(file, manifest, openings, registry, manufacturing, null);
    }

    /**
     * 打开或恢复一次公司经营档案（{@code stock-company-experiment-v1}）。
     *
     * <p>配置与模块必须成对：manufacturing 清单缺配置、或旧档案多带配置都在创建任何文件之前拒绝。
     * 开局还要在写盘前确认：公司财产主体登记为 ORGANIZATION、其组织就是证券发行人、
     * 公司只有一个经营现金账户（含零余额的第二账户）、公司没有持股/库存股等未建模资产。
     * 配置会绑进第一批开局内容，换一套配置重开同一日志会在恢复期被拒绝。</p>
     *
     * @param file          运行日志（本类独占）
     * @param manifest      运行清单
     * @param openings      现金开局清单
     * @param registry      开局登记，制造档案必需
     * @param manufacturing 制造开局配置；旧档案为 null
     * @param charter       董事会章程；只有治理档案非空
     * @throws IOException 日志不可用、清单/开局/登记/配置与持久记录不一致
     */
    public WorldRuntime(Path file, RunManifest manifest, List<OpeningAccount> openings,
                        OpeningRegistry registry, ManufacturingRun.Config manufacturing,
                        BoardModel.Charter charter) throws IOException {
        this(file, manifest, openings, registry, manufacturing, charter, null, null);
    }

    /**
     * 打开或恢复一次含任职与资料阅读的公司档案（{@code informed-stock-company-experiment-v1}）。
     *
     * <p>两个配置与对应模块必须成对：清单缺配置、或旧档案多带配置，都在创建任何文件之前拒绝。
     * 首批校验还包括：登记读者必须是已登记 PERSON；产品管理授权与初始任职必须通过
     * {@link RoleAccess#open} 与 {@link RoleAccess#validateAgainst} 的来源校验；组织受众必须是
     * 已登记 ORGANIZATION、直投受众与发布者必须是配置中的登记读者。配置随第一批开局内容冻结，
     * 换一套配置重开同一日志会在恢复期被拒绝。</p>
     *
     * @param file        运行日志（本类独占）
     * @param manifest    运行清单
     * @param openings    现金开局清单
     * @param registry    开局登记
     * @param manufacturing 制造开局配置
     * @param charter     董事会章程
     * @param roles       任职开局配置；旧档案为 null
     * @param information 资料阅读开局配置；旧档案为 null
     * @throws IOException 日志不可用、清单/开局/登记/配置与持久记录不一致
     */
    public WorldRuntime(Path file, RunManifest manifest, List<OpeningAccount> openings,
                        OpeningRegistry registry, ManufacturingRun.Config manufacturing,
                        BoardModel.Charter charter, RoleRun.Config roles,
                        InformationRun.Config information) throws IOException {
        this(file, manifest, openings, registry, manufacturing, charter, roles, information, null);
    }

    /**
     * 打开或恢复一次含决定屏障与尝试的公司档案（{@code decision-experiment-v1}，schema8）。
     *
     * <p>决定配置与 {@code decisions:1} 模块严格成对：缺配置或多带配置都在创建任何文件之前拒绝。
     * 配置随第一批开局内容冻结，换一套预算/耗时重开同一日志会在恢复期被拒绝。</p>
     *
     * @param file        运行日志（本类独占）
     * @param manifest    运行清单
     * @param openings    现金开局清单
     * @param registry    开局登记
     * @param manufacturing 制造开局配置
     * @param charter     董事会章程
     * @param roles       任职开局配置
     * @param information 资料阅读开局配置
     * @param decisions   冻结的决定配置；旧档案为 null
     * @throws IOException 日志不可用、清单/开局/登记/配置与持久记录不一致
     */
    public WorldRuntime(Path file, RunManifest manifest, List<OpeningAccount> openings,
                        OpeningRegistry registry, ManufacturingRun.Config manufacturing,
                        BoardModel.Charter charter, RoleRun.Config roles,
                        InformationRun.Config information, DecisionModel.Config decisions)
            throws IOException {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        if (manifest.hasRegistry() && registry == null) {
            throw new IllegalArgumentException("规则档案 " + manifest.rulesVersion()
                    + " 必须提供开局登记");
        }
        if (!manifest.hasRegistry() && registry != null) {
            throw new IllegalArgumentException("现金模式运行不接受开局登记");
        }
        if (manifest.hasManufacturing() && manufacturing == null) {
            throw new IllegalArgumentException("规则档案 " + manifest.rulesVersion()
                    + " 必须提供制造开局配置");
        }
        if (!manifest.hasManufacturing() && manufacturing != null) {
            throw new IllegalArgumentException("未启用制造模块的运行不接受制造配置："
                    + manifest.rulesVersion());
        }
        if (manifest.hasGovernance() && charter == null) {
            throw new IllegalArgumentException("规则档案 " + manifest.rulesVersion()
                    + " 必须提供董事会章程");
        }
        if (!manifest.hasGovernance() && charter != null) {
            throw new IllegalArgumentException("未启用治理模块的运行不接受董事会章程："
                    + manifest.rulesVersion());
        }
        if (manifest.hasGovernance() && !manifest.hasManufacturing()) {
            throw new IllegalArgumentException("治理档案必须同时启用制造经营模块："
                    + manifest.rulesVersion());
        }
        if (manifest.hasRoles() && roles == null) {
            throw new IllegalArgumentException("规则档案 " + manifest.rulesVersion()
                    + " 必须提供任职开局配置");
        }
        if (!manifest.hasRoles() && roles != null) {
            throw new IllegalArgumentException("未启用任职模块的运行不接受任职配置："
                    + manifest.rulesVersion());
        }
        if (manifest.hasInformation() && information == null) {
            throw new IllegalArgumentException("规则档案 " + manifest.rulesVersion()
                    + " 必须提供资料阅读开局配置");
        }
        if (!manifest.hasInformation() && information != null) {
            throw new IllegalArgumentException("未启用资料阅读模块的运行不接受阅读配置："
                    + manifest.rulesVersion());
        }
        if (manifest.hasRoles() != manifest.hasInformation()) {
            throw new IllegalArgumentException("任职与资料阅读模块必须同时启用："
                    + manifest.rulesVersion());
        }
        if (manifest.hasDecisions() && (!manifest.hasTrading() || !manifest.hasRoles()
                || !manifest.hasInformation())) {
            throw new IllegalArgumentException("决定档案必须建立在四岗交易与阅读档案之上："
                    + manifest.rulesVersion());
        }
        if (manifest.hasDecisions() && decisions == null) {
            throw new IllegalArgumentException("规则档案 " + manifest.rulesVersion()
                    + " 必须提供冻结的决定配置");
        }
        if (!manifest.hasDecisions() && decisions != null) {
            throw new IllegalArgumentException("未启用决定模块的运行不接受决定配置："
                    + manifest.rulesVersion());
        }
        if (manifest.hasRoles() && (!manifest.hasGovernance()
                || !manifest.hasManufacturing() || !manifest.hasMatching())) {
            throw new IllegalArgumentException("任职与资料阅读档案必须建立在完整公司治理档案之上："
                    + manifest.rulesVersion());
        }
        this.registry = registry;
        this.manufacturingConfig = manufacturing;
        this.charter = charter;
        this.roleConfig = roles;
        this.informationConfig = information;
        this.decisionConfig = decisions;
        this.decisionQueue = decisions == null ? null
                : new java.util.concurrent.ArrayBlockingQueue<>(
                        decisions.limits().queueCapacity());
        this.seed = manifest.seed();
        List<OpeningAccount> ordered = new ArrayList<>(Objects.requireNonNull(openings, "openings"));
        ordered.sort(Comparator.comparing(OpeningAccount::accountId));
        Map<String, CashLedger.CashAccount> accounts = new TreeMap<>();
        for (OpeningAccount opening : ordered) {
            if (accounts.putIfAbsent(opening.accountId(),
                    new CashLedger.CashAccount(opening.ownerId(), opening.cash(), opening.source()))
                    != null) {
                throw new IllegalArgumentException("重复开局账户：" + opening.accountId());
            }
        }
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("开局账户不能为空");
        }
        if (registry != null) {
            // 在创建日志文件之前完成对齐：属主可解析、持股账户存在、库存股归发行组织
            registry.validateAgainst(ordered);
        }
        if (manufacturingConfig != null) {
            // 开局错误必须在写盘前拒绝：组织映射、发行人、公司第二账户、公司持股
            validateManufacturingOpening(ordered);
        }
        if (charter != null) {
            // 章程组织/五席/登记人物在写盘前拒绝
            validateGovernanceOpening();
        }
        this.orderedOpenings = List.copyOf(ordered);
        if (roleConfig != null) {
            // 任职与阅读开局：mandates/初始任职/登记读者在写盘前拒绝
            validateRoleReadingOpening();
        }
        if (manifest.hasTrading()) {
            // 四岗交易开局：初始任职人必须具备已配置的信息读者能力，写盘前拒绝
            validateTradingOpening();
        }
        WorldState initial = initialState(accounts);
        this.journal = new WorldJournal(file, manifest.runId());
        try {
            if (journal.commits().isEmpty()) {
                String content = openingContent(ordered);
                List<WorldEvent> events = new ArrayList<>();
                events.add(new WorldEvent(OPEN_COMMAND + ":manifest", "RUN_MANIFEST_V1", content));
                if (registry != null) {
                    events.add(new WorldEvent(OPEN_COMMAND + ":registry", "OPENING_REGISTRY_V1",
                            mapper.writeValueAsString(registry)));
                }
                events.add(snapshotEvent(initial));
                journal.commit(OPEN_COMMAND, content, "OPENED", events);
            }
            this.state = restore(ordered, initial);
        } catch (IOException | RuntimeException failure) {
            journal.close();
            throw failure;
        }
    }

    /**
     * 排程一次现金转账：排程本身持久提交，到期才结算。
     *
     * @param commandId          外部命令标识，重发返回原回报
     * @param taskId             任务标识，运行内不复用
     * @param dueTimeMillis      到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId       可信调用方解析的财产主体
     * @param fromId             付款账户
     * @param toId               收款账户
     * @param amount             正数、精确到分
     * @param receiptDelayMillis 回报渠道延迟，非负
     * @return 规范化回报文本；重发返回同一文本
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时间非法
     */
    public synchronized String scheduleTransfer(String commandId, String taskId, long dueTimeMillis,
                                                String actorOwnerId, String fromId, String toId,
                                                BigDecimal amount, long receiptDelayMillis)
            throws IOException {
        requireOpen();
        requireWriter();
        if (paused) {
            throw new IllegalStateException("运行已暂停，不接受新排程；须先恢复");
        }
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(actorOwnerId, "财产主体");
        requireText(fromId, "付款账户");
        requireText(toId, "收款账户");
        if (manufacturingConfig != null) {
            String company = manufacturingConfig.accounting().companyAccountId();
            if (company.equals(fromId) || company.equals(toId)) {
                throw new IllegalArgumentException("公司经营账户只能通过制造经营分录变动，"
                        + "不接受普通转账：" + company);
            }
        }
        if (receiptDelayMillis < 0) {
            throw new IllegalArgumentException("回报延迟不能为负");
        }
        BigDecimal normalized = CashLedger.money(amount);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("转账金额必须为正");
        }
        long noticeDueMillis;
        try {
            noticeDueMillis = Math.addExact(dueTimeMillis, Math.max(1L, receiptDelayMillis));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("到期时间与回报延迟组合溢出，无法表达回报时点", overflow);
        }
        ScheduleCommand command = new ScheduleCommand(commandId, taskId, dueTimeMillis,
                actorOwnerId, fromId, toId, normalized, receiptDelayMillis);
        String content = mapper.writeValueAsString(command);
        String previousContent = state.commandContents().get(commandId);
        if (previousContent != null) {
            if (!previousContent.equals(content)) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return state.commandOutcomes().get(commandId);
        }
        if (dueTimeMillis <= state.timeMillis()) {
            throw new IllegalArgumentException("到期时间必须晚于当前时点：" + state.timeMillis());
        }
        if (state.usedTaskIds().contains(taskId)) {
            throw new IllegalArgumentException("任务标识运行内不复用：" + taskId);
        }
        CashLedger.CashAccount from = state.accounts().get(fromId);
        if (from == null || !from.ownerId().equals(actorOwnerId)) {
            throw new IllegalArgumentException("该财产主体无权动用账户：" + fromId);
        }
        if (!state.accounts().containsKey(toId)) {
            throw new IllegalArgumentException("未知收款账户：" + toId);
        }

        TransferPayload payload = new TransferPayload(commandId, actorOwnerId, fromId, toId,
                normalized, receiptDelayMillis, noticeDueMillis);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.CASH_TRANSFER_V1,
                mapper.writeValueAsString(payload));
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.add(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(taskId);
        Map<String, String> contents = new TreeMap<>(state.commandContents());
        contents.put(commandId, content);
        Map<String, String> outcomes = new TreeMap<>(state.commandOutcomes());
        String outcome = "SCHEDULED:" + taskId;
        outcomes.put(commandId, outcome);

        WorldState candidate = next(state.timeMillis(), state.lastPhase(), state.accounts(),
                pending, state.delivered(), used, contents, outcomes, state.securities(),
                state.exchange());
        commitAndApply("cmd:" + commandId, content, outcome,
                List.of(new WorldEvent("task-scheduled:" + taskId, "TASK_SCHEDULED_V1",
                        mapper.writeValueAsString(task))), candidate);
        return outcome;
    }

    /**
     * 排程一次预留卖出：只允许管理端的系统清算夹具调用，冻结合格可卖批次。
     *
     * <p>排程本身持久提交，到期才按最新持仓复核；因此这里只做结构性校验（属主、账户、证券、
     * 数量、时点、标识唯一）。数量不足等业务原因在执行期以 {@link ActionReport} 记录，
     * 不改动资产。重发同一命令返回原 {@code SCHEDULED} 回报，不会因时点已过再排一次。</p>
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  可信调用方解析的财产主体
     * @param accountId     卖方账户
     * @param symbol        证券代码
     * @param reservationId 预留标识，运行内不复用
     * @param quantity      预留数量，必须为正
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、权属、能力或时间非法
     */
    public synchronized String scheduleReserveSell(String commandId, String taskId,
                                                   long dueTimeMillis, String actorOwnerId,
                                                   String accountId, String symbol,
                                                   String reservationId, long quantity)
            throws IOException {
        requireSecuritiesEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(actorOwnerId, "财产主体");
        requireText(accountId, "账户");
        requireText(symbol, "证券代码");
        requireText(reservationId, "预留标识");
        if (quantity <= 0) {
            throw new IllegalArgumentException("预留数量必须为正：" + quantity);
        }
        requireClearingFixture();
        ReserveCommand command = new ReserveCommand(commandId, taskId, dueTimeMillis, actorOwnerId,
                accountId, symbol, reservationId, quantity);
        String content = mapper.writeValueAsString(command);
        String replay = state.commandOutcomes().get(commandId);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return replay;
        }
        requireSchedulable(dueTimeMillis, taskId);
        CashLedger.CashAccount account = state.accounts().get(accountId);
        if (account == null || !account.ownerId().equals(actorOwnerId)) {
            throw new IllegalArgumentException("该财产主体无权动用账户：" + accountId);
        }
        if (registry.security(symbol) == null) {
            throw new IllegalArgumentException("未知证券：" + symbol);
        }
        if (state.securities().usedReservationIds().contains(reservationId)) {
            throw new IllegalArgumentException("预留标识已使用，运行内不复用：" + reservationId);
        }
        ReservePayload payload = new ReservePayload(commandId, actorOwnerId, accountId, symbol,
                reservationId, quantity);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.SELL_RESERVE_V1,
                mapper.writeValueAsString(payload));
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 排程一次撤销预留：只释放该预留尚未成交的数量。
     *
     * <p>预留可能在排程之后才由另一任务创建，因此属主与存在性在到期时复核；未知预留是业务拒绝，
     * 不是技术异常。</p>
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  可信调用方解析的财产主体
     * @param reservationId 预留标识
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时间非法
     */
    public synchronized String scheduleCancelSell(String commandId, String taskId,
                                                  long dueTimeMillis, String actorOwnerId,
                                                  String reservationId) throws IOException {
        requireSecuritiesEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(actorOwnerId, "财产主体");
        requireText(reservationId, "预留标识");
        requireClearingFixture();
        CancelCommand command = new CancelCommand(commandId, taskId, dueTimeMillis, actorOwnerId,
                reservationId);
        String content = mapper.writeValueAsString(command);
        String replay = state.commandOutcomes().get(commandId);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return replay;
        }
        requireSchedulable(dueTimeMillis, taskId);
        CancelPayload payload = new CancelPayload(commandId, actorOwnerId, reservationId);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.SELL_CANCEL_V1,
                mapper.writeValueAsString(payload));
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 排程一次同批清算成交：受信的系统夹具，不是买方下单接口。
     *
     * <p>到期时复核预留是否仍活动、买卖双方是否为不同财产受益主体、买方现金是否足够；
     * 成功时现金、股份、预留、报告与全量状态在同一个批次持久确认后才发布。</p>
     *
     * @param commandId      外部命令标识
     * @param taskId         任务标识，运行内不复用
     * @param dueTimeMillis  到期模拟毫秒，必须晚于当前时点
     * @param reservationId  卖方预留标识
     * @param buyerAccountId 买方账户
     * @param price          成交价，精确到分且为正
     * @param quantity       成交数量，必须为正
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时间非法
     */
    public synchronized String scheduleFixtureFill(String commandId, String taskId,
                                                   long dueTimeMillis, String reservationId,
                                                   String buyerAccountId, BigDecimal price,
                                                   long quantity) throws IOException {
        requireSecuritiesEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(reservationId, "预留标识");
        requireText(buyerAccountId, "买方账户");
        if (quantity <= 0) {
            throw new IllegalArgumentException("成交数量必须为正：" + quantity);
        }
        requireClearingFixture();
        BigDecimal normalized = CashLedger.money(price);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("成交价必须为正");
        }
        FillCommand command = new FillCommand(commandId, taskId, dueTimeMillis, reservationId,
                buyerAccountId, normalized, quantity);
        String content = mapper.writeValueAsString(command);
        String replay = state.commandOutcomes().get(commandId);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return replay;
        }
        requireSchedulable(dueTimeMillis, taskId);
        if (!state.accounts().containsKey(buyerAccountId)) {
            throw new IllegalArgumentException("未知买方账户：" + buyerAccountId);
        }
        FillPayload payload = new FillPayload(commandId, reservationId, buyerAccountId,
                normalized, quantity);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.FILL_SETTLE_V1,
                mapper.writeValueAsString(payload));
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 排程一笔世界订单：排程本身持久提交，到期才按最新簿/资格受理并分配订单号。
     *
     * <p>这里只做可提前判定的结构校验：账户属主、证券登记、方向/类型、价格精度、数量、时点与
     * 标识唯一。现金可用与可卖批次在到期时按最新状态复核；业务拒绝形成
     * {@link OrderReport}，不分配订单号、不产生成交、不改变资产。同一命令重发返回原
     * {@code SCHEDULED} 回报，不因时点已过再排一次。</p>
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  可信调用方解析的财产主体
     * @param accountId     下单账户
     * @param symbol        证券代码
     * @param side          买卖方向
     * @param type          限价或市价
     * @param price         限价；市价必须为空，限价精确到分且为正
     * @param quantity      数量，必须为正
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、权属、能力或时间非法
     */
    public synchronized String scheduleOrder(String commandId, String taskId, long dueTimeMillis,
                                             String actorOwnerId, String accountId,
                                             String symbol, Side side, OrderType type,
                                             BigDecimal price, long quantity)
            throws IOException {
        requireMatchingEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(actorOwnerId, "财产主体");
        requireText(accountId, "账户");
        requireText(symbol, "证券代码");
        if (manufacturingConfig != null) {
            CashLedger.CashAccount account = state.accounts().get(accountId);
            if (account != null && account.ownerId().equals(
                    manufacturingConfig.accounting().companyOwnerId())) {
                throw new IllegalArgumentException("公司证券申报必须走经营分录，本档案拒绝直接下单："
                        + accountId);
            }
        }
        if (side == null) {
            throw new IllegalArgumentException("买卖方向不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("订单类型不能为空");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("订单数量必须为正：" + quantity);
        }
        BigDecimal normalized = null;
        if (type == OrderType.LIMIT) {
            normalized = CashLedger.money(price);
            if (normalized.signum() <= 0) {
                throw new IllegalArgumentException("限价必须为正：" + price);
            }
        } else if (price != null) {
            throw new IllegalArgumentException("市价单不得携带限价");
        }
        OrderCommand command = new OrderCommand(commandId, taskId, dueTimeMillis, actorOwnerId,
                accountId, symbol, side, type, normalized, quantity);
        String content = mapper.writeValueAsString(command);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        CashLedger.CashAccount account = state.accounts().get(accountId);
        if (account == null || !account.ownerId().equals(actorOwnerId)) {
            throw new IllegalArgumentException("该财产主体无权动用账户：" + accountId);
        }
        if (registry.security(symbol) == null) {
            throw new IllegalArgumentException("未知证券：" + symbol);
        }
        OrderPayload payload = new OrderPayload(commandId, actorOwnerId, accountId, symbol,
                side, type, normalized, quantity);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.EXCHANGE_ORDER_V1,
                mapper.writeValueAsString(payload));
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 排程撤销一笔世界订单：只释放剩余量；未知/非属主/终态在到期时形成明确业务拒绝。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  可信调用方解析的财产主体
     * @param orderId       订单号
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时间非法
     */
    public synchronized String scheduleCancelOrder(String commandId, String taskId,
                                                   long dueTimeMillis, String actorOwnerId,
                                                   long orderId) throws IOException {
        requireMatchingEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(actorOwnerId, "财产主体");
        if (orderId <= 0) {
            throw new IllegalArgumentException("订单号必须为正：" + orderId);
        }
        CancelOrderCommand command = new CancelOrderCommand(commandId, taskId, dueTimeMillis,
                actorOwnerId, orderId);
        String content = mapper.writeValueAsString(command);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        CancelOrderPayload payload = new CancelOrderPayload(commandId, actorOwnerId, orderId);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.EXCHANGE_CANCEL_V1,
                mapper.writeValueAsString(payload));
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 排程收盘：受信系统动作，撤销全簿并释放冻结；收盘后申报（含此前已排未执行的）被拒绝。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时间非法
     */
    public synchronized String scheduleCloseMarket(String commandId, String taskId,
                                                   long dueTimeMillis) throws IOException {
        requireMatchingEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        CloseMarketCommand command = new CloseMarketCommand(commandId, taskId, dueTimeMillis);
        String content = mapper.writeValueAsString(command);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        CloseMarketPayload payload = new CloseMarketPayload(commandId);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.EXCHANGE_CLOSE_V1,
                mapper.writeValueAsString(payload));
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 外部治理夹具：排程召集一次预算会议。到期才真正召集，并与自动开会待办同批提交。
     *
     * <p>这是受信管理夹具：显式 chairSeatId 只是身份声明，不是 LLM/真实用户身份认证。
     * 只接受 BUDGET；经营复盘会议只由制造强制关期的固定证据产生。时序要求
     * {@code due ≤ materialDeadline < opensAt < closesAt}，相邻至少 1 模拟毫秒。</p>
     *
     * @param commandId        外部命令标识，重发返回原回报
     * @param taskId           任务标识，运行内不复用
     * @param dueTimeMillis    召集到期，必须晚于当前时点
     * @param chairSeatId      声明的主席席位，必须等于章程主席
     * @param meetingId        会议标识；与已有会议同名时到期形成业务拒绝
     * @param materialDeadline 材料截止
     * @param opensAt          开会时刻
     * @param closesAt         表决截止（不含）
     * @param attendeeSeatIds  到会席位（合成夹具事实）
     * @param recusedSeatIds   回避席位，必须是到会子集
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、权属、能力或时间非法
     */
    public synchronized String scheduleBoardMeeting(String commandId, String taskId,
                                                    long dueTimeMillis, String chairSeatId,
                                                    String meetingId, long materialDeadline,
                                                    long opensAt, long closesAt,
                                                    List<String> attendeeSeatIds,
                                                    List<String> recusedSeatIds)
            throws IOException {
        requireGovernanceEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(chairSeatId, "召集席位");
        requireText(meetingId, "会议标识");
        if (!charter.chairSeatId().equals(chairSeatId)) {
            throw new IllegalArgumentException("只有章程主席可以召集会议：" + chairSeatId);
        }
        List<String> attendees = List.copyOf(Objects.requireNonNull(attendeeSeatIds, "到会席位"));
        List<String> recused = List.copyOf(Objects.requireNonNull(recusedSeatIds, "回避席位"));
        for (String seat : attendees) {
            if (!charter.hasSeat(seat)) {
                throw new IllegalArgumentException("到会席位不在章程中：" + seat);
            }
        }
        for (String seat : recused) {
            if (!attendees.contains(seat)) {
                throw new IllegalArgumentException("回避席位必须是到会席位子集：" + seat);
            }
        }
        if (materialDeadline < dueTimeMillis) {
            throw new IllegalArgumentException("材料截止不得早于召集到期：" + materialDeadline);
        }
        requireMinGap(materialDeadline, opensAt, "材料截止", "开会");
        requireMinGap(opensAt, closesAt, "开会", "表决截止");
        GovernancePayload payload = new GovernancePayload(commandId, chairSeatId, meetingId,
                materialDeadline, opensAt, closesAt, attendees, recused, "", "", 0L, List.of(), "");
        return scheduleGovernanceTask(commandId, taskId, dueTimeMillis,
                PendingTask.GOVERNANCE_MEETING_V1, payload);
    }

    /**
     * 外部治理夹具：向会议提交材料。到期复核声明席位是否到会且未回避；被拒绝不覆盖旧材料。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识
     * @param dueTimeMillis 到期，必须晚于当前时点
     * @param seatId        声明席位
     * @param meetingId     会议标识
     * @param materialId    会议内材料标识
     * @param sourceRef     来源引用
     * @param version       材料版本，必须为正
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、权属或能力非法
     */
    public synchronized String scheduleBoardMaterial(String commandId, String taskId,
                                                     long dueTimeMillis, String seatId,
                                                     String meetingId, String materialId,
                                                     String sourceRef, long version)
            throws IOException {
        requireGovernanceEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireSeat(seatId);
        requireText(meetingId, "会议标识");
        requireText(materialId, "材料标识");
        requireText(sourceRef, "材料来源引用");
        if (version <= 0) {
            throw new IllegalArgumentException("材料版本必须为正：" + version);
        }
        GovernancePayload payload = new GovernancePayload(commandId, seatId, meetingId,
                0L, 0L, 0L, List.of(), List.of(), materialId, sourceRef, version,
                List.of(), "");
        return scheduleGovernanceTask(commandId, taskId, dueTimeMillis,
                PendingTask.GOVERNANCE_MATERIAL_V1, payload);
    }

    /**
     * 外部治理夹具：提出预算请求行。空提案在开会时不能进入表决，到截止必须形成明确拒绝。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识
     * @param dueTimeMillis 到期，必须晚于当前时点
     * @param seatId        声明席位
     * @param meetingId     会议标识
     * @param lines         预算请求行（绝对新上限）
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、权属或能力非法
     */
    public synchronized String scheduleBudgetProposal(String commandId, String taskId,
                                                      long dueTimeMillis, String seatId,
                                                      String meetingId,
                                                      List<BoardModel.BudgetLine> lines)
            throws IOException {
        requireGovernanceEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireSeat(seatId);
        requireText(meetingId, "会议标识");
        List<BoardModel.BudgetLine> budgetLines = List.copyOf(
                Objects.requireNonNull(lines, "预算请求行"));
        GovernancePayload payload = new GovernancePayload(commandId, seatId, meetingId,
                0L, 0L, 0L, List.of(), List.of(), "", "", 0L, budgetLines, "");
        return scheduleGovernanceTask(commandId, taskId, dueTimeMillis,
                PendingTask.GOVERNANCE_BUDGET_PROPOSAL_V1, payload);
    }

    /**
     * 外部治理夹具：对应席位投票。到期按会议真实到会/回避与表决窗口复核；
     * 反对、弃权与回避都作为证据保留。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识
     * @param dueTimeMillis 到期，必须晚于当前时点
     * @param seatId        声明席位
     * @param meetingId     会议标识
     * @param vote          选择
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、权属或能力非法
     */
    public synchronized String scheduleBoardVote(String commandId, String taskId,
                                                 long dueTimeMillis, String seatId,
                                                 String meetingId, BoardModel.Vote vote)
            throws IOException {
        requireGovernanceEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireSeat(seatId);
        requireText(meetingId, "会议标识");
        Objects.requireNonNull(vote, "投票选择");
        GovernancePayload payload = new GovernancePayload(commandId, seatId, meetingId,
                0L, 0L, 0L, List.of(), List.of(), "", "", 0L, List.of(), vote.name());
        return scheduleGovernanceTask(commandId, taskId, dueTimeMillis,
                PendingTask.GOVERNANCE_VOTE_V1, payload);
    }

    /**
     * 外部治理夹具：实施已通过的预算决议。ADOPTED 本身不授予预算；这里成功后才改上限，
     * 且只能成功一次。同一命令重发返回原回报，换命令也不会重复实施。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识
     * @param dueTimeMillis 到期，必须晚于当前时点且不早于关会
     * @param seatId        声明席位（必须是章程成员）
     * @param meetingId     会议标识
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、权属或能力非法
     */
    public synchronized String scheduleBudgetImplementation(String commandId, String taskId,
                                                            long dueTimeMillis, String seatId,
                                                            String meetingId) throws IOException {
        requireGovernanceEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireSeat(seatId);
        requireText(meetingId, "会议标识");
        GovernancePayload payload = new GovernancePayload(commandId, seatId, meetingId,
                0L, 0L, 0L, List.of(), List.of(), "", "", 0L, List.of(), "");
        return scheduleGovernanceTask(commandId, taskId, dueTimeMillis,
                PendingTask.GOVERNANCE_IMPLEMENT_V1, payload);
    }

    /** 治理排程共用：内容幂等、时点/标识校验与同批持久提交。 */
    private String scheduleGovernanceTask(String commandId, String taskId, long dueTimeMillis,
                                          String kind, GovernancePayload payload)
            throws IOException {
        String content = mapper.writeValueAsString(payload);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            requireSameSchedule(commandId, taskId, dueTimeMillis, WorldScheduler.Phase.COMPLETION,
                    kind, content);
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, kind, content);
        return scheduleCompletionTask(task, commandId, content);
    }

    /** 声明席位必须是章程成员；到会/回避在到期时按会议复核。 */
    private void requireSeat(String seatId) {
        requireText(seatId, "席位");
        if (!charter.hasSeat(seatId)) {
            throw new IllegalArgumentException("席位不在章程中：" + seatId);
        }
    }

    /** 已知后续时点必须至少晚 1ms，且加法不得溢出。 */
    private static void requireMinGap(long earlier, long later, String earlierName,
                                      String laterName) {
        long minimum;
        try {
            minimum = Math.addExact(earlier, 1L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(earlierName + " 时点已无法再取下一毫秒", overflow);
        }
        if (later < minimum) {
            throw new IllegalArgumentException(laterName + " 必须至少比" + earlierName
                    + "晚 1 毫秒：" + later + " / " + earlier);
        }
    }

    /**
     * 排程一次采购下单：只允许受信管理夹具为公司排程，到期才形成采购承诺。
     *
     * <p>采购到货固定为到期后 1 个实验日，因此这里先拒绝 {@code due + 1 日} 溢出的排程，
     * 不让任务到期后永久卡住。命令重发返回原 {@code SCHEDULED} 回报。</p>
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  发起人，必须是公司财产主体
     * @param purchaseId    采购单标识，运行内不复用
     * @param quantity      数量，必须为正
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力、时点或溢出非法
     */
    public synchronized String schedulePurchaseOrder(String commandId, String taskId,
                                                     long dueTimeMillis, String actorOwnerId,
                                                     String purchaseId, long quantity)
            throws IOException {
        if (quantity <= 0) {
            throw new IllegalArgumentException("采购数量必须为正：" + quantity);
        }
        requireHorizon(dueTimeMillis, ManufacturingModel.DAY_MILLIS, "采购到货");
        return scheduleManufacturing(commandId, taskId, dueTimeMillis, actorOwnerId,
                PendingTask.MANUFACTURING_PURCHASE_ORDER_V1, purchaseId, "", quantity, null, -1L, -1L);
    }

    /**
     * 排程一次投产：到期耗料、付人工并形成在制批次，成功后自动排 1 日后完工。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  发起人，必须是公司财产主体
     * @param batchId       生产批次标识，运行内不复用
     * @param quantity      批量，纯核在到期时校验为 10 的倍数且不超过 20
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力、时点或溢出非法
     */
    public synchronized String scheduleProduction(String commandId, String taskId,
                                                  long dueTimeMillis, String actorOwnerId,
                                                  String batchId, long quantity)
            throws IOException {
        if (quantity <= 0) {
            throw new IllegalArgumentException("生产批量必须为正：" + quantity);
        }
        requireHorizon(dueTimeMillis, ManufacturingModel.DAY_MILLIS, "生产完工");
        return scheduleManufacturing(commandId, taskId, dueTimeMillis, actorOwnerId,
                PendingTask.MANUFACTURING_PRODUCTION_V1, batchId, "", quantity, null, -1L, -1L);
    }

    /**
     * 排程一次客户签约：到期才按当期需求/预算与同期间条件复核，不提前确认收入。
     *
     * @param commandId          外部命令标识
     * @param taskId             任务标识，运行内不复用
     * @param dueTimeMillis      到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId       发起人，必须是公司财产主体
     * @param contractId         合同标识，运行内不复用
     * @param quantity           合同数量，必须为正
     * @param unitPrice          合同单价，精确到分且为正
     * @param promisedDeliveryAt 承诺交付时点，必须晚于到期且在同一期间内
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时点非法
     */
    public synchronized String scheduleSalesContract(String commandId, String taskId,
                                                     long dueTimeMillis, String actorOwnerId,
                                                     String contractId, long quantity,
                                                     BigDecimal unitPrice, long promisedDeliveryAt)
            throws IOException {
        if (quantity <= 0) {
            throw new IllegalArgumentException("合同数量必须为正：" + quantity);
        }
        if (unitPrice == null) {
            throw new IllegalArgumentException("合同单价不能为空");
        }
        BigDecimal normalized = CashLedger.money(unitPrice);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("合同单价必须为正：" + unitPrice);
        }
        if (promisedDeliveryAt <= dueTimeMillis) {
            throw new IllegalArgumentException("承诺交付必须晚于签约到期："
                    + promisedDeliveryAt + " / " + dueTimeMillis);
        }
        try {
            Math.multiplyExact(dueTimeMillis / ManufacturingModel.PERIOD_MILLIS + 1,
                    ManufacturingModel.PERIOD_MILLIS);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("签约到期所在期间的期末超出模拟时间范围", overflow);
        }
        return scheduleManufacturing(commandId, taskId, dueTimeMillis, actorOwnerId,
                PendingTask.MANUFACTURING_SALES_CONTRACT_V1, contractId, "", quantity, normalized,
                promisedDeliveryAt, -1L);
    }

    /**
     * 排程一次交付：到期扣产成品并确认收入/成本/应收，成功后自动排 15 日收款。
     *
     * <p>应收到期固定为交付后 15 个实验日，因此 {@code due + 15 日} 溢出的排程在这里就被拒绝。</p>
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  发起人，必须是公司财产主体
     * @param contractId    来源合同
     * @param quantity      本次交付数量，必须为正
     * @param deliveryId    交付/应收标识，运行内不复用
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力、时点或溢出非法
     */
    public synchronized String scheduleDelivery(String commandId, String taskId, long dueTimeMillis,
                                                String actorOwnerId, String contractId,
                                                long quantity, String deliveryId)
            throws IOException {
        requireText(deliveryId, "交付标识");
        if (quantity <= 0) {
            throw new IllegalArgumentException("交付数量必须为正：" + quantity);
        }
        requireHorizon(dueTimeMillis, ManufacturingModel.RECEIVABLE_TERM_MILLIS, "应收到期");
        return scheduleManufacturing(commandId, taskId, dueTimeMillis, actorOwnerId,
                PendingTask.MANUFACTURING_DELIVERY_V1, contractId, deliveryId, quantity, null,
                -1L, -1L);
    }

    /**
     * 排程一次应收回款：逾期后可明确重试，已收清再收会被纯核拒绝且不动资产。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  发起人，必须是公司财产主体
     * @param receivableId  应收标识
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时点非法
     */
    public synchronized String scheduleCollectReceivable(String commandId, String taskId,
                                                         long dueTimeMillis, String actorOwnerId,
                                                         String receivableId) throws IOException {
        return scheduleManufacturing(commandId, taskId, dueTimeMillis, actorOwnerId,
                PendingTask.MANUFACTURING_COLLECT_RECEIVABLE_V1, receivableId, "", 0L, null, -1L, -1L);
    }

    /**
     * 排程一次费用账单付款：只结清该期已确认未付金额，不重复记费用。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  发起人，必须是公司财产主体
     * @param period        费用账单所属期间
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时点非法
     */
    public synchronized String schedulePayExpenseBill(String commandId, String taskId,
                                                      long dueTimeMillis, String actorOwnerId,
                                                      long period) throws IOException {
        if (period < 0) {
            throw new IllegalArgumentException("期间不能为负：" + period);
        }
        return scheduleManufacturing(commandId, taskId, dueTimeMillis, actorOwnerId,
                PendingTask.MANUFACTURING_PAY_EXPENSE_BILL_V1, "", "", 0L, null, -1L, period);
    }

    /**
     * 六个制造排程入口的共用实现：结构校验、幂等比对、条件排程与持久提交。
     *
     * <p>业务条件（预算、需求、库存、可用现金、ID 是否被占）一律留到到期复核；这里只拒绝参数、
     * 权属、能力、时点与已知溢出。</p>
     */
    private String scheduleManufacturing(String commandId, String taskId, long dueTimeMillis,
                                        String actorOwnerId, String kind, String businessId,
                                        String resultId, long quantity, BigDecimal unitPrice,
                                        long promisedDeliveryAt, long period) throws IOException {
        requireManufacturingEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(actorOwnerId, "财产主体");
        if (!PendingTask.MANUFACTURING_PAY_EXPENSE_BILL_V1.equals(kind)) {
            requireText(businessId, "业务标识");
        }
        if (!actorOwnerId.equals(manufacturingConfig.accounting().companyOwnerId())) {
            throw new IllegalArgumentException("只有公司财产主体可以发起经营动作：" + actorOwnerId);
        }
        ManufacturingPayload payload = new ManufacturingPayload(commandId, actorOwnerId,
                businessId, resultId, quantity, unitPrice, promisedDeliveryAt, period);
        String payloadJson = mapper.writeValueAsString(payload);
        ManufacturingCommand command = new ManufacturingCommand(commandId, taskId, dueTimeMillis,
                actorOwnerId, businessId, resultId, quantity, unitPrice, promisedDeliveryAt, period);
        String content = mapper.writeValueAsString(command);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            requireSameSchedule(commandId, taskId, dueTimeMillis, WorldScheduler.Phase.COMPLETION,
                    kind, payloadJson);
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        PendingTask task = new PendingTask(taskId, dueTimeMillis, WorldScheduler.Phase.COMPLETION,
                kind, payloadJson);
        return scheduleCompletionTask(task, commandId, content);
    }

    // ------------------------------------------------------------------
    // 任职与资料阅读：受信管理排程（全部 due > 世界时间，到期才提交）
    // ------------------------------------------------------------------

    /**
     * 排程一次任职建立：到期才写入授权状态，排程本身只持久待办与命令内容。
     *
     * <p>起点不得早于到期时刻（不能借排程倒填过去任职），身份种类与重叠冲突在排程期即按固定
     * 开局登记预检，并在到期时用同一口径再校验一次。同一命令重发返回原 {@code SCHEDULED} 文本。</p>
     *
     * @param commandId           外部命令标识
     * @param taskId              任务标识，运行内不复用
     * @param dueTimeMillis       到期模拟毫秒，必须晚于当前时点
     * @param appointmentId       任职标识，历史唯一
     * @param personPartyId       任职人物
     * @param organizationPartyId 任职组织
     * @param role                岗位
     * @param startsAt            任职起始（含），不得早于到期
     * @param endsAt              任职结束（不含）
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、身份、区间或能力非法
     */
    public synchronized String scheduleAppointment(String commandId, String taskId,
                                                   long dueTimeMillis, String appointmentId,
                                                   String personPartyId,
                                                   String organizationPartyId,
                                                   RoleAccess.Role role, long startsAt, long endsAt)
            throws IOException {
        requireRoleReadingEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(appointmentId, "任职标识");
        requireText(personPartyId, "任职人物");
        requireText(organizationPartyId, "任职组织");
        Objects.requireNonNull(role, "任职岗位");
        AppointmentPayload payload = new AppointmentPayload(commandId, appointmentId,
                personPartyId, organizationPartyId, role.name(), startsAt, endsAt);
        String content = mapper.writeValueAsString(payload);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            requireSameSchedule(commandId, taskId, dueTimeMillis, WorldScheduler.Phase.RULES,
                    PendingTask.ROLE_APPOINT_V1, content);
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        if (startsAt < dueTimeMillis) {
            throw new IllegalArgumentException("任职起点不得早于执行到期：" + startsAt
                    + " < " + dueTimeMillis);
        }
        // 固定登记与重叠预检：与到期时同一口径
        new RoleAccess.Appointment(appointmentId, personPartyId, organizationPartyId, role,
                startsAt, endsAt, null);
        requireParty(personPartyId, OpeningRegistry.PartyKind.PERSON, "任职人物");
        requireParty(organizationPartyId, OpeningRegistry.PartyKind.ORGANIZATION, "任职组织");
        PendingTask task = new PendingTask(taskId, dueTimeMillis, WorldScheduler.Phase.RULES,
                PendingTask.ROLE_APPOINT_V1, content);
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 排程一次任职撤销（RULES）。到期写入一次撤销时刻，不删除记录、不改人物/组织/岗位/原期限。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param appointmentId 任职标识
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数或能力非法
     */
    public synchronized String scheduleRevokeAppointment(String commandId, String taskId,
                                                         long dueTimeMillis, String appointmentId)
            throws IOException {
        requireRoleReadingEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(appointmentId, "任职标识");
        AppointmentPayload payload = new AppointmentPayload(commandId, appointmentId,
                "", "", "", 0L, 0L);
        String content = mapper.writeValueAsString(payload);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            requireSameSchedule(commandId, taskId, dueTimeMillis, WorldScheduler.Phase.RULES,
                    PendingTask.ROLE_REVOKE_V1, content);
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        PendingTask task = new PendingTask(taskId, dueTimeMillis, WorldScheduler.Phase.RULES,
                PendingTask.ROLE_REVOKE_V1, content);
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 排程首次发表一篇资料：实际发布时间等于任务到期，不能在排程期预造过去出版经历。
     *
     * <p>发布者必须是冻结的登记读者；公开受众不带对象，组织受众必须是已登记 ORGANIZATION，
     * 直投受众必须是登记读者。受众与发布者随文档冻结，追加版本不能修改。</p>
     *
     * @param commandId               外部命令标识
     * @param taskId                  任务标识，运行内不复用
     * @param dueTimeMillis           到期模拟毫秒，必须晚于当前时点
     * @param docId                   新资料标识
     * @param publisherPartyId        发布者（登记读者）
     * @param audience                受众
     * @param title                   标题
     * @param body                    正文；只是资料内容，不是系统指令
     * @param publicSourceDescription 公开来源描述
     * @param readDurationMillis      固定阅读耗时，必须为正
     * @param internalSourceIds       仅管理审计的内部来源标识
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、受众或能力非法
     */
    public synchronized String schedulePublishDocument(String commandId, String taskId,
                                                       long dueTimeMillis, String docId,
                                                       String publisherPartyId,
                                                       InformationModel.Audience audience,
                                                       String title, String body,
                                                       String publicSourceDescription,
                                                       long readDurationMillis,
                                                       List<String> internalSourceIds)
            throws IOException {
        requireRoleReadingEnabled();
        Objects.requireNonNull(audience, "受众");
        PublishPayload payload = new PublishPayload(commandId, docId, publisherPartyId,
                audience.kind().name(), audience.partyId() == null ? "" : audience.partyId(),
                title, body, publicSourceDescription, readDurationMillis,
                internalSourceIds == null ? List.of() : List.copyOf(internalSourceIds));
        return schedulePublish(commandId, taskId, dueTimeMillis, payload,
                PendingTask.INFORMATION_PUBLISH_V1);
    }

    /**
     * 排程给已有资料追加一版：沿用原发布者与受众，发布时间等于任务到期且必须晚于上一版。
     *
     * @param commandId               外部命令标识
     * @param taskId                  任务标识，运行内不复用
     * @param dueTimeMillis           到期模拟毫秒，必须晚于当前时点
     * @param docId                   已有资料标识
     * @param title                   标题
     * @param body                    正文
     * @param publicSourceDescription 公开来源描述
     * @param readDurationMillis      固定阅读耗时，必须为正
     * @param internalSourceIds       仅管理审计的内部来源标识
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数或能力非法
     */
    public synchronized String schedulePublishDocumentVersion(String commandId, String taskId,
                                                              long dueTimeMillis, String docId,
                                                              String title, String body,
                                                              String publicSourceDescription,
                                                              long readDurationMillis,
                                                              List<String> internalSourceIds)
            throws IOException {
        requireRoleReadingEnabled();
        requireText(docId, "资料标识");
        PublishPayload payload = new PublishPayload(commandId, docId, "", "", "",
                title, body, publicSourceDescription, readDurationMillis,
                internalSourceIds == null ? List.of() : List.copyOf(internalSourceIds));
        return schedulePublish(commandId, taskId, dueTimeMillis, payload,
                PendingTask.INFORMATION_PUBLISH_VERSION_V1);
    }

    /** 发表排程共用：命令幂等、固定受众校验与同批持久提交。 */
    private String schedulePublish(String commandId, String taskId, long dueTimeMillis,
                                   PublishPayload payload, String kind) throws IOException {
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(payload.docId(), "资料标识");
        requireText(payload.title(), "资料标题");
        requireText(payload.body(), "资料正文");
        if (payload.readDurationMillis() <= 0) {
            throw new IllegalArgumentException("阅读耗时必须为正毫秒："
                    + payload.readDurationMillis());
        }
        String content = mapper.writeValueAsString(payload);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            requireSameSchedule(commandId, taskId, dueTimeMillis, WorldScheduler.Phase.COMPLETION,
                    kind, content);
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        if (PendingTask.INFORMATION_PUBLISH_V1.equals(kind)) {
            requireText(payload.publisherPartyId(), "发布者");
            if (!informationConfig.readers().contains(payload.publisherPartyId())) {
                throw new IllegalArgumentException("发布者必须是冻结的登记读者："
                        + payload.publisherPartyId());
            }
            InformationModel.AudienceKind audienceKind =
                    InformationModel.AudienceKind.valueOf(payload.audienceKind());
            if (audienceKind == InformationModel.AudienceKind.ORGANIZATION) {
                requireParty(payload.audiencePartyId(),
                        OpeningRegistry.PartyKind.ORGANIZATION, "组织受众");
            } else if (audienceKind == InformationModel.AudienceKind.DIRECT) {
                requireParty(payload.audiencePartyId(), OpeningRegistry.PartyKind.PERSON,
                        "直投受众");
                if (!informationConfig.readers().contains(payload.audiencePartyId())) {
                    throw new IllegalArgumentException("直投对象必须是冻结的登记读者："
                            + payload.audiencePartyId());
                }
            }
        }
        PendingTask task = new PendingTask(taskId, dueTimeMillis, WorldScheduler.Phase.COMPLETION,
                kind, content);
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 排程开始阅读一个明确版本：到期才预留工时，并同批排唯一匹配的自动完成待办。
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param personPartyId 读者（登记人物）
     * @param docId         资料标识
     * @param version       明确版本号，必须为正
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数或能力非法
     */
    public synchronized String scheduleStartReading(String commandId, String taskId,
                                                    long dueTimeMillis, String personPartyId,
                                                    String docId, long version)
            throws IOException {
        requireRoleReadingEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(personPartyId, "阅读者");
        requireText(docId, "资料标识");
        if (version < 1) {
            throw new IllegalArgumentException("阅读版本必须从 1 开始：" + version);
        }
        requireParty(personPartyId, OpeningRegistry.PartyKind.PERSON, "阅读者");
        if (!informationConfig.readers().contains(personPartyId)) {
            throw new IllegalArgumentException("阅读者必须是冻结的登记读者：" + personPartyId);
        }
        ReadingPayload payload = new ReadingPayload(commandId, personPartyId, docId, version, "", 0L);
        String content = mapper.writeValueAsString(payload);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            requireSameSchedule(commandId, taskId, dueTimeMillis, WorldScheduler.Phase.COMPLETION,
                    PendingTask.INFORMATION_START_READ_V1, content);
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        PendingTask task = new PendingTask(taskId, dueTimeMillis, WorldScheduler.Phase.COMPLETION,
                PendingTask.INFORMATION_START_READ_V1, content);
        return scheduleCompletionTask(task, commandId, content);
    }

    /**
     * 排程取消一张活动阅读：到期后只取消本单，并只移除本单对应的自动完成待办。
     *
     * <p>入口按<b>本人范围引用</b>（所属人物 + 资料 + 固定版本 + 本人阅读序号）解析，不接受管理端全局
     * {@code READ-n} 序号，因此别人多读或少读都不会改变本单的取消语义。</p>
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param personPartyId 读者（登记人物）
     * @param ref           本人范围引用（来自 {@link RoleView.ReadingRef}）
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、引用归属或能力非法
     */
    public synchronized String scheduleCancelReading(String commandId, String taskId,
                                                     long dueTimeMillis, String personPartyId,
                                                     RoleView.ReadingRef ref)
            throws IOException {
        requireRoleReadingEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(personPartyId, "阅读者");
        Objects.requireNonNull(ref, "阅读引用");
        if (!ref.personPartyId().equals(personPartyId)) {
            throw new IllegalArgumentException("阅读引用不属于该人物：" + ref.personPartyId());
        }
        ReadingPayload payload = new ReadingPayload(commandId, personPartyId, ref.docId(),
                ref.version(), "", ref.index());
        String content = mapper.writeValueAsString(payload);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            requireSameSchedule(commandId, taskId, dueTimeMillis, WorldScheduler.Phase.COMPLETION,
                    PendingTask.INFORMATION_CANCEL_READ_V1, content);
            return state.commandOutcomes().get(commandId);
        }
        requireSchedulable(dueTimeMillis, taskId);
        PendingTask task = new PendingTask(taskId, dueTimeMillis, WorldScheduler.Phase.COMPLETION,
                PendingTask.INFORMATION_CANCEL_READ_V1, content);
        return scheduleCompletionTask(task, commandId, content);
    }

    /** 登记人物/组织种类：只读取固定开局登记，不信任调用方声明。 */
    private void requireParty(String partyId, OpeningRegistry.PartyKind kind, String field) {
        OpeningRegistry.Party party = partyOf(partyId);
        if (party == null || party.kind() != kind) {
            throw new IllegalArgumentException(field + "未登记或种类不符：" + partyId);
        }
    }

    /** 在固定开局登记里按标识查主体；不存在返回 null。 */
    private OpeningRegistry.Party partyOf(String partyId) {
        if (registry == null || partyId == null) {
            return null;
        }
        for (OpeningRegistry.Party party : registry.parties()) {
            if (party.partyId().equals(partyId)) {
                return party;
            }
        }
        return null;
    }

    /**
     * 推进到目标模拟毫秒：按公平顺序执行到期待办，再为时钟边界落一个完整批次。
     *
     * @param inclusiveMillis 目标模拟毫秒，不得早于当前时间
     * @throws IOException 持久提交失败；此时运行暂停、状态不前进
     */
    public synchronized void advanceTo(long inclusiveMillis) throws IOException {
        requireOpen();
        requireWriter();
        if (paused) {
            throw new IllegalStateException("运行已暂停，须恢复后继续");
        }
        if (inclusiveMillis < state.timeMillis()) {
            throw new IllegalArgumentException("世界时间不能倒退：" + state.timeMillis());
        }
        try {
            long limit = decisionLimit(inclusiveMillis);
            boolean stopsAtBarrier = stopsAtDecisionBarrier(inclusiveMillis);
            while (true) {
                PendingTask next = peek(limit);
                if (next == null) {
                    break;
                }
                switch (next.kind()) {
                    case PendingTask.CASH_TRANSFER_V1 -> completeTransfer(next);
                    case PendingTask.TRANSFER_NOTICE_V1 -> deliverNotice(next);
                    case PendingTask.SELL_RESERVE_V1 -> completeReserve(next);
                    case PendingTask.SELL_CANCEL_V1 -> completeCancel(next);
                    case PendingTask.FILL_SETTLE_V1 -> completeFill(next);
                    case PendingTask.EXCHANGE_ORDER_V1 -> completeOrder(next);
                    case PendingTask.EXCHANGE_CANCEL_V1 -> completeCancelOrder(next);
                    case PendingTask.EXCHANGE_CLOSE_V1 -> completeCloseMarket(next);
                    case PendingTask.MANUFACTURING_PURCHASE_ORDER_V1,
                         PendingTask.MANUFACTURING_PRODUCTION_V1,
                         PendingTask.MANUFACTURING_SALES_CONTRACT_V1,
                         PendingTask.MANUFACTURING_DELIVERY_V1,
                         PendingTask.MANUFACTURING_COLLECT_RECEIVABLE_V1,
                         PendingTask.MANUFACTURING_PAY_EXPENSE_BILL_V1 -> completeManufacturing(next);
                    case PendingTask.MANUFACTURING_GOODS_RECEIPT_V1,
                         PendingTask.MANUFACTURING_BATCH_COMPLETION_V1,
                         PendingTask.MANUFACTURING_AUTO_COLLECTION_V1 -> completeManufacturingChild(next);
                    case PendingTask.MANUFACTURING_PERIOD_CLOSE_V1 -> completePeriodClose(next);
                    case PendingTask.GOVERNANCE_MEETING_V1,
                         PendingTask.GOVERNANCE_MATERIAL_V1,
                         PendingTask.GOVERNANCE_BUDGET_PROPOSAL_V1,
                         PendingTask.GOVERNANCE_VOTE_V1,
                         PendingTask.GOVERNANCE_IMPLEMENT_V1 -> completeGovernance(next);
                    case PendingTask.BOARD_OPEN_V1 -> completeBoardOpen(next);
                    case PendingTask.BOARD_CLOSE_V1 -> completeBoardClose(next);
                    case PendingTask.BOARD_REVIEW_V1 -> completeBoardReview(next);
                    case PendingTask.ROLE_APPOINT_V1,
                         PendingTask.ROLE_REVOKE_V1 -> completeRole(next);
                    case PendingTask.INFORMATION_PUBLISH_V1,
                         PendingTask.INFORMATION_PUBLISH_VERSION_V1,
                         PendingTask.INFORMATION_START_READ_V1,
                         PendingTask.INFORMATION_CANCEL_READ_V1,
                         PendingTask.INFORMATION_COMPLETE_READ_V1 -> completeInformation(next);
                    case PendingTask.ROLE_TRADING_READ_V1,
                         PendingTask.ROLE_TRADING_UNREAD_V1 -> completeRoleReading(next);
                    case PendingTask.ROLE_TRADING_RESEARCH_V1,
                         PendingTask.ROLE_TRADING_TARGET_V1,
                         PendingTask.ROLE_TRADING_REVIEW_V1,
                         PendingTask.ROLE_TRADING_CONFIRM_V1,
                         PendingTask.ROLE_TRADING_HALT_V1,
                         PendingTask.ROLE_TRADING_CANCEL_V1,
                         PendingTask.ROLE_TRADING_CHILD_V1,
                         PendingTask.ROLE_TRADING_CHILD_CANCEL_V1 -> completeRoleAction(next);
                    case PendingTask.ROLE_TRADING_SWEEP_V1 -> completeRoleSweep(next);
                    case PendingTask.ROLE_TRADING_EXPIRE_V1 -> completeRoleExpire(next);
                    case PendingTask.ROLE_TRADING_APPOINTMENT_END_V1 -> completeAppointmentEnd(next);
                    case PendingTask.ROLE_TRADING_HALT_RELEASE_V1 -> completeHaltRelease(next);
                    default -> throw new IllegalStateException("未启用的任务种类：" + next.kind());
                }
            }
            if (stopsAtBarrier) {
                // 停在最早未决屏障之前：只把时钟推到屏障，不落观察批次；已在屏障时不加空批。
                if (limit > state.timeMillis()) {
                    String content = "{\"timeMillis\":" + limit + "}";
                    WorldState candidate = next(limit,
                            WorldScheduler.Phase.COMPLETION.name(), state.accounts(),
                            state.pending(), state.delivered(), state.usedTaskIds(),
                            state.commandContents(), state.commandOutcomes(), state.securities(),
                            state.exchange());
                    commitAndApply("barrier:" + limit, content, "BARRIER_REACHED",
                            List.of(new WorldEvent("barrier:" + limit, "DECISION_BARRIER_V1",
                                    content)), candidate);
                }
                return;
            }
            if (inclusiveMillis > state.timeMillis()) {
                String content = "{\"timeMillis\":" + inclusiveMillis + "}";
                WorldState candidate = next(inclusiveMillis,
                        WorldScheduler.Phase.OBSERVATION.name(), state.accounts(), state.pending(),
                        state.delivered(), state.usedTaskIds(), state.commandContents(),
                        state.commandOutcomes(), state.securities(), state.exchange());
                commitAndApply("clock:" + inclusiveMillis, content, "ADVANCED",
                        List.of(new WorldEvent("clock:" + inclusiveMillis, "CLOCK_ADVANCED_V1",
                                content)), candidate);
            }
        } catch (RuntimeException failure) {
            // 内部执行期（含 ID 冲突、序列化、溢出）失败同样进入暂停，保留旧快照
            paused = true;
            throw failure;
        }
    }

    /** @return 当前管理端快照（只读、同一提交位置） */
    public synchronized Snapshot snapshot() {
        return new Snapshot(state.position(), state.timeMillis(), state.lastPhase(),
                state.accounts(), Optional.ofNullable(state.securities()),
                Optional.ofNullable(state.exchange()), Optional.ofNullable(state.manufacturing()),
                Optional.ofNullable(state.governance()), Optional.ofNullable(state.roles()),
                Optional.ofNullable(state.information()), Optional.ofNullable(state.trading()),
                Optional.ofNullable(state.decisions()),
                state.pending(), state.delivered(), state.usedTaskIds());
    }

    /**
     * 开局登记的管理端视图。
     *
     * <p>这是**开局分配**的只读快照，不是实时持仓：股份的后续变动只体现在
     * {@link Snapshot#securities()} 的批次台账里。两者必须分开读，不能用开局分配冒充当前持股，
     * 也不能用当前批次反推开局时的历史归属。</p>
     *
     * @return 现金模式为空；带登记的运行返回开局分配
     */
    public Optional<OpeningRegistry> openingRegistry() {
        return Optional.ofNullable(registry);
    }

    /** @return 是否因提交失败而暂停 */
    public boolean isPaused() {
        return paused;
    }

    // ------------------------------------------------------------------
    // 角色视图：只从同一已确认快照派生，不暴露管理端原始状态
    // ------------------------------------------------------------------

    /**
     * 单个角色的只读视图：世界时间、本人当前任职、本人可见账户与证券汇总、公共市场聚合、
     * 允许目录、本人真正读完的经历、本人阅读状态与已接入的阅读工具。
     *
     * <p><b>时间来源</b>：只用世界时点 {@code state.timeMillis()}，不接受调用方传入时间，
     * 因此不能借视图把过去的授权复原。</p>
     *
     * <p><b>拒绝口径</b>：未登记且没有任职记录的人物得到同一条拒绝，不区分「不存在」与「无权」，
     * 不泄露隐藏人物的存在性。</p>
     *
     * @param personPartyId 可信人物标识
     * @return 该人物的角色视图
     * @throws IllegalArgumentException 人物为空或不可见
     * @throws IllegalStateException    当前运行未启用任职与阅读模块
     */
    public synchronized RoleView roleView(String personPartyId) {
        requireOpen();
        requireRoleReadingEnabled();
        return roleView(state, personPartyId, requireRoleRun().access());
    }

    private RoleView roleView(WorldState snap, String personPartyId,
                              RoleAccess.State access) {
        String person = personPartyId == null ? "" : personPartyId;
        if (snap.information() == null) {
            throw new IllegalStateException("快照缺少阅读子集");
        }
        InformationModel.State information = snap.information().information();
        boolean registered = information.persons().contains(person);
        boolean appointed = false;
        for (RoleAccess.Appointment appointment : access.appointments()) {
            if (appointment.personPartyId().equals(person)) {
                appointed = true;
                break;
            }
        }
        if (!registered && !appointed) {
            throw new IllegalArgumentException("角色视图不可用");
        }
        long now = snap.timeMillis();

        List<RoleView.Appointment> appointments = new ArrayList<>();
        for (RoleAccess.Appointment appointment : access.appointments()) {
            if (appointment.personPartyId().equals(person) && appointment.activeAt(now)) {
                appointments.add(new RoleView.Appointment(appointment.id(),
                        appointment.organizationPartyId(), appointment.role(),
                        appointment.startsAt(), appointment.effectiveEnd()));
            }
        }
        Map<String, RoleView.AccountKind> visible = new LinkedHashMap<>();
        for (RoleAccess.PersonAccount personal : access.personAccounts()) {
            if (personal.personPartyId().equals(person)) {
                visible.put(personal.accountId(), RoleView.AccountKind.PERSONAL);
            }
        }
        for (RoleView.Appointment appointment : appointments) {
            for (RoleAccess.ProductMandate mandate : access.mandates()) {
                if (!mandate.organizationPartyId().equals(appointment.organizationPartyId())) {
                    continue;
                }
                for (String accountId : mandate.accountIds()) {
                    visible.putIfAbsent(accountId, RoleView.AccountKind.PRODUCT);
                }
            }
        }
        List<RoleView.Account> accounts = new ArrayList<>();
        for (Map.Entry<String, RoleView.AccountKind> entry : visible.entrySet()) {
            CashLedger.CashAccount cash = snap.accounts().get(entry.getKey());
            if (cash == null) {
                continue;
            }
            BigDecimal frozen = ZERO;
            if (snap.exchange() != null) {
                frozen = WorldExchange.frozenCash(snap.exchange(), entry.getKey());
            }
            accounts.add(new RoleView.Account(entry.getKey(), entry.getValue(), cash.ownerId(),
                    cash.cash(), frozen, cash.cash().subtract(frozen)));
        }
        List<RoleView.Holding> holdings = holdingsOf(snap, visible.keySet());
        List<RoleView.Market> market = marketOf(snap);
        List<InformationModel.CatalogEntry> catalog = InformationModel.catalog(information,
                access, person, now);
        Map<String, Integer> ordinals = readingOrdinals(information, person);
        List<RoleView.Experience> observations = new ArrayList<>();
        for (InformationModel.ReadObservation observation
                : InformationModel.observations(information, person)) {
            Integer index = ordinals.get(observation.readId());
            if (index == null) {
                throw new IllegalStateException("经历找不到本人阅读序号：" + observation.readId());
            }
            observations.add(new RoleView.Experience(
                    new RoleView.ReadingRef(observation.personPartyId(), observation.docId(),
                            observation.version(), index),
                    observation.title(), observation.body(),
                    observation.publicSourceDescription(), observation.publishedAt(),
                    observation.completedAt()));
        }
        List<RoleView.Reading> readings = new ArrayList<>();
        for (InformationModel.Reading reading : information.active()) {
            if (reading.personPartyId().equals(person)) {
                Integer index = ordinals.get(reading.readId());
                if (index == null) {
                    throw new IllegalStateException("活动阅读找不到本人序号：" + reading.readId());
                }
                readings.add(new RoleView.Reading(new RoleView.ReadingRef(reading.personPartyId(),
                        reading.docId(), reading.version(), index), reading.dueAt()));
            }
        }
        List<RoleView.Permission> permissions = permissionsOf(access, appointments, person, now);
        return new RoleView(now, person, appointments, accounts, holdings, market, catalog,
                observations, readings, permissions,
                List.of(RoleView.TOOL_CATALOG, RoleView.TOOL_START_READ, RoleView.TOOL_CITE));
    }

    /**
     * 引用判定：本人是否已在世界当前时点之前真正读完该资料的该固定版本。
     *
     * <p>只看本人经历，不看目录标题、不看别人的经历、不读正文里的指令；引用时刻取世界时间，
     * 调用方不能传过去时间。</p>
     *
     * @param personPartyId 读者
     * @param docId         资料标识
     * @param version       被引版本
     * @return 可引用返回 true
     * @throws IllegalArgumentException 参数非法或版本号小于 1
     * @throws IllegalStateException    当前运行未启用任职与阅读模块
     */
    public synchronized boolean canCite(String personPartyId, String docId, long version) {
        requireOpen();
        requireRoleReadingEnabled();
        return InformationModel.canCite(requireInformationRun().information(), personPartyId,
                docId, version, state.timeMillis());
    }

    /**
     * 本人范围内的阅读序号：把该人物的活动与已终结阅读按内部记录顺序排列，得到 1..n。
     *
     * <p>只用于投影，不写入任何新状态；别人的阅读不参与排序，因此别人多读一次不会改变本人序号，
     * 同一人物同刻取消后重开也会得到新的序号。</p>
     */
    private static Map<String, Integer> readingOrdinals(InformationModel.State information,
                                                        String personPartyId) {
        List<String> ids = new ArrayList<>();
        for (InformationModel.Reading reading : information.active()) {
            if (reading.personPartyId().equals(personPartyId)) {
                ids.add(reading.readId());
            }
        }
        for (InformationModel.ClosedReading closed : information.closed()) {
            if (closed.personPartyId().equals(personPartyId)) {
                ids.add(closed.readId());
            }
        }
        ids.sort(Comparator.comparingLong(WorldRuntime::readSequence)
                .thenComparing(Comparator.naturalOrder()));
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            ordinals.put(ids.get(i), i + 1);
        }
        return ordinals;
    }

    /** 内部阅读记录的顺序键；非 {@code READ-<n>} 标识排到最后并由标识本身决定顺序。 */
    private static long readSequence(String readId) {
        if (readId == null || !readId.startsWith("READ-")) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(readId.substring("READ-".length()));
        } catch (NumberFormatException notNumeric) {
            return Long.MAX_VALUE;
        }
    }

    /** 证券按账户+代码汇总；冻结来自活动卖出预留，不暴露批次标识。 */
    private List<RoleView.Holding> holdingsOf(WorldState snap, Set<String> accountIds) {
        List<RoleView.Holding> holdings = new ArrayList<>();
        if (snap.securities() == null) {
            return holdings;
        }
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (SecuritiesLedger.Lot lot : snap.securities().lots()) {
            if (!accountIds.contains(lot.accountId())) {
                continue;
            }
            long[] sums = totals.computeIfAbsent(lot.accountId() + "\u0000" + lot.symbol(),
                    key -> new long[]{0L, 0L});
            sums[0] = Math.addExact(sums[0], lot.remaining());
        }
        for (SecuritiesLedger.SellReservation reservation : snap.securities().reservations()) {
            if (!accountIds.contains(reservation.accountId())) {
                continue;
            }
            long[] sums = totals.get(reservation.accountId() + "\u0000" + reservation.symbol());
            if (sums != null) {
                sums[1] = Math.addExact(sums[1], reservation.quantity());
            }
        }
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            int split = entry.getKey().indexOf('\u0000');
            String accountId = entry.getKey().substring(0, split);
            String symbol = entry.getKey().substring(split + 1);
            long quantity = entry.getValue()[0];
            long frozen = entry.getValue()[1];
            holdings.add(new RoleView.Holding(accountId, symbol, quantity, frozen,
                    quantity - frozen));
        }
        return holdings;
    }

    /** 公共市场聚合：只给标的、最近成交价量与盘口，不给对手账户或内部成交序号。 */
    private List<RoleView.Market> marketOf(WorldState snap) {
        List<RoleView.Market> market = new ArrayList<>();
        if (registry == null || snap.exchange() == null) {
            return market;
        }
        for (OpeningRegistry.Security security : registry.securities()) {
            String symbol = security.symbol();
            WorldExchange.Trade lastTrade = null;
            for (WorldExchange.Trade trade : snap.exchange().trades()) {
                if (!trade.symbol().equals(symbol)) {
                    continue;
                }
                if (lastTrade == null || trade.timeMillis() > lastTrade.timeMillis()
                        || (trade.timeMillis() == lastTrade.timeMillis()
                        && trade.tradeId() > lastTrade.tradeId())) {
                    lastTrade = trade;
                }
            }
            BigDecimal bestBid = null;
            BigDecimal bestAsk = null;
            for (WorldExchange.Order order : snap.exchange().orders()) {
                if (!order.live() || !order.symbol().equals(symbol)
                        || order.limitPrice() == null) {
                    continue;
                }
                if (order.side() == Side.BUY
                        && (bestBid == null || order.limitPrice().compareTo(bestBid) > 0)) {
                    bestBid = order.limitPrice();
                }
                if (order.side() == Side.SELL
                        && (bestAsk == null || order.limitPrice().compareTo(bestAsk) < 0)) {
                    bestAsk = order.limitPrice();
                }
            }
            market.add(new RoleView.Market(symbol,
                    lastTrade == null ? null : lastTrade.price(),
                    lastTrade == null ? 0L : lastTrade.quantity(),
                    lastTrade == null ? -1L : lastTrade.timeMillis(), bestBid, bestAsk));
        }
        return market;
    }

    /** 岗位权限状态：只枚举本人当前任职范围内的组织、产品与账户。 */
    private List<RoleView.Permission> permissionsOf(RoleAccess.State access,
                                                    List<RoleView.Appointment> appointments,
                                                    String person, long now) {
        List<RoleView.Permission> permissions = new ArrayList<>();
        for (RoleView.Appointment appointment : appointments) {
            String organization = appointment.organizationPartyId();
            RoleAccess.Target orgTarget = RoleAccess.Target.organization(organization);
            for (RoleAccess.Action action : RoleAccess.Action.values()) {
                if (action == RoleAccess.Action.READ_ORGANIZATION_FILE
                        || action == RoleAccess.Action.WRITE_RESEARCH) {
                    permissions.add(permission(access, person, action, orgTarget,
                            "ORGANIZATION:" + organization, now));
                    continue;
                }
                for (RoleAccess.ProductMandate mandate : access.mandates()) {
                    if (!mandate.organizationPartyId().equals(organization)) {
                        continue;
                    }
                    if (action == RoleAccess.Action.SET_PORTFOLIO_TARGET
                            || action == RoleAccess.Action.ISSUE_RISK_HALT) {
                        permissions.add(permission(access, person, action,
                                RoleAccess.Target.product(organization, mandate.productOwnerId()),
                                "PRODUCT:" + mandate.productOwnerId(), now));
                        continue;
                    }
                    for (String accountId : mandate.accountIds()) {
                        permissions.add(permission(access, person, action,
                                RoleAccess.Target.account(organization,
                                        mandate.productOwnerId(), accountId),
                                "ACCOUNT:" + accountId, now));
                    }
                }
            }
        }
        return permissions;
    }

    private RoleView.Permission permission(RoleAccess.State access, String person,
                                          RoleAccess.Action action, RoleAccess.Target target,
                                          String scope, long now) {
        return new RoleView.Permission(action, scope,
                RoleAccess.authorize(access, person, action, target, now));
    }

    // ==================================================================
    // M4d 决定屏障与尝试：运行桥
    // ==================================================================

    /** 一次已排定的动作：任务种类、参数正文、内部标识与到期，全部可由决定原文重算。 */
    private record PlacedAction(String actionId, String tool, String kind, String taskId,
                                String commandId, long dueMillis, String payloadJson) {
    }

    /** 决定审计事件类型：只出现在已确认批次里，不进入状态快照。 */
    private static final String DECISION_AUDIT_V1 = "DECISION_AUDIT_V1";

    /**
     * 管理端只读决定审计：来源于唯一世界日志的已确认事件，不参与人物输入、模拟时间、
     * 公平排序或决定摘要。
     *
     * <p>墙钟值只供管理端核对。派发时点表示**宿主派发许可**的时点（含执行器等待），
     * 不声称观测到真实外部提供方的调用起点；恢复后没有本进程标记时该值为 {@code null}，
     * 不得用当前时间补造。两段等待量只用单调纳秒差换算，负值明确为未知。</p>
     *
     * @param runId                    运行标识
     * @param actionId                 动作标识
     * @param attemptId                尝试标识
     * @param attemptNo                尝试序号
     * @param recoveryGeneration       尝试所属恢复代次
     * @param kind                     审计类别：REPLY/FAILURE/TIMEOUT/REJECTED_IDENTITY_REUSE
     * @param outcome                  与同批命令回报一致的结果码
     * @param position                 该审计所在的已确认提交位置
     * @param dispatchedAtEpochMillis  宿主派发许可时点；未知为空
     * @param receivedAtEpochMillis    成功入队或宿主直投入口的接收时点；未知为空
     * @param recordedAtEpochMillis    宿主提交准备时点
     * @param dispatchToRecordMillis   派发到记录的等待量；未知为空
     * @param receiveToRecordMillis    接收到记录的等待量；未知为空
     * @param body                     被拒绝回执的精确原文；其余类别为空串
     * @param bodySha256               回执正文摘要，用于关联同批已记录尝试；无正文时为空串
     * @param declaredUsage            该回执声明的提供方用量；无回执时为空
     * @param usageSource              用量来源；无回执时为空串
     */
    public record DecisionAudit(String runId, String actionId, String attemptId, int attemptNo,
                                long recoveryGeneration, String kind, String outcome,
                                long position, Long dispatchedAtEpochMillis,
                                Long receivedAtEpochMillis, long recordedAtEpochMillis,
                                Long dispatchToRecordMillis, Long receiveToRecordMillis,
                                String body, String bodySha256,
                                DecisionModel.Usage declaredUsage, String usageSource) {

        /** 校验身份与文本字段；未知时间保持 null，不用 0 冒充。 */
        public DecisionAudit {
            if (runId == null || runId.isBlank() || actionId == null || actionId.isBlank()
                    || attemptId == null || attemptId.isBlank() || kind == null || kind.isBlank()
                    || outcome == null || outcome.isBlank()) {
                throw new IllegalArgumentException("审计身份与结果不能为空");
            }
            if (attemptNo < 1 || recoveryGeneration < 1 || position < 1) {
                throw new IllegalArgumentException("审计序号与提交位置必须从 1 开始");
            }
            if (recordedAtEpochMillis < 0) {
                throw new IllegalArgumentException("审计记录时点不能为负");
            }
            body = body == null ? "" : body;
            bodySha256 = bodySha256 == null ? "" : bodySha256;
            usageSource = usageSource == null ? "" : usageSource;
        }
    }

    /** 本进程派发许可的墙钟标记：纳秒只算耗时，epoch 只做审计。 */
    private record DispatchMark(long nanos, long epochMillis) {
    }

    /** 回执接收元数据：在调用线程上打点，不读世界状态。 */
    private record ReceiptTiming(Long receivedAtEpochMillis, long receivedAtNanos) {

        /** 回调线程入队或宿主直投入口：当前调用线程时刻即接收时刻。 */
        static ReceiptTiming arrival() {
            return new ReceiptTiming(System.currentTimeMillis(), System.nanoTime());
        }
    }

    /** 三点墙钟与两段等待；未知值保持 null。 */
    private record AuditTiming(Long dispatchedAtEpochMillis, Long receivedAtEpochMillis,
                               long recordedAtEpochMillis, Long dispatchToRecordMillis,
                               Long receiveToRecordMillis) {
    }

    /**
     * 由本进程派发标记与候选接收元数据算出审计时间；未知即 null，负耗时明确未知。
     *
     * @param attemptId           尝试标识
     * @param receipt             候选携带的接收元数据；无回执为空
     * @param recordedAtNanos     记录时点的单调纳秒
     * @param recordedAtEpochMillis 记录时点的 epoch 毫秒
     * @return 审计时间
     */
    private AuditTiming auditTiming(String attemptId, ReceiptTiming receipt,
                                    long recordedAtNanos, long recordedAtEpochMillis) {
        DispatchMark mark = decisionInFlight.get(attemptId);
        Long dispatchedAt = mark == null ? null : mark.epochMillis();
        Long dispatchToRecord = mark == null ? null
                : elapsedMillis(recordedAtNanos - mark.nanos());
        Long receivedAt = receipt == null ? null : receipt.receivedAtEpochMillis();
        Long receiveToRecord = receipt == null ? null
                : elapsedMillis(recordedAtNanos - receipt.receivedAtNanos());
        return new AuditTiming(dispatchedAt, receivedAt, recordedAtEpochMillis,
                dispatchToRecord, receiveToRecord);
    }

    /** 单调纳秒差换毫秒；负值（时钟或线程异常）明确未知，不伪造零。 */
    private static Long elapsedMillis(long nanos) {
        if (nanos < 0) {
            return null;
        }
        return nanos / 1_000_000L;
    }

    /** 由完整身份、类别与实际结算关系构造一条审计。 */
    private static DecisionAudit decisionAudit(String runId, String actionId, String attemptId,
                                               int attemptNo, long generation, String kind,
                                               String outcome, long position, AuditTiming timing,
                                               String body, String bodySha256,
                                               DecisionModel.Usage usage, String usageSource) {
        return new DecisionAudit(runId, actionId, attemptId, attemptNo, generation, kind, outcome,
                position, timing.dispatchedAtEpochMillis(), timing.receivedAtEpochMillis(),
                timing.recordedAtEpochMillis(), timing.dispatchToRecordMillis(),
                timing.receiveToRecordMillis(), body, bodySha256, usage, usageSource);
    }

    /** 一次正常回执结算的审计：正文只在快照保存，这里用摘要关联同批已记录尝试。 */
    private static DecisionAudit replyAudit(DecisionRun run, DecisionModel.Decision decision,
                                            DecisionModel.Attempt attempt, String outcome,
                                            long position, AuditTiming timing) {
        return decisionAudit(run.state().runId(), decision.actionId(), attempt.attemptId(),
                attempt.attemptNo(), attempt.recoveryGeneration(), "REPLY", outcome, position,
                timing, "", sha256Hex(attempt.replyBody()), attempt.declaredUsage(),
                attempt.usageSource());
    }

    /** 审计事件：payload 与只读视图是同一个不可变记录，不复制第二套字段。 */
    private String auditJson(DecisionAudit audit) {
        try {
            return decisionMapper.writeValueAsString(audit);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("无法生成决定审计原文", failure);
        }
    }

    /** 事件标识按尝试与提交位置稳定派生；位置在一份日志内唯一。 */
    private WorldEvent auditEvent(DecisionAudit audit) {
        return new WorldEvent("dec-audit:" + audit.attemptId() + ":" + audit.position(),
                DECISION_AUDIT_V1, auditJson(audit));
    }

    /** 按尝试标识取该决定下的尝试；不存在返回 null。 */
    private static DecisionModel.Attempt attemptOf(DecisionModel.State model, String actionId,
                                                   String attemptId) {
        DecisionModel.Decision decision = DecisionModel.decision(model, actionId).orElse(null);
        if (decision == null) {
            return null;
        }
        for (DecisionModel.Attempt candidate : decision.attempts()) {
            if (candidate.attemptId().equals(attemptId)) {
                return candidate;
            }
        }
        return null;
    }

    /** 摘要只用于去重与关联，不能替代语义校验。 */
    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少 SHA-256", impossible);
        }
    }

    /**
     * 管理端只读审计列表：只从唯一世界日志的已确认批次导出，不补时间、不写盘。
     *
     * <p>返回不可变列表，按提交顺序排列；旧档案没有审计事件时为空。严格语义校验发生在
     * 恢复期逐批核对，读取本身不修改任何状态。</p>
     *
     * @return 不可变审计列表
     */
    public synchronized List<DecisionAudit> decisionAudits() {
        requireOpen();
        List<DecisionAudit> audits = new ArrayList<>();
        for (WorldCommit commit : journal.commits()) {
            for (WorldEvent event : commit.events()) {
                if (DECISION_AUDIT_V1.equals(event.type())) {
                    audits.add(readAudit(event.payload()));
                }
            }
        }
        return List.copyOf(audits);
    }

    /** 读取一条已确认审计原文；恢复期已按语义核对过。 */
    private DecisionAudit readAudit(String payload) {
        try {
            return decisionMapper.readValue(payload, DecisionAudit.class);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("已确认决定审计原文无法解析", invalid);
        }
    }

    private void requireDecisionsEnabled() {
        if (!manifest.hasDecisions() || state.decisions() == null) {
            throw new IllegalStateException("当前运行未启用决定模块：" + manifest.rulesVersion());
        }
    }

    private DecisionRun requireDecisions() {
        DecisionRun run = state.decisions();
        if (run == null) {
            throw new IllegalStateException("当前运行没有决定子集状态");
        }
        return run;
    }

    /** 进程本地门闸：新运行与每次重开都必须显式接续。 */
    private void requireDecisionGate() {
        if (!decisionGateOpen) {
            throw new IllegalStateException("决定会话尚未接续：必须先调用 beginDecisionSession");
        }
    }

    /**
     * 显式接续决定会话：递增恢复代次，按稳定次序保守结算旧代次未消费尝试并按原输入有界重试，
     * 同批落盘后才打开本进程的派发与结果应用门闸。
     *
     * @param commandId 可信宿主命令标识，重发返回原回报
     * @return {@code SESSION_OPEN:<generation>}
     * @throws IOException 持久提交失败；此时门闸保持关闭、状态不前进
     */
    public synchronized String beginDecisionSession(String commandId) throws IOException {
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireDecisionsEnabled();
        requireText(commandId, "命令标识");
        DecisionRun run = requireDecisions();
        String internalCommand = "dec-session:" + commandId;
        if (state.commandContents().containsKey(internalCommand)) {
            return state.commandOutcomes().get(internalCommand);
        }
        DecisionModel.State raw = run.state();
        DecisionModel.State ready = raw.sessionOpen() ? DecisionModel.recover(raw) : raw;
        DecisionModel.Result begun = DecisionModel.beginSession(ready);
        if (begun.verdict() != DecisionModel.Verdict.ACCEPTED) {
            throw new IllegalStateException("无法接续决定会话：" + begun.reason());
        }
        DecisionModel.State nextModel = begun.state();
        for (DecisionModel.Decision decision : resumeOrder(nextModel)) {
            DecisionModel.Result resumed = DecisionModel.resumeDecision(nextModel,
                    decision.actionId());
            if (resumed.verdict() == DecisionModel.Verdict.ACCEPTED) {
                nextModel = resumed.state();
            }
        }
        DecisionRun candidate = run.withState(nextModel);
        String content = "{\"generation\":" + nextModel.recoveryGeneration() + "}";
        String outcome = "SESSION_OPEN:" + nextModel.recoveryGeneration();
        WorldState world = decisionWorld(candidate, internalCommand, content, outcome);
        commitAndApply(internalCommand, content, outcome,
                List.of(new WorldEvent("dec-session:" + commandId, "DECISION_SESSION_OPEN_V1",
                        content)), world);
        decisionGateOpen = true;
        decisionInFlight.clear();
        return outcome;
    }

    /** 需要接续的旧代次未结算尝试，按种子公平顺序与动作标识稳定排序。 */
    private List<DecisionModel.Decision> resumeOrder(DecisionModel.State model) {
        List<DecisionModel.Decision> open = new ArrayList<>();
        for (DecisionModel.Decision decision : model.decisions()) {
            if (decision.status() == DecisionModel.Status.OPEN
                    && !decision.lastAttempt().settledDone()
                    && decision.lastAttempt().recoveryGeneration() < model.recoveryGeneration()) {
                open.add(decision);
            }
        }
        open.sort(Comparator
                .comparing((DecisionModel.Decision decision) -> WorldScheduler.fairOrder(seed,
                        decision.actionId()))
                .thenComparing(DecisionModel.Decision::actionId));
        return open;
    }

    /**
     * 一次冻结一批人物的决定输入：同一已确认截止位置、同一决定点、各自冻结输入与屏障。
     *
     * <p>幂等先于投影：同一动作标识的已完成请求直接返回，不重新投影新世界。一人至多一个
     * 未解决决定；批内重复人物或动作整批拒绝且不写盘。</p>
     *
     * @param commandId 可信宿主命令标识
     * @param requests  人物、动作标识与唤醒原因
     * @return {@code DECIDING:<新开数量>}
     * @throws IOException 持久提交失败
     */
    public synchronized String openDecisions(String commandId, List<DecisionRun.Request> requests)
            throws IOException {
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireDecisionsEnabled();
        requireDecisionGate();
        requireText(commandId, "命令标识");
        Objects.requireNonNull(requests, "决定请求");
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个决定请求");
        }
        DecisionRun run = requireDecisions();
        String internalCommand = "dec-open:" + commandId;
        requests = List.copyOf(requests);
        String content = mapper.writeValueAsString(requests);
        String replay = decisionCommandResult(internalCommand, content);
        if (replay != null) {
            return replay;
        }
        long cutoffPosition = state.position();
        long cutoffTime = state.timeMillis();
        Set<String> persons = new LinkedHashSet<>();
        Set<String> newPersons = new LinkedHashSet<>();
        Set<String> actionIds = new LinkedHashSet<>();
        for (DecisionRun.Request request : requests) {
            requireText(request.personPartyId(), "人物");
            requireText(request.actionId(), "动作标识");
            requireText(request.wakeReason(), "唤醒原因");
            if (!persons.add(request.personPartyId())) {
                throw new IllegalArgumentException("同一批不得给同一人物两个决定："
                        + request.personPartyId());
            }
            if (!actionIds.add(request.actionId())) {
                throw new IllegalArgumentException("同一批不得复用动作标识：" + request.actionId());
            }
            if (!decisionConfig.thinkMillisByWake().containsKey(request.wakeReason())) {
                throw new IllegalArgumentException("未知唤醒原因：" + request.wakeReason());
            }
            DecisionModel.Decision existing = DecisionModel.decision(run.state(),
                    request.actionId()).orElse(null);
            if (existing != null && (!existing.personPartyId().equals(request.personPartyId())
                    || !existing.wakeReason().equals(request.wakeReason()))) {
                throw new IllegalArgumentException("同一动作标识不能对应不同请求："
                        + request.actionId());
            }
            if (existing == null) {
                newPersons.add(request.personPartyId());
            }
        }
        for (String person : newPersons) {
            for (DecisionModel.Decision decision : run.state().decisions()) {
                if (decision.personPartyId().equals(person)
                        && decision.status() == DecisionModel.Status.OPEN) {
                    throw new IllegalArgumentException("同一人物至多一个未解决决定：" + person);
                }
            }
        }
        Optional<DecisionModel.Decision> barrier = DecisionModel.earliestBarrier(run.state());
        if (!newPersons.isEmpty() && barrier.isPresent()
                && cutoffTime >= barrier.get().barrierAtMillis()) {
            throw new IllegalStateException("未解决屏障之前不能建立新的观察");
        }

        DecisionRun candidate = run;
        DecisionModel.State model = run.state();
        int opened = 0;
        int created = 0;
        for (DecisionRun.Request request : requests) {
            if (DecisionModel.decision(model, request.actionId()).isPresent()) {
                continue;
            }
            WorldRoleSession session = openRoleSessionAt(state, request.personPartyId(),
                    cutoffTime);
            RoleTradingView view = roleTradingViewAt(state, session);
            List<String> tools = allowedToolsOf(view);
            String input = DecisionRun.inputText(view, request.wakeReason(), cutoffTime,
                    pendingActionsAt(state, run, request.personPartyId()), tools, decisionConfig);
            DecisionModel.OpenRequest openRequest = new DecisionModel.OpenRequest(
                    model.runId(), request.actionId(), request.personPartyId(),
                    request.wakeReason(), cutoffTime, cutoffPosition, cutoffTime,
                    DecisionRun.TOOL_CONTRACT_VERSION, tools, input);
            DecisionModel.Result result = DecisionModel.openDecision(model, openRequest,
                    cutoffPosition, cutoffTime);
            switch (result.verdict()) {
                case ACCEPTED, BUDGET_EXHAUSTED -> {
                    model = result.state();
                    candidate = candidate.withState(model).withBinding(new DecisionRun.Binding(
                            request.actionId(), request.personPartyId(), request.wakeReason(),
                            session.appointments()));
                    created++;
                    if (result.verdict() == DecisionModel.Verdict.ACCEPTED) {
                        opened++;
                    }
                }
                case DUPLICATE -> {
                }
                default -> throw new IllegalArgumentException("无法打开决定：" + result.reason());
            }
        }
        if (created == 0) {
            return "DECIDING:0";
        }
        String outcome = "DECIDING:" + opened;
        WorldState world = decisionWorld(candidate, internalCommand, content, outcome);
        commitAndApply(internalCommand, content, outcome,
                List.of(new WorldEvent("dec-opened:" + commandId, "DECISION_OPENED_V1", content)),
                world);
        return outcome;
    }

    /**
     * 接受一条提供方回执：纯核先判定身份与代次，再严格校验工具参数，最后把决定、动作任务、
     * 原命令映射与已用标识写进同一批。
     *
     * <p>旧代次回执只做持久归档（一个审计批次），不改决定、预算与经济；同一归档重放不再写盘。
     * 静态参数失败走已知用量技术失败入口，保留原正文与原声明，不写成观望。</p>
     *
     * @param commandId 可信宿主命令标识
     * @param reply     提供方回执
     * @return {@code RECORDED}、{@code DUPLICATE}、{@code STALE_GENERATION}、
     *         {@code TECHNICAL_FAILED:<reason>} 或 {@code REJECTED:<reason>}
     * @throws IOException 持久提交失败
     */
    public synchronized String acceptDecisionReply(String commandId, DecisionModel.Reply reply)
            throws IOException {
        return acceptDecisionReply(commandId, reply, ReceiptTiming.arrival());
    }

    /**
     * 回执结算的统一入口：宿主直投与 writer 处理队列候选共用。
     *
     * <p>调用方必须持有本对象监视器；候选接收时点由调用方按来源传入，本方法不推断。</p>
     *
     * @param commandId 可信宿主命令标识
     * @param reply     提供方回执
     * @param receipt   接收元数据（回调线程打点或宿主直投入口）
     * @return 与公共入口相同的结果码
     * @throws IOException 持久提交失败
     */
    private String acceptDecisionReply(String commandId, DecisionModel.Reply reply,
                                       ReceiptTiming receipt) throws IOException {
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireDecisionsEnabled();
        requireDecisionGate();
        requireText(commandId, "命令标识");
        Objects.requireNonNull(reply, "回执");
        DecisionRun run = requireDecisions();
        if (!run.state().runId().equals(reply.runId())) {
            return "REJECTED:RUN_MISMATCH";
        }
        String internalCommand = "dec-reply:" + commandId;
        String content = mapper.writeValueAsString(reply);
        String replay = decisionCommandResult(internalCommand, content);
        if (replay != null) {
            return replay;
        }
        DecisionModel.Decision decision = DecisionModel.decision(run.state(), reply.actionId())
                .orElse(null);
        if (decision == null) {
            return "REJECTED:UNKNOWN_DECISION";
        }
        DecisionModel.Attempt attempt = null;
        for (DecisionModel.Attempt candidate : decision.attempts()) {
            if (candidate.attemptId().equals(reply.attemptId())) {
                attempt = candidate;
            }
        }
        if (attempt == null || attempt.attemptNo() != reply.attemptNo()) {
            return "REJECTED:UNKNOWN_ATTEMPT";
        }
        if (reply.recoveryGeneration() != attempt.recoveryGeneration()) {
            return "REJECTED:GENERATION_MISMATCH";
        }
        if (reply.recoveryGeneration() != run.state().recoveryGeneration()) {
            return archiveStale(commandId, content, run, reply, attempt, receipt);
        }
        if (attempt.settledDone()) {
            boolean same = attempt.replyReceived() && attempt.replyBody().equals(reply.body())
                    && Objects.equals(attempt.declaredUsage(), reply.usage())
                    && attempt.usageSource().equals(reply.usageSource());
            if (same) {
                return "DUPLICATE";
            }
            return rejectIdentityReuse(internalCommand, content, run, decision, attempt, reply, receipt);
        }
        if (decision.status() != DecisionModel.Status.OPEN) {
            return "REJECTED:" + (decision.status() == DecisionModel.Status.RECORDED
                    ? DecisionModel.REASON_ALREADY_RECORDED : DecisionModel.REASON_ALREADY_EXPIRED);
        }

        DecisionModel.Result result = DecisionModel.submitReply(run.state(), reply);
        if (result.verdict() == DecisionModel.Verdict.DUPLICATE) {
            return "DUPLICATE";
        }
        if (result.verdict() == DecisionModel.Verdict.STALE_GENERATION) {
            return "STALE_GENERATION";
        }
        if (result.verdict() != DecisionModel.Verdict.ACCEPTED) {
            return "REJECTED:" + result.reason();
        }
        DecisionModel.State accepted = result.state();
        DecisionModel.Decision recorded = result.decision();
        DecisionRun withState = run.withState(accepted);
        DecisionModel.Attempt settled = attemptOf(accepted, recorded.actionId(), reply.attemptId());
        if (settled == null) {
            throw new IllegalStateException("已记录回执缺少对应尝试：" + reply.attemptId());
        }
        // 记录时点在即将提交前取一次；等待量只用单调纳秒差，未知值保持 null。
        AuditTiming marks = auditTiming(reply.attemptId(), receipt, System.nanoTime(),
                System.currentTimeMillis());
        long position = state.position() + 1L;
        if (recorded.outcome() == DecisionModel.Outcome.TECHNICAL_FAILED) {
            String outcome = "TECHNICAL_FAILED:" + recorded.lastAttempt().reason();
            return commitDecisionOnly(commandId, content, withState, outcome, reply.attemptId(),
                    replyAudit(run, recorded, settled, outcome, position, marks));
        }
        if (recorded.outcome() == DecisionModel.Outcome.WAIT) {
            return commitDecisionOnly(commandId, content, withState, "RECORDED", reply.attemptId(),
                    replyAudit(run, recorded, settled, "RECORDED", position, marks));
        }

        DecisionRun.Binding binding = run.binding(recorded.actionId());
        if (binding == null) {
            throw new IllegalStateException("已记录意图缺少观察绑定：" + recorded.actionId());
        }
        PlacedAction placed;
        try {
            placed = planAction(binding, recorded, accepted, null);
        } catch (IOException | RuntimeException failure) {
            // 静态参数失败必须落在**原未消费状态**上：不能先持久 RECORDED 再改回。
            DecisionModel.Result rejected = DecisionModel.rejectReply(run.state(), reply,
                    DecisionModel.REASON_TOOL_ARGS_INVALID + ":" + shortReason(failure));
            if (rejected.verdict() != DecisionModel.Verdict.ACCEPTED
                    && rejected.verdict() != DecisionModel.Verdict.DUPLICATE) {
                throw new IllegalStateException("无法记录工具参数技术失败：" + rejected.reason(),
                        failure);
            }
            DecisionModel.State rejectedState = rejected.state();
            DecisionModel.Attempt rejectedAttempt = attemptOf(rejectedState, recorded.actionId(),
                    reply.attemptId());
            if (rejectedAttempt == null) {
                throw new IllegalStateException("工具参数失败缺少对应尝试：" + reply.attemptId(),
                        failure);
            }
            String outcome = "TECHNICAL_FAILED:" + DecisionModel.REASON_TOOL_ARGS_INVALID;
            return commitDecisionOnly(commandId, content, run.withState(rejectedState), outcome,
                    reply.attemptId(), replyAudit(run, recorded, rejectedAttempt, outcome,
                            state.position() + 1L, marks));
        }

        DecisionRun withRef = withState.withActionRef(new DecisionRun.ActionRef(
                placed.actionId(), placed.taskId(), placed.tool(), placed.dueMillis()));
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.add(new PendingTask(placed.taskId(), placed.dueMillis(),
                WorldScheduler.Phase.COMPLETION, placed.kind(), placed.payloadJson()));
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(placed.taskId());
        Map<String, String> contents = new TreeMap<>(state.commandContents());
        contents.put(placed.commandId(), placed.payloadJson());
        contents.put(internalCommand, content);
        Map<String, String> outcomes = new TreeMap<>(state.commandOutcomes());
        outcomes.put(placed.commandId(), "SCHEDULED:" + placed.actionId());
        outcomes.put(internalCommand, "RECORDED");
        WorldState world = next(state.timeMillis(), state.lastPhase(), state.accounts(), pending,
                state.delivered(), used, contents, outcomes, state.securities(), state.exchange(),
                state.manufacturing(), state.governance(), state.roles(), state.information(),
                state.trading(), withRef);
        DecisionAudit scheduled = replyAudit(run, recorded, settled, "RECORDED", world.position(),
                marks);
        if (scheduled.position() != world.position()) {
            throw new IllegalStateException("审计位置与提交位置不一致");
        }
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("dec-scheduled:" + placed.taskId(),
                "DECISION_ACTION_SCHEDULED_V1", placed.payloadJson()));
        events.add(auditEvent(scheduled));
        commitAndApply(internalCommand, content, "RECORDED", events, world);
        decisionInFlight.remove(reply.attemptId());
        return "RECORDED";
    }

    /** 旧代次回执：只在归档里追加一条，模型状态与经济一律不变；审计与归档同批。 */
    private String archiveStale(String commandId, String content, DecisionRun run,
                                DecisionModel.Reply reply, DecisionModel.Attempt attempt,
                                ReceiptTiming receipt) throws IOException {
        DecisionRun.Archive archive = new DecisionRun.Archive(reply.actionId(),
                reply.attemptId(), reply.attemptNo(), reply.recoveryGeneration(), reply.body(),
                reply.usage(), reply.usageSource(), "STALE_GENERATION", "OLD_GENERATION");
        for (DecisionRun.Archive existing : run.archives()) {
            if (existing.sameKey(archive)) {
                if (existing.equals(archive)) {
                    return "STALE_GENERATION";
                }
                DecisionModel.Decision decision = DecisionModel.decision(run.state(), reply.actionId()).orElseThrow();
                return rejectIdentityReuse("dec-reply:" + commandId, content, run, decision,
                        attempt, reply, receipt);
            }
        }
        DecisionRun candidate = run.withArchive(archive);
        WorldState world = decisionWorld(candidate, "dec-reply:" + commandId, content, "STALE_GENERATION");
        AuditTiming marks = auditTiming(reply.attemptId(), receipt, System.nanoTime(),
                System.currentTimeMillis());
        DecisionAudit audit = decisionAudit(run.state().runId(), reply.actionId(),
                reply.attemptId(), reply.attemptNo(), reply.recoveryGeneration(), "REPLY",
                "STALE_GENERATION", world.position(), marks, "", sha256Hex(reply.body()),
                reply.usage(), reply.usageSource());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("dec-archived:" + archive.actionId() + ":"
                + archive.attemptId(), "DECISION_STALE_ARCHIVED_V1", content));
        events.add(auditEvent(audit));
        commitAndApply("dec-reply:" + commandId, content, "STALE_GENERATION", events, world);
        decisionInFlight.remove(reply.attemptId());
        return "STALE_GENERATION";
    }

    /** 只提交决定状态（观望、技术失败、重试），不排任何业务动作；审计与原回执同批。 */
    private String commitDecisionOnly(String commandId, String content, DecisionRun candidate,
                                      String outcome, String attemptId, DecisionAudit audit)
            throws IOException {
        WorldState world = decisionWorld(candidate, "dec-reply:" + commandId, content, outcome);
        if (audit.position() != world.position()) {
            throw new IllegalStateException("审计位置与提交位置不一致");
        }
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("dec-reply:" + commandId, "DECISION_REPLIED_V1", outcome));
        events.add(auditEvent(audit));
        commitAndApply("dec-reply:" + commandId, content, outcome, events, world);
        if (attemptId != null) {
            decisionInFlight.remove(attemptId);
        }
        return outcome;
    }

    /**
     * 已消费身份复用了不同正文、声明用量或来源：按完整规范回执的摘要留一条独立审计批次。
     *
     * <p>命令标识取完整回执原文的 SHA-256，因此同一非法回执换宿主命令重投也不重复落盘；
     * 决定、预算、待办与钱股一律不变。合法摘要只作幂等键，语义判定仍在调用前完成。</p>
     *
     * @return 固定返回 {@code REJECTED:IDENTITY_REUSE}
     * @throws IOException 持久提交失败
     */
    private String rejectIdentityReuse(String hostCommand, String content, DecisionRun run,
                                       DecisionModel.Decision decision,
                                       DecisionModel.Attempt attempt, DecisionModel.Reply reply,
                                       ReceiptTiming receipt) throws IOException {
        String rejectId = "dec-reject:" + sha256Hex(content);
        String replay = decisionCommandResult(rejectId, content);
        if (replay != null) {
            return replay;
        }
        Map<String, String> contents = new TreeMap<>(state.commandContents());
        contents.put(rejectId, content);
        contents.put(hostCommand, content);
        Map<String, String> outcomes = new TreeMap<>(state.commandOutcomes());
        outcomes.put(rejectId, "REJECTED:IDENTITY_REUSE");
        outcomes.put(hostCommand, "REJECTED:IDENTITY_REUSE");
        WorldState world = next(state.timeMillis(), state.lastPhase(), state.accounts(), state.pending(),
                state.delivered(), state.usedTaskIds(), contents, outcomes, state.securities(),
                state.exchange(), state.manufacturing(), state.governance(), state.roles(),
                state.information(), state.trading(), run);
        AuditTiming marks = auditTiming(attempt.attemptId(), receipt, System.nanoTime(),
                System.currentTimeMillis());
        DecisionAudit audit = decisionAudit(run.state().runId(), decision.actionId(),
                attempt.attemptId(), attempt.attemptNo(), attempt.recoveryGeneration(),
                "REJECTED_IDENTITY_REUSE", "REJECTED:IDENTITY_REUSE", world.position(), marks,
                reply.body(), sha256Hex(reply.body()), reply.usage(), reply.usageSource());
        commitAndApply(hostCommand, content, "REJECTED:IDENTITY_REUSE",
                List.of(auditEvent(audit)), world);
        return "REJECTED:IDENTITY_REUSE";
    }

    /** 原始宿主命令在派生观察或读取可变决定状态之前校验。 */
    private String decisionCommandResult(String commandId, String content) {
        String previous = state.commandContents().get(commandId);
        if (previous == null) {
            return null;
        }
        if (!previous.equals(content)) {
            throw new IllegalArgumentException("同一命令标识不能对应不同内容");
        }
        return state.commandOutcomes().get(commandId);
    }

    /** 决定与其原始宿主请求、实际回报在同一快照中提交。 */
    private WorldState decisionWorld(DecisionRun candidate, String commandId, String content,
                                      String outcome) {
        Map<String, String> contents = new TreeMap<>(state.commandContents());
        contents.put(commandId, content);
        Map<String, String> outcomes = new TreeMap<>(state.commandOutcomes());
        outcomes.put(commandId, outcome);
        return next(state.timeMillis(), state.lastPhase(), state.accounts(), state.pending(),
                state.delivered(), state.usedTaskIds(), contents, outcomes, state.securities(),
                state.exchange(), state.manufacturing(), state.governance(), state.roles(),
                state.information(), state.trading(), candidate);
    }

    /**
     * 派发一次调用：只返回程序绑定的身份与冻结输入，不写盘、不推进世界时间。
     * 同一尝试重复派发返回同一描述；并发派发受 {@code maxInFlight} 限制。
     *
     * @param actionId 动作标识
     * @return 调用描述
     */
    public synchronized DecisionRun.Call dispatchDecision(String actionId) {
        requireOpen();
        requireWriter();
        requireDecisionsEnabled();
        requireDecisionGate();
        if (paused) {
            throw new IllegalStateException("运行已暂停，不接受新的派发");
        }
        DecisionRun run = requireDecisions();
        DecisionModel.Decision decision = DecisionModel.decision(run.state(), actionId)
                .orElseThrow(() -> new IllegalArgumentException("未知决定：" + actionId));
        if (decision.status() != DecisionModel.Status.OPEN) {
            throw new IllegalStateException("决定不在可派发状态：" + decision.status());
        }
        DecisionModel.Attempt attempt = decision.lastAttempt();
        if (attempt.settledDone()) {
            throw new IllegalStateException("最后尝试已结算，不能再派发：" + actionId);
        }
        if (!decisionInFlight.containsKey(attempt.attemptId())
                && decisionInFlight.size() >= decisionConfig.limits().maxInFlight()) {
            throw new IllegalStateException("并发派发超过 maxInFlight="
                    + decisionConfig.limits().maxInFlight());
        }
        decisionInFlight.putIfAbsent(attempt.attemptId(),
                new DispatchMark(System.nanoTime(), System.currentTimeMillis()));
        return new DecisionRun.Call(run.state().runId(), decision.actionId(), attempt.attemptId(),
                attempt.attemptNo(), attempt.recoveryGeneration(), decision.wakeReason(),
                decision.allowedTools(), decision.inputText());
    }

    /**
     * 异步结果只入有界队列：任意线程可调用，绝不读写世界领域对象。
     *
     * @param reply 提供方回执
     * @return {@code QUEUED} 或 {@code QUEUE_FULL}
     */
    public String offerDecisionCallback(DecisionModel.Reply reply) {
        Objects.requireNonNull(reply, "回执");
        java.util.concurrent.ArrayBlockingQueue<DecisionCandidate> queue = decisionQueue;
        if (queue == null) {
            throw new IllegalStateException("当前运行未启用决定模块");
        }
        return queue.offer(new DecisionCandidate(reply, null, ReceiptTiming.arrival()))
                ? "QUEUED" : "QUEUE_FULL";
    }

    /** @return 队列当前长度（管理端审计用） */
    public synchronized int queuedDecisionCallbacks() {
        return decisionQueue == null ? 0 : decisionQueue.size();
    }

    /** 一条无回执失败候选：脚本缺失或抛错，由写入线程按预留保守结算。 */
    public record DecisionFailure(String actionId, int attemptNo, String attemptId,
                                  long recoveryGeneration, String reason) {

        /** 校验身份字段。 */
        public DecisionFailure {
            if (actionId == null || actionId.isBlank() || attemptId == null
                    || attemptId.isBlank()) {
                throw new IllegalArgumentException("失败候选身份不能为空");
            }
            if (attemptNo < 1 || recoveryGeneration < 1) {
                throw new IllegalArgumentException("失败候选序号必须从 1 开始");
            }
            reason = reason == null || reason.isBlank() ? "UNKNOWN_USAGE" : reason;
        }
    }

    /** 两种不可变候选共享一个容量，只有世界写入线程能够移除。 */
    private record DecisionCandidate(DecisionModel.Reply reply, DecisionFailure failure,
                                     ReceiptTiming receipt) {
        String actionId() {
            return reply != null ? reply.actionId() : failure.actionId();
        }

        String attemptId() {
            return reply != null ? reply.attemptId() : failure.attemptId();
        }
    }

    /**
     * 投递一条无回执失败候选：任意线程可调用，只入有界队列。
     *
     * @param failure 失败候选
     * @return {@code QUEUED} 或 {@code QUEUE_FULL}
     */
    public String offerDecisionFailure(DecisionFailure failure) {
        Objects.requireNonNull(failure, "失败候选");
        java.util.concurrent.ArrayBlockingQueue<DecisionCandidate> queue = decisionQueue;
        if (queue == null) {
            throw new IllegalStateException("当前运行未启用决定模块");
        }
        return queue.offer(new DecisionCandidate(null, failure, null)) ? "QUEUED" : "QUEUE_FULL";
    }

    /**
     * 由写入线程结算一次无回执失败：按冻结预留保守结算一次，并按剩余预算有界重试。
     * 绝不把技术失败改写成人物观望。
     *
     * @param actionId           动作标识
     * @param attemptNo          尝试序号
     * @param attemptId          尝试标识
     * @param recoveryGeneration 尝试所属代次
     * @param reason             失败原因
     * @return {@code TECHNICAL_FAILED:<reason>} 或拒绝码
     * @throws IOException 持久提交失败
     */
    public synchronized String failDecisionAttempt(String actionId, int attemptNo, String attemptId,
                                                   long recoveryGeneration, String reason)
            throws IOException {
        requireOpen();
        requireWriter();
        requireDecisionsEnabled();
        requireDecisionGate();
        requireNotPaused();
        DecisionRun run = requireDecisions();
        DecisionModel.Attempt failed = attemptOf(run.state(), actionId, attemptId);
        DecisionModel.Result result = DecisionModel.failAttempt(run.state(), actionId, attemptNo,
                attemptId, recoveryGeneration, reason);
        if (result.verdict() != DecisionModel.Verdict.ACCEPTED) {
            decisionInFlight.remove(attemptId);
            return "REJECTED:" + result.reason();
        }
        if (failed == null) {
            throw new IllegalStateException("已结算失败缺少对应尝试：" + attemptId);
        }
        DecisionRun candidate = run.withState(result.state());
        String content = "{\"actionId\":\"" + actionId + "\",\"attemptId\":\"" + attemptId
                + "\",\"reason\":\"" + reason + "\"}";
        WorldState world = next(state.timeMillis(), state.lastPhase(), state.accounts(),
                state.pending(), state.delivered(), state.usedTaskIds(), state.commandContents(),
                state.commandOutcomes(), state.securities(), state.exchange(),
                state.manufacturing(), state.governance(), state.roles(), state.information(),
                state.trading(), candidate);
        String outcome = "TECHNICAL_FAILED:" + reason;
        AuditTiming marks = auditTiming(attemptId, null, System.nanoTime(),
                System.currentTimeMillis());
        DecisionAudit audit = decisionAudit(run.state().runId(), actionId, attemptId,
                failed.attemptNo(), failed.recoveryGeneration(), "FAILURE", outcome,
                world.position(), marks, "", "", null, "");
        if (audit.position() != world.position()) {
            throw new IllegalStateException("审计位置与提交位置不一致");
        }
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("dec-fail:" + attemptId, "DECISION_FAILED_V1", content));
        events.add(auditEvent(audit));
        commitAndApply("dec-fail:" + attemptId, content, outcome, events, world);
        decisionInFlight.remove(attemptId);
        return outcome;
    }

    /**
     * 由世界写入线程应用已入队回执：先取候选（peek）、持久成功后才移除。
     * 暂停或提交失败时候选保留，不静默丢弃，也不重复调用提供方。
     *
     * @return 实际应用的条数
     * @throws IOException 持久提交失败；原候选留在队列并保持运行暂停
     */
    public synchronized int drainDecisionCallbacks() throws IOException {
        requireOpen();
        requireWriter();
        requireDecisionsEnabled();
        requireDecisionGate();
        if (paused || decisionQueue == null) {
            return 0;
        }
        List<DecisionCandidate> batch = new ArrayList<>(decisionQueue);
        batch.sort(Comparator
                .comparing((DecisionCandidate candidate) -> WorldScheduler.fairOrder(seed,
                        candidate.actionId()))
                .thenComparing(DecisionCandidate::actionId)
                .thenComparing(DecisionCandidate::attemptId));
        int applied = 0;
        for (DecisionCandidate candidate : batch) {
            String code;
            if (candidate.reply() != null) {
                // 由已确认位置派生本次自动命令，重复回执仍由尝试身份判定。
                code = acceptDecisionReply("callback:" + state.position(), candidate.reply(),
                        candidate.receipt());
            } else {
                DecisionFailure failure = candidate.failure();
                code = failDecisionAttempt(failure.actionId(), failure.attemptNo(),
                        failure.attemptId(), failure.recoveryGeneration(), failure.reason());
            }
            if (code.equals("RECORDED") || code.startsWith("TECHNICAL_FAILED")
                    || code.equals("STALE_GENERATION")) {
                applied++;
            }
            decisionQueue.remove(candidate);
        }
        return applied;
    }

    /**
     * 按真实墙钟结算超时尝试：只保守结算一次，不推进模拟时间、不产生业务动作。
     *
     * @return 本次结算的尝试条数
     * @throws IOException 持久提交失败
     */
    public synchronized int expireDecisionTimeouts() throws IOException {
        requireOpen();
        requireWriter();
        requireDecisionsEnabled();
        requireDecisionGate();
        if (paused) {
            return 0;
        }
        long timeoutMillis = decisionConfig.limits().wallTimeoutMillis();
        long now = System.nanoTime();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, DispatchMark> entry : decisionInFlight.entrySet()) {
            if (java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - entry.getValue().nanos())
                    >= timeoutMillis) {
                expired.add(entry.getKey());
            }
        }
        if (expired.isEmpty()) {
            return 0;
        }
        DecisionRun run = requireDecisions();
        int settled = 0;
        for (String attemptId : expired) {
            DecisionModel.Decision decision = decisionOfAttempt(run.state(), attemptId);
            if (decision == null) {
                decisionInFlight.remove(attemptId);
                continue;
            }
            DecisionModel.Attempt attempt = decision.lastAttempt();
            DecisionModel.Result result = DecisionModel.failAttempt(run.state(),
                    decision.actionId(), attempt.attemptNo(), attemptId,
                    attempt.recoveryGeneration(), "TIMEOUT");
            if (result.verdict() != DecisionModel.Verdict.ACCEPTED) {
                decisionInFlight.remove(attemptId);
                continue;
            }
            DecisionRun candidate = run.withState(result.state());
            String content = "{\"actionId\":\"" + decision.actionId() + "\",\"attemptId\":\""
                    + attemptId + "\",\"reason\":\"TIMEOUT\"}";
            WorldState world = next(state.timeMillis(), state.lastPhase(), state.accounts(),
                    state.pending(), state.delivered(), state.usedTaskIds(),
                    state.commandContents(), state.commandOutcomes(), state.securities(),
                    state.exchange(), state.manufacturing(), state.governance(), state.roles(),
                    state.information(), state.trading(), candidate);
            AuditTiming marks = auditTiming(attemptId, null, System.nanoTime(),
                    System.currentTimeMillis());
            DecisionAudit audit = decisionAudit(run.state().runId(), decision.actionId(),
                    attemptId, attempt.attemptNo(), attempt.recoveryGeneration(), "TIMEOUT",
                    "TIMEOUT", world.position(), marks, "", "", null, "");
            if (audit.position() != world.position()) {
                throw new IllegalStateException("审计位置与提交位置不一致");
            }
            List<WorldEvent> events = new ArrayList<>();
            events.add(new WorldEvent("dec-timeout:" + attemptId, "DECISION_TIMEOUT_V1",
                    content));
            events.add(auditEvent(audit));
            commitAndApply("dec-timeout:" + decision.actionId() + ":" + attemptId, content,
                    "TIMEOUT", events, world);
            run = candidate;
            decisionInFlight.remove(attemptId);
            settled++;
        }
        return settled;
    }

    /** 按尝试标识定位决定。 */
    private static DecisionModel.Decision decisionOfAttempt(DecisionModel.State model,
                                                             String attemptId) {
        for (DecisionModel.Decision decision : model.decisions()) {
            for (DecisionModel.Attempt attempt : decision.attempts()) {
                if (attempt.attemptId().equals(attemptId)) {
                    return decision;
                }
            }
        }
        return null;
    }

    /**
     * 最早未解决屏障：返回屏障时间与同刻全部动作标识（按种子公平顺序与标识稳定排序）。
     *
     * @return 未决屏障；没有未解决决定时为空
     */
    public synchronized Optional<DecisionRun.Barrier> pendingBarrier() {
        requireOpen();
        requireDecisionsEnabled();
        DecisionRun run = requireDecisions();
        Optional<DecisionModel.Decision> earliest = DecisionModel.earliestBarrier(run.state());
        if (earliest.isEmpty()) {
            return Optional.empty();
        }
        long time = earliest.get().barrierAtMillis();
        List<String> actionIds = new ArrayList<>();
        for (DecisionModel.Decision decision : run.state().decisions()) {
            if (decision.status() == DecisionModel.Status.OPEN
                    && decision.barrierAtMillis() == time) {
                actionIds.add(decision.actionId());
            }
        }
        actionIds.sort(Comparator
                .comparing((String actionId) -> WorldScheduler.fairOrder(seed, actionId))
                .thenComparing(actionId -> actionId));
        return Optional.of(new DecisionRun.Barrier(time, List.copyOf(actionIds)));
    }

    /** advanceTo 的钳制上限：最早未决屏障之前。 */
    private long decisionLimit(long inclusiveMillis) {
        if (!manifest.hasDecisions() || state.decisions() == null) {
            return inclusiveMillis;
        }
        Optional<DecisionModel.Decision> earliest = DecisionModel.earliestBarrier(
                state.decisions().state());
        return earliest.map(decision -> Math.min(inclusiveMillis, decision.barrierAtMillis()))
                .orElse(inclusiveMillis);
    }

    /** 本次推进是否撞上最早未决屏障（含 inclusive 恰好等于屏障）。 */
    private boolean stopsAtDecisionBarrier(long inclusiveMillis) {
        if (!manifest.hasDecisions() || state.decisions() == null) {
            return false;
        }
        Optional<DecisionModel.Decision> earliest = DecisionModel.earliestBarrier(
                state.decisions().state());
        return earliest.isPresent() && earliest.get().barrierAtMillis() <= inclusiveMillis;
    }

    /** 本次观察允许返回的工具：视图工具去掉只读引用，再加唯一内建观望。 */
    private static List<String> allowedToolsOf(RoleTradingView view) {
        TreeSet<String> tools = new TreeSet<>(view.tools());
        tools.remove(RoleTradingView.TOOL_CITE);
        tools.add(DecisionModel.TOOL_WAIT);
        return List.copyOf(tools);
    }

    /** 本人尚未完成的业务动作摘要：只取本人绑定且仍在待办里的动作引用。 */
    private List<DecisionRun.PendingAction> pendingActionsAt(WorldState snap, DecisionRun run,
                                                             String person) throws IOException {
        Set<String> pendingIds = new HashSet<>();
        for (PendingTask task : snap.pending()) {
            pendingIds.add(task.taskId());
        }
        List<DecisionRun.PendingAction> actions = new ArrayList<>();
        for (DecisionRun.ActionRef ref : run.actionRefs().values()) {
            DecisionRun.Binding binding = run.binding(ref.actionId());
            if (binding == null || !binding.personPartyId().equals(person)) {
                continue;
            }
            if (!pendingIds.contains(ref.taskId())) {
                continue;
            }
            actions.add(new DecisionRun.PendingAction(ref.actionId(), ref.tool(),
                    ref.dueMillis(), replyArgs(DecisionModel.recordedReplyBody(
                            DecisionModel.decision(run.state(), ref.actionId()).orElseThrow())).toString()));
        }
        actions.sort(Comparator.comparing(DecisionRun.PendingAction::actionId));
        return actions;
    }

    /** 由显式快照开会话：与 {@link #openRoleSession(String)} 同一绑定口径，不读全局状态。 */
    WorldRoleSession openRoleSessionAt(WorldState snap, String personPartyId, long observedAt) {
        if (snap.information() == null || snap.roles() == null) {
            throw new IllegalStateException("快照缺少阅读或任职子集");
        }
        if (personPartyId == null || personPartyId.isBlank()) {
            throw new IllegalArgumentException("角色会话不可用");
        }
        InformationModel.State information = snap.information().information();
        if (!information.persons().contains(personPartyId)) {
            throw new IllegalArgumentException("角色会话不可用");
        }
        RoleAccess.State access = snap.roles().access();
        Map<String, String> observed = new LinkedHashMap<>();
        for (RoleAccess.Appointment appointment : access.appointments()) {
            if (!appointment.personPartyId().equals(personPartyId)
                    || !appointment.activeAt(observedAt)) {
                continue;
            }
            String organization = appointment.organizationPartyId();
            String existing = observed.get(organization);
            if (existing == null || startsLater(access, appointment, existing)) {
                observed.put(organization, appointment.id());
            }
        }
        return new WorldRoleSession(this, personPartyId, observedAt, observed);
    }

    /**
     * 把已记录意图翻译成原四岗动作任务：严格 DTO（未知字段、尾随内容、浮点整数一律失败），
     * 人物与任职取决定打开时的绑定，任务标识由动作标识稳定派生。
     */
    private PlacedAction planAction(DecisionRun.Binding binding,
                                    DecisionModel.Decision recorded,
                                    DecisionModel.State model, String forcedTaskId)
            throws IOException {
        String tool = recorded.recordedTool();
        if (DecisionModel.TOOL_WAIT.equals(tool)) {
            throw new IOException("观望不能排业务动作");
        }
        JsonNode args = replyArgs(recorded.lastAttempt().replyBody());
        String kind;
        Object body;
        switch (tool) {
            case RoleTradingView.TOOL_READING_START -> {
                kind = PendingTask.ROLE_TRADING_READ_V1;
                RoleReadBody read = strict(RoleReadBody.class, args);
                requireText(read.docId(), "资料标识");
                if (read.version() < 1) {
                    throw new IllegalArgumentException("资料版本必须从1开始");
                }
                body = read;
            }
            case RoleTradingView.TOOL_READING_CANCEL -> {
                kind = PendingTask.ROLE_TRADING_UNREAD_V1;
                RoleUnreadBody unread = strict(RoleUnreadBody.class, args);
                RoleView.ReadingRef ref = new RoleView.ReadingRef(binding.personPartyId(),
                        unread.docId(), unread.version(), unread.index());
                body = new RoleUnreadBody(ref.docId(), ref.version(), ref.index());
            }
            case RoleTradingView.TOOL_RESEARCH_SUBMIT -> {
                kind = PendingTask.ROLE_TRADING_RESEARCH_V1;
                body = strict(WorldRoleSession.ResearchDraft.class, args);
            }
            case RoleTradingView.TOOL_TARGET_SET -> {
                kind = PendingTask.ROLE_TRADING_TARGET_V1;
                body = strict(WorldRoleSession.TargetDraft.class, args);
            }
            case RoleTradingView.TOOL_TARGET_REVIEW -> {
                kind = PendingTask.ROLE_TRADING_REVIEW_V1;
                body = strict(WorldRoleSession.ReviewDraft.class, args);
            }
            case RoleTradingView.TOOL_TARGET_HALT -> {
                kind = PendingTask.ROLE_TRADING_HALT_V1;
                body = strict(WorldRoleSession.HaltDraft.class, args);
            }
            case RoleTradingView.TOOL_TARGET_CONFIRM -> {
                kind = PendingTask.ROLE_TRADING_CONFIRM_V1;
                body = strict(WorldRoleSession.ConfirmDraft.class, args);
            }
            case RoleTradingView.TOOL_TARGET_CANCEL -> {
                kind = PendingTask.ROLE_TRADING_CANCEL_V1;
                body = strict(WorldRoleSession.CancelTargetDraft.class, args);
            }
            case RoleTradingView.TOOL_CHILD_SUBMIT -> {
                kind = PendingTask.ROLE_TRADING_CHILD_V1;
                body = strict(WorldRoleSession.ChildDraft.class, args);
            }
            case RoleTradingView.TOOL_CHILD_CANCEL -> {
                kind = PendingTask.ROLE_TRADING_CHILD_CANCEL_V1;
                JsonNode node = args.deepCopy();
                if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) {
                    throw new IOException("工具参数必须是对象");
                }
                if (object.has("personPartyId")) {
                    throw new IOException("取消子单的人物只能由观察绑定提供");
                }
                object.put("personPartyId", binding.personPartyId());
                body = strict(WorldRoleSession.ChildRef.class, object);
            }
            default -> throw new IOException("未知业务工具：" + tool);
        }
        long dueMillis = DecisionModel.actionDueMillis(model, recorded.actionId());
        String taskId = forcedTaskId != null ? forcedTaskId : freeTaskId(
                DecisionRun.actionTaskId(recorded.actionId()));
        String commandId = DecisionRun.actionCommandId(recorded.actionId());
        RoleActionPayload payload = new RoleActionPayload(commandId, recorded.actionId(),
                recorded.actionId(), binding.personPartyId(), dueMillis,
                new TreeMap<>(binding.appointments()), kind, mapper.writeValueAsString(body));
        return new PlacedAction(recorded.actionId(), tool, kind, taskId, commandId, dueMillis,
                mapper.writeValueAsString(payload));
    }

    /** 从回执外壳取 args 对象；外壳已由纯核验证。 */
    private JsonNode replyArgs(String replyBody) throws IOException {
        JsonNode root = decisionMapper.readerFor(JsonNode.class).readValue(replyBody);
        JsonNode args = root == null ? null : root.get("args");
        if (args == null || !args.isObject()) {
            throw new IOException("工具参数必须是对象");
        }
        return args;
    }

    /** 严格反序列化：未知字段、尾随内容、空值与浮点整数一律失败。 */
    private <T> T strict(Class<T> type, JsonNode node) throws IOException {
        T value = decisionMapper.readerFor(type).readValue(node);
        if (value == null) {
            throw new IOException("工具参数为空");
        }
        return value;
    }

    /** 确定性找空闲任务标识：基名被占时追加后缀，仍保留原动作标识。 */
    private String freeTaskId(String base) {
        String candidate = base;
        int suffix = 1;
        while (takenTaskId(candidate)) {
            candidate = base + "-" + (++suffix);
        }
        return candidate;
    }

    private boolean takenTaskId(String taskId) {
        if (state.usedTaskIds().contains(taskId)) {
            return true;
        }
        for (PendingTask task : state.pending()) {
            if (task.taskId().equals(taskId)) {
                return true;
            }
        }
        return false;
    }

    private static String shortReason(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message.length() <= 120 ? message : message.substring(0, 120);
    }

    /**
     * 决定子集恢复期校验：配置冻结、历史前缀、代次单调、归档前缀、动作任务与决定原文双向对应，
     * 以及新决定输入能由打开前的已确认快照逐字重生成。
     */
    private void validateDecisions(WorldState candidate, WorldState previous) throws IOException {
        if (expectedSchema() < STATE_SCHEMA_DECISIONS) {
            if (candidate.decisions() != null) {
                throw new IOException("旧档案不得包含决定子集状态");
            }
            return;
        }
        DecisionRun run = candidate.decisions();
        if (run == null) {
            throw new IOException("决定档案缺少决定子集状态");
        }
        if (!manifest.runId().equals(run.state().runId())) {
            throw new IOException("决定运行标识不匹配");
        }
        if (!run.state().config().equals(decisionConfig)) {
            throw new IOException("决定配置与冻结开局不一致");
        }
        if (previous != null && previous.decisions() != null) {
            DecisionRun before = previous.decisions();
            if (run.state().recoveryGeneration() < before.state().recoveryGeneration()) {
                throw new IOException("恢复代次倒退");
            }
            if (!isArchivePrefix(before.archives(), run.archives())) {
                throw new IOException("旧回执归档被改写");
            }
            for (DecisionModel.Decision older : before.state().decisions()) {
                DecisionModel.Decision now = DecisionModel.decision(run.state(),
                        older.actionId()).orElse(null);
                if (now == null) {
                    throw new IOException("历史决定丢失：" + older.actionId());
                }
                if (!sameFrozenPrefix(older, now)) {
                    throw new IOException("历史决定被改写：" + older.actionId());
                }
            }
            for (Map.Entry<String, DecisionRun.Binding> entry : before.bindings().entrySet()) {
                DecisionRun.Binding now = run.bindings().get(entry.getKey());
                if (!entry.getValue().equals(now)) {
                    throw new IOException("历史观察绑定被改写：" + entry.getKey());
                }
            }
            for (Map.Entry<String, DecisionRun.ActionRef> entry : before.actionRefs().entrySet()) {
                if (!entry.getValue().equals(run.actionRefs().get(entry.getKey()))) {
                    throw new IOException("历史动作引用被改写：" + entry.getKey());
                }
            }
        }
        Set<String> previousIds = new HashSet<>();
        if (previous != null && previous.decisions() != null) {
            for (DecisionModel.Decision older : previous.decisions().state().decisions()) {
                previousIds.add(older.actionId());
            }
        }
        Set<String> attemptIds = new HashSet<>();
        Set<String> decisionIds = new HashSet<>();
        for (DecisionModel.Decision decision : run.state().decisions()) {
            decisionIds.add(decision.actionId());
            DecisionRun.Binding binding = run.binding(decision.actionId());
            if (binding == null) {
                throw new IOException("决定缺少观察绑定：" + decision.actionId());
            }
            if (!binding.personPartyId().equals(decision.personPartyId())
                    || !binding.wakeReason().equals(decision.wakeReason())) {
                throw new IOException("观察绑定与决定身份不一致：" + decision.actionId());
            }
            for (DecisionModel.Attempt attempt : decision.attempts()) {
                if (!attemptIds.add(attempt.attemptId())) {
                    throw new IOException("尝试标识重复：" + attempt.attemptId());
                }
            }
            if (decision.status() == DecisionModel.Status.RECORDED
                    && decision.outcome() == DecisionModel.Outcome.INTENT
                    && !run.actionRefs().containsKey(decision.actionId())) {
                throw new IOException("已记录意图缺少动作引用：" + decision.actionId());
            }
            if (decision.outcome() != DecisionModel.Outcome.INTENT
                    && run.actionRefs().containsKey(decision.actionId())) {
                throw new IOException("非业务意图不能引用动作任务：" + decision.actionId());
            }
            if (!previousIds.contains(decision.actionId())) {
                validateReopenedInput(previous, run, decision, binding);
            }
        }
        if (!run.bindings().keySet().equals(decisionIds)) {
            throw new IOException("观察绑定必须与决定逐项对应");
        }
        for (DecisionRun.ActionRef ref : run.actionRefs().values()) {
            if (!candidate.usedTaskIds().contains(ref.taskId())) {
                throw new IOException("动作任务标识未登记到已用任务：" + ref.taskId());
            }
            PendingTask task = null;
            for (PendingTask pendingTask : candidate.pending()) {
                if (pendingTask.taskId().equals(ref.taskId())) {
                    task = pendingTask;
                }
            }
            DecisionModel.Decision decision = DecisionModel.decision(run.state(),
                    ref.actionId()).orElseThrow(() -> new IOException(
                            "动作引用指向不存在的决定：" + ref.actionId()));
            PlacedAction replanned = planAction(run.binding(ref.actionId()), decision,
                    run.state(), ref.taskId());
            if (!ref.tool().equals(replanned.tool()) || ref.dueMillis() != replanned.dueMillis()
                    || !replanned.payloadJson().equals(candidate.commandContents().get(replanned.commandId()))
                    || !("SCHEDULED:" + ref.actionId()).equals(candidate.commandOutcomes().get(replanned.commandId()))) {
                throw new IOException("动作引用或原命令与决定原文不一致：" + ref.actionId());
            }
            RoleTradingRun.Report report = candidate.trading().report(ref.taskId());
            if (task != null) {
                if (report != null || !replanned.kind().equals(task.kind())
                        || replanned.dueMillis() != task.dueTimeMillis()
                        || !replanned.payloadJson().equals(task.payload())) {
                    throw new IOException("动作任务内容与决定原文不一致：" + task.taskId());
                }
            } else if (report == null || !report.personPartyId().equals(decision.personPartyId())
                    || !report.clientCommandId().equals(ref.actionId())
                    || !report.clientTaskId().equals(ref.actionId())
                    || !report.kind().equals(replanned.kind())
                    || report.timeMillis() != ref.dueMillis()) {
                throw new IOException("已记录意图缺少待办或准确完成回报：" + ref.actionId());
            }
        }
    }

    /** 逐字重生成新决定的输入：只能来自打开前的已确认快照。 */
    private void validateReopenedInput(WorldState previous, DecisionRun run,
                                       DecisionModel.Decision decision,
                                       DecisionRun.Binding binding) throws IOException {
        if (previous == null) {
            throw new IOException("新决定缺少可核对的历史快照：" + decision.actionId());
        }
        if (decision.cutoffPosition() != previous.position()
                || decision.cutoffTimeMillis() != previous.timeMillis()) {
            throw new IOException("决定观察截止与历史快照不一致：" + decision.actionId());
        }
        WorldRoleSession session = openRoleSessionAt(previous, decision.personPartyId(),
                decision.cutoffTimeMillis());
        if (!session.appointments().equals(binding.appointments())) {
            throw new IOException("观察任职无法由历史快照重生成：" + decision.actionId());
        }
        RoleTradingView view = roleTradingViewAt(previous, session);
        List<String> tools = allowedToolsOf(view);
        if (!tools.equals(decision.allowedTools())) {
            throw new IOException("允许工具与历史投影不一致：" + decision.actionId());
        }
        String regenerated = DecisionRun.inputText(view, decision.wakeReason(),
                decision.cutoffTimeMillis(), pendingActionsAt(previous, run,
                        decision.personPartyId()), tools, decisionConfig);
        if (!regenerated.equals(decision.inputText())) {
            throw new IOException("决定输入无法由历史快照重生成：" + decision.actionId());
        }
    }

    /** 历史前缀：身份、输入、屏障与已结算尝试逐项不得改写。 */
    private static boolean sameFrozenPrefix(DecisionModel.Decision older,
                                            DecisionModel.Decision now) {
        if (!older.personPartyId().equals(now.personPartyId())
                || older.wakeReason().equals(now.wakeReason()) == false
                || older.decisionAtMillis() != now.decisionAtMillis()
                || older.barrierAtMillis() != now.barrierAtMillis()
                || older.cutoffPosition() != now.cutoffPosition()
                || older.cutoffTimeMillis() != now.cutoffTimeMillis()
                || !older.inputText().equals(now.inputText())
                || !older.inputDigest().equals(now.inputDigest())
                || !older.allowedTools().equals(now.allowedTools())) {
            return false;
        }
        if (now.attempts().size() < older.attempts().size()) {
            return false;
        }
        for (int index = 0; index < older.attempts().size(); index++) {
            DecisionModel.Attempt before = older.attempts().get(index);
            DecisionModel.Attempt after = now.attempts().get(index);
            if (!before.attemptId().equals(after.attemptId())
                    || before.attemptNo() != after.attemptNo()
                    || !before.inputDigest().equals(after.inputDigest())) {
                return false;
            }
            if (before.settledDone() && !before.equals(after)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isArchivePrefix(List<DecisionRun.Archive> older,
                                           List<DecisionRun.Archive> now) {
        if (now.size() < older.size()) {
            return false;
        }
        for (int index = 0; index < older.size(); index++) {
            if (!older.get(index).equals(now.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** 关闭日志；已发布快照仍可读取。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            journal.close();
        } catch (IOException ignored) {
            // 关闭失败不影响已发布快照；日志锁由进程退出回收
        }
    }

    // ------------------------------------------------------------------
    // 内部：均要求持有本对象监视器（synchronized 方法内调用）
    // ------------------------------------------------------------------

    private void completeTransfer(PendingTask task) throws IOException {
        TransferPayload payload = mapper.readValue(task.payload(), TransferPayload.class);
        CashLedger.TransferOutcome outcome = transferOutcome(payload);

        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        if (outcome.accepted()) {
            events.add(new WorldEvent("cash:" + task.taskId(), "CASH_TRANSFERRED_V1",
                    mapper.writeValueAsString(payload)));
        }

        String noticeTaskId = freeNoticeTaskId(task.taskId(), pending, used);
        String noticeId = "notice:" + task.taskId();
        PendingTask noticeTask = new PendingTask(noticeTaskId, payload.noticeDueMillis(),
                WorldScheduler.Phase.DELIVERY, PendingTask.TRANSFER_NOTICE_V1,
                mapper.writeValueAsString(new NoticePayload(noticeId, task.taskId(),
                        payload.commandId(), payload.fromId(), payload.toId(), payload.amount(),
                        outcome.accepted(), outcome.reason())));
        pending.add(noticeTask);
        used.add(noticeTaskId);
        events.add(new WorldEvent("task-scheduled:" + noticeTaskId, "TASK_SCHEDULED_V1",
                mapper.writeValueAsString(noticeTask)));

        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(),
                outcome.accounts(), pending, state.delivered(), used, state.commandContents(),
                state.commandOutcomes(), state.securities(), state.exchange());
        commitAndApply("task:" + task.taskId(), task.payload(), outcome.reason(), events, candidate);
    }

    private void deliverNotice(PendingTask task) throws IOException {
        NoticePayload payload = mapper.readValue(task.payload(), NoticePayload.class);
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<Notice> delivered = new ArrayList<>(state.delivered());
        delivered.add(new Notice(payload.noticeId(), payload.sourceTaskId(), payload.commandId(),
                payload.accepted(), payload.reason(), payload.fromId(), payload.toId(),
                payload.amount(), task.dueTimeMillis()));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), state.accounts(),
                pending, delivered, used, state.commandContents(), state.commandOutcomes(),
                state.securities(), state.exchange());
        commitAndApply("task:" + task.taskId(), task.payload(), "DELIVERED",
                List.of(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                                task.payload()),
                        new WorldEvent("notice:" + payload.noticeId(), "NOTICE_DELIVERED_V1",
                                task.payload())),
                candidate);
    }

    /**
     * 预留卖出：到期按最新持仓复核；业务拒绝也保存报告，不改变资产。
     */
    private void completeReserve(PendingTask task) throws IOException {
        ReservePayload payload = mapper.readValue(task.payload(), ReservePayload.class);
        long now = task.dueTimeMillis();
        CashLedger.CashAccount account = state.accounts().get(payload.accountId());
        String reason;
        boolean accepted = false;
        SecuritiesLedger.State candidate = state.securities();
        if (account == null) {
            reason = REASON_UNKNOWN_ACCOUNT;
        } else if (!account.ownerId().equals(payload.actorOwnerId())) {
            reason = REASON_NOT_AUTHORIZED;
        } else if (registry.security(payload.symbol()) == null) {
            reason = REASON_UNKNOWN_ACCOUNT;
        } else {
            SecuritiesLedger.Outcome outcome = SecuritiesLedger.reserve(state.securities(),
                    payload.reservationId(), payload.accountId(), payload.symbol(),
                    payload.quantity(), SecuritiesLedger.day(now));
            accepted = outcome.accepted();
            reason = outcome.reason();
            candidate = outcome.state();
        }
        ActionReport report = new ActionReport(task.taskId(), payload.commandId(),
                PendingTask.SELL_RESERVE_V1, accepted, reason, now, state.position() + 1);
        finishSecuritiesTask(task, state.accounts(), candidate.withReport(report), report,
                List.of());
    }

    /**
     * 撤销预留：只释放未成交冻结；未知或越权是业务拒绝。
     */
    private void completeCancel(PendingTask task) throws IOException {
        CancelPayload payload = mapper.readValue(task.payload(), CancelPayload.class);
        long now = task.dueTimeMillis();
        SellReservation reservation = SecuritiesLedger.reservation(state.securities(),
                payload.reservationId());
        String reason;
        boolean accepted = false;
        SecuritiesLedger.State candidate = state.securities();
        if (reservation == null) {
            reason = SecuritiesLedger.REASON_UNKNOWN_RESERVATION;
        } else {
            CashLedger.CashAccount account = state.accounts().get(reservation.accountId());
            if (account == null) {
                reason = REASON_UNKNOWN_ACCOUNT;
            } else if (!account.ownerId().equals(payload.actorOwnerId())) {
                reason = REASON_NOT_AUTHORIZED;
            } else {
                SecuritiesLedger.Outcome outcome = SecuritiesLedger.cancel(state.securities(),
                        payload.reservationId());
                accepted = outcome.accepted();
                reason = outcome.reason();
                candidate = outcome.state();
            }
        }
        ActionReport report = new ActionReport(task.taskId(), payload.commandId(),
                PendingTask.SELL_CANCEL_V1, accepted, reason, now, state.position() + 1);
        finishSecuritiesTask(task, state.accounts(), candidate.withReport(report), report,
                List.of());
    }

    /**
     * 同批清算成交：现金、股份、预留、报告与全量状态必须在同一批次持久确认后才发布。
     *
     * <p>同一受益主体的两个账户拒绝成交（不把同一组织的不同产品合并成一个主体）；
     * 买方现金不足、预留不足或预留已结束时都不留下单边效果。</p>
     */
    private void completeFill(PendingTask task) throws IOException {
        FillPayload payload = mapper.readValue(task.payload(), FillPayload.class);
        long now = task.dueTimeMillis();
        SellReservation reservation = SecuritiesLedger.reservation(state.securities(),
                payload.reservationId());
        String reason;
        boolean accepted = false;
        SecuritiesLedger.State securitiesCandidate = state.securities();
        Map<String, CashLedger.CashAccount> accountsCandidate = state.accounts();
        List<WorldEvent> effects = new ArrayList<>();
        if (reservation == null) {
            reason = SecuritiesLedger.REASON_UNKNOWN_RESERVATION;
        } else {
            CashLedger.CashAccount seller = state.accounts().get(reservation.accountId());
            CashLedger.CashAccount buyer = state.accounts().get(payload.buyerAccountId());
            if (seller == null || buyer == null) {
                reason = REASON_UNKNOWN_ACCOUNT;
            } else if (seller.ownerId().equals(buyer.ownerId())) {
                reason = REASON_SELF_TRADE_PREVENTED;
            } else if (registry.security(reservation.symbol()) == null) {
                reason = REASON_UNKNOWN_ACCOUNT;
            } else {
                SecuritiesLedger.FillOutcome filled = SecuritiesLedger.fill(state.securities(),
                        task.taskId(), payload.reservationId(), payload.buyerAccountId(),
                        payload.price(), payload.quantity(), now);
                if (!filled.accepted()) {
                    reason = filled.reason();
                } else {
                    CashLedger.TransferOutcome cash = CashLedger.computeTransfer(
                            state.accounts(), buyer.ownerId(), payload.buyerAccountId(),
                            reservation.accountId(), filled.amount());
                    if (!cash.accepted()) {
                        reason = cash.reason();
                    } else {
                        accepted = true;
                        reason = filled.reason();
                        securitiesCandidate = filled.state();
                        accountsCandidate = cash.accounts();
                        Map<String, Object> effect = new LinkedHashMap<>();
                        effect.put("reservationId", payload.reservationId());
                        effect.put("sellerAccountId", reservation.accountId());
                        effect.put("buyerAccountId", payload.buyerAccountId());
                        effect.put("price", payload.price());
                        effect.put("quantity", payload.quantity());
                        effect.put("amount", filled.amount());
                        effects.add(new WorldEvent("cash:" + task.taskId(),
                                "SECURITIES_CASH_TRANSFERRED_V1", mapper.writeValueAsString(effect)));
                        effects.add(new WorldEvent("securities:" + task.taskId(),
                                "SECURITIES_FILLED_V1", mapper.writeValueAsString(effect)));
                    }
                }
            }
        }
        ActionReport report = new ActionReport(task.taskId(), payload.commandId(),
                PendingTask.FILL_SETTLE_V1, accepted, reason, now, state.position() + 1);
        finishSecuritiesTask(task, accountsCandidate, securitiesCandidate.withReport(report),
                report, effects);
    }

    /**
     * 世界订单到期：按最新状态复核并撮合。
     *
     * <p>订单表、逐笔成交、现金、持仓批次、预留、订单结果与完整状态在同一个批次持久确认后
     * 才发布；业务拒绝（现金/批次不足、收盘）也保存结果，但不分配订单号、不改变资产。</p>
     */
    private void completeOrder(PendingTask task) throws IOException {
        OrderPayload payload = mapper.readValue(task.payload(), OrderPayload.class);
        long now = task.dueTimeMillis();
        if (manufacturingConfig != null) {
            CashLedger.CashAccount account = state.accounts().get(payload.accountId());
            if (account != null && account.ownerId().equals(
                    manufacturingConfig.accounting().companyOwnerId())) {
                OrderReport report = new OrderReport(task.taskId(), payload.commandId(),
                        task.kind(), false, REASON_COMPANY_TRADING_FORBIDDEN, null, List.of(),
                        List.of(), now, state.position() + 1);
                finishExchangeTask(task, state.accounts(), state.securities(), state.exchange(),
                        report);
                return;
            }
        }
        if (registry.security(payload.symbol()) == null) {
            OrderReport report = new OrderReport(task.taskId(), payload.commandId(),
                    task.kind(), false, WorldExchange.REASON_UNKNOWN_SYMBOL, null, List.of(),
                    List.of(), now, state.position() + 1);
            finishExchangeTask(task, state.accounts(), state.securities(), state.exchange(),
                    report);
            return;
        }
        WorldExchange.Outcome outcome = WorldExchange.submit(state.exchange(), state.accounts(),
                state.securities(), task.taskId(), payload.commandId(), payload.actorOwnerId(),
                payload.accountId(), payload.symbol(), payload.side(), payload.type(),
                payload.price(), payload.quantity(), now);
        OrderReport report = new OrderReport(task.taskId(), payload.commandId(), task.kind(),
                outcome.accepted(), outcome.reason(), outcome.orderId(), outcome.tradeIds(),
                outcome.cancelledOrderIds(), now, state.position() + 1);
        finishExchangeTask(task, outcome.accounts(), outcome.securities(),
                outcome.exchange().withReport(report), report);
    }

    /** 到期撤单：未知/非属主/终态是业务拒绝，不改变挂单与冻结。 */
    private void completeCancelOrder(PendingTask task) throws IOException {
        CancelOrderPayload payload = mapper.readValue(task.payload(), CancelOrderPayload.class);
        long now = task.dueTimeMillis();
        WorldExchange.Outcome outcome = WorldExchange.cancel(state.exchange(), state.accounts(),
                state.securities(), payload.actorOwnerId(), payload.orderId(), now);
        OrderReport report = new OrderReport(task.taskId(), payload.commandId(), task.kind(),
                outcome.accepted(), outcome.reason(), outcome.orderId(), outcome.tradeIds(),
                outcome.cancelledOrderIds(), now, state.position() + 1);
        finishExchangeTask(task, outcome.accounts(), outcome.securities(),
                outcome.exchange().withReport(report), report);
    }

    /** 到期收盘：撤销全簿、释放预留与派生冻结；历史成交保留。 */
    private void completeCloseMarket(PendingTask task) throws IOException {
        CloseMarketPayload payload = mapper.readValue(task.payload(), CloseMarketPayload.class);
        long now = task.dueTimeMillis();
        WorldExchange.Outcome outcome = WorldExchange.close(state.exchange(), state.accounts(),
                state.securities(), now);
        OrderReport report = new OrderReport(task.taskId(), payload.commandId(), task.kind(),
                outcome.accepted(), outcome.reason(), outcome.orderId(), outcome.tradeIds(),
                outcome.cancelledOrderIds(), now, state.position() + 1);
        finishExchangeTask(task, outcome.accounts(), outcome.securities(),
                outcome.exchange().withReport(report), report);
    }

    /** 世界订单任务的统一收尾：完成待办、写入成交与结果、保存全量状态并持久确认后发布。 */
    private void finishExchangeTask(PendingTask task,
                                    Map<String, CashLedger.CashAccount> accounts,
                                    SecuritiesLedger.State securities,
                                    WorldExchange.State exchange, OrderReport report)
            throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        for (Long tradeId : report.tradeIds()) {
            WorldExchange.Trade trade = exchange.trades().stream()
                    .filter(item -> item.tradeId() == tradeId).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "订单结果引用了未登记成交：" + tradeId));
            events.add(new WorldEvent("exchange-trade:" + tradeId, "EXCHANGE_TRADE_V1",
                    mapper.writeValueAsString(trade)));
        }
        events.add(new WorldEvent("exchange-report:" + task.taskId(),
                "EXCHANGE_ORDER_REPORT_V1", mapper.writeValueAsString(report)));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), accounts, pending,
                state.delivered(), used, state.commandContents(), state.commandOutcomes(),
                securities, exchange);
        commitAndApply("task:" + task.taskId(), task.payload(), report.reason(), events, candidate);
    }

    /**
     * 清算夹具任务的统一收尾：完成待办、追加报告、保存全量状态并在持久确认后发布。
     */
    private void finishSecuritiesTask(PendingTask task,
                                      Map<String, CashLedger.CashAccount> accounts,
                                      SecuritiesLedger.State securities,
                                      ActionReport report, List<WorldEvent> effects)
            throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        events.addAll(effects);
        events.add(new WorldEvent("securities-report:" + task.taskId(), "SECURITIES_REPORT_V1",
                mapper.writeValueAsString(report)));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), accounts, pending,
                state.delivered(), used, state.commandContents(), state.commandOutcomes(),
                securities, state.exchange());
        commitAndApply("task:" + task.taskId(), task.payload(), report.reason(), events, candidate);
    }

    // ------------------------------------------------------------------
    // 制造经营：到期执行、自动子任务与强制关期
    // ------------------------------------------------------------------

    /** 自动子任务的排程描述；标识在提交批次内按全世界已用任务表空闲选定。 */
    private record ManufacturingChild(String kind, WorldScheduler.Phase phase, String stem,
                                      long dueTimeMillis, String businessId, long period) {
    }

    /**
     * 外部制造命令到期：按 {@code task.dueTimeMillis()} 调用纯核转换。
     *
     * <p>可用现金一律取同一快照的 {@link WorldExchange#freeCash}（含公司经营账户与客户回款），
     * 不能花掉挂单冻结。成功时自动子任务与父效果同批保存；失败时也保留候选台账
     * （{@code FAILED_NO_CASH}/{@code OVERDUE}），不丢元数据。</p>
     */
    private void completeManufacturing(PendingTask task) throws IOException {
        ManufacturingPayload payload = mapper.readValue(task.payload(), ManufacturingPayload.class);
        long now = task.dueTimeMillis();
        ManufacturingRun run = requireManufacturingRun();
        ManufacturingModel.State ledger = run.ledger();
        String owner = ledger.opening().companyOwnerId();
        Map<String, CashLedger.CashAccount> accounts = state.accounts();
        BigDecimal companyFree = availableCash(ledger.opening().companyAccountId(), accounts);
        if (charter != null
                && (PendingTask.MANUFACTURING_PURCHASE_ORDER_V1.equals(task.kind())
                || PendingTask.MANUFACTURING_PRODUCTION_V1.equals(task.kind()))) {
            BoardModel.BudgetCategory category = PendingTask.MANUFACTURING_PURCHASE_ORDER_V1
                    .equals(task.kind()) ? BoardModel.BudgetCategory.PROCUREMENT
                    : BoardModel.BudgetCategory.PRODUCTION_LABOR;
            long period = now / ManufacturingModel.PERIOD_MILLIS;
            if (requireGovernanceRun().board().budget(period, category) == null) {
                // 没有已实施的有效上限：在跑纯候选之前就明确拒绝，保证无库存/现金也能报预算原因
                ManufacturingModel.Outcome rejected = new ManufacturingModel.Outcome(false,
                        REASON_BUDGET_NOT_SET, ledger, accounts);
                finishManufacturingTask(task, payload.commandId(), rejected, List.of(),
                        payload.businessId(), ZERO, List.of(), null);
                return;
            }
        }
        ManufacturingModel.Outcome outcome;
        ManufacturingChild child = null;
        BoardModel.State boardCandidate = null;
        BigDecimal amount = ZERO;
        String from = ledger.opening().companyAccountId();
        String to = ledger.opening().supplierAccountId();
        String reportBusiness = payload.businessId();
        switch (task.kind()) {
            case PendingTask.MANUFACTURING_PURCHASE_ORDER_V1 -> {
                outcome = ManufacturingModel.placePurchaseOrder(ledger, now, owner,
                        payload.businessId(), payload.quantity(), accounts);
                if (outcome.accepted() && charter != null) {
                    BoardModel.Outcome reserved = reservePurchaseBudget(now, payload.businessId(),
                            payload.quantity());
                    if (!reserved.accepted()) {
                        // 预算不成立：整体丢弃纯核候选，不增 PO/usedId/child/现金
                        outcome = new ManufacturingModel.Outcome(false, reserved.reason(), ledger,
                                accounts);
                    } else {
                        boardCandidate = reserved.state();
                    }
                }
                if (outcome.accepted()) {
                    ManufacturingModel.PurchaseOrder order =
                            outcome.state().purchaseOrders().get(payload.businessId());
                    child = new ManufacturingChild(PendingTask.MANUFACTURING_GOODS_RECEIPT_V1,
                            WorldScheduler.Phase.COMPLETION, "mfg-receipt:", order.arrivalAt(),
                            payload.businessId(), -1L);
                }
            }
            case PendingTask.MANUFACTURING_PRODUCTION_V1 -> {
                outcome = ManufacturingModel.startProduction(ledger, now, owner,
                        payload.businessId(), payload.quantity(), companyFree, accounts);
                if (outcome.accepted() && charter != null) {
                    BoardModel.Outcome reserved = reserveLaborBudget(now, payload.businessId(),
                            payload.quantity());
                    if (!reserved.accepted()) {
                        outcome = new ManufacturingModel.Outcome(false, reserved.reason(), ledger,
                                accounts);
                    } else {
                        boardCandidate = reserved.state();
                    }
                }
                if (outcome.accepted()) {
                    ManufacturingModel.ProductionBatch batch =
                            outcome.state().productionBatches().get(payload.businessId());
                    amount = batch.laborCost();
                    to = ledger.opening().opsAccountId();
                    child = new ManufacturingChild(PendingTask.MANUFACTURING_BATCH_COMPLETION_V1,
                            WorldScheduler.Phase.COMPLETION, "mfg-finish:", batch.completesAt(),
                            payload.businessId(), -1L);
                }
            }
            case PendingTask.MANUFACTURING_SALES_CONTRACT_V1 -> outcome =
                    ManufacturingModel.signContract(ledger, now, owner, payload.businessId(),
                            payload.quantity(), payload.unitPrice(), payload.promisedDeliveryAt(),
                            accounts);
            case PendingTask.MANUFACTURING_DELIVERY_V1 -> {
                outcome = ManufacturingModel.deliver(ledger, now, owner, payload.businessId(),
                        payload.quantity(), payload.resultId(), accounts);
                reportBusiness = payload.resultId();
                if (outcome.accepted()) {
                    ManufacturingModel.Receivable receivable =
                            outcome.state().receivables().get(payload.resultId());
                    child = new ManufacturingChild(PendingTask.MANUFACTURING_AUTO_COLLECTION_V1,
                            WorldScheduler.Phase.COMPLETION, "mfg-collect:", receivable.dueAt(),
                            payload.resultId(), -1L);
                }
            }
            case PendingTask.MANUFACTURING_COLLECT_RECEIVABLE_V1 -> {
                amount = outstanding(ledger, payload.businessId());
                from = ledger.opening().customerAccountId();
                to = ledger.opening().companyAccountId();
                outcome = ManufacturingModel.collectReceivable(ledger, now, owner,
                        payload.businessId(), customerFree(ledger, accounts), accounts);
            }
            case PendingTask.MANUFACTURING_PAY_EXPENSE_BILL_V1 -> {
                ManufacturingModel.ExpenseBill bill = billOfPeriod(ledger, payload.period());
                reportBusiness = bill == null ? "" : bill.billId();
                amount = bill == null ? ZERO : bill.outstanding();
                to = ledger.opening().opsAccountId();
                outcome = ManufacturingModel.payExpenseBill(ledger, now, owner, payload.period(),
                        companyFree, accounts);
            }
            default -> throw new IllegalStateException("未启用的制造任务种类：" + task.kind());
        }
        List<WorldEvent> effects = outcome.accepted() && amount.signum() > 0
                ? cashTransferred(task, from, to, amount, reportBusiness) : List.of();
        if (!outcome.accepted()) {
            amount = ZERO;
        }
        finishManufacturingTask(task, payload.commandId(), outcome,
                child == null ? List.of() : List.of(child), reportBusiness, amount, effects,
                boardCandidate == null ? null
                        : requireGovernanceRun().withBoard(boardCandidate));
    }

    /**
     * 自动子任务到期：到货、完工、到期回款。参数只来自父动作已确认的纯核记录。
     */
    private void completeManufacturingChild(PendingTask task) throws IOException {
        ManufacturingChildPayload payload = mapper.readValue(task.payload(),
                ManufacturingChildPayload.class);
        long now = task.dueTimeMillis();
        ManufacturingRun run = requireManufacturingRun();
        ManufacturingModel.State ledger = run.ledger();
        String owner = ledger.opening().companyOwnerId();
        Map<String, CashLedger.CashAccount> accounts = state.accounts();
        ManufacturingModel.Outcome outcome;
        BigDecimal amount = ZERO;
        BoardModel.State boardCandidate = null;
        String from;
        String to;
        switch (task.kind()) {
            case PendingTask.MANUFACTURING_GOODS_RECEIPT_V1 -> {
                ManufacturingModel.PurchaseOrder order =
                        ledger.purchaseOrders().get(payload.businessId());
                BigDecimal due = order == null ? BigDecimal.ZERO
                        : CashLedger.money(order.unitPrice().multiply(
                                BigDecimal.valueOf(order.quantity())));
                outcome = ManufacturingModel.receivePurchaseOrder(ledger, now, owner,
                        payload.businessId(),
                        availableCash(ledger.opening().companyAccountId(), accounts), accounts);
                if (outcome.accepted() && charter != null) {
                    BoardModel.Outcome spent = BoardModel.spendBudget(requireGovernanceRun().board(),
                            now, "po:" + payload.businessId(), due);
                    if (!spent.accepted()) {
                        throw new IllegalStateException("到货付款成功但预算承诺无法转已花："
                                + payload.businessId() + " / " + spent.reason());
                    }
                    boardCandidate = spent.state();
                } else if (!outcome.accepted() && charter != null
                        && outcome.state().purchaseOrders().get(payload.businessId()) != null
                        && outcome.state().purchaseOrders().get(payload.businessId()).status()
                        == ManufacturingModel.PurchaseOrderStatus.FAILED_NO_CASH) {
                    boardCandidate = releasePurchaseBudget(now, payload.businessId());
                }
                amount = outcome.accepted() ? due : ZERO;
                from = ledger.opening().companyAccountId();
                to = ledger.opening().supplierAccountId();
            }
            case PendingTask.MANUFACTURING_BATCH_COMPLETION_V1 -> {
                outcome = ManufacturingModel.completeProduction(ledger, now, owner,
                        payload.businessId(), accounts);
                from = ledger.opening().companyAccountId();
                to = ledger.opening().opsAccountId();
            }
            case PendingTask.MANUFACTURING_AUTO_COLLECTION_V1 -> {
                amount = outstanding(ledger, payload.businessId());
                outcome = ManufacturingModel.collectReceivable(ledger, now, owner,
                        payload.businessId(), customerFree(ledger, accounts), accounts);
                from = ledger.opening().customerAccountId();
                to = ledger.opening().companyAccountId();
            }
            default -> throw new IllegalStateException("未启用的制造子任务：" + task.kind());
        }
        List<WorldEvent> effects = outcome.accepted() && amount.signum() > 0
                ? cashTransferred(task, from, to, amount, payload.businessId()) : List.of();
        if (!outcome.accepted()) {
            amount = ZERO;
        }
        finishManufacturingTask(task, payload.commandId(), outcome, List.of(),
                payload.businessId(), amount, effects,
                boardCandidate == null ? null
                        : requireGovernanceRun().withBoard(boardCandidate));
    }

    /**
     * 系统强制关期：期末确认费用/部分付款/应付/折旧，并在同一批排下一期间任务。
     *
     * <p>没有外部关期入口；账单标识按纯核已用标识找空闲，不会因用户提前占名而卡死。
     * 当前档案的合法关期应成功；预期外拒绝会触发关期链校验失败，保留旧快照和待办并暂停，
     * 不发布缺失下一期任务的候选。</p>
     */
    private void completePeriodClose(PendingTask task) throws IOException {
        ManufacturingChildPayload payload = mapper.readValue(task.payload(),
                ManufacturingChildPayload.class);
        long now = task.dueTimeMillis();
        ManufacturingRun run = requireManufacturingRun();
        ManufacturingModel.State ledger = run.ledger();
        String owner = ledger.opening().companyOwnerId();
        Map<String, CashLedger.CashAccount> accounts = state.accounts();
        String billId = freeBillId(ledger, payload.period());
        ManufacturingModel.Outcome outcome = ManufacturingModel.closePeriod(ledger, now, owner,
                payload.period(), billId, availableCash(ledger.opening().companyAccountId(), accounts),
                accounts);
        ManufacturingModel.ExpenseBill bill = outcome.accepted()
                ? outcome.state().expenseBills().get(billId) : null;
        BigDecimal amount = bill == null ? ZERO : bill.paid();
        List<WorldEvent> effects = outcome.accepted() && amount.signum() > 0
                ? cashTransferred(task, ledger.opening().companyAccountId(),
                        ledger.opening().opsAccountId(), amount, billId) : List.of();
        List<ManufacturingChild> children = new ArrayList<>();
        BoardRun boardCandidate = null;
        if (outcome.accepted()) {
            long period = outcome.state().closedPeriods();
            long due;
            try {
                due = Math.multiplyExact(period + 1, ManufacturingModel.PERIOD_MILLIS);
            } catch (ArithmeticException overflow) {
                due = -1L;
            }
            if (due > now) {
                children.add(new ManufacturingChild(PendingTask.MANUFACTURING_PERIOD_CLOSE_V1,
                        WorldScheduler.Phase.RULES, "mfg-close:", due, String.valueOf(period),
                        period));
            }
            if (charter != null) {
                boardCandidate = capturePeriodReview(payload.period(), now,
                        state.position() + 1, outcome.state(), outcome.accounts(), children);
            }
        }
        finishManufacturingTask(task, SYSTEM_COMMAND_PREFIX + "close-period:" + payload.period(),
                outcome, children, bill == null ? "" : billId, amount, effects, boardCandidate);
    }

    /**
     * 强制关期同批捕捉本截止的真实证据：建立期间唯一 OPERATING_REVIEW 会议、登记三份材料、
     * 计算客观三项分，并排下一毫秒的开会与评分任务。
     *
     * <p>证据只从本批关期台账与账户现算，之后不再读取更晚快照重算；披露与长期投入当前无实现，
     * 因此不会生成这两项，总分恒为空。自动登记材料不等于任何人物已经阅读。</p>
     */
    private BoardRun capturePeriodReview(long period, long capturedAt, long position,
                                         ManufacturingModel.State ledger,
                                         Map<String, CashLedger.CashAccount> accounts,
                                         List<ManufacturingChild> children) throws IOException {
        BoardRun run = requireGovernanceRun();
        long opensAt = Math.addExact(capturedAt, 1L);
        long closesAt = Math.addExact(opensAt, 1L);
        String meetingId = freeMeetingId(run.board(), "review:" + period);
        BoardModel.MeetingRequest request = new BoardModel.MeetingRequest(meetingId,
                BoardModel.Topic.OPERATING_REVIEW, charter.chairSeatId(), capturedAt,
                opensAt, closesAt, seatIds(), List.of());
        BoardModel.Outcome convened = BoardModel.convene(run.board(), capturedAt, request);
        if (!convened.accepted()) {
            throw new IllegalStateException("期间复盘会议无法建立：" + period + " / "
                    + convened.reason());
        }
        String reportSource = "period:" + period + ":report:" + position;
        String contractSource = "period:" + period + ":contracts:" + position;
        String receivableSource = "period:" + period + ":receivables:" + position;
        BoardModel.Outcome reviewed = convened;
        reviewed = addPeriodMaterial(reviewed, capturedAt, meetingId, "period-report",
                reportSource);
        reviewed = addPeriodMaterial(reviewed, capturedAt, meetingId, "period-contracts",
                contractSource);
        reviewed = addPeriodMaterial(reviewed, capturedAt, meetingId, "period-receivables",
                receivableSource);
        BoardRun.PeriodEvidence evidence = periodEvidence(period, capturedAt, position,
                meetingId, reportSource, contractSource, receivableSource, ledger, accounts);
        children.add(new ManufacturingChild(PendingTask.BOARD_OPEN_V1,
                WorldScheduler.Phase.RULES, "board-open:", opensAt, meetingId, -1L));
        children.add(new ManufacturingChild(PendingTask.BOARD_REVIEW_V1,
                WorldScheduler.Phase.COMPLETION, "board-review:", opensAt, meetingId, period));
        return run.withBoard(reviewed.state()).withPeriodEvidence(evidence);
    }

    private BoardModel.Outcome addPeriodMaterial(BoardModel.Outcome state, long now,
                                                String meetingId, String materialId,
                                                String sourceRef) {
        BoardModel.Outcome added = BoardModel.addMaterial(state.state(), now, meetingId,
                new BoardModel.MaterialRef(materialId, sourceRef, 1L, now));
        if (!added.accepted()) {
            throw new IllegalStateException("期间材料无法登记：" + materialId + " / "
                    + added.reason());
        }
        return added;
    }

    /** 按冻结规则计算本期三项客观分；缺项保留 null，不用 0 补齐。 */
    private BoardRun.PeriodEvidence periodEvidence(long period, long capturedAt, long position,
                                                   String meetingId, String reportSource,
                                                   String contractSource,
                                                   String receivableSource,
                                                   ManufacturingModel.State ledger,
                                                   Map<String, CashLedger.CashAccount> accounts) {
        long committed = 0L;
        long delivered = 0L;
        List<String> contractIds = new ArrayList<>();
        for (ManufacturingModel.SalesContract contract : ledger.contracts().values()) {
            if (contract.period() == period && contract.promisedDeliveryAt() <= capturedAt) {
                committed = Math.addExact(committed, contract.quantity());
                delivered = Math.addExact(delivered, contract.deliveredQuantity());
                contractIds.add(contract.contractId());
            }
        }
        BigDecimal operating = committed == 0 ? null : BigDecimal.valueOf(delivered)
                .multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(committed), 2, java.math.RoundingMode.HALF_UP);
        contractIds.sort(String::compareTo);

        BigDecimal payable = BigDecimal.ZERO.setScale(2);
        for (ManufacturingModel.ExpenseBill bill : ledger.expenseBills().values()) {
            payable = payable.add(bill.outstanding());
        }
        BigDecimal companyCash = accounts.get(ledger.opening().companyAccountId()).cash();
        BigDecimal solvency = payable.signum() == 0 ? new BigDecimal("100.00")
                : companyCash.divide(payable, 8, java.math.RoundingMode.HALF_UP)
                        .min(BigDecimal.ONE).multiply(new BigDecimal("100"))
                        .setScale(2, java.math.RoundingMode.HALF_UP);

        BigDecimal arTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal arOverdue = BigDecimal.ZERO.setScale(2);
        List<String> receivableIds = new ArrayList<>();
        for (ManufacturingModel.Receivable receivable : ledger.receivables().values()) {
            if (receivable.outstanding().signum() > 0) {
                arTotal = arTotal.add(receivable.outstanding());
                receivableIds.add(receivable.receivableId());
                if (receivable.status() == ManufacturingModel.ReceivableStatus.OVERDUE
                        || receivable.dueAt() <= capturedAt) {
                    arOverdue = arOverdue.add(receivable.outstanding());
                }
            }
        }
        BigDecimal risk = arTotal.signum() == 0 ? new BigDecimal("100.00")
                : arTotal.subtract(arOverdue).multiply(new BigDecimal("100"))
                        .divide(arTotal, 2, java.math.RoundingMode.HALF_UP);
        receivableIds.sort(String::compareTo);
        return new BoardRun.PeriodEvidence(period, capturedAt, position, meetingId,
                "period:" + period + ":evidence:" + position, reportSource, contractSource,
                receivableSource, operating, solvency, risk, contractIds, receivableIds);
    }

    private List<String> seatIds() {
        List<String> seats = new ArrayList<>();
        charter.seats().forEach(seat -> seats.add(seat.seatId()));
        return seats;
    }

    /** 自动会议标识在董事会已有会议里找空闲，不限制外部前缀。 */
    private static String freeMeetingId(BoardModel.State board, String stem) {
        String candidate = stem;
        int suffix = 1;
        while (board.meeting(candidate) != null) {
            candidate = stem + "-" + (++suffix);
        }
        return candidate;
    }

    /**
     * 外部治理动作到期：召集、材料、提案、投票、实施。全部在到期时按真实状态复核，
     * 业务拒绝也形成不可改写的治理回报，不改变预算或现金。
     */
    private void completeGovernance(PendingTask task) throws IOException {
        GovernancePayload payload = mapper.readValue(task.payload(), GovernancePayload.class);
        long now = task.dueTimeMillis();
        BoardRun run = requireGovernanceRun();
        BoardModel.State board = run.board();
        String meetingId = payload.meetingId();
        BoardModel.Outcome outcome;
        List<ManufacturingChild> children = new ArrayList<>();
        switch (task.kind()) {
            case PendingTask.GOVERNANCE_MEETING_V1 -> {
                outcome = BoardModel.convene(board, now, new BoardModel.MeetingRequest(meetingId,
                        BoardModel.Topic.BUDGET, payload.seatId(), payload.materialDeadline(),
                        payload.opensAt(), payload.closesAt(), payload.attendeeSeatIds(),
                        payload.recusedSeatIds()));
                if (outcome.accepted()) {
                    children.add(new ManufacturingChild(PendingTask.BOARD_OPEN_V1,
                            WorldScheduler.Phase.RULES, "board-open:", payload.opensAt(),
                            meetingId, -1L));
                }
            }
            case PendingTask.GOVERNANCE_MATERIAL_V1 -> {
                BoardModel.Meeting meeting = board.meeting(meetingId);
                if (meeting == null) {
                    outcome = new BoardModel.Outcome(false, BoardModel.REASON_UNKNOWN_MEETING,
                            board);
                } else if (!meeting.attendeeSeatIds().contains(payload.seatId())
                        || meeting.recusedSeatIds().contains(payload.seatId())) {
                    outcome = new BoardModel.Outcome(false, REASON_NOT_ELIGIBLE_SEAT, board);
                } else {
                    outcome = BoardModel.addMaterial(board, now, meetingId,
                            new BoardModel.MaterialRef(payload.materialId(),
                                    payload.sourceRef(), payload.version(), now));
                }
            }
            case PendingTask.GOVERNANCE_BUDGET_PROPOSAL_V1 -> {
                BoardModel.Meeting meeting = board.meeting(meetingId);
                if (meeting == null) {
                    outcome = new BoardModel.Outcome(false, BoardModel.REASON_UNKNOWN_MEETING,
                            board);
                } else if (!meeting.attendeeSeatIds().contains(payload.seatId())
                        || meeting.recusedSeatIds().contains(payload.seatId())) {
                    outcome = new BoardModel.Outcome(false, REASON_NOT_ELIGIBLE_SEAT, board);
                } else {
                    long closed = requireManufacturingRun().ledger().closedPeriods();
                    if (hasClosedPeriod(payload.lines(), closed)) {
                        outcome = new BoardModel.Outcome(false,
                                REASON_BUDGET_PERIOD_CLOSED, board);
                    } else {
                        outcome = BoardModel.proposeBudget(board, now, meetingId,
                                payload.lines());
                    }
                }
            }
            case PendingTask.GOVERNANCE_VOTE_V1 -> outcome = BoardModel.castVote(board, now,
                    meetingId, payload.seatId(), BoardModel.Vote.valueOf(payload.vote()));
            case PendingTask.GOVERNANCE_IMPLEMENT_V1 -> {
                BoardModel.Meeting target = board.meeting(meetingId);
                long closed = requireManufacturingRun().ledger().closedPeriods();
                if (target != null && target.status() == BoardModel.MeetingStatus.ADOPTED
                        && hasClosedPeriod(target.proposedLines(), closed)) {
                    outcome = new BoardModel.Outcome(false, REASON_BUDGET_PERIOD_CLOSED,
                            board);
                } else {
                    outcome = BoardModel.executeAdoptedResolution(board, now, meetingId);
                }
            }
            default -> throw new IllegalStateException("未启用的治理任务种类：" + task.kind());
        }
        finishGovernanceTask(task, payload.commandId(), outcome, meetingId,
                run.withBoard(outcome.state()), children);
    }

    /** 自动开会：冻结材料；预算请求存在时进入表决，并在同批排自动关会。 */
    private void completeBoardOpen(PendingTask task) throws IOException {
        ManufacturingChildPayload payload = mapper.readValue(task.payload(),
                ManufacturingChildPayload.class);
        BoardRun run = requireGovernanceRun();
        String meetingId = payload.businessId();
        BoardModel.Meeting meeting = run.board().meeting(meetingId);
        BoardModel.Outcome outcome;
        if (meeting == null) {
            outcome = new BoardModel.Outcome(false, BoardModel.REASON_UNKNOWN_MEETING,
                    run.board());
        } else {
            outcome = BoardModel.freezeMaterials(run.board(), task.dueTimeMillis(), meetingId);
            if (outcome.accepted()) {
                BoardModel.Meeting opened = outcome.state().meeting(meetingId);
                if (opened.topic() == BoardModel.Topic.BUDGET
                        && !opened.proposedLines().isEmpty()) {
                    outcome = BoardModel.startVoting(outcome.state(), task.dueTimeMillis(),
                            meetingId);
                }
            }
        }
        List<ManufacturingChild> children = new ArrayList<>();
        BoardModel.Meeting current = outcome.state().meeting(meetingId);
        if (current != null && current.topic() == BoardModel.Topic.BUDGET
                && (current.status() == BoardModel.MeetingStatus.MATERIALS_FROZEN
                || current.status() == BoardModel.MeetingStatus.VOTING)) {
            children.add(new ManufacturingChild(PendingTask.BOARD_CLOSE_V1,
                    WorldScheduler.Phase.COMPLETION, "board-close:", current.closesAt(),
                    meetingId, -1L));
        }
        finishGovernanceTask(task, SYSTEM_COMMAND_PREFIX + "board-open:" + meetingId, outcome,
                meetingId, run.withBoard(outcome.state()), children);
    }

    /** 自动关会：到期据真实选票形成 ADOPTED/REJECTED/ADJOURNED；空提案形成明确拒绝终态。 */
    private void completeBoardClose(PendingTask task) throws IOException {
        ManufacturingChildPayload payload = mapper.readValue(task.payload(),
                ManufacturingChildPayload.class);
        BoardRun run = requireGovernanceRun();
        BoardModel.Outcome outcome = BoardModel.closeVoting(run.board(), task.dueTimeMillis(),
                payload.businessId());
        finishGovernanceTask(task, SYSTEM_COMMAND_PREFIX + "board-close:" + payload.businessId(),
                outcome, payload.businessId(), run.withBoard(outcome.state()), List.of());
    }

    /** 自动复盘评分：只使用关期同批冻结的三项证据；披露/长期投入缺失，总分必为 null。 */
    private void completeBoardReview(PendingTask task) throws IOException {
        ManufacturingChildPayload payload = mapper.readValue(task.payload(),
                ManufacturingChildPayload.class);
        BoardRun run = requireGovernanceRun();
        BoardRun.PeriodEvidence evidence = run.evidence(payload.period())
                .orElseThrow(() -> new IllegalStateException(
                        "复盘任务缺少期间证据：" + payload.period()));
        List<BoardModel.EvidenceMetric> metrics = new ArrayList<>();
        if (evidence.operatingCommitment() != null) {
            metrics.add(new BoardModel.EvidenceMetric(
                    BoardModel.Dimension.OPERATING_COMMITMENT,
                    evidence.operatingCommitment(), evidence.contractSource(),
                    evidence.capturedAt()));
        }
        metrics.add(new BoardModel.EvidenceMetric(BoardModel.Dimension.CASH_SOLVENCY,
                evidence.cashSolvency(), evidence.reportSource(), evidence.capturedAt()));
        metrics.add(new BoardModel.EvidenceMetric(BoardModel.Dimension.RISK,
                evidence.risk(), evidence.receivableSource(), evidence.capturedAt()));
        BoardModel.Outcome outcome = BoardModel.calculateReview(run.board(),
                task.dueTimeMillis(), payload.businessId(), metrics);
        finishGovernanceTask(task, SYSTEM_COMMAND_PREFIX + "board-review:"
                + payload.businessId(), outcome, payload.businessId(),
                run.withBoard(outcome.state()), List.of());
    }

    /**
     * 治理任务统一收尾：完成待办、同批追加自动子任务与治理回报，保存全量状态后持久确认再发布。
     */
    private void finishGovernanceTask(PendingTask task, String commandId,
                                      BoardModel.Outcome outcome, String meetingId,
                                      BoardRun governance, List<ManufacturingChild> children)
            throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        for (ManufacturingChild child : children) {
            String childId = freeInternalTaskId(child.stem(), child.businessId(), used);
            ManufacturingChildPayload childPayload = new ManufacturingChildPayload(commandId,
                    child.businessId(), child.period());
            PendingTask scheduled = new PendingTask(childId, child.dueTimeMillis(), child.phase(),
                    child.kind(), mapper.writeValueAsString(childPayload));
            pending.add(scheduled);
            used.add(childId);
            events.add(new WorldEvent("task-scheduled:" + childId, "TASK_SCHEDULED_V1",
                    mapper.writeValueAsString(scheduled)));
        }
        BoardRun.Report entry = new BoardRun.Report(task.taskId(), commandId, task.kind(),
                meetingId, outcome.accepted(), outcome.reason(), task.dueTimeMillis(),
                state.position() + 1);
        BoardRun candidateRun = governance.withReport(entry);
        events.add(new WorldEvent("governance-report:" + task.taskId(),
                "GOVERNANCE_REPORT_V1", mapper.writeValueAsString(entry)));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), state.accounts(),
                pending, state.delivered(), used, state.commandContents(), state.commandOutcomes(),
                state.securities(), state.exchange(), state.manufacturing(), candidateRun);
        commitAndApply("task:" + task.taskId(), task.payload(), outcome.reason(), events, candidate);
    }

    // ------------------------------------------------------------------
    // 任职与资料阅读：到期执行、自动完成与报告
    // ------------------------------------------------------------------

    /** 自动阅读完成子任务：到期复核并终结，终态、经历、报告与待办同一批持久化。 */
    private record ReadingChild(String stem, long dueTimeMillis, String readId,
                                String personPartyId, String docId, long version,
                                long startedAt) {
    }

    /**
     * 任职任务到期：调用纯核 {@link RoleAccess#appoint} / {@link RoleAccess#revoke}。
     *
     * <p>语义拒绝（重叠、未知、已撤销、已到期）是显式管理结果，不中断世界：状态保持原样、
     * 报告记为 {@code accepted=false}。所有成功与失败都与报告同批提交。</p>
     */
    private void completeRole(PendingTask task) throws IOException {
        AppointmentPayload payload = mapper.readValue(task.payload(), AppointmentPayload.class);
        long now = task.dueTimeMillis();
        RoleRun run = requireRoleRun();
        RoleAccess.State access = run.access();
        boolean accepted;
        String reason;
        Map<String, CashLedger.CashAccount> accounts = state.accounts();
        SecuritiesLedger.State securities = state.securities();
        WorldExchange.State exchange = state.exchange();
        List<WorldEvent> events = new ArrayList<>();
        try {
            if (PendingTask.ROLE_APPOINT_V1.equals(task.kind())) {
                access = RoleAccess.appoint(registry, access, payload.appointmentId(),
                        payload.personPartyId(), payload.organizationPartyId(),
                        RoleAccess.Role.valueOf(payload.role()), payload.startsAt(),
                        payload.endsAt(), now);
            } else {
                access = RoleAccess.revoke(access, payload.appointmentId(), now);
                // 提前撤任：当批撤销该任职发出的活动子单，保留已成交
                if (state.trading() != null) {
                    CanceledOrders canceled = cancelByAppointment(state.trading().trading(),
                            payload.appointmentId(), now, task.taskId(), task.kind());
                    accounts = canceled.accounts();
                    securities = canceled.securities();
                    exchange = canceled.exchange();
                    events.addAll(canceled.events());
                }
            }
            accepted = true;
            reason = "OK";
        } catch (IllegalArgumentException rejected) {
            accepted = false;
            reason = "REJECTED";
            access = run.access();
            accounts = state.accounts();
            securities = state.securities();
            exchange = state.exchange();
            events = new ArrayList<>();
        }
        RoleRun.Report entry = new RoleRun.Report(task.taskId(), payload.commandId(),
                task.kind(), accepted, reason, payload.appointmentId(), now,
                state.position() + 1);
        finishRoleTask(task, run.withAccess(access).withReport(entry), entry, accounts, securities,
                exchange, events);
    }

    /** 撤销某任职发出的全部活动子单；不动工作流、链接与其它任职的订单。 */
    private CanceledOrders cancelByAppointment(RoleTradingModel.State trading, String appointmentId,
                                               long now, String taskId, String kind)
            throws IOException {
        Map<String, CashLedger.CashAccount> accounts = state.accounts();
        SecuritiesLedger.State securities = state.securities();
        WorldExchange.State exchange = state.exchange();
        List<Long> cancelled = new ArrayList<>();
        for (RoleTradingModel.ChildLink link : trading.children()) {
            if (!link.appointmentId().equals(appointmentId)) {
                continue;
            }
            RoleTradingModel.Target target = RoleTradingModel.target(trading, link.targetId());
            WorldExchange.Order order = exchange.order(link.orderId());
            if (target == null || order == null || !order.live()) {
                continue;
            }
            WorldExchange.Outcome outcome = WorldExchange.cancel(exchange, accounts, securities,
                    target.productOwnerId(), link.orderId(), now);
            if (outcome.accepted()) {
                exchange = outcome.exchange();
                accounts = outcome.accounts();
                securities = outcome.securities();
                cancelled.add(link.orderId());
            }
        }
        List<WorldEvent> events = new ArrayList<>();
        if (!cancelled.isEmpty()) {
            WorldExchange.OrderReport report = new WorldExchange.OrderReport(taskId,
                    "system:appointment-end", kind, true, WorldExchange.REASON_CANCELLED, null,
                    List.of(), cancelled, now, state.position() + 1);
            exchange = exchange.withReport(report);
            events.add(new WorldEvent("exchange-report:" + taskId + ":" + appointmentId,
                    "EXCHANGE_ORDER_REPORT_V1", mapper.writeValueAsString(report)));
        }
        return new CanceledOrders(accounts, securities, exchange, events);
    }

    /** 任职任务统一收尾：完成待办、写报告事件、保存全量状态后持久确认再发布。 */
    private void finishRoleTask(PendingTask task, RoleRun roles, RoleRun.Report entry)
            throws IOException {
        finishRoleTask(task, roles, entry, state.accounts(), state.securities(), state.exchange(),
                List.of());
    }

    private void finishRoleTask(PendingTask task, RoleRun roles, RoleRun.Report entry,
                                Map<String, CashLedger.CashAccount> accounts,
                                SecuritiesLedger.State securities, WorldExchange.State exchange,
                                List<WorldEvent> effects) throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        events.addAll(effects);
        events.add(new WorldEvent("role-report:" + task.taskId(), "ROLE_REPORT_V1",
                mapper.writeValueAsString(entry)));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), accounts,
                pending, state.delivered(), used, state.commandContents(), state.commandOutcomes(),
                securities, exchange, state.manufacturing(), state.governance(),
                roles, state.information());
        commitAndApply("task:" + task.taskId(), task.payload(), entry.reason(), events, candidate);
    }

    /**
     * 资料阅读任务到期：发表、追加版本、开始阅读、取消阅读与自动完成共用同一收尾。
     *
     * <p>发表的实际时间就是任务到期；开始阅读成功时同批创建唯一匹配 {@code readId} 的自动完成
     * 待办；取消只移除本单对应待办；自动完成以阅读的 {@code dueAt} 复核任职并终结，
     * {@code DENIED} 也保留终态、释放工时并写报告。</p>
     */
    private void completeInformation(PendingTask task) throws IOException {
        InformationRun run = requireInformationRun();
        InformationModel.State before = run.information();
        long now = task.dueTimeMillis();
        InformationModel.State after = before;
        boolean accepted;
        String reason;
        String subjectId = "";
        String reader = "";
        String commandId;
        ReadingChild child = null;
        boolean removeReadingChild = false;
        String internalReadId = null;
        InformationModel.ReadObservation observation = null;

        switch (task.kind()) {
            case PendingTask.INFORMATION_PUBLISH_V1 -> {
                PublishPayload payload = mapper.readValue(task.payload(), PublishPayload.class);
                commandId = payload.commandId();
                subjectId = payload.docId();
                try {
                    after = InformationModel.publish(before, payload.docId(),
                            payload.publisherPartyId(), audienceOf(payload),
                            draftOf(payload, now));
                    accepted = true;
                    reason = "OK";
                } catch (IllegalArgumentException rejected) {
                    accepted = false;
                    reason = "REJECTED";
                    after = before;
                }
            }
            case PendingTask.INFORMATION_PUBLISH_VERSION_V1 -> {
                PublishPayload payload = mapper.readValue(task.payload(), PublishPayload.class);
                commandId = payload.commandId();
                subjectId = payload.docId();
                try {
                    after = InformationModel.publishVersion(before, payload.docId(),
                            draftOf(payload, now));
                    accepted = true;
                    reason = "OK";
                } catch (IllegalArgumentException rejected) {
                    accepted = false;
                    reason = "REJECTED";
                    after = before;
                }
            }
            case PendingTask.INFORMATION_START_READ_V1 -> {
                ReadingPayload payload = mapper.readValue(task.payload(), ReadingPayload.class);
                commandId = payload.commandId();
                reader = payload.personPartyId();
                subjectId = payload.docId();
                InformationModel.Outcome outcome = InformationModel.startRead(before,
                        requireRoleRun().access(), payload.personPartyId(), payload.docId(),
                        payload.version(), now);
                after = outcome.state();
                accepted = outcome.status() == InformationModel.Status.STARTED;
                reason = outcome.status().name();
                if (accepted) {
                    InformationModel.Reading started = readingView(after, reader);
                    child = new ReadingChild("read:", started.dueAt(), started.readId(), reader,
                            payload.docId(), payload.version(), started.startedAt());
                }
            }
            case PendingTask.INFORMATION_CANCEL_READ_V1 -> {
                ReadingPayload payload = mapper.readValue(task.payload(), ReadingPayload.class);
                commandId = payload.commandId();
                reader = payload.personPartyId();
                subjectId = payload.docId();
                InformationModel.Reading target = activeReadingOf(before, payload.personPartyId(),
                        payload.docId(), payload.version(), payload.refIndex());
                if (target == null) {
                    after = before;
                    accepted = false;
                    reason = "NOT_FOUND";
                } else {
                    InformationModel.Outcome outcome = InformationModel.cancelRead(before,
                            payload.personPartyId(), target.readId(), now);
                    after = outcome.state();
                    accepted = outcome.status() == InformationModel.Status.CANCELLED;
                    reason = outcome.status().name();
                    removeReadingChild = accepted;
                    internalReadId = target.readId();
                }
            }
            case PendingTask.INFORMATION_COMPLETE_READ_V1 -> {
                ReadingPayload payload = mapper.readValue(task.payload(), ReadingPayload.class);
                commandId = payload.commandId();
                reader = payload.personPartyId();
                subjectId = payload.readId();
                RoleAccess.State readingAccess = requireRoleRun().access();
                String original = state.commandContents().get(payload.commandId());
                if (state.trading() != null && original != null
                        && PendingTask.ROLE_TRADING_READ_V1.equals(
                        mapper.readTree(original).path("kind").asText())) {
                    RoleActionPayload start = mapper.readValue(original, RoleActionPayload.class);
                    readingAccess = scopedRoleAccess(readingAccess, payload.personPartyId(),
                            start.appointments());
                }
                InformationModel.Outcome outcome = InformationModel.completeRead(before,
                        readingAccess, payload.personPartyId(), payload.readId(), now);
                after = outcome.state();
                observation = outcome.observation();
                accepted = outcome.status() == InformationModel.Status.COMPLETED;
                reason = outcome.status().name();
            }
            default -> throw new IllegalStateException("未启用的阅读任务种类：" + task.kind());
        }

        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        if (removeReadingChild) {
            removeReadingChildTask(pending, internalReadId);
        }
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        if (child != null) {
            String childId = freeInternalTaskId(child.stem(), child.readId(), used);
            PendingTask scheduled = new PendingTask(childId, child.dueTimeMillis(),
                    WorldScheduler.Phase.COMPLETION, PendingTask.INFORMATION_COMPLETE_READ_V1,
                    mapper.writeValueAsString(new ReadingPayload(commandId, child.personPartyId(),
                            child.docId(), child.version(), child.readId(), 0L)));
            pending.add(scheduled);
            used.add(childId);
            events.add(new WorldEvent("task-scheduled:" + childId, "TASK_SCHEDULED_V1",
                    mapper.writeValueAsString(scheduled)));
        }
        if (observation != null) {
            events.add(new WorldEvent("information-observation:" + task.taskId(),
                    "INFORMATION_OBSERVATION_V1", mapper.writeValueAsString(observation)));
        }
        InformationRun.Report entry = new InformationRun.Report(task.taskId(), commandId,
                task.kind(), accepted, reason, subjectId, reader, task.dueTimeMillis(),
                state.position() + 1);
        InformationRun candidateRun = run.withInformation(after).withReport(entry);
        events.add(new WorldEvent("information-report:" + task.taskId(),
                "INFORMATION_REPORT_V1", mapper.writeValueAsString(entry)));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), state.accounts(),
                pending, state.delivered(), used, state.commandContents(), state.commandOutcomes(),
                state.securities(), state.exchange(), state.manufacturing(), state.governance(),
                state.roles(), candidateRun);
        commitAndApply("task:" + task.taskId(), task.payload(), reason, events, candidate);
    }

    /** 按本人范围引用查活动阅读：序号、资料、版本都要对上；别人的阅读单返回 null。 */
    private static InformationModel.Reading activeReadingOf(InformationModel.State state,
                                                            String personPartyId, String docId,
                                                            long version, long index) {
        Map<String, Integer> ordinals = readingOrdinals(state, personPartyId);
        for (InformationModel.Reading reading : state.active()) {
            if (!reading.personPartyId().equals(personPartyId)
                    || !reading.docId().equals(docId)
                    || reading.version() != version) {
                continue;
            }
            Integer ordinal = ordinals.get(reading.readId());
            if (ordinal != null && ordinal == index) {
                return reading;
            }
        }
        return null;
    }

    /** 只移除与给定 readId 匹配的自动完成待办：不受理其它阅读单的取消。 */
    private void removeReadingChildTask(List<PendingTask> pending, String readId)
            throws IOException {
        for (int i = 0; i < pending.size(); i++) {
            PendingTask candidate = pending.get(i);
            if (!PendingTask.INFORMATION_COMPLETE_READ_V1.equals(candidate.kind())) {
                continue;
            }
            ReadingPayload payload = mapper.readValue(candidate.payload(), ReadingPayload.class);
            if (readId.equals(payload.readId())) {
                pending.remove(i);
                return;
            }
        }
    }

    /** 由发表任务参数还原受众；种类在排程期已按固定登记校验，这里再解析一次。 */
    private InformationModel.Audience audienceOf(PublishPayload payload) {
        InformationModel.AudienceKind kind =
                InformationModel.AudienceKind.valueOf(payload.audienceKind());
        return switch (kind) {
            case PUBLIC -> InformationModel.Audience.publicAudience();
            case ORGANIZATION -> InformationModel.Audience.organization(payload.audiencePartyId());
            case DIRECT -> InformationModel.Audience.direct(payload.audiencePartyId());
        };
    }

    /** 发表草稿：发布时刻恒等于任务到期，不能由调用方指定过去出版经历。 */
    private static InformationModel.VersionDraft draftOf(PublishPayload payload, long publishedAt) {
        return new InformationModel.VersionDraft(payload.title(), payload.body(),
                payload.publicSourceDescription() == null ? "" : payload.publicSourceDescription(),
                publishedAt, payload.readDurationMillis(), payload.internalSourceIds());
    }

    /** 刚启动的阅读单：从候选状态里按读者取唯一活动阅读。 */
    private InformationModel.Reading readingView(InformationModel.State state,
                                                 String personPartyId) {
        for (InformationModel.Reading reading : state.active()) {
            if (reading.personPartyId().equals(personPartyId)) {
                return reading;
            }
        }
        throw new IllegalStateException("开始阅读后缺少活动阅读单：" + personPartyId);
    }

    /**
     * 制造任务统一收尾：完成待办、追加自动子任务、写报告与资金事件、保存全量状态后在持久确认后发布。
     */
    private void finishManufacturingTask(PendingTask task, String commandId,
                                         ManufacturingModel.Outcome outcome,
                                         List<ManufacturingChild> children, String businessId,
                                         BigDecimal amount, List<WorldEvent> effects,
                                         BoardRun governanceCandidate)
            throws IOException {
        ManufacturingRun run = requireManufacturingRun().withLedger(outcome.state());
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        events.addAll(effects);
        for (ManufacturingChild child : children) {
            String childId = freeInternalTaskId(child.stem(), child.businessId(), used);
            ManufacturingChildPayload childPayload = new ManufacturingChildPayload(commandId,
                    child.businessId(), child.period());
            PendingTask scheduled = new PendingTask(childId, child.dueTimeMillis(),
                    child.phase(), child.kind(), mapper.writeValueAsString(childPayload));
            pending.add(scheduled);
            used.add(childId);
            events.add(new WorldEvent("task-scheduled:" + childId, "TASK_SCHEDULED_V1",
                    mapper.writeValueAsString(scheduled)));
        }
        ReportEntry entry = new ReportEntry(task.taskId(), commandId, task.kind(),
                outcome.accepted(), outcome.reason(), businessId, amount, task.dueTimeMillis(),
                state.position() + 1);
        run = run.withReport(entry);
        events.add(new WorldEvent("manufacturing-report:" + task.taskId(),
                "MANUFACTURING_REPORT_V1", mapper.writeValueAsString(entry)));
        BoardRun governance = governanceCandidate != null ? governanceCandidate
                : state.governance();
        if (governanceCandidate != null && governanceCandidate.periodEvidence().size()
                > state.governance().periodEvidence().size()) {
            for (BoardRun.PeriodEvidence evidence : governanceCandidate.periodEvidence().values()) {
                if (!state.governance().periodEvidence().containsKey(evidence.period())) {
                    events.add(new WorldEvent("period-evidence:" + evidence.period(),
                            "PERIOD_EVIDENCE_V1", mapper.writeValueAsString(evidence)));
                }
            }
        }
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), outcome.accounts(),
                pending, state.delivered(), used, state.commandContents(), state.commandOutcomes(),
                state.securities(), state.exchange(), run, governance);
        commitAndApply("task:" + task.taskId(), task.payload(), outcome.reason(), events, candidate);
    }

    /** 资金事件：只记录真实发生的制造付款/收款，载荷与普通转账、证券清算都不兼容。 */
    private List<WorldEvent> cashTransferred(PendingTask task, String fromId, String toId,
                                             BigDecimal amount, String businessId)
            throws IOException {
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("taskId", task.taskId());
        effect.put("kind", task.kind());
        effect.put("fromAccountId", fromId);
        effect.put("toAccountId", toId);
        effect.put("amount", amount);
        effect.put("businessId", businessId);
        return List.of(new WorldEvent("manufacturing-cash:" + task.taskId(),
                "MANUFACTURING_CASH_TRANSFERRED_V1", mapper.writeValueAsString(effect)));
    }

    private ManufacturingRun requireManufacturingRun() {
        ManufacturingRun run = state.manufacturing();
        if (run == null) {
            throw new IllegalStateException("当前运行没有制造子集状态");
        }
        return run;
    }

    private BoardRun requireGovernanceRun() {
        BoardRun run = state.governance();
        if (run == null) {
            throw new IllegalStateException("当前运行没有治理子集状态");
        }
        return run;
    }

    private RoleRun requireRoleRun() {
        RoleRun run = state.roles();
        if (run == null) {
            throw new IllegalStateException("当前运行没有任职子集状态");
        }
        return run;
    }

    private InformationRun requireInformationRun() {
        InformationRun run = state.information();
        if (run == null) {
            throw new IllegalStateException("当前运行没有资料阅读子集状态");
        }
        return run;
    }

    /** 任职与阅读接口只对 informed-stock-company-experiment-v1 开放。 */
    private void requireRoleReadingEnabled() {
        if (!manifest.hasRoles() || !manifest.hasInformation()
                || roleConfig == null || informationConfig == null) {
            throw new IllegalStateException("当前运行未启用任职与资料阅读模块："
                    + manifest.rulesVersion());
        }
    }

    /** 采购下单预算：按下单期间 PROCUREMENT 占用 quantity×50，承诺标识与采购单一一对应。 */
    private BoardModel.Outcome reservePurchaseBudget(long now, String purchaseOrderId,
                                                     long quantity) {
        BigDecimal amount = CashLedger.money(ManufacturingModel.RAW_UNIT_PRICE
                .multiply(BigDecimal.valueOf(quantity)));
        return BoardModel.reserveBudget(requireGovernanceRun().board(), now,
                "po:" + purchaseOrderId, now / ManufacturingModel.PERIOD_MILLIS,
                BoardModel.BudgetCategory.PROCUREMENT, amount);
    }

    /** 投产预算：当期 LABOR 占用 quantity×30，并在同一批把真实人工付款转已花。 */
    private BoardModel.Outcome reserveLaborBudget(long now, String batchId, long quantity) {
        BigDecimal amount = CashLedger.money(ManufacturingModel.LABOR_UNIT_PRICE
                .multiply(BigDecimal.valueOf(quantity)));
        String commitmentId = "labor:" + batchId;
        BoardModel.Outcome reserved = BoardModel.reserveBudget(requireGovernanceRun().board(), now,
                commitmentId, now / ManufacturingModel.PERIOD_MILLIS,
                BoardModel.BudgetCategory.PRODUCTION_LABOR, amount);
        if (!reserved.accepted()) {
            return reserved;
        }
        BoardModel.Outcome spent = BoardModel.spendBudget(reserved.state(), now, commitmentId,
                amount);
        if (!spent.accepted()) {
            throw new IllegalStateException("人工付款成功但预算承诺无法转已花："
                    + batchId + " / " + spent.reason());
        }
        return spent;
    }

    /** 现金不足到货：把原采购承诺未用额全额释放，不留下 active 残留。 */
    private BoardModel.State releasePurchaseBudget(long now, String purchaseOrderId) {
        BoardModel.State board = requireGovernanceRun().board();
        BoardModel.Commitment commitment = null;
        for (BoardModel.Commitment candidate : board.commitments()) {
            if (candidate.commitmentId().equals("po:" + purchaseOrderId)) {
                commitment = candidate;
                break;
            }
        }
        if (commitment == null || commitment.status() != BoardModel.CommitmentStatus.ACTIVE) {
            throw new IllegalStateException("失败到货缺少活动预算承诺：" + purchaseOrderId);
        }
        BoardModel.Outcome released = BoardModel.releaseBudget(board, now,
                commitment.commitmentId(), commitment.outstanding());
        if (!released.accepted()) {
            throw new IllegalStateException("失败到货无法释放预算承诺："
                    + purchaseOrderId + " / " + released.reason());
        }
        return released.state();
    }

    /** 同一快照下的可用现金：撮合档案扣买单冻结，旧档案按余额。 */
    private BigDecimal availableCash(String accountId, Map<String, CashLedger.CashAccount> accounts) {
        CashLedger.CashAccount account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalStateException("缺少现金账户：" + accountId);
        }
        if (manifest.hasMatching() && state.exchange() != null) {
            return WorldExchange.freeCash(state.exchange(), accounts, accountId);
        }
        return account.cash();
    }

    private BigDecimal customerFree(ManufacturingModel.State ledger,
                                    Map<String, CashLedger.CashAccount> accounts) {
        return availableCash(ledger.opening().customerAccountId(), accounts);
    }

    private static BigDecimal outstanding(ManufacturingModel.State ledger, String receivableId) {
        ManufacturingModel.Receivable receivable = ledger.receivables().get(receivableId);
        return receivable == null ? BigDecimal.ZERO : receivable.outstanding();
    }

    private static ManufacturingModel.ExpenseBill billOfPeriod(ManufacturingModel.State ledger,
                                                               long period) {
        for (ManufacturingModel.ExpenseBill bill : ledger.expenseBills().values()) {
            if (bill.period() == period) {
                return bill;
            }
        }
        return null;
    }

    /**
     * 为内部任务找空闲标识：从固定前缀起，被占用就加确定性序号。
     *
     * <p>外部 taskId 不保留任何前缀，所以不能用"禁止某前缀"隔离；用户提前占名只会让内部任务换一个
     * 可读的后缀，不会让强制任务永久失败。</p>
     */
    private static String freeInternalTaskId(String stem, String businessId, Set<String> used) {
        String base = stem + businessId;
        String candidate = base;
        int suffix = 1;
        while (used.contains(candidate)) {
            candidate = base + "-" + (++suffix);
        }
        return candidate;
    }

    /** 费用账单标识在纯核已用标识里找空闲；同样不限制用户前缀。 */
    private static String freeBillId(ManufacturingModel.State ledger, long period) {
        String base = "bill:" + period;
        String candidate = base;
        int suffix = 1;
        while (ledger.usedIds().contains(candidate)) {
            candidate = base + "-" + (++suffix);
        }
        return candidate;
    }

    private void commitAndApply(String commandId, String content, String result,
                                List<WorldEvent> domainEvents, WorldState candidate)
            throws IOException {
        try {
            validate(candidate, candidate.position(), state);
        } catch (IOException invalid) {
            paused = true;
            throw invalid;
        }
        List<WorldEvent> events = new ArrayList<>(domainEvents);
        events.add(snapshotEvent(candidate));
        try {
            validateAudits(new WorldCommit(1, candidate.position(), manifest.runId(), commandId,
                    content, result, events), candidate, state);
            WorldCommit committed = journal.commit(commandId, content, result, events);
            if (committed.position() != candidate.position()) {
                throw new IOException("候选快照缺少对应的新持久批次");
            }
        } catch (IOException | RuntimeException failure) {
            // 提交期任何失败（含重复事件 ID、超大帧）都暂停并保留旧快照
            paused = true;
            throw failure;
        }
        state = candidate;
    }

    private PendingTask peek(long inclusiveMillis) {
        Comparator<PendingTask> order = Comparator
                .comparingLong(PendingTask::dueTimeMillis)
                .thenComparingInt(task -> task.phase().ordinal())
                .thenComparing(task -> WorldScheduler.fairOrder(seed, task.taskId()))
                .thenComparing(PendingTask::taskId);
        PendingTask best = null;
        for (PendingTask task : state.pending()) {
            if (task.dueTimeMillis() <= inclusiveMillis
                    && (best == null || order.compare(task, best) < 0)) {
                best = task;
            }
        }
        return best;
    }

    private void validate(WorldState candidate, long expectedPosition, WorldState previous)
            throws IOException {
        if (candidate.schemaVersion() != expectedSchema()) {
            throw new IOException("状态版本不支持：" + candidate.schemaVersion()
                    + "（本次运行需要 " + expectedSchema() + "）");
        }
        if (!manifest.runId().equals(candidate.runId())) {
            throw new IOException("状态运行标识不匹配");
        }
        if (candidate.position() != expectedPosition) {
            throw new IOException("状态位置不连续：" + candidate.position());
        }
        if (previous != null) {
            if (candidate.timeMillis() < previous.timeMillis()) {
                throw new IOException("状态时间倒退");
            }
            if (!candidate.accounts().keySet().equals(previous.accounts().keySet())) {
                throw new IOException("状态账户集合被改变");
            }
            for (Map.Entry<String, CashLedger.CashAccount> entry : candidate.accounts().entrySet()) {
                CashLedger.CashAccount before = previous.accounts().get(entry.getKey());
                CashLedger.CashAccount now = entry.getValue();
                if (!before.ownerId().equals(now.ownerId())) {
                    throw new IOException("状态账户权属被改变：" + entry.getKey());
                }
                if (!before.source().equals(now.source())) {
                    throw new IOException("状态开局来源被改变：" + entry.getKey());
                }
            }
            if (total(candidate).compareTo(total(previous)) != 0) {
                throw new IOException("状态现金总额不守恒");
            }
            if (!candidate.usedTaskIds().containsAll(previous.usedTaskIds())) {
                throw new IOException("状态已用任务标识丢失");
            }
            if (!candidate.commandContents().keySet().containsAll(previous.commandContents().keySet())) {
                throw new IOException("状态命令记录丢失");
            }
            if (candidate.delivered().size() < previous.delivered().size()) {
                throw new IOException("状态已投递回报丢失");
            }
            for (int i = 0; i < previous.delivered().size(); i++) {
                if (!candidate.delivered().get(i).equals(previous.delivered().get(i))) {
                    throw new IOException("状态已投递回报被改写：" + i);
                }
            }
            if (!candidate.commandOutcomes().keySet().equals(candidate.commandContents().keySet())) {
                throw new IOException("状态命令内容与回报的键不一致");
            }
            for (Map.Entry<String, String> entry : previous.commandContents().entrySet()) {
                if (!entry.getValue().equals(candidate.commandContents().get(entry.getKey()))) {
                    throw new IOException("状态旧命令内容被改写：" + entry.getKey());
                }
            }
            for (Map.Entry<String, String> entry : previous.commandOutcomes().entrySet()) {
                if (!entry.getValue().equals(candidate.commandOutcomes().get(entry.getKey()))) {
                    throw new IOException("状态旧命令回报被改写：" + entry.getKey());
                }
            }
        }
        WorldScheduler.Phase phase;
        try {
            phase = WorldScheduler.Phase.valueOf(candidate.lastPhase());
        } catch (RuntimeException invalid) {
            throw new IOException("状态阶段名非法：" + candidate.lastPhase(), invalid);
        }
        if (previous != null && candidate.timeMillis() == previous.timeMillis()
                && phase.ordinal() < WorldScheduler.Phase.valueOf(previous.lastPhase()).ordinal()) {
            throw new IOException("同一模拟时点的状态阶段倒退");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (CashLedger.CashAccount account : candidate.accounts().values()) {
            if (account.cash().signum() < 0) {
                throw new IOException("状态出现负余额：" + account.ownerId());
            }
        }
        for (PendingTask task : candidate.pending()) {
            if (!ids.add(task.taskId())) {
                throw new IOException("状态待办重复：" + task.taskId());
            }
            if (!candidate.usedTaskIds().contains(task.taskId())) {
                throw new IOException("状态待办未登记到已用任务：" + task.taskId());
            }
            if (task.dueTimeMillis() < candidate.timeMillis()) {
                throw new IOException("状态待办到期早于快照时点：" + task.taskId());
            }
            if (task.dueTimeMillis() == candidate.timeMillis() && task.phase().ordinal() < phase.ordinal()) {
                throw new IOException("状态待办落在同一时点已结束的阶段：" + task.taskId());
            }
        }
        validateSecurities(candidate, previous);
        validateExchange(candidate, previous);
        validateManufacturing(candidate, previous);
        validateGovernance(candidate, previous);
        validateRoleReading(candidate, previous);
        validateTrading(candidate, previous);
        validateDecisions(candidate, previous);
    }

    /**
     * 校验任职与阅读子集：固定开局配置不漂移、旧历史只增不改、活动阅读与唯一自动完成待办双向一致。
     *
     * <p>这是恢复期的强制边界：{@code fromJson} 只保证内部自洽，不能证明任职、账户与受众来自
     * 真实开局。这里每批都重新对照冻结登记与冻结配置，并调用
     * {@link RoleAccess#validateAgainst} 复核账户/个人绑定/mandates/任职身份。</p>
     */
    private void validateRoleReading(WorldState candidate, WorldState previous) throws IOException {
        if (expectedSchema() < STATE_SCHEMA_ROLE_READING) {
            if (candidate.roles() != null || candidate.information() != null) {
                throw new IOException("旧档案不得包含任职或阅读子集状态");
            }
            return;
        }
        if (candidate.roles() == null || candidate.information() == null) {
            throw new IOException("任职与阅读档案缺少子集状态");
        }
        validateRole(candidate, previous);
        validateInformation(candidate, previous);
    }

    /** 任职子集：来源校验 + 固定配置不漂移 + 旧任职只允许补一次撤销。 */
    private void validateRole(WorldState candidate, WorldState previous) throws IOException {
        RoleRun run = candidate.roles();
        RoleAccess.State access = run.access();
        RoleAccess.validateAgainst(registry, orderedOpenings, access);
        RoleAccess.State baseline = roleStateOf(roleConfig);
        if (!access.accounts().equals(baseline.accounts())) {
            throw new IOException("任职账户集合与冻结开局不一致");
        }
        if (!access.personAccounts().equals(baseline.personAccounts())) {
            throw new IOException("个人账户绑定与冻结开局不一致");
        }
        if (!access.mandates().equals(baseline.mandates())) {
            throw new IOException("产品管理授权与冻结配置不一致");
        }
        Map<String, RoleAccess.Appointment> baselineById = appointmentsById(baseline);
        Map<String, RoleAccess.Appointment> nowById = appointmentsById(access);
        for (RoleAccess.Appointment initial : baseline.appointments()) {
            RoleAccess.Appointment found = nowById.get(initial.id());
            if (found == null) {
                throw new IOException("初始任职被删除：" + initial.id());
            }
            if (!sameIdentity(initial, found)) {
                throw new IOException("初始任职的身份、岗位或原期限被改写：" + initial.id());
            }
        }
        if (previous != null && previous.roles() != null) {
            for (RoleAccess.Appointment before : previous.roles().access().appointments()) {
                RoleAccess.Appointment after = nowById.get(before.id());
                if (after == null) {
                    throw new IOException("旧任职记录被删除：" + before.id());
                }
                if (!sameIdentity(before, after)) {
                    throw new IOException("旧任职的身份、岗位或原期限被改写：" + before.id());
                }
                if (before.revokedAt() != null && !before.revokedAt().equals(after.revokedAt())) {
                    throw new IOException("旧任职的撤销时刻被改写：" + before.id());
                }
            }
            for (Map.Entry<String, RoleRun.Report> entry : previous.roles().reports().entrySet()) {
                if (!entry.getValue().equals(run.reports().get(entry.getKey()))) {
                    throw new IOException("旧任职报告被改写：" + entry.getKey());
                }
            }
        }
        for (RoleRun.Report report : run.reports().values()) {
            if (report.position() > candidate.position()) {
                throw new IOException("任职报告的位置超过提交位置：" + report.taskId());
            }
        }
        if (baselineById.size() != baseline.appointments().size()) {
            throw new IOException("冻结配置的初始任职标识重复");
        }
    }

    /** 任职身份/岗位/原期限完全一致；撤销时刻单独比较。 */
    private static boolean sameIdentity(RoleAccess.Appointment left, RoleAccess.Appointment right) {
        return left.personPartyId().equals(right.personPartyId())
                && left.organizationPartyId().equals(right.organizationPartyId())
                && left.role() == right.role()
                && left.startsAt() == right.startsAt()
                && left.endsAt() == right.endsAt();
    }

    private static Map<String, RoleAccess.Appointment> appointmentsById(RoleAccess.State state) {
        Map<String, RoleAccess.Appointment> map = new LinkedHashMap<>();
        for (RoleAccess.Appointment appointment : state.appointments()) {
            map.put(appointment.id(), appointment);
        }
        return map;
    }

    /** 阅读子集：登记读者固定、旧文档/版本/终态/经历只增不改、活动阅读与唯一自动完成待办双向一致。 */
    private void validateInformation(WorldState candidate, WorldState previous) throws IOException {
        InformationRun run = candidate.information();
        InformationModel.State information = run.information();
        if (!information.persons().equals(informationConfig.readers())) {
            throw new IOException("登记读者与冻结配置不一致");
        }
        if (previous != null && previous.information() != null) {
            InformationModel.State before = previous.information().information();
            if (information.nextReadSequence() < before.nextReadSequence()) {
                throw new IOException("阅读序号倒退");
            }
            for (InformationModel.Document doc : before.documents()) {
                InformationModel.Document now = documentOf(information, doc.docId());
                if (now == null) {
                    throw new IOException("旧资料被删除：" + doc.docId());
                }
                if (!now.publisherPartyId().equals(doc.publisherPartyId())
                        || !now.audience().equals(doc.audience())) {
                    throw new IOException("旧资料的发布者或受众被改写：" + doc.docId());
                }
                if (now.versions().size() < doc.versions().size()
                        || !now.versions().subList(0, doc.versions().size()).equals(doc.versions())) {
                    throw new IOException("旧资料版本前缀被改写：" + doc.docId());
                }
            }
            for (InformationModel.ClosedReading closed : before.closed()) {
                if (!information.closed().contains(closed)) {
                    throw new IOException("已终结阅读被删改：" + closed.readId());
                }
            }
            for (InformationModel.ReadObservation observation : before.observations()) {
                if (!information.observations().contains(observation)) {
                    throw new IOException("本人经历被删改：" + observation.readId());
                }
            }
            for (InformationModel.Reading reading : before.active()) {
                if (!information.active().contains(reading)
                        && !closedContains(information.closed(), reading.readId())) {
                    throw new IOException("活动阅读被无终态删除：" + reading.readId());
                }
            }
            for (Map.Entry<String, InformationRun.Report> entry
                    : previous.information().reports().entrySet()) {
                if (!entry.getValue().equals(run.reports().get(entry.getKey()))) {
                    throw new IOException("旧阅读报告被改写：" + entry.getKey());
                }
            }
        }
        for (InformationRun.Report report : run.reports().values()) {
            if (report.position() > candidate.position()) {
                throw new IOException("阅读报告的位置超过提交位置：" + report.taskId());
            }
        }
        validateReadingChildren(candidate);
    }

    /** 活动阅读与其自动完成待办必须一一对应：活动单不能没有待办，待办也不能指向已终结阅读。 */
    private void validateReadingChildren(WorldState candidate) throws IOException {
        InformationModel.State information = candidate.information().information();
        Map<String, Integer> children = new LinkedHashMap<>();
        for (PendingTask task : candidate.pending()) {
            if (!PendingTask.INFORMATION_COMPLETE_READ_V1.equals(task.kind())) {
                continue;
            }
            ReadingPayload payload = mapper.readValue(task.payload(), ReadingPayload.class);
            children.merge(payload.readId(), 1, Integer::sum);
        }
        for (InformationModel.Reading reading : information.active()) {
            Integer count = children.remove(reading.readId());
            if (count == null) {
                throw new IOException("活动阅读缺少自动完成待办：" + reading.readId());
            }
            if (count != 1) {
                throw new IOException("活动阅读的自动完成待办不唯一：" + reading.readId());
            }
        }
        if (!children.isEmpty()) {
            throw new IOException("自动完成待办指向不存在的活动阅读：" + children.keySet());
        }
    }

    private static InformationModel.Document documentOf(InformationModel.State state, String docId) {
        for (InformationModel.Document document : state.documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }

    private static boolean closedContains(List<InformationModel.ClosedReading> closed, String readId) {
        for (InformationModel.ClosedReading reading : closed) {
            if (reading.readId().equals(readId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验制造子集：档案一致、公司平衡式与世界现金守恒、旧记录不可改写、报告只增不改，
     * 并要求活动采购/在制/未到期应收必须由持久待办推动。
     */
    private void validateManufacturing(WorldState candidate, WorldState previous) throws IOException {
        ManufacturingRun run = candidate.manufacturing();
        if (expectedSchema() < STATE_SCHEMA_MANUFACTURING) {
            if (run != null) {
                throw new IOException("旧档案不得包含制造子集状态");
            }
            return;
        }
        if (run == null) {
            throw new IOException("公司档案缺少制造子集状态");
        }
        if (!run.symbol().equals(manufacturingConfig.symbol())) {
            throw new IOException("制造子集证券与开局配置不一致：" + run.symbol());
        }
        if (!run.ledger().opening().equals(manufacturingConfig.accounting())) {
            throw new IOException("制造子集账户绑定与开局配置不一致");
        }
        try {
            ManufacturingModel.validate(run.ledger(), candidate.accounts());
        } catch (RuntimeException invalid) {
            throw new IOException("制造账不平衡：" + invalid.getMessage(), invalid);
        }
        for (ReportEntry entry : run.reports().values()) {
            if (entry.position() > candidate.position()) {
                throw new IOException("制造报告出现在未来的提交位置：" + entry.taskId());
            }
            if (entry.timeMillis() > candidate.timeMillis()) {
                throw new IOException("制造报告时点晚于快照时点：" + entry.taskId());
            }
            if (!candidate.usedTaskIds().contains(entry.taskId())) {
                throw new IOException("制造报告缺少对应的已用任务：" + entry.taskId());
            }
        }
        validateManufacturingPending(candidate, run.ledger());
        if (previous == null || previous.manufacturing() == null) {
            return;
        }
        ManufacturingModel.State before = previous.manufacturing().ledger();
        ManufacturingModel.State now = run.ledger();
        if (!now.opening().equals(before.opening())) {
            throw new IOException("制造开局账户绑定被改写");
        }
        if (now.initialEquity().compareTo(before.initialEquity()) != 0
                || now.initialWorldCash().compareTo(before.initialWorldCash()) != 0) {
            throw new IOException("制造初始权益或初始世界现金被改写");
        }
        if (now.equipment().originalCost().compareTo(before.equipment().originalCost()) != 0) {
            throw new IOException("设备原值被改写");
        }
        if (now.equipment().accumulatedDepreciation()
                .compareTo(before.equipment().accumulatedDepreciation()) < 0) {
            throw new IOException("设备累计折旧回退");
        }
        if (!now.usedIds().containsAll(before.usedIds())) {
            throw new IOException("制造已用标识丢失");
        }
        for (ManufacturingModel.PurchaseOrder old : before.purchaseOrders().values()) {
            ManufacturingModel.PurchaseOrder updated = now.purchaseOrders().get(
                    old.purchaseOrderId());
            if (updated == null) {
                throw new IOException("制造旧采购单被删除：" + old.purchaseOrderId());
            }
            if (updated.quantity() != old.quantity()
                    || updated.unitPrice().compareTo(old.unitPrice()) != 0
                    || updated.orderedAt() != old.orderedAt()
                    || updated.arrivalAt() != old.arrivalAt()) {
                throw new IOException("制造旧采购单原始内容被改写：" + old.purchaseOrderId());
            }
            if (old.status() != ManufacturingModel.PurchaseOrderStatus.ORDERED
                    && updated.status() != old.status()) {
                throw new IOException("制造终态采购单被改写：" + old.purchaseOrderId());
            }
        }
        for (ManufacturingModel.ProductionBatch old : before.productionBatches().values()) {
            ManufacturingModel.ProductionBatch updated = now.productionBatches().get(old.batchId());
            if (updated == null) {
                throw new IOException("制造旧生产批次被删除：" + old.batchId());
            }
            if (updated.quantity() != old.quantity() || updated.startedAt() != old.startedAt()
                    || updated.completesAt() != old.completesAt()
                    || updated.materialCost().compareTo(old.materialCost()) != 0
                    || updated.laborCost().compareTo(old.laborCost()) != 0) {
                throw new IOException("制造旧生产批次成本或时点被改写：" + old.batchId());
            }
            if (old.status() != ManufacturingModel.ProductionStatus.IN_PROGRESS
                    && updated.status() != old.status()) {
                throw new IOException("制造已完工批次被改写：" + old.batchId());
            }
        }
        for (ManufacturingModel.SalesContract old : before.contracts().values()) {
            ManufacturingModel.SalesContract updated = now.contracts().get(old.contractId());
            if (updated == null) {
                throw new IOException("制造旧合同被删除：" + old.contractId());
            }
            if (updated.quantity() != old.quantity()
                    || updated.unitPrice().compareTo(old.unitPrice()) != 0
                    || updated.signedAt() != old.signedAt()
                    || updated.promisedDeliveryAt() != old.promisedDeliveryAt()
                    || updated.period() != old.period()) {
                throw new IOException("制造旧合同原始内容被改写：" + old.contractId());
            }
            if (updated.deliveredQuantity() < old.deliveredQuantity()) {
                throw new IOException("制造合同已交付数量回退：" + old.contractId());
            }
        }
        for (ManufacturingModel.Receivable old : before.receivables().values()) {
            ManufacturingModel.Receivable updated = now.receivables().get(old.receivableId());
            if (updated == null) {
                throw new IOException("制造旧应收被删除：" + old.receivableId());
            }
            if (updated.quantity() != old.quantity()
                    || updated.amount().compareTo(old.amount()) != 0
                    || updated.cost().compareTo(old.cost()) != 0
                    || updated.recognizedAt() != old.recognizedAt()
                    || updated.dueAt() != old.dueAt()
                    || updated.period() != old.period()) {
                throw new IOException("制造旧应收原始内容被改写：" + old.receivableId());
            }
            if (updated.outstanding().compareTo(old.outstanding()) > 0) {
                throw new IOException("制造应收未收金额被增加：" + old.receivableId());
            }
        }
        for (ManufacturingModel.ExpenseBill old : before.expenseBills().values()) {
            ManufacturingModel.ExpenseBill updated = now.expenseBills().get(old.billId());
            if (updated == null) {
                throw new IOException("制造旧费用账单被删除：" + old.billId());
            }
            if (updated.period() != old.period()
                    || updated.amount().compareTo(old.amount()) != 0
                    || updated.closedAt() != old.closedAt()) {
                throw new IOException("制造旧费用账单原始内容被改写：" + old.billId());
            }
            if (updated.paid().compareTo(old.paid()) < 0) {
                throw new IOException("制造费用账单已付金额回退：" + old.billId());
            }
        }
        for (ReportEntry old : previous.manufacturing().reports().values()) {
            ReportEntry updated = run.reports().get(old.taskId());
            if (!old.equals(updated)) {
                throw new IOException("制造旧报告被删除或改写：" + old.taskId());
            }
        }
    }

    /**
     * 活动业务必须有持久待办推动：ORDERED 采购要有到货任务，在制批次要有完工任务，
     * 未到期应收要有自动回款任务，且始终只有一个下一期关期任务。
     */
    private void validateManufacturingPending(WorldState candidate, ManufacturingModel.State ledger)
            throws IOException {
        Set<String> receipts = new LinkedHashSet<>();
        Set<String> finishes = new LinkedHashSet<>();
        Set<String> collections = new LinkedHashSet<>();
        List<Long> closes = new ArrayList<>();
        for (PendingTask task : candidate.pending()) {
            if (task.kind().equals(PendingTask.MANUFACTURING_PERIOD_CLOSE_V1)) {
                try {
                    closes.add(mapper.readValue(task.payload(), ManufacturingChildPayload.class)
                            .period());
                } catch (IOException invalid) {
                    throw new IOException("关期任务参数不可解析：" + task.taskId(), invalid);
                }
            } else if (task.kind().equals(PendingTask.MANUFACTURING_GOODS_RECEIPT_V1)
                    || task.kind().equals(PendingTask.MANUFACTURING_BATCH_COMPLETION_V1)
                    || task.kind().equals(PendingTask.MANUFACTURING_AUTO_COLLECTION_V1)) {
                ManufacturingChildPayload payload;
                try {
                    payload = mapper.readValue(task.payload(), ManufacturingChildPayload.class);
                } catch (IOException invalid) {
                    throw new IOException("制造子任务参数不可解析：" + task.taskId(), invalid);
                }
                if (task.kind().equals(PendingTask.MANUFACTURING_GOODS_RECEIPT_V1)) {
                    receipts.add(payload.businessId());
                } else if (task.kind().equals(PendingTask.MANUFACTURING_BATCH_COMPLETION_V1)) {
                    finishes.add(payload.businessId());
                } else {
                    collections.add(payload.businessId());
                }
            }
        }
        for (ManufacturingModel.PurchaseOrder order : ledger.purchaseOrders().values()) {
            if (order.status() == ManufacturingModel.PurchaseOrderStatus.ORDERED
                    && !receipts.contains(order.purchaseOrderId())) {
                throw new IOException("活动采购单没有到货待办：" + order.purchaseOrderId());
            }
        }
        for (ManufacturingModel.ProductionBatch batch : ledger.productionBatches().values()) {
            if (batch.status() == ManufacturingModel.ProductionStatus.IN_PROGRESS
                    && !finishes.contains(batch.batchId())) {
                throw new IOException("在制批次没有完工待办：" + batch.batchId());
            }
        }
        for (ManufacturingModel.Receivable receivable : ledger.receivables().values()) {
            if (receivable.status() == ManufacturingModel.ReceivableStatus.OPEN
                    && !collections.contains(receivable.receivableId())) {
                throw new IOException("未到期应收没有自动回款待办：" + receivable.receivableId());
            }
        }
        if (closes.size() != 1 || closes.get(0) != ledger.closedPeriods()) {
            throw new IOException("必须恰好有一个下一期关期待办，收到 " + closes
                    + "，已关 " + ledger.closedPeriods());
        }
    }

    /**
     * 校验治理子集：章程一致、回报只增不改、期间证据不被未来快照覆盖、会议原文不被改写，
     * 并把每一条预算承诺与制造原始业务记录/实际付款交叉核对。
     */
    private void validateGovernance(WorldState candidate, WorldState previous) throws IOException {
        BoardRun run = candidate.governance();
        if (expectedSchema() < STATE_SCHEMA_GOVERNANCE) {
            if (run != null) {
                throw new IOException("旧档案不得包含治理子集状态");
            }
            return;
        }
        if (run == null) {
            throw new IOException("治理档案缺少董事会子集状态");
        }
        if (charter == null || !charter.equals(run.board().charter())) {
            throw new IOException("董事会章程与开局绑定不一致");
        }
        for (BoardModel.Meeting meeting : run.board().meetings().values()) {
            if (expectedSchema() <= STATE_SCHEMA_DECISIONS
                    && meeting.topic() == BoardModel.Topic.DIVIDEND_AUTHORIZATION) {
                throw new IOException("旧规则档案不得包含分红治理议题：" + meeting.meetingId());
            }
        }
        for (BoardRun.Report report : run.reports().values()) {
            if (report.position() > candidate.position()) {
                throw new IOException("治理结果出现在未来的提交位置：" + report.taskId());
            }
            if (report.timeMillis() > candidate.timeMillis()) {
                throw new IOException("治理结果时点晚于快照时点：" + report.taskId());
            }
            if (!candidate.usedTaskIds().contains(report.taskId())) {
                throw new IOException("治理结果缺少对应的已用任务：" + report.taskId());
            }
        }
        for (BoardRun.PeriodEvidence evidence : run.periodEvidence().values()) {
            if (evidence.capturedAt() > candidate.timeMillis()) {
                throw new IOException("期间证据时点晚于快照时点：" + evidence.period());
            }
            if (evidence.position() > candidate.position()) {
                throw new IOException("期间证据出现在未来的提交位置：" + evidence.period());
            }
            BoardModel.Meeting meeting = run.board().meeting(evidence.reviewMeetingId());
            if (meeting == null || meeting.topic() != BoardModel.Topic.OPERATING_REVIEW) {
                throw new IOException("期间证据缺少对应复盘会议：" + evidence.period());
            }
        }
        validateBudgetAgainstLedger(run.board(), candidate.manufacturing());
        if (previous == null || previous.governance() == null) {
            return;
        }
        BoardRun before = previous.governance();
        for (BoardRun.Report older : before.reports().values()) {
            BoardRun.Report now = run.reports().get(older.taskId());
            if (!older.equals(now)) {
                throw new IOException("治理旧回报被删除或改写：" + older.taskId());
            }
        }
        for (BoardRun.PeriodEvidence older : before.periodEvidence().values()) {
            BoardRun.PeriodEvidence now = run.periodEvidence().get(older.period());
            if (!older.equals(now)) {
                throw new IOException("期间证据被后续快照改写：" + older.period());
            }
        }
        for (BoardModel.ReviewResult older : before.board().reviews()) {
            if (!run.board().reviews().contains(older)) {
                throw new IOException("旧考核结果被删除或改写：" + older.reviewId());
            }
        }
        for (BoardModel.Meeting older : before.board().meetings().values()) {
            BoardModel.Meeting now = run.board().meeting(older.meetingId());
            if (now == null) {
                throw new IOException("旧会议被删除：" + older.meetingId());
            }
            if (now.topic() != older.topic()
                    || !now.convenerSeatId().equals(older.convenerSeatId())
                    || now.createdAt() != older.createdAt()
                    || now.materialDeadline() != older.materialDeadline()
                    || now.opensAt() != older.opensAt()
                    || now.closesAt() != older.closesAt()
                    || !now.attendeeSeatIds().equals(older.attendeeSeatIds())
                    || !now.recusedSeatIds().equals(older.recusedSeatIds())) {
                throw new IOException("旧会议身份或时点被改写：" + older.meetingId());
            }
            validateMeetingRecords(candidate, older, now);
            if (!allowedMeetingTransition(older.status(), now.status())) {
                throw new IOException("会议状态倒退或非法跳转：" + older.meetingId()
                        + " " + older.status() + " -> " + now.status());
            }
            validateFailureReason(run, now);
        }
        for (BoardModel.Meeting meeting : run.board().meetings().values()) {
            validateBudgetDecision(candidate, meeting);
        }
        validateClosedPeriodBudgets(run, before, candidate);
    }

    /** 预算终态必须符合冻结提案及真实选票，不能仅凭跨帧内容相同就信任结论。 */
    private static void validateBudgetDecision(WorldState candidate, BoardModel.Meeting meeting)
            throws IOException {
        if (meeting.topic() != BoardModel.Topic.BUDGET) {
            return;
        }
        List<String> eligible = meeting.eligibleVoters();
        if (meeting.status() == BoardModel.MeetingStatus.ADJOURNED_NO_QUORUM) {
            if (eligible.size() >= BoardModel.QUORUM || meeting.proposedLines().isEmpty()
                    || candidate.timeMillis() < meeting.closesAt()) {
                throw new IOException("延期终态与法定人数不一致：" + meeting.meetingId());
            }
            return;
        }
        if (meeting.status() == BoardModel.MeetingStatus.IMPLEMENTED) {
            boolean implemented = candidate.governance().reports().values().stream()
                    .anyMatch(report -> report.meetingId().equals(meeting.meetingId())
                            && PendingTask.GOVERNANCE_IMPLEMENT_V1.equals(report.kind())
                            && report.accepted());
            if (!implemented) {
                throw new IOException("已实施会议缺少成功实施回报：" + meeting.meetingId());
            }
        }
        boolean adopted;
        switch (meeting.status()) {
            case ADOPTED, IMPLEMENTED, IMPLEMENTATION_FAILED -> adopted = true;
            case REJECTED -> adopted = false;
            default -> { return; }
        }
        int required = meeting.requestedTotal().compareTo(BoardModel.SPECIAL_BUDGET_THRESHOLD) > 0
                ? BoardModel.SPECIAL_YES_VOTES : BoardModel.ORDINARY_YES_VOTES;
        long yes = meeting.ballots().values().stream()
                .filter(ballot -> ballot.vote() == BoardModel.Vote.YES
                        && eligible.contains(ballot.seatId())).count();
        boolean passed = !meeting.proposedLines().isEmpty()
                && eligible.size() >= BoardModel.QUORUM && yes >= required;
        if (candidate.timeMillis() < meeting.closesAt() || adopted != passed
                || (!meeting.proposedLines().isEmpty() && eligible.size() < BoardModel.QUORUM)) {
            throw new IOException("表决终态与选票不一致：" + meeting.meetingId());
        }
    }

    /**
     * 跨帧保护会议原文：旧材料不可改删（CONVENED 可追加，冻结后不得追加）、已提案不可改删、
     * 旧选票不可改删（只在原 VOTING 期间合法新增），failureReason 终态不可改。
     */
    private void validateMeetingRecords(WorldState candidate, BoardModel.Meeting older,
                                        BoardModel.Meeting now) throws IOException {
        Map<String, BoardModel.MaterialRef> beforeMaterials = materialMap(older);
        Map<String, BoardModel.MaterialRef> nowMaterials = materialMap(now);
        for (Map.Entry<String, BoardModel.MaterialRef> entry : beforeMaterials.entrySet()) {
            if (!entry.getValue().equals(nowMaterials.get(entry.getKey()))) {
                throw new IOException("冻结后材料被改写或删除：" + older.meetingId()
                        + " / " + entry.getKey());
            }
        }
        if (nowMaterials.size() > beforeMaterials.size()) {
            if (older.status() != BoardModel.MeetingStatus.CONVENED
                    || now.status() != BoardModel.MeetingStatus.CONVENED) {
                throw new IOException("冻结后材料不得追加：" + older.meetingId());
            }
            if (candidate.timeMillis() > older.materialDeadline()) {
                throw new IOException("材料截止后不得追加：" + older.meetingId());
            }
        }
        if (!older.proposedLines().isEmpty()) {
            if (!older.proposedLines().equals(now.proposedLines())) {
                throw new IOException("已提案被改写或删除：" + older.meetingId());
            }
        } else if (!now.proposedLines().isEmpty()) {
            if (older.status() != BoardModel.MeetingStatus.CONVENED
                    || now.status() != BoardModel.MeetingStatus.CONVENED) {
                throw new IOException("已提案只能在 CONVENED 首次登记：" + older.meetingId());
            }
            if (candidate.timeMillis() > older.materialDeadline()) {
                throw new IOException("已提案超过材料截止：" + older.meetingId());
            }
        }
        for (Map.Entry<String, BoardModel.Ballot> entry : older.ballots().entrySet()) {
            if (!entry.getValue().equals(now.ballots().get(entry.getKey()))) {
                throw new IOException("旧选票被改写或删除：" + older.meetingId()
                        + " / " + entry.getKey());
            }
        }
        for (BoardModel.Ballot ballot : now.ballots().values()) {
            if (older.ballots().containsKey(ballot.seatId())) {
                continue;
            }
            if (older.status() != BoardModel.MeetingStatus.VOTING
                    || now.status() != BoardModel.MeetingStatus.VOTING) {
                throw new IOException("非表决期间不得新增选票：" + older.meetingId()
                        + " / " + ballot.seatId());
            }
            if (ballot.castAt() < older.opensAt() || ballot.castAt() >= older.closesAt()) {
                throw new IOException("表决窗口外不得新增选票：" + older.meetingId()
                        + " / " + ballot.seatId());
            }
            if (!older.eligibleVoters().contains(ballot.seatId())) {
                throw new IOException("无效席位不得新增选票：" + older.meetingId()
                        + " / " + ballot.seatId());
            }
        }
        if (older.failureReason() != null
                && !older.failureReason().equals(now.failureReason())) {
            throw new IOException("失败原因被改写：" + older.meetingId());
        }
    }

    /** FAILED 终态必须有同一会议的实施拒绝回报，且原因一致。 */
    private void validateFailureReason(BoardRun run, BoardModel.Meeting meeting)
            throws IOException {
        if (meeting.status() != BoardModel.MeetingStatus.IMPLEMENTATION_FAILED) {
            return;
        }
        boolean matched = false;
        for (BoardRun.Report report : run.reports().values()) {
            if (report.meetingId().equals(meeting.meetingId())
                    && PendingTask.GOVERNANCE_IMPLEMENT_V1.equals(report.kind())
                    && !report.accepted()
                    && report.reason().equals(meeting.failureReason())) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new IOException("失败原因与治理回报不一致：" + meeting.meetingId());
        }
    }

    /** 已关期间的预算上限在跨帧恢复中不得被改写或事后新增。 */
    private void validateClosedPeriodBudgets(BoardRun run, BoardRun before, WorldState candidate)
            throws IOException {
        long closed = candidate.manufacturing().ledger().closedPeriods();
        for (BoardModel.Budget oldBudget : before.board().budgets()) {
            if (oldBudget.period() >= closed) {
                continue;
            }
            BoardModel.Budget nowBudget = run.board().budget(oldBudget.period(),
                    oldBudget.category());
            if (nowBudget == null || nowBudget.cap().compareTo(oldBudget.cap()) != 0) {
                throw new IOException("已关期间预算上限被事后改写：" + oldBudget.period()
                        + "/" + oldBudget.category());
            }
        }
        for (BoardModel.Budget nowBudget : run.board().budgets()) {
            if (nowBudget.period() >= closed) {
                continue;
            }
            boolean existed = false;
            for (BoardModel.Budget oldBudget : before.board().budgets()) {
                if (oldBudget.period() == nowBudget.period()
                        && oldBudget.category() == nowBudget.category()) {
                    existed = true;
                    break;
                }
            }
            if (!existed) {
                throw new IOException("已关期间预算上限被事后新增：" + nowBudget.period()
                        + "/" + nowBudget.category());
            }
        }
    }

    private static Map<String, BoardModel.MaterialRef> materialMap(BoardModel.Meeting meeting) {
        Map<String, BoardModel.MaterialRef> materials = new LinkedHashMap<>();
        for (BoardModel.MaterialRef material : meeting.materials()) {
            materials.put(material.materialId(), material);
        }
        return materials;
    }

    /** 任一预算行指向已关期间即拒绝；未来期间不受影响。 */
    private static boolean hasClosedPeriod(List<BoardModel.BudgetLine> lines, long closedPeriods) {
        for (BoardModel.BudgetLine line : lines) {
            if (line.period() < closedPeriods) {
                return true;
            }
        }
        return false;
    }

    /** 会议状态只能按档案定义的单向路径推进，终态不可回退。 */
    private static boolean allowedMeetingTransition(BoardModel.MeetingStatus before,
                                                    BoardModel.MeetingStatus now) {
        if (before == now) {
            return true;
        }
        return switch (before) {
            case CONVENED -> true;
            case MATERIALS_FROZEN -> now == BoardModel.MeetingStatus.VOTING
                    || now == BoardModel.MeetingStatus.REVIEWED
                    || now == BoardModel.MeetingStatus.REJECTED
                    || now == BoardModel.MeetingStatus.ADJOURNED_NO_QUORUM;
            case VOTING -> now == BoardModel.MeetingStatus.ADOPTED
                    || now == BoardModel.MeetingStatus.REJECTED
                    || now == BoardModel.MeetingStatus.ADJOURNED_NO_QUORUM;
            case ADOPTED -> now == BoardModel.MeetingStatus.IMPLEMENTED
                    || now == BoardModel.MeetingStatus.IMPLEMENTATION_FAILED;
            default -> false;
        };
    }

    /**
     * 每条采购/生产承诺必须与制造原始业务记录、数量和实际付款一致：
     * 采购按订单单价×数量，投产按数量×人工单价；未付款不能标已花，失败必须全额释放，
     * COGS、固定费用与回款不得再占一次预算。
     */
    private void validateBudgetAgainstLedger(BoardModel.State board, ManufacturingRun run)
            throws IOException {
        if (run == null) {
            throw new IOException("治理档案缺少制造台账");
        }
        ManufacturingModel.State ledger = run.ledger();
        Map<String, BoardModel.Commitment> commitments = new LinkedHashMap<>();
        for (BoardModel.Commitment commitment : board.commitments()) {
            commitments.put(commitment.commitmentId(), commitment);
        }
        Set<String> expected = new LinkedHashSet<>();
        for (ManufacturingModel.PurchaseOrder order : ledger.purchaseOrders().values()) {
            String id = "po:" + order.purchaseOrderId();
            expected.add(id);
            BoardModel.Commitment commitment = commitments.get(id);
            if (commitment == null) {
                throw new IOException("采购单没有预算承诺：" + order.purchaseOrderId());
            }
            BigDecimal amount = CashLedger.money(order.unitPrice()
                    .multiply(BigDecimal.valueOf(order.quantity())));
            if (commitment.category() != BoardModel.BudgetCategory.PROCUREMENT
                    || commitment.period() != order.orderedAt() / ManufacturingModel.PERIOD_MILLIS
                    || commitment.reserved().compareTo(amount) != 0) {
                throw new IOException("采购预算承诺与原始订单不一致：" + order.purchaseOrderId());
            }
            switch (order.status()) {
                case ORDERED -> {
                    if (commitment.status() != BoardModel.CommitmentStatus.ACTIVE
                            || commitment.spent().signum() != 0
                            || commitment.released().signum() != 0) {
                        throw new IOException("未到货采购不得标已花或释放：" + order.purchaseOrderId());
                    }
                }
                case RECEIVED -> {
                    if (commitment.spent().compareTo(amount) != 0
                            || commitment.released().signum() != 0) {
                        throw new IOException("已到货采购必须把原承诺全部转已花："
                                + order.purchaseOrderId());
                    }
                }
                case FAILED_NO_CASH -> {
                    if (commitment.released().compareTo(amount) != 0
                            || commitment.spent().signum() != 0) {
                        throw new IOException("失败到货必须全额释放原承诺："
                                + order.purchaseOrderId());
                    }
                }
            }
        }
        for (ManufacturingModel.ProductionBatch batch : ledger.productionBatches().values()) {
            String id = "labor:" + batch.batchId();
            expected.add(id);
            BoardModel.Commitment commitment = commitments.get(id);
            if (commitment == null) {
                throw new IOException("生产批次没有人工预算承诺：" + batch.batchId());
            }
            BigDecimal amount = CashLedger.money(ManufacturingModel.LABOR_UNIT_PRICE
                    .multiply(BigDecimal.valueOf(batch.quantity())));
            if (commitment.category() != BoardModel.BudgetCategory.PRODUCTION_LABOR
                    || commitment.period() != batch.startedAt() / ManufacturingModel.PERIOD_MILLIS
                    || commitment.reserved().compareTo(amount) != 0
                    || commitment.spent().compareTo(amount) != 0
                    || commitment.released().signum() != 0) {
                throw new IOException("人工预算承诺与原始批次/付款不一致：" + batch.batchId());
            }
        }
        if (!commitments.keySet().equals(expected)) {
            throw new IOException("存在没有原始业务记录的预算承诺：" + commitments.keySet()
                    + "，期望 " + expected);
        }
        for (BoardModel.Budget budget : board.budgets()) {
            BigDecimal used = BoardModel.spentBudget(board, budget.period(), budget.category())
                    .add(BoardModel.outstandingBudget(board, budget.period(), budget.category()));
            if (budget.cap().compareTo(used) < 0) {
                throw new IOException("预算上限低于已花加活动承诺：" + budget.period()
                        + "/" + budget.category() + " cap=" + budget.cap() + " used=" + used);
            }
        }
        for (BoardModel.Commitment commitment : commitments.values()) {
            if (board.budget(commitment.period(), commitment.category()) == null) {
                throw new IOException("预算承诺没有对应上限：" + commitment.commitmentId());
            }
        }
    }

    /**
     * 校验世界订单子集：版本与档案一致、证券引用有效、旧订单/成交/结果不可改写、
     * 计数器只增、收盘不可撤销，以及 {@link WorldExchange#validate} 的结构不变量。
     */
    private void validateExchange(WorldState candidate, WorldState previous) throws IOException {
        WorldExchange.State exchange = candidate.exchange();
        if (expectedSchema() < STATE_SCHEMA_EXCHANGE) {
            if (exchange != null) {
                throw new IOException("旧档案不得包含世界订单子集状态");
            }
            return;
        }
        if (exchange == null) {
            throw new IOException("撮合档案缺少订单子集状态");
        }
        if (candidate.securities() == null) {
            throw new IOException("撮合档案缺少持仓子集状态");
        }
        WorldExchange.validate(exchange, candidate.accounts(), candidate.securities());
        for (WorldExchange.Order order : exchange.orders()) {
            if (registry.security(order.symbol()) == null) {
                throw new IOException("订单引用了未登记证券：" + order.orderId());
            }
        }
        for (OrderReport report : exchange.reports().values()) {
            if (report.position() > candidate.position()) {
                throw new IOException("订单结果出现在未来的提交位置：" + report.taskId());
            }
            if (report.timeMillis() > candidate.timeMillis()) {
                throw new IOException("订单结果时点晚于快照时点：" + report.taskId());
            }
            if (!candidate.usedTaskIds().contains(report.taskId())) {
                throw new IOException("订单结果缺少对应的已用任务：" + report.taskId());
            }
        }
        if (previous == null || previous.exchange() == null) {
            return;
        }
        WorldExchange.State before = previous.exchange();
        Map<Long, WorldExchange.Order> current = new LinkedHashMap<>();
        for (WorldExchange.Order order : exchange.orders()) {
            current.put(order.orderId(), order);
        }
        for (WorldExchange.Order old : before.orders()) {
            WorldExchange.Order now = current.get(old.orderId());
            if (now == null) {
                throw new IOException("状态旧订单被删除：" + old.orderId());
            }
            if (!now.accountId().equals(old.accountId()) || !now.symbol().equals(old.symbol())
                    || now.side() != old.side() || now.type() != old.type()
                    || now.quantity() != old.quantity() || now.sequence() != old.sequence()
                    || now.acceptedAtMillis() != old.acceptedAtMillis()
                    || !Objects.equals(now.limitPrice(), old.limitPrice())
                    || !Objects.equals(now.reservationId(), old.reservationId())) {
                throw new IOException("状态旧订单字段被改写：" + old.orderId());
            }
            if (now.filled() < old.filled()) {
                throw new IOException("状态旧订单的成交量回退：" + old.orderId());
            }
            if (old.status() != WorldExchange.OrderStatus.ACTIVE
                    && now.status() != old.status()) {
                throw new IOException("状态终态订单的状态被改写：" + old.orderId());
            }
        }
        if (exchange.trades().size() < before.trades().size()) {
            throw new IOException("状态成交明细被删除");
        }
        for (int i = 0; i < before.trades().size(); i++) {
            if (!before.trades().get(i).equals(exchange.trades().get(i))) {
                throw new IOException("状态旧成交被改写：" + before.trades().get(i).tradeId());
            }
        }
        for (OrderReport older : before.reports().values()) {
            OrderReport now = exchange.reports().get(older.taskId());
            if (now == null || !now.equals(older)) {
                throw new IOException("状态旧订单结果被删除或改写：" + older.taskId());
            }
        }
        if (before.closed() && !exchange.closed()) {
            throw new IOException("状态收盘标志被撤销");
        }
        if (exchange.nextOrderId() < before.nextOrderId()
                || exchange.nextSequence() < before.nextSequence()
                || exchange.nextTradeId() < before.nextTradeId()) {
            throw new IOException("状态订单/成交计数器回退");
        }
    }

    /**
     * 校验证券子集：版本与档案一致、引用有效、逐证券守恒、冻结不超过批次剩余，
     * 以及相对上一批次的不可逆性（报告与已用标识只增不改、批次只减）。
     */
    private void validateSecurities(WorldState candidate, WorldState previous) throws IOException {
        SecuritiesLedger.State securities = candidate.securities();
        if (expectedSchema() < STATE_SCHEMA_SECURITIES) {
            if (securities != null) {
                throw new IOException("现金/登记档案不得包含证券子集状态");
            }
            return;
        }
        if (securities == null) {
            throw new IOException("证券档案缺少持仓子集状态");
        }
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            if (!candidate.accounts().containsKey(lot.accountId())) {
                throw new IOException("持仓批次引用了未登记账户：" + lot.accountId());
            }
            if (registry.security(lot.symbol()) == null) {
                throw new IOException("持仓批次引用了未登记证券：" + lot.symbol());
            }
        }
        Map<String, Long> totals = new TreeMap<>();
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            Long previousTotal = totals.get(lot.symbol());
            try {
                totals.put(lot.symbol(), previousTotal == null ? lot.remaining()
                        : Math.addExact(previousTotal, lot.remaining()));
            } catch (ArithmeticException overflow) {
                throw new IOException("证券批次数量累计溢出：" + lot.symbol(), overflow);
            }
        }
        for (OpeningRegistry.Security security : registry.securities()) {
            Long total = totals.getOrDefault(security.symbol(), 0L);
            if (total != security.issuedShares()) {
                throw new IOException("证券总股数与已发行不一致：" + security.symbol()
                        + " 发行 " + security.issuedShares() + "，批次 " + total);
            }
        }
        if (totals.size() != registry.securities().size()) {
            throw new IOException("证券子集包含未登记的证券代码：" + totals.keySet());
        }
        for (SellReservation reservation : securities.reservations()) {
            if (!candidate.accounts().containsKey(reservation.accountId())) {
                throw new IOException("活动预留引用了未登记账户：" + reservation.accountId());
            }
        }
        for (ActionReport report : securities.reports().values()) {
            if (report.position() > candidate.position()) {
                throw new IOException("业务结果出现在未来的提交位置：" + report.taskId());
            }
            if (report.timeMillis() > candidate.timeMillis()) {
                throw new IOException("业务结果时点晚于快照时点：" + report.taskId());
            }
            if (!candidate.usedTaskIds().contains(report.taskId())) {
                throw new IOException("业务结果缺少对应的已用任务：" + report.taskId());
            }
        }
        if (previous == null || previous.securities() == null) {
            return;
        }
        SecuritiesLedger.State before = previous.securities();
        for (ActionReport older : before.reports().values()) {
            ActionReport now = securities.reports().get(older.taskId());
            if (now == null || !now.equals(older)) {
                throw new IOException("状态旧证券结果被删除或改写：" + older.taskId());
            }
        }
        if (!securities.usedReservationIds().containsAll(before.usedReservationIds())) {
            throw new IOException("状态已用预留标识丢失");
        }
        Map<String, Long> beforeTotals = new TreeMap<>();
        for (SecuritiesLedger.Lot lot : before.lots()) {
            Long sum = beforeTotals.get(lot.symbol());
            try {
                beforeTotals.put(lot.symbol(), sum == null ? lot.remaining()
                        : Math.addExact(sum, lot.remaining()));
            } catch (ArithmeticException overflow) {
                throw new IOException("恢复的证券批次数量累计溢出：" + lot.symbol(), overflow);
            }
        }
        if (!beforeTotals.equals(totals)) {
            throw new IOException("证券总股数在同一运行内改变");
        }
        Map<String, SecuritiesLedger.Lot> current = new LinkedHashMap<>();
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            current.put(lot.lotId(), lot);
        }
        for (SecuritiesLedger.Lot lot : before.lots()) {
            SecuritiesLedger.Lot now = current.get(lot.lotId());
            if (now == null) {
                continue;
            }
            if (!now.accountId().equals(lot.accountId()) || !now.symbol().equals(lot.symbol())
                    || now.eligibleFromDay() != lot.eligibleFromDay()
                    || now.acquiredAtMillis() != lot.acquiredAtMillis()
                    || now.acquiredPosition() != lot.acquiredPosition()
                    || now.costPrice().compareTo(lot.costPrice()) != 0
                    || now.classification() != lot.classification()) {
                throw new IOException("持仓批次的归属或成本被改写：" + lot.lotId());
            }
            if (now.remaining() > lot.remaining()) {
                throw new IOException("持仓批次的剩余数量被增加：" + lot.lotId());
            }
        }
    }

    private WorldState restore(List<OpeningAccount> ordered, WorldState expectedInitial)
            throws IOException {
        List<WorldCommit> commits = journal.commits();
        String expected = openingContent(ordered);
        WorldCommit first = commits.get(0);
        if (!OPEN_COMMAND.equals(first.commandId()) || !expected.equals(first.commandContent())
                || !"OPENED".equals(first.result())) {
            throw new IOException("运行清单或开局现金与持久记录不一致");
        }
        WorldState previous = null;
        for (WorldCommit commit : commits) {
            WorldState snapshot = decodeSnapshot(commit);
            validate(snapshot, commit.position(), previous);
            validateAudits(commit, snapshot, previous);
            if (previous == null && !expectedInitial.equals(snapshot)) {
                throw new IOException("开局快照与期望开局状态不一致（必须等于清单绑定的初始资产）");
            }
            previous = snapshot;
        }
        return previous;
    }

    /**
     * 恢复期逐批核对审计事件：位置、完整尝试身份、类别、正文摘要与声明必须与同批已确认
     * 尝试或拒绝归档形成真实关系。合法摘要不能替代语义。
     *
     * @param commit   已确认批次
     * @param snapshot 该批次的状态快照
     * @throws IOException 审计与已确认状态不一致
     */
    private void validateAudits(WorldCommit commit, WorldState snapshot, WorldState previous) throws IOException {
        for (WorldEvent event : commit.events()) {
            if (!DECISION_AUDIT_V1.equals(event.type())) {
                continue;
            }
            DecisionAudit audit;
            try {
                audit = decisionMapper.readValue(event.payload(), DecisionAudit.class);
            } catch (IOException | RuntimeException invalid) {
                throw new IOException("批次 " + commit.position() + " 的审计原文不合法", invalid);
            }
            if (audit.position() != commit.position()) {
                throw new IOException("审计位置与批次不一致：" + audit.position()
                        + "/" + commit.position());
            }
            if (!audit.runId().equals(commit.runId()) || !audit.runId().equals(snapshot.runId())
                    || !audit.outcome().equals(commit.result())) {
                throw new IOException("审计运行或结果与批次不一致：" + commit.position());
            }
            validateAuditWait(audit.dispatchedAtEpochMillis(), audit.dispatchToRecordMillis());
            validateAuditWait(audit.receivedAtEpochMillis(), audit.receiveToRecordMillis());
            if (snapshot.decisions() == null) {
                throw new IOException("无决定子集的批次出现审计：" + commit.position());
            }
            DecisionModel.Attempt attempt = attemptOf(snapshot.decisions().state(),
                    audit.actionId(), audit.attemptId());
            if (attempt == null || attempt.attemptNo() != audit.attemptNo()
                    || attempt.recoveryGeneration() != audit.recoveryGeneration()) {
                throw new IOException("审计身份无法对应已确认尝试：" + audit.attemptId());
            }
            DecisionModel.Attempt prior = previous == null || previous.decisions() == null ? null
                    : attemptOf(previous.decisions().state(), audit.actionId(), audit.attemptId());
            if (prior == null) {
                throw new IOException("审计缺少调用前已确认尝试：" + audit.attemptId());
            }
            switch (audit.kind()) {
                case "REPLY" -> {
                    if (!audit.outcome().equals("STALE_GENERATION") && prior.settledDone()) {
                        throw new IOException("已消费尝试不能再次记录首次回执：" + audit.attemptId());
                    }
                    validateReplyAudit(audit, attempt, snapshot.decisions(), previous.decisions());
                }
                case "FAILURE", "TIMEOUT" -> {
                    if (prior.settledDone()) {
                        throw new IOException("已消费尝试不能再次结算无回执失败：" + audit.attemptId());
                    }
                    validateNoReceiptAudit(audit, attempt);
                }
                case "REJECTED_IDENTITY_REUSE" -> {
                    if (!prior.settledDone()) {
                        throw new IOException("复用拒绝缺少此前已结算尝试：" + audit.attemptId());
                    }
                    validateRejectedAudit(audit, attempt, commit, snapshot);
                }
                default -> throw new IOException("未知审计类别：" + audit.kind());
            }
        }
    }

    /** 已知等待必须非负并有对应打点；负的单调差在写入时已明确转为未知。 */
    private static void validateAuditWait(Long atEpochMillis, Long waitMillis) throws IOException {
        if (waitMillis != null && (waitMillis < 0 || atEpochMillis == null)) {
            throw new IOException("审计等待量缺少对应时点或为负");
        }
    }

    /** 回执审计：正常回执以摘要关联原尝试；旧代次以摘要关联同批归档原文。 */
    private void validateReplyAudit(DecisionAudit audit, DecisionModel.Attempt attempt,
                                    DecisionRun run, DecisionRun before) throws IOException {
        if ("STALE_GENERATION".equals(audit.outcome())) {
            if (!audit.body().isEmpty()) {
                throw new IOException("旧代次审计不得保存正文：" + audit.attemptId());
            }
            boolean archived = false;
            for (DecisionRun.Archive archive : run.archives()) {
                if (archive.actionId().equals(audit.actionId())
                        && archive.attemptId().equals(audit.attemptId())
                        && archive.attemptNo() == audit.attemptNo()
                        && archive.generation() == audit.recoveryGeneration()
                        && sha256Hex(archive.body()).equals(audit.bodySha256())
                        && Objects.equals(archive.usage(), audit.declaredUsage())
                        && archive.usageSource().equals(audit.usageSource())) {
                    if (before.archives().contains(archive)) {
                        throw new IOException("旧回执归档不能在后续批次重复冒充新增：" + audit.attemptId());
                    }
                    archived = true;
                }
            }
            if (!archived) {
                throw new IOException("旧代次审计缺少对应归档原文：" + audit.attemptId());
            }
            return;
        }
        if (!attempt.settledDone() || !attempt.replyReceived()) {
            throw new IOException("回执审计缺少已结算原尝试：" + audit.attemptId());
        }
        if (!audit.body().isEmpty()) {
            throw new IOException("正常回执审计不得重复保存正文：" + audit.attemptId());
        }
        if (!sha256Hex(attempt.replyBody()).equals(audit.bodySha256())) {
            throw new IOException("审计正文摘要与原尝试不一致：" + audit.attemptId());
        }
        if (!Objects.equals(audit.declaredUsage(), attempt.declaredUsage())
                || !audit.usageSource().equals(attempt.usageSource())) {
            throw new IOException("审计声明用量或来源与原尝试不一致：" + audit.attemptId());
        }
    }

    /** 无回执失败或超时：不得出现接收时点或接收等待量。 */
    private void validateNoReceiptAudit(DecisionAudit audit, DecisionModel.Attempt attempt)
            throws IOException {
        if (!attempt.settledDone() || attempt.replyReceived()) {
            throw new IOException("无回执审计与原尝试不一致：" + audit.attemptId());
        }
        if (!audit.body().isEmpty() || !audit.bodySha256().isEmpty()
                || audit.receivedAtEpochMillis() != null
                || audit.receiveToRecordMillis() != null) {
            throw new IOException("无回执审计不得带正文或接收时间：" + audit.attemptId());
        }
        if ("FAILURE".equals(audit.kind()) && !audit.outcome().startsWith("TECHNICAL_FAILED:")) {
            throw new IOException("失败审计结果码不合法：" + audit.outcome());
        }
        if ("TIMEOUT".equals(audit.kind()) && !"TIMEOUT".equals(audit.outcome())) {
            throw new IOException("超时审计结果码不合法：" + audit.outcome());
        }
    }

    /** 已消费身份复用：原文摘要必须自洽，且语义上不得与原回执完全相同。 */
    private void validateRejectedAudit(DecisionAudit audit, DecisionModel.Attempt attempt,
                                       WorldCommit commit, WorldState snapshot)
            throws IOException {
        if (!attempt.settledDone()) {
            throw new IOException("复用拒绝缺少已结算原尝试：" + audit.attemptId());
        }
        if (!sha256Hex(audit.body()).equals(audit.bodySha256())) {
            throw new IOException("复用拒绝正文摘要不自洽：" + audit.attemptId());
        }
        if (!"REJECTED:IDENTITY_REUSE".equals(audit.outcome())) {
            throw new IOException("复用拒绝结果码不合法：" + audit.outcome());
        }
        boolean sameBody = attempt.replyReceived() && attempt.replyBody().equals(audit.body());
        boolean sameUsage = Objects.equals(attempt.declaredUsage(), audit.declaredUsage());
        boolean sameSource = attempt.usageSource().equals(audit.usageSource());
        if (sameBody && sameUsage && sameSource) {
            throw new IOException("复用拒绝与原回执完全相同，应作为重复回执处理："
                    + audit.attemptId());
        }
        String content = mapper.writeValueAsString(new DecisionModel.Reply(audit.runId(),
                audit.actionId(), audit.attemptId(), audit.attemptNo(), audit.recoveryGeneration(),
                audit.body(), audit.declaredUsage(), audit.usageSource()));
        String rejectId = "dec-reject:" + sha256Hex(content);
        if (!content.equals(commit.commandContent())
                || !content.equals(snapshot.commandContents().get(commit.commandId()))
                || !content.equals(snapshot.commandContents().get(rejectId))
                || !audit.outcome().equals(snapshot.commandOutcomes().get(rejectId))) {
            throw new IOException("拒绝审计与完整原回执或宿主请求不一致：" + audit.attemptId());
        }
    }

    private WorldState decodeSnapshot(WorldCommit commit) throws IOException {
        WorldState found = null;
        List<ActionReport> reported = new ArrayList<>();
        List<OrderReport> exchangeReports = new ArrayList<>();
        List<ReportEntry> manufacturingReports = new ArrayList<>();
        List<BoardRun.Report> governanceReports = new ArrayList<>();
        List<BoardRun.PeriodEvidence> periodEvidence = new ArrayList<>();
        List<RoleRun.Report> roleReports = new ArrayList<>();
        List<InformationRun.Report> informationReports = new ArrayList<>();
        List<InformationModel.ReadObservation> informationObservations = new ArrayList<>();
        for (WorldEvent event : commit.events()) {
            if (SNAPSHOT_TYPE.equals(event.type())) {
                if (found != null) {
                    throw new IOException("批次 " + commit.position() + " 含多个状态快照");
                }
                ObjectMapper snapshotMapper = manifest.hasDecisions() ? decisionMapper : mapper;
                found = snapshotMapper.readValue(event.payload(), WorldState.class);
            } else if ("SECURITIES_REPORT_V1".equals(event.type())) {
                reported.add(mapper.readValue(event.payload(), ActionReport.class));
            } else if ("EXCHANGE_ORDER_REPORT_V1".equals(event.type())) {
                exchangeReports.add(mapper.readValue(event.payload(), OrderReport.class));
            } else if ("MANUFACTURING_REPORT_V1".equals(event.type())) {
                manufacturingReports.add(mapper.readValue(event.payload(), ReportEntry.class));
            } else if ("GOVERNANCE_REPORT_V1".equals(event.type())) {
                governanceReports.add(mapper.readValue(event.payload(), BoardRun.Report.class));
            } else if ("PERIOD_EVIDENCE_V1".equals(event.type())) {
                periodEvidence.add(mapper.readValue(event.payload(),
                        BoardRun.PeriodEvidence.class));
            } else if ("ROLE_REPORT_V1".equals(event.type())) {
                roleReports.add(mapper.readValue(event.payload(), RoleRun.Report.class));
            } else if ("INFORMATION_REPORT_V1".equals(event.type())) {
                informationReports.add(mapper.readValue(event.payload(),
                        InformationRun.Report.class));
            } else if ("INFORMATION_OBSERVATION_V1".equals(event.type())) {
                informationObservations.add(mapper.readValue(event.payload(),
                        InformationModel.ReadObservation.class));
            }
        }
        if (found == null) {
            throw new IOException("批次 " + commit.position() + " 缺少状态快照");
        }
        // 批次内声明的结果事件必须在快照中存在且一致；旧结果由状态链校验保护。
        for (ActionReport report : reported) {
            ActionReport stored = found.securities() == null ? null
                    : found.securities().reports().get(report.taskId());
            if (!report.equals(stored)) {
                throw new IOException("批次 " + commit.position()
                        + " 的证券结果与状态快照不一致：" + report.taskId());
            }
        }
        for (OrderReport report : exchangeReports) {
            OrderReport stored = found.exchange() == null ? null
                    : found.exchange().reports().get(report.taskId());
            if (!report.equals(stored)) {
                throw new IOException("批次 " + commit.position()
                        + " 的订单结果与状态快照不一致：" + report.taskId());
            }
        }
        for (ReportEntry report : manufacturingReports) {
            ReportEntry stored = found.manufacturing() == null ? null
                    : found.manufacturing().reports().get(report.taskId());
            if (!report.equals(stored)) {
                throw new IOException("批次 " + commit.position()
                        + " 的制造结果与状态快照不一致：" + report.taskId());
            }
        }
        for (BoardRun.Report report : governanceReports) {
            BoardRun.Report stored = found.governance() == null ? null
                    : found.governance().reports().get(report.taskId());
            if (!report.equals(stored)) {
                throw new IOException("批次 " + commit.position()
                        + " 的治理结果与状态快照不一致：" + report.taskId());
            }
        }
        for (BoardRun.PeriodEvidence evidence : periodEvidence) {
            BoardRun.PeriodEvidence stored = found.governance() == null ? null
                    : found.governance().periodEvidence().get(evidence.period());
            if (!evidence.equals(stored)) {
                throw new IOException("批次 " + commit.position()
                        + " 的期间证据与状态快照不一致：" + evidence.period());
            }
        }
        for (RoleRun.Report report : roleReports) {
            RoleRun.Report stored = found.roles() == null ? null
                    : found.roles().reports().get(report.taskId());
            if (!report.equals(stored)) {
                throw new IOException("批次 " + commit.position()
                        + " 的任职结果与状态快照不一致：" + report.taskId());
            }
        }
        for (InformationRun.Report report : informationReports) {
            InformationRun.Report stored = found.information() == null ? null
                    : found.information().reports().get(report.taskId());
            if (!report.equals(stored)) {
                throw new IOException("批次 " + commit.position()
                        + " 的阅读结果与状态快照不一致：" + report.taskId());
            }
        }
        for (InformationModel.ReadObservation observation : informationObservations) {
            boolean stored = found.information() != null
                    && found.information().information().observations().contains(observation);
            if (!stored) {
                throw new IOException("批次 " + commit.position()
                        + " 的阅读经历与状态快照不一致：" + observation.readId());
            }
        }
        return found;
    }

    private WorldEvent snapshotEvent(WorldState snapshot) throws IOException {
        return new WorldEvent("state:" + snapshot.position(), SNAPSHOT_TYPE,
                mapper.writeValueAsString(snapshot));
    }

    private String openingContent(List<OpeningAccount> ordered) throws IOException {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("manifest", manifest);
        content.put("openings", ordered);
        if (registry != null) {
            // 只有登记模式才加这个键：旧现金日志的开局内容保持逐字节不变
            content.put("registry", registry);
        }
        if (manufacturingConfig != null) {
            // 只有公司档案才加这个键：旧档案的开局内容保持逐字节不变
            content.put("manufacturing", manufacturingConfig);
        }
        if (charter != null) {
            // 只有治理档案才加这个键：旧档案的开局内容保持逐字节不变
            content.put("governance", charter);
        }
        if (roleConfig != null) {
            // 只有任职/阅读档案才加这两个键：旧档案的开局内容保持逐字节不变
            content.put("roles", roleConfig);
        }
        if (informationConfig != null) {
            content.put("information", informationConfig);
        }
        return mapper.writeValueAsString(content);
    }

    private static BigDecimal total(WorldState state) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CashLedger.CashAccount account : state.accounts().values()) {
            sum = sum.add(account.cash());
        }
        return sum;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("运行已关闭");
        }
    }

    /**
     * 排程与推进只能由创建本运行的线程执行；文件/网络线程不得改变世界顺序。
     */
    private void requireWriter() {
        if (Thread.currentThread() != writerThread) {
            throw new IllegalStateException("只有创建运行的世界写入线程可以排程或推进");
        }
    }

    /** 暂停的运行不接受新排程，必须先恢复。 */
    private void requireNotPaused() {
        if (paused) {
            throw new IllegalStateException("运行已暂停，不接受新排程；须先恢复");
        }
    }

    /** 新待办的到期必须晚于当前时点，且任务标识运行内不复用。 */
    private void requireSchedulable(long dueTimeMillis, String taskId) {
        if (dueTimeMillis <= state.timeMillis()) {
            throw new IllegalArgumentException("到期时间必须晚于当前时点：" + state.timeMillis());
        }
        if (state.usedTaskIds().contains(taskId)) {
            throw new IllegalArgumentException("任务标识运行内不复用：" + taskId);
        }
    }

    /**
     * 普通付款的候选结果。
     *
     * <p>撮合档案下必须按<b>可用现金</b>（总余额减活动买单冻结）复核，不能花掉挂单冻结；
     * 这一口径对所有后续经营付款同样适用。旧档案仍按原余额口径，保持原行为。</p>
     */
    private CashLedger.TransferOutcome transferOutcome(TransferPayload payload) {
        if (manufacturingConfig != null) {
            String company = manufacturingConfig.accounting().companyAccountId();
            if (company.equals(payload.fromId()) || company.equals(payload.toId())) {
                // 到期防御复核：不把公司现金绕出经营分录
                return new CashLedger.TransferOutcome(false, REASON_COMPANY_LEDGER_FORBIDDEN,
                        state.accounts());
            }
        }
        if (manifest.hasMatching() && state.exchange() != null) {
            CashLedger.CashAccount from = state.accounts().get(payload.fromId());
            if (from != null && from.ownerId().equals(payload.actorOwnerId())) {
                BigDecimal free = WorldExchange.freeCash(state.exchange(), state.accounts(),
                        payload.fromId());
                if (free.compareTo(payload.amount()) < 0) {
                    return new CashLedger.TransferOutcome(false, REASON_INSUFFICIENT_CASH,
                            state.accounts());
                }
            }
        }
        return CashLedger.computeTransfer(state.accounts(), payload.actorOwnerId(),
                payload.fromId(), payload.toId(), payload.amount());
    }

    /** 证券清算接口只对声明了 securities 模块的运行开放。 */
    private void requireSecuritiesEnabled() {
        if (!manifest.hasSecurities()) {
            throw new IllegalStateException("当前运行未启用证券清算模块：" + manifest.rulesVersion());
        }
        if (state.securities() == null) {
            throw new IllegalStateException("当前运行没有证券子集状态");
        }
    }

    /**
     * 清算夹具接口只对证券清算档案开放；撮合档案必须走订单簿，不能绕过订单改预留。
     */
    private void requireClearingFixture() {
        if (manifest.hasMatching()) {
            throw new IllegalStateException("撮合档案禁用证券清算夹具，请使用世界订单接口");
        }
        requireSecuritiesEnabled();
    }

    /** 世界订单接口只对声明了 matching 模块的运行开放。 */
    private void requireMatchingEnabled() {
        if (!manifest.hasMatching()) {
            throw new IllegalStateException("当前运行未启用世界订单撮合模块："
                    + manifest.rulesVersion());
        }
        if (state.exchange() == null) {
            throw new IllegalStateException("当前运行没有订单子集状态");
        }
    }

    /** 制造经营接口只对 stock-company-experiment-v1 开放。 */
    private void requireManufacturingEnabled() {
        if (!manifest.hasManufacturing() || manufacturingConfig == null) {
            throw new IllegalStateException("当前运行未启用制造经营模块：" + manifest.rulesVersion());
        }
        if (state.manufacturing() == null) {
            throw new IllegalStateException("当前运行没有制造子集状态");
        }
    }

    /** 治理接口只对 governed-stock-company-experiment-v1 开放。 */
    private void requireGovernanceEnabled() {
        if (!manifest.hasGovernance() || charter == null) {
            throw new IllegalStateException("当前运行未启用董事会治理模块："
                    + manifest.rulesVersion());
        }
        if (state.governance() == null) {
            throw new IllegalStateException("当前运行没有治理子集状态");
        }
    }

    /** 已知后续耗时必须在排程前确认不溢出，避免任务到期后永久卡住。 */
    private static void requireHorizon(long dueTimeMillis, long deltaMillis, String what) {
        try {
            Math.addExact(dueTimeMillis, deltaMillis);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("到期时间加 " + what + " 会溢出，无法安排后续动作", overflow);
        }
    }

    /**
     * 任职与阅读开局：在创建任何日志文件之前完成全部来源校验。
     *
     * <p>登记读者必须是已登记 PERSON；产品管理授权与初始任职必须通过
     * {@link RoleAccess#open}、{@link RoleAccess#appoint} 与 {@link RoleAccess#validateAgainst}
     * 的同一口径；初始任职不得携带撤销记录（由 {@link RoleRun.Config} 构造期拒绝）。</p>
     */
    private void validateRoleReadingOpening() {
        if (registry == null || manufacturingConfig == null || charter == null) {
            throw new IllegalArgumentException("任职与阅读档案必须同时提供登记、制造配置与章程");
        }
        for (String reader : informationConfig.readers()) {
            requireParty(reader, OpeningRegistry.PartyKind.PERSON, "登记读者");
        }
        InformationModel.open(registry, informationConfig.readers());
        roleStateOf(roleConfig);
    }

    /**
     * 治理开局：章程公司必须就是制造公司所属组织、也是证券发行人；五席是登记人物。
     *
     * <p>全部在创建日志之前完成，因此失败不会留下半成品档案。</p>
     */
    private void validateGovernanceOpening() {
        if (manufacturingConfig == null) {
            throw new IllegalArgumentException("治理档案必须同时提供制造开局配置");
        }
        // BoardModel.open 校验公司是 ORGANIZATION、五席各绑定不同 PERSON、主席在席、quorum=3
        BoardModel.open(registry, charter);
        String companyParty = charter.companyPartyId();
        OpeningRegistry.PropertyOwner owner = registry.owner(
                manufacturingConfig.accounting().companyOwnerId());
        if (owner == null || !companyParty.equals(owner.partyId())) {
            throw new IllegalArgumentException("章程公司必须就是制造公司所属组织：章程 "
                    + companyParty + "，公司组织 " + (owner == null ? "null" : owner.partyId()));
        }
        OpeningRegistry.Security security = registry.security(manufacturingConfig.symbol());
        if (security == null || !companyParty.equals(security.issuerOrganizationId())) {
            throw new IllegalArgumentException("章程公司必须就是证券发行人：章程 " + companyParty
                    + "，发行人 " + (security == null ? "null" : security.issuerOrganizationId()));
        }
    }

    /**
     * 公司档案的开局边界：组织映射、证券发行人、唯一经营账户、未建模的公司持股。
     *
     * <p>全部在创建日志之前完成，因此失败时磁盘上不会留下半成品档案。</p>
     */
    private void validateManufacturingOpening(List<OpeningAccount> ordered) {
        ManufacturingModel.Opening accounting = manufacturingConfig.accounting();
        OpeningRegistry.Security security = registry.security(manufacturingConfig.symbol());
        if (security == null) {
            throw new IllegalArgumentException("制造档案的证券未登记：" + manufacturingConfig.symbol());
        }
        OpeningRegistry.PropertyOwner owner = registry.owner(accounting.companyOwnerId());
        if (owner == null) {
            throw new IllegalArgumentException("公司财产主体未登记：" + accounting.companyOwnerId());
        }
        if (owner.kind() != OpeningRegistry.OwnerKind.ORGANIZATION) {
            throw new IllegalArgumentException("公司财产主体必须登记为 ORGANIZATION："
                    + accounting.companyOwnerId() + " 是 " + owner.kind());
        }
        if (!security.issuerOrganizationId().equals(owner.partyId())) {
            throw new IllegalArgumentException("证券发行人组织必须就是公司财产主体所属组织：发行人 "
                    + security.issuerOrganizationId() + "，公司组织 " + owner.partyId());
        }
        Map<String, String> ownerOfAccount = new LinkedHashMap<>();
        for (OpeningAccount opening : ordered) {
            ownerOfAccount.put(opening.accountId(), opening.ownerId());
            if (opening.ownerId().equals(accounting.companyOwnerId())
                    && !opening.accountId().equals(accounting.companyAccountId())) {
                throw new IllegalArgumentException("公司财产主体只能有一个经营现金账户（含零余额）："
                        + opening.accountId());
            }
        }
        if (!ownerOfAccount.containsKey(accounting.companyAccountId())) {
            throw new IllegalArgumentException("缺少公司经营账户：" + accounting.companyAccountId());
        }
        if (!accounting.companyOwnerId().equals(
                ownerOfAccount.get(accounting.companyAccountId()))) {
            throw new IllegalArgumentException("公司经营账户不属于公司财产主体："
                    + accounting.companyAccountId());
        }
        for (OpeningRegistry.ShareAllocation allocation : registry.allocations()) {
            if (accounting.companyOwnerId().equals(ownerOfAccount.get(allocation.accountId()))) {
                throw new IllegalArgumentException("公司持股/库存股是未建模资产，制造档案拒绝开局："
                        + allocation.accountId() + " 持有 " + allocation.symbol());
            }
        }
    }

    /** 排程一个到期任务：待办、已用标识、命令内容与回报同一批持久提交。 */
    private String scheduleCompletionTask(PendingTask task, String commandId, String content)
            throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.add(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        Map<String, String> contents = new TreeMap<>(state.commandContents());
        contents.put(commandId, content);
        Map<String, String> outcomes = new TreeMap<>(state.commandOutcomes());
        String outcome = "SCHEDULED:" + task.taskId();
        outcomes.put(commandId, outcome);
        WorldState candidate = next(state.timeMillis(), state.lastPhase(), state.accounts(),
                pending, state.delivered(), used, contents, outcomes, state.securities(),
                state.exchange());
        commitAndApply("cmd:" + commandId, content, outcome,
                List.of(new WorldEvent("task-scheduled:" + task.taskId(), "TASK_SCHEDULED_V1",
                        mapper.writeValueAsString(task))), candidate);
        return outcome;
    }

    /**
     * 命令重放的身份校验：同一 commandId 必须对应完整同一个待办。
     *
     * <p>只比较 payload 会漏掉 taskId、到期与阶段——例如同一 payload 换一个到期重发会被静默
     * 当成原命令。这里从原提交 {@code cmd:<commandId>} 的 {@code TASK_SCHEDULED_V1} 事件反序列化
     * 完整 {@link PendingTask} 再逐字段比较，因此不需要给 {@link WorldState} 加第二份缓存，
     * 也不改旧日志的 JSON 形状（旧 schema5 日志同样留有该事件，回读不受影响）。</p>
     *
     * @param commandId     外部命令标识
     * @param taskId        本次请求的任务标识
     * @param dueTimeMillis 本次请求的到期
     * @param phase         本次请求的阶段
     * @param kind          本次请求的任务种类
     * @param payloadJson   本次请求的规范化参数
     * @throws IllegalArgumentException 与首次排程不同
     * @throws IOException              原始排程事件不可解析或自相矛盾
     */
    private void requireSameSchedule(String commandId, String taskId, long dueTimeMillis,
                                     WorldScheduler.Phase phase, String kind, String payloadJson)
            throws IOException {
        PendingTask original = originalSchedule(commandId);
        if (original == null) {
            throw new IOException("已登记命令缺少首次排程事件：" + commandId);
        }
        if (original.dueTimeMillis() != dueTimeMillis
                || original.phase() != phase
                || !original.kind().equals(kind)
                || !original.taskId().equals(taskId)
                || !original.payload().equals(payloadJson)) {
            throw new IllegalArgumentException("同一命令标识不能对应不同的任务、到期、阶段、种类或参数："
                    + commandId);
        }
    }

    /** 从已确认历史里取某命令首次排程的完整待办；没有对应提交返回 null。 */
    private PendingTask originalSchedule(String commandId) throws IOException {
        String target = "cmd:" + commandId;
        for (WorldCommit commit : journal.commits()) {
            if (!target.equals(commit.commandId())) {
                continue;
            }
            PendingTask found = null;
            for (WorldEvent event : commit.events()) {
                if (!"TASK_SCHEDULED_V1".equals(event.type())) {
                    continue;
                }
                PendingTask task = mapper.readValue(event.payload(), PendingTask.class);
                if (found != null && !found.equals(task)) {
                    throw new IOException("同一命令存在多个不同排程：" + commandId);
                }
                found = task;
            }
            return found;
        }
        return null;
    }

    private WorldState initialState(Map<String, CashLedger.CashAccount> accounts) throws IOException {
        ManufacturingRun manufacturing = null;
        BoardRun governance = null;
        RoleRun roles = null;
        InformationRun information = null;
        List<PendingTask> pending = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        if (manufacturingConfig != null) {
            ManufacturingModel.State ledger = ManufacturingModel.open(
                    manufacturingConfig.accounting(), accounts);
            manufacturing = new ManufacturingRun(manufacturingConfig.symbol(), ledger, Map.of());
            // 新档案开局必须自带期间 0 关期任务；否则经营费用/折旧只能靠人手漏记
            String closeCommand = SYSTEM_COMMAND_PREFIX + "close-period:0";
            ManufacturingChildPayload payload = new ManufacturingChildPayload(closeCommand, "0", 0L);
            PendingTask close = new PendingTask("mfg-close:0",
                    Math.multiplyExact(1L, ManufacturingModel.PERIOD_MILLIS),
                    WorldScheduler.Phase.RULES, PendingTask.MANUFACTURING_PERIOD_CLOSE_V1,
                    mapper.writeValueAsString(payload));
            pending.add(close);
            used.add(close.taskId());
        }
        if (charter != null) {
            governance = new BoardRun(BoardModel.open(registry, charter), Map.of(), Map.of());
        }
        if (roleConfig != null) {
            RoleAccess.State access = roleStateOf(roleConfig);
            roles = new RoleRun(access, Map.of());
        }
        if (informationConfig != null) {
            information = new InformationRun(
                    InformationModel.open(registry, informationConfig.readers()), Map.of());
        }
        RoleTradingRun trading = manifest.hasTrading() ? RoleTradingRun.opening() : null;
        DecisionRun decisions = manifest.hasDecisions()
                ? DecisionRun.opening(manifest.runId(), seed, decisionConfig) : null;
        return new WorldState(expectedSchema(), manifest.runId(), 1, 0L,
                WorldScheduler.Phase.OBSERVATION.name(), accounts, pending, List.of(), used,
                Map.of(), Map.of(),
                manifest.hasSecurities() ? SecuritiesLedger.opening(registry) : null,
                manifest.hasMatching() ? WorldExchange.opening() : null,
                manufacturing, governance, roles, information, trading, decisions);
    }

    /**
     * 由冻结配置重建开局授权状态：产品授权 + 初始任职，并对照开局来源再校验一次。
     *
     * <p>初始任职的起点必须不早于世界开局（0），因此不需要在这里伪造调用时刻；
     * 每条任职都要通过公开的 {@link RoleAccess#appoint} 同口径的身份/区间/重叠校验。</p>
     */
    private RoleAccess.State roleStateOf(RoleRun.Config config) {
        RoleAccess.State access = RoleAccess.open(registry, orderedOpenings, config.mandates());
        for (RoleAccess.Appointment appointment : config.appointments()) {
            access = RoleAccess.appoint(registry, access, appointment.id(),
                    appointment.personPartyId(), appointment.organizationPartyId(),
                    appointment.role(), appointment.startsAt(), appointment.endsAt(), 0L);
        }
        RoleAccess.validateAgainst(registry, orderedOpenings, access);
        return access;
    }

    /** @return 当前规则档案对应的世界状态版本 */
    private int expectedSchema() {
        if (manifest.hasDecisions()) {
            return STATE_SCHEMA_DECISIONS;
        }
        if (manifest.hasTrading()) {
            return STATE_SCHEMA_ROLE_TRADING;
        }
        if (manifest.hasRoles()) {
            return STATE_SCHEMA_ROLE_READING;
        }
        if (manifest.hasGovernance()) {
            return STATE_SCHEMA_GOVERNANCE;
        }
        if (manifest.hasManufacturing()) {
            return STATE_SCHEMA_MANUFACTURING;
        }
        if (manifest.hasMatching()) {
            return STATE_SCHEMA_EXCHANGE;
        }
        return manifest.hasSecurities() ? STATE_SCHEMA_SECURITIES : STATE_SCHEMA;
    }

    /**
     * 由当前状态派生下一个提交位置的状态；只替换显式给出的部分。
     */
    private WorldState next(long timeMillis, String lastPhase,
                            Map<String, CashLedger.CashAccount> accounts,
                            List<PendingTask> pending, List<Notice> delivered,
                            Set<String> usedTaskIds, Map<String, String> commandContents,
                            Map<String, String> commandOutcomes,
                            SecuritiesLedger.State securities,
                            WorldExchange.State exchange) {
        return next(timeMillis, lastPhase, accounts, pending, delivered, usedTaskIds,
                commandContents, commandOutcomes, securities, exchange, state.manufacturing(),
                state.governance());
    }

    private WorldState next(long timeMillis, String lastPhase,
                            Map<String, CashLedger.CashAccount> accounts,
                            List<PendingTask> pending, List<Notice> delivered,
                            Set<String> usedTaskIds, Map<String, String> commandContents,
                            Map<String, String> commandOutcomes,
                            SecuritiesLedger.State securities,
                            WorldExchange.State exchange,
                            ManufacturingRun manufacturing) {
        return next(timeMillis, lastPhase, accounts, pending, delivered, usedTaskIds,
                commandContents, commandOutcomes, securities, exchange, manufacturing,
                state.governance());
    }

    private WorldState next(long timeMillis, String lastPhase,
                            Map<String, CashLedger.CashAccount> accounts,
                            List<PendingTask> pending, List<Notice> delivered,
                            Set<String> usedTaskIds, Map<String, String> commandContents,
                            Map<String, String> commandOutcomes,
                            SecuritiesLedger.State securities,
                            WorldExchange.State exchange,
                            ManufacturingRun manufacturing,
                            BoardRun governance) {
        return next(timeMillis, lastPhase, accounts, pending, delivered, usedTaskIds,
                commandContents, commandOutcomes, securities, exchange, manufacturing, governance,
                state.roles(), state.information(), state.trading(), state.decisions());
    }

    private WorldState next(long timeMillis, String lastPhase,
                            Map<String, CashLedger.CashAccount> accounts,
                            List<PendingTask> pending, List<Notice> delivered,
                            Set<String> usedTaskIds, Map<String, String> commandContents,
                            Map<String, String> commandOutcomes,
                            SecuritiesLedger.State securities,
                            WorldExchange.State exchange,
                            ManufacturingRun manufacturing,
                            BoardRun governance,
                            RoleRun roles,
                            InformationRun information) {
        return next(timeMillis, lastPhase, accounts, pending, delivered, usedTaskIds,
                commandContents, commandOutcomes, securities, exchange, manufacturing, governance,
                roles, information, state.trading(), state.decisions());
    }

    private WorldState next(long timeMillis, String lastPhase,
                            Map<String, CashLedger.CashAccount> accounts,
                            List<PendingTask> pending, List<Notice> delivered,
                            Set<String> usedTaskIds, Map<String, String> commandContents,
                            Map<String, String> commandOutcomes,
                            SecuritiesLedger.State securities,
                            WorldExchange.State exchange,
                            ManufacturingRun manufacturing,
                            BoardRun governance,
                            RoleRun roles,
                            InformationRun information,
                            RoleTradingRun trading) {
        return next(timeMillis, lastPhase, accounts, pending, delivered, usedTaskIds,
                commandContents, commandOutcomes, securities, exchange, manufacturing, governance,
                roles, information, trading, state.decisions());
    }

    private WorldState next(long timeMillis, String lastPhase,
                            Map<String, CashLedger.CashAccount> accounts,
                            List<PendingTask> pending, List<Notice> delivered,
                            Set<String> usedTaskIds, Map<String, String> commandContents,
                            Map<String, String> commandOutcomes,
                            SecuritiesLedger.State securities,
                            WorldExchange.State exchange,
                            ManufacturingRun manufacturing,
                            BoardRun governance,
                            RoleRun roles,
                            InformationRun information,
                            RoleTradingRun trading,
                            DecisionRun decisions) {
        return new WorldState(state.schemaVersion(), state.runId(), state.position() + 1,
                timeMillis, lastPhase, accounts, pending, delivered, usedTaskIds,
                commandContents, commandOutcomes, securities, exchange, manufacturing, governance,
                roles, information, trading, decisions);
    }

    // ==================================================================
    // M4c 四岗交易：人物会话、动作排程与只读投影
    // ==================================================================

    /** 交易动作参数：人物与观察任职由可信会话绑定，body 是动作业务字段的 JSON。 */
    private record RoleActionPayload(String commandId, String clientCommandId,
                                    String clientTaskId, String personPartyId,
                                    long dueMillis, Map<String, String> appointments,
                                    String kind, String body) {
    }

    /** 阅读取消的本人范围引用。 */
    private record RoleUnreadBody(String docId, long version, long index) {
    }

    /** 系统扫描：目标 + 版本。 */
    private record RoleSweepBody(String targetId, int version) {
    }

    /** 系统到期：目标 + 版本 + 审核版本。 */
    private record RoleExpireBody(String targetId, int version, int revision) {
    }

    /** 系统任职结束：任职标识。 */
    private record RoleAppointmentBody(String appointmentId) {
    }

    /** 系统暂停到期：目标 + 版本。 */
    private record RoleHaltBody(String targetId, int version) {
    }

    /** @return 人物命名空间下的内部命令标识；长度前缀保证 (person, clientId) 单射 */
    static String roleCommandId(String personPartyId, String clientCommandId) {
        return "roleT:" + personPartyId.length() + ":" + personPartyId + ":" + clientCommandId;
    }

    /**
     * 人物命名空间下的内部任务标识。
     *
     * <p>长度前缀让 {@code (person, clientId)} 到内部键成为单射，人物标识本身含冒号也不会错位；
     * 回报只向人物投影 client 标识。</p>
     *
     * @param personPartyId 人物
     * @param clientTaskId  人物自己的任务标识
     * @return 内部任务标识
     */
    public static String roleTaskId(String personPartyId, String clientTaskId) {
        return "roleT:" + personPartyId.length() + ":" + personPartyId + ":" + clientTaskId;
    }

    private void requireTradingEnabled() {
        if (!manifest.hasTrading() || state.trading() == null) {
            throw new IllegalStateException("当前运行未启用四岗交易模块：" + manifest.rulesVersion());
        }
    }

    private RoleTradingRun requireTradingRun() {
        RoleTradingRun run = state.trading();
        if (run == null) {
            throw new IllegalStateException("当前运行没有四岗交易子集状态");
        }
        return run;
    }

    /**
     * 打开一个绑定人物与本次观察任职的会话。
     *
     * <p>只要求人物是开局登记 PERSON 且具备已配置的信息读者能力；<b>不要求</b>当前有有效任职，
     * 离任者仍可查看个人资产、已读经历与公开资料，岗位动作在完成期按观察任职复核。</p>
     *
     * @param personPartyId 可信人物标识
     * @return 会话
     * @throws IOException              读取持久状态失败
     * @throws IllegalArgumentException 人物未登记、不是读者或模块未启用
     */
    public synchronized WorldRoleSession openRoleSession(String personPartyId) throws IOException {
        requireOpen();
        requireRoleReadingEnabled();
        requireTradingEnabled();
        if (personPartyId == null || personPartyId.isBlank()) {
            throw new IllegalArgumentException("角色会话不可用");
        }
        InformationModel.State information = requireInformationRun().information();
        if (!information.persons().contains(personPartyId)) {
            throw new IllegalArgumentException("角色会话不可用");
        }
        long now = state.timeMillis();
        RoleAccess.State access = requireRoleRun().access();
        Map<String, String> observed = new LinkedHashMap<>();
        for (RoleAccess.Appointment appointment : access.appointments()) {
            if (!appointment.personPartyId().equals(personPartyId) || !appointment.activeAt(now)) {
                continue;
            }
            String organization = appointment.organizationPartyId();
            String existing = observed.get(organization);
            if (existing == null || startsLater(access, appointment, existing)) {
                observed.put(organization, appointment.id());
            }
        }
        return new WorldRoleSession(this, personPartyId, now, observed);
    }

    private static boolean startsLater(RoleAccess.State access, RoleAccess.Appointment candidate,
                                       String existingId) {
        for (RoleAccess.Appointment appointment : access.appointments()) {
            if (appointment.id().equals(existingId)) {
                return candidate.startsAt() >= appointment.startsAt();
            }
        }
        return true;
    }

    /**
     * 人物只读视图：任职映射取本次观察，资产、行情、工作流取同一已确认快照。
     *
     * @param session 会话
     * @return 视图
     * @throws IOException 读取失败
     */
    synchronized RoleTradingView roleTradingView(WorldRoleSession session) throws IOException {
        requireOpen();
        return roleTradingViewAt(state, session);
    }

    /**
     * 由**显式给出的**已确认快照生成人物视图：恢复期可以据此重算历史输入，
     * 不交换全局状态、不复制第二套资产/权限算法。
     *
     * @param snap    已确认世界快照（通常是本次决定打开前的状态）
     * @param session 会话
     * @return 视图
     * @throws IOException 读取失败
     */
    synchronized RoleTradingView roleTradingViewAt(WorldState snap, WorldRoleSession session)
            throws IOException {
        requireOpen();
        if (!manifest.hasTrading() || !manifest.hasRoles() || !manifest.hasInformation()) {
            throw new IllegalStateException("当前运行未启用四岗交易或阅读模块："
                    + manifest.rulesVersion());
        }
        if (snap.trading() == null || snap.information() == null || snap.roles() == null) {
            throw new IllegalStateException("快照缺少四岗交易、阅读或任职子集");
        }
        String person = session.personPartyId();
        RoleTradingRun run = snap.trading();
        RoleTradingModel.State trading = run.trading();
        InformationModel.State information = snap.information().information();
        RoleAccess.State access = snap.roles().access();
        long now = snap.timeMillis();

        // 观察绑定 ∩ 当前有效任职：离任后旧会话不得继承新任职或离任后新增内容
        Map<String, String> effective = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : session.appointments().entrySet()) {
            for (RoleAccess.Appointment appointment : access.appointments()) {
                if (appointment.id().equals(entry.getValue())
                        && appointment.personPartyId().equals(person)
                        && appointment.organizationPartyId().equals(entry.getKey())
                        && appointment.activeAt(now)) {
                    effective.put(entry.getKey(), appointment.id());
                }
            }
        }

        List<RoleTradingView.Appointment> appointments = new ArrayList<>();
        for (Map.Entry<String, String> entry : effective.entrySet()) {
            for (RoleAccess.Appointment appointment : access.appointments()) {
                if (appointment.id().equals(entry.getValue())) {
                    appointments.add(new RoleTradingView.Appointment(appointment.id(),
                            appointment.organizationPartyId(), appointment.role(),
                            appointment.effectiveEnd()));
                }
            }
        }

        RoleView profile = roleView(snap, person, scopedRoleAccess(access, person, effective));

        Set<String> organizations = new HashSet<>(effective.keySet());
        List<RoleTradingView.ResearchView> research = new ArrayList<>();
        for (RoleTradingModel.Research note : trading.research()) {
            if (!organizations.contains(note.organizationPartyId())) {
                continue;
            }
            InformationModel.Document document = documentOf(information, note.docId());
            InformationModel.DocumentVersion fixed = document == null ? null
                    : document.at(note.docVersion());
            String title = fixed == null ? note.docId() : fixed.title();
            boolean read = InformationModel.canCite(information, person, note.docId(),
                    note.docVersion(), now);
            research.add(new RoleTradingView.ResearchView(note.researchId(),
                    note.organizationPartyId(), note.productOwnerId(), note.symbol(), note.docId(),
                    note.docVersion(), title, read && fixed != null ? fixed.body() : null, read));
        }

        List<RoleTradingView.TargetView> targets = new ArrayList<>();
        for (RoleTradingModel.Target target : trading.targets()) {
            if (!organizations.contains(target.organizationPartyId())) {
                continue;
            }
            List<RoleTradingView.Version> versions = new ArrayList<>();
            for (RoleTradingModel.TargetVersion version : target.versions()) {
                versions.add(new RoleTradingView.Version(version.version(),
                        version.absoluteTargetQuantity(), version.baseQuantity(),
                        version.changeQuantity(), version.side(), version.priceLimit(),
                        version.deadlineMillis()));
            }
            RoleTradingModel.TargetVersion current = target.current();
            long filled = 0L;
            long leaves = 0L;
            for (RoleTradingModel.ChildLink link : trading.children()) {
                if (!link.targetId().equals(target.targetId())
                        || link.targetVersion() != current.version()) {
                    continue;
                }
                WorldExchange.Order order = snap.exchange().order(link.orderId());
                filled = Math.addExact(filled, order.filled());
                if (order.live()) {
                    leaves = Math.addExact(leaves, order.remaining());
                }
            }
            RoleTradingModel.RiskReview review = RoleTradingModel.currentReview(trading,
                    target.targetId(), current.version());
            RoleTradingView.ReviewView reviewView = review == null ? null
                    : new RoleTradingView.ReviewView(review.revision(), review.approved(),
                    review.maxTotalQuantity(), review.maxChildQuantity(), review.priceLimit(),
                    review.validUntilMillis(), review.reason());
            targets.add(new RoleTradingView.TargetView(target.targetId(),
                    target.organizationPartyId(), target.productOwnerId(), target.accountId(),
                    target.symbol(), target.status(), target.haltedUntilMillis(),
                    new RoleTradingView.Version(current.version(),
                            current.absoluteTargetQuantity(), current.baseQuantity(),
                            current.changeQuantity(), current.side(), current.priceLimit(),
                            current.deadlineMillis()),
                    versions, reviewView, filled, leaves,
                    Math.max(0L, current.changeQuantity() - filled - leaves)));
        }

        List<RoleTradingView.ChildView> children = new ArrayList<>();
        for (RoleTradingModel.ChildLink link : trading.children()) {
            if (!link.personPartyId().equals(person)) {
                continue;
            }
            WorldExchange.Order order = snap.exchange().order(link.orderId());
            children.add(new RoleTradingView.ChildView(
                    new WorldRoleSession.ChildRef(link.targetId(), link.personPartyId(),
                            link.childId()), link.targetVersion(), order.quantity(), order.filled(),
                    order.live() ? order.remaining() : 0L, order.limitPrice(),
                    order.status().name()));
        }

        List<RoleTradingView.Notice> notices = new ArrayList<>();
        for (RoleTradingRun.Report entry : run.reportsOf(person)) {
            notices.add(new RoleTradingView.Notice(entry.clientCommandId(), entry.clientTaskId(),
                    entry.kind(), entry.accepted(), entry.reason(), entry.timeMillis()));
        }

        return new RoleTradingView(session.observedAt(), now, person, profile, appointments,
                research, targets, children, notices, roleTools(appointments));
    }

    /** 使用原观察任职裁剪权限，身份、个人账户及组织账户登记保持同一来源。 */
    private static RoleAccess.State scopedRoleAccess(RoleAccess.State access, String person,
                                                      Map<String, String> observed) {
        List<RoleAccess.Appointment> appointments = new ArrayList<>();
        for (RoleAccess.Appointment appointment : access.appointments()) {
            if (!appointment.personPartyId().equals(person)
                    || observed.containsValue(appointment.id())) {
                appointments.add(appointment);
            }
        }
        return new RoleAccess.State(access.accounts(), access.personAccounts(), access.mandates(),
                appointments);
    }

    private static List<String> roleTools(List<RoleTradingView.Appointment> appointments) {
        boolean researcher = false;
        boolean manager = false;
        boolean risk = false;
        boolean trader = false;
        for (RoleTradingView.Appointment appointment : appointments) {
            switch (appointment.role()) {
                case RESEARCHER -> researcher = true;
                case PORTFOLIO_MANAGER -> manager = true;
                case RISK_OFFICER -> risk = true;
                case EXECUTION_TRADER -> trader = true;
                default -> {
                }
            }
        }
        List<String> tools = new ArrayList<>();
        tools.add(RoleTradingView.TOOL_READING_START);
        tools.add(RoleTradingView.TOOL_READING_CANCEL);
        tools.add(RoleTradingView.TOOL_CITE);
        if (researcher) {
            tools.add(RoleTradingView.TOOL_RESEARCH_SUBMIT);
        }
        if (manager) {
            tools.add(RoleTradingView.TOOL_TARGET_SET);
            tools.add(RoleTradingView.TOOL_TARGET_CONFIRM);
            tools.add(RoleTradingView.TOOL_TARGET_CANCEL);
        }
        if (risk) {
            tools.add(RoleTradingView.TOOL_TARGET_REVIEW);
            tools.add(RoleTradingView.TOOL_TARGET_HALT);
        }
        if (trader) {
            tools.add(RoleTradingView.TOOL_CHILD_SUBMIT);
            tools.add(RoleTradingView.TOOL_CHILD_CANCEL);
        }
        return List.copyOf(tools);
    }

    /** 会话开始阅读：组织资料要核对本次观察任职，公开与本人直投按个人能力。 */
    String scheduleRoleRead(WorldRoleSession session, String clientCommandId, String clientTaskId,
                            long dueMillis, String docId, long version) throws IOException {
        return scheduleRoleAction(session, PendingTask.ROLE_TRADING_READ_V1, clientCommandId,
                clientTaskId, dueMillis, new RoleReadBody(docId, version));
    }

    /** 会话取消阅读：只按本人范围引用解析。 */
    String scheduleRoleUnread(WorldRoleSession session, String clientCommandId,
                              String clientTaskId, long dueMillis, RoleView.ReadingRef ref)
            throws IOException {
        Objects.requireNonNull(ref, "阅读引用");
        if (!ref.personPartyId().equals(session.personPartyId())) {
            throw new IllegalArgumentException("阅读引用不属于该人物：" + ref.personPartyId());
        }
        return scheduleRoleAction(session, PendingTask.ROLE_TRADING_UNREAD_V1, clientCommandId,
                clientTaskId, dueMillis, new RoleUnreadBody(ref.docId(), ref.version(), ref.index()));
    }

    /** 阅读开始参数。 */
    private record RoleReadBody(String docId, long version) {
    }

    /**
     * 排程一个人物动作：形状合法即可靠排程，未知对象与越权在完成期统一拒绝。
     *
     * @param session         会话（人物与观察任职来源）
     * @param kind            任务种类
     * @param clientCommandId 人物命令标识
     * @param clientTaskId    人物任务标识
     * @param dueMillis       到期模拟毫秒
     * @param body            业务参数
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 静态字段、时间或命名空间非法
     */
    String scheduleRoleAction(WorldRoleSession session, String kind, String clientCommandId,
                              String clientTaskId, long dueMillis, Object body) throws IOException {
        requireTradingEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        Objects.requireNonNull(session, "会话");
        requireText(clientCommandId, "命令标识");
        requireText(clientTaskId, "任务标识");
        Objects.requireNonNull(body, "动作参数");
        String internalCommand = roleCommandId(session.personPartyId(), clientCommandId);
        String internalTask = roleTaskId(session.personPartyId(), clientTaskId);
        RoleActionPayload payload = new RoleActionPayload(internalCommand, clientCommandId,
                clientTaskId, session.personPartyId(), dueMillis,
                new TreeMap<>(session.appointments()), kind, mapper.writeValueAsString(body));
        String content = mapper.writeValueAsString(payload);
        if (state.commandContents().containsKey(internalCommand)) {
            if (!content.equals(state.commandContents().get(internalCommand))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return state.commandOutcomes().get(internalCommand);
        }
        if (dueMillis <= state.timeMillis()) {
            throw new IllegalArgumentException("到期时间必须晚于当前时点：" + state.timeMillis());
        }
        if (state.usedTaskIds().contains(internalTask)
                || state.pending().stream().anyMatch(task -> task.taskId().equals(internalTask))) {
            throw new IllegalArgumentException("任务标识运行内不复用：" + internalTask);
        }
        PendingTask task = new PendingTask(internalTask, dueMillis, WorldScheduler.Phase.COMPLETION,
                kind, content);
        return scheduleRoleTask(task, internalCommand, content, clientTaskId);
    }

    /** 排程一个角色动作待办并持久回报；同命令幂等由调用方先行处理。 */
    private String scheduleRoleTask(PendingTask task, String internalCommand, String content,
                                    String clientTaskId) throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.add(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        Map<String, String> contents = new TreeMap<>(state.commandContents());
        contents.put(internalCommand, content);
        Map<String, String> outcomes = new TreeMap<>(state.commandOutcomes());
        String outcome = "SCHEDULED:" + clientTaskId;
        outcomes.put(internalCommand, outcome);
        WorldState candidate = next(state.timeMillis(), state.lastPhase(), state.accounts(), pending,
                state.delivered(), used, contents, outcomes, state.securities(), state.exchange(),
                state.manufacturing(), state.governance(), state.roles(), state.information(),
                state.trading());
        commitAndApply("cmd:" + internalCommand, content, outcome,
                List.of(new WorldEvent("task-scheduled:" + task.taskId(), "TASK_SCHEDULED_V1",
                        mapper.writeValueAsString(task))), candidate);
        return outcome;
    }

    // ------------------------------------------------------------------
    // 完成期：阅读、八个岗位动作、系统扫描/到期/离任/暂停到期
    // ------------------------------------------------------------------

    /** 会议阅读：组织资料要求本次观察任职仍在任且属于该组织；公开与直投按个人能力。 */
    private void completeRoleReading(PendingTask task) throws IOException {
        RoleActionPayload payload = mapper.readValue(task.payload(), RoleActionPayload.class);
        long now = task.dueTimeMillis();
        RoleTradingRun run = requireTradingRun();
        InformationRun informationRun = requireInformationRun();
        InformationModel.State before = informationRun.information();
        InformationModel.State after = before;
        RoleAccess.State access = requireRoleRun().access();
        List<PendingTask> extra = new ArrayList<>();
        List<WorldEvent> events = new ArrayList<>();
        boolean accepted = false;
        String reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
        String subject = "";
        String removeReadId = null;

        if (PendingTask.ROLE_TRADING_READ_V1.equals(task.kind())) {
            RoleReadBody body = mapper.readValue(payload.body(), RoleReadBody.class);
            subject = body.docId();
            InformationModel.Document document = documentOf(before, body.docId());
            boolean allowed = document != null
                    && InformationModel.catalog(before, access, payload.personPartyId(), now).stream()
                    .anyMatch(entry -> entry.docId().equals(body.docId()))
                    && (document.audience().kind() != InformationModel.AudienceKind.ORGANIZATION
                    || observedFor(payload, document.audience().partyId(), access, now) != null);
            if (!allowed) {
                reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
            } else {
                InformationModel.Outcome outcome = InformationModel.startRead(before, access,
                        payload.personPartyId(), body.docId(), body.version(), now);
                after = outcome.state();
                accepted = outcome.status() == InformationModel.Status.STARTED;
                reason = outcome.status().name();
                if (accepted) {
                    InformationModel.Reading started = readingView(after, payload.personPartyId());
                    String childId = freeInternalTaskId("read:", started.readId(),
                            new LinkedHashSet<>(state.usedTaskIds()));
                    PendingTask child = new PendingTask(childId, started.dueAt(),
                            WorldScheduler.Phase.COMPLETION, PendingTask.INFORMATION_COMPLETE_READ_V1,
                            mapper.writeValueAsString(new ReadingPayload(payload.commandId(),
                                    payload.personPartyId(), body.docId(), body.version(),
                                    started.readId(), 0L)));
                    extra.add(child);
                }
            }
        } else {
            RoleUnreadBody body = mapper.readValue(payload.body(), RoleUnreadBody.class);
            subject = body.docId();
            InformationModel.Reading target = activeReadingOf(before, payload.personPartyId(),
                    body.docId(), body.version(), body.index());
            if (target == null) {
                reason = "NOT_FOUND";
            } else {
                InformationModel.Outcome outcome = InformationModel.cancelRead(before,
                        payload.personPartyId(), target.readId(), now);
                after = outcome.state();
                accepted = outcome.status() == InformationModel.Status.CANCELLED;
                reason = outcome.status().name();
                if (accepted) {
                    removeReadId = target.readId();
                }
            }
        }

        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        if (removeReadId != null) {
            removeReadingChildTask(pending, removeReadId);
        }
        for (PendingTask child : extra) {
            pending.add(child);
            used.add(child.taskId());
            events.add(new WorldEvent("task-scheduled:" + child.taskId(), "TASK_SCHEDULED_V1",
                    mapper.writeValueAsString(child)));
        }

        InformationRun informationCandidate = informationRun.withInformation(after);
        RoleTradingRun.Report entry = new RoleTradingRun.Report(task.taskId(),
                payload.personPartyId(), payload.clientCommandId(), payload.clientTaskId(),
                task.kind(), accepted, reason, subject, task.dueTimeMillis(),
                state.position() + 1);
        RoleTradingRun runCandidate = run.withReport(entry);
        events.add(new WorldEvent("role-trading-report:" + task.taskId(),
                "ROLE_TRADING_REPORT_V1", mapper.writeValueAsString(entry)));
        events.add(0, new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), state.accounts(),
                pending, state.delivered(), used, state.commandContents(), state.commandOutcomes(),
                state.securities(), state.exchange(), state.manufacturing(), state.governance(),
                state.roles(), informationCandidate, runCandidate);
        commitAndApply("task:" + task.taskId(), task.payload(), reason, events, candidate);
    }

    /** 八个岗位动作的统一完成期：先按观察任职复核，再调纯核，最后同批写入报价、链接与报告。 */
    private void completeRoleAction(PendingTask task) throws IOException {
        RoleActionPayload payload = mapper.readValue(task.payload(), RoleActionPayload.class);
        long now = task.dueTimeMillis();
        RoleTradingRun run = requireTradingRun();
        RoleAccess.State access = requireRoleRun().access();
        InformationRun informationRun = requireInformationRun();
        InformationModel.State information = informationRun.information();
        RoleTradingModel.State before = run.trading();
        RoleTradingModel.State after = before;
        InformationModel.State informationAfter = information;
        Map<String, CashLedger.CashAccount> accounts = state.accounts();
        SecuritiesLedger.State securities = state.securities();
        WorldExchange.State exchange = state.exchange();
        List<PendingTask> extra = new ArrayList<>();
        List<WorldEvent> events = new ArrayList<>();
        boolean accepted = false;
        String reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
        String subject = "";

        switch (task.kind()) {
            case PendingTask.ROLE_TRADING_RESEARCH_V1 -> {
                WorldRoleSession.ResearchDraft draft = mapper.readValue(payload.body(),
                        WorldRoleSession.ResearchDraft.class);
                subject = draft.researchId();
                String appointment = observedFor(payload, draft.organizationPartyId(), access, now);
                if (appointment == null) {
                    reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
                    break;
                }
                List<RoleTradingModel.Citation> citations = new ArrayList<>();
                for (WorldRoleSession.Citation citation : draft.citations()) {
                    citations.add(new RoleTradingModel.Citation(citation.docId(), citation.version()));
                }
                RoleTradingModel.ResearchResult result = RoleTradingModel.submitResearch(before,
                        access, information, registry, new RoleTradingModel.ResearchRequest(
                                draft.researchId(), draft.organizationPartyId(),
                                draft.productOwnerId(), draft.symbol(), payload.personPartyId(),
                                appointment, draft.docId(), draft.title(), draft.body(), citations),
                        now);
                accepted = result.accepted();
                reason = result.reason();
                if (accepted) {
                    after = result.state();
                    informationAfter = result.information();
                }
            }
            case PendingTask.ROLE_TRADING_TARGET_V1 -> {
                WorldRoleSession.TargetDraft draft = mapper.readValue(payload.body(),
                        WorldRoleSession.TargetDraft.class);
                subject = draft.targetId();
                String appointment = observedFor(payload, draft.organizationPartyId(), access, now);
                if (appointment == null) {
                    reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
                    break;
                }
                RoleTradingModel.Result result = RoleTradingModel.setTarget(before, access,
                        information, securities, new RoleTradingModel.TargetRequest(draft.targetId(),
                                draft.expectedVersion(), draft.organizationPartyId(),
                                draft.productOwnerId(), draft.accountId(), draft.symbol(),
                                draft.absoluteTargetQuantity(), draft.priceLimit(),
                                draft.deadlineMillis(), draft.sourceResearchId(),
                                payload.personPartyId(), appointment), now);
                accepted = result.accepted();
                reason = result.reason();
                if (accepted) {
                    after = result.state();
                    scheduleFollowing(after, draft.targetId(), extra, now);
                }
            }
            case PendingTask.ROLE_TRADING_REVIEW_V1 -> {
                WorldRoleSession.ReviewDraft draft = mapper.readValue(payload.body(),
                        WorldRoleSession.ReviewDraft.class);
                subject = draft.targetId();
                RoleTradingModel.Target parent = RoleTradingModel.target(before, draft.targetId());
                String appointment = parent == null ? null
                        : observedFor(payload, parent.organizationPartyId(), access, now);
                if (appointment == null) {
                    reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
                    break;
                }
                int previousRevision = latestRevision(before, draft.targetId());
                RoleTradingModel.Result result = RoleTradingModel.reviewTarget(before, access,
                        securities, new RoleTradingModel.ReviewRequest(draft.targetId(),
                                draft.expectedVersion(), draft.approved(), draft.maxTotalQuantity(),
                                draft.maxChildQuantity(), draft.priceLimit(),
                                draft.validUntilMillis(), draft.reason(), payload.personPartyId(),
                                appointment), now);
                accepted = result.accepted();
                reason = result.reason();
                if (accepted) {
                    after = result.state();
                    RoleTradingModel.RiskReview review = RoleTradingModel.currentReview(after,
                            draft.targetId(), draft.expectedVersion());
                    if (review != null && review.revision() != previousRevision
                            && hasLiveChild(before, exchange, draft.targetId())) {
                        scheduleSweep(draft.targetId(), draft.expectedVersion(), extra, now);
                    }
                    scheduleFollowing(after, draft.targetId(), extra, now);
                }
            }
            case PendingTask.ROLE_TRADING_CONFIRM_V1 -> {
                WorldRoleSession.ConfirmDraft draft = mapper.readValue(payload.body(),
                        WorldRoleSession.ConfirmDraft.class);
                subject = draft.targetId();
                RoleTradingModel.Target parent = RoleTradingModel.target(before, draft.targetId());
                String appointment = parent == null ? null
                        : observedFor(payload, parent.organizationPartyId(), access, now);
                if (appointment == null) {
                    reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
                    break;
                }
                if (hasPendingSweep(draft.targetId())) {
                    reason = "CANCEL_PENDING";
                    break;
                }
                RoleTradingModel.Result result = RoleTradingModel.confirmTarget(before, access,
                        draft.targetId(), draft.expectedVersion(), draft.riskRevision(),
                        payload.personPartyId(), appointment, now);
                accepted = result.accepted();
                reason = result.reason();
                if (accepted) {
                    after = result.state();
                    scheduleFollowing(after, draft.targetId(), extra, now);
                }
            }
            case PendingTask.ROLE_TRADING_HALT_V1 -> {
                WorldRoleSession.HaltDraft draft = mapper.readValue(payload.body(),
                        WorldRoleSession.HaltDraft.class);
                subject = draft.targetId();
                RoleTradingModel.Target parent = RoleTradingModel.target(before, draft.targetId());
                String appointment = parent == null ? null
                        : observedFor(payload, parent.organizationPartyId(), access, now);
                if (appointment == null) {
                    reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
                    break;
                }
                RoleTradingModel.Result result = RoleTradingModel.haltTarget(before, access,
                        draft.haltId(), draft.targetId(), draft.expectedVersion(),
                        draft.untilMillis(), draft.reason(), payload.personPartyId(), appointment,
                        now);
                accepted = result.accepted();
                reason = result.reason();
                if (accepted) {
                    after = result.state();
                    scheduleSweep(draft.targetId(), draft.expectedVersion(), extra, now);
                    scheduleHaltRelease(draft.targetId(), draft.expectedVersion(),
                            draft.untilMillis(), extra);
                }
            }
            case PendingTask.ROLE_TRADING_CANCEL_V1 -> {
                WorldRoleSession.CancelTargetDraft draft = mapper.readValue(payload.body(),
                        WorldRoleSession.CancelTargetDraft.class);
                subject = draft.targetId();
                RoleTradingModel.Target parent = RoleTradingModel.target(before, draft.targetId());
                String appointment = parent == null ? null
                        : observedFor(payload, parent.organizationPartyId(), access, now);
                if (appointment == null) {
                    reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
                    break;
                }
                RoleTradingModel.Result result = RoleTradingModel.cancelTarget(before, access,
                        draft.targetId(), draft.expectedVersion(), payload.personPartyId(),
                        appointment, now);
                accepted = result.accepted();
                reason = result.reason();
                if (accepted) {
                    after = result.state();
                    scheduleSweep(draft.targetId(), draft.expectedVersion(), extra, now);
                }
            }
            case PendingTask.ROLE_TRADING_CHILD_V1 -> {
                WorldRoleSession.ChildDraft draft = mapper.readValue(payload.body(),
                        WorldRoleSession.ChildDraft.class);
                subject = draft.childId();
                RoleTradingModel.Target parent = RoleTradingModel.target(before, draft.parentId());
                String appointment = parent == null ? null
                        : observedFor(payload, parent.organizationPartyId(), access, now);
                if (appointment == null) {
                    reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
                    break;
                }
                RoleTradingModel.ChildRequest request = new RoleTradingModel.ChildRequest(
                        draft.parentId(), draft.parentVersion(), draft.riskRevision(),
                        draft.childId(), payload.personPartyId(), appointment, draft.quantity(),
                        draft.limitPrice(), task.taskId());
                RoleTradingModel.Result admitted = RoleTradingModel.admitChild(before, access,
                        exchange, securities, request, now);
                if (!admitted.accepted()) {
                    reason = admitted.reason();
                    break;
                }
                RoleTradingModel.TargetVersion version = parent.current();
                WorldExchange.Outcome outcome = WorldExchange.submit(exchange, accounts, securities,
                        task.taskId(), payload.commandId(), parent.productOwnerId(),
                        parent.accountId(), parent.symbol(), version.side(), OrderType.LIMIT,
                        draft.limitPrice(), draft.quantity(), now);
                if (!outcome.accepted()) {
                    reason = outcome.reason();
                    break;
                }
                RoleTradingModel.Result linked = RoleTradingModel.linkOrder(admitted.state(), access,
                        outcome.exchange(), request, outcome.orderId());
                if (!linked.accepted()) {
                    // 撮合候选不得单独落地：链接失败即丢弃整笔订单
                    reason = linked.reason();
                    break;
                }
                after = linked.state();
                exchange = outcome.exchange();
                accounts = outcome.accounts();
                securities = outcome.securities();
                accepted = true;
                reason = "OK";
                for (Long tradeId : outcome.tradeIds()) {
                    WorldExchange.Trade trade = exchange.trades().stream()
                            .filter(item -> item.tradeId() == tradeId).findFirst()
                            .orElseThrow(() -> new IllegalStateException("成交号未登记：" + tradeId));
                    events.add(new WorldEvent("exchange-trade:" + tradeId, "EXCHANGE_TRADE_V1",
                            mapper.writeValueAsString(trade)));
                }
                WorldExchange.OrderReport report = new WorldExchange.OrderReport(task.taskId(),
                        payload.commandId(), task.kind(), true, outcome.reason(),
                        outcome.orderId(), outcome.tradeIds(), List.of(), now,
                        state.position() + 1);
                exchange = exchange.withReport(report);
                events.add(new WorldEvent("exchange-report:" + task.taskId(),
                        "EXCHANGE_ORDER_REPORT_V1", mapper.writeValueAsString(report)));
                scheduleAppointmentEnd(appointment, extra);
                scheduleFollowing(after, draft.parentId(), extra, now);
            }
            case PendingTask.ROLE_TRADING_CHILD_CANCEL_V1 -> {
                WorldRoleSession.ChildRef ref = mapper.readValue(payload.body(),
                        WorldRoleSession.ChildRef.class);
                subject = ref.childId();
                RoleTradingModel.ChildLink link = linkOf(before, ref);
                RoleTradingModel.Target parent = link == null ? null
                        : RoleTradingModel.target(before, link.targetId());
                String appointment = parent == null ? null
                        : observedFor(payload, parent.organizationPartyId(), access, now);
                if (link == null || appointment == null || !appointment.equals(link.appointmentId())) {
                    reason = RoleTradingModel.REASON_NOT_AUTHORIZED;
                    break;
                }
                WorldExchange.Order order = exchange.order(link.orderId());
                if (order == null || !order.live()) {
                    reason = "TERMINAL_ORDER";
                    break;
                }
                WorldExchange.Outcome outcome = WorldExchange.cancel(exchange, accounts, securities,
                        parent.productOwnerId(), link.orderId(), now);
                accepted = outcome.accepted();
                reason = outcome.reason();
                if (accepted) {
                    exchange = outcome.exchange();
                    accounts = outcome.accounts();
                    securities = outcome.securities();
                    WorldExchange.OrderReport report = new WorldExchange.OrderReport(task.taskId(),
                            payload.commandId(), task.kind(), true, outcome.reason(),
                            outcome.orderId(), outcome.tradeIds(), outcome.cancelledOrderIds(), now,
                            state.position() + 1);
                    exchange = exchange.withReport(report);
                    events.add(new WorldEvent("exchange-report:" + task.taskId(),
                            "EXCHANGE_ORDER_REPORT_V1", mapper.writeValueAsString(report)));
                }
            }
            default -> throw new IllegalStateException("未启用的交易动作：" + task.kind());
        }

        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        for (PendingTask extraTask : extra) {
            if (used.contains(extraTask.taskId())) {
                continue;
            }
            pending.add(extraTask);
            used.add(extraTask.taskId());
            events.add(new WorldEvent("task-scheduled:" + extraTask.taskId(), "TASK_SCHEDULED_V1",
                    mapper.writeValueAsString(extraTask)));
        }
        RoleTradingRun.Report entry = new RoleTradingRun.Report(task.taskId(),
                payload.personPartyId(), payload.clientCommandId(), payload.clientTaskId(),
                task.kind(), accepted, reason, subject, task.dueTimeMillis(),
                state.position() + 1);
        RoleTradingRun runCandidate = run.withTrading(after).withReport(entry);
        events.add(new WorldEvent("role-trading-report:" + task.taskId(),
                "ROLE_TRADING_REPORT_V1", mapper.writeValueAsString(entry)));
        events.add(0, new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), accounts, pending,
                state.delivered(), used, state.commandContents(), state.commandOutcomes(), securities,
                exchange, state.manufacturing(), state.governance(), state.roles(),
                informationAfter.equals(information) ? state.information()
                        : informationRun.withInformation(informationAfter),
                runCandidate);
        if (task.taskId().startsWith("roleT:") && !extra.isEmpty()) {
        }
        commitAndApply("task:" + task.taskId(), task.payload(), reason, events, candidate);
    }

    /** 系统扫描：撤不相容旧单；撤净后 UPDATING 重建基点，其余阶段只停新单。 */
    private void completeRoleSweep(PendingTask task) throws IOException {
        RoleSweepBody body = mapper.readValue(task.payload(), RoleSweepBody.class);
        long now = task.dueTimeMillis();
        RoleTradingRun run = requireTradingRun();
        RoleTradingModel.State trading = run.trading();
        RoleTradingModel.Target target = RoleTradingModel.target(trading, body.targetId());
        if (target == null) {
            finishSystemRoleTask(task, trading, state.accounts(), state.securities(),
                    state.exchange(), "NO_TARGET");
            return;
        }
        if (target.current().version() != body.version()) {
            finishSystemRoleTask(task, trading, state.accounts(), state.securities(),
                    state.exchange(), "STALE_VERSION");
            return;
        }
        Map<String, CashLedger.CashAccount> accounts = state.accounts();
        SecuritiesLedger.State securities = state.securities();
        WorldExchange.State exchange = state.exchange();
        List<WorldEvent> events = new ArrayList<>();
        List<Long> cancelled = new ArrayList<>();
        for (RoleTradingModel.ChildLink link : trading.children()) {
            if (!link.targetId().equals(body.targetId())) {
                continue;
            }
            WorldExchange.Order order = exchange.order(link.orderId());
            if (order == null || !order.live()) {
                continue;
            }
            WorldExchange.Outcome outcome = WorldExchange.cancel(exchange, accounts, securities,
                    target.productOwnerId(), link.orderId(), now);
            if (outcome.accepted()) {
                exchange = outcome.exchange();
                accounts = outcome.accounts();
                securities = outcome.securities();
                cancelled.add(link.orderId());
            } else if (outcome.exchange().order(link.orderId()) != null
                    && outcome.exchange().order(link.orderId()).live()) {
                throw new IOException("系统撤单后订单仍活动：" + link.orderId());
            }
        }
        if (!cancelled.isEmpty()) {
            WorldExchange.OrderReport report = new WorldExchange.OrderReport(task.taskId(),
                    "system:sweep", task.kind(), true, WorldExchange.REASON_CANCELLED, null,
                    List.of(), cancelled, now, state.position() + 1);
            exchange = exchange.withReport(report);
            events.add(new WorldEvent("exchange-report:" + task.taskId(),
                    "EXCHANGE_ORDER_REPORT_V1", mapper.writeValueAsString(report)));
        }
        RoleTradingModel.State after = trading;
        List<PendingTask> extra = new ArrayList<>();
        if (target.status() == RoleTradingModel.Status.UPDATING) {
            RoleTradingModel.Result settled = RoleTradingModel.settleUpdate(trading, exchange,
                    securities, body.targetId(), now);
            if (settled.accepted()) {
                after = settled.state();
                scheduleFollowing(after, body.targetId(), extra, now);
            }
        }
        finishWithExtra(task, after, accounts, securities, exchange, "OK", extra, events);
    }

    /** 系统到期：目标截止到期即 EXPIRED 并撤剩余；审核期限到期只撤该 revision 的旧单。 */
    private void completeRoleExpire(PendingTask task) throws IOException {
        RoleExpireBody body = mapper.readValue(task.payload(), RoleExpireBody.class);
        long now = task.dueTimeMillis();
        RoleTradingRun run = requireTradingRun();
        RoleTradingModel.State trading = run.trading();
        RoleTradingModel.Target target = RoleTradingModel.target(trading, body.targetId());
        if (target == null) {
            finishSystemRoleTask(task, trading, state.accounts(), state.securities(),
                    state.exchange(), "NO_TARGET");
            return;
        }
        if (target.current().version() != body.version()) {
            finishSystemRoleTask(task, trading, state.accounts(), state.securities(),
                    state.exchange(), "STALE_VERSION");
            return;
        }
        if (body.revision() > 0) {
            if (latestRevision(trading, body.targetId()) != body.revision()) {
                finishSystemRoleTask(task, trading, state.accounts(), state.securities(),
                        state.exchange(), "STALE_VERSION");
                return;
            }
            CanceledOrders canceled = cancelOrders(trading, body.targetId(), body.version(),
                    body.revision(), now, task.taskId(), task.kind());
            finishWithExtra(task, trading, canceled.accounts(), canceled.securities(),
                    canceled.exchange(), "REVIEW_EXPIRED", List.of(), canceled.events());
            return;
        }
        if (now < target.current().deadlineMillis()) {
            finishSystemRoleTask(task, trading, state.accounts(), state.securities(),
                    state.exchange(), "NOT_DUE");
            return;
        }
        RoleTradingModel.State after = trading;
        if (target.status() != RoleTradingModel.Status.CANCELLED
                && target.status() != RoleTradingModel.Status.EXPIRED
                && target.status() != RoleTradingModel.Status.COMPLETED) {
            RoleTradingModel.Result expired = RoleTradingModel.expireTarget(trading,
                    body.targetId(), now);
            if (expired.accepted()) {
                after = expired.state();
            }
        }
        List<PendingTask> extra = new ArrayList<>();
        scheduleSweep(body.targetId(), body.version(), extra, now);
        finishWithExtra(task, after, state.accounts(), state.securities(), state.exchange(), "OK",
                extra);
    }

    /** 撤销指定版本（可选 revision）授权的活动子单，返回同批的账户/证券/订单与领域事件。 */
    private CanceledOrders cancelOrders(RoleTradingModel.State trading, String targetId,
                                        int version, int revision, long now, String taskId,
                                        String kind) throws IOException {
        Map<String, CashLedger.CashAccount> accounts = state.accounts();
        SecuritiesLedger.State securities = state.securities();
        WorldExchange.State exchange = state.exchange();
        RoleTradingModel.Target target = RoleTradingModel.target(trading, targetId);
        List<Long> cancelled = new ArrayList<>();
        if (target != null) {
            for (RoleTradingModel.ChildLink link : trading.children()) {
                if (!link.targetId().equals(targetId) || link.targetVersion() != version
                        || (revision > 0 && link.riskRevision() != revision)) {
                    continue;
                }
                WorldExchange.Order order = exchange.order(link.orderId());
                if (order == null || !order.live()) {
                    continue;
                }
                WorldExchange.Outcome outcome = WorldExchange.cancel(exchange, accounts,
                        securities, target.productOwnerId(), link.orderId(), now);
                if (outcome.accepted()) {
                    exchange = outcome.exchange();
                    accounts = outcome.accounts();
                    securities = outcome.securities();
                    cancelled.add(link.orderId());
                }
            }
        }
        List<WorldEvent> events = new ArrayList<>();
        if (!cancelled.isEmpty()) {
            WorldExchange.OrderReport report = new WorldExchange.OrderReport(taskId,
                    "system:expire", kind, true, WorldExchange.REASON_CANCELLED, null, List.of(),
                    cancelled, now, state.position() + 1);
            exchange = exchange.withReport(report);
            events.add(new WorldEvent("exchange-report:" + taskId + ":" + now,
                    "EXCHANGE_ORDER_REPORT_V1", mapper.writeValueAsString(report)));
        }
        return new CanceledOrders(accounts, securities, exchange, events);
    }

    /** 一批撤单后的账户、证券、订单与事件。 */
    private record CanceledOrders(Map<String, CashLedger.CashAccount> accounts,
                                  SecuritiesLedger.State securities,
                                  WorldExchange.State exchange, List<WorldEvent> events) {
    }

    /** 系统任职结束：只撤该任职发出的活动子单。 */
    private void completeAppointmentEnd(PendingTask task) throws IOException {
        RoleAppointmentBody body = mapper.readValue(task.payload(), RoleAppointmentBody.class);
        long now = task.dueTimeMillis();
        RoleTradingRun run = requireTradingRun();
        RoleTradingModel.State trading = run.trading();
        Map<String, CashLedger.CashAccount> accounts = state.accounts();
        SecuritiesLedger.State securities = state.securities();
        WorldExchange.State exchange = state.exchange();
        List<WorldEvent> events = new ArrayList<>();
        for (RoleTradingModel.ChildLink link : trading.children()) {
            if (!link.appointmentId().equals(body.appointmentId())) {
                continue;
            }
            RoleTradingModel.Target target = RoleTradingModel.target(trading, link.targetId());
            if (target == null) {
                continue;
            }
            WorldExchange.Order order = exchange.order(link.orderId());
            if (order == null || !order.live()) {
                continue;
            }
            WorldExchange.Outcome outcome = WorldExchange.cancel(exchange, accounts, securities,
                    target.productOwnerId(), link.orderId(), now);
            if (outcome.accepted()) {
                exchange = outcome.exchange();
                accounts = outcome.accounts();
                securities = outcome.securities();
            }
        }
        finishSystemRoleTask(task, trading, accounts, securities, exchange, "OK", events);
    }

    /** 系统暂停到期：回到待审核，不自动恢复执行。 */
    private void completeHaltRelease(PendingTask task) throws IOException {
        RoleHaltBody body = mapper.readValue(task.payload(), RoleHaltBody.class);
        long now = task.dueTimeMillis();
        RoleTradingRun run = requireTradingRun();
        RoleTradingModel.Target target = RoleTradingModel.target(run.trading(), body.targetId());
        if (target == null || target.current().version() != body.version()) {
            finishSystemRoleTask(task, run.trading(), state.accounts(), state.securities(),
                    state.exchange(), "STALE_VERSION");
            return;
        }
        RoleTradingModel.Result released = RoleTradingModel.releaseHalt(run.trading(),
                body.targetId(), now);
        RoleTradingModel.State after = released.accepted() ? released.state() : run.trading();
        finishSystemRoleTask(task, after, state.accounts(), state.securities(), state.exchange(),
                released.accepted() ? "OK" : released.reason());
    }

    /** 系统任务统一收尾：完成待办、写领域事件与全量状态；系统任务不产生人物 client 回报。 */
    private void finishSystemRoleTask(PendingTask task, RoleTradingModel.State trading,
                                      Map<String, CashLedger.CashAccount> accounts,
                                      SecuritiesLedger.State securities,
                                      WorldExchange.State exchange, String reason)
            throws IOException {
        finishSystemRoleTask(task, trading, accounts, securities, exchange, reason, List.of());
    }

    private void finishSystemRoleTask(PendingTask task, RoleTradingModel.State trading,
                                      Map<String, CashLedger.CashAccount> accounts,
                                      SecuritiesLedger.State securities,
                                      WorldExchange.State exchange, String reason,
                                      List<WorldEvent> effects) throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        events.addAll(effects);
        RoleTradingRun candidateRun = requireTradingRun().withTrading(trading);
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), accounts, pending,
                state.delivered(), used, state.commandContents(), state.commandOutcomes(), securities,
                exchange, state.manufacturing(), state.governance(), state.roles(),
                state.information(), candidateRun);
        commitAndApply("task:" + task.taskId(), task.payload(), reason, events, candidate);
    }

    /** 系统任务统一收尾，并把新排的定时任务一并写入；可携带领域事件。 */
    private void finishWithExtra(PendingTask task, RoleTradingModel.State trading,
                                 Map<String, CashLedger.CashAccount> accounts,
                                 SecuritiesLedger.State securities, WorldExchange.State exchange,
                                 String reason, List<PendingTask> extra) throws IOException {
        finishWithExtra(task, trading, accounts, securities, exchange, reason, extra, List.of());
    }

    private void finishWithExtra(PendingTask task, RoleTradingModel.State trading,
                                 Map<String, CashLedger.CashAccount> accounts,
                                 SecuritiesLedger.State securities, WorldExchange.State exchange,
                                 String reason, List<PendingTask> extra, List<WorldEvent> effects)
            throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        events.addAll(effects);
        for (PendingTask candidate : extra) {
            if (used.contains(candidate.taskId())) {
                continue;
            }
            pending.add(candidate);
            used.add(candidate.taskId());
            events.add(new WorldEvent("task-scheduled:" + candidate.taskId(), "TASK_SCHEDULED_V1",
                    mapper.writeValueAsString(candidate)));
        }
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), accounts, pending,
                state.delivered(), used, state.commandContents(), state.commandOutcomes(), securities,
                exchange, state.manufacturing(), state.governance(), state.roles(),
                state.information(), requireTradingRun().withTrading(trading));
        commitAndApply("task:" + task.taskId(), task.payload(), reason, events, candidate);
    }

    /** 观察任职：本次会话固定的组织→任职必须仍在任且属于该人物。 */
    private String observedFor(RoleActionPayload payload, String organizationPartyId,
                               RoleAccess.State access, long now) {
        if (organizationPartyId == null || payload.appointments() == null) {
            return null;
        }
        String appointmentId = payload.appointments().get(organizationPartyId);
        if (appointmentId == null) {
            return null;
        }
        for (RoleAccess.Appointment appointment : access.appointments()) {
            if (appointment.id().equals(appointmentId)
                    && appointment.personPartyId().equals(payload.personPartyId())
                    && appointment.organizationPartyId().equals(organizationPartyId)
                    && appointment.activeAt(now)) {
                return appointmentId;
            }
        }
        return null;
    }

    private static int latestRevision(RoleTradingModel.State trading, String targetId) {
        RoleTradingModel.Target target = RoleTradingModel.target(trading, targetId);
        if (target == null) {
            return 0;
        }
        RoleTradingModel.RiskReview review = RoleTradingModel.currentReview(trading, targetId,
                target.current().version());
        return review == null ? 0 : review.revision();
    }

    private static RoleTradingModel.ChildLink linkOf(RoleTradingModel.State trading,
                                                     WorldRoleSession.ChildRef ref) {
        for (RoleTradingModel.ChildLink link : trading.children()) {
            if (link.targetId().equals(ref.parentId())
                    && link.personPartyId().equals(ref.personPartyId())
                    && link.childId().equals(ref.childId())) {
                return link;
            }
        }
        return null;
    }

    private boolean hasPendingSweep(String targetId) throws IOException {
        for (PendingTask pending : state.pending()) {
            if (PendingTask.ROLE_TRADING_SWEEP_V1.equals(pending.kind())) {
                RoleSweepBody body = mapper.readValue(pending.payload(), RoleSweepBody.class);
                if (body.targetId().equals(targetId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return 该目标是否还有活动子单 */
    private static boolean hasLiveChild(RoleTradingModel.State trading, WorldExchange.State exchange,
                                         String targetId) {
        for (RoleTradingModel.ChildLink link : trading.children()) {
            if (!link.targetId().equals(targetId)) {
                continue;
            }
            WorldExchange.Order order = exchange.order(link.orderId());
            if (order != null && order.live()) {
                return true;
            }
        }
        return false;
    }

    /** 目标阶段变化后的跟排：UPDATING 撤单；待审/执行同时排目标截止与当前审核期限。 */
    private void scheduleFollowing(RoleTradingModel.State trading, String targetId,
                                   List<PendingTask> extra, long now) {
        RoleTradingModel.Target target = RoleTradingModel.target(trading, targetId);
        if (target == null) {
            return;
        }
        if (target.status() == RoleTradingModel.Status.UPDATING) {
            scheduleSweep(targetId, target.current().version(), extra, now);
            return;
        }
        if (target.status() == RoleTradingModel.Status.PENDING_REVIEW
                || target.status() == RoleTradingModel.Status.ACTIVE) {
            RoleTradingModel.RiskReview review = RoleTradingModel.currentReview(trading, targetId,
                    target.current().version());
            scheduleExpire(targetId, target.current().version(), 0,
                    target.current().deadlineMillis(), extra, now);
            if (review != null) {
                scheduleExpire(targetId, target.current().version(), review.revision(),
                        review.validUntilMillis(), extra, now);
            }
        }
    }

    /** 内部标识探测：与已用、在队任务都不冲突，保留原有公平顺序语义。 */
    private String freeRoleTaskId(String stem) {
        Set<String> taken = new LinkedHashSet<>(state.usedTaskIds());
        for (PendingTask pending : state.pending()) {
            taken.add(pending.taskId());
        }
        String candidate = stem;
        int suffix = 2;
        while (taken.contains(candidate)) {
            candidate = stem + ":v" + suffix;
            suffix++;
        }
        return candidate;
    }

    private void scheduleSweep(String targetId, int version, List<PendingTask> extra, long now) {
        for (PendingTask pending : state.pending()) {
            if (!PendingTask.ROLE_TRADING_SWEEP_V1.equals(pending.kind())) {
                continue;
            }
            try {
                RoleSweepBody body = mapper.readValue(pending.payload(), RoleSweepBody.class);
                if (body.targetId().equals(targetId) && body.version() == version) {
                    return;
                }
            } catch (IOException ignored) {
                // 解析不了的旧待办不由这里处理，交给恢复校验
            }
        }
        try {
            String taskId = freeRoleTaskId("roleT:sweep:" + targetId + ":" + version + ":" + now);
            extra.add(new PendingTask(taskId, now + 1, WorldScheduler.Phase.RULES,
                    PendingTask.ROLE_TRADING_SWEEP_V1,
                    mapper.writeValueAsString(new RoleSweepBody(targetId, version))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("无法序列化撤单扫描参数", failure);
        }
    }

    private void scheduleExpire(String targetId, int version, int revision, long due,
                                List<PendingTask> extra, long now) {
        if (due <= now) {
            return;
        }
        for (PendingTask pending : state.pending()) {
            if (!PendingTask.ROLE_TRADING_EXPIRE_V1.equals(pending.kind())) {
                continue;
            }
            try {
                RoleExpireBody body = mapper.readValue(pending.payload(), RoleExpireBody.class);
                if (body.targetId().equals(targetId) && body.version() == version
                        && body.revision() == revision) {
                    return;
                }
            } catch (IOException ignored) {
                // 同上：解析失败留给恢复校验
            }
        }
        try {
            String stem = "roleT:expire:" + targetId + ":" + version + ":" + revision;
            String taskId = freeRoleTaskId(stem);
            extra.add(new PendingTask(taskId, due, WorldScheduler.Phase.RULES,
                    PendingTask.ROLE_TRADING_EXPIRE_V1,
                    mapper.writeValueAsString(new RoleExpireBody(targetId, version, revision))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("无法序列化到期参数", failure);
        }
    }

    private void scheduleHaltRelease(String targetId, int version, long due,
                                     List<PendingTask> extra) {
        for (PendingTask pending : state.pending()) {
            if (!PendingTask.ROLE_TRADING_HALT_RELEASE_V1.equals(pending.kind())) {
                continue;
            }
            try {
                RoleHaltBody body = mapper.readValue(pending.payload(), RoleHaltBody.class);
                if (body.targetId().equals(targetId) && body.version() == version
                        && pending.dueTimeMillis() == due) {
                    return;
                }
            } catch (IOException ignored) {
                // 同上
            }
        }
        try {
            String stem = "roleT:halt:" + targetId + ":" + version + ":" + due;
            String taskId = freeRoleTaskId(stem);
            extra.add(new PendingTask(taskId, due, WorldScheduler.Phase.RULES,
                    PendingTask.ROLE_TRADING_HALT_RELEASE_V1,
                    mapper.writeValueAsString(new RoleHaltBody(targetId, version))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("无法序列化暂停到期参数", failure);
        }
    }

    private void scheduleAppointmentEnd(String appointmentId, List<PendingTask> extra) {
        RoleAccess.Appointment appointment = appointmentOf(state, appointmentId);
        if (appointment == null) {
            return;
        }
        long endsAt = appointment.effectiveEnd();
        for (PendingTask pending : state.pending()) {
            if (!PendingTask.ROLE_TRADING_APPOINTMENT_END_V1.equals(pending.kind())) {
                continue;
            }
            try {
                RoleAppointmentBody body = mapper.readValue(pending.payload(),
                        RoleAppointmentBody.class);
                if (body.appointmentId().equals(appointmentId)) {
                    return;
                }
            } catch (IOException ignored) {
                // 同上
            }
        }
        try {
            String taskId = freeRoleTaskId("roleT:apptEnd:" + appointmentId);
            extra.add(new PendingTask(taskId, endsAt, WorldScheduler.Phase.RULES,
                    PendingTask.ROLE_TRADING_APPOINTMENT_END_V1,
                    mapper.writeValueAsString(new RoleAppointmentBody(appointmentId))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("无法序列化任职结束参数", failure);
        }
    }

    /**
     * 四岗交易子集的恢复校验：位置/时间不越界、历史前缀不可改、无孤儿链接。
     *
     * @param candidate 候选状态
     * @param previous  上一确认状态；开局为空
     * @throws IOException 违反不变量
     */
    private void validateTrading(WorldState candidate, WorldState previous) throws IOException {
        if (expectedSchema() < STATE_SCHEMA_ROLE_TRADING) {
            if (candidate.trading() != null) {
                throw new IOException("旧档案不得包含四岗交易子集状态");
            }
            return;
        }
        if (candidate.trading() == null) {
            throw new IOException("四岗交易档案缺少子集状态");
        }
        RoleTradingRun run = candidate.trading();
        validateRoleTimers(candidate, run.trading());
        for (RoleTradingRun.Report report : run.reports().values()) {
            if (report.position() > candidate.position()) {
                throw new IOException("交易回报出现在未来的提交位置：" + report.taskId());
            }
            if (report.timeMillis() > candidate.timeMillis()) {
                throw new IOException("交易回报时点晚于快照时点：" + report.taskId());
            }
            if (!candidate.usedTaskIds().contains(report.taskId())) {
                throw new IOException("交易回报缺少对应的已用任务：" + report.taskId());
            }
            roleRequest(candidate, report);
        }
        for (RoleTradingModel.ChildLink link : run.trading().children()) {
            WorldExchange.Order order = candidate.exchange().order(link.orderId());
            if (order == null) {
                throw new IOException("子单链接指向未登记订单：" + link.childId());
            }
            RoleTradingModel.Target target = RoleTradingModel.target(run.trading(), link.targetId());
            if (target == null || !order.accountId().equals(target.accountId())
                    || !order.symbol().equals(target.symbol())) {
                throw new IOException("子单链接与目标账户/标的不一致：" + link.childId());
            }
        }
        Map<String, RoleTradingRun.Report> reports = run.reports();
        InformationModel.State information = candidate.information() == null ? null
                : candidate.information().information();
        for (RoleTradingModel.Research note : run.trading().research()) {
            RoleTradingRun.Report creating = reports.get(researchTaskId(note, reports));
            if (creating == null) {
                throw new IOException("研究报告缺少创建回报：" + note.researchId());
            }
            if (!creating.accepted() || !creating.personPartyId().equals(note.authorPersonPartyId())
                    || !creating.subjectId().equals(note.researchId())
                    || creating.timeMillis() != note.createdAtMillis()) {
                throw new IOException("研究报告与创建命令不一致：" + note.researchId());
            }
            InformationModel.Document document = information == null ? null
                    : documentOf(information, note.docId());
            if (document == null || document.audience().kind()
                    != InformationModel.AudienceKind.ORGANIZATION
                    || !note.organizationPartyId().equals(document.audience().partyId())
                    || document.at(note.docVersion()) == null) {
                throw new IOException("研究报告缺少对应的组织文档版本：" + note.researchId());
            }
            for (RoleTradingModel.Citation citation : note.citations()) {
                if (information == null || !InformationModel.canCite(information,
                        note.authorPersonPartyId(), citation.docId(), citation.version(),
                        note.createdAtMillis())) {
                    throw new IOException("研究报告引用了作者当时未读完的版本："
                            + note.researchId());
                }
            }
        }
        for (RoleTradingModel.Target target : run.trading().targets()) {
            checkTargetHistory(target, candidate, previous);
            if (!terminalStatus(target.status())
                    && !hasPendingExpiry(candidate, target.targetId(),
                    target.current().version(), 0)) {
                throw new IOException("非终态目标缺少截止任务：" + target.targetId());
            }
            RoleTradingModel.RiskReview review = RoleTradingModel.currentReview(run.trading(),
                    target.targetId(), target.current().version());
            if (!terminalStatus(target.status()) && review != null
                    && review.validUntilMillis() > candidate.timeMillis()
                    && !hasPendingExpiry(candidate, target.targetId(), target.current().version(),
                    review.revision())) {
                throw new IOException("当前审核缺少到期任务：" + target.targetId());
            }
            if (target.status() == RoleTradingModel.Status.HALTED
                    && !hasPendingTargetTask(candidate, PendingTask.ROLE_TRADING_HALT_RELEASE_V1,
                    target.targetId(), target.current().version(), target.haltedUntilMillis())) {
                throw new IOException("暂停状态缺少对应恢复待办：" + target.targetId());
            }
            long sweepDue = target.status() == RoleTradingModel.Status.UPDATING
                    ? target.pending().requestedAtMillis() + 1 : 0;
            if (hasLiveChild(run.trading(), candidate.exchange(), target.targetId())) {
                switch (target.status()) {
                    case HALTED -> sweepDue = run.trading().halts().stream().filter(halt ->
                            halt.targetId().equals(target.targetId())
                                    && halt.targetVersion() == target.current().version()
                                    && halt.untilMillis() == target.haltedUntilMillis())
                            .reduce((first, last) -> last)
                            .orElseThrow(() -> new IOException("暂停状态缺少来源：" + target.targetId()))
                            .issuedAtMillis() + 1;
                    case CANCELLED -> sweepDue = reports.values().stream().filter(report ->
                            report.accepted() && report.subjectId().equals(target.targetId())
                                    && report.kind().equals(PendingTask.ROLE_TRADING_CANCEL_V1))
                            .max(Comparator.comparingLong(RoleTradingRun.Report::position))
                            .orElseThrow(() -> new IOException("取消状态缺少来源：" + target.targetId()))
                            .timeMillis() + 1;
                    case PENDING_REVIEW -> {
                        if (review == null) {
                            throw new IOException("待审核目标存在未终结旧子单：" + target.targetId());
                        }
                        sweepDue = review.reviewedAtMillis() + 1;
                    }
                    case EXPIRED, COMPLETED -> throw new IOException("终结目标仍有活动子单：" + target.targetId());
                    default -> { }
                }
            }
            if (sweepDue > 0 && !hasPendingTargetTask(candidate, PendingTask.ROLE_TRADING_SWEEP_V1,
                    target.targetId(), target.current().version(), sweepDue)) {
                throw new IOException("停止或更新状态缺少撤单待办：" + target.targetId());
            }
        }
        for (RoleTradingModel.ChildLink link : run.trading().children()) {
            WorldExchange.Order order = candidate.exchange().order(link.orderId());
            RoleAccess.Appointment linkAppointment = appointmentOf(candidate, link.appointmentId());
            if (linkAppointment != null && linkAppointment.activeAt(candidate.timeMillis())
                    && !hasPendingAppointmentEnd(candidate, link.appointmentId())) {
                throw new IOException("活动子单缺少任职结束任务：" + link.childId());
            }
            RoleTradingRun.Report creating = reports.get(link.taskId());
            if (creating == null || !creating.accepted()
                    || !creating.personPartyId().equals(link.personPartyId())
                    || !creating.subjectId().equals(link.childId())
                    || creating.timeMillis() != order.acceptedAtMillis()) {
                throw new IOException("子单链接与创建命令不一致：" + link.childId());
            }
            RoleActionPayload request = roleRequest(candidate, creating);
            if (!PendingTask.ROLE_TRADING_CHILD_V1.equals(request.kind())) {
                throw new IOException("子单链接没有对应的子单请求：" + link.childId());
            }
            WorldRoleSession.ChildDraft draft = mapper.readValue(request.body(),
                    WorldRoleSession.ChildDraft.class);
            RoleTradingModel.Target parent = RoleTradingModel.target(run.trading(), link.targetId());
            RoleTradingModel.TargetVersion version = parent.versions().get(link.targetVersion() - 1);
            if (!draft.parentId().equals(link.targetId())
                    || draft.parentVersion() != link.targetVersion()
                    || draft.riskRevision() != link.riskRevision()
                    || !draft.childId().equals(link.childId())
                    || draft.quantity() != order.quantity()
                    || order.type() != OrderType.LIMIT || version.side() != order.side()
                    || draft.limitPrice().compareTo(order.limitPrice()) != 0
                    || !order.taskId().equals(link.taskId())
                    || !order.commandId().equals(request.commandId())
                    || !link.appointmentId().equals(request.appointments().get(parent.organizationPartyId()))) {
                throw new IOException("真实子单与原请求不一致：" + link.childId());
            }
            RoleAccess.Appointment appointment = appointmentOf(candidate, link.appointmentId());
            if (appointment == null || !appointment.personPartyId().equals(link.personPartyId())
                    || !appointment.organizationPartyId().equals(
                    orderOrganization(run.trading(), link))
                    || !appointment.activeAt(order.acceptedAtMillis())) {
                throw new IOException("子单链接的任职在受理时无效：" + link.childId());
            }
        }
        if (previous == null || previous.trading() == null) {
            return;
        }
        RoleTradingModel.State before = previous.trading().trading();
        RoleTradingModel.State after = run.trading();
        requirePrefix(before.research(), after.research(), "研究报告");
        requirePrefix(before.children(), after.children(), "子单链接");
        requirePrefix(before.reviews(), after.reviews(), "风险审核");
        requirePrefix(before.halts(), after.halts(), "风险暂停");
        for (RoleTradingModel.RiskReview review : after.reviews().subList(before.reviews().size(),
                after.reviews().size())) {
            RoleActionPayload request = acceptedRoleRequest(candidate, PendingTask.ROLE_TRADING_REVIEW_V1,
                    review.targetId());
            WorldRoleSession.ReviewDraft draft = mapper.readValue(request.body(), WorldRoleSession.ReviewDraft.class);
            RoleTradingModel.Target parent = RoleTradingModel.target(after, review.targetId());
            String appointment = observedFor(request, parent.organizationPartyId(),
                    candidate.roles().access(), request.dueMillis());
            if (appointment == null || appointmentOf(candidate, appointment).role() != RoleAccess.Role.RISK_OFFICER) {
                throw new IOException("风险审核的原任职无效：" + review.targetId());
            }
            RoleTradingModel.RiskReview expected = new RoleTradingModel.RiskReview(draft.targetId(),
                    draft.expectedVersion(), review.revision(), draft.approved(), draft.maxTotalQuantity(),
                    draft.maxChildQuantity(), draft.priceLimit(), draft.validUntilMillis(), draft.reason(),
                    request.personPartyId(), appointment, request.dueMillis());
            if (!review.equals(expected)) {
                throw new IOException("风险审核与原请求不一致：" + review.targetId());
            }
        }
        for (RoleTradingModel.Halt halt : after.halts().subList(before.halts().size(), after.halts().size())) {
            RoleActionPayload request = acceptedRoleRequest(candidate, PendingTask.ROLE_TRADING_HALT_V1,
                    halt.targetId());
            WorldRoleSession.HaltDraft draft = mapper.readValue(request.body(), WorldRoleSession.HaltDraft.class);
            RoleTradingModel.Target parent = RoleTradingModel.target(after, halt.targetId());
            String appointment = observedFor(request, parent.organizationPartyId(),
                    candidate.roles().access(), request.dueMillis());
            if (appointment == null || appointmentOf(candidate, appointment).role() != RoleAccess.Role.RISK_OFFICER) {
                throw new IOException("风险暂停的原任职无效：" + halt.targetId());
            }
            RoleTradingModel.Halt expected = new RoleTradingModel.Halt(draft.haltId(), draft.targetId(),
                    draft.expectedVersion(), draft.untilMillis(), draft.reason(), request.personPartyId(),
                    appointment, request.dueMillis());
            if (!halt.equals(expected)) {
                throw new IOException("风险暂停与原请求不一致：" + halt.haltId());
            }
        }
        if (after.targets().size() < before.targets().size()) {
            throw new IOException("目标历史被删减");
        }
        for (int index = 0; index < before.targets().size(); index++) {
            RoleTradingModel.Target old = before.targets().get(index);
            RoleTradingModel.Target now = after.targets().get(index);
            if (!old.targetId().equals(now.targetId())
                    || old.organizationPartyId().equals("") || !old.organizationPartyId()
                    .equals(now.organizationPartyId())
                    || !old.productOwnerId().equals(now.productOwnerId())
                    || !old.accountId().equals(now.accountId())
                    || !old.symbol().equals(now.symbol())) {
                throw new IOException("目标范围被改写：" + old.targetId());
            }
            requirePrefix(old.versions(), now.versions(), "目标版本");
        }
        Map<String, RoleTradingRun.Report> history = previous.trading().reports();
        for (Map.Entry<String, RoleTradingRun.Report> entry : history.entrySet()) {
            RoleTradingRun.Report now = reports.get(entry.getKey());
            if (now == null || !now.equals(entry.getValue())) {
                throw new IOException("交易回报历史被改写：" + entry.getKey());
            }
        }
    }

    private static boolean terminalStatus(RoleTradingModel.Status status) {
        return status == RoleTradingModel.Status.CANCELLED
                || status == RoleTradingModel.Status.EXPIRED
                || status == RoleTradingModel.Status.COMPLETED;
    }

    private boolean hasPendingTargetTask(WorldState candidate, String kind, String target, int version,
                                          long due) throws IOException {
        for (PendingTask task : candidate.pending()) {
            if (task.kind().equals(kind) && task.dueTimeMillis() == due) {
                RoleSweepBody body = mapper.readValue(task.payload(), RoleSweepBody.class);
                if (body.targetId().equals(target) && body.version() == version) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 系统待办须引用真实历史对象，期限与对应业务记录完全相同。 */
    private void validateRoleTimers(WorldState candidate, RoleTradingModel.State trading) throws IOException {
        for (PendingTask task : candidate.pending()) {
            switch (task.kind()) {
                case PendingTask.ROLE_TRADING_EXPIRE_V1 -> {
                    RoleExpireBody body = mapper.readValue(task.payload(), RoleExpireBody.class);
                    RoleTradingModel.Target target = timerTarget(trading, body.targetId(), body.version());
                    long expected = target.versions().get(body.version() - 1).deadlineMillis();
                    if (body.revision() != 0) {
                        RoleTradingModel.RiskReview review = trading.reviews().stream().filter(item ->
                                item.targetId().equals(body.targetId()) && item.targetVersion() == body.version()
                                        && item.revision() == body.revision()).findFirst()
                                .orElseThrow(() -> new IOException("到期待办引用未知审核：" + task.taskId()));
                        expected = review.validUntilMillis();
                    }
                    if (task.dueTimeMillis() != expected) {
                        throw new IOException("到期待办与业务期限不一致：" + task.taskId());
                    }
                }
                case PendingTask.ROLE_TRADING_HALT_RELEASE_V1 -> {
                    RoleHaltBody body = mapper.readValue(task.payload(), RoleHaltBody.class);
                    timerTarget(trading, body.targetId(), body.version());
                    boolean known = trading.halts().stream().anyMatch(halt ->
                            halt.targetId().equals(body.targetId()) && halt.targetVersion() == body.version()
                                    && halt.untilMillis() == task.dueTimeMillis());
                    if (!known) {
                        throw new IOException("暂停待办引用未知期限：" + task.taskId());
                    }
                }
                case PendingTask.ROLE_TRADING_APPOINTMENT_END_V1 -> {
                    RoleAppointmentBody body = mapper.readValue(task.payload(), RoleAppointmentBody.class);
                    RoleAccess.Appointment appointment = appointmentOf(candidate, body.appointmentId());
                    if (appointment == null || task.dueTimeMillis() != appointment.endsAt()) {
                        throw new IOException("任职待办与原任期不一致：" + task.taskId());
                    }
                }
                case PendingTask.ROLE_TRADING_SWEEP_V1 -> {
                    RoleSweepBody body = mapper.readValue(task.payload(), RoleSweepBody.class);
                    timerTarget(trading, body.targetId(), body.version());
                }
                default -> { }
            }
        }
    }

    private static RoleTradingModel.Target timerTarget(RoleTradingModel.State trading, String id,
                                                        int version) throws IOException {
        RoleTradingModel.Target target = RoleTradingModel.target(trading, id);
        if (target == null || version < 1 || version > target.versions().size()) {
            throw new IOException("系统待办引用未知目标版本：" + id);
        }
        return target;
    }

    /** 候选状态里是否仍排着指定 (target, version, revision) 的到期任务。 */
    private boolean hasPendingExpiry(WorldState candidate, String targetId, int version,
                                     int revision) {
        for (PendingTask pending : candidate.pending()) {
            if (!PendingTask.ROLE_TRADING_EXPIRE_V1.equals(pending.kind())) {
                continue;
            }
            try {
                RoleExpireBody body = mapper.readValue(pending.payload(), RoleExpireBody.class);
                if (body.targetId().equals(targetId) && body.version() == version
                        && body.revision() == revision) {
                    return true;
                }
            } catch (IOException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean hasPendingAppointmentEnd(WorldState candidate, String appointmentId) {
        for (PendingTask pending : candidate.pending()) {
            if (!PendingTask.ROLE_TRADING_APPOINTMENT_END_V1.equals(pending.kind())) {
                continue;
            }
            try {
                RoleAppointmentBody body = mapper.readValue(pending.payload(),
                        RoleAppointmentBody.class);
                if (body.appointmentId().equals(appointmentId)) {
                    return true;
                }
            } catch (IOException ignored) {
                return false;
            }
        }
        return false;
    }

    /** 前缀必须原样保留：历史只追加。 */
    private static <T> void requirePrefix(List<T> before, List<T> after, String field)
            throws IOException {
        if (after.size() < before.size() || !after.subList(0, before.size()).equals(before)) {
            throw new IOException(field + "历史被改写");
        }
    }

    /** 新版本按创建批次的真实持仓核验，已有版本由历史前缀保护。 */
    private void checkTargetHistory(RoleTradingModel.Target target, WorldState candidate,
                                    WorldState previous) throws IOException {
        RoleTradingModel.Target old = previous == null || previous.trading() == null ? null
                : RoleTradingModel.target(previous.trading().trading(), target.targetId());
        int known = old == null ? 0 : old.versions().size();
        long held = RoleTradingModel.held(candidate.securities(), target.accountId(), target.symbol());
        if (target.versions().size() > known) {
            RoleTradingModel.TargetVersion expected;
            if (known == 0) {
                WorldRoleSession.TargetDraft draft = targetRequest(candidate, target, 0);
                expected = new RoleTradingModel.TargetVersion(1, draft.absoluteTargetQuantity(),
                        draft.priceLimit(), draft.deadlineMillis(), draft.sourceResearchId(), held,
                        candidate.timeMillis());
            } else {
                RoleTradingModel.PendingUpdate pending = old.pending();
                if (pending == null) {
                    throw new IOException("新目标版本缺少原更新请求：" + target.targetId());
                }
                expected = new RoleTradingModel.TargetVersion(pending.version(),
                        pending.absoluteTargetQuantity(), pending.priceLimit(), pending.deadlineMillis(),
                        pending.sourceResearchId(), held, candidate.timeMillis());
            }
            if (!target.versions().subList(known, target.versions().size()).equals(List.of(expected))) {
                throw new IOException("目标版本与原请求或实际持仓不一致：" + target.targetId());
            }
        }
        if (target.pending() != null && (old == null || !target.pending().equals(old.pending()))) {
            WorldRoleSession.TargetDraft draft = targetRequest(candidate, target, target.current().version());
            RoleTradingModel.PendingUpdate expected = new RoleTradingModel.PendingUpdate(
                    target.current().version() + 1, draft.absoluteTargetQuantity(), draft.priceLimit(),
                    draft.deadlineMillis(), draft.sourceResearchId(), candidate.timeMillis());
            if (!target.pending().equals(expected)) {
                throw new IOException("目标更新与经理原请求不一致：" + target.targetId());
            }
        }
    }

    private WorldRoleSession.TargetDraft targetRequest(WorldState candidate,
                                                       RoleTradingModel.Target target,
                                                       int expectedVersion) throws IOException {
        RoleActionPayload request = acceptedRoleRequest(candidate, PendingTask.ROLE_TRADING_TARGET_V1,
                target.targetId());
        WorldRoleSession.TargetDraft draft = mapper.readValue(request.body(), WorldRoleSession.TargetDraft.class);
        String appointment = observedFor(request, target.organizationPartyId(),
                candidate.roles().access(), request.dueMillis());
        if (draft.expectedVersion() != expectedVersion
                || !draft.organizationPartyId().equals(target.organizationPartyId())
                || !draft.productOwnerId().equals(target.productOwnerId())
                || !draft.accountId().equals(target.accountId()) || !draft.symbol().equals(target.symbol())
                || appointment == null
                || appointmentOf(candidate, appointment).role() != RoleAccess.Role.PORTFOLIO_MANAGER) {
            throw new IOException("目标范围或经理任职与原请求不一致：" + target.targetId());
        }
        return draft;
    }

    /** 新增工作流记录必须来自本批已接受的同类人物动作。 */
    private RoleActionPayload acceptedRoleRequest(WorldState candidate, String kind, String subject)
            throws IOException {
        for (RoleTradingRun.Report report : candidate.trading().reports().values()) {
            if (report.position() == candidate.position() && report.accepted()
                    && report.kind().equals(kind) && report.subjectId().equals(subject)) {
                return roleRequest(candidate, report);
            }
        }
        throw new IOException("工作流记录缺少本批原请求：" + subject);
    }

    /** 从唯一原命令核验人物回报的命名空间、动作、业务对象与到期时点。 */
    private RoleActionPayload roleRequest(WorldState candidate, RoleTradingRun.Report report)
            throws IOException {
        String command = roleCommandId(report.personPartyId(), report.clientCommandId());
        String content = candidate.commandContents().get(command);
        boolean linked = content != null
                && roleTaskId(report.personPartyId(), report.clientTaskId())
                .equals(report.taskId());
        if (!linked && candidate.decisions() != null) {
            // 决定触发的动作使用长度编码的决定命名空间，同一份原请求仍可逐字核验。
            DecisionRun.ActionRef ref = candidate.decisions().actionRef(report.clientTaskId());
            DecisionRun.Binding binding = candidate.decisions().binding(report.clientTaskId());
            if (ref != null && binding != null && ref.taskId().equals(report.taskId())
                    && binding.personPartyId().equals(report.personPartyId())) {
                command = DecisionRun.actionCommandId(report.clientTaskId());
                content = candidate.commandContents().get(command);
                linked = content != null;
            }
        }
        if (!linked) {
            throw new IOException("人物回报与原命令归属不一致：" + report.taskId());
        }
        RoleActionPayload request = mapper.readValue(content, RoleActionPayload.class);
        if (!command.equals(request.commandId())
                || !report.personPartyId().equals(request.personPartyId())
                || !report.clientCommandId().equals(request.clientCommandId())
                || !report.clientTaskId().equals(request.clientTaskId())
                || !report.kind().equals(request.kind()) || report.timeMillis() != request.dueMillis()
                || request.appointments() == null || request.body() == null) {
            throw new IOException("人物回报与原请求不一致：" + report.taskId());
        }
        String field = switch (request.kind()) {
            case PendingTask.ROLE_TRADING_READ_V1, PendingTask.ROLE_TRADING_UNREAD_V1 -> "docId";
            case PendingTask.ROLE_TRADING_RESEARCH_V1 -> "researchId";
            case PendingTask.ROLE_TRADING_CHILD_V1, PendingTask.ROLE_TRADING_CHILD_CANCEL_V1 -> "childId";
            case PendingTask.ROLE_TRADING_TARGET_V1, PendingTask.ROLE_TRADING_REVIEW_V1,
                 PendingTask.ROLE_TRADING_CONFIRM_V1, PendingTask.ROLE_TRADING_HALT_V1,
                 PendingTask.ROLE_TRADING_CANCEL_V1 -> "targetId";
            default -> throw new IOException("人物回报种类未登记：" + report.kind());
        };
        if (!report.subjectId().equals(mapper.readTree(request.body()).path(field).asText())) {
            throw new IOException("人物回报与原业务对象不一致：" + report.taskId());
        }
        return request;
    }

    private static String researchTaskId(RoleTradingModel.Research note,
                                         Map<String, RoleTradingRun.Report> reports) {
        for (Map.Entry<String, RoleTradingRun.Report> entry : reports.entrySet()) {
            if (entry.getValue().accepted() && entry.getValue().subjectId().equals(note.researchId())
                    && PendingTask.ROLE_TRADING_RESEARCH_V1.equals(entry.getValue().kind())) {
                return entry.getKey();
            }
        }
        return "";
    }

    private static String orderOrganization(RoleTradingModel.State trading,
                                            RoleTradingModel.ChildLink link) {
        RoleTradingModel.Target target = RoleTradingModel.target(trading, link.targetId());
        return target == null ? "" : target.organizationPartyId();
    }

    private static RoleAccess.Appointment appointmentOf(WorldState candidate, String appointmentId) {
        if (candidate.roles() == null) {
            return null;
        }
        for (RoleAccess.Appointment appointment : candidate.roles().access().appointments()) {
            if (appointment.id().equals(appointmentId)) {
                return appointment;
            }
        }
        return null;
    }

    /** 开局：交易档案要求初始任职人具备已配置的信息读者能力。 */
    private void validateTradingOpening() {
        for (RoleAccess.Appointment appointment : roleConfig.appointments()) {
            if (!informationConfig.readers().contains(appointment.personPartyId())) {
                throw new IllegalArgumentException("四岗交易档案的初始任职人必须是登记读者："
                        + appointment.personPartyId());
            }
        }
    }

    /**
     * 为内部回报任务找一个未被占用的标识：从 {@code notice:<源任务>} 起，被占用就再套一层前缀。
     *
     * <p>外部 taskId 不保留任何前缀，因此不能用“禁止某前缀”来隔离；这里以确定性探测保证
     * 内部标识既不与已排程任务冲突，也不与已用标识冲突。</p>
     *
     * @param sourceTaskId 产生回报的原任务标识
     * @param pending      当前待办（含本次尚未加入的内部任务之前的全部）
     * @param used         已用任务标识
     * @return 未被占用的内部任务标识
     */
    private static String freeNoticeTaskId(String sourceTaskId, List<PendingTask> pending,
                                           Set<String> used) {
        Set<String> pendingIds = new LinkedHashSet<>();
        pending.forEach(task -> pendingIds.add(task.taskId()));
        String candidate = "notice:" + sourceTaskId;
        while (used.contains(candidate) || pendingIds.contains(candidate)) {
            candidate = "notice:" + candidate;
        }
        return candidate;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
