# M4a.1 任职与四岗权限纯核（待执行）

依据总体基线§2/7/8及公司池研究§5，先做可复用的确定性访问判定，再接世界持久动作/上下文。此处不是四个自主LLM已经运行，也不是完整阅读/记忆实现。

## 范围与唯一资产

仅新增`world/RoleAccess.java`及相应测试，可在既有C盘隔离树复用施工；不改WorldRuntime/Board/Model/旧测试/config/CI/docs。没有I/O/时钟/余额写入。四岗共用OpeningRegistry里一个PRODUCT财产主体的已登记账户，不能为每个任职复制资金。

最小不可变数据：AccountBinding(accountId, ownerId)、ProductMandate(organizationPartyId, productOwnerId, accountIds)、Appointment(id, personPartyId, organizationPartyId, role, startsAt, endsAt, revokedAt)、State。State仅保存身份/授权元数据，开局验证用OpeningRegistry及账户引用表，不存现金。产品与组织自有财产分别解析，不从同名字符串猜身份。各集合防御复制、JSON往返稳定、公共API中文JavaDoc，复用现有Jackson，不添依赖。

## 固定权限语义

角色仅RESEARCHER、PORTFOLIO_MANAGER、RISK_OFFICER、EXECUTION_TRADER，不预埋尚无执行的任免/公司董事/编辑等授权。四岗均可读本组织获授权的产品账户/组织档案；专属动作分别WRITE_RESEARCH、SET_PORTFOLIO_TARGET、ISSUE_RISK_HALT、EXECUTE_ORDER/CANCEL_ORDER。经理不能直接下单，研究员写报告不能改账，风控不能用文本越过引擎硬限制，交易员不能改投资目标。

权限判定必须同时指定可信personPartyId、组织、动作以及所需账户范围，不能让调用方传isGod/owner=true。角色授权只覆盖mandate明确账户；同一机构另一个产品、雇主自有账户、别的机构产品、别人的个人账户都不能顺带读写。个人账户只允许对应PERSON财产owner绑定的人物查看；个人证券交易入口留未来工具层，不把PRODUCT ownerId误当人物。

开局productOwner必须PRODUCT、组织是ORGANIZATION、账户存在且归该产品、每一产品首版仅一个管理组织（拒绝冲突）。这是固定管理关系，动态更换管理人另做；产品下可以有多个显式账户，不允许owner通配授予未列账户。

任职区间`[startsAt,endsAt)`且startsAt<endsAt，人物必须PERSON、组织必须登记。保留原始期限及可空revokedAt；撤职不删除任职、不改原person/org/role/期限/ID。有效期结束或撤职当刻即无新系统访问。再次撤销明确拒绝；ID历史唯一不复用。任职建立/撤销是受信管理夹具，不开放任何角色给自己加权限。

为保留同机构四岗独立，拒绝同人物在同组织同时兼任不同四岗、同岗任职有效区间重叠，以及同组织同岗的重叠占位；离任后可由新人接任，未来不重叠区间可预排。初版不做兼职/代理任职例外；如需例外另升规则版本，不能默默合并四个上下文。

提供纯open/appoint/revoke/authorize及组织档案/个人账户查看判定即可，不造全局服务注册器或RBAC框架。now一律非负；appoint时起点不得早于now，revoke以now记录且不能在任职开始前事后改期；authorize只能按明确now判定，不读取本机时间。外部返回拒绝用NOT_AUTHORIZED即可，避免把未知账户/秘密任职存在性泄给普通角色；原始管理State仍仅上帝可用。

## 先RED验收

- [ ] 身份/产品/账户/mandate错误开局拒绝；公司财产owner与组织partyId可不同，产品不伪造party映射。
- [ ] 四个不同人物的能力矩阵正确，经理不能EXECUTE、研究员不能改target、交易员不能RISK_HALT；只有同一有限产品资产引用，无余额/现金复制。
- [ ] start含/end不含；撤职当刻拒绝、原任职记录保留，新人仅获授权目录不克隆前任经历（本模块不实现记忆，因此不得宣称已证明其全部行为）。
- [ ] 跨产品/跨机构/雇主自有账户/未列同产品第二账户一律拒绝；PERSON账户只本人读；unknown也不暴露存在性。
- [ ] 兼岗/同岗占位/区间重叠与历史ID复用拒绝，撤职后不重叠继任可用；过期和负时间不产生权限。
- [ ] 集合深不可变、JSON确定；定向/隔离全套/Javadoc，私有错误副本证实撤职/跨产品/经理下单等断言会失败。

后续M4a.2将此判定接入唯一世界日志，在动作完成时重新检查任职版本；不能因工具层曾通过一次就永久持有产品交易权。信息目录/全文阅读完成/个人经历属于后续单元，不用权限元数据伪装已经实现六层记忆。
