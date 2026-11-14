# M4i.2c 运行桥实施合同（2026-11-13）

前置：schema15已提交2f24e22/PR43，正式1428与CI43392262590通过；匿名证据纯核已独审。继承2026-11-07-interview-evidence.md。此合同授权隔离实现schema16运行桥与Web，保持Java为唯一权威。

1. 新EvidenceRun仅保存Config(shareMillis正数)与每机构MediaEvidence.State；与MediaRun并列保存到WorldRuntime.WorldState，同一次候选帧提交信息、媒体、证据状态。配置为本实验独立冻结的正时长（初始1000ms，仅实验参数），不增每机构覆盖表、不修改旧MediaRun.Config形状、不重复来源账本/工时账本。新Work种类MEDIA_EVIDENCE_SHARE_V1仍进入现有机构预算与Work表。
2. schema16独有manifest/hasEvidence及工具合同v4；旧1–15的JSON、配置、inputText、工具与执行行为保持不变，旧档证据字段null且不序列化，禁止静默升级。恢复必须核初始可信registry/config。新kind在旧档明确不可达且恢复拒绝。
3. submit沿用单一evidence列表，公开/非公开按真实资料受众派生，新增可选aliases。保留旧草稿构造器/旧序列化；不得模型自报来源数、不得复制privateEvidence列表。提交调用submitWithEvidence并保留已有EvidenceState。别名规则沿用纯核，不重复发明唯一性限制。
4. share为真实人物命令，参数rootDocId/rootVersion/recipientPartyId/note；共享ID只由人物与clientCommandId派生。读过并可回忆的共享材料可二次共享/用作证据，按纯核绑定归同一原根，不增加独立来源，不授予原私证权限。
5. 两帧：观察与完成均核实际读完、指定版本可引用、回忆/任职/策略；schema16非公开材料按纯核允许范围处理，不另加“必须DIRECT收件人”排除合法组织阅读。公开路径原拒绝次序/旧行为不改。媒体工时与预算遵守现有规则；失败不部分发布/写证据，拒绝回执仍真实记录。
6. 新任务必须绑定本批真实命令/决定动作及人物，合法重算digest的伪造pending+usedIds+事件+command元数据也不能凭最终自洽通过。validateMedia按逐帧重算比较EvidenceRun/MediaRun/Information，不能信存档最终结果。
7. Web提供新evidence档入口。管理员可审计实际根、别名与共享绑定；人物只见实际读到的公开标签及本人可见资料，不能从标题、docId、未读统计、输入、导出/历史接口泄露私根。公开文章可继承权威私根但人物无读取权。别名不是自动正文审查，不承诺阻止已知身份在言语中说出。
8. 可用性：新档包含明确模拟的私证/角色情境，经实际发布、实际阅读进入流程，不自动已读/成稿/共享。模型工具表包含完整参数；新输入只给本人的实际可用证据/别名操作标识。测试必须取真实inputText字段，不能猜内部request/doc协议。保留5Key与路由预算，不调用真实provider做开发测试。
9. 验收：纯私证/混合/公开转述继承/更正版本/同源去重；观察或完成失权/未读/遗忘拒绝；真实决定submit/share及读后得知；幂等与碰撞；跨真实JVM恢复；三态原子写故障；合法重哈希复合篡改；新实现按旧15规则续档后真正旧类读回；真实HTTP权限/全输入驱动链/前端身份切换；最终正式套件与浏览器。

旧15基准：logs/implementation-20261106-continued/interview-evidence-runtime-preparation/compat15。原档c359db33…、旧client a9c6db06…不可改/重编；仅使用副本与冻结旧类验证。新增schema16绝不写入旧15档。准备提案v2的DIRECT限制与旧15写新动作叙述以本合同为准。

实施者只写自己的隔离mirror；先交接口/文件清单，随后在本合同范围完成实现与定向验证，冻结完整候选与SHA。Root核关键差异，独立Hermes另审，Root接主树后一次正式验收与PR。不得把作者自报/模拟流程称为真实模型自主实验或总体完成。

## 工时接缝裁定（续接）

不修改MediaRun.Config、不引入外部时长表或全局注册。复用现有采访Work的分层校验：MediaRun识别新share kind，对该类Work以实际completed-started跨度作为结构计费上界，正常完成按跨度计费；WorldRuntime必须在排程、完成与恢复逐帧重算中以冻结EvidenceRun.Config.shareMillis核对该跨度、原命令与报告，旧schema拒绝新kind。运行层统一duration helper对share取EvidenceRun.Config、旧kind仍调用原MediaRun.durationOf。这与现有INTERVIEW_KINDS/会话Work的职责一致，不让Work自报跨度替代世界验证。

预算不足/控制动作/业务拒绝如何计费按现有media具体路径复用并记录，不统称失败免费。新增验证必须覆盖share伪造跨度+工时+预算+报告自洽而无真实命令的合法重哈希篡改，确保分层校验未开旁路。
