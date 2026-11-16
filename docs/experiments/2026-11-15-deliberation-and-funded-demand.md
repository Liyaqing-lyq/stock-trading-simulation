# 2026-11-15 决策依据与持续需求整改

整体状态：验收不通过。代码、定向测试和模型请求数量均不等于世界成立。

## 已实现但尚未闭环的切片

- 外部需求纯核4d4b6be及修复26007e8：只消费真实已交付投入，有限最终买方现金付款；真实供应商/运营外部账户的收入按期转移形成后继购买力，不凭空补客户资金。原账户权威不复制。最终买方复用公司或客户账户的漏洞经反例修复、独审复核。运行时集成另行验证。
- deliberation-v2（b80c023）：真实董事/经理职责目标、会议窗口、本人的决策依据与复查时间；显式lifecycle-v1预算，不重置旧运行累计用量。新协议与旧档隔离。
- 定向机制检查通过：ExternalDemandRunTest13项；RoleDeliberationTest4项、LifecycleBudgetTest2项；Host/旧事件/路由/提供方30项。各次日志独立，不作为正式全套计数。

## 真实v2小试（无预读，无脚本选择）

冻结b80c023类目录启动RoleDeliberationLiveProbe，52人、DeepSeek Flash、最多300次请求/20分钟墙钟、目标1模拟日。实际200次请求及200份完整回执后，由程序以ALL_DECISIONS_FAILED停止：位置854，模拟时间371001ms，物理在途0。提供方已知输入1080375、输出15477 token，费用UNKNOWN。

请求为69阅读、88WAIT、27召集、9到会、5加材料、1任命提案、1投票。**请求不是执行结果**：实际仅5场会议、7次到会，材料0，选票0，任职0，预算0，经营合同0。形式失败6次。尚无自主经营证据。

已定位：

1. BEGIN任务结束后，后继finish任务还可能待执行；输入却仅按原task消失标为FINISHED_SEE_CURRENT_STATE。业务拒绝原本记录在EngineeringRun.Report，未进入角色反馈。call103/105与对应回执显示非主席召集后无真实会议，模型却据此继续到会或挂材料；这支持缺陷具有实际影响，不证明唯一因果。独审同样提出Important。
2. 最后一个事件窗口仅一名角色，其nextReviewAt等于当前时点，被严格协议拒绝；Host将该窗口全部失败当成整个世界失败，停止其余角色。应隔离个人失败并限制重试，不放松协议或无限调用。
3. 输入仍夹带旧decisionGuide.waitContract，声称WAIT仅有tool；新版要求decision元数据，两者冲突。后继明确新版本修正，保存v2原档。

原输入、回执、最终管理视图及摘要：[压缩记录](deliberation-20261115/deliberation-v2-raw.json.gz)、[摘要](deliberation-20261115/deliberation-v2-summary.json)、[摘要校验](deliberation-20261115/SHA256.txt)。完整world.journal保留本机logs/operating-cycle/live-deliberation-b80c023/，只读恢复审计使用副本。旧失败档不改写。

下一步：修复本人真实阶段/拒绝反馈和个人失败隔离；接通持续需求同帧记账，再检查真实多订单、多期经营。正式GUI/CSV、季度/半年、因果对照和人工review仍未完成。

离线副本重开已实际恢复到854/371001ms（HTTP=0）。权威经营/治理报告共59条，其中拒绝为NOT_AUTHORIZED 19、NOT_ELIGIBLE_SEAT 4、UNKNOWN_MEETING 2；包括call103前那次召集的BEGIN权限拒绝。拒绝明细另存deliberation-20261115/deliberation-v2-business-rejections.json。这与角色收到的FINISHED表述直接矛盾，修复应接真实报告而非猜测模型想法。


## 2026-11-16：v3 真实试跑、持久化断点与修复切片

冻结源码 eeaf720；请求模型 deepseek-flash；52 人，HTTP 上限500、墙钟20分钟、目标60实验日、1小时推进窗口。首次0bef95b入口因窗口参数错误在HTTP前失败，保留原目录；修正后独立重开，没有重置旧实验累计用量。

实际478次请求/478原回执，供应方声明输入2,889,093、输出33,223 token，费用 UNKNOWN。技术失败决定22次（包括复查时点不在未来），不能计入零故障主行为比较。实际7场会议、4份材料、12次到会、7张选票；制造新材一项任命决议ADOPTED，但effectiveAt=1200001早于实际实施完成1202000，拒绝INVALID_TIMELINE。经理任职、授权预算、经营合同均为0。整体验收仍不通过。

运行停在 WORLD_FAULT、position2001、time465632000，无物理在途请求。旧日志2,397,066,556bytes；最后一帧解压16,765,502bytes，距16,777,216上限仅11,714bytes。其完整状态含12,775,118bytes决定历史，每次提交重复写入。原失败异常文本没有暴露（live.phase=FAULT、fault=null）；后续实际runtime因同类累计状态增长明确复现16,810,768bytes超限，并保留确认前缀。不能把相关尺寸单独当原命令异常栈证据。

原日志SHA256：1125a81ac9af90020774dd1a9cdc977bd811c741087c1b4ca961f03658378bdf。独立副本完整恢复通过：position2001/time465632000，HTTP0，291363ms。所有原call/reply与配置/提示存于 deliberation-20261115/deliberation-v3-raw.json.gz（SHA25653ac223b23ef8a692d5ff35a2ec851a68d8550c0b1a128361020ac859abf0d74），摘要为deliberation-v3-summary.json。完整日志保留在项目logs/operating-cycle/live-accountable-v3-eeaf720下（相对于本隔离clone），不改写失败档。

083eda6增加显式state-storage=delta-v1：每帧保留原领域事件，状态仅保存有基准/结果摘要的增量，16MiB上限不变；已实现19,524,043bytes实际runtime状态的完整重开，168帧最大290,846bytes。该夹具使用长命令标识，不能把591715bytes压缩结果外推为真实模型存储比。恢复与追加21.791s；CPU仍随累计状态增长。compact分支显式不支持，超限检查点明确拒绝。切片独立审查进行中，不代表季度或经营通过。

旧eeaf720完整Maven2271项通过，但verify进程在断线后未完成所有步骤；补跑冻结类控制台8秒：49成交/136订单/7排名，CSV复算通过。JavaFX实际启动标记存在；本机自动审批拒绝javaw窗口操作，因此没有视觉验收通过结论。新网页/探针6617624的8项隔离回归及独立审查通过；仍待最终完整联合验证。

### 真实失败轨迹的离线差量核对（2026-11-16）

首次核对因环境断开止于1500帧，保留中断日志；重新运行最终退出0。以同一份原始journal（SHA-256 1125a81ac9af90020774dd1a9cdc977bd811c741087c1b4ca961f03658378bdf）逐帧进行差量编码和重建，2001个确认帧状态值全部一致，命令/回报及其他领域事件不变。原文件2,397,066,556字节；派生codec-only.frames为7,237,401字节，最大解码差量提交214,098字节，耗时766,284毫秒，HTTP调用0。

这是对真实轨迹的codec等价/大小核验，未把旧档迁移为新运行，也没有产生新的经营行动。新runtime的同帧恢复/篡改/故障行为仍以独立Task6和Task5证据为准；CPU与恢复耗时继续随累计状态增长，不能据此声称无界扩展。结果与 helper 源码见 deliberation-20261115/deliberation-v3-codec-benchmark.json 和 ActualTraceDelta.java.txt；原大日志仍留项目logs。


### v4 真实经营小试与恢复失败（2026-11-16，冻结75f17f4）

显式PLANNING_OPERATING（deliberation-v4、delta-v1、external-demand-v1），DeepSeek Flash请求别名；上限900 HTTP、30分钟墙钟、目标60模拟日。实际503次HTTP及503份原回执，声明输入3,717,978/输出40,817 token，费用UNKNOWN。退出时物理在途0，位置2075，模拟时间5,105,000ms（约1.42小时，不能冒充60日完成）；墙钟903,440ms。

真实执行：水电与制造结构两项经理任命IMPLEMENTED，各5张有效选票；水电5次DISPATCH_FLOW均被接受。水电报告共65条，其中60条SETTLE属于系统物理结算，只能计5次经理经营行动。所有公司授权预算0；三制造合同、采购、批次和应收均0。请求中的36次到会、10次材料、10次任命提案、10次投票、11次实施、19次召集、104次阅读、298次WAIT和5次调度均保留；请求计数不替代实际业务结果。整体仍未通过。

停止于ROLE_DISPATCH_REJECTED：水电经理输入33370字符/37758字节，超过冻结字符上限32768（字节上限60000）；没有向提供方发送超限请求。最后接受的call483输入31126字符，其中hydroOperations.reports有56条、13473字符，随后全量结算历史继续增长。Host将此单人INPUT_TOO_LARGE当全局停止。此证据支持下一步显式有界历史投影与个人预检失败隔离，不提高限额、删原账或补业务动作。

独立新JVM只读恢复失败：同一冻结类、原journal副本SHA256 a671a4a4dab3e453481d2111fee0c3323b26e8f096cd4ba41faadc27aa36b724，HTTP0，213410ms后exit1；历史决定live:1253:1:governance:hydro:act:hydro-manager的输入不能逐字重生成。正在定位精确差异；尚未宣称日志可恢复。原journal及首次失败日志不修改。

原回执归档deliberation-20261115/deliberation-v4-raw.json.gz，SHA256 88cbf3f3a0a8c1bd75d3049f8b9507c40c8df4de6682323f515886f76a181e4e；摘要和恢复失败单据同目录。完整日志位于logs/operating-cycle/live-planning-operating-v4-75f17f4。经理当前报价/供应/时延补充为新manager-v1切片，独审中；未混入本轮冻结试验。


### 6f61bb5：原v4恢复切片完成，经营验收仍失败

真正根因是持久化对象键规范排序与角色报告数组的live提交序不同，delta-v1及composed-v2全快照均受影响。首次修复按position统一排序，虽然18项通过且原2075帧可恢复，却被独审用原75程序实际“写报告→关闭→重开→才决定”的合法旧档反例拦下。随后仅补历史input验证又漏了expectedOpen原命令重演，完整33项中10失败，原大档恢复亦失败；这些候选、失败和一次核验自有进程后中断的审计全部保留，不能抹掉成一次通过。

最终只在新openInformedOperating标明operating-report-order=position-v1，直接严格重生成；其他旧入口保持原live投影。旧canonical历史仅允许以留存input的taskId顺序提示表示：报告唯一集合/数量/内容从权威状态核验，内容和非报告原文不复制旧值；排列还须符合某次旧重开的键排序前缀与后续提交尾部，同批所有人物须共享可能cut。原本已按time/command固定排序的制造数组直接strict，不参加该hint。输入验证与expectedOpen两处同规处理后，从可信input重算摘要/计划/预算，完整批次equals保持。旧map顺序和实际重开点未单独持久化，因此未独立证明跨不同批次的排列轨迹；这是明确限制，不包装成全历史顺序真实性证明。后续真实实验只用新position-v1，不将旧档无声升级继续经营。

最终1c冻结类（对应源码6f61bb5）独立恢复至position2075/time5105000，exit0，605446ms，HTTP0。另独立核对503份原call的inputText、tools、plan、attempt身份与规范摘要，以及503份原reply正文和声明用量，全部一致；原journal和恢复副本SHA均a671a4a4dab3e453481d2111fee0c3323b26e8f096cd4ba41faadc27aa36b724。恢复状态全部11字段与保留成功审计深比较相等，但audit.json表示字节不同，未声称该JSON文件逐字一致。独审据实际收据关闭Task9恢复切片，0 Critical/Important/Minor。

证据汇总：deliberation-20261115/deliberation-v4-recovery-final.json；独立原输入核对helper源码audit-input-provenance.cjs.txt。固定原75旧续写样本及来源校验在src/test/resources/operating-replay-75f17f4。最终受影响19项与新增路径边界9项分别运行，不拿测试数量当世界验收。当前真实模型经营结果仍是两任命/5次水电调度/制造订单0；Task10正在修有界输入与个人超限隔离，新的模型试跑尚未开始。


### 7424fab：有界报告与个人输入隔离切片完成

仅新 openInformedOperating 显式启用 bounded-history-v1 / local-input-v1，五类经理的已授权报告取最新12条、最新6条非SETTLE及最新6条拒绝的并集，最多24条；准确显示保留和遗漏，完整账本及当前资金/合同/物理状态不删。原32768字符/60000字节上限不变。只有typed INPUT_TOO_LARGE局部阻断，其他人物和未来事件继续；预算、世界故障及配置变化仍全局停止，在途回执正常drain/记账。旧模式保持原合同。

独审发现免费尺寸缓存误用付费wakeFacts：真实会议时间197999→198000，输入32769→32755字符而业务facts相同，旧缓存仍阻断。已改用权威position+time，公开Host回归验证实际fit后免费解除、随后首次派发，重复查询不增调用或账。配置变更仍补发的竞态亦以真实在途latch修复，原回执仍一次记账。

39个不同针对性/兼容用例分次通过；原38项运行37通过/1旧manifest fixture错误，fixture适配后5项通过，缓存修复后1项及其余Host7项分别通过，不能称一次39/39。独审最终0 Critical/Important/Minor；所有RED、失败夹具和反例保留在logs/operating-cycle/task10-*。资料目录或合同等其他输入仍可能超限，不承诺普遍无限输入。

这只是机制修复：尚无新模式真实模型结果，原真实结果仍是两项任命、5次水电调度、制造订单0。接续Task11新Web/Probe入口和提供方模型元数据记录；冻结审查后另开新档试跑，不将原v4无声升级。整体验收仍失败。
