# M4d 决定屏障与尝试恢复 Implementation Plan

> 使用executing-plans逐项推进。Codex维护合同及独立验收，真实Hermes承担指定模块，源码与Maven始终只有一名写入者。本单在M4c运行桥验收之后进入实现。

**Goal** 为人物会话增加可恢复的决定周期，在调用前持久固定观察与提交屏障，阻止过期回调产生行动，并用可控提供方验证预算和失败处理。

**Architecture** 使用原WorldJournal和世界写入线程。决定状态保存输入、尝试、预算及动作引用，经济效果继续走WorldRoleSession和原账。异步线程只提交候选结果。

**Sources** 总体设计基线§7/9/10、开放经济细化§8/9、上下文与记忆专题§4/6/7。真实Hermes准备会话20261106_094824_aa419b的proposal.md和handoff.md位于logs/implementation-20261106-continued/decision-preparation/，属于只读提案。

## 当前状态

- [x] 按当前源文件及997284d固定接口调查缺口，恢复既有设计。
- [x] Codex裁定屏障、输入保存、恢复代次和验收范围。
- [x] M4c运行桥交回并通过独立验收。
- [x] 在已验收分支上启动本单实现，固定实际base和测试基线。

## 实施裁决

### 调用与时间

- 调用前同批保存决定点、固定观察截止位置、准确输入、可用工具、预算预留和最早提交屏障。完成该批后才能取得调用凭据。
- 思考耗时按唤醒原因及岗位配置，在选中动作之前确定。动作耗时由程序的工具合同或资料版本决定，取正模拟毫秒。模型输出不提供人物、任职、命令标识、全局位置或执行时间。
- 新档案的advanceTo表示最多推进到给定时点。遇到未解决决定的最早屏障时，先执行该时点应先发生的规则、投递和既有完成任务，再停在新观察之前。必须继续处理屏障之前的所有既有任务，不能在创建尝试后直接跳过它们。
- 宿主通过明确的屏障查询识别停因。旧档案保持原推进与序列化合同。
- 返回快慢只影响墙钟等待。相同已记录决定且未触发超时分支时，世界事件顺序、角色后续输入和经济状态相同。审计中真实返回顺序、排队耗时和归档记录可有差异，验收比较须列明这些字段。
- 同一屏障的独立动作按运行种子及稳定动作标识派发和应用。撮合入簿以后继续遵守原价格时间优先。

### 输入与恢复

- 每个人物分别从同一已确认截止位置生成输入。固定保存实际发送的可见输入文本、工具合同版本及摘要，结合原快照复核。输入体积受配置上限和日志帧上限约束。
- 运行内部保存的截止位置与尝试标识属于审计元数据。模型正文只包含其获权材料、本人已读经历、必要当前状态和合法业务动作。
- 每次尝试绑定runId、actionId、attemptId和recoveryGeneration，尝试序号从1开始。模型不能选择绑定字段。
- 新档案在明确beginDecisionSession提交后才开放派发和结果应用，该提交递增恢复代次。重开后默认处于待接续状态，调用方遗漏接续时也不能派发或接受旧候选。
- 已确认决定及其排程动作从日志恢复。未确认尝试继续使用原观察和屏障，按新代次产生有界重试。旧代次结果只归档。
- 跨线程入口只入有界队列。重复、过期和权限的最终判断由世界写入线程按已确认状态完成。暂停期间候选保留待复核，不能直接产生经济效果。
- 记录决定与排入其动作任务必须同批。I/O失败保持完整旧Snapshot并暂停。旧schema1–7继续按原字段恢复。

### 预算与结果

- 本施工单只启用明确标记的scripted提供方，固定输入和回执，不产生真实网络调用。配置显式保存每次及每人、每运行的调用数、输入输出token上限、队列容量、并发上限、墙钟超时和最多尝试次数。
- token是提供方声明用量。夹具用量及其来源写入记录，不能把字符估算写成真实计费token。
- 实验预算与人物资产分别管理。当前提供方费用为0.00元。真实提供方的适配、单价精度、用量核验及实际运行另按明确费用预算推进。
- 主动观望、世界业务拒绝、技术失败和预算耗尽分别记录。技术错误不转换成人物自愿观望，预算不足不产生规则替身。
- 重试保持同一观察和屏障，每次调用均占用预算。预算先预留、后按明确回执结算，重复结果不重复扣减。未知用量的失败按已冻结的保守预留规则结算。
- 实验日暂沿用86,400,000模拟毫秒的既有规则。配置中的数值属于夹具输入，实际基线在启动实现时冻结。

## Task 1 纯决定与预算状态

- [x] 先写实际失败测试，明确状态转换、屏障计算、尝试身份和预算预留/结算。
- [x] 实现不可变决定、尝试、结果及预算记录，精确算术，拒绝溢出、倒退代次、未来观察和重复效果。
- [x] 结果先校验结构和允许动作。正文中的指令不改变工具合同，未知动作留下可分类技术失败。
- [x] 验证同一动作重试、重复候选、晚到旧代次和预算耗尽的纯规则。

## Task 2 运行桥与人物输入

- [x] 新明确规则档案启用decisions模块及新状态版本。原档案没有新字段或额外接续批次。
- [x] 调用前持久输入与屏障，世界推进真正受屏障约束，较早的规则/成交/阅读完成继续执行。
- [x] 通过当前人物会话派生输入并绑定观察任职。决定选择阅读后，全文仍要等真正完成才能进入下一次输入。
- [x] 队列结果由唯一写入线程记录，决定与实际业务任务同批排程，业务回报引用原决定。
- [x] 至少两个独立人物决定点用scripted提供方反序返回，验证固定经济顺序及权限隔离。
- [x] 保留四岗独立上下文。初始工具子集及全部不支持动作在新档案中显式声明，后续逐项接入。

## Task 3 故障与正常入口

- [x] 立即返回与延迟返回，在相同记录决定且未超时条件下产生相同角色输入及经济状态。
- [x] 屏障之前和同刻前置阶段的任务照常执行，未知动作不能补记到过去。
- [x] 暂停、超时、有界重试、重复返回、旧代次、队列满和公平配额均有实际对照。
- [x] C1调用前、C2决定落盘、C3业务完成分别测试真实Thread中断和半帧恢复。完整旧Snapshot与钱股守恒均断言。
- [x] 三个独立JVM演示待决定恢复、已记录决定恢复和重发一次性，明确输出scripted配置与实际用量。
- [x] 真正旧schema7存档由新代码续写，再由冻结旧二进制回读。全部既有测试、标准GUI/CSV和JavaDoc通过。
- [x] 独立Hermes和Codex本地验收通过，评分映射、运行记录及主Plan已更新。依赖PR16的[AI-Generated]草稿PR17已发布，远端CI通过。

## 认知阶段的接续

已有阅读经历与权威状态用于本轮固定输入。六层认知整体继续列在M4中，当前尚缺主观检索与遗忘、分来源偏信、经历关联的习惯版本，以及完整个人待办。M4d通过后另以明确施工单接入这些机制，保留原经历和世界账。

## 执行记录

- 2026-11-06，准备会话20261106_094824_aa419b完成只读提案，未运行Maven或真实模型。Codex按原基线裁定本单，尚未进入源码实现。

## Task1 入场裁决（2026-11-06，Codex）

M4c提交ffb49d4，草稿PR #15已创建。当前codex/world-decisions以该提交为base，标准423项已通过。真实旧schema7 classes及活动存档保存于logs/implementation-20261106-continued/decision-preparation/legacy-v7。Task1为独立纯核提交，Task2–3在其后接续。

准备稿core-draft/contract.md及14测试仅供复用，以下修正为正式合同。

- State保存runId及不可变Config。open(runId,seed,config)冻结配置，后续转换从State取配置，禁止逐次替换预算/耗时。Reply明确带runId，所有身份项与实际尝试共同核对，跨运行回执不得被接受。
- Config只接受SCRIPTED。删除未使用的defaultThinkMillis与微元callMicros。保留明确providerId/pricingVersion、按唤醒原因的正思考耗时、按工具的正动作耗时、预算和限制。金钱使用BigDecimal两位，Usage第四维为cost，当前脚本费用及预算严格0.00，真实计价以后单独实施。
- Attempt保存准确回执body、usageSource、reserved/settled、outcome及reason，未返回时正文和来源为空。Decision保存明确recordedTool，INTENT恢复必须仍能取得原动作全部参数。输入摘要采用无歧义的规范JSON编码，包含工具合同、排序去重工具与准确inputText。
- 请求的runId须等于State，decisionAt不得晚于已确认时点，cutoffTime不得晚于decisionAt及已确认时点，cutoffPosition不得晚于已确认位置。工具名须存在于冻结动作耗时合同，role.wait为唯一不排业务动作的内建工具。
- headroom只扣尚未结算尝试的全部reserved。已结算尝试即使所属决定正在重试，其reserved与settled差额已释放，不继续占用。回执用量attempts必须为1，费用0.00，超预留或非法声明按技术失败保守结算一次。
- 同一回执重复为DUPLICATE，异正文/用量复用已消费身份为REJECTED。旧未消费代次为STALE_GENERATION且无结算。当前session未开启则拒绝所有派发与应用。
- beginSession只开启新代次。另提供可信宿主resumeDecision(state,actionId)，仅处理OPEN且最后未结算尝试属于旧代次的决定，保守结算旧尝试并在当前代次按原输入/屏障有界重试。不能由旧回执触发重试，不能让已记录决定重问。
- JSON回执严格校验外壳，WAIT只有tool，INTENT只有tool/args且args为对象。未知外壳字段、未知工具、恶意正文或到期溢出为技术失败，不观望。工具业务字段由Task2的明确DTO合同校验。maxReplyChars与maxInputChars限制原文，不静默截断。
- actionDueMillis(state,actionId)仅对已记录INTENT取实际recordedTool，不能传入另一个工具计算时间。相同屏障以WorldScheduler.fairOrder(seed,actionId)及actionId排序，复用现有算法。
- 提供toJson/fromJson，反序列化验证身份、摘要、连续尝试、预留/结算、全局及逐人累计、已记录正文/工具和终态，不以合法SHA代替语义。合法状态roundtrip精确相等。

Task1公开接口采用open、recover、beginSession、openDecision、submitReply、failAttempt、resumeDecision、decision、earliestBarrier、actionDueMillis、headroom、toJson/fromJson。去掉逐操作Config参数，其余记录细节由实现者按上述最小合同固定。测试先落盘并真实运行缺能力RED，再实现。原runBudgetExhaustion草稿上限1000小于单次预留1200，须修成至少容纳首个调用，不能照抄假定首调用成功。

- Task1续接真实Hermes20261106_094824_aa419b，120工具/3600秒，唯二源码/测试文件及Maven独占。实际Task2准备由原运行桥会话20261106_091523_c9946f只读进行，45工具/1200秒。PR15远端verify run42164122461已completed/success。


## Task2 运行桥入场裁决（Codex）

真实Hermes只读准备稿runtime-preparation/implementation-contract.md和test-scenarios.md已交。该稿未经编译，以下明确修正后才能实现。

- 新档案decision-experiment-v1、decisions:1、schema8及scripted-decisions-v1，配置直接采用DecisionModel.Config。旧八参构造委托新九参可选Config，旧档案有新参数或新档案缺Config都在建文件前拒绝。新字段NON_NULL，所有next路径保留状态。
- openDecisions(commandId,List<DecisionRequest(personPartyId,actionId,wakeReason)>)使用当前已确认时点，不接受调用方指定未来decisionAt。同批每人从同一截止位置投影，原任职绑定另存内部。一个人物至多一个OPEN决定，批内重复人/动作也拒绝。完整原宿主请求在计算当前输入前先查幂等，已完成重放不重投影新世界。
- 输入必须含获权的原RoleTradingView.profile资产、市场、目录和已读经历，以及研究、目标、子单和本人回报，不能只保留任务清单漏掉经济状态。规范JSON以明确DTO序列化，不得靠“数字很长”猜测内部标识而误删正文。排除cutoffPosition、attemptId、代次、世界日志位置及全局订单/经历序号。
- 新决定的输入、工具和任职在validate(candidate,previous)中应能由previous的相同截止快照重新生成核验。为原角色投影增加明确WorldState参数重载并复用，可用现有公共入口委托当前state。禁止验证时临时替换全局state，禁止拷贝第二套资产/权限算法。旧决定和其原观察有历史前缀约束。
- 首批支持全部10个可变四岗工具及role.wait。role.cite作为当前可引用资料事实提供，明确不属于可返回业务动作。ChildCancel的模型args只含parentId/childId，person由绑定人物填入，取消阅读只接docId/version/index并由绑定人物组装原ReadingRef。工具DTO严格拒绝额外字段，静态参数失败在接受决定前分类为技术失败，业务授权和资金/股数到完成期原路径裁决。
- 若纯核先解析为INTENT，但运行层DTO结构失败，必须以原未消费状态通过同一次纯核技术失败结算/重试，不能先持久RECORDED再改回。可以为纯核failAttempt增加明确已知用量失败入口，以保留真实脚本回执和用量。不能把已知用量一律写成未知保守结算，也不能将静态错误记为观望。
- 重用原RoleActionPayload及原完成处理器。决定记录、精确回执、动作引用、原指令映射、usedTaskIds和PendingTask在一个candidate及一次commitAndApply中提交。不得调用会自行提交的公开WorldRoleSession动作方法。action任务标识以原actionId稳定派生，与回调顺序和attempt序号无关，遇原前缀占用使用现有确定性找空闲逻辑。
- 屏障只允许既有四阶段，不新增BARRIER枚举或lastPhase字符串。advanceTo最多到最早OPEN屏障，处理全部更早任务和同刻RULES/DELIVERY/COMPLETION，在OBSERVATION前停下。即使inclusive恰等于屏障也必须停在观察前，反复推进未决屏障不加空批。pendingBarrier()返回明确时间及稳定排序actionIds。
- 回执先到可以记录并排未来动作，但任一同屏障决定未解时世界仍停住。业务任务采用原公平队列稳定顺序。后续输入与经济状态作为可比较结果，真实回执归档先后允许不同。
- 新运行和重开均需显式beginDecisionSession(commandId)开闸，提交代次变更并按稳定次序接续旧OPEN尝试。重开后的本地派发/应用默认关闭，旧档案不会增加该提交。迟到旧代次只归档，不改变预算或经济。相同已消费回执不重复写盘，异正文复用有单独拒绝记录。
- bounded回调队列只允许offer原候选，回调线程不能读写世界领域对象。writer处理使用peek→持久成功→移除，I/O失败或暂停保留候选。明确容量、maxInFlight和真实墙钟超时。派发许可在已确认输入/尝试/预算后产生，派发身份只返回程序绑定字段。脚本提供方和runner单独类，异步仅使用Java17现有Executor，无网络。
- 记录派发/回执等待的墙钟审计不进入人物inputText。重复派发、同一尝试并发和已确认未执行业务都从确定状态/受控宿主状态去重，不能以CPU快慢改变世界次序。未知用量失败按预留结算，已知脚本用量按实际声明结算，费用0.00。
- 恢复须核对原输入生成、任职绑定、尝试身份与历史、预算总和、实际动作原文及任务/完成回报双向关系。单有usedTaskIds不能证明业务已排程，漏掉尚未完成动作的PendingTask必须拒绝。

Task2与Task3可以按有证据的可验收单元拆分PR，任何骨架阶段仍标明实际覆盖。施工顺序为专项RED、最小实现、专项/全套绿、私有独立探针与审查，再标准GUI/CSV/Javadoc/文档/草稿PR。原schema7兼容使用真实旧程序产物副本及旧二进制回读。

补充输入及进程门闸细节。人物input使用明确投影字段，profile提供原资产/市场/目录/经历/权限，旧RoleView.tools中的information.*说明不作为本轮可返回动作混入prompt。只列新决定合同的enabledTools、参数及耗时。当前session是否开放派发由进程本地门闸与已确认代次共同决定，重开门闸默认关闭。已确认Snapshot不得为关闭门闸而未经日志替换为recover副本，beginDecisionSession的候选才使用recover→beginSession及接续转换，再一次确认后打开本地门闸。纯核recover是无副作用函数，不属于自动世界提交。

- Task1首稿449项后Codex新增30专项，实际RED修复严格JSON、恢复配置/预算/代次/全局序号、原声明与回执留存。最终479标准、49成交/121订单/7排名、GUI/CSV及JavaDoc13:22:44通过，72源root-candidate-3独立编译，20正常入口手算检查通过，等待最终hash独审交回及草稿PR。技术失败仍保留TECHNICAL_FAILED，同时以NO_RETRY_BUDGET明确记录重试停止原因，区别零调用初次预算耗尽。

最后的JSON数值校验补充。整数计数和序号禁止浮点自动截断，DecisionModel与Task2工具DTO均关闭ACCEPT_FLOAT_AS_INT。Codex以尝试序号1.9和预留token100.9取得两项实际RED，不能把静默变为1/100当合法读取。初轮479项及candidate3保留为中间证据，加入这两项后最终以481项及candidate4为准。

- 最终纯核candidate4在32审计+26作者专项下，481项标准/GUI/CSV及JavaDoc13:29:10通过。相对candidate3仅增加禁止整数浮点截断，原72源清单已重新核对，独立20检查通过。独立Hermes候选3为263 PASS/0 FAIL、20合法对照，最终单配置变更复验进行中。

- 真实Hermes独立终审20261106_130606_fdbe9f对最终candidate4核对72源一致，263 PASS/0 FAIL、20合法对照、7整数/浮点检查通过，无可复现残留。Task1完成本地验收，Task2–3仍依该正式合同接续。源码71c1a160…d9f61f，证据core-review-final-4/。

Task2输入还须提供本人已记录但业务尚未完成的动作摘要（actionId、工具、业务参数、到期），从原ActionRef与PendingTask派生，排除他人动作和内部任务标识。只限制每人一个OPEN决定，不能因决定先记录而让下一次输入丢失其未完成承诺。初始SCRIPTED runner采用具体有界实现，无当前调用者需要的多提供方框架以后再加。

- Task1提交550f5b608f96f6e39a9f99dbe3fbfe10ed3f6fb8，已建立草稿PR #16，base为#15分支，远端verify run42169342964创建时in_progress。当前C以codex/world-decision-runtime接续，原schema7程序和活动存档已冻结。Task2–3启用新的真实Hermes实施上下文，读取正式Plan及旧运行桥准备交接，避免继承旧轮调试过程。

- PR16远端verify run42169342964已实际completed/success。Task2–3实施query为decision-preparation/runtime-implementation-query.txt，CLI exec73494，220工具/7200秒，新真实Hermes上下文单写src/测试/Maven，Codex只维护docs及私有验收。

运行桥首轮过程纠正。新的实施上下文在新增RED之前修改了DecisionModel/RunManifest并创建DecisionRun，Codex于13:46停止仅属于该query的进程树，恢复自己这轮的两个跟踪文件并移除自己新增的DecisionRun，验证src与550f5b6一致。保留Root文档和既有用户文件。改为严格分两轮授权，先仅写测试和缺能力RED、交回，下一轮才授生产源码。日志runtime-implementation-hermes.log保留，不把该首轮称为有效实现。

Task2测试核对裁决。测试优先会话20261106_135216_55467f交回27项（19运行桥、7脚本runner、1已知用量纯核入口），正式缺能力RED已实际执行，src/main始终未改。Root核对后修正两处与正式合同不符的夹具。阅读取消index从0改为合法的1，以实际ReadingRef约束为准。旧代次回执初次应持久归档，因此首回执position加1、Model.State与经济不变，相同原命令重发不加批次，取代“不写盘”的原私有稿断言。完整RED和原交接保留。

dispatchDecision只返回已确认输入/尝试的调用描述，可用本地inFlight标记及真实nanoTime，不要求额外写盘。但ScriptedDecisionRunner必须以完整尝试身份确保同一次提供方实际执行至多一次。队列满时异步结果要有界保留或背压重试入队，不能静默丢失，也不能再次调用提供方重取结果。DecisionRun可增加观察绑定、动作引用及归档元数据字段，保持state()入口，不能只有纯状态而丢失原任职与待办关系。

动作与内部命令标识使用长度编码的完整actionId，末尾也须有长度边界，使foo被占名后的后缀不能撞上另一个foo-2动作。仍保留原actionId便于审计，不能依赖attempt或回调顺序。新进程显式begin必有代次审计批次，三JVM演示须分别报告接续批次与业务重放，不能为让总position不变而跳过begin或旧业务请求。

## 运行桥接回与本地验收（2026-11-06，Codex）

作者510及首轮标准通过后，Codex接回源码，独立Hermes复现drain编号复用造成暂停或内存领先日志、坏阅读字符串导致永久待办。Codex新增实际RED并修复完整宿主幂等、初次预算终态、统一有界队列提交后移除、跨运行身份、Long最大超时、恢复双向动作关系、门闸与观察屏障。runner由原作者独占两文件修复单池、真实一次派发、队列满保留重投及执行器拒绝恢复，再由Codex补同步/异步混用回归。

最终输入补齐本人未完成承诺的原args和活动阅读的本人index，真实取消动作验证可用。原决定输入重生成仍逐字相等，原任职从历史快照重新生成核对。schema8嵌套状态恢复使用严格JSON映射，两项浮点整数恢复实际RED保留。旧档案使用原映射。

candidate4冻结75源，Codex独立146检查通过，桥55+runner12专项通过。真实三JVM保持800.00/20股/1成交，旧7原件副本经新程序full25→42再由旧二进制full42→42通过。全套标准549项及CSV45成交/119订单/7排名，GUI、JavaDoc及最终hash独审交回结果据docs/05第20节追加。

- [x] Task2运行桥、世界屏障、原输入/任职、十工具同批动作与输入完整性本地核验。
- [x] Task3的线程中断、半帧、真实交易反序、三JVM、真实旧7及本地专项核验。
- [x] 最终候选4独审、GUI/JavaDoc与本地文档。
- [x] 草稿PR17发布与代码e1ba440的远端CI记录完成。
- [x] 宿主派发/回执等待持久审计及独立身份复用拒绝已在M4e本地补齐，证据见docs/05第21节。

Task2–3本单有两项后续审计缺口，当前只能称运行桥与故障子集已实现。完整M4、真实模型与六层认知继续保留。下一并行准备是M3现金分红/TWR，已有只读proposal仍待Codex裁决，不得把建议当实现。

最终candidate4独审42断言exit0，67桥/runner专项及549标准、GUI/CSV和JavaDoc16:15:59通过。五份schema8与一份schema7日志的独立Decimal复算通过，详见docs/05第20节。下一步发布草稿PR及核对远端CI，再接M4d审计补项和M3分红/TWR施工。

运行桥代码e1ba4402503c773c508a3547d9b1e3f5ab593ad8已建立[草稿PR #17](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/17)，base为codex/world-decisions（PR16）。远端verify run42186774016实际completed/success，已附当前会话，尚待人工实质review。

M4e以573标准、独立72+146检查和35独审断言补齐本单两项审计，旧代次异回执队列阻塞已修复。M4其余认知/媒体与真实模型继续主Plan。
