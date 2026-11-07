# Multi-Agent Continuous Auction Stock Trading Simulation System 项目记忆

本文件只适用于本仓库。可变事实（数量、进度、状态）以当前代码、配置、测试和数据为准。

## 最新接续（2026-11-07 05:42）

当前分支codex/world-web-details，base67665d9。B2b PR29两轮CI均成功。Web详情8新增/1010标准、Root105 HTTP、GUI/CSV46成交121订单7排名、JavaDoc05:34:47及真实浏览器完成；独立Hermes82动态通过，交接已完成，PR30与代码CI42299095951成功。唯一当前台账为主Plan“当前接续”，下方历史状态不代表现行写权。

Root独占主树/Maven；d3c63e exec59426只独审私有范围，ed3b0f exec90021只B2c Runtime完成槽私有候选。16项Inbox私有测试通过含真实时钟边界失败回归，未集成主树。5key仍仅既有51token一次；Web真实角色调用/认知媒体/多公司银行/长跑均待完成。证据docs/05§33，下一合同docs/superpowers/plans/2026-11-07-live-web-runtime.md。

## 启动规则

- 先读 `AGENTS.md` 与本文件，再动代码。
- 单写入者：本仓库当前由一人 + AI 助手维护；AI 生成的改动必须在 PR 描述中标注 `[AI-Generated]`。
- 不写入真实券商、实盘或任何真实资金路径；本项目为纯模拟。

## 长期不变量

### 记账口径
- 金额 `BigDecimal`：金额 scale 2、成本价 scale 4；数量 `long`；禁止用 `double` 记账。
- 买入受理时按限价（市价单按当时卖一价）冻结资金，成交时按实际价结算，差额退回可用现金。
- 卖出受理时冻结持仓，成交/撤单时释放；冻结在 `Account` 内部维护并按订单 id 记账。
- 资金不变量：`Σ可用现金 + Σ冻结现金 + Σ(持仓 × 成本价) = Σ期初资金 + Σ期初持仓成本 + Σ已实现盈亏`。

### 撮合口径
- 成交价 = 被动方（挂在簿上那一方）价格。
- 订单簿 = `TreeMap<价格, ArrayDeque<Order>>`：价格优先靠有序键，时间优先靠 FIFO；
  用 id 索引支持 O(log n) 撤单。
- 限价单未成交部分挂簿；市价单未成交部分立即撤销并释放冻结。
- 撮合后订单簿必须不交叉（bestBid < bestAsk）；收盘撤销全部挂单并释放冻结。

### 并发口径
- 撮合的校验 → 冻结 → 撮合 → 记账 → 改簿 全在同一把锁内完成。
- 锁顺序固定「引擎锁 → 账户锁」；账户层永不回调引擎（无反向加锁路径，故不死锁）。
- 行情广播、CSV 落盘、GUI 更新一律在锁外；GUI 更新必须 `Platform.runLater`。

### 三个必须存在的机制（否则系统跑不起来）
- **初始持仓**：规格只给了初始资金，若所有人都只有现金则卖出必然失败、首笔成交永远无法产生；
  因此配置里必须有 `agent.<类型>.initial.holdings`。
- **开盘首推行情**：行情快照原本只在订单受理/撤单后推送，而 agent 的决策依赖最新快照 →
  会形成「没人下单→没行情→没人下单」的死锁；`SimulationRunner.start()` 必须先推一次开盘行情。
- **agent 必须能管理自己挂单的生命周期**：限价单提交即冻结资金/持仓，只有成交或撤单才释放。
  若 agent 只挂不撤，冻结额单调累积 → 耗尽后 agent 停止下单 → 全场只剩永不成交的陈旧挂单。
  因此每个持续报价的 agent 都必须能撤单改价（报价刷新），并在额度不足时先撤旧单。
  新 agent 直接复用 `AbstractTradingAgent` 的 `cancelAllOpenOrders` / `trimQuotes` / `ensureCapacityFor`。
- 新增 agent 必须订阅 `MarketDataPublisher`（历史 bug：agent 未订阅导致市场永不下单）。

### 交易行为口径
- **限价单价格等于最新价永远不成交**：最新价必落在买一卖一之间，所以想成交必须主动吃对手价
  （买用卖一、卖用买一）。这类错误不报错，只会让某个 agent 静默地一期都不交易。

### 绩效口径
- 收益率基准 = **初始权益**（初始资金 + 初始持仓成本），用初始资金会把持仓成本误算成收益。
- 最大回撤基于权益曲线峰值到谷值；胜率 = 盈利平仓笔数 / 平仓笔数。

## 验证原则

- 声称完成前必须有真实验证证据：`mvnw.cmd test` 全绿（当前 36 项）、控制台演示真实产生成交与排行榜、
  GUI 改动需实跑并看到 `[AuctionUi] 界面已启动` 日志。
- 集成测试必须真起线程（`SimulationIntegrationTest`），只做单元测试不足以证明市场能运转。
- **回归测试必须验证过它能失败**：把修复点临时改回旧行为跑一次，确认测试真的挂掉，再恢复。
  一个从没失败过的回归测试等于没有测试。
- 金额类不变量必须用「守恒式」断言，而不是只看单个断言数字。
- 修复 bug 必须写回归测试；不得删测试或放宽断言来掩盖失败。

## 扩展世界的实现约束

- 总体目标及已确认语义见docs/design/总体设计基线.md，进度及接续以docs/superpowers/plans/2026-11-06-world-implementation.md为准。
- 世界变化通过唯一WorldJournal批次提交，候选持久确认后发布。钱股、订单与待办在同一Snapshot中恢复，课程CSV用于导出核验。
- 机构目标采用账户标的绝对股数。目标更新停止新子单，旧单实际撤净后以最新真实持仓建立新执行基点，再审核确认，历史成交已包含在实际持仓中。
- 四岗执行采用研究报告实际阅读、经理目标、风控审核、经理确认、交易员子单的流程。人物会话绑定观察任职，工作流只保存元数据及真实订单链接，金额和持仓从原世界账派生。
- 规则档案显式启用其能力及状态版本，旧档案继续沿原序列化与行为契约恢复。实际验证数量和最新PR状态从当前记录与远端核验。
- schema7四岗会话固定全部组织任职，实时资产、资料和工具取原观察任职与当前资格的交集。组织阅读完成从原命令恢复该绑定，个人经历保留，公开与本人直投阅读按个人能力处理。
- 新目标版本基点取创建批次的实际证券持仓，原请求与历史前缀共同保护版本。目标截止、审核期限和执行任期分别排程，自动任务按业务目的去重并寻找空闲标识，Long最大时间值仍可合法排程。

- 决定配置随State冻结，尝试保存准确输入、原回执、声明用量与一次结算。重试沿用原观察和屏障，恢复代次由宿主明确接续，未知用量保守结算不改变人物资产。脚本费用BigDecimal两位0.00，调用/token计数与真实计费区分。

- schema8决定桥保存完整宿主请求，重放在观察投影和当前人物OPEN检查之前。重开后原session命令仅重放回报，本进程门闸仍关闭，须新命令接续。初次零预算也保存带绑定的零尝试终态。
- 异步回执与无回执失败共用有界队列，写入成功后移除。runner完整尝试身份先占用再排程，自建线程池只一份，队列满保留结果供retryPending，不二次执行脚本。完整日志确认位置必须与候选一致才可发布。
- 恢复核对每个决定与原任职、动作引用、原业务指令、待办或准确完成回报。输入包含本人未完成动作原参数及可操作的本人阅读引用，排除内部序号。schema8复用严格映射，禁止嵌套浮点截断为整数。阶段证据以docs/05第20节及主Plan现测为准。

- 决定审计复用WorldJournal附加事件与只读DecisionAudit，正常回执批次数保持，身份复用拒绝独立落审计。完整Reply含原文、声明与来源形成去重，已提交的拒绝宿主编号同样冻结。旧代次首回执保留第一归档，后续异回执分类拒绝并继续队列。
- 审计绑定真实批次和前后尝试状态，墙钟仅为宿主许可、成功投递入口与提交准备观测。恢复与读取只复现确认历史，原进程未知时点保持缺失。schema8状态不增审计副本，实际旧7/8新旧二进制交叉验证见docs/05第21节。

- 分红D0登记只固定含冻结/LOCKED/当日买入的remaining权益并排除TREASURY，登记零应付，除息才生成claims。持有人减值不消灭公司债务，整方案付款元数据记录减值转回。Receipt保留完整原请求及Event三字段，恢复逐事件核对阶段与金额历史。D0不执行现金转账或验证董事会授权，D1估值/TWR、D2授权、D3真实同批公司行为仍需接续，证据见docs/05第22节。

- D1账户估值只加现金总额一次，remaining按原始价计价，缺价保留空净资产及缺失标的，组织完整经营净资产不支持。表现用显式流前后估值几何链接，DECIMAL128中间计算、输出12位HALF_EVEN，已知资本流与缺价事实可并存。证券acquiredPosition为本地FIFO序号，不能冒充世界位置。真实运行来源、资金事实和公司行为桥仍需D2/D3，证据见docs/05第23节。

## D2 Root恢复核验与写权接回

作者29119已退出释放，Root独占BoardModel/测试/Runtime/Maven。首稿candidate1的Root私有完整重签探针实际10失败，新增12正式测试取得42专项中10失败，缺字段合法0提案对照与重签合法对照通过。已修新Topic时序、主席/成员/回避、票/材料时间及阶段、及时授权、DTO必填、已实施和活动方案互斥。真实Hermes首稿独审38合法全过、forge16/20，报告三类时间缺口，已退出；需最终freeze复验。原77源首稿相同。

Root以旧D1二进制实际生成治理日志，15帧前缀legal按新D2恢复BUDGET，首帧只改Topic并重签可恢复DIVIDEND_AUTHORIZATION，而旧二进制拒枚举。已新增Runtime回归取得真实RED，按正式Plan授权加入schema<=8的窄分红议题能力闸。旧664断言未改，仅在既有RuntimeGovernanceTest新增一项。当前组合目标707=664+42+1，尚未最终标准验收。证据dividend-board/{root-probe-1,root-regression-red,legacy-gate-*-2,root-runtime-red}.log，首轮私有脚本事件名错误证据保留，正确类型STATE_SNAPSHOT_V1的第二轮控制已成立。

## D2最终本地验收

最终candidate2冻结78源，BoardModel815c8e7f...a09951e及Runtimeee0817b1...987a92c0，其余76生产源与D1逐字节相等。新47专项中14失败1错误的首稿RED已真实取得并修复，另1旧档能力闸恢复RED已修。最终712标准、52成交133订单7排名CSV、GUI、JavaDoc20:14:36通过。Root13分红+146世界+72审计检查，原编译旧客户端及旧构造、三golden逐字往返通过。真实Hermes最终独立重编74/74及旧档合法/伪造对照通过，已退出释放。当前Root独占源码/Maven，准备草稿PR，仍待人工实质review。D3从制造报表与除息参考接缝开始，随后完整新档案桥，不把纯核误记为真实付款。

董事会分红授权代码eb9d96e2d0d69c63954b9539d5a01e6054a49dad已建立[草稿PR #21](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/21)，base为codex/world-valuation-performance（PR20）。远端verify run42217088050已实际completed/success，已附当前会话，等待人工实质review。D3a财务与除息参考接缝继续按新施工单推进，M3整体运行闭环仍在施工。

## D3a测试先行写权

当前codex/world-dividend-financial-seams base d100a8a。D2草稿PR21代码eb9d96e实际verify42217088050成功并附会话，文档head d100a8a待核CI。正式D3aPlan已写2026-11-06-dividend-financial-seams.md，两个纯核接缝，原712保留。真实Hermes aeafaa测试轮仅拥有新ManufacturingDividendTest/DividendReferencePriceTest及C Maven，55工具1800秒，未授78生产源。Root不可编辑两测试或跑C Maven，仍可docs/private，测试交回核对后再授生产。生产仍D2最终78源，暂无main写入者。

## D3a测试核对及生产裁决

作者29项缺API RED及78源未改证据已交。Root保存两原测试于dividend-financial/root-test-review。两个多方案夹具原先先完成S1除息再回到更早登记时点propose S2，违反D0时间单调。改成同一时序中先提案授权两个方案、同截止分别登记、再依次除息，保持生产规则。Root抽取修订测试的真实底层helper，在D2冻结classes独立编译运行，两方案、四个实际成交及D0清偿/减值均通过，fixture-run.log。

新增成交位于两次除息之间只扣后一次、PROPOSED/AUTHORIZED/CANCELLED不调参考及null D0拒绝三项，共32，testcompile仍仅缺新API。制造新validate三参必须返回Report，与旧签名和正式Plan一致，作者handoff把它写成void属于交接笔误，测试本身已要求Report。null DividendPosition或新prices的D0入参采用明确IllegalArgumentException，精确金额约束沿CashLedger.money，旧行为保持。

现在可正式只授ManufacturingModel、ValuationModel和两新测试及C Maven。原其余76生产源及旧712测试保持。目标744组合与32专项，Runtime/档案/角色不在本单施工。D2文档head d100a8a的verify42217584157亦已实际成功。

## D3a生产执行中

真实Hermes aeafaa exec64131当前独占ManufacturingModel、ValuationModel及两新测试和C Maven，90工具3600秒，implementation-query已明确授写。Root不得编辑四文件或跑Maven直到交回。已见32专项/744组合首绿，作者正在真实变异，源码可能短暂为MUTATION，不可冻结当final。Root只准备未编译CodexDividendFinancialProbe.java，使用原真实世界持仓、D0权利、实际CashLedger各腿与制造报告/除息price纯组合，需交回后冻结编译。D3b只读contract48124已交草案，尚未采纳，存在合并措辞错误、GovernancePayload数目错误、时序now+3过严、取消引用不存在BoardModel.cancel及impair使用seat代替holderowner等问题，须Root正式Plan纠正。D2文档CI42217584157成功。

## D3a Root接回与当前证据

作者exec64131 exited0释放四文件/Maven，Root现在独占。candidate1冻结78源，ManufacturingModel3d134a9a...410e421、Valuation93300cf3...0a332，其余76源与D2相同。作者32/744及两变异4失败/3失败还原。Root独立javac后44组合通过，Python Decimal独立复算2000现金/1000股、200应付→0、权益2000→1800、利润0、投资者NAV保持和15减值转回。私有首轮三外部账户误同owner导致mfg.open拒绝，已改分别owner，保留失败日志，不属产品缺陷。原D1编译客户26+7在新生产classes通过。

标准exec67639已完成744全绿、49成交128订单7排名、GUI及JavaDoc21:02:42。真实Hermes849817 exec10994独审冻结candidate1正在私有javac运行，尚未交最终report，勿省略该结论。下一接审查、修复若有、docs与D3a草稿PR/CI，再D3b。D3b Root裁决稿在dividend-runtime-preparation/root-bridge-plan.md，尚未正式授测试，优先于先前只读建议，明确13字段、最紧t/t+1/t+2/t+3、holderowner减值、单一Price reference含age、报告不复制D0位置、实际公告及保留文档空间；取消需后续单独接线。

真实Hermes849817最终独审从candidate1核78源hash并独立重编，制造17及除息参考26项自写断言合计43/43通过，未发现可复现缺陷，报告dividend-financial-review-candidate-1/handoff.md。Root随后仅移除新测试Fixture的三个未使用分量，所有断言及生产源保持，重新运行clean标准744全绿，最终CSV为37成交、119订单、7排名，JavaDoc21:11:18重新生成。最终日志standard-root-final-1-raw.log及javadoc-root-final-1-raw.log，冻结standard-final-raw。先前49/128及21:02:42为首轮结果，保留原证据。D3a本地验收完成，当前发布草稿PR及等待实际CI。

财务及除息参考代码6220ed85275eda2826b17054ac570c32f01dadb9已建立[草稿PR #22](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/22)，base为codex/world-dividend-authorization（PR21）。实际verify run42223835571已completed/success，已附当前会话，待人工实质review。D3a验收及发布完成，下一D3b同批分红运行桥按正式施工单推进。

## D3b最终本地验收与接续

当前分支codex/world-dividend-runtime，base490f20c。Root拥有所有生产/测试及Maven；真实作者aeafaa已释放，独审849817最终candidate5已交回。schema9真实董事会授权、下一毫秒PUBLIC公告、证券登记、除息、足额现金付款及持有人减值已接入唯一WorldJournal。四已有生产及新Demo变化，其余74生产、D0/D1/D2/D3a和旧744测试保持。严格同批来源重算覆盖原请求、待办、会议、公告、账务、报告及完整事件。

最终candidate5冻结79源，Runtime SHA256 CD2FA09FB0DB265B10FD5AF4D7C47D25CB1EBE4F26F8848FFA0EA81D063827E0。标准816项通过（新增72），CSV46成交/121订单/7排名，GUI启动，JavaDoc2026-11-07 00:20:41。Root独立47正常+74完整帧+13严格原请求+78故障共212检查通过，39帧Decimal现金4600/200股，旧二进制构造与schema8新旧交替接写、Demo三JVM均通过。两真实变异3失败/1失败后已还原再验。真实Hermes最终自编79源、31合法及6完整重签反例全部通过。详情docs/05§26，私有证据logs/implementation-20261106-continued/dividend-runtime。当前发布草稿PR及等待实际CI，禁止把整个总体基线标完成。

D3c正式契约2026-11-06-capital-flow-performance.md作为接续计划随本阶段归档，实施仍待D3b实际CI后授权。Runtime-only类型化真实资本转移，复用现金/Notice，资本事件不复制NAV；每个确认帧重算表现，保留减值回撤。真实Hermes只读契约22检查通过，R1公司资格到期/R2两处元数据幂等/R3Point候选位置已裁决。Root资本探针尚未编译，不能把准备数字当实测。下一从D3b文档head建codex/world-capital-performance，先新测试RED再授生产。

## 2026-11-07用户最新交付与凭据要求

最终交付形式确定为Web，上帝视角须能看到具体key配置。用户指定本机C:/Users/JXGU/api-key/deepseek/java-stock-trade.txt，已仅在内存核对为5行、5个互不相同的key，未显示值、未发起服务请求。可按场景配置并由Codex判断是否测试。实现时凭据仅服务端加载，角色只看到模型别名，日志/恢复/导出/仓库不得含密钥。上帝配置默认遮罩，受限本机管理员显式查看完整值；请求不能被网页第三方来源触发。具体场景映射和有界真实调用待实现与核验，不宣称已接通。此为用户对Web候选的明确裁决，保留JavaFX课程入口。详见主Plan与Web凭据施工单。

M6原离线审计页用户回复稍后手动查看，尚未显示验收，不绕过此前浏览器本地HTML策略。真实Hermes协作保持已有两个会话；不清理Temp、不触碰原G工作树及两个stash。所有PR保持草稿，等待人工实质review，无自动合并授权。

## 2026-11-07远端合并状态核对

GitHub实际查询显示PR6–22于2026-11-06 23:35–23:41（上海时间）由人工账号gujixuan12-rgb合并。PR22落到codex/world-role-trading，最新累计head de20df0d64fb9bd405c58d8c9984fabc440fc376；它包含原490f20c且文件树完全相同。main当前fdbe62f只接收较早阶段，不能将“PR均已合并”误称全部能力已进入main。上述阶段的“待review”是当时记录，当前合并状态以此条为准；未读取人工评审内容，不额外宣称实质review覆盖。

D3b代码a133bbd6657b3111f3af20e03e07eba2d08a087a已推送并建立草稿PR23，base改用现存累计分支codex/world-role-trading。首次向已删除分支创建PR失败，没有改动代码；核对原基线祖先关系及相同文件树后重建成功。链接https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/23，已附当前会话。真实CI run42253286290已completed/success。新PR仍须人工review，Codex不执行合并。

## D3c资本流最终本地验收

当前codex/world-capital-performance basebd200157。Root独占主源码/测试/Maven，生产仅Runtime变动，原78生产与816测试保持。schema9资本元数据同时进入排程请求/待办幂等，真实付款后唯一CAPITAL_FLOW，方向由有限两侧owner派生。新9排程/完成/Notice逐批从原来源重建完整候选，普通批保护待办。每确认帧估值，起点流后无Flow，缺价仍保留已知资本事实，D1允许该组合，不新增NAV状态。

最终candidate3 Runtime5BEAA4D0…4D839DA，42专项/858标准，CSV47成交121订单7排名、GUI与JavaDoc01:38:11通过。Root20+44+59=123独立检查与12帧资本/39帧分红Decimal通过，原D3b212/旧8/三JVM在candidate2通过；candidate3仅一行溢出guard。真实Hermes849817独立20+2及作者修正后12no-op控制/12反例在最终独立classes通过。作者初始浮点重写导致假覆盖已纠正，后发现恢复溢出异常类型真实缺口，正式42th RED后修复。详情docs/05§27。

当前准备D3c草稿PR/CI，未完成总体基线。Web私有候选由Root19HTTP检查取得11失败（精度/严格JSON/会话/异步结果），aeafaa仅拥有web-root-candidate-1私有src/static修复，禁止主树/Maven。849817资本独审已释放。实际五key仅加载验证，没有提供方请求。

资本流代码93d4b7ae18807a745317622526111dccbdef8a7b已推送并建立草稿[PR #24](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/24)，base为codex/world-dividend-runtime。真实verify run42264571970已completed/success，已附当前会话，等待人工实质review，Codex不合并。下一阶段Web正式集成沿五key配置Plan，私有候选的严格输入/身份切换修复进行中。

## 2026-11-07 Web 工作台当前验收

分支codex/world-web-workbench base8311e7a。资本PR24代码及文档CI均已实际成功，仍OPEN未合并。新增7生产/3资源/7测试类，原79生产与858旧测试保持。最终candidate3冻结86源，Root4项缺陷RED已修：失败启动退出码、满队列自关闭、倒退时间400、无决定模块的屏障查询。902标准/43成交119订单7排名/GUI与JavaDoc03:05:52通过，Root24独编HTTP（含超2^53真实数值）通过。candidate2真实Hermes56独审全绿，candidate3一行闸增量独审进行中。

真实浏览器已实点：登录/推进、五场景保存、默认遮罩、dialog显式查看与自动清除、离开页清除、切alice后管理DOM清空、刷新保持PERSON、轮换引导返管理员、同一世界及key-5/调用3配置重启保留。Postman22请求56断言通过，仅dummy凭据、禁止远端报告。原离线审计HTML仍待用户手动检查，不混同。

Root拥有全部主树/Maven；作者aeafaa仅拥有deepseek-adapter-preparation私有目录（exec26122）修Root7项中5个边界失败，不能用Temp/真实keys/主树/Maven。审查849817仅拥有web-independent-final私有目录（exec9516）复核candidate3，不能Maven/主树。真实五keys未发提供方请求。DeepSeek适配器只是私有准备，未集成世界，成本未知不能填0当账单。总体基线继续施工，下一发布Web草稿PR/实际CI后接正式适配器和Web业务链。

最终candidate3真实Hermes增量独审已完成：86源hash一致，独立重编后64/64通过；同探针对candidate2取得6个cash-only失败，证明模块闸修复有效。Root24独编HTTP、902标准/43成交119订单7排名、GUI/JavaDoc及真实浏览器均已通过，现发布草稿PR，等待实际CI；总体基线继续施工。

Web工作台代码3bbf337c8cbb87ef07ca2546a082ac73ee56e075已建立草稿[PR25](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/25)，base为codex/world-capital-performance；实际[CI42277171062](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42277171062)已completed/success。最终独审64/64。下一按2026-11-07-deepseek-provider.md继续，私有修复已由Root独立重新编译后7/7通过；真实keys未调用。Root另读出可选cache字段非法类型被当缺失、超int范围choice index可能截断为0两处待正式RED核验。整体基线未完成，无自动合并。

## 2026-11-07 DeepSeek A 当前状态
当前分支codex/world-deepseek-provider，base5f68a16。Web PR25代码CI42277171062与文档CI42277945916均实际success。Root收回全部主树/Maven；适配器新增1生产/1测试，原86源/902旧测试保持。正式23先真实3行为RED后修，925标准/CSV47成交126订单7排名/GUI及JavaDoc03:27:17通过；冻结6436d61e…a2cbc，Root独编10通过，纠正审查夹具后42/42对旧版9失败。真实Hermes849817仅私有最终补正index漏usage，exec93387；不能Maven/主树。
真实key-1已于03:30:15单次flash调用成功：46输入5输出51总、cache hit0/miss46、931ms、预期JSON正确、128输出/15秒/无重试。实际费用null未知，其余4key未调用；原文/凭据未归档。禁止重跑live-once：唯一尝试标记已创建。世界connected=false，适配器不是世界运行桥。
aeafaa exec30920仅私有live-decision-core-preparation准备新profile/未知费用纯核，禁止主树/Maven/真实keys/Temp。下步接最终独审、发布适配器草稿PR/CI，再纯核正式RED及版本化接线；B草案见live-provider-preparation/root-decisions.md。总体基线仍未完成，不自动合并。

DeepSeek候选6436d61e…a2cbc最终真实Hermes独审已补正：每个index反例有含合法usage的成功基底，独编42/42；相同探针对旧客户端33/42，9失败包括两个截断为0的整数，先前37项中5个index断言因缺usage是假覆盖，原报告已另存superseded。Root独编10与纠正版42、925标准/47成交126订单7排名/GUI/JavaDoc及单次真实51token均通过。当前发布适配器草稿PR并等待实际CI，世界运行桥与总体基线继续施工。

有界提供方代码bd07ba0d6ae636c32c968e67b3531b52453b138d已建立草稿[PR26](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/26)，base为codex/world-web-workbench；实际[CI42280670472](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42280670472)已completed/success。A传输阶段验收完成，仍待人工review；下一按2026-11-07-live-decision-core.md实施现有DecisionModel的B1版本化用量/预算纯核，私有替代模型的62项不计正式覆盖。世界运行桥未连接，总体基线继续施工。

## B1当前写权与接续（2026-11-07 03:43）
当前codex/world-live-decision-core base72d90d5，PR26代码CI42280670472及文档42281109727都success。A最终925标准/10+42Root/42独审/单次51token完成，真实调用禁止重复（live-once标记存在）。
作者aeafaa exec83657独占DecisionModel.java、WorldRuntime入口guard、DecisionModelLiveTest与WorldRuntimeLiveProfileGuardTest及Maven。正式RED已取；Root改正了两处错误测试期望（未知未结算不能是已知0、无status的已知正费用应保旧合法），补逐人预算释放/未知全额结算/旧7参provider拒绝，预计22新测试，尚无生产通过证据。Root修正RED在live-decision-core-root/formal-root-corrected-red.log。
Root准备真实旧类已编译客户端RootLegacyDecisionClient及legacy-classes，legacy-before.log的完整State SHA8297222c39590150ea873b86fae4aabb919f72724eb87a8e3ccecd1792a66afa；最终只换类不重编客户端核对。RootLiveDecisionProbe.java准备24检查未运行。
B1新真实Hermes独立审查 exec22858（新session ID尚待log输出），仅live-decision-core-independent准备对真实DecisionModel的探针，不改主树/target/Maven/Temp。新上下文理由：前独审旧假设导致两次假覆盖，已明确修正后另开本模块独审；不是Codex子代理。旧849817本阶段释放。私有toy LiveDecisionCandidate62检查不是正式实现，不能计覆盖。B1正式Plan在docs/superpowers/plans/2026-11-07-live-decision-core.md；B2路线与派发持久化仍未实现。

## 2026-11-07 04:06 B1 当前接续

Root现拥有全部生产/测试/Maven。B1最终candidate3为DecisionModel2d31206a…b82b2、Runtime57cb0782…bf4d；953标准、49成交121订单7排名、GUI、JavaDoc04:06:05、Root24+4、旧二进制JSON精确与同journal新time6/旧time7现金4100已通过。候选1全局重试漏洞与候选2费用状态4反例均有真实RED并修复；旧925断言保持。

真实Hermes afc0c5仅私有live-decision-core-independent终审candidate3（exec10133），不得主树/Maven；aeafaa仅私有live-provider-preparation按新正式B2a Plan准备真实模型测试/旧调用client（exec92035），不得主树/Maven。旧写权83657/22858/49068/58461已释放，不可继续沿历史授权写主树。B1尚待最终独审和PR/CI；B2a正式Plan为2026-11-07-scene-decision-budget.md，未授生产。真实keys仅此前一次51token，本轮未调用。总体基线未完成。

B1最终candidate3已由真实Hermes afc0c5独立核87源并重编：原40+补充2+新增费用15，共57项动态纯核断言全过；另11静态检查，Runtime部分仅静态，未冒称其做过动态测试。Root的Runtime配置2对照2拒绝及正常/复用/旧代次真实journal隔离测试独立成立。独审报告将Root“4项Runtime RED”混称，实际是Runtime测试类4测试，其中新增1测试含3入口分支；真实RED为该新增测试，数量以JUnit日志为准。发布前实际五key逐字扫描199个仓库文件通过。当前本地验收齐备，准备AI草稿PR，人工review与CI待远端事实。

真实模型预算纯核代码269e854d88f840f3c7890b5a441ec2333addb3d6已建立草稿[PR27](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/27)，base为codex/world-deepseek-provider；实际[CI42285963075](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42285963075)已completed/success，已附当前会话。本地953/标准GUI/CSV/JavaDoc和独审57通过，B1工程验收完成，仍待人工实质review，未合并。下一按2026-11-07-scene-decision-budget.md继续B2a。Root已拒收私有新测试的无效预算/输出边界/不存在动作超额等夹具，要求修正，不把缺API RED当语义正确。

## B2a 测试先行写权（B1 PR27 CI之后）

当前codex/world-scene-decision-budget base6db6963；B1代码269e854/PR27实际CI42285963075成功，文档6db6963的CI42286406476仍进行中。aeafaa exec86025仅新DecisionModelRouteTest及Maven独占，87生产尚未授改。Root已拒收9类私有夹具（scripted零费用误判UNKNOWN、非法Route先抛、scene cap未入Route、x2不存在、conditional跳过伪造等），详细反馈live-provider-preparation/b2a-root-test-review.txt；不得把缺API编译当语义已通过。afc0c5 exec9628仅scene-decision-independent准备独立实际模型探针。Root负责文档、契约和独立验收，不并发跑Maven或改新测试。下一接回正式测试核对，再授DecisionModel单文件实现；不启用网络或真实keys。

## B2a Root测试核准与新作者写权

旧aeafaa exec86025已退出，主树正式测试14项仍有cap256却要求1168预留成功、把run/person输入1100当总cap、REJECTED空decision解引用和可跳过retry断言。Root接回后纠正这些新测试夹具，并补7项真实场景边界/UTF8/低cap恢复retry/冻结slot-model-output/实际退款不退次数/按plan输出超额/跨scene全局revision，共21项，缺API RED在scene-decision-root/formal-root-corrected-red.log；原953断言不改。

旧作者已有1042消息且连续两轮夹具错误，本模块改用新真实Hermes实施上下文（exec75641，session ID待日志返回）。新作者仅DecisionModel.java与scene-decision-author私有目录及Maven独占；新21测试只读、其它86生产不授改，预算80轮2400秒。Root不并发Maven或写DecisionModel。afc0c5独审准备51项已交且exec9628结束，未跑正式候选，不计通过。旧aeafaa不再拥有任何主树/Maven。

Root另用B1最终冻结类独立编译旧16/10构造客户端，class哈希31e0495d…87f05、完整JSON指纹90a7032d…8bf76，真实运行合法WAIT且无plan字段；后续不重编同class以验新代码。PR27文档CI42286406476也实际success。B2a仍施工，未新发真实key请求。

### 2026-11-07 B2a接续

场景计划及累计预算纯核candidate2本地验收：23新增/976标准、44成交123订单7排名、GUI/JavaDoc04:35:08，Root23及真实Hermes58独立通过；DecisionModel768a4ee6…fa8c5，旧客户端JSON完整指纹90a7032d…bf76保持。正式证据docs/05 §31及scene-decision-*私有日志。当前发布草稿PR，B2b/schema10持久派发与B2c/Web真实调用继续，不能当总体完成。

Root独占主树/Maven；真实作者20261107_041840_ed3b0f仅live-runtime-preparation只读准备（exec51627），无主树写权。独审20261107_034237_afc0c5最终58全绿已退出。旧aeafaa不再拥有写权。无新增真实key调用。

场景预算代码a883e99192fd7822e9d5861db5560a86d436075f已建立草稿[PR28](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/28)，base为codex/world-live-decision-core；实际[CI42289821622](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42289821622)已completed/success，已附会话。本地976及Root23/Hermes58通过，仍待人工review、不自动合并。下一按2026-11-07-live-decision-runtime.md实施B2b。作者ed3b0f只读准备exec51627已退出，当前Root独占主树/Maven。


### B2b生产写权（当前）

分支codex/world-live-decision-runtime，base cb5afcd；PR28代码/文档CI42289821622与42290156095均success。Root已核23新增测试缺API RED；正式Plan2026-11-07-live-decision-runtime.md。真实Hermes ed3b0f exec26075仅RunManifest/DecisionRun/WorldRuntime及Maven独占；新两测试和其它84生产只读。Root负责文档/私有证据与最终验收，不并发改三生产或跑Maven。afc0c5独审20检查源已准备，尚未新API行为运行。总体仍在实施，真实keys仍仅既有51token一次连通。

### B2b 05:12 接续与真实缺陷

作者ed3b0f第一实施进程已退出。Root修新测试漏Role import、Thread.sleep受检异常声明及cmd:t-1定位三项夹具；作者私有23+原976通过不是正式验收。Root正式25专项已运行：原23通过，新增2项真实生产ERROR（静态DTO非法回执不能提交；含引号失败JSON非法）。第一次追加测试误用Java21 getLast导致release17编译失败已改索引，不计生产缺陷。日志live-runtime-root/formal-new-regressions-red-2.log。

candidate1恢复marketOf读取未初始化state导致NPE，Root第二JVM与真实独审均确证；candidate2改传入snap后Root三个独立JVM8+10+10=28检查通过、旧编译4/3/8客户端JSON指纹保持，探针无网络。candidate2不是最终验收：上述2项仍待修复。Root授权ed3b0f exec70339仅Runtime及Maven，tests只读；afc0c5 exec71686仅candidate2独审私有目录；d39b13 exec11548仅Web详情私有镜像补唯一精度断言。Web首轮95检查94过，1项需诊断，不已接入主树。真实5key仍只此前一次51token连通。

### B2b最终本地验收（05:21）

Root已接回全部主树/Maven。最终candidate3 Runtime6f3f1aa3…a21e532，1002标准/40成交126订单7排名/GUI/JavaDoc通过，Root三JVM28、旧二进制JSONf6f6081b…bc871、真实旧9新time8/旧time9现金4100通过。真实Hermes afc0c5独立87源重编26动态通过，含DTO/安全失败/full taskId改名/纯拒绝批末帧平衡移转。详情docs/05§32。当前发布草稿PR/实际CI待确认，总体仍施工。

B2b作者ed3b0f exec16971现在仅live-web-preparation准备B2c合同，无主树/Maven写权；Web作者d39b13 exec82370仅web-details-preparation/tests私有准备真实详情回归，最终Web5文件105HTTP检查已交；独审89624最终已交并退出。Root不把私有Web候选视为正式交付。B2c草案2026-11-07-live-web-runtime.md已留入口，尚未授权生产；5keys仍仅原51token一次。

B2b代码f56dea4845fbc242ef4bfa2ec18b85b69160001e已建立草稿[PR29](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/29)，base为codex/world-scene-decision-budget；真实[CI42296162891](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42296162891)已completed/success，已附当前会话。1002标准及最终独审26通过，本阶段工程验收完成，等待人工review、不自动合并。下一独立分支集成Web四组只读详情；B2c完成槽仅私有候选准备，不能先称模型已驱动世界。

Web详情代码90e726a70c7d53b16cc255e6b897e56f1ca0ac81已建立草稿[PR30](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/30)，base为codex/world-live-decision-runtime，已附会话。实际[CI42299095951](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42299095951)已completed/success。1010标准、Root105 HTTP及准确400/401、真实浏览器与独审82通过。本阶段工程验收完成，待至少一名人工review，不自动合并。
