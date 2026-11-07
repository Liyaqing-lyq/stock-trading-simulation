# M6a 真实成交 K 线 Web 增量

总体基线§6/§12要求同成交链的分时/日/周图、真实量额、无成交空档、未完成柱和截止。此单只实现当前确认快照的原始成交图表；账户净值/回撤序列和任意位置历史回放仍是下一步，不冒称完整M6。

## 功能与约束

- 管理员市场页提供证券选择、分钟/模拟日/模拟周周期、原始OHLC蜡烛与成交量、成交额及精确表格。保留原有分红/成交列表。界面白色沿现有样式，图表有标题/单位/可键盘选择、非颜色说明/表格替代。
- 新只读 POST `/api/admin/market-history`，严格JSON仅symbol/period；symbol可空字符串表示按真实已启用证券稳定选择首个，period只minute/day/week；其他类型/多余字段/重复键/尾随/无权限/Origin均按既有Server规则拒绝。不写状态、不调用模型。
- 同一Host writer任务内一次真实snapshot，包含runId/position/timeMillis/lastPhase、available、marketClosed、真实symbol候选、selectedSymbol/period/basis=UNADJUSTED_EXPERIMENT、bars/gaps/truncated/totalBars。没有撮合模块则available=false，不能假造0价格。未知非空symbol拒绝。仅当前cut，不接受endPosition/time伪历史。
- 新纯聚合类可放web/MarketHistory，输入当前WorldExchange.Trade链和cut；原始每笔quantity long、price BigDecimal。按timeMillis再tradeId排序取同桶open/close，high/low精确。minute60000、day86400000、week604800000，从模拟时点0分桶（明确不是现实交易日历）。
- 只生成有成交的bar，最多最近500根，totalBars与truncated真实。间断只用相邻保留bar之间的区间表示（最多499），不逐毫秒/空桶枚举，不补价或假交易。不要从全市场closed推断单股停牌。无复权算法，标原始成交价。
- bucketStart可用time/period*period，endExclusive若溢出long则null且complete=false；其余end<=cut表示完成。不把当前柱完成或平移桶边界。未来成交不得混入已确认cut。bar含first/lastTradeId、count、open/high/low/close、volume/amount、start/end、complete，long与BigDecimal在HTTP全部精确字符串。
- 量合计仍long，Math.addExact溢出时volume=null、volumeStatus=OVERFLOW，金额继续BigDecimal精确累加，不能整列误成0/负数或double。金额不得用浮点记账；JS只将归一化图形坐标近似Number，精确tooltip/表格保留原字符串。大时间/ID使用BigInt差值归一化。
- SVG只textContent/安全DOM不拼注入HTML。fetch用身份generation/pageVersion/requestId与所选symbol/period共同防迟到；切个人/退出/导航清理图形、表格、摘要/选项与后台缓存。不自动推进世界，刷新展示同cut。

## 验收与写权

真实Trade聚合专项：同刻tradeId次序、high/low、跨分钟/日/周边界、空档、500裁剪、大long边界/桶end溢出/volume溢出/精确amount、不改变输入。HTTP：401/403/Origin/严格JSON、未知symbol、旧无exchange、同cut不推进；真实WorldRuntime成交→HTTP数值/恢复一致，不能仅mockDTO冒充接线。UI由Root真实浏览器验收。

作者d39b13只写 `logs/implementation-20261106-continued/market-history-author/` 私有镜像，基于belief-core-candidate-3的91源/资源；可新增MarketHistory及测试，只改私有WorldWebHost/WorldWebServer和web/app.js/index.html/style.css。禁止WorldRuntime/纯核/主树/Maven/target/git/Temp/keys/供应商/用户窗口。每个待改文件先核实际契约，先保存有意义RED再实现；失败保留、不删弱旧测试。可自编私有javac/JUnit/loopbackHTTP，用web-root-production/classpath.txt。Root拥有主树/所有最终验收与信念Web接线。最终只按文件差异合并，不整树覆盖新认知桥。
