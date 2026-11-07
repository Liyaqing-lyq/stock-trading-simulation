# M6b 账户规模、表现与回撤历史：实施合同

依据总体设计基线§6与§12。Root核准后执行；本步骤与媒体纯核隔离写权。M4g.2最终源码85d71ed为起点，纯只读增量，不改journal/schema/旧规则。

## 用户行为与范围

在账户表现页选择财产主体与已确认记录区间，同时查看资金规模、剔除真实外部资本流的表现指数、回撤及资本流。每条数据来自同一writer时点的确认历史；注资不画成盈利，应收分红到账不重复记收益。图表保留精确表格替代、单位、起止位置与模拟时间。

该步骤不是全世界历史观察回放、继续运行或历史开分支。旧课程收益口径保持；旧运行数据不重写，模型不重新请求。

## 权威计算与兼容

1. `WorldRuntime.performance(owner,start,end)`现有逐帧生成Point的逻辑抽出共享私有方法，保留periodId与既有返回内容。新增历史查询一次构造同一组Point，不允许每个前缀重新扫描全日志。查询不追加事件、不推进时钟、不改变调用预算。
2. `PerformanceModel`新增不可变历史结果，包含现有Performance和每点Point、表现指数、当前回撤。旧public record构造/JSON、calculate结果保持；calculate与新接口共享同一遍DECIMAL128几何链接及12位HALF_EVEN输出，不从已舍入指数另算回撤造成图表与汇总不同。
3. 完整且至少两个点时，首点表现指数1、回撤0；后续点用已有资本流前后估值与同一高精度peak计算。整个期间INCOMPLETE_VALUATION、NON_POSITIVE_EQUITY、NO_PERIOD时沿用旧Performance语义，指数/回撤保持空，不填0或连成假曲线。已知的资金规模仍可展示，缺价点断开。
4. 金额、指数、比例、long位置/时间到Web均用精确字符串；前端只把BigInt精确差值/缩放结果转为画图坐标。单点、平价、负值、极大正负值和一分钱跨度均不得除零、Infinity或丢失差异。

## Web查询与界面

- 新管理员POST历史查询：严格ownerId、startPosition、endPosition（位置用十进制字符串）；沿用会话/Origin/请求标记/no-store，人物会话不可调用，未知主体/无效范围明确拒绝。cash旧档案明确不可用，不访问未启用模块。
- 单writer任务获取当前世界cut，然后查询不晚于cut的完整连续区间。响应同时带当前run/position/time、所选起止position/time、主体、真实总点数、Performance、曲线点与资本流；不得将历史区间终点误作实时世界时间。
- 明确沿用`MAX_PERFORMANCE_FRAMES=400`。大于400帧拒绝，不静默截断或改起点。UI可默认最近至多400帧，但必须明写“所选区间”，展示实际起点，不能冒称全期。前后端均保留输入精度。
- 页面新增主体/起止位置与查询按钮，三幅小SVG（资金规模/表现指数/回撤）及可展开精确表。横轴按确认记录位置，不用相同毫秒制造重叠点或暗示均匀真实时间；每点另显示模拟时刻，真实资本流有标记/表。
- request generation、当前页、人物身份和快照切换全部防迟到响应；离页/退出/变更主体区间/失败/空数据清空旧图。使用textContent，不能把返回的名字/说明作为HTML；不用外链图库。

## 验收

1. 复用真实WorldRuntime入口：初始净资产→注资→成交价格变化→提取；核对资金规模变化、TWR与回撤。另以真实分红登记/除息应收/到账证明不重复收益。
2. 真实旧档重开、查询前后journal位置/hash/预算不变；旧Performance输出/periodId固定，旧构造/JSON与既有全套保持。
3. 缺价/陈旧、零负权益、单点、同毫秒多确认、未知主体/非法边界、400/401帧、精确大整数与一分钱、无权限/Origin/请求标记/no-store、切页/身份/迟到响应。
4. Node实际渲染函数回归进入现有verify入口；Root实际HTTP与浏览器核验、正式verify/CSV/GUI/JavaDoc、评分/记录、冻结hash及独审、AI草稿PR/实际CI后才计此步骤完成。

## 分工

真实Hermes仅在`logs/implementation-20261106-continued/performance-history-author/`私有镜像实施：PerformanceModel、WorldRuntime、WorldWebHost、WorldWebServer、web app.js/index.html/style.css、新测试和新增Node测试脚本。其他现有源只读（特别是RoleAccess与全部Media代码）。先写api.md固定最小方法/DTO再实施。禁止主树/Maven/target/git/keys/供应商/浏览器/全局memory/系统Temp；所有脚本和构建在私有目录，交接后停止。Root负责主树接回、验证入口改动、独立验收与发布；不得覆盖媒体作者目录或共享源码。
