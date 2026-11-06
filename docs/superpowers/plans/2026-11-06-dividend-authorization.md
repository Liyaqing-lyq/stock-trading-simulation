# M3d D2 董事会现金分红授权纯核

依赖D1估值与表现纯核发布及实际CI，启工前核对最终提交。依据总体设计基线第4/5.2节，复用现有五席董事会与投票流程。授权的经济内容固定，实际公司出款沿D3实施。

## 范围与兼容

- 生产核心修改BoardModel.java，新增BoardModelDividendTest.java。旧664项测试保留，DividendModel、D1两纯核、制造与交易核心不改。先测试RED，再明确授生产。
- State继续保留原5个record分量。现有测试明确核对该形状，提案与授权结果作为所属Meeting的可选元数据，不新增平行治理索引。原准备稿向State加两集合的建议撤销。
- Meeting最后增加dividendProposal及dividendResolution两个可选分量，保留原14参构造重载。仅这两个分量使用NON_NULL序列化，旧null字段的写法保持，旧合法JSON逐字往返。copyWith统一携带新元数据，逐处核对其它new Meeting调用。
- 真实旧BoardModel生成的开局、预算及评审JSON与已编译旧客户端由只读准备取得，保存原字节及hash。新实现使用这些固定证据做合法旧数据与二进制构造对照，不能用新实现生成预期再验证自己。
- 从旧规则档案恢复时不得偷偷启用新治理议题。若实际反例证明需要Runtime能力闸，本单允许WorldRuntime.java加入仅拒绝现有档案出现新分红治理数据的窄校验，并补回归。D3才启用新档案及完整运行桥，当前不排程分红。
- BoardModel.fromJson继续一个入口，严格拒绝重复键、尾随、整数浮点截断与null原语。旧Meeting缺两个可选字段合法，禁止开启使旧合法JSON缺新可选字段也失败的全局FAIL_ON_MISSING_CREATOR_PROPERTIES。新增DTO必填字段明确required。

## 新公开形状

```java
enum Topic { BUDGET, OPERATING_REVIEW, DIVIDEND_AUTHORIZATION }

record DividendProposal(DividendModel.SchemeRequest request, String proposerSeatId,
                        List<String> relatedSeatIds, long proposedAtMillis)
record DividendResolution(long issuedAtMillis, String authorizationRef)

// Meeting保留前14分量，最后追加两个可选元数据。
record Meeting(..., String failureReason,
               DividendProposal dividendProposal, DividendResolution dividendResolution)

static Outcome proposeDividendAuthorization(State state, long now, String meetingId,
        String proposerSeatId, List<String> relatedSeatIds, DividendModel.SchemeRequest request)
static Outcome executeAdoptedDividendResolution(State state, long now, String meetingId)
static boolean verifiesDividendAuthorization(State state, String authorizationRef,
        DividendModel.SchemeRequest request)
```

结果沿用Outcome，不引入另一份现金或分红状态。查询通过所属Meeting访问元数据。SchemeRequest为D0的原始载体，本单受理后须严格规范每股金额2位、正值及recordAt<exAt<=payAt，复用D0已接受Scheme的校验约束。所有新增公共类型、构造及方法有中文Javadoc。

## 会议与关联回避

- 复用convene、addMaterial、freezeMaterials、castVote、closeVoting。startVoting按明确议题选择预算或分红提案，OPERATING_REVIEW继续原评审路径。预算实施入口仍只实施预算，特别预算门槛4票继续保留。
- 分红固定五席、法定人数3、至少3赞成。关联回避不降低任一门槛。提案人只需是章程席位，提案职责本身不自动构成利益关联。
- relatedSeatIds为可信场景提供的实际关联事实，均须是章程席位。关联且到会者必须在recusedSeatIds中，已缺席者无投票资格，不要求为了回避而先到会。回避者不能投票，剩余有效到会少于3只能延期。
- 提案在CONVENED、材料截止前且不早于会议创建，首次提交后不可覆盖。会议closesAt不晚于方案recordAt，授权实施不早于表决截止且不晚于recordAt。材料与选票沿现有时间及引用规则。
- 新会议刚召集时允许无提案，CONVENED/MATERIALS_FROZEN允许0或1。VOTING/ADOPTED/IMPLEMENTED要求准确提案，空提案到期形成明确REJECTED。新议题的预算行为空，旧议题不能携带分红元数据。
- 同schemeId经济内容不可改。被否决/延期后可在新会议以同经济内容重审，已有未终结提案时拒绝并行重审，每scheme最多一个已实施授权。不同金额或时点须新schemeId。

## 决议、引用与恢复

- closeVoting从原选票产生ADOPTED，仅表示表决通过。executeAdoptedDividendResolution再次核对实际章程、有效到会、逐席选票及通过条件，生成授权并把该会议标IMPLEMENTED，不改变预算、钱股或DividendModel。
- issuedAtMillis只表示此次授权生成时点，不冒称实际closeVoting提交时点。纯核没有WorldJournal位置，不虚构position字段。D3将授权生成与世界批次、D0授权和公告关联。
- 引用采用board-dividend-v1域及SHA256，规范源包含完整方案经济内容、公司及章程席位、会议标识/主席/关键时点、到会/回避/关联事实、原材料引用、逐席选票及issuedAt。选票插入顺序不影响结果，同赞成数但不同实际选票不能伪装同一来源。采用无歧义的规范JSON或等价结构化编码，不能用未转义分隔符拼接身份。
- 不复制一份可独立改写的材料或选票表，原事实仍在Meeting/Charter。引用计算排除自身authorizationRef，并在ADOPTED→IMPLEMENTED时采用稳定的决议源字段。
- verifiesDividendAuthorization只有在实际IMPLEMENTED、方案逐字段相同、授权引用及实际通过来源均核对时为true。既有合法授权在以后支付日仍可核验，不因支付日已晚于recordAt而过期。生成授权的issuedAt本身必须及时。
- Meeting/State构造及fromJson核对新议题所有引用、时间、阶段与实票结果，拒绝伪造通过/空授权、孤儿元数据、非成员票/回避票、错误方案及变造来源。仅重签一个自洽SHA不能替代重算真实通过条件。
- 新治理事实及同方案经济保持深不可变。旧预算、承诺和考核操作必须保留新议题历史，新分红操作也不能改旧预算或制造经营结果。外部发行owner/公司账户/证券发行组织的真实映射由D3按OpeningRegistry再次核对。

## 验收顺序

- [x] 固定真实旧JSON、旧编译客户端及hash，先缺能力RED，旧78生产源未动。
- [x] 正常3票授权、2票否决、回避不足3延期、非关联提案人可投、缺席关联者不要求到会、非成员/回避/重复票拒绝。
- [x] 提案/材料/投票/关会/授权各时界及Long最大合法时间，空提案到期闭合，否决后可重审，同方案改经济/并行或重复实施拒绝。
- [x] 原授权引用绑定全部实际来源，材料/席位/选票/经济/时间篡改反例与合法对照，JSON严格、旧JSON逐字、旧构造源码及已编译客户端兼容。
- [x] 旧预算普通/特别门槛及绝对预算、承诺、完整/缺项评审均保持，新旧事项交替操作不丢历史。旧规则档案不因新议题而静默启用分红。
- [ ] Codex独立取证与真实Hermes独审、标准GUI/CSV/JavaDoc、评分映射及运行记录，AI-Generated草稿PR和实际CI，待人工实质review。

D2只完成董事会授权纯核。唯一WorldJournal同批授权/公告、截止登记、除息参考与应收、真实足额公司派息及恢复由D3接续。

## 真实旧数据基线

父D1代码dc90a607bbac4c6a330691f0d35dccc8a861c279已建立草稿PR20，启工前等待其实际CI。旧Board源码3807eb0d2baaf22cd4b989aa79ad8710acc58f110bd9151dc39319bc68f9b434由冻结旧二进制真实运行得到三份golden。测试资源固定为src/test/resources/world/board-legacy-open.json、board-legacy-budget.json、board-legacy-review.json，分别289、956、1395字节。SHA依次f37df787e8c1b60431bc461c0bb2a423421e6e2336c664213efe2b171e2a9e38、9af96be6b4a94188a57c8d3790727d084020a0e9153c6441b11db00ee98481f4、8b4147e4c229d320a5c836ceba7ee8f5e59bfc5a1029a478f23fc7976240cb50。

真实预算500.00、占用300.00、已花100.00、释放200.00，剩可用400.00。完整评审60/70/80/50/80按冻结35/25/15/15/10得66.00。Root Decimal已独立复算。LegacyBoardClient及LegacyBoardConstructors都按旧classes编译，后者直接执行原5参State/14参Meeting构造并与956字节golden相等。两份旧class将原样用于新实现的二进制兼容检查，不重新编译伪装旧客户端。证据dividend-board-preparation/legacy。

D1代码verify run42209352373已实际completed/success，PR20已附会话。D2现进入测试先行，当前生产源码由Root独占，先明确授新测试后再授BoardModel实现。

## 测试核对及实现裁决

真实Hermes aeafaa交26项仅缺API的编译RED，78生产源及旧664测试前后hash相同。Codex保留原测试副本，修正authorize夹具原来未召集会议或重复提交已有提案、否决后重审沿用过期材料截止、新旧事项交替时点倒退三类问题。空提案的startVoting检查移到已冻结阶段，三票换投票人的对照使用完全相同的到会名单，避免其它字段变化代替真正票源差异。

同一方案已实施后，新的重复提案在propose入口即拒绝，重复execute也拒绝。原稿让已授权方案再次走完整表决到execute才拒绝的冗余路径撤销。相同经济内容的否决/延期重审继续合法。同schemeId经济不可改用例在前一场已否决后测试，消除并行保护遮住经济保护的混淆。

新增raw请求非法金额/精度/时序业务拒绝与精确规范化、未知提案人/关联席位、真实旧完整评审不丢失、关联事实深不可变四项，共30项，当前仍仅缺API的编译RED。proposeDividendAuthorization接收D0原始请求载体，非法金融组合返回accepted=false及原State，已受理的DividendProposal静态构造严格校验。缺API编译RED只证明尚无实现，实际专项绿及旧694组合目标仍待生产轮实跑。

D1最终文档32b71f09941bbb96d673b8178e2b9518abcaaebb的verify run42209688311也已实际completed/success。下一明确只授BoardModel.java及新BoardModelDividendTest，Runtime的任何能力闸要先由Root取得具体反例再单独修复，当前不授Runtime写权。

## D2 Root恢复核验与写权接回

作者29119已退出释放，Root独占BoardModel/测试/Runtime/Maven。首稿candidate1的Root私有完整重签探针实际10失败，新增12正式测试取得42专项中10失败，缺字段合法0提案对照与重签合法对照通过。已修新Topic时序、主席/成员/回避、票/材料时间及阶段、及时授权、DTO必填、已实施和活动方案互斥。真实Hermes首稿独审38合法全过、forge16/20，报告三类时间缺口，已退出；需最终freeze复验。原77源首稿相同。

Root以旧D1二进制实际生成治理日志，15帧前缀legal按新D2恢复BUDGET，首帧只改Topic并重签可恢复DIVIDEND_AUTHORIZATION，而旧二进制拒枚举。已新增Runtime回归取得真实RED，按正式Plan授权加入schema<=8的窄分红议题能力闸。旧664断言未改，仅在既有RuntimeGovernanceTest新增一项。当前组合目标707=664+42+1，尚未最终标准验收。证据dividend-board/{root-probe-1,root-regression-red,legacy-gate-*-2,root-runtime-red}.log，首轮私有脚本事件名错误证据保留，正确类型STATE_SNAPSHOT_V1的第二轮控制已成立。

## D2最终本地验收

最终candidate2冻结78源，BoardModel815c8e7f...a09951e及Runtimeee0817b1...987a92c0，其余76生产源与D1逐字节相等。新47专项中14失败1错误的首稿RED已真实取得并修复，另1旧档能力闸恢复RED已修。最终712标准、52成交133订单7排名CSV、GUI、JavaDoc20:14:36通过。Root13分红+146世界+72审计检查，原编译旧客户端及旧构造、三golden逐字往返通过。真实Hermes最终独立重编74/74及旧档合法/伪造对照通过，已退出释放。当前Root独占源码/Maven，准备草稿PR，仍待人工实质review。D3从制造报表与除息参考接缝开始，随后完整新档案桥，不把纯核误记为真实付款。
