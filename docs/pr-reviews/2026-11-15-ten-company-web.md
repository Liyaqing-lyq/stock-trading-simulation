# 十公司世界：审阅入口

[AI-Generated] Codex负责组合、资金/权限接线和实际Web验收；Hermes承担隔离模块与有界独立边界检查。AI独审不替代人工review。运行结果和失败修复详见[记录§108](../05-运行与验证记录.md)，操作边界见[十公司调试说明](../design/十公司开局与调试边界.md)。

## 解决的问题

各行业原来分散在纯模型和增量档中。新 `world-baseline` 把三制造、单工程、银行、研发、资源、水电、数字和陈酿接到同一个Java世界、实际资金与Web，明确保留旧档规则。主开局没有预置经理和预算。网页允许符合真实任职的人物选择其公司，真实调用、排程、完成、拒绝和恢复分别可查。

## 建议审阅顺序

| 重点 | 源码入口 | 应检查的反例 |
| --- | --- | --- |
| 十公司身份与初始财产 | `BaselineIndustryConfig`、`ComposedIndustryScenario`、`ComposedWorldScenario` | 去除重复发行人后股份是否守恒；投资资金和外部客户现金是否有限；银行owner/账户是否一一对应 |
| 行业付款和完整报表 | `AgedOperations`、`DigitalOperations`、`HydroOperations`、`ResourceOperations`、`ComposedCompanyReport` | 资金冻结、存款、应付和权益是否重复计入；预算拒绝时是否丢弃整批候选；预收是否被错误当成收入 |
| 权限与经营动作 | `WorldRoleSession`、`WorldRuntime` 的行业执行与 `governanceCompanies` | 开始后撤权/任期到期；跨公司动作；未任命候选；系统关期、终端购买、阶段完工能否被人物伪造 |
| 同时点与恢复 | `CompanyOperationsRun`、`PendingTask`、原命令重演与行业恢复测试 | 原命令不变而重写快照、事件与SHA；进程中断后重复扣款；父子世界是否共享可写账目 |
| 角色输入与Web | `WorldWebHost`、`WorldWebServer`、`operations.js`、`app.js` | 经理是否可见未来需求/来水/成功标记；God切人物后缓存与迟到响应；清空人物能否继续选择独立董事 |

`WorldRuntime` 是既有权威提交与重演入口，本次没有拆出第二个账本。旧连续竞价价格时间优先、被动方成交价、金额BigDecimal和数量long等课程口径保持。审阅重点应放在新增行业与既有账户、银行、董事预算和排程的交界。

## 可复现检查

专项入口：`BaselineIndustryWorldTest`、`BaselineIndustryWebHttpTest`、`BaselineIndustryRecoveryTest`、`BaselineGovernanceLiveTest`。新增独立反例保留为 `IndependentAgedBoundaryTest`、`IndependentDigitalBoundaryTest`、`IndependentBaselineAuthorityTest`、`IndependentJournalTamperTest`。网页身份/迟到响应/治理选择和银行检查继续使用原断言，测试夹具补齐新增页面依赖。

本地最终正式 `bash scripts/verify.sh --with-gui` 实际退出0：261套2193项零失败/错误/跳过，CSV39笔成交/149条订单/7行排行复算、全部Node与真实JavaFX启动通过。JavaDoc另行退出0。最终冻结17共562文件，生产源码与已运行runtime-v15一致；远端CI与人工review另列。Hermes新增15项独立探针的通过范围见§108，任务本身因轮数上限退出1，不伪称独审进程正常结束。

端口占用测试现在持有操作系统分配的端口，并把启动器的整个测试范围限定为该端口。这样仍检验 `PORT_TAKEN`、子档保持 `CREATED` 和既有监听者不被关闭，同时允许真实调试子世界在18100运行；没有停止真实服务来让测试通过。

真实浏览器证据：主世界27/1ms；从原开局23派生子世界，推进30日到42/2592000001ms，停止/重启后账目与42项待办一致，父世界不变。一次真实数字董事判断返回WAIT，输入2655/输出6 token，费用UNKNOWN；五场景结束后关闭。

## 未支持的主张

内部调试可用不等于总体基线最终完成。退出/新注资等生命周期、全公司授信、整体行为/压力分布和现实参数校准仍开放。独审没有覆盖所有不同人物换任、真实撮合冻结后付款和全部网页组合；子模型固定能力身份与世界原命令中的实际发起人须区别。任何观望、无成交或经营失败都应保留，不应通过预置权限、暗补现金或强制反向交易制造成功。
