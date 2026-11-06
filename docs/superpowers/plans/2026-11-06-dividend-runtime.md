# M3d D3b 现金分红运行桥

正式施工单。D3a代码6220ed85275eda2826b17054ac570c32f01dadb9、草稿PR22及实际verify42223835571已成功，基线文档490f20c。依据总体设计基线§4–6、§8–10，以及已验收D0/D1/D2和D3a公开接缝。

## 范围和依赖

- 新档案dividend-world-experiment-v1在现有decision全组合上增加dividend:1，schema9，模型配置沿scripted-decisions-v1，沿用原9参WorldRuntime配置构造。
- 生产限定RunManifest、PendingTask、WorldRuntime、RoleView，新增WorldRuntimeDividendTest及正式WorldDividendDemo。D0、D2、D3a纯核及原测试保持。
- WorldState末尾只新增NON_NULL DividendModel.State dividends，旧schema1–8为null且续写不落新键。next最末新增重载，旧调用链自动保留同一dividends。无平行现金、股份、分红、参考价格或报告状态表。
- Snapshot末尾追加Optional dividends，保留旧15参构造。RoleView.Market末尾追加NON_NULL ValuationModel.Price reference并保留旧6参构造。旧档案reference=null，新档案由同截止价格簿派生，包含价、来源、asOf及age。lastPrice继续来自实际成交。
- 初版发行人、付款账户、标的均绑定既有唯一制造公司及章程组织。新档案开局拒绝发行人自持本股非TREASURY，原公司证券申报限制保持，登记再防御检查。现旧制造档案连TREASURY也拒绝，新9在原开局闸中仅对本公司本标的TREASURY加入窄例外，旧4–8继续原拒绝。TREASURY为开局发行份额分类，制造资产及初始权益继续由既有现金和经营资产派生。
- D3c随后连接真实资本流和按确认历史派生的TWR。登记前取消/重公告/新版本授权另列后续施工，当前新档案拒绝未实现的取消及其它公司行为。

## 公开入口

```java
String scheduleBoardMeeting(String commandId, String taskId, long dueTimeMillis,
        String chairSeatId, String meetingId, long materialDeadline, long opensAt, long closesAt,
        List<String> attendeeSeatIds, List<String> recusedSeatIds, BoardModel.Topic topic);
String scheduleDividendProposal(String commandId, String taskId, long dueTimeMillis,
        String seatId, String meetingId, List<String> relatedSeatIds, DividendModel.SchemeRequest request);
String scheduleDividendAuthorization(String commandId, String taskId, long dueTimeMillis,
        String seatId, String meetingId);
String scheduleDividendSettlement(String commandId, String taskId, long dueTimeMillis,
        String actorOwnerId, String schemeId);
String scheduleDividendImpairment(String commandId, String taskId, long dueTimeMillis,
        String actorOwnerId, String claimId, BigDecimal amount, String evidenceRef);
ValuationModel.Value valuation(String ownerId);
List<DividendReport> dividendReports();
record DividendReport(String taskId, String commandId, String kind, String meetingId,
        String schemeId, long position, long atMillis, boolean accepted, String reason);
```

Snapshot.dividends提供不可变管理查询。报告附于唯一日志的DIVIDEND_REPORT_V1事件，查询按确认顺序派生不可变列表。报告不另存d0Position，D0位置由同批快照及Receipt核对。

新宿主入口沿现有受信管理夹具身份语义。提案须已登记席位，授权须主席，结算须该公司owner。减值须原claim账户的owner，seatId不能代替持有人。运行桥不额外向人物工具授出这些管理权限。证据引用表示受信场景的持有人估值依据，未接阅读来源的记录不得冒称实际读过某文档。

上述新分红宿主入口、dividendReports及valuation要求hasDividend。旧scheduleBoardMeeting保持原行为，显式DIV重载要求新档案。Snapshot旧档返回Optional.empty，全部新返回值为管理视图。

排程沿原命令内容、task、due、kind幂等。重放在当前阶段和截止检查之前返回原memo，异内容复用拒绝。输入结构有界，金额精确。新分红任务的时窗、阶段、已知人物的实际权限及现金等业务资格在到期时复核，失败保留原账及D0并持久报告。新档案无分类跨owner转账同样到期拒绝并沿原Notice投递，排程结构/档案不支持等参数错误继续原异常方式。所有公共API中文Javadoc。

## 会议与真实授权链

- PendingTask新增六种明确kind，GOVERNANCE_DIVIDEND_PROPOSAL_V1、GOVERNANCE_DIVIDEND_IMPLEMENT_V1、DIVIDEND_REGISTER_V1、DIVIDEND_BOOK_EX_V1、DIVIDEND_SETTLE_V1、DIVIDEND_IMPAIR_V1。前两种及settle/impair为COMPLETION，register/bookEx为RULES。
- 旧scheduleBoardMeeting保持原签名。新重载只支持BUDGET及DIVIDEND_AUTHORIZATION，OPERATING_REVIEW继续系统产生。
- 原GovernancePayload实际13字段。追加NON_NULL topic，BUDGET时为null，旧canonical JSON逐字保持。新DIV议题显式入原排程参数，恢复逐帧核对首次会议与该原始任务，不能只放开Topic枚举。
- 自动BOARD_OPEN/BOARD_CLOSE对分红复用冻结、startVoting和closeVoting。资料、席位投票、回避及法定人数沿D2真实流程。
- 初次Board提案受理与D0.propose同批。否决/延期后相同经济重审复用原PROPOSED，不在D0重复propose。D0经济与Board原提案始终对应。
- 授权批从实际ADOPTED会议实施Board决议，并以其真实引用D0.authorize。同批排公开发表、登记、除息、自动清偿待办。任一候选失败时Board和D0共同保留原状态。
- 每批最多一次D0变更，Event.position取实际候选WorldJournal位置，Event.atMillis取任务due。D0.receipts已接受事件与原请求不可删改，历史重放不生成新经济效果。

## 时间与公告

- Runtime授权须now>closesAt，now+1<recordAt。最紧可用序列为close=t、authorize=t+1、publish=t+2、record=t+3。D2纯核的等值边界保持。
- 授权批只排INFORMATION_PUBLISH_V1，due=now+1。下一个模拟毫秒通过实际InformationModel/InformationRun形成PUBLIC文档，人物按原规则进入目录并阅读后获得正文。
- 公告使用稳定方案文档标识，正文只含公司、标的、方案、每股金额及登记/除息/支付时点。账户号、持有人名单、内部证据和审计位置保持在管理审计域。原文版本1不可变。新档案固定公告阅读耗时60_000模拟毫秒，属于明确实验默认，实际完成阅读前只提供目录元信息。
- InformationModel当前只允许已登记人物发表。系统公告发布者取章程主席对应人物，正文和公开来源说明标明公司董事会。新档案构造期要求该主席人物在InformationRun.Config.readers中，避免授权成功后不能发表，不扩展InformationModel身份类型。
- 自动任务标识复用已有空闲ID工具并核业务目的去重。系统公告文档标识固定dividend-public:加schemeId，新档案普通发表/追加版本入口拒绝该保留前缀，旧档案原行为保持。只有准确授权批派生的内部PUBLIC发表任务可使用该空间，避免在途文档被另一普通任务先占用。授权候选仍防御检查文档占用，不能由pending任务存在替代实际公告。
- 登记与bookEx均要求授权公告已实际发表且内容、受众、公司、版本、发布时间与确认任务相符。到期前可由准确pending发表任务证明在途，过due后不得继续以pending冒充已发布。
- 登记在recordAt的RULES，用该批实际remaining股数，包含冻结、LOCKED及当日买入，排除TREASURY。此前未发生的同刻COMPLETION交易属于后续位置。
- 除息在exAt的RULES，同批确认D0 claims。参考价由新D0状态经D3a派生，实际成交和K线不改。自动付款在payAt的COMPLETION，payAt==exAt时必先除息。

## 付款与减值

- 只支持整方案足额现金结清，按每条真实未付claim计算现金腿，不按净应收减值后金额少付。
- 使用公司freeCash复核，真实CashLedger.computeTransfer逐腿形成候选，任一失败全部丢弃，债务和参考保持。全部TREASURY等无合格持有人时沿D0登记返回NO_HOLDERS并写失败报告，保留AUTHORIZED且应付为零，后续自动除息/付款按原阶段拒绝，不尝试零金额转账。D0保持原接口与规则。
- 同批保存一个DIVIDEND_CASH_PAYMENT_V1事件，载明方案、真实逐claim付款腿及合计。D0 Payment.cashEvidenceId指向该实际事件ID，无法由宿主传入任意“已付款”凭证。
- 资金不足写失败报告，D0保持EX_BOOKED，自动付款只尝试一次。新命令可显式重试，旧命令仍返回原memo，重复成功不得再付。
- 持有人减值仅影响其净应收，公司面额债务及制造利润保持。真实足额付款按D0规则转回原减值。
- 新档案普通跨owner转账明确拒绝TRANSFER_UNCLASSIFIED，防未来表现历史产生无分类资本流。同owner内部转账仍按原资格处理，公司经营现金专用限制保持。D3c再提供有实际现金腿的类型化资本入口，旧1–8普通转账行为保持。

## 财务、估值与恢复

- D0的EX_BOOKED/SETTLED方案按公司汇总declaredFace，companyPayable汇总unpaidFace。REGISTERED阶段均为零。制造report/validate采用D3a重载，period现金偿债分母计入未付股利，经营利润保持。
- valuation(ownerId)读取同一确认快照的现金、证券、成交与D0，用六参prices再value。公司完整NAV仍沿D1的UNSUPPORTED_COMPANY_SCOPE，经营报表通过既有制造报告入口提供。
- 复用validateAudits的提交前及restore逐帧批次校验位置，新增validateDividendBatch(commit,candidate,previous)。D3报告及现金事件须绑定原任务、命令、due、完整参数、真实前后状态及D0操作。
- 新分红任务参数、报告与现金证据复用既有严格decisionMapper，拒绝重复键、尾随、浮点截整数、null原语及未知字段。旧GovernancePayload的BUDGET canonical形状保持。
- 新增D0事实须用原pure操作从previous重算，对比完整candidate及报告。register用该批真实证券重算，settle用原cash/freeCash及真实逐腿现金重算，impair核对持有人。禁止仅重算摘要或验证终态结构。
- 新会议/提案/授权分别与实际原任务重算。Board已确认元数据及票/材料前缀保持，原D0 Receipt、Payment、Impairment和报告事件不可重写。
- 无分红动作的批次不得改变D0；失败批次D0及现金保持，报告原因须与实际重算一致。原调度消费、待办、usedTaskIds、幂等回报和同批position仍需核对。
- 校验须双向绑定，既检查报告所称动作，也拒绝D0变化缺报告、清偿缺真实现金事件、重复报告或无原任务的孤儿事件。
- 旧1–8禁止D0状态、新任务或事件和DIV议题。新schema9必须具有准确空开局D0，并随next、角色决定、普通时钟及重启持续保留。

## 测试与交付

1. 新档案配置、旧JSON/旧构造、能力闸、开局self持股拒绝，先缺API RED。
2. 通过真实公开入口召集、提案、材料、三票、自动关闭、授权及下一毫秒公告。覆盖否决、回避、延期重审、重复及异内容命令、最紧时序及Long边界。
3. 真实登记含冻结/LOCKED/当日买入，登记后卖出保留原权利。除息同批参考及应收，pay==ex阶段、整批付款、无合格持有人明确失败及不足现金失败/重试、减值与转回。
4. 与制造资产负债、同截止估值、真实原始成交、角色目录/阅读边界组合，普通跨owner无分类拒绝与旧档行为保持。
5. 实际持久I/O中断、尾半帧、授权后/登记后/除息后/付款后三JVM恢复。完整重签的伪造授权、缺/改公告、登记股数、D0事件位置、现金证据、失败报告或未知任务均拒绝，合法控制先通过。
6. Root独立冻结编译/Decimal现金股份和公司资产负债复算，真实Hermes独审，标准测试/CSV/GUI/JavaDoc，评分及运行记录和主Plan，AI-Generated草稿PR与真实CI。

准备稿中的“D2已合并”、13字段写成12、不存在的BoardModel.cancel、减值用seatId授权、要求额外now+3余量，以及过期pending可替代实际公告等表述均以本稿裁决修正。PR继续草稿等待人工review。

## 执行检查点

- [x] 仅新WorldRuntimeDividendTest测试先行，78生产及旧744测试保持，实际缺API RED及开局夹具核对。
- [x] 明确授生产后新专项、原744、两处真实变异及完整恢复语义矩阵通过并交回。
- [x] Root独立冻源、实际正常入口、Decimal及持久化故障反例核对通过。
- [x] 真实Hermes最终源码独审、标准GUI/CSV/JavaDoc、评分/运行记录及主Plan完成。
- [x] AI-Generated草稿PR与实际CI通过，等待人工实质review。

## 首稿复核与接续裁决

首稿774项首绿，完整恢复重算尚未完成。Root独立冻结79源，在另一真实旧配置来源构造新9运行，47项正常链及Decimal现金4600/股份200复算通过。八个完整重签且各有合法前缀对照的伪造均被首稿接受，涉及登记证券来源、付款证据、现金去向、缺现金事件、缺或重复报告、公告正文及发布者。Root新增12项正式回归，42项专项真实12失败且无夹具错误，原30断言保持。

实现采用独立DIVIDEND_MEETING_V1任务召集分红会议，复用既有董事会纯核及BOARD_OPEN/CLOSE，保留原13字段GovernancePayload逐字兼容。Root接受这一窄接线调整，新增任务共七种。其余六种采用本单原定名称，付款成功和失败报告均绑定实际同名任务，不能混用PAY与SETTLE。新增会议同样必须完整核原始任务和Board结果。

接续实现须补齐提交前及恢复时同一validateDividendBatch语义，从previous和原待办重算全部D0、Board、原账和公告。禁止临时把运行state改为previous来调用有副作用完成函数。完整保护已确认前缀，检查准确消费任务、剩余待办、命令回报及双向事件关系。新payload严格解析，管理入口资格到期核验，重复减值用任务级事件标识，报告保留原commandId。当前生产写权另见项目检查点。

第二轮42专项及786组合通过，Root冻结candidate2独立47正常检查通过，扩展19场景中仍有现金证据未知字段与逐腿金额两项被接受。作者交接确认待办完整性、实际公告必需性和唯一公司绑定尚未完成。Root再补13正式回归至55，新增普通clock以实际确认无动作批，最终55中13失败0错误，原42断言保持。还覆盖孤儿事件、删自动子任务、缺实际文档、追加版本空间绕过、三种错误发行绑定、新排程未知字段及旧档案能力闸。接续目标至少799，以上完整恢复原范围继续施工。

## 测试核对及最终实现裁决

真实Hermes先交20项RED，Root保留作者原稿。实际核对发现原测试混有Side/OrderType字符串类型错误，作者“全是缺API”的交接不准确。Root改为现有枚举，修legacy漏DecisionConfig、重审窗口与固定经济截止、登记读rights而非未生成claims、转卖现金合计、行情取实际登记读者、空集合伪造谓词。无分类转账改用非公司SUP→INV及正回执延迟，排除原公司限制和零延迟异常遮住新行为。授权、减值和跨owner资格在到期形成业务回报，保留预排程与原memo语义。

Root新增主席读者前置及旧档合法对照、PUBLIC公告及60秒真实阅读、系统文档空间及旧档对照、当日买入权利、全库存股NO_HOLDERS、实际现金证据事件及只读查询、未知任务报告与伪造失败报告、真实制造回款后结清、含股利面债的偿债评分10项，共30。旧744断言保持，目标774。

从实际新测试抽取旧底层夹具在D3a冻结classes独立编译运行。旧schema8构造及SUP→INV现金转移通过，真实预算/采购/生产/交付/回款链25d公司现金200.00、35d1200.00；四名读者无主席的旧档构造合法；D0全库存股登记实际NO_HOLDERS。日志dividend-runtime/root-test-fixture-run-3.log。Root自建另一真实旧配置来源夹具也已在schema8通过。私有首轮被旧制造闸拒库存股、第二轮重复分配INV/FLOAT而拒绝，均保留并纠正，未当产品缺陷。

现在只授RunManifest、PendingTask、WorldRuntime、RoleView四个已有生产及新增WorldDividendDemo、新测试WorldRuntimeDividendTest和C Maven独占。其它74既有生产、D0/D2/D3a及旧744测试不改。公开Demo使用prepare/full/full三JVM，prepare留已除息待付款状态，full付款及原命令重放，第三次保持经济及位置，报告真实公司资产负债和世界现金/股份。Root独立故障矩阵另覆盖授权/登记/除息各确认前缀，最终未审查前不宣称完整基线完成。

## 最终本地验收

candidate5于2026-11-07通过816标准，新增72；CSV46/121/7、GUI、JavaDoc00:20:41。Root独立212检查、Decimal、旧编译客户端/旧schema8交替接写及Demo三JVM通过。真实Hermes最终独立79源编译、31合法+6完整重签反例通过。详细RED修复和边界见docs/05§26。当前由Root持有生产与Maven，PR/CI归档后转D3c。
