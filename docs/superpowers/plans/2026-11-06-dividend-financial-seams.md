# M3d D3a 分红财务及除息参考接缝

依据总体设计基线§4–6及D0/D1/D2已验收契约。D3先分为D3a纯核接缝、D3b同批运行桥、D3c资本流及运行表现，逐段独立验证。三段共同完成现金分红运行闭环，阶段状态分别记录。

## 范围与源事实

- 本单仅修改ManufacturingModel与ValuationModel，新增ManufacturingDividendTest和DividendReferencePriceTest。原712测试及其断言保持。先真实缺API RED，再授权两生产文件。
- 原制造报表以初始权益加经营利润为权益，公司真实派息100后会出现资产1900、负债0、权益2000的不平衡，实际旧纯核probe已复现。接缝使宣告和清偿分别反映债务/权益及现金变化，经营利润口径保持。
- 除息参考由已确认D0方案和实际成交流水派生。D0记录仍为唯一分红元数据，原证券账、现金账及成交链继续作为源。
- 当前规则档案、WorldState、BoardModel、DividendModel、角色视图及Runtime均保持。实际授权消费、公告、排程、出款及持久化接入由D3b实施。

## 制造报表公开契约

```java
record DividendPosition(BigDecimal declaredFace, BigDecimal unpaidFace) {
    static DividendPosition none();
}
static Report report(State state, Map<String, CashAccount> accounts, DividendPosition dividends);
static Report validate(State state, Map<String, CashAccount> accounts, DividendPosition dividends);
```

- 两金额精确规范2位，均非负，unpaidFace≤declaredFace。null及无法精确到2位的金额拒绝。none为0.00/0.00。
- declaredFace为公司已到EX_BOOKED或SETTLED的累计宣告面值，unpaidFace为对应合同未付面值。D3b按发行主体从D0派生，登记阶段两者均为零，持有人减值不减少公司合同未付面值。
- totalLiabilities等于原经营应付加unpaidFace。equity等于initialEquity加profit减declaredFace。资产、经营费用、利润、现金及物量计算沿用原规则。
- Report原record分量、构造与序列化形状保持。expensePayables字段继续仅含原经营应付。公司分红义务在totalLiabilities体现。
- report继续返回实际balanced结果，validate遇真实资产负债失衡或世界现金不守恒继续抛异常。declaredFace可导致负权益，按真实金额表达。
- 旧report/validate两参入口调用none，旧输出逐字等价。不得为了派息放宽原守恒式或在制造State中再存一份分红账。

## 除息参考公开契约

```java
enum PriceSource { TRADE, OPENING_REFERENCE, EX_DIVIDEND_REFERENCE, MISSING }
static PriceBook prices(long position, long cutoffMillis, WorldExchange.State exchange,
        OpeningRegistry registry, Collection<String> symbols, DividendModel.State dividends);
```

- 原五参prices保持原始成交/开局参考行为，旧调用方继续原口径。新重载显式接收同一截止D0状态，null拒绝。
- 先核对位置/时间非负、D0最后确认位置及时点不晚于查询截止。已受理方案证券与发行财产主体须映射到登记中的同一组织。仅此入口可得的引用进行校验，付款账户实际归属由value及D3b核对。
- 各标的先按原timeMillis/tradeId选择最后实际成交，否则使用明确开局参考。未知标的保持MISSING。
- 仅扣phase为EX_BOOKED或SETTLED且baseTime<exAt≤cutoff的每股金额总和。无成交时baseTime=0。同一exAt、不同方案各自计入，一方案即使已付款也只扣一次。
- 成交恰在exAt时按除息后的实际成交处理，不再扣该次股息。D3b必须用RULES除息先于COMPLETION订单的真实阶段及截止位置保证此前提。
- 有扣减且结果非负时source=EX_DIVIDEND_REFERENCE，asOf为参与扣减的最大exAt，age=cutoff−asOf。零价合法，扣后负价为MISSING。未扣减时保留原来源、时点及陈旧度。
- 除息参考保持独立来源，exchange.trades、真实成交价及K线源保持。已有持仓估值使用该新价格簿和同一D0债权，避免红利到账重复收益。

## 执行及验证

1. 真实Hermes仅写两新测试，取得缺API编译RED。Root核对夹具与已确认底层源，确认原78生产源保持。
2. 明确授权两生产及两测试文件，按旧行为复用最小修改。新增公共API中文Javadoc。专项、原712与两处真实变异后交回。
3. Root从冻结源独立编译，复算宣告前/未付/实付/减值下公司资产负债及投资者估值。检验多方案、成交前后/同刻、零价、缺价、未来状态及Long边界。
4. 真实Hermes独立复验，标准测试/CSV/GUI及JavaDoc，更新评分映射、架构/接口、运行记录与主Plan，建立AI-Generated草稿PR并等待真实CI。

## 验收清单

- [x] 原制造报表和原五参价格入口保持，旧Report形状与金额/异常约束通过。
- [x] 未付分红增加合同负债并减少权益，付款减少现金与合同未付，利润及世界现金守恒保持。
- [x] D0真实方案阶段、减值、多个方案、实际成交链与价格来源的边界检查通过。
- [x] 除息应收和参考价同一输入形成投资者估值，付款只改变资产形态，源码不写现金。
- [x] 真实RED/变异、独立检查、标准验证、文档、草稿PR和实际CI证据齐备。

D3b继续启用完整新档案，在唯一WorldJournal批次消费D2授权、发布可读公开公告、截止登记及除息确认、足额真实付款并恢复。D3c连接实际资本流和按确认历史派生的TWR。

## D3a测试核对及生产裁决

作者29项缺API RED及78源未改证据已交。Root保存两原测试于dividend-financial/root-test-review。两个多方案夹具原先先完成S1除息再回到更早登记时点propose S2，违反D0时间单调。改成同一时序中先提案授权两个方案、同截止分别登记、再依次除息，保持生产规则。Root抽取修订测试的真实底层helper，在D2冻结classes独立编译运行，两方案、四个实际成交及D0清偿/减值均通过，fixture-run.log。

新增成交位于两次除息之间只扣后一次、PROPOSED/AUTHORIZED/CANCELLED不调参考及null D0拒绝三项，共32，testcompile仍仅缺新API。制造新validate三参必须返回Report，与旧签名和正式Plan一致，作者handoff把它写成void属于交接笔误，测试本身已要求Report。null DividendPosition或新prices的D0入参采用明确IllegalArgumentException，精确金额约束沿CashLedger.money，旧行为保持。

现在可正式只授ManufacturingModel、ValuationModel和两新测试及C Maven。原其余76生产源及旧712测试保持。目标744组合与32专项，Runtime/档案/角色不在本单施工。D2文档head d100a8a的verify42217584157亦已实际成功。

真实Hermes849817最终独审从candidate1核78源hash并独立重编，制造17及除息参考26项自写断言合计43/43通过，未发现可复现缺陷，报告dividend-financial-review-candidate-1/handoff.md。Root随后仅移除新测试Fixture的三个未使用分量，所有断言及生产源保持，重新运行clean标准744全绿，最终CSV为37成交、119订单、7排名，JavaDoc21:11:18重新生成。最终日志standard-root-final-1-raw.log及javadoc-root-final-1-raw.log，冻结standard-final-raw。先前49/128及21:02:42为首轮结果，保留原证据。D3a本地验收完成，当前发布草稿PR及等待实际CI。

财务及除息参考代码6220ed85275eda2826b17054ac570c32f01dadb9已建立[草稿PR #22](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/22)，base为codex/world-dividend-authorization（PR21）。实际verify run42223835571已completed/success，已附当前会话，待人工实质review。D3a验收及发布完成，下一D3b同批分红运行桥按正式施工单推进。
