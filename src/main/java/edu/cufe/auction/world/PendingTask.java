package edu.cufe.auction.world;

/**
 * 可序列化的待办任务：世界里“已经排定、尚未执行”的最小单位。
 *
 * <p>与 M1.1 的内存 {@link Runnable} 不同，本类型只承载数据：到期时间、固定阶段、
 * 动作种类与动作参数。执行器由运行层按 {@link #kind()} 解析，因此进程重启后可以直接
 * 从日志重建待办，不需要重新构造闭包，也不会重跑已确认的动作。</p>
 *
 * <p>当前支持八类交易任务：现金转账、回报投递、三类证券清算夹具动作（预留卖出、撤销预留、
 * 同批清算成交），以及三类世界订单动作（下单、撤单、收盘）；再加十类制造经营任务：六个外部
 * 管理夹具动作（采购、投产、签约、交付、回款、付费用账单）、三个人工触发的自动子任务
 * （到货、完工、自动回款）与一个系统强制关期任务；最后是治理任务：五类外部管理夹具动作
 * （召集、材料、预算提案、投票、实施）与三个系统自动任务（开会、关会、期间评分）。
 * 业务动作都落在 COMPLETION 阶段，转账回报投递落在 DELIVERY 阶段，强制关期与自动开会落在
 * RULES 阶段。未登记的 kind 在构造期即被拒绝。</p>
 *
 * @param taskId        运行内唯一任务标识，不复用
 * @param dueTimeMillis 到期的模拟毫秒，必须为正
 * @param phase         到期时执行的固定阶段
 * @param kind          任务种类，见常量
 * @param payload       任务参数，规范化 JSON 文本
 */
public record PendingTask(String taskId, long dueTimeMillis, WorldScheduler.Phase phase,
                          String kind, String payload) {

    /** 现金转账任务：在 COMPLETION 阶段结算资金。 */
    public static final String CASH_TRANSFER_V1 = "CASH_TRANSFER_V1";

    /** 转账回报投递任务：在 DELIVERY 阶段把回执交给收件人。 */
    public static final String TRANSFER_NOTICE_V1 = "TRANSFER_NOTICE_V1";

    /** 预留卖出：在 COMPLETION 阶段按最新持仓冻结可卖批次。 */
    public static final String SELL_RESERVE_V1 = "SELL_RESERVE_V1";

    /** 撤销预留：在 COMPLETION 阶段只释放未成交的冻结。 */
    public static final String SELL_CANCEL_V1 = "SELL_CANCEL_V1";

    /** 同批清算成交：受信的系统夹具，在 COMPLETION 阶段一次提交现金、股份与预留。 */
    public static final String FILL_SETTLE_V1 = "FILL_SETTLE_V1";

    /** 世界下单：到期按最新簿与资格受理，形成订单/成交/现金/批次/预留。 */
    public static final String EXCHANGE_ORDER_V1 = "EXCHANGE_ORDER_V1";

    /** 世界撤单：到期撤销活动订单的剩余量并释放预留。 */
    public static final String EXCHANGE_CANCEL_V1 = "EXCHANGE_CANCEL_V1";

    /** 世界收盘：受信系统动作，撤销全簿并释放冻结。 */
    public static final String EXCHANGE_CLOSE_V1 = "EXCHANGE_CLOSE_V1";

    /** 外部管理夹具：到期形成采购承诺并按固定耗时自动排到货。 */
    public static final String MANUFACTURING_PURCHASE_ORDER_V1 = "MANUFACTURING_PURCHASE_ORDER_V1";

    /** 自动子任务：采购到货，同批付款入库或记 {@code FAILED_NO_CASH}。 */
    public static final String MANUFACTURING_GOODS_RECEIPT_V1 = "MANUFACTURING_GOODS_RECEIPT_V1";

    /** 外部管理夹具：到期耗料/付人工/形成在制，并自动排完工。 */
    public static final String MANUFACTURING_PRODUCTION_V1 = "MANUFACTURING_PRODUCTION_V1";

    /** 自动子任务：生产完工，在制成本全额转入产成品。 */
    public static final String MANUFACTURING_BATCH_COMPLETION_V1 =
            "MANUFACTURING_BATCH_COMPLETION_V1";

    /** 外部管理夹具：到期按预算/需求/同期间条件签约，不提前确认收入。 */
    public static final String MANUFACTURING_SALES_CONTRACT_V1 = "MANUFACTURING_SALES_CONTRACT_V1";

    /** 外部管理夹具：到期扣产成品并确认收入/成本/应收，并自动排 15 日收款。 */
    public static final String MANUFACTURING_DELIVERY_V1 = "MANUFACTURING_DELIVERY_V1";

    /** 外部管理夹具：逾期后可明确重试的应收回款。 */
    public static final String MANUFACTURING_COLLECT_RECEIVABLE_V1 =
            "MANUFACTURING_COLLECT_RECEIVABLE_V1";

    /** 自动子任务：应收到期自动尝试回款；客户可用现金不足只标逾期。 */
    public static final String MANUFACTURING_AUTO_COLLECTION_V1 =
            "MANUFACTURING_AUTO_COLLECTION_V1";

    /** 外部管理夹具：只付该期已确认未付的费用账单。 */
    public static final String MANUFACTURING_PAY_EXPENSE_BILL_V1 =
            "MANUFACTURING_PAY_EXPENSE_BILL_V1";

    /** 系统强制关期：期末确认费用/部分付款/应付/折旧，并自动排下一期间。 */
    public static final String MANUFACTURING_PERIOD_CLOSE_V1 = "MANUFACTURING_PERIOD_CLOSE_V1";

    /** 外部治理夹具：召集一次预算会议；只接受 BUDGET，复盘会议由系统关期产生。 */
    public static final String GOVERNANCE_MEETING_V1 = "GOVERNANCE_MEETING_V1";

    /** 外部治理夹具：向已召集会议提交材料。 */
    public static final String GOVERNANCE_MATERIAL_V1 = "GOVERNANCE_MATERIAL_V1";

    /** 外部治理夹具：提出预算请求行（绝对新上限）。 */
    public static final String GOVERNANCE_BUDGET_PROPOSAL_V1 = "GOVERNANCE_BUDGET_PROPOSAL_V1";

    /** 外部治理夹具：对应席位投票；反对/弃权/回避都保留。 */
    public static final String GOVERNANCE_VOTE_V1 = "GOVERNANCE_VOTE_V1";

    /** 外部治理夹具：实施已通过的预算决议；通过不等于自动生效。 */
    public static final String GOVERNANCE_IMPLEMENT_V1 = "GOVERNANCE_IMPLEMENT_V1";

    /** 自动子任务：开会冻结材料，并在预算请求存在时进入表决。 */
    public static final String BOARD_OPEN_V1 = "BOARD_OPEN_V1";

    /** 自动子任务：到期关会，据真实选票形成终态。 */
    public static final String BOARD_CLOSE_V1 = "BOARD_CLOSE_V1";

    /** 自动子任务：对期间证据评分（客观考核，不是多数表决）。 */
    public static final String BOARD_REVIEW_V1 = "BOARD_REVIEW_V1";
    /**
     * schema18b：正工时人物动作的真实 BEGIN 屏障（到期=真实开始，携带原动作 payload）。
     *
     * <p>接单/排程只落意图与屏障；屏障到期才复核权限/岗位/对象/焦点并排真正的
     * {@link #ENGINEERING_BOARD_V1}/{@link #BOARD_ATTEND_V1}/{@link #ENGINEERING_ACTION_V1} 完成帧。
     * 屏障被判业务拒绝时不留完成帧，也不占焦点。</p>
     */
    public static final String ENGINEERING_BEGIN_V1 = "ENGINEERING_BEGIN_V1";
    /** schema18b：按公司董事动作（含自动 OPEN/CLOSE 日程）。 */
    public static final String ENGINEERING_BOARD_V1 = "ENGINEERING_BOARD_V1";
    /** schema18b：董事本人异步参与登记（正耗时）。 */
    public static final String BOARD_ATTEND_V1 = "BOARD_ATTEND_V1";
    /** schema18c：周期期末采样（真实的期间汇总、快照与会议日程）。 */
    public static final String PERIOD_CAPTURE_V1 = "PERIOD_CAPTURE_V1";
    /** schema18c：周期评分窗口关闭，逐席聚合已合法提交的评分。 */
    public static final String PERIOD_CLOSE_V1 = "PERIOD_CLOSE_V1";
    /** schema18c：披露截止，未发布才记 MISSED，与评分缺失解耦。 */
    public static final String PERIOD_DISCLOSURE_DEADLINE_V1 = "PERIOD_DISCLOSURE_DEADLINE_V1";
    /** schema18c：人物周期动作（准备/评分/批准/授权/发布）的完成帧。 */
    public static final String ENGINEERING_PERIODIC_V1 = "ENGINEERING_PERIODIC_V1";

    /** schema18b：公司经理经营动作（签约/开工/开票/终止/付款等）。 */
    public static final String ENGINEERING_ACTION_V1 = "ENGINEERING_ACTION_V1";

    /**
     * 组合档 v2：显式资金经营动作（预收/投入/回款/质保/追索）。
     *
     * <p>与 {@link #ENGINEERING_ACTION_V1} 并列的第二种类，**不改动**旧 payload 形状：
     * 只有这个 kind 才携带显式资金桶与政策标签，旧档不会出现它，也不需要迁移。</p>
     */
    public static final String ENGINEERING_FUND_V1 = "ENGINEERING_FUND_V1";
    /** schema18b：冻结客户响应事件（验收/拒验/延迟/付款/逾期）。 */
    public static final String CUSTOMER_EVENT_V1 = "CUSTOMER_EVENT_V1";

    /** 受信管理夹具：到期建立一条任职（RULES）。 */
    public static final String ROLE_APPOINT_V1 = "ROLE_APPOINT_V1";

    /** 受信管理夹具：到期给已有任职写入一次撤销时刻（RULES）。 */
    public static final String ROLE_REVOKE_V1 = "ROLE_REVOKE_V1";

    /** 受信场景夹具：到期首次发表一篇资料，实际发布时间等于任务到期（COMPLETION）。 */
    public static final String INFORMATION_PUBLISH_V1 = "INFORMATION_PUBLISH_V1";

    /** 受信场景夹具：到期给已有资料追加一版，沿用原发布者与受众（COMPLETION）。 */
    public static final String INFORMATION_PUBLISH_VERSION_V1 = "INFORMATION_PUBLISH_VERSION_V1";

    /** 受信管理夹具：到期开始阅读固定版本，并同批排唯一自动完成待办（COMPLETION）。 */
    public static final String INFORMATION_START_READ_V1 = "INFORMATION_START_READ_V1";

    /** 受信管理夹具：到期取消本单阅读，只移除对应自动完成待办（COMPLETION）。 */
    public static final String INFORMATION_CANCEL_READ_V1 = "INFORMATION_CANCEL_READ_V1";

    /** 自动子任务：到期复核阅读资格并终结（完成或明确拒绝）（COMPLETION）。 */
    public static final String INFORMATION_COMPLETE_READ_V1 = "INFORMATION_COMPLETE_READ_V1";

    /** 人物会话：开始阅读一个明确版本，并核对本次观察任职（COMPLETION）。 */
    public static final String ROLE_TRADING_READ_V1 = "ROLE_TRADING_READ_V1";

    /** 人物会话：取消本人正在读的一单（COMPLETION）。 */
    public static final String ROLE_TRADING_UNREAD_V1 = "ROLE_TRADING_UNREAD_V1";

    /** 人物会话：研究员提交组织研究报告（COMPLETION）。 */
    public static final String ROLE_TRADING_RESEARCH_V1 = "ROLE_TRADING_RESEARCH_V1";

    /** 人物会话：经理建立或修改绝对目标（COMPLETION）。 */
    public static final String ROLE_TRADING_TARGET_V1 = "ROLE_TRADING_TARGET_V1";

    /** 人物会话：风控审核（COMPLETION）。 */
    public static final String ROLE_TRADING_REVIEW_V1 = "ROLE_TRADING_REVIEW_V1";

    /** 人物会话：经理确认获批版本（COMPLETION）。 */
    public static final String ROLE_TRADING_CONFIRM_V1 = "ROLE_TRADING_CONFIRM_V1";

    /** 人物会话：风控暂停（COMPLETION）。 */
    public static final String ROLE_TRADING_HALT_V1 = "ROLE_TRADING_HALT_V1";

    /** 人物会话：经理取消目标（COMPLETION）。 */
    public static final String ROLE_TRADING_CANCEL_V1 = "ROLE_TRADING_CANCEL_V1";

    /** 人物会话：执行员子单（admit + 唯一撮合 + 链接，同批）（COMPLETION）。 */
    public static final String ROLE_TRADING_CHILD_V1 = "ROLE_TRADING_CHILD_V1";

    /** 人物会话：执行员撤销本人子单（COMPLETION）。 */
    public static final String ROLE_TRADING_CHILD_CANCEL_V1 = "ROLE_TRADING_CHILD_CANCEL_V1";

    /** 系统任务：撤不相容旧单并在撤净后重建基点（RULES）。 */
    public static final String ROLE_TRADING_SWEEP_V1 = "ROLE_TRADING_SWEEP_V1";

    /** 系统任务：目标/审核到期（RULES）。 */
    public static final String ROLE_TRADING_EXPIRE_V1 = "ROLE_TRADING_EXPIRE_V1";

    /** 系统任务：任职结束或撤销，撤该任职发出的活动子单（RULES）。 */
    public static final String ROLE_TRADING_APPOINTMENT_END_V1 = "ROLE_TRADING_APPOINTMENT_END_V1";

    /** 系统任务：风险暂停到期，回到待审核（RULES）。 */
    public static final String ROLE_TRADING_HALT_RELEASE_V1 = "ROLE_TRADING_HALT_RELEASE_V1";

    /** 分红：召集分红授权会议（COMPLETION）。 */
    public static final String DIVIDEND_MEETING_V1 = "DIVIDEND_MEETING_V1";

    /** 分红：受理董事会分红提案并在同批形成 D0 PROPOSED（COMPLETION）。 */
    public static final String GOVERNANCE_DIVIDEND_PROPOSAL_V1 = "GOVERNANCE_DIVIDEND_PROPOSAL_V1";

    /** 分红：实施已通过的董事会决议、授权 D0 并排后续公告/登记/除息/付款（COMPLETION）。 */
    public static final String GOVERNANCE_DIVIDEND_IMPLEMENT_V1 = "GOVERNANCE_DIVIDEND_IMPLEMENT_V1";

    /** 分红：登记截止按真实剩余库存固定受益人（RULES）。 */
    public static final String DIVIDEND_REGISTER_V1 = "DIVIDEND_REGISTER_V1";

    /** 分红：除息截止确认 D0 claims 并派生参考价（RULES）。 */
    public static final String DIVIDEND_BOOK_EX_V1 = "DIVIDEND_BOOK_EX_V1";

    /** 分红：付款截止整批足额真实出款（COMPLETION）。 */
    public static final String DIVIDEND_SETTLE_V1 = "DIVIDEND_SETTLE_V1";

    /** 分红：持有人减值（COMPLETION）。 */
    public static final String DIVIDEND_IMPAIR_V1 = "DIVIDEND_IMPAIR_V1";

    /** 信念：形成/重申主观判断（COMPLETION）。 */
    public static final String ROLE_TRADING_BELIEF_V1 = "ROLE_TRADING_BELIEF_V1";

    /** 信念：发布公开结构化来源预测（COMPLETION）。 */
    public static final String ROLE_TRADING_FORECAST_V1 = "ROLE_TRADING_FORECAST_V1";

    /** 信念：主席请求公司在 asOf 捕获订单事实（COMPLETION 请求）。 */
    public static final String ROLE_TRADING_COMPANY_REPORT_V1 = "ROLE_TRADING_COMPANY_REPORT_V1";

    /** 信念：在 asOf 的 OBSERVATION 阶段捕获合同数量（晚于同刻全部 COMPLETION）。 */
    public static final String ROLE_TRADING_COMPANY_FACT_CAPTURE_V1 =
            "ROLE_TRADING_COMPANY_FACT_CAPTURE_V1";

    /** 信念：下一毫秒发布已捕获事实（DELIVERY）。 */
    public static final String INFORMATION_BELIEF_PUBLISH_FACT_V1 =
            "INFORMATION_BELIEF_PUBLISH_FACT_V1";

    /** 媒体：提出选题（COMPLETION，schema13）。 */
    public static final String MEDIA_STORY_PROPOSE_V1 = "MEDIA_STORY_PROPOSE_V1";

    /** 媒体：提交组织草稿（COMPLETION，schema13）。 */
    public static final String MEDIA_STORY_SUBMIT_V1 = "MEDIA_STORY_SUBMIT_V1";

    /** 媒体：编辑审阅（COMPLETION，schema13）。 */
    public static final String MEDIA_STORY_REVIEW_V1 = "MEDIA_STORY_REVIEW_V1";

    /** 媒体：发布已批准版本（COMPLETION，schema13）。 */
    public static final String MEDIA_STORY_PUBLISH_V1 = "MEDIA_STORY_PUBLISH_V1";

    /** 媒体：开启更正轮（COMPLETION，schema13）。 */
    public static final String MEDIA_STORY_CORRECTION_V1 = "MEDIA_STORY_CORRECTION_V1";

    /** 媒体：取消本人未完成的媒体工作（COMPLETION，schema13）。 */
    public static final String MEDIA_WORK_CANCEL_V1 = "MEDIA_WORK_CANCEL_V1";

    /** 证据：分享一份本人已读并记得的来源材料（COMPLETION，schema16）。 */
    public static final String MEDIA_EVIDENCE_SHARE_V1 = "MEDIA_EVIDENCE_SHARE_V1";

    /** 社交：写完私人消息，进入排队（COMPLETION，schema17）。 */
    public static final String SOCIAL_FINISH_V1 = "SOCIAL_FINISH_V1";
    /** 社交：barrier 到达后才真正开始写信（COMPLETION，schema17）。 */
    public static final String SOCIAL_BEGIN_V1 = "SOCIAL_BEGIN_V1";
    /** 社交：计划投递一封已写完的消息（COMPLETION，schema17）。 */
    public static final String SOCIAL_DELIVER_V1 = "SOCIAL_DELIVER_V1";
    /** 社交：到期设置本人对目标的屏蔽偏好（COMPLETION，schema17）。 */
    public static final String SOCIAL_BLOCK_V1 = "SOCIAL_BLOCK_V1";
    /** 社交：到期解除本人对目标的屏蔽（COMPLETION，schema17）。 */
    public static final String SOCIAL_UNBLOCK_V1 = "SOCIAL_UNBLOCK_V1";

    /** 群/平台：barrier 到达后才真正开始动作（COMPLETION，schema20）。 */
    public static final String GROUP_BEGIN_V1 = "GROUP_BEGIN_V1";
    /** 群/平台：正工时到期结算动作（COMPLETION，schema20）。 */
    public static final String GROUP_FINISH_V1 = "GROUP_FINISH_V1";
    /** 群/平台：系统按冻结发布时刻投递正文，不占人物焦点（COMPLETION，schema20）。 */
    public static final String GROUP_PUBLISH_V1 = "GROUP_PUBLISH_V1";

    /** 采访：记者到期提出预约邀请，形成 PROPOSED（COMPLETION，schema14）。 */
    public static final String INTERVIEW_INVITE_V1 = "INTERVIEW_INVITE_V1";

    /** 采访：双方到期回应邀请，形成 ACCEPTED/DECLINED（COMPLETION，schema14）。 */
    public static final String INTERVIEW_RESPOND_V1 = "INTERVIEW_RESPOND_V1";

    /** 采访：双方到期取消尚未开始的预约（COMPLETION，schema14）。 */
    public static final String INTERVIEW_CANCEL_V1 = "INTERVIEW_CANCEL_V1";

    /** 采访：双方到期结束会话（提前结束）（COMPLETION，schema14）。 */
    public static final String INTERVIEW_END_V1 = "INTERVIEW_END_V1";

    /** 采访：系统门任务，在约定时刻尝试开始（RULES，schema14）。 */
    public static final String INTERVIEW_START_GATE_V1 = "INTERVIEW_START_GATE_V1";

    /** 采访：系统门任务，未开始则在约定时刻后一毫秒转 MISSED（RULES，schema14）。 */
    public static final String INTERVIEW_MISS_GATE_V1 = "INTERVIEW_MISS_GATE_V1";

    /** 采访：系统门任务，在预约终点正常结束会话（RULES，schema14）。 */
    public static final String INTERVIEW_END_GATE_V1 = "INTERVIEW_END_GATE_V1";

    /** 采访：系统门任务，邀请到期转 EXPIRED（RULES，schema14）。 */
    public static final String INTERVIEW_TTL_GATE_V1 = "INTERVIEW_TTL_GATE_V1";

    /** 逐轮访谈：双方各发一轮（COMPLETION，schema15）。 */
    public static final String INTERVIEW_SPEAK_V1 = "INTERVIEW_SPEAK_V1";

    /** 逐轮访谈：回复期限门，到期结束真实会话（RULES，schema15）。 */
    public static final String INTERVIEW_DIALOGUE_DEADLINE_V1 = "INTERVIEW_DIALOGUE_DEADLINE_V1";

    /**
     * 银行人物动作的真实 BEGIN 屏障（RULES，schema19 银行角色档案）。
     *
     * <p>排程只落意图与屏障：屏障到期复核人物任职、银行绑定与焦点，通过后才排真正的
     * {@link #BANK_ACTION_V1} 完成帧；被拒时只留一条业务拒绝事件，不留完成帧、不占焦点。</p>
     */
    public static final String BANK_ACTION_BEGIN_V1 = "BANK_ACTION_BEGIN_V1";

    /**
     * 银行人物动作的完成帧（COMPLETION，schema19 银行角色档案）。
     *
     * <p>到期时从上一帧的现金账户、银行三态与贷款台账重新执行一次纯动作，结果与待办、
     * 命令记录、事件一起在同一次世界提交里落盘。</p>
     */
    public static final String BANK_ACTION_V1 = "BANK_ACTION_V1";

    /** 校验必需字段与 kind/phase 对应关系。 */
    public PendingTask {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务标识不能为空");
        }
        if (dueTimeMillis <= 0) {
            throw new IllegalArgumentException("任务到期时间必须为正：" + dueTimeMillis);
        }
        if (phase == null || kind == null || payload == null) {
            throw new IllegalArgumentException("任务阶段、种类与参数不能为空");
        }
        switch (kind) {
            case CASH_TRANSFER_V1, SELL_RESERVE_V1, SELL_CANCEL_V1, FILL_SETTLE_V1,
                 EXCHANGE_ORDER_V1, EXCHANGE_CANCEL_V1, EXCHANGE_CLOSE_V1,
                 MANUFACTURING_PURCHASE_ORDER_V1, MANUFACTURING_GOODS_RECEIPT_V1,
                 MANUFACTURING_PRODUCTION_V1, MANUFACTURING_BATCH_COMPLETION_V1,
                 MANUFACTURING_SALES_CONTRACT_V1, MANUFACTURING_DELIVERY_V1,
                 MANUFACTURING_COLLECT_RECEIVABLE_V1, MANUFACTURING_AUTO_COLLECTION_V1,
                 MANUFACTURING_PAY_EXPENSE_BILL_V1,
                 GOVERNANCE_MEETING_V1, GOVERNANCE_MATERIAL_V1,
                 GOVERNANCE_BUDGET_PROPOSAL_V1, GOVERNANCE_VOTE_V1,
                 GOVERNANCE_IMPLEMENT_V1, BOARD_CLOSE_V1, BOARD_REVIEW_V1,
                 INFORMATION_PUBLISH_V1, INFORMATION_PUBLISH_VERSION_V1,
                 INFORMATION_START_READ_V1, INFORMATION_CANCEL_READ_V1,
                 INFORMATION_COMPLETE_READ_V1,
                 ROLE_TRADING_READ_V1, ROLE_TRADING_UNREAD_V1,
                 ROLE_TRADING_RESEARCH_V1, ROLE_TRADING_TARGET_V1,
                 ROLE_TRADING_REVIEW_V1, ROLE_TRADING_CONFIRM_V1,
                 ROLE_TRADING_HALT_V1, ROLE_TRADING_CANCEL_V1,
                 ROLE_TRADING_CHILD_V1, ROLE_TRADING_CHILD_CANCEL_V1,
                 DIVIDEND_MEETING_V1, GOVERNANCE_DIVIDEND_PROPOSAL_V1,
                 GOVERNANCE_DIVIDEND_IMPLEMENT_V1, DIVIDEND_SETTLE_V1, DIVIDEND_IMPAIR_V1 -> {
                if (phase != WorldScheduler.Phase.COMPLETION) {
                    throw new IllegalArgumentException(kind + " 必须落在 COMPLETION 阶段，收到 " + phase);
                }
            }
            case BOARD_OPEN_V1,
                 DIVIDEND_REGISTER_V1, DIVIDEND_BOOK_EX_V1,
                 ROLE_APPOINT_V1, ROLE_REVOKE_V1,
                 ROLE_TRADING_SWEEP_V1, ROLE_TRADING_EXPIRE_V1,
                 ROLE_TRADING_APPOINTMENT_END_V1, ROLE_TRADING_HALT_RELEASE_V1,
                 INTERVIEW_START_GATE_V1, INTERVIEW_MISS_GATE_V1,
                 INTERVIEW_END_GATE_V1, INTERVIEW_TTL_GATE_V1,
                 INTERVIEW_DIALOGUE_DEADLINE_V1 -> {
                if (phase != WorldScheduler.Phase.RULES) {
                    throw new IllegalArgumentException(kind + " 必须落在 RULES 阶段，收到 " + phase);
                }
            }
            case MANUFACTURING_PERIOD_CLOSE_V1 -> {
                // 旧档仍在 RULES；schema18 起提前到周期边界，先于同刻采样。
                if (phase != WorldScheduler.Phase.RULES
                        && phase != WorldScheduler.Phase.PERIOD_BOUNDARY_CLOSE) {
                    throw new IllegalArgumentException(kind + " 必须落在 RULES 或周期边界关期阶段，收到 "
                            + phase);
                }
            }
            case TRANSFER_NOTICE_V1, INFORMATION_BELIEF_PUBLISH_FACT_V1 -> {
                if (phase != WorldScheduler.Phase.DELIVERY) {
                    throw new IllegalArgumentException(kind + " 必须落在 DELIVERY 阶段，收到 " + phase);
                }
            }
            case ROLE_TRADING_BELIEF_V1, ROLE_TRADING_FORECAST_V1,
                 ROLE_TRADING_COMPANY_REPORT_V1 -> {
                if (phase != WorldScheduler.Phase.COMPLETION) {
                    throw new IllegalArgumentException(kind + " 必须落在 COMPLETION 阶段，收到 " + phase);
                }
            }
            case ROLE_TRADING_COMPANY_FACT_CAPTURE_V1 -> {
                if (phase != WorldScheduler.Phase.OBSERVATION) {
                    throw new IllegalArgumentException(kind + " 必须落在 OBSERVATION 阶段，收到 " + phase);
                }
            }
            case ENGINEERING_BEGIN_V1, ENGINEERING_BOARD_V1, BOARD_ATTEND_V1,
                 ENGINEERING_ACTION_V1, ENGINEERING_FUND_V1, ENGINEERING_PERIODIC_V1,
                 PERIOD_CLOSE_V1,
                 PERIOD_DISCLOSURE_DEADLINE_V1, BANK_ACTION_BEGIN_V1 -> {
                if (phase != WorldScheduler.Phase.RULES) {
                    throw new IllegalArgumentException(kind + " 必须落在 RULES 阶段，收到 " + phase);
                }
            }
            case PERIOD_CAPTURE_V1 -> {
                // 旧检查点仍在 OBSERVATION；schema18 起落在周期边界采样阶段（晚于关期）。
                if (phase != WorldScheduler.Phase.OBSERVATION
                        && phase != WorldScheduler.Phase.PERIOD_BOUNDARY_CAPTURE) {
                    throw new IllegalArgumentException(kind + " 必须落在 OBSERVATION 或周期边界采样阶段，收到 "
                            + phase);
                }
            }
            case CUSTOMER_EVENT_V1 -> {
                if (phase != WorldScheduler.Phase.COMPLETION) {
                    throw new IllegalArgumentException(kind + " 必须落在 COMPLETION 阶段，收到 " + phase);
                }
            }
            case MEDIA_STORY_PROPOSE_V1, MEDIA_STORY_SUBMIT_V1, MEDIA_STORY_REVIEW_V1,
                 MEDIA_STORY_PUBLISH_V1, MEDIA_STORY_CORRECTION_V1, MEDIA_WORK_CANCEL_V1,
                 INTERVIEW_INVITE_V1, INTERVIEW_RESPOND_V1,
                 INTERVIEW_CANCEL_V1, INTERVIEW_END_V1, INTERVIEW_SPEAK_V1,
                 MEDIA_EVIDENCE_SHARE_V1, SOCIAL_BEGIN_V1, SOCIAL_FINISH_V1, SOCIAL_DELIVER_V1,
                 SOCIAL_BLOCK_V1, SOCIAL_UNBLOCK_V1,
                 BANK_ACTION_V1, GROUP_BEGIN_V1, GROUP_FINISH_V1, GROUP_PUBLISH_V1 -> {
                if (phase != WorldScheduler.Phase.COMPLETION) {
                    throw new IllegalArgumentException(kind + " 必须落在 COMPLETION 阶段，收到 " + phase);
                }
            }
            default -> throw new IllegalArgumentException("未启用的任务种类：" + kind);
        }
    }
}
