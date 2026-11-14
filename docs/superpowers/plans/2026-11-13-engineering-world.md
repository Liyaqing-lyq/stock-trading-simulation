# schema18 工程经营与公司治理运行合同（Root，2026-11-13）

依据总体设计基线、已接受EngineeringModel/ReportContext、经理任免纯核及董事参与前置。参考engineering-runtime-preparation/proposal-v4.md，但以下裁定优先，不能带入旧稿误例、新行业预算枚举或不存在的OrganizationValuation类。schema18/tool-v6，银行后继19；旧<=17不升级。

## 状态与权限

- 新18持久engineering按公司Map；制造董事会沿用governance单例，工程董事会置于engineering.boards，键域严格不交，以boardOfCompany统一查询，每公司只有一份权威。制造与两工程公司均有董事会，各公司只一种经营模板。新增字段仅18出现，旧字段摘要/JSON不变；机构/媒体已有能力完整保留。
- 两工程公司各自唯一现金账户、各自Engineering State；A至少两合同共享A现金，不能复制初始财产。boards公司键/charter/经营账户/合同公司绑定，初始权益与世界现金基线不重设。
- 经理任免：真实五席会议→完整授权→真实RoleAccess任职/撤销。授权≠任职。所有任职/经营动作在排程与到期核当前身份/组织/期限；不得让旧<=17经理gate失效。解除只拦新经理行为，已付人工的施工继续至到期或显式终止，不抹成本。
- 董事按charter真实seat/person绑定，有board工具（召集、材料、参与确认、提案、投票、实施）；公司经理用engineering工具，角色不可互借。与既有ROLE任职会话不同的董事席位必须显式绑定，不能让模型自填actor/seat/权限。
- 按docs/superpowers/plans/2026-11-13-board-participation.md异步参与：新18召集不得替其他人确认参与。本人正耗时attend；投票前必须实际完成读取固定材料版本。晚到关联席自动回避；先参与后关联提案安全拒绝，本人窗口内补回避再提案可行。旧convene/API不改。
- 保留旧法定人数/普通3票/重大预算4票门槛，关联回避不降低分母。不得为使工程大额预算通过降低门槛。拒绝/延期、无人投票均真实结果。

## 经营、客户与钱

- 机会参数/合同条款不可由经理自由造价或重写。经理仅能接受本人实际读过且仍能回忆的机会，后继动作要求相应公司任职与对象绑定；未知/无权一致拒绝。
- 经理可签约、请求开工/开票/收款/减值/核销/终止、批供应商付款；客户验收/付款来自冻结客户配置与有限账户/期间预算，经理请求不得变必定成功；豁免须独立董事授权，不凭文字抹债。
- 使用现有EngineeringModel receiveAdvance/startWork/completeWork/accept/invoice/collectDue/collectRetention/paySupplier/impair/writeOff/recover/waive/terminate实际签名。拒验/延迟不生成收入或应收；逾期仅引用真实已有债权/发票。事件ID、条款、人物、公司、任务与到期绑定，重放原效果。
- 客户外部事实由确定性冻结policy/seed事件产生，持久可重演；区分请求、客户回应、验收、到期、真实支付。客户现金不足拒绝付款并保留债权，不得补钱或强制收款。
- 工程人工映射既有PRODUCTION_LABOR，分包服务映射PROCUREMENT。开工同一候选预留/支用两预算和真实成本；任一不足不付钱、不记应付、不建工单。已有供应商应付付款不再次扣预算，也不因预算下调拒绝归还已发生债。已支用/已付款不因终止回滚。
- 所有现金只改唯一accounts，由CashLedger.computeTransfer产生对手；工程供应商应付聚合，强制累计发生−累计支付=期末应付，UI明示聚合。客户预付/工程资产/义务按纯核既有字段独立与公司AR/CA/备查核对，不能仅balanced=true。
- 复用CAPITAL_FLOW_V1和DividendModel事件。ReportContext从权威事件只读重算netExternalCapital/declaredDividends/unpaidDividends，所有报告/验证/恢复走同一Context；不新增第二资本流水、不覆盖initialEquity，不把资本/分红当利润。

## 时序、决定与恢复

- 全部动作正耗时，人物跨公司/董事/经理/阅读/采访/媒体/社交/决定共享焦点，双向拒绝重叠。同会子动作也必须依次，不能借同会议名并发。施工物理工作与经理决策工时区分。
- 沿schema17已修规则：早模型回执只记意图，真实动作到屏障才开始；不能未来clock、完成时补过去开始或跳过其它到期任务。预算/焦点预留不漏不双算。业务拒绝保留INTENT+原回执+报告，不改WAIT/TECH_FAIL。
- v6只扩新18，原v1-v5输入/工具文本按旧版本保持。董事/经营走现有governance场景/key1（不增第六场景），仍默认OFF，不实际provider调用。角色输入仅本人权限与真实阅读所得机会/材料、当前截止；God隐藏真值不能回灌角色。
- 世界一批：命令+任务/事件+现金+工程+预算/任职+报告同候选，journal成功后应用。拒绝命令也绑定原请求/结果，重放不回滚新state，异body冲突。逐帧重演，完整自洽篡改报告/任务/命令/digest也须拒绝。
- 新旧世界版本严格门禁；真正旧17客户端来自最终已接受17主树冻结class，原件不改不重编；新实现续写旧17后旧客户端可读，不称新18可被旧17读取。

## 验收与交付边界

- 双公司+A两合同；真实董事投票预算/任免→角色实读机会→签约/预收→开工正耗时/有限预算→部分验收开票→真实回款/拒绝延迟→逾期减值核销回收→终止/质保→撤职后新动作拒绝且旧已付工单不消失。
- 精确采用已接受A/B/Bprime数例：B收入4000/COGS2400、AR当期600/质保1000、计提核销600、回收150→备查450/客户义务1450；终止剩余成本3600、供应商付2400→现金1550/权益2550；显式豁免1450后权益1550。不要沿用未验证1000/900/200/300例子。
- 纯核单侧通过≠桥完成。独立跨JVM/partial-write/force/真实旧17/输入驱动两公司、跨公司焦点/串权/回避不足/预算失败/旧经理动作/客户拒付/自洽篡改后再Web。
- 本阶段后端作者只world与WorldWebScenario，Web Host/Server/static后继单写者。提供只读God公司审计与角色可见DTO的API计划，不先改别人层。最终多公司工作台、定期董事经营复盘/季度考核、财务披露日历列入18后续交付，不能以一次会议冒充完整生命周期。
- 其它十类机制、群/平台、银行、M6历史/运行管理/行为实验按总Plan继续，本合同不取消这些范围。

## 构建与接线补充

Root正在主树正式clean/test，隔离作者必须从自己work源码自编完整依赖，只复用Maven缓存jar；不要把主树target/classes当作稳定依赖或旧17兼容证据。旧17客户端/运行类在自己隔离目录冻结，不能随Root clean丢失。任何独立EngineeringRuntime(Path journalFile,...)或第二套world snapshots/journal/scheduler均不接受；EngineeringRun只能是由既有WorldRuntime管理的值对象。原engineering-runtime-author的独立运行器尝试已由Root中止，保留为未接受草稿。新的engineering-world-integration先完成真正WorldState/Snapshot/同一journal基础，再继续全链。

## Root 23:20结构裁定

已阅读当前api.md，批准更小的制造governance单例+engineering.boards非交叠Map；新18每公司只有一份状态且统一boardOfCompany只读路由，不要求机械XOR大改。不得因此让工程动作误路由制造董事会。冻结开局既有合同可t=0初始化，但须明确是开局存量，不能冒称经理实际签约；后续机会与合同分开，角色读取机会后真实签约的全链仍必做。

## 当前执行切片

18a先接真实WorldRuntime开局/配置/WorldState/Snapshot/逐帧恢复/旧17兼容/公司董事查询，隔离候选待独审；18b再做董事与经理实际权限、经营与客户任务、v6输入工具；18c完成Web、周期治理与披露。18a通过不能将18b/c标为完成。参考E/engineering-world-integration与engineering-world-independent；全部验收后才纳入主树正式门槛。
