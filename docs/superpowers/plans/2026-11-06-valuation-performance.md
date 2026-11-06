# M3d D1 账户估值与时间加权收益纯核

依赖D0分红纯核的最终candidate3及草稿PR19，正式启工前核对其代码CI。依据总体设计基线第6节，复用真实现金、证券批次、成交流水及DividendModel债权。D1只读计算，不产生资产、现金腿、世界事件或第二份账。

## 范围与裁决

- 新增world/ValuationModel.java、world/PerformanceModel.java及对应两个测试文件。保留D0和旧616项、Runtime、规则档案、配置及schema。先授测试与缺API RED，核对后再授两生产文件。
- 使用现有OpeningRegistry识别财产主体及初始参考，避免另复制身份映射。投资者和PRODUCT支持本轮完整证券账户范围，组织主体明确UNSUPPORTED_COMPANY_SCOPE，净资产为空，因为经营资产和其它义务尚未纳入。不得把其现金与证券小计称公司完整NAV。
- 金额精确规范到2位，不舍入。数量long，BigDecimal精确乘数量，不转换long分，不用double。收益计算使用MathContext.DECIMAL128，中间不截到展示精度，输出收益率、指数及回撤统一scale12、HALF_EVEN。
- 无份额登记，本轮只提供基数1的流量剔除表现指数。基金份额净值、申赎、年化及夏普继续保留后续范围。
- 价格只取原始成交流水及明确开局参考，来源和时间、陈旧毫秒保留，不设未经指定的陈旧阈值。不在本单计算除息参考或强改实际成交价。分红不双算夹具中的9.00须是明确合成输入价或真实夹具成交价，不冒称市场一定下跌。

## ValuationModel公开形状

采用三个PriceSource值TRADE、OPENING_REFERENCE、MISSING，三个Status值COMPLETE、MISSING_PRICE、UNSUPPORTED_COMPANY_SCOPE。

```java
record Price(String symbol, BigDecimal price, PriceSource source,
             long asOfMillis, long ageMillis)
record PriceBook(long position, long atMillis, Map<String, Price> prices)
record HoldingLine(String accountId, String symbol, long quantity,
                   Price price, BigDecimal value)
record Value(String ownerId, long position, long atMillis, Status status,
             BigDecimal cash, BigDecimal holdings, BigDecimal dividendReceivable,
             BigDecimal dividendPayable, BigDecimal netWorth,
             Set<String> missingSymbols, List<HoldingLine> lines)

static PriceBook prices(long position, long cutoffMillis, WorldExchange.State exchange,
                        OpeningRegistry registry, Collection<String> symbols)
static Price priceOf(PriceBook book, String symbol)
static Value value(String ownerId, long position, long atMillis, OpeningRegistry registry,
                   Map<String, CashLedger.CashAccount> accounts,
                   SecuritiesLedger.State securities, DividendModel.State dividends,
                   PriceBook book)
```

- Price的MISSING值为price=null、asOfMillis=-1、ageMillis=-1，其余价格非负、精确2位、来源时间不晚于截止，age恰为截止减来源时间。零价格可明确估值为零，禁止把缺价转为零。
- prices在截止前按(timeMillis, tradeId)取最后一笔真实成交，tradeId为现有long。没有截止前成交才用registry开局参考，asOf=0。未知且无参考为MISSING，未来成交不能泄漏。exchange为null表示该快照尚未启用撮合，不能捏造成交。
- book.position及atMillis须与value相等，dividends.lastPosition/lastAtMillis不得超过该截止。真实现金、持仓和债权来自同一世界快照的保证由未来Runtime适配器承担，纯核不可能从缺少来源时标的独立现金Map证明其历史来源。
- 未知owner拒绝。按其全部账户聚合现金，只加CashAccount.cash一次，冻结已含于其中。每个账户/标的聚合所有批次remaining，含冻结和LOCKED。TREASURY作为明确持有数量保留在组织范围明细中，组织仍不给完整净资产。
- 分红投资者应收复用DividendModel.holderNetReceivable，公司合同应付复用companyPayable按issuerOwnerId汇总，不重新实现权利或减值。孤儿现金账户主体、持仓账户、债权账户及发行付款账户引用应拒绝，不能因无法映射而静默遗漏。
- COMPLETE时netWorth=cash+holdings+dividendReceivable−dividendPayable。任何持仓缺价时holdings和netWorth为空，missingSymbols列全，保留已知明细与现金/应收/应付。组织状态优先为UNSUPPORTED_COMPANY_SCOPE，仍列出缺价标的。没有持仓时不因无行情变成缺价。
- 集合深不可变、排序稳定，金额scale2。派生结果只作查询，不成为可变余额或持仓输入。

## PerformanceModel公开形状

FlowKind只有CAPITAL_CONTRIBUTION、CAPITAL_WITHDRAWAL。Status为COMPLETE、INCOMPLETE_VALUATION、NON_POSITIVE_EQUITY、NO_PERIOD。

```java
record Flow(String flowId, String ownerId, FlowKind kind, BigDecimal amount, String evidenceRef)
record Point(String ownerId, long position, long atMillis, ValuationModel.Status valuationStatus,
             BigDecimal navBeforeFlow, BigDecimal navAfterFlow, List<Flow> flows)
record Segment(long fromPosition, long toPosition, Status status,
               BigDecimal returnRate, BigDecimal index)
record Performance(String ownerId, String periodId, Status status,
                   BigDecimal returnRate, BigDecimal index, BigDecimal maxDrawdown,
                   List<Segment> segments)

static Performance calculate(String ownerId, String periodId, List<Point> points)
```

- Flow显式记录真实权益转移时点的外部资本流，金额严格为正且scale2，需非空证据。工资转入、注资、提现等由宿主分类，股票买卖、红利收现不自动产生Flow。D1不能从现金差额推断注资，也不验证未来现金证据的物理真实性。
- Point同时给该截止资本流发生前后估值，完整点严格满足after=before+本批有符号流量。没有Flow时两值相同，首点无Flow。每个Flow只出现一次且owner匹配，位置严格增长、时间不倒退，同毫秒不同位置合法。输入乱序直接拒绝，不排序后掩盖缺失边界。
- 相邻完整正权益点的区间因子为current.navBeforeFlow/previous.navAfterFlow。逐因子几何链接，得到指数、收益率与同一流量剔除指数的最大回撤，禁止用原NAV规模曲线算回撤。
- 一个原子世界批次中的多笔资本流可合并在同一Point，流前后估值必须齐备。不同提交位置分别有Point，不能把多次流量全部挪到期末再扣。
- 缺价/组织不支持点不出精确收益，整体为INCOMPLETE_VALUATION且收益/指数/回撤均为空，不能跳过该段连接前后。完整点净资产为零或负时仍保留原值，表现为NON_POSITIVE_EQUITY且输出为空。仅0/1点为NO_PERIOD。不同原因同时存在时优先缺完整估值，再非正权益，再无期间。
- 清盘后重新注资由调用方启用新periodId和正权益起点，旧含非正权益期间不能接成无限收益。D1只读重算，没有新表现余额、份额登记或可变收益账。所有输出及集合不可变，公共API有中文Javadoc。

## 先行验证与验收

- [x] 测试先行真实缺API RED，既有76生产源hash不改。测试接口核对后再授生产。
- [x] 真实SecuritiesLedger/WorldExchange夹具验证现金与冻结只计一次、多个账户、LOCKED、组织库存股明细、严格截止/未来价/同刻成交号、缺价和大额。
- [x] 复用D0分红的登记/除息/减值/清偿，100股@10、每股1的9元夹具价+100应收=1000，到账只资产形态变化。当前未接Runtime真实分红付款，不据此宣称现金闭环。
- [x] 1000追加500无收益，先涨10%到1100再追加500至1600、后跌10%到1440得TWR=-1%、回撤10%。同毫秒多流、提现、错误边界、重复Flow、错误owner和跨缺价/非正期间拒绝或明确不适用。
- [x] Codex独立手算与真实Hermes独审，专项及标准GUI/CSV/JavaDoc、评分映射和真实记录齐备。
- [x] AI-Generated草稿PR依赖PR19，实际CI通过，等待人工实质review。随后D2董事会授权与D3唯一世界批次实际分红。

## 测试交接与实施裁决

真实Hermes aeafaa交40项仅缺两API的编译RED，76主源前后hash相同。Codex保留原测试副本，移除BigDecimal数字串中的非法下划线，分开未交易的独立开局估值夹具与三笔真实成交的行情夹具，后者保留成交后真实现金与股份。锁定和缺价测试在独立开局同步股份发行登记，分红专用夹具排除无关第二标的，清偿夹具的公司现金同步扣100。修正单点零权益的优先级，按正式Plan先报NON_POSITIVE_EQUITY。

Codex从当前测试源码直接抽取底层夹具函数，在最终D0的76源二进制上独立编译运行，无D1 stub。实际三笔成交的A现金1305.00/持股70、B现金4695.00/持股30，开局未交易夹具仍A100/B0。分红合同100.00、减值40.00后净应收60.00但公司仍欠100.00、未成交买单冻结200.00均实跑核对通过。证据valuation-performance/root-test-review/fixture-run.log。

新增真实未成交买冻结只计一次、登记零资产增加、发行财产主体与付款账户及注册发行组织对齐、真实未来取得批次不得用于较早估值四项，共44项。全部新测试仍取得仅缺API的编译RED，生产尚未授予，核对后新轮授两文件。

- 证券批次acquiredAtMillis不得晚于估值截止。acquiredPosition是证券台账的FIFO本地顺序，不是WorldJournal位置，禁止拿它与book.position比较。证券可卖日可以晚于当前时点，当日买入仍是持有资产。
- D1核对issuerOwnerId对应登记组织、partyId匹配Security.issuerOrganizationId，companyAccountId归该财产主体。这里只校验已给数据的引用，董事会授权真实性继续D2/D3。
- 非COMPLETE的Point两个nav都为空。表现状态优先缺完整估值，再非正权益，再无期间，与作者初稿单点零权益先报NO_PERIOD的断言冲突已纠正。
- 真实来源属于同一截止的外层保证仍由Runtime适配器承担，D1不声称可从无历史来源时标的独立现金Map证明其过去归属。当前44项加原616，预期全套至少660项。

## 独立核验进展

作者交660组合与44专项，两处错误缺价/因子变异取得1项和6项失败并还原。Codex独立26正常检查通过，但7边界有6失败，新增4项正式回归实际3失败1错误后最小修复。当前candidate2含48专项、组合664标准、GUI/CSV53成交140订单7排名、JavaDoc19:07:27、独立26+7及Decimal均通过，最终Hermes独审进行中。

缺价不妨碍记录已知外部资本流。非COMPLETE Point保留Flow及其原身份/证据，nav仍为空，calculate返回INCOMPLETE_VALUATION且收益/指数/回撤为空，不能删掉资金事实。此裁决取代作者首稿对缺价Flow的额外限制。PriceBook逐条验证价格来源时点/age与自身截止，OPENING_REFERENCE固定asOf=0，Long最大陈旧度可合法表示。结构化账户/标的二元键保留全部合法字符串标识，不依赖分隔符拆解。

最终真实Hermescandidate2按78源hash独立重编，44原检查及20窄探针共64/64通过，原两个Important均修复，原76源逐字节相同。D1本地验收完成，当前发布依赖PR19的草稿PR，待实际CI及人工实质review。

估值与表现代码dc90a607bbac4c6a330691f0d35dccc8a861c279已建立[草稿PR #20](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/20)，base为codex/world-dividend-core（PR19）。远端verify run42209352373实际completed/success，已附当前会话，待人工实质review。下一阶段D2董事会分红授权纯核继续施工。
