# M3d D0 现金分红权利与应收纯核

当前基线071b4e9c3ec0283e867b886c22501ef0a32bd027，PR18代码和文档head的远端verify均已成功。依据总体设计基线5.2与6，本单完成权利、应收与清偿元数据的纯计算。运行桥、真实公司付款、董事会授权实施、价格参考、NAV与TWR在后续D1–D3接入。

## 范围与数据

- 唯一新增生产文件为world/DividendModel.java，唯一新增测试为world/DividendModelTest.java。当前先授测试，生产权在真实RED交回后另轮授予。不得修改Runtime、旧纯核、任何配置或原573项。
- 复用真实SecuritiesLedger.State及其Lot，金额BigDecimal规范到scale2且不舍入，数量long。可精确表示的1/1.0规范为1.00，0.005拒绝，金额很大仍用精确BigDecimal乘数量。
- 不在D0持有现金账户、实时持仓、价格/参考调整、董事会表决、通知、世界任务或I/O。历史权利快照保存登记股数与面值。原准备稿中的ReferenceAdjustment、fromPrice与recordShortfall接口不进入本单。
- 不可变方案保存schemeId、symbol、发行组织/财产主体与公司账户标识、每股金额和recordAtMillis/exAtMillis/payAtMillis。时点直接采用非负模拟毫秒，recordAt严格小于exAt，exAt不晚于payAt，Long最大合法时点可使用，不转换日乘法。
- 支持PROPOSED→AUTHORIZED→REGISTERED→EX_BOOKED→SETTLED及登记前CANCELLED。authorizationRef是宿主授权引用，D0保存该引用，真实性由后续董事会运行桥核验。不得把字符串引用称为已验证表决。
- propose、authorize、cancel、register、bookEx、settle、impair都有明确的宿主事件ID及提交位置/模拟时点。公共API采用少量不可变records与静态纯函数，允许在测试交接中给出最小DTO形状，由Codex核对后固定，不建提供方/插件等通用框架。

## 共同语义

1. 新事件的世界位置严格递增，模拟时点不倒退，允许位置有间隔。相同事件ID与完整原始请求重放在当前状态/时序校验前返回原回报且不追加。异内容复用拒绝并保留完整旧State。位置只作次序与审计，不能替代稳定事件ID。不得只把position作为幂等键。
2. 登记在固定recordAtMillis取得该时点的权益快照，按symbol聚合所有remaining数量。FLOAT及LOCKED、卖出冻结与当日买入尚不可卖批次计入，TREASURY排除。接收SecuritiesLedger.State，复用其批次/冻结一致性校验。重复登记不重新取后来持仓。登记截止与实际源世界快照的对应由后续Runtime核验。
3. REGISTERED只固定归属，claims为空，公司应付和投资者应收均为0.00。snapshot.totalAmount是固定权利面值，尚未入应付。原准备稿把登记时companyPayable等于总面值的建议撤销。
4. 在exAtMillis的bookEx生成一次claims，面值为登记股数乘每股金额。此时companyPayable才等于全部合同未付额。股息参考由D1/D3在同一世界除息批完成，D0不计算价格。登记后转卖不转移既有权利，不能按付款时持仓重算。
5. 首轮清偿按方案全部未付权益整批确认，settle需要非空cashEvidenceId、准确amount与每个claim的付款明细，时间不早于payAt。明细须覆盖该方案全部未付、各项金额及账户一致。付款不足、部分、超付、跨方案、缺证据均不改变已付。纯核只保存宿主现金事实引用，后续Runtime须同批逐笔核对公司真实出款及各受益账户，不以本单结果声称真钱已到账。
6. paid最多等于face。投资者净应收为face-paid-holderImpairment，公司应付为face-paid。减值带明确事件与证据，最多达到未付面值，保留审计。减值不修改合同面值、paid或公司未偿债务。整批真实付款确认时清除对应减值，并在清偿记录保留转回额供D1核算投资损益。
7. 已SETTLED或CANCELLED方案不能重新登记/除息/付款。登记后方案修改走新方案ID，本单只允许登记前取消。所有原权利、确认、付款及减值历史只增不改，集合深不可变，账户/方案引用不可串用。派生claim标识须无歧义，禁止schemeId加冒号accountId的别名碰撞。
8. State构造与fromJson校验完整引用、阶段/时间、面值合计、付款/减值累计与原事件及证据、已付终态、全局位置/事件唯一和不可逆历史。toJson/fromJson使用现有Jackson并严格拒绝重复键、尾随及整数浮点截断。合法State精确往返，不能仅凭合法JSON接受伪造已付或改写权益。
9. 静态DTO非法形状按现有纯核风格在构造时拒绝，合法形状的业务拒绝返回不可变Result及原State。禁止double参与金额，禁止先转long分计金额，禁止以重放重新确认同一笔权益或付款。

## 公开查询

提供empty、上述七个阶段动作、方案/快照/claim读取、declaredTotal、companyPayable、faceUnpaid、holderNetReceivable、toJson/fromJson。declaredTotal表示已固定权利面值，应付/应收按是否已除息确认派生，文档区分二者。

## 测试及验收

- [x] 仅测试先行的实际缺API RED并交回，原573无改动。
- [x] 授生产后专项全绿，真实库存/冻结/T+1/锁定/库存股、登记零应付、除息一次确认、转卖后归属、金额极值和ID碰撞均核验。
- [x] 全额清偿、缺/错证据、部分/超付/跨方案拒绝、减值不减债及付款转回有原事件链与反例。
- [x] 完整原事件重放先于当前时间/终态，配置时序与JSON恢复/不可变性核验。
- [x] Codex独立手算及真实Hermes独审，再标准GUI/CSV/JavaDoc、评分状态与运行记录更新。
- [x] 依赖PR18的AI-Generated草稿PR及实际CI，保留人工review门槛。

D0纯核已通过本地验收，发布状态见末尾实施与接续。完整现金分红继续需要D1估值收益、D2授权及D3运行桥的同批除息参考/真实付款/故障与旧档案验证。
## D0测试核对及接口裁决

真实Hermes20261106_145940_aeafaa交29项仅缺DividendModel的编译RED，75源前后hash相等。Codex保留原测试副本，修正ID碰撞夹具，其第二方案原来晚于自己登记/除息截止才创建。现两个方案都先在1000ms分别提案/授权/登记，再在2000ms分别确认。另补相同登记事件使用后来真实fill后账重放、晚登记/晚除息两项，合计31项测试。

- 采用测试交接中的Event、SchemeRequest、Scheme、RightsEntry、RightsSnapshot、Claim、Payment、Impairment、State及Result形状。发行组织由未来Runtime从issuerOwnerId的登记映射校验，D0只保存发行财产主体与公司账户。只存授权引用，当前不验证董事会真实性。
- SchemeRequest是未经受理的请求载体，金额精度及组合时序由propose统一返回业务拒绝，1/0.1精确规范到2位。已受理的Scheme及State各记录构造严格校验，禁止从JSON恢复非法形状。这一明确区分取代前文所有DTO静态形状一律构造拒绝的笼统要求。
- authorize可与recordAt同一模拟毫秒，但其已确认事件位置须先于register。register和bookEx必须恰在各自固定截止，不用迟到时的持仓替代历史。系统恢复后执行时仍传原到期毫秒。propose/authorize须不晚于recordAt；登记后取消仍拒绝。
- receipts只存已接受事件，准确保留action、Event三字段、原回报原因和规范requestJson。采用Receipt(eventId,position,atMillis,action,reason,requestJson)，无需恒真的accepted字段。只存SHA不能证明恢复后原请求，必须保留准确规范请求。被拒操作保持原State，其世界拒绝记录由后续Runtime保存。
- register的证券账是受信世界注入值，原业务请求仅含Event与schemeId。相同已确认事件重放先查原请求与回报，后来注入证券账不能改原权利。首次取权利存历史快照，其来源与实际世界截止由D3恢复交叉核对。不得把完整证券账复制到receipts。
- Payment.settlementId与Impairment.impairmentId直接采用原Event.eventId。付款/减值的历史位置、时点、证据、金额、明细必须与对应原receipt全部一致。每个方案及其原提案、授权、登记和除息都能由已确认receipts核对，不能靠最后余额看似自洽接受伪造状态。
- 其余正式共同语义及旧573不变，当前尚未授生产写权，核对后以新轮query实施。

## 实施与接续

测试先行、生产单文件、完整Event/原请求及阶段历史修复和金额规范化已完成。最终candidate3共76生产源，原75逐字节不改。43专项、组合616标准、课程CSV46成交/130订单/7排名及JavaFX启动、JavaDoc18:35:18、Codex独立82检查和13帧Decimal复算已通过。真实Hermes首稿62检查有4条恢复发现，已加入回归，最终hash独审进行中。发布及最终审查结果在本节追加，实际公司出款和完整M3仍待D1–D3。

最终真实Hermescandidate3独立重编译并核对76源hash，原62与新增28共90/90检查通过，原4条发现已消失且合法对照保留。只新增DividendModel，原75源逐字节相同。D0本地验收完成，当前建立依赖PR18的草稿PR，待实际CI及人工实质review。

分红纯核代码d2fb92607b24e5da893f6626bcd3525c52418cc3已建立[草稿PR #19](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/19)，base为codex/world-decision-audit（PR18）。远端verify run42204395506实际completed/success，已附当前会话，待人工实质review。下一阶段D1账户估值与时间加权收益纯核继续施工。
