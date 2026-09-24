# 多智能体连续竞价股票交易模拟系统

Java 程序设计课程项目（中央财经大学）：用多智能体模拟真实交易所的**连续竞价**机制——
没有外部行情源，价格完全由订单撮合"涌现"出来。

## 快速开始

需要 JDK 17+（本机为 JDK 21）。仓库自带 Maven Wrapper，**无需安装 Maven**。

```bash
# 0) 一键验证：测试套件 + 从 CSV 黑盒复算（加 --with-gui 会短暂弹出界面）
bash scripts/verify.sh --with-gui

# 1) 编译并跑测试（35 项）
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
- **多标的**：每个标的独立订单簿（AAPL / GOOGL / TSLA）。
- **账户**：初始资金 + 初始持仓、下单冻结/成交结算、摊薄成本、已实现盈亏、权益、收益率、胜率、最大回撤。
- **行情广播**：成交与盘口快照推送给全部监听者（GUI、CSV、agent），监听者异常被隔离。
- **Agent**：人类 broker（GUI/控制台手工下单）、LLM 交易员（DeepSeek，含限流/价格边界/JSON 校验/失败回退）、
  规则型动量 agent（对照组）、噪音做市 agent（提供初始流动性）。
- **多线程**：agent 在调度线程池中并发决策，撮合走单锁临界区，锁外做广播与落盘。
- **持久化**：`data/orders.csv`、`data/trades.csv`、`data/ranking.csv`。

## 目录结构

```
src/main/java/edu/cufe/auction/
  model/    不可变领域对象：Order/OrderRequest/OrderResult/Trade/快照
  engine/   MatchingEngine（撮合）、OrderBook（订单簿）、OrderGateway（下单接口）
  account/  Account/AccountManager/Position/PerformanceTracker/PerformanceRank
  market/   MarketDataPublisher / MarketDataListener
  agent/    TradingAgent 及实现：Human / Noise / Momentum / ai（LLM 交易员）
  io/       CsvHistoryRecorder
  config/   SimulationConfig
  sim/      SimulationRunner（装配与调度）
  app/      ConsoleApp（控制台演示）
  gui/      AuctionApp（JavaFX 界面）
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
   - `app/ConsoleApp`、`gui/Launcher` 可以直接右键 Run（走 classpath）；
   - ⚠ **不要直接 Run `gui/AuctionApp`**：它继承 `javafx.application.Application`，JavaFX 启动器
     会要求 JavaFX 位于 *module-path*，而 Maven 依赖是放在 classpath 上的，实测报
     `错误: 缺少 JavaFX 运行时组件`。想在 IDE 里一键启动就用 `gui/Launcher`（它对 classpath 启动友好）。

## 配置

配置文件在 `src/main/resources/config.properties`：标的与参考价、各类 agent 的数量/初始资金/初始持仓、
AI 决策间隔、LLM 端点与限流、输出目录。要点：

- `agent.<类型>.initial.holdings` 是**必需**的（原始规格只给初始资金，会导致市场上没有任何可卖股票，
  首笔成交永远无法产生，见 `docs/01` 第 5.1 节）。
- `llm.api.key=${LLM_API_KEY}` 从环境变量注入，密钥不进仓库。
- 需要 JDK 11 环境时：把 `pom.xml` 的 `javafx.version` 降到 `17.0.10`，并把 `maven.compiler.release` 改为 `11`。

## 文档

| 文档 | 内容 |
| --- | --- |
| `docs/01-需求与评分拆解.md` | 规格逐条映射到代码、评分项状态、规格缺口说明 |
| `docs/02-系统架构设计.md` | 分层、Mermaid 类图与 4 张时序图、线程模型、不变量清单 |
| `docs/03-接口定义.md` | 各接口契约（前置/后置、线程安全、异常行为） |
| `docs/04-开发计划与PR流程.md` | 里程碑映射、后续任务切分、PR 规范与模板 |
| `docs/05-运行与验证记录.md` | 真实运行输出、3 个缺陷的根因与回归测试 |
| `docs/agent-memory/PROJECT_MEMORY.md` | 项目长期不变量（供后续会话/协作者） |

## 已知边界

- GUI 为可运行骨架：深度、最新价、排行榜、手动下单已具备；价格走势图与 P&L 配色待第 12 周阶段补。
- 尚未实现：夏普比率、最活跃交易员、AI 专项排名（规格第 10 节的其余奖项）。
- 远端仓库（GitHub/Gitee）与 PR 讨论需要账号后才可推送；本地 git 历史与 PR 流程已就绪。
- 个人实验报告（Human vs AI 策略对比）待撰写，数据来自 `data/*.csv`。
