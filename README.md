# 多智能体连续竞价股票交易模拟系统

Java 程序设计课程项目（中央财经大学）：用多智能体模拟真实交易所的**连续竞价**机制——
没有外部行情源，价格完全由订单撮合"涌现"出来。

## 快速开始

设计扩展请先读[总体设计基线](docs/design/总体设计基线.md)与[本轮复审及争议处置](docs/research/2026-11-05-设计复审与争议处置.md)。目标与已实现子集分开记录；下述主界面仍是课程系统，HTML 草图不代表新世界已实现。

持续施工与接续见[实施 Plan](docs/superpowers/plans/2026-11-06-world-implementation.md)。世界基础调试夹具可运行 `mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldFoundationDemo`；同一路径重跑复用原付款回报，不重复发钱。它尚未接入股票、公司、银行或模型。

世界交易、制造、治理及人物阅读实验另有独立入口，均为不调用模型的管理夹具：

```bash
# 制造：先保存已交付但尚未收款的状态，再用另一个进程恢复到期末与回款后
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldManufacturingDemo -Dexec.args="logs/my-company prepare"
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldManufacturingDemo -Dexec.args="logs/my-company full"
# 治理：另开一份日志，预算须表决并实施；强制关期保留当时证据并自动复盘
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldGovernanceDemo -Dexec.args="logs/my-governed-company prepare"
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldGovernanceDemo -Dexec.args="logs/my-governed-company full"
# 人物阅读：保存活动阅读，再由独立进程恢复完成；人物视图裁剪权限和正文
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldRoleReadingDemo -Dexec.args="logs/my-role-reading prepare"
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldRoleReadingDemo -Dexec.args="logs/my-role-reading full"
# 四岗交易保存活动子单，再由独立进程恢复并逐条重发完整计划
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldRoleTradingDemo -Dexec.args="logs/my-role-trading prepare"
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldRoleTradingDemo -Dexec.args="logs/my-role-trading full"
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.WorldRoleTradingDemo -Dexec.args="logs/my-role-trading full"
# 原命令再跑一次不重复收付款。世界存档放logs，课程验收会清理data目录。
```

制造夹具连接同一世界交易和现金日志，治理夹具加入五席预算表决、经营承诺和固定期间复盘。schema6人物阅读夹具保存任职、固定版本、实际阅读和本人经历。schema7四岗交易夹具通过绑定人物会话依次提交研究、经理目标、风控审核、经理确认及真实子单，prepare保留部分成交后的活动单，full恢复到绝对目标60股，第三次启动逐条重发原计划并保持成交与位置不变。以上采用model=none，真实模型、认知、媒体、银行和新世界工作台继续按实施Plan推进。具体证据见[运行记录](docs/05-运行与验证记录.md)。

需要 JDK 17+（本机为 JDK 21）。仓库自带 Maven Wrapper，**无需安装 Maven**。

```bash
# 0) 一键验证：测试套件 + 从 CSV 黑盒复算（加 --with-gui 会短暂弹出界面）
bash scripts/verify.sh --with-gui

# 1) 编译并跑完整测试套件（当前数量见运行记录）
mvnw.cmd test

# 2) 控制台演示：跑 15 秒行情，结束后输出排行榜与 CSV 路径
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.ConsoleApp -Dexec.args="15"

# 3) JavaFX 界面
mvnw.cmd javafx:run

# 4) 生成 JavaDoc（交付物）
mvnw.cmd javadoc:javadoc     # 结果在 target/site/apidocs
```

在 git-bash 中，Maven 自带的 POSIX 脚本会因为 MSYS 路径不转换而报
`ClassNotFoundException: ...classworlds...`，改用仓库外的 shim：
`"C:/Users/JXGU/DevTools/Runtimes/apache-maven-3.9.9/bin/mvn-msys" test`。

启用真实 LLM 交易员（不配置则 AI agent 自动回退为规则型动量策略）：

```bash
export LLM_API_KEY=<你的 key>     # 或用 cmd: set LLM_API_KEY=...
mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.ConsoleApp -Dexec.args="20"
```

## 功能一览

- **撮合引擎**：价格-时间优先；限价单挂簿、市价单扫簿后剩余自动撤销；部分成交；撤单；收盘清算。
- **多标的**：每个标的独立订单簿；默认配置使用 V001 / G001 / T001 / S001 四个 A 股模拟标的。
- **A 股价格制度**：按标的档案执行涨跌停限制和 0.01 元最小变动价位，越界委托由撮合引擎拒绝并返回原因。
- **账户**：初始资金 + 初始持仓、下单冻结/成交结算、摊薄成本、已实现盈亏、权益、收益率、胜率、最大回撤。
- **行情广播**：成交与盘口快照推送给全部监听者（GUI、CSV、agent），监听者异常被隔离；Agent 成交统计以成交广播为唯一来源，覆盖主动与被动成交，自成交按买卖两侧各计一次。
- **Agent**：人类 broker（GUI/控制台手工下单）、LLM 交易员（DeepSeek，含限流/价格边界/JSON 校验/失败回退）、
  规则型动量 agent（对照组）、噪音做市 agent（提供初始流动性）。
- **多线程**：agent 在调度线程池中并发决策，撮合走单锁临界区，锁外做广播与落盘。
- **持久化**：`data/orders.csv`、`data/trades.csv`、`data/ranking.csv`；排行榜包含每个 Agent 的累计成交股数，便于与成交流水交叉验证。

## 目录结构

```
src/main/java/edu/cufe/auction/
  model/    不可变领域对象：Order/OrderRequest/OrderResult/Trade/InstrumentProfile/快照
  engine/   MatchingEngine（撮合）、OrderBook（订单簿）、OrderGateway（下单接口）
  account/  Account/AccountManager/Position/PerformanceTracker/PerformanceRank
  market/   MarketDataPublisher / MarketDataListener
  agent/    TradingAgent 及实现：Human / Noise / Momentum / ai（LLM 交易员）
  io/       CsvHistoryRecorder
  config/   SimulationConfig
  sim/      SimulationRunner（装配与调度）
  app/      ConsoleApp（控制台演示）
  gui/      AuctionApp（GUI 入口）、AuctionUi（JavaFX 界面实现）
docs/       需求拆解、架构设计（类图/时序图）、接口定义、开发计划、运行与验证记录
```

## 在 IntelliJ IDEA 中打开与运行

1. `File → Open`，选目录 `G:\项目\java-stock-auction`（或直接选其中的 `pom.xml`）→ `Open as Project`。
   右侧出现 **Maven** 面板、且模块名为 `stock-auction-sim`，即导入成功。仓库自带 Maven Wrapper，
   不需要本机安装 Maven。
2. **设置 JDK（必做）**：`File → Project Structure → SDKs → Add SDK → JDK`，
   选 `C:\Users\JXGU\DevTools\Runtimes\Java\current`；再在 `Project` 页把 `SDK` 设为它、
   `Language level` 设为 `17`。

   > 实测提醒：本机 IDEA 里原本没有注册任何 Java JDK，不设这一项会全项目标红、无法运行。
3. 运行入口（任选）：
   - **图形界面**：Maven 面板 → `Plugins → javafx → javafx:run` 双击；
   - **控制台演示**：Maven 面板 → `Plugins → exec → exec:java` 双击（默认跑 20 秒）；
   - **测试**：右键 `src/test/java` → `Run 'All Tests'`；或 Maven 面板 `Lifecycle → test`。
4. 关于"右键直接 Run"：
   - **入口是 `gui/AuctionApp`**：它有 `main`、且不继承 `Application`，右键 Run 即可（走 classpath）；
   - `app/ConsoleApp`（控制台演示）同样可直接 Run；
   - ⚠ 别直接 Run `gui/AuctionUi`（界面实现）：它继承 `javafx.application.Application`，JavaFX 启动器
     会要求 JavaFX 位于 *module-path*，而 Maven 依赖在 classpath，实测报「缺少 JavaFX 运行时组件」。
     入口与实现分离正是为了让"随手点入口"这件事不会失败。

扩展世界提供`DecisionModel`及schema8决定运行桥，支持冻结人物输入、真实推进屏障、双层预算、有界异步回执和恢复。`WorldDecisionDemo prepare|full <journal>`可按prepare/full/full在三个独立JVM复跑。当前提供方为scripted，费用0.00。新进程须用新的宿主命令接续，队列满时runner保留结果供retryPending重投。标准573项、持久回执审计、真实旧schema7/8交叉续写回读的证据见`docs/05`第20–21节。真实模型、六层认知和完整世界继续按实施Plan推进。

现金分红的`DividendModel`已提供权利登记、除息应收、持有人减值和完整清偿元数据纯核。43项专项及616项组合标准验证、真实证券夹具复算见`docs/05`第22节。实际公司出款、董事会授权验证及NAV/TWR继续按D1–D3接入。

`ValuationModel`与`PerformanceModel`提供明确截止的账户估值、缺价状态、资本流剔除的时间加权收益与回撤。48专项和664项组合标准、CSV/界面及独立复算见`docs/05`第23节，实际世界接线继续沿实施Plan推进。

`BoardModel`已支持现金分红提案、三票授权、关联回避与来源恢复核验。47专项及旧档恢复回归、712项组合标准、真实旧客户端兼容证据见`docs/05`第24节。分红运行闭环按D3继续施工。

制造报表已提供分红义务投影，估值模块可从已确认方案派生除息参考。32专项、744项组合标准及独立账务复算见`docs/05`第25节，实际世界付款与资本流继续接线。

现金分红世界桥提供schema9的实际授权、PUBLIC公告与阅读、登记、除息、足额原账付款及持有人减值。`WorldDividendDemo <目录> prepare|full|replay`为受信管理脚本，支持同一目录依次prepare/full/full在三个独立进程复跑。输出公司资产、负债、权益、利润及世界现金和股份。72专项、816项标准及独立恢复/账务核验见`docs/05`第26节，类型化资本流和运行表现见下一段。

## Web 实验工作台

在本机启动（JDK 21）：

```powershell
.\mvnw.cmd compile exec:java "-Dexec.mainClass=edu.cufe.auction.app.WorldWebApp"
```

打开启动窗口显示的本机网址，输入一次性管理员引导凭据。默认读取用户指定的外部五Key文件，不把API Key粘贴到登录框。引导凭据每次登录后轮换，返回管理员时使用控制台新值。工作目录默认为logs/web-workbench，重启沿用同一世界。

可观察世界、账户、成交与分红，切换alice/bob/carol/dave/erin/investor人物视角，配置五场景的模型/Key槽位/预算。Key默认遮罩，显式查看15秒后清除。当前为内部调试版，真实模型尚未接入，保存场景不会产生API调用；完整经营操作和自主世界继续实施。902项标准及浏览器证据见docs/05第28节。

## 配置

配置文件在 `src/main/resources/config.properties`：标的与参考价、各类 agent 的数量/初始资金/初始持仓、
AI 决策间隔、LLM 端点与限流、输出目录。要点：

- `agent.<类型>.initial.holdings` 是**必需**的（原始规格只给初始资金，会导致市场上没有任何可卖股票，
  首笔成交永远无法产生，见 `docs/01` 第 5.1 节）。
- 每个标的可配置 `<SYMBOL>.limit.percent` 和 `<SYMBOL>.tick.size`；默认分别为 `10` 和 `0.01`。
- `llm.api.key=${LLM_API_KEY}` 从环境变量注入，密钥不进仓库。
- 需要 JDK 11 环境时：把 `pom.xml` 的 `javafx.version` 降到 `17.0.10`，并把 `maven.compiler.release` 改为 `11`。

类型化资本流已接入新档案的同一现金账，管理端可用 `scheduleCapitalFlow`、`capitalFlows`、`performance` 查询真实出入资与逐帧投资表现。追加资金不计盈利，分红付款不重复计收益，减值回撤保留。42专项、858标准及独立复算见 `docs/05` 第27节。最终交付已确定为Web，上帝五key配置与内部工作台已接入，完整自主工作台继续按Plan施工。

## 文档

| 文档                                    | 内容                               |
| ------------------------------------- | -------------------------------- |
| `docs/01-需求与评分拆解.md`                  | 规格逐条映射到代码、评分项状态、规格缺口说明           |
| `docs/02-系统架构设计.md`                   | 分层、Mermaid 类图与 4 张时序图、线程模型、不变量清单 |
| `docs/03-接口定义.md`                     | 各接口契约（前置/后置、线程安全、异常行为）           |
| `docs/04-开发计划与PR流程.md`                | 里程碑映射、后续任务切分、PR 规范与模板            |
| `docs/05-运行与验证记录.md`                  | 真实运行输出、3 个缺陷的根因与回归测试             |
| `docs/agent-memory/PROJECT_MEMORY.md` | 项目长期不变量（供后续会话/协作者）               |

## 已知边界

世界工作台新增有界 `DeepSeekClient`：固定官方地址、单请求不重试、输入/响应体与总等待时间均有限额，严格区分完整回答、截断和技术失败。它尚未接入世界决定执行，场景配置仍显示未连接；真实用量及独立验证记录见 `docs/05` 第29节。

- GUI 已具备深度、最新价、涨跌停价格区间、排行榜、手动下单和价格走势图；P&L 配色待第 12 周阶段补。
- 尚未实现：夏普比率、最活跃交易员、AI 专项排名（规格第 10 节的其余奖项）。
- 世界扩展通过标注AI生成的草稿PR交付，每项代码改动均须至少一名人工review后合入。
- 个人实验报告（Human vs AI 策略对比）待撰写，数据来自 `data/*.csv`。
本机Windows部署成功，可以正常启动GUI模拟交易界面。

真实模型用量纯核已区分“账单未知”和免费脚本，调用前预留运行及个人预算，超额冻结后续尝试。953项标准、真实旧存档交替续写及兼容证据见docs/05第30节；真实场景执行和完整Web世界仍在实施。

场景计划已冻结模型、Key槽位和输入/输出预留；换槽位不会重置累计用量，降低限额会阻止超预算的新尝试。976项标准、58项独审与旧构造兼容证据见docs/05第31节。持久派发及Web真实角色调用继续实施，当前配置不自动发出网络请求。

真实模型持久派发已接入独立schema10档案：只有日志确认后才返回一次调用许可，重启不会重发同attempt或恢复已耗预算。1002项标准、真实三进程恢复和旧档案兼容见docs/05第32节。网络完成与Web角色轮次继续B2c，保存场景配置仍不自动调用模型。
