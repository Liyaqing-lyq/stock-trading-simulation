# 项目协作指令

本文件是本仓库的上下文规则文件，适用于所有在本仓库内工作的会话。

## 项目记忆边界

- 在本仓库开始实质性工作前，必须先读 `docs/agent-memory/PROJECT_MEMORY.md`。
- 仓库相关的长期知识写在该文件；Hermes 全局 `MEMORY.md` 只保留跨项目的用户偏好。
- 可变事实（数量、状态、进度）以当前代码、配置、测试和数据为准。

## 项目性质与交付要求

- 这是中央财经大学《Java 程序设计》课程项目：多智能体连续竞价股票交易模拟系统。
- 原始需求文档：桌面 `Java实验作业/course project.docx`，项目内的拆解见 `docs/01-需求与评分拆解.md`。
- 评分 = 核心功能 60 + 进阶功能 30 + 文档 10；所有代码变更必须走 PR 且至少一人 review，
  AI 生成的改动要在 PR 描述里标注 `[AI-Generated]`（详见 `docs/04-开发计划与PR流程.md`）。
- 保持评分表与实现的可追溯性：任何新功能落进代码后，同步更新 `docs/01-需求与评分拆解.md` 的完成状态。

## 构建与运行（本机 Windows / git-bash）

环境：JDK 21（`C:\Users\JXGU\DevTools\Runtimes\Java\current`）、Maven 3.9.9
（`C:\Users\JXGU\DevTools\Runtimes\apache-maven-3.9.9`）。

- **推荐**：在 cmd / PowerShell / IntelliJ 里用仓库自带的 wrapper
  `mvnw.cmd clean test`、`mvnw.cmd javafx:run`，无需安装 Maven。
- 在 git-bash 里，Maven 自带的 POSIX `mvn` 脚本会因为 MSYS 路径不转换而报
  `ClassNotFoundException: ...classworlds...`。改用 shim：
  `"C:/Users/JXGU/DevTools/Runtimes/apache-maven-3.9.9/bin/mvn-msys" test`。
- 控制台演示：`mvnw.cmd exec:java -Dexec.mainClass=edu.cufe.auction.app.ConsoleApp -Dexec.args="15"`。
- Maven 输出在控制台是 GBK 编码；在 git-bash 里查看需 `iconv -f GBK -t UTF-8`。

## 代码约定（不要静默改变）

- 金额一律 `BigDecimal`：金额 scale 2、成本价 scale 4，禁止 `double` 参与记账。
- 数量一律 `long`。
- 线程安全：撮合的读改写必须发生在 `MatchingEngine` 的同一把锁内；锁顺序固定为
  「引擎锁 → 账户锁」，账户层不得回调引擎；行情广播与 CSV 落盘一律在锁外执行。
- 成交价口径：按被动方（挂在簿上的那一方）价格成交，买方获得价格改善。
- 收益率口径：以「初始权益 = 初始资金 + 初始持仓成本」为基准，不得退回用初始资金。
- 新增 agent 必须订阅 `MarketDataPublisher`，否则拿不到行情、整个市场不下单。
- 公共 API 必须有中文 Javadoc（JavaDoc 是交付物之一）。

## 完成标准（阶段性）

一个阶段只有在以下证据齐备时才可宣称完成：

1. `mvnw.cmd test` 全绿（当前 35 项），失败必须定位根因后修复，不允许删测试或放宽断言掩盖；
2. 控制台演示真实跑出成交与排行榜（贴真实输出，不贴推测结果）；
3. GUI 改动需实跑 `mvnw.cmd javafx:run` 并确认启动日志与无异常；
4. 与评分表对应的功能项在 `docs/01-需求与评分拆解.md` 中更新状态。
