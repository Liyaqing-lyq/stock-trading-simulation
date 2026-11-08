# PR-001 新增价值型 / 逆势型 / 做市商 agent

分支：`feature/agent-diversity` → `main`

## 变更内容

在原有「人类 broker / LLM(AI) / 噪音做市 / 规则型动量」之外，按既有范式新增三类规则型 agent，
补上价格发现里缺失的估值锚与均值回归对手盘：

- `agent/ValueAgent`：以昨收为估值锚，偏离 ±2% 时低买高卖；不为高于估值的价格买单、不在低于估值时卖出；
- `agent/ContrarianAgent`：按决策周期采样最近 6 次价格，偏离均线 1.5% 时反向交易（与动量 agent 互为对手盘）；
- `agent/MarketMakerAgent`：围绕中间价双边报价（单边价差 0.4%），报价不穿越对手价，
  持仓偏离目标库存时整体偏斜（最大 0.3%），每轮撤单改价。

配套改动：`AgentType` 增加 `VALUE / CONTRARIAN / MARKET_MAKER`；`SimulationConfig` 增加对应
数量与初始资金读取；`config.properties` 增加 `agent.{value,contrarian,maker}.*` 配置
（含初始持仓，避免「没有可卖股票→首笔成交永远无法产生」）；`SimulationRunner` 增加三条装配路径；
文档 `01 / 02 / 03 / 05` 与 `README` 同步更新。

原有 agent、撮合、账户、行情、CSV 与 GUI 代码未改动行为。

## 关联评分项

- [x] 核心：撮合 / 多标的 / 账户 / GUI / CSV / 异常校验（本次未改动，仅回归验证）
- [x] 进阶：多线程 / 市价单 / LLM / 撤单
- [x] 新增：agent 多样性与价格发现（价值 / 逆势 / 做市）

## AI 参与标注

- [AI-Generated] `ValueAgent`、`ContrarianAgent`、`MarketMakerAgent` 及其 Javadoc；
  `AgentType` 三个新枚举值；`SimulationConfig` 六个取值方法；`SimulationRunner` 三条装配方法；
  `StrategyAgentsTest`、`NewAgentTypesIntegrationTest`；`config.properties` 配置块；
  本文档与 `docs/05` §5.5。

## 线程安全说明

- 三个 agent 均继承 `AbstractTradingAgent`，行情缓存沿用 `ConcurrentHashMap`，只在 `onTick()` 中决策；
- 不引入新的锁，也不在 agent 内直接访问 `MatchingEngine`：下单/撤单一律走 `OrderGateway`，
  撮合与账户变更仍在引擎单锁内完成（锁顺序仍为「引擎锁 → 账户锁」）；
- 逆势型的价格窗口用 `synchronized (priceHistory)` 保护，但只在自身 `onTick()` 内读写，
  不跨线程共享查询，不会形成新的锁顺序；
- 与 `MomentumAgent` 一致，每轮 tick 先撤旧单（`cancelAllOpenOrders` / 按标的撤单），
  回收冻结资金与持仓，避免额度耗尽后静默停摆。

## 验证证据

环境：Windows 11 / JDK 21.0.12 / Maven 3.9.9。

合入上游最新 `main`（`fdbe62f`，独立 121 项测试）后的完整测试：

```
[INFO] Tests run: 128, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

新增测试：

- `StrategyAgentsTest`（6 项）：价值型低估买入 / 高估卖出 / 锚附近不交易；逆势型跌破均线买入 /
  高于均线卖出；做市商双边报价不穿越对手价且库存偏高时整体下移；
- `NewAgentTypesIntegrationTest`（1 项）：真起线程、真进撮合，制造 -6% 价格冲击后，
  断言三类 agent 都真的下单、市场产生成交、所有账户现金与可卖数量非负。

真实运行（`ConsoleApp 12`，默认配置 10 个 agent）：

```
  价值型-1[value-1 类型=VALUE 下单=5 被拒=0 成交=810股 撤单=0 存活挂单=1]
  逆势型-1[contrarian-1 类型=CONTRARIAN 下单=4 被拒=0 成交=448股 撤单=0 存活挂单=2]
  做市商-1[maker-1 类型=MARKET_MAKER 下单=82 被拒=0 成交=3839股 撤单=59 存活挂单=8]
```

GUI 实启截图可见排行榜中的 `价值型-1 / 逆势型-1 / 做市商-1`，成交明细中出现 `maker-1` 的双边成交。

## Reviewer 检查点

- [ ] 新 agent 是否都订阅了行情（`SimulationRunner` 统一订阅 `MarketDataListener`）
- [ ] 报价刷新是否真的回收冻结额度（做市商每轮按标的撤单重挂）
- [ ] 做市报价是否可能穿越对手价（已夹到 `bestAsk - 0.01` / `bestBid + 0.01`）
- [ ] 价值型是否可能以高于估值/低于估值的价格成交（买单上限为锚价、卖单下限为锚价）
- [ ] 逆势型的采样窗口是否按决策周期而非行情快照（§5.5 记录了首版失败原因）
- [ ] 新配置缺省时（`agent.*.count` 缺失）是否保持 0 个，不影响既有测试
