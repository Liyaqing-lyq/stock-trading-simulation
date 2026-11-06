# M4f 认知接续：已有经历的有界回忆

依据总体基线§8、§14和记忆专题六层结构，先落已有经历的回忆边界，再接主观信念/信任/成熟经历习惯与版本化运行桥。不会将此小段当认知整体完成。

## Root实施裁决

1. 新增纯函数 `CognitionRecall`，只读取真实 `InformationModel.State` 及明确人物、模拟时点、Policy，不拥有第二份文档/经历状态，不修改旧JSON或Runtime。只有本人已完成阅读且发布时间/完成时间不晚于查询时点的经历可选；目录、未完成/取消/拒绝阅读、他人经历不得进入，离职已读经历保留。未来条目不影响本人可见计数、截断或排序。
2. Policy为明确实验参数：正halfLifeMillis、thresholdPermille 1..1000、maxItems1..100、maxContentChars1..1000000。无“现实最佳”默认值。分数1000按每完整半衰期整除2，到0停止；使用非负long差，不用浮点或超范围移位。先过滤阈值，再按分数降序、completedAt降序、docId/version/readId确定顺序。
3. 有界选择整条经历；正文不静默截断。每条内容成本精确定义为title.length+body.length+publicSourceDescription.length（UTF-16字符），用long计算总和。贪心按顺序装入，超单条预算可跳过后续较短条目；maxItems/内容预算不足明确标记budgetTruncated，计数只含本人截至时点的可回忆条目。阈值遗忘与预算截断分别计数，未读取材料不计。此预算只约束回忆正文，不冒称完整模型输入JSON/token预算已实现。
4. 返回不可变结果：selected列表中每项只含原ReadObservation及score；eligibleCount/forgottenCount/budgetTruncated/contentChars。保持原正文/引用/公开来源描述，不携带Document或internalSourceIds；更不导出其哈希或隐藏独立源数量。匿名同源审计留后续独立模型，不把后台真值变成角色可知。
5. 单条重读仍是实际新经历；不凭字符串相同假设同源，不把多个已读条目自动计为独立证据。没有人工创建虚假回忆或跨人物复制入口。
6. 本单不接世界、不改变模型input、不启用默认遗忘、不引入库。下一段单独裁决信念、来源四维、成熟证据和慢习惯，不能用未成熟结果、全知奖励或任意模型自述更新。

### Root源码核对后的重读裁决

现有InformationModel对同人物/doc/version只保留首次ReadObservation，但每次实际阅读完成都有独立ClosedReading。不能因此让遗忘后的重读永远无效。回忆内容继续引用原Observation，Item另外携带lastReadId/lastReadAt，来自同人物/doc/version、reason=COMPLETED且closedAt<=now的最后一条实际阅读，分数/排序时间用它。没有新建正文或虚假经历；旧ReadObservation原样不改。未来重读、取消、拒绝不刷新。直接调用回忆不是重新阅读，不刷新lastReadAt。实际重读会先再次花完整阅读工时，完成前仍保持先前衰减。相同doc/version选一条内容，但可审计此次重新获取的真实readId。本条替代第2条排序时间及第4条Item字段的首次完成时间口径。

## 验收

- [ ] 作者私有纯核及测试：真实InformationModel发布/开始/完成生成经历；未完成/他人/未来无泄漏、半衰期边界/极值、条数/正文预算、不可变、稳定顺序、离职经历/原始审计不变。
- [ ] Root独立反例和真实RED/GREEN，正式集成后标准套件/CSV/GUI/JavaDoc、评分与验证记录。
- [ ] 真实Hermes独审、草稿PR/CI，之后接认知状态与版本化桥。

Root拥有主树/Maven。作者只在logs/implementation-20261106-continued/cognition-recall-author复制源/测试/编译；不读真实key，不调用供应商，不改其它目录。不能把私有结果或纯核编译当Web已用认知。

## 纯核本地验收

- [x] Root14独立检查原实现2真实失败，重读修复后全过；作者14专项正式集成。
- [x] 1099标准/45成交119订单7排名/GUI/JavaDoc07:06:15通过。
- [x] 真实Hermes d39b13独立编译90源，95动态检查通过，未读作者或Root测试。
- [ ] 草稿PR/实际CI及人工review。
- [ ] 版本化运行桥、角色全文裁剪、实际Web认知链。

最终candidate2仅修类注释，将首次完成时间描述改为最近真实完成时间；可执行文本同独审candidate1，源码SHA ffa6de34…fc1b2。核对了原89源不改。整条正文预算、实际重读、多次获取审计和原Observation不变已验证；同分同刻决胜仅静态，长跑性能尚未测。整个认知状态/信任/习惯仍缺，参数无默认“现实最优”。
