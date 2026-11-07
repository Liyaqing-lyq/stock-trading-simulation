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
