# M4c 四岗任务与交易执行 Implementation Plan

> **For agentic workers:** 使用executing-plans逐项实现。Codex维护决定和独立验收，每项指定一名源码/Maven写入者。Task1由Hermes先写测试、Codex实现，Task2–4交真实Hermes接续。步骤用复选框追踪。

**Goal:** 在同一有限产品账户上打通实际阅读、研究报告、经理目标、风控审核、经理确认和交易员子单的可恢复交易链。

**Architecture:** RoleTradingModel保存不可变工作流及订单链接，额度从唯一WorldExchange和证券账派生。新schema7在原WorldJournal同批提交。可信宿主创建绑定人物及观察任职的WorldRoleSession，角色动作只接收业务字段。

**Tech Stack:** 现有Java release17/JDK21、Maven Wrapper、Jackson、JUnit5，无新增依赖。

**Spec:** [总体设计基线](../../design/总体设计基线.md) §2/7–10、[机构分工](../../research/2026-11-05-公司池与机构分工研究.md) §4、[开放经济](../../research/2026-11-05-开放经济与媒体社交运行细化.md) §4.2，以及总Plan的M4下一施工口。用户“继续完成”延续既有实现授权。

## 全局约束

- 金额BigDecimal金额2位、成本4位，数量long，精确算术。钱股、冻结及成交只取原世界账。
- 单writer、due>now、固定RULES/DELIVERY/COMPLETION/OBSERVATION、完整命令幂等、候选→force→publish。失败暂停并保留完整旧Snapshot，恢复只读已确认记录。
- 使用现有C工作树。纯核codex/world-role-trading以fe30171为基线，运行桥codex/world-role-trading-runtime以997284d为基线。G树开工前的用户config/PROJECT_MEMORY由其原检出保留。所有代码走[AI-Generated]草稿PR，人工review后合入。
- 新role-trading-experiment-v1精确启用现有九模块各1及trading:1，schema7、model=none。沿用八参构造，空交易工作流由新档案开局产生。旧schema1–6省略新字段，旧重载和真实旧二进制兼容保留。
- 本施工单形成确定性四人执行入口。实际模型的屏障/尝试代次/费用属于后续M4d，届时再启用模型运行配置。

## Review Focus

1. 修改人物字段、跨产品、旧任职撤销后重新任职，旧决定不能借新任职执行。
2. 被动成交、撤单排队、目标更新和风控暂停穿插，额度含全部版本真实filled与活动leaves，撤单提交前继续占用。
3. 目标是账户标的的绝对目标股数，不能每次收到同一研究就再买完整数量。经理改变方向须等旧子单实际终结，再按实际持仓重算。
4. 人物观察、任务查询、回报及错误须先按组织/产品授权裁剪，未知与无权统一，不暴露全球订单号、位置或隐藏数量。
5. 真实I/O中断与半帧恢复、普通交易/制造/治理/阅读穿插，角色任务及对应自动待办均随单一批次保持一致。

## 设计裁决与来源

- 采用纯工作流加Runtime桥及进程内人物会话，复用WorldExchange纯候选。Hermes建议稿在logs/implementation-20261106-continued/role-trading/proposal.md；本Plan覆盖其中冲突建议。
- 开放经济§4.2已经规定研究建议→经理意图→风控校验→经理确认→执行，因此本轮落实显式审核/确认。研究报告先发表为组织资料并实际阅读，经理才能引用它创建目标。该要求来自已有设计。
- Codex实现默认采用限价子单，母任务有明确账户、标的、目标股数、价格边界、截止及版本。风控有明确总量/单笔/价格/有效期限制；数值均是实验任务输入。
- 组织研究报告采用一模拟分钟的实验阅读耗时，既有来源资料继续使用各自版本的耗时。
- Codex实现默认把停止、更新、风险暂停后的撤单排在下一模拟毫秒，当前批只关闭新子单入口。旧挂单在真实撤单确认前仍可合法成交并占用额度。母任务到期和执行员任期结束在RULES触发系统撤单，保留此前真实成交。
- 自动撤销只覆盖受影响任务或执行员任职所发子单。离任研究员不撤基金订单。经理既有授权任务按组织记录保留，后续当前经理可取消/更新。
- 额度统计不保存第二份filled或钱股。当前实际持仓与活动子单共同约束绝对目标，同一执行版本子单的真实filled+liveLeaves受该版本风险批准总变动上限约束。一个账户/标的同时只有一个在处理的母任务。
- 目标更新先记录UPDATING并阻止新单，下一毫秒撤旧挂单后按最新持仓形成新版本待审核目标。截止、暂停等内部任务携带版本，过期旧版本只记无效果结果。
- 更新撤单确认前，旧版本挂单及可能发生的成交仍有效。确认撤净后新版本记录当时实际持仓为执行基点，重新计算绝对目标差额并重审。旧版本filled已经进入当前持仓，不得再次从新版本额度扣减。例v1目标100、已买30且待单50，更新目标120，撤单前再合法买10，则v2基点40、最大新买80；随后目标改为20，撤净后应按实际持仓形成SELL任务，而非把旧BUY成交量加在SELL额度上。ACTIVE新版本不得遗留旧版本活动子单。

## 文件职责

- 新world/RoleTradingModel.java负责研究元数据、目标版本、风险审核、确认、订单链接及额度派生。研究正文只在InformationModel固定文档中保存。
- 新world/RoleTradingRun.java负责不可变工作流与管理回报。新world/RoleTradingView.java负责裁剪后的研究目录、目标/风险/子单进度及本人回报。
- 新world/WorldRoleSession.java负责绑定人物及观察时任职，不提供动作actor/isGod字段。初始角色观察固定，新的决定重新打开会话。
- 修改world/RunManifest.java、PendingTask.java、WorldRuntime.java，新增档案/调度/恢复及交易桥，所有旧next路径保留trading状态。
- 新app/WorldRoleTradingDemo.java为不用测试框架的四人正常入口，独立目录prepare/full/full运行。
- 新test/world/RoleTradingModelTest.java、WorldRuntimeRoleTradingTest.java覆盖规则与正常入口、故障、兼容。共享docs/01/02/03/05/README及总Plan由Codex维护。

## Task 1 纯工作流和真实引用

**Interfaces:** RoleTradingModel公开不可变Citation(docId,version)、Research元数据、TargetVersion、Parent、RiskReview、ChildLink、State及Outcome；方法open、submitResearch、setTarget、reviewTarget、confirmTarget、haltTarget、cancelTarget、admitChild、linkOrder、used及toJson/fromJson。具体参数封装为清楚的request record，纯函数接收RoleAccess/InformationModel/WorldExchange/SecuritiesLedger当前状态及显式now。

实际公开记录名为Target/Result，State只有research/targets/reviews/halts/children五列表。`linkOrder(State,RoleAccess.State,WorldExchange.State,ChildRequest,long orderId)`核对实际订单字段和任职，运行桥在交易受理后用实际候选调用。报告表由RoleTradingRun持有。

- [x] 先写角色分权、本人已读证据、风险审核/经理确认以及额度的失败测试并实际运行。缺新能力编译失败与行为断言失败分别记录，不把夹具枚举错误当RED。
- [x] 实现最小纯核。研究员引用≥1条本人已完成固定版本资料，提交组织报告元数据及固定文档；经理必须实际读完同组织、产品、标的报告。
- [x] 经理目标含targetId、expectedVersion（首建0）、组织、产品、账户、symbol、absoluteTargetQuantity、priceLimit、deadline、sourceResearchId。方向从当前真实持仓与目标推导。目标未变且尚在处理时禁止再建另一完整任务。
- [x] 风控review附expectedVersion、maxTotalQuantity、maxChildQuantity、priceLimit、validUntil及理由。批准量不超过经理变动量，单笔≤批准总量，价格更严、有效期≤经理截止。经理确认相同版本和审核版本后才ACTIVE。
- [x] 子单只指定parentId、parentVersion、riskRevision、本人childId、quantity、limitPrice。方向/标的/账户从父任务取，申请量须满足经理/风控/实际持仓及全部活动子单约束。
- [x] 手算A现金1000/股票0，外部卖方现金0/100股：目标100、买上限10、风控总100/单40。40+40后30拒；第一单成交10后used仍80，第三单20可受理、再1拒。现金/股份总量守恒。
- [x] 测试部分成交、拒单不建链接、真实撤单后额度才释放、价格改善、巨大long数量、更新前跨版本挂单占用和更新确认后新基点及合法序列化。全套回归并交接纯核证据。

## Task 2 唯一日志运行桥

**Interfaces:** 原WorldRuntime八参构造接受新档案，Snapshot新增Optional<RoleTradingRun>。new WorldState末尾trading字段仅schema7存在。Role任务在COMPLETION，目标/审核到期、任职结束及自动撤单在相应未来RULES，内部标识沿现有找空闲策略。

- [x] 先测试新档案模块/配置、伪造恢复元数据、任职绑定、完整命令重放、正文注入无权限变化。统一拒绝未知/无权，错误不泄露对象是否存在。
- [x] 实现任务排程和处理，调用RoleAccess及工作流→WorldExchange.submit/cancel→链接/领域事件/报告/全量Snapshot同批commitAndApply。买卖钱股交割只有WorldExchange原路径。
- [x] 真实普通对手单使角色被动单部分成交后，额度和人物进度立即取最新exchange。旧next所有路径保留工作流，引用的订单/成交/资料必须一致。
- [x] 测试同command合法已完成重放不写盘，换task/due/kind/版本/actor/引用拒绝。人物command/task按(person,clientId)无歧义命名空间，用户能读取的结果仅返回其clientId。
- [x] 目标连续更新、相反方向、旧版本迟到子单、风险限制更新、暂停、取消、有效期结束，以及已排撤单尚未确认时的成交竞态按上述合同通过。
- [x] 任职撤销或自然结束仅撤相应执行任职的活动子单，下一任执行员从真实剩余状态执行。旧任职会话即使同人重新上岗仍被拒绝。
- [x] 恢复保护研究与目标/风险版本的历史前缀、子单链接/交易字段不可改、自动撤单/到期任务双向一致；无未来创建、倒退版本或孤儿链接。

## Task 3 人物正常入口及限权观察

**Interfaces:** WorldRuntime.openRoleSession(String personPartyId)由可信宿主调用。WorldRoleSession公开view()、startReading/cancelReading/canCite、submitResearch、setTarget、reviewTarget、confirmTarget、haltTarget、cancelTarget、submitChild、cancelChild。动作参数仅含commandId/taskId/due及业务request，不含actor/appointmentId；内部从本会话固定的person及观察任职取可信值。

- [x] 先写人物入口测试，四个会话共用原PRODUCT资产，经理无法执行子单、执行员无法设目标、跨机构拒绝。新会话代表下一次观察，任务到期复核固定任职ID及当前任务/审核版本。
- [x] 同一人物可在不同组织有不同有效任职。会话固定全部观察任职的组织→appointmentId映射，业务组织从目标/授权范围选取，逐项复核，不能任选第一份任职绑定所有动作。
- [x] RoleTradingView组合原RoleView和授权产品内研究目录、母任务、审批、真实filled/leaves/remaining、本人client回报。研究全文须来自本人阅读经历，原始交易序号、他人私密来源和全局位置不输出。
- [x] view工具列表按有效岗位和实际方法生成，阅读包含取消。旧schema6的RoleView与工具列表保持原语义，新入口使用新观察DTO。
- [x] 未知/无权产品、任务与子单统一拒绝。ChildRef明确绑定parent/person/本人childId，外来/旧实例引用不影响他人的单。
- [x] 隐藏人物或其它机构额外研究/下单而本人公开行情/资产相同，本人JSON一致。所有只读查询使用同一已确认时点。

## Task 4 故障、演示及兼容

- [x] 子单/成交真实持久写入中断时，完整Snapshot、订单、钱股、工作流/链接/报告/待办均保留旧版并暂停；清中断后重开只执行一次。
- [x] 真实撤单完成帧半帧恢复时，旧活动单和额度仍在，再完成后只释放一次；原文件保留，使用副本。
- [x] WorldRoleTradingDemo prepare保存活动父子任务，full恢复到部分成交/暂停/撤单后重审确认/后续完成，第三JVM重发不重复成交。打印实际世界钱股、目标、风险上限、filled/leaves及人物角色回报。
- [x] 使用legacy-v6中fe30171真正旧classes生成的活动阅读存档，副本用新源码续写full，再由旧classes回读；旧schema6不增加trading字段。旧schema1–5已有回归保留。
- [x] Hermes交回源码/Maven后Codex独立重编、手算及正常入口复验，独立Hermes审查真实反例并修复。
- [x] 标准bash scripts/verify.sh --with-gui、JavaDoc真生成、评分映射/运行记录/架构接口/总Plan更新，明确实际模型与本地场景参数的范围。
- [x] 显式文件提交、秘密检查、推送、[AI-Generated]运行桥草稿PR依赖#14，核实远端CI和人工review状态。

## 执行记录

- 2026-11-06续工采用当前C工作树，基线fe30171实际362/362通过，日志role-trading/baseline-tests.log。真实Hermes设计调查20261106_084239_687e0f已交接，提案中“风控无须预审”与开放经济§4.2冲突，按原文落实审核/经理确认。
- 已冻结当前fe30171真实classes及旧schema6活动阅读存档在logs/implementation-20261106-continued/role-trading/legacy-v6，原件只读用于新续写/旧回读对照。
- Task1最终23专项、组合385项、标准CSV/GUI通过，JavaDoc09:31:25实际生成。独审20261106_091128_ff456b实测103检查，包含专门确认缺陷的反例。链接悬空/字段错配已用8项实际RED后修复，源码与测试最终hash见core-after-review-hashes.json。
- Ruling I1。admitChild负责岗位和目标量价授权，实际可卖批次/T+1与可用现金由WorldExchange最终裁决。SELL意图可等待结算，交易拒绝时保持原钱股且不建立链接。Task2须有当日卖出拒绝及次日成功的正常入口正反对照。
- Ruling I2/I3。链接增加真实exchange与RoleAccess参数，核对订单存在、账户、标的、方向、数量、价格、任务、任职、审核和受理时点。订单字段保留唯一权威，工作流只存链接。恢复须交叉核验，完整订单表缺失属于一致性失败。
- Ruling M1。额度采用精确算术，异常状态的溢出进入一致性拒绝/暂停。新链接先按剩余限额比较，阻止正常路径形成溢出状态。
- Ruling M5。风险收紧触发停止新单及后续真实撤单，确认前已有合法成交保留。桥在审核变更后撤掉不相容挂单，再允许对应确认及新单。M6原始原因码由观察阶段和任务状态共同解释。
- 附加恢复/参数边界已用实际RED修复，包含未来目标/审核被提前使用、分数价格异常分类及HALTED期限与阶段矛盾。当前385项由原362+23构成。真实模型、Runtime桥、人物入口尚在后续任务中。
- 纯核提交997284db216248e82b5ac4926b2eaebf8e9c9100已建立[草稿PR #14](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/14)，以#13分支为base。远端verify run42144982713实际completed/success，仍待人工review。运行桥分支codex/world-role-trading-runtime从997284d接续。
- 正式运行桥作者继续使用真实Hermes会话20261106_091523_c9946f。09:39启动runtime-implementation-query.txt，260工具轮、5400秒，独占C源码/测试/Maven。准备稿与正式合同的语义差异已逐条写入查询文件，Codex在明确交回前只更新文档和私有验收材料。
- 运行桥首稿于10:31前后交回，新增10专项、组合395项通过。Codex按最终71源文件冻结独立javac，实际正常入口61检查通过，包括现金4600/股份500守恒、部分成交/暂停撤单窗口、次日反向、重聘会话、真实中断及两次重启。证据为codex-runtime/author-final-1/，该轮仍有独审缺口待修。
- Codex在首稿最终hash复现旧会话离任后可见新组织研究、更新后版本截止仍ACTIVE、四种语义伪造存档被接受。独立Hermes在冻结中间稿发现提前撤任后存量挂单存续至原任期结束，Codex用首稿最终hash重编其Probe后再次复现。完整命令due问题已由作者首轮修正，最终61检查已覆盖，不再作为未修项。
- 20261106_091523_c9946f继续按runtime-fixes-query.txt修复，230工具轮、5400秒，独占C源码/测试/Maven。范围包括任职撤销存量单、观察绑定的实时读权限、独立目标/审核到期任务、恢复交叉校验、内部标识碰撞和同刻确认等待窗口。最终验收与PR在这些回归修复后推进。

- Codex于11:21接回单writer，纠正恢复夹具的额外调试open与BigDecimal scale，再以实际RED修复目标基点、输入归属、旧会话、完整原请求、Long期限、缺失/改期/孤儿待办和旧版本sweep。最终运行桥38项、组合423项及标准GUI/CSV全绿，JavaDoc12:40:43实际生成。
- 最终71源root-candidate-3与当前树hash一致，Codex主探针61检查及7重签反例、三JVM42→42两笔成交、真实旧6新续写旧回读通过。独审20261106_101643_be6a4c在最终hash上59 PASS/0 FAIL及3种timer伪造拒绝，覆盖盲区按报告保留。实际根因及恢复记录codex-runtime/root-debug-recovery.md。Task2–4本地验收，发布和人工review状态另记。

- 运行桥提交ffb49d4bbe05fbed746a7819b7742e590d5e4c99，通过[草稿PR #15](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/15)依赖#14。远端verify run42164122461实际completed/success，已附会话，等待人工review。当前codex/world-decisions从此提交接续M4d，PR链均未合入。
