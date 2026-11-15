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
