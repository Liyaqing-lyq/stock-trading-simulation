# B2a 场景冻结与可变预留 Plan

依赖B1草稿PR实际CI；此单扩展现有DecisionModel，不创建平行状态机。后续B2b才加入schema10持久派发，B2c接Web runner/pump。用户最终目标仍为完整Web基线，本单不能冒充已连通世界。

## 已定契约

1. 生产仅DecisionModel.java；新增DecisionModelRouteTest.java。保留B1全部旧测试、Usage四参/Config七参、Decision十六参/OpenRequest十参构造及其旧JSON。Decision和OpenRequest末尾新增可空NON_NULL的ProviderPlan；旧构造传null。所有内部转换必须保留plan，包括零尝试预算终态、记录、失败、重试、恢复。sameFrozenInput比较plan，同action改route不能被当重放。
2. 新嵌套record Route(sceneId,slotId,model,revision,maxCalls,maxTotalTokens,maxOutputTokens,maxConcurrency)，仅非秘密字段。五scene稳定标识、key-1..5、flash/pro与Web现有范围相同：calls1..100、total256..1000000、output128..8192且<=total、并发1..4且<=calls；revision>=0。world不得import web。ProviderPlan(Route route,long inputBytes)冻结本决定路由，所有重试沿用；费用全部UNKNOWN。
3. 本单固定估算版本utf8-plus-1024-v1：inputBytes必须等于冻结inputText的UTF8字节数，1..60000；输入预留=inputBytes+1024，输出预留=route.maxOutputTokens，attempt=1。1024仅本地估算余量，不是官方账单上限；B2c固定system提示词与包装必须落此已说明假设，实际超额仍冻结。new plan仅live profile允许；两个预留分别不得超过Config.limits.perCallInputTokens/perCallOutputTokens。旧plan=null继续B1固定规则。
4. 决定输入摘要维持原语义（不改变旧摘要）；plan是独立冻结字段，后续派发另作其准确摘要。仅改plan不能合法改写原决定或原请求重放。恢复验证plan输入字节、路由形态、每attempt对应预留、实际settled/declared关系；不能仍按global perCall判定planned回执超额。
5. 派前run/person继续原双层；增加按稳定scene累计已结算与未结算预留，跨revision不清零。scene cap的token是输入+输出总数，两项不是各一份总额度；等于cap合法，超过才拒。新决定不足保留带plan零尝试预算终态。未知回执全预留占额，正常回执退差额，已知超额保留真实声明/全局阻断，自动retry也须scene预算足够。
6. 路由修改语义：已存在决定的slot/model/output与输入冻结不变。新请求的revision不得低于state中任何plan的最高revision；同revision+scene必须有完全相同Route。同scene用于后续新预留的cap取已观察到的最高revision Route（含新请求），改高可增加上限，但历史已用不归零；改低可保留已有预留/历史花费，新预留不足时拒绝。retry仍用原输出预留，预算上限按该scene当前已观察的最新Route，不能借旧高cap绕过后来低cap。
7. scene使用量只从decisions/attempts派生，不维护第二份可篡改计数。公开sceneUsage(state,sceneId)、sceneReserved(state,sceneId)返回UNKNOWN Usage；latestRoute(state,sceneId)返回Optional<Route>。计数精确且溢出拒绝。并发参数在此仅冻结，实际在途门禁由B2b派发实现，不能把未派发队列宣称为网络并发。
8. State单帧可核计数/预留/计划一致性，但不能凭终态证明每次过去路由修改发生时的预算；B2b必须按前一确认帧及原open/retry命令重建来源。不要用最新降低cap拒绝本来合法的历史已用/在途，也不要把静态反序列化当完整时间线审计。

## 文件接缝

Decision/OpenRequest尾部可选plan及旧构造；openDecision/sameFrozenInput/withAttempts/new Decision各路径；reservation/settleUsage/validateDecision需以当前决定plan或attempt.reserved为准；settleTechnical/headroomAfter加scene预留检查。保留liveBudgetBlocked对任何attempt实际预留的比较。

## 验收

- [ ] 正式真实模型测试先RED：旧构造JSON原样、scripted拒plan、字节/路由边界、差异输出预留、同action改plan冲突、run/person/scene三层、等于cap/跨revision已用不清零、cap提高/降低、旧输出freeze与retry新cap、未知保守/超额停发、JSON伪造及恢复。
- [ ] 实现最小变更，原953+新增专项全绿；实际旧编译调用程序和JSON逐字对照。
- [ ] Root独立反例与真实Hermes独审，标准verify含GUI/CSV、JavaDoc、评分/接口/证据/总Plan同步；AI草稿PR与实际CI。
- [ ] 后续B2b：schema10含分红全能力、manifest严格组合、DecisionRun持久dispatch事实、真实旧9新旧交替、三进程防重；此单不创建新世界档案或调用网络。
