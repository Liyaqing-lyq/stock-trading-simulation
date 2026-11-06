# B1 真实模型用量与预算纯核 Plan

依赖有界传输PR26实际CI。用户已授权按总体基线持续实现；此阶段只扩展现有DecisionModel并隔离旧Runtime，不提前宣称世界已连接真实模型。私有LiveDecisionCandidate是设计演示，62检查没有运行现有DecisionModel，不能当正式实现或兼容证据。

## 文件与兼容契约

生产只改world/DecisionModel.java与WorldRuntime.java的旧档案配置/回执入口与恢复闸；新增DecisionModelLiveTest及Runtime拒绝live配置的专门测试。保留原87生产中的85文件及原925测试。禁止新增另一套决定状态机、通用provider框架或改旧断言。

1. Config追加可空Profile字段（只启用LIVE_HTTP_V1），注解NON_NULL；保留原七参构造，其profile=null，继续严格SCRIPTED-only及0.00费用预算。新显式profile才接受DEEPSEEK_HTTP和未知费用预算；静态工厂不能绕过record canonical校验。
2. Usage追加可空CostStatus（UNKNOWN），旧四参构造仍拒绝null费用，金额非负两位，序列化不增加新键；缺status字段反序列化仍走旧严格语义。新unknown工厂传cost=null/status=UNKNOWN；UNKNOWN配数值费用、无status配null均拒绝，不能松开旧不变量。
3. 未知费用通过显式新构造进入算术，不能洗成0。旧covers遇未知返回false，不准借此穿透旧金额预算。仅新profile的内部预算辅助函数按attempt/input/output检查；新profile的零值、预留、累计、headroom、重算都使用未知费用零计数。不能只改open而遗漏Attempt未结算状态或恢复重算。
4. 同profile的加减保留精确计数与未知费用，所有溢出继续拒绝。旧JSON和旧4/7参二进制调用实测兼容；不承诺反射record组件数量不变。新字段必须版本化，不能让旧档案识别为已经启用live。
5. 当前RunManifest最高schema9仍只允许scripted，WorldRuntime在接触/创建journal前拒绝profile非null的配置。恢复校验也不能把旧档案带新profile/costStatus接受。B2才新增schema与运行接线。

## 派前预留与超额

沿原固定perCall输入/输出预留，运行和逐人两层先检查attempt/input/output预算；不足时不创建可派发尝试。不在本阶段宣称token估计是提供方硬账单上限，也不加入HTTP、scene预算、可变输入估计。

正常live回执：attempts=1、cost未知、输入输出均在预留内才按实际计数结算；缺usage按预留全额结算，不退款。已知超预留原始declaredUsage仍保留，技术失败按预留占额，实际声明不能截断为预留；预算占额与真实声明是不同字段。

任一已知超额使新live尝试全局停止：从已确认attempt声明和预留派生阻断，不存隐藏布尔开关。当前超额不能自动建立下一尝试；已有其他尝试回执可结算，但不得由它们创建新retry。open/retry/resume入口均检查；B2派发器也须检查这个派生状态。旧scripted超额/重试行为不变。未知用量在已配置尝试数和预算内的处理沿原技术失败规则，不自动增加额度。

## 验收顺序

- [x] 新正式测试先RED：实际DecisionModel，不使用平行玩具模型。覆盖旧null拒绝/JSON逐字、新unknown构造/混用拒绝、两层预算、未知保守、超额冻结、多个在途、幂等、恢复/伪造累计、旧Runtime建档前拒绝。
- [x] 最小生产实现与新旧全套通过。最终新增28，组合953。
- [x] Root实际旧编译客户端与JSON对照、独立探针；Hermes独审明确完整输入合法对照，不能用缺字段先失败冒充目标覆盖。
- [ ] 标准verify含GUI/CSV、JavaDoc、评分/接口/证据/Plan同步、AI草稿PR及实际CI。
- [ ] B2另单：scene稳定累计预算/冻结路线、持久派发标记、异步runner/代次/超时、schema10、新Web场景和审计。B1不勾成B2。

Root补充验收：candidate1另在途自动retry穿透全局冻结，2正式回归RED修复；candidate2又有4费用状态反例，新增3模型/1Runtime正式测试先失败后修复。候选3在旧pending/newpending、live已知零声明、旧正常/复用/旧代次回执入口及恢复归档/审计隔离新费用格式。Root24+4、真实旧构造/完整State JSON精确、同journal新旧推进time1至7及现金4100、953标准/CSV49/121/7/GUI/JavaDoc04:06:05已通过。最终独审与PR/CI尚待，不能勾整个B2。
