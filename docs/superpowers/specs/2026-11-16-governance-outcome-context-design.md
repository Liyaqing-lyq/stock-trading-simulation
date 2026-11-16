# 本人调用结果与公司决议状态的分离

本设计是自主世界整改的一个限定切片，由Root在用户持续执行授权内作出实现裁定；不是自主经营完成声明。依据：709次真实试跑及task-13-diagnosis.md。三制造未同时具备经理和预算；多名主席把本人重复实施被拒误解成公司决议被否决。另已复现预算提案存在却没有PROPOSAL_EXISTS规划阻断。

## 选择与范围

比较过三条路径：改业务拒绝码会改变旧规则/原档；扩充董事实时资产和协调机制会混入本次尚未证实的原因；仅在显式新输入版本增加目标当前事实，能单独检验语义影响。本轮选第三条，同时补确定的预算规划遗漏。原始accepted/reason/execution不改。不给下一动作、金额、人选、票型或成功承诺，不取消独立董事并发实施权。

## 精确合同

- 新role-context为deliberation-v5，新工厂openContextualInformedOperating(Path)，runId=web-informed-operating-v2；旧openInformedOperating仍v4和web-informed-operating-v1。新输入不能写入旧档。
- v5保留v4相对reviewAfterMillis合同及完全相同PLANNING_SYSTEM_TEXT，manager-v1、position-v1、bounded-history-v1、local-input-v1、external-demand、delta-v1和有限预算全部保留。只精确列举v4/v5的规划合同，不放开未知版本。
- 只在本人board.implement的原反馈行内添加targetMeetingContext；从本人已持久接受的INTENT、精确settled attempt/ActionRef及严格工具参数绑定companyPartyId/meetingId，匹配原commandId，绝不从basis、最近会议或actionId字符串猜目标。
- 上下文只包含当前同帧且本人有权看到的目标事实：公司、会议、asOfMillis、currentStatus、historicalInvocationScope=THIS_COMMAND_ONLY、currentEffect。原报告所有字段/值保留。公司决议已实施不等于本人重复调用成功。
- 任命效果必须核真实任职/撤销记录，区分NOT_EFFECTIVE_YET、ACTIVE、EXPIRED、REVOKED、UNKNOWN；ADOPTED不是IMPLEMENTED，未来生效不是ACTIVE。REVOKE按真实撤销处理，找不到真实效果时给UNKNOWN/UNAVAILABLE，不靠提案补出任职。
- 预算效果仅引用已有授权cap或已实施议案行，并说明非新增现金；不在该切片新增spent/outstanding或他人私账。
- 技术失败、无精确ActionRef/attempt、工具不符、参数无效、目标不在当前授权投影时不补业务事实。跨公司同meetingId不能串联；不暴露成功者私有原回复/理由/选票。
- 上下文嵌入recentDecisions/lastActionResult，沿现有wakeFacts整体剥离；纯asOf变化不额外付费唤醒，真实任职/预算变化仍按当前事实唤醒。
- 仅v5的BUDGET规划：proposedLines非空时board.proposeBudget增加PROPOSAL_EXISTS。空提案不增加；不把任命proposalPresent误作预算。执行器不改。

## 证据门

用真实两董事并发实施夹具证明只一次任职/预算、另一条原拒绝保留，新上下文准确。覆盖提前实施、未来生效、到期/撤销、错目标/身份/技术失败/未接受伪回复、预算空与非空、隐私、时钟无虚假唤醒、旧版本完整输入及跨旧JVM恢复。然后独审，冻结新版本；真实同事实对照与完整运行分列，不能规定必须任命/采购或以测试通过作为经营验收。

## 限制

当前模型已经拿到足以正确理解的多数事实；语义澄清是否改善行为尚待对照，不能承诺。经理自选不读/一天复查、短任期、真实时滞、其他信息范围与跨期治理未由本设计解决。季度/半年及整体世界继续未通过。
