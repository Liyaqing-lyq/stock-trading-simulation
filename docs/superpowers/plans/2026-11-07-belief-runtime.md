# M4g.2 信念运行桥与 Web：实施合同

依赖 M4g.1 `2026-11-07-belief-core.md`，最终纯核候选2的 BeliefModel e124ccf8…b145；候选3只修 SceneRoutes 短时原子替换拒绝。目标是真实发布→阅读→形成判断→截止事实→读到核验→下一轮模型输入和管理员 Web 审计。不是把纯核 DTO 放到页面就称运行闭环。

## 版本与旧档兼容

- 新明确档案 `cognition-belief-experiment-v1`，schema12，在schema11基础上增加 `belief:1`，仍live模型配置。旧1..11清单、输入、工具列表、JSON与行为保持。
- InformationRun.Config新增nullable beliefPolicy，保留旧1/2参构造且null键不序列化。belief必须和recall同时启用，开局冻结两种参数/工具输入版本；不匹配在写文件前拒绝。
- 新 BeliefRun 保存 BeliefModel.State、结构化SourceClaim/FactClaim、报告捕获/发布状态和审计报告，不复制资金或整份InformationModel。WorldState新增nullable字段，开局/每条next/恢复验证完整传递。
- 主日志 append+force 之后才发布候选；恢复必须从前一帧重算本批增量并对整个认知子集严格比较，不能只校验非空或最终总量。原记录未知额外字段仍拒绝。

## 角色动作与真实观察截止

- 新工具 `belief.assert`、`forecast.publish`、`company.reportOrders`，仅新档案真正注册、严格DTO并接入决定的planAction。所有操作有正模拟工时，可信会话给人物；参数禁止person/sourcePublisher/verificationPublisher/factValue/score。
- 新档案 WorldRoleSession 冻结 observedPosition（nullable Long，旧4参构造保留且旧档案为null）。新认知任务payload持久化 observedPosition/observedAt；旧任务JSON不增字段。调度和完成均确认观察帧真实存在、人物/任职一致，不能拿当前状态按time过滤冒充历史。
- 可用日志中真实 WORLD_STATE 帧解析精准position，拒绝不存在/未来位置。新动作只能使用实际观察帧里已经读完且selected的证据/声明；同毫秒后续提交读到的资料不可填补旧观察。完成时再次要求仍可回忆，角色不能通过自行拼会话切换runtime/人物。公开构造属于宿主可信边界，LLM只见严格业务DTO。
- `belief.assert`：业务草稿包括本人beliefId、company、ORDERS_CONFIRMED、AT_LEAST/AT_MOST、非负long threshold、未来targetAsOf、可空adoptedClaimId、1..3本人ReadingRef、rationale。verificationPublisher由观察帧对应公司真实章程主席确定，不能模型任填。形成时点是动作完成期，targetAsOf仍须未来；以完成期当前已知可靠性冻结confidence（不回填观察期旧判断），证据必须同时通过观察和完成两次资格。完全重复命令依现有幂等不重复形成。
- `forecast.publish`：公开结构化来源预测，绑定真正发布人物、固定docId/version与全部预测字段、冻结验证者。草稿含claimId/docId/title/body/publicSourceDescription/readDurationMillis/company/comparison/threshold/targetAsOf；指标固定ORDERS_CONFIRMED。正文只是声明，不能用文本解析权威事实。文档与claim同批持久化且每doc/version仅一种claim。普通注册人物可发表，不虚设证券任职要求。
- `company.reportOrders`：仅当前真实章程主席可请求该公司未来精确asOf公告；观察与捕获/发布都核验主席身份，失败留下报告、不能偷偷换核验者。程序生成fact值，模型不提供value/sourceID。每(company,metric,asOf,verificationPublisher)只一个初始事实；不实现无权威来源的账本重述。

## 程序事实的精确时点

- 在asOf的OBSERVATION阶段捕获，确保同毫秒所有COMPLETION（含新订单合同确认）已经执行；冻结company/asOf/该批position/实际contracts数量。当前制造模型合同台账中的已确认合同按公司过滤；实施前核对真实Contract类型/状态，不能把询价/失败请求算入。指标COUNT为合同个数，不是销售额或商品件数。
- 下一毫秒DELIVERY发布捕获值，不能再次读当前ledger；Long.MAX_VALUE无法发布须入口拒绝。捕获与发布是可恢复的两个真实持久任务，稳定子任务ID、幂等、取消/失败语义明确。发布生成固定规范正文和FactClaim，读者仍必须实际读完才学习。不得由管理员直接注入任意FactClaim值。
- 阅读完成在同一个候选批次用新的InformationModel和本次ClosedReading.closedAt调用evaluate，再提交认知变化。重读不能误用首次Observation.completedAt。取消、拒绝、未读、仅目录不学习。恢复重算来源完全来自已确认历史。

## 输入、审计与 Web

- 新RoleView nullable cognition字段，保留旧11/12参构造及null省略。本人bounded belief projection（最多20）+来源band/习惯+当前selected文档上的可见claims；隐藏sourceIds、内部READ编号、数值confidence/competence、Policy、冻结position不进入角色输入。
- BeliefView缺少的adoptedClaimId/sourcePartyId/verificationPublisher补在桥投影元数据，限选中的本人belief，不能借此恢复遗忘外部正文。Fact可见内容只来自本次selected的已读版本。
- 新输入和工具合同显式版本，现有输入超字节预算拒绝必须继续，不能为容纳新字段放宽budget。下一轮决定的inputText应实际包含本人信念和最新学习；其他人不变。旧schema11 inputText逐字保持。
- 管理员审计API返回同一已确认cut的policy、belief/evaluation/habit历史、来源分数、claims和报告，查询不改变状态或调用提供方。Root负责Host/HTTP/UI接入，no-store/auth/严格参数/切身份清空/迟到响应保护。Web显示预测、实际核验阅读、主观来源可靠性、实验习惯，说明不是客观诚信。
- Root在Web新档案明确启用schema12，复用旧档案时不偷偷升级；保留现有schema11入口用于旧运行恢复。

## 验收

真实WorldRuntime正常入口测试：发布/阅读/本人形成；未来/他人/未读/旧观察截止/同毫秒后读/遗忘证据拒绝；程序事实含同毫秒合同、不含下一毫秒合同；不读不学习、实际读后HIT/MISS、重读不重复、来源变化影响下一次输入；两次MISS不同目标达到配置K习惯支持；无权限报告/注入value/错publisher拒绝；主日志恢复/不同JVM/损坏帧拒绝/提交失败无可见半状态；旧1..11测试与旧schema11已编译class不重编兼容。新声明/事实的数值不能以纯核夹具当端到端账本证据。

正式全套/CSV/GUI/JavaDoc、独立Hermes复核、Root HTTP/浏览器验证、评分与运行文档、AI草稿PR/实际CI。五key不新增真实调用，回环提供方测试足够；总体仍需媒体、多公司银行、图表与长跑，不混称完成。

## 当前写权

作者f270fe仅 `logs/implementation-20261106-continued/belief-runtime-author/` 私有镜像。可改该镜像中的 RunManifest/InformationRun/WorldRuntime/WorldRoleSession/RoleView/PendingTask/DecisionModel/RoleTradingView，新增BeliefRun及必要专用DTO类和新测试；改动只为上述桥，纯核BeliefModel只读。先保存真实RED再实现；旧测试不得删弱。禁止主树/Maven/target/git/Temp/keys/供应商/窗口/全局memory。可私有javac与JUnit。Root拥有主树/Maven/Web和最终验收。
