# 治理结果上下文 Implementation Plan

> **For agentic workers:** Root按superpowers:subagent-driven-development协调独立审查；被分配的单一实现者内联执行这些步骤，不派子代理。

**Goal:** 在新输入合同中准确连接本人实施请求与公司当前决议事实，保持旧档和业务执行不变。
**Architecture:** 单独的只读投影helper，显式v5/factory分支；原事实与原失败仍权威，版本接缝统一处理相对复查。
**Tech Stack:** 现有Java/Jackson/JUnit与WorldRuntime，不加依赖。
**Spec:** ../specs/2026-11-16-governance-outcome-context-design.md

## Global Constraints

- 新role-context为deliberation-v5；旧openInformedOperating仍v4和web-informed-operating-v1；新工厂openContextualInformedOperating(Path)，runId=web-informed-operating-v2。
- Java唯一账务/权限/时间权威；金额BigDecimal、数量long；旧格式/原文/输入顺序/预算用量不迁移或重置。
- 原accepted/reason/execution不改；不选择董事票、人选、预算金额或经理动作；不暴露未授权/未来信息。
- 全部工作只在隔离clone；Task12接受并释放源写权之后才实施。子代理不调用真实模型、不读密钥、不启动/改原709调用档。

## Review Focus

- 已实施尚未生效任职不得当作空缺未任命：边界测试比较startsAt前/等于/结束及撤销。
- 模型自己声称的成功或伪造basis不得产生新上下文：测试无ActionRef、非本人attempt、技术失败和参数不匹配。
- 同名会议跨公司不得串联：权限/目标匹配回归。
- v5相对复查与纯时钟不唤醒：Host测试；现有完整旧v4字节/跨旧JVM边界保持。
- 新probe证据不能绕过旧mode/source/prompt/physical0/sidecar验证：seed跨版本拒绝测试。

### Task 13A：v5只读上下文与实际规划条件

Files: WorldRuntime.java、RoleDeliberation.java、RolePlanningContext.java、RunManifest.java、ComposedIndustryScenario.java、LiveDecisionProvider.java、WorldWebHost.java；新增窄helper RoleGovernanceOutcomeContext.java（可按现有包内规范落名）及对应JUnit。原BoardModel/业务Report/persistedDTO不改。

接口：消费本人DecisionRun已接受决定与当前同帧董事合法投影/真实Roles状态；产出只附加在本人结果行的targetMeetingContext。所有字段与精确版本见Spec与task-13-diagnosis.md§9。解析严格工具args不产生任何排程/执行副作用。

- [ ] 写两董事同观察ADOPTED并依次完成implement的失败回归；实际一次成功、一次旧NOT_ADOPTED后，要求新上下文标目标IMPLEMENTED。先运行确认缺上下文而失败。
- [ ] 实施显式v5常量、精确版本谓词、manifest合法配对与新工厂，不改旧工厂数据。统一所有直接v4规划判断点，包括历史声明、reviewAt、provider提示和wakeFacts。
- [ ] 实施窄上下文关联，按真实角色/会议和任职记录区分当前效果，保留原反馈。
- [ ] 预算proposedLines非空回归先RED后补v5规划PROPOSAL_EXISTS；空提案/v4控制保持。
- [ ] 覆盖ReviewFocus和Spec的时间/身份/隐私/原字节边界，运行受影响RoleDeliberation/RolePlanningContext/RoleActionFeedback/Host/Manifest/组合恢复专项，保存逐次真实结果。
- [ ] 使用c78冻结真旧二进制写/读与候选旧模式交叉，独立新目录，不复跑整个709原档；测试新v5原命令全恢复。

### Task 13B：有界试跑接线（同一实现/审查切片）

Files: WorldWebApp.java、AutonomousLifecycleProbe.java及现有entry/seed测试；ProbeProviderEvidence只在确有需扩文档时改，语义验证不弱化。

- [ ] 新Web启动模式world-contextual-informed-operating、新journal world-contextual-informed-operating-v2.journal；旧模式/default完全保留。
- [ ] Probe新模式CONTEXTUAL_INFORMED_OPERATING，精确新工厂/原PLANNING_SYSTEM_TEXT，沿provider-provenance-v1完整留证。Config原额度上限与seed同mode/source/prompt/完整证据门保持；新旧不得互作seed。覆盖来源审计、最终经营输出、提示词及seed全部入口，不仅openWorld工厂；新Web沿routesAbsent门，不重写既有revision=0配置。
- [ ] entry模式/seed拒绝先RED后GREEN，本机fixture不发真实外网调用；旧rev0配置不静默升级。
- [ ] 给Root提交完整报告、源SHA、受影响测试命令与原始退出记录；释放source/test/target，独审通过后由Root提交/冻结。不得自行commit或派子代理。

### Task 14：真实对照与后继经营

- [ ] Root冻结输入、源码、模型请求参数/系统提示和提供方声明；以原call386、426、537及提前实施控制等同事实输入做有限成对重复。准入以新helper实际输出为准，不手写期望事实，原回执不改。
- [ ] 不要求经营/YES；逐条判事实是否自相矛盾、工具合同是否有效、等待依据是否对应真实条件。提供方权重版本/未支持随机种子仍UNKNOWN，不声称统计显著或对照已证明闭环。
- [ ] 依据实际对照结论继续新版本独立真实世界试跑；完整回执/资金来源/多订单/跨期及失败原档恢复另验。整体Plan未完成项不提前勾选。

Self-review: 范围只覆盖两个已明确输入问题；未来董事实时资产/协调工具/等待策略不混入。版本门与真实结果/未来生效边界均有对应验证；未改变总体经营验收目标。
