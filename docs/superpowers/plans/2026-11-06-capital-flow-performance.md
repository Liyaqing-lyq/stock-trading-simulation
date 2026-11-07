# M3d D3c 实际资本流与确认历史表现

本施工单依据总体设计基线§6及已验收D1纯核。D3b真实运行桥完成独立恢复验收及草稿PR后进入执行，当前仅固定接续契约。

## 范围

- 沿用schema9及dividend-world-experiment-v1，在唯一WorldJournal保存真实资本现金腿及CAPITAL_FLOW_V1事实。新增WorldRuntimeCapitalFlowTest，生产限WorldRuntime。D0、D1、D2、D3a及旧档案行为保持。
- 复用CASH_TRANSFER_V1、CashLedger.computeTransfer、freeCash和原Notice，不新增另一付款实现。ScheduleCommand及TransferPayload末尾同时增加NON_NULL资本元数据并保留旧构造及旧JSON，evidence和资本类型必须进入命令幂等内容。
- 同owner内部现金转移沿普通入口。资本入口要求from/to为不同owner，公司经营账户沿专用账拒绝。公司融资、工资模型、银行存款负债和申赎治理后续各自接线。其它已有主体可保留真实资本事实，组织完整NAV依D1返回不支持。
- 不新增PerformanceRun、NAV表或资本状态Map。估值均由该确认帧的现金、证券、原成交、D0和D3a参考派生。

## 管理入口

```java
String scheduleCapitalFlow(String commandId, String taskId, long dueTimeMillis,
        String actorOwnerId, String fromId, String toId, BigDecimal amount,
        long receiptDelayMillis, String evidenceRef);
List<CapitalFlowReport> capitalFlows(String ownerId);
PerformanceModel.Performance performance(String ownerId, long startPosition, long endPosition);
record CapitalFlowReport(String flowId, String taskId, String commandId, long position,
        long atMillis, String actorOwnerId, String fromAccountId, String toAccountId,
        String fromOwnerId, String toOwnerId, BigDecimal amount, String evidenceRef);
```

新入口均要求hasDividend，旧档案明确拒绝。owner、位置和结构错误沿现有管理接口异常语义，余额、公司专用账和到期所有权资格形成原Notice。排程不预扣现金，原命令内容与task/due/kind幂等先于当前时刻和业务资格检查。同内容返回原memo，异内容拒绝。evidenceRef为受信管理场景的资本说明，不能冒称人物实际读过的材料。

原scheduleTransfer在排程期已拒公司账户，新资本入口不能简单调用该公开方法。可提取共用排程核心并保留旧入口原前置行为，新资本入口将公司专用账和实际账户动用资格保留到期复核，复用同一个transferOutcome及完成付款/Notice路径。结构上未知账户、未知owner和非法金额继续早拒，同owner内部转移使用普通入口。

flowId由域标识、runId及原任务/命令/到期/账户/金额的规范化元组摘要派生，不能用无转义分隔符拼接。事件内不再持久保存可从前后确认快照复算的NAV。查询返回不可变列表，按真实确认顺序过滤与所给owner有关的两侧事实。

## 完成批与恢复

- 资本成功批包含原TASK_COMPLETED、唯一CASH_TRANSFERRED、唯一CAPITAL_FLOW及原Notice排程。payer为CAPITAL_WITHDRAWAL，payee为CAPITAL_CONTRIBUTION，二者金额相同且为精确两位正数。
- 到期用实际previous和原任务复核actor/source/destination/金额/freeCash。失败沿原通知，现金不变且不产生资本事实。原普通跨owner转账在新9继续TRANSFER_UNCLASSIFIED。
- 提交前及restore逐帧同一个validateCapitalFlows，严格decisionMapper解析新元数据和事件。绑定原commandContents、task、due、kind、实际前后账户和原Notice，双向拒成功现金腿缺资本事实、伪造或重复资本事实、失败附资本、错误owner/金额/evidence/position及任务来源。
- 新资本批仅改原现金和正常调度/通知字段，证券、成交、D0、Board、信息及决定状态与previous相等。实际原账户现金逐项等于唯一computeTransfer结果，公司经营账限制不可绕过。
- 旧1–8禁止新资本元数据或事件。新9普通转账不能以缺字段、伪造内部命令或未知字段绕过分类。实际I/O失败暂停且不发布候选，尾半帧恢复到完整边界，重开只转移一次。

## 表现查询

1. owner必须存在，start/end必须为实际已确认位置且start<=end。periodId由固定域、runId、owner和startPosition规范编码生成，同一运行及起点可稳定重算，调用方不能随填。
2. 起点取start帧的流后净值，形成无Flow基线。起点前和起点本批的资本事实仍可查询，但不作为本表现期间资本事件。
3. 遍历(start,end]内每个已确认WorldCommit，均形成一个Point。无本owner资本时前后均为该帧NAV。存在本owner资本时，before用previous经济快照在该批due截止估值，after用该批candidate相同due估值，Flow方向按实际两侧owner。一个位置只一个Point。
4. before是该现金腿前的逻辑边界，估值输入快照身份用真实previous.position并采用本批due计算价格时龄。after用实际candidate.position。Point本身只有一个position，固定取该资本批candidate.position。两边估值包含该现金动作前已确认的同刻RULES除息等效果，不读取同时间戳下后续批次。
5. 任何缺价或组织不支持的边界保留Flow和准确估值状态，两个NAV为空。双COMPLETE时逐项确认after-before为±真实现金金额。不能用null变零，也不能丢弃资本事实。
6. 所有Point交给原PerformanceModel.calculate，DECIMAL128、12位HALF_EVEN、缺价/非正/不足期间状态及几何收益/最大回撤沿原核。价格变化、股利应收和股利收现均不是资本流。
7. 查询只读，不推进位置、不产生事件、不重复计股利。默认人物工具及RoleView面保持本单现状，先供管理端核验。

逐帧采样为Root明确裁决。只取资本流和终点会漏掉无资本流期间下跌后恢复。纯模型1000→995→1000实测TWR0、回撤0.005，起止两点却丢为0。正式回归必须包含真实价格先跌后涨及持有人减值后足额付款两条曲线。

真实Hermes只读复核从D3b candidate2独立编译79源，用自建PRODUCT持有人及实际12元成交，再以原现金腿纯组合核对22项准备断言。500追加、实际价格收益和100提取后，投资者净值1600、TWR0.133333333333，基金净值840、TWR0.057142857143。此证据验证夹具与纯接缝，三个新资本API仍未实现。其R1公司排程、R2完整幂等及R3单Point位置歧义已按本单上述文字裁决，来源root-contract-review/handoff.md。

## 验证与交付

- 新测试先行，真实有限账户追加500后净值规模增加且TWR0，提取不当亏损，source/payee两侧各有准确流，冻结资金不透支，同owner内部无资本流。
- 真实交易后的价格收益与资本流剥离，分红登记/除息/到账不分类为注资。每帧采样捕获先跌后涨，缺价/零和负权益不跨段拼接，任意新确认起点开启独立期间。
- 原命令重放、恢复、相同due的RULES优先、严格字段、伪造全部来源矩阵及持久化故障。合法控制先通过再完整重签反例。
- Root冻结源码独立编译、Decimal原现金与股份复算，真实Hermes最终独审。标准verify含GUI/CSV及JavaDoc通过后更新docs01/02/03/05、README、Memory和唯一主Plan。建立AI-Generated草稿PR并核实际CI，继续待人工实质review。

## 检查点

- [x] 真实Hermes只读准备与Root采样纠正，固定最小运行契约。
- [ ] 前置D3b完整本地验收、草稿PR及实际CI。
- [ ] 正式授权测试先行、真实RED、夹具独立核对。
- [ ] 正式授权生产、专项/全套、变异及恢复矩阵。
- [ ] Root独立验收、Hermes独审、GUI/CSV/JavaDoc和文档。
- [ ] AI-Generated草稿PR及实际CI，待人工实质review。
