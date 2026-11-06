# M4f 回忆运行桥与Web观察：Root施工合同

依赖回忆纯核PR33，最终90源candidate2，1099标准/95独审已通过。目标是把真实已有经历的有界回忆接入人物观察与决定输入；不将本段当完整认知/媒体完成。参考cognition-runtime-preparation/prepare.md，只采纳下列已核准合同，冲突以本单为准。

## 1. 唯一配置与旧档隔离

- `RunManifest.RULES_COGNITION_RECALL_EXPERIMENT_V1 = "cognition-recall-experiment-v1"`，严格模块表=原live全部模块+`cognition-recall:1`，model仍LIVE_DECISIONS_V1；新增`hasCognitionRecall()`，不假称六层认知全开。
- `InformationRun.Config(readers, @JsonInclude(NON_NULL) CognitionRecall.Policy recallPolicy)`；保留原一参构造，null省略JSON键，旧字节/ABI不变。新模块必须非null Policy，旧模块必须null；先校验再打开journal。Policy全量进入现有openingContent，同日志换任何参数必须拒绝，绝不迁移旧档。
- 新schema11，仅expectedSchema最前判Recall，旧1..10及旧schema10的输入/重放逐字保持。无第二份CognitionRun/WorldState字段、无新账本/新事件种类。原纯核/InformationModel不改。恢复两处inputText重算必须用传入previous快照及冻结Policy，不读当前未来状态。

## 2. 角色数据与管理员审计分离

- RoleView新增可空NON_NULL `RecallMetadata recall`，保留旧11参构造；Experience新增可空NON_NULL `RecallOrigin recall`，保留旧6参构造。
- `RecallMetadata(boolean budgetTruncated)`只说明正文/条数预算截断；`RecallOrigin(ReadingRef lastReadRef,long lastReadAt)`记录最近实际获取。**角色DTO也不含Policy、score、eligible/forgottenCount或原始lastReadId**。InformationModel的READ-n是全局序号，不能因新增字段泄露别人的阅读活动；用原readingOrdinals按本人所有阅读映射成ReadingRef（不得按selected重排）。原Observation.ref仍保留首次本人引用。
- Runtime仅在新档调用CognitionRecall，角色observations只取selected；金额/持仓/账户/有效授权/目录/待办/进行中阅读照旧精确提供。离任已读经历可保留，继任者不得继承。
- 同时关掉research正文旁路：新档RoleTradingView.ResearchView.body只能来自同cut selected中的该doc/version原Observation，read仍表示历史canCite，不改为false冒充没读过。允许read=true/body=null，旧档原逻辑不变。不需要给ResearchView增加组件；新input可条件追加bodyAvailable表示当前正文是否可得。
- Citation资格不变；重新获取仍要实际startRead/completeRead，取消/拒绝/未完成不能刷新。管理员information审计原文/历史不裁。
- 新可信管理员Runtime接口`Optional<RecallAudit> recallAudit(String person)`，旧档empty，新档只接受已登记读者；同cut返回runId/position/time/person/Policy/Result。它不在WorldRoleSession中提供，也不进入人物输入。Root稍后接admin专用HTTP/UI，不由角色得到这份审计对象。

## 3. 决定输入与超限

- DecisionRun.inputText旧档一个字节不变。新档依据profile.recall!=null：readExperiences追加lastReadRef（docId/version/index，本人scope，不输出person/global READ-id）及lastReadAt；顶层recallTruncated、permissions[{action,scope,decision}]。不含Policy/score/忘记数量/内部来源或其hash。保留本人原pendingActions参数，这是未完成义务，不因遗忘删除。
- 原accounts/holdings/market/catalog/activeReadings/targets/children/notices/pendingActions/tools/appointments不缩减。正文选取后生成完整JSON，再测实际字符/UTF8字节；不二次缩小Policy、不截正文/账户来勉强通过。
- 新档在ProviderPlan构造前检查字符上限及MAX_INPUT_BYTES。超限抛类型化`WorldRuntime.DecisionInputTooLargeException`（IllegalArgumentException子类），含actualChars/actualBytes/maxChars/maxBytes的只读数值和固定错误码INPUT_TOO_LARGE，不带输入/Key/自由异常正文。整批无提交、Snapshot/预算/位置不变；旧档失败语义不改。重放仍严格拒绝任何伪造超额输入。
- Root对Host捕获该类型后展示固定INPUT_TOO_LARGE与安全数值，不能吞成无诊断OPEN_REJECTED，也不自动重试。

## 4. 新Web档案与可观察交付

- `WorldWebScenario.cognitionManifest()`/`openCognition(Path)`复用同一经济开局与liveConfig，新增明确实验Policy：14模拟日半衰期、阈值100、8条、12000 UTF16正文字符。参数未经调优，不宣称现实最优，修改需新日志。
- App第四参数增加`cognition`，使用独立world-cognition.journal；**当前默认是live，保持live**（准备稿“默认scripted”为错误）。旧live/scripted不变。启动/读取/保存配置不发真实请求，仍明确单轮启用。
- Root在现有上帝信息页增加人物回忆审计：可选择登记人物，同cut显示Policy/候选及遗忘数/截断/选中经历与最近获取/分数；原全量资料审计继续存在。对应新admin HTTP鉴权/Origin/严格参数/no-store，人物接口不能获得该对象。页面实测使用独立dummy/loopback世界真实发布与读取，不造历史数据冒称生产实验。
- 公共API中文JavaDoc。只用现有依赖，最终Web阶段未完成项仍如实标注。

## 5. 写权与验收

Root拥有主树/Maven/WebHost/Server/三资源及全部文档。作者先在cognition-runtime-author私有镜像改InformationRun、RunManifest、RoleView、DecisionRun、WorldRuntime、WorldWebScenario、WorldWebApp和新测试；其余83生产源只读，不动CognitionRecall/InformationModel/RoleTradingView/WorldJournal。

- [ ] 先测试：新档/旧档配置能力闸、参数恢复拒绝；原构造JSON/旧profile行为不变；原始READ-n/Policy/score/内部源均不进入角色DTO或input。
- [ ] 真实Runtime完成阅读→遗忘→重新阅读工时未到→恢复；research旁路同样收口；引用资格/准确义务/账户/权限/普通目录不丢；独立人物互不继承。
- [ ] 输入实际字节/字符两类超额、第二个人使整批超额不留第一人的决定或预留；同cut/恢复冻结输入/ProviderPlan估算准确。
- [ ] Root旧编译客户端不重编指纹828217b8…553f4保持；真实旧schema10新续写旧二进制读回，schema11三JVM读/重读/input一致，伪造新策略/新字段旧档拒绝。
- [ ] Root接admin查询/UI与Host超限原因，测试401/403/Origin/严格JSON/同cut/身份切换清空；真实浏览器展示本轮查询和重读效果。
- [ ] 正式全套含CSV/GUI、JavaDoc、独立冻结复核、文档与草稿PR/CI。所有未测边界明确保留，不以编译RED冒称语义RED。

执行中发现未知入口先给Root具体位置/影响，在范围内修正；不让用户重复裁决既定需求。总体仍缺信念/偏信/成熟习惯、媒体社交、多公司银行、完整图表和长跑。
