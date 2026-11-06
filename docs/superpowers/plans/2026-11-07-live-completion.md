# B2c1 完成登记与提供方映射实施 Plan

基线：Web草稿PR30代码90e726a、文档ea0ddd3；代码CI42299095951实际success。分支codex/world-live-completion。用户要求持续完成总体基线、最终Web、五Key场景使用保持。此阶段完成Runtime接收与转换，不把未接Host的类称为Web模型闭环。

## 范围

WorldRuntime最小增量，新增LiveCompletionInbox、LiveDecisionProvider；新增相应三测试。不得改DecisionModel/DecisionRun/RunManifest字段与旧schema1..9格式，不改课程经济核。live专用旧直接回执测试按正式完成登记协议迁移但原业务/审计/恢复断言保持，所有旧scripted测试不改。所有public API中文JavaDoc。

完整接线语义以2026-11-07-live-web-runtime.md末尾Root核准合同为准：Runtime自动私有槽、先持久派发/open后返Call、首次宿主登记、timely/timeout/late、knownusage技术失败原命令重建、有界容量、IO候选保持，旧模式隔离。新provider单次同步适配Call，不创建线程/重试，不持有世界；755字节固定system提示和准确输入，费用未知、失败固定码，不造WAIT。

## 顺序与验证

- [x] Inbox私有16与provider私有17测试核准；正式测试缺类编译RED（不是行为RED），复制两类后正式组件33/33通过。Inbox负nano/回绕4项已有真实行为RED→GREEN。
- [x] Runtime私有候选定稿、Root核验正式失败测试；补多claim首项保存失败、直接live期限旁路、已结算重复/旧代次审计保留、provider-failure严格逐批恢复。
- [x] 最小集成并完成专项/旧live测试迁移、全部旧scripted断言不变；真实多进程与旧9/二进制兼容。
- [x] 冻结源真实Hermes独立审查/Root故障复验；标准verify含GUI/CSV与JavaDoc、评分/接口/证据/总Plan同步。
- [ ] AI草稿PR与实际CI。
- [ ] 接续Host Web单线程pump、实际回环网络闭环、开始停止暂停及有界真实角色单次试验（B2c2）；该项不计本B2c1完成。

## 当前写权及证据

Root独占主树/Maven。作者ed3b0f exec49155仅live-web-preparation/runtime-candidate；提供方d39b13私有b2c已交（17/17，hash36aeec3f…93858e3），独审afc0c5准备已交（尚无冻结动态结论）；旧Web独审d3c63e已结束。Root已发现私有pump多claim首项失败只retry当前、其余claim遗留，Root实际RED得到可再领取1而非2。作者正在修复并新增双候选IO/运行异常回归。

真实五Key仍仅之前key-1一次51token，以上测试dummy/回环或无网络。具体日志live-completion-root与对应私有目录。总体认知媒体、多公司/银行、分支回放及压力实验仍待完成。

补充：活动新回执必须登记；已结算/旧代次direct accept保留原审计拒绝能力。原quoted failure的旧任意reason不能由新固定码入口产生，Root用原B2b二进制实际生成两份gzip日志兼容夹具，原方法全断言通过，新测试继续验证恢复与完整身份篡改拒绝。新的合法审计夹具窗口1秒并真实等待1.1秒触发超时，原业务断言保留。

最终本地1059标准/73专项、32成交118订单7排名、GUI、JavaDoc06:02:53、Root三JVM15/旧binary/旧9交替及26独审通过；candidate2与主树89源相同。上方完整工程交付复选项中的PR/CI仍待下一步实际结果，不能提前视为远端完成。Host作者d39b13 exec53092只私有live-host-preparation，Root仍独占主树/Maven。

最终candidate3仅修中文JavaDoc归属及scripted队列计数说明，可执行文本与candidate2相同；最终Runtime0bdfebd8…68815，JavaDoc06:06:23。
