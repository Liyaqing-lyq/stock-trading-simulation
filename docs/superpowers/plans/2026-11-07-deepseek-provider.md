# DeepSeek 有界 HTTP 适配器实施 Plan

依据：总体基线 §7/§9、五Key Web Plan；用户授权使用指定凭据并由Codex决定测试。依赖Web草稿PR25。只读准备不是已实现能力；正式阶段先把提供方协议稳定接入Java，再接版本化世界决定和Web调用记录。

## A. 确定的最小接口

新增一个web/DeepSeekClient.java和正式JUnit DeepSeekClientTest，不改原86生产/902测试、世界规则或pom依赖。仅JDK HttpClient/Jackson；公开构造固定官方https://api.deepseek.com/chat/completions、禁止重定向，同包测试构造只允许回环。请求为system/user文本、非流式、thinking disabled、JSON object、输出上限128..8192，总体墙钟<=60秒，请求64KiB/响应1MiB有界。未知新metadata兼容，已知字段严格类型、重复键和尾随JSON拒绝。

只有单choice index0/assistant/finish_reason=stop/非空白正文才完整；length/filter/tool_calls/empty不执行动作，保留已知usage。prompt/completion/total及缓存计数严格非负整数、总和核对，单个已知cache不得超过prompt；缺usage不能伪造0。错误分AUTH/RATE_LIMITED/SERVICE_UNAVAILABLE/REQUEST_REJECTED/TIMEOUT/NETWORK/PROTOCOL；不自动重试。

凭据Supplier只供Authorization，格式校验与Supplier异常固定不带cause；请求/结果toString不回显正文或提供方可控文本。响应metadata含本次凭据则丢弃，正文含本次凭据则整条拒绝并保留已知usage。真实输入不含其他密钥或管理员世界数据；本适配器不写档案、不执行经济行为。

总体期限覆盖响应头和完整body，使用有界BodySubscriber并取消超限订阅；不能只给header超时后无限读body。取消是本机行为，不保证服务端停止生成或不收费。预算与账单由后续版本化运行桥管理，绝不以0.00冒称真实成本。

## 验证顺序

1. 正式JUnit先RED，覆盖实际回环HTTP而非mock；非法参数不发请求、合法对照、空白/截断/非法usage、凭据反射、302、401/429/5xx、header/body迟到、超限、无重试。
2. 引入已独立核对的候选，专项及旧902全绿；Root独编边界检查与真实Hermes独审，标准GUI/CSV/JavaDoc，更新评分与docs/Plan，草稿PR及实际CI。
3. 本地通过后允许Root单次真实小试：仅key-1、deepseek-flash、固定非敏感JSON连通指令，输入UTF-8<=1024字节、max_tokens=128、thinking disabled、总墙钟15秒、最多一次应用层请求、无重试。只记录固定状态/实际usage/模型及耗时，不输出key或原响应。失败/不确定不得伪称成功，其他四槽保持未测试。费用按官方价格表只能估算并另标，不能当账单。
4. 下一阶段另定新profile与schema：保留旧scripted guard、决定输入权限/屏障/代次/恢复，预留预算与未知用量保守结算、场景冻结及Web调用审计。A阶段通过不等于已接世界。

## 已有私有证据与裁决

作者首稿61检查通过，Root独立7检查取得5失败（空白误完整、metadata反射凭据两项、单cache超prompt、非法key异常）。私有修订87检查，尚待Root最终复验与正式JUnit；不把私有运行计入生产测试数量。来源deepseek-adapter-preparation与web-root-production/adapter-edge-red.log。

官方接口于2026-11-07核读：[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[Models](https://api-docs.deepseek.com/api/list-models/)、[Pricing](https://api-docs.deepseek.com/quick_start/pricing/)。当前模型deepseek-flash和deepseek-v4-pro；max_tokens是输出上限，JSON模式空白仍须拒绝。价格会变，运行记录保存实际核读日期及估算性质。

- [x] 正式测试RED与生产实现。
- [x] 独立核验、旧回归与正式标准。
- [x] 有界真实小试及未测状态记录。
- [x] 文档/草稿PR/实际CI。
- [ ] 版本化世界运行桥（下一子阶段，不随HTTP适配器自动完成）。

## A 当前实际结果

正式新增DeepSeekClient及23个JUnit，原86生产/902测试保持。测试先缺类RED；Root发现测试夹具未正确引号导致反例可能假通过，纠正为JSON节点生成、合法对照及资源关闭后，实际取得3行为失败（cache类型、index整数截断、测试端点）。生产最小修复后925标准全绿，CSV47成交126订单7排名、GUI实际启动、JavaDoc03:27:17通过。冻结hash6436d61e…a2cbc，Root独编10检查通过。

独审初版把坏cache当null、index反例漏usage，Root明确拒收对应结论；Root私有纠正探针42项在正式候选全过、旧候选9项失败。真实Hermes按合法usage基底最终独立复核同样42/42，旧候选33/42；保留每轮原证据，不将假覆盖计作完成。

2026-11-07 03:30:15上海时间，Root单次key-1/flash连通完成，输入46、输出5、总51（cache hit0/miss46），931毫秒，预期JSON校验正确；128输出上限、15秒总体期限、只一次请求且无重试。固定CREATE_NEW尝试标记防重发；原文和凭据未输出/归档。实际账单未知null，其余key-2..5未调用。世界尚未接模型，connected=false保持真实。

有界提供方代码bd07ba0d6ae636c32c968e67b3531b52453b138d已建立草稿[PR26](https://github.com/Liyaqing-lyq/stock-trading-simulation/pull/26)，base为codex/world-web-workbench；实际[CI42280670472](https://github.com/Liyaqing-lyq/stock-trading-simulation/actions/runs/42280670472)已completed/success。A传输阶段验收完成，仍待人工review；下一按2026-11-07-live-decision-core.md实施现有DecisionModel的B1版本化用量/预算纯核，私有替代模型的62项不计正式覆盖。世界运行桥未连接，总体基线继续施工。
