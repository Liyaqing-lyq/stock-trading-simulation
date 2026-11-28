# Frozen Thinking Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 让真实自主经营新档使用可审计、不可漂移的thinking enabled/high调用，并保留全部旧档合同。
**Architecture:** 可空路由providerPolicy冻结至ProviderPlan与Request，v2来源记录只用于新策略；新factory/新probe模式隔离60000ms与8192输出设置。
**Tech Stack:** 既有Java/Jackson/HTTP/JUnit/原生JS，不增依赖。
**Spec:** docs/superpowers/specs/2026-11-16-frozen-thinking-provider-design.md

## Global Constraints
- null策略保持旧序列化、旧HTTPbody、旧v1来源字节；唯一新值deepseek-thinking-high-v1，未知拒绝。
- 新策略仅请求enabled/high；input/system/工具/权限/经济不注入指令。
- provider-policy必须源于冻结call.plan.route，不读当前可变路由；完成token完整结算，成本UNKNOWN。
- 不保存reasoning_content；固定端点、凭据只认证、无重试与传输界限保留。
- 新runId web-thinking-operating-v1，60秒墙钟/8192输出，旧factory不迁移；真实经营仍未通过。
- 单一source/test/target写者，Root最终提交，日志项目内，失败留证，不自动合并。

## Review Focus
- 旧构造/JSON缺省仍逐字不变，恢复不能默默改模式。
- 新路由保存到页面再回写不能丢失策略，冻结后的call不受后续路由修改影响。
- 提供方token多于最终content时仍全额记账，截断及未知用量不能变成功。
- 新策略来源与seed篡改、v1/v2混用必须拒绝。
- 新世界60秒限制与deadline/drain一致，不能在证据失败后无限付费。

### Task23: 冻结策略与显式新档（一个集成变更）
**Modify:** src/main/java/edu/cufe/auction/world/DecisionModel.java; web/SceneRoutes.java,WorldWebHost.java,WorldWebServer.java,DeepSeekClient.java,LiveDecisionProvider.java; world/composed/ComposedIndustryScenario.java; src/main/resources/web/app.js; src/test/java/edu/cufe/auction/web/AutonomousLifecycleProbe.java,ProbeProviderEvidence.java and focused tests.
**Interfaces:** Nullable providerPolicy appended to route/request records, old constructors retained. New factory openThinkingInformedOperating(Path). New probe mode THINKING_INFORMED_OPERATING. Result nullable finishReason and new nullable ProviderProvenance invocation as specified.
- [x] Add failing tests proving actual frozen-enabled request vs old-disabled body, new scene save/reopen/freeze, complete usage incl reasoning generation. Preserve genuine RED.
- [x] Implement minimal nullable strategy propagation and v2 evidence. Keep v1 bytes with null-omission.
- [x] Implement newfactory/probe plus strict v2seed checks and firstproviderfailure stop/drain for newmode. New config only timeout difference beyondmanifest.
- [x] Add focused oldserialization/conflict/length/empty/unknownfinish/overusage/tamperedseed/factorycross-open tests, and UI control preservation within existing JS verification where possible.
- [x] Run affected JUnit classes once; inspect final XML and raw exits. No full-suite launch by worker.
- [x] Self-review, save source hashes/test evidence and report; release ownership to Root. Root independent review, fixloop and implementation commit complete; final integration/runtime freeze are tracked by Task24 below.

### Task24: 真实新档与经营验收
- [x] Independentreview complete; one formalverify/JavaDoc integratedsource and frozenclassparity.
- [x] Preserve opening/strict frozenconfig, fresh bounded trial max2000HTTP/30min/60day target no seed, no pre-read/autovote/auto budget, original52roles. Underlyingweights/costUNKNOWN.
- [x] Preserve all calls/replies/provider invocation, stop+drain onfirsttechnicalproviderfailure/evidenceunknown; review actualworld businessstate and failedoperations.
- [x] Audit journal strictreplay/provenance/Decimalcash; if businesschainmissing, diagnose earliest real blocker andcontinue. No claim overallcompletion without originalquarter/halfyear/causal/UI/humancriteria.

Task23接线补充：src/main/java/edu/cufe/auction/app/WorldWebApp.java与WorldWebAppRouteDefaultsTest.java加入上述新显式模式/独立journal/仅缺失路由初始化。既有8类最终定向结果保存，新增入口仅跑其受影响类，不重复全组；统一正式验收后进行。scripts/test-thinking-routes.cjs需要纳入既有verify Node检查序列，或明确未注册的实际限制。

Task24执行与原档审计已结束，结果为世界故障和0经营（运行记录§129）；勾选只代表试验执行/留证完成，不代表经营验收。后续Task25与Task26先修复实际执行和反馈阻断，整体季度/半年、因果、UI与人工门仍开放。
