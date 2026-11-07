# M4g.1 信念、来源可靠性与慢习惯纯核：Root 施工合同

依赖 PR34（2f206aa）。总体基线 §7–§10 和六层记忆专题是方向；belief-preparation/prepare-v2.md 只是准备材料，冲突以本合同为准。本段先新增独立纯核，随后必须接真实阅读/任务/恢复/决定输入及 Web，不能以纯核完成冒称认知基线完成。

## 写权与边界

真实 Hermes f270fe 仅在 `logs/implementation-20261106-continued/belief-core-author/` 私有镜像新增 `world/BeliefModel.java` 与 `world/BeliefModelTest.java`；其余90生产源及主树只读。无 Maven、git、Temp、Key/真实供应商、用户窗口或全局memory写入。私有javac/JUnit及日志允许。Root独占主树/文档与最终验收。

只用现有Java/Jackson依赖，不改InformationModel、DTO、RunManifest、WorldRuntime或任何旧记录。此纯核不联网、不读墙钟、不写日志、不读余额/利润/股价/未来账本。后续桥将有真实能力闸和持久化，不用当前未接线能力伪装完成。

## 唯一语义

1. 首批预测只有公司 `ORDERS_CONFIRMED`（COUNT/long），比较为 AT_LEAST 或 AT_MOST。每条在形成时冻结公司、指标、阈值、**精确 targetAsOf**、验证发布者人物ID（后续桥绑定公司章程主席）。不设一日容差；晚一毫秒的账本也不能验证早期预测。形成时 targetAsOf 必须在未来，且不超过正 maxHorizonMillis，减法检查避免加法溢出。
2. 自拟预测与明确采纳结构化来源预测分开。来源预测带稳定claimId、作者、目标全部字段及所在docId/version；采纳必须逐字段相同、该版本确实由该作者发布，且本人当前仍可回忆、已完成阅读。不能凭自由正文/引用过记者推断采纳。自己的预测不更改其他来源可靠性；预测失败不代表诚信错误。本段没有伪造常量integrity字段。
3. 证据限1..3条真实本人ReadingRef，首次引用和本人序号按现有规则（包括取消/拒绝阅读占用的序号）核对；不接受他人、目录/未完成/取消/拒绝/未来阅读或伪造index。形成要求这些doc/version在 `CognitionRecall.recall` 的当前 selected 中。回忆结果由纯核调用现有函数计算，不接受调用方伪造selected。判断可保留≤500字符本人rationale；它不是外部证据，绝不据此补回外部正文。
4. 纯核接收可信适配层提供的结构化声明表（每个doc/version最多一条 SourceClaim 或 FactClaim），并核对实际InformationModel中的已发布版本、发布者、时间。不得将声明当成旧文档原生已实现字段；文档到声明的持久绑定、事实值从公司账派生属于下一段桥的强制责任。准备稿的自由文本解析方案不采用。声明输入的可信边界写清楚，不声称纯核能自行证明账本数值。
5. FactClaim含唯一factId、公司/指标、精确asOf、long值、验证发布者、生成时冻结位置、doc/version、可空supersedesFactId。事实发布时间不得早于asOf。只有传入 `InformationModel.State` 内**本次真实COMPLETED阅读**（本人、doc/version、closedAt==now，发布不晚于完成）才能评估；未读/仅目录/不同公司/指标/验证来源/时点全部不更新。核验文档不得是同条判断的形成证据，禁止自证。
6. 无匹配材料保持OPEN，不写UNVERIFIABLE、不把超期当错。首次匹配追加HIT/MISS Evaluation；同一次重试/重读同fact不重复，后来的相反普通声明不能改判。明确更正必须本人实际读到，且supersedesFactId精确指向该判断当前已评的factId、同目标/验证发布者，才追加新的Evaluation取代有效结果；旧评估永久保留。未收到更正者不变，未读到中间更正则不能跳过链覆写已有结果。首次读到更正且尚未评估可直接用其公开完整事实评估。更正能力仅纯核测试，不声称当前公司已支持财报重述。
7. 同一人物不能重复采纳同一sourceClaimId刷学习；同一人物同目标/比较/阈值的重复自拟判断也拒绝。有限容量只拒新增，不删除旧判断/评估。幂等：同beliefId同形成内容返回原状态，不同内容拒绝；形成内容含时点与证据。评估按稳定belief顺序处理，集合顺序不影响结果。

## 来源偏信与慢习惯（显式实验规则）

- 来源可靠性仅针对本人明确采纳的来源预测。每个唯一采纳claim的**最新有效评估**贡献 HIT +1 / MISS -1；competence=clamp(500 + step * 净贡献,0,1000)，中间long避免溢出。更正重算有效贡献，不能在已夹紧分数上做错误逆运算。重复阅读不能再加一次。来源标识来自实际发布者，不来自模型任填。
- 形成时confidence=clamp(500 + weight*(competence-500)/500,0,1000)，整数除法；自拟为500。冻结形成值，后来信任变化不改过去判断。人物投影只给LOW(<400)/MID(400..699)/HIGH(>=700)，管理员内部状态可有精确数值。来源可靠性必须能进入下一轮投影，不只是存字段。
- 公开来源分组只取本人实际读过版本的**公开来源描述**的精确非空值；内部sourceIds及其hash完全不读。叫“公开声明来源组”，不声称不同任意字符串证明真实独立。空白来源保守不能增加独立组；改隐藏映射不改变学习。已形成判断冻结这些可见分组；不为了更新习惯回读当前未来文档。
- 两种习惯 VERIFY_BEFORE_ACT、SEEK_SECOND_SOURCE，等级0..3。仅本人成熟MISS并实际读到核验材料支持前者，其中公开来源组数≤1的支持后者。去重单位为唯一判断（已拒同一claim重复）；HIT不提供支持。每K条不同支持经历才一级；在正windowMillis内，以各判断最新有效评估的首次成熟时间为年龄（更正不延长年龄）。level=min(3,floor(有效支持数/K))。本段采用**最近成熟经历窗口**，支持到期或更正可以降低等级；不谎称只升不降或永久人格。
- 只在一次有新有效评估/更正的真实阅读批次重算习惯；单纯查询、无关阅读、时钟变化不更新状态。每次等级或支持集合改变追加HabitRevision，含时点、kind、旧/新等级、支持beliefId清单，历史不删。旧判断被更正后不继续支撑习惯。支持证据完整可追溯，不能只保存最后K项并丢审计。
- Policy只放实际使用参数：maxBeliefsPerPerson(1..10000)、maxOpenBeliefs(1..maxBeliefs)、maxHorizonMillis>0、trustStep(1..1000)、confidenceWeight(0..500)、habitThreshold(2..100)、habitWindowMillis>0。证据3条、rationale500、习惯3级是合同常量，不另建无用配置。默认试验建议200/8/30日/20/200/3/30日，纯核不偷偷设置现实默认；所有参数由调用方显式传入。

## 数据与投影要求

不可变State保存beliefs/evaluations/habitRevisions；来源分数与当前习惯可从审计历史确定性派生，避免两份不同步状态。结构化声明表是外部可信输入，不在本段复制整份信息状态。新record列表防御复制，输入不被修改，公开API中文JavaDoc。

提供本人有界投影（调用者显式maxItems 1..100）：OPEN优先，再按形成时间降序、id升序；超额有truncated标记。只含本人判断、主观rationale、本人scope证据引用、最新结果和band、本人相关来源band及习惯级别。不得含Policy/数值confidence/数值competence/内部映射/global READ编号/外部文档全文/他人；历史已读资格不等于可获得外部正文。不要在普通RoleView上添加字段，本段只返回新的纯核投影类型。

## 验收与后续

- [x] 正常InformationModel发布→startRead→completeRead形成证据；初始失败测试与可执行语义反例分别保存，编译缺类不算语义RED。
- [x] 公司/精确时点±1/验证发布者/未成熟/未读/取消/拒绝/未来/他人/假index/遗忘均不得错误学习；重读完成可重新形成。
- [x] 明确采纳字段全匹配；自拟不归咎来源；唯一claim重复不能刷分；上下夹紧后更正重算正确；预测输赢不更新诚信/资产。
- [x] 无关阅读零变化；事实重复读取幂等；未读更正不更新，真实读到更正追加历史；普通相反事实不覆写；更正链跳步拒绝改已评结果。
- [x] K-1/K/多组、窗口精确边界、支持到期、重复证据、同公开组/空组、HIT、更正撤销支持；HabitRevision保存支持和历史。
- [x] 相同输入确定性、不可变/容量/溢出/非法Policy、本人投影不泄露/预算排序；隐藏映射改变结果不变。
- [ ] Root独立反例与正式全套/CSV/GUI/JavaDoc、文档、草稿PR/CI。本段未接世界/模型/Web；下一段必须完成这些接线与真实恢复验收。

完成后给Root准确API、文件hash、测试实际数/失败证据、边界和接线差异；不可擅自修改本合同或实现未授权生产文件。

## 冻结前澄清（Root 与独立合同审阅，2026-11-07）

1. `fact.asOfMillis == belief.targetAsOfMillis` 必须精确相等；AT_LEAST/AT_MOST都包含等号。它已是正文方向，现明确运算符。
2. 形成证据只接受首次Observation的本人引用，重读只刷新可回忆性；同doc/version重复证据拒绝。证据按docId/version/index规范排序后保存及幂等比较，顺序不是新内容。
3. 核验docId不得等于任一形成证据docId，跨版本也禁止自证。本段公开声明来源组仅取这1..3条形成证据的公开描述，其他已读/核验材料不加入。
4. SourceClaim必须携带verificationPublisherPartyId，采纳与它精确匹配。作者等于采纳人时拒绝；自拟预测走自拟入口，不绕成采纳自己的来源。
5. 同一人物同company/metric/targetAsOf/comparator/threshold的预测只允许一条，涵盖自拟与采纳；换claimId、作者、证据、核验人都不能再计一条。同一sourceClaimId仍只允许采纳一次。不同发布者转载不能增加成熟支持或来源贡献；人物可以把不同证据放进同一次形成的1..3条证据，而非重复创建。
6. 同beliefId的幂等核对先于新建容量、回忆/证据可用性与业务闸；同形成时点/规范化内容返回原状态，不被自己或后来新增条目占满的容量挡住。不同形成时点是不同内容，不能沿用旧ID。
7. evaluate另收**当前确认位置**非负long，FactClaim.frozenPosition不得超过它（违反返回零更新）；这只是必要上界，真实账本派生/文档持久绑定仍由可信桥负责，纯核不声称能验证账本值。now必须是该批模拟任务到期/实际closedAt，不能传墙钟或处理延迟时刻。
8. 窗口精确定义为 `0 <= now - belief第一条Evaluation的maturedAt <= windowMillis`，含两端；更正不得延长该年龄。confidence用Java整数除法**向零截断**，中间long。HIGH>=700，MID>=400且<700，LOW<400。
9. habitLevel及project返回最近已提交HabitRevision的状态，不能按查询时间自行降级；无版本的初始级别0。人物投影带对应习惯上次更新时点，明确是上次实际核验的结果。只有有新有效评估的阅读批次才根据窗口重算并追加版本；公开habitSupport如保留须明确是“按给定时间预计算支持”，不是已提交级别。
10. 对某人物project/habitLevel的now早于该人物任何已提交形成/评估/习惯时点时拒绝，历史回看需传真实旧State；不能拿未来完整State生成伪历史。排序精确为(openRank,formedAt降序,id升序)，truncated只裁尾部。
11. 更正评估记录实际读到的factId及supersedesEvaluationId，完整事实链在可信声明表保留；首次只读到末端更正不伪造中间阅读。冻结验证者人物是本段明确实验限制：主席换人后的新来源不会自动获准替旧预测核验，需后续治理来源授权版本机制；不得静默把其余任何人物当权威。
12. 新形成/新评估也不能早于该人物已提交的形成、评估或习惯时点，否则抛IllegalArgumentException；不能以较晚学到的可靠性回填更早判断。已存在同ID同内容的形成重试仍先走幂等返回；已处理同事实的评估重读可以保持零变化，不追加过去事件。

独审报告含“13处”计数笔误，实际列H1–H5、M1–M6、L1–L4共15条；其中H1是已明确方向的运算符澄清，H4不替代可信适配层证据。Root没有采用跨组织泛化或隐式更正授权。
