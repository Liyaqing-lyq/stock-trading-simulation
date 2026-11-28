# PR草案：角色依据与真实治理尝试（整体自主经营未通过）

目标仓库：Liyaqing-lyq/stock-trading-simulation。base：`codex/world-autonomous-control`（7557ffa）；head：`codex/world-operating-evidence`。本文件是本地草案，尚无远端PR，不代表人工review。

## 问题与行为

真实董事的公司专属资料在目录和模型阅读入口被`BoardAccess.NONE`挡住；雇佣任职空表与章程董事权利又容易产生歧义。新增显式authority-v1/evidence-v1，后者发布来自开局账本/业务配置的公司私有资料，通过真实耗时阅读进入观察；候选能力仅EXP自述，无虚构履历。旧版本不迁移。

首次发布与日志恢复都从冻结候选表、已复演账本重新生成正文，拒绝重算合法帧后的资料伪造。经理、预算、选票、订单仍必须走真实流程。

真实52人累计113请求仅落成1场会、1材料、2人到会，停在原治理100次预算，无经理/预算/经营。18次输入对照和2份原回执重放单列。持续付款来源、多订单、跨期经营、完整岗位目标仍未实现，不能将此PR作为自主世界完成。

## AI参与与线程

[AI-Generated] 本PR的Java实现、测试/实验工具、证据整理及文档由Codex生成。独立AI审查已关闭资料恢复伪造Important问题；人工review待完成。

WorldRuntime仍在既有同步边界发布/阅读/执行，开局正文仅在时点0从同一快照生成；未改撮合引擎/账户锁顺序或网络调用位置。模型实验工具沿LiveDecisionProvider和既有后台调度，不预写动作。

## 验证

- 资料隔离、模型阅读、旧输入/重开、伪造恢复：5项通过；保留RED/GREEN。
- 受影响事件/窗口调度、旧阅读、资料发布、提供方、manifest及公司工具：56项通过。
- JavaDoc实际生成通过；`git diff --check`通过。
- [完整证据](../experiments/2026-11-15-role-evidence-ledger.md)及[逐条模型原回执](../experiments/role-evidence-20261115/live-receipts.json)。HTTP_NETWORK、环境中断、离线依赖/信任库失败均保留。
- 未跑不变的撮合/CSV/GUI正式全量，不称阶段正式验收；未部署旧Web，未合并。

## 人工审阅重点

- 新版本是否确实隔离旧档；章程董事/有效经理/无权候选是否分开。
- 开局正文是否可从已复演账本重建，日志内自述是否能伪造现金或任职。
- 实际动作与原回执是否分列；预算截断、未知费用与技术失败是否如实表达。

## 远端阻塞记录

2026-11-15实际尝试：GitHub连接可读仓库，但创建分支返回403 `Resource not accessible by integration`；本机`gh repo view`返回401，Git SSH因known_hosts读取权限失败。未禁用主机验证、未读取/导出凭据、未改全局Git配置。当前只有本地提交与草案，恢复合法仓库写入权限后再推送、建立Draft PR并由人工review；不得先合并。
