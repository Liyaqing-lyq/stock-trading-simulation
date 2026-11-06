package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

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
                              InformationRun information) {

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
            while (true) {
                PendingTask next = peek(inclusiveMillis);
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
                    default -> throw new IllegalStateException("未启用的任务种类：" + next.kind());
                }
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
                Optional.ofNullable(state.information()),
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
        String person = personPartyId == null ? "" : personPartyId;
        InformationModel.State information = requireInformationRun().information();
        RoleAccess.State access = requireRoleRun().access();
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
        long now = state.timeMillis();

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
            CashLedger.CashAccount cash = state.accounts().get(entry.getKey());
            if (cash == null) {
                continue;
            }
            BigDecimal frozen = ZERO;
            if (state.exchange() != null) {
                frozen = WorldExchange.frozenCash(state.exchange(), entry.getKey());
            }
            accounts.add(new RoleView.Account(entry.getKey(), entry.getValue(), cash.ownerId(),
                    cash.cash(), frozen, cash.cash().subtract(frozen)));
        }
        List<RoleView.Holding> holdings = holdingsOf(visible.keySet());
        List<RoleView.Market> market = marketOf();
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
    private List<RoleView.Holding> holdingsOf(Set<String> accountIds) {
        List<RoleView.Holding> holdings = new ArrayList<>();
        if (state.securities() == null) {
            return holdings;
        }
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (SecuritiesLedger.Lot lot : state.securities().lots()) {
            if (!accountIds.contains(lot.accountId())) {
                continue;
            }
            long[] sums = totals.computeIfAbsent(lot.accountId() + "\u0000" + lot.symbol(),
                    key -> new long[]{0L, 0L});
            sums[0] = Math.addExact(sums[0], lot.remaining());
        }
        for (SecuritiesLedger.SellReservation reservation : state.securities().reservations()) {
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
    private List<RoleView.Market> marketOf() {
        List<RoleView.Market> market = new ArrayList<>();
        if (registry == null || state.exchange() == null) {
            return market;
        }
        for (OpeningRegistry.Security security : registry.securities()) {
            String symbol = security.symbol();
            WorldExchange.Trade lastTrade = null;
            for (WorldExchange.Trade trade : state.exchange().trades()) {
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
            for (WorldExchange.Order order : state.exchange().orders()) {
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
        try {
            if (PendingTask.ROLE_APPOINT_V1.equals(task.kind())) {
                access = RoleAccess.appoint(registry, access, payload.appointmentId(),
                        payload.personPartyId(), payload.organizationPartyId(),
                        RoleAccess.Role.valueOf(payload.role()), payload.startsAt(),
                        payload.endsAt(), now);
            } else {
                access = RoleAccess.revoke(access, payload.appointmentId(), now);
            }
            accepted = true;
            reason = "OK";
        } catch (IllegalArgumentException rejected) {
            accepted = false;
            reason = "REJECTED";
            access = run.access();
        }
        RoleRun.Report entry = new RoleRun.Report(task.taskId(), payload.commandId(),
                task.kind(), accepted, reason, payload.appointmentId(), now,
                state.position() + 1);
        finishRoleTask(task, run.withAccess(access).withReport(entry), entry);
    }

    /** 任职任务统一收尾：完成待办、写报告事件、保存全量状态后持久确认再发布。 */
    private void finishRoleTask(PendingTask task, RoleRun roles, RoleRun.Report entry)
            throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        events.add(new WorldEvent("role-report:" + task.taskId(), "ROLE_REPORT_V1",
                mapper.writeValueAsString(entry)));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), state.accounts(),
                pending, state.delivered(), used, state.commandContents(), state.commandOutcomes(),
                state.securities(), state.exchange(), state.manufacturing(), state.governance(),
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
                InformationModel.Outcome outcome = InformationModel.completeRead(before,
                        requireRoleRun().access(), payload.personPartyId(), payload.readId(), now);
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
            journal.commit(commandId, content, result, events);
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
            if (previous == null && !expectedInitial.equals(snapshot)) {
                throw new IOException("开局快照与期望开局状态不一致（必须等于清单绑定的初始资产）");
            }
            previous = snapshot;
        }
        return previous;
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
                found = mapper.readValue(event.payload(), WorldState.class);
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
        return new WorldState(expectedSchema(), manifest.runId(), 1, 0L,
                WorldScheduler.Phase.OBSERVATION.name(), accounts, pending, List.of(), used,
                Map.of(), Map.of(),
                manifest.hasSecurities() ? SecuritiesLedger.opening(registry) : null,
                manifest.hasMatching() ? WorldExchange.opening() : null,
                manufacturing, governance, roles, information);
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
                state.roles(), state.information());
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
        return new WorldState(state.schemaVersion(), state.runId(), state.position() + 1,
                timeMillis, lastPhase, accounts, pending, delivered, usedTaskIds,
                commandContents, commandOutcomes, securities, exchange, manufacturing, governance,
                roles, information);
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
