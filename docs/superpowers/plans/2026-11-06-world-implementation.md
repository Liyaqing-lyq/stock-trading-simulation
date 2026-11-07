# 股票世界持续实施 Plan

> **For agentic workers:** 按 `superpowers:executing-plans` 逐任务执行；Codex 管理计划、接口和最终验收，真实 Hermes 按明确文件范围承担实现或独立审计。每个任务先阅读本 Plan 与总体设计基线，使用复选框跟踪。

**Goal:** 在保留课程系统的前提下，按总体设计基线逐步实现可审计、可恢复的多公司、多岗位股票世界。

**Architecture:** 单进程 Java 权威状态和唯一世界变更调度器；模型推理与观察可并行，状态转换串行持久提交。旧课程连续竞价模式保留独立入口；新世界先验证时间、身份、账目、恢复，再接公司—媒体—机构—交易—图表链。

**Tech Stack:** 现有 Maven Wrapper、Java release 17（本机 JDK 21）、JavaFX、JUnit 5；沿用现有依赖，未因规划增加框架或服务。

**Spec:** [总体设计基线 v0.1](../../design/总体设计基线.md)，2026-11-05；用户于 2026-11-06 明确要求据此建 Plan、与 Hermes 从现状核验开始，并利用今晚尽可能实施。已有方向直接继承，未定实验参数不得冒称现实规则。

## 全局约束

当前增量（2026-11-07 12:22）：当前codex/world-performance-history，基底f16cc9c4。M4h.1最终独审199检查/22旧角色及真正旧12新续旧读通过，草稿[PR37](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/37)实际CI42337472698成功，待人工review。M6b[账户表现历史](2026-11-07-performance-history.md)已接主树，1280标准、CSV50/119/7、两组Node/真实GUI、71专项和旧calculate122输出全通过；真实浏览器验证18确认点及资本流/回撤/范围与身份隔离。exec72563正独审105文件候选；最后两行JavaDoc修改需确保重新生成。M4h.2[媒体运行桥](2026-11-07-media-runtime.md)作者405ace exec81622仍在私有后端实施，Root负责Web/合并/验收。本机持续Web18080仍belief candidate3/PID51516，尚未升级M6b；五Key已加载且场景关闭，0新增供应商调用。总体未完成，继续接收性能独审、交付该增量并推进媒体运行桥。

- 金额 BigDecimal（金额 2 位、成本价 4 位），数量 long；锁顺序引擎锁 → 账户锁，模型、I/O、广播不能持有撮合锁。
- Java 保留账目、交易、时间、权限和动作裁决；首轮单进程，不引入微服务或任意代码热加载。
- 课程模式与扩展规则分开；成交按被动方价格；课程收益继续以初始权益为基准。
- 角色不能凭文本获得权限；人物、组织、财产主体、账户、任职分开；复制配置不复制资产或经历。
- 事件原文和账务不可覆盖，纠错用更正事件；CSV 不是恢复源；失败不能伪造成功。
- 首轮精度 1 模拟毫秒，固定四阶段；新消息最早下一时间步。公平顺序来自运行种子并可记录。
- 用户可见首版须含所启用公司的董事会、独立四岗、遗忘/偏信/习惯和真实图表来源；基础模块通过不代表首版完成。
- 每阶段：专项测试 + `bash scripts/verify.sh --with-gui` + 评分映射 + 真实记录；代码通过 PR、`[AI-Generated]`、至少一名人工实质 review 后才能合入。
- 保留启动时所有未提交改动。草稿依赖链 `codex/world-implementation` → `codex/world-securities-ledger` → `codex/world-matching` → `codex/world-exchange` → `codex/world-manufacturing` → `codex/world-governance` → `codex/world-role-information` → `codex/world-role-reading` → `codex/world-role-trading` → `codex/world-role-trading-runtime` → `codex/world-decisions` → `codex/world-decision-runtime` → `codex/world-decision-audit` → `codex/world-dividend-core` → `codex/world-valuation-performance` → `codex/world-dividend-authorization` → `codex/world-dividend-financial-seams` → 当前 `codex/world-dividend-runtime`，最初起点 `bd0d3aa`。不擅自合入主分支，不提交配置秘密，不调用无上限真实模型实验。

## Review Focus

1. 恢复遇到半条记录、提交后未应用、重复命令：只能完整旧版/新版，不能重付；M1 以截断、失败注入和重启测试验证（V11/V12）。
2. 同时到期、网络乱序、跨人物新消息：固定阶段与公平顺序，不回跳，不让迟到结果写入（M1/M4，V10）。
3. 昨日库存卖出后当日回补、自成交、费用部分成交：不得重复卖合格库存或刷成交量（M0/M2，V05/V06）。
4. 离任、匿名来源、上帝切个人：查询、检索、导出、回看都使用相同可信权限（M2/M4/M6，V07/V08/V14）。
5. 资本流、分红与缺价：跨页同一时点净值，分红一次计入；未完成指标不填零（M2/M3/M6，V04/V15）。

## 维护规则与接续入口

这是唯一实施总台账。阶段状态采用：待细化 / 可执行 / 执行中 / 本地已验收 / 待人工 review / 已合入 / 阻断。子任务在此保留编号；确有必要的子计划从此链接，不另建竞争的进度表。每完成一个可验收单元就更新状态、证据、下一步；失败保留在记录中。后续任务进入施工前才固定文件与接口。

接续顺序：`AGENTS.md` → `docs/agent-memory/PROJECT_MEMORY.md` → 基线 → 本 Plan 当前任务/执行记录 → 对应代码与最新日志。可变状态用现测覆盖；不因会话更换重做已通过步骤。Hermes 会话编号和交接写入下方执行记录。

## 阶段与依赖

| 阶段 | 产出与范围 | 依赖 | 基线验收覆盖 | 当前状态 |
| --- | --- | --- | --- | --- |
| M0 现状核验/加固 | 课程全套实跑；静态风险；必要回归修复 | 无 | V06/V15 的课程部分 | 本地已验收，待 PR 人工 review |
| M1 世界运行基础 | 模拟时序/阶段、运行清单、唯一提交、幂等事件日志、恢复与分支边界 | M0 | V10/V11/V12 | 现金运行子集本地验收；完整世界/实验分支仍待实施 |
| M2 身份/开局/交易账 | 人物/组织/账户/任职；开局股份与现金清单；托管映射；版本化规则；T+1 批次/自成交 | M1 | V01/V02/V05/V06/V14 | M2.1–2.4b可靠交易核本地验收；任职/人物投影及schema7角色交易网关本地验收，银行托管待实施 |
| M3 公司经营竖切 | 制造公司、外部客户/供应商、合同履约、现金与报表、董事会、现金分红、表现核算 | M2 | V04/V15/V16/V17 | 制造及治理桥、D0/D1/D2/D3a草稿PR19–22实际CI通过，744标准，均待人工review。D3b最终816标准、212独立检查及真实Hermes31+6独审通过，草稿PR23及实际CI42253286290通过。D3c最终858标准/123独立/最终独审通过，草稿PR24及实际CI42264571970成功；取消/重新公告接续 |
| M4 角色与信息竖切 | 独立四岗机构、两类媒体、普通投资者；阅读/传播/权限、六层记忆与偏信/习惯、模型屏障与预算 | M1/M2/M3 | V07/V08/V09/V10/V13/V14 | schema6阅读桥及PR13 CI通过；M4c纯核23专项/组合385标准及PR14 CI通过；schema7运行桥38专项/组合423标准及GUI/CSV、61正常入口、三JVM、旧6兼容与最终hash独审通过，PR15 CI通过；M4d纯核58专项/481标准与最终hash独审及PR16 CI通过，schema8桥55+runner12专项与549标准本地通过，三JVM/旧7兼容通过，最终独审42断言及GUI/CSV/JavaDoc通过，PR17代码CI通过，M4e573项审计补项及PR18代码CI通过，待人工review |
| M5 差异公司与银行 | 工程回款、银行完整实验规则、直接/组合敞口迁移、其余十类公司及八类媒体逐项配置 | M3/M4 | V01/V02/V03/V13/V16 | 待细化 |
| M6 真实观察工作台 | 先接增量观察；白色市场/股票/公司/账户/人物机构/新闻/世界页面；同快照、权限裁剪、图表与历史回放 | M1 起增量，完整依赖 M2–M5 | V12/V14/V15 | 本机白色Web、五Key/五场景、四组真实业务详情及模型单轮已实现并实测，完整股票图表/历史工作台仍待实施；原file审计页用户稍后手验 |
| M7 行为/压力与交付 | 无 LLM 故障夹具、固定预算小试、多次独立实验、失败率/分布、课程报告/JavaDoc/PR 人工评审 | 所有启用能力 | V17/V18 及全矩阵回归 | 待细化 |

阶段覆盖的是责任，不表示整条 Vxx 已通过。多公司、银行、媒体、认知目标保留；今晚按依赖推进，不用虚构数据或跳过检查换取页面数量。

## 当前执行任务

### 当前接续

- 当前新分支 `codex/world-belief-core`，base `1cd53eb`。回忆运行桥PR34代码CI42314108569、文档CI42314416430均已成功。M4g.1唯一合同为[信念、来源可靠性与慢习惯纯核](2026-11-07-belief-core.md)，含冻结前澄清。真实Hermes f270fe原exec6410已由Root仅停止核验过身份的私有worker以交付具体反例，现exec8522续同session修复；仅写belief-core-author。d39b13 exec29091独审合同完成，15条澄清意见由Root裁定，报告无动态测试结论。
- Root初始provisional-3独立8测试2通过/6失败；作者修复后provisional-5为12测试11通过/1失败，Root追加新建/新评估时点检查（2行）后provisional-6共13通过；保留provisional-7-red以同13测试证实旧代码的2项回填过去失败。现已冻结candidate1：91源/5资源/1Root测试，97哈希；新增BeliefModel SHA f6e44b06…92fa1，旧90源未动。Root接回该新类与13正式回归，Maven专项08:04:45通过；作者仍修其原测试，正式全套与最终合并未验收。d39b13 exec63793正在独立自编/动态审查候选，禁止读作者和Root测试。
- 旧schema11客户端已编译并真实创建档案10/30，class hash d5c41e01…b5346，后续禁止重编；新桥兼容尚未验收。M6 Web图表只读准备由d39b13完成在web-history-preparation/prepare.md，尚未授权生产实现。信念本段随后必须接运行/Web，不作为完整认知完成。
- 当前实施分支 `codex/world-cognition-runtime`，base `e885cf9cb3651c652908017bb0e94ce33f2e2eec`。施工单：[M4f回忆运行桥与Web观察](2026-11-07-cognition-runtime.md)。真实Hermes作者f270fe私有七源已交；Root接回冻结candidate1并修复旧cash档Optional审计接口，主树及candidate2已通过1117标准、CSV51/132/7、真实GUI、JavaDoc07:35:26。
- Root新增5项包括新HTTP真实404失败与旧cash审计真实异常失败的修复；另20三JVM、旧DTO未重编指纹不变、旧schema10旧写→新续→旧读通过。真实浏览器初读/遗忘/重读中/完成恢复、跨人物选择、身份清空及同档重启均通过，最终截图与细节在cognition-runtime-root/browser-acceptance.md。新真实供应商调用仍为0。
- 独审 `20261107_045400_d39b13` 已完成：候选1自编后135项动态和13项Web静态通过；最终candidate3自编、99文件哈希无差异，新增10项动态和12项静态通过。未动态覆盖research正文旁路（该项由作者正常工具测试及变异反例覆盖），未做浏览器（由Root完成）。本阶段草稿PR34及代码CI42314108569已成功；PR33代码/文档CI42310013886/42310471565已成功。
- 后续认知准备：同作者f270fe的belief-preparation首稿已被Root要求修订；必须匹配公司/指标/目标时点与实际读到的权威事实，不把预测错误当诚信错误，不用隐藏来源改变人物学习。修订稿仅准备，无生产写权。Root独占主树/Maven；总体基线、人工review与后续媒体/多公司银行/完整图表长跑继续。

### 上一阶段验收（回忆纯核）

- 当前分支 `codex/world-cognition-recall`，base `876e485`。Web PR32代码b2fb23d/文档876e485的实际CI42308822586/42309278593均success；1085标准、74独审、浏览器/预算/故障验收完成，仍待人工review。
- Root拥有全部主树/Maven。新增CognitionRecall及14测试，原89Java未改；1099标准、CSV45成交119订单7排名、真实GUI、JavaDoc07:06:15通过。Root正常入口14检查先2个真实重读失败再全部通过；真实Hermes d39b13独立90源自编95检查通过。
- 最终candidate2仅修纯核类注释（实际最近阅读时间），可执行文本与candidate1相同；源码SHA ffa6de348219ec396c3102e2845039ebb860214c510b2e0356121cce595fc1b2。原经历/ClosedReading保留、按真实重读完成恢复可得性；不引入第二份状态或启用默认遗忘。
- 回忆尚未接Runtime/角色input/Web，不能当六层认知完成。运行桥新上下文exec81750仅cognition-runtime-preparation只读调查，60轮预算已到，等待报告后续接；原作者ed3b0f exec61639已退出。新schema/旧JSON/旧二进制和全文旁路合同待Root核准后实施。
- Root已预编译旧InformationRun.Config/RoleView/RoleTradingView/inputText客户端，合并JSON指纹828217b8cf517410de92937772abe20a80067f4cc3ec52d3a318e4d3dbf553f4；运行桥验收只换类，不重编此客户端。
- 真实Key仍仅既有key-1一次51token，其余4槽未测，本轮0新增供应商请求。信念/来源偏信/成熟习惯、媒体、多公司银行、完整图表长跑继续，不自动合并。

施工单：[M4f已有经历有界回忆](2026-11-07-cognition-recall.md)。

以下保留各阶段任务与原始执行记录。

### M0.1 当前项目基线核验（Codex + Hermes，只读分工）

**Files:** 阅读 `engine/MatchingEngine.java`、`account/Account.java`、`sim/SimulationRunner.java`、`gui/AuctionUi.java`、`scripts/verify.sh`、现有测试；输出 `logs/implementation-20261106/` 和 `docs/05-运行与验证记录.md`。

- [x] 读取项目指令、项目记忆、设计基线、已有改动与分支；保留既有文件。
- [x] 启动真实 Hermes 有界背景恢复/静态审计；禁止与 Codex 同时跑共享 data 的仿真。
- [x] Codex 运行 `bash scripts/verify.sh --with-gui`，记录各项真实输出和边界。
- [x] 核对 Hermes 发现的具体风险；只有经现有代码/失败测试确认的缺陷进入 M0.2。
- [x] 更新评分表核验说明与运行记录；新增世界能力不提前打勾。

### M0.2 撮合阻断风险修复（Hermes 单写入者）

**Files:** 修改 `engine/MatchingEngine.java`、`model/OrderResult.java`；创建 `engine/SelfTradePreventionTest.java`、`engine/RejectedOrderListenerTest.java`（测试目录）。共享文档由 Codex 写。

**Interfaces:** 保留 `submit(OrderRequest)`；增加 `OrderResult.accepted(Order, List<Trade>, String reason)` 重载，原重载仍返回 null 原因。受理后取消剩余量的业务原因可读取，区别整单入场前拒绝。

- [x] 失败测试：自身买卖不能成交、买卖两侧均释放新单剩余冻结、非交叉同账户可挂簿；第三方先成交再遇自有挂单时保留合法成交并取消新单剩余量，不跳过自有更优价去吃更差价；总现金/股数守恒、原挂单仍有效（V06）。
- [x] 实现取消主动单剩余量并返回 `SELF_TRADE_PREVENTED` 原因；已合法成交不回滚，自有被动挂单不变，不产生自成交量。
- [x] 失败测试：拒单监听者内让另一线程读取引擎并等待结果；必须可完成，证明通知不持有引擎锁（使用短超时和 finally 清理线程）。
- [x] 将拒单通知移至锁外，保持现有受理/拒绝/行情副作用；仅必要结构调整。
- [x] Hermes 独占 Maven 运行定向测试和全套，保存 RED/GREEN 日志与交接；Codex 之后独立复验和 GUI/CSV 总验收。AI 审查不替代人工 review。

### M0.3 业务取消的可见回报（Hermes；M0.2 的验收补齐）

**Files:** `engine/EngineListener.java`、`engine/MatchingEngine.java`、`io/CsvHistoryRecorder.java`、`agent/ai/LlmTradingAgent.java`，新增 `io/CsvHistoryRecorderTest.java`。

**Interfaces:** 新增 `EngineListener.onOrderAccepted(OrderResult)` 默认转发旧 `(Order,List<Trade>)`；旧监听者继续收到一次通知，CSV 复用既有原因列。新自成交规则不能只在调用栈返回原因，却在导出中无从查找。

- [x] 引擎 → CSV 集成测试先红：自成交取消原因落盘、普通挂单/成交原因空、旧监听者不丢失/不重复。
- [x] 最小实现并验证专项及全套；不扩展撤单统计口径。

### M1.1 确定性世界时序（Codex）

**Files:** 创建 `src/main/java/edu/cufe/auction/world/WorldScheduler.java`、`src/test/java/edu/cufe/auction/world/WorldSchedulerTest.java`。独立于旧 `SimulationRunner`，不把缺少持久化的旧撮合冒充新世界；后续 M1.2/M2 接入唯一写调度。

**Interfaces:** `WorldScheduler(long seed)`；`Phase { RULES, DELIVERY, COMPLETION, OBSERVATION }`；`schedule(String eventId, long timeMillis, Phase phase, Runnable action)`；`scheduleDelivery(String eventId, long channelDelayMillis, Runnable action)`；`runUntil(long inclusiveMillis)`；`pause()` / `resume()`；`nowMillis()` / `pendingCount()` / `isPaused()`。一次推进同步执行，其他线程不能重入推进；同刻不同阶段按枚举顺序，同阶段按 seed+eventId 的稳定顺序；eventId 在运行内唯一且不依赖线程先后生成。

- [x] 先写失败测试：`phasesRunInDeclaredOrder`；`sameSeedIgnoresInsertionOrder`；`deliveryCreatedByCompletionStartsNextMillisecond`；`rejectsPastAndCompletedPhase`；`pauseRetainsPendingWork`；`failurePausesWithoutReplayingSideEffects`；`rejectsRecursiveAdvancementAndDuplicateIds`。断言 10ms 完成动作所发消息最早 11ms，延迟 30ms 时为 40ms；失败不推进到目标时点或继续执行其他动作。
- [x] 运行 `mvnw.cmd -Dtest=WorldSchedulerTest test`，确认因新能力缺失而失败。
- [x] 实现上述最小类，中文 Javadoc；校验非负时间、重复 ID、溢出、过去时点和已结束阶段；动作异常暂停，不隐式重试可能已有副作用的动作。
- [x] 同一测试命令全绿；再运行全套，不改变课程运行入口。
- [x] 独立复核时间语义并记录限制：Runnable 队列本步尚不能序列化，M1.2 必须替换/绑定持久事件才能宣称恢复。

### M1.2 不可变批次/幂等/可靠记录（Codex）

**Files:** 新增 `world/WorldEvent.java`、`world/WorldCommit.java`、`world/WorldJournal.java` 及 `world/WorldJournalTest.java`（测试目录）。复用已有 Jackson 序列化，JDK FileChannel 单文件追加/force 和独占文件锁，不增加数据库依赖。

**Interfaces:** `WorldEvent(String eventId, String type, String payload)`；`WorldCommit(int schemaVersion, long position, String runId, String commandId, String commandContent, String result, List<WorldEvent> events)`；`WorldJournal(Path file, String runId)` implements AutoCloseable；`commit(String commandId, String commandContent, String result, List<WorldEvent> events)` 返回已持久 `WorldCommit`；`commits()` 返回不可变历史。命令内容精确相等用于去重，上层负责提供规范化命令；事件 payload/result 为不可变文本，具体领域 schema 由模块校验。

- [x] 先失败测试：同命令只产生一批并返回原回报、异内容拒绝、重开继续追加；每批结果和事件同时恢复且不调用模型；重复事件 ID 拒绝。
- [x] 先失败测试：末条短写恢复到上一完整批次；完整记录校验和损坏明确拒绝；运行 ID 不匹配拒绝；第二写入者拒绝；关闭后提交拒绝；超大记录拒绝且原记录仍可恢复。
- [x] 实现长度 + UTF-8 JSON + SHA-256 校验和的批次帧（单帧最大 16 MiB），force 后才返回成功。仅不完整末尾可截断；完整损坏不当作未确认尾部忽略。I/O 失败后锁定写入，必须关闭/恢复，不能继续确认。
- [x] 专项与全套验证；记录本步只完成可靠记录层，尚未接入旧撮合状态，V11 的经济原子性还需 M1.3/M2 实例验证。

不把 JSON 文件写出等同完整恢复。后续候选状态在唯一写调度中形成，持久确认后才发布；提交后应用前退出从日志恢复。现有 CSV 入口继续用于课程导出，不作为世界日志、不在恢复路径新建截断。

### M1.3 运行清单和可恢复世界快照（依赖 M1.1/M1.2）

明确记录运行 ID/父分支、规则/资料/模块/模型配置版本、初始资源、事件位置和随机状态；保存只发生在完整提交边界。恢复读取历史结果，不重新调用模型；缺版本明确拒绝继续。仅有调度或日志分别通过不能宣称本项完成。

#### M1.3a 当前施工单：现金子集的可恢复运行（Hermes）

**范围裁决：** 先把已启用现金能力接成一个可恢复运行，不建尚无第二实现的 WorldModule/CashModule/TaskHandler 抽象，不升级无必要的 WorldCommit 存储格式。沿用单一 WorldJournal，每个提交保存完整不可变子集状态；当前小规模以正确性优先，未宣称大规模存储性能。分支创建、证券/公司/银行/角色恢复仍在 M1.3 后续单元，不能打整阶段完成。

**Files:** 新增 `world/RunManifest.java`、`world/PendingTask.java`、`world/WorldRuntime.java`、测试 `world/WorldRuntimeTest.java`；必要修改 `CashLedger.java` 提取可复用纯计算、`WorldScheduler.java` 复用公平排序键、`app/WorldFoundationDemo.java` 改用可恢复运行。其他文件不改，共享文档由 Codex 管理。

**Interfaces:**
- `RunManifest(runId, seed, rulesVersion, moduleVersions, dataVersions, modelConfigVersion)` 为不可变 record。当前只接受 `rulesVersion=cash-world-v1`、模块 `{cash:1,scheduler:1}`、`modelConfigVersion=none`；资料版本表可空。运行清单、精确开局现金清单在第一个提交内绑定，任何不一致拒绝续跑。
- `PendingTask(taskId, dueTimeMillis, phase, kind, payload)` 是可序列化数据，当前只有 `CASH_TRANSFER_V1`（COMPLETION）与 `TRANSFER_NOTICE_V1`（DELIVERY）；未知 kind 拒绝。模型推理对象/任意 Runnable 不进入运行状态。
- `WorldRuntime(Path file, RunManifest manifest, List<OpeningAccount> openings)`；`scheduleTransfer(String commandId, String taskId, long dueTimeMillis, String actorOwnerId, String fromId, String toId, BigDecimal amount, long receiptDelayMillis)`；`advanceTo(long inclusiveMillis)`；`snapshot()`；`isPaused()`；`close()`。快照包含完整提交位置、时钟/阶段、现金账户、待办、已投递回报、已用任务 ID（只读管理视图）。
- Runtime 是唯一日志所有者。`CashLedger` 只提取 package-private 的纯候选计算供 Runtime 使用，保留现有独立子账入口/测试，Runtime 不再打开第二个子账日志。公平键复用 `WorldScheduler` 的同一 seed/id 算法。

**提交规则：**
- 排程本身持久提交。新转账到期必须晚于当前时点；任务 ID 运行内不复用。外部命令与内部任务/时钟命令采用隔离命名空间，同命令重发先查原回报，不能因当前时间已过而再排一次。
- 一任务一批：纯计算形成现金效果 + 完成标记 + 后续投递待办 + 全量子集状态 → 同一个 WorldCommit 持久确认 → 一次发布。先 peek 到期待办，成功后才从新状态移除。未确认不得丢任务。
- 所有回报最早下一模拟毫秒，满足渠道延迟；业务拒绝也完成任务并保留原因。时钟到目标但无任务也形成完整边界，重开不倒退到最后一笔转账时间。固定阶段和种子顺序不得由文件/网络线程改变。
- 失败保留旧快照、待办与资金，Runtime 暂停且报错；恢复只解码并验证已记录状态，不调用动作处理或模型。每批状态校验位置、时间、非负余额、总额、账户权属不变、待办唯一性及已启用版本。不冒充硬件断电恢复保证。

**验收步骤：**
- [x] RED：`manifestMismatchRejectsResume`（规则/模块/开局不同均拒绝）、`pendingTransferSurvivesRestartWithoutExecution`（重开时不付款）、`cashEffectAndFollowUpShareCommit`（资金与新回报待办同一位置）。
- [x] RED：`duplicateScheduleDoesNotPayTwiceAfterRestart`、`commitFailureLeavesCashAndPendingUnchanged`（实际中断 I/O、暂停）、`truncatedCompletionKeepsOriginalPendingTask`（截到未确认完成帧时旧资金与待办保留）。
- [x] RED：`clockAndNoticeSurviveRestart`、`sameSeedTaskOrderIgnoresScheduleInsertionOrder`、`unsupportedCapabilityAndPastTimeRejectedWithoutCommit`、`snapshotIsImmutableAndWriterIsUnique`。
- [x] 最小实现，保留原 82 项；精确记录 RED/GREEN、必要失败修复、全套结果和未完成项。更新可恢复调试入口，实际两次进程验证待办恢复和不重付。
- [x] Codex 独立检查实现与日志；专项 + 标准 `verify.sh --with-gui`；新鲜审查覆盖新增 Runtime，修复重要项后再提交到草稿 PR。

### M2.1 现金权利子账的提交—恢复竖切（Codex；只覆盖基础子集）

**Files:** 新增 `world/CashLedger.java`、测试目录 `world/CashLedgerTest.java`。本步不迁移旧 Account、不声明已有银行/托管完整对账；用于以真实双边资金变更验证 M1.2 的持久协议。

**Interfaces:** `CashLedger(Path file, String runId, List<OpeningAccount> openings)`；嵌套不可变 `OpeningAccount(accountId, ownerId, cash, source)` / `CashAccount(ownerId, cash, source)` / `Snapshot(position, accounts)` / `TransferResult(accepted, reason, position)`；`transfer(commandId, actorOwnerId, fromAccountId, toAccountId, BigDecimal amount)`；`snapshot()` 返回仅供管理端的完整只读视图；`close()`。调用方负责把可信身份映射到财产主体，不能让未来客户端自报 actorOwnerId。

- [x] 先失败测试：明确历史禀赋来源开局；1000 元从 A 转 100 给 B 后 900+100=1000；重发同命令不重付；重开不重发初始资金；改变开局清单拒绝。
- [x] 先失败测试：余额不足/越权/未知账户不产生单边转移；拒绝也保存结果，后来余额改变后重试仍返回原拒绝；同 ID 异内容拒绝；负数/零/不足分的金额拒绝。
- [x] 实现唯一同步写入者形成不可变候选 → journal 持久确认 → 发布新快照。持久失败不发布；快照读者只见完整版本。金额固定两位，绝不 double 记账。
- [x] 恢复按已记录批次重建并校验结果；夹具直接在 journal 写入一条完整转账批次而不更新 ledger，模拟提交后退出，重开应恢复 900/100，重试仍不重付。关闭后提交失败必须保持快照不变。
- [x] 专项/全套，独立审计。通过仅证明现金子账，不把它扩大为完整 V02/V11 或全部世界检查点。

### M2.2 显式身份、资产归属与开局股份登记（下一施工单，依赖 M1.3a 验收）

**目标边界：** 先实现不可变开局登记，不启用证券交易、T+1、基金申赎或公司经营。持仓数量只有开局分配表一份权威；不另存一份 Issuer.holdings。新 Agent 配置不创建现金或股份；新实验的明确历史禀赋与同一运行复制对象区分。

**Files:** 新增 `world/OpeningRegistry.java`（数据与验证，嵌套 record/enum）与 `world/OpeningRegistryTest.java`；修改 `RunManifest.java`、`WorldRuntime.java` 与 `WorldRuntimeTest.java` 接入。保留旧现金子集的存储/恢复与原入口，不碰课程 `Account`/`seedPosition`/配置。

**数据契约：**
- `Party(partyId, kind, name)`，kind=PERSON/ORGANIZATION。人物/组织不包含资金；产品财产主体不冒充人物或雇主。
- `PropertyOwner(ownerId, kind, partyId)`，kind=PERSON/ORGANIZATION/PRODUCT/AGGREGATE。前两类必须引用同类 Party；PRODUCT/AGGREGATE 的 partyId 为空，独立持有财产；产品管理授权后续单独实现，本步不启用基金经营模板。
- `Security(symbol, issuerOrganizationId, issuedShares, initialReferencePrice)`：发行人必须是组织，股数 long >0，参考估值 BigDecimal 正数精确到分。
- `ShareAllocation(accountId, symbol, quantity, costPrice, classification)`：quantity long >0，成本精确 4 位；本轮开局成本等于参考估值，历史成本导入另做。classification=FLOAT/LOCKED/TREASURY；库存股账户须归发行组织。尚无解锁/转让动作，不能凭分类自动交易。
- `OpeningRegistry(parties, owners, securities, allocations)`：集合不可变、按稳定键规范化，拒绝各表重复主键；分配主键为 `(accountId, symbol, classification)`，允许同账户同证券同时有流通/锁定等分类，不允许同类重复。用结构化键而非分隔符拼串。`validateAgainst(List<OpeningAccount>)` 使用既有现金开局账户的 accountId/ownerId，不复制现金表。所有账户属主可解析，所有持股账户/证券已登记，逐证券用 Math.addExact 核对所有分类持有人之和等于已发行股数。额外现金或股票须在新开局明确列示，运行中无隐式增发。

**Runtime 接入：**
- 保留 `cash-world-v1 + {cash:1,scheduler:1}` 原样；新增明确组合 `cash-registry-world-v1 + {cash:1,scheduler:1,registry:1}`。继续 model=none，未知或缺依赖模块拒绝，不做任意组合框架。
- 新构造重载 `(Path, RunManifest, List<OpeningAccount>, OpeningRegistry)`。旧构造用于现金模式；新档案没有 registry / 旧档案传了 registry 均在写盘前拒绝。
- Registry 是此单元不变的开局状态，同现金禀赋/清单写在第一批 `RUN_MANIFEST_V1` 内容中，可额外记录 `OPENING_REGISTRY_V1` 事件；其余每批继续同一条世界日志。WorldState 的现金 schema v1 不为尚无持仓变动的登记强行升级，旧现金日志继续可读可运行；注册表在首批完整绑定并在续跑严格比对，不在旁边再开文件。
- `openingRegistry()` 返回 `Optional<OpeningRegistry>` 管理视图（明确为**开局分配**，不宣称实时持仓）。股票变动将在 M2.3 引入新的已声明状态/规则版本；不提前伪造交易能力。

**验收步骤：**
- [x] RED：发行 1000，A600/B300/外部100 通过；改 999 或1001 拒绝且不创建日志；所有分类都计入总股份，按可卖量漏算锁定股份必须失败。
- [x] RED：未知财产主体、未知持有人账户/发行人/证券、重复 symbol、负数/溢出数量、成本/精度错误、非发行组织库存股，均在开局持久提交前拒绝。
- [x] RED：人物 Alice 与雇主 Org、产品财产主体 Product、账户 P 不混同；以人物/岗位名称自报不获得 Product 资金权（只证明本步映射，不宣称任职授权已实现）。
- [x] RED：增加新人物/财产主体及零余额账户但没有股权分配，总发行与原持有人股份不变；重复原底仓给新账户导致超发必须拒绝。
- [x] RED：同清单重开不发钱/不发股；有效登记内容变化拒绝续跑；旧 cash-only 日志照常继续且 Optional.empty；旧新模式互开拒绝，不静默装最新模块。
- [x] 最小实现、专项/全套、独立审查与标准验收；记录这是开局登记能力，不能升级 V05/T+1、撮合、银行或整个 M2 状态。

**M2.3 进入施工前的固定口径：** 剩余持仓含冻结，冻结是其子集，不能用 remaining+frozen 再算一份库存；可卖量为 `Σ(remaining−frozen)` 且 `eligibleFromDay <= 当前实验交易日`。当日买入资格日=当前日+1。昨日100，今卖60/回补60后仅40合格，再冻结30仅10可用，撤单恢复40。本轮不以 UTC 自然日冒充真实交易日历，具体实验日规则在 M2.3 单元中版本化。

### M2.3 持仓批次、冻结与同批清算夹具（本地已验收）

**边界：** 实现有限库存和现金的清算夹具，先验证 V02/V05，不宣称已有订单簿、真实交易所档案、费用/价格限制、收益图或角色交易授权。只有管理端可排程系统清算夹具，model=none；未来角色工具不得直接调用此夹具制造成交。

**Files:** 新增 `world/SecuritiesLedger.java`、`world/SecuritiesLedgerTest.java`、`world/WorldRuntimeSecuritiesTest.java`；修改 `RunManifest.java`、`PendingTask.java`、`WorldRuntime.java` 接入。保留原 121 项合理断言和课程入口，现金记账仍复用 CashLedger.computeTransfer，不创建平行资金账。

**数据与纯计算契约：**
- `Lot(lotId, accountId, symbol, remaining, eligibleFromDay, acquiredAtMillis, acquiredPosition, costPrice, classification)`：数量 long，成本 BigDecimal 4 位；remaining 是未售总量，**包含冻结部分**，不再存第二份 frozen 数量。取得时间与排序序号分开：初始 acquiredAtMillis=0，稳定初始 lot ID/取得顺序可由规范化序号生成；acquiredPosition **只表示批次内 FIFO 顺序，不是全局 journal 提交位置**。新买入顺序须晚于全部现存批次（如 max+1），发生时间用实际完成时点，源任务由唯一 lot ID/报告关联。不能让新买入批次因提交号小于开局数组序号而排到旧批次前面。
- `LotQuantity(lotId, quantity)`；`SellReservation(reservationId, accountId, symbol, allocations)`：冻结唯一权威为这些批次分配，数量由 allocations 求和。完全成交/撤单移除活动预留，已用 reservationId 不复用。成交消耗预留，不可绕过批次资格。
- `ActionReport(taskId, commandId, kind, accepted, reason, timeMillis, position)`；`State(lots, reservations, usedReservationIds, reports)`：集合深度不可变，结果按 taskId 保存且旧值不可改写。reports 是管理审计结果，不是已向角色送达的消息。
- `opening(registry)`、`available(state, accountId, symbol, day)`、`reserve(...)`、`cancel(...)`、`fill(...)` 为无 I/O 的纯候选计算，Runtime 持久确认后才发布。FIFO 在合格 FLOAT 批次中按 acquiredPosition/lotId 消耗；LOCKED/TREASURY 计入发行总数但不能被本夹具卖出。
- 当前实验规则固定 `DAY_MILLIS=86_400_000`、`day=floor(模拟毫秒/DAY_MILLIS)`。这是从运行起点计算的实验日，不是 UTC 自然日或真实交易日历。初始 FLOAT day=0 可卖；当日买入 day+1 可卖。金额=BigDecimal价格×long数量，禁止 long/double 金额运算；数量累计 Math.addExact。

**唯一运行与版本：**
- 新档案 `securities-clearing-experiment-v1` 精确模块 `{cash:1,scheduler:1,registry:1,securities:1}`，仍 model=none、必须有 registry。原 cash/registry 两种档案原样支持。
- 新档案使用 WorldState schema 2，含完整 SecuritiesLedger.State；旧两档案保持 schema 1，新增的可空 securities 字段在 JSON 中必须省略（不能写 null 破坏旧读取器）。旧日志读取为空，继续写出的旧格式也保持。WorldCommit 帧格式不变；找不到模块版本明确拒绝。
- 管理 Snapshot 同截止位置增加 `Optional<SecuritiesLedger.State> securities`；openingRegistry 仍表示初始分配，不能伪装成交易后的持仓。
- 每个完整状态核对：各证券 Σremaining=已发行股份，所有账户/证券/批次引用有效，冻结分配合计≤对应 lot.remaining，无负数/溢出；现金总额沿既有守恒，账户权属不变。原子检查点包含现金+持仓+预留+待办+结果，不另开日志。

**排程接口（返回原 SCHEDULED 回报，到期执行才产生 ActionReport）：**
- `scheduleReserveSell(commandId, taskId, dueTimeMillis, actorOwnerId, accountId, symbol, reservationId, long quantity)`。
- `scheduleCancelSell(commandId, taskId, dueTimeMillis, actorOwnerId, reservationId)`。
- `scheduleFixtureFill(commandId, taskId, dueTimeMillis, reservationId, buyerAccountId, BigDecimal price, long quantity)`：仅受信的系统夹具，不是买方下单接口。成交双方必须有不同财产受益主体；同管理组织的不同产品主体不合并。
- 三类 PendingTask `SELL_RESERVE_V1/SELL_CANCEL_V1/FILL_SETTLE_V1` 均在 COMPLETION 执行，due>now，线程/暂停/幂等规则与现有排程一致。未知能力在提交前拒绝；原命令重试先取原回报，不因过去时点重复排程。
- 到期按最新状态复核。卖方预留不足、买方现金不足、同主体或无权动作形成明确业务拒绝，不改变资产；拒绝也保留报告，不能同原命令再次付款。冻结/撤销校验财产主体与账户，内部清算以明确系统夹具身份记载。
- fill 同一 WorldCommit 保存：任务完成 + 现金转移 + 卖方 lot/预留减少 + 买方新 lot + 报告 + 全量状态。先持久再发布。失败/短写时旧钱、旧股、旧预留和原待办都保留；恢复不重跑已确认动作。

**验收步骤：**
- [x] RED：纯批次资格（初始/当日买入/次实验日）、锁定和库存股不可卖、冻结不双算、超量/重复预留拒绝、FIFO与成本 4 位、取消只释放未成交量。
- [x] RED：发行 200，A/B 各100、现金各1000；A卖60给B，再由B的**旧底仓**卖60回A，A总量100但当日可卖40；再预留30可卖10、取消后40，次日100。不能让B把刚买到的60当日转卖。
- [x] RED：预留80、成交30后 remaining70/预留50/可用20，撤销后可用70；现金只转价格×30，各证券总数守恒；独立公司账户现金不因二级成交增加。
- [x] RED：同一受益主体两账户不能自成交、不同产品主体可独立结算；未知/越权、钱不足/股不足不留单边状态；同命令重发/重启后无重复付款或股份。
- [x] RED：实际中断 fill 提交与完成帧半条截断，恢复资金/持仓/冻结/待办一致，继续只完成一次；暂停后拒绝新排程。
- [x] RED：旧 cash 与旧 registry 运行继续可读/可推进且新证券接口明确未启用；Codex 用实际上一提交生成的文件做跨版本复验；同种子/不同入队顺序结果一致。
- [x] 最小实现、全套、独立审计、标准 GUI/CSV 入口与 JavaDoc；M2.3 只报清算/批次子集，订单撮合与交易授权继续后续施工。

### M2.4a 共享纯撮合计划（本地已验收）

**目的与边界：** 旧 MatchingEngine 直接修改账户/簿，不能放到可靠日志之前执行。先抽出唯一的无副作用撮合算法，旧入口通过它应用相同结果，下一单元世界订单也使用它；本步不宣称世界已经能下单。不改 Account、旧规则限制、GUI 或广播锁边界，不新增框架。

**Files:** 新增 `engine/MatchPlanner.java` 和对应测试；`OrderBook` 增加按对手侧价格/FIFO 顺序的不可变报价副本；仅修改 `MatchingEngine.match` 与不再使用的预算辅助方法。现有所有课程断言保留。由 Hermes 独占上述源文件和 Maven，Codex 管理文档与最终验收。

**契约：** 输入主动方向/类型/限价/数量/受益主体、按价格时间顺序的被动报价（orderId、受益主体、价格、剩余量），买方可用预算等于本单冻结加可用现金。输出不可变成交计划（makerId、被动价、数量）、剩余量、停止原因及触发自成交的 makerId。纯计算不改 Order/Account/簿、不读系统时钟、不做 I/O。预算按实际成交金额递减，先按剩余量封顶再转 long，防止巨大 BigDecimal 预算转 long 溢出。

- [x] RED：买卖两侧价格优先、同价 FIFO、被动价成交、不交叉停止、市价扫簿、部分数量；输入与输出不随旧可变 Order 后续变化。
- [x] RED：买方现金1000、市价数量100，卖10×50与11×100，先按卖一冻结1000可受理，实际50+45、花995、剩余5；现金1200请求200按卖一需2000，应由原受理层拒绝，不能错误宣称截断成交。
- [x] RED：前一第三方成交后遇同主体更优挂单，停止并取消主动剩余，保留前一成交和被动自有单；同价不跳队。
- [x] 将旧引擎委托纯计划，应用成交仍在原锁内，广播仍锁外；自成交原因文本/限价挂簿/市价撤销行为保持。
- [x] 专项与原课程断言全绿、独立核对、标准 GUI/CSV + JavaDoc，更新记录和评分映射；仅在本步完成后细化 M2.4b 世界订单状态/买方现金冻结。

### M2.4b 世界订单与可靠撮合（本地已验收）

**边界：** 仅受信管理夹具的确定性交易核，model=none。新档案 `securities-matching-experiment-v1` 精确启用 `{cash:1,scheduler:1,registry:1,securities:1,matching:1}`。支持限价/市价、1分钱精度、T+1实验日、主动剩余自成交取消、撤单/收盘；不支持涨跌停、手数限制、真实时段/节假日、费用、停牌、出金或角色授权。这些规则不能配置为“已启用”后静默忽略；课程模式的既有价格限制保留。

**Files:** 新增领域纯模块 `world/WorldExchange.java`（数据与候选转换，不持有可变 legacy Order/Account）及专项测试、`WorldRuntimeMatchingTest`；修改 RunManifest/PendingTask/WorldRuntime 接入。复用唯一 `MatchPlanner`、CashLedger.computeTransfer、SecuritiesLedger 的 reserve/cancel/fill，不调用会先改账的 legacy submit，不复制证券或现金计算。必要时新增正式可复跑 `app/WorldExchangeDemo`，不用仅存在测试内的“演示”。

**唯一权威与候选状态：**
- Exchange 保存不可变订单表、明确订单状态、逐笔成交与下一 order/sequence/trade 计数、是否收盘；订单含账户、证券、方向/类型、限价、原数量/成交数量、入簿序号。簿由活动限价订单按价格/sequence派生，不再保存可漂移的第二份队列。序号/ID必须持久，不从进程原子计数或CSV恢复。
- **买方冻结唯一来自活动买限价单的 `limitPrice × remaining`**，按账户求和；不再另存一份总余额/冻结数量。现金总余额仍只在 CashLedger，`可用 = 总余额 − 全部活动买单冻结`。市价单初始按当时卖一×全量校验冻结资格，成交后未成交全部撤销，最终不留冻结。此规则保留现有课程实验语义。
- 卖方冻结仍唯一来自 SecuritiesLedger 的具体批次分配。每个活动卖单必须有一一对应预留，数量等于订单剩余；撤单/收盘只释放剩余，全部成交移除活动预留，历史已用标识不复用。
- 新买受理先检查 freeCash 足够初始冻结，卖单先检查并形成合格批次预留；计划仅计算。逐笔结算使用被动价，借助既有现金/证券纯函数计算候选；一批保存订单/成交/现金/批次/预留/报告/待办，durable 后才统一发布。任何内部不一致技术暂停，业务拒绝有结果但不改变资产。
- 同一财产 ownerId 不能自成交，不能仅比较账户ID；不同产品财产owner独立。费用/现金市值/收益率不在本单元伪造。

**排程与资产保护：**
- `scheduleOrder(commandId, taskId, due, actorOwnerId, accountId, symbol, side, type, price, long quantity)`；市价price为空，限价正数精确2位，due>now。排程只回原SCHEDULED，到期才按最新簿/资格受理，分配订单/序号；同命令异内容拒绝。
- `scheduleCancelOrder(commandId, taskId, due, actorOwnerId, long orderId)`；未知/非属主/终态形成明确拒绝，不改挂单；`scheduleCloseMarket(commandId,taskId,due)` 为受信系统动作，撤全簿并释放冻结，收盘后申报拒绝（此前已排但未执行的也拒绝），保留历史成交。
- 新档案下普通 `scheduleTransfer` 到期必须按**可用现金**复核，不能花掉买单冻结；任何后续经营付款也必须遵守同一可用口径。旧证券的 reserve/cancel/fixtureFill 在 matching 档案明确禁用，不能绕过订单簿或替订单取消预留。
- 原cash/registry/clearing三个profile行为和序列化保持；新WorldState用schema3、末尾可空exchange字段在旧档案省略，管理Snapshot增加Optional交易状态，冻结与可用从同一快照派生。旧档案恢复/续写后仍能由旧读取器打开。

**验收：**
- [x] RED：卖10×100遇买限10.20×100，100@10成交、只花1000、零剩余冻结；买方新批次10.0000、当日不能卖（买方须先有1020满足限价全量冻结）。
- [x] RED：1000现金市价100扫10×50/11×100，50+45成交995、剩5取消，现金5；1200现金市价200初始冻结2000不足整单拒绝。卖主动吃买限价取被动买价。
- [x] RED：同价A/B/C重启后D进队尾，ID/sequence/累计量恢复；多证券隔离，价优不跳队；同主体不同账户自成交取消主动剩余，保留此前第三方成交。
- [x] RED：活动买单冻结阻止普通转账；部分成交后剩余冻结、卖批次与订单剩余一致；越权撤单失败，合法撤单/收盘全部释放且恢复一致。
- [x] RED：已有买卖挂单，真正成交帧写失败/半帧，钱/股/簿/冻结/待办一起保持旧版；恢复仅成交一次。同命令重发、同刻插入乱序由seed决定、暂停拒绝新命令。
- [x] RED：档案缺模块/未知能力拒绝，新旧隔离；用03cebea真实旧程序生成的证券存档续写再旧程序读回（现有 `legacy-securities-v2/actual-old-run` 停在time10/pos4/frozen60/pending fill1）。
- [x] 同一可比订单序列分别进课程与世界，成交价/数量/主动剩余一致（世界T+1差异明确）；正式演示进程退出前留挂单，第二进程恢复成交，第三进程重发无重复；独立审查、标准GUI/CSV、JavaDoc、评分映射/真实记录更新。

### M3a 制造经营实验核（纯核及Runtime桥本地已验收）

**采纳范围：** 提案 `hermes-m3-manufacturing-proposal.md` v2 仅作素材；以本段为后续细化依据。先做有限制造/收付与资产负债核对，再做 M3b 董事会，用户首版必须两者俱全。不得把未接董事会的实验核称为完整公司。全部参数为合成实验，不是原型公司财务数据。

- 组合交易与制造的新档案 `stock-company-experiment-v1`，在 matching 精确模块上加 manufacturing:1，model=none；schema4末尾可空manufacturing字段，前三种旧格式及schema3继续读写。不能做一个孤立制造运行就宣称公司—股市链已经连通。
- 首个纯领域模块拟 `world/ManufacturingModel.java`（不可变状态、固定规则与候选结果，必要时再拆数据文件）；复用既有现金权威与世界提交，避免插件框架、第二本现金账。开局公司/证券/对手账户绑定并在首批验证历史设备与权益来源。
- 公司现金1000、设备净值1000、无负债、初始权益2000；供应商100、客户2000、人力/场地0、投资者500，世界现金3600。公司证券可用200股/参考10的有限开局，全部归已登记投资者，不自动复制底仓。
- 实验日沿用86400000模拟毫秒，期间固定30实验日。供应商原料50/件、日容量20；到货滞后1日，首档案**现金交货、到货原料与付款同批**。先原料入库/应付、再异步付款会偷换为信用采购，v2提案A2不照搬。付不起则到货失败、不增库存、不生应付，保留失败证据。预付、分期、供应商违约/退货暂不启用。
- 生产10的倍数且不超过20件、同时最多1批，原料1件/成品1件、现金人工30/件，投产消耗原料+付人工+形成WIP同批，1日后WIP转成品。存货数量与账面总额为权威，出库分配实际成本，清零时结清尾差，不用目录价覆盖历史成本。
- 客户每期间需求50、单价上限130、预算1500；签约占需求/预算、同期间内交付，不提前认收入。交付按实物/成本同批确认全额收入、应收与COGS，15日后按真实**可用现金**回款；允许收款跨期间，不再次占预算。未收标逾期，之后可明确重试，已收不得再次收取。预收/合同负债、有息债务、多币种、跨期间交付条款在首档案拒绝，不能配置后忽略。
- 每期间固定费用250，期末能付部分按可用现金付，余数形成对人力/场地账户的应付；折旧100且不超过设备剩余。期末是强制持久规则任务，按期间唯一关闭，不能换commandId重复记费、漏记现金不足费用或任意提前“月结”。发生/付款分开；付清应付只减既有负债，不再记费用。
- 公司经营现金账户不允许普通转账或证券下单绕过经营分录；首档案只允许公司一个现金账户，拒绝遗漏在报表外的同owner第二账户（包括零余额）及公司持有证券的未建模资产/库存股，不能让它们绕道经营账。系统域动作以公司/对手的既定合同授权结算，未来资本行动另立完整分录。客户/对手的交易冻结仍受 matching 可用现金约束。市场价/市值不改公司权益。
- 每批检查公司 `现金 + 原料/WIP/成品账面 + 应收 + 设备净值 = 应付 + 初始权益 + 累计利润`，世界Σ现金3600，物量入出/在制互转与成本守恒；采购、生产、交付、回款、费用各有唯一事件/结果，恢复不重做。

**手算验收主线：** 期间0第17日采购10件，18日到货付500，随后投产付300/材料500→WIP800；19日完工→FG800；20日信用交付10×120→AR1200/收入1200/COGS800、利润400但现金200。额外采购5件现金交货需250、付不起失败且资产不变。30日期末费用250（付200/应付50）+折旧100→累计利润50、公司资产AR1200+设备900=2100、负债50、权益2050；35日收1200再付应付50→现金1150+设备900=权益2050。每一步所有账户现金合计3600。正式施工时先用这些逐步RED断言，再接待办/恢复/标准验收。

**M3b 不可遗漏：** 5个有效席位绑定真实登记人物，关联回避后仍须原章程法定人数3，普通事项至少3赞成；剩余席位不足3延期，不把回避人数减少变成1人自动通过。预算/任免/分红等授权分类和考核权重先保存，决议与执行分开。金额阈值须适配本小夹具，不能照搬百万级提案使治理从不触发。具体接口在M3a稳定后锁定。

M3a.2 Runtime桥的固定接口、强制期间任务与验收见[制造经营接入施工单](2026-11-06-manufacturing-runtime.md)。只维护本总Plan的状态，不另建竞争台账。

M3b.1董事会纯核在既有隔离树已交付，具体规则和失败场景见[董事会纯核施工单](2026-11-06-board-core.md)。独立审查及修复后56项新增测试、隔离全套242项通过；它暂不接世界，不把纯规则通过当作公司治理闭环已完成。

M3b.2的预算同批经营、持久会议与固定截止复盘按[治理接入施工单](2026-11-06-board-runtime.md)执行。M4a.1只做独立于Runtime的[任职权限纯核](2026-11-06-role-access-core.md)，可在隔离树先行；不把权限元数据当作四个模型上下文/真实阅读已经完成。

M4b.1在隔离树执行[资料版本与实际阅读纯核](2026-11-06-information-core.md)，复用RoleAccess；M6a的[离线审计观察页](2026-11-06-audit-observer.md)只在忽略目录准备，尚未进入正式代码分支，浏览器验收状态单独保留。

M4a.2/M4b.2按[人物阅读运行施工单](2026-11-06-role-reading-runtime.md)接入schema6，草稿[PR #13](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/13)依赖#12，待人工review。当前按[四岗交易运行施工单](2026-11-06-role-trading-runtime.md)推进M4c，落地研究报告、经理目标、风控审核、经理确认、执行员子单及人物会话。六层认知和媒体保留独立验收。用户已回复稍后手动查看离线审计页，M6继续待人工界面验收。

## 进入后续阶段前必须冻结的规格

- M2：开局资产负债、发行股份/所有者、托管/银行映射、授权对象与规则档案；实验 T+1/限价/费率，不冒称全部真实交易所制度。
- M3：制造模板可执行状态表、数量单位、收入/回款/成本、董事会章程与指标、分红批次/尾差、TWR 截点。
- M4：阅读/传播/工时、任职权限和离任、记忆更新的可见证据、模型屏障/尝试代次/预算；先用固定决定夹具验证。
- M5：银行可计算档案与完整性检查；未实现指标拒绝启用，不以 0 默认通过；其他公司逐模板通过机制验收。
- M6：进程内观察快照契约先行；若选择 Web 再写请求/响应规范并加载 API 工程技能，不先建空服务壳。
- M7：先少量试跑估计方差再定重复次数；真实模型费用须有明确运行预算，技术失败样本保留。

## 执行记录（只追加事实；状态在上文更新）

- 2026-11-06：按用户授权开始持续实施。独立分支从 `bd0d3aa` 创建；保留启动前 README、docs/01–04、旧扩展设计、PROJECT_MEMORY、config.properties 及 design/research 未提交内容。Hermes 初轮只读审计已发起，Codex 独占运行验证。
- M0 旧分支实跑：36/36，76 笔成交、184 条订单、11 行排名，GUI 启动；原始输出 `logs/implementation-20261106/baseline-verify.log`。这是同步前证据。
- Hermes 初轮会话 `20261106_001151_63c207`；达到 24 轮预算，审计答复保存在 `hermes-initial.log`，未成功落单独报告，不能称报告文件已生成。Codex 据代码核实其分支风险；fetch 后 fast-forward 至 `origin/main=6e06ad2`（含价格限制/被动成交统计）。通过有名 stash 保存并恢复原改动，自动合并无冲突，保留 stash 作为可回退记录。之后验证以新基线为准。
- M1.1：新增测试先因缺少 WorldScheduler 编译失败（`scheduler-red.log`），最小实现后 8/8 专项通过（`scheduler-green.log`）；全套和独立复核尚待。
- M0.2：Hermes 实际 RED 为 8 项中 2 断言失败/3 错误；原代码自成交且锁内通知。实现后专项 8/8、当时全套 72/72（`verify-targeted.log`/`verify-full.log`），交接 `hermes-m0-handoff.md`。Codex 更新了旧 AgentStats 中“允许自成交”的测试为“禁止后成交量为 0”，保留正常成交计数测试；这是新规则引起的行为变更，不是放宽守恒断言。
- M1/M2 基础子集：新增 25 项 JUnit 独立运行通过（`world-green.log`）。为避免与 Hermes 抢 Maven，临时用现有 JUnit Launcher + 单独编译目录运行，最终仍要执行标准全套。缺失账户导致 Map.copyOf 的 Map.get(null) 抛异常已先复现为 1 项红（`cash-null-red.log`）再修复；实际线程中断造成 FileChannel I/O 失败时，候选余额不发布且后续写入被阻止。
- 测试反证：仅在忽略目录编译三个故障副本，未修改生产文件。去掉下一毫秒约束 → 2 项失败；去掉命令去重 → 1 项失败；将候选余额提前到持久提交前发布 → I/O 失败测试失败。证据 `mutation-scheduler.log`/`mutation-journal.log`/`mutation-cash.log`；实际代码保持 25/25。
- 新增 `app/WorldFoundationDemo.java` 把调度与现金子账接成可运行调试夹具。独立 JVM 连跑两次，同一 journal 均得到 `deliveryTime=11`、`time=20 position=2 A=900.00 B=100.00 total=1000.00`（`foundation-first.log`/`foundation-restart.log`）。只证明固定夹具重试不重付，不是整个世界检查点恢复。
- 新鲜 Hermes 审查会话正在只读检查 world 包，输出位置 `hermes-world-review.md`；原实施会话继续 M0.3，Codex 不与其并发 Maven。审查发起不表示通过。
- 独立审查已落盘（会话 `20261106_003102_1831ce`）：无 Critical，4 项 Important 与 3 项 Minor。Codex 复现跨线程/暂停排程与中断打开的 3 个反例后修复，新增 5 项后 world 为 30/30（`review-fixes-red.log` → `review-fixes-green.log`）。部分帧头、损坏长度、现金子账残尾重提的测试补齐；不以中断测试代替短写测试。
- Ruling: 排程/推进绑定创建调度器的线程，暂停期间新排程明确拒绝，异步结果交唯一写入者复核；这是落实基线的串行提交与时序规则，代价是未来运行器须在该线程创建/驱动，不能从模型线程直接调用排程。
- Ruling: 中断状态由上层取消策略处理，不在存储层悄悄清除；已中断线程打开日志在创建文件前明确失败。代价是恢复必须使用有效工作线程或由上层明确结束取消状态，不能无条件同线程重试。
- Final: minor (deferred): WorldJournal.close 的极端文件锁释放失败路径仍可能跳过 channel.close；普通关闭和中断关闭均已测，异常释放的资源清理留下一单元处理。
- Final: minor (deferred): 帧头以长度反码检查常见损坏，未为“长度及反码同时协调损坏成另一合法长度”提供额外摘要；本次证明进程故障/显式截断恢复，未证明硬件断电重排或全部磁盘损坏情形。
- Final: minor (deferred): CashLedger 写失败通过异常和阻止后续写显露，尚无专用可写状态观察字段；运行器接入时须把该故障映射为世界暂停，不能只展示旧余额。
- M0.3 真实 CSV 原因回归先红后绿，定向 3/3，原回调兼容。最终标准验收（`review-fixed-verify.log`）：82/82、76 笔成交/215 条订单/11 行排名、CSV 复算与 JavaFX 启动，全绿。未调用真实 LLM；已有用户 agent 数量配置保留。本阶段“本地已验收”不等于已人工 review/合入，更不等于全部世界已完成。
- 首批提交 `25ded88` 已推送到功能分支。GitHub 连接器建 PR 返回 403，改用本机已有 gh 登录并仅在该子进程绕过失效代理后成功创建草稿 PR [#6](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/6)。未更改系统代理/登录配置，没有合入。
- M1.3 提案由原 Hermes 会话给出（`hermes-m13-proposal.md`）；Codex 采纳单一日志与持久待办，裁掉尚无必要的多模块抽象、格式升级与假模型端口。上面 M1.3a 是施工规格，提案不自动成为实现或验收。
- PR #6 的首批远端 CI 已实际通过：`verify` run `42069135406`，状态 completed / conclusion success，提交 `25ded88fb80b91ae8d92886180e568749836edfe`。这只验证该提交的无 GUI 入口，不代替本地界面实跑或后续提交验证。PR 仍为 draft/open，未合入。
- M1.3a 初稿：Hermes 新增 10 场景先因能力占位失败，后专项 10/10、全套 92/92，独立进程 schedule/full/full 验证位置 5、900/100 不重付（`hermes-m13-handoff.md`）。尚未验收/提交：新鲜会话 `20261106_010714_b6dff1` 的实测发现溢出和内部回报 ID 碰撞会永久卡住运行，以及线程/暂停契约未落实；Codex 另指出测试钩子、整帧删除冒充短写和首快照未与开局资产核对。当前进入一次 RED→GREEN 修复，任务/证据在 `hermes-m13-fix-query.txt`、`hermes-m13-fix.log`，不是完成记录。
- Ruling: 内部回报 ID 在整个已用任务集合中确定性找空闲标识，不通过限制用户 taskId 前缀掩盖碰撞；同 ID 异内容仍拒绝，外部命令已有 `cmd:` 命名空间，无须多设保留字。代价是内部回报 ID 不保证总等于简单的 `notice:<source>`，必须在回报中显式保留源任务 ID。
- Ruling: 恢复读取并验证完整已记录状态，不重新执行处理器；首快照必须与清单的开局资产一致，后续阶段/待办/幂等记录均检查不倒退或改写。代价是当前全量状态验证成本随历史增长；未宣称规模性能。
- M2 初步提案已保存 `hermes-m2-opening-proposal.md`，未授权按原文施工。Codex 核对发现可卖日应 `eligibleFromDay <= day`（提案 `<` 会多锁一天）、冻结属于剩余库存的子集不能再相加、同一运行复制配置与新实验开局/分支不能混淆；人物/组织/产品财产主体和持仓数量唯一权威还需收束。进入 M2 前必须纠正这些接口/算例。
- M1.3a 阻断修复：真实 RED 为 16 项中 5 失败/2 错误；改为前置精确时间、内部标识找空闲、线程绑定/暂停、首快照与状态链验证后 98/98。Codex 把碰撞测试改为真正交换到期顺序，又补同刻阶段/较早阶段待办恢复反例，2 项实际失败后修复，最终 Runtime 18/18。
- M1.3a 本地最终验收（`m13-final-verify.log`）：100/100、98 笔成交/226 条订单/11 行排名、CSV 复算与 JavaFX 全绿。Codex 三进程演示位置固定 5、900/100、1 个回报（`codex-runtime-demo-1/2/3.log`）；`javadoc:javadoc` BUILD SUCCESS（`m13-javadoc.log`）。详见 `docs/05` §8。首批 82 项断言未放宽。此处仅现金启用子集完成本地验收，M1 整阶段/全部世界仍未完成。
- Final: Ruling: Runtime 审查中的“外部 __open__ 需禁用”和“重算 due 仍返回原回报”不采纳：`cmd:` 已隔离外部命令，同标识异内容必须拒绝；代价是调用者须保存原规范化请求重发，不能改时间后仍称原请求。
- Final: minor (deferred): Runtime.close 仍未向调用方传播关闭异常；与已记录的 Journal 极端释放失败路径一并留到资源生命周期单元，当前未对该路径作成功保证。运行公开暂停标志现已 volatile，写失败状态观察在 Runtime 中已补齐。
- M1.3a 提交 `60fc1f4f8ba21699d91c3fe696b5d7c1cc72de44` 的远端 CI 已通过（run `42076638419`）；PR #6 仍为 draft，未合入。M2.2 新增 17 项先红后绿、初稿全套 117/117，但独立会话 `20261106_015153_619714` 发现成本/参考估值未对齐、分配主键过窄，故尚未验收/提交，正在一次修复。
- Codex 用 `60fc1f4` 真实源码单独编译生成旧现金待办日志，再以 M2.2 新代码恢复与重跑，得到位置 5、900/100、1 条回报；原始文件/输出在 `legacy-v1/`。独立审查也核对了旧 openingContent 逐字节不变。不是只用新代码自写自读。
- Ruling: 不人为缩小 long 股数范围来掩盖未来错误算术；数量累计用 Math.addExact，金额乘数量用 BigDecimal。登记 schema 由 `registry:1` 与对应规则档案联合固定，未来变更须显式升级并保留旧读写支，不另加无用途的版本号。代价是后续模块必须实际遵守精度与兼容契约，不能随意改字段。
- M2.2 最终本地验收（`m22-standard-verify.log`）：121/121、72 笔成交/230 条订单/11 行排名、CSV 复算与 JavaFX 全绿，JavaDoc 构建成功。成本/估值与分类三元主键的反例先红后绿（`m22-fix-red/green.log`）；详见 `docs/05` §9。只完成不可变开局登记，未实现动态证券持仓/任职。
- M2.2 已提交并推送 `025c45b` 至草稿 PR #6。原 Hermes 实现会话已有 462 条消息，已留下 `hermes-m22-fix-handoff.md`；在此已验收单元边界换新执行上下文承担 M2.3，避免无限增长上下文。新任务读取本 Plan/现有源码/交接，不用 latest 猜会话；查询和输出为 `hermes-m23-query.txt` / `hermes-m23.log`，140轮/2400秒有界执行。Codex 保留总体方向与单写入者管理。
- Ruling: 批次的 acquiredPosition 采用局部取得顺序而非世界提交号，初始排序稳定，新买入取现存最大值+1；真实发生时间/来源另由 acquiredAtMillis 与源任务关联。这比把开局数组序号和真实提交号混用更直接，也保留 FIFO；代价是审计查询不能把该顺序号直接当 journal 位置。M2.3 契约已据实际最小实现澄清，未声称语义相异的数字可互换。
- Ruling: 为限制一次人工审查的范围，PR #6 冻结在已验收 `025c45b`（121 项）；M2.3 改在同一工作区的 `codex/world-securities-ledger` 分支施工，后续草稿以 `codex/world-implementation` 为依赖基线。切分不改变源码/用户改动，不合入主干；代价是人工合入前需按依赖顺序审查，前一 PR 合入后重核后续基线。
- CI 配合上述分段仅扩展 pull_request 目标匹配到 `codex/**`，原 main/push/验证命令和断言不变；依据 GitHub 官方 workflow-syntax 对目标分支过滤的定义。新草稿创建后须看真实远端 run，不能把匹配规则写好当成 CI 已通过。
- M2.3 初稿 143 项通过；Codex 发现两项故障用例实际中断的是预留完成帧，原覆盖保留并更正名称，另补“预留已确认后 fill 故障”的真实中断/半帧用例。修补与独立会话 `20261106_023855_71c22d` 审查并行，生产实现冻结，尚未验收。
- Codex 用 `025c45b` 的实际旧源码生成 registry 待办存档；本单元新程序续写至位置5、A900/B100、股份1000、回报1，再由原旧程序读取新追加的文件，数值/位置一致。证据 `legacy-registry-v1/m23-new-append.log`、`m23-old-read-new-append.log`，不依赖新代码自写自读。
- Ruling: ActionReport 由 Runtime 结合权属与提交位置组装，纯证券函数只产候选与原因；reports 按 taskId 的不可变 Map 保存。这样避免在证券子集重复账户权属，报告仍与资产同批确认；清算夹具不因此成为角色授权接口。
- M2.3 独立审查已保存（`hermes-m23-review.md`）：未发现错账或重复结算实现阻断，私有副本实测5场景27断言通过。真实fill故障缺口已补；Minor中的现金事件类型二义经新增断言实际1项失败后，改为独立 `SECURITIES_CASH_TRANSFERRED_V1`，载荷包含买方/卖方/实际金额，专项14/14（`m23-event-red/green.log`）；报告校验注释改为实际单向保证，不扩防伪造系统。
- Codex 针对新增的两个fill故障测试分别编译私有错误副本：提前发布候选状态使中断测试失败（位置4却变5），不清除不完整尾部使截断测试在再次重启时报校验和错误。生产文件未作这些故障变更，证据 `m23-fill-mutation/early-publish.log`、`retain-tail.log`。局部取得序号允许退役后复用，历史审计以唯一lotId/任务为准。
- M2.3 最终标准验收（`m23-final-verify.log`）：145/145、83笔成交/249条订单/11行排名、CSV复算与JavaFX全绿，JavaDoc成功（`m23-final-javadoc.log`）。评分映射已补、真实记录见 `docs/05` §10；清算子集本地验收，不代表 M2/世界撮合完成。
- M2.3 已提交 `03cebeaebd0496b0bcf976e98bbda9b678d60cdf`，草稿 [PR #7](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/7) 依赖 #6，未合入。工作区仅保留用户原 PROJECT_MEMORY/config 改动；M2.4 在同一工作区新分支 `codex/world-matching` 继续，后续草稿依赖 #7。
- PR #7 的真实远端 CI 已通过：提交 `03cebeaebd0496b0bcf976e98bbda9b678d60cdf`、verify run `42088303477` completed/success；扩展 codex 目标分支的验证实际触发。M2.4a 新实现上下文在 `hermes-m24a.log`，唯一写入 engine 指定文件并独占 Maven；Codex 不并发改这些文件。
- M2.4a 首稿自报164/164，但尚未验收：Codex私有探针实测“预算只吃部分最优单后误查后续自有单”的错误停因；另以03cebea真实旧引擎对比公开无价格档案入口，0.03现金扫两笔0.014报价时旧版成交1、新版成交2。默认配置整分不能代替旧公开入口兼容。证据 `codex-m24a-regressions.md`、`m24a-review-private/`，进入定向修复，不能用绿测试将这两条遗漏升级为通过。
- Hermes 实现会话 `20261106_025235_8376bb` 主动读取共享审查证据，新增2条真实失败后修复（`m24a-regression-red.log`，2失败 → `m24a-regression-green.log`，166/166）。同一maker内继续计算，不提前翻下一quote；预算按旧账户逐笔2位HALF_UP精确演化。独立审查 `20261106_030041_fd5d41` 的初稿仅终端输出，最终文件为 `hermes-m24a-review-final.md`，已更正其非整分误判。
- Codex 用私有探针重新核对：预算部分成交50+45/剩5停BUDGET且selfMaker空；前两maker全成交花完1000后仍先检查下一self、成交90/剩10返回原SELF_TRADE原因；旧/新真实引擎的部分情景均reason=null/cash5，非整分旧/新均只成交1股。证据 `m24a-review-private/budget-green.log`、`engine-*-fixed.log`、`fraction-fixed.log`。原有非整分冻结0.01尾差留作旧缺陷，本单元不大修Account。
- M2.4a 标准验收（`m24a-standard-verify.log`）166/166、64笔成交/243条订单/11行排名、CSV与JavaFX全绿，JavaDoc成功。评分映射与 `docs/05` §11 已更新；仅共享算法/课程适配本地验收，M2.4b 世界可靠订单继续施工。
- M2.4a 已提交 `8c4d7c5e2be8b26e031b0e9144ec58a22b28aadb` 至草稿 PR #8，依赖 #7，未合入。M2.4b 在同一工作区 `codex/world-exchange` 新分支继续；接入只用新世界候选账，不调用 legacy submit，普通付款必须扣除活动买单冻结。内部Java契约已按 api-engineer 技能核对；本步没有HTTP服务，不增加无用途的Postman目录或远端mock。
- PR #8 远端 CI 已实际通过：`8c4d7c5e2be8b26e031b0e9144ec58a22b28aadb`、verify run `42091320092` completed/success。M2.4b执行会话 `20261106_031136_9e1495`，180轮/3000秒有界，输出 `hermes-m24b.log`，Codex保留共享Plan和后续制造规格，未与实现者抢Maven/源文件。
- 为并行实现已固定的M3a纯经营核，使用原生工具创建独立工作区 `C:/Users/JXGU/.codex/worktrees/manufacturing-core/java-stock-auction`，基于已验收8c4d7c5、分支 `codex/manufacturing-core`。只写ManufacturingModel及其测试，不动Runtime/匹配模块；独立target/Maven，证据保存主仓库logs/m3core供后续保留。交易接入仍在G盘主工作区，集成前逐文件验收，不把并行纯核当已连通世界。
- M3a纯核执行查询 `logs/implementation-20261106/m3core/query.txt`，160轮/2700秒有界，输出/交接 `m3core/hermes.log`、`m3core/handoff.md`。Codex另以Decimal独立复算9个阶段，公司平衡式和世界现金3600均成立（`m3core/expected-flow.json`）；这是验收预期，不是Java实现通过证据。
- M2.4b 实现者已交回Maven，自测187项（原166+21），真实RED记录在 `m24b-red.log`。独立审查 `20261106_032958_70a32e` 完成文件 `hermes-m24b-review-final.md`：Critical/Important均0，私有探针51/51；接口文案已由Codex修正，公开Snapshot新增exchange组件需外部按位置构造者调整，旧文件兼容另已实测。纯模块不重复持有registry，由Runtime校验证券是既定分工，不新增第二注册表。
- Codex 独立课程/世界同序列对照已补：预算部分吃单、限价价格改善、主动卖方吃被动买盘三组，成交价/数量、每单剩余/终态、各账户gross/free/frozen均一致（`m24b-parity/run.log`）。不比较两模式有意不同的T+1卖出资格/时钟。
- Codex 发现正式Demo默认在data子目录，会被课程verify整体清理；改为logs/world-exchange-demo，并通过两个独立JVM的0参数实际路径对照（`m24b-default-demo-probe/comparison.log`）。这只改默认存档位置，不扩大日志层硬件保证。
- Codex 三进程正式Demo（`codex-exchange-1/2/3.log`）确认prepare time10/pos4/预留100/pending1→full time30/pos6/交易1/现金1600与400/股40与160/卖预留40；第2/3进程输出逐字节相同。03cebea真实schema2存档新程序续写再旧程序读取，均time30/pos6/现金1600与400/股40与160/预留0/报告2，且旧格式无exchange字段（`legacy-securities-v2/m24b-new-append.log`、`m24b-old-read-new.log`）。
- M3a纯核会话 `20261106_031841_9f78dc` 已结束、C盘独立186项（原166+20）全绿，源码仍仅C盘未集成。Codex另写Java探针逐步调用公开纯接口，9阶段JSON与预先Decimal参考完全相等（`m3core/codex-private/flow-comparison.log`）；公司owner第二现金账户在验收时已正确拒绝。它不等于G盘已跑制造；关期强制调度/交易冻结联动/董事会仍待Runtime桥。
- M2.4b 最终标准验收 `m24b-standard-verify.log` 为187/187、85笔成交/236条订单/11行排名、CSV/课程GUI全绿，JavaDoc已实际重生成并核对修正文案；评分映射和 `docs/05` §12 已更新。用户配置与记忆原改动仍不提交；仅可靠交易核本地验收，尚不表示完整M2或股票世界。
- M2.4b 提交 `2acea2aecb6529021e39f91ba9eca13b23a5ae97` 已推送，草稿 [PR #9](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/9) 依赖 #8，未合入。后续主分支为 `codex/world-manufacturing`；独立C盘的ManufacturingModel及测试已逐文件SHA256相等复制到G盘，未复制配置/旧Plan/生成物，进入组合基线验证和Runtime桥施工。
- G盘复制后的组合基线207/207（187交易核+20制造纯核，`m3core/main-combined-baseline.log`）。原生工具拒绝归档C盘并行树，原因是它被应用设为当前主工作区；没有通过shell绕过删除，保留可恢复副本。后续仍明确以G盘为代码/证据主仓库，全部命令显式workdir；必要时在最终提交后把C盘主树更新到同一版本，避免用户看到旧源。
- PR #9的真实远端CI已通过：2acea2aecb6529021e39f91ba9eca13b23a5ae97，verify run42096777481 completed/success；PR #6–9均已附在当前chat供审查。M3a.2新上下文查询/输出 `hermes-m3runtime-query.txt` / `hermes-m3runtime.log`，180轮/3000秒，独占G盘Maven；仅按子计划施工。
- C盘既有隔离树改在 `codex/board-core`（保留M3纯核未提交文件），并行M3b.1纯核，仅新增BoardModel/测试。查询/交接在 `logs/implementation-20261106/boardcore/`，150轮/2400秒；不改G盘Runtime，不把纯核称为治理闭环。
- M3只读提案第一稿存在预付资产遗漏、预收交付应收双算、客户预算与合同矛盾、有息债务未计提等问题，Codex已逐条指出并取得修订稿（`hermes-m3-manufacturing-proposal.md` v2）。它仍是待裁定提案，不是实现；后续要组合交易模块、约束公司经营账户不能绕过业务分录，不能仅给WorldState加字段就称公司竖切完成。
- 方案取舍：总 Plan 采用滚动细化，避免给银行/认知等未到施工点的模块虚设签名。用户已明确执行方式为 Codex + Hermes，且要求今晚持续推进，因此不再增加一轮纯计划批准；涉及改变既定需求/架构或人工 review 合入时仍遵守仓库要求。

## 下一步

M2.4a 共享纯撮合 → 审查/标准验收 → M2.4b 世界订单接入；制造公司提案已由独立 Hermes 只读准备，待交易边界稳定后裁定施工单。M1/M2 只有上述已验收子集有证据，其他任务继续按依赖施工；不把“已派发”写成“已完成”。
- 04:08后：董事会纯核Hermes会话20261106_035758_104166交付，仅C盘两个新文件；独立树226项（原166+制造20+董事会40）全绿/Javadoc成功，尚未转G。Codex另开真实Hermes独立审查（boardcore/review-query.txt、review.log），重点含小数评分最终舍入和时序边界；不能凭原作者测试宣布验收。
- Codex从已提交2acea2a提取实际旧schema3源文件与WorldExchangeDemo，在私有目录独立javac编译，旧程序真实产生time10/position4/活动卖单及pending买单存档。保留logs/implementation-20261106/legacy-matching-v3/actual-old-run，待制造桥新追加后再由原旧程序读回；未用新程序伪造旧存档。
- 董事会独立审查会话20261106_040916_148f47：116条公开接口检查113通过/3个小数评分失败，确认Important两项（逐项舍入、负/倒流时间），另记5个Minor。实际报告boardcore/review-private/review.md，不是原作者自评。已续接原执行20261106_035758_104166，query fix-query.txt，按先RED修舍入/时间/空预算/错过窗口及结果内部一致性；同时按下一Runtime合同补REVIEWED与考核quorum。sourceRef版本歧义留受信夹具边界，Runtime唯一引用含截止位置；不把管理审计说成角色已读。
- 董事会审查修复后54项/隔离240全绿；Codex另用公开ReviewResult私有探针抓到评分>100与无缺项却标INCOMPLETE两例，先加2个JUnit真实RED（56项2失败），加最小字段守卫后隔离全套242/242通过、私有探针两项拒绝且0.01正确。证据boardcore/codex-invariants-*.log、codex-final-full.log。原40断言保持；这是董事会纯核验收，尚未转G/接世界。
- C盘原生隔离树继续复用为codex/role-access-core（base8c+既有制造20+董事会56，基线242）；真实Hermes新上下文执行M4a.1，仅RoleAccess及测试，query rolecore-query.txt，150轮/2400秒。G/C target互不交叉，C既有Model/Board不再改；机构资产只引用同一PRODUCT账户，不创建四份钱。
- Codex已独立跑制造标准215/215+CSV（78成交/228订单/11排名）+JavaFX启动与JavaDoc重生；UTF8三进程制造输出2/3 SHA256相等，现金1150/设备900/利润50/权益2050/世界3600；真实旧schema3新续写旧读回成功。制造独立审查仍在m3runtime-review-private，未以尚未结束的审查冒称整阶段完成。
- M3a.2独立审查20261106_041826_7c05d5实际解析106批/59报告/23现金事件，1719检查通过、0Critical/0Important。Codex对4个Minor中可最小修的期末溢出/制造结果交叉核对/关期失败注释作收尾；新增2测试先RED，再10/10通过。首个最终标准重建因target/surefire-reports占用在clean阶段失败（未跑测试），保留记录；原命令重试217/217+73成交/234订单/11排名+GUI全绿，JavaDoc04:35:36重生成功，最终源码真实旧schema3新续写旧读回再次通过。
- Ruling：预期外关期拒绝不会提交一份没有下一期任务的候选；链校验失败保留旧快照/待办并暂停。原handoff说仅记失败报告继续不准确，以当前代码/接口文档为准。错误参数同时违反能力和时间时的异常优先序，以及纯Model直接传无法表示的天文期间，仍为明确低影响边界；Runtime已前置阻断后者。
- PR #10已草稿创建并附chat：https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/10 ，head828417417f7f3d73b450ad753d1b1c42f1ff7ad7/base codex/world-exchange；远端verify run42105036609 completed/success。保留人工review后才合入，未自动合并。制造独立审查窄范围校正后新源码1720检查全绿、0Critical/0Important，仅异常优先序/纯核直接天文输入两低影响边界。
- 主G新分支codex/world-governance，从C逐文件hash转移BoardModel与56测试；制造217+董事会56=273项，main-combined-baseline.log实际273/273通过。治理桥真实Hermes新上下文query governance-query.txt/log governance/hermes.log（180轮/3000秒），独占G source/Maven，依BoardRuntime施工单；Codex负责独立验收和下一草稿PR。
- 已从冻结8284174实际git源提取旧schema4独立程序并生成20日prepare存档：legacy-manufacturing-v4/actual-old-run/company.journal（position13/cash200/AR1200/pending4），保留classes/src/source-commit.txt供下一治理版本新续写再旧程序回读，不能用自写自读替代。
- C角色纯核已实作16新增测试，隔离258全绿，作者仍在收尾交接；已开真实Hermes独立只读审查（rolecore/review-query.txt/review.log，90轮/1500秒），不用G/C target，私有javac对照权限/撤职/跨产品。未转G，不宣称四岗LLM/角色工具已运行。
- RoleAccess执行20261106_042905_9ef3bb、独审20261106_044111_0c2730均已结束；C全套258（242+16）通过，165独立断言全绿、无Critical/Important。四个Minor保留：State与registry归属恢复需validateAgainst、null异常文案、预排任职开始前不可撤；Runtime必须绑定原registry、复核时点/岗位、仍服从引擎/风险限制，机构级mandate不能冒称产品级岗位分权。代码仍仅C，不在G阶段计数中。
- C继续复用为codex/information-core，真实Hermes新上下文按information-query.txt实施InformationModel及测试（150轮/2400秒），基线258，G源码/Maven不碰。目标为版本冻结、正耗时阅读、到期授权、个人真实经历与引用；群聊/自主媒体/六层认知不虚构完成。
- M6a私有准备：observer-preview/WorldAuditExporter.java + world-audit.html从真实35日存档导出index.html，另实际世界夹具导出precision.html。静态核对cash1150/equip900/profit50/equity2050/world3600与实际经营现金流1200/500/550；超2^53金额和9007199254740993股均以精确字符串保存，HTML敏感内容转义、无innerHTML/网络资源，node --check通过。尚未复制入src/未提交，不声称界面验收通过。
- 内置浏览器明确因URL协议安全策略拒绝file://，禁止绕道其他浏览器/协议完成同一动作；未启动HTTP或替代浏览器规避。已停止界面自动点击路径，向用户留了可手动打开的本地HTML链接与可异步回复的验看问题，继续独立核心工作。M6浏览器真实点击/截图仍待，静态检查不是替代品。
- M3b.2独审两项Important经真实RED修复；Codex重跑原TamperProbe发现首轮修复遗漏“所有快照一致改票但结论矛盾”，新增Runtime第21项RED再补表决终态与冻结提案/真实票数/截止的一致性。最终295/295标准+80成交/230订单/11排名+GUI全绿，JavaDoc05:36:50实际重生。最终私有3JVM、30/35/61日逐帧独立算账、真正8284174旧schema4新续写再旧读回均通过，证据governance/codex-final-v2；旧失败证据保留。
- MarketContinuityTest一次失败由独立诊断20261106_051332_c544e0查明是随机库存偏移导致单边报价，并非冻结回收bug。仅测试增加有限HumanBroker主动对手，原assert/阈值/末30%窗口保留；真实撤单路径禁用副本使原assert失败，正常对照与三次运行通过。见docs/05 §14.4，生产策略未改。
- C任职纯核补validateAgainst，原16+6=22，C全套287绿；Codex在更新源码上重跑原独审165断言及4个开局归属反例，全部通过。固定账户/个人绑定/PRODUCT种类与任职登记恢复边界已补，Runtime仍须逐批保护历史与固定配置。
- C资料纯核独审20261106_051933_57247a发现迟交授权时点与固定due矛盾及无效权限夹具；原实现会话20261106_045958_8e2ff2续修后29项、C全套293绿/Javadoc重生，岗位越权变异体被新断言抓住。Codex重跑原Probe/Probe2：due后离任的两次迟交均COMPLETED且closedAt=due、due当刻离任DENIED、读序号倒退及恰好due取消均拒；旧读保留/新人不克隆/v2冻结通过。尚未转G，不混入当前295计数。
- 治理最终窄复核两个Important消除、63条独立断言通过；又实证两项Minor（足额席位伪改ADJOURNED、无实施回报伪改IMPLEMENTED）。Codex新增两条回归，补法定人数/截止及成功实施回报校验；第二条修正夹具为ADOPTED后的新帧，并用冻结旧Runtime运行新版回归取得真实RED。最终私有StatusTamperProbe两例均拒，治理专项23/23；最终全套计数297。复核报告governance/review-private/review-final.md描述补丁前哈希，最终源码与证据governance/codex-final-v3，按此区分时点。
- 治理最终297/297标准验收：70笔成交、219条订单、11行排名与CSV/JavaFX全绿；最终JavaDoc05:44:30重新生成。治理预算/复盘子集本地已验收，完整M3仍含分红等未完范围。评分映射与真实记录已更新，不将AI审查视作人工review。
- 治理已提交bbd51818cf709db3383220e63cea05a294d6b16d至草稿[PR #11](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/11)，base world-manufacturing；远端verify42115434616 completed/success，未合并。后续在codex/world-role-information逐文件hash转入C已验收Role/Information四文件，未夹带C旧Model/Board。
- 任职/信息组合G348/348、98成交/238订单/11排名、CSV/JavaFX全绿，JavaDoc05:46:37实际重新生成。证据rolecore/g-combined-standard-verify.log及g-combined-javadoc.log；纯核本地已验收，Runtime仍schema5，尚无真实持久阅读/角色工具。下一步按八参/schema6施工单接入，角色交易母任务与认知另做。
- 任职/信息纯核已提交77e1136f78eb2febe77a6939e62e12542e9a7b79至草稿[PR #12](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/12)，base world-governance；verify42115778312 completed/success。主G继续codex/world-role-reading，Role/Info Runtime施工query在role-reading-query.txt（180轮/3000秒），真实Hermes独占G源码/Maven；Codex负责文档/独立验收。
- C原生主检出无法归档（app primary checkout限制），没有绕道删除。核对只有8个本轮自建未跟踪文件、无tracked改动后，按显式路径stash保留，再detach到77e1136同步已交付代码；所有成果已逐hash转入G提交，原隔离版本仍可恢复。未移动用户G文件。
- 已冻结真正旧schema5程序bbd5181至legacy-governance-v5，最终actual-old-run-final在20日/position30/cash200/AR1200/pending3，供新schema6跨版本续写再旧读回；先前实验目录保留但不作为最终旧程序证据。
- 离线观察私有原型追加governance.html，来自实际61日治理日志：现金900/设备800/利润-300/权益1700、30日期间证据100/0/100、两次不完整复盘与500/300预算已花/0可用、真实现金流1200/500/800。静态序列化/精度/转义断言通过；仍无浏览器实点，不标M6完成。

- M4a.2/M4b.2实施20261106_054749_91e011、独审20261106_061240_1c6562。首稿359项不能作为最终验收；Codex真实复现命令换due/task仍幂等、同人同毫秒旧引用误取消新单，原RED保留codex-run-1。修复为从原TASK_SCHEDULED比较完整请求，以及所属人物+本人实例序号引用；不增加资产副本或改旧schema5线格式。作者交回361项后Codex补完整Snapshot断言和同毫秒公平排序回归，最终362项标准/GUI/CSV全绿（79成交/221订单/11排名），JavaDoc06:52:22真生成。
- M4最终私有证据codex-run-2：95检查通过；同毫秒start/cancel/restart旧ref→NOT_FOUND active=1、同刻同版本跨人ref拒绝；真实ClosedByInterruptException当场完整Snapshot相等并暂停，两次重启只完成一次；正式Demo prepare/full/full保持position18、1经历/1DENIED；bbd5181真实旧schema5→新续写→旧回读通过。独审按最终B7CE83D0/085D2564冻结源码复测46条自有检查、31条重放及91条旧版Root检查，全过且本人视图差异0。原作者交接旧hash与审查中旧API文字的时间差已在docs/05 §16校正，不把历史发现当当前未修项。
- 下一施工口：在本地已验收schema6上固定人物身份绑定工具网关与四岗交易母任务/风险额度/执行员子单，不直接给RoleAccess.ALLOW接旧引擎submit。媒体/六层认知/银行/现金分红/TWR/完整白色工作台仍未完成；离线观察页只在忽略目录有静态数据验证，没有浏览器实点，未计M6通过。所有草稿仍须人工review，未合入main。
- M4运行桥已提交3a60017dbef5481e85952ffb1451dc1eac208245并推送，草稿[PR #13](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/13)以world-role-information为base，已附当前会话。创建时verify run42125539654实际in_progress；远端最新结果以PR Checks为准，不把本地全绿冒充远端结论。config.properties/PROJECT_MEMORY的开工前SHA256保持，未纳入提交。草稿依赖链#6→#7→#8→#9→#10→#11→#12→#13，均未合入。
- 用户继续授权完成，采用C当前树codex/world-role-trading，basefe30171。真实Hermes调查/测试20261106_084239_687e0f，Codex实现纯核并修正状态夹具，独审20261106_091128_ff456b。最终RoleTradingModel源码98AD58EF…7EFC17，23专项与全套385通过，标准49成交/123订单/7排名及GUI/CSV全绿，JavaDoc09:31:25真生成。G原用户改动继续位于原检出。详见docs/05 §17及M4c子Plan的审查裁决。
- M4c运行桥准备会话20261106_091523_c9946f已产生接口合同和6条测试草稿，保存在logs/implementation-20261106-continued/role-trading/runtime-preparation/。纯核之后接续新schema7、人物会话、完整I/O/重启及旧schema6对照。草稿把更新后撤单前成交改为更新前，且故障用例只有文件截断，正式实施须补回真实因果窗口和Thread中断写入验收。
- 纯核997284d已建立[草稿PR #14](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/14)，依赖#13且未合入，verify run42144982713实际completed/success。C树现以codex/world-role-trading-runtime续接运行桥，真实Hermes会话20261106_091523_c9946f于09:39接获正式实现合同，交回前由其单写源码/测试/Maven。
- M4d已形成[决定屏障与尝试恢复施工单](2026-11-06-decision-runtime.md)，依据真实Hermes只读准备会话20261106_094824_aa419b。当前仅完成规格裁决，待M4c运行桥独立验收后施工。先用scripted提供方验证时间、代次、预算与故障，真实模型按明确费用预算另行启用。六层认知仍有独立缺口。

- M4c运行桥最终本地验收，71源冻结root-candidate-3，38专项/组合423标准全绿，48成交/124订单/7排名，GUI启动/CSV复算和JavaDoc12:40:43通过。Codex主探针61检查、7种合法SHA语义伪造拒绝、三JVM42→42/成交2→2、旧6新续写旧回读均通过。真实Hermes最终独审59 PASS/0 FAIL及3种timer反例拒绝，盲区明确，详见docs/05 §18。

- 运行桥ffb49d4建立[草稿PR #15](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/15)，base为#14分支，远端verify run42164122461实际completed/success，尚待人工review。当前C转codex/world-decisions接续M4d。真实Hermes20261106_094824_aa419b按Task1入场裁决独占DecisionModel及其测试/Maven，120工具/3600秒；原运行桥会话20261106_091523_c9946f仅做Task2私有只读准备。

- M4d纯核DecisionModel新增56专项及组合479标准全绿，49成交/121订单/7排名、GUI/CSV、JavaDoc13:22:44、Codex72源独立编译及20正常入口检查通过。首稿26专项/449后Root新增30审计专项修复真实反例，最终hash独审正在进行。运行桥Task2–3仍待接续，详见docs/05 §19及M4d施工单。

- 决定纯核550f5b6通过[草稿PR #16](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/16)依赖#15，远端verify run42169342964实际completed/success，已附会话。当前C转codex/world-decision-runtime，真实Hermes新上下文按M4d Task2–3合同独占源码/Maven，220工具轮/7200秒；旧准备稿为只读参考，正式裁决优先。

- M4d运行桥候选4冻结75源，桥55+runner12及1纯核入口新增，标准549项、CSV45成交/119订单/7排名。Codex独立146检查、同供给40/10竞争、资金4600/股份500/冻结285、真实中断/半帧通过。三JVM28帧仅1笔经济成交，真实旧7新full25→42与旧full42→42通过，原件不变。最终独审、GUI/JavaDoc与草稿PR收尾中。持久墙钟审计、独立身份拒绝归档、M4认知媒体及M6人工查看继续保留。

- M4d candidate4最终75源一致，真实Hermes独审42断言及原六组探针通过。标准549项、GUI/CSV、JavaDoc16:15:59和独立Decimal预算/钱股复算完成，当前发布草稿PR。M4d两项审计补充和M3分红/TWR仍保留施工状态。

- 运行桥代码e1ba4402503c773c508a3547d9b1e3f5ab593ad8已建立[草稿PR #17](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/17)，base为codex/world-decisions（PR16）。远端verify run42186774016实际completed/success，已附当前会话，尚待人工实质review。 持久等待审计及身份复用拒绝记录继续以M4e明确补齐，准备轮只读进行，随后接M3分红/TWR。

### M4e 决定审计补项（依赖PR17）

施工单见[决定审计Plan](2026-11-06-decision-audit.md)。代码基线46478b2，PR17代码与文档head的远端verify分别42186774016、42187176239均已实际成功。真实Hermes20261106_162110_27a01f只读准备后取得8项缺能力RED，Codex纠正三处新测试夹具并核对生产零改动。现授权同会话仅写WorldRuntime及该测试文件，Maven独占，80工具/2400秒。审计附加在原WorldJournal批次，不增加快照字段或第二账。完整Reply去重含原文、声明及来源，持久拒绝不改决定、预算与经济。完成后再推进M3分红/TWR，原只读建议尚需裁决。

- M4e审计补项candidate4本地验收完成，573标准、53成交/129订单/7排名、GUI、JavaDoc17:38:15，独立72+146及Hermes35断言通过。旧7新续写旧回读、旧8旧prepare由新代码接续、含4审计的新日志由旧二进制实际转账8→10再由新代码读回均通过。当前发布草稿PR，随后接M3分红权利纯核。分红准备稿中的金额类型、减值与债务口径已纠正，登记时应付确认及价格边界仍以正式D0施工裁决为准。

- 审计代码a92ef2b9b95460c42e19ee6339c961a9f2cdcb72已建立[草稿PR #18](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/18)，base为codex/world-decision-runtime（PR17）。远端verify run42197537789实际completed/success，已附当前会话，待人工实质review。 下一阶段M3分红权利纯核以本提交为基线接续。

### M3d D0 分红权利与应收纯核

施工单见[分红纯核Plan](2026-11-06-dividend-core.md)。基线PR18文档071b4e9及代码/文档CI均成功。真实Hermes先交缺API RED，Codex裁决登记零应付、减值不减公司债务、完整Event及原请求幂等，再授新增DividendModel单文件。首稿604通过后独立核验取得8组恢复RED与金额Map规范化RED，最终43专项和616标准、GUI/CSV/JavaDoc、82独立检查及真实13帧Decimal复算通过。真实Hermes独审首稿4条缺口已纳入回归，最终hash复验进行中。D0只权利及清偿元数据，D1估值/TWR、D2授权、D3真实同批除息与付款为后续施工，M3整体仍未完成。

D0最终candidate3的真实Hermes独审90/90通过，原4条发现全部修复，76源hash一致。本地验收完成，现发布草稿PR，随后接D1估值/TWR纯核。D0首稿与中间614项结果保留，最终标准为616项。

分红纯核代码d2fb92607b24e5da893f6626bcd3525c52418cc3已建立[草稿PR #19](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/19)，base为codex/world-decision-audit（PR18）。远端verify run42204395506实际completed/success，已附当前会话，待人工实质review。下一阶段D1账户估值与时间加权收益纯核继续施工。

### M3d D1 账户估值与时间加权收益

施工单见[估值与表现Plan](2026-11-06-valuation-performance.md)。父版本D0草稿PR19已通过代码CI。现授真实Hermes aeafaa两个新测试文件及Maven独占，先取得缺API RED，76生产源和旧616项不得改。D1采用同一截止现金/剩余持仓/分红净应收查询、原始价格与缺价状态、明确流前后估值和几何链接表现指数。公司完整NAV、份额申赎、董事会授权及实际派息沿后续施工。Root当前只维护文档和私有证据，测试交回后核对再授生产。

D1测试先行交40项仅缺API RED，Codex核对修正新夹具并增加4项，44新测试与真实底层夹具已核准，76生产源保持。现授同一真实Hermes两新生产文件及两测试/Maven独占，专项目标44、组合至少660。D0最终文档head4e33ce1的verify run42204747497也已实际completed/success，原PR19继续等待人工review。

D1首稿44/660及两变异通过后，Codex独立取得未来Quote/age/开局时点、标识二元键碰撞和缺价已知Flow等实际反例。新增4项正式RED后修复，当前candidate2为48专项/664标准，GUI/CSV53成交140订单7排名、JavaDoc19:07:27、独立33及Decimal复算通过。最终Hermes独审进行中，D2董事会授权只读准备已交，尚未授源码。

D1最终candidate2的真实Hermes独审64/64通过，原2条Important修复，78源hash一致且原76逐字节相同。48专项、664标准、独立33及Decimal、GUI/CSV/JavaDoc均通过，现发布草稿PR。下一D2正式Plan采用Meeting可选元数据并保留State五字段，实际旧二进制golden准备进行，尚未授测试/生产。

估值与表现代码dc90a607bbac4c6a330691f0d35dccc8a861c279已建立[草稿PR #20](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/20)，base为codex/world-dividend-core（PR19）。远端verify run42209352373实际completed/success，已附当前会话，待人工实质review。下一阶段D2董事会分红授权纯核继续施工。

### M3d D2 董事会分红授权纯核

施工单见[分红授权Plan](2026-11-06-dividend-authorization.md)。D1代码PR20实际CI成功后进入D2。真实旧Board二进制生成三份固定JSON及旧编译客户端，Root已核预算可用400.00及评审66.00，原5参State/14参Meeting构造字节相同。现授真实Hermes aeafaa新BoardModelDividendTest及Maven独占，仅测试缺API RED，78生产源及旧664项保持。State仍5字段，授权元数据归所属Meeting，旧合法JSON和接口继续兼容。D3的真实现金与世界批次尚未接线。

D2测试交26项真实缺API RED，Codex核对修正重审时界/辅助构造及来源对照，再补4项共30，78生产与旧664保持。现授同一真实Hermes仅BoardModel与新测试/Maven独占，目标694组合。原State保持5字段，Meeting可选元数据及旧14构造由新测试和真实旧golden共同保护。真实现金、参考及恢复桥沿D3准备，尚未授D3源码。

## D2最终本地验收

最终candidate2冻结78源，BoardModel815c8e7f...a09951e及Runtimeee0817b1...987a92c0，其余76生产源与D1逐字节相等。新47专项中14失败1错误的首稿RED已真实取得并修复，另1旧档能力闸恢复RED已修。最终712标准、52成交133订单7排名CSV、GUI、JavaDoc20:14:36通过。Root13分红+146世界+72审计检查，原编译旧客户端及旧构造、三golden逐字往返通过。真实Hermes最终独立重编74/74及旧档合法/伪造对照通过，已退出释放。当前Root独占源码/Maven，准备草稿PR，仍待人工实质review。D3从制造报表与除息参考接缝开始，随后完整新档案桥，不把纯核误记为真实付款。

董事会分红授权代码eb9d96e2d0d69c63954b9539d5a01e6054a49dad已建立[草稿PR #21](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/21)，base为codex/world-valuation-performance（PR20）。远端verify run42217088050已实际completed/success，已附当前会话，等待人工实质review。D3a财务与除息参考接缝继续按新施工单推进，M3整体运行闭环仍在施工。

## D3a测试先行写权

当前codex/world-dividend-financial-seams base d100a8a。D2草稿PR21代码eb9d96e实际verify42217088050成功并附会话，文档head d100a8a待核CI。正式D3aPlan已写2026-11-06-dividend-financial-seams.md，两个纯核接缝，原712保留。真实Hermes aeafaa测试轮仅拥有新ManufacturingDividendTest/DividendReferencePriceTest及C Maven，55工具1800秒，未授78生产源。Root不可编辑两测试或跑C Maven，仍可docs/private，测试交回核对后再授生产。生产仍D2最终78源，暂无main写入者。

真实Hermes849817最终独审从candidate1核78源hash并独立重编，制造17及除息参考26项自写断言合计43/43通过，未发现可复现缺陷，报告dividend-financial-review-candidate-1/handoff.md。Root随后仅移除新测试Fixture的三个未使用分量，所有断言及生产源保持，重新运行clean标准744全绿，最终CSV为37成交、119订单、7排名，JavaDoc21:11:18重新生成。最终日志standard-root-final-1-raw.log及javadoc-root-final-1-raw.log，冻结standard-final-raw。先前49/128及21:02:42为首轮结果，保留原证据。D3a本地验收完成，当前发布草稿PR及等待实际CI。

财务及除息参考代码6220ed85275eda2826b17054ac570c32f01dadb9已建立[草稿PR #22](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/22)，base为codex/world-dividend-authorization（PR21）。实际verify run42223835571已completed/success，已附当前会话，待人工实质review。D3a验收及发布完成，下一D3b同批分红运行桥按正式施工单推进。

资本流代码93d4b7ae18807a745317622526111dccbdef8a7b已推送并建立草稿[PR #24](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/24)，base为codex/world-dividend-runtime。真实verify run42264571970已completed/success，已附当前会话，等待人工实质review，Codex不合并。下一阶段Web正式集成沿五key配置Plan，私有候选的严格输入/身份切换修复进行中。

## 2026-11-07 Web 工作台当前验收

分支codex/world-web-workbench base8311e7a。资本PR24代码及文档CI均已实际成功，仍OPEN未合并。新增7生产/3资源/7测试类，原79生产与858旧测试保持。最终candidate3冻结86源，Root4项缺陷RED已修：失败启动退出码、满队列自关闭、倒退时间400、无决定模块的屏障查询。902标准/43成交119订单7排名/GUI与JavaDoc03:05:52通过，Root24独编HTTP（含超2^53真实数值）通过。candidate2真实Hermes56独审全绿，candidate3一行闸增量独审进行中。

真实浏览器已实点：登录/推进、五场景保存、默认遮罩、dialog显式查看与自动清除、离开页清除、切alice后管理DOM清空、刷新保持PERSON、轮换引导返管理员、同一世界及key-5/调用3配置重启保留。Postman22请求56断言通过，仅dummy凭据、禁止远端报告。原离线审计HTML仍待用户手动检查，不混同。

Root拥有全部主树/Maven；作者aeafaa仅拥有deepseek-adapter-preparation私有目录（exec26122）修Root7项中5个边界失败，不能用Temp/真实keys/主树/Maven。审查849817仅拥有web-independent-final私有目录（exec9516）复核candidate3，不能Maven/主树。真实五keys未发提供方请求。DeepSeek适配器只是私有准备，未集成世界，成本未知不能填0当账单。总体基线继续施工，下一发布Web草稿PR/实际CI后接正式适配器和Web业务链。

最终candidate3真实Hermes增量独审已完成：86源hash一致，独立重编后64/64通过；同探针对candidate2取得6个cash-only失败，证明模块闸修复有效。Root24独编HTTP、902标准/43成交119订单7排名、GUI/JavaDoc及真实浏览器均已通过，现发布草稿PR，等待实际CI；总体基线继续施工。

Web工作台代码3bbf337c8cbb87ef07ca2546a082ac73ee56e075已建立草稿[PR25](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/25)，base为codex/world-capital-performance；实际[CI42277171062](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42277171062)已completed/success。最终独审64/64。下一按2026-11-07-deepseek-provider.md继续，私有修复已由Root独立重新编译后7/7通过；真实keys未调用。Root另读出可选cache字段非法类型被当缺失、超int范围choice index可能截断为0两处待正式RED核验。整体基线未完成，无自动合并。

DeepSeek候选6436d61e…a2cbc最终真实Hermes独审已补正：每个index反例有含合法usage的成功基底，独编42/42；相同探针对旧客户端33/42，9失败包括两个截断为0的整数，先前37项中5个index断言因缺usage是假覆盖，原报告已另存superseded。Root独编10与纠正版42、925标准/47成交126订单7排名/GUI/JavaDoc及单次真实51token均通过。当前发布适配器草稿PR并等待实际CI，世界运行桥与总体基线继续施工。

有界提供方代码bd07ba0d6ae636c32c968e67b3531b52453b138d已建立草稿[PR26](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/26)，base为codex/world-web-workbench；实际[CI42280670472](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42280670472)已completed/success。A传输阶段验收完成，仍待人工review；下一按2026-11-07-live-decision-core.md实施现有DecisionModel的B1版本化用量/预算纯核，私有替代模型的62项不计正式覆盖。世界运行桥未连接，总体基线继续施工。

## 2026-11-07 04:06 B1 当前接续

Root现拥有全部生产/测试/Maven。B1最终candidate3为DecisionModel2d31206a…b82b2、Runtime57cb0782…bf4d；953标准、49成交121订单7排名、GUI、JavaDoc04:06:05、Root24+4、旧二进制JSON精确与同journal新time6/旧time7现金4100已通过。候选1全局重试漏洞与候选2费用状态4反例均有真实RED并修复；旧925断言保持。

真实Hermes afc0c5仅私有live-decision-core-independent终审candidate3（exec10133），不得主树/Maven；aeafaa仅私有live-provider-preparation按新正式B2a Plan准备真实模型测试/旧调用client（exec92035），不得主树/Maven。旧写权83657/22858/49068/58461已释放，不可继续沿历史授权写主树。B1尚待最终独审和PR/CI；B2a正式Plan为2026-11-07-scene-decision-budget.md，未授生产。真实keys仅此前一次51token，本轮未调用。总体基线未完成。

B1最终candidate3已由真实Hermes afc0c5独立核87源并重编：原40+补充2+新增费用15，共57项动态纯核断言全过；另11静态检查，Runtime部分仅静态，未冒称其做过动态测试。Root的Runtime配置2对照2拒绝及正常/复用/旧代次真实journal隔离测试独立成立。独审报告将Root“4项Runtime RED”混称，实际是Runtime测试类4测试，其中新增1测试含3入口分支；真实RED为该新增测试，数量以JUnit日志为准。发布前实际五key逐字扫描199个仓库文件通过。当前本地验收齐备，准备AI草稿PR，人工review与CI待远端事实。

真实模型预算纯核代码269e854d88f840f3c7890b5a441ec2333addb3d6已建立草稿[PR27](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/27)，base为codex/world-deepseek-provider；实际[CI42285963075](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42285963075)已completed/success，已附当前会话。本地953/标准GUI/CSV/JavaDoc和独审57通过，B1工程验收完成，仍待人工实质review，未合并。下一按2026-11-07-scene-decision-budget.md继续B2a。Root已拒收私有新测试的无效预算/输出边界/不存在动作超额等夹具，要求修正，不把缺API RED当语义正确。

## B2a 测试先行写权（B1 PR27 CI之后）

当前codex/world-scene-decision-budget base6db6963；B1代码269e854/PR27实际CI42285963075成功，文档6db6963的CI42286406476仍进行中。aeafaa exec86025仅新DecisionModelRouteTest及Maven独占，87生产尚未授改。Root已拒收9类私有夹具（scripted零费用误判UNKNOWN、非法Route先抛、scene cap未入Route、x2不存在、conditional跳过伪造等），详细反馈live-provider-preparation/b2a-root-test-review.txt；不得把缺API编译当语义已通过。afc0c5 exec9628仅scene-decision-independent准备独立实际模型探针。Root负责文档、契约和独立验收，不并发跑Maven或改新测试。下一接回正式测试核对，再授DecisionModel单文件实现；不启用网络或真实keys。

## B2a Root测试核准与新作者写权

旧aeafaa exec86025已退出，主树正式测试14项仍有cap256却要求1168预留成功、把run/person输入1100当总cap、REJECTED空decision解引用和可跳过retry断言。Root接回后纠正这些新测试夹具，并补7项真实场景边界/UTF8/低cap恢复retry/冻结slot-model-output/实际退款不退次数/按plan输出超额/跨scene全局revision，共21项，缺API RED在scene-decision-root/formal-root-corrected-red.log；原953断言不改。

旧作者已有1042消息且连续两轮夹具错误，本模块改用新真实Hermes实施上下文（exec75641，session ID待日志返回）。新作者仅DecisionModel.java与scene-decision-author私有目录及Maven独占；新21测试只读、其它86生产不授改，预算80轮2400秒。Root不并发Maven或写DecisionModel。afc0c5独审准备51项已交且exec9628结束，未跑正式候选，不计通过。旧aeafaa不再拥有任何主树/Maven。

Root另用B1最终冻结类独立编译旧16/10构造客户端，class哈希31e0495d…87f05、完整JSON指纹90a7032d…8bf76，真实运行合法WAIT且无plan字段；后续不重编同class以验新代码。PR27文档CI42286406476也实际success。B2a仍施工，未新发真实key请求。

## B2a最终本地验收及接续（2026-11-07 04:35）

候选2固定87源，DecisionModel768a4ee6…fa8c5，Runtime保持B1。23新增专项、976标准、CSV44/123/7、GUI及JavaDoc04:35:08通过；Root23独编及旧编译客户端完整JSON90a7032d…bf76一致；真实Hermes afc0c5独编58/58并核87源一致。候选1两个恢复cap漏洞均实际RED后修，降低cap合法恢复对照保留。

Root现拥有全部主树/测试/Maven。新作者ed3b0f实施进程75641已退出，只读B2b准备exec51627，仅live-runtime-preparation私有目录可写，禁止主树/Maven/Temp/keys；afc0c5最终独审40912已退出，无在写任务。旧aeafaa无任何写权。本单当前发布AI草稿PR及等待实际CI，下一B2b持久派发/schema10，B2c真实Web角色调用。此前唯一真实key请求51token，没有新增调用；总体基线继续施工。

场景预算代码a883e99192fd7822e9d5861db5560a86d436075f已建立草稿[PR28](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/28)，base为codex/world-live-decision-core；实际[CI42289821622](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42289821622)已completed/success，已附会话。本地976及Root23/Hermes58通过，仍待人工review、不自动合并。下一按2026-11-07-live-decision-runtime.md实施B2b。作者ed3b0f只读准备exec51627已退出，当前Root独占主树/Maven。


## B2b正式测试先行写权（2026-11-07 04:42）

分支codex/world-live-decision-runtime base cb5afcd。B2a PR28代码CI42289821622实际success，文档CI42290156095待核。正式Plan为2026-11-07-live-decision-runtime.md。作者ed3b0f exec6182仅WorldRuntimeLiveDecisionTest/DecisionRunDispatchTest新两类及Maven独占，87生产只读；Root不并发Maven。独审afc0c5 exec15390仅live-runtime-independent私有准备，禁止主树/Maven。

Root另用B2a冻结类编译实际旧DecisionRun4/Request3/Call8客户端，class BDCA1248…8E1BF，完整JSON SHA f6f6081b7270fad7670f80c84cfedaa4203cf9c319f97a2fc43d302ef32bc871、ROUNDTRIP true。后续直接同class连新类，不能重编冒充旧二进制。Root三JVM派发探针已准备，API未实现，尚未运行，不记通过。

### B2b Root测试核准与生产写权

作者测试exec6182与独审准备exec73284均已退出。Root修正新增测试的JSON层级、实际变异计数、零预算旧档对照、非空派发历史保留、run/scene并发隔离，新增未派发旧回执和同批守恒资金搬移/互斥事件反例；当前17Runtime+6Dispatch=23，真实缺API RED为live-runtime-root/formal-root-corrected-red.log。原976未改，87生产此前保持。

现作者ed3b0f exec26075仅RunManifest/DecisionRun/WorldRuntime及Maven独占（100轮3600秒）；两个新测试只读，其余生产含DecisionModel只读。Root不可并发改这三生产或跑Maven。独审afc0c5已交真实Runtime13+恢复7探针，只在旧冻结类编译证明缺新API，尚未行为运行；冻结候选后再授独审。Root三JVM/旧编译客户端证据保持。

作者首轮日志曾出现/tmp/red1.txt重定向，但handoff称未写Temp；Root已要求据实际日志更正，未授权扫描/清理外部临时目录。后续只用项目私有目录。B2a PR28文档cb5afcd的CI42290156095也已实际success。无新增真实key请求，总体Web基线继续。

### 并行Web只读页面候选（私有）

另开真实Hermes Web上下文（exec78088，session待退出日志），只拥有web-details-preparation私有镜像：以B2a冻结87源准备公司经营/治理、人物任职、信息阅读、账户表现与历史页面。禁止主树/Maven/target/Temp/keys/网络；仅可运行自己的回环dummy验证。用户最终Web目标不变，私有候选尚未集成，不能记功能完成。它不会与ed3b0f的三世界生产文件/Maven写权重叠；B2b验收后再核对合入页面，B2c真实runner仍另接。

### B2b 最终本地验收

- [x] schema10严格档案与持久一次派发、完整原命令逐帧审计及失败JSON；26新增/1002标准、40成交126订单7排名、GUI/JavaDoc。
- [x] Root三JVM28、真旧二进制4/3/8 JSON不变、旧schema9新time8/旧time9续写；真实Hermes独立26动态检查通过，最终Runtime6f3f1aa3…a21e532。证据docs/05§32。
- [x] 草稿PR29代码f56dea4及实际CI42296162891成功；仍待人工review，AI不代替人工合入。
- 下一：Web四组只读业务详情私有候选105检查与正式测试集成；[B2c真实Web闭环准备](2026-11-07-live-web-runtime.md)明确固定首次接收、期限竞态和有界轮次，当前未授生产。总体基线继续。

Web详情代码90e726a70c7d53b16cc255e6b897e56f1ca0ac81已建立草稿[PR30](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/30)，base为codex/world-live-decision-runtime，已附会话。实际[CI42299095951](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42299095951)已completed/success。1010标准、Root105 HTTP及准确400/401、真实浏览器与独审82通过。本阶段工程验收完成，待至少一名人工review，不自动合并。

B2c2代码b2fb23d1f209b0466448744df2e6a3c580bdc7c5已建立草稿[PR32](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/32)，base codex/world-live-completion，已附会话。实际[CI42308822586](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42308822586) completed/success；1085标准/GUI/CSV/JavaDoc、74独审与最终JS复核及Root浏览器/容量/故障证据齐备。本单工程验收完成，等待人工review，不自动合并。下一M4f回忆纯核候选90源/14新增、Root14反例GREEN，仍待正式全套与独审，未接世界。

回忆纯核代码4809bef4665128349b9296d09d1feade825d38dc已建立草稿[PR33](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/33)，base codex/world-live-web，已附会话。实际[CI42310013886](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42310013886) completed/success。首推遇GitHub Internal Server Error，经ls-remote核对无分支后再次推送成功，未变提交。1099标准及95独审/14Root通过，纯核工程验收完成，仍待人工review。
下一施工单docs/superpowers/plans/2026-11-07-cognition-runtime.md。准备会话20261107_070330_f270fe初轮60工具到限，续90910报告完成；Root纠正默认模式仍live、角色DTO不得带Policy/score或全局READ-n。新schema11冻结策略、两条全文路径同时裁剪、本人scoped阅读引用、管理员独立审计是下一硬边界。Root独占主树/Maven，作者尚未获得主树写权。

草稿PR34：https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/34；代码2f206aa7da89d2677a955f8ba87c6a548b624398，base codex/world-cognition-recall，实际CI42314108569成功。SSH推送两次服务器500失败后，同一已授权仓库HTTPS推送成功；未改远端配置、未合并。

### M4g.1本地验收与M4g.2接续（2026-11-07 10:01）

- [x] 信念纯核37新增，真实阅读/精确目标/明确采纳/更正/有效贡献重算/有版本习惯与不可变投影；Hermes98独立动态通过。详见[纯核合同](2026-11-07-belief-core.md)与docs/05§38。
- [x] 最后复跑旧SceneRoutes原子move实际AccessDenied；真实共享句柄RED后小幅修复，2新增回归+自然2000保存全过，正式1156/CSV51/132/7/GUI/JavaDoc10:01:01通过。保留旧1154错误日志；最终独审配置增量24动态通过，另Root真实sleep中断通过；草稿PR/CI随后。
- [ ] [M4g.2运行桥](2026-11-07-belief-runtime.md)：schema12、真实观察cut、权威合同数截止、真实阅读学习、后续input与Web审计已实现；1185标准及三JVM/旧客户端/实际Web本地通过，候选2待最终独审、草稿PR/CI。原f270fe作者已交接，Root独占主树/Maven/Web；新真实Hermes exec61411独审，不把作者自测当独审。
- [ ] [M4h.1公开证据采编纯核](2026-11-07-media-editorial-core.md)：Root已核准双人快讯/调查、真实阅读草稿、追加更正与多根来源谱系；Hermes405ace仅media-editorial-author私有镜像实施，主树无写权。后续M4h.2须接真实工具/媒体场景/Web和工时预算，然后实现采访的拒绝与匿名身份显式共享、曝光/群组/社交及其余六类工作流，不把第一步当媒体全部完成。
- [ ] 媒体/传播、多公司银行、图表/历史与长跑验收继续，不以局部纯核冒称总体基线完成。真实供应商0新增，原51token唯一连通证据保持。

信念纯核代码e6a81adfaf3b24c86e507cf32e9c54aac0ed6be1已发布草稿[PR35](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/35)，base codex/world-cognition-runtime；实际[CI42327343664](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42327343664) completed/success。1156标准、98纯核独审+24配置独审及Root实际退避中断通过，工程阶段验收完成，仍待人工review、不自动合并。
