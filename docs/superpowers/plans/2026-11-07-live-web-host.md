# B2c2 Web角色轮次运行合同（Root核准）

依赖B2c1冻结candidate2（89源）；本单先私有实现Host+Scenario+新测试，Root接Server/UI并独立验收。用户持续推进总体基线、最终Web、五Key场景授权不变；禁止把本单当总体完成。

## 最小行为

- 保持旧 `WorldWebScenario.open`/manifest/schema9逐字行为；新增openLive+liveManifest，独立runId/profile/schema10，使用完全相同经济开局，不迁移旧日志。新增liveConfig：maxAttempts1；maxInFlight2；wallTimeout15000；inputChars32768/replyChars65536；perCallInputTokens60000/output8192；queue4；run未知cost预算100calls/1000000input/200000output；四岗各25calls/250000input/50000output；wake1000ms，各原role工具操作2000ms。所有数字为内部实验限制。
- Host原构造兼容；新增生产构造 `(IOSupplier, SceneRoutes, LiveDecisionProvider)`。仅该组合且Runtime live才有执行器；不自动begin、不自动网络。一次开始选当前实验四岗alice/bob/carol/dave中的1..4不同人物，固定公平顺序，最多每人一次，自动结束；research映射alice，其余trading，不声称其余三场景执行已连接。
- startLiveRound(expectedPosition, expectedRevision, persons) writer内先检查世界位置、路由修订、未暂停/未故障、无活动轮次/物理在途；新进程第一次显式start才begin新session，后续不重复begin。动作/命令ID由当前确认位置和person生成，用户不任意指定。未开始前没有pendingDecisions。
- 待唤醒名单只在Host内存有界<=4。每次真正有物理worker空位、同scene空位时才取最新routes.view，enabledfalse跳过并给固定原因；累计scene/run/person预算由DecisionModel冻结/预留判断，绝不能第二份消费账或重置预算。route快照为授权观察点，open+dispatch在同writer立即相邻执行（不预先open其余人物，不留未派发屏障）。blocked/expired给实际原因，不能造WAIT。每次选定之后都只使用原Call，配置修改不改已发模型/Key/输出上限。
- 配置保存需同writer排序：新增host.replaceRoutes(expectedRevision, routes)（只针对该Host持有SceneRoutes），RootServer在connectedHost时调用；scriptedServer可保留原SceneRoutes.replace。修订检查先于start副作用。当前视图中未启用scene应明确跳过；任何API拒绝不自动重复开始。
- bounded worker pool最多2个，提交前保证容量，不无限队列。worker只用不可变Call/provider且只调用Runtime.offerLiveDecisionCompletion，不查询/修改世界。每attempt一次，不自动重试。调度若在持久dispatch后意外executor拒绝，必须登记固定LOCAL_EXECUTOR_REJECTED失败，不能丢槽/虚报未收费。
- writer每250ms poll，在每项外部任务后及空闲时pump；读取页面不是回执应用触发器。暂停Host标记阻止advance、新dispatch、pump，但worker继续只登记完成；正常resume允许pump且按首次到达时点判断，不改Runtime故障paused。Runtime IO故障只展示FAULT/restart required，不能靠resume解除持久故障。
- stopLiveRound(expectedPosition)清空尚未打开的名单，保留已在途直到结算，不能把cancel称为供应商未收费。Host close释放自己worker/Runtime/queue，已发未结算靠原restart保守结算，不重发。
- liveStatus()同writer采样：真实position/time字符串、模式、ready/running/stopping/paused/fault/idle，pending人数、physicalInFlight、场景及已确认用量/冻结预留/上限（来源DecisionModel，不用网络返回临时累计）；本轮跳过原因；最近最多10决定/尝试，原plan的scene/slot/model/revision、状态/结果/声明usage/costStatus、准确input/reply的最多4000字符预览+完整长度+truncated标志，未知为null。每页同cut；不能读全知数据当模型input；不露Key/路径/异常自由文本。详细字段先定义typed record/Map保持现有风格。
- 人物接口不暴露管理员模型面板。新start/stop/pause/resume与routes更新的HTTP由Root下一步接现有鉴权/Origin/Host/X-World-Request/严格JSON/expectedPosition，不在本私有实现中改Server/resources/App。

## 允许文件与验收

私有目录 logs/implementation-20261106-continued/live-host-preparation/ 内复制Host、Scenario和新WorldWebLiveHostTest；其余89生产源从冻结candidate2编译（覆盖这两文件），不使用主target。Root独占主树/Maven。新增公开API中文JavaDoc。不读真实keys、不供应商网络，仅已有DeepSeekClient包内loopback+dummy、不得Temp。

测试真实回环：启动/保存配置/读取不调用；显式start恰一次/无人刷新也持久；四岗受限input与scene，配置切换后原call冻结；同scene并发1、run2，停止不派未开始，暂停及时结果不超时但迟到不做业务；预算触顶不增加请求；关闭/重开不自动网络、显式start保守结算；传输失败/已知usage/无usage；expectedPosition/revision/invalidperson无副作用；scripted原构造行为。测试前真实RED，再实现GREEN，失败不得放宽断言。交handoff+哈希+真实日志+未覆盖。Root独审后才集成。

## Root Web接线合同

在Host候选核准后，Root修改WorldWebServer/WorldWebApp与web三资源、新HTTP测试。新增管理员GET /api/admin/live与POST /api/admin/live/start|stop|pause|resume，严格字段、Origin/Host/X-World-Request、完整会话鉴权不变；全部变更同writer校验expectedPosition，start另验expectedRevision及1..4明确人物。拒绝不自动重试调用。暂停/恢复需Host同任务位置校验，不能前读后写。

UI新增“模型运行”页，四岗选择、单轮开始/停止/暂停/继续、真实当前状态/物理在途/尚未派发人数、场景累计/预留/上限和最近决定输入输出预览（截断标明）。最多选择四人，每人一次；没有启用对应场景时提示跳过。两秒只读轮询仅当管理员停留该页，切身份清timer/内容且generation挡迟到；服务器推进不依赖轮询。配置页五场景显示只有research/trading已连接，其余配置保留待接，不暗示已运行。完整Key仍显式查看限时清除。

WorldWebApp第四参数live/scripted，默认live；原工作目录不变，live用新world-live.journal，scripted仍world.journal，schema9 open入口不变。启动/读取/保存配置无自动请求。发生WORLD_IO不称可由普通继续解除，UI明确须重启恢复。默认所有场景依旧关闭。

Root补充核准：为Host在open之前区分已超时未物理返回、已登记未归档的占额，允许WorldRuntime新增只读liveCompletionSlots()，不暴露Inbox/Claim、无schema或预算字段变化；Root独占Runtime及计数回归，作者只消费该接口。Host派前同时检查实际worker与完成槽容量，按scene限制物理请求，任何拒绝不能遗留新未派发OPEN屏障。

## 本轮进度与验收

- [x] Root Runtime只读完成槽计数17专项通过；不改变schema或预算。
- [x] Root 8HTTP独立回归已实际运行；早期诊断快照7过1失败，重启后明确开始409为真实缺陷。两项原journal正文独占锁错误为测试夹具问题，已修且保留日志。
- [ ] Hermes私有最终Host/Scenario修复并恢复完整重启断言；旧14绿不计最终验收。
- [ ] 完整候选冻结后Root HTTP、主树正式测试及真实Hermes独审。
- [ ] Web实际浏览器、verify含GUI/CSV、JavaDoc、文档、秘密扫描与草稿PR/实际CI。

当前作者exec28031、独审准备exec89635；Root独占主树/Maven。UI字段补充LiveStatus.completionSlots、runBudget、personBudgets，每项预算按已耗/预留/上限显示精确字符串。网络占用与结果槽有交集，不能在页面相加。真实keys本轮未调用。

Root最小复用决定：WorldScheduler已有纯函数fairOrder改为public并补中文JavaDoc，算法不变；集成Host时直接调用并删除私有候选复制的SHA算法。当前cognition-preparation只读准备由ed3b0f完成（本轮exec53092已退出），不授下一阶段生产写权。Root HTTP现为10项，另可控Runtime时钟+真实阻塞HTTP容量探针已复现旧Host误开第二决定；补预算原因重启保留验收。

## 06:55 最终候选进度（覆盖上文历史未完成状态）

- [x] Host/Scenario15回归，完整重启实际新HTTP断言；Root已移除作者测试setup重试，原样15通过。
- [x] Root10 HTTP、可控 Runtime 时钟18检查；早期超时仍物理在途却打开新决定和预算拒绝显示EMPTY均先RED后修复。
- [x] 主树1085标准、CSV42成交121订单7排名、真实GUI及JavaDoc06:47:24通过。
- [x] 实际浏览器四岗loopback调用、冻结配置/预算/输入输出、重启无自动请求及身份切换清空。
- [ ] 最终真实Hermes独审与UI增量复核。
- [ ] 文档/秘密扫描/草稿PR及实际CI。

candidate3全部89 Java与candidate2相同，只有页面截止及导航保护的JS增量；日志详见live-web-root。真实Key0新增请求。AccessDenied诊断只确认Windows文件替换异常，未确证扫描器或持有句柄进程，生产SceneRoutes未改。

最终收口：candidate2独审74动态通过；candidate3全部89Java相同，最终JS增量静态复核无发现（exec62389完成）。Root追加9真实IO+6executor拒绝通过，最终浏览器cut17→18同步已验。代码与文档进入草稿PR，远端CI结果随后记录，人工review尚未完成。
