# M3a.2 制造经营接入唯一世界日志

本文件是[持续实施 Plan](2026-11-06-world-implementation.md)的施工规格；状态与最终证据只在总 Plan 维护。用户已授权今晚持续推进，不另设常规审批门。董事会属于下一单元，本步只启用受信管理夹具，不能宣称完整公司首版。

## 起点与文件边界

- 主工作区 `G:/项目/java-stock-auction`，`codex/world-manufacturing`，基线2acea2a；未合入草稿PR #9依赖#8/#7/#6。新增ManufacturingModel及20项测试已从隔离工作树逐文件校验复制，组合基线207项通过。
- 复用现有ManufacturingModel，不复制账务。改 `world/WorldRuntime.java`、`RunManifest.java`、`PendingTask.java`；新增 `WorldRuntimeManufacturingTest.java`、`app/WorldManufacturingDemo.java`。必要的制造运行数据/报告类型可放新 `ManufacturingRun.java`，不用通用模块框架。
- 不改engine/Account/CashLedger/旧187测试/配置/CI。纯核20测试也保留；确有集成缺陷时先RED，再对Model作最小修复并交接。文档由Codex写，Hermes独占G盘Maven/源文件；GUI/CSV总验收由Codex接手。

## 档案、开局与唯一权威

- 新档案 `stock-company-experiment-v1` 精确模块 cash/scheduler/registry/securities/matching/manufacturing 各1，model=none、WorldState schema4。旧schema1/2/3的JSON均不新增null字段；旧构造器保留。
- 新增明确构造参数，例如 `ManufacturingConfig(ManufacturingModel.Opening accounting, String symbol)`；有模块必须有配置，无模块不得带配置，全部开局错误在创建日志之前拒绝。把配置绑进首批openingContent，只在新档案加字段。
- 公司财产owner须为ORGANIZATION，其partyId对应登记的公司组织；symbol发行人须为同一组织。财产ownerId与组织partyId允许不同字符串，不能混同。模型指定经营账户归该owner，所有对手账户存在且满足纯核约束。
- 首档案拒绝公司同owner第二现金账户（含零余额）和公司持有证券/库存股的未建模资产；其他owner账户/有限股份保留。公司经营账户不可用普通转账或证券下单绕过业务分录。限制只作用新制造档案，旧档案不变。
- 现金余额仍唯一在CashLedger；每次模型付款注入同一截止位置的WorldExchange.freeCash。模型报表从该候选账户表派生，不存第二份现金或根据市值改权益。
- 持久制造子集可为 `ManufacturingRun(symbol, ledger, reports)`：纯核State加按taskId不可变管理结果。管理Snapshot仅新增Optional子集；报告含task/原command/kind/结果/关联业务ID/时间/提交位置，命令重发仍回原SCHEDULED。原报告不可删改，不把它们叫已投递角色消息。

## 对外排程接口（仅管理夹具）

全部due>now、暂停/创建线程/任务唯一/命令幂等沿用现有规则。到期复核才执行；参数错误提前拒绝。下单/投产/交付有固定后续耗时，已知的 `due+1日`、`due+15日` 溢出须在外部排程前拒绝，避免到期永久卡住。

1. `schedulePurchaseOrder(commandId,taskId,due,actorOwnerId,purchaseId,long quantity)`：到期形成PO，成功后自动排到货。
2. `scheduleProduction(commandId,taskId,due,actorOwnerId,batchId,long quantity)`：到期耗料/付人工/形成WIP，成功后自动排完工。
3. `scheduleSalesContract(commandId,taskId,due,actorOwnerId,contractId,long quantity,unitPrice,promisedDeliveryAt)`：到期按预算/需求/同期间条件签约，不提前收入。
4. `scheduleDelivery(commandId,taskId,due,actorOwnerId,contractId,long quantity,deliveryId)`：到期扣FG、确认收入/成本/应收，成功后自动排15日收款。
5. `scheduleCollectReceivable(commandId,taskId,due,actorOwnerId,receivableId)`：逾期后可明确重试，已收拒绝再收。
6. `schedulePayExpenseBill(commandId,taskId,due,actorOwnerId,long period)`：只付该期已确认未付，不重复费用。

不暴露随意关期或改账入口；不预埋未实现的预收/预付/债务/分红/外币动作。一般公司/客户/供应商权限仍由固定夹具处理，未实现角色任职授权。

## 自动待办与时序

- PO到货、生产完工、信用回款均是COMPLETION，参数来自刚确认的纯核记录，child任务与父动作/现金/实体变化同批保存。不得先发布父效果再补child，也不在恢复时重新生成已记录效果。
- 新档案开局必须包含期间0关期任务；期末固定30实验日、RULES阶段。每次关期同批确认费用/部分付款/应付/折旧及下一期间任务。没有可用现金也照记费用，不能靠不调用接口漏记。
- 费用账单ID在到期时按ledger已用ID找空闲；内部任务ID按全世界usedTaskIds找空闲。不能限制用户ID前缀，也不能让用户提前使用某ID使强制期末任务永久失败。报告/事件记录实际选用ID。
- 当期任务到期调用模型时使用task.dueTime，不是advanceTo目标时间。月份边界是实验规则，不读本机日历。
- 到货资金不足保存FAILED_NO_CASH元数据，回款不足保存OVERDUE；false结果也可能携带该元数据，不能一概丢掉候选。不得产生单边钱/库存、凭利润放款或重新扣签约预算。
- 普通转账同时保留买冻结保护。触及公司经营账户的普通转账、公司证券申报在入口拒绝并在到期防御复核，不用账不平的技术暂停代替清楚的能力拒绝。市场收盘不停止公司费用/应收任务。

## 原子提交与恢复验证

- 每批：完成当前待办、纯核候选、账户候选、后续自动待办、结果/领域事件、全量世界快照；journal持久确认后才发布。资金事件用独立 `MANUFACTURING_CASH_TRANSFERRED_V1`，明确付款/收款账户与实际金额，别复用不同载荷的既有现金事件。
- 系统关期结果与外部原command区分；内部child保留父动作/command关联。所有报告task已登记、位置/时点不越过快照，旧结果不改。
- 每批调用Model.validate，检查公司A=L+E、世界现金守恒；固定Opening/initialEquity/initialWorldCash/设备原值不改、已用ID只增，旧PO/批次/合同/应收/费用账单的原始数量/价格/成本/时点不改，合法状态与已付/未收数量单向变化。
- 活动PO/WIP/开放应收必须由持久待办推动；期间关账不能跳过/重做。逾期应收允许等待手动重试。无须为任意篡改后重签扩建防伪造系统，保留日志既有边界。
- 旧schema3真实文件新追加后应能再被旧程序读取；不以新码自写自读替代跨版本复验。原文件保留。

## 必须先RED的验收组

- [ ] 缺模块/配置/组织映射/证券发行人/公司第二账户/公司持股等开局在写盘前拒绝；profile旧新相互隔离。
- [ ] 17日采购→18日付款入料→投产现金300/WIP800→19日完工→20日交付AR1200/利润400/现金200；额外5件COD需250失败，资产不变。
- [ ] 自动30日关期：付200、应付50、折旧100，利润50、资产2100/负债50/权益2050；35日自动收1200再付50→现金1150/设备900；世界现金每批3600。不手动调用关期凑结果。
- [ ] 客户gross2000但买单冻结1000，35日应收1200收款失败/逾期；取消买单后明确重试只收一次。公司普通转账/证券下单不能绕过分录。
- [ ] 每种外部命令重试不重复实体、后续child、费用/收付款；内部任务/账单ID碰撞可恢复推进，暂停拒绝外部排程。
- [ ] 已有PO/WIP/应收后真正经济完成帧线程中断/半帧，账户/库存/应收应付/待办/报告一起旧版，恢复只完成一次；不能误测尚未形成经济状态的第一帧。
- [ ] 期间唯一/不可提前/不可跳过，跨多个期间自动累计，设备不折成负；股票收盘后公司任务继续。
- [ ] 同seed乱序排程不改变合法同刻结果；旧三档案与schema3续写兼容；标准全套/GUI/CSV、JavaDoc、评分映射与真实记录。

正式 `WorldManufacturingDemo <dir> [prepare|full]` 默认logs目录。prepare推进到20日交付后退出，留下AR/期末/后续待办；full恢复到35日+1ms完成强制关期/自动回款/已排应付，第三进程同命令重发不多付款/记费。输出同一快照的公司表和世界现金；它是model=none夹具，无新世界GUI或董事会完成宣称。
