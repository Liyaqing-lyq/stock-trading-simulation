package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * schema18 工程经营子集：按公司的经营状态与董事会，是唯一世界日志里的纯值对象。
 *
 * <p><b>为什么不另造运行器</b>：本类型没有文件、线程、时钟或现金快照；它只持有
 * {@link EngineeringModel.State}（每公司一份）与 {@link BoardRun}（每公司一份）。现金仍然只在
 * {@link WorldRuntime} 的同一张 {@link CashLedger.CashAccount} 账户表里，开局权益由
 * {@link EngineeringModel#open} 从该账户现金派生，绝不复制世界现金基线。</p>
 *
 * <p><b>权威唯一</b>：每个有效经营公司在 {@link #companies()} 与 {@link #boards()} 里各出现一次。
 * 制造业董事会继续放在旧 {@code governance} 单例（schema ≤17 与 18 都用它），因此本类型的公司键
 * 域必须与制造业章程公司严格不交；两处合起来才是全部公司，不重复权威。</p>
 *
 * <p><b>客户是一个有限外部主体，不是一个规则引擎</b>：本类型只保存经理真实提出的请求、
 * 冻结计划给出的响应结果与真实发生的客户付款。它不保存客户余额、不复制现金、不推断未来政策。</p>
 *
 * @param config    冻结配置（每公司一份经营模板与章程）
 * @param companies 按公司 partyId 索引的经营状态
 * @param boards    按公司 partyId 索引的董事会运行
 * @param reports   工程任务审计回报，按 taskId 索引
 * @param customer  客户请求/响应/真实付款审计（旧档为空）
 * @param waivers   具体债权豁免决议（授权与消耗的唯一权威，旧档为空）
 * @param periodic  schema18 周期治理状态（旧档缺失即空；只在 schema18 写入）
 */
public record EngineeringRun(Config config, Map<String, EngineeringModel.State> companies,
                             Map<String, BoardRun> boards, Map<String, Report> reports,
                             Customer customer, List<BoardModel.DebtWaiver> waivers,
                             PeriodicModel.State periodic) {

    /** 旧六参构造：没有周期治理状态（旧档与≤17 路径）。 */
    public EngineeringRun(Config config, Map<String, EngineeringModel.State> companies,
                          Map<String, BoardRun> boards, Map<String, Report> reports,
                          Customer customer, List<BoardModel.DebtWaiver> waivers) {
        this(config, companies, boards, reports, customer, waivers,
                new PeriodicModel.State(List.of()));
    }

    /** 旧四参构造：没有客户审计与豁免决议。 */
    public EngineeringRun(Config config, Map<String, EngineeringModel.State> companies,
                          Map<String, BoardRun> boards, Map<String, Report> reports) {
        this(config, companies, boards, reports, Customer.empty(), List.of(),
                new PeriodicModel.State(List.of()));
    }

    /** 旧五参构造：没有豁免决议。 */
    public EngineeringRun(Config config, Map<String, EngineeringModel.State> companies,
                          Map<String, BoardRun> boards, Map<String, Report> reports,
                          Customer customer) {
        this(config, companies, boards, reports, customer, List.of(),
                new PeriodicModel.State(List.of()));
    }

    /**
     * 换入周期治理状态：唯一写入点，保持其它子集不变。
     *
     * @param next 新的周期状态
     * @return 新工程子集
     */
    public EngineeringRun withPeriodic(PeriodicModel.State next) {
        return new EngineeringRun(config, companies, boards, reports, customer, waivers,
                next);
    }

    /**
     * 取某公司的周期状态；旧档或未初始化的公司返回空。
     *
     * @param companyPartyId 公司组织
     * @return 该公司的周期状态
     */
    public java.util.Optional<PeriodicModel.CompanyPeriods> periodicOf(String companyPartyId) {
        for (PeriodicModel.CompanyPeriods periods : periodic.companies()) {
            if (periods.companyPartyId().equals(companyPartyId)) {
                return java.util.Optional.of(periods);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * 单条工程审计回报。
     *
     * @param taskId        产生回报的待办
     * @param commandId     原始命令（内部日程带 system: 前缀）
     * @param kind          任务种类
     * @param companyPartyId 公司组织
     * @param accepted      业务是否成功
     * @param reason        结果代码
     * @param timeMillis    执行时点
     * @param position      提交位置
     */
    public record Report(String taskId, String commandId, String kind, String companyPartyId,
                         boolean accepted, String reason, long timeMillis, long position) {
        /** 校验标识与时点。 */
        public Report {
            requireText(taskId, "工程回报任务");
            requireText(commandId, "工程回报命令");
            requireText(kind, "工程回报种类");
            requireText(companyPartyId, "工程回报公司");
            requireText(reason, "工程回报结果");
            if (timeMillis < 0 || position <= 0) {
                throw new IllegalArgumentException("工程回报时点或位置非法");
            }
        }
    }

    /** 校验索引一致、模板绑定与集合深不可变。 */
    public EngineeringRun {
        periodic = periodic == null ? new PeriodicModel.State(List.of()) : periodic;
        Objects.requireNonNull(config, "工程配置");
        Map<String, EngineeringModel.State> stateCopy = new LinkedHashMap<>(
                Objects.requireNonNull(companies, "工程经营状态"));
        Map<String, BoardRun> boardCopy = new LinkedHashMap<>(
                Objects.requireNonNull(boards, "工程董事会"));
        Map<String, Report> reportCopy = new TreeMap<>();
        if (reports != null) {
            for (Map.Entry<String, Report> entry : reports.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().taskId())) {
                    throw new IllegalArgumentException("工程回报索引与任务不一致："
                            + entry.getKey());
                }
                reportCopy.put(entry.getKey(), entry.getValue());
            }
        }
        customer = customer == null ? Customer.empty() : customer;
        if (!stateCopy.keySet().equals(boardCopy.keySet())) {
            throw new IllegalArgumentException("工程公司与董事会键域必须一致");
        }
        for (Map.Entry<String, EngineeringModel.State> entry : stateCopy.entrySet()) {
            CompanySpec spec = config.specOf(entry.getKey());
            if (spec == null) {
                throw new IllegalArgumentException("工程状态公司不在冻结配置中：" + entry.getKey());
            }
            if (!spec.engineeringConfig().equals(entry.getValue().config())) {
                throw new IllegalArgumentException("工程经营模板与冻结配置不一致："
                        + entry.getKey());
            }
            BoardRun board = boardCopy.get(entry.getKey());
            if (!spec.charter().equals(board.board().charter())) {
                throw new IllegalArgumentException("工程董事会章程与冻结配置不一致："
                        + entry.getKey());
            }
        }
        // 客户付款必须记在本公司唯一客户账户名下；不允许凭空写一个池子或另一家公司的账户。
        for (Customer.Payment payment : customer.payments()) {
            CompanySpec spec = config.specOf(payment.companyPartyId());
            if (spec == null) {
                throw new IllegalArgumentException("客户付款指向未知工程公司："
                        + payment.companyPartyId());
            }
            if (!spec.customerAccountId().equals(payment.poolAccountId())) {
                throw new IllegalArgumentException("客户付款账户必须就是本公司客户账户："
                        + payment.eventId() + " 声明 " + payment.poolAccountId()
                        + "，公司客户 " + spec.customerAccountId());
            }
        }
        // 豁免决议只能绑定本公司的真实会议与章程；授权与消耗由 BoardModel 同一口径自校验。
        List<BoardModel.DebtWaiver> waiverList = new ArrayList<>(
                waivers == null ? List.of() : waivers);
        waiverList.sort(java.util.Comparator.comparing(waiver -> waiver.request().waiverId()));
        for (BoardModel.DebtWaiver waiver : waiverList) {
            if (config.specOf(waiver.request().companyPartyId()) == null) {
                throw new IllegalArgumentException("豁免决议指向未知工程公司："
                        + waiver.request().waiverId());
            }
        }
        for (String company : stateCopy.keySet()) {
            List<BoardModel.DebtWaiver> companyWaivers = new ArrayList<>();
            for (BoardModel.DebtWaiver waiver : waiverList) {
                if (waiver.request().companyPartyId().equals(company)) {
                    companyWaivers.add(waiver);
                }
            }
            BoardModel.validateDebtWaivers(boardCopy.get(company).board().charter(),
                    boardCopy.get(company).board().meetings(), companyWaivers);
        }
        companies = Collections.unmodifiableMap(stateCopy);
        boards = Collections.unmodifiableMap(boardCopy);
        reports = Collections.unmodifiableMap(reportCopy);
        waivers = List.copyOf(waiverList);
    }

    /**
     * 客户期间预算的冻结声明：同一客户账户在所有公司间只有一份。
     *
     * <p>期间序号 = {@code now / periodMillis}，只覆盖冻结的 {@code periods} 个期间；
     * 超出即该期间没有预算。它是外部客户的有限支付能力，不是公司预算，也不与
     * {@code BoardModel.Budget} 共用额度。</p>
     *
     * @param customerAccountId 客户聚合账户（必须等于声明公司的客户账户）
     * @param periodMillis      单个期间长度，必须为正
     * @param budgetPerPeriod   每期可支付上限，精确到分且非负
     * @param periods           冻结的期间个数，必须为正
     */
    public record CustomerPool(String customerAccountId, long periodMillis,
                               BigDecimal budgetPerPeriod, int periods) {

        /** 校验账户、期间长度与金额。 */
        public CustomerPool {
            requireText(customerAccountId, "客户账户");
            if (periodMillis <= 0) {
                throw new IllegalArgumentException("客户预算期间必须为正：" + customerAccountId);
            }
            if (periods <= 0) {
                throw new IllegalArgumentException("客户预算期间个数必须为正：" + customerAccountId);
            }
            budgetPerPeriod = budgetPerPeriod == null ? CashLedger.money(BigDecimal.ZERO)
                    : CashLedger.money(budgetPerPeriod);
            if (budgetPerPeriod.signum() < 0) {
                throw new IllegalArgumentException("客户期间预算不能为负：" + customerAccountId);
            }
        }

        /** @return 该时点所属期间序号 */
        public long periodIndex(long now) {
            if (now < 0) {
                throw new IllegalArgumentException("模拟毫秒不能为负：" + now);
            }
            return now / periodMillis;
        }
    }

    /**
     * 客户交互审计：经理真实请求、外部客户的真实响应与真实付款。
     *
     * <p>三者都只由运行层在真实任务到期时写入；角色与模型都不能直接构造或改写。
     * 拒绝/延迟/逾期不是空报告：它们在此留下对应原请求、结果与当时的未清义务。</p>
     *
     * @param requests  经理请求，按 requestId 排序
     * @param responses 客户响应，按 eventId 排序
     * @param payments  真实客户付款，按 eventId 排序
     */
    public record Customer(List<Request> requests, List<Response> responses,
                           List<Payment> payments) {

        /** 深拷贝并按标识稳定排序。 */
        public Customer {
            List<Request> requestList = new ArrayList<>(
                    requests == null ? List.of() : requests);
            requestList.sort(Comparator.comparing(Request::requestId));
            List<Response> responseList = new ArrayList<>(
                    responses == null ? List.of() : responses);
            responseList.sort(Comparator.comparing(Response::eventId));
            List<Payment> paymentList = new ArrayList<>(
                    payments == null ? List.of() : payments);
            paymentList.sort(Comparator.comparing(Payment::eventId));
            requests = List.copyOf(requestList);
            responses = List.copyOf(responseList);
            payments = List.copyOf(paymentList);
        }

        /** @return 空客户审计 */
        public static Customer empty() {
            return new Customer(List.of(), List.of(), List.of());
        }

        /**
         * 追加一条经理请求；同一 requestId 只能出现一次。
         *
         * @param request 新请求
         * @return 追加后的新审计
         */
        public Customer withRequest(Request request) {
            List<Request> next = new ArrayList<>(requests);
            for (Request existing : requests) {
                if (existing.requestId().equals(request.requestId())) {
                    if (!existing.equals(request)) {
                        throw new IllegalArgumentException("同一客户请求标识内容不一致："
                                + request.requestId());
                    }
                    return this;
                }
            }
            next.add(request);
            return new Customer(next, responses, payments);
        }

        /**
         * 追加一条客户响应；同一 eventId 只能出现一次。
         *
         * @param response 新响应
         * @return 追加后的新审计
         */
        public Customer withResponse(Response response) {
            for (Response existing : responses) {
                if (existing.eventId().equals(response.eventId())) {
                    if (!existing.equals(response)) {
                        throw new IllegalArgumentException("同一客户响应事件内容不一致："
                                + response.eventId());
                    }
                    return this;
                }
            }
            List<Response> next = new ArrayList<>(responses);
            next.add(response);
            return new Customer(requests, next, payments);
        }

        /**
         * 追加一条真实客户付款。
         *
         * @param payment 新付款
         * @return 追加后的新审计
         */
        public Customer withPayment(Payment payment) {
            for (Payment existing : payments) {
                if (existing.eventId().equals(payment.eventId())) {
                    if (!existing.equals(payment)) {
                        throw new IllegalArgumentException("同一客户付款事件内容不一致："
                                + payment.eventId());
                    }
                    return this;
                }
            }
            List<Payment> next = new ArrayList<>(payments);
            next.add(payment);
            return new Customer(requests, responses, next);
        }

        /**
         * 按 (公司, 合同, 期次, 种类) 查最新未回答请求。
         *
         * @param companyPartyId 公司
         * @param contractId     合同
         * @param trancheIndex   期次
         * @param kind           请求种类
         * @return 未回答请求；不存在返回 null
         */
        public Request openRequest(String companyPartyId, String contractId, int trancheIndex,
                                   String kind) {
            Request found = null;
            for (Request request : requests) {
                if (!request.companyPartyId().equals(companyPartyId)
                        || !request.contractId().equals(contractId)
                        || request.trancheIndex() != trancheIndex
                        || !request.kind().equals(kind)) {
                    continue;
                }
                if (!request.answered()) {
                    found = request;
                }
            }
            return found;
        }

        /**
         * 按 (公司, 合同, 期次) 查最新未回答请求（任意种类）。
         *
         * @param companyPartyId 公司
         * @param contractId     合同
         * @param trancheIndex   期次
         * @return 未回答请求；不存在返回 null
         */
        public Request openRequestOf(String companyPartyId, String contractId, int trancheIndex) {
            Request found = null;
            for (Request request : requests) {
                if (request.companyPartyId().equals(companyPartyId)
                        && request.contractId().equals(contractId)
                        && request.trancheIndex() == trancheIndex
                        && !request.answered()) {
                    found = request;
                }
            }
            return found;
        }

        /**
         * 标记请求已被某个响应回答。
         *
         * @param requestId 请求标识
         * @param eventId   响应事件
         * @param atMillis  响应时点
         * @return 更新后的审计
         */
        public Customer answered(String requestId, String eventId, long atMillis) {
            List<Request> next = new ArrayList<>(requests);
            for (int i = 0; i < next.size(); i++) {
                Request request = next.get(i);
                if (request.requestId().equals(requestId)) {
                    next.set(i, request.answer(eventId, atMillis));
                }
            }
            return new Customer(next, responses, payments);
        }

        /**
         * 某客户账户在某期间已真实支付的合计（预算余量的唯一来源）。
         *
         * @param poolAccountId 客户账户
         * @param period        期间序号
         * @return 合计金额
         */
        public BigDecimal paidInPeriod(String poolAccountId, long period) {
            BigDecimal total = CashLedger.money(BigDecimal.ZERO);
            for (Payment payment : payments) {
                if (payment.poolAccountId().equals(poolAccountId) && payment.period() == period) {
                    total = total.add(payment.amount());
                }
            }
            return CashLedger.money(total);
        }

        /** @return 某公司全部请求 */
        public List<Request> requestsOf(String companyPartyId) {
            List<Request> out = new ArrayList<>();
            for (Request request : requests) {
                if (request.companyPartyId().equals(companyPartyId)) {
                    out.add(request);
                }
            }
            return List.copyOf(out);
        }

        /** @return 某公司全部响应 */
        public List<Response> responsesOf(String companyPartyId) {
            List<Response> out = new ArrayList<>();
            for (Response response : responses) {
                if (response.companyPartyId().equals(companyPartyId)) {
                    out.add(response);
                }
            }
            return List.copyOf(out);
        }

        /** @return 某公司全部真实付款 */
        public List<Payment> paymentsOf(String companyPartyId) {
            List<Payment> out = new ArrayList<>();
            for (Payment payment : payments) {
                if (payment.companyPartyId().equals(companyPartyId)) {
                    out.add(payment);
                }
            }
            return List.copyOf(out);
        }

        /**
         * 经理真实提出的请求。
         *
         * @param requestId      请求标识
         * @param companyPartyId 公司
         * @param contractId     合同
         * @param trancheIndex   期次
         * @param kind           种类：ACCEPTANCE/INVOICE/PAYMENT/RETENTION/RECOVERY
         * @param atMillis       提出时点
         * @param personPartyId  发起经理
         * @param answeredByEventId 回答事件；未回答为空串
         * @param answeredAtMillis  回答时点；未回答为 0
         * @param amount         请求金额（RECOVERY 为追索金额；其余为 0），精确到分
         */
        public record Request(String requestId, String companyPartyId, String contractId,
                              int trancheIndex, String kind, long atMillis,
                              String personPartyId, String answeredByEventId,
                              long answeredAtMillis, BigDecimal amount) {

            /** 校验标识、种类、时点与金额。 */
            public Request {
                requireText(requestId, "客户请求");
                requireText(companyPartyId, "客户请求公司");
                requireText(contractId, "客户请求合同");
                requireText(kind, "客户请求种类");
                if (!REQUEST_KINDS.contains(kind)) {
                    throw new IllegalArgumentException("未知客户请求种类：" + kind);
                }
                if (trancheIndex < 0) {
                    throw new IllegalArgumentException("客户请求期次不能为负：" + requestId);
                }
                if (atMillis < 0 || answeredAtMillis < 0) {
                    throw new IllegalArgumentException("客户请求时点不能为负：" + requestId);
                }
                if (answeredAtMillis > 0 && (answeredByEventId == null
                        || answeredByEventId.isBlank())) {
                    throw new IllegalArgumentException("已回答请求必须有响应事件：" + requestId);
                }
                amount = amount == null ? CashLedger.money(BigDecimal.ZERO)
                        : CashLedger.money(amount);
                if (amount.signum() < 0) {
                    throw new IllegalArgumentException("客户请求金额不能为负：" + requestId);
                }
                answeredByEventId = answeredByEventId == null ? "" : answeredByEventId;
            }

            /** @return 是否已被回答（只有一次） */
            public boolean answered() {
                return !answeredByEventId.isEmpty();
            }

            /**
             * 生成回答后的请求。
             *
             * @param eventId 响应事件
             * @param at      响应时点
             * @return 已回答请求
             */
            public Request answer(String eventId, long at) {
                return new Request(requestId, companyPartyId, contractId, trancheIndex, kind,
                        atMillis, personPartyId, eventId, at, amount);
            }
        }

        /**
         * 外部客户的一次真实响应结果。
         *
         * @param eventId            冻结计划事件标识
         * @param companyPartyId     公司
         * @param contractId         合同
         * @param trancheIndex       期次
         * @param kind               事件种类
         * @param atMillis           响应时点
         * @param requestId          对应经理请求；客户自发事件为空串
         * @param outcome            结果：ACCEPTED/REJECTED/DELAYED/OVERDUE/PAID/PARTIAL/REJECTED_PAYMENT
         * @param amount             本次真实金额；未付款为 0
         * @param currentOutstanding 响应时点该期当期未清
         * @param retentionOutstanding 响应时点该期质保未清
         * @param reason             真实结果代码
         */
        public record Response(String eventId, String companyPartyId, String contractId,
                               int trancheIndex, String kind, long atMillis, String requestId,
                               String outcome, BigDecimal amount, BigDecimal currentOutstanding,
                               BigDecimal retentionOutstanding, String reason) {

            /** 校验标识与金额。 */
            public Response {
                requireText(eventId, "客户响应");
                requireText(companyPartyId, "客户响应公司");
                requireText(contractId, "客户响应合同");
                requireText(kind, "客户响应种类");
                requireText(outcome, "客户响应结果");
                requireText(reason, "客户响应原因");
                if (atMillis < 0 || trancheIndex < 0) {
                    throw new IllegalArgumentException("客户响应时点或期次非法：" + eventId);
                }
                amount = amount == null ? CashLedger.money(BigDecimal.ZERO)
                        : CashLedger.money(amount);
                currentOutstanding = currentOutstanding == null
                        ? CashLedger.money(BigDecimal.ZERO) : CashLedger.money(currentOutstanding);
                retentionOutstanding = retentionOutstanding == null
                        ? CashLedger.money(BigDecimal.ZERO)
                        : CashLedger.money(retentionOutstanding);
                if (amount.signum() < 0 || currentOutstanding.signum() < 0
                        || retentionOutstanding.signum() < 0) {
                    throw new IllegalArgumentException("客户响应金额不能为负：" + eventId);
                }
                requestId = requestId == null ? "" : requestId;
            }
        }

        /**
         * 一笔真实客户付款（预收/当期/质保/核销后回收分开记账）。
         *
         * @param eventId        触发付款的事件标识
         * @param poolAccountId  客户账户（唯一池）
         * @param period         期间序号
         * @param companyPartyId 公司
         * @param contractId     合同
         * @param trancheIndex   期次
         * @param kind           ADVANCE/CURRENT/RETENTION/RECOVERY
         * @param amount         真实金额
         * @param atMillis       付款时点
         */
        public record Payment(String eventId, String poolAccountId, long period,
                              String companyPartyId, String contractId, int trancheIndex,
                              String kind, BigDecimal amount, long atMillis) {

            /** 校验标识、期间与金额。 */
            public Payment {
                requireText(eventId, "客户付款事件");
                requireText(poolAccountId, "客户付款账户");
                requireText(companyPartyId, "客户付款公司");
                requireText(kind, "客户付款种类");
                if (period < 0 || atMillis < 0 || trancheIndex < 0) {
                    throw new IllegalArgumentException("客户付款期间或时点非法：" + eventId);
                }
                amount = CashLedger.money(amount);
                if (amount.signum() <= 0) {
                    throw new IllegalArgumentException("客户付款金额必须为正：" + eventId);
                }
                contractId = contractId == null ? "" : contractId;
            }
        }
    }

    /** 客户请求种类：经理只能提出这些请求，不能自造响应。RECOVERY = 核销后追索请求（不扣款）。 */
    public static final Set<String> REQUEST_KINDS = Set.of("ACCEPTANCE", "INVOICE", "PAYMENT",
            "RETENTION", "RECOVERY");

    /**
     * 冻结客户响应计划：只表达外部客户在合法阶段可发生的确定事件，不是运行时 policy 引擎。
     *
     * @param eventId       事件标识，公司内唯一
     * @param companyPartyId 公司组织
     * @param contractId    合同标识
     * @param trancheIndex  期次
     * @param kind          ADVANCE/ACCEPT/REJECT/DELAY/PAY/RETENTION_PAY/RECOVERY_PAY/OVERDUE
     * @param atMillis      事件到期模拟毫秒，必须为正
     * @param units         验收/拒验单位；付款为 0
     * @param amount        付款金额；验收/拒验为 0
     * @param payerAccountId 付款账户；非付款为空串
     * @param evidenceRef   来源证据引用
     * @param requestKind   必须绑定的经理请求种类；空串表示客户自发动作
     */
    public record CustomerEventPlan(String eventId, String companyPartyId, String contractId,
                                    int trancheIndex, String kind, long atMillis, long units,
                                    BigDecimal amount, String payerAccountId,
                                    String evidenceRef, String requestKind) {

        /** 旧十参构造：不绑定经理请求（客户自发动作，兼容既有冻结档）。 */
        public CustomerEventPlan(String eventId, String companyPartyId, String contractId,
                                 int trancheIndex, String kind, long atMillis, long units,
                                 BigDecimal amount, String payerAccountId, String evidenceRef) {
            this(eventId, companyPartyId, contractId, trancheIndex, kind, atMillis, units,
                    amount, payerAccountId, evidenceRef, "");
        }

        /** 冻结合法客户动作集合，并校验时间/金额。 */
        public CustomerEventPlan {
            requireText(eventId, "客户事件");
            requireText(companyPartyId, "客户事件公司");
            requireText(contractId, "客户事件合同");
            requireText(kind, "客户事件类型");
            requireText(evidenceRef, "客户事件证据");
            if (!Set.of("ADVANCE", "ACCEPT", "REJECT", "DELAY", "PAY", "RETENTION_PAY",
                    "RECOVERY_PAY", "OVERDUE").contains(kind)) {
                throw new IllegalArgumentException("未知客户事件类型：" + kind);
            }
            if (atMillis <= 0) {
                throw new IllegalArgumentException("客户事件时点必须为正：" + eventId);
            }
            if (trancheIndex < 0 || units < 0) {
                throw new IllegalArgumentException("客户事件期次或单位非法：" + eventId);
            }
            amount = amount == null ? CashLedger.money(BigDecimal.ZERO) : CashLedger.money(amount);
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("客户事件金额不能为负：" + eventId);
            }
            boolean payment = "PAY".equals(kind) || "ADVANCE".equals(kind)
                    || "RETENTION_PAY".equals(kind) || "RECOVERY_PAY".equals(kind);
            if (payment && (amount.signum() <= 0 || payerAccountId == null
                    || payerAccountId.isBlank())) {
                throw new IllegalArgumentException("付款事件必须有正金额与付款账户：" + eventId);
            }
            if (!payment && amount.signum() != 0) {
                throw new IllegalArgumentException("非付款客户事件金额必须为 0：" + eventId);
            }
            if ("ACCEPT".equals(kind) && units <= 0) {
                throw new IllegalArgumentException("验收事件必须有正单位：" + eventId);
            }
            if (payerAccountId == null) {
                payerAccountId = "";
            }
            requestKind = requestKind == null ? "" : requestKind;
            if (!requestKind.isEmpty() && !REQUEST_KINDS.contains(requestKind)) {
                throw new IllegalArgumentException("未知客户事件绑定请求种类：" + eventId
                        + " -> " + requestKind);
            }
        }
    }

    /**
     * 工程经营与董事会冻结配置。
     *
     * @param paramSource    参数来源说明，必须非空
     * @param companies      每公司一份模板；公司、owner、经营账户必须唯一，外部池可跨公司共享
     * @param customerEvents 按公司冻结的有限客户响应计划；旧档为空表
     */
    public record Config(String paramSource, List<CompanySpec> companies,
                         Map<String, List<CustomerEventPlan>> customerEvents) {

        /** 旧二参构造：没有冻结客户计划。 */
        public Config(String paramSource, List<CompanySpec> companies) {
            this(paramSource, companies, Map.of());
        }

        /** 校验公司/owner/经营账户唯一，并禁止任何公司经营账户充当外部池。 */
        public Config {
            requireText(paramSource, "参数来源");
            List<CompanySpec> list = new ArrayList<>(Objects.requireNonNull(companies, "工程公司"));
            if (list.isEmpty()) {
                throw new IllegalArgumentException("工程配置至少需要一个公司");
            }
            Set<String> companyIds = new LinkedHashSet<>();
            Set<String> owners = new LinkedHashSet<>();
            Set<String> companyAccounts = new LinkedHashSet<>();
            for (CompanySpec spec : list) {
                if (!companyIds.add(spec.companyPartyId())) {
                    throw new IllegalArgumentException("重复工程公司：" + spec.companyPartyId());
                }
                if (!owners.add(spec.companyOwnerId())) {
                    throw new IllegalArgumentException("同一 owner 不得绑定多个工程公司："
                            + spec.companyOwnerId());
                }
                if (!companyAccounts.add(spec.companyAccountId())) {
                    throw new IllegalArgumentException("工程经营账户重复或跨公司复用："
                            + spec.companyAccountId());
                }
            }
            for (CompanySpec spec : list) {
                for (String pool : spec.externalAccountIds()) {
                    if (companyAccounts.contains(pool)) {
                        throw new IllegalArgumentException(
                                "任何公司经营账户不能充当客户/供应商/作业聚合池：" + pool);
                    }
                }
            }
            // 唯一客户池：同一客户账户被多家公司声明时必须完全一致，否则拒开局，不取任意一份。
            Map<String, CustomerPool> pools = new TreeMap<>();
            for (CompanySpec spec : list) {
                CustomerPool declared = spec.customerPool();
                if (declared == null) {
                    continue;
                }
                CustomerPool existing = pools.putIfAbsent(declared.customerAccountId(), declared);
                if (existing != null && !existing.equals(declared)) {
                    throw new IllegalArgumentException(
                            "同一客户账户被不同公司声明了冲突的期间预算/周期：" + spec.companyPartyId()
                                    + " / " + declared + " vs " + existing);
                }
            }
            Map<String, List<CustomerEventPlan>> plans = new TreeMap<>();
            if (customerEvents != null) {
                for (Map.Entry<String, List<CustomerEventPlan>> entry : customerEvents.entrySet()) {
                    if (!companyIds.contains(entry.getKey())) {
                        throw new IllegalArgumentException("客户计划公司不在配置中：" + entry.getKey());
                    }
                    List<CustomerEventPlan> events = new ArrayList<>(
                            Objects.requireNonNull(entry.getValue(), "客户事件"));
                    Set<String> eventIds = new LinkedHashSet<>();
                    for (CustomerEventPlan event : events) {
                        if (!event.companyPartyId().equals(entry.getKey())) {
                            throw new IllegalArgumentException("客户事件公司不一致：" + event.eventId());
                        }
                        if (!eventIds.add(event.eventId())) {
                            throw new IllegalArgumentException("客户事件标识重复：" + event.eventId());
                        }
                    }
                    plans.put(entry.getKey(), List.copyOf(events));
                }
            }
            companies = List.copyOf(list);
            customerEvents = Collections.unmodifiableMap(plans);
        }

        /**
         * 按公司取冻结客户计划。
         *
         * @param companyPartyId 公司组织
         * @return 事件列表；无计划返回空表
         */
        public List<CustomerEventPlan> customerEventsOf(String companyPartyId) {
            return customerEvents.getOrDefault(companyPartyId, List.of());
        }

        /**
         * 按客户账户取唯一期间预算声明。
         *
         * @param customerAccountId 客户聚合账户
         * @return 冻结声明；未声明返回 null
         */
        public CustomerPool poolOf(String customerAccountId) {
            CustomerPool found = null;
            for (CompanySpec spec : companies) {
                CustomerPool declared = spec.customerPool();
                if (declared != null && declared.customerAccountId().equals(customerAccountId)) {
                    found = declared;
                }
            }
            return found;
        }

        /**
         * 按公司 partyId 取模板。
         *
         * @param companyPartyId 公司组织标识
         * @return 模板；不存在返回 null
         */
        public CompanySpec specOf(String companyPartyId) {
            for (CompanySpec spec : companies) {
                if (spec.companyPartyId().equals(companyPartyId)) {
                    return spec;
                }
            }
            return null;
        }
    }

    /**
     * 冻结合同计划：只保存纯核条款的可比较值对象。
     *
     * <p>{@link EngineeringModel.Terms} 持有 {@code long[]}，记录相等性是数组引用；直接把它放进配置会让
     * 同一配置在序列化往返后不相等。这里用 {@code List<Long>} 保存阶段单位，保证值相等，并在
     * {@link #toSpec()} 时才构造纯核输入。</p>
     *
     * @param contractId        合同标识，同一公司内唯一
     * @param unitPrice         合同单价，精确到分且为正
     * @param unitCost          单位履约成本，最多 4 位且非负
     * @param advanceBp         预收比例（万分比）
     * @param retentionBp       质保比例（万分比）
     * @param netDays           应收到期天数
     * @param retentionNetDays  质保到期天数
     * @param trancheUnits      阶段单位表，至少一项且均为正
     */
    public record ContractPlan(String contractId, BigDecimal unitPrice, BigDecimal unitCost,
                               int advanceBp, int retentionBp, int netDays,
                               int retentionNetDays, List<Long> trancheUnits) {

        /** 校验并以纯核同一口径验证条款。 */
        public ContractPlan {
            requireText(contractId, "合同标识");
            List<Long> units = List.copyOf(Objects.requireNonNull(trancheUnits, "阶段单位表"));
            long[] raw = new long[units.size()];
            for (int i = 0; i < units.size(); i++) {
                Long value = units.get(i);
                if (value == null || value <= 0) {
                    throw new IllegalArgumentException("阶段单位必须为正：" + value);
                }
                raw[i] = value;
            }
            // 复用纯核校验；compact 构造器内不能读访问器，直接用参数构造
            new EngineeringModel.ContractSpec(contractId, unitPrice, unitCost,
                    new EngineeringModel.Terms(advanceBp, retentionBp, netDays,
                            retentionNetDays, EngineeringModel.RetentionMode.TIME_ONLY, raw));
            trancheUnits = units;
        }

        /** @return 后继签约使用的纯核输入 */
        public EngineeringModel.ContractSpec toSpec() {
            long[] raw = new long[trancheUnits.size()];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = trancheUnits.get(i);
            }
            return new EngineeringModel.ContractSpec(contractId, unitPrice, unitCost,
                    new EngineeringModel.Terms(advanceBp, retentionBp, netDays,
                            retentionNetDays, EngineeringModel.RetentionMode.TIME_ONLY, raw));
        }
    }

    /**
     * 单公司工程模板：账户绑定、经营参数、董事会章程与冻结合同。
     *
     * <p>合同只是冻结条款（开局不代签、不动现金），至少一张；同一公司内合同标识不得重复。
     * 合同所属公司由本模板唯一确定，不接受调用方临时指定。</p>
     *
     * @param companyPartyId    公司组织 partyId（董事会键）
     * @param companyOwnerId    公司财产 ownerId（经营账户属主）
     * @param companyAccountId  公司唯一经营现金账户
     * @param customerAccountId 客户聚合账户
     * @param supplierAccountId 供应商聚合账户
     * @param opsAccountId      作业方账户
     * @param unitMillis        每标准工日的模拟毫秒
     * @param laborBp           现场付现比例（0..10000）
     * @param charter           董事会章程，公司必须等于 {@code companyPartyId}
     * @param contracts         冻结合同计划，至少一张
     * @param customerPool      客户期间预算声明；null 表示本档不限制客户期间预算
     */
    public record CompanySpec(String companyPartyId, String companyOwnerId,
                              String companyAccountId, String customerAccountId,
                              String supplierAccountId, String opsAccountId, long unitMillis,
                              int laborBp, BoardModel.Charter charter,
                              List<ContractPlan> contracts, CustomerPool customerPool) {

        /** 旧十参构造：不声明客户期间预算。 */
        public CompanySpec(String companyPartyId, String companyOwnerId,
                           String companyAccountId, String customerAccountId,
                           String supplierAccountId, String opsAccountId, long unitMillis,
                           int laborBp, BoardModel.Charter charter,
                           List<ContractPlan> contracts) {
            this(companyPartyId, companyOwnerId, companyAccountId, customerAccountId,
                    supplierAccountId, opsAccountId, unitMillis, laborBp, charter, contracts,
                    null);
        }

        /** 校验标识、章程绑定与合同唯一性。 */
        public CompanySpec {
            requireText(companyPartyId, "工程公司");
            requireText(companyOwnerId, "工程公司 owner");
            if (charter == null) {
                throw new IllegalArgumentException("工程公司章程不能为空：" + companyPartyId);
            }
            if (!companyPartyId.equals(charter.companyPartyId())) {
                throw new IllegalArgumentException("工程公司与其章程公司不一致："
                        + companyPartyId + " / " + charter.companyPartyId());
            }
            List<ContractPlan> list = new ArrayList<>(
                    Objects.requireNonNull(contracts, "工程合同"));
            if (list.isEmpty()) {
                throw new IllegalArgumentException("工程公司至少需要一张冻结合同："
                        + companyPartyId);
            }
            Set<String> ids = new LinkedHashSet<>();
            for (ContractPlan contract : list) {
                if (!ids.add(contract.contractId())) {
                    throw new IllegalArgumentException("同一工程公司合同标识重复："
                            + contract.contractId());
                }
            }
            if (customerPool != null
                    && !customerPool.customerAccountId().equals(customerAccountId)) {
                throw new IllegalArgumentException("客户期间预算必须绑定本公司客户账户："
                        + companyPartyId + " 声明 " + customerPool.customerAccountId()
                        + "，客户账户 " + customerAccountId);
            }
            // 复用冻结参数校验：账户互异、工日与人工比例合法
            // 注意：compact 构造器内字段尚未赋值，必须直接用参数构造，不能调用访问器
            new EngineeringModel.Config(companyOwnerId, companyAccountId, customerAccountId,
                    supplierAccountId, opsAccountId, unitMillis, laborBp,
                    "schema18:" + companyPartyId);
            contracts = List.copyOf(list);
        }

        /** @return 由本模板派生的纯核经营配置 */
        public EngineeringModel.Config engineeringConfig() {
            return new EngineeringModel.Config(companyOwnerId, companyAccountId,
                    customerAccountId, supplierAccountId, opsAccountId, unitMillis, laborBp,
                    "schema18:" + companyPartyId);
        }

        /** @return 本公司的四个账户标识 */
        public List<String> accountIds() {
            return List.of(companyAccountId, customerAccountId, supplierAccountId, opsAccountId);
        }

        /** @return 外部客户/供应商/作业聚合账户（可与其它公司共享同一权威账户） */
        public List<String> externalAccountIds() {
            return List.of(customerAccountId, supplierAccountId, opsAccountId);
        }
    }

    /**
     * 开局：逐公司校验账户与登记，建立经营状态与董事会；冻结合同只进入配置，不代签、不动现金。
     *
     * <p>全部校验在创建日志之前完成，失败不会留下半成品档案；这里不移动任何现金。</p>
     *
     * @param config   冻结配置
     * @param accounts 唯一世界账户表
     * @param registry 开局登记（校验 owner 与章程人物）
     * @return 开局工程子集
     * @throws IllegalArgumentException 配置、账户、owner、章程或金额非法
     */
    public static EngineeringRun open(Config config, Map<String, CashLedger.CashAccount> accounts,
                                      OpeningRegistry registry) {
        Objects.requireNonNull(config, "工程配置");
        Objects.requireNonNull(accounts, "账户表");
        Objects.requireNonNull(registry, "开局登记");
        Map<String, EngineeringModel.State> states = new TreeMap<>();
        Map<String, BoardRun> boards = new TreeMap<>();
        for (CompanySpec spec : config.companies()) {
            OpeningRegistry.PropertyOwner owner = registry.owner(spec.companyOwnerId());
            if (owner == null || owner.kind() != OpeningRegistry.OwnerKind.ORGANIZATION) {
                throw new IllegalArgumentException("工程公司 owner 未登记为 ORGANIZATION："
                        + spec.companyOwnerId());
            }
            if (!spec.companyPartyId().equals(owner.partyId())) {
                throw new IllegalArgumentException("工程公司 owner 与登记组织不一致："
                        + spec.companyOwnerId() + " / " + owner.partyId());
            }
            for (String account : spec.accountIds()) {
                if (!accounts.containsKey(account)) {
                    throw new IllegalArgumentException("工程账户未开局：" + account);
                }
            }
            CashLedger.CashAccount company = accounts.get(spec.companyAccountId());
            if (!spec.companyOwnerId().equals(company.ownerId())) {
                throw new IllegalArgumentException("工程经营账户不属于公司 owner："
                        + spec.companyAccountId());
            }
            if (company.cash().signum() < 0) {
                throw new IllegalArgumentException("工程公司初始金额不能为负："
                        + spec.companyAccountId() + " = " + company.cash());
            }
            // F5：客户付款账户必须就是本公司冻结客户账户，不能声明一个池却扣另一个
            for (CustomerEventPlan plan : config.customerEventsOf(spec.companyPartyId())) {
                if (!spec.companyPartyId().equals(plan.companyPartyId())) {
                    throw new IllegalArgumentException("客户事件公司不属于该配置："
                            + plan.eventId() + " -> " + plan.companyPartyId());
                }
                if ("PAY".equals(plan.kind())) {
                    if (!spec.customerAccountId().equals(plan.payerAccountId())) {
                        throw new IllegalArgumentException(
                                "付款事件的付款账户必须就是本公司客户账户：" + plan.eventId()
                                        + " 声明 " + plan.payerAccountId() + "，公司客户 "
                                        + spec.customerAccountId());
                    }
                    if (!accounts.containsKey(plan.payerAccountId())) {
                        throw new IllegalArgumentException("付款账户未开局："
                                + plan.payerAccountId());
                    }
                }
            }
            EngineeringModel.State state = EngineeringModel.open(spec.engineeringConfig(),
                    accounts);
            states.put(spec.companyPartyId(), state);
            boards.put(spec.companyPartyId(),
                    new BoardRun(BoardModel.open(registry, spec.charter()), Map.of(), Map.of()));
        }
        return new EngineeringRun(config, states, boards, Map.of());
    }

    /**
     * 按公司取经营状态。
     *
     * @param companyPartyId 公司组织标识
     * @return 状态；不存在返回 null
     */
    public EngineeringModel.State stateOf(String companyPartyId) {
        return companies.get(companyPartyId);
    }

    /**
     * 按公司取董事会。
     *
     * @param companyPartyId 公司组织标识
     * @return 董事会；不存在返回 null
     */
    public BoardRun boardOf(String companyPartyId) {
        return boards.get(companyPartyId);
    }

    /** @return 全部工程公司键（不可变） */
    public Set<String> companyIds() {
        return companies.keySet();
    }

    /**
     * 替换单公司经营状态，保持其它公司、章程与配置不变。
     *
     * @param companyPartyId 公司组织标识
     * @param state          候选状态
     * @return 替换后的新子集
     * @throws IllegalArgumentException 公司不在冻结配置中
     */
    public EngineeringRun withCompanyState(String companyPartyId, EngineeringModel.State state) {
        if (!companies.containsKey(companyPartyId)) {
            throw new IllegalArgumentException("未知工程公司：" + companyPartyId);
        }
        Map<String, EngineeringModel.State> next = new LinkedHashMap<>(companies);
        next.put(companyPartyId, Objects.requireNonNull(state, "经营状态"));
        return new EngineeringRun(config, next, boards, reports, customer, waivers, periodic);
    }

    /**
     * 替换单公司董事会，保持其它公司、经营与配置不变。
     *
     * @param companyPartyId 公司组织标识
     * @param board          候选董事会
     * @return 替换后的新子集
     * @throws IllegalArgumentException 公司不在冻结配置中
     */
    public EngineeringRun withBoard(String companyPartyId, BoardRun board) {
        if (!boards.containsKey(companyPartyId)) {
            throw new IllegalArgumentException("未知工程公司：" + companyPartyId);
        }
        Map<String, BoardRun> next = new LinkedHashMap<>(boards);
        next.put(companyPartyId, Objects.requireNonNull(board, "工程董事会"));
        return new EngineeringRun(config, companies, next, reports, customer, waivers, periodic);
    }

    /**
     * 替换客户交互审计，保持其它子集不变。
     *
     * @param next 新客户审计
     * @return 替换后的新子集
     */
    public EngineeringRun withCustomer(Customer next) {
        return new EngineeringRun(config, companies, boards, reports,
                Objects.requireNonNull(next, "客户审计"), waivers, periodic);
    }

    /**
     * 同时替换单公司董事会与全部豁免决议（实施豁免决议时必须原子地一起换）。
     *
     * @param companyPartyId 公司组织标识
     * @param board          候选董事会
     * @param next           候选豁免决议（全部公司）
     * @return 替换后的新子集
     */
    public EngineeringRun withBoardAndWaivers(String companyPartyId, BoardRun board,
            List<BoardModel.DebtWaiver> next) {
        if (!boards.containsKey(companyPartyId)) {
            throw new IllegalArgumentException("未知工程公司：" + companyPartyId);
        }
        Map<String, BoardRun> boardCopy = new LinkedHashMap<>(boards);
        boardCopy.put(companyPartyId, Objects.requireNonNull(board, "工程董事会"));
        return new EngineeringRun(config, companies, boardCopy, reports, customer,
                next == null ? List.of() : next, periodic);
    }

    /**
     * 替换豁免决议列表，保持其它子集不变。
     *
     * @param next 新豁免决议
     * @return 替换后的新子集
     */
    public EngineeringRun withWaivers(List<BoardModel.DebtWaiver> next) {
        return new EngineeringRun(config, companies, boards, reports, customer,
                next == null ? List.of() : next, periodic);
    }

    /**
     * 某公司的豁免决议。
     *
     * @param companyPartyId 公司组织
     * @return 该公司的豁免决议（按标识排序）
     */
    public List<BoardModel.DebtWaiver> waiversOf(String companyPartyId) {
        List<BoardModel.DebtWaiver> out = new ArrayList<>();
        for (BoardModel.DebtWaiver waiver : waivers) {
            if (waiver.request().companyPartyId().equals(companyPartyId)) {
                out.add(waiver);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 追加一条工程审计回报，同一任务不得改写。
     *
     * @param entry 新回报
     * @return 追加后的新子集
     */
    public EngineeringRun withReport(Report entry) {
        Objects.requireNonNull(entry, "工程回报");
        Report existing = reports.get(entry.taskId());
        if (existing != null) {
            if (!existing.equals(entry)) {
                throw new IllegalArgumentException("同一工程任务只能有一条回报："
                        + entry.taskId());
            }
            return this;
        }
        Map<String, Report> next = new TreeMap<>(reports);
        next.put(entry.taskId(), entry);
        return new EngineeringRun(config, companies, boards, next, customer, waivers, periodic);
    }

    /**
     * 逐字段校验候选状态仍绑定冻结配置与同一账户表。
     *
     * @param accounts 唯一世界账户表
     * @throws IllegalArgumentException 配置、现金或合同绑定被改写
     */
    public void validateAgainst(Map<String, CashLedger.CashAccount> accounts) {
        Objects.requireNonNull(accounts, "账户表");
        Set<String> configured = new LinkedHashSet<>();
        for (CompanySpec spec : config.companies()) {
            configured.add(spec.companyPartyId());
        }
        if (!configured.equals(companyIds())) {
            throw new IllegalArgumentException("工程公司键域与冻结配置不一致");
        }
        for (CompanySpec spec : config.companies()) {
            EngineeringModel.State state = companies.get(spec.companyPartyId());
            if (state == null) {
                throw new IllegalArgumentException("缺少工程经营状态：" + spec.companyPartyId());
            }
            if (!spec.engineeringConfig().equals(state.config())) {
                throw new IllegalArgumentException("工程经营配置被改写："
                        + spec.companyPartyId());
            }
            Set<String> configuredContracts = new LinkedHashSet<>();
            for (ContractPlan contract : spec.contracts()) {
                configuredContracts.add(contract.contractId());
            }
            for (String contractId : state.contracts().keySet()) {
                if (!configuredContracts.contains(contractId)) {
                    throw new IllegalArgumentException("工程合同不在冻结配置中："
                            + spec.companyPartyId() + " / " + contractId);
                }
            }
            CashLedger.CashAccount company = accounts.get(spec.companyAccountId());
            if (company == null || !spec.companyOwnerId().equals(company.ownerId())) {
                throw new IllegalArgumentException("工程经营账户权属被改写："
                        + spec.companyAccountId());
            }
            BoardRun board = boards.get(spec.companyPartyId());
            if (board == null || !spec.charter().equals(board.board().charter())) {
                throw new IllegalArgumentException("工程董事会章程被改写："
                        + spec.companyPartyId());
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
