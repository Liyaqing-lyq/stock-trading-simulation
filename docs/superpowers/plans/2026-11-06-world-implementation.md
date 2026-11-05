# 股票世界持续实施 Plan

> **For agentic workers:** 按 `superpowers:executing-plans` 逐任务执行；Codex 管理计划、接口和最终验收，真实 Hermes 按明确文件范围承担实现或独立审计。每个任务先阅读本 Plan 与总体设计基线，使用复选框跟踪。

**Goal:** 在保留课程系统的前提下，按总体设计基线逐步实现可审计、可恢复的多公司、多岗位股票世界。

**Architecture:** 单进程 Java 权威状态和唯一世界变更调度器；模型推理与观察可并行，状态转换串行持久提交。旧课程连续竞价模式保留独立入口；新世界先验证时间、身份、账目、恢复，再接公司—媒体—机构—交易—图表链。

**Tech Stack:** 现有 Maven Wrapper、Java release 17（本机 JDK 21）、JavaFX、JUnit 5；沿用现有依赖，未因规划增加框架或服务。

**Spec:** [总体设计基线 v0.1](../../design/总体设计基线.md)，2026-11-05；用户于 2026-11-06 明确要求据此建 Plan、与 Hermes 从现状核验开始，并利用今晚尽可能实施。已有方向直接继承，未定实验参数不得冒称现实规则。

## 全局约束

- 金额 BigDecimal（金额 2 位、成本价 4 位），数量 long；锁顺序引擎锁 → 账户锁，模型、I/O、广播不能持有撮合锁。
- Java 保留账目、交易、时间、权限和动作裁决；首轮单进程，不引入微服务或任意代码热加载。
- 课程模式与扩展规则分开；成交按被动方价格；课程收益继续以初始权益为基准。
- 角色不能凭文本获得权限；人物、组织、财产主体、账户、任职分开；复制配置不复制资产或经历。
- 事件原文和账务不可覆盖，纠错用更正事件；CSV 不是恢复源；失败不能伪造成功。
- 首轮精度 1 模拟毫秒，固定四阶段；新消息最早下一时间步。公平顺序来自运行种子并可记录。
- 用户可见首版须含所启用公司的董事会、独立四岗、遗忘/偏信/习惯和真实图表来源；基础模块通过不代表首版完成。
- 每阶段：专项测试 + `bash scripts/verify.sh --with-gui` + 评分映射 + 真实记录；代码通过 PR、`[AI-Generated]`、至少一名人工实质 review 后才能合入。
- 保留启动时所有未提交改动。当前工作分支 `codex/world-implementation`，起点 `bd0d3aa`。不擅自合入主分支，不提交配置秘密，不调用无上限真实模型实验。

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
| M1 世界运行基础 | 模拟时序/阶段、运行清单、唯一提交、幂等事件日志、恢复与分支边界 | M0 | V10/V11/V12 | 执行中：时间/日志有专项证据，完整运行恢复未完成 |
| M2 身份/开局/交易账 | 人物/组织/账户/任职；开局股份与现金清单；托管映射；版本化规则；T+1 批次/自成交 | M1 | V01/V02/V05/V06/V14 | 待细化 |
| M3 公司经营竖切 | 制造公司、外部客户/供应商、合同履约、现金与报表、董事会、现金分红、表现核算 | M2 | V04/V15/V16/V17 | 待细化 |
| M4 角色与信息竖切 | 独立四岗机构、两类媒体、普通投资者；阅读/传播/权限、六层记忆与偏信/习惯、模型屏障与预算 | M1/M2/M3 | V07/V08/V09/V10/V13/V14 | 待细化 |
| M5 差异公司与银行 | 工程回款、银行完整实验规则、直接/组合敞口迁移、其余十类公司及八类媒体逐项配置 | M3/M4 | V01/V02/V03/V13/V16 | 待细化 |
| M6 真实观察工作台 | 先接增量观察；白色市场/股票/公司/账户/人物机构/新闻/世界页面；同快照、权限裁剪、图表与历史回放 | M1 起增量，完整依赖 M2–M5 | V12/V14/V15 | 待细化；Web 主界面仍为候选 |
| M7 行为/压力与交付 | 无 LLM 故障夹具、固定预算小试、多次独立实验、失败率/分布、课程报告/JavaDoc/PR 人工评审 | 所有启用能力 | V17/V18 及全矩阵回归 | 待细化 |

阶段覆盖的是责任，不表示整条 Vxx 已通过。多公司、银行、媒体、认知目标保留；今晚按依赖推进，不用虚构数据或跳过检查换取页面数量。

## 当前执行任务

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

### M2.1 现金权利子账的提交—恢复竖切（Codex；只覆盖基础子集）

**Files:** 新增 `world/CashLedger.java`、测试目录 `world/CashLedgerTest.java`。本步不迁移旧 Account、不声明已有银行/托管完整对账；用于以真实双边资金变更验证 M1.2 的持久协议。

**Interfaces:** `CashLedger(Path file, String runId, List<OpeningAccount> openings)`；嵌套不可变 `OpeningAccount(accountId, ownerId, cash, source)` / `CashAccount(ownerId, cash, source)` / `Snapshot(position, accounts)` / `TransferResult(accepted, reason, position)`；`transfer(commandId, actorOwnerId, fromAccountId, toAccountId, BigDecimal amount)`；`snapshot()` 返回仅供管理端的完整只读视图；`close()`。调用方负责把可信身份映射到财产主体，不能让未来客户端自报 actorOwnerId。

- [x] 先失败测试：明确历史禀赋来源开局；1000 元从 A 转 100 给 B 后 900+100=1000；重发同命令不重付；重开不重发初始资金；改变开局清单拒绝。
- [x] 先失败测试：余额不足/越权/未知账户不产生单边转移；拒绝也保存结果，后来余额改变后重试仍返回原拒绝；同 ID 异内容拒绝；负数/零/不足分的金额拒绝。
- [x] 实现唯一同步写入者形成不可变候选 → journal 持久确认 → 发布新快照。持久失败不发布；快照读者只见完整版本。金额固定两位，绝不 double 记账。
- [x] 恢复按已记录批次重建并校验结果；夹具直接在 journal 写入一条完整转账批次而不更新 ledger，模拟提交后退出，重开应恢复 900/100，重试仍不重付。关闭后提交失败必须保持快照不变。
- [x] 专项/全套，独立审计。通过仅证明现金子账，不把它扩大为完整 V02/V11 或全部世界检查点。

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
- 方案取舍：总 Plan 采用滚动细化，避免给银行/认知等未到施工点的模块虚设签名。用户已明确执行方式为 Codex + Hermes，且要求今晚持续推进，因此不再增加一轮纯计划批准；涉及改变既定需求/架构或人工 review 合入时仍遵守仓库要求。

## 下一步

把当前已验收增量整理为 `[AI-Generated]` 草稿 PR（不合入）→ 核对 Hermes 的 M1.3 最小施工建议 → 固定运行清单/可序列化待办/唯一提交的接口和失败测试 → 持续实现。M1/M2 只有上述子集有证据，其他任务继续按依赖施工；不把“已派发”写成“已完成”。

